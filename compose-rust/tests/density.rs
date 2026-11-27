//! How many device pixels one dp covers, as the Renderer reports it to the Host, and how
//! application code reads it and hears that it changed.

mod support;

use compose_rust::protocol::{HostEvent, ProtocolError, decode_event, encode_event};
use compose_rust::runtime::*;
use compose_rust::schema::{EVENT_SCHEMA, EventPayload, EventPayloadType};
use compose_rust::ui::*;
use compose_rust::{ComposeHost, density};
use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use support::Tree;

#[Composable]
fn App() {
    Text!("density");
}

fn start() -> (ComposeHost, Tree) {
    compose_rust::density::reset_density();
    let mut host = ComposeHost::with_content(App);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

fn density_changed(scale: f32) -> HostEvent<'static> {
    HostEvent::new(0, 0, EventPayload::DensityChanged { scale })
}

/// The record is 20 bytes, the header and one `f32`, under event tag 53, and a scale comes
/// back as it went. The vector's last record is a screen at 200%. A scale of zero, below
/// zero or NaN is a protocol error, and so is a record of another length.
#[test]
fn fr43_density_event_round_trips() {
    let schema = EVENT_SCHEMA
        .iter()
        .find(|event| event.name == "DensityChanged")
        .expect("DensityChanged is in the event schema");
    assert_eq!(schema.tag, 53);
    assert_eq!(schema.payload, EventPayloadType::Density);
    let at = EVENT_SCHEMA
        .iter()
        .position(|event| event.name == "DensityChanged")
        .unwrap();
    assert_eq!(
        EVENT_SCHEMA[at - 1].name,
        "SelectionsChanged",
        "the event table is append only, so the density stays where it was appended"
    );
    assert_eq!(
        EVENT_SCHEMA.iter().filter(|event| event.tag == 53).count(),
        1,
        "tag 53 names one event"
    );

    for scale in [1.0_f32, 1.25, 2.0, 2.625, 3.0] {
        let event = density_changed(scale);
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        assert_eq!(bytes.len(), 20);
        assert_eq!(&bytes[0..2], &53_u16.to_le_bytes(), "the event tag is 53");
        assert_eq!(&bytes[2..4], &20_u16.to_le_bytes());
        assert_eq!(&bytes[4..8], &0_u32.to_le_bytes());
        assert_eq!(&bytes[8..16], &0_u64.to_le_bytes());
        assert_eq!(&bytes[16..20], &scale.to_bits().to_le_bytes());
        assert_eq!(decode_event(&bytes), Ok(event));
    }

    let events = include_bytes!("vectors/events.bin");
    assert_eq!(events.len(), 1759 + 20 + 32 + 24 + 36 + 171 + 20 + 20);
    assert_eq!(decode_event(&events[1759..1779]), Ok(density_changed(2.0)));
    let mut encoded = Vec::new();
    encode_event(&density_changed(2.0), &mut encoded).unwrap();
    assert_eq!(encoded.as_slice(), &events[1759..1779]);

    for bad in [0.0_f32, -0.0, -1.0, f32::NEG_INFINITY, f32::NAN] {
        let mut patched = encoded.clone();
        patched[16..20].copy_from_slice(&bad.to_bits().to_le_bytes());
        assert!(
            matches!(
                decode_event(&patched),
                Err(ProtocolError::InvalidValueKind(_))
            ),
            "{bad} decoded as a density"
        );
    }
    let mut long = encoded.clone();
    long[2..4].copy_from_slice(&24_u16.to_le_bytes());
    long.extend_from_slice(&[0; 4]);
    assert_eq!(decode_event(&long), Err(ProtocolError::InvalidRecordLength));
}

/// Until the Renderer has said anything the density reads as 1.0, for the single window
/// and for a window of a multi-window application alike.
#[test]
fn fr43_density_defaults_to_one_before_the_first_event() {
    let (_host, _tree) = start();
    assert_eq!(density(), 1.0);
    assert_eq!(compose_rust::density::density_of(7), 1.0);
    assert_eq!(compose_rust::density::DEFAULT_DENSITY, 1.0);
}

/// A new value is readable and wakes a reader once; the same value again wakes nobody. The
/// reader here is what holds measurements made at the old density: `DensityChanged(2.0)`
/// from the Renderer empties its cache, and a repeat leaves what was measured since alone.
#[test]
fn fr43_density_change_reaches_readers() {
    let (mut host, _tree) = start();
    let woken = Arc::new(AtomicUsize::new(0));
    let measured: Arc<Mutex<HashMap<&'static str, f32>>> = Arc::new(Mutex::new(HashMap::new()));
    measured.lock().unwrap().insert("hello", 31.5);

    let counter = woken.clone();
    let cache = measured.clone();
    let subscription = compose_rust::density::subscribe(Arc::new(move || {
        counter.fetch_add(1, Ordering::SeqCst);
        cache.lock().unwrap().clear();
    }));

    // 1.0 is what was read before any report, so reporting it changes nothing.
    host.dispatch(density_changed(1.0)).unwrap();
    assert_eq!(woken.load(Ordering::SeqCst), 0, "the default wakes nobody");
    assert_eq!(measured.lock().unwrap().len(), 1);

    host.dispatch(density_changed(2.0)).unwrap();
    assert_eq!(density(), 2.0);
    assert_eq!(woken.load(Ordering::SeqCst), 1);
    assert!(
        measured.lock().unwrap().is_empty(),
        "measurements made at the old density were kept"
    );

    measured.lock().unwrap().insert("hello", 63.0);
    host.dispatch(density_changed(2.0)).unwrap();
    assert_eq!(woken.load(Ordering::SeqCst), 1, "a repeat wakes nobody");
    assert_eq!(
        measured.lock().unwrap().len(),
        1,
        "a repeat emptied the cache"
    );

    host.dispatch(density_changed(1.25)).unwrap();
    assert_eq!(density(), 1.25);
    assert_eq!(woken.load(Ordering::SeqCst), 2);
    assert!(measured.lock().unwrap().is_empty());

    drop(subscription);
    host.dispatch(density_changed(3.0)).unwrap();
    assert_eq!(density(), 3.0, "a report nobody reads is still recorded");
    assert_eq!(
        woken.load(Ordering::SeqCst),
        2,
        "a dropped subscription is not called"
    );
}

/// The density and the zoom are separate reports: neither changes what the other reads.
#[test]
fn fr43_density_is_independent_of_the_zoom() {
    let (mut host, _tree) = start();
    host.dispatch(density_changed(2.0)).unwrap();
    assert_eq!(compose_rust::zoom().k, 1.0);
    host.dispatch(HostEvent::new(
        0,
        0,
        EventPayload::ZoomChanged {
            k: 1.2,
            os: 1.0,
            level: 1,
        },
    ))
    .unwrap();
    assert_eq!(density(), 2.0);
}

/// The report is about a window's screen, not a node: one that names a handler, or a node
/// that is not a window, is a protocol error, and so is a scale that is not a positive
/// number even when it did not come through the decoder. None of them is recorded.
#[test]
fn fr43_a_malformed_density_report_is_a_protocol_error() {
    let (mut host, _tree) = start();
    let mut named = density_changed(2.0);
    named.node_id = 3;
    assert!(matches!(
        host.dispatch(named),
        Err(ProtocolError::InvalidValueKind(_))
    ));
    let mut handled = density_changed(2.0);
    handled.handler_id = 9;
    assert!(matches!(
        host.dispatch(handled),
        Err(ProtocolError::InvalidValueKind(_))
    ));
    for bad in [0.0_f32, -2.0, f32::NAN] {
        assert!(
            matches!(
                host.dispatch(density_changed(bad)),
                Err(ProtocolError::InvalidValueKind(_))
            ),
            "{bad} was taken as a density"
        );
    }
    assert_eq!(density(), 1.0);
}
