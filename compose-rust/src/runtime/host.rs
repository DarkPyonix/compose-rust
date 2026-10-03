//! The slot table runtime as the boundary sees it, in two halves.
//!
//! [`Recomposer`] is the part that builds and changes the tree: it composes the root,
//! recomposes what a state change invalidated, and runs the handler an event names. It
//! writes the tree's records and nothing else, so it can sit behind any Host that owns
//! the rest of a batch. That is the same division the Dioxus adapter has, where a
//! `VirtualDom` builds the tree and the Host owns the theme, the window, assets, messages,
//! notifications and the frame clock.
//!
//! [`ComposeHost`] is that rest, for a composable application: the Host the boundary
//! calls. It reports the window, the design system and the notification permission
//! through the same modules the Dioxus Host reports them through, and the Recomposer
//! hears about them the way a Dioxus hook does, by subscribing.

use super::Composition;
use super::composer::{Composer, EventCallback, ROOT};
use super::{drain_graveyard, effects, state, with_composer};
use crate::boundary::{
    EventDispatchGuard, HostCallGuard, PendingAppend, begin_render_frame, flush_messages,
    flush_notifications, flush_pending_appends, queue_append, request_frame_from_worker,
    set_lifecycle_running,
};
use crate::protocol::{HostEvent, ProtocolError, decode_event};
use crate::schema::{AssetKind, EventPayload, IconRole, Theme};
use crate::writer::NodeWriter;
use crate::{KeyEvent, RangeRequest, Selection};
use std::any::Any;
use std::rc::Rc;
use std::sync::Arc;

/// The slot table runtime for one root: what composes the tree and runs its handlers.
///
/// Every method enters the composition for its own length, so it may be called from any
/// Host call on the UI thread. The tree's records go into [`Recomposer::writer`]'s batch,
/// between whatever the Host writes before and after them.
pub struct Recomposer {
    content: Rc<dyn Fn()>,
    composition: Rc<Composition>,
    /// What the Renderer reports, followed the way a Dioxus hook follows it, for as long
    /// as this composition lives.
    _subscriptions: Vec<Box<dyn Any>>,
}

impl Recomposer {
    /// A runtime for `content`, composed by [`Recomposer::rebuild`].
    pub fn new(content: impl Fn() + 'static) -> Self {
        let composition = Composition::new();
        let (window, design, permission) = {
            let composer = composition.composer.borrow();
            (
                composer.ambient.window.clone(),
                composer.ambient.design.clone(),
                composer.ambient.permission.clone(),
            )
        };
        // The values themselves stay in the modules the Host publishes them to. A state
        // per value is what lets a composable read one and be run again when it moves,
        // and the subscription is what moves it.
        let subscriptions: Vec<Box<dyn Any>> = vec![
            Box::new(crate::window::WindowSizeSubscription::new(Arc::new(
                move || window.set(crate::window::window_size()),
            ))),
            Box::new(crate::design::DesignSystemSubscription::new(Arc::new(
                move || design.set(crate::design::design_system()),
            ))),
            Box::new(crate::notification::PermissionSubscription::new(Arc::new(
                move || permission.set(crate::notification::notification_permission()),
            ))),
        ];
        Self {
            content: Rc::new(content),
            composition,
            _subscriptions: subscriptions,
        }
    }

    /// The node table and the batch the tree's records go into.
    pub(crate) fn writer<R>(&self, f: impl FnOnce(&mut NodeWriter) -> R) -> R {
        f(&mut self.composition.composer.borrow_mut().writer)
    }

    /// The batch arena.
    pub fn arena(&self) -> (*const u8, usize) {
        self.composition.composer.borrow().writer.arena()
    }

    /// The application's name for a node it asked to have measured, if it asked.
    pub fn size_token(&self, node_id: u32) -> Option<u32> {
        self.composition
            .composer
            .borrow()
            .writer
            .size_token(node_id)
    }

    /// Composes the whole tree. Called once, on a fresh runtime.
    pub fn rebuild(&mut self) {
        let _composition = self.composition.enter();
        with_composer(|composer| {
            composer.abandon_composition();
            composer.begin_root();
        });
        (self.content)();
        with_composer(Composer::end_root);
        self.after_composition();
    }

    /// Writes whatever changed since the last call: a frame's time to whatever waits for
    /// one, the futures that woke, and the scopes the states written since invalidated.
    pub fn render(&mut self, frame_time_nanos: Option<u64>) {
        let _composition = self.composition.enter();
        if let Some(now) = frame_time_nanos {
            with_composer(|composer| composer.executor.deliver_frame(now));
        }
        effects::poll_woken();
        self.recompose();
    }

    /// Runs the handler an event names, and nothing else; the caller renders afterwards.
    ///
    /// Returns what the boundary hands back as the call's result, which is how a key
    /// handler says it consumed the key. An event naming a handler or node this
    /// composition does not have is an error, and so is a payload no handler can take.
    pub fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        let _composition = self.composition.enter();
        let Some((node, callback)) = with_composer(|composer| {
            composer
                .handlers
                .get(&event.handler_id)
                .map(|entry| (entry.node, entry.callback.clone()))
        })
        .flatten() else {
            return Err(ProtocolError::InvalidValueKind(0));
        };
        if node != event.node_id {
            return Err(ProtocolError::InvalidValueKind(0));
        }
        deliver(&callback, &event.payload)
    }

    /// Runs code the application handed to the Host, a message's action for one, with
    /// this composition current, so the states it writes are picked up by the next
    /// render.
    pub fn run_in_context(&mut self, action: &mut dyn FnMut()) {
        let _composition = self.composition.enter();
        action();
    }

    /// One round of recomposition: the states written since the last round mark their
    /// readers, and the readers run, outermost first.
    ///
    /// A state written while this round composes is left for the next frame, which the
    /// write asks for: composing describes the screen, and a description that changes
    /// what it describes is a loop the frame clock should pace, not this call.
    fn recompose(&mut self) {
        with_composer(Composer::abandon_composition);
        state::apply_state_changes();
        let root = with_composer(|composer| composer.root_invalid()).unwrap_or(false);
        if root {
            with_composer(Composer::begin_root);
            (self.content)();
            with_composer(Composer::end_root);
        }
        let invalid = with_composer(Composer::take_invalid).unwrap_or_default();
        for group in invalid {
            if group == ROOT {
                continue;
            }
            let restart = with_composer(|composer| composer.prepare_restart(group)).flatten();
            if let Some(restart) = restart {
                restart();
                with_composer(Composer::finish_restart);
            }
        }
        self.after_composition();
    }

    fn after_composition(&mut self) {
        drain_graveyard();
        effects::apply_effects();
        drain_graveyard();
        effects::poll_woken();
        // Whatever was written during this round, by the composition, an effect or a
        // future, is the next frame's.
        let pending = with_composer(|composer| {
            !composer
                .signal
                .dirty
                .lock()
                .unwrap_or_else(|poison| poison.into_inner())
                .is_empty()
                || !composer.invalid.is_empty()
        })
        .unwrap_or(false);
        if pending {
            request_frame_from_worker();
        }
    }

    /// Closes the batch. The bytes live in the composition's arena until the next call
    /// begins a batch, so they are handed out for as long as `self` is borrowed.
    fn finish(&mut self) -> Result<&[u8], ProtocolError> {
        let composer = self.composition.composer.as_ptr();
        // SAFETY: no borrow of the RefCell is alive here, and the slice borrows `self`
        // mutably, so nothing can reach the composer and clear the arena while it lives.
        unsafe { (*composer).writer.finish_frame() }
    }
}

/// The Host of a composable application.
///
/// The boundary builds one on the UI thread for an application launched with
/// [`crate::launch`] or [`crate::application`]. Tests and benchmarks build one directly,
/// which is why it is public.
pub struct ComposeHost {
    /// Kept so the composition can be built again from nothing when the Renderer asks for
    /// a resync.
    content: Rc<dyn Fn()>,
    theme: Theme,
    window: crate::schema::Window,
    runtime: Recomposer,
    pending_appends: Vec<PendingAppend>,
}

impl ComposeHost {
    /// The Host for `content`, in the theme the application launched with.
    pub fn new(content: fn()) -> Self {
        Self::with_theme(content, crate::boundary::launched_theme())
    }

    /// The same, in the given theme.
    pub fn with_theme(content: fn(), theme: Theme) -> Self {
        Self::from_content(Rc::new(content), theme)
    }

    /// A Host for a closure rather than a function, for tests that compose something
    /// built at run time.
    pub fn with_content(content: impl Fn() + 'static) -> Self {
        Self::from_content(Rc::new(content), crate::boundary::launched_theme())
    }

    fn from_content(content: Rc<dyn Fn()>, theme: Theme) -> Self {
        // The same resets the Dioxus Host makes, for the same reasons: a fresh Host has
        // not been measured, queued messages belong to the Host it replaces, and the
        // Renderer it is about to talk to has an empty asset cache.
        crate::window::reset_window_size();
        crate::message::reset_messages();
        crate::asset::requeue_all();
        crate::theme::install(theme);
        let root = Rc::clone(&content);
        Self {
            content,
            theme,
            window: crate::boundary::launched_window(),
            runtime: Recomposer::new(move || root()),
            pending_appends: Vec::new(),
        }
    }

    /// The batch arena, reported to the Renderer on every boundary call.
    pub fn arena(&self) -> (*const u8, usize) {
        self.runtime.arena()
    }

    /// The first batch: the theme, the window, and the whole composition.
    pub fn rebuild(&mut self) -> Result<&[u8], ProtocolError> {
        let _call = HostCallGuard::enter();
        let (theme, window) = (self.theme, self.window);
        self.runtime.writer(|writer| {
            writer.begin_frame();
            // One record at the root, before any node exists, and beside it the window.
            writer.set_theme(theme);
            writer.set_window(window);
        });
        self.runtime.rebuild();
        self.finish()
    }

    /// Answers with the whole tree, for a Renderer that no longer has a node table. The
    /// composition is built again from nothing, so remembered state starts over.
    pub fn resync(&mut self) -> Result<(&[u8], i64), ProtocolError> {
        let content = Rc::clone(&self.content);
        let theme = self.theme;
        *self = Self::from_content(content, theme);
        Ok((self.rebuild()?, 0))
    }

    pub fn dispatch_event(&mut self, bytes: &[u8]) -> Result<(&[u8], i64), ProtocolError> {
        let event = decode_event(bytes)?;
        self.dispatch(event)
    }

    /// Delivers one event: runs its handler, then the scopes it invalidated, and answers
    /// with the batch and the handler's result.
    pub fn dispatch(&mut self, event: HostEvent<'_>) -> Result<(&[u8], i64), ProtocolError> {
        let _call = HostCallGuard::enter();
        self.runtime.writer(NodeWriter::begin_frame);
        let mut result = 0;
        match event.payload {
            EventPayload::Resync => return self.resync(),
            EventPayload::LifecycleStart | EventPayload::LifecycleStop => {
                set_lifecycle_running(matches!(event.payload, EventPayload::LifecycleStart));
                // An empty batch: starting again is the Renderer's cue to draw, and it
                // asks for that frame itself.
                return Ok((self.finish()?, 0));
            }
            // These address the Host itself. Publishing wakes whatever subscribed, which
            // here is the composition's state for that value.
            EventPayload::DesignSystemResolved(system) => {
                crate::design::publish(system);
            }
            EventPayload::NotificationPermissionChanged(permission) => {
                if event.node_id != 0 || event.handler_id != 0 {
                    return Err(ProtocolError::InvalidValueKind(0));
                }
                crate::notification::publish_permission(permission);
            }
            EventPayload::NotificationActivated { action, key } => {
                if event.node_id != 0 || event.handler_id != 0 {
                    return Err(ProtocolError::InvalidValueKind(0));
                }
                let _dispatch = EventDispatchGuard::enter();
                let activation = crate::notification::NotificationActivation {
                    key: key.to_owned(),
                    action,
                };
                self.runtime.run_in_context(&mut || {
                    crate::notification::activate(activation.clone());
                });
            }
            EventPayload::WindowSizeChanged {
                width_dp,
                height_dp,
                class,
                height_class,
            } => {
                let size = crate::window::WindowSize::new(width_dp, height_dp, class, height_class);
                if event.node_id == 0 {
                    crate::window::publish(size);
                } else if let Some(token) = self.runtime.size_token(event.node_id) {
                    crate::window::publish_node(token, size);
                }
            }
            EventPayload::ProtocolError { .. } => return Err(ProtocolError::InvalidValueKind(0)),
            EventPayload::Clicked if crate::message::has_action(event.handler_id) => {
                // The action on a transient message belongs to no node: the message is not
                // in the tree.
                if event.node_id != 0 {
                    return Err(ProtocolError::InvalidValueKind(0));
                }
                if let Some(action) = crate::message::take_action(event.handler_id) {
                    let _dispatch = EventDispatchGuard::enter();
                    self.runtime.run_in_context(&mut || action.call());
                }
            }
            _ => {
                let _dispatch = EventDispatchGuard::enter();
                result = self.runtime.handle_event(&event)?;
            }
        }
        self.runtime.render(None);
        Ok((self.finish()?, result))
    }

    /// Serves one frame: delivers the frame time to whatever waits for it, runs the
    /// futures that woke, and recomposes what the states written since the last call
    /// invalidated.
    pub fn render_frame(&mut self, frame_time_nanos: u64) -> Result<&[u8], ProtocolError> {
        let _call = HostCallGuard::enter();
        self.runtime.writer(NodeWriter::begin_frame);
        if !begin_render_frame() {
            // Off screen: only queued notifications leave, and nothing is composed.
            self.runtime.writer(flush_notifications);
            return self.runtime.finish();
        }
        self.runtime.render(Some(frame_time_nanos));
        let pending = &mut self.pending_appends;
        self.runtime
            .writer(|writer| flush_pending_appends(pending, writer));
        self.finish()
    }

    /// Queues a streamed tail for the next frame, merged with whatever else arrives for
    /// the same node before it.
    pub fn append_text(&mut self, node_id: u32, tail: &str) {
        queue_append(&mut self.pending_appends, node_id, tail);
    }

    /// Registers one asset and returns the batch that carries it.
    pub fn register_asset(
        &mut self,
        asset_id: u32,
        kind: AssetKind,
        bytes: &[u8],
    ) -> Result<&[u8], ProtocolError> {
        self.runtime.writer(|writer| {
            writer.begin_frame();
            writer.register_asset(asset_id, kind, bytes);
        });
        self.runtime.finish()
    }

    /// Registers an icon by the meaning it carries.
    pub fn register_icon(&mut self, asset_id: u32, role: IconRole) -> Result<&[u8], ProtocolError> {
        self.register_asset(
            asset_id,
            AssetKind::VectorIcon,
            &(role as u16).to_le_bytes(),
        )
    }

    /// Drops the asset from the Renderer's cache.
    pub fn release_asset(&mut self, asset_id: u32) -> Result<&[u8], ProtocolError> {
        self.runtime.writer(|writer| {
            writer.begin_frame();
            writer.release_asset(asset_id);
        });
        self.runtime.finish()
    }

    /// Replaces an uncontrolled text field's contents from the Host's side.
    pub fn set_text(
        &mut self,
        node_id: u32,
        text: &str,
        selection: Option<Selection>,
    ) -> Result<&[u8], ProtocolError> {
        self.runtime.writer(|writer| {
            writer.begin_frame();
            writer.set_text_node(node_id, text, selection);
        });
        self.runtime.finish()
    }

    /// Writes what the application said during this call and closes the batch.
    fn finish(&mut self) -> Result<&[u8], ProtocolError> {
        let theme = &mut self.theme;
        self.runtime.writer(|writer| flush_messages(writer, theme));
        self.runtime.finish()
    }
}

/// Calls a handler with an event's payload, converted to what the handler takes.
fn deliver(callback: &EventCallback, payload: &EventPayload<'_>) -> Result<i64, ProtocolError> {
    match (callback, payload) {
        (
            EventCallback::Unit(handler),
            EventPayload::Clicked | EventPayload::FocusLost | EventPayload::FilesEntered,
        ) => (&mut *handler.borrow_mut())(),
        (EventCallback::Text(handler), EventPayload::TextChanged(text))
        | (EventCallback::Text(handler), EventPayload::TextSubmitted(text)) => {
            (&mut *handler.borrow_mut())((*text).to_owned())
        }
        (
            EventCallback::Key(handler),
            EventPayload::KeyDown {
                key,
                shift_key,
                ctrl_key,
                alt_key,
                meta_key,
            },
        ) => {
            let event = KeyEvent::new(*key, *shift_key, *ctrl_key, *alt_key, *meta_key);
            (&mut *handler.borrow_mut())(event.clone());
            return Ok(i64::from(event.consumed()));
        }
        (EventCallback::Value(handler), EventPayload::ValueChanged(value)) => {
            (&mut *handler.borrow_mut())(*value)
        }
        (EventCallback::Range(handler), EventPayload::RangeRequested { start, count }) => {
            (&mut *handler.borrow_mut())(RangeRequest::new(*start, *count))
        }
        (EventCallback::Files(handler), EventPayload::FilesDropped(paths)) => {
            (&mut *handler.borrow_mut())(crate::FileDrop::new(paths))
        }
        _ => return Err(ProtocolError::InvalidValueKind(0)),
    }
    Ok(0)
}

impl Composer {
    /// Drops any walk a panic left half done, so the next call starts from a clean stack.
    pub(crate) fn abandon_composition(&mut self) {
        self.clear_walk();
    }
}
