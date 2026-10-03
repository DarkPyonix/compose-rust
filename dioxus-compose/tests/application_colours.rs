//! An application's own colours: what a palette changes, what it leaves to the design
//! system, how it travels, and what is said about one that cannot be read.

use dioxus_compose::boundary::palette_report;
use dioxus_compose::palette::{
    PALETTE_ENTRY_LEN, PaletteProblem, PaletteViolation, decode_palette, ink_for,
};
use dioxus_compose::protocol::{BatchEncoder, Mutation, decode_batch};
use dioxus_compose::schema::DESIGN_SYSTEM_SCHEMA;
use dioxus_compose::tokens::table;
use dioxus_compose::{Color, ColorRole, ColorScheme, DesignSystem, Palette, Theme};

const EMBER: Palette = Palette::new()
    .with(
        ColorRole::Primary,
        Color::rgb(0xE8590C),
        Color::rgb(0xFF8A4C),
    )
    .with(
        ColorRole::OnPrimary,
        Color::rgb(0xFFFFFF),
        Color::rgb(0x2B0E00),
    )
    .with(
        ColorRole::Background,
        Color::rgb(0xFFFBF8),
        Color::rgb(0x141110),
    );

fn systems() -> Vec<DesignSystem> {
    DESIGN_SYSTEM_SCHEMA
        .iter()
        .map(|variant| DesignSystem::try_from(variant.tag).unwrap())
        .collect()
}

/// A theme is still a value that can be written down at compile time with a palette on it.
const THEME: Theme = Theme::adaptive(DesignSystem::Material3).with_palette(&EMBER);

/// The palette's value is what a role resolves to in every system, in the scheme it was
/// given for, while the shape, type and spacing tables are untouched.
#[test]
fn fr14_10_the_palette_value_is_what_a_role_resolves_to_in_every_system() {
    assert_eq!(THEME.palette, Some(&EMBER));
    for system in systems() {
        let light = EMBER.resolve(system, ColorScheme::Light);
        let dark = EMBER.resolve(system, ColorScheme::Dark);
        assert_eq!(light[ColorRole::Primary as usize - 1], Color::rgb(0xE8590C));
        assert_eq!(dark[ColorRole::Primary as usize - 1], Color::rgb(0xFF8A4C));
        assert_eq!(
            light[ColorRole::Background as usize - 1],
            Color::rgb(0xFFFBF8)
        );
    }
}

/// A role the palette does not give is the system's, and a role given for light only is
/// the system's in dark.
#[test]
fn fr14_10_a_role_left_unsaid_is_the_systems_in_each_scheme() {
    let light_only = Palette::new().with_light(ColorRole::Secondary, Color::rgb(0x123456));
    for system in systems() {
        let base = table(system);
        for scheme in [ColorScheme::Light, ColorScheme::Dark] {
            let resolved = EMBER.resolve(system, scheme);
            assert_eq!(
                resolved[ColorRole::Outline as usize - 1],
                base.color(ColorRole::Outline, scheme),
                "{system:?} {scheme:?}: Outline was not given and moved anyway"
            );
        }
        assert_eq!(
            light_only.resolve(system, ColorScheme::Light)[ColorRole::Secondary as usize - 1],
            Color::rgb(0x123456)
        );
        assert_eq!(
            light_only.resolve(system, ColorScheme::Dark)[ColorRole::Secondary as usize - 1],
            base.color(ColorRole::Secondary, ColorScheme::Dark),
            "{system:?}: a light only value leaked into dark"
        );
    }
}

/// A fill given without its ink gets an ink that reads on it: black on a light yellow,
/// white on a deep navy, and at least the 3:1 an accent label is held to.
#[test]
fn fr14_10_an_ink_left_unsaid_is_chosen_to_read_on_the_new_fill() {
    let yellow = Palette::new().with(
        ColorRole::Primary,
        Color::rgb(0xFFE14D),
        Color::rgb(0xFFE14D),
    );
    let navy = Palette::new().with(
        ColorRole::Primary,
        Color::rgb(0x0B1F4B),
        Color::rgb(0x0B1F4B),
    );
    for system in systems() {
        for scheme in [ColorScheme::Light, ColorScheme::Dark] {
            let on_yellow = yellow.resolve(system, scheme)[ColorRole::OnPrimary as usize - 1];
            let on_navy = navy.resolve(system, scheme)[ColorRole::OnPrimary as usize - 1];
            assert!(dioxus_compose::contrast::contrast(on_yellow, Color::rgb(0xFFE14D)) >= 3.0);
            assert!(dioxus_compose::contrast::contrast(on_navy, Color::rgb(0x0B1F4B)) >= 3.0);
            assert_ne!(
                on_yellow, on_navy,
                "{system:?} {scheme:?} put the same ink on yellow and on navy"
            );
        }
    }
    // Where the system's own ink still reads, it is kept.
    assert_eq!(
        ink_for(Color::rgb(0xE8590C), Color::rgb(0xFFFFFF), 3.0),
        Color::rgb(0xFFFFFF)
    );
    assert_eq!(
        ink_for(Color::rgb(0xFFE14D), Color::rgb(0xFFFFFF), 3.0),
        Color::rgb(0x000000)
    );
    assert_eq!(
        ink_for(Color::rgb(0x0B1F4B), Color::rgb(0x000000), 3.0),
        Color::rgb(0xFFFFFF)
    );
}

/// An ink given without its fill is the application's choice and is left alone.
#[test]
fn fr14_10_an_ink_given_alone_is_not_corrected() {
    let ink_only = Palette::new().with(
        ColorRole::OnPrimary,
        Color::rgb(0x777777),
        Color::rgb(0x777777),
    );
    for system in systems() {
        assert_eq!(
            ink_only.resolve(system, ColorScheme::Light)[ColorRole::OnPrimary as usize - 1],
            Color::rgb(0x777777)
        );
    }
}

/// The check finds a pair that does not read, and finds nothing in a palette that does.
#[test]
fn fr14_10_the_check_reports_what_does_not_read_and_nothing_else() {
    for system in systems() {
        assert_eq!(
            Palette::new().check(system),
            Vec::new(),
            "{system:?} reports a problem with a palette that changes nothing"
        );
        assert_eq!(
            Palette::new()
                .with(
                    ColorRole::PrimaryContainer,
                    table(system).color(ColorRole::PrimaryContainer, ColorScheme::Light),
                    table(system).color(ColorRole::PrimaryContainer, ColorScheme::Dark),
                )
                .check(system),
            Vec::new()
        );
    }

    let grey_on_grey = Palette::new().with(
        ColorRole::OnSurface,
        Color::rgb(0x9A9A9A),
        Color::rgb(0x6A6A6A),
    );
    for system in systems() {
        let found = grey_on_grey.check(system);
        assert!(
            found.iter().any(|violation| matches!(
                violation,
                PaletteViolation::Contrast {
                    ink: ColorRole::OnSurface,
                    ..
                }
            )),
            "{system:?}: grey body text on the page went unreported: {found:?}"
        );
    }

    let flat_comment = Palette::new().with(
        ColorRole::SyntaxComment,
        Color::rgb(0xDDDDDD),
        Color::rgb(0x333333),
    );
    let found = flat_comment.check(DesignSystem::Fluent);
    assert!(found.iter().any(|violation| matches!(
        violation,
        PaletteViolation::Contrast {
            ink: ColorRole::SyntaxComment,
            fill: ColorRole::SurfaceContainer,
            ..
        }
    )));

    let invisible_panel = Palette::new().with(
        ColorRole::PrimaryContainer,
        Color::rgb(0xFEF7FF),
        Color::rgb(0x141218),
    );
    assert!(
        invisible_panel
            .check(DesignSystem::Material3)
            .iter()
            .any(|violation| matches!(
                violation,
                PaletteViolation::Indistinct {
                    first: ColorRole::PrimaryContainer,
                    second: ColorRole::Background,
                    ..
                }
            ))
    );
}

/// The debug build's start up report covers every system an adaptive theme may land on,
/// and says nothing about a theme without a palette.
#[test]
fn fr14_10_the_startup_report_names_the_system_and_the_pair() {
    assert!(palette_report(&Theme::adaptive(DesignSystem::Material3)).is_empty());
    const GREY: Palette = Palette::new().with(
        ColorRole::OnSurface,
        Color::rgb(0x9A9A9A),
        Color::rgb(0x6A6A6A),
    );
    let adaptive = palette_report(&Theme::adaptive(DesignSystem::Material3).with_palette(&GREY));
    for system in systems() {
        assert!(
            adaptive
                .iter()
                .any(|line| line.contains(&format!("{system:?}")) && line.contains("OnSurface")),
            "{system:?} was not checked: {adaptive:?}"
        );
    }
    let unified = palette_report(&Theme::unified(DesignSystem::Gnome).with_palette(&GREY));
    assert!(unified.iter().all(|line| line.contains("Gnome")));
}

/// The theme record is 56 bytes with a palette and without one, the palette rides behind
/// the records at eight bytes an entry, and the whole thing reads back as it was.
#[test]
fn fr14_10_set_theme_is_56_bytes_and_carries_the_palette_behind_it() {
    let with = Theme::unified(DesignSystem::Fluent).with_palette(&EMBER);
    let without = Theme::unified(DesignSystem::Fluent);
    for (theme, entries) in [(with, EMBER.len()), (without, 0)] {
        let mut encoder = BatchEncoder::default();
        encoder.encode(&Mutation::SetTheme(theme)).unwrap();
        let bytes = encoder.finish().unwrap();
        let record_length = u16::from_le_bytes([bytes[14], bytes[15]]);
        assert_eq!(record_length, 56);
        assert_eq!(bytes.len(), 12 + 56 + entries * PALETTE_ENTRY_LEN);
        let palette_len = u32::from_le_bytes(bytes[12 + 52..12 + 56].try_into().unwrap());
        assert_eq!(palette_len as usize, entries * PALETTE_ENTRY_LEN);
        assert_eq!(decode_batch(bytes).unwrap(), [Mutation::SetTheme(theme)]);
    }
    assert_eq!(EMBER.len(), 6);
}

/// Each entry is a role tag, a scheme tag and a colour, in role order, light first.
#[test]
fn fr14_10_a_palette_entry_is_a_role_a_scheme_and_a_colour() {
    let mut bytes = Vec::new();
    Palette::new()
        .with_dark(ColorRole::Error, Color::argb(0x8011_2233))
        .with_light(ColorRole::Primary, Color::rgb(0x010203))
        .write_into(&mut bytes);
    assert_eq!(
        bytes,
        [
            1, 0, 1, 0, 0x03, 0x02, 0x01, 0xff, // Primary, light
            13, 0, 2, 0, 0x33, 0x22, 0x11, 0x80, // Error, dark
        ]
    );
}

/// An unknown role, a scheme of `FollowSystem`, a duplicate and a ragged length are each
/// reported, and every sound entry still applies.
#[test]
fn fr14_10_a_bad_entry_is_reported_and_the_rest_still_apply() {
    let entry = |role: u16, scheme: u16, argb: u32| {
        let mut out = Vec::new();
        out.extend_from_slice(&role.to_le_bytes());
        out.extend_from_slice(&scheme.to_le_bytes());
        out.extend_from_slice(&argb.to_le_bytes());
        out
    };
    let mut bytes = Vec::new();
    bytes.extend(entry(1, 1, 0xffe8_590c)); // Primary, light: sound
    bytes.extend(entry(999, 1, 0xff00_0000)); // no such role
    bytes.extend(entry(9, 3, 0xff00_0000)); // FollowSystem is not a scheme
    bytes.extend(entry(1, 1, 0xff00_00ff)); // Primary, light again
    bytes.extend(entry(9, 2, 0xff14_1110)); // Background, dark: sound
    bytes.extend([0, 0, 0]); // a ragged tail
    let (palette, problems) = decode_palette(&bytes);
    assert_eq!(
        problems,
        [
            PaletteProblem::Length { len: 43 },
            PaletteProblem::UnknownRole { index: 1, tag: 999 },
            PaletteProblem::InvalidScheme {
                index: 2,
                scheme: 3
            },
            PaletteProblem::Duplicate {
                index: 3,
                role: ColorRole::Primary,
                scheme: ColorScheme::Light
            },
        ]
    );
    assert_eq!(
        palette.get(ColorRole::Primary, ColorScheme::Light),
        Some(Color::argb(0xffe8_590c)),
        "the first value for a role and scheme stands"
    );
    assert_eq!(
        palette.get(ColorRole::Background, ColorScheme::Dark),
        Some(Color::argb(0xff14_1110))
    );
    assert_eq!(palette.len(), 2);
}

/// The palette in the protocol vectors decodes to what was written.
#[test]
fn fr14_10_the_vector_palette_round_trips() {
    use dioxus_compose::codegen::{VECTOR_PALETTE, generate_mutation_vector};
    let bytes = generate_mutation_vector().unwrap();
    let theme = decode_batch(&bytes)
        .unwrap()
        .into_iter()
        .find_map(|mutation| match mutation {
            Mutation::SetTheme(theme) => Some(theme),
            _ => None,
        })
        .expect("the vector has a theme");
    assert_eq!(theme.palette, Some(&VECTOR_PALETTE));
    assert_eq!(VECTOR_PALETTE.len(), 3);
}
