//! The zoom the Renderer reports, and the application's way to set a level.

use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{
    HostEvent, Mutation, PropertyValue, decode_batch, encode_event,
};
use dioxus_compose_adapter::zoom::reset_zoom;
use dioxus_compose_adapter::{EventPayload, Host, PropertyKind, WidgetKind};

fn app() -> Element {
    let zoom = use_zoom();
    let label = format!("level {}", zoom.get().level);
    rsx! {
        Column {
            Text { text: label }
            Button {
                text: "in twice",
                on_click: move |_| {
                    zoom.zoom_in();
                    zoom.zoom_in();
                },
            }
        }
    }
}

fn button(batch: &[u8]) -> (u32, u64) {
    let mutations = decode_batch(batch).unwrap();
    let node = mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Button,
            } => Some(*node_id),
            _ => None,
        })
        .expect("a button");
    let handler = mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnClick,
                value: PropertyValue::Integer(handler),
            } if *node_id == node => Some(*handler as u64),
            _ => None,
        })
        .expect("a click handler");
    (node, handler)
}

fn texts(batch: &[u8]) -> Vec<String> {
    decode_batch(batch)
        .unwrap()
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                property: PropertyKind::Text,
                value: PropertyValue::String(value),
                ..
            } => Some((*value).to_owned()),
            _ => None,
        })
        .collect()
}

fn report(host: &mut Host, k: f32, os: f32, level: i32) -> Vec<String> {
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: EventPayload::ZoomChanged { k, os, level },
        },
        &mut wire,
    )
    .unwrap();
    let (batch, _) = host.dispatch_event(&wire).unwrap();
    texts(batch)
}

/// A report reaches the components that read the zoom, and the same report twice wakes
/// nothing.
#[test]
fn fr43_a_zoom_report_reaches_the_components_that_read_it() {
    reset_zoom();
    let mut host = Host::new(app);
    let first = host.rebuild().unwrap().to_vec();
    assert!(texts(&first).contains(&"level 0".to_owned()));

    assert_eq!(
        report(&mut host, 1.25 * 1.44, 1.25, 2),
        vec!["level 2".to_owned()]
    );
    assert!(report(&mut host, 1.25 * 1.44, 1.25, 2).is_empty());
    assert_eq!(dioxus_compose_adapter::zoom::zoom().os, 1.25);
    reset_zoom();
}

/// A level the application sets goes out on the window record, in the batch the handler
/// produced, and two steps in one handler are two steps.
#[test]
fn fr43_an_application_level_rides_on_the_window_record() {
    reset_zoom();
    let mut host = Host::new(app);
    let first = host.rebuild().unwrap().to_vec();
    let (node, handler) = button(&first);

    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: node,
            handler_id: handler,
            payload: EventPayload::Clicked,
        },
        &mut wire,
    )
    .unwrap();
    let (batch, _) = host.dispatch_event(&wire).unwrap();
    let levels: Vec<Option<i8>> = decode_batch(batch)
        .unwrap()
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetWindow(window) => Some(window.zoom_level),
            _ => None,
        })
        .collect();
    assert_eq!(levels, vec![Some(2)]);
    reset_zoom();
}
