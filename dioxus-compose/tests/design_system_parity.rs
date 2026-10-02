//! The three ported systems, as the Host holds them and as the standalone project does.
//!
//! GNOME, Breeze and Deepin were worked out first in `dioxus-design-systems`, a project
//! that is published on its own and cannot depend on anything here, and their values were
//! then copied into `tokens.rs` so applications could select them. Two copies of the same
//! palette with nothing comparing them is exactly the arrangement that drifts: GNOME's
//! dark second accent carried white ink in one file and near black in the other, and
//! Breeze filled a dark panel with two different greys, for long enough that both were
//! described in comments as the value the other one held.
//!
//! So the copies are compared here, by reading the Kotlin sources as text. It is a blunt
//! instrument, and it only reaches the three systems whose tables are written as literals
//! (Material 3, Cupertino and Fluent derive theirs from palette objects), but it is the
//! only thing that can see both sides at once, and those three are the ones that were
//! copied by hand.

use dioxus_compose::schema::{ColorScheme, DesignSystem};
use dioxus_compose::tokens::table;
use std::collections::BTreeMap;

/// One standalone system: the Host-side enum and the Kotlin source that mirrors it.
struct Ported {
    system: DesignSystem,
    /// Named for the error messages, which have to say which file to open.
    path: &'static str,
    source: &'static str,
}

fn ported() -> [Ported; 3] {
    [
        Ported {
            system: DesignSystem::Gnome,
            path: "dioxus-design-systems/gnome/src/dioxus/compose/gnome/Adwaita.kt",
            source: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../dioxus-design-systems/gnome/src/dioxus/compose/gnome/Adwaita.kt"
            )),
        },
        Ported {
            system: DesignSystem::Breeze,
            path: "dioxus-design-systems/breeze/src/dioxus/compose/breeze/Breeze.kt",
            source: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../dioxus-design-systems/breeze/src/dioxus/compose/breeze/Breeze.kt"
            )),
        },
        Ported {
            system: DesignSystem::Deepin,
            path: "dioxus-design-systems/deepin/src/dioxus/compose/deepin/Deepin.kt",
            source: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../dioxus-design-systems/deepin/src/dioxus/compose/deepin/Deepin.kt"
            )),
        },
    ]
}

/// The body of a `when (role)` block, given the line that opens it.
///
/// Panics rather than returning nothing: a block that cannot be found means the Kotlin
/// side was rewritten into a shape this comparison no longer reads, and silently
/// comparing zero rows would turn that into a passing test.
fn when_block<'a>(source: &'a str, path: &str, opener: &str) -> &'a str {
    let start = source.find(opener).unwrap_or_else(|| {
        panic!(
            "{path} no longer contains `{opener}`, so the values in it cannot be compared \
             with the ones the Host ships. Either restore the block or teach this \
             comparison to read the shape that replaced it."
        )
    });
    let body = &source[start + opener.len()..];
    let end = body.find("\n    }").unwrap_or_else(|| {
        panic!("{path}: `{opener}` is not closed by a line of four spaces and a brace")
    });
    &body[..end]
}

/// Every `Prefix.Role -> ...` row of a block, as role name against the rest of the line.
fn rows(block: &str, prefix: &str) -> BTreeMap<String, String> {
    let mut found = BTreeMap::new();
    for line in block.lines() {
        let Some(rest) = line.trim().strip_prefix(prefix) else {
            continue;
        };
        let Some((role, value)) = rest.split_once(" -> ") else {
            continue;
        };
        found.insert(role.to_string(), value.trim().to_string());
    }
    found
}

/// The `RRGGBB` of a literal `Color(0xAARRGGBB)`. The alpha pair is dropped, because the
/// shipped tables store a colour as three channels and every literal here is opaque.
fn kotlin_rgb(value: &str, path: &str, role: &str) -> u32 {
    let digits = value
        .strip_prefix("Color(0x")
        .and_then(|rest| rest.strip_suffix(')'))
        .unwrap_or_else(|| {
            panic!("{path}: {role} is `{value}`, which is not a literal `Color(0xAARRGGBB)`")
        });
    assert_eq!(
        digits.len(),
        8,
        "{path}: {role} is `{value}`, and a literal colour here is eight hex digits with the alpha pair first"
    );
    u32::from_str_radix(&digits[2..], 16)
        .unwrap_or_else(|_| panic!("{path}: {role} is `{value}`, which is not hexadecimal"))
}

/// The dp figure of `12.dp`.
fn kotlin_dp(value: &str, path: &str, role: &str) -> f32 {
    value
        .strip_suffix(".dp")
        .and_then(|number| number.parse::<f32>().ok())
        .unwrap_or_else(|| panic!("{path}: {role} is `{value}`, which is not a literal dp figure"))
}

#[test]
fn fr14_ported_palettes_match_the_standalone_project() {
    for Ported {
        system,
        path,
        source,
    } in ported()
    {
        let host = table(system);
        for (scheme, function) in [
            (ColorScheme::Light, "lightColor"),
            (ColorScheme::Dark, "darkColor"),
        ] {
            let opener = format!("private fun {function}(role: ColorRole): Color = when (role) {{");
            let block = when_block(source, path, &opener);
            let kotlin = rows(block, "ColorRole.");
            assert_eq!(
                kotlin.len(),
                host.colors.len(),
                "{path}: {function} answers {} roles and the shipped table has {}. Every role \
                 has to be answered in both places, or an application and the standalone \
                 library paint the same widget differently.",
                kotlin.len(),
                host.colors.len()
            );
            for token in host.colors {
                let role = format!("{:?}", token.role);
                let value = kotlin.get(&role).unwrap_or_else(|| {
                    panic!("{path}: {function} has no row for {role}, which the shipped table does")
                });
                let theirs = kotlin_rgb(value, path, &role);
                let ours = host.color(token.role, scheme).0 & 0xff_ffff;
                assert_eq!(
                    ours, theirs,
                    "{path}: {function} paints {role} #{theirs:06x} and the shipped table \
                     paints it #{ours:06x}. The two are meant to be the same palette, so \
                     whichever is wrong has to be corrected in both files."
                );
            }
        }
    }
}

#[test]
fn fr14_ported_shape_and_space_ladders_match_the_standalone_project() {
    for Ported {
        system,
        path,
        source,
    } in ported()
    {
        let host = table(system);

        // `Full` is skipped. The shipped table says it with a radius large enough to read
        // as a pill and the Kotlin side says `percent = 50`, which is the same intent
        // written in the units each side has.
        let shapes = rows(
            when_block(
                source,
                path,
                "override fun shape(role: ShapeRole): Shape = when (role) {",
            ),
            "ShapeRole.",
        );
        for token in host.shapes.iter().filter(|token| token.radius < 100.0) {
            let role = format!("{:?}", token.role);
            let value = shapes
                .get(&role)
                .unwrap_or_else(|| panic!("{path}: no shape row for {role}"));
            let theirs = value
                .strip_prefix("RoundedCornerShape(")
                .and_then(|rest| rest.strip_suffix(')'))
                .unwrap_or_else(|| {
                    panic!("{path}: {role} is `{value}`, which is not a literal rounded corner")
                });
            assert_eq!(
                token.radius,
                kotlin_dp(theirs, path, &role),
                "{path}: {role} rounds at {} in the shipped table and at `{theirs}` here",
                token.radius
            );
        }

        let spaces = rows(
            when_block(
                source,
                path,
                "override fun space(role: SpaceRole): Dp = when (role) {",
            ),
            "SpaceRole.",
        );
        for token in host.spaces {
            let role = format!("{:?}", token.role);
            let value = spaces
                .get(&role)
                .unwrap_or_else(|| panic!("{path}: no spacing row for {role}"));
            assert_eq!(
                token.value,
                kotlin_dp(value, path, &role),
                "{path}: {role} is {} in the shipped table and `{value}` here",
                token.value
            );
        }
    }
}
