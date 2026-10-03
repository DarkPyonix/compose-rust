package dev.darkpyonix.composerust.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.HostEvent
import kotlin.math.pow

// How large the renderer draws, the way an editor zooms.
//
// Two things make a screen larger. The operating system has a text size (`os`): Windows'
// Text size, GNOME's text scaling factor, KDE's font DPI, a phone's font size. And the
// application has a zoom level (`level`), a whole number from -8 to 8 that the reader steps
// with Command or Control and plus, minus or zero, each step a factor of 1.2. The whole
// factor is `k = os × 1.2^level`.
//
// Native widgets are drawn with `Density(density × app, fontScale = os)`: the level scales
// everything, the way a zoomed editor does, and the system's text size scales text, the way
// every native application on the platform does. [zoomedDensity] is the one place that
// Density is made, so anything that draws at another factor (an HTML island drawn at `k`
// with no font scale, a measurement made at the same factor) builds its Density beside
// this one rather than working the factor out again.
//
// Shared by every renderer through a symlink. The desktop windows hold a [Zoom] of their own
// and give their scene its Density; a renderer that sits inside a platform's own Compose
// host (a phone, the web) gets one made here and applied around the content.

const val MIN_ZOOM_LEVEL = -8
const val MAX_ZOOM_LEVEL = 8

/** The factor one zoom step multiplies by, the same step an editor takes. */
const val ZOOM_STEP = 1.2f

/** `1.2^level`: the application's share of the factor. */
fun appZoomScale(level: Int): Float = ZOOM_STEP.pow(level.coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL))

/**
 * The Density native widgets are drawn with: the display's scale times the application's
 * zoom, and the system's text size as the font scale.
 */
fun zoomedDensity(displayScale: Float, app: Float, os: Float): Density =
    Density(displayScale * app, sanitizeOsTextScale(os))

/**
 * Keeps a text size a platform reported inside what text can be drawn at.
 *
 * A setting that is missing, unreadable or nonsense is read as the default rather than
 * drawn: a zero would lay every line out at no height, and a hundred is a misread setting
 * rather than anyone's choice. The bounds are wider than any platform offers.
 */
fun sanitizeOsTextScale(value: Float): Float = when {
    value.isNaN() || value <= 0f -> 1f
    value < MIN_OS_TEXT_SCALE -> MIN_OS_TEXT_SCALE
    value > MAX_OS_TEXT_SCALE -> MAX_OS_TEXT_SCALE
    else -> value
}

const val MIN_OS_TEXT_SCALE = 0.5f
const val MAX_OS_TEXT_SCALE = 4f

/** What one of the zoom shortcuts asks for. */
enum class ZoomShortcut { In, Out, Reset }

/**
 * Where the application's zoom level is kept between runs.
 *
 * One per application, in the platform's own settings store: the user defaults on Apple
 * platforms, a file under the application's configuration directory on Windows and Linux,
 * the application's preferences on Android, local storage on the web.
 */
interface ZoomLevelStore {
    /** The level saved last time, or null where none was. */
    fun load(): Int?

    fun save(level: Int)

    /** Remembers nothing: every run starts at level zero. */
    object None : ZoomLevelStore {
        override fun load(): Int? = null
        override fun save(level: Int) {}
    }
}

/**
 * The store a renderer that sits inside a platform's own Compose host saves its level in.
 *
 * Set by the platform's entry point before the content is composed; a desktop window makes
 * its own [Zoom] with its own store and does not read this.
 */
var platformZoomLevelStore: () -> ZoomLevelStore = { ZoomLevelStore.None }

/** A level kept inside -8..8. */
fun clampZoomLevel(level: Int): Int = level.coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)

/**
 * The zoom one window is drawn at.
 *
 * Snapshot state, so composition that reads it is told when it changes. The window asks it
 * once a turn whether anything changed ([refresh]); the reader's keys and the application's
 * level both arrive through [setLevel] or [step].
 *
 * @param osSource The operating system's text size now. Asked once when this is made and
 *   again on every [refresh], so it has to be cheap: each platform's source keeps its last
 *   answer and goes back to the system only when the system says something changed.
 * @param store Where the level is kept between runs. Read once, here.
 */
class Zoom(
    private val osSource: () -> Float = { 1f },
    private val store: ZoomLevelStore = ZoomLevelStore.None,
) {
    /** The operating system's text size, where 1 is the default. */
    var os: Float by mutableFloatStateOf(sanitizeOsTextScale(osSource()))
        private set

    /** The application's zoom level, from -8 to 8. */
    var level: Int by mutableIntStateOf(clampZoomLevel(store.load() ?: 0))
        private set

    /** `1.2^level`. */
    val app: Float get() = appZoomScale(level)

    /** The whole factor, `os × app`. */
    val k: Float get() = os * app

    private var seenOs = os
    private var seenLevel = level

    /**
     * Asks the system again, and answers whether anything the Density is made of changed
     * since the last time this was asked: the system's text size, or a level the keys or
     * the application set.
     *
     * True is the window's cue to draw: nothing in the scene has invalidated, but every
     * piece of it is about to be laid out again at another size.
     */
    fun refresh(): Boolean {
        val now = sanitizeOsTextScale(osSource())
        if (now != os) os = now
        val changed = os != seenOs || level != seenLevel
        seenOs = os
        seenLevel = level
        return changed
    }

    /**
     * Takes the system's text size from somewhere other than [osSource]: the font scale a
     * platform's own Compose host already carries.
     */
    fun followOs(value: Float) {
        val now = sanitizeOsTextScale(value)
        if (now != os) os = now
    }

    /** Sets the level, kept inside -8..8 and saved. Answers whether it changed. */
    fun setLevel(value: Int): Boolean {
        val next = clampZoomLevel(value)
        if (next == level) return false
        level = next
        store.save(next)
        return true
    }

    /** Takes one step. Answers whether the level changed. */
    fun step(shortcut: ZoomShortcut): Boolean = setLevel(
        when (shortcut) {
            ZoomShortcut.In -> level + 1
            ZoomShortcut.Out -> level - 1
            ZoomShortcut.Reset -> 0
        },
    )

    /**
     * Takes a zoom shortcut the scene has already been offered.
     *
     * A key the application consumed is the application's: it is not also a zoom. Answers
     * whether the key was taken as one.
     */
    fun takeShortcut(shortcut: ZoomShortcut?, consumedByApplication: Boolean): Boolean {
        if (shortcut == null || consumedByApplication) return false
        step(shortcut)
        return true
    }

    /** The Density a scene is given at this display scale. */
    fun density(displayScale: Float): Density = zoomedDensity(displayScale, app, os)
}

/**
 * The zoom the window this content is in is drawn at, where the window holds one.
 *
 * Null inside a platform's own Compose host, where the content makes one itself.
 */
val LocalZoom = staticCompositionLocalOf<Zoom?> { null }

/**
 * Applies a level the application set on its window record, and tells the Host the zoom
 * once at start and again whenever it changes.
 *
 * A composable of its own so that only it is recomposed when the zoom changes. The report
 * is made from a SideEffect, because a boundary call is made on the thread the Host was
 * started on and that is the thread composition is applied on.
 */
@Composable
internal fun ZoomReporter(host: ComposeRustHost, zoom: Zoom) {
    val asked = host.table.window?.zoomLevel
    val applied = remember(host) { arrayOfNulls<Int>(1) }
    SideEffect {
        if (asked != null && asked != applied[0]) {
            applied[0] = asked
            zoom.setLevel(asked)
        }
    }
    val k = zoom.k
    val os = zoom.os
    val level = zoom.level
    val reported = remember(host) { arrayOfNulls<HostEvent.ZoomChanged>(1) }
    SideEffect {
        val event = HostEvent.ZoomChanged(nodeId = 0, handlerId = 0, k = k, os = os, level = level)
        if (reported[0] != event) {
            reported[0] = event
            host.dispatch(event)
        }
    }
}

/**
 * Which zoom shortcut a Compose key press is, for a renderer inside a platform's own Compose
 * host, where a key arrives already converted.
 *
 * Command on Apple platforms and Control elsewhere, with plus (or equals), minus or zero, on
 * the main keys or the keypad, and no other modifier besides Shift. Only presses, not
 * releases.
 */
fun composeZoomShortcut(event: KeyEvent, apple: Boolean): ZoomShortcut? {
    if (event.type != KeyEventType.KeyDown) return null
    val primary = if (apple) event.isMetaPressed else event.isCtrlPressed
    val other = event.isAltPressed || (if (apple) event.isCtrlPressed else event.isMetaPressed)
    if (!primary || other) return null
    return when (event.utf16CodePoint) {
        '='.code, '+'.code -> ZoomShortcut.In
        '-'.code, '_'.code -> ZoomShortcut.Out
        '0'.code -> ZoomShortcut.Reset
        else -> when (event.key) {
            Key.Equals, Key.Plus, Key.NumPadAdd -> ZoomShortcut.In
            Key.Minus, Key.NumPadSubtract -> ZoomShortcut.Out
            Key.Zero, Key.NumPad0 -> ZoomShortcut.Reset
            else -> null
        }
    }
}
