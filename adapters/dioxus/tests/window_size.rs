//! The window size hook, driven by the size events the Renderer sends.

use dioxus_compose_adapter::Host;
use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
use dioxus_compose_adapter::schema::{EventPayload, PropertyKind};
use dioxus_compose_adapter::window::reset_window_size;
use std::sync::atomic::{AtomicUsize, Ordering};

static RESPONSIVE_RENDERS: AtomicUsize = AtomicUsize::new(0);
static SIBLING_RENDERS: AtomicUsize = AtomicUsize::new(0);

#[component]
fn Responsive() -> Element {
    RESPONSIVE_RENDERS.fetch_add(1, Ordering::SeqCst);
    let window = use_window_size();
    let label = if window.is_expanded() {
        "sidebar"
    } else if window.is_medium() {
        "two columns"
    } else {
        "one column"
    };
    rsx! { Text { text: label } }
}

#[component]
fn ShortOrTall() -> Element {
    let window = use_window_size();
    let label = if window.is_tall() {
        "tall"
    } else if window.is_medium_height() {
        "ordinary"
    } else {
        "short"
    };
    rsx! { Text { text: label } }
}

fn height_app() -> Element {
    rsx! { Column { ShortOrTall {} } }
}

#[component]
fn Sibling() -> Element {
    SIBLING_RENDERS.fetch_add(1, Ordering::SeqCst);
    rsx! { Text { text: "fixed" } }
}

fn responsive_app() -> Element {
    rsx! {
        Column {
            Responsive {}
            Sibling {}
        }
    }
}

fn resize(host: &mut Host, width_dp: f32) -> Vec<String> {
    resize_to(host, width_dp, 800.0)
}

fn resize_to(host: &mut Host, width_dp: f32, height_dp: f32) -> Vec<String> {
    let event = HostEvent {
        node_id: 0,
        handler_id: 0,
        payload: EventPayload::WindowSizeChanged {
            width_dp,
            height_dp,
            class: WindowSizeClass::from_width_dp(width_dp),
            height_class: WindowHeightClass::from_height_dp(height_dp),
        },
    };
    let (batch, _) = host.dispatch(event).unwrap();
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

#[test]
fn fr20_crossing_a_boundary_rerenders_the_hook_and_resizing_within_a_class_does_not() {
    reset_window_size();
    RESPONSIVE_RENDERS.store(0, Ordering::SeqCst);
    SIBLING_RENDERS.store(0, Ordering::SeqCst);
    let mut host = Host::new(responsive_app);
    host.rebuild().unwrap();
    assert_eq!(RESPONSIVE_RENDERS.load(Ordering::SeqCst), 1);
    assert_eq!(SIBLING_RENDERS.load(Ordering::SeqCst), 1);

    // Crossing 600dp: the hook's component re-renders once, its sibling not at all.
    assert_eq!(resize(&mut host, 700.0), vec!["two columns".to_owned()]);
    assert_eq!(RESPONSIVE_RENDERS.load(Ordering::SeqCst), 2);
    assert_eq!(SIBLING_RENDERS.load(Ordering::SeqCst), 1);

    // A report that does not change the class changes nothing. The Renderer does not
    // send one, and a Host that receives one anyway must not run the VirtualDom for it.
    assert!(resize(&mut host, 700.0).is_empty());
    assert_eq!(RESPONSIVE_RENDERS.load(Ordering::SeqCst), 2);

    assert_eq!(resize(&mut host, 900.0), vec!["sidebar".to_owned()]);
    assert_eq!(RESPONSIVE_RENDERS.load(Ordering::SeqCst), 3);
    assert_eq!(SIBLING_RENDERS.load(Ordering::SeqCst), 1);
    reset_window_size();
}

#[test]
fn fr20_crossing_a_height_boundary_rerenders_and_resizing_within_one_does_not() {
    reset_window_size();
    let mut host = Host::new(height_app);
    host.rebuild().unwrap();

    // 300dp tall is the class the Host already holds, so growing to 479dp inside it
    // changes nothing.
    assert!(resize_to(&mut host, 400.0, 479.0).is_empty());

    // 480dp crosses into the ordinary height, and 900dp into the tall one. One
    // mutation each, and nothing for the step in between.
    assert_eq!(
        resize_to(&mut host, 400.0, 480.0),
        vec!["ordinary".to_owned()]
    );
    assert!(resize_to(&mut host, 400.0, 899.0).is_empty());
    assert_eq!(resize_to(&mut host, 400.0, 900.0), vec!["tall".to_owned()]);
    reset_window_size();
}
