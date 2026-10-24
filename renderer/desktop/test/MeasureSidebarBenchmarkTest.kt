package dev.darkpyonix.composerust.test

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.GenericFamily
import dev.darkpyonix.composerust.protocol.MeasureRecords
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.RendererMeasure
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a sidebar's worth of measuring costs through the measure call, against doing the
 * same work with `TextMeasurer` directly in Kotlin.
 *
 * One sidebar is two hundred labels, and a flexbox asks each of them three things: the
 * narrowest it can be, the widest, and how tall it is at a given width. That is six hundred
 * measurements, asked in one call. The direct side does exactly the same text work, so the
 * difference is what the call itself adds: reading the requests, resolving the style, and
 * writing the answers. No cache on either side, so every measurement is a cold one.
 *
 * The numbers are printed on every run, which is how they come out of CI, and the
 * comparison is asserted.
 */
@OptIn(ExperimentalTestApi::class)
class MeasureSidebarBenchmarkTest {

    private val labels = List(LABELS) { index -> "Sidebar entry ${index + 1}: ${WORDS[index % WORDS.size]}" }

    private fun requests(): MeasureRequests {
        val fonts = listOf(FontRef.Generic(GenericFamily.SansSerif))
        val requests = MeasureRequests()
        for (label in labels) {
            requests.text(label, role = 0, fontSize = 13f, fonts = fonts, constraint = MeasureRecords.CONSTRAINT_MIN_CONTENT)
            requests.text(label, role = 0, fontSize = 13f, fonts = fonts, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT)
            requests.text(label, role = 0, fontSize = 13f, fonts = fonts, width = WIDTH)
        }
        return requests
    }

    /** The same six hundred answers, worked out with Compose's own API and nothing between. */
    private fun direct(density: Density, resolver: FontFamily.Resolver, direction: LayoutDirection): Float {
        val measurer = TextMeasurer(resolver, density, direction, cacheSize = 0)
        val style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight(400), fontFamily = FontFamily.SansSerif)
        var sink = 0f
        for (label in labels) {
            val text = AnnotatedString(label)
            for (minimum in listOf(true, false)) {
                val intrinsics = MultiParagraphIntrinsics(text, style, emptyList(), density, resolver)
                val width = if (minimum) intrinsics.minIntrinsicWidth else intrinsics.maxIntrinsicWidth
                val laidOut = measurer.measure(
                    text,
                    style,
                    constraints = Constraints.fitPrioritizingWidth(0, ceil(width).toInt(), 0, Constraints.Infinity),
                )
                sink += laidOut.size.height + laidOut.firstBaseline + laidOut.lineCount
            }
            val laidOut = measurer.measure(
                text,
                style,
                constraints = Constraints.fitPrioritizingWidth(
                    0,
                    (WIDTH * density.density).roundToInt(),
                    0,
                    Constraints.Infinity,
                ),
            )
            sink += laidOut.size.height + laidOut.firstBaseline + laidOut.lineCount
        }
        return sink
    }

    @Test
    fun nfr9_measure_sidebar_costs_at_most_ten_percent_over_text_measurer() = runComposeUiTest {
        lateinit var density: Density
        lateinit var resolver: FontFamily.Resolver
        lateinit var direction: LayoutDirection
        setContent {
            density = LocalDensity.current
            resolver = LocalFontFamilyResolver.current
            direction = LocalLayoutDirection.current
            ComposeRustContent(rememberComposeRustHost(FakeHostConnection(emptyList())))
        }
        waitForIdle()
        val buffer = requests().encode()
        val count = LABELS * 3
        val results = resultBuffer(count)
        var through = LongArray(0)
        var straight = LongArray(0)
        var sink = 0f
        runOnIdle {
            // Warm both paths once: class loading and the font caches are not what this
            // compares, and both sides would pay them identically.
            repeat(WARMUP) {
                RendererMeasure.measure(buffer, count, results)
                sink += direct(density, resolver, direction)
            }
            through = LongArray(ROUNDS)
            straight = LongArray(ROUNDS)
            // Interleaved, so a slow patch of the machine lands on both sides.
            for (round in 0 until ROUNDS) {
                val started = System.nanoTime()
                val status = RendererMeasure.measure(buffer, count, results)
                through[round] = System.nanoTime() - started
                assertEquals(MeasureRecords.CALL_OK, status)
                val began = System.nanoTime()
                sink += direct(density, resolver, direction)
                straight[round] = System.nanoTime() - began
            }
        }
        assertTrue(sink != 0f)
        through.sort()
        straight.sort()
        val throughMedian = through[ROUNDS / 2]
        val straightMedian = straight[ROUNDS / 2]
        val overhead = throughMedian.toDouble() / straightMedian - 1.0
        println(
            "measure_sidebar: $count measurements, ${"%.3f".format(throughMedian / 1e6)} ms through the " +
                "measure call, ${"%.3f".format(straightMedian / 1e6)} ms with TextMeasurer directly, " +
                "overhead ${"%.1f".format(overhead * 100)}% (median of $ROUNDS, jvm, " +
                "${System.getProperty("os.name")} ${System.getProperty("os.arch")})",
        )
        assertTrue(
            overhead <= 0.10,
            "the measure call costs ${"%.1f".format(overhead * 100)}% more than measuring directly",
        )
    }

    private companion object {
        const val LABELS = 200
        const val WIDTH = 180f
        const val WARMUP = 3
        const val ROUNDS = 15
        val WORDS = listOf(
            "Inbox",
            "Drafts and unsent messages",
            "Archive",
            "A considerably longer label that wraps at the sidebar's width",
            "설정",
            "Projects / compose-rust / renderer",
        )
    }
}
