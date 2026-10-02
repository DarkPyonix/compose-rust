//! Pickers: a value, a range and a change event, and nothing that says how to pick.

use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
use dioxus_compose::{EventPayload, Host, PropertyKind, WidgetKind};
use std::cell::RefCell;

fn node_of(batch: &[u8], widget: WidgetKind) -> u32 {
    decode_batch(batch)
        .unwrap()
        .into_iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: kind,
            } if kind == widget => Some(node_id),
            _ => None,
        })
        .unwrap_or_else(|| panic!("no {widget:?} was created"))
}

fn props_of(batch: &[u8], node: u32) -> Vec<(PropertyKind, PropertyValue<'static>)> {
    decode_batch(batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property,
                value,
            } if node_id == node => Some((
                property,
                match value {
                    // The borrowed payloads cannot outlive the batch, and no picker
                    // property carries one, so they compare as absent.
                    PropertyValue::String(_) => PropertyValue::None,
                    PropertyValue::Bytes(_) => PropertyValue::None,
                    PropertyValue::None => PropertyValue::None,
                    PropertyValue::Bool(value) => PropertyValue::Bool(value),
                    PropertyValue::Integer(value) => PropertyValue::Integer(value),
                    PropertyValue::Float(value) => PropertyValue::Float(value),
                },
            )),
            _ => None,
        })
        .collect()
}

fn handler_of(batch: &[u8], node: u32, property: PropertyKind) -> u64 {
    props_of(batch, node)
        .into_iter()
        .find_map(|(kind, value)| match value {
            PropertyValue::Integer(handler) if kind == property => Some(handler as u64),
            _ => None,
        })
        .unwrap_or_else(|| panic!("node {node} has no {property:?}"))
}

/// A widget tag is assigned once and never reused.
#[test]
fn fr15_picker_widgets_keep_their_assigned_tags() {
    assert_eq!(
        [
            WidgetKind::DatePicker as u16,
            WidgetKind::TimePicker as u16,
            WidgetKind::Dropdown as u16,
        ],
        [27, 28, 29],
    );
}

thread_local! {
    static PICKED: RefCell<Vec<i64>> = const { RefCell::new(Vec::new()) };
}

fn date_app() -> Element {
    rsx! {
        DatePicker {
            value: 20_352,
            min: 20_000,
            max: 20_500,
            on_change: move |days: i64| PICKED.with(|picked| picked.borrow_mut().push(days)),
        }
    }
}

/// A date crosses as whole days since 1970-01-01, with the range in the same unit. There
/// is no time zone in the record, because the Renderer is the side that can read the
/// platform's, and no format string, because the moment one crosses, following the
/// platform stops being true.
#[test]
fn fr15_date_picker_sends_a_value_and_a_range_as_epoch_days() {
    let mut host = Host::new(date_app);
    let batch = host.rebuild().unwrap().to_vec();
    let node = node_of(&batch, WidgetKind::DatePicker);
    let props = props_of(&batch, node);
    assert!(props.contains(&(PropertyKind::Value, PropertyValue::Integer(20_352))));
    assert!(props.contains(&(PropertyKind::Min, PropertyValue::Integer(20_000))));
    assert!(props.contains(&(PropertyKind::Max, PropertyValue::Integer(20_500))));
}

/// The picked value comes back as the same integer, so the round trip is lossless and
/// nothing about how the user picked it reaches the Host.
#[test]
fn fr15_date_picker_reports_the_picked_day() {
    PICKED.with(|picked| picked.borrow_mut().clear());
    let mut host = Host::new(date_app);
    let batch = host.rebuild().unwrap().to_vec();
    let node = node_of(&batch, WidgetKind::DatePicker);
    let handler = handler_of(&batch, node, PropertyKind::OnValueChange);
    let mut encoded = Vec::new();
    encode_event(
        &HostEvent {
            node_id: node,
            handler_id: handler,
            payload: EventPayload::ValueChanged(20_400.0),
        },
        &mut encoded,
    )
    .unwrap();
    host.dispatch_event(&encoded).unwrap();
    PICKED.with(|picked| assert_eq!(*picked.borrow(), vec![20_400]));
}

fn time_app() -> Element {
    rsx! {
        TimePicker { value: 13 * 60 + 45 }
    }
}

/// A time crosses as minutes since midnight. Whether it reads as 12 or 24 hour is a
/// platform setting the Renderer owns, so no property here can say.
#[test]
fn fr15_time_picker_sends_minutes_since_midnight() {
    let mut host = Host::new(time_app);
    let batch = host.rebuild().unwrap().to_vec();
    let node = node_of(&batch, WidgetKind::TimePicker);
    let props = props_of(&batch, node);
    assert!(props.contains(&(PropertyKind::Value, PropertyValue::Integer(825))));
    assert!(props.contains(&(PropertyKind::Min, PropertyValue::Integer(0))));
    assert!(props.contains(&(PropertyKind::Max, PropertyValue::Integer(1_439))));
}

fn dropdown_app() -> Element {
    rsx! {
        Dropdown {
            selected_index: 1,
            on_change: move |index: usize| {
                PICKED.with(|picked| picked.borrow_mut().push(index as i64))
            },
            Text { text: "first" }
            Text { text: "second" }
        }
    }
}

/// A Dropdown's children are its options, and the chosen position comes back as the
/// value. The list of options is the tree, not a property, so an option can be any widget.
#[test]
fn fr15_dropdown_carries_its_options_as_children() {
    PICKED.with(|picked| picked.borrow_mut().clear());
    let mut host = Host::new(dropdown_app);
    let batch = host.rebuild().unwrap().to_vec();
    let node = node_of(&batch, WidgetKind::Dropdown);
    let props = props_of(&batch, node);
    assert!(props.contains(&(PropertyKind::SelectedIndex, PropertyValue::Integer(1))));
    let children = decode_batch(&batch)
        .unwrap()
        .into_iter()
        .filter(|mutation| {
            matches!(mutation, Mutation::Insert { parent_id, node_id, .. }
                if *parent_id == node && *node_id != 0)
        })
        .count();
    assert_eq!(children, 2);

    let handler = handler_of(&batch, node, PropertyKind::OnValueChange);
    let mut encoded = Vec::new();
    encode_event(
        &HostEvent {
            node_id: node,
            handler_id: handler,
            payload: EventPayload::ValueChanged(1.0),
        },
        &mut encoded,
    )
    .unwrap();
    host.dispatch_event(&encoded).unwrap();
    PICKED.with(|picked| assert_eq!(*picked.borrow(), vec![1]));
}

/// The three systems do not merely style a picker differently, they operate it
/// differently: a calendar grid and a dial, a wheel, a calendar flyout. That only works if
/// the Host cannot ask for one of them, so the schema has no name for any of them. This is
/// the assertion that fails the day someone adds `mode: "wheel"`.
#[test]
fn fr15_no_property_lets_the_host_choose_how_a_picker_is_operated() {
    let named: Vec<_> = dioxus_compose::schema::PROPERTY_SCHEMA
        .iter()
        .map(|property| property.name.to_ascii_lowercase())
        .filter(|name| {
            [
                "wheel", "dial", "calendar", "grid", "flyout", "mode", "style", "format",
            ]
            .iter()
            .any(|banned| name.contains(banned))
        })
        .collect();
    assert!(
        named.is_empty(),
        "a picker property named how the user picks, or how the value is formatted: {named:?}. \
         The design systems are operated differently, and a Host that can ask for one \
         of them turns the rest into imitations of it.",
    );
}

/// A picker emits its value, its range and its handlers, and no appearance at all.
#[test]
fn fr15_pickers_emit_no_appearance_of_their_own() {
    let mut host = Host::new(date_app);
    let batch = host.rebuild().unwrap().to_vec();
    let node = node_of(&batch, WidgetKind::DatePicker);
    let unexpected: Vec<_> = props_of(&batch, node)
        .into_iter()
        .map(|(property, _)| property)
        .filter(|property| {
            !matches!(
                property,
                PropertyKind::Value
                    | PropertyKind::Min
                    | PropertyKind::Max
                    | PropertyKind::Enabled
                    | PropertyKind::OnValueChange
            )
        })
        .collect();
    assert!(unexpected.is_empty(), "a picker sent {unexpected:?}");
}
