//! An application that depends on `dioxus-compose` and nothing else.
//!
//! Run with `--launch` to open the window. Without it the program returns as soon as it
//! starts, which is what `scripts/tests/consumer-crate.test.sh` wants: by the time `main`
//! runs, the loader has already had to find the renderer, resolve it, and bind the
//! `dioxus_compose_host_*` symbols it calls back into. Those are the three things that
//! used to fail, and all three happen before the first line of this function.
//!
//! The call to `launch` stays in the binary because the branch is decided at run time.
//! That is what makes the renderer a load-time dependency of this executable rather than
//! a library the linker drops for being unused.

use dioxus_compose::prelude::*;

fn app() -> Element {
    rsx! {
        Column {
            Text { text: "A consumer of dioxus-compose." }
        }
    }
}

fn main() {
    if std::env::args().any(|argument| argument == "--launch") {
        launch(app);
        return;
    }
    println!("the renderer was loaded and this program started");
}
