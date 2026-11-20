//! The overlay and navigation widgets as call macros: each one writes the bytes its builder
//! writes, with every parameter set and with only the required ones.

use compose_rust::ComposeHost;

/// The first batch `content` composes, as bytes.
fn batch(content: impl Fn() + Send + Sync + 'static) -> Vec<u8> {
    let mut host = ComposeHost::with_content(content);
    host.rebuild().expect("the first batch encodes").to_vec()
}

/// A child for the slots and the content, written the same way in both forms so the
/// comparison is about the widget around it.
fn child(text: &str) {
    compose_rust::ui::Text(text);
}

/// The screens written with the builders, for as long as the builders are public.
mod builders {
    use super::child;
    use compose_rust::NativeHandle;
    use compose_rust::ui::*;

    pub fn dialog() {
        Dialog(true)
            .on_dismiss(|| {})
            .modifier(Modifier.padding(8.0))
            .scrim(Paint::Role(ColorRole::Primary))
            .on_motion_finished(|_| {})
            .content(|| child("body"));
    }

    pub fn plain_dialog() {
        Dialog(false).on_dismiss(|| {});
    }

    pub fn sheet() {
        Sheet(true)
            .on_dismiss(|| {})
            .modifier(Modifier.padding(8.0))
            .scrim(Paint::Role(ColorRole::Secondary))
            .max_height_fraction(0.75)
            .presentation(SheetPresentation::Centered)
            .max_width(480)
            .handle(false)
            .on_motion_finished(|_| {})
            .content(|| child("body"));
    }

    pub fn plain_sheet() {
        Sheet(false).on_dismiss(|| {});
    }

    pub fn menu() {
        Menu(true)
            .on_dismiss(|| {})
            .modifier(Modifier.padding(8.0))
            .scrim(Paint::Role(ColorRole::Primary))
            .anchor(|| child("anchor"))
            .on_motion_finished(|_| {})
            .content(|| {
                child("first");
                child("second");
            });
    }

    pub fn plain_menu() {
        Menu(false).on_dismiss(|| {});
    }

    pub fn popover() {
        Popover(true)
            .on_dismiss(|| {})
            .modifier(Modifier.padding(8.0))
            .placement(PopoverPlacement::Top)
            .align(PopoverAlign::End)
            .offset(6.0)
            .scrim(true)
            .scrim_color(Paint::Role(ColorRole::Secondary))
            .anchor(|| child("anchor"))
            .on_motion_finished(|_| {})
            .content(|| child("body"));
    }

    pub fn plain_popover() {
        Popover(false).on_dismiss(|| {});
    }

    pub fn tooltip() {
        Tooltip("Saves the file")
            .modifier(Modifier.padding(8.0))
            .on_motion_finished(|_| {})
            .content(|| child("Save"));
    }

    pub fn plain_tooltip() {
        Tooltip("Saves the file");
    }

    pub fn native_view() {
        NativeView(NativeHandle(42))
            .modifier(Modifier.padding(8.0))
            .visible(false)
            .on_focus_change(|_| {});
    }

    pub fn plain_native_view() {
        NativeView(NativeHandle(42));
    }

    pub fn file_drop_target() {
        FileDropTarget()
            .on_files_dropped(|_| {})
            .modifier(Modifier.padding(8.0))
            .alignment(Alignment::Center)
            .on_files_entered(|| {})
            .on_motion_finished(|_| {})
            .content(|| child("Drop here"));
    }

    pub fn plain_file_drop_target() {
        FileDropTarget().on_files_dropped(|_| {});
    }

    pub fn tabs() {
        Tabs(1)
            .modifier(Modifier.padding(8.0))
            .color(Paint::Role(ColorRole::Primary))
            .on_motion_finished(|_| {})
            .content(|| {
                child("One");
                child("Two");
            });
    }

    pub fn plain_tabs() {
        Tabs(0);
    }

    pub fn top_app_bar() {
        TopAppBar()
            .modifier(Modifier.padding(8.0))
            .title("Inbox")
            .hairline(true)
            .separator(false)
            .on_motion_finished(|_| {})
            .content(|| child("action"));
    }

    pub fn plain_top_app_bar() {
        TopAppBar();
    }

    pub fn scaffold() {
        Scaffold()
            .modifier(Modifier.padding(8.0))
            .top_bar(|| child("top"))
            .bottom_bar(|| child("bottom"))
            .floating_action(|| child("action"))
            .on_motion_finished(|_| {})
            .content(|| child("page"));
    }

    pub fn plain_scaffold() {
        Scaffold();
    }

    pub fn navigation() {
        Navigation(1)
            .modifier(Modifier.padding(8.0))
            .compact(CompactNavigation::Sidebar)
            .head(|| child("head"))
            .foot(|| child("foot"))
            .on_motion_finished(|_| {})
            .content(|| {
                NavigationItem("Home").on_click(|| {});
                NavigationItem("Settings")
                    .on_click(|| {})
                    .icon(IconRole::Settings);
            });
    }

    pub fn plain_navigation() {
        Navigation(0);
    }

    pub fn navigation_item() {
        NavigationItem("Home")
            .on_click(|| {})
            .modifier(Modifier.padding(8.0))
            .icon(IconRole::Settings)
            .color(Paint::Role(ColorRole::Primary))
            .enabled(false)
            .section("Main")
            .on_motion_finished(|_| {});
    }

    pub fn plain_navigation_item() {
        NavigationItem("Home").on_click(|| {});
    }

    pub fn split_pane() {
        SplitPane()
            .on_change(|_| {})
            .modifier(Modifier.padding(8.0))
            .value(240.0)
            .min(120.0)
            .max(400.0)
            .collapsible(true)
            .collapse_below(600.0)
            .selected_index(1)
            .label("Sidebar")
            .on_dismiss(|| {})
            .on_motion_finished(|_| {})
            .content(|| {
                child("side");
                child("body");
            });
    }

    pub fn plain_split_pane() {
        SplitPane().on_change(|_| {});
    }
}

/// The same screens with the call macros.
mod macros {
    use super::child;
    use compose_rust::ui::next::*;
    use compose_rust::{FileDrop, MotionFinished, NativeHandle};

    pub fn dialog() {
        Dialog!(
            true,
            || {},
            modifier = Modifier.padding(8.0),
            scrim = Paint::Role(ColorRole::Primary),
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                child("body");
            }
        );
    }

    pub fn plain_dialog() {
        Dialog!(false, || {});
    }

    pub fn sheet() {
        Sheet!(
            true,
            || {},
            modifier = Modifier.padding(8.0),
            scrim = Paint::Role(ColorRole::Secondary),
            max_height_fraction = 0.75,
            presentation = SheetPresentation::Centered,
            max_width = 480,
            handle = false,
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                child("body");
            }
        );
    }

    pub fn plain_sheet() {
        Sheet!(false, || {});
    }

    pub fn menu() {
        Menu!(
            true,
            || {},
            modifier = Modifier.padding(8.0),
            scrim = Paint::Role(ColorRole::Primary),
            anchor = Some(|| child("anchor")),
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                child("first");
                child("second");
            }
        );
    }

    pub fn plain_menu() {
        Menu!(false, || {});
    }

    pub fn popover() {
        Popover!(
            true,
            || {},
            modifier = Modifier.padding(8.0),
            placement = PopoverPlacement::Top,
            align = PopoverAlign::End,
            offset = 6.0,
            scrim = true,
            scrim_color = Paint::Role(ColorRole::Secondary),
            anchor = Some(|| child("anchor")),
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                child("body");
            }
        );
    }

    pub fn plain_popover() {
        Popover!(false, || {});
    }

    pub fn tooltip() {
        Tooltip!(
            "Saves the file",
            modifier = Modifier.padding(8.0),
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                child("Save");
            }
        );
    }

    pub fn plain_tooltip() {
        Tooltip!("Saves the file");
    }

    pub fn native_view() {
        NativeView!(
            NativeHandle(42),
            modifier = Modifier.padding(8.0),
            visible = false,
            on_focus_change = Some(|_: bool| {}),
        );
    }

    pub fn plain_native_view() {
        NativeView!(NativeHandle(42));
    }

    pub fn file_drop_target() {
        FileDropTarget!(
            |_: FileDrop| {},
            modifier = Modifier.padding(8.0),
            alignment = Alignment::Center,
            on_files_entered = Some(|| {}),
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                child("Drop here");
            }
        );
    }

    pub fn plain_file_drop_target() {
        FileDropTarget!(|_| {});
    }

    pub fn tabs() {
        Tabs!(
            1,
            modifier = Modifier.padding(8.0),
            color = Paint::Role(ColorRole::Primary),
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                child("One");
                child("Two");
            }
        );
    }

    pub fn plain_tabs() {
        Tabs!(0);
    }

    pub fn top_app_bar() {
        TopAppBar!(
            modifier = Modifier.padding(8.0),
            title = "Inbox",
            hairline = true,
            separator = false,
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                child("action");
            }
        );
    }

    pub fn plain_top_app_bar() {
        TopAppBar!();
    }

    pub fn scaffold() {
        Scaffold!(
            modifier = Modifier.padding(8.0),
            top_bar = Some(|| child("top")),
            bottom_bar = Some(|| child("bottom")),
            floating_action = Some(|| child("action")),
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                child("page");
            }
        );
    }

    pub fn plain_scaffold() {
        Scaffold!();
    }

    pub fn navigation() {
        Navigation!(
            1,
            modifier = Modifier.padding(8.0),
            compact = CompactNavigation::Sidebar,
            head = Some(|| child("head")),
            foot = Some(|| child("foot")),
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                NavigationItem!("Home", || {});
                NavigationItem!("Settings", || {}, icon = IconRole::Settings);
            }
        );
    }

    pub fn plain_navigation() {
        Navigation!(0);
    }

    pub fn navigation_item() {
        NavigationItem!(
            "Home",
            || {},
            modifier = Modifier.padding(8.0),
            icon = IconRole::Settings,
            color = Paint::Role(ColorRole::Primary),
            enabled = false,
            section = "Main",
            on_motion_finished = Some(|_: MotionFinished| {}),
        );
    }

    pub fn plain_navigation_item() {
        NavigationItem!("Home", || {});
    }

    pub fn split_pane() {
        SplitPane!(
            |_| {},
            modifier = Modifier.padding(8.0),
            value = 240.0,
            min = 120.0,
            max = 400.0,
            collapsible = true,
            collapse_below = 600.0,
            selected_index = 1,
            label = "Sidebar",
            on_dismiss = Some(|| {}),
            on_motion_finished = Some(|_: MotionFinished| {}),
            {
                child("side");
                child("body");
            }
        );
    }

    pub fn plain_split_pane() {
        SplitPane!(|_| {});
    }
}

#[test]
fn fr39_function_form_dialog_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::dialog), batch(builders::dialog));
    assert_eq!(batch(macros::plain_dialog), batch(builders::plain_dialog));
}

#[test]
fn fr39_function_form_sheet_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::sheet), batch(builders::sheet));
    assert_eq!(batch(macros::plain_sheet), batch(builders::plain_sheet));
}

#[test]
fn fr39_function_form_menu_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::menu), batch(builders::menu));
    assert_eq!(batch(macros::plain_menu), batch(builders::plain_menu));
}

#[test]
fn fr39_function_form_popover_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::popover), batch(builders::popover));
    assert_eq!(batch(macros::plain_popover), batch(builders::plain_popover));
}

#[test]
fn fr39_function_form_tooltip_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::tooltip), batch(builders::tooltip));
    assert_eq!(batch(macros::plain_tooltip), batch(builders::plain_tooltip));
}

#[test]
fn fr39_function_form_native_view_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::native_view), batch(builders::native_view));
    assert_eq!(
        batch(macros::plain_native_view),
        batch(builders::plain_native_view)
    );
}

#[test]
fn fr39_function_form_file_drop_target_writes_the_bytes_the_builders_write() {
    assert_eq!(
        batch(macros::file_drop_target),
        batch(builders::file_drop_target)
    );
    assert_eq!(
        batch(macros::plain_file_drop_target),
        batch(builders::plain_file_drop_target)
    );
}

#[test]
fn fr39_function_form_tabs_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::tabs), batch(builders::tabs));
    assert_eq!(batch(macros::plain_tabs), batch(builders::plain_tabs));
}

#[test]
fn fr39_function_form_top_app_bar_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::top_app_bar), batch(builders::top_app_bar));
    assert_eq!(
        batch(macros::plain_top_app_bar),
        batch(builders::plain_top_app_bar)
    );
}

#[test]
fn fr39_function_form_scaffold_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::scaffold), batch(builders::scaffold));
    assert_eq!(
        batch(macros::plain_scaffold),
        batch(builders::plain_scaffold)
    );
}

#[test]
fn fr39_function_form_navigation_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::navigation), batch(builders::navigation));
    assert_eq!(
        batch(macros::plain_navigation),
        batch(builders::plain_navigation)
    );
}

#[test]
fn fr39_function_form_navigation_item_writes_the_bytes_the_builders_write() {
    assert_eq!(
        batch(macros::navigation_item),
        batch(builders::navigation_item)
    );
    assert_eq!(
        batch(macros::plain_navigation_item),
        batch(builders::plain_navigation_item)
    );
}

#[test]
fn fr39_function_form_split_pane_writes_the_bytes_the_builders_write() {
    assert_eq!(batch(macros::split_pane), batch(builders::split_pane));
    assert_eq!(
        batch(macros::plain_split_pane),
        batch(builders::plain_split_pane)
    );
}
