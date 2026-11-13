package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.foundation.MESSAGE_TEST_TAG
import dev.darkpyonix.composerust.foundation.dialogTestTag
import dev.darkpyonix.composerust.foundation.sheetTestTag
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.MessageDuration
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.OverlayTokens
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.paintProp
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        lateinit var host: ComposeRustHost
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                host = rememberComposeRustHost(connection)
                ComposeRustContent(
                    host,
                    Modifier.requiredSize(500.dp, 800.dp),
                )
            }
        }
        waitForIdle()

        assertEquals(tokens, host.table.overlayTokens)
        onNodeWithTag(nodeTestTag(textId)).assertTextEquals("Settings")

        // Overriding scrim on the node directly
        val scrimBits = (2L shl 32) or (0x40112233L and 0xFFFFFFFFL)
        connection.scheduleFrame(
            listOf(
                Mutation.SetProp(sheetId, PropertyKind.Scrim, PropertyValue.Integer(scrimBits)),
            ),
        )
        frames.request()
        waitForIdle()

        val node = host.table.node(sheetId)
        assertNotNull(node)
        assertEquals(Paint.Literal(0x40112233), node.paintProp(PropertyKind.Scrim))
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
        val scrimBits = (2L shl 32) or (0x80000000L and 0xFFFFFFFFL)
        val connection = FakeHostConnection(
            listOf(
                Mutation.SetTheme(
                    Theme(DesignSystem.Cupertino, DesignSystem.Cupertino, ColorScheme.Light, adaptive = false),
                ),
                Mutation.SetOverlayTokens(tokens),
                Mutation.Create(root, WidgetKind.Column),
                Mutation.Create(dialogId, WidgetKind.Dialog),
                Mutation.SetProp(dialogId, PropertyKind.Open, PropertyValue.Bool(true)),
                Mutation.SetProp(dialogId, PropertyKind.Scrim, PropertyValue.Integer(scrimBits)),
                Mutation.Create(dialogText, WidgetKind.Text),
                Mutation.SetProp(dialogText, PropertyKind.Text, PropertyValue.Text("Confirm")),
                Mutation.Insert(dialogId, dialogText, 0),
                Mutation.Insert(root, dialogId, 0),
            ),
        )
        lateinit var host: ComposeRustHost
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                host = rememberComposeRustHost(connection)
                ComposeRustContent(
                    host,
                    Modifier.requiredSize(800.dp, 600.dp),
                )
            }
        }
        waitForIdle()

        assertEquals(tokens, host.table.overlayTokens)
        onNodeWithTag(nodeTestTag(dialogText)).assertTextEquals("Confirm")
        onNodeWithTag(dialogTestTag(dialogId)).assertExists()
        val dialogNode = host.table.node(dialogId)
        assertNotNull(dialogNode)
        assertEquals(Paint.Literal(0x80000000.toInt()), dialogNode.paintProp(PropertyKind.Scrim))
    }

    /**
     * The message on a white page, captured, with [tokens] sent first when there are any.
     * The clock is held so the message is still up when the picture is taken.
     */
    private fun messagePicture(tokens: OverlayTokens?): PixelMap {
        lateinit var pixels: PixelMap
        runComposeUiTest {
            mainClock.autoAdvance = false
            val batch = buildList {
                add(
                    Mutation.SetTheme(
                        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
                    ),
                )
                if (tokens != null) add(Mutation.SetOverlayTokens(tokens))
                add(Mutation.Create(1, WidgetKind.Box))
                add(Mutation.SetModifier(1, 0, ProtocolModifier.FillMaxWidth))
                add(Mutation.SetModifier(1, 1, ProtocolModifier.FillMaxHeight))
                add(Mutation.SetModifier(1, 2, ProtocolModifier.Background(Paint.Literal(WHITE))))
                add(Mutation.ShowMessage(0L, "Saved", "", MessageDuration.Long))
            }
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(
                        rememberComposeRustHost(FakeHostConnection(batch)),
                        Modifier.requiredSize(400.dp, 300.dp),
                    )
                }
            }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            onNodeWithTag(MESSAGE_TEST_TAG).assertTextEquals("Saved")
            pixels = onRoot().captureToImage().toPixelMap()
        }
        return pixels
    }

    /** The left, top, right and bottom pixel of the message's dark container. */
    private fun containerBounds(pixels: PixelMap): IntArray {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val colour = pixels[x, y]
                if ((colour.red + colour.green + colour.blue) / 3f < 0.5f) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue(right >= 0, "the message's container is on the page")
        return intArrayOf(left, top, right, bottom)
    }

    private fun isWhite(pixels: PixelMap, x: Int, y: Int): Boolean {
        val colour = pixels[x, y]
        return colour.red > 253f / 255f && colour.green > 253f / 255f && colour.blue > 253f / 255f
    }

    /**
     * A message is not a node and takes no Modifier, so its shadow comes from the overlay
     * tokens. With `toast_elevation(8)` the page just under the message is darkened by its
     * shadow. With 0 the message is the design system's own, pixel for pixel the same as
     * with no tokens at all, and the page under it is untouched.
     */
    @Test
    fun fr74_toast_elevation_token() {
        val plain = messagePicture(null)
        val zero = messagePicture(OverlayTokens(toastElevation = 0))
        val lifted = messagePicture(OverlayTokens(toastElevation = 8))

        val (left, _, right, bottom) = containerBounds(plain).toList()
        assertEquals(containerBounds(plain).toList(), containerBounds(lifted).toList(), "the shadow moves nothing")
        val middle = (left + right) / 2
        val under = bottom + 3
        assertTrue(isWhite(plain, middle, under), "no shadow under the design system's message: ${plain[middle, under]}")
        assertTrue(!isWhite(lifted, middle, under), "a shadow under the lifted message: ${lifted[middle, under]}")

        for (y in 0 until plain.height) {
            for (x in 0 until plain.width) {
                assertEquals(plain[x, y], zero[x, y], "a zero elevation token draws the design system's message, at ($x, $y)")
            }
        }
    }

    /**
     * `toast_corner_radius` rounds the message's corners past the design system's: a pixel
     * three in from the bottom left corner is the container under the design system's small
     * corner and the page under a 20dp one.
     */
    @Test
    fun fr74_toast_corner_radius_token() {
        val plain = messagePicture(null)
        val round = messagePicture(OverlayTokens(toastCornerRadius = 20))
        val (left, _, _, bottom) = containerBounds(plain).toList()
        assertTrue(!isWhite(plain, left + 3, bottom - 3), "the design system's corner keeps that pixel: ${plain[left + 3, bottom - 3]}")
        assertTrue(isWhite(round, left + 3, bottom - 3), "a 20dp corner cuts it away: ${round[left + 3, bottom - 3]}")
    }
}

private const val WHITE = 0xFFFFFFFF.toInt()
