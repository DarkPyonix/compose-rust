//! A node can say what it is and what it is called to assistive technology.

mod support;

use compose_rust::ComposeHost;
use compose_rust::runtime::*;
use compose_rust::ui::*;
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> Tree {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    tree
}

#[composable]
fn Plain() {
    Column().content(|| {
        Box().modifier(Modifier.padding(4.0)).content(|| {
            Text("plain");
        });
        Button("Save").on_click(|| {});
        Switch(true);
    });
}

#[composable]
fn Named() {
    Column().content(|| {
        Box()
            .modifier(Modifier.label("Refresh").role(Role::Button))
            .on_click(|| {})
            .content(|| {
                Text("↻");
            });
        Button("")
            .icon(IconRole::Add)
            .accessible_name("Import")
            .on_click(|| {});
        Button("Save")
            .modifier(Modifier.label("From the modifier"))
            .accessible_name("Save the draft")
            .on_click(|| {});
        Switch(true).accessible_name("Notifications");
        Checkbox(false).accessible_name("Remember me");
        RadioButton(true).accessible_name("Small");
        Slider(0.5).accessible_name("Volume");
    });
}

#[composable]
fn Stated() {
    Column().content(|| {
        Box()
            .modifier(Modifier.semantics(Role::Tab, "Files", SemanticsState::selected(true)))
            .content(|| {});
        Box().modifier(Modifier.checked(false)).content(|| {});
    });
}

#[test]
fn fr68_a_node_that_asks_for_no_semantics_sends_none() {
    let dump = start(Plain).dump();
    assert!(!dump.contains("AccessibleName"), "{dump}");
    assert!(!dump.contains("Semantics"), "{dump}");
}

#[test]
fn fr68_the_modifier_sends_the_name_and_the_role() {
    let dump = start(Named).dump();
    assert!(dump.contains("AccessibleName=\"Refresh\""), "{dump}");
    assert!(
        dump.contains("Semantics { role: Button, state: 0 }"),
        "{dump}"
    );
}

#[test]
fn fr68_the_modifier_sends_the_state_and_an_unset_role_stays_none() {
    let dump = start(Stated).dump();
    // Selected on is 2 in the selected field, which starts two bits up.
    assert!(dump.contains("Semantics { role: Tab, state: 8 }"), "{dump}");
    assert!(dump.contains("AccessibleName=\"Files\""), "{dump}");
    // Checked off is 1 in the checked field, with no role given.
    assert!(
        dump.contains("Semantics { role: None, state: 1 }"),
        "{dump}"
    );
}

#[test]
fn fr68_accessible_name_reaches_every_control_that_has_one() {
    let dump = start(Named).dump();
    for (widget, name) in [
        ("Button", "Import"),
        ("Switch", "Notifications"),
        ("Checkbox", "Remember me"),
        ("RadioButton", "Small"),
        ("Slider", "Volume"),
    ] {
        let line = dump
            .lines()
            .find(|line| line.trim_start().starts_with(widget))
            .unwrap_or_else(|| panic!("no {widget} in\n{dump}"));
        assert!(
            line.contains(&format!("AccessibleName=\"{name}\"")),
            "{widget}: {line}"
        );
    }
}

#[test]
fn fr68_the_widget_name_wins_over_the_modifier_label_and_the_icon() {
    let dump = start(Named).dump();
    assert!(dump.contains("AccessibleName=\"Save the draft\""), "{dump}");
    assert!(!dump.contains("From the modifier"), "{dump}");
}
