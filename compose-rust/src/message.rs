//! Transient messages: the one sentence an application says after something happened.
//!
//! A message is not part of the tree. It has a lifetime rather than a position, and that
//! lifetime belongs to the Renderer: how long it stays, where it sits, what it does when a
//! second one arrives while the first is still up. Modelling it as a node would mean the
//! Host holding "showing until four seconds from now" and building the tree again to
//! take it away, which is a render for an animation nobody asked Rust about.
//!
//! So a message is posted and forgotten. It rides out on the batch the current call
//! produces, in the same way every other record does.

use crate::schema::{MessageDuration, MessagePlacement};
use std::cell::{Cell, RefCell};
use std::collections::VecDeque;

/// One message, ready to be written into a batch.
pub(crate) struct PendingMessage {
    pub(crate) handler_id: u64,
    pub(crate) text: String,
    pub(crate) action: String,
    pub(crate) duration: MessageDuration,
    pub(crate) placement: Option<MessagePlacement>,
    pub(crate) offset_dp: Option<f32>,
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

/// What a message action runs. The Host runs it through the runtime, so it gets whatever
/// context that runtime's state writes need.
pub(crate) type Action = Box<dyn FnMut()>;

thread_local! {
    static QUEUE: RefCell<Vec<PendingMessage>> = const { RefCell::new(Vec::new()) };
    static ACTIONS: RefCell<VecDeque<(u64, Action)>> = const {
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
/// What it looks like is the design system's: Material calls it a snackbar, Apple a
/// transient overlay, GNOME a toast, the same way it decides what a `Dialog` looks like.
/// Where it appears is the design system's too, unless the application asks for a place
/// with [`Message::with_placement`] or a distance from the edge with
/// [`Message::with_offset`].
pub struct Message {
    text: String,
    action: String,
    on_action: Option<Action>,
    duration: MessageDuration,
    placement: Option<MessagePlacement>,
    offset_dp: Option<f32>,
}

impl Message {
    pub fn new(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            action: String::new(),
            on_action: None,
            duration: MessageDuration::Short,
            placement: None,
            offset_dp: None,
        }
    }

    /// Adds the one thing the user can do about the message, such as undoing it.
    ///
    /// The action runs on the UI thread, inside the runtime the application launched, so
    /// it can change the same state an event handler can.
    pub fn with_action(
        mut self,
        label: impl Into<String>,
        mut on_action: impl FnMut(()) + 'static,
    ) -> Self {
        self.action = label.into();
        self.on_action = Some(Box::new(move || on_action(())));
        self
    }

    pub fn with_duration(mut self, duration: MessageDuration) -> Self {
        self.duration = duration;
        self
    }

    /// Draws the message along `placement`'s edge, at `placement`'s end of it, rather than
    /// where the design system would put it.
    ///
    /// The place is measured from inside the safe area and the application's own bars, so
    /// a message at the bottom is above a bottom tab bar and one at the top is below the
    /// top bar.
    ///
    /// ```ignore
    /// Message::new("Copied")
    ///     .with_placement(MessagePlacement {
    ///         vertical: MessageVertical::Bottom,
    ///         horizontal: MessageHorizontal::Center,
    ///     })
    ///     .with_offset(96.0)
    ///     .show();
    /// ```
    pub fn with_placement(mut self, placement: MessagePlacement) -> Self {
        self.placement = Some(placement);
        self
    }

    /// How far the message is from the edge it is drawn along, in dp, in place of the
    /// design system's distance. It is measured from the safe area and the bars, as the
    /// placement is. A negative or non-finite distance is taken as zero, because a message
    /// pushed off the screen is a message nobody reads.
    pub fn with_offset(mut self, dp: f32) -> Self {
        self.offset_dp = Some(if dp.is_finite() { dp.max(0.0) } else { 0.0 });
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
                placement: self.placement,
                offset_dp: self.offset_dp,
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
pub(crate) fn take_action(handler_id: u64) -> Option<Action> {
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
