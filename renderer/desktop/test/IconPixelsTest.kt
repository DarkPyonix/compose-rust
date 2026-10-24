package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.darkpyonix.composerust.ui.platform.iconPixels
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The application's picture, as the pixels a window of our own is given.
 *
 * Every window without a toolkit takes the icon as red, green, blue and alpha bytes with
 * the colour already multiplied by the alpha, so the order of the bytes is the thing to
 * get right: a swapped red and blue is an icon in the wrong colours on every desktop.
 */
class IconPixelsTest {

    @Test
    fun nfr14_an_icon_reaches_the_window_as_rgba_bytes() {
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(2, 1))
        // Opaque blue.
        bitmap.erase(0xFF0000FF.toInt())
        val (pixels, width, height) = iconPixels(bitmap.asComposeImageBitmap())!!
        assertEquals(2, width)
        assertEquals(1, height)
        assertEquals(8, pixels.size)
        assertEquals(listOf(0, 0, 255, 255), pixels.take(4).map { it.toInt() and 0xFF })
    }
}
