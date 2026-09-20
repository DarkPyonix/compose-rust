//! Widget tags 12 to 17: the selection controls, the progress indicator and the divider,
//! each taken through the wire and back.

use dioxus_compose::codegen::generate_mutation_vector;
use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{
    HostEvent, Mutation, PropertyValue, decode_batch, decode_event, encode_event,
};
use dioxus_compose::{EventPayload, Host, PropertyKind, WidgetKind};
use std::cell::RefCell;

/// The decoded records of the first frame, with borrowed strings turned into owned ones so
/// the batch can be dropped.
#[derive(Clone, Debug, PartialEq)]
enum Record {
    Create(u32, WidgetKind),
    Prop(u32, PropertyKind, PropertyValue<'static>),
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
                    PropertyValue::String(_) | PropertyValue::None => PropertyValue::None,
                    PropertyValue::Bool(value) => PropertyValue::Bool(value),
                    PropertyValue::Integer(value) => PropertyValue::Integer(value),
                    PropertyValue::Float(value) => PropertyValue::Float(value),
                },
            )),
            _ => None,
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

fn handler_of(records: &[Record], node: u32, property: PropertyKind) -> u64 {
    records
        .iter()
        .find_map(|record| match record {
            Record::Prop(node_id, kind, PropertyValue::Integer(value))
                if *node_id == node && *kind == property =>
            {
                Some(*value as u64)
            }
            _ => None,
        })
        .unwrap_or_else(|| panic!("node {node} has no {property:?}"))
}

/// Properties a node sends that are not handler ids. A widget that emits roles alone should
/// send only what its value model needs, so this is what the appearance assertions look at.
fn value_props(records: &[Record], node: u32) -> Vec<(PropertyKind, PropertyValue<'static>)> {
    records
        .iter()
        .filter_map(|record| match record {
            Record::Prop(node_id, kind, value)
                if *node_id == node
                    && !matches!(
                        kind,
                        PropertyKind::OnClick
                            | PropertyKind::OnValueChange
                            | PropertyKind::OnSubmit
                            | PropertyKind::OnFocusLost
                            | PropertyKind::OnKeyDown
                            | PropertyKind::OnRangeRequested
                            | PropertyKind::OnDismiss
                    ) =>
            {
                Some((*kind, value.clone()))
            }
            _ => None,
        })
        .collect()
}

/// A widget tag is assigned once and never reused, so a later edit that renumbers one of
/// these silently breaks every Renderer already built against it. Pinning the numbers here
/// is what turns that into a failing test instead.
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

/// The property tags are append-only for the same reason the widget tags are, and these
/// eight sit in the block reserved for widget tags 12 to 17. `Image` and `Icon` take 28 to
/// 31, so a change that shifts these down would collide with them.
#[test]
fn fr15_selection_control_properties_keep_their_assigned_tags() {
    assert_eq!(
        [
            PropertyKind::Checked as u16,
            PropertyKind::Value as u16,
            PropertyKind::MinValue as u16,
            PropertyKind::MaxValue as u16,
            PropertyKind::Steps as u16,
            PropertyKind::Determinate as u16,
            PropertyKind::Circular as u16,
            PropertyKind::Vertical as u16,
        ],
        [32, 33, 34, 35, 36, 37, 38, 39],
    );
}

thread_local! {
    static CLICKS: RefCell<u32> = const { RefCell::new(0) };
    static VALUES: RefCell<Vec<f32>> = const { RefCell::new(Vec::new()) };
}

fn checkbox_app() -> Element {
    rsx! {
        Checkbox {
            checked: true,
            on_click: move |()| CLICKS.with(|count| *count.borrow_mut() += 1),
        }
    }
}

/// The box is controlled: it sends the state it should be drawn in, and a press comes back
/// as a bare click because flipping a boolean the Host already holds needs no payload. The
/// Host is what changes the value, so the screen can never disagree with it.
#[test]
fn fr15_checkbox_sends_its_state_and_reports_a_press_as_a_click() {
    CLICKS.with(|count| *count.borrow_mut() = 0);
    let mut host = Host::new(checkbox_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let checkbox = node_of(&records, WidgetKind::Checkbox);
    assert!(records.contains(&Record::Prop(
        checkbox,
        PropertyKind::Checked,
        PropertyValue::Bool(true)
    )));

    let handler_id = handler_of(&records, checkbox, PropertyKind::OnClick);
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: checkbox,
            handler_id,
            payload: EventPayload::Clicked,
        },
        &mut wire,
    )
    .unwrap();
    host.dispatch_event(&wire).unwrap();
    assert_eq!(CLICKS.with(|count| *count.borrow()), 1);
}

fn radio_button_app() -> Element {
    rsx! {
        RadioButton {
            selected: true,
            on_click: move |()| CLICKS.with(|count| *count.borrow_mut() += 1),
        }
    }
}

/// `selected` is the Compose name and stays the name of the rsx attribute, but on the wire
/// a selected radio button is the same "is this on" boolean a checked box sends, so the
/// schema carries that concept once.
#[test]
fn fr15_radio_button_selection_travels_as_the_shared_checked_boolean() {
    CLICKS.with(|count| *count.borrow_mut() = 0);
    let mut host = Host::new(radio_button_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let radio = node_of(&records, WidgetKind::RadioButton);
    assert!(records.contains(&Record::Prop(
        radio,
        PropertyKind::Checked,
        PropertyValue::Bool(true)
    )));

    let handler_id = handler_of(&records, radio, PropertyKind::OnClick);
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: radio,
            handler_id,
            payload: EventPayload::Clicked,
        },
        &mut wire,
    )
    .unwrap();
    host.dispatch_event(&wire).unwrap();
    assert_eq!(CLICKS.with(|count| *count.borrow()), 1);
}

fn switch_app() -> Element {
    rsx! {
        Switch {
            checked: false,
            enabled: false,
            on_click: move |()| CLICKS.with(|count| *count.borrow_mut() += 1),
        }
    }
}

/// Whether a switch ripples, dims or slides is the design system's decision, so the widget
/// sends the state and whether it can be used, and nothing about how it looks.
#[test]
fn fr15_switch_sends_no_appearance_of_its_own() {
    let mut host = Host::new(switch_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let switch = node_of(&records, WidgetKind::Switch);
    let mut props = value_props(&records, switch);
    props.sort_by_key(|(kind, _)| *kind as u16);
    assert_eq!(
        props,
        vec![
            (PropertyKind::Enabled, PropertyValue::Bool(false)),
            (PropertyKind::Checked, PropertyValue::Bool(false)),
        ],
    );
}

fn slider_app() -> Element {
    rsx! {
        Slider {
            value: 2.5,
            min_value: 0.0,
            max_value: 10.0,
            steps: 4_u32,
            on_value_change: move |value: f32| VALUES.with(|log| log.borrow_mut().push(value)),
        }
    }
}

/// The one control here that needs a payload. Where a drag lands is a continuous value the
/// Host cannot work out from what it already sent, so the Renderer reports it. The position
/// during the drag stays in the Renderer, which is why the Host hears one value and not one
/// per frame.
#[test]
fn fr15_slider_reports_the_dragged_value_through_the_wire() {
    VALUES.with(|log| log.borrow_mut().clear());
    let mut host = Host::new(slider_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let slider = node_of(&records, WidgetKind::Slider);
    for (property, value) in [
        (PropertyKind::Value, PropertyValue::Float(2.5)),
        (PropertyKind::MinValue, PropertyValue::Float(0.0)),
        (PropertyKind::MaxValue, PropertyValue::Float(10.0)),
        (PropertyKind::Steps, PropertyValue::Integer(4)),
    ] {
        assert!(
            records.contains(&Record::Prop(slider, property, value.clone())),
            "the slider must send {property:?} as {value:?}: {records:?}",
        );
    }

    let handler_id = handler_of(&records, slider, PropertyKind::OnValueChange);
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: slider,
            handler_id,
            payload: EventPayload::ValueChanged { value: 7.5 },
        },
        &mut wire,
    )
    .unwrap();
    host.dispatch_event(&wire).unwrap();
    assert_eq!(VALUES.with(|log| log.borrow().clone()), vec![7.5]);
}

/// The new event record is fixed layout like every other one: a 20-byte record whose
/// payload is the four bytes of the value, and decoding it returns what was encoded.
#[test]
fn pr4_value_changed_round_trips_as_a_twenty_byte_record() {
    let event = HostEvent {
        node_id: 15,
        handler_id: 17,
        payload: EventPayload::ValueChanged { value: 0.75 },
    };
    let mut wire = Vec::new();
    encode_event(&event, &mut wire).unwrap();
    assert_eq!(wire.len(), 20);
    assert_eq!(u16::from_le_bytes([wire[0], wire[1]]), 16);
    assert_eq!(decode_event(&wire).unwrap(), event);
}

fn progress_app() -> Element {
    rsx! {
        Column {
            ProgressIndicator { value: 0.4, determinate: true, circular: true }
            ProgressIndicator { determinate: false }
        }
    }
}

/// How fast an indeterminate indicator travels, and what easing it uses, is motion, and
/// motion is the design system's rule. The widget says only whether the value means
/// anything and which of the two shared forms it takes.
#[test]
fn fr15_progress_indicator_sends_only_its_value_model() {
    let mut host = Host::new(progress_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let created: Vec<u32> = records
        .iter()
        .filter_map(|record| match record {
            Record::Create(node_id, WidgetKind::ProgressIndicator) => Some(*node_id),
            _ => None,
        })
        .collect();
    assert_eq!(created.len(), 2);

    let mut determinate = value_props(&records, created[0]);
    determinate.sort_by_key(|(kind, _)| *kind as u16);
    assert_eq!(
        determinate,
        vec![
            (PropertyKind::Value, PropertyValue::Float(0.4)),
            (PropertyKind::Determinate, PropertyValue::Bool(true)),
            (PropertyKind::Circular, PropertyValue::Bool(true)),
        ],
    );

    assert!(
        value_props(&records, created[1])
            .contains(&(PropertyKind::Determinate, PropertyValue::Bool(false))),
    );
}

fn divider_app() -> Element {
    rsx! {
        Row {
            Divider { vertical: true }
            Divider {}
        }
    }
}

/// The thickness, the colour and the inset are the design system's, so the axis is the only
/// thing a divider puts on the wire.
#[test]
fn fr15_divider_carries_nothing_but_its_axis() {
    let mut host = Host::new(divider_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let dividers: Vec<u32> = records
        .iter()
        .filter_map(|record| match record {
            Record::Create(node_id, WidgetKind::Divider) => Some(*node_id),
            _ => None,
        })
        .collect();
    assert_eq!(dividers.len(), 2);
    assert_eq!(
        value_props(&records, dividers[0]),
        vec![(PropertyKind::Vertical, PropertyValue::Bool(true))],
    );
    assert_eq!(
        value_props(&records, dividers[1]),
        vec![(PropertyKind::Vertical, PropertyValue::Bool(false))],
    );
}

/// The checked-in vector is the bytes both sides are tested against, so each of the six has
/// to survive a decode of that file rather than only of a batch this test built.
#[test]
fn pr4_checked_in_vector_round_trips_every_selection_control() {
    let vector = generate_mutation_vector().unwrap();
    assert_eq!(
        vector.as_slice(),
        include_bytes!("vectors/mutations.bin").as_slice(),
        "the checked-in vector is stale; run `cargo run -p dioxus-compose --bin codegen`",
    );
    let decoded = decode_batch(&vector).unwrap();
    for (node_id, widget) in [
        (12, WidgetKind::Checkbox),
        (13, WidgetKind::RadioButton),
        (14, WidgetKind::Switch),
        (15, WidgetKind::Slider),
        (16, WidgetKind::ProgressIndicator),
        (17, WidgetKind::Divider),
    ] {
        assert!(
            decoded.contains(&Mutation::Create { node_id, widget }),
            "the vector must create a {widget:?}",
        );
    }
    for (node_id, property, value) in [
        (12, PropertyKind::Checked, PropertyValue::Bool(true)),
        (13, PropertyKind::Checked, PropertyValue::Bool(false)),
        (14, PropertyKind::Checked, PropertyValue::Bool(true)),
        (15, PropertyKind::Value, PropertyValue::Float(0.25)),
        (15, PropertyKind::MinValue, PropertyValue::Float(0.0)),
        (15, PropertyKind::MaxValue, PropertyValue::Float(10.0)),
        (15, PropertyKind::Steps, PropertyValue::Integer(4)),
        (16, PropertyKind::Determinate, PropertyValue::Bool(true)),
        (16, PropertyKind::Circular, PropertyValue::Bool(true)),
        (16, PropertyKind::Value, PropertyValue::Float(0.5)),
        (17, PropertyKind::Vertical, PropertyValue::Bool(true)),
    ] {
        assert!(
            decoded.contains(&Mutation::SetProp {
                node_id,
                property,
                value: value.clone(),
            }),
            "the vector must carry {property:?} on node {node_id}",
        );
    }
}

/// Every one of the six is reachable from `rsx!` and lands on its own widget tag in a
/// single frame, which is the per-widget round trip taken as one tree.
#[test]
fn fr15_all_six_widgets_round_trip_in_one_frame() {
    fn app() -> Element {
        rsx! {
            Column {
                Checkbox { checked: true }
                RadioButton { selected: false }
                Switch { checked: true }
                Slider { value: 0.5 }
                ProgressIndicator { value: 0.5 }
                Divider {}
            }
        }
    }

    let mut host = Host::new(app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    for widget in [
        WidgetKind::Checkbox,
        WidgetKind::RadioButton,
        WidgetKind::Switch,
        WidgetKind::Slider,
        WidgetKind::ProgressIndicator,
        WidgetKind::Divider,
    ] {
        node_of(&records, widget);
    }
}
