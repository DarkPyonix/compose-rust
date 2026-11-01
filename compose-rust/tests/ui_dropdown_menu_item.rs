//! Closed-field `Select` / `Dropdown::closed(true)` and `MenuItem` reach the wire.

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
fn fr64_select_marks_the_dropdown_as_a_closed_field() {
    let (_host, tree) = start(|| {
        Select(1).content(|| {
            Text("Opus 4.7");
            Text("Sonnet 4.6");
        });
    });
    let dump = tree.dump();
    assert!(dump.contains("Dropdown "), "{dump}");
    assert!(dump.contains("SelectedIndex=Integer(1)"), "{dump}");
    assert!(dump.contains("Collapsible=Bool(true)"), "{dump}");
}

#[test]
fn fr64_menu_item_writes_left_aligned_text_button_with_checked_state() {
    let (_host, tree) = start(|| {
        Menu(true)
            .anchor(|| {
                Button("File");
            })
            .content(|| {
                MenuItem("Open")
                    .icon(IconRole::Search)
                    .selected(true)
                    .modifier(Modifier.fill_max_width());
            });
    });
    let dump = tree.dump();
    assert!(dump.contains("Text=\"Open\""), "{dump}");
    assert!(dump.contains("Checked=Bool(true)"), "{dump}");
    assert!(dump.contains("Variant=Integer(4)"), "{dump}");
    assert!(dump.contains("TextAlign=Integer(1)"), "{dump}");
}
