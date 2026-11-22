mod support;

use compose_rust::ComposeHost;
use compose_rust::measure::{
    MEASURE_RECORD_LEN, MeasureRequests, TEXT_SPAN_METRICS_COUNT_AT, TEXT_SPAN_METRICS_OFFSET_AT,
    TextConstraint, TextStyle,
};
use compose_rust::protocol::{BatchEncoder, Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::mutable_state_of;
use compose_rust::schema::{Modifier as SchemaModifier, PropertyKind};
use compose_rust::spans::{
    SpanMetric, TextSpan, TextSpans, decode_metrics_table, encode_metrics_table,
};
use compose_rust::ui::{Box, Mask, MaskRepeat, MaskSize, Modifier, Text};
use support::Tree;

#[test]
fn fr10_mask_modifier_round_trips() {
    let modifier = SchemaModifier::Mask {
        brush: 42,
        repeat: 3,
        size_mode: 2,
        size_width: 120.0,
        size_height: 64.0,
        pos_x: 8.0,
        pos_y: -4.0,
    };
    let mut batch = BatchEncoder::default();
    batch
        .encode(&Mutation::SetModifier {
            node_id: 7,
            index: 0,
            modifier: modifier.clone(),
        })
        .expect("Mask encodes");
    let bytes = batch.finish().expect("Mask finishes");
    let decoded = decode_batch(bytes).expect("Mask decodes");
    assert_eq!(
        decoded,
        vec![Mutation::SetModifier {
            node_id: 7,
            index: 0,
            modifier,
        }]
    );

    // Checked-in protocol vector carries a Mask modifier on node 4.
    let checked_in = decode_batch(include_bytes!("vectors/mask_and_span_metrics.bin")).unwrap();
    assert!(
        checked_in.iter().any(|m| matches!(
            m,
            Mutation::SetModifier {
                node_id: 4,
                index: 0,
                modifier: SchemaModifier::Mask {
                    brush: 9,
                    repeat: 3,
                    size_mode: 2,
                    size_width,
                    size_height,
                    pos_x,
                    pos_y,
                },
            } if *size_width == 64.0 && *size_height == 32.0 && *pos_x == 4.0 && *pos_y == 2.0
        )),
        "checked-in mask_and_span_metrics vector must include Mask"
    );
}

#[test]
fn fr49_mask_change_sends_one_modifier() {
    let pos_x = mutable_state_of(0.0_f32);
    let state = pos_x;
    let mut host = ComposeHost::with_content(move || {
        let mut mask = Mask::new(9);
        mask.repeat = MaskRepeat::NoRepeat;
        mask.size_mode = MaskSize::Explicit;
        mask.size_width = 40.0;
        mask.size_height = 20.0;
        mask.pos_x = state.get();
        Box!(modifier = Modifier.mask(mask), {
            Text!("child");
        });
    });
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("initial batch encodes"));

    pos_x.set(12.0);
    let batch = host.render_frame(0).expect("update batch encodes").to_vec();
    let mutations = decode_batch(&batch).expect("update batch decodes");
    tree.apply(&batch);
    assert_eq!(mutations.len(), 1, "{mutations:?}");
    assert!(
        matches!(
            mutations[0],
            Mutation::SetModifier {
                modifier: SchemaModifier::Mask {
                    brush: 9,
                    repeat: 3,
                    size_mode: 3,
                    pos_x,
                    ..
                },
                ..
            } if pos_x == 12.0
        ),
        "{mutations:?}"
    );
    assert_eq!(tree.texts(), vec!["child".to_owned()]);
}

#[test]
fn fr40_span_metrics_round_trip() {
    assert_eq!(PropertyKind::SpanMetrics as u16, 218);
    assert_eq!(PropertyKind::try_from(218), Ok(PropertyKind::SpanMetrics));

    let entries = [
        SpanMetric::new(0, 28.0, f32::NAN),
        SpanMetric::new(1, f32::NAN, 2.0),
    ];
    let blob = encode_metrics_table(&entries).expect("non-empty blob");
    let decoded = decode_metrics_table(&blob).expect("decodes");
    assert_eq!(decoded.len(), 2);
    assert_eq!(decoded[0].span, 0);
    assert_eq!(decoded[0].font_size, 28.0);
    assert!(decoded[0].letter_spacing.is_nan());
    assert_eq!(decoded[1].span, 1);
    assert!(decoded[1].font_size.is_nan());
    assert_eq!(decoded[1].letter_spacing, 2.0);

    // Duplicate span index is rejected.
    let dup = encode_metrics_table(&[
        SpanMetric::new(0, 20.0, f32::NAN),
        SpanMetric::new(0, 24.0, f32::NAN),
    ])
    .unwrap();
    assert!(decode_metrics_table(&dup).is_none());

    // Checked-in protocol vector carries SpanMetrics on node 24.
    let checked_in = decode_batch(include_bytes!("vectors/mask_and_span_metrics.bin")).unwrap();
    let vector_blob = checked_in.iter().find_map(|m| match m {
        Mutation::SetProp {
            node_id: 24,
            property: PropertyKind::SpanMetrics,
            value: PropertyValue::Bytes(bytes),
        } => Some(*bytes),
        _ => None,
    });
    let vector_entries = decode_metrics_table(vector_blob.expect("vector has SpanMetrics"))
        .expect("vector SpanMetrics decodes");
    assert_eq!(vector_entries.len(), 2);
    assert_eq!(vector_entries[0].span, 1);
    assert_eq!(vector_entries[0].font_size, 28.0);
    assert_eq!(vector_entries[1].span, 4);
    assert_eq!(vector_entries[1].letter_spacing, 2.0);
}

#[test]
fn pr2_measure_record_carries_span_metrics() {
    assert_eq!(MEASURE_RECORD_LEN, 96);
    assert_eq!(TEXT_SPAN_METRICS_OFFSET_AT, 88);
    assert_eq!(TEXT_SPAN_METRICS_COUNT_AT, 92);

    let spans = TextSpans::new([TextSpan::new(0, 4), TextSpan::new(4, 4)])
        .with_metrics_table(encode_metrics_table(&[SpanMetric::new(1, 28.0, 2.0)]).unwrap());
    let mut batch = MeasureRequests::new();
    batch.text(
        "abcdEFGH",
        &spans,
        &TextStyle::default(),
        TextConstraint::MaxContent,
    );
    let bytes = batch.encoded();
    assert!(bytes.len() >= MEASURE_RECORD_LEN + 16);
    let offset = u32::from_le_bytes(
        bytes[TEXT_SPAN_METRICS_OFFSET_AT..TEXT_SPAN_METRICS_OFFSET_AT + 4]
            .try_into()
            .unwrap(),
    ) as usize;
    let count = u32::from_le_bytes(
        bytes[TEXT_SPAN_METRICS_COUNT_AT..TEXT_SPAN_METRICS_COUNT_AT + 4]
            .try_into()
            .unwrap(),
    );
    assert_eq!(count, 1);
    assert_eq!(
        u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap()),
        1
    );
    assert_eq!(
        f32::from_le_bytes(bytes[offset + 4..offset + 8].try_into().unwrap()),
        28.0
    );
    assert_eq!(
        f32::from_le_bytes(bytes[offset + 8..offset + 12].try_into().unwrap()),
        2.0
    );
}
