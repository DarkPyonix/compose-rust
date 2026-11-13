package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.ButtonVariant
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TextAlign
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Button that is a row: a left-aligned label, its own label size, and children in place
 * of the label.
 */
@OptIn(ExperimentalTestApi::class)
class ButtonRowTest {
    private val root = 1
    private val button = 2
    private val label = 3
    private val spacer = 4
    private val count = 5
    private val handler = 77L

    private fun theme() = Mutation.SetTheme(
        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false),
    )

    private fun buttonNode(width: Float?) = buildList {
        add(Mutation.Create(button, WidgetKind.Button))
        if (width != null) add(Mutation.SetModifier(button, 0, ProtocolModifier.Width(width)))
        add(Mutation.SetProp(button, PropertyKind.Variant, PropertyValue.Integer(ButtonVariant.Text.ordinal + 1L)))
        add(Mutation.SetProp(button, PropertyKind.OnClick, PropertyValue.Integer(handler)))
    }

    private fun text(id: Int, value: String) = listOf(
        Mutation.Create(id, WidgetKind.Text),
        Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(value)),
        Mutation.Insert(button, id, if (id == label) 0 else 2),
    )

    private fun childrenButton() = listOf(theme(), Mutation.Create(root, WidgetKind.Box)) +
        buttonNode(300f) +
        text(label, "Files") +
        listOf(
            Mutation.Create(spacer, WidgetKind.Spacer),
            Mutation.SetModifier(spacer, 0, ProtocolModifier.Weight(1f)),
            Mutation.Insert(button, spacer, 1),
        ) +
        text(count, "12") +
        listOf(Mutation.Insert(root, button, 0))

    @Test
    fun fr66_children_lay_out_as_a_row_with_the_count_at_the_right_edge() {
        runComposeUiTest {
            setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(childrenButton()))) }
            waitForIdle()
            val outer = onNodeWithTag(nodeTestTag(button)).getBoundsInRoot()
            val first = onNodeWithTag(nodeTestTag(label), useUnmergedTree = true).getBoundsInRoot()
            val last = onNodeWithTag(nodeTestTag(count), useUnmergedTree = true).getBoundsInRoot()
            assertTrue(first.left - outer.left in 0.dp..32.dp, "label starts at the inner padding: ${first.left - outer.left}")
            assertTrue(outer.right - last.right in 0.dp..32.dp, "count is at the inner right edge: ${outer.right - last.right}")
            val labelMid = (first.top + first.bottom) / 2
            val countMid = (last.top + last.bottom) / 2
            assertTrue((labelMid - countMid).value in -1f..1f, "children share a vertical centre")
        }
    }

    @Test
    fun fr66_a_disabled_button_with_children_draws_and_exposes_them_once() {
        runComposeUiTest {
            val connection = FakeHostConnection(
                childrenButton() + Mutation.SetProp(button, PropertyKind.Enabled, PropertyValue.Bool(false)),
            )
            setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
            waitForIdle()
            for (child in listOf(label, count)) {
                assertEquals(
                    1,
                    onAllNodesWithTag(nodeTestTag(child), useUnmergedTree = true).fetchSemanticsNodes().size,
                    "child $child appears once in the unmerged tree",
                )
            }
            onNodeWithTag(nodeTestTag(button)).performClick()
            waitForIdle()
            assertTrue(
                connection.events.filterIsInstance<HostEvent.Clicked>().isEmpty(),
                "a disabled button delivers no click",
            )
        }
    }

    @Test
    fun fr66_a_click_on_the_whole_button_fires_once() {
        runComposeUiTest {
            val connection = FakeHostConnection(childrenButton())
            setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
            onNodeWithTag(nodeTestTag(button)).performClick()
            waitForIdle()
            assertEquals(
                listOf(HostEvent.Clicked(button, handler)),
                connection.events.filterIsInstance<HostEvent.Clicked>(),
            )
        }
    }

    private fun widthOf(fontSize: Float?, align: TextAlign?): Float {
        var width = 0f
        runComposeUiTest {
            val mutations = buildList {
                add(theme())
                add(Mutation.Create(root, WidgetKind.Box))
                addAll(buttonNode(null))
                add(Mutation.SetProp(button, PropertyKind.Text, PropertyValue.Text("Changes")))
                if (fontSize != null) add(Mutation.SetProp(button, PropertyKind.FontSize, PropertyValue.Float(fontSize)))
                if (align != null) add(Mutation.SetProp(button, PropertyKind.TextAlign, PropertyValue.Integer(align.ordinal + 1L)))
                add(Mutation.Insert(root, button, 0))
            }
            setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations))) }
            waitForIdle()
            val bounds = onNodeWithTag(nodeTestTag(button)).getBoundsInRoot()
            width = bounds.right.value - bounds.left.value
        }
        return width
    }

    @Test
    fun fr66_font_size_sizes_the_label_and_its_absence_changes_nothing() {
        assertTrue(widthOf(30f, TextAlign.Start) > widthOf(null, TextAlign.Start), "a larger FontSize widens the label")
        assertEquals(widthOf(null, null), widthOf(null, TextAlign.Start), "alignment alone does not resize an unconstrained button")
    }

    private fun labelBounds(width: Float, fontSize: Float?, align: TextAlign?): Pair<DpRect, DpRect> {
        lateinit var result: Pair<DpRect, DpRect>
        runComposeUiTest {
            val mutations = buildList {
                add(theme())
                add(Mutation.Create(root, WidgetKind.Box))
                addAll(buttonNode(width))
                add(Mutation.SetProp(button, PropertyKind.Text, PropertyValue.Text("Changes")))
                if (fontSize != null) add(Mutation.SetProp(button, PropertyKind.FontSize, PropertyValue.Float(fontSize)))
                if (align != null) add(Mutation.SetProp(button, PropertyKind.TextAlign, PropertyValue.Integer(align.ordinal + 1L)))
                add(Mutation.Insert(root, button, 0))
            }
            setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations))) }
            waitForIdle()
            result = onNodeWithTag(nodeTestTag(button)).getBoundsInRoot() to
                onNodeWithText("Changes", useUnmergedTree = true).getBoundsInRoot()
        }
        return result
    }

    @Test
    fun fr66_start_alignment_puts_the_label_at_the_inner_padding_and_font_size_sets_its_height() {
        val (outer, start) = labelBounds(300f, 13f, TextAlign.Start)
        val (_, centred) = labelBounds(300f, 13f, null)
        assertTrue(start.left - outer.left in 0.dp..32.dp, "a Start label sits at the inner padding: ${start.left - outer.left}")
        assertTrue(centred.left - outer.left > 60.dp, "an unaligned label stays centred: ${centred.left - outer.left}")
        val (_, small) = labelBounds(300f, 13f, TextAlign.Start)
        val (_, large) = labelBounds(300f, 26f, TextAlign.Start)
        assertTrue(large.bottom - large.top > small.bottom - small.top, "FontSize sets the label size")
    }

    private val reference = 6

    /**
     * The label of a Button with this role and weight, and a Text with the same role and
     * weight, measured in the same frame. A label set at the role draws exactly as a Text
     * of that role does.
     */
    private fun labelAndReference(role: TypeRole?, weight: Long?): Pair<DpRect, DpRect> {
        lateinit var result: Pair<DpRect, DpRect>
        runComposeUiTest {
            val mutations = buildList {
                add(theme())
                add(Mutation.Create(root, WidgetKind.Column))
                addAll(buttonNode(null))
                add(Mutation.SetProp(button, PropertyKind.Text, PropertyValue.Text("Commit")))
                if (role != null) add(Mutation.SetProp(button, PropertyKind.TypeRole, PropertyValue.Integer(role.ordinal + 1L)))
                if (weight != null) add(Mutation.SetProp(button, PropertyKind.FontWeight, PropertyValue.Integer(weight)))
                add(Mutation.Insert(root, button, 0))
                add(Mutation.Create(reference, WidgetKind.Text))
                add(Mutation.SetProp(reference, PropertyKind.Text, PropertyValue.Text("Reference")))
                add(Mutation.SetProp(reference, PropertyKind.TypeRole, PropertyValue.Integer((role ?: TypeRole.Body).ordinal + 1L)))
                if (weight != null) add(Mutation.SetProp(reference, PropertyKind.FontWeight, PropertyValue.Integer(weight)))
                add(Mutation.Insert(root, reference, 1))
            }
            setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations))) }
            waitForIdle()
            result = onNodeWithText("Commit", useUnmergedTree = true).getBoundsInRoot() to
                onNodeWithTag(nodeTestTag(reference), useUnmergedTree = true).getBoundsInRoot()
        }
        return result
    }

    private fun DpRect.height() = bottom - top
    private fun DpRect.width() = right - left

    @Test
    fun fr66_button_type_role_and_weight() {
        val (caption, captionText) = labelAndReference(TypeRole.Caption, null)
        val (title, _) = labelAndReference(TypeRole.Title, null)
        assertEquals(captionText.height(), caption.height(), "a Caption label has the Caption line height")
        assertTrue(title.height() > caption.height(), "the role sets the label's size: ${title.height()} vs ${caption.height()}")

        val (light, _) = labelAndReference(TypeRole.Caption, 100)
        val (heavy, _) = labelAndReference(TypeRole.Caption, 900)
        assertEquals(caption.height(), heavy.height(), "a weight leaves the role's size and line height alone")
        assertTrue(heavy.width() > light.width(), "font_weight overrides the role's weight: ${heavy.width()} vs ${light.width()}")
    }

    @Test
    fun fr66_button_font_size_wins_over_its_type_role() {
        lateinit var bounds: Pair<DpRect, DpRect>
        runComposeUiTest {
            val mutations = buildList {
                add(theme())
                add(Mutation.Create(root, WidgetKind.Column))
                addAll(buttonNode(null))
                add(Mutation.SetProp(button, PropertyKind.Text, PropertyValue.Text("Commit")))
                add(Mutation.SetProp(button, PropertyKind.TypeRole, PropertyValue.Integer(TypeRole.Caption.ordinal + 1L)))
                add(Mutation.SetProp(button, PropertyKind.FontSize, PropertyValue.Float(30f)))
                add(Mutation.Insert(root, button, 0))
            }
            setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations))) }
            waitForIdle()
            bounds = onNodeWithText("Commit", useUnmergedTree = true).getBoundsInRoot() to
                onNodeWithTag(nodeTestTag(button)).getBoundsInRoot()
        }
        val (caption, _) = labelAndReference(TypeRole.Caption, null)
        assertTrue(bounds.first.width() > caption.width(), "an explicit FontSize beats the role's size")
    }
}
