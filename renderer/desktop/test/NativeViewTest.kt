package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.LocalNativeViewWindow
import dev.darkpyonix.composerust.foundation.NativeHole
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.NativeViewPlatform
import dev.darkpyonix.composerust.foundation.NativeViewWindow
import dev.darkpyonix.composerust.foundation.NativeViewWindowEvents
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.NativeViewWire
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.ScrollWire
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LIST = 1
private const val ABOVE = 2
private const val VIEW = 3
private const val BELOW = 4
private const val SECOND = 5
private const val FIELD = 6
private const val POPOVER = 7
private const val ANCHOR = 8
private const val CONTENT = 9
private const val FOCUS_HANDLER = 41L
private const val HANDLE = 0x1000L
private const val OTHER_HANDLE = 0x2000L

/**
 * A window's platform view slot as the renderer drives it, with every call written down: the
 * same interface the macOS window gives, standing in for it on the JVM. It keeps focus the
 * way the window does: a view that is hidden or taken out while it has focus gives it back to
 * the scene first, and says so.
 */
private class FakeNativeViewWindow : NativeViewWindow {
    override var parentHandle = 0x6000_0000L
    override val parentKind = NativeViewWire.PARENT_APPKIT
    override var scale = 1f
    override var events: NativeViewWindowEvents? = null

    /** Whether the next attach is taken. */
    var takes = true
    val attached = LinkedHashMap<Int, Long>()
    val attachOrder = ArrayList<Int>()
    val detached = ArrayList<Pair<Int, Long>>()
    val frames = HashMap<Int, NativeRect>()
    val frameLog = ArrayList<Pair<Int, NativeRect>>()
    val clips = HashMap<Int, NativeRect?>()
    val holes = HashMap<Int, List<NativeHole>>()
    val visible = HashMap<Int, Boolean>()
    var focused: Int? = null
    var sceneFocusCalls = 0

    override fun attach(id: Int, handle: Long): Boolean {
        if (!takes || handle == 0L || id in attached || handle in attached.values) return false
        attached[id] = handle
        attachOrder += id
        visible[id] = false
        return true
    }

    override fun setFrame(id: Int, x: Int, y: Int, width: Int, height: Int) {
        val rect = NativeRect(x, y, width, height)
        frames[id] = rect
        frameLog += id to rect
    }

    override fun setVisibleRegion(id: Int, clip: NativeRect?, holes: List<NativeHole>) {
        clips[id] = clip
        this.holes[id] = holes
    }

    override fun setVisible(id: Int, visible: Boolean) {
        if (!visible && focused == id) focusScene()
        this.visible[id] = visible
    }

    override fun focus(id: Int) {
        if (id !in attached || visible[id] != true) return
        val before = focused
        if (before == id) return
        focused = id
        if (before != null) events?.focusChanged(before, false)
        events?.focusChanged(id, true)
    }

    override fun focusScene() {
        sceneFocusCalls++
        val before = focused ?: return
        focused = null
        events?.focusChanged(before, false)
    }

    override fun detach(id: Int) {
        if (focused == id) focusScene()
        val handle = attached.remove(id) ?: return
        detached += id to handle
        visible.remove(id)
    }

    /** The window stopped being the key window, or became it again, as the fork reports it. */
    fun keyChanged(key: Boolean) {
        val slot = focused ?: return
        events?.focusChanged(slot, key)
    }

    /** A click inside the view: the platform gives it the keyboard. */
    fun click(id: Int) = focus(id)
}

private class NativeViewScreen(val connection: FakeHostConnection, val host: ComposeRustHost, val window: FakeNativeViewWindow) {
    val errors: List<HostEvent.ProtocolError>
        get() = connection.events.filterIsInstance<HostEvent.ProtocolError>()
    val parents: List<HostEvent.NativeViewParent>
        get() = connection.events.filterIsInstance<HostEvent.NativeViewParent>()
    val focus: List<Boolean>
        get() = connection.events.filterIsInstance<HostEvent.NativeViewFocus>().map { it.focused }
}

private fun theme() = Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false))

private fun nativeView(id: Int, width: Float, height: Float, handle: Long = HANDLE): List<Mutation> = buildList {
    add(Mutation.Create(id, WidgetKind.NativeView))
    add(Mutation.SetModifier(id, 0, ProtocolModifier.Size(width, height)))
    add(Mutation.SetProp(id, PropertyKind.OnNativeViewFocus, PropertyValue.Integer(FOCUS_HANDLER)))
    if (handle != 0L) add(Mutation.AttachNativeView(id, handle))
}

/**
 * A column 200 by 300 that scrolls: a box 200 by 100, the view 200 by 100, and a box 200 by
 * 1000 below it, at one pixel to the dp. The view starts at y 100.
 */
private fun scrollingPage(vararg more: Mutation): List<Mutation> = buildList {
    add(theme())
    add(Mutation.Create(LIST, WidgetKind.ScrollColumn))
    add(Mutation.SetModifier(LIST, 0, ProtocolModifier.Size(200f, 300f)))
    add(Mutation.Create(ABOVE, WidgetKind.Box))
    add(Mutation.SetModifier(ABOVE, 0, ProtocolModifier.Size(200f, 100f)))
    add(Mutation.Insert(LIST, ABOVE, 0))
    addAll(nativeView(VIEW, 200f, 100f))
    add(Mutation.Insert(LIST, VIEW, 1))
    add(Mutation.Create(BELOW, WidgetKind.Box))
    add(Mutation.SetModifier(BELOW, 0, ProtocolModifier.Size(200f, 1000f)))
    add(Mutation.Insert(LIST, BELOW, 2))
    addAll(more)
}

private fun scrollTo(node: Int, y: Double) = Mutation.ScrollTo(node, 0, ScrollWire.TO_Y, 0.0, y)

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.screen(batch: List<Mutation>, window: FakeNativeViewWindow = FakeNativeViewWindow()): NativeViewScreen {
    val connection = FakeHostConnection(batch)
    lateinit var host: ComposeRustHost
    setContent {
        CompositionLocalProvider(
            LocalDensity provides Density(1f),
            LocalNativeViewWindow provides window,
        ) {
            host = rememberComposeRustHost(connection)
            ComposeRustContent(host, Modifier.fillMaxSize())
        }
    }
    waitForIdle()
    return NativeViewScreen(connection, host, window)
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.send(screen: NativeViewScreen, vararg mutations: Mutation) {
    runOnIdle {
        screen.connection.scheduleFrame(mutations.toList())
        screen.host.renderFrame(0L)
    }
    waitForIdle()
}

/**
 * `NativeView` on the renderer's side, against a stand-in for the window's slot: where the view
 * is placed, what part of it shows, how focus moves, what the Host is told, and what is refused.
 * The same behaviour against real `NSView`s in a real window is `NativeViewWindowTest` in the
 * macOS renderer's tests, which also reads the window's image back.
 */
@OptIn(ExperimentalTestApi::class)
class NativeViewTest {
    @BeforeTest
    fun windowsHaveTheSlot() {
        NativeViewPlatform.available = true
    }

    @AfterTest
    fun putThePlatformBack() {
        NativeViewPlatform.available = false
    }

    @Test
    fun fr81_parent_handle_is_reported_once_per_window() = runComposeUiTest {
        val screen = screen(scrollingPage())
        val parent = screen.parents.single()
        assertEquals(0, parent.nodeId, "the single window's report names the root")
        assertEquals(0L, parent.handlerId)
        assertEquals(0x6000_0000L, parent.handle)
        assertEquals(NativeViewWire.PARENT_APPKIT, parent.kind)
        assertEquals(1f, parent.scale)

        // A second view in the same window is not a second report.
        send(screen, *nativeView(SECOND, 50f, 50f, OTHER_HANDLE).toTypedArray(), Mutation.Insert(LIST, SECOND, 3))
        assertEquals(1, screen.parents.size)

        // A new scale is.
        runOnIdle {
            screen.window.scale = 2f
            screen.window.events!!.scaleChanged(2f)
        }
        waitForIdle()
        assertEquals(listOf(1f, 2f), screen.parents.map { it.scale })
    }

    @Test
    fun fr81_tree_without_native_view_sends_no_parent() = runComposeUiTest {
        val screen = screen(listOf(theme(), Mutation.Create(LIST, WidgetKind.Box)))
        assertTrue(screen.parents.isEmpty())
        assertTrue(screen.window.attached.isEmpty())
        assertTrue(screen.window.frameLog.isEmpty(), "nothing is asked of the window")
    }

    @Test
    fun fr81_view_follows_layout_and_scroll_in_the_same_frame() = runComposeUiTest {
        val screen = screen(scrollingPage())
        val first = screen.window.frames[VIEW]
        assertEquals(NativeRect(0, 100, 200, 100), first, "the frame is the node's bounds in the window")
        assertEquals(true, screen.window.visible[VIEW])

        // Frame by frame, with the clock stopped between them: in every frame the view is
        // where the content is, so in the frame that scrolled the view moved too.
        mainClock.autoAdvance = false
        runOnIdle {
            screen.connection.scheduleFrame(listOf(scrollTo(LIST, 100.0)))
            screen.host.renderFrame(0L)
        }
        var frames = 0
        while (frames++ < 20) {
            mainClock.advanceTimeByFrame()
            val content = onNodeWithTag(nodeTestTag(VIEW)).getUnclippedBoundsInRoot().top.value.roundToInt()
            assertEquals(content, screen.window.frames[VIEW]!!.y, "frame $frames: the view trails the content")
            if (content == 0) break
        }
        assertEquals(0, screen.window.frames[VIEW]!!.y, "a 100 pixel scroll moved the view 100 pixels")
        mainClock.autoAdvance = true
    }

    @Test
    fun fr81_view_is_clipped_by_its_ancestors() = runComposeUiTest {
        // At y 250 the 100 high view is half outside the 300 high column.
        val screen = screen(scrollingPage())
        send(screen, Mutation.SetModifier(ABOVE, 0, ProtocolModifier.Size(200f, 250f)))
        assertEquals(NativeRect(0, 250, 200, 100), screen.window.frames[VIEW])
        assertEquals(NativeRect(0, 250, 200, 50), screen.window.clips[VIEW], "only the half inside the column shows")
        assertEquals(true, screen.window.visible[VIEW])

        // All the way out: hidden, and still attached.
        send(screen, Mutation.SetModifier(ABOVE, 0, ProtocolModifier.Size(200f, 400f)))
        assertEquals(false, screen.window.visible[VIEW])
        assertEquals(HANDLE, screen.window.attached[VIEW])

        // Back in: shown again.
        send(screen, Mutation.SetModifier(ABOVE, 0, ProtocolModifier.Size(200f, 100f)))
        assertEquals(true, screen.window.visible[VIEW])
        assertNull(screen.window.clips[VIEW], "the whole frame shows, so nothing is cut")
    }

    @Test
    fun fr81_overlay_above_the_view_shows_through_a_rounded_hole() = runComposeUiTest {
        val popover = listOf(
            Mutation.Create(POPOVER, WidgetKind.Popover),
            Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(true)),
            Mutation.Create(ANCHOR, WidgetKind.Box),
            Mutation.SetModifier(ANCHOR, 0, ProtocolModifier.Size(200f, 20f)),
            Mutation.Insert(POPOVER, ANCHOR, 0),
            Mutation.Create(CONTENT, WidgetKind.Box),
            Mutation.SetModifier(CONTENT, 0, ProtocolModifier.Size(120f, 120f)),
            Mutation.Insert(POPOVER, CONTENT, 1),
        )
        val screen = screen(
            buildList {
                add(theme())
                add(Mutation.Create(LIST, WidgetKind.Column))
                addAll(popover)
                add(Mutation.Insert(LIST, POPOVER, 0))
                addAll(nativeView(VIEW, 200f, 200f))
                add(Mutation.Insert(LIST, VIEW, 1))
            },
        )
        val holes = screen.window.holes[VIEW].orEmpty()
        assertEquals(1, holes.size, "the open popover is cut out of the view: $holes")
        val hole = holes.single()
        assertTrue(hole.cornerRadius > 0, "the hole has the popover's rounded corners: $hole")
        val frame = screen.window.frames[VIEW]!!
        assertTrue(hole.rect.intersects(frame), "the hole is over the view: $hole, $frame")
        assertTrue(hole.rect.width > 120 && hole.rect.height > 120, "the hole takes the shadow in: $hole")

        send(screen, Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(false)))
        assertTrue(screen.window.holes[VIEW].orEmpty().isEmpty(), "closed, the hole is gone")
    }

    @Test
    fun fr81_scrim_over_the_window_covers_the_whole_view() = runComposeUiTest {
        val dialog = 10
        val screen = screen(
            buildList {
                add(theme())
                add(Mutation.Create(LIST, WidgetKind.Column))
                addAll(nativeView(VIEW, 200f, 100f))
                add(Mutation.Insert(LIST, VIEW, 0))
                add(Mutation.Create(dialog, WidgetKind.Dialog))
                add(Mutation.SetProp(dialog, PropertyKind.Open, PropertyValue.Bool(true)))
                add(Mutation.Insert(LIST, dialog, 1))
            },
        )
        val frame = screen.window.frames[VIEW]!!
        assertTrue(
            screen.window.holes[VIEW].orEmpty().any { it.rect == frame },
            "a dimmed window covers the view: ${screen.window.holes[VIEW]}",
        )
        assertEquals(HANDLE, screen.window.attached[VIEW], "covered, not taken out")
    }

    @Test
    fun fr81_hidden_view_keeps_its_state() = runComposeUiTest {
        val screen = screen(scrollingPage())
        send(screen, Mutation.SetProp(VIEW, PropertyKind.NativeVisible, PropertyValue.Bool(false)))
        assertEquals(false, screen.window.visible[VIEW])
        assertEquals(HANDLE, screen.window.attached[VIEW], "hidden, not detached")
        assertTrue(screen.window.detached.isEmpty())
        send(screen, Mutation.SetProp(VIEW, PropertyKind.NativeVisible, PropertyValue.Bool(true)))
        assertEquals(true, screen.window.visible[VIEW])
        assertEquals(listOf(VIEW), screen.window.attachOrder, "the same view, never attached again")
    }

    @Test
    fun fr81_focus_moves_between_the_view_and_the_scene() = runComposeUiTest {
        val screen = screen(scrollingPage())
        runOnIdle { screen.window.click(VIEW) }
        waitForIdle()
        assertEquals(listOf(true), screen.focus)
        assertEquals(FOCUS_HANDLER, screen.connection.events.filterIsInstance<HostEvent.NativeViewFocus>().last().handlerId)

        send(screen, Mutation.FocusNativeView(VIEW, false))
        assertEquals(listOf(true, false), screen.focus)
        assertNull(screen.window.focused)

        send(screen, Mutation.FocusNativeView(VIEW, true))
        assertEquals(VIEW, screen.window.focused)
        assertEquals(listOf(true, false, true), screen.focus)

        // A Compose node taking focus takes it from the view.
        send(screen, Mutation.RequestFocus(LIST))
        assertNull(screen.window.focused)
        assertEquals(listOf(true, false, true, false), screen.focus)
    }

    @Test
    fun fr81_focus_returns_to_the_scene_on_remove_hide_and_deactivate() = runComposeUiTest {
        val screen = screen(scrollingPage())
        // Hidden.
        send(screen, Mutation.FocusNativeView(VIEW, true))
        send(screen, Mutation.SetProp(VIEW, PropertyKind.NativeVisible, PropertyValue.Bool(false)))
        assertEquals(listOf(true, false), screen.focus)
        send(screen, Mutation.SetProp(VIEW, PropertyKind.NativeVisible, PropertyValue.Bool(true)))

        // The window going to the background and back.
        send(screen, Mutation.FocusNativeView(VIEW, true))
        runOnIdle { screen.window.keyChanged(false) }
        waitForIdle()
        runOnIdle { screen.window.keyChanged(true) }
        waitForIdle()
        assertEquals(listOf(true, false, true, false, true), screen.focus)

        // Removed.
        send(screen, Mutation.Remove(VIEW))
        assertEquals(listOf(true, false, true, false, true, false), screen.focus)
        assertNull(screen.window.focused)
    }

    @Test
    fun fr81_later_attached_view_is_above() = runComposeUiTest {
        val screen = screen(scrollingPage())
        send(screen, *nativeView(SECOND, 200f, 100f, OTHER_HANDLE).toTypedArray(), Mutation.Insert(LIST, SECOND, 3))
        assertEquals(listOf(VIEW, SECOND), screen.window.attachOrder, "attached in this order, so the second is above")
    }

    @Test
    fun fr81_detach_removes_without_destroying() = runComposeUiTest {
        val screen = screen(scrollingPage())
        send(screen, Mutation.AttachNativeView(VIEW, 0L))
        assertEquals(listOf(VIEW to HANDLE), screen.window.detached)
        assertFalse(VIEW in screen.window.attached)
        // A new handle goes in where the old one was.
        send(screen, Mutation.AttachNativeView(VIEW, OTHER_HANDLE))
        assertEquals(OTHER_HANDLE, screen.window.attached[VIEW])
        // Removing the node takes the view out too.
        send(screen, Mutation.Remove(VIEW))
        assertEquals(listOf(VIEW to HANDLE, VIEW to OTHER_HANDLE), screen.window.detached)
        assertTrue(screen.errors.isEmpty(), "${screen.errors}")
    }

    @Test
    fun fr81_native_view_errors_are_protocol_errors() = runComposeUiTest {
        val screen = screen(scrollingPage(Mutation.Create(SECOND, WidgetKind.NativeView), Mutation.Insert(LIST, SECOND, 3)))
        fun codes() = screen.errors.map { it.code }

        // Unknown node, and a node that is not a NativeView.
        send(screen, Mutation.AttachNativeView(999, HANDLE), Mutation.FocusNativeView(999, true))
        assertEquals(listOf(TableError.UNKNOWN_NODE, TableError.UNKNOWN_NODE), codes())
        send(screen, Mutation.AttachNativeView(ABOVE, HANDLE), Mutation.FocusNativeView(ABOVE, true))
        assertEquals(TableError.NOT_NATIVE_VIEW, codes()[2])
        assertEquals(TableError.NOT_NATIVE_VIEW, codes()[3])
        assertEquals(21, TableError.NOT_NATIVE_VIEW)

        // A handle already attached to another node, with the rest of the batch applied.
        send(
            screen,
            Mutation.AttachNativeView(SECOND, HANDLE),
            Mutation.SetModifier(ABOVE, 0, ProtocolModifier.Size(200f, 120f)),
        )
        assertEquals(TableError.NATIVE_VIEW_REJECTED, codes()[4])
        assertEquals(22, TableError.NATIVE_VIEW_REJECTED)
        assertEquals(NativeRect(0, 120, 200, 100), screen.window.frames[VIEW], "the rest of the batch applied")

        // A handle the window will not take.
        screen.window.takes = false
        send(screen, Mutation.AttachNativeView(SECOND, 0x3000L))
        assertEquals(TableError.NATIVE_VIEW_REJECTED, codes()[5])

        // The properties belong to a NativeView, and nothing goes inside one.
        send(
            screen,
            Mutation.SetProp(ABOVE, PropertyKind.NativeVisible, PropertyValue.Bool(false)),
            Mutation.SetProp(ABOVE, PropertyKind.OnNativeViewFocus, PropertyValue.Integer(1L)),
            Mutation.Create(20, WidgetKind.Text),
            Mutation.Insert(VIEW, 20, 0),
        )
        assertEquals(
            listOf(TableError.UNSUPPORTED_PROPERTY, TableError.UNSUPPORTED_PROPERTY, TableError.INVALID_CHILDREN),
            codes().drop(6),
        )
        assertEquals(HANDLE, screen.window.attached[VIEW], "the view that was there is untouched")
    }

    @Test
    fun fr81_unsupported_platform_refuses_the_view() = runComposeUiTest {
        NativeViewPlatform.available = false
        val connection = FakeHostConnection(scrollingPage())
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
            }
        }
        waitForIdle()
        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        assertEquals(listOf(TableError.NATIVE_VIEW_UNSUPPORTED), errors.map { it.code })
        assertEquals(23, TableError.NATIVE_VIEW_UNSUPPORTED)
        assertTrue(connection.events.none { it is HostEvent.NativeViewParent }, "no window has a slot to report")
    }
}
