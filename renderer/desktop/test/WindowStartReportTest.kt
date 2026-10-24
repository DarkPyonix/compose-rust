package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.WindowStartReport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The window start lines read the same whichever renderer printed them.
 *
 * One copy of this file is compiled into the native image's tests and, linked from here, into
 * the Kotlin/Native macOS and Linux renderers' tests, so the same expected text is checked on
 * the JVM and on Kotlin/Native. The smoke runs of all of them read these lines with one check
 * (renderer/desktop/scripts/smoke-startup.sh), and a runtime that wrote a number differently,
 * `2x` where another writes `2.0x`, would fail one of them and not the others.
 */
class WindowStartReportTest {

    @Test
    fun nfr14_the_window_line_is_the_same_on_every_renderer() {
        assertEquals(
            "compose-rust: a window of our own, 1040x720 at 2.0x, with no toolkit in it",
            WindowStartReport.windowOpenedLine(1040, 720, 2.0f),
        )
        assertEquals(
            "compose-rust: a window of our own, 780x540 at 1.5x, with no toolkit in it",
            WindowStartReport.windowOpenedLine(780, 540, 1.5f),
        )
    }

    @Test
    fun nfr14_the_scene_line_names_the_dispatcher_and_what_was_asked_for() {
        assertEquals(
            "compose-rust: Compose scene created, main dispatcher " +
                "dev.darkpyonix.composerust.ui.platform.FrameMainDispatcher " +
                "(compose.main.dispatcher=coroutines), window 520x360 at 1.0x",
            WindowStartReport.sceneCreatedLine(
                dispatcher = "dev.darkpyonix.composerust.ui.platform.FrameMainDispatcher",
                asked = "coroutines",
                width = 520,
                height = 360,
                scale = 1.0f,
            ),
        )
        // A Kotlin/Native build has no system properties, so nothing was asked for.
        assertEquals(
            "compose-rust: Compose scene created, main dispatcher " +
                "kotlinx.coroutines.DarwinMainDispatcher " +
                "(compose.main.dispatcher=unset), window 1040x720 at 2.0x",
            WindowStartReport.sceneCreatedLine(
                dispatcher = "kotlinx.coroutines.DarwinMainDispatcher",
                asked = null,
                width = 1040,
                height = 720,
                scale = 2.0f,
            ),
        )
    }

    @Test
    fun nfr14_the_first_frame_line_is_the_same_on_every_renderer() {
        assertEquals("compose-rust: first frame drawn, 520x360", WindowStartReport.firstFrameLine(520, 360))
    }
}
