//! Letter spacing on the wire: an explicit 0 is a real 0, and an unset spacing is NaN.
//!
//! A design property at 0 is not sent, so a `Text` whose letter spacing was 0 reached the
//! Renderer as text with no spacing set, and was drawn with its role's. CSS
//! `letter-spacing: 0` came out wider than the browser draws it.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{PropertyKind, WidgetKind};
use compose_rust::ui::*;

/// The letter spacing values one node was given, in order.
fn spacings(mutations: &[Mutation<'_>], node: u32) -> Vec<Option<f32>> {
    mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::LetterSpacing,
                value,
            } if *node_id == node => match value {
                PropertyValue::Float(value) => Some(Some(*value)),
                PropertyValue::None => Some(None),
                other => panic!("letter spacing is a float or nothing, not {other:?}"),
            },
            _ => None,
        })
        .collect()
}

fn texts(mutations: &[Mutation<'_>]) -> Vec<u32> {
    mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Text,
            } => Some(*node_id),
            _ => None,
        })
        .collect()
}

/// 0 is unset, 1 is an explicit 0, 2 is 1.5sp.
#[Composable]
fn Spaced(spacing: MutableState<u8>) {
    Column().content(|| {
        let text = Text("A");
        match spacing.get() {
            1 => text.letter_spacing(0.0),
            2 => text.letter_spacing(1.5),
            _ => text,
        };
        Text("zero").letter_spacing(0.0);
        Text("plain");
    });
}

#[test]
fn fr40_explicit_zero_letter_spacing_is_not_unset() {
    let spacing = mutable_state_of(0u8);
    let state = spacing;
    let mut host = ComposeHost::with_content(move || Spaced(state));
    let first = host.rebuild().unwrap().to_vec();
    let first = decode_batch(&first).unwrap();
    let nodes = texts(&first);
    assert_eq!(nodes.len(), 3, "three texts are created");
    assert!(
        spacings(&first, nodes[0]).is_empty(),
        "an unset spacing sends nothing, so the Renderer uses the role's"
    );
    assert_eq!(
        spacings(&first, nodes[1]),
        vec![Some(0.0)],
        "an explicit 0 is sent, so the Renderer draws no extra spacing"
    );
    assert!(spacings(&first, nodes[2]).is_empty());

    spacing.set(2);
    let set = host.render_frame(1).unwrap().to_vec();
    assert_eq!(
        spacings(&decode_batch(&set).unwrap(), nodes[0]),
        vec![Some(1.5)]
    );

    spacing.set(1);
    let zero = host.render_frame(2).unwrap().to_vec();
    assert_eq!(
        spacings(&decode_batch(&zero).unwrap(), nodes[0]),
        vec![Some(0.0)],
        "going to 0 sends 0, not a clear"
    );

    spacing.set(0);
    let cleared = host.render_frame(3).unwrap().to_vec();
    let cleared = spacings(&decode_batch(&cleared).unwrap(), nodes[0]);
    assert_eq!(cleared.len(), 1, "clearing sends one record: {cleared:?}");
    assert!(
        cleared[0].is_none_or(f32::is_nan),
        "a cleared spacing is unset (NaN), not 0: {cleared:?}"
    );

    // Recomposing with nothing changed sends nothing again.
    spacing.set(0);
    let again = host.render_frame(4).unwrap().to_vec();
    assert!(spacings(&decode_batch(&again).unwrap(), nodes[0]).is_empty());
}
