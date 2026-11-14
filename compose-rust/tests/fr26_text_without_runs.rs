//! A `Text` that says nothing about runs travels exactly as it did before runs existed: no
//! record for them, and the same records in the same bytes whether the application never
//! mentioned runs or handed over an empty list.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{PropertyKind, WidgetKind};
use compose_rust::spans::{TextSpan, TextSpans};
use compose_rust::ui::*;

#[Composable]
fn Untouched() {
    Text("plain bold link");
}

#[Composable]
fn EmptyRuns() {
    Text("plain bold link").spans(TextSpans::new(Vec::<TextSpan>::new()));
}

#[Composable]
fn WithRuns() {
    Text("plain bold link").spans(TextSpans::new([TextSpan::new(6, 4).bold()]));
}

fn first_batch(content: fn()) -> Vec<u8> {
    let mut host = ComposeHost::with_content(content);
    host.rebuild().expect("the first batch encodes").to_vec()
}

/// How many records `batch` writes under `kind`, whatever value they carry.
fn records_of(batch: &[u8], kind: PropertyKind) -> usize {
    decode_batch(batch)
        .expect("the batch decodes")
        .iter()
        .filter(
            |mutation| matches!(mutation, Mutation::SetProp { property, .. } if *property == kind),
        )
        .count()
}

#[test]
fn fr26_a_text_without_runs_sends_no_runs_record() {
    let untouched = first_batch(Untouched);
    let mutations = decode_batch(&untouched).expect("the batch decodes");
    assert!(
        mutations.iter().any(|mutation| matches!(
            mutation,
            Mutation::Create {
                widget: WidgetKind::Text,
                ..
            }
        )),
        "the text is created: {mutations:?}"
    );
    assert!(
        mutations.iter().any(|mutation| matches!(
            mutation,
            Mutation::SetProp {
                property: PropertyKind::Text,
                value: PropertyValue::String("plain bold link"),
                ..
            }
        )),
        "the string is sent: {mutations:?}"
    );
    assert_eq!(
        records_of(&untouched, PropertyKind::Spans),
        0,
        "a text that never mentioned runs sends no record for them: {mutations:?}"
    );

    // An empty list is the same text on the wire, record for record and byte for byte.
    let empty = first_batch(EmptyRuns);
    assert_eq!(records_of(&empty, PropertyKind::Spans), 0);
    assert_eq!(
        decode_batch(&empty).expect("the batch decodes"),
        mutations,
        "an empty list of runs changes no record"
    );
    assert_eq!(empty, untouched, "nor a byte of the batch");

    // The checks above can only fail if a text that does have runs sends the record.
    assert_eq!(records_of(&first_batch(WithRuns), PropertyKind::Spans), 1);
}
