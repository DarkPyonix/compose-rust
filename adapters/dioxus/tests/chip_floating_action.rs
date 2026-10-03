//! Widget tags 38 and 39: a chip, and the one action a screen is about.
//!
//! What is checked here is the Host's half. A chip carries its label and whether it is
//! chosen, and whether it is chosen is the Host's: it changes only because the Host's own
//! click handler changed it, never because the Renderer flipped something on its own. A
//! floating action carries an icon, a label and a click and nothing else, because where it
//! sits and what shape it takes is the design system's answer, and an attribute that let
//! the Host say "bottom right" would be the Host answering instead.
//!
//! The other half, that seven systems draw these seven ways and put the action in more
//! than one place, is checked in the Renderer's tests, where the design systems are.

use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
use dioxus_compose_adapter::schema::SCHEMA_DESCRIPTOR;
use dioxus_compose_adapter::{EventPayload, Host, PropertyKind, WidgetKind};
use std::collections::BTreeSet;
use std::sync::atomic::{AtomicUsize, Ordering};

/// What one batch said, with the borrowed strings copied out so the batch can be dropped.
#[derive(Clone, Debug, PartialEq)]
enum Record {
    Create(u32, WidgetKind),
    Prop(u32, PropertyKind, Value),
    Modifier(u32),
}

#[derive(Clone, Debug, PartialEq)]
enum Value {
    None,
    Text(String),
    Bool(bool),
    Integer(i64),
    Float(f32),
    Bytes,
}

fn records(batch: &[u8]) -> Vec<Record> {
    decode_batch(batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create { node_id, widget } => Some(Record::Create(node_id, widget)),
            Mutation::SetProp {
                node_id,
                property,
                value,
            } => Some(Record::Prop(
                node_id,
                property,
                match value {
                    PropertyValue::None => Value::None,
                    PropertyValue::String(text) => Value::Text(text.to_owned()),
                    PropertyValue::Bool(value) => Value::Bool(value),
                    PropertyValue::Integer(value) => Value::Integer(value),
                    PropertyValue::Float(value) => Value::Float(value),
                    PropertyValue::Bytes(_) => Value::Bytes,
                },
            )),
            Mutation::SetModifier { node_id, .. } => Some(Record::Modifier(node_id)),
            _ => None,
        })
        .collect()
}

fn created(records: &[Record], widget: WidgetKind) -> Vec<u32> {
    records
        .iter()
        .filter_map(|record| match record {
            Record::Create(node_id, kind) if *kind == widget => Some(*node_id),
            _ => None,
        })
        .collect()
}

fn prop(records: &[Record], node: u32, kind: PropertyKind) -> Option<Value> {
    records.iter().rev().find_map(|record| match record {
        Record::Prop(node_id, property, value) if *node_id == node && *property == kind => {
            Some(value.clone())
        }
        _ => None,
    })
}

fn handler(records: &[Record], node: u32) -> u64 {
    match prop(records, node, PropertyKind::OnClick) {
        Some(Value::Integer(handler)) => handler as u64,
        other => panic!("node {node} has no click handler: {other:?}"),
    }
}

fn click(host: &mut Host, node: u32, handler_id: u64) -> Vec<Record> {
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: node,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .unwrap();
    records(batch)
}

/// The two tags are fixed, and the descriptor names both after `FileDropTarget`.
///
/// The descriptor is positional: it is what the two sides hash to agree they speak the
/// same schema, so a widget appended out of order would change the meaning of the tags
/// after it.
#[test]
fn fr15_2_10_chip_and_floating_action_have_their_own_widget_tags() {
    assert_eq!(WidgetKind::Chip as u16, 38);
    assert_eq!(WidgetKind::FloatingAction as u16, 39);
    assert_eq!(
        WidgetKind::try_from(38).ok(),
        Some(WidgetKind::Chip),
        "tag 38 does not decode to a Chip",
    );
    assert_eq!(
        WidgetKind::try_from(39).ok(),
        Some(WidgetKind::FloatingAction)
    );

    let widgets = SCHEMA_DESCRIPTOR
        .split(';')
        .find_map(|part| part.strip_prefix("widgets="))
        .expect("the descriptor lists no widgets");
    assert!(
        widgets.contains("Chip") && widgets.contains("FloatingAction"),
        "the schema descriptor does not name the two widgets: {widgets}",
    );
    let names: Vec<&str> = widgets.split(',').collect();
    let chip = names.iter().position(|name| *name == "Chip").unwrap();
    let action = names
        .iter()
        .position(|name| *name == "FloatingAction")
        .unwrap();
    assert_eq!(
        action,
        chip + 1,
        "FloatingAction must follow Chip: {widgets}"
    );
}

static FILTERS: AtomicUsize = AtomicUsize::new(0);

fn chip_app() -> Element {
    let mut unread = use_signal(|| false);
    rsx! {
        Row {
            Chip {
                text: "Unread",
                selected: unread(),
                on_click: move |()| {
                    FILTERS.fetch_add(1, Ordering::SeqCst);
                    unread.set(!unread());
                },
            }
            Chip { text: "Flagged", icon: IconRole::Check, selected: true }
        }
    }
}

/// A chip sends its label and whether it is chosen, and nothing about what it looks like.
#[test]
fn fr15_2_10_a_chip_sends_its_label_and_whether_it_is_chosen() {
    let mut host = Host::new(chip_app);
    let initial = records(host.rebuild().unwrap());
    let chips = created(&initial, WidgetKind::Chip);
    assert_eq!(chips.len(), 2, "two chips were declared: {initial:?}");

    assert_eq!(
        prop(&initial, chips[0], PropertyKind::Text),
        Some(Value::Text("Unread".into())),
    );
    assert_eq!(
        prop(&initial, chips[0], PropertyKind::Checked),
        Some(Value::Bool(false)),
        "the chosen state travels as the same boolean the toggles use",
    );
    assert_eq!(
        prop(&initial, chips[1], PropertyKind::Checked),
        Some(Value::Bool(true))
    );
    assert_eq!(
        prop(&initial, chips[1], PropertyKind::Icon),
        Some(Value::Integer(i64::from(IconRole::Check as u16))),
    );
    for chip in &chips {
        for kind in [
            PropertyKind::Variant,
            PropertyKind::Color,
            PropertyKind::TypeRole,
        ] {
            assert_eq!(
                prop(&initial, *chip, kind),
                None,
                "a chip sent {kind:?}, which is the design system's to decide",
            );
        }
    }
}

/// Choosing a chip is the Host's: the click reaches the Host's handler, the handler
/// changes the Host's own state, and the new state comes back as one property change on
/// that chip and nothing else.
#[test]
fn fr15_2_10_a_chips_selection_reaches_the_host_through_its_click() {
    FILTERS.store(0, Ordering::SeqCst);
    let mut host = Host::new(chip_app);
    let initial = records(host.rebuild().unwrap());
    let chip = created(&initial, WidgetKind::Chip)[0];
    let other = created(&initial, WidgetKind::Chip)[1];
    let handler_id = handler(&initial, chip);

    let after = click(&mut host, chip, handler_id);
    assert_eq!(
        FILTERS.load(Ordering::SeqCst),
        1,
        "the click never reached the Host"
    );
    assert_eq!(
        prop(&after, chip, PropertyKind::Checked),
        Some(Value::Bool(true)),
        "the Host chose the chip and the chip was not told: {after:?}",
    );
    assert!(
        created(&after, WidgetKind::Chip).is_empty(),
        "choosing a chip rebuilt the chips: {after:?}",
    );
    assert_eq!(
        prop(&after, other, PropertyKind::Checked),
        None,
        "choosing one chip resent its neighbour: {after:?}",
    );

    let again = click(&mut host, chip, handler(&initial, chip));
    assert_eq!(FILTERS.load(Ordering::SeqCst), 2);
    assert_eq!(
        prop(&again, chip, PropertyKind::Checked),
        Some(Value::Bool(false))
    );
}

fn chip_without_a_handler() -> Element {
    rsx! {
        Chip { text: "Static", selected: false }
    }
}

/// A chip whose Host never changes it stays as it was sent. The Renderer is not asked to
/// keep a chosen state of its own, so with no handler there is nothing that could move it.
#[test]
fn fr15_2_10_a_chip_nobody_handles_stays_as_the_host_sent_it() {
    let mut host = Host::new(chip_without_a_handler);
    let initial = records(host.rebuild().unwrap());
    let chip = created(&initial, WidgetKind::Chip)[0];
    assert_eq!(
        prop(&initial, chip, PropertyKind::Checked),
        Some(Value::Bool(false))
    );
    let handler_id = handler(&initial, chip);
    let after = click(&mut host, chip, handler_id);
    assert_eq!(prop(&after, chip, PropertyKind::Checked), None, "{after:?}");
}

static ACTIONS: AtomicUsize = AtomicUsize::new(0);

fn action_app() -> Element {
    rsx! {
        dioxus_compose_adapter::Box {
            FloatingAction {
                icon: IconRole::Compose,
                text: "New message",
                on_click: move |()| {
                    ACTIONS.fetch_add(1, Ordering::SeqCst);
                },
            }
        }
    }
}

/// A floating action carries an icon, a label and a click, and nothing that could say
/// where it goes or what it is drawn as.
#[test]
fn fr15_2_10_a_floating_action_sends_only_its_icon_label_and_click() {
    let mut host = Host::new(action_app);
    let initial = records(host.rebuild().unwrap());
    let actions = created(&initial, WidgetKind::FloatingAction);
    assert_eq!(actions.len(), 1, "{initial:?}");
    let action = actions[0];

    let sent: BTreeSet<String> = initial
        .iter()
        .filter_map(|record| match record {
            Record::Prop(node, kind, _) if *node == action => Some(format!("{kind:?}")),
            _ => None,
        })
        .collect();
    let expected: BTreeSet<String> = ["Text", "Icon", "OnClick"]
        .into_iter()
        .map(str::to_string)
        .collect();
    assert_eq!(
        sent, expected,
        "a floating action sends its icon, its label and its click and nothing else",
    );
    assert!(
        !initial
            .iter()
            .any(|record| matches!(record, Record::Modifier(node) if *node == action)),
        "a floating action carried a modifier, which would let the Host place it: {initial:?}",
    );
    assert_eq!(
        prop(&initial, action, PropertyKind::Icon),
        Some(Value::Integer(i64::from(IconRole::Compose as u16))),
    );
    assert_eq!(
        prop(&initial, action, PropertyKind::Text),
        Some(Value::Text("New message".into())),
    );
}

/// Its click is an ordinary click, so the action reaches the Host without a new event.
#[test]
fn fr15_2_10_a_floating_actions_click_reaches_the_host_once() {
    ACTIONS.store(0, Ordering::SeqCst);
    let mut host = Host::new(action_app);
    let initial = records(host.rebuild().unwrap());
    let action = created(&initial, WidgetKind::FloatingAction)[0];
    let after = click(&mut host, action, handler(&initial, action));
    assert_eq!(ACTIONS.load(Ordering::SeqCst), 1);
    assert!(created(&after, WidgetKind::FloatingAction).is_empty());
}

/// Both widgets are in the checked-in vector, so the Kotlin decoder is tested against the
/// same two tags and the same properties the Host encodes.
#[test]
fn fr15_2_10_both_widgets_round_trip_through_the_checked_in_vector() {
    let bytes = include_bytes!("../../../dioxus-compose/tests/vectors/mutations.bin");
    let decoded = records(bytes);
    let chips = created(&decoded, WidgetKind::Chip);
    let actions = created(&decoded, WidgetKind::FloatingAction);
    assert_eq!(chips.len(), 1, "the vector has no Chip");
    assert_eq!(actions.len(), 1, "the vector has no FloatingAction");
    assert_eq!(
        prop(&decoded, chips[0], PropertyKind::Checked),
        Some(Value::Bool(true))
    );
    assert_eq!(
        prop(&decoded, chips[0], PropertyKind::Text),
        Some(Value::Text("필터".into())),
    );
    assert_eq!(
        prop(&decoded, actions[0], PropertyKind::Icon),
        Some(Value::Integer(i64::from(IconRole::Add as u16))),
    );
    assert_eq!(
        prop(&decoded, actions[0], PropertyKind::Text),
        Some(Value::Text("New".into())),
    );
    assert!(matches!(
        prop(&decoded, actions[0], PropertyKind::OnClick),
        Some(Value::Integer(_))
    ));
}
