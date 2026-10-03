use crate::Selection;
use crate::protocol::{HostEvent, ProtocolError, decode_event};
use crate::runtime::Runtime;
use crate::schema::{
    AssetKind, EventPayload, IconRole, LoopMode, PROTOCOL_VERSION, SCHEMA_HASH, Theme,
};
use std::cell::RefCell;
use std::ffi::c_int;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::task::{Context, Wake, Waker};

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
    fn compose_rust_renderer_run() -> c_int;
    fn compose_rust_renderer_request_frame();
}

// Defined by the small library the Linux static renderer ships beside its archive. The
// renderer finds the Host's functions with dlsym, so the application has to export them,
// and that library is what makes the linker do it: it needs them, and a linker exports
// from an executable what a library it links needs. Rust links with --as-needed, which
// drops a library nothing refers to, so this byte is the reference that keeps it.
#[cfg(all(
    renderer_host_exports,
    renderer_linked,
    not(any(test, feature = "mock-renderer"))
))]
unsafe extern "C" {
    static compose_rust_renderer_host_exports: u8;
}

#[cfg(all(renderer_linked, not(any(test, feature = "mock-renderer"))))]
extern "C" fn native_run() -> c_int {
    // A volatile read so that the reference survives optimisation. The value means
    // nothing; being linked is the point.
    #[cfg(renderer_host_exports)]
    // SAFETY: a byte the library defines as a constant.
    let _ = unsafe { std::ptr::read_volatile(&raw const compose_rust_renderer_host_exports) };
    // SAFETY: The application links the Renderer implementation of this declared C ABI.
    unsafe { compose_rust_renderer_run() }
}

#[cfg(all(renderer_linked, not(any(test, feature = "mock-renderer"))))]
extern "C" fn native_request_frame() {
    // SAFETY: The Renderer contract makes request_frame thread-safe.
    unsafe { compose_rust_renderer_request_frame() }
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
        "compose-rust: this application was built without a renderer, so there is nothing\n\
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

/// Coalesced wake used internally by the runtime's scheduler waker.
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

/// Makes the runtime a Host drives. Called once per Host, and again on a resync, which
/// is the one thing a diff against a lost node table cannot answer.
///
/// Shared across threads because `launch` runs on the Rust main thread and the Host is
/// built on the Renderer's UI thread, which is a different one under
/// `LoopMode::Renderer`. The runtime it makes is not shared: it lives and dies on the UI
/// thread.
pub type RuntimeFactory = Arc<dyn Fn() -> Box<dyn Runtime> + Send + Sync>;

pub struct Host {
    /// Kept so the tree can be built again from nothing when the Renderer asks for a
    /// resync, which is the one thing a diff against a lost node table cannot answer.
    factory: RuntimeFactory,
    theme: Theme,
    window: crate::schema::Window,
    runtime: Box<dyn Runtime>,
    frame_waker: Waker,
    pending_appends: Vec<PendingAppend>,
}

impl Host {
    /// A Host for the runtime `factory` makes, in the theme the application launched with.
    pub fn new(factory: impl Fn() -> Box<dyn Runtime> + Send + Sync + 'static) -> Self {
        Self::from_factory(Arc::new(factory), launched_theme())
    }

    /// The theme the application chose. Choosing nothing follows the host platform, with
    /// Material 3 where the platform has no look of its own.
    pub fn with_theme(
        factory: impl Fn() -> Box<dyn Runtime> + Send + Sync + 'static,
        theme: Theme,
    ) -> Self {
        Self::from_factory(Arc::new(factory), theme)
    }

    fn from_factory(factory: RuntimeFactory, theme: Theme) -> Self {
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
        let runtime = factory();
        Self {
            factory,
            theme,
            window: launched_window(),
            runtime,
            frame_waker: Waker::from(Arc::new(FrameWake)),
            pending_appends: Vec::new(),
        }
    }

    fn batch(&mut self) -> &mut crate::runtime::Batch {
        self.runtime.batch_mut()
    }

    pub fn rebuild(&mut self) -> Result<&[u8], ProtocolError> {
        let _call = HostCallGuard::enter();
        self.batch().begin();
        // One record at the root, before any node exists. The Renderer resolves roles to
        // values, so switching theme or colour scheme costs this one record rather than a
        // SetProp for every node in the tree.
        let (theme, window) = (self.theme, self.window);
        self.batch().set_theme(theme);
        // Beside the theme, and for the same reason: it is about the window rather than
        // any node in it, and it is settled once rather than every frame. The Renderer
        // reads it out of this batch before it stands the window up.
        self.batch().set_window(window);
        self.runtime.rebuild();
        self.flush_messages();
        self.arm_scheduler_wake();
        self.runtime.batch_mut().finish()
    }

    pub fn dispatch_event(&mut self, bytes: &[u8]) -> Result<(&[u8], i64), ProtocolError> {
        let event = decode_event(bytes)?;
        self.dispatch(event)
    }

    /// The batch arena, reported to the Renderer on every boundary call.
    pub fn arena(&self) -> (*const u8, usize) {
        self.runtime.batch().arena()
    }

    /// Answers with the whole tree, for a Renderer that no longer has a node table.
    ///
    /// The Host keeps no shadow of what it has already sent, so the only way to produce a
    /// full-tree batch is to build the application again, and that resets component state.
    /// A Renderer that keeps its node table across a configuration change never needs
    /// this call, which is what the Android host does.
    pub fn resync(&mut self) -> Result<(&[u8], i64), ProtocolError> {
        *self = Self::from_factory(self.factory.clone(), self.theme);
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
        self.batch().begin();
        Ok((self.runtime.batch_mut().finish()?, 0))
    }

    /// Renders what the runtime has waiting, writes the Host's own records after it, and
    /// closes the batch. The tail every call that renders shares.
    fn render_and_finish(&mut self, result: i64) -> Result<(&[u8], i64), ProtocolError> {
        self.batch().begin();
        self.runtime.render();
        self.flush_messages();
        self.arm_scheduler_wake();
        Ok((self.runtime.batch_mut().finish()?, result))
    }

    /// An empty batch, for an event that changed nothing anyone is reading.
    fn empty_batch(&mut self) -> Result<(&[u8], i64), ProtocolError> {
        self.batch().begin();
        Ok((self.runtime.batch_mut().finish()?, 0))
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
        self.batch().begin();
        if woke {
            self.runtime.render();
        }
        self.flush_messages();
        self.arm_scheduler_wake();
        Ok((self.runtime.batch_mut().finish()?, 0))
    }

    /// Records whether notifications may be shown, and renders the components that asked.
    ///
    /// The same shape as the resolved design system: sent once after start and again only
    /// when it changes, and a repeat wakes nobody.
    fn publish_notification_permission(
        &mut self,
        state: crate::schema::NotificationPermission,
    ) -> Result<(&[u8], i64), ProtocolError> {
        self.batch().begin();
        if crate::notification::publish_permission(state) {
            self.runtime.render();
        }
        self.flush_messages();
        self.arm_scheduler_wake();
        Ok((self.runtime.batch_mut().finish()?, 0))
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
            return self.render_and_finish(0);
        }
        self.empty_batch()
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
                    .runtime
                    .size_token(event.node_id)
                    .is_some_and(|token| crate::window::publish_node(token, size));
                if !woke {
                    return self.empty_batch();
                }
                return self.render_and_finish(0);
            }
            crate::window::publish(size);
            return self.render_and_finish(0);
        }
        // The action on a transient message. It belongs to no node, because the message is
        // not in the tree, so it is found by its handler id alone and the event carries the
        // "no node" id the Renderer uses for anything the tree does not own.
        let message_action = match event.payload {
            EventPayload::Clicked => crate::message::take_action(event.handler_id),
            _ => None,
        };
        if let Some(mut action) = message_action {
            if event.node_id != 0 {
                return Err(ProtocolError::InvalidValueKind(0));
            }
            let _dispatch_guard = EventDispatchGuard::enter();
            self.runtime.run_in_context(&mut *action);
            return self.render_and_finish(0);
        }
        // Everything else names a handler on a node, and handlers belong to the runtime.
        if let EventPayload::ProtocolError { .. } = event.payload {
            return Err(ProtocolError::InvalidValueKind(0));
        }
        let _dispatch_guard = EventDispatchGuard::enter();
        let result = self.runtime.handle_event(&event)?;
        self.render_and_finish(result)
    }

    pub fn render_frame(&mut self, _frame_time_nanos: u64) -> Result<&[u8], ProtocolError> {
        let _call = HostCallGuard::enter();
        if LIFECYCLE_SUPPRESSED.load(Ordering::Acquire) {
            // The platform has the UI off screen and the Renderer called anyway, which it
            // does for one reason: a notification was queued, and a notification is what
            // has to get out while nobody is looking. Only that goes. The components are
            // not rendered and the held frame request is left held, so timers and
            // animations stay suppressed until start delivers it.
            self.batch().begin();
            self.flush_notifications();
            return self.runtime.batch_mut().finish();
        }
        FRAME_REQUESTED.store(false, Ordering::Release);
        EVENT_DISPATCH_ACTIVE.store(false, Ordering::Release);
        DEFERRED_FRAME_REQUEST.store(false, Ordering::Release);
        self.batch().begin();
        self.runtime.render();
        self.flush_pending_appends();
        self.flush_messages();
        self.arm_scheduler_wake();
        self.runtime.batch_mut().finish()
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
            self.batch().set_theme(theme);
        }
        let batch = self.runtime.batch_mut();
        // Registrations first. Not because the Renderer needs them first, it applies the
        // whole batch before drawing any of it, but because a batch read by a person
        // debugging one reads in the order the screen was built.
        crate::asset::drain(|pending| {
            batch.register_asset(pending.asset_id, pending.kind, pending.bytes);
        });
        crate::message::drain(|message| {
            batch.show_message(
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
        let batch = self.runtime.batch_mut();
        let posts = crate::notification::drain(|command| batch.notification(command));
        crate::notification::note_posted(posts);
    }

    fn flush_pending_appends(&mut self) {
        let batch = self.runtime.batch_mut();
        for pending in &self.pending_appends {
            if pending.dirty {
                batch.append_text_node(pending.node_id, &pending.text);
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
        let batch = self.runtime.batch_mut();
        batch.begin();
        batch.register_asset(asset_id, kind, bytes);
        batch.finish()
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
        let batch = self.runtime.batch_mut();
        batch.begin();
        batch.release_asset(asset_id);
        batch.finish()
    }

    pub fn set_text(
        &mut self,
        node_id: u32,
        text: &str,
        selection: Option<Selection>,
    ) -> Result<&[u8], ProtocolError> {
        let batch = self.runtime.batch_mut();
        batch.begin();
        batch.set_text_node(node_id, text, selection);
        batch.finish()
    }

    fn arm_scheduler_wake(&mut self) {
        let mut context = Context::from_waker(&self.frame_waker);
        if self.runtime.poll_work(&mut context).is_ready() {
            request_frame_from_worker();
        }
    }
}

/// Holds the thread's `Host` and, crucially, keeps it out of thread-local teardown.
///
/// Dropping a runtime can reach back into thread-locals of its own: a Dioxus
/// `VirtualDom` does. Thread-local destruction order is unspecified, so if the UI thread
/// ends without `compose_rust_host_shutdown`, that drop can run after those locals are
/// already gone and panic with "cannot access a TLS value during or after destruction".
/// A panic in a destructor is non-unwinding: it aborts the process, which
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

/// The launched application, as the factory for its runtime.
///
/// The runtime and every `compose_rust_host_*` call run on the Renderer UI thread, and
/// that thread is not the one that called `launch`: with `LoopMode::Renderer` the Rust
/// main thread blocks inside `compose_rust_renderer_run` while Compose composes on the
/// toolkit's own thread. So the factory, unlike the `Host` it builds, has to be reachable
/// across threads, which is why it is `Send + Sync` and the runtime it makes is not.
///
/// `shutdown` deliberately leaves this set: it belongs to `launch`, not to one UI thread's
/// `Host`. That is also what `LoopMode::Platform` needs, where the Renderer may tear the
/// Host down and initialize it again (Android recreates its surface on a configuration
/// change) without relaunching.
static APP: Mutex<Option<RuntimeFactory>> = Mutex::new(None);

/// Chosen by `LaunchBuilder::with_theme`, read once when the Host is built.
static THEME: Mutex<Theme> = Mutex::new(Theme::unified(crate::schema::DesignSystem::Material3));

/// Chosen by `LaunchBuilder::with_window`, read once when the Host is built.
static WINDOW: Mutex<crate::schema::Window> = Mutex::new(crate::schema::Window::new());

thread_local! {
    static HOST: HostSlot = const { HostSlot(RefCell::new(None)) };
}

fn launched_app() -> Option<RuntimeFactory> {
    APP.lock().map_or(None, |app| (*app).clone())
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

    /// Runs the application whose tree `runtime` builds. Does not return while it is
    /// running, and ends the process with a failing status if the renderer loop could
    /// not run at all.
    ///
    /// `runtime` is called on the Renderer's UI thread each time a Host is made: once at
    /// start, and again whenever the Renderer asks for the whole tree back.
    ///
    /// Exiting rather than returning is the point. Launching is the last statement of
    /// `main` in every application that uses this crate, so returning from it means `main`
    /// returns, which means the process exits 0. A window that never opened would then be
    /// indistinguishable from a program that did its work and stopped.
    pub fn launch_runtime(self, runtime: impl Fn() -> Box<dyn Runtime> + Send + Sync + 'static) {
        let status = self.try_launch_runtime(runtime);
        if status != STATUS_OK {
            std::process::exit(EXIT_FAILURE);
        }
    }

    /// [`LaunchBuilder::launch_runtime`] without the exit: the status the renderer loop
    /// ended with, handed back for a caller that has its own idea of what to do with it.
    pub fn try_launch_runtime(
        self,
        runtime: impl Fn() -> Box<dyn Runtime> + Send + Sync + 'static,
    ) -> i32 {
        let factory: RuntimeFactory = Arc::new(runtime);
        if let Ok(mut slot) = APP.lock() {
            *slot = Some(factory);
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

/// [`LaunchBuilder::launch_runtime`] with every choice left to its default.
pub fn launch_runtime(runtime: impl Fn() -> Box<dyn Runtime> + Send + Sync + 'static) {
    LaunchBuilder::new().launch_runtime(runtime);
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
                format!("compose-rust: palette under {system:?}: {violation}")
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
            "compose-rust: the renderer was built from a different schema than this              program. It sent hash {hash:#x} version {version}, and this build expects              hash {SCHEMA_HASH:#x} version {PROTOCOL_VERSION}. Rebuild the renderer after              running codegen; if it was already rebuilt, its build directory is holding a              cached copy of the generated protocol and has to be cleared."
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
pub unsafe extern "C" fn compose_rust_host_init(
    handshake: *const u8,
    len: u32,
    out: *mut MutationBatch,
) -> i32 {
    ffi_status(|| {
        // SAFETY: Validated according to the function's C ABI contract.
        let bytes = unsafe { input_slice(handshake, len) }.map_err(protocol_error)?;
        let _mode = parse_handshake(bytes).map_err(protocol_error)?;
        let factory = launched_app().ok_or(STATUS_NOT_INITIALIZED)?;
        HOST.with(|slot| {
            let mut host_slot = slot.borrow_mut();
            if host_slot.is_some() {
                return Err(STATUS_ALREADY_INITIALIZED);
            }
            let mut host = Host::from_factory(factory, launched_theme());
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
pub unsafe extern "C" fn compose_rust_host_dispatch_event(
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
pub unsafe extern "C" fn compose_rust_host_render_frame(
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
pub unsafe extern "C" fn compose_rust_host_release_batch(batch: *mut MutationBatch) {
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
pub extern "C" fn compose_rust_host_shutdown() {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        HOST.with(|slot| *slot.borrow_mut() = None);
        FRAME_REQUESTED.store(false, Ordering::Release);
    }));
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `APP` is process-global, as launching is, so two tests that launch at the same
    /// time would each see the other's. Poisoning is ignored: a failing test has already
    /// reported itself, and the rest still need the lock.
    static LAUNCH: Mutex<()> = Mutex::new(());

    fn launch_guard() -> std::sync::MutexGuard<'static, ()> {
        LAUNCH
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    #[test]
    fn malformed_ffi_input_returns_protocol_error() {
        let mut output = MutationBatch::default();
        // SAFETY: The test passes a valid output pointer and intentionally null input.
        let status = unsafe { compose_rust_host_dispatch_event(std::ptr::null(), 4, &mut output) };
        assert_eq!(status, STATUS_PROTOCOL_ERROR);
    }

    #[test]
    fn all_exports_isolate_invalid_calls() {
        let _launch = launch_guard();
        let mut output = MutationBatch::default();
        // SAFETY: Each call uses null or valid test-owned pointers as documented.
        unsafe {
            assert_eq!(
                compose_rust_host_init(std::ptr::null(), 1, &mut output),
                STATUS_PROTOCOL_ERROR
            );
            // No handshake has run on this thread, so this is a call-order error.
            assert_eq!(
                compose_rust_host_render_frame(0, &mut output),
                STATUS_NOT_INITIALIZED
            );
            compose_rust_host_release_batch(std::ptr::null_mut());
        }
        compose_rust_host_shutdown();
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
    compose_rust_host_init as *const (),
    compose_rust_host_dispatch_event as *const (),
    compose_rust_host_render_frame as *const (),
    compose_rust_host_release_batch as *const (),
    compose_rust_host_shutdown as *const (),
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
    " /EXPORT:compose_rust_host_init",
    " /EXPORT:compose_rust_host_dispatch_event",
    " /EXPORT:compose_rust_host_render_frame",
    " /EXPORT:compose_rust_host_release_batch",
    " /EXPORT:compose_rust_host_shutdown",
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
