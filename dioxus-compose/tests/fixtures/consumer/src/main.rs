//! An application that depends on `compose-rust` and nothing else.
//!
//! Run with `--launch` to open the window. Without it the program returns as soon as it
//! starts, which is what `scripts/tests/consumer-crate.test.sh` wants: by the time `main`
//! runs, the loader has already had to find the renderer, resolve it, and bind the
//! `dioxus_compose_host_*` symbols it calls back into. Those are the three things that
//! used to fail, and all three happen before the first line of this function.
//!
//! `--self-check` opens the window as well and exits 0 only if the renderer drew. Starting
//! is not enough to prove that: on Linux the renderer looks the Host's functions up in the
//! executable only once it runs, so an application that started cleanly could still die
//! with "undefined symbol" the moment the window came up. Set
//! `DIOXUS_COMPOSE_AUTOEXIT_MS` so the window closes itself.
//!
//! The call to `launch` stays in the binary because the branch is decided at run time.
//! That is what makes the renderer a load-time dependency of this executable rather than
//! a library the linker drops for being unused.

use compose_rust::prelude::*;
use std::sync::atomic::{AtomicU32, Ordering};

fn app() -> Element {
    rsx! {
        Column {
            Text { text: "A consumer of dioxus-compose." }
        }
    }
}

/// The highest step the self-check reached. Read after the renderer loop has ended.
static STEP: AtomicU32 = AtomicU32::new(0);

/// How many frames the renderer has to have driven before the check passes.
///
/// Each step after the first is rendered inside `dioxus_compose_host_render_frame`, which
/// the renderer calls only from its window's frame clock, and the next step is asked for
/// while that frame is being produced. So reaching step 2 means the renderer applied step
/// 1's changes, drew that frame, and came back for another one: a whole frame went
/// through, not only the start of one.
const FRAMES: u32 = 2;

fn self_check() -> Element {
    let mut step = use_signal(|| 0_u32);
    let current = step();
    STEP.fetch_max(current, Ordering::SeqCst);
    if current < FRAMES {
        // A task rather than a write during render, so the change reaches the screen the
        // way any application's does: the Host asks the renderer for a frame and renders
        // it when the renderer calls back.
        dioxus_core::spawn(async move {
            step.set(current + 1);
        });
    }
    rsx! {
        Column {
            Text { text: format!("self-check frame {current} of {FRAMES}") }
        }
    }
}

fn main() {
    if std::env::args().any(|argument| argument == "--self-check") {
        let status = LaunchBuilder::new().try_launch(self_check);
        let reached = STEP.load(Ordering::SeqCst);
        if status != compose_rust::boundary::STATUS_OK {
            eprintln!("self-check: the renderer loop ended with status {status}");
            std::process::exit(1);
        }
        if reached < FRAMES {
            eprintln!(
                "self-check: the renderer drove {reached} of {FRAMES} frames before the \
                 window closed. It started and did not draw; its own output above says why."
            );
            std::process::exit(1);
        }
        println!("self-check: the renderer drew {reached} frames");
        return;
    }
    if std::env::args().any(|argument| argument == "--launch") {
        launch(app);
        return;
    }
    println!("the renderer was loaded and this program started");
}
