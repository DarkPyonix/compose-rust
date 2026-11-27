//! The colours of the self-care reference, written out.
//!
//! Literals rather than roles, and that is the whole point of this file. The sample is one
//! reference picture: a black check-in under a pastel panel, light cyan for whatever is
//! chosen, and a pale pill under the destination you are on. A role would be resolved by
//! whichever design system is running, so every accent came out as that system's blue and
//! the quote card came out of the accent containers in a tint the picture does not have.
//!
//! The values are read off the reference rather than chosen. The four feelings are the
//! discs of the four faces, and the panel behind each face is a paler tint of its disc,
//! because the reference's panel is paler than the face drawn on it. They live here rather
//! than beside the widgets that use them so the sample has one place to change.

use compose_rust::ui::{Color, Paint};

/// The page. Black, as the check-in and the worry picker both are.
pub const PAGE: Paint = Paint::Literal(Color::rgb(0x00_0000));

/// Everything written on the page, the finished segments of the check-in's progress, and
/// the fill of the white pill that moves the check-in on.
pub const INK: Paint = Paint::Literal(Color::rgb(0xff_ffff));

/// What is written on anything pale: a feeling, a chosen worry, the pill under the
/// destination you are on, a light card and the white pill.
pub const ON_LIGHT: Paint = Paint::Literal(Color::rgb(0x00_0000));

/// What supports rather than says: the days under the week, "See all", a chevron.
pub const MUTED: Paint = Paint::Literal(Color::rgb(0x8e_8e93));

/// An answer that was not chosen, and the chip on a dark card.
pub const CHIP: Paint = Paint::Literal(Color::rgb(0x2c_2c2e));

/// A dark card on the page: a session card, the quote card and the settings list.
pub const CARD: Paint = Paint::Literal(Color::rgb(0x1c_1c1e));

/// The chip on a light card.
pub const LIGHT_CHIP: Paint = Paint::Literal(Color::rgb(0xef_efef));

/// The hairline between two settings, the outline of "Other", and the steps of the
/// check-in not yet reached.
pub const OUTLINE: Paint = Paint::Literal(Color::rgb(0x3a_3a3c));

/// The steps not yet reached, drawn over a feeling's panel. White at half strength, so it
/// is a lighter shade of whichever tint is behind it.
pub const PANEL_TRACK: Paint = Paint::Literal(Color::argb(0x80ff_ffff));

/// The one accent: a chosen worry, a play button, the quote's tile, the week's line and the
/// card the reference puts first under "Special for you".
pub const CYAN: Paint = Paint::Literal(Color::rgb(0x9f_e3f5));

/// The pill the destination you are on sits in, along the bottom.
pub const PILL: Paint = Paint::Literal(Color::rgb(0xe1_eff2));

/// The four feelings: the chosen answer's fill, which is the disc its face is drawn on.
pub const POWDER: Paint = Paint::Literal(Color::rgb(0xa8_c6f5));
pub const MINT: Paint = Paint::Literal(Color::rgb(0x7e_e8b6));
pub const PINK: Paint = Paint::Literal(Color::rgb(0xf7_9ad3));
pub const CORAL: Paint = Paint::Literal(Color::rgb(0xf7_9a8c));

/// The panel behind each face, paler than its disc so the face stands on it.
pub const POWDER_PANEL: Paint = Paint::Literal(Color::rgb(0xe3_ecfc));
pub const MINT_PANEL: Paint = Paint::Literal(Color::rgb(0xd9_fbea));
pub const PINK_PANEL: Paint = Paint::Literal(Color::rgb(0xfd_e1f3));
pub const CORAL_PANEL: Paint = Paint::Literal(Color::rgb(0xfd_e4e0));

#[cfg(test)]
mod tests {
    use super::*;

    const ALL: [Paint; 19] = [
        PAGE,
        INK,
        ON_LIGHT,
        MUTED,
        CHIP,
        CARD,
        LIGHT_CHIP,
        OUTLINE,
        PANEL_TRACK,
        CYAN,
        PILL,
        POWDER,
        MINT,
        PINK,
        CORAL,
        POWDER_PANEL,
        MINT_PANEL,
        PINK_PANEL,
        CORAL_PANEL,
    ];

    /// The sample's colours are the picture's, not the running design system's.
    ///
    /// Both halves are asserted. That each one is a literal is what keeps the design
    /// system out: this sample was drawn with roles once, and the week, the worry chips and
    /// the white pill came out in the theme's blue while the quote card came out of
    /// `PrimaryContainer`. That each value is the one written here is what keeps the sample
    /// matching the picture it was taken from once somebody edits this file.
    #[test]
    fn fr22_the_palette_is_the_reference_colours_rather_than_the_theme() {
        assert_eq!(argb(PAGE), 0xff00_0000);
        assert_eq!(argb(INK), 0xffff_ffff);
        assert_eq!(argb(ON_LIGHT), 0xff00_0000);
        assert_eq!(argb(MUTED), 0xff8e_8e93);
        assert_eq!(argb(CHIP), 0xff2c_2c2e);
        assert_eq!(argb(CARD), 0xff1c_1c1e);
        assert_eq!(argb(LIGHT_CHIP), 0xffef_efef);
        assert_eq!(argb(OUTLINE), 0xff3a_3a3c);
        assert_eq!(argb(PANEL_TRACK), 0x80ff_ffff);
        assert_eq!(argb(CYAN), 0xff9f_e3f5);
        assert_eq!(argb(PILL), 0xffe1_eff2);
        assert_eq!(argb(POWDER), 0xffa8_c6f5);
        assert_eq!(argb(MINT), 0xff7e_e8b6);
        assert_eq!(argb(PINK), 0xfff7_9ad3);
        assert_eq!(argb(CORAL), 0xfff7_9a8c);
        assert_eq!(argb(POWDER_PANEL), 0xffe3_ecfc);
        assert_eq!(argb(MINT_PANEL), 0xffd9_fbea);
        assert_eq!(argb(PINK_PANEL), 0xfffd_e1f3);
        assert_eq!(argb(CORAL_PANEL), 0xfffd_e4e0);
    }

    /// Nothing in the palette is the theme's blue, which is the fault this palette exists
    /// to fix. The reference's accent is a light cyan, whose blue barely leads its green;
    /// the system blue leads it by more than half the scale.
    #[test]
    fn fr22_no_colour_in_the_palette_is_the_theme_blue() {
        for paint in ALL {
            let value = argb(paint);
            assert_ne!(
                value & 0x00ff_ffff,
                0x000a_84ff,
                "{value:08x} is the dark system blue"
            );
            assert_ne!(
                value & 0x00ff_ffff,
                0x0000_7aff,
                "{value:08x} is the light system blue"
            );
            let (red, green, blue) = ((value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff);
            assert!(
                blue <= red.max(green) + 0x40,
                "{value:08x} is bluer than the reference ever gets",
            );
        }
    }

    /// Each feeling's panel is paler than its disc, which is what lets the face stand on
    /// it. A panel in the disc's own tint is a face with no edge, which is how it looked.
    #[test]
    fn fr22_each_panel_is_paler_than_its_disc() {
        let lightness = |paint: Paint| {
            let value = argb(paint);
            ((value >> 16) & 0xff) + ((value >> 8) & 0xff) + (value & 0xff)
        };
        for (disc, panel) in [
            (POWDER, POWDER_PANEL),
            (MINT, MINT_PANEL),
            (PINK, PINK_PANEL),
            (CORAL, CORAL_PANEL),
        ] {
            assert!(
                lightness(panel) > lightness(disc),
                "{:08x} is no paler than the disc {:08x} it holds",
                argb(panel),
                argb(disc)
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
