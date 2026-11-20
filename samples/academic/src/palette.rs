//! The colours the drum school is drawn in.
//!
//! Literals rather than roles, and that is the whole point of this file. The school is one
//! reference picture: a black page, four tiles in purple, coral, teal and amber, one white
//! lesson card and dark grey locked ones. A role would be resolved by whichever design
//! system is running, so the chosen stage and the open lesson came out in that system's
//! blue, and the progress cards came out of the accent containers in pale tints. None of
//! those colours is anywhere in the picture this school is meant to be.
//!
//! The values were read off the reference picture. They live here rather than beside the
//! widgets that use them so that the school has one place to change.

use compose_rust::ui::{Color, Paint};

/// The page, under everything.
pub const PAGE: Paint = Paint::Literal(Color::rgb(0x00_0000));

/// What is read on the page: the title, the tile names, the bar's icons.
pub const TEXT: Paint = Paint::Literal(Color::rgb(0xff_ffff));

/// A stage nobody has reached yet, which the strip names in a quieter grey.
pub const MUTED: Paint = Paint::Literal(Color::rgb(0x95_9595));

/// The Technique tile.
pub const PURPLE: Paint = Paint::Literal(Color::rgb(0x3b_2566));

/// The one accent: the dot on the destination you are on, the dot beside "RECENT", the
/// quarter circle in the corner of the open card. The Arsenal tile is the same coral.
pub const ACCENT: Paint = Paint::Literal(Color::rgb(0xfc_5358));

/// The Coordination tile.
pub const TEAL: Paint = Paint::Literal(Color::rgb(0x17_c6b9));

/// The Songs tile, and the pill behind the stage being looked at.
pub const AMBER: Paint = Paint::Literal(Color::rgb(0xfc_ad2c));

/// The lesson that is open. The one light card on a black screen.
pub const CARD: Paint = Paint::Literal(Color::rgb(0xff_ffff));

/// The title and the "RECENT" mark on the open card.
pub const CARD_INK: Paint = Paint::Literal(Color::rgb(0x00_0000));

/// The "4th lesson" line on the open card.
pub const CARD_MUTED: Paint = Paint::Literal(Color::rgb(0x78_7878));

/// The chevron on the open card, a near black indigo rather than the ink.
pub const CHEVRON: Paint = Paint::Literal(Color::rgb(0x1f_1846));

/// A lesson that is still locked: a dark grey card, a little lighter than the page.
pub const LOCKED: Paint = Paint::Literal(Color::rgb(0x1f_222a));

/// Everything written on a locked card.
pub const LOCKED_INK: Paint = Paint::Literal(Color::rgb(0xa6_a9af));

#[cfg(test)]
mod tests {
    use super::*;

    /// The school's colours are the picture's, not the running design system's.
    ///
    /// Both halves are asserted. That each one is a literal is what keeps the design
    /// system out: the lesson cards were drawn with roles once and the open one came out
    /// with the theme's blue on it. That each value is the one written here is what keeps
    /// the school matching the picture it was taken from once somebody edits this file.
    #[test]
    fn fr22_the_palette_is_the_reference_colours_rather_than_the_theme() {
        assert_eq!(argb(PAGE), 0xff00_0000);
        assert_eq!(argb(TEXT), 0xffff_ffff);
        assert_eq!(argb(MUTED), 0xff95_9595);
        assert_eq!(argb(PURPLE), 0xff3b_2566);
        assert_eq!(argb(ACCENT), 0xfffc_5358);
        assert_eq!(argb(TEAL), 0xff17_c6b9);
        assert_eq!(argb(AMBER), 0xfffc_ad2c);
        assert_eq!(argb(CARD), 0xffff_ffff);
        assert_eq!(argb(CARD_INK), 0xff00_0000);
        assert_eq!(argb(CARD_MUTED), 0xff78_7878);
        assert_eq!(argb(CHEVRON), 0xff1f_1846);
        assert_eq!(argb(LOCKED), 0xff1f_222a);
        assert_eq!(argb(LOCKED_INK), 0xffa6_a9af);
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
