package dioxus.compose.ui.platform

import dioxus.compose.tooling.m0DemoHost

/**
 * JVM entry point for collecting reachability metadata with the GraalVM tracing agent.
 * See `scripts/collect-metadata.sh`.
 *
 * A scripted Host drives it: a JVM run has no Rust executable to resolve the
 * `dioxus_compose_host_*` symbols against, and `NativeHostConnection` needs GraalVM word
 * types that exist only inside a native image.
 */
fun main() {
    runRenderer(System.getenv("DIOXUS_COMPOSE_AUTOEXIT_MS")?.toLongOrNull()) { m0DemoHost() }
}
