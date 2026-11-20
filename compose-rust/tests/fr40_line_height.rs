//! Text line height on the wire: explicit line height in sp, or NaN (normal) to use
//! the font's own metrics. An unset line height (0 or never calling this) falls back
//! to the type role's line height.
//!
//! Two frames with `line_height(f32::NAN)` must not re-emit every frame.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{PropertyKind, WidgetKind};
use compose_rust::ui::*;

/// The line height values one node was given, in order.
fn line_heights(mutations: &[Mutation<'_>], node: u32) -> Vec<Option<f32>> {
    mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::LineHeight,
                value,
            } if *node_id == node => match value {
                PropertyValue::Float(value) => Some(Some(*value)),
                PropertyValue::None => Some(None),
                other => panic!("line height is a float or nothing, not {other:?}"),
            },
            _ => None,
        })
        .collect()
}

fn texts(mutations: &[Mutation<'_>]) -> Vec<u32> {
    mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Text,
            } => Some(*node_id),
            _ => None,
        })
        .collect()
}

/// 0 is normal (NaN), 1 is 24sp, 2 is plain (unset: 0.0).
#[Composable]
fn LineHeighted(height_mode: MutableState<u8>) {
    Column!({
        let line_height = match height_mode.get() {
            0 => Some(f32::NAN),
            1 => Some(24.0),
            _ => None,
        };
        Text!("A", line_height = line_height);
        Text!("normal", line_height = f32::NAN);
        Text!("explicit", line_height = 18.0);
        Text!("plain");
    });
}

#[test]
fn fr40_normal_line_height_does_not_reemit_every_frame() {
    let mode = mutable_state_of(0u8);
    let state = mode;
    let mut host = ComposeHost::with_content(move || LineHeighted(state));
    let first = host.rebuild().unwrap().to_vec();
    let first = decode_batch(&first).unwrap();
    let nodes = texts(&first);
    assert_eq!(nodes.len(), 4, "four texts are created");

    // First node was created with NaN (normal line height).
    let h0 = line_heights(&first, nodes[0]);
    assert_eq!(h0.len(), 1, "first text receives one line height prop");
    assert!(h0[0].is_some_and(f32::is_nan), "initial line height is NaN");

    // Second node is explicitly NaN.
    let h1 = line_heights(&first, nodes[1]);
    assert_eq!(h1.len(), 1);
    assert!(h1[0].is_some_and(f32::is_nan), "normal line height is NaN");

    // Third node is explicitly 18.0.
    assert_eq!(line_heights(&first, nodes[2]), vec![Some(18.0)]);

    // Fourth node is unset (sends nothing).
    assert!(
        line_heights(&first, nodes[3]).is_empty(),
        "an unset line height sends nothing, so the Renderer uses the role's"
    );

    // Re-rendering another frame with identical NaN state MUST NOT re-emit line_height!
    let second = host.render_frame(1).unwrap().to_vec();
    let second_batch = decode_batch(&second).unwrap();
    let h0_second = line_heights(&second_batch, nodes[0]);
    assert!(
        h0_second.is_empty(),
        "identical NaN line height across frames does not re-emit: {h0_second:?}"
    );

    // Switching to 24.0 emits 24.0.
    mode.set(1);
    let third = host.render_frame(2).unwrap().to_vec();
    let third_batch = decode_batch(&third).unwrap();
    assert_eq!(
        line_heights(&third_batch, nodes[0]),
        vec![Some(24.0)],
        "changing from NaN to explicit line height emits new value"
    );

    // Switching back to NaN emits NaN.
    mode.set(0);
    let fourth = host.render_frame(3).unwrap().to_vec();
    let fourth_batch = decode_batch(&fourth).unwrap();
    let h0_fourth = line_heights(&fourth_batch, nodes[0]);
    assert_eq!(h0_fourth.len(), 1);
    assert!(
        h0_fourth[0].is_some_and(f32::is_nan),
        "switching back to NaN emits NaN"
    );
}
