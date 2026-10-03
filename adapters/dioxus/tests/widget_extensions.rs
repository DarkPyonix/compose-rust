//! Compile-time schema extensions use the same typed RSX and the same fixed-layout protocol
//! as the built-in widgets. An extension is a pair of source files, one Rust and one Kotlin,
//! compiled into both sides; it is not a runtime plugin.

use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{Mutation, PropertyValue, ProtocolError, decode_batch};
use dioxus_compose_adapter::schema::{PROPERTY_SCHEMA, SCHEMA_HASH, WIDGET_SCHEMA};
use dioxus_compose_adapter::{Host, PropertyKind, WidgetKind};

fn progress_app() -> Element {
    rsx! { LinearProgressIndicator { progress: 0.625 } }
}

fn zero_progress_app() -> Element {
    rsx! { LinearProgressIndicator { progress: 0.0 } }
}

#[test]
fn fr11_typed_extension_uses_fixed_layout_mutations() {
    let mut host = Host::new(progress_app);
    let batch = host.rebuild().unwrap().to_vec();
    let mutations = decode_batch(&batch).unwrap();

    assert!(mutations.iter().any(|mutation| matches!(
        mutation,
        Mutation::Create {
            widget: WidgetKind::LinearProgressIndicator,
            ..
        }
    )));
    assert!(mutations.iter().any(|mutation| matches!(
        mutation,
        Mutation::SetProp {
            property: PropertyKind::Progress,
            value: PropertyValue::Float(value),
            ..
        } if *value == 0.625
    )));

    let records_length = u32::from_le_bytes(batch[4..8].try_into().unwrap()) as usize;
    let mut offset = 12;
    let mut lengths = Vec::new();
    while offset < records_length {
        let length = u16::from_le_bytes(batch[offset + 2..offset + 4].try_into().unwrap());
        lengths.push(length);
        offset += usize::from(length);
    }
    // The theme, the window, the node and its one property: four records, each four-byte
    // aligned, each carrying its own length in its header. That is what lets a reader walk
    // the batch without knowing what any of it means, and it is the property worth
    // asserting.
    //
    // The exact lengths are not. This assertion used to spell them out and broke twice in
    // one day for the same uninteresting reason: the window's record grew when it learned
    // to carry a title, and again when it learned to carry an icon. Neither had anything
    // to do with what this test is about.
    //
    // Four and not eight. The comment here used to say eight while the numbers beside it
    // included a twelve, so the first rewrite asserted the sentence rather than the data
    // and failed on a record that had always been there.
    assert_eq!(
        lengths.len(),
        4,
        "the batch holds four records: {lengths:?}"
    );
    for length in &lengths {
        assert_eq!(
            length % 4,
            0,
            "a record that is not four-byte aligned leaves the next one misaligned: \
             {lengths:?}"
        );
        assert!(
            *length >= 12,
            "a record shorter than its own header cannot be walked past: {lengths:?}"
        );
    }

    let mut zero_host = Host::new(zero_progress_app);
    let zero_batch = zero_host.rebuild().unwrap().to_vec();
    assert!(decode_batch(&zero_batch).unwrap().iter().any(|mutation| {
        matches!(
            mutation,
            Mutation::SetProp {
                property: PropertyKind::Progress,
                value: PropertyValue::Float(value),
                ..
            } if *value == 0.0
        )
    }));
}

#[test]
fn fr11_extension_is_in_the_closed_schema() {
    // Extension widget tags start at 100. Tags 1 to 29 are the core vocabulary, and an
    // extension inside that range collides with the next core widget that is added.
    assert_eq!(WidgetKind::LinearProgressIndicator as u16, 100);
    assert_eq!(PropertyKind::Progress as u16, 27);
    assert_eq!(
        WIDGET_SCHEMA.last().unwrap().name,
        "LinearProgressIndicator"
    );
    assert_eq!(PROPERTY_SCHEMA.last().unwrap().name, "Progress");
    assert_ne!(SCHEMA_HASH, 10_919_967_072_721_375_032);

    let mut invalid_widget_batch = vec![0, 0, 12, 0, 24, 0, 0, 0, 1, 0, 0, 0];
    invalid_widget_batch.extend_from_slice(&[1, 0, 12, 0, 1, 0, 0, 0, 0xff, 0xff, 0, 0]);
    assert_eq!(
        decode_batch(&invalid_widget_batch),
        Err(ProtocolError::InvalidWidget(u16::MAX))
    );

    let mut invalid_property_batch = vec![0, 0, 12, 0, 36, 0, 0, 0, 1, 0, 0, 0];
    invalid_property_batch.extend_from_slice(&[
        2, 0, 24, 0, 1, 0, 0, 0, 0xff, 0xff, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ]);
    assert_eq!(
        decode_batch(&invalid_property_batch),
        Err(ProtocolError::InvalidProperty(u16::MAX))
    );
}
