//! Each case is the source before and what the codemod writes, before rustfmt.

use fr39_codemod::{Options, rewrite};

fn run(source: &str) -> (String, Vec<String>) {
    let outcome = rewrite(source, &Options::default()).expect("the case parses");
    let flags = outcome
        .flags
        .iter()
        .map(|flag| format!("{}: {}", flag.line, flag.reason))
        .collect();
    (outcome.source, flags)
}

fn same(before: &str, after: &str) {
    let (got, flags) = run(before);
    assert_eq!(got, after);
    assert!(flags.is_empty(), "unexpected flags: {flags:?}");
}

#[test]
fn a_bare_widget_call_becomes_its_macro() {
    same("fn f() { Text(\"a\"); }", "fn f() { Text!(\"a\"); }");
}

#[test]
fn methods_become_named_arguments_in_chain_order() {
    same(
        "fn f() { Text(\"a\").font_size(14.0).color(paint).modifier(m); }",
        "fn f() { Text!(\"a\", font_size = 14.0, color = paint, modifier = m); }",
    );
}

#[test]
fn a_content_closure_becomes_the_trailing_block_and_nested_chains_follow() {
    same(
        "fn f() { Column().spacing(8.0).content(|| { Text(\"a\").max_lines(1); Button(\"b\").on_click(save); }); }",
        "fn f() { Column!(spacing = 8.0, { Text!(\"a\", max_lines = 1); Button!(\"b\", save); }); }",
    );
}

#[test]
fn a_missing_main_handler_is_an_empty_closure() {
    same(
        "fn f() { Button(\"b\").enabled(false); Switch(on); }",
        "fn f() { Button!(\"b\", || {}, enabled = false); Switch!(on, |_| {}); }",
    );
}

#[test]
fn text_field_text_is_the_first_positional_and_defaults_to_empty() {
    same(
        "fn f() { TextField().text(t).on_value_change(set).multiline(true); TextField().bare(); }",
        "fn f() { TextField!(t, set, multiline = true); TextField!(\"\", |_| {}, bare = true); }",
    );
}

#[test]
fn optional_handlers_are_wrapped_and_their_closures_typed() {
    same(
        "fn f() { Box().on_click(move || open.set(true)).on_key_down(|event| handle(event)); }",
        "fn f() { Box!(on_click = Some(move || open.set(true)), on_key_down = Some(|event: ::compose_rust::KeyEvent| handle(event))); }",
    );
}

#[test]
fn a_typed_or_named_handler_is_wrapped_as_it_is() {
    same(
        "fn f() { Box().on_key_up(|e: KeyEvent| g(e)).on_focus_lost(done); }",
        "fn f() { Box!(on_key_up = Some(|e: KeyEvent| g(e)), on_focus_lost = Some(done)); }",
    );
}

#[test]
fn on_pointer_is_a_tuple_in_some() {
    same(
        "fn f() { Box().on_pointer(kinds, move |event| seen(event)); }",
        "fn f() { Box!(on_pointer = Some((kinds, move |event: ::compose_rust::PointerEvent| seen(event)))); }",
    );
}

#[test]
fn several_arguments_are_a_tuple_and_a_flag_is_true() {
    same(
        "fn f() { Button(\"b\").on_click(go).content_padding(12.0, 6.0); Canvas(list).fractional(); }",
        "fn f() { Button!(\"b\", go, content_padding = (12.0, 6.0)); Canvas!(list, fractional = true); }",
    );
}

#[test]
fn if_twins_and_renames_take_the_parameter_name() {
    same(
        "fn f() { Button(\"b\").on_click(go).color_if(paint); Row().equal_height(true); ProgressIndicator(0.5).indicator_color(p); }",
        "fn f() { Button!(\"b\", go, color = paint); Row!(intrinsic_size = true); ProgressIndicator!(0.5, color = p); }",
    );
}

#[test]
fn icon_asset_becomes_its_own_widget() {
    same(
        "fn f() { Icon::asset(id).color(p); }",
        "fn f() { IconAsset!(id, color = p); }",
    );
}

#[test]
fn a_lazy_list_keeps_its_content_closure_named() {
    same(
        "fn f() { LazyColumn().content(|list| list.item(1, || { Text(\"a\"); })); }",
        "fn f() { LazyColumn!(content = |list| list.item(1, || { Text!(\"a\"); })); }",
    );
}

#[test]
fn a_str_parameter_takes_a_literal_as_it_is_and_anything_else_as_ref() {
    same(
        "fn f() { Box().tear_off_key(\"k\"); Window(close).title(name.clone()); }",
        "fn f() { Box!(tear_off_key = \"k\"); Window!(close, title = AsRef::<str>::as_ref(&(name.clone()))); }",
    );
}

#[test]
fn a_call_through_a_path_brings_the_name_into_scope() {
    same(
        "fn f() { compose_rust::ui::Text(\"a\").max_lines(2); }",
        "fn f() { { use ::compose_rust::ui::next::Text; Text!(\"a\", max_lines = 2) }; }",
    );
}

#[test]
fn imports_of_widgets_move_to_next() {
    same(
        "use compose_rust::ui::*;\nuse compose_rust::ui::{Column, Text};\nuse compose_rust::ui::Modifier;\nuse compose_rust::ui::{self, Text as T};\n",
        "use compose_rust::ui::next::*;\nuse compose_rust::ui::next::{Column, Text};\nuse compose_rust::ui::Modifier;\nuse compose_rust::ui::{self, Text as T};\n",
    );
}

#[test]
fn a_builder_bound_with_let_is_left_alone_and_reported() {
    let source = "fn f() { let b = Column().spacing(4.0); b.content(|| {}); }";
    let (got, flags) = run(source);
    assert_eq!(got, source);
    assert_eq!(flags.len(), 1, "{flags:?}");
    assert!(flags[0].contains("value is used"), "{flags:?}");
}

#[test]
fn a_builder_returned_or_in_a_match_arm_is_left_alone() {
    let source = "fn f() -> TextScope { Text(\"a\") }\nfn g(x: bool) { match x { true => Text(\"a\"), false => Text(\"b\") }; }";
    let (got, flags) = run(source);
    assert_eq!(got, source);
    assert_eq!(flags.len(), 3, "{flags:?}");
}

#[test]
fn the_same_parameter_twice_is_left_alone() {
    let source = "fn f() { Button(\"b\").color(a).color_if(b); }";
    let (got, flags) = run(source);
    assert_eq!(got, source);
    assert!(flags[0].contains("set twice"), "{flags:?}");
}

#[test]
fn a_comment_between_methods_goes_in_front_of_its_argument() {
    same(
        "fn f() {\n    Button(\"b\")\n        .modifier(m)\n        // why it is off\n        .enabled(false)\n        // what it does\n        .on_click(go);\n}",
        "fn f() {\n    Button!(\"b\", \n// what it does\ngo, modifier = m, \n// why it is off\nenabled = false);\n}",
    );
}

#[test]
fn a_comment_inside_a_methods_parentheses_is_left_alone() {
    let source = "fn f() { Text(\"a\").max_lines(/* one */ 1); }";
    let (got, flags) = run(source);
    assert_eq!(got, source);
    assert!(flags[0].contains("comment"), "{flags:?}");
}

#[test]
fn a_comment_inside_an_argument_is_kept() {
    same(
        "fn f() { Column().content(|| {\n    // first\n    Text(\"a\");\n}); }",
        "fn f() { Column!({\n    // first\n    Text!(\"a\");\n}); }",
    );
}

#[test]
fn an_untyped_closure_for_an_unknown_optional_handler_is_left_alone() {
    let source = "fn f() { Box().on_something(|x| g(x)); }";
    let (got, flags) = run(source);
    assert_eq!(got, source);
    assert!(flags[0].contains("no type"), "{flags:?}");
}

#[test]
fn a_chain_inside_a_flagged_chain_is_still_rewritten() {
    let source = "fn f() { let b = Column().content(|| { Text(\"a\"); }); }";
    let (got, flags) = run(source);
    assert_eq!(
        got,
        "fn f() { let b = Column().content(|| { Text!(\"a\"); }); }"
    );
    assert_eq!(flags.len(), 1, "{flags:?}");
}

#[test]
fn non_ascii_text_keeps_its_offsets() {
    same(
        "fn f() { Text(\"한국어 ✓\").max_lines(1); Text(\"b\"); }",
        "fn f() { Text!(\"한국어 ✓\", max_lines = 1); Text!(\"b\"); }",
    );
}

#[test]
fn an_enum_variant_named_like_a_widget_is_not_a_widget() {
    let source =
        "fn f() { let v = Value::Text(s); let w = WordWrap::Column(40); g(Value::Text(t)); }";
    let (got, flags) = run(source);
    assert_eq!(got, source);
    assert!(flags.is_empty(), "{flags:?}");
}
