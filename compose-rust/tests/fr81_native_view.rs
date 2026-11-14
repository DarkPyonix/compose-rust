//! `NativeView`, the place a platform view the application made is put in, as it reaches
//! the wire: the widget, its two properties, the two commands, the two events, and what
//! the Host does with each event.
//!
//! What the Renderer does with a view (where it puts it, how it clips it, how focus moves)
//! is tested on the Renderer's side, against real platform views.

mod support;

use compose_rust::ComposeHost;
use compose_rust::native_view::{NativeParent, native_parent, subscribe_native_parent};
use compose_rust::protocol::{
    HostEvent, Mutation, PropertyValue, ProtocolError, decode_batch, decode_event, encode_event,
};
use compose_rust::runtime::*;
use compose_rust::schema::{EventPayload, NativeHandle, NativeParentKind, PropertyKind, WidgetKind};
use compose_rust::ui::*;
use compose_rust::{Batch, codegen};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use support::Tree;

const MUTATIONS: &[u8] = include_bytes!("vectors/mutations.bin");
const EVENTS: &[u8] = include_bytes!("vectors/events.bin");

/// Where the two events sit in the event vector.
const PARENT_AT: usize = 1755;
const PARENT_LEN: usize = 32;
const FOCUS_AT: usize = 1787;
const FOCUS_LEN: usize = 24;

fn vector_parent() -> HostEvent<'static> {
    HostEvent {
        node_id: 20,
        handler_id: 0,
        payload: EventPayload::NativeViewParent {
            handle: NativeHandle(codegen::NATIVE_PARENT_VECTOR_HANDLE),
            kind: NativeParentKind::AppKit,
            scale: 2.0,
        },
    }
}

fn vector_focus() -> HostEvent<'static> {
    HostEvent {
        node_id: 24,
        handler_id: 301,
        payload: EventPayload::NativeViewFocus { focused: true },
    }
}

fn native_records(batch: &[u8]) -> Vec<Mutation<'_>> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter(|mutation| {
            matches!(
                mutation,
                Mutation::AttachNativeView { .. } | Mutation::FocusNativeView { .. }
            )
        })
        .collect()
}

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree, Vec<u8>) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    tree.apply(&batch);
    (host, tree, batch)
}

fn click(host: &mut ComposeHost, tree: &mut Tree, label: &str) -> Vec<u8> {
    let (node_id, handler_id) = tree.find("Button", Some(label), 0, PropertyKind::OnClick);
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .expect("the click is accepted");
    let batch = batch.to_vec();
    tree.apply(&batch);
    batch
}

/// The tags are the wire. A number that moved would be read by a Renderer built from the
/// older schema as something else.
#[test]
fn fr81_native_view_tags_are_appended_and_fixed() {
    assert_eq!(WidgetKind::NativeView as u16, 48);
    assert_eq!(PropertyKind::NativeVisible as u16, 190);
    assert_eq!(PropertyKind::OnNativeViewFocus as u16, 191);
    assert_eq!(
        PropertyKind::from_name("native_visible"),
        Ok(PropertyKind::NativeVisible)
    );
    assert_eq!(NativeParentKind::AppKit as u16, 1);
    assert_eq!(NativeParentKind::Win32 as u16, 2);
    assert_eq!(NativeParentKind::X11 as u16, 3);
    assert_eq!(NativeParentKind::Android as u16, 4);
    assert_eq!(NativeParentKind::UIKit as u16, 5);
    assert_eq!(NativeParentKind::try_from(0), Err(()));
    assert_eq!(NativeParentKind::try_from(6), Err(()));
    let events: Vec<(&str, u16)> = compose_rust::schema::EVENT_SCHEMA
        .iter()
        .filter(|event| event.name.starts_with("NativeView"))
        .map(|event| (event.name, event.tag))
        .collect();
    assert_eq!(events, [("NativeViewParent", 61), ("NativeViewFocus", 62)]);
}

#[test]
fn fr81_native_view_records_round_trip_through_the_protocol_vectors() {
    let handle = NativeHandle(codegen::NATIVE_VIEW_VECTOR_HANDLE);
    let expected = vec![
        Mutation::AttachNativeView {
            node_id: 24,
            handle,
        },
        Mutation::FocusNativeView {
            node_id: 24,
            focus: true,
        },
    ];
    assert_eq!(native_records(MUTATIONS), expected);
    // The node they name is a NativeView, hidden, with a focus handler.
    let all = decode_batch(MUTATIONS).unwrap();
    assert!(all.contains(&Mutation::Create {
        node_id: 24,
        widget: WidgetKind::NativeView
    }));
    assert!(all.contains(&Mutation::SetProp {
        node_id: 24,
        property: PropertyKind::NativeVisible,
        value: PropertyValue::Bool(false),
    }));
    assert!(all.contains(&Mutation::SetProp {
        node_id: 24,
        property: PropertyKind::OnNativeViewFocus,
        value: PropertyValue::Integer(301),
    }));

    // The Batch methods write the same two records, byte for byte.
    let mut batch = Batch::new();
    batch.begin();
    batch.attach_native_view(24, handle);
    batch.focus_native_view(24, true);
    let written = batch.finish().expect("the batch encodes").to_vec();
    assert_eq!(native_records(&written), expected);

    // The two events sit at their place in the event vector, byte for byte.
    assert_eq!(EVENTS.len(), FOCUS_AT + FOCUS_LEN);
    for (at, len, event) in [
        (PARENT_AT, PARENT_LEN, vector_parent()),
        (FOCUS_AT, FOCUS_LEN, vector_focus()),
    ] {
        let bytes = &EVENTS[at..at + len];
        assert_eq!(decode_event(bytes).expect("the event decodes"), event);
        let mut encoded = Vec::new();
        encode_event(&event, &mut encoded).expect("the event encodes");
        assert_eq!(encoded, bytes);
    }
}

#[test]
fn fr81_native_view_record_layout_is_fixed() {
    let mut batch = Batch::new();
    batch.begin();
    batch.attach_native_view(5, NativeHandle(0x0102_0304_0506_0708));
    batch.focus_native_view(5, false);
    let written = batch.finish().expect("the batch encodes").to_vec();
    // After the 12 byte envelope: tag 37, length 24, the node, two reserved words and the
    // handle at record offset 16; then tag 38, length 16, the node, the focus word and a
    // reserved word.
    let records = &written[12..12 + 40];
    let mut expected = Vec::new();
    expected.extend_from_slice(&37_u16.to_le_bytes());
    expected.extend_from_slice(&24_u16.to_le_bytes());
    expected.extend_from_slice(&5_u32.to_le_bytes());
    expected.extend_from_slice(&0_u32.to_le_bytes());
    expected.extend_from_slice(&0_u32.to_le_bytes());
    expected.extend_from_slice(&0x0102_0304_0506_0708_u64.to_le_bytes());
    expected.extend_from_slice(&38_u16.to_le_bytes());
    expected.extend_from_slice(&16_u16.to_le_bytes());
    expected.extend_from_slice(&5_u32.to_le_bytes());
    expected.extend_from_slice(&0_u32.to_le_bytes());
    expected.extend_from_slice(&0_u32.to_le_bytes());
    assert_eq!(records, &expected[..]);
}

/// A tree with no `NativeView` writes nothing new: no node of the widget, no property of
/// it, no command, and nothing in the event vector before the two new events moved.
#[test]
fn fr81_tree_without_native_view_is_unchanged() {
    let (mut host, mut tree, first) = start(|| {
        let count = remember(|| mutable_state_of(0));
        Column().content(|| {
            Text(format!("{}", count.get()));
            Button("Bump").on_click(move || count.update(|count| *count += 1));
        });
    });
    let second = click(&mut host, &mut tree, "Bump");
    for batch in [&first, &second] {
        for mutation in decode_batch(batch).unwrap() {
            match mutation {
                Mutation::Create { widget, .. } => assert_ne!(widget, WidgetKind::NativeView),
                Mutation::SetProp { property, .. } => assert!(
                    property != PropertyKind::NativeVisible
                        && property != PropertyKind::OnNativeViewFocus,
                    "{property:?} was written for a tree without a platform view"
                ),
                Mutation::AttachNativeView { .. } | Mutation::FocusNativeView { .. } => {
                    panic!("a tree without a platform view wrote {mutation:?}")
                }
                _ => {}
            }
        }
    }
    // The events before the two new ones are where they were: they were appended after
    // the last of them, which ends at the parent report.
    assert_eq!(
        decode_event(&EVENTS[1415..1439]).unwrap().payload,
        EventPayload::CodeScrolled { offset_px: 1234.5 }
    );
    assert!(matches!(
        decode_event(&EVENTS[PARENT_AT - 76..PARENT_AT]).unwrap().payload,
        EventPayload::SelectionsChanged { .. }
    ));
}

#[test]
fn fr81_native_view_writes_its_node_and_its_handle_once() {
    let (mut host, mut tree, first) = start(|| {
        let handle = remember(|| mutable_state_of(0x1000_u64));
        let bump = remember(|| mutable_state_of(0));
        Column().content(|| {
            Text(format!("{}", bump.get()));
            Button("Bump").on_click(move || bump.update(|count| *count += 1));
            Button("Swap").on_click(move || handle.set(0x2000));
            Button("Detach").on_click(move || handle.set(0));
            NativeView(NativeHandle(handle.get()))
                .modifier(Modifier::new().size(200.0, 100.0));
        });
    });
    assert!(tree.widgets().iter().any(|widget| widget == "NativeView"));
    let node = decode_batch(&first)
        .unwrap()
        .into_iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::NativeView,
            } => Some(node_id),
            _ => None,
        })
        .expect("the NativeView node is created");
    assert_eq!(
        native_records(&first),
        vec![Mutation::AttachNativeView {
            node_id: node,
            handle: NativeHandle(0x1000)
        }]
    );
    // Visible is the default and is not written; there is no handler, so none is written.
    let dump = tree.dump();
    assert!(!dump.contains("NativeVisible="), "{dump}");
    assert!(tree.handled("NativeView", PropertyKind::OnNativeViewFocus).is_empty());

    // A render that does not change the handle writes no command.
    let unrelated = click(&mut host, &mut tree, "Bump");
    assert!(native_records(&unrelated).is_empty());

    // A new handle replaces the old one with one command.
    let swapped = click(&mut host, &mut tree, "Swap");
    assert_eq!(
        native_records(&swapped),
        vec![Mutation::AttachNativeView {
            node_id: node,
            handle: NativeHandle(0x2000)
        }]
    );

    // No handle takes the view out.
    let detached = click(&mut host, &mut tree, "Detach");
    assert_eq!(
        native_records(&detached),
        vec![Mutation::AttachNativeView {
            node_id: node,
            handle: NativeHandle::NONE
        }]
    );
}

#[test]
fn fr81_native_view_with_no_handle_writes_no_command() {
    let (_host, _tree, first) = start(|| {
        NativeView(NativeHandle::NONE).modifier(Modifier::new().size(10.0, 10.0));
    });
    assert!(native_records(&first).is_empty());
    assert!(decode_batch(&first).unwrap().iter().any(|mutation| matches!(
        mutation,
        Mutation::Create {
            widget: WidgetKind::NativeView,
            ..
        }
    )));
}

#[test]
fn fr81_hidden_native_view_writes_native_visible_false() {
    let (mut host, mut tree, _) = start(|| {
        let shown = remember(|| mutable_state_of(false));
        Column().content(|| {
            Button("Show").on_click(move || shown.set(true));
            NativeView(NativeHandle(0x1000)).visible(shown.get());
        });
    });
    assert!(tree.dump().contains("NativeVisible=Bool(false)"), "{}", tree.dump());
    let shown = click(&mut host, &mut tree, "Show");
    // Showing takes the property away, which is the default; the view is not attached
    // again.
    assert!(decode_batch(&shown).unwrap().iter().any(|mutation| matches!(
        mutation,
        Mutation::SetProp {
            property: PropertyKind::NativeVisible,
            value: PropertyValue::None,
            ..
        }
    )));
    assert!(native_records(&shown).is_empty());
    assert!(!tree.dump().contains("NativeVisible="), "{}", tree.dump());
}

#[test]
fn fr81_focus_report_reaches_the_handler() {
    static FOCUSED: AtomicBool = AtomicBool::new(false);
    static HEARD: AtomicUsize = AtomicUsize::new(0);
    let (mut host, tree, _) = start(|| {
        NativeView(NativeHandle(0x1000)).on_focus_change(|focused| {
            FOCUSED.store(focused, Ordering::SeqCst);
            HEARD.fetch_add(1, Ordering::SeqCst);
        });
    });
    let (node_id, handler_id) = tree.handled("NativeView", PropertyKind::OnNativeViewFocus)[0];
    for focused in [true, false] {
        host.dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::NativeViewFocus { focused },
        })
        .expect("the focus report is accepted");
        assert_eq!(FOCUSED.load(Ordering::SeqCst), focused);
    }
    assert_eq!(HEARD.load(Ordering::SeqCst), 2);
}

#[test]
fn fr81_parent_report_is_kept_per_window_and_wakes_subscribers() {
    let (mut host, _tree, _) = start(|| {
        NativeView(NativeHandle::NONE);
    });
    assert_eq!(native_parent(0), None);
    let woken = Arc::new(AtomicUsize::new(0));
    let counter = Arc::clone(&woken);
    let _subscription = subscribe_native_parent(Arc::new(move || {
        counter.fetch_add(1, Ordering::SeqCst);
    }));
    let report = |handle: u64, scale: f32| HostEvent {
        node_id: 0,
        handler_id: 0,
        payload: EventPayload::NativeViewParent {
            handle: NativeHandle(handle),
            kind: NativeParentKind::AppKit,
            scale,
        },
    };
    host.dispatch(report(0xabc0, 2.0)).expect("the report is accepted");
    assert_eq!(
        native_parent(0),
        Some(NativeParent {
            window: 0,
            handle: NativeHandle(0xabc0),
            kind: NativeParentKind::AppKit,
            scale: 2.0,
        })
    );
    assert_eq!(woken.load(Ordering::SeqCst), 1);
    // The same report again wakes nobody.
    host.dispatch(report(0xabc0, 2.0)).expect("the report is accepted");
    assert_eq!(woken.load(Ordering::SeqCst), 1);
    // A new scale is a new report.
    host.dispatch(report(0xabc0, 1.0)).expect("the report is accepted");
    assert_eq!(native_parent(0).map(|parent| parent.scale), Some(1.0));
    assert_eq!(woken.load(Ordering::SeqCst), 2);
    // A closed window reports no handle.
    host.dispatch(report(0, 1.0)).expect("the report is accepted");
    assert_eq!(native_parent(0).map(|parent| parent.handle), Some(NativeHandle::NONE));

    // A report with a handler, or about a node that is not a window, is refused.
    let mut with_handler = report(0xabc0, 2.0);
    with_handler.handler_id = 4;
    assert!(host.dispatch(with_handler).is_err());
    let mut not_a_window = report(0xabc0, 2.0);
    not_a_window.node_id = 9999;
    assert!(host.dispatch(not_a_window).is_err());
}

/// What the Host cannot read is a protocol error, never a guess and never a crash.
#[test]
fn fr81_native_view_records_refuse_what_they_cannot_read() {
    let mut parent = Vec::new();
    encode_event(&vector_parent(), &mut parent).unwrap();
    let mut focus = Vec::new();
    encode_event(&vector_focus(), &mut focus).unwrap();

    // A kind no window has.
    let mut unknown_kind = parent.clone();
    unknown_kind[24..26].copy_from_slice(&9_u16.to_le_bytes());
    assert_eq!(
        decode_event(&unknown_kind),
        Err(ProtocolError::InvalidValueKind(9))
    );
    // A reserved half word that is set.
    let mut reserved = parent.clone();
    reserved[26..28].copy_from_slice(&1_u16.to_le_bytes());
    assert!(decode_event(&reserved).is_err());
    // A scale that sizes nothing.
    for scale in [0.0_f32, -1.0, f32::NAN, f32::INFINITY] {
        let mut bad = parent.clone();
        bad[28..32].copy_from_slice(&scale.to_bits().to_le_bytes());
        assert!(decode_event(&bad).is_err(), "scale {scale} was accepted");
    }
    // A focus word that is neither 0 nor 1.
    let mut two = focus.clone();
    two[16..20].copy_from_slice(&2_u32.to_le_bytes());
    assert!(decode_event(&two).is_err());
    // Lengths other than the record's.
    for (bytes, wrong) in [(&parent, 28_u16), (&parent, 36), (&focus, 20), (&focus, 28)] {
        let mut resized = bytes[..usize::from(wrong).min(bytes.len())].to_vec();
        resized.resize(usize::from(wrong), 0);
        resized[2..4].copy_from_slice(&wrong.to_le_bytes());
        assert_eq!(
            decode_event(&resized),
            Err(ProtocolError::InvalidRecordLength),
            "a record of {wrong} bytes"
        );
    }

    // The commands: a reserved word that is set, a focus word out of range, and a length
    // other than the record's.
    let mut batch = Batch::new();
    batch.begin();
    batch.attach_native_view(5, NativeHandle(0x10));
    batch.focus_native_view(5, true);
    let written = batch.finish().unwrap().to_vec();
    let attach_at = 12;
    let focus_at = 12 + 24;
    for (offset, what) in [
        (attach_at + 8, "the first reserved word after the node"),
        (attach_at + 12, "the second reserved word, before the handle"),
        (focus_at + 12, "the focus record's reserved word"),
    ] {
        let mut bad = written.clone();
        bad[offset..offset + 4].copy_from_slice(&1_u32.to_le_bytes());
        assert!(decode_batch(&bad).is_err(), "{what} was accepted");
    }
    let mut bad_focus = written.clone();
    bad_focus[focus_at + 8..focus_at + 12].copy_from_slice(&2_u32.to_le_bytes());
    assert!(decode_batch(&bad_focus).is_err());
    let mut short = written.clone();
    short[attach_at + 2..attach_at + 4].copy_from_slice(&20_u16.to_le_bytes());
    assert!(decode_batch(&short).is_err());
}
