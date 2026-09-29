package dioxus.compose.test

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.IconRole
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getBoundsInRoot

/**
 * A button wearing an icon role.
 *
 * Every reference design the samples are built from puts icon buttons in its bars: the
 * Windows calculator's hamburger and history, the iOS note's back, undo, share and done,
 * Gemini's compose and collapse. Application code could not place one. The `Icon` widget
 * takes an asset id the Host registered, and the role enum only reached the tree through
 * a navigation destination, so the samples filled their bars with words and the words
 * pushed each other off a narrow window.
 *
 * What is asserted here is that the role reaches the screen, that it is the design
 * system's own artwork rather than one drawing shared by all of them, and that a button
 * with no word on it still has a name.
 */
@OptIn(ExperimentalTestApi::class)
class IconButtonTest {
    private val root = 1
    private val button = 2

    private fun batch(
        system: DesignSystem,
        icon: IconRole?,
        text: String,
    ) = buildList {
        add(Mutation.SetTheme(Theme(system, system, ColorScheme.Light, false)))
        add(Mutation.Create(root, WidgetKind.Box))
        add(Mutation.Create(button, WidgetKind.Button))
        add(Mutation.SetProp(button, PropertyKind.Text, PropertyValue.Text(text)))
        if (icon != null) {
            add(
                Mutation.SetProp(
                    button,
                    PropertyKind.Icon,
                    PropertyValue.Integer(icon.ordinal + 1L),
                ),
            )
        }
        add(Mutation.Insert(root, button, 0))
    }

    private fun <T> drawn(
        system: DesignSystem,
        icon: IconRole?,
        text: String,
        read: (SemanticsNodeInteraction) -> T,
    ): T {
        var answer: T? = null
        runComposeUiTest {
            setContent {
                DioxusContent(rememberDioxusHost(FakeHostConnection(batch(system, icon, text))))
            }
            waitForIdle()
            answer = read(onNodeWithTag(nodeTestTag(button)))
        }
        @Suppress("UNCHECKED_CAST")
        return answer as T
    }

    /** Every pixel of the button, which is what a drawn glyph changes and a missing one does not. */
    private fun pixels(system: DesignSystem, icon: IconRole?, text: String): List<Int> =
        drawn(system, icon, text) { node ->
            val image = node.captureToImage().toAwtImage()
            buildList {
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) add(image.getRGB(x, y))
                }
            }
        }

    /**
     * A button with a glyph and no word is not a wide pill with a dot in the middle.
     *
     * The horizontal padding is there to keep a word off the button's ends. A button with
     * no word has nothing to keep off them, and using it anyway makes every icon control
     * half as wide again as it needs to be: two of them in a floating group came out at
     * 120 points against the reference's 82, and the composer's leading key pushed its
     * placeholder 59 points in where the reference puts it 24.
     *
     * Square enough is the assertion rather than exactly square, because the glyph's own
     * optical size and the minimum height a control may be are both the design system's
     * and neither is obliged to agree with the other.
     */
    @Test
    fun fr16_4_a_button_with_no_word_is_not_a_wide_pill() {
        for (system in DesignSystem.entries) {
            val size = drawn(system, IconRole.Add, "") { it.getBoundsInRoot() }
            val width = size.right - size.left
            val height = size.bottom - size.top
            assertTrue(
                width <= height + SQUARE_ENOUGH,
                "$system draws a wordless button ${width} by ${height}, which is a pill " +
                    "with a dot in the middle",
            )
        }
    }

    @Test
    fun fr16_4_a_button_with_an_icon_draws_its_system_s_own_glyph() {
        val byDesignSystem = DesignSystem.entries.associateWith { system ->
            val withGlyph = pixels(system, IconRole.Search, text = "")
            assertNotEquals(
                pixels(system, icon = null, text = ""),
                withGlyph,
                "under $system a button asked for an icon drew nothing",
            )
            withGlyph
        }
        val systems = DesignSystem.entries
        for (i in systems.indices) {
            for (j in i + 1 until systems.size) {
                assertNotEquals(
                    byDesignSystem[systems[i]],
                    byDesignSystem[systems[j]],
                    "${systems[i]} and ${systems[j]} draw the same icon button, so the " +
                        "glyph is one drawing rather than each system's own",
                )
            }
        }
    }

    @Test
    fun fr16_4_a_button_with_an_icon_and_a_word_draws_both() {
        val both = pixels(DesignSystem.Material3, IconRole.Search, text = "Search")
        val wordOnly = pixels(DesignSystem.Material3, icon = null, text = "Search")
        val glyphOnly = pixels(DesignSystem.Material3, IconRole.Search, text = "")
        assertNotEquals(both, wordOnly, "the glyph went missing when a word was beside it")
        assertNotEquals(both, glyphOnly, "the word went missing when a glyph was beside it")
        assertTrue(
            both.size > wordOnly.size && both.size > glyphOnly.size,
            "a button holding both is no wider than one holding either",
        )
    }

    @Test
    fun fr16_4_a_button_with_no_word_still_has_a_name() {
        drawn(DesignSystem.Material3, IconRole.Search, text = "") { node ->
            node.assertContentDescriptionEquals(IconRole.Search.name)
        }
    }

    private companion object {
        /** How much wider than tall a wordless button may be before it is a pill. */
        val SQUARE_ENOUGH = 8.dp
    }
}
