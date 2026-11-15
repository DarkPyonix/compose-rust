package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.LocalDisplayScale
import dev.darkpyonix.composerust.runtime.LocalZoom
import dev.darkpyonix.composerust.runtime.Zoom
import dev.darkpyonix.composerust.runtime.ZoomShortcut
import dev.darkpyonix.composerust.runtime.displayScale
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.runtime.zoomedDensity
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How many device pixels one dp of a window covers, told to the Host. The test stands in
 * for the screen with the Density the content is composed in, which is where every platform's
 * window puts its screen's scale.
 */
@OptIn(ExperimentalTestApi::class)
class DensityChangedTest {
    private fun page() = FakeHostConnection(
        listOf(
            Mutation.Create(1, WidgetKind.Column),
            Mutation.Create(2, WidgetKind.Text),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("density")),
            Mutation.Insert(1, 2, 0),
        ),
    )

    private fun FakeHostConnection.densities(): List<HostEvent.DensityChanged> =
        events.filterIsInstance<HostEvent.DensityChanged>()

    /**
     * The current density goes to the Host once, before the first frame is drawn, addressed
     * to the Host itself for the one window of a single window application.
     */
    @Test
    fun fr43_density_reported_at_start() = runComposeUiTest {
        val connection = page()
        var reportedAtFirstDraw: Int? = null
        setContent {
            Box(
                Modifier.drawBehind {
                    if (reportedAtFirstDraw == null) reportedAtFirstDraw = connection.densities().size
                },
            ) {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    ComposeRustContent(rememberComposeRustHost(connection))
                }
            }
        }
        waitForIdle()
        assertEquals(1, reportedAtFirstDraw, "the density is told before the first frame")
        assertEquals(listOf(HostEvent.DensityChanged(0, 0L, 2f)), connection.densities())
    }

    /**
     * A window that comes to be on a screen of another scale reports the new density once,
     * and going back is one more. Moving a real window between two monitors is checked by
     * hand; here the Density the content is composed in changes, which is what that move
     * does to it.
     */
    @Test
    fun fr43_density_reported_when_window_changes_screen() = runComposeUiTest {
        val connection = page()
        var screen by mutableStateOf(2f)
        setContent {
            val scale = screen
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
        assertEquals(listOf(2f), connection.densities().map { it.scale })

        screen = 1.25f
        waitForIdle()
        assertEquals(listOf(2f, 1.25f), connection.densities().map { it.scale }, "one event for the move")

        screen = 2f
        waitForIdle()
        assertEquals(listOf(2f, 1.25f, 2f), connection.densities().map { it.scale })
        assertTrue(connection.densities().all { it.nodeId == 0 && it.handlerId == 0L })
    }

    /**
     * Nothing makes the same density go again: not a recomposition, not the same value set
     * again, and not the system's text size, which changes the Density's font scale and
     * leaves its pixels per dp alone.
     */
    @Test
    fun fr43_density_is_not_repeated_while_it_stays_the_same() = runComposeUiTest {
        val connection = page()
        var screen by mutableStateOf(1.5f)
        var fontScale by mutableStateOf(1f)
        setContent {
            val density = Density(screen, fontScale)
            CompositionLocalProvider(LocalDensity provides density) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
        assertEquals(listOf(1.5f), connection.densities().map { it.scale })

        fontScale = 1.3f
        waitForIdle()
        fontScale = 1f
        waitForIdle()
        screen = 1.5f
        waitForIdle()
        assertEquals(
            listOf(1.5f),
            connection.densities().map { it.scale },
            "the same density went to the Host twice",
        )
    }

    /**
     * The zoom is not the density. A window that holds a zoom draws at the screen's scale
     * times the application's level, and a step of the level reaches the Host as a zoom
     * report and no density report: what is told is the screen's own scale, as the window
     * provides it.
     */
    @Test
    fun fr43_density_does_not_follow_the_zoom() = runComposeUiTest {
        val connection = page()
        val zoom = Zoom()
        setContent {
            CompositionLocalProvider(
                LocalDensity provides zoomedDensity(2f, zoom.app, 1f),
                LocalZoom provides zoom,
                LocalDisplayScale provides 2f,
            ) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
        assertEquals(listOf(2f), connection.densities().map { it.scale })

        runOnIdle { zoom.step(ZoomShortcut.In) }
        waitForIdle()
        runOnIdle { zoom.step(ZoomShortcut.In) }
        waitForIdle()
        assertEquals(2, connection.events.filterIsInstance<HostEvent.ZoomChanged>().last().level)
        assertEquals(listOf(2f), connection.densities().map { it.scale }, "a zoom step was reported as a density")
    }

    /**
     * Where nothing provides the screen's scale, it is the Density with the application's
     * share of the zoom divided out, and with no zoom at all it is the Density's own.
     */
    @Test
    fun fr43_display_scale_is_the_density_without_the_zoom() {
        assertEquals(2f, displayScale(Density(2f, 1.3f), null))
        val zoom = Zoom()
        assertEquals(1.25f, displayScale(zoomedDensity(1.25f, zoom.app, 1f), zoom))
        zoom.step(ZoomShortcut.In)
        val worked = displayScale(zoomedDensity(1.25f, zoom.app, 1f), zoom)
        assertTrue(kotlin.math.abs(worked - 1.25f) < 1e-5f, "worked out $worked from a screen at 1.25")
    }

    /**
     * One window of a multi-window application reports under its own `Window` node, the way
     * its size and its zoom are reported.
     */
    @Test
    fun fr43_density_of_a_window_names_its_window_node() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(WINDOW, WidgetKind.Window),
                Mutation.Insert(0, WINDOW, -1),
                Mutation.Create(LABEL, WidgetKind.Text),
                Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("hello")),
                Mutation.Insert(WINDOW, LABEL, -1),
            ),
        )
        var screen by mutableStateOf(1f)
        setContent {
            val scale = screen
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                ComposeRustContent(rememberComposeRustHost(connection), windowNodeId = WINDOW)
            }
        }
        waitForIdle()
        assertEquals(listOf(HostEvent.DensityChanged(WINDOW, 0L, 1f)), connection.densities())

        screen = 2f
        waitForIdle()
        assertEquals(
            listOf(HostEvent.DensityChanged(WINDOW, 0L, 1f), HostEvent.DensityChanged(WINDOW, 0L, 2f)),
            connection.densities(),
        )
    }

    private companion object {
        const val WINDOW = 20
        const val LABEL = 21
    }
}
