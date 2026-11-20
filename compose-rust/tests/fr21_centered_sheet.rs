//! Centered Sheet presentation with scrim, max width, and schema encoding.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{DesignSystem, PropertyKind, SheetPresentation, Theme, WidgetKind};
use compose_rust::ui::*;
use std::cell::Cell;

thread_local! {
    static PRESENTATION: Cell<Option<SheetPresentation>> = const { Cell::new(None) };
    static MAX_WIDTH: Cell<Option<u32>> = const { Cell::new(None) };
}

#[Composable]
fn CenteredModals() {
    let presentation = PRESENTATION.with(Cell::get);
    let max_width = MAX_WIDTH.with(Cell::get);
    Sheet!(
        true,
        || {},
        presentation = presentation.unwrap_or(SheetPresentation::Edge),
        max_width = max_width,
        {
            Text!("in the sheet");
        }
    );
    // A dialog is always centered and sized by its content: it takes neither parameter.
    Dialog!(true, || {}, {
        Text!("in the dialog");
    });
}

/// The `(widget, value)` pairs one property was sent with.
type PropertyRecords = Vec<(WidgetKind, i64)>;

fn sheet_property_records(
    presentation: Option<SheetPresentation>,
    max_width: Option<u32>,
) -> (PropertyRecords, PropertyRecords) {
    PRESENTATION.with(|cell| cell.set(presentation));
    MAX_WIDTH.with(|cell| cell.set(max_width));
    let mut host = ComposeHost::with_theme(CenteredModals, Theme::unified(DesignSystem::Material3));
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
    let pres_records = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::Presentation,
                value,
            } => match value {
                PropertyValue::Integer(val) => Some((widget_of(*node_id), *val)),
                other => panic!("presentation travels as an integer, not {other:?}"),
            },
            _ => None,
        })
        .collect();
    let max_width_records = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::MaxWidth,
                value,
            } => match value {
                PropertyValue::Integer(val) => Some((widget_of(*node_id), *val)),
                other => panic!("max_width travels as an integer, not {other:?}"),
            },
            _ => None,
        })
        .collect();
    PRESENTATION.with(|cell| cell.set(None));
    MAX_WIDTH.with(|cell| cell.set(None));
    (pres_records, max_width_records)
}

#[test]
fn fr21_sheet_default_presentation_is_edge() {
    assert_eq!(PropertyKind::Presentation as u16, 206);
    assert_eq!(PropertyKind::MaxWidth as u16, 207);
    assert_eq!(SheetPresentation::try_from(0), Ok(SheetPresentation::Edge));
    assert_eq!(
        SheetPresentation::try_from(1),
        Ok(SheetPresentation::Centered)
    );

    let (pres, width) = sheet_property_records(None, None);
    assert!(
        pres.is_empty(),
        "default Sheet sends no presentation property"
    );
    assert!(
        width.is_empty(),
        "default Sheet sends no max_width property"
    );

    let (pres_edge, _) = sheet_property_records(Some(SheetPresentation::Edge), None);
    assert!(
        pres_edge.is_empty(),
        "Edge presentation is the default and sends no property"
    );
}

#[test]
fn fr21_sheet_presentation_centered_emits_property() {
    let (pres, width) = sheet_property_records(Some(SheetPresentation::Centered), Some(1360));
    assert_eq!(
        pres,
        vec![(WidgetKind::Sheet, 1)],
        "Centered presentation travels as 1 on the sheet"
    );
    assert_eq!(
        width,
        vec![(WidgetKind::Sheet, 1360)],
        "max_width 1360 travels as integer 1360 on the sheet"
    );
}
