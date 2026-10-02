package dioxus.compose.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.DioxusHost
import dioxus.compose.runtime.onProtocolError
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.tooling.HostResponse
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.RenderNodeObserver
import dioxus.compose.ui.node.TableError
import dioxus.compose.ui.node.nodeTestTag
import dioxus.compose.ui.platform.HostCallException

private const val COLUMN = 1
private const val LEFT = 2
private const val RIGHT = 3
private const val BUTTON = 4
private const val CLICK_HANDLER = 77L

private fun twoTextColumn() = listOf(
    Mutation.Create(COLUMN, WidgetKind.Column),
    Mutation.Create(LEFT, WidgetKind.Text),
    Mutation.SetProp(LEFT, PropertyKind.Text, PropertyValue.Text("left")),
    Mutation.Insert(COLUMN, LEFT, 0),
    Mutation.Create(RIGHT, WidgetKind.Text),
    Mutation.SetProp(RIGHT, PropertyKind.Text, PropertyValue.Text("right")),
    Mutation.Insert(COLUMN, RIGHT, 1),
)

@OptIn(ExperimentalTestApi::class)
class InterpreterTest {
    @AfterTest
    fun clearObserver() {
        RenderNodeObserver.onCompose = null
    }

    @Test
    fun fr1_mutations_build_the_expected_tree() = runComposeUiTest {
        val connection = FakeHostConnection(twoTextColumn())
        setContent { DioxusContent(rememberDioxusHost(connection)) }

        onNodeWithTag(nodeTestTag(COLUMN)).assertIsDisplayed()
        onNodeWithTag(nodeTestTag(LEFT)).assertTextEquals("left")
        onNodeWithTag(nodeTestTag(RIGHT)).assertTextEquals("right")
    }

    @Test
    fun fr1_remove_detaches_the_node_and_its_subtree() = runComposeUiTest {
        val connection = FakeHostConnection(twoTextColumn())
        connection.respondWith { HostResponse(listOf(Mutation.Remove(LEFT))) }
        lateinit var host: DioxusHost
        setContent { host = rememberDioxusHost(connection) ; DioxusContent(host) }

        host.dispatch(HostEvent.Clicked(COLUMN, 0))
        waitForIdle()

        onNodeWithTag(nodeTestTag(LEFT)).assertDoesNotExist()
        onNodeWithTag(nodeTestTag(RIGHT)).assertIsDisplayed()
    }

    @Test
    fun fr2_unsupported_property_reports_a_protocol_error_and_keeps_rendering() =
        runComposeUiTest {
            val connection = FakeHostConnection(
                twoTextColumn() + Mutation.SetProp(
                    LEFT,
                    PropertyKind.Placeholder,
                    PropertyValue.Text("nope"),
                ),
            )
            setContent { DioxusContent(rememberDioxusHost(connection)) }
            waitForIdle()

            val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
            assertEquals(1, errors.size, "expected exactly one ProtocolError: ${connection.nodeEvents}")
            onNodeWithTag(nodeTestTag(LEFT)).assertTextEquals("left")
        }

    /**
     * A parent chain that loops is a malformed tree, not a tree to walk. Inserting a node
     * under one of its own descendants would make the two of them each other's ancestor,
     * and anything that follows the chain (drawing it, or walking up from a node to its
     * root) would recurse until the stack ran out. The Renderer refuses the Insert,
     * reports it, and keeps the tree it had.
     */
    @Test
    fun nfr7_an_insert_that_would_loop_the_parent_chain_is_refused() = runComposeUiTest {
        val connection = FakeHostConnection(
            twoTextColumn() + listOf(
                Mutation.Create(BUTTON, WidgetKind.Column),
                Mutation.Insert(COLUMN, BUTTON, 2),
                // The Column is already the grandparent of this node's parent.
                Mutation.Insert(BUTTON, COLUMN, 0),
            ),
        )
        lateinit var host: DioxusHost
        setContent { host = rememberDioxusHost(connection) ; DioxusContent(host) }
        waitForIdle()

        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        assertEquals(1, errors.size, "expected exactly one ProtocolError: ${connection.nodeEvents}")
        assertEquals(TableError.CYCLIC_INSERT, errors.first().code)
        assertEquals(
            listOf(LEFT, RIGHT, BUTTON),
            host.table.node(COLUMN)!!.children,
            "the refused Insert changed the tree",
        )
        onNodeWithTag(nodeTestTag(LEFT)).assertTextEquals("left")
    }

    /**
     * Node id 0 is the "no node" sentinel, and the Host inserts it where a Dioxus
     * placeholder stands (an empty `for` body). It draws nothing, takes no slot, and the
     * siblings around it keep the positions the Host gave them.
     */
    @Test
    fun fr1_placeholder_node_id_zero_takes_no_slot_and_is_not_an_error() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(COLUMN, WidgetKind.Column),
                Mutation.Create(LEFT, WidgetKind.Text),
                Mutation.SetProp(LEFT, PropertyKind.Text, PropertyValue.Text("left")),
                Mutation.Create(RIGHT, WidgetKind.Text),
                Mutation.SetProp(RIGHT, PropertyKind.Text, PropertyValue.Text("right")),
                Mutation.Insert(COLUMN, LEFT, 0),
                Mutation.Insert(COLUMN, NodeTable.ROOT_ID, 1),
                Mutation.Insert(COLUMN, RIGHT, 2),
            ),
        )
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        waitForIdle()

        assertEquals(
            emptyList(),
            connection.events.filterIsInstance<HostEvent.ProtocolError>(),
            "the placeholder sentinel is not a protocol error",
        )
        onNodeWithTag(nodeTestTag(LEFT)).assertTextEquals("left")
        onNodeWithTag(nodeTestTag(RIGHT)).assertTextEquals("right")
    }

    /**
     * The Rust Host answers a `ProtocolError` event with a protocol-error status,
     * because no handler owns that event. The report failing must not take the composition
     * down; the error still has to surface.
     */
    @Test
    fun nfr7_a_host_that_rejects_the_error_report_does_not_break_the_composition() =
        runComposeUiTest {
            val reported = mutableListOf<TableError>()
            val previous = onProtocolError
            onProtocolError = { error -> reported += error }
            try {
                val connection = FakeHostConnection(twoTextColumn() + Mutation.Remove(999))
                connection.respondWith { throw HostCallException("host rejected the report") }
                setContent { DioxusContent(rememberDioxusHost(connection)) }
                waitForIdle()

                assertEquals(
                    listOf(TableError.UNKNOWN_NODE),
                    reported.map { error -> error.code },
                )
                onNodeWithTag(nodeTestTag(LEFT)).assertTextEquals("left")
            } finally {
                onProtocolError = previous
            }
        }

    @Test
    fun fr4_set_prop_does_not_recompose_siblings() = runComposeUiTest {
        val compositions = mutableMapOf<Int, Int>()
        RenderNodeObserver.onCompose = { id -> compositions[id] = (compositions[id] ?: 0) + 1 }
        val connection = FakeHostConnection(twoTextColumn())
        connection.respondWith {
            HostResponse(
                listOf(Mutation.SetProp(LEFT, PropertyKind.Text, PropertyValue.Text("changed"))),
            )
        }
        lateinit var host: DioxusHost
        setContent { host = rememberDioxusHost(connection) ; DioxusContent(host) }
        waitForIdle()
        val before = compositions.toMap()

        host.dispatch(HostEvent.Clicked(COLUMN, 0))
        waitForIdle()

        onNodeWithTag(nodeTestTag(LEFT)).assertTextEquals("changed")
        assertEquals(
            (before[LEFT] ?: 0) + 1,
            compositions[LEFT],
            "the changed Text must recompose exactly once",
        )
        assertEquals(before[RIGHT], compositions[RIGHT], "the sibling Text must not recompose")
        assertEquals(before[COLUMN], compositions[COLUMN], "the parent Column must not recompose")
    }

    @Test
    fun pr2_batch_applies_as_one_transaction() = runComposeUiTest {
        val seen = mutableListOf<String>()
        val connection = FakeHostConnection(twoTextColumn())
        connection.respondWith {
            HostResponse(
                listOf(
                    Mutation.SetProp(LEFT, PropertyKind.Text, PropertyValue.Text("intermediate")),
                    Mutation.SetProp(LEFT, PropertyKind.Text, PropertyValue.Text("final")),
                ),
            )
        }
        lateinit var host: DioxusHost
        setContent {
            host = rememberDioxusHost(connection)
            DioxusContent(host)
        }
        RenderNodeObserver.onCompose = { id ->
            if (id == LEFT) seen += host.table.node(LEFT)?.text(PropertyKind.Text).orEmpty()
        }
        waitForIdle()

        host.dispatch(HostEvent.Clicked(COLUMN, 0))
        waitForIdle()

        onNodeWithTag(nodeTestTag(LEFT)).assertTextEquals("final")
        assertTrue(
            "intermediate" !in seen,
            "no intermediate state of a batch may reach the screen, saw $seen",
        )
    }

    @Test
    fun fr3_one_click_produces_exactly_one_clicked_event() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(BUTTON, WidgetKind.Button),
                Mutation.SetProp(BUTTON, PropertyKind.Text, PropertyValue.Text("go")),
                Mutation.SetProp(
                    BUTTON,
                    PropertyKind.OnClick,
                    PropertyValue.Integer(CLICK_HANDLER),
                ),
            ),
        )
        setContent { DioxusContent(rememberDioxusHost(connection)) }

        onNodeWithTag(nodeTestTag(BUTTON)).performClick()
        waitForIdle()

        assertEquals(
            listOf(HostEvent.Clicked(BUTTON, CLICK_HANDLER)),
            connection.nodeEvents,
        )
    }

    @Test
    fun fr10_modifier_list_rebuilds_the_compose_chain() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(COLUMN, WidgetKind.Box),
                Mutation.SetModifier(COLUMN, 0, ProtocolModifier.Size(40f, 20f)),
            ),
        )
        setContent { DioxusContent(rememberDioxusHost(connection)) }

        onNodeWithTag(nodeTestTag(COLUMN)).assertWidthIsEqualTo(40.dp)
        onNodeWithTag(nodeTestTag(COLUMN)).assertHeightIsEqualTo(20.dp)
    }

    @Test
    fun fr12_handler_result_is_returned_to_compose() = runComposeUiTest {
        val connection = FakeHostConnection(twoTextColumn())
        connection.respondWith { HostResponse(result = 1) }
        lateinit var host: DioxusHost
        setContent { host = rememberDioxusHost(connection) ; DioxusContent(host) }

        assertTrue(host.dispatch(HostEvent.Clicked(COLUMN, CLICK_HANDLER)), "result 1 is consumed")

        connection.respondWith { HostResponse(result = 0) }
        assertTrue(
            !host.dispatch(HostEvent.Clicked(COLUMN, CLICK_HANDLER)),
            "result 0 is not consumed",
        )
    }
}
