//! The application's own width breakpoints: which bucket the window is in, and a reader
//! that runs again only when the bucket changes.
//!
//! The size reports are driven through the Host the way the Renderer sends them, so what
//! is tested is what a composable sees.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{HostEvent, Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{
    DesignSystem, EventPayload, LastWindowClosed, Theme, WidgetKind, Window as WindowSettings,
    WindowHeightClass, WindowSizeClass,
};
use compose_rust::ui::*;
use std::cell::Cell;
use support::Tree;

const EMBER: [f32; 3] = [560.0, 900.0, 1279.0];

thread_local! {
    static READER_RUNS: Cell<usize> = const { Cell::new(0) };
    static SIBLING_RUNS: Cell<usize> = const { Cell::new(0) };
    static PANE_RUNS: [Cell<usize>; 2] = const { [Cell::new(0), Cell::new(0)] };
}

/// A size report for `node` (0 is the single window) at `width_dp`, with the classes the
/// Renderer would send for it.
fn report(node: u32, width_dp: f32) -> HostEvent<'static> {
    let height_dp = 700.0;
    HostEvent::new(
        node,
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

/// A single window that reports every whole-dp change, the way an application that wants
/// every breakpoint opens it.
fn reporting_host(content: fn()) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_theme(content, Theme::unified(DesignSystem::Material3))
        .with_window(WindowSettings::new().with_report_size(true));
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

fn resize(host: &mut ComposeHost, tree: &mut Tree, node: u32, width_dp: f32) {
    let (batch, _) = host
        .dispatch(report(node, width_dp))
        .expect("the size report dispatches");
    tree.apply(batch);
}

// ----- the bucket follows the width ----------------------------------------------------

#[Composable]
fn Bucket() {
    Text!(format!("{}", current_window_breakpoint(&EMBER)));
}

/// A width on a breakpoint belongs to the bucket above it; a width just below it does not.
#[test]
fn fr20_10_breakpoint_index_follows_the_window_width() {
    let (mut host, mut tree) = reporting_host(Bucket);
    for (width, bucket) in [
        (559.9, "0"),
        (560.0, "1"),
        (899.0, "1"),
        (900.0, "2"),
        (1278.9, "2"),
        (1279.0, "3"),
    ] {
        resize(&mut host, &mut tree, 0, width);
        assert_eq!(tree.texts(), vec![bucket], "at {width} dp");
    }
    // And back down, so the edges hold in both directions.
    for (width, bucket) in [(1278.9, "2"), (899.9, "1"), (559.0, "0")] {
        resize(&mut host, &mut tree, 0, width);
        assert_eq!(tree.texts(), vec![bucket], "at {width} dp");
    }
}

// ----- the reader runs again only on a bucket change -----------------------------------

#[Composable]
fn Reader() {
    READER_RUNS.with(|runs| runs.set(runs.get() + 1));
    Text!(format!("{}", current_window_breakpoint(&EMBER)));
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

/// A drag inside a bucket runs nothing again, even across the 840 dp class edge; crossing
/// a breakpoint runs the reader once and never its sibling.
#[test]
fn fr20_10_reader_reruns_only_on_a_bucket_change() {
    let (mut host, mut tree) = reporting_host(reader_and_sibling);
    let reader_runs = || READER_RUNS.with(Cell::get);
    let sibling_runs = || SIBLING_RUNS.with(Cell::get);
    let siblings = sibling_runs();

    resize(&mut host, &mut tree, 0, 700.0);
    assert_eq!(tree.texts(), vec!["1", "sibling"]);
    let settled = reader_runs();

    for width in 701..=899 {
        resize(&mut host, &mut tree, 0, width as f32);
        assert_eq!(
            reader_runs(),
            settled,
            "{width} dp is still bucket 1, so the reader is not run again"
        );
    }
    assert_eq!(tree.texts(), vec!["1", "sibling"]);

    resize(&mut host, &mut tree, 0, 900.0);
    assert_eq!(tree.texts(), vec!["2", "sibling"]);
    assert_eq!(reader_runs(), settled + 1, "crossing 900 dp runs it once");

    resize(&mut host, &mut tree, 0, 901.0);
    assert_eq!(reader_runs(), settled + 1);
    assert_eq!(
        sibling_runs(),
        siblings,
        "the sibling never reads the bucket"
    );
}

// ----- each window reads its own width ---------------------------------------------------

#[Composable]
fn Pane(index: usize) {
    PANE_RUNS.with(|runs| runs[index].set(runs[index].get() + 1));
    Text!(format!("{index}:{}", current_window_breakpoint(&EMBER)));
}

fn two_panes() {
    for index in 0..2_usize {
        key(index, || {
            Window!(|| {}, report_size = true, content = || Pane(index));
        });
    }
}

/// The ids of the windows a batch creates, in order.
fn window_ids(batch: &[u8]) -> Vec<u32> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Window,
            } => Some(node_id),
            _ => None,
        })
        .collect()
}

#[test]
fn fr20_10_breakpoint_reads_its_own_window() {
    let mut host = ComposeHost::with_theme(two_panes, Theme::unified(DesignSystem::Material3))
        .with_window(
            WindowSettings::new()
                .with_windows()
                .with_last_window_closed(LastWindowClosed::Exit),
        );
    let mut tree = Tree::default();
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    let windows = window_ids(&first);
    tree.apply(&first);
    assert_eq!(windows.len(), 2);
    assert_eq!(tree.texts(), vec!["0:0", "1:0"]);
    let runs = || PANE_RUNS.with(|runs| [runs[0].get(), runs[1].get()]);

    let before = runs();
    resize(&mut host, &mut tree, windows[1], 1300.0);
    assert_eq!(tree.texts(), vec!["0:0", "1:3"]);
    assert_eq!(runs(), [before[0], before[1] + 1]);

    let before = runs();
    resize(&mut host, &mut tree, windows[0], 600.0);
    assert_eq!(tree.texts(), vec!["0:1", "1:3"]);
    assert_eq!(runs(), [before[0] + 1, before[1]]);
}

// ----- the defaults ----------------------------------------------------------------------

#[Composable]
fn Unmeasured() {
    Text!(format!("{}", current_window_breakpoint(&EMBER)));
    Text!(format!("{}", current_window_breakpoint(&[])));
}

/// Before the Renderer measures anything the window is 0 dp wide, so the bucket is 0; an
/// empty list has only bucket 0 at any width.
#[test]
fn fr20_10_breakpoint_defaults_to_zero() {
    // Outside a composition, before anything is measured, there is nothing to remember
    // and the width is 0 dp.
    assert_eq!(current_window_breakpoint(&EMBER), 0);
    assert_eq!(current_window_breakpoint(&[]), 0);

    let (mut host, mut tree) = reporting_host(Unmeasured);
    assert_eq!(tree.texts(), vec!["0", "0"]);
    for width in [300.0, 900.0, 4000.0] {
        resize(&mut host, &mut tree, 0, width);
        assert_eq!(tree.texts()[1], "0", "an empty list at {width} dp");
    }
    assert_eq!(tree.texts()[0], "3");
}

// ----- the list itself -----------------------------------------------------------------

#[Composable]
fn Lists() {
    Text!(format!("{}", current_window_breakpoint(&EMBER)));
    Text!(format!(
        "{}",
        current_window_breakpoint(&[1279.0, 560.0, 900.0])
    ));
    Text!(format!(
        "{}",
        current_window_breakpoint(&[
            f32::NAN,
            560.0,
            -1.0,
            f32::INFINITY,
            0.0,
            900.0,
            f32::NEG_INFINITY,
            1279.0,
        ])
    ));
}

/// The bucket counts the breakpoints the width has reached, so the order of the list does
/// not matter, and values that are not widths are not counted. Nothing panics.
#[test]
fn fr20_10_breakpoint_list_order_and_bad_values() {
    let (mut host, mut tree) = reporting_host(Lists);
    assert_eq!(tree.texts(), vec!["0", "0", "0"], "unmeasured is bucket 0");
    for (width, bucket) in [
        (559.9, "0"),
        (560.0, "1"),
        (900.0, "2"),
        (1279.0, "3"),
        (5000.0, "3"),
    ] {
        resize(&mut host, &mut tree, 0, width);
        assert_eq!(tree.texts(), vec![bucket, bucket, bucket], "at {width} dp");
    }
}
