//! The browser's entry point, as an application gets it from `web_main!`.

use dioxus_compose_adapter::schema::WEB_START_SYMBOL;

/// The page calls one name to start the Host it just instantiated, and that name is
/// exported by the application's module rather than by compose-rust's, because a wasm
/// module cannot be linked with an import nobody satisfies. `web_main!` is where an
/// application written with `rsx!` gets that export, so the name has to be the one the
/// page calls.
#[test]
fn pr6_web_main_exports_the_start_under_the_name_the_page_calls() {
    let lib = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/lib.rs"));
    assert!(
        lib.contains(&format!(
            "pub extern \"C\" fn {WEB_START_SYMBOL}() -> u32 {{"
        )),
        "`web_main!` has to export the start under the name the page calls"
    );
}
