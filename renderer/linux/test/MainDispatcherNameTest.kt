package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.mainDispatcherName
import dev.darkpyonix.composerust.ui.platform.mainForTests
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The main dispatcher the start report names on this renderer is the window's frame
 * dispatcher, once the renderer has installed it, and naming it does not throw.
 *
 * kotlinx.coroutines has no main dispatcher for Linux and answers with a stand-in that throws
 * at the first dispatch, so the renderer installs its own before it makes a scene. A build in
 * which that install compiled to nothing, or installed something else, names another class here.
 * The smoke run (renderer/desktop/scripts/smoke-startup.sh) requires the same name in the scene
 * line of a real run.
 */
class MainDispatcherNameTest {
    @Test
    fun nfr14_the_scene_line_names_the_frame_dispatcher() {
        mainForTests()
        assertEquals("dev.darkpyonix.composerust.ui.platform.FrameDispatcher", mainDispatcherName())
    }
}
