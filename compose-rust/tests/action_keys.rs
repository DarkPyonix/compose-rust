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

#[Composable]
fn Pad() {
    Button!("7", || {}, kind = ButtonKind::ActionKey);
    Button!("plain", || {});
}

/// The `ButtonKind` records of a batch, as the node and the value each one carries.
fn kind_records(batch: &[u8]) -> Vec<(u32, PropertyValue<'_>)> {
    decode_batch(batch)
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
        .collect()
}

/// Only a button that names a kind sends one.
#[test]
fn fr30_1_only_a_button_that_names_a_kind_sends_one() {
    let mut host = ComposeHost::with_content(Pad);
    let batch = host.rebuild().unwrap().to_vec();
    let values: Vec<_> = kind_records(&batch)
        .into_iter()
        .map(|(_, value)| value)
        .collect();
    assert_eq!(
        values,
        vec![PropertyValue::Integer(ButtonKind::ActionKey as i64)],
        "the plain button sent a kind it never named"
    );
}

#[Composable]
fn Key(kind: MutableState<Option<ButtonKind>>, label: MutableState<&'static str>) {
    Button!(label.get(), || {}, kind = kind.get());
}

/// A button that drops its kind sends the zero once, which the Renderer reads as the
/// standard button, and sends nothing about its kind after that.
#[test]
fn fr30_1_a_button_that_drops_its_kind_sends_the_removal_once() {
    let kind = mutable_state_of(Some(ButtonKind::ActionKey));
    let label = mutable_state_of("7");
    let mut host = ComposeHost::with_content(move || Key(kind, label));
    let first = host.rebuild().unwrap().to_vec();
    let named = kind_records(&first);
    assert_eq!(named.len(), 1, "{named:?}");
    let node = named[0].0;
    assert_eq!(
        named[0].1,
        PropertyValue::Integer(ButtonKind::ActionKey as i64)
    );

    kind.set(None);
    let dropped = host.render_frame(0).unwrap().to_vec();
    assert_eq!(
        kind_records(&dropped),
        vec![(node, PropertyValue::Integer(0))],
        "the kind that was taken away is not withdrawn"
    );

    label.set("8");
    let after = host.render_frame(0).unwrap().to_vec();
    assert!(
        !decode_batch(&after).unwrap().is_empty(),
        "the label change sent nothing, so this frame proves nothing"
    );
    let repeated = kind_records(&after);
    assert!(
        repeated.is_empty(),
        "a kind already withdrawn is withdrawn again: {repeated:?}"
    );

    kind.set(Some(ButtonKind::ActionKey));
    let again = host.render_frame(0).unwrap().to_vec();
    assert_eq!(
        kind_records(&again),
        vec![(node, PropertyValue::Integer(ButtonKind::ActionKey as i64))],
        "a kind named again is not sent"
    );
}

/// A plain button that composes again says nothing about its kind.
#[test]
fn fr30_1_a_plain_button_that_recomposes_sends_no_kind() {
    let kind = mutable_state_of(None);
    let label = mutable_state_of("plain");
    let mut host = ComposeHost::with_content(move || Key(kind, label));
    let first = host.rebuild().unwrap().to_vec();
    let sent = kind_records(&first);
    assert!(sent.is_empty(), "{sent:?}");

    label.set("still plain");
    let second = host.render_frame(0).unwrap().to_vec();
    assert!(
        !decode_batch(&second).unwrap().is_empty(),
        "the label change sent nothing, so this frame proves nothing"
    );
    let sent = kind_records(&second);
    assert!(sent.is_empty(), "{sent:?}");
}
