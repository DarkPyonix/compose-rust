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

fn animation(property: AnimatedProperty, values: Vec<KeyframeValue>) -> Animation<'static> {
    let count = values.len();
    let colour = matches!(
        property,
        AnimatedProperty::Color | AnimatedProperty::Background | AnimatedProperty::BorderColor
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

fn functions(list: &[TransformFunction]) -> KeyframeValue {
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
        let event = HostEvent::new(4, 0, payload);
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        assert_eq!(decode_event(&bytes), Ok(event));
    }

    let mutations = decode_batch(include_bytes!("vectors/mutations.bin")).unwrap();
    let animations = mutations
        .iter()
        .filter(|mutation| matches!(mutation, Mutation::StartAnimation(_)))
        .count();
    assert_eq!(animations, 12, "the vector carries twelve animations");
    assert!(
        mutations
            .iter()
            .any(|mutation| matches!(mutation, Mutation::ControlAnimation { .. }))
    );
    let events = include_bytes!("vectors/events.bin");
    assert!(matches!(
        decode_event(&events[491..531]).unwrap().payload,
        EventPayload::AnimationEvent {
            kind: AnimationEventKind::End,
            ..
        }
    ));
    assert_eq!(
        decode_event(&events[531..551]).unwrap().payload,
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

/// A border's colour is one animation for the whole border: one record that names the
/// property, with a colour in each keyframe, mixed the way the record says. The checked-in
/// vector carries one, after every record that was there before it.
#[test]
fn fr41_border_color_animation_round_trips() {
    let record = Mutation::StartAnimation(animation(
        AnimatedProperty::BorderColor,
        vec![
            KeyframeValue::Paint(Paint::Role(ColorRole::Outline)),
            KeyframeValue::Paint(Paint::Literal(Color::argb(0xff33_6699))),
        ],
    ));
    let bytes = encode(std::slice::from_ref(&record));
    assert_eq!(decode_batch(&bytes), Ok(vec![record]));

    let mutations = decode_batch(include_bytes!("vectors/mutations.bin")).unwrap();
    // Records added later (a spring, a 3D matrix) come after it, so it is the last one of
    // its own property rather than the last animation.
    let last = mutations
        .iter()
        .rev()
        .find_map(|mutation| match mutation {
            Mutation::StartAnimation(animation)
                if animation.property == AnimatedProperty::BorderColor =>
            {
                Some(animation)
            }
            _ => None,
        })
        .expect("the vector carries a border colour animation");
    assert_eq!(last.property, AnimatedProperty::BorderColor);
    assert_eq!(
        last.interpolation,
        Some(ColorInterpolation::SrgbPremultiplied)
    );
    assert_eq!(
        last.keyframes.last().map(|keyframe| &keyframe.value),
        Some(&KeyframeValue::Paint(Paint::Literal(Color::argb(
            0xff33_6699
        ))))
    );
}

/// The animations the vector ends with, newest last: a spring, then a 3D matrix.
fn vector_animations() -> Vec<Animation<'static>> {
    decode_batch(include_bytes!("vectors/mutations.bin"))
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::StartAnimation(animation) => Some(animation),
            _ => None,
        })
        .collect()
}

fn spring_animation(stiffness: f32, damping_ratio: f32) -> Animation<'static> {
    let mut spring = animation(
        AnimatedProperty::Alpha,
        vec![KeyframeValue::Alpha(0.0), KeyframeValue::Alpha(1.0)],
    );
    spring.keyframes.to_mut()[0].timing = Timing::Spring {
        stiffness,
        damping_ratio,
    };
    spring
}

/// A spring keyframe is timing kind 4 with no step position, the stiffness and the damping
/// ratio in the first two numbers and zero in the last two. It decodes back to what was
/// encoded, and the checked-in vector carries one after every record that was there before.
#[test]
fn fr41_spring_timing_round_trips() {
    let record = Mutation::StartAnimation(spring_animation(1500.0, 0.5));
    let bytes = encode(std::slice::from_ref(&record));
    let keyframe = &bytes[12 + 52..12 + 52 + 24];
    assert_eq!(keyframe[4], 4, "timing kind Spring");
    assert_eq!(keyframe[5], 0, "no step position");
    let number = |at: usize| f32::from_le_bytes(keyframe[at..at + 4].try_into().unwrap());
    assert_eq!(number(8), 1500.0);
    assert_eq!(number(12), 0.5);
    assert_eq!(&keyframe[16..24], &[0; 8]);
    assert_eq!(decode_batch(&bytes), Ok(vec![record]));

    let animations = vector_animations();
    let spring = &animations[animations.len() - 2];
    assert_eq!(spring.property, AnimatedProperty::Alpha);
    assert_eq!(
        spring.keyframes[0].timing,
        Timing::Spring {
            stiffness: 1500.0,
            damping_ratio: 0.5
        }
    );
    assert_eq!(spring.keyframes[1].timing, Timing::Linear);
}

/// A spring whose stiffness or damping ratio is not a finite positive number, or that
/// carries anything in the numbers or the step position it does not use, is a protocol
/// error.
#[test]
fn fr41_spring_with_invalid_parameters_is_a_protocol_error() {
    for (stiffness, damping_ratio) in [
        (0.0, 1.0),
        (-1500.0, 1.0),
        (f32::NAN, 1.0),
        (f32::INFINITY, 1.0),
        (1500.0, 0.0),
        (1500.0, -0.5),
        (1500.0, f32::NAN),
        (1500.0, f32::INFINITY),
    ] {
        let bytes = encode(&[Mutation::StartAnimation(spring_animation(
            stiffness,
            damping_ratio,
        ))]);
        assert_eq!(
            decode_batch(&bytes),
            Err(ProtocolError::InvalidAnimation),
            "stiffness {stiffness}, damping ratio {damping_ratio} was accepted"
        );
    }
    let good = encode(&[Mutation::StartAnimation(spring_animation(1500.0, 0.5))]);
    let keyframe = 12 + 52;
    for (what, at, byte) in [
        ("a third number", keyframe + 16, 0x3f_u8),
        ("a fourth number", keyframe + 20, 0x3f),
        ("a step position", keyframe + 5, 2),
    ] {
        let mut bad = good.clone();
        bad[at + if at == keyframe + 5 { 0 } else { 3 }] = byte;
        assert_eq!(
            decode_batch(&bad),
            Err(ProtocolError::InvalidAnimation),
            "{what} was accepted"
        );
    }
}

/// `AnimationSpec::spring` fills the length with the spring's settle time: how long Compose's
/// spring of mass 1 takes to come within 0.001 of its target and stay there. The same table
/// is checked against the Renderer's own settle time on the Kotlin side.
#[test]
fn fr41_spring_spec_duration_is_the_settle_time() {
    use compose_rust::{AnimationSpec, spring_settle_ms};
    // (stiffness, damping ratio, settle time in ms): critically damped, under-damped and
    // over-damped springs.
    let table = [
        (1500.0, 1.0, 238.4053),
        (1500.0, 0.5, 364.1429),
        (400.0, 0.75, 488.073),
        (200.0, 0.2, 2449.4766),
        (10_000.0, 1.0, 92.3340),
        (50.0, 2.0, 3685.1787),
    ];
    for (stiffness, damping_ratio, settle_ms) in table {
        let spec = AnimationSpec::spring(stiffness, damping_ratio);
        assert!(
            (spec.duration_ms - settle_ms).abs() < 1.0,
            "spring({stiffness}, {damping_ratio}) lasts {} ms, not {settle_ms}",
            spec.duration_ms
        );
        assert_eq!(spec.duration_ms, spring_settle_ms(stiffness, damping_ratio));
        assert_eq!(spec.delay_ms, 0.0);
        assert_eq!(
            spec.timing,
            Timing::Spring {
                stiffness,
                damping_ratio
            }
        );
    }
}

/// `rotateY(30deg)` in front of `perspective(400px)`, column-major.
fn turned_matrix() -> [f32; 16] {
    let (sin, cos) = 30.0_f32.to_radians().sin_cos();
    // The perspective row is (0, 0, -1/400, 1), which takes the rotation's third row into w.
    [
        cos,
        0.0,
        -sin,
        sin / 400.0,
        0.0,
        1.0,
        0.0,
        0.0,
        sin,
        0.0,
        cos,
        -cos / 400.0,
        0.0,
        0.0,
        0.0,
        1.0,
    ]
}

fn matrix_animation(values: Vec<KeyframeValue>) -> Animation<'static> {
    let mut animation = animation(AnimatedProperty::Transform3D, values);
    // A spring among the keyframes as well, so every timing kind travels with a matrix.
    animation.keyframes.to_mut()[0].timing = Timing::Spring {
        stiffness: 400.0,
        damping_ratio: 0.75,
    };
    animation
}

const IDENTITY: [f32; 16] = [
    1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
];

/// A 3D matrix animation is property 6 with sixteen `f32` in each keyframe, an 88 byte
/// keyframe, and no origin of its own. It decodes back to what was encoded, and the vector
/// carries one as its last animation.
#[test]
fn fr41_2_1_transform3d_animation_round_trips() {
    let to = turned_matrix();
    let record = Mutation::StartAnimation(matrix_animation(vec![
        KeyframeValue::Matrix3D(IDENTITY),
        KeyframeValue::Matrix3D(to),
        KeyframeValue::Matrix3D(IDENTITY),
    ]));
    let bytes = encode(std::slice::from_ref(&record));
    assert_eq!(bytes.len(), 12 + 52 + 3 * 88, "a keyframe is 88 bytes");
    assert_eq!(bytes[12 + 12], 6, "property Transform3D");
    let second = 12 + 52 + 88 + 24;
    let values: Vec<f32> = bytes[second..second + 64]
        .chunks(4)
        .map(|chunk| f32::from_le_bytes(chunk.try_into().unwrap()))
        .collect();
    assert_eq!(values, to, "column-major, in the modifier's order");
    assert_eq!(decode_batch(&bytes), Ok(vec![record]));

    let animations = vector_animations();
    let last = animations.last().expect("the vector carries animations");
    assert_eq!(last.property, AnimatedProperty::Transform3D);
    assert_eq!((last.origin_x, last.origin_y), (0.0, 0.0));
    assert!(matches!(
        last.keyframes.last().map(|keyframe| &keyframe.value),
        Some(KeyframeValue::Matrix3D(m)) if m[3] == 0.00125 && m[11] == -0.0025 && m[12] == 12.0
    ));
}

/// A matrix with a value that is not a number, and a record that names an origin of its own,
/// are protocol errors. Whether the node has the `Transform3D` modifier the animation plays
/// on, and whether a `Transform` animation names a node that has one, is the Renderer's
/// question once the batch is in, and is tested there.
#[test]
fn fr41_2_1_transform3d_animation_needs_its_base_modifier() {
    for bad in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
        for cell in [0, 3, 11, 15] {
            let mut m = IDENTITY;
            m[cell] = bad;
            let bytes = encode(&[Mutation::StartAnimation(matrix_animation(vec![
                KeyframeValue::Matrix3D(IDENTITY),
                KeyframeValue::Matrix3D(m),
            ]))]);
            assert_eq!(
                decode_batch(&bytes),
                Err(ProtocolError::InvalidAnimation),
                "{bad} in cell {cell} was accepted"
            );
        }
    }
    for (origin_x, origin_y) in [(0.5, 0.0), (0.0, 0.5)] {
        let mut animation = matrix_animation(vec![
            KeyframeValue::Matrix3D(IDENTITY),
            KeyframeValue::Matrix3D(turned_matrix()),
        ]);
        animation.origin_x = origin_x;
        animation.origin_y = origin_y;
        let bytes = encode(&[Mutation::StartAnimation(animation)]);
        assert_eq!(
            decode_batch(&bytes),
            Err(ProtocolError::InvalidAnimation),
            "origin ({origin_x}, {origin_y}) was accepted"
        );
    }
    // A matrix where a list of functions belongs, and the other way round.
    let mut list_in_matrix = matrix_animation(vec![
        KeyframeValue::Matrix3D(IDENTITY),
        KeyframeValue::Matrix3D(IDENTITY),
    ]);
    list_in_matrix.keyframes.to_mut()[1].value = functions(&[TransformFunction::rotate(0.0)]);
    let mut matrix_in_list = animation(
        AnimatedProperty::Transform,
        vec![
            functions(&[TransformFunction::rotate(0.0)]),
            functions(&[TransformFunction::rotate(90.0)]),
        ],
    );
    matrix_in_list.keyframes.to_mut()[1].value = KeyframeValue::Matrix3D(IDENTITY);
    for (what, animation) in [
        ("a function list in a matrix animation", list_in_matrix),
        ("a matrix in a function list animation", matrix_in_list),
    ] {
        let bytes = encode(&[Mutation::StartAnimation(animation)]);
        assert_eq!(
            decode_batch(&bytes),
            Err(ProtocolError::InvalidAnimation),
            "{what} was accepted"
        );
    }
}
