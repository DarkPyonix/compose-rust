//! Editable rich text in a `TextField`: the two commands, the two events and the records
//! they carry.

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
