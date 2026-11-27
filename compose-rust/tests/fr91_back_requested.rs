//! A request to go back, as it reaches the wire and the handler: the event, its source
//! word, the handler property, and what a handler's `consume` answers the Renderer with.
//!
//! Who is asked first and what the platform does with a request nobody consumed is the
//! Renderer's, and is tested on its side.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{
    HostEvent, Mutation, PropertyValue, ProtocolError, decode_batch, decode_event, encode_event,
};
use compose_rust::runtime::*;
use compose_rust::schema::{EVENT_SCHEMA, EventPayload, EventPayloadType, PropertyKind};
use compose_rust::ui::*;
use std::sync::atomic::{AtomicU32, AtomicUsize, Ordering};
use support::Tree;

const MUTATIONS: &[u8] = include_bytes!("vectors/mutations.bin");
const EVENTS: &[u8] = include_bytes!("vectors/events.bin");

/// Where the back request sits in the event vector.
const BACK_AT: usize = 2062;
const BACK_LEN: usize = 20;

fn back(node_id: u32, handler_id: u64, source: BackSource) -> HostEvent<'static> {
    HostEvent::new(node_id, handler_id, EventPayload::BackRequested { source })
}

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

/// The event is 20 bytes under tag 70, the header and one source word, and the handler
/// travels as property 223. Both are in the checked-in vectors after every older record.
/// A source nobody named and a record of another length are protocol errors.
#[test]
fn fr91_back_requested_round_trips() {
    assert_eq!(PropertyKind::OnBackRequested as u16, 223);
    assert_eq!(
        PropertyKind::from_name("on_back_requested"),
        Ok(PropertyKind::OnBackRequested)
    );
    let schema = EVENT_SCHEMA
        .iter()
        .find(|event| event.name == "BackRequested")
        .expect("BackRequested is in the event schema");
    assert_eq!(schema.tag, 70);
    assert_eq!(schema.payload, EventPayloadType::Back);
    let at = EVENT_SCHEMA
        .iter()
        .position(|event| event.name == "BackRequested")
        .unwrap();
    assert_eq!(
        EVENT_SCHEMA[at - 1].name,
        "AssetWanted",
        "the event table is append only, so the back request stays where it was appended"
    );
    assert_eq!(
        [
            BackSource::SystemBack,
            BackSource::Escape,
            BackSource::PointerBack,
            BackSource::EdgeSwipe
        ]
        .map(u16::from),
        [1, 2, 3, 4]
    );

    for source in [
        BackSource::SystemBack,
        BackSource::Escape,
        BackSource::PointerBack,
        BackSource::EdgeSwipe,
    ] {
        let event = back(7, 9, source);
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        assert_eq!(bytes.len(), BACK_LEN);
        assert_eq!(&bytes[0..2], &70_u16.to_le_bytes());
        assert_eq!(&bytes[2..4], &20_u16.to_le_bytes());
        assert_eq!(&bytes[4..8], &7_u32.to_le_bytes());
        assert_eq!(&bytes[8..16], &9_u64.to_le_bytes());
        assert_eq!(&bytes[16..20], &u32::from(u16::from(source)).to_le_bytes());
        assert_eq!(decode_event(&bytes), Ok(event));
    }

    // The vector's record, byte for byte, and nothing after it that this test knows of.
    let vector = back(1, 302, BackSource::EdgeSwipe);
    assert!(EVENTS.len() >= BACK_AT + BACK_LEN);
    assert_eq!(
        decode_event(&EVENTS[BACK_AT..BACK_AT + BACK_LEN]),
        Ok(vector.clone())
    );
    let mut encoded = Vec::new();
    encode_event(&vector, &mut encoded).unwrap();
    assert_eq!(encoded, &EVENTS[BACK_AT..BACK_AT + BACK_LEN]);
    assert!(
        decode_batch(MUTATIONS)
            .expect("the mutation vector decodes")
            .contains(&Mutation::SetProp {
                node_id: 1,
                property: PropertyKind::OnBackRequested,
                value: PropertyValue::Integer(302),
            })
    );

    // A source this Host has no name for.
    for unknown in [0_u32, 5, 0x1_0002] {
        let mut bytes = encoded.clone();
        bytes[16..20].copy_from_slice(&unknown.to_le_bytes());
        assert!(
            matches!(
                decode_event(&bytes),
                Err(ProtocolError::InvalidValueKind(_))
            ),
            "source {unknown} was read as {:?}",
            decode_event(&bytes)
        );
    }
    // A record of another length.
    let mut long = encoded.clone();
    long[2..4].copy_from_slice(&24_u16.to_le_bytes());
    long.extend_from_slice(&[0; 4]);
    assert_eq!(decode_event(&long), Err(ProtocolError::InvalidRecordLength));
}

/// A container and a window given a handler write it as `OnBackRequested`; one given none
/// writes nothing, so a window with no handler never claims the platform's back.
#[test]
fn fr91_on_back_requested_travels_as_a_handler_property() {
    let (_host, tree) = start(|| {
        Column!(on_back_requested = Some(|_: BackRequested| {}), {
            Box!(on_back_requested = Some(|_: BackRequested| {}));
            Row!();
        });
    });
    assert_eq!(
        tree.handled("Column", PropertyKind::OnBackRequested).len(),
        1
    );
    assert_eq!(tree.handled("Box", PropertyKind::OnBackRequested).len(), 1);
    assert!(
        tree.handled("Row", PropertyKind::OnBackRequested)
            .is_empty()
    );

    let (_host, tree) = start(|| {
        Window!(|| {}, on_back_requested = Some(|_: BackRequested| {}), {
            Text!("page");
        });
    });
    assert_eq!(
        tree.handled("Window", PropertyKind::OnBackRequested).len(),
        1
    );
}

/// The handler is given the source, and what it does with `consume` is the call's result:
/// 1 for a request it acted on, 0 for one it left to the next handler out.
#[test]
fn fr91_consume_is_the_synchronous_result() {
    static HEARD: AtomicUsize = AtomicUsize::new(0);
    static SOURCE: AtomicU32 = AtomicU32::new(0);
    let (mut host, tree) = start(|| {
        Column!(
            on_back_requested = Some(|event: BackRequested| {
                HEARD.fetch_add(1, Ordering::SeqCst);
                SOURCE.store(u32::from(u16::from(event.source())), Ordering::SeqCst);
                // Escape is left alone here; every other source is acted on.
                if event.source() != BackSource::Escape {
                    event.consume();
                }
            })
        );
    });
    let (node_id, handler_id) = tree.handled("Column", PropertyKind::OnBackRequested)[0];

    let (_, result) = host
        .dispatch(back(node_id, handler_id, BackSource::SystemBack))
        .expect("the back request is accepted");
    assert_eq!(result, 1, "a consumed request answers 1");
    assert_eq!(SOURCE.load(Ordering::SeqCst), 1);

    let (_, result) = host
        .dispatch(back(node_id, handler_id, BackSource::Escape))
        .expect("the back request is accepted");
    assert_eq!(result, 0, "a request left alone answers 0");
    assert_eq!(SOURCE.load(Ordering::SeqCst), 2);
    assert_eq!(HEARD.load(Ordering::SeqCst), 2);
}
