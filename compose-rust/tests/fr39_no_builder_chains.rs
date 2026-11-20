//! No builder chains in the public widget API.
//!
//! A widget is a function that returns nothing, so there is nothing to chain on. This
//! reads the sources of `compose_rust::ui` as text, macro bodies included, because most of
//! the old builder methods were written inside `macro_rules!` where a parser does not look.

use std::fs;
use std::path::{Path, PathBuf};

/// The value types that are built by chaining and then passed to a widget as an argument,
/// as Compose's `Modifier` is. Adding one is a decision, so it is added here by name.
const VALUE_TYPES: &[&str] = &[
    "Modifier",
    "HoverStyle",
    "PressStyle",
    "Shadow",
    "BackgroundBrush",
    "SemanticsState",
];

fn sources() -> Vec<(PathBuf, String)> {
    fn walk(dir: &Path, out: &mut Vec<(PathBuf, String)>) {
        for entry in fs::read_dir(dir).expect("the ui sources are readable") {
            let path = entry.expect("a directory entry").path();
            if path.is_dir() {
                walk(&path, out);
            } else if path.extension().is_some_and(|extension| extension == "rs") {
                let text = fs::read_to_string(&path).expect("a source file is readable");
                out.push((path, text));
            }
        }
    }
    let mut out = Vec::new();
    walk(
        &Path::new(env!("CARGO_MANIFEST_DIR")).join("src/ui"),
        &mut out,
    );
    assert!(!out.is_empty(), "no sources were found under src/ui");
    out
}

/// The text of a signature that starts on `lines[start]`, up to its body or its end.
fn signature(lines: &[&str], start: usize) -> String {
    let mut text = String::new();
    for line in &lines[start..] {
        text.push_str(line.trim());
        text.push(' ');
        if line.trim_end().ends_with('{') || line.trim_end().ends_with(';') {
            break;
        }
    }
    text
}

/// The type an `impl` line is for: the last path segment before the body, without its
/// generics. `impl<'a> MenuScope<'a> {` is `MenuScope`, `impl $name {` is `$name`.
fn impl_target(line: &str) -> Option<String> {
    let line = line.trim();
    // A parameter written `impl Trait` on a line of its own is not an `impl` block.
    if !line.starts_with("impl") || !line.ends_with('{') {
        return None;
    }
    let head = line.split('{').next()?.trim();
    let head = head.rsplit(" for ").next()?;
    let mut target = head.trim_start_matches("impl").trim();
    if target.starts_with('<') {
        target = target.split_once('>')?.1.trim();
    }
    let name: String = target
        .chars()
        .take_while(|c| c.is_alphanumeric() || *c == '_' || *c == '$')
        .collect();
    (!name.is_empty()).then_some(name)
}

#[test]
fn fr39_function_form_no_public_method_chains_outside_the_value_types() {
    let mut found = Vec::new();
    for (path, text) in sources() {
        let lines: Vec<&str> = text.lines().collect();
        let mut target = String::new();
        for (index, line) in lines.iter().enumerate() {
            if let Some(name) = impl_target(line) {
                target = name;
            }
            let trimmed = line.trim_start();
            if !trimmed.starts_with("pub fn ") && !trimmed.starts_with("pub const fn ") {
                continue;
            }
            let signature = signature(&lines, index);
            let takes_self = signature.contains("(mut self")
                || signature.contains("(self,")
                || signature.contains("(self)");
            let returns_self = signature.contains("-> Self");
            if takes_self && returns_self && !VALUE_TYPES.contains(&target.as_str()) {
                found.push(format!(
                    "{}:{}: `{}` on `{target}`",
                    path.display(),
                    index + 1,
                    signature.trim()
                ));
            }
        }
    }
    assert!(
        found.is_empty(),
        "a public method that takes `self` and returns `Self` is a builder chain. A widget \
         option is a parameter of the widget's function instead:\n{}",
        found.join("\n")
    );
}

#[test]
fn fr39_function_form_every_widget_function_returns_nothing() {
    let mut found = Vec::new();
    for (path, text) in sources() {
        let lines: Vec<&str> = text.lines().collect();
        for (index, line) in lines.iter().enumerate() {
            // A widget's name starts with a capital, as Compose's does, or is the name a
            // local macro was given for it.
            let Some(rest) = line.trim_start().strip_prefix("pub fn ") else {
                continue;
            };
            if !rest.starts_with(|c: char| c.is_ascii_uppercase() || c == '$') {
                continue;
            }
            let signature = signature(&lines, index);
            // What follows the parameter list. A `->` inside it belongs to a parameter.
            let after_parameters = signature.rsplit(')').next().unwrap_or("");
            if after_parameters.contains("->") {
                found.push(format!(
                    "{}:{}: `{}`",
                    path.display(),
                    index + 1,
                    signature.trim()
                ));
            }
        }
    }
    assert!(
        found.is_empty(),
        "a widget function that returns a value hands the caller something to chain on:\n{}",
        found.join("\n")
    );
}
