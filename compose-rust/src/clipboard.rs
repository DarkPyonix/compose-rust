//! Plain text on the platform clipboard, written and read from application code.
//!
//! Copying what the user selected is the Renderer's own business and never comes through
//! here. This is the other way in: a "Copy link" button whose text the application already
//! holds. The Host asks with one command record on the batch the current call produces,
//! and the Renderer puts the text on the clipboard through Compose. Nothing comes back: a
//! write either lands or, where there is no clipboard to write to, is dropped quietly.
//!
//! A read goes the same way and does come back: [`read_text`] numbers the request, the
//! Renderer reads the clipboard through Compose, and one `ClipboardText` event with that
//! number carries the text, or says there was none, or that there was too much. Every read
//! gets exactly one answer.
//!
//! Anything on the clipboard can be read by every other application and by clipboard
//! history tools, so this is not the way to copy a password, a token or a private key.

use std::cell::RefCell;
use std::fmt;

/// The longest text one call may put on or read from the clipboard, in UTF-8 bytes.
///
/// A link or a code snippet fits many times over. The limit keeps one call from carrying a
/// document's worth of bytes through a batch whose job is the next frame.
pub const MAX_TEXT_BYTES: usize = 64 * 1024;

/// Why a clipboard write was refused before anything was sent, or a read brought no text.
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
                "clipboard text is {len} bytes, longer than the {MAX_TEXT_BYTES} bytes one read or write may carry"
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
    READS.with_borrow_mut(Vec::clear);
    WAITING.with_borrow_mut(Vec::clear);
}

// ----- reading ------------------------------------------------------------------------------

/// What the clipboard held when a read was answered, as the wire says it.
///
/// The words travel in the `ClipboardText` event's status field.
pub mod read_status {
    /// No plain text: the clipboard is empty, holds something else, is missing, or the
    /// platform refused to hand it over.
    pub const NONE: u32 = 0;
    /// Plain text, carried whole in the event.
    pub const TEXT: u32 = 1;
    /// Plain text longer than [`super::MAX_TEXT_BYTES`]. None of it is carried; the event's
    /// length field says how many bytes it was.
    pub const TOO_LONG: u32 = 2;
}

/// The result of one read: the text, `None` when there was no plain text, or
/// [`ClipboardError::TooLong`] when there was more than one read may carry. Over-long text
/// is never cut short, so an application can refuse the paste instead of pasting part.
pub type ReadResult = Result<Option<String>, ClipboardError>;

type ReadCallback = Box<dyn FnOnce(ReadResult)>;

thread_local! {
    /// Read requests waiting for the next batch.
    static READS: RefCell<Vec<u32>> = const { RefCell::new(Vec::new()) };
    /// Callbacks waiting for their answer, by request number.
    static WAITING: RefCell<Vec<(u32, ReadCallback)>> = const { RefCell::new(Vec::new()) };
    /// The next request number. 0 is skipped so a zeroed record never looks like a request.
    static NEXT_READ: std::cell::Cell<u32> = const { std::cell::Cell::new(1) };
}

fn next_read() -> u32 {
    NEXT_READ.with(|next| {
        let mut id = next.get();
        if id == 0 {
            id = 1;
        }
        next.set(id.wrapping_add(1));
        id
    })
}

/// Reads the plain text on the platform clipboard and calls `on_text` once with it.
///
/// Call it on the UI thread, from an event handler or an effect. The request rides out on
/// the batch the current call produces and the answer comes back as an event, so
/// `on_text` runs later, on the UI thread, and is then dropped. It is called exactly once
/// for every read: with `Ok(None)` when the clipboard is empty, holds no text, does not
/// exist, or the platform (a browser, say) refused, and with [`ClipboardError::TooLong`]
/// when the text is longer than [`MAX_TEXT_BYTES`].
///
/// A paste shortcut is the usual caller:
///
/// ```ignore
/// clipboard::read_text(move |result| match result {
///     Ok(Some(text)) => terminal.paste(&text),
///     Ok(None) => {}
///     Err(error) => show_message(error.to_string()),
/// });
/// ```
pub fn read_text(on_text: impl FnOnce(ReadResult) + 'static) {
    let request = next_read();
    WAITING.with_borrow_mut(|waiting| waiting.push((request, Box::new(on_text))));
    READS.with_borrow_mut(|reads| reads.push(request));
    // Inside a Host call the batch that call produces carries it. Anywhere else, a frame
    // is what gets it out.
    if !crate::boundary::in_host_call() {
        crate::boundary::request_frame_from_worker();
    }
}

/// [`read_text`] as a future, for a coroutine scope or a launched effect.
///
/// ```ignore
/// scope.launch(async move {
///     if let Ok(Some(text)) = clipboard::read_text_async().await {
///         field.set(text);
///     }
/// });
/// ```
///
/// The read is asked for on the first poll. Dropping the future before it resolves leaves
/// the answer to be ignored when it arrives.
pub fn read_text_async() -> impl std::future::Future<Output = ReadResult> {
    ReadFuture { shared: None }
}

#[derive(Default)]
struct ReadShared {
    result: Option<ReadResult>,
    waker: Option<std::task::Waker>,
}

struct ReadFuture {
    shared: Option<std::rc::Rc<RefCell<ReadShared>>>,
}

impl std::future::Future for ReadFuture {
    type Output = ReadResult;

    fn poll(
        mut self: std::pin::Pin<&mut Self>,
        context: &mut std::task::Context<'_>,
    ) -> std::task::Poll<ReadResult> {
        let shared = match &self.shared {
            Some(shared) => std::rc::Rc::clone(shared),
            None => {
                let shared = std::rc::Rc::new(RefCell::new(ReadShared::default()));
                let answer = std::rc::Rc::clone(&shared);
                read_text(move |result| {
                    let waker = {
                        let mut answer = answer.borrow_mut();
                        answer.result = Some(result);
                        answer.waker.take()
                    };
                    if let Some(waker) = waker {
                        waker.wake();
                    }
                });
                self.shared = Some(std::rc::Rc::clone(&shared));
                shared
            }
        };
        let mut state = shared.borrow_mut();
        match state.result.take() {
            Some(result) => std::task::Poll::Ready(result),
            None => {
                state.waker = Some(context.waker().clone());
                std::task::Poll::Pending
            }
        }
    }
}

/// The platform clipboard, as a value a component can hold. Compose's `LocalClipboard`.
///
/// It carries nothing: every call goes to the same clipboard. It exists so a component reads
/// `let clipboard = use_clipboard();` and then calls `clipboard.read_text().await` or
/// `clipboard.set_text(..)`.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct Clipboard {
    _private: (),
}

impl Clipboard {
    /// See [`read_text_async`].
    pub fn read_text(&self) -> impl std::future::Future<Output = ReadResult> {
        read_text_async()
    }

    /// See [`read_text`].
    pub fn read_text_with(&self, on_text: impl FnOnce(ReadResult) + 'static) {
        read_text(on_text);
    }

    /// See [`set_text`].
    pub fn set_text(&self, text: &str) -> Result<(), ClipboardError> {
        set_text(text)
    }
}

/// The platform clipboard. Free to call anywhere, including during composition.
pub fn use_clipboard() -> Clipboard {
    Clipboard::default()
}

/// Hands every queued read to `emit`, oldest first, and empties the queue.
pub(crate) fn drain_reads(mut emit: impl FnMut(u32)) {
    let taken = READS.with_borrow_mut(std::mem::take);
    for request in taken {
        emit(request);
    }
}

/// Hands an answer to the callback that asked for it. Returns whether there was one.
///
/// `status` is one of [`read_status`]; a word this side does not know reads as no text.
/// `len` is the text's length in bytes as the Renderer measured it, which is what a
/// too-long answer reports. An answer nobody is waiting for is ignored.
pub(crate) fn deliver_read(request: u32, status: u32, len: u32, text: &str) -> bool {
    let result = match status {
        read_status::TEXT if text.len() > MAX_TEXT_BYTES => {
            Err(ClipboardError::TooLong { len: text.len() })
        }
        read_status::TEXT if text.is_empty() => Ok(None),
        read_status::TEXT => Ok(Some(text.to_owned())),
        read_status::TOO_LONG => Err(ClipboardError::TooLong {
            len: (len as usize).max(MAX_TEXT_BYTES + 1),
        }),
        _ => Ok(None),
    };
    // Taken out before it runs: the callback may read again, which edits the list.
    let callback = WAITING.with_borrow_mut(|waiting| {
        waiting
            .iter()
            .position(|(id, _)| *id == request)
            .map(|at| waiting.remove(at).1)
    });
    match callback {
        Some(callback) => {
            callback(result);
            true
        }
        None => false,
    }
}
