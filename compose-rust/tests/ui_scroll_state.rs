//! Programmatic scroll requests and scroll position reporting for `ScrollColumn` and
//! `ScrollRow`.
//!
//! A `ScrollState` reads the position from the Renderer's `ScrollChanged` report, which is in
//! physical pixels, and keeps it in dp along the axis the container scrolls on.

mod support;

use compose_rust::protocol::{HostEvent, encode_event};
use compose_rust::schema::PropertyKind;
use compose_rust::ui::*;
use compose_rust::{ComposeHost, EventPayload};
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

fn dispatch(host: &mut ComposeHost, tree: &mut Tree, event: &HostEvent<'_>) {
    let mut encoded = Vec::new();
    encode_event(event, &mut encoded).expect("the event encodes");
    let (batch, _) = host.dispatch_event(&encoded).expect("the event dispatches");
    let batch = batch.to_vec();
    tree.apply(&batch);
}

/// A `ScrollChanged` report for `node`'s `handler`, with the values on both axes.
fn report(node: u32, handler: u64, x: (f64, f64, f64), y: (f64, f64, f64)) -> HostEvent<'static> {
    HostEvent::new(
        node,
        handler,
        EventPayload::ScrollChanged {
            item: 0,
            settled: true,
            can_scroll_backward: x.0 > 0.0 || y.0 > 0.0,
            can_scroll_forward: x.0 < x.1 || y.0 < y.1,
            x_px: x.0,
            y_px: y.0,
            max_x_px: x.1,
            max_y_px: y.1,
            viewport_width_px: x.2,
            viewport_height_px: y.2,
        },
    )
}

/// The state, printed by a `Text` on the same screen so a test reads it from the tree.
fn shown(state: &ScrollState) -> String {
    format!(
        "offset={:.0}/max={:.0}/viewport={:.0}",
        state.offset(),
        state.max_offset(),
        state.viewport_size()
    )
}

/// Moving the state sends the target, and the Renderer's report of where the container is
/// updates the offset and the furthest offset. The same behaviour as before the state read
/// `ScrollChanged`: only the report it reads changed.
#[test]
fn fr62_scroll_state_sends_target_and_reads_offset_from_scroll_changed() {
    let (mut host, mut tree) = start(|| {
        let state = remember_scroll_state();
        Column!({
            Text!(format!(
                "offset={:.0}/max={:.0}",
                state.offset(),
                state.max_offset()
            ));
            let to_zero = state.clone();
            Button!("Top", move || to_zero.scroll_to(0.0));
            let to_far = state.clone();
            Button!("Bottom", move || to_far.animate_scroll_to(120.0));
            ScrollColumn!(scroll_state = &state, {});
        });
    });

    assert_eq!(tree.texts(), vec!["offset=0/max=0", "Top", "Bottom"]);

    // The Renderer reports y = 45 and a furthest offset of 200, in pixels at density 1.
    let (scroll_node, scroll_handler) =
        tree.find("ScrollColumn", None, 0, PropertyKind::OnScrollChanged);
    dispatch(
        &mut host,
        &mut tree,
        &report(
            scroll_node,
            scroll_handler,
            (0.0, 0.0, 320.0),
            (45.0, 200.0, 300.0),
        ),
    );
    assert_eq!(tree.texts(), vec!["offset=45/max=200", "Top", "Bottom"]);

    // Click "Bottom" -> animate_scroll_to(120.0) writes Value(120.0), Determinate(true), Count(1).
    let (bottom_node, bottom_handler) =
        tree.find("Button", Some("Bottom"), 0, PropertyKind::OnClick);
    dispatch(
        &mut host,
        &mut tree,
        &HostEvent::new(bottom_node, bottom_handler, EventPayload::Clicked),
    );
    let dump = tree.dump();
    assert!(dump.contains("Value=Float(120.0)"), "{dump}");
    assert!(dump.contains("Determinate=Bool(true)"), "{dump}");
    assert!(dump.contains("Count=Integer(1)"), "{dump}");

    // Click "Top" twice -> scroll_to(0.0) increments Count to 2 and then 3 so repeated 0.0
    // jumps still cross the wire.
    let (top_node, top_handler) = tree.find("Button", Some("Top"), 0, PropertyKind::OnClick);
    let click_top = HostEvent::new(top_node, top_handler, EventPayload::Clicked);
    dispatch(&mut host, &mut tree, &click_top);
    assert!(tree.dump().contains("Count=Integer(2)"), "{}", tree.dump());

    dispatch(&mut host, &mut tree, &click_top);
    assert!(tree.dump().contains("Count=Integer(3)"), "{}", tree.dump());
}

/// A container with a state hears `ScrollChanged` and asks for no `RangeRequested`, and one
/// without a state asks for neither.
#[test]
fn fr62_scroll_state_subscribes_scroll_changed_and_not_range_requested() {
    let (_host, tree) = start(|| {
        let state = remember_scroll_state();
        Column!({
            ScrollColumn!(scroll_state = &state, {});
            ScrollRow!(scroll_state = &state, {});
            ScrollColumn!({});
        });
    });
    assert_eq!(
        tree.handled("ScrollColumn", PropertyKind::OnScrollChanged)
            .len(),
        1
    );
    assert_eq!(
        tree.handled("ScrollRow", PropertyKind::OnScrollChanged)
            .len(),
        1
    );
    for widget in ["ScrollColumn", "ScrollRow"] {
        assert_eq!(
            tree.handled(widget, PropertyKind::OnRangeRequested),
            Vec::<(u32, u64)>::new(),
            "{widget} asked for RangeRequested"
        );
    }
}

/// Before the Renderer has said anything, the viewport is 0, like the offset and the furthest
/// offset.
#[test]
fn fr62_viewport_size_is_zero_before_the_first_report() {
    let (_host, tree) = start(|| {
        let state = remember_scroll_state();
        Column!({
            Text!(shown(&state));
            ScrollColumn!(scroll_state = &state, {});
        });
    });
    assert_eq!(tree.texts(), vec!["offset=0/max=0/viewport=0"]);
}

/// A `ScrollColumn` scrolls down, so its viewport is the report's height and not its width.
#[test]
fn fr62_viewport_size_reads_the_height_of_a_scroll_column() {
    let (mut host, mut tree) = start(|| {
        let state = remember_scroll_state();
        Column!({
            Text!(shown(&state));
            ScrollColumn!(scroll_state = &state, {});
        });
    });
    let (node, handler) = tree.find("ScrollColumn", None, 0, PropertyKind::OnScrollChanged);
    dispatch(
        &mut host,
        &mut tree,
        &report(node, handler, (7.0, 0.0, 640.0), (120.0, 700.0, 300.0)),
    );
    assert_eq!(tree.texts(), vec!["offset=120/max=700/viewport=300"]);
}

/// A `ScrollRow` scrolls sideways, so all three values come from the width axis.
#[test]
fn fr62_viewport_size_reads_the_width_of_a_scroll_row() {
    let (mut host, mut tree) = start(|| {
        let state = remember_scroll_state();
        Column!({
            Text!(shown(&state));
            ScrollRow!(scroll_state = &state, {});
        });
    });
    let (node, handler) = tree.find("ScrollRow", None, 0, PropertyKind::OnScrollChanged);
    dispatch(
        &mut host,
        &mut tree,
        &report(node, handler, (90.0, 610.0, 390.0), (0.0, 0.0, 48.0)),
    );
    assert_eq!(tree.texts(), vec!["offset=90/max=610/viewport=390"]);
}

/// At a density of 2 and one zoom step (1.2) a dp is 2.4 pixels, so a 720 pixel viewport is
/// 300 dp. The system text size, which is in `k` but only scales text, does not change it.
#[test]
fn fr62_scroll_state_converts_pixels_with_the_density_and_the_app_zoom() {
    let (mut host, mut tree) = start(|| {
        let state = remember_scroll_state();
        Column!({
            Text!(format!(
                "offset={:.3}/max={:.3}/viewport={:.3}",
                state.offset(),
                state.max_offset(),
                state.viewport_size()
            ));
            ScrollColumn!(scroll_state = &state, {});
        });
    });
    dispatch(
        &mut host,
        &mut tree,
        &HostEvent::new(0, 0, EventPayload::DensityChanged { scale: 2.0 }),
    );
    dispatch(
        &mut host,
        &mut tree,
        &HostEvent::new(
            0,
            0,
            EventPayload::ZoomChanged {
                k: 1.2 * 1.5,
                os: 1.5,
                level: 1,
            },
        ),
    );
    let (node, handler) = tree.find("ScrollColumn", None, 0, PropertyKind::OnScrollChanged);
    dispatch(
        &mut host,
        &mut tree,
        &report(node, handler, (0.0, 0.0, 960.0), (240.0, 1200.0, 720.0)),
    );
    assert_eq!(
        tree.texts(),
        vec!["offset=100.000/max=500.000/viewport=300.000"]
    );
}

/// A report with no viewport, as from a container laid out at no height, reads as 0, and the
/// rest of the report is still read.
#[test]
fn fr62_a_report_with_no_viewport_reads_as_zero() {
    let (mut host, mut tree) = start(|| {
        let state = remember_scroll_state();
        Column!({
            Text!(shown(&state));
            ScrollColumn!(scroll_state = &state, {});
        });
    });
    let (node, handler) = tree.find("ScrollColumn", None, 0, PropertyKind::OnScrollChanged);
    dispatch(
        &mut host,
        &mut tree,
        &report(node, handler, (0.0, 0.0, 320.0), (10.0, 400.0, 300.0)),
    );
    assert_eq!(tree.texts(), vec!["offset=10/max=400/viewport=300"]);
    dispatch(
        &mut host,
        &mut tree,
        &report(node, handler, (0.0, 0.0, 0.0), (0.0, 400.0, 0.0)),
    );
    assert_eq!(tree.texts(), vec!["offset=0/max=400/viewport=0"]);
}

/// The system text size is in the zoom's `k` but scales text alone, so the three values stay
/// in layout dp whatever it is. At a display scale of 1.5 and zoom level 2 (1.44) a layout dp
/// is 2.16 pixels. The same report reads the same before and after the reader's text size
/// goes from 1.0 to 1.3, which moves `k` from 1.44 to 1.872; read with `k`, the offset would
/// be 38.462 dp after the change instead of 50.
#[test]
fn fr62_scroll_state_values_do_not_move_with_the_system_text_size() {
    let (mut host, mut tree) = start(|| {
        let state = remember_scroll_state();
        Column!({
            Text!(format!(
                "offset={:.3}/max={:.3}/viewport={:.3}",
                state.offset(),
                state.max_offset(),
                state.viewport_size()
            ));
            ScrollColumn!(scroll_state = &state, {});
        });
    });
    let zoom = |os: f32| {
        HostEvent::new(
            0,
            0,
            EventPayload::ZoomChanged {
                k: os * 1.2 * 1.2,
                os,
                level: 2,
            },
        )
    };
    dispatch(
        &mut host,
        &mut tree,
        &HostEvent::new(0, 0, EventPayload::DensityChanged { scale: 1.5 }),
    );
    dispatch(&mut host, &mut tree, &zoom(1.0));
    let (node, handler) = tree.find("ScrollColumn", None, 0, PropertyKind::OnScrollChanged);
    let same_place = report(node, handler, (0.0, 0.0, 480.0), (108.0, 864.0, 540.0));
    dispatch(&mut host, &mut tree, &same_place);
    let expected = vec!["offset=50.000/max=400.000/viewport=250.000"];
    assert_eq!(tree.texts(), expected);

    // The reader enlarges their text. Layout is not resized by it, so the Renderer reports
    // the same pixels, and the state reads the same dp.
    dispatch(&mut host, &mut tree, &zoom(1.3));
    dispatch(&mut host, &mut tree, &same_place);
    assert_eq!(tree.texts(), expected);
}
