//! No builder chains in the public API, and the widget functions keep one parameter order.
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

/// The sources of the widget functions.
fn widget_function_sources() -> Vec<(PathBuf, String)> {
    let mut out = Vec::new();
    read_tree(
        &Path::new(env!("CARGO_MANIFEST_DIR")).join("src/ui/functions"),
        &mut out,
    );
    assert!(
        out.len() > 1,
        "no widget functions were found under src/ui/functions"
    );
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

/// The type names a file declares, as far as its text says.
#[derive(Default)]
struct Types {
    /// Declared plainly `pub`: `pub struct`, `pub enum`, `pub type` or `pub union`.
    public: HashSet<String>,
    /// Declared at the start of a line without `pub`, or with `pub(crate)` and the other
    /// restricted forms.
    hidden: HashSet<String>,
}

impl Types {
    /// Whether the methods of `name` can be reached from outside the crate. A type that is
    /// declared nowhere in the text was made by a macro, such as the wire enums, and is
    /// counted as public: this errs towards checking too much.
    fn reachable(&self, name: &str) -> bool {
        self.public.contains(name) || !self.hidden.contains(name)
    }
}

/// Adds the types `text` declares to `types`.
fn types_in(text: &str, types: &mut Types) {
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
                    types.public.insert(name);
                }
            }
        }
        let trimmed = line.trim_start();
        let unrestricted = match trimmed.strip_prefix("pub(") {
            Some(rest) => rest
                .split_once(')')
                .map_or("", |(_, rest)| rest.trim_start()),
            None if trimmed.starts_with("pub ") => continue,
            None => trimmed,
        };
        for keyword in ["struct ", "enum ", "type ", "union "] {
            if let Some(rest) = unrestricted.strip_prefix(keyword) {
                let name: String = rest
                    .chars()
                    .take_while(|c| c.is_alphanumeric() || *c == '_')
                    .collect();
                if !name.is_empty() {
                    types.hidden.insert(name);
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
fn chain_methods(path: &Path, text: &str, types: &Types, allowed: &[&str]) -> Vec<String> {
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
                || types.reachable(&current.name);
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
    let mut types = Types::default();
    for (_, text) in &sources {
        types_in(text, &mut types);
    }
    let found: Vec<String> = sources
        .iter()
        .flat_map(|(path, text)| chain_methods(path, text, &types, VALUE_TYPES))
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

impl MadeByAMacro {
    pub fn shade(mut self) -> Self { self }
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
    let mut types = Types::default();
    types_in(source, &mut types);
    let found = chain_methods(path, source, &types, &["Modifier"]);
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
    assert!(reported("fn shade"), "a type no text declares: {found:#?}");
    assert!(!reported("fn hidden"), "a pub(crate) method: {found:#?}");
    assert!(!reported("in_a_comment"), "a comment: {found:#?}");
    assert!(!reported("fn reset"), "not a chain: {found:#?}");
    assert!(!reported("fn unreachable"), "a pub(crate) type: {found:#?}");
    assert!(!reported("fn padding"), "an allowed value type: {found:#?}");
    assert_eq!(found.len(), 6, "{found:#?}");
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

// ----- parameter order ------------------------------------------------------------------

/// Widgets with no `modifier`, each with the reason. A window is not laid out in a parent,
/// so there is nothing for a modifier to change.
const NO_MODIFIER: &[&str] = &["Window"];

/// One parameter of a widget function as its source declares it.
#[derive(Debug)]
struct Parameter {
    name: String,
    /// It has a `#[default(..)]`, so the call macro can leave it out.
    optional: bool,
}

/// The text inside the first balanced `open .. close` pair at the start of `text`, and
/// what follows it.
fn balanced(text: &str, open: char, close: char) -> Option<(&str, &str)> {
    if !text.starts_with(open) {
        return None;
    }
    let mut depth = 0;
    for (index, character) in text.char_indices() {
        if character == open {
            depth += 1;
        } else if character == close {
            depth -= 1;
            if depth == 0 {
                return Some((&text[open.len_utf8()..index], &text[index + 1..]));
            }
        }
    }
    None
}

/// The parameters of a parameter list, in order.
///
/// A `macro_rules!` repetition, `$( .. )*`, is one entry named after it. It is optional when
/// its parameters can carry their own attributes (`$(#[$attr])*`), since a call of the
/// macro then gives each one a `#[default]` (`params_blocks_without_defaults` checks that),
/// and required when they cannot.
fn parameters(list: &str) -> Vec<Parameter> {
    let mut out = Vec::new();
    let mut rest = list.trim_start();
    while !rest.is_empty() {
        if let Some(group) = rest
            .strip_prefix('$')
            .filter(|group| group.starts_with('('))
        {
            let Some((inside, after)) = balanced(group, '(', ')') else {
                break;
            };
            out.push(Parameter {
                name: format!("$({})", inside.trim()),
                optional: inside.contains("#[$"),
            });
            rest = after
                .trim_start_matches(['*', '?', '+'])
                .trim_start()
                .trim_start_matches(',')
                .trim_start();
            continue;
        }
        let mut optional = false;
        while let Some(attribute) = rest.strip_prefix('#') {
            let Some((inside, after)) = balanced(attribute.trim_start(), '[', ']') else {
                break;
            };
            if inside.trim_start().starts_with("default") {
                optional = true;
            }
            rest = after.trim_start();
        }
        let name: String = rest
            .chars()
            .take_while(|c| c.is_alphanumeric() || *c == '_' || *c == '$')
            .collect();
        // The type runs to the next comma outside brackets. The `>` of an arrow does not
        // close an angle bracket.
        let bytes = rest.as_bytes();
        let mut depth = 0i32;
        let mut end = rest.len();
        for (index, byte) in bytes.iter().enumerate() {
            match byte {
                b'(' | b'[' | b'{' | b'<' => depth += 1,
                b'>' if index > 0 && bytes[index - 1] == b'-' => {}
                b')' | b']' | b'}' | b'>' => depth -= 1,
                b',' if depth == 0 => {
                    end = index;
                    break;
                }
                _ => {}
            }
        }
        if !name.is_empty() {
            out.push(Parameter { name, optional });
        }
        rest = rest[end..].trim_start_matches(',').trim_start();
    }
    out
}

/// The parameter list of the function whose signature starts at `column` on
/// `lines[index]`: from the `(` after its name and generics to the matching `)`.
fn parameter_list(lines: &[String], index: usize, column: usize) -> Option<(String, String)> {
    let mut text = String::new();
    for (offset, line) in lines[index..].iter().enumerate().take(80) {
        let part = if offset == 0 {
            &line[column..]
        } else {
            line.as_str()
        };
        text.push_str(part.trim());
        text.push(' ');
    }
    let at = text.find("fn ")?;
    let name: String = text[at + 3..]
        .trim_start()
        .chars()
        .take_while(|c| c.is_alphanumeric() || *c == '_' || *c == '$')
        .collect();
    let after_name = text[at + 3..].trim_start()[name.len()..].trim_start();
    let list = skip_generics(after_name);
    let (inside, _) = balanced(list, '(', ')')?;
    Some((name, inside.to_string()))
}

/// The widget functions in one file whose parameters are out of order, one line each.
///
/// The order is: the required parameters, then `modifier`, then the optional ones, with
/// `content` last when there is one. `content` may be required (a lazy list's is) and
/// still comes last.
fn parameter_order_violations(path: &Path, text: &str, no_modifier: &[&str]) -> Vec<String> {
    let lines = code_lines(text);
    let mut found = Vec::new();
    for (index, line) in lines.iter().enumerate() {
        for column in public_fn_starts(line) {
            let Some((name, list)) = parameter_list(&lines, index, column) else {
                continue;
            };
            if !name.starts_with(|c: char| c.is_ascii_uppercase() || c == '$') {
                continue;
            }
            let params = parameters(&list);
            let names: Vec<&str> = params.iter().map(|param| param.name.as_str()).collect();
            let at = format!(
                "{}:{}: `{name}` ({})",
                path.display(),
                index + 1,
                names.join(", ")
            );
            if names
                .iter()
                .position(|name| *name == "content")
                .is_some_and(|content| content + 1 != names.len())
            {
                found.push(format!("{at}: `content` is not the last parameter"));
            }
            let Some(modifier) = names.iter().position(|name| *name == "modifier") else {
                if !no_modifier.contains(&name.as_str()) {
                    found.push(format!("{at}: there is no `modifier` parameter"));
                }
                // Without a modifier the required parameters still come first.
                if let Some(first_optional) = params.iter().position(|param| param.optional) {
                    for param in &params[first_optional..] {
                        if !param.optional && param.name != "content" {
                            found.push(format!(
                                "{at}: required `{}` comes after an optional parameter",
                                param.name
                            ));
                        }
                    }
                }
                continue;
            };
            for param in &params[..modifier] {
                if param.optional {
                    found.push(format!(
                        "{at}: optional `{}` comes before `modifier`",
                        param.name
                    ));
                }
            }
            for param in &params[modifier + 1..] {
                if !param.optional && param.name != "content" {
                    found.push(format!(
                        "{at}: required `{}` comes after `modifier`",
                        param.name
                    ));
                }
            }
        }
    }
    found
}

/// The `params: { .. }` blocks of the widget macro calls in one file that have a
/// parameter without a `#[default]`, one line each. The macros put these parameters after
/// `modifier`, so they have to be optional. A block in a macro's own rules, which names
/// metavariables, is the pattern and not a call.
fn params_blocks_without_defaults(path: &Path, text: &str) -> Vec<String> {
    let lines = code_lines(text);
    let joined = lines.join("\n");
    let mut found = Vec::new();
    let mut from = 0;
    while let Some(found_at) = joined[from..].find("params:") {
        let at = from + found_at;
        from = at + "params:".len();
        let Some((inside, _)) = balanced(joined[from..].trim_start(), '{', '}') else {
            continue;
        };
        if inside.contains('$') {
            continue;
        }
        let line = joined[..at].matches('\n').count() + 1;
        for param in parameters(inside) {
            if !param.optional {
                found.push(format!(
                    "{}:{line}: `{}` in a macro's `params` has no `#[default]`, but it \
                     comes after `modifier`",
                    path.display(),
                    param.name
                ));
            }
        }
    }
    found
}

#[test]
fn fr39_function_form_parameters_are_required_then_modifier_then_optional_then_content() {
    let mut found = Vec::new();
    let mut functions = 0;
    for (path, text) in widget_function_sources() {
        functions += code_lines(&text)
            .iter()
            .map(|line| public_fn_starts(line).len())
            .sum::<usize>();
        found.extend(parameter_order_violations(&path, &text, NO_MODIFIER));
        found.extend(params_blocks_without_defaults(&path, &text));
    }
    assert!(
        functions > 20,
        "only {functions} widget functions were read"
    );
    assert!(
        found.is_empty(),
        "a widget function takes its required parameters, then `modifier`, then the \
         optional ones, and `content` last:\n{}",
        found.join("\n")
    );
}

#[test]
fn fr39_function_form_parameter_order_check_reports_what_it_should() {
    let path = Path::new("probe.rs");
    let good = r#"
pub fn Good<'a>(
    text: impl Into<String>,
    on_click: impl FnMut() + 'static,
    modifier: Modifier,
    #[default(None::<(f32, f32)>)] size: impl Into<Option<(f32, f32)>>,
    #[default(|| {})] content: impl FnOnce(),
) {}
pub fn Lazy(modifier: Modifier, content: impl FnOnce(&mut Scope)) {}
macro_rules! toggle {
    ($name:ident($state:ident), params: { $($(#[$attr:meta])* $param:ident: $ty:ty,)* }) => {
        pub fn $name($state: bool, modifier: Modifier, $($(#[$attr])* $param: $ty,)*
            #[default(None::<fn()>)] on_done: Option<impl FnMut() + 'static>,
        ) {}
    };
}
toggle! { Switch(checked), params: { #[default(None::<f32>)] size: impl Into<Option<f32>>, } }
"#;
    assert_eq!(
        parameter_order_violations(path, good, &[]),
        Vec::<String>::new()
    );
    assert_eq!(
        params_blocks_without_defaults(path, good),
        Vec::<String>::new()
    );

    let bad = r#"
pub fn Late(modifier: Modifier, text: impl Into<String>) {}
pub fn Early(#[default(1)] size: u32, modifier: Modifier) {}
pub fn Inside(modifier: Modifier, #[default(|| {})] content: impl FnOnce(), #[default(1)] x: u32) {}
pub fn Bare(text: String) {}
toggle! { Switch(checked), params: { size: f32, } }
"#;
    let found = parameter_order_violations(path, bad, &[]);
    let reported = |name: &str, what: &str| {
        found
            .iter()
            .any(|line| line.contains(&format!("`{name}`")) && line.contains(what))
    };
    assert!(reported("Late", "required `text`"), "{found:#?}");
    assert!(reported("Early", "optional `size`"), "{found:#?}");
    assert!(reported("Inside", "not the last"), "{found:#?}");
    assert!(reported("Bare", "no `modifier`"), "{found:#?}");
    assert!(
        parameter_order_violations(path, "pub fn Bare(text: String) {}", &["Bare"]).is_empty(),
        "a named exception is not reported"
    );
    let blocks = params_blocks_without_defaults(path, bad);
    assert_eq!(blocks.len(), 1, "{blocks:#?}");
    assert!(blocks[0].contains("`size`"), "{blocks:#?}");
}
