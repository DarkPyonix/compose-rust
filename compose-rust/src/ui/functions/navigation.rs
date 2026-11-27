//! Frames and navigation.

use super::{set, set_some};
use crate::MotionFinished;
use crate::schema::{CompactNavigation, IconRole, Paint};
use crate::ui::Modifier;
use compose_rust_macros::Composable;

/// A row of tabs; each child is one tab, and a tap is that child's own click. Compose's
/// `TabRow`.
///
/// `selected_index` is the tab that shows as selected, and `color` the colour of the mark
/// that shows it. `on_motion_finished` runs once when the node's size motion comes to
/// rest, with the size it settled at in dp; the node needs `Modifier::motion` for its size
/// to move.
#[Composable(widget)]
pub fn Tabs(
    selected_index: usize,
    modifier: Modifier,
    #[default(None::<::compose_rust::ui::Paint>)] color: impl Into<Option<Paint>>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::Tabs(selected_index).modifier(modifier);
    set!(scope; color);
    set_some!(scope; on_motion_finished);
    scope.content(content);
}

/// The bar across the top of a screen; its children are its content. Compose's
/// `TopAppBar`, with the title a property so the Renderer can find it.
///
/// `title` is the bar's title, empty when it is unset. `hairline` says whether to draw the
/// design system's hairline separator under the bar; when it is unset the active design
/// system decides (Cupertino, Fluent and GNOME draw one; Material 3 and Deepin do not; a
/// custom border on the bar also suppresses it). `separator` is another name for
/// `hairline`; when both are given, `separator` wins.
///
/// `on_motion_finished` runs once when the node's size motion comes to rest, with the size
/// it settled at in dp; the node needs `Modifier::motion` for its size to move.
#[Composable(widget)]
pub fn TopAppBar<'a>(
    modifier: Modifier,
    #[default(None::<&str>)] title: impl Into<Option<&'a str>>,
    #[default(None::<bool>)] hairline: impl Into<Option<bool>>,
    #[default(None::<bool>)] separator: impl Into<Option<bool>>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::TopAppBar().modifier(modifier);
    set!(scope; title, hairline, separator);
    set_some!(scope; on_motion_finished);
    scope.content(content);
}

/// The screen's frame: which slots it has filled, and the page. Compose's `Scaffold`.
/// What each slot becomes on this platform at this width is the Renderer's decision.
///
/// The Modifier carries the two things a frame takes: `background` and `material`.
/// `top_bar`, `bottom_bar` and `floating_action` fill the slots of those names, and the
/// content is the page.
///
/// `on_motion_finished` runs once when the node's size motion comes to rest, with the size
/// it settled at in dp; the node needs `Modifier::motion` for its size to move.
#[Composable(widget)]
pub fn Scaffold<'a>(
    modifier: Modifier,
    #[default(None::<fn()>)] top_bar: Option<impl FnOnce() + 'a>,
    #[default(None::<fn()>)] bottom_bar: Option<impl FnOnce() + 'a>,
    #[default(None::<fn()>)] floating_action: Option<impl FnOnce() + 'a>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::Scaffold().modifier(modifier);
    set_some!(scope; top_bar, bottom_bar, floating_action, on_motion_finished);
    scope.content(content);
}

/// A set of destinations and the screen they lead to. The children that are
/// `NavigationItem`s are the destinations; whether they are a bar, a rail or a drawer is
/// the Renderer's choice. Compose's `NavigationSuiteScaffold`.
///
/// `selected_index` is the destination that shows as selected.
///
/// `compact` is what the destinations become in a compact window. With `Default` the
/// design system answers, which is a bottom bar on most of them. `Sidebar` puts the
/// sidebar away behind a toggle on every design system, and `None` leaves the destinations
/// off a compact window so the screen can reach them its own way. `Floating` draws them as
/// a pill-shaped bar floating over the bottom of the page, clear of the window's edges and
/// of the bottom safe area; the page runs on under it, and a scroll container in the page
/// is given room at its end so its last content is not behind the bar. Medium and Expanded
/// windows keep the design system's rail and drawer whatever this says, and the screen
/// still never asks how wide the window is.
///
/// `head` is what sits above the destinations in a strip, and `foot` what sits below them.
///
/// `on_motion_finished` runs once when the node's size motion comes to rest, with the size
/// it settled at in dp; the node needs `Modifier::motion` for its size to move.
#[Composable(widget)]
pub fn Navigation<'a>(
    selected_index: usize,
    modifier: Modifier,
    #[default(::compose_rust::ui::CompactNavigation::Default)] compact: CompactNavigation,
    #[default(None::<fn()>)] head: Option<impl FnOnce() + 'a>,
    #[default(None::<fn()>)] foot: Option<impl FnOnce() + 'a>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::Navigation(selected_index)
        .modifier(modifier)
        .compact(compact);
    set_some!(scope; head, foot, on_motion_finished);
    scope.content(content);
}

/// One destination inside a `Navigation`. Compose's `NavigationSuiteItem`.
///
/// `text` is the destination's label and `on_click` runs when the user picks it. `icon`
/// and `color` are its icon and the colour it is drawn in, the design system's when they
/// are unset. `enabled` set to false shows it and keeps it from being picked. `section`
/// is the named group of the strip this destination belongs to. `count` is how many
/// things the destination holds, drawn where the design system puts a destination's
/// count: at the end of a sidebar row, or as a badge on the icon of a bar or a rail. Left
/// out, the destination shows no count; 0 is a count of nothing and is shown.
///
/// `on_motion_finished` runs once when the node's size motion comes to rest, with the size
/// it settled at in dp; the node needs `Modifier::motion` for its size to move.
#[Composable(widget)]
pub fn NavigationItem<'a>(
    text: impl Into<String>,
    on_click: impl FnMut() + 'static,
    modifier: Modifier,
    #[default(None::<::compose_rust::ui::IconRole>)] icon: impl Into<Option<IconRole>>,
    #[default(None::<::compose_rust::ui::Paint>)] color: impl Into<Option<Paint>>,
    #[default(true)] enabled: bool,
    #[default(None::<&str>)] section: impl Into<Option<&'a str>>,
    #[default(None::<u32>)] count: impl Into<Option<u32>>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
) {
    let mut scope = crate::ui::widgets::NavigationItem(text)
        .modifier(modifier)
        .on_click(on_click);
    set!(scope; icon, color);
    scope = scope.enabled(enabled);
    set!(scope; section, count);
    set_some!(scope; on_motion_finished);
    drop(scope);
}

/// A side pane and a body with a divider the user drags. Exactly two children, side pane
/// first. The drag is reported once, when it ends.
///
/// `on_change` receives the side pane's width in dp when a drag ends. `value` is the side
/// pane's width in dp, or 0 to open it folded; `min` and `max` bound the width a drag
/// leaves it at. `collapsible` lets the side pane fold away to nothing.
///
/// `collapse_below` is the split pane's own width in dp below which it shows one pane at a
/// time, the one `selected_index` names (0 the side pane, 1 the body). At or above it the
/// panes are never stacked, and the design system still decides between two columns and a
/// side pane laid over the body. Unset, the line is the size class's: stacked below 600 dp,
/// and 0 is the same as unset. The width is measured on the Renderer, so crossing the line
/// sends the Host nothing. A negative or non-finite width is reported as a protocol error
/// and the split pane is drawn as if it had none.
///
/// `label` is what a screen reader calls the divider. `on_dismiss` runs when the user goes
/// back from the body to the side pane while the panes are stacked.
///
/// `on_motion_finished` runs once when the node's size motion comes to rest, with the size
/// it settled at in dp; the node needs `Modifier::motion` for its size to move.
#[Composable(widget)]
pub fn SplitPane<'a>(
    on_change: impl FnMut(f32) + 'static,
    modifier: Modifier,
    #[default(None::<f32>)] value: impl Into<Option<f32>>,
    #[default(None::<f32>)] min: impl Into<Option<f32>>,
    #[default(None::<f32>)] max: impl Into<Option<f32>>,
    #[default(false)] collapsible: bool,
    #[default(None::<f32>)] collapse_below: impl Into<Option<f32>>,
    #[default(0)] selected_index: usize,
    #[default(None::<&str>)] label: impl Into<Option<&'a str>>,
    #[default(None::<fn()>)] on_dismiss: Option<impl FnMut() + 'static>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::SplitPane()
        .modifier(modifier)
        .on_change(on_change);
    set!(scope; value, min, max);
    scope = scope.collapsible(collapsible);
    set!(scope; collapse_below);
    scope = scope.selected_index(selected_index);
    set!(scope; label);
    set_some!(scope; on_dismiss, on_motion_finished);
    scope.content(content);
}
