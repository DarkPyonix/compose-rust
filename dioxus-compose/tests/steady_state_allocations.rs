//! The encoder's own allocation behaviour: a warm arena encodes a batch without touching
//! the allocator. The Host's per-event ceiling is asserted against the chat sample, where
//! there is a real conversation behind the keystroke being measured.

use dioxus_compose::protocol::{BatchEncoder, Mutation, PropertyValue};
use dioxus_compose::schema::PropertyKind;
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

fn encode_one_hundred(encoder: &mut BatchEncoder) {
    encoder.clear();
    for node_id in 0..100 {
        encoder
            .encode(&Mutation::SetProp {
                node_id,
                property: PropertyKind::Text,
                value: PropertyValue::String("steady-state"),
            })
            .unwrap();
    }
    std::hint::black_box(encoder.finish().unwrap());
}

#[test]
fn nfr9_boundary_encoding_does_not_allocate() {
    let mut encoder = BatchEncoder::with_capacity(4096, 2048, 128);
    encode_one_hundred(&mut encoder);

    let measurement = AllocationMeasurement::start();
    encode_one_hundred(&mut encoder);
    let allocations = measurement.finish();

    assert_eq!(allocations, 0, "warm arena encoding allocated");
}
