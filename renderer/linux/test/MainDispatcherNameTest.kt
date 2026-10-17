package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.mainDispatcherName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The main dispatcher the start report names on this renderer is the stand-in
 * kotlinx.coroutines answers with where a platform has none, and naming it does not throw.
 *
 * Linux has no main loop for coroutines to attach to, so `Dispatchers.Main` is a stand-in
 * that refuses the first thing sent to it; the window gives its scene a dispatcher of its own
 * for that reason. The smoke run (renderer/desktop/scripts/smoke-startup.sh) requires this
 * name in the scene line, so a build that gained a real main dispatcher is noticed there and
 * here rather than passing as if nothing had changed.
 */
class MainDispatcherNameTest {
    @Test
    fun nfr14_the_scene_line_names_the_missing_main_dispatcher() {
        assertEquals("kotlinx.coroutines.MissingMainDispatcher", mainDispatcherName())
    }
}
