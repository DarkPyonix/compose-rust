package org.thisisthepy.compose.deepin

import org.thisisthepy.compose.designsystem.ColorRole
import org.thisisthepy.compose.designsystem.DesignSystem
import org.thisisthepy.compose.designsystem.ShapeRole
import org.thisisthepy.compose.designsystem.SpaceRole
import org.thisisthepy.compose.designsystem.TypeRole
import org.thisisthepy.compose.breeze.BreezeDesignSystem
import org.thisisthepy.compose.gnome.GnomeDesignSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three Linux systems have to be told apart on screen.
 *
 * Adwaita, Breeze and the fallback answer the same roles, and the point of writing three
 * of them is that a person looking at a screenshot can say which one drew it. Values that
 * quietly converged would turn the three into one grey theme with three names, and
 * nothing else in the build would notice.
 */
class LinuxDesignSystemsDifferTest {

    private val light = listOf(GnomeDesignSystem.Light, BreezeDesignSystem.Light, DeepinDesignSystem.Light)
    private val dark = listOf(GnomeDesignSystem.Dark, BreezeDesignSystem.Dark, DeepinDesignSystem.Dark)

    @Test
    fun fr14_the_three_linux_systems_report_different_identities() {
        assertEquals(3, light.map { it.id }.toSet().size)
        assertEquals(3, dark.map { it.id }.toSet().size)
    }

    /**
     * The roles whose value is settled by contrast rather than chosen by the language.
     *
     * The ink on an accent fill, on a second accent and on an error fill is white in
     * almost every light palette, because those three fills are saturated and white is
     * what reads on them. Three systems agreeing there is arithmetic, not convergence, and
     * counting it crowds out the roles where agreement really would mean one theme with
     * three names.
     */
    private val forcedByContrast = setOf(ColorRole.OnPrimary, ColorRole.OnSecondary, ColorRole.OnError)

    @Test
    fun fr14_no_two_linux_systems_share_a_palette() {
        val chosen = ColorRole.entries.filterNot { it in forcedByContrast }
        for (scheme in listOf(light, dark)) {
            forEachPair(scheme) { a, b ->
                // Counted over the roles a language actually chooses. Counting all of them
                // was the bug this line used to have: the three inks below were excluded
                // in a list that was then never used, so every ink that reads white in two
                // systems was charged against the budget for real collisions, and a role
                // added later moved the count without anything having converged.
                val same = chosen.filter { a.color(it) == b.color(it) }
                // Three, not zero. Even among the chosen roles some agreement is
                // arithmetic: white is the right ink over more than one saturated fill,
                // and two systems that agree on which fills take white ink are not one
                // theme. Two that agreed on a fill would be.
                assertTrue(
                    same.size <= 3,
                    "${a.id} and ${b.id} give the same answer for ${same.size} of the " +
                        "${chosen.size} colour roles they actually choose, so they would " +
                        "look like one theme. The roles they agree on are " +
                        same.joinToString { it.name },
                )
            }
        }
    }

    @Test
    fun fr14_no_two_linux_systems_share_a_type_scale() {
        forEachPair(light) { a, b ->
            val shared = TypeRole.entries.count { a.type(it) == b.type(it) }
            assertTrue(shared == 0, "${a.id} and ${b.id} share $shared type roles")
            val bodyGap = a.type(TypeRole.Body).fontSize.value - b.type(TypeRole.Body).fontSize.value
            assertTrue(
                kotlin.math.abs(bodyGap) >= 1f,
                "${a.id} and ${b.id} set body text within a point of each other",
            )
        }
    }

    @Test
    fun fr14_no_two_linux_systems_share_a_shape_scale() {
        forEachPair(light) { a, b ->
            val shared = ShapeRole.entries.count { a.shape(it) == b.shape(it) }
            assertTrue(
                shared <= 2,
                "${a.id} and ${b.id} round $shared of the ${ShapeRole.entries.size} shape " +
                    "roles identically",
            )
        }
    }

    @Test
    fun fr14_no_two_linux_systems_share_a_spacing_scale() {
        forEachPair(light) { a, b ->
            val shared = SpaceRole.entries.count { a.space(it) == b.space(it) }
            assertTrue(
                shared <= 2,
                "${a.id} and ${b.id} use the same gap for $shared of the " +
                    "${SpaceRole.entries.size} spacing roles",
            )
        }
    }

    @Test
    fun fr14_each_linux_system_keeps_its_own_density_and_roundness_order() {
        // Breeze is the dense one and the fallback is the roomy one, at every rung that
        // is not zero. Adwaita sits between them.
        for (role in SpaceRole.entries.filter { it != SpaceRole.None }) {
            val breeze = BreezeDesignSystem.Light.space(role).value
            val gnome = GnomeDesignSystem.Light.space(role).value
            val fallback = DeepinDesignSystem.Light.space(role).value
            assertTrue(
                breeze <= gnome && gnome <= fallback,
                "at $role the gaps are Breeze $breeze, GNOME $gnome, fallback $fallback, " +
                    "which is not the intended dense to roomy order",
            )
        }
    }

    @Test
    fun fr14_the_three_linux_systems_differ_from_material_on_the_same_roles() {
        // Material 3 is not on this module's classpath, so its published values are
        // written out here: the baseline light surface, its 12dp medium corner and its
        // 14sp body size. A system that matched all three would be Material wearing
        // another name.
        val materialLightSurface = androidx.compose.ui.graphics.Color(0xFFFEF7FF)
        val materialMediumRadius = 12f
        val materialBodySize = 14f
        for (system in light) {
            val matches = listOf(
                system.color(ColorRole.Surface) == materialLightSurface,
                system.space(SpaceRole.Md).value == materialMediumRadius,
                system.type(TypeRole.Body).fontSize.value == materialBodySize,
            ).count { it }
            assertTrue(
                matches < 3,
                "${system.id} matches Material 3 on surface colour, medium spacing and body " +
                    "size at once",
            )
        }
    }

    private fun forEachPair(systems: List<DesignSystem>, body: (DesignSystem, DesignSystem) -> Unit) {
        for (i in systems.indices) {
            for (j in i + 1 until systems.size) {
                body(systems[i], systems[j])
            }
        }
    }
}
