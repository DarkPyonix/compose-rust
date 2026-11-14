//! The code colour roles on the wire: a highlighted `Text` names roles and never a colour,
//! and the roles are colour roles only, so adding them touched no widget, property or
//! modifier.

use compose_rust::ComposeHost;
use compose_rust::highlight::highlight_span;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{
    COLOR_ROLE_SCHEMA, ColorRole, MODIFIER_SCHEMA, PROPERTY_SCHEMA, Paint, PropertyKind,
    SCHEMA_DESCRIPTOR, WIDGET_SCHEMA,
};
use compose_rust::spans::{
    SPAN_BACKGROUND_AT, SPAN_COLOR_AT, SPAN_LEN, SPAN_START_AT, TextSpan, TextSpans,
};
use compose_rust::ui::*;

const SNIPPET: &str = "fn main() { let total = 42; } // done";

/// What a highlighter reports for `SNIPPET`: each token it names, and the name.
const TOKENS: [(&str, &str); 9] = [
    ("fn", "keyword.function"),
    ("main", "function"),
    ("(", "punctuation.bracket"),
    ("{", "punctuation.bracket"),
    ("let", "keyword"),
    ("total", "variable"),
    ("=", "operator"),
    ("42", "number"),
    ("// done", "comment"),
];

/// The runs the highlighter's names turn into, each over the first place its token stands.
fn highlighted() -> Vec<TextSpan> {
    TOKENS
        .iter()
        .map(|&(token, name)| {
            let start = SNIPPET.find(token).expect("the token is in the snippet");
            highlight_span(start as u32, token.len() as u32, name)
                .unwrap_or_else(|| panic!("{name} has a code role"))
        })
        .collect()
}

#[Composable]
fn Highlighted() {
    Text(SNIPPET).spans(TextSpans::new(highlighted()));
}

fn word(record: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([record[at], record[at + 1], record[at + 2], record[at + 3]])
}

/// The eight bytes of a paint in a run record: the value in the low word, the kind above.
fn paint_bits(record: &[u8], at: usize) -> u64 {
    u64::from(word(record, at)) | (u64::from(word(record, at + 4)) << 32)
}

#[test]
fn fr13_1_3_a_highlighted_text_carries_no_literal_colour() {
    let mut host = ComposeHost::with_content(Highlighted);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the batch decodes");
    let blobs: Vec<&[u8]> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                property: PropertyKind::Spans,
                value: PropertyValue::Bytes(bytes),
                ..
            } => Some(*bytes),
            _ => None,
        })
        .collect();
    assert_eq!(blobs.len(), 1, "one text, one list of runs: {mutations:?}");
    let records: Vec<&[u8]> = blobs[0].chunks_exact(SPAN_LEN).collect();
    assert_eq!(blobs[0].len(), TOKENS.len() * SPAN_LEN);
    assert_eq!(
        records.len(),
        TOKENS.len(),
        "one record per highlighted token"
    );

    let mut roles = Vec::new();
    for (record, (token, name)) in records.iter().zip(TOKENS) {
        let start = word(record, SPAN_START_AT) as usize;
        assert_eq!(&SNIPPET[start..start + token.len()], token);
        match Paint::from_bits(paint_bits(record, SPAN_COLOR_AT)) {
            Some(Paint::Role(role)) => roles.push(role),
            other => panic!("the run for {name} is painted {other:?}, not a role"),
        }
        assert_eq!(
            paint_bits(record, SPAN_BACKGROUND_AT),
            0,
            "the run for {name} paints nothing behind it"
        );
    }
    assert_eq!(
        roles,
        vec![
            ColorRole::SyntaxKeyword,
            ColorRole::SyntaxFunction,
            ColorRole::SyntaxPunctuation,
            ColorRole::SyntaxPunctuation,
            ColorRole::SyntaxKeyword,
            ColorRole::SyntaxVariable,
            ColorRole::SyntaxOperator,
            ColorRole::SyntaxNumber,
            ColorRole::SyntaxComment,
        ]
    );
}

/// The names in one list of the schema descriptor, such as `widgets=Column,Row,...;`.
fn descriptor_list(section: &str) -> Vec<&'static str> {
    let prefix = format!("{section}=");
    SCHEMA_DESCRIPTOR
        .split(';')
        .find_map(|part| part.strip_prefix(prefix.as_str()))
        .unwrap_or_else(|| panic!("the descriptor has a {section} list"))
        .split(',')
        .collect()
}

/// A name with its case and underscores taken out, so `syntax_spans` and `SyntaxSpans`
/// are one name.
fn folded(name: &str) -> String {
    name.replace('_', "").to_lowercase()
}

#[test]
fn fr13_1_3_code_roles_add_no_widget_property_or_modifier() {
    // The code roles are colour roles: fifteen syntax inks and seven diff colours.
    let code_roles: Vec<ColorRole> = COLOR_ROLE_SCHEMA
        .iter()
        .filter(|variant| variant.name.starts_with("Syntax") || variant.name.starts_with("Diff"))
        .map(|variant| ColorRole::try_from(variant.tag).expect("a listed role decodes"))
        .collect();
    assert_eq!(code_roles.len(), 22);
    let role_names: Vec<String> = code_roles
        .iter()
        .map(|role| folded(&format!("{role:?}")))
        .collect();

    // Every name a widget, a property or a modifier goes by, from the descriptor and from
    // the tables the two sides are generated from.
    let mut entries: Vec<(&str, &str)> = Vec::new();
    for section in ["widgets", "properties", "modifiers"] {
        for name in descriptor_list(section) {
            entries.push((section, name));
        }
    }
    entries.extend(
        WIDGET_SCHEMA
            .iter()
            .map(|variant| ("widgets", variant.name)),
    );
    entries.extend(
        PROPERTY_SCHEMA
            .iter()
            .map(|variant| ("properties", variant.name)),
    );
    for modifier in MODIFIER_SCHEMA {
        entries.push(("modifiers", modifier.name));
        for field in modifier.fields {
            entries.push(("modifiers", field.name));
        }
    }
    assert!(
        entries.len() > 100,
        "the lists were read: {}",
        entries.len()
    );

    for (section, name) in &entries {
        assert!(
            !role_names.contains(&folded(name)),
            "{name} in {section} is named after a code colour role"
        );
    }
    // Nothing else there is about syntax or diffs either, with one exception that is not
    // a colour: the code editor's list of highlighted ranges.
    let about_code: Vec<(&str, &str)> = entries
        .iter()
        .copied()
        .filter(|(_, name)| {
            let name = folded(name);
            (name.contains("syntax") || name.contains("diff")) && name != "syntaxspans"
        })
        .collect();
    assert_eq!(about_code, Vec::<(&str, &str)>::new());

    // A code role travels in the paint every colour role travels in, so anything that
    // takes a colour takes one with no record of its own.
    let role_kind = Paint::Role(ColorRole::Primary).to_bits() >> 32;
    for role in code_roles {
        assert_eq!(Paint::Role(role).to_bits() >> 32, role_kind, "{role:?}");
        assert_eq!(
            Paint::from_bits(Paint::Role(role).to_bits()),
            Some(Paint::Role(role))
        );
    }
}
