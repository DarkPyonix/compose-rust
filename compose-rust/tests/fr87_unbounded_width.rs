//! An unbounded width modifier measures content without the parent's maximum width constraint
//! so ObserveSize can report the natural width of the content.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{BatchEncoder, Mutation, decode_batch};
use compose_rust::schema::MODIFIER_SCHEMA;
use compose_rust::ui::*;
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

#[test]
fn fr87_unbounded_width_round_trips() {
    let schema = MODIFIER_SCHEMA
        .iter()
        .find(|variant| variant.name == "UnboundedWidth")
        .expect("the schema names UnboundedWidth");
    assert_eq!(schema.tag, 40);
    assert_eq!(schema.extra_words, 0);

    let expected = [Mutation::SetModifier {
        node_id: 2,
        index: 37,
        modifier: compose_rust::Modifier::UnboundedWidth,
    }];
    let mut encoder = BatchEncoder::default();
    encoder.encode(&expected[0]).unwrap();
    let bytes = encoder.finish().unwrap().to_vec();
    assert_eq!(decode_batch(&bytes).unwrap(), expected);
}

#[test]
fn fr87_modifier_unbounded_width_builder() {
    let (_host, tree) = start(|| {
        Box!(modifier = Modifier.unbounded_width(), {});
    });
    let dump = tree.dump();
    assert!(dump.contains("m37=UnboundedWidth"), "{dump}");

    let (_host, tree) = start(|| {
        Box!(modifier = Modifier.wrap_content_width(true), {});
    });
    let dump = tree.dump();
    assert!(dump.contains("m37=UnboundedWidth"), "{dump}");

    let (_host, tree) = start(|| {
        Box!(modifier = Modifier.wrap_content_width(false), {});
    });
    let dump = tree.dump();
    assert!(!dump.contains("UnboundedWidth"), "{dump}");

    // The last call decides, in either order.
    let (_host, tree) = start(|| {
        Box!(
            modifier = Modifier.unbounded_width().wrap_content_width(false),
            {}
        );
    });
    let dump = tree.dump();
    assert!(!dump.contains("UnboundedWidth"), "{dump}");
}

/// The modifier has a slot of its own, so it sits beside the size report and the pinning
/// that a table cell also carries instead of replacing either.
#[test]
fn fr87_unbounded_width_keeps_its_own_slot_beside_observe_size_and_sticky() {
    let (_host, tree) = start(|| {
        Box!(
            modifier = Modifier.observe_size_every_dp(7).sticky().unbounded_width(),
            {}
        );
    });
    let dump = tree.dump();
    assert!(dump.contains("m23=ObserveSize"), "{dump}");
    assert!(dump.contains("m35=Sticky"), "{dump}");
    assert!(dump.contains("m37=UnboundedWidth"), "{dump}");
}
