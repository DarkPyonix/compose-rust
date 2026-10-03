//! The HTML and CSS drawing elements written in `rsx!`.

use dioxus_compose_adapter::Host;
use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{Mutation, decode_batch};
use dioxus_compose_adapter::schema::WidgetKind;

const OUTLINE: Paint = Paint::Role(ColorRole::Outline);
const RED: Paint = Paint::Literal(Color::rgb(0xff0000));

fn mixed() -> Element {
    rsx! {
        AbsoluteBox {
            offset: (10.0, 20.0),
            required_size: (200.0, 100.0),
            background: Paint::Role(ColorRole::Surface),
            border: ([1.0, 2.0, 3.0, 4.0], [OUTLINE, RED, OUTLINE, RED]),
            corners: [8.0, 0.0, 8.0, 0.0],
            shadow: (0.0, 2.0, 6.0, -1.0, OUTLINE),
            clip: true,
            alpha: 0.5,
            transform: (0.0, 1.0, -1.0, 0.0, 4.0, 0.0),
        }
    }
}

fn uniform() -> Element {
    rsx! {
        AbsoluteBox {
            border: ([2.0; 4], [OUTLINE; 4]),
            corners: [6.0; 4],
        }
    }
}

/// The modifiers of one node, in the order the Renderer applies them.
fn chain(app: fn() -> Element) -> Vec<Modifier> {
    let mut host = Host::new(app);
    let mutations = decode_batch(host.rebuild().unwrap()).unwrap();
    let boxes: Vec<u32> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::AbsoluteBox,
            } => Some(*node_id),
            _ => None,
        })
        .collect();
    assert_eq!(boxes.len(), 1, "one AbsoluteBox is created");
    let mut slots: Vec<(u16, Modifier)> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                node_id,
                index,
                modifier,
            } if *node_id == boxes[0] && *modifier != Modifier::Empty => {
                Some((*index, modifier.clone()))
            }
            _ => None,
        })
        .collect();
    slots.sort_by_key(|(index, _)| *index);
    slots.into_iter().map(|(_, modifier)| modifier).collect()
}

/// Every element of a box reaches the wire, in the order CSS draws a box in: where it is
/// and how big, its opacity and shadow, its fill and outline, then the clip.
#[test]
fn fr42_an_absolute_box_sends_its_modifiers_in_drawing_order() {
    let names: Vec<String> = chain(mixed)
        .iter()
        .map(|modifier| {
            format!("{modifier:?}")
                .split(|c: char| !c.is_alphanumeric())
                .next()
                .unwrap_or_default()
                .to_owned()
        })
        .collect();
    assert_eq!(
        names,
        [
            "Offset",
            "RequiredSize",
            "Transform",
            "Alpha",
            "Shadow",
            "CornerEach",
            "Background",
            "BorderEach",
            "Clip"
        ]
    );
}

/// Equal sides and equal corners go out as the records an ordinary node already sends.
#[test]
fn fr42_uniform_borders_and_radii_from_rsx_use_the_existing_modifiers() {
    let modifiers = chain(uniform);
    assert!(modifiers.contains(&Modifier::Border {
        width: 2.0,
        paint: OUTLINE,
    }));
    assert!(modifiers.contains(&Modifier::Shape {
        top_start: 6.0,
        top_end: 6.0,
        bottom_end: 6.0,
        bottom_start: 6.0,
    }));
    assert!(
        !modifiers.iter().any(|modifier| matches!(
            modifier,
            Modifier::BorderEach { .. } | Modifier::CornerEach { .. }
        )),
        "{modifiers:?}"
    );
}
