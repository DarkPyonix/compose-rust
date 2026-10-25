//! The colours of the reference sheet, written out.
//!
//! This sample draws the design its reference specifies on every platform, so these are
//! literals rather than roles. The sheet is ink on paper: white, three greys, black, and
//! two colours that are not grey at all, the red of a delete and the green of a switch
//! that is on. A role would hand each of those to whichever design system is running, and
//! the accent containers it answers with are pale lilac and powder blue, which the sheet
//! does not have.
//!
//! The values are read off the reference picture rather than chosen.

use compose_rust::ui::{Color, Paint};

/// The sheet.
pub const PAGE: Paint = Paint::Literal(Color::rgb(0xff_ffff));

/// The light card: the stepper, the panel with its paragraph, the list of toggles.
pub const PANEL: Paint = Paint::Literal(Color::rgb(0xf4_f4f4));

/// A region set into a card.
pub const RECESS: Paint = Paint::Literal(Color::rgb(0xe5_e5ea));

/// The dark card: the inverted stepper and the inverted header.
pub const DARK: Paint = Paint::Literal(Color::rgb(0x42_4242));

/// The grey card the reference sets its slider on.
pub const GREY: Paint = Paint::Literal(Color::rgb(0x7a_7a7a));

/// Ink: every word on a light card and the filled keys.
pub const INK: Paint = Paint::Literal(Color::rgb(0x00_0000));

/// What is written on a dark card, a grey one or a filled key.
pub const ON_DARK: Paint = Paint::Literal(Color::rgb(0xff_ffff));

/// What supports rather than says: a section label, a subtitle, a caption.
pub const MUTED: Paint = Paint::Literal(Color::rgb(0x8e_8e93));

/// The hairline around a swatch, which keeps a white one visible on the white sheet.
pub const OUTLINE: Paint = Paint::Literal(Color::rgb(0xe0_e0e0));

/// The one destructive action.
pub const ALERT: Paint = Paint::Literal(Color::rgb(0xee_3b33));

/// A switch that is on.
pub const ON: Paint = Paint::Literal(Color::rgb(0x34_e534));

/// The second disc of the hero mark, the pale one the black disc overlaps.
pub const PALE: Paint = Paint::Literal(Color::rgb(0xe5_e5ea));

#[cfg(test)]
mod tests {
    use super::*;

    /// The sheet's colours are the picture's, not the running design system's.
    ///
    /// Both halves are asserted: each one is a literal, which keeps the design system
    /// out, and each value is the one read off the reference, which keeps the sample
    /// matching its picture once somebody edits this file.
    #[test]
    fn fr22_the_palette_is_the_reference_colours_rather_than_the_theme() {
        assert_eq!(argb(PAGE), 0xffff_ffff);
        assert_eq!(argb(PANEL), 0xfff4_f4f4);
        assert_eq!(argb(RECESS), 0xffe5_e5ea);
        assert_eq!(argb(DARK), 0xff42_4242);
        assert_eq!(argb(GREY), 0xff7a_7a7a);
        assert_eq!(argb(INK), 0xff00_0000);
        assert_eq!(argb(ON_DARK), 0xffff_ffff);
        assert_eq!(argb(MUTED), 0xff8e_8e93);
        assert_eq!(argb(OUTLINE), 0xffe0_e0e0);
        assert_eq!(argb(ALERT), 0xffee_3b33);
        assert_eq!(argb(ON), 0xff34_e534);
        assert_eq!(argb(PALE), 0xffe5_e5ea);
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
