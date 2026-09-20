use crate::protocol::{HostEvent, ProtocolError, decode_event};
use crate::renderer::ComposeRenderer;
use crate::schema::{EventPayload, LoopMode, PROTOCOL_VERSION, SCHEMA_HASH, Theme};
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

pub fn install_renderer_api(api: RendererApi) -> Result<(), RendererApi> {
    RENDERER_API.set(api)
}

#[cfg(all(feature = "native-renderer", not(any(test, feature = "mock-renderer"))))]
unsafe extern "C" {
    fn dioxus_compose_renderer_run() -> c_int;
    fn dioxus_compose_renderer_request_frame();
}

#[cfg(all(feature = "native-renderer", not(any(test, feature = "mock-renderer"))))]
extern "C" fn native_run() -> c_int {
    // SAFETY: The application links the Renderer implementation of this declared C ABI.
    unsafe { dioxus_compose_renderer_run() }
}

#[cfg(all(feature = "native-renderer", not(any(test, feature = "mock-renderer"))))]
extern "C" fn native_request_frame() {
    // SAFETY: The Renderer contract makes request_frame thread-safe.
    unsafe { dioxus_compose_renderer_request_frame() }
}

#[cfg(any(test, feature = "mock-renderer", not(feature = "native-renderer")))]
extern "C" fn native_run() -> c_int {
    STATUS_OK
}

#[cfg(any(test, feature = "mock-renderer", not(feature = "native-renderer")))]
extern "C" fn native_request_frame() {}

fn renderer_api() -> RendererApi {
    RENDERER_API.get().copied().unwrap_or(RendererApi {
        run: native_run,
        request_frame: native_request_frame,
    })
}

/// Coalesced wake used internally by the Dioxus scheduler waker.
pub fn request_frame_from_worker() {
    if !FRAME_REQUESTED.swap(true, Ordering::AcqRel) {
        if EVENT_DISPATCH_ACTIVE.load(Ordering::Acquire) {
            DEFERRED_FRAME_REQUEST.store(true, Ordering::Release);
        } else {
            (renderer_api().request_frame)();
        }
    }
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
        if DEFERRED_FRAME_REQUEST.swap(false, Ordering::AcqRel) {
            (renderer_api().request_frame)();
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
    theme: Theme,
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
        Self {
            theme,
            dom: VirtualDom::new(app),
            renderer: ComposeRenderer::new(),
            frame_waker: Waker::from(Arc::new(FrameWake)),
            pending_appends: Vec::new(),
        }
    }

    pub fn rebuild(&mut self) -> Result<&[u8], ProtocolError> {
        self.renderer.begin_frame();
        // One record at the root, before any node exists. The Renderer resolves roles to
        // values, so switching theme or colour scheme costs this one record rather than a
        // SetProp for every node in the tree.
        self.renderer.set_theme(self.theme);
        self.dom.rebuild(&mut self.renderer);
        self.arm_scheduler_wake();
        self.renderer.finish_frame()
    }

    pub fn dispatch_event(&mut self, bytes: &[u8]) -> Result<(&[u8], i64), ProtocolError> {
        let event = decode_event(bytes)?;
        self.dispatch(event)
    }

    pub fn dispatch(&mut self, event: HostEvent<'_>) -> Result<(&[u8], i64), ProtocolError> {
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
            EventPayload::RangeRequested { start, count } => {
                Event::new(Rc::new(RangeRequest::new(start, count)), true).into_any()
            }
            EventPayload::ValueChanged { value } => Event::new(Rc::new(value), true).into_any(),
        };
        let _dispatch_guard = EventDispatchGuard::enter();
        self.dom.runtime().handle_event(name, event_data, element);
        self.renderer.begin_frame();
        self.dom.render_immediate(&mut self.renderer);
        self.arm_scheduler_wake();
        let result = i64::from(key_event.is_some_and(|event| event.consumed()));
        Ok((self.renderer.finish_frame()?, result))
    }

    pub fn render_frame(&mut self, _frame_time_nanos: u64) -> Result<&[u8], ProtocolError> {
        FRAME_REQUESTED.store(false, Ordering::Release);
        EVENT_DISPATCH_ACTIVE.store(false, Ordering::Release);
        DEFERRED_FRAME_REQUEST.store(false, Ordering::Release);
        self.renderer.begin_frame();
        self.dom.render_immediate(&mut self.renderer);
        self.flush_pending_appends();
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

thread_local! {
    static HOST: HostSlot = const { HostSlot(RefCell::new(None)) };
}

fn launched_app() -> Option<fn() -> Element> {
    APP.lock().map_or(None, |app| *app)
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
}

impl Default for LaunchBuilder {
    fn default() -> Self {
        Self {
            mode: LoopMode::Renderer,
            theme: Theme::default(),
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

    pub fn launch(self, app: fn() -> Element) {
        if let Ok(mut slot) = APP.lock() {
            *slot = Some(app);
        }
        if let Ok(mut slot) = THEME.lock() {
            *slot = self.theme;
        }
        if self.mode == LoopMode::Renderer {
            let _ = (renderer_api().run)();
        }
    }
}

pub fn launch(app: fn() -> Element) {
    LaunchBuilder::new().launch(app);
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
                    | Mutation::SetTheme(_) => {}
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
            })
        );
        assert_eq!(
            first_record(LaunchBuilder::new().with_theme(Theme::adaptive(DesignSystem::Cupertino))),
            Mutation::SetTheme(Theme {
                design_system: DesignSystem::Cupertino,
                fallback: DesignSystem::Cupertino,
                color_scheme: ColorScheme::FollowSystem,
                adaptive: true,
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
