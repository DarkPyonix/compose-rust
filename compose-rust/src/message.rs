//! Transient messages: the one sentence an application says after something happened.
//!
//! A message is not part of the tree. It has a lifetime rather than a position, and that
//! lifetime belongs to the Renderer: how long it stays, where it sits, what it does when a
//! second one arrives while the first is still up. Modelling it as a node would mean the
//! Host holding "showing until four seconds from now" and running the VirtualDom again to
//! take it away, which is a render for an animation nobody asked Rust about.
//!
//! So a message is posted and forgotten. It rides out on the batch the current call
//! produces, in the same way every other record does.

use crate::schema::MessageDuration;
use dioxus_core::Callback;
use std::cell::{Cell, RefCell};
use std::collections::VecDeque;

/// One message, ready to be written into a batch.
pub(crate) struct PendingMessage {
    pub(crate) handler_id: u64,
    pub(crate) text: String,
    pub(crate) action: String,
    pub(crate) duration: MessageDuration,
}

/// Where message action ids start.
///
/// Handler ids for listeners on nodes are handed out from 1 upwards, so starting here
/// leaves the two sets of ids unable to meet: an event carrying an id this large named an
/// action on a message, and one below it named a listener on a node.
const ACTION_HANDLER_BASE: u64 = 1 << 63;

/// How many action callbacks are kept alive at once.
///
/// A message that has left the screen can never be pressed again, but nothing tells the
/// Host when that happened, and adding a record that did would put the Renderer's own
/// timer back on the wire. The bound solves it from the other end: the Renderer shows one
/// message at a time and queues at most eight, so an action older than that is already
/// unreachable and its callback can be dropped.
const LIVE_ACTIONS: usize = 16;

thread_local! {
    static QUEUE: RefCell<Vec<PendingMessage>> = const { RefCell::new(Vec::new()) };
    static ACTIONS: RefCell<VecDeque<(u64, Callback<()>)>> = const {
        RefCell::new(VecDeque::new())
    };
    static NEXT_ACTION_ID: Cell<u64> = const { Cell::new(ACTION_HANDLER_BASE) };
}

/// A sentence to show the user once, with an optional thing to do about it.
///
/// ```ignore
/// Message::new("Task deleted")
///     .with_action("Undo", move |()| restore())
///     .with_duration(MessageDuration::Long)
///     .show();
/// ```
///
/// Nothing here says where the message appears or what it looks like. Material calls it a
/// snackbar, Apple a transient overlay, GNOME a toast: the design system decides, the same
/// way it decides what a `Dialog` looks like.
pub struct Message {
    text: String,
    action: String,
    on_action: Option<Callback<()>>,
    duration: MessageDuration,
}

impl Message {
    pub fn new(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            action: String::new(),
            on_action: None,
            duration: MessageDuration::Short,
        }
    }

    /// Adds the one thing the user can do about the message, such as undoing it.
    ///
    /// Call this from a component or an event handler: the callback is owned by the scope
    /// it is created in, which is what lets it touch that scope's signals when it runs.
    pub fn with_action(
        mut self,
        label: impl Into<String>,
        on_action: impl FnMut(()) + 'static,
    ) -> Self {
        self.action = label.into();
        self.on_action = Some(Callback::new(on_action));
        self
    }

    pub fn with_duration(mut self, duration: MessageDuration) -> Self {
        self.duration = duration;
        self
    }

    /// Hands the message over. It goes out with the batch this call produces.
    pub fn show(self) {
        let handler_id = match self.on_action {
            Some(callback) => {
                let id = NEXT_ACTION_ID.with(|next| {
                    let id = next.get();
                    next.set(id.wrapping_add(1).max(ACTION_HANDLER_BASE));
                    id
                });
                ACTIONS.with_borrow_mut(|actions| {
                    while actions.len() >= LIVE_ACTIONS {
                        actions.pop_front();
                    }
                    actions.push_back((id, callback));
                });
                id
            }
            None => 0,
        };
        QUEUE.with_borrow_mut(|queue| {
            queue.push(PendingMessage {
                handler_id,
                text: self.text,
                action: self.action,
                duration: self.duration,
            });
        });
    }
}

/// Shows a message with no action, which is the common case.
pub fn show_message(text: impl Into<String>) {
    Message::new(text).show();
}

/// Hands every queued message to `emit` and empties the queue.
///
/// The buffer is moved out and put back, so a steady stream of messages reuses the same
/// allocation rather than asking for a new one each frame.
pub(crate) fn drain(mut emit: impl FnMut(&PendingMessage)) {
    let mut taken = QUEUE.with_borrow_mut(std::mem::take);
    if taken.is_empty() {
        return;
    }
    for message in &taken {
        emit(message);
    }
    taken.clear();
    QUEUE.with_borrow_mut(|queue| {
        if queue.is_empty() {
            *queue = taken;
        }
    });
}

/// The callback behind a message action id, removed as it is handed over.
///
/// Removed, because an action is a thing the user does once: the message goes away when it
/// is pressed, so a second press would be a press on something that is no longer there.
pub(crate) fn take_action(handler_id: u64) -> Option<Callback<()>> {
    if handler_id < ACTION_HANDLER_BASE {
        return None;
    }
    ACTIONS.with_borrow_mut(|actions| {
        let position = actions.iter().position(|(id, _)| *id == handler_id)?;
        actions.remove(position).map(|(_, callback)| callback)
    })
}

/// Forgets every queued message and every live action. Used between tests and when a new
/// `Host` takes over the thread.
#[doc(hidden)]
pub fn reset_messages() {
    QUEUE.with_borrow_mut(Vec::clear);
    ACTIONS.with_borrow_mut(VecDeque::clear);
    NEXT_ACTION_ID.with(|next| next.set(ACTION_HANDLER_BASE));
}
