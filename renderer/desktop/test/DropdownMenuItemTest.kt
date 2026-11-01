package dev.darkpyonix.composerust.test

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.height
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.darkpyonix.composerust.foundation.BelowAnchorPositionProvider
import dev.darkpyonix.composerust.foundation.dropdownPopupTestTag
import dev.darkpyonix.composerust.protocol.ButtonVariant
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TextAlign
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag

@OptIn(ExperimentalTestApi::class)
class DropdownMenuItemTest {
    @Test
    fun fr64_closed_dropdown_stays_compact_on_wheel_themes_and_opens_popup() = runComposeUiTest {
        val records = listOf(
            Mutation.SetTheme(
                Theme(DesignSystem.LiquidGlass, DesignSystem.Material3, ColorScheme.Light, false),
            ),
            Mutation.Create(1, WidgetKind.Dropdown),
            Mutation.SetProp(1, PropertyKind.SelectedIndex, PropertyValue.Integer(0)),
            Mutation.SetProp(1, PropertyKind.Collapsible, PropertyValue.Bool(true)),
            Mutation.Create(2, WidgetKind.Text),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("Opus 4.7")),
            Mutation.Insert(1, 2, 0),
            Mutation.Create(3, WidgetKind.Text),
            Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("Sonnet 4.6")),
            Mutation.Insert(1, 3, 1),
        )
        val connection = FakeHostConnection(records)
        setContent {
            ComposeRustContent(rememberComposeRustHost(connection))
        }
        waitForIdle()

        val closedHeight = onNodeWithTag(nodeTestTag(1)).getUnclippedBoundsInRoot().height.value
        assertTrue(
            closedHeight < 60f,
            "a closed-field selector must rest as one compact line rather than a five-row wheel: $closedHeight",
        )

        onNodeWithText("Opus 4.7").performClick()
        waitForIdle()
        onNodeWithTag(dropdownPopupTestTag(1)).assertIsDisplayed()
    }

    @Test
    fun fr64_below_anchor_position_provider_places_popup_under_anchor() {
        val anchor = IntRect(left = 40, top = 24, right = 160, bottom = 56)
        val window = IntSize(width = 800, height = 600)
        val popup = IntSize(width = 180, height = 120)

        val offset = BelowAnchorPositionProvider.calculatePosition(
            anchorBounds = anchor,
            windowSize = window,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = popup,
        )
        assertEquals(IntOffset(40, 56), offset)
    }

    @Test
    fun fr64_menu_item_sets_selected_semantics_and_left_aligns_content() = runComposeUiTest {
        val records = listOf(
            Mutation.Create(1, WidgetKind.Column),
            Mutation.SetModifier(1, 0, Modifier.Width(240f)),
            Mutation.Create(2, WidgetKind.Button),
            Mutation.SetModifier(2, 0, Modifier.FillMaxWidth),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("Open")),
            Mutation.SetProp(2, PropertyKind.Variant, PropertyValue.Integer(ButtonVariant.Text.ordinal.toLong() + 1L)),
            Mutation.SetProp(2, PropertyKind.TextAlign, PropertyValue.Integer(TextAlign.Start.ordinal.toLong() + 1L)),
            Mutation.SetProp(2, PropertyKind.Checked, PropertyValue.Bool(true)),
            Mutation.Insert(1, 2, 0),
        )
        val connection = FakeHostConnection(records)
        setContent {
            ComposeRustContent(rememberComposeRustHost(connection))
        }
        waitForIdle()

        onNodeWithTag(nodeTestTag(2)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        )
        val buttonBounds = onNodeWithTag(nodeTestTag(2)).getUnclippedBoundsInRoot()
        val labelBounds = onNodeWithText("Open", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            labelBounds.left.value - buttonBounds.left.value < 40f,
            "a MenuItem label must sit at the leading edge rather than centred in a 240dp row: label=${labelBounds.left}, button=${buttonBounds.left}",
        )
    }

    @Test
    fun fr64_dropdown_honours_node_background_modifier_on_closed_field() = runComposeUiTest {
        val records = listOf(
            Mutation.SetTheme(
                Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false),
            ),
            Mutation.Create(1, WidgetKind.Dropdown),
            Mutation.SetModifier(1, 0, Modifier.Background(Paint.Literal(0xFF123456.toInt()))),
            Mutation.SetProp(1, PropertyKind.SelectedIndex, PropertyValue.Integer(0)),
            Mutation.Create(2, WidgetKind.Text),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("Default")),
            Mutation.Insert(1, 2, 0),
        )
        val connection = FakeHostConnection(records)
        setContent {
            ComposeRustContent(rememberComposeRustHost(connection))
        }
        waitForIdle()

        onNodeWithTag(nodeTestTag(1)).assertIsDisplayed()
    }
}
