//! Formatting a rich text field from Rust: a state a field is bound to, the commands it
//! queues for that field, and the formatting and document it hears back.

use std::cell::{Cell, RefCell};
use std::fmt;
use std::rc::Rc;

use crate::rich_text::{CHARACTER_RUN_LEN, Format, PARAGRAPH_LEN, RichDocument, TextFormat};
use crate::runtime::{MutableState, mutable_state_of, with_composer_if_idle};

/// One thing asked of the field a state is bound to.
pub(crate) enum RichRequest {
    Format(Format),
    Load(RichDocument),
}

impl RichRequest {
    /// What the request holds while it waits, counted as the bytes it is on the wire: the
    /// 20 byte record of a command, or the 40 byte record of a document with its text and
    /// its run and paragraph records.
    fn bytes(&self) -> usize {
        match self {
            Self::Format(_) => 20,
            Self::Load(document) => {
                40 + document.text.len()
                    + document.characters.len() * CHARACTER_RUN_LEN
                    + document.paragraphs.len() * PARAGRAPH_LEN
            }
        }
    }
}

/// What a state and the composition share: the node the state is bound to, 0 while it is
/// bound to none, and what is waiting to be written.
#[derive(Default)]
pub(crate) struct RichTarget {
    pub(crate) node: Cell<u32>,
    /// Whether the composer already has this target on its list of work for the next batch.
    pub(crate) queued: Cell<bool>,
    /// What is waiting, oldest first. Only `push` adds to it and only `take` empties it,
    /// so `pending_bytes` is always its size.
    pending: RefCell<Vec<RichRequest>>,
    /// What `pending` holds, in bytes, kept as requests come and go so it is never summed.
    pending_bytes: Cell<usize>,
    /// Whether a request was dropped since `pending` was last taken, so that it is said
    /// once and not for every request of a flood.
    full: Cell<bool>,
}

impl RichTarget {
    /// Adds `request` to the ones waiting, and answers whether it did. A request that does
    /// not fit in [`RichTextState::MAX_PENDING_BYTES`] behind the ones already waiting is
    /// dropped. One that finds nothing waiting is taken whatever its size.
    fn push(&self, request: RichRequest) -> bool {
        let mut pending = self.pending.borrow_mut();
        let held = self.pending_bytes.get();
        let bytes = request.bytes();
        if !pending.is_empty() && held.saturating_add(bytes) > RichTextState::MAX_PENDING_BYTES {
            let first = !self.full.replace(true);
            #[cfg(debug_assertions)]
            if first {
                eprintln!(
                    "compose-rust: a RichTextState dropped a command. {held} bytes of commands \
                     already wait for its field and the most is {}. Commands are dropped until \
                     the field is on screen and takes them.",
                    RichTextState::MAX_PENDING_BYTES
                );
            }
            let _ = first;
            return false;
        }
        pending.push(request);
        self.pending_bytes.set(held.saturating_add(bytes));
        true
    }

    /// Whether anything is waiting.
    pub(crate) fn has_pending(&self) -> bool {
        !self.pending.borrow().is_empty()
    }

    /// Hands over what is waiting, oldest first, and leaves room for new requests.
    pub(crate) fn take(&self) -> Vec<RichRequest> {
        self.pending_bytes.set(0);
        self.full.set(false);
        self.pending.take()
    }
}

/// A rich text field's formatting, and the way to change it. Make one with
/// [`remember_rich_text_state`], give it to a field with `rich = &state`, and call
/// [`RichTextState::format`] from a handler, such as the buttons of a format sheet.
///
/// The Renderer owns the formatting of the field while it is edited, so this does not hold
/// the runs. It queues commands for the field and keeps the last answers the field gave:
/// the formatting at the selection and, after the user pauses, the document. Cloning is
/// cheap: every clone is the same state.
///
/// Commands wait for the field. One made before the field is composed, or while it is
/// off screen, goes out in the first batch after the field is there, in the order they
/// were made. A state keeps at most [`RichTextState::MAX_PENDING_BYTES`] of them waiting.
#[derive(Clone)]
pub struct RichTextState {
    target: Rc<RichTarget>,
    format: MutableState<TextFormat>,
    document: MutableState<RichDocument>,
}

impl RichTextState {
    /// The most the commands waiting for a state's field may hold, in bytes as they are on
    /// the wire: about 1 MiB.
    ///
    /// Commands wait until the field is on screen, and a state that is never given to a
    /// field waits for ever, so without a limit every command made on it would be kept for
    /// as long as the state lives. A command that does not fit behind the ones already
    /// waiting is dropped, and a debug build prints a line for the first one dropped. A
    /// command that finds nothing waiting is always taken, so one document larger than this
    /// still loads.
    pub const MAX_PENDING_BYTES: usize = 1024 * 1024;

    /// A state bound to no field yet, with nothing selected and an empty document.
    pub fn new() -> Self {
        Self {
            target: Rc::default(),
            format: mutable_state_of(TextFormat::default()),
            document: mutable_state_of(RichDocument::default()),
        }
    }

    /// Applies a formatting command to the selection of the field this state is bound to.
    /// The next batch carries one `FormatText` for it. A command made while
    /// [`RichTextState::MAX_PENDING_BYTES`] of them already wait for the field is dropped.
    pub fn format(&self, format: Format) {
        if self.target.push(RichRequest::Format(format)) {
            self.request();
        }
    }

    /// Replaces the field's document with `document`, for opening one that was saved. The
    /// field keeps the selection it has, as `SetText` does, and holds the change until an
    /// input method composition in it is done.
    ///
    /// A document that does not hold together (see [`RichDocument::validate`]) is refused
    /// and never sent; in a debug build the reason is printed. A document that does not fit
    /// behind the commands already waiting (see [`RichTextState::MAX_PENDING_BYTES`]) is
    /// refused as well. Either way [`RichTextState::document`] stays what it was. Returns
    /// whether it was accepted.
    pub fn load(&self, document: RichDocument) -> bool {
        if let Err(error) = document.validate() {
            #[cfg(debug_assertions)]
            eprintln!("compose-rust: RichTextState::load refused a document: {error}");
            let _ = error;
            return false;
        }
        if !self.target.push(RichRequest::Load(document.clone())) {
            return false;
        }
        self.document.set(document);
        self.request();
        true
    }

    /// The formatting at the selection, as the field last reported it. A composable that
    /// reads this runs again when the selection or its formatting changes.
    pub fn format_at_selection(&self) -> TextFormat {
        self.format.get()
    }

    /// The document as the field last reported it, which is after the user pauses typing or
    /// after [`RichTextState::load`]. A composable that reads this runs again when it
    /// changes.
    pub fn document(&self) -> RichDocument {
        self.document.get()
    }

    /// Whether the state is bound to a field.
    pub fn is_bound(&self) -> bool {
        self.target.node.get() != 0
    }

    fn request(&self) {
        let target = Rc::clone(&self.target);
        with_composer_if_idle(|composer| composer.request_rich(target));
    }

    pub(crate) fn target(&self) -> Rc<RichTarget> {
        Rc::clone(&self.target)
    }

    pub(crate) fn format_state(&self) -> MutableState<TextFormat> {
        self.format
    }

    pub(crate) fn document_state(&self) -> MutableState<RichDocument> {
        self.document
    }
}

impl Default for RichTextState {
    fn default() -> Self {
        Self::new()
    }
}

impl fmt::Debug for RichTextState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("RichTextState")
            .field("node", &self.target.node.get())
            .field("pending", &self.target.pending.borrow().len())
            .field("pending_bytes", &self.target.pending_bytes.get())
            .finish()
    }
}

impl PartialEq for RichTextState {
    fn eq(&self, other: &Self) -> bool {
        Rc::ptr_eq(&self.target, &other.target)
    }
}

/// A [`RichTextState`] this call site keeps across compositions.
#[track_caller]
pub fn remember_rich_text_state() -> RichTextState {
    crate::runtime::remember(RichTextState::new)
}

/// Binds `state` to the node the widget being composed is about to create or update.
/// Called right before the widget's `emit`.
pub(crate) fn bind_rich_state(state: Option<&RichTextState>) {
    if let Some(state) = state {
        let target = state.target();
        with_composer_if_idle(|composer| composer.bind_rich_to_next_node(target));
    }
}
