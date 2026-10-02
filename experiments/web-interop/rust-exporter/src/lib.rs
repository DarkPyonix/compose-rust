//! Web-interop experiment: the Rust (Host) side of a two-module WebAssembly link.
//!
//! This crate plays the role the `dioxus-compose` Host would play on the Web
//! target. It owns a fixed-layout arena in linear memory, read in place with no
//! serialisation or copying, and exports the
//! boundary functions the Renderer module is supposed to import directly,
//! without a JavaScript frame on the call path.
//!
//! Two artifacts are built from this one source (see `build.sh`):
//!   * `host_owns_memory.wasm`  - the module defines and exports `memory`.
//!   * `host_imports_memory.wasm` - built with `-Clink-arg=--import-memory`, so
//!     the module imports `env.memory` instead. This is the variant needed if
//!     the *other* module turns out to be the only one able to own the memory.

#![no_std]

use core::panic::PanicInfo;

#[panic_handler]
fn panic(_: &PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}

/// The protocol arena. Fixed size, reused every frame, never reallocated, so a
/// steady-state frame does no allocation at all.
const ARENA_LEN: usize = 64 * 1024;
static mut ARENA: [u8; ARENA_LEN] = [0; ARENA_LEN];

/// Byte offset of the arena inside the shared linear memory. The Renderer needs
/// this to build its view; it is a plain integer, not a pointer object.
#[unsafe(no_mangle)]
pub extern "C" fn arena_ptr() -> u32 {
    &raw const ARENA as *const u8 as u32
}

#[unsafe(no_mangle)]
pub extern "C" fn arena_len() -> u32 {
    ARENA_LEN as u32
}

/// The cheapest possible boundary call: one i32 in, one i32 out, no memory
/// traffic. This is what the direct-vs-JS-shim benchmark measures.
#[unsafe(no_mangle)]
pub extern "C" fn bump(x: i32) -> i32 {
    x.wrapping_add(1)
}

/// A boundary call that touches the arena, standing in for `render_frame`:
/// writes `len` bytes of a deterministic pattern and returns their checksum.
#[unsafe(no_mangle)]
pub extern "C" fn write_pattern(len: u32, seed: u32) -> u32 {
    let len = core::cmp::min(len as usize, ARENA_LEN);
    let arena = &raw mut ARENA as *mut u8;
    let mut sum: u32 = 0;
    for i in 0..len {
        let b = (seed.wrapping_add(i as u32) & 0xff) as u8;
        unsafe { arena.add(i).write(b) };
        sum = sum.wrapping_add(b as u32);
    }
    sum
}

/// Reads back `len` bytes of the arena, standing in for the Host consuming an
/// event record the Renderer wrote in place.
#[unsafe(no_mangle)]
pub extern "C" fn checksum(len: u32) -> u32 {
    let len = core::cmp::min(len as usize, ARENA_LEN);
    let arena = &raw const ARENA as *const u8;
    let mut sum: u32 = 0;
    for i in 0..len {
        sum = sum.wrapping_add(unsafe { arena.add(i).read() } as u32);
    }
    sum
}

// --- The Renderer -> Host direction is above; below is Host -> Renderer. ---

// A function the *Renderer* module is expected to export and that this module
// imports directly. If two-module linking works in both directions, JS only
// supplies this at instantiation time.
#[link(wasm_import_module = "renderer")]
unsafe extern "C" {
    fn renderer_bump(x: i32) -> i32;
}

/// Calls into the Renderer `n` times so the Host -> Renderer direction can be
/// timed from inside wasm, with no JS frame anywhere in the loop.
#[unsafe(no_mangle)]
pub extern "C" fn call_renderer_n(n: u32, x: i32) -> i32 {
    let mut acc = x;
    for _ in 0..n {
        acc = unsafe { renderer_bump(acc) };
    }
    acc
}
