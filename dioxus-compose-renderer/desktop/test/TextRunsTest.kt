package dioxus.compose.test

import dioxus.compose.foundation.TextRun
import dioxus.compose.foundation.decodeRuns
import dioxus.compose.foundation.runsProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Runs inside a string, and what happens when a Host miscounts them.
 *
 * A run reaching past the end of the string, or starting inside the one before it, is a
 * Host being wrong about its own text. The reader still wants to read the paragraph, so
 * it is reported and the string is drawn plain: never an abort.
 */
class TextRunsTest {

    private fun run(start: Int, length: Int) = TextRun(
        start = start,
        length = length,
        role = null,
        color = null,
        bold = false,
        italic = false,
        underline = false,
        strikethrough = false,
        handlerId = 0,
    )

    @Test
    fun fr26_runs_that_fit_the_string_are_accepted() {
        assertNull(runsProblem(listOf(run(0, 5), run(6, 4)), byteLength = 10))
    }

    @Test
    fun fr26_a_run_past_the_end_is_reported() {
        val problem = runsProblem(listOf(run(6, 9)), byteLength = 10)
        assertNotNull(problem, "a run covering bytes 6 to 15 of a ten byte string is wrong")
    }

    @Test
    fun fr26_overlapping_runs_are_reported() {
        val problem = runsProblem(listOf(run(0, 6), run(4, 3)), byteLength = 20)
        assertNotNull(problem, "the second run starts inside the first")
    }

    @Test
    fun fr26_an_empty_run_is_reported() {
        assertNotNull(runsProblem(listOf(run(3, 0)), byteLength = 10))
    }

    @Test
    fun fr26_a_blob_that_is_not_whole_records_is_refused() {
        assertNull(decodeRuns(ByteArray(17)), "seventeen bytes is not a whole number of runs")
        assertEquals(0, decodeRuns(ByteArray(0))?.size, "no bytes is no runs, not an error")
    }
}
