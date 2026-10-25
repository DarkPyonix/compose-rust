//! Embeds an application manifest in the stand-in program.
//!
//! The certification kit reads DPI awareness from the executable's embedded manifest. A
//! program that only declares it at run time (as the renderer does, with
//! SetProcessDpiAwarenessContext) draws correctly but is reported as not DPI aware. An
//! application that wants a clean report embeds the same manifest the same way: these
//! two linker arguments in its own build script.

fn main() {
    let manifest = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("examples")
        .join("fixture_app.manifest");
    println!("cargo:rerun-if-changed={}", manifest.display());
    let windows = std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows");
    let msvc = std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc");
    if windows && msvc {
        println!("cargo:rustc-link-arg-examples=/MANIFEST:EMBED");
        println!(
            "cargo:rustc-link-arg-examples=/MANIFESTINPUT:{}",
            manifest.display()
        );
    }
}
