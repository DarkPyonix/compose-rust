//! Animations written with the authoring API: `animate_*_as_state` and a graphics layer.
//! The Renderer plays them; the Host sends one record and is not involved again.

use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{
    HostEvent, Mutation, PropertyValue, decode_batch, encode_event,
};
use dioxus_compose_adapter::{
    AnimatedProperty, AnimationSpec, EventPayload, GraphicsLayer, Host, KeyframeValue,
    PropertyKind, WidgetKind, animate_float_as_state,
};
use std::sync::atomic::{AtomicUsize, Ordering};

static RENDERS: AtomicUsize = AtomicUsize::new(0);

fn fading() -> Element {
    let mut faded = use_signal(|| false);
    RENDERS.fetch_add(1, Ordering::SeqCst);
    rsx! {
        Column {
            Button { text: "fade", on_click: move |()| faded.toggle() }
            AbsoluteBox {
                required_size: (40.0, 40.0),
                animated_alpha: animate_float_as_state(if faded() { 0.0 } else { 1.0 }, AnimationSpec::STANDARD),
            }
        }
    }
}

fn owned(batch: &[u8]) -> Vec<Mutation<'static>> {
    // Leaked: a test keeps a few small batches past the call that produced them.
    let bytes: &'static [u8] = Box::leak(batch.to_vec().into_boxed_slice());
    decode_batch(bytes).unwrap()
}

fn node_of(mutations: &[Mutation<'_>], widget: WidgetKind) -> u32 {
    mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: kind,
            } if *kind == widget => Some(*node_id),
            _ => None,
        })
        .unwrap_or_else(|| panic!("no {widget:?} was created"))
}

fn click_handler(mutations: &[Mutation<'_>], node: u32) -> u64 {
    mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnClick,
                value: PropertyValue::Integer(handler),
            } if *node_id == node => Some(*handler as u64),
            _ => None,
        })
        .expect("the button has a click handler")
}

fn click(host: &mut Host, node: u32, handler: u64) -> Vec<Mutation<'static>> {
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
    owned(batch)
}

/// A change of target is one batch holding the new underlying value and one transition,
/// the same `StartAnimation` a Host would write by hand for the same change.
#[test]
fn fr41_compose_api_emits_the_same_records() {
    let mut host = Host::new(fading);
    let first = owned(host.rebuild().unwrap());
    assert!(
        !first
            .iter()
            .any(|mutation| matches!(mutation, Mutation::StartAnimation(_))),
        "the first value is where the node starts, not a change to animate"
    );
    let boxed = node_of(&first, WidgetKind::AbsoluteBox);
    let button = node_of(&first, WidgetKind::Button);

    let changed = click(&mut host, button, click_handler(&first, button));
    let started: Vec<_> = changed
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::StartAnimation(animation) => Some(animation.clone()),
            _ => None,
        })
        .collect();
    assert_eq!(started.len(), 1, "one transition: {changed:?}");
    let expected = dioxus_compose_adapter::transition(
        boxed,
        started[0].animation_id,
        AnimatedProperty::Alpha,
        KeyframeValue::Alpha(0.0),
        AnimationSpec::STANDARD,
    );
    assert_eq!(started[0], expected);
    assert!(
        changed.iter().any(|mutation| matches!(
            mutation,
            Mutation::SetModifier { node_id, modifier: Modifier::Alpha(value), .. }
                if *node_id == boxed && *value == 0.0
        )),
        "the underlying value is the target: {changed:?}"
    );
    assert!(
        changed.iter().all(|mutation| matches!(
            mutation,
            Mutation::StartAnimation(_) | Mutation::SetModifier { .. } | Mutation::SetProp { .. }
        )),
        "nothing else goes out: {changed:?}"
    );
}

/// While the Renderer plays it, the component is not rendered again and no frame carries
/// anything: a transition is one batch, and none follow it.
#[test]
fn fr41_compose_api_animation_does_not_recompose() {
    let mut host = Host::new(fading);
    let first = owned(host.rebuild().unwrap());
    let button = node_of(&first, WidgetKind::Button);
    click(&mut host, button, click_handler(&first, button));
    let renders = RENDERS.load(Ordering::SeqCst);
    for frame in 1..=60_u64 {
        let batch = owned(host.render_frame(frame * 16_666_667).unwrap());
        assert!(batch.is_empty(), "frame {frame} carried {batch:?}");
    }
    assert_eq!(
        renders,
        RENDERS.load(Ordering::SeqCst),
        "the component was rendered again"
    );
}

/// A graphics layer goes out as the transform and the opacity it describes: scaled, then
/// turned about its origin, then moved.
#[test]
fn fr41_graphics_layer_is_a_transform_and_an_alpha() {
    let layer = GraphicsLayer {
        rotation_z: 90.0,
        scale_x: 2.0,
        scale_y: 3.0,
        translation_x: 5.0,
        translation_y: -1.0,
        transform_origin: (0.0, 1.0),
        alpha: 0.25,
    };
    let Modifier::Transform {
        a,
        b,
        c,
        d,
        e,
        f,
        origin_x,
        origin_y,
    } = layer.transform()
    else {
        panic!("a graphics layer is a transform");
    };
    for (value, expected) in [(a, 0.0), (b, 2.0), (c, -3.0), (d, 0.0), (e, 5.0), (f, -1.0)] {
        assert!((value - expected).abs() < 1e-5, "{value} != {expected}");
    }
    assert_eq!((origin_x, origin_y), (0.0, 1.0));
    assert_eq!(layer.alpha(), Modifier::Alpha(0.25));
}
