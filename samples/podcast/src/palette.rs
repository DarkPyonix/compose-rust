//! The colours the podcast hub is drawn in.
//!
//! Literals rather than roles, and that is the whole point of this file. The hub is one
//! reference picture: white pages, near black ink, and one orange that marks everything
//! worth pressing. A role would be resolved by whichever design system is running, so the
//! orange came out as that system's accent (blue, every time) and the rows under
//! "Podcasts You May Like" came out of the accent containers as pale blue, lilac and
//! pink. None of those colours is anywhere in the picture this hub is meant to be.
//!
//! They live here rather than beside the widgets that use them so that the hub has one
//! place to change. A hex scattered through the tree is a hex somebody misses.

use compose_rust::ui::{Color, Paint};

/// The page, and what reads on the orange.
pub const PAGE: Paint = Paint::Literal(Color::rgb(0xff_ffff));

/// What is read: titles, names, counts.
pub const INK: Paint = Paint::Literal(Color::rgb(0x11_1111));

/// The blurb under a title, and anything that supports rather than says.
pub const MUTED: Paint = Paint::Literal(Color::rgb(0x6e_6e73));

/// The one accent: the search button, the played part of a waveform, the play button, the
/// row that is being recommended, and the destination you are on.
pub const ACCENT: Paint = Paint::Literal(Color::rgb(0xf4_511e));

/// The flat grey a resting round button is filled with, and the panels on the profile.
pub const TILE: Paint = Paint::Literal(Color::rgb(0xf1_f1f1));

/// The hairline round a card, a row and an outlined action.
pub const OUTLINE: Paint = Paint::Literal(Color::rgb(0xe5_e5e5));

/// The part of a waveform that has not been played yet.
pub const WAVE: Paint = Paint::Literal(Color::rgb(0x9a_9a9a));

/// White let through a little, laid on the orange: the follow button on the recommended
/// row, which the reference draws as a paler orange pill rather than a white outline.
pub const ON_ACCENT_SOFT: Paint = Paint::Literal(Color::argb(0x33_ffffff));

/// The edge of a square button standing on the orange artwork of the player.
pub const ON_ACCENT_EDGE: Paint = Paint::Literal(Color::argb(0x66_ffffff));

#[cfg(test)]
mod tests {
    use super::*;

    /// The hub's colours are the picture's, not the running design system's.
    ///
    /// Both halves are asserted. That each one is a literal is what keeps the design
    /// system out: this hub was drawn with roles once, and every accent on it came out as
    /// the theme's blue while the rows came out pale blue, lilac and pink. That each value
    /// is the one written here is what keeps the hub matching the picture it was taken
    /// from once somebody edits this file.
    #[test]
    fn fr22_the_palette_is_the_reference_colours_rather_than_the_theme() {
        assert_eq!(argb(PAGE), 0xffff_ffff);
        assert_eq!(argb(INK), 0xff11_1111);
        assert_eq!(argb(MUTED), 0xff6e_6e73);
        assert_eq!(argb(ACCENT), 0xfff4_511e);
        assert_eq!(argb(TILE), 0xfff1_f1f1);
        assert_eq!(argb(OUTLINE), 0xffe5_e5e5);
        assert_eq!(argb(WAVE), 0xff9a_9a9a);
        assert_eq!(argb(ON_ACCENT_SOFT), 0x33ff_ffff);
        assert_eq!(argb(ON_ACCENT_EDGE), 0x66ff_ffff);
    }

    /// Nothing in the hub is blue, which is the fault this palette exists to fix. The
    /// reference has one accent and it is the orange above.
    #[test]
    fn fr22_no_colour_in_the_palette_is_the_theme_blue() {
        for paint in [
            PAGE,
            INK,
            MUTED,
            ACCENT,
            TILE,
            OUTLINE,
            WAVE,
            ON_ACCENT_SOFT,
            ON_ACCENT_EDGE,
        ] {
            let value = argb(paint);
            let (red, green, blue) = ((value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff);
            assert!(
                blue <= red.max(green) + 8,
                "{value:08x} is bluer than the hub ever gets",
            );
        }
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
