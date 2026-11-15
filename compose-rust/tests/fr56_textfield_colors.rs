//! A `TextField` can name its placeholder colour and caret colour as `Paint` values.
//! When unset, neither property is sent and the design system's defaults remain.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{ColorRole, PropertyKind, WidgetKind};
use compose_rust::ui::*;

const LITERAL_GREEN: Paint = Paint::Literal(Color::rgb(0x12_3456));
const PRIMARY_ROLE: Paint = Paint::Role(ColorRole::Primary);

#[Composable]
fn ConfiguredFields() {
    Column().content(|| {
        TextField()
            .placeholder("Search")
            .placeholder_color(LITERAL_GREEN)
            .caret_color(PRIMARY_ROLE);
        TextField().placeholder("Default");
    });
}

#[test]
fn fr56_text_field_placeholder_and_caret_paint_round_trips() {
    let mut host = ComposeHost::with_content(ConfiguredFields);
    let batch = host.rebuild().expect("the batch encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the batch decodes");

    let fields: Vec<u32> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::TextField,
            } => Some(*node_id),
            _ => None,
        })
        .collect();
    assert_eq!(fields.len(), 2, "two fields are created");

    let configured_field = fields[0];
    let default_field = fields[1];

    let placeholder_color_mutation = mutations.iter().find(|m| {
        matches!(
            m,
            Mutation::SetProp {
                node_id,
                property: PropertyKind::PlaceholderColor,
                ..
            } if *node_id == configured_field
        )
    });
    let caret_color_mutation = mutations.iter().find(|m| {
        matches!(
            m,
            Mutation::SetProp {
                node_id,
                property: PropertyKind::CaretColor,
                ..
            } if *node_id == configured_field
        )
    });

    assert!(
        placeholder_color_mutation.is_some(),
        "configured field emits PlaceholderColor property"
    );
    assert!(
        caret_color_mutation.is_some(),
        "configured field emits CaretColor property"
    );

    match placeholder_color_mutation.unwrap() {
        Mutation::SetProp {
            value: PropertyValue::Integer(bits),
            ..
        } => {
            let paint = Paint::from_bits(*bits as u64).expect("valid paint bits");
            assert_eq!(paint, LITERAL_GREEN);
        }
        other => panic!("expected PropertyValue::Integer, got {other:?}"),
    }

    match caret_color_mutation.unwrap() {
        Mutation::SetProp {
            value: PropertyValue::Integer(bits),
            ..
        } => {
            let paint = Paint::from_bits(*bits as u64).expect("valid paint bits");
            assert_eq!(paint, PRIMARY_ROLE);
        }
        other => panic!("expected PropertyValue::Integer, got {other:?}"),
    }

    let default_placeholder = mutations.iter().find(|m| {
        matches!(
            m,
            Mutation::SetProp {
                node_id,
                property: PropertyKind::PlaceholderColor,
                ..
            } if *node_id == default_field
        )
    });
    let default_caret = mutations.iter().find(|m| {
        matches!(
            m,
            Mutation::SetProp {
                node_id,
                property: PropertyKind::CaretColor,
                ..
            } if *node_id == default_field
        )
    });

    assert!(
        default_placeholder.is_none(),
        "default field emits no PlaceholderColor property"
    );
    assert!(
        default_caret.is_none(),
        "default field emits no CaretColor property"
    );
}

#[test]
fn fr56_text_field_color_properties_keep_their_wire_tags() {
    assert_eq!(
        PropertyKind::PlaceholderColor as u16,
        203,
        "PlaceholderColor tag is 203"
    );
    assert_eq!(
        PropertyKind::CaretColor as u16,
        204,
        "CaretColor tag is 204"
    );
}
