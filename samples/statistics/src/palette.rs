//! The colours the statistics screens are drawn in.
//!
//! Literals rather than roles, and that is the point of this file. The two screens are one
//! reference picture: a near-white page with a white dial on it, a sage panel, a black
//! column and one orange dot. A role is answered by whichever design system is running,
//! so a role here would come out as that system's blue or its accent containers, and none
//! of those colours is in the picture.
//!
//! Each value is read off the reference, so the page, the panel and the hatching match it
//! rather than an approximation of it. They live here rather than beside the widgets that
//! use them so the screen has one place to change.

use compose_rust::ui::{Color, Paint};

/// The page under everything on the overview, and the disc in the middle of the dial.
pub const PAGE: Paint = Paint::Literal(Color::rgb(0xf1_f1f1));

/// The dial's face, and the card holding the second reading.
pub const CARD: Paint = Paint::Literal(Color::rgb(0xff_ffff));

/// What is read: titles, readings, amounts, the dearest day's column.
pub const INK: Paint = Paint::Literal(Color::rgb(0x00_0000));

/// The costs panel on the overview, and the whole costs page.
pub const SAGE: Paint = Paint::Literal(Color::rgb(0xb1_c3b7));

/// A service tile on the costs page: a little darker than the page, so it reads as a tile.
pub const TILE: Paint = Paint::Literal(Color::rgb(0xa7_b7ab));

/// The stripes across every column that is not the dearest day.
pub const HATCH: Paint = Paint::Literal(Color::rgb(0x9d_afa2));

/// The round badge where a service's logo would be.
pub const BADGE: Paint = Paint::Literal(Color::rgb(0x97_a59b));

/// Quiet writing on sage: the days under the columns, a service's name, "All Time:".
pub const SAGE_INK: Paint = Paint::Literal(Color::rgb(0x6e_7f72));

/// The block under the second reading.
pub const POWDER: Paint = Paint::Literal(Color::rgb(0xaf_c1d2));

/// The one accent: the dot that marks the dial's reading.
pub const ORANGE: Paint = Paint::Literal(Color::rgb(0xff_8922));

/// The ticks round the dial.
pub const TICK: Paint = Paint::Literal(Color::rgb(0xd0_d0d0));

/// What the dial is reading ("Mobile"), and the chevron at the foot.
pub const GREY: Paint = Paint::Literal(Color::rgb(0x99_9999));

/// The day that is not selected at the foot of the overview.
pub const FAINT: Paint = Paint::Literal(Color::rgb(0xbe_bebe));

/// The round "+" button.
pub const BUTTON: Paint = Paint::Literal(Color::rgb(0xd9_d9d9));

/// Every colour above, for the tests that hold drawings and widgets to this file.
#[cfg(test)]
pub const ALL: [Paint; 14] = [
    PAGE, CARD, INK, SAGE, TILE, HATCH, BADGE, SAGE_INK, POWDER, ORANGE, TICK, GREY, FAINT, BUTTON,
];

#[cfg(test)]
mod tests {
    use super::*;

    /// The screens' colours are the picture's, not the running design system's.
    ///
    /// Both halves are asserted. That each one is a literal is what keeps the design
    /// system out: a role here comes out as the theme's blue and its accent containers.
    /// That each value is the one written here is what keeps the screens matching the
    /// picture they were taken from once somebody edits this file. The test this sample
    /// had before only checked that the charts used colours from this list, which passes
    /// whatever the list holds.
    #[test]
    fn fr22_the_palette_is_the_reference_colours_rather_than_the_theme() {
        assert_eq!(argb(PAGE), 0xfff1_f1f1);
        assert_eq!(argb(CARD), 0xffff_ffff);
        assert_eq!(argb(INK), 0xff00_0000);
        assert_eq!(argb(SAGE), 0xffb1_c3b7);
        assert_eq!(argb(TILE), 0xffa7_b7ab);
        assert_eq!(argb(HATCH), 0xff9d_afa2);
        assert_eq!(argb(BADGE), 0xff97_a59b);
        assert_eq!(argb(SAGE_INK), 0xff6e_7f72);
        assert_eq!(argb(POWDER), 0xffaf_c1d2);
        assert_eq!(argb(ORANGE), 0xffff_8922);
        assert_eq!(argb(TICK), 0xffd0_d0d0);
        assert_eq!(argb(GREY), 0xff99_9999);
        assert_eq!(argb(FAINT), 0xffbe_bebe);
        assert_eq!(argb(BUTTON), 0xffd9_d9d9);
    }

    /// The one blue in the picture is the pale powder block, and it is nowhere near the
    /// saturated blue a design system's accent is. Anything bluer than that is the theme
    /// leaking back in.
    #[test]
    fn fr22_no_colour_in_the_palette_is_the_theme_blue() {
        for paint in ALL {
            let value = argb(paint);
            let (red, green, blue) = ((value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff);
            assert!(
                blue <= red.max(green) + 0x20,
                "{value:08x} is bluer than anything in the reference",
            );
        }
    }

    /// Each colour is named once. Two names for one value is how a screen ends up with
    /// two colours that drift apart when one of them is edited.
    #[test]
    fn fr22_every_colour_in_the_palette_is_a_different_colour() {
        for (index, paint) in ALL.iter().enumerate() {
            for other in &ALL[index + 1..] {
                assert_ne!(argb(*paint), argb(*other), "two names for one colour");
            }
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
