//! An application's own colours.
//!
//! A theme picks a design system and a scheme, and until this existed that was the whole of
//! what an application could say about colour. The only way to paint a brand colour was a
//! literal on every node, and a literal is a colour the design system never sees: the
//! pressed layer, the disabled fade, the tint of glass and the switch to dark all carried on
//! without it.
//!
//! A [`Palette`] replaces the value of colour roles, one role and one scheme at a time, and
//! nothing else. The shape, type, spacing, elevation, motion and component rules stay the
//! design system's, so the same brand orange is a large rounded ripple under Material 3 and
//! a glass capsule under Liquid Glass. What crosses the boundary is a short list of role
//! values, never a token table.
//!
//! Both schemes travel at once. When the platform turns dark the Renderer chooses from what
//! it already holds, and the Host hears nothing.

use crate::contrast::{DIFF_ROLES, MIN_SEPARATION, SYNTAX_ROLES, apart, contrast};
use crate::schema::{COLOR_ROLE_COUNT, Color, ColorRole, ColorScheme, DesignSystem};
use crate::tokens::table;

/// Bytes of one palette entry on the wire: the role, the scheme, and the colour.
pub const PALETTE_ENTRY_LEN: usize = 8;

/// The colours one application gives some of the roles, per scheme.
///
/// A role the application did not give keeps the design system's value, separately in each
/// scheme: giving `Primary` for light only leaves dark `Primary` to the system.
///
/// Built in a `const`, so a theme that carries it stays `Copy` and can be a `const` too:
///
/// ```
/// use compose_rust::{Color, ColorRole, DesignSystem, Palette, Theme};
///
/// const EMBER: Palette = Palette::new()
///     .with(ColorRole::Primary, Color::rgb(0xE8590C), Color::rgb(0xFF8A4C))
///     .with(ColorRole::OnPrimary, Color::rgb(0xFFFFFF), Color::rgb(0x2B0E00))
///     .with(ColorRole::Background, Color::rgb(0xFFFBF8), Color::rgb(0x141110));
///
/// const THEME: Theme = Theme::adaptive(DesignSystem::Material3).with_palette(&EMBER);
/// ```
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Palette {
    light: [Option<Color>; COLOR_ROLE_COUNT],
    dark: [Option<Color>; COLOR_ROLE_COUNT],
}

impl Default for Palette {
    fn default() -> Self {
        Self::new()
    }
}

/// One role's colour in one scheme, as it travels.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PaletteEntry {
    pub role: ColorRole,
    /// `Light` or `Dark`. Never `FollowSystem`, which is a way of choosing, not a scheme.
    pub scheme: ColorScheme,
    pub color: Color,
}

impl Palette {
    /// A palette that changes nothing.
    pub const fn new() -> Self {
        Self {
            light: [None; COLOR_ROLE_COUNT],
            dark: [None; COLOR_ROLE_COUNT],
        }
    }

    /// Gives a role its colour in both schemes.
    ///
    /// Two values rather than one, because one colour used for both is almost always wrong
    /// in one of them: a deep orange chosen for a light page sinks into a dark one.
    pub const fn with(mut self, role: ColorRole, light: Color, dark: Color) -> Self {
        self.light[role as usize - 1] = Some(light);
        self.dark[role as usize - 1] = Some(dark);
        self
    }

    /// Gives a role its colour in the light scheme only. Dark keeps the system's.
    pub const fn with_light(mut self, role: ColorRole, light: Color) -> Self {
        self.light[role as usize - 1] = Some(light);
        self
    }

    /// Gives a role its colour in the dark scheme only. Light keeps the system's.
    pub const fn with_dark(mut self, role: ColorRole, dark: Color) -> Self {
        self.dark[role as usize - 1] = Some(dark);
        self
    }

    /// The colour this palette gives a role in a scheme, or None where it leaves the
    /// system's. `FollowSystem` is not a scheme and has no answer.
    pub const fn get(&self, role: ColorRole, scheme: ColorScheme) -> Option<Color> {
        match scheme {
            ColorScheme::Light => self.light[role as usize - 1],
            ColorScheme::Dark => self.dark[role as usize - 1],
            ColorScheme::FollowSystem => None,
        }
    }

    /// Every value this palette gives, in role order with light before dark.
    pub fn entries(&self) -> impl Iterator<Item = PaletteEntry> + '_ {
        crate::schema::COLOR_ROLE_SCHEMA
            .iter()
            .flat_map(move |variant| {
                let role = ColorRole::try_from(variant.tag).expect("a role from the role schema");
                [ColorScheme::Light, ColorScheme::Dark]
                    .into_iter()
                    .filter_map(move |scheme| {
                        self.get(role, scheme).map(|color| PaletteEntry {
                            role,
                            scheme,
                            color,
                        })
                    })
            })
    }

    /// How many values this palette gives.
    pub fn len(&self) -> usize {
        self.light.iter().chain(self.dark.iter()).flatten().count()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Writes the entries as the wire carries them, eight bytes each.
    pub fn write_into(&self, out: &mut Vec<u8>) {
        for entry in self.entries() {
            out.extend_from_slice(&(entry.role as u16).to_le_bytes());
            out.extend_from_slice(&(entry.scheme as u16).to_le_bytes());
            out.extend_from_slice(&entry.color.to_argb().to_le_bytes());
        }
    }

    /// Every role's colour in one scheme of one design system, with this palette on top.
    ///
    /// This is the Renderer's resolution, written out where a test can reach it. A role
    /// the palette gives is that value. A role it does not give is the system's, except
    /// for one case: a fill the palette gives whose ink it does not. The system's ink is
    /// kept if it still holds the contrast that pair is held to on the new fill, and
    /// otherwise the ink is white or black, whichever holds more. Orange takes the system's
    /// white where white reads on it, and a yellow brand takes black.
    ///
    /// Only that direction. An ink given without its fill was meant, and if it does not
    /// read the thing to correct is the palette.
    pub fn resolve(&self, system: DesignSystem, scheme: ColorScheme) -> [Color; COLOR_ROLE_COUNT] {
        let base = table(system);
        let mut out = [Color::default(); COLOR_ROLE_COUNT];
        for (index, slot) in out.iter_mut().enumerate() {
            let role = base.colors[index].role;
            *slot = self
                .get(role, scheme)
                .unwrap_or_else(|| base.color(role, scheme));
        }
        for (fill, ink, required) in INK_PAIRS {
            if self.get(fill, scheme).is_none() || self.get(ink, scheme).is_some() {
                continue;
            }
            out[ink as usize - 1] =
                ink_for(out[fill as usize - 1], out[ink as usize - 1], required);
        }
        out
    }

    /// The pairs this palette, laid over a design system's table, leaves short of the
    /// contrast or the separation they are held to. Empty where nothing is.
    ///
    /// The bounds are the ones the system tables themselves are tested against: accent
    /// inks 3:1 on their fills, reading inks and container inks 4.5:1, every syntax ink
    /// 4.5:1 on the code panel, each diff ink 4.5:1 on its own line background and the body
    /// ink 4.5:1 on both, and a container, a line background or a word background that has
    /// to be told apart from what is behind it actually is. An application calls this from
    /// its own tests, once for each of the seven systems.
    pub fn check(&self, system: DesignSystem) -> Vec<PaletteViolation> {
        let mut found = Vec::new();
        for scheme in [ColorScheme::Light, ColorScheme::Dark] {
            let colors = self.resolve(system, scheme);
            let color = |role: ColorRole| colors[role as usize - 1];
            let mut contrast_pair = |ink: ColorRole, fill: ColorRole, required: f64| {
                let ratio = contrast(color(ink), color(fill));
                if ratio < required {
                    found.push(PaletteViolation::Contrast {
                        scheme,
                        ink,
                        fill,
                        ratio,
                        required,
                    });
                }
            };
            for (fill, ink, required) in INK_PAIRS {
                contrast_pair(ink, fill, required);
            }
            contrast_pair(ColorRole::OnSurface, ColorRole::SurfaceContainer, 4.5);
            for ink in SYNTAX_ROLES {
                contrast_pair(ink, ColorRole::SurfaceContainer, 4.5);
            }
            for (ink, line, _) in DIFF_ROLES {
                contrast_pair(ink, line, 4.5);
                contrast_pair(ColorRole::OnSurface, line, 4.5);
            }
            let mut apart_pair = |first: ColorRole, second: ColorRole| {
                let distance = apart(color(first), color(second));
                if distance < MIN_SEPARATION {
                    found.push(PaletteViolation::Indistinct {
                        scheme,
                        first,
                        second,
                        distance,
                    });
                }
            };
            for container in [
                ColorRole::SurfaceContainer,
                ColorRole::PrimaryContainer,
                ColorRole::SecondaryContainer,
                ColorRole::TertiaryContainer,
            ] {
                apart_pair(container, ColorRole::Background);
            }
            for (_, line, word) in DIFF_ROLES {
                apart_pair(word, line);
            }
        }
        found
    }
}

/// A pair a palette leaves short, as [`Palette::check`] reports it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum PaletteViolation {
    /// `ink` on `fill` measures `ratio`, under the `required` bound.
    Contrast {
        scheme: ColorScheme,
        ink: ColorRole,
        fill: ColorRole,
        ratio: f64,
        required: f64,
    },
    /// Two colours that have to be told apart are `distance` apart, summed over the three
    /// eight bit channels.
    Indistinct {
        scheme: ColorScheme,
        first: ColorRole,
        second: ColorRole,
        distance: u32,
    },
}

impl std::fmt::Display for PaletteViolation {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Contrast {
                scheme,
                ink,
                fill,
                ratio,
                required,
            } => write!(
                formatter,
                "{scheme:?}: {ink:?} on {fill:?} is {ratio:.2}:1, under the {required}:1 it needs to be read"
            ),
            Self::Indistinct {
                scheme,
                first,
                second,
                distance,
            } => write!(
                formatter,
                "{scheme:?}: {first:?} and {second:?} are {distance} apart, so one cannot be seen on the other"
            ),
        }
    }
}

/// Every fill whose ink the Renderer may choose, with the contrast that pair is held to.
///
/// Accents hold 3:1, the bound a label on a control is held to. Containers and the reading
/// surfaces hold 4.5:1, because a paragraph lands on them.
pub const INK_PAIRS: [(ColorRole, ColorRole, f64); 10] = [
    (ColorRole::Primary, ColorRole::OnPrimary, 3.0),
    (ColorRole::Secondary, ColorRole::OnSecondary, 3.0),
    (ColorRole::Tertiary, ColorRole::OnTertiary, 3.0),
    (ColorRole::Error, ColorRole::OnError, 3.0),
    (
        ColorRole::PrimaryContainer,
        ColorRole::OnPrimaryContainer,
        4.5,
    ),
    (
        ColorRole::SecondaryContainer,
        ColorRole::OnSecondaryContainer,
        4.5,
    ),
    (
        ColorRole::TertiaryContainer,
        ColorRole::OnTertiaryContainer,
        4.5,
    ),
    (ColorRole::Surface, ColorRole::OnSurface, 4.5),
    (ColorRole::SurfaceVariant, ColorRole::OnSurfaceVariant, 4.5),
    (ColorRole::Background, ColorRole::OnBackground, 4.5),
];

/// The ink for a fill the application changed and whose ink it left alone.
pub fn ink_for(fill: Color, system_ink: Color, required: f64) -> Color {
    if contrast(system_ink, fill) >= required {
        return system_ink;
    }
    let white = Color::rgb(0xffffff);
    let black = Color::rgb(0x000000);
    if contrast(white, fill) >= contrast(black, fill) {
        white
    } else {
        black
    }
}

/// What is wrong with one entry of a palette that arrived over the wire.
///
/// Each is reported, and the entry is left out. The rest of the palette still applies:
/// throwing the whole theme away for one bad entry would open the application with no
/// colours at all.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PaletteProblem {
    /// The palette is not a whole number of entries. The trailing bytes are ignored.
    Length {
        len: usize,
    },
    UnknownRole {
        index: usize,
        tag: u16,
    },
    /// A scheme that is neither light nor dark, `FollowSystem` included.
    InvalidScheme {
        index: usize,
        scheme: u16,
    },
    /// The same role and scheme a second time. The first one stands.
    Duplicate {
        index: usize,
        role: ColorRole,
        scheme: ColorScheme,
    },
}

/// Reads a palette off the wire, keeping every entry that is sound.
pub fn decode_palette(bytes: &[u8]) -> (Palette, Vec<PaletteProblem>) {
    let mut palette = Palette::new();
    let mut problems = Vec::new();
    if bytes.len() % PALETTE_ENTRY_LEN != 0 {
        problems.push(PaletteProblem::Length { len: bytes.len() });
    }
    for (index, entry) in bytes.chunks_exact(PALETTE_ENTRY_LEN).enumerate() {
        let tag = u16::from_le_bytes([entry[0], entry[1]]);
        let scheme_tag = u16::from_le_bytes([entry[2], entry[3]]);
        let argb = u32::from_le_bytes([entry[4], entry[5], entry[6], entry[7]]);
        let Ok(role) = ColorRole::try_from(tag) else {
            problems.push(PaletteProblem::UnknownRole { index, tag });
            continue;
        };
        let scheme = match ColorScheme::try_from(scheme_tag) {
            Ok(scheme @ (ColorScheme::Light | ColorScheme::Dark)) => scheme,
            _ => {
                problems.push(PaletteProblem::InvalidScheme {
                    index,
                    scheme: scheme_tag,
                });
                continue;
            }
        };
        if palette.get(role, scheme).is_some() {
            problems.push(PaletteProblem::Duplicate {
                index,
                role,
                scheme,
            });
            continue;
        }
        let color = Color::argb(argb);
        palette = match scheme {
            ColorScheme::Light => palette.with_light(role, color),
            _ => palette.with_dark(role, color),
        };
    }
    (palette, problems)
}
