use crate::protocol::{HostEvent, ProtocolError, decode_event};
use crate::renderer::ComposeRenderer;
use crate::schema::{
    AssetKind, EventPayload, IconRole, LoopMode, PROTOCOL_VERSION, SCHEMA_HASH, Theme,
};
use crate::{Element, KeyEvent, RangeRequest, Selection, VirtualDom};
use dioxus_core::{ElementId, Event};
use std::cell::RefCell;
use std::ffi::c_int;
use std::future::Future;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::pin::pin;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::task::{Context, Poll, Wake, Waker};

pub const STATUS_OK: i32 = 0;
pub const STATUS_PROTOCOL_ERROR: i32 = -1;
pub const STATUS_NOT_INITIALIZED: i32 = -2;
pub const STATUS_ALREADY_INITIALIZED: i32 = -3;
pub const STATUS_PANIC: i32 = -4;
/// There is no renderer in this build to run. Returned by the loop entry point, never by
/// a boundary call: a call could not have got this far without a renderer to make it.
pub const STATUS_NO_RENDERER: i32 = -5;

/// What the process exits with when the renderer loop does not finish successfully.
///
/// 1, not the status itself: exit codes are a single byte on Unix, so `STATUS_NO_RENDERER`
/// would reach the shell as 251 and read as a signal rather than an ordinary failure.
const EXIT_FAILURE: i32 = 1;

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct MutationBatch {
    pub ptr: *const u8,
    pub len: u32,
    pub result: i64,
}

impl Default for MutationBatch {
    fn default() -> Self {
        Self {
            ptr: std::ptr::null(),
            len: 0,
            result: 0,
        }
    }
}

#[derive(Clone, Copy)]
pub struct RendererApi {
    pub run: extern "C" fn() -> c_int,
    pub request_frame: extern "C" fn(),
}

static RENDERER_API: OnceLock<RendererApi> = OnceLock::new();
static FRAME_REQUESTED: AtomicBool = AtomicBool::new(false);
static EVENT_DISPATCH_ACTIVE: AtomicBool = AtomicBool::new(false);
static DEFERRED_FRAME_REQUEST: AtomicBool = AtomicBool::new(false);
/// Set between a stop and the start that follows it. While it is set a worker's frame
/// request is remembered but not delivered, so a process that is not on screen is never
/// asked to draw.
static LIFECYCLE_SUPPRESSED: AtomicBool = AtomicBool::new(false);

pub fn install_renderer_api(api: RendererApi) -> Result<(), RendererApi> {
    RENDERER_API.set(api)
}

// `renderer_linked` is set by the build script, and only when a renderer was really
// linked. That is not the same as the feature being on: a documentation build turns the
// feature on and links nothing, and the result has to behave like the build it is.
#[cfg(all(renderer_linked, not(any(test, feature = "mock-renderer"))))]
unsafe extern "C" {
    fn dioxus_compose_renderer_run() -> c_int;
    fn dioxus_compose_renderer_request_frame();
}

#[cfg(all(renderer_linked, not(any(test, feature = "mock-renderer"))))]
extern "C" fn native_run() -> c_int {
    // SAFETY: The application links the Renderer implementation of this declared C ABI.
    unsafe { dioxus_compose_renderer_run() }
}

#[cfg(all(renderer_linked, not(any(test, feature = "mock-renderer"))))]
extern "C" fn native_request_frame() {
    // SAFETY: The Renderer contract makes request_frame thread-safe.
    unsafe { dioxus_compose_renderer_request_frame() }
}

// The mock renderer and the crate's own tests drive the boundary directly and never want
// a window, so doing nothing is the correct answer for them and always has been.
#[cfg(any(test, feature = "mock-renderer"))]
extern "C" fn native_run() -> c_int {
    STATUS_OK
}

#[cfg(any(test, feature = "mock-renderer"))]
extern "C" fn native_request_frame() {}

// A real build with no renderer. This used to return success, so an application built
// this way opened no window, drew nothing, printed nothing and exited 0, and there was
// no way to tell that from a program that had simply finished.
#[cfg(all(not(renderer_linked), not(any(test, feature = "mock-renderer"))))]
extern "C" fn native_run() -> c_int {
    eprintln!("{}", no_renderer_message());
    STATUS_NO_RENDERER
}

#[cfg(all(not(renderer_linked), not(any(test, feature = "mock-renderer"))))]
extern "C" fn native_request_frame() {}

/// What a build with no renderer says on its way out.
///
/// Compiled into every build, not only the one that prints it, so that a test can read it
/// whatever the build it is running in was configured with.
pub fn no_renderer_message() -> String {
    format!(
        "dioxus-compose: this application was built without a renderer, so there is nothing\n\
         to draw with and nothing to draw on. Exiting {EXIT_FAILURE} rather than looking like\n\
         a program that ran and finished.\n\
         \n\
         A default `cargo build` links the renderer for the platform it is building for and\n\
         downloads it if it has to. A build reaches this message by turning that off:\n\
         \n\
         \x20   default-features = false, without re-enabling `native-renderer`\n\
         \x20   a documentation build, which has no network to fetch a renderer with\n\
         \n\
         Building for a test or with the `mock-renderer` feature is a third way, and that\n\
         one is silent on purpose: those builds drive the boundary directly and want no\n\
         window."
    )
}

fn renderer_api() -> RendererApi {
    RENDERER_API.get().copied().unwrap_or(RendererApi {
        run: native_run,
        request_frame: native_request_frame,
    })
}

/// Coalesced wake used internally by the Dioxus scheduler waker.
///
/// The flag spans the moment of delivery, not the wait for the frame that answers it. The
/// Renderer folds requests into its own frame clock, so however many arrive between two
/// frames it draws once; holding the flag until the frame came back would instead mean
/// that one request the Renderer was not yet listening for silenced every later one. That
/// happens on a cold start, where the first composition can be seconds after the first
/// worker request, and it leaves the application frozen with nothing to unfreeze it.
pub fn request_frame_from_worker() {
    if !FRAME_REQUESTED.swap(true, Ordering::AcqRel) {
        if LIFECYCLE_SUPPRESSED.load(Ordering::Acquire)
            || EVENT_DISPATCH_ACTIVE.load(Ordering::Acquire)
        {
            DEFERRED_FRAME_REQUEST.store(true, Ordering::Release);
        } else {
            (renderer_api().request_frame)();
            FRAME_REQUESTED.store(false, Ordering::Release);
        }
    }
}

thread_local! {
    /// How deep this thread is inside a Host call. A notification posted from inside one is
    /// written into the batch that call returns, so it needs no frame of its own.
    static HOST_CALL_DEPTH: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
}

/// Whether this thread is inside `rebuild`, `dispatch` or `render_frame` right now.
pub(crate) fn in_host_call() -> bool {
    HOST_CALL_DEPTH.with(std::cell::Cell::get) > 0
}

/// Marks the span of one Host call on this thread.
struct HostCallGuard;

impl HostCallGuard {
    fn enter() -> Self {
        HOST_CALL_DEPTH.with(|depth| depth.set(depth.get() + 1));
        Self
    }
}

impl Drop for HostCallGuard {
    fn drop(&mut self) {
        HOST_CALL_DEPTH.with(|depth| depth.set(depth.get().saturating_sub(1)));
    }
}

/// Asks for the frame that carries a queued notification out.
///
/// The one request a stopped UI does not hold back. Holding requests while the platform
/// has the UI off screen is right for timers and animations, which nobody is watching, and
/// wrong for a notification, whose whole point is the moment nobody is watching. So while
/// stopped this goes to the Renderer directly, and the Renderer serves it outside its
/// stopped frame clock. Everything else stays held, and the frame it was waiting for is
/// still delivered on start.
pub(crate) fn request_frame_for_notifications() {
    if LIFECYCLE_SUPPRESSED.load(Ordering::Acquire) {
        (renderer_api().request_frame)();
        return;
    }
    request_frame_from_worker();
}

struct EventDispatchGuard;

impl EventDispatchGuard {
    fn enter() -> Self {
        EVENT_DISPATCH_ACTIVE.store(true, Ordering::Release);
        Self
    }
}

impl Drop for EventDispatchGuard {
    fn drop(&mut self) {
        EVENT_DISPATCH_ACTIVE.store(false, Ordering::Release);
        if LIFECYCLE_SUPPRESSED.load(Ordering::Acquire) {
            return;
        }
        if DEFERRED_FRAME_REQUEST.swap(false, Ordering::AcqRel) {
            (renderer_api().request_frame)();
            FRAME_REQUESTED.store(false, Ordering::Release);
        }
    }
}

struct FrameWake;

impl Wake for FrameWake {
    fn wake(self: Arc<Self>) {
        request_frame_from_worker();
    }

    fn wake_by_ref(self: &Arc<Self>) {
        request_frame_from_worker();
    }
}

/// One streaming Text node's tail, accumulated between frames.
struct PendingAppend {
    node_id: u32,
    text: String,
    dirty: bool,
}

pub struct Host {
    /// Kept so the tree can be built again from nothing when the Renderer asks for a
    /// resync, which is the one thing a diff against a lost node table cannot answer.
    app: fn() -> Element,
    theme: Theme,
    window: crate::schema::Window,
    dom: VirtualDom,
    renderer: ComposeRenderer,
    frame_waker: Waker,
    pending_appends: Vec<PendingAppend>,
}

impl Host {
    pub fn new(app: fn() -> Element) -> Self {
        Self::with_theme(app, launched_theme())
    }

    /// The theme the application chose. Choosing nothing follows the host platform, with
    /// Material 3 where the platform has no look of its own.
    pub fn with_theme(app: fn() -> Element, theme: Theme) -> Self {
        // A fresh Host has not been measured yet, and the Renderer that is about to drive
        // it starts from the same assumption. Leaving a previous Host's last measurement
        // behind would put the two sides out of step, because the Renderer reports only
        // differences.
        crate::window::reset_window_size();
        // Messages queued against a Host that is going away would otherwise be said by
        // the one replacing it, out of any context that made them make sense.
        //
        crate::message::reset_messages();
        // The assets do not go with them. The Renderer this Host is about to talk to has
        // an empty cache, so every registration has to be made again, which is what this
        // does: the ids stay as they were and the bytes are queued for the first batch.
        //
        // Forgetting them instead is what this used to do, and it was wrong in a way that
        // only showed on the screen. A theme names its fonts by id and a window names its
        // icon by id, and both are built by the caller before the Host is made out of
        // them, so the ids travelled and the bytes they named had been thrown away.
        crate::asset::requeue_all();
        // The theme a running application changes from, and nothing queued against the
        // Host this one replaces.
        crate::theme::install(theme);
        Self {
            app,
            theme,
            window: launched_window(),
            dom: VirtualDom::new(app),
            renderer: ComposeRenderer::new(),
            frame_waker: Waker::from(Arc::new(FrameWake)),
            pending_appends: Vec::new(),
        }
    }

    pub fn rebuild(&mut self) -> Result<&[u8], ProtocolError> {
        let _call = HostCallGuard::enter();
        self.renderer.begin_frame();
        // One record at the root, before any node exists. The Renderer resolves roles to
        // values, so switching theme or colour scheme costs this one record rather than a
        // SetProp for every node in the tree.
        self.renderer.set_theme(self.theme);
        // Beside the theme, and for the same reason: it is about the window rather than
        // any node in it, and it is settled once rather than every frame. The Renderer
        // reads it out of this batch before it stands the window up.
        self.renderer.set_window(self.window);
        self.dom.rebuild(&mut self.renderer);
        self.flush_messages();
        self.arm_scheduler_wake();
        self.renderer.finish_frame()
    }

    pub fn dispatch_event(&mut self, bytes: &[u8]) -> Result<(&[u8], i64), ProtocolError> {
        let event = decode_event(bytes)?;
        self.dispatch(event)
    }

    /// The batch arena, reported to the Renderer on every boundary call.
    pub fn arena(&self) -> (*const u8, usize) {
        self.renderer.arena()
    }

    /// Answers with the whole tree, for a Renderer that no longer has a node table.
    ///
    /// The Host keeps no shadow of what it has already sent, so the only way to produce a
    /// full-tree batch is to build the application again, and that resets component state.
    /// A Renderer that keeps its node table across a configuration change never needs
    /// this call, which is what the Android host does.
    pub fn resync(&mut self) -> Result<(&[u8], i64), ProtocolError> {
        *self = Self::with_theme(self.app, self.theme);
        Ok((self.rebuild()?, 0))
    }

    /// Suppresses timers and animations while the UI is off screen, and releases the
    /// request that arrived while it was, so nothing is lost by stopping.
    fn set_lifecycle_running(&mut self, running: bool) -> Result<(&[u8], i64), ProtocolError> {
        LIFECYCLE_SUPPRESSED.store(!running, Ordering::Release);
        if running {
            if DEFERRED_FRAME_REQUEST.swap(false, Ordering::AcqRel) {
                (renderer_api().request_frame)();
            }
            FRAME_REQUESTED.store(false, Ordering::Release);
        }
        // An empty batch, not a frame: starting again is the Renderer's cue to draw, and
        // it asks for that frame itself.
        self.renderer.begin_frame();
        Ok((self.renderer.finish_frame()?, 0))
    }

    /// Hands a pressed notification to the components listening for one.
    ///
    /// No node and no handler: the notification is not in the tree, and the component that
    /// posted it may be long gone. What it carries is the key, which the application reads.
    fn notification_activated(
        &mut self,
        action: u32,
        key: &str,
    ) -> Result<(&[u8], i64), ProtocolError> {
        let woke = {
            let _dispatch_guard = EventDispatchGuard::enter();
            crate::notification::activate(crate::notification::NotificationActivation {
                key: key.to_owned(),
                action,
            })
        };
        self.renderer.begin_frame();
        if woke {
            self.dom.render_immediate(&mut self.renderer);
        }
        self.flush_messages();
        self.arm_scheduler_wake();
        Ok((self.renderer.finish_frame()?, 0))
    }

    /// Records whether notifications may be shown, and renders the components that asked.
    ///
    /// The same shape as the resolved design system: sent once after start and again only
    /// when it changes, and a repeat wakes nobody.
    fn publish_notification_permission(
        &mut self,
        state: crate::schema::NotificationPermission,
    ) -> Result<(&[u8], i64), ProtocolError> {
        self.renderer.begin_frame();
        if crate::notification::publish_permission(state) {
            self.dom.render_immediate(&mut self.renderer);
        }
        self.flush_messages();
        self.arm_scheduler_wake();
        Ok((self.renderer.finish_frame()?, 0))
    }

    /// Records which design system the Renderer resolved the theme to.
    ///
    /// The same shape as a size class arriving: it belongs to no node and no handler, and
    /// a repeat of the same answer wakes nothing, so an application that never asks pays
    /// a comparison once per change and nothing per frame.
    fn publish_design_system(
        &mut self,
        system: crate::schema::DesignSystem,
    ) -> Result<(&[u8], i64), ProtocolError> {
        if crate::design::publish(system) {
            self.renderer.begin_frame();
            self.dom.render_immediate(&mut self.renderer);
            self.flush_messages();
            self.arm_scheduler_wake();
            return Ok((self.renderer.finish_frame()?, 0));
        }
        self.renderer.begin_frame();
        Ok((self.renderer.finish_frame()?, 0))
    }

    pub fn dispatch(&mut self, event: HostEvent<'_>) -> Result<(&[u8], i64), ProtocolError> {
        let _call = HostCallGuard::enter();
        // These address the Host itself: no node, no handler, and an answer before
        // anything is looked up.
        match event.payload {
            EventPayload::Resync => return self.resync(),
            EventPayload::DesignSystemResolved(system) => {
                return self.publish_design_system(system);
            }
            EventPayload::LifecycleStart => return self.set_lifecycle_running(true),
            EventPayload::LifecycleStop => return self.set_lifecycle_running(false),
            EventPayload::NotificationActivated { action, key } => {
                if event.node_id != 0 || event.handler_id != 0 {
                    return Err(ProtocolError::InvalidValueKind(0));
                }
                return self.notification_activated(action, key);
            }
            EventPayload::NotificationPermissionChanged(state) => {
                if event.node_id != 0 || event.handler_id != 0 {
                    return Err(ProtocolError::InvalidValueKind(0));
                }
                return self.publish_notification_permission(state);
            }
            _ => {}
        }
        // The window's size belongs to no node and no handler: the Renderer measures the
        // root content and reports it. It takes the same synchronous path as every other
        // event, so the batch it produces is applied in the frame that asked for it.
        if let EventPayload::WindowSizeChanged {
            width_dp,
            height_dp,
            class,
            height_class,
        } = event.payload
        {
            // Node id zero is the window; anything else is one of its nodes. One event
            // for both, because they are the same fact measured at two scales and a
            // second way of saying it would be a second thing to keep in step.
            let size = crate::window::WindowSize::new(width_dp, height_dp, class, height_class);
            if event.node_id != 0 {
                let woke = self
                    .renderer
                    .size_token(event.node_id)
                    .is_some_and(|token| crate::window::publish_node(token, size));
                if !woke {
                    self.renderer.begin_frame();
                    return Ok((self.renderer.finish_frame()?, 0));
                }
                self.renderer.begin_frame();
                self.dom.render_immediate(&mut self.renderer);
                self.flush_messages();
                self.arm_scheduler_wake();
                return Ok((self.renderer.finish_frame()?, 0));
            }
            crate::window::publish(size);
            self.renderer.begin_frame();
            self.dom.render_immediate(&mut self.renderer);
            self.flush_messages();
            self.arm_scheduler_wake();
            return Ok((self.renderer.finish_frame()?, 0));
        }
        // The action on a transient message. It belongs to no node, because the message is
        // not in the tree, so it is found by its handler id alone and the event carries the
        // "no node" id the Renderer uses for anything the tree does not own.
        let message_action = match event.payload {
            EventPayload::Clicked => crate::message::take_action(event.handler_id),
            _ => None,
        };
        if let Some(action) = message_action {
            if event.node_id != 0 {
                return Err(ProtocolError::InvalidValueKind(0));
            }
            let _dispatch_guard = EventDispatchGuard::enter();
            action.call(());
            self.renderer.begin_frame();
            self.dom.render_immediate(&mut self.renderer);
            self.flush_messages();
            self.arm_scheduler_wake();
            return Ok((self.renderer.finish_frame()?, 0));
        }
        let Some((element, node_id, name)) = self.renderer.handler(event.handler_id) else {
            return Err(ProtocolError::InvalidValueKind(0));
        };
        if event.node_id != node_id {
            return Err(ProtocolError::InvalidValueKind(0));
        }
        let mut key_event = None;
        let event_data = match event.payload {
            EventPayload::Clicked | EventPayload::FocusLost => {
                Event::new(Rc::new(()), true).into_any()
            }
            EventPayload::TextChanged(text) | EventPayload::TextSubmitted(text) => {
                Event::new(Rc::new(text.to_owned()), true).into_any()
            }
            EventPayload::ProtocolError { .. } => {
                return Err(ProtocolError::InvalidValueKind(0));
            }
            EventPayload::KeyDown {
                key,
                shift_key,
                ctrl_key,
                alt_key,
                meta_key,
            } => {
                let value = KeyEvent::new(key, shift_key, ctrl_key, alt_key, meta_key);
                key_event = Some(value.clone());
                Event::new(Rc::new(value), true).into_any()
            }
            EventPayload::FilesEntered => Event::new(Rc::new(()), true).into_any(),
            EventPayload::FilesDropped(paths) => {
                Event::new(Rc::new(crate::widgets::FileDrop::new(paths)), true).into_any()
            }
            EventPayload::RangeRequested { start, count } => {
                Event::new(Rc::new(RangeRequest::new(start, count)), true).into_any()
            }
            EventPayload::ValueChanged(value) => Event::new(Rc::new(value), true).into_any(),
            EventPayload::WindowSizeChanged { .. }
            | EventPayload::DesignSystemResolved(_)
            | EventPayload::Resync
            | EventPayload::LifecycleStart
            | EventPayload::LifecycleStop
            | EventPayload::NotificationActivated { .. }
            | EventPayload::NotificationPermissionChanged(_) => unreachable!("handled above"),
        };
        let _dispatch_guard = EventDispatchGuard::enter();
        self.dom.runtime().handle_event(name, event_data, element);
        self.renderer.begin_frame();
        self.dom.render_immediate(&mut self.renderer);
        self.flush_messages();
        self.arm_scheduler_wake();
        let result = i64::from(key_event.is_some_and(|event| event.consumed()));
        Ok((self.renderer.finish_frame()?, result))
    }

    pub fn render_frame(&mut self, _frame_time_nanos: u64) -> Result<&[u8], ProtocolError> {
        let _call = HostCallGuard::enter();
        if LIFECYCLE_SUPPRESSED.load(Ordering::Acquire) {
            // The platform has the UI off screen and the Renderer called anyway, which it
            // does for one reason: a notification was queued, and a notification is what
            // has to get out while nobody is looking. Only that goes. The components are
            // not rendered and the held frame request is left held, so timers and
            // animations stay suppressed until start delivers it.
            self.renderer.begin_frame();
            self.flush_notifications();
            return self.renderer.finish_frame();
        }
        FRAME_REQUESTED.store(false, Ordering::Release);
        EVENT_DISPATCH_ACTIVE.store(false, Ordering::Release);
        DEFERRED_FRAME_REQUEST.store(false, Ordering::Release);
        self.renderer.begin_frame();
        self.dom.render_immediate(&mut self.renderer);
        self.flush_pending_appends();
        self.flush_messages();
        self.arm_scheduler_wake();
        self.renderer.finish_frame()
    }

    /// Queues a streamed tail for the next frame. Tokens arriving inside one frame are
    /// merged into a single `AppendText` record, so a token never costs a batch of its own.
    pub fn append_text(&mut self, node_id: u32, tail: &str) {
        match self
            .pending_appends
            .iter_mut()
            .find(|pending| pending.node_id == node_id)
        {
            Some(pending) => {
                pending.text.push_str(tail);
                pending.dirty = true;
            }
            None => self.pending_appends.push(PendingAppend {
                node_id,
                text: tail.to_owned(),
                dirty: true,
            }),
        }
        // Repeated calls collapse into one frame request; see `request_frame_from_worker`.
        request_frame_from_worker();
    }

    /// Writes whatever the tree asked to say during this call into the batch it produced.
    ///
    /// A message therefore arrives in the same call as the change it is about, which is
    /// what makes "deleted" and the row disappearing one frame rather than two.
    fn flush_messages(&mut self) {
        // A theme the application changed during this call, one record for the whole
        // tree. Kept as the Host's own as well, so a resync rebuilds with it.
        if let Some(theme) = crate::theme::take_pending() {
            self.theme = theme;
            self.renderer.set_theme(theme);
        }
        let renderer = &mut self.renderer;
        // Registrations first. Not because the Renderer needs them first, it applies the
        // whole batch before drawing any of it, but because a batch read by a person
        // debugging one reads in the order the screen was built.
        crate::asset::drain(|pending| {
            renderer.register_asset(pending.asset_id, pending.kind, pending.bytes);
        });
        crate::message::drain(|message| {
            renderer.show_message(
                message.handler_id,
                &message.text,
                &message.action,
                message.duration,
            );
        });
        self.flush_notifications();
    }

    /// Writes the notification commands waiting in the Host into this batch.
    ///
    /// Workers queue them from their own threads; this is the one place they leave, on the
    /// UI thread, inside a call. One atomic read when there are none.
    fn flush_notifications(&mut self) {
        let renderer = &mut self.renderer;
        let posts = crate::notification::drain(|command| renderer.notification(command));
        crate::notification::note_posted(posts);
    }

    fn flush_pending_appends(&mut self) {
        for index in 0..self.pending_appends.len() {
            let pending = &self.pending_appends[index];
            if pending.dirty {
                self.renderer
                    .append_text_node(pending.node_id, &pending.text);
            }
        }
        // Buffers are kept so steady-state streaming reuses their capacity and a streamed
        // token does not allocate.
        for pending in &mut self.pending_appends {
            pending.text.clear();
            pending.dirty = false;
        }
    }

    /// Registers one asset and returns the batch that carries it.
    ///
    /// The bytes are copied once here and once more by the Renderer into its cache. After
    /// that the id is all that travels, so drawing the same image every frame costs a
    /// fixed-layout property record and nothing else.
    pub fn register_asset(
        &mut self,
        asset_id: u32,
        kind: AssetKind,
        bytes: &[u8],
    ) -> Result<&[u8], ProtocolError> {
        self.renderer.begin_frame();
        self.renderer.register_asset(asset_id, kind, bytes);
        self.renderer.finish_frame()
    }

    /// Registers an icon by the meaning it carries. The Renderer holds the artwork for
    /// every design system, so what crosses is the role and not a picture or a name.
    pub fn register_icon(&mut self, asset_id: u32, role: IconRole) -> Result<&[u8], ProtocolError> {
        self.register_asset(
            asset_id,
            AssetKind::VectorIcon,
            &(role as u16).to_le_bytes(),
        )
    }

    /// Drops the asset from the Renderer's cache. Using the id afterwards is a reported
    /// protocol error.
    pub fn release_asset(&mut self, asset_id: u32) -> Result<&[u8], ProtocolError> {
        self.renderer.begin_frame();
        self.renderer.release_asset(asset_id);
        self.renderer.finish_frame()
    }

    pub fn set_text(
        &mut self,
        node_id: u32,
        text: &str,
        selection: Option<Selection>,
    ) -> Result<&[u8], ProtocolError> {
        self.renderer.begin_frame();
        self.renderer.set_text_node(node_id, text, selection);
        self.renderer.finish_frame()
    }

    pub fn handler_target(&self, handler_id: u64) -> Option<ElementId> {
        self.renderer.handler(handler_id).map(|value| value.0)
    }

    fn arm_scheduler_wake(&mut self) {
        let mut context = Context::from_waker(&self.frame_waker);
        let future = self.dom.wait_for_work();
        let mut future = pin!(future);
        if future.as_mut().poll(&mut context) == Poll::Ready(()) {
            request_frame_from_worker();
        }
    }
}

/// Holds the thread's `Host` and, crucially, keeps it out of thread-local teardown.
///
/// Dropping a `VirtualDom` reaches back into the Dioxus runtime's own
/// thread-locals. Thread-local destruction order is unspecified, so if the UI thread
/// ends without `dioxus_compose_host_shutdown`, that drop can run after the Dioxus
/// locals are already gone and panic with "cannot access a TLS value during or after
/// destruction". A panic in a destructor is non-unwinding: it aborts the process, which
/// is exactly what must not happen: a protocol or teardown fault has to stay recoverable.
///
/// So the slot empties itself and leaks the `Host` when the thread is tearing down. An
/// orderly `shutdown` still drops it properly; only the unorderly path leaks, and that
/// path is a thread ending anyway.
struct HostSlot(RefCell<Option<Host>>);

impl Drop for HostSlot {
    fn drop(&mut self) {
        std::mem::forget(self.0.borrow_mut().take());
    }
}

impl std::ops::Deref for HostSlot {
    type Target = RefCell<Option<Host>>;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

/// The launched application.
///
/// The `VirtualDom` and every `dioxus_compose_host_*` call run on the Renderer UI
/// thread, and that thread is not the one that called `launch`: with `LoopMode::Renderer`
/// the Rust main thread blocks inside `dioxus_compose_renderer_run` while Compose composes
/// on the toolkit's own thread. So the app function, unlike the `Host` it builds, has to be
/// reachable across threads. A `fn() -> Element` is a plain function pointer, so sharing it
/// costs nothing and adds no thread affinity.
///
/// `shutdown` deliberately leaves this set: it belongs to `launch`, not to one UI thread's
/// `Host`. That is also what `LoopMode::Platform` needs, where the Renderer may tear the
/// Host down and initialize it again (Android recreates its surface on a configuration
/// change) without relaunching.
static APP: Mutex<Option<fn() -> Element>> = Mutex::new(None);

/// Chosen by `LaunchBuilder::with_theme`, read once when the Host is built.
static THEME: Mutex<Theme> = Mutex::new(Theme::unified(crate::schema::DesignSystem::Material3));

/// Chosen by `LaunchBuilder::with_window`, read once when the Host is built.
static WINDOW: Mutex<crate::schema::Window> = Mutex::new(crate::schema::Window::new());

thread_local! {
    static HOST: HostSlot = const { HostSlot(RefCell::new(None)) };
}

fn launched_app() -> Option<fn() -> Element> {
    APP.lock().map_or(None, |app| *app)
}

fn launched_window() -> crate::schema::Window {
    WINDOW
        .lock()
        .map_or_else(|error| *error.into_inner(), |window| *window)
}

fn launched_theme() -> Theme {
    THEME
        .lock()
        .map_or_else(|error| *error.into_inner(), |theme| *theme)
}

#[derive(Clone, Copy, Debug)]
pub struct LaunchBuilder {
    mode: LoopMode,
    theme: Theme,
    window: crate::schema::Window,
}

impl Default for LaunchBuilder {
    fn default() -> Self {
        Self {
            mode: LoopMode::Renderer,
            theme: Theme::default(),
            window: crate::schema::Window::new(),
        }
    }
}

impl LaunchBuilder {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_mode(mut self, mode: LoopMode) -> Self {
        self.mode = mode;
        self
    }

    /// `Theme::unified` for one design system everywhere, `Theme::adaptive` to follow the
    /// host platform. Not calling this follows the host platform, falling back to
    /// Material 3.
    pub fn with_theme(mut self, theme: Theme) -> Self {
        self.theme = theme;
        self
    }

    /// What the application asks of its own window: its size, and whether it wears the
    /// platform's title bar or has content run into it.
    ///
    /// Not calling this gets a modern window that the Renderer sizes. The window belongs
    /// to the Renderer and most of what it looks like is the design system's, so what can
    /// be said here is short on purpose: nothing names a colour, a corner, or where the
    /// window buttons go.
    pub fn with_window(mut self, window: crate::schema::Window) -> Self {
        self.window = window;
        self
    }

    /// Runs the application. Does not return while it is running, and ends the process
    /// with a failing status if the renderer loop could not run at all.
    ///
    /// Exiting rather than returning is the point. `launch` is the last statement of
    /// `main` in every application that uses this crate, so returning from it means `main`
    /// returns, which means the process exits 0. A window that never opened would then be
    /// indistinguishable from a program that did its work and stopped.
    pub fn launch(self, app: fn() -> Element) {
        let status = self.try_launch(app);
        if status != STATUS_OK {
            std::process::exit(EXIT_FAILURE);
        }
    }

    /// [`LaunchBuilder::launch`] without the exit: the status the renderer loop ended
    /// with, handed back for a caller that has its own idea of what to do with it.
    pub fn try_launch(self, app: fn() -> Element) -> i32 {
        if let Ok(mut slot) = APP.lock() {
            *slot = Some(app);
        }
        if let Ok(mut slot) = THEME.lock() {
            *slot = self.theme;
        }
        // A palette that leaves something unreadable is said once, at start, in a debug
        // build. It does not stop the launch: a screen whose contrast is a little short is
        // better than no screen, and what to do about it is the application's call.
        #[cfg(debug_assertions)]
        for line in palette_report(&self.theme) {
            eprintln!("{line}");
        }
        if let Ok(mut slot) = WINDOW.lock() {
            *slot = self.window;
        }
        // Under `LoopMode::Platform` the platform owns the loop and calls in through the
        // boundary when it is ready. There is nothing to run and nothing to fail.
        if self.mode != LoopMode::Renderer {
            return STATUS_OK;
        }
        (renderer_api().run)()
    }
}

pub fn launch(app: fn() -> Element) {
    LaunchBuilder::new().launch(app);
}

/// What a theme's palette leaves short of contrast, as lines to print.
///
/// Every system an adaptive theme could land on is checked, because which one it lands on
/// is decided by the Renderer on a machine this code has not seen. A unified theme is
/// checked against the one system it names. Empty where there is no palette or nothing is
/// short.
pub fn palette_report(theme: &Theme) -> Vec<String> {
    let Some(palette) = theme.palette else {
        return Vec::new();
    };
    let systems: Vec<crate::schema::DesignSystem> = if theme.adaptive {
        crate::schema::DESIGN_SYSTEM_SCHEMA
            .iter()
            .filter_map(|variant| crate::schema::DesignSystem::try_from(variant.tag).ok())
            .collect()
    } else {
        vec![theme.design_system]
    };
    systems
        .into_iter()
        .flat_map(|system| {
            palette.check(system).into_iter().map(move |violation| {
                format!("dioxus-compose: palette under {system:?}: {violation}")
            })
        })
        .collect()
}

fn parse_handshake(bytes: &[u8]) -> Result<LoopMode, ProtocolError> {
    if bytes.len() < 12 {
        return Err(ProtocolError::Truncated);
    }
    let hash = u64::from_le_bytes(
        bytes[0..8]
            .try_into()
            .map_err(|_| ProtocolError::Truncated)?,
    );
    let version = u16::from_le_bytes(
        bytes[8..10]
            .try_into()
            .map_err(|_| ProtocolError::Truncated)?,
    );
    if hash != SCHEMA_HASH || version != PROTOCOL_VERSION {
        // Say what did not match, and say it here rather than leaving the status code to
        // carry it. A mismatch means the two halves were generated from different
        // versions of the schema, which on a desktop build means a renderer library
        // compiled before the last codegen run. The window still opens, because the
        // renderer stands it up before it asks, so the only thing on screen is an empty
        // page in the default theme: a symptom that looks like a blank application rather
        // than like a stale build, and one that cost a morning to read the first time.
        eprintln!(
            "dioxus-compose: the renderer was built from a different schema than this              program. It sent hash {hash:#x} version {version}, and this build expects              hash {SCHEMA_HASH:#x} version {PROTOCOL_VERSION}. Rebuild the renderer after              running codegen; if it was already rebuilt, its build directory is holding a              cached copy of the generated protocol and has to be cleared."
        );
        return Err(ProtocolError::InvalidEnvelope);
    }
    match bytes[10] {
        0 => Ok(LoopMode::Renderer),
        1 => Ok(LoopMode::Platform),
        other => Err(ProtocolError::InvalidValueKind(u16::from(other))),
    }
}

unsafe fn input_slice<'a>(ptr: *const u8, len: u32) -> Result<&'a [u8], ProtocolError> {
    if len == 0 {
        return Ok(&[]);
    }
    if ptr.is_null() {
        return Err(ProtocolError::Truncated);
    }
    // SAFETY: The C caller promises a readable buffer of `len` bytes for this call.
    Ok(unsafe { std::slice::from_raw_parts(ptr, len as usize) })
}

unsafe fn write_batch(
    out: *mut MutationBatch,
    bytes: &[u8],
    result: i64,
) -> Result<(), ProtocolError> {
    if out.is_null() {
        return Err(ProtocolError::Truncated);
    }
    let len = u32::try_from(bytes.len()).map_err(|_| ProtocolError::LengthOverflow)?;
    // SAFETY: Null was rejected and the C caller promises writable storage.
    unsafe {
        out.write(MutationBatch {
            ptr: bytes.as_ptr(),
            len,
            result,
        });
    }
    Ok(())
}

/// A call-order failure is not a malformed message: the boundary declares distinct statuses
/// so the Renderer can tell "you called me too early" from "your bytes were wrong". The
/// closures below therefore yield a status directly.
fn ffi_status(operation: impl FnOnce() -> Result<(), i32>) -> i32 {
    // There is no channel for the Host to raise a ProtocolError event back to the Renderer:
    // the boundary is a synchronous call that returns a status, and events only travel
    // Renderer to Host. Malformed calls therefore return STATUS_PROTOCOL_ERROR.
    match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

/// Every `ProtocolError` leaving an export is reported as one status code.
fn protocol_error(_error: ProtocolError) -> i32 {
    STATUS_PROTOCOL_ERROR
}

#[unsafe(no_mangle)]
/// Initializes the UI-thread Host from the handshake and returns the initial batch.
///
/// # Safety
/// `handshake` must address `len` readable bytes and `out` must be writable.
pub unsafe extern "C" fn dioxus_compose_host_init(
    handshake: *const u8,
    len: u32,
    out: *mut MutationBatch,
) -> i32 {
    ffi_status(|| {
        // SAFETY: Validated according to the function's C ABI contract.
        let bytes = unsafe { input_slice(handshake, len) }.map_err(protocol_error)?;
        let _mode = parse_handshake(bytes).map_err(protocol_error)?;
        let app = launched_app().ok_or(STATUS_NOT_INITIALIZED)?;
        HOST.with(|slot| {
            let mut host_slot = slot.borrow_mut();
            if host_slot.is_some() {
                return Err(STATUS_ALREADY_INITIALIZED);
            }
            let mut host = Host::new(app);
            let batch = host.rebuild().map_err(protocol_error)?;
            // SAFETY: `out` is checked before writing.
            unsafe { write_batch(out, batch, 0) }.map_err(protocol_error)?;
            *host_slot = Some(host);
            Ok(())
        })
    })
}

#[unsafe(no_mangle)]
/// Dispatches one encoded event synchronously and returns its diff batch.
///
/// # Safety
/// `event` must address `len` readable bytes and `out` must be writable.
pub unsafe extern "C" fn dioxus_compose_host_dispatch_event(
    event: *const u8,
    len: u32,
    out: *mut MutationBatch,
) -> i32 {
    ffi_status(|| {
        // SAFETY: Validated according to the function's C ABI contract.
        let bytes = unsafe { input_slice(event, len) }.map_err(protocol_error)?;
        HOST.with(|slot| {
            let mut host_slot = slot.borrow_mut();
            let host = host_slot.as_mut().ok_or(STATUS_NOT_INITIALIZED)?;
            let (batch, result) = host.dispatch_event(bytes).map_err(protocol_error)?;
            // SAFETY: `out` is checked before writing.
            unsafe { write_batch(out, batch, result) }.map_err(protocol_error)
        })
    })
}

#[unsafe(no_mangle)]
/// Renders work scheduled before the current platform frame.
///
/// # Safety
/// `out` must point to writable storage for one [`MutationBatch`].
pub unsafe extern "C" fn dioxus_compose_host_render_frame(
    frame_time_nanos: u64,
    out: *mut MutationBatch,
) -> i32 {
    ffi_status(|| {
        HOST.with(|slot| {
            let mut host_slot = slot.borrow_mut();
            let host = host_slot.as_mut().ok_or(STATUS_NOT_INITIALIZED)?;
            let batch = host
                .render_frame(frame_time_nanos)
                .map_err(protocol_error)?;
            // SAFETY: `out` is checked before writing.
            unsafe { write_batch(out, batch, 0) }.map_err(protocol_error)
        })
    })
}

#[unsafe(no_mangle)]
/// Releases a batch after the Renderer has applied it on the same call stack.
///
/// # Safety
/// `batch` must be null or point to writable storage for one [`MutationBatch`].
pub unsafe extern "C" fn dioxus_compose_host_release_batch(batch: *mut MutationBatch) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if !batch.is_null() {
            // SAFETY: The caller supplied its own writable MutationBatch.
            unsafe { batch.write(MutationBatch::default()) };
        }
    }));
}

/// The UI thread's batch arena.
///
/// Not a boundary entry point: it is crate-internal, and the generated Android shims use
/// it to tell the Renderer where the arena is so it can map it once instead of per call.
pub fn current_arena() -> (*const u8, usize) {
    HOST.with(|slot| {
        slot.borrow()
            .as_ref()
            .map_or((std::ptr::null(), 0), Host::arena)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn dioxus_compose_host_shutdown() {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        HOST.with(|slot| *slot.borrow_mut() = None);
        FRAME_REQUESTED.store(false, Ordering::Release);
    }));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::prelude::*;
    use crate::protocol::{Mutation, PropertyValue, decode_batch};
    use crate::schema::PropertyKind;
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicUsize, Ordering};

    static CLICKS: AtomicUsize = AtomicUsize::new(0);

    /// `APP` is process-global, as `launch` is, so two tests that launch different apps at
    /// the same time would each see the other's. Cargo runs tests on one thread each, so
    /// the ones that launch take this first. Poisoning is ignored: a failing test has
    /// already reported itself, and the rest still need the lock.
    static LAUNCH: Mutex<()> = Mutex::new(());

    fn launch_guard() -> std::sync::MutexGuard<'static, ()> {
        LAUNCH
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn app() -> Element {
        let mut count = use_signal(|| 0_i64);
        rsx! {
            Column {
                Text { text: count().to_string() }
                TextField { placeholder: "Message" }
                Button {
                    text: "Increment",
                    on_click: move |_| {
                        CLICKS.fetch_add(1, Ordering::SeqCst);
                        *count.write() += 1;
                    }
                }
            }
        }
    }

    fn key_app() -> Element {
        rsx! {
            Column {
                TextField {
                    multiline: true,
                    on_key_down: move |event: KeyEvent| {
                        if event.key() == Key::Enter && !event.shift_key() {
                            event.consume();
                        }
                    }
                }
                TextField {
                    on_key_down: move |_event: KeyEvent| {}
                }
            }
        }
    }

    #[derive(Default)]
    struct MockRenderer {
        widgets: HashMap<u32, crate::WidgetKind>,
        parents: HashMap<u32, u32>,
    }

    impl MockRenderer {
        fn apply(&mut self, batch: &[Mutation<'_>]) {
            for mutation in batch {
                match mutation {
                    Mutation::Create { node_id, widget } => {
                        self.widgets.insert(*node_id, *widget);
                    }
                    Mutation::Insert {
                        parent_id, node_id, ..
                    }
                    | Mutation::Move {
                        parent_id, node_id, ..
                    } => {
                        self.parents.insert(*node_id, *parent_id);
                    }
                    Mutation::Remove { node_id } => {
                        self.widgets.remove(node_id);
                        self.parents.remove(node_id);
                    }
                    Mutation::SetProp { .. }
                    | Mutation::SetModifier { .. }
                    | Mutation::SetText { .. }
                    | Mutation::AppendText { .. }
                    | Mutation::RegisterAsset { .. }
                    | Mutation::ReleaseAsset { .. }
                    | Mutation::ShowMessage { .. }
                    | Mutation::SetTheme(_)
                    | Mutation::SetWindow(_)
                    | Mutation::PostNotification { .. }
                    | Mutation::WithdrawNotification { .. }
                    | Mutation::RequestNotificationPermission => {}
                }
            }
        }
    }

    #[test]
    fn click_runs_once_and_emits_only_text_set_prop() {
        CLICKS.store(0, Ordering::SeqCst);
        let mut host = Host::new(app);
        let initial = decode_batch(host.rebuild().unwrap()).unwrap();
        let mut mock = MockRenderer::default();
        mock.apply(&initial);
        assert_eq!(mock.widgets.len(), 4);
        assert_eq!(mock.parents.len(), 3);

        let (button_node, handler_id) = initial
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::OnClick,
                    value: PropertyValue::Integer(handler),
                } => Some((*node_id, *handler as u64)),
                _ => None,
            })
            .unwrap();
        drop(initial);

        let event = HostEvent {
            node_id: button_node,
            handler_id,
            payload: EventPayload::Clicked,
        };
        let (batch, result) = host.dispatch(event).unwrap();
        let mutations = decode_batch(batch).unwrap();
        assert_eq!(CLICKS.load(Ordering::SeqCst), 1);
        assert_eq!(result, 0);
        assert_eq!(mutations.len(), 1);
        assert!(matches!(
            &mutations[0],
            Mutation::SetProp {
                property: PropertyKind::Text,
                value: PropertyValue::String(value),
                ..
            } if *value == "1"
        ));
    }

    #[test]
    fn malformed_ffi_input_returns_protocol_error() {
        let mut output = MutationBatch::default();
        // SAFETY: The test passes a valid output pointer and intentionally null input.
        let status =
            unsafe { dioxus_compose_host_dispatch_event(std::ptr::null(), 4, &mut output) };
        assert_eq!(status, STATUS_PROTOCOL_ERROR);
    }

    #[test]
    fn fr12_key_consumption_is_returned_and_does_not_leak() {
        let _launch = launch_guard();
        LaunchBuilder::new()
            .with_mode(LoopMode::Platform)
            .launch(key_app);
        let mut handshake = Vec::with_capacity(12);
        handshake.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
        handshake.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
        handshake.extend_from_slice(&[LoopMode::Platform as u8, 0]);
        let mut output = MutationBatch::default();
        // SAFETY: All pointers refer to live test-owned buffers for the duration of each call.
        unsafe {
            assert_eq!(
                dioxus_compose_host_init(handshake.as_ptr(), handshake.len() as u32, &mut output,),
                STATUS_OK
            );
        }
        // SAFETY: A successful init returned a readable batch owned by the Host.
        let initial = unsafe { std::slice::from_raw_parts(output.ptr, output.len as usize) };
        let handlers: Vec<_> = decode_batch(initial)
            .unwrap()
            .into_iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::OnKeyDown,
                    value: PropertyValue::Integer(handler),
                } => Some((node_id, handler as u64)),
                _ => None,
            })
            .collect();
        assert_eq!(handlers.len(), 2);

        let dispatch = |node_id, handler_id, shift_key, output: &mut MutationBatch| {
            let event = HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::KeyDown {
                    key: Key::Enter,
                    shift_key,
                    ctrl_key: false,
                    alt_key: false,
                    meta_key: false,
                },
            };
            let mut bytes = Vec::new();
            crate::protocol::encode_event(&event, &mut bytes).unwrap();
            // SAFETY: The encoded event and output storage remain live for the call.
            unsafe {
                assert_eq!(
                    dioxus_compose_host_dispatch_event(bytes.as_ptr(), bytes.len() as u32, output,),
                    STATUS_OK
                );
            }
        };

        dispatch(handlers[0].0, handlers[0].1, false, &mut output);
        assert_ne!(output.result, 0);
        dispatch(handlers[0].0, handlers[0].1, true, &mut output);
        assert_eq!(output.result, 0);
        dispatch(handlers[1].0, handlers[1].1, false, &mut output);
        assert_eq!(output.result, 0);
        dispatch(handlers[0].0, handlers[0].1, false, &mut output);
        assert_ne!(output.result, 0);
        dioxus_compose_host_shutdown();
    }

    /// `launch` runs on the Rust main thread, but the Renderer UI thread that calls
    /// `dioxus_compose_host_init` is a different thread (on macOS, AWT's event thread inside
    /// the isolate). The app the application launched must be reachable from there.
    #[test]
    fn pr3_init_runs_on_a_different_thread_than_launch() {
        let _launch = launch_guard();
        LaunchBuilder::new()
            .with_mode(LoopMode::Platform)
            .launch(app);
        let mut handshake = Vec::with_capacity(12);
        handshake.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
        handshake.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
        handshake.extend_from_slice(&[LoopMode::Platform as u8, 0]);
        std::thread::spawn(move || {
            let mut output = MutationBatch::default();
            // SAFETY: Both buffers are live test-owned storage for the duration of the call.
            let status = unsafe {
                dioxus_compose_host_init(handshake.as_ptr(), handshake.len() as u32, &mut output)
            };
            assert_eq!(status, STATUS_OK, "init must work off the launch thread");
            assert!(output.len > 0, "init returns the initial tree batch");
            dioxus_compose_host_shutdown();
        })
        .join()
        .unwrap();
    }

    /// The first record of the initial batch carries the theme. Saying nothing follows the
    /// host platform with a Material 3 fallback; `unified` is never implicit.
    #[test]
    fn fr14_initial_batch_opens_with_the_launched_theme() {
        let _launch = launch_guard();
        let first_record = |builder: LaunchBuilder| {
            builder.with_mode(LoopMode::Platform).launch(app);
            let mut handshake = Vec::with_capacity(12);
            handshake.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
            handshake.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
            handshake.extend_from_slice(&[LoopMode::Platform as u8, 0]);
            std::thread::spawn(move || {
                let mut output = MutationBatch::default();
                // SAFETY: Both buffers are live test-owned storage for this call.
                let status = unsafe {
                    dioxus_compose_host_init(
                        handshake.as_ptr(),
                        handshake.len() as u32,
                        &mut output,
                    )
                };
                assert_eq!(status, STATUS_OK);
                // SAFETY: A successful init returned a readable batch owned by the Host.
                let batch = unsafe { std::slice::from_raw_parts(output.ptr, output.len as usize) };
                let first = decode_batch(batch).unwrap().into_iter().next().unwrap();
                dioxus_compose_host_shutdown();
                first
            })
            .join()
            .unwrap()
        };

        // Saying nothing follows the host platform, falling back to Material 3.
        assert_eq!(
            first_record(LaunchBuilder::new()),
            Mutation::SetTheme(Theme::adaptive(DesignSystem::Material3))
        );
        assert_eq!(
            first_record(LaunchBuilder::new().with_theme(Theme::unified(DesignSystem::Fluent))),
            Mutation::SetTheme(Theme {
                design_system: DesignSystem::Fluent,
                fallback: DesignSystem::Fluent,
                color_scheme: ColorScheme::FollowSystem,
                adaptive: false,
                fonts: [0; crate::schema::TYPE_ROLE_COUNT],
                palette: None,
            })
        );
        assert_eq!(
            first_record(LaunchBuilder::new().with_theme(Theme::adaptive(DesignSystem::Cupertino))),
            Mutation::SetTheme(Theme {
                design_system: DesignSystem::Cupertino,
                fallback: DesignSystem::Cupertino,
                color_scheme: ColorScheme::FollowSystem,
                adaptive: true,
                fonts: [0; crate::schema::TYPE_ROLE_COUNT],
                palette: None,
            })
        );
    }

    /// The theme is sent once, not once per frame: resending it would put a record in every
    /// batch for a value that almost never changes.
    #[test]
    fn fr14_set_theme_is_not_resent_every_frame() {
        let mut host = Host::new(app);
        let initial = decode_batch(host.rebuild().unwrap()).unwrap();
        assert!(matches!(initial.first(), Some(Mutation::SetTheme(_))));
        let frame = decode_batch(host.render_frame(0).unwrap()).unwrap();
        assert!(
            !frame
                .iter()
                .any(|mutation| matches!(mutation, Mutation::SetTheme(_)))
        );
    }

    #[test]
    fn all_exports_isolate_invalid_calls() {
        let _launch = launch_guard();
        let mut output = MutationBatch::default();
        // SAFETY: Each call uses null or valid test-owned pointers as documented.
        unsafe {
            assert_eq!(
                dioxus_compose_host_init(std::ptr::null(), 1, &mut output),
                STATUS_PROTOCOL_ERROR
            );
            // No handshake has run on this thread, so this is a call-order error.
            assert_eq!(
                dioxus_compose_host_render_frame(0, &mut output),
                STATUS_NOT_INITIALIZED
            );
            dioxus_compose_host_release_batch(std::ptr::null_mut());
        }
        dioxus_compose_host_shutdown();
    }
}

/// The design system a demonstration should start in.
///
/// An application picks its own theme and never needs this. The samples do, because the
/// point of a sample is to show what one declaration looks like under each system, and on
/// any given machine the adaptive default can only ever show you one of them. Material 3
/// and Fluent went unseen for weeks for exactly that reason: everything was checked on a
/// Mac, so everything was Cupertino.
///
/// `DXC_DESIGN` names the system. Anything else, including nothing, adapts to the host.
/// `DXC_SCHEME` names `light` or `dark`; anything else leaves the reader's own setting
/// alone.
pub fn demo_theme() -> Theme {
    demo_theme_for(Theme::adaptive(crate::schema::DesignSystem::Material3))
}

/// The same, for a demonstration that has a theme of its own to start from.
///
/// A sample whose design is one particular design system in one particular colour scheme
/// says so, and that declaration is the sample. But the machine it is being looked at on
/// can only draw one of the seven at a time, so without a way to point it somewhere else
/// the other six are never seen. This keeps the sample's own theme as the answer and lets
/// the two variables override the part they name, so what is on screen is either the
/// design the sample chose or exactly the one that was asked for.
pub fn demo_theme_for(theme: Theme) -> Theme {
    use crate::schema::DesignSystem;
    let theme = match std::env::var("DXC_DESIGN").as_deref().map(str::trim) {
        Ok("material3") => Theme::unified(DesignSystem::Material3),
        Ok("cupertino") => Theme::unified(DesignSystem::Cupertino),
        Ok("fluent") => Theme::unified(DesignSystem::Fluent),
        Ok("gnome") => Theme::unified(DesignSystem::Gnome),
        Ok("breeze") => Theme::unified(DesignSystem::Breeze),
        Ok("deepin") => Theme::unified(DesignSystem::Deepin),
        // Spelled both ways, because the name is two words everywhere it is written down
        // and nobody remembers which one a shell variable wants.
        Ok("liquidglass") | Ok("liquid-glass") => Theme::unified(DesignSystem::LiquidGlass),
        // Naming a system replaces the sample's design system but keeps its colour
        // scheme, so asking to see one design does not also change how light it is.
        _ => return with_scheme_override(theme),
    }
    .with_color_scheme(theme.color_scheme);
    with_scheme_override(theme)
}

/// Applies `DXC_SCHEME` if it names one of the two schemes.
fn with_scheme_override(theme: Theme) -> Theme {
    match std::env::var("DXC_SCHEME").as_deref().map(str::trim) {
        Ok("light") => theme.with_color_scheme(crate::schema::ColorScheme::Light),
        Ok("dark") => theme.with_color_scheme(crate::schema::ColorScheme::Dark),
        _ => theme,
    }
}

#[cfg(test)]
mod demo_theme_tests {
    use super::*;
    use crate::schema::{ColorScheme, DESIGN_SYSTEM_SCHEMA, DesignSystem};
    use std::sync::{Mutex, MutexGuard};

    /// Held for the length of any test that sets one of the variables.
    ///
    /// The variables belong to the process, not to the test, so two of these running at
    /// once read each other's settings and fail on a value neither of them asked for.
    static ENVIRONMENT: Mutex<()> = Mutex::new(());

    /// Clears both variables and keeps everyone else out until the guard is dropped.
    fn exclusive_environment() -> MutexGuard<'static, ()> {
        let guard = ENVIRONMENT
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        // SAFETY: the lock makes this the only thread touching the environment, and the
        // variables are read only by the functions called under the same lock.
        unsafe {
            std::env::remove_var("DXC_DESIGN");
            std::env::remove_var("DXC_SCHEME");
        }
        guard
    }

    /// Named for what it defends: a sample that cannot be pointed at a design system
    /// leaves five of the six unseen on any one machine.
    #[test]
    fn fr14_a_named_design_system_is_unified_and_anything_else_adapts() {
        let _environment = exclusive_environment();
        // SAFETY: `exclusive_environment` holds the lock, so nothing else is reading or
        // writing these while this runs.
        for (name, system) in [
            ("material3", DesignSystem::Material3),
            ("cupertino", DesignSystem::Cupertino),
            ("fluent", DesignSystem::Fluent),
            ("gnome", DesignSystem::Gnome),
            ("breeze", DesignSystem::Breeze),
            ("deepin", DesignSystem::Deepin),
            ("liquidglass", DesignSystem::LiquidGlass),
            ("liquid-glass", DesignSystem::LiquidGlass),
        ] {
            unsafe { std::env::set_var("DXC_DESIGN", name) };
            assert_eq!(
                demo_theme(),
                Theme::unified(system),
                "{name} is not selectable"
            );
        }
        assert_eq!(
            DESIGN_SYSTEM_SCHEMA.len(),
            7,
            "a system nobody can select goes unseen"
        );

        unsafe { std::env::set_var("DXC_DESIGN", "nonsense") };
        assert_eq!(demo_theme(), Theme::adaptive(DesignSystem::Material3));

        unsafe { std::env::remove_var("DXC_DESIGN") };
        assert_eq!(demo_theme(), Theme::adaptive(DesignSystem::Material3));
    }

    /// Named for what it defends: a machine set to dark draws every sample dark, so a
    /// design that is meant to be read light cannot be looked at at all without this.
    #[test]
    fn fr14_a_named_colour_scheme_is_pinned_and_anything_else_is_left_alone() {
        let _environment = exclusive_environment();
        // SAFETY: as above.
        for (name, scheme) in [("light", ColorScheme::Light), ("dark", ColorScheme::Dark)] {
            unsafe { std::env::set_var("DXC_SCHEME", name) };
            assert_eq!(
                demo_theme().color_scheme,
                scheme,
                "{name} is not selectable"
            );
        }

        unsafe { std::env::set_var("DXC_SCHEME", "nonsense") };
        assert_eq!(demo_theme().color_scheme, ColorScheme::FollowSystem);

        unsafe { std::env::remove_var("DXC_SCHEME") };
        assert_eq!(demo_theme().color_scheme, ColorScheme::FollowSystem);
    }

    /// A sample that names its own theme keeps it, and each variable overrides only the
    /// half it names. Asking to see Liquid Glass must not also throw away the colour
    /// scheme the design was drawn for.
    #[test]
    fn fr14_a_samples_own_theme_survives_everything_the_variables_do_not_name() {
        let _environment = exclusive_environment();
        let sample = Theme::unified(DesignSystem::Cupertino).with_color_scheme(ColorScheme::Light);
        assert_eq!(demo_theme_for(sample), sample);

        unsafe { std::env::set_var("DXC_DESIGN", "liquidglass") };
        assert_eq!(
            demo_theme_for(sample),
            Theme::unified(DesignSystem::LiquidGlass).with_color_scheme(ColorScheme::Light)
        );

        unsafe { std::env::set_var("DXC_SCHEME", "dark") };
        assert_eq!(
            demo_theme_for(sample),
            Theme::unified(DesignSystem::LiquidGlass).with_color_scheme(ColorScheme::Dark)
        );

        unsafe {
            std::env::remove_var("DXC_DESIGN");
            std::env::remove_var("DXC_SCHEME");
        };
    }
    /// A renderer built from a different schema is refused, and says so.
    ///
    /// The refusal already worked. What did not was finding out why: the renderer stands
    /// its window up before it asks the Host anything, so a mismatch leaves an empty page
    /// in the default theme and a status code, which reads as an application that draws
    /// nothing rather than as a build that is out of date. It cost a morning once.
    #[test]
    fn pr2_a_handshake_from_another_schema_is_refused() {
        let mut wrong = Vec::with_capacity(12);
        wrong.extend_from_slice(&SCHEMA_HASH.wrapping_add(1).to_le_bytes());
        wrong.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
        wrong.extend_from_slice(&[LoopMode::Renderer as u8, 0]);
        assert!(
            matches!(
                parse_handshake(&wrong),
                Err(crate::protocol::ProtocolError::InvalidEnvelope)
            ),
            "a handshake carrying another schema's hash was accepted, so the two halves \
             would go on to disagree about what every record means"
        );

        let mut right = Vec::with_capacity(12);
        right.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
        right.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
        right.extend_from_slice(&[LoopMode::Renderer as u8, 0]);
        assert!(
            parse_handshake(&right).is_ok(),
            "the handshake this build generates is not one it accepts"
        );
    }
}

/// Keeps the five exported boundary functions in the final executable.
///
/// The Renderer resolves them by name once it is loaded, which means nothing in the
/// application ever refers to them and the linker is free to conclude they are dead. It
/// does exactly that in any binary whose own code happens not to reach them, a test
/// harness for an application being the ordinary case, and the result is a process that
/// dies on startup with the dynamic loader unable to bind a symbol that should have been
/// right there in the executable.
///
/// Taking their addresses in a static the compiler is told to keep marks them as roots
/// for the dead-code pass, which is the whole job. It costs five pointers.
#[used]
static BOUNDARY_EXPORTS: BoundaryExports = BoundaryExports([
    dioxus_compose_host_init as *const (),
    dioxus_compose_host_dispatch_event as *const (),
    dioxus_compose_host_render_frame as *const (),
    dioxus_compose_host_release_batch as *const (),
    dioxus_compose_host_shutdown as *const (),
]);

/// The same five names, as a linker directive the object file carries.
///
/// The Renderer finds these with GetProcAddress against the running executable, which
/// reads the PE export table, and an executable has no export table unless the link is
/// told to make one. The build script asks for that with `/EXPORT:` arguments, and those
/// reach the binaries of this package and no further: Cargo does not pass a dependency's
/// link arguments on to whatever depends on it.
///
/// So every application built on this crate linked without an export table, the Renderer
/// could not reach back into it, and the window came up empty. It looked like it worked
/// because the only Windows binaries anyone had run were this package's own examples.
///
/// `.drectve` is how an object file carries linker arguments of its own. The MSVC linker
/// reads that section out of every object it links, so the directive travels inside the
/// rlib and applies wherever the rlib ends up.
#[cfg(all(target_os = "windows", target_env = "msvc"))]
const EXPORT_DIRECTIVES: &str = concat!(
    " /EXPORT:dioxus_compose_host_init",
    " /EXPORT:dioxus_compose_host_dispatch_event",
    " /EXPORT:dioxus_compose_host_render_frame",
    " /EXPORT:dioxus_compose_host_release_batch",
    " /EXPORT:dioxus_compose_host_shutdown",
);

#[cfg(all(target_os = "windows", target_env = "msvc"))]
#[used]
#[unsafe(link_section = ".drectve")]
static EXPORT_DIRECTIVE_BYTES: [u8; EXPORT_DIRECTIVES.len()] = {
    let source = EXPORT_DIRECTIVES.as_bytes();
    let mut bytes = [0u8; EXPORT_DIRECTIVES.len()];
    let mut index = 0;
    while index < source.len() {
        bytes[index] = source[index];
        index += 1;
    }
    bytes
};

/// Never read. Being referenced is the entire contract.
struct BoundaryExports(#[allow(dead_code)] [*const (); 5]);

// SAFETY: The addresses are written once, at compile time, and never read. A static has
// to be Sync to exist at all, and raw pointers decline to be only because of what they
// might point at; these point at code.
unsafe impl Sync for BoundaryExports {}
