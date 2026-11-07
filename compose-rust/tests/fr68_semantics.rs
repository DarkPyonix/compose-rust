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

#[Composable]
fn Plain() {
    Column().content(|| {
        Box().modifier(Modifier.padding(4.0)).content(|| {
            Text("plain");
        });
        Button("Save").on_click(|| {});
        Switch(true);
    });
}

#[Composable]
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

#[Composable]
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

#[Composable]
fn Renaming() {
    let named = remember(|| mutable_state_of(true));
    Column().content(|| {
        let flip = named.clone();
        Row()
            .on_click(move || flip.update(|n| *n = !*n))
            .content(|| {
                Text("flip");
            });
        let button = Button("").icon(IconRole::Add).on_click(|| {});
        if named.get() {
            button.accessible_name("Import")
        } else {
            button
        };
    });
}

#[test]
fn fr68_a_widget_that_stops_naming_itself_sends_a_removal() {
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
    use compose_rust::schema::{EventPayload, PropertyKind};

    let mut host = ComposeHost::with_content(Renaming);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    assert!(tree.dump().contains("AccessibleName=\"Import\""));

    let (node_id, handler_id) = tree.handled("Row", PropertyKind::OnClick)[0];
    let mut batches = Vec::new();
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .expect("the click is accepted");
    batches.push(batch.to_vec());
    batches.push(host.render_frame(0).expect("the frame encodes").to_vec());

    let mut removed = 0;
    for batch in &batches {
        for mutation in decode_batch(batch).expect("the batch decodes") {
            if let Mutation::SetProp {
                property: PropertyKind::AccessibleName,
                value,
                ..
            } = mutation
            {
                assert_eq!(value, PropertyValue::None);
                removed += 1;
            }
        }
        tree.apply(batch);
    }
    assert_eq!(removed, 1, "one removal of the name was expected");
    assert!(!tree.dump().contains("AccessibleName"), "{}", tree.dump());
}

#[Composable]
fn Described() {
    let described = remember(|| mutable_state_of(true));
    Column().content(|| {
        let flip = described.clone();
        Row()
            .on_click(move || flip.update(|d| *d = !*d))
            .content(|| {
                Text("flip");
            });
        let modifier = if described.get() {
            Modifier.semantics(Role::Heading, "Title", SemanticsState::expanded(true))
        } else {
            Modifier
        };
        Box().modifier(modifier).content(|| {});
    });
}

#[test]
fn fr71_removing_semantics_restores_the_defaults() {
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
    use compose_rust::schema::{EventPayload, Modifier as Wire, PropertyKind};

    let mut host = ComposeHost::with_content(Described);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    assert!(
        tree.dump().contains("Semantics { role: Heading"),
        "{}",
        tree.dump()
    );

    let (node_id, handler_id) = tree.handled("Row", PropertyKind::OnClick)[0];
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .expect("the click is accepted");
    let batches = [
        batch.to_vec(),
        host.render_frame(0).expect("the frame encodes").to_vec(),
    ];

    let (mut name_removed, mut modifier_cleared) = (0, 0);
    for batch in &batches {
        for mutation in decode_batch(batch).expect("the batch decodes") {
            match mutation {
                Mutation::SetProp {
                    property: PropertyKind::AccessibleName,
                    value,
                    ..
                } => {
                    assert_eq!(value, PropertyValue::None);
                    name_removed += 1;
                }
                Mutation::SetModifier {
                    modifier: Wire::Empty,
                    ..
                } => modifier_cleared += 1,
                _ => {}
            }
        }
        tree.apply(batch);
    }
    assert_eq!(name_removed, 1, "one removal of the name was expected");
    assert!(modifier_cleared >= 1, "the semantics slot was not cleared");
    assert!(!tree.dump().contains("Semantics"), "{}", tree.dump());
    assert!(!tree.dump().contains("AccessibleName"), "{}", tree.dump());
}

#[Composable]
fn EveryNewRole() {
    Column().content(|| {
        for role in [
            Role::TabList,
            Role::Tree,
            Role::TreeItem,
            Role::Menu,
            Role::MenuItem,
            Role::TextBox,
            Role::Heading,
            Role::Navigation,
            Role::List,
            Role::ListItem,
            Role::Group,
        ] {
            Box()
                .modifier(Modifier.role(role).disabled(false).click_action(true))
                .content(|| {});
        }
    });
}

#[test]
fn fr71_every_new_role_round_trips_and_is_exposed() {
    let dump = start(EveryNewRole).dump();
    for role in [
        "TabList",
        "Tree",
        "TreeItem",
        "Menu",
        "MenuItem",
        "TextBox",
        "Heading",
        "Navigation",
        "List",
        "ListItem",
        "Group",
    ] {
        assert!(
            dump.contains(&format!("Semantics {{ role: {role}, state: ")),
            "{role} missing in\n{dump}"
        );
    }
}
