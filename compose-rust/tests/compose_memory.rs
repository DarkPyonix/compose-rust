//! The slot table runtime over a long run: what it holds does not grow with the frames.
//!
//! A screen that changes on every frame, a streamed reply or a field being typed into,
//! rebuilds its nodes' attributes each time. What a frame builds goes back to the runtime's
//! pool or to the allocator, so the live bytes after the thousandth frame are the live
//! bytes after the hundredth. A pool that kept one buffer per node per frame showed as
//! about 170 KB of growth a frame in the chat sample, 4.5 GB over two days of use.

use compose_rust::ComposeHost;
use compose_rust::runtime::*;
use compose_rust::ui::*;
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

struct LiveBytesAllocator;

// Per thread for the reason `compose_allocations` gives: the harness runs tests at once.
thread_local! {
    static TRACKING: Cell<bool> = const { Cell::new(false) };
    static LIVE: Cell<isize> = const { Cell::new(0) };
}

fn add(delta: isize) {
    let _ = TRACKING.try_with(|tracking| {
        if tracking.get() {
            let _ = LIVE.try_with(|live| live.set(live.get() + delta));
        }
    });
}

// SAFETY: Every operation delegates to the process System allocator unchanged.
unsafe impl GlobalAlloc for LiveBytesAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        add(layout.size() as isize);
        // SAFETY: Delegating the caller-provided layout to System.
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        add(-(layout.size() as isize));
        // SAFETY: Delegating the original pointer and layout to System.
        unsafe { System.dealloc(ptr, layout) };
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        add(size as isize - layout.size() as isize);
        // SAFETY: Delegating the original allocation and requested size to System.
        unsafe { System.realloc(ptr, layout, size) }
    }
}

#[global_allocator]
static GLOBAL: LiveBytesAllocator = LiveBytesAllocator;

fn live_bytes() -> isize {
    LIVE.with(|live| live.get())
}

#[composable]
fn Busy(count: MutableState<i32>) {
    Column().content(|| {
        for row in 0..40 {
            Row().content(|| {
                Text(format!("row {row} count {}", count.get()));
                Button("More");
            });
        }
    });
}

#[test]
fn nfr_17_live_memory_does_not_grow_with_the_frames_drawn() {
    let count = mutable_state_of(0);
    let state = count.clone();
    let mut host = ComposeHost::with_content(move || Busy(state.clone()));
    host.rebuild().unwrap();
    TRACKING.with(|tracking| tracking.set(true));
    // Warm: pools and scratch buffers reach the size this screen needs.
    for frame in 0..200u64 {
        count.set(frame as i32 + 1);
        host.render_frame(frame).unwrap();
    }
    let warm = live_bytes();
    for frame in 200..5200u64 {
        count.set(frame as i32 + 1);
        host.render_frame(frame).unwrap();
    }
    let grown = live_bytes() - warm;
    TRACKING.with(|tracking| tracking.set(false));
    // Five thousand frames of 80 nodes. Held per node per frame, this is over 500 MB.
    assert!(
        grown < 256 * 1024,
        "live memory grew by {grown} bytes over 5000 frames"
    );
}
