//! The design primitives as seen from rsx.

use compose_rust::prelude::*;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::{Host, PropertyKind, WidgetKind};

fn titled_text() -> Element {
    rsx! {
        Text { text: "제목", type_role: TypeRole::Title }
    }
}

fn styled_column() -> Element {
    rsx! {
        Column {
            arrangement: Arrangement::SpaceBetween,
            space_role: SpaceRole::Md,
            alignment: Alignment::Center,
            Button { text: "확인", variant: ButtonVariant::Tonal }
        }
    }
}

fn scrolling() -> Element {
    rsx! {
        ScrollColumn {
            Text { text: "long" }
        }
    }
}

fn props_of(app: fn() -> Element) -> Vec<(PropertyKind, PropertyValue<'static>)> {
    let mut host = Host::new(app);
    let batch = host.rebuild().unwrap().to_vec();
    // The batch borrows its strings, and the assertions only need the non-string values.
    decode_batch(&batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                property, value, ..
            } => Some((
                property,
                match value {
                    // Neither strings nor byte blobs are compared by value here.
                    PropertyValue::String(_) | PropertyValue::Bytes(_) => PropertyValue::None,
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

/// A Text that only names a `type_role` pays exactly one SetProp for it, and
/// the eight overrides it did not set cost nothing. A role is sent; zero is not.
#[test]
fn fr13_type_role_costs_one_set_prop() {
    let props = props_of(titled_text);
    let design: Vec<_> = props
        .iter()
        .filter(|(property, _)| *property as u16 >= PropertyKind::TypeRole as u16)
        .collect();
    assert_eq!(
        design,
        [&(
            PropertyKind::TypeRole,
            PropertyValue::Integer(TypeRole::Title as i64)
        )]
    );
}

/// Layout roles and the component variant reach the wire as their tags. The Host sends the
/// role; what it looks like is the design system's decision, not the caller's.
#[test]
fn fr13_layout_roles_and_variant_are_sent_as_tags() {
    let props = props_of(styled_column);
    for expected in [
        (
            PropertyKind::Arrangement,
            PropertyValue::Integer(Arrangement::SpaceBetween as i64),
        ),
        (
            PropertyKind::SpaceRole,
            PropertyValue::Integer(SpaceRole::Md as i64),
        ),
        (
            PropertyKind::Alignment,
            PropertyValue::Integer(Alignment::Center as i64),
        ),
        (
            PropertyKind::Variant,
            PropertyValue::Integer(ButtonVariant::Tonal as i64),
        ),
    ] {
        assert!(props.contains(&expected), "missing {expected:?}");
    }
    // The roles nobody set are absent rather than sent as zero.
    assert!(
        !props
            .iter()
            .any(|(property, _)| *property == PropertyKind::Spacing)
    );
}

fn monospaced_field() -> Element {
    rsx! {
        TextField { placeholder: "경로", type_role: TypeRole::Mono }
    }
}

/// A field holding a file path is set in the monospace rung, so the rung is a property of
/// the field like it is of a Text.
#[test]
fn fr13_text_field_takes_a_type_role() {
    let props = props_of(monospaced_field);
    assert!(
        props.contains(&(
            PropertyKind::TypeRole,
            PropertyValue::Integer(TypeRole::Mono as i64)
        )),
        "the field did not send its rung: {props:?}"
    );
}

fn destructive_button() -> Element {
    rsx! {
        Button {
            text: "삭제",
            variant: ButtonVariant::Text,
            color: Paint::Role(ColorRole::Error),
        }
    }
}

fn separated_rows() -> Element {
    rsx! {
        Column {
            Text { text: "위" }
            Separator {}
            Text { text: "아래" }
        }
    }
}

/// A destructive action is a plain button whose label is the error colour, so a Button
/// can name a colour role of its own. The role is what crosses; the shade is the design
/// system's.
#[test]
fn fr13_button_label_colour_is_sent_as_a_role() {
    let props = props_of(destructive_button);
    assert!(
        props.contains(&(
            PropertyKind::Color,
            PropertyValue::Integer(Paint::Role(ColorRole::Error).to_bits() as i64)
        )),
        "the button did not send its label colour: {props:?}"
    );
}

/// A Separator is one Divider, so the weight of the rule, its colour and how far it is held
/// back from the edge are the design system's answer rather than a constant in this crate.
#[test]
fn fr13_separator_is_one_divider_so_the_design_system_sets_its_weight() {
    let mut host = Host::new(separated_rows);
    let batch = host.rebuild().unwrap().to_vec();
    let mutations = decode_batch(&batch).unwrap();

    let divider = mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Divider,
            } => Some(*node_id),
            _ => None,
        })
        .expect("the separator is drawn as a Divider");

    // `Empty` is not a decision. A widget declares every modifier attribute and the unset
    // ones clear their slot, which is what stops an attribute that shares a slot with its
    // partner from being left behind by the previous frame. What would break the promise
    // here is a modifier that actually names a thickness or a colour.
    let modifiers: Vec<_> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                node_id, modifier, ..
            } if *node_id == divider && !matches!(modifier, Modifier::Empty) => {
                Some(modifier.clone())
            }
            _ => None,
        })
        .collect();
    assert!(
        modifiers.is_empty(),
        "a separator that named its own thickness or colour would be deciding for the \
         design system: {modifiers:?}"
    );

    assert!(
        !mutations.iter().any(|mutation| matches!(
            mutation,
            Mutation::Create {
                widget: WidgetKind::Spacer,
                ..
            }
        )),
        "the hand-drawn hairline is gone, so no Spacer stands in for the rule"
    );
}

/// ScrollColumn is a widget of its own, at wire tag 9.
#[test]
fn fr13_scroll_column_is_its_own_widget() {
    let mut host = Host::new(scrolling);
    let batch = host.rebuild().unwrap().to_vec();
    assert!(
        decode_batch(&batch)
            .unwrap()
            .iter()
            .any(|mutation| matches!(
                mutation,
                Mutation::Create {
                    widget: WidgetKind::ScrollColumn,
                    ..
                }
            ))
    );
}

fn scrolling_sideways() -> Element {
    rsx! {
        ScrollRow {
            width: 120.0,
            fill_max_height: true,
            Text { text: "first" }
            Text { text: "second" }
            Text { text: "third" }
        }
    }
}

/// The horizontal non-lazy scroll container is appended after the drop target at tag 37,
/// and the schema text that both sides hash says so in the same position.
#[test]
fn fr15_2_10_scroll_row_keeps_its_assigned_tag() {
    assert_eq!(WidgetKind::ScrollRow as u16, 37);
    assert_eq!(WidgetKind::try_from(37), Ok(WidgetKind::ScrollRow));
    assert!(
        compose_rust::schema::SCHEMA_DESCRIPTOR.contains(",FileDropTarget,ScrollRow"),
        "ScrollRow has to follow FileDropTarget in the widget list, because the order of \
         that list is the wire numbering"
    );
}

/// A ScrollRow is a widget of its own that carries every child, and nothing about where it
/// is scrolled to: the position is the Renderer's, as it is for a ScrollColumn, so the
/// Host sends the container, its modifiers and its children and not one property more.
#[test]
fn fr15_2_10_scroll_row_carries_its_children_and_no_scroll_state() {
    let mut host = Host::new(scrolling_sideways);
    let batch = host.rebuild().unwrap().to_vec();
    let mutations = decode_batch(&batch).unwrap();
    let row = mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::ScrollRow,
            } => Some(*node_id),
            _ => None,
        })
        .expect("rsx ScrollRow becomes a ScrollRow on the wire");

    let children = mutations
        .iter()
        .filter(|mutation| {
            matches!(mutation, Mutation::Insert { parent_id, node_id, .. }
                if *parent_id == row && *node_id != 0)
        })
        .count();
    assert_eq!(children, 3, "every child is materialised, none is windowed");

    let props: Vec<_> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id, property, ..
            } if *node_id == row => Some(*property),
            _ => None,
        })
        .collect();
    assert!(
        props.is_empty(),
        "a ScrollRow sent {props:?}, and where it is scrolled to never crosses the boundary"
    );

    let modifiers: Vec<_> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                node_id, modifier, ..
            } if *node_id == row && !matches!(modifier, Modifier::Empty) => Some(modifier.clone()),
            _ => None,
        })
        .collect();
    assert!(
        modifiers.contains(&Modifier::Width(120.0)),
        "the width it was given bounds the viewport: {modifiers:?}"
    );
    assert!(
        modifiers.contains(&Modifier::FillMaxHeight),
        "the same modifiers a ScrollColumn takes: {modifiers:?}"
    );
}
