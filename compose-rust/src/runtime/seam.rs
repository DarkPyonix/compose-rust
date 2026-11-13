//! The seam between the boundary and whatever builds the tree.
//!
//! The boundary entry points, the batch arena, the theme and window records, assets,
//! messages, notifications, streaming appends and the frame request all belong to this
//! crate and behave the same whatever authoring model produced the tree. What differs is
//! how a tree is built and changed: a Dioxus `VirtualDom` diffs one, a recomposition
//! runtime re-runs the scopes that read a changed state. That part is a [`Runtime`].
//!
//! An application registers one with [`LaunchBuilder::launch_runtime`], and every
//! `compose_rust_host_*` call is answered by a [`Host`] that drives it. Nothing about
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
        if let Some(overlay) = theme.overlay {
            self.write(Mutation::SetOverlayTokens(*overlay));
        }
    }

    /// Sets application-level overlay theme tokens for Dialog, Menu, Dropdown and Sheet.
    pub fn set_overlay_tokens(&mut self, tokens: crate::schema::OverlayTokens) {
        self.write(Mutation::SetOverlayTokens(tokens));
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

    /// Writes one clipboard write. It names no node: the clipboard is the platform's.
    pub fn set_clipboard_text(&mut self, text: &str) {
        self.write(Mutation::SetClipboardText { text });
    }

    /// Writes one clipboard read request. It names no node either.
    pub fn read_clipboard(&mut self, request: u32) {
        self.write(Mutation::ReadClipboard { request });
    }

    /// Writes one operation on a file the Renderer holds a handle for.
    pub(crate) fn file_call(&mut self, call: &crate::picked_file::Call) {
        self.write(Mutation::FileCall {
            request: call.request,
            op: call.op,
            create: call.create,
            handle: call.handle,
            name: &call.name,
            data: &call.data,
        });
    }

    /// Writes one storage operation for the Renderer to carry out. It names no node either.
    pub(crate) fn storage_call(&mut self, call: &crate::storage::RendererCall) {
        self.write(Mutation::StorageCall {
            request: call.request,
            op: call.op,
            store: &call.store,
            key: &call.key,
            data: &call.data,
        });
    }

    /// Lets the Renderer go of a file handle nothing on this side names any more.
    pub(crate) fn release_file_handle(&mut self, handle: u32) {
        self.write(Mutation::ReleaseFileHandle { handle });
    }

    /// Writes one request to scroll the container `node_id` to an offset in physical
    /// pixels. An axis left `None` is not touched; a request that names neither axis is
    /// refused when the batch is finished.
    ///
    /// A request, not a command: the Renderer owns the position. It keeps the offset
    /// inside what the content allows, ignores the request while the user is dragging or
    /// flinging, and says where the container ended up in `ScrollChanged`.
    pub fn scroll_to(&mut self, node_id: u32, request: crate::schema::ScrollTo) {
        use crate::schema::scroll_to_flags as flag;
        let mut flags = 0;
        if request.animate {
            flags |= flag::ANIMATE;
        }
        if request.x_px.is_some() {
            flags |= flag::X;
        }
        if request.y_px.is_some() {
            flags |= flag::Y;
        }
        let x_px = request.x_px.unwrap_or(0.0);
        let y_px = request.y_px.unwrap_or(0.0);
        if flags & (flag::X | flag::Y) == 0 || !x_px.is_finite() || !y_px.is_finite() {
            // The Renderer would refuse it with a protocol error after the batch had
            // crossed. Refusing it here names the mistake where it was made.
            self.fail(ProtocolError::InvalidValueKind(0));
            return;
        }
        self.write(Mutation::ScrollTo {
            node_id,
            item: request.item,
            flags,
            x_px,
            y_px,
        });
    }

    /// Writes one request to bring a rectangle into view in the container `node_id`. One
    /// request moves one container: to show something inside nested containers, write one
    /// for each of them, innermost first.
    pub fn scroll_into_view(&mut self, node_id: u32, request: crate::schema::ScrollIntoView) {
        use crate::schema::{ScrollTarget, scroll_into_view_flags, scroll_target_kind};
        let (target_kind, target, rect) = match request.target {
            ScrollTarget::Content(rect) => (scroll_target_kind::CONTENT, 0, rect),
            ScrollTarget::Node(node, rect) => (scroll_target_kind::NODE, node, rect),
            ScrollTarget::Item(item, rect) => (scroll_target_kind::ITEM, item, rect),
        };
        let numbers = [rect.x_px, rect.y_px, rect.width_px, rect.height_px];
        if numbers.iter().any(|value| !value.is_finite())
            || rect.width_px < 0.0
            || rect.height_px < 0.0
        {
            self.fail(ProtocolError::InvalidValueKind(0));
            return;
        }
        self.write(Mutation::ScrollIntoView {
            node_id,
            target,
            target_kind,
            align_x: request.align_x as u16,
            align_y: request.align_y as u16,
            flags: if request.animate {
                scroll_into_view_flags::ANIMATE
            } else {
                0
            },
            rect,
        });
    }

    /// Writes one file dialog request. Like a notification it names no node: the dialog is
    /// not in the tree, and `window` only says which window it is modal for.
    pub(crate) fn file_dialog(&mut self, request: &crate::file_dialog::Request) {
        self.write(Mutation::ShowFileDialog {
            request: request.id,
            window: request.window,
            kind: request.kind,
            default_filter: request.default_filter,
            title: &request.title,
            directory: &request.directory,
            name: &request.name,
            filters: &request.filters,
        });
    }

    /// Writes one edit the application asked of a code editor: replace `range` of the
    /// document as it stood at `base_version` with `text`. The Renderer moves it past what
    /// the reader typed since, or refuses it and reports `request_id` back.
    pub fn edit_code(
        &mut self,
        node_id: u32,
        request_id: u32,
        base_version: u32,
        range: crate::code::CodeRange,
        text: &str,
    ) {
        self.write(Mutation::EditCode {
            node_id,
            request_id,
            base_version,
            start_line: range.start.line,
            start_column: range.start.column,
            end_line: range.end.line,
            end_column: range.end.column,
            text,
        });
    }

    /// Writes one request to put a code editor's selection between two places in the
    /// document as it stood at `base_version`. The Renderer moves both ends past what the
    /// reader typed since and drops the request, with a protocol error, where an end is not
    /// a place in the document.
    pub fn set_selection(
        &mut self,
        node_id: u32,
        base_version: u32,
        anchor: crate::code::Position,
        active: crate::code::Position,
    ) {
        self.write(Mutation::SetSelection {
            node_id,
            base_version,
            anchor_line: anchor.line,
            anchor_column: anchor.column,
            active_line: active.line,
            active_column: active.column,
        });
    }

    /// Writes one request to scroll a code editor until `range`, as the document stood at
    /// `base_version`, is on screen. A range inside a folded stretch unfolds it.
    pub fn reveal_range(
        &mut self,
        node_id: u32,
        base_version: u32,
        range: crate::code::CodeRange,
        align: crate::schema::RevealAlign,
    ) {
        self.write(Mutation::RevealRange {
            node_id,
            base_version,
            start_line: range.start.line,
            start_column: range.start.column,
            end_line: range.end.line,
            end_column: range.end.column,
            align,
        });
    }

    /// Writes one request to fold or unfold the stretch of a code editor that starts on
    /// `start_line`.
    pub fn set_folded(&mut self, node_id: u32, start_line: u32, collapsed: bool) {
        self.write(Mutation::SetFolded {
            node_id,
            start_line,
            collapsed,
        });
    }

    /// Writes one request to scroll a code editor so that `offset_px` physical pixels of its
    /// document are above the top of its view. The offset is the unit `CodeScrolled`
    /// reports, so what one editor reported can be given to another to keep the two level.
    /// The Renderer keeps it inside what the document allows.
    pub fn set_scroll_offset(&mut self, node_id: u32, offset_px: f64) {
        self.write(Mutation::SetScrollOffset { node_id, offset_px });
    }

    /// Appends the streamed tail to a Text node without resending its whole value.
    pub fn append_text_node(&mut self, node_id: u32, text: &str) {
        self.write(Mutation::AppendText { node_id, text });
    }

    /// Requests input focus for `node_id`, or clears focus when `node_id` is 0.
    pub fn request_focus(&mut self, node_id: u32) {
        self.write(Mutation::RequestFocus { node_id });
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
    ///
    /// `frame_time_nanos` is the time of the frame the Renderer is serving, when this call
    /// is serving one, and `None` when it answers an event. A runtime whose animations and
    /// timers run on the frame clock reads it here; one that has no clock of its own
    /// ignores it.
    fn render(&mut self, frame_time_nanos: Option<u64>);

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

    /// Whether `node_id` is a `Window` node. A window's size and zoom reports name
    /// it, and nothing else, so a runtime without windows has none.
    fn is_window(&self, node_id: u32) -> bool {
        let _ = node_id;
        false
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
