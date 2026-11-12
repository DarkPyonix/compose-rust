//! The colours of the reference's meditation app, written out.
//!
//! A unified sample draws the design its reference specifies on every platform, so these
//! are literals rather than roles. The page is the clearest case: the reference is cream,
//! and measuring `ColorRole::Background` across the seven design systems gives seven
//! answers that all land within a few percent of white. There is no role for cream.

use compose_rust::ui::{Color, Paint};

/// The page. Cream, which is what the reference sets every light screen on.
pub const PAGE: Paint = Paint::Literal(Color::rgb(0xFDF3E7));

/// A card standing on the page.
pub const CARD: Paint = Paint::Literal(Color::rgb(0xFFFFFF));

/// Everything written on the page, and the fill of the hero's caption band.
pub const INK: Paint = Paint::Literal(Color::rgb(0x2E2A4F));

/// The support line under a title, and the meta above one.
pub const MUTED: Paint = Paint::Literal(Color::rgb(0x7A7596));

/// The small uppercase label that sits above a course title.
pub const META: Paint = Paint::Literal(Color::rgb(0x5E8C87));

/// The two illustration grounds the reference alternates between.
pub const SAGE: Paint = Paint::Literal(Color::rgb(0xA8C5BF));
pub const BLUSH: Paint = Paint::Literal(Color::rgb(0xF4938A));

/// What is written on either of those, and on the caption band.
pub const ON_ART: Paint = Paint::Literal(Color::rgb(0xFFFFFF));

/// The band a caption sits on inside an illustration, and the badge on it.
///
/// The reference lays its hero caption over the picture rather than under it, on a panel
/// dark enough to read white text on. These are that panel and the round play badge that
/// shares it.
pub const SCRIM: Paint = Paint::Literal(Color::argb(0xD9_2E2A4F));
pub const SCRIM_BADGE: Paint = Paint::Literal(Color::argb(0x40_FFFFFF));

/// The support line in a caption drawn over an illustration.
pub const ON_ART_MUTED: Paint = Paint::Literal(Color::argb(0xCC_FFFFFF));

#[cfg(test)]
mod tests {
    use super::*;

    /// The meditation app's colours are the picture's, not the running design system's.
    ///
    /// Both halves are asserted. That each one is a literal is what keeps the design
    /// system out: no design system has a cream page, a deep plum ink or a sage, and a
    /// role would hand each of them to whichever system is running. That each value is
    /// the one written here is what keeps the app matching the picture it was taken from
    /// once somebody edits this file.
    #[test]
    fn fr22_the_palette_is_the_reference_colours_rather_than_the_theme() {
        assert_eq!(argb(PAGE), 0xfffd_f3e7);
        assert_eq!(argb(CARD), 0xffff_ffff);
        assert_eq!(argb(INK), 0xff2e_2a4f);
        assert_eq!(argb(MUTED), 0xff7a_7596);
        assert_eq!(argb(META), 0xff5e_8c87);
        assert_eq!(argb(SAGE), 0xffa8_c5bf);
        assert_eq!(argb(BLUSH), 0xfff4_938a);
        assert_eq!(argb(ON_ART), 0xffff_ffff);
        assert_eq!(argb(SCRIM), 0xd92e_2a4f);
        assert_eq!(argb(SCRIM_BADGE), 0x40ff_ffff);
        assert_eq!(argb(ON_ART_MUTED), 0xccff_ffff);
    }

    fn argb(paint: Paint) -> u32 {
        match paint {
            Paint::Literal(color) => color.to_argb(),
            Paint::Role(role) => {
                panic!("{role:?} is a role, so the design system would pick the colour")
            }
            Paint::Asset(id) => panic!("brush {id} is a fill, not a colour this can check"),
        }
    }
}
