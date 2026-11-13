//! How an `Image` lays its picture into its box.
//!
//! The choice is one integer property on the image node. A picture that never chose is
//! sent nothing, so it draws as every picture did before there was a choice.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{DesignSystem, PropertyKind, Theme, WidgetKind};
use compose_rust::ui::*;
use std::cell::Cell;

thread_local! {
    static CHOICE: Cell<Option<ContentScale>> = const { Cell::new(None) };
}

#[Composable]
fn Pictures() {
    let image = Image(7).modifier(Modifier.size(64.0, 32.0));
    let icon = Icon(8);
    // An icon takes its design system's optical size, so it is never sent a scale.
    match CHOICE.with(Cell::get) {
        Some(scale) => {
            drop(image.content_scale(scale));
            drop(icon.content_scale(scale));
        }
        None => {
            drop(image);
            drop(icon);
        }
    }
}

/// The `ContentScale` records the first frame wrote, with the widget each was written on,
/// after the frame went through the wire encoding and back.
fn scale_records(choice: Option<ContentScale>) -> Vec<(WidgetKind, i64)> {
    CHOICE.with(|cell| cell.set(choice));
    let mut host = ComposeHost::with_theme(Pictures, Theme::unified(DesignSystem::Material3));
    let batch = host.rebuild().expect("the first frame encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the first frame decodes");
    let widget_of = |id: u32| {
        mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::Create { node_id, widget } if *node_id == id => Some(*widget),
                _ => None,
            })
            .expect("every property is written on a node the frame created")
    };
    let records = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::ContentScale,
                value,
            } => match value {
                PropertyValue::Integer(tag) => Some((widget_of(*node_id), *tag)),
                other => panic!("the content scale travels as an integer tag, not {other:?}"),
            },
            _ => None,
        })
        .collect();
    CHOICE.with(|cell| cell.set(None));
    records
}

/// `FillBounds` travels as tag 3 of property 37 on the image, and comes back out of the
/// batch as the value that went in. The tags are the ones the Renderer's enum is
/// generated from: `Fit` 1, `Crop` 2, `FillBounds` 3.
#[test]
fn fr16_image_content_scale_round_trips() {
    assert_eq!(PropertyKind::ContentScale as u16, 37);
    assert_eq!(u16::from(ContentScale::Fit), 1);
    assert_eq!(u16::from(ContentScale::Crop), 2);
    assert_eq!(u16::from(ContentScale::FillBounds), 3);
    assert_eq!(
        compose_rust::schema::CONTENT_SCALE_SCHEMA
            .iter()
            .map(|variant| (variant.name, variant.tag))
            .collect::<Vec<_>>(),
        vec![("Fit", 1), ("Crop", 2), ("FillBounds", 3)],
    );

    assert_eq!(
        scale_records(Some(ContentScale::FillBounds)),
        vec![(WidgetKind::Image, 3)],
    );
    assert_eq!(
        scale_records(Some(ContentScale::Crop)),
        vec![(WidgetKind::Image, 2)],
    );
    assert_eq!(
        scale_records(Some(ContentScale::Fit)),
        vec![(WidgetKind::Image, 1)]
    );
    assert_eq!(ContentScale::try_from(3), Ok(ContentScale::FillBounds));
    assert_eq!(ContentScale::try_from(0), Err(()));
}

/// An image that never chose writes no record, so it reaches the Renderer exactly as it
/// did before, and draws `Fit`.
#[test]
fn fr16_image_without_content_scale_sends_nothing() {
    assert_eq!(scale_records(None), Vec::<(WidgetKind, i64)>::new());
}
