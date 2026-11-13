package dev.darkpyonix.composerust.ui.node

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.darkpyonix.composerust.foundation.ChromeInsets
import dev.darkpyonix.composerust.foundation.MessageQueue
import dev.darkpyonix.composerust.foundation.ScrollPort
import dev.darkpyonix.composerust.foundation.ScrollReports
import dev.darkpyonix.composerust.foundation.ScrollRequest
import dev.darkpyonix.composerust.protocol.ScrollWire
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.OverlayTokens
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.CodePosition
import dev.darkpyonix.composerust.foundation.codeEvents
import dev.darkpyonix.composerust.protocol.CodeEditorRecords
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.ui.SEMANTICS_STATE_MASK

/**
 * Text the Host pushed into a TextField with `SetText`.
 *
 * The field is uncontrolled: the Renderer owns the edit value and the composition state, and
 * this is the only way the Host changes it. If an IME composition is in progress the change
 * waits until the composition commits, because replacing text mid-composition destroys the
 * syllable being assembled.
 */
data class HostText(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    /** Increments on every `SetText`, so re-sending the same text still applies. */
    val revision: Long,
)

/**
 * One interpreted node. Each field is its own snapshot state, so a `SetProp` on one node
 * invalidates only the composables that read that node.
 */
class Node internal constructor(val id: Int, val widget: WidgetKind) {
    internal val props = mutableStateMapOf<PropertyKind, PropertyValue>()
    internal val modifiers = mutableStateListOf<ProtocolModifier>()
    internal val children = mutableStateListOf<Int>()
    internal var parentId: Int = NodeTable.ROOT_ID
    internal var hostText by mutableStateOf<HostText?>(null)
    internal var focusRequestRevision by mutableStateOf(0L)

    /**
     * A code editor's document and everything over it. Created with the node and kept for
     * its life, because the document is the Renderer's from the moment it opens: the Host
     * feeds it through the node's properties and its edit records, and never replaces it.
     */
    internal val code: CodeEditorModel? =
        if (widget == WidgetKind.CodeEditor) CodeEditorModel() else null

    /**
     * What a scroll container holds for the Host's scroll requests and for its reports.
     * Created with the node, because a request can arrive in the batch that creates it.
     */
    internal val scroll: ScrollPort? = if (widget in NodeTable.SCROLL_CONTAINERS) ScrollPort(id) else null

    /**
     * Set while a scroll request needs to know where this node is drawn. Nothing records
     * that for a node nobody asked about.
     */
    internal var placementWanted by mutableStateOf(false)
    internal var placement: androidx.compose.ui.layout.LayoutCoordinates? = null

    fun property(kind: PropertyKind): PropertyValue? = props[kind]

    fun text(kind: PropertyKind, default: String = ""): String =
        (props[kind] as? PropertyValue.Text)?.value ?: default

    fun flag(kind: PropertyKind, default: Boolean): Boolean =
        (props[kind] as? PropertyValue.Bool)?.value ?: default

    /** Handler ids arrive as integer property values. */
    fun handler(kind: PropertyKind): Long? = (props[kind] as? PropertyValue.Integer)?.value

    fun number(kind: PropertyKind): Float? = (props[kind] as? PropertyValue.Float)?.value

    /** The raw bytes of a byte-valued property, such as a Canvas command list. */
    fun bytes(kind: PropertyKind): ByteArray? = (props[kind] as? PropertyValue.Bytes)?.value
}

/** A protocol violation that must become a `ProtocolError` event, never a crash. */
data class TableError(val code: Int, val message: String) {
    companion object {
        const val UNKNOWN_NODE = 1
        const val DUPLICATE_NODE = 2
        const val UNSUPPORTED_PROPERTY = 3
        const val INVALID_INDEX = 4
        const val UNKNOWN_ASSET = 5
        const val UNSUPPORTED_ASSET = 6
        const val UNREADABLE_ASSET = 7
        const val CYCLIC_INSERT = 8
        const val INVALID_PALETTE = 9
        const val INVALID_CHILDREN = 10
        const val INVALID_ANIMATION = 11
        /** A code editor record or edit whose range, version or kind could not be honoured. */
        const val INVALID_CODE_EDIT = 12
        /** A `Window` node placed anywhere but directly under the root. */
        const val MISPLACED_WINDOW = 13
        /** A node moved from the content of one `Window` into another, or out of its window. */
        const val MOVE_BETWEEN_WINDOWS = 14
        /** A file dialog request whose number is still waiting for its answer. The command is dropped. */
        const val DUPLICATE_FILE_DIALOG = 15
        /** A modifier whose value could not be honoured. The modifier is dropped. */
        const val INVALID_MODIFIER = 16
        /** An input or output request whose number is still waiting for its answer. The command is dropped. */
        const val DUPLICATE_IO_REQUEST = 17
        /** A file operation whose request number is still waiting for its answer. The command is dropped. */
        const val DUPLICATE_FILE_CALL = 18
        /** A scroll request for a node that is not a scroll container. The request is dropped. */
        const val NOT_SCROLLABLE = 19
        /** A scroll request whose values make no sense for the container. The request is dropped. */
        const val INVALID_SCROLL = 20
    }
}

/**
 * The interpreted node tree.
 *
 * A whole batch is applied inside one `Snapshot.withMutableSnapshot` transaction by
 * [ComposeRustHost], so no intermediate tree state is ever drawn.
 */
class NodeTable {
    private val nodes = mutableStateMapOf<Int, Node>()

    /** The code editors among them, so that what they report is collected without a walk of the tree. */
    private val codeNodes = LinkedHashMap<Int, Node>()
    private val rootChildren = mutableStateListOf<Int>()
    private val errors = mutableListOf<TableError>()

    /**
     * The theme each `Window` node overrides the application's with. A window with
     * no entry follows [theme]. Dropped with the node, so a closed window leaves nothing here.
     */
    private val windowThemes = mutableStateMapOf<Int, Theme>()

    /** The `FocusWindow` commands of the batch just applied; the window manager drains them. */
    private val focusRequests = mutableListOf<Int>()

    /**
     * What applying a batch gave a code editor to say: the change an edit from the Host
     * made, or the edit it refused. Sent after the batch is applied, never in the middle of
     * it, so the Host is not entered while it is still writing.
     */
    private val outbox = mutableListOf<HostEvent>()

    /**
     * The registered assets. Pictures outlive the batch that carried them, so they are not
     * part of a node: a node names an id and the cache answers with what was registered.
     */
    val assets: AssetCache = AssetCache()

    /**
     * The transient messages the Host has asked to say.
     *
     * Not part of the node tree, because a message is not part of the tree: it has a
     * lifetime rather than a position, and that lifetime is owned here.
     */
    val messages: MessageQueue = MessageQueue()

    /**
     * What the screen's own chrome is using along the window's edges.
     *
     * Only a message reads it, and only so that it does not cover the destinations it is
     * drawn over. It is not part of the tree and never crosses the boundary.
     */
    val insets: ChromeInsets = ChromeInsets()

    /**
     * The notifications the Host has asked to show, and the platform's answers on their way
     * back.
     *
     * Not cleared with the tree. A resync throws the nodes away, but what is showing in the
     * notification centre and what the Host has been told about permission are still true,
     * and starting them over would tell the Host something it already knows or forget a
     * press that has not been delivered yet.
     */
    /**
     * The animations the Host handed over to play, and what each node shows because of
     * them. The Host is not called while they play unless an animation asked for events.
     */
    val animations: AnimationTable = AnimationTable(this)

    /**
     * Pointer input for the nodes that subscribed to it: who holds a press, and the moves
     * and wheel travel waiting for the next frame.
     */
    internal val pointers: dev.darkpyonix.composerust.foundation.PointerRouter =
        dev.darkpyonix.composerust.foundation.PointerRouter { id -> nodes.containsKey(id) }

    val notifications: dev.darkpyonix.composerust.ui.platform.NotificationCenter =
        dev.darkpyonix.composerust.ui.platform.NotificationCenter()

    /**
     * The file dialogs the Host has asked for, and their answers on the way back.
     *
     * Not cleared with the tree, for the reason the notifications are not: a request the Host
     * is waiting on is still owed its one answer after a resync.
     */
    val fileDialogs: dev.darkpyonix.composerust.ui.platform.FileDialogCenter =
        dev.darkpyonix.composerust.ui.platform.FileDialogCenter()

    /**
     * The operations the Host asked of files this side holds handles for, and their answers.
     * Not cleared with the tree, for the reason the dialogs are not: every request is owed its
     * one answer after a resync too.
     */
    val fileCalls: dev.darkpyonix.composerust.ui.platform.FileCallCenter =
        dev.darkpyonix.composerust.ui.platform.FileCallCenter()

    /**
     * Where the text the Host asks to copy goes. Not part of the tree and not cleared with
     * it: the clipboard is the platform's.
     */
    val clipboard: dev.darkpyonix.composerust.ui.platform.ClipboardWriter =
        dev.darkpyonix.composerust.ui.platform.ClipboardWriter()

    /**
     * The clipboard reads the Host asked for, each answered once with what the clipboard
     * held. Not part of the tree either.
     */
    val clipboardReads: dev.darkpyonix.composerust.ui.platform.ClipboardReader =
        dev.darkpyonix.composerust.ui.platform.ClipboardReader()

    /**
     * The storage operations the Host asked this side to carry out, each answered once.
     * Not cleared with the tree: a request the Host waits on is owed its answer after a
     * resync too.
     */
    val storage: dev.darkpyonix.composerust.ui.platform.StorageCenter =
        dev.darkpyonix.composerust.ui.platform.StorageCenter()

    /**
     * Counts the batches applied, so a scroll container can tell whether its own stated
     * move and a scroll request came in the same one.
     */
    private var batchSerial = 0L

    /** The scroll reports waiting for the next frame, from the containers the Host listens to. */
    internal val scrolls: ScrollReports = ScrollReports()

    private var revision = 0L

    /** Increments on each `RequestFocus(0)` so the root composition clears active focus. */
    var focusClearRevision by mutableStateOf(0L)
        private set

    /** Null until the Host sends its first `SetTheme` record. */
    var theme: Theme? by mutableStateOf(null)
        private set

    /** Null until the Host sends an overlay tokens record. */
    var overlayTokens: OverlayTokens? by mutableStateOf(null)
        private set

    /** Null until the Host sends its first `SetWindow` record. */
    var window: Window? by mutableStateOf(null)
        private set

    /** The theme the window drawn for the `Window` node [windowId] uses; node 0 is the application's. */
    fun themeFor(windowId: Int): Theme? =
        if (windowId == ROOT_ID) theme else windowThemes[windowId] ?: theme

    /** True when [windowId] has a theme of its own over the application's. */
    internal fun overridesTheme(windowId: Int): Boolean = windowThemes.containsKey(windowId)

    /** How many windows hold a theme override; zero once they are all gone. */
    internal val windowThemeCount: Int get() = windowThemes.size

    /**
     * The zoom level the application asked for the window [windowId], or null where it left
     * the level to the renderer. Node 0 is the single window of `SetWindow`.
     */
    fun zoomLevelFor(windowId: Int): Int? {
        if (windowId == ROOT_ID) return window?.zoomLevel
        val level = (nodes[windowId]?.props?.get(PropertyKind.WindowZoomLevel) as? PropertyValue.Integer)?.value
        return level?.toInt()?.coerceIn(-8, 8)
    }

    /**
     * Whether the window [windowId] asked for every whole-dp change of its size. Node 0 is
     * the single window of `SetWindow`, which asks through its settings; a `Window` node
     * asks through its `report_size` property.
     */
    fun reportSizeFor(windowId: Int): Boolean {
        if (windowId == ROOT_ID) return window?.reportSize == true
        return node(windowId)?.flag(PropertyKind.ReportSize, false) == true
    }

    /**
     * The `Window` node the node [nodeId] is drawn in, itself included, or null for a node
     * that sits in none (every node of a single window application).
     */
    fun windowOf(nodeId: Int): Int? {
        var current = nodeId
        var steps = nodes.size + 1
        while (current != ROOT_ID && steps-- > 0) {
            val node = nodes[current] ?: return null
            if (node.widget == WidgetKind.Window) return current
            current = node.parentId
        }
        return null
    }

    /** The `FocusWindow` commands applied since the last call, in order. */
    internal fun drainFocusRequests(): List<Int> {
        if (focusRequests.isEmpty()) return emptyList()
        val drained = focusRequests.toList()
        focusRequests.clear()
        return drained
    }

    /** Top-level nodes, in creation order until the Host parents them. */
    val roots: List<Int> get() = rootChildren

    fun node(id: Int): Node? = nodes[id]

    /** Events code editors queued while a batch was applied; drained after it commits. */
    internal fun drainEvents(): List<HostEvent> {
        // What is reported as where it ended up, the selection and the lines on screen, is
        // said once for the whole batch: several moves in it are one report.
        if (codeNodes.isNotEmpty()) for (node in codeNodes.values.toList()) collectCode(node, includeReports = true)
        if (outbox.isEmpty()) return emptyList()
        val drained = outbox.toList()
        outbox.clear()
        return drained
    }

    /** Errors collected while applying a batch; drained after the transaction commits. */
    internal fun drainErrors(): List<TableError> {
        // Asked once the whole batch is in, because a batch may start an animation before
        // the record that gives it something to play on.
        animations.afterBatch { code, message -> fail(code, message) }
        batchSerial++
        if (errors.isEmpty()) return emptyList()
        val drained = errors.toList()
        errors.clear()
        return drained
    }

    /**
     * Throws the whole interpreted tree away.
     *
     * For a Renderer that is about to ask the Host to send all of it again: the Host
     * answers a resync by building the application from nothing, so the ids in that batch
     * start over and anything kept from before would collide with them.
     */
    fun clear() {
        // The windows go with the nodes, and a dialog open on one of them ends with a cancel.
        nodes.filterValues { it.widget == WidgetKind.Window }.keys.toList()
            .forEach(fileDialogs::windowRemoved)
        animations.clear()
        scrolls.clear()
        nodes.clear()
        codeNodes.clear()
        rootChildren.clear()
        errors.clear()
        outbox.clear()
        windowThemes.clear()
        focusRequests.clear()
        assets.clear()
        messages.clear()
        theme = null
        window = null
        materials = 0
        revision = 0
        focusClearRevision = 0
    }

    fun apply(mutation: Mutation) {
        when (mutation) {
            is Mutation.Create -> create(mutation)
            is Mutation.SetProp -> setProp(mutation)
            is Mutation.SetModifier -> setModifier(mutation)
            is Mutation.Insert -> insert(mutation.parentId, mutation.nodeId, mutation.index)
            is Mutation.Move -> insert(mutation.parentId, mutation.nodeId, mutation.index)
            is Mutation.Remove -> remove(mutation.nodeId)
            is Mutation.SetText -> setText(mutation)
            is Mutation.AppendText -> appendText(mutation)
            // One record changes the whole tree's appearance. `ComposeRustContent`
            // resolves it into tokens and rules, and Compose invalidates the readers.
            is Mutation.SetTheme -> {
                theme = mutation.theme
                // The entries that were wrong are already out of the palette and the rest
                // of it applies. Each one is still said, so an application whose brand
                // colour did not arrive can find out why.
                mutation.theme.paletteProblems.forEach { problem ->
                    fail(TableError.INVALID_PALETTE, "theme palette: $problem")
                }
            }
            is Mutation.SetOverlayTokens -> overlayTokens = mutation.overlay
            // Read before there is a window to apply it to. The platform layer asks for
            // this out of the first batch and builds the window from it, so by the time
            // the batch is applied the window already looks the way it says. Keeping it
            // here as well is what lets a later rebuild change it.
            is Mutation.SetWindow -> window = mutation.window
            // A theme for one window, and bringing one forward. The theme is kept with the
            // window's node and read by the scene drawn for it; the focus request waits for
            // the window manager, which owns the platform windows, and is acted on after
            // the batch.
            is Mutation.SetWindowTheme -> setWindowTheme(mutation)
            is Mutation.FocusWindow -> focusWindow(mutation)
            is Mutation.RequestFocus -> requestFocus(mutation)
            // The bytes are read here, once, and never again: what a frame carries is the
            // id. A kind this Renderer cannot read, or a release of something that was
            // never registered, is reported and the rest of the batch still applies.
            is Mutation.RegisterAsset ->
                assets.register(mutation.assetId, mutation.kind, mutation.bytes)?.let(errors::add)

            is Mutation.ReleaseAsset -> assets.release(mutation.assetId)?.let(errors::add)

            // A sentence to say, not a node to draw. It joins the line; how long it stays
            // and what happens to the one behind it is decided on this side.
            is Mutation.ShowMessage -> messages.post(
                mutation.handlerId,
                mutation.text,
                mutation.action,
                mutation.duration,
            )

            // Outside the window altogether. The centre decides what the window's own state
            // says about it (whether it is active, whether permission has been asked) and
            // the platform shows it.
            is Mutation.PostNotification -> notifications.apply(mutation)
            is Mutation.WithdrawNotification -> notifications.withdraw(mutation.key)
            is Mutation.RequestNotificationPermission -> notifications.requestPermission()

            // Not a node either. A request on a window that is not there is answered as
            // unavailable, so the Host's one answer per request holds, and is reported too.
            is Mutation.ShowFileDialog -> showFileDialog(mutation)

            // Plain text for the platform clipboard. Nothing answers it, and where there is
            // no clipboard it does nothing.
            is Mutation.SetClipboardText -> clipboard.write(mutation.text)

            // A read is answered later, outside the batch, by one event with its number.
            is Mutation.ReadClipboard -> clipboardReads.request(mutation.request)

            // An operation on a file this side holds a handle for, answered later, outside the
            // batch, by one event with its number. Where nothing holds handles it is answered
            // as unavailable, which is still its one answer.
            is Mutation.FileCall -> fileCall(mutation)
            // Nothing answers a release, and a handle nobody holds is no error.
            is Mutation.ReleaseFileHandle -> fileCalls.release(mutation.handle)
            // Started now, while the value to write can still be read where the Host put it,
            // and answered later, outside the batch, by one event with its number.
            is Mutation.StorageCall -> storageCall(mutation)

            // Played on this side's frame clock until it ends. Whether the node has
            // somewhere to play it is asked once the whole batch is in.
            is Mutation.StartAnimation -> animations.start(mutation.animation)
            is Mutation.ControlAnimation -> animations.control(mutation)
            // A request against the editor's document, which the editor may refuse. What
            // it did is reported once the batch is applied.
            is Mutation.EditCode -> editCode(mutation)

            // The editor's selection and scroll are this side's, so these are requests
            // too: each is checked against the document and refused, with a protocol
            // error, where it names a place that is not in it.
            is Mutation.SetSelection -> codeNode(mutation.nodeId, "SetSelection")?.let { (node, code) ->
                code.hostSetSelection(
                    mutation.baseVersion,
                    mutation.anchorLine,
                    mutation.anchorColumn,
                    mutation.activeLine,
                    mutation.activeColumn,
                )
                collectCode(node)
            }
            is Mutation.RevealRange -> codeNode(mutation.nodeId, "RevealRange")?.let { (node, code) ->
                code.hostReveal(
                    mutation.baseVersion,
                    CodePosition(mutation.startLine, mutation.startColumn),
                    CodePosition(mutation.endLine, mutation.endColumn),
                    mutation.align,
                )
                collectCode(node)
            }
            is Mutation.SetFolded -> codeNode(mutation.nodeId, "SetFolded")?.let { (node, code) ->
                code.hostSetFolded(mutation.startLine, mutation.collapsed)
                collectCode(node)
            }
            // The offset waits in the model for the surface, which owns the scroll position
            // and knows how far the document lets it go.
            is Mutation.SetScrollOffset -> codeNode(mutation.nodeId, "SetScrollOffset")?.let { (node, code) ->
                code.hostScrollTo(mutation.offsetPx)
                collectCode(node)
            }

            // Requests too: the scroll position is this side's. Each is checked here and
            // waits on the node for the container, which keeps it in range, may animate it,
            // and drops it while the user is scrolling.
            is Mutation.ScrollTo -> scrollTo(mutation)
            is Mutation.ScrollIntoView -> scrollIntoView(mutation)
        }
    }

    private fun windowNode(nodeId: Int, command: String): Node? {
        val node = nodes[nodeId] ?: run {
            fail(TableError.UNKNOWN_NODE, "$command for unknown node $nodeId")
            return null
        }
        if (node.widget != WidgetKind.Window) {
            fail(TableError.UNSUPPORTED_PROPERTY, "$command on ${node.widget}")
            return null
        }
        return node
    }

    private fun setWindowTheme(mutation: Mutation.SetWindowTheme) {
        windowNode(mutation.nodeId, "SetWindowTheme") ?: return
        windowThemes[mutation.nodeId] = mutation.theme
        mutation.theme.paletteProblems.forEach { problem ->
            fail(TableError.INVALID_PALETTE, "window ${mutation.nodeId} theme palette: $problem")
        }
    }

    private fun focusWindow(mutation: Mutation.FocusWindow) {
        windowNode(mutation.nodeId, "FocusWindow") ?: return
        focusRequests += mutation.nodeId
    }

    private fun showFileDialog(mutation: Mutation.ShowFileDialog) {
        val windowKnown = mutation.window == ROOT_ID ||
            nodes[mutation.window]?.widget == WidgetKind.Window
        when (fileDialogs.request(mutation, windowKnown)) {
            dev.darkpyonix.composerust.ui.platform.FileDialogCenter.Accepted.Accepted -> Unit
            dev.darkpyonix.composerust.ui.platform.FileDialogCenter.Accepted.Duplicate -> fail(
                TableError.DUPLICATE_FILE_DIALOG,
                "ShowFileDialog request ${mutation.request} is already waiting for its answer",
            )
            dev.darkpyonix.composerust.ui.platform.FileDialogCenter.Accepted.UnknownWindow -> fail(
                TableError.UNKNOWN_NODE,
                "ShowFileDialog for unknown window ${mutation.window}",
            )
        }
    }

    private fun fileCall(mutation: Mutation.FileCall) {
        if (fileCalls.request(mutation) == dev.darkpyonix.composerust.ui.platform.FileCallCenter.Accepted.Duplicate) {
            fail(
                TableError.DUPLICATE_FILE_CALL,
                "FileCall request ${mutation.request} is already waiting for its answer",
            )
        }
    }

    private fun storageCall(mutation: Mutation.StorageCall) {
        when (storage.request(mutation)) {
            dev.darkpyonix.composerust.ui.platform.StorageCenter.Accepted.Accepted -> Unit
            dev.darkpyonix.composerust.ui.platform.StorageCenter.Accepted.Duplicate -> fail(
                TableError.DUPLICATE_IO_REQUEST,
                "StorageCall request ${mutation.request} is already waiting for its answer",
            )
        }
    }

    private fun requestFocus(mutation: Mutation.RequestFocus) {
        if (mutation.nodeId == ROOT_ID) {
            revision += 1
            focusClearRevision = revision
            return
        }
        if (!requestNodeFocus(mutation.nodeId)) {
            fail(TableError.UNKNOWN_NODE, "RequestFocus for unknown node ${mutation.nodeId}")
        }
    }

    /**
     * Asks for keyboard focus to move to the node [nodeId] names, and says whether there is
     * such a node.
     *
     * A `RequestFocus` from the Host and the focus action of the accessibility tree both end
     * here, so a screen reader moves focus the way the application does: the node's own
     * composable sees the new revision and calls its focus requester.
     */
    internal fun requestNodeFocus(nodeId: Int): Boolean {
        val node = nodes[nodeId] ?: return false
        revision += 1
        node.focusRequestRevision = revision
        return true
    }

    /** The scroll container a command names, or null after reporting that it names none. */
    private fun scrollNode(nodeId: Int, command: String): Pair<Node, ScrollPort>? {
        val node = nodes[nodeId] ?: run {
            fail(TableError.UNKNOWN_NODE, "$command for unknown node $nodeId")
            return null
        }
        val port = node.scroll ?: run {
            fail(TableError.NOT_SCROLLABLE, "$command on ${node.widget}, which does not scroll")
            return null
        }
        return node to port
    }

    private fun scrollTo(mutation: Mutation.ScrollTo) {
        val (node, port) = scrollNode(mutation.nodeId, "ScrollTo") ?: return
        val invalid = { why: String ->
            fail(TableError.INVALID_SCROLL, "ScrollTo on node ${node.id}: $why")
        }
        if (mutation.flags and ScrollWire.TO_KNOWN.inv() != 0) {
            return invalid("flag bits this renderer does not know: 0x${(mutation.flags and ScrollWire.TO_KNOWN.inv()).toString(16)}")
        }
        val hasX = mutation.flags and ScrollWire.TO_X != 0
        val hasY = mutation.flags and ScrollWire.TO_Y != 0
        if (!hasX && !hasY) return invalid("it names neither axis")
        if (!mutation.xPx.isFinite() || !mutation.yPx.isFinite()) {
            return invalid("an offset of (${mutation.xPx}, ${mutation.yPx}) is not a distance")
        }
        if (mutation.item != 0 && node.widget !in LAZY_CONTAINERS) {
            return invalid("item ${mutation.item.toUInt()} on ${node.widget}, which has no items")
        }
        port.beforeRequest(batchSerial)
        port.request(
            ScrollRequest.To(
                // Unsigned on the wire. An index past what an Int holds is past any list.
                item = if (mutation.item < 0) Int.MAX_VALUE else mutation.item,
                x = if (hasX) mutation.xPx else null,
                y = if (hasY) mutation.yPx else null,
                animate = mutation.flags and ScrollWire.TO_ANIMATE != 0,
            ),
        )
    }

    private fun scrollIntoView(mutation: Mutation.ScrollIntoView) {
        val (node, port) = scrollNode(mutation.nodeId, "ScrollIntoView") ?: return
        val invalid = { why: String ->
            fail(TableError.INVALID_SCROLL, "ScrollIntoView on node ${node.id}: $why")
        }
        if (mutation.flags and ScrollWire.INTO_VIEW_ANIMATE.inv() != 0) {
            return invalid("flag bits this renderer does not know: 0x${(mutation.flags and ScrollWire.INTO_VIEW_ANIMATE.inv()).toString(16)}")
        }
        for (align in intArrayOf(mutation.alignX, mutation.alignY)) {
            if (align !in ScrollWire.ALIGN_KEEP..ScrollWire.ALIGN_NEAREST) return invalid("unknown alignment $align")
        }
        val numbers = doubleArrayOf(mutation.rectXPx, mutation.rectYPx, mutation.rectWidthPx, mutation.rectHeightPx)
        if (numbers.any { !it.isFinite() }) return invalid("a rectangle of ${numbers.joinToString()} is not a place")
        if (mutation.rectWidthPx < 0.0 || mutation.rectHeightPx < 0.0) {
            return invalid("a rectangle ${mutation.rectWidthPx} by ${mutation.rectHeightPx} has a negative size")
        }
        val lazy = node.widget in LAZY_CONTAINERS
        var target = mutation.target
        when (mutation.targetKind) {
            ScrollWire.TARGET_CONTENT -> {
                if (lazy) return invalid("${node.widget} has no content coordinates; name an item")
                if (target != 0) return invalid("a content rectangle names no target, but $target was sent")
            }
            ScrollWire.TARGET_NODE -> {
                if (target == node.id || !nodes.containsKey(target) || !isDescendant(target, node.id)) {
                    return invalid("node $target is not inside the container")
                }
            }
            ScrollWire.TARGET_ITEM -> {
                if (!lazy) return invalid("item ${target.toUInt()} on ${node.widget}, which has no items")
                if (target < 0) target = Int.MAX_VALUE
            }
            else -> return invalid("unknown target kind ${mutation.targetKind}")
        }
        port.beforeRequest(batchSerial)
        port.request(
            ScrollRequest.IntoView(
                targetKind = mutation.targetKind,
                target = target,
                alignX = mutation.alignX,
                alignY = mutation.alignY,
                animate = mutation.flags and ScrollWire.INTO_VIEW_ANIMATE != 0,
                x = mutation.rectXPx,
                y = mutation.rectYPx,
                width = mutation.rectWidthPx,
                height = mutation.rectHeightPx,
            ),
        )
    }

    /** The code editor a command names, or null after reporting that it names none. */
    private fun codeNode(nodeId: Int, command: String): Pair<Node, CodeEditorModel>? {
        val node = nodes[nodeId] ?: run {
            fail(TableError.UNKNOWN_NODE, "$command for unknown node $nodeId")
            return null
        }
        val code = node.code ?: run {
            fail(TableError.UNSUPPORTED_PROPERTY, "$command on ${node.widget}")
            return null
        }
        return node to code
    }

    private fun editCode(mutation: Mutation.EditCode) {
        val node = nodes[mutation.nodeId] ?: return fail(
            TableError.UNKNOWN_NODE,
            "EditCode for unknown node ${mutation.nodeId}",
        )
        val code = node.code ?: return fail(
            TableError.UNSUPPORTED_PROPERTY,
            "EditCode on ${node.widget}",
        )
        code.hostEdit(
            mutation.requestId,
            mutation.baseVersion,
            mutation.startLine,
            mutation.startColumn,
            mutation.endLine,
            mutation.endColumn,
            mutation.text,
        )
        collectCode(node)
    }

    /**
     * Hands a code editor what the Host just set on it. The text is compared with the
     * document rather than applied, and the two lists are read record by record, so a bad
     * record costs that record and nothing else.
     */
    private fun feedCode(node: Node, kind: PropertyKind, value: PropertyValue) {
        val code = node.code ?: return
        when (kind) {
            PropertyKind.Text -> code.setHostText((value as? PropertyValue.Text)?.value ?: "")
            PropertyKind.Decorations -> code.setDecorations(
                (value as? PropertyValue.Bytes)?.value?.let { bytes ->
                    CodeEditorRecords.decodeDecorations(bytes) { message ->
                        fail(TableError.INVALID_CODE_EDIT, "decorations on node ${node.id}: $message")
                    }
                } ?: emptyList(),
            )
            PropertyKind.SyntaxSpans -> code.setSyntaxSpans(
                (value as? PropertyValue.Bytes)?.value?.let { bytes ->
                    CodeEditorRecords.decodeSyntaxSpans(bytes) { message ->
                        fail(TableError.INVALID_CODE_EDIT, "syntax spans on node ${node.id}: $message")
                    }
                } ?: emptyList(),
            )
            PropertyKind.Folds -> code.setFolds(
                (value as? PropertyValue.Bytes)?.value?.let { bytes ->
                    CodeEditorRecords.decodeFolds(bytes) { message ->
                        fail(TableError.INVALID_CODE_EDIT, "folds on node ${node.id}: $message")
                    }
                } ?: emptyList(),
            )
            PropertyKind.GutterDecorations -> code.setGutterDecorations(
                (value as? PropertyValue.Bytes)?.value?.let { bytes ->
                    CodeEditorRecords.decodeGutterDecorations(bytes) { message ->
                        fail(TableError.INVALID_CODE_EDIT, "gutter decorations on node ${node.id}: $message")
                    }
                } ?: emptyList(),
            )
            // Read where the editor is drawn. Here it is only checked, so that a record
            // that cannot be read is said once, when it arrives.
            PropertyKind.CodeEditorColors -> {
                (value as? PropertyValue.Bytes)?.value?.let { bytes ->
                    val records = CodeEditorRecords.decodeCodeEditorColors(bytes) { message ->
                        fail(TableError.INVALID_CODE_EDIT, "colours on node ${node.id}: $message")
                    }
                    if (records.size > 1) {
                        fail(
                            TableError.INVALID_CODE_EDIT,
                            "colours on node ${node.id}: ${records.size} records where an editor has one",
                        )
                    }
                }
                return
            }
            // Whether there is anyone to tell. The model reports only what is heard, and
            // the next frame's drain carries what a new listener has yet to hear.
            PropertyKind.OnCodeMetrics -> {
                code.hearsLineHeight = value is PropertyValue.Integer
                return
            }
            PropertyKind.OnCodeScrolled -> {
                code.hearsScroll = value is PropertyValue.Integer
                return
            }
            else -> return
        }
        collectCode(node)
    }

    private fun collectCode(node: Node, includeReports: Boolean = false) {
        val code = node.code ?: return
        outbox += node.codeEvents(code.drainOutput(includeReports)) { message ->
            fail(TableError.INVALID_CODE_EDIT, "code editor ${node.id}: $message")
        }
    }

    private fun create(mutation: Mutation.Create) {
        if (nodes.containsKey(mutation.nodeId)) {
            fail(TableError.DUPLICATE_NODE, "node ${mutation.nodeId} already exists")
            return
        }
        val created = Node(mutation.nodeId, mutation.widget)
        nodes[mutation.nodeId] = created
        if (created.code != null) codeNodes[mutation.nodeId] = created
        // A node the Host never inserts stays a root. The Host has no mutation that attaches
        // the application root, so the first created node is what the Renderer draws.
        // There is no mutation that attaches a root, so roots are inferred here.
        rootChildren.add(mutation.nodeId)
    }

    private fun setProp(mutation: Mutation.SetProp) {
        val node = nodes[mutation.nodeId] ?: return fail(
            TableError.UNKNOWN_NODE,
            "SetProp for unknown node ${mutation.nodeId}",
        )
        if (!supportsProperty(node.widget, mutation.property)) {
            // An out-of-schema property is reported and skipped, never applied.
            fail(
                TableError.UNSUPPORTED_PROPERTY,
                "${node.widget} does not support ${mutation.property}",
            )
            return
        }
        if (mutation.value is PropertyValue.None) {
            node.props.remove(mutation.property)
        } else {
            node.props[mutation.property] = mutation.value
        }
        if (node.code != null) feedCode(node, mutation.property, mutation.value)
        // The revision of a scroll container's own stated move. Where a scroll request is
        // already waiting from this batch, its place among them is kept, so the two are
        // applied in the order the batch wrote them.
        if (mutation.property == PropertyKind.Count && node.scroll != null) {
            node.scroll.stated(batchSerial, (mutation.value as? PropertyValue.Integer)?.value ?: 0L)
        }
    }

    private fun setModifier(mutation: Mutation.SetModifier) {
        val node = nodes[mutation.nodeId] ?: return fail(
            TableError.UNKNOWN_NODE,
            "SetModifier for unknown node ${mutation.nodeId}",
        )
        if (mutation.index < 0 || mutation.index > MAX_MODIFIERS) {
            fail(TableError.INVALID_INDEX, "modifier index ${mutation.index} out of range")
            return
        }
        val modifier = mutation.modifier
        if (modifier is ProtocolModifier.Semantics && modifier.state and SEMANTICS_STATE_MASK.inv() != 0) {
            // A bit this renderer has no meaning for is a newer or a broken Host. Guessing
            // would announce a control as something it is not, so the modifier is refused
            // and the slot keeps what it held.
            val unknown = modifier.state and SEMANTICS_STATE_MASK.inv()
            fail(
                TableError.INVALID_MODIFIER,
                "Semantics modifier on node ${mutation.nodeId} sets state or action bits this renderer " +
                    "does not know: 0x${unknown.toString(16)}",
            )
            return
        }
        while (node.modifiers.size <= mutation.index) {
            node.modifiers.add(ProtocolModifier.Empty)
        }
        val replaced = node.modifiers[mutation.index]
        node.modifiers[mutation.index] = mutation.modifier
        // Counted rather than searched for. Whether any node in the tree is made of a
        // material decides whether the window is stood up in a form that can show what is
        // behind it, and that question is asked once per frame: walking the tree to answer
        // it would put the size of the tree into every frame for a screen that mostly says
        // no.
        if (replaced is ProtocolModifier.Material) materials--
        if (mutation.modifier is ProtocolModifier.Material) materials++
    }

    /**
     * How many nodes are made of a material.
     *
     * Zero for almost every screen. One is enough for the window to need a backdrop, and
     * which part of it actually shows through is decided where the material is drawn: a
     * sidebar made of glass is glass, and a page that paints itself covers what is behind
     * it exactly as an ordinary page does.
     */
    private var materials: Int by mutableStateOf(0)

    /** True where some node asked to be made of a material. */
    internal val asksForMaterial: Boolean get() = materials > 0

    private fun insert(parentId: Int, nodeId: Int, index: Int) {
        // Node id 0 is the "no node" sentinel. The Host uses it for a Dioxus
        // placeholder: an empty `for` body still has a position in the parent, but nothing
        // to draw. It occupies no slot here, and the Host's later Insert for the real
        // children carries the position the placeholder stood at, so indices still line up.
        if (nodeId == ROOT_ID) return
        if (!nodes.containsKey(nodeId)) {
            fail(TableError.UNKNOWN_NODE, "Insert of unknown node $nodeId")
            return
        }
        if (parentId != ROOT_ID && !nodes.containsKey(parentId)) {
            fail(TableError.UNKNOWN_NODE, "Insert into unknown parent $parentId")
            return
        }
        // A window is a child of the root and nothing else, and what is drawn in one
        // window cannot be moved into another: a composition is per window and Compose has
        // no way to hand a node from one scene to the next. Both are the Host's
        // mistake; the tree is left as it was and the process goes on.
        if (nodes[nodeId]?.widget == WidgetKind.Window && parentId != ROOT_ID) {
            fail(TableError.MISPLACED_WINDOW, "Window $nodeId must be a child of the root, not of $parentId")
            return
        }
        val from = windowOf(nodes[nodeId]?.parentId ?: ROOT_ID)
        if (from != null && from != windowOf(parentId)) {
            fail(
                TableError.MOVE_BETWEEN_WINDOWS,
                "node $nodeId cannot move from window $from to $parentId",
            )
            return
        }
        // A node cannot be placed under itself or under one of its own descendants: the
        // parent chain would then have no root, and anything that follows it, drawing the
        // tree or walking up from a node, recurses until the stack runs out. The tree the
        // Renderer already has is left exactly as it was.
        if (nodeId == parentId || isDescendant(parentId, nodeId)) {
            fail(
                TableError.CYCLIC_INSERT,
                "Insert of node $nodeId under $parentId would make $nodeId its own ancestor",
            )
            return
        }
        detach(nodeId)
        val siblings = childrenOf(parentId) ?: return
        // `index` is unsigned on the wire; u32::MAX means "append".
        val position = if (index.toLong() and 0xFFFF_FFFFL > siblings.size.toLong()) {
            siblings.size
        } else {
            index
        }
        siblings.add(position, nodeId)
        nodes[nodeId]?.parentId = parentId
    }

    private fun remove(nodeId: Int) {
        // The placeholder sentinel was never materialised, so removing it is a no-op.
        if (nodeId == ROOT_ID) return
        if (!nodes.containsKey(nodeId)) {
            fail(TableError.UNKNOWN_NODE, "Remove of unknown node $nodeId")
            return
        }
        detach(nodeId)
        removeSubtree(nodeId)
    }

    private fun removeSubtree(nodeId: Int) {
        val node = nodes.remove(nodeId) ?: return
        codeNodes.remove(nodeId)
        node.scroll?.let(scrolls::forget)
        if (node.widget == WidgetKind.Window) {
            windowThemes.remove(nodeId)
            fileDialogs.windowRemoved(nodeId)
        }
        animations.removeNode(nodeId)
        materials -= node.modifiers.count { it is ProtocolModifier.Material }
        node.children.toList().forEach(::removeSubtree)
    }

    /**
     * Appends to a Text node's content. Streaming sends only the new tail, so the
     * batch does not grow with the text already on screen.
     */
    private fun appendText(mutation: Mutation.AppendText) {
        val node = nodes[mutation.nodeId] ?: return fail(
            TableError.UNKNOWN_NODE,
            "AppendText for unknown node ${mutation.nodeId}",
        )
        if (!supportsProperty(node.widget, PropertyKind.Text)) {
            fail(TableError.UNSUPPORTED_PROPERTY, "AppendText on ${node.widget}")
            return
        }
        revision += 1
        node.props[PropertyKind.Text] = PropertyValue.Text(node.text(PropertyKind.Text) + mutation.text)
    }

    private fun setText(mutation: Mutation.SetText) {
        val node = nodes[mutation.nodeId] ?: return fail(
            TableError.UNKNOWN_NODE,
            "SetText for unknown node ${mutation.nodeId}",
        )
        if (node.widget != WidgetKind.TextField) {
            fail(TableError.UNSUPPORTED_PROPERTY, "SetText on ${node.widget}")
            return
        }
        revision += 1
        node.hostText = HostText(
            text = mutation.text,
            selectionStart = mutation.selectionStart,
            selectionEnd = mutation.selectionEnd,
            revision = revision,
        )
    }

    /**
     * True when [nodeId] is [candidate] itself or sits below it, found by walking up from
     * [nodeId] rather than down, so the cost is the depth of the tree and not its size.
     *
     * The walk is bounded by the number of nodes, so even a chain that already loops ends
     * the walk instead of running forever.
     */
    private fun isDescendant(nodeId: Int, candidate: Int): Boolean {
        var current = nodeId
        var steps = nodes.size
        while (current != ROOT_ID && steps-- > 0) {
            if (current == candidate) return true
            current = nodes[current]?.parentId ?: return false
        }
        return false
    }

    private fun detach(nodeId: Int) {
        val parent = nodes[nodeId]?.parentId ?: ROOT_ID
        childrenOf(parent)?.remove(nodeId)
        rootChildren.remove(nodeId)
        nodes[nodeId]?.parentId = ROOT_ID
    }

    private fun childrenOf(parentId: Int): MutableList<Int>? =
        if (parentId == ROOT_ID) rootChildren else nodes[parentId]?.children

    private fun fail(code: Int, message: String) {
        errors += TableError(code, message)
    }

    /**
     * Records a protocol error found outside the batch itself, such as a measure request
     * naming a font that was never registered. It is reported with the batch's own errors,
     * after the call it was found in has returned, so the Host is never re-entered.
     */
    internal fun report(code: Int, message: String) = fail(code, message)

    /**
     * [report], unless the same error is already waiting to be reported. For a mistake a
     * Host makes in every request of a kind, which is one thing to fix however many
     * requests it was made in.
     */
    internal fun reportOnce(code: Int, message: String) {
        if (errors.none { it.code == code && it.message == message }) fail(code, message)
    }

    companion object {
        /** Node id 0 is the Host's "no node" sentinel, so it names the virtual root. */
        const val ROOT_ID: Int = 0
        private const val MAX_MODIFIERS = 64

        /** The lazy containers, which scroll by item and have no content coordinates. */
        internal val LAZY_CONTAINERS: Set<WidgetKind> =
            setOf(WidgetKind.LazyColumn, WidgetKind.LazyRow, WidgetKind.LazyGrid)

        /** Every widget that takes a scroll request and can report its position. */
        internal val SCROLL_CONTAINERS: Set<WidgetKind> =
            LAZY_CONTAINERS + setOf(WidgetKind.ScrollColumn, WidgetKind.ScrollRow, WidgetKind.CodeEditor)

        internal fun supportsProperty(widget: WidgetKind, property: PropertyKind): Boolean =
            when (property) {
                // The name assistive technology reads can be given to any node.
                PropertyKind.AccessibleName -> true

                // Colours and size of the selection controls.
                PropertyKind.TrackColor,
                PropertyKind.ThumbColor,
                -> widget == WidgetKind.Switch || widget == WidgetKind.Slider

                PropertyKind.ActiveTrackColor -> widget == WidgetKind.Slider

                PropertyKind.ContainerColor,
                PropertyKind.CheckColor,
                -> widget == WidgetKind.Checkbox

                PropertyKind.ControlSize -> widget == WidgetKind.Switch || widget == WidgetKind.Checkbox

                // Event properties carry handler ids and are valid on any widget.
                PropertyKind.OnClick,
                PropertyKind.OnValueChange,
                PropertyKind.OnSubmit,
                PropertyKind.OnFocusLost,
                PropertyKind.OnFocusGained,
                PropertyKind.OnKeyDown,
                PropertyKind.OnKeyUp,
                PropertyKind.OnRangeRequested,
                PropertyKind.OnDismiss,
                // Any node's size can move under a motion role, so any node may hear it
                // settle. One without the role hears nothing, which is not an error: the
                // role can come and go with the screen's state.
                PropertyKind.OnMotionFinished,
                -> true

                // The scroll report belongs to the widgets that scroll.
                PropertyKind.OnScrollChanged -> widget in SCROLL_CONTAINERS

                // Pointer subscription, cursor and selectability belong to the nodes a
                // pointer can sit over as a region: the containers, text and the HTML
                // region root. A picker takes only its value, range and events.
                PropertyKind.OnPointer,
                PropertyKind.PointerKinds,
                PropertyKind.Cursor,
                PropertyKind.UserSelect,
                // A dragged window passing over a node, and the key a node is pulled out
                // into a window under, belong to the same containers a pointer can sit over.
                PropertyKind.OnWindowDragOver,
                PropertyKind.TearOffKey,
                -> widget == WidgetKind.Text ||
                    widget == WidgetKind.Column ||
                    widget == WidgetKind.Row ||
                    widget == WidgetKind.Box ||
                    widget == WidgetKind.Card ||
                    widget == WidgetKind.Surface ||
                    widget == WidgetKind.ScrollColumn ||
                    widget == WidgetKind.ScrollRow ||
                    widget == WidgetKind.SelectionContainer ||
                    widget == WidgetKind.Spacer ||
                    widget == WidgetKind.AbsoluteBox

                // A Tooltip's text is the explanation it shows, and its description in the
                // accessibility tree. A destination's is its label.
                PropertyKind.Text ->
                    widget == WidgetKind.Text ||
                        widget == WidgetKind.Button ||
                        widget == WidgetKind.TextField ||
                        widget == WidgetKind.Tooltip ||
                        widget == WidgetKind.NavigationItem ||
                        // A bar's is the window's title, where that bar is the window's
                        // caption. It is a property rather than a child because three
                        // design systems centre it, and a bar whose children are an
                        // arbitrary tree gives no way to tell which of them to centre.
                        widget == WidgetKind.TopAppBar ||
                        // A chip's label, and the name of the one action a screen is about,
                        // which is its accessible name where the system draws the glyph
                        // alone.
                        widget == WidgetKind.Chip ||
                        widget == WidgetKind.FloatingAction ||
                        // A badge's short word, shown where a count would be.
                        widget == WidgetKind.Badge ||
                        // A split pane's is its divider's name for a screen reader.
                        widget == WidgetKind.SplitPane ||
                        // A code editor's document as the Host opened it. A text equal to
                        // what the editor holds changes nothing; a different one opens a
                        // new document.
                        widget == WidgetKind.CodeEditor

                // Note: SpacerProps has width and height in the Rust schema, but there are
                // no matching PropertyKind variants, so a Spacer can only be sized with
                // modifiers.
                PropertyKind.Placeholder -> widget == WidgetKind.TextField
                PropertyKind.Multiline -> widget == WidgetKind.TextField
                PropertyKind.Enabled -> widget != WidgetKind.Spacer

                // Windowing properties belong to the lazy container alone.
                PropertyKind.ItemCount ->
                    widget == WidgetKind.LazyColumn ||
                        widget == WidgetKind.LazyRow ||
                        widget == WidgetKind.LazyGrid
                PropertyKind.ItemKey -> true

                // Design primitives, resolved against the design system's token table when
                // the node is drawn.
                PropertyKind.TypeRole,
                PropertyKind.FontSize,
                PropertyKind.FontWeight,
                PropertyKind.LineHeight,
                PropertyKind.LetterSpacing,
                PropertyKind.Color,
                PropertyKind.TextAlign,
                PropertyKind.MaxLines,
                PropertyKind.Overflow,
                -> widget == WidgetKind.Text ||
                    widget == WidgetKind.Button ||
                    widget == WidgetKind.TextField ||
                    // An editor takes the rung of the type ladder, the size and the row
                    // height. Its ink is the design system's or a colour run's, and it
                    // neither aligns nor cuts off its lines.
                    (
                        widget == WidgetKind.CodeEditor &&
                            (
                                property == PropertyKind.TypeRole ||
                                    property == PropertyKind.FontSize ||
                                    property == PropertyKind.LineHeight
                                )
                        ) ||
                    // A chip's label takes a line limit and an overflow, as a button's does.
                    (
                        widget == WidgetKind.Chip &&
                            (property == PropertyKind.MaxLines || property == PropertyKind.Overflow)
                        ) ||
                    // An icon takes a tint through the same Paint attribute text does, and
                    // so does a destination: its icon and label are properties rather than
                    // a child tree, so there is no Text node underneath to colour instead.
                    (
                        property == PropertyKind.Color &&
                            (
                                widget == WidgetKind.Icon ||
                                    widget == WidgetKind.NavigationItem ||
                                    // A tab strip's selection mark, and a slider's
                                    // filled track and thumb. Both are drawn rather
                                    // than written, so there is no Text underneath to
                                    // colour instead.
                                    widget == WidgetKind.Tabs ||
                                    widget == WidgetKind.Slider ||
                                    // A badge's fill. Only its role is read: a badge
                                    // has no literal colour.
                                    widget == WidgetKind.Badge ||
                                    // A layout container's colour is the one the text
                                    // inside it inherits, as CSS `color` is.
                                    widget in INHERITS_TEXT_COLOR
                                )
                        )

                PropertyKind.Arrangement,
                PropertyKind.Spacing,
                PropertyKind.SpaceRole,
                PropertyKind.Alignment,
                -> widget == WidgetKind.Column ||
                    widget == WidgetKind.Row ||
                    widget == WidgetKind.Box ||
                    widget == WidgetKind.LazyColumn ||
                    widget == WidgetKind.LazyRow ||
                    widget == WidgetKind.ScrollColumn ||
                    widget == WidgetKind.ScrollRow ||
                    widget == WidgetKind.FileDropTarget

                PropertyKind.Variant -> widget == WidgetKind.Button

                // An asset id is the whole of what a picture carries. The bytes were read
                // at registration and the id is what crosses per frame.
                PropertyKind.Asset ->
                    widget == WidgetKind.Image || widget == WidgetKind.Icon

                // The pickers carry a value and the ends of the range it may take, in the
                // widget's own unit; a slider carries a position between the same two
                // ends. Nothing here says how the value should be reached.
                PropertyKind.Value,
                PropertyKind.Min,
                PropertyKind.Max,
                -> widget == WidgetKind.DatePicker ||
                    widget == WidgetKind.TimePicker ||
                    widget == WidgetKind.Slider ||
                    // A split pane's side pane width in dp, and the range it may be dragged
                    // over.
                    widget == WidgetKind.SplitPane ||
                    // An indicator's value is how far along it is, and it has no range.
                    (property == PropertyKind.Value && widget == WidgetKind.ProgressIndicator) ||
                    // A scroll container's target scroll offset in dp when the Host asks
                    // to jump or animate to a position.
                    (
                        property == PropertyKind.Value &&
                            (widget == WidgetKind.ScrollColumn || widget == WidgetKind.ScrollRow)
                        ) ||
                    // A Canvas's reference coordinate space (width in Min, height in Max)
                    // when its commands are written in fractional or view-box coordinates.
                    (
                        (property == PropertyKind.Min || property == PropertyKind.Max) &&
                            widget == WidgetKind.Canvas
                        )

                // One boolean for the three toggles: a selected radio button and a switch
                // that is on are the same fact.
                PropertyKind.Checked ->
                    widget == WidgetKind.Checkbox ||
                        widget == WidgetKind.RadioButton ||
                        widget == WidgetKind.Switch ||
                        // A chosen chip or a selected menu item is the same fact again.
                        widget == WidgetKind.Chip ||
                        widget == WidgetKind.Button

                PropertyKind.Steps -> widget == WidgetKind.Slider

                PropertyKind.Determinate,
                PropertyKind.Circular,
                -> widget == WidgetKind.ProgressIndicator ||
                    // On a scroll container, Determinate says whether a requested scroll
                    // animates or jumps immediately.
                    (
                        property == PropertyKind.Determinate &&
                            (widget == WidgetKind.ScrollColumn || widget == WidgetKind.ScrollRow)
                        )

                PropertyKind.Vertical -> widget == WidgetKind.Divider

                // The overlays seed the Renderer's own open state; the tab strip seeds its
                // own selection. Neither is read back every frame.
                PropertyKind.Open -> widget == WidgetKind.Dialog ||
                    widget == WidgetKind.Menu ||
                    // A sheet is an overlay on the same terms as a dialog.
                    widget == WidgetKind.Sheet ||
                    // A popover's is read every time: the Host owns it.
                    widget == WidgetKind.Popover

                // A custom scrim colour for modal and popup overlays.
                PropertyKind.Scrim -> widget == WidgetKind.Dialog ||
                    widget == WidgetKind.Menu ||
                    widget == WidgetKind.Sheet ||
                    // On a popover it is also a boolean: whether to dim the window at all.
                    widget == WidgetKind.Popover

                // The side of its anchor a popover opens on, the edge it lines up with,
                // and the gap between the two.
                PropertyKind.Placement,
                PropertyKind.Align,
                PropertyKind.OffsetDp,
                -> widget == WidgetKind.Popover
                // A field drawn without the design system's frame.
                PropertyKind.Bare -> widget == WidgetKind.TextField ||
                    widget == WidgetKind.Dropdown
                // A wrapping row's gaps, alignments and per-line limit. Only it lays out by
                // lines, so no other widget has anything to read them for.
                PropertyKind.HorizontalSpacing,
                PropertyKind.VerticalSpacing,
                PropertyKind.HorizontalAlignment,
                PropertyKind.VerticalAlignment,
                PropertyKind.MaxItemsInEachRow,
                -> widget == WidgetKind.FlowRow
                // A Dropdown's selection is a position in its own children, the same thing
                // a tab strip's selection is.
                PropertyKind.SelectedIndex ->
                    widget == WidgetKind.Tabs ||
                        widget == WidgetKind.Dropdown ||
                        // Which destination of a set is the current one, counted over the
                        // destinations rather than over all the children.
                        widget == WidgetKind.Navigation ||
                        // Which pane shows when a split pane shows one at a time.
                        widget == WidgetKind.SplitPane
                // Drawing commands belong to the Canvas alone: no other widget draws
                // anything the Host described command by command.
                PropertyKind.Commands -> widget == WidgetKind.Canvas

                // The meaning of an icon. A meaning, never a picture: the artwork is the
                // design system's. A destination carries one, and so does a button,
                // because every toolbar worth copying is a row of icon buttons and
                // nothing else in the vocabulary can place one.
                PropertyKind.Icon ->
                    widget == WidgetKind.NavigationItem ||
                        widget == WidgetKind.Button ||
                        // A glyph before a chip's label, and what the one action a screen
                        // is about means.
                        widget == WidgetKind.Chip ||
                        widget == WidgetKind.FloatingAction

                // Which part of a screen's frame a subtree fills. Only the wrapper a
                // Scaffold puts around a slot carries it.
                PropertyKind.Slot -> widget == WidgetKind.ScaffoldSlot

                // Which named group of a strip a destination is in. Only a destination
                // has one: a group is a run of them, and nothing else in a strip is in a
                // run of anything.
                PropertyKind.Section -> widget == WidgetKind.NavigationItem

                // Runs of different treatment inside one string.
                PropertyKind.Spans -> widget == WidgetKind.Text

                // What text laid out by CSS needs: its own fonts, where its lines may
                // break, its tab stops, sizes the system's font scale leaves alone, and
                // whether it wraps at all. A Text, and its own list of fonts on a TextField
                // A control's label is set by the design system.
                PropertyKind.Font ->
                    widget == WidgetKind.Text ||
                        widget == WidgetKind.TextField ||
                        // An editor set in the application's own monospace list.
                        widget == WidgetKind.CodeEditor

                // Where lines break, in a Text and in a field's typed text and placeholder.
                PropertyKind.WordBreak,
                PropertyKind.OverflowWrap,
                -> widget == WidgetKind.Text || widget == WidgetKind.TextField

                PropertyKind.SpanFonts,
                PropertyKind.TabSize,
                PropertyKind.AbsoluteSize,
                PropertyKind.SoftWrap,
                -> widget == WidgetKind.Text

                // How large an HTML island is drawn. Only the island's root has one.
                PropertyKind.IslandZoom -> widget == WidgetKind.AbsoluteBox

                // How many a badge counts, or the revision of a programmatic scroll request.
                PropertyKind.Count ->
                    widget == WidgetKind.Badge ||
                        widget == WidgetKind.ScrollColumn ||
                        widget == WidgetKind.ScrollRow

                // Whether a split pane's side pane may be folded away, a dropdown rests as
                // a closed field on every design system, or a linear layout sizes its cross
                // axis to its largest child.
                PropertyKind.Collapsible ->
                    widget == WidgetKind.SplitPane ||
                        widget == WidgetKind.Dropdown ||
                        widget == WidgetKind.Row ||
                        widget == WidgetKind.Column

                // What a button is apart from its emphasis: a plain one, or a key of a
                // dense grid. Only a button has a kind; a chip or a floating action is
                // already its own component.
                PropertyKind.ButtonKind -> widget == WidgetKind.Button

                // A code editor's lists, its tab width and the handlers only it reports to.
                PropertyKind.Decorations,
                PropertyKind.SyntaxSpans,
                PropertyKind.TabWidth,
                PropertyKind.OnEditRejected,
                PropertyKind.OnHover,
                PropertyKind.OnSave,
                PropertyKind.OnDecorationClick,
                PropertyKind.Folds,
                PropertyKind.GutterDecorations,
                PropertyKind.OnSelectionChange,
                PropertyKind.OnVisibleRangeChange,
                PropertyKind.OnEditorFocusChange,
                PropertyKind.OnFoldChange,
                // Whether it draws line numbers, how its gutter is spaced, the colours of
                // its own parts, and the handlers of its row height and scroll offset.
                PropertyKind.LineNumbers,
                PropertyKind.GutterMinWidthDp,
                PropertyKind.GutterPaddingDp,
                PropertyKind.TextInsetDp,
                PropertyKind.CodeEditorColors,
                PropertyKind.OnCodeMetrics,
                PropertyKind.OnCodeScrolled,
                -> widget == WidgetKind.CodeEditor

                PropertyKind.OnCompositionChange ->
                    widget == WidgetKind.CodeEditor || widget == WidgetKind.TextField

                // Files over a node and files let go on it. Only the widget that exists
                // to receive them, because a handler is attached whether or not a screen
                // supplied one: on any container these would cost every container in the
                // tree three records for saying nothing.
                PropertyKind.OnFilesEntered,
                PropertyKind.OnFilesDropped,
                -> widget == WidgetKind.FileDropTarget

                // How wide a grid's columns are, said as a count or as a minimum.
                PropertyKind.Columns,
                PropertyKind.MinColumnWidth,
                -> widget == WidgetKind.LazyGrid

                // A window's own properties and handlers. The platform
                // layer reads them; the table only keeps them on the node.
                PropertyKind.WindowTitle,
                PropertyKind.WindowSize,
                PropertyKind.WindowMinSize,
                PropertyKind.WindowResizable,
                PropertyKind.ReportSize,
                PropertyKind.WindowChrome,
                PropertyKind.WindowTitleBar,
                PropertyKind.WindowIcon,
                PropertyKind.WindowPosition,
                PropertyKind.WindowRestoreKey,
                PropertyKind.WindowZoomLevel,
                PropertyKind.DragFromPointer,
                PropertyKind.OnWindowCloseRequested,
                PropertyKind.OnWindowFocusChanged,
                PropertyKind.OnWindowMoved,
                PropertyKind.OnWindowDrag,
                PropertyKind.OnWindowUnavailable,
                PropertyKind.OnWindowClosed,
                -> widget == WidgetKind.Window

                // What a set of destinations becomes in a compact window, where the
                // application chose. Only a navigation has destinations to place.
                PropertyKind.CompactNavigation -> widget == WidgetKind.Navigation

                // A property declared by an extension package belongs to the widget
                // that package declared it for.
                PropertyKind.Progress -> widget == WidgetKind.LinearProgressIndicator
            }
    }
}
