//! The system's light or dark appearance, as the Renderer reports it to the Host, and how
//! application code reads it.

mod support;

use compose_rust::protocol::{HostEvent, ProtocolError, decode_event, encode_event};
use compose_rust::runtime::*;
use compose_rust::schema::{ColorScheme, EventPayload, Theme};
use compose_rust::ui::*;
use compose_rust::{
    ComposeHost, SystemColorScheme, ThemeHandle, resolved_color_scheme,
    subscribe_system_color_scheme, system_color_scheme,
};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use support::Tree;

#[Composable]
fn App() {
    Text("scheme");
}

fn start() -> (ComposeHost, Tree) {
    compose_rust::theme::reset_system_color_scheme();
    let mut host = ComposeHost::with_content(App);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

fn scheme_changed(scheme: SystemColorScheme) -> HostEvent<'static> {
    HostEvent {
        node_id: 0,
        handler_id: 0,
        payload: EventPayload::ColorSchemeChanged(scheme),
    }
}

/// The record is 20 bytes, the header and one word, and both values come back as they went.
/// The vector's last record is the dark report, and a word that is neither 0 nor 1, or a
/// record of another length, is a protocol error.
#[test]
fn fr44_color_scheme_event_round_trips() {
    for scheme in [SystemColorScheme::Light, SystemColorScheme::Dark] {
        let event = scheme_changed(scheme);
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        assert_eq!(bytes.len(), 20);
        assert_eq!(&bytes[0..2], &52_u16.to_le_bytes(), "the event tag is 52");
        assert_eq!(&bytes[2..4], &20_u16.to_le_bytes());
        assert_eq!(&bytes[16..20], &u32::from(u16::from(scheme)).to_le_bytes());
        assert_eq!(decode_event(&bytes), Ok(event));
    }
    assert_eq!(u16::from(SystemColorScheme::Light), 0);
    assert_eq!(u16::from(SystemColorScheme::Dark), 1);

    let events = include_bytes!("vectors/events.bin");
    assert_eq!(events.len(), 1643);
    assert_eq!(
        decode_event(&events[1623..1643]),
        Ok(scheme_changed(SystemColorScheme::Dark))
    );

    let mut bytes = Vec::new();
    encode_event(&scheme_changed(SystemColorScheme::Dark), &mut bytes).unwrap();
    for unknown in [2_u32, 3, 0xffff, 0x1_0000, u32::MAX] {
        let mut patched = bytes.clone();
        patched[16..20].copy_from_slice(&unknown.to_le_bytes());
        assert!(
            matches!(
                decode_event(&patched),
                Err(ProtocolError::InvalidValueKind(_))
            ),
            "{unknown} decoded as a scheme"
        );
    }
    let mut long = bytes.clone();
    long[2..4].copy_from_slice(&24_u16.to_le_bytes());
    long.extend_from_slice(&[0; 4]);
    assert_eq!(decode_event(&long), Err(ProtocolError::InvalidRecordLength));
}

/// What the Renderer reports is readable by application code and wakes a subscriber once.
/// The same value again wakes nobody, and the resolved scheme follows the system only while
/// the theme asks it to.
#[test]
fn fr44_reported_scheme_is_readable_and_wakes_readers_once() {
    let (mut host, _tree) = start();
    assert_eq!(system_color_scheme(), None);
    let woken = Arc::new(AtomicUsize::new(0));
    let counter = woken.clone();
    let subscription = subscribe_system_color_scheme(Arc::new(move || {
        counter.fetch_add(1, Ordering::SeqCst);
    }));

    host.dispatch(scheme_changed(SystemColorScheme::Light))
        .unwrap();
    assert_eq!(system_color_scheme(), Some(SystemColorScheme::Light));
    assert_eq!(woken.load(Ordering::SeqCst), 1);

    host.dispatch(scheme_changed(SystemColorScheme::Light))
        .unwrap();
    assert_eq!(woken.load(Ordering::SeqCst), 1, "a repeat wakes nobody");

    host.dispatch(scheme_changed(SystemColorScheme::Dark))
        .unwrap();
    assert_eq!(system_color_scheme(), Some(SystemColorScheme::Dark));
    assert_eq!(woken.load(Ordering::SeqCst), 2);

    assert_eq!(
        compose_rust::theme::current_theme().color_scheme,
        ColorScheme::FollowSystem
    );
    assert_eq!(resolved_color_scheme(), Some(SystemColorScheme::Dark));
    ThemeHandle::default().set_color_scheme(ColorScheme::Light);
    assert_eq!(resolved_color_scheme(), Some(SystemColorScheme::Light));
    ThemeHandle::default().set(Theme::default().with_color_scheme(ColorScheme::FollowSystem));
    assert_eq!(resolved_color_scheme(), Some(SystemColorScheme::Dark));

    drop(subscription);
    host.dispatch(scheme_changed(SystemColorScheme::Light))
        .unwrap();
    assert_eq!(
        woken.load(Ordering::SeqCst),
        2,
        "a dropped subscription is not called"
    );
}

/// The report is about the system, not a node: one that names a node or a handler is a
/// protocol error.
#[test]
fn fr44_a_scheme_report_naming_a_node_is_a_protocol_error() {
    let (mut host, _tree) = start();
    let mut named = scheme_changed(SystemColorScheme::Dark);
    named.node_id = 3;
    assert!(matches!(
        host.dispatch(named),
        Err(ProtocolError::InvalidValueKind(_))
    ));
    let mut handled = scheme_changed(SystemColorScheme::Dark);
    handled.handler_id = 9;
    assert!(matches!(
        host.dispatch(handled),
        Err(ProtocolError::InvalidValueKind(_))
    ));
    assert_eq!(system_color_scheme(), None);
}
