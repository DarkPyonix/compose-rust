//! A panic in application code must come back across the C boundary as `STATUS_PANIC`.
//!
//! Unwinding out of an `extern "C"` function ends the process, so every export catches it.
//! The other boundary tests only ever reach that catch through the Host's own code; these
//! put the panic where an application would, in a composable while it is composed and in
//! an event handler, and check the export returns a status instead of taking the Renderer
//! down with it.
//!
//! What is launched is process-global, so the tests take turns, and each runs on a thread
//! of its own, as the Renderer's UI thread is not the one that launched: a composition the
//! panic left half done stays on that thread's state and goes when it ends.

mod support;

use compose_rust::LaunchBuilder;
use compose_rust::boundary::{
    MutationBatch, STATUS_OK, STATUS_PANIC, compose_rust_host_dispatch_event,
    compose_rust_host_init, compose_rust_host_shutdown,
};
use compose_rust::protocol::{HostEvent, encode_event};
use compose_rust::runtime::*;
use compose_rust::schema::{EventPayload, LoopMode, PROTOCOL_VERSION, PropertyKind, SCHEMA_HASH};
use compose_rust::ui::*;
use std::sync::{Mutex, MutexGuard};
use support::Tree;

static LAUNCH: Mutex<()> = Mutex::new(());

fn launch_guard() -> MutexGuard<'static, ()> {
    LAUNCH
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[Composable]
fn PanicsWhileComposing() {
    Column!({
        Text!("before");
        if std::hint::black_box(true) {
            panic!("a deliberate panic while composing");
        }
        Text!("after");
    });
}

#[Composable]
fn PanicsOnClick() {
    Column!({
        Button!("Go", move || {
            if std::hint::black_box(true) {
                panic!("a deliberate panic in a click handler");
            }
        });
    });
}

fn handshake() -> Vec<u8> {
    let mut bytes = Vec::with_capacity(12);
    bytes.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
    bytes.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
    bytes.extend_from_slice(&[LoopMode::Platform as u8, 0]);
    bytes
}

fn init(output: &mut MutationBatch) -> i32 {
    let handshake = handshake();
    // SAFETY: the handshake and the output are live for the call.
    unsafe { compose_rust_host_init(handshake.as_ptr(), handshake.len() as u32, output) }
}

fn batch_of(output: &MutationBatch) -> Vec<u8> {
    // SAFETY: a successful call returned a readable batch owned by the Host.
    unsafe { std::slice::from_raw_parts(output.ptr, output.len as usize) }.to_vec()
}

#[test]
fn nfr7_a_panicking_composable_is_a_status_from_init() {
    let _launch = launch_guard();
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .application(PanicsWhileComposing);
    std::thread::spawn(|| {
        let mut output = MutationBatch::default();
        assert_eq!(
            init(&mut output),
            STATUS_PANIC,
            "a composable's panic must not unwind across the C boundary"
        );
        compose_rust_host_shutdown();
    })
    .join()
    .expect("the panic escaped init");
}

#[test]
fn nfr7_a_panicking_handler_is_a_status_from_dispatch_event() {
    let _launch = launch_guard();
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .application(PanicsOnClick);
    std::thread::spawn(|| {
        let mut output = MutationBatch::default();
        assert_eq!(init(&mut output), STATUS_OK);
        let mut tree = Tree::default();
        tree.apply(&batch_of(&output));
        let (node_id, handler_id) = tree.find("Button", Some("Go"), 0, PropertyKind::OnClick);
        let mut event = Vec::new();
        encode_event(
            &HostEvent::new(node_id, handler_id, EventPayload::Clicked),
            &mut event,
        )
        .unwrap();
        // SAFETY: the event and the output are live for the call.
        let status = unsafe {
            compose_rust_host_dispatch_event(event.as_ptr(), event.len() as u32, &mut output)
        };
        assert_eq!(
            status, STATUS_PANIC,
            "a handler's panic must not unwind across the C boundary"
        );
        compose_rust_host_shutdown();
    })
    .join()
    .expect("the panic escaped dispatch_event");
}
