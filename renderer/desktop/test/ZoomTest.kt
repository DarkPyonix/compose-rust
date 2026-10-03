@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, ExperimentalTestApi::class)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.LocalZoom
import dev.darkpyonix.composerust.runtime.Zoom
import dev.darkpyonix.composerust.runtime.ZoomLevelStore
import dev.darkpyonix.composerust.runtime.ZoomShortcut
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.WindowEvent
import dev.darkpyonix.composerust.ui.platform.WindowFrames
import dev.darkpyonix.composerust.ui.platform.WindowMeasurement
import dev.darkpyonix.composerust.ui.platform.macZoomShortcut
import dev.darkpyonix.composerust.ui.platform.receive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How large a window draws: the system's text size times the application's zoom level.
 *
 * Every desktop window builds its scene's Density through one [Zoom]: `Density(density ×
 * 1.2^level, fontScale = os)`. What is checked here is that shared part, driven by a fake of
 * the system's setting and of the settings store: the Density the scene gets, the report the
 * Host gets, the keys, and the level kept between runs. Where each platform finds the
 * system's number is checked in its own tests.
 */
class ZoomTest {

    /** What the system says, as a test sets it. */
    private var system = 1f

    /** A settings store that lives as long as the test. */
    private class MemoryStore(var saved: Int? = null) : ZoomLevelStore {
        override fun load(): Int? = saved
        override fun save(level: Int) {
            saved = level
        }
    }

    private fun close(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 1e-4f, "$what: expected $expected, was $actual")

    @Test
    fun fr43_native_widgets_get_the_display_scale_times_the_app_zoom_and_the_os_font_scale() {
        system = 1.25f
        val zoom = Zoom({ system }, MemoryStore())
        assertTrue(zoom.setLevel(2))

        close(1.44f, zoom.app, "app")
        close(1.25f * 1.44f, zoom.k, "k")
        val density = zoom.density(2f)
        close(2f * 1.44f, density.density, "density")
        assertEquals(1.25f, density.fontScale)
    }

    @Test
    fun fr43_a_refresh_says_whether_anything_the_density_is_made_of_changed() {
        val zoom = Zoom({ system })
        assertFalse(zoom.refresh(), "nothing changed, so there is nothing to draw")

        system = 1.5f
        assertTrue(zoom.refresh(), "a changed text size is a frame to draw")
        assertEquals(1.5f, zoom.os)
        assertFalse(zoom.refresh(), "the same answer twice is one change")

        zoom.step(ZoomShortcut.In)
        assertTrue(zoom.refresh(), "a changed level is a frame to draw")
        assertFalse(zoom.refresh())
    }

    @Test
    fun fr43_a_text_size_that_is_not_a_size_is_read_as_the_default() {
        for (nonsense in listOf(0f, -1f, Float.NaN)) {
            system = nonsense
            val zoom = Zoom({ system })
            assertEquals(1f, zoom.os, "a reported $nonsense")
        }
        system = 100f
        assertEquals(4f, Zoom({ system }).os, "a misread setting is held to what text can be drawn at")
    }

    /**
     * Two presses take the level to 2 and the factor to `os × 1.44`, and the next run starts
     * there.
     */
    @Test
    fun fr43_app_zoom_steps_and_persists() {
        system = 1.25f
        val store = MemoryStore()
        val zoom = Zoom({ system }, store)

        assertTrue(zoom.takeShortcut(ZoomShortcut.In, consumedByApplication = false))
        assertTrue(zoom.takeShortcut(ZoomShortcut.In, consumedByApplication = false))

        assertEquals(2, zoom.level)
        close(1.25f * 1.44f, zoom.k, "k")
        assertEquals(2, store.saved)

        val nextRun = Zoom({ system }, store)
        assertEquals(2, nextRun.level, "the next run starts at the level the reader left")

        nextRun.step(ZoomShortcut.Reset)
        assertEquals(0, nextRun.level)
        assertEquals(0, store.saved)
    }

    @Test
    fun fr43_the_level_stays_between_minus_eight_and_eight() {
        val zoom = Zoom(store = MemoryStore(saved = 40))
        assertEquals(8, zoom.level, "a saved level out of range is held to the range")
        assertFalse(zoom.step(ZoomShortcut.In))
        repeat(20) { zoom.step(ZoomShortcut.Out) }
        assertEquals(-8, zoom.level)
        close(1f / 1.2f.let { it * it * it * it * it * it * it * it }, zoom.app, "app at -8")
    }

    /** A key the application consumed is the application's, and not also a zoom. */
    @Test
    fun fr43_consumed_zoom_key_does_not_zoom() {
        val zoom = Zoom(store = MemoryStore())
        assertFalse(zoom.takeShortcut(ZoomShortcut.In, consumedByApplication = true))
        assertEquals(0, zoom.level)
        assertFalse(zoom.takeShortcut(null, consumedByApplication = false), "not a shortcut")
        assertEquals(0, zoom.level)
    }

    /**
     * The same, through a real scene: a window offers the key to the scene first, and only a
     * key nothing in it consumed zooms.
     */
    @Test
    fun fr43_consumed_zoom_key_does_not_zoom_through_the_scene() {
        for (consumes in listOf(true, false)) {
            val zoom = Zoom(store = MemoryStore())
            val scene = CanvasLayersComposeScene(density = zoom.density(1f), size = IntSize(200, 100))
            scene.setContent {
                val focus = remember { FocusRequester() }
                Box(
                    Modifier.size(50.dp)
                        .focusRequester(focus)
                        .onKeyEvent { consumes }
                        .focusable(),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
            try {
                render(scene)
                render(scene)
                // Command and the equals key, as the AppKit window records it.
                val press = WindowEvent(
                    kind = WindowEvent.KEY_DOWN,
                    x = 0f,
                    y = 0f,
                    buttons = 0,
                    modifiers = 1 shl 20,
                    keyCode = 0x18,
                    codePoint = '='.code,
                    text = "",
                )
                val consumed = scene.receive(press)
                assertEquals(consumes, consumed, "the scene's answer")
                zoom.takeShortcut(
                    macZoomShortcut(press.codePoint, press.keyCode, press.modifiers.toLong()),
                    consumed,
                )
                assertEquals(if (consumes) 0 else 1, zoom.level, "consumed = $consumes")
            } finally {
                scene.close()
            }
        }
    }

    /**
     * A frame hands the scene the display's scale times the zoom and the system's text size
     * together, and a change while the window is open is in the next frame.
     */
    @Test
    fun fr43_os_text_size_reaches_the_frame_without_a_restart() {
        system = 1.5f
        val zoom = Zoom({ system })
        val handed = mutableListOf<Density>()
        val frames = WindowFrames({ WindowMeasurement(800, 600, 2f) }, zoom) { _, density ->
            handed.add(density)
        }

        assertTrue(frames.draw())
        system = 2f
        assertTrue(zoom.refresh())
        assertTrue(frames.draw())

        assertEquals(listOf(Density(2f, 1.5f), Density(2f, 2f)), handed)
    }

    /**
     * Native text is laid out at the system's size, and again when the size changes with the
     * window open. Through a real scene of the kind every window draws, given its Density the
     * way a window's frame gives it.
     */
    @Test
    fun fr43_os_text_size_reaches_native_text_while_the_window_is_open() {
        val zoom = Zoom({ system })
        val scene = CanvasLayersComposeScene(density = zoom.density(1f), size = IntSize(400, 300))
        var seen = Density(0f)
        var lineHeight = 0
        scene.setContent {
            seen = LocalDensity.current
            BasicText(
                "Aa",
                style = TextStyle(fontSize = 20.sp),
                modifier = Modifier.onSizeChanged { lineHeight = it.height },
            )
        }
        val frames = WindowFrames({ WindowMeasurement(400, 300, 1f) }, zoom) { size, density ->
            if (scene.size != size || scene.density != density) {
                scene.density = density
                scene.size = size
            }
            render(scene)
        }
        try {
            assertTrue(frames.draw())
            assertEquals(1f, seen.fontScale)
            val defaultHeight = lineHeight
            assertTrue(defaultHeight > 0, "the text was laid out")

            // The reader asks the system for text twice the size, with the window open.
            system = 2f
            assertTrue(zoom.refresh())
            assertTrue(frames.draw())

            assertEquals(2f, seen.fontScale, "the composition sees the system's text size")
            assertEquals(1f, seen.density, "the display's scale is unchanged by it")
            assertTrue(
                lineHeight >= defaultHeight * 3 / 2,
                "a line of text at twice the size is taller: $defaultHeight then $lineHeight",
            )
        } finally {
            scene.close()
        }
    }

    /**
     * The Host is told the zoom once at start, and once more for each change: the system's
     * text size, and a level from the keys.
     */
    @Test
    fun fr43_os_text_size_reaches_the_host_once_per_change() = runComposeUiTest {
        system = 1f
        val zoom = Zoom({ system }, MemoryStore())
        val connection = FakeHostConnection()
        setContent {
            CompositionLocalProvider(LocalZoom provides zoom) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
        fun reports() = connection.events.filterIsInstance<HostEvent.ZoomChanged>()
        assertEquals(listOf(HostEvent.ZoomChanged(0, 0, 1f, 1f, 0)), reports())

        system = 1.25f
        runOnIdle { zoom.refresh() }
        waitForIdle()
        assertEquals(2, reports().size, "one report for the change")
        assertEquals(HostEvent.ZoomChanged(0, 0, 1.25f, 1.25f, 0), reports().last())

        runOnIdle { zoom.step(ZoomShortcut.In) }
        waitForIdle()
        val last = reports().last()
        assertEquals(3, reports().size)
        assertEquals(1, last.level)
        assertEquals(1.25f, last.os)
        close(1.25f * 1.2f, last.k, "k")
    }

    /** A level the application put on its window record is applied, kept and reported. */
    @Test
    fun fr43_a_level_on_the_window_record_is_applied() = runComposeUiTest {
        val store = MemoryStore()
        val zoom = Zoom(store = store)
        val connection = FakeHostConnection(
            listOf(
                Mutation.SetWindow(
                    Window(
                        chrome = Chrome.Modern,
                        titleBar = TitleBar.Normal,
                        title = "",
                        icon = 0,
                        width = 0,
                        height = 0,
                        minWidth = 0,
                        minHeight = 0,
                        resizable = true,
                        zoomLevel = 3,
                    ),
                ),
            ),
        )
        setContent {
            CompositionLocalProvider(LocalZoom provides zoom) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        assertEquals(3, zoom.level)
        assertEquals(3, store.saved)
        assertEquals(3, connection.events.filterIsInstance<HostEvent.ZoomChanged>().last().level)
    }

    /**
     * Inside a platform's own Compose host there is no window to hold a zoom, so the content
     * makes one: the platform's font scale is the system's text size, and the level scales
     * the density.
     */
    @Test
    fun fr43_content_without_a_window_zoom_takes_the_platform_font_scale() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.SetWindow(
                    Window(Chrome.Modern, TitleBar.Normal, "", 0, 0, 0, 0, 0, true, zoomLevel = 2),
                ),
            ),
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        val report = connection.events.filterIsInstance<HostEvent.ZoomChanged>().last()
        assertEquals(2, report.level)
        assertEquals(1.3f, report.os)
        close(1.3f * 1.44f, report.k, "k")
    }

    private fun render(scene: CanvasLayersComposeScene) {
        val size = scene.size ?: IntSize(1, 1)
        org.jetbrains.skia.Surface.makeRasterN32Premul(size.width, size.height).use { surface ->
            scene.render(surface.canvas.asComposeCanvas(), 0L)
        }
    }
}
