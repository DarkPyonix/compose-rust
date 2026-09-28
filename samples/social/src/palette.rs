//! The colours of the reference's meditation app, written out.
//!
//! A unified sample draws the design its reference specifies on every platform, so these
//! are literals rather than roles. The page is the clearest case: the reference is cream,
//! and measuring `ColorRole::Background` across the seven design systems gives seven
//! answers that all land within a few percent of white. There is no role for cream.

use dioxus_compose::prelude::Color;

/// The page. Cream, which is what the reference sets every light screen on.
pub const PAGE: Color = Color::rgb(0xFDF3E7);

/// A card standing on the page.
pub const CARD: Color = Color::rgb(0xFFFFFF);

/// Everything written on the page, and the fill of the hero's caption band.
pub const INK: Color = Color::rgb(0x2E2A4F);

/// The support line under a title, and the meta above one.
pub const MUTED: Color = Color::rgb(0x7A7596);

/// The small uppercase label that sits above a course title.
pub const META: Color = Color::rgb(0x5E8C87);

/// The two illustration grounds the reference alternates between.
pub const SAGE: Color = Color::rgb(0xA8C5BF);
pub const BLUSH: Color = Color::rgb(0xF4938A);

/// What is written on either of those, and on the caption band.
pub const ON_ART: Color = Color::rgb(0xFFFFFF);

/// The band a caption sits on inside an illustration, and the badge on it.
///
/// The reference lays its hero caption over the picture rather than under it, on a panel
/// dark enough to read white text on. These are that panel and the round play badge that
/// shares it.
pub const SCRIM: Color = Color::argb(0xD9_2E2A4F);
pub const SCRIM_BADGE: Color = Color::argb(0x40_FFFFFF);

/// The support line in a caption drawn over an illustration.
pub const ON_ART_MUTED: Color = Color::argb(0xCC_FFFFFF);
