//! Several windows in one process (FR-53).
//!
//! A window is a node: a `Window` child of the root, whose children are that window's
//! content. All the windows share one composition, one Host and one node table, so a state
//! one window's handler writes is read by every window that reads it, and the batch that
//! results carries every window's change (FR-53.1, FR-53.5).
//!
//! This module holds what is not a node. A [`WindowHandle`] names the window the caller is
//! in and carries the commands that happen once (bring it forward, give it a theme, change
//! its zoom). [`WindowEvent`] is what a window reports. The root hears two events that
//! belong to no window, a window the system made or restored and the application being
//! opened again with none, through the registrations at the bottom.
//!
//! Everything is keyed by the id of the `Window` node, the number the Renderer reports a
//! window's events under, so one window's size, zoom and theme never reach another's.

use crate::schema::{EventPayload, Theme};
use std::cell::{Cell, RefCell};
use std::rc::Rc;

pub use crate::schema::{LastWindowClosed, WindowMode};

/// The phase of a torn-off window being dragged (FR-53.7).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum WindowDragPhase {
    /// The window moved with the pointer.
    Move,
    /// The pointer was let go.
    End,
    /// The system took the drag away, or `Esc` was pressed.
    Cancel,
}

impl WindowDragPhase {
    fn from_wire(value: u32) -> Option<Self> {
        match value {
            crate::protocol::WINDOW_DRAG_MOVE => Some(Self::Move),
            crate::protocol::WINDOW_DRAG_END => Some(Self::End),
            crate::protocol::WINDOW_DRAG_CANCEL => Some(Self::Cancel),
            _ => None,
        }
    }
}

/// What a dragged window is doing over a node that listens for it (FR-53.7).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum WindowDragOverKind {
    Enter,
    Move,
    Leave,
    /// The pointer was let go over the node: the place to move the tab into this window.
    Drop,
}

impl WindowDragOverKind {
    fn from_wire(value: u32) -> Option<Self> {
        match value {
            crate::protocol::WINDOW_DRAG_OVER_ENTER => Some(Self::Enter),
            crate::protocol::WINDOW_DRAG_OVER_MOVE => Some(Self::Move),
            crate::protocol::WINDOW_DRAG_OVER_LEAVE => Some(Self::Leave),
            crate::protocol::WINDOW_DRAG_OVER_DROP => Some(Self::Drop),
            _ => None,
        }
    }
}

/// What a window reports to the handler registered for it (FR-53.4).
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum WindowEvent {
    /// The user asked to close the window. Nothing closes until the application removes
    /// the `Window` node.
    CloseRequested,
    /// The window became the key window, or lost that place.
    FocusChanged { focused: bool },
    /// The window's outer frame moved or changed size, in screen dp.
    Moved {
        x: f32,
        y: f32,
        width: f32,
        height: f32,
    },
    /// A torn-off window is being dragged. `over_window` is the id of the window under the
    /// pointer, or 0.
    Drag {
        phase: WindowDragPhase,
        screen_x: f32,
        screen_y: f32,
        over_window: u32,
    },
    /// A dragged window is passing over a node with a drag-over handler. The position is
    /// inside that node, in dp.
    DragOver {
        kind: WindowDragOverKind,
        window: u32,
        x: f32,
        y: f32,
    },
    /// The window could not be opened on this platform.
    Unavailable,
    /// The system closed the window without asking.
    Closed,
}

impl WindowEvent {
    /// The event a payload is, or `None` for a payload that is not a window's.
    pub(crate) fn from_payload(payload: &EventPayload<'_>) -> Option<Self> {
        Some(match *payload {
            EventPayload::WindowCloseRequested => Self::CloseRequested,
            EventPayload::WindowFocusChanged { focused } => Self::FocusChanged { focused },
            EventPayload::WindowMoved {
                x,
                y,
                width,
                height,
            } => Self::Moved {
                x,
                y,
                width,
                height,
            },
            EventPayload::WindowDrag {
                phase,
                screen_x,
                screen_y,
                over_window,
            } => Self::Drag {
                phase: WindowDragPhase::from_wire(phase)?,
                screen_x,
                screen_y,
                over_window,
            },
            EventPayload::WindowDragOver { kind, window, x, y } => Self::DragOver {
                kind: WindowDragOverKind::from_wire(kind)?,
                window,
                x,
                y,
            },
            EventPayload::WindowUnavailable => Self::Unavailable,
            EventPayload::WindowClosed => Self::Closed,
            _ => return None,
        })
    }
}

/// A window's outer frame, in screen dp (FR-53.8).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WindowFrame {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

/// A torn-off window being dragged (FR-53.7).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WindowDragEvent {
    pub phase: WindowDragPhase,
    /// Where the pointer is, in screen dp.
    pub screen_x: f32,
    pub screen_y: f32,
    /// The id of the window under the pointer, or 0.
    pub over_window: u32,
}

/// A dragged window passing over a node that listens for it (FR-53.7).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WindowDragOver {
    pub kind: WindowDragOverKind,
    /// The id of the window being dragged.
    pub window: u32,
    /// The position inside the node, in dp.
    pub x: f32,
    pub y: f32,
}

/// Opens a window under a pointer that is held down and has it follow the pointer until it
/// is let go (FR-53.7).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DragFromPointer {
    pub pointer_id: u32,
    /// The point of the new window's content, in dp, that sits under the pointer.
    pub anchor_x: f32,
    pub anchor_y: f32,
}

impl DragFromPointer {
    /// The 12 bytes the property travels as: the pointer, then the anchor.
    pub(crate) fn to_bytes(self) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(12);
        bytes.extend_from_slice(&self.pointer_id.to_le_bytes());
        bytes.extend_from_slice(&self.anchor_x.to_bits().to_le_bytes());
        bytes.extend_from_slice(&self.anchor_y.to_bits().to_le_bytes());
        bytes
    }
}

/// A window the application has no node for yet (FR-53.4): the system made it, or brought
/// it back after the process was killed, or the user asked for a new one.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WindowRequest {
    /// The `RestoreKey` the window was made under, or empty for a new window.
    pub key: String,
    /// The Renderer's number for the request.
    pub request: u32,
}

/// A command that happens once and so is a record and not a property.
pub(crate) enum Command {
    Focus(u32),
    Theme(u32, Theme),
    ZoomLevel(u32, i8),
}

thread_local! {
    /// The window whose node a handler is being called for, so a call to `window_size()`
    /// or `zoom()` from inside the handler means that window.
    static DISPATCH_WINDOW: Cell<u32> = const { Cell::new(0) };
    static COMMANDS: RefCell<Vec<Command>> = const { RefCell::new(Vec::new()) };
    /// The theme each window was last given, so a theme composed every frame is sent once.
    static APPLIED_THEMES: RefCell<Vec<(u32, Theme)>> = const { RefCell::new(Vec::new()) };
    static REQUEST_HANDLERS: RefCell<Vec<(u64, Rc<dyn Fn(WindowRequest)>)>> =
        const { RefCell::new(Vec::new()) };
    static REOPEN_HANDLERS: RefCell<Vec<(u64, Rc<dyn Fn()>)>> =
        const { RefCell::new(Vec::new()) };
    static NEXT_ID: Cell<u64> = const { Cell::new(1) };
}

/// Marks the window a handler is called for, until dropped.
pub(crate) struct DispatchWindow {
    previous: u32,
}

impl DispatchWindow {
    pub(crate) fn enter(window: u32) -> Self {
        let previous = DISPATCH_WINDOW.with(|current| current.replace(window));
        Self { previous }
    }
}

impl Drop for DispatchWindow {
    fn drop(&mut self) {
        let _ = DISPATCH_WINDOW.try_with(|current| current.set(self.previous));
    }
}

/// The id of the `Window` node the caller is in, or 0 outside any window.
///
/// While composing it is the window the composable being composed sits in. Inside a
/// handler it is the window the handler's node sits in.
pub(crate) fn current_window_id() -> u32 {
    let composing = crate::runtime::with_composer_if_idle(|composer| {
        composer.composing().then(|| composer.enclosing_window())
    })
    .flatten();
    composing.unwrap_or_else(|| DISPATCH_WINDOW.with(Cell::get))
}

/// A window of the application, named by the id of its `Window` node.
///
/// `Copy`, so it can move into any number of handlers. [`current_window`] hands out the
/// one the caller is in.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct WindowHandle {
    node: u32,
}

/// The window the caller is in, or `None` outside any `Window` (and so always in an
/// application that has one window).
pub fn current_window() -> Option<WindowHandle> {
    match current_window_id() {
        0 => None,
        node => Some(WindowHandle { node }),
    }
}

impl WindowHandle {
    /// The id of the window's node, the number its events are reported under.
    pub const fn id(&self) -> u32 {
        self.node
    }

    /// Brings the window forward and makes it the key window. A platform that does not let
    /// an application take the focus makes the window flash in the task bar instead.
    pub fn focus(&self) {
        COMMANDS.with_borrow_mut(|commands| commands.push(Command::Focus(self.node)));
        crate::boundary::request_frame_from_worker();
    }

    /// Gives this window a theme of its own over the application's (FR-53.3).
    pub fn set_theme(&self, theme: Theme) {
        apply_theme(self.node, theme);
    }

    /// What this window last measured. Not recorded as a read; a composable that has to
    /// run again when the class changes reads `current_window_size()`.
    pub fn size(&self) -> crate::window::WindowSize {
        crate::window::window_size_of(self.node)
    }

    /// What this window is drawing at.
    pub fn zoom(&self) -> crate::zoom::Zoom {
        crate::zoom::zoom_of(self.node)
    }

    /// Asks for an application zoom level for this window, kept inside its range.
    pub fn set_zoom_level(&self, level: i8) {
        queue_zoom_level(self.node, crate::zoom::clamp_level(level));
    }
}

/// Queues a theme for a window unless it already has exactly that one.
pub(crate) fn apply_theme(window: u32, theme: Theme) {
    let changed = APPLIED_THEMES.with_borrow_mut(|applied| {
        match applied.iter_mut().find(|(id, _)| *id == window) {
            Some(entry) => {
                if entry.1 == theme {
                    return false;
                }
                entry.1 = theme;
                true
            }
            None => {
                applied.push((window, theme));
                true
            }
        }
    });
    if !changed {
        return;
    }
    COMMANDS.with_borrow_mut(|commands| {
        commands.retain(|command| !matches!(command, Command::Theme(id, _) if *id == window));
        commands.push(Command::Theme(window, theme));
    });
    crate::boundary::request_frame_from_worker();
}

/// Queues a zoom level for a window, replacing one that is still waiting.
pub(crate) fn queue_zoom_level(window: u32, level: i8) {
    COMMANDS.with_borrow_mut(|commands| {
        commands.retain(|command| !matches!(command, Command::ZoomLevel(id, _) if *id == window));
        commands.push(Command::ZoomLevel(window, level));
    });
    crate::boundary::request_frame_from_worker();
}

/// The level a window was asked for since the last batch, if it was.
pub(crate) fn pending_zoom_level(window: u32) -> Option<i8> {
    COMMANDS.with_borrow(|commands| {
        commands.iter().find_map(|command| match command {
            Command::ZoomLevel(id, level) if *id == window => Some(*level),
            _ => None,
        })
    })
}

/// Hands the waiting commands over, oldest first, and empties the queue.
pub(crate) fn drain_commands(mut each: impl FnMut(Command)) {
    let commands = COMMANDS.with_borrow_mut(std::mem::take);
    for command in commands {
        each(command);
    }
}

/// Forgets everything kept for a window that left the tree.
pub(crate) fn forget_window(window: u32) {
    crate::window::forget_window(window);
    crate::zoom::forget_window(window);
    APPLIED_THEMES.with_borrow_mut(|applied| applied.retain(|(id, _)| *id != window));
    COMMANDS.with_borrow_mut(|commands| {
        commands.retain(|command| match command {
            Command::Focus(id) | Command::Theme(id, _) | Command::ZoomLevel(id, _) => *id != window,
        });
    });
}

/// Clears the commands and the registrations. Used when a Host is made and between tests.
#[doc(hidden)]
pub fn reset_windows() {
    DISPATCH_WINDOW.with(|current| current.set(0));
    COMMANDS.with_borrow_mut(Vec::clear);
    APPLIED_THEMES.with_borrow_mut(Vec::clear);
    REQUEST_HANDLERS.with_borrow_mut(Vec::clear);
    REOPEN_HANDLERS.with_borrow_mut(Vec::clear);
}

fn next_id() -> u64 {
    NEXT_ID.with(|next| {
        let id = next.get();
        next.set(id + 1);
        id
    })
}

/// A registration for windows the application has no node for. Dropping it removes the
/// handler.
pub struct WindowRequestSubscription {
    id: u64,
}

impl Drop for WindowRequestSubscription {
    fn drop(&mut self) {
        REQUEST_HANDLERS.with_borrow_mut(|handlers| handlers.retain(|(id, _)| *id != self.id));
    }
}

/// Runs `handler` for each window the system or the user made that the application has no
/// node for, for as long as the returned registration is kept. The handler puts a `Window`
/// node with that `RestoreKey` into the tree in the same call, or the Renderer closes the
/// window (FR-53.4).
pub fn on_window_request(handler: Rc<dyn Fn(WindowRequest)>) -> WindowRequestSubscription {
    let id = next_id();
    REQUEST_HANDLERS.with_borrow_mut(|handlers| handlers.push((id, handler)));
    WindowRequestSubscription { id }
}

/// Hands a request to the handlers. Returns whether anyone was listening.
pub(crate) fn deliver_request(request: &WindowRequest) -> bool {
    // Copied out first: a handler may mount or unmount a component that listens.
    let handlers: Vec<Rc<dyn Fn(WindowRequest)>> = REQUEST_HANDLERS.with_borrow(|handlers| {
        handlers
            .iter()
            .map(|(_, handler)| handler.clone())
            .collect()
    });
    for handler in &handlers {
        handler(request.clone());
    }
    !handlers.is_empty()
}

/// A registration for the application being opened again while it has no window. Dropping
/// it removes the handler.
pub struct ApplicationReopenSubscription {
    id: u64,
}

impl Drop for ApplicationReopenSubscription {
    fn drop(&mut self) {
        REOPEN_HANDLERS.with_borrow_mut(|handlers| handlers.retain(|(id, _)| *id != self.id));
    }
}

/// Runs `handler` when the user opens the application again while it has no window, which
/// is the Dock icon on macOS (FR-53.6).
pub fn on_application_reopen_handler(handler: Rc<dyn Fn()>) -> ApplicationReopenSubscription {
    let id = next_id();
    REOPEN_HANDLERS.with_borrow_mut(|handlers| handlers.push((id, handler)));
    ApplicationReopenSubscription { id }
}

/// Tells the handlers. Returns whether anyone was listening.
pub(crate) fn deliver_reopen() -> bool {
    let handlers: Vec<Rc<dyn Fn()>> = REOPEN_HANDLERS.with_borrow(|handlers| {
        handlers
            .iter()
            .map(|(_, handler)| handler.clone())
            .collect()
    });
    for handler in &handlers {
        handler();
    }
    !handlers.is_empty()
}
