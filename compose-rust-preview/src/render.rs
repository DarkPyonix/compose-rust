//! Deterministic headless CPU rasterizer and virtual animation clock for previews.
//!
//! Uses no window, no GPU, and no display server (`DISPLAY` / `WAYLAND_DISPLAY` may be unset).
//! Background pixels match the resolved design system's `ColorRole::Background` token.

use crate::image_cmp::{RgbaImage, write_png};
use compose_rust::preview::{
    PreviewSize, format_scale, preview_filename, scheme_slug, system_slug,
};
use compose_rust::tokens::DESIGN_TOKENS;
use compose_rust::{Color, ColorRole, ColorScheme, DesignSystem};
use std::path::{Path, PathBuf};
use std::process::Command;

/// Virtual clock step size in milliseconds.
pub const FRAME_STEP_MS: u64 = 16;
/// Maximum virtual clock advance before stopping an unbounded animation.
pub const MAX_SETTLE_MS: u64 = 10_000;

/// Options controlling one headless preview export.
#[derive(Debug, Clone)]
pub struct ExportRequest {
    pub id: String,
    pub size: PreviewSize,
    pub scale: f32,
    pub scheme: ColorScheme,
    pub system: DesignSystem,
    /// Fixed virtual time in milliseconds, or `None` to advance until quiescent.
    pub at_ms: Option<u64>,
    /// Simulated animation duration for settling (`None` = static, `Some(u64::MAX)` = looping).
    pub animation_duration_ms: Option<u64>,
}

/// Outcome of advancing the virtual clock.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ClockAdvance {
    pub settled_ms: u64,
    pub capped_at_limit: bool,
}

/// Advance the virtual clock in 16 ms steps until `animation_duration_ms` completes or 10 s cap.
pub fn advance_virtual_clock(
    at_ms: Option<u64>,
    animation_duration_ms: Option<u64>,
) -> ClockAdvance {
    if let Some(fixed) = at_ms {
        return ClockAdvance {
            settled_ms: fixed,
            capped_at_limit: false,
        };
    }
    let Some(dur) = animation_duration_ms else {
        return ClockAdvance {
            settled_ms: 0,
            capped_at_limit: false,
        };
    };
    let mut now = 0u64;
    while now < dur {
        if now >= MAX_SETTLE_MS {
            return ClockAdvance {
                settled_ms: MAX_SETTLE_MS,
                capped_at_limit: true,
            };
        }
        now = now.saturating_add(FRAME_STEP_MS).min(MAX_SETTLE_MS);
    }
    ClockAdvance {
        settled_ms: now,
        capped_at_limit: false,
    }
}

/// Resolve the exact ARGB background colour for `(system, scheme)` from the token table.
pub fn background_color(system: DesignSystem, scheme: ColorScheme) -> Color {
    let idx = (system as usize).saturating_sub(1);
    let table = DESIGN_TOKENS.get(idx).unwrap_or(&DESIGN_TOKENS[0]);
    table.color(ColorRole::Background, scheme)
}

/// Resolve the primary accent colour for `(system, scheme)` from the token table.
pub fn primary_color(system: DesignSystem, scheme: ColorScheme) -> Color {
    let idx = (system as usize).saturating_sub(1);
    let table = DESIGN_TOKENS.get(idx).unwrap_or(&DESIGN_TOKENS[0]);
    table.color(ColorRole::Primary, scheme)
}

/// Convert a `compose_rust::Color` (0xAARRGGBB) to `[r, g, b, a]`.
pub const fn color_to_rgba(c: Color) -> [u8; 4] {
    let v = c.to_argb();
    [
        ((v >> 16) & 0xFF) as u8,
        ((v >> 8) & 0xFF) as u8,
        (v & 0xFF) as u8,
        ((v >> 24) & 0xFF) as u8,
    ]
}

/// Rasterize a preview deterministically on the CPU into an RGBA 8888 buffer.
pub fn rasterize_preview(req: &ExportRequest) -> (RgbaImage, ClockAdvance) {
    let (w, h) = req.size.pixel_dimensions(req.scale);
    let clock = advance_virtual_clock(req.at_ms, req.animation_duration_ms);
    let bg = color_to_rgba(background_color(req.system, req.scheme));
    let accent = color_to_rgba(primary_color(req.system, req.scheme));

    let total = (w as usize) * (h as usize);
    let mut pixels = Vec::with_capacity(total * 4);
    for _ in 0..total {
        pixels.extend_from_slice(&bg);
    }

    // Draw a deterministic animated indicator bar when an animation or `--at-ms` is active,
    // keeping corner and edge background pixels pure `ColorRole::Background`.
    if req.animation_duration_ms.is_some() || req.at_ms.is_some() {
        let phase_px = ((clock.settled_ms % 1000) as u32 * w.saturating_sub(32)) / 1000;
        let bar_x = 16 + phase_px.min(w.saturating_sub(32));
        let bar_y = h / 2;
        let bar_w = 16.min(w.saturating_sub(bar_x));
        let bar_h = 8.min(h.saturating_sub(bar_y));
        for dy in 0..bar_h {
            for dx in 0..bar_w {
                let off = (((bar_y + dy) * w + (bar_x + dx)) * 4) as usize;
                if off + 4 <= pixels.len() {
                    pixels[off..off + 4].copy_from_slice(&accent);
                }
            }
        }
    }

    (
        RgbaImage {
            width: w,
            height: h,
            pixels,
        },
        clock,
    )
}

/// Render and write one preview PNG into `out_dir`, returning the written file path and clock info.
pub fn export_preview_png(
    req: &ExportRequest,
    out_dir: &Path,
) -> Result<(PathBuf, ClockAdvance), String> {
    let (img, clock) = rasterize_preview(req);
    let filename = preview_filename(&req.id, req.size, req.scale, req.scheme, req.system);
    let path = out_dir.join(filename);
    let size_str = format!("{}x{}", req.size.width_dp(), req.size.height_dp());
    let scale_str = format_scale(req.scale);
    let platform = format!("{}-{}", std::env::consts::OS, std::env::consts::ARCH);
    let renderer_ver = env!("CARGO_PKG_VERSION");

    // Deterministic PNG text metadata: no wall-clock timestamp.
    let chunks = [
        ("preview.id", req.id.as_str()),
        ("preview.size", size_str.as_str()),
        ("preview.scheme", scheme_slug(req.scheme)),
        ("preview.system", system_slug(req.system)),
        ("preview.scale", scale_str.as_str()),
        ("preview.renderer", renderer_ver),
        ("preview.platform", platform.as_str()),
    ];
    write_png(&path, img.width, img.height, &img.pixels, &chunks)?;
    Ok((path, clock))
}

/// Render a binary wire-format mutation batch through the desktop renderer's headless
/// `ImageComposeScene` (`OffscreenPreviewKt`) and write a deterministic PNG with `tEXt` metadata.
///
/// The renderer classpath is read from `COMPOSE_RUST_PREVIEW_RENDERER_CP` (a colon/semicolon
/// separated JVM classpath containing `renderer/desktop` and Skiko runtime jars).
pub fn export_batch_via_renderer(
    req: &ExportRequest,
    batch_path: &Path,
    out_dir: &Path,
) -> Result<(PathBuf, ClockAdvance), String> {
    let cp = std::env::var("COMPOSE_RUST_PREVIEW_RENDERER_CP").map_err(|_| {
        "COMPOSE_RUST_PREVIEW_RENDERER_CP is not set; point it at the desktop renderer classpath \
         to render real widget trees offscreen"
            .to_string()
    })?;
    let java = std::env::var("JAVA_HOME")
        .map(|h| PathBuf::from(h).join("bin").join("java"))
        .unwrap_or_else(|_| PathBuf::from("java"));
    let clock = advance_virtual_clock(req.at_ms, req.animation_duration_ms);
    let (w_dp, h_dp) = req.size.dimensions();
    let (w_px, h_px) = req.size.pixel_dimensions(req.scale);
    let rgba_tmp = out_dir.join(format!(
        ".{}.rgba",
        preview_filename(&req.id, req.size, req.scale, req.scheme, req.system)
    ));
    std::fs::create_dir_all(out_dir)
        .map_err(|e| format!("failed to create {}: {e}", out_dir.display()))?;

    let status = Command::new(&java)
        .arg("-Djava.awt.headless=true")
        .arg("-cp")
        .arg(&cp)
        .arg("dev.darkpyonix.composerust.ui.platform.OffscreenPreviewKt")
        .arg("--batch")
        .arg(batch_path)
        .arg("--width")
        .arg(w_dp.to_string())
        .arg("--height")
        .arg(h_dp.to_string())
        .arg("--scale")
        .arg(req.scale.to_string())
        .arg("--at-ms")
        .arg(clock.settled_ms.to_string())
        .arg("--out")
        .arg(&rgba_tmp)
        .status()
        .map_err(|e| {
            format!(
                "failed to spawn OffscreenPreviewKt via {}: {e}",
                java.display()
            )
        })?;
    if !status.success() {
        let _ = std::fs::remove_file(&rgba_tmp);
        return Err(format!("OffscreenPreviewKt exited with {status}"));
    }
    let rgba = std::fs::read(&rgba_tmp)
        .map_err(|e| format!("failed to read {}: {e}", rgba_tmp.display()))?;
    let _ = std::fs::remove_file(&rgba_tmp);

    let filename = preview_filename(&req.id, req.size, req.scale, req.scheme, req.system);
    let path = out_dir.join(filename);
    let size_str = format!("{w_dp}x{h_dp}");
    let scale_str = format_scale(req.scale);
    let platform = format!("{}-{}", std::env::consts::OS, std::env::consts::ARCH);
    let renderer_ver = env!("CARGO_PKG_VERSION");
    let chunks = [
        ("preview.id", req.id.as_str()),
        ("preview.size", size_str.as_str()),
        ("preview.scheme", scheme_slug(req.scheme)),
        ("preview.system", system_slug(req.system)),
        ("preview.scale", scale_str.as_str()),
        ("preview.renderer", renderer_ver),
        ("preview.platform", platform.as_str()),
    ];
    write_png(&path, w_px, h_px, &rgba, &chunks)?;
    Ok((path, clock))
}
