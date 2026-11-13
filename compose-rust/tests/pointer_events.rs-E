//! Node pointer events: the 72-byte record, the subscription a node writes, and the
//! consume result a handler hands back.

mod support;

use compose_rust::boundary::{
    MutationBatch, STATUS_OK, compose_rust_host_dispatch_event, compose_rust_host_init,
    compose_rust_host_release_batch, compose_rust_host_shutdown,
};
use compose_rust::protocol::{HostEvent, ProtocolError, decode_event, encode_event};
use compose_rust::runtime::*;
use compose_rust::schema::{
    EventPayload, LoopMode, PROTOCOL_VERSION, PointerKind, PointerType, PropertyKind, SCHEMA_HASH,
    WheelDeltaMode,
};
use compose_rust::ui::*;
use compose_rust::{Cursor, UserSelect};
use std::cell::RefCell;
use std::sync::{Mutex, MutexGuard};
use support::Tree;

static LAUNCH: Mutex<()> = Mutex::new(());

fn launch_guard() -> MutexGuard<'static, ()> {
    LAUNCH
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

thread_local! {
    static SEEN: RefCell<Vec<(PointerKind, f32, f32, u32, u32, u32)>> = const { RefCell::new(Vec::new()) };
}

#[composable]
fn Screen() {
    Column().content(|| {
        // Subscribed to presses and moves. A press is consumed, a move is not.
        Box()
            .modifier(Modifier.cursor(Cursor::Grab).user_select(UserSelect::None))
            .on_pointer(PointerKinds::DOWN | PointerKinds::MOVE, |event| {
                SEEN.with(|seen| {
                    seen.borrow_mut().push((
                        event.kind(),
                        event.x(),
                        event.y(),
                        event.buttons(),
                        event.button(),
                        event.click_count(),
                    ));
                });
                if event.kind() == PointerKind::Down {
                    event.consume();
                }
            })
            .content(|| Text("subscribed"));
        // Never subscribed.
        Box().content(|| Text("plain"));
        // A subscription of no kinds is not one.
        Box()
            .on_pointer(PointerKinds::NONE, |_| {})
            .content(|| Text("empty mask"));
    });
}

fn pointer(kind: PointerKind, node_id: u32, handler_id: u64) -> HostEvent<'static> {
    HostEvent {
        node_id,
        handler_id,
        payload: EventPayload::Pointer {
            kind,
            pointer_id: 1,
            pointer_type: PointerType::Mouse,
            x: 12.5,
            y: 3.0,
            buttons: 1,
            button: if kind == PointerKind::Down { 1 } else { 0 },
            modifiers: 0,
            click_count: if kind == PointerKind::Down { 2 } else { 0 },
            delta_x: 0.0,
            delta_y: 0.0,
            delta_mode: WheelDeltaMode::Pixel,
            timestamp_nanos: 42,
        },
    }
}

fn batch_of(output: &MutationBatch) -> Vec<u8> {
    // SAFETY: a successful call returned a readable batch owned by the Host.
    unsafe { std::slice::from_raw_parts(output.ptr, output.len as usize) }.to_vec()
}

fn handshake() -> Vec<u8> {
    let mut bytes = Vec::with_capacity(12);
    bytes.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
    bytes.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
    bytes.extend_from_slice(&[LoopMode::Platform as u8, 0]);
    bytes
}

/// Runs `check` against the screen's tree on a thread of its own, as the Renderer's UI
/// thread is not the one that launched.
fn with_screen(check: impl FnOnce(&mut Tree) + Send + 'static) {
    let _launch = launch_guard();
    compose_rust::LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .application(Screen);
    std::thread::spawn(move || {
        let handshake = handshake();
        let mut output = MutationBatch::default();
        // SAFETY: both buffers are live for the call.
        let status = unsafe {
            compose_rust_host_init(handshake.as_ptr(), handshake.len() as u32, &mut output)
        };
        assert_eq!(status, STATUS_OK);
        let mut tree = Tree::default();
        tree.apply(&batch_of(&output));
        // SAFETY: `output` is this test's own storage.
        unsafe { compose_rust_host_release_batch(&mut output) };
        check(&mut tree);
        compose_rust_host_shutdown();
    })
    .join()
    .unwrap();
}

fn dispatch(event: &HostEvent<'_>) -> i64 {
    let mut bytes = Vec::new();
    encode_event(event, &mut bytes).unwrap();
    let mut output = MutationBatch::default();
    // SAFETY: the event and the output are live for the call.
    let status = unsafe {
        compose_rust_host_dispatch_event(bytes.as_ptr(), bytes.len() as u32, &mut output)
    };
    assert_eq!(status, STATUS_OK);
    let result = output.result;
    // SAFETY: `output` is this test's own storage.
    unsafe { compose_rust_host_release_batch(&mut output) };
    result
}

#[test]
fn fr46_pointer_record_is_72_bytes_and_round_trips() {
    let event = pointer(PointerKind::Down, 7, 9);
    let mut bytes = Vec::new();
    encode_event(&event, &mut bytes).unwrap();
    assert_eq!(bytes.len(), 72);
    assert_eq!(
        u16::from_le_bytes([bytes[0], bytes[1]]),
        8,
        "the Pointer tag"
    );
    assert_eq!(u16::from_le_bytes([bytes[2], bytes[3]]), 72);
    assert_eq!(
        u32::from_le_bytes(bytes[16..20].try_into().unwrap()),
        4,
        "Down"
    );
    assert_eq!(
        f32::from_bits(u32::from_le_bytes(bytes[28..32].try_into().unwrap())),
        12.5
    );
    assert_eq!(
        u32::from_le_bytes(bytes[48..52].try_into().unwrap()),
        2,
        "click_count"
    );
    assert_eq!(u64::from_le_bytes(bytes[64..72].try_into().unwrap()), 42);
    assert_eq!(decode_event(&bytes).unwrap(), event);
}

#[test]
fn nfr7_unknown_pointer_kind_is_a_protocol_error() {
    let mut bytes = Vec::new();
    encode_event(&pointer(PointerKind::Move, 7, 9), &mut bytes).unwrap();
    bytes[16..20].copy_from_slice(&99_u32.to_le_bytes());
    assert_eq!(
        decode_event(&bytes),
        Err(ProtocolError::InvalidValueKind(99))
    );
    bytes[16..20].copy_from_slice(&0_u32.to_le_bytes());
    assert!(decode_event(&bytes).is_err(), "kind 0 names nothing");
}

#[test]
fn fr46_pointer_record_of_the_wrong_length_is_rejected() {
    let mut bytes = Vec::new();
    encode_event(&pointer(PointerKind::Move, 7, 9), &mut bytes).unwrap();
    bytes.truncate(68);
    bytes[2..4].copy_from_slice(&68_u16.to_le_bytes());
    assert_eq!(
        decode_event(&bytes),
        Err(ProtocolError::InvalidRecordLength)
    );
}

#[test]
fn fr46_kind_bits_are_one_shifted_by_the_tag_minus_one() {
    assert_eq!(PointerKinds::MOVE.bits(), 1);
    assert_eq!(PointerKinds::DOWN.bits(), 1 << 3);
    assert_eq!(PointerKinds::CONTEXT_MENU.bits(), 1 << 7);
    assert_eq!(PointerKinds::ALL.bits(), 0xff);
    assert!((PointerKinds::DOWN | PointerKinds::UP).contains(PointerKind::Up));
    assert!(!(PointerKinds::DOWN | PointerKinds::UP).contains(PointerKind::Move));
}

#[test]
fn fr46_button_bits_match_compose_pointer_buttons() {
    assert_eq!(
        [
            pointer_button::PRIMARY,
            pointer_button::SECONDARY,
            pointer_button::TERTIARY,
            pointer_button::BACK,
            pointer_button::FORWARD,
        ],
        [1, 2, 4, 8, 16]
    );
}

#[test]
fn fr46_unsubscribed_node_gets_no_pointer_modifier() {
    with_screen(|tree| {
        let dump = tree.dump();
        // Exactly one node of the three subscribes: the other two write neither property.
        assert_eq!(dump.matches("PointerKinds=").count(), 1, "{dump}");
        assert!(
            dump.contains("PointerKinds=Integer(9)"),
            "Down | Move is 8 | 1: {dump}"
        );
        let subscribed = dump
            .lines()
            .find(|line| line.contains("PointerKinds="))
            .unwrap();
        assert!(subscribed.starts_with("  Box"), "{dump}");
    });
}

#[test]
fn fr47_cursor_and_user_select_travel_only_where_set() {
    with_screen(|tree| {
        let dump = tree.dump();
        assert_eq!(dump.matches("Cursor=").count(), 1, "{dump}");
        assert_eq!(dump.matches("UserSelect=").count(), 1, "{dump}");
        assert!(dump.contains("Cursor=Integer(7)"), "Grab is 7: {dump}");
        assert!(dump.contains("UserSelect=Integer(1)"), "None is 1: {dump}");
    });
}

#[test]
fn fr46_handler_sees_the_record_and_its_consume_is_the_result() {
    with_screen(|tree| {
        SEEN.with(|seen| seen.borrow_mut().clear());
        let (node, handler) = tree.find("Box", None, 0, PropertyKind::OnPointer);
        let moved = dispatch(&pointer(PointerKind::Move, node, handler));
        let pressed = dispatch(&pointer(PointerKind::Down, node, handler));
        assert_eq!(moved, 0, "a move nobody consumed bubbles on");
        assert_eq!(pressed, 1, "a consumed press does not");
        SEEN.with(|seen| {
            assert_eq!(
                *seen.borrow(),
                vec![
                    (PointerKind::Move, 12.5, 3.0, 1, 0, 0),
                    (PointerKind::Down, 12.5, 3.0, 1, 1, 2),
                ]
            );
        });
    });
}
