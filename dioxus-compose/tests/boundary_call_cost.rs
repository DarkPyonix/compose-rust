//! What one text change costs at the boundary.
//!
//! The Renderer hands the event over and the Host answers with the diff on the same call
//! stack: the batch is an out parameter of the dispatch call, not something a second call
//! goes back for, and the handler's answer rides in the same struct. Releasing it is the
//! other call, and it is not optional, because the arena underneath is the one the next
//! event will write into.
//!
//! So the whole of a keystroke is two calls, and these tests are what would notice if it
//! stopped being two: a diff that needed collecting, a result that needed asking for, or a
//! frame that had to be rendered before the change was visible would each show up here.

use dioxus_compose::boundary::{
    MutationBatch, STATUS_OK, dioxus_compose_host_dispatch_event, dioxus_compose_host_init,
    dioxus_compose_host_release_batch, dioxus_compose_host_render_frame,
};
use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
use dioxus_compose::schema::{
    EventPayload, PROTOCOL_VERSION, PropertyKind, SCHEMA_HASH, WidgetKind,
};

fn app() -> Element {
    let mut typed = use_signal(String::new);
    rsx! {
        Column {
            Text { text: "{typed}" }
            TextField {
                placeholder: "type here",
                on_value_change: move |value: String| typed.set(value),
            }
        }
    }
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

/// The field's node id and the handler id it reports value changes to, read off the first
/// batch the way a Renderer would.
fn field_and_handler(batch: &MutationBatch) -> (u32, u64) {
    let mutations = decode_batch(batch_bytes(batch)).expect("the first batch did not decode");
    let field = mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::TextField,
            } => Some(*node_id),
            _ => None,
        })
        .expect("the first batch created no TextField");
    let handler = mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnValueChange,
                value: PropertyValue::Integer(handler),
            } if *node_id == field => Some(*handler as u64),
            _ => None,
        })
        .expect("the field was given no value change handler");
    (field, handler)
}

fn typed_event(field: u32, handler: u64, text: &str) -> Vec<u8> {
    let mut bytes = Vec::new();
    encode_event(
        &HostEvent {
            node_id: field,
            handler_id: handler,
            payload: EventPayload::TextChanged(text),
        },
        &mut bytes,
    )
    .expect("the keystroke did not encode");
    bytes
}

/// Brings the Host up and releases the batch the handshake produced.
fn start() -> (u32, u64) {
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .launch(app);
    let bytes = handshake();
    let mut first = MutationBatch::default();
    // SAFETY: The handshake buffer and `first` are live test-owned storage.
    let status =
        unsafe { dioxus_compose_host_init(bytes.as_ptr(), bytes.len() as u32, &mut first) };
    assert_eq!(status, STATUS_OK, "the handshake was refused");
    let found = field_and_handler(&first);
    // SAFETY: `first` is this test's own storage.
    unsafe { dioxus_compose_host_release_batch(&mut first) };
    found
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
    let (field, handler) = start();
    let event = typed_event(field, handler, "typed into the field");
    let mut batch = MutationBatch::default();

    // One. The diff is already here when it returns, and so is the handler's answer.
    // SAFETY: The event buffer and `batch` are live test-owned storage.
    let status = unsafe {
        dioxus_compose_host_dispatch_event(event.as_ptr(), event.len() as u32, &mut batch)
    };
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
    unsafe { dioxus_compose_host_release_batch(&mut batch) };
    assert!(batch.ptr.is_null(), "release left the batch readable");
}

#[test]
fn pr4_nothing_is_left_for_a_third_call() {
    let (field, handler) = start();
    let event = typed_event(field, handler, "already applied");
    let mut batch = MutationBatch::default();
    // SAFETY: The event buffer and `batch` are live test-owned storage.
    let status = unsafe {
        dioxus_compose_host_dispatch_event(event.as_ptr(), event.len() as u32, &mut batch)
    };
    assert_eq!(status, STATUS_OK);
    assert!(texts(&batch).contains(&"already applied".to_string()));
    // SAFETY: `batch` is this test's own storage.
    unsafe { dioxus_compose_host_release_batch(&mut batch) };

    // A Renderer that had to render a frame to see the keystroke would find work here.
    let mut frame = MutationBatch::default();
    // SAFETY: `frame` is live test-owned storage.
    let status = unsafe { dioxus_compose_host_render_frame(0, &mut frame) };
    assert_eq!(status, STATUS_OK);
    let left_over = decode_batch(batch_bytes(&frame)).expect("the frame did not decode");
    assert!(
        left_over.is_empty(),
        "the frame after the keystroke still carried {left_over:?}, so the change was not \
         finished when dispatch returned"
    );
    // SAFETY: `frame` is this test's own storage.
    unsafe { dioxus_compose_host_release_batch(&mut frame) };
}

#[test]
fn pr4_the_batch_buffer_is_an_argument_and_not_a_queue() {
    let (field, handler) = start();
    let mut arenas = Vec::new();
    for text in ["first", "second", "third"] {
        let event = typed_event(field, handler, text);
        let mut batch = MutationBatch::default();
        // SAFETY: The event buffer and `batch` are live test-owned storage.
        let status = unsafe {
            dioxus_compose_host_dispatch_event(event.as_ptr(), event.len() as u32, &mut batch)
        };
        assert_eq!(status, STATUS_OK);
        assert!(texts(&batch).contains(&text.to_string()));
        arenas.push(batch.ptr);
        // SAFETY: `batch` is this test's own storage.
        unsafe { dioxus_compose_host_release_batch(&mut batch) };
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
}
