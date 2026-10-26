//! The slot table runtime over a long run: what it holds does not grow with the frames.
//!
//! A screen that changes on every frame, a streamed reply or a field being typed into,
//! rebuilds its nodes' attributes each time. What a frame builds goes back to the runtime's
//! pool or to the allocator, so the live bytes after the thousandth frame are the live
//! bytes after the hundredth. A pool that kept one buffer per node per frame showed as
//! about 170 KB of growth a frame in the chat sample, 4.5 GB over two days of use.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::HostEvent;
use compose_rust::runtime::*;
use compose_rust::schema::{EventPayload, PropertyKind};
use compose_rust::ui::*;
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use support::Tree;

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

#[composable]
fn Toggled(shown: MutableState<bool>) {
    Column().content(|| {
        if shown.get() {
            for _ in 0..40 {
                // A size gives the node a Modifier slot, which the writer remembers per
                // node for as long as the node stands.
                Box().modifier(Modifier.size(40.0, 40.0)).content(|| {});
            }
        }
    });
}

#[test]
fn nfr_17_the_writer_keeps_nothing_for_nodes_that_were_removed() {
    let shown = mutable_state_of(true);
    let state = shown.clone();
    let mut host = ComposeHost::with_content(move || Toggled(state.clone()));
    host.rebuild().unwrap();
    TRACKING.with(|tracking| tracking.set(true));
    let mut frame = 0u64;
    let mut flip = |host: &mut ComposeHost, count: usize| {
        for _ in 0..count {
            frame += 1;
            shown.set(frame % 2 == 0);
            host.render_frame(frame).unwrap();
        }
    };
    flip(&mut host, 200);
    let warm = live_bytes();
    // Two thousand rounds of forty nodes made and removed: 80000 node ids that no longer
    // stand. Ids are never reused, so an entry kept for one is kept for good.
    flip(&mut host, 4000);
    let grown = live_bytes() - warm;
    TRACKING.with(|tracking| tracking.set(false));
    assert!(
        grown < 256 * 1024,
        "live memory grew by {grown} bytes over 2000 add and remove rounds"
    );
}

#[composable]
fn Chat(count: MutableState<usize>, tail: MutableState<usize>) {
    LazyColumn().content(move |list| {
        let rows = count.get();
        let tail = tail.get();
        list.items(rows, move |index| {
            // The last row is the one being streamed into: its text only ever grows.
            let text = if index + 1 == rows {
                "word ".repeat(tail)
            } else {
                "an earlier reply".to_owned()
            };
            Text(text);
        });
    });
}

#[test]
fn nfr_17_a_list_that_grows_and_a_text_that_streams_do_not_leave_memory_behind() {
    let count = mutable_state_of(1usize);
    let tail = mutable_state_of(0usize);
    let (c, t) = (count.clone(), tail.clone());
    let mut host = ComposeHost::with_content(move || Chat(c.clone(), t.clone()));
    let mut tree = Tree::default();
    tree.apply(host.rebuild().unwrap());
    let (node, handler) = tree.find("LazyColumn", None, 0, PropertyKind::OnRangeRequested);
    TRACKING.with(|tracking| tracking.set(true));
    let mut frame = 0u64;
    // A reply streams in forty chunks, then the next message starts a new row; after
    // thirty rows the conversation starts over, so the screen has a steady size.
    let mut run = |host: &mut ComposeHost, tree: &mut Tree, replies: usize| {
        for reply in 0..replies {
            count.set(reply % 30 + 1);
            for chunk in 0..40usize {
                frame += 1;
                tail.set(chunk + 1);
                let batch = host
                    .dispatch(HostEvent {
                        node_id: node,
                        handler_id: handler,
                        payload: EventPayload::RangeRequested {
                            start: 0,
                            count: 30,
                        },
                    })
                    .unwrap()
                    .0
                    .to_vec();
                tree.apply(&batch);
                let batch = host.render_frame(frame).unwrap().to_vec();
                tree.apply(&batch);
            }
        }
    };
    run(&mut host, &mut tree, 60);
    let warm = live_bytes();
    run(&mut host, &mut tree, 300);
    let grown = live_bytes() - warm;
    TRACKING.with(|tracking| tracking.set(false));
    assert!(
        grown < 512 * 1024,
        "live memory grew by {grown} bytes over 300 replies of 40 chunks"
    );
}
