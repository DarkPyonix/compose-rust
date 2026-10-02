package dioxus.compose.cupertino

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dioxus.compose.ButtonVariant
import dioxus.compose.ColorRole
import dioxus.compose.DesignSystem
import dioxus.compose.DesignSystemId
import dioxus.compose.ShapeRole
import dioxus.compose.SpaceRole
import dioxus.compose.TypeRole
import dioxus.compose.breeze.BreezeDesignSystem
import dioxus.compose.deepin.DeepinDesignSystem
import dioxus.compose.fluent.FluentDesignSystem
import dioxus.compose.gnome.GnomeDesignSystem
import dioxus.compose.liquidglass.contrastRatio
import dioxus.compose.material3.Material3DesignSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * All six systems, asked the same questions side by side.
 *
 * The other cross system tests cover a pair and a trio: Material against Fluent, and the
 * three Linux systems against each other. Neither of them ever asks Cupertino anything,
 * so until this existed a design system could have collapsed onto another one's values in
 * five of the fifteen pairings and every test in the project would still have passed.
 *
 * What is asserted here is that no two systems answer a whole family of roles the same
 * way. It is deliberately not "no two systems ever agree on any single role", because
 * they legitimately do and should: a square corner is a square corner, white is the only
 * sensible label on half of these accent colours, and Material and Deepin both happen to
 * round a medium container at twelve. Those coincidences are not the failure mode. The
 * failure mode is a system that is another system with the numbers nudged, and a system
 * that answers an entire ladder identically to its neighbour is exactly that.
 */
class AllDesignSystemsDifferTest {

    private fun systems(dark: Boolean): List<DesignSystem> = listOf(
        if (dark) Material3DesignSystem.Dark else Material3DesignSystem.Light,
        if (dark) CupertinoDesignSystem.Dark else CupertinoDesignSystem.Light,
        if (dark) FluentDesignSystem.Dark else FluentDesignSystem.Light,
        GnomeDesignSystem.of(dark),
        BreezeDesignSystem.of(dark),
        DeepinDesignSystem.of(dark),
    )

    private fun eachPair(dark: Boolean, body: (DesignSystem, DesignSystem) -> Unit) {
        val all = systems(dark)
        for (i in all.indices) {
            for (j in i + 1 until all.size) body(all[i], all[j])
        }
    }

    private fun bothSchemes(body: (DesignSystem, DesignSystem) -> Unit) {
        for (dark in listOf(false, true)) eachPair(dark, body)
    }

    @Test
    fun fr14_there_are_six_systems_and_each_reports_its_own_identity() {
        for (dark in listOf(false, true)) {
            val ids = systems(dark).map { it.id }
            assertEquals(DesignSystemId.entries.size, ids.size)
            assertEquals(ids.size, ids.toSet().size, "two systems report the same id: $ids")
            assertEquals(DesignSystemId.entries.toSet(), ids.toSet())
        }
    }

    @Test
    fun fr14_every_system_resolves_every_role_in_both_schemes() {
        for (dark in listOf(false, true)) {
            for (system in systems(dark)) {
                assertEquals(dark, system.isDark, "${system.id} disagrees about its scheme")
                for (role in ColorRole.entries) {
                    assertNotEquals(
                        Color.Unspecified,
                        system.color(role),
                        "${system.id} has no answer for $role",
                    )
                }
                for (role in TypeRole.entries) {
                    assertTrue(
                        system.type(role).fontSize.value > 0f,
                        "${system.id} sets $role at no size",
                    )
                }
                for (role in ShapeRole.entries) system.shape(role)
                for (role in SpaceRole.entries) {
                    assertTrue(system.space(role).value >= 0f, "${system.id} pads $role negatively")
                }
                for (variant in ButtonVariant.entries) system.button(variant)
                assertTrue(system.motion.pressMillis > 0, "${system.id} presses instantly")
            }
        }
    }

    @Test
    fun fr14_a_panel_lifts_off_the_page_in_every_system() {
        // `SurfaceContainer` is the one role a panel can be made of, so it has to be
        // visible against the page it sits on. `Surface` cannot carry that promise:
        // several systems give it the same value as the page on purpose, and a panel
        // painted with it is drawn full size, in the right colour, and cannot be seen.
        //
        // Three systems answered this role with their own `Surface` when it was added,
        // and two of those were within twenty parts of their own page.
        for (dark in listOf(false, true)) {
            for (system in systems(dark)) {
                val panel = system.color(ColorRole.SurfaceContainer)
                val page = system.color(ColorRole.Background)
                val apart = (
                    abs(panel.red - page.red) +
                        abs(panel.green - page.green) +
                        abs(panel.blue - page.blue)
                    ) * 255f
                assertTrue(
                    apart >= 24f,
                    "${system.id} ${if (dark) "dark" else "light"}: a panel and the page " +
                        "it sits on are $apart apart, so the panel is invisible",
                )
                // The accent containers answer the same question for a tinted panel. A
                // tint nobody can see is a panel that is not there, and these are the
                // fills a screen made of coloured tiles is built out of.
                for (role in listOf(
                    ColorRole.PrimaryContainer,
                    ColorRole.SecondaryContainer,
                    ColorRole.TertiaryContainer,
                )) {
                    val tint = system.color(role)
                    val tintApart = (
                        abs(tint.red - page.red) +
                            abs(tint.green - page.green) +
                            abs(tint.blue - page.blue)
                        ) * 255f
                    assertTrue(
                        tintApart >= 24f,
                        "${system.id} ${if (dark) "dark" else "light"}: $role and the page " +
                            "are $tintApart apart, so a tinted panel is invisible",
                    )
                }
            }
        }
    }

    /**
     * Every `On*` role is readable on the role it names, in all six systems.
     *
     * Only Cupertino was checked before, so the other five could put unreadable ink on
     * their own fills and nothing here would say so. Breeze shipped white on its
     * "positive" teal until the port to the running path replaced it with dark ink, and
     * that pairing was never measured on this side at all.
     *
     * The thresholds are the ones the Cupertino test explains: reading surfaces carry
     * body text and are held to 4.5, accent fills carry short control labels and are
     * held to the 3.0 that Apple's own systemBlue and systemRed land near.
     */
    @Test
    fun fr14_every_on_role_is_readable_on_its_pair_in_every_system() {
        // `SurfaceContainer` has no ink of its own: a panel filled with it holds the
        // page's reading ink, and that is the promise a caller relies on.
        val reading = listOf(
            ColorRole.Surface to ColorRole.OnSurface,
            ColorRole.SurfaceVariant to ColorRole.OnSurfaceVariant,
            ColorRole.Background to ColorRole.OnBackground,
            ColorRole.SurfaceContainer to ColorRole.OnSurface,
            // The accent containers exist so a paragraph can land on a tinted panel, not
            // just a word, so they are held to the reading bound rather than to the looser
            // bound their accents keep.
            ColorRole.PrimaryContainer to ColorRole.OnPrimaryContainer,
            ColorRole.SecondaryContainer to ColorRole.OnSecondaryContainer,
            ColorRole.TertiaryContainer to ColorRole.OnTertiaryContainer,
        )
        val accent = listOf(
            ColorRole.Primary to ColorRole.OnPrimary,
            ColorRole.Secondary to ColorRole.OnSecondary,
            ColorRole.Tertiary to ColorRole.OnTertiary,
            ColorRole.Error to ColorRole.OnError,
        )
        for (dark in listOf(false, true)) {
            for (system in systems(dark)) {
                for ((container, content) in reading) {
                    val ratio = contrastRatio(system.color(container), system.color(content))
                    assertTrue(
                        ratio >= 4.5f,
                        "${system.id} ${if (dark) "dark" else "light"}: $content on " +
                            "$container reads at $ratio, below the 4.5 body minimum",
                    )
                }
                for ((container, content) in accent) {
                    val ratio = contrastRatio(system.color(container), system.color(content))
                    assertTrue(
                        ratio >= 3f,
                        "${system.id} ${if (dark) "dark" else "light"}: $content on " +
                            "$container reads at $ratio, below the 3.0 control label minimum",
                    )
                }
            }
        }
    }

    @Test
    fun fr14_no_two_systems_share_an_accent() {
        // The accent is the single most identity bearing colour a system has: it is the
        // one a reader names when asked what the screen looks like. Unlike the label and
        // outline roles, there is no excuse for two systems landing on the same one.
        bothSchemes { a, b ->
            assertNotEquals(
                a.color(ColorRole.Primary),
                b.color(ColorRole.Primary),
                "${a.id} and ${b.id} use the same accent",
            )
        }
    }

    @Test
    fun fr13_no_two_systems_share_a_colour_table() {
        bothSchemes { a, b ->
            assertNotEquals(
                ColorRole.entries.map(a::color),
                ColorRole.entries.map(b::color),
                "${a.id} and ${b.id} are one palette under two names",
            )
        }
    }

    @Test
    fun fr13_no_two_systems_share_a_type_ladder() {
        bothSchemes { a, b ->
            assertNotEquals(
                TypeRole.entries.map(a::type),
                TypeRole.entries.map(b::type),
                "${a.id} and ${b.id} set every step of the ladder identically",
            )
        }
    }

    @Test
    fun fr13_no_two_systems_share_a_corner_ladder() {
        bothSchemes { a, b ->
            assertNotEquals(
                ShapeRole.entries.map(a::shape),
                ShapeRole.entries.map(b::shape),
                "${a.id} and ${b.id} round every step identically",
            )
        }
    }

    @Test
    fun fr13_no_two_systems_share_a_spacing_ladder() {
        bothSchemes { a, b ->
            assertNotEquals(
                SpaceRole.entries.map(a::space),
                SpaceRole.entries.map(b::space),
                "${a.id} and ${b.id} have the same density at every step",
            )
        }
    }

    @Test
    fun fr14_no_two_systems_paint_the_same_set_of_buttons() {
        bothSchemes { a, b ->
            assertNotEquals(
                ButtonVariant.entries.map(a::button),
                ButtonVariant.entries.map(b::button),
                "${a.id} and ${b.id} draw all four button variants the same way",
            )
        }
    }

    @Test
    fun fr14_no_two_systems_raise_a_surface_the_same_way() {
        val base = Color(0xFF808080)
        bothSchemes { a, b ->
            assertNotEquals(
                listOf(2, 8, 24).map { a.elevation(it.dp, base) },
                listOf(2, 8, 24).map { b.elevation(it.dp, base) },
                "${a.id} and ${b.id} treat height identically, so depth reads the same",
            )
        }
    }

    @Test
    fun fr14_only_cupertino_answers_with_a_material_rather_than_a_fill() {
        // Glass is Cupertino's design language, not a richer option the others declined.
        // A Material 3 card that came back as glass would be wrong rather than fancier,
        // which is why this is asserted in both directions.
        for (dark in listOf(false, true)) {
            for (system in systems(dark)) {
                val glassRoles = ColorRole.entries.filter {
                    system.material(it) is dioxus.compose.SurfaceMaterial.Glass
                }
                if (system.id == DesignSystemId.Cupertino) {
                    assertTrue(
                        glassRoles.isNotEmpty(),
                        "Cupertino stopped answering with Liquid Glass anywhere",
                    )
                } else {
                    assertTrue(
                        glassRoles.isEmpty(),
                        "${system.id} answers $glassRoles with glass, which is not its language",
                    )
                }
            }
        }
    }

    @Test
    fun fr14_material_is_the_only_system_that_ripples() {
        // Ripple is a Material behaviour. Every other system here dims, tints or
        // recolours a stroke instead, and a ripple appearing in one of them would be a
        // visible mistake rather than a detail.
        for (dark in listOf(false, true)) {
            for (system in systems(dark)) {
                val ripples = ButtonVariant.entries.any { system.button(it).ripple }
                assertEquals(
                    system.id == DesignSystemId.Material3,
                    ripples,
                    "${system.id} ripples: ${system.id == DesignSystemId.Material3} expected",
                )
            }
        }
    }
}

/**
 * The three background roles have to be telling apart on screen, in every system.
 *
 * A chat bubble filled with SurfaceVariant on a Background page vanished entirely under
 * Cupertino, because the two colours were three parts in 255 apart. Nothing failed: the
 * bubble was drawn, with the right colour, and it was invisible. Layering is the whole
 * point of having three roles, so any pair of them collapsing is a bug even though each
 * value on its own looks reasonable in a palette file.
 */
class LayeringIsVisibleTest {

    private fun systems(dark: Boolean): List<Pair<String, DesignSystem>> = listOf(
        "Material3" to if (dark) Material3DesignSystem.Dark else Material3DesignSystem.Light,
        "Cupertino" to if (dark) CupertinoDesignSystem.Dark else CupertinoDesignSystem.Light,
        "Fluent" to if (dark) FluentDesignSystem.Dark else FluentDesignSystem.Light,
        "GNOME" to GnomeDesignSystem.of(dark),
        "Breeze" to BreezeDesignSystem.of(dark),
        "Deepin" to DeepinDesignSystem.of(dark),
    )

    /** Summed channel distance in eight bit terms, which is enough to catch a collapse. */
    private fun distance(first: Color, second: Color): Int {
        fun channels(color: Color) = listOf(color.red, color.green, color.blue)
        return channels(first).zip(channels(second))
            .sumOf { (a, b) -> (kotlin.math.abs(a - b) * 255f).toInt() }
    }

    /**
     * Background and Surface are deliberately not compared. Material 3 gives them the same
     * value on purpose and expresses depth through tonal containers and elevation instead,
     * so requiring them to differ would be requiring Material 3 to stop being Material 3.
     * SurfaceVariant is the role a filled thing is given when it has to read as a thing,
     * and it is the one that has to hold its own against both.
     */
    @Test
    fun fr14_surface_variant_is_visible_against_the_page_and_the_surface() {
        for (dark in listOf(false, true)) {
            for ((name, system) in systems(dark)) {
                for (under in listOf(ColorRole.Background, ColorRole.Surface)) {
                    val apart = distance(
                        system.color(ColorRole.SurfaceVariant),
                        system.color(under),
                    )
                    assertTrue(
                        apart >= 24,
                        "$name ${if (dark) "dark" else "light"}: SurfaceVariant and $under " +
                            "are $apart apart, which reads as one flat surface. Anything " +
                            "filled with one on a page of the other disappears.",
                    )
                }
            }
        }
    }
}
