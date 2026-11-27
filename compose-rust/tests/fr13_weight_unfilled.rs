//! Unfilled weight (`Modifier::weight_unfilled(f32)`, wire tag 48) corresponds to Compose
//! `Modifier.weight(w, fill = false)`: a child takes at most its weighted share without
//! being forced to expand when its intrinsic size is smaller.

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

fn wire(modifier: compose_rust::Modifier) -> (u16, u16, Vec<Mutation<'static>>) {
    let expected = Mutation::SetModifier {
        node_id: 2,
        index: 0,
        modifier,
    };
    let mut encoder = BatchEncoder::default();
    encoder.encode(&expected).unwrap();
    let bytes: &'static [u8] =
        std::boxed::Box::leak(encoder.finish().unwrap().to_vec().into_boxed_slice());
    let tag = u16::from_le_bytes([bytes[22], bytes[23]]);
    let len = u16::from_le_bytes([bytes[14], bytes[15]]);
    let decoded = decode_batch(bytes).unwrap();
    assert_eq!(decoded, [expected]);
    (tag, len, decoded)
}

#[test]
fn fr13_weight_unfilled_round_trips() {
    let schema = MODIFIER_SCHEMA
        .iter()
        .find(|variant| variant.name == "WeightUnfilled")
        .expect("the schema names WeightUnfilled");
    assert_eq!(schema.tag, 48);
    assert_eq!(schema.extra_words, 0);

    let (unfilled_tag, unfilled_len, unfilled_decoded) =
        wire(compose_rust::Modifier::WeightUnfilled(1.0));
    let (filled_tag, filled_len, filled_decoded) = wire(compose_rust::Modifier::Weight(1.0));

    assert_eq!(unfilled_tag, 48);
    assert_eq!(filled_tag, 11);
    assert_eq!(unfilled_len, filled_len);
    assert_ne!(unfilled_decoded, filled_decoded);
}

#[test]
fn fr13_modifier_weight_unfilled_builder() {
    let (_host, tree) = start(|| {
        Row!({
            Text!("short", modifier = Modifier::new().weight_unfilled(1.0));
            Box!(modifier = Modifier::new().weight(1.0), {});
        });
    });
    let dump = tree.dump();
    assert!(dump.contains("m0=WeightUnfilled(1.0)"), "{dump}");
    assert!(dump.contains("m0=Weight(1.0)"), "{dump}");

    // Last call wins in either order because both share the weight slot.
    let (_host, tree) = start(|| {
        Box!(
            modifier = Modifier::new().weight(1.0).weight_unfilled(2.5),
            {}
        );
    });
    let dump = tree.dump();
    assert!(dump.contains("m0=WeightUnfilled(2.5)"), "{dump}");
    assert!(!dump.contains("m0=Weight("), "{dump}");

    let (_host, tree) = start(|| {
        Box!(
            modifier = Modifier::new().weight_unfilled(2.5).weight(1.5),
            {}
        );
    });
    let dump = tree.dump();
    assert!(dump.contains("m0=Weight(1.5)"), "{dump}");
    assert!(!dump.contains("m0=WeightUnfilled("), "{dump}");
}
