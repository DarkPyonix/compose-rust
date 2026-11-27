//! The heights a bottom sheet rests at, and whether a pull closes it, as they reach the wire.
//!
//! Each is sent only when it says something other than the default, so a sheet that sets
//! neither reaches the Renderer exactly as it did before. Where the sheet rests and what a
//! pull does is the Renderer's, and is tested on its side.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{DesignSystem, PropertyKind, Theme, WidgetKind};
use compose_rust::ui::*;
use std::cell::Cell;

const MUTATIONS: &[u8] = include_bytes!("vectors/mutations.bin");

thread_local! {
    static DETENTS: Cell<SheetDetents> = const { Cell::new(SheetDetents::NONE) };
    static DRAG_TO_DISMISS: Cell<bool> = const { Cell::new(true) };
}

#[Composable]
fn Modals() {
    Sheet!(
        true,
        || {},
        detents = DETENTS.with(Cell::get),
        drag_to_dismiss = DRAG_TO_DISMISS.with(Cell::get),
        {
            Text!("in the sheet");
        }
    );
    // A dialog rests nowhere and is not pulled, so it takes neither parameter.
    Dialog!(true, || {}, {
        Text!("in the dialog");
    });
}

/// The two properties the first frame wrote, with the widget of the node each was written
/// on, after the frame went through the wire encoding and back.
fn sheet_records(
    detents: SheetDetents,
    drag_to_dismiss: bool,
) -> Vec<(WidgetKind, PropertyKind, PropertyValue<'static>)> {
    DETENTS.with(|cell| cell.set(detents));
    DRAG_TO_DISMISS.with(|cell| cell.set(drag_to_dismiss));
    let mut host = ComposeHost::with_theme(Modals, Theme::unified(DesignSystem::Material3));
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
                property: property @ (PropertyKind::SheetDetents | PropertyKind::SheetDragToDismiss),
                value,
            } => {
                let value = match value {
                    PropertyValue::Integer(bits) => PropertyValue::Integer(*bits),
                    PropertyValue::Bool(flag) => PropertyValue::Bool(*flag),
                    other => panic!("{property:?} travelled as {other:?}"),
                };
                Some((widget_of(*node_id), *property, value))
            }
            _ => None,
        })
        .collect();
    DETENTS.with(|cell| cell.set(SheetDetents::NONE));
    DRAG_TO_DISMISS.with(|cell| cell.set(true));
    records
}

/// The resting heights travel as property 224, the bits of the set, and the pull as
/// property 225, a boolean, both on the sheet alone. The checked-in vector carries both.
/// Bits that name no height are not a set.
#[test]
fn fr21_3_4_sheet_detents_round_trip() {
    assert_eq!(PropertyKind::SheetDetents as u16, 224);
    assert_eq!(PropertyKind::SheetDragToDismiss as u16, 225);
    assert_eq!(
        PropertyKind::from_name("sheet_detents"),
        Ok(PropertyKind::SheetDetents)
    );
    assert_eq!(
        PropertyKind::from_name("sheet_drag_to_dismiss"),
        Ok(PropertyKind::SheetDragToDismiss)
    );
    assert_eq!(SheetDetents::MEDIUM.bits(), 1);
    assert_eq!(SheetDetents::LARGE.bits(), 2);
    let both = SheetDetents::MEDIUM | SheetDetents::LARGE;
    assert_eq!(both.bits(), 3);
    assert!(both.contains(SheetDetents::MEDIUM) && both.contains(SheetDetents::LARGE));
    assert!(!SheetDetents::MEDIUM.contains(SheetDetents::LARGE));
    for bits in 0..=3 {
        assert_eq!(
            SheetDetents::from_bits(bits).map(SheetDetents::bits),
            Some(bits)
        );
    }
    for unknown in [4_u32, 5, 8, 0x8000_0001] {
        assert_eq!(
            SheetDetents::from_bits(unknown),
            None,
            "{unknown:#x} sets a bit that names no height"
        );
    }

    assert_eq!(
        sheet_records(both, false),
        [
            (
                WidgetKind::Sheet,
                PropertyKind::SheetDetents,
                PropertyValue::Integer(3)
            ),
            (
                WidgetKind::Sheet,
                PropertyKind::SheetDragToDismiss,
                PropertyValue::Bool(false)
            ),
        ]
    );
    assert_eq!(
        sheet_records(SheetDetents::LARGE, true),
        [(
            WidgetKind::Sheet,
            PropertyKind::SheetDetents,
            PropertyValue::Integer(2)
        )]
    );

    let vector = decode_batch(MUTATIONS).expect("the mutation vector decodes");
    assert!(vector.contains(&Mutation::Create {
        node_id: 27,
        widget: WidgetKind::Sheet
    }));
    assert!(vector.contains(&Mutation::SetProp {
        node_id: 27,
        property: PropertyKind::SheetDetents,
        value: PropertyValue::Integer(3),
    }));
    assert!(vector.contains(&Mutation::SetProp {
        node_id: 27,
        property: PropertyKind::SheetDragToDismiss,
        value: PropertyValue::Bool(false),
    }));
}

/// A sheet that sets neither writes neither record.
#[test]
fn fr21_3_4_a_sheet_that_sets_neither_writes_no_record() {
    assert_eq!(sheet_records(SheetDetents::NONE, true), []);
}
