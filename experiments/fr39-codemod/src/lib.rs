//! Rewrites compose-rust widget builder chains into calls of the macros `#[Composable]` generates.
//!
//! ```text
//! Column().modifier(m).spacing(8.0).content(|| { Text("a").font_size(14.0); });
//! Column!(modifier = m, spacing = 8.0, { Text!("a", font_size = 14.0); });
//! ```
//!
//! The rewrite is textual: every argument, closure body and comment inside an argument is
//! copied from the source as it was, and only the chain around them changes. Run
//! `rustfmt` afterwards; the macro calls are laid out the way it lays out a function call.
//!
//! What it leaves alone and reports instead:
//! - a chain whose value is used (`let b = Column()..`, a builder returned or passed on,
//!   a match arm or a tail expression), because the macro form returns nothing;
//! - a method the widget's table entry does not list;
//! - the same method twice on one chain;
//! - a comment between the chain's methods, which a rewrite would lose;
//! - an optional handler whose closure has an untyped argument the table has no type for.
//!
//! Chains inside other macros' token streams (`vec![..]`, `format!(..)`) are not parsed by
//! syn and are not seen at all.

pub mod table;

use proc_macro2::LineColumn;
use syn::spanned::Spanned;
use syn::visit::{self, Visit};
use syn::{Expr, ExprCall, ExprClosure, ExprMethodCall, ExprPath, Pat, Stmt};

/// A chain the codemod could not rewrite, and why.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Flag {
    pub line: usize,
    pub column: usize,
    pub reason: String,
}

/// The result of rewriting one file.
#[derive(Debug)]
pub struct Outcome {
    pub source: String,
    pub rewritten: usize,
    /// `use` paths moved from `ui::` to `ui::next::`.
    pub imports: usize,
    pub flags: Vec<Flag>,
}

/// Options for one run.
#[derive(Debug, Clone)]
pub struct Options {
    /// The path call sites use for compose-rust, written in front of the handler argument
    /// types the codemod adds (`::compose_rust`, or `crate` inside the crate itself).
    pub crate_path: String,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            crate_path: "::compose_rust".to_owned(),
        }
    }
}

/// Rewrites every builder chain in `source` it can, and reports the rest.
pub fn rewrite(source: &str, options: &Options) -> Result<Outcome, syn::Error> {
    let file = syn::parse_file(source)?;
    let offsets = Offsets::new(source);
    let mut finder = Finder {
        offsets: &offsets,
        options,
        chains: Vec::new(),
        flags: Vec::new(),
        statement: None,
        imports: Vec::new(),
    };
    finder.visit_file(&file);
    let Finder {
        chains,
        flags,
        imports,
        ..
    } = finder;

    // Only chains that are not inside another chain are spliced into the file; the ones
    // inside are spliced into their parent's arguments as the parent is rendered.
    let top: Vec<&Chain> = chains
        .iter()
        .filter(|chain| {
            !chains
                .iter()
                .any(|outer| outer.range != chain.range && contains(outer.range, chain.range))
        })
        .collect();
    let mut edits: Vec<(Range, String)> = top
        .iter()
        .map(|chain| (chain.range, render(chain, &chains, source)))
        .collect();
    edits.extend(imports.iter().map(|at| ((*at, *at), "next::".to_owned())));
    edits.sort_by_key(|(range, _)| range.0);
    let mut out = String::with_capacity(source.len());
    let mut at = 0;
    for (range, text) in &edits {
        out.push_str(&source[at..range.0]);
        out.push_str(text);
        at = range.1;
    }
    out.push_str(&source[at..]);
    Ok(Outcome {
        source: out,
        rewritten: chains.len(),
        imports: imports.len(),
        flags,
    })
}

type Range = (usize, usize);

fn contains(outer: Range, inner: Range) -> bool {
    outer.0 <= inner.0 && inner.1 <= outer.1
}

/// One argument's source range.
#[derive(Debug, Clone)]
enum Piece {
    /// Copied from this range, with the chains inside it rewritten.
    Source(Range),
    /// `Some(..)` around another piece.
    Some(Box<Piece>),
    /// `AsRef::<str>::as_ref(&(..))` around another piece.
    AsStr(Box<Piece>),
    /// Literal text.
    Text(String),
    /// Several pieces joined with `, ` inside `(..)`.
    Tuple(Vec<Piece>),
    /// A closure whose argument types are written in: `|name: Type, ..|` then the
    /// closure's source from `body` on, with `prefix` (such as `move `) kept.
    TypedClosure {
        prefix: String,
        params: Vec<(String, String)>,
        body: Range,
    },
}

/// One argument of the macro call.
#[derive(Debug, Clone)]
enum Arg {
    Positional(Piece),
    Named(String, Piece),
    /// The trailing `{ .. }` block: the range of a closure body's block.
    Block(Range),
}

#[derive(Debug)]
struct Chain {
    range: Range,
    /// The macro's path, `Text!` or `ui::Text!`.
    path: String,
    args: Vec<Arg>,
}

fn render(chain: &Chain, chains: &[Chain], source: &str) -> String {
    let mut out = format!("{}!(", chain.path);
    for (index, arg) in chain.args.iter().enumerate() {
        if index > 0 {
            out.push_str(", ");
        }
        match arg {
            Arg::Positional(piece) => out.push_str(&piece_text(piece, chains, source)),
            Arg::Named(name, piece) => {
                out.push_str(name);
                out.push_str(" = ");
                out.push_str(&piece_text(piece, chains, source));
            }
            Arg::Block(range) => out.push_str(&spliced(*range, chains, source)),
        }
    }
    out.push(')');
    out
}

fn piece_text(piece: &Piece, chains: &[Chain], source: &str) -> String {
    match piece {
        Piece::Source(range) => spliced(*range, chains, source),
        Piece::Some(inner) => format!("Some({})", piece_text(inner, chains, source)),
        Piece::AsStr(inner) => format!(
            "AsRef::<str>::as_ref(&({}))",
            piece_text(inner, chains, source)
        ),
        Piece::Text(text) => text.clone(),
        Piece::Tuple(pieces) => {
            let inner: Vec<String> = pieces
                .iter()
                .map(|piece| piece_text(piece, chains, source))
                .collect();
            format!("({})", inner.join(", "))
        }
        Piece::TypedClosure {
            prefix,
            params,
            body,
        } => {
            let params: Vec<String> = params
                .iter()
                .map(|(name, ty)| format!("{name}: {ty}"))
                .collect();
            format!(
                "{prefix}|{}| {}",
                params.join(", "),
                spliced(*body, chains, source)
            )
        }
    }
}

/// The source in `range`, with every chain directly inside it rendered.
fn spliced(range: Range, chains: &[Chain], source: &str) -> String {
    let inside: Vec<&Chain> = chains
        .iter()
        .filter(|chain| contains(range, chain.range) && chain.range != range)
        .collect();
    let mut direct: Vec<&Chain> = inside
        .iter()
        .copied()
        .filter(|chain| {
            !inside
                .iter()
                .any(|outer| outer.range != chain.range && contains(outer.range, chain.range))
        })
        .collect();
    direct.sort_by_key(|chain| chain.range.0);
    let mut out = String::new();
    let mut at = range.0;
    for chain in direct {
        out.push_str(&source[at..chain.range.0]);
        out.push_str(&render(chain, chains, source));
        at = chain.range.1;
    }
    out.push_str(&source[at..range.1]);
    out
}

/// Line and column (from proc-macro2's span locations) to byte offsets.
struct Offsets<'a> {
    source: &'a str,
    line_starts: Vec<usize>,
}

impl<'a> Offsets<'a> {
    fn new(source: &'a str) -> Self {
        let mut line_starts = vec![0];
        for (index, byte) in source.bytes().enumerate() {
            if byte == b'\n' {
                line_starts.push(index + 1);
            }
        }
        Self {
            source,
            line_starts,
        }
    }

    fn offset(&self, at: LineColumn) -> usize {
        let start = self.line_starts[at.line - 1];
        let line = &self.source[start..];
        // proc-macro2 counts columns in characters.
        start
            + line
                .char_indices()
                .nth(at.column)
                .map(|(index, _)| index)
                .unwrap_or(line.len())
    }

    fn range(&self, spanned: &impl Spanned) -> Range {
        let span = spanned.span();
        (self.offset(span.start()), self.offset(span.end()))
    }
}

struct Finder<'a> {
    offsets: &'a Offsets<'a>,
    options: &'a Options,
    chains: Vec<Chain>,
    flags: Vec<Flag>,
    /// The expression statement being visited, if its expression is the chain itself.
    statement: Option<Range>,
    /// Where `next::` goes into a `use` path.
    imports: Vec<usize>,
}

/// A chain taken apart: the root call and the methods on it, first to last.
struct Parts<'e> {
    root: &'e ExprCall,
    path: &'e ExprPath,
    methods: Vec<&'e ExprMethodCall>,
}

/// The widget a call's path names: `Text`, `ui::Text`, or `Icon::asset` for `IconAsset`.
fn widget_name(path: &ExprPath) -> Option<String> {
    if path.qself.is_some() {
        return None;
    }
    let segments: Vec<_> = path.path.segments.iter().collect();
    let last = segments.last()?;
    if !last.arguments.is_none() {
        return None;
    }
    let name = last.ident.to_string();
    if name == "asset" && segments.len() >= 2 && segments[segments.len() - 2].ident == "Icon" {
        return Some("IconAsset".to_owned());
    }
    table::is_widget(&name).then_some(name)
}

fn parts(expr: &Expr) -> Option<Parts<'_>> {
    let mut methods = Vec::new();
    let mut at = expr;
    loop {
        match at {
            Expr::MethodCall(call) => {
                methods.push(call);
                at = &call.receiver;
            }
            Expr::Call(root) => {
                let Expr::Path(path) = &*root.func else {
                    return None;
                };
                widget_name(path)?;
                methods.reverse();
                return Some(Parts {
                    root,
                    path,
                    methods,
                });
            }
            _ => return None,
        }
    }
}

impl Finder<'_> {
    fn flag(&mut self, spanned: &impl Spanned, reason: impl Into<String>) {
        let start = spanned.span().start();
        self.flags.push(Flag {
            line: start.line,
            column: start.column + 1,
            reason: reason.into(),
        });
    }

    /// Visits what a chain's arguments contain, without visiting the chain itself again.
    fn visit_arguments(&mut self, parts: &Parts<'_>) {
        for arg in &parts.root.args {
            self.statement = None;
            self.visit_expr(arg);
        }
        for method in &parts.methods {
            for arg in &method.args {
                self.statement = None;
                self.visit_expr(arg);
            }
        }
    }

    fn chain(&mut self, expr: &Expr, parts: &Parts<'_>) {
        let widget = widget_name(parts.path).expect("parts checked the name");
        let widget = widget.as_str();
        let range = self.offsets.range(expr);
        let in_statement = self.statement == Some(range);
        self.visit_arguments(parts);
        if !in_statement {
            self.flag(
                expr,
                format!(
                    "{}: the builder's value is used here (bound, returned or passed on); \
                     rewrite it by hand",
                    widget
                ),
            );
            return;
        }
        match self.convert(expr, widget, parts) {
            Ok(chain) => self.chains.push(chain),
            Err(reason) => self.flag(expr, format!("{widget}: {reason}")),
        }
    }

    fn convert(&self, expr: &Expr, widget: &str, parts: &Parts<'_>) -> Result<Chain, String> {
        let range = self.offsets.range(expr);
        let source = self.offsets.source;
        if has_comment_between_methods(source, range, parts, self.offsets) {
            return Err("a comment sits between the chain's methods; move it first".into());
        }

        let mut args: Vec<Arg> = parts
            .root
            .args
            .iter()
            .map(|arg| Arg::Positional(Piece::Source(self.offsets.range(arg))))
            .collect();

        let main_handler = table::main_handler(widget);
        let data_method = table::data_method(widget);
        let mut seen: Vec<String> = Vec::new();
        let mut main: Option<Piece> = None;
        let mut data: Option<Piece> = None;
        let mut named: Vec<Arg> = Vec::new();
        let mut block: Option<Arg> = None;
        for method in &parts.methods {
            let name = method.method.to_string();
            if method.turbofish.is_some() {
                return Err(format!("`{name}` has explicit type arguments"));
            }
            let piece_of = |arg: &Expr| Piece::Source(self.offsets.range(arg));
            let one = || -> Result<&Expr, String> {
                match method.args.len() {
                    1 => Ok(&method.args[0]),
                    count => Err(format!("`{name}` takes one argument here, not {count}")),
                }
            };
            // `color_if(opt)` and `color(paint)` both become `color`, which takes either.
            let parameter = match name.strip_suffix("_if") {
                Some(base) => base.to_owned(),
                None => table::parameter(widget, &name),
            };
            if seen.contains(&parameter) {
                return Err(format!("`{parameter}` is set twice on one chain"));
            }
            seen.push(parameter.clone());
            if name == "content" {
                block = Some(match one()? {
                    Expr::Closure(closure)
                        if closure.inputs.is_empty()
                            && closure.capture.is_none()
                            && closure.asyncness.is_none()
                            && closure.output == syn::ReturnType::Default
                            && matches!(&*closure.body, Expr::Block(_)) =>
                    {
                        Arg::Block(self.offsets.range(&closure.body))
                    }
                    other => Arg::Named("content".into(), piece_of(other)),
                });
                continue;
            }
            if main_handler.is_some_and(|main| main.method == name) {
                main = Some(piece_of(one()?));
                continue;
            }
            if data_method.is_some_and(|(data, _)| data == name) {
                data = Some(piece_of(one()?));
                continue;
            }
            if table::is_optional(widget, &name) {
                named.push(Arg::Named(parameter, self.optional(&name, method)?));
                continue;
            }
            let piece = match method.args.len() {
                0 => Piece::Text("true".into()),
                1 if table::STR_PARAMETERS.contains(&parameter.as_str()) => {
                    self.as_str(&method.args[0])
                }
                1 => piece_of(&method.args[0]),
                _ => Piece::Tuple(method.args.iter().map(piece_of).collect()),
            };
            named.push(Arg::Named(parameter, piece));
        }

        if let Some((_, empty)) = data_method {
            args.push(Arg::Positional(
                data.unwrap_or_else(|| Piece::Text(empty.into())),
            ));
        }
        if let Some(handler) = main_handler {
            args.push(Arg::Positional(
                main.unwrap_or_else(|| Piece::Text(handler.empty.into())),
            ));
        }
        args.extend(named);
        args.extend(block);
        Ok(Chain {
            range,
            path: path_text(parts.path, widget),
            args,
        })
    }

    /// A `&str` parameter's argument: a string literal as it is, anything else through
    /// `AsRef::<str>::as_ref`, which takes `String`, `&String` and `&str`.
    fn as_str(&self, arg: &Expr) -> Piece {
        let range = self.offsets.range(arg);
        if let Expr::Lit(literal) = arg
            && matches!(literal.lit, syn::Lit::Str(_))
        {
            return Piece::Source(range);
        }
        Piece::AsStr(Box::new(Piece::Source(range)))
    }

    /// `Some(..)` around an optional handler, with its closure's argument types written in.
    fn optional(&self, name: &str, method: &ExprMethodCall) -> Result<Piece, String> {
        let args: Vec<&Expr> = method.args.iter().collect();
        let handler = *args
            .last()
            .ok_or_else(|| format!("`{name}` takes a handler"))?;
        let handler_piece = match handler {
            Expr::Closure(closure) => self.typed_closure(name, closure)?,
            other => Piece::Source(self.offsets.range(other)),
        };
        let inner = if args.len() == 1 {
            handler_piece
        } else {
            let mut pieces: Vec<Piece> = args[..args.len() - 1]
                .iter()
                .map(|arg| Piece::Source(self.offsets.range(*arg)))
                .collect();
            pieces.push(handler_piece);
            Piece::Tuple(pieces)
        };
        Ok(Piece::Some(Box::new(inner)))
    }

    fn typed_closure(&self, name: &str, closure: &ExprClosure) -> Result<Piece, String> {
        let untyped = closure
            .inputs
            .iter()
            .any(|input| !matches!(input, Pat::Type(_)));
        if !untyped {
            return Ok(Piece::Source(self.offsets.range(closure)));
        }
        let Some(types) = table::handler_argument_types(name) else {
            return Err(format!(
                "`{name}`'s closure has an untyped argument and the codemod has no type for it"
            ));
        };
        if types.len() != closure.inputs.len() {
            return Err(format!(
                "`{name}`'s closure takes {} arguments where the handler passes {}",
                closure.inputs.len(),
                types.len()
            ));
        }
        let source = self.offsets.source;
        let mut params = Vec::new();
        for (input, ty) in closure.inputs.iter().zip(types) {
            let (pattern, ty) = match input {
                Pat::Type(typed) => {
                    let pattern = &source[range_of(self.offsets, &typed.pat)];
                    let given = &source[range_of(self.offsets, &typed.ty)];
                    (pattern.to_owned(), given.to_owned())
                }
                other => (
                    source[range_of(self.offsets, other)].to_owned(),
                    ty.replace("{crate}", &self.options.crate_path),
                ),
            };
            params.push((pattern, ty));
        }
        let closure_range = self.offsets.range(closure);
        let or1 = closure.or1_token.span.start();
        let prefix = source[closure_range.0..self.offsets.offset(or1)].to_owned();
        Ok(Piece::TypedClosure {
            prefix,
            params,
            body: self.offsets.range(&closure.body),
        })
    }
}

fn range_of(offsets: &Offsets<'_>, spanned: &impl Spanned) -> std::ops::Range<usize> {
    let (start, end) = offsets.range(spanned);
    start..end
}

/// The macro's path: the call's path with the widget's name at the end, so `ui::Text`
/// stays `ui::Text!` and `Icon::asset` becomes `IconAsset!`.
fn path_text(path: &ExprPath, widget: &str) -> String {
    let mut out = String::new();
    if path.path.leading_colon.is_some() {
        out.push_str("::");
    }
    let mut names: Vec<String> = path
        .path
        .segments
        .iter()
        .map(|segment| segment.ident.to_string())
        .collect();
    names.pop();
    if widget == "IconAsset" {
        names.pop();
    }
    names.push(widget.to_owned());
    out.push_str(&names.join("::"));
    out
}

/// Whether a `//` or `/*` comment sits in the chain outside every argument.
fn has_comment_between_methods(
    source: &str,
    range: Range,
    parts: &Parts<'_>,
    offsets: &Offsets<'_>,
) -> bool {
    let mut arguments: Vec<Range> = parts
        .root
        .args
        .iter()
        .map(|arg| offsets.range(arg))
        .collect();
    for method in &parts.methods {
        arguments.extend(method.args.iter().map(|arg| offsets.range(arg)));
    }
    let text = &source[range.0..range.1];
    for marker in ["//", "/*"] {
        let mut from = 0;
        while let Some(found) = text[from..].find(marker) {
            let at = range.0 + from + found;
            if !arguments.iter().any(|arg| arg.0 <= at && at < arg.1) {
                return true;
            }
            from += found + marker.len();
        }
    }
    false
}

/// Whether the tree under `ui::` brings in widgets: a glob, a group, or a widget's name.
fn imports_widgets(tree: &syn::UseTree) -> bool {
    match tree {
        syn::UseTree::Glob(_) => true,
        // `ui::{self, ..}` would rename the `ui` it brings in, so it stays.
        syn::UseTree::Group(group) => !group
            .items
            .iter()
            .any(|item| matches!(item, syn::UseTree::Name(name) if name.ident == "self")),
        syn::UseTree::Name(name) => table::is_widget(&name.ident.to_string()),
        syn::UseTree::Rename(rename) => table::is_widget(&rename.ident.to_string()),
        syn::UseTree::Path(path) => {
            path.ident != "next" && table::is_widget(&path.ident.to_string())
        }
    }
}

impl Finder<'_> {
    /// Finds `compose_rust::ui::<widgets>` in a `use` tree (or `crate::ui::` inside the
    /// crate) and records where `next::` goes.
    fn use_tree(&mut self, tree: &syn::UseTree, parent: Option<String>) {
        match tree {
            syn::UseTree::Path(path) => {
                let ident = path.ident.to_string();
                let root = self.options.crate_path.trim_start_matches("::");
                if ident == "ui" && parent.as_deref() == Some(root) && imports_widgets(&path.tree) {
                    self.imports.push(self.offsets.range(&*path.tree).0);
                    return;
                }
                self.use_tree(&path.tree, Some(ident));
            }
            syn::UseTree::Group(group) => {
                for item in &group.items {
                    self.use_tree(item, parent.clone());
                }
            }
            _ => {}
        }
    }
}

impl<'ast> Visit<'ast> for Finder<'_> {
    fn visit_item_use(&mut self, item: &'ast syn::ItemUse) {
        self.use_tree(&item.tree, None);
    }

    fn visit_stmt(&mut self, stmt: &'ast Stmt) {
        if let Stmt::Expr(expr, Some(_)) = stmt {
            self.statement = Some(self.offsets.range(expr));
            self.visit_expr(expr);
            self.statement = None;
            return;
        }
        self.statement = None;
        visit::visit_stmt(self, stmt);
    }

    fn visit_expr(&mut self, expr: &'ast Expr) {
        if let Some(parts) = parts(expr) {
            self.chain(expr, &parts);
            return;
        }
        let statement = self.statement.take();
        // Only the statement's own expression is in statement position.
        if statement == Some(self.offsets.range(expr)) {
            self.statement = None;
        }
        visit::visit_expr(self, expr);
    }
}
