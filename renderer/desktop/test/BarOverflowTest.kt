package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.foundation.barOverflowMenuTestTag
import dev.darkpyonix.composerust.foundation.barOverflowTestTag
import dev.darkpyonix.composerust.foundation.fittingActions
import dev.darkpyonix.composerust.foundation.trailingActionsStart
import dev.darkpyonix.composerust.protocol.ButtonVariant
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
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.WindowCaption
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROOT = 1
private const val BAR = 2
private const val BACK = 3
private const val SPACER = 4
private const val TITLE = "Notes"

/** The bar's trailing actions, in the order they are declared. */
private val ACTIONS = listOf(10, 11, 12, 13, 14)
private val LABELS = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo")
private fun handlerOf(id: Int): Long = 100L + id

private const val NARROW = 320
private const val WIDE = 1200
private const val HEIGHT = 600

/** One way a bar is drawn: the design system and the caption it sits in. */
private data class BarPath(val name: String, val system: DesignSystem, val caption: WindowCaption)

/** A strip that is also the window's caption, with the window buttons at its trailing end. */
private val CAPTION_STRIP = BarPath(
    "a strip that is the window caption",
    DesignSystem.Material3,
    WindowCaption(height = 28.dp, buttonsWidth = 78.dp, buttonsAtStart = false),
)

/** GNOME centres its title in the window, which lays the children out in a row of their own. */
private val CENTRED_TITLE = BarPath(
    "a bar that centres its title",
    DesignSystem.Gnome,
    WindowCaption(height = 40.dp, buttonsWidth = 0.dp),
)

/** Liquid Glass paints nothing across the window and floats its actions in capsules. */
private val FLOATING = BarPath("a floating bar", DesignSystem.LiquidGlass, WindowCaption.None)

/**
 * A top bar whose actions do not all fit puts the ones that do not into a menu of its own.
 *
 * A Compose `Row` gives each child what the ones before it left, so when the actions were
 * the row's children the last of them were measured at nothing: notepad at 420 dp drew
 * "File" as an empty pill and dropped "Format" and "Save", and Save could not be reached.
 */
@OptIn(ExperimentalTestApi::class)
class BarOverflowTest {
    private val frames = FrameRequestSource()

    /**
     * `Column { TopAppBar(title) { back, Spacer(weight), Alpha .. Echo } }`.
     *
     * The back button and the weighted spacer are the bar's leading content; the five text
     * buttons after them are its trailing actions.
     */
    private fun batch(system: DesignSystem): List<Mutation> = buildList {
        add(Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)))
        add(Mutation.Create(ROOT, WidgetKind.Column))
        add(Mutation.Create(BAR, WidgetKind.TopAppBar))
        add(Mutation.SetProp(BAR, PropertyKind.Text, PropertyValue.Text(TITLE)))
        add(Mutation.Create(BACK, WidgetKind.Button))
        add(Mutation.SetProp(BACK, PropertyKind.Icon, PropertyValue.Integer(IconRole.Back.ordinal + 1L)))
        add(Mutation.SetProp(BACK, PropertyKind.Variant, PropertyValue.Integer(ButtonVariant.Text.ordinal + 1L)))
        add(Mutation.SetProp(BACK, PropertyKind.OnClick, PropertyValue.Integer(handlerOf(BACK))))
        add(Mutation.Insert(BAR, BACK, 0))
        add(Mutation.Create(SPACER, WidgetKind.Spacer))
        add(Mutation.SetModifier(SPACER, 0, ProtocolModifier.Weight(1f)))
        add(Mutation.Insert(BAR, SPACER, 1))
        ACTIONS.forEachIndexed { index, id ->
            add(Mutation.Create(id, WidgetKind.Button))
            add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(LABELS[index])))
            add(Mutation.SetProp(id, PropertyKind.Variant, PropertyValue.Integer(ButtonVariant.Text.ordinal + 1L)))
            add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(handlerOf(id))))
            add(Mutation.Insert(BAR, id, index + 2))
        }
        add(Mutation.Insert(ROOT, BAR, 0))
    }

    /** Draws the bar at [width] dp, one dp to the pixel so widths read as they are declared. */
    private fun ComposeUiTest.showBar(
        connection: FakeHostConnection,
        width: () -> Int,
        caption: WindowCaption = WindowCaption.None,
    ) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(connection),
                    Modifier.requiredSize(width().dp, HEIGHT.dp),
                    caption = caption,
                )
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.exists(tag: String): Boolean =
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The actions drawn in the bar, while the overflow menu is closed. */
    private fun ComposeUiTest.actionsInTheBar(): List<Int> = ACTIONS.filter { exists(nodeTestTag(it)) }

    /**
     * Every action the bar draws is whole and inside the bar, and so is the overflow button.
     * [endInset] is room at the bar's trailing end that belongs to the window buttons.
     */
    private fun ComposeUiTest.assertNothingSqueezed(path: String, endInset: Dp = 0.dp) {
        val bar = onNodeWithTag(nodeTestTag(BAR)).getBoundsInRoot()
        val drawn = actionsInTheBar().map { nodeTestTag(it) } +
            listOf(barOverflowTestTag(BAR)).filter { exists(it) }
        for (tag in drawn) {
            val bounds = onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
            assertTrue(bounds.right - bounds.left > 1.dp, "$path: $tag is drawn $bounds wide, squeezed to nothing")
            assertTrue(
                bounds.right <= bar.right - endInset + 0.5.dp,
                "$path: $tag ends at ${bounds.right}, past the bar's usable end at ${bar.right - endInset}",
            )
            assertTrue(bounds.left >= bar.left - 0.5.dp, "$path: $tag starts at ${bounds.left}, before the bar")
        }
    }

    /**
     * At a narrow width the last declared actions are not in the bar, an overflow button
     * is, and opening it shows those actions in the order they were declared.
     */
    @Test
    fun fr30_3_at_a_narrow_width_the_last_actions_are_in_the_overflow_menu() =
        runDesktopComposeUiTest(WIDE, HEIGHT) {
            showBar(FakeHostConnection(batch(DesignSystem.Material3)), { NARROW })

            val inBar = actionsInTheBar()
            assertTrue(inBar.size < ACTIONS.size, "all five actions are in a $NARROW dp bar: $inBar")
            assertTrue(ACTIONS.last() !in inBar, "the last declared action stayed in the bar: $inBar")
            assertEquals(ACTIONS.take(inBar.size), inBar, "the bar kept actions out of declaration order")
            assertTrue(exists(barOverflowTestTag(BAR)), "actions overflowed and there is no overflow button")
            assertNothingSqueezed("a $NARROW dp strip")

            onNodeWithTag(barOverflowTestTag(BAR)).performClick()
            waitForIdle()
            assertTrue(exists(barOverflowMenuTestTag(BAR)), "pressing the overflow button opened no menu")
            val overflowed = ACTIONS.drop(inBar.size)
            val tops = overflowed.map { id ->
                assertTrue(exists(nodeTestTag(id)), "action $id is neither in the bar nor in the menu")
                onNodeWithTag(nodeTestTag(id), useUnmergedTree = true).getBoundsInRoot().top
            }
            assertEquals(tops.sorted(), tops, "the menu lists the overflowed actions out of declaration order")
        }

    /** An action in the menu is still that node: choosing it is its own click, and the menu closes. */
    @Test
    fun fr30_3_an_overflowed_action_is_still_invokable() = runDesktopComposeUiTest(WIDE, HEIGHT) {
        val connection = FakeHostConnection(batch(DesignSystem.Material3))
        showBar(connection, { NARROW })
        val last = ACTIONS.last()
        assertTrue(!exists(nodeTestTag(last)), "the last action did not overflow at $NARROW dp")

        onNodeWithTag(barOverflowTestTag(BAR)).performClick()
        waitForIdle()
        assertTrue(connection.events.none { it is HostEvent.Clicked }, "opening the menu reached the Host: ${connection.events}")
        onNodeWithTag(nodeTestTag(last), useUnmergedTree = true).performClick()
        waitForIdle()

        val clicks = connection.events.filterIsInstance<HostEvent.Clicked>()
        assertEquals(1, clicks.size, "one choice is one click: ${connection.events}")
        assertEquals(last, clicks.single().nodeId, "the click came from another node")
        assertEquals(handlerOf(last), clicks.single().handlerId, "the click carried another handler")
        assertTrue(!exists(barOverflowMenuTestTag(BAR)), "the menu stayed open after an action was chosen")
    }

    /** With room for every action, they are all in the bar and there is no overflow button. */
    @Test
    fun fr30_3_nothing_overflows_when_there_is_room() = runDesktopComposeUiTest(WIDE, HEIGHT) {
        showBar(FakeHostConnection(batch(DesignSystem.Material3)), { WIDE })
        assertEquals(ACTIONS, actionsInTheBar(), "an action overflowed a $WIDE dp bar")
        for (id in ACTIONS) onNodeWithTag(nodeTestTag(id), useUnmergedTree = true).assertIsDisplayed()
        assertTrue(!exists(barOverflowTestTag(BAR)), "a bar with room for everything drew an overflow button")
        assertNothingSqueezed("a $WIDE dp strip")
    }

    /**
     * Narrowing the bar step by step only ever takes actions from the end, and widening it
     * again brings them back.
     */
    @Test
    fun fr30_3_the_first_declared_actions_stay_in_the_bar_longest() = runDesktopComposeUiTest(WIDE, HEIGHT) {
        var width by mutableStateOf(WIDE)
        showBar(FakeHostConnection(batch(DesignSystem.Material3)), { width })
        var previous = ACTIONS.size
        for (step in listOf(WIDE, 800, 600, 480, 400, 360, 320, 280, 240)) {
            width = step
            waitForIdle()
            val inBar = actionsInTheBar()
            assertEquals(ACTIONS.take(inBar.size), inBar, "at $step dp the bar kept $inBar, not the first declared")
            assertTrue(inBar.size <= previous, "at $step dp the bar holds more actions than it did wider")
            assertEquals(
                inBar.size < ACTIONS.size,
                exists(barOverflowTestTag(BAR)),
                "at $step dp the overflow button does not match what overflowed: $inBar",
            )
            assertNothingSqueezed("a $step dp strip")
            previous = inBar.size
        }
        assertTrue(previous < ACTIONS.size, "nothing overflowed even at 240 dp")
        width = WIDE
        waitForIdle()
        assertEquals(ACTIONS, actionsInTheBar(), "widening the bar again did not bring its actions back")
    }

    /** The back button and the title are leading content and stay where they are. */
    @Test
    fun fr30_3_leading_content_keeps_its_place() = runDesktopComposeUiTest(WIDE, HEIGHT) {
        showBar(FakeHostConnection(batch(DesignSystem.Material3)), { 240 })
        assertTrue(exists(barOverflowTestTag(BAR)), "nothing overflowed at 240 dp")
        val back = onNodeWithTag(nodeTestTag(BACK), useUnmergedTree = true)
        back.assertIsDisplayed()
        val bounds = back.getBoundsInRoot()
        assertTrue(bounds.right - bounds.left > 1.dp, "the back button was squeezed to ${bounds.right - bounds.left}")
        assertTrue(onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty(), "the title left the bar")
    }

    /** The overflow button is named for what it does, not left as an unnamed glyph. */
    @Test
    fun fr30_3_the_overflow_button_has_an_accessible_name() = runDesktopComposeUiTest(WIDE, HEIGHT) {
        showBar(FakeHostConnection(batch(DesignSystem.Material3)), { NARROW })
        onNodeWithTag(barOverflowTestTag(BAR)).assertContentDescriptionEquals("More actions")
    }

    /**
     * The window-caption strip, the bar that centres its title and the floating bar all
     * overflow the same way: the last actions go into the menu at a narrow width, nothing
     * does at a wide one, and nothing the bar draws is squeezed or under the window buttons.
     */
    @Test
    fun fr30_3_every_bar_path_overflows_the_same_way() = runDesktopComposeUiTest(WIDE, HEIGHT) {
        var path by mutableStateOf(CAPTION_STRIP)
        var width by mutableStateOf(NARROW)
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                key(path) {
                    ComposeRustContent(
                        rememberComposeRustHost(FakeHostConnection(batch(path.system))),
                        Modifier.requiredSize(width.dp, HEIGHT.dp),
                        caption = path.caption,
                    )
                }
            }
        }
        for (each in listOf(CAPTION_STRIP, CENTRED_TITLE, FLOATING)) {
            path = each
            val endInset = if (each.caption.buttonsAtStart) 0.dp else each.caption.buttonsWidth

            width = NARROW
            waitForIdle()
            val inBar = actionsInTheBar()
            assertTrue(ACTIONS.last() !in inBar, "${each.name}: the last action stayed in a $NARROW dp bar")
            assertEquals(ACTIONS.take(inBar.size), inBar, "${each.name}: the bar kept actions out of order")
            assertTrue(exists(barOverflowTestTag(BAR)), "${each.name}: actions overflowed with no overflow button")
            assertNothingSqueezed(each.name, endInset)

            onNodeWithTag(barOverflowTestTag(BAR)).performClick()
            waitForIdle()
            for (id in ACTIONS.drop(inBar.size)) {
                assertTrue(exists(nodeTestTag(id)), "${each.name}: action $id is in neither the bar nor the menu")
            }

            width = WIDE
            waitForIdle()
            assertEquals(ACTIONS, actionsInTheBar(), "${each.name}: an action overflowed a $WIDE dp bar")
            assertTrue(!exists(barOverflowTestTag(BAR)), "${each.name}: a bar with room drew an overflow button")
            assertTrue(!exists(barOverflowMenuTestTag(BAR)), "${each.name}: the menu outlived the overflow")
            assertNothingSqueezed(each.name, endInset)
        }
    }

    /** Only the run of actions at the bar's end can overflow; a weighted child or a non-action ends it. */
    @Test
    fun fr30_3_the_trailing_actions_are_the_run_of_actions_at_the_end() {
        val table = NodeTable()
        batch(DesignSystem.Material3).filterNot { it is Mutation.SetTheme }.forEach { table.apply(it) }
        val children = table.node(BAR)!!.children.toList()
        assertEquals(ACTIONS, children.drop(table.trailingActionsStart(children)))
    }

    /** The bar keeps the longest prefix that fits, with room for the overflow button when any is left out. */
    @Test
    fun fr30_3_the_bar_keeps_the_longest_prefix_that_fits() {
        val widths = listOf(50, 50, 50, 50)
        val anchor = 20
        fun rowWidth(count: Int, withAnchor: Boolean) =
            widths.take(count).sum() + (if (withAnchor) anchor else 0)
        assertEquals(4, fittingActions(4, 200, ::rowWidth), "everything fits with no button")
        assertEquals(3, fittingActions(4, 199, ::rowWidth), "three and the button fit in 199")
        assertEquals(2, fittingActions(4, 169, ::rowWidth), "two and the button fit in 169")
        assertEquals(0, fittingActions(4, 10, ::rowWidth), "not even the button fits, and nothing is kept")
    }
}
