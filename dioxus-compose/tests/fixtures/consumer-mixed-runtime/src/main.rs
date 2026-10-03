//! An application with a C++ library built for the C runtime DLL beside compose-rust.
//!
//! Runs the library's function and checks what it wrote. `--launch` opens the window, and
//! is there so the renderer stays a part of the executable the linker has to put together.

use compose_rust::prelude::*;

unsafe extern "C" {
    fn mixed_runtime_greeting(out: *mut u8, capacity: usize) -> usize;
}

fn app() -> Element {
    rsx! {
        Column {
            Text { text: "A consumer with a library built for the runtime DLL." }
        }
    }
}

fn main() {
    if std::env::args().any(|argument| argument == "--launch") {
        launch(app);
        return;
    }
    let mut buffer = [0_u8; 128];
    // SAFETY: the buffer is as long as the capacity passed, and the function writes at most
    // capacity - 1 bytes and a terminator.
    let length = unsafe { mixed_runtime_greeting(buffer.as_mut_ptr(), buffer.len()) };
    let text = String::from_utf8_lossy(&buffer[..length]);
    println!("mixed runtime: {text}");
    if text != "built for the C runtime DLL, linked into one executable" {
        eprintln!("mixed runtime: the C++ library answered something else");
        std::process::exit(1);
    }
}
