package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
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
import dev.darkpyonix.composerust.ui.platform.elementRole
import dev.darkpyonix.composerust.ui.platform.expandedStates
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val BOX = 1
private const val EXPANDED_OPEN = 2 shl 4
private const val EXPANDED_CLOSED = 1 shl 4
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
        assertEquals(AccessibleState.EXPANDABLE, expandedStates(false))
        assertEquals(EXPANDABLE_AND_EXPANDED, expandedStates(true))

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
}
