@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlin.native.ref.createCleaner
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

/**
 * The lock [FrameDispatcher]'s queue is guarded by, on Linux: a pthread mutex.
 *
 * A file of each platform's own, because the dispatcher is one file that the Kotlin/Native
 * Windows renderer links too, and the two systems' locks are declared differently: glibc's
 * `pthread_mutex_t` is a struct, MinGW's an integer. Windows has its own `FrameLock.kt`.
 */
internal class FrameLock {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>().also { pthread_mutex_init(it.ptr, null) }

    @Suppress("unused")
    private val freeMutex = createCleaner(mutex) {
        pthread_mutex_destroy(it.ptr)
        nativeHeap.free(it)
    }

    fun lock() {
        pthread_mutex_lock(mutex.ptr)
    }

    fun unlock() {
        pthread_mutex_unlock(mutex.ptr)
    }
}
