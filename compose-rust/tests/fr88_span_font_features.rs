use compose_rust::ComposeHost;
use compose_rust::font_features::{FONT_FEATURE_LEN, FontFeature};
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::schema::{PropertyKind, TypeRole, WidgetKind};
use compose_rust::spans::{
    SPAN_FEATURE_COUNT_AT, SPAN_FEATURE_ENTRY_LEN, SPAN_FEATURE_OFFSET_AT, SPAN_FEATURE_SPAN_AT,
    TextSpan, TextSpans,
};
use compose_rust::ui::*;

#[test]
fn fr88_span_font_features_encode_decode_round_trip() {
    let span0 = TextSpan::new(0, 5);
    let span1 = TextSpan::new(5, 4);
    let span2 = TextSpan::new(9, 6);

    let spans = TextSpans::new_with_features([
        (span0, vec![]),
        (span1, vec![FontFeature::TABULAR_NUMS]),
        (
            span2,
            vec![
                FontFeature::NO_LIGATURES,
                FontFeature::new(b"ss01", 2),
            ],
        ),
    ]);

    assert_eq!(spans.feature_entry_count(), 2);
    let decoded = spans.span_features();
    assert_eq!(decoded.len(), 2);

    // Span 1 has tnum
    assert_eq!(decoded[0].0, 1);
    assert_eq!(decoded[0].1, vec![FontFeature::TABULAR_NUMS]);

    // Span 2 has liga 0 and ss01 2
    assert_eq!(decoded[1].0, 2);
    assert_eq!(
        decoded[1].1,
        vec![
            FontFeature::NO_LIGATURES,
            FontFeature::new(b"ss01", 2),
        ]
    );

    // Verify blob structure
    let table = spans.feature_table();
    assert!(table.len() >= 4 + 2 * SPAN_FEATURE_ENTRY_LEN + 3 * FONT_FEATURE_LEN);
    let count = u32::from_le_bytes([table[0], table[1], table[2], table[3]]);
    assert_eq!(count, 2);

    // Entry 0 points to span 1
    let entry0_at = 4;
    let span_idx0 = u32::from_le_bytes([
        table[entry0_at + SPAN_FEATURE_SPAN_AT],
        table[entry0_at + SPAN_FEATURE_SPAN_AT + 1],
        table[entry0_at + SPAN_FEATURE_SPAN_AT + 2],
        table[entry0_at + SPAN_FEATURE_SPAN_AT + 3],
    ]);
    assert_eq!(span_idx0, 1);
}

#[Composable]
fn TextWithSpanFeatures() {
    Column().content(|| {
        let span0 = TextSpan::new(0, 4);
        let span1 = TextSpan::new(4, 4);
        let spans = TextSpans::new_with_features([
            (span0, vec![]),
            (span1, vec![FontFeature::TABULAR_NUMS]),
        ]);
        Text("12345678").spans(spans);

        let plain_spans = TextSpans::new([TextSpan::new(0, 8)]);
        Text("87654321").spans(plain_spans);

        Text("no spans at all");
    });
}

#[test]
fn fr88_span_font_features_writes_one_property_only_when_set() {
    let mut host = ComposeHost::new();
    let batch = host.render(TextWithSpanFeatures);
    let mutations = decode_batch(&batch).expect("batch decodes");

    let text_nodes: Vec<u32> = mutations
        .iter()
        .filter_map(|m| match m {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Text,
            } => Some(*node_id),
            _ => None,
        })
        .collect();

    assert_eq!(text_nodes.len(), 3);

    // Node 0 has span font features
    let node0_has_span_features = mutations.iter().any(|m| match m {
        Mutation::SetProp {
            node_id,
            property: PropertyKind::SpanFontFeatures,
            value: PropertyValue::Bytes(bytes),
        } if *node_id == text_nodes[0] => !bytes.is_empty(),
        _ => false,
    });
    assert!(node0_has_span_features, "node 0 must emit SpanFontFeatures");

    // Node 1 (plain spans) and Node 2 (no spans) must NOT emit SpanFontFeatures
    for &node_id in &text_nodes[1..] {
        let has_span_features = mutations.iter().any(|m| match m {
            Mutation::SetProp {
                node_id: id,
                property: PropertyKind::SpanFontFeatures,
                ..
            } if *id == node_id => true,
            _ => false,
        });
        assert!(!has_span_features, "node {node_id} must not emit SpanFontFeatures");
    }
}

#[test]
fn fr88_span_font_features_with_feature_table_adopts_blob() {
    let span = TextSpan::new(0, 10);
    let spans1 = TextSpans::new_with_features([(span, vec![FontFeature::TABULAR_NUMS])]);
    let blob = spans1.feature_table().to_vec();

    let spans2 = TextSpans::new([span]).with_feature_table(blob);
    assert_eq!(spans2.feature_entry_count(), 1);
    assert_eq!(spans2.span_features(), spans1.span_features());
}
