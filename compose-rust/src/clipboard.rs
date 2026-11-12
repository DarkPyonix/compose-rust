//! Writing plain text to the platform clipboard from application code.
//!
//! Copying what the user selected is the Renderer's own business and never comes through
//! here. This is the other way in: a "Copy link" button whose text the application already
//! holds. The Host asks with one command record on the batch the current call produces,
//! and the Renderer puts the text on the clipboard through Compose. Nothing comes back: a
//! write either lands or, where there is no clipboard to write to, is dropped quietly.
//!
//! Anything on the clipboard can be read by every other application and by clipboard
//! history tools, so this is not the way to copy a password, a token or a private key.

use std::cell::RefCell;
use std::fmt;

/// The longest text one call may put on the clipboard, in UTF-8 bytes.
///
/// A link or a code snippet fits many times over. The limit keeps one call from carrying a
/// document's worth of bytes through a batch whose job is the next frame.
pub const MAX_TEXT_BYTES: usize = 64 * 1024;

/// Why a clipboard write was refused before anything was sent.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ClipboardError {
    /// The text is longer than [`MAX_TEXT_BYTES`]. `len` is its length in bytes.
    TooLong { len: usize },
}

impl fmt::Display for ClipboardError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ClipboardError::TooLong { len } => write!(
                f,
                "clipboard text is {len} bytes, longer than the {MAX_TEXT_BYTES} bytes one write may carry"
            ),
        }
    }
}

impl std::error::Error for ClipboardError {}

thread_local! {
    static QUEUE: RefCell<Vec<String>> = const { RefCell::new(Vec::new()) };
}

/// Puts `text` on the platform clipboard as plain text, replacing what was there.
///
/// Call it from an event handler or an effect, like the Host's other calls; a worker
/// thread updates a signal and lets the UI side call it. The write rides out on the batch
/// the current call produces. An empty string leaves the clipboard holding empty text.
///
/// Returns [`ClipboardError::TooLong`] and sends nothing when `text` is longer than
/// [`MAX_TEXT_BYTES`]. Success means the request is on its way, not that a clipboard
/// exists: a Renderer without one ignores it.
pub fn set_text(text: &str) -> Result<(), ClipboardError> {
    if text.len() > MAX_TEXT_BYTES {
        return Err(ClipboardError::TooLong { len: text.len() });
    }
    QUEUE.with_borrow_mut(|queue| queue.push(text.to_owned()));
    Ok(())
}

/// Hands every queued write to `emit`, oldest first, and empties the queue.
pub(crate) fn drain(mut emit: impl FnMut(&str)) {
    let mut taken = QUEUE.with_borrow_mut(std::mem::take);
    if taken.is_empty() {
        return;
    }
    for text in &taken {
        emit(text);
    }
    taken.clear();
    QUEUE.with_borrow_mut(|queue| {
        if queue.is_empty() {
            *queue = taken;
        }
    });
}

/// Forgets every queued write. Used when a new `Host` takes over the thread.
#[doc(hidden)]
pub fn reset_clipboard() {
    QUEUE.with_borrow_mut(Vec::clear);
}
