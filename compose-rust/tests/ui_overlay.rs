//! An overlay reaches the wire through the app-facing Modifier as one modifier record,
//! and nothing about the node's size or radius has to be restated for it.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{BatchEncoder, Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::MODIFIER_SCHEMA;
use compose_rust::ui::*;
use support::Tree;

const WASH: Paint = Paint::Literal(Color::argb(0x1f00_0000));

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

/// The record is tag 39 and comes back from the decoder as it was written, for a literal and
/// for a role.
#[test]
fn fr60_overlay_round_trips() {
    let schema = MODIFIER_SCHEMA
        .iter()
        .find(|variant| variant.name == "Overlay")
        .expect("the schema names the overlay");
    assert_eq!(schema.tag, 39);
    for paint in [WASH, Paint::Role(ColorRole::Primary)] {
        let expected = [Mutation::SetModifier {
            node_id: 3,
            index: 27,
            modifier: compose_rust::Modifier::Overlay(paint),
        }];
        let mut encoder = BatchEncoder::default();
        encoder.encode(&expected[0]).unwrap();
        let bytes = encoder.finish().unwrap().to_vec();
        assert_eq!(decode_batch(&bytes).unwrap(), expected);
    }
}

/// The builder sends the paint and nothing else: no size and no radius of its own, beside
/// the radius the node already had.
#[test]
fn fr60_overlay_follows_bounds_and_shape_on_the_wire() {
    let (_host, tree) = start(|| {
        Box()
            .modifier(Modifier.corner_radius(12.0).overlay(WASH))
            .content(|| {
                Text("label");
            });
        Box().modifier(Modifier.corner_radius(12.0)).content(|| {});
    });
    let dump = tree.dump();
    let boxes: Vec<&str> = dump
        .lines()
        .filter(|line| line.trim_start().starts_with("Box"))
        .collect();
    assert_eq!(boxes.len(), 2, "{dump}");
    assert!(boxes[0].contains("Overlay("), "{dump}");
    assert!(!boxes[1].contains("Overlay("), "{dump}");
    for line in &boxes {
        assert!(!line.contains("Size"), "{line}");
        assert!(!line.contains("Width"), "{line}");
        assert!(!line.contains("Height"), "{line}");
    }
}

/// Taking the overlay away is one record that empties its slot, and the content is left as
/// it was.
#[test]
fn fr60_overlay_removed_sends_one_modifier() {
    let pressed = mutable_state_of(true);
    let state = pressed;
    let (mut host, mut tree) = start(move || {
        Box()
            .modifier(
                Modifier
                    .corner_radius(12.0)
                    .overlay_if(state.get().then_some(WASH)),
            )
            .content(|| {
                Text("child");
            });
    });
    pressed.set(false);
    let batch = host.render_frame(0).expect("the frame encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the batch decodes");
    tree.apply(&batch);
    assert_eq!(mutations.len(), 1, "{mutations:?}");
    assert!(
        matches!(
            mutations[0],
            Mutation::SetModifier {
                index: 34,
                modifier: compose_rust::Modifier::Empty,
                ..
            }
        ),
        "{mutations:?}"
    );
    assert_eq!(tree.texts(), vec!["child".to_owned()]);
}
