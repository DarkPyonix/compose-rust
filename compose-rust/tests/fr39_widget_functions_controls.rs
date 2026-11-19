//! The control widgets as call macros: each one writes the bytes its builder writes, with
//! every parameter set and with only the required ones.

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

/// The screens written with the builders, for as long as the builders are public.
mod builders {
    use super::{ERROR, OUTLINE, PRIMARY, commands};
    use compose_rust::ui::*;

    pub fn button() {
        let handle = FocusHandle::new();
        Button("Save")
            .on_click(|| {})
            .modifier(Modifier.padding(4.0))
            .accessible_name("Save the file")
            .text_align(TextAlign::Start)
            .font_size(15.0)
            .font_weight(600)
            .type_role(TypeRole::Title)
            .content_padding(12.0, 6.0)
            .icon(IconRole::Add)
            .enabled(false)
            .variant(ButtonVariant::Outlined)
            .kind(ButtonKind::ActionKey)
            .max_lines(2)
            .overflow(TextOverflow::Ellipsis)
            .color(PRIMARY)
            .focus_handle(&handle)
            .on_motion_finished(|_| {})
            .content(|| {
                Badge().value(1);
            });
    }

    pub fn plain_button() {
        Button("Save").on_click(|| {});
    }

    pub fn toggles() {
        Switch(true)
            .on_change(|_| {})
            .modifier(Modifier.padding(1.0))
            .accessible_name("Wi-Fi")
            .enabled(false)
            .track_color(PRIMARY)
            .thumb_color(OUTLINE)
            .size(52.0, 32.0)
            .on_motion_finished(|_| {});
        Checkbox(true)
            .on_change(|_| {})
            .modifier(Modifier.padding(2.0))
            .accessible_name("Remember me")
            .enabled(false)
            .container_color(PRIMARY)
            .check_color(OUTLINE)
            .size(20.0)
            .on_motion_finished(|_| {});
        RadioButton(true)
            .on_change(|_| {})
            .modifier(Modifier.padding(3.0))
            .accessible_name("Small")
            .enabled(false)
            .ring_color(PRIMARY)
            .dot_color(OUTLINE)
            .size(18.0)
            .on_motion_finished(|_| {});
    }

    pub fn plain_toggles() {
        Switch(false).on_change(|_| {});
        Checkbox(false).on_change(|_| {});
        RadioButton(false).on_change(|_| {});
    }

    pub fn slider() {
        Slider(0.5)
            .on_change(|_| {})
            .modifier(Modifier.padding(4.0))
            .accessible_name("Volume")
            .track_color(OUTLINE)
            .thumb_color(PRIMARY)
            .active_track_color(ERROR)
            .range(0.0, 10.0)
            .steps(4)
            .enabled(false)
            .color(PRIMARY)
            .thumb_size(12.0)
            .on_motion_finished(|_| {});
    }

    pub fn plain_slider() {
        Slider(0.5).on_change(|_| {});
    }

    pub fn progress() {
        ProgressIndicator(0.5)
            .modifier(Modifier.padding(1.0))
            .determinate(false)
            .circular(true)
            .color(PRIMARY)
            .track_color(OUTLINE)
            .on_motion_finished(|_| {});
        IndeterminateProgressIndicator()
            .modifier(Modifier.padding(2.0))
            .determinate(true)
            .circular(true)
            .color(ERROR)
            .track_color(PRIMARY)
            .on_motion_finished(|_| {});
        LinearProgressIndicator(0.25)
            .modifier(Modifier.padding(3.0))
            .on_motion_finished(|_| {});
    }

    pub fn plain_progress() {
        ProgressIndicator(0.5);
        IndeterminateProgressIndicator();
        LinearProgressIndicator(0.25);
    }

    pub fn dividers() {
        Divider()
            .modifier(Modifier.padding(1.0))
            .vertical(true)
            .on_motion_finished(|_| {});
        Separator(Some(OUTLINE))
            .modifier(Modifier.padding(2.0).background(OUTLINE))
            .vertical(true)
            .on_motion_finished(|_| {});
    }

    pub fn plain_dividers() {
        Divider();
        Separator(None);
        Separator(Some(OUTLINE));
    }

    pub fn pictures() {
        Image(1)
            .modifier(Modifier.padding(1.0))
            .color(PRIMARY)
            .content_scale(ContentScale::FillBounds)
            .on_motion_finished(|_| {});
        Icon(2)
            .modifier(Modifier.padding(2.0))
            .color(OUTLINE)
            .content_scale(ContentScale::FillBounds)
            .on_motion_finished(|_| {});
        Icon::asset(3)
            .modifier(Modifier.padding(3.0))
            .color(ERROR)
            .content_scale(ContentScale::FillBounds)
            .on_motion_finished(|_| {});
    }

    pub fn plain_pictures() {
        Image(1);
        Icon(2);
        Icon::asset(3);
    }

    pub fn canvas() {
        Canvas(commands())
            .modifier(Modifier.padding(1.0))
            .view_box(10.0, 20.0)
            .fractional()
            .on_motion_finished(|_| {});
        Canvas(commands()).view_box(10.0, 20.0);
    }

    pub fn plain_canvas() {
        Canvas(commands());
    }

    pub fn pickers() {
        DatePicker(19_000)
            .on_change(|_| {})
            .modifier(Modifier.padding(1.0))
            .min(18_000)
            .max(20_000)
            .enabled(false)
            .on_motion_finished(|_| {});
        TimePicker(600)
            .on_change(|_| {})
            .modifier(Modifier.padding(2.0))
            .min(60)
            .max(1_200)
            .enabled(false)
            .on_motion_finished(|_| {});
    }

    pub fn plain_pickers() {
        DatePicker(19_000).on_change(|_| {});
        TimePicker(600).on_change(|_| {});
    }

    pub fn dropdowns() {
        Dropdown(1)
            .on_change(|_| {})
            .modifier(Modifier.padding(1.0))
            .enabled(false)
            .closed(true)
            .bare()
            .on_motion_finished(|_| {})
            .content(|| {
                MenuItem("One").on_click(|| {});
                MenuItem("Two").on_click(|| {});
            });
        Select(0)
            .on_change(|_| {})
            .modifier(Modifier.padding(2.0))
            .enabled(false)
            .closed(false)
            .bare()
            .on_motion_finished(|_| {})
            .content(|| {
                MenuItem("Three").on_click(|| {});
            });
    }

    pub fn plain_dropdowns() {
        Dropdown(1).on_change(|_| {}).content(|| {});
        Select(0).on_change(|_| {}).content(|| {});
    }

    pub fn menu_item() {
        MenuItem("Delete")
            .on_click(|| {})
            .modifier(Modifier.padding(1.0))
            .icon(IconRole::Close)
            .selected(true)
            .enabled(false)
            .color(ERROR)
            .on_motion_finished(|_| {});
    }

    pub fn plain_menu_item() {
        MenuItem("Delete").on_click(|| {});
    }

    pub fn chip() {
        Chip("Rust")
            .on_click(|| {})
            .modifier(Modifier.padding(1.0))
            .icon(IconRole::Check)
            .selected(true)
            .enabled(false)
            .max_lines(2)
            .overflow(TextOverflow::Ellipsis)
            .on_motion_finished(|_| {});
    }

    pub fn plain_chip() {
        Chip("Rust").on_click(|| {});
    }

    pub fn floating_action() {
        FloatingAction("New")
            .on_click(|| {})
            .modifier(Modifier.padding(1.0))
            .icon(IconRole::Add)
            .on_motion_finished(|_| {});
    }

    pub fn plain_floating_action() {
        FloatingAction("New").on_click(|| {});
    }

    pub fn badge() {
        Badge()
            .modifier(Modifier.padding(1.0))
            .value(3)
            .text("new")
            .color(ColorRole::Primary)
            .on_motion_finished(|_| {})
            .content(|| {
                Icon(2);
            });
    }

    pub fn plain_badge() {
        Badge();
    }
}

/// The same screens with the call macros.
mod macros {
    use super::{ERROR, OUTLINE, PRIMARY, commands};
    use compose_rust::MotionFinished;
    use compose_rust::ui::next::*;

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
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
    }

    pub fn plain_menu_item() {
        MenuItem!("Delete", || {});
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

#[test]
fn fr39_function_form_button_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::button), batch(builders::button));
    assert_eq!(batch(macros::plain_button), batch(builders::plain_button));
}

#[test]
fn fr39_function_form_toggles_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::toggles), batch(builders::toggles));
    assert_eq!(batch(macros::plain_toggles), batch(builders::plain_toggles));
}

#[test]
fn fr39_function_form_slider_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::slider), batch(builders::slider));
    assert_eq!(batch(macros::plain_slider), batch(builders::plain_slider));
}

#[test]
fn fr39_function_form_progress_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::progress), batch(builders::progress));
    assert_eq!(
        batch(macros::plain_progress),
        batch(builders::plain_progress)
    );
}

#[test]
fn fr39_function_form_dividers_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::dividers), batch(builders::dividers));
    assert_eq!(
        batch(macros::plain_dividers),
        batch(builders::plain_dividers)
    );
}

#[test]
fn fr39_function_form_pictures_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::pictures), batch(builders::pictures));
    assert_eq!(
        batch(macros::plain_pictures),
        batch(builders::plain_pictures)
    );
}

#[test]
fn fr39_function_form_canvas_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::canvas), batch(builders::canvas));
    assert_eq!(batch(macros::plain_canvas), batch(builders::plain_canvas));
}

#[test]
fn fr39_function_form_pickers_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::pickers), batch(builders::pickers));
    assert_eq!(batch(macros::plain_pickers), batch(builders::plain_pickers));
}

#[test]
fn fr39_function_form_dropdowns_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::dropdowns), batch(builders::dropdowns));
    assert_eq!(
        batch(macros::plain_dropdowns),
        batch(builders::plain_dropdowns)
    );
}

#[test]
fn fr39_function_form_menu_item_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::menu_item), batch(builders::menu_item));
    assert_eq!(
        batch(macros::plain_menu_item),
        batch(builders::plain_menu_item)
    );
}

#[test]
fn fr39_function_form_chip_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::chip), batch(builders::chip));
    assert_eq!(batch(macros::plain_chip), batch(builders::plain_chip));
}

#[test]
fn fr39_function_form_floating_action_writes_the_bytes_the_builders_write() {
    assert_eq!(
        batch(macros::floating_action),
        batch(builders::floating_action)
    );
    assert_eq!(
        batch(macros::plain_floating_action),
        batch(builders::plain_floating_action)
    );
}

#[test]
fn fr39_function_form_badge_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::badge), batch(builders::badge));
    assert_eq!(batch(macros::plain_badge), batch(builders::plain_badge));
}

/// A background the modifier already has survives a separator without a colour of its own.
#[test]
fn fr39_function_form_separator_keeps_the_modifier_background() {
    use compose_rust::ui::next::*;
    assert_eq!(
        batch(|| {
            Separator!(None, modifier = Modifier.background(OUTLINE));
        }),
        batch(|| {
            compose_rust::ui::Divider().modifier(Modifier.background(OUTLINE));
        })
    );
}
