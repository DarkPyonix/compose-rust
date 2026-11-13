//! The width a split pane stacks below, on the wire.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{BatchEncoder, Mutation, PropertyValue, decode_batch};
use compose_rust::ui::*;
use compose_rust::{PropertyKind, WidgetKind};
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

#[test]
fn fr15_2_12_collapse_below_writes_its_property() {
    let (_host, tree) = start(|| {
        SplitPane().collapse_below(720.0).content(|| {
            Text("Sessions");
            Text("The conversation");
        });
    });
    let dump = tree.dump();
    assert!(dump.contains("CollapseBelow=Float(720.0)"), "{dump}");
}

#[test]
fn fr15_2_12_a_split_pane_without_collapse_below_sends_none() {
    let (_host, tree) = start(|| {
        SplitPane().content(|| {
            Text("Sessions");
            Text("The conversation");
        });
    });
    let dump = tree.dump();
    assert!(dump.contains("SplitPane"), "{dump}");
    assert!(!dump.contains("CollapseBelow"), "{dump}");
}

/// Property 91, the place after `Collapsible` in the split pane's block, carrying an f32
/// that both the encoder and the checked-in vector read back unchanged.
#[test]
fn fr15_2_12_collapse_below_round_trips() {
    assert_eq!(PropertyKind::CollapseBelow as u16, 91);
    let mutations = vec![
        Mutation::Create {
            node_id: 2,
            widget: WidgetKind::SplitPane,
        },
        Mutation::SetProp {
            node_id: 2,
            property: PropertyKind::CollapseBelow,
            value: PropertyValue::Float(720.5),
        },
    ];
    let mut encoder = BatchEncoder::default();
    for mutation in &mutations {
        encoder.encode(mutation).unwrap();
    }
    let bytes = encoder.finish().unwrap().to_vec();
    assert_eq!(decode_batch(&bytes), Ok(mutations));

    let vector = decode_batch(include_bytes!("vectors/mutations.bin")).unwrap();
    let in_vector: Vec<_> = vector
        .iter()
        .filter(|mutation| {
            matches!(
                mutation,
                Mutation::SetProp {
                    property: PropertyKind::CollapseBelow,
                    ..
                }
            )
        })
        .collect();
    assert_eq!(
        in_vector,
        vec![&Mutation::SetProp {
            node_id: 2,
            property: PropertyKind::CollapseBelow,
            value: PropertyValue::Float(720.5),
        }],
        "the checked-in vector is stale; run `cargo run -p compose-rust --bin codegen`"
    );
}
