package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A ScrollColumn wired with `OnRangeRequested`, `Value`, `Determinate`, and `Count`
 * scrolls to the requested dp offset and reports `(offsetDp, maxOffsetDp)` back to the Host.
 * One wired with `OnScrollChanged` instead, which is what the Host's `ScrollState` attaches,
 * reports the same place in pixels with the viewport beside it.
 */
@OptIn(ExperimentalTestApi::class)
class ScrollStateTest {

    @Test
    fun fr_62_scroll_state_moves_and_reports_offset_and_max() = runComposeUiTest {
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
            Mutation.Create(1, WidgetKind.ScrollColumn),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(120f, 100f)),
            Mutation.SetProp(1, PropertyKind.OnRangeRequested, PropertyValue.Integer(7L)),
            Mutation.SetProp(1, PropertyKind.Value, PropertyValue.Float(120f)),
            Mutation.SetProp(1, PropertyKind.Determinate, PropertyValue.Bool(false)),
            Mutation.SetProp(1, PropertyKind.Count, PropertyValue.Integer(1L)),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(120f, 300f)),
            Mutation.Insert(1, 2, 0),
        )
        val connection = FakeHostConnection(batch)
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()

        val reports = connection.events.filterIsInstance<HostEvent.RangeRequested>()
        assertTrue(reports.isNotEmpty(), "ScrollColumn reports its offset and max offset")
        val last = reports.last()
        val offsetDp = Float.fromBits(last.start)
        val maxDp = Float.fromBits(last.count)
        assertTrue(abs(offsetDp - 120f) <= 2f, "expected offset ~120 dp, got $offsetDp")
        assertTrue(abs(maxDp - 200f) <= 2f, "expected max offset ~200 dp, got $maxDp")
    }

    /**
     * The properties a `ScrollState` sends, with the handler it attaches: the container moves
     * to the dp asked for and reports, at one pixel to the dp, the offset, the furthest
     * offset and both sides of the viewport. Nothing asked for `RangeRequested`, so none is
     * sent.
     */
    @Test
    fun fr62_scroll_state_container_reports_offset_max_and_viewport_through_scroll_changed() = runComposeUiTest {
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
            Mutation.Create(1, WidgetKind.ScrollColumn),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(120f, 100f)),
            Mutation.SetProp(1, PropertyKind.OnScrollChanged, PropertyValue.Integer(7L)),
            Mutation.SetProp(1, PropertyKind.Value, PropertyValue.Float(120f)),
            Mutation.SetProp(1, PropertyKind.Determinate, PropertyValue.Bool(false)),
            Mutation.SetProp(1, PropertyKind.Count, PropertyValue.Integer(1L)),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(120f, 300f)),
            Mutation.Insert(1, 2, 0),
        )
        val connection = FakeHostConnection(batch)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        val reports = connection.events.filterIsInstance<HostEvent.ScrollChanged>()
        assertTrue(reports.isNotEmpty(), "the ScrollColumn reports where it is")
        val last = reports.last()
        assertTrue(abs(last.yPx - 120.0) <= 2.0, "expected y ~120 px, got ${last.yPx}")
        assertTrue(abs(last.maxYPx - 200.0) <= 2.0, "expected a furthest y of ~200 px, got ${last.maxYPx}")
        assertTrue(abs(last.viewportHeightPx - 100.0) <= 2.0, "expected a viewport ~100 px high, got ${last.viewportHeightPx}")
        assertTrue(abs(last.viewportWidthPx - 120.0) <= 2.0, "expected a viewport ~120 px wide, got ${last.viewportWidthPx}")
        assertTrue(
            connection.events.none { it is HostEvent.RangeRequested },
            "no RangeRequested without its handler",
        )
    }
}
