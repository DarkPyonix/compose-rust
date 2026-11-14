//! From a highlighter's names to the code colour roles.
//!
//! A highlighter, tree-sitter or anything that speaks its vocabulary, names each token with
//! a dotted capture name such as `keyword.return` or `variable.parameter`. The Renderer does
//! not know any language and never hears these names: they are turned into colour roles
//! here, on the Host side, and the roles travel as the `Paint` of a span the way any other
//! coloured run does. Markdown is handled the same way, for the same reason.
//!
//! The names are matched from the most specific prefix down, so `string.escape` is an
//! escape while `string.special` is still a string, and a name nothing here recognises gives
//! `None`. A token without a role is left in the body ink rather than painted a guess.

use crate::schema::{ColorRole, Paint};
use crate::spans::TextSpan;

/// The colour role a highlight name is painted with, or None to leave it in the body ink.
///
/// Accepts tree-sitter's capture names (`keyword.control`, `function.method`,
/// `punctuation.bracket`, `diff.plus`), the older names some grammars still use
/// (`conditional`, `repeat`, `include`, `float`, `field`, `preproc`), and VS Code's
/// semantic token kinds (`enumMember`, `typeParameter`, `decorator`).
pub fn role_for_highlight(name: &str) -> Option<ColorRole> {
    // Longest first: every row is a prefix that ends at a dot or at the end of the name.
    const TABLE: &[(&str, ColorRole)] = &[
        ("string.escape", ColorRole::SyntaxEscape),
        ("string.regexp", ColorRole::SyntaxEscape),
        ("string.regex", ColorRole::SyntaxEscape),
        ("constant.character.escape", ColorRole::SyntaxEscape),
        ("function.macro", ColorRole::SyntaxMacro),
        ("keyword.directive", ColorRole::SyntaxMacro),
        ("keyword.import", ColorRole::SyntaxKeyword),
        ("variable.member", ColorRole::SyntaxProperty),
        ("variable.other.member", ColorRole::SyntaxProperty),
        ("tag.attribute", ColorRole::SyntaxAttribute),
        ("constructor", ColorRole::SyntaxFunction),
        ("keyword", ColorRole::SyntaxKeyword),
        ("conditional", ColorRole::SyntaxKeyword),
        ("repeat", ColorRole::SyntaxKeyword),
        ("exception", ColorRole::SyntaxKeyword),
        ("include", ColorRole::SyntaxKeyword),
        ("storageclass", ColorRole::SyntaxKeyword),
        ("modifier", ColorRole::SyntaxKeyword),
        ("string", ColorRole::SyntaxString),
        ("character", ColorRole::SyntaxString),
        ("comment", ColorRole::SyntaxComment),
        ("number", ColorRole::SyntaxNumber),
        ("float", ColorRole::SyntaxNumber),
        ("boolean", ColorRole::SyntaxConstant),
        ("constant", ColorRole::SyntaxConstant),
        ("enumMember", ColorRole::SyntaxConstant),
        ("type", ColorRole::SyntaxType),
        ("typeParameter", ColorRole::SyntaxType),
        ("class", ColorRole::SyntaxType),
        ("struct", ColorRole::SyntaxType),
        ("enum", ColorRole::SyntaxType),
        ("interface", ColorRole::SyntaxType),
        ("namespace", ColorRole::SyntaxType),
        ("module", ColorRole::SyntaxType),
        ("function", ColorRole::SyntaxFunction),
        ("method", ColorRole::SyntaxFunction),
        ("variable", ColorRole::SyntaxVariable),
        ("parameter", ColorRole::SyntaxVariable),
        ("property", ColorRole::SyntaxProperty),
        ("field", ColorRole::SyntaxProperty),
        ("operator", ColorRole::SyntaxOperator),
        ("punctuation", ColorRole::SyntaxPunctuation),
        ("tag", ColorRole::SyntaxTag),
        ("attribute", ColorRole::SyntaxAttribute),
        ("macro", ColorRole::SyntaxMacro),
        ("preproc", ColorRole::SyntaxMacro),
        ("define", ColorRole::SyntaxMacro),
        ("decorator", ColorRole::SyntaxMacro),
        ("annotation", ColorRole::SyntaxMacro),
        ("regexp", ColorRole::SyntaxEscape),
        ("diff.plus", ColorRole::DiffAdded),
        ("diff.minus", ColorRole::DiffRemoved),
        ("diff.delta", ColorRole::DiffModified),
    ];
    let matches = |prefix: &str| {
        name.strip_prefix(prefix)
            .is_some_and(|rest| rest.is_empty() || rest.starts_with('.'))
    };
    TABLE
        .iter()
        .filter(|(prefix, _)| matches(prefix))
        .max_by_key(|(prefix, _)| prefix.len())
        .map(|(_, role)| *role)
}

/// A span painting `length` bytes from `start` in the role a highlight name maps to, or
/// None where the name has no role and the run is left in the body ink.
pub fn highlight_span(start: u32, length: u32, name: &str) -> Option<TextSpan> {
    role_for_highlight(name).map(|role| TextSpan::new(start, length).with_color(Paint::Role(role)))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The finer names fold into the twenty two, and the most specific prefix wins.
    #[test]
    fn fr13_1_3_highlight_names_fold_into_the_code_roles() {
        for (name, role) in [
            ("keyword", ColorRole::SyntaxKeyword),
            ("keyword.return", ColorRole::SyntaxKeyword),
            ("keyword.directive", ColorRole::SyntaxMacro),
            ("string", ColorRole::SyntaxString),
            ("string.special", ColorRole::SyntaxString),
            ("string.escape", ColorRole::SyntaxEscape),
            ("string.regexp", ColorRole::SyntaxEscape),
            ("character", ColorRole::SyntaxString),
            ("comment.documentation", ColorRole::SyntaxComment),
            ("number", ColorRole::SyntaxNumber),
            ("float", ColorRole::SyntaxNumber),
            ("constant.builtin", ColorRole::SyntaxConstant),
            ("boolean", ColorRole::SyntaxConstant),
            ("type.builtin", ColorRole::SyntaxType),
            ("function.method", ColorRole::SyntaxFunction),
            ("function.macro", ColorRole::SyntaxMacro),
            ("constructor", ColorRole::SyntaxFunction),
            ("variable.parameter", ColorRole::SyntaxVariable),
            ("variable.member", ColorRole::SyntaxProperty),
            ("property", ColorRole::SyntaxProperty),
            ("field", ColorRole::SyntaxProperty),
            ("operator", ColorRole::SyntaxOperator),
            ("punctuation.bracket", ColorRole::SyntaxPunctuation),
            ("tag", ColorRole::SyntaxTag),
            ("attribute", ColorRole::SyntaxAttribute),
            ("preproc", ColorRole::SyntaxMacro),
            ("diff.plus", ColorRole::DiffAdded),
            ("diff.minus", ColorRole::DiffRemoved),
            ("diff.delta", ColorRole::DiffModified),
        ] {
            assert_eq!(role_for_highlight(name), Some(role), "{name}");
        }
    }

    /// A name nothing here knows stays in the body ink, and a prefix only counts at a dot.
    #[test]
    fn fr13_1_3_an_unknown_name_is_left_unpainted() {
        assert_eq!(role_for_highlight("embedded"), None);
        assert_eq!(role_for_highlight("spell"), None);
        assert_eq!(role_for_highlight("keywords"), None);
        assert!(highlight_span(0, 3, "none").is_none());
    }

    /// Highlighting a Rust snippet into a `Text` sends every coloured run as a colour role on
    /// the wire and never stamps a literal ARGB colour into the batch.
    #[test]
    fn fr13_1_3_highlighted_rust_text_carries_no_literal_colour_on_the_wire() {
        use crate::protocol::{Mutation, PropertyValue, decode_batch};
        use crate::schema::{Modifier as WireModifier, PropertyKind};
        use crate::spans::{SPAN_COLOR_AT, SPAN_LEN, TextSpans};

        let source = "pub fn total(items: &[u32]) -> u32 { // forty-two\n    items.len() + 42\n}";
        let tokens = [
            ("pub", "keyword"),
            ("fn", "keyword"),
            ("total", "function"),
            ("items", "variable.parameter"),
            ("&", "operator"),
            ("[", "punctuation.bracket"),
            ("u32", "type.builtin"),
            ("]", "punctuation.bracket"),
            ("->", "operator"),
            ("// forty-two", "comment"),
            ("len", "function.method"),
            ("+", "operator"),
            ("42", "number"),
            ("items", "spell"),
        ];
        let mut cursor = 0;
        let mut spans = Vec::new();
        for (lexeme, capture) in tokens {
            let Some(relative) = source[cursor..].find(lexeme) else {
                continue;
            };
            let start = (cursor + relative) as u32;
            let length = lexeme.len() as u32;
            if let Some(span) = highlight_span(start, length, capture) {
                spans.push(span);
                cursor = start as usize + lexeme.len();
            }
        }
        assert!(
            !spans.is_empty(),
            "the highlighted snippet must produce runs"
        );

        let mut host = crate::ComposeHost::with_content(move || {
            let encoded_spans = TextSpans::new(spans.clone());
            crate::ui::Text(source).spans(encoded_spans);
        });
        let wire = host.rebuild().expect("the batch encodes").to_vec();
        let mutations = decode_batch(&wire).expect("the batch decodes");

        let mut raw_spans: Option<&[u8]> = None;
        for mutation in &mutations {
            match mutation {
                Mutation::SetProp {
                    property: PropertyKind::Spans,
                    value: PropertyValue::Bytes(bytes),
                    ..
                } => {
                    raw_spans = Some(bytes);
                }
                Mutation::SetProp {
                    property: PropertyKind::Color,
                    value: PropertyValue::Integer(bits),
                    ..
                } => {
                    assert_ne!(
                        Paint::from_bits(*bits as u64),
                        None,
                        "any colour property on the wire must decode as a valid paint"
                    );
                    assert!(
                        !matches!(Paint::from_bits(*bits as u64), Some(Paint::Literal(_))),
                        "a highlighted Text must not send a literal node colour"
                    );
                }
                Mutation::SetModifier {
                    modifier: WireModifier::Background(paint),
                    ..
                } => {
                    assert!(
                        !matches!(paint, Paint::Literal(_)),
                        "a highlighted Text must not send a literal background"
                    );
                }
                _ => {}
            }
        }

        let span_bytes = raw_spans.expect("the highlighted Text must emit a Spans property");
        assert_eq!(span_bytes.len() % SPAN_LEN, 0);
        assert!(!span_bytes.is_empty());

        for record in span_bytes.chunks_exact(SPAN_LEN) {
            let paint_kind = u32::from_le_bytes([
                record[SPAN_COLOR_AT + 4],
                record[SPAN_COLOR_AT + 5],
                record[SPAN_COLOR_AT + 6],
                record[SPAN_COLOR_AT + 7],
            ]);
            assert_eq!(
                paint_kind, 1,
                "every highlighted run must carry Paint::Role (tag 1) on the wire, never a literal colour"
            );
        }

        for span in TextSpans::from_bytes(span_bytes).spans() {
            assert!(
                matches!(span.color, Some(Paint::Role(_))),
                "every decoded span must use a colour role, got {:?}",
                span.color
            );
            assert!(
                span.background.is_none(),
                "syntax highlighting sets ink roles only, got {:?}",
                span.background
            );
        }
    }

    /// Adding the twenty-two code colour roles extends `ColorRole` and the token tables
    /// without altering the widget definitions, the property schema or the modifier schema.
    #[test]
    fn fr13_1_3_code_roles_leave_widget_property_and_modifier_schemas_unchanged() {
        use crate::protocol::{BatchEncoder, Mutation, PropertyValue};
        use crate::schema::{
            COLOR_ROLE_COUNT, COLOR_ROLE_SCHEMA, FieldType, MODIFIER_SCHEMA, Modifier,
            PROPERTY_SCHEMA, PropertyKind, SCHEMA_DESCRIPTOR, WIDGET_SCHEMA,
        };

        assert_eq!(COLOR_ROLE_COUNT, 45);
        assert_eq!(COLOR_ROLE_SCHEMA.len(), COLOR_ROLE_COUNT);
        assert_eq!(COLOR_ROLE_SCHEMA[22].name, "OnTertiaryContainer");
        assert_eq!(COLOR_ROLE_SCHEMA[22].tag, 23);
        assert_eq!(COLOR_ROLE_SCHEMA[23].name, "SyntaxKeyword");
        assert_eq!(COLOR_ROLE_SCHEMA[23].tag, 24);
        assert_eq!(COLOR_ROLE_SCHEMA[44].name, "DiffRemovedEmphasis");
        assert_eq!(COLOR_ROLE_SCHEMA[44].tag, 45);
        for (index, entry) in COLOR_ROLE_SCHEMA.iter().enumerate() {
            assert_eq!(
                entry.tag as usize,
                index + 1,
                "colour roles are append-only with contiguous tags"
            );
        }

        for code_role in &COLOR_ROLE_SCHEMA[23..] {
            assert!(
                !SCHEMA_DESCRIPTOR.contains(code_role.name),
                "the widget, property and modifier descriptor does not list colour role {}",
                code_role.name
            );
            assert!(
                WIDGET_SCHEMA
                    .iter()
                    .all(|widget| widget.name != code_role.name),
                "code colour roles add no widget definition"
            );
            assert!(
                PROPERTY_SCHEMA
                    .iter()
                    .all(|property| property.name != code_role.name),
                "code colour roles add no property kind"
            );
            assert!(
                MODIFIER_SCHEMA
                    .iter()
                    .all(|modifier| modifier.name != code_role.name),
                "code colour roles add no modifier variant"
            );
        }

        for modifier in MODIFIER_SCHEMA {
            for field in modifier.fields {
                assert_ne!(
                    field.ty,
                    FieldType::Role("ColorRole"),
                    "modifier {} field {} should take a Paint rather than a dedicated ColorRole slot",
                    modifier.name,
                    field.name
                );
            }
        }

        let encode_one = |mutation: Mutation<'_>| -> Vec<u8> {
            let mut encoder = BatchEncoder::default();
            encoder.encode(&mutation).expect("encodes");
            encoder.finish().expect("finishes").to_vec()
        };
        let base_prop = encode_one(Mutation::SetProp {
            node_id: 1,
            property: PropertyKind::Color,
            value: PropertyValue::Integer(Paint::Role(ColorRole::Primary).to_bits() as i64),
        });
        let code_prop = encode_one(Mutation::SetProp {
            node_id: 1,
            property: PropertyKind::Color,
            value: PropertyValue::Integer(Paint::Role(ColorRole::SyntaxKeyword).to_bits() as i64),
        });
        assert_eq!(base_prop.len(), code_prop.len());

        let base_mod = encode_one(Mutation::SetModifier {
            node_id: 1,
            index: 0,
            modifier: Modifier::Background(Paint::Role(ColorRole::SurfaceContainer)),
        });
        let code_mod = encode_one(Mutation::SetModifier {
            node_id: 1,
            index: 0,
            modifier: Modifier::Background(Paint::Role(ColorRole::DiffAddedContainer)),
        });
        assert_eq!(base_mod.len(), code_mod.len());
    }
}
