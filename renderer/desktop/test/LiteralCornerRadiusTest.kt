package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.ui.resolvedShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FR-50.2 (#378): a radius written as a number is a circular arc of that many pixels at 1x. */
class LiteralCornerRadiusTest {
    @Test
    fun fr_50_2_a_literal_corner_radius_is_circular_in_every_design_system() {
        DesignSystem.entries.forEach { system ->
            val theme = resolveTheme(
                Theme(system, system, ColorScheme.Light, false),
                HostPlatform.MacOs,
                systemDark = false,
            )
            listOf(8f, 12f, 14f, 24f).forEach { radius ->
                val shape = listOf<ProtocolModifier>(ProtocolModifier.Shape(radius, radius, radius, radius))
                    .resolvedShape(theme)
                val outline = shape.createOutline(Size(64f, 64f), LayoutDirection.Ltr, Density(1f))
                assertTrue(outline is Outline.Rounded, "$system $radius")
                val corner = (outline as Outline.Rounded).roundRect
                assertEquals(radius, corner.topLeftCornerRadius.x, "$system $radius")
                assertEquals(radius, corner.bottomRightCornerRadius.y, "$system $radius")
            }
        }
    }
}
