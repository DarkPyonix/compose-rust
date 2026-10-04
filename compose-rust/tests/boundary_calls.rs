//! The boundary's calls as the Renderer makes them: the handshake, the thread it arrives
//! on, a keystroke's diff and its result, and the batch buffer the answers are written to.
//!
//! The runtime is written by hand, so nothing here depends on an authoring layer. What
//! these check is the boundary's: the Host answers an event with the diff on the same call
//! stack, the handler's result rides in the same struct, the arena is one buffer reused
//! rather than a queue, and a handshake that does not match is a status rather than an
//! abort.
//!
//! So the whole of a keystroke is two calls, and these tests are what would notice if it
//! stopped being two: a diff that needed collecting, a result that needed asking for, or a
//! frame that had to be rendered before the change was visible would each show up here.

use compose_rust::boundary::{
    MutationBatch, STATUS_OK, STATUS_PROTOCOL_ERROR, compose_rust_host_dispatch_event,
    compose_rust_host_init, compose_rust_host_release_batch, compose_rust_host_render_frame,
    compose_rust_host_shutdown,
};
use compose_rust::protocol::{
    HostEvent, Mutation, PropertyValue, ProtocolError, decode_batch, encode_event,
};
use compose_rust::schema::{
    EventPayload, Key, LoopMode, PROTOCOL_VERSION, PropertyKind, SCHEMA_HASH, WidgetKind,
};
use compose_rust::{Batch, LaunchBuilder, Runtime};
use std::sync::{Mutex, MutexGuard};

const COLUMN: u32 = 1;
/// Shows whatever was last typed into `FIELD`.
const LABEL: u32 = 2;
/// A multiline field: Enter without Shift is consumed, as a composer's send key would be.
const FIELD: u32 = 3;
/// A field whose key handler consumes nothing.
const PLAIN: u32 = 4;

const ON_VALUE_CHANGE: u64 = 1;
const FIELD_KEY_DOWN: u64 = 2;
const PLAIN_KEY_DOWN: u64 = 3;

/// What is launched is process-global, so the tests that launch take turns.
static LAUNCH: Mutex<()> = Mutex::new(());

fn launch_guard() -> MutexGuard<'static, ()> {
    LAUNCH
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

struct Composer {
    batch: Batch,
    typed: String,
    shown: String,
}

impl Composer {
    fn new() -> Self {
        Self {
            batch: Batch::new(),
            typed: String::new(),
            shown: String::new(),
        }
    }

    fn label(&mut self) {
        self.batch.write(Mutation::SetProp {
            node_id: LABEL,
            property: PropertyKind::Text,
            value: PropertyValue::String(&self.typed),
        });
        self.shown.clear();
        self.shown.push_str(&self.typed);
    }

    fn handler(&mut self, node_id: u32, property: PropertyKind, handler_id: u64) {
        self.batch.write(Mutation::SetProp {
            node_id,
            property,
            value: PropertyValue::Integer(handler_id as i64),
        });
    }
}

impl Runtime for Composer {
    fn batch(&self) -> &Batch {
        &self.batch
    }

    fn batch_mut(&mut self) -> &mut Batch {
        &mut self.batch
    }

    fn rebuild(&mut self) {
        for (node_id, widget) in [
            (COLUMN, WidgetKind::Column),
            (LABEL, WidgetKind::Text),
            (FIELD, WidgetKind::TextField),
            (PLAIN, WidgetKind::TextField),
        ] {
            self.batch.write(Mutation::Create { node_id, widget });
        }
        self.label();
        self.batch.write(Mutation::SetProp {
            node_id: FIELD,
            property: PropertyKind::Multiline,
            value: PropertyValue::Bool(true),
        });
        self.handler(FIELD, PropertyKind::OnValueChange, ON_VALUE_CHANGE);
        self.handler(FIELD, PropertyKind::OnKeyDown, FIELD_KEY_DOWN);
        self.handler(PLAIN, PropertyKind::OnKeyDown, PLAIN_KEY_DOWN);
        for (index, node_id) in [LABEL, FIELD, PLAIN].into_iter().enumerate() {
            self.batch.write(Mutation::Insert {
                parent_id: COLUMN,
                node_id,
                index: index as u32,
            });
        }
    }

    fn render(&mut self) {
        if self.shown != self.typed {
            self.label();
        }
    }

    fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        match (event.node_id, event.handler_id, &event.payload) {
            (FIELD, ON_VALUE_CHANGE, EventPayload::TextChanged(value)) => {
                self.typed.clear();
                self.typed.push_str(value);
                Ok(0)
            }
            (FIELD, FIELD_KEY_DOWN, EventPayload::KeyDown { key, shift_key, .. }) => {
                Ok(i64::from(*key == Key::Enter && !*shift_key))
            }
            (PLAIN, PLAIN_KEY_DOWN, EventPayload::KeyDown { .. }) => Ok(0),
            _ => Err(ProtocolError::InvalidValueKind(0)),
        }
    }
}

fn launch_composer() {
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .try_launch_runtime(|| Box::new(Composer::new()) as Box<dyn Runtime>);
}

fn handshake() -> Vec<u8> {
    let mut bytes = Vec::with_capacity(12);
    bytes.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
    bytes.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
    bytes.extend_from_slice(&[LoopMode::Platform as u8, 0]);
    bytes
}

/// The batch as the Renderer reads it: the bytes are the Host's arena, read where they lie.
fn batch_bytes(batch: &MutationBatch) -> &[u8] {
    assert!(!batch.ptr.is_null(), "the batch carried no arena pointer");
    // SAFETY: The Host wrote the pointer and length of its own live arena, and nothing has
    // been released or dispatched since.
    unsafe { std::slice::from_raw_parts(batch.ptr, batch.len as usize) }
}

/// Brings the Host up on this thread and releases the batch the handshake produced.
fn start() {
    launch_composer();
    let bytes = handshake();
    let mut first = MutationBatch::default();
    // SAFETY: The handshake buffer and `first` are live test-owned storage.
    let status = unsafe { compose_rust_host_init(bytes.as_ptr(), bytes.len() as u32, &mut first) };
    assert_eq!(status, STATUS_OK, "the handshake was refused");
    // SAFETY: `first` is this test's own storage.
    unsafe { compose_rust_host_release_batch(&mut first) };
}

fn encoded(event: HostEvent<'_>) -> Vec<u8> {
    let mut bytes = Vec::new();
    encode_event(&event, &mut bytes).expect("the event did not encode");
    bytes
}

fn typed_event(text: &str) -> Vec<u8> {
    encoded(HostEvent {
        node_id: FIELD,
        handler_id: ON_VALUE_CHANGE,
        payload: EventPayload::TextChanged(text),
    })
}

/// The text the batch puts on screen, whether it replaced the whole property or the node's
/// text alone.
fn texts(batch: &MutationBatch) -> Vec<String> {
    decode_batch(batch_bytes(batch))
        .expect("the diff did not decode")
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetText { text, .. } => Some((*text).to_string()),
            Mutation::SetProp {
                property: PropertyKind::Text,
                value: PropertyValue::String(text),
                ..
            } => Some((*text).to_string()),
            _ => None,
        })
        .collect()
}

#[test]
fn pr4_a_text_change_costs_two_boundary_calls() {
    let _launch = launch_guard();
    start();
    let event = typed_event("typed into the field");
    let mut batch = MutationBatch::default();

    // One. The diff is already here when it returns, and so is the handler's answer.
    // SAFETY: The event buffer and `batch` are live test-owned storage.
    let status =
        unsafe { compose_rust_host_dispatch_event(event.as_ptr(), event.len() as u32, &mut batch) };
    assert_eq!(status, STATUS_OK);
    assert!(
        batch.len > 0,
        "the dispatch returned an empty batch, so the change would have to be collected by \
         some later call"
    );
    assert!(
        texts(&batch).contains(&"typed into the field".to_string()),
        "the dispatch batch did not carry the new text: {:?}",
        texts(&batch)
    );
    // The handler's synchronous result is a field of the same struct, so reading it is not
    // a call of its own.
    assert_eq!(batch.result, 0);

    // Two. After this the arena is the next event's to write into.
    // SAFETY: `batch` is this test's own storage.
    unsafe { compose_rust_host_release_batch(&mut batch) };
    assert!(batch.ptr.is_null(), "release left the batch readable");
    compose_rust_host_shutdown();
}

#[test]
fn pr4_nothing_is_left_for_a_third_call() {
    let _launch = launch_guard();
    start();
    let event = typed_event("already applied");
    let mut batch = MutationBatch::default();
    // SAFETY: The event buffer and `batch` are live test-owned storage.
    let status =
        unsafe { compose_rust_host_dispatch_event(event.as_ptr(), event.len() as u32, &mut batch) };
    assert_eq!(status, STATUS_OK);
    assert!(texts(&batch).contains(&"already applied".to_string()));
    // SAFETY: `batch` is this test's own storage.
    unsafe { compose_rust_host_release_batch(&mut batch) };

    // A Renderer that had to render a frame to see the keystroke would find work here.
    let mut frame = MutationBatch::default();
    // SAFETY: `frame` is live test-owned storage.
    let status = unsafe { compose_rust_host_render_frame(0, &mut frame) };
    assert_eq!(status, STATUS_OK);
    let left_over = decode_batch(batch_bytes(&frame)).expect("the frame did not decode");
    assert!(
        left_over.is_empty(),
        "the frame after the keystroke still carried {left_over:?}, so the change was not \
         finished when dispatch returned"
    );
    // SAFETY: `frame` is this test's own storage.
    unsafe { compose_rust_host_release_batch(&mut frame) };
    compose_rust_host_shutdown();
}

#[test]
fn pr4_the_batch_buffer_is_an_argument_and_not_a_queue() {
    let _launch = launch_guard();
    start();
    let mut arenas = Vec::new();
    for text in ["first", "second", "third"] {
        let event = typed_event(text);
        let mut batch = MutationBatch::default();
        // SAFETY: The event buffer and `batch` are live test-owned storage.
        let status = unsafe {
            compose_rust_host_dispatch_event(event.as_ptr(), event.len() as u32, &mut batch)
        };
        assert_eq!(status, STATUS_OK);
        assert!(texts(&batch).contains(&text.to_string()));
        arenas.push(batch.ptr);
        // SAFETY: `batch` is this test's own storage.
        unsafe { compose_rust_host_release_batch(&mut batch) };
    }
    // The second and third keystrokes were written where the first one was. A queue would
    // have handed out somewhere else to keep the earlier batches readable; this is one
    // buffer, reused, and that is why it has to be released before the next call.
    assert_eq!(
        arenas[0], arenas[1],
        "the second keystroke was encoded into different storage"
    );
    assert_eq!(
        arenas[1], arenas[2],
        "the third keystroke was encoded into different storage"
    );
    compose_rust_host_shutdown();
}

/// A key handler's answer is the call's result, and it belongs to that call alone: the
/// next key, on the same field or another, starts from nothing.
#[test]
fn fr12_key_consumption_is_returned_and_does_not_leak() {
    let _launch = launch_guard();
    start();
    let dispatch = |node_id, handler_id, shift_key, output: &mut MutationBatch| {
        let bytes = encoded(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::KeyDown {
                key: Key::Enter,
                shift_key,
                ctrl_key: false,
                alt_key: false,
                meta_key: false,
            },
        });
        // SAFETY: The encoded event and output storage remain live for the call.
        let status =
            unsafe { compose_rust_host_dispatch_event(bytes.as_ptr(), bytes.len() as u32, output) };
        assert_eq!(status, STATUS_OK);
    };

    let mut output = MutationBatch::default();
    dispatch(FIELD, FIELD_KEY_DOWN, false, &mut output);
    assert_ne!(output.result, 0, "Enter in the composer was consumed");
    dispatch(FIELD, FIELD_KEY_DOWN, true, &mut output);
    assert_eq!(output.result, 0, "Shift+Enter in the composer was not");
    dispatch(PLAIN, PLAIN_KEY_DOWN, false, &mut output);
    assert_eq!(
        output.result, 0,
        "the plain field consumes nothing, whatever the composer answered before"
    );
    dispatch(FIELD, FIELD_KEY_DOWN, false, &mut output);
    assert_ne!(output.result, 0);
    compose_rust_host_shutdown();
}

/// The application launches on its own main thread, but the Renderer UI thread that calls
/// `compose_rust_host_init` is a different one (on macOS, AppKit's main thread inside the
/// renderer). The runtime the application registered must be reachable from there.
#[test]
fn pr3_init_runs_on_a_different_thread_than_launch() {
    let _launch = launch_guard();
    launch_composer();
    let handshake = handshake();
    std::thread::spawn(move || {
        let mut output = MutationBatch::default();
        // SAFETY: Both buffers are live test-owned storage for the duration of the call.
        let status = unsafe {
            compose_rust_host_init(handshake.as_ptr(), handshake.len() as u32, &mut output)
        };
        assert_eq!(status, STATUS_OK, "init must work off the launch thread");
        assert!(output.len > 0, "init returns the initial tree batch");
        compose_rust_host_shutdown();
    })
    .join()
    .unwrap();
}

/// A Renderer generated from a different schema is refused with a status, not a crash.
#[test]
fn nfr7_init_with_a_wrong_schema_hash_returns_a_status() {
    let _launch = launch_guard();
    launch_composer();
    let mut bytes = handshake();
    bytes[0] ^= 0xFF;
    let mut out = MutationBatch::default();
    // SAFETY: The buffer and `out` are live test-owned storage.
    let status = unsafe { compose_rust_host_init(bytes.as_ptr(), bytes.len() as u32, &mut out) };
    assert_eq!(status, STATUS_PROTOCOL_ERROR);
    compose_rust_host_shutdown();
}
