package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.MeasureRecords
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.protocol.WindowHeightClass
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.WindowContent
import dev.darkpyonix.composerust.runtime.WindowSizeReporter
import dev.darkpyonix.composerust.runtime.WindowSafeArea
import dev.darkpyonix.composerust.runtime.WindowCaption
import dev.darkpyonix.composerust.runtime.LocalWindowDrag
import dev.darkpyonix.composerust.runtime.RendererMeasure
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.runtime.windowHeightClassOf
import dev.darkpyonix.composerust.runtime.windowSizeClassOf
import dev.darkpyonix.composerust.runtime.windowReportKey
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import dev.darkpyonix.composerust.runtime.WindowActions
import dev.darkpyonix.composerust.runtime.LocalWindowActions
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.ColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val LABEL = 1

/** A second root, which the Host rewrites when it answers a size. */
private const val ECHO = 2

/** A page as an HTML view sends it: a box the width of the window, and a strip across it. */
private const val PAGE = 10
private const val STRIP = 11

/** A `Window` node of its own, for the tests that need a node to carry a property. */
private const val WINDOW = 5

/** The token the Host would have handed out for a node it wants the size of. */
private const val SIZE_TOKEN = 7

/** Recording dispatcher: the Host is not involved in what the reporter decides to send. */
private class RecordingDispatcher : EventDispatcher {
    val events = mutableListOf<HostEvent>()

    override fun dispatch(event: HostEvent): Boolean {
        events += event
        return false
    }
}

/**
 * The window size class: which class a width belongs to, and the rule that the Host hears
 * about it only when the class changes.
 */
@OptIn(ExperimentalTestApi::class)
class WindowSizeTest {
    private val frames = FrameRequestSource()

    @Test
    fun fr19_9_fullsize_first_child_starts_at_y_zero() = runComposeUiTest {
        var fullSize by mutableStateOf(true)
        val connection = FakeHostConnection(listOf(
            Mutation.Create(LABEL, WidgetKind.Text),
            Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("first child")),
        ))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(200.dp, 200.dp),
                    caption = WindowCaption(height = 32.dp, fullSize = fullSize))
            }
        }
        waitForIdle()
        assertEquals(0f, onNodeWithText("first child").fetchSemanticsNode().boundsInRoot.top)
        fullSize = false
        waitForIdle()
        assertEquals(32f, onNodeWithText("first child").fetchSemanticsNode().boundsInRoot.top)
    }

    /**
     * The window's size reaches the Host from the layout pass that measured it, the way an
     * observed node's does, not from a recomposition that pass schedules. A window that
     * draws only when something asks for a frame (macOS) may not draw that second frame for
     * a while, and until then the Host lays its page out for a 0x0, compact window.
     *
     * One frame: composition and the first layout, and no recomposition after it.
     */
    @Test
    fun fr20_first_measurement_reaches_the_host_in_the_frame_that_made_it() = runComposeUiTest {
        mainClock.autoAdvance = false
        val connection = FakeHostConnection(listOf(
            Mutation.Create(LABEL, WidgetKind.Text),
            Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("first")),
        ))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(1440.dp, 900.dp))
            }
        }
        val sizes = connection.events.filterIsInstance<HostEvent.WindowSizeChanged>().filter { it.nodeId == 0 }
        assertEquals(1, sizes.size, "one report from the first layout: ${connection.events}")
        assertEquals(1440f, sizes[0].widthDp)
        assertEquals(900f, sizes[0].heightDp)
        assertEquals(WindowSizeClass.Expanded, sizes[0].sizeClass)
    }

    /**
     * A Host that lays its own page out answers a size by measuring the nodes on that page,
     * before the call that told it the size returns, and hands back a batch. Measuring a
     * node is a layout of its own, which cannot start while the pass that measured the
     * window is still running, so the size is told once that pass is over.
     *
     * The window asks for every dp, as a page that follows the window does, so the first
     * layout and a resize inside one class both reach the Host.
     */
    @Test
    fun fr20_host_measures_a_node_while_answering_a_window_size() = runComposeUiTest {
        val connection = FakeHostConnection(listOf(
            Mutation.SetWindow(Window(Chrome.Modern, TitleBar.Normal, "", 0, 0, 0, 0, 0, true, reportSize = true)),
            Mutation.Create(LABEL, WidgetKind.Text),
            Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("measured on resize")),
            Mutation.Create(ECHO, WidgetKind.Text),
            Mutation.SetProp(ECHO, PropertyKind.Text, PropertyValue.Text("not told yet")),
        ))
        val calls = mutableListOf<Int>()
        val answers = mutableListOf<MeasuredResult>()
        connection.respondWith { event ->
            if (event !is HostEvent.WindowSizeChanged || event.nodeId != 0) return@respondWith HostResponse()
            val requests = MeasureRequests().node(LABEL, maxWidth = event.widthDp)
            val results = resultBuffer(requests.count)
            calls += RendererMeasure.measure(requests.encode(), requests.count, results)
            answers += results.results(requests.count)
            HostResponse(
                listOf(Mutation.SetProp(ECHO, PropertyKind.Text, PropertyValue.Text("${event.widthDp.toInt()} wide"))),
            )
        }
        var width by mutableStateOf(520.dp)
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(width, 640.dp))
            }
        }
        waitForIdle()
        width = 580.dp
        waitForIdle()

        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        assertTrue(errors.isEmpty(), "measuring from inside a size report failed: ${errors.map { it.message }}")
        val sizes = connection.events.filterIsInstance<HostEvent.WindowSizeChanged>().filter { it.nodeId == 0 }
        assertEquals(listOf(520f, 580f), sizes.map { it.widthDp }, "the first layout, then the resize")
        assertEquals(listOf(MeasureRecords.CALL_OK, MeasureRecords.CALL_OK), calls)
        val drawn = onNodeWithText("measured on resize").fetchSemanticsNode().size
        answers.forEachIndexed { index, measured ->
            assertEquals(MeasureRecords.STATUS_OK, measured.status, "answer $index")
            assertEquals(drawn.width.toFloat(), measured.width, "width in answer $index")
            assertEquals(drawn.height.toFloat(), measured.height, "height in answer $index")
        }
        assertTrue(drawn.width > 0 && drawn.height > 0, "the label measured as nothing")
        // The batch the Host answered the resize with is what is on screen.
        onNodeWithText("580 wide").assertExists()
    }

    /**
     * A window that gets narrower is told to the Host as one that gets wider is, and the
     * page the Host lays out for the new width is the one drawn.
     *
     * The page is what an HTML view sends: every box with the size the Host worked out
     * for it. The Host asks how big the label is before it answers, as it does for a
     * widget it cannot measure itself, and sizes its boxes to the width it was told. A box
     * left at the old width would be centred on the narrower window, so its left edge
     * says whether the answer was drawn.
     */
    @Test
    fun fr20_a_shrunk_window_is_reported_and_its_page_is_laid_out_again() = runComposeUiTest {
        fun sized(width: Float) = listOf(
            Mutation.SetModifier(PAGE, 0, ProtocolModifier.RequiredSize(width, 600f)),
            Mutation.SetModifier(STRIP, 0, ProtocolModifier.RequiredSize(width, 40f)),
        )
        val connection = FakeHostConnection(
            listOf(
                Mutation.SetWindow(Window(Chrome.Modern, TitleBar.Normal, "", 0, 0, 0, 0, 0, true, reportSize = true)),
                Mutation.Create(PAGE, WidgetKind.AbsoluteBox),
                Mutation.Create(STRIP, WidgetKind.AbsoluteBox),
                Mutation.Create(LABEL, WidgetKind.Text),
                Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("a label")),
                Mutation.Insert(PAGE, STRIP, 0),
                Mutation.Insert(STRIP, LABEL, 0),
            ) + sized(900f),
        )
        val answers = mutableListOf<MeasuredResult>()
        connection.respondWith { event ->
            if (event !is HostEvent.WindowSizeChanged || event.nodeId != 0) return@respondWith HostResponse()
            val requests = MeasureRequests().node(LABEL, maxWidth = event.widthDp)
            val results = resultBuffer(requests.count)
            RendererMeasure.measure(requests.encode(), requests.count, results)
            answers += results.results(requests.count)
            HostResponse(sized(event.widthDp))
        }
        var width by mutableStateOf(900.dp)
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(width, 600.dp))
            }
        }
        waitForIdle()
        width = 600.dp
        waitForIdle()

        val sizes = connection.events.filterIsInstance<HostEvent.WindowSizeChanged>().filter { it.nodeId == 0 }
        assertEquals(listOf(900f, 600f), sizes.map { it.widthDp }, "the narrower window was not reported")
        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        assertTrue(errors.isEmpty(), "the Host's measure call failed: ${errors.map { it.message }}")
        answers.forEachIndexed { index, measured ->
            assertEquals(MeasureRecords.STATUS_OK, measured.status, "answer $index")
            assertTrue(measured.width > 0f && measured.height > 0f, "answer $index measured the label as nothing")
        }
        for (id in listOf(PAGE, STRIP)) {
            val drawn = onNodeWithTag(nodeTestTag(id), useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(600, drawn.size.width, "width of node $id after the shrink")
            assertEquals(0f, drawn.positionInRoot.x, "left edge of node $id after the shrink")
        }
    }

    @Test
    fun fr19_9_window_drag_modifier_arm() = runComposeUiTest {
        var drags = 0
        val connection = FakeHostConnection(listOf(
            Mutation.Create(LABEL, WidgetKind.Text),
            Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("drag here")),
            Mutation.SetModifier(LABEL, 0, ProtocolModifier.WindowDrag(true)),
        ))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalWindowDrag provides { drags++ }) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(200.dp, 200.dp))
            }
        }
        onNodeWithText("drag here").performMouseInput { moveTo(center); press(); release() }
        assertEquals(1, drags)
    }

    @Test
    fun fr19_9_window_drag_false_excludes_the_node() = runComposeUiTest {
        var drags = 0
        val connection = FakeHostConnection(listOf(
            Mutation.Create(LABEL, WidgetKind.Text),
            Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("excluded")),
            Mutation.SetModifier(LABEL, 0, ProtocolModifier.WindowDrag(false)),
        ))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalWindowDrag provides { drags++ }) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(200.dp, 200.dp),
                    caption = WindowCaption(height = 32.dp, fullSize = true))
            }
        }
        onNodeWithText("excluded").performMouseInput { moveTo(center); press(); moveBy(Offset(30f, 0f)); release() }
        assertEquals(0, drags)
    }

    @Test
    fun fr19_9_interactive_child_does_not_drag_the_window() = runComposeUiTest {
        var drags = 0
        val connection = FakeHostConnection(listOf(
            Mutation.Create(LABEL, WidgetKind.Box),
            Mutation.SetProp(LABEL, PropertyKind.OnClick, PropertyValue.Integer(1L)),
            Mutation.Create(2, WidgetKind.Text),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("button")),
            Mutation.Insert(LABEL, 2, 0),
        ))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalWindowDrag provides { drags++ }) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(200.dp, 200.dp),
                    caption = WindowCaption(height = 32.dp, fullSize = true))
            }
        }
        onNodeWithText("button", useUnmergedTree = true).performMouseInput { moveTo(center); press(); release() }
        assertEquals(0, drags)
    }

    @Test
    fun fr19_9_clickable_descendant_of_drag_region_receives_click() = runComposeUiTest {
        var drags = 0
        val connection = FakeHostConnection(listOf(
            Mutation.Create(LABEL, WidgetKind.Box),
            Mutation.SetModifier(LABEL, 0, ProtocolModifier.WindowDrag(true)),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetProp(2, PropertyKind.OnClick, PropertyValue.Integer(1L)),
            Mutation.Create(3, WidgetKind.Text),
            Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("child click")),
            Mutation.Insert(2, 3, 0),
            Mutation.Insert(LABEL, 2, 0),
        ))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalWindowDrag provides { drags++ }) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(200.dp, 200.dp))
            }
        }
        onNodeWithText("child click", useUnmergedTree = true).performMouseInput { moveTo(center); press(); release() }
        assertEquals(0, drags)
        assertEquals(1, connection.events.filterIsInstance<HostEvent.Clicked>().size)
    }

    @Test
    fun fr19_9_excluded_child_does_not_block_ancestor_click() = runComposeUiTest {
        var drags = 0
        val connection = FakeHostConnection(listOf(
            Mutation.Create(LABEL, WidgetKind.Box),
            Mutation.SetProp(LABEL, PropertyKind.OnClick, PropertyValue.Integer(1L)),
            Mutation.Create(2, WidgetKind.Text),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("excluded child")),
            Mutation.SetModifier(2, 0, ProtocolModifier.WindowDrag(false)),
            Mutation.Insert(LABEL, 2, 0),
        ))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalWindowDrag provides { drags++ }) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(200.dp, 200.dp),
                    caption = WindowCaption(height = 32.dp, fullSize = true))
            }
        }
        onNodeWithText("excluded child", useUnmergedTree = true).performMouseInput { moveTo(center); press(); release() }
        assertEquals(0, drags)
        assertEquals(1, connection.events.filterIsInstance<HostEvent.Clicked>().size)
    }

    @Test
    fun fr19_9_unconsumed_strip_drag_starts_once_after_slop() = runComposeUiTest {
        var drags = 0
        val connection = FakeHostConnection(listOf(
            Mutation.Create(LABEL, WidgetKind.Text),
            Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("strip")),
        ))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalWindowDrag provides { drags++ }) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(200.dp, 200.dp),
                    caption = WindowCaption(height = 32.dp, fullSize = true))
            }
        }
        onNodeWithText("strip").performMouseInput {
            moveTo(center); press(); moveBy(Offset(30f, 0f)); moveBy(Offset(30f, 0f)); release()
        }
        assertEquals(1, drags)
    }

    /** The safe area the Host is told about for a window whose buttons sit as [caption] says. */
    private fun reportedSafeArea(
        density: Float,
        caption: WindowCaption,
        renderedButtons: Boolean,
    ): HostEvent.WindowSizeChanged {
        lateinit var connection: FakeHostConnection
        runComposeUiTest {
            connection = FakeHostConnection(listOf(
                Mutation.SetTheme(Theme(DesignSystem.Fluent, DesignSystem.Fluent, ColorScheme.Light, adaptive = false)),
                Mutation.Create(LABEL, WidgetKind.Column),
            ))
            setContent {
                val actions = if (renderedButtons) WindowActions({}, {}, {}) else null
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(density),
                    LocalWindowActions provides actions,
                ) {
                    ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(800.dp, 600.dp), caption = caption)
                }
            }
            waitForIdle()
        }
        return connection.events.filterIsInstance<HostEvent.WindowSizeChanged>().last { it.nodeId == 0 }
    }

    /**
     * Where the window buttons are, as the application has to leave room for them. The
     * platform's own buttons (macOS) are at the leading edge, as wide as the window measured
     * them; buttons the renderer draws (Windows, Fluent: three of 46dp, no spacing) at the
     * trailing edge. `top` is the caption's height either way, and every value is in dp, so
     * it is the same at 100, 150 and 200%.
     */
    @Test
    fun fr19_9_the_safe_area_is_leading_on_macos_and_trailing_on_windows_at_every_density() {
        for (density in listOf(1f, 1.5f, 2f)) {
            val mac = reportedSafeArea(
                density,
                WindowCaption(height = 28.dp, buttonsWidth = 70.dp, buttonsAtStart = true),
                renderedButtons = false,
            )
            assertEquals(Triple(28f, 70f, 0f), Triple(mac.safeAreaTop, mac.safeAreaLeading, mac.safeAreaTrailing), "macOS at $density")
            val windows = reportedSafeArea(density, WindowCaption(height = 32.dp), renderedButtons = true)
            assertEquals(Triple(32f, 0f, 138f), Triple(windows.safeAreaTop, windows.safeAreaLeading, windows.safeAreaTrailing), "Windows at $density")
        }
    }

    @Test
    fun fr19_9_safe_area_rides_with_the_window_size_event() {
        val dispatcher = RecordingDispatcher()
        WindowSizeReporter().report(300f, 300f, dispatcher, WindowSafeArea(28f, 78f, 0f))
        val event = dispatcher.events.single() as HostEvent.WindowSizeChanged
        assertEquals(28f, event.safeAreaTop)
        assertEquals(78f, event.safeAreaLeading)
        assertEquals(0f, event.safeAreaTrailing)
    }

    @Test
    fun fr19_9_safe_area_change_alone_resends() {
        val dispatcher = RecordingDispatcher()
        val reporter = WindowSizeReporter()
        reporter.report(300f, 300f, dispatcher, WindowSafeArea(28f, 78f, 0f))
        reporter.report(300f, 300f, dispatcher, WindowSafeArea())
        assertEquals(2, dispatcher.events.size)
        assertEquals(0f, (dispatcher.events.last() as HostEvent.WindowSizeChanged).safeAreaTop)
        // The bottom side alone resends as well, carrying the new value: a navigation bar
        // that appears or goes away moves nothing else.
        reporter.report(300f, 300f, dispatcher, WindowSafeArea(bottom = 24f))
        assertEquals(3, dispatcher.events.size)
        assertEquals(24f, (dispatcher.events.last() as HostEvent.WindowSizeChanged).safeAreaBottom)
        // The same bottom again is no change.
        reporter.report(300f, 300f, dispatcher, WindowSafeArea(bottom = 24f))
        assertEquals(3, dispatcher.events.size)
    }

    /**
     * A desktop window has no strip at the bottom edge for the system's own controls, so
     * its report says 0 there, with a caption or without one.
     */
    @Test
    fun fr19_9_desktop_reports_no_bottom_safe_area() {
        val withCaption = reportedSafeArea(1f, WindowCaption(height = 32.dp), renderedButtons = true)
        assertEquals(0f, withCaption.safeAreaBottom)
        val without = reportedSafeArea(1f, WindowCaption.None, renderedButtons = false)
        assertEquals(0f, without.safeAreaBottom)
    }

    @Test
    fun fr20_size_classes_split_at_six_hundred_and_eight_hundred_and_forty_dp() {
        assertEquals(WindowSizeClass.Compact, windowSizeClassOf(0f))
        assertEquals(WindowSizeClass.Compact, windowSizeClassOf(599.9f))
        assertEquals(WindowSizeClass.Medium, windowSizeClassOf(600f))
        assertEquals(WindowSizeClass.Medium, windowSizeClassOf(839.9f))
        assertEquals(WindowSizeClass.Expanded, windowSizeClassOf(840f))
    }

    @Test
    fun fr20_height_classes_split_at_four_hundred_and_eighty_and_nine_hundred_dp() {
        assertEquals(WindowHeightClass.Compact, windowHeightClassOf(0f))
        assertEquals(WindowHeightClass.Compact, windowHeightClassOf(479.9f))
        assertEquals(WindowHeightClass.Medium, windowHeightClassOf(480f))
        assertEquals(WindowHeightClass.Medium, windowHeightClassOf(899.9f))
        assertEquals(WindowHeightClass.Expanded, windowHeightClassOf(900f))
    }

    @Test
    fun fr20_crossing_a_boundary_sends_one_event_and_resizing_within_a_class_sends_none() {
        val dispatcher = RecordingDispatcher()
        val reporter = WindowSizeReporter()

        // A window that opens small in both axes says nothing: the Host already assumes
        // Compact for each. Nor does any width from 300dp to 599dp at the same height,
        // all of them the same pair of classes.
        for (widthDp in 300..599) {
            reporter.report(widthDp.toFloat(), 400f, dispatcher)
        }
        assertEquals(0, dispatcher.events.size)

        // 599dp to 601dp crosses the boundary: exactly one event.
        assertTrue(reporter.report(601f, 400f, dispatcher))
        assertEquals(1, dispatcher.events.size)
        val event = dispatcher.events[0] as HostEvent.WindowSizeChanged
        assertEquals(WindowSizeClass.Medium, event.sizeClass)
        assertEquals(WindowHeightClass.Compact, event.heightClass)
        assertEquals(601f, event.widthDp)
        assertEquals(400f, event.heightDp)
        assertEquals(0, event.nodeId)
        assertEquals(0L, event.handlerId)

        // And back down is another single event, not a stream of them.
        for (widthDp in 600 downTo 300) {
            reporter.report(widthDp.toFloat(), 400f, dispatcher)
        }
        assertEquals(2, dispatcher.events.size)
        assertEquals(
            WindowSizeClass.Compact,
            (dispatcher.events[1] as HostEvent.WindowSizeChanged).sizeClass,
        )
    }

    @Test
    fun fr20_8_opted_in_window_reports_dp_change_inside_a_class() {
        val dispatcher = RecordingDispatcher()
        val reporter = WindowSizeReporter(3)

        // Both sizes are Expanded wide and Medium high, so the class never changes.
        assertTrue(reporter.report(1280f, 820f, dispatcher, everyDpChange = true))
        assertTrue(reporter.report(1000f, 620f, dispatcher, everyDpChange = true))
        assertEquals(2, dispatcher.events.size)
        val event = dispatcher.events[1] as HostEvent.WindowSizeChanged
        assertEquals(3, event.nodeId)
        assertEquals(1000f, event.widthDp)
        assertEquals(620f, event.heightDp)
        assertEquals(WindowSizeClass.Expanded, event.sizeClass)
        assertEquals(WindowHeightClass.Medium, event.heightClass)
    }

    @Test
    fun fr20_8_default_window_still_reports_only_class_change() {
        val dispatcher = RecordingDispatcher()
        val reporter = WindowSizeReporter()

        assertTrue(reporter.report(1280f, 820f, dispatcher))
        assertFalse(reporter.report(1000f, 620f, dispatcher))
        assertFalse(reporter.report(900f, 500f, dispatcher))
        assertEquals(1, dispatcher.events.size)
    }

    @Test
    fun fr20_8_opted_in_window_ignores_sub_dp_jitter() {
        val dispatcher = RecordingDispatcher()
        val reporter = WindowSizeReporter()

        assertTrue(reporter.report(1000f, 700f, dispatcher, everyDpChange = true))
        // 999.5 rounds up to 1000 and 1000.49 rounds to 1000: the same whole dp.
        assertFalse(reporter.report(999.5f, 700.2f, dispatcher, everyDpChange = true))
        assertFalse(reporter.report(1000.49f, 699.6f, dispatcher, everyDpChange = true))
        assertEquals(1, dispatcher.events.size)
        // 1000.5 rounds up to 1001: one whole dp, one event, carrying the measured size.
        assertTrue(reporter.report(1000.5f, 700f, dispatcher, everyDpChange = true))
        assertEquals(2, dispatcher.events.size)
        assertEquals(1000.5f, (dispatcher.events[1] as HostEvent.WindowSizeChanged).widthDp)
        // Back inside that dp sends nothing more.
        assertFalse(reporter.report(1001.2f, 700f, dispatcher, everyDpChange = true))
        assertEquals(2, dispatcher.events.size)
    }

    @Test
    fun fr20_crossing_a_height_boundary_sends_one_event_and_the_two_axes_share_it() {
        val dispatcher = RecordingDispatcher()
        val reporter = WindowSizeReporter()

        // Growing from 300dp to 479dp tall stays in one class, so nothing is sent.
        for (heightDp in 300..479) {
            reporter.report(400f, heightDp.toFloat(), dispatcher)
        }
        assertEquals(0, dispatcher.events.size)

        assertTrue(reporter.report(400f, 480f, dispatcher))
        assertEquals(1, dispatcher.events.size)
        val taller = dispatcher.events[0] as HostEvent.WindowSizeChanged
        assertEquals(WindowSizeClass.Compact, taller.sizeClass)
        assertEquals(WindowHeightClass.Medium, taller.heightClass)

        // Both axes crossing at once is still one event, because one record holds both.
        assertTrue(reporter.report(900f, 900f, dispatcher))
        assertEquals(2, dispatcher.events.size)
        val both = dispatcher.events[1] as HostEvent.WindowSizeChanged
        assertEquals(WindowSizeClass.Expanded, both.sizeClass)
        assertEquals(WindowHeightClass.Expanded, both.heightClass)
    }

    /**
     * A node that was asked to report its size answers on both axes, in one event.
     *
     * The same record the window uses, so the same rule: a resize that stays inside both
     * classes says nothing, and a resize that leaves either one says it once. This is the
     * node path rather than the root path, and it had its own copy of the remembering,
     * which is how it came to remember only the width.
     */
    @Test
    fun fr28_an_observed_node_reports_both_classes_in_one_event() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(LABEL, WidgetKind.Text),
                Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("hello")),
                // Filling the space, or the node measures to the width of the word and a
                // window that changed size would not change the node's size at all.
                Mutation.SetModifier(LABEL, 1, ProtocolModifier.FillMaxWidth),
                Mutation.SetModifier(LABEL, 2, ProtocolModifier.FillMaxHeight),
                Mutation.SetModifier(LABEL, 3, ProtocolModifier.ObserveSize(SIZE_TOKEN, false)),
            ),
        )
        var height by mutableStateOf(300.dp)
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(connection),
                    Modifier.requiredSize(400.dp, height),
                )
            }
        }
        waitForIdle()
        val sizes = {
            connection.events
                .filterIsInstance<HostEvent.WindowSizeChanged>()
                .filter { it.nodeId == LABEL }
        }
        val before = sizes().size

        // Taller, and across the first height boundary. The width has not moved, so an
        // event here can only have come from the height.
        height = 500.dp
        waitForIdle()
        assertEquals(before + 1, sizes().size)
        val taller = sizes().last()
        assertEquals(WindowSizeClass.Compact, taller.sizeClass)
        assertEquals(WindowHeightClass.Medium, taller.heightClass)

        // Taller again, inside the same class, so nothing is sent.
        height = 700.dp
        waitForIdle()
        assertEquals(before + 1, sizes().size)
    }

    @Test
    fun fr20_window_size_is_the_window_not_the_content() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(LABEL, WidgetKind.Text),
                Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("one line")),
            ),
        )
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                // The window, and what every platform window draws inside it, with no modifier.
                androidx.compose.foundation.layout.Box(Modifier.requiredSize(900.dp, 1000.dp)) {
                    WindowContent(rememberComposeRustHost(connection))
                }
            }
        }
        waitForIdle()
        val event = connection.events.filterIsInstance<HostEvent.WindowSizeChanged>().last()
        assertEquals(900f, event.widthDp)
        assertEquals(1000f, event.heightDp)
        assertEquals(WindowSizeClass.Expanded, event.sizeClass)
        assertEquals(WindowHeightClass.Expanded, event.heightClass)
    }

    @Test
    fun fr20_root_content_reports_its_measured_size_to_the_host() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(LABEL, WidgetKind.Text),
                Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("hello")),
            ),
        )
        var width by mutableStateOf(500.dp)
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                // Pinned so the test asserts on dp, not on whatever density the machine
                // running it happens to have.
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(connection),
                    Modifier.requiredSize(width, 400.dp),
                )
            }
        }
        waitForIdle()
        val sizes = { connection.events.filterIsInstance<HostEvent.WindowSizeChanged>() }
        // Compact in both axes is what the Host already assumes, so nothing was sent.
        assertEquals(0, sizes().size)

        // Still Compact: the window changed size and the Host is not told.
        width = 560.dp
        waitForIdle()
        assertEquals(0, sizes().size)

        width = 700.dp
        waitForIdle()
        assertEquals(1, sizes().size)
        assertEquals(WindowSizeClass.Medium, sizes()[0].sizeClass)
        assertEquals(WindowHeightClass.Compact, sizes()[0].heightClass)
        assertEquals(700f, sizes()[0].widthDp)

        width = 900.dp
        waitForIdle()
        assertEquals(2, sizes().size)
        assertEquals(WindowSizeClass.Expanded, sizes()[1].sizeClass)
    }

    @Test
    fun fr20_8_removing_report_size_returns_to_class_only_reports() {
        val table = NodeTable()
        table.apply(Mutation.Create(WINDOW, WidgetKind.Window))
        table.apply(Mutation.SetProp(WINDOW, PropertyKind.ReportSize, PropertyValue.Bool(true)))
        val flag = { table.node(WINDOW)!!.flag(PropertyKind.ReportSize, false) }
        val dispatcher = RecordingDispatcher()
        val reporter = WindowSizeReporter(WINDOW)

        assertTrue(flag())
        assertTrue(reporter.report(1280f, 820f, dispatcher, everyDpChange = flag()))
        assertTrue(reporter.report(1200f, 820f, dispatcher, everyDpChange = flag()))

        table.apply(Mutation.SetProp(WINDOW, PropertyKind.ReportSize, PropertyValue.None))
        assertFalse(flag())
        // Inside the class again: nothing is sent, as before the window ever opted in.
        assertFalse(reporter.report(1100f, 800f, dispatcher, everyDpChange = flag()))
        assertEquals(2, dispatcher.events.size)
        // A safe-area change and a class change still report.
        assertTrue(reporter.report(1100f, 800f, dispatcher, WindowSafeArea(top = 24f), flag()))
        assertTrue(reporter.report(500f, 800f, dispatcher, WindowSafeArea(top = 24f), flag()))
        assertEquals(4, dispatcher.events.size)
    }

    @Test
    fun fr20_8_safe_area_only_change_reports_with_and_without_the_opt_in() {
        for (everyDp in listOf(true, false)) {
            val dispatcher = RecordingDispatcher()
            val reporter = WindowSizeReporter()
            assertTrue(reporter.report(1000f, 700f, dispatcher, WindowSafeArea(), everyDp))
            assertFalse(reporter.report(1000f, 700f, dispatcher, WindowSafeArea(), everyDp))
            assertTrue(reporter.report(1000f, 700f, dispatcher, WindowSafeArea(top = 28f), everyDp))
            val event = dispatcher.events[1] as HostEvent.WindowSizeChanged
            assertEquals(28f, event.safeAreaTop)
            assertEquals(2, dispatcher.events.size)
        }
    }

    @Test
    fun fr20_8_fractional_dp_is_compared_after_rounding() {
        val dispatcher = RecordingDispatcher()
        val reporter = WindowSizeReporter()

        assertTrue(reporter.report(600.4f, 700f, dispatcher, everyDpChange = true))
        assertFalse(reporter.report(600.45f, 700f, dispatcher, everyDpChange = true))
        // 600.4 rounds to 600 and 600.6 to 601, inside one class.
        assertTrue(reporter.report(600.6f, 700f, dispatcher, everyDpChange = true))
        assertEquals(2, dispatcher.events.size)
        assertEquals(600.6f, (dispatcher.events[1] as HostEvent.WindowSizeChanged).widthDp)
    }

    @Test
    fun fr20_a_window_that_only_crosses_a_height_boundary_still_reports_at_the_first_measurement() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(LABEL, WidgetKind.Text),
                Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("hello")),
            ),
        )
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                // A phone: Compact wide like the state both sides start in, Medium high.
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(390.dp, 844.dp))
            }
        }
        waitForIdle()
        val sizes = connection.events.filterIsInstance<HostEvent.WindowSizeChanged>()
        assertEquals(1, sizes.size, "a height-only class change reaches the Host")
        assertEquals(WindowSizeClass.Compact, sizes[0].sizeClass)
        assertEquals(WindowHeightClass.Medium, sizes[0].heightClass)
        assertEquals(390f, sizes[0].widthDp)
        assertEquals(844f, sizes[0].heightDp)
    }

    @Test
    fun fr20_8_a_compact_window_that_opts_in_reports_its_size_at_the_first_measurement() {
        // 390 by 300 is Compact wide and Compact high, the state both sides start in, so a
        // window that did not opt in stays silent. One that did has never told the Host its
        // size, and its rounded size differs from the (0, 0) it starts with.
        val quiet = RecordingDispatcher()
        assertFalse(WindowSizeReporter().report(390f, 300f, quiet))
        assertEquals(0, quiet.events.size)

        val dispatcher = RecordingDispatcher()
        val reporter = WindowSizeReporter()
        assertTrue(reporter.report(390f, 300f, dispatcher, everyDpChange = true))
        assertEquals(1, dispatcher.events.size)
        val first = dispatcher.events[0] as HostEvent.WindowSizeChanged
        assertEquals(390f, first.widthDp)
        assertEquals(300f, first.heightDp)
        // The same size again is silent.
        assertFalse(reporter.report(390f, 300f, dispatcher, everyDpChange = true))
        assertEquals(1, dispatcher.events.size)
    }

    private fun rootWindowBatch(report: Boolean): List<Mutation> = listOf(
        Mutation.SetWindow(Window(Chrome.Modern, TitleBar.Normal, "", 0, 0, 0, 0, 0, true, reportSize = report)),
        Mutation.Create(LABEL, WidgetKind.Text),
        Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("hello")),
    )

    private fun rootWindowSizes(report: Boolean): List<HostEvent.WindowSizeChanged> {
        var sizes: List<HostEvent.WindowSizeChanged> = emptyList()
        runComposeUiTest {
            val connection = FakeHostConnection(rootWindowBatch(report))
            var size by mutableStateOf(1280.dp to 820.dp)
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                    ComposeRustContent(
                        rememberComposeRustHost(connection),
                        Modifier.requiredSize(size.first, size.second),
                    )
                }
            }
            waitForIdle()
            size = 1000.dp to 620.dp
            waitForIdle()
            sizes = connection.events.filterIsInstance<HostEvent.WindowSizeChanged>()
        }
        return sizes
    }

    @Test
    fun fr20_8_implicit_root_window_reports_dp_change_inside_a_class() {
        val sizes = rootWindowSizes(true)
        // Both sizes are Expanded wide, and Medium high at 820 and 620 alike, so only the
        // opt-in can have sent the second event.
        assertTrue(sizes.size >= 2, "expected a report for the dp change, got ${sizes.size}")
        assertEquals(1000f, sizes.last().widthDp)
        assertEquals(620f, sizes.last().heightDp)
    }

    @Test
    fun fr20_8_implicit_root_window_default_is_class_only() {
        val sizes = rootWindowSizes(false)
        assertTrue(sizes.none { it.widthDp == 1000f }, "a window that did not opt in reported a dp change")
    }

    @Test
    fun fr20_8_report_size_for_root_follows_set_window() {
        val table = NodeTable()
        assertFalse(table.reportSizeFor(0))
        table.apply(Mutation.SetWindow(Window(Chrome.Modern, TitleBar.Normal, "", 0, 0, 0, 0, 0, true, reportSize = true)))
        assertTrue(table.reportSizeFor(0))
        table.apply(Mutation.SetWindow(Window(Chrome.Modern, TitleBar.Normal, "", 0, 0, 0, 0, 0, true)))
        assertFalse(table.reportSizeFor(0))
    }

    private fun windowBatch(report: Boolean): List<Mutation> = listOf(
        Mutation.Create(WINDOW, WidgetKind.Window),
        Mutation.SetProp(WINDOW, PropertyKind.ReportSize, PropertyValue.Bool(report)),
        Mutation.Insert(0, WINDOW, -1),
        Mutation.Create(LABEL, WidgetKind.Text),
        Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("hello")),
        Mutation.Insert(WINDOW, LABEL, -1),
    )

    private fun flagReachesTheReporter(report: Boolean) = runComposeUiTest {
        val connection = FakeHostConnection(windowBatch(report))
        var width by mutableStateOf(1000.dp)
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                ComposeRustContent(
                    rememberComposeRustHost(connection),
                    Modifier.requiredSize(width, 700.dp),
                    windowNodeId = WINDOW,
                )
            }
        }
        waitForIdle()
        val sizes = { connection.events.filterIsInstance<HostEvent.WindowSizeChanged>() }
        // Compact to Expanded is a class change, so the first measurement is always sent.
        assertEquals(1, sizes().size)
        assertEquals(WINDOW, sizes()[0].nodeId)

        // Whole dp inside Expanded: sent only for a window that opted in.
        width = 1003.dp
        waitForIdle()
        assertEquals(if (report) 2 else 1, sizes().size)
        if (report) assertEquals(1003f, sizes()[1].widthDp)
    }

    @Test
    fun fr20_8_report_size_flag_reaches_the_reporter_through_the_host() = flagReachesTheReporter(true)

    @Test
    fun fr20_8_window_without_report_size_stays_class_only_through_the_host() = flagReachesTheReporter(false)

    @Test
    fun fr20_8_a_density_change_at_the_same_pixel_size_changes_the_reported_dp() = runComposeUiTest {
        val connection = FakeHostConnection(windowBatch(true))
        var density by mutableStateOf(2f)
        setContent {
            val d = density
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(d)) {
                // 1200 by 1400 pixels at either density.
                ComposeRustContent(
                    rememberComposeRustHost(connection),
                    Modifier.requiredSize((1200f / d).dp, (1400f / d).dp),
                    windowNodeId = WINDOW,
                )
            }
        }
        waitForIdle()
        val sizes = { connection.events.filterIsInstance<HostEvent.WindowSizeChanged>() }
        assertEquals(1, sizes().size)
        assertEquals(600f, sizes()[0].widthDp)
        density = 1.5f
        waitForIdle()
        assertEquals(2, sizes().size)
        assertEquals(800f, sizes()[1].widthDp)
        assertEquals(WindowSizeClass.Medium, sizes()[1].sizeClass)
    }

    private fun rootSizesWhile(report: Boolean, first: Pair<Float, Float>, vararg then: List<Pair<Float, Float>>): List<HostEvent.WindowSizeChanged> {
        var sizes: List<HostEvent.WindowSizeChanged> = emptyList()
        runComposeUiTest {
            val connection = FakeHostConnection(rootWindowBatch(report))
            var size by mutableStateOf(first.first.dp to first.second.dp)
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                    ComposeRustContent(
                        rememberComposeRustHost(connection),
                        Modifier.requiredSize(size.first, size.second),
                    )
                }
            }
            waitForIdle()
            // Each list is one frame: every size in it is set before the frame runs.
            for (frame in then) {
                for ((w, h) in frame) size = w.dp to h.dp
                waitForIdle()
            }
            sizes = connection.events.filterIsInstance<HostEvent.WindowSizeChanged>()
        }
        return sizes
    }

    /**
     * 520 by 640 is Compact wide, the class both sides start in, and Medium high. Nothing
     * about the width changes, so the report has to come from the height class alone.
     */
    @Test
    fun fr20_4_a_window_that_stays_compact_wide_reports_at_first_layout() {
        val sizes = rootSizesWhile(false, 520f to 640f)
        assertEquals(1, sizes.size, "the first layout of a 520x640 window reaches the Host")
        assertEquals(520f, sizes[0].widthDp)
        assertEquals(640f, sizes[0].heightDp)
        assertEquals(WindowSizeClass.Compact, sizes[0].sizeClass)
        assertEquals(WindowHeightClass.Medium, sizes[0].heightClass)
    }

    @Test
    fun fr20_4_a_default_window_resized_inside_one_class_sends_nothing_more() {
        val sizes = rootSizesWhile(false, 520f to 640f, listOf(580f to 640f))
        assertEquals(1, sizes.size)
        assertEquals(520f, sizes[0].widthDp)
    }

    /** Compact in both axes and opted in: only the opt-in can make the first layout report. */
    @Test
    fun fr20_8_an_opted_in_compact_window_reports_at_first_layout() {
        val sizes = rootSizesWhile(true, 520f to 400f)
        assertEquals(1, sizes.size, "an opted-in window that never changes class still reports its size")
        assertEquals(520f, sizes[0].widthDp)
        assertEquals(400f, sizes[0].heightDp)
    }

    @Test
    fun fr20_8_an_opted_in_window_reports_a_resize_inside_compact() {
        val sizes = rootSizesWhile(true, 520f to 640f, listOf(580f to 640f), listOf(580f to 700f))
        assertEquals(3, sizes.size)
        assertEquals(580f, sizes[1].widthDp)
        assertEquals(640f, sizes[1].heightDp)
        assertEquals(700f, sizes[2].heightDp)
        assertTrue(sizes.all { it.sizeClass == WindowSizeClass.Compact && it.heightClass == WindowHeightClass.Medium })
    }

    @Test
    fun fr20_8_resizes_within_one_frame_are_one_report_with_the_last_size() {
        val sizes = rootSizesWhile(true, 520f to 640f, listOf(540f to 640f, 560f to 650f, 580f to 660f))
        assertEquals(2, sizes.size)
        assertEquals(580f, sizes[1].widthDp)
        assertEquals(660f, sizes[1].heightDp)
    }

    /** The key the composition subscribes to moves exactly when the reporter would send. */
    @Test
    fun fr20_8_report_key_changes_exactly_when_the_reporter_sends() {
        val cases = listOf(520f to 640f, 520.3f to 640f, 580f to 640f, 580f to 700f, 700f to 700f, 700f to 400f, 700.6f to 400.2f)
        for (everyDp in listOf(false, true)) {
            val reporter = WindowSizeReporter()
            val dispatcher = RecordingDispatcher()
            var last = windowReportKey(0f, 0f, everyDp)
            // The reporter starts at Compact and Compact; with the opt-in, at (0, 0) dp.
            for ((w, h) in cases) {
                val key = windowReportKey(w, h, everyDp)
                assertEquals(key != last, reporter.report(w, h, dispatcher, everyDpChange = everyDp), "$w x $h, every dp $everyDp")
                last = key
            }
        }
    }
}
