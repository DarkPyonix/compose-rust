//! The colours the shop is drawn in.
//!
//! Literals rather than roles, and that is the whole point of this file. The shop is one
//! reference picture: a white page, near black ink, flat light grey cards and a single
//! yellow. A role would be resolved by whichever design system is running, so the yellow
//! came out as that system's accent (blue, every time) and the cards came out of the
//! accent containers as pale lilac and powder blue. None of those colours is anywhere in
//! the picture this shop is meant to be.
//!
//! They live here rather than beside the widgets that use them so that the shop has one
//! place to change. A hex scattered through the tree is a hex somebody misses.

use dioxus_compose::prelude::*;

/// The page, and the ink that reads on a filled dark control.
pub const PAGE: Paint = Paint::Literal(Color::rgb(0xff_ffff));

/// What is read: headings, names, prices, and the icons along the bottom.
pub const INK: Paint = Paint::Literal(Color::rgb(0x11_1111));

/// The second line under a name, and anything that supports rather than says.
pub const MUTED: Paint = Paint::Literal(Color::rgb(0x73_747b));

/// The flat grey a garment is photographed against: the carousel card and every product
/// card's picture area.
pub const TILE: Paint = Paint::Literal(Color::rgb(0xf5_f5f5));

/// The one accent. It marks the size that is chosen and the destination you are on, and
/// nothing else on the screen is coloured at all.
pub const ACCENT: Paint = Paint::Literal(Color::rgb(0xff_d60a));

/// The filled dark control: the carousel's active dot, the stepper's plus, and the button
/// that puts a garment in the bag. Lighter than the ink, the way the reference draws it.
pub const DARK: Paint = Paint::Literal(Color::rgb(0x34_3434));

/// A carousel dot that is not the one showing.
pub const DOT: Paint = Paint::Literal(Color::rgb(0xe3_e3e3));

/// The hairline between two rows of a grouped list.
pub const OUTLINE: Paint = Paint::Literal(Color::rgb(0xe5_e5e5));

/// The mark on the bag that says something is waiting, and the one destructive action.
pub const ALERT: Paint = Paint::Literal(Color::rgb(0xff_5a4e));

#[cfg(test)]
mod tests {
    use super::*;

    /// The shop's colours are the picture's, not the running design system's.
    ///
    /// Both halves are asserted. That each one is a literal is what keeps the design
    /// system out: this shop was drawn with roles once, and every accent on it came out
    /// as the theme's blue while the cards came out pale lilac and powder blue. That each
    /// value is the one written here is what keeps the shop matching the picture it was
    /// taken from once somebody edits this file.
    #[test]
    fn fr22_the_palette_is_the_reference_colours_rather_than_the_theme() {
        assert_eq!(argb(PAGE), 0xffff_ffff);
        assert_eq!(argb(INK), 0xff11_1111);
        assert_eq!(argb(MUTED), 0xff73_747b);
        assert_eq!(argb(TILE), 0xfff5_f5f5);
        assert_eq!(argb(ACCENT), 0xffff_d60a);
        assert_eq!(argb(DARK), 0xff34_3434);
        assert_eq!(argb(DOT), 0xffe3_e3e3);
        assert_eq!(argb(OUTLINE), 0xffe5_e5e5);
        assert_eq!(argb(ALERT), 0xffff_5a4e);
    }

    /// Nothing in the shop is blue, which is the fault this palette exists to fix. The
    /// reference has one accent and it is the yellow above.
    #[test]
    fn fr22_no_colour_in_the_palette_is_the_theme_blue() {
        for paint in [PAGE, INK, MUTED, TILE, ACCENT, DARK, DOT, OUTLINE, ALERT] {
            let value = argb(paint);
            let (red, green, blue) = ((value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff);
            assert!(
                blue <= red.max(green) + 8,
                "{value:08x} is bluer than the shop ever gets",
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
