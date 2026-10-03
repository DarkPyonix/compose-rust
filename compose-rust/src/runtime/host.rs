//! The Host for a composable application: the same three calls the Dioxus Host answers,
//! answered by the slot table runtime.

use super::Runtime;
use super::composer::{Composer, EventCallback, ROOT};
use super::{drain_graveyard, effects, state, with_composer};
use crate::boundary::{
    EventDispatchGuard, HostCallGuard, PendingAppend, begin_render_frame, flush_messages,
    flush_notifications, flush_pending_appends, queue_append, request_frame_from_worker,
    set_lifecycle_running,
};
use crate::protocol::{HostEvent, ProtocolError, decode_event};
use crate::schema::{AssetKind, EventPayload, IconRole, Theme};
use crate::{KeyEvent, RangeRequest, Selection};
use std::rc::Rc;

/// The Host of a composable application.
///
/// It owns the composition and the batch arena, and it is what the boundary calls on the
/// UI thread. An application never holds one: [`crate::application`] builds it on the
/// thread that draws. Tests and benchmarks build one directly, which is why it is public.
pub struct ComposeHost {
    /// Kept so the composition can be built again from nothing when the Renderer asks for
    /// a resync.
    content: Rc<dyn Fn()>,
    theme: Theme,
    window: crate::schema::Window,
    runtime: Rc<Runtime>,
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
        Self {
            content,
            theme,
            window: crate::boundary::launched_window(),
            runtime: Runtime::new(),
            pending_appends: Vec::new(),
        }
    }

    /// The batch arena, reported to the Renderer on every boundary call.
    pub fn arena(&self) -> (*const u8, usize) {
        self.runtime.composer.borrow().writer.arena()
    }

    fn writer_call<R>(&self, f: impl FnOnce(&mut Composer) -> R) -> R {
        f(&mut self.runtime.composer.borrow_mut())
    }

    /// The first batch: the theme, the window, and the whole composition.
    pub fn rebuild(&mut self) -> Result<&[u8], ProtocolError> {
        let _call = HostCallGuard::enter();
        let _runtime = self.runtime.enter();
        let (theme, window) = (self.theme, self.window);
        with_composer(|composer| {
            composer.abandon_composition();
            composer.writer.begin_frame();
            // One record at the root, before any node exists, and beside it the window.
            composer.writer.set_theme(theme);
            composer.writer.set_window(window);
            composer.begin_root();
        });
        (self.content)();
        with_composer(Composer::end_root);
        self.after_composition();
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

    /// Delivers one event: runs its handler, then the scopes the handler invalidated, and
    /// answers with the batch and the handler's result.
    pub fn dispatch(&mut self, event: HostEvent<'_>) -> Result<(&[u8], i64), ProtocolError> {
        let _call = HostCallGuard::enter();
        let _runtime = self.runtime.enter();
        self.writer_call(|composer| composer.writer.begin_frame());
        let mut result = 0;
        match event.payload {
            EventPayload::Resync => {
                drop(_runtime);
                return self.resync();
            }
            EventPayload::LifecycleStart | EventPayload::LifecycleStop => {
                set_lifecycle_running(matches!(event.payload, EventPayload::LifecycleStart));
                // An empty batch: starting again is the Renderer's cue to draw, and it
                // asks for that frame itself.
                return Ok((self.finish()?, 0));
            }
            EventPayload::DesignSystemResolved(system) => {
                crate::design::publish(system);
                let state = with_composer(|composer| composer.ambient.design.clone());
                if let Some(state) = state {
                    state.set(system);
                }
            }
            EventPayload::NotificationPermissionChanged(permission) => {
                if event.node_id != 0 || event.handler_id != 0 {
                    return Err(ProtocolError::InvalidValueKind(0));
                }
                crate::notification::publish_permission(permission);
                let state = with_composer(|composer| composer.ambient.permission.clone());
                if let Some(state) = state {
                    state.set(permission);
                }
            }
            EventPayload::NotificationActivated { action, key } => {
                if event.node_id != 0 || event.handler_id != 0 {
                    return Err(ProtocolError::InvalidValueKind(0));
                }
                let _dispatch = EventDispatchGuard::enter();
                crate::notification::activate(crate::notification::NotificationActivation {
                    key: key.to_owned(),
                    action,
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
                    let state = with_composer(|composer| composer.ambient.window.clone());
                    if let Some(state) = state {
                        state.set(size);
                    }
                } else {
                    let token = with_composer(|composer| composer.writer.size_token(event.node_id))
                        .flatten();
                    if let Some(token) = token {
                        crate::window::publish_node(token, size);
                        let state = with_composer(|composer| {
                            composer.ambient.node_sizes.get(&token).cloned()
                        })
                        .flatten();
                        if let Some(state) = state {
                            state.set(size);
                        }
                    }
                }
            }
            EventPayload::ProtocolError { .. } => return Err(ProtocolError::InvalidValueKind(0)),
            payload => {
                // The action on a transient message belongs to no node: the message is not
                // in the tree.
                if let EventPayload::Clicked = payload {
                    if let Some(action) = crate::message::take_action(event.handler_id) {
                        if event.node_id != 0 {
                            return Err(ProtocolError::InvalidValueKind(0));
                        }
                        let _dispatch = EventDispatchGuard::enter();
                        action.call();
                        drop(_dispatch);
                        self.after_event();
                        return Ok((self.finish()?, 0));
                    }
                }
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
                let _dispatch = EventDispatchGuard::enter();
                result = deliver(&callback, &payload)?;
            }
        }
        self.after_event();
        Ok((self.finish()?, result))
    }

    /// Serves one frame: delivers the frame time to whatever waits for it, runs the
    /// futures that woke, and recomposes what the states written since the last call
    /// invalidated.
    pub fn render_frame(&mut self, frame_time_nanos: u64) -> Result<&[u8], ProtocolError> {
        let _call = HostCallGuard::enter();
        let _runtime = self.runtime.enter();
        self.writer_call(|composer| composer.writer.begin_frame());
        if !begin_render_frame() {
            // Off screen: only queued notifications leave, and nothing is composed.
            self.writer_call(|composer| flush_notifications(&mut composer.writer));
            return self.writer_finish();
        }
        with_composer(|composer| composer.executor.deliver_frame(frame_time_nanos));
        effects::poll_woken();
        self.recompose();
        {
            let mut composer = self.runtime.composer.borrow_mut();
            flush_pending_appends(&mut self.pending_appends, &mut composer.writer);
        }
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
        self.writer_call(|composer| {
            composer.writer.begin_frame();
            composer.writer.register_asset(asset_id, kind, bytes);
        });
        self.writer_finish()
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
        self.writer_call(|composer| {
            composer.writer.begin_frame();
            composer.writer.release_asset(asset_id);
        });
        self.writer_finish()
    }

    /// Replaces an uncontrolled text field's contents from the Host's side.
    pub fn set_text(
        &mut self,
        node_id: u32,
        text: &str,
        selection: Option<Selection>,
    ) -> Result<&[u8], ProtocolError> {
        self.writer_call(|composer| {
            composer.writer.begin_frame();
            composer.writer.set_text_node(node_id, text, selection);
        });
        self.writer_finish()
    }

    /// After a handler: what it invalidated is recomposed in this same call, so the
    /// interaction is one batch.
    fn after_event(&mut self) {
        effects::poll_woken();
        self.recompose();
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

    fn finish(&mut self) -> Result<&[u8], ProtocolError> {
        {
            let mut composer = self.runtime.composer.borrow_mut();
            flush_messages(&mut composer.writer, &mut self.theme);
        }
        self.writer_finish()
    }

    fn writer_finish(&mut self) -> Result<&[u8], ProtocolError> {
        // The batch lives in the composer's arena, which lives as long as the Host and is
        // only cleared by the next call, so it is handed out for as long as `self` is
        // borrowed, the same as the Dioxus Host's.
        let composer = self.runtime.composer.as_ptr();
        // SAFETY: the RefCell is not borrowed here (every borrow above has ended), and the
        // returned slice borrows `self` mutably, so no other call can reach the composer
        // and clear the arena while the slice is alive.
        unsafe { (*composer).writer.finish_frame() }
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
