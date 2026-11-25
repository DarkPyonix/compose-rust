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

use crate::code::SyntaxFontStyle;
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

/// The default font style for a code colour role: italic for comments, medium (500) for types,
/// and normal (inherited) for every other role.
pub const fn style_for_role(role: ColorRole) -> SyntaxFontStyle {
    match role {
        ColorRole::SyntaxComment => SyntaxFontStyle::NORMAL.italic(),
        ColorRole::SyntaxType => SyntaxFontStyle::NORMAL.weight(500),
        _ => SyntaxFontStyle::NORMAL,
    }
}

/// The default font style for a highlight capture name, or [`SyntaxFontStyle::NORMAL`] where
/// the name has no role or the role uses the editor's base style.
pub fn style_for_highlight(name: &str) -> SyntaxFontStyle {
    role_for_highlight(name).map_or(SyntaxFontStyle::NORMAL, style_for_role)
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

    #[test]
    fn fr38_syntax_role_default_font_styles() {
        assert_eq!(
            style_for_highlight("comment"),
            SyntaxFontStyle::NORMAL.italic()
        );
        assert_eq!(
            style_for_highlight("comment.documentation"),
            SyntaxFontStyle::NORMAL.italic()
        );
        assert_eq!(
            style_for_highlight("type"),
            SyntaxFontStyle::NORMAL.weight(500)
        );
        assert_eq!(
            style_for_highlight("type.builtin"),
            SyntaxFontStyle::NORMAL.weight(500)
        );
        assert_eq!(style_for_highlight("keyword"), SyntaxFontStyle::NORMAL);
        assert_eq!(style_for_highlight("unknown"), SyntaxFontStyle::NORMAL);
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
            ("pub", "keyword", Some(ColorRole::SyntaxKeyword)),
            ("fn", "keyword", Some(ColorRole::SyntaxKeyword)),
            ("total", "function", Some(ColorRole::SyntaxFunction)),
            (
                "items",
                "variable.parameter",
                Some(ColorRole::SyntaxVariable),
            ),
            ("&", "operator", Some(ColorRole::SyntaxOperator)),
            (
                "[",
                "punctuation.bracket",
                Some(ColorRole::SyntaxPunctuation),
            ),
            ("u32", "type.builtin", Some(ColorRole::SyntaxType)),
            (
                "]",
                "punctuation.bracket",
                Some(ColorRole::SyntaxPunctuation),
            ),
            ("->", "operator", Some(ColorRole::SyntaxOperator)),
            ("// forty-two", "comment", Some(ColorRole::SyntaxComment)),
            ("items", "spell", None),
            ("len", "function.method", Some(ColorRole::SyntaxFunction)),
            ("+", "operator", Some(ColorRole::SyntaxOperator)),
            ("42", "number", Some(ColorRole::SyntaxNumber)),
        ];
        let mut cursor = 0;
        let mut spans = Vec::new();
        let mut expected_spans = Vec::new();
        for (lexeme, capture, expected_role) in tokens {
            let relative = source[cursor..]
                .find(lexeme)
                .unwrap_or_else(|| panic!("missing token {lexeme} after byte {cursor}"));
            let start = (cursor + relative) as u32;
            let length = lexeme.len() as u32;
            cursor = start as usize + lexeme.len();
            let span = highlight_span(start, length, capture);
            assert_eq!(
                span.as_ref().and_then(|s| s.color),
                expected_role.map(Paint::Role),
                "unexpected paint for {lexeme} ({capture})"
            );
            if let Some(span) = span {
                expected_spans.push((lexeme, expected_role.unwrap()));
                spans.push(span);
            }
        }
        assert_eq!(spans.len(), 13);

        let mut host = crate::ComposeHost::with_content(move || {
            let encoded_spans = TextSpans::new(spans.clone());
            crate::ui::widgets::Text(source).spans(encoded_spans);
        });
        let wire = host.rebuild().expect("the batch encodes").to_vec();
        let mutations = decode_batch(&wire).expect("the batch decodes");

        let mut blobs = Vec::new();
        for mutation in &mutations {
            match mutation {
                Mutation::SetProp {
                    property: PropertyKind::Spans,
                    value: PropertyValue::Bytes(bytes),
                    ..
                } => {
                    blobs.push(*bytes);
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

        assert_eq!(blobs.len(), 1, "one text, one list of runs: {mutations:?}");
        let span_bytes = blobs[0];
        assert_eq!(span_bytes.len(), expected_spans.len() * SPAN_LEN);

        for (record, (lexeme, _)) in span_bytes.chunks_exact(SPAN_LEN).zip(&expected_spans) {
            let start = u32::from_le_bytes([record[0], record[1], record[2], record[3]]) as usize;
            let len = u32::from_le_bytes([record[4], record[5], record[6], record[7]]) as usize;
            assert_eq!(&source[start..start + len], *lexeme);
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

        let decoded: Vec<_> = TextSpans::from_bytes(span_bytes).spans().collect();
        assert_eq!(decoded.len(), expected_spans.len());
        for (span, (lexeme, role)) in decoded.iter().zip(&expected_spans) {
            assert_eq!(
                span.color,
                Some(Paint::Role(*role)),
                "decoded span for {lexeme} must carry role {role:?}"
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
            COLOR_ROLE_COUNT, COLOR_ROLE_SCHEMA, EnumVariantSchema, FieldSlot, FieldType,
            MODIFIER_SCHEMA, Modifier, PROPERTY_SCHEMA, PropertyKind, SCHEMA_DESCRIPTOR,
            WIDGET_SCHEMA,
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

        let hash_bytes = |mut hash: u64, bytes: &[u8]| -> u64 {
            for &byte in bytes {
                hash ^= byte as u64;
                hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
            }
            hash
        };
        let hash_enum = |mut hash: u64, schema: &[EnumVariantSchema]| -> u64 {
            for entry in schema {
                hash = hash_bytes(hash, entry.name.as_bytes());
                hash = hash_bytes(hash, &entry.tag.to_le_bytes());
            }
            hash
        };
        let mut tables_hash = 0xcbf2_9ce4_8422_2325_u64;
        tables_hash = hash_enum(tables_hash, WIDGET_SCHEMA);
        tables_hash = hash_enum(tables_hash, PROPERTY_SCHEMA);
        for modifier in MODIFIER_SCHEMA {
            tables_hash = hash_bytes(tables_hash, modifier.name.as_bytes());
            tables_hash = hash_bytes(tables_hash, &modifier.tag.to_le_bytes());
            tables_hash = hash_bytes(tables_hash, &[modifier.extra_words]);
            for field in modifier.fields {
                tables_hash = hash_bytes(tables_hash, field.name.as_bytes());
                tables_hash = hash_bytes(
                    tables_hash,
                    &[match field.ty {
                        FieldType::Float => 1,
                        FieldType::U32 => 2,
                        FieldType::U64 => 3,
                        FieldType::Paint => 4,
                        FieldType::Role(_) => 5,
                        FieldType::Bool => 6,
                        FieldType::FiniteFloat => 7,
                        FieldType::OptionalPaint => 8,
                    }],
                );
                if let FieldType::Role(role) = field.ty {
                    tables_hash = hash_bytes(tables_hash, role.as_bytes());
                }
                tables_hash = hash_bytes(
                    tables_hash,
                    &[match field.slot {
                        FieldSlot::FirstLow => 0,
                        FieldSlot::FirstHigh => 1,
                        FieldSlot::First => 2,
                        FieldSlot::SecondLow => 3,
                        FieldSlot::SecondHigh => 4,
                        FieldSlot::Second => 5,
                        FieldSlot::ThirdLow => 6,
                        FieldSlot::ThirdHigh => 7,
                        FieldSlot::Third => 8,
                        FieldSlot::FourthLow => 9,
                        FieldSlot::FourthHigh => 10,
                        FieldSlot::Fourth => 11,
                        FieldSlot::FifthLow => 12,
                        FieldSlot::FifthHigh => 13,
                        FieldSlot::Fifth => 14,
                        FieldSlot::SixthLow => 15,
                        FieldSlot::SixthHigh => 16,
                        FieldSlot::Sixth => 17,
                        FieldSlot::SeventhLow => 18,
                        FieldSlot::SeventhHigh => 19,
                        FieldSlot::Seventh => 20,
                        FieldSlot::EighthLow => 21,
                        FieldSlot::EighthHigh => 22,
                        FieldSlot::Eighth => 23,
                        FieldSlot::NinthLow => 24,
                        FieldSlot::NinthHigh => 25,
                        FieldSlot::Ninth => 26,
                        FieldSlot::TenthLow => 27,
                        FieldSlot::TenthHigh => 28,
                        FieldSlot::Tenth => 29,
                    }],
                );
            }
        }
        // A recorded value, not a rule: a change that means to add a widget, a property or a
        // modifier updates the numbers below in the same commit. What this catches is a
        // change that moves them without meaning to, as adding a colour role must not.
        let on_purpose = "if this change adds or alters a widget, a property or a modifier on \
                          purpose, record the new value here; a change to colour roles alone \
                          must leave it as it is";
        assert_eq!(
            WIDGET_SCHEMA.len(),
            49,
            "the number of widgets moved: {on_purpose}"
        );
        assert_eq!(
            PROPERTY_SCHEMA.len(),
            159,
            "the number of properties moved: {on_purpose}"
        );
        assert_eq!(
            MODIFIER_SCHEMA.len(),
            45,
            "the number of modifiers moved: {on_purpose}"
        );
        assert_eq!(
            tables_hash, 0x356d_d2f8_078c_86f5,
            "the names, tags or modifier fields of the widget, property and modifier tables \
             moved, and now hash to {tables_hash:#018x}: {on_purpose}"
        );

        let folded = |name: &str| -> String {
            name.chars()
                .filter(|ch| *ch != '_')
                .map(|ch| ch.to_ascii_lowercase())
                .collect()
        };
        let mut entries = Vec::new();
        for section in SCHEMA_DESCRIPTOR.split(';') {
            if let Some(rest) = section.strip_prefix("widgets=") {
                for name in rest.split(',') {
                    entries.push(("widgets", name));
                }
            } else if let Some(rest) = section.strip_prefix("properties=") {
                for name in rest.split(',') {
                    entries.push(("properties", name));
                }
            } else if let Some(rest) = section.strip_prefix("modifiers=") {
                for name in rest.split(',') {
                    entries.push(("modifiers", name));
                }
            }
        }
        for widget in WIDGET_SCHEMA {
            entries.push(("widgets", widget.name));
        }
        for property in PROPERTY_SCHEMA {
            entries.push(("properties", property.name));
        }
        for modifier in MODIFIER_SCHEMA {
            entries.push(("modifiers", modifier.name));
            for field in modifier.fields {
                entries.push(("modifiers", field.name));
            }
        }
        let about_code: Vec<_> = entries
            .iter()
            .copied()
            .filter(|(_, name)| {
                let name = folded(name);
                (name.contains("syntax") || name.contains("diff")) && name != "syntaxspans"
            })
            .collect();
        assert_eq!(about_code, Vec::<(&str, &str)>::new());

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
        assert_eq!(base_prop.len(), 36);
        assert_eq!(code_prop.len(), 36);

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
        assert_eq!(base_mod.len(), 40);
        assert_eq!(code_mod.len(), 40);
    }
}
