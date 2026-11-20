//! The colours the component sheet is drawn in.
//!
//! Literals rather than roles, and that is the whole point of this file. The sheet is one
//! reference picture: black, white and four greys, with exactly two colours that are not
//! grey, the red behind the delete action and the green of a switch that is on. A role
//! would be resolved by whichever design system is running, so the segmented control, the
//! buttons and the switch came out in that system's blue, the page came out in its grey,
//! and a group of its accent containers came out pale lilac and powder blue. None of those
//! colours is anywhere on the sheet.
//!
//! They live here rather than beside the widgets that use them so that the sheet has one
//! place to change. A hex scattered through the tree is a hex somebody misses.
//!
//! Each value was read off the reference picture. A few come as a `Color` as well, because
//! a gradient's stops are colours rather than paints.

use compose_rust::ui::{Color, Paint};

/// The sheet, and the ink on anything filled black or dark grey.
pub const PAGE: Paint = Paint::Literal(PAGE_COLOR);
pub const PAGE_COLOR: Color = Color::rgb(0xff_ffff);

/// What is read on a light card, and the fill of everything the sheet draws black: the
/// selected segment, the stepper's round buttons, the inverted bar and the black tile.
pub const INK: Paint = Paint::Literal(INK_COLOR);
pub const INK_COLOR: Color = Color::rgb(0x00_0000);

/// The light card every light component sits on.
pub const TILE: Paint = Paint::Literal(Color::rgb(0xf7_f7f7));

/// The track a segmented control slides on, a switch that is off, and the grabber on a
/// light card.
pub const TRACK: Paint = Paint::Literal(Color::rgb(0xda_dada));

/// The second line of anything: the subtitle under the hero, a description, a paragraph.
pub const MUTED: Paint = Paint::Literal(Color::rgb(0x8c_8c8c));

/// The dark card: the inverted stepper, the dark chip and tile, and the search field on
/// the black bar.
pub const DARK: Paint = Paint::Literal(Color::rgb(0x45_4545));

/// The mid grey card the slider sits on.
pub const SLATE: Paint = Paint::Literal(Color::rgb(0x75_7575));

/// The grabber on the mid grey card, and the part of its slider track the thumb has not
/// reached.
pub const SLATE_LINE: Paint = Paint::Literal(Color::rgb(0x84_8484));

/// The one destructive action.
pub const DELETE: Paint = Paint::Literal(Color::rgb(0xf0_3131));

/// A switch that is on.
pub const SWITCH: Paint = Paint::Literal(Color::rgb(0x21_ff21));

/// Where the shaded disc of the hero ends, on its right.
pub const FADE_COLOR: Color = Color::rgb(0xec_ecec);

/// The soft shadow under the hero and the white cards, which is how a white card on a
/// white sheet is seen at all.
pub const SHADOW: Paint = Paint::Literal(Color::argb(0x26_000000));

/// The coin on a "Button / Description" chip: its worn edge, its bronze ring and its gold
/// face.
pub const COIN_EDGE: Paint = Paint::Literal(Color::rgb(0x6f_675d));
pub const COIN_RING: Paint = Paint::Literal(Color::rgb(0x7b_5228));
pub const COIN_FACE: Paint = Paint::Literal(Color::rgb(0xf4_d162));

/// Every paint above, for the tests that read all of them.
#[cfg(test)]
pub const ALL: [Paint; 15] = [
    PAGE,
    INK,
    TILE,
    TRACK,
    MUTED,
    DARK,
    SLATE,
    SLATE_LINE,
    DELETE,
    SWITCH,
    SHADOW,
    COIN_EDGE,
    COIN_RING,
    COIN_FACE,
    Paint::Literal(FADE_COLOR),
];

#[cfg(test)]
mod tests {
    use super::*;

    /// The sheet's colours are the picture's, not the running design system's.
    ///
    /// Both halves are asserted. That each one is a literal is what keeps the design
    /// system out: this sample was drawn with roles once, and its segmented control,
    /// buttons and switch came out in the theme's blue while a whole group of panels came
    /// out of the accent containers. That each value is the one written here is what
    /// keeps the sheet matching the picture it was taken from once somebody edits this
    /// file.
    #[test]
    fn fr22_the_palette_is_the_reference_colours_rather_than_the_theme() {
        assert_eq!(argb(PAGE), 0xffff_ffff);
        assert_eq!(argb(INK), 0xff00_0000);
        assert_eq!(argb(TILE), 0xfff7_f7f7);
        assert_eq!(argb(TRACK), 0xffda_dada);
        assert_eq!(argb(MUTED), 0xff8c_8c8c);
        assert_eq!(argb(DARK), 0xff45_4545);
        assert_eq!(argb(SLATE), 0xff75_7575);
        assert_eq!(argb(SLATE_LINE), 0xff84_8484);
        assert_eq!(argb(DELETE), 0xfff0_3131);
        assert_eq!(argb(SWITCH), 0xff21_ff21);
        assert_eq!(argb(SHADOW), 0x2600_0000);
        assert_eq!(argb(COIN_EDGE), 0xff6f_675d);
        assert_eq!(argb(COIN_RING), 0xff7b_5228);
        assert_eq!(argb(COIN_FACE), 0xfff4_d162);
        assert_eq!(PAGE_COLOR.to_argb(), 0xffff_ffff);
        assert_eq!(INK_COLOR.to_argb(), 0xff00_0000);
        assert_eq!(FADE_COLOR.to_argb(), 0xffec_ecec);
    }

    /// The sheet is monochrome apart from the red and the green, so every other colour
    /// in it is a grey: the same amount of red, green and blue. A colour that crept in
    /// from a design system would not be.
    #[test]
    fn fr22_everything_but_the_red_the_green_and_the_coin_is_grey() {
        for paint in ALL {
            if [DELETE, SWITCH, COIN_EDGE, COIN_RING, COIN_FACE].contains(&paint) {
                continue;
            }
            let value = argb(paint);
            let (red, green, blue) = ((value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff);
            assert!(
                red == green && green == blue,
                "{value:08x} is not a grey, and the sheet has no colour there",
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
