//! Gradients and image fills: registered once, named by id wherever a colour can go.
//!
//! A brush does not fit where a colour fits. A `Paint` is two words on the wire and a
//! gradient is a list of stops, so the brush travels the way a picture does: registered
//! before anything draws it, kept by the Renderer, and named by id after that. That is
//! the road [`crate::asset`] already built, and this is a second kind of traffic on it
//! rather than a second road.
//!
//! The bytes are this module's own fixed layout and not a file format. No platform stores
//! a list of stops as a document, so a format would be a parser to write, to keep, and to
//! get wrong.

use crate::asset::register_owned;
use crate::schema::{AssetKind, Color, Paint, TileMode};

/// A stop: where along the run it sits, and what colour it is there.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Stop {
    /// Zero at the start of the run, one at the end.
    pub at: f32,
    pub color: Color,
}

impl Stop {
    pub const fn new(at: f32, color: Color) -> Self {
        Self { at, color }
    }
}

/// What a surface can be filled with beyond a flat colour.
///
/// The coordinates are fractions of the area being filled, never pixels. A gradient
/// described in pixels would be a gradient that only looks right at one size, and nothing
/// on this side of the boundary knows how large anything ended up.
#[derive(Clone, Debug, PartialEq)]
pub enum Brush {
    Linear {
        start: (f32, f32),
        end: (f32, f32),
        stops: Vec<Stop>,
        tile: TileMode,
    },
    Radial {
        center: (f32, f32),
        /// As a fraction of the larger side, so a circle stays a circle.
        radius: f32,
        stops: Vec<Stop>,
        tile: TileMode,
    },
    /// A registered picture, laid out as a fill.
    Image { asset: u32, tile: TileMode },
}

const KIND_LINEAR: u16 = 1;
const KIND_RADIAL: u16 = 2;
const KIND_IMAGE: u16 = 3;

impl Brush {
    /// Top to bottom, which is the gradient almost every screen wants.
    pub fn vertical(stops: Vec<Stop>) -> Self {
        Self::Linear {
            start: (0.5, 0.0),
            end: (0.5, 1.0),
            stops,
            tile: TileMode::Clamp,
        }
    }

    /// Left to right.
    pub fn horizontal(stops: Vec<Stop>) -> Self {
        Self::Linear {
            start: (0.0, 0.5),
            end: (1.0, 0.5),
            stops,
            tile: TileMode::Clamp,
        }
    }

    /// Out from the middle.
    pub fn radial(stops: Vec<Stop>) -> Self {
        Self::Radial {
            center: (0.5, 0.5),
            radius: 0.5,
            stops,
            tile: TileMode::Clamp,
        }
    }

    /// The bytes the Renderer reads, in this module's own layout.
    ///
    /// Fixed fields throughout: a kind, a stop count, four coordinates, and then eight
    /// bytes per stop. Nothing is skipped for a brush that does not use a coordinate,
    /// because a reader that has to know the kind before it knows where the stops start
    /// is a reader that can be walked off the end of a short buffer.
    pub fn to_bytes(&self) -> Vec<u8> {
        let (kind, coordinates, stops, tile) = match self {
            Self::Linear {
                start,
                end,
                stops,
                tile,
            } => (
                KIND_LINEAR,
                [start.0, start.1, end.0, end.1],
                stops.as_slice(),
                *tile,
            ),
            Self::Radial {
                center,
                radius,
                stops,
                tile,
            } => (
                KIND_RADIAL,
                [center.0, center.1, *radius, 0.0],
                stops.as_slice(),
                *tile,
            ),
            // The picture's id rides in the first coordinate's bits. It is a u32 in a
            // four-byte field, and giving the image case a layout of its own would mean
            // two layouts to read instead of one.
            Self::Image { asset, tile } => (
                KIND_IMAGE,
                [f32::from_bits(*asset), 0.0, 0.0, 0.0],
                &[][..],
                *tile,
            ),
        };
        let mut bytes = Vec::with_capacity(HEADER_LEN + stops.len() * STOP_LEN);
        bytes.extend_from_slice(&kind.to_le_bytes());
        bytes.extend_from_slice(&(stops.len() as u16).to_le_bytes());
        bytes.extend_from_slice(&(tile as u16).to_le_bytes());
        bytes.extend_from_slice(&0u16.to_le_bytes());
        for coordinate in coordinates {
            bytes.extend_from_slice(&coordinate.to_bits().to_le_bytes());
        }
        for stop in stops {
            bytes.extend_from_slice(&stop.at.to_bits().to_le_bytes());
            bytes.extend_from_slice(&stop.color.0.to_le_bytes());
        }
        bytes
    }
}

/// A kind, a stop count, a tile mode, two bytes of padding and four coordinates.
pub const HEADER_LEN: usize = 24;
/// A position and a colour.
pub const STOP_LEN: usize = 8;

/// Registers a brush and returns the `Paint` that names it.
///
/// Call it wherever the brush is used, including in a component body that runs every
/// render: the second call with the same brush returns the first call's id and registers
/// nothing, so a screen painted with the same gradient every frame registers it once and
/// allocates nothing after that.
///
/// ```ignore
/// let sky = brush(Brush::vertical(vec![
///     Stop::new(0.0, Color::rgb(0x4a90d9)),
///     Stop::new(1.0, Color::rgb(0xffffff)),
/// ]));
/// rsx! { Surface { background: sky, .. } }
/// ```
pub fn brush(brush: Brush) -> Paint {
    Paint::Asset(register_owned(AssetKind::Brush, brush.to_bytes()))
}
