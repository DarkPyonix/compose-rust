//! The colours of the reference's check-in, written out.
//!
//! A unified sample draws the design its reference specifies on every platform, so these
//! are literals rather than roles. A role would hand the question to whichever design
//! system happened to be active, and none of the seven answers with a mint or a coral.
//!
//! The values are read off the reference picture rather than chosen: the four feelings
//! are the pastels it fills the panel with, and the page under them is black because the
//! check-in is the screen this sample opens on.

use dioxus_compose::prelude::Color;

/// The page. Black, as the check-in and the worry picker both are.
pub const PAGE: Color = Color::rgb(0x000000);

/// Everything written on the page.
pub const INK: Color = Color::rgb(0xFFFFFF);

/// An answer that was not chosen. Dark enough to sit on the page as a shape rather than
/// as a hole in it, and quiet enough that the chosen one is the only colour in the row.
pub const CHIP: Color = Color::rgb(0x262626);

/// The four feelings, and the ink that reads on each. All four inks are the page's own
/// black: these are pastels, and a pastel carrying white text is the one way to make four
/// quiet colours unreadable.
pub const MINT: Color = Color::rgb(0xA7F3D0);
pub const PINK: Color = Color::rgb(0xF9A8D4);
pub const POWDER: Color = Color::rgb(0xBAE6FD);
pub const CORAL: Color = Color::rgb(0xFCA5A5);

/// What is written on any of the four.
pub const ON_MOOD: Color = Color::rgb(0x000000);

/// A card or a list standing on the black page: the session list, the week, the quote and
/// the settings. One step up from the page, the way the reference's dark cards are.
pub const CARD: Color = Color::rgb(0x1C1C1E);

/// What supports rather than says: a session's length, a section label, a chevron, the
/// destinations along the bottom that are not the one you are on.
pub const MUTED: Color = Color::rgb(0x8E8E93);

/// The one accent, and it is one of the four feelings: the powder blue the reference
/// fills a chosen worry with. It marks what is chosen, the destination you are on, and the
/// week's line.
pub const ACCENT: Color = POWDER;

#[cfg(test)]
mod tests {
    use super::*;

    /// The check-in's colours are the picture's, not the running design system's.
    ///
    /// `Color` rather than `Paint`, so a role cannot be written in this file at all; what
    /// this pins is that each value is still the one read off the reference.
    #[test]
    fn fr22_the_palette_is_the_reference_colours_rather_than_the_theme() {
        assert_eq!(PAGE.to_argb(), 0xff00_0000);
        assert_eq!(INK.to_argb(), 0xffff_ffff);
        assert_eq!(CHIP.to_argb(), 0xff26_2626);
        assert_eq!(MINT.to_argb(), 0xffa7_f3d0);
        assert_eq!(PINK.to_argb(), 0xfff9_a8d4);
        assert_eq!(POWDER.to_argb(), 0xffba_e6fd);
        assert_eq!(CORAL.to_argb(), 0xfffc_a5a5);
        assert_eq!(ON_MOOD.to_argb(), 0xff00_0000);
        assert_eq!(CARD.to_argb(), 0xff1c_1c1e);
        assert_eq!(MUTED.to_argb(), 0xff8e_8e93);
        assert_eq!(ACCENT.to_argb(), POWDER.to_argb());
    }
}
