//! Formatting a rich text field from Rust: a state a field is bound to, the commands it
//! queues for that field, and the formatting and document it hears back.

use std::cell::{Cell, RefCell};
use std::fmt;
use std::rc::Rc;

use crate::rich_text::{Format, RichDocument, TextFormat};
use crate::runtime::{MutableState, mutable_state_of, with_composer_if_idle};

/// One thing asked of the field a state is bound to.
pub(crate) enum RichRequest {
    Format(Format),
    Load(RichDocument),
}

/// What a state and the composition share: the node the state is bound to, 0 while it is
/// bound to none, and what is waiting to be written.
#[derive(Default)]
pub(crate) struct RichTarget {
    pub(crate) node: Cell<u32>,
    /// Whether the composer already has this target on its list of work for the next batch.
    pub(crate) queued: Cell<bool>,
    pub(crate) pending: RefCell<Vec<RichRequest>>,
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
/// were made.
#[derive(Clone)]
pub struct RichTextState {
    target: Rc<RichTarget>,
    format: MutableState<TextFormat>,
    document: MutableState<RichDocument>,
}

impl RichTextState {
    /// A state bound to no field yet, with nothing selected and an empty document.
    pub fn new() -> Self {
        Self {
            target: Rc::default(),
            format: mutable_state_of(TextFormat::default()),
            document: mutable_state_of(RichDocument::default()),
        }
    }

    /// Applies a formatting command to the selection of the field this state is bound to.
    /// The next batch carries one `FormatText` for it.
    pub fn format(&self, format: Format) {
        self.target
            .pending
            .borrow_mut()
            .push(RichRequest::Format(format));
        self.request();
    }

    /// Replaces the field's document with `document`, for opening one that was saved. The
    /// field keeps the selection it has, as `SetText` does, and holds the change until an
    /// input method composition in it is done.
    ///
    /// A document that does not hold together (see [`RichDocument::validate`]) is refused
    /// and never sent; in a debug build the reason is printed. Returns whether it was
    /// accepted.
    pub fn load(&self, document: RichDocument) -> bool {
        if let Err(error) = document.validate() {
            #[cfg(debug_assertions)]
            eprintln!("compose-rust: RichTextState::load refused a document: {error}");
            let _ = error;
            return false;
        }
        self.document.set(document.clone());
        self.target
            .pending
            .borrow_mut()
            .push(RichRequest::Load(document));
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
