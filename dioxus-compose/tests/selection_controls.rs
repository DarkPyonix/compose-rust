//! The selection controls and the indicators: what they put on the wire, and what comes
//! back when the user moves one.

use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{
    HostEvent, Mutation, PropertyValue, decode_batch, decode_event, encode_event,
};
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
                    // The borrowed payloads cannot outlive the batch, and none of these
                    // controls carries one, so they compare as absent.
                    PropertyValue::String(_) | PropertyValue::Bytes(_) | PropertyValue::None => {
                        PropertyValue::None
                    }
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

fn deliver(host: &mut Host, node: u32, handler: u64, value: f64) {
    let mut encoded = Vec::new();
    encode_event(
        &HostEvent {
            node_id: node,
            handler_id: handler,
            payload: EventPayload::ValueChanged(value),
        },
        &mut encoded,
    )
    .unwrap();
    host.dispatch_event(&encoded).unwrap();
}

/// A widget tag is assigned once and never reused.
#[test]
fn fr15_selection_control_widgets_keep_their_assigned_tags() {
    assert_eq!(
        [
            WidgetKind::Checkbox as u16,
            WidgetKind::RadioButton as u16,
            WidgetKind::Switch as u16,
            WidgetKind::Slider as u16,
            WidgetKind::ProgressIndicator as u16,
            WidgetKind::Divider as u16,
        ],
        [12, 13, 14, 15, 16, 17],
    );
}

/// The five properties these widgets add, in the block that follows Image and Icon. The
/// slider's value and range reuse the tags the pickers already have, because "this
/// control's value" is one concept and the schema names a concept once.
#[test]
fn fr15_selection_control_properties_keep_their_assigned_tags() {
    assert_eq!(
        [
            PropertyKind::Checked as u16,
            PropertyKind::Steps as u16,
            PropertyKind::Determinate as u16,
            PropertyKind::Circular as u16,
            PropertyKind::Vertical as u16,
        ],
        [32, 33, 34, 35, 36],
    );
    assert_eq!(
        [
            PropertyKind::Value as u16,
            PropertyKind::Min as u16,
            PropertyKind::Max as u16,
        ],
        [51, 52, 53],
    );
}

/// The single value-change event. Tag 16 because the pointer gestures hold 8 to 15, and
/// one f64 because an epoch count, a slider position and a toggle all fit in it exactly.
#[test]
fn fr15_value_changed_is_tag_16_carrying_one_f64() {
    let event = HostEvent {
        node_id: 4,
        handler_id: 9,
        payload: EventPayload::ValueChanged(0.25),
    };
    let mut encoded = Vec::new();
    encode_event(&event, &mut encoded).unwrap();

    assert_eq!(u16::from_le_bytes([encoded[0], encoded[1]]), 16);
    assert_eq!(u16::from_le_bytes([encoded[2], encoded[3]]), 24);
    assert_eq!(encoded.len(), 24);
    assert_eq!(
        f64::from_le_bytes(encoded[16..24].try_into().unwrap()),
        0.25
    );
    assert_eq!(decode_event(&encoded).unwrap(), event);
}

/// A fractional position survives the round trip. An integer payload would have rounded
/// it away, and a slider is the reason the event carries a float at all.
#[test]
fn fr15_a_fractional_value_survives_the_round_trip() {
    let event = HostEvent {
        node_id: 1,
        handler_id: 2,
        payload: EventPayload::ValueChanged(-19_723.5),
    };
    let mut encoded = Vec::new();
    encode_event(&event, &mut encoded).unwrap();
    assert_eq!(decode_event(&encoded).unwrap(), event);
}

/// The gesture tags are reserved, not implemented. A record claiming one of them is a
/// reported protocol error rather than a value change decoded off the wrong offset.
#[test]
fn fr15_the_reserved_gesture_tags_are_not_decoded_as_a_value_change() {
    let mut encoded = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 1,
            handler_id: 2,
            payload: EventPayload::ValueChanged(1.0),
        },
        &mut encoded,
    )
    .unwrap();
    for tag in 8_u16..=15 {
        encoded[0..2].copy_from_slice(&tag.to_le_bytes());
        assert!(
            decode_event(&encoded).is_err(),
            "tag {tag} is reserved and must not decode"
        );
    }
}

thread_local! {
    static TOGGLED: RefCell<Vec<bool>> = const { RefCell::new(Vec::new()) };
    static SLID: RefCell<Vec<f32>> = const { RefCell::new(Vec::new()) };
}

fn toggles_app() -> Element {
    rsx! {
        Checkbox {
            checked: true,
            on_change: move |on: bool| TOGGLED.with(|seen| seen.borrow_mut().push(on)),
        }
        RadioButton { selected: true }
        Switch { checked: false }
    }
}

/// All three toggles say the same thing on the wire: one boolean that is the whole of
/// what they draw. A selected radio button is not a second concept.
#[test]
fn fr15_the_three_toggles_send_one_checked_boolean() {
    let mut host = Host::new(toggles_app);
    let batch = host.rebuild().unwrap().to_vec();
    for (widget, expected) in [
        (WidgetKind::Checkbox, true),
        (WidgetKind::RadioButton, true),
        (WidgetKind::Switch, false),
    ] {
        let node = node_of(&batch, widget);
        assert!(
            props_of(&batch, node)
                .contains(&(PropertyKind::Checked, PropertyValue::Bool(expected))),
            "{widget:?} did not send checked={expected}"
        );
    }
}

/// A toggle is controlled: the Renderer reports the state the user asked for and the Host
/// is the one that flips the value, so the two cannot drift apart.
#[test]
fn fr15_a_toggle_reports_its_new_state_as_zero_or_one() {
    TOGGLED.with(|seen| seen.borrow_mut().clear());
    let mut host = Host::new(toggles_app);
    let batch = host.rebuild().unwrap().to_vec();
    let node = node_of(&batch, WidgetKind::Checkbox);
    let handler = handler_of(&batch, node, PropertyKind::OnValueChange);
    deliver(&mut host, node, handler, 0.0);
    deliver(&mut host, node, handler, 1.0);
    TOGGLED.with(|seen| assert_eq!(*seen.borrow(), vec![false, true]));
}

fn slider_app() -> Element {
    rsx! {
        Slider {
            value: 0.25,
            min: 0.0,
            max: 4.0,
            steps: 3,
            on_change: move |value: f32| SLID.with(|seen| seen.borrow_mut().push(value)),
        }
    }
}

/// A slider carries a position, the two ends of its range and the number of stops between
/// them. It carries no track height, no thumb size and no colour: those are drawn by the
/// design system, which is why the same declaration looks different in each one.
#[test]
fn fr15_slider_sends_a_value_a_range_and_its_steps() {
    let mut host = Host::new(slider_app);
    let batch = host.rebuild().unwrap().to_vec();
    let node = node_of(&batch, WidgetKind::Slider);
    let props = props_of(&batch, node);
    assert!(props.contains(&(PropertyKind::Value, PropertyValue::Float(0.25))));
    assert!(props.contains(&(PropertyKind::Max, PropertyValue::Float(4.0))));
    assert!(props.contains(&(PropertyKind::Steps, PropertyValue::Integer(3))));
}

/// The dragged position comes back as itself. User code sees an f32 and never the f64 the
/// wire uses.
#[test]
fn fr15_slider_reports_the_position_it_was_dragged_to() {
    SLID.with(|seen| seen.borrow_mut().clear());
    let mut host = Host::new(slider_app);
    let batch = host.rebuild().unwrap().to_vec();
    let node = node_of(&batch, WidgetKind::Slider);
    let handler = handler_of(&batch, node, PropertyKind::OnValueChange);
    deliver(&mut host, node, handler, 2.5);
    SLID.with(|seen| assert_eq!(*seen.borrow(), vec![2.5_f32]));
}

fn indicators_app() -> Element {
    rsx! {
        ProgressIndicator { value: 0.4, determinate: true, circular: true }
        Divider { vertical: true }
    }
}

/// An indicator says how far along it is and which of the two forms it takes, and a
/// divider says which way it runs. Neither carries a thickness or a colour, and neither
/// says how fast an indeterminate sweep travels: that is motion, which the design system
/// owns.
#[test]
fn fr15_the_indicators_carry_only_their_state_and_their_axis() {
    let mut host = Host::new(indicators_app);
    let batch = host.rebuild().unwrap().to_vec();

    let indicator = node_of(&batch, WidgetKind::ProgressIndicator);
    let props = props_of(&batch, indicator);
    assert!(props.contains(&(PropertyKind::Value, PropertyValue::Float(0.4))));
    assert!(props.contains(&(PropertyKind::Determinate, PropertyValue::Bool(true))));
    assert!(props.contains(&(PropertyKind::Circular, PropertyValue::Bool(true))));

    let divider = node_of(&batch, WidgetKind::Divider);
    assert!(
        props_of(&batch, divider).contains(&(PropertyKind::Vertical, PropertyValue::Bool(true)))
    );
}

fn modified_app() -> Element {
    rsx! {
        Switch { checked: true, padding: 8.0, width: 48.0 }
    }
}

/// Every widget takes the Modifier attributes, and these six are no exception: a control
/// that could not be given padding or a width would have to be wrapped in a Box to sit
/// anywhere.
#[test]
fn fr15_a_selection_control_takes_the_modifier_attributes() {
    let mut host = Host::new(modified_app);
    let batch = host.rebuild().unwrap().to_vec();
    let node = node_of(&batch, WidgetKind::Switch);
    let modifiers: Vec<_> = decode_batch(&batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                node_id, modifier, ..
            } if node_id == node => Some(modifier),
            _ => None,
        })
        .collect();
    assert!(modifiers.contains(&Modifier::Padding(8.0)));
    assert!(modifiers.contains(&Modifier::Width(48.0)));
}
