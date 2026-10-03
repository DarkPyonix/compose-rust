//! The hooks a component reads the Renderer's reports through.
//!
//! The values are compose-rust's: the window's size class, a node's measured size, the
//! design system the theme resolved to, the notification permission. Each is recorded by
//! the Host when the Renderer reports it, and each keeps a list of readers to wake. A hook
//! here is one such reader, registered for as long as the component holds its hook state,
//! woken by marking the component dirty.

use compose_rust::notification::NotificationActivation;
use compose_rust::schema::{DesignSystem, NotificationPermission};
use compose_rust::theme::ThemeHandle;
use compose_rust::window::{NodeSize, WindowSize};
use std::rc::Rc;

/// Reads the window's size class inside a component, and re-renders it when the class
/// changes.
///
/// ```ignore
/// let window = use_window_size();
/// rsx! {
///     if window.is_expanded() {
///         Row { Sidebar {} Content {} }
///     } else {
///         Content {}
///     }
/// }
/// ```
///
/// Only components that call this are woken, and only when the class actually changes.
/// Resizing inside one class re-renders nothing.
pub fn use_window_size() -> WindowSize {
    // Rc, because hook state has to be `Clone` and the registration must not be
    // duplicated: dropping the last handle with the component's hook state is what
    // removes the subscription.
    dioxus_core::use_hook(|| {
        Rc::new(compose_rust::window::subscribe(
            dioxus_core::schedule_update(),
        ))
    });
    compose_rust::window::window_size()
}

/// Follows one node's size, and re-renders this component when its class changes.
///
/// The token is the name the screen gives the node, because a node id belongs to the
/// Renderer and never crosses back as something the Host chose. Attach it with
/// `observe_size` and read the size here.
///
/// ```ignore
/// let panel = use_node_size();
/// rsx! {
///     Card {
///         observe_size: panel.token(),
///         if panel.is_expanded() { Row { Left {} Right {} } } else { Left {} }
///     }
/// }
/// ```
///
/// A component that never calls this costs nothing: the modifier is the only thing that
/// makes the Renderer measure, and the modifier comes from here.
pub fn use_node_size() -> NodeSize {
    let subscription = dioxus_core::use_hook(|| {
        let token = compose_rust::window::next_node_token();
        Rc::new(compose_rust::window::subscribe_node(
            token,
            dioxus_core::schedule_update(),
        ))
    });
    NodeSize::of(subscription.token())
}

/// Reads the resolved design system inside a component, and re-renders it when the answer
/// changes.
///
/// ```ignore
/// let design = use_design_system();
/// if design.is_apple() {
///     // AC, and no memory row
/// } else {
///     // C, and a memory row
/// }
/// ```
///
/// **This is not how an application chooses a design system.** Choosing is
/// `Theme::unified`; this reads an answer already given. And it is not a way to paint
/// colours by hand: what a role resolves to is still the design system's, and reaching
/// for a literal because this told you which system is running is the thing the role
/// vocabulary exists to prevent.
pub fn use_design_system() -> DesignSystem {
    // Rc for the same reason the window size hook uses one: hook state has to be `Clone`,
    // and the registration must be dropped exactly once, with the last handle.
    dioxus_core::use_hook(|| {
        Rc::new(compose_rust::design::subscribe(
            dioxus_core::schedule_update(),
        ))
    });
    compose_rust::design::design_system()
}

/// The application's theme, to read and to change while it runs.
///
/// ```ignore
/// let theme = use_theme();
/// rsx! {
///     Button {
///         text: "Ember",
///         on_click: move |_| theme.set_palette(
///             Palette::new().with(ColorRole::Primary, Color::rgb(0xE8590C), Color::rgb(0xFF8A4C)),
///         ),
///     }
/// }
/// ```
///
/// A change is one `SetTheme` record in the batch the handler produces. Turning dark
/// because the platform did is not this: that is the Renderer's, and the Host never hears
/// about it.
pub fn use_theme() -> ThemeHandle {
    ThemeHandle::default()
}

/// Reads whether notifications may be shown, and re-renders when that changes.
///
/// ```ignore
/// match use_notification_permission() {
///     NotificationPermission::Denied => rsx! { Text { text: "Notifications are off in Settings" } },
///     _ => rsx! { Switch { checked: wants, onchange: move |_| request_notification_permission() } },
/// }
/// ```
///
/// What to show instead of a notification nobody will see is the application's decision.
/// The Renderer never swaps one for a message inside the window: the moment a notification
/// is for is the moment nobody is looking at the window.
pub fn use_notification_permission() -> NotificationPermission {
    dioxus_core::use_hook(|| {
        Rc::new(compose_rust::notification::subscribe_permission(
            dioxus_core::schedule_update(),
        ))
    });
    compose_rust::notification::notification_permission()
}

/// Runs `handler` when the user presses a notification this application posted.
///
/// One hook for the whole application rather than a callback per notification. A
/// notification stays in the notification centre for hours, long after the component that
/// posted it is gone, so what it carries back is the key it was posted under, which the
/// application can read at any time:
///
/// ```ignore
/// use_notification_activated(move |activation| {
///     if let Some(id) = activation.key.strip_prefix("session/") {
///         open_session(id);
///     }
/// });
/// ```
///
/// With no component holding this hook, an activation is dropped.
pub fn use_notification_activated(handler: impl FnMut(NotificationActivation) + 'static) {
    // A Callback rather than the closure itself, so the handler runs inside the scope of the
    // component that registered it and can write that component's signals.
    let callback = dioxus_hooks::use_callback(handler);
    dioxus_core::use_hook(|| {
        Rc::new(compose_rust::notification::on_activation(Rc::new(
            move |activation: NotificationActivation| callback.call(activation),
        )))
    });
}

/// One edit asked of a code editor, waiting for the batch it goes out in.
pub(crate) struct PendingEdit {
    pub(crate) token: u32,
    pub(crate) request_id: u32,
    pub(crate) base_version: u32,
    pub(crate) range: compose_rust::code::CodeRange,
    pub(crate) text: String,
}

thread_local! {
    static EDITS: std::cell::RefCell<Vec<PendingEdit>> = const { std::cell::RefCell::new(Vec::new()) };
    static NEXT_EDITOR_TOKEN: std::cell::Cell<u32> = const { std::cell::Cell::new(1) };
}

/// A way to change the document a code editor holds, for formatting, accepting a
/// completion or reloading a file that changed underneath it.
///
/// Created with [`use_code_editor`] and handed to the editor as its `handle`. An edit is
/// written against a version, the one the application last heard of. Where the reader has
/// typed since, the Renderer moves the range along with what they typed; where they typed
/// in the same place, it refuses the edit and says so through `on_edit_rejected`, because
/// an application must never type over its reader. An edit that is applied comes back as
/// an ordinary change, so the version stays one sequence whoever made the edit.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct CodeEditorHandle {
    token: u32,
}

impl CodeEditorHandle {
    /// A handle attached to no editor yet. [`use_code_editor`] is the usual way to get one,
    /// because it keeps the same handle across renders.
    #[allow(clippy::new_without_default)]
    pub fn new() -> Self {
        let token = NEXT_EDITOR_TOKEN.with(|next| {
            let token = next.get();
            next.set(token.checked_add(1).unwrap_or(1));
            token
        });
        Self { token }
    }

    pub(crate) fn token(self) -> u32 {
        self.token
    }

    /// Replaces `range` of the document as it stood at `base_version` with `text`.
    ///
    /// It goes out with the batch the current call produces, so call it from a component,
    /// an event handler or a task on the thread the application runs on. A worker updates
    /// a signal instead, and the component that reads the signal calls this.
    ///
    /// `request_id` is the application's own name for the edit, returned if it is refused.
    pub fn edit(
        &self,
        request_id: u32,
        base_version: u32,
        range: compose_rust::code::CodeRange,
        text: impl Into<String>,
    ) {
        let edit = PendingEdit {
            token: self.token,
            request_id,
            base_version,
            range,
            text: text.into(),
        };
        EDITS.with_borrow_mut(|edits| edits.push(edit));
    }
}

/// A handle to the editor this component draws, the same one on every render.
pub fn use_code_editor() -> CodeEditorHandle {
    dioxus_core::use_hook(CodeEditorHandle::new)
}

/// Hands every queued edit to `emit` and empties the queue.
pub(crate) fn drain_edits(mut emit: impl FnMut(&PendingEdit)) {
    let mut taken = EDITS.with_borrow_mut(std::mem::take);
    if taken.is_empty() {
        return;
    }
    for edit in &taken {
        emit(edit);
    }
    taken.clear();
    EDITS.with_borrow_mut(|edits| {
        if edits.is_empty() {
            *edits = taken;
        }
    });
}

/// Forgets every queued edit, for a new runtime taking over the thread: an edit addressed
/// to the old one's editor names nothing in the new one.
pub(crate) fn reset_edits() {
    EDITS.with_borrow_mut(Vec::clear);
}
