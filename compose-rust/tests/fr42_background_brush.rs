//! A positioned and sized background brush: one `BackgroundBrush` modifier draws a CSS
//! `background-image` layer with its position, size and repeat on a single node.

use compose_rust::ComposeHost;
use compose_rust::protocol::{BatchEncoder, Mutation, ProtocolError, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{BackgroundRepeat, DesignSystem, Paint, Theme};
use compose_rust::ui::{BackgroundBrush, Box, Color, Modifier};

fn encode(mutations: &[Mutation<'_>]) -> Vec<u8> {
    let mut encoder = BatchEncoder::default();
    for mutation in mutations {
        encoder.encode(mutation).unwrap();
    }
    encoder.finish().unwrap().to_vec()
}

fn layer(repeat: BackgroundRepeat, tile_width: f32, tile_height: f32) -> compose_rust::Modifier {
    compose_rust::Modifier::BackgroundBrush {
        x: 12.5,
        y: -4.0,
        width: 48.0,
        height: 24.0,
        paint: Paint::Asset(9),
        repeat,
        tile_width,
        tile_height,
    }
}

/// Modifier tag 28, and one 52 byte record: the 28 bytes every modifier has and three more
/// words. The rectangle fills the first two words, the paint the third, the repeat the
/// fourth and the step the fifth. It comes back out of the batch as the value that went
/// in, and the checked-in vector holds the same bytes.
#[test]
fn fr42_background_brush_round_trips() {
    let record = Mutation::SetModifier {
        node_id: 4,
        index: 2,
        modifier: layer(BackgroundRepeat::RepeatX, 56.0, 30.0),
    };
    let bytes = encode(std::slice::from_ref(&record));
    assert_eq!(bytes.len(), 12 + 52);
    let body = &bytes[12..];
    assert_eq!(&body[0..4], &[3, 0, 52, 0], "tag 3, length 52");
    assert_eq!(
        u16::from_le_bytes([body[10], body[11]]),
        28,
        "modifier tag 28"
    );
    let float = |at: usize| f32::from_le_bytes(body[at..at + 4].try_into().unwrap());
    assert_eq!(
        [float(12), float(16), float(20), float(24)],
        [12.5, -4.0, 48.0, 24.0]
    );
    assert_eq!(
        u64::from_le_bytes(body[28..36].try_into().unwrap()),
        Paint::Asset(9).to_bits()
    );
    assert_eq!(
        u64::from_le_bytes(body[36..44].try_into().unwrap()),
        3,
        "RepeatX"
    );
    assert_eq!([float(44), float(48)], [56.0, 30.0]);
    assert_eq!(decode_batch(&bytes), Ok(vec![record]));

    assert_eq!(
        include_bytes!("vectors/background_brush.bin").as_slice(),
        bytes.as_slice(),
        "the checked-in vector"
    );
    assert_eq!(u16::from(BackgroundRepeat::NoRepeat), 1);
    assert_eq!(u16::from(BackgroundRepeat::Repeat), 2);
    assert_eq!(u16::from(BackgroundRepeat::RepeatX), 3);
    assert_eq!(u16::from(BackgroundRepeat::RepeatY), 4);
}

/// A step of zero or less, a value that is not a number and a repeat this side does not
/// know are refused as a protocol error, not drawn.
#[test]
fn fr42_background_brush_with_a_step_that_is_not_a_size_is_a_protocol_error() {
    for (tile_width, tile_height) in [(0.0, 30.0), (56.0, -1.0), (f32::NAN, 30.0)] {
        let bytes = encode(&[Mutation::SetModifier {
            node_id: 4,
            index: 2,
            modifier: layer(BackgroundRepeat::Repeat, tile_width, tile_height),
        }]);
        assert_eq!(
            decode_batch(&bytes),
            Err(ProtocolError::InvalidModifier(28)),
            "a step of {tile_width} by {tile_height}"
        );
    }
    let mut unknown = encode(&[Mutation::SetModifier {
        node_id: 4,
        index: 2,
        modifier: layer(BackgroundRepeat::Repeat, 56.0, 30.0),
    }]);
    unknown[12 + 36] = 9;
    assert!(decode_batch(&unknown).is_err(), "repeat 9 is no repeat");
}

thread_local! {
    static LAYERS: std::cell::RefCell<Vec<BackgroundBrush>> = const { std::cell::RefCell::new(Vec::new()) };
}

#[Composable]
fn Layered() {
    let layers = LAYERS.with(|cell| cell.borrow().clone());
    Box()
        .modifier(
            Modifier
                .size(200.0, 100.0)
                .background(Paint::Literal(Color::rgb(0x202020)))
                .background_brushes(layers),
        )
        .content(|| {});
}

/// The `BackgroundBrush` records the first frame wrote, with their chain positions, and the
/// position of the background colour.
fn frame(layers: Vec<BackgroundBrush>) -> (Vec<(u16, compose_rust::Modifier)>, u16) {
    LAYERS.with(|cell| *cell.borrow_mut() = layers);
    let mut host = ComposeHost::with_theme(Layered, Theme::unified(DesignSystem::Material3));
    let batch = host.rebuild().expect("the first frame encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the first frame decodes");
    let mut brushes = Vec::new();
    let mut background = None;
    for mutation in mutations {
        if let Mutation::SetModifier {
            index, modifier, ..
        } = mutation
        {
            match modifier {
                compose_rust::Modifier::BackgroundBrush { .. } => brushes.push((index, modifier)),
                compose_rust::Modifier::Background(_) => background = Some(index),
                _ => {}
            }
        }
    }
    LAYERS.with(|cell| cell.borrow_mut().clear());
    (
        brushes,
        background.expect("the background colour is written"),
    )
}

/// Layers go out in the order they were added, each in a chain position of its own after
/// the background colour, so the first is drawn at the bottom and all of them over the
/// colour. A layer whose step is not a size is not sent at all.
#[test]
fn fr42_background_brush_layers_follow_the_background_in_order() {
    let gradient = Paint::Asset(3);
    let (brushes, background) = frame(vec![
        BackgroundBrush::new(0.0, 0.0, 40.0, 40.0, gradient).repeat(BackgroundRepeat::Repeat),
        BackgroundBrush::new(10.0, 20.0, 30.0, 10.0, gradient)
            .repeat(BackgroundRepeat::RepeatY)
            .step(30.0, 15.0),
        BackgroundBrush::new(0.0, 0.0, 10.0, 10.0, gradient).step(0.0, 10.0),
    ]);
    assert_eq!(
        brushes,
        vec![
            (
                background + 1,
                compose_rust::Modifier::BackgroundBrush {
                    x: 0.0,
                    y: 0.0,
                    width: 40.0,
                    height: 40.0,
                    paint: gradient,
                    repeat: BackgroundRepeat::Repeat,
                    tile_width: 40.0,
                    tile_height: 40.0,
                },
            ),
            (
                background + 2,
                compose_rust::Modifier::BackgroundBrush {
                    x: 10.0,
                    y: 20.0,
                    width: 30.0,
                    height: 10.0,
                    paint: gradient,
                    repeat: BackgroundRepeat::RepeatY,
                    tile_width: 30.0,
                    tile_height: 15.0,
                },
            ),
        ],
    );
}

/// A node with no layers writes no `BackgroundBrush` record, so it reaches the Renderer as
/// it always did.
#[test]
fn fr42_a_node_without_background_brushes_sends_none() {
    assert_eq!(frame(Vec::new()).0, Vec::new());
}
