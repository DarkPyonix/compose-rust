//! The call macro `#[Composable]` generates next to a composable function.
//!
//! `#[Composable] fn Name(a: A, b: B, content: impl Fn())` keeps its function form and also
//! gets a macro of the same name that takes the arguments positionally first and by name
//! after that, fills in `#[default(..)]` values, and expands to one call of the function.
//!
//! The macro is a small state machine written as `macro_rules!` rules, generated per
//! function. Its state is the argument list so far, one bracketed slot per parameter, and
//! the arguments not read yet:
//!
//! * `@p<K>`: reading the positional prefix; `K` parameters are filled. A positional
//!   argument fills slot `K`. A `name = value` argument or a trailing `{ .. }` block moves
//!   to the named phase.
//! * `@n`: reading `name = value` arguments in any order. Each parameter has a rule for the
//!   empty slot (fill it) and one for a filled slot (duplicate).
//! * `@call`: every argument has been read. Empty slots of parameters with a default take
//!   it, an empty slot of a required parameter is an error naming it, and when none is
//!   left the rule emits `Name(slot0, slot1, ..)`.
//!
//! Nothing is bound to a local, so the arguments stay inside the function call where
//! closures take their parameter types from the function's bounds, and they are evaluated in
//! parameter order.
//!
//! The rules call the macro again by the name the caller used for it. A rule cannot name the
//! macro by an absolute path: inside the crate that defines it, the compiler refuses
//! `crate::__compose_call_Name` for a macro that another macro expanded. The function is
//! called by its own name for the same reason.

use proc_macro2::{Group, Punct, Spacing, TokenStream, TokenTree};
use quote::{format_ident, quote};
use syn::{Attribute, Ident, ItemFn, Type, Visibility};

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
    let count = params.len();
    let slot = |index: usize| format_ident!("s{}", index);
    let has_content = params.last().is_some_and(|param| param.ident == "content");

    // `[ $($s0:tt)* ] [ $($s1:tt)* ] ..`, with slot `which` matched as empty.
    let capture = |empty: Option<usize>| -> TokenStream {
        let slots = (0..count).map(|index| {
            let var = slot(index);
            if Some(index) == empty {
                quote!([])
            } else {
                quote!([ $($#var:tt)* ])
            }
        });
        quote!(#(#slots)*)
    };
    // The same slots, spliced back. `fill` replaces one slot's content.
    let forward = |fill: Option<(usize, TokenStream)>| -> TokenStream {
        let slots = (0..count).map(|index| match &fill {
            Some((target, tokens)) if *target == index => quote!([ #tokens ]),
            _ => {
                let var = slot(index);
                quote!([ $($#var)* ])
            }
        });
        quote!(#(#slots)*)
    };
    let names_list = params
        .iter()
        .map(|param| format!("`{}`", param.ident))
        .collect::<Vec<_>>()
        .join(", ");
    let again = quote!(#name!);

    let mut rules: Vec<TokenStream> = Vec::new();

    // Read the positional prefix. State `@p<K>` has filled the first K parameters.
    for filled in 0..=count {
        let state = format_ident!("p{}", filled);
        let next = format_ident!("p{}", filled + 1);
        let all = capture(None);
        let kept = forward(None);
        rules.push(quote! {
            (@#state #all ; ) => { #again(@call #kept) };
        });
        if has_content {
            rules.push(quote! {
                (@#state #all ; { $($body:tt)* } $(,)?) => {
                    #again(@n #kept ; content = || { $($body)* })
                };
            });
        } else {
            let message = format!(
                "{name}! has no `content` parameter, so it takes no trailing block; the parameters are: {names_list}"
            );
            rules.push(quote! {
                (@#state #all ; { $($body:tt)* } $(,)?) => { ::core::compile_error!(#message) };
            });
        }
        rules.push(quote! {
            (@#state #all ; $given:ident = $($rest:tt)+) => {
                #again(@n #kept ; $given = $($rest)+)
            };
        });
        if filled < count {
            let filled_in = forward(Some((filled, quote!($value))));
            rules.push(quote! {
                (@#state #all ; $value:expr $(, $($rest:tt)*)?) => {
                    #again(@#next #filled_in ; $($($rest)*)?)
                };
            });
        } else {
            let message = format!(
                "{name}! takes {count} argument(s) and was given more; the parameters are: {names_list}"
            );
            rules.push(quote! {
                (@#state #all ; $($rest:tt)+) => { ::core::compile_error!(#message) };
            });
        }
    }

    // Read `name = value` arguments.
    {
        let all = capture(None);
        let kept = forward(None);
        rules.push(quote! {
            (@n #all ; ) => { #again(@call #kept) };
        });
        if has_content {
            rules.push(quote! {
                (@n #all ; { $($body:tt)* } $(,)?) => {
                    #again(@n #kept ; content = || { $($body)* })
                };
            });
        } else {
            let message = format!(
                "{name}! has no `content` parameter, so it takes no trailing block; the parameters are: {names_list}"
            );
            rules.push(quote! {
                (@n #all ; { $($body:tt)* } $(,)?) => { ::core::compile_error!(#message) };
            });
        }
        for (index, param) in params.iter().enumerate() {
            let ident = &param.ident;
            let dup_message = format!("argument `{ident}` of {name}! is given twice");
            let dup_slots = (0..count).map(|other| {
                let other_var = slot(other);
                if other == index {
                    quote!([ $($#other_var:tt)+ ])
                } else {
                    quote!([ $($#other_var:tt)* ])
                }
            });
            rules.push(quote! {
                (@n #(#dup_slots)* ; #ident = $value:expr $(, $($rest:tt)*)?) => {
                    ::core::compile_error!(#dup_message)
                };
            });
            let empty = capture(Some(index));
            let filled_in = forward(Some((index, quote!($value))));
            rules.push(quote! {
                (@n #empty ; #ident = $value:expr $(, $($rest:tt)*)?) => {
                    #again(@n #filled_in ; $($($rest)*)?)
                };
            });
        }
        let unknown = format!("{name}! has no parameter `");
        let unknown_tail = format!("`; the parameters are: {names_list}");
        rules.push(quote! {
            (@n #all ; $given:ident = $($rest:tt)*) => {
                ::core::compile_error!(::core::concat!(#unknown, ::core::stringify!($given), #unknown_tail))
            };
        });
        let positional = format!(
            "a positional argument of {name}! follows a named one; name it too, the parameters are: {names_list}"
        );
        rules.push(quote! {
            (@n #all ; $($rest:tt)+) => { ::core::compile_error!(#positional) };
        });
    }

    // Every argument is read: defaults first, then the required ones that are missing.
    for (index, param) in params.iter().enumerate() {
        if let Some(default) = &param.default {
            let empty = capture(Some(index));
            let filled_in = forward(Some((index, default.clone())));
            rules.push(quote! {
                (@call #empty) => { #again(@call #filled_in) };
            });
        }
    }
    for (index, param) in params.iter().enumerate() {
        if param.default.is_none() {
            let ident = &param.ident;
            let message = format!("missing required argument `{ident}` for {name}!");
            let empty = capture(Some(index));
            rules.push(quote! {
                (@call #empty) => { ::core::compile_error!(#message) };
            });
        }
    }
    {
        let all = capture(None);
        let arguments = (0..count).map(|index| {
            let var = slot(index);
            quote!($($#var)*)
        });
        rules.push(quote! {
            (@call #all) => { #name(#(#arguments),*) };
        });
    }
    // The entry rule comes last, so that a caller's arguments never meet the state rules.
    let empty_slots = (0..count).map(|_| quote!([]));
    rules.push(quote! {
        ($($arguments:tt)*) => { #again(@p0 #(#empty_slots)* ; $($arguments)*) };
    });

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
    quote! {
        #(#cfgs)*
        #(#docs)*
        #[macro_export]
        #[doc(hidden)]
        macro_rules! #hidden {
            #(#rules)*
        }
        #(#cfgs)*
        #[allow(unused_imports)]
        #visibility use #hidden as #name;
    }
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
        let label = docs.find("`label: impl Fn()`, default `|| {}`").expect("label");
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
        assert!(docs.contains("default `::compose_rust::ui::Modifier::new()`"), "{docs}");
    }

    #[test]
    fn fr39_call_macro_is_hidden_exported_and_reexported_at_the_function_visibility() {
        let (function, params) = sample();
        let items = expand(function, params);
        let Item::Macro(item) = &items[0] else { panic!() };
        assert_eq!(item.mac.path.segments.last().unwrap().ident, "macro_rules");
        assert_eq!(item.ident.as_ref().unwrap(), "__compose_call_Field");
        let text = quote!(#(#items)*).to_string();
        assert!(text.contains("macro_export"));
        assert!(text.contains("doc (hidden)"));
        let Item::Use(reexport) = &items[1] else { panic!() };
        assert!(matches!(reexport.vis, Visibility::Public(_)));
    }

    #[test]
    fn fr39_call_macro_default_paths_keep_meaning_the_defining_crate() {
        let normalised = normalise_default(quote!(crate::theme::DEFAULT)).to_string();
        assert_eq!(normalised, "$ crate :: theme :: DEFAULT");
    }
}
