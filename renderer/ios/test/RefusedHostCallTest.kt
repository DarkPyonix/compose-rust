package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.HostEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Kotlin/Native connection, the one the macOS and iOS renderers use, once printed a
 * refused dispatch as "compose_rust_host_dispatch_event returned -1" and nothing else. An
 * application that logged two of those at its first frame could not say which event the
 * Host had refused.
 */
class RefusedHostCallTest {
    @Test
    fun nfr7_a_refused_dispatch_names_the_event_its_node_and_its_handler() {
        assertEquals(
            "compose_rust_host_dispatch_event returned -1 for Clicked(nodeId=42, handlerId=7)",
            refusedHostCallMessage("dispatch_event", -1, HostEvent.Clicked(nodeId = 42, handlerId = 7)),
        )
    }

    @Test
    fun nfr7_a_refused_call_without_an_event_says_the_status_alone() {
        assertEquals(
            "compose_rust_host_render_frame returned -2",
            refusedHostCallMessage("render_frame", -2, null),
        )
    }
}
