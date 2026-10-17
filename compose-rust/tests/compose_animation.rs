//! Animations written with compose-rust's own API: `animate_*_as_state` and
//! `Modifier.graphics_layer`. The Renderer plays them; the Host sends one record and is not
//! involved again.

mod support;

use compose_rust::protocol::{Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::Modifier as Wire;
use compose_rust::schema::WidgetKind;
use compose_rust::ui::*;
use compose_rust::{
    AnimatedProperty, AnimationSpec, ComposeHost, GraphicsLayer, KeyframeValue,
    animate_color_as_state, animate_float_as_state, animate_offset_as_state, transition,
};
use std::cell::Cell;
use support::Tree;

thread_local! {
    /// Per test thread: the tests in this file run in parallel, and a count shared between
    /// them would see the other test's compositions.
    static COMPOSED: Cell<usize> = const { Cell::new(0) };
}

#[composable]
fn Fading(faded: MutableState<bool>) {
    COMPOSED.with(|composed| composed.set(composed.get() + 1));
    let target = if faded.get() { 0.0 } else { 1.0 };
    Box()
        .modifier(
            Modifier
                .size(40.0, 40.0)
                .animated_alpha(animate_float_as_state(target, AnimationSpec::STANDARD)),
        )
        .content(|| {});
}

fn owned(batch: &[u8]) -> Vec<Mutation<'static>> {
    // Leaked: a test keeps a few small batches past the call that produced them.
    let bytes: &'static [u8] = std::boxed::Box::leak(batch.to_vec().into_boxed_slice());
    decode_batch(bytes).expect("the batch decodes")
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

fn start(
    content: impl Fn() + Send + Sync + 'static,
) -> (ComposeHost, Tree, Vec<Mutation<'static>>) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    tree.apply(&batch);
    (host, tree, owned(&batch))
}

/// A change of target is one batch holding the new underlying value and one transition,
/// the same `StartAnimation` a Host would write by hand for the same change.
#[test]
fn fr41_compose_api_emits_the_same_records() {
    let faded = mutable_state_of(false);
    let state = faded.clone();
    let (mut host, mut tree, first) = start(move || Fading(state.clone()));
    assert!(
        !first
            .iter()
            .any(|mutation| matches!(mutation, Mutation::StartAnimation(_))),
        "the first value is where the node starts, not a change to animate"
    );
    let boxed = node_of(&first, WidgetKind::Box);

    faded.set(true);
    let batch = host.render_frame(16_666_667).expect("the frame encodes").to_vec();
    tree.apply(&batch);
    let changed = owned(&batch);
    let started: Vec<_> = changed
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::StartAnimation(animation) => Some(animation.clone()),
            _ => None,
        })
        .collect();
    assert_eq!(started.len(), 1, "one transition: {changed:?}");
    let expected = transition(
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
            Mutation::SetModifier { node_id, modifier: Wire::Alpha(value), .. }
                if *node_id == boxed && *value == 0.0
        )),
        "the underlying value is the target: {changed:?}"
    );
    assert!(
        changed.iter().all(|mutation| matches!(
            mutation,
            Mutation::StartAnimation(_) | Mutation::SetModifier { .. }
        )),
        "nothing else goes out: {changed:?}"
    );
}

/// While the Renderer plays it, nothing is composed again and no frame carries anything:
/// a transition is one batch, and none follow it.
#[test]
fn fr41_compose_api_animation_does_not_recompose() {
    let faded = mutable_state_of(false);
    let state = faded.clone();
    let (mut host, mut tree, _) = start(move || Fading(state.clone()));
    faded.set(true);
    let batch = host.render_frame(16_666_667).expect("the frame encodes").to_vec();
    tree.apply(&batch);
    let composed = COMPOSED.with(Cell::get);
    for frame in 2..=61_u64 {
        let batch = owned(host.render_frame(frame * 16_666_667).expect("the frame encodes"));
        assert!(batch.is_empty(), "frame {frame} carried {batch:?}");
    }
    assert_eq!(
        composed,
        COMPOSED.with(Cell::get),
        "the composable was composed again while the Renderer played"
    );
}

#[composable]
fn Turned() {
    Box()
        .modifier(Modifier.size(40.0, 40.0).graphics_layer(GraphicsLayer {
            rotation_z: 90.0,
            scale_x: 2.0,
            scale_y: 3.0,
            translation_x: 5.0,
            translation_y: -1.0,
            transform_origin: (0.0, 1.0),
            alpha: 0.25,
        }))
        .content(|| {});
}

/// A graphics layer goes out as the transform and the opacity it describes: scaled, then
/// turned about its origin, then moved, and faded as one group.
#[test]
fn fr41_compose_api_graphics_layer_is_a_transform_and_an_alpha() {
    let (_, _, first) = start(Turned);
    let boxed = node_of(&first, WidgetKind::Box);
    let modifiers: Vec<_> = first
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                node_id, modifier, ..
            } if *node_id == boxed => Some(modifier.clone()),
            _ => None,
        })
        .collect();
    let Some(Wire::Transform {
        a,
        b,
        c,
        d,
        e,
        f,
        origin_x,
        origin_y,
    }) = modifiers
        .iter()
        .find(|modifier| matches!(modifier, Wire::Transform { .. }))
        .cloned()
    else {
        panic!("no Transform in {modifiers:?}");
    };
    for (value, expected) in [(a, 0.0), (b, 2.0), (c, -3.0), (d, 0.0), (e, 5.0), (f, -1.0)] {
        assert!((value - expected).abs() < 1e-5, "{value} != {expected}");
    }
    assert_eq!((origin_x, origin_y), (0.0, 1.0));
    assert!(modifiers.contains(&Wire::Alpha(0.25)), "{modifiers:?}");
}

#[composable]
fn Moving(moved: MutableState<bool>) {
    let (offset, colour) = if moved.get() {
        ((24.0, 8.0), Paint::Role(ColorRole::Primary))
    } else {
        ((0.0, 0.0), Paint::Role(ColorRole::Surface))
    };
    Box()
        .modifier(
            Modifier
                .size(40.0, 40.0)
                .animated_offset(animate_offset_as_state(offset, AnimationSpec::STANDARD))
                .animated_background(animate_color_as_state(colour, AnimationSpec::STANDARD)),
        )
        .content(|| {});
}

/// A translation and a background are animated the same way: one transition each, to
/// the new target, and the target is the node's underlying value.
#[test]
fn fr41_compose_api_offset_and_colour_are_one_transition_each() {
    let moved = mutable_state_of(false);
    let state = moved.clone();
    let (mut host, _, first) = start(move || Moving(state.clone()));
    let boxed = node_of(&first, WidgetKind::Box);
    moved.set(true);
    let changed = owned(host.render_frame(16_666_667).expect("the frame encodes"));
    let properties: Vec<_> = changed
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::StartAnimation(animation) if animation.node_id == boxed => {
                Some(animation.property)
            }
            _ => None,
        })
        .collect();
    assert_eq!(properties.len(), 2, "{changed:?}");
    assert!(properties.contains(&AnimatedProperty::Transform));
    assert!(properties.contains(&AnimatedProperty::Background));
    assert!(
        changed.iter().any(|mutation| matches!(
            mutation,
            Mutation::SetModifier { modifier: Wire::Background(Paint::Role(ColorRole::Primary)), .. }
        )),
        "{changed:?}"
    );
}
