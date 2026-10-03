//! Code colour roles: a highlighted piece of code travels as roles, never as literals, and
//! adding the roles moved nothing else in the schema.

use dioxus_compose::highlight::highlight_span;
use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{BatchEncoder, Mutation, PropertyValue, decode_batch};
use dioxus_compose::schema::{
    COLOR_ROLE_COUNT, COLOR_ROLE_SCHEMA, MODIFIER_SCHEMA, PROPERTY_SCHEMA, WIDGET_SCHEMA,
};
use dioxus_compose::spans::{TextSpan, TextSpans};
use dioxus_compose::tokens::DESIGN_TOKENS;
use dioxus_compose::{Host, PropertyKind};

const SNIPPET: &str = "// Adds one.\nfn next(count: u32) -> u32 {\n    let label = \"next\\n\";\n    println!(\"{label}\");\n    Some(count).unwrap_or(0) + 1\n}\n";

/// A small lexer standing in for a real highlighter. It says what each token is in the
/// highlighter's own vocabulary, which is all a highlighter hands over.
fn highlight(source: &str) -> Vec<TextSpan> {
    const KEYWORDS: &[&str] = &[
        "fn", "let", "mut", "pub", "return", "if", "else", "for", "in",
    ];
    let bytes = source.as_bytes();
    let mut spans = Vec::new();
    let mut at = 0;
    let mut push = |start: usize, end: usize, name: &str| {
        if let Some(span) = highlight_span(start as u32, (end - start) as u32, name) {
            spans.push(span);
        }
    };
    while at < bytes.len() {
        let byte = bytes[at];
        if bytes[at..].starts_with(b"//") {
            let end = source[at..]
                .find('\n')
                .map_or(bytes.len(), |offset| at + offset);
            push(at, end, "comment");
            at = end;
        } else if byte == b'"' {
            let mut end = at + 1;
            while end < bytes.len() && bytes[end] != b'"' {
                if bytes[end] == b'\\' {
                    // The escape is its own run inside the string.
                    push(at, end, "string");
                    push(end, end + 2, "string.escape");
                    at = end + 2;
                    end += 2;
                    continue;
                }
                end += 1;
            }
            push(at, end + 1, "string");
            at = end + 1;
        } else if byte.is_ascii_digit() {
            let end = at
                + source[at..]
                    .find(|c: char| !c.is_ascii_digit())
                    .unwrap_or(0);
            push(at, end.max(at + 1), "number");
            at = end.max(at + 1);
        } else if byte.is_ascii_alphabetic() || byte == b'_' {
            let end = at
                + source[at..]
                    .find(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))
                    .unwrap_or(source.len() - at);
            let word = &source[at..end];
            let name = if KEYWORDS.contains(&word) {
                "keyword"
            } else if bytes.get(end) == Some(&b'!') {
                "function.macro"
            } else if bytes.get(end) == Some(&b'(') {
                "function"
            } else if word.chars().next().is_some_and(char::is_uppercase) || word == "u32" {
                "type"
            } else {
                "variable"
            };
            push(at, end, name);
            at = end;
        } else if b"(){};,:.".contains(&byte) {
            push(at, at + 1, "punctuation.delimiter");
            at += 1;
        } else if b"=+-*/<>&|!".contains(&byte) {
            push(at, at + 1, "operator");
            at += 1;
        } else {
            at += 1;
        }
    }
    spans
}

fn highlighted() -> Element {
    rsx! { Text { text: SNIPPET, spans: TextSpans::new(highlight(SNIPPET)) } }
}

/// A highlighted snippet carries no literal colour at all, and every role it does carry
/// is a different colour in light and in dark, in every system.
#[test]
fn fr13_1_3_highlighted_code_travels_as_roles_and_differs_between_schemes() {
    dioxus_compose::window::reset_window_size();
    let mut host = Host::new(highlighted);
    let batch = host.rebuild().expect("the first frame failed to encode");
    let bytes = decode_batch(batch)
        .expect("decode")
        .into_iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                property: PropertyKind::Spans,
                value: PropertyValue::Bytes(bytes),
                ..
            } => Some(bytes.to_vec()),
            _ => None,
        })
        .expect("the runs did not travel");
    let spans: Vec<TextSpan> = TextSpans::from_bytes(bytes).spans().collect();
    assert!(
        spans.len() > 10,
        "the snippet was barely highlighted: {spans:?}"
    );

    let mut roles = std::collections::BTreeSet::new();
    for span in &spans {
        match span.color {
            Some(Paint::Role(role)) => {
                roles.insert(role as u16);
            }
            other => panic!("a run carries {other:?}, and a highlighter only sends roles"),
        }
        assert_eq!(span.background, None, "plain code has no word backgrounds");
    }
    for expected in [
        ColorRole::SyntaxKeyword,
        ColorRole::SyntaxString,
        ColorRole::SyntaxComment,
        ColorRole::SyntaxNumber,
        ColorRole::SyntaxType,
        ColorRole::SyntaxFunction,
        ColorRole::SyntaxVariable,
        ColorRole::SyntaxPunctuation,
        ColorRole::SyntaxOperator,
        ColorRole::SyntaxEscape,
        ColorRole::SyntaxMacro,
    ] {
        assert!(
            roles.contains(&(expected as u16)),
            "nothing was painted {expected:?}"
        );
    }
    for table in DESIGN_TOKENS {
        for &tag in &roles {
            let role = ColorRole::try_from(tag).unwrap();
            assert_ne!(
                table.color(role, ColorScheme::Light),
                table.color(role, ColorScheme::Dark),
                "{:?} paints {role:?} the same in light and dark",
                table.system
            );
        }
    }
}

/// The new roles are colour values and nothing else: no widget, no property and no
/// modifier was added for them, and a paint naming the last of them is the same one word
/// on the wire as one naming the first.
#[test]
fn fr13_1_3_code_roles_leave_the_widget_property_and_modifier_schema_alone() {
    assert_eq!(COLOR_ROLE_SCHEMA.len(), COLOR_ROLE_COUNT);
    assert_eq!(COLOR_ROLE_COUNT, 45);
    for variant in WIDGET_SCHEMA.iter().chain(PROPERTY_SCHEMA.iter()) {
        assert!(
            !variant.name.starts_with("Syntax") && !variant.name.starts_with("Diff"),
            "{} is a code colour turned into schema",
            variant.name
        );
    }
    for variant in MODIFIER_SCHEMA {
        assert!(!variant.name.starts_with("Syntax") && !variant.name.starts_with("Diff"));
    }

    let mut lengths = std::collections::BTreeSet::new();
    for variant in COLOR_ROLE_SCHEMA {
        let role = ColorRole::try_from(variant.tag).unwrap();
        let paint = Paint::Role(role);
        assert_eq!(Paint::from_bits(paint.to_bits()), Some(paint));
        let records = [
            Mutation::SetModifier {
                node_id: 1,
                index: 0,
                modifier: Modifier::Background(paint),
            },
            Mutation::SetProp {
                node_id: 1,
                property: PropertyKind::Color,
                value: PropertyValue::Integer(paint.to_bits() as i64),
            },
        ];
        let mut encoder = BatchEncoder::default();
        for record in &records {
            encoder.encode(record).unwrap();
        }
        let bytes = encoder.finish().unwrap();
        lengths.insert(bytes.len());
        assert_eq!(decode_batch(bytes).unwrap(), records);
    }
    assert_eq!(
        lengths.len(),
        1,
        "a later role made a longer record: {lengths:?}"
    );
}
