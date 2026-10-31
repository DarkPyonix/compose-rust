//! Cross-axis intrinsic sizing on `Row` and `Column` reaches the wire cleanly.

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
fn fr61_row_intrinsic_size_writes_collapsible_property() {
    let (_host, tree) = start(|| {
        Row().equal_height(true).content(|| {});
    });
    let dump = tree.dump();
    assert!(dump.contains("Collapsible=Bool(true)"), "{dump}");
}

#[test]
fn fr61_plain_row_does_not_write_collapsible_property() {
    let (_host, tree) = start(|| {
        Row().content(|| {});
    });
    let dump = tree.dump();
    assert!(!dump.contains("Collapsible"), "{dump}");
}
