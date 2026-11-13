@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlin.native.ref.createCleaner
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.windows.AcquireSRWLockExclusive
import platform.windows.InitializeSRWLock
import platform.windows.ReleaseSRWLockExclusive
import platform.windows.SRWLOCK

/**
 * The lock [FrameDispatcher]'s queue is guarded by, on Windows: a slim reader/writer lock,
 * taken exclusively.
 *
 * The dispatcher is the Linux renderer's file, linked here too; Linux's `FrameLock.kt` is a
 * pthread mutex, which MinGW's headers declare as an integer rather than a struct. An SRW lock
 * needs no destruction, so the cleaner only frees its memory.
 */
internal class FrameLock {
    private val srw = nativeHeap.alloc<SRWLOCK>().also { InitializeSRWLock(it.ptr) }

    @Suppress("unused")
    private val freeSrw = createCleaner(srw) { nativeHeap.free(it) }

    fun lock() {
        AcquireSRWLockExclusive(srw.ptr)
    }

    fun unlock() {
        ReleaseSRWLockExclusive(srw.ptr)
    }
}
