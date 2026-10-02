//! Widget tags 40 and 41: a badge, attached to a child or standing on its own, and a region
//! whose text can be selected and copied. Each is taken through the wire and back.

use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
use dioxus_compose::{EventPayload, Host, PropertyKind, WidgetKind};

/// One decoded record, with borrowed strings turned into owned ones so the batch can be
/// dropped and the record compared.
#[derive(Clone, Debug, PartialEq)]
enum Record {
    Create(u32, WidgetKind),
    Prop(u32, PropertyKind, Value),
    Insert(u32, u32, u32),
    Other,
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
        .map(|mutation| match mutation {
            Mutation::Create { node_id, widget } => Record::Create(node_id, widget),
            Mutation::SetProp {
                node_id,
                property,
                value,
            } => Record::Prop(
                node_id,
                property,
                match value {
                    PropertyValue::None => Value::None,
                    PropertyValue::String(text) => Value::Text(text.to_string()),
                    PropertyValue::Bool(value) => Value::Bool(value),
                    PropertyValue::Integer(value) => Value::Integer(value),
                    PropertyValue::Float(value) => Value::Float(value),
                    PropertyValue::Bytes(_) => Value::Bytes,
                },
            ),
            Mutation::Insert {
                parent_id,
                node_id,
                index,
            } => Record::Insert(parent_id, node_id, index),
            _ => Record::Other,
        })
        .collect()
}

fn nodes_of(records: &[Record], widget: WidgetKind) -> Vec<u32> {
    records
        .iter()
        .filter_map(|record| match record {
            Record::Create(node_id, kind) if *kind == widget => Some(*node_id),
            _ => None,
        })
        .collect()
}

fn node_of(records: &[Record], widget: WidgetKind) -> u32 {
    *nodes_of(records, widget)
        .first()
        .unwrap_or_else(|| panic!("no {widget:?} was created"))
}

/// The value a node was last given for a property, or None where it never was given one
/// or was given an empty one.
fn prop_of(records: &[Record], node: u32, property: PropertyKind) -> Option<Value> {
    records
        .iter()
        .rev()
        .find_map(|record| match record {
            Record::Prop(node_id, kind, value) if *node_id == node && *kind == property => {
                Some(value.clone())
            }
            _ => None,
        })
        .filter(|value| *value != Value::None)
}

/// Children in the order the parent draws them, replayed the way the Renderer applies a
/// batch. Node id 0 is the placeholder for a dynamic slot that produced nothing.
fn children_of(records: &[Record], parent: u32) -> Vec<u32> {
    let mut children: Vec<u32> = Vec::new();
    for record in records {
        let Record::Insert(parent_id, node_id, index) = record else {
            continue;
        };
        if *parent_id != parent || *node_id == 0 {
            continue;
        }
        let at = (*index as usize).min(children.len());
        children.insert(at, *node_id);
    }
    children
}

fn handler_of(records: &[Record], node: u32, property: PropertyKind) -> u64 {
    match prop_of(records, node, property) {
        Some(Value::Integer(handler)) => handler as u64,
        other => panic!("node {node} has no {property:?} handler, found {other:?}"),
    }
}

fn click(host: &mut Host, node: u32, handler: u64) -> Vec<Record> {
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: node,
            handler_id: handler,
            payload: EventPayload::Clicked,
        },
        &mut wire,
    )
    .unwrap();
    let (batch, _) = host.dispatch_event(&wire).unwrap();
    records(batch)
}

/// A widget tag is assigned once and never reused, and the badge's one new property opens
/// a block of its own rather than squeezing into a block another group of widgets owns.
#[test]
fn fr15_2_11_badge_and_selection_container_keep_their_assigned_tags() {
    assert_eq!(WidgetKind::Badge as u16, 40);
    assert_eq!(WidgetKind::SelectionContainer as u16, 41);
    assert_eq!(PropertyKind::Count as u16, 80);
}

fn badges_app() -> Element {
    rsx! {
        Column {
            // Attached to a child, with a count.
            Badge { value: 3,
                Icon { asset_id: 1 }
            }
            // Standing on its own at the end of a row, with a count large enough that a
            // design system may choose to shorten it.
            Badge { value: 120 }
            // A short word.
            Badge { text: "new", color: ColorRole::Primary }
            // Neither: a dot.
            Badge {}
        }
    }
}

/// A count travels as a count and a word as a word. Nothing about where the mark sits or
/// how a large count is written crosses: those are the design system's.
#[test]
fn fr15_2_11_a_badge_sends_its_count_or_its_word_and_nothing_about_placement() {
    let mut host = Host::new(badges_app);
    let records = records(host.rebuild().unwrap());
    let badges = nodes_of(&records, WidgetKind::Badge);
    assert_eq!(badges.len(), 4, "four badges were declared");

    let counted: Vec<_> = badges
        .iter()
        .map(|badge| prop_of(&records, *badge, PropertyKind::Count))
        .collect();
    assert!(counted.contains(&Some(Value::Integer(3))));
    assert!(
        counted.contains(&Some(Value::Integer(120))),
        "120 is sent as it is; shortening it is the Renderer's decision"
    );

    let worded: Vec<_> = badges
        .iter()
        .filter_map(|badge| prop_of(&records, *badge, PropertyKind::Text))
        .collect();
    assert_eq!(worded, vec![Value::Text("new".to_string())]);

    // The only properties a badge carries are its count, its word and its colour role.
    let allowed = [PropertyKind::Count, PropertyKind::Text, PropertyKind::Color];
    for record in &records {
        let Record::Prop(node, property, value) = record else {
            continue;
        };
        if badges.contains(node) && *value != Value::None {
            assert!(
                allowed.contains(property),
                "a badge sent {property:?}, which says something the design system decides"
            );
        }
    }
}

/// A badge with neither a count nor a word is a dot. Sending a count of zero for it would
/// say "none" where the screen meant "something new".
#[test]
fn fr15_2_11_a_dot_carries_neither_a_count_nor_a_word() {
    let mut host = Host::new(badges_app);
    let records = records(host.rebuild().unwrap());
    let dots: Vec<_> = nodes_of(&records, WidgetKind::Badge)
        .into_iter()
        .filter(|badge| {
            prop_of(&records, *badge, PropertyKind::Count).is_none()
                && prop_of(&records, *badge, PropertyKind::Text).is_none()
        })
        .collect();
    assert_eq!(dots.len(), 1, "exactly one badge was declared as a dot");
}

/// Attached and standing alone are the same widget. The difference is whether it has a
/// child, so the attached one has exactly the child it was given.
#[test]
fn fr15_2_11_an_attached_badge_has_its_child_and_a_standalone_one_has_none() {
    let mut host = Host::new(badges_app);
    let records = records(host.rebuild().unwrap());
    let icon = node_of(&records, WidgetKind::Icon);
    let mut with_children = 0;
    for badge in nodes_of(&records, WidgetKind::Badge) {
        let children = children_of(&records, badge);
        if children.is_empty() {
            continue;
        }
        with_children += 1;
        assert_eq!(children, vec![icon]);
    }
    assert_eq!(with_children, 1);
}

/// The colour is a role, never a literal. The role reaches the wire as the same paint word
/// every other colour slot uses, so the Renderer resolves it in the running system.
#[test]
fn fr15_2_11_a_badge_colour_is_a_role() {
    let mut host = Host::new(badges_app);
    let records = records(host.rebuild().unwrap());
    let worded = nodes_of(&records, WidgetKind::Badge)
        .into_iter()
        .find(|badge| prop_of(&records, *badge, PropertyKind::Text).is_some())
        .unwrap();
    assert_eq!(
        prop_of(&records, worded, PropertyKind::Color),
        Some(Value::Integer(
            Paint::Role(ColorRole::Primary).to_bits() as i64
        )),
    );
    // A badge that names no role sends none, and the Renderer fills it with the error
    // role. Sending the default would cost every badge a record for saying nothing.
    let counted = nodes_of(&records, WidgetKind::Badge)
        .into_iter()
        .find(|badge| prop_of(&records, *badge, PropertyKind::Count) == Some(Value::Integer(3)))
        .unwrap();
    assert_eq!(prop_of(&records, counted, PropertyKind::Color), None);
}

fn counting_app() -> Element {
    let mut unread = use_signal(|| 3_u32);
    rsx! {
        Column {
            Button { text: "more", on_click: move |()| *unread.write() += 1 }
            Badge { value: unread(),
                Text { text: "Inbox" }
            }
        }
    }
}

/// A new count is one property on one node. The child it is attached to is not touched,
/// so it is not drawn again.
#[test]
fn fr15_2_11_a_new_count_changes_one_property_and_leaves_the_child_alone() {
    let mut host = Host::new(counting_app);
    let first = records(host.rebuild().unwrap());
    let button = node_of(&first, WidgetKind::Button);
    let badge = node_of(&first, WidgetKind::Badge);
    let handler = handler_of(&first, button, PropertyKind::OnClick);

    let changed: Vec<_> = click(&mut host, button, handler)
        .into_iter()
        .filter(|record| *record != Record::Other)
        .collect();
    assert_eq!(
        changed,
        vec![Record::Prop(badge, PropertyKind::Count, Value::Integer(4))],
        "a new count must be exactly one record, on the badge"
    );
}

fn selection_app() -> Element {
    rsx! {
        Column {
            Text { text: "outside" }
            SelectionContainer {
                Text { text: "A paragraph." }
                Text { text: "- a list item" }
                LazyRow {
                    item_count: 1,
                    item: move |_: usize| rsx! { Text { text: "let code = 1;" } },
                }
                Button { text: "Copy link" }
            }
        }
    }
}

/// The region carries nothing of its own: no property, and no handler, because selecting
/// and copying are the Renderer's and there is nothing for the Host to hear.
#[test]
fn fr33_a_selection_container_carries_no_properties_and_no_handlers() {
    let mut host = Host::new(selection_app);
    let records = records(host.rebuild().unwrap());
    let region = node_of(&records, WidgetKind::SelectionContainer);
    let sent: Vec<_> = records
        .iter()
        .filter(|record| {
            matches!(record, Record::Prop(node, _, value) if *node == region && *value != Value::None)
        })
        .collect();
    assert!(sent.is_empty(), "a selection region sent {sent:?}");
}

/// Everything inside is its children, as declared: the text blocks, the horizontally
/// scrolled code and the button, in order. Only the text outside is not one of them.
#[test]
fn fr33_the_selectable_text_is_the_text_inside_the_region() {
    let mut host = Host::new(selection_app);
    let records = records(host.rebuild().unwrap());
    let region = node_of(&records, WidgetKind::SelectionContainer);
    let children = children_of(&records, region);
    assert_eq!(children.len(), 4);
    let kinds: Vec<_> = children
        .iter()
        .map(|child| {
            records
                .iter()
                .find_map(|record| match record {
                    Record::Create(node, kind) if node == child => Some(*kind),
                    _ => None,
                })
                .unwrap()
        })
        .collect();
    assert_eq!(
        kinds,
        vec![
            WidgetKind::Text,
            WidgetKind::Text,
            WidgetKind::LazyRow,
            WidgetKind::Button
        ]
    );
    let column = node_of(&records, WidgetKind::Column);
    let outside = children_of(&records, column)[0];
    assert!(!children.contains(&outside));
}

/// No event the Host can receive is about a selection. Copying writes the platform
/// clipboard from the Renderer, so the copied text never crosses either.
#[test]
fn fr33_no_event_tag_exists_for_selecting_or_copying() {
    for event in dioxus_compose::schema::EVENT_SCHEMA {
        let name = event.name.to_ascii_lowercase();
        assert!(
            !name.contains("select") && !name.contains("copy") && !name.contains("clipboard"),
            "{} would carry a selection across the boundary",
            event.name
        );
    }
}
