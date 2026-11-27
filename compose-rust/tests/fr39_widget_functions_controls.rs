//! The control widgets as call macros: each one composes with every parameter set and
//! with only the required ones, and the macro with every parameter set is the function
//! call with the same arguments.

use compose_rust::ComposeHost;
use compose_rust::ui::{ColorRole, DrawList, Paint};

/// The first batch `content` composes, as bytes.
fn batch(content: impl Fn() + Send + Sync + 'static) -> Vec<u8> {
    let mut host = ComposeHost::with_content(content);
    host.rebuild().expect("the first batch encodes").to_vec()
}

const PRIMARY: Paint = Paint::Role(ColorRole::Primary);
const OUTLINE: Paint = Paint::Role(ColorRole::Outline);
const ERROR: Paint = Paint::Role(ColorRole::Error);

/// The one drawing both forms of `Canvas` draw.
fn commands() -> DrawList {
    DrawList::builder()
        .line(PRIMARY, 0.0, 0.0, 10.0, 10.0, 1.0)
        .build()
}

/// The screens with the call macros.
mod macros {
    use super::{ERROR, OUTLINE, PRIMARY, commands};
    use compose_rust::MotionFinished;
    use compose_rust::ui::*;

    pub fn button() {
        let handle = FocusHandle::new();
        Button!(
            "Save",
            || {},
            modifier = Modifier.padding(4.0),
            accessible_name = "Save the file",
            text_align = TextAlign::Start,
            font_size = 15.0,
            font_weight = 600,
            type_role = TypeRole::Title,
            content_padding = (12.0, 6.0),
            icon = IconRole::Add,
            enabled = false,
            variant = ButtonVariant::Outlined,
            kind = ButtonKind::ActionKey,
            max_lines = 2,
            overflow = TextOverflow::Ellipsis,
            color = PRIMARY,
            focus_handle = &handle,
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                Badge!(value = 1);
            }
        );
    }

    pub fn plain_button() {
        Button!("Save", || {});
    }

    pub fn toggles() {
        Switch!(
            true,
            |_| {},
            modifier = Modifier.padding(1.0),
            accessible_name = "Wi-Fi",
            enabled = false,
            track_color = PRIMARY,
            thumb_color = OUTLINE,
            size = (52.0, 32.0),
            thumb_elevation = 2.0,
            track_border = (1.0, ERROR),
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
        Checkbox!(
            true,
            |_| {},
            modifier = Modifier.padding(2.0),
            accessible_name = "Remember me",
            enabled = false,
            container_color = PRIMARY,
            check_color = OUTLINE,
            size = 20.0,
            indeterminate = true,
            outline_color = ERROR,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
        RadioButton!(
            true,
            |_| {},
            modifier = Modifier.padding(3.0),
            accessible_name = "Small",
            enabled = false,
            ring_color = PRIMARY,
            dot_color = OUTLINE,
            size = 18.0,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
    }

    pub fn plain_toggles() {
        Switch!(false, |_| {});
        Checkbox!(false, |_| {});
        RadioButton!(false, |_| {});
    }

    pub fn slider() {
        Slider!(
            0.5,
            |_| {},
            modifier = Modifier.padding(4.0),
            accessible_name = "Volume",
            track_color = OUTLINE,
            thumb_color = PRIMARY,
            active_track_color = ERROR,
            range = (0.0, 10.0),
            steps = 4,
            enabled = false,
            color = PRIMARY,
            thumb_size = 12.0,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
    }

    pub fn plain_slider() {
        Slider!(0.5, |_| {});
    }

    pub fn progress() {
        ProgressIndicator!(
            0.5,
            modifier = Modifier.padding(1.0),
            determinate = false,
            circular = true,
            color = PRIMARY,
            track_color = OUTLINE,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
        IndeterminateProgressIndicator!(
            modifier = Modifier.padding(2.0),
            determinate = true,
            circular = true,
            color = ERROR,
            track_color = PRIMARY,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
        LinearProgressIndicator!(
            0.25,
            modifier = Modifier.padding(3.0),
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
    }

    pub fn plain_progress() {
        ProgressIndicator!(0.5);
        IndeterminateProgressIndicator!();
        LinearProgressIndicator!(0.25);
    }

    pub fn dividers() {
        Divider!(
            modifier = Modifier.padding(1.0),
            vertical = true,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
        Separator!(
            Some(OUTLINE),
            modifier = Modifier.padding(2.0),
            vertical = true,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
    }

    pub fn plain_dividers() {
        Divider!();
        Separator!(None);
        Separator!(Some(OUTLINE));
    }

    pub fn pictures() {
        Image!(
            1,
            modifier = Modifier.padding(1.0),
            color = PRIMARY,
            content_scale = ContentScale::FillBounds,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
        Icon!(
            2,
            modifier = Modifier.padding(2.0),
            color = OUTLINE,
            content_scale = ContentScale::FillBounds,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
        IconAsset!(
            3,
            modifier = Modifier.padding(3.0),
            color = ERROR,
            content_scale = ContentScale::FillBounds,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
    }

    pub fn plain_pictures() {
        Image!(1);
        Icon!(2);
        IconAsset!(3);
    }

    pub fn canvas() {
        Canvas!(
            commands(),
            modifier = Modifier.padding(1.0),
            view_box = (10.0, 20.0),
            fractional = true,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
        Canvas!(commands(), view_box = (10.0, 20.0));
    }

    pub fn plain_canvas() {
        Canvas!(commands());
    }

    pub fn pickers() {
        DatePicker!(
            19_000,
            |_| {},
            modifier = Modifier.padding(1.0),
            min = 18_000,
            max = 20_000,
            enabled = false,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
        TimePicker!(
            600,
            |_| {},
            modifier = Modifier.padding(2.0),
            min = 60,
            max = 1_200,
            enabled = false,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
    }

    pub fn plain_pickers() {
        DatePicker!(19_000, |_| {});
        TimePicker!(600, |_| {});
    }

    pub fn dropdowns() {
        Dropdown!(
            1,
            |_| {},
            modifier = Modifier.padding(1.0),
            enabled = false,
            closed = true,
            bare = true,
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                MenuItem!("One", || {});
                MenuItem!("Two", || {});
            }
        );
        Select!(
            0,
            |_| {},
            modifier = Modifier.padding(2.0),
            enabled = false,
            closed = false,
            bare = true,
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                MenuItem!("Three", || {});
            }
        );
    }

    pub fn plain_dropdowns() {
        Dropdown!(1, |_| {});
        Select!(0, |_| {});
    }

    pub fn menu_item() {
        MenuItem!(
            "Delete",
            || {},
            modifier = Modifier.padding(1.0),
            icon = IconRole::Close,
            selected = true,
            enabled = false,
            color = ERROR,
            shortcut_label = "Ctrl+D",
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
    }

    pub fn plain_menu_item() {
        MenuItem!("Delete", || {});
    }

    pub fn submenu() {
        Submenu!(
            "Export",
            modifier = Modifier.padding(1.0),
            icon = IconRole::Share,
            enabled = false,
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                MenuItem!("PDF", || {});
            }
        );
    }

    pub fn plain_submenu() {
        Submenu!("Export");
    }

    pub fn chip() {
        Chip!(
            "Rust",
            || {},
            modifier = Modifier.padding(1.0),
            icon = IconRole::Check,
            selected = true,
            enabled = false,
            max_lines = 2,
            overflow = TextOverflow::Ellipsis,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
    }

    pub fn plain_chip() {
        Chip!("Rust", || {});
    }

    pub fn floating_action() {
        FloatingAction!(
            "New",
            || {},
            modifier = Modifier.padding(1.0),
            icon = IconRole::Add,
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
    }

    pub fn plain_floating_action() {
        FloatingAction!("New", || {});
    }

    pub fn badge() {
        Badge!(
            modifier = Modifier.padding(1.0),
            value = 3,
            text = "new",
            color = ColorRole::Primary,
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                Icon!(2);
            }
        );
    }

    pub fn plain_badge() {
        Badge!();
    }
}

/// The screens of `macros` with every parameter set, in the function form: each argument in
/// declaration order, the ones the macro call leaves out given their defaults.
mod functions {
    use super::{ERROR, OUTLINE, PRIMARY, commands};
    use compose_rust::MotionFinished;
    use compose_rust::ui::*;

    pub fn button() {
        let handle = FocusHandle::new();
        Button(
            "Save",
            || {},
            Modifier.padding(4.0),
            "Save the file",
            TextAlign::Start,
            15.0,
            600,
            TypeRole::Title,
            (12.0, 6.0),
            IconRole::Add,
            false,
            ButtonVariant::Outlined,
            ButtonKind::ActionKey,
            2,
            TextOverflow::Ellipsis,
            PRIMARY,
            &handle,
            Some(|_: MotionFinished| {}),
            || {
                Badge!(value = 1);
            },
        );
    }

    pub fn toggles() {
        Switch(
            true,
            |_| {},
            Modifier.padding(1.0),
            "Wi-Fi",
            false,
            PRIMARY,
            OUTLINE,
            (52.0, 32.0),
            2.0,
            (1.0, ERROR),
            Some(|_: MotionFinished| {}),
        );
        Checkbox(
            true,
            |_| {},
            Modifier.padding(2.0),
            "Remember me",
            false,
            PRIMARY,
            OUTLINE,
            20.0,
            true,
            ERROR,
            Some(|_: MotionFinished| {}),
        );
        RadioButton(
            true,
            |_| {},
            Modifier.padding(3.0),
            "Small",
            false,
            PRIMARY,
            OUTLINE,
            18.0,
            Some(|_: MotionFinished| {}),
        );
    }

    pub fn slider() {
        Slider(
            0.5,
            |_| {},
            Modifier.padding(4.0),
            "Volume",
            OUTLINE,
            PRIMARY,
            ERROR,
            (0.0, 10.0),
            4,
            false,
            PRIMARY,
            12.0,
            Some(|_: MotionFinished| {}),
        );
    }

    pub fn progress() {
        ProgressIndicator(
            0.5,
            Modifier.padding(1.0),
            false,
            true,
            PRIMARY,
            OUTLINE,
            Some(|_: MotionFinished| {}),
        );
        IndeterminateProgressIndicator(
            Modifier.padding(2.0),
            true,
            true,
            ERROR,
            PRIMARY,
            Some(|_: MotionFinished| {}),
        );
        LinearProgressIndicator(0.25, Modifier.padding(3.0), Some(|_: MotionFinished| {}));
    }

    pub fn dividers() {
        Divider(Modifier.padding(1.0), true, Some(|_: MotionFinished| {}));
        Separator(
            Some(OUTLINE),
            Modifier.padding(2.0),
            true,
            Some(|_: MotionFinished| {}),
        );
    }

    pub fn pictures() {
        Image(
            1,
            Modifier.padding(1.0),
            PRIMARY,
            ContentScale::FillBounds,
            Some(|_: MotionFinished| {}),
        );
        Icon(
            2,
            Modifier.padding(2.0),
            OUTLINE,
            ContentScale::FillBounds,
            Some(|_: MotionFinished| {}),
        );
        IconAsset(
            3,
            Modifier.padding(3.0),
            ERROR,
            ContentScale::FillBounds,
            Some(|_: MotionFinished| {}),
        );
    }

    pub fn canvas() {
        Canvas(
            commands(),
            Modifier.padding(1.0),
            (10.0, 20.0),
            true,
            Some(|_: MotionFinished| {}),
        );
        Canvas(
            commands(),
            Modifier::new(),
            (10.0, 20.0),
            false,
            None::<fn(MotionFinished)>,
        );
    }

    pub fn pickers() {
        DatePicker(
            19_000,
            |_| {},
            Modifier.padding(1.0),
            18_000,
            20_000,
            false,
            Some(|_: MotionFinished| {}),
        );
        TimePicker(
            600,
            |_| {},
            Modifier.padding(2.0),
            60,
            1_200,
            false,
            Some(|_: MotionFinished| {}),
        );
    }

    pub fn dropdowns() {
        Dropdown(
            1,
            |_| {},
            Modifier.padding(1.0),
            false,
            true,
            true,
            Some(|_: MotionFinished| {}),
            || {
                MenuItem!("One", || {});
                MenuItem!("Two", || {});
            },
        );
        Select(
            0,
            |_| {},
            Modifier.padding(2.0),
            false,
            false,
            true,
            Some(|_: MotionFinished| {}),
            || {
                MenuItem!("Three", || {});
            },
        );
    }

    pub fn menu_item() {
        MenuItem(
            "Delete",
            || {},
            Modifier.padding(1.0),
            IconRole::Close,
            true,
            false,
            ERROR,
            "Ctrl+D",
            Some(|_: MotionFinished| {}),
        );
    }

    pub fn submenu() {
        Submenu(
            "Export",
            Modifier.padding(1.0),
            IconRole::Share,
            false,
            Some(|_: MotionFinished| {}),
            || {
                MenuItem!("PDF", || {});
            },
        );
    }

    pub fn chip() {
        Chip(
            "Rust",
            || {},
            Modifier.padding(1.0),
            IconRole::Check,
            true,
            false,
            2,
            TextOverflow::Ellipsis,
            Some(|_: MotionFinished| {}),
        );
    }

    pub fn floating_action() {
        FloatingAction(
            "New",
            || {},
            Modifier.padding(1.0),
            IconRole::Add,
            Some(|_: MotionFinished| {}),
        );
    }

    pub fn badge() {
        Badge(
            Modifier.padding(1.0),
            3,
            "new",
            ColorRole::Primary,
            Some(|_: MotionFinished| {}),
            || {
                Icon!(2);
            },
        );
    }
}

#[test]
fn fr39_function_form_button_composes_with_every_parameter() {
    assert!(!batch(macros::button).is_empty());
    assert!(!batch(macros::plain_button).is_empty());
}

#[test]
fn fr39_function_form_toggles_composes_with_every_parameter() {
    assert!(!batch(macros::toggles).is_empty());
    assert!(!batch(macros::plain_toggles).is_empty());
}

#[test]
fn fr39_function_form_slider_composes_with_every_parameter() {
    assert!(!batch(macros::slider).is_empty());
    assert!(!batch(macros::plain_slider).is_empty());
}

#[test]
fn fr39_function_form_progress_composes_with_every_parameter() {
    assert!(!batch(macros::progress).is_empty());
    assert!(!batch(macros::plain_progress).is_empty());
}

#[test]
fn fr39_function_form_dividers_composes_with_every_parameter() {
    assert!(!batch(macros::dividers).is_empty());
    assert!(!batch(macros::plain_dividers).is_empty());
}

#[test]
fn fr39_function_form_pictures_composes_with_every_parameter() {
    assert!(!batch(macros::pictures).is_empty());
    assert!(!batch(macros::plain_pictures).is_empty());
}

#[test]
fn fr39_function_form_canvas_composes_with_every_parameter() {
    assert!(!batch(macros::canvas).is_empty());
    assert!(!batch(macros::plain_canvas).is_empty());
}

#[test]
fn fr39_function_form_pickers_composes_with_every_parameter() {
    assert!(!batch(macros::pickers).is_empty());
    assert!(!batch(macros::plain_pickers).is_empty());
}

#[test]
fn fr39_function_form_dropdowns_composes_with_every_parameter() {
    assert!(!batch(macros::dropdowns).is_empty());
    assert!(!batch(macros::plain_dropdowns).is_empty());
}

#[test]
fn fr39_function_form_submenu_composes_with_every_parameter() {
    assert!(!batch(macros::submenu).is_empty());
    assert!(!batch(macros::plain_submenu).is_empty());
}

#[test]
fn fr39_call_macro_submenu_is_the_function_call() {
    assert_eq!(batch(functions::submenu), batch(macros::submenu));
}

#[test]
fn fr39_function_form_menu_item_composes_with_every_parameter() {
    assert!(!batch(macros::menu_item).is_empty());
    assert!(!batch(macros::plain_menu_item).is_empty());
}

#[test]
fn fr39_function_form_chip_composes_with_every_parameter() {
    assert!(!batch(macros::chip).is_empty());
    assert!(!batch(macros::plain_chip).is_empty());
}

#[test]
fn fr39_function_form_floating_action_composes_with_every_parameter() {
    assert!(!batch(macros::floating_action).is_empty());
    assert!(!batch(macros::plain_floating_action).is_empty());
}

#[test]
fn fr39_function_form_badge_composes_with_every_parameter() {
    assert!(!batch(macros::badge).is_empty());
    assert!(!batch(macros::plain_badge).is_empty());
}

#[test]
fn fr39_call_macro_button_is_the_function_call() {
    assert_eq!(batch(functions::button), batch(macros::button));
}

#[test]
fn fr39_call_macro_toggles_is_the_function_call() {
    assert_eq!(batch(functions::toggles), batch(macros::toggles));
}

#[test]
fn fr39_call_macro_slider_is_the_function_call() {
    assert_eq!(batch(functions::slider), batch(macros::slider));
}

#[test]
fn fr39_call_macro_progress_is_the_function_call() {
    assert_eq!(batch(functions::progress), batch(macros::progress));
}

#[test]
fn fr39_call_macro_dividers_is_the_function_call() {
    assert_eq!(batch(functions::dividers), batch(macros::dividers));
}

#[test]
fn fr39_call_macro_pictures_is_the_function_call() {
    assert_eq!(batch(functions::pictures), batch(macros::pictures));
}

#[test]
fn fr39_call_macro_canvas_is_the_function_call() {
    assert_eq!(batch(functions::canvas), batch(macros::canvas));
}

#[test]
fn fr39_call_macro_pickers_is_the_function_call() {
    assert_eq!(batch(functions::pickers), batch(macros::pickers));
}

#[test]
fn fr39_call_macro_dropdowns_is_the_function_call() {
    assert_eq!(batch(functions::dropdowns), batch(macros::dropdowns));
}

#[test]
fn fr39_call_macro_menu_item_is_the_function_call() {
    assert_eq!(batch(functions::menu_item), batch(macros::menu_item));
}

#[test]
fn fr39_call_macro_chip_is_the_function_call() {
    assert_eq!(batch(functions::chip), batch(macros::chip));
}

#[test]
fn fr39_call_macro_floating_action_is_the_function_call() {
    assert_eq!(
        batch(functions::floating_action),
        batch(macros::floating_action)
    );
}

#[test]
fn fr39_call_macro_badge_is_the_function_call() {
    assert_eq!(batch(functions::badge), batch(macros::badge));
}

/// A background the modifier already has survives a separator without a colour of its own.
#[test]
fn fr39_function_form_separator_keeps_the_modifier_background() {
    use compose_rust::ui::*;
    assert_eq!(
        batch(|| {
            Separator!(None, modifier = Modifier.background(OUTLINE));
        }),
        batch(|| {
            Divider!(modifier = Modifier.background(OUTLINE));
        })
    );
}
