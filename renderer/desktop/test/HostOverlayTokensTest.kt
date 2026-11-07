package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.foundation.dialogTestTag
import dev.darkpyonix.composerust.foundation.sheetTestTag
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.OverlayTokens
import dev.darkpyonix.composerust.protocol.Paint
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
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class HostOverlayTokensTest {
    private val frames = FrameRequestSource()

    @Test
    fun fr74_sheet_honors_overlay_tokens_and_own_modifiers() = runComposeUiTest {
        val sheetId = 1
        val textId = 2
        val tokens = OverlayTokens(
            sheetCornerRadius = 24,
            sheetElevation = 8,
            sheetPadding = 20,
            scrim = 0x80000000L,
        )
        val connection = FakeHostConnection(
            listOf(
                Mutation.SetTheme(
                    Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
                ),
                Mutation.SetOverlayTokens(tokens),
                Mutation.Create(sheetId, WidgetKind.Sheet),
                Mutation.SetProp(sheetId, PropertyKind.Open, PropertyValue.Bool(true)),
                Mutation.Create(textId, WidgetKind.Text),
                Mutation.SetProp(textId, PropertyKind.Text, PropertyValue.Text("Settings")),
                Mutation.Insert(sheetId, textId, 0),
            ),
        )
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(connection),
                    Modifier.requiredSize(500.dp, 800.dp),
                )
            }
        }
        waitForIdle()

        assertEquals(tokens, connection.host.table.overlayTokens)
        onNodeWithTag(nodeTestTag(textId)).assertTextEquals("Settings")

        // Overriding scrim on the node directly
        connection.scheduleFrame(
            listOf(
                Mutation.SetProp(sheetId, PropertyKind.Scrim, PropertyValue.Paint(Paint.Rgb(0x40, 0x11, 0x22, 0x33))),
            ),
        )
        frames.request()
        waitForIdle()

        val node = connection.host.table.node(sheetId)
        assertNotNull(node)
        assertEquals(Paint.Rgb(0x40, 0x11, 0x22, 0x33), node.paintProp(PropertyKind.Scrim))
    }

    @Test
    fun fr74_dialog_and_menu_honor_scrim_and_tokens() = runComposeUiTest {
        val root = 10
        val dialogId = 11
        val dialogText = 12
        val tokens = OverlayTokens(
            dialogCornerRadius = 16,
            popupCornerRadius = 12,
            dialogElevation = 6,
            popupElevation = 4,
            dialogPadding = 16,
            popupPadding = 8,
            scrim = 0x66000000L,
        )
        val connection = FakeHostConnection(
            listOf(
                Mutation.SetTheme(
                    Theme(DesignSystem.Cupertino, DesignSystem.Cupertino, ColorScheme.Light, adaptive = false),
                ),
                Mutation.SetOverlayTokens(tokens),
                Mutation.Create(root, WidgetKind.Column),
                Mutation.Create(dialogId, WidgetKind.Dialog),
                Mutation.SetProp(dialogId, PropertyKind.Open, PropertyValue.Bool(true)),
                Mutation.SetProp(dialogId, PropertyKind.Scrim, PropertyValue.Paint(Paint.Rgb(0x80, 0x00, 0x00, 0x00))),
                Mutation.Create(dialogText, WidgetKind.Text),
                Mutation.SetProp(dialogText, PropertyKind.Text, PropertyValue.Text("Confirm")),
                Mutation.Insert(dialogId, dialogText, 0),
                Mutation.Insert(root, dialogId, 0),
            ),
        )
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(connection),
                    Modifier.requiredSize(800.dp, 600.dp),
                )
            }
        }
        waitForIdle()

        assertEquals(tokens, connection.host.table.overlayTokens)
        onNodeWithTag(nodeTestTag(dialogText)).assertTextEquals("Confirm")
        onNodeWithTag(dialogTestTag(dialogId)).assertExists()
    }
}
