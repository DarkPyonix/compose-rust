//! `FlowRow` reaches the wire: its widget kind, its five layout properties, and its children.

use compose_rust::ComposeHost;
use compose_rust::codegen::generate_mutation_vector;
use compose_rust::protocol::{BatchEncoder, Mutation, PropertyValue, decode_batch};
use compose_rust::ui::*;
use compose_rust::{PropertyKind, WidgetKind};

fn flow_row_props(batch: &[u8]) -> (u32, Vec<(PropertyKind, PropertyValue<'_>)>, usize) {
    let mutations = decode_batch(batch).expect("the batch decodes");
    let flow = mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::FlowRow,
            } => Some(*node_id),
            _ => None,
        })
        .expect("a FlowRow node is created");
    let props = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property,
                value,
            } if *node_id == flow => Some((*property, value.clone())),
            _ => None,
        })
        .collect();
    let children = mutations
        .iter()
        .filter(
            |mutation| matches!(mutation, Mutation::Insert { parent_id, .. } if *parent_id == flow),
        )
        .count();
    (flow, props, children)
}

/// Eight chips of different widths under one `FlowRow`, with the gaps the mockup uses: the
/// Host writes one wrapping row and its eight children, and leaves the line breaking to the
/// Renderer, which measures them.
#[test]
fn fr76_flow_row_wraps_by_measured_width() {
    let mut host = ComposeHost::with_content(|| {
        FlowRow()
            .horizontal_spacing(8.0)
            .vertical_spacing(8.0)
            .modifier(Modifier.width(390.0))
            .content(|| {
                for label in [
                    "전체",
                    "읽지 않음",
                    "중요",
                    "보관함",
                    "첨부 파일 있음",
                    "나",
                    "이번 주",
                    "스팸",
                ] {
                    Chip(label);
                }
            });
    });
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let (_, props, children) = flow_row_props(&batch);
    assert_eq!(children, 8, "every chip is a child of the row");
    assert!(props.contains(&(PropertyKind::HorizontalSpacing, PropertyValue::Float(8.0))));
    assert!(props.contains(&(PropertyKind::VerticalSpacing, PropertyValue::Float(8.0))));
    // Nothing was said about alignment or a per-line limit, so nothing is written: the
    // Renderer's defaults are Start, Top and no limit.
    assert!(
        props.iter().all(|(property, _)| !matches!(
            property,
            PropertyKind::HorizontalAlignment
                | PropertyKind::VerticalAlignment
                | PropertyKind::MaxItemsInEachRow
        )),
        "{props:?}"
    );
}

/// Every layout property the builder takes is written under its own tag, with the value
/// it was given, and each one survives an encode and a decode unchanged.
#[test]
fn fr76_flow_row_spacing() {
    let mut host = ComposeHost::with_content(|| {
        FlowRow()
            .horizontal_spacing(6.0)
            .vertical_spacing(12.0)
            .horizontal_alignment(HorizontalAlignment::End)
            .vertical_alignment(VerticalAlignment::Center)
            .max_items_in_each_row(3)
            .content(|| {
                Text("a");
            });
    });
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let (flow, props, _) = flow_row_props(&batch);
    let expected = [
        (PropertyKind::HorizontalSpacing, PropertyValue::Float(6.0)),
        (PropertyKind::VerticalSpacing, PropertyValue::Float(12.0)),
        (
            PropertyKind::HorizontalAlignment,
            PropertyValue::Integer(HorizontalAlignment::End as i64),
        ),
        (
            PropertyKind::VerticalAlignment,
            PropertyValue::Integer(VerticalAlignment::Center as i64),
        ),
        (PropertyKind::MaxItemsInEachRow, PropertyValue::Integer(3)),
    ];
    for entry in &expected {
        assert!(props.contains(entry), "{entry:?} missing from {props:?}");
    }

    // The same records, encoded on their own and read back.
    let mutations: Vec<Mutation<'_>> = std::iter::once(Mutation::Create {
        node_id: flow,
        widget: WidgetKind::FlowRow,
    })
    .chain(expected.iter().map(|(property, value)| Mutation::SetProp {
        node_id: flow,
        property: *property,
        value: value.clone(),
    }))
    .collect();
    let mut encoder = BatchEncoder::default();
    for mutation in &mutations {
        encoder.encode(mutation).unwrap();
    }
    let bytes = encoder.finish().unwrap().to_vec();
    assert_eq!(decode_batch(&bytes).unwrap(), mutations);
}

/// The checked-in protocol vector carries a FlowRow and all five properties, so the
/// Kotlin decoder is held to the same bytes.
#[test]
fn fr76_flow_row_records_are_in_the_protocol_vector() {
    let bytes = generate_mutation_vector().unwrap();
    let (flow, props, _) = flow_row_props(&bytes);
    assert_eq!(flow, 21);
    assert_eq!(
        props,
        vec![
            (PropertyKind::HorizontalSpacing, PropertyValue::Float(8.0)),
            (PropertyKind::VerticalSpacing, PropertyValue::Float(12.0)),
            (
                PropertyKind::HorizontalAlignment,
                PropertyValue::Integer(HorizontalAlignment::Center as i64)
            ),
            (
                PropertyKind::VerticalAlignment,
                PropertyValue::Integer(VerticalAlignment::Bottom as i64)
            ),
            (PropertyKind::MaxItemsInEachRow, PropertyValue::Integer(3)),
        ]
    );
    assert_eq!(
        include_bytes!("vectors/mutations.bin").as_slice(),
        bytes.as_slice(),
        "the checked-in vector is the generated one; regenerate it with codegen"
    );
}
