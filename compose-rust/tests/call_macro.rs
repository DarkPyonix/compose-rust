//! The call macro `#[Composable]` generates next to every composable (FR-39.1.1 to
//! FR-39.1.3), through the public surface: functions written by an application and a Host
//! that composes them.

mod support;

use compose_rust::ComposeHost;
use compose_rust::runtime::*;
use compose_rust::ui::*;
use std::cell::RefCell;
use support::Tree;

thread_local! {
    static LOG: RefCell<Vec<String>> = const { RefCell::new(Vec::new()) };
}

fn note(line: impl Into<String>) {
    LOG.with_borrow_mut(|log| log.push(line.into()));
}

fn take_log() -> Vec<String> {
    LOG.with_borrow_mut(std::mem::take)
}

/// Composes `content` once and returns the tree it built.
fn compose(content: impl Fn() + Send + Sync + 'static) -> Tree {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    tree
}

/// A value that records when it is evaluated, to see the order arguments run in.
fn traced<T>(label: &'static str, value: T) -> T {
    note(format!("eval {label}"));
    value
}

#[Composable]
fn Labelled(
    text: String,
    on_pick: impl Fn(String) + 'static,
    modifier: Modifier,
    #[default(String::from("none"))] hint: String,
    #[default(false)] bold: bool,
    #[default(|| {})] content: impl Fn() + 'static,
) {
    on_pick(text.clone());
    note(format!(
        "Labelled text={text} hint={hint} bold={bold} plain_modifier={}",
        modifier == Modifier::new()
    ));
    content();
}

#[Composable]
fn Pair(first: i32, second: i32) {
    note(format!("Pair {first} {second}"));
}

#[test]
fn fr39_function_form_takes_every_argument_and_no_defaults() {
    take_log();
    compose(|| {
        Labelled(
            "a".into(),
            |_| {},
            Modifier::new(),
            "h".into(),
            true,
            || note("content"),
        );
    });
    assert_eq!(
        take_log(),
        vec!["Labelled text=a hint=h bold=true plain_modifier=true", "content"]
    );
}

#[test]
fn fr39_call_macro_positional_prefix_then_named_in_any_order() {
    take_log();
    compose(|| {
        Labelled!("a".into(), |_| {}, bold = true, hint = "h".into());
        Labelled!("b".into(), |_| {}, hint = "x".into(), bold = false);
    });
    assert_eq!(
        take_log(),
        vec![
            "Labelled text=a hint=h bold=true plain_modifier=true",
            "Labelled text=b hint=x bold=false plain_modifier=true",
        ]
    );
}

#[test]
fn fr39_call_macro_names_alone_fill_a_signature() {
    take_log();
    compose(|| {
        Labelled!(on_pick = |_| {}, text = "n".into());
        Pair!(second = 2, first = 1);
    });
    assert_eq!(
        take_log(),
        vec![
            "Labelled text=n hint=none bold=false plain_modifier=true",
            "Pair 1 2"
        ]
    );
}

#[test]
fn fr39_call_macro_closure_parameters_infer_from_the_function_bounds() {
    take_log();
    compose(|| {
        // `text` is a String only because `on_pick: impl Fn(String)` says so.
        Labelled!("c".into(), |text| note(format!("picked {}", text.len())));
    });
    assert_eq!(take_log()[0], "picked 1");
}

#[test]
fn fr39_call_macro_trailing_block_is_the_content_closure() {
    take_log();
    compose(|| {
        Labelled!("a".into(), |_| {}, { note("block") });
        Labelled!("b".into(), |_| {}, bold = true, { note("block after names") });
        Labelled!("c".into(), |_| {}, content = || note("named content"));
    });
    let log = take_log();
    assert!(log.contains(&"block".to_string()));
    assert!(log.contains(&"block after names".to_string()));
    assert!(log.contains(&"named content".to_string()));
}

#[test]
fn fr39_call_macro_evaluates_arguments_in_parameter_order() {
    take_log();
    compose(|| {
        Pair!(second = traced("second", 2), first = traced("first", 1));
    });
    assert_eq!(take_log(), vec!["eval first", "eval second", "Pair 1 2"]);
}

#[test]
fn fr39_default_modifier_is_modifier_new_and_other_defaults_apply_in_macro_form() {
    take_log();
    compose(|| {
        Labelled!("a".into(), |_| {});
    });
    assert_eq!(
        take_log(),
        vec!["Labelled text=a hint=none bold=false plain_modifier=true"]
    );
}

#[test]
fn fr39_default_modifier_can_be_given_and_then_differs() {
    take_log();
    compose(|| {
        Labelled!("a".into(), |_| {}, modifier = Modifier::new().fill_max_width());
    });
    assert_eq!(
        take_log(),
        vec!["Labelled text=a hint=none bold=false plain_modifier=false"]
    );
}

thread_local! {
    static DEFAULTS: RefCell<u32> = const { RefCell::new(0) };
}

fn counted() -> u32 {
    DEFAULTS.with_borrow_mut(|count| {
        *count += 1;
        *count
    })
}

#[Composable]
fn Counted(#[default(counted())] value: u32) {
    note(format!("value {value}"));
}

#[test]
fn fr39_default_expressions_run_at_the_call_site_each_time() {
    take_log();
    DEFAULTS.with_borrow_mut(|count| *count = 0);
    compose(|| {
        Counted!();
        Counted!();
        Counted!(value = 9);
    });
    assert_eq!(take_log(), vec!["value 1", "value 2", "value 9"]);
}

#[Composable]
fn Generic<T: Clone + PartialEq + std::fmt::Debug + 'static>(
    items: Vec<T>,
    #[default(Vec::new())] more: Vec<T>,
) {
    note(format!("Generic {items:?} {more:?}"));
}

#[test]
fn fr39_call_macro_limits_generics_are_inferred_like_the_function_form() {
    take_log();
    compose(|| {
        Generic(vec![1], vec![2]);
        Generic!(vec![1u8]);
        Generic!(vec!["a"], more = vec!["b"]);
    });
    assert_eq!(
        take_log(),
        vec!["Generic [1] [2]", "Generic [1] []", "Generic [\"a\"] [\"b\"]"]
    );
}

#[Composable]
fn Leaf(text: String) {
    let clicks = remember(|| mutable_state_of(0));
    Text(format!("{text}:{}", clicks.get()));
}

#[test]
fn fr39_call_macro_expands_to_the_same_tree_as_the_function_form() {
    let function_form = compose(|| {
        Column().content(|| {
            Leaf("a".into());
            Leaf("b".into());
        });
    });
    let macro_form = compose(|| {
        Column().content(|| {
            Leaf!("a".into());
            Leaf!("b".into());
        });
    });
    assert_eq!(function_form.dump(), macro_form.dump());
}

#[test]
fn fr39_call_macro_expands_mixed_with_the_function_form_keeps_each_calls_state() {
    // The slot key is made inside the function, so a call through the macro and a call
    // through the function share one position and one identity. Mixing the two forms in one
    // parent and recomposing must keep each call's remembered state apart and intact.
    let mut host = ComposeHost::with_content(|| {
        Column().content(|| {
            Leaf("fn".into());
            Leaf!("macro".into());
        });
    });
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    assert_eq!(tree.texts(), vec!["fn:0", "macro:0"]);
}
