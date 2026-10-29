//! A container with `on_click` is a control, and a container without one is not.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::HostEvent;
use compose_rust::runtime::*;
use compose_rust::schema::{EventPayload, PropertyKind};
use compose_rust::ui::*;
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

fn click(host: &mut ComposeHost, tree: &mut Tree, node_id: u32, handler_id: u64) {
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .expect("the click is accepted");
    let batch = batch.to_vec();
    tree.apply(&batch);
}

fn frame(host: &mut ComposeHost, tree: &mut Tree) {
    let batch = host.render_frame(0).expect("the frame encodes").to_vec();
    tree.apply(&batch);
}

#[composable]
fn Clickables() {
    let count = remember(|| mutable_state_of(0));
    Column().content(|| {
        Text(format!("{}", count.get()));
        let c = count.clone();
        Row()
            .on_click(move || c.update(|n| *n += 1))
            .content(|| {
                Text("row");
            });
        let c = count.clone();
        Box()
            .on_click(move || c.update(|n| *n += 10))
            .content(|| {
                Text("box");
            });
        let c = count.clone();
        Card()
            .on_click(move || c.update(|n| *n += 100))
            .content(|| {
                Text("card");
            });
        let c = count.clone();
        Surface()
            .on_click(move || c.update(|n| *n += 1000))
            .content(|| {
                Text("surface");
            });
        let c = count.clone();
        Column()
            .on_click(move || c.update(|n| *n += 10000))
            .content(|| {
                Text("column");
            });
        Row().content(|| {
                Text("plain row");
            });
    });
}

fn handled_nth(tree: &Tree, widget: &str, nth: usize) -> (u32, u64) {
    tree.handled(widget, PropertyKind::OnClick)[nth]
}

#[test]
fn fr_55_a_clicked_container_runs_its_closure_across_recompositions() {
    let (mut host, mut tree) = start(Clickables);
    for round in 1..=3_u32 {
        let (node, handler) = handled_nth(&tree, "Row", 0);
        click(&mut host, &mut tree, node, handler);
        frame(&mut host, &mut tree);
        assert_eq!(tree.texts()[0], round.to_string());
    }
    let mut expected = 3;
    for (widget, step) in [
        ("Box", 10),
        ("Card", 100),
        ("Surface", 1000),
    ] {
        let (node, handler) = handled_nth(&tree, widget, 0);
        click(&mut host, &mut tree, node, handler);
        expected += step;
        assert_eq!(tree.texts()[0], expected.to_string(), "{widget}");
    }
    // The outer Column has no handler, so the only Column with one is the inner.
    let (node, handler) = handled_nth(&tree, "Column", 0);
    click(&mut host, &mut tree, node, handler);
    expected += 10000;
    assert_eq!(tree.texts()[0], expected.to_string());
}

#[test]
fn fr_55_a_container_without_a_click_handler_is_not_a_control() {
    let (_host, tree) = start(Clickables);
    // Six containers were written: five with a handler, the plain Row without one.
    assert_eq!(tree.handled("Row", PropertyKind::OnClick).len(), 1);
}
