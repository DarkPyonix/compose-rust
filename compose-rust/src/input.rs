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
