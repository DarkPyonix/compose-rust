package dioxus.compose.test

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dioxus.compose.design.BadgePlacement
import dioxus.compose.design.HostPlatform
import dioxus.compose.design.resolveTheme
import dioxus.compose.foundation.badgeMarkTestTag
import dioxus.compose.protocol.ButtonVariant
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusHost
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.tooling.HostResponse
import dioxus.compose.tooling.designShowcaseRecords
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.RenderNodeObserver
import dioxus.compose.ui.node.nodeTestTag
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BADGE = 1
private const val CHILD = 2
private const val ALONE = 3
private const val COLUMN = 4

private fun theme(system: DesignSystem) =
    Mutation.SetTheme(Theme(system, DesignSystem.Material3, ColorScheme.Light, false))

/** A column holding a badge with a count on a button, and a badge standing on its own. */
private fun badgedTree(system: DesignSystem, count: Long = 3): List<Mutation> = listOf(
    theme(system),
    Mutation.Create(COLUMN, WidgetKind.Column),
    Mutation.Create(BADGE, WidgetKind.Badge),
    Mutation.SetProp(BADGE, PropertyKind.Count, PropertyValue.Integer(count)),
    Mutation.Create(CHILD, WidgetKind.Button),
    Mutation.SetProp(CHILD, PropertyKind.Text, PropertyValue.Text("Inbox")),
    Mutation.SetProp(CHILD, PropertyKind.Variant, PropertyValue.Integer(ButtonVariant.Tonal.ordinal + 1L)),
    Mutation.SetProp(CHILD, PropertyKind.OnClick, PropertyValue.Integer(1L)),
    Mutation.Insert(BADGE, CHILD, 0),
    Mutation.Insert(COLUMN, BADGE, 0),
    Mutation.Create(ALONE, WidgetKind.Badge),
    Mutation.SetProp(ALONE, PropertyKind.Text, PropertyValue.Text("new")),
    Mutation.Insert(COLUMN, ALONE, 1),
)

private fun stateDescriptionOf(description: String) =
    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

/**
 * A badge: a count, a word or a dot, on a child or on its own. The Host sends what it says
 * and a role; where it sits, its shape and how a large count is written are the design
 * system's, and these tests are what keeps that split honest.
 */
@OptIn(ExperimentalTestApi::class)
class BadgeTest {

    @AfterTest
    fun forgetObserver() {
        RenderNodeObserver.onCompose = null
    }

    /** The three things a badge carries are kept on a badge and nowhere they do not belong. */
    @Test
    fun fr15_2_11_a_badge_keeps_its_count_its_word_and_its_role() {
        for (property in listOf(PropertyKind.Count, PropertyKind.Text, PropertyKind.Color)) {
            assertTrue(NodeTable.supportsProperty(WidgetKind.Badge, property), "$property")
        }
        assertFalse(NodeTable.supportsProperty(WidgetKind.Text, PropertyKind.Count))
        assertFalse(NodeTable.supportsProperty(WidgetKind.Badge, PropertyKind.Checked))
    }

    /** Every one of the seven systems draws the mark, attached and on its own. */
    @Test
    fun fr15_2_11_every_design_system_draws_a_badge() {
        DesignSystem.entries.forEach { system ->
            runComposeUiTest {
                val connection = FakeHostConnection(badgedTree(system))
                setContent { DioxusContent(rememberDioxusHost(connection)) }
                waitForIdle()
                onNodeWithTag(badgeMarkTestTag(BADGE), useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag(badgeMarkTestTag(ALONE), useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag(nodeTestTag(CHILD)).assertIsDisplayed()
            }
        }
    }

    /**
     * The same count of 120 does not come out the same seven times. Some systems write it
     * out, some cut it at their ceiling, and one sets it at the end of the line rather than
     * on the corner.
     */
    @Test
    fun fr15_2_11_a_large_count_is_written_or_placed_differently_between_systems() {
        val answers = DesignSystem.entries.associateWith { system ->
            val resolved = resolveTheme(
                Theme(system, system, ColorScheme.Light, false),
                HostPlatform.Unknown,
                systemDark = false,
            )
            val style = resolved.rules.badge(resolved)
            style.label(120) to style.placement
        }
        assertTrue(
            answers.values.toSet().size >= 2,
            "every system wrote 120 the same way in the same place: $answers",
        )
        assertNotEquals(
            answers.getValue(DesignSystem.Material3).first,
            answers.getValue(DesignSystem.Fluent).first,
            "Material writes 120 out and Fluent cuts it at 99",
        )
        assertEquals("99+", answers.getValue(DesignSystem.Fluent).first)
        assertEquals(BadgePlacement.Trailing, answers.getValue(DesignSystem.Gnome).second)
    }

    /**
     * The count is added to the description of the thing it is on, and the mark is never
     * read on its own. A reader hears "Inbox, 120", not "Inbox" and then "99+".
     */
    @Test
    fun fr15_2_11_an_attached_badge_is_read_as_part_of_its_child() = runComposeUiTest {
        val connection = FakeHostConnection(badgedTree(DesignSystem.Fluent, count = 120))
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        waitForIdle()

        onNodeWithTag(nodeTestTag(CHILD)).assert(stateDescriptionOf("120"))
        val mark = onNodeWithTag(badgeMarkTestTag(BADGE), useUnmergedTree = true)
            .fetchSemanticsNode()
        assertNull(mark.config.getOrNull(SemanticsProperties.Text), "the mark is read on its own")
        assertNull(mark.config.getOrNull(SemanticsProperties.ContentDescription))
    }

    /** A new count reaches the description too, without the child being drawn again. */
    @Test
    fun fr15_2_11_a_new_count_changes_the_mark_and_not_the_child() = runComposeUiTest {
        val compositions = mutableMapOf<Int, Int>()
        RenderNodeObserver.onCompose = { id -> compositions[id] = (compositions[id] ?: 0) + 1 }
        val connection = FakeHostConnection(badgedTree(DesignSystem.Material3, count = 3))
        connection.respondWith {
            HostResponse(
                listOf(Mutation.SetProp(BADGE, PropertyKind.Count, PropertyValue.Integer(4))),
            )
        }
        lateinit var host: DioxusHost
        setContent {
            host = rememberDioxusHost(connection)
            DioxusContent(host)
        }
        waitForIdle()
        val before = compositions.toMap()

        host.dispatch(HostEvent.Clicked(COLUMN, 0))
        waitForIdle()

        onNodeWithTag(nodeTestTag(CHILD)).assert(stateDescriptionOf("4"))
        assertEquals(before[CHILD], compositions[CHILD], "the child must not be composed again")
        assertEquals(before[ALONE], compositions[ALONE], "the other badge must not be composed again")
    }

    /** A badge with neither a count nor a word is a dot, and a count of zero is not a dot. */
    @Test
    fun fr15_2_11_a_dot_has_no_label_and_zero_is_a_count() {
        val resolved = resolveTheme(
            Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false),
            HostPlatform.Unknown,
            systemDark = false,
        )
        val style = resolved.rules.badge(resolved)
        assertEquals("0", style.label(0))
        assertEquals("999+", style.label(1_000))
        assertEquals("999", style.label(999))
    }

    /** The showcase shows a badge attached and alone, with a count, a word and a dot. */
    @Test
    fun fr15_2_11_the_showcase_draws_every_kind_of_badge() {
        val records = designShowcaseRecords(theme(DesignSystem.Material3).theme)
        val badges = records.filterIsInstance<Mutation.Create>()
            .filter { it.widget == WidgetKind.Badge }
            .map { it.nodeId }
            .toSet()
        val props = records.filterIsInstance<Mutation.SetProp>().filter { it.nodeId in badges }
        val counted = props.filter { it.property == PropertyKind.Count }.map { it.nodeId }.toSet()
        val worded = props.filter { it.property == PropertyKind.Text }.map { it.nodeId }.toSet()
        val dots = badges - counted - worded
        val parents = records.filterIsInstance<Mutation.Insert>().map { it.parentId }.toSet()
        val attached = badges.filter { it in parents }.toSet()
        val alone = badges - attached

        assertTrue(counted.isNotEmpty(), "no badge carries a count")
        assertTrue(worded.isNotEmpty(), "no badge carries a word")
        assertTrue(dots.isNotEmpty(), "no badge is a dot")
        assertTrue(attached.isNotEmpty(), "no badge is attached to anything")
        assertTrue(alone.isNotEmpty(), "no badge stands on its own")
        assertTrue(
            props.any { it.property == PropertyKind.Count && it.value == PropertyValue.Integer(120) },
            "the count that differs between systems is not in the showcase",
        )
    }
}
