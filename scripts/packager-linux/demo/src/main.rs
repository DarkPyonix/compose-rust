//! A small packaged application. The packaging workflow builds it twice with different
//! versions, wraps each as an AppImage and as a Flatpak, and checks that the package
//! carries the version it was built with. A Flatpak is also updated from the first
//! version to the second with `flatpak update`.
//!
//! ```text
//! update-demo --version     print the version
//! ```

use std::process::ExitCode;

/// Set by the build, so two builds of the same source differ the way two releases do.
const VERSION: &str = match option_env!("COMPOSE_RUST_APP_VERSION") {
    Some(version) => version,
    None => env!("CARGO_PKG_VERSION"),
};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("--version") | None => {
            println!("update-demo {VERSION}");
            ExitCode::SUCCESS
        }
        Some(other) => {
            eprintln!("unknown argument {other}; the only one is --version");
            ExitCode::from(2)
        }
    }
}
