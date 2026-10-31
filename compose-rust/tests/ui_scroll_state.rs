//! Programmatic scroll requests and scroll offset reporting for `ScrollColumn` and `ScrollRow`.

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

#[test]
fn fr62_scroll_state_sends_target_and_updates_offset_from_range_report() {
    let (mut host, mut tree) = start(|| {
        let state = remember_scroll_state();
        Column().content(|| {
            Text(format!(
                "offset={:.0}/max={:.0}",
                state.offset(),
                state.max_offset()
            ));
            let to_zero = state.clone();
            Button("Top").on_click(move || to_zero.scroll_to(0.0));
            let to_far = state.clone();
            Button("Bottom").on_click(move || to_far.animate_scroll_to(120.0));
            ScrollColumn().scroll_state(&state).content(|| {});
        });
    });

    assert_eq!(tree.texts(), vec!["offset=0/max=0", "Top", "Bottom"]);

    // Renderer reports offset = 45.0 dp, max_offset = 200.0 dp via OnRangeRequested.
    let (scroll_node, range_handler) =
        tree.find("ScrollColumn", None, 0, PropertyKind::OnRangeRequested);
    dispatch(
        &mut host,
        &mut tree,
        &HostEvent {
            node_id: scroll_node,
            handler_id: range_handler,
            payload: EventPayload::RangeRequested {
                start: 45.0_f32.to_bits(),
                count: 200.0_f32.to_bits(),
            },
        },
    );
    assert_eq!(tree.texts(), vec!["offset=45/max=200", "Top", "Bottom"]);

    // Click "Bottom" -> animate_scroll_to(120.0) writes Value(120.0), Determinate(true), Count(1).
    let (bottom_node, bottom_handler) =
        tree.find("Button", Some("Bottom"), 0, PropertyKind::OnClick);
    dispatch(
        &mut host,
        &mut tree,
        &HostEvent {
            node_id: bottom_node,
            handler_id: bottom_handler,
            payload: EventPayload::Clicked,
        },
    );
    let dump = tree.dump();
    assert!(dump.contains("Value=Float(120.0)"), "{dump}");
    assert!(dump.contains("Determinate=Bool(true)"), "{dump}");
    assert!(dump.contains("Count=Integer(1)"), "{dump}");

    // Click "Top" twice -> scroll_to(0.0) increments Count to 2 and then 3 so repeated 0.0
    // jumps still cross the wire.
    let (top_node, top_handler) = tree.find("Button", Some("Top"), 0, PropertyKind::OnClick);
    let click_top = HostEvent {
        node_id: top_node,
        handler_id: top_handler,
        payload: EventPayload::Clicked,
    };
    dispatch(&mut host, &mut tree, &click_top);
    assert!(tree.dump().contains("Count=Integer(2)"), "{}", tree.dump());

    dispatch(&mut host, &mut tree, &click_top);
    assert!(tree.dump().contains("Count=Integer(3)"), "{}", tree.dump());
}
