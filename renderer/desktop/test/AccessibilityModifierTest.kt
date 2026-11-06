package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IconRole
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
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TREE_ROOT = 1
private const val TAG_PREFIX = "compose-node-"
private const val SOME_HANDLER = 77L

private fun name(text: String) = PropertyValue.Text(text)

/** What a screen reader would read for a node: its description, then its text. */
private fun SemanticsNode.spoken(): String {
    val description = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    val text = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    return (description + text).joinToString(" ").trim()
}

private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

/** Everything that can take focus or be operated, which is what must be named. */
private val operable = SemanticsMatcher("can be operated") {
    it.tag()?.startsWith(TAG_PREFIX) == true && (
        it.config.contains(SemanticsActions.OnClick) ||
            it.config.contains(SemanticsProperties.ToggleableState) ||
            it.config.contains(SemanticsProperties.Selected) ||
            it.config.contains(SemanticsProperties.ProgressBarRangeInfo)
        )
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.show(mutations: List<Mutation>) {
    setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations))) }
    waitForIdle()
}

/**
 * Widgets the every-operable-node-has-a-name check does not cover.
 * Sliders are left out until the design system's Slider exposes a range semantics node: thisisthepy/compose-multiplatform-core-extended#180.
 */
private val LEFT_OUT_OF_THE_CHECK = setOf(WidgetKind.Slider)

/** A column holding one of every kind of control, each given a name unless it names itself. */
private fun everyOperable(named: Boolean) = buildList {
    add(Mutation.Create(TREE_ROOT, WidgetKind.Column))
    var next = TREE_ROOT + 1
    fun child(widget: WidgetKind, setup: (Int) -> List<Mutation>): Int {
        val id = next++
        add(Mutation.Create(id, widget))
        addAll(setup(id))
        add(Mutation.Insert(TREE_ROOT, id, id - TREE_ROOT - 1))
        return id
    }
    fun label(id: Int, text: String) =
        if (named) listOf(Mutation.SetProp(id, PropertyKind.AccessibleName, name(text))) else emptyList()

    // A button with a word, and one with only a glyph, which is named by its meaning.
    child(WidgetKind.Button) {
        listOf(
            Mutation.SetProp(it, PropertyKind.Text, name("Save")),
            Mutation.SetProp(it, PropertyKind.OnClick, PropertyValue.Integer(SOME_HANDLER)),
        )
    }
    child(WidgetKind.Button) {
        listOf(
            Mutation.SetProp(it, PropertyKind.Text, name("")),
            Mutation.SetProp(it, PropertyKind.Icon, PropertyValue.Integer(IconRole.Add.ordinal + 1L)),
            Mutation.SetProp(it, PropertyKind.OnClick, PropertyValue.Integer(SOME_HANDLER)),
        ) + label(it, "Import")
    }
    // A pressable container with no text of its own.
    child(WidgetKind.Box) {
        listOf(
            Mutation.SetProp(it, PropertyKind.OnClick, PropertyValue.Integer(SOME_HANDLER)),
        ) + label(it, "Refresh")
    }
    for (kind in listOf(WidgetKind.Switch, WidgetKind.Checkbox, WidgetKind.RadioButton)) {
        child(kind) {
            listOf(
                Mutation.SetProp(it, PropertyKind.Checked, PropertyValue.Bool(true)),
                Mutation.SetProp(it, PropertyKind.OnValueChange, PropertyValue.Integer(SOME_HANDLER)),
            ) + label(it, "$kind option")
        }
    }
    if (WidgetKind.Slider !in LEFT_OUT_OF_THE_CHECK) child(WidgetKind.Slider) {
        listOf(
            Mutation.SetProp(it, PropertyKind.Value, PropertyValue.Float(0.5f)),
            Mutation.SetProp(it, PropertyKind.Min, PropertyValue.Float(0f)),
            Mutation.SetProp(it, PropertyKind.Max, PropertyValue.Float(1f)),
            Mutation.SetProp(it, PropertyKind.OnValueChange, PropertyValue.Integer(SOME_HANDLER)),
        ) + label(it, "Volume")
    }
}

/** The nodes that can be operated and that a screen reader would find without a name. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.unnamed(): List<String> =
    onAllNodes(operable).fetchSemanticsNodes().filter { it.spoken().isEmpty() }.map { it.tag().orEmpty() }

@OptIn(ExperimentalTestApi::class)
class AccessibilityModifierTest {
    @Test
    fun fr68_every_operable_node_has_a_name() {
        runComposeUiTest {
            show(everyOperable(named = true))
            val operableCount = onAllNodes(operable).fetchSemanticsNodes().size
            assertTrue(operableCount >= 6, "only $operableCount operable nodes were found, so the check proves nothing")
            val missing = unnamed()
            assertTrue(missing.isEmpty(), "these nodes can be operated and have no name: $missing")
        }
    }

    @Test
    fun fr68_the_check_finds_a_control_that_has_no_name() {
        runComposeUiTest {
            show(everyOperable(named = false))
            val missing = unnamed()
            // The pressable container and the three toggles say nothing by themselves. Sliders
            // are in LEFT_OUT_OF_THE_CHECK.
            assertTrue(missing.size >= 4, "an unnamed control went unnoticed: $missing")
            assertTrue(missing.all { it.startsWith(TAG_PREFIX) }, "$missing")
        }
    }

    @Test
    fun fr68_a_box_with_a_label_and_a_role_is_announced_as_both() {
        val box = 1
        runComposeUiTest {
            show(
                listOf(
                    Mutation.Create(box, WidgetKind.Box),
                    Mutation.SetProp(box, PropertyKind.AccessibleName, name("Refresh")),
                    Mutation.SetModifier(box, 0, ProtocolModifier.Semantics(SemanticsRole.Button, 0)),
                ),
            )
            val node = onNodeWithTag(nodeTestTag(box)).fetchSemanticsNode()
            assertEquals(listOf("Refresh"), node.config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        }
    }

    @Test
    fun fr68_a_node_that_asks_for_nothing_gets_no_semantics_of_its_own() {
        val box = 1
        runComposeUiTest {
            show(listOf(Mutation.Create(box, WidgetKind.Box)))
            val config = onNodeWithTag(nodeTestTag(box)).fetchSemanticsNode().config
            assertEquals(null, config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(null, config.getOrNull(SemanticsProperties.Role))
        }
    }

    @Test
    fun fr68_a_box_with_a_label_and_a_role_is_one_semantics_node() {
        val box = 1
        runComposeUiTest {
            show(
                listOf(
                    Mutation.Create(box, WidgetKind.Box),
                    Mutation.SetProp(box, PropertyKind.AccessibleName, name("Refresh")),
                    Mutation.SetModifier(box, 0, ProtocolModifier.Semantics(SemanticsRole.Button, 0)),
                ),
            )
            val nodes = onAllNodes(SemanticsMatcher("carries the label or the role") {
                it.tag() == nodeTestTag(box) ||
                    it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Refresh") ||
                    it.config.getOrNull(SemanticsProperties.Role) == Role.Button
            }, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(1, nodes.size, "the label and the role must sit on one node: $nodes")
            val config = nodes.single().config
            assertEquals(listOf("Refresh"), config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(Role.Button, config.getOrNull(SemanticsProperties.Role))
        }
    }

    @Test
    fun fr68_the_state_of_a_modifier_reaches_the_semantics() {
        val box = 1
        runComposeUiTest {
            // Checked on is 2 in the low field, selected off is 1 in the field above it.
            show(
                listOf(
                    Mutation.Create(box, WidgetKind.Box),
                    Mutation.SetModifier(box, 0, ProtocolModifier.Semantics(SemanticsRole.Tab, 2 or (1 shl 2))),
                ),
            )
            val config = onNodeWithTag(nodeTestTag(box)).fetchSemanticsNode().config
            assertEquals(Role.Tab, config.getOrNull(SemanticsProperties.Role))
            assertEquals(ToggleableState.On, config.getOrNull(SemanticsProperties.ToggleableState))
            assertEquals(false, config.getOrNull(SemanticsProperties.Selected))
        }
    }

    @Test
    fun fr68_the_accessible_name_beats_the_name_of_the_icon() {
        val button = 1
        runComposeUiTest {
            show(
                listOf(
                    Mutation.Create(button, WidgetKind.Button),
                    Mutation.SetProp(button, PropertyKind.Text, name("")),
                    Mutation.SetProp(button, PropertyKind.Icon, PropertyValue.Integer(IconRole.Search.ordinal + 1L)),
                    Mutation.SetProp(button, PropertyKind.AccessibleName, name("Import")),
                    Mutation.SetProp(button, PropertyKind.OnClick, PropertyValue.Integer(SOME_HANDLER)),
                ),
            )
            val node = onNodeWithTag(nodeTestTag(button)).fetchSemanticsNode()
            assertEquals(listOf("Import"), node.config.getOrNull(SemanticsProperties.ContentDescription))
        }
    }

    @Test
    fun fr68_a_named_switch_says_its_name_and_that_it_is_on() {
        val switch = 1
        runComposeUiTest {
            show(
                listOf(
                    Mutation.Create(switch, WidgetKind.Switch),
                    Mutation.SetProp(switch, PropertyKind.Checked, PropertyValue.Bool(true)),
                    Mutation.SetProp(switch, PropertyKind.AccessibleName, name("Notifications")),
                    Mutation.SetProp(switch, PropertyKind.OnValueChange, PropertyValue.Integer(SOME_HANDLER)),
                ),
            )
            val config = onNodeWithTag(nodeTestTag(switch)).fetchSemanticsNode().config
            assertEquals(listOf("Notifications"), config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(ToggleableState.On, config.getOrNull(SemanticsProperties.ToggleableState))
        }
    }

    @Test
    fun fr68_removing_the_accessible_name_returns_the_default_name() {
        val button = 1
        fun iconButton(vararg extra: Mutation) = listOf(
            Mutation.Create(button, WidgetKind.Button),
            Mutation.SetProp(button, PropertyKind.Text, name("")),
            Mutation.SetProp(button, PropertyKind.Icon, PropertyValue.Integer(IconRole.Search.ordinal + 1L)),
            Mutation.SetProp(button, PropertyKind.OnClick, PropertyValue.Integer(SOME_HANDLER)),
        ) + extra
        fun ComposeUiTest.description() = onNodeWithTag(nodeTestTag(button)).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription)

        // The name of the same button when it never had one of its own.
        var defaultName: List<String>? = null
        runComposeUiTest {
            show(iconButton())
            defaultName = description()
        }

        runComposeUiTest {
            val frames = FrameRequestSource()
            val connection = FakeHostConnection(
                iconButton(Mutation.SetProp(button, PropertyKind.AccessibleName, name("가져오기"))),
            )
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    ComposeRustContent(rememberComposeRustHost(connection))
                }
            }
            waitForIdle()
            assertEquals(listOf("가져오기"), description())

            connection.scheduleFrame(listOf(Mutation.SetProp(button, PropertyKind.AccessibleName, PropertyValue.None)))
            frames.request()
            waitForIdle()
            assertEquals(defaultName, description(), "the name after the removal is not the default one")
            assertTrue(description() != listOf("가져오기"), "the removed name is still spoken")
        }
    }

    private val newRoles = mapOf(
        SemanticsRole.TabList to "tablist",
        SemanticsRole.Tree to "tree",
        SemanticsRole.TreeItem to "treeitem",
        SemanticsRole.Menu to "menu",
        SemanticsRole.MenuItem to "menuitem",
        SemanticsRole.TextBox to "textbox",
        SemanticsRole.Navigation to "navigation",
        SemanticsRole.List to "list",
        SemanticsRole.ListItem to "listitem",
        SemanticsRole.Group to "group",
    )

    @Test
    fun fr71_every_new_role_round_trips_and_is_exposed() {
        val box = 1
        for ((role, word) in newRoles) {
            runComposeUiTest {
                show(
                    listOf(
                        Mutation.Create(box, WidgetKind.Box),
                        Mutation.SetModifier(box, 0, ProtocolModifier.Semantics(role, 0)),
                    ),
                )
                val config = onNodeWithTag(nodeTestTag(box)).fetchSemanticsNode().config
                assertEquals(word, config.getOrNull(SemanticsProperties.StateDescription), "$role")
            }
        }
        runComposeUiTest {
            show(
                listOf(
                    Mutation.Create(box, WidgetKind.Box),
                    Mutation.SetModifier(box, 0, ProtocolModifier.Semantics(SemanticsRole.Heading, 0)),
                ),
            )
            val config = onNodeWithTag(nodeTestTag(box)).fetchSemanticsNode().config
            assertTrue(config.contains(SemanticsProperties.Heading), "a heading must be marked as one")
        }
    }

    @Test
    fun fr71_state_bits_reach_the_accessibility_node() {
        val box = 1
        fun ComposeUiTest.configOf(state: Int, role: SemanticsRole = SemanticsRole.None): androidx.compose.ui.semantics.SemanticsConfiguration {
            show(
                listOf(
                    Mutation.Create(box, WidgetKind.Box),
                    Mutation.SetModifier(box, 0, ProtocolModifier.Semantics(role, state)),
                ),
            )
            return onNodeWithTag(nodeTestTag(box), useUnmergedTree = true).fetchSemanticsNode().config
        }
        runComposeUiTest {
            // Expanded on, disabled on, focused off.
            val config = configOf((2 shl 4) or (2 shl 6) or (1 shl 8), SemanticsRole.TreeItem)
            assertEquals("treeitem, expanded", config.getOrNull(SemanticsProperties.StateDescription))
            assertTrue(config.contains(SemanticsProperties.Disabled))
            assertEquals(false, config.getOrNull(SemanticsProperties.Focused))
            assertTrue(!config.contains(SemanticsProperties.HideFromAccessibility))
        }
        runComposeUiTest {
            val config = configOf((1 shl 4) or (2 shl 8))
            assertEquals("collapsed", config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(true, config.getOrNull(SemanticsProperties.Focused))
            assertTrue(!config.contains(SemanticsProperties.Disabled))
        }
        runComposeUiTest {
            // Enabled is explicit and sets nothing; hidden leaves the node out of the tree.
            val config = configOf((1 shl 6) or (1 shl 10), SemanticsRole.Group)
            assertTrue(!config.contains(SemanticsProperties.Disabled))
            assertTrue(config.contains(SemanticsProperties.HideFromAccessibility))
        }
    }

    @Test
    fun fr71_click_action_calls_the_nodes_click_handler() {
        val box = 1
        val clickAction = 1 shl 11
        runComposeUiTest {
            val connection = FakeHostConnection(
                listOf(
                    Mutation.Create(box, WidgetKind.Box),
                    Mutation.SetProp(box, PropertyKind.OnClick, PropertyValue.Integer(SOME_HANDLER)),
                    Mutation.SetModifier(box, 0, ProtocolModifier.Semantics(SemanticsRole.MenuItem, clickAction)),
                ),
            )
            setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
            waitForIdle()
            onNodeWithTag(nodeTestTag(box)).performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            val clicks = connection.events.filterIsInstance<HostEvent.Clicked>()
            assertEquals(listOf(HostEvent.Clicked(box, SOME_HANDLER)), clicks)
        }
        runComposeUiTest {
            show(
                listOf(
                    Mutation.Create(box, WidgetKind.Box),
                    Mutation.SetModifier(box, 0, ProtocolModifier.Semantics(SemanticsRole.MenuItem, clickAction)),
                ),
            )
            val config = onNodeWithTag(nodeTestTag(box)).fetchSemanticsNode().config
            assertTrue(!config.contains(SemanticsActions.OnClick), "a node without a handler has no click action")
        }
    }
}
