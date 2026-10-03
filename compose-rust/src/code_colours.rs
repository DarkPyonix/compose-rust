//! The rule the code colours of Material 3 and Deepin are derived by.
//!
//! Neither system publishes a code editor scheme, so their code colours cannot be read off a
//! reference the way the other five systems' are. Writing them from memory is exactly what
//! the reference images exist to stop, so instead they are worked out from the system's own
//! base roles, which were read off its reference, by a fixed rule:
//!
//! - each syntax ink takes the value of one base role of the same system and scheme;
//! - the removed ink is the error role, and the added ink is the error role turned to a
//!   green hue (145 degrees) at the same HCT tone and chroma, because neither system has a
//!   role that means success and the two lines have to read with the same weight;
//! - the modified ink is the tertiary accent;
//! - a line background is its ink laid over the code panel at 12% in light and 20% in dark,
//!   and a word background the same way at 30% and 40%;
//! - a value that misses the contrast it is held to keeps its hue and chroma and moves in
//!   tone, one unit at a time, darker in light and lighter in dark, until it holds.
//!
//! The rule runs once, here, to check the tables: the values themselves are literals in
//! `tokens.rs` like every other system's, and the Renderer never computes a colour.
//!
//! HCT is hue and chroma from CAM16 under the standard viewing conditions, and tone from
//! CIE L*. The inverse searches CAM16's lightness for the requested tone and, where the
//! colour falls outside sRGB, the largest chroma that stays inside.

use crate::contrast::contrast;
use crate::schema::{Color, ColorRole, ColorScheme};
use crate::tokens::DesignTokenTable;

const WHITE: [f64; 3] = [95.047, 100.0, 108.883];

fn linearized(channel: u32) -> f64 {
    let value = f64::from(channel) / 255.0;
    let linear = if value <= 0.040_449_936 {
        value / 12.92
    } else {
        ((value + 0.055) / 1.055).powf(2.4)
    };
    linear * 100.0
}

fn delinearized(linear: f64) -> u32 {
    let value = linear / 100.0;
    let encoded = if value <= 0.003_130_8 {
        value * 12.92
    } else {
        1.055 * value.powf(1.0 / 2.4) - 0.055
    };
    (encoded * 255.0 + 0.5).floor().clamp(0.0, 255.0) as u32
}

fn lab_f(t: f64) -> f64 {
    let e = 216.0 / 24389.0;
    let kappa = 24389.0 / 27.0;
    if t > e {
        t.powf(1.0 / 3.0)
    } else {
        (kappa * t + 16.0) / 116.0
    }
}

fn lab_invf(ft: f64) -> f64 {
    let e = 216.0 / 24389.0;
    let kappa = 24389.0 / 27.0;
    let ft3 = ft * ft * ft;
    if ft3 > e {
        ft3
    } else {
        (116.0 * ft - 16.0) / kappa
    }
}

fn y_from_lstar(lstar: f64) -> f64 {
    100.0 * lab_invf((lstar + 16.0) / 116.0)
}

fn lstar_from_y(y: f64) -> f64 {
    lab_f(y / 100.0) * 116.0 - 16.0
}

fn signum(value: f64) -> f64 {
    if value > 0.0 {
        1.0
    } else if value < 0.0 {
        -1.0
    } else {
        0.0
    }
}

/// CAM16's viewing conditions: an average surround, a mid-grey background, and an adapting
/// luminance of a grey at L* 50.
struct Viewing {
    rgb_d: [f64; 3],
    aw: f64,
    n: f64,
    c: f64,
    z: f64,
    nbb: f64,
    ncb: f64,
    nc: f64,
    fl: f64,
}

impl Viewing {
    fn standard() -> Self {
        let [wx, wy, wz] = WHITE;
        let adapting = (200.0 / std::f64::consts::PI) * y_from_lstar(50.0) / 100.0;
        let background = 50.0;
        let surround = 2.0;
        let rw = wx * 0.401_288 + wy * 0.650_173 + wz * -0.051_461;
        let gw = wx * -0.250_268 + wy * 1.204_414 + wz * 0.045_854;
        let bw = wx * -0.002_079 + wy * 0.048_952 + wz * 0.953_127;
        let f = 0.8 + surround / 10.0;
        let c = if f >= 0.9 {
            0.59 + (0.69 - 0.59) * ((f - 0.9) * 10.0)
        } else {
            0.525 + (0.59 - 0.525) * ((f - 0.8) * 10.0)
        };
        let d = (f * (1.0 - (1.0 / 3.6) * ((-adapting - 42.0) / 92.0).exp())).clamp(0.0, 1.0);
        let rgb_d = [
            d * (100.0 / rw) + 1.0 - d,
            d * (100.0 / gw) + 1.0 - d,
            d * (100.0 / bw) + 1.0 - d,
        ];
        let k = 1.0 / (5.0 * adapting + 1.0);
        let k4 = k * k * k * k;
        let k4f = 1.0 - k4;
        let fl = k4 * adapting + 0.1 * k4f * k4f * (5.0 * adapting).powf(1.0 / 3.0);
        let n = y_from_lstar(background) / wy;
        let z = 1.48 + n.sqrt();
        let nbb = 0.725 / n.powf(0.2);
        let factor = |rgb_d: f64, white: f64| (fl * rgb_d * white / 100.0).powf(0.42);
        let adapted = |factor: f64| 400.0 * factor / (factor + 27.13);
        let aw = (2.0 * adapted(factor(rgb_d[0], rw))
            + adapted(factor(rgb_d[1], gw))
            + 0.05 * adapted(factor(rgb_d[2], bw)))
            * nbb;
        Self {
            rgb_d,
            aw,
            n,
            c,
            z,
            nbb,
            ncb: nbb,
            nc: f,
            fl,
        }
    }
}

/// Hue in degrees, chroma, and tone of an opaque colour.
pub(crate) fn hct(color: Color) -> (f64, f64, f64) {
    let viewing = Viewing::standard();
    let argb = color.to_argb();
    let r = linearized((argb >> 16) & 0xff);
    let g = linearized((argb >> 8) & 0xff);
    let b = linearized(argb & 0xff);
    let x = 0.412_338_95 * r + 0.357_620_64 * g + 0.180_510_42 * b;
    let y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
    let z = 0.019_321_41 * r + 0.119_163_82 * g + 0.950_344_78 * b;
    let rc = 0.401_288 * x + 0.650_173 * y - 0.051_461 * z;
    let gc = -0.250_268 * x + 1.204_414 * y + 0.045_854 * z;
    let bc = -0.002_079 * x + 0.048_952 * y + 0.953_127 * z;
    let adapt = |value: f64| {
        if value == 0.0 {
            return 0.0;
        }
        let af = (viewing.fl * value.abs() / 100.0).powf(0.42);
        signum(value) * 400.0 * af / (af + 27.13)
    };
    let ra = adapt(viewing.rgb_d[0] * rc);
    let ga = adapt(viewing.rgb_d[1] * gc);
    let ba = adapt(viewing.rgb_d[2] * bc);
    let a = (11.0 * ra + -12.0 * ga + ba) / 11.0;
    let bb = (ra + ga - 2.0 * ba) / 9.0;
    let u = (20.0 * ra + 20.0 * ga + 21.0 * ba) / 20.0;
    let p2 = (40.0 * ra + 20.0 * ga + ba) / 20.0;
    let mut hue = bb.atan2(a).to_degrees();
    if hue < 0.0 {
        hue += 360.0;
    } else if hue >= 360.0 {
        hue -= 360.0;
    }
    let ac = p2 * viewing.nbb;
    let j = 100.0 * (ac / viewing.aw).powf(viewing.c * viewing.z);
    let hue_prime = if hue < 20.14 { hue + 360.0 } else { hue };
    let e_hue = 0.25 * ((hue_prime.to_radians() + 2.0).cos() + 3.8);
    let p1 = 50000.0 / 13.0 * e_hue * viewing.nc * viewing.ncb;
    let t = p1 * a.hypot(bb) / (u + 0.305);
    let alpha = t.powf(0.9) * (1.64 - 0.29_f64.powf(viewing.n)).powf(0.73);
    let chroma = alpha * (j / 100.0).sqrt();
    (hue, chroma, lstar_from_y(y))
}

/// Linear RGB, 0 to 100, and Y, for a CAM16 lightness, chroma and hue.
fn linear_from_jch(viewing: &Viewing, j: f64, chroma: f64, hue: f64) -> [f64; 4] {
    let alpha = if chroma == 0.0 || j == 0.0 {
        0.0
    } else {
        chroma / (j / 100.0).sqrt()
    };
    let t = (alpha / (1.64 - 0.29_f64.powf(viewing.n)).powf(0.73)).powf(1.0 / 0.9);
    let radians = hue.to_radians();
    let e_hue = 0.25 * ((radians + 2.0).cos() + 3.8);
    let ac = viewing.aw * (j / 100.0).powf(1.0 / viewing.c / viewing.z);
    let p1 = e_hue * (50000.0 / 13.0) * viewing.nc * viewing.ncb;
    let p2 = ac / viewing.nbb;
    let (sin, cos) = (radians.sin(), radians.cos());
    let gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * cos + 108.0 * t * sin);
    let a = gamma * cos;
    let b = gamma * sin;
    let ra = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0;
    let ga = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0;
    let ba = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0;
    let unadapt = |value: f64| {
        if value == 0.0 {
            return 0.0;
        }
        let base = (27.13 * value.abs() / (400.0 - value.abs())).max(0.0);
        signum(value) * (100.0 / viewing.fl) * base.powf(1.0 / 0.42)
    };
    let rf = unadapt(ra) / viewing.rgb_d[0];
    let gf = unadapt(ga) / viewing.rgb_d[1];
    let bf = unadapt(ba) / viewing.rgb_d[2];
    let x = 1.862_067_86 * rf - 1.011_254_63 * gf + 0.149_186_77 * bf;
    let y = 0.387_526_54 * rf + 0.621_447_44 * gf - 0.008_973_98 * bf;
    let z = -0.015_841_50 * rf - 0.034_122_94 * gf + 1.049_964_44 * bf;
    [
        3.241_377_479_238_868_5 * x - 1.537_665_240_285_185_1 * y - 0.498_853_668_462_680_53 * z,
        -0.969_145_251_300_532_1 * x + 1.875_885_345_106_787_2 * y + 0.041_565_856_169_120_61 * z,
        0.055_620_936_896_913_05 * x - 0.203_955_245_647_421_23 * y + 1.057_179_911_122_033_5 * z,
        y,
    ]
}

/// The linear colour of this hue and chroma whose tone is `tone`, or None outside sRGB.
fn at_tone(viewing: &Viewing, hue: f64, chroma: f64, tone: f64) -> Option<[f64; 3]> {
    let target = y_from_lstar(tone);
    let (mut low, mut high) = (0.0, 100.0);
    for _ in 0..64 {
        let middle = (low + high) / 2.0;
        if linear_from_jch(viewing, middle, chroma, hue)[3] < target {
            low = middle;
        } else {
            high = middle;
        }
    }
    let [r, g, b, _] = linear_from_jch(viewing, (low + high) / 2.0, chroma, hue);
    let epsilon = 1e-7;
    if r.min(g).min(b) < -epsilon || r.max(g).max(b) > 100.0 + epsilon {
        return None;
    }
    Some([r, g, b])
}

/// The colour with this hue, chroma and tone, or the most chromatic one inside sRGB.
pub(crate) fn from_hct(hue: f64, chroma: f64, tone: f64) -> Color {
    if tone <= 0.0 {
        return Color::rgb(0x000000);
    }
    if tone >= 100.0 {
        return Color::rgb(0xffffff);
    }
    let viewing = Viewing::standard();
    let linear = at_tone(&viewing, hue, chroma, tone).unwrap_or_else(|| {
        let (mut low, mut high) = (0.0, chroma);
        let mut best = at_tone(&viewing, hue, 0.0, tone).unwrap_or([0.0; 3]);
        for _ in 0..48 {
            let middle = (low + high) / 2.0;
            match at_tone(&viewing, hue, middle, tone) {
                Some(found) => {
                    low = middle;
                    best = found;
                }
                None => high = middle,
            }
        }
        best
    });
    let [r, g, b] = linear.map(|channel| delinearized(channel.clamp(0.0, 100.0)));
    Color::rgb((r << 16) | (g << 8) | b)
}

/// `ink` laid over `under` at `alpha`, as the opaque colour a reader sees.
pub(crate) fn over(ink: Color, under: Color, alpha: f64) -> Color {
    let mut out = 0;
    for shift in [16, 8, 0] {
        let fg = f64::from((ink.to_argb() >> shift) & 0xff);
        let bg = f64::from((under.to_argb() >> shift) & 0xff);
        out |= ((fg * alpha + bg * (1.0 - alpha) + 0.5).floor() as u32) << shift;
    }
    Color::rgb(out)
}

/// Moves `color` in tone, one unit at a time, until `passes` holds. Darker in light,
/// lighter in dark. Hue and chroma stay.
pub(crate) fn shift_until(color: Color, dark: bool, passes: impl Fn(Color) -> bool) -> Color {
    if passes(color) {
        return color;
    }
    let (hue, chroma, tone) = hct(color);
    let step = if dark { 1.0 } else { -1.0 };
    (1..=100)
        .map(|k| from_hct(hue, chroma, tone + step * f64::from(k)))
        .find(|candidate| passes(*candidate))
        .unwrap_or(color)
}

/// The base role each syntax ink takes its value from.
pub(crate) const DERIVED_FROM: [(ColorRole, ColorRole); 15] = [
    (ColorRole::SyntaxKeyword, ColorRole::Primary),
    (ColorRole::SyntaxString, ColorRole::Tertiary),
    (ColorRole::SyntaxComment, ColorRole::OnSurfaceVariant),
    (ColorRole::SyntaxNumber, ColorRole::OnTertiaryContainer),
    (ColorRole::SyntaxConstant, ColorRole::OnTertiaryContainer),
    (ColorRole::SyntaxType, ColorRole::Secondary),
    (ColorRole::SyntaxFunction, ColorRole::OnPrimaryContainer),
    (ColorRole::SyntaxVariable, ColorRole::OnSurface),
    (ColorRole::SyntaxProperty, ColorRole::OnSecondaryContainer),
    (ColorRole::SyntaxOperator, ColorRole::OnSurfaceVariant),
    (ColorRole::SyntaxPunctuation, ColorRole::OnSurfaceVariant),
    (ColorRole::SyntaxTag, ColorRole::Primary),
    (ColorRole::SyntaxAttribute, ColorRole::Secondary),
    (ColorRole::SyntaxEscape, ColorRole::Error),
    (ColorRole::SyntaxMacro, ColorRole::Secondary),
];

/// The hue the added ink is turned to.
pub(crate) const ADDED_HUE: f64 = 145.0;

/// The 22 code colours of one system and scheme, worked out from its base roles.
///
/// Returned in role tag order, from `SyntaxKeyword` to `DiffRemovedEmphasis`.
pub(crate) fn derive(table: &DesignTokenTable, scheme: ColorScheme) -> Vec<(ColorRole, Color)> {
    let dark = scheme == ColorScheme::Dark;
    let panel = table.color(ColorRole::SurfaceContainer, scheme);
    let reads = |ink: Color| contrast(ink, panel) >= 4.5;
    let mut out: Vec<(ColorRole, Color)> = DERIVED_FROM
        .iter()
        .map(|(role, from)| (*role, shift_until(table.color(*from, scheme), dark, reads)))
        .collect();
    let (line, word) = if dark { (0.20, 0.40) } else { (0.12, 0.30) };
    let error = table.color(ColorRole::Error, scheme);
    let (_, chroma, tone) = hct(error);
    let added = from_hct(ADDED_HUE, chroma, tone);
    let settle = |ink: Color| {
        shift_until(ink, dark, |candidate| {
            contrast(candidate, over(candidate, panel, line)) >= 4.5
        })
    };
    let added = settle(added);
    let removed = settle(error);
    out.push((ColorRole::DiffAdded, added));
    out.push((ColorRole::DiffRemoved, removed));
    out.push((
        ColorRole::DiffModified,
        table.color(ColorRole::Tertiary, scheme),
    ));
    out.push((ColorRole::DiffAddedContainer, over(added, panel, line)));
    out.push((ColorRole::DiffRemovedContainer, over(removed, panel, line)));
    out.push((ColorRole::DiffAddedEmphasis, over(added, panel, word)));
    out.push((ColorRole::DiffRemovedEmphasis, over(removed, panel, word)));
    out.sort_by_key(|(role, _)| *role as u16);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The conversion holds a colour still: out to HCT and back is the same colour. Every
    /// derived value rests on this, so it is checked on its own first.
    #[test]
    fn fr13_1_3_hct_round_trips_an_opaque_colour() {
        for argb in [
            0xb3261e, 0x6750a4, 0x0081ff, 0x123456, 0xf2b8b5, 0x00ff00, 0x777777,
        ] {
            let color = Color::rgb(argb);
            let (hue, chroma, tone) = hct(color);
            assert_eq!(
                from_hct(hue, chroma, tone),
                color,
                "#{argb:06x} came back as something else"
            );
        }
    }

    /// The published HCT of Material's baseline error, which pins the viewing conditions.
    #[test]
    fn fr13_1_3_hct_agrees_with_the_published_error_tone() {
        let (hue, chroma, tone) = hct(Color::rgb(0xb3261e));
        assert!((hue - 25.98).abs() < 0.05, "hue {hue}");
        assert!((chroma - 76.33).abs() < 0.05, "chroma {chroma}");
        assert!((tone - 39.69).abs() < 0.05, "tone {tone}");
    }
}
