package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.foundation.code.CODE_EDITOR_FIELD_TAG
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SemanticsRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.describe
import dev.darkpyonix.composerust.ui.platform.describeSubtree
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// What a live read of Ember's editor window found (compose-rust #1092, #1093): the roles a Host
// declared on pressable rows and tabs were lost to the button every pressable container is,
// named regions read as text, buttons held a second button of their own name, and the code
// editor was a one-line field named by its whole document.

private const val ROW = 1
private const val CHILD = 2
private const val LEAF = 3
private const val HANDLER = 41L
private const val EXPANDED_CLOSED = 1 shl 4
private const val SELECTED_ON = 2 shl 2
private const val CLICK_ACTION = 1 shl 11

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.show(mutations: List<Mutation>) {
    setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations))) }
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.element(id: Int): AccessibleElement =
    assertNotNull(onNodeWithTag(nodeTestTag(id)).fetchSemanticsNode().describe(-1, 1f), "node $id was left out")

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.window(): List<AccessibleElement> = onRoot().fetchSemanticsNode().describeSubtree(1f)

/** A pressable Box the Host named [label] and declared [role] with [state], as an rsx div with a role and an onclick. */
private fun pressable(id: Int, label: String, role: SemanticsRole, state: Int) = listOf(
    Mutation.Create(id, WidgetKind.Box),
    Mutation.SetProp(id, PropertyKind.AccessibleName, PropertyValue.Text(label)),
    Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(HANDLER)),
    Mutation.SetModifier(id, 0, ProtocolModifier.Semantics(role, state or CLICK_ACTION)),
)

@OptIn(ExperimentalTestApi::class)
class PlatformRoleTreeTest {

    @Test
    fun fr71_a_pressable_tree_row_is_a_row_of_the_outline_not_a_button() {
        runComposeUiTest {
            show(pressable(ROW, "src", SemanticsRole.TreeItem, EXPANDED_CLOSED))
            val row = element(ROW)
            assertEquals(ElementRole.OUTLINE_ROW, row.role)
            assertEquals("src", row.label)
            // Closed is a state the platform names, not text read out as the value.
            assertEquals(AccessibleState.EXPANDABLE, row.states and (AccessibleState.EXPANDABLE or AccessibleState.EXPANDED))
            assertNull(row.value, "the role and state were read as text")
            assertTrue(row.actions and AccessibleAction.PRESS != 0, "the row can no longer be pressed")
        }
    }

    @Test
    fun fr71_a_pressable_tab_is_a_tab_not_a_button() {
        runComposeUiTest {
            show(pressable(ROW, "README.md", SemanticsRole.Tab, SELECTED_ON))
            val tab = element(ROW)
            assertEquals(ElementRole.TAB, tab.role)
            assertTrue(tab.states and AccessibleState.SELECTED != 0, "the current tab is not selected")
            assertNull(tab.value)
        }
    }

    @Test
    fun fr71_a_code_editor_is_a_text_area_not_named_by_its_document() {
        val document = "fn main() {\n    println!(\"hi\");\n}\n"
        runComposeUiTest {
            show(
                listOf(
                    Mutation.Create(ROW, WidgetKind.CodeEditor),
                    Mutation.SetModifier(ROW, 0, ProtocolModifier.Size(400f, 200f)),
                    Mutation.SetProp(ROW, PropertyKind.Text, PropertyValue.Text(document)),
                ),
            )
            val field = assertNotNull(onNodeWithTag(CODE_EDITOR_FIELD_TAG).fetchSemanticsNode().describe(-1, 1f))
            assertEquals(ElementRole.FIELD, field.role)
            assertTrue(field.states and AccessibleState.MULTI_LINE != 0, "the editor is a one-line field")
            assertEquals("", field.label, "the editor is named by its document")
            assertEquals(document, field.value)
        }
    }

    @Test
    fun fr71_a_multi_line_text_field_is_a_text_area_and_a_one_line_one_is_not() {
        for (multiline in listOf(true, false)) {
            runComposeUiTest {
                show(
                    listOf(
                        Mutation.Create(ROW, WidgetKind.TextField),
                        Mutation.SetProp(ROW, PropertyKind.Text, PropertyValue.Text("one\ntwo")),
                        Mutation.SetProp(ROW, PropertyKind.Multiline, PropertyValue.Bool(multiline)),
                    ),
                )
                val field = assertNotNull(window().firstOrNull { it.role == ElementRole.FIELD }, "no field")
                assertEquals(multiline, field.states and AccessibleState.MULTI_LINE != 0, "multiline=$multiline")
                assertTrue(!field.label.contains("one"), "the field is named by its text: ${field.label}")
            }
        }
    }

    @Test
    fun fr71_a_named_region_holding_controls_is_a_group_not_text() {
        runComposeUiTest {
            show(
                listOf(
                    Mutation.Create(ROW, WidgetKind.Column),
                    Mutation.SetProp(ROW, PropertyKind.AccessibleName, PropertyValue.Text("프로젝트")),
                    Mutation.Create(CHILD, WidgetKind.Button),
                    Mutation.SetProp(CHILD, PropertyKind.Text, PropertyValue.Text("열기")),
                    Mutation.SetProp(CHILD, PropertyKind.OnClick, PropertyValue.Integer(HANDLER)),
                    Mutation.Insert(ROW, CHILD, 0),
                ),
            )
            val elements = window()
            val region = assertNotNull(elements.firstOrNull { it.label == "프로젝트" }, "$elements")
            assertEquals(ElementRole.GROUP, region.role)
            val button = assertNotNull(elements.firstOrNull { it.role == ElementRole.BUTTON }, "$elements")
            assertEquals(region.id, button.parentId)
        }
        runComposeUiTest {
            // A name with nothing inside is still read as text.
            show(
                listOf(
                    Mutation.Create(ROW, WidgetKind.Box),
                    Mutation.SetProp(ROW, PropertyKind.AccessibleName, PropertyValue.Text("상태")),
                ),
            )
            assertEquals(ElementRole.TEXT, element(ROW).role)
        }
    }

    @Test
    fun fr71_a_button_holding_a_button_of_the_same_name_is_one_button() {
        runComposeUiTest {
            show(
                listOf(
                    Mutation.Create(ROW, WidgetKind.Box),
                    Mutation.SetProp(ROW, PropertyKind.AccessibleName, PropertyValue.Text("예")),
                    Mutation.SetProp(ROW, PropertyKind.OnClick, PropertyValue.Integer(HANDLER)),
                    Mutation.Create(CHILD, WidgetKind.Box),
                    Mutation.SetProp(CHILD, PropertyKind.AccessibleName, PropertyValue.Text("예")),
                    Mutation.SetProp(CHILD, PropertyKind.OnClick, PropertyValue.Integer(HANDLER + 1)),
                    Mutation.Insert(ROW, CHILD, 0),
                    Mutation.Create(LEAF, WidgetKind.Text),
                    Mutation.SetProp(LEAF, PropertyKind.Text, PropertyValue.Text("예")),
                    Mutation.Insert(CHILD, LEAF, 0),
                ),
            )
            val buttons = window().filter { it.role == ElementRole.BUTTON }
            assertEquals(listOf("예"), buttons.map { it.label }, "${window()}")
        }
        runComposeUiTest {
            // A different name inside is a control of its own.
            show(
                listOf(
                    Mutation.Create(ROW, WidgetKind.Box),
                    Mutation.SetProp(ROW, PropertyKind.AccessibleName, PropertyValue.Text("프로젝트 창")),
                    Mutation.SetProp(ROW, PropertyKind.OnClick, PropertyValue.Integer(HANDLER)),
                    Mutation.Create(CHILD, WidgetKind.Box),
                    Mutation.SetProp(CHILD, PropertyKind.AccessibleName, PropertyValue.Text("ax-demo")),
                    Mutation.SetProp(CHILD, PropertyKind.OnClick, PropertyValue.Integer(HANDLER + 1)),
                    Mutation.Insert(ROW, CHILD, 0),
                ),
            )
            assertEquals(2, window().count { it.role == ElementRole.BUTTON }, "${window()}")
        }
    }
}
