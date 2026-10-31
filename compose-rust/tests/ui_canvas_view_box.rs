//! `Canvas` reference coordinate space (`view_box` and `fractional`) reaches the wire.

mod support;

use compose_rust::ComposeHost;
use compose_rust::ui::*;
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

#[test]
fn fr63_canvas_view_box_writes_min_and_max_properties() {
    let (_host, tree) = start(|| {
        let list = DrawList::builder()
            .fractional()
            .rect(Paint::Role(ColorRole::Primary), 0.0, 0.0, 0.5, 1.0, 0.0)
            .build();
        Canvas(list);
    });
    let dump = tree.dump();
    assert!(dump.contains("Min=Float(1.0)"), "{dump}");
    assert!(dump.contains("Max=Float(1.0)"), "{dump}");
}

#[test]
fn fr63_plain_canvas_does_not_write_min_or_max_properties() {
    let (_host, tree) = start(|| {
        let list = DrawList::builder()
            .rect(Paint::Role(ColorRole::Primary), 0.0, 0.0, 20.0, 20.0, 0.0)
            .build();
        Canvas(list);
    });
    let dump = tree.dump();
    assert!(!dump.contains("Min="), "{dump}");
    assert!(!dump.contains("Max="), "{dump}");
}
