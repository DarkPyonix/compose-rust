//! Overlay theme tokens and node geometry overrides.

use compose_rust::protocol::{BatchEncoder, Mutation, decode_batch};
use compose_rust::schema::{DesignSystem, OverlayTokens, PropertyKind, Theme};

#[test]
fn fr74_overlay_tokens_command_round_trips() {
    let tokens = OverlayTokens::new()
        .sheet_corner_radius(24)
        .dialog_corner_radius(16)
        .popup_corner_radius(12)
        .sheet_elevation(8)
        .dialog_elevation(6)
        .popup_elevation(4)
        .sheet_padding(20)
        .dialog_padding(16)
        .popup_padding(8)
        .scrim_argb(0x80000000);

    let mut encoder = BatchEncoder::default();
    encoder.encode(&Mutation::SetOverlayTokens(tokens)).unwrap();
    let bytes = encoder.finish().unwrap();

    let decoded = decode_batch(bytes).unwrap();
    assert_eq!(decoded.len(), 1);
    match &decoded[0] {
        Mutation::SetOverlayTokens(actual) => {
            assert_eq!(*actual, tokens);
            assert_eq!(actual.sheet_corner_radius, 24);
            assert_eq!(actual.dialog_corner_radius, 16);
            assert_eq!(actual.popup_corner_radius, 12);
            assert_eq!(actual.sheet_elevation, 8);
            assert_eq!(actual.dialog_elevation, 6);
            assert_eq!(actual.popup_elevation, 4);
            assert_eq!(actual.sheet_padding, 20);
            assert_eq!(actual.dialog_padding, 16);
            assert_eq!(actual.popup_padding, 8);
            assert_eq!(actual.scrim, 0x80000000);
        }
        other => panic!("expected SetOverlayTokens, got {other:?}"),
    }
}

#[test]
fn fr74_theme_builder_attaches_overlay_tokens() {
    static TOKENS: OverlayTokens = OverlayTokens {
        sheet_corner_radius: 24,
        dialog_corner_radius: 16,
        popup_corner_radius: 12,
        sheet_elevation: 8,
        popup_elevation: 4,
        dialog_elevation: 6,
        sheet_padding: 20,
        dialog_padding: 16,
        popup_padding: 8,
        scrim: 0x66000000,
        toast_elevation: 8,
        toast_corner_radius: 12,
    };

    let theme = Theme::unified(DesignSystem::Cupertino).with_overlay_tokens(&TOKENS);
    assert_eq!(theme.overlay, Some(&TOKENS));
}

#[test]
fn fr74_property_kind_scrim_has_correct_wire_tag() {
    assert_eq!(PropertyKind::Scrim as u16, 128);
    assert_eq!(
        PropertyKind::from_name("scrim").unwrap(),
        PropertyKind::Scrim
    );
}

#[test]
fn fr74_toast_tokens_round_trip_after_the_older_fields() {
    let tokens = OverlayTokens::new()
        .sheet_corner_radius(24)
        .popup_padding(8)
        .scrim_argb(0x8000_0000)
        .toast_elevation(8)
        .toast_corner_radius(12);

    let mut encoder = BatchEncoder::default();
    encoder.encode(&Mutation::SetOverlayTokens(tokens)).unwrap();
    let bytes = encoder.finish().unwrap();

    // The batch header, then the record: its tag and length, then the payload. The older
    // fields keep their offsets and the two message fields follow the scrim.
    let record = &bytes[bytes.len() - 32..];
    assert_eq!(u16::from_le_bytes([record[0], record[1]]), 27);
    assert_eq!(u16::from_le_bytes([record[2], record[3]]), 32);
    let payload = &record[4..];
    let u16_at = |at: usize| u16::from_le_bytes([payload[at], payload[at + 1]]);
    assert_eq!(u16_at(0), 24, "sheet_corner_radius stays first");
    assert_eq!(u16_at(16), 8, "popup_padding stays where it was");
    assert_eq!(
        u32::from_le_bytes([payload[20], payload[21], payload[22], payload[23]]),
        0x8000_0000,
        "scrim stays where it was"
    );
    assert_eq!(u16_at(24), 8, "toast_elevation follows the scrim");
    assert_eq!(
        u16_at(26),
        12,
        "toast_corner_radius follows toast_elevation"
    );

    let decoded = decode_batch(bytes).unwrap();
    match &decoded[..] {
        [Mutation::SetOverlayTokens(actual)] => {
            assert_eq!(*actual, tokens);
            assert_eq!(actual.toast_elevation, 8);
            assert_eq!(actual.toast_corner_radius, 12);
        }
        other => panic!("expected one SetOverlayTokens, got {other:?}"),
    }
}

#[test]
fn fr74_toast_tokens_default_to_the_design_system() {
    let tokens = OverlayTokens::new();
    assert_eq!(tokens.toast_elevation, 0);
    assert_eq!(tokens.toast_corner_radius, 0);
    assert_eq!(OverlayTokens::default(), tokens);
}

#[test]
fn fr74_old_length_overlay_tokens_record_is_refused() {
    // A record of the length before the message fields were appended is a record from a
    // different schema, and decoding it is an error rather than a guess.
    let tokens = OverlayTokens::new().toast_elevation(8);
    let mut encoder = BatchEncoder::default();
    encoder.encode(&Mutation::SetOverlayTokens(tokens)).unwrap();
    let mut bytes = encoder.finish().unwrap().to_vec();
    let at = bytes.len() - 32;
    bytes[at + 2..at + 4].copy_from_slice(&28_u16.to_le_bytes());
    bytes.truncate(bytes.len() - 4);
    let records_len = u32::from_le_bytes(bytes[4..8].try_into().unwrap()) - 4;
    bytes[4..8].copy_from_slice(&records_len.to_le_bytes());
    assert_eq!(
        decode_batch(&bytes).unwrap_err(),
        compose_rust::protocol::ProtocolError::InvalidRecordLength
    );
}
