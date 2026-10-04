package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.platform.NativeFileDrops
import dev.darkpyonix.composerust.ui.platform.WindowEvent
import dev.darkpyonix.composerust.ui.platform.routeFileDrop
import dev.darkpyonix.composerust.ui.platform.splitDroppedPaths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Files let go over a window that has no toolkit to tell which node they are over.
 *
 * The window reports a place and a list of paths; the node that takes files has said where
 * it is. These check that the one under the place gets the files, once, and that a place
 * nobody claims gets nothing.
 */
class NativeFileDropTest {

    private val heard = mutableListOf<HostEvent>()
    private val dispatcher = EventDispatcher { event -> heard += event; true }

    @AfterTest
    fun clear() {
        NativeFileDrops.unregister(1)
        NativeFileDrops.unregister(2)
    }

    @Test
    fun fr27_files_let_go_over_a_target_reach_that_target() {
        NativeFileDrops.register(1, Rect(0f, 0f, 100f, 100f), entered = 7L, dropped = 8L, dispatcher)
        val taken = NativeFileDrops.drop(Offset(50f, 50f), listOf("/a.txt", "/b.txt"))
        assertTrue(taken)
        assertEquals(listOf<HostEvent>(HostEvent.FilesDropped(1, 8L, "/a.txt\u0000/b.txt")), heard)
    }

    @Test
    fun fr27_files_let_go_where_no_target_is_reach_nothing() {
        NativeFileDrops.register(1, Rect(0f, 0f, 100f, 100f), entered = null, dropped = 8L, dispatcher)
        assertFalse(NativeFileDrops.drop(Offset(500f, 500f), listOf("/a.txt")))
        assertTrue(heard.isEmpty())
    }

    @Test
    fun fr27_the_target_drawn_on_top_gets_the_files() {
        NativeFileDrops.register(1, Rect(0f, 0f, 200f, 200f), entered = null, dropped = 1L, dispatcher)
        NativeFileDrops.register(2, Rect(50f, 50f, 100f, 100f), entered = null, dropped = 2L, dispatcher)
        NativeFileDrops.drop(Offset(60f, 60f), listOf("/a.txt"))
        assertEquals(listOf<HostEvent>(HostEvent.FilesDropped(2, 2L, "/a.txt")), heard)
    }

    @Test
    fun fr27_entering_is_said_once_per_target_not_on_every_move() {
        NativeFileDrops.register(1, Rect(0f, 0f, 100f, 100f), entered = 7L, dropped = 8L, dispatcher)
        NativeFileDrops.enter(Offset(10f, 10f))
        NativeFileDrops.enter(Offset(20f, 20f))
        assertEquals(listOf<HostEvent>(HostEvent.FilesEntered(1, 7L)), heard)
        NativeFileDrops.leave()
        NativeFileDrops.enter(Offset(10f, 10f))
        assertEquals(2, heard.size)
    }

    @Test
    fun fr27_the_window_routes_its_file_events_to_the_target() {
        NativeFileDrops.register(1, Rect(0f, 0f, 100f, 100f), entered = 7L, dropped = 8L, dispatcher)
        routeFileDrop(WindowEvent.FILES_ENTERED, Offset(5f, 5f)) { "" }
        routeFileDrop(WindowEvent.FILES_DROPPED, Offset(5f, 5f)) { "/x\u0000/y" }
        assertEquals(
            listOf<HostEvent>(
                HostEvent.FilesEntered(1, 7L),
                HostEvent.FilesDropped(1, 8L, "/x\u0000/y"),
            ),
            heard,
        )
    }

    @Test
    fun fr27_a_path_the_window_could_not_give_is_dropped_and_the_rest_arrive() {
        assertEquals(listOf("/a", "/b"), splitDroppedPaths("/a\u0000\u0000/b"))
        assertTrue(splitDroppedPaths("").isEmpty())
    }
}
