//! A button that is a key of a dense grid, on the wire.

use compose_rust::protocol::{BatchEncoder, Mutation, PropertyValue, decode_batch};
use compose_rust::{ButtonKind, PropertyKind, WidgetKind};

/// The kind of a button is property 75, and its two values keep their tags.
#[test]
fn fr30_1_button_kind_is_property_75_and_round_trips() {
    assert_eq!(PropertyKind::ButtonKind as u16, 75);
    assert_eq!(ButtonKind::Standard as u16, 1);
    assert_eq!(ButtonKind::ActionKey as u16, 2);
    let mutations = vec![
        Mutation::Create {
            node_id: 2,
            widget: WidgetKind::Button,
        },
        Mutation::SetProp {
            node_id: 2,
            property: PropertyKind::ButtonKind,
            value: PropertyValue::Integer(ButtonKind::ActionKey as i64),
        },
    ];
    let mut encoder = BatchEncoder::default();
    for mutation in &mutations {
        encoder.encode(mutation).unwrap();
    }
    let bytes = encoder.finish().unwrap().to_vec();
    assert_eq!(decode_batch(&bytes), Ok(mutations));
}

mod support;

use compose_rust::ComposeHost;
use compose_rust::runtime::*;
use compose_rust::ui::*;

#[composable]
fn Pad() {
    Button("7").kind(ButtonKind::ActionKey);
    Button("plain");
}

/// Only a button that names a kind sends one.
#[test]
fn fr30_1_only_a_button_that_names_a_kind_sends_one() {
    let mut host = ComposeHost::with_content(Pad);
    let batch = host.rebuild().unwrap().to_vec();
    let kinds: Vec<_> = decode_batch(&batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::ButtonKind,
                value,
            } => Some((node_id, value)),
            _ => None,
        })
        .collect();
    // A role that was not set travels as zero, which the Renderer reads as absent.
    let values: Vec<_> = kinds.iter().map(|(_, value)| value.clone()).collect();
    assert_eq!(
        values,
        vec![
            PropertyValue::Integer(ButtonKind::ActionKey as i64),
            PropertyValue::Integer(0)
        ]
    );
}
