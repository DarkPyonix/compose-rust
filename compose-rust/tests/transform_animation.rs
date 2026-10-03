//! CSS transforms on the wire.

use compose_rust::Modifier;
use compose_rust::protocol::{BatchEncoder, Mutation, ProtocolError, decode_batch};

fn encode(mutations: &[Mutation<'_>]) -> Vec<u8> {
    let mut encoder = BatchEncoder::default();
    for mutation in mutations {
        encoder.encode(mutation).unwrap();
    }
    encoder.finish().unwrap().to_vec()
}

fn transform(values: [f32; 8]) -> Modifier {
    let [a, b, c, d, e, f, origin_x, origin_y] = values;
    Modifier::Transform {
        a,
        b,
        c,
        d,
        e,
        f,
        origin_x,
        origin_y,
    }
}

/// A transform is one 44 byte record: the 28 every modifier has and two more words, laid
/// out as the linear part, the translation and the origin, two `f32` to a word with the
/// first in the low half. The checked-in vector carries the same bytes.
#[test]
fn fr41_transform_round_trips() {
    let modifier = transform([0.75, 0.5, -0.25, 1.25, 12.0, -6.0, 0.5, 0.25]);
    let record = Mutation::SetModifier {
        node_id: 8,
        index: 7,
        modifier,
    };
    let bytes = encode(std::slice::from_ref(&record));
    assert_eq!(bytes.len(), 12 + 44);
    let body = &bytes[12..];
    assert_eq!(&body[0..4], &[3, 0, 44, 0], "tag 3, length 44");
    assert_eq!(
        u16::from_le_bytes([body[10], body[11]]),
        26,
        "modifier tag 26"
    );
    let floats: Vec<f32> = body[12..44]
        .chunks(4)
        .map(|chunk| f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]))
        .collect();
    assert_eq!(floats, [0.75, 0.5, -0.25, 1.25, 12.0, -6.0, 0.5, 0.25]);
    assert_eq!(decode_batch(&bytes), Ok(vec![record]));

    let vector = include_bytes!("vectors/mutations.bin");
    assert!(
        vector.windows(body.len()).any(|window| window == body),
        "the checked-in vector carries the same transform record"
    );
}

/// A matrix or an origin that is not a number is a protocol error, and so is a transform
/// record cut to the 28 bytes an older modifier has.
#[test]
fn fr41_a_transform_that_is_not_a_number_or_the_wrong_length_is_a_protocol_error() {
    for bad in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
        for field in 0..8 {
            let mut values = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.5, 0.5];
            values[field] = bad;
            let bytes = encode(&[Mutation::SetModifier {
                node_id: 1,
                index: 0,
                modifier: transform(values),
            }]);
            assert_eq!(
                decode_batch(&bytes),
                Err(ProtocolError::InvalidModifier(26)),
                "{bad} in field {field}"
            );
        }
    }

    let mut bytes = encode(&[Mutation::SetModifier {
        node_id: 1,
        index: 0,
        modifier: transform([1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.5, 0.5]),
    }]);
    // Shorten the record to 28 bytes and the batch with it.
    bytes.truncate(12 + 28);
    bytes[4..8].copy_from_slice(&40_u32.to_le_bytes());
    bytes[14..16].copy_from_slice(&28_u16.to_le_bytes());
    assert_eq!(
        decode_batch(&bytes),
        Err(ProtocolError::InvalidRecordLength)
    );
}
