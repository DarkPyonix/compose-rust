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

/// The support line in a caption drawn over an illustration, and on the night page.
pub const ON_ART_MUTED: Color = Color::argb(0xCC_FFFFFF);

/// The sleep stories page. The reference draws that one destination dark navy, inside an
/// app whose other pages are cream.
pub const NIGHT: Color = Color::rgb(0x2C2B4D);

/// The one accent: the coral of the reference's heart badge, which marks the destination
/// you are on along the bottom.
pub const ACCENT: Color = Color::rgb(0xE8625A);

#[cfg(test)]
mod tests {
    use super::*;

    /// The app's colours are the picture's, not the running design system's.
    ///
    /// `Color` rather than `Paint`, so a role cannot be written in this file at all; what
    /// this pins is that each value is still the one read off the reference, so an edit
    /// that drifts from the picture says so here first.
    #[test]
    fn fr22_the_palette_is_the_reference_colours_rather_than_the_theme() {
        assert_eq!(PAGE.to_argb(), 0xfffd_f3e7);
        assert_eq!(CARD.to_argb(), 0xffff_ffff);
        assert_eq!(INK.to_argb(), 0xff2e_2a4f);
        assert_eq!(MUTED.to_argb(), 0xff7a_7596);
        assert_eq!(META.to_argb(), 0xff5e_8c87);
        assert_eq!(SAGE.to_argb(), 0xffa8_c5bf);
        assert_eq!(BLUSH.to_argb(), 0xfff4_938a);
        assert_eq!(ON_ART.to_argb(), 0xffff_ffff);
        assert_eq!(SCRIM.to_argb(), 0xd92e_2a4f);
        assert_eq!(SCRIM_BADGE.to_argb(), 0x40ff_ffff);
        assert_eq!(ON_ART_MUTED.to_argb(), 0xccff_ffff);
        assert_eq!(NIGHT.to_argb(), 0xff2c_2b4d);
        assert_eq!(ACCENT.to_argb(), 0xffe8_625a);
    }
}
