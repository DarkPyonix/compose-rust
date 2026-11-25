//! End-to-end tests for `compose-rust-preview` discovery, deterministic PNG export,
//! virtual clock stepping, headless/offline operation, and `--compare`/`--check`.

use compose_rust::preview::PreviewSize;
use compose_rust::{ColorScheme, DesignSystem};
use compose_rust_preview::discover::{discover_in_crate, matches_glob};
use compose_rust_preview::image_cmp::{compare_pngs, read_png_rgba, write_png};
use compose_rust_preview::render::{
    ExportRequest, MAX_SETTLE_MS, background_color, color_to_rgba, export_preview_png,
};
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

fn temp_dir(tag: &str) -> PathBuf {
    static NEXT: AtomicU64 = AtomicU64::new(1);
    let n = NEXT.fetch_add(1, Ordering::Relaxed);
    let dir = std::env::temp_dir().join(format!(
        "compose-rust-preview-test-{}-{}-{n}",
        std::process::id(),
        tag
    ));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dir
}

#[test]
fn fr54_export_size_follows_size_and_scale() {
    let out = temp_dir("sizes");
    let cases = [
        (PreviewSize::Desktop, 1.0, (1440, 900)),
        (PreviewSize::Desktop, 2.0, (2880, 1800)),
        (PreviewSize::Laptop, 1.0, (1024, 768)),
        (PreviewSize::Tablet, 1.0, (768, 1024)),
        (PreviewSize::Phone, 1.0, (390, 844)),
        (PreviewSize::Custom(640, 480), 2.0, (1280, 960)),
    ];
    for (size, scale, (ew, eh)) in cases {
        let req = ExportRequest {
            id: "demo::ui::home".to_string(),
            size,
            scale,
            scheme: ColorScheme::Light,
            system: DesignSystem::Material3,
            at_ms: None,
            animation_duration_ms: None,
        };
        let (path, _) = export_preview_png(&req, &out).unwrap();
        let img = read_png_rgba(&path).unwrap();
        assert_eq!((img.width, img.height), (ew, eh));
    }
    let _ = fs::remove_dir_all(&out);
}

#[test]
fn fr54_export_scheme_and_system_flags() {
    let out = temp_dir("scheme_system");
    let light_req = ExportRequest {
        id: "demo::ui::card".to_string(),
        size: PreviewSize::Phone,
        scale: 1.0,
        scheme: ColorScheme::Light,
        system: DesignSystem::Material3,
        at_ms: None,
        animation_duration_ms: None,
    };
    let dark_req = ExportRequest {
        scheme: ColorScheme::Dark,
        ..light_req.clone()
    };
    let cupertino_dark = ExportRequest {
        system: DesignSystem::Cupertino,
        ..dark_req.clone()
    };

    let (lp, _) = export_preview_png(&light_req, &out).unwrap();
    let (dp, _) = export_preview_png(&dark_req, &out).unwrap();
    let (cp, _) = export_preview_png(&cupertino_dark, &out).unwrap();

    let l_bytes = fs::read(&lp).unwrap();
    let d_bytes = fs::read(&dp).unwrap();
    assert_ne!(l_bytes, d_bytes, "light and dark PNGs must differ");

    let l_img = read_png_rgba(&lp).unwrap();
    let d_img = read_png_rgba(&dp).unwrap();
    let c_img = read_png_rgba(&cp).unwrap();

    assert_eq!(
        &l_img.pixels[0..4],
        &color_to_rgba(background_color(
            DesignSystem::Material3,
            ColorScheme::Light
        ))
    );
    assert_eq!(
        &d_img.pixels[0..4],
        &color_to_rgba(background_color(DesignSystem::Material3, ColorScheme::Dark))
    );
    assert_eq!(
        &c_img.pixels[0..4],
        &color_to_rgba(background_color(DesignSystem::Cupertino, ColorScheme::Dark))
    );
    let _ = fs::remove_dir_all(&out);
}

#[test]
fn fr54_export_is_byte_identical() {
    let out1 = temp_dir("det1");
    let out2 = temp_dir("det2");
    let req = ExportRequest {
        id: "demo::ui::home".to_string(),
        size: PreviewSize::Desktop,
        scale: 1.0,
        scheme: ColorScheme::Dark,
        system: DesignSystem::Fluent,
        at_ms: None,
        animation_duration_ms: None,
    };
    let (p1, _) = export_preview_png(&req, &out1).unwrap();
    let (p2, _) = export_preview_png(&req, &out2).unwrap();
    assert_eq!(fs::read(&p1).unwrap(), fs::read(&p2).unwrap());
    let _ = fs::remove_dir_all(&out1);
    let _ = fs::remove_dir_all(&out2);
}

#[test]
fn fr54_export_uses_a_virtual_clock() {
    let out = temp_dir("clock");
    let looping = ExportRequest {
        id: "demo::ui::spinner".to_string(),
        size: PreviewSize::Phone,
        scale: 1.0,
        scheme: ColorScheme::Light,
        system: DesignSystem::Material3,
        at_ms: None,
        animation_duration_ms: Some(u64::MAX),
    };
    let (a, clk_a) = export_preview_png(&looping, &out).unwrap();
    assert!(clk_a.capped_at_limit);
    assert_eq!(clk_a.settled_ms, MAX_SETTLE_MS);
    let bytes_a = fs::read(&a).unwrap();

    let (b, _) = export_preview_png(&looping, &out).unwrap();
    assert_eq!(
        bytes_a,
        fs::read(&b).unwrap(),
        "looping animation must settle deterministically"
    );

    let at_250 = ExportRequest {
        at_ms: Some(250),
        ..looping.clone()
    };
    let at_500 = ExportRequest {
        at_ms: Some(500),
        ..looping
    };
    let (p250_a, _) = export_preview_png(&at_250, &out).unwrap();
    let b250_a = fs::read(&p250_a).unwrap();
    let (p250_b, _) = export_preview_png(&at_250, &out).unwrap();
    assert_eq!(b250_a, fs::read(&p250_b).unwrap());

    let (p500_a, _) = export_preview_png(&at_500, &out).unwrap();
    let b500_a = fs::read(&p500_a).unwrap();
    let (p500_b, _) = export_preview_png(&at_500, &out).unwrap();
    assert_eq!(b500_a, fs::read(&p500_b).unwrap());
    assert_ne!(b250_a, b500_a, "--at-ms 250 and --at-ms 500 must differ");
    let _ = fs::remove_dir_all(&out);
}

#[test]
fn fr54_export_runs_without_a_display() {
    // Runs via CPU rasterization with no DISPLAY / WAYLAND_DISPLAY / GPU dependency.
    let out = temp_dir("headless");
    let req = ExportRequest {
        id: "demo::ui::headless".to_string(),
        size: PreviewSize::Laptop,
        scale: 1.0,
        scheme: ColorScheme::Light,
        system: DesignSystem::Gnome,
        at_ms: None,
        animation_duration_ms: None,
    };
    let (p, _) = export_preview_png(&req, &out).unwrap();
    assert!(p.is_file());
    let _ = fs::remove_dir_all(&out);
}

#[test]
fn fr54_preview_runs_without_network() {
    // Discovery + export operate purely on local source files and local token tables.
    let root = temp_dir("offline_crate");
    fs::create_dir_all(root.join("src")).unwrap();
    fs::write(
        root.join("src/lib.rs"),
        r#"
            #[Preview(name = "Home", sizes = [Desktop, Phone], schemes = [Light, Dark])]
            fn home_preview(ctx: &PreviewContext) {}
        "#,
    )
    .unwrap();
    let found = discover_in_crate(&root, "offline-app").unwrap();
    assert_eq!(found.len(), 1);
    assert_eq!(found[0].id, "offline_app::home_preview");
    assert_eq!(found[0].name, "Home");
    assert!(matches_glob("offline_app::*", &found[0].id));
    let _ = fs::remove_dir_all(&root);
}

#[test]
fn fr54_compare_and_check() {
    let dir = temp_dir("compare");
    let a = dir.join("a.png");
    let b = dir.join("b.png");
    let c = dir.join("c.png");
    let side = dir.join("a.side.png");
    let diff = dir.join("a.diff.png");

    let mut px_a = vec![100u8; 4 * 4 * 4];
    for i in 0..16 {
        px_a[i * 4 + 3] = 255;
    }
    let mut px_b = px_a.clone();
    px_b[0] = 200; // 1 pixel differs out of 16

    write_png(&a, 4, 4, &px_a, &[]).unwrap();
    write_png(&b, 4, 4, &px_b, &[]).unwrap();
    write_png(&c, 8, 4, &[255u8; 8 * 4 * 4], &[]).unwrap();

    // Same size, 1 pixel differs: writes .side.png (8x4) and .diff.png (4x4).
    let res = compare_pngs(&a, &b, Some(&side), Some(&diff)).unwrap();
    assert_eq!(res.diff_pixels, 1);
    assert_eq!(res.total_pixels, 16);
    let side_img = read_png_rgba(&side).unwrap();
    assert_eq!((side_img.width, side_img.height), (8, 4));
    assert!(diff.is_file());

    // Size mismatch: fails naming both dimensions without scaling.
    let err = compare_pngs(&a, &c, None, None).unwrap_err();
    assert!(
        err.contains("4x4") && err.contains("8x4"),
        "unexpected error: {err}"
    );
    let _ = fs::remove_dir_all(&dir);
}
