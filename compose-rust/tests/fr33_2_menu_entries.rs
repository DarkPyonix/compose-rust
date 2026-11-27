//! Menu entries reach the wire as entries: a `MenuItem` is a `Button` marked as one and
//! carries its shortcut label, and a `Submenu` is a `Menu` marked as one whose first child
//! names it. A Renderer that puts menus up as the operating system's own reads the marks.

mod support;

use compose_rust::protocol::{BatchEncoder, Mutation, PropertyValue};
use compose_rust::ui::*;
use compose_rust::{ComposeHost, PropertyKind};
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> Tree {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    tree
}

/// The dump line of the first node whose line contains `needle`.
fn line_with<'a>(dump: &'a str, needle: &str) -> &'a str {
    dump.lines()
        .find(|line| line.contains(needle))
        .unwrap_or_else(|| panic!("no node with {needle} in\n{dump}"))
}

#[test]
fn fr33_2_the_two_menu_properties_keep_their_tags() {
    assert_eq!(PropertyKind::ShortcutLabel as u16, 228);
    assert_eq!(PropertyKind::MenuEntry as u16, 229);
    assert_eq!(
        PropertyKind::from_name("shortcut_label"),
        Ok(PropertyKind::ShortcutLabel)
    );
    assert_eq!(
        PropertyKind::from_name("menu_entry"),
        Ok(PropertyKind::MenuEntry)
    );
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetProp {
            node_id: 2,
            property: PropertyKind::ShortcutLabel,
            value: PropertyValue::String("Ctrl+S"),
        })
        .unwrap();
    encoder
        .encode(&Mutation::SetProp {
            node_id: 2,
            property: PropertyKind::MenuEntry,
            value: PropertyValue::Bool(true),
        })
        .unwrap();
    assert!(!encoder.finish().unwrap().is_empty());
}

#[test]
fn fr33_2_menu_item_is_a_marked_button_with_its_shortcut_label() {
    let tree = start(|| {
        Menu!(
            true,
            || {},
            anchor = Some(|| {
                Button!("File", || {});
            }),
            {
                MenuItem!("Save", || {}, shortcut_label = "Ctrl+S");
                MenuItem!("Close", || {});
            }
        );
    });
    let dump = tree.dump();
    let save = line_with(&dump, "Text=\"Save\"");
    assert!(save.trim_start().starts_with("Button "), "{dump}");
    assert!(save.contains("MenuEntry=Bool(true)"), "{dump}");
    assert!(save.contains("ShortcutLabel=\"Ctrl+S\""), "{dump}");

    // A shortcut label that is not given is not sent at all.
    let close = line_with(&dump, "Text=\"Close\"");
    assert!(close.contains("MenuEntry=Bool(true)"), "{dump}");
    assert!(!close.contains("ShortcutLabel"), "{dump}");

    // The anchor is an ordinary button, not an entry.
    let file = line_with(&dump, "Text=\"File\"");
    assert!(!file.contains("MenuEntry"), "{dump}");
}

#[test]
fn fr33_2_submenu_is_a_marked_menu_named_by_its_first_child() {
    let tree = start(|| {
        Menu!(true, || {}, {
            MenuItem!("Open", || {});
            Separator!(None);
            Submenu!("Export", icon = IconRole::Share, enabled = false, {
                MenuItem!("PDF", || {});
                MenuItem!("PNG", || {});
            });
        });
    });
    let dump = tree.dump();
    let lines: Vec<&str> = dump.lines().collect();
    let outer = lines
        .iter()
        .position(|line| line.trim_start().starts_with("Menu "))
        .unwrap_or_else(|| panic!("no menu in\n{dump}"));
    let submenu = lines
        .iter()
        .enumerate()
        .skip(outer + 1)
        .find(|(_, line)| line.trim_start().starts_with("Menu "))
        .map(|(index, _)| index)
        .unwrap_or_else(|| panic!("no submenu in\n{dump}"));
    assert!(lines[submenu].contains("MenuEntry=Bool(true)"), "{dump}");
    assert!(
        !lines[outer].contains("MenuEntry"),
        "the outer menu is a menu, not an entry:\n{dump}"
    );

    // Its first child is the row that names it, then its own entries.
    let row = lines[submenu + 1];
    assert!(row.trim_start().starts_with("Button "), "{dump}");
    assert!(row.contains("Text=\"Export\""), "{dump}");
    assert!(row.contains("Enabled=Bool(false)"), "{dump}");
    assert!(row.contains("MenuEntry=Bool(true)"), "{dump}");
    assert!(row.contains("Icon="), "{dump}");
    assert!(lines[submenu + 2].contains("Text=\"PDF\""), "{dump}");
    assert!(lines[submenu + 3].contains("Text=\"PNG\""), "{dump}");

    // A submenu's open state is the Renderer's, so none is sent.
    assert!(!lines[submenu].contains("Open="), "{dump}");
}
