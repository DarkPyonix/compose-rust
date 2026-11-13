//! The three ported systems, as the Host holds them and as the standalone project does.
//!
//! GNOME, Breeze and Deepin were worked out first in the design systems project, which is
//! published on its own and cannot depend on anything here, and their values were then
//! copied into `tokens.rs` so applications could select them. Two copies of the same
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
//!
//! The design systems live in the Compose fork, thisisthepy/compose-multiplatform-core-extended,
//! under `extended/design-systems`. They are read at the commit
//! `renderer/scripts/build-compose.sh` pins, from the checkout
//! `scripts/fetch-design-systems.sh` makes of that one directory under `.scratch/`.

use compose_rust::schema::{ColorScheme, DesignSystem};
use compose_rust::tokens::table;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

/// One standalone system: the Host-side enum and the Kotlin source that mirrors it.
struct Ported {
    system: DesignSystem,
    /// Named for the error messages, which have to say which file to open.
    path: String,
    source: String,
}

/// The root of this repository.
fn repo() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("the crate lives one directory below the repository root")
        .to_path_buf()
}

/// The fork commit `build-compose.sh` pins, read from its `REVISION="..."` line.
fn pinned_revision() -> String {
    let script = repo().join("renderer/scripts/build-compose.sh");
    let text = std::fs::read_to_string(&script)
        .unwrap_or_else(|error| panic!("cannot read {}: {error}", script.display()));
    text.lines()
        .find_map(|line| line.strip_prefix("REVISION=\""))
        .and_then(|rest| rest.strip_suffix('"'))
        .filter(|revision| revision.len() == 40)
        .unwrap_or_else(|| {
            panic!(
                "{} has no `REVISION=\"<40 hex digits>\"` line, so there is no fork commit \
                 to read the design systems from",
                script.display()
            )
        })
        .to_string()
}

/// The design systems project at the pinned commit, as `fetch-design-systems.sh` left it.
fn design_systems() -> PathBuf {
    let revision = pinned_revision();
    let root = repo()
        .join(".scratch/design-systems")
        .join(&revision)
        .join("extended/design-systems");
    assert!(
        root.is_dir(),
        "{} is missing. The design systems these tables are compared with live in \
         thisisthepy/compose-multiplatform-core-extended under extended/design-systems, at \
         the commit renderer/scripts/build-compose.sh pins ({revision}). Run \
         scripts/fetch-design-systems.sh to fetch that directory, then run this test again.",
        root.display()
    );
    root
}

fn ported() -> [Ported; 3] {
    let root = design_systems();
    let read = |system: DesignSystem, relative: &str| {
        let file = root.join(relative);
        let source = std::fs::read_to_string(&file)
            .unwrap_or_else(|error| panic!("cannot read {}: {error}", file.display()));
        Ported {
            system,
            path: format!("extended/design-systems/{relative}"),
            source,
        }
    };
    [
        read(
            DesignSystem::Gnome,
            "gnome/src/org/thisisthepy/compose/gnome/Adwaita.kt",
        ),
        read(
            DesignSystem::Breeze,
            "breeze/src/org/thisisthepy/compose/breeze/Breeze.kt",
        ),
        read(
            DesignSystem::Deepin,
            "deepin/src/org/thisisthepy/compose/deepin/Deepin.kt",
        ),
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
        let (path, source) = (path.as_str(), source.as_str());
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
        let (path, source) = (path.as_str(), source.as_str());
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
