//! The Canvas widget and its closed drawing command set.

use dioxus_compose::Host;
use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{Mutation, PropertyValue, decode_batch};
use dioxus_compose::schema::{PropertyKind, WidgetKind};
use dioxus_compose::{DrawCommand, DrawList};
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

thread_local! {
    static TRACKING: Cell<bool> = const { Cell::new(false) };
    static ALLOCATIONS: Cell<usize> = const { Cell::new(0) };
}

struct CountingAllocator;

fn record_allocation() {
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

/// A line chart, written with nothing but Rust. No Kotlin knows this chart exists: the
/// Renderer only ever sees the closed command vocabulary.
fn line_chart(samples: &[f32]) -> DrawList {
    let mut list = DrawList::builder()
        .rect(
            Paint::Role(ColorRole::SurfaceVariant),
            0.0,
            0.0,
            120.0,
            60.0,
            0.0,
        )
        .line(Paint::Role(ColorRole::Outline), 0.0, 60.0, 120.0, 60.0, 1.0);
    for window in samples.windows(2) {
        let index = samples
            .iter()
            .position(|value| (*value - window[0]).abs() < f32::EPSILON)
            .unwrap_or(0) as f32;
        list = list.line(
            Paint::Role(ColorRole::Primary),
            index * 20.0,
            60.0 - window[0],
            (index + 1.0) * 20.0,
            60.0 - window[1],
            2.0,
        );
    }
    list.text_at(
        Paint::Role(ColorRole::OnSurface),
        "traffic",
        4.0,
        4.0,
        TypeRole::Label,
    )
    .build()
}

fn chart_app() -> Element {
    let samples = use_signal(|| vec![10.0_f32, 24.0, 18.0, 40.0, 33.0]);
    rsx! {
        Canvas { commands: line_chart(&samples()) }
    }
}

fn canvas_records(batch: &[u8]) -> Vec<(u32, Vec<u8>)> {
    decode_batch(batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::Commands,
                value: PropertyValue::Bytes(bytes),
            } => Some((node_id, bytes.to_vec())),
            _ => None,
        })
        .collect()
}

#[test]
fn fr17_a_line_chart_is_authored_in_rsx_alone() {
    let mut host = Host::new(chart_app);
    let batch = host.rebuild().unwrap();

    let decoded = decode_batch(batch).unwrap();
    assert!(
        decoded.iter().any(|mutation| matches!(
            mutation,
            Mutation::Create {
                widget: WidgetKind::Canvas,
                ..
            }
        )),
        "no Canvas node was created: {decoded:#?}",
    );

    let commands = DrawList::from_bytes(canvas_records(host.rebuild().unwrap())[0].1.clone())
        .decode()
        .unwrap();
    assert_eq!(
        commands.len(),
        7,
        "a background, a baseline, four segments and a label: {commands:#?}",
    );
    assert!(matches!(commands[0], DrawCommand::Rect { .. }));
}

#[test]
fn fr17_a_color_role_reaches_the_renderer_unresolved() {
    let mut host = Host::new(chart_app);
    let batch = host.rebuild().unwrap();

    let commands = DrawList::from_bytes(canvas_records(batch)[0].1.clone())
        .decode()
        .unwrap();

    // The Host never turns a role into a colour: doing so would take the canvas outside
    // the design system and give the same chart the same colour everywhere.
    assert!(
        commands
            .iter()
            .any(|command| matches!(command.paint(), Paint::Role(ColorRole::Primary))),
        "the chart line lost its ColorRole on the way to the wire",
    );
    assert!(
        !commands
            .iter()
            .any(|command| matches!(command.paint(), Paint::Literal(_))),
        "a literal colour appeared where the chart only asked for roles",
    );
}

#[test]
fn fr17_an_unchanged_command_list_sends_no_mutation() {
    let mut host = Host::new(chart_app);
    let initial = host.rebuild().unwrap();
    assert_eq!(canvas_records(initial).len(), 1, "the first frame draws");

    // Nothing changed, so the attribute compares equal and the list is not resent.
    let next = host.render_frame(0).unwrap();

    assert!(
        canvas_records(next).is_empty(),
        "an unchanged command list was resent",
    );
}

#[test]
fn fr17_an_unchanged_command_list_allocates_nothing() {
    let list = line_chart(&[10.0, 24.0, 18.0, 40.0, 33.0]);
    let mut host = Host::new(chart_app);
    for _ in 0..3 {
        let _ = host.rebuild().unwrap();
    }

    ALLOCATIONS.with(|count| count.set(0));
    TRACKING.with(|tracking| tracking.set(true));
    // Handing the same list to the next frame is a reference count bump and a byte
    // comparison, not a rebuild of the command array.
    let same = list.clone();
    let equal = same == list;
    std::hint::black_box((equal, same.as_bytes().len()));
    TRACKING.with(|tracking| tracking.set(false));
    let allocations = ALLOCATIONS.with(Cell::get);

    assert!(equal);
    assert_eq!(
        allocations, 0,
        "reusing an unchanged command list allocated {allocations} times",
    );
}

#[test]
fn fr17_a_point_heavy_path_crosses_as_an_asset_id() {
    let list = DrawList::builder()
        .polyline_ref(Paint::Role(ColorRole::Primary), 7, 2.0)
        .build();

    // However many points the path has, the record is the same size, because the points
    // never enter it.
    assert_eq!(list.as_bytes().len(), dioxus_compose::drawing::COMMAND_LEN);
    assert_eq!(
        list.decode().unwrap()[0],
        DrawCommand::PolylineRef {
            paint: Paint::Role(ColorRole::Primary),
            asset_id: 7,
            stroke_width: 2.0,
        },
    );
}
