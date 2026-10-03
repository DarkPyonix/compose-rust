package dioxus.compose.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.width
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.tooling.designShowcaseRecords
import dioxus.compose.ui.node.nodeTestTag
import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.LocalFrameRequests
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROW = 1
private const val VIEWPORT_WIDTH = 200f
private const val TILE_WIDTH = 100f
private const val TILE_COUNT = 20
private const val FIRST_TILE = 10
private const val LABEL_OFFSET = 1000
private const val ADDED_TILE = 900

private fun tile(index: Int) = FIRST_TILE + index
private fun label(index: Int) = LABEL_OFFSET + index

/**
 * A row two tiles wide holding twenty tiles, so the content is ten times wider than the
 * container and the only way to reach the end of it is to scroll.
 */
private fun stripTree(system: DesignSystem = DesignSystem.Material3): List<Mutation> = buildList {
    add(Mutation.SetTheme(Theme(system, DesignSystem.Material3, ColorScheme.Light, false)))
    add(Mutation.Create(ROW, WidgetKind.ScrollRow))
    add(Mutation.SetModifier(ROW, 0, ProtocolModifier.Size(VIEWPORT_WIDTH, 60f)))
    for (index in 0 until TILE_COUNT) {
        add(Mutation.Create(tile(index), WidgetKind.Box))
        add(Mutation.SetModifier(tile(index), 0, ProtocolModifier.Width(TILE_WIDTH)))
        add(Mutation.Create(label(index), WidgetKind.Text))
        add(Mutation.SetProp(label(index), PropertyKind.Text, PropertyValue.Text("tile $index")))
        add(Mutation.Insert(tile(index), label(index), 0))
        add(Mutation.Insert(ROW, tile(index), index))
    }
}

@OptIn(ExperimentalTestApi::class)
class ScrollRowTest {
    // Private to this class rather than the process-wide counter, for the reason
    // ContainerWidgetsTest gives: two hosts alive at once would drive each other's frames.
    private val frames = FrameRequestSource()

    /**
     * Content wider than the row scrolls sideways, and where it was scrolled to survives
     * the Host changing the row and its children. The position is the Renderer's own
     * state, so a frame that recomposes the row must not put it back at the start.
     */
    @Test
    fun fr15_2_10_content_wider_than_a_scroll_row_scrolls_and_keeps_its_position_across_a_recomposition() =
        runComposeUiTest {
            val connection = FakeHostConnection(stripTree())
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    DioxusContent(rememberDioxusHost(connection))
                }
            }
            waitForIdle()

            val viewport = onNodeWithTag(nodeTestTag(ROW)).getUnclippedBoundsInRoot()
            assertTrue(
                abs(viewport.width.value - VIEWPORT_WIDTH) < 1f,
                "the row is ${viewport.width}, and its size is what its modifier said",
            )
            val firstAtRest = onNodeWithTag(nodeTestTag(tile(0))).getUnclippedBoundsInRoot().left
            val lastAtRest = onNodeWithTag(nodeTestTag(tile(TILE_COUNT - 1))).getUnclippedBoundsInRoot()
            assertTrue(
                abs(firstAtRest.value - viewport.left.value) < 1f,
                "an unscrolled row starts at its first child",
            )
            assertTrue(
                lastAtRest.left > viewport.right,
                "the last tile starts at ${lastAtRest.left}, inside a row that ends at " +
                    "${viewport.right}, so there was nothing to scroll",
            )

            onNodeWithTag(nodeTestTag(ROW))
                .performScrollToNode(hasTestTag(nodeTestTag(tile(TILE_COUNT - 1))))
            waitForIdle()

            val firstScrolled = onNodeWithTag(nodeTestTag(tile(0))).getUnclippedBoundsInRoot().left
            val lastScrolled = onNodeWithTag(nodeTestTag(tile(TILE_COUNT - 1))).getUnclippedBoundsInRoot()
            assertTrue(
                firstScrolled < firstAtRest,
                "the first tile stayed at $firstScrolled, so the row did not scroll sideways",
            )
            assertTrue(
                lastScrolled.right.value <= viewport.right.value + 1f,
                "the last tile ends at ${lastScrolled.right}, past a row ending at ${viewport.right}",
            )

            // One frame that touches the row itself, one of its children and its child
            // list: each of those recomposes the row's own node.
            connection.scheduleFrame(
                listOf(
                    Mutation.SetModifier(
                        ROW,
                        1,
                        ProtocolModifier.Background(Paint.Role(ColorRole.Surface)),
                    ),
                    Mutation.SetProp(label(0), PropertyKind.Text, PropertyValue.Text("renamed")),
                    Mutation.Create(ADDED_TILE, WidgetKind.Box),
                    Mutation.SetModifier(ADDED_TILE, 0, ProtocolModifier.Width(TILE_WIDTH)),
                    Mutation.Insert(ROW, ADDED_TILE, TILE_COUNT),
                ),
            )
            frames.request()
            waitForIdle()

            onNodeWithTag(nodeTestTag(label(0))).assertTextEquals("renamed")
            val firstAfter = onNodeWithTag(nodeTestTag(tile(0))).getUnclippedBoundsInRoot().left
            assertEquals(
                firstScrolled.value,
                firstAfter.value,
                0.5f,
                "the row went from $firstScrolled to $firstAfter when the Host recomposed it, " +
                    "and the scroll position is the user's, not the Host's",
            )
        }

    /**
     * The same strip scrolls under every design system. Nothing about a ScrollRow is
     * styled by the Host, so whatever the system does to its children, none of the seven
     * may stop the content from being reached.
     */
    @Test
    fun fr15_2_10_a_scroll_row_draws_and_scrolls_in_every_design_system() {
        DesignSystem.entries.forEach { system ->
            runComposeUiTest {
                val frames = FrameRequestSource()
                setContent {
                    CompositionLocalProvider(LocalFrameRequests provides frames) {
                        DioxusContent(rememberDioxusHost(FakeHostConnection(stripTree(system))))
                    }
                }
                waitForIdle()

                onNodeWithTag(nodeTestTag(ROW)).assertIsDisplayed()
                onNodeWithTag(nodeTestTag(label(0))).assertTextEquals("tile 0")
                val before = onNodeWithTag(nodeTestTag(tile(0))).getUnclippedBoundsInRoot().left

                onNodeWithTag(nodeTestTag(ROW))
                    .performScrollToNode(hasTestTag(nodeTestTag(tile(TILE_COUNT - 1))))
                waitForIdle()

                val after = onNodeWithTag(nodeTestTag(tile(0))).getUnclippedBoundsInRoot().left
                assertTrue(after < before, "under $system the row did not scroll: $before to $after")
            }
        }
    }

    /**
     * A widget nobody can look at is a widget whose design system rule nobody checks, so
     * the showcase has to draw one, and with more content than it can show at once.
     */
    @Test
    fun fr15_2_10_the_showcase_draws_a_scroll_row_wider_than_its_window() {
        DesignSystem.entries.forEach { system ->
            val records = designShowcaseRecords(Theme(system, system, ColorScheme.Light, false))
            val row = records.filterIsInstance<Mutation.Create>()
                .firstOrNull { it.widget == WidgetKind.ScrollRow }
                ?.nodeId
            assertTrue(row != null, "the $system showcase does not draw a ScrollRow")
            val children = records.filterIsInstance<Mutation.Insert>().count { it.parentId == row }
            assertTrue(children >= 8, "a strip of $children tiles fits in the window and never scrolls")
        }
    }
}
