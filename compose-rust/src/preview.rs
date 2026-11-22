//! Preview declarations and deterministic context for screen previews and PNG export.
//!
//! A preview function takes `&PreviewContext` and emits UI with fixed logical size, colour
//! scheme, design system, pixel scale, fixture name, timestamp and random seed so repeated
//! renders produce identical frames.

use crate::schema::{ColorScheme, DesignSystem, WindowHeightClass, WindowSizeClass};
use std::sync::atomic::{AtomicU64, Ordering};

/// Default preview timestamp: `2026-01-01T00:00:00Z` in Unix seconds.
pub const DEFAULT_PREVIEW_NOW_UNIX_SECS: i64 = 1_767_225_600;

/// Default preview timestamp formatted as RFC 3339 / ISO 8601 UTC.
pub const DEFAULT_PREVIEW_NOW_ISO: &str = "2026-01-01T00:00:00Z";

/// Logical viewport size (in dp) for a preview.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum PreviewSize {
    /// 1440 x 900 dp.
    Desktop,
    /// 1024 x 768 dp.
    Laptop,
    /// 768 x 1024 dp.
    Tablet,
    /// 390 x 844 dp.
    Phone,
    /// Arbitrary logical width and height in dp.
    Custom(u32, u32),
}

impl PreviewSize {
    /// Logical `(width_dp, height_dp)`.
    pub const fn dimensions(self) -> (u32, u32) {
        match self {
            Self::Desktop => (1440, 900),
            Self::Laptop => (1024, 768),
            Self::Tablet => (768, 1024),
            Self::Phone => (390, 844),
            Self::Custom(w, h) => (w, h),
        }
    }

    /// Logical width in dp.
    pub const fn width_dp(self) -> u32 {
        self.dimensions().0
    }

    /// Logical height in dp.
    pub const fn height_dp(self) -> u32 {
        self.dimensions().1
    }

    /// Physical pixel dimensions `(width_px, height_px)` at `scale`.
    pub fn pixel_dimensions(self, scale: f32) -> (u32, u32) {
        let (w, h) = self.dimensions();
        let s = if scale.is_finite() && scale > 0.0 {
            scale
        } else {
            1.0
        };
        (
            ((w as f32) * s).round().max(1.0) as u32,
            ((h as f32) * s).round().max(1.0) as u32,
        )
    }

    /// Horizontal window size class derived from the logical width.
    pub fn size_class(self) -> WindowSizeClass {
        WindowSizeClass::from_width_dp(self.width_dp() as f32)
    }

    /// Vertical window size class derived from the logical height.
    pub fn height_class(self) -> WindowHeightClass {
        WindowHeightClass::from_height_dp(self.height_dp() as f32)
    }

    /// Parse a named size (`Desktop`, `Laptop`, `Tablet`, `Phone`, case-insensitive) or `WxH`.
    pub fn parse(input: &str) -> Result<Self, String> {
        let trimmed = input.trim();
        match trimmed.to_ascii_lowercase().as_str() {
            "desktop" => return Ok(Self::Desktop),
            "laptop" => return Ok(Self::Laptop),
            "tablet" => return Ok(Self::Tablet),
            "phone" => return Ok(Self::Phone),
            _ => {}
        }
        let lower = trimmed.to_ascii_lowercase();
        let (w_str, h_str) = lower.split_once('x').ok_or_else(|| {
            format!(
                "invalid preview size '{trimmed}': expected Desktop, Laptop, Tablet, Phone, or WxH"
            )
        })?;
        let w: u32 = w_str
            .parse()
            .map_err(|_| format!("invalid preview width '{w_str}' in '{trimmed}'"))?;
        let h: u32 = h_str
            .parse()
            .map_err(|_| format!("invalid preview height '{h_str}' in '{trimmed}'"))?;
        if w == 0 || h == 0 {
            return Err(format!("preview size must be non-zero, got '{trimmed}'"));
        }
        Ok(Self::Custom(w, h))
    }
}

/// Deterministic context handed to every `#[Preview]` function.
#[derive(Debug)]
pub struct PreviewContext {
    size: PreviewSize,
    scheme: ColorScheme,
    system: DesignSystem,
    scale: f32,
    fixture: Option<&'static str>,
    now_unix_secs: i64,
    seed: u64,
    rng_state: AtomicU64,
}

impl PreviewContext {
    /// Build a deterministic preview context with default timestamp (`2026-01-01T00:00:00Z`).
    pub fn new(
        size: PreviewSize,
        scheme: ColorScheme,
        system: DesignSystem,
        scale: f32,
        fixture: Option<&'static str>,
    ) -> Self {
        let seed = default_seed(size, scheme, system, scale, fixture);
        Self {
            size,
            scheme,
            system,
            scale: if scale.is_finite() && scale > 0.0 {
                scale
            } else {
                1.0
            },
            fixture,
            now_unix_secs: DEFAULT_PREVIEW_NOW_UNIX_SECS,
            seed,
            rng_state: AtomicU64::new(if seed == 0 {
                0x9e37_79b9_7f4a_7c15
            } else {
                seed
            }),
        }
    }

    /// Override the fixed Unix timestamp in seconds (e.g. from `--now`).
    pub fn set_now_unix_secs(&mut self, unix_secs: i64) {
        self.now_unix_secs = unix_secs;
    }

    /// Override the deterministic seed.
    pub fn set_seed(&mut self, seed: u64) {
        self.seed = seed;
        self.rng_state.store(
            if seed == 0 {
                0x9e37_79b9_7f4a_7c15
            } else {
                seed
            },
            Ordering::Relaxed,
        );
    }

    /// Logical preview size.
    pub const fn size(&self) -> PreviewSize {
        self.size
    }

    /// Logical width in dp.
    pub const fn width_dp(&self) -> u32 {
        self.size.width_dp()
    }

    /// Logical height in dp.
    pub const fn height_dp(&self) -> u32 {
        self.size.height_dp()
    }

    /// Window width size class.
    pub fn size_class(&self) -> WindowSizeClass {
        self.size.size_class()
    }

    /// Window height size class.
    pub fn height_class(&self) -> WindowHeightClass {
        self.size.height_class()
    }

    /// Active colour scheme.
    pub const fn scheme(&self) -> ColorScheme {
        self.scheme
    }

    /// Active design system.
    pub const fn system(&self) -> DesignSystem {
        self.system
    }

    /// Pixel scale factor.
    pub const fn scale(&self) -> f32 {
        self.scale
    }

    /// Optional fixture name declared on the preview attribute.
    pub const fn fixture(&self) -> Option<&'static str> {
        self.fixture
    }

    /// Fixed current time in Unix seconds (default `2026-01-01T00:00:00Z`).
    pub const fn now_unix_secs(&self) -> i64 {
        self.now_unix_secs
    }

    /// Fixed current time in Unix milliseconds.
    pub const fn now_unix_ms(&self) -> i64 {
        self.now_unix_secs.saturating_mul(1000)
    }

    /// Fixed random seed for this preview variant.
    pub const fn seed(&self) -> u64 {
        self.seed
    }

    /// Next deterministic pseudo-random `u64` from this context's fixed seed (xorshift64*).
    pub fn next_u64(&self) -> u64 {
        let mut x = self.rng_state.load(Ordering::Relaxed);
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.rng_state.store(x, Ordering::Relaxed);
        x.wrapping_mul(0x2545_f491_4f6c_dd1d)
    }
}

fn default_seed(
    size: PreviewSize,
    scheme: ColorScheme,
    system: DesignSystem,
    scale: f32,
    fixture: Option<&str>,
) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    let mix = |h: &mut u64, bytes: &[u8]| {
        for &b in bytes {
            *h ^= u64::from(b);
            *h = h.wrapping_mul(0x0100_0000_01b3);
        }
    };
    let (w, ht) = size.dimensions();
    mix(&mut h, &w.to_le_bytes());
    mix(&mut h, &ht.to_le_bytes());
    mix(&mut h, scheme_slug(scheme).as_bytes());
    mix(&mut h, system_slug(system).as_bytes());
    mix(&mut h, &scale.to_bits().to_le_bytes());
    if let Some(f) = fixture {
        mix(&mut h, f.as_bytes());
    }
    h
}

/// One concrete combination from a [`PreviewSpec`]'s configuration matrix.
#[derive(Debug, Clone, PartialEq)]
pub struct PreviewVariant {
    pub id: &'static str,
    pub name: &'static str,
    pub size: PreviewSize,
    pub scheme: ColorScheme,
    pub system: DesignSystem,
    pub scale: f32,
    pub fixture: Option<&'static str>,
}

impl PreviewVariant {
    /// Canonical output PNG filename: `<id>@<W>x<H>@<scale>x-<scheme>-<system>.png` (`::` -> `.`).
    pub fn filename(&self) -> String {
        preview_filename(self.id, self.size, self.scale, self.scheme, self.system)
    }

    /// Construct a [`PreviewContext`] for this variant.
    pub fn context(&self) -> PreviewContext {
        PreviewContext::new(
            self.size,
            self.scheme,
            self.system,
            self.scale,
            self.fixture,
        )
    }
}

/// Metadata and entry point for one `#[Preview]` function.
///
/// Only emitted when built with `--cfg compose_rust_preview`; normal and release builds
/// carry no preview descriptors.
#[derive(Clone, Copy)]
pub struct PreviewSpec {
    pub id: &'static str,
    pub name: &'static str,
    pub sizes: &'static [PreviewSize],
    pub schemes: &'static [ColorScheme],
    pub systems: &'static [DesignSystem],
    pub scales: &'static [f32],
    pub fixture: Option<&'static str>,
    pub render: fn(&PreviewContext),
}

impl PreviewSpec {
    /// Expand the declaration matrix into concrete variants, substituting `default_system`
    /// when `systems` is empty.
    pub fn matrix(&self, default_system: DesignSystem) -> Vec<PreviewVariant> {
        let sizes = if self.sizes.is_empty() {
            &[PreviewSize::Desktop][..]
        } else {
            self.sizes
        };
        let schemes = if self.schemes.is_empty() {
            &[ColorScheme::Light][..]
        } else {
            self.schemes
        };
        let systems_fallback = [default_system];
        let systems = if self.systems.is_empty() {
            &systems_fallback[..]
        } else {
            self.systems
        };
        let scales = if self.scales.is_empty() {
            &[1.0_f32][..]
        } else {
            self.scales
        };

        let mut out =
            Vec::with_capacity(sizes.len() * schemes.len() * systems.len() * scales.len());
        for &size in sizes {
            for &scale in scales {
                for &scheme in schemes {
                    for &system in systems {
                        out.push(PreviewVariant {
                            id: self.id,
                            name: self.name,
                            size,
                            scheme,
                            system,
                            scale,
                            fixture: self.fixture,
                        });
                    }
                }
            }
        }
        out
    }

    /// Canonical PNG filename for one variant of this preview.
    pub fn filename(
        &self,
        size: PreviewSize,
        scale: f32,
        scheme: ColorScheme,
        system: DesignSystem,
    ) -> String {
        preview_filename(self.id, size, scale, scheme, system)
    }
}

/// Format the canonical PNG filename: `<id>@<W>x<H>@<scale>x-<scheme>-<system>.png` (`::` -> `.`).
pub fn preview_filename(
    id: &str,
    size: PreviewSize,
    scale: f32,
    scheme: ColorScheme,
    system: DesignSystem,
) -> String {
    let dotted = id.replace("::", ".");
    let (w, h) = size.dimensions();
    let scale_str = format_scale(scale);
    format!(
        "{dotted}@{w}x{h}@{scale_str}x-{}-{}.png",
        scheme_slug(scheme),
        system_slug(system),
    )
}

/// Lowercase slug for a colour scheme (`light`, `dark`, `system`).
pub const fn scheme_slug(scheme: ColorScheme) -> &'static str {
    match scheme {
        ColorScheme::Light => "light",
        ColorScheme::Dark => "dark",
        ColorScheme::FollowSystem => "system",
    }
}

/// Lowercase slug for a design system.
pub const fn system_slug(system: DesignSystem) -> &'static str {
    match system {
        DesignSystem::Material3 => "material3",
        DesignSystem::Cupertino => "cupertino",
        DesignSystem::Fluent => "fluent",
        DesignSystem::Gnome => "gnome",
        DesignSystem::Breeze => "breeze",
        DesignSystem::Deepin => "deepin",
        DesignSystem::LiquidGlass => "liquidglass",
    }
}

/// Format a scale factor without trailing `.0` for integer scales (`1.0` -> `"1"`, `1.5` -> `"1.5"`).
pub fn format_scale(scale: f32) -> String {
    let s = if scale.is_finite() && scale > 0.0 {
        scale
    } else {
        1.0
    };
    if (s - s.round()).abs() < 1e-6 {
        format!("{}", s.round() as u32)
    } else {
        let raw = format!("{s:.2}");
        raw.trim_end_matches('0').trim_end_matches('.').to_string()
    }
}

/// Platform default design system used when a preview leaves `systems` unspecified.
pub const fn platform_default_system() -> DesignSystem {
    #[cfg(target_os = "macos")]
    {
        DesignSystem::Cupertino
    }
    #[cfg(target_os = "windows")]
    {
        DesignSystem::Fluent
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        DesignSystem::Material3
    }
}

/// Compose a `#[Preview]` function into its binary mutation batch for offscreen rendering.
///
/// The returned bytes are the exact wire-format batch that the desktop renderer decodes
/// and draws through Compose Multiplatform + Skia.
pub fn render_to_batch<F>(ctx: PreviewContext, preview_fn: F) -> Result<Vec<u8>, String>
where
    F: Fn(&PreviewContext) + Send + Sync + 'static,
{
    let mut theme = crate::schema::Theme::unified(ctx.system());
    theme.color_scheme = ctx.scheme();
    let (w, h) = ctx.size().dimensions();
    crate::window::publish(crate::window::WindowSize {
        width_dp: w as f32,
        height_dp: h as f32,
        class: ctx.size_class(),
        height_class: ctx.height_class(),
        safe_area: crate::window::WindowSafeArea::default(),
    });
    crate::design::publish(ctx.system());
    let mut host =
        crate::runtime::ComposeHost::with_content_and_theme(move || preview_fn(&ctx), theme);
    let bytes = host
        .rebuild()
        .map_err(|e| format!("preview composition failed: {e:?}"))?
        .to_vec();
    crate::window::reset_window_size();
    crate::design::reset_design_system();
    Ok(bytes)
}
