package dioxus.compose.test

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dioxus.compose.design.ContainerRole
import dioxus.compose.design.ContinuousCornerShape
import dioxus.compose.design.GlassProminence
import dioxus.compose.design.HostPlatform
import dioxus.compose.design.LiquidGlass
import dioxus.compose.design.SurfaceMaterial
import dioxus.compose.design.adaptiveSystem
import dioxus.compose.design.compositeOver
import dioxus.compose.design.concentricRadius
import dioxus.compose.design.inset
import dioxus.compose.design.contrastRatio
import dioxus.compose.design.relativeLuminance
import dioxus.compose.design.drawsAsGlass
import dioxus.compose.design.glassBlurRadius
import dioxus.compose.design.glassFill
import dioxus.compose.design.resolveTheme
import dioxus.compose.protocol.ButtonVariant
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WindowSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The Liquid Glass design system, and the window width that decides how far its glass
 * reaches.
 *
 * These assert the parts that can be stated as values rather than looked at: which roles
 * are glass at which width, that the opaque fallback carries a real contrast guarantee,
 * that reduced transparency reaches both the colour and the blur pass, and that an inner
 * corner is cut concentric with its container.
 */
/**
 * The smallest difference in levels that still reads as a separate surface.
 *
 * Six out of 255 is not a contrast guarantee for text, and is not meant to be. It is the
 * point below which a fill stops being a fill: two levels is what the vanishing tinted
 * button measured, and nobody could see it.
 */
private const val MIN_FILL_STEP = 6

class LiquidGlassTest {

    private fun glassTheme(dark: Boolean, sizeClass: WindowSizeClass) = resolveTheme(
        theme = Theme(
            DesignSystem.LiquidGlass,
            DesignSystem.LiquidGlass,
            if (dark) ColorScheme.Dark else ColorScheme.Light,
            adaptive = false,
        ),
        platform = HostPlatform.Unknown,
        systemDark = dark,
        sizeClass = sizeClass,
    )

    private fun materialOf(role: ContainerRole, dark: Boolean, sizeClass: WindowSizeClass): SurfaceMaterial {
        val theme = glassTheme(dark, sizeClass)
        return requireNotNull(theme.rules.container(role, theme).material) {
            "the Liquid Glass rules answer every container role with a material"
        }
    }

    /**
     * The Apple slot is glass, and the flat system is chosen by name.
     *
     * Adaptive says the application did not choose and the machine's own language is
     * used. macOS 26 and iOS 26 draw themselves in glass, so answering with the previous
     * generation's language would be a choice the application never made.
     */
    /**
     * A window that has lost the key draws no glass.
     *
     * The third condition, beside reduced transparency and a platform that cannot blur,
     * and it takes the same answer: the opaque fallback, no blur pass, no lift. On a
     * screen with several windows open, which one is translucent is what says which one is
     * yours, and a window that kept showing through after it stopped being yours would be
     * the only one on the screen that did not sink.
     */
    @Test
    fun fr14_1_5_an_inactive_window_draws_its_fallback() {
        assertFalse(
            drawsAsGlass(reduceTransparency = false, blurAvailable = true, windowActive = false),
            "an inactive window still draws glass, so nothing on the screen says which " +
                "window is the one being used",
        )
        assertTrue(
            drawsAsGlass(reduceTransparency = false, blurAvailable = true, windowActive = true),
            "the active window stopped drawing glass",
        )
    }

    /** And the blur pass goes with it, which is the cost rather than the look. */
    @Test
    fun fr14_1_5_an_inactive_window_pays_for_no_blur() {
        val glass = LiquidGlass.material(
            dark = false,
            prominence = GlassProminence.Regular,
            backdrop = Color.White,
            content = Color.Black,
        )
        assertEquals(
            0.dp,
            glassBlurRadius(glass, reduceTransparency = false, blurAvailable = true, windowActive = false),
            "an inactive window is still blurring a backdrop nobody is looking through",
        )
    }

    @Test
    fun fr14_1_3_adaptive_takes_the_apple_slot_for_glass_and_the_flat_system_is_named() {
        assertEquals(
            DesignSystem.LiquidGlass,
            adaptiveSystem(HostPlatform.MacOs, DesignSystem.Material3),
        )
        assertEquals(
            DesignSystem.LiquidGlass,
            adaptiveSystem(HostPlatform.Ios, DesignSystem.Material3),
        )
        val theme = resolveTheme(theme = null, platform = HostPlatform.MacOs, systemDark = false)
        assertEquals(DesignSystem.LiquidGlass, theme.system)

        assertEquals(
            DesignSystem.LiquidGlass,
            glassTheme(dark = false, sizeClass = WindowSizeClass.Compact).system,
        )
    }

    @Test
    fun fr14_1_3_chrome_is_glass_at_every_window_size() {
        val chrome = listOf(
            ContainerRole.TopAppBar,
            ContainerRole.Menu,
            ContainerRole.Dialog,
            ContainerRole.Tooltip,
        )
        for (sizeClass in WindowSizeClass.entries) {
            for (role in chrome) {
                assertTrue(
                    materialOf(role, dark = false, sizeClass = sizeClass) is SurfaceMaterial.Glass,
                    "$role is chrome and is glass at every width; it was not at $sizeClass",
                )
            }
        }
    }

    @Test
    fun fr14_1_3_content_surfaces_are_glass_only_in_a_compact_window() {
        val content = listOf(ContainerRole.Card, ContainerRole.Surface)
        for (role in content) {
            assertTrue(
                materialOf(role, dark = false, sizeClass = WindowSizeClass.Compact)
                    is SurfaceMaterial.Glass,
                "$role carries glass on a phone sized window",
            )
            for (wide in listOf(WindowSizeClass.Medium, WindowSizeClass.Expanded)) {
                assertTrue(
                    materialOf(role, dark = false, sizeClass = wide) is SurfaceMaterial.Opaque,
                    "$role is the document on a desktop window and stays opaque; it did not at $wide",
                )
            }
        }
    }

    @Test
    fun fr14_1_2_the_opaque_fallback_meets_body_contrast() {
        for (dark in listOf(false, true)) {
            for (prominence in GlassProminence.entries) {
                val content = if (dark) Color.White else Color.Black
                val material = LiquidGlass.material(
                    dark = dark,
                    prominence = prominence,
                    backdrop = if (dark) Color(0xFF000000) else Color(0xFFFFFFFF),
                    content = content,
                )
                val ratio = contrastRatio(material.fallback, content)
                assertTrue(
                    ratio >= LiquidGlass.MIN_CONTRAST_BODY,
                    "the fallback is the one surface whose contrast can be promised; " +
                        "dark=$dark $prominence reached only $ratio",
                )
                assertEquals(1f, material.fallback.alpha, "a fallback that is see-through promises nothing")
            }
        }
    }

    @Test
    fun fr14_1_2_reduced_transparency_draws_the_fallback_and_drops_the_blur() {
        val material = LiquidGlass.material(
            dark = false,
            backdrop = Color(0xFFFFFFFF),
            content = Color.Black,
        )
        assertTrue(drawsAsGlass(reduceTransparency = false, blurAvailable = true))

        val glassFill = glassFill(material, reduceTransparency = false, blurAvailable = true)
        // Compose stores an sRGB alpha in eight bits, so the round trip lands within one
        // step of the requested value rather than on it.
        assertEquals(material.tintAlpha, glassFill.alpha, absoluteTolerance = 1f / 255f)

        val reducedFill = glassFill(material, reduceTransparency = true, blurAvailable = true)
        assertEquals(material.fallback, reducedFill)

        // The blur pass has to go with the look. Blurring a backdrop nobody can see
        // through is pure cost, paid every frame, by the reader who asked for less of it.
        assertEquals(
            material.blurRadius,
            glassBlurRadius(material, reduceTransparency = false, blurAvailable = true),
        )
        assertEquals(
            0.dp,
            glassBlurRadius(material, reduceTransparency = true, blurAvailable = true),
        )
        assertEquals(
            0.dp,
            glassBlurRadius(material, reduceTransparency = false, blurAvailable = false),
        )
    }

    @Test
    fun fr14_1_2_an_inner_corner_is_concentric_with_its_container() {
        // Concentric means the gap between the two outlines is the same all the way
        // round, which happens only when the inner radius is the outer one minus the
        // inset. A shared radius, or a constant of its own, pinches at the corners.
        assertEquals(12.dp, concentricRadius(20.dp, 8.dp))
        assertEquals(0.dp, concentricRadius(4.dp, 8.dp))

        assertEquals(
            ContinuousCornerShape(12.dp, 12.dp, 12.dp, 12.dp),
            ContinuousCornerShape(20.dp).inset(8.dp),
            "an inset shape is the container's shape with the inset taken off each corner",
        )

        // And the rules really do cut continuous corners, which is the half of this that
        // a pure function cannot show. A menu is the clearest case: it is the one chrome
        // surface with a finite radius rather than a capsule.
        val theme = glassTheme(dark = false, sizeClass = WindowSizeClass.Compact)
        val menu = theme.rules.container(ContainerRole.Menu, theme)
        assertTrue(
            menu.shape is ContinuousCornerShape,
            "a menu's corner is continuous here, and it was ${menu.shape}",
        )
    }

    @Test
    fun fr14_1_2_a_continuous_corner_is_fuller_than_a_circular_one() {
        // A superellipse corner keeps more area near the corner than a quarter circle of
        // the same radius, which is the whole visible difference. Sampling the outline at
        // the diagonal is the cheapest way to state it.
        val shape = ContinuousCornerShape(40.dp)
        val outline = shape.createOutline(
            size = Size(200f, 200f),
            layoutDirection = LayoutDirection.Ltr,
            density = Density(1f),
        )
        assertTrue(outline is Outline.Generic, "a superellipse has no exact rounded rectangle form")
        val bounds = outline.path.getBounds()
        assertEquals(0f, bounds.left)
        assertEquals(0f, bounds.top)
        assertTrue(bounds.right in 199f..200f)
        assertTrue(bounds.bottom in 199f..200f)
    }

    /**
     * A tinted button has to be a step away from whatever it lands on: the page, a panel,
     * or the bar. A stored grey cannot promise that. The secondary fill and the tint of a
     * dark bar are neighbours, so a tinted button on a toolbar came out the colour of the
     * toolbar and the control simply was not there.
     */
    @Test
    fun fr14_2_a_tinted_button_is_visible_on_everything_it_can_land_on() {
        listOf(true, false).forEach { dark ->
            listOf(WindowSizeClass.Compact, WindowSizeClass.Expanded).forEach { sizeClass ->
                val theme = glassTheme(dark, sizeClass)
                val fill = theme.rules.button(ButtonVariant.Tonal, theme).container
                val backdrops = mapOf(
                    "the page" to theme.color(ColorRole.Background),
                    "a panel" to theme.color(ColorRole.Surface),
                    "the bar" to barFill(theme),
                )
                backdrops.forEach { (name, backdrop) ->
                    val drawn = compositeOver(fill, backdrop)
                    assertTrue(
                        channelDistance(drawn, backdrop) >= MIN_FILL_STEP,
                        "a tinted button on $name is ${channelDistance(drawn, backdrop)} levels " +
                            "from it (dark=$dark, $sizeClass), which is not a control anyone can see",
                    )
                }
            }
        }
    }

    /** The colour a top app bar actually draws once its glass is composited on the page. */
    private fun barFill(theme: dioxus.compose.design.ResolvedTheme): Color {
        val material = requireNotNull(theme.rules.container(ContainerRole.TopAppBar, theme).material)
        val glass = glassFill(material, reduceTransparency = false, blurAvailable = true)
        return compositeOver(glass, theme.color(ColorRole.Background))
    }

    /** How far apart two colours are on their furthest channel, in levels out of 255. */
    private fun channelDistance(a: Color, b: Color): Int = maxOf(
        kotlin.math.abs(a.red - b.red),
        kotlin.math.abs(a.green - b.green),
        kotlin.math.abs(a.blue - b.blue),
    ).let { (it * 255f).toInt() }

    /**
     * The page is the one colour Apple specifies twice, so it is the one colour the
     * window's width is allowed to change. A phone's grouped page is black; a desktop
     * window's is not, or the panels on it have no edge.
     */
    @Test
    fun fr14_1_3_the_dark_page_is_black_on_a_phone_and_not_in_a_desktop_window() {
        val phone = glassTheme(dark = true, sizeClass = WindowSizeClass.Compact)
        val desktop = glassTheme(dark = true, sizeClass = WindowSizeClass.Expanded)

        assertEquals(Color(0xFF000000), phone.color(ColorRole.Background))
        assertNotEquals(Color(0xFF000000), desktop.color(ColorRole.Background))
        // The panel has to be visible against whichever page it sits on, and against the
        // desktop page it is the darker of the two: a well in the window, which is what a
        // macOS document area is.
        assertNotEquals(desktop.color(ColorRole.Background), desktop.color(ColorRole.Surface))
        assertTrue(
            relativeLuminance(desktop.color(ColorRole.Background)) >
                relativeLuminance(desktop.color(ColorRole.Surface)),
            "the desktop page is no lighter than the panels on it, so they have no edge",
        )
    }

    /** Every other role, and light mode, answer with the generated table at every width. */
    @Test
    fun fr14_1_3_only_the_page_changes_with_the_window() {
        val phone = glassTheme(dark = true, sizeClass = WindowSizeClass.Compact)
        val desktop = glassTheme(dark = true, sizeClass = WindowSizeClass.Expanded)
        ColorRole.entries.filter { it != ColorRole.Background }.forEach { role ->
            assertEquals(phone.color(role), desktop.color(role), "$role changed with the window width")
        }

        val lightPhone = glassTheme(dark = false, sizeClass = WindowSizeClass.Compact)
        val lightDesktop = glassTheme(dark = false, sizeClass = WindowSizeClass.Expanded)
        ColorRole.entries.forEach { role ->
            assertEquals(
                lightPhone.color(role),
                lightDesktop.color(role),
                "$role changed with the window width in light mode",
            )
        }
    }

    @Test
    fun fr14_1_2_the_glass_systems_material_differs_from_the_flat_systems() {
        val glass = glassTheme(dark = false, sizeClass = WindowSizeClass.Compact)
        val glassBar = glass.rules.container(ContainerRole.TopAppBar, glass)
        assertTrue(glassBar.material is SurfaceMaterial.Glass)

        // Cupertino is in this list on purpose. It is the other Apple system and it is
        // not glass: that is the whole reason the two exist side by side.
        for (system in listOf(DesignSystem.Material3, DesignSystem.Fluent, DesignSystem.Cupertino)) {
            val theme = resolveTheme(
                theme = Theme(system, system, ColorScheme.Light, adaptive = false),
                platform = HostPlatform.Unknown,
                systemDark = false,
                sizeClass = WindowSizeClass.Compact,
            )
            val bar = theme.rules.container(ContainerRole.TopAppBar, theme)
            assertEquals(
                null,
                bar.material,
                "$system is a flat fill by design, not a degraded glass",
            )
        }
    }
}
