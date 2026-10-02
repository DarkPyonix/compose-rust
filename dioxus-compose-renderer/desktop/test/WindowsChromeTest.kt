package dioxus.compose.test

import dioxus.compose.ui.platform.WindowChrome
import dioxus.compose.ui.platform.platformDrawsWindowButtons
import dioxus.compose.ui.platform.platformKeepsSystemFrame
import dioxus.compose.ui.platform.windowCaption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a Windows window keeps, and what it gives up.
 *
 * An undecorated window on Windows is not a window without a title bar. It is a window
 * without a drop shadow, without a resize border and without Snap Layouts, because the
 * frame provides all three and nothing here can draw them. The first person to run this
 * on Windows said the borders looked crude, and that is what they were looking at.
 *
 * Keeping the system bar avoids that and costs the look the whole feature exists for, so
 * the window keeps the frame and gives up only the caption, which is what VS Code and
 * Windows Terminal do. The tests below fix the three things that split that arrangement
 * from either of the two it sits between.
 */
class WindowsChromeTest {

    @Test
    fun fr19_windows_keeps_the_frame_it_was_given() {
        assertTrue(
            platformKeepsSystemFrame("Windows 11"),
            "an undecorated window on Windows loses the shadow, the resize border and " +
                "Snap Layouts along with the bar, and none of the three can be drawn",
        )
        assertTrue(platformKeepsSystemFrame("Mac OS X"), "the bar goes transparent, not away")
        assertFalse(
            platformKeepsSystemFrame("Linux"),
            "Linux offers no equivalent of the macOS client properties, so the whole " +
                "decoration goes and every part of it is drawn",
        )
    }

    @Test
    fun fr19_windows_draws_its_own_caption_buttons() {
        assertFalse(
            platformDrawsWindowButtons("Windows 11"),
            "the caption strip became client area, and the system's buttons left with it",
        )
        assertTrue(platformDrawsWindowButtons("Mac OS X"), "the traffic lights stay")
        assertFalse(platformDrawsWindowButtons("Linux"), "nothing of the system's is left")
    }

    @Test
    fun fr19_windows_reserves_the_strip_it_took() {
        assertTrue(
            windowCaption(null, WindowChrome.Modern, "Windows 11").height.value > 0f,
            "the caption is drawn there now, so content laid out under it would be " +
                "underneath the buttons and the title",
        )
        assertEquals(
            0.0f,
            windowCaption(null, WindowChrome.System, "Windows 11").height.value,
            "a window that asked for the system bar never gave the strip away",
        )
        assertEquals(
            0.0f,
            windowCaption(null, WindowChrome.System, "Linux").height.value,
            "a window that asked for the system bar never had a strip to give away",
        )
        assertTrue(
            windowCaption(null, WindowChrome.Modern, "Linux").height.value > 0f,
            "Linux draws the caption itself and needs the room for it",
        )
    }
}
