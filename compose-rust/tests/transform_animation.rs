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

use compose_rust::protocol::{HostEvent, decode_event, encode_event};
use compose_rust::{
    AnimatedProperty, Animation, AnimationControl, AnimationEventKind, AnimationEvents, Color,
    ColorInterpolation, ColorRole, EventPayload, FillMode, Keyframe, KeyframeValue, Paint,
    PlayState, PlaybackDirection, ReducedMotion, StepPosition, Timing, TransformFunction,
};
use std::borrow::Cow;

fn animation(
    property: AnimatedProperty,
    values: Vec<KeyframeValue<'static>>,
) -> Animation<'static> {
    let count = values.len();
    let colour = matches!(
        property,
        AnimatedProperty::Color | AnimatedProperty::Background
    );
    Animation {
        node_id: 3,
        animation_id: 11,
        property,
        slot: 1,
        direction: PlaybackDirection::AlternateReverse,
        fill: FillMode::Forwards,
        play_state: PlayState::Paused,
        interpolation: colour.then_some(ColorInterpolation::Oklab),
        start_time_nanos: 42,
        delay_ms: -100.0,
        duration_ms: 500.0,
        iterations: f32::INFINITY,
        origin_x: if property == AnimatedProperty::Transform {
            0.5
        } else {
            0.0
        },
        origin_y: if property == AnimatedProperty::Transform {
            0.5
        } else {
            0.0
        },
        events: AnimationEvents::READY.union(AnimationEvents::ITERATION),
        keyframes: Cow::Owned(
            values
                .into_iter()
                .enumerate()
                .map(|(index, value)| Keyframe {
                    offset: index as f32 / (count - 1) as f32,
                    timing: match index % 3 {
                        0 => Timing::Linear,
                        1 => Timing::CubicBezier {
                            x1: 0.42,
                            y1: 0.0,
                            x2: 0.58,
                            y2: 1.0,
                        },
                        _ => Timing::Steps {
                            count: 3,
                            position: StepPosition::JumpEnd,
                        },
                    },
                    from_presented: index == 0,
                    value,
                })
                .collect(),
        ),
    }
}

fn functions(list: &[TransformFunction]) -> KeyframeValue<'static> {
    KeyframeValue::Transform(Cow::Owned(list.to_vec()))
}

fn every_animation() -> Vec<Animation<'static>> {
    let mut all = vec![
        animation(
            AnimatedProperty::Alpha,
            vec![
                KeyframeValue::Alpha(0.0),
                KeyframeValue::Alpha(0.5),
                KeyframeValue::Alpha(1.0),
            ],
        ),
        animation(
            AnimatedProperty::Color,
            vec![
                KeyframeValue::Paint(Paint::Role(ColorRole::Primary)),
                KeyframeValue::Paint(Paint::Literal(Color::argb(0x8011_2233))),
            ],
        ),
        animation(
            AnimatedProperty::Background,
            vec![
                KeyframeValue::Paint(Paint::Literal(Color::rgb(0x000000))),
                KeyframeValue::Paint(Paint::Role(ColorRole::Surface)),
            ],
        ),
    ];
    for (from, to) in [
        (
            TransformFunction::translate(0.0, 0.0),
            TransformFunction::translate(10.0, -4.0),
        ),
        (
            TransformFunction::rotate(0.0),
            TransformFunction::rotate(720.0),
        ),
        (
            TransformFunction::scale(1.0, 1.0),
            TransformFunction::scale(2.0, 0.5),
        ),
        (
            TransformFunction::skew(0.0, 0.0),
            TransformFunction::skew(10.0, 5.0),
        ),
        (
            TransformFunction::matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
            TransformFunction::matrix(0.5, 0.25, -0.25, 0.5, 3.0, 4.0),
        ),
    ] {
        all.push(animation(
            AnimatedProperty::Transform,
            vec![functions(&[from]), functions(&[to])],
        ));
    }
    all.push(animation(
        AnimatedProperty::Transform,
        vec![
            functions(&[
                TransformFunction::rotate(0.0),
                TransformFunction::translate(0.0, 0.0),
                TransformFunction::scale(1.0, 1.0),
            ]),
            functions(&[
                TransformFunction::rotate(360.0),
                TransformFunction::translate(100.0, 0.0),
                TransformFunction::scale(2.0, 2.0),
            ]),
        ],
    ));
    all
}

/// Every property, every transform function kind and a list of three, the control record,
/// the animation event and the motion setting all decode back to what was encoded, and the
/// checked-in vectors decode on this side as well.
#[test]
fn fr41_start_animation_round_trips() {
    for animation in every_animation() {
        let record = Mutation::StartAnimation(animation);
        let bytes = encode(std::slice::from_ref(&record));
        assert_eq!(decode_batch(&bytes), Ok(vec![record.clone()]), "{record:?}");
    }
    let control = Mutation::ControlAnimation {
        node_id: 3,
        animation_id: 11,
        property: AnimatedProperty::Transform,
        slot: 2,
        op: AnimationControl::Resume,
        at_time_nanos: 99,
    };
    let bytes = encode(std::slice::from_ref(&control));
    assert_eq!(bytes.len(), 12 + 24, "a control record is 24 bytes");
    assert_eq!(decode_batch(&bytes), Ok(vec![control]));

    for payload in [
        EventPayload::AnimationEvent {
            animation_id: 7,
            kind: AnimationEventKind::Iteration,
            property: AnimatedProperty::Alpha,
            slot: 3,
            iteration: 2,
            elapsed_ms: 1000.0,
            time_nanos: 5,
        },
        EventPayload::ReducedMotionChanged(ReducedMotion::Unknown),
    ] {
        let event = HostEvent {
            node_id: 4,
            handler_id: 0,
            payload,
        };
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        assert_eq!(decode_event(&bytes), Ok(event));
    }

    let mutations = decode_batch(include_bytes!("vectors/mutations.bin")).unwrap();
    let animations = mutations
        .iter()
        .filter(|mutation| matches!(mutation, Mutation::StartAnimation(_)))
        .count();
    assert_eq!(animations, 9, "the vector carries nine animations");
    assert!(
        mutations
            .iter()
            .any(|mutation| matches!(mutation, Mutation::ControlAnimation { .. }))
    );
    let events = include_bytes!("vectors/events.bin");
    assert!(matches!(
        decode_event(&events[282..322]).unwrap().payload,
        EventPayload::AnimationEvent {
            kind: AnimationEventKind::End,
            ..
        }
    ));
    assert_eq!(
        decode_event(&events[322..342]).unwrap().payload,
        EventPayload::ReducedMotionChanged(ReducedMotion::On)
    );
}

/// Records that are bytes but not an animation the Renderer could play are protocol errors.
#[test]
fn fr41_an_animation_that_cannot_be_played_is_a_protocol_error() {
    let base = || {
        animation(
            AnimatedProperty::Alpha,
            vec![KeyframeValue::Alpha(0.0), KeyframeValue::Alpha(1.0)],
        )
    };
    let mut cases: Vec<(&str, Animation<'static>)> = Vec::new();
    let mut one = base();
    one.keyframes.to_mut().truncate(1);
    cases.push(("one keyframe", one));
    let mut late_start = base();
    late_start.keyframes.to_mut()[0].offset = 0.1;
    cases.push(("first offset not 0", late_start));
    let mut early_end = base();
    early_end.keyframes.to_mut()[1].offset = 0.9;
    cases.push(("last offset not 1", early_end));
    let mut backwards = animation(
        AnimatedProperty::Alpha,
        vec![
            KeyframeValue::Alpha(0.0),
            KeyframeValue::Alpha(0.5),
            KeyframeValue::Alpha(1.0),
        ],
    );
    backwards.keyframes.to_mut()[1].offset = -0.5;
    cases.push(("an offset that goes back", backwards));
    let mut negative = base();
    negative.duration_ms = -1.0;
    cases.push(("a negative duration", negative));
    let mut nan = base();
    nan.iterations = f32::NAN;
    cases.push(("NaN iterations", nan));
    let mut fewer = base();
    fewer.iterations = -1.0;
    cases.push(("negative iterations", fewer));
    let mut mismatched = animation(
        AnimatedProperty::Transform,
        vec![
            functions(&[TransformFunction::rotate(0.0)]),
            functions(&[TransformFunction::scale(2.0, 2.0)]),
        ],
    );
    mismatched.origin_x = 0.5;
    cases.push(("transform lists that differ", mismatched));
    let mut wrong_value = base();
    wrong_value.keyframes.to_mut()[1].value = KeyframeValue::Paint(Paint::Role(ColorRole::Primary));
    cases.push(("a value of another property", wrong_value));
    for (what, animation) in cases {
        let bytes = encode(&[Mutation::StartAnimation(animation)]);
        assert_eq!(
            decode_batch(&bytes),
            Err(ProtocolError::InvalidAnimation),
            "{what} was accepted"
        );
    }

    // An unknown value, and a keyframe count that does not match the length.
    let mut bytes = encode(&[Mutation::StartAnimation(base())]);
    let mut unknown = bytes.clone();
    unknown[12 + 14] = 9;
    assert_eq!(
        decode_batch(&unknown),
        Err(ProtocolError::InvalidAnimation),
        "direction 9"
    );
    bytes[12 + 18] = 3;
    assert_eq!(
        decode_batch(&bytes),
        Err(ProtocolError::InvalidAnimation),
        "three keyframes counted, two sent"
    );
}
