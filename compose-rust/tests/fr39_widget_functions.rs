//! The layout widgets as functions and call macros: they compose with every parameter
//! set, the two forms are one call, and an optional handler can be absent.

use compose_rust::ComposeHost;

/// The first batch `content` composes, as bytes.
fn batch(content: impl Fn() + Send + Sync + 'static) -> Vec<u8> {
    let mut host = ComposeHost::with_content(content);
    host.rebuild().expect("the first batch encodes").to_vec()
}

/// The screens with the call macros.
mod macros {
    use compose_rust::ui::*;
    use compose_rust::{KeyEvent, MotionFinished, PointerEvent, PointerKinds};

    pub fn layout() {
        Column!(
            modifier = Modifier.padding(8.0),
            arrangement = Arrangement::SpaceBetween,
            spacing = 4.0,
            space_role = SpaceRole::Md,
            alignment = Alignment::Center,
            intrinsic_size = true,
            radio_group = true,
            on_click = Some(|| {}),
            on_pointer = Some((PointerKinds::DOWN, |_: PointerEvent| {})),
            on_focus_gained = Some(|| {}),
            on_focus_lost = Some(|| {}),
            on_key_down = Some(|_: KeyEvent| {}),
            on_key_up = Some(|_: KeyEvent| {}),
            on_motion_finished = Some(|_: MotionFinished| {}),
            tear_off_key = "pane",
            on_window_drag_over = Some(|_: compose_rust::windows::WindowDragOver| {}),
            {
                Row!(spacing = 2.0, {
                    Box!(alignment = Alignment::BottomEnd, {
                        Spacer!(modifier = Modifier.width(4.0));
                    });
                });
                ScrollColumn!(snap = Snap::ItemStart, {
                    Card!(on_click = Some(|| {}), {
                        Surface!({
                            SelectionContainer!({});
                        });
                    });
                });
                ScrollRow!({});
                FlowRow!(
                    horizontal_spacing = 3.0,
                    vertical_spacing = 5.0,
                    horizontal_alignment = HorizontalAlignment::End,
                    vertical_alignment = VerticalAlignment::Bottom,
                    max_items_in_each_row = 4,
                    {}
                );
            }
        );
    }

    pub fn plain_layout() {
        Column!({
            Row!();
        });
    }
}

/// The plain screen with the function form, which takes every parameter.
mod functions {
    use compose_rust::ui::*;
    use compose_rust::windows::WindowDragOver;
    use compose_rust::{BackRequested, KeyEvent, MotionFinished, PointerEvent, PointerKinds};

    /// `macros::layout`, every call in the function form.
    pub fn layout() {
        Column(
            Modifier.padding(8.0),
            Arrangement::SpaceBetween,
            4.0,
            SpaceRole::Md,
            Alignment::Center,
            true,
            true,
            Some(|| {}),
            Some((PointerKinds::DOWN, |_: PointerEvent| {})),
            Some(|| {}),
            Some(|| {}),
            Some(|_: KeyEvent| {}),
            Some(|_: KeyEvent| {}),
            None::<&FocusHandle>,
            Some(|_: MotionFinished| {}),
            None::<fn(BackRequested)>,
            "pane",
            Some(|_: WindowDragOver| {}),
            || {
                Row(
                    Modifier::new(),
                    None::<Arrangement>,
                    2.0,
                    None::<SpaceRole>,
                    None::<Alignment>,
                    None::<bool>,
                    false,
                    None::<fn()>,
                    None::<(PointerKinds, fn(PointerEvent))>,
                    None::<fn()>,
                    None::<fn()>,
                    None::<fn(KeyEvent)>,
                    None::<fn(KeyEvent)>,
                    None::<&FocusHandle>,
                    None::<fn(MotionFinished)>,
                    None::<fn(BackRequested)>,
                    None::<&str>,
                    None::<fn(WindowDragOver)>,
                    || {
                        Box(
                            Modifier::new(),
                            Alignment::BottomEnd,
                            None::<fn()>,
                            None::<(PointerKinds, fn(PointerEvent))>,
                            None::<fn()>,
                            None::<fn()>,
                            None::<fn(KeyEvent)>,
                            None::<fn(KeyEvent)>,
                            None::<&FocusHandle>,
                            None::<fn(MotionFinished)>,
                            None::<fn(BackRequested)>,
                            None::<&str>,
                            None::<fn(WindowDragOver)>,
                            || {
                                Spacer(
                                    Modifier.width(4.0),
                                    None::<fn()>,
                                    None::<(PointerKinds, fn(PointerEvent))>,
                                    None::<fn()>,
                                    None::<fn()>,
                                    None::<fn(KeyEvent)>,
                                    None::<fn(KeyEvent)>,
                                    None::<&FocusHandle>,
                                    None::<fn(MotionFinished)>,
                                    None::<fn(BackRequested)>,
                                    || {},
                                );
                            },
                        );
                    },
                );
                ScrollColumn(
                    Modifier::new(),
                    None::<&ScrollState>,
                    Snap::ItemStart,
                    None::<fn()>,
                    None::<(PointerKinds, fn(PointerEvent))>,
                    None::<fn()>,
                    None::<fn()>,
                    None::<fn(KeyEvent)>,
                    None::<fn(KeyEvent)>,
                    None::<&FocusHandle>,
                    None::<fn(MotionFinished)>,
                    None::<fn(BackRequested)>,
                    || {
                        Card(
                            Modifier::new(),
                            Some(|| {}),
                            None::<(PointerKinds, fn(PointerEvent))>,
                            None::<fn()>,
                            None::<fn()>,
                            None::<fn(KeyEvent)>,
                            None::<fn(KeyEvent)>,
                            None::<&FocusHandle>,
                            None::<fn(MotionFinished)>,
                            None::<fn(BackRequested)>,
                            || {
                                Surface(
                                    Modifier::new(),
                                    None::<fn()>,
                                    None::<(PointerKinds, fn(PointerEvent))>,
                                    None::<fn()>,
                                    None::<fn()>,
                                    None::<fn(KeyEvent)>,
                                    None::<fn(KeyEvent)>,
                                    None::<&FocusHandle>,
                                    None::<fn(MotionFinished)>,
                                    None::<fn(BackRequested)>,
                                    || {
                                        SelectionContainer(
                                            Modifier::new(),
                                            None::<fn()>,
                                            None::<(PointerKinds, fn(PointerEvent))>,
                                            None::<fn()>,
                                            None::<fn()>,
                                            None::<fn(KeyEvent)>,
                                            None::<fn(KeyEvent)>,
                                            None::<&FocusHandle>,
                                            None::<fn(MotionFinished)>,
                                            None::<fn(BackRequested)>,
                                            || {},
                                        );
                                    },
                                );
                            },
                        );
                    },
                );
                ScrollRow(
                    Modifier::new(),
                    None::<&ScrollState>,
                    Snap::None,
                    None::<fn()>,
                    None::<(PointerKinds, fn(PointerEvent))>,
                    None::<fn()>,
                    None::<fn()>,
                    None::<fn(KeyEvent)>,
                    None::<fn(KeyEvent)>,
                    None::<&FocusHandle>,
                    None::<fn(MotionFinished)>,
                    None::<fn(BackRequested)>,
                    || {},
                );
                FlowRow(
                    Modifier::new(),
                    3.0,
                    5.0,
                    HorizontalAlignment::End,
                    VerticalAlignment::Bottom,
                    4,
                    None::<fn(MotionFinished)>,
                    || {},
                );
            },
        );
    }

    pub fn plain_layout() {
        Column(
            Modifier::new(),
            None::<Arrangement>,
            None::<f32>,
            None::<SpaceRole>,
            None::<Alignment>,
            None::<bool>,
            false,
            None::<fn()>,
            None::<(PointerKinds, fn(PointerEvent))>,
            None::<fn()>,
            None::<fn()>,
            None::<fn(KeyEvent)>,
            None::<fn(KeyEvent)>,
            None::<&FocusHandle>,
            None::<fn(MotionFinished)>,
            None::<fn(BackRequested)>,
            None::<&str>,
            None::<fn(WindowDragOver)>,
            || {
                Row(
                    Modifier::new(),
                    None::<Arrangement>,
                    None::<f32>,
                    None::<SpaceRole>,
                    None::<Alignment>,
                    None::<bool>,
                    false,
                    None::<fn()>,
                    None::<(PointerKinds, fn(PointerEvent))>,
                    None::<fn()>,
                    None::<fn()>,
                    None::<fn(KeyEvent)>,
                    None::<fn(KeyEvent)>,
                    None::<&FocusHandle>,
                    None::<fn(MotionFinished)>,
                    None::<fn(BackRequested)>,
                    None::<&str>,
                    None::<fn(WindowDragOver)>,
                    || {},
                );
            },
        );
    }
}

#[test]
fn fr39_function_form_layout_composes_with_every_parameter() {
    assert!(!batch(macros::layout).is_empty());
    assert!(!batch(macros::plain_layout).is_empty());
}

#[test]
fn fr39_call_macro_layout_is_the_function_call() {
    assert_eq!(batch(functions::plain_layout), batch(macros::plain_layout));
}

#[test]
fn fr39_call_macro_layout_with_every_parameter_is_the_function_call() {
    assert_eq!(batch(functions::layout), batch(macros::layout));
}

#[test]
fn fr39_function_form_optional_handler_can_depend_on_a_condition() {
    use compose_rust::ui::*;
    fn screen(clickable: bool) -> Vec<u8> {
        batch(move || {
            Column!(on_click = clickable.then_some(|| {}), {});
        })
    }
    assert_eq!(
        screen(true),
        batch(|| {
            Column!(on_click = Some(|| {}), {});
        })
    );
    assert_ne!(screen(true), screen(false));
    assert_eq!(
        screen(false),
        batch(|| {
            Column!({});
        })
    );
}
