//! A background behind one run of a `Text`: what the record carries and how it travels.

use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{Mutation, PropertyValue, decode_batch};
use dioxus_compose_adapter::spans::{
    SPAN_BACKGROUND_AT, SPAN_COLOR_AT, SPAN_LEN, TextSpan, TextSpans,
};
use dioxus_compose_adapter::{Host, PropertyKind};

/// A span record is 36 bytes, and the background is its own word after the text's paint,
/// so one run can carry both.
#[test]
fn fr26_a_span_record_is_36_bytes_with_the_background_after_the_text_colour() {
    assert_eq!(SPAN_LEN, 36);
    assert_eq!(SPAN_BACKGROUND_AT, SPAN_COLOR_AT + 8);
    let colour = Paint::Role(ColorRole::SyntaxKeyword);
    let background = Paint::Role(ColorRole::DiffAddedEmphasis);
    let spans = TextSpans::new([TextSpan::new(2, 3)
        .with_color(colour)
        .with_background(background)]);
    let bytes = spans.as_bytes();
    assert_eq!(bytes.len(), 36);
    let word = |at: usize| u64::from_le_bytes(bytes[at..at + 8].try_into().unwrap());
    assert_eq!(word(SPAN_COLOR_AT), colour.to_bits());
    assert_eq!(word(SPAN_BACKGROUND_AT), background.to_bits());
    let back: Vec<_> = spans.spans().collect();
    assert_eq!(back[0].color, Some(colour));
    assert_eq!(back[0].background, Some(background));
}

/// No background is zero on the wire and none when read back, so a run without one draws
/// nothing behind its letters.
#[test]
fn fr26_a_span_without_a_background_carries_zero() {
    let spans = TextSpans::new([TextSpan::new(0, 4).bold()]);
    let bytes = spans.as_bytes();
    assert!(
        bytes[SPAN_BACKGROUND_AT..SPAN_BACKGROUND_AT + 8]
            .iter()
            .all(|byte| *byte == 0)
    );
    assert_eq!(spans.spans().next().unwrap().background, None);
}

fn changed_word() -> Element {
    // "let total = 3;" with only "total" marked as the word that changed on an added line.
    let spans = TextSpans::new([
        TextSpan::new(0, 3).with_color(Paint::Role(ColorRole::SyntaxKeyword)),
        TextSpan::new(4, 5)
            .with_color(Paint::Role(ColorRole::SyntaxVariable))
            .with_background(Paint::Role(ColorRole::DiffAddedEmphasis)),
    ]);
    rsx! {
        Row { background: Paint::Role(ColorRole::DiffAddedContainer),
            Text { text: "let total = 3;", spans }
        }
    }
}

/// The background travels with the run it belongs to and no other, through the same
/// `Spans` property every run already uses.
#[test]
fn fr26_a_background_travels_on_its_own_run_only() {
    dioxus_compose_adapter::window::reset_window_size();
    let mut host = Host::new(changed_word);
    let batch = host.rebuild().expect("the first frame failed to encode");
    let bytes = decode_batch(batch)
        .expect("decode")
        .into_iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                property: PropertyKind::Spans,
                value: PropertyValue::Bytes(bytes),
                ..
            } => Some(bytes.to_vec()),
            _ => None,
        })
        .expect("the runs did not travel");
    assert_eq!(bytes.len(), 2 * SPAN_LEN);
    let runs: Vec<_> = TextSpans::from_bytes(bytes).spans().collect();
    assert_eq!(runs[0].background, None);
    assert_eq!(
        runs[1].background,
        Some(Paint::Role(ColorRole::DiffAddedEmphasis))
    );
    assert_eq!(runs[1].color, Some(Paint::Role(ColorRole::SyntaxVariable)));
}

/// The runs in the protocol vector are the 36 byte records both sides read.
#[test]
fn fr26_the_vector_carries_36_byte_runs() {
    use dioxus_compose_adapter::codegen::{generate_mutation_vector, spans_vector};
    let bytes = generate_mutation_vector().unwrap();
    let carried = decode_batch(&bytes)
        .unwrap()
        .into_iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                property: PropertyKind::Spans,
                value: PropertyValue::Bytes(bytes),
                ..
            } => Some(bytes.to_vec()),
            _ => None,
        })
        .expect("the vector has runs");
    assert_eq!(carried, spans_vector().as_bytes());
    assert_eq!(carried.len(), 2 * SPAN_LEN);
}
