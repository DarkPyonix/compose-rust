//! The slot table runtime as the boundary sees it, in two halves.
//!
//! [`Recomposer`] is the part that builds and changes the tree: it composes the root,
//! recomposes what a state change invalidated, and runs the handler an event names. It
//! writes the tree's records and nothing else, so it can sit behind any Host that owns
//! the rest of a batch. That is the same division the Dioxus adapter has, where a
//! `VirtualDom` builds the tree and the Host owns the theme, the window, assets, messages,
//! notifications and the frame clock.
//!
//! That rest is compose-rust's own [`Host`], which drives any [`Runtime`]; the Recomposer
//! is one. The window, the design system and the notification permission reach the
//! composition the way they reach a Dioxus hook, by subscribing to the modules the Host
//! publishes them to. [`ComposeHost`] is the Host with a Recomposer in it.

use super::Composition;
use super::composer::{Composer, EventCallback, ROOT};
use super::seam::{Batch, Runtime};
use super::{drain_graveyard, effects, state, with_composer};
use crate::boundary::{Host, request_frame_from_worker};
use crate::protocol::{HostEvent, ProtocolError};
use crate::schema::{EventPayload, Theme};
use crate::{KeyEvent, RangeRequest};
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
            Box::new(crate::window::subscribe(Arc::new(move || {
                window.set(crate::window::window_size())
            }))),
            Box::new(crate::design::subscribe(Arc::new(move || {
                design.set(crate::design::design_system())
            }))),
            Box::new(crate::notification::subscribe_permission(Arc::new(
                move || permission.set(crate::notification::notification_permission()),
            ))),
        ];
        Self {
            content: Rc::new(content),
            composition,
            _subscriptions: subscriptions,
        }
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
}

/// The Host of a composable application: compose-rust's [`Host`] driving a
/// [`Recomposer`].
///
/// The boundary builds one on the UI thread for an application launched with
/// [`launch`]. Tests and benchmarks build one directly, which is why it is public; it
/// derefs to the [`Host`], whose calls are the boundary's.
pub struct ComposeHost(Host);

impl ComposeHost {
    /// The Host for `content`, in the theme the application launched with.
    pub fn new(content: fn()) -> Self {
        Self(Host::new(recomposer_for(content)))
    }

    /// The same, in the given theme.
    pub fn with_theme(content: fn(), theme: Theme) -> Self {
        Self(Host::with_theme(recomposer_for(content), theme))
    }

    /// A Host for a closure rather than a function, for tests that compose something
    /// built at run time. The closure is shared with the factory a resync calls, so it
    /// has to be `Send` and `Sync` like any other root.
    pub fn with_content(content: impl Fn() + Send + Sync + 'static) -> Self {
        let content = Arc::new(content);
        Self(Host::new(move || {
            let content = Arc::clone(&content);
            Box::new(Recomposer::new(move || content())) as Box<dyn Runtime>
        }))
    }
}

impl std::ops::Deref for ComposeHost {
    type Target = Host;

    fn deref(&self) -> &Host {
        &self.0
    }
}

impl std::ops::DerefMut for ComposeHost {
    fn deref_mut(&mut self) -> &mut Host {
        &mut self.0
    }
}

/// The runtime factory for one composable root: what [`crate::LaunchBuilder::launch_runtime`]
/// and the browser's start take.
pub fn recomposer_for(content: fn()) -> impl Fn() -> Box<dyn Runtime> + Send + Sync + 'static {
    move || Box::new(Recomposer::new(content)) as Box<dyn Runtime>
}

impl crate::LaunchBuilder {
    /// Runs a composable as the application. Does not return while it is running, and
    /// ends the process with a failing status if the renderer loop could not run at all.
    ///
    /// ```ignore
    /// #[composable]
    /// fn app() {
    ///     Text("Hello");
    /// }
    ///
    /// fn main() {
    ///     compose_rust::LaunchBuilder::new().launch(app);
    /// }
    /// ```
    pub fn launch(self, content: fn()) {
        self.launch_runtime(recomposer_for(content));
    }

    /// [`crate::LaunchBuilder::launch`] without the exit: the status the renderer loop
    /// ended with.
    pub fn try_launch(self, content: fn()) -> i32 {
        self.try_launch_runtime(recomposer_for(content))
    }

    /// The same as `launch`, under Compose Desktop's name for it.
    pub fn application(self, content: fn()) {
        self.launch(content);
    }
}

/// Runs a composable as the application, with every launch choice left to its default.
///
/// ```ignore
/// #[composable]
/// fn counter() {
///     let count = remember(|| mutable_state_of(0));
///     let more = count.clone();
///     Button(format!("{}", count.get())).on_click(move || more.update(|count| *count += 1));
/// }
///
/// fn main() {
///     compose_rust::launch(counter);
/// }
/// ```
pub fn launch(content: fn()) {
    crate::LaunchBuilder::new().launch(content);
}

/// The same as [`launch`], under Compose Desktop's name for it.
pub fn application(content: fn()) {
    launch(content);
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

impl Runtime for Recomposer {
    fn batch(&self) -> &Batch {
        let composer = self.composition.composer.as_ptr();
        // SAFETY: the composer is only borrowed inside this runtime's own calls, which take
        // `&mut self`, so no borrow is alive while the Host holds this reference, and the
        // reference borrows `self`, so no runtime call can start while it lives.
        unsafe { (*composer).writer.batch() }
    }

    fn batch_mut(&mut self) -> &mut Batch {
        let composer = self.composition.composer.as_ptr();
        // SAFETY: as for `batch`, with `&mut self` making this the only reference.
        unsafe { (*composer).writer.batch_mut() }
    }

    fn rebuild(&mut self) {
        Recomposer::rebuild(self);
    }

    fn render(&mut self, frame_time_nanos: Option<u64>) {
        Recomposer::render(self, frame_time_nanos);
    }

    fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        Recomposer::handle_event(self, event)
    }

    fn size_token(&self, node_id: u32) -> Option<u32> {
        Recomposer::size_token(self, node_id)
    }

    fn run_in_context(&mut self, action: &mut dyn FnMut()) {
        Recomposer::run_in_context(self, action);
    }
}
