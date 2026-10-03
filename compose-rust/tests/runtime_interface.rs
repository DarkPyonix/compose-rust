//! The boundary driving a runtime that is not Dioxus.
//!
//! compose-rust owns the five entry points and every record that is not the tree; the tree
//! is whatever runtime the application registered. This file registers one written by
//! hand, with no Dioxus anywhere in the test's build, and drives it through the same C
//! entry points the Renderer calls. If the boundary still assumed a `VirtualDom`
//! somewhere, this is where it would show.

use compose_rust::boundary::{
    MutationBatch, STATUS_OK, compose_rust_host_dispatch_event, compose_rust_host_init,
    compose_rust_host_render_frame, compose_rust_host_shutdown,
};
use compose_rust::protocol::{
    HostEvent, Mutation, PropertyValue, ProtocolError, decode_batch, encode_event,
};
use compose_rust::schema::{
    EventPayload, LoopMode, PROTOCOL_VERSION, PropertyKind, SCHEMA_HASH, WidgetKind,
};
use compose_rust::{Batch, LaunchBuilder, Message, Runtime};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Mutex, MutexGuard};

const BUTTON: u32 = 1;
const ON_CLICK: u64 = 1;

static FACTORY_CALLS: AtomicUsize = AtomicUsize::new(0);
static IN_CONTEXT: AtomicBool = AtomicBool::new(false);
static ACTION_RAN_IN_CONTEXT: AtomicBool = AtomicBool::new(false);

/// What is launched is process-global, so the tests that launch take turns.
static LAUNCH: Mutex<()> = Mutex::new(());

fn launch_guard() -> MutexGuard<'static, ()> {
    LAUNCH
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// A counter button, kept by hand: one node, one handler, and a label that is the count.
struct Counter {
    batch: Batch,
    count: i64,
    shown: i64,
}

impl Counter {
    fn new() -> Self {
        FACTORY_CALLS.fetch_add(1, Ordering::SeqCst);
        Self {
            batch: Batch::new(),
            count: 0,
            shown: 0,
        }
    }

    fn label(&mut self) {
        let text = self.count.to_string();
        self.batch.write(Mutation::SetProp {
            node_id: BUTTON,
            property: PropertyKind::Text,
            value: PropertyValue::String(&text),
        });
        self.shown = self.count;
    }
}

impl Runtime for Counter {
    fn batch(&self) -> &Batch {
        &self.batch
    }

    fn batch_mut(&mut self) -> &mut Batch {
        &mut self.batch
    }

    fn rebuild(&mut self) {
        self.batch.write(Mutation::Create {
            node_id: BUTTON,
            widget: WidgetKind::Button,
        });
        self.label();
        self.batch.write(Mutation::SetProp {
            node_id: BUTTON,
            property: PropertyKind::OnClick,
            value: PropertyValue::Integer(ON_CLICK as i64),
        });
    }

    fn render(&mut self) {
        if self.shown != self.count {
            self.label();
        }
    }

    fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        if event.handler_id != ON_CLICK || event.node_id != BUTTON {
            return Err(ProtocolError::InvalidValueKind(0));
        }
        let EventPayload::Clicked = event.payload else {
            return Err(ProtocolError::InvalidValueKind(0));
        };
        self.count += 1;
        if self.count == 2 {
            Message::new("Counted twice")
                .with_action("Undo", |()| {
                    ACTION_RAN_IN_CONTEXT
                        .store(IN_CONTEXT.load(Ordering::SeqCst), Ordering::SeqCst);
                })
                .show();
        }
        Ok(self.count)
    }

    fn run_in_context(&mut self, action: &mut dyn FnMut()) {
        IN_CONTEXT.store(true, Ordering::SeqCst);
        action();
        IN_CONTEXT.store(false, Ordering::SeqCst);
    }
}

fn handshake() -> Vec<u8> {
    let mut bytes = Vec::with_capacity(12);
    bytes.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
    bytes.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
    bytes.extend_from_slice(&[LoopMode::Platform as u8, 0]);
    bytes
}

fn launch_counter() {
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .try_launch_runtime(|| Box::new(Counter::new()) as Box<dyn Runtime>);
}

/// The batch a call returned, decoded and owned so the next call can be made.
fn records(output: &MutationBatch) -> Vec<String> {
    // SAFETY: a successful call wrote the pointer and length of the Host's live arena, and
    // nothing has been called since.
    let bytes = unsafe { std::slice::from_raw_parts(output.ptr, output.len as usize) };
    decode_batch(bytes)
        .unwrap()
        .iter()
        .map(|mutation| format!("{mutation:?}"))
        .collect()
}

fn click(node_id: u32, handler_id: u64, output: &mut MutationBatch) -> i32 {
    let mut bytes = Vec::new();
    encode_event(
        &HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        },
        &mut bytes,
    )
    .unwrap();
    // SAFETY: the event bytes and the output storage are live for the whole call.
    unsafe { compose_rust_host_dispatch_event(bytes.as_ptr(), bytes.len() as u32, output) }
}

/// The handshake builds the registered runtime and answers with the Host's records first,
/// then the runtime's tree. An event reaches the runtime's handler, its result comes back
/// as the call's result, and the diff is what the runtime wrote.
#[test]
fn fr39_the_boundary_drives_a_runtime_that_is_not_dioxus() {
    let _launch = launch_guard();
    launch_counter();
    std::thread::spawn(|| {
        let handshake = handshake();
        let mut output = MutationBatch::default();
        // SAFETY: both buffers are live test-owned storage for the call.
        let status = unsafe {
            compose_rust_host_init(handshake.as_ptr(), handshake.len() as u32, &mut output)
        };
        assert_eq!(status, STATUS_OK);
        let initial = records(&output);
        assert!(initial[0].starts_with("SetTheme"), "{initial:?}");
        assert!(initial[1].starts_with("SetWindow"), "{initial:?}");
        assert!(
            initial[2].starts_with("Create") && initial[2].contains("Button"),
            "the runtime's tree follows the Host's own records: {initial:?}"
        );

        assert_eq!(click(BUTTON, ON_CLICK, &mut output), STATUS_OK);
        assert_eq!(
            output.result, 1,
            "the handler's result is the call's result"
        );
        let diff = records(&output);
        assert_eq!(diff.len(), 1, "one label changed, one record: {diff:?}");
        assert!(
            diff[0].contains("Text") && diff[0].contains("\"1\""),
            "{diff:?}"
        );

        // Nothing changed since, so a frame writes nothing.
        // SAFETY: `output` is live test-owned storage.
        let status = unsafe { compose_rust_host_render_frame(0, &mut output) };
        assert_eq!(status, STATUS_OK);
        assert!(records(&output).is_empty());

        // A handler the runtime does not have is refused rather than guessed at.
        assert_ne!(click(BUTTON, ON_CLICK + 1, &mut output), STATUS_OK);
        compose_rust_host_shutdown();
    })
    .join()
    .unwrap();
}

/// A message the runtime's handler showed rides out in that handler's batch, and its action
/// runs through the runtime, inside whatever context the runtime gives it.
#[test]
fn fr39_a_message_action_runs_inside_the_runtime() {
    let _launch = launch_guard();
    launch_counter();
    ACTION_RAN_IN_CONTEXT.store(false, Ordering::SeqCst);
    std::thread::spawn(|| {
        let handshake = handshake();
        let mut output = MutationBatch::default();
        // SAFETY: both buffers are live test-owned storage for the call.
        let status = unsafe {
            compose_rust_host_init(handshake.as_ptr(), handshake.len() as u32, &mut output)
        };
        assert_eq!(status, STATUS_OK);
        assert_eq!(click(BUTTON, ON_CLICK, &mut output), STATUS_OK);
        assert_eq!(click(BUTTON, ON_CLICK, &mut output), STATUS_OK);

        // SAFETY: the call above succeeded and nothing has been called since.
        let bytes = unsafe { std::slice::from_raw_parts(output.ptr, output.len as usize) };
        let action = decode_batch(bytes)
            .unwrap()
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::ShowMessage { handler_id, .. } if *handler_id != 0 => Some(*handler_id),
                _ => None,
            })
            .expect("the message goes out in the batch of the click that showed it");

        // A message is not in the tree, so its action names no node.
        assert_eq!(click(0, action, &mut output), STATUS_OK);
        assert!(
            ACTION_RAN_IN_CONTEXT.load(Ordering::SeqCst),
            "the action ran outside the runtime's context"
        );
        compose_rust_host_shutdown();
    })
    .join()
    .unwrap();
}

/// A Renderer that lost its node table gets the whole tree from a runtime made fresh by
/// the factory the application registered.
#[test]
fn fr39_a_resync_builds_a_fresh_runtime_from_the_registered_factory() {
    let _launch = launch_guard();
    launch_counter();
    std::thread::spawn(|| {
        let handshake = handshake();
        let mut output = MutationBatch::default();
        // SAFETY: both buffers are live test-owned storage for the call.
        let status = unsafe {
            compose_rust_host_init(handshake.as_ptr(), handshake.len() as u32, &mut output)
        };
        assert_eq!(status, STATUS_OK);
        let before = FACTORY_CALLS.load(Ordering::SeqCst);

        let mut bytes = Vec::new();
        encode_event(
            &HostEvent {
                node_id: 0,
                handler_id: 0,
                payload: EventPayload::Resync,
            },
            &mut bytes,
        )
        .unwrap();
        // SAFETY: the event bytes and the output storage are live for the whole call.
        let status = unsafe {
            compose_rust_host_dispatch_event(bytes.as_ptr(), bytes.len() as u32, &mut output)
        };
        assert_eq!(status, STATUS_OK);
        assert_eq!(FACTORY_CALLS.load(Ordering::SeqCst), before + 1);
        let tree = records(&output);
        assert!(
            tree.iter().any(|record| record.starts_with("Create")),
            "a resync batch creates the tree: {tree:?}"
        );
        compose_rust_host_shutdown();
    })
    .join()
    .unwrap();
}
