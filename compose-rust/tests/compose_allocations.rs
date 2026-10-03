//! The slot table runtime at rest: a frame with nothing to do touches no allocator.
//!
//! A frame is asked for whenever anything might have changed, and most of them find that
//! nothing did. Those frames are the ones a screen spends most of its life serving, so
//! they have to cost a few branches and no heap.

use compose_rust::ComposeHost;
use compose_rust::runtime::*;
use compose_rust::ui::*;
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

struct CountingAllocator;

// Counting is per thread, not per process: the test harness runs tests concurrently, so a
// global counter would attribute other tests' allocations to whichever measurement is open.
thread_local! {
    static TRACKING: Cell<bool> = const { Cell::new(false) };
    static ALLOCATIONS: Cell<usize> = const { Cell::new(0) };
}

fn record_allocation() {
    // `try_with` because a thread tearing down its locals must not re-enter them.
    let _ = TRACKING.try_with(|tracking| {
        if tracking.get() {
            let _ = ALLOCATIONS.try_with(|count| count.set(count.get() + 1));
        }
    });
}

// SAFETY: Every operation delegates to the process System allocator unchanged.
unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        record_allocation();
        // SAFETY: Delegating the caller-provided layout to System.
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        // SAFETY: Delegating the original pointer and layout to System.
        unsafe { System.dealloc(ptr, layout) };
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        record_allocation();
        // SAFETY: Delegating the original allocation and requested size to System.
        unsafe { System.realloc(ptr, layout, size) }
    }
}

#[global_allocator]
static GLOBAL: CountingAllocator = CountingAllocator;

struct AllocationMeasurement;

impl AllocationMeasurement {
    fn start() -> Self {
        ALLOCATIONS.with(|count| count.set(0));
        TRACKING.with(|tracking| tracking.set(true));
        Self
    }

    fn finish(self) -> usize {
        TRACKING.with(|tracking| tracking.set(false));
        ALLOCATIONS.with(|count| count.get())
    }
}

#[composable]
fn Steady(count: MutableState<i32>) {
    Column().content(|| {
        Text(format!("{}", count.get()));
        Button("More");
    });
}

#[test]
fn fr39_a_frame_with_nothing_to_do_allocates_nothing() {
    let count = mutable_state_of(0);
    let state = count.clone();
    let mut host = ComposeHost::with_content(move || Steady(state.clone()));
    host.rebuild().unwrap();
    // Warm: the first frames size the scratch buffers the later ones reuse.
    for frame in 0..4 {
        host.render_frame(frame).unwrap();
    }
    let measurement = AllocationMeasurement::start();
    for frame in 4..64 {
        host.render_frame(frame).unwrap();
    }
    let allocations = measurement.finish();
    assert_eq!(
        allocations, 0,
        "sixty idle frames allocated {allocations} times"
    );

    // An equal write changes nothing, and so costs nothing either.
    let measurement = AllocationMeasurement::start();
    count.set(0);
    host.render_frame(64).unwrap();
    let allocations = measurement.finish();
    assert_eq!(
        allocations, 0,
        "an equal write and its frame allocated {allocations} times"
    );
}
