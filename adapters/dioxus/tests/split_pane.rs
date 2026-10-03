//! Widget tag 42: a side pane and a body with a divider between them. What the Host puts on
//! the wire, what comes back, and what does not cross at all.

use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{
    BatchEncoder, HostEvent, Mutation, PropertyValue, decode_batch, encode_event,
};
use dioxus_compose_adapter::{EventPayload, Host, PropertyKind, WidgetKind};
use std::cell::RefCell;

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

fn node_of(records: &[Record], widget: WidgetKind) -> u32 {
    records
        .iter()
        .find_map(|record| match record {
            Record::Create(node_id, kind) if *kind == widget => Some(*node_id),
            _ => None,
        })
        .unwrap_or_else(|| panic!("no {widget:?} was created"))
}

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

fn props_on(records: &[Record], node: u32) -> Vec<PropertyKind> {
    records
        .iter()
        .filter_map(|record| match record {
            Record::Prop(node_id, kind, _) if *node_id == node => Some(*kind),
            _ => None,
        })
        .collect()
}

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

fn send(host: &mut Host, node: u32, handler: u64, payload: EventPayload<'static>) -> Vec<Record> {
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: node,
            handler_id: handler,
            payload,
        },
        &mut wire,
    )
    .unwrap();
    let (batch, _) = host.dispatch_event(&wire).unwrap();
    records(batch)
}

fn resize(host: &mut Host, width_dp: f32) -> Vec<Record> {
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: EventPayload::WindowSizeChanged {
                width_dp,
                height_dp: 800.0,
                class: WindowSizeClass::from_width_dp(width_dp),
                height_class: WindowHeightClass::from_height_dp(800.0),
            },
        })
        .unwrap();
    records(batch)
}

/// The split pane keeps the tag the vocabulary gave it, and its one new property opens
/// the block of ten after the badge's.
#[test]
fn fr15_2_12_split_pane_keeps_its_assigned_tags() {
    assert_eq!(WidgetKind::SplitPane as u16, 42);
    assert_eq!(PropertyKind::Collapsible as u16, 90);
    // Everything else it says reuses a tag that already means the same thing.
    assert_eq!(PropertyKind::Value as u16, 51);
    assert_eq!(PropertyKind::Min as u16, 52);
    assert_eq!(PropertyKind::Max as u16, 53);
    assert_eq!(PropertyKind::OnValueChange as u16, 6);
    assert_eq!(PropertyKind::SelectedIndex as u16, 42);
    assert_eq!(PropertyKind::OnDismiss as u16, 41);
    assert_eq!(PropertyKind::Text as u16, 1);
}

thread_local! {
    static REPORTED: RefCell<Vec<f32>> = const { RefCell::new(Vec::new()) };
    static DISMISSED: RefCell<u32> = const { RefCell::new(0) };
}

fn sessions_app() -> Element {
    let mut width = use_signal(|| 320.0_f32);
    let mut selected = use_signal(|| 0_usize);
    rsx! {
        SplitPane {
            fill_max_width: true,
            value: width(),
            min: 200.0,
            max: 400.0,
            collapsible: true,
            selected_index: selected(),
            label: "Sessions",
            on_change: move |value: f32| {
                REPORTED.with(|reported| reported.borrow_mut().push(value));
                width.set(value);
            },
            on_dismiss: move |_| {
                DISMISSED.with(|dismissed| *dismissed.borrow_mut() += 1);
                selected.set(0);
            },
            Column {
                Text { text: "First session" }
                Button { text: "Open", on_click: move |_| selected.set(1) }
            }
            Column {
                Text { text: "The conversation" }
            }
        }
    }
}

/// What a split pane puts on the wire: its width, its range, that it folds, which pane
/// shows when one fits, the divider's name, the two handlers, and two children in order.
#[test]
fn fr15_2_12_a_split_pane_sends_its_width_range_and_two_children() {
    dioxus_compose_adapter::window::reset_window_size();
    let mut host = Host::new(sessions_app);
    let first = records(host.rebuild().unwrap());
    let pane = node_of(&first, WidgetKind::SplitPane);
    assert_eq!(
        prop_of(&first, pane, PropertyKind::Value),
        Some(Value::Float(320.0))
    );
    assert_eq!(
        prop_of(&first, pane, PropertyKind::Min),
        Some(Value::Float(200.0))
    );
    assert_eq!(
        prop_of(&first, pane, PropertyKind::Max),
        Some(Value::Float(400.0))
    );
    assert_eq!(
        prop_of(&first, pane, PropertyKind::Collapsible),
        Some(Value::Bool(true))
    );
    assert_eq!(
        prop_of(&first, pane, PropertyKind::SelectedIndex),
        Some(Value::Integer(0))
    );
    assert_eq!(
        prop_of(&first, pane, PropertyKind::Text),
        Some(Value::Text("Sessions".to_string()))
    );
    handler_of(&first, pane, PropertyKind::OnValueChange);
    handler_of(&first, pane, PropertyKind::OnDismiss);
    assert_eq!(
        children_of(&first, pane).len(),
        2,
        "a split pane has two panes"
    );
}

/// A split pane that says nothing about its width sends no width, so the Renderer opens it
/// at the design system's own sidebar width rather than at zero.
#[test]
fn fr15_2_12_no_width_means_the_design_systems_width() {
    fn plain() -> Element {
        rsx! {
            SplitPane {
                Text { text: "side" }
                Text { text: "body" }
            }
        }
    }
    dioxus_compose_adapter::window::reset_window_size();
    let mut host = Host::new(plain);
    let first = records(host.rebuild().unwrap());
    let pane = node_of(&first, WidgetKind::SplitPane);
    let sent = props_on(&first, pane);
    for absent in [PropertyKind::Value, PropertyKind::Min, PropertyKind::Max] {
        assert!(
            prop_of(&first, pane, absent).is_none(),
            "{absent:?} was sent: {sent:?}"
        );
    }
}

/// The width a drag ends on comes back once through the value change event, as dp. Folding
/// is the same event carrying zero. Written back into the signal, a new width is one
/// SetProp on the split pane and nothing else.
#[test]
fn fr15_2_12_a_finished_drag_and_a_fold_each_report_once() {
    REPORTED.with(|reported| reported.borrow_mut().clear());
    dioxus_compose_adapter::window::reset_window_size();
    let mut host = Host::new(sessions_app);
    let first = records(host.rebuild().unwrap());
    let pane = node_of(&first, WidgetKind::SplitPane);
    let handler = handler_of(&first, pane, PropertyKind::OnValueChange);

    let after = send(&mut host, pane, handler, EventPayload::ValueChanged(288.0));
    REPORTED.with(|reported| assert_eq!(*reported.borrow(), [288.0]));
    let mut frame = after;
    frame.extend(records(host.render_frame(0).unwrap()));
    let changed: Vec<_> = frame
        .iter()
        .filter(|record| matches!(record, Record::Prop(..)))
        .collect();
    assert_eq!(
        changed,
        [&Record::Prop(
            pane,
            PropertyKind::Value,
            Value::Float(288.0)
        )],
        "a new width is exactly one SetProp on the split pane"
    );
    assert!(
        !frame
            .iter()
            .any(|record| matches!(record, Record::Create(..))),
        "a new width rebuilt part of the tree"
    );

    send(&mut host, pane, handler, EventPayload::ValueChanged(0.0));
    REPORTED.with(|reported| assert_eq!(*reported.borrow(), [288.0, 0.0]));
}

/// Going back from the body is a press on the dismiss handler, once, and the application
/// answers it by showing the side pane again.
#[test]
fn fr15_2_12_going_back_dismisses_once_and_the_side_pane_is_selected() {
    DISMISSED.with(|dismissed| *dismissed.borrow_mut() = 0);
    dioxus_compose_adapter::window::reset_window_size();
    let mut host = Host::new(sessions_app);
    let first = records(host.rebuild().unwrap());
    let pane = node_of(&first, WidgetKind::SplitPane);
    let open = first
        .iter()
        .find_map(|record| match record {
            Record::Create(node_id, WidgetKind::Button) => Some(*node_id),
            _ => None,
        })
        .unwrap();
    let open_handler = handler_of(&first, open, PropertyKind::OnClick);
    let mut opened = send(&mut host, open, open_handler, EventPayload::Clicked);
    opened.extend(records(host.render_frame(0).unwrap()));
    assert_eq!(
        prop_of(&opened, pane, PropertyKind::SelectedIndex),
        Some(Value::Integer(1))
    );

    let dismiss = handler_of(&first, pane, PropertyKind::OnDismiss);
    let mut back = send(&mut host, pane, dismiss, EventPayload::Clicked);
    back.extend(records(host.render_frame(0).unwrap()));
    DISMISSED.with(|dismissed| assert_eq!(*dismissed.borrow(), 1));
    assert_eq!(
        prop_of(&back, pane, PropertyKind::SelectedIndex),
        Some(Value::Integer(0))
    );
}

/// The Host does not branch on the width: narrowing and widening the window across every
/// size class creates no node and sends nothing about the split pane.
#[test]
fn fr15_2_12_resizing_the_window_creates_nothing_and_reports_nothing() {
    REPORTED.with(|reported| reported.borrow_mut().clear());
    dioxus_compose_adapter::window::reset_window_size();
    let mut host = Host::new(sessions_app);
    let first = records(host.rebuild().unwrap());
    let pane = node_of(&first, WidgetKind::SplitPane);
    for width in [1100.0, 500.0, 700.0, 1100.0, 420.0] {
        let mut after = resize(&mut host, width);
        after.extend(records(host.render_frame(0).unwrap()));
        assert!(
            !after
                .iter()
                .any(|record| matches!(record, Record::Create(..))),
            "the window at {width}dp created a node: {after:?}"
        );
        assert!(
            props_on(&after, pane).is_empty(),
            "the window at {width}dp changed the split pane"
        );
    }
    REPORTED.with(|reported| assert!(reported.borrow().is_empty()));
}

/// The new property travels as a boolean in the one fixed record every property uses.
#[test]
fn fr15_2_12_collapsible_round_trips_as_a_bool() {
    let records = [Mutation::SetProp {
        node_id: 9,
        property: PropertyKind::Collapsible,
        value: PropertyValue::Bool(true),
    }];
    let mut encoder = BatchEncoder::default();
    encoder.encode(&records[0]).unwrap();
    let bytes = encoder.finish().unwrap();
    assert_eq!(bytes.len(), 12 + 24);
    assert_eq!(u16::from_le_bytes([bytes[20], bytes[21]]), 90);
    assert_eq!(decode_batch(bytes).unwrap(), records);

    let vector = dioxus_compose_adapter::codegen::generate_mutation_vector().unwrap();
    assert!(
        decode_batch(&vector)
            .unwrap()
            .iter()
            .any(|mutation| *mutation
                == Mutation::SetProp {
                    node_id: 2,
                    property: PropertyKind::Collapsible,
                    value: PropertyValue::Bool(true),
                }),
        "the protocol vector does not carry Collapsible"
    );
}
