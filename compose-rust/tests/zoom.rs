//! The zoom the Renderer reports, the level the application asks for, and the property that
//! tells an HTML island how large to draw.

mod support;

use compose_rust::protocol::{
    BatchEncoder, HostEvent, Mutation, PropertyValue, ProtocolError, decode_batch,
};
use compose_rust::runtime::*;
use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind};
use compose_rust::ui::*;
use compose_rust::{ComposeHost, ZoomHandle, zoom};
use support::Tree;

#[composable]
fn App() {
    Button("in twice").on_click(|| {
        let handle = ZoomHandle::default();
        handle.zoom_in();
        handle.zoom_in();
    });
}

fn start() -> (ComposeHost, Tree) {
    compose_rust::zoom::reset_zoom();
    let mut host = ComposeHost::with_content(|| App());
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

fn zoom_changed(k: f32, os: f32, level: i32) -> HostEvent<'static> {
    HostEvent {
        node_id: 0,
        handler_id: 0,
        payload: EventPayload::ZoomChanged { k, os, level },
    }
}

/// The report the Renderer makes is readable by the application, and a window record that
/// came back from a resync keeps the level the reader chose.
#[test]
fn fr43_zoom_changed_is_readable_by_the_application() {
    let (mut host, _tree) = start();
    assert_eq!(zoom().level, 0);
    host.dispatch(zoom_changed(1.25 * 1.44, 1.25, 2)).unwrap();
    let now = zoom();
    assert_eq!((now.os, now.level), (1.25, 2));
    assert!((now.k - 1.8).abs() < 1e-6);
}

/// A report is about the Renderer, not a node: one that names a node or a handler is a
/// protocol error, and so is a level the Renderer cannot be at.
#[test]
fn fr43_a_malformed_zoom_report_is_a_protocol_error() {
    let (mut host, _tree) = start();
    let mut named = zoom_changed(1.0, 1.0, 0);
    named.node_id = 3;
    assert!(matches!(
        host.dispatch(named),
        Err(ProtocolError::InvalidValueKind(_))
    ));
    let mut bytes = Vec::new();
    compose_rust::protocol::encode_event(&zoom_changed(1.0, 1.0, 40), &mut bytes).unwrap();
    assert!(compose_rust::protocol::decode_event(&bytes).is_err());
}

/// Two steps asked for in one handler go out as one window record at level 2.
#[test]
fn fr43_app_zoom_steps_and_persists() {
    let (mut host, tree) = start();
    let (node_id, handler_id) = tree.find("Button", Some("in twice"), 0, PropertyKind::OnClick);
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .unwrap();
    let levels: Vec<_> = decode_batch(batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetWindow(window) => Some(window.zoom_level),
            _ => None,
        })
        .collect();
    assert_eq!(levels, vec![Some(2)]);
}

/// The property that sizes an island is number 120, a float, and travels on an
/// `AbsoluteBox` like any other property.
#[test]
fn fr43_island_zoom_is_property_120_on_an_absolute_box() {
    assert_eq!(PropertyKind::IslandZoom as u16, 120);
    let mutations = vec![
        Mutation::Create {
            node_id: 5,
            widget: WidgetKind::AbsoluteBox,
        },
        Mutation::SetProp {
            node_id: 5,
            property: PropertyKind::IslandZoom,
            value: PropertyValue::Float(1.44),
        },
    ];
    let mut encoder = BatchEncoder::default();
    for mutation in &mutations {
        encoder.encode(mutation).unwrap();
    }
    let bytes = encoder.finish().unwrap().to_vec();
    assert_eq!(decode_batch(&bytes), Ok(mutations));
}
