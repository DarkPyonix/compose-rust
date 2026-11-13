//! Where a scroll container comes to rest once a drag or a fling ends.
//!
//! The Host says which rule applies, once, as one property on the scroll container. The
//! snap itself is the Renderer's: it runs inside the platform's fling, so the Host sends
//! nothing while the user scrolls.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{DesignSystem, PropertyKind, Theme, WidgetKind};
use compose_rust::ui::*;
use std::cell::Cell;

thread_local! {
    static CHOICE: Cell<Snap> = const { Cell::new(Snap::None) };
}

#[Composable]
fn Strips() {
    ScrollRow().snap(CHOICE.with(Cell::get)).content(|| {
        Box().modifier(Modifier.width(390.0)).content(|| {});
        Box().modifier(Modifier.width(390.0)).content(|| {});
    });
    ScrollColumn().snap(CHOICE.with(Cell::get)).content(|| {
        Text("one");
        Text("two");
    });
}

/// The `Snap` records the first frame wrote, with the widget of the node each was written
/// on, after the frame went through the wire encoding and back.
fn snap_records(choice: Snap) -> Vec<(WidgetKind, i64)> {
    CHOICE.with(|cell| cell.set(choice));
    let mut host = ComposeHost::with_theme(Strips, Theme::unified(DesignSystem::Material3));
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
                property: PropertyKind::Snap,
                value,
            } => match value {
                PropertyValue::Integer(tag) => Some((widget_of(*node_id), *tag)),
                other => panic!("the snap rule travels as an integer tag, not {other:?}"),
            },
            _ => None,
        })
        .collect();
    CHOICE.with(|cell| cell.set(Snap::None));
    records
}

/// `ItemStart` travels as tag 1 of property 188 on both scroll containers, and comes back
/// out of the batch as the value that went in.
#[test]
fn fr62_scroll_snap_round_trips_on_both_scroll_containers() {
    assert_eq!(PropertyKind::Snap as u16, 188);
    assert_eq!(u16::from(Snap::None), 0);
    assert_eq!(u16::from(Snap::ItemStart), 1);

    assert_eq!(
        snap_records(Snap::ItemStart),
        vec![(WidgetKind::ScrollRow, 1), (WidgetKind::ScrollColumn, 1)],
    );
    assert_eq!(Snap::try_from(1), Ok(Snap::ItemStart));
}

/// A container that never asked for snapping writes no record, so it reaches the Renderer
/// exactly as it did before there was a rule to choose.
#[test]
fn fr62_snap_none_sends_nothing() {
    assert_eq!(
        snap_records(Snap::None),
        Vec::<(WidgetKind, i64)>::new(),
        "free scrolling is the default, and the default is not sent"
    );
}

/// Only the real choice is in the table the Renderer's enum is generated from: tag 0 is
/// "not sent", as it is for every other role.
#[test]
fn fr62_only_item_start_is_in_the_generated_table() {
    assert_eq!(Snap::try_from(0), Err(()));
    assert_eq!(
        compose_rust::schema::SNAP_SCHEMA
            .iter()
            .map(|variant| (variant.name, variant.tag))
            .collect::<Vec<_>>(),
        vec![("ItemStart", 1)],
    );
}
