//! A node's least touch target reaches the wire through the app-facing Modifier.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{BatchEncoder, Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::ui::*;
use support::Tree;

#[Composable]
fn Buttons() {
    Row().content(|| {
        Button("")
            .icon(IconRole::Add)
            .modifier(Modifier.size(26.0, 32.0).min_touch_target(44.0))
            .on_click(|| {});
        Button("")
            .icon(IconRole::Close)
            .modifier(Modifier.size(26.0, 32.0))
            .on_click(|| {});
    });
}

/// `min_touch_target` is sent as modifier tag 36 carrying its size, only on the node that
/// asked for it, and it survives the wire.
#[test]
fn fr55_min_touch_target_grows_the_hit_area_only() {
    let mut host = ComposeHost::with_content(Buttons);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    let dump = tree.dump();
    let buttons: Vec<_> = dump
        .lines()
        .filter(|line| line.trim_start().starts_with("Button"))
        .collect();
    assert_eq!(buttons.len(), 2, "{dump}");
    assert!(
        buttons[0].contains("MinTouchTarget { size: 44.0 }"),
        "{dump}"
    );
    assert!(!buttons[1].contains("MinTouchTarget"), "{dump}");

    let expected = Mutation::SetModifier {
        node_id: 3,
        index: 27,
        modifier: compose_rust::Modifier::MinTouchTarget { size: 44.0 },
    };
    let mut encoder = BatchEncoder::default();
    encoder.encode(&expected).unwrap();
    let bytes = encoder.finish().unwrap().to_vec();
    assert_eq!(u16::from_le_bytes([bytes[22], bytes[23]]), 36);
    assert_eq!(decode_batch(&bytes).unwrap(), [expected]);
}
