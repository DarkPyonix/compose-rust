//! What an event hands its handler, for the events whose payload is more than a value.
//!
//! These are the Host's types rather than an authoring layer's, because what they hold is
//! decided by the wire: a key press and whether it was consumed, the paths a drop
//! carried, the range a list asked for. Every runtime hands the same ones over.

use crate::Key;
use std::cell::Cell;
use std::rc::Rc;

/// A key-down event whose consumption state is shared with the Host boundary.
#[derive(Clone, Debug)]
pub struct KeyEvent {
    key: Key,
    shift_key: bool,
    ctrl_key: bool,
    alt_key: bool,
    meta_key: bool,
    consumed: Rc<Cell<bool>>,
}

impl KeyEvent {
    pub fn new(key: Key, shift_key: bool, ctrl_key: bool, alt_key: bool, meta_key: bool) -> Self {
        Self {
            key,
            shift_key,
            ctrl_key,
            alt_key,
            meta_key,
            consumed: Rc::new(Cell::new(false)),
        }
    }

    pub fn key(&self) -> Key {
        self.key
    }

    pub fn shift_key(&self) -> bool {
        self.shift_key
    }

    pub fn ctrl_key(&self) -> bool {
        self.ctrl_key
    }

    pub fn alt_key(&self) -> bool {
        self.alt_key
    }

    pub fn meta_key(&self) -> bool {
        self.meta_key
    }

    pub fn consume(&self) {
        self.consumed.set(true);
    }

    pub fn consumed(&self) -> bool {
        self.consumed.get()
    }
}

/// The paths of the files a reader let go over a node.
///
/// Separated on the wire by a NUL, which is the one byte no path on any of the three
/// desktops may contain. A newline would have been shorter to read and wrong: a file
/// called "notes\nfor tuesday" is legal on two of them, and splitting on newlines would
/// have turned one file into two.
#[derive(Clone, Debug, PartialEq)]
pub struct FileDrop {
    paths: Vec<String>,
}

impl FileDrop {
    /// Takes apart the one string the paths travelled in.
    ///
    /// Public because the separation is part of what this type promises, and a test that
    /// could not build one could only check it through a window.
    pub fn new(joined: &str) -> Self {
        Self {
            paths: joined
                .split('\0')
                .filter(|path| !path.is_empty())
                .map(str::to_owned)
                .collect(),
        }
    }

    /// Every path that arrived, in the order the platform gave them.
    pub fn paths(&self) -> &[String] {
        &self.paths
    }
}

/// The visible item range the Renderer asks the Host to materialise.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RangeRequest {
    start: u32,
    count: u32,
}

impl RangeRequest {
    pub fn new(start: u32, count: u32) -> Self {
        Self { start, count }
    }

    pub fn start(&self) -> usize {
        self.start as usize
    }

    pub fn count(&self) -> usize {
        self.count as usize
    }
}

/// Which kinds of pointer event a node subscribes to, as the bit mask the wire carries.
///
/// The bit of a kind is `1 << (tag - 1)`, so `Move` is 1 and `ContextMenu` is 128. A mask
/// of zero subscribes to nothing, and a node with no subscription gets no pointer input
/// at all, which is what keeps a screen of plain containers free of it.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct PointerKinds(pub u32);

impl PointerKinds {
    pub const NONE: Self = Self(0);
    pub const MOVE: Self = Self::of(crate::schema::PointerKind::Move);
    pub const ENTER: Self = Self::of(crate::schema::PointerKind::Enter);
    pub const LEAVE: Self = Self::of(crate::schema::PointerKind::Leave);
    pub const DOWN: Self = Self::of(crate::schema::PointerKind::Down);
    pub const UP: Self = Self::of(crate::schema::PointerKind::Up);
    pub const CANCEL: Self = Self::of(crate::schema::PointerKind::Cancel);
    pub const WHEEL: Self = Self::of(crate::schema::PointerKind::Wheel);
    pub const CONTEXT_MENU: Self = Self::of(crate::schema::PointerKind::ContextMenu);
    /// Every kind.
    pub const ALL: Self = Self(0xff);

    /// The mask holding one kind.
    pub const fn of(kind: crate::schema::PointerKind) -> Self {
        Self(1 << (kind as u32 - 1))
    }

    /// This mask and `other` together.
    pub const fn union(self, other: Self) -> Self {
        Self(self.0 | other.0)
    }

    /// Whether `kind` is in the mask.
    pub const fn contains(self, kind: crate::schema::PointerKind) -> bool {
        self.0 & Self::of(kind).0 != 0
    }

    pub const fn bits(self) -> u32 {
        self.0
    }
}

impl std::ops::BitOr for PointerKinds {
    type Output = Self;

    fn bitor(self, other: Self) -> Self {
        self.union(other)
    }
}

/// The bit of each mouse button in `buttons` and `button`: the same numbers as the DOM's
/// `buttons` and Compose's `PointerButtons`.
pub mod pointer_button {
    pub const PRIMARY: u32 = 1;
    pub const SECONDARY: u32 = 2;
    pub const TERTIARY: u32 = 4;
    pub const BACK: u32 = 8;
    pub const FORWARD: u32 = 16;
}

/// One pointer event handed to a handler, with its consumption state shared with the
/// Host boundary like [`KeyEvent`]'s.
///
/// Calling [`PointerEvent::consume`] keeps the event from reaching an ancestor's handler
/// and from doing what the Renderer would otherwise do with it: a consumed `Wheel` does not
/// scroll, a consumed `ContextMenu` shows no menu.
#[derive(Clone, Debug)]
pub struct PointerEvent {
    kind: crate::schema::PointerKind,
    pointer_id: u32,
    pointer_type: crate::schema::PointerType,
    x: f32,
    y: f32,
    buttons: u32,
    button: u32,
    modifiers: u32,
    click_count: u32,
    delta_x: f32,
    delta_y: f32,
    delta_mode: crate::schema::WheelDeltaMode,
    timestamp_nanos: u64,
    consumed: Rc<Cell<bool>>,
}

impl PointerEvent {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        kind: crate::schema::PointerKind,
        pointer_id: u32,
        pointer_type: crate::schema::PointerType,
        x: f32,
        y: f32,
        buttons: u32,
        button: u32,
        modifiers: u32,
        click_count: u32,
        delta_x: f32,
        delta_y: f32,
        delta_mode: crate::schema::WheelDeltaMode,
        timestamp_nanos: u64,
    ) -> Self {
        Self {
            kind,
            pointer_id,
            pointer_type,
            x,
            y,
            buttons,
            button,
            modifiers,
            click_count,
            delta_x,
            delta_y,
            delta_mode,
            timestamp_nanos,
            consumed: Rc::new(Cell::new(false)),
        }
    }

    pub fn kind(&self) -> crate::schema::PointerKind {
        self.kind
    }

    /// Names one finger, pen or mouse across its events. A mouse is 1.
    pub fn pointer_id(&self) -> u32 {
        self.pointer_id
    }

    pub fn pointer_type(&self) -> crate::schema::PointerType {
        self.pointer_type
    }

    /// Distance from the node's left edge, in dp. Outside the node while it holds a capture.
    pub fn x(&self) -> f32 {
        self.x
    }

    /// Distance from the node's top edge, in dp.
    pub fn y(&self) -> f32 {
        self.y
    }

    /// The buttons held now, as a mask of [`pointer_button`] bits.
    pub fn buttons(&self) -> u32 {
        self.buttons
    }

    /// The button that caused this `Down` or `Up`, and 0 for any other kind.
    pub fn button(&self) -> u32 {
        self.button
    }

    pub fn shift_key(&self) -> bool {
        self.modifiers & 1 != 0
    }

    pub fn ctrl_key(&self) -> bool {
        self.modifiers & 2 != 0
    }

    pub fn alt_key(&self) -> bool {
        self.modifiers & 4 != 0
    }

    pub fn meta_key(&self) -> bool {
        self.modifiers & 8 != 0
    }

    /// Consecutive presses in the same place on a `Down` or `Up`: 1, 2, and so on.
    pub fn click_count(&self) -> u32 {
        self.click_count
    }

    /// Wheel travel, positive to the right. Zero for any other kind.
    pub fn delta_x(&self) -> f32 {
        self.delta_x
    }

    /// Wheel travel, positive downward.
    pub fn delta_y(&self) -> f32 {
        self.delta_y
    }

    pub fn delta_mode(&self) -> crate::schema::WheelDeltaMode {
        self.delta_mode
    }

    /// When the OS produced the input, on the monotonic clock `render_frame` also uses.
    pub fn timestamp_nanos(&self) -> u64 {
        self.timestamp_nanos
    }

    pub fn consume(&self) {
        self.consumed.set(true);
    }

    pub fn consumed(&self) -> bool {
        self.consumed.get()
    }
}
