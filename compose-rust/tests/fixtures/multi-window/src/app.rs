//! Two windows of one application, sharing their state.
//!
//! This file is the whole application. `main.rs` launches it, and
//! `compose-rust/tests/fr53_windows_app.rs` includes it to drive the same composable through a
//! Host in a test, so what the Windows check runs and what the test proves are one program.
//!
//! Each window shows the same three things, all held once at the root and read by both:
//!
//! - a click count and a button that adds one to it;
//! - a text field, and under it what was last typed into either window's field;
//! - a button that closes this window.
//!
//! The first window also offers to open the second again once it is closed. Closing a window,
//! from its button or from the title bar, removes its `Window` node; the platform window goes
//! with it. When the last one goes the application ends.
//!
//! With [`CHECK`] set, an effect at the root drives the run instead of a person: it waits for
//! both windows to be laid out, writes the shared state, closes the second window, writes the
//! state again for the first one alone, then closes the first. [`STEP`] says how far it got,
//! which `main` reads once the renderer loop has ended.

#![allow(dead_code)]

use compose_rust::runtime::*;
use compose_rust::ui::*;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

/// Set before launch for the scripted run.
pub static CHECK: AtomicBool = AtomicBool::new(false);

/// How far the scripted run got. Each step is reached only after the one before it.
pub static STEP: AtomicU32 = AtomicU32::new(0);
/// Both windows reported a size, so the renderer laid out each of them.
pub const BOTH_OPEN: u32 = 1;
/// Both windows composed the count the effect wrote once.
pub const SHARED: u32 = 2;
/// The second window was removed and frames still came for the first, which composed the
/// count written after that.
pub const SECOND_CLOSED: u32 = 3;
/// The first window, the last one, was removed. The renderer loop is to end now, and `main`
/// prints this step's line only once it has.
pub const LAST_CLOSED: u32 = 4;

/// What each step is called in the check's output, one `compose-rust: <name>: ok` line each.
pub const STEP_NAMES: [&str; 4] = [
    "fr53_windows_app_opens_two_windows",
    "fr53_windows_app_windows_share_state",
    "fr53_windows_app_closing_one_window_keeps_the_other",
    "fr53_windows_app_exits_after_the_last_window",
];

/// The most windows the application can open: two, and a third for a measurement run.
pub const MAX_WINDOWS: usize = 3;

/// How many windows open at the start, from 1 to [`MAX_WINDOWS`]. Two unless `main` was asked
/// for another number, which `scripts/parity/macos-windows.sh` does to measure one window
/// against three.
pub static WINDOWS: AtomicU32 = AtomicU32::new(2);

/// The titles of the windows, which a person and a screen reader see.
pub const TITLES: [&str; MAX_WINDOWS] = [
    "compose-rust first window",
    "compose-rust second window",
    "compose-rust third window",
];

/// The size each window opens at, in dp: wide enough to be a Medium window, so its first
/// report differs from what a window that was never measured holds.
pub const WINDOW_SIZE: (f32, f32) = (640.0, 520.0);

/// Whether each window has reported a size.
static MEASURED: [AtomicBool; MAX_WINDOWS] = [
    AtomicBool::new(false),
    AtomicBool::new(false),
    AtomicBool::new(false),
];
/// The click count each window last composed.
static SEEN: [AtomicU32; MAX_WINDOWS] = [AtomicU32::new(0), AtomicU32::new(0), AtomicU32::new(0)];

/// Forgets what an earlier run recorded, for a test that runs the application twice.
pub fn reset() {
    STEP.store(0, Ordering::SeqCst);
    for (measured, seen) in MEASURED.iter().zip(&SEEN) {
        measured.store(false, Ordering::SeqCst);
        seen.store(0, Ordering::SeqCst);
    }
}

fn reached(step: u32) {
    STEP.store(step, Ordering::SeqCst);
    eprintln!("compose-rust: {}: ok", STEP_NAMES[step as usize - 1]);
}

/// The application: two `Window` nodes at the root and the state they share.
#[Composable]
pub fn app() {
    let clicks = remember(|| mutable_state_of(0_u32));
    let typed = remember(|| mutable_state_of(String::new()));
    let windows = WINDOWS.load(Ordering::SeqCst) as usize;
    let open: [MutableState<bool>; MAX_WINDOWS] = [
        remember(|| mutable_state_of(windows > 0)),
        remember(|| mutable_state_of(windows > 1)),
        remember(|| mutable_state_of(windows > 2)),
    ];
    for (index, shown) in open.iter().enumerate() {
        if shown.get() {
            key(index, || window(index, clicks, typed, open));
        }
    }
    if CHECK.load(Ordering::SeqCst) {
        launched_effect((), move || scripted(clicks, open));
    }
}

/// One window. Every window reads the same states, so a change made in one is in the batch
/// that redraws both.
fn window(
    index: usize,
    clicks: MutableState<u32>,
    typed: MutableState<String>,
    open: [MutableState<bool>; MAX_WINDOWS],
) {
    let close = open[index];
    Window(move || {
        println!("event: window {} asked to close", index + 1);
        close.set(false);
    })
    .title(TITLES[index])
    .size(WINDOW_SIZE.0, WINDOW_SIZE.1)
    .min_size(360.0, 360.0)
    .report_size(true)
    .on_focus_changed(move |focused| {
        println!("event: window {} focused = {focused}", index + 1);
    })
    .content(move || {
        let size = current_window_size();
        if size.width_dp > 0.0 {
            MEASURED[index].store(true, Ordering::SeqCst);
        }
        let count = clicks.get();
        SEEN[index].store(count, Ordering::SeqCst);
        Column()
            .modifier(Modifier.padding(16.0).fill_max_width())
            .space_role(SpaceRole::Sm)
            .content(move || {
                Text(TITLES[index]).type_role(TypeRole::Title);
                Text(format!("Clicks, shared by both windows: {count}"));
                Button("Add one").on_click(move || {
                    clicks.update(|clicks| *clicks += 1);
                    println!("event: window {} added a click", index + 1);
                });
                TextField()
                    .placeholder(format!("Type in window {} (Korean too)", index + 1))
                    .on_value_change(move |text| {
                        println!("event: window {} field = {text}", index + 1);
                        typed.set(text);
                    });
                Text(format!("Typed in either window: {}", typed.get()));
                Text(format!(
                    "This window: {:.0} x {:.0} dp",
                    size.width_dp, size.height_dp
                ));
                Button("Close this window").on_click(move || {
                    println!("event: window {} closed from its button", index + 1);
                    close.set(false);
                });
                if index == 0 && !open[1].get() {
                    let second = open[1];
                    Button("Open the second window").on_click(move || {
                        println!("event: the second window opened again");
                        second.set(true);
                    });
                }
            });
    });
}

/// Waits for frames until `done` holds. Each wait asks for a frame, so the renderer keeps
/// serving them for as long as a window is open.
async fn frames_until(done: impl Fn() -> bool) {
    while !done() {
        with_frame_nanos(|_| ()).await;
    }
}

/// The scripted run: what a person does in the hand check, without the person.
async fn scripted(clicks: MutableState<u32>, open: [MutableState<bool>; MAX_WINDOWS]) {
    let measured = |index: usize| MEASURED[index].load(Ordering::SeqCst);
    let seen = |index: usize| SEEN[index].load(Ordering::SeqCst);

    frames_until(|| measured(0) && measured(1)).await;
    reached(BOTH_OPEN);

    clicks.set(1);
    frames_until(|| seen(0) == 1 && seen(1) == 1).await;
    reached(SHARED);

    open[1].set(false);
    // A frame after the removal was applied means the loop outlived the second window.
    with_frame_nanos(|_| ()).await;
    clicks.set(2);
    frames_until(|| seen(0) == 2).await;
    with_frame_nanos(|_| ()).await;
    if seen(1) != 1 {
        eprintln!(
            "compose-rust: the second window composed count {} after it was closed",
            seen(1)
        );
        return;
    }
    reached(SECOND_CLOSED);

    // Whether the loop then ends is for `main` to say, once the launch has returned.
    STEP.store(LAST_CLOSED, Ordering::SeqCst);
    eprintln!("compose-rust: removed the last window");
    open[0].set(false);
}
