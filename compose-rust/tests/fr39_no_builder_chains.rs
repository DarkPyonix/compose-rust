//! No builder chains in the public API.
//!
//! These read the crate's sources as text, macro bodies included, because most of the old
//! builder methods were written inside `macro_rules!` where a parser does not look. Each
//! check is a function over one file's path and text, so the tests at the bottom can feed
//! it a small source that breaks the rule and see it reported: a check that cannot fail
//! proves nothing.

use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};

/// The value types that are built by chaining and then passed to a widget as an argument,
/// as Compose's `Modifier` is. Adding one is a decision, so it is added here by name.
const VALUE_TYPES: &[&str] = &[
    "Modifier",
    "TextSpans",
    "DrawListBuilder",
    "HoverStyle",
    "PressStyle",
    "Shadow",
    "LaunchBuilder",
    "BackgroundBrush",
    "SemanticsState",
    "Message",
    "KeyEvent",
    // The types below chain as well and were found when this check widened from `ui` to
    // the whole crate. They are listed so the check passes while each one is decided.
    // One styled run of a `TextSpans`.
    "TextSpan",
    // A code editor's underline decoration on a range.
    "Decoration",
    // The weight, slant and underline of one syntax span.
    "SyntaxFontStyle",
    // One highlighted run of a code editor's text.
    "SyntaxSpan",
    // A stretch of lines a code editor can fold.
    "Fold",
    // A mark in a code editor's gutter.
    "GutterDecoration",
    // Rows a code editor inserts between lines.
    "ViewZone",
    // The colour roles of a code editor's parts.
    "CodeEditorColors",
    // An application's colour palette for a theme.
    "Palette",
    // A system notification, built and then posted.
    "Notification",
    // The window's size class, as a composable reads it.
    "WindowSize",
    // The launch settings of a window (`compose_rust::schema::Window`), not the widget.
    "Window",
    // The application's overlay theme tokens.
    "OverlayTokens",
    // The design system choice and its tokens.
    "Theme",
    // A bit set of animation moments; `union` joins two.
    "AnimationEvents",
    // A bit set of pointer event kinds; `union` joins two.
    "PointerKinds",
    // A file dialog, built and then shown.
    "FileDialog",
    // A font fallback stack.
    "FontStack",
    // The test and benchmark Host; `with_window` sets its window settings.
    "ComposeHost",
];

/// Every `.rs` file under `dir`, with its text.
fn read_tree(dir: &Path, out: &mut Vec<(PathBuf, String)>) {
    for entry in fs::read_dir(dir).expect("the sources are readable") {
        let path = entry.expect("a directory entry").path();
        if path.is_dir() {
            read_tree(&path, out);
        } else if path.extension().is_some_and(|extension| extension == "rs") {
            let text = fs::read_to_string(&path).expect("a source file is readable");
            out.push((path, text));
        }
    }
}

/// The sources of the library crate: everything under `src` except `src/bin`, whose files
/// are separate binaries that export nothing.
///
/// This is the whole crate and not only what `compose_rust::ui` re-exports. Following
/// re-exports by text would be a guess, and a public type in any public module is as much
/// a part of the API as one in `ui`. A public type in a private module that nothing
/// re-exports is counted as well; that errs towards checking too much, never too little.
fn library_sources() -> Vec<(PathBuf, String)> {
    let src = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let bin = src.join("bin");
    let mut out = Vec::new();
    read_tree(&src, &mut out);
    out.retain(|(path, _)| !path.starts_with(&bin));
    assert!(
        out.iter().any(|(path, _)| path.ends_with("message.rs")),
        "the crate sources were not found under {}",
        src.display()
    );
    out
}

/// The sources of `compose_rust::ui`.
fn ui_sources() -> Vec<(PathBuf, String)> {
    let mut out = Vec::new();
    read_tree(
        &Path::new(env!("CARGO_MANIFEST_DIR")).join("src/ui"),
        &mut out,
    );
    assert!(!out.is_empty(), "no sources were found under src/ui");
    out
}

// ----- reading source text ------------------------------------------------------------

/// The lines of `text` with `//` comments taken out, doc comments included, so that an
/// example in a doc comment is not read as code. A `//` inside a string literal stays.
fn code_lines(text: &str) -> Vec<String> {
    text.lines()
        .map(|line| {
            let bytes = line.as_bytes();
            let mut in_string = false;
            let mut index = 0;
            while index < bytes.len() {
                match bytes[index] {
                    b'\\' if in_string => index += 1,
                    b'"' => in_string = !in_string,
                    b'/' if !in_string && bytes.get(index + 1) == Some(&b'/') => {
                        return line[..index].to_string();
                    }
                    _ => {}
                }
                index += 1;
            }
            line.to_string()
        })
        .collect()
}

fn is_identifier_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'$'
}

/// The columns where a public function starts on a line: `pub fn`, `pub const fn`,
/// `pub unsafe fn` and the like, anywhere on the line, since a `macro_rules!` body can put
/// an attribute or a whole item before it. `pub(crate)` and the other restricted forms do
/// not match.
fn public_fn_starts(line: &str) -> Vec<usize> {
    let mut starts = Vec::new();
    let mut from = 0;
    while let Some(found) = line[from..].find("pub ") {
        let at = from + found;
        from = at + 4;
        if at > 0 && is_identifier_byte(line.as_bytes()[at - 1]) {
            continue;
        }
        let mut rest = line[at + 4..].trim_start();
        loop {
            let current = rest;
            if let Some(after) = ["const ", "unsafe ", "async "]
                .iter()
                .find_map(|qualifier| current.strip_prefix(*qualifier))
            {
                rest = after.trim_start();
            } else if let Some(after) = rest.strip_prefix("extern ") {
                rest = after.trim_start();
                if let Some(quoted) = rest.strip_prefix('"') {
                    rest = quoted
                        .split_once('"')
                        .map_or("", |(_, after)| after)
                        .trim_start();
                }
            } else {
                break;
            }
        }
        if rest.starts_with("fn ") {
            starts.push(at);
        }
    }
    starts
}

/// The text of a signature that starts at `column` on `lines[start]`, up to the line that
/// opens its body or ends it.
fn signature_from(lines: &[String], start: usize, column: usize) -> String {
    let mut text = String::new();
    for (offset, line) in lines[start..].iter().enumerate().take(80) {
        let part = if offset == 0 {
            &line[column..]
        } else {
            line.as_str()
        };
        text.push_str(part.trim());
        text.push(' ');
        let end = line.trim_end();
        if end.ends_with('{') || end.ends_with(';') || end.ends_with('}') {
            break;
        }
    }
    text
}

/// `text` from its first character past a balanced `<..>`, if it starts with one. The `>`
/// of an arrow inside it does not close it.
fn skip_generics(text: &str) -> &str {
    let text = text.trim_start();
    if !text.starts_with('<') {
        return text;
    }
    let bytes = text.as_bytes();
    let mut depth = 0;
    for (index, byte) in bytes.iter().enumerate() {
        match byte {
            b'<' => depth += 1,
            b'>' if index > 0 && bytes[index - 1] == b'-' => {}
            b'>' => {
                depth -= 1;
                if depth == 0 {
                    return text[index + 1..].trim_start();
                }
            }
            _ => {}
        }
    }
    ""
}

/// A function signature taken apart: the text inside its parameter list and its return
/// type, empty when it returns nothing. Generics and parameter types can hold parentheses
/// and arrows of their own, so this counts brackets rather than searching for the first
/// `(` and the last `)`.
fn split_signature(signature: &str) -> Option<(String, String)> {
    let at = signature.find("fn ")?;
    let after_name = signature[at + 3..]
        .trim_start()
        .trim_start_matches(|c: char| c.is_alphanumeric() || c == '_' || c == '$');
    let rest = skip_generics(after_name);
    if !rest.starts_with('(') {
        return None;
    }
    let mut depth = 0;
    let mut close = None;
    for (index, character) in rest.char_indices() {
        match character {
            '(' => depth += 1,
            ')' => {
                depth -= 1;
                if depth == 0 {
                    close = Some(index);
                    break;
                }
            }
            _ => {}
        }
    }
    let close = close?;
    let parameters = rest[1..close].to_string();
    let after = rest[close + 1..].trim_start();
    let returns = match after.strip_prefix("->") {
        Some(returns) => {
            let end = [
                returns.find(" where "),
                returns.find('{'),
                returns.find(';'),
            ]
            .into_iter()
            .flatten()
            .min()
            .unwrap_or(returns.len());
            returns[..end].trim().to_string()
        }
        None => String::new(),
    };
    Some((parameters, returns))
}

// ----- builder chains -----------------------------------------------------------------

/// What a method is declared in: the type of an `impl` block or a trait.
struct Container {
    name: String,
    /// A public trait's methods are public without a `pub` of their own.
    public_trait: bool,
}

/// The type an `impl` header is for: the last path segment before the body, without its
/// generics. `impl<'a> MenuScope<'a>` is `MenuScope`, `impl $name` is `$name`, and
/// `impl<T> From<T> for Wrapper<T>` is `Wrapper`.
fn impl_target(head: &str) -> Option<String> {
    let head = head.trim();
    let head = head.strip_prefix("unsafe ").unwrap_or(head);
    let after = head.strip_prefix("impl")?;
    let target = skip_generics(after);
    let target = target.rsplit(" for ").next()?.trim();
    let target = target.trim_start_matches(['&', ' ']);
    let name: String = target
        .chars()
        .take_while(|c| c.is_alphanumeric() || *c == '_' || *c == '$')
        .collect();
    (!name.is_empty()).then_some(name)
}

/// The `impl` block or trait whose header starts on `lines[index]`, if one does.
fn container_at(lines: &[String], index: usize) -> Option<Container> {
    let line = lines[index].trim();
    let unqualified = line.strip_prefix("unsafe ").unwrap_or(line);
    let opens_impl = unqualified.starts_with("impl ") || unqualified.starts_with("impl<");
    // A parameter written `impl Trait` on a line of its own ends with a comma or a
    // parenthesis, and is not an `impl` block.
    if opens_impl && !line.ends_with(',') && !line.ends_with(')') {
        let mut head = String::new();
        for line in lines[index..].iter().take(8) {
            head.push_str(line.trim());
            head.push(' ');
            if line.contains('{') {
                let head = head.split('{').next().unwrap_or("");
                return impl_target(head).map(|name| Container {
                    name,
                    public_trait: false,
                });
            }
        }
        return None;
    }
    let (public_trait, after) = if let Some(after) = line.strip_prefix("pub trait ") {
        (true, after)
    } else if let Some(after) = line.strip_prefix("pub unsafe trait ") {
        (true, after)
    } else if let Some(after) = line.strip_prefix("trait ") {
        (false, after)
    } else if let Some((_, after)) = line
        .strip_prefix("pub(")
        .and_then(|rest| rest.split_once(") trait "))
    {
        (false, after)
    } else {
        return None;
    };
    let name: String = after
        .chars()
        .take_while(|c| c.is_alphanumeric() || *c == '_' || *c == '$')
        .collect();
    (!name.is_empty()).then_some(Container { name, public_trait })
}

/// The names of the types declared plainly `pub` in a file: `pub struct`, `pub enum`,
/// `pub type` and `pub union`. A type declared `pub(crate)` or private is not here, so the
/// `pub` methods on it are not reachable from outside the crate.
fn public_types_in(text: &str, out: &mut HashSet<String>) {
    for line in code_lines(text) {
        for keyword in ["pub struct ", "pub enum ", "pub type ", "pub union "] {
            let mut from = 0;
            while let Some(found) = line[from..].find(keyword) {
                let at = from + found;
                from = at + keyword.len();
                if at > 0 && is_identifier_byte(line.as_bytes()[at - 1]) {
                    continue;
                }
                let name: String = line[at + keyword.len()..]
                    .chars()
                    .take_while(|c| c.is_alphanumeric() || *c == '_')
                    .collect();
                if !name.is_empty() {
                    out.insert(name);
                }
            }
        }
    }
}

/// Whether `ty` names the type the method is declared on.
fn names_the_type(ty: &str, target: &str) -> bool {
    let ty = ty.trim();
    ty == "Self"
        || (!target.is_empty()
            && (ty == target
                || ty
                    .strip_prefix(target)
                    .is_some_and(|rest| rest.starts_with('<'))))
}

/// `text` past a leading `&` and an optional lifetime: `&'a mut Self` is `mut Self`.
fn past_reference(text: &str) -> Option<&str> {
    let rest = text.trim().strip_prefix('&')?.trim_start();
    if rest.starts_with('\'') {
        return rest.split_once(' ').map(|(_, rest)| rest.trim_start());
    }
    Some(rest)
}

/// Whether a method with these parameters and this return type is a link of a builder
/// chain: it takes `self` and returns `Self`, or takes `&mut self` and returns
/// `&mut Self`.
fn is_chain_link(parameters: &str, returns: &str, target: &str) -> bool {
    let receiver = parameters.split(',').next().unwrap_or("").trim();
    if receiver == "self" || receiver == "mut self" {
        return names_the_type(returns, target);
    }
    if past_reference(receiver) == Some("mut self") {
        return past_reference(returns)
            .and_then(|rest| rest.strip_prefix("mut "))
            .is_some_and(|rest| names_the_type(rest, target));
    }
    false
}

/// The public methods in one file that are links of a builder chain on a public type that
/// is not one of `allowed`, one line each.
fn chain_methods(
    path: &Path,
    text: &str,
    public_types: &HashSet<String>,
    allowed: &[&str],
) -> Vec<String> {
    let lines = code_lines(text);
    let mut found = Vec::new();
    let mut container: Option<Container> = None;
    for (index, line) in lines.iter().enumerate() {
        if let Some(opened) = container_at(&lines, index) {
            container = Some(opened);
        }
        let Some(current) = &container else {
            continue;
        };
        let mut starts = public_fn_starts(line);
        if current.public_trait {
            let trimmed = line.trim_start();
            if trimmed.starts_with("fn ") {
                starts.push(line.len() - trimmed.len());
            }
        }
        for column in starts {
            let signature = signature_from(&lines, index, column);
            let Some((parameters, returns)) = split_signature(&signature) else {
                continue;
            };
            if !is_chain_link(&parameters, &returns, &current.name) {
                continue;
            }
            // A metavariable is whatever type a macro is given, so it is checked.
            let reachable = current.public_trait
                || current.name.starts_with('$')
                || public_types.contains(&current.name);
            if reachable && !allowed.contains(&current.name.as_str()) {
                found.push(format!(
                    "{}:{}: `{}` on `{}`",
                    path.display(),
                    index + 1,
                    signature.trim(),
                    current.name
                ));
            }
        }
    }
    found
}

#[test]
fn fr39_function_form_no_public_method_chains_outside_the_value_types() {
    let sources = library_sources();
    let mut public_types = HashSet::new();
    for (_, text) in &sources {
        public_types_in(text, &mut public_types);
    }
    let found: Vec<String> = sources
        .iter()
        .flat_map(|(path, text)| chain_methods(path, text, &public_types, VALUE_TYPES))
        .collect();
    assert!(
        found.is_empty(),
        "a public method that takes `self` and returns `Self`, or takes `&mut self` and \
         returns `&mut Self`, is a builder chain. A widget option is a parameter of the \
         widget's function instead; a type that is built by chaining and passed as a value \
         is named in this test's list:\n{}",
        found.join("\n")
    );
}

#[test]
fn fr39_function_form_no_builder_chains_check_reports_what_it_should() {
    let path = Path::new("probe.rs");
    let source = r#"
pub struct Panel;
pub(crate) struct Inner;
pub struct Modifier;
pub struct Buffer;

impl Panel {
    pub fn title(mut self, title: &str) -> Self { self }
    pub(crate) fn hidden(mut self) -> Self { self }
    /// pub fn in_a_comment(self) -> Self
    pub fn finish(self) -> Panel { self }
    pub fn reset(&mut self) {}
}

impl Inner {
    pub fn unreachable(mut self) -> Self { self }
}

impl Modifier {
    pub const fn padding(mut self, dp: f32) -> Self { self }
}

impl Buffer {
    pub fn push(&mut self, value: u8) -> &mut Self { self }
}

macro_rules! chain {
    ($name:ident) => {
        impl $name {
            $(#[$meta])* pub fn tint(mut self, paint: u32) -> Self { self }
        }
    };
}

pub trait Styled {
    fn bold(self) -> Self;
}
"#;
    let mut public_types = HashSet::new();
    public_types_in(source, &mut public_types);
    let found = chain_methods(path, source, &public_types, &["Modifier"]);
    let reported = |name: &str| found.iter().any(|line| line.contains(name));
    assert!(reported("fn title"), "a by-value chain: {found:#?}");
    assert!(
        reported("fn finish"),
        "a chain naming its own type: {found:#?}"
    );
    assert!(reported("fn push"), "a `&mut Self` chain: {found:#?}");
    assert!(
        reported("fn tint"),
        "a chain inside a macro body: {found:#?}"
    );
    assert!(reported("fn bold"), "a public trait's chain: {found:#?}");
    assert!(!reported("fn hidden"), "a pub(crate) method: {found:#?}");
    assert!(!reported("in_a_comment"), "a comment: {found:#?}");
    assert!(!reported("fn reset"), "not a chain: {found:#?}");
    assert!(!reported("fn unreachable"), "a pub(crate) type: {found:#?}");
    assert!(!reported("fn padding"), "an allowed value type: {found:#?}");
    assert_eq!(found.len(), 5, "{found:#?}");
}

// ----- widget functions return nothing ------------------------------------------------

/// The public functions in one file whose name starts with a capital, as a widget's does,
/// or is a macro's metavariable, and that return a value, one line each.
fn widgets_that_return(path: &Path, text: &str) -> Vec<String> {
    let lines = code_lines(text);
    let mut found = Vec::new();
    for (index, line) in lines.iter().enumerate() {
        for column in public_fn_starts(line) {
            let signature = signature_from(&lines, index, column);
            let Some(at) = signature.find("fn ") else {
                continue;
            };
            let name = signature[at + 3..].trim_start();
            if !name.starts_with(|c: char| c.is_ascii_uppercase() || c == '$') {
                continue;
            }
            let returns = split_signature(&signature)
                .map(|(_, returns)| returns)
                .unwrap_or_default();
            if !returns.is_empty() {
                found.push(format!(
                    "{}:{}: `{}`",
                    path.display(),
                    index + 1,
                    signature.trim()
                ));
            }
        }
    }
    found
}

#[test]
fn fr39_function_form_every_widget_function_returns_nothing() {
    let found: Vec<String> = ui_sources()
        .iter()
        .flat_map(|(path, text)| widgets_that_return(path, text))
        .collect();
    assert!(
        found.is_empty(),
        "a widget function that returns a value hands the caller something to chain on:\n{}",
        found.join("\n")
    );
}

#[test]
fn fr39_function_form_returns_nothing_check_reports_what_it_should() {
    let source = r#"
pub fn Panel(modifier: Modifier, on_pick: impl Fn(u32) -> bool) -> PanelScope {}
pub fn Plain(modifier: Modifier, on_pick: impl Fn(u32) -> bool) {}
pub(crate) fn Builder() -> Scope {}
"#;
    let found = widgets_that_return(Path::new("probe.rs"), source);
    assert_eq!(found.len(), 1, "{found:#?}");
    assert!(found[0].contains("fn Panel"), "{found:#?}");
}
