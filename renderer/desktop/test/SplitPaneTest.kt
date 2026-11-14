package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.SplitPanePresentation
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.foundation.SidebarShortcuts
import dev.darkpyonix.composerust.foundation.splitBackTestTag
import dev.darkpyonix.composerust.foundation.splitBodyTestTag
import dev.darkpyonix.composerust.foundation.splitDividerTestTag
import dev.darkpyonix.composerust.foundation.splitPaneStyle
import dev.darkpyonix.composerust.foundation.splitSideTestTag
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.hostPlatformOverride
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.designShowcaseRecords
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier

private const val ROOT = 1
private const val SPLIT = 2
private const val SIDE = 3
private const val BODY = 4
private const val SIDE_TEXT = 5
private const val BODY_TEXT = 6
private const val EXTRA = 7
private const val OUTER = 20
private const val ON_CHANGE = 70L
private const val ON_DISMISS = 80L

/** A split pane holding a side pane and a body, with whatever range and state it is given. */
private fun splitTree(
    system: DesignSystem = DesignSystem.Fluent,
    value: Float? = 320f,
    min: Float? = 200f,
    max: Float? = 400f,
    collapsible: Boolean = false,
    selected: Long? = null,
    width: Float? = null,
    children: Int = 2,
    collapseBelow: Float? = null,
): List<Mutation> {
    val records = mutableListOf<Mutation>(
        Mutation.SetTheme(Theme(system, system, ColorScheme.Light, false)),
        Mutation.Create(ROOT, WidgetKind.Column),
        Mutation.SetModifier(ROOT, 0, ProtocolModifier.FillMaxWidth),
        Mutation.SetModifier(ROOT, 1, ProtocolModifier.FillMaxHeight),
        Mutation.Create(SPLIT, WidgetKind.SplitPane),
        Mutation.SetModifier(
            SPLIT,
            0,
            if (width == null) ProtocolModifier.FillMaxWidth else ProtocolModifier.Width(width),
        ),
        Mutation.SetModifier(SPLIT, 1, ProtocolModifier.FillMaxHeight),
        Mutation.SetProp(SPLIT, PropertyKind.Collapsible, PropertyValue.Bool(collapsible)),
        Mutation.SetProp(SPLIT, PropertyKind.Text, PropertyValue.Text("Sessions")),
        Mutation.SetProp(SPLIT, PropertyKind.OnValueChange, PropertyValue.Integer(ON_CHANGE)),
        Mutation.SetProp(SPLIT, PropertyKind.OnDismiss, PropertyValue.Integer(ON_DISMISS)),
        Mutation.Create(SIDE, WidgetKind.Column),
        Mutation.Create(SIDE_TEXT, WidgetKind.Text),
        Mutation.SetProp(SIDE_TEXT, PropertyKind.Text, PropertyValue.Text("First session")),
        Mutation.Insert(SIDE, SIDE_TEXT, 0),
        Mutation.Create(BODY, WidgetKind.Column),
        Mutation.Create(BODY_TEXT, WidgetKind.Text),
        Mutation.SetProp(BODY_TEXT, PropertyKind.Text, PropertyValue.Text("The conversation")),
        Mutation.Insert(BODY, BODY_TEXT, 0),
        Mutation.Insert(ROOT, SPLIT, 0),
        Mutation.Insert(SPLIT, SIDE, 0),
        Mutation.Insert(SPLIT, BODY, 1),
    )
    value?.let { records += Mutation.SetProp(SPLIT, PropertyKind.Value, PropertyValue.Float(it)) }
    min?.let { records += Mutation.SetProp(SPLIT, PropertyKind.Min, PropertyValue.Float(it)) }
    max?.let { records += Mutation.SetProp(SPLIT, PropertyKind.Max, PropertyValue.Float(it)) }
    selected?.let { records += Mutation.SetProp(SPLIT, PropertyKind.SelectedIndex, PropertyValue.Integer(it)) }
    collapseBelow?.let { records += Mutation.SetProp(SPLIT, PropertyKind.CollapseBelow, PropertyValue.Float(it)) }
    if (children > 2) {
        records += Mutation.Create(EXTRA, WidgetKind.Text)
        records += Mutation.SetProp(EXTRA, PropertyKind.Text, PropertyValue.Text("a third pane"))
        records += Mutation.Insert(SPLIT, EXTRA, 2)
    }
    return records
}

private fun FakeHostConnection.widths(): List<Double> =
    events.filterIsInstance<HostEvent.ValueChanged>().filter { it.nodeId == SPLIT }.map { it.value }

/**
 * A side pane and a body: the drag stays on this side until it is let go, the width it ends
 * on is reported once, the range holds, folding is zero, narrow places stack by the split
 * pane's own width, and the keyboard and a screen reader reach the divider.
 */
@OptIn(ExperimentalTestApi::class)
class SplitPaneTest {

    @AfterTest
    fun forgetPlatform() {
        hostPlatformOverride = null
    }

    private fun ComposeUiTest.show(connection: FakeHostConnection, width: Dp, height: Dp = 600.dp) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(width, height))
            }
        }
        waitForIdle()
    }

    /** Shows the tree in a place as wide as [width] says, which the test may change. */
    private fun ComposeUiTest.showIn(connection: FakeHostConnection, width: MutableState<Dp>) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(width.value, 600.dp)) {
                    ComposeRustContent(rememberComposeRustHost(connection))
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.bodyWidth(): Float =
        onNodeWithTag(splitBodyTestTag(SPLIT)).getBoundsInRoot().let { (it.right - it.left).value }

    /** One pane at a time: the side pane, as `SelectedIndex` 0 asks, and no divider. */
    private fun ComposeUiTest.assertStacked() {
        onNodeWithTag(splitSideTestTag(SPLIT)).assertIsDisplayed()
        onNodeWithTag(splitBodyTestTag(SPLIT)).assertDoesNotExist()
        onNodeWithTag(splitDividerTestTag(SPLIT)).assertDoesNotExist()
    }

    /** Both panes and the divider, with the body beside the side pane rather than under it. */
    private fun ComposeUiTest.assertSideBySide(place: Float) {
        onNodeWithTag(splitSideTestTag(SPLIT)).assertIsDisplayed()
        onNodeWithTag(splitBodyTestTag(SPLIT)).assertIsDisplayed()
        onNodeWithTag(splitDividerTestTag(SPLIT)).assertExists()
        assertTrue(bodyWidth() < place - sideWidth() + 1f, "the body is ${bodyWidth()} wide in a $place dp place")
    }

    private fun ComposeUiTest.sideWidth(): Float =
        onNodeWithTag(splitSideTestTag(SPLIT)).getBoundsInRoot().let { (it.right - it.left).value }

    private fun ComposeUiTest.drag(by: Float) {
        onNodeWithTag(splitDividerTestTag(SPLIT)).performTouchInput {
            down(center)
            moveBy(Offset(by / 2f, 0f))
            moveBy(Offset(by / 2f, 0f))
            up()
        }
        waitForIdle()
    }

    /** Two columns at 1100 dp, nothing sent while the divider moves, one width when let go. */
    @Test
    fun fr15_2_12_a_drag_is_reported_once_when_it_is_let_go() = runDesktopComposeUiTest(1100, 600) {
        val connection = FakeHostConnection(splitTree())
        show(connection, 1100.dp)
        onNodeWithTag(splitSideTestTag(SPLIT)).assertIsDisplayed()
        onNodeWithTag(splitBodyTestTag(SPLIT)).assertIsDisplayed()

        onNodeWithTag(splitDividerTestTag(SPLIT)).performTouchInput {
            down(center)
            moveBy(Offset(30f, 0f))
            moveBy(Offset(30f, 0f))
        }
        waitForIdle()
        assertEquals(emptyList(), connection.widths(), "the drag crossed the boundary while it moved")
        assertTrue(sideWidth() > 320f, "the side pane did not follow the pointer")

        onNodeWithTag(splitDividerTestTag(SPLIT)).performTouchInput { up() }
        waitForIdle()
        val reported = connection.widths()
        assertEquals(1, reported.size, "letting go reported $reported")
        assertTrue(reported.single() > 320.0 && reported.single() <= 380.0, "reported ${reported.single()}")
        assertTrue(abs(sideWidth() - reported.single().toFloat()) < 1f)
    }

    /** `Value = 320` opens at 320, and the divider cannot be taken past 200 or 400. */
    @Test
    fun fr15_2_12_the_width_opens_where_it_was_given_and_stays_in_range() = runDesktopComposeUiTest(1100, 600) {
        val connection = FakeHostConnection(splitTree())
        show(connection, 1100.dp)
        assertTrue(abs(sideWidth() - 320f) < 1f, "opened at ${sideWidth()}")

        drag(400f)
        assertTrue(abs(sideWidth() - 400f) < 1f, "dragged past the maximum to ${sideWidth()}")
        drag(-600f)
        assertTrue(abs(sideWidth() - 200f) < 1f, "dragged past the minimum to ${sideWidth()}")
        assertEquals(listOf(400.0, 200.0), connection.widths())
    }

    /**
     * A collapsible side pane folds when dragged past its minimum and reports zero once; a
     * side pane that is not collapsible stops at its minimum.
     */
    @Test
    fun fr15_2_12_dragging_past_the_minimum_folds_only_what_may_fold() = runDesktopComposeUiTest(1100, 600) {
        val connection = FakeHostConnection(splitTree(collapsible = true))
        show(connection, 1100.dp)
        drag(-300f)
        assertEquals(listOf(0.0), connection.widths())
        assertTrue(sideWidth() < 1f, "the side pane is still ${sideWidth()} wide")

        // Unfolding from the keyboard brings back the width it had, and reports it once.
        onNodeWithTag(splitDividerTestTag(SPLIT)).requestFocus()
        onNodeWithTag(splitDividerTestTag(SPLIT)).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf(0.0, 320.0), connection.widths())
        assertTrue(abs(sideWidth() - 320f) < 1f)
    }

    @Test
    fun fr15_2_12_a_side_pane_that_may_not_fold_stops_at_its_minimum() = runDesktopComposeUiTest(1100, 600) {
        val connection = FakeHostConnection(splitTree(collapsible = false))
        show(connection, 1100.dp)
        drag(-300f)
        assertEquals(listOf(200.0), connection.widths())
        assertTrue(abs(sideWidth() - 200f) < 1f)
    }

    /** `Value = 0` on a collapsible split pane opens it folded. */
    @Test
    fun fr15_2_12_a_width_of_zero_opens_folded() = runDesktopComposeUiTest(1100, 600) {
        val connection = FakeHostConnection(splitTree(value = 0f, collapsible = true))
        show(connection, 1100.dp)
        assertTrue(sideWidth() < 1f, "opened at ${sideWidth()}")
        assertEquals(emptyList(), connection.widths(), "opening folded is not a change to report")
    }

    /**
     * The same declaration is one pane at a time at 500 dp and two columns at 1100 dp, and
     * moving between the two widths sends the Host nothing.
     */
    @Test
    fun fr15_2_12_a_narrow_place_stacks_and_a_wide_one_does_not() = runDesktopComposeUiTest(1100, 600) {
        val connection = FakeHostConnection(splitTree())
        var width by mutableStateOf(500.dp)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(width, 600.dp)) {
                    ComposeRustContent(rememberComposeRustHost(connection))
                }
            }
        }
        waitForIdle()
        onNodeWithTag(splitSideTestTag(SPLIT)).assertIsDisplayed()
        onNodeWithTag(splitBodyTestTag(SPLIT)).assertDoesNotExist()
        onNodeWithTag(splitDividerTestTag(SPLIT)).assertDoesNotExist()
        val before = connection.nodeEvents.toList()

        width = 1100.dp
        waitForIdle()
        onNodeWithTag(splitSideTestTag(SPLIT)).assertIsDisplayed()
        onNodeWithTag(splitBodyTestTag(SPLIT)).assertIsDisplayed()
        width = 500.dp
        waitForIdle()
        width = 1100.dp
        waitForIdle()
        assertEquals(before, connection.nodeEvents.toList(), "changing width sent something about a node")
        // The table is the one the Host built: no pane was dropped and made again.
        onNodeWithTag(nodeTestTag(SIDE_TEXT)).assertIsDisplayed()
        onNodeWithTag(nodeTestTag(BODY_TEXT)).assertIsDisplayed()
    }

    /** A 500 dp place inside an 1100 dp window stacks: the class is the split pane's own. */
    @Test
    fun fr15_2_12_the_size_class_is_the_split_panes_own_width() = runDesktopComposeUiTest(1100, 600) {
        val connection = FakeHostConnection(splitTree(width = 500f))
        show(connection, 1100.dp)
        onNodeWithTag(splitSideTestTag(SPLIT)).assertIsDisplayed()
        onNodeWithTag(splitBodyTestTag(SPLIT)).assertDoesNotExist()
    }

    /**
     * Stacked with the body selected, the body shows; going back shows the side pane at once
     * and dismisses exactly once.
     */
    @Test
    fun fr15_2_12_going_back_shows_the_side_pane_and_dismisses_once() = runDesktopComposeUiTest(500, 600) {
        val connection = FakeHostConnection(splitTree(selected = 1))
        show(connection, 500.dp)
        onNodeWithTag(splitBodyTestTag(SPLIT)).assertIsDisplayed()
        onNodeWithTag(splitSideTestTag(SPLIT)).assertDoesNotExist()

        onNodeWithTag(splitBackTestTag(SPLIT)).performClick()
        waitForIdle()
        onNodeWithTag(splitSideTestTag(SPLIT)).assertIsDisplayed()
        val dismissals = connection.events.filterIsInstance<HostEvent.Clicked>()
            .filter { it.nodeId == SPLIT && it.handlerId == ON_DISMISS }
        assertEquals(1, dismissals.size)
    }

    /**
     * Narrowing the window takes the side pane down without telling anyone, and widening it
     * again brings back the width that was chosen.
     */
    @Test
    fun fr15_2_12_a_narrowed_window_squeezes_the_side_pane_and_gives_it_back() = runDesktopComposeUiTest(1100, 600) {
        val connection = FakeHostConnection(splitTree(value = 380f))
        var width by mutableStateOf(1100.dp)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(width, 600.dp)) {
                    ComposeRustContent(rememberComposeRustHost(connection))
                }
            }
        }
        waitForIdle()
        assertTrue(abs(sideWidth() - 380f) < 1f)
        width = 680.dp
        waitForIdle()
        assertTrue(sideWidth() < 380f, "the side pane kept ${sideWidth()} in a 680 dp window")
        assertTrue(sideWidth() >= 200f - 0.5f)
        width = 1100.dp
        waitForIdle()
        assertTrue(abs(sideWidth() - 380f) < 1f, "the chosen width did not come back: ${sideWidth()}")
        assertEquals(emptyList(), connection.widths())
    }

    /**
     * The keyboard reaches the divider: an arrow moves it by the system's step and reports
     * when released, Home and End go to the ends, and none of it reaches the Host's key
     * handler.
     */
    @Test
    fun fr15_2_12_the_divider_is_moved_from_the_keyboard_and_reported_on_release() = runDesktopComposeUiTest(1100, 600) {
        val connection = FakeHostConnection(splitTree())
        show(connection, 1100.dp)
        val step = resolveTheme(
            Theme(DesignSystem.Fluent, DesignSystem.Fluent, ColorScheme.Light, false),
            HostPlatform.Unknown,
            systemDark = false,
        ).let { it.rules.splitPane(WindowSizeClass.Expanded, it).keyStep.value }

        onNodeWithTag(splitDividerTestTag(SPLIT)).requestFocus()
        onNodeWithTag(splitDividerTestTag(SPLIT)).performKeyInput { keyDown(Key.DirectionRight) }
        waitForIdle()
        assertEquals(emptyList(), connection.widths(), "a held key was reported before release")
        onNodeWithTag(splitDividerTestTag(SPLIT)).performKeyInput { keyUp(Key.DirectionRight) }
        waitForIdle()
        assertEquals(listOf(320.0 + step), connection.widths())

        onNodeWithTag(splitDividerTestTag(SPLIT)).performKeyInput { pressKey(Key.MoveHome) }
        waitForIdle()
        onNodeWithTag(splitDividerTestTag(SPLIT)).performKeyInput { pressKey(Key.MoveEnd) }
        waitForIdle()
        assertEquals(listOf(320.0 + step, 200.0, 400.0), connection.widths())
        assertTrue(connection.events.none { it is HostEvent.KeyDown }, "a divider key reached the Host")
    }

    /**
     * Control Command S puts a collapsible sidebar away on a Mac wherever focus is in the
     * window, here on a button in the body, and says so once.
     */
    @Test
    fun fr15_2_12_the_platform_sidebar_shortcut_works_from_anywhere_in_the_window() = runDesktopComposeUiTest(1100, 600) {
        hostPlatformOverride = HostPlatform.MacOs
        val button = 9
        val connection = FakeHostConnection(
            splitTree(collapsible = true) + listOf(
                Mutation.Create(button, WidgetKind.Button),
                Mutation.SetProp(button, PropertyKind.Text, PropertyValue.Text("Send")),
                Mutation.SetProp(button, PropertyKind.OnClick, PropertyValue.Integer(1L)),
                Mutation.Insert(BODY, button, 1),
            ),
        )
        show(connection, 1100.dp)
        onNodeWithTag(nodeTestTag(button)).requestFocus()
        onNodeWithTag(nodeTestTag(button)).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.S) } }
        }
        waitForIdle()
        assertEquals(listOf(0.0), connection.widths())
    }

    /**
     * With two collapsible split panes in one window, the command goes to the innermost one
     * holding focus, and with none holding focus to the outermost.
     */
    @Test
    fun fr15_2_12_the_sidebar_command_goes_to_the_focused_pane_or_else_the_outermost() {
        val shortcuts = SidebarShortcuts()
        shortcuts.register(OUTER, depth = 0) {}
        shortcuts.register(SPLIT, depth = 1) {}
        assertEquals(OUTER, shortcuts.target(), "with nothing focused the outermost answers")
        shortcuts.focus(OUTER, true)
        shortcuts.focus(SPLIT, true)
        assertEquals(SPLIT, shortcuts.target(), "the innermost pane holding focus answers")
        shortcuts.focus(SPLIT, false)
        assertEquals(OUTER, shortcuts.target())
        shortcuts.unregister(OUTER)
        shortcuts.focus(SPLIT, false)
        assertEquals(SPLIT, shortcuts.target())
    }

    /** The same rule in a window: focus in the inner pane folds the inner pane. */
    @Test
    fun fr15_2_12_nested_split_panes_fold_the_one_with_focus() = runDesktopComposeUiTest(1100, 600) {
        hostPlatformOverride = HostPlatform.MacOs
        val outerSide = 21
        val records = splitTree(collapsible = true).toMutableList()
        // The whole tree above becomes the body of an outer collapsible split pane.
        records += listOf(
            Mutation.Create(OUTER, WidgetKind.SplitPane),
            Mutation.SetModifier(OUTER, 0, ProtocolModifier.FillMaxWidth),
            Mutation.SetModifier(OUTER, 1, ProtocolModifier.FillMaxHeight),
            Mutation.SetProp(OUTER, PropertyKind.Collapsible, PropertyValue.Bool(true)),
            Mutation.SetProp(OUTER, PropertyKind.Value, PropertyValue.Float(200f)),
            Mutation.SetProp(OUTER, PropertyKind.OnValueChange, PropertyValue.Integer(ON_CHANGE + 1)),
            Mutation.Create(outerSide, WidgetKind.Column),
            Mutation.Insert(OUTER, outerSide, 0),
            Mutation.Insert(OUTER, ROOT, 1),
        )
        val connection = FakeHostConnection(records)
        show(connection, 1100.dp)
        onNodeWithTag(splitDividerTestTag(SPLIT)).requestFocus()
        onNodeWithTag(splitDividerTestTag(SPLIT)).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.S) } }
        }
        waitForIdle()
        assertEquals(listOf(0.0), connection.widths(), "the inner pane did not fold")
        assertTrue(
            connection.events.filterIsInstance<HostEvent.ValueChanged>().none { it.nodeId == OUTER },
            "the outer pane folded instead",
        )
    }

    /**
     * To a screen reader the divider is an adjustable control named for the side pane, with
     * its width and its range, and setting it reports once. Whether VoiceOver, Narrator and
     * Orca read it that way is checked by hand on the native image.
     */
    @Test
    fun fr15_2_12_the_divider_is_an_adjustable_control_with_a_name_and_a_width() = runDesktopComposeUiTest(1100, 600) {
        val connection = FakeHostConnection(splitTree())
        show(connection, 1100.dp)
        onNodeWithTag(splitDividerTestTag(SPLIT))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Sessions")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "320"))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(320f, 200f..400f),
                ),
            )
        onNodeWithTag(splitDividerTestTag(SPLIT)).performSemanticsAction(SemanticsActions.SetProgress) {
            it(300f)
        }
        waitForIdle()
        assertEquals(listOf(300.0), connection.widths())
    }

    /** Drawn in all seven systems, in two columns at 1100 dp. */
    @Test
    fun fr15_2_12_every_design_system_draws_a_split_pane() {
        DesignSystem.entries.forEach { system ->
            runDesktopComposeUiTest(1100, 600) {
                show(FakeHostConnection(splitTree(system = system)), 1100.dp)
                onNodeWithTag(splitSideTestTag(SPLIT)).assertIsDisplayed()
                onNodeWithTag(splitBodyTestTag(SPLIT)).assertIsDisplayed()
                onNodeWithTag(splitDividerTestTag(SPLIT)).assertIsDisplayed()
            }
        }
    }

    /**
     * The divider is not drawn the same way seven times (a hairline in some, a grip in
     * others), and at least one system answers a medium width with something other than two
     * columns.
     */
    @Test
    fun fr15_2_12_systems_differ_in_the_divider_and_in_the_medium_answer() {
        val looks = mutableSetOf<Pair<Boolean, Float>>()
        val medium = mutableSetOf<SplitPanePresentation>()
        DesignSystem.entries.forEach { system ->
            val theme = resolveTheme(Theme(system, system, ColorScheme.Light, false), HostPlatform.Unknown, systemDark = false)
            val style = theme.rules.splitPane(WindowSizeClass.Medium, theme)
            looks += (style.handle != null) to style.lineWidth.value
            medium += style.presentation
            assertEquals(
                SplitPanePresentation.Stacked,
                theme.rules.splitPane(WindowSizeClass.Compact, theme).presentation,
                "$system shows two panes on a phone",
            )
            assertEquals(
                SplitPanePresentation.SideBySide,
                theme.rules.splitPane(WindowSizeClass.Expanded, theme).presentation,
            )
        }
        assertTrue(looks.size >= 2, "every system drew the same divider: $looks")
        assertTrue(medium.size >= 2, "every system answered a medium width the same way: $medium")
    }

    /** The showcase carries a split pane with a side pane and a body. */
    @Test
    fun fr15_2_12_the_showcase_has_a_split_pane() {
        val records = designShowcaseRecords(Theme(DesignSystem.Gnome, DesignSystem.Gnome, ColorScheme.Light, false))
        val split = records.filterIsInstance<Mutation.Create>().single { it.widget == WidgetKind.SplitPane }.nodeId
        assertEquals(2, records.filterIsInstance<Mutation.Insert>().count { it.parentId == split })
    }

    /** A split pane that does not have two children is reported once and still drawn. */
    @Test
    fun fr15_2_12_a_split_pane_without_two_children_is_reported() = runDesktopComposeUiTest(1100, 600) {
        val connection = FakeHostConnection(splitTree(children = 3))
        show(connection, 1100.dp)
        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
            .filter { it.code == TableError.INVALID_CHILDREN }
        assertEquals(1, errors.size, "${connection.events}")
        onNodeWithTag(nodeTestTag(SIDE_TEXT)).assertIsDisplayed()
        onNodeWithTag(nodeTestTag(EXTRA)).assertIsDisplayed()
    }

    /**
     * A split pane told to stack below 720 dp shows one pane at 700 dp, a medium width that
     * would otherwise be two columns, and two columns at 740 dp.
     */
    @Test
    fun fr15_2_12_collapse_below_stacks_under_its_own_threshold() = runDesktopComposeUiTest(1100, 600) {
        val width = mutableStateOf(700.dp)
        showIn(FakeHostConnection(splitTree(collapseBelow = 720f)), width)
        assertStacked()

        width.value = 740.dp
        waitForIdle()
        assertSideBySide(740f)

        width.value = 700.dp
        waitForIdle()
        assertStacked()
    }

    /**
     * A split pane told to stack below 480 dp does not stack at 520 dp, which is a compact
     * width: the design system is asked as for a medium one. Where the side pane's minimum
     * and the body's minimum do not both fit, the side pane is laid over the body; where
     * they do, the panes sit side by side.
     */
    @Test
    fun fr15_2_12_collapse_below_lowers_the_stacking_line_below_compact() {
        runDesktopComposeUiTest(1100, 600) {
            // Without the line, 520 dp is compact and stacks.
            showIn(FakeHostConnection(splitTree()), mutableStateOf(520.dp))
            assertStacked()
        }
        runDesktopComposeUiTest(1100, 600) {
            // A minimum of 200, a hairline and a body of at least 360 do not fit in 520.
            showIn(FakeHostConnection(splitTree(collapseBelow = 480f)), mutableStateOf(520.dp))
            onNodeWithTag(splitSideTestTag(SPLIT)).assertIsDisplayed()
            onNodeWithTag(splitBodyTestTag(SPLIT)).assertIsDisplayed()
            onNodeWithTag(splitDividerTestTag(SPLIT)).assertExists()
            assertTrue(abs(bodyWidth() - 520f) < 1f, "the side pane was not laid over the body: ${bodyWidth()}")
        }
        runDesktopComposeUiTest(1100, 600) {
            // A minimum of 100 does.
            showIn(FakeHostConnection(splitTree(value = 120f, min = 100f, collapseBelow = 480f)), mutableStateOf(520.dp))
            assertSideBySide(520f)
        }
        // Above the line every system gives its own medium answer, and below it every system
        // stacks, whatever size class the width is.
        DesignSystem.entries.forEach { system ->
            val theme = resolveTheme(Theme(system, system, ColorScheme.Light, false), HostPlatform.Unknown, systemDark = false)
            assertEquals(
                theme.rules.splitPane(WindowSizeClass.Medium, theme).presentation,
                splitPaneStyle(theme, 520f, 480f).presentation,
                "$system at 520 dp with a line at 480",
            )
            assertEquals(
                SplitPanePresentation.Stacked,
                splitPaneStyle(theme, 900f, 1000f).presentation,
                "$system at 900 dp with a line at 1000",
            )
            assertEquals(
                theme.rules.splitPane(WindowSizeClass.Expanded, theme).presentation,
                splitPaneStyle(theme, 900f, 720f).presentation,
                "$system at 900 dp with a line at 720",
            )
        }
    }

    /**
     * A 700 dp place inside an 1100 dp window stacks under a line at 720: it is the split
     * pane's own width that is measured, not the window's. Without the line the same place
     * is medium and draws two columns.
     */
    @Test
    fun fr15_2_12_collapse_below_measures_its_own_width() {
        runDesktopComposeUiTest(1100, 600) {
            show(FakeHostConnection(splitTree(width = 700f, collapseBelow = 720f)), 1100.dp)
            assertStacked()
        }
        runDesktopComposeUiTest(1100, 600) {
            show(FakeHostConnection(splitTree(width = 700f)), 1100.dp)
            assertSideBySide(700f)
        }
    }

    /**
     * Crossing the line back and forth keeps the Host's nodes, sends the Host nothing, and
     * brings the side pane back at the width the user dragged it to.
     */
    @Test
    fun fr15_2_12_crossing_collapse_below_keeps_panes_and_sends_nothing() = runDesktopComposeUiTest(1100, 600) {
        val connection = FakeHostConnection(splitTree(collapseBelow = 720f))
        val width = mutableStateOf(800.dp)
        showIn(connection, width)
        drag(60f)
        val chosen = connection.widths()
        assertEquals(1, chosen.size, "the drag reported $chosen")
        assertTrue(chosen.single() > 320.0, "the drag did not move the divider: $chosen")
        val before = connection.nodeEvents.toList()
        val eventsBefore = connection.events.toList()

        repeat(3) {
            width.value = 700.dp
            waitForIdle()
            assertStacked()
            onNodeWithTag(nodeTestTag(SIDE_TEXT)).assertIsDisplayed()
            width.value = 800.dp
            waitForIdle()
            assertSideBySide(800f)
        }

        assertEquals(before, connection.nodeEvents.toList(), "crossing the line sent something about a node")
        assertEquals(eventsBefore, connection.events.toList(), "crossing the line sent the Host an event")
        assertEquals(chosen, connection.widths())
        assertTrue(abs(sideWidth() - chosen.single().toFloat()) < 1f, "came back at ${sideWidth()}, not $chosen")
        // The table is the one the Host built: no pane was dropped and made again.
        onNodeWithTag(nodeTestTag(SIDE_TEXT)).assertIsDisplayed()
        onNodeWithTag(nodeTestTag(BODY_TEXT)).assertIsDisplayed()
    }

    /**
     * Without the property, and with it at 0, the line is the size class's own: one pane
     * below 600 dp and two columns from 600 dp up, the Expanded answer from 840 dp.
     */
    @Test
    fun fr15_2_12_without_collapse_below_the_size_class_line_is_unchanged() {
        for (line in listOf(null, 0f)) {
            runDesktopComposeUiTest(1100, 600) {
                val width = mutableStateOf(590.dp)
                showIn(FakeHostConnection(splitTree(min = 100f, collapseBelow = line)), width)
                assertStacked()
                width.value = 610.dp
                waitForIdle()
                assertSideBySide(610f)
                width.value = 900.dp
                waitForIdle()
                assertSideBySide(900f)
            }
        }
        DesignSystem.entries.forEach { system ->
            val theme = resolveTheme(Theme(system, system, ColorScheme.Light, false), HostPlatform.Unknown, systemDark = false)
            for ((place, sizeClass) in listOf(
                599f to WindowSizeClass.Compact,
                600f to WindowSizeClass.Medium,
                839f to WindowSizeClass.Medium,
                840f to WindowSizeClass.Expanded,
            )) {
                val answer = theme.rules.splitPane(sizeClass, theme).presentation
                assertEquals(answer, splitPaneStyle(theme, place, null).presentation, "$system at $place")
                assertEquals(answer, splitPaneStyle(theme, place, 0f).presentation, "$system at $place with a line of 0")
            }
        }
    }

    /**
     * A negative, NaN or infinite line is reported once as a protocol error, the process
     * goes on, and the split pane is drawn as if it had no line: two columns at 700 dp.
     */
    @Test
    fun fr15_2_12_invalid_collapse_below_is_a_protocol_error() {
        for (bad in listOf(-10f, Float.NaN, Float.POSITIVE_INFINITY)) {
            runDesktopComposeUiTest(1100, 600) {
                val connection = FakeHostConnection(splitTree(collapseBelow = bad))
                showIn(connection, mutableStateOf(700.dp))
                val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
                    .filter { it.code == TableError.UNSUPPORTED_PROPERTY }
                assertEquals(1, errors.size, "$bad: ${connection.events}")
                assertTrue("stacks below" in errors.single().message, errors.single().message)
                assertSideBySide(700f)
            }
        }
    }

    /** What a split pane carries is kept on a split pane, and its one property nowhere else. */
    @Test
    fun fr15_2_12_a_split_pane_keeps_its_properties() {
        for (property in listOf(
            PropertyKind.Value,
            PropertyKind.Min,
            PropertyKind.Max,
            PropertyKind.Collapsible,
            PropertyKind.CollapseBelow,
            PropertyKind.SelectedIndex,
            PropertyKind.Text,
            PropertyKind.OnValueChange,
            PropertyKind.OnDismiss,
        )) {
            assertTrue(NodeTable.supportsProperty(WidgetKind.SplitPane, property), "$property")
        }
        assertFalse(NodeTable.supportsProperty(WidgetKind.Box, PropertyKind.Collapsible))
        assertFalse(NodeTable.supportsProperty(WidgetKind.Column, PropertyKind.CollapseBelow))
    }
}
