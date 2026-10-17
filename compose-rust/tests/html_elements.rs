//! The elements an HTML and CSS screen is drawn with, on the wire.
//!
//! A box whose children sit where the Host put them, and seven modifiers. Two of the
//! modifiers do not fit the two words a `SetModifier` record has always carried, so the
//! record's length now follows from the modifier's tag. Every modifier that existed before
//! keeps its 28 bytes exactly.

use compose_rust::protocol::{BatchEncoder, Mutation, ProtocolError, decode_batch};
use compose_rust::schema::{MODIFIER_SCHEMA, modifier_extra_words};
use compose_rust::{
    Color, ColorRole, MaterialRole, Modifier, MotionRole, Paint, ShapeRole, SpaceRole, WidgetKind,
};

fn encode(mutations: &[Mutation<'_>]) -> Vec<u8> {
    let mut encoder = BatchEncoder::default();
    for mutation in mutations {
        encoder.encode(mutation).unwrap();
    }
    encoder.finish().unwrap().to_vec()
}

fn set_modifier(modifier: Modifier) -> Mutation<'static> {
    Mutation::SetModifier {
        node_id: 7,
        index: 3,
        modifier,
    }
}

/// A batch envelope for `records_len` bytes of records holding `count` of them.
fn envelope(records_len: u32, count: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(&0_u16.to_le_bytes());
    bytes.extend_from_slice(&12_u16.to_le_bytes());
    bytes.extend_from_slice(&records_len.to_le_bytes());
    bytes.extend_from_slice(&count.to_le_bytes());
    bytes
}

/// A `SetModifier` record of `len` bytes for `tag`, with `words` as its value.
fn raw_set_modifier(len: u16, tag: u16, words: &[u64]) -> Vec<u8> {
    let mut bytes = envelope(12 + u32::from(len), 1);
    bytes.extend_from_slice(&3_u16.to_le_bytes());
    bytes.extend_from_slice(&len.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    bytes.extend_from_slice(&0_u16.to_le_bytes());
    bytes.extend_from_slice(&tag.to_le_bytes());
    for word in words {
        bytes.extend_from_slice(&word.to_le_bytes());
    }
    bytes.resize(12 + usize::from(len), 0);
    bytes
}

/// One value of every modifier in the schema, the new ones with values that differ in
/// every field so a field read from the wrong word cannot pass.
fn every_modifier() -> Vec<Modifier> {
    vec![
        Modifier::Empty,
        Modifier::Padding(16.0),
        Modifier::FillMaxWidth,
        Modifier::FillMaxHeight,
        Modifier::Width(120.0),
        Modifier::Height(48.0),
        Modifier::Size {
            width: 20.0,
            height: 30.0,
        },
        Modifier::Background(Paint::Literal(Color::argb(0xff11_2233))),
        Modifier::Clickable { handler_id: 42 },
        Modifier::PaddingRole(SpaceRole::Md),
        Modifier::PaddingEach {
            start: 1.0,
            top: 2.0,
            end: 3.0,
            bottom: 4.0,
        },
        Modifier::Weight(0.5),
        Modifier::Shape {
            top_start: 4.0,
            top_end: 8.0,
            bottom_end: 12.0,
            bottom_start: 16.0,
        },
        Modifier::ShapeRole(ShapeRole::Large),
        Modifier::Border {
            width: 2.0,
            paint: Paint::Role(ColorRole::Outline),
        },
        Modifier::Elevation(6.0),
        Modifier::ObserveSize { token: 9 },
        Modifier::Motion(MotionRole::Standard),
        Modifier::Material(MaterialRole::Regular),
        Modifier::Offset { x: 12.5, y: -4.0 },
        Modifier::RequiredSize {
            width: 320.0,
            height: 180.0,
        },
        Modifier::BorderEach {
            top: 1.0,
            right: 2.0,
            bottom: 3.0,
            left: 4.0,
            top_paint: Paint::Literal(Color::argb(0xff11_2233)),
            right_paint: Paint::Role(ColorRole::Outline),
            bottom_paint: Paint::Literal(Color::argb(0x8044_5566)),
            left_paint: Paint::Asset(17),
        },
        Modifier::CornerEach {
            top_left: 4.0,
            top_right: 8.0,
            bottom_right: 12.0,
            bottom_left: 16.0,
        },
        Modifier::Shadow {
            x: 1.0,
            y: 2.0,
            blur: 6.0,
            spread: -1.0,
            paint: Paint::Literal(Color::argb(0x4000_0000)),
        },
        Modifier::Clip(true),
        Modifier::Clip(false),
        Modifier::Alpha(0.5),
    ]
}

fn tag_of(modifier: &Modifier) -> u16 {
    let bytes = encode(&[set_modifier(modifier.clone())]);
    u16::from_le_bytes([bytes[22], bytes[23]])
}

/// Each new modifier round-trips, and its record is as long as its tag says: 28 bytes for
/// a value of two words, 36 for the shadow's three and 60 for the border's six.
#[test]
fn fr42_every_modifier_round_trips_at_the_length_its_tag_fixes() {
    let modifiers = every_modifier();
    let mut covered = std::collections::BTreeSet::new();
    for modifier in &modifiers {
        let bytes = encode(&[set_modifier(modifier.clone())]);
        let tag = tag_of(modifier);
        covered.insert(tag);
        let extra = modifier_extra_words(tag).expect("every encoded tag is in the schema");
        let len = usize::from(u16::from_le_bytes([bytes[14], bytes[15]]));
        assert_eq!(
            len,
            28 + 8 * usize::from(extra),
            "{modifier:?} was written as a {len} byte record"
        );
        assert_eq!(bytes.len(), 12 + len);
        assert_eq!(
            decode_batch(&bytes),
            Ok(vec![set_modifier(modifier.clone())]),
            "{modifier:?} did not decode back to itself"
        );
    }
    let schema: std::collections::BTreeSet<u16> =
        MODIFIER_SCHEMA.iter().map(|variant| variant.tag).collect();
    assert_eq!(covered, schema, "every modifier in the schema is exercised");

    for (tag, len) in [
        (19, 28),
        (20, 28),
        (21, 60),
        (22, 28),
        (23, 36),
        (24, 28),
        (25, 28),
    ] {
        assert_eq!(
            28 + 8 * usize::from(modifier_extra_words(tag).unwrap()),
            len,
            "modifier {tag} has the wrong record length in the schema"
        );
    }
}

/// The new modifiers can sit beside each other and beside old ones in one batch, and a
/// longer record does not move the one after it.
#[test]
fn fr42_long_and_short_modifier_records_follow_each_other_in_one_batch() {
    let mut mutations = vec![Mutation::Create {
        node_id: 7,
        widget: WidgetKind::AbsoluteBox,
    }];
    mutations.extend(every_modifier().into_iter().map(set_modifier));
    mutations.push(Mutation::Remove { node_id: 9 });
    let bytes = encode(&mutations);
    assert_eq!(decode_batch(&bytes), Ok(mutations));
}

/// The bytes of every modifier record that existed before, as they were checked in before
/// the length of a record could depend on its tag.
const RECORDS_BEFORE: [&str; 17] = [
    "03001c00010000000000000000000000000000000000000000000000",
    "03001c00010000000100010000008041000000000000000000000000",
    "03001c00010000000200020000000000000000000000000000000000",
    "03001c00010000000300030000000000000000000000000000000000",
    "03001c0001000000040004000000f042000000000000000000000000",
    "03001c00010000000500050000004042000000000000000000000000",
    "03001c0001000000060006000000a041000000000000f04100000000",
    "03001c000100000007000700332211ff020000000000000000000000",
    "03001c0001000000080008002a000000000000000000000000000000",
    "03001c00010000000900070005000000010000000000000000000000",
    "03001c00010000000a00090004000000000000000000000000000000",
    "03001c00010000000b000a000000803f000000400000404000008040",
    "03001c00010000000c000b000000003f000000000000000000000000",
    "03001c00010000000d000c0000008040000000410000404100008041",
    "03001c00010000000e000d0005000000000000000000000000000000",
    "03001c00010000000f000e0000000040000000000b00000001000000",
    "03001c000100000010000f000000c040000000000000000000000000",
];

fn hex(text: &str) -> Vec<u8> {
    (0..text.len())
        .step_by(2)
        .map(|at| u8::from_str_radix(&text[at..at + 2], 16).unwrap())
        .collect()
}

/// The older modifiers are byte for byte what they were: the checked-in vector holds them
/// where it always did, and encoding them again writes the same bytes.
#[test]
fn fr41_existing_modifier_vectors_are_unchanged() {
    let before: Vec<u8> = RECORDS_BEFORE
        .iter()
        .flat_map(|record| hex(record))
        .collect();
    let vector = include_bytes!("vectors/mutations.bin");
    assert_eq!(
        &vector[200..200 + before.len()],
        before.as_slice(),
        "the modifier records in the checked-in vector moved or changed"
    );

    // The seventeen the vector writes, in its order, each at its own index on node 1.
    let mut modifiers = every_modifier();
    modifiers.truncate(16);
    modifiers.insert(9, Modifier::Background(Paint::Role(ColorRole::Surface)));
    assert_eq!(modifiers.len(), RECORDS_BEFORE.len());
    for (index, (modifier, expected)) in modifiers.iter().zip(RECORDS_BEFORE).enumerate() {
        let bytes = encode(&[Mutation::SetModifier {
            node_id: 1,
            index: index as u16,
            modifier: modifier.clone(),
        }]);
        assert_eq!(&bytes[12..], hex(expected).as_slice(), "{modifier:?}");
    }
}

/// A record whose length is not the one its modifier's tag fixes is a protocol error,
/// whichever way it is wrong, and so is a tag the schema does not list.
#[test]
fn fr42_a_modifier_record_of_the_wrong_length_is_a_protocol_error() {
    assert_eq!(
        decode_batch(&raw_set_modifier(28, 21, &[0, 0])),
        Err(ProtocolError::InvalidRecordLength),
        "a border cut down to 28 bytes"
    );
    assert_eq!(
        decode_batch(&raw_set_modifier(28, 23, &[0, 0])),
        Err(ProtocolError::InvalidRecordLength),
        "a shadow cut down to 28 bytes"
    );
    assert_eq!(
        decode_batch(&raw_set_modifier(36, 1, &[0, 0, 0])),
        Err(ProtocolError::InvalidRecordLength),
        "a padding grown to 36 bytes"
    );
    assert_eq!(
        decode_batch(&raw_set_modifier(24, 1, &[0, 0])),
        Err(ProtocolError::InvalidRecordLength),
        "a record too short to hold a value"
    );
    assert_eq!(
        decode_batch(&raw_set_modifier(36, 999, &[0, 0, 0])),
        Err(ProtocolError::InvalidModifier(999)),
        "a tag the schema does not list"
    );
}

/// A clip is on or off. A third value is a record the two sides disagree about, not "on".
#[test]
fn fr42_a_clip_that_is_neither_on_nor_off_is_a_protocol_error() {
    assert_eq!(
        decode_batch(&raw_set_modifier(28, 24, &[2, 0])),
        Err(ProtocolError::InvalidModifier(24))
    );
}

/// The box that places its children where the Host put them has the tag the schema gives it.
#[test]
fn fr42_absolute_box_is_widget_44() {
    assert_eq!(WidgetKind::AbsoluteBox as u16, 44);
    let bytes = encode(&[Mutation::Create {
        node_id: 3,
        widget: WidgetKind::AbsoluteBox,
    }]);
    assert_eq!(
        decode_batch(&bytes),
        Ok(vec![Mutation::Create {
            node_id: 3,
            widget: WidgetKind::AbsoluteBox
        }])
    );
}

/// Four equal sides and one radius go out as the modifiers every ordinary node already
/// sends. The new records are written only where the values differ.
#[test]
fn fr42_uniform_borders_and_radii_use_the_existing_modifiers() {
    let outline = Paint::Role(ColorRole::Outline);
    assert_eq!(
        Modifier::border_sides([2.0; 4], [outline; 4]),
        Modifier::Border {
            width: 2.0,
            paint: outline,
        }
    );
    assert_eq!(
        Modifier::border_sides([2.0, 2.0, 3.0, 2.0], [outline; 4]),
        Modifier::BorderEach {
            top: 2.0,
            right: 2.0,
            bottom: 3.0,
            left: 2.0,
            top_paint: outline,
            right_paint: outline,
            bottom_paint: outline,
            left_paint: outline,
        }
    );
    let red = Paint::Literal(Color::rgb(0xff0000));
    assert!(matches!(
        Modifier::border_sides([2.0; 4], [outline, outline, red, outline]),
        Modifier::BorderEach { .. }
    ));

    assert_eq!(
        Modifier::corner_radii([6.0; 4]),
        Modifier::Shape {
            top_start: 6.0,
            top_end: 6.0,
            bottom_end: 6.0,
            bottom_start: 6.0,
        }
    );
    assert_eq!(
        Modifier::corner_radii([6.0, 0.0, 6.0, 0.0]),
        Modifier::CornerEach {
            top_left: 6.0,
            top_right: 0.0,
            bottom_right: 6.0,
            bottom_left: 0.0,
        }
    );
}

/// Changing one modifier of one node is one record: the node's `SetModifier` at that
/// index, and the record is the same length it was.
#[test]
fn fr42_changing_one_modifier_is_one_record() {
    let before = Modifier::Offset { x: 10.0, y: 20.0 };
    let after = Modifier::Offset { x: 10.0, y: 64.0 };
    let bytes = encode(&[Mutation::SetModifier {
        node_id: 4,
        index: 0,
        modifier: after.clone(),
    }]);
    let decoded = decode_batch(&bytes).unwrap();
    assert_eq!(decoded.len(), 1);
    assert_eq!(
        encode(&[set_modifier(before)]).len(),
        encode(&[set_modifier(after)]).len()
    );
}
