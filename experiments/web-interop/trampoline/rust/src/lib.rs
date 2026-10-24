//! Rust half of the web-interop trampoline experiment.
//!
//! The module exports a few `extern "C"` functions with the flat, primitives
//! only surface the real boundary allows, and it reads/writes a fixed-layout arena in
//! linear memory in place, the way the mutation batch is read.
//!
//! Two build configurations are produced (see `../build-rust.sh`):
//!   * `owner`    - the module defines and exports its own `memory`.
//!   * `importer` - the module imports `env.memory` from another module.
//!
//! The arena logic itself is plain Rust over a slice so it can be unit tested
//! on the host target.

#![cfg_attr(target_arch = "wasm32", no_std)]

/// Arena size in bytes, matching the harness.
pub const ARENA_LEN: usize = 64 * 1024;

/// Sum of the `i32` records in `words`.
///
/// This is the "read the batch the other side wrote" direction of the batch.
pub fn sum_words(words: &[i32]) -> i32 {
    let mut acc: i32 = 0;
    let mut i = 0;
    while i < words.len() {
        acc = acc.wrapping_add(words[i]);
        i += 1;
    }
    acc
}

/// Fill `words` with a fixed-layout ramp starting at `seed`.
pub fn fill_words(words: &mut [i32], seed: i32) {
    let mut i = 0;
    while i < words.len() {
        words[i] = seed.wrapping_add(i as i32);
        i += 1;
    }
}

// ---------------------------------------------------------------------------
// Boundary exports
// ---------------------------------------------------------------------------

/// Cheapest possible call: used to measure raw call overhead.
#[unsafe(no_mangle)]
pub extern "C" fn compose_rust_trampoline_add(a: i32, b: i32) -> i32 {
    a.wrapping_add(b)
}

/// Sum `len` `i32` words at byte offset `ptr` of linear memory.
///
/// # Safety
/// `ptr` must be 4-byte aligned and `[ptr, ptr + len * 4)` must be in bounds.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn compose_rust_trampoline_sum(ptr: *const i32, len: i32) -> i32 {
    let words = unsafe { core::slice::from_raw_parts(ptr, len as usize) };
    sum_words(words)
}

/// Write a fixed-layout ramp of `len` `i32` words at byte offset `ptr`.
///
/// # Safety
/// Same as [`compose_rust_trampoline_sum`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn compose_rust_trampoline_fill(ptr: *mut i32, len: i32, seed: i32) {
    let words = unsafe { core::slice::from_raw_parts_mut(ptr, len as usize) };
    fill_words(words, seed);
}

// ---------------------------------------------------------------------------
// Imports: the other half of the boundary
// ---------------------------------------------------------------------------

#[cfg(target_arch = "wasm32")]
mod peer {
    // Bound directly to a Kotlin/Wasm `@WasmExport` at instantiation.
    #[link(wasm_import_module = "peer")]
    unsafe extern "C" {
        pub fn peer_add(a: i32, b: i32) -> i32;
    }

    // Bound to a JS function that forwards to the same Kotlin export.
    #[link(wasm_import_module = "jsshim")]
    unsafe extern "C" {
        pub fn shim_add(a: i32, b: i32) -> i32;
    }
}

/// Loop body with no call at all: the benchmark baseline.
#[cfg(target_arch = "wasm32")]
#[unsafe(no_mangle)]
pub extern "C" fn compose_rust_trampoline_bench_local(n: i32) -> i32 {
    let mut acc: i32 = 0;
    let mut i = 0;
    while i < n {
        acc = acc.wrapping_add(i).wrapping_add(1);
        i += 1;
    }
    acc
}

/// `n` direct wasm->wasm calls into the peer module.
#[cfg(target_arch = "wasm32")]
#[unsafe(no_mangle)]
pub extern "C" fn compose_rust_trampoline_bench_peer(n: i32) -> i32 {
    let mut acc: i32 = 0;
    let mut i = 0;
    while i < n {
        acc = acc.wrapping_add(unsafe { peer::peer_add(i, 1) });
        i += 1;
    }
    acc
}

/// `n` calls into the peer module through a JS shim.
#[cfg(target_arch = "wasm32")]
#[unsafe(no_mangle)]
pub extern "C" fn compose_rust_trampoline_bench_shim(n: i32) -> i32 {
    let mut acc: i32 = 0;
    let mut i = 0;
    while i < n {
        acc = acc.wrapping_add(unsafe { peer::shim_add(i, 1) });
        i += 1;
    }
    acc
}

/// Static arena used when this module owns the memory.
#[cfg(target_arch = "wasm32")]
static mut ARENA: [u8; ARENA_LEN] = [0; ARENA_LEN];

/// Byte offset of the arena inside linear memory.
#[cfg(target_arch = "wasm32")]
#[unsafe(no_mangle)]
pub extern "C" fn compose_rust_trampoline_arena_ptr() -> i32 {
    core::ptr::addr_of!(ARENA) as i32
}

/// Length of the arena in bytes.
#[unsafe(no_mangle)]
pub extern "C" fn compose_rust_trampoline_arena_len() -> i32 {
    ARENA_LEN as i32
}

#[cfg(target_arch = "wasm32")]
#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pr6_sum_words_adds_every_record() {
        assert_eq!(sum_words(&[1, 2, 3, 4]), 10);
        assert_eq!(sum_words(&[]), 0);
    }

    #[test]
    fn pr6_sum_words_wraps_instead_of_panicking() {
        assert_eq!(sum_words(&[i32::MAX, 1]), i32::MIN);
    }

    #[test]
    fn pr6_fill_words_writes_a_fixed_layout_ramp() {
        let mut buf = [0i32; 4];
        fill_words(&mut buf, 10);
        assert_eq!(buf, [10, 11, 12, 13]);
    }

    #[test]
    fn pr6_fill_then_sum_round_trips() {
        let mut buf = [0i32; 8];
        fill_words(&mut buf, 0);
        assert_eq!(sum_words(&buf), 28);
    }
}
