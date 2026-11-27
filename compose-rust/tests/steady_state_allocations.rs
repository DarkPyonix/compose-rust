//! The encoder's own allocation behaviour: a warm arena encodes a batch without touching
//! the allocator. The Host's per-event ceiling is asserted against the chat sample, where
//! there is a real conversation behind the keystroke being measured.

use compose_rust::protocol::{BatchEncoder, Mutation, PropertyValue};
use compose_rust::schema::PropertyKind;
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

// A debug build warns on standard error when a Host frame takes over 1 ms. A loaded machine
// makes any frame slow, so the warning must not allocate either, or a steady-state
// allocation count depends on how busy the machine was.
static SLOW: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn steady_frames(
    host: &mut compose_rust::ComposeHost,
    count: compose_rust::runtime::MutableState<i32>,
    from: u64,
) -> usize {
    let measurement = AllocationMeasurement::start();
    for frame in from..from + 20 {
        count.set(frame as i32);
        host.render_frame(frame).unwrap();
    }
    measurement.finish()
}

#[test]
fn nfr9_a_slow_frame_allocates_no_more_than_a_fast_one() {
    use compose_rust::runtime::*;
    use compose_rust::ui::*;
    use std::sync::atomic::Ordering;

    let count = mutable_state_of(0);
    let state = count;
    let mut host = compose_rust::ComposeHost::with_content(move || {
        let _ = state.get();
        if SLOW.load(Ordering::Relaxed) {
            std::thread::sleep(std::time::Duration::from_millis(2));
        }
        Column!({
            Box!(modifier = Modifier.padding(4.0), {});
        });
    });
    host.rebuild().unwrap();
    for frame in 1..10u64 {
        count.set(frame as i32);
        host.render_frame(frame).unwrap();
    }
    // One slow frame first, so whatever standard error sets up on first use is not counted.
    SLOW.store(true, Ordering::Relaxed);
    count.set(-1);
    host.render_frame(10).unwrap();
    SLOW.store(false, Ordering::Relaxed);

    let fast = steady_frames(&mut host, count, 100);
    SLOW.store(true, Ordering::Relaxed);
    let slow = steady_frames(&mut host, count, 200);
    SLOW.store(false, Ordering::Relaxed);

    assert_eq!(
        slow, fast,
        "slow frames allocated {slow} where fast ones took {fast}"
    );
}
