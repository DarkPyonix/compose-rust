//! `#[Preview]` attribute macro for declaring screen and component previews.
//!
//! Keeps the annotated function untouched so normal callers and tests can invoke it directly,
//! and emits a `PreviewSpec` descriptor gated behind `#[cfg(compose_rust_preview)]` so normal
//! and release builds carry zero preview registration overhead.

use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::parse::{Parse, ParseStream};
use syn::punctuated::Punctuated;
use syn::{Expr, ExprArray, Ident, ItemFn, Lit, LitFloat, LitInt, LitStr, Token};

#[derive(Default)]
struct PreviewArgs {
    name: Option<LitStr>,
    sizes: Vec<TokenStream>,
    schemes: Vec<TokenStream>,
    systems: Vec<TokenStream>,
    scales: Vec<TokenStream>,
    fixture: Option<LitStr>,
}

impl Parse for PreviewArgs {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let mut args = Self::default();
        while !input.is_empty() {
            let key: Ident = input.parse()?;
            input.parse::<Token![=]>()?;
            match key.to_string().as_str() {
                "name" => {
                    args.name = Some(input.parse::<LitStr>()?);
                }
                "fixture" => {
                    args.fixture = Some(input.parse::<LitStr>()?);
                }
                "sizes" => {
                    let arr: ExprArray = input.parse()?;
                    args.sizes = parse_sizes(&arr.elems)?;
                }
                "schemes" => {
                    let arr: ExprArray = input.parse()?;
                    args.schemes = parse_schemes(&arr.elems)?;
                }
                "systems" => {
                    let arr: ExprArray = input.parse()?;
                    args.systems = parse_systems(&arr.elems)?;
                }
                "scales" => {
                    let arr: ExprArray = input.parse()?;
                    args.scales = parse_scales(&arr.elems)?;
                }
                other => {
                    return Err(syn::Error::new(
                        key.span(),
                        format!(
                            "unknown #[Preview] argument `{other}`; expected one of: \
                             name, sizes, schemes, systems, scales, fixture"
                        ),
                    ));
                }
            }
            if input.peek(Token![,]) {
                input.parse::<Token![,]>()?;
            }
        }
        Ok(args)
    }
}

fn parse_sizes(elems: &Punctuated<Expr, Token![,]>) -> syn::Result<Vec<TokenStream>> {
    let mut out = Vec::with_capacity(elems.len());
    for expr in elems {
        match expr {
            Expr::Path(p) => {
                let ident = p.path.get_ident().ok_or_else(|| {
                    syn::Error::new_spanned(
                        p,
                        "expected Desktop, Laptop, Tablet, Phone, or a \"WxH\" string",
                    )
                })?;
                let variant = match ident.to_string().as_str() {
                    "Desktop" => quote!(::compose_rust::preview::PreviewSize::Desktop),
                    "Laptop" => quote!(::compose_rust::preview::PreviewSize::Laptop),
                    "Tablet" => quote!(::compose_rust::preview::PreviewSize::Tablet),
                    "Phone" => quote!(::compose_rust::preview::PreviewSize::Phone),
                    other => {
                        return Err(syn::Error::new(
                            ident.span(),
                            format!(
                                "unknown preview size `{other}`; expected Desktop, Laptop, \
                                 Tablet, Phone, or \"WxH\""
                            ),
                        ));
                    }
                };
                out.push(variant);
            }
            Expr::Lit(lit) => {
                let Lit::Str(s) = &lit.lit else {
                    return Err(syn::Error::new_spanned(
                        lit,
                        "expected Desktop, Laptop, Tablet, Phone, or a \"WxH\" string",
                    ));
                };
                let val = s.value();
                let lower = val.trim().to_ascii_lowercase();
                let (w_str, h_str) = lower.split_once('x').ok_or_else(|| {
                    syn::Error::new_spanned(s, "custom preview size must have the form \"WxH\"")
                })?;
                let w: u32 = w_str
                    .parse()
                    .map_err(|_| syn::Error::new_spanned(s, "invalid width in \"WxH\""))?;
                let h: u32 = h_str
                    .parse()
                    .map_err(|_| syn::Error::new_spanned(s, "invalid height in \"WxH\""))?;
                if w == 0 || h == 0 {
                    return Err(syn::Error::new_spanned(
                        s,
                        "preview width and height must be non-zero",
                    ));
                }
                out.push(quote!(::compose_rust::preview::PreviewSize::Custom(#w, #h)));
            }
            other => {
                return Err(syn::Error::new_spanned(
                    other,
                    "expected Desktop, Laptop, Tablet, Phone, or a \"WxH\" string",
                ));
            }
        }
    }
    Ok(out)
}

fn parse_schemes(elems: &Punctuated<Expr, Token![,]>) -> syn::Result<Vec<TokenStream>> {
    let mut out = Vec::with_capacity(elems.len());
    for expr in elems {
        let Expr::Path(p) = expr else {
            return Err(syn::Error::new_spanned(expr, "expected Light or Dark"));
        };
        let ident = p
            .path
            .get_ident()
            .ok_or_else(|| syn::Error::new_spanned(p, "expected Light or Dark"))?;
        match ident.to_string().as_str() {
            "Light" => out.push(quote!(::compose_rust::ColorScheme::Light)),
            "Dark" => out.push(quote!(::compose_rust::ColorScheme::Dark)),
            other => {
                return Err(syn::Error::new(
                    ident.span(),
                    format!("unknown preview scheme `{other}`; expected Light or Dark"),
                ));
            }
        }
    }
    Ok(out)
}

fn parse_systems(elems: &Punctuated<Expr, Token![,]>) -> syn::Result<Vec<TokenStream>> {
    let mut out = Vec::with_capacity(elems.len());
    for expr in elems {
        let Expr::Path(p) = expr else {
            return Err(syn::Error::new_spanned(
                expr,
                "expected Material3, Cupertino, Fluent, Gnome, Breeze, Deepin, or LiquidGlass",
            ));
        };
        let ident = p.path.get_ident().ok_or_else(|| {
            syn::Error::new_spanned(
                p,
                "expected Material3, Cupertino, Fluent, Gnome, Breeze, Deepin, or LiquidGlass",
            )
        })?;
        match ident.to_string().as_str() {
            "Material3" => out.push(quote!(::compose_rust::DesignSystem::Material3)),
            "Cupertino" => out.push(quote!(::compose_rust::DesignSystem::Cupertino)),
            "Fluent" => out.push(quote!(::compose_rust::DesignSystem::Fluent)),
            "Gnome" => out.push(quote!(::compose_rust::DesignSystem::Gnome)),
            "Breeze" => out.push(quote!(::compose_rust::DesignSystem::Breeze)),
            "Deepin" => out.push(quote!(::compose_rust::DesignSystem::Deepin)),
            "LiquidGlass" => out.push(quote!(::compose_rust::DesignSystem::LiquidGlass)),
            other => {
                return Err(syn::Error::new(
                    ident.span(),
                    format!(
                        "unknown design system `{other}`; expected Material3, Cupertino, \
                         Fluent, Gnome, Breeze, Deepin, or LiquidGlass"
                    ),
                ));
            }
        }
    }
    Ok(out)
}

fn parse_scales(elems: &Punctuated<Expr, Token![,]>) -> syn::Result<Vec<TokenStream>> {
    let mut out = Vec::with_capacity(elems.len());
    for expr in elems {
        let Expr::Lit(lit) = expr else {
            return Err(syn::Error::new_spanned(
                expr,
                "expected a positive scale literal like 1.0 or 2.0",
            ));
        };
        let val: f32 = match &lit.lit {
            Lit::Float(f) => parse_float(f)?,
            Lit::Int(i) => parse_int_as_f32(i)?,
            _ => {
                return Err(syn::Error::new_spanned(
                    lit,
                    "expected a positive scale literal like 1.0 or 2.0",
                ));
            }
        };
        if !val.is_finite() || val <= 0.0 {
            return Err(syn::Error::new_spanned(
                lit,
                "preview scale must be positive",
            ));
        }
        out.push(quote!(#val));
    }
    Ok(out)
}

fn parse_float(f: &LitFloat) -> syn::Result<f32> {
    f.base10_parse::<f32>()
}

fn parse_int_as_f32(i: &LitInt) -> syn::Result<f32> {
    let v = i.base10_parse::<u32>()?;
    Ok(v as f32)
}

pub fn expand(attribute: TokenStream, item: TokenStream) -> syn::Result<TokenStream> {
    let args: PreviewArgs = syn::parse2(attribute)?;
    let function: ItemFn = syn::parse2(item)?;

    if function.sig.asyncness.is_some() {
        return Err(syn::Error::new_spanned(
            function.sig.asyncness,
            "a #[Preview] function cannot be async",
        ));
    }
    if function.sig.inputs.len() != 1 {
        return Err(syn::Error::new_spanned(
            &function.sig.inputs,
            "a #[Preview] function must take exactly one parameter: `&PreviewContext`",
        ));
    }

    let fn_ident = &function.sig.ident;
    let fn_name_str = fn_ident.to_string();
    let display_name = match args.name {
        Some(n) => quote!(#n),
        None => quote!(#fn_name_str),
    };
    let sizes = if args.sizes.is_empty() {
        vec![quote!(::compose_rust::preview::PreviewSize::Desktop)]
    } else {
        args.sizes
    };
    let schemes = if args.schemes.is_empty() {
        vec![quote!(::compose_rust::ColorScheme::Light)]
    } else {
        args.schemes
    };
    let systems = args.systems;
    let scales = if args.scales.is_empty() {
        vec![quote!(1.0_f32)]
    } else {
        args.scales
    };
    let fixture = match args.fixture {
        Some(f) => quote!(::core::option::Option::Some(#f)),
        None => quote!(::core::option::Option::None),
    };

    let spec_ident = format_ident!("__COMPOSE_PREVIEW_{}", fn_name_str.to_uppercase());

    Ok(quote! {
        #function

        #[doc(hidden)]
        #[allow(unexpected_cfgs, dead_code, non_upper_case_globals)]
        const _: () = {
            #[cfg(compose_rust_preview)]
            const #spec_ident: ::compose_rust::preview::PreviewSpec =
                ::compose_rust::preview::PreviewSpec {
                    id: ::core::concat!(::core::module_path!(), "::", #fn_name_str),
                    name: #display_name,
                    sizes: &[#(#sizes),*],
                    schemes: &[#(#schemes),*],
                    systems: &[#(#systems),*],
                    scales: &[#(#scales),*],
                    fixture: #fixture,
                    render: #fn_ident,
                };
        };
    })
}
