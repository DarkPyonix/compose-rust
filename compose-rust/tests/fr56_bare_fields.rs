//! A field drawn without the design system's frame: `bare()` on `TextField` and `Dropdown`
//! sends one boolean property, and a field that does not ask sends what it always sent.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{PropertyKind, WidgetKind};
use compose_rust::ui::*;

/// Each node the first batch creates as `widget`, with the `Bare` values written to it,
/// read back through the protocol decoder.
fn bare_values(content: impl Fn() + Send + Sync + 'static, widget: WidgetKind) -> Vec<Vec<bool>> {
    let mut host = ComposeHost::with_content(content);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the batch round-trips through the decoder");
    let nodes: Vec<u32> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: kind,
            } if *kind == widget => Some(*node_id),
            _ => None,
        })
        .collect();
    nodes
        .iter()
        .map(|node| {
            mutations
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Bare,
                        value,
                    } if node_id == node => match value {
                        PropertyValue::Bool(bare) => Some(*bare),
                        other => panic!("Bare is a boolean, not {other:?}"),
                    },
                    _ => None,
                })
                .collect()
        })
        .collect()
}

#[Composable]
fn Fields() {
    Column().content(|| {
        TextField().placeholder("검색").bare();
        TextField().placeholder("framed");
    });
}

#[Composable]
fn Selects() {
    Column().content(|| {
        Select(0).bare().content(|| {
            Text("Opus");
            Text("Sonnet");
        });
        Select(0).content(|| {
            Text("Opus");
        });
    });
}

#[test]
fn fr56_bare_text_field_round_trips_the_bare_property() {
    let values = bare_values(Fields, WidgetKind::TextField);
    assert_eq!(values.len(), 2, "two fields are created");
    assert_eq!(
        values[0],
        vec![true],
        "a bare field writes Bare = true once"
    );
    assert!(
        values[1].is_empty(),
        "a framed field writes no Bare property"
    );
}

#[test]
fn fr56_bare_dropdown_round_trips_the_bare_property() {
    let values = bare_values(Selects, WidgetKind::Dropdown);
    assert_eq!(values.len(), 2, "two selects are created");
    assert_eq!(
        values[0],
        vec![true],
        "a bare select writes Bare = true once"
    );
    assert!(
        values[1].is_empty(),
        "a framed select writes no Bare property"
    );
}

#[test]
fn fr56_bare_property_keeps_its_wire_tag() {
    assert_eq!(
        PropertyKind::Bare as u16,
        169,
        "Bare is appended at tag 169"
    );
}
