// The Kotlin half of the probe check-windows-mingw-link.sh links into an MSVC executable.
//
// Compiled by Kotlin/Native for mingwX64 into a static library, the way the renderer is, so
// it carries the same two things an MSVC link gets wrong without fix-mingw-objects.py: the
// Kotlin runtime's static constructors, and per-function unwind data. The exception is thrown
// three calls down and caught at the top, which is the shape that sent the unwinder into a
// loop; the uncaught one has to be reported by Kotlin and end the process.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

import kotlin.native.CName

class Probe(message: String) : Exception(message)

// A value built at startup rather than at compile time, so a runtime whose initialisers
// never ran answers wrongly here instead of only hanging somewhere less obvious.
private val words = listOf("one", "two", "three").map { it.uppercase() }

private fun third(value: Int): Int {
    if (value >= 0) throw Probe("thrown at $value")
    return value
}

private fun second(value: Int): Int = third(value) + words.size

private fun first(value: Int): Int = second(value) + 1

/** How many of [times] exceptions thrown three calls down were caught at the top. */
@CName("probe_catch")
fun probeCatch(times: Int): Int {
    var caught = 0
    repeat(times) { index ->
        try {
            first(index)
        } catch (thrown: Probe) {
            if (thrown.message == "thrown at $index") caught++
        }
    }
    return if (words.joinToString(",") == "ONE,TWO,THREE") caught else -1
}

/** Throws through the same three calls with nothing to catch it. Never returns. */
@CName("probe_uncaught")
fun probeUncaught(): Int = first(7)
