//! Widget tag 43: a code editor whose document the Renderer owns and the Host follows one
//! committed edit at a time. The records, the command and the events go through the wire
//! and back here; what a Dioxus component sends is tested in the adapter.

use compose_rust::code::{
    CodeChange, CodeRange, DECORATION_LEN, DECORATION_RECORD, Decoration, Decorations, FOLD_LEN,
    FOLD_RECORD, Fold, Folds, GUTTER_DECORATION_LEN, GUTTER_DECORATION_RECORD, GutterDecoration,
    GutterDecorations, Position, RecordFieldType, SYNTAX_SPAN_LEN, SYNTAX_SPAN_RECORD, SyntaxSpan,
    SyntaxSpans, byte_offset,
};
use compose_rust::protocol::{
    BatchEncoder, HostEvent, Mutation, PropertyValue, ProtocolError, decode_batch, decode_event,
    encode_event,
};
use compose_rust::schema::EVENT_SCHEMA;
use compose_rust::{
    ColorRole, DecorationKind, EventPayload, GutterDecorationKind, HoverPhase, Paint, PropertyKind,
    RevealAlign, Severity, WidgetKind,
};

/// One decoded record with its borrowed parts made owned, so the batch can be dropped.
#[derive(Clone, Debug, PartialEq)]
enum Record {
    Create(u32, WidgetKind),
    Prop(u32, PropertyKind, Value),
    EditCode {
        node_id: u32,
        request_id: u32,
        base_version: u32,
        range: CodeRange,
        text: String,
    },
    SetSelection {
        node_id: u32,
        base_version: u32,
        anchor: Position,
        active: Position,
    },
    RevealRange {
        node_id: u32,
        base_version: u32,
        range: CodeRange,
        align: RevealAlign,
    },
    SetFolded {
        node_id: u32,
        start_line: u32,
        collapsed: bool,
    },
    Other,
}

#[derive(Clone, Debug, PartialEq)]
enum Value {
    None,
    Text(String),
    Bool(bool),
    Integer(i64),
    Float(f32),
    Bytes(Vec<u8>),
}

fn records(batch: &[u8]) -> Vec<Record> {
    decode_batch(batch)
        .unwrap()
        .into_iter()
        .map(|mutation| match mutation {
            Mutation::Create { node_id, widget } => Record::Create(node_id, widget),
            Mutation::SetProp {
                node_id,
                property,
                value,
            } => Record::Prop(
                node_id,
                property,
                match value {
                    PropertyValue::None => Value::None,
                    PropertyValue::String(text) => Value::Text(text.to_string()),
                    PropertyValue::Bool(value) => Value::Bool(value),
                    PropertyValue::Integer(value) => Value::Integer(value),
                    PropertyValue::Float(value) => Value::Float(value),
                    PropertyValue::Bytes(bytes) => Value::Bytes(bytes.to_vec()),
                },
            ),
            Mutation::EditCode {
                node_id,
                request_id,
                base_version,
                start_line,
                start_column,
                end_line,
                end_column,
                text,
            } => Record::EditCode {
                node_id,
                request_id,
                base_version,
                range: CodeRange::of(start_line, start_column, end_line, end_column),
                text: text.to_string(),
            },
            Mutation::SetSelection {
                node_id,
                base_version,
                anchor_line,
                anchor_column,
                active_line,
                active_column,
            } => Record::SetSelection {
                node_id,
                base_version,
                anchor: Position::new(anchor_line, anchor_column),
                active: Position::new(active_line, active_column),
            },
            Mutation::RevealRange {
                node_id,
                base_version,
                start_line,
                start_column,
                end_line,
                end_column,
                align,
            } => Record::RevealRange {
                node_id,
                base_version,
                range: CodeRange::of(start_line, start_column, end_line, end_column),
                align,
            },
            Mutation::SetFolded {
                node_id,
                start_line,
                collapsed,
            } => Record::SetFolded {
                node_id,
                start_line,
                collapsed,
            },
            _ => Record::Other,
        })
        .collect()
}

fn round_trip(payload: EventPayload<'_>) -> (usize, HostEvent<'static>) {
    let event = HostEvent {
        node_id: 3,
        handler_id: 9,
        payload,
    };
    let mut wire = Vec::new();
    encode_event(&event, &mut wire).unwrap();
    let length = wire.len();
    let leaked: &'static [u8] = Box::leak(wire.into_boxed_slice());
    let decoded = decode_event(leaked).unwrap();
    assert_eq!(decoded, event);
    (length, decoded)
}

/// The tags are the ones the wire table gives, and a tag is never reused.
#[test]
fn fr38_code_editor_keeps_its_assigned_tags() {
    assert_eq!(WidgetKind::CodeEditor as u16, 43);
    assert_eq!(PropertyKind::Decorations as u16, 100);
    assert_eq!(PropertyKind::SyntaxSpans as u16, 101);
    assert_eq!(PropertyKind::TabWidth as u16, 102);
    assert_eq!(PropertyKind::OnEditRejected as u16, 103);
    assert_eq!(PropertyKind::OnHover as u16, 104);
    assert_eq!(PropertyKind::OnSave as u16, 105);
    assert_eq!(PropertyKind::OnDecorationClick as u16, 106);
    let tag = |name: &str| {
        EVENT_SCHEMA
            .iter()
            .find(|event| event.name == name)
            .unwrap_or_else(|| panic!("{name} is not in the event schema"))
            .tag
    };
    assert_eq!(tag("CodeChanged"), 26);
    assert_eq!(tag("CodeEditRejected"), 27);
    assert_eq!(tag("CodeHovered"), 28);
    assert_eq!(tag("CodeSaveRequested"), 29);
    assert_eq!(tag("DecorationActivated"), 30);
    assert_eq!(DecorationKind::Underline as u16, 1);
    assert_eq!(DecorationKind::CodeLens as u16, 2);
    assert_eq!(DecorationKind::HoverAnchor as u16, 3);
    assert_eq!(DecorationKind::GhostText as u16, 4);
    assert_eq!(Severity::Error as u16, 1);
    assert_eq!(Severity::Warning as u16, 2);
    assert_eq!(Severity::Information as u16, 3);
    assert_eq!(Severity::Hint as u16, 4);
    assert_eq!(HoverPhase::Rest as u16, 1);
    assert_eq!(HoverPhase::Leave as u16, 2);
}

/// The edit command is forty bytes with its header, carries the request id the rejection
/// echoes, and comes back from the batch exactly as it went in.
#[test]
fn fr38_edit_code_is_a_forty_byte_record_that_round_trips() {
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::EditCode {
            node_id: 5,
            request_id: 77,
            base_version: 3,
            start_line: 1,
            start_column: 2,
            end_line: 4,
            end_column: 6,
            text: "😀\n",
        })
        .unwrap();
    let batch = encoder.finish().unwrap().to_vec();
    // The envelope is twelve bytes, and the record follows it.
    assert_eq!(&batch[12..16], &[17, 0, 40, 0]);
    assert_eq!(
        records(&batch),
        vec![Record::EditCode {
            node_id: 5,
            request_id: 77,
            base_version: 3,
            range: CodeRange::of(1, 2, 4, 6),
            text: "😀\n".to_string(),
        }]
    );
}

/// Each of the five events has the length the wire table gives it and decodes to what was
/// encoded.
#[test]
fn fr38_the_five_code_events_round_trip_at_their_lengths() {
    let (length, _) = round_trip(EventPayload::CodeChanged {
        version: 4,
        start_line: 2,
        start_column: 3,
        end_line: 5,
        end_column: 1,
        text: "한😀",
    });
    assert_eq!(length, 44 + "한😀".len());
    let (length, _) = round_trip(EventPayload::CodeEditRejected {
        request_id: 8,
        base_version: 1,
        current_version: 6,
        start_line: 0,
        start_column: 1,
        end_line: 0,
        end_column: 2,
    });
    assert_eq!(length, 44);
    let (length, _) = round_trip(EventPayload::CodeHovered {
        decoration: u64::MAX - 1,
        line: 7,
        column: 9,
        phase: HoverPhase::Leave,
    });
    assert_eq!(length, 36);
    let (length, _) = round_trip(EventPayload::CodeSaveRequested { version: 12 });
    assert_eq!(length, 24);
    let (length, _) = round_trip(EventPayload::DecorationActivated { decoration: 41 });
    assert_eq!(length, 24);
}

/// The phase is a closed set. A value outside it is a protocol error, not a guess.
#[test]
fn fr38_an_unknown_hover_phase_is_a_protocol_error() {
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 1,
            handler_id: 2,
            payload: EventPayload::CodeHovered {
                decoration: 0,
                line: 0,
                column: 0,
                phase: HoverPhase::Rest,
            },
        },
        &mut wire,
    )
    .unwrap();
    wire[32] = 3;
    assert_eq!(decode_event(&wire), Err(ProtocolError::InvalidValueKind(3)));
    // A record shorter than its kind's length is refused rather than read short.
    let mut short = wire.clone();
    short[2] = 32;
    short.truncate(32);
    assert_eq!(
        decode_event(&short),
        Err(ProtocolError::InvalidRecordLength)
    );
}

/// A decoration is forty-four bytes and a colour run twenty-eight, and both come back
/// from their blobs as they were written, text included.
#[test]
fn fr38_decoration_and_syntax_span_records_have_their_wire_sizes_and_round_trip() {
    assert_eq!(DECORATION_LEN, 44);
    assert_eq!(SYNTAX_SPAN_LEN, 28);
    assert_eq!(usize::from(DECORATION_RECORD.length), DECORATION_LEN);
    assert_eq!(usize::from(SYNTAX_SPAN_RECORD.length), SYNTAX_SPAN_LEN);

    let decorations = vec![
        Decoration::underline(3, CodeRange::of(1, 8, 1, 9), Severity::Error),
        Decoration::underline(3, CodeRange::of(2, 0, 2, 4), Severity::Hint)
            .with_color(ColorRole::Tertiary),
        Decoration::code_lens(3, 0, "2 references", 41),
        Decoration::hover_anchor(3, CodeRange::of(0, 3, 0, 7), 42),
        Decoration::ghost_text(3, Position::new(4, 2), "ln!(\"한😀\")", 43),
    ];
    let encoded = Decorations::new(decorations.clone());
    let text_bytes = "2 references".len() + "ln!(\"한😀\")".len();
    assert_eq!(encoded.as_bytes().len(), 5 * DECORATION_LEN + text_bytes);
    assert_eq!(encoded.decorations(), decorations);

    let spans = vec![
        SyntaxSpan::role(3, CodeRange::of(0, 0, 0, 2), ColorRole::Primary),
        SyntaxSpan::new(
            3,
            CodeRange::of(1, 12, 1, 16),
            Paint::Role(ColorRole::Tertiary),
        ),
    ];
    let encoded = SyntaxSpans::new(spans.clone());
    assert_eq!(encoded.as_bytes().len(), 2 * SYNTAX_SPAN_LEN);
    assert_eq!(encoded.spans(), spans);
}

/// The layout codegen hands the Renderer is the layout the encoder writes: every field is
/// read at the offset the schema names and gives back what was put in.
#[test]
fn fr38_the_record_schema_is_the_layout_the_encoder_writes() {
    let decoration = Decoration::ghost_text(9, Position::new(3, 4), "abc", 0x0102_0304_0506_0708)
        .with_color(ColorRole::Error);
    let encoded = Decorations::new([decoration.clone()]);
    let bytes = encoded.as_bytes();
    let read = |offset: u16, width: usize| -> u64 {
        let at = usize::from(offset);
        let mut value = 0_u64;
        for (index, byte) in bytes[at..at + width].iter().enumerate() {
            value |= u64::from(*byte) << (8 * index);
        }
        value
    };
    for field in DECORATION_RECORD.fields {
        let expected = match field.name {
            "version" => 9,
            "kind" => DecorationKind::GhostText as u64,
            "severity" => 0,
            "color" => ColorRole::Error as u64,
            "startLine" | "endLine" => 3,
            "startColumn" | "endColumn" => 4,
            "id" => 0x0102_0304_0506_0708,
            "text" => DECORATION_LEN as u64,
            other => panic!("the decoration schema has a field this test does not know: {other}"),
        };
        let width = match field.ty {
            RecordFieldType::Role { .. } => 2,
            RecordFieldType::U32 | RecordFieldType::Flag | RecordFieldType::Text => 4,
            RecordFieldType::U64 | RecordFieldType::Paint => 8,
        };
        assert_eq!(read(field.offset, width), expected, "{}", field.name);
    }
    // The text's length follows its offset.
    assert_eq!(read(40, 4), 3);
    assert_eq!(&bytes[DECORATION_LEN..], b"abc");

    let span = SyntaxSpan::role(2, CodeRange::of(5, 6, 7, 8), ColorRole::Primary);
    let encoded = SyntaxSpans::new([span]);
    let bytes = encoded.as_bytes();
    for field in SYNTAX_SPAN_RECORD.fields {
        let at = usize::from(field.offset);
        let expected = match field.name {
            "version" => 2,
            "startLine" => 5,
            "startColumn" => 6,
            "endLine" => 7,
            "endColumn" => 8,
            "paint" => Paint::Role(ColorRole::Primary).to_bits(),
            other => panic!("the span schema has a field this test does not know: {other}"),
        };
        let width = if field.ty == RecordFieldType::Paint {
            8
        } else {
            4
        };
        let mut value = 0_u64;
        for (index, byte) in bytes[at..at + width].iter().enumerate() {
            value |= u64::from(*byte) << (8 * index);
        }
        assert_eq!(value, expected, "{}", field.name);
    }
}

/// Columns are UTF-16 units and a line ends at `\n`, `\r\n` or a lone `\r`, the language
/// server's rules. A column that would split an emoji in two is not a place in the text.
#[test]
fn fr38_positions_count_utf16_units_and_every_line_break() {
    let text = "a😀b\r\n한c\rlast";
    assert_eq!(byte_offset(text, Position::new(0, 0)), Some(0));
    assert_eq!(byte_offset(text, Position::new(0, 1)), Some(1));
    assert_eq!(byte_offset(text, Position::new(0, 2)), None);
    assert_eq!(byte_offset(text, Position::new(0, 3)), Some(5));
    assert_eq!(byte_offset(text, Position::new(0, 4)), Some(6));
    assert_eq!(byte_offset(text, Position::new(0, 5)), None);
    assert_eq!(byte_offset(text, Position::new(1, 0)), Some(8));
    assert_eq!(byte_offset(text, Position::new(1, 1)), Some(11));
    assert_eq!(byte_offset(text, Position::new(1, 2)), Some(12));
    assert_eq!(byte_offset(text, Position::new(2, 4)), Some(text.len()));
    assert_eq!(byte_offset(text, Position::new(3, 0)), None);
}

/// Criterion 2, from the Host's side: starting from version 0 and applying every change
/// in the order it arrived gives the document the reader has, through typing, a pasted
/// block, an undo and a deletion across lines, with an emoji in the way.
#[test]
fn fr38_applying_every_change_to_version_zero_gives_the_document() {
    let mut document = String::from("fn main() {\n    let x = \"😀\";\n}\n");
    let changes = [
        // Typing after the emoji: its two units come before the column.
        CodeChange {
            version: 1,
            range: CodeRange::of(1, 15, 1, 15),
            text: "!".into(),
        },
        // A pasted block across two new lines.
        CodeChange {
            version: 2,
            range: CodeRange::of(2, 0, 2, 0),
            text: "    // one\n    // two\n".into(),
        },
        // Undo of the typing, as the editor reports it: the inverse replacement.
        CodeChange {
            version: 3,
            range: CodeRange::of(1, 15, 1, 16),
            text: String::new(),
        },
        // A deletion across lines.
        CodeChange {
            version: 4,
            range: CodeRange::of(1, 17, 3, 4),
            text: " ".into(),
        },
    ];
    for change in &changes {
        assert!(change.apply_to(&mut document), "{change:?}");
    }
    assert_eq!(document, "fn main() {\n    let x = \"😀\"; // two\n}\n");
    // A change that does not fit the copy says so and changes nothing.
    let stale = CodeChange {
        version: 5,
        range: CodeRange::of(9, 0, 9, 0),
        text: "x".into(),
    };
    assert!(!stale.apply_to(&mut document));
    assert_eq!(document, "fn main() {\n    let x = \"😀\"; // two\n}\n");
}

fn event_tag(name: &str) -> u16 {
    EVENT_SCHEMA
        .iter()
        .find(|event| event.name == name)
        .unwrap_or_else(|| panic!("{name} is not in the event schema"))
        .tag
}

/// The additions keep the tags the wire table gives them, and the numbers that are other
/// requirements' are left alone.
#[test]
fn fr38_editor_events_keep_their_assigned_tags() {
    assert_eq!(PropertyKind::Folds as u16, 107);
    assert_eq!(PropertyKind::GutterDecorations as u16, 108);
    assert_eq!(PropertyKind::OnSelectionChange as u16, 109);
    assert_eq!(PropertyKind::OnVisibleRangeChange as u16, 130);
    assert_eq!(PropertyKind::OnEditorFocusChange as u16, 131);
    assert_eq!(PropertyKind::OnFoldChange as u16, 132);
    assert_eq!(PropertyKind::OnCompositionChange as u16, 133);
    assert_eq!(event_tag("SelectionChanged"), 34);
    assert_eq!(event_tag("VisibleRangeChanged"), 35);
    assert_eq!(event_tag("EditorFocusChanged"), 36);
    assert_eq!(event_tag("FoldChanged"), 37);
    assert_eq!(event_tag("CompositionChanged"), 38);
    assert_eq!(GutterDecorationKind::Dot as u16, 1);
    assert_eq!(GutterDecorationKind::Bar as u16, 2);
    assert_eq!(GutterDecorationKind::Glyph as u16, 3);
    assert_eq!(RevealAlign::Nearest as u16, 1);
    assert_eq!(RevealAlign::Center as u16, 2);
    assert_eq!(RevealAlign::Top as u16, 3);
}

/// `SetSelection` is 28 bytes with its header, `RevealRange` 32 and `SetFolded` 16, and
/// each comes back from the batch as it went in.
#[test]
fn fr38_set_selection_and_reveal_range_apply_records_round_trip() {
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetSelection {
            node_id: 5,
            base_version: 3,
            anchor_line: 1,
            anchor_column: 2,
            active_line: 4,
            active_column: 6,
        })
        .unwrap();
    encoder
        .encode(&Mutation::RevealRange {
            node_id: 5,
            base_version: 4,
            start_line: 90,
            start_column: 0,
            end_line: 91,
            end_column: 7,
            align: RevealAlign::Center,
        })
        .unwrap();
    encoder
        .encode(&Mutation::SetFolded {
            node_id: 5,
            start_line: 12,
            collapsed: true,
        })
        .unwrap();
    let batch = encoder.finish().unwrap().to_vec();
    assert_eq!(&batch[12..16], &[20, 0, 28, 0]);
    assert_eq!(&batch[12 + 28..12 + 28 + 4], &[21, 0, 32, 0]);
    assert_eq!(&batch[12 + 28 + 32..12 + 28 + 32 + 4], &[22, 0, 16, 0]);
    assert_eq!(
        records(&batch),
        vec![
            Record::SetSelection {
                node_id: 5,
                base_version: 3,
                anchor: Position::new(1, 2),
                active: Position::new(4, 6),
            },
            Record::RevealRange {
                node_id: 5,
                base_version: 4,
                range: CodeRange::of(90, 0, 91, 7),
                align: RevealAlign::Center,
            },
            Record::SetFolded {
                node_id: 5,
                start_line: 12,
                collapsed: true,
            },
        ]
    );
}

/// An alignment or a collapsed word outside the closed set is refused as a protocol error
/// rather than guessed at, and a record of the wrong length is refused whole.
#[test]
fn fr38_a_reveal_align_or_a_fold_flag_outside_its_set_is_a_protocol_error() {
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::RevealRange {
            node_id: 5,
            base_version: 0,
            start_line: 0,
            start_column: 0,
            end_line: 0,
            end_column: 1,
            align: RevealAlign::Top,
        })
        .unwrap();
    let mut batch = encoder.finish().unwrap().to_vec();
    // The alignment is the last word of the record.
    batch[12 + 28] = 9;
    assert!(matches!(
        decode_batch(&batch),
        Err(ProtocolError::InvalidValueKind(9))
    ));

    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetFolded {
            node_id: 5,
            start_line: 1,
            collapsed: false,
        })
        .unwrap();
    let mut batch = encoder.finish().unwrap().to_vec();
    batch[12 + 4 + 8] = 2;
    assert!(matches!(
        decode_batch(&batch),
        Err(ProtocolError::InvalidValueKind(2))
    ));
}

/// Each of the five events has the length the wire table gives it and decodes to what was
/// encoded.
#[test]
fn fr38_selection_change_reaches_the_host_at_its_lengths() {
    let (length, _) = round_trip(EventPayload::SelectionChanged {
        version: 7,
        anchor_line: 1,
        anchor_column: 4,
        active_line: 300,
        active_column: 0,
    });
    assert_eq!(length, 36);
    let (length, _) = round_trip(EventPayload::VisibleRangeChanged {
        version: 7,
        first_line: 40_000,
        last_line: 40_052,
    });
    assert_eq!(length, 28);
    let (length, _) = round_trip(EventPayload::EditorFocusChanged { focused: true });
    assert_eq!(length, 24);
    let (length, _) = round_trip(EventPayload::FoldChanged {
        start_line: 12,
        collapsed: false,
    });
    assert_eq!(length, 24);
    let (length, _) = round_trip(EventPayload::CompositionChanged { composing: true });
    assert_eq!(length, 24);
}

/// The composition event is a flag and a pad. There is nowhere in it for the text an input
/// method is building, so that text cannot reach the Host through it.
#[test]
fn fr38_composition_signal_carries_no_text() {
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 1,
            handler_id: 2,
            payload: EventPayload::CompositionChanged { composing: true },
        },
        &mut wire,
    )
    .unwrap();
    assert_eq!(wire.len(), 24);
    assert_eq!(&wire[16..], &[1, 0, 0, 0, 0, 0, 0, 0]);
    // A longer record, which is the only way text could ride along, is not this event.
    let mut longer = wire.clone();
    longer.extend_from_slice(b"han");
    longer[2] = 27;
    assert_eq!(
        decode_event(&longer),
        Err(ProtocolError::InvalidRecordLength)
    );
}

/// The flags are closed. Two is neither focused nor not, and a short record is not read.
#[test]
fn fr38_an_unknown_focus_composition_or_fold_flag_is_a_protocol_error() {
    let cases: [(EventPayload<'static>, usize); 3] = [
        (EventPayload::EditorFocusChanged { focused: true }, 16),
        (EventPayload::CompositionChanged { composing: true }, 16),
        (
            EventPayload::FoldChanged {
                start_line: 3,
                collapsed: true,
            },
            20,
        ),
    ];
    for (payload, at) in cases {
        let mut wire = Vec::new();
        encode_event(
            &HostEvent {
                node_id: 1,
                handler_id: 2,
                payload,
            },
            &mut wire,
        )
        .unwrap();
        wire[at] = 2;
        assert_eq!(decode_event(&wire), Err(ProtocolError::InvalidValueKind(2)));
        wire.truncate(20);
        wire[2] = 20;
        assert_eq!(decode_event(&wire), Err(ProtocolError::InvalidRecordLength));
    }
}

/// A fold is sixteen bytes and a gutter mark twenty-eight, and both come back from their
/// blobs as they were written.
#[test]
fn fr38_fold_and_gutter_records_have_their_wire_sizes_and_round_trip() {
    assert_eq!(FOLD_LEN, 16);
    assert_eq!(GUTTER_DECORATION_LEN, 28);
    assert_eq!(usize::from(FOLD_RECORD.length), FOLD_LEN);
    assert_eq!(
        usize::from(GUTTER_DECORATION_RECORD.length),
        GUTTER_DECORATION_LEN
    );

    let folds = vec![Fold::new(2, 0, 9), Fold::new(2, 3, 5).collapsed()];
    let encoded = Folds::new(folds.clone());
    assert_eq!(encoded.as_bytes().len(), 2 * FOLD_LEN);
    assert_eq!(encoded.folds(), folds);

    let marks = vec![
        GutterDecoration::dot(2, 4, 0),
        GutterDecoration::bar(2, 5, 90).with_color(ColorRole::Tertiary),
        GutterDecoration::glyph(2, 6, '\u{1F41B}', 91),
    ];
    let encoded = GutterDecorations::new(marks.clone());
    assert_eq!(encoded.as_bytes().len(), 3 * GUTTER_DECORATION_LEN);
    assert_eq!(encoded.decorations(), marks);
    assert_eq!(encoded.as_bytes()[8], GutterDecorationKind::Dot as u8);
}

/// The layouts codegen hands the Renderer are the layouts the encoders write.
#[test]
fn fr38_the_fold_and_gutter_schemas_are_the_layout_the_encoders_write() {
    let fold = Folds::new([Fold::new(5, 6, 7).collapsed()]);
    let bytes = fold.as_bytes();
    for field in FOLD_RECORD.fields {
        let at = usize::from(field.offset);
        let value = u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]]);
        let expected = match field.name {
            "version" => 5,
            "startLine" => 6,
            "endLine" => 7,
            "collapsed" => 1,
            other => panic!("the fold schema has a field this test does not know: {other}"),
        };
        assert_eq!(value, expected, "{}", field.name);
    }

    let mark = GutterDecorations::new([
        GutterDecoration::glyph(9, 3, 'x', 0x0102_0304_0506_0708).with_color(ColorRole::Error)
    ]);
    let bytes = mark.as_bytes();
    for field in GUTTER_DECORATION_RECORD.fields {
        let at = usize::from(field.offset);
        let width = match field.ty {
            RecordFieldType::Role { .. } => 2,
            RecordFieldType::U32 => 4,
            RecordFieldType::U64 => 8,
            other => panic!("unexpected gutter field type {other:?}"),
        };
        let mut value = 0_u64;
        for (index, byte) in bytes[at..at + width].iter().enumerate() {
            value |= u64::from(*byte) << (8 * index);
        }
        let expected = match field.name {
            "version" => 9,
            "line" => 3,
            "kind" => GutterDecorationKind::Glyph as u64,
            "color" => ColorRole::Error as u64,
            "id" => 0x0102_0304_0506_0708,
            "glyph" => u64::from('x'),
            other => panic!("the gutter schema has a field this test does not know: {other}"),
        };
        assert_eq!(value, expected, "{}", field.name);
    }
}

/// What an application writes through the batch for the three commands is the record the
/// wire table gives, so a component never builds the mutation by hand.
#[test]
fn fr38_set_selection_and_reveal_range_apply_through_the_batch_methods() {
    let mut batch = compose_rust::Batch::new();
    batch.begin();
    batch.set_selection(4, 2, Position::new(3, 1), Position::new(3, 9));
    batch.reveal_range(4, 2, CodeRange::of(40, 0, 41, 2), RevealAlign::Top);
    batch.set_folded(4, 6, false);
    let bytes = batch.finish().unwrap().to_vec();
    assert_eq!(
        records(&bytes),
        vec![
            Record::SetSelection {
                node_id: 4,
                base_version: 2,
                anchor: Position::new(3, 1),
                active: Position::new(3, 9),
            },
            Record::RevealRange {
                node_id: 4,
                base_version: 2,
                range: CodeRange::of(40, 0, 41, 2),
                align: RevealAlign::Top,
            },
            Record::SetFolded {
                node_id: 4,
                start_line: 6,
                collapsed: false,
            },
        ]
    );
}

/// The two lists go to the editor as the properties the table gives them, and the events a
/// listener hears are the typed values the Host reads back, not raw words.
#[test]
fn fr38_gutter_decorations_follow_edits_and_activate_on_the_wire() {
    let marks = GutterDecorations::new([GutterDecoration::dot(3, 7, 90)]);
    let folds = Folds::new([Fold::new(3, 7, 12)]);
    let mut encoder = BatchEncoder::default();
    for (property, bytes) in [
        (PropertyKind::GutterDecorations, marks.as_bytes()),
        (PropertyKind::Folds, folds.as_bytes()),
    ] {
        encoder
            .encode(&Mutation::SetProp {
                node_id: 2,
                property,
                value: PropertyValue::Bytes(bytes),
            })
            .unwrap();
    }
    let batch = encoder.finish().unwrap().to_vec();
    assert_eq!(
        records(&batch),
        vec![
            Record::Prop(
                2,
                PropertyKind::GutterDecorations,
                Value::Bytes(marks.as_bytes().to_vec())
            ),
            Record::Prop(
                2,
                PropertyKind::Folds,
                Value::Bytes(folds.as_bytes().to_vec())
            ),
        ]
    );
    // A press on a mark comes back as the activation of the id the application gave it.
    let (length, _) = round_trip(EventPayload::DecorationActivated { decoration: 90 });
    assert_eq!(length, 24);
}
