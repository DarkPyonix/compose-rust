use compose_rust::measure::{
    MEASURE_RECORD_LEN, MeasureRequests, TEXT_SPAN_METRICS_COUNT_AT, TEXT_SPAN_METRICS_OFFSET_AT,
    TextConstraint, TextStyle,
};
use compose_rust::protocol::{BatchEncoder, Mutation, decode_batch};
use compose_rust::schema::{Modifier, PropertyKind};
use compose_rust::spans::{
    SpanMetric, TextSpan, TextSpans, decode_metrics_table, encode_metrics_table,
};

#[test]
fn fr10_mask_modifier_round_trips() {
    let modifier = Modifier::Mask {
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
