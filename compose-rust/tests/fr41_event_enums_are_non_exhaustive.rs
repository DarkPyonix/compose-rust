//! The event types stay open to additions.
//!
//! `HostEvent` and `EventPayload` carry `#[non_exhaustive]`, so a new event or a new field
//! is not a breaking change for code outside this crate. An attribute cannot be observed at
//! run time, so this file reads the sources as text and checks that the attribute sits on
//! each declaration. The compile-fail fixtures under `macros/tests/event_enums/` check what
//! the attribute does to a caller: a `match` with no `_` arm, a struct literal and a pattern
//! with no `..` all fail to build there.

const SCHEMA: &str = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/schema.rs"));
const PROTOCOL: &str = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/protocol.rs"));
const EXHAUSTIVE_MATCH_FIXTURE: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/macros/tests/event_enums/fail_payload_match_without_wildcard.rs"
));

/// The declarations that have to be non-exhaustive, and the file each one is in.
const DECLARATIONS: &[(&str, &str, &str)] = &[
    ("src/protocol.rs", PROTOCOL, "pub struct HostEvent<"),
    ("src/schema.rs", SCHEMA, "pub enum EventPayload<"),
];

/// Whether the attributes and doc comment directly above `declaration` in `source` include
/// `#[non_exhaustive]`. An error says what is wrong.
fn carries_non_exhaustive(source: &str, declaration: &str) -> Result<(), String> {
    let lines: Vec<&str> = source.lines().collect();
    let at = lines
        .iter()
        .position(|line| line.starts_with(declaration))
        .ok_or_else(|| format!("no line starts with `{declaration}`"))?;
    let found = lines[..at]
        .iter()
        .rev()
        .map(|line| line.trim())
        .take_while(|line| line.starts_with("#[") || line.starts_with("///"))
        .any(|line| line == "#[non_exhaustive]");
    if found {
        Ok(())
    } else {
        Err(format!(
            "`{declaration}` has no `#[non_exhaustive]` among the attributes above it"
        ))
    }
}

/// The variant names of `pub enum EventPayload<'a>`, in declaration order.
fn payload_variants() -> Vec<&'static str> {
    let mut lines = SCHEMA.lines();
    lines
        .by_ref()
        .find(|line| line.starts_with("pub enum EventPayload<"))
        .expect("schema.rs declares `pub enum EventPayload<'a>`");
    lines
        .take_while(|line| *line != "}")
        .filter_map(|line| {
            let name = line.strip_prefix("    ")?;
            if !name.starts_with(|c: char| c.is_ascii_uppercase()) {
                return None;
            }
            let end = name
                .find(|c: char| !c.is_ascii_alphanumeric())
                .unwrap_or(name.len());
            Some(&name[..end])
        })
        .collect()
}

#[test]
fn fr41_event_enums_are_non_exhaustive() {
    let failures: Vec<String> = DECLARATIONS
        .iter()
        .filter_map(|(file, source, declaration)| {
            carries_non_exhaustive(source, declaration)
                .err()
                .map(|error| format!("{file}: {error}"))
        })
        .collect();
    assert!(
        failures.is_empty(),
        "an event type without `#[non_exhaustive]` turns every new event or field into a \
         breaking change for code outside the crate:\n{}",
        failures.join("\n")
    );
}

#[test]
fn fr41_event_enums_are_non_exhaustive_checker_can_fail() {
    let without = "#[derive(Clone, Debug, PartialEq)]\npub enum EventPayload<'a> {\n}\n";
    assert!(
        carries_non_exhaustive(without, "pub enum EventPayload<").is_err(),
        "the checker passed a declaration that has no `#[non_exhaustive]`"
    );
    // An attribute separated from the declaration by other code belongs to something else.
    let elsewhere =
        "#[non_exhaustive]\npub struct Other;\n\n#[derive(Clone)]\npub struct HostEvent<'a> {\n}\n";
    assert!(
        carries_non_exhaustive(elsewhere, "pub struct HostEvent<").is_err(),
        "the checker credited an attribute that belongs to the item above"
    );
    let with = "/// Docs.\n#[derive(Clone)]\n#[non_exhaustive]\npub struct HostEvent<'a> {\n}\n";
    assert!(
        carries_non_exhaustive(with, "pub struct HostEvent<").is_ok(),
        "the checker refused a declaration that has `#[non_exhaustive]`"
    );
}

/// The fixture that matches without a `_` arm names every event, so the only thing its
/// match lacks is the wildcard. Without this, a new event would make the fixture fail for
/// the missing event instead, whether or not the enum is non-exhaustive.
#[test]
fn fr41_event_enums_are_non_exhaustive_fixture_names_every_event() {
    let variants = payload_variants();
    assert!(
        variants.len() > 1,
        "found {} variants in EventPayload; the reader of schema.rs is broken",
        variants.len()
    );
    let missing: Vec<&str> = variants
        .into_iter()
        .filter(|name| {
            let path = format!("EventPayload::{name}");
            !EXHAUSTIVE_MATCH_FIXTURE
                .match_indices(&path)
                .any(|(at, _)| {
                    !EXHAUSTIVE_MATCH_FIXTURE[at + path.len()..]
                        .starts_with(|c: char| c.is_ascii_alphanumeric())
                })
        })
        .collect();
    assert!(
        missing.is_empty(),
        "macros/tests/event_enums/fail_payload_match_without_wildcard.rs has no arm for \
         these events; add one each and regenerate its .stderr: {missing:?}"
    );
}
