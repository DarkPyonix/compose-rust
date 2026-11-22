//! Editable rich text in a `TextField`: the two commands, the two events, the records they
//! carry and the Host API that queues the commands.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{
    BatchEncoder, HostEvent, Mutation, ProtocolError, decode_batch, decode_event, encode_event,
};
use compose_rust::rich_text::{
    CHARACTER_RUN_LEN, CharacterRun, PARAGRAPH_LEN, ParagraphFormat, RichDocument, RichTextError,
    TextFormat, Tri,
};
use compose_rust::schema::{
    Color, ColorRole, EventPayload, FormatOp, ListKind, Paint, ParagraphRole, PropertyKind,
    Selection,
};
use compose_rust::ui::*;
use std::sync::{Arc, Mutex};
use support::Tree;

// ----- helpers ---------------------------------------------------------------------------

fn encode(mutation: &Mutation<'_>) -> Result<Vec<u8>, ProtocolError> {
    let mut encoder = BatchEncoder::default();
    encoder.encode(mutation)?;
    Ok(encoder.finish()?.to_vec())
}

fn is_rich_error<T: std::fmt::Debug>(result: Result<T, ProtocolError>) -> bool {
    matches!(result, Err(ProtocolError::InvalidRichText(_)))
}

/// The document of the checked-in vectors, so the tests and the vectors agree.
fn vector_document() -> RichDocument {
    compose_rust::codegen::rich_text_vector()
}

fn set_rich_text(document: &RichDocument) -> Result<Vec<u8>, ProtocolError> {
    encode(&Mutation::SetRichText {
        node_id: 9,
        text: &document.text,
        characters: &document.encode_characters(),
        paragraphs: &document.encode_paragraphs(),
        selection: None,
    })
}

fn format_text_bytes(op: u16, value: u16, paint: u64) -> Vec<u8> {
    let mut bytes = encode(&Mutation::FormatText {
        node_id: 9,
        op: FormatOp::Bold,
        value: 1,
        paint: None,
    })
    .expect("a bold command encodes");
    assert_eq!(bytes.len(), 12 + 20);
    bytes[20..22].copy_from_slice(&op.to_le_bytes());
    bytes[22..24].copy_from_slice(&value.to_le_bytes());
    bytes[24..32].copy_from_slice(&paint.to_le_bytes());
    bytes
}

// ----- the commands ----------------------------------------------------------------------

#[test]
fn fr26_1_the_numbers_are_the_agreed_ones() {
    assert_eq!(PropertyKind::RichText as u16, 220);
    assert_eq!(PropertyKind::OnTextFormat as u16, 221);
    assert_eq!(PropertyKind::OnRichTextChanged as u16, 222);
    assert_eq!(u16::from(FormatOp::Quote), 10);
    assert_eq!(u16::from(ParagraphRole::Monostyled), 5);
    assert_eq!(u16::from(ListKind::None), 0);
    assert_eq!(u16::from(ListKind::Numbered), 3);
    assert_eq!(CHARACTER_RUN_LEN, 24);
    assert_eq!(PARAGRAPH_LEN, 16);
}

#[test]
fn fr26_1_format_text_round_trips_at_twenty_bytes() {
    let paint = Paint::Literal(Color(0xFF33_66CC));
    for (op, value, paint) in [
        (FormatOp::Bold, 2, None),
        (FormatOp::Italic, 0, None),
        (FormatOp::Underline, 1, None),
        (FormatOp::Strikethrough, 2, None),
        (FormatOp::Color, 0, Some(paint)),
        (FormatOp::Color, 0, None),
        (FormatOp::Paragraph, 3, None),
        (FormatOp::List, 0, None),
        (FormatOp::List, 3, None),
        (FormatOp::Indent, 0, None),
        (FormatOp::Outdent, 0, None),
        (FormatOp::Quote, 2, None),
    ] {
        let mutation = Mutation::FormatText {
            node_id: 9,
            op,
            value,
            paint,
        };
        let bytes = encode(&mutation).expect("the command encodes");
        assert_eq!(bytes.len(), 12 + 20, "{op:?}");
        // The record: tag 42 and its length, the node, the operation, the value, the paint.
        assert_eq!(&bytes[12..16], &[42, 0, 20, 0]);
        assert_eq!(&bytes[16..20], &9u32.to_le_bytes());
        assert_eq!(&bytes[20..22], &u16::from(op).to_le_bytes());
        assert_eq!(&bytes[22..24], &value.to_le_bytes());
        assert_eq!(
            &bytes[24..32],
            &paint.map_or(0, Paint::to_bits).to_le_bytes()
        );
        assert_eq!(decode_batch(&bytes).unwrap(), vec![mutation], "{op:?}");
    }
}

#[test]
fn fr26_1_a_format_text_that_means_nothing_is_a_protocol_error() {
    let literal = Paint::Literal(Color(0xFF33_66CC)).to_bits();
    let cases: [(&str, u16, u16, u64); 12] = [
        ("an operation that is not one", 99, 0, 0),
        ("operation zero", 0, 0, 0),
        ("a style value of 3", 1, 3, 0),
        ("a flip of a colour", 5, 2, 0),
        ("a colour value of 1", 5, 1, 0),
        ("a paragraph role of 0", 6, 0, 0),
        ("a paragraph role of 6", 6, 6, 0),
        ("a list kind of 4", 7, 4, 0),
        ("an indent with a value", 8, 1, 0),
        ("an outdent with a value", 9, 1, 0),
        ("a quote value of 3", 10, 3, 0),
        ("a paint on a bold", 1, 1, literal),
    ];
    for (what, op, value, paint) in cases {
        let bytes = format_text_bytes(op, value, paint);
        assert!(is_rich_error(decode_batch(&bytes)), "{what} was accepted");
    }
    // A paint that is not a colour, on the operation that takes one.
    let bytes = format_text_bytes(5, 0, 9 << 32);
    assert!(is_rich_error(decode_batch(&bytes)));
    // The same refusals on the encoding side, where the Host would otherwise send them.
    assert!(is_rich_error(encode(&Mutation::FormatText {
        node_id: 9,
        op: FormatOp::Indent,
        value: 4,
        paint: None,
    })));
    assert!(is_rich_error(encode(&Mutation::FormatText {
        node_id: 9,
        op: FormatOp::Bold,
        value: 1,
        paint: Some(Paint::Role(ColorRole::Primary)),
    })));
    // A command of the wrong length is not read as one.
    let mut short = format_text_bytes(1, 1, 0);
    // The record says it is 16 bytes long, and so does the envelope's record region.
    short[14] = 16;
    short.truncate(12 + 16);
    short[4..8].copy_from_slice(&28u32.to_le_bytes());
    assert_eq!(
        decode_batch(&short),
        Err(ProtocolError::InvalidRecordLength)
    );
}

#[test]
fn fr26_1_set_rich_text_round_trips_at_forty_bytes() {
    let document = vector_document();
    for selection in [None, Some(Selection { start: 7, end: 13 })] {
        let mutation = Mutation::SetRichText {
            node_id: 9,
            text: &document.text,
            characters: &document.encode_characters(),
            paragraphs: &document.encode_paragraphs(),
            selection,
        };
        let bytes = encode(&mutation).expect("the document encodes");
        // The tag, then the record's length: the head, the node, three references and the
        // selection's two ends.
        assert_eq!(&bytes[12..16], &[43, 0, 40, 0]);
        let (start, end) = selection.map_or((u32::MAX, u32::MAX), |s| (s.start, s.end));
        assert_eq!(&bytes[44..48], &start.to_le_bytes());
        assert_eq!(&bytes[48..52], &end.to_le_bytes());
        assert_eq!(decode_batch(&bytes).unwrap(), vec![mutation]);
    }
    // The records behind the batch are what the document says they are.
    let bytes = set_rich_text(&document).unwrap();
    assert!(
        bytes
            .windows(CHARACTER_RUN_LEN * 3)
            .any(|window| window == document.encode_characters().as_slice())
    );
    let characters = RichDocument::decode_characters(&document.encode_characters()).unwrap();
    assert_eq!(characters, document.characters);
    let paragraphs = RichDocument::decode_paragraphs(&document.encode_paragraphs()).unwrap();
    assert_eq!(paragraphs, document.paragraphs);
}

// ----- the events ------------------------------------------------------------------------

fn format_event(format: TextFormat) -> HostEvent<'static> {
    HostEvent::new(9, 4, EventPayload::TextFormatAtSelection(format))
}

#[test]
fn fr26_1_text_format_at_selection_round_trips_at_thirty_six_bytes() {
    for format in [
        TextFormat::default(),
        compose_rust::codegen::text_format_vector(),
        TextFormat {
            bold: Tri::Mixed,
            italic: Tri::All,
            color: None,
            color_mixed: true,
            role: None,
            list: None,
            indent: None,
            quote: Tri::All,
            ..TextFormat::default()
        },
        TextFormat {
            list: Some(ListKind::None),
            indent: Some(8),
            ..TextFormat::default()
        },
    ] {
        let event = format_event(format);
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).expect("the format encodes");
        assert_eq!(bytes.len(), 36);
        assert_eq!(&bytes[0..4], &[68, 0, 36, 0]);
        assert_eq!(decode_event(&bytes), Ok(event));
    }
    // The words are where the format says: two bits for each style from the lowest bit.
    let mut bytes = Vec::new();
    encode_event(
        &format_event(compose_rust::codegen::text_format_vector()),
        &mut bytes,
    )
    .unwrap();
    assert_eq!(&bytes[16..20], &(1u32 | (2 << 2) | (1 << 6)).to_le_bytes());
    assert_eq!(&bytes[20..22], &3u16.to_le_bytes());
    assert_eq!(&bytes[22..24], &3u16.to_le_bytes());
    assert_eq!(&bytes[24..26], &2u16.to_le_bytes());
    assert_eq!(&bytes[26..28], &2u16.to_le_bytes());
    assert_eq!(
        &bytes[28..36],
        &Paint::Literal(Color(0xFFCC_3300)).to_bits().to_le_bytes()
    );
}

#[test]
fn fr26_1_a_text_format_with_a_word_that_is_not_one_is_a_protocol_error() {
    let mut good = Vec::new();
    encode_event(&format_event(TextFormat::default()), &mut good).unwrap();
    let patches: [(&str, usize, &[u8]); 8] = [
        ("a style state of 3", 16, &3u32.to_le_bytes()),
        ("a bit past the colour flag", 16, &(1u32 << 9).to_le_bytes()),
        ("a role of 6", 20, &6u16.to_le_bytes()),
        ("a list kind of 4", 22, &4u16.to_le_bytes()),
        ("an indent of 9", 24, &9u16.to_le_bytes()),
        ("a quote of 3", 26, &3u16.to_le_bytes()),
        (
            "a paint that is not a colour",
            28,
            &(9u64 << 32).to_le_bytes(),
        ),
        // A colour that is both mixed and one colour says two things.
        (
            "a mixed colour with a colour",
            28,
            &Paint::Role(ColorRole::Primary).to_bits().to_le_bytes(),
        ),
    ];
    for (what, at, bytes) in patches {
        let mut patched = good.clone();
        patched[at..at + bytes.len()].copy_from_slice(bytes);
        if what.starts_with("a mixed") {
            patched[16..20].copy_from_slice(&(1u32 << 8).to_le_bytes());
        }
        assert!(is_rich_error(decode_event(&patched)), "{what} was accepted");
    }
    let mut short = good.clone();
    short.truncate(32);
    short[2] = 32;
    assert_eq!(
        decode_event(&short),
        Err(ProtocolError::InvalidRecordLength)
    );
}

fn rich_event<'a>(document: &'a RichDocument, parts: &'a (Vec<u8>, Vec<u8>)) -> HostEvent<'a> {
    HostEvent::new(
        9,
        5,
        EventPayload::RichTextChanged {
            text: &document.text,
            characters: &parts.0,
            paragraphs: &parts.1,
        },
    )
}

#[test]
fn fr26_1_rich_text_changed_round_trips_with_its_three_arrays() {
    let document = vector_document();
    let parts = (document.encode_characters(), document.encode_paragraphs());
    let event = rich_event(&document, &parts);
    let mut bytes = Vec::new();
    encode_event(&event, &mut bytes).expect("the document encodes");
    // The record is 40 bytes and the text, the runs and the paragraphs follow it.
    assert_eq!(&bytes[0..4], &[69, 0, 40, 0]);
    assert_eq!(bytes.len(), 40 + document.text.len() + 3 * 24 + 2 * 16);
    assert_eq!(&bytes[16..20], &40u32.to_le_bytes());
    assert_eq!(&bytes[20..24], &(document.text.len() as u32).to_le_bytes());
    assert_eq!(decode_event(&bytes), Ok(event.clone()));
    let decoded = decode_event(&bytes).unwrap();
    assert_eq!(RichDocument::from_payload(&decoded.payload), Some(document));
    // An event that is not a document is none.
    assert_eq!(RichDocument::from_payload(&EventPayload::Clicked), None);
}

#[test]
fn fr26_1_a_rich_text_changed_with_a_bad_array_is_a_protocol_error() {
    let document = RichDocument {
        text: "ab\ncd".to_owned(),
        characters: vec![CharacterRun {
            bold: true,
            ..CharacterRun::new(0, 2)
        }],
        paragraphs: vec![ParagraphFormat {
            role: ParagraphRole::Heading,
            ..ParagraphFormat::body(3)
        }],
    };
    let parts = (document.encode_characters(), document.encode_paragraphs());
    let mut good = Vec::new();
    encode_event(&rich_event(&document, &parts), &mut good).unwrap();
    assert!(decode_event(&good).is_ok());
    let characters_at = 40 + document.text.len();
    let paragraphs_at = characters_at + 24;
    let patches: [(&str, usize, u8); 5] = [
        ("a reserved word of a run", characters_at + 20, 1),
        ("a style bit nobody knows", characters_at + 8, 0x10),
        ("a reserved word of a paragraph", paragraphs_at + 12, 1),
        ("a paragraph role of 9", paragraphs_at + 4, 9),
        ("a paragraph that does not start one", paragraphs_at, 1),
    ];
    for (what, at, value) in patches {
        let mut patched = good.clone();
        patched[at] = value;
        assert!(is_rich_error(decode_event(&patched)), "{what} was accepted");
    }
    // A reference whose length is not a whole number of records.
    let mut patched = good.clone();
    patched[28..32].copy_from_slice(&25u32.to_le_bytes());
    assert!(is_rich_error(decode_event(&patched)));
}

// ----- what a document may hold ----------------------------------------------------------

fn doc(
    text: &str,
    characters: Vec<CharacterRun>,
    paragraphs: Vec<ParagraphFormat>,
) -> RichDocument {
    RichDocument {
        text: text.to_owned(),
        characters,
        paragraphs,
    }
}

fn bold(start: u32, len: u32) -> CharacterRun {
    CharacterRun {
        bold: true,
        ..CharacterRun::new(start, len)
    }
}

fn heading(start: u32) -> ParagraphFormat {
    ParagraphFormat {
        role: ParagraphRole::Heading,
        ..ParagraphFormat::body(start)
    }
}

#[test]
fn fr26_1_a_document_that_does_not_hold_together_is_refused_everywhere() {
    let cases: Vec<(&str, RichDocument)> = vec![
        (
            "runs that overlap",
            doc("abcdef", vec![bold(0, 3), bold(2, 2)], vec![]),
        ),
        (
            "a run past the end",
            doc("ab\ncd", vec![bold(3, 3)], vec![]),
        ),
        ("an empty run", doc("abc", vec![bold(1, 0)], vec![])),
        (
            "a run that starts inside a character",
            doc("한글", vec![bold(1, 2)], vec![]),
        ),
        (
            "a run that ends inside a character",
            doc("한글", vec![bold(0, 4)], vec![]),
        ),
        (
            "runs out of order",
            doc("abcdef", vec![bold(3, 1), bold(0, 1)], vec![]),
        ),
        (
            "a run that says nothing",
            doc("abc", vec![CharacterRun::new(0, 2)], vec![]),
        ),
        (
            "an indent of 9",
            doc(
                "abc",
                vec![],
                vec![ParagraphFormat {
                    indent: 9,
                    ..heading(0)
                }],
            ),
        ),
        (
            "a paragraph that does not start one",
            doc("ab\ncd", vec![], vec![heading(1)]),
        ),
        (
            "a paragraph past the end",
            doc("ab", vec![], vec![heading(5)]),
        ),
        (
            "paragraphs out of order",
            doc("a\nb\nc", vec![], vec![heading(4), heading(2)]),
        ),
        (
            "a paragraph twice",
            doc("a\nb", vec![], vec![heading(2), heading(2)]),
        ),
        (
            "a body paragraph that says nothing",
            doc("a\nb", vec![], vec![ParagraphFormat::body(2)]),
        ),
    ];
    for (what, document) in cases {
        assert!(document.validate().is_err(), "{what}: validate accepted it");
        // Encoding refuses what validation refuses.
        let encoded = set_rich_text(&document);
        assert!(is_rich_error(encoded), "{what}: the encoder accepted it");
        // The wire reader refuses it too, from the bytes.
        let read = RichDocument::from_wire(
            &document.text,
            &document.encode_characters(),
            &document.encode_paragraphs(),
        );
        assert!(read.is_err(), "{what}: the reader accepted it");
    }
}

#[test]
fn fr26_1_records_that_are_not_records_are_refused() {
    let good = vector_document();
    let text = good.text.as_str();
    let characters = good.encode_characters();
    let paragraphs = good.encode_paragraphs();
    assert!(RichDocument::from_wire(text, &characters, &paragraphs).is_ok());

    // A length that is not a whole number of records.
    let mut long = characters.clone();
    long.push(0);
    assert_eq!(
        RichDocument::from_wire(text, &long, &paragraphs),
        Err(RichTextError::NotWholeRecords {
            list: "character run",
            len: 73
        })
    );
    assert!(matches!(
        RichDocument::from_wire(text, &characters, &paragraphs[..31]),
        Err(RichTextError::NotWholeRecords {
            list: "paragraph",
            ..
        })
    ));
    // A reserved word that is not zero.
    let mut patched = characters.clone();
    patched[20] = 1;
    assert!(matches!(
        RichDocument::from_wire(text, &patched, &paragraphs),
        Err(RichTextError::ReservedNotZero { index: 0, .. })
    ));
    // A style bit that is not a style.
    let mut patched = characters.clone();
    patched[8] |= 0x10;
    assert!(matches!(
        RichDocument::from_wire(text, &patched, &paragraphs),
        Err(RichTextError::UnknownStyleBits { .. })
    ));
    // A role, a list kind and an indent that are not.
    for (at, value) in [(4, 9u16), (6, 4), (8, 9)] {
        let mut patched = paragraphs.clone();
        patched[at..at + 2].copy_from_slice(&value.to_le_bytes());
        assert!(
            RichDocument::from_wire(text, &characters, &patched).is_err(),
            "{at} {value}"
        );
    }
    // A colour that is not a paint.
    let mut patched = characters.clone();
    patched[12..20].copy_from_slice(&(9u64 << 32).to_le_bytes());
    assert!(matches!(
        RichDocument::from_wire(text, &patched, &paragraphs),
        Err(RichTextError::InvalidPaint { .. })
    ));
}

#[test]
fn fr26_1_a_plain_document_is_its_text_and_two_empty_lists() {
    let plain = RichDocument::plain("한글 text");
    assert!(plain.validate().is_ok());
    assert!(plain.encode_characters().is_empty());
    assert!(plain.encode_paragraphs().is_empty());
    // A paragraph may start right after a final line break, on the empty last paragraph.
    assert!(doc("a\n", vec![], vec![heading(2)]).validate().is_ok());
    assert!(doc("", vec![], vec![heading(0)]).validate().is_ok());
}

// ----- the Host API ----------------------------------------------------------------------

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree, Vec<u8>) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    tree.apply(&batch);
    (host, tree, batch)
}

fn click(host: &mut ComposeHost, tree: &mut Tree, label: &str) -> Vec<u8> {
    let (node_id, handler_id) = tree.find("Button", Some(label), 0, PropertyKind::OnClick);
    let (batch, _) = host
        .dispatch(HostEvent::new(node_id, handler_id, EventPayload::Clicked))
        .expect("the click is accepted");
    let batch = batch.to_vec();
    tree.apply(&batch);
    batch
}

/// The `FormatText` records of a batch, with the node each is for.
fn format_records(batch: &[u8]) -> Vec<(u32, FormatOp, u16, Option<Paint>)> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::FormatText {
                node_id,
                op,
                value,
                paint,
            } => Some((node_id, op, value, paint)),
            _ => None,
        })
        .collect()
}

fn loads(batch: &[u8]) -> Vec<(u32, String)> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetRichText { node_id, text, .. } => Some((node_id, text.to_owned())),
            _ => None,
        })
        .collect()
}

#[test]
fn fr26_1_a_field_without_rich_writes_no_rich_text_property() {
    let (_host, _tree, batch) = start(|| {
        TextField!("hello", |_| {}, placeholder = "p", read_only = true);
    });
    for mutation in decode_batch(&batch).unwrap() {
        if let Mutation::SetProp { property, .. } = mutation {
            assert!(
                !matches!(
                    property,
                    PropertyKind::RichText
                        | PropertyKind::OnTextFormat
                        | PropertyKind::OnRichTextChanged
                ),
                "{property:?} was written for a field that is not rich"
            );
        }
    }
}

#[test]
fn fr26_1_a_rich_field_writes_the_property_and_its_two_listeners() {
    let (_host, tree, batch) = start(|| {
        let state = remember_rich_text_state();
        TextField!("hello", |_| {}, rich = &state);
    });
    let properties: Vec<_> = decode_batch(&batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                property, value, ..
            } => Some((property, value)),
            _ => None,
        })
        .collect();
    assert!(properties.iter().any(|(property, value)| {
        *property == PropertyKind::RichText
            && *value == compose_rust::protocol::PropertyValue::Bool(true)
    }));
    assert_eq!(
        tree.handled("TextField", PropertyKind::OnTextFormat).len(),
        1
    );
    assert_eq!(
        tree.handled("TextField", PropertyKind::OnRichTextChanged)
            .len(),
        1
    );
}

/// Whether a batch turns `RichText` on for a node.
fn turns_rich_text_on(batch: &[u8]) -> bool {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .any(|mutation| {
            matches!(
                mutation,
                Mutation::SetProp {
                    property: PropertyKind::RichText,
                    value: compose_rust::protocol::PropertyValue::Bool(true),
                    ..
                }
            )
        })
}

#[test]
fn fr26_1_a_handler_without_rich_turns_rich_text_on() {
    // The formatting at the selection only comes from a field that keeps formatting, so
    // asking to hear it is asking for a rich field. No state is bound, so the document,
    // which nobody hears, gets no listener.
    let (_host, tree, batch) = start(|| {
        TextField!("", |_| {}, on_format = Some(|_: TextFormat| {}));
    });
    assert!(
        turns_rich_text_on(&batch),
        "on_format alone is a rich field"
    );
    assert_eq!(
        tree.handled("TextField", PropertyKind::OnTextFormat).len(),
        1
    );
    assert!(
        tree.handled("TextField", PropertyKind::OnRichTextChanged)
            .is_empty()
    );

    // The same for the document.
    let (_host, tree, batch) = start(|| {
        TextField!("", |_| {}, on_rich_change = Some(|_: RichDocument| {}));
    });
    assert!(
        turns_rich_text_on(&batch),
        "on_rich_change alone is a rich field"
    );
    assert_eq!(
        tree.handled("TextField", PropertyKind::OnRichTextChanged)
            .len(),
        1
    );
    assert!(
        tree.handled("TextField", PropertyKind::OnTextFormat)
            .is_empty()
    );

    // A field with neither handler and no state stays plain.
    let (_host, _tree, batch) = start(|| {
        TextField!("", |_| {});
    });
    assert!(!turns_rich_text_on(&batch));
}

#[test]
fn fr26_1_format_writes_exactly_one_format_text_record_in_the_next_batch() {
    let (mut host, mut tree, first) = start(|| {
        let state = remember_rich_text_state();
        let bold = state.clone();
        Column!({
            TextField!("", |_| {}, rich = &state);
            Button!("Bold", move || bold.format(Format::Bold(Toggle::Flip)));
        });
    });
    assert!(format_records(&first).is_empty(), "nothing was asked yet");
    let (field, _) = tree.find("TextField", None, 0, PropertyKind::OnTextFormat);
    let batch = click(&mut host, &mut tree, "Bold");
    assert_eq!(
        format_records(&batch),
        vec![(field, FormatOp::Bold, 2, None)]
    );
    // The record's bytes: tag 42, length 20, the node, the operation, the value, no paint.
    let mut record = vec![42, 0, 20, 0];
    record.extend_from_slice(&field.to_le_bytes());
    record.extend_from_slice(&1u16.to_le_bytes());
    record.extend_from_slice(&2u16.to_le_bytes());
    record.extend_from_slice(&0u64.to_le_bytes());
    assert_eq!(
        batch
            .windows(20)
            .filter(|w| *w == record.as_slice())
            .count(),
        1
    );
    // The command went out once: the next batch has none.
    let again = click(&mut host, &mut tree, "Bold");
    assert_eq!(format_records(&again).len(), 1);
}

#[test]
fn fr26_1_commands_go_out_in_the_order_they_were_made() {
    let (mut host, mut tree, _) = start(|| {
        let state = remember_rich_text_state();
        let sheet = state.clone();
        Column!({
            TextField!("", |_| {}, rich = &state);
            Button!("Sheet", move || {
                sheet.format(Format::Paragraph(ParagraphRole::Heading));
                sheet.format(Format::Color(Some(Paint::Role(ColorRole::Primary))));
                sheet.format(Format::List(ListKind::Numbered));
                sheet.format(Format::Indent);
            });
        });
    });
    let batch = click(&mut host, &mut tree, "Sheet");
    let ops: Vec<_> = format_records(&batch)
        .into_iter()
        .map(|(_, op, value, paint)| (op, value, paint))
        .collect();
    assert_eq!(
        ops,
        vec![
            (FormatOp::Paragraph, 3, None),
            (FormatOp::Color, 0, Some(Paint::Role(ColorRole::Primary))),
            (FormatOp::List, 3, None),
            (FormatOp::Indent, 0, None),
        ]
    );
}

#[test]
fn fr26_1_a_command_made_before_the_field_is_composed_waits_for_it() {
    let (_host, _tree, first) = start(|| {
        let state = remember_rich_text_state();
        // Asked before the field that takes the state is composed.
        state.format(Format::Underline(Toggle::On));
        Column!({
            TextField!("", |_| {}, rich = &state);
        });
    });
    let records = format_records(&first);
    assert_eq!(records.len(), 1, "{records:?}");
    assert_eq!((records[0].1, records[0].2), (FormatOp::Underline, 1));
}

#[test]
fn fr26_1_a_state_bound_to_no_field_asks_for_nothing() {
    let (mut host, mut tree, first) = start(|| {
        let never = remember_rich_text_state();
        let ask = never.clone();
        Button!("Ask", move || ask.format(Format::Bold(Toggle::On)));
    });
    assert!(format_records(&first).is_empty());
    assert!(format_records(&click(&mut host, &mut tree, "Ask")).is_empty());
    let loose = RichTextState::new();
    loose.format(Format::Bold(Toggle::On));
    assert!(!loose.is_bound());
}

#[test]
fn fr26_1_load_refuses_an_invalid_document_and_sends_a_valid_one_once() {
    let valid = vector_document();
    let accepted = Arc::new(Mutex::new(Vec::new()));
    let seen = Arc::clone(&accepted);
    let (mut host, mut tree, _) = start(move || {
        let state = remember_rich_text_state();
        let bad = state.clone();
        let good = state.clone();
        let (valid, invalid) = (vector_document(), doc("한글", vec![bold(1, 2)], vec![]));
        let (seen_bad, seen_good) = (Arc::clone(&seen), Arc::clone(&seen));
        Column!({
            TextField!("", |_| {}, rich = &state);
            Button!("Bad", move || {
                seen_bad.lock().unwrap().push(bad.load(invalid.clone()));
            });
            Button!("Good", move || {
                seen_good.lock().unwrap().push(good.load(valid.clone()));
            });
        });
    });
    let refused = click(&mut host, &mut tree, "Bad");
    assert!(loads(&refused).is_empty(), "an invalid document was sent");
    let sent = click(&mut host, &mut tree, "Good");
    let (field, _) = tree.find("TextField", None, 0, PropertyKind::OnTextFormat);
    assert_eq!(loads(&sent), vec![(field, valid.text.clone())]);
    assert_eq!(*accepted.lock().unwrap(), vec![false, true]);
    // Asking again with the invalid one still sends nothing.
    let again = click(&mut host, &mut tree, "Bad");
    assert!(loads(&again).is_empty());
}

#[test]
fn fr26_1_a_state_keeps_no_more_than_the_cap_waiting_for_its_field() {
    // A state that is given to no field: nothing ever takes what it is asked, so everything
    // waits. Two documents of half the cap fill it exactly, and the third finds no room.
    let cap = RichTextState::MAX_PENDING_BYTES;
    assert_eq!(cap, 1024 * 1024);
    // A plain document is its 40 byte record and its text on the wire.
    let half = RichDocument::plain("a".repeat(cap / 2 - 40));
    let small = RichDocument::plain("b");
    let answers = Arc::new(Mutex::new(Vec::new()));
    let kept = Arc::new(Mutex::new(None));
    let (seen_answers, seen_kept) = (Arc::clone(&answers), Arc::clone(&kept));
    let expected = half.clone();
    let (mut host, mut tree, first) = start(move || {
        let state = remember_rich_text_state();
        let (half, small) = (half.clone(), small.clone());
        let (answers, kept) = (Arc::clone(&seen_answers), Arc::clone(&seen_kept));
        Button!("Fill", move || {
            let mut answers = answers.lock().unwrap();
            answers.push(state.load(half.clone()));
            answers.push(state.load(half.clone()));
            answers.push(state.load(small.clone()));
            // A command is dropped the same way, and says nothing by its return.
            state.format(Format::Bold(Toggle::On));
            *kept.lock().unwrap() = Some(state.document());
        });
    });
    assert!(loads(&first).is_empty());
    let batch = click(&mut host, &mut tree, "Fill");
    assert_eq!(*answers.lock().unwrap(), vec![true, true, false]);
    // The document the state answers with is the last one it accepted, not the one it
    // dropped.
    assert_eq!(kept.lock().unwrap().as_ref(), Some(&expected));
    // No field took any of it.
    assert!(loads(&batch).is_empty());
    assert!(format_records(&batch).is_empty());

    // A document larger than the cap is taken when nothing waits, so it can still be loaded.
    let large = RichDocument::plain("c".repeat(cap + 1));
    let taken = Arc::new(Mutex::new(Vec::new()));
    let seen = Arc::clone(&taken);
    let (mut host, mut tree, _) = start(move || {
        let state = remember_rich_text_state();
        let large = large.clone();
        let seen = Arc::clone(&seen);
        Button!("Large", move || {
            let mut seen = seen.lock().unwrap();
            seen.push(state.load(large.clone()));
            seen.push(state.load(RichDocument::plain("d")));
        });
    });
    click(&mut host, &mut tree, "Large");
    assert_eq!(*taken.lock().unwrap(), vec![true, false]);
}

#[test]
fn fr26_1_the_state_and_the_handlers_hear_the_two_events() {
    let formats = Arc::new(Mutex::new(Vec::new()));
    let documents = Arc::new(Mutex::new(Vec::new()));
    let (seen_formats, seen_documents) = (Arc::clone(&formats), Arc::clone(&documents));
    let (mut host, mut tree, _) = start(move || {
        let state = remember_rich_text_state();
        let (formats, documents) = (Arc::clone(&seen_formats), Arc::clone(&seen_documents));
        Column!({
            Text!(format!(
                "bold:{:?} chars:{}",
                state.format_at_selection().bold,
                state.document().characters.len()
            ));
            TextField!(
                "",
                |_| {},
                rich = &state,
                on_format = Some(move |format: TextFormat| formats.lock().unwrap().push(format)),
                on_rich_change = Some(move |document: RichDocument| {
                    documents.lock().unwrap().push(document)
                })
            );
        });
    });
    assert_eq!(tree.texts()[0], "bold:None chars:0");
    let (field, format_handler) = tree.find("TextField", None, 0, PropertyKind::OnTextFormat);
    let (_, document_handler) = tree.find("TextField", None, 0, PropertyKind::OnRichTextChanged);

    let format = compose_rust::codegen::text_format_vector();
    let (batch, _) = host
        .dispatch(HostEvent::new(
            field,
            format_handler,
            EventPayload::TextFormatAtSelection(format),
        ))
        .expect("the format is delivered");
    let batch = batch.to_vec();
    tree.apply(&batch);
    assert_eq!(*formats.lock().unwrap(), vec![format]);
    assert_eq!(tree.texts()[0], "bold:All chars:0");

    let document = vector_document();
    let (characters, paragraphs) = (document.encode_characters(), document.encode_paragraphs());
    let (batch, _) = host
        .dispatch(HostEvent::new(
            field,
            document_handler,
            EventPayload::RichTextChanged {
                text: &document.text,
                characters: &characters,
                paragraphs: &paragraphs,
            },
        ))
        .expect("the document is delivered");
    let batch = batch.to_vec();
    tree.apply(&batch);
    assert_eq!(*documents.lock().unwrap(), vec![document]);
    assert_eq!(tree.texts()[0], "bold:All chars:3");

    // A document that does not hold together is refused with a protocol error.
    let bad = doc("한글", vec![bold(1, 2)], vec![]);
    let (characters, paragraphs) = (bad.encode_characters(), bad.encode_paragraphs());
    let refused = host.dispatch(HostEvent::new(
        field,
        document_handler,
        EventPayload::RichTextChanged {
            text: &bad.text,
            characters: &characters,
            paragraphs: &paragraphs,
        },
    ));
    assert!(refused.is_err());
    assert_eq!(documents.lock().unwrap().len(), 1);
}
