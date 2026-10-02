//! A schema change the Renderer has not caught up with must not build.
//!
//! The chain has two links. `generated_protocol.rs` pins the first: the checked-in Kotlin
//! protocol has to be what the generator produces from the Rust schema, so a new widget or
//! property that nobody regenerated fails there. This file pins the second: every variant
//! the generator emits has to be named by the interpreter that dispatches on it.
//!
//! Kotlin already enforces that, because a `when` over an enum or a sealed interface must
//! cover every case. The rule stops applying the moment someone writes an `else` arm, and
//! then a new variant reaches the Renderer as silence rather than as a compile error. That
//! is the failure this file is here to notice, and it notices it without a Kotlin build:
//! the variant names come from the generator and are looked for in the interpreter's
//! source, so `cargo test` alone reports it.

use dioxus_compose::codegen::generate_kotlin;

const RENDERER: &str = concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../dioxus-compose-renderer/desktop/src/renderer/"
);

const NODE_TABLE: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../dioxus-compose-renderer/desktop/src/renderer/NodeTable.kt"
));
const RENDER_NODE: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../dioxus-compose-renderer/desktop/src/renderer/RenderNode.kt"
));
const MODIFIER_CHAIN: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../dioxus-compose-renderer/desktop/src/renderer/ModifierChain.kt"
));

/// The source with comments and string literals blanked out, one character in for one
/// character out. Braces and variant names inside a comment are not code, and a test that
/// counted them would follow the wrong brace or pass on a name that is only explained.
fn code_only(source: &str) -> Vec<char> {
    let chars: Vec<char> = source.chars().collect();
    let mut out = Vec::with_capacity(chars.len());
    let mut index = 0;
    while index < chars.len() {
        let rest = chars.len() - index;
        let two = |a: char, b: char| rest >= 2 && chars[index] == a && chars[index + 1] == b;
        if two('/', '/') {
            while index < chars.len() && chars[index] != '\n' {
                out.push(' ');
                index += 1;
            }
        } else if two('/', '*') {
            while index < chars.len()
                && !(chars[index] == '*' && chars.get(index + 1) == Some(&'/'))
            {
                out.push(if chars[index] == '\n' { '\n' } else { ' ' });
                index += 1;
            }
            let closing = 2.min(chars.len() - index);
            out.extend(std::iter::repeat_n(' ', closing));
            index += closing;
        } else if chars[index] == '"' {
            out.push(' ');
            index += 1;
            while index < chars.len() && chars[index] != '"' {
                let escaped = chars[index] == '\\';
                out.push(' ');
                index += 1;
                if escaped && index < chars.len() {
                    out.push(' ');
                    index += 1;
                }
            }
            if index < chars.len() {
                out.push(' ');
                index += 1;
            }
        } else {
            out.push(chars[index]);
            index += 1;
        }
    }
    out
}

/// The body of the `when` that dispatches on `subject`, braces balanced.
fn when_block(source: &str, subject: &str) -> String {
    let code: String = code_only(source).into_iter().collect();
    let needle = format!("when ({subject})");
    let start = code
        .find(&needle)
        .unwrap_or_else(|| panic!("no `{needle}` in the interpreter; was it renamed?"));
    let open = code[start..]
        .find('{')
        .unwrap_or_else(|| panic!("`{needle}` has no body"))
        + start;
    let mut depth = 0usize;
    for (offset, character) in code[open..].char_indices() {
        match character {
            '{' => depth += 1,
            '}' => {
                depth -= 1;
                if depth == 0 {
                    return code[open + 1..open + offset].to_string();
                }
            }
            _ => {}
        }
    }
    panic!("`{needle}` is never closed");
}

/// Whether `block` names `prefix.name` as a whole word. `WidgetKind.Text` is a prefix of
/// `WidgetKind.TextField`, so a plain substring search would report a widget as handled
/// because a different widget's name starts the same way.
fn names(block: &str, prefix: &str, name: &str) -> bool {
    let needle = format!("{prefix}.{name}");
    let mut from = 0;
    while let Some(found) = block[from..].find(&needle) {
        let end = from + found + needle.len();
        match block[end..].chars().next() {
            Some(next) if next.is_alphanumeric() || next == '_' => from = end,
            _ => return true,
        }
    }
    false
}

/// The members of `enum class <name> { A, B, C }` in the generated protocol.
fn enum_variants(generated: &str, name: &str) -> Vec<String> {
    let header = format!("enum class {name} {{");
    let start = generated
        .find(&header)
        .unwrap_or_else(|| panic!("the generator emits no `{header}`"))
        + header.len();
    let end = generated[start..].find('}').expect("unterminated enum") + start;
    generated[start..end]
        .split(',')
        .map(str::trim)
        .filter(|variant| !variant.is_empty())
        .map(str::to_string)
        .collect()
}

/// The members of `sealed interface <name> { ... }` in the generated protocol.
fn sealed_variants(generated: &str, name: &str) -> Vec<String> {
    let header = format!("sealed interface {name} {{");
    let start = generated
        .find(&header)
        .unwrap_or_else(|| panic!("the generator emits no `{header}`"))
        + header.len();
    let end = generated[start..]
        .find("\n}")
        .expect("unterminated interface")
        + start;
    generated[start..end]
        .lines()
        .filter_map(|line| {
            let line = line.trim();
            let rest = line
                .strip_prefix("data class ")
                .or_else(|| line.strip_prefix("data object "))
                .or_else(|| line.strip_prefix("class "))?;
            Some(
                rest.split(['(', ' ', ':'])
                    .next()
                    .unwrap_or_default()
                    .to_string(),
            )
        })
        .filter(|variant| !variant.is_empty())
        .collect()
}

fn assert_covered(block: &str, prefix: &str, variants: &[String], site: &str) {
    assert!(!variants.is_empty(), "no variants were read for {prefix}");
    let missing: Vec<&str> = variants
        .iter()
        .map(String::as_str)
        .filter(|variant| !names(block, prefix, variant))
        .collect();
    assert!(
        missing.is_empty(),
        "{site} does not name {prefix}.{{{}}}. The schema grew and the interpreter did \
         not: give each one an arm, and do not reach for an `else`, because an `else` is \
         what lets the next one through without a word.",
        missing.join(", ")
    );
}

#[test]
fn fr7_every_widget_in_the_schema_has_an_arm_in_the_interpreter() {
    let generated = generate_kotlin();
    let block = when_block(RENDER_NODE, "node.widget");
    assert_covered(
        &block,
        "WidgetKind",
        &enum_variants(&generated, "WidgetKind"),
        "RenderNode.kt",
    );
}

#[test]
fn fr7_every_property_in_the_schema_has_an_arm_in_the_interpreter() {
    let generated = generate_kotlin();
    let block = when_block(NODE_TABLE, "property");
    assert_covered(
        &block,
        "PropertyKind",
        &enum_variants(&generated, "PropertyKind"),
        "NodeTable.kt",
    );
}

#[test]
fn fr7_every_modifier_in_the_schema_has_an_arm_in_the_interpreter() {
    let generated = generate_kotlin();
    let block = when_block(MODIFIER_CHAIN, "value");
    // The interpreter imports the protocol's `Modifier` under another name, because
    // Compose has a `Modifier` of its own and both are in scope there.
    assert_covered(
        &block,
        "ProtocolModifier",
        &sealed_variants(&generated, "Modifier"),
        "ModifierChain.kt",
    );
}

#[test]
fn fr7_every_mutation_in_the_schema_has_an_arm_in_the_interpreter() {
    let generated = generate_kotlin();
    let block = when_block(NODE_TABLE, "mutation");
    assert_covered(
        &block,
        "Mutation",
        &sealed_variants(&generated, "Mutation"),
        "NodeTable.kt",
    );
}

/// The interpreter the Renderer runs on iOS is the desktop tree, symlinked rather than
/// copied, so that the arms above cannot be right on one platform and stale on the other.
#[test]
fn fr7_the_ios_interpreter_is_the_same_file_as_the_desktop_one() {
    for file in ["NodeTable.kt", "RenderNode.kt", "ModifierChain.kt"] {
        let desktop = std::fs::canonicalize(format!("{RENDERER}{file}"))
            .unwrap_or_else(|error| panic!("{file}: {error}"));
        let ios = std::fs::canonicalize(format!(
            "{}/../dioxus-compose-renderer/ios/src/shared/{file}",
            env!("CARGO_MANIFEST_DIR")
        ))
        .unwrap_or_else(|error| panic!("ios/src/shared/{file}: {error}"));
        assert_eq!(
            desktop, ios,
            "ios/src/shared/{file} is a second copy of the interpreter, not the same file"
        );
    }
}
