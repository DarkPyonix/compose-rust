//! The desktop program. Everything it draws is in the library beside this.

// A window application rather than a console one; see samples/minimal for why.
#![windows_subsystem = "windows"]

fn main() {
    sample_minimal_compose::launch();
}
