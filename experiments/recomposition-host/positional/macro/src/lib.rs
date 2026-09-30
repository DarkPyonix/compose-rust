//! `#[composable]`: what the Compose compiler plugin does, attempted from outside the
//! compiler.
//!
//! The plugin wraps a restartable function in a group and wraps every conditional branch
//! and loop body in one of its own, keyed by source position. Without those groups a slot
//! table reads one call site's state at another's offset as soon as a branch stops being
//! taken. This rewrites the body to insert them, so the question of whether that is
//! bearable in Rust can be answered by reading a body that has been through it.

use proc_macro::TokenStream;
use quote::quote;
use syn::visit_mut::{self, VisitMut};
use syn::{Block, Expr, ItemFn, parse_macro_input, parse_quote};

/// Wraps a function body, and every branch and loop body inside it, in a group.
#[proc_macro_attribute]
pub fn composable(_attribute: TokenStream, item: TokenStream) -> TokenStream {
    let mut function = parse_macro_input!(item as ItemFn);
    let path = format!("{}", function.sig.ident);

    let mut inserter = Inserter {
        path: path.clone(),
        ordinal: 0,
    };
    inserter.visit_block_mut(&mut function.block);

    let body = &function.block;
    let key = key_expression(&path, u32::MAX);
    function.block = parse_quote!({
        let __composable_group = ::positional::group(#key);
        #body
    });

    quote!(#function).into()
}

struct Inserter {
    path: String,
    ordinal: u32,
}

impl Inserter {
    /// Wraps a block so that what it remembers belongs to this call site and no other.
    ///
    /// The guard is bound as a local rather than the body being wrapped in a closure. A
    /// local of a type with a `Drop` lives to the end of the block and is dropped after the
    /// block's tail expression has been evaluated, so the group closes in the right place
    /// on an ordinary exit and closes anyway on `continue`, `break`, `return`, `?` or an
    /// unwind. A closure would have made the first two compile errors and the third mean
    /// something else.
    fn wrap(&mut self, block: &mut Block) {
        let key = key_expression(&self.path, self.ordinal);
        self.ordinal += 1;
        let inner = block.clone();
        let guard = guard_name(self.ordinal);
        *block = parse_quote!({
            let #guard = ::positional::group(#key);
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
                    let key = key_expression(&self.path, self.ordinal);
                    self.ordinal += 1;
                    let guard = guard_name(self.ordinal);
                    let body = arm.body.clone();
                    arm.body = parse_quote!({
                        let #guard = ::positional::group(#key);
                        #body
                    });
                }
            }
            Expr::ForLoop(loop_expression) => self.wrap(&mut loop_expression.body),
            Expr::While(loop_expression) => self.wrap(&mut loop_expression.body),
            Expr::Loop(loop_expression) => self.wrap(&mut loop_expression.body),
            _ => {}
        }
    }

    /// Bodies of nested items are somebody else's composition, or not a composition at all.
    fn visit_item_mut(&mut self, _item: &mut syn::Item) {}
}

/// A distinct name per group, so nesting does not shadow an outer guard and drop it early.
fn guard_name(ordinal: u32) -> syn::Ident {
    syn::Ident::new(
        &format!("__composable_group_{ordinal}"),
        proc_macro2::Span::call_site(),
    )
}

fn key_expression(path: &str, ordinal: u32) -> proc_macro2::TokenStream {
    quote!(::positional::call_site(concat!(module_path!(), "::", #path), #ordinal))
}
