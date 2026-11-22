package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.ui.platform.OffscreenPreview
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OffscreenPreviewTest {
    private fun sampleScreen(system: DesignSystem, scheme: ColorScheme): List<Mutation> = listOf(
        Mutation.SetTheme(Theme(system, system, scheme, adaptive = false)),
        Mutation.Create(1, WidgetKind.Column),
        Mutation.Create(2, WidgetKind.TopAppBar),
        Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("Preview Screen")),
        Mutation.Create(3, WidgetKind.Text),
        Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("Real widgets rendered offscreen")),
        Mutation.Create(4, WidgetKind.Button),
        Mutation.SetProp(4, PropertyKind.Text, PropertyValue.Text("Primary Action")),
        Mutation.Insert(1, 2, 0),
        Mutation.Insert(1, 3, 1),
        Mutation.Insert(1, 4, 2),
    )

    @Test
    fun offscreen_preview_renders_real_widget_tree_at_1x_and_2x_deterministically() {
        val mutations = sampleScreen(DesignSystem.Material3, ColorScheme.Light)
        val f1a = OffscreenPreview.renderMutationsToRgba(mutations, 390, 844, scale = 1f)
        val f1b = OffscreenPreview.renderMutationsToRgba(mutations, 390, 844, scale = 1f)
        assertEquals(390, f1a.width)
        assertEquals(844, f1a.height)
        assertEquals(390 * 844 * 4, f1a.pixels.size)
        assertContentEquals(f1a.pixels, f1b.pixels, "repeated offscreen renders must be byte-identical")

        // Verify real widgets were drawn (not a uniform single-colour fill).
        val bgR = f1a.pixels[f1a.pixels.size - 4]
        val bgG = f1a.pixels[f1a.pixels.size - 3]
        val bgB = f1a.pixels[f1a.pixels.size - 2]
        var nonBg = 0
        for (i in 0 until f1a.pixels.size step 4) {
            if (f1a.pixels[i] != bgR || f1a.pixels[i + 1] != bgG || f1a.pixels[i + 2] != bgB) {
                nonBg++
            }
        }
        assertTrue(nonBg > 200, "expected real widget pixels drawn, got only $nonBg non-background pixels")

        val f2 = OffscreenPreview.renderMutationsToRgba(mutations, 390, 844, scale = 2f)
        assertEquals(780, f2.width)
        assertEquals(1688, f2.height)
    }

    @Test
    fun offscreen_preview_switches_scheme_and_design_system() {
        val light = OffscreenPreview.renderMutationsToRgba(
            sampleScreen(DesignSystem.Material3, ColorScheme.Light),
            390,
            844,
            scale = 1f,
        )
        val dark = OffscreenPreview.renderMutationsToRgba(
            sampleScreen(DesignSystem.Material3, ColorScheme.Dark),
            390,
            844,
            scale = 1f,
        )
        val cupertinoDark = OffscreenPreview.renderMutationsToRgba(
            sampleScreen(DesignSystem.Cupertino, ColorScheme.Dark),
            390,
            844,
            scale = 1f,
        )
        assertFalse(light.pixels.contentEquals(dark.pixels), "light and dark renders must differ")
        assertFalse(dark.pixels.contentEquals(cupertinoDark.pixels), "Material3 and Cupertino renders must differ")
    }
}
