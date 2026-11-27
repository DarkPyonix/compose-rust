//! `Text`, `TextField`, the lazy lists and `Window` as functions and call macros: each
//! composes with every parameter set, and the macro call is the function call with the
//! same arguments.

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

/// The screens of `macros` in the function form: each argument in declaration order, the
/// ones the macro call leaves out given their defaults.
mod functions {
    use compose_rust::schema::{Chrome, TitleBar};
    use compose_rust::ui::*;
    use compose_rust::windows::{DragFromPointer, WindowDragEvent, WindowFrame};
    use compose_rust::{
        FontRefs, KeyEvent, MotionFinished, OverflowWrap, PointerEvent, PointerKinds, WordBreak,
    };

    pub fn text() {
        Text(
            "plain",
            Modifier::new(),
            TextSpans::default(),
            None::<TypeRole>,
            None::<f32>,
            None::<u16>,
            None::<f32>,
            None::<f32>,
            None::<Paint>,
            None::<TextAlign>,
            None::<u32>,
            None::<TextOverflow>,
            FontRefs::default(),
            &[],
            None::<WordBreak>,
            None::<OverflowWrap>,
            None::<(PointerKinds, fn(PointerEvent))>,
            None::<fn(MotionFinished)>,
        );
        Text(
            "styled",
            Modifier.padding(2.0),
            TextSpans::default(),
            TypeRole::Title,
            14.0,
            600,
            20.0,
            0.5,
            Paint::Role(ColorRole::Primary),
            TextAlign::Center,
            2,
            TextOverflow::Ellipsis,
            FontRefs::default(),
            &[FontFeature::TABULAR_NUMS],
            WordBreak::KeepAll,
            OverflowWrap::Anywhere,
            Some((PointerKinds::DOWN, |_: PointerEvent| {})),
            Some(|_: MotionFinished| {}),
        );
    }

    pub fn text_field() {
        TextField(
            "",
            |_| {},
            Modifier::new(),
            "",
            true,
            false,
            None::<TypeRole>,
            None::<f32>,
            None::<u16>,
            None::<f32>,
            None::<f32>,
            None::<Paint>,
            None::<Paint>,
            None::<Paint>,
            FontRefs::default(),
            &[],
            None::<WordBreak>,
            None::<OverflowWrap>,
            None::<fn(String)>,
            None::<fn()>,
            None::<fn()>,
            None::<fn(KeyEvent)>,
            None::<fn(KeyEvent)>,
            None::<fn(bool)>,
            None::<&FocusHandle>,
            None::<&TextFieldHandle>,
            None::<fn(MotionFinished)>,
            false,
            false,
            None::<&RichTextState>,
            None::<fn(compose_rust::TextFormat)>,
            None::<fn(compose_rust::RichDocument)>,
        );
        let focus = use_focus_handle();
        TextField(
            "start",
            |_| {},
            Modifier.fill_max_width(),
            "Name",
            false,
            true,
            TypeRole::Body,
            13.0,
            500,
            18.0,
            0.25,
            Paint::Role(ColorRole::Primary),
            Paint::Role(ColorRole::Secondary),
            Paint::Role(ColorRole::Error),
            FontRefs::default(),
            &[FontFeature::TABULAR_NUMS],
            WordBreak::BreakAll,
            OverflowWrap::BreakWord,
            Some(|_: String| {}),
            Some(|| {}),
            Some(|| {}),
            Some(|_: KeyEvent| {}),
            Some(|_: KeyEvent| {}),
            Some(|_: bool| {}),
            &focus,
            None::<&TextFieldHandle>,
            Some(|_: MotionFinished| {}),
            true,
            true,
            None::<&RichTextState>,
            None::<fn(compose_rust::TextFormat)>,
            None::<fn(compose_rust::RichDocument)>,
        );
    }

    pub fn lazy() {
        LazyColumn(
            Modifier.fill_max_width(),
            Some(|_: MotionFinished| {}),
            |list| {
                list.item(|| {
                    Text!("header");
                });
                list.items(3, |index| {
                    Text!(format!("item {index}"));
                });
            },
        );
        LazyRow(Modifier::new(), None::<fn(MotionFinished)>, |list| {
            list.items(2, |_| {})
        });
        LazyGrid(
            Modifier::new(),
            3,
            120.0,
            None::<fn(MotionFinished)>,
            |list| list.items(2, |_| {}),
        );
    }

    pub fn window() {
        Window(
            || {},
            None::<&str>,
            None::<(f32, f32)>,
            None::<(f32, f32)>,
            None::<bool>,
            None::<bool>,
            None::<Chrome>,
            None::<TitleBar>,
            None::<u32>,
            None::<(f32, f32)>,
            None::<&str>,
            None::<i8>,
            None::<DragFromPointer>,
            None::<Theme>,
            None::<fn(bool)>,
            None::<fn(WindowFrame)>,
            None::<fn(WindowDragEvent)>,
            None::<fn()>,
            None::<fn()>,
            None::<fn(compose_rust::BackRequested)>,
            || {},
        );
        Window(
            || {},
            "Notes",
            (900.0, 600.0),
            (300.0, 200.0),
            false,
            true,
            None::<Chrome>,
            None::<TitleBar>,
            7,
            (40.0, 60.0),
            "notes",
            2,
            None::<DragFromPointer>,
            None::<Theme>,
            Some(|_: bool| {}),
            Some(|_: WindowFrame| {}),
            Some(|_: WindowDragEvent| {}),
            Some(|| {}),
            Some(|| {}),
            None::<fn(compose_rust::BackRequested)>,
            || {
                Text!("inside");
            },
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

#[test]
fn fr39_call_macro_text_is_the_function_call() {
    assert_eq!(batch(functions::text), batch(macros::text));
}

#[test]
fn fr39_call_macro_text_field_is_the_function_call() {
    assert_eq!(batch(functions::text_field), batch(macros::text_field));
}

#[test]
fn fr39_call_macro_lazy_is_the_function_call() {
    assert_eq!(batch(functions::lazy), batch(macros::lazy));
}

#[test]
fn fr39_call_macro_window_is_the_function_call() {
    assert_eq!(batch(functions::window), batch(macros::window));
}
