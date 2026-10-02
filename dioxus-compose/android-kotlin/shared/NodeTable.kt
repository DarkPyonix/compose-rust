package dioxus.compose.ui.node

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dioxus.compose.foundation.ChromeInsets
import dioxus.compose.foundation.MessageQueue
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.Window
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.protocol.Modifier as ProtocolModifier

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
    }
}

/**
 * The interpreted node tree.
 *
 * A whole batch is applied inside one `Snapshot.withMutableSnapshot` transaction by
 * [DioxusHost], so no intermediate tree state is ever drawn.
 */
class NodeTable {
    private val nodes = mutableStateMapOf<Int, Node>()
    private val rootChildren = mutableStateListOf<Int>()
    private val errors = mutableListOf<TableError>()

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

    private var revision = 0L

    /** Null until the Host sends its first `SetTheme` record. */
    var theme: Theme? by mutableStateOf(null)
        private set

    /** Null until the Host sends its first `SetWindow` record. */
    var window: Window? by mutableStateOf(null)
        private set

    /** Top-level nodes, in creation order until the Host parents them. */
    val roots: List<Int> get() = rootChildren

    fun node(id: Int): Node? = nodes[id]

    /** Errors collected while applying a batch; drained after the transaction commits. */
    internal fun drainErrors(): List<TableError> {
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
        nodes.clear()
        rootChildren.clear()
        errors.clear()
        assets.clear()
        messages.clear()
        theme = null
        window = null
        materials = 0
        revision = 0
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
            // One record changes the whole tree's appearance. `DioxusContent`
            // resolves it into tokens and rules, and Compose invalidates the readers.
            is Mutation.SetTheme -> theme = mutation.theme
            // Read before there is a window to apply it to. The platform layer asks for
            // this out of the first batch and builds the window from it, so by the time
            // the batch is applied the window already looks the way it says. Keeping it
            // here as well is what lets a later rebuild change it.
            is Mutation.SetWindow -> window = mutation.window
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
        }
    }

    private fun create(mutation: Mutation.Create) {
        if (nodes.containsKey(mutation.nodeId)) {
            fail(TableError.DUPLICATE_NODE, "node ${mutation.nodeId} already exists")
            return
        }
        nodes[mutation.nodeId] = Node(mutation.nodeId, mutation.widget)
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

    companion object {
        /** Node id 0 is the Host's "no node" sentinel, so it names the virtual root. */
        const val ROOT_ID: Int = 0
        private const val MAX_MODIFIERS = 64

        internal fun supportsProperty(widget: WidgetKind, property: PropertyKind): Boolean =
            when (property) {
                // Event properties carry handler ids and are valid on any widget.
                PropertyKind.OnClick,
                PropertyKind.OnValueChange,
                PropertyKind.OnSubmit,
                PropertyKind.OnFocusLost,
                PropertyKind.OnKeyDown,
                PropertyKind.OnRangeRequested,
                PropertyKind.OnDismiss,
                -> true

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
                        widget == WidgetKind.TopAppBar

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
                                    widget == WidgetKind.Slider
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
                    // An indicator's value is how far along it is, and it has no range.
                    (property == PropertyKind.Value && widget == WidgetKind.ProgressIndicator)

                // One boolean for the three toggles: a selected radio button and a switch
                // that is on are the same fact.
                PropertyKind.Checked ->
                    widget == WidgetKind.Checkbox ||
                        widget == WidgetKind.RadioButton ||
                        widget == WidgetKind.Switch

                PropertyKind.Steps -> widget == WidgetKind.Slider

                PropertyKind.Determinate,
                PropertyKind.Circular,
                -> widget == WidgetKind.ProgressIndicator

                PropertyKind.Vertical -> widget == WidgetKind.Divider

                // The overlays seed the Renderer's own open state; the tab strip seeds its
                // own selection. Neither is read back every frame.
                PropertyKind.Open -> widget == WidgetKind.Dialog ||
                    widget == WidgetKind.Menu ||
                    // A sheet is an overlay on the same terms as a dialog.
                    widget == WidgetKind.Sheet
                // A Dropdown's selection is a position in its own children, the same thing
                // a tab strip's selection is.
                PropertyKind.SelectedIndex ->
                    widget == WidgetKind.Tabs ||
                        widget == WidgetKind.Dropdown ||
                        // Which destination of a set is the current one, counted over the
                        // destinations rather than over all the children.
                        widget == WidgetKind.Navigation
                // Drawing commands belong to the Canvas alone: no other widget draws
                // anything the Host described command by command.
                PropertyKind.Commands -> widget == WidgetKind.Canvas

                // The meaning of an icon. A meaning, never a picture: the artwork is the
                // design system's. A destination carries one, and so does a button,
                // because every toolbar worth copying is a row of icon buttons and
                // nothing else in the vocabulary can place one.
                PropertyKind.Icon ->
                    widget == WidgetKind.NavigationItem || widget == WidgetKind.Button

                // Which part of a screen's frame a subtree fills. Only the wrapper a
                // Scaffold puts around a slot carries it.
                PropertyKind.Slot -> widget == WidgetKind.ScaffoldSlot

                // Runs of different treatment inside one string.
                PropertyKind.Spans -> widget == WidgetKind.Text

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

                // A property declared by an extension package belongs to the widget
                // that package declared it for.
                PropertyKind.Progress -> widget == WidgetKind.LinearProgressIndicator
            }
    }
}
