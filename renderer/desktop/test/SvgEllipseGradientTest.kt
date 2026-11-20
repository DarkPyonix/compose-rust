package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.node.decodeVectorAsset
import org.jetbrains.skia.Color
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGLength
import org.jetbrains.skia.svg.SVGLengthUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Skia's SVG DOM paints an `<ellipse>` filled with an `objectBoundingBox` gradient solid black.
 * Verify that `decodeVectorAsset` preserves the gradient on desktop/Skia.
 */
class SvgEllipseGradientTest {
    @Test
    fun ellipse_with_object_bounding_box_gradient_renders_gradient_not_black() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100">
              <defs>
                <linearGradient id="g" x1="0" y1="0" x2="1" y2="0" gradientUnits="objectBoundingBox">
                  <stop offset="0%" stop-color="#ff0000"/>
                  <stop offset="100%" stop-color="#0000ff"/>
                </linearGradient>
              </defs>
              <ellipse cx="50" cy="50" rx="40" ry="30" fill="url(#g)"/>
            </svg>
        """.trimIndent().encodeToByteArray()

        val dom = assertNotNull(decodeVectorAsset(svg))
        dom.root?.let { root ->
            root.width = SVGLength(100f, SVGLengthUnit.PX)
            root.height = SVGLength(100f, SVGLengthUnit.PX)
        }
        val surface = Surface.makeRasterN32Premul(100, 100)
        try {
            surface.canvas.clear(Color.WHITE)
            dom.render(surface.canvas)
            val bitmap = org.jetbrains.skia.Bitmap()
            bitmap.allocN32Pixels(100, 100)
            assertTrue(surface.readPixels(bitmap, 0, 0))

            val left = bitmap.getColor(25, 50)
            val right = bitmap.getColor(75, 50)
            val leftR = (left ushr 16) and 0xff
            val leftB = left and 0xff
            val rightR = (right ushr 16) and 0xff
            val rightB = right and 0xff

            assertTrue(
                leftR > 140 && leftR > leftB,
                "left side of ellipse should be reddish (got #${(left and 0xffffff).toString(16)})",
            )
            assertTrue(
                rightB > 140 && rightB > rightR,
                "right side of ellipse should be bluish (got #${(right and 0xffffff).toString(16)})",
            )
        } finally {
            surface.close()
        }
    }
}
