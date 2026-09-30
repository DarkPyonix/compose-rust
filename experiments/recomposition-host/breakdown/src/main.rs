//! Where the time in one interaction actually goes.
//!
//! The recorded number for an interaction covers handler, diff and encoding together,
//! because the mutation sink writes the wire records as the diff walks. Nothing outside
//! the call can tell those two apart: they are one pass.
//!
//! So this runs the same interaction twice over two VirtualDoms of the same component,
//! one writing wire records and one writing nothing, and takes the difference. The dom
//! that writes nothing still walks the same dirty scopes and compares the same dynamic
//! slots, so what separates the two is the encoding and nothing else.
//!
//! What this answers: replacing the reconciler with a Compose-style runtime removes the
//! comparison and keeps everything else, so the comparison is the whole of what such a
//! change could buy. This says how many nanoseconds that is.

use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
use dioxus_compose::renderer::ComposeRenderer;
use dioxus_compose::schema::{EventPayload, PropertyKind};
use dioxus_compose::{Host, VirtualDom};
use dioxus_core::{
    AttributeValue, ElementId, Event, Template, WriteMutations,
};
use std::hint::black_box;
use std::rc::Rc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Instant;

/// The same component the recorded interaction number was measured on, so the totals here
/// can be lined up against it rather than read on their own.
fn app() -> Element {
    let mut count = use_signal(|| 0_u64);
    rsx! {
        Column {
            Text { text: count().to_string() }
            TextField { placeholder: "Message" }
            Button {
                text: "Increment",
                on_click: move |_| *count.write() += 1,
            }
        }
    }
}

/// How many changed text slots the scaling component draws. Read when the component runs,
/// so it is set before the dom is built and left alone afterwards.
static CHANGED_SLOTS: AtomicUsize = AtomicUsize::new(1);

/// A component whose every text node reads the signal the click writes.
///
/// One click dirties one scope and changes every one of those texts, so the work divides
/// into a part paid once for re-running the component and a part paid per changed slot.
/// Measuring two widths and taking the slope is what separates them, and the separation is
/// the point: a Compose-style runtime still re-runs the component, so only the per-slot
/// part is what replacing the reconciler could remove.
fn scaling_app() -> Element {
    let mut count = use_signal(|| 0_u64);
    let width = CHANGED_SLOTS.load(Ordering::Relaxed);
    rsx! {
        Column {
            for slot in 0..width {
                Text { text: "{count}-{slot}" }
            }
            Button {
                text: "Increment",
                on_click: move |_| *count.write() += 1,
            }
        }
    }
}

/// The same component, clicked in a way that leaves every value where it was.
///
/// Writing a signal marks its scope dirty whatever the value, so this re-runs the
/// component and formats every string exactly as the one above does. The only thing it
/// does differently is give the comparison nothing to report, so nothing is emitted.
///
/// Subtracting this from the one above leaves what it costs to emit a changed slot, with
/// the component's own per-slot work (the formatting, and the allocation under it) on both
/// sides of the subtraction and therefore out of the answer.
fn unchanged_app() -> Element {
    let mut count = use_signal(|| 0_u64);
    let width = CHANGED_SLOTS.load(Ordering::Relaxed);
    rsx! {
        Column {
            for slot in 0..width {
                Text { text: "{count}-{slot}" }
            }
            Button {
                text: "Increment",
                on_click: move |_| *count.write() = 0,
            }
        }
    }
}

/// A changed slot that costs no string.
///
/// Every text is a literal, so nothing is formatted and nothing is allocated, and the one
/// thing the click changes is a number. Set beside the two above this says whether the cost
/// of a changed slot is the reconciler's bookkeeping or the string churn underneath it,
/// which decides whether the number is something a different runtime would avoid or
/// something any runtime re-running the same component would pay.
fn numeric_app() -> Element {
    let mut count = use_signal(|| 0_u32);
    let width = CHANGED_SLOTS.load(Ordering::Relaxed);
    rsx! {
        Column {
            for _ in 0..width {
                Text { text: "fixed", max_lines: count() % 7 + 1 }
            }
            Button {
                text: "Increment",
                on_click: move |_| *count.write() += 1,
            }
        }
    }
}

/// The same, with the number left where it was.
fn numeric_unchanged_app() -> Element {
    let mut count = use_signal(|| 0_u32);
    let width = CHANGED_SLOTS.load(Ordering::Relaxed);
    rsx! {
        Column {
            for _ in 0..width {
                Text { text: "fixed", max_lines: count() % 7 + 1 }
            }
            Button {
                text: "Increment",
                on_click: move |_| *count.write() = 0,
            }
        }
    }
}

/// One prop, and a body whose own child never changes.
///
/// Every widget in this library is a component, so a changed prop on a `Text` re-runs a
/// component whose props struct carries some twenty optional fields. This carries one. If a
/// changed slot costs much less here, the cost is the width of the widget's props rather
/// than the price of re-running a scope at all, and the two have different answers.
#[component]
fn Tiny(value: u32) -> Element {
    rsx! {
        Text { text: "fixed", max_lines: value }
    }
}

/// A changed prop on the one-prop component.
fn tiny_app() -> Element {
    let mut count = use_signal(|| 0_u32);
    let width = CHANGED_SLOTS.load(Ordering::Relaxed);
    rsx! {
        Column {
            for _ in 0..width {
                Tiny { value: count() % 7 + 1 }
            }
            Button {
                text: "Increment",
                on_click: move |_| *count.write() += 1,
            }
        }
    }
}

/// The same, left where it was, so the props compare equal and the child is skipped.
fn tiny_unchanged_app() -> Element {
    let mut count = use_signal(|| 0_u32);
    let width = CHANGED_SLOTS.load(Ordering::Relaxed);
    rsx! {
        Column {
            for _ in 0..width {
                Tiny { value: count() % 7 + 1 }
            }
            Button {
                text: "Increment",
                on_click: move |_| *count.write() = 0,
            }
        }
    }
}

/// A mutation sink that keeps the diff honest and writes nothing.
///
/// Every argument goes through `black_box` so the optimiser cannot decide the traversal
/// that produced it was pointless and delete it. Without that this measures a diff that
/// was partly compiled away, which would overstate the saving it is here to size.
#[derive(Default)]
struct NullSink {
    /// Counted so the run can confirm both doms were given the same work to do.
    calls: usize,
}

impl WriteMutations for NullSink {
    fn append_children(&mut self, id: ElementId, m: usize) {
        black_box((id, m));
        self.calls += 1;
    }

    fn assign_node_id(&mut self, path: &'static [u8], id: ElementId) {
        black_box((path, id));
        self.calls += 1;
    }

    fn create_placeholder(&mut self, id: ElementId) {
        black_box(id);
        self.calls += 1;
    }

    fn create_text_node(&mut self, value: &str, id: ElementId) {
        black_box((value, id));
        self.calls += 1;
    }

    fn load_template(&mut self, template: Template, index: usize, id: ElementId) {
        black_box((template_shape(&template), index, id));
        self.calls += 1;
    }

    fn replace_node_with(&mut self, id: ElementId, m: usize) {
        black_box((id, m));
        self.calls += 1;
    }

    fn replace_placeholder_with_nodes(&mut self, path: &'static [u8], m: usize) {
        black_box((path, m));
        self.calls += 1;
    }

    fn insert_nodes_after(&mut self, id: ElementId, m: usize) {
        black_box((id, m));
        self.calls += 1;
    }

    fn insert_nodes_before(&mut self, id: ElementId, m: usize) {
        black_box((id, m));
        self.calls += 1;
    }

    fn set_attribute(
        &mut self,
        name: &'static str,
        namespace: Option<&'static str>,
        value: &AttributeValue,
        id: ElementId,
    ) {
        black_box((name, namespace, attribute_shape(value), id));
        self.calls += 1;
    }

    fn set_node_text(&mut self, value: &str, id: ElementId) {
        black_box((value, id));
        self.calls += 1;
    }

    fn create_event_listener(&mut self, name: &'static str, id: ElementId) {
        black_box((name, id));
        self.calls += 1;
    }

    fn remove_event_listener(&mut self, name: &'static str, id: ElementId) {
        black_box((name, id));
        self.calls += 1;
    }

    fn remove_node(&mut self, id: ElementId) {
        black_box(id);
        self.calls += 1;
    }

    fn push_root(&mut self, id: ElementId) {
        black_box(id);
        self.calls += 1;
    }
}

/// Enough of a template to be worth reading, without copying it.
///
/// `black_box` on the template itself would hide the pointer and stop there. Touching the
/// counts makes the sink look at the thing the diff handed it, which is what the real sink
/// does.
fn template_shape(template: &Template) -> (usize, usize, usize) {
    (
        template.roots.len(),
        template.node_paths.len(),
        template.attr_paths.len(),
    )
}

/// The same, for an attribute: read the value rather than the pointer to it.
fn attribute_shape(value: &AttributeValue) -> usize {
    match value {
        AttributeValue::Text(text) => text.len(),
        AttributeValue::Float(number) => *number as usize,
        AttributeValue::Int(number) => *number as usize,
        AttributeValue::Bool(flag) => usize::from(*flag),
        _ => 0,
    }
}

fn p99_ns(iterations: usize, operation: impl FnMut()) -> u128 {
    percentiles_ns(iterations, operation).1
}

/// Median and p99 together.
///
/// p99 alone reads a tail that the allocator's slow path can own outright, and a per-slot
/// cost derived from two tails is two slow paths divided by a width. The median says
/// whether the shape being described is the usual one.
fn percentiles_ns(iterations: usize, mut operation: impl FnMut()) -> (u128, u128) {
    let mut samples = Vec::with_capacity(iterations);
    for _ in 0..iterations {
        let started = Instant::now();
        operation();
        samples.push(started.elapsed().as_nanos());
    }
    samples.sort_unstable();
    (
        samples[iterations / 2],
        samples[(iterations * 99).div_ceil(100) - 1],
    )
}

/// The click target, found the way the recorded benchmark finds it: the handler id the
/// first batch carried for the button.
fn click_target(host: &mut Host) -> HostEvent<'static> {
    let initial = decode_batch(host.rebuild().unwrap()).unwrap();
    let (node_id, handler_id) = initial
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnClick,
                value: PropertyValue::Integer(handler),
            } => Some((*node_id, *handler as u64)),
            _ => None,
        })
        .expect("the button's click handler is in the first batch");
    HostEvent {
        node_id,
        handler_id,
        payload: EventPayload::Clicked,
    }
}

/// One dom driven by hand, so a stage can be timed on its own.
///
/// `Host` runs handler, diff and encoding behind one call, which is the thing that cannot
/// be decomposed from outside. Driving `dioxus-core` directly is what makes the stages
/// separable, and the component and the event are the same ones `Host` would have used.
struct HandDriven<S: WriteMutations> {
    dom: VirtualDom,
    sink: S,
    listener: (&'static str, ElementId),
}

impl<S: WriteMutations> HandDriven<S> {
    fn new(sink: S) -> Self {
        Self::of(app, sink)
    }

    fn of(component: fn() -> Element, mut sink: S) -> Self {
        let mut discovery = ListenerDiscovery::default();
        VirtualDom::new(component).rebuild(&mut discovery);
        let listener = discovery
            .click
            .expect("the button registers a click listener during the first build");
        let mut dom = VirtualDom::new(component);
        dom.rebuild(&mut sink);
        Self {
            dom,
            sink,
            listener,
        }
    }

    /// Runs the user's handler and marks the scope that read the signal dirty. No diff.
    fn handle_event(&mut self) {
        let (name, element) = self.listener;
        self.dom
            .runtime()
            .handle_event(name, Event::new(Rc::new(()), true).into_any(), element);
    }

    /// Walks the dirty scopes and hands what changed to the sink.
    fn render(&mut self) {
        self.dom.render_immediate(&mut self.sink);
    }
}

/// Finds the click listener's element by watching the first build register it.
#[derive(Default)]
struct ListenerDiscovery {
    click: Option<(&'static str, ElementId)>,
}

impl WriteMutations for ListenerDiscovery {
    fn create_event_listener(&mut self, name: &'static str, id: ElementId) {
        if name == "click" && self.click.is_none() {
            self.click = Some((name, id));
        }
    }

    fn append_children(&mut self, _: ElementId, _: usize) {}
    fn assign_node_id(&mut self, _: &'static [u8], _: ElementId) {}
    fn create_placeholder(&mut self, _: ElementId) {}
    fn create_text_node(&mut self, _: &str, _: ElementId) {}
    fn load_template(&mut self, _: Template, _: usize, _: ElementId) {}
    fn replace_node_with(&mut self, _: ElementId, _: usize) {}
    fn replace_placeholder_with_nodes(&mut self, _: &'static [u8], _: usize) {}
    fn insert_nodes_after(&mut self, _: ElementId, _: usize) {}
    fn insert_nodes_before(&mut self, _: ElementId, _: usize) {}
    fn set_attribute(&mut self, _: &'static str, _: Option<&'static str>, _: &AttributeValue, _: ElementId) {}
    fn set_node_text(&mut self, _: &str, _: ElementId) {}
    fn remove_event_listener(&mut self, _: &'static str, _: ElementId) {}
    fn remove_node(&mut self, _: ElementId) {}
    fn push_root(&mut self, _: ElementId) {}
}

const WARMUP: usize = 200;

fn main() {
    let iterations: usize = std::env::args()
        .nth(1)
        .and_then(|argument| argument.parse().ok())
        .unwrap_or(10_000);

    // The whole interaction through the public surface, for a number that can be lined up
    // against the recorded one rather than trusted on its own.
    let mut host = Host::new(app);
    let click = click_target(&mut host);
    for _ in 0..WARMUP {
        let _ = host.dispatch(click.clone()).unwrap();
    }
    let whole = p99_ns(iterations, || {
        let (batch, result) = host.dispatch(click.clone()).unwrap();
        black_box((batch.len(), result));
    });

    // Handler only: the user's closure and the dirty mark, with no diff after it. Dioxus
    // will not diff until something asks it to, so this stage stands alone.
    let mut handler_only = HandDriven::new(NullSink::default());
    for _ in 0..WARMUP {
        handler_only.handle_event();
        handler_only.render();
    }
    let handler = p99_ns(iterations, || {
        handler_only.handle_event();
        black_box(handler_only.sink.calls);
    });
    // The dom is left with as many dirty scopes as there were iterations; drain them so
    // the count printed below is not the debt of this stage.
    handler_only.render();

    // Diff with nothing written.
    let mut null = HandDriven::new(NullSink::default());
    for _ in 0..WARMUP {
        null.handle_event();
        null.render();
    }
    let diff_only = p99_ns(iterations, || {
        null.handle_event();
        null.render();
    });
    let null_calls = null.sink.calls;

    // Diff with the wire records written, which is what ships.
    let mut wire = HandDriven::new(ComposeRenderer::new());
    for _ in 0..WARMUP {
        wire.handle_event();
        wire.sink.begin_frame();
        wire.render();
        let _ = wire.sink.finish_frame().unwrap();
    }
    let diff_and_encode = p99_ns(iterations, || {
        wire.handle_event();
        wire.sink.begin_frame();
        wire.render();
        black_box(wire.sink.finish_frame().unwrap().len());
    });

    // The envelope on its own: no diff, just the framing the boundary rejects when it is
    // wrong.
    let mut envelope_sink = ComposeRenderer::new();
    let mut envelope_dom = VirtualDom::new(app);
    envelope_sink.begin_frame();
    envelope_dom.rebuild(&mut envelope_sink);
    let _ = envelope_sink.finish_frame().unwrap();
    let envelope = p99_ns(iterations, || {
        envelope_sink.begin_frame();
        black_box(envelope_sink.finish_frame().unwrap().len());
    });

    // The slope, over several widths rather than two, because a per-slot cost taken from
    // two points says nothing about whether the line is straight and a curve read as a
    // slope is a wrong answer with a plausible shape.
    let widths = [1_usize, 5, 17, 33, 65, 129];
    let changed: Vec<(usize, u128, u128)> = widths
        .iter()
        .map(|&width| {
            let (median, tail) = scaling(scaling_app, width, iterations);
            (width, median, tail)
        })
        .collect();
    let unchanged: Vec<(usize, u128, u128)> = widths
        .iter()
        .map(|&width| {
            let (median, tail) = scaling(unchanged_app, width, iterations);
            (width, median, tail)
        })
        .collect();

    // The same two sweeps with nothing written, which puts the encoder outside the
    // measurement. What is left is what dioxus-core spends on a changed slot before it
    // reaches any sink at all, and the difference between these slopes and the two above
    // says which half of the cost of a changed slot belongs to which side.
    let changed_null: Vec<(usize, u128, u128)> = widths
        .iter()
        .map(|&width| {
            let (median, tail) = scaling_null(scaling_app, width, iterations);
            (width, median, tail)
        })
        .collect();
    let unchanged_null: Vec<(usize, u128, u128)> = widths
        .iter()
        .map(|&width| {
            let (median, tail) = scaling_null(unchanged_app, width, iterations);
            (width, median, tail)
        })
        .collect();

    // The same question with no string anywhere in it.
    let numeric: Vec<(usize, u128, u128)> = widths
        .iter()
        .map(|&width| {
            let (median, tail) = scaling(numeric_app, width, iterations);
            (width, median, tail)
        })
        .collect();
    let numeric_unchanged: Vec<(usize, u128, u128)> = widths
        .iter()
        .map(|&width| {
            let (median, tail) = scaling(numeric_unchanged_app, width, iterations);
            (width, median, tail)
        })
        .collect();

    // The same, through a component carrying one prop instead of twenty.
    let tiny: Vec<(usize, u128, u128)> = widths
        .iter()
        .map(|&width| {
            let (median, tail) = scaling(tiny_app, width, iterations);
            (width, median, tail)
        })
        .collect();
    let tiny_unchanged: Vec<(usize, u128, u128)> = widths
        .iter()
        .map(|&width| {
            let (median, tail) = scaling(tiny_unchanged_app, width, iterations);
            (width, median, tail)
        })
        .collect();

    let encode = diff_and_encode.saturating_sub(diff_only);

    println!("iterations={iterations}");
    println!("sink_calls_per_interaction={}", null_calls / (iterations + WARMUP));
    println!();
    println!("p99 nanoseconds");
    println!("  whole_interaction_through_host   {whole:>8}");
    println!("  handler_and_dirty_mark           {handler:>8}");
    println!("  diff_only_null_sink              {diff_only:>8}");
    println!("  diff_and_wire_encode             {diff_and_encode:>8}");
    println!("  wire_encode_by_difference        {encode:>8}");
    println!("  envelope_only                    {envelope:>8}");
    println!();
    println!("one dirty scope over widening trees, nanoseconds");
    println!("  slots   changed p50   changed p99   unchanged p50   unchanged p99");
    for ((width, median, tail), (_, median_same, tail_same)) in changed.iter().zip(&unchanged) {
        println!("  {width:>5}   {median:>11}   {tail:>11}   {median_same:>13}   {tail_same:>13}");
    }
    println!();
    let changed_slope = slope(&changed);
    let unchanged_slope = slope(&unchanged);
    println!("least squares on the medians, nanoseconds per slot");
    println!("  changed, re-run plus compare plus emit   {changed_slope:>8.0}");
    println!("  unchanged, re-run plus compare           {unchanged_slope:>8.0}");
    println!(
        "  emitting a changed slot                  {:>8.0}",
        changed_slope - unchanged_slope
    );
    println!();
    let changed_null_slope = slope(&changed_null);
    let unchanged_null_slope = slope(&unchanged_null);
    println!("the same, with nothing written, nanoseconds per slot");
    println!("  changed, no sink                          {changed_null_slope:>8.0}");
    println!("  unchanged, no sink                        {unchanged_null_slope:>8.0}");
    println!(
        "  a changed slot before any sink           {:>8.0}",
        changed_null_slope - unchanged_null_slope
    );
    println!(
        "  the wire sink's share of a changed slot  {:>8.0}",
        (changed_slope - unchanged_slope) - (changed_null_slope - unchanged_null_slope)
    );
    println!();
    let numeric_slope = slope(&numeric);
    let numeric_unchanged_slope = slope(&numeric_unchanged);
    println!("a changed number rather than a changed string, nanoseconds per slot");
    println!("  changed number                            {numeric_slope:>8.0}");
    println!("  unchanged number                          {numeric_unchanged_slope:>8.0}");
    println!(
        "  a changed numeric slot                   {:>8.0}",
        numeric_slope - numeric_unchanged_slope
    );
    println!();
    let tiny_slope = slope(&tiny);
    let tiny_unchanged_slope = slope(&tiny_unchanged);
    println!("through a one-prop component, nanoseconds per slot");
    println!("  changed                                   {tiny_slope:>8.0}");
    println!("  unchanged, props compare equal            {tiny_unchanged_slope:>8.0}");
    println!(
        "  a changed slot                           {:>8.0}",
        tiny_slope - tiny_unchanged_slope
    );
}

/// One width, driven into a sink that writes nothing.
fn scaling_null(component: fn() -> Element, width: usize, iterations: usize) -> (u128, u128) {
    CHANGED_SLOTS.store(width, Ordering::Relaxed);
    let mut driven = HandDriven::of(component, NullSink::default());
    for _ in 0..WARMUP {
        driven.handle_event();
        driven.render();
    }
    percentiles_ns(iterations, || {
        driven.handle_event();
        driven.render();
        black_box(driven.sink.calls);
    })
}

/// Least squares slope of the medians against the width.
fn slope(points: &[(usize, u128, u128)]) -> f64 {
    let n = points.len() as f64;
    let mean_x = points.iter().map(|point| point.0 as f64).sum::<f64>() / n;
    let mean_y = points.iter().map(|point| point.1 as f64).sum::<f64>() / n;
    let covariance: f64 = points
        .iter()
        .map(|point| (point.0 as f64 - mean_x) * (point.1 as f64 - mean_y))
        .sum();
    let variance: f64 = points
        .iter()
        .map(|point| (point.0 as f64 - mean_x).powi(2))
        .sum();
    covariance / variance
}

/// One width of the scaling component, driven the way the wire path drives it.
fn scaling(component: fn() -> Element, width: usize, iterations: usize) -> (u128, u128) {
    CHANGED_SLOTS.store(width, Ordering::Relaxed);
    let mut driven = HandDriven::of(component, ComposeRenderer::new());
    for _ in 0..WARMUP {
        driven.handle_event();
        driven.sink.begin_frame();
        driven.render();
        let _ = driven.sink.finish_frame().unwrap();
    }
    percentiles_ns(iterations, || {
        driven.handle_event();
        driven.sink.begin_frame();
        driven.render();
        black_box(driven.sink.finish_frame().unwrap().len());
    })
}
