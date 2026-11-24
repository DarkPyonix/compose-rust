//! The call macro `#[Composable]` generates next to a composable function.
//!
//! `#[Composable] fn Name(a: A, b: B, content: impl Fn())` keeps its function form and also
//! gets a macro of the same name that takes the arguments positionally first and by name
//! after that, fills in `#[default(..)]` values, and expands to one call of the function.
//!
//! The generated `macro_rules!` has one rule. It forwards the function's name, one entry per
//! parameter (its name and, when it has one, its default expression) and the caller's
//! argument tokens, untouched, to the hidden proc macro [`check`]. The proc macro reads the
//! arguments, so an error is reported with `syn::Error::new_spanned` on the caller's own
//! tokens and points at the offending argument. A `compile_error!` written in a
//! `macro_rules!` body would point at the `#[Composable]` attribute instead.
//!
//! On success the proc macro emits `Name(arg0, arg1, ..)`. Nothing is bound to a local, so
//! the arguments stay inside the function call where closures take their parameter types
//! from the function's bounds, and they are evaluated in parameter order.
//!
//! The rule names the proc macro as `::compose_rust::__compose_call_check!`, which works
//! from any crate (and inside compose-rust through `extern crate self`). The function is
//! called by its own name, so the caller's scope decides what it means.

use proc_macro2::{Group, Punct, Spacing, TokenStream, TokenTree};
use quote::ToTokens;
use quote::{format_ident, quote};
use syn::parse::{Parse, ParseStream};
use syn::visit_mut::VisitMut;
use syn::{Attribute, Expr, Ident, ItemFn, Token, Type, Visibility};

/// One parameter, as the call macro sees it.
pub(crate) struct CallParam {
    pub ident: Ident,
    pub ty: Type,
    /// The default expression, normalised so it can be expanded at a call site. `None` is a
    /// required parameter.
    pub default: Option<TokenStream>,
}

/// The default of `modifier: Modifier` when the parameter has no `#[default]`.
pub(crate) fn implicit_default(ident: &Ident, ty: &Type) -> Option<TokenStream> {
    let is_modifier = matches!(ty, Type::Path(path)
        if path.qself.is_none()
            && path.path.segments.last().is_some_and(|segment| segment.ident == "Modifier"));
    (ident == "modifier" && is_modifier).then(|| quote!(::compose_rust::ui::Modifier::new()))
}

/// Rewrites what a default expression cannot keep when it moves to the call site: a path
/// that starts at `crate` has to keep meaning the crate that wrote the default.
pub(crate) fn normalise_default(tokens: TokenStream) -> TokenStream {
    tokens
        .into_iter()
        .flat_map(|tree| match tree {
            TokenTree::Ident(ident) if ident == "crate" => vec![
                TokenTree::Punct(Punct::new('$', Spacing::Alone)),
                TokenTree::Ident(ident),
            ],
            TokenTree::Group(group) => {
                let mut rebuilt = Group::new(group.delimiter(), normalise_default(group.stream()));
                rebuilt.set_span(group.span());
                vec![TokenTree::Group(rebuilt)]
            }
            other => vec![other],
        })
        .collect()
}

/// Token text with the spaces `TokenStream::to_string` puts around punctuation taken out.
fn tidy(mut text: String) -> String {
    for (from, to) in [
        ("{ }", "{}"),
        (" :: ", "::"),
        (":: ", "::"),
        (" < ", "<"),
        (" <", "<"),
        ("< ", "<"),
        (" >", ">"),
        (" ,", ","),
        (" (", "("),
        ("( ", "("),
        (" )", ")"),
        ("& ", "&"),
    ] {
        text = text.replace(from, to);
    }
    text
}

/// The text of a type for the macro's documentation.
fn type_text(ty: &Type) -> String {
    tidy(quote!(#ty).to_string())
}

fn default_text(default: &TokenStream) -> String {
    tidy(default.to_string().replace("$ crate", "crate"))
}

/// The documentation lines of the macro: the function's own text, then every parameter in
/// declaration order. rust-analyzer shows a macro's docs but not the signature of the
/// function it calls, so this is what an editor hover has to go on.
fn documentation(function: &ItemFn, params: &[CallParam]) -> Vec<String> {
    let mut lines: Vec<String> = Vec::new();
    for attribute in &function.attrs {
        if !attribute.path().is_ident("doc") {
            continue;
        }
        if let syn::Meta::NameValue(meta) = &attribute.meta {
            if let syn::Expr::Lit(syn::ExprLit {
                lit: syn::Lit::Str(text),
                ..
            }) = &meta.value
            {
                lines.push(text.value());
            }
        }
    }
    if !lines.is_empty() {
        lines.push(String::new());
    }
    let name = &function.sig.ident;
    lines.push(format!(
        " Call macro of `{name}`: positional arguments first, then `name = value` in any order."
    ));
    lines.push(String::new());
    lines.push(" Parameters, in declaration order:".to_string());
    lines.push(String::new());
    for param in params {
        let ident = &param.ident;
        let ty = type_text(&param.ty);
        let fate = match &param.default {
            Some(default) => format!("default `{}`", default_text(default)),
            None => "required".to_string(),
        };
        lines.push(format!(" * `{ident}: {ty}`, {fate}"));
    }
    if params.is_empty() {
        lines.push(" * none".to_string());
    }
    lines
}

/// Everything the call macro adds to the function: the exported macro and the re-export
/// under the function's name. The caller decides whether a function gets them at all: a
/// method has no name of its own to share with a macro.
pub(crate) fn generate(function: &ItemFn, params: &[CallParam]) -> TokenStream {
    let name = &function.sig.ident;
    let hidden = format_ident!("__compose_call_{}", name);
    let entries = params.iter().map(|param| {
        let ident = &param.ident;
        match &param.default {
            Some(default) => quote!((#ident [ #default ])),
            None => quote!((#ident)),
        }
    });
    let rule = quote! {
        ($($arguments:tt)*) => {
            ::compose_rust::__compose_call_check! { #name [ #(#entries)* ] $($arguments)* }
        };
    };

    let docs = documentation(function, params);
    let docs = docs.iter().map(|line| quote!(#[doc = #line]));
    let cfgs: Vec<&Attribute> = function
        .attrs
        .iter()
        .filter(|attribute| attribute.path().is_ident("cfg"))
        .collect();
    let visibility = match &function.vis {
        Visibility::Inherited => quote!(),
        other => quote!(#other),
    };
    // The macro is `#[doc(hidden)]` so its exported name stays off the crate's front page,
    // and rustdoc leaves out a plain re-export of a hidden item: the public name would have
    // no page and the documentation above would be seen by no one. `doc(inline)` makes
    // rustdoc document the macro, docs and all, under the public name instead.
    let inline = match &function.vis {
        Visibility::Public(_) => quote!(#[doc(inline)]),
        _ => quote!(),
    };
    quote! {
        #(#cfgs)*
        #(#docs)*
        #[macro_export]
        #[doc(hidden)]
        macro_rules! #hidden {
            #rule
        }
        #(#cfgs)*
        #inline
        #[allow(unused_imports)]
        #visibility use #hidden as #name;
    }
}

/// The input of [`check`]: `Name [ (param) (param [default]) .. ] arguments..`.
struct CheckInput {
    name: Ident,
    params: Vec<(Ident, Option<TokenStream>)>,
    arguments: TokenStream,
}

impl Parse for CheckInput {
    fn parse(input: ParseStream) -> syn::Result<Self> {
        let name: Ident = input.parse()?;
        let list;
        syn::bracketed!(list in input);
        let mut params = Vec::new();
        while !list.is_empty() {
            let entry;
            syn::parenthesized!(entry in list);
            let ident: Ident = entry.parse()?;
            let default = if entry.is_empty() {
                None
            } else {
                let tokens;
                syn::bracketed!(tokens in entry);
                Some(tokens.parse::<TokenStream>()?)
            };
            params.push((ident, default));
        }
        Ok(CheckInput {
            name,
            params,
            arguments: input.parse()?,
        })
    }
}

/// One argument as the caller wrote it.
enum Argument {
    Positional(Expr),
    Named(Ident, Expr),
    Block(proc_macro2::Group),
}

fn parse_arguments(input: ParseStream) -> syn::Result<Vec<Argument>> {
    let mut arguments = Vec::new();
    while !input.is_empty() {
        if input.peek(syn::token::Brace) {
            let group: proc_macro2::Group = input.parse()?;
            arguments.push(Argument::Block(group));
            // A block ends the list, with an optional comma after it.
            let _: Option<Token![,]> = input.parse()?;
            if !input.is_empty() {
                return Err(input.error("nothing can follow the trailing block"));
            }
            break;
        }
        if input.peek(Ident) && input.peek2(Token![=]) && !input.peek2(Token![==]) {
            let ident: Ident = input.parse()?;
            let _: Token![=] = input.parse()?;
            arguments.push(Argument::Named(ident, input.parse()?));
        } else {
            arguments.push(Argument::Positional(input.parse()?));
        }
        if input.is_empty() {
            break;
        }
        let _: Token![,] = input.parse()?;
    }
    Ok(arguments)
}

/// Runs `visitor` over a call macro's arguments, read with [`check`]'s grammar, in the
/// order they are written, and gives them back as tokens. The trailing block is visited as
/// the body of the closure [`check`] makes of it, so `Name!(a, { .. })` is visited exactly
/// as `Name(a, || { .. })` is. Arguments that do not parse give `None` before the visitor
/// has seen anything, and are left for the call macro to report.
pub(crate) fn visit_arguments_mut<V: VisitMut>(
    tokens: TokenStream,
    visitor: &mut V,
) -> Option<TokenStream> {
    enum Read {
        Expr(Option<Ident>, Expr),
        Block(syn::Block),
    }
    // Kept with its own span: a missing argument is reported on the last token written.
    let trailing_comma = match tokens.clone().into_iter().last() {
        Some(TokenTree::Punct(punct)) if punct.as_char() == ',' => Some(punct),
        _ => None,
    };
    let arguments = syn::parse::Parser::parse2(parse_arguments, tokens).ok()?;
    let mut read = Vec::with_capacity(arguments.len());
    for argument in arguments {
        read.push(match argument {
            Argument::Positional(expr) => Read::Expr(None, expr),
            Argument::Named(ident, expr) => Read::Expr(Some(ident), expr),
            Argument::Block(group) => {
                Read::Block(syn::parse2(TokenTree::Group(group).into()).ok()?)
            }
        });
    }
    let mut written = Vec::with_capacity(read.len());
    for argument in read {
        written.push(match argument {
            Read::Expr(None, mut expr) => {
                visitor.visit_expr_mut(&mut expr);
                expr.into_token_stream()
            }
            Read::Expr(Some(ident), mut expr) => {
                visitor.visit_expr_mut(&mut expr);
                quote!(#ident = #expr)
            }
            Read::Block(mut block) => {
                visitor.visit_block_mut(&mut block);
                block.into_token_stream()
            }
        });
    }
    Some(quote!(#(#written),* #trailing_comma))
}

/// Reads the caller's arguments against the parameter list and emits the call, or the
/// error on the offending argument's own tokens.
pub(crate) fn check(input: TokenStream) -> syn::Result<TokenStream> {
    let CheckInput {
        name,
        params,
        arguments,
    } = syn::parse2(input)?;
    let names_list = params
        .iter()
        .map(|(ident, _)| format!("`{ident}`"))
        .collect::<Vec<_>>()
        .join(", ");
    let count = params.len();
    let has_content = params.last().is_some_and(|(ident, _)| ident == "content");
    // Where a missing argument is reported: the arguments the caller did write, or the
    // call itself when there are none.
    let whole = arguments.clone();
    let arguments = syn::parse::Parser::parse2(parse_arguments, arguments)?;

    let mut slots: Vec<Option<TokenStream>> = vec![None; count];
    let mut positional = 0;
    let mut named_seen = false;
    for argument in arguments {
        match argument {
            Argument::Positional(expr) => {
                if named_seen {
                    return Err(syn::Error::new_spanned(
                        &expr,
                        format!(
                            "a positional argument of {name}! follows a named one; name it too, the parameters are: {names_list}"
                        ),
                    ));
                }
                if positional >= count {
                    return Err(syn::Error::new_spanned(
                        &expr,
                        format!(
                            "{name}! takes {count} argument(s) and was given more; the parameters are: {names_list}"
                        ),
                    ));
                }
                slots[positional] = Some(expr.into_token_stream());
                positional += 1;
            }
            Argument::Named(ident, expr) => {
                named_seen = true;
                let Some(index) = params.iter().position(|(param, _)| *param == ident) else {
                    return Err(syn::Error::new_spanned(
                        &ident,
                        format!(
                            "{name}! has no parameter `{ident}`; the parameters are: {names_list}"
                        ),
                    ));
                };
                if slots[index].is_some() {
                    return Err(syn::Error::new_spanned(
                        &ident,
                        format!("argument `{ident}` of {name}! is given twice"),
                    ));
                }
                slots[index] = Some(expr.into_token_stream());
            }
            Argument::Block(group) => {
                named_seen = true;
                if !has_content {
                    return Err(syn::Error::new_spanned(
                        &group,
                        format!(
                            "{name}! has no `content` parameter, so it takes no trailing block; the parameters are: {names_list}"
                        ),
                    ));
                }
                let last = count - 1;
                if slots[last].is_some() {
                    return Err(syn::Error::new_spanned(
                        &group,
                        format!("argument `content` of {name}! is given twice"),
                    ));
                }
                slots[last] = Some(quote!(|| #group));
            }
        }
    }

    let mut values = Vec::with_capacity(count);
    for ((ident, default), slot) in params.iter().zip(slots) {
        match (slot, default) {
            (Some(tokens), _) => values.push(tokens),
            (None, Some(default)) => values.push(default.clone()),
            (None, None) => {
                let span = if whole.is_empty() {
                    proc_macro2::Span::call_site()
                } else {
                    whole
                        .clone()
                        .into_iter()
                        .last()
                        .map_or_else(proc_macro2::Span::call_site, |tree| tree.span())
                };
                return Err(syn::Error::new(
                    span,
                    format!("missing required argument `{ident}` for {name}!"),
                ));
            }
        }
    }
    Ok(quote!(#name(#(#values),*)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use syn::{Item, parse_quote};

    fn expand(function: ItemFn, params: Vec<CallParam>) -> Vec<Item> {
        let tokens = generate(&function, &params);
        syn::parse2::<syn::File>(tokens)
            .expect("the generated items parse")
            .items
    }

    fn docs_of(item: &Item) -> Vec<String> {
        let Item::Macro(item) = item else {
            panic!("the first item is the macro")
        };
        item.attrs
            .iter()
            .filter_map(|attribute| match &attribute.meta {
                syn::Meta::NameValue(meta) if attribute.path().is_ident("doc") => {
                    match &meta.value {
                        syn::Expr::Lit(syn::ExprLit {
                            lit: syn::Lit::Str(text),
                            ..
                        }) => Some(text.value()),
                        _ => None,
                    }
                }
                _ => None,
            })
            .collect()
    }

    fn sample() -> (ItemFn, Vec<CallParam>) {
        let function: ItemFn = parse_quote! {
            /// Draws a field.
            pub fn Field(value: String, label: impl Fn(), single_line: bool) {}
        };
        let params = vec![
            CallParam {
                ident: parse_quote!(value),
                ty: parse_quote!(String),
                default: None,
            },
            CallParam {
                ident: parse_quote!(label),
                ty: parse_quote!(impl Fn()),
                default: Some(quote!(|| {})),
            },
            CallParam {
                ident: parse_quote!(single_line),
                ty: parse_quote!(bool),
                default: Some(quote!(false)),
            },
        ];
        (function, params)
    }

    #[test]
    fn fr39_call_macro_doc_lists_every_parameter_in_declaration_order() {
        let (function, params) = sample();
        let items = expand(function, params);
        let docs = docs_of(&items[0]).join("\n");
        let value = docs.find("`value: String`, required").expect("value");
        let label = docs
            .find("`label: impl Fn()`, default `|| {}`")
            .expect("label");
        let line = docs
            .find("`single_line: bool`, default `false`")
            .expect("single_line");
        assert!(value < label && label < line, "{docs}");
    }

    #[test]
    fn fr39_call_macro_doc_starts_with_the_function_docs() {
        let (function, params) = sample();
        let items = expand(function, params);
        assert_eq!(docs_of(&items[0])[0], " Draws a field.");
    }

    #[test]
    fn fr39_call_macro_doc_shows_the_implicit_modifier_default() {
        let function: ItemFn = parse_quote!(
            pub fn Panel(modifier: Modifier) {}
        );
        let ident: Ident = parse_quote!(modifier);
        let ty: Type = parse_quote!(Modifier);
        let params = vec![CallParam {
            default: implicit_default(&ident, &ty),
            ident,
            ty,
        }];
        let docs = docs_of(&expand(function, params)[0]).join("\n");
        assert!(
            docs.contains("default `::compose_rust::ui::Modifier::new()`"),
            "{docs}"
        );
    }

    #[test]
    fn fr39_call_macro_is_hidden_exported_and_reexported_at_the_function_visibility() {
        let (function, params) = sample();
        let items = expand(function, params);
        let Item::Macro(item) = &items[0] else {
            panic!()
        };
        assert_eq!(item.mac.path.segments.last().unwrap().ident, "macro_rules");
        assert_eq!(item.ident.as_ref().unwrap(), "__compose_call_Field");
        let text = quote!(#(#items)*).to_string();
        assert!(text.contains("macro_export"));
        assert!(text.contains("doc (hidden)"));
        let Item::Use(reexport) = &items[1] else {
            panic!()
        };
        assert!(matches!(reexport.vis, Visibility::Public(_)));
    }

    /// Whether an item has `#[doc(<word>)]`.
    fn has_doc_word(attrs: &[Attribute], word: &str) -> bool {
        attrs.iter().any(|attribute| {
            attribute.path().is_ident("doc")
                && matches!(&attribute.meta, syn::Meta::List(list) if list.tokens.to_string() == word)
        })
    }

    #[test]
    fn fr39_call_macro_docs_reach_the_public_name() {
        // The public name is a re-export of the hidden macro, and rustdoc shows a re-export
        // of a hidden item only when the re-export asks to be inlined. So the docs are on
        // the macro the name resolves to, and the re-export carries `doc(inline)`.
        let (function, params) = sample();
        let items = expand(function, params);
        let Item::Macro(hidden) = &items[0] else {
            panic!("the first item is the macro")
        };
        let hidden_name = hidden.ident.as_ref().expect("the macro has a name");
        assert!(has_doc_word(&hidden.attrs, "hidden"));
        let docs = docs_of(&items[0]).join("\n");
        assert!(docs.starts_with(" Draws a field."), "{docs}");
        assert!(docs.contains("`value: String`, required"), "{docs}");

        let Item::Use(reexport) = &items[1] else {
            panic!("the second item is the re-export")
        };
        let syn::UseTree::Rename(rename) = &reexport.tree else {
            panic!("the re-export renames the macro")
        };
        assert_eq!(&rename.ident, hidden_name, "it names the documented macro");
        assert_eq!(rename.rename, "Field", "under the function's name");
        assert!(
            has_doc_word(&reexport.attrs, "inline"),
            "a public re-export of a hidden macro is inlined, or rustdoc shows nothing"
        );
        assert!(!has_doc_word(&reexport.attrs, "hidden"));
    }

    #[test]
    fn fr39_call_macro_docs_private_function_reexport_is_not_inlined() {
        let function: ItemFn = parse_quote! {
            /// Draws a field.
            fn Field(value: String) {}
        };
        let params = vec![CallParam {
            ident: parse_quote!(value),
            ty: parse_quote!(String),
            default: None,
        }];
        let items = expand(function, params);
        let Item::Use(reexport) = &items[1] else {
            panic!("the second item is the re-export")
        };
        assert!(matches!(reexport.vis, Visibility::Inherited));
        assert!(!has_doc_word(&reexport.attrs, "inline"));
    }

    #[test]
    fn fr39_call_macro_default_paths_keep_meaning_the_defining_crate() {
        let normalised = normalise_default(quote!(crate::theme::DEFAULT)).to_string();
        assert_eq!(normalised, "$ crate :: theme :: DEFAULT");
    }

    fn run(arguments: TokenStream) -> Result<String, String> {
        let input = quote!(Field [ (value) (label [ || {} ]) (single_line [ false ]) ] #arguments);
        check(input)
            .map(|tokens| tokens.to_string())
            .map_err(|error| error.to_string())
    }

    #[test]
    fn fr39_call_macro_check_emits_the_call_in_parameter_order_with_defaults() {
        let text = run(quote!(single_line = true, value = a))
            .unwrap()
            .replace(' ', "");
        assert_eq!(text, "Field(a,||{},true)");
    }

    #[test]
    fn fr39_call_macro_check_names_the_offending_argument() {
        assert!(
            run(quote!(a, nope = 1))
                .unwrap_err()
                .contains("no parameter `nope`")
        );
        assert!(
            run(quote!(a, value = 1))
                .unwrap_err()
                .contains("given twice")
        );
        assert!(
            run(quote!(single_line = true, a))
                .unwrap_err()
                .contains("follows a named one")
        );
        assert!(
            run(quote!(a, b, c, d))
                .unwrap_err()
                .contains("was given more")
        );
        assert!(
            run(quote!(single_line = true))
                .unwrap_err()
                .contains("missing required argument `value`")
        );
    }
}
