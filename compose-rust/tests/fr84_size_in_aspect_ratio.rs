//! Layout constraint modifiers: size range and aspect ratio.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{BatchEncoder, Mutation, decode_batch};
use compose_rust::schema::Modifier as WireModifier;
use compose_rust::ui::*;
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

#[test]
fn fr84_size_in_and_aspect_ratio_round_trip() {
    let size_in = WireModifier::SizeIn {
        min_width: 10.0,
        max_width: 100.0,
        min_height: 20.0,
        max_height: 200.0,
    };
    let aspect_ratio = WireModifier::AspectRatio {
        ratio: 16.0 / 9.0,
        match_height_constraints_first: true,
    };

    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetModifier {
            node_id: 1,
            index: 0,
            modifier: size_in.clone(),
        })
        .unwrap();
    encoder
        .encode(&Mutation::SetModifier {
            node_id: 1,
            index: 1,
            modifier: aspect_ratio.clone(),
        })
        .unwrap();

    let batch = encoder.finish().unwrap();
    let decoded = decode_batch(batch).expect("batch decodes");
    assert_eq!(
        decoded,
        vec![
            Mutation::SetModifier {
                node_id: 1,
                index: 0,
                modifier: size_in,
            },
            Mutation::SetModifier {
                node_id: 1,
                index: 1,
                modifier: aspect_ratio,
            },
        ]
    );

    let size_in_nan = WireModifier::SizeIn {
        min_width: f32::NAN,
        max_width: 50.0,
        min_height: 30.0,
        max_height: f32::NAN,
    };
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetModifier {
            node_id: 2,
            index: 0,
            modifier: size_in_nan,
        })
        .unwrap();
    let batch = encoder.finish().unwrap();
    let decoded = decode_batch(batch).expect("batch with nan decodes");
    match &decoded[0] {
        Mutation::SetModifier {
            node_id: 2,
            index: 0,
            modifier:
                WireModifier::SizeIn {
                    min_width,
                    max_width,
                    min_height,
                    max_height,
                },
        } => {
            assert!(min_width.is_nan());
            assert_eq!(*max_width, 50.0);
            assert_eq!(*min_height, 30.0);
            assert!(max_height.is_nan());
        }
        other => panic!("expected SizeIn with NaN bounds, got {other:?}"),
    }
}

#[test]
fn fr84_modifier_size_in_builder_bounds() {
    let (_host, tree) = start(|| {
        Box()
            .modifier(Modifier.size_in(10.0, 100.0, 20.0, 200.0))
            .content(|| {});
    });
    let dump = tree.dump();
    assert!(
        dump.contains(
            "SizeIn { min_width: 10.0, max_width: 100.0, min_height: 20.0, max_height: 200.0 }"
        ),
        "{dump}"
    );

    let (_host, tree) = start(|| {
        Box()
            .modifier(Modifier.width_in(15.0, 80.0).height_in(25.0, 90.0))
            .content(|| {});
    });
    let dump = tree.dump();
    assert!(
        dump.contains(
            "SizeIn { min_width: 15.0, max_width: 80.0, min_height: 25.0, max_height: 90.0 }"
        ),
        "{dump}"
    );

    let (_host, tree) = start(|| {
        Box().modifier(Modifier.max_width(120.0)).content(|| {});
    });
    let dump = tree.dump();
    assert!(dump.contains("max_width: 120.0"), "{dump}");

    let (_host, tree) = start(|| {
        Box().modifier(Modifier.min_height(40.0)).content(|| {});
    });
    let dump = tree.dump();
    assert!(dump.contains("min_height: 40.0"), "{dump}");
}

#[test]
fn fr84_modifier_aspect_ratio_builder() {
    let (_host, tree) = start(|| {
        Box().modifier(Modifier.aspect_ratio(1.5)).content(|| {});
    });
    let dump = tree.dump();
    assert!(
        dump.contains("AspectRatio { ratio: 1.5, match_height_constraints_first: false }"),
        "{dump}"
    );

    let (_host, tree) = start(|| {
        Box()
            .modifier(Modifier.aspect_ratio_match_height(2.0, true))
            .content(|| {});
    });
    let dump = tree.dump();
    assert!(
        dump.contains("AspectRatio { ratio: 2.0, match_height_constraints_first: true }"),
        "{dump}"
    );
}
