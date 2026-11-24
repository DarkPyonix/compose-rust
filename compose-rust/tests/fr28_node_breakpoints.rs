//! Node-level width breakpoints: which bucket an observed node is in, and a
//! reader that runs again only when the bucket changes.
//!
//! Size reports are driven through the Host the way the Renderer sends them, so what is
//! tested is what a composable sees.

mod support;

use compose_rust::protocol::{HostEvent, Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{EventPayload, WindowHeightClass, WindowSizeClass};
use compose_rust::ui::*;
use compose_rust::{ComposeHost, Modifier as WireModifier};
use std::cell::Cell;
use support::Tree;

const BREAKPOINTS: [f32; 3] = [300.0, 600.0, 900.0];

thread_local! {
    static READER_RUNS: Cell<usize> = const { Cell::new(0) };
    static SIBLING_RUNS: Cell<usize> = const { Cell::new(0) };
    static PANE_RUNS: [Cell<usize>; 2] = const { [Cell::new(0), Cell::new(0)] };
}

/// A size report for an observed `node_id` at `width_dp`, with the classes the Renderer
/// would send for it.
fn report(node_id: u32, width_dp: f32) -> HostEvent<'static> {
    let height_dp = 700.0;
    HostEvent::new(
        node_id,
        0,
        EventPayload::WindowSizeChanged {
            width_dp,
            height_dp,
            class: WindowSizeClass::from_width_dp(width_dp),
            height_class: WindowHeightClass::from_height_dp(height_dp),
            safe_area_top: 0.0,
            safe_area_leading: 0.0,
            safe_area_trailing: 0.0,
            safe_area_bottom: 0.0,
        },
    )
}

/// The ids of the nodes that carry `ObserveSize`, in the order the first batch emitted
/// them.
fn observed_node_ids(batch: &[u8]) -> Vec<u32> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                node_id,
                modifier: WireModifier::ObserveSize { .. },
                ..
            } => Some(node_id),
            _ => None,
        })
        .collect()
}

fn start(content: fn()) -> (ComposeHost, Tree, Vec<u32>) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    let nodes = observed_node_ids(&first);
    tree.apply(&first);
    (host, tree, nodes)
}

fn resize(host: &mut ComposeHost, tree: &mut Tree, node_id: u32, width_dp: f32) {
    let (batch, _) = host
        .dispatch(report(node_id, width_dp))
        .expect("the size report dispatches");
    tree.apply(batch);
}

// ----- the bucket follows the node width -----------------------------------------------

#[Composable]
fn Bucket() {
    let size = remember_node_size();
    let bucket = current_node_breakpoint(size, &BREAKPOINTS);
    Column!(modifier = Modifier.observe_size_every_dp(size.token()), {
        Text!(format!("{bucket}"));
    });
}

/// A width on a breakpoint belongs to the bucket above it; a width just below it does not.
#[test]
fn fr28_node_breakpoint_follows_node_width_across_resizes() {
    let (mut host, mut tree, nodes) = start(Bucket);
    assert_eq!(nodes.len(), 1);
    let node_id = nodes[0];
    assert_eq!(tree.texts(), vec!["0"], "unmeasured starts at bucket 0");

    for (width, bucket) in [
        (0.0, "0"),
        (299.9, "0"),
        (300.0, "1"),
        (599.9, "1"),
        (600.0, "2"),
        (899.9, "2"),
        (900.0, "3"),
    ] {
        resize(&mut host, &mut tree, node_id, width);
        assert_eq!(tree.texts(), vec![bucket], "at {width} dp");
    }
    // And back down, so the edges hold in both directions.
    for (width, bucket) in [(899.9, "2"), (599.9, "1"), (299.9, "0"), (0.0, "0")] {
        resize(&mut host, &mut tree, node_id, width);
        assert_eq!(tree.texts(), vec![bucket], "at {width} dp");
    }
}

// ----- the reader runs again only on a bucket change -----------------------------------

#[Composable]
fn Reader() {
    READER_RUNS.with(|runs| runs.set(runs.get() + 1));
    let size = remember_node_size();
    let bucket = current_node_breakpoint(size, &BREAKPOINTS);
    Column!(modifier = Modifier.observe_size_every_dp(size.token()), {
        Text!(format!("{bucket}"));
    });
}

#[Composable]
fn Sibling() {
    SIBLING_RUNS.with(|runs| runs.set(runs.get() + 1));
    Text!("sibling");
}

fn reader_and_sibling() {
    Reader();
    Sibling();
}

/// Resizing inside one bucket runs nothing again; crossing a breakpoint runs the reader
/// once and never its sibling.
#[test]
fn fr28_node_breakpoint_recomposes_only_when_bucket_changes() {
    READER_RUNS.with(|runs| runs.set(0));
    SIBLING_RUNS.with(|runs| runs.set(0));

    let (mut host, mut tree, nodes) = start(reader_and_sibling);
    assert_eq!(nodes.len(), 1);
    let node_id = nodes[0];
    let reader_runs = || READER_RUNS.with(Cell::get);
    let sibling_runs = || SIBLING_RUNS.with(Cell::get);
    let siblings = sibling_runs();

    resize(&mut host, &mut tree, node_id, 320.0);
    assert_eq!(tree.texts(), vec!["1", "sibling"]);
    let settled = reader_runs();

    for width in [350.0, 450.0, 599.0] {
        resize(&mut host, &mut tree, node_id, width);
        assert_eq!(
            reader_runs(),
            settled,
            "{width} dp is still bucket 1, so the reader is not run again"
        );
    }
    assert_eq!(tree.texts(), vec!["1", "sibling"]);

    resize(&mut host, &mut tree, node_id, 600.0);
    assert_eq!(tree.texts(), vec!["2", "sibling"]);
    assert_eq!(reader_runs(), settled + 1, "crossing 600 dp runs it once");

    resize(&mut host, &mut tree, node_id, 601.0);
    assert_eq!(reader_runs(), settled + 1);
    assert_eq!(
        sibling_runs(),
        siblings,
        "the sibling never reads the bucket"
    );
}

// ----- non-positive, non-finite, unordered and empty lists -----------------------------

#[Composable]
fn Lists() {
    let size = remember_node_size();
    let ordered = current_node_breakpoint(size, &BREAKPOINTS);
    let unordered = current_node_breakpoint(size, &[900.0, 300.0, 600.0]);
    let mixed = current_node_breakpoint(
        size,
        &[
            0.0,
            -10.0,
            f32::NAN,
            300.0,
            f32::INFINITY,
            600.0,
            f32::NEG_INFINITY,
            900.0,
        ],
    );
    let empty = current_node_breakpoint(size, &[]);
    Column!(modifier = Modifier.observe_size_every_dp(size.token()), {
        Text!(format!("{ordered}"));
        Text!(format!("{unordered}"));
        Text!(format!("{mixed}"));
        Text!(format!("{empty}"));
    });
}

/// Non-positive and non-finite breakpoints are ignored, order does not change the count,
/// an empty list always produces bucket 0, and nothing panics.
#[test]
fn fr28_node_breakpoint_ignores_non_positive_and_non_finite_breakpoints() {
    // Outside a composition, an unmeasured node is 0 dp wide and in bucket 0.
    assert_eq!(current_node_breakpoint(NodeSize::of(0), &BREAKPOINTS), 0);
    assert_eq!(current_node_breakpoint(NodeSize::of(0), &[]), 0);

    let (mut host, mut tree, nodes) = start(Lists);
    assert_eq!(nodes.len(), 1);
    let node_id = nodes[0];
    assert_eq!(
        tree.texts(),
        vec!["0", "0", "0", "0"],
        "unmeasured is bucket 0"
    );

    for (width, bucket) in [
        (299.9, "0"),
        (300.0, "1"),
        (600.0, "2"),
        (900.0, "3"),
        (2000.0, "3"),
    ] {
        resize(&mut host, &mut tree, node_id, width);
        assert_eq!(
            tree.texts(),
            vec![bucket, bucket, bucket, "0"],
            "at {width} dp"
        );
    }
}

// ----- two independent panes in a split layout -----------------------------------------

#[Composable]
fn Pane(index: usize) {
    PANE_RUNS.with(|runs| runs[index].set(runs[index].get() + 1));
    let size = remember_node_size();
    let bucket = current_node_breakpoint(size, &BREAKPOINTS);
    Column!(modifier = Modifier.observe_size_every_dp(size.token()), {
        Text!(format!("{index}:{bucket}"));
    });
}

fn split_layout() {
    Row!({
        Pane(0);
        Pane(1);
    });
}

/// Two panes with separate `remember_node_size` handles resize independently without
/// triggering each other's recomposition.
#[test]
fn fr28_node_breakpoint_works_independently_for_two_panes() {
    PANE_RUNS.with(|runs| {
        runs[0].set(0);
        runs[1].set(0);
    });

    let (mut host, mut tree, nodes) = start(split_layout);
    assert_eq!(nodes.len(), 2);
    assert_eq!(tree.texts(), vec!["0:0", "1:0"]);
    let runs = || PANE_RUNS.with(|runs| [runs[0].get(), runs[1].get()]);

    let before = runs();
    resize(&mut host, &mut tree, nodes[1], 950.0);
    assert_eq!(tree.texts(), vec!["0:0", "1:3"]);
    assert_eq!(runs(), [before[0], before[1] + 1]);

    // Further movement inside pane 1's bucket runs neither pane.
    resize(&mut host, &mut tree, nodes[1], 980.0);
    assert_eq!(runs(), [before[0], before[1] + 1]);

    let before = runs();
    resize(&mut host, &mut tree, nodes[0], 350.0);
    assert_eq!(tree.texts(), vec!["0:1", "1:3"]);
    assert_eq!(runs(), [before[0] + 1, before[1]]);
}
