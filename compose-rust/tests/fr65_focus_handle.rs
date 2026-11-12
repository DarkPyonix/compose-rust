//! A `FocusHandle` bound to a `ui` widget puts keyboard focus on that widget's node.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{HostEvent, Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{EventPayload, PropertyKind};
use compose_rust::ui::*;
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree, Vec<u32>) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    tree.apply(&batch);
    let focus = focus_requests(&batch);
    (host, tree, focus)
}

/// The nodes the batch asks to focus, in order.
fn focus_requests(batch: &[u8]) -> Vec<u32> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::RequestFocus { node_id } => Some(node_id),
            _ => None,
        })
        .collect()
}

/// Clicks the button labelled `label` and answers the focus requests in the batch.
fn click(host: &mut ComposeHost, tree: &mut Tree, label: &str) -> Vec<u32> {
    let (node_id, handler_id) = tree.find("Button", Some(label), 0, PropertyKind::OnClick);
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .expect("the click is accepted");
    let batch = batch.to_vec();
    tree.apply(&batch);
    focus_requests(&batch)
}

#[test]
fn fr65_ui_focus_handle_requests_focus_for_its_node() {
    let (mut host, mut tree, first) = start(|| {
        let drawer = use_focus_handle();
        let opener = use_focus_handle();
        Column().content(|| {
            let take = drawer.clone();
            Button("Open")
                .focus_handle(&opener)
                .on_click(move || take.request());
            let back = opener.clone();
            Column()
                .focus_handle(&drawer)
                .on_key_down(move |key| {
                    if key.text() == "Escape" {
                        key.consume();
                        back.request();
                    }
                })
                .content(|| {
                    Text("Drawer");
                });
        });
    });
    assert!(first.is_empty(), "nothing asked for focus yet: {first:?}");

    let (drawer, key_handler) = tree.find("Column", None, 1, PropertyKind::OnKeyDown);
    assert_eq!(click(&mut host, &mut tree, "Open"), vec![drawer]);

    // Escape in the drawer gives focus back to the button that opened it.
    let (opener, _) = tree.find("Button", Some("Open"), 0, PropertyKind::OnClick);
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: drawer,
            handler_id: key_handler,
            payload: EventPayload::KeyDown {
                key: Key::Other,
                shift_key: false,
                ctrl_key: false,
                alt_key: false,
                meta_key: false,
                repeat: false,
                code: 0x29,
                text: "Escape",
            },
        })
        .expect("the key is accepted");
    let batch = batch.to_vec();
    tree.apply(&batch);
    assert_eq!(focus_requests(&batch), vec![opener]);
}

#[test]
fn fr65_ui_focus_handle_text_field_takes_focus() {
    let (mut host, mut tree, _) = start(|| {
        let field = use_focus_handle();
        Column().content(|| {
            let take = field.clone();
            Button("Edit").on_click(move || take.request());
            TextField().focus_handle(&field);
        });
    });
    let (field, _) = tree.find("TextField", None, 0, PropertyKind::OnKeyDown);
    assert_eq!(click(&mut host, &mut tree, "Edit"), vec![field]);
}

#[test]
fn fr65_ui_focus_handle_request_before_mount_does_nothing() {
    let (mut host, mut tree, first) = start(|| {
        let early = use_focus_handle();
        let never = use_focus_handle();
        // Asked before the widget that takes the handle is composed.
        early.request();
        Column().content(|| {
            let ask = never.clone();
            Button("Ask").on_click(move || ask.request());
            Column().focus_handle(&early).content(|| {});
        });
    });
    assert!(
        first.is_empty(),
        "an unbound handle asked for focus: {first:?}"
    );
    // A handle no widget ever took asks for nothing either, and does not panic.
    assert!(click(&mut host, &mut tree, "Ask").is_empty());
    // A handle made outside any composition is bound to nothing.
    let loose = FocusHandle::new();
    loose.request();
    assert!(!loose.is_bound());
}

#[test]
fn fr65_ui_focus_handle_two_requests_in_one_frame_give_one_record() {
    let (mut host, mut tree, _) = start(|| {
        let target = use_focus_handle();
        Column().content(|| {
            let ask = target.clone();
            Button("Twice").on_click(move || {
                ask.request();
                ask.clone().request();
            });
            Box()
                .focus_handle(&target)
                .on_key_down(|_| {})
                .content(|| {});
        });
    });
    let (node, _) = tree.find("Box", None, 0, PropertyKind::OnKeyDown);
    assert_eq!(click(&mut host, &mut tree, "Twice"), vec![node]);
    // The request was spent: the next frame asks for nothing.
    let batch = host.render_frame(0).expect("the frame encodes").to_vec();
    tree.apply(&batch);
    assert!(focus_requests(&batch).is_empty());
    // And a new request asks again.
    assert_eq!(click(&mut host, &mut tree, "Twice"), vec![node]);
}

#[test]
fn fr65_ui_focus_handle_follows_its_node_across_a_re_render() {
    let (mut host, mut tree, _) = start(|| {
        let target = use_focus_handle();
        let wide = remember(|| mutable_state_of(false));
        let count = remember(|| mutable_state_of(0));
        Column().content(|| {
            Text(format!("{}", count.get()));
            Button("Bump").on_click(move || count.update(|count| *count += 1));
            Button("Swap").on_click(move || wide.update(|wide| *wide = !*wide));
            let ask = target.clone();
            Button("Ask").on_click(move || ask.request());
            if wide.get() {
                Row()
                    .focus_handle(&target)
                    .on_key_down(|_| {})
                    .content(|| {});
            } else {
                Column()
                    .focus_handle(&target)
                    .on_key_down(|_| {})
                    .content(|| {});
            }
        });
    });
    let (column, _) = tree.find("Column", None, 1, PropertyKind::OnKeyDown);
    assert_eq!(click(&mut host, &mut tree, "Ask"), vec![column]);

    // A re-render that keeps the node keeps the binding.
    assert!(click(&mut host, &mut tree, "Bump").is_empty());
    assert_eq!(tree.texts()[0], "1");
    assert_eq!(click(&mut host, &mut tree, "Ask"), vec![column]);

    // A re-render that replaces the node moves the binding to the new one.
    assert!(click(&mut host, &mut tree, "Swap").is_empty());
    let (row, _) = tree.find("Row", None, 0, PropertyKind::OnKeyDown);
    assert_ne!(row, column);
    assert_eq!(click(&mut host, &mut tree, "Ask"), vec![row]);
}
