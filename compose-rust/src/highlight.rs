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
}
