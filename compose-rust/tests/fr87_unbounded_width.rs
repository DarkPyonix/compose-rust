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
        index: 35,
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
        Box().modifier(Modifier.unbounded_width()).content(|| {});
    });
    let dump = tree.dump();
    assert!(dump.contains("UnboundedWidth"), "{dump}");

    let (_host, tree) = start(|| {
        Box()
            .modifier(Modifier.wrap_content_width(true))
            .content(|| {});
    });
    let dump = tree.dump();
    assert!(dump.contains("UnboundedWidth"), "{dump}");

    let (_host, tree) = start(|| {
        Box()
            .modifier(Modifier.wrap_content_width(false))
            .content(|| {});
    });
    let dump = tree.dump();
    assert!(!dump.contains("UnboundedWidth"), "{dump}");
}
