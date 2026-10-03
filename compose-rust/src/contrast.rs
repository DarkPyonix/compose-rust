//! How far apart two colours are, in the two ways the colour roles are held to: contrast
//! for an ink on a fill, and separation for a fill that has to be seen on another.
//!
//! The design system tables are tested against these, an application's palette is checked
//! against them, and the Renderer chooses an ink with the same arithmetic.

use crate::schema::{Color, ColorRole};

/// The fifteen syntax inks.
pub const SYNTAX_ROLES: [ColorRole; 15] = [
    ColorRole::SyntaxKeyword,
    ColorRole::SyntaxString,
    ColorRole::SyntaxComment,
    ColorRole::SyntaxNumber,
    ColorRole::SyntaxConstant,
    ColorRole::SyntaxType,
    ColorRole::SyntaxFunction,
    ColorRole::SyntaxVariable,
    ColorRole::SyntaxProperty,
    ColorRole::SyntaxOperator,
    ColorRole::SyntaxPunctuation,
    ColorRole::SyntaxTag,
    ColorRole::SyntaxAttribute,
    ColorRole::SyntaxEscape,
    ColorRole::SyntaxMacro,
];

/// Each diff ink with its line background and its word background.
pub const DIFF_ROLES: [(ColorRole, ColorRole, ColorRole); 2] = [
    (
        ColorRole::DiffAdded,
        ColorRole::DiffAddedContainer,
        ColorRole::DiffAddedEmphasis,
    ),
    (
        ColorRole::DiffRemoved,
        ColorRole::DiffRemovedContainer,
        ColorRole::DiffRemovedEmphasis,
    ),
];

/// How far apart, summed over three eight bit channels, two fills have to be before one
/// can be seen on the other. The same figure the system tables are tested against.
pub const MIN_SEPARATION: u32 = 24;

fn channel(value: u32) -> f64 {
    let value = f64::from(value) / 255.0;
    if value <= 0.040_45 {
        value / 12.92
    } else {
        ((value + 0.055) / 1.055).powf(2.4)
    }
}

/// Relative luminance, as WCAG defines it.
pub fn luminance(color: Color) -> f64 {
    let argb = color.to_argb();
    0.2126 * channel((argb >> 16) & 0xff)
        + 0.7152 * channel((argb >> 8) & 0xff)
        + 0.0722 * channel(argb & 0xff)
}

/// The WCAG contrast ratio of two opaque colours, from 1 to 21.
pub fn contrast(first: Color, second: Color) -> f64 {
    let (a, b) = (luminance(first), luminance(second));
    let (high, low) = if a > b { (a, b) } else { (b, a) };
    (high + 0.05) / (low + 0.05)
}

/// Summed channel distance in eight bit terms.
pub fn apart(first: Color, second: Color) -> u32 {
    [16, 8, 0]
        .into_iter()
        .map(|shift| {
            let a = (first.to_argb() >> shift) & 0xff;
            let b = (second.to_argb() >> shift) & 0xff;
            a.abs_diff(b)
        })
        .sum()
}
