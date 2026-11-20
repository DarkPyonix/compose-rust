//! `Text`, `TextField`, the lazy lists and `Window` as functions and call macros: each
//! composes with every parameter set.

use compose_rust::ComposeHost;

/// The first batch `content` composes, as bytes.
fn batch(content: impl Fn() + Send + Sync + 'static) -> Vec<u8> {
    let mut host = ComposeHost::with_content(content);
    host.rebuild().expect("the first batch encodes").to_vec()
}

/// The screens with the call macros.
mod macros {
    use compose_rust::ui::*;
    use compose_rust::windows::{WindowDragEvent, WindowFrame};
    use compose_rust::{
        FontRefs, KeyEvent, MotionFinished, OverflowWrap, PointerEvent, PointerKinds, WordBreak,
    };

    pub fn text() {
        Text!("plain");
        Text!(
            "styled",
            modifier = Modifier.padding(2.0),
            type_role = TypeRole::Title,
            font_size = 14.0,
            font_weight = 600,
            line_height = 20.0,
            letter_spacing = 0.5,
            color = Paint::Role(ColorRole::Primary),
            text_align = TextAlign::Center,
            max_lines = 2,
            overflow = TextOverflow::Ellipsis,
            font = FontRefs::default(),
            font_feature_settings = &[FontFeature::TABULAR_NUMS],
            word_break = WordBreak::KeepAll,
            overflow_wrap = OverflowWrap::Anywhere,
            on_pointer = Some((PointerKinds::DOWN, |_: PointerEvent| {})),
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
    }

    pub fn text_field() {
        TextField!("", |_| {});
        let focus = use_focus_handle();
        TextField!(
            "start",
            |_| {},
            modifier = Modifier.fill_max_width(),
            placeholder = "Name",
            enabled = false,
            multiline = true,
            type_role = TypeRole::Body,
            font_size = 13.0,
            font_weight = 500,
            line_height = 18.0,
            letter_spacing = 0.25,
            color = Paint::Role(ColorRole::Primary),
            placeholder_color = Paint::Role(ColorRole::Secondary),
            caret_color = Paint::Role(ColorRole::Error),
            font_feature_settings = &[FontFeature::TABULAR_NUMS],
            word_break = WordBreak::BreakAll,
            overflow_wrap = OverflowWrap::BreakWord,
            on_submit = Some(|_: String| {}),
            on_focus_gained = Some(|| {}),
            on_focus_lost = Some(|| {}),
            on_key_down = Some(|_: KeyEvent| {}),
            on_key_up = Some(|_: KeyEvent| {}),
            on_composition_change = Some(|_: bool| {}),
            focus_handle = &focus,
            on_motion_finished = Some(|_: MotionFinished| {}),
            bare = true,
            read_only = true,
        );
    }

    pub fn lazy() {
        LazyColumn!(
            modifier = Modifier.fill_max_width(),
            on_motion_finished = Some(|_: MotionFinished| {}),
            content = |list| {
                list.item(|| {
                    Text!("header");
                });
                list.items(3, |index| {
                    Text!(format!("item {index}"));
                });
            },
        );
        LazyRow!(content = |list| list.items(2, |_| {}));
        LazyGrid!(
            columns = 3,
            min_column_width = 120.0,
            content = |list| list.items(2, |_| {}),
        );
    }

    pub fn window() {
        Window!(|| {});
        Window!(
            || {},
            title = "Notes",
            size = (900.0, 600.0),
            min_size = (300.0, 200.0),
            resizable = false,
            report_size = true,
            icon = 7,
            position = (40.0, 60.0),
            restore_key = "notes",
            zoom_level = 2,
            on_focus_changed = Some(|_: bool| {}),
            on_moved = Some(|_: WindowFrame| {}),
            on_drag = Some(|_: WindowDragEvent| {}),
            on_unavailable = Some(|| {}),
            on_closed = Some(|| {}),
            {
                Text!("inside");
            }
        );
    }
}

#[test]
fn fr39_function_form_text_composes_with_every_parameter() {
    assert!(!batch(macros::text).is_empty());
}

#[test]
fn fr39_function_form_text_field_composes_with_every_parameter() {
    assert!(!batch(macros::text_field).is_empty());
}

#[test]
fn fr39_function_form_lazy_lists_composes_with_every_parameter() {
    assert!(!batch(macros::lazy).is_empty());
}

#[test]
fn fr39_function_form_window_composes_with_every_parameter() {
    assert!(!batch(macros::window).is_empty());
}
