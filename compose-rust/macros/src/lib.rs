//! `#[composable]`: what the Compose compiler plugin does to a function, done from outside
//! the compiler.
//!
//! The plugin does three things to a `@Composable` function, and this does the same three
//! to a Rust one:
//!
//! 1. **It gives the call a group**, keyed by where the function is declared. A slot table
//!    stores what a call site remembered at a position, and the group is what turns a
//!    position into an identity: two calls of the same function in one parent are told
//!    apart by order, and everything the function remembers belongs to its group alone.
//! 2. **It gives every branch a group of its own**: each arm of an `if`, an `else` and a
//!    `match`. Without one, the state a branch remembered is handed to whichever call
//!    occupies its position after the branch stops being taken. Both values are often the
//!    same type, so nothing complains, and the screen silently shows one widget's state in
//!    another.
//!
//!    Loop bodies get no group, as in Compose. Each pass of a loop calls the same call
//!    sites again, and the runtime tells the passes apart by order, which is what a list
//!    that only grows and shrinks at the end needs. A list whose items move needs to say
//!    which item is which, and that is `key(id, || ..)`: a group per iteration here would
//!    sit between the loop and the key and pin every item to its position.
//! 3. **It makes the function skippable and restartable.** The parameters are compared
//!    with the ones the group was last run with, and when they are all equal and nothing
//!    the function read has changed, its body is not run at all. When a state it read
//!    changes, the runtime runs this function again on its own, with the parameters it
//!    last had, without running whatever called it.
//!
//! A group closes when a guard bound at the top of its block is dropped, not when a
//! closure wrapping the block returns. A local with a destructor is dropped after the
//! block's tail expression has been evaluated, so the group closes in the right place on
//! an ordinary exit, and closes anyway on `continue`, `break`, `return`, `?` and an
//! unwind. Wrapping the body in a closure would make the first two compile errors and give
//! `return` a different meaning.
//!
//! What can be compared and stored is decided from the parameter's type as written. A
//! parameter whose type borrows (other than `&'static`), names a generic parameter, or is
//! `impl Trait` cannot be kept past the call, so a function with one is never skipped and
//! is re-run by its caller rather than on its own. A parameter that can be kept but is not
//! `Clone + PartialEq` is found out at compile time by method resolution and treated the
//! same way. Nothing here needs the function's author to say which is which.

use proc_macro::TokenStream;
use proc_macro2::Span;
use quote::{format_ident, quote};
use syn::visit::{self, Visit};
use syn::visit_mut::{self, VisitMut};
use syn::{
    Block, Expr, FnArg, GenericParam, Ident, ItemFn, Pat, ReturnType, Type, parse_macro_input,
    parse_quote,
};

/// Turns a function into a composable.
///
/// ```ignore
/// #[composable]
/// fn Greeting(name: String) {
///     let clicks = remember(|| mutable_state_of(0));
///     Text(format!("Hello, {name}: {}", clicks.get()));
///     Button("Again").on_click(move || clicks.update(|count| *count += 1));
/// }
/// ```
///
/// `#[composable(nonrestartable)]` keeps the groups and the skipping but never re-runs the
/// function on its own: a state it reads re-runs its caller instead. That is what an
/// associated function needs, because the runtime re-runs a function by calling it by its
/// bare name.
#[proc_macro_attribute]
pub fn composable(attribute: TokenStream, item: TokenStream) -> TokenStream {
    let options = match parse_options(attribute.into()) {
        Ok(options) => options,
        Err(error) => return error.to_compile_error().into(),
    };
    let mut function = parse_macro_input!(item as ItemFn);
    match expand(&mut function, &options) {
        Ok(()) => quote!(#function).into(),
        Err(error) => error.to_compile_error().into(),
    }
}

struct Options {
    restartable: bool,
}

fn parse_options(attribute: proc_macro2::TokenStream) -> syn::Result<Options> {
    let mut options = Options { restartable: true };
    if attribute.is_empty() {
        return Ok(options);
    }
    let ident: Ident = syn::parse2(attribute)?;
    if ident == "nonrestartable" {
        options.restartable = false;
        Ok(options)
    } else {
        Err(syn::Error::new(
            ident.span(),
            "#[composable] takes no argument except `nonrestartable`",
        ))
    }
}

/// One parameter the runtime may compare and keep.
struct Param {
    ident: Ident,
    storable: bool,
}

fn expand(function: &mut ItemFn, options: &Options) -> syn::Result<()> {
    if let Some(asyncness) = function.sig.asyncness {
        return Err(syn::Error::new(
            asyncness.span,
            "a composable cannot be async: it runs to completion inside one composition. \
             Start asynchronous work with LaunchedEffect or remember_coroutine_scope",
        ));
    }
    let name = function.sig.ident.clone();
    let path = name.to_string();

    // Branch groups first, so the function's own group wraps them.
    let mut inserter = Inserter {
        path: path.clone(),
        ordinal: 0,
    };
    inserter.visit_block_mut(&mut function.block);

    let generic_types: Vec<Ident> = function
        .sig
        .generics
        .params
        .iter()
        .filter_map(|param| match param {
            GenericParam::Type(param) => Some(param.ident.clone()),
            _ => None,
        })
        .collect();
    let has_generics = !function.sig.generics.params.is_empty();

    let mut params = Vec::new();
    let mut params_ok = true;
    for input in &function.sig.inputs {
        match input {
            FnArg::Receiver(_) => params_ok = false,
            FnArg::Typed(typed) => match &*typed.pat {
                Pat::Ident(pattern) if pattern.by_ref.is_none() && pattern.subpat.is_none() => {
                    params.push(Param {
                        ident: pattern.ident.clone(),
                        storable: storable(&typed.ty, &generic_types),
                    });
                }
                _ => params_ok = false,
            },
        }
    }

    let unit_return = match &function.sig.output {
        ReturnType::Default => true,
        ReturnType::Type(_, ty) => matches!(&**ty, Type::Tuple(tuple) if tuple.elems.is_empty()),
    };
    // A parameter that cannot be kept cannot be compared next time, so a function with
    // one runs whenever its caller does, as an unstable parameter makes a Compose function
    // run.
    let skippable = unit_return && params_ok && params.iter().all(|param| param.storable);
    let restartable = skippable && options.restartable && !has_generics;

    let private = quote!(::compose_rust::runtime::__private);
    let key = key_expression(&path, u32::MAX);
    let body = &function.block;

    let skip = if skippable {
        let comparisons = params.iter().enumerate().map(|(index, param)| {
            let ident = &param.ident;
            quote! {
                __compose_changed |= (&#private::Param(&#ident))
                    .__compose_changed(&__compose_scope, #index);
            }
        });
        let restart = if restartable && params.is_empty() {
            quote! {
                if __compose_scope.wants_restart() {
                    __compose_scope.set_restart(move || {
                        #name();
                    });
                }
            }
        } else if restartable {
            let captured: Vec<Ident> = (0..params.len())
                .map(|index| format_ident!("__compose_param_{}", index))
                .collect();
            let idents = params.iter().map(|param| &param.ident);
            let clones = captured.iter().map(|captured| {
                quote! {
                    (&#private::Param(&#captured))
                        .__compose_clone()
                        .expect("a parameter that was cloned once clones again")
                }
            });
            quote! {
                if __compose_scope.wants_restart() {
                    if let (#(::core::option::Option::Some(#captured),)*) =
                        (#((&#private::Param(&#idents)).__compose_clone(),)*)
                    {
                        __compose_scope.set_restart(move || {
                            #name(#(#clones),*);
                        });
                    }
                }
            }
        } else {
            quote! {}
        };
        quote! {
            {
                #[allow(unused_mut)]
                let mut __compose_changed = __compose_scope.must_run();
                #(#comparisons)*
                if !__compose_changed {
                    __compose_scope.skip();
                    return;
                }
                #restart
            }
        }
    } else {
        quote! {}
    };

    function.block = parse_quote!({
        #[allow(unused_imports)]
        use #private::{StableParam as _, UnstableParam as _};
        let __compose_scope = #private::start_restart_group(#key);
        #skip
        #body
    });
    function.attrs.push(parse_quote!(#[allow(non_snake_case)]));
    Ok(())
}

/// Whether a parameter of this type, as written, can be kept after the call returns.
///
/// Only the syntax is available here, so the answer is conservative: anything that could
/// borrow, anything generic and anything opaque is treated as not storable, and the
/// function is then run every time its caller runs rather than wrongly skipped.
fn storable(ty: &Type, generics: &[Ident]) -> bool {
    struct Check<'a> {
        generics: &'a [Ident],
        ok: bool,
    }
    impl<'ast> Visit<'ast> for Check<'_> {
        fn visit_type_reference(&mut self, reference: &'ast syn::TypeReference) {
            match &reference.lifetime {
                Some(lifetime) if lifetime.ident == "static" => {}
                _ => self.ok = false,
            }
            visit::visit_type_reference(self, reference);
        }
        fn visit_lifetime(&mut self, lifetime: &'ast syn::Lifetime) {
            if lifetime.ident != "static" {
                self.ok = false;
            }
        }
        fn visit_type_impl_trait(&mut self, _: &'ast syn::TypeImplTrait) {
            self.ok = false;
        }
        fn visit_type_infer(&mut self, _: &'ast syn::TypeInfer) {
            self.ok = false;
        }
        fn visit_type_path(&mut self, path: &'ast syn::TypePath) {
            if path.qself.is_some() {
                self.ok = false;
            }
            if let Some(first) = path.path.segments.first() {
                if first.ident == "Self" || self.generics.iter().any(|g| first.ident == *g) {
                    self.ok = false;
                }
            }
            visit::visit_type_path(self, path);
        }
        fn visit_type_ptr(&mut self, _: &'ast syn::TypePtr) {
            self.ok = false;
        }
    }
    let mut check = Check { generics, ok: true };
    check.visit_type(ty);
    check.ok
}

struct Inserter {
    path: String,
    ordinal: u32,
}

impl Inserter {
    fn next(&mut self) -> (proc_macro2::TokenStream, Ident) {
        let key = key_expression(&self.path, self.ordinal);
        self.ordinal += 1;
        (key, guard_name(self.ordinal))
    }

    /// Wraps a block so that what it remembers belongs to this branch and no other.
    fn wrap(&mut self, block: &mut Block) {
        let (key, guard) = self.next();
        let inner = block.clone();
        *block = parse_quote!({
            let #guard = ::compose_rust::runtime::__private::group(#key);
            #inner
        });
    }
}

impl VisitMut for Inserter {
    fn visit_expr_mut(&mut self, expr: &mut Expr) {
        // Descend first, so an inner branch is numbered and wrapped before the outer one
        // closes over it.
        visit_mut::visit_expr_mut(self, expr);

        match expr {
            Expr::If(conditional) => {
                self.wrap(&mut conditional.then_branch);
                if let Some((_, otherwise)) = conditional.else_branch.as_mut() {
                    // An `else if` is another `Expr::If`, already wrapped by the descent
                    // above. Only a plain `else` block needs one here.
                    if let Expr::Block(block) = otherwise.as_mut() {
                        self.wrap(&mut block.block);
                    }
                }
            }
            Expr::Match(matched) => {
                for arm in &mut matched.arms {
                    let (key, guard) = self.next();
                    let body = arm.body.clone();
                    arm.body = parse_quote!({
                        let #guard = ::compose_rust::runtime::__private::group(#key);
                        #body
                    });
                }
            }
            _ => {}
        }
    }

    /// Bodies of nested items are somebody else's composition, or not a composition at all.
    fn visit_item_mut(&mut self, _item: &mut syn::Item) {}
}

/// A distinct name per group, so nesting does not shadow an outer guard and drop it early.
fn guard_name(ordinal: u32) -> Ident {
    Ident::new(&format!("__compose_group_{ordinal}"), Span::call_site())
}

/// A call site's key, computed at compile time from the function's path and the macro's
/// own counter within its body.
fn key_expression(path: &str, ordinal: u32) -> proc_macro2::TokenStream {
    quote!({
        const __COMPOSE_KEY: u64 = ::compose_rust::runtime::__private::call_site(
            concat!(module_path!(), "::", #path),
            #ordinal,
        );
        __COMPOSE_KEY
    })
}
