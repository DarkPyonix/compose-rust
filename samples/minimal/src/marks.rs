//! What the sheet draws rather than lays out: the shading on the hero's second disc, the
//! coin on the "Button / Description" chips, and the small glyph on the button tiles.
//!
//! The coin and the glyph are the sheet's own artwork rather than a meaning a design
//! system has an icon for, so they are drawn here in the sheet's colours, the way the
//! shop draws its mark.

use crate::palette;
use compose_rust::ui::{Brush, DrawList, DrawListBuilder, Paint, Stop, brush};

/// The hero's second disc: black on its left, where it overlaps the solid one, fading to
/// a light grey on its right.
///
/// Registered once. Every later call names the same brush and registers nothing.
pub fn hero_fade() -> Paint {
    brush(Brush::horizontal(vec![
        Stop::new(0.0, palette::INK_COLOR),
        Stop::new(1.0, palette::FADE_COLOR),
    ]))
}

/// The coin on a chip: a worn edge, a bronze ring and a gold face, in a square of `size`.
pub fn coin(size: f32) -> DrawList {
    let middle = size / 2.0;
    DrawListBuilder::with_capacity(3, 0)
        .circle(palette::COIN_EDGE, middle, middle, middle, 0.0)
        .circle(palette::COIN_RING, middle, middle, middle * 0.78, 0.0)
        .circle(palette::COIN_FACE, middle, middle, middle * 0.46, 0.0)
        .build()
}

/// The small glyph in the corner of a button tile: a rounded sheet with two eyes and two
/// lines on it, `width` across.
///
/// `sheet` is the glyph and `marks` is what is cut into it, so the same drawing reads on
/// a white tile (a black sheet with white marks) and on a black one (the other way round).
pub fn tile_glyph(width: f32, sheet: Paint, marks: Paint) -> DrawList {
    let height = width * 1.3;
    let eye = width * 0.2;
    let line = height * 0.07;
    DrawListBuilder::with_capacity(5, 0)
        .round_rect(sheet, 0.0, 0.0, width, height, width * 0.18, 0.0)
        .rect(marks, width * 0.24, height * 0.2, eye, eye, 0.0)
        .rect(marks, width * 0.56, height * 0.2, eye, eye, 0.0)
        .rect(marks, width * 0.24, height * 0.55, width * 0.52, line, 0.0)
        .rect(marks, width * 0.24, height * 0.7, width * 0.52, line, 0.0)
        .build()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every drawing here is painted in the sheet's own colours.
    #[test]
    fn fr22_every_drawing_is_painted_in_the_reference_colours() {
        let drawings = [
            coin(28.0),
            tile_glyph(14.0, palette::INK, palette::PAGE),
            tile_glyph(14.0, palette::PAGE, palette::INK),
        ];
        for drawing in drawings {
            for command in drawing.decode().expect("a drawing did not decode") {
                assert!(
                    matches!(command.paint(), Paint::Literal(_)),
                    "{command:?} is painted with something the design system would pick"
                );
            }
        }
    }

    /// The shaded disc is a brush rather than a colour, and asking for it twice names the
    /// same one, so a screen drawn every frame registers it once.
    #[test]
    fn fr22_the_shaded_disc_is_one_brush() {
        let first = hero_fade();
        assert!(matches!(first, Paint::Asset(_)), "{first:?} is not a brush");
        assert_eq!(hero_fade(), first);
    }
}
