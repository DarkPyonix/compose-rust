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

/// Everything written on the cream page.
pub const INK: Paint = Paint::Literal(Color::rgb(0x2E2A4F));

/// The support line under a title, and the meta above one.
pub const MUTED: Paint = Paint::Literal(Color::rgb(0x7A7596));

/// The small uppercase label that sits above a course title.
pub const META: Paint = Paint::Literal(Color::rgb(0x5E8C87));

/// The coral the reference marks things with: the heart on the favourites card, the
/// shirts its figures wear. Here it is the destination you are on.
pub const ACCENT: Paint = Paint::Literal(Color::rgb(0xEF6A63));

/// The two illustration grounds the reference alternates between.
pub const SAGE: Paint = Paint::Literal(Color::rgb(0xA8C5BF));
pub const BLUSH: Paint = Paint::Literal(Color::rgb(0xF4938A));

/// The deep green of the play badge on a green card.
pub const DEEP_SAGE: Paint = Paint::Literal(Color::rgb(0x2E7C77));

/// The page the sleep stories stand on. The reference sets them on a dark navy, the one
/// screen in the app that is not cream.
pub const NIGHT: Paint = Paint::Literal(Color::rgb(0x2B2B4B));

/// The round back button beside the sleep stories' title, a step darker than the page.
pub const NIGHT_RAISED: Paint = Paint::Literal(Color::rgb(0x1F1F38));

/// What is written on a picture and on the night page.
pub const ON_ART: Paint = Paint::Literal(Color::rgb(0xFFFFFF));

/// The support line in a caption drawn over a picture or on the night page.
pub const ON_ART_MUTED: Paint = Paint::Literal(Color::argb(0xCC_FFFFFF));

/// The dark round badge a card opens by, and the back button on an opened course.
pub const BADGE: Paint = Paint::Literal(Color::argb(0xE6_2E2A4F));

/// The two ends of the wash a caption sits on inside a picture: clear where the picture
/// shows, the page's plum where the words are. Colours rather than paints, because they
/// are the stops of a gradient rather than fills of their own.
pub const SCRIM_TOP: Color = Color::argb(0x00_2E2A4F);
pub const SCRIM_BOTTOM: Color = Color::argb(0xD9_2E2A4F);

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
        assert_eq!(argb(ACCENT), 0xffef_6a63);
        assert_eq!(argb(META), 0xff5e_8c87);
        assert_eq!(argb(SAGE), 0xffa8_c5bf);
        assert_eq!(argb(BLUSH), 0xfff4_938a);
        assert_eq!(argb(DEEP_SAGE), 0xff2e_7c77);
        assert_eq!(argb(NIGHT), 0xff2b_2b4b);
        assert_eq!(argb(NIGHT_RAISED), 0xff1f_1f38);
        assert_eq!(argb(ON_ART), 0xffff_ffff);
        assert_eq!(argb(ON_ART_MUTED), 0xccff_ffff);
        assert_eq!(argb(BADGE), 0xe62e_2a4f);
        assert_eq!(SCRIM_TOP.to_argb(), 0x002e_2a4f);
        assert_eq!(SCRIM_BOTTOM.to_argb(), 0xd92e_2a4f);
    }

    /// The sleep stories are on a dark page and everything else is on cream, which is
    /// the one change of ground the reference makes. Dark here means the white written on
    /// it reads: the page is well under a fifth as light as the cream.
    #[test]
    fn fr22_the_night_page_is_dark_navy_rather_than_cream() {
        let luminance = |paint| {
            let value = argb(paint);
            let (red, green, blue) = ((value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff);
            (red * 299 + green * 587 + blue * 114) / 1000
        };
        assert!(
            luminance(NIGHT) * 5 < luminance(PAGE),
            "the night page is not dark"
        );
        let value = argb(NIGHT);
        assert!(
            value & 0xff > (value >> 16) & 0xff,
            "the night page is not navy: its blue is no deeper than its red"
        );
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
