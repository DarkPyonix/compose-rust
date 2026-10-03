//! The application the self-update rehearsal installs and watches update itself.
//!
//! It shows its own version, offers "Check for Updates...", and appends one line per
//! launch to `self-update-demo.log` beside its bundle, which is how the rehearsal tells
//! the version that was installed from the one that relaunched in its place.
//!
//! Environment, read at launch (a relaunch by Sparkle has none of it, and gets the
//! defaults):
//!
//! - `DEMO_INSTALL=on-quit` waits for the application to quit before installing, which is
//!   Sparkle's default; otherwise an update found in the background is installed at once.
//! - `DEMO_CHECK_AFTER_SECS=n` checks in the background after n seconds rather than at
//!   launch, so the old version can be seen first.
//! - `DEMO_QUIT_AFTER_SECS=n` quits after n seconds the way the Quit menu item does.

#![windows_subsystem = "windows"]

use dioxus_compose::prelude::*;

const VERSION: &str = match option_env!("DEMO_VERSION") {
    Some(version) => version,
    None => env!("CARGO_PKG_VERSION"),
};

fn app() -> Element {
    let title = format!("Self Update Demo {VERSION}");
    let channel = if cfg!(feature = "sparkle") {
        "Updates itself with Sparkle."
    } else {
        "Does not update itself."
    };
    rsx! {
        Column {
            fill_max_width: true,
            Text { text: title }
            Text { text: channel }
            Button {
                text: "Check for Updates...",
                on_click: move |_| check_for_updates(),
            }
        }
    }
}

fn check_for_updates() {
    #[cfg(feature = "sparkle")]
    dioxus_compose_update::check_for_updates();
}

#[cfg(feature = "sparkle")]
fn seconds_from(name: &str) -> Option<u64> {
    std::env::var(name).ok()?.parse().ok()
}

/// One line per launch, beside the bundle, so a relaunch can be told from the original.
fn record_launch() {
    let Ok(executable) = std::env::current_exe() else {
        return;
    };
    // Name.app/Contents/MacOS/<executable>: four levels up is the directory holding the app.
    let Some(beside) = executable.ancestors().nth(4) else {
        return;
    };
    let line = format!(
        "version {VERSION} started, pid {}, from {}\n",
        std::process::id(),
        executable.display()
    );
    let _ = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(beside.join("self-update-demo.log"))
        .and_then(|mut file| std::io::Write::write_all(&mut file, line.as_bytes()));
    eprint!("self-update-demo: {line}");
}

#[cfg(feature = "sparkle")]
fn start_updates() {
    use dioxus_compose_update::{Install, UpdaterOptions, check_in_background, start};

    let install = match std::env::var("DEMO_INSTALL").as_deref() {
        Ok("on-quit") => Install::OnQuit,
        _ => Install::Immediately,
    };
    let delay = seconds_from("DEMO_CHECK_AFTER_SECS");
    let options = UpdaterOptions {
        check_on_start: delay.is_none(),
        install,
    };
    if let Err(error) = start(options) {
        eprintln!("self-update-demo: updates are off: {error}");
        return;
    }
    if let Some(seconds) = delay {
        // A Host worker thread: the request itself is queued to the main thread.
        std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_secs(seconds));
            check_in_background();
        });
    }
}

/// `[NSApp terminate:nil]` on the main thread, which is what the Quit menu item sends.
#[cfg(all(feature = "sparkle", target_os = "macos"))]
fn quit_after(seconds: u64) {
    use objc2::runtime::{AnyClass, AnyObject};
    use objc2::msg_send;
    use std::ffi::c_void;

    #[repr(C)]
    struct DispatchQueue {
        _opaque: [u8; 0],
    }
    unsafe extern "C" {
        static _dispatch_main_q: DispatchQueue;
        fn dispatch_async_f(
            queue: *const DispatchQueue,
            context: *mut c_void,
            work: extern "C" fn(*mut c_void),
        );
    }
    extern "C" fn terminate(_: *mut c_void) {
        let Some(class) = AnyClass::get(c"NSApplication") else {
            return;
        };
        // SAFETY: on the main thread; sharedApplication exists once the renderer is up.
        unsafe {
            let app: *mut AnyObject = msg_send![class, sharedApplication];
            let () = msg_send![app, terminate: std::ptr::null_mut::<AnyObject>()];
        }
    }
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_secs(seconds));
        eprintln!("self-update-demo: quitting as the Quit menu item would");
        // SAFETY: the main queue lives for the process, and `terminate` takes no context.
        unsafe { dispatch_async_f(&raw const _dispatch_main_q, std::ptr::null_mut(), terminate) };
    });
}

fn main() {
    record_launch();
    #[cfg(feature = "sparkle")]
    start_updates();
    #[cfg(all(feature = "sparkle", target_os = "macos"))]
    if let Some(seconds) = seconds_from("DEMO_QUIT_AFTER_SECS") {
        quit_after(seconds);
    }
    dioxus_compose::launch(app);
}
