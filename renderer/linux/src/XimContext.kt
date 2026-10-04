@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.LC_CTYPE
import platform.posix.getenv
import platform.posix.setlocale
import x11.Display
import x11.XCloseIM
import x11.XCreateIC
import x11.XDestroyIC
import x11.XFilterEvent
import x11.XFree
import x11.XGetICValues
import x11.XGetIMValues
import x11.XIC
import x11.XIM
import x11.XIMCallback
import x11.XIMPreeditCallbacks
import x11.XIMPreeditDrawCallbackStruct
import x11.XIMPreeditNone
import x11.XIMPreeditNothing
import x11.XIMStatusNone
import x11.XIMStatusNothing
import x11.XIMStyles
import x11.XNClientWindow
import x11.XNFilterEvents
import x11.XNFocusWindow
import x11.XNInputStyle
import x11.XNPreeditAttributes
import x11.XNPreeditCaretCallback
import x11.XNPreeditDoneCallback
import x11.XNPreeditDrawCallback
import x11.XNPreeditStartCallback
import x11.XNQueryInputStyle
import x11.XNSpotLocation
import x11.XOpenIM
import x11.XPoint
import x11.XSetICFocus
import x11.XSetICValues
import x11.XSetLocaleModifiers
import x11.XSupportsLocale
import x11.XUnsetICFocus
import x11.XVaCreateNestedList
import x11.Xutf8LookupString
import x11.Xutf8ResetIC
import x11.XEvent
import x11.Window

/**
 * The connection to whatever input method this desktop runs, through XIM.
 *
 * XIM is the one door every X11 input method keeps open: ibus and fcitx5 both answer it
 * (ibus through the XIM server its daemon starts with `--xim`, which the GNOME session
 * does, and fcitx5 through its `xim` addon), and so does anything older. A window reached
 * through XWayland uses it the same way, which is why this is the baseline. The two daemons'
 * own D-Bus input context protocols are different from each other and neither is stable
 * across versions, so a second route per daemon would triple what has to be kept working
 * for the same Korean input.
 *
 * Every call here is Xlib's, made on the thread that reads the display server, and so are
 * the callbacks it makes back: they arrive from inside [filter]. They are the C ABI's
 * function pointers, which is the one place a Kotlin lambda cannot be handed over, so they
 * are non-capturing and find their state through the pointer the client asked to be called
 * back with.
 *
 * Open fails softly. A machine with no input method, or one this locale cannot talk to,
 * answers null and the window types as it did before.
 */
internal class XimContext private constructor(
    private val display: CPointer<Display>,
    private val method: XIM,
    private val context: XIC,
    private val state: StableRef<ImeSession>,
    private val callbacks: List<CPointer<XIMCallback>>,
    /** The event mask the input method asked the window to select, to be added to its own. */
    val filterMask: Long,
    /** Whether composition is drawn in the field, which is what the preedit callbacks give. */
    val inline: Boolean,
) {
    private var focused = false

    /**
     * Gives the input method the event first. True when it took it, in which case the event
     * is the input method's and is not the window's to handle.
     *
     * Asked of every event, not only keys: the protocol's own messages arrive as ordinary
     * events, and one left unfiltered is a conversation the input method never finishes.
     */
    fun filter(event: XEvent): Boolean = XFilterEvent(event.ptr, 0uL) != 0

    /**
     * What the event types, as UTF-8: the characters of a key press, or the text an input
     * method committed. Empty for a key that types nothing.
     */
    fun lookup(event: XEvent): String {
        var capacity = LOOKUP_BYTES
        var attempts = 0
        while (attempts < 2) {
            attempts += 1
            var needed = 0
            val text = memScoped {
                val buffer = allocArray<ByteVar>(capacity)
                val keysym = alloc<ULongVar>()
                val status = alloc<IntVar>()
                val count = Xutf8LookupString(
                    context, event.xkey.ptr, buffer, capacity - 1, keysym.ptr, status.ptr,
                )
                if (status.value == X_BUFFER_OVERFLOW) {
                    // The call says how long the answer is. Asked again with room for it.
                    needed = count + 1
                    return@memScoped null
                }
                if (count <= 0 || status.value == X_LOOKUP_NONE || status.value == X_LOOKUP_KEYSYM) {
                    return@memScoped ""
                }
                buffer[count] = 0
                buffer.toKString()
            }
            if (text != null) return text
            capacity = needed
        }
        return ""
    }

    /**
     * Ends the composition where it stands and answers what was being composed, which the
     * caller keeps. Empty where there was nothing, and the input method may call the preedit
     * callbacks from inside this to say it is done.
     */
    fun reset(): String {
        val raw = Xutf8ResetIC(context) ?: return ""
        val text = raw.toKString()
        XFree(raw)
        return text
    }

    /** The input method should be heard: this window has the keyboard and a field wants text. */
    fun focus(wanted: Boolean) {
        if (wanted == focused) return
        focused = wanted
        if (wanted) XSetICFocus(context) else XUnsetICFocus(context)
    }

    /** Tells the input method where the caret is, so that its candidate window follows it. */
    fun spot(x: Int, y: Int) {
        memScoped {
            val point = alloc<XPoint>()
            point.x = x.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            point.y = y.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            val list = XVaCreateNestedList(0, XNSpotLocation.cstr.ptr, point.ptr, null)
            XSetICValues(context, XNPreeditAttributes.cstr.ptr, list, null)
            if (list != null) XFree(list)
        }
    }

    fun close() {
        XDestroyIC(context)
        XCloseIM(method)
        for (callback in callbacks) nativeHeap.free(callback.rawValue)
        state.dispose()
    }

    companion object {
        /**
         * Opens the input method, or answers null where there is none to talk to.
         *
         * The locale is what decides it. XIM converts text into the encoding the process's
         * locale names, so a process in the C locale gets ASCII from an input method that
         * was asked for Hangul. A UTF-8 locale is asked for, and a machine that has none is
         * not offered an input method that would hand back the wrong bytes.
         */
        fun open(
            display: CPointer<Display>,
            window: Window,
            session: ImeSession,
        ): XimContext? {
            failure = ""
            if (!chooseUtf8Locale()) return fails("no UTF-8 locale")
            if (XSupportsLocale() == 0) return fails("Xlib does not support this locale")
            XSetLocaleModifiers("")
            val method = XOpenIM(display, null, null, null)
                ?: return fails("XOpenIM found no input method (XMODIFIERS=${getenv("XMODIFIERS")?.toKString()})")
            val style = memScoped {
                val styles = alloc<kotlinx.cinterop.CPointerVar<XIMStyles>>()
                val failed = XGetIMValues(method, XNQueryInputStyle.cstr.ptr, styles.ptr, null)
                val found = styles.value
                if (failed != null || found == null) return@memScoped null
                val offered = ArrayList<Long>()
                val supported = found.pointed.supported_styles
                for (index in 0 until found.pointed.count_styles.toInt()) {
                    offered += supported!![index].toLong()
                }
                XFree(found)
                chooseInputStyle(
                    offered,
                    XIMPreeditCallbacks.toLong(),
                    XIMPreeditNothing.toLong(),
                    XIMPreeditNone.toLong(),
                    XIMStatusNothing.toLong(),
                    XIMStatusNone.toLong(),
                )
            }
            if (style == null) {
                XCloseIM(method)
                return fails("the input method offers no usable input style")
            }
            val inline = style and XIMPreeditCallbacks.toLong() != 0L
            val state = StableRef.create(session)
            val callbacks = ArrayList<CPointer<XIMCallback>>()
            fun callback(function: COpaquePointer): CPointer<XIMCallback> {
                val allocated = nativeHeap.alloc<XIMCallback>()
                allocated.client_data = state.asCPointer().reinterpret()
                allocated.callback = function.reinterpret()
                callbacks += allocated.ptr
                return allocated.ptr
            }
            val context = memScoped {
                val preedit = if (inline) {
                    XVaCreateNestedList(
                        0,
                        XNPreeditStartCallback.cstr.ptr, callback(onPreeditStart),
                        XNPreeditDoneCallback.cstr.ptr, callback(onPreeditDone),
                        XNPreeditDrawCallback.cstr.ptr, callback(onPreeditDraw),
                        XNPreeditCaretCallback.cstr.ptr, callback(onPreeditCaret),
                        null,
                    )
                } else {
                    null
                }
                val created = if (preedit != null) {
                    XCreateIC(
                        method,
                        XNInputStyle.cstr.ptr, style,
                        XNClientWindow.cstr.ptr, window.toLong(),
                        XNFocusWindow.cstr.ptr, window.toLong(),
                        XNPreeditAttributes.cstr.ptr, preedit,
                        null,
                    )
                } else {
                    XCreateIC(
                        method,
                        XNInputStyle.cstr.ptr, style,
                        XNClientWindow.cstr.ptr, window.toLong(),
                        XNFocusWindow.cstr.ptr, window.toLong(),
                        null,
                    )
                }
                if (preedit != null) XFree(preedit)
                created
            }
            if (context == null) {
                XCloseIM(method)
                for (callback in callbacks) nativeHeap.free(callback.rawValue)
                state.dispose()
                return fails("XCreateIC failed")
            }
            // What the input method needs the window to select, over and above what it
            // already does. Left out, some of them never see the key they are meant to
            // filter and the keyboard types as if there were none.
            val mask = memScoped {
                val value = alloc<ULongVar>()
                XGetICValues(context, XNFilterEvents.cstr.ptr, value.ptr, null)
                value.value.toLong()
            }
            return XimContext(display, method, context, state, callbacks, mask, inline)
        }

        /**
         * Puts the process in a UTF-8 locale, if there is one to be had.
         *
         * The one the environment names comes first. A desktop running Korean sets one, and
         * what the reader typed is then in the encoding the rest of the session uses. A
         * machine with no locale set, a container or a test runner, is given `C.UTF-8`, which
         * every current C library has.
         */
        private fun chooseUtf8Locale(): Boolean {
            if (isUtf8Locale(setlocale(LC_CTYPE, "")?.toKString())) return true
            for (name in listOf("C.UTF-8", "en_US.UTF-8", "C.utf8")) {
                if (isUtf8Locale(setlocale(LC_CTYPE, name)?.toKString())) return true
            }
            return false
        }

        /** Why the last attempt to open an input method gave none, for the frame report. */
        var failure: String = ""
            private set

        private fun fails(reason: String): XimContext? {
            failure = reason
            return null
        }

        private const val LOOKUP_BYTES = 64

        // From Xlib.h: the status Xutf8LookupString writes.
        private const val X_BUFFER_OVERFLOW = -1
        private const val X_LOOKUP_NONE = 1
        private const val X_LOOKUP_KEYSYM = 3

        // The four callbacks. Each is told which session asked through the pointer it
        // registered, and reads what the input method passed from the call data.
        //
        // The start callback returns an int, which is the longest composition this client
        // will take, and -1 is "no limit". The declared type of the field says it returns
        // nothing, which is why the pointer is reinterpreted where it is stored: an input method reads the
        // return register, and one left holding whatever the caller last had there would
        // cut a syllable off at a length nobody chose.
        private val onPreeditStart: COpaquePointer =
            staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Int> { _, client, _ ->
                client?.asStableRef<ImeSession>()?.get()?.preeditStart()
                -1
            }

        private val onPreeditDone: COpaquePointer =
            staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { _, client, _ ->
                client?.asStableRef<ImeSession>()?.get()?.preeditDone()
            }

        private val onPreeditCaret: COpaquePointer =
            staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { _, _, _ ->
                // The caret inside the composition is not drawn: the field marks the whole
                // composition and puts its own caret after it.
            }

        private val onPreeditDraw: COpaquePointer =
            staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { _, client, data ->
                val session = client?.asStableRef<ImeSession>()?.get() ?: return@staticCFunction
                val draw = data?.reinterpret<XIMPreeditDrawCallbackStruct>()?.pointed
                    ?: return@staticCFunction
                session.preeditDraw(
                    first = draw.chg_first,
                    length = draw.chg_length,
                    text = draw.text?.pointed?.let { text ->
                        if (text.encoding_is_wchar != 0) {
                            // wchar_t is a signed 32-bit int on x86-64 Linux and unsigned on arm64, so
                            // cinterop types it differently per target; both are 32-bit code points.
                            wideString(text.string.wide_char?.reinterpret<IntVar>(), text.length.toInt())
                        } else {
                            text.string.multi_byte?.toKString() ?: ""
                        }
                    } ?: "",
                    caret = draw.caret,
                )
            }
    }
}

/** A wide string, which on Linux is one 32-bit code point per character. */
private fun wideString(characters: CPointer<IntVar>?, length: Int): String {
    if (characters == null) return ""
    val points = ArrayList<Int>(length)
    for (index in 0 until length) points += characters[index]
    return buildString { for (point in points) appendPoint(point) }
}
