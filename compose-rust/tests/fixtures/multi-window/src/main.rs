//! Two windows in one process, through the Rust API: `LaunchBuilder::windows()` and two
//! `Window` nodes that share their state. The application is in `app.rs`.
//!
//! Run with no argument for a person: both windows open and stay until they are closed, and
//! every click, keystroke, focus change and close prints an `event:` line. The process ends
//! when the last window closes. README.md beside this says what to check.
//!
//! `--windows N` opens N windows at the start instead of two, from 1 to 3, for the idle and
//! memory measurement in `scripts/parity/macos-windows.sh`.
//!
//! `--check` runs the same windows without a person, for `scripts/check-windows-consumer.sh`:
//! it waits until both windows are laid out, writes the shared state, closes the second
//! window, closes the first, and exits 0 only if the renderer loop then ended by itself with
//! every step reached. Each step prints `compose-rust: <name>: ok` on standard error.

mod app;

use compose_rust::LaunchBuilder;
use compose_rust::boundary::STATUS_OK;
use compose_rust::schema::LastWindowClosed;
use std::ffi::c_int;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

/// How long the scripted run may take to open both windows and close them.
const CHECK_TIMEOUT: Duration = Duration::from_secs(180);

/// How long the process may outlive its last window before that counts as not exiting.
const EXIT_TIMEOUT: Duration = Duration::from_secs(30);

unsafe extern "C" {
    /// Ends the process without running exit handlers. The renderer's runtime is still
    /// running on the main thread, and handlers that tear it down from this one could hang
    /// rather than end anything.
    fn _exit(status: c_int) -> !;
}

fn fail_now(message: String) -> ! {
    eprintln!("compose-rust: {message}");
    let _ = std::io::Write::flush(&mut std::io::stdout());
    let _ = std::io::Write::flush(&mut std::io::stderr());
    // SAFETY: ends the process; nothing after it runs.
    unsafe { _exit(1) }
}

/// Ends the process with a failure if the scripted run stops short, or if the renderer keeps
/// running once the last window is gone.
fn watch() {
    std::thread::spawn(|| {
        let started = Instant::now();
        let mut last_closed_at = None;
        loop {
            let step = app::STEP.load(Ordering::SeqCst);
            if step >= app::LAST_CLOSED {
                let at = *last_closed_at.get_or_insert_with(Instant::now);
                if at.elapsed() > EXIT_TIMEOUT {
                    fail_now(format!(
                        "{}: the last window was removed {}s ago and the renderer loop is \
                         still running",
                        app::STEP_NAMES[3],
                        EXIT_TIMEOUT.as_secs()
                    ));
                }
            } else if started.elapsed() > CHECK_TIMEOUT {
                fail_now(format!(
                    "{}: not reached in {}s; the steps before it that printed ok are the ones \
                     that passed",
                    app::STEP_NAMES[step as usize],
                    CHECK_TIMEOUT.as_secs()
                ));
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    });
}

/// The number after `--windows`, for a measurement run that opens one or three windows.
fn windows_asked() -> Option<u32> {
    let arguments: Vec<String> = std::env::args().collect();
    let at = arguments.iter().position(|argument| argument == "--windows")?;
    let count = arguments.get(at + 1)?.parse::<u32>().ok()?;
    (1..=app::MAX_WINDOWS as u32).contains(&count).then_some(count)
}

fn main() {
    if let Some(count) = windows_asked() {
        app::WINDOWS.store(count, Ordering::SeqCst);
    }
    let builder = LaunchBuilder::new()
        .windows()
        .on_last_window_closed(LastWindowClosed::Exit);
    if !std::env::args().any(|argument| argument == "--check") {
        builder.launch(app::app);
        return;
    }
    app::CHECK.store(true, Ordering::SeqCst);
    watch();
    let status = builder.try_launch(app::app);
    let step = app::STEP.load(Ordering::SeqCst);
    if status != STATUS_OK {
        fail_now(format!("the renderer loop ended with status {status}"));
    }
    if step < app::LAST_CLOSED {
        fail_now(format!(
            "{}: the renderer loop ended before it, with a window still open",
            app::STEP_NAMES[step as usize]
        ));
    }
    eprintln!("compose-rust: {}: ok", app::STEP_NAMES[3]);
}
