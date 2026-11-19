package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.measureTime
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier

/**
 * A deep tree of unweighted/weighted Columns with FillMaxWidth children must measure in
 * polynomial (not exponential) time as depth grows from 25 to 50 to 100.
 */
@OptIn(ExperimentalTestApi::class)
class DeepMeasureLinearTest {
    private fun buildDeepTree(depth: Int): List<Mutation> = buildList {
        var nextId = 1
        val root = nextId++
        add(Mutation.Create(root, WidgetKind.Column))
        var parent = root
        for (level in 0 until depth) {
            val isRow = level % 2 == 1
            val child = nextId++
            add(Mutation.Create(child, if (isRow) WidgetKind.Row else WidgetKind.Column))
            add(Mutation.SetModifier(child, 0, ProtocolModifier.FillMaxWidth))
            add(Mutation.Insert(parent, child, 0))
            // A weighted sibling so the parent uses WeightedStackPolicy (crossIntrinsic).
            val spacer = nextId++
            add(Mutation.Create(spacer, WidgetKind.Box))
            add(Mutation.SetModifier(spacer, 0, ProtocolModifier.Weight(1f)))
            add(Mutation.Insert(parent, spacer, 1))
            parent = child
        }
        val leaf = nextId++
        add(Mutation.Create(leaf, WidgetKind.Text))
        add(Mutation.SetProp(leaf, PropertyKind.Text, PropertyValue.Text("leaf-$depth")))
        add(Mutation.Insert(parent, leaf, 0))
    }

    private fun measureDepth(depth: Int): Duration {
        val batch = buildDeepTree(depth)
        var elapsed = Duration.ZERO
        runComposeUiTest {
            val frames = FrameRequestSource()
            elapsed = measureTime {
                setContent {
                    CompositionLocalProvider(LocalFrameRequests provides frames) {
                        ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)))
                    }
                }
                waitForIdle()
            }
        }
        return elapsed
    }

    @Test
    fun nfr9_deep_tree_with_fill_width_child_measures_without_exponential_blowup() {
        // Warm up JIT and Compose classloading before timing.
        measureDepth(10)

        val t25 = measureDepth(25)
        val t50 = measureDepth(50)
        val t100 = measureDepth(100)

        assertTrue(
            t100.inWholeMilliseconds < 2_000L,
            "depth 100 tree took ${t100.inWholeMilliseconds} ms (depth 25=${t25.inWholeMilliseconds} ms, depth 50=${t50.inWholeMilliseconds} ms); expected < 2000 ms",
        )
        val ceilingMs = maxOf(t25.inWholeMilliseconds * 16L, 250L)
        assertTrue(
            t100.inWholeMilliseconds <= ceilingMs,
            "depth 100 (${t100.inWholeMilliseconds} ms) grew faster than expected relative to depth 25 (${t25.inWholeMilliseconds} ms, depth 50=${t50.inWholeMilliseconds} ms; ceiling=$ceilingMs ms)",
        )
    }
}
