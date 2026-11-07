//! Overlay theme tokens and node geometry overrides.

use compose_rust::protocol::{BatchEncoder, Mutation, decode_batch};
use compose_rust::schema::{Color, DesignSystem, OverlayTokens, PropertyKind, Theme};

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

    let mut encoder = BatchEncoder::new();
    encoder.encode(&Mutation::SetOverlayTokens(tokens)).unwrap();
    let bytes = encoder.finish();

    let decoded = decode_batch(&bytes).unwrap();
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
