package dioxus.compose.test

import dioxus.compose.ui.platform.applyIcon
import java.awt.Image
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which windows get the application's picture.
 *
 * A window with none wears the toolkit's, which on Windows is the Java coffee cup. The
 * renderer used to set it on the one window it created and at the moment the asset
 * arrived, so a dialog that became a platform window of its own, and anything opened
 * before the asset landed, kept the cup. Both are fixed by choosing windows rather than
 * one window, which is what these pin.
 */
class WindowIconTest {

    private class FakeWindow(var images: List<Image> = emptyList())

    private fun apply(windows: List<FakeWindow>, ours: List<Image>) =
        applyIcon(windows, ours, { it.images }, { window, next -> window.images = next })

    private val picture: Image = object : Image() {
        override fun getWidth(observer: java.awt.image.ImageObserver?) = 1
        override fun getHeight(observer: java.awt.image.ImageObserver?) = 1
        override fun getSource() = error("not drawn in a test")
        override fun getGraphics() = error("not drawn in a test")
        override fun getProperty(name: String?, observer: java.awt.image.ImageObserver?) = null
    }

    @Test
    fun fr19_3_every_window_gets_the_application_picture() {
        val windows = listOf(FakeWindow(), FakeWindow(), FakeWindow())
        assertEquals(3, apply(windows, listOf(picture)), "a second window is still a window")
        for (window in windows) {
            assertEquals(listOf(picture), window.images)
        }
    }

    @Test
    fun fr19_3_a_window_that_already_carries_it_is_left_alone() {
        val windows = listOf(FakeWindow(listOf(picture)), FakeWindow())
        assertEquals(1, apply(windows, listOf(picture)), "only the one without it changed")
    }

    @Test
    fun fr19_3_saying_nothing_leaves_the_toolkit_picture() {
        // Zero means the application did not name one, and the toolkit's own is then the
        // right answer rather than a blank window.
        val windows = listOf(FakeWindow())
        assertEquals(0, apply(windows, emptyList()))
        assertEquals(emptyList(), windows.single().images)
    }
}
