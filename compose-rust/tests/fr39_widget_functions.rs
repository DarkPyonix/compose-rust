//! The widgets as functions and call macros: each one writes the bytes its builder writes,
//! in the function form and in the macro form, and its parameters come in the one order.

use compose_rust::ComposeHost;

/// The first batch `content` composes, as bytes.
fn batch(content: impl Fn() + Send + Sync + 'static) -> Vec<u8> {
    let mut host = ComposeHost::with_content(content);
    host.rebuild().expect("the first batch encodes").to_vec()
}

/// The screens written with the builders, for as long as the builders are public.
mod builders {
    use compose_rust::ui::*;
    use compose_rust::{KeyEvent, PointerEvent, PointerKinds};

    pub fn layout() {
        Column()
            .modifier(Modifier.padding(8.0))
            .arrangement(Arrangement::SpaceBetween)
            .spacing(4.0)
            .space_role(SpaceRole::Md)
            .alignment(Alignment::Center)
            .intrinsic_size(true)
            .radio_group(true)
            .on_click(|| {})
            .on_pointer(PointerKinds::DOWN, |_: PointerEvent| {})
            .on_focus_gained(|| {})
            .on_focus_lost(|| {})
            .on_key_down(|_: KeyEvent| {})
            .on_key_up(|_: KeyEvent| {})
            .on_motion_finished(|_| {})
            .tear_off_key("pane")
            .on_window_drag_over(|_| {})
            .content(|| {
                Row().spacing(2.0).content(|| {
                    Box().alignment(Alignment::BottomEnd).content(|| {
                        Spacer().modifier(Modifier.width(4.0));
                    });
                });
                ScrollColumn().snap(Snap::ItemStart).content(|| {
                    Card().on_click(|| {}).content(|| {
                        Surface().content(|| {
                            SelectionContainer().content(|| {});
                        });
                    });
                });
                ScrollRow().content(|| {});
                FlowRow()
                    .horizontal_spacing(3.0)
                    .vertical_spacing(5.0)
                    .horizontal_alignment(HorizontalAlignment::End)
                    .vertical_alignment(VerticalAlignment::Bottom)
                    .max_items_in_each_row(4)
                    .content(|| {});
            });
    }

    pub fn plain_layout() {
        Column().content(|| {
            Row();
        });
    }
}

/// The same screens with the call macros.
mod macros {
    use compose_rust::ui::next::*;
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
    use compose_rust::ui::next::*;
    use compose_rust::windows::WindowDragOver;
    use compose_rust::{KeyEvent, MotionFinished, PointerEvent, PointerKinds};

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
                    None::<&str>,
                    None::<fn(WindowDragOver)>,
                    || {},
                );
            },
        );
    }
}

#[test]
fn fr39_function_form_layout_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::layout), batch(builders::layout));
    assert_eq!(batch(macros::plain_layout), batch(builders::plain_layout));
}

#[test]
fn fr39_call_macro_layout_is_the_function_call() {
    assert_eq!(batch(functions::plain_layout), batch(macros::plain_layout));
}

#[test]
fn fr39_function_form_optional_handler_can_depend_on_a_condition() {
    use compose_rust::ui::next::*;
    fn screen(clickable: bool) -> Vec<u8> {
        batch(move || {
            Column!(on_click = clickable.then_some(|| {}), {});
        })
    }
    assert_eq!(
        screen(true),
        batch(|| {
            compose_rust::ui::Column().on_click(|| {}).content(|| {});
        })
    );
    assert_eq!(
        screen(false),
        batch(|| {
            compose_rust::ui::Column().content(|| {});
        })
    );
}
