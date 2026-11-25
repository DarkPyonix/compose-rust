//! Giving a live `TextField` a new text from Rust: a handle the field is bound to, and a
//! `SetText` for that field's node.

use std::cell::{Cell, RefCell};
use std::fmt;
use std::rc::Rc;

use crate::runtime::with_composer_if_idle;
use crate::schema::Selection;

/// What a handle and the composition share: the node the handle is bound to (0 while it
/// is bound to none), and the text waiting for the next batch, if any.
#[derive(Default)]
pub(crate) struct TextFieldTarget {
    pub(crate) node: Cell<u32>,
    /// Whether a text is waiting for the next batch. Its text is in `text`.
    pub(crate) queued: Cell<bool>,
    pub(crate) text: RefCell<String>,
    pub(crate) selection: Cell<Option<Selection>>,
}

/// A way to change the text of one `TextField` while it is on screen.
///
/// A `TextField` is uncontrolled: the Renderer owns what is typed, and passing a different
/// `text` to a field that is already there changes nothing, so an input method's
/// composition is never sent through Rust and back. When the application itself has a new
/// text for the field (a reset, a cleared form, a value chosen elsewhere), it says so with
/// [`TextFieldHandle::set_text`]. The Renderer applies it to the same field, keeping the
/// caret where it still fits, and waits for a composition in progress to end first.
///
/// Make one with [`use_text_field_handle`], give it to a field with `text_handle = &handle`,
/// and call `set_text` from a handler or an effect. Cloning is cheap: every clone is the
/// same handle. The handle follows its field's node across re-renders, and when the field
/// is replaced by a new node, it follows the new one.
///
/// ```ignore
/// use compose_rust::runtime::*;
/// use compose_rust::ui::*;
///
/// #[Composable]
/// fn Search() {
///     let query = use_text_field_handle();
///     Row!({
///         TextField!("", |_| {}, text_handle = &query);
///         let clear = query.clone();
///         Button!("Clear", move || clear.set_text(""));
///     });
/// }
/// ```
///
/// A call made while the handle's field is not on screen does nothing and is not kept for
/// later, as with [`FocusHandle::request`](super::FocusHandle::request).
#[derive(Clone, Default)]
pub struct TextFieldHandle {
    target: Rc<TextFieldTarget>,
}

impl TextFieldHandle {
    /// A handle bound to no field yet.
    pub fn new() -> Self {
        Self::default()
    }

    /// Gives the field `text`, the empty text included. The next batch carries one
    /// `SetText` for the field's node, with the last text asked for before it. The
    /// Renderer keeps the caret and selection where they still fit.
    pub fn set_text(&self, text: impl AsRef<str>) {
        self.queue(text.as_ref(), None);
    }

    /// Gives the field `text` with the caret or selection at `selection`, in UTF-16 code
    /// units from the start of `text`.
    pub fn set_text_with_selection(&self, text: impl AsRef<str>, selection: Selection) {
        self.queue(text.as_ref(), Some(selection));
    }

    /// Whether the handle is bound to a field. A field that has since left the screen still
    /// counts until another field takes the handle.
    pub fn is_bound(&self) -> bool {
        self.target.node.get() != 0
    }

    pub(crate) fn target(&self) -> Rc<TextFieldTarget> {
        Rc::clone(&self.target)
    }

    fn queue(&self, text: &str, selection: Option<Selection>) {
        let target = Rc::clone(&self.target);
        with_composer_if_idle(|composer| composer.request_set_text(target, text, selection));
    }
}

impl fmt::Debug for TextFieldHandle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("TextFieldHandle")
            .field("node", &self.target.node.get())
            .field("queued", &self.target.queued.get())
            .finish()
    }
}

impl PartialEq for TextFieldHandle {
    fn eq(&self, other: &Self) -> bool {
        Rc::ptr_eq(&self.target, &other.target)
    }
}

/// A text field handle this call site keeps across compositions.
#[track_caller]
pub fn use_text_field_handle() -> TextFieldHandle {
    crate::runtime::remember(TextFieldHandle::new)
}

/// Binds `handle` to the node the field being composed is about to create or update.
/// Called right before the field's `emit`.
pub(crate) fn bind_text_field_handle(handle: Option<TextFieldHandle>) {
    if let Some(handle) = handle {
        with_composer_if_idle(|composer| composer.bind_text_to_next_node(handle.target()));
    }
}
