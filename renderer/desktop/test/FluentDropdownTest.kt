package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpRect
import dev.darkpyonix.composerust.foundation.dialogTestTag
import dev.darkpyonix.composerust.foundation.dropdownPopupTestTag
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WINDOW_WIDTH = 640
private const val WINDOW_HEIGHT = 480
private const val PAGE = 1
private const val DROPDOWN = 2
private const val OPTIONS = 4
private const val PAGE_COLOUR = 0xFFFFFFFF.toInt()

/**
 * A Fluent dropdown opens a flyout: a light-dismiss list hanging from the field, with
 * nothing laid over the rest of the window, and every option in it whole.
 */
@OptIn(ExperimentalTestApi::class)
class FluentDropdownTest {

    private fun option(index: Int) = "Option ${index + 1}"

    private fun batch() = buildList {
        add(Mutation.SetTheme(Theme(DesignSystem.Fluent, DesignSystem.Fluent, ColorScheme.Light, false)))
        add(Mutation.Create(PAGE, WidgetKind.Column))
        add(Mutation.SetModifier(PAGE, 0, Modifier.FillMaxWidth))
        add(Mutation.SetModifier(PAGE, 1, Modifier.FillMaxHeight))
        add(Mutation.SetModifier(PAGE, 2, Modifier.Background(Paint.Literal(PAGE_COLOUR))))
        add(Mutation.SetModifier(PAGE, 3, Modifier.Padding(16f)))
        add(Mutation.Create(DROPDOWN, WidgetKind.Dropdown))
        add(Mutation.SetProp(DROPDOWN, PropertyKind.SelectedIndex, PropertyValue.Integer(0)))
        repeat(OPTIONS) { index ->
            val id = 10 + index
            add(Mutation.Create(id, WidgetKind.Text))
            add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(option(index))))
            add(Mutation.Insert(DROPDOWN, id, index))
        }
        add(Mutation.Insert(PAGE, DROPDOWN, 0))
    }

    private fun ComposeUiTest.open(): DpRect {
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch()))) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(DROPDOWN)).performClick()
        waitForIdle()
        return onNodeWithTag(dropdownPopupTestTag(DROPDOWN)).getUnclippedBoundsInRoot()
    }

    @Test
    fun fr64_a_fluent_dropdown_opens_with_no_scrim() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        val popup = open()
        assertEquals(0, onAllNodesWithTag(dialogTestTag(DROPDOWN)).fetchSemanticsNodes().size, "the list opened as a dialog")
        // The window's own content, away from the field and the list, is still the page.
        val pixels = onAllNodes(isRoot())[0].captureToImage().toPixelMap()
        val pageArea = Rect(0f, popup.bottom.value + 24f, WINDOW_WIDTH.toFloat(), WINDOW_HEIGHT.toFloat())
        var covered = 0
        var y = pageArea.top.toInt()
        while (y < pixels.height) {
            var x = 0
            while (x < pixels.width) {
                if (pixels[x, y].toArgb() != PAGE_COLOUR) covered++
                x += 16
            }
            y += 16
        }
        assertEquals(0, covered, "pixels outside the list that are not the page")
    }

    @Test
    fun fr64_a_fluent_dropdown_shows_every_option_whole() = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        val popup = open()
        val field = onNodeWithTag(nodeTestTag(DROPDOWN)).getUnclippedBoundsInRoot()
        assertTrue(popup.top >= field.bottom, "the list hangs below the field: $popup under $field")
        repeat(OPTIONS) { index ->
            val row = onAllNodesWithText(option(index)).onLast().getUnclippedBoundsInRoot()
            assertTrue(
                row.top >= popup.top && row.bottom <= popup.bottom && row.left >= popup.left && row.right <= popup.right,
                "${option(index)} at $row is not whole inside the list at $popup",
            )
        }
    }
}
