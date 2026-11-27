package dev.darkpyonix.composerust.test

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SemanticsRole
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.DeclaredRole
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.describe
import dev.darkpyonix.composerust.ui.platform.elementRole
import dev.darkpyonix.composerust.ui.platform.expandedStates
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BOX = 1
private const val COLUMN = 10
private val ROWS = listOf("새 파일", "열기", "저장")
private const val EXPANDED_OPEN = 2 shl 4
private const val EXPANDED_CLOSED = 1 shl 4
private const val HAS_POPUP = 1 shl 13
private const val EXPANDABLE_AND_EXPANDED = AccessibleState.EXPANDABLE or AccessibleState.EXPANDED

/** What the window hands the platform for one Box carrying [role] and [state], and maybe [label]. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.described(role: SemanticsRole, state: Int = 0, label: String? = null): AccessibleElement? {
    val mutations = buildList {
        add(Mutation.Create(BOX, WidgetKind.Box))
        if (label != null) add(Mutation.SetProp(BOX, PropertyKind.AccessibleName, PropertyValue.Text(label)))
        add(Mutation.SetModifier(BOX, 0, ProtocolModifier.Semantics(role, state)))
    }
    setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations))) }
    waitForIdle()
    return onNodeWithTag(nodeTestTag(BOX)).fetchSemanticsNode().describe(-1, 1f)
}

@OptIn(ExperimentalTestApi::class)
class PlatformRoleTest {

    @Test
    fun fr71_platform_role_every_role_compose_cannot_name_has_the_platforms_own() {
        val expected = mapOf(
            SemanticsRole.TabList to ElementRole.TAB_GROUP,
            SemanticsRole.Tree to ElementRole.OUTLINE,
            SemanticsRole.TreeItem to ElementRole.OUTLINE_ROW,
            SemanticsRole.Menu to ElementRole.MENU,
            SemanticsRole.MenuItem to ElementRole.MENU_ITEM,
            SemanticsRole.TextBox to ElementRole.FIELD,
            SemanticsRole.Heading to ElementRole.HEADING,
            SemanticsRole.Navigation to ElementRole.NAVIGATION,
            SemanticsRole.List to ElementRole.LIST,
            SemanticsRole.ListItem to ElementRole.LIST_ITEM,
            SemanticsRole.Group to ElementRole.GROUP,
            SemanticsRole.ComboBox to ElementRole.COMBO_BOX,
            SemanticsRole.ListBox to ElementRole.LIST,
            SemanticsRole.Option to ElementRole.LIST_ITEM,
        )
        for ((role, platform) in expected) assertEquals(platform, role.elementRole(), "$role")
        // The roles Compose names itself go through Compose's role, and no role is no role.
        for (role in SemanticsRole.entries - expected.keys) assertNull(role.elementRole(), "$role")
    }

    @Test
    fun fr71_platform_role_reaches_the_window_for_every_new_role() {
        for (role in SemanticsRole.entries) {
            val platform = role.elementRole() ?: continue
            runComposeUiTest {
                val element = assertNotNull(described(role, label = "Files"), "$role was left out")
                assertEquals(platform, element.role, "$role")
                assertEquals("Files", element.label)
                // The platform says the role in its own words, so the role word is not read too.
                assertNull(element.value, "$role")
            }
        }
    }

    @Test
    fun fr71_platform_role_a_node_with_a_role_and_no_name_is_kept() {
        runComposeUiTest {
            val element = assertNotNull(described(SemanticsRole.Menu), "a menu without a name was left out")
            assertEquals(ElementRole.MENU, element.role)
            assertEquals("", element.label)
        }
        runComposeUiTest {
            val element = assertNotNull(described(SemanticsRole.Navigation), "a navigation landmark was left out")
            assertEquals(ElementRole.NAVIGATION, element.role)
        }
    }

    @Test
    fun fr71_platform_role_a_node_with_neither_role_nor_name_is_still_left_out() {
        runComposeUiTest {
            assertNull(described(SemanticsRole.None))
        }
    }

    @Test
    fun fr71_expanded_state_reaches_the_platform() {
        assertEquals(0, expandedStates(null))
        assertEquals(AccessibleState.EXPANDABLE, expandedStates(null, hasPopup = true))
        assertEquals(AccessibleState.EXPANDABLE, expandedStates(false))
        assertEquals(EXPANDABLE_AND_EXPANDED, expandedStates(true))
        assertEquals(EXPANDABLE_AND_EXPANDED, expandedStates(true, hasPopup = true))

        runComposeUiTest {
            val element = assertNotNull(described(SemanticsRole.TreeItem, EXPANDED_OPEN, label = "src"))
            assertEquals(ElementRole.OUTLINE_ROW, element.role)
            assertEquals(EXPANDABLE_AND_EXPANDED, element.states and EXPANDABLE_AND_EXPANDED)
            // Neither "treeitem" nor "expanded" is read out as the value.
            assertNull(element.value)
        }
        runComposeUiTest {
            val element = assertNotNull(described(SemanticsRole.Menu, EXPANDED_CLOSED))
            assertEquals(AccessibleState.EXPANDABLE, element.states and EXPANDABLE_AND_EXPANDED)
        }
        runComposeUiTest {
            val element = assertNotNull(described(SemanticsRole.Button, HAS_POPUP, label = "IDE"))
            assertEquals(ElementRole.BUTTON, element.role)
            assertEquals(AccessibleState.EXPANDABLE, element.states and EXPANDABLE_AND_EXPANDED)
            assertNull(element.value)
        }
        runComposeUiTest {
            // Open or closed with no role and no name is still something to announce.
            val element = assertNotNull(described(SemanticsRole.None, EXPANDED_OPEN))
            assertEquals(ElementRole.GROUP, element.role)
            assertEquals(EXPANDABLE_AND_EXPANDED, element.states and EXPANDABLE_AND_EXPANDED)
        }
        runComposeUiTest {
            // Saying nothing about it is not the same as closed.
            val element = assertNotNull(described(SemanticsRole.Tree, label = "Files"))
            assertEquals(0, element.states and EXPANDABLE_AND_EXPANDED)
        }
    }

    /** A Column carrying [role] (or no Semantics modifier at all) holding one Text row per [ROWS]. */
    private fun ComposeUiTest.showColumn(role: SemanticsRole?) {
        val mutations = buildList {
            add(Mutation.Create(COLUMN, WidgetKind.Column))
            if (role != null) add(Mutation.SetModifier(COLUMN, 0, ProtocolModifier.Semantics(role, 0)))
            ROWS.forEachIndexed { index, word ->
                val row = COLUMN + 1 + index
                add(Mutation.Create(row, WidgetKind.Text))
                add(Mutation.SetProp(row, PropertyKind.Text, PropertyValue.Text(word)))
                add(Mutation.Insert(COLUMN, row, index))
            }
        }
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations))) }
        waitForIdle()
    }

    /** The elements a window describes below [node], each attached to the nearest kept ancestor. */
    private fun describedBelow(node: SemanticsNode, parentId: Int): List<AccessibleElement> =
        node.children.flatMap { child ->
            val element = child.describe(parentId, 1f)
            listOfNotNull(element) + describedBelow(child, element?.id ?: parentId)
        }

    @Test
    fun fr71_a_container_role_keeps_its_children_as_separate_nodes() {
        val expected = mapOf(
            SemanticsRole.Menu to ElementRole.MENU,
            SemanticsRole.List to ElementRole.LIST,
            SemanticsRole.Group to ElementRole.GROUP,
        )
        for ((role, platformRole) in expected) {
            runComposeUiTest {
                showColumn(role)
                // The merged tree is the one a reader walks, so it is the one asked here.
                val column = onNodeWithTag(nodeTestTag(COLUMN)).fetchSemanticsNode()
                assertEquals(role, column.config.getOrNull(DeclaredRole), "$role")
                assertEquals(true, column.config.getOrNull(SemanticsProperties.IsTraversalGroup), "$role is not a group to walk into")
                assertNull(column.config.getOrNull(SemanticsProperties.Text), "$role took its rows' text as its own")
                for (word in ROWS) onAllNodes(hasText(word)).assertCountEquals(1)
                if (role != SemanticsRole.Group) {
                    assertEquals(ROWS.size, column.config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount, "$role")
                }
                // What the window hands the platform: the role of its own, and rows beneath it.
                val element = assertNotNull(column.describe(-1, 1f))
                assertEquals(platformRole, element.role, "$role")
                val rows = describedBelow(column, element.id)
                assertEquals(ROWS, rows.map { it.label }, "$role")
                for (row in rows) {
                    assertEquals(ElementRole.TEXT, row.role)
                    assertEquals(element.id, row.parentId, "a row of $role is not its child")
                }
            }
        }
    }

    @Test
    fun fr71_a_column_without_a_container_role_is_unchanged() {
        runComposeUiTest {
            showColumn(null)
            val column = onNodeWithTag(nodeTestTag(COLUMN)).fetchSemanticsNode()
            assertNull(column.config.getOrNull(SemanticsProperties.IsTraversalGroup))
            assertNull(column.config.getOrNull(SemanticsProperties.CollectionInfo))
            assertNull(column.describe(-1, 1f), "a column that says nothing is not an element")
            for (word in ROWS) onAllNodes(hasText(word)).assertCountEquals(1)
        }
        runComposeUiTest {
            // A role that holds nothing to walk into is not made a group.
            showColumn(SemanticsRole.MenuItem)
            val column = onNodeWithTag(nodeTestTag(COLUMN)).fetchSemanticsNode()
            assertNull(column.config.getOrNull(SemanticsProperties.IsTraversalGroup))
            assertTrue(column.config.getOrNull(SemanticsProperties.CollectionInfo) == null)
        }
    }

    /**
     * A mixed Checkbox is mixed to assistive technology in every design system: Compose's
     * indeterminate toggle state on the node, and the platform's mixed state, not checked, on
     * the element the window hands the platform.
     */
    @Test
    fun fr68_mixed_checkbox_is_mixed_in_the_accessibility_tree() {
        for (system in DesignSystem.entries) {
            for (checked in listOf(false, true)) {
                runComposeUiTest {
                    val mutations = listOf(
                        Mutation.SetTheme(Theme(system, system, ColorScheme.Light, false)),
                        Mutation.Create(BOX, WidgetKind.Checkbox),
                        Mutation.SetProp(BOX, PropertyKind.Checked, PropertyValue.Bool(checked)),
                        Mutation.SetProp(BOX, PropertyKind.Indeterminate, PropertyValue.Bool(true)),
                        Mutation.SetProp(BOX, PropertyKind.OnValueChange, PropertyValue.Integer(71L)),
                        Mutation.SetProp(BOX, PropertyKind.AccessibleName, PropertyValue.Text("Staged")),
                    )
                    setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations))) }
                    waitForIdle()
                    val node = onNodeWithTag(nodeTestTag(BOX)).fetchSemanticsNode()
                    assertEquals(
                        ToggleableState.Indeterminate,
                        node.config.getOrNull(SemanticsProperties.ToggleableState),
                        "$system (checked: $checked)",
                    )
                    val element = assertNotNull(node.describe(-1, 1f), "$system: the checkbox was left out")
                    assertEquals(ElementRole.CHECKBOX, element.role, "$system")
                    assertEquals(AccessibleState.MIXED, element.states and AccessibleState.MIXED, "$system: not mixed")
                    assertEquals(0, element.states and AccessibleState.CHECKED, "$system: mixed is not checked")
                }
            }
        }
    }
}
