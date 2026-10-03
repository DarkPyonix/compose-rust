//! The seam between the boundary and whatever builds the tree.
//!
//! The boundary entry points, the batch arena, the theme and window records, assets,
//! messages, notifications, streaming appends and the frame request all belong to this
//! crate and behave the same whatever authoring model produced the tree. What differs is
//! how a tree is built and changed: a Dioxus `VirtualDom` diffs one, a recomposition
//! runtime re-runs the scopes that read a changed state. That part is a [`Runtime`].
//!
//! An application registers one with [`LaunchBuilder::launch_runtime`], and every
//! `dioxus_compose_host_*` call is answered by a [`Host`] that drives it. Nothing about
//! the boundary changes with the runtime: the same five entry points, the same records,
//! the same batch.
//!
//! [`LaunchBuilder::launch_runtime`]: crate::LaunchBuilder::launch_runtime
//! [`Host`]: crate::Host

use crate::protocol::{BatchEncoder, HostEvent, Mutation, ProtocolError};
use crate::schema::{AssetKind, MessageDuration, Selection, Theme, Window};
use std::task::{Context, Poll};

/// One batch, written by the runtime and by the Host into the same arena.
///
/// The Host opens it with [`Batch::begin`], writes the records that are its own (the theme,
/// the window, assets, messages, notifications, streamed tails) and has the runtime write
/// the tree's records between them, then closes it with [`Batch::finish`]. The bytes that
/// come back are what the boundary hands the Renderer.
///
/// A runtime has no fallible way to report a bad record from the middle of a diff, so
/// [`Batch::fail`] keeps the error and `finish` returns it in place of the batch.
pub struct Batch {
    encoder: BatchEncoder,
    error: Option<ProtocolError>,
}

impl Default for Batch {
    fn default() -> Self {
        Self::new()
    }
}

impl Batch {
    /// Sized so that an ordinary screen's batches never grow it: once warm, writing a
    /// frame allocates nothing.
    pub fn new() -> Self {
        Self {
            encoder: BatchEncoder::with_capacity(16 * 1024, 4 * 1024, 256),
            error: None,
        }
    }

    /// The batch arena, so a Renderer that reads Host memory through a mapped view can be
    /// handed one.
    pub fn arena(&self) -> (*const u8, usize) {
        self.encoder.arena()
    }

    /// Starts a new batch, discarding the previous one and any error it carried.
    pub fn begin(&mut self) {
        self.encoder.clear();
        self.error = None;
    }

    /// Closes the batch and returns its bytes, or the first error written into it.
    pub fn finish(&mut self) -> Result<&[u8], ProtocolError> {
        if let Some(error) = self.error.take() {
            return Err(error);
        }
        self.encoder.finish()
    }

    /// Writes one record. After an error nothing more is written: the batch is already
    /// going to be refused.
    pub fn write(&mut self, mutation: Mutation<'_>) {
        if self.error.is_none() {
            if let Err(error) = self.encoder.encode(&mutation) {
                self.error = Some(error);
            }
        }
    }

    /// Records an error found while building the batch. `finish` returns it.
    pub fn fail(&mut self, error: ProtocolError) {
        self.error = Some(error);
    }

    /// The root theme record. Written once per rebuild, never per frame.
    pub fn set_theme(&mut self, theme: Theme) {
        self.write(Mutation::SetTheme(theme));
    }

    /// The root window record. Written once per rebuild, never per frame.
    pub fn set_window(&mut self, window: Window) {
        self.write(Mutation::SetWindow(window));
    }

    pub fn set_text_node(&mut self, node_id: u32, text: &str, selection: Option<Selection>) {
        self.write(Mutation::SetText {
            node_id,
            text,
            selection,
        });
    }

    /// Copies one asset into the batch. The bytes ride behind the records, and the
    /// Renderer takes its own copy inside the call that carries them.
    pub fn register_asset(&mut self, asset_id: u32, kind: AssetKind, bytes: &[u8]) {
        self.write(Mutation::RegisterAsset {
            asset_id,
            kind,
            bytes,
        });
    }

    pub fn release_asset(&mut self, asset_id: u32) {
        self.write(Mutation::ReleaseAsset { asset_id });
    }

    /// Writes one transient message into the batch.
    ///
    /// It names no node, because a message is not in the tree: it is a sentence with a
    /// lifetime, and that lifetime belongs to the Renderer.
    pub fn show_message(
        &mut self,
        handler_id: u64,
        text: &str,
        action: &str,
        duration: MessageDuration,
    ) {
        self.write(Mutation::ShowMessage {
            handler_id,
            text,
            action,
            duration,
        });
    }

    /// Writes one notification command into the batch.
    ///
    /// Like a message it names no node: what it asks for happens outside the window, and
    /// the Renderer is the side that owns the platform it happens on.
    pub(crate) fn notification(&mut self, command: &crate::notification::NotificationCommand) {
        use crate::notification::NotificationCommand;
        match command {
            NotificationCommand::Post(notification) => {
                self.write(Mutation::PostNotification {
                    key: &notification.key,
                    title: &notification.title,
                    body: &notification.body,
                    channel: &notification.channel,
                    action_1: &notification.actions[0],
                    action_2: &notification.actions[1],
                    importance: notification.importance,
                    presentation: notification.presentation,
                });
            }
            NotificationCommand::Withdraw(key) => {
                self.write(Mutation::WithdrawNotification { key });
            }
            NotificationCommand::RequestPermission => {
                self.write(Mutation::RequestNotificationPermission);
            }
        }
    }

    /// Appends the streamed tail to a Text node without resending its whole value.
    pub fn append_text_node(&mut self, node_id: u32, text: &str) {
        self.write(Mutation::AppendText { node_id, text });
    }
}

/// What builds and changes the tree, seen from the boundary.
///
/// The Host owns everything that is not the tree and calls in here for the rest. Every
/// method runs on the Renderer's UI thread, inside a boundary call, between a
/// [`Batch::begin`] and a [`Batch::finish`] the Host makes on [`Runtime::batch_mut`], so a
/// runtime writes its records and returns; it never opens or closes a batch itself.
pub trait Runtime {
    /// The batch this runtime writes the tree's records into. The Host writes its own
    /// records into the same one, before and after the runtime's.
    fn batch(&self) -> &Batch;

    fn batch_mut(&mut self) -> &mut Batch;

    /// Writes the whole tree. Called once on a fresh runtime, which is also what a resync
    /// gets: the Host makes a new runtime and rebuilds it.
    fn rebuild(&mut self);

    /// Writes whatever changed since the last call: state a worker wrote, a scope that a
    /// size class or a design system woke, the consequences of an event.
    fn render(&mut self);

    /// Runs the handler an event names, and nothing else: the Host renders afterwards.
    ///
    /// Returns the value the boundary hands back as the call's result, which is how a key
    /// handler says it consumed the key. An event naming a handler or a node this runtime
    /// does not have is an error, and so is a payload that no handler can receive.
    fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError>;

    /// The application's name for a node it asked to have measured, if it asked.
    fn size_token(&self, node_id: u32) -> Option<u32> {
        let _ = node_id;
        None
    }

    /// Whether the runtime has work waiting that a frame should run. A runtime that is
    /// woken by something outside a boundary call (a timer, a task it spawned) wakes the
    /// context's waker, and the Host turns that into a frame request.
    fn poll_work(&mut self, context: &mut Context<'_>) -> Poll<()> {
        let _ = context;
        Poll::Pending
    }

    /// Runs code the application handed to the Host, a message's action for one, inside
    /// whatever context this runtime's state writes need.
    fn run_in_context(&mut self, action: &mut dyn FnMut()) {
        action();
    }
}
