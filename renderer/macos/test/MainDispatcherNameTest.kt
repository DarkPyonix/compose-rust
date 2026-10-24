package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.mainDispatcherName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The main dispatcher the start report names on this renderer is kotlinx.coroutines' own
 * dispatcher onto the main run loop, which is where the fork's window sends the scene's work.
 *
 * The smoke run (renderer/desktop/scripts/smoke-startup.sh) requires this name in the scene
 * line, so the two say the same thing, and this says it without a window.
 */
class MainDispatcherNameTest {
    @Test
    fun nfr14_the_scene_line_names_the_main_run_loop_dispatcher() {
        assertEquals("kotlinx.coroutines.DarwinMainDispatcher", mainDispatcherName())
    }
}
