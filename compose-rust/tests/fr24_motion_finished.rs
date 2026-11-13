//! The report that a node's size motion has settled: the handler property and the event
//! on the wire, and how the event reaches the handler the node was given.

mod support;

use compose_rust::ComposeHost;
use compose_rust::MotionFinished;
use compose_rust::protocol::{
    HostEvent, Mutation, ProtocolError, decode_batch, decode_event, encode_event,
};
use compose_rust::schema::{EVENT_SCHEMA, EventPayload, PropertyKind};
use compose_rust::ui::*;
use std::sync::{Arc, Mutex};
use support::Tree;

const EVENTS: &[u8] = include_bytes!("vectors/events.bin");

/// Where the report sits in the event vector, and how long it is.
const REPORT_AT: usize = 1439;
const REPORT_LEN: usize = 24;

fn vector_report() -> HostEvent<'static> {
    HostEvent {
        node_id: 14,
        handler_id: 30,
        payload: EventPayload::MotionFinished {
            width_dp: 240.5,
            height_dp: 0.0,
        },
    }
}

fn event_tag(name: &str) -> u16 {
    EVENT_SCHEMA
        .iter()
        .find(|event| event.name == name)
        .unwrap_or_else(|| panic!("{name} is in the event schema"))
        .tag
}

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

#[test]
fn fr24_motion_finished_round_trips() {
    assert_eq!(PropertyKind::OnMotionFinished as u16, 193);
    assert_eq!(event_tag("MotionFinished"), 63);

    // The record is the 16 byte header, then the width and the height as f32 in dp.
    let mut wire = Vec::new();
    encode_event(&vector_report(), &mut wire).expect("the report encodes");
    assert_eq!(wire.len(), REPORT_LEN);
    assert_eq!(&wire[0..2], &63_u16.to_le_bytes());
    assert_eq!(&wire[2..4], &(REPORT_LEN as u16).to_le_bytes());
    assert_eq!(&wire[4..8], &14_u32.to_le_bytes());
    assert_eq!(&wire[8..16], &30_u64.to_le_bytes());
    assert_eq!(&wire[16..20], &240.5_f32.to_bits().to_le_bytes());
    assert_eq!(&wire[20..24], &0.0_f32.to_bits().to_le_bytes());
    assert_eq!(decode_event(&wire), Ok(vector_report()));

    // The same bytes the Kotlin side writes, read from the shared vector.
    let vector = &EVENTS[REPORT_AT..REPORT_AT + REPORT_LEN];
    assert_eq!(vector, wire.as_slice());
    assert_eq!(decode_event(vector), Ok(vector_report()));

    // A record of any other length is refused rather than read short.
    let mut short = wire[..20].to_vec();
    short[2..4].copy_from_slice(&20_u16.to_le_bytes());
    assert_eq!(
        decode_event(&short),
        Err(ProtocolError::InvalidRecordLength)
    );

    // The handler property travels as a handler id, like every other handler.
    let (_host, tree) = start(|| {
        Box()
            .modifier(Modifier.motion(MotionRole::Standard))
            .on_motion_finished(|_| {})
            .content(|| {});
    });
    assert_eq!(tree.handled("Box", PropertyKind::OnMotionFinished).len(), 1);
}

/// A report reaches the closure the node was given, once for each report, with the size
/// it carries.
#[test]
fn fr24_motion_finished_reports_once_with_the_final_size() {
    let seen: Arc<Mutex<Vec<MotionFinished>>> = Arc::new(Mutex::new(Vec::new()));
    let sink = Arc::clone(&seen);
    let (mut host, tree) = start(move || {
        let sink = Arc::clone(&sink);
        Column()
            .modifier(Modifier.motion(MotionRole::Standard).height(120.0))
            .on_motion_finished(move |finished| sink.lock().unwrap().push(finished))
            .content(|| {
                Text("collapsing");
            });
    });
    let handled = tree.handled("Column", PropertyKind::OnMotionFinished);
    assert_eq!(handled.len(), 1, "{}", tree.dump());
    let (node_id, handler_id) = handled[0];
    assert!(seen.lock().unwrap().is_empty(), "nothing before a report");

    host.dispatch(HostEvent {
        node_id,
        handler_id,
        payload: EventPayload::MotionFinished {
            width_dp: 320.0,
            height_dp: 0.0,
        },
    })
    .expect("the report is accepted");
    assert_eq!(
        *seen.lock().unwrap(),
        vec![MotionFinished {
            width_dp: 320.0,
            height_dp: 0.0,
        }]
    );

    // Through the boundary's own entry point, from the bytes the Renderer writes.
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::MotionFinished {
                width_dp: 320.0,
                height_dp: 120.0,
            },
        },
        &mut wire,
    )
    .unwrap();
    host.dispatch_event(&wire)
        .expect("the encoded report is accepted");
    assert_eq!(
        *seen.lock().unwrap(),
        vec![
            MotionFinished {
                width_dp: 320.0,
                height_dp: 0.0,
            },
            MotionFinished {
                width_dp: 320.0,
                height_dp: 120.0,
            },
        ]
    );
}

/// A node given no handler writes no handler property, so its wire is what it was before
/// the property existed; a text field takes the handler the same way a container does.
#[test]
fn fr24_no_handler_means_no_listener() {
    let mut host = ComposeHost::with_content(|| {
        Column()
            .modifier(Modifier.motion(MotionRole::Standard))
            .content(|| {
                Text("plain");
                TextField().modifier(Modifier.motion(MotionRole::Quick));
            });
    });
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the batch decodes");
    assert!(
        !mutations.iter().any(|mutation| matches!(
            mutation,
            Mutation::SetProp {
                property: PropertyKind::OnMotionFinished,
                ..
            }
        )),
        "a node with no handler wrote one: {mutations:?}"
    );

    let (_host, tree) = start(|| {
        TextField()
            .modifier(Modifier.motion(MotionRole::Quick))
            .on_motion_finished(|_| {});
    });
    assert_eq!(
        tree.handled("TextField", PropertyKind::OnMotionFinished)
            .len(),
        1
    );
}
