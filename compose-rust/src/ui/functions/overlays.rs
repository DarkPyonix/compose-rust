//! Surfaces that come and go, and the widgets that sit over or around other content.

use super::{set, set_some};
use crate::schema::{
    Alignment, NativeHandle, Paint, PopoverAlign, PopoverPlacement, SheetDetents, SheetPresentation,
};
use crate::ui::Modifier;
use crate::{FileDrop, MotionFinished};
use compose_rust_macros::Composable;

/// A modal. Compose's `Dialog`, kept in the tree and opened by `open`, so its enter and
/// exit belong to the Renderer.
///
/// `open` seeds the Renderer's own open state, and `on_dismiss` says once that the user
/// asked to close it. A dialog sizes itself to its content and is always centered.
///
/// `scrim` is the colour of the scrim behind it, instead of the design system's.
/// `on_motion_finished` runs once when the node's size motion comes to rest, with the size
/// it settled at in dp; the node needs `Modifier::motion` for its size to move.
#[Composable(widget)]
pub fn Dialog(
    open: bool,
    on_dismiss: impl FnMut() + 'static,
    modifier: Modifier,
    #[default(None::<::compose_rust::ui::Paint>)] scrim: impl Into<Option<Paint>>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::Dialog(open)
        .modifier(modifier)
        .on_dismiss(on_dismiss);
    set!(scope; scrim);
    set_some!(scope; on_motion_finished);
    scope.content(content);
}

/// A surface that slides in from the edge the Renderer chooses. Compose's
/// `ModalBottomSheet`, without a promise about which edge.
///
/// `open` seeds the Renderer's own open state, and `on_dismiss` says once that the user
/// asked to close it. `scrim` is the colour of the scrim behind it, instead of the design
/// system's.
///
/// `max_height_fraction` is the tallest the sheet grows, as a fraction of the window's
/// height, above 0 and at most 1. Below it the sheet is as tall as its content; taller
/// content stops the sheet at the fraction and scrolls inside it. A fraction above 1 is
/// taken as 1, and one that is not above 0 leaves the height to the design system.
///
/// `presentation` is the form the sheet takes: an edge drawer (`Edge`) or a centered modal
/// (`Centered`). `max_width` is the widest a centered sheet grows in dp; zero or unset
/// leaves it to the design system (560 dp), and an edge sheet does not take it. `handle`
/// says whether a bottom sheet draws its drag handle; without it the sheet still drags
/// down and closes the same way.
///
/// `detents` is the set of heights a bottom sheet rests at: `SheetDetents::MEDIUM |
/// SheetDetents::LARGE` opens at half the window and is pulled up to nearly all of it and
/// back. Which one it is at is the Renderer's state. `drag_to_dismiss = false` stops a pull
/// at the lowest resting height and sends no dismissal for it; Escape, the platform's back
/// and a press outside still call `on_dismiss`. A centered sheet and one that comes from
/// the side take neither.
///
/// `on_motion_finished` runs once when the node's size motion comes to rest, with the size
/// it settled at in dp; the node needs `Modifier::motion` for its size to move.
#[Composable(widget)]
pub fn Sheet(
    open: bool,
    on_dismiss: impl FnMut() + 'static,
    modifier: Modifier,
    #[default(None::<::compose_rust::ui::Paint>)] scrim: impl Into<Option<Paint>>,
    #[default(None::<f32>)] max_height_fraction: impl Into<Option<f32>>,
    #[default(::compose_rust::ui::SheetPresentation::Edge)] presentation: SheetPresentation,
    #[default(None::<u32>)] max_width: impl Into<Option<u32>>,
    #[default(true)] handle: bool,
    #[default(::compose_rust::ui::SheetDetents::NONE)] detents: SheetDetents,
    #[default(true)] drag_to_dismiss: bool,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::Sheet(open)
        .modifier(modifier)
        .on_dismiss(on_dismiss);
    set!(scope; scrim, max_height_fraction);
    scope = scope.presentation(presentation);
    set!(scope; max_width);
    scope = scope.handle(handle);
    scope = scope.detents(detents).drag_to_dismiss(drag_to_dismiss);
    set_some!(scope; on_motion_finished);
    scope.content(content);
}

/// A popup hanging off its anchor. Compose's `DropdownMenu`, with the anchor said rather
/// than positioned: the Renderer places the popup.
///
/// `expanded` says whether the popup is open, and `on_dismiss` runs when the user asks to
/// close it without choosing an entry. `scrim` is the colour of the scrim behind the popup,
/// instead of the design system's. `anchor` is the widget the menu hangs off; it is the
/// first child on the wire, and the content is the menu's entries.
///
/// The content holds entries and nothing else: `MenuItem`, `Submenu`, and `Separator`
/// between them. That is what every platform's own menu can show, and where the window
/// puts menus up as the operating system's own (Android's `PopupMenu`, iOS's `UIMenu`) the
/// menu is one, with the platform's keyboard, its reading by assistive technology and its
/// place at the screen's edge.
/// Choosing an entry runs that entry's `on_click`. Any other node in the content is a
/// protocol error, reported to the Host, and the menu does not open as a drawn one in its
/// place; a popup of arbitrary content is a `Popover`.
///
/// `on_motion_finished` runs once when the node's size motion comes to rest, with the size
/// it settled at in dp; the node needs `Modifier::motion` for its size to move.
#[Composable(widget)]
pub fn Menu<'a>(
    expanded: bool,
    on_dismiss: impl FnMut() + 'static,
    modifier: Modifier,
    #[default(None::<::compose_rust::ui::Paint>)] scrim: impl Into<Option<Paint>>,
    #[default(None::<fn()>)] anchor: Option<impl FnOnce() + 'a>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::Menu(expanded)
        .modifier(modifier)
        .on_dismiss(on_dismiss);
    set!(scope; scrim);
    set_some!(scope; anchor, on_motion_finished);
    scope.content(content);
}

/// A floating box of any content attached to its anchor.
///
/// The anchor is always drawn. The content is drawn only while `open` is true, and `open`
/// is the application's: pressing outside the box or pressing Escape calls `on_dismiss`
/// once and changes nothing, so the handler is where the application closes it or keeps
/// it open to ask about unsaved work.
///
/// The Renderer places the box. It opens on the side `placement` names (`Bottom` when it
/// is unset), lined up with the edge `align` names (`Start` when it is unset), and it moves
/// to the opposite side or slides along the edge when it would otherwise leave the window.
/// `offset` is the gap between the anchor and the box in dp, the design system's when it
/// is unset.
///
/// `scrim` dims the window behind the box while it is open; it is off unless asked.
/// `scrim_color` dims it with this colour instead of the design system's. `anchor` is the
/// widget the box is attached to; it is the first child on the wire.
///
/// `modifier` belongs to the floating box, not to the anchor: a shape, a border, a shadow
/// or a material given there replaces the design system's for this box.
///
/// `on_motion_finished` runs once when the node's size motion comes to rest, with the size
/// it settled at in dp; the node needs `Modifier::motion` for its size to move.
#[Composable(widget)]
pub fn Popover<'a>(
    open: bool,
    on_dismiss: impl FnMut() + 'static,
    modifier: Modifier,
    #[default(None::<::compose_rust::ui::PopoverPlacement>)] placement: impl Into<
        Option<PopoverPlacement>,
    >,
    #[default(None::<::compose_rust::ui::PopoverAlign>)] align: impl Into<Option<PopoverAlign>>,
    #[default(None::<f32>)] offset: impl Into<Option<f32>>,
    #[default(false)] scrim: bool,
    #[default(None::<::compose_rust::ui::Paint>)] scrim_color: impl Into<Option<Paint>>,
    #[default(None::<fn()>)] anchor: Option<impl FnOnce() + 'a>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::Popover(open)
        .modifier(modifier)
        .on_dismiss(on_dismiss);
    set!(scope; placement, align, offset);
    scope = scope.scrim(scrim);
    set!(scope; scrim_color);
    set_some!(scope; anchor, on_motion_finished);
    scope.content(content);
}

/// An explanation attached to its child, which assistive technology reads too. Compose's
/// `TooltipBox`.
///
/// `text` is the explanation, and the content is the child it is attached to.
/// `on_motion_finished` runs once when the node's size motion comes to rest, with the size
/// it settled at in dp; the node needs `Modifier::motion` for its size to move.
#[Composable(widget)]
pub fn Tooltip(
    text: impl Into<String>,
    modifier: Modifier,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::Tooltip(text).modifier(modifier);
    set_some!(scope; on_motion_finished);
    scope.content(content);
}

/// A place in the window for a platform view the application made: a web view, a video
/// surface or a map, as an `NSView`, an `HWND` or an X11 window.
///
/// Make the view against the handle the window reported
/// (`compose_rust::native_view::native_parent`), then hand it over as `handle`. The
/// Renderer puts it over this node's rectangle and keeps it there as the layout moves and
/// scrolls, cuts it to what the node's ancestors and any menu, popover or dialog drawn
/// above it leave showing, and hands keyboard focus to it and back. It never destroys the
/// view: once the node is gone, or `NativeHandle::NONE` has replaced the handle, and the
/// batch saying so has been applied, the view is the application's to destroy.
///
/// The node has no size of its own. Give it one with its modifier (a size, a fill, a
/// weight); what the modifier draws shows only where the view does not cover it.
///
/// `visible` says whether the view shows. A hidden view stays in the window with
/// everything it holds, so showing it again shows the same page; only removing the node
/// takes it out. `on_focus_change` hears the view take keyboard focus (`true`) and lose it
/// (`false`): a click in it, a focus request, the window going to the background and
/// coming back.
#[Composable(widget)]
pub fn NativeView(
    handle: NativeHandle,
    modifier: Modifier,
    #[default(true)] visible: bool,
    #[default(None::<fn(bool)>)] on_focus_change: Option<impl FnMut(bool) + 'static>,
) {
    let mut scope = crate::ui::widgets::NativeView(handle).modifier(modifier);
    scope = scope.visible(visible);
    set_some!(scope; on_focus_change);
    drop(scope);
}

/// A place files may be dropped. Desktop only in effect.
///
/// `on_files_dropped` receives the files dropped on it. `alignment` is where the content
/// sits inside it. `on_files_entered` runs when a drag carrying files enters it.
///
/// `on_motion_finished` runs once when the node's size motion comes to rest, with the size
/// it settled at in dp; the node needs `Modifier::motion` for its size to move.
#[Composable(widget)]
pub fn FileDropTarget(
    on_files_dropped: impl FnMut(FileDrop) + 'static,
    modifier: Modifier,
    #[default(None::<::compose_rust::ui::Alignment>)] alignment: impl Into<Option<Alignment>>,
    #[default(None::<fn()>)] on_files_entered: Option<impl FnMut() + 'static>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::FileDropTarget()
        .modifier(modifier)
        .on_files_dropped(on_files_dropped);
    set!(scope; alignment);
    set_some!(scope; on_files_entered, on_motion_finished);
    scope.content(content);
}
