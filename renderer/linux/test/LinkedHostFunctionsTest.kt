package dev.darkpyonix.composerust.ui.platform

import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The renderer's own test executable links with no Host in it, which is exactly the case
 * the weak references exist for: it has to link at all, and every Host function has to come
 * back as "not linked" rather than as an address that is not one. In an application the
 * same five names resolve to the Host, which `scripts/check-single-executable.sh` proves by
 * running one.
 */
class LinkedHostFunctionsTest {

    @Test
    fun nfr15_without_a_host_linked_every_host_function_is_absent() {
        for (name in listOf(
            "compose_rust_host_init",
            "compose_rust_host_dispatch_event",
            "compose_rust_host_render_frame",
            "compose_rust_host_release_batch",
            "compose_rust_host_shutdown",
        )) {
            assertNull(linkedHostFunction(name), "$name resolved in an executable with no Host")
        }
    }

    @Test
    fun nfr15_a_name_that_is_not_a_host_function_is_absent() {
        assertNull(linkedHostFunction("compose_rust_host_unknown"))
        assertNull(linkedHostFunction(""))
    }
}
