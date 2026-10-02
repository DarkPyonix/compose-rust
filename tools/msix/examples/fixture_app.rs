//! A stand-in application for proving the packaging pipeline when no application build
//! is at hand: it opens a window (a message box) and stays up until it is closed, which
//! is all the certification kit's launch checks ask of a program.
//!
//! Built for Windows with the GUI subsystem so that no console window appears; on other
//! hosts it only prints, so the example still compiles in the workspace's checks.

#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(windows)]
mod win {
    use std::ffi::c_void;

    #[link(name = "user32")]
    unsafe extern "system" {
        fn MessageBoxW(hwnd: *mut c_void, text: *const u16, caption: *const u16, kind: u32) -> i32;
    }

    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }

    pub fn show() {
        let text = wide("This program stands in for an application while its package is checked.");
        let caption = wide("dioxus-compose packaging fixture");
        // SAFETY: both strings are NUL-terminated UTF-16 that outlive the call, and a
        // null owner window is allowed.
        unsafe {
            MessageBoxW(std::ptr::null_mut(), text.as_ptr(), caption.as_ptr(), 0x40);
        }
    }
}

fn main() {
    #[cfg(windows)]
    win::show();
    #[cfg(not(windows))]
    println!("the packaging fixture only shows a window on Windows");
}
