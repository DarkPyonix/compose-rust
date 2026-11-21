//! The overlay and navigation widgets as call macros: each one composes with every
//! parameter set and with only the required ones, and the macro with every parameter set
//! is the function call with the same arguments.

use compose_rust::ComposeHost;

/// The first batch `content` composes, as bytes.
fn batch(content: impl Fn() + Send + Sync + 'static) -> Vec<u8> {
    let mut host = ComposeHost::with_content(content);
    host.rebuild().expect("the first batch encodes").to_vec()
}

/// A child for the slots and the content, written the same way in both forms so the
/// comparison is about the widget around it.
fn child(text: &str) {
    use compose_rust::ui::Text;
    Text!(text);
}

/// The screens with the call macros.
mod macros {
    use super::child;
    use compose_rust::ui::*;
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
            count = 4,
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

/// The screens of `macros` with every parameter set, in the function form: each argument in
/// declaration order.
mod functions {
    use super::child;
    use compose_rust::ui::*;
    use compose_rust::{FileDrop, MotionFinished, NativeHandle};

    pub fn dialog() {
        Dialog(
            true,
            || {},
            Modifier.padding(8.0),
            Paint::Role(ColorRole::Primary),
            Some(|_: MotionFinished| {}),
            || {
                child("body");
            },
        );
    }

    pub fn sheet() {
        Sheet(
            true,
            || {},
            Modifier.padding(8.0),
            Paint::Role(ColorRole::Secondary),
            0.75,
            SheetPresentation::Centered,
            480,
            false,
            Some(|_: MotionFinished| {}),
            || {
                child("body");
            },
        );
    }

    pub fn menu() {
        Menu(
            true,
            || {},
            Modifier.padding(8.0),
            Paint::Role(ColorRole::Primary),
            Some(|| child("anchor")),
            Some(|_: MotionFinished| {}),
            || {
                child("first");
                child("second");
            },
        );
    }

    pub fn popover() {
        Popover(
            true,
            || {},
            Modifier.padding(8.0),
            PopoverPlacement::Top,
            PopoverAlign::End,
            6.0,
            true,
            Paint::Role(ColorRole::Secondary),
            Some(|| child("anchor")),
            Some(|_: MotionFinished| {}),
            || {
                child("body");
            },
        );
    }

    pub fn tooltip() {
        Tooltip(
            "Saves the file",
            Modifier.padding(8.0),
            Some(|_: MotionFinished| {}),
            || {
                child("Save");
            },
        );
    }

    pub fn native_view() {
        NativeView(
            NativeHandle(42),
            Modifier.padding(8.0),
            false,
            Some(|_: bool| {}),
        );
    }

    pub fn file_drop_target() {
        FileDropTarget(
            |_: FileDrop| {},
            Modifier.padding(8.0),
            Alignment::Center,
            Some(|| {}),
            Some(|_: MotionFinished| {}),
            || {
                child("Drop here");
            },
        );
    }

    pub fn tabs() {
        Tabs(
            1,
            Modifier.padding(8.0),
            Paint::Role(ColorRole::Primary),
            Some(|_: MotionFinished| {}),
            || {
                child("One");
                child("Two");
            },
        );
    }

    pub fn top_app_bar() {
        TopAppBar(
            Modifier.padding(8.0),
            "Inbox",
            true,
            false,
            Some(|_: MotionFinished| {}),
            || {
                child("action");
            },
        );
    }

    pub fn scaffold() {
        Scaffold(
            Modifier.padding(8.0),
            Some(|| child("top")),
            Some(|| child("bottom")),
            Some(|| child("action")),
            Some(|_: MotionFinished| {}),
            || {
                child("page");
            },
        );
    }

    pub fn navigation() {
        Navigation(
            1,
            Modifier.padding(8.0),
            CompactNavigation::Sidebar,
            Some(|| child("head")),
            Some(|| child("foot")),
            Some(|_: MotionFinished| {}),
            || {
                NavigationItem!("Home", || {});
                NavigationItem!("Settings", || {}, icon = IconRole::Settings);
            },
        );
    }

    pub fn navigation_item() {
        NavigationItem(
            "Home",
            || {},
            Modifier.padding(8.0),
            IconRole::Settings,
            Paint::Role(ColorRole::Primary),
            false,
            "Main",
            4,
            Some(|_: MotionFinished| {}),
        );
    }

    pub fn split_pane() {
        SplitPane(
            |_| {},
            Modifier.padding(8.0),
            240.0,
            120.0,
            400.0,
            true,
            600.0,
            1,
            "Sidebar",
            Some(|| {}),
            Some(|_: MotionFinished| {}),
            || {
                child("side");
                child("body");
            },
        );
    }
}

#[test]
fn fr39_function_form_dialog_composes_with_every_parameter() {
    assert!(!batch(macros::dialog).is_empty());
    assert!(!batch(macros::plain_dialog).is_empty());
}

#[test]
fn fr39_function_form_sheet_composes_with_every_parameter() {
    assert!(!batch(macros::sheet).is_empty());
    assert!(!batch(macros::plain_sheet).is_empty());
}

#[test]
fn fr39_function_form_menu_composes_with_every_parameter() {
    assert!(!batch(macros::menu).is_empty());
    assert!(!batch(macros::plain_menu).is_empty());
}

#[test]
fn fr39_function_form_popover_composes_with_every_parameter() {
    assert!(!batch(macros::popover).is_empty());
    assert!(!batch(macros::plain_popover).is_empty());
}

#[test]
fn fr39_function_form_tooltip_composes_with_every_parameter() {
    assert!(!batch(macros::tooltip).is_empty());
    assert!(!batch(macros::plain_tooltip).is_empty());
}

#[test]
fn fr39_function_form_native_view_composes_with_every_parameter() {
    assert!(!batch(macros::native_view).is_empty());
    assert!(!batch(macros::plain_native_view).is_empty());
}

#[test]
fn fr39_function_form_file_drop_target_composes_with_every_parameter() {
    assert!(!batch(macros::file_drop_target).is_empty());
    assert!(!batch(macros::plain_file_drop_target).is_empty());
}

#[test]
fn fr39_function_form_tabs_composes_with_every_parameter() {
    assert!(!batch(macros::tabs).is_empty());
    assert!(!batch(macros::plain_tabs).is_empty());
}

#[test]
fn fr39_function_form_top_app_bar_composes_with_every_parameter() {
    assert!(!batch(macros::top_app_bar).is_empty());
    assert!(!batch(macros::plain_top_app_bar).is_empty());
}

#[test]
fn fr39_function_form_scaffold_composes_with_every_parameter() {
    assert!(!batch(macros::scaffold).is_empty());
    assert!(!batch(macros::plain_scaffold).is_empty());
}

#[test]
fn fr39_function_form_navigation_composes_with_every_parameter() {
    assert!(!batch(macros::navigation).is_empty());
    assert!(!batch(macros::plain_navigation).is_empty());
}

#[test]
fn fr39_function_form_navigation_item_composes_with_every_parameter() {
    assert!(!batch(macros::navigation_item).is_empty());
    assert!(!batch(macros::plain_navigation_item).is_empty());
}

#[test]
fn fr39_function_form_split_pane_composes_with_every_parameter() {
    assert!(!batch(macros::split_pane).is_empty());
    assert!(!batch(macros::plain_split_pane).is_empty());
}

#[test]
fn fr39_call_macro_dialog_is_the_function_call() {
    assert_eq!(batch(functions::dialog), batch(macros::dialog));
}

#[test]
fn fr39_call_macro_sheet_is_the_function_call() {
    assert_eq!(batch(functions::sheet), batch(macros::sheet));
}

#[test]
fn fr39_call_macro_menu_is_the_function_call() {
    assert_eq!(batch(functions::menu), batch(macros::menu));
}

#[test]
fn fr39_call_macro_popover_is_the_function_call() {
    assert_eq!(batch(functions::popover), batch(macros::popover));
}

#[test]
fn fr39_call_macro_tooltip_is_the_function_call() {
    assert_eq!(batch(functions::tooltip), batch(macros::tooltip));
}

#[test]
fn fr39_call_macro_native_view_is_the_function_call() {
    assert_eq!(batch(functions::native_view), batch(macros::native_view));
}

#[test]
fn fr39_call_macro_file_drop_target_is_the_function_call() {
    assert_eq!(
        batch(functions::file_drop_target),
        batch(macros::file_drop_target)
    );
}

#[test]
fn fr39_call_macro_tabs_is_the_function_call() {
    assert_eq!(batch(functions::tabs), batch(macros::tabs));
}

#[test]
fn fr39_call_macro_top_app_bar_is_the_function_call() {
    assert_eq!(batch(functions::top_app_bar), batch(macros::top_app_bar));
}

#[test]
fn fr39_call_macro_scaffold_is_the_function_call() {
    assert_eq!(batch(functions::scaffold), batch(macros::scaffold));
}

#[test]
fn fr39_call_macro_navigation_is_the_function_call() {
    assert_eq!(batch(functions::navigation), batch(macros::navigation));
}

#[test]
fn fr39_call_macro_navigation_item_is_the_function_call() {
    assert_eq!(
        batch(functions::navigation_item),
        batch(macros::navigation_item)
    );
}

#[test]
fn fr39_call_macro_split_pane_is_the_function_call() {
    assert_eq!(batch(functions::split_pane), batch(macros::split_pane));
}
