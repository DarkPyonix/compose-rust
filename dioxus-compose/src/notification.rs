//! Notifications: calling the user from outside the window.
//!
//! A transient message speaks inside the window, for a few seconds, about something the user
//! just did. A notification speaks outside it, about something the user did not do (a long
//! job finished, something is waiting for an answer), and stays until the user clears it.
//! Its moment is precisely when nobody is looking at the window.
//!
//! The Host does not call the platform. The Renderer owns the platform, so what crosses is a
//! command record in an ordinary batch, and what comes back is an ordinary event. That keeps
//! one way in to the Host (the Renderer's UI thread) and keeps every platform binding on the
//! side that already has the window.
//!
//! A notification is usually a worker's news: the session ended on a worker thread, so that
//! is where the application learns it. [`NotificationSender`] is `Send` for that reason.
//! What it posts waits in a queue inside the Host, the Host asks for a frame the way it does
//! for any worker's change, and the next batch carries it. Nothing here is a queue on the
//! boundary: the boundary still only sees a batch handed over inside a call.

use crate::schema::{NotificationImportance, NotificationPermission, NotificationPresentation};
use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

/// One notification, built and then posted.
///
/// ```ignore
/// Notification::new("Session finished")
///     .body("refactor-parser: 214 tests passed")
///     .key(format!("session/{id}"))
///     .channel("Sessions")
///     .presentation(NotificationPresentation::WhenInactive)
///     .post();
/// ```
///
/// Nothing here names the application or its icon. The platform puts those on from the
/// application's own bundle, which is where the window's icon comes from as well.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Notification {
    pub(crate) key: String,
    pub(crate) title: String,
    pub(crate) body: String,
    pub(crate) channel: String,
    pub(crate) actions: [String; 2],
    pub(crate) importance: NotificationImportance,
    pub(crate) presentation: NotificationPresentation,
}

impl Notification {
    pub fn new(title: impl Into<String>) -> Self {
        Self {
            key: String::new(),
            title: title.into(),
            body: String::new(),
            channel: String::new(),
            actions: [String::new(), String::new()],
            importance: NotificationImportance::Normal,
            presentation: NotificationPresentation::Always,
        }
    }

    /// The text under the title.
    pub fn body(mut self, body: impl Into<String>) -> Self {
        self.body = body.into();
        self
    }

    /// The application's own name for this notification.
    ///
    /// Posting again under the same key replaces the one already showing, so a session
    /// whose state keeps changing has one notification that is kept current rather than a
    /// pile of them. The key is also what comes back when the user presses it, and what
    /// [`withdraw_notification`] takes.
    ///
    /// Without one the Renderer names it, and a notification named that way can be neither
    /// replaced nor withdrawn.
    pub fn key(mut self, key: impl Into<String>) -> Self {
        self.key = key.into();
        self
    }

    /// The group the user can turn off on its own, in the application's own words.
    ///
    /// Android lists these in its settings, so the name is something a person reads. Empty
    /// is the application's default group. A platform without groups ignores it.
    pub fn channel(mut self, channel: impl Into<String>) -> Self {
        self.channel = channel.into();
        self
    }

    /// The first button. Pressing it reports action 1, and does not bring the window up:
    /// a button on a notification exists to be dealt with without opening anything.
    pub fn action_1(mut self, label: impl Into<String>) -> Self {
        self.actions[0] = label.into();
        self
    }

    /// The second button, which reports action 2. Two is the most every platform shows
    /// the same way.
    pub fn action_2(mut self, label: impl Into<String>) -> Self {
        self.actions[1] = label.into();
        self
    }

    /// How much this matters. What that is worth in sound or in getting through Do Not
    /// Disturb is the platform's answer.
    pub fn importance(mut self, importance: NotificationImportance) -> Self {
        self.importance = importance;
        self
    }

    /// Whether it is shown while the application's window is the active one.
    pub fn presentation(mut self, presentation: NotificationPresentation) -> Self {
        self.presentation = presentation;
        self
    }

    /// Hands the notification over.
    ///
    /// From a component or an event handler it goes out with the batch that call produces.
    /// From any other thread it waits in the Host and the Host asks for a frame, which is
    /// what [`NotificationSender::post`] does as well.
    pub fn post(self) {
        submit(NotificationCommand::Post(self));
    }
}

/// Takes back the notification posted under `key`.
///
/// An approval the user already gave inside the window leaves "waiting for approval" in the
/// notification centre saying something that is no longer true. This is how that stops.
pub fn withdraw_notification(key: impl Into<String>) {
    submit(NotificationCommand::Withdraw(key.into()));
}

/// Asks for permission to show notifications now, rather than on the first one posted.
///
/// For a settings screen with a switch that says "notify me when a session finishes":
/// asking at the moment the user turns it on is asking when the user knows what for. The
/// answer arrives through [`notification_permission`] and the readers registered with
/// [`subscribe_permission`].
///
/// A browser only lets a page ask from inside something the user did. Called from the
/// handler of that press, the request rides out in the batch the press produced and is
/// applied on the same call stack, so it is still inside it.
pub fn request_notification_permission() {
    submit(NotificationCommand::RequestPermission);
}

/// A handle a worker thread posts notifications through.
///
/// `Send`, `Copy` and free to make: the queue it feeds belongs to the Host, so every handle
/// reaches the same one. Nothing it does calls the Renderer. It queues, and the Host asks
/// for a frame in which the queue is written into the batch.
///
/// ```ignore
/// let notifications = NotificationSender::new();
/// std::thread::spawn(move || {
///     run_session();
///     notifications.post(Notification::new("Session finished").key("session/7"));
/// });
/// ```
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct NotificationSender {
    _private: (),
}

impl NotificationSender {
    pub const fn new() -> Self {
        Self { _private: () }
    }

    pub fn post(&self, notification: Notification) {
        submit(NotificationCommand::Post(notification));
    }

    pub fn withdraw(&self, key: impl Into<String>) {
        submit(NotificationCommand::Withdraw(key.into()));
    }

    pub fn request_permission(&self) {
        submit(NotificationCommand::RequestPermission);
    }
}

/// What the queue holds, in the order it was asked for. A post followed by a withdrawal of
/// the same key has to reach the Renderer in that order, so the three share one queue.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum NotificationCommand {
    Post(Notification),
    Withdraw(String),
    RequestPermission,
}

/// Commands waiting for the next batch. Shared across threads, because a worker posts.
static QUEUE: Mutex<Vec<NotificationCommand>> = Mutex::new(Vec::new());

/// Whether the queue has anything in it, read without taking the lock.
///
/// Every batch looks, and almost every batch finds nothing. A flag read first keeps that
/// look to one atomic load and no lock and no allocation.
static PENDING: AtomicBool = AtomicBool::new(false);

fn queue() -> MutexGuard<'static, Vec<NotificationCommand>> {
    // A worker that panicked while holding the lock does not make the queue wrong: a push
    // either happened or did not. Refusing every later notification would.
    QUEUE.lock().unwrap_or_else(|poison| poison.into_inner())
}

fn submit(command: NotificationCommand) {
    queue().push(command);
    PENDING.store(true, Ordering::Release);
    // Inside a Host call on the UI thread, the batch that call is about to produce carries
    // it, so asking for a frame would only buy an empty one.
    if !crate::boundary::in_host_call() {
        crate::boundary::request_frame_for_notifications();
    }
}

/// Hands every queued command to `emit` and empties the queue.
///
/// The buffer is moved out and put back, so posting through a steady stream reuses one
/// allocation. Returns how many posts went out, so the Host can say once, in a debug build,
/// that a platform which cannot show them is taking them.
pub(crate) fn drain(mut emit: impl FnMut(&NotificationCommand)) -> usize {
    if !PENDING.swap(false, Ordering::AcqRel) {
        return 0;
    }
    let mut taken = std::mem::take(&mut *queue());
    let mut posts = 0;
    for command in &taken {
        if matches!(command, NotificationCommand::Post(_)) {
            posts += 1;
        }
        emit(command);
    }
    taken.clear();
    let mut guard = queue();
    if guard.is_empty() {
        *guard = taken;
    } else {
        // Something was posted while these were being written. It is still waiting, so the
        // flag has to say so.
        PENDING.store(true, Ordering::Release);
    }
    posts
}

/// What the user pressed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NotificationActivation {
    /// The key the notification was posted under.
    pub key: String,
    /// 0 for the notification itself, 1 or 2 for one of its buttons.
    pub action: u32,
}

impl NotificationActivation {
    /// True when the notification itself was pressed rather than one of its buttons. The
    /// Renderer has already brought the window up.
    pub fn is_body(&self) -> bool {
        self.action == 0
    }

    /// Which button was pressed, if one was.
    pub fn action_button(&self) -> Option<u32> {
        (self.action != 0).then_some(self.action)
    }
}

struct Subscriber {
    id: u64,
    notify: Arc<dyn Fn() + Send + Sync>,
}

/// What an activation is handed to. Reference counted so the list can be copied out
/// before any of them runs: a handler may add or remove one.
type ActivationHandler = Rc<dyn Fn(NotificationActivation)>;

thread_local! {
    /// Both sides start from here, so the Renderer only has to report a difference.
    static PERMISSION: Cell<NotificationPermission> =
        const { Cell::new(NotificationPermission::NotDetermined) };
    static PERMISSION_SUBSCRIBERS: RefCell<Vec<Subscriber>> = const { RefCell::new(Vec::new()) };
    static ACTIVATION_HANDLERS: RefCell<Vec<(u64, ActivationHandler)>> =
        const { RefCell::new(Vec::new()) };
    static NEXT_ID: Cell<u64> = const { Cell::new(1) };
}

/// Whether a post that a platform could not show has already been explained.
static EXPLAINED: AtomicBool = AtomicBool::new(false);
/// Whether anything has been posted yet, so an `Unsupported` that arrives afterwards can
/// still be explained once.
static POSTED: AtomicBool = AtomicBool::new(false);

fn next_id() -> u64 {
    NEXT_ID.with(|next| {
        let id = next.get();
        next.set(id + 1);
        id
    })
}

/// The permission the Renderer last reported. Available outside a component too.
pub fn notification_permission() -> NotificationPermission {
    PERMISSION.with(Cell::get)
}

/// Records the Renderer's answer and wakes the components that asked about it.
///
/// Returns whether any component was woken, which is what decides whether the Host renders.
pub(crate) fn publish_permission(state: NotificationPermission) -> bool {
    if state == NotificationPermission::Unsupported && POSTED.load(Ordering::Acquire) {
        explain_unsupported();
    }
    if PERMISSION.with(Cell::get) == state {
        return false;
    }
    PERMISSION.with(|current| current.set(state));
    PERMISSION_SUBSCRIBERS.with_borrow(|subscribers| {
        for subscriber in subscribers {
            (subscriber.notify)();
        }
        !subscribers.is_empty()
    })
}

/// Records that `posts` notifications went out in a batch, and says once, in a debug build,
/// why none of them will appear when this run cannot show any.
pub(crate) fn note_posted(posts: usize) {
    if posts == 0 {
        return;
    }
    POSTED.store(true, Ordering::Release);
    if notification_permission() == NotificationPermission::Unsupported {
        explain_unsupported();
    }
}

fn explain_unsupported() {
    if cfg!(debug_assertions) && !EXPLAINED.swap(true, Ordering::AcqRel) {
        eprintln!("compose-rust: {}", unsupported_reason());
    }
}

/// What usually stands between this platform and a notification, for the one debug line.
///
/// Worked out from the platform this was built for, because the Host is never told the
/// Renderer's reason, only that there is one.
pub fn unsupported_reason() -> &'static str {
    if cfg!(target_os = "macos") || cfg!(target_os = "ios") {
        "notifications are not shown in this run: it has no bundle identifier, so the \
         notification centre will not take them. A signed application bundle has one; a \
         development run of a bare executable does not."
    } else if cfg!(target_os = "windows") {
        "notifications are not shown in this run: no Start menu shortcut carries an \
         AppUserModelID for this executable, and toasts need one. An installed package \
         (MSIX, or an installer that makes the shortcut) has it; an unpacked executable \
         does not."
    } else if cfg!(target_os = "android") {
        "notifications are not shown in this run: the system has no notification service \
         for this application."
    } else if cfg!(target_family = "wasm") {
        "notifications are not shown in this run: the page is not a secure context (HTTPS \
         or localhost), or this browser has no Notifications API."
    } else {
        "notifications are not shown in this run: nothing on the session bus owns \
         org.freedesktop.Notifications, so there is no notification daemon to show them."
    }
}

/// Hands an activation to every component that asked for one.
///
/// Returns whether anyone was listening. Nobody listening is not an error: the event is
/// dropped, which is what an application that never asked expects.
pub(crate) fn activate(activation: NotificationActivation) -> bool {
    // Copied out first. A handler may mount or unmount a component that listens, and that
    // edits the list being walked.
    let handlers: Vec<ActivationHandler> = ACTIVATION_HANDLERS
        .with_borrow(|handlers| handlers.iter().map(|(_, h)| h.clone()).collect());
    for handler in &handlers {
        handler(activation.clone());
    }
    !handlers.is_empty()
}

/// Forgets the permission, the listeners and anything still queued. Used between tests.
#[doc(hidden)]
pub fn reset_notifications() {
    queue().clear();
    PENDING.store(false, Ordering::Release);
    PERMISSION.with(|current| current.set(NotificationPermission::NotDetermined));
    PERMISSION_SUBSCRIBERS.with_borrow_mut(Vec::clear);
    ACTIVATION_HANDLERS.with_borrow_mut(Vec::clear);
    POSTED.store(false, Ordering::Release);
    EXPLAINED.store(false, Ordering::Release);
}

/// A registration for changes of the notification permission. Dropping it ends the
/// registration, so a runtime keeps it for as long as the reader it wakes is alive.
pub struct PermissionSubscription {
    id: u64,
}

/// Registers `notify` to be called each time the Renderer reports a different permission.
/// The Dioxus adapter's `use_notification_permission` hook holds one.
pub fn subscribe_permission(notify: Arc<dyn Fn() + Send + Sync>) -> PermissionSubscription {
    let id = next_id();
    PERMISSION_SUBSCRIBERS
        .with_borrow_mut(|subscribers| subscribers.push(Subscriber { id, notify }));
    PermissionSubscription { id }
}

impl Drop for PermissionSubscription {
    fn drop(&mut self) {
        PERMISSION_SUBSCRIBERS.with_borrow_mut(|subscribers| {
            subscribers.retain(|subscriber| subscriber.id != self.id);
        });
    }
}

/// An activation handler's registration. Dropping it removes the handler.
pub struct ActivationSubscription {
    id: u64,
}

impl Drop for ActivationSubscription {
    fn drop(&mut self) {
        ACTIVATION_HANDLERS.with_borrow_mut(|handlers| handlers.retain(|(id, _)| *id != self.id));
    }
}

/// Runs `handler` when the user presses a notification this application posted, for as
/// long as the returned registration is kept.
///
/// One registration for the whole application rather than a callback per notification. A
/// notification stays in the notification centre for hours, long after whatever posted it
/// is gone, so what it carries back is the key it was posted under. The Dioxus adapter's
/// `use_notification_activated` hook holds one of these for a component.
///
/// With no handler registered, an activation is dropped.
pub fn on_activation(handler: Rc<dyn Fn(NotificationActivation)>) -> ActivationSubscription {
    let id = next_id();
    ACTIVATION_HANDLERS.with_borrow_mut(|handlers| handlers.push((id, handler)));
    ActivationSubscription { id }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fr36_a_notification_says_nothing_it_was_not_told() {
        let notification = Notification::new("title");
        assert_eq!(notification.key, "");
        assert_eq!(notification.body, "");
        assert_eq!(notification.channel, "");
        assert_eq!(notification.actions, [String::new(), String::new()]);
        assert_eq!(notification.importance, NotificationImportance::Normal);
        assert_eq!(notification.presentation, NotificationPresentation::Always);
    }

    #[test]
    fn fr36_an_activation_knows_whether_a_button_was_pressed() {
        let body = NotificationActivation {
            key: "k".to_owned(),
            action: 0,
        };
        let button = NotificationActivation {
            key: "k".to_owned(),
            action: 2,
        };
        assert!(body.is_body());
        assert_eq!(body.action_button(), None);
        assert!(!button.is_body());
        assert_eq!(button.action_button(), Some(2));
    }
}
