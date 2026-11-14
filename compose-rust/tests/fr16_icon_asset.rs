//! An `Icon` drawn from an SVG the application ships: the asset id travels on its own
//! property, so the Renderer knows to tint the picture rather than draw it in its own
//! colours.

use compose_rust::ComposeHost;
use compose_rust::protocol::{BatchEncoder, Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{AssetKind, PropertyKind, WidgetKind};
use compose_rust::ui::*;

static STROKE: &[u8] = b"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>\
<path d='M4 12H20' stroke='black' stroke-width='4' fill='none'/></svg>";

const PRIMARY: Paint = Paint::Role(ColorRole::Primary);

#[Composable]
fn Icons() {
    Row().content(|| {
        let picture = asset(AssetKind::Svg, STROKE);
        Icon::asset(picture).color(PRIMARY);
        // The same picture through the plain constructor is a picture in its own colours.
        Icon(picture);
    });
}

/// The icon's property records: what `Icon::asset` sends, and what `Icon` still sends.
#[test]
fn fr16_icon_asset_travels_as_its_own_property() {
    assert_eq!(PropertyKind::IconAsset as u16, 205);
    assert_eq!(
        PropertyKind::from_name("icon_asset"),
        Ok(PropertyKind::IconAsset)
    );

    let mut host = ComposeHost::with_content(Icons);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the batch decodes");

    let registered: Vec<u32> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::RegisterAsset {
                asset_id,
                kind: AssetKind::Svg,
                bytes,
            } if *bytes == STROKE => Some(*asset_id),
            _ => None,
        })
        .collect();
    assert_eq!(registered.len(), 1, "{mutations:?}");
    let picture = i64::from(registered[0]);

    let icons: Vec<u32> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Icon,
            } => Some(*node_id),
            _ => None,
        })
        .collect();
    assert_eq!(icons.len(), 2, "{mutations:?}");
    let props_of = |id: u32| -> Vec<(PropertyKind, PropertyValue)> {
        mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property,
                    value,
                } if *node_id == id => Some((*property, value.clone())),
                _ => None,
            })
            .collect()
    };

    let tinted = props_of(icons[0]);
    assert!(
        tinted.contains(&(PropertyKind::IconAsset, PropertyValue::Integer(picture))),
        "{tinted:?}"
    );
    assert!(
        tinted.contains(&(
            PropertyKind::Color,
            PropertyValue::Integer(PRIMARY.to_bits() as i64)
        )),
        "{tinted:?}"
    );
    assert!(
        !tinted.iter().any(|(kind, _)| *kind == PropertyKind::Asset),
        "a tinted icon names its picture once: {tinted:?}"
    );

    let plain = props_of(icons[1]);
    assert!(
        plain.contains(&(PropertyKind::Asset, PropertyValue::Integer(picture))),
        "{plain:?}"
    );
    assert!(
        !plain
            .iter()
            .any(|(kind, _)| *kind == PropertyKind::IconAsset),
        "{plain:?}"
    );
}

/// The record itself survives the encoder and the decoder unchanged.
#[test]
fn fr16_icon_asset_round_trips_on_the_wire() {
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetProp {
            node_id: 4,
            property: PropertyKind::IconAsset,
            value: PropertyValue::Integer(9),
        })
        .unwrap();
    let batch = encoder.finish().unwrap().to_vec();
    assert_eq!(
        decode_batch(&batch).expect("the batch decodes"),
        vec![Mutation::SetProp {
            node_id: 4,
            property: PropertyKind::IconAsset,
            value: PropertyValue::Integer(9),
        }]
    );
}
