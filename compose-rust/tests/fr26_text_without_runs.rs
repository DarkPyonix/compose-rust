//! A `Text` that says nothing about runs travels exactly as it did before runs existed: no
//! record for them, and the same records in the same bytes whether the application never
//! mentioned runs or handed over an empty list.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{OverflowWrap, PropertyKind, WidgetKind, WordBreak};
use compose_rust::spans::{TextSpan, TextSpans};
use compose_rust::ui::*;
use compose_rust::{FontRef, FontRefs, GenericFamily};

#[Composable]
fn Untouched() {
    Text!("plain bold link");
}

#[Composable]
fn EmptyRuns() {
    Text!(
        "plain bold link",
        spans = TextSpans::new(Vec::<TextSpan>::new())
    );
}

#[Composable]
fn WithRuns() {
    Text!(
        "plain bold link",
        spans = TextSpans::new([TextSpan::new(6, 4).bold()])
    );
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

/// Whether each record `batch` writes under `kind` carries nothing, in the order written.
fn cleared_records_of(batch: &[u8], kind: PropertyKind) -> Vec<bool> {
    decode_batch(batch)
        .expect("the batch decodes")
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                property, value, ..
            } if *property == kind => Some(matches!(value, PropertyValue::None)),
            _ => None,
        })
        .collect()
}

fn serif() -> FontRefs {
    FontRefs::new([FontRef::Generic(GenericFamily::Serif)])
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

/// The fonts runs name travel in a record of their own beside the runs, and a text with no
/// runs has no use for that one either.
#[test]
fn fr26_a_text_without_runs_sends_no_run_font_record() {
    let untouched = first_batch(Untouched);
    assert_eq!(
        records_of(&untouched, PropertyKind::SpanFonts),
        0,
        "a text that never mentioned runs sends no font table for them: {:?}",
        decode_batch(&untouched).expect("the batch decodes")
    );
}

#[Composable]
fn RunFonts(on: MutableState<bool>, count: MutableState<i32>) {
    let spans = if on.get() {
        TextSpans::new_with_fonts([(TextSpan::new(6, 4), serif())])
    } else {
        TextSpans::new([TextSpan::new(6, 4)])
    };
    Text!(format!("plain bold link {}", count.get()), spans = spans);
}

/// A text whose runs stop naming fonts clears the table once, and sends nothing for it
/// after that.
#[test]
fn fr26_a_run_font_table_that_goes_away_is_cleared_once() {
    let on = mutable_state_of(true);
    let count = mutable_state_of(0);
    let mut host = ComposeHost::with_content(move || RunFonts(on, count));
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    assert_eq!(
        cleared_records_of(&first, PropertyKind::SpanFonts),
        vec![false],
        "the table is sent once"
    );

    on.set(false);
    let cleared = host.render_frame(1).expect("the frame encodes").to_vec();
    assert_eq!(
        cleared_records_of(&cleared, PropertyKind::SpanFonts),
        vec![true],
        "a table that goes away is cleared by exactly one record"
    );

    count.set(1);
    let after = host.render_frame(2).expect("the frame encodes").to_vec();
    assert!(
        cleared_records_of(&after, PropertyKind::SpanFonts).is_empty(),
        "and it is not cleared again"
    );
}

/// The font list and the two break rules are left out the same way when a text names none
/// of them.
#[test]
fn fr26_a_plain_text_sends_no_font_or_break_rule_records() {
    let untouched = first_batch(Untouched);
    for kind in [
        PropertyKind::Font,
        PropertyKind::WordBreak,
        PropertyKind::OverflowWrap,
    ] {
        assert_eq!(
            records_of(&untouched, kind),
            0,
            "a plain text sends no {kind:?} record: {:?}",
            decode_batch(&untouched).expect("the batch decodes")
        );
    }
}

#[Composable]
fn Styled(on: MutableState<bool>) {
    // One Text call and one place it is composed, so both frames are the same node.
    let on = on.get();
    Text!(
        "plain bold link",
        font = if on { serif() } else { FontRefs::default() },
        word_break = on.then_some(WordBreak::KeepAll),
        overflow_wrap = on.then_some(OverflowWrap::Anywhere),
    );
}

/// A font list or a break rule that is taken away is cleared by one record each.
#[test]
fn fr26_a_font_or_break_rule_that_goes_away_is_cleared_once() {
    let on = mutable_state_of(true);
    let mut host = ComposeHost::with_content(move || Styled(on));
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    on.set(false);
    let cleared = host.render_frame(1).expect("the frame encodes").to_vec();
    for kind in [
        PropertyKind::Font,
        PropertyKind::WordBreak,
        PropertyKind::OverflowWrap,
    ] {
        assert_eq!(
            cleared_records_of(&first, kind),
            vec![false],
            "{kind:?} is sent once when set"
        );
        assert_eq!(
            cleared_records_of(&cleared, kind),
            vec![true],
            "{kind:?} is cleared by exactly one record: {:?}",
            decode_batch(&cleared).expect("the batch decodes")
        );
    }
}
