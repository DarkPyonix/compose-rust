//! The colours of the reference sheet, written out.
//!
//! A unified sample draws the design its reference specifies on every platform, so these
//! are literals rather than roles. The sheet is ink on paper: white, three greys, black,
//! and two colours that are not grey at all, the red of a delete and the green of a switch
//! that is on. A role would hand each of those to whichever design system is running, and
//! every one of the seven answers an accent with a blue the sheet does not have.
//!
//! The values are read off the reference picture rather than chosen.

use dioxus_compose::prelude::Color;

/// The sheet.
pub const PAGE: Color = Color::rgb(0xFFFFFF);

/// The light card: the stepper, the panel with its paragraph, the list of toggles.
pub const PANEL: Color = Color::rgb(0xF4F4F4);

/// A region set into a card, and a key that cannot be pressed.
pub const RECESS: Color = Color::rgb(0xE5E5EA);

/// The dark card: the inverted stepper, and the search field on the black header.
pub const DARK: Color = Color::rgb(0x424242);

/// The grey card the reference sets its slider on.
pub const GREY: Color = Color::rgb(0x7A7A7A);

/// Ink: every word on a light card, the filled keys and the inverted header.
pub const INK: Color = Color::rgb(0x000000);

/// What is written on a dark card, a grey one or a filled key.
pub const ON_DARK: Color = Color::rgb(0xFFFFFF);

/// What supports rather than says: a section label, a subtitle, a caption.
pub const MUTED: Color = Color::rgb(0x8E8E93);

/// The hairline between two rows, and the edge that keeps a white swatch visible on the
/// white sheet.
pub const OUTLINE: Color = Color::rgb(0xE0E0E0);

/// The one destructive action.
pub const ALERT: Color = Color::rgb(0xEE3B33);

/// A switch that is on.
pub const ON: Color = Color::rgb(0x34E534);

/// The second disc of the hero mark, the pale one the black disc overlaps.
pub const PALE: Color = Color::rgb(0xE5E5EA);

#[cfg(test)]
mod tests {
    use super::*;

    /// The sheet's colours are the picture's, not the running design system's.
    ///
    /// `Color` rather than `Paint`, so a role cannot be written in this file at all; what
    /// this pins is that each value is still the one read off the reference.
    #[test]
    fn fr22_the_palette_is_the_reference_colours_rather_than_the_theme() {
        assert_eq!(PAGE.to_argb(), 0xffff_ffff);
        assert_eq!(PANEL.to_argb(), 0xfff4_f4f4);
        assert_eq!(RECESS.to_argb(), 0xffe5_e5ea);
        assert_eq!(DARK.to_argb(), 0xff42_4242);
        assert_eq!(GREY.to_argb(), 0xff7a_7a7a);
        assert_eq!(INK.to_argb(), 0xff00_0000);
        assert_eq!(ON_DARK.to_argb(), 0xffff_ffff);
        assert_eq!(MUTED.to_argb(), 0xff8e_8e93);
        assert_eq!(OUTLINE.to_argb(), 0xffe0_e0e0);
        assert_eq!(ALERT.to_argb(), 0xffee_3b33);
        assert_eq!(ON.to_argb(), 0xff34_e534);
        assert_eq!(PALE.to_argb(), 0xffe5_e5ea);
    }
}
