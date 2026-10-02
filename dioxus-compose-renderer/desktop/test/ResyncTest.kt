package dioxus.compose.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.DioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.tooling.HostResponse
import dioxus.compose.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val COLUMN = 1
private const val FIRST = 2
private const val SECOND = 3

private fun tree(vararg labels: Pair<Int, String>) = buildList {
    add(Mutation.Create(COLUMN, WidgetKind.Column))
    labels.forEachIndexed { index, (nodeId, text) ->
        add(Mutation.Create(nodeId, WidgetKind.Text))
        add(Mutation.SetProp(nodeId, PropertyKind.Text, PropertyValue.Text(text)))
        add(Mutation.Insert(COLUMN, nodeId, index))
    }
}

@OptIn(ExperimentalTestApi::class)
class ResyncTest {
    /**
     * A resync answers with a tree built from nothing, so the ids in it start over. The
     * Renderer has to have let go of the old tree before that batch arrives: a node the
     * new tree does not mention would otherwise still be on screen, and the first Create
     * would land on an id that is still taken.
     */
    @Test
    fun pr5_resync_replaces_the_tree_rather_than_merging_into_it() = runComposeUiTest {
        val connection = FakeHostConnection(tree(FIRST to "before", SECOND to "stale"))
        connection.respondWith { event ->
            if (event is HostEvent.Resync) {
                HostResponse(tree(FIRST to "after"))
            } else {
                HostResponse()
            }
        }
        val host = DioxusHost(connection)
        host.start()
        setContent { DioxusContent(host) }
        onNodeWithTag(nodeTestTag(SECOND)).assertIsDisplayed()

        host.resync()
        waitForIdle()

        onNodeWithTag(nodeTestTag(FIRST)).assertIsDisplayed().assertTextEquals("after")
        onNodeWithTag(nodeTestTag(SECOND)).assertDoesNotExist()
        assertEquals(listOf(COLUMN), host.roots, "the old tree is gone, not doubled")
        assertTrue(
            connection.events.any { it is HostEvent.Resync },
            "the Host is asked for the tree, not just told to forget it",
        )
        assertTrue(
            connection.events.none { it is HostEvent.ProtocolError },
            "a resync batch applies cleanly: ${connection.events}",
        )
    }
}
