package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.design.pickPlatformUiFamily
import dev.darkpyonix.composerust.design.platformUiFamilyNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which installed family the machine's interface text is set in, asked of a fake machine.
 *
 * A fake because the question is about Windows and the tests run on macOS and Linux: the
 * font manager is replaced by a table of what each family name answers, and the faces each
 * family has, written down the way DirectWrite lists them on Windows 11.
 */
class PlatformUiFamilyTest {

    /** One face of an installed family: its weight, and whether that weight is a file of its own. */
    private class Face(val weight: Int, val ownFile: Boolean)

    /** An installed family: the name the font manager answers with, and its faces. */
    private class Family(val answers: String, val faces: List<Face>)

    /**
     * Windows 11. Segoe UI Variable is one variable file whose weights are positions on a
     * `wght` axis (300 to 700) with an `opsz` axis beside it, listed as three families of
     * named instances. Segoe UI is the static family Windows 10 had and Windows 11 still
     * ships, a file per weight.
     */
    private val windows11 = run {
        val variable = listOf(300, 350, 400, 600, 700).map { Face(it, ownFile = false) }
        mapOf(
            "Segoe UI Variable Text" to Family("Segoe UI Variable Text", variable),
            "Segoe UI Variable Display" to Family("Segoe UI Variable Display", variable),
            "Segoe UI Variable Small" to Family("Segoe UI Variable Small", variable),
            "Segoe UI" to Family("Segoe UI", listOf(300, 350, 400, 600, 700, 900).map { Face(it, ownFile = true) }),
        )
    }

    /** Windows 10: no Segoe UI Variable. Asking for it answers the default family instead. */
    private val windows10 = mapOf(
        "Segoe UI" to Family("Segoe UI", listOf(300, 350, 400, 600, 700, 900).map { Face(it, ownFile = true) }),
    )

    private fun pick(machine: Map<String, Family>, osName: String, default: String = "Segoe UI"): String? =
        pickPlatformUiFamily(platformUiFamilyNames(osName)) { name -> machine[name]?.answers ?: default }

    @Test
    fun fr13_2_windows_interface_text_is_set_in_a_family_with_a_file_for_each_weight() {
        val picked = pick(windows11, "Windows 11")
        assertEquals("Segoe UI", picked)
        val semibold = windows11.getValue(picked!!).faces.single { it.weight == 600 }
        assertTrue(
            semibold.ownFile,
            "weight 600 has to be a face of its own: a weight that is a position on a variable face drew regular at 28px",
        )
    }

    @Test
    fun fr13_2_windows_10_is_set_in_segoe_ui() {
        assertEquals("Segoe UI", pick(windows10, "Windows 10"))
    }

    @Test
    fun fr13_2_a_name_answered_with_a_substitute_is_passed_over() {
        val machine = mapOf("Noto Sans" to Family("Noto Sans", emptyList()))
        val picked = pickPlatformUiFamily(listOf("Adwaita Sans", "Cantarell", "Noto Sans")) { name ->
            machine[name]?.answers ?: "DejaVu Sans"
        }
        assertEquals("Noto Sans", picked)
    }

    @Test
    fun fr13_2_the_macos_system_family_is_taken_under_its_hidden_name() {
        // CoreText answers the dotted name with the family's real name, so the name is
        // trusted rather than compared.
        val picked = pickPlatformUiFamily(platformUiFamilyNames("Mac OS X")) { name ->
            if (name == ".AppleSystemUIFont") "System Font" else "Helvetica"
        }
        assertEquals(".AppleSystemUIFont", picked)
    }

    @Test
    fun fr13_2_nothing_installed_leaves_the_toolkit_default() {
        assertEquals(null, pickPlatformUiFamily(platformUiFamilyNames("Windows 11")) { null })
    }
}
