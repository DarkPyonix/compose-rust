package dioxus.compose.ui.platform

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
            "dioxus_compose_host_init",
            "dioxus_compose_host_dispatch_event",
            "dioxus_compose_host_render_frame",
            "dioxus_compose_host_release_batch",
            "dioxus_compose_host_shutdown",
        )) {
            assertNull(linkedHostFunction(name), "$name resolved in an executable with no Host")
        }
    }

    @Test
    fun nfr15_a_name_that_is_not_a_host_function_is_absent() {
        assertNull(linkedHostFunction("dioxus_compose_host_unknown"))
        assertNull(linkedHostFunction(""))
    }
}
