//! Windows.

use super::{set, set_some};
use crate::schema::{Chrome, Theme, TitleBar};
use crate::BackRequested;
use crate::windows::{DragFromPointer, WindowDragEvent, WindowFrame};
use compose_rust_macros::Composable;

/// A platform window, a child of the root, whose children are the window's content.
/// Compose Desktop's `Window`. The application has to be launched with
/// [`LaunchBuilder::windows`](crate::LaunchBuilder::windows).
///
/// `on_close_request` is called when the user asks to close the window. It does not close
/// it: the window closes when the application stops composing this `Window`, which is
/// where a dialog that asks about unsaved work goes.
///
/// ```ignore
/// Window!(move || open.set(false), title = "Notes", size = (900.0, 600.0), {
///     Text!("Hello");
/// });
/// ```
///
/// Only what is given is sent. `title` is what the window calls itself. `size` is the
/// size it opens at in dp, and a later change resizes it; `min_size` is the smallest the
/// user can make it. `report_size` asks to be told every time the width or height,
/// rounded to whole dp, changes; without it the size is reported only when its class or
/// the safe area changes. Turn it on only for a window that lays out by its exact size:
/// each whole-dp change while the user drags an edge costs one call into the Host.
///
/// `chrome` is who draws the caption, and `title_bar` which of the two title bars the
/// window has. `icon` is the id of a registered asset; without one the window wears the
/// application's. `position` is where the window sits, in screen dp from the top left of
/// the main screen. Changing it moves the window; the position the user chose is not
/// given back, and without it the platform places the window. `restore_key` is the
/// application's stable name for the window, which matches a window the system brings
/// back to the node that describes it. `zoom_level` is the window's own application zoom
/// level, kept inside its range. `drag_from_pointer` opens the window under a pointer
/// that is held down and follows it until it is let go; it is read when the window opens
/// and not again. `theme` is a theme for this window over the application's.
///
/// `on_focus_changed` hears the window become the key window or stop being it, `on_moved`
/// its outer frame move or change size, and `on_drag` the window being dragged out of a
/// tab and how that ended. `on_unavailable` means the platform could not open this
/// window: tell the user, or put the tab back. `on_closed` means the system closed the
/// window without asking: stop composing it. `on_back_requested` hears a request to go
/// back in this window that no node inside it consumed; calling `consume` on the event
/// keeps the platform from acting on it.
#[Composable(widget)]
pub fn Window<'a>(
    on_close_request: impl FnMut() + 'static,
    #[default(None::<&str>)] title: impl Into<Option<&'a str>>,
    #[default(None::<(f32, f32)>)] size: impl Into<Option<(f32, f32)>>,
    #[default(None::<(f32, f32)>)] min_size: impl Into<Option<(f32, f32)>>,
    #[default(None::<bool>)] resizable: impl Into<Option<bool>>,
    #[default(None::<bool>)] report_size: impl Into<Option<bool>>,
    #[default(None::<::compose_rust::schema::Chrome>)] chrome: impl Into<Option<Chrome>>,
    #[default(None::<::compose_rust::schema::TitleBar>)] title_bar: impl Into<Option<TitleBar>>,
    #[default(None::<u32>)] icon: impl Into<Option<u32>>,
    #[default(None::<(f32, f32)>)] position: impl Into<Option<(f32, f32)>>,
    #[default(None::<&str>)] restore_key: impl Into<Option<&'a str>>,
    #[default(None::<i8>)] zoom_level: impl Into<Option<i8>>,
    #[default(None::<::compose_rust::windows::DragFromPointer>)] drag_from_pointer: impl Into<
        Option<DragFromPointer>,
    >,
    #[default(None::<::compose_rust::schema::Theme>)] theme: impl Into<Option<Theme>>,
    #[default(None::<fn(bool)>)] on_focus_changed: Option<impl FnMut(bool) + 'static>,
    #[default(None::<fn(::compose_rust::windows::WindowFrame)>)] on_moved: Option<
        impl FnMut(WindowFrame) + 'static,
    >,
    #[default(None::<fn(::compose_rust::windows::WindowDragEvent)>)] on_drag: Option<
        impl FnMut(WindowDragEvent) + 'static,
    >,
    #[default(None::<fn()>)] on_unavailable: Option<impl FnMut() + 'static>,
    #[default(None::<fn()>)] on_closed: Option<impl FnMut() + 'static>,
    #[default(None::<fn(::compose_rust::BackRequested)>)] on_back_requested: Option<
        impl FnMut(BackRequested) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::Window(on_close_request);
    if let Some((width, height)) = size.into() {
        scope = scope.size(width, height);
    }
    if let Some((width, height)) = min_size.into() {
        scope = scope.min_size(width, height);
    }
    if let Some((x, y)) = position.into() {
        scope = scope.position(x, y);
    }
    set!(
        scope;
        title,
        resizable,
        report_size,
        chrome,
        title_bar,
        icon,
        restore_key,
        zoom_level,
        drag_from_pointer,
        theme,
    );
    set_some!(
        scope;
        on_focus_changed,
        on_moved,
        on_drag,
        on_unavailable,
        on_closed,
        on_back_requested,
    );
    scope.content(content);
}
