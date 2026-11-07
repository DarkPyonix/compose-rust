//! CSS 3D transforms on the wire.

use compose_rust::Modifier;
use compose_rust::codegen::TRANSFORM_3D_VECTOR_MATRIX;
use compose_rust::protocol::{BatchEncoder, Mutation, ProtocolError, decode_batch};

fn encode(mutations: &[Mutation<'_>]) -> Vec<u8> {
    let mut encoder = BatchEncoder::default();
    for mutation in mutations {
        encoder.encode(mutation).unwrap();
    }
    encoder.finish().unwrap().to_vec()
}

fn transform_3d(m: [f32; 16], origin_x: f32, origin_y: f32, backface_hidden: bool) -> Modifier {
    Modifier::Transform3D {
        m,
        origin_x,
        origin_y,
        backface_hidden,
    }
}

const IDENTITY: [f32; 16] = [
    1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
];

/// A 3D transform is one 92 byte record: the 28 every modifier has and eight more words.
/// The sixteen matrix values come first in column-major order, two `f32` to a word with
/// the first in the low half, then the origin, then a whole word for the backface flag.
/// The checked-in vector carries the same bytes and the main vector is not touched.
#[test]
fn fr41_3_transform3d_round_trips() {
    let modifier = transform_3d(TRANSFORM_3D_VECTOR_MATRIX, 0.5, 0.25, true);
    let record = Mutation::SetModifier {
        node_id: 3,
        index: 0,
        modifier,
    };
    let bytes = encode(std::slice::from_ref(&record));
    assert_eq!(bytes.len(), 12 + 92);
    let body = &bytes[12..];
    assert_eq!(&body[0..4], &[3, 0, 92, 0], "tag 3, length 92");
    assert_eq!(
        u16::from_le_bytes([body[10], body[11]]),
        27,
        "modifier tag 27"
    );
    let floats: Vec<f32> = body[12..84]
        .chunks(4)
        .map(|chunk| f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]))
        .collect();
    assert_eq!(&floats[..16], &TRANSFORM_3D_VECTOR_MATRIX, "column-major");
    assert_eq!(&floats[16..], &[0.5, 0.25], "origin at offsets 76 and 80");
    assert_eq!(
        u64::from_le_bytes(body[84..92].try_into().unwrap()),
        1,
        "backface_hidden is a whole word at offset 84"
    );
    assert_eq!(decode_batch(&bytes), Ok(vec![record]));

    let vector = include_bytes!("vectors/transform3d.bin");
    assert_eq!(vector.as_slice(), bytes.as_slice(), "the checked-in vector");

    let visible = encode(&[Mutation::SetModifier {
        node_id: 3,
        index: 0,
        modifier: transform_3d(IDENTITY, 0.5, 0.5, false),
    }]);
    assert_eq!(&visible[12 + 84..], &[0; 8], "backface visible is zero");
}

/// A matrix or an origin that is not a number is a protocol error, and so are a backface
/// flag that is neither 0 nor 1 and a record whose length is not the one the tag fixes.
#[test]
fn fr41_3_non_finite_is_a_protocol_error() {
    for bad in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
        for field in 0..18 {
            let mut m = IDENTITY;
            let (mut origin_x, mut origin_y) = (0.5, 0.5);
            match field {
                16 => origin_x = bad,
                17 => origin_y = bad,
                _ => m[field] = bad,
            }
            let bytes = encode(&[Mutation::SetModifier {
                node_id: 1,
                index: 0,
                modifier: transform_3d(m, origin_x, origin_y, false),
            }]);
            assert_eq!(
                decode_batch(&bytes),
                Err(ProtocolError::InvalidModifier(27)),
                "{bad} in field {field}"
            );
        }
    }

    let mut bytes = encode(&[Mutation::SetModifier {
        node_id: 1,
        index: 0,
        modifier: transform_3d(IDENTITY, 0.5, 0.5, false),
    }]);
    bytes[12 + 84..12 + 92].copy_from_slice(&2_u64.to_le_bytes());
    assert_eq!(
        decode_batch(&bytes),
        Err(ProtocolError::InvalidModifier(27)),
        "a flag of 2"
    );

    let mut bytes = encode(&[Mutation::SetModifier {
        node_id: 1,
        index: 0,
        modifier: transform_3d(IDENTITY, 0.5, 0.5, false),
    }]);
    // Cut the record to the 44 bytes of the 2D transform and the batch with it.
    bytes.truncate(12 + 44);
    bytes[4..8].copy_from_slice(&56_u32.to_le_bytes());
    bytes[14..16].copy_from_slice(&44_u16.to_le_bytes());
    assert_eq!(
        decode_batch(&bytes),
        Err(ProtocolError::InvalidRecordLength)
    );
}
