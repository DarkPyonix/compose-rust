package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.design.iconGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** IconRole.Settings draws an 8-tooth gear with an inner hub rather than a 4-tick crosshair. */
class SettingsIconGeometryTest {
    @Test
    fun settings_icon_has_eight_gear_teeth_and_concentric_hub() {
        val geom = iconGeometry(IconRole.Settings)
        assertEquals(8, geom.strokes.size, "gear needs 4 cardinal + 4 diagonal teeth")
        val diagonalCount = geom.strokes.count { seg ->
            seg.size == 2 && seg[0].x != seg[1].x && seg[0].y != seg[1].y
        }
        assertEquals(4, diagonalCount, "gear needs 4 diagonal teeth")
        assertEquals(2, geom.dots.size, "gear needs outer ring and inner hub")
        assertTrue(geom.dots.all { !it.filled })
    }
}
