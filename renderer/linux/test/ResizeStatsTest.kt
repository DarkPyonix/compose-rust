@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import resize.dxc_resize_present
import resize.dxc_resize_present_against
import resize.dxc_resize_stale_ratio
import resize.dxc_resize_reset
import resize.dxc_resize_step
import resize.dxc_resize_stats
import resize.dxc_resize_stretched
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The resize accounting, fed through the header the Kotlin/Native window links, with the same
 * sequences `scripts/tests/appkit-live-resize.test.sh` feeds the same header from C. The two
 * have to say the same thing, because the header is the one the native image windows count a
 * drag with and the parity run compares what each path reports.
 */
class ResizeStatsTest {
    private fun counted(run: (resizeStats: dxc_resize_stats) -> Unit): List<Long> {
        val stats = nativeHeap.alloc<dxc_resize_stats>()
        try {
            dxc_resize_reset(stats.ptr)
            run(stats)
            return listOf(stats.steps, stats.presented, stats.stale, dxc_resize_stretched(stats.ptr))
        } finally {
            nativeHeap.free(stats.rawPtr)
        }
    }

    @Test
    fun a_drag_with_a_frame_at_every_size_counts_nothing_stretched_or_stale() {
        val result = counted { stats ->
            for (step in 1..60) {
                dxc_resize_step(stats.ptr, 480 + step * 2, 640 + step * 2)
                dxc_resize_present(stats.ptr, 480 + step * 2, 640 + step * 2)
            }
        }
        assertEquals(listOf(60L, 60L, 0L, 0L), result)
    }

    @Test
    fun a_drag_with_no_frames_counts_every_size_stretched() {
        val result = counted { stats ->
            for (step in 1..60) dxc_resize_step(stats.ptr, 480 + step * 2, 640 + step * 2)
        }
        assertEquals(listOf(60L, 0L, 0L, 60L), result)
    }

    @Test
    fun a_frame_at_the_previous_size_counts_stale_and_leaves_the_size_stretched() {
        val result = counted { stats ->
            dxc_resize_step(stats.ptr, 500, 700)
            dxc_resize_present(stats.ptr, 480, 640)
        }
        assertEquals(listOf(1L, 1L, 1L, 1L), result)
    }

    @Test
    fun a_frame_is_stale_when_it_is_not_the_size_the_window_has_now() {
        val stats = nativeHeap.alloc<dxc_resize_stats>()
        try {
            dxc_resize_reset(stats.ptr)
            // Three frames at the window's size, then one for a size it has already left.
            for (width in 400..402) {
                dxc_resize_present_against(stats.ptr, width, 300, width, 300)
            }
            dxc_resize_present_against(stats.ptr, 402, 300, 405, 300)
            assertEquals(4L, stats.presented)
            assertEquals(1L, stats.stale)
            assertEquals(0.25, dxc_resize_stale_ratio(stats.ptr))
        } finally {
            nativeHeap.free(stats.rawPtr)
        }
    }

    @Test
    fun nothing_presented_is_a_ratio_of_zero() {
        val stats = nativeHeap.alloc<dxc_resize_stats>()
        try {
            dxc_resize_reset(stats.ptr)
            assertEquals(0.0, dxc_resize_stale_ratio(stats.ptr))
        } finally {
            nativeHeap.free(stats.rawPtr)
        }
    }
}
