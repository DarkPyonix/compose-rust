package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.darkpyonix.composerust.design.FloatingActionPlacement
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.designShowcaseRecords
import dev.darkpyonix.composerust.ui.node.nodeTestTag

private const val CHIP = 1
private const val PAGE = 1
private const val ACTION = 2
private const val HANDLER = 83L

/** A window wide enough to be a desktop, which is where most of the systems disagree. */
private val WINDOW = DpSize(900.dp, 500.dp)

private fun theme(system: DesignSystem) =
    Mutation.SetTheme(Theme(system, system, ColorScheme.Light, false))

private fun resolved(system: DesignSystem): ResolvedTheme = resolveTheme(
    Theme(system, system, ColorScheme.Light, false),
    HostPlatform.MacOs,
    systemDark = false,
)

private fun chipTree(chosen: Boolean, system: DesignSystem = DesignSystem.Material3) = listOf(
    theme(system),
    Mutation.Create(CHIP, WidgetKind.Chip),
    Mutation.SetProp(CHIP, PropertyKind.Text, PropertyValue.Text("Unread")),
    Mutation.SetProp(CHIP, PropertyKind.Checked, PropertyValue.Bool(chosen)),
    Mutation.SetProp(CHIP, PropertyKind.OnClick, PropertyValue.Integer(HANDLER)),
)

/** A page that fills the window, with the one action declared in it. */
private fun pageTree(system: DesignSystem) = listOf(
    theme(system),
    Mutation.Create(PAGE, WidgetKind.Box),
    Mutation.SetModifier(PAGE, 0, ProtocolModifier.FillMaxWidth),
    Mutation.SetModifier(PAGE, 1, ProtocolModifier.FillMaxHeight),
    Mutation.Create(ACTION, WidgetKind.FloatingAction),
    Mutation.SetProp(ACTION, PropertyKind.Text, PropertyValue.Text("New")),
    Mutation.SetProp(ACTION, PropertyKind.Icon, PropertyValue.Integer(IconRole.Add.ordinal + 1L)),
    Mutation.SetProp(ACTION, PropertyKind.OnClick, PropertyValue.Integer(HANDLER)),
    Mutation.Insert(PAGE, ACTION, 0),
)

/** Which quarter of the page an action's centre falls in, read off where it was drawn. */
private enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

private fun cornerOf(bounds: DpRect, page: DpSize): Corner {
    val x = (bounds.left + bounds.right) / 2
    val y = (bounds.top + bounds.bottom) / 2
    val end = x > page.width / 2
    val bottom = y > page.height / 2
    return when {
        bottom && end -> Corner.BottomEnd
        bottom -> Corner.BottomStart
        end -> Corner.TopEnd
        else -> Corner.TopStart
    }
}

/**
 * The chip and the one action a screen is about.
 *
 * A chip draws the chosen state the Host sent and nothing else, and its shape is its
 * design system's. The action is drawn and placed by its design system, and the test that
 * matters most here is the one that fails if all seven put it in the same place.
 */
@OptIn(ExperimentalTestApi::class)
class ChipFloatingActionTest {

    /** Both tags reach a composable, in every system, rather than falling through. */
    @Test
    fun fr15_2_10_both_widgets_are_drawn_in_every_design_system() {
        DesignSystem.entries.forEach { system ->
            runComposeUiTest {
                val connection = FakeHostConnection(
                    chipTree(chosen = true, system) + listOf(
                        Mutation.Create(ACTION + 10, WidgetKind.FloatingAction),
                        Mutation.SetProp(ACTION + 10, PropertyKind.Text, PropertyValue.Text("New")),
                        Mutation.SetProp(
                            ACTION + 10,
                            PropertyKind.Icon,
                            PropertyValue.Integer(IconRole.Add.ordinal + 1L),
                        ),
                    ),
                )
                setContent {
                    Box(Modifier.size(WINDOW)) {
                        ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
                    }
                }
                waitForIdle()
                onNodeWithTag(nodeTestTag(CHIP)).assertIsDisplayed()
                onNodeWithTag(nodeTestTag(ACTION + 10)).assertIsDisplayed()
                val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
                assertTrue(errors.isEmpty(), "$system refused a property: ${errors.map { it.message }}")
            }
        }
    }

    /**
     * Pressing a chip reports a click to the Host and changes nothing on screen.
     *
     * The chosen state is the Host's. If the chip flipped itself, a filter the Host had
     * refused would still show as applied.
     */
    @Test
    fun fr15_2_10_a_chip_reports_its_click_and_keeps_the_state_the_host_sent() = runComposeUiTest {
        val connection = FakeHostConnection(chipTree(chosen = false))
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(CHIP)).assertIsNotSelected()

        onNodeWithTag(nodeTestTag(CHIP)).performClick()
        waitForIdle()

        val clicks = connection.events.filterIsInstance<HostEvent.Clicked>()
        assertEquals(1, clicks.size, "one press is one click")
        assertEquals(CHIP, clicks.single().nodeId)
        assertEquals(HANDLER, clicks.single().handlerId)
        assertTrue(
            connection.events.none { it is HostEvent.ValueChanged },
            "a chip asks nothing of the Host but the click",
        )
        onNodeWithTag(nodeTestTag(CHIP)).assertIsNotSelected()
    }

    /** A chip the Host sent as chosen is announced as chosen. */
    @Test
    fun fr15_2_10_a_chosen_chip_is_announced_as_chosen() = runComposeUiTest {
        val connection = FakeHostConnection(chipTree(chosen = true))
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(CHIP)).assertIsSelected()
    }

    /**
     * Every system answers what a chip is, and no two answer alike.
     *
     * The colours are left out: every system has its own palette, so comparing them would
     * pass while all seven drew the same capsule in seven tints.
     */
    @Test
    fun fr15_2_10_no_two_systems_draw_a_chip_alike() {
        val shapes = DesignSystem.entries.associateWith { system ->
            val theme = resolved(system)
            val style = theme.rules.chip(theme)
            listOf(style.height, style.borderWidth, style.leadingCheck, style.pressedAlpha)
        }
        assertDistinct(shapes, "a chip")
    }

    /** No two systems draw the one action alike, at the width of a desktop window. */
    @Test
    fun fr15_2_10_no_two_systems_draw_the_floating_action_alike() {
        val shapes = DesignSystem.entries.associateWith { system ->
            val theme = resolved(system)
            val style = theme.rules.floatingAction(WindowSizeClass.Expanded, theme)
            listOf(style.placement, style.form, style.size, style.elevation)
        }
        assertDistinct(shapes, "the floating action")
    }

    /**
     * The action is not put in one place by every system.
     *
     * Floating over the corner of the page is Material's answer. Apple puts a plus at the
     * top right and Fluent an accent button at the head of the command bar, so the rules
     * between them have to name more than one place, at the phone width as much as at the
     * desktop one.
     */
    @Test
    fun fr15_2_10_the_floating_action_goes_to_more_than_one_place() {
        WindowSizeClass.entries.forEach { sizeClass ->
            val places = DesignSystem.entries.map { system ->
                val theme = resolved(system)
                theme.rules.floatingAction(sizeClass, theme).placement
            }.toSet()
            assertTrue(places.size >= 2, "every system puts the action in $places at $sizeClass")
        }
        val material = resolved(DesignSystem.Material3)
        assertEquals(
            FloatingActionPlacement.OverPageBottomEnd,
            material.rules.floatingAction(WindowSizeClass.Expanded, material).placement,
        )
        val apple = resolved(DesignSystem.Cupertino)
        assertEquals(
            FloatingActionPlacement.BarEnd,
            apple.rules.floatingAction(WindowSizeClass.Expanded, apple).placement,
        )
        val fluent = resolved(DesignSystem.Fluent)
        assertEquals(
            FloatingActionPlacement.BarStart,
            fluent.rules.floatingAction(WindowSizeClass.Expanded, fluent).placement,
        )
    }

    /**
     * The same declaration, drawn, lands in a different corner of the page.
     *
     * The rules above could name three places while the frame put the action in the same
     * one every time. This draws one tree under each system and reads where the action
     * actually came out.
     */
    @Test
    fun fr15_2_10_the_same_action_lands_in_different_corners_of_the_page() {
        val corners = DesignSystem.entries.associateWith { system ->
            var corner: Corner? = null
            runComposeUiTest {
                val connection = FakeHostConnection(pageTree(system))
                setContent {
                    Box(Modifier.size(WINDOW)) {
                        ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
                    }
                }
                waitForIdle()
                val page = onNodeWithTag(nodeTestTag(PAGE)).getUnclippedBoundsInRoot()
                val action = onNodeWithTag(nodeTestTag(ACTION)).getUnclippedBoundsInRoot()
                assertTrue(
                    action.right - action.left < page.right - page.left,
                    "$system laid the action over the whole page rather than at a corner of it",
                )
                corner = cornerOf(action, DpSize(page.right - page.left, page.bottom - page.top))
            }
            corner!!
        }
        assertEquals(Corner.BottomEnd, corners[DesignSystem.Material3], "Material floats it")
        assertEquals(Corner.TopEnd, corners[DesignSystem.Cupertino], "Apple puts a plus at the top right")
        assertEquals(Corner.TopStart, corners[DesignSystem.Fluent], "Fluent heads the command bar with it")
        assertTrue(corners.values.toSet().size >= 2, "every system put the action in one corner: $corners")
    }

    /** Pressing the action is one click on its own handler, wherever it was put. */
    @Test
    fun fr15_2_10_the_floating_action_reports_one_click() {
        listOf(DesignSystem.Material3, DesignSystem.Cupertino, DesignSystem.Fluent).forEach { system ->
            runComposeUiTest {
                val connection = FakeHostConnection(pageTree(system))
                setContent {
                    Box(Modifier.size(WINDOW)) {
                        ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
                    }
                }
                waitForIdle()
                onNodeWithTag(nodeTestTag(ACTION)).performClick()
                waitForIdle()
                val clicks = connection.events.filterIsInstance<HostEvent.Clicked>()
                assertEquals(listOf(ACTION to HANDLER), clicks.map { it.nodeId to it.handlerId }, "$system")
            }
        }
    }

    /** Both appear in the showcase, which is where the seven systems are compared by eye. */
    @Test
    fun fr15_2_10_the_showcase_draws_a_chip_and_a_floating_action() {
        val records = designShowcaseRecords(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false))
        val drawn = records.filterIsInstance<Mutation.Create>().map { it.widget }.toSet()
        assertTrue(WidgetKind.Chip in drawn, "the showcase does not draw a chip")
        assertTrue(WidgetKind.FloatingAction in drawn, "the showcase does not draw a floating action")
        val chips = records.filterIsInstance<Mutation.Create>()
            .filter { it.widget == WidgetKind.Chip }
            .map { it.nodeId }
            .toSet()
        val chosen = records.filterIsInstance<Mutation.SetProp>()
            .filter { it.property == PropertyKind.Checked && it.nodeId in chips }
            .map { (it.value as PropertyValue.Bool).value }
            .toSet()
        assertTrue(true in chosen && false in chosen, "the showcase draws chips in only one state")
    }

    private fun assertDistinct(shapes: Map<DesignSystem, List<Any>>, what: String) {
        val systems = shapes.keys.toList()
        for (i in systems.indices) {
            for (j in i + 1 until systems.size) {
                assertTrue(
                    shapes[systems[i]] != shapes[systems[j]],
                    "${systems[i]} and ${systems[j]} give $what the same shape: ${shapes[systems[i]]}",
                )
            }
        }
    }
}
