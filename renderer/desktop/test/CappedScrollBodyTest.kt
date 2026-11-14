package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROOT = 1
private const val OVERLAY = 2
private const val BODY_COLUMN = 3
private const val HEADER = 4
private const val SCROLL = 5
private const val FOOTER = 6
private const val FIRST_ROW = 100

/**
 * A modal with a pinned header and footer and a scrolling body between them. The body takes
 * `SizeIn` with only a maximum height, so it is as tall as its rows up to that cap and
 * scrolls past it, and the footer under it stays on the screen either way. Ember's sheet
 * and dialog bodies are this shape.
 */
@OptIn(ExperimentalTestApi::class)
class CappedScrollBodyTest {
    private val frames = FrameRequestSource()

    private fun modal(kind: WidgetKind, rows: Int): List<Mutation> = buildList {
        add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)))
        add(Mutation.Create(ROOT, WidgetKind.Column))
        add(Mutation.Create(OVERLAY, kind))
        add(Mutation.SetProp(OVERLAY, PropertyKind.Open, PropertyValue.Bool(true)))
        add(Mutation.Create(BODY_COLUMN, WidgetKind.Column))
        add(Mutation.Create(HEADER, WidgetKind.Text))
        add(Mutation.SetProp(HEADER, PropertyKind.Text, PropertyValue.Text("New project")))
        add(Mutation.Create(SCROLL, WidgetKind.ScrollColumn))
        add(Mutation.SetModifier(SCROLL, 0, ProtocolModifier.SizeIn(Float.NaN, Float.NaN, Float.NaN, 200f)))
        for (row in 0 until rows) {
            add(Mutation.Create(FIRST_ROW + row, WidgetKind.Text))
            add(Mutation.SetProp(FIRST_ROW + row, PropertyKind.Text, PropertyValue.Text("Row $row")))
            add(Mutation.Insert(SCROLL, FIRST_ROW + row, row))
        }
        add(Mutation.Create(FOOTER, WidgetKind.Text))
        add(Mutation.SetProp(FOOTER, PropertyKind.Text, PropertyValue.Text("Create")))
        add(Mutation.Insert(BODY_COLUMN, HEADER, 0))
        add(Mutation.Insert(BODY_COLUMN, SCROLL, 1))
        add(Mutation.Insert(BODY_COLUMN, FOOTER, 2))
        add(Mutation.Insert(OVERLAY, BODY_COLUMN, 0))
        add(Mutation.Insert(ROOT, OVERLAY, 0))
    }

    private fun bounds(kind: WidgetKind, rows: Int, check: (scroll: Rect, firstRow: Rect, footer: Rect) -> Unit) =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                    ComposeRustContent(
                        rememberComposeRustHost(FakeHostConnection(modal(kind, rows))),
                        Modifier.requiredSize(800.dp, 800.dp),
                    )
                }
            }
            waitForIdle()
            onNodeWithTag(nodeTestTag(FOOTER), useUnmergedTree = true).assertIsDisplayed()
            val of = { id: Int -> onNodeWithTag(nodeTestTag(id), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
            check(of(SCROLL), of(FIRST_ROW), of(FOOTER))
        }

    @Test
    fun fr84_capped_scroll_body_keeps_the_footer_in_a_sheet_and_a_dialog() {
        for (kind in listOf(WidgetKind.Sheet, WidgetKind.Dialog)) {
            // Many rows: the body stops at its cap and the footer sits right under it.
            bounds(kind, rows = 60) { scroll, _, footer ->
                assertEquals(200f, scroll.height, "$kind: the body stops at its cap")
                assertTrue(footer.top >= scroll.bottom - 0.5f, "$kind: the footer is under the body, not under its rows")
                assertTrue(footer.bottom <= 800f, "$kind: the footer is inside the window: $footer")
            }
            // Two rows: the body is only as tall as they are, so the modal shrinks to them.
            bounds(kind, rows = 2) { scroll, firstRow, _ ->
                assertTrue(scroll.height < 200f, "$kind: a short body is shorter than its cap: ${scroll.height}")
                assertTrue(scroll.height >= 2 * firstRow.height - 0.5f, "$kind: and as tall as its two rows")
            }
        }
    }
}
