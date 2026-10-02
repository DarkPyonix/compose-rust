package dioxus.compose.test

import dioxus.compose.ui.platform.FrameDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Where the scene's own work runs.
 *
 * The host a renderer talks to belongs to one thread and is invisible from any other. A
 * scene left to choose for itself runs its work on the toolkit's queue, and a lazy list
 * asking for the rows it is about to show asked from there: a thread that has no host,
 * which answered that nothing had been initialised. The frames are drawn on one thread
 * and this keeps the scene's work on the same one.
 */
class FrameDispatcherTest {
    @Test
    fun `work waits for the frame that runs it`() {
        val dispatcher = FrameDispatcher()
        var ran = false
        CoroutineScope(dispatcher).launch { ran = true }
        assertTrue(!ran, "nothing should run before a frame asks for it")
        dispatcher.runPending()
        assertTrue(ran, "the frame should run what was waiting")
    }

    @Test
    fun `work runs on the thread that draws`() {
        val dispatcher = FrameDispatcher()
        var where: Thread? = null
        runBlocking {
            Thread { CoroutineScope(dispatcher).launch { where = Thread.currentThread() } }
                .apply { start() }
                .join()
        }
        val drawing = Thread.currentThread()
        dispatcher.runPending()
        assertEquals(drawing, where, "work posted from elsewhere should run on this thread")
    }

    @Test
    fun `a frame runs everything waiting for it`() {
        val dispatcher = FrameDispatcher()
        val order = mutableListOf<Int>()
        val scope = CoroutineScope(dispatcher)
        scope.launch { order.add(1) }
        scope.launch { order.add(2) }
        scope.launch { order.add(3) }
        dispatcher.runPending()
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `work asked for while a frame runs waits for the next one`() {
        val dispatcher = FrameDispatcher()
        val order = mutableListOf<String>()
        val scope = CoroutineScope(dispatcher)
        scope.launch {
            order.add("first")
            scope.launch { order.add("second") }
        }
        dispatcher.runPending()
        assertEquals(listOf("first"), order, "a frame should end rather than chase its own tail")
        dispatcher.runPending()
        assertEquals(listOf("first", "second"), order)
    }
}
