//! A node's size report, and the opt-in that also reports a resize inside one class.

mod support;

use compose_rust::protocol::{BatchEncoder, Mutation, decode_batch};
use compose_rust::ui::*;
use compose_rust::{ComposeHost, Modifier as WireModifier};
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

fn record(modifier: WireModifier) -> Vec<u8> {
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetModifier {
            node_id: 1,
            index: 3,
            modifier,
        })
        .unwrap();
    encoder.finish().unwrap()[12..].to_vec()
}

/// With the flag off, the record is the one a size report always was: tag 16, the token in
/// the first word and a second word of zero. A Renderer that predates the flag reads it as
/// it always did.
#[test]
fn fr28_every_dp_off_by_default_keeps_the_record_bytes() {
    let bytes = record(WireModifier::ObserveSize {
        token: 9,
        every_dp: false,
    });
    let mut expected = Vec::new();
    expected.extend_from_slice(&3_u16.to_le_bytes());
    expected.extend_from_slice(&28_u16.to_le_bytes());
    expected.extend_from_slice(&1_u32.to_le_bytes());
    expected.extend_from_slice(&3_u16.to_le_bytes());
    expected.extend_from_slice(&16_u16.to_le_bytes());
    expected.extend_from_slice(&9_u64.to_le_bytes());
    expected.extend_from_slice(&0_u64.to_le_bytes());
    assert_eq!(bytes, expected);

    let on = record(WireModifier::ObserveSize {
        token: 9,
        every_dp: true,
    });
    assert_eq!(on[..20], expected[..20], "only the second word differs");
    assert_eq!(on[20..], 1_u64.to_le_bytes());
}

/// Both values decode back to themselves, and a second word that is neither 0 nor 1 is a
/// record the two sides disagree about.
#[test]
fn fr28_every_dp_round_trips_and_refuses_a_bad_flag() {
    for every_dp in [false, true] {
        let mutation = Mutation::SetModifier {
            node_id: 1,
            index: 3,
            modifier: WireModifier::ObserveSize { token: 9, every_dp },
        };
        let mut encoder = BatchEncoder::default();
        encoder.encode(&mutation).unwrap();
        let bytes = encoder.finish().unwrap().to_vec();
        assert_eq!(decode_batch(&bytes), Ok(vec![mutation]));
    }
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetModifier {
            node_id: 1,
            index: 3,
            modifier: WireModifier::ObserveSize {
                token: 9,
                every_dp: true,
            },
        })
        .unwrap();
    let mut bytes = encoder.finish().unwrap().to_vec();
    let last = bytes.len() - 8;
    bytes[last] = 2;
    assert!(decode_batch(&bytes).is_err());
}

/// The builder: `observe_size` sends the flag off, `observe_size_every_dp` sends it on.
#[test]
fn fr28_the_builder_sends_every_dp_only_when_asked() {
    let (_host, tree) = start(|| {
        Column().modifier(Modifier.observe_size(9)).content(|| {});
    });
    let dump = tree.dump();
    assert!(
        dump.contains("ObserveSize { token: 9, every_dp: false }"),
        "{dump}"
    );

    let (_host, tree) = start(|| {
        Column()
            .modifier(Modifier.observe_size_every_dp(9))
            .content(|| {});
    });
    let dump = tree.dump();
    assert!(
        dump.contains("ObserveSize { token: 9, every_dp: true }"),
        "{dump}"
    );
    assert_eq!(dump.matches("ObserveSize").count(), 1, "{dump}");
}
