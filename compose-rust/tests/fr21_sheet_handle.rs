//! Whether a bottom sheet draws its drag handle.
//!
//! The Host sends one boolean on the `Sheet` node, and only to turn the handle off, so a
//! sheet that keeps it reaches the Renderer exactly as it did before.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{DesignSystem, PropertyKind, Theme, WidgetKind};
use compose_rust::ui::*;
use std::cell::Cell;

thread_local! {
    static HANDLE: Cell<Option<bool>> = const { Cell::new(None) };
}

#[Composable]
fn Modals() {
    let handle = HANDLE.with(Cell::get);
    Sheet!(true, || {}, handle = handle.unwrap_or(true), {
        Text!("in the sheet");
    });
    // A dialog has no handle, so it does not take the parameter.
    Dialog!(true, || {}, {
        Text!("in the dialog");
    });
}

/// The `SheetHandle` records the first frame wrote, with the widget of the node each was
/// written on, after the frame went through the wire encoding and back.
fn handle_records(handle: Option<bool>) -> Vec<(WidgetKind, bool)> {
    HANDLE.with(|cell| cell.set(handle));
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
                property: PropertyKind::SheetHandle,
                value,
            } => match value {
                PropertyValue::Bool(show) => Some((widget_of(*node_id), *show)),
                other => panic!("the handle travels as a boolean, not {other:?}"),
            },
            _ => None,
        })
        .collect();
    HANDLE.with(|cell| cell.set(None));
    records
}

/// `handle(false)` travels as property 214 on the sheet and is not written on a dialog,
/// which has no handle.
#[test]
fn fr21_sheet_handle_false_travels_on_the_wire() {
    assert_eq!(PropertyKind::SheetHandle as u16, 214);
    assert_eq!(
        PropertyKind::from_name("sheet_handle"),
        Ok(PropertyKind::SheetHandle)
    );
    assert_eq!(
        handle_records(Some(false)),
        vec![(WidgetKind::Sheet, false)]
    );
}

/// The default is a handle, so a sheet that never asked and one that asked for the handle
/// both write nothing.
#[test]
fn fr21_sheet_handle_default_writes_no_record() {
    assert_eq!(handle_records(None), Vec::<(WidgetKind, bool)>::new());
    assert_eq!(handle_records(Some(true)), Vec::<(WidgetKind, bool)>::new());
}
