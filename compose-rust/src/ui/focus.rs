//! Moving keyboard focus from Rust: a handle a widget is bound to, and a request that
//! puts focus on that widget's node.

use std::cell::Cell;
use std::fmt;
use std::rc::Rc;

use crate::runtime::with_composer_if_idle;

/// What a handle and the composition share: the node the handle is bound to, 0 while it
/// is bound to none, and whether a request for it is waiting for the next batch.
#[derive(Default)]
pub(crate) struct FocusTarget {
    pub(crate) node: Cell<u32>,
    pub(crate) requested: Cell<bool>,
}

/// A way to put keyboard focus on one widget. Compose's `FocusRequester`.
///
/// Make one with [`use_focus_handle`], give it to a widget with `.focus_handle(&handle)`,
/// and call [`FocusHandle::request`] from a handler or an effect. Cloning is cheap: every
/// clone is the same handle. The handle follows its widget's node across re-renders, and
/// when the widget is replaced by a new node, it follows the new one.
///
/// A drawer that takes focus when it opens and gives it back to the control that opened
/// it when Escape closes it:
///
/// ```ignore
/// use compose_rust::runtime::*;
/// use compose_rust::ui::*;
///
/// #[Composable]
/// fn Screen() {
///     let open = remember(|| mutable_state_of(false));
///     let opener = use_focus_handle();
///     let drawer = use_focus_handle();
///     Column().content(|| {
///         Button("Menu")
///             .focus_handle(&opener)
///             .on_click(move || open.set(true));
///         if open.get() {
///             let back = opener.clone();
///             Column()
///                 .focus_handle(&drawer)
///                 .on_key_down(move |key| {
///                     if key.text() == "Escape" {
///                         key.consume();
///                         open.set(false);
///                         back.request();
///                     }
///                 })
///                 .content(|| {
///                     Text("Drawer");
///                 });
///         }
///     });
///     // The drawer's node exists once the composition that opened it is applied, so
///     // the request runs as an effect of that composition rather than in the click.
///     let take = drawer.clone();
///     let is_open = open.get();
///     launched_effect(is_open, move || async move {
///         if is_open {
///             take.request();
///         }
///     });
/// }
/// ```
///
/// A request made while the handle's node is not on screen does nothing and is not kept
/// for later, which is why the drawer above asks from an effect.
#[derive(Clone, Default)]
pub struct FocusHandle {
    target: Rc<FocusTarget>,
}

impl FocusHandle {
    /// A handle bound to nothing yet.
    pub fn new() -> Self {
        Self::default()
    }

    /// Asks for keyboard focus on the node this handle is bound to. The next batch
    /// carries one `RequestFocus` for it, however many times this is called before then.
    ///
    /// A handle that is bound to no node on screen, because its widget has not been
    /// composed yet or has left, asks for nothing.
    pub fn request(&self) {
        if self.target.requested.get() {
            return;
        }
        let target = Rc::clone(&self.target);
        with_composer_if_idle(|composer| composer.request_focus(target));
    }

    /// Whether the handle is bound to a node. A node that has since left the screen still
    /// counts until another widget takes the handle.
    pub fn is_bound(&self) -> bool {
        self.target.node.get() != 0
    }

    pub(crate) fn target(&self) -> Rc<FocusTarget> {
        Rc::clone(&self.target)
    }
}

impl fmt::Debug for FocusHandle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("FocusHandle")
            .field("node", &self.target.node.get())
            .field("requested", &self.target.requested.get())
            .finish()
    }
}

impl PartialEq for FocusHandle {
    fn eq(&self, other: &Self) -> bool {
        Rc::ptr_eq(&self.target, &other.target)
    }
}

/// A focus handle this call site keeps across compositions. Compose's
/// `remember { FocusRequester() }`.
#[track_caller]
pub fn use_focus_handle() -> FocusHandle {
    crate::runtime::remember(FocusHandle::new)
}

/// Binds `handle` to the node the widget being composed is about to create or update.
/// Called right before the widget's `emit`.
pub(crate) fn bind_focus_handle(handle: Option<FocusHandle>) {
    if let Some(handle) = handle {
        with_composer_if_idle(|composer| composer.bind_focus_to_next_node(handle.target()));
    }
}
