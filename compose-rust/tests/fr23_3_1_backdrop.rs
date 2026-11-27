//! `Backdrop`, the blur an application chooses for itself, as it reaches the wire: the
//! modifier's tag and words, the values the decoder refuses, and the app-facing Modifier.
//!
//! What is blurred and what is drawn where nothing can be is the Renderer's, and is tested
//! on its side.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{BatchEncoder, Mutation, ProtocolError, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{FieldSlot, FieldType, MODIFIER_SCHEMA, modifier_extra_words};
use compose_rust::ui::*;
use support::Tree;

const MUTATIONS: &[u8] = include_bytes!("vectors/mutations.bin");

/// White at 40%: the tint of the vector's record.
const FROST: Paint = Paint::Literal(Color::argb(0x66ff_ffff));

/// Where the first value word of a lone `SetModifier` record sits in its batch: after the
/// 12 byte envelope and the record's own 12 byte head.
const FIRST_WORD: usize = 24;

fn backdrop(blur_dp: f32, saturation: f32, tint: Paint) -> compose_rust::Modifier {
    compose_rust::Modifier::Backdrop {
        blur_dp,
        saturation,
        tint,
    }
}

fn encode(modifier: compose_rust::Modifier) -> Vec<u8> {
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetModifier {
            node_id: 7,
            index: 3,
            modifier,
        })
        .unwrap();
    encoder.finish().unwrap().to_vec()
}

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

/// The modifier is tag 47 with a value of two words: the blur and the saturation share the
/// first and the tint is the second. It comes back as it went, the checked-in vector
/// carries one, and a blur or a saturation that is negative or not a number is refused.
#[test]
fn fr23_3_1_backdrop_round_trips() {
    let schema = MODIFIER_SCHEMA
        .iter()
        .find(|variant| variant.name == "Backdrop")
        .expect("the schema names Backdrop");
    assert_eq!(schema.tag, 47);
    assert_eq!(schema.extra_words, 0);
    assert_eq!(modifier_extra_words(47), Some(0));
    let fields: Vec<_> = schema
        .fields
        .iter()
        .map(|field| (field.name, field.ty, field.slot))
        .collect();
    assert_eq!(
        fields,
        [
            ("blurDp", FieldType::Float, FieldSlot::FirstLow),
            ("saturation", FieldType::Float, FieldSlot::FirstHigh),
            ("tint", FieldType::Paint, FieldSlot::Second),
        ]
    );

    let bytes = encode(backdrop(20.0, 1.8, FROST));
    // A 28 byte record: the head, the node, the index and the tag, then two words.
    assert_eq!(bytes.len(), 12 + 28);
    assert_eq!(&bytes[22..24], &47_u16.to_le_bytes());
    assert_eq!(&bytes[24..28], &20.0_f32.to_le_bytes());
    assert_eq!(&bytes[28..32], &1.8_f32.to_le_bytes());
    assert_eq!(&bytes[32..40], &FROST.to_bits().to_le_bytes());
    assert_eq!(
        decode_batch(&bytes),
        Ok(vec![Mutation::SetModifier {
            node_id: 7,
            index: 3,
            modifier: backdrop(20.0, 1.8, FROST),
        }])
    );
    // A role tint, and the two values at zero, which are drawable: no blur, no colour.
    let plain = backdrop(0.0, 0.0, Paint::Role(ColorRole::Surface));
    assert_eq!(
        decode_batch(&encode(plain.clone())),
        Ok(vec![Mutation::SetModifier {
            node_id: 7,
            index: 3,
            modifier: plain,
        }])
    );

    assert!(
        decode_batch(MUTATIONS)
            .expect("the mutation vector decodes")
            .contains(&Mutation::SetModifier {
                node_id: 26,
                index: 0,
                modifier: backdrop(20.0, 1.8, FROST),
            })
    );

    for bad in [-1.0_f32, f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
        for at in [FIRST_WORD, FIRST_WORD + 4] {
            let mut refused = bytes.clone();
            refused[at..at + 4].copy_from_slice(&bad.to_le_bytes());
            assert_eq!(
                decode_batch(&refused),
                Err(ProtocolError::InvalidModifier(47)),
                "{bad} at byte {at} was read as a value"
            );
        }
    }
    // A tint that is no paint at all.
    let mut no_paint = bytes.clone();
    no_paint[32..40].copy_from_slice(&0_u64.to_le_bytes());
    assert!(decode_batch(&no_paint).is_err());
}

/// `Modifier::backdrop` writes one `Backdrop` record with its values, a colour role or a
/// literal as the tint, and taking it away clears the record.
#[test]
fn fr23_3_1_modifier_backdrop_writes_the_record() {
    let on = mutable_state_of(true);
    let state = on;
    let (mut host, mut tree) = start(move || {
        let modifier = if state.get() {
            Modifier.backdrop(20.0, 1.0, FROST)
        } else {
            Modifier
        };
        Box!(modifier = modifier, {});
        Row!(
            modifier = Modifier.backdrop(8.0, 1.5, ColorRole::Surface),
            {}
        );
    });
    let dump = tree.dump();
    assert!(
        dump.contains(&format!("{:?}", backdrop(20.0, 1.0, FROST))),
        "{dump}"
    );
    assert!(
        dump.contains(&format!(
            "{:?}",
            backdrop(8.0, 1.5, Paint::Role(ColorRole::Surface))
        )),
        "{dump}"
    );

    on.set(false);
    tree.apply(host.render_frame(0).expect("the frame encodes"));
    let dump = tree.dump();
    assert!(
        !dump.contains(&format!("{:?}", backdrop(20.0, 1.0, FROST))),
        "{dump}"
    );
    assert!(dump.contains("Backdrop"), "the row keeps its own: {dump}");
}

/// A node with a material and a backdrop carries both records; which one is drawn is the
/// Renderer's to decide, and it draws the backdrop.
#[test]
fn fr23_3_1_material_and_backdrop_both_travel() {
    let (_host, tree) = start(|| {
        Box!(
            modifier = Modifier
                .material(MaterialRole::Regular)
                .backdrop(20.0, 1.0, FROST),
            {}
        );
    });
    let dump = tree.dump();
    assert!(dump.contains("Material"), "{dump}");
    assert!(dump.contains("Backdrop"), "{dump}");
}
