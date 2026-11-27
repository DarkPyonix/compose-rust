//! Tests for the `#[Preview]` attribute macro and `compose_rust::preview` runtime types.

use compose_rust::preview::{DEFAULT_PREVIEW_NOW_UNIX_SECS, format_scale, preview_filename};
use compose_rust::{
    ColorScheme, DesignSystem, Preview, PreviewContext, PreviewSize, WindowHeightClass,
    WindowSizeClass,
};

#[Preview]
fn default_preview(ctx: &PreviewContext) {
    assert_eq!(ctx.width_dp(), 1440);
    assert_eq!(ctx.height_dp(), 900);
    assert_eq!(ctx.scheme(), ColorScheme::Light);
    assert_eq!(ctx.scale(), 1.0);
    assert_eq!(ctx.fixture(), None);
    assert_eq!(ctx.now_unix_secs(), DEFAULT_PREVIEW_NOW_UNIX_SECS);
}

#[Preview(
    name = "Counter Matrix",
    sizes = [Desktop, Laptop, Tablet, Phone, "800x600"],
    schemes = [Light, Dark],
    systems = [Material3, Cupertino, Fluent],
    scales = [1.0, 2.0],
    fixture = "demo"
)]
fn matrix_preview(ctx: &PreviewContext) {
    assert_eq!(ctx.fixture(), Some("demo"));
    assert!(ctx.width_dp() > 0 && ctx.height_dp() > 0);
}

#[test]
fn fr54_release_build_has_no_preview_registry() {
    // Calling the annotated functions directly works in normal builds.
    let ctx = PreviewContext::new(
        PreviewSize::Desktop,
        ColorScheme::Light,
        DesignSystem::Material3,
        1.0,
        None,
    );
    default_preview(&ctx);

    let matrix_ctx = PreviewContext::new(
        PreviewSize::Phone,
        ColorScheme::Dark,
        DesignSystem::Cupertino,
        2.0,
        Some("demo"),
    );
    matrix_preview(&matrix_ctx);

    // Without `--cfg compose_rust_preview`, the macro must NOT emit `__COMPOSE_PREVIEW_*`.
    // We verify this by checking that the cfg flag itself is unset in normal test/release builds.
    #[allow(unexpected_cfgs)]
    let preview_cfg_active = cfg!(compose_rust_preview);
    assert!(
        !preview_cfg_active,
        "normal builds must not enable compose_rust_preview registry emission"
    );
}

#[test]
fn fr54_preview_sizes_and_classes_match_spec() {
    assert_eq!(PreviewSize::Desktop.dimensions(), (1440, 900));
    assert_eq!(PreviewSize::Laptop.dimensions(), (1024, 768));
    assert_eq!(PreviewSize::Tablet.dimensions(), (768, 1024));
    assert_eq!(PreviewSize::Phone.dimensions(), (390, 844));
    assert_eq!(PreviewSize::Custom(800, 600).dimensions(), (800, 600));

    assert_eq!(PreviewSize::Desktop.pixel_dimensions(2.0), (2880, 1800));
    assert_eq!(PreviewSize::Phone.pixel_dimensions(1.0), (390, 844));
    assert_eq!(PreviewSize::Desktop.size_class(), WindowSizeClass::Expanded);
    assert_eq!(PreviewSize::Tablet.size_class(), WindowSizeClass::Medium);
    assert_eq!(PreviewSize::Phone.size_class(), WindowSizeClass::Compact);
    assert_eq!(
        PreviewSize::Desktop.height_class(),
        WindowHeightClass::Expanded
    );
    assert_eq!(
        PreviewSize::Laptop.height_class(),
        WindowHeightClass::Medium
    );

    assert_eq!(PreviewSize::parse("Desktop").unwrap(), PreviewSize::Desktop);
    assert_eq!(PreviewSize::parse("phone").unwrap(), PreviewSize::Phone);
    assert_eq!(
        PreviewSize::parse("800x600").unwrap(),
        PreviewSize::Custom(800, 600)
    );
    assert!(PreviewSize::parse("0x600").is_err());
    assert!(PreviewSize::parse("bad").is_err());
}

#[test]
fn fr54_preview_context_is_deterministic_and_formats_filenames() {
    let a = PreviewContext::new(
        PreviewSize::Desktop,
        ColorScheme::Light,
        DesignSystem::Material3,
        1.0,
        Some("demo"),
    );
    let b = PreviewContext::new(
        PreviewSize::Desktop,
        ColorScheme::Light,
        DesignSystem::Material3,
        1.0,
        Some("demo"),
    );
    assert_eq!(a.now_unix_secs(), 1_767_225_600);
    assert_eq!(a.seed(), b.seed());
    assert_eq!(a.next_u64(), b.next_u64());
    assert_eq!(a.next_u64(), b.next_u64());

    assert_eq!(format_scale(1.0), "1");
    assert_eq!(format_scale(2.0), "2");
    assert_eq!(format_scale(1.5), "1.5");
    assert_eq!(
        preview_filename(
            "ember_app::ui::home::home_preview",
            PreviewSize::Desktop,
            2.0,
            ColorScheme::Dark,
            DesignSystem::Cupertino,
        ),
        "ember_app.ui.home.home_preview@1440x900@2x-dark-cupertino.png"
    );
}
