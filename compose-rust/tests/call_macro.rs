//! The call macro `#[Composable]` generates next to every composable, through the public
//! surface: functions written by an application and a Host that composes them.

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
        vec![
            "Labelled text=a hint=h bold=true plain_modifier=true",
            "content"
        ]
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
        Labelled!("b".into(), |_| {}, bold = true, {
            note("block after names")
        });
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
        Labelled!(
            "a".into(),
            |_| {},
            modifier = Modifier::new().fill_max_width()
        );
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
        vec![
            "Generic [1] [2]",
            "Generic [1] []",
            "Generic [\"a\"] [\"b\"]"
        ]
    );
}

#[Composable]
fn Repeated<const N: usize>(
    cells: [u8; N],
    label: impl Into<String> + Clone + PartialEq + 'static,
) {
    let label: String = label.into();
    note(format!("Repeated {N} {cells:?} {label}"));
}

#[test]
fn fr39_call_macro_limits_const_generics_and_impl_trait_are_inferred_like_the_function_form() {
    take_log();
    compose(|| {
        Repeated([1, 2], "f");
        Repeated!([7, 8, 9], "m");
        Repeated!([], label = String::from("named"));
    });
    assert_eq!(
        take_log(),
        vec![
            "Repeated 2 [1, 2] f",
            "Repeated 3 [7, 8, 9] m",
            "Repeated 0 [] named"
        ]
    );
}

#[Composable]
fn Leaf(text: String) {
    let clicks = remember(|| mutable_state_of(0));
    Text!(format!("{text}:{}", clicks.get()));
}

#[test]
fn fr39_call_macro_expands_to_the_same_tree_as_the_function_form() {
    let function_form = compose(|| {
        Column!({
            Leaf("a".into());
            Leaf("b".into());
        });
    });
    let macro_form = compose(|| {
        Column!({
            Leaf!("a".into());
            Leaf!("b".into());
        });
    });
    assert_eq!(function_form.dump(), macro_form.dump());
}

thread_local! {
    static KEPT: RefCell<Vec<(String, MutableState<i32>)>> = const { RefCell::new(Vec::new()) };
}

/// A text with a remembered count, which hands its state out the first time it is made.
#[Composable]
fn Kept(label: String, round: i32) {
    let count = remember(|| {
        let count = mutable_state_of(0);
        KEPT.with_borrow_mut(|kept| kept.push((label.clone(), count)));
        count
    });
    Text!(format!("{label}:{round}:{}", count.get()));
}

/// The state `Kept` made for `label`.
fn kept(label: &str) -> MutableState<i32> {
    KEPT.with_borrow(|kept| {
        kept.iter()
            .find(|(made_for, _)| made_for == label)
            .map(|(_, count)| *count)
            .unwrap_or_else(|| panic!("no state was remembered for {label}"))
    })
}

/// Applies the next frame to `tree`, and returns the batch.
fn frame(host: &mut ComposeHost, tree: &mut Tree) -> Vec<u8> {
    let batch = host.render_frame(0).expect("the frame encodes").to_vec();
    tree.apply(&batch);
    batch
}

#[Composable]
fn Mixed(round: MutableState<i32>) {
    let now = round.get();
    Column!({
        Kept("fn".into(), now);
        Kept!("macro".into(), now);
    });
}

#[test]
fn fr39_call_macro_expands_mixed_with_the_function_form_keeps_each_calls_state() {
    // The slot key is made inside the function, so a call through the macro and a call
    // through the function share one position and one identity. Mixing the two forms in one
    // parent and recomposing must keep each call's remembered state apart and intact.
    KEPT.with_borrow_mut(Vec::clear);
    let round = mutable_state_of(0);
    let mut host = ComposeHost::with_content(move || Mixed(round));
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    assert_eq!(tree.texts(), vec!["fn:0:0", "macro:0:0"]);

    kept("fn").set(1);
    kept("macro").set(2);
    round.set(1);
    frame(&mut host, &mut tree);
    assert_eq!(
        tree.texts(),
        vec!["fn:1:1", "macro:1:2"],
        "the parent recomposed and each call kept its own count"
    );

    round.set(2);
    frame(&mut host, &mut tree);
    assert_eq!(tree.texts(), vec!["fn:2:1", "macro:2:2"]);
    assert_eq!(
        KEPT.with_borrow(Vec::len),
        2,
        "no call remembered a new state while recomposing"
    );
    assert!(!kept("fn").is_released() && !kept("macro").is_released());
}

/// The same screen twice, differing only in the form of the `Kept` call between the two
/// texts.
#[Composable]
fn ThroughTheFunction(round: MutableState<i32>) {
    let now = round.get();
    Column!({
        Text!("before");
        Kept("site".into(), now);
        Text!("after");
    });
}

#[Composable]
fn ThroughTheMacro(round: MutableState<i32>) {
    let now = round.get();
    Column!({
        Text!("before");
        Kept!("site".into(), now);
        Text!("after");
    });
}

/// The batches a program sends: the first composition, then a frame after its own state
/// and its parent's changed.
fn batches_of(program: fn(MutableState<i32>)) -> (Vec<u8>, Vec<u8>, Vec<String>) {
    KEPT.with_borrow_mut(Vec::clear);
    let round = mutable_state_of(0);
    let mut host = ComposeHost::with_content(move || program(round));
    let mut tree = Tree::default();
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    tree.apply(&first);
    kept("site").set(5);
    round.set(1);
    let second = frame(&mut host, &mut tree);
    (first, second, tree.texts())
}

#[test]
fn fr39_call_macro_one_call_site_has_the_same_key_in_both_forms() {
    // One call site written in either form makes the same group: the two programs send the
    // same records with the same node ids, both when they first compose and when the state
    // at that site and its parent's change.
    let (function_first, function_second, function_texts) = batches_of(ThroughTheFunction);
    let (macro_first, macro_second, macro_texts) = batches_of(ThroughTheMacro);
    assert_eq!(function_first, macro_first, "the first composition");
    assert_eq!(function_second, macro_second, "the recomposition");
    assert!(
        !function_second.is_empty(),
        "the recomposition sent something"
    );
    assert_eq!(function_texts, vec!["before", "site:1:5", "after"]);
    assert_eq!(macro_texts, function_texts);
}

#[test]
fn fr39_call_macro_docs_widget_reexport_is_inlined() {
    // `ui` re-exports the widgets from a private module. rustdoc follows a re-export to the
    // hidden macro behind it, so this re-export has to ask to be inlined as well, or the
    // documentation lists no widget macro.
    let source = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/src/ui/mod.rs"))
        .expect("the ui module's source is readable");
    let lines: Vec<&str> = source.lines().collect();
    let at = lines
        .iter()
        .position(|line| line.starts_with("pub use functions::"))
        .expect("ui re-exports the widget functions");
    assert!(
        at > 0 && lines[at - 1].trim() == "#[doc(inline)]",
        "the widget re-export in src/ui/mod.rs needs `#[doc(inline)]` on the line above it"
    );
}
