//! A `TextField` that names no font list and no break rule sends no record for them, as a
//! `Text` does not, and one that had them and loses them clears each with one record.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{OverflowWrap, PropertyKind, WordBreak};
use compose_rust::ui::*;
use compose_rust::{FontRef, FontRefs, GenericFamily};

const KINDS: [PropertyKind; 3] = [
    PropertyKind::Font,
    PropertyKind::WordBreak,
    PropertyKind::OverflowWrap,
];

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

#[Composable]
fn PlainField() {
    TextField!("", |_| {});
}

#[test]
fn fr40_a_plain_text_field_sends_no_font_or_break_rule_records() {
    let mut host = ComposeHost::with_content(PlainField);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    for kind in KINDS {
        assert!(
            cleared_records_of(&batch, kind).is_empty(),
            "a plain field sends no {kind:?} record: {:?}",
            decode_batch(&batch).expect("the batch decodes")
        );
    }
}

#[Composable]
fn StyledField(on: MutableState<bool>) {
    // One TextField call and one place it is composed, so both frames are the same node.
    let on = on.get();
    TextField!(
        "",
        |_| {},
        font = if on {
            FontRefs::new([FontRef::Generic(GenericFamily::Serif)])
        } else {
            FontRefs::default()
        },
        word_break = on.then_some(WordBreak::KeepAll),
        overflow_wrap = on.then_some(OverflowWrap::Anywhere),
    );
}

#[test]
fn fr40_a_text_field_font_or_break_rule_that_goes_away_is_cleared_once() {
    let on = mutable_state_of(true);
    let mut host = ComposeHost::with_content(move || StyledField(on));
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    on.set(false);
    let cleared = host.render_frame(1).expect("the frame encodes").to_vec();
    for kind in KINDS {
        assert_eq!(
            cleared_records_of(&first, kind),
            vec![false],
            "{kind:?} is sent once when set"
        );
        assert_eq!(
            cleared_records_of(&cleared, kind),
            vec![true],
            "{kind:?} is cleared by exactly one record"
        );
    }
}
