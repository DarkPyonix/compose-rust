//! The tallest a sheet grows, as a fraction of the window's height.
//!
//! The Host sends the fraction once, as one property on the `Sheet` node. The window's
//! height is the Renderer's to measure, so the screen never reads it.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{DesignSystem, PropertyKind, Theme, WidgetKind};
use compose_rust::ui::*;
use std::cell::Cell;

thread_local! {
    static FRACTION: Cell<Option<f32>> = const { Cell::new(None) };
}

#[Composable]
fn Modals() {
    let fraction = FRACTION.with(Cell::get);
    let mut sheet = Sheet(true);
    let mut dialog = Dialog(true);
    if let Some(fraction) = fraction {
        sheet = sheet.max_height_fraction(fraction);
        dialog = dialog.max_height_fraction(fraction);
    }
    sheet.content(|| {
        Text("in the sheet");
    });
    dialog.content(|| {
        Text("in the dialog");
    });
}

/// The `MaxHeightFraction` records the first frame wrote, with the widget of the node each
/// was written on, after the frame went through the wire encoding and back.
fn fraction_records(fraction: Option<f32>) -> Vec<(WidgetKind, f32)> {
    FRACTION.with(|cell| cell.set(fraction));
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
                property: PropertyKind::MaxHeightFraction,
                value,
            } => match value {
                PropertyValue::Float(fraction) => Some((widget_of(*node_id), *fraction)),
                other => panic!("the fraction travels as a float, not {other:?}"),
            },
            _ => None,
        })
        .collect();
    FRACTION.with(|cell| cell.set(None));
    records
}

/// 0.85 travels as property 189 on the sheet, comes back out of the batch as the value
/// that went in, and is not written on a dialog, which sizes itself to its content.
#[test]
fn fr21_sheet_max_height_fraction_round_trips() {
    assert_eq!(PropertyKind::MaxHeightFraction as u16, 189);
    assert_eq!(
        fraction_records(Some(0.85)),
        vec![(WidgetKind::Sheet, 0.85)]
    );
}

/// A sheet that never asked writes no record, so it reaches the Renderer exactly as it did
/// before; a fraction that is not above 0 is the same as not asking, and one above 1 is the
/// whole window.
#[test]
fn fr21_sheet_max_height_fraction_is_sent_only_in_range() {
    assert_eq!(fraction_records(None), Vec::<(WidgetKind, f32)>::new());
    assert_eq!(fraction_records(Some(0.0)), Vec::<(WidgetKind, f32)>::new());
    assert_eq!(
        fraction_records(Some(-0.5)),
        Vec::<(WidgetKind, f32)>::new()
    );
    assert_eq!(
        fraction_records(Some(f32::NAN)),
        Vec::<(WidgetKind, f32)>::new()
    );
    assert_eq!(fraction_records(Some(1.5)), vec![(WidgetKind::Sheet, 1.0)]);
    assert_eq!(fraction_records(Some(1.0)), vec![(WidgetKind::Sheet, 1.0)]);
}
