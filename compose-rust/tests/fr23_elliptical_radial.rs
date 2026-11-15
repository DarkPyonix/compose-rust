//! An elliptical radial gradient: the radial brush's fourth coordinate is its vertical
//! radius. No new record and no new asset kind, so a circle written before there was an
//! ellipse still reads as a circle.

use compose_rust::schema::Color;
use compose_rust::{Brush, Stop, TileMode};

const HEADER_LEN: usize = compose_rust::brush::HEADER_LEN;

fn coordinate(bytes: &[u8], index: usize) -> f32 {
    let at = 8 + 4 * index;
    f32::from_bits(u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap()))
}

fn stops() -> Vec<Stop> {
    vec![
        Stop::new(0.0, Color::rgb(0xff0000)),
        Stop::new(1.0, Color::rgb(0x0000ff)),
    ]
}

/// The horizontal radius rides in the third coordinate as it always has, and the vertical
/// one in the fourth, which a circle leaves at zero.
#[test]
fn fr23_elliptical_radial_gradient_writes_radius_y_in_the_fourth_coordinate() {
    let ellipse = Brush::Radial {
        center: (0.25, 0.75),
        radius: 0.5,
        radius_y: 0.125,
        stops: stops(),
        tile: TileMode::Clamp,
    }
    .to_bytes();
    assert_eq!(
        ellipse.len(),
        HEADER_LEN + 2 * compose_rust::brush::STOP_LEN
    );
    assert_eq!(
        u16::from_le_bytes([ellipse[0], ellipse[1]]),
        2,
        "a radial brush"
    );
    assert_eq!(
        (0..4).map(|i| coordinate(&ellipse, i)).collect::<Vec<_>>(),
        vec![0.25, 0.75, 0.5, 0.125],
    );

    let shorthand = Brush::elliptical(0.5, 0.125, stops()).to_bytes();
    assert_eq!(coordinate(&shorthand, 2), 0.5);
    assert_eq!(coordinate(&shorthand, 3), 0.125);
}

/// `Brush::radial` is the circle it always was: its fourth coordinate is zero, so its bytes
/// are what they were before the coordinate had a meaning.
#[test]
fn fr23_a_circle_still_sends_zero_as_its_vertical_radius() {
    let circle = Brush::radial(stops()).to_bytes();
    assert_eq!(coordinate(&circle, 2), 0.5);
    assert_eq!(coordinate(&circle, 3).to_bits(), 0.0f32.to_bits());
}
