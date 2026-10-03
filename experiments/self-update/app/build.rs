//! Makes the Host's entry points visible to the renderer, for the published 0.0.0 only.
//!
//! The renderer finds `dioxus_compose_host_*` in the executable by name when it is loaded.
//! dioxus-compose 0.0.0 asked for that with link arguments in its own build script, and
//! Cargo does not pass a dependency's link arguments on to the application, so an
//! application of that version starts with "symbol not found in flat namespace
//! '_dioxus_compose_host_dispatch_event'". Later versions keep the symbols from inside the
//! crate and need none of this.

const HOST_ENTRY_POINTS: [&str; 5] = [
    "dioxus_compose_host_init",
    "dioxus_compose_host_dispatch_event",
    "dioxus_compose_host_render_frame",
    "dioxus_compose_host_release_batch",
    "dioxus_compose_host_shutdown",
];

fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("macos") {
        return;
    }
    println!("cargo:rustc-link-arg-bins=-Wl,-export_dynamic");
    for symbol in HOST_ENTRY_POINTS {
        // Kept even though nothing in the program calls them: the renderer does.
        println!("cargo:rustc-link-arg-bins=-Wl,-u,_{symbol}");
    }
}
