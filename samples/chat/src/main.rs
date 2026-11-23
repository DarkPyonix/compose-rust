//! The desktop program. Everything it draws is in the library beside this, because the
//! same screens have to be reachable from an Android Activity and from a browser page,
//! and neither of those has a `main` to put them in.

// A window application rather than a console one.
//
// Without this the executable is linked for the console subsystem, so double clicking it
// opens a terminal beside the window and closing that terminal kills it. It has to be said
// by the application: a library cannot say it on an application's behalf, because Cargo
// does not pass a dependency's link arguments on.
//
// It costs no diagnostics. The renderer attaches to a parent console at startup, so run
// from a terminal this still writes there, and run from Explorer there is no terminal to
// open.
#![windows_subsystem = "windows"]

fn main() {
    // Velopack's installer and updater start the program with hook arguments and wait for
    // it to answer; this answers them and exits before any window opens.
    #[cfg(not(any(target_os = "android", target_os = "ios", target_family = "wasm")))]
    velopack::VelopackApp::build().run();
    sample_chat::launch();
}
