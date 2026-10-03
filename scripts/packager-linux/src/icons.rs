//! Application icons in the freedesktop icon theme layout.
//!
//! The size directory an icon goes in has to be its real size, so it is read from the
//! file rather than trusted from its name. Scaling is not done here: a store wants an icon
//! drawn for the size, and an upscaled one is the one thing a reviewer reliably notices.

use std::fmt;
use std::path::{Path, PathBuf};

/// The sizes the hicolor theme defines directories for.
pub const HICOLOR_SIZES: &[u32] = &[16, 22, 24, 32, 48, 64, 96, 128, 192, 256, 512];

/// The smallest raster icon Flathub accepts.
pub const FLATHUB_MINIMUM: u32 = 128;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IconKind {
    /// A square PNG of this many pixels a side.
    Png(u32),
    Svg,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Icon {
    pub source: PathBuf,
    pub kind: IconKind,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IconError(pub String);

impl fmt::Display for IconError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for IconError {}

/// Width and height from a PNG's header.
pub fn png_size(bytes: &[u8]) -> Option<(u32, u32)> {
    const SIGNATURE: &[u8] = b"\x89PNG\r\n\x1a\n";
    if bytes.len() < 24 || &bytes[..8] != SIGNATURE || &bytes[12..16] != b"IHDR" {
        return None;
    }
    let width = u32::from_be_bytes(bytes[16..20].try_into().ok()?);
    let height = u32::from_be_bytes(bytes[20..24].try_into().ok()?);
    Some((width, height))
}

impl Icon {
    /// Classifies the icon file at `source`, whose contents are `bytes`.
    pub fn classify(source: &Path, bytes: &[u8]) -> Result<Icon, IconError> {
        let shown = source.display();
        if let Some((width, height)) = png_size(bytes) {
            if width != height {
                return Err(IconError(format!(
                    "{shown} is {width}x{height}; an application icon must be square"
                )));
            }
            if !HICOLOR_SIZES.contains(&width) {
                return Err(IconError(format!(
                    "{shown} is {width}x{width}; the icon theme has directories for {}",
                    HICOLOR_SIZES
                        .iter()
                        .map(u32::to_string)
                        .collect::<Vec<_>>()
                        .join(", ")
                )));
            }
            return Ok(Icon {
                source: source.to_owned(),
                kind: IconKind::Png(width),
            });
        }
        let head = String::from_utf8_lossy(&bytes[..bytes.len().min(512)]).to_lowercase();
        if source
            .extension()
            .is_some_and(|e| e.eq_ignore_ascii_case("svg"))
            || head.contains("<svg")
        {
            return Ok(Icon {
                source: source.to_owned(),
                kind: IconKind::Svg,
            });
        }
        Err(IconError(format!("{shown} is neither a PNG nor an SVG")))
    }

    /// Where the icon goes under a prefix (`/app`, or an AppDir's `usr`).
    pub fn install_path(&self, app_id: &str) -> String {
        match self.kind {
            IconKind::Png(size) => {
                format!("share/icons/hicolor/{size}x{size}/apps/{app_id}.png")
            }
            IconKind::Svg => format!("share/icons/hicolor/scalable/apps/{app_id}.svg"),
        }
    }

    /// File name beside the desktop entry at an AppDir's root.
    pub fn root_name(&self, app_id: &str) -> String {
        match self.kind {
            IconKind::Png(_) => format!("{app_id}.png"),
            IconKind::Svg => format!("{app_id}.svg"),
        }
    }
}

/// The icon to show where only one is used: a vector one if there is one, else the largest.
pub fn best(icons: &[Icon]) -> Option<&Icon> {
    icons.iter().max_by_key(|icon| match icon.kind {
        IconKind::Svg => u32::MAX,
        IconKind::Png(size) => size,
    })
}

/// Checks a set of icons against what Flathub accepts: one vector icon, or a PNG of at
/// least 128 pixels, and never two for the same size.
pub fn check_for_flathub(icons: &[Icon]) -> Result<(), IconError> {
    if icons.is_empty() {
        return Err(IconError(
            "no icon: pass --icon or set [bundle] icon".into(),
        ));
    }
    let mut seen = Vec::new();
    for icon in icons {
        if seen.contains(&icon.kind) {
            return Err(IconError(format!(
                "two icons for the same size ({})",
                icon.source.display()
            )));
        }
        seen.push(icon.kind);
    }
    let large_enough = icons.iter().any(|icon| match icon.kind {
        IconKind::Svg => true,
        IconKind::Png(size) => size >= FLATHUB_MINIMUM,
    });
    if !large_enough {
        return Err(IconError(format!(
            "the largest icon is smaller than {FLATHUB_MINIMUM}x{FLATHUB_MINIMUM}; Flathub needs an SVG or a PNG at least that size"
        )));
    }
    Ok(())
}
