package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.foundation.ModalOverlayPositionProvider
import dev.darkpyonix.composerust.foundation.ModalOverlayProperties
import dev.darkpyonix.composerust.foundation.bottomSheetHeight
import dev.darkpyonix.composerust.foundation.dialogTestTag
import dev.darkpyonix.composerust.foundation.sheetScrimTestTag
import dev.darkpyonix.composerust.foundation.sheetTestTag
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.SystemBars
import dev.darkpyonix.composerust.runtime.WindowCaption
import dev.darkpyonix.composerust.runtime.pageInsets
import dev.darkpyonix.composerust.runtime.platformKeyboardInset
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PAGE = 1
private const val BAR = 2
private const val TITLE = 3
private const val BODY = 4
private const val FOOTER = 5
private const val SHEET = 6
private const val FIELD = 7

/** A phone in portrait, at one pixel per dp so the numbers read as they are. */
private val WIDTH = 360.dp
private val HEIGHT = 800.dp

/** About what a phone keyboard covers in portrait. */
private val KEYBOARD = 300.dp

/** A clock above and a gesture bar below. */
private val PHONE = SystemBars(top = 24.dp, bottom = 48.dp)

private fun assertNear(expected: Dp, actual: Dp, what: String) {
    assertTrue(
        abs(expected.value - actual.value) <= 1f,
        "$what should be about ${expected.value}dp but was ${actual.value}dp",
    )
}

private val THEME = Mutation.SetTheme(
    Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
)

private fun text(id: Int, parent: Int, index: Int, value: String) = listOf(
    Mutation.Create(id, WidgetKind.Text),
    Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(value)),
    Mutation.Insert(parent, id, index),
)

/**
 * The notepad and chat shape: a bar at the top, a body that takes what is left, and a row
 * at the bottom (`Column { TopAppBar { Text } Box(weight 1) Text }`).
 */
private val PAGE_TREE = listOf(
    THEME,
    Mutation.Create(PAGE, WidgetKind.Column),
    Mutation.SetModifier(PAGE, 0, ProtocolModifier.FillMaxWidth),
    Mutation.SetModifier(PAGE, 1, ProtocolModifier.FillMaxHeight),
    Mutation.Create(BAR, WidgetKind.TopAppBar),
    Mutation.Insert(PAGE, BAR, 0),
) + text(TITLE, BAR, 0, "Notes") + listOf(
    Mutation.Create(BODY, WidgetKind.Box),
    Mutation.SetModifier(BODY, 0, ProtocolModifier.FillMaxWidth),
    Mutation.SetModifier(BODY, 1, ProtocolModifier.Weight(1f)),
    Mutation.Insert(PAGE, BODY, 1),
) + text(FOOTER, PAGE, 2, "12 words")

/** The todo shape: an open sheet holding a text field. */
private val SHEET_TREE = listOf(
    THEME,
    Mutation.Create(SHEET, WidgetKind.Sheet),
    Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(true)),
    Mutation.Create(FIELD, WidgetKind.TextField),
    Mutation.SetProp(FIELD, PropertyKind.Placeholder, PropertyValue.Text("New task")),
    Mutation.Insert(SHEET, FIELD, 0),
)

/**
 * The on-screen keyboard is reported, not panned for.
 *
 * Android used to slide the whole window up by the keyboard's height: the bar at the top
 * left the screen and ran under the status bar, the rows below the focused field stayed
 * under the keyboard, and a sheet's scrim started part way down the screen. Now the page
 * ends at the keyboard's top edge, the bar keeps its place, and an overlay's scrim keeps
 * covering the whole window while only its surface moves up.
 *
 * A desktop window has no keyboard to put up, so these install one through the same hook
 * the platform reads it from.
 */
@OptIn(ExperimentalTestApi::class)
class KeyboardInsetTest {
    private val frames = FrameRequestSource()

    private fun <T> withKeyboard(block: (setKeyboard: (Dp) -> Unit) -> T): T {
        val previous = platformKeyboardInset
        var keyboard by mutableStateOf(0.dp)
        platformKeyboardInset = { keyboard }
        try {
            return block { keyboard = it }
        } finally {
            platformKeyboardInset = previous
        }
    }

    @Test
    fun fr19_the_page_ends_above_the_keyboard() {
        val (_, bottom) = pageInsets(
            WindowCaption.None,
            PHONE.copy(bottom = 0.dp),
            barTakesTheTop = true,
            navigationTakesTheBottom = false,
            keyboard = KEYBOARD,
        )
        assertEquals(KEYBOARD, bottom, "the bottom of the page is drawn under the keyboard")
    }

    @Test
    fun fr19_the_keyboard_leaves_the_top_of_the_page_alone() {
        val without = pageInsets(WindowCaption.None, PHONE, barTakesTheTop = false, navigationTakesTheBottom = false)
        val with = pageInsets(
            WindowCaption.None,
            PHONE,
            barTakesTheTop = false,
            navigationTakesTheBottom = false,
            keyboard = KEYBOARD,
        )
        assertEquals(without.first, with.first, "the keyboard moved the top of the page")
    }

    /** A navigation bar along the bottom grows into the gesture strip, but not into the keyboard. */
    @Test
    fun fr19_a_navigation_along_the_bottom_still_stops_above_the_keyboard() {
        val (_, bottom) = pageInsets(
            WindowCaption.None,
            PHONE,
            barTakesTheTop = false,
            navigationTakesTheBottom = true,
            keyboard = KEYBOARD,
        )
        assertEquals(KEYBOARD, bottom)
    }

    @Test
    fun fr19_with_no_keyboard_nothing_changes() {
        assertEquals(
            pageInsets(WindowCaption.None, PHONE, barTakesTheTop = false, navigationTakesTheBottom = false),
            pageInsets(WindowCaption.None, PHONE, barTakesTheTop = false, navigationTakesTheBottom = false, keyboard = 0.dp),
        )
    }

    /**
     * The page shrinks by the keyboard's height and the bar stays where it was: what
     * notepad's word count and chat's top bar needed.
     */
    @Test
    fun fr19_the_page_shrinks_by_the_keyboard_and_the_bar_keeps_its_place() = withKeyboard { setKeyboard ->
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(
                        rememberComposeRustHost(FakeHostConnection(PAGE_TREE)),
                        Modifier.requiredSize(WIDTH, HEIGHT),
                    )
                }
            }
            waitForIdle()
            val pageBefore = onNodeWithTag(nodeTestTag(PAGE)).getBoundsInRoot()
            val barBefore = onNodeWithTag(nodeTestTag(BAR)).getBoundsInRoot()
            val footerBefore = onNodeWithTag(nodeTestTag(FOOTER)).getBoundsInRoot()

            setKeyboard(KEYBOARD)
            waitForIdle()
            val page = onNodeWithTag(nodeTestTag(PAGE)).getBoundsInRoot()
            val bar = onNodeWithTag(nodeTestTag(BAR)).getBoundsInRoot()
            val footer = onNodeWithTag(nodeTestTag(FOOTER)).getBoundsInRoot()

            assertNear(pageBefore.top, page.top, "the top of the page")
            assertNear(
                (pageBefore.bottom - pageBefore.top) - KEYBOARD,
                page.bottom - page.top,
                "the page's height with the keyboard up",
            )
            assertNear(barBefore.top, bar.top, "the bar's top")
            assertNear(barBefore.bottom, bar.bottom, "the bar's bottom")
            assertTrue(
                footer.bottom.value <= (pageBefore.bottom - KEYBOARD).value + 1f,
                "the last row ends at ${footer.bottom}, under a keyboard that starts at " +
                    "${pageBefore.bottom - KEYBOARD}",
            )

            // Put away, the page is as it was.
            setKeyboard(0.dp)
            waitForIdle()
            assertNear(footerBefore.bottom, onNodeWithTag(nodeTestTag(FOOTER)).getBoundsInRoot().bottom, "the last row")
        }
    }

    /**
     * The sheet's surface moves above the keyboard; its scrim does not move at all. The
     * scrim starting part way down the screen was the todo sheet's problem.
     */
    @Test
    fun fr19_a_sheet_rises_above_the_keyboard_and_its_scrim_still_covers_the_window() = withKeyboard { setKeyboard ->
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(
                        rememberComposeRustHost(FakeHostConnection(SHEET_TREE)),
                        Modifier.requiredSize(WIDTH, HEIGHT),
                    )
                }
            }
            waitForIdle()
            val scrimBefore = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()

            setKeyboard(KEYBOARD)
            waitForIdle()
            val scrim = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()
            val sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
            val field = onNodeWithTag(nodeTestTag(FIELD)).getBoundsInRoot()

            assertNear(scrimBefore.top, scrim.top, "the scrim's top")
            assertNear(scrimBefore.bottom, scrim.bottom, "the scrim's bottom")
            assertNear(scrim.bottom - KEYBOARD, sheet.bottom, "the sheet's bottom edge")
            assertTrue(
                field.bottom.value <= (scrim.bottom - KEYBOARD).value + 1f,
                "the field ends at ${field.bottom}, under a keyboard that starts at ${scrim.bottom - KEYBOARD}",
            )
        }
    }

    /** A dialog is centred in what the keyboard leaves, and its scrim covers the window. */
    @Test
    fun fr19_a_dialog_is_centred_above_the_keyboard() = withKeyboard { setKeyboard ->
        runComposeUiTest {
            val dialog = 8
            val label = 9
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(
                        rememberComposeRustHost(
                            FakeHostConnection(
                                listOf(
                                    THEME,
                                    Mutation.Create(dialog, WidgetKind.Dialog),
                                    Mutation.SetProp(dialog, PropertyKind.Open, PropertyValue.Bool(true)),
                                ) + text(label, dialog, 0, "Rename"),
                            ),
                        ),
                        Modifier.requiredSize(WIDTH, HEIGHT),
                    )
                }
            }
            waitForIdle()
            val scrimBefore = onNodeWithTag(dialogTestTag(dialog)).getBoundsInRoot()

            setKeyboard(KEYBOARD)
            waitForIdle()
            val scrim = onNodeWithTag(dialogTestTag(dialog)).getBoundsInRoot()
            val text = onNodeWithTag(nodeTestTag(label)).getBoundsInRoot()

            assertNear(scrimBefore.top, scrim.top, "the scrim's top")
            assertNear(scrimBefore.bottom, scrim.bottom, "the scrim's bottom")
            val above = scrim.bottom - KEYBOARD
            assertTrue(
                text.bottom.value <= above.value + 1f,
                "the dialog's content ends at ${text.bottom}, under a keyboard that starts at $above",
            )
            // Loosely: the surface's own padding need not be the same above and below.
            val centre = (scrim.top + above) / 2f
            val drawn = (text.top + text.bottom) / 2f
            assertTrue(
                abs(centre.value - drawn.value) <= 16f,
                "the dialog sits at $drawn, not centred on $centre in what the keyboard leaves",
            )
        }
    }

    /**
     * The keyboard moves a sheet; it does not shrink it.
     *
     * On a phone the todo sheet came down to its drag handle when the keyboard opened: the
     * keyboard was taken off the height the sheet's share was worked out from, and on
     * Android it had already been taken off once by the popup's window. The sheet keeps the
     * height it had and only its position changes.
     */
    @Test
    fun fr19_a_sheet_keeps_its_height_when_the_keyboard_opens() = withKeyboard { setKeyboard ->
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(
                        rememberComposeRustHost(FakeHostConnection(SHEET_TREE)),
                        Modifier.requiredSize(WIDTH, HEIGHT),
                    )
                }
            }
            waitForIdle()
            val before = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()

            setKeyboard(KEYBOARD)
            waitForIdle()
            val after = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
            val field = onNodeWithTag(nodeTestTag(FIELD)).getBoundsInRoot()
            // The overlay's window is the whole test window, which is not the content's
            // size, so the keyboard's top edge is measured from the scrim's bottom.
            val scrim = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()

            assertTrue(before.height.value > 0f, "the sheet has no height before the keyboard opens")
            assertNear(before.height, after.height, "the sheet's height with the keyboard up")
            assertNear(scrim.bottom - KEYBOARD, after.bottom, "the sheet's bottom edge")
            assertTrue(
                field.top.value >= after.top.value - 1f && field.bottom.value <= after.bottom.value + 1f,
                "the field at $field is not inside the sheet at $after",
            )
        }
    }

    /**
     * The sizes the todo sheet was laid out with on a Galaxy Z Flip 4 (1080 x 2640 pixels at
     * 2.625 pixels per dp): about 1006dp of window, a 36dp status bar and a 426dp keyboard.
     *
     * The popup's window came out as tall as the space between the status bar and the
     * keyboard (544dp), the keyboard was taken off that again (118dp left), and half of
     * that was the sheet: 59dp, which is the handle and nothing else. Measured against the
     * whole window, the sheet is the half it asked for and still fits above the keyboard.
     */
    @Test
    fun fr19_a_sheet_on_a_phone_keeps_its_share_of_the_window_above_the_keyboard() {
        val window = 1005.7.dp
        val status = 36.dp
        val keyboard = 426.dp
        assertNear(window * 0.5f, bottomSheetHeight(window, 0.5f, 0.dp, status), "without the keyboard")
        assertNear(window * 0.5f, bottomSheetHeight(window, 0.5f, keyboard, status), "with the keyboard")
        // A keyboard that leaves less than the sheet asked for: the sheet takes all of what
        // is left between the status bar and the keyboard, never more.
        val tall = 700.dp
        assertNear(window - tall - status, bottomSheetHeight(window, 0.5f, tall, status), "under a tall keyboard")
        assertEquals(0.dp, bottomSheetHeight(window, 0.5f, window, status), "a keyboard over the whole window")
    }

    /**
     * A sheet's or a dialog's window is measured against the whole window.
     *
     * Android measures and places a clipped popup inside the parent window's visible frame,
     * which leaves out the status bar, the gesture bar and the keyboard. That is what put
     * the top of the todo sheet's scrim below the status bar and made its window only as
     * tall as the space above the keyboard. A desktop popup has no such frame, so this is
     * checked on the properties the Android popup reads rather than on a layout.
     */
    @Test
    fun fr19_a_modal_overlay_window_is_not_clipped_to_the_visible_frame() {
        assertFalse(ModalOverlayProperties.clippingEnabled, "the overlay is measured against the visible frame")
        assertTrue(ModalOverlayProperties.focusable, "a field in the overlay cannot take the keyboard")
        assertFalse(ModalOverlayProperties.dismissOnBackPress, "Back closes the overlay behind the renderer's back")
    }

    /**
     * A sheet's or a dialog's window starts at the corner of the application's window, not
     * centred on the node that opened it.
     *
     * The numbers are the todo page on the Flip 4 in pixels: the page below its 95 pixel
     * status bar, a window 2640 pixels tall, and the popup the size of the visible frame
     * with the keyboard up. Centred on the page, that popup started 654 pixels down; the
     * scrim has to start at the top of the screen whatever size the popup came out.
     */
    @Test
    fun fr19_a_modal_overlay_window_starts_at_the_window_corner() {
        val window = IntSize(1080, 2640)
        val page = IntRect(0, 95, 1080, 2640)
        for (popup in listOf(window, IntSize(1080, 1427))) {
            assertEquals(
                IntOffset.Zero,
                ModalOverlayPositionProvider.calculatePosition(page, window, LayoutDirection.Ltr, popup),
                "a $popup overlay opened from $page",
            )
        }
    }
}
