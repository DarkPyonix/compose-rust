//! Sticky child modifier in scrolling containers.

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
fn fr89_sticky_modifier_round_trips() {
    let schema = MODIFIER_SCHEMA
        .iter()
        .find(|variant| variant.name == "Sticky")
        .expect("the schema names Sticky");
    assert_eq!(schema.tag, 41);

    let expected = [Mutation::SetModifier {
        node_id: 3,
        index: 35,
        modifier: compose_rust::Modifier::Sticky,
    }];
    let mut encoder = BatchEncoder::default();
    encoder.encode(&expected[0]).unwrap();
    let bytes = encoder.finish().unwrap().to_vec();
    assert_eq!(decode_batch(&bytes).unwrap(), expected);
}

#[test]
fn fr89_modifier_sticky_builder() {
    let (_host, tree) = start(|| {
        Box!(modifier = Modifier.sticky(), {
            Text!("pinned");
        });
        Box!({
            Text!("scrolling");
        });
    });
    let dump = tree.dump();
    let boxes: Vec<&str> = dump
        .lines()
        .filter(|line| line.trim_start().starts_with("Box"))
        .collect();
    assert_eq!(boxes.len(), 2, "{dump}");
    assert!(boxes[0].contains("Sticky"), "{dump}");
    assert!(!boxes[1].contains("Sticky"), "{dump}");
}

#[test]
fn fr89_writer_maps_sticky_attribute() {
    let (_host, tree) = start(|| {
        ScrollRow!({
            Box!(modifier = Modifier.sticky(), {});
            Box!({});
        });
    });
    let dump = tree.dump();
    assert!(dump.contains("Sticky"), "{dump}");
}
