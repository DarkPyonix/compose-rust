//! Widget tag 43: a code editor whose document the Renderer owns and the Host follows one
//! committed edit at a time. The records, the command and the events go through the wire
//! and back here; what a Dioxus component sends is tested in the adapter.

use compose_rust::code::{
    CODE_EDIT_LEN, CODE_EDITOR_COLORS_LEN, CODE_EDITOR_COLORS_RECORD, CaretRect, CodeChange,
    CodeEditorColors, CodePointer, CodeRange, CodeRect, DECORATION_LEN, DECORATION_RECORD,
    Decoration, Decorations, EditGroupResult, FOLD_LEN, FOLD_RECORD, Fold, Folds,
    GUTTER_DECORATION_LEN, GUTTER_DECORATION_RECORD, GutterDecoration, GutterDecorations, Position,
    PositionAnswer, RecordFieldType, SYNTAX_SPAN_LEN, SYNTAX_SPAN_RECORD, SyntaxSpan, SyntaxSpans,
    byte_offset, decode_edit_group, encode_edit_group, position_query,
};
use compose_rust::protocol::{
    BatchEncoder, HostEvent, Mutation, PropertyValue, ProtocolError, decode_batch, decode_event,
    encode_event,
};
use compose_rust::schema::EVENT_SCHEMA;
use compose_rust::{
    ColorRole, DecorationKind, EditGroupStatus, EventPayload, GutterDecorationKind, HoverPhase,
    Paint, PropertyKind, RevealAlign, Severity, WidgetKind,
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
    SetScrollOffset {
        node_id: u32,
        offset_px: f64,
    },
    QueryPositionRect {
        node_id: u32,
        request: u32,
        position: Position,
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
            Mutation::SetScrollOffset { node_id, offset_px } => {
                Record::SetScrollOffset { node_id, offset_px }
            }
            Mutation::QueryPositionRect {
                node_id,
                request,
                line,
                column,
            } => Record::QueryPositionRect {
                node_id,
                request,
                position: Position::new(line, column),
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

/// The look properties, the two listeners, the two events and the scroll command keep the
/// numbers they were given, after the ones other work has taken.
#[test]
fn fr38_editor_look_and_scroll_keep_their_assigned_tags() {
    assert_eq!(PropertyKind::LineNumbers as u16, 178);
    assert_eq!(PropertyKind::GutterMinWidthDp as u16, 179);
    assert_eq!(PropertyKind::GutterPaddingDp as u16, 180);
    assert_eq!(PropertyKind::TextInsetDp as u16, 181);
    assert_eq!(PropertyKind::CodeEditorColors as u16, 182);
    assert_eq!(PropertyKind::OnCodeMetrics as u16, 183);
    assert_eq!(PropertyKind::OnCodeScrolled as u16, 184);
    assert_eq!(event_tag("CodeMetrics"), 54);
    assert_eq!(event_tag("CodeScrolled"), 55);
    // A front end names a property the way the schema descriptor spells it.
    assert_eq!(
        PropertyKind::from_name("gutter_min_width_dp"),
        Ok(PropertyKind::GutterMinWidthDp)
    );
    assert_eq!(
        PropertyKind::from_name("code_editor_colors"),
        Ok(PropertyKind::CodeEditorColors)
    );
}

/// The type and the row height an application gives an editor travel as the properties a
/// `Text` uses, and the gutter's as the editor's own, each with the value it was given.
#[test]
fn fr38_editor_type_and_line_height_round_trip_as_properties() {
    let props = [
        (PropertyKind::FontSize, PropertyValue::Float(13.0)),
        (PropertyKind::LineHeight, PropertyValue::Float(20.0)),
        (PropertyKind::LineNumbers, PropertyValue::Bool(false)),
        (PropertyKind::GutterMinWidthDp, PropertyValue::Float(56.0)),
        (PropertyKind::GutterPaddingDp, PropertyValue::Float(12.0)),
        (PropertyKind::TextInsetDp, PropertyValue::Float(4.0)),
    ];
    let mut encoder = BatchEncoder::default();
    for (property, value) in props.iter().cloned() {
        encoder
            .encode(&Mutation::SetProp {
                node_id: 2,
                property,
                value,
            })
            .unwrap();
    }
    let batch = encoder.finish().unwrap().to_vec();
    assert_eq!(
        records(&batch),
        vec![
            Record::Prop(2, PropertyKind::FontSize, Value::Float(13.0)),
            Record::Prop(2, PropertyKind::LineHeight, Value::Float(20.0)),
            Record::Prop(2, PropertyKind::LineNumbers, Value::Bool(false)),
            Record::Prop(2, PropertyKind::GutterMinWidthDp, Value::Float(56.0)),
            Record::Prop(2, PropertyKind::GutterPaddingDp, Value::Float(12.0)),
            Record::Prop(2, PropertyKind::TextInsetDp, Value::Float(4.0)),
        ]
    );
}

/// The colour record is twelve bytes, one role in each of five halves and a reserved pair,
/// at the offsets the schema the Renderer's decoder is written from gives. A slot nobody
/// set is zero, which is how it stays the design system's.
#[test]
fn fr38_editor_colors_override_the_design_system_on_the_wire() {
    let colors = CodeEditorColors::new()
        .container(ColorRole::Surface)
        .gutter(ColorRole::Background)
        .current_line_number(ColorRole::Primary);
    let bytes = colors.to_bytes();
    assert_eq!(bytes.len(), CODE_EDITOR_COLORS_LEN);
    assert_eq!(CODE_EDITOR_COLORS_RECORD.length as usize, bytes.len());
    for field in CODE_EDITOR_COLORS_RECORD.fields {
        let at = field.offset as usize;
        let value = u16::from_le_bytes([bytes[at], bytes[at + 1]]);
        let expected = match field.name {
            "container" => ColorRole::Surface as u16,
            "gutter" => ColorRole::Background as u16,
            "lineNumber" => 0,
            "currentLineNumber" => ColorRole::Primary as u16,
            "currentLine" => 0,
            other => panic!("the colour schema has a field this test does not know: {other}"),
        };
        assert_eq!(value, expected, "{}", field.name);
        assert_eq!(
            field.ty,
            RecordFieldType::Role {
                name: "ColorRole",
                optional: true
            }
        );
    }
    assert_eq!(&bytes[10..], &[0, 0]);
    assert_eq!(CodeEditorColors::from_bytes(&bytes), Some(colors));
    assert!(CodeEditorColors::new().is_empty());
    assert!(!colors.is_empty());

    // A role this side does not know, or a record cut short, is refused whole.
    let mut unknown = bytes;
    unknown[0..2].copy_from_slice(&u16::MAX.to_le_bytes());
    assert_eq!(CodeEditorColors::from_bytes(&unknown), None);
    assert_eq!(CodeEditorColors::from_bytes(&bytes[..10]), None);

    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetProp {
            node_id: 2,
            property: PropertyKind::CodeEditorColors,
            value: PropertyValue::Bytes(&bytes),
        })
        .unwrap();
    let batch = encoder.finish().unwrap().to_vec();
    assert_eq!(
        records(&batch),
        vec![Record::Prop(
            2,
            PropertyKind::CodeEditorColors,
            Value::Bytes(bytes.to_vec())
        )]
    );
}

/// The row height comes back as one `f64` of pixels in a 24 byte record, with the fraction
/// a density that is not a whole number leaves.
#[test]
fn fr38_editor_reports_line_height_on_the_wire() {
    let (length, decoded) = round_trip(EventPayload::CodeMetrics {
        line_height_px: 32.5,
    });
    assert_eq!(length, 24);
    assert_eq!(
        decoded.payload,
        EventPayload::CodeMetrics {
            line_height_px: 32.5
        }
    );

    // A record of another length under the same tag is refused, not read short.
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 3,
            handler_id: 9,
            payload: EventPayload::CodeMetrics {
                line_height_px: 20.0,
            },
        },
        &mut wire,
    )
    .unwrap();
    assert_eq!(&wire[0..4], &[54, 0, 24, 0]);
    wire[2] = 20;
    assert_eq!(
        decode_event(&wire[..20]),
        Err(ProtocolError::InvalidRecordLength)
    );
}

/// What one editor reports as its scroll offset is, bit for bit, what the command gives
/// another: the event and the command carry the same `f64` of pixels.
#[test]
fn fr38_editor_scroll_offset_round_trips_on_the_wire() {
    let offset_px = 123_456.75;
    let (length, decoded) = round_trip(EventPayload::CodeScrolled { offset_px });
    assert_eq!(length, 24);
    let EventPayload::CodeScrolled {
        offset_px: reported,
    } = decoded.payload
    else {
        panic!("a scroll event decoded as {:?}", decoded.payload);
    };

    let mut batch = compose_rust::Batch::new();
    batch.begin();
    batch.set_scroll_offset(4, reported);
    let bytes = batch.finish().unwrap().to_vec();
    // The command is tag 30 and 16 bytes with its header.
    assert_eq!(&bytes[12..16], &[30, 0, 16, 0]);
    assert_eq!(
        records(&bytes),
        vec![Record::SetScrollOffset {
            node_id: 4,
            offset_px
        }]
    );

    // Cut short, the record is refused rather than read as a smaller offset.
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetScrollOffset {
            node_id: 4,
            offset_px: 1.0,
        })
        .unwrap();
    let mut short = encoder.finish().unwrap().to_vec();
    short[14] = 12;
    assert!(decode_batch(&short).is_err());
}

/// The caret report, the position query and its answer keep the tags the wire table gives
/// them, past the numbers other requirements hold.
#[test]
fn fr38_caret_and_position_rects_keep_their_assigned_tags() {
    assert_eq!(PropertyKind::OnCaretRect as u16, 195);
    assert_eq!(event_tag("CaretRect"), 64);
    assert_eq!(event_tag("PositionRect"), 65);
    assert_eq!(
        PropertyKind::from_name("on_caret_rect"),
        Ok(PropertyKind::OnCaretRect)
    );
}

/// The caret comes back as two words and four floats in a 40 byte record, read into the
/// place and the rectangle. A record of another length under the same tag is refused.
#[test]
fn fr38_editor_reports_the_caret_rect_on_the_wire() {
    let rect = CodeRect::new(120.5, 64.25, 2.0, 20.0);
    let (length, decoded) = round_trip(EventPayload::CaretRect {
        line: 2,
        column: 4,
        rect,
    });
    assert_eq!(length, 40);
    assert_eq!(
        CaretRect::from_payload(&decoded.payload),
        Some(CaretRect {
            position: Position::new(2, 4),
            rect,
        })
    );
    assert_eq!(rect.bottom(), 84.25);
    assert_eq!(rect.right(), 122.5);
    assert_eq!(
        CaretRect::from_payload(&EventPayload::CodeSaveRequested { version: 1 }),
        None
    );

    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 3,
            handler_id: 9,
            payload: EventPayload::CaretRect {
                line: 0,
                column: 0,
                rect,
            },
        },
        &mut wire,
    )
    .unwrap();
    assert_eq!(&wire[0..4], &[64, 0, 40, 0]);
    wire[2] = 36;
    assert_eq!(
        decode_event(&wire[..36]),
        Err(ProtocolError::InvalidRecordLength)
    );
}

thread_local! {
    static ANSWERS: std::cell::RefCell<Vec<(&'static str, PositionAnswer)>> =
        const { std::cell::RefCell::new(Vec::new()) };
}

#[compose_rust::runtime::Composable]
fn Nothing() {
    compose_rust::ui::Text("editor");
}

/// The query is a 20 byte command written with the request number a callback was given,
/// the answer a 40 byte event with a word that says whether the position is on screen, and
/// through a Host the answer reaches its own callback once.
#[test]
fn fr38_editor_answers_position_rects_on_the_wire() {
    ANSWERS.with_borrow_mut(Vec::clear);
    let near = position_query(|answer| ANSWERS.with_borrow_mut(|all| all.push(("near", answer))));
    let far = position_query(|answer| ANSWERS.with_borrow_mut(|all| all.push(("far", answer))));
    assert_ne!(near, 0, "0 is never a request number");
    assert_ne!(near, far);

    let mut batch = compose_rust::Batch::new();
    batch.begin();
    batch.query_position_rect(4, near, Position::new(1, 8));
    let bytes = batch.finish().unwrap().to_vec();
    assert_eq!(&bytes[12..16], &[39, 0, 20, 0]);
    assert_eq!(
        records(&bytes),
        vec![Record::QueryPositionRect {
            node_id: 4,
            request: near,
            position: Position::new(1, 8),
        }]
    );
    let mut short = bytes.clone();
    short[14] = 16;
    assert!(decode_batch(&short).is_err());

    let rect = CodeRect::new(88.75, 64.25, 2.0, 20.0);
    let (length, _) = round_trip(EventPayload::PositionRect {
        request: near,
        rect: Some(rect),
    });
    assert_eq!(length, 40);
    let (length, decoded) = round_trip(EventPayload::PositionRect {
        request: far,
        rect: None,
    });
    assert_eq!(length, 40);
    assert_eq!(
        decoded.payload,
        EventPayload::PositionRect {
            request: far,
            rect: None
        }
    );
    // The word is 0 or 1, and anything else is a record the two sides disagree on.
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 4,
            handler_id: 0,
            payload: EventPayload::PositionRect {
                request: far,
                rect: Some(rect),
            },
        },
        &mut wire,
    )
    .unwrap();
    assert_eq!(&wire[20..24], &[1, 0, 0, 0]);
    wire[20] = 2;
    assert_eq!(decode_event(&wire), Err(ProtocolError::InvalidValueKind(2)));

    // Through a Host: each answer goes to the callback that asked, once.
    let mut host = compose_rust::ComposeHost::with_theme(
        Nothing,
        compose_rust::schema::Theme::unified(compose_rust::schema::DesignSystem::Material3),
    );
    host.rebuild().expect("the first batch encodes");
    // Made after the Host, which forgets every query a Host before it left waiting.
    let near = position_query(|answer| ANSWERS.with_borrow_mut(|all| all.push(("near", answer))));
    let far = position_query(|answer| ANSWERS.with_borrow_mut(|all| all.push(("far", answer))));
    let answer = |request: u32, rect: Option<CodeRect>| HostEvent {
        node_id: 4,
        handler_id: 0,
        payload: EventPayload::PositionRect { request, rect },
    };
    host.dispatch(answer(far, None))
        .expect("the answer dispatches");
    host.dispatch(answer(near, Some(rect)))
        .expect("the answer dispatches");
    host.dispatch(answer(near, None))
        .expect("an answer nobody waits for is ignored");
    assert_eq!(
        ANSWERS.with_borrow(Clone::clone),
        vec![("far", None), ("near", Some(rect))]
    );
    // An answer is addressed to no handler.
    assert!(
        host.dispatch(HostEvent {
            node_id: 4,
            handler_id: 7,
            payload: EventPayload::PositionRect {
                request: far,
                rect: None
            },
        })
        .is_err()
    );
}

/// The key preview is the editor's own handler property, past the caret's, and what it
/// hears is the `KeyDown` record a key handler hears: the same 32 bytes and tag, so a Host
/// reads a previewed key the way it reads any other and answers it with the same result.
#[test]
fn fr38_editor_key_preview_keeps_its_tag_and_the_key_down_record() {
    assert_eq!(PropertyKind::OnKeyPreview as u16, 196);
    assert_eq!(
        PropertyKind::from_name("on_key_preview"),
        Ok(PropertyKind::OnKeyPreview)
    );
    let (length, decoded) = round_trip(EventPayload::KeyDown {
        key: compose_rust::Key::ArrowDown,
        shift_key: false,
        ctrl_key: true,
        alt_key: false,
        meta_key: false,
        repeat: true,
        code: 0x51,
        text: "ArrowDown",
    });
    assert_eq!(length, 32 + "ArrowDown".len());
    assert!(matches!(
        decoded.payload,
        EventPayload::KeyDown {
            key: compose_rust::Key::ArrowDown,
            ctrl_key: true,
            repeat: true,
            code: 0x51,
            ..
        }
    ));
}

fn code_pointer(
    kind: compose_rust::PointerKind,
    position: Option<Position>,
) -> EventPayload<'static> {
    EventPayload::CodePointer {
        kind,
        pointer_id: 1,
        pointer_type: compose_rust::PointerType::Mouse,
        x: 96.5,
        y: 30.25,
        buttons: 1,
        button: 1,
        modifiers: 8,
        click_count: 1,
        delta_x: 0.0,
        delta_y: 0.0,
        delta_mode: compose_rust::WheelDeltaMode::Pixel,
        timestamp_nanos: 123_456_789_000,
        position,
    }
}

/// A pointer over the editor is the 72 byte pointer record and the line and the column
/// under it, 80 bytes under its own tag, and both halves of the place are `u32::MAX` where
/// it is over no character. Read back, it is a pointer event with the place beside it.
#[test]
fn fr38_editor_pointer_reports_text_position_on_the_wire() {
    assert_eq!(PropertyKind::OnCodePointer as u16, 197);
    assert_eq!(event_tag("CodePointer"), 66);
    assert_eq!(
        PropertyKind::from_name("on_code_pointer"),
        Ok(PropertyKind::OnCodePointer)
    );

    let over = code_pointer(compose_rust::PointerKind::Down, Some(Position::new(1, 13)));
    let (length, decoded) = round_trip(over.clone());
    assert_eq!(length, 80);
    let read = CodePointer::from_payload(&decoded.payload).expect("a code pointer");
    assert_eq!(read.position, Some(Position::new(1, 13)));
    assert_eq!(read.event.kind(), compose_rust::PointerKind::Down);
    assert!(read.event.meta_key());
    assert!(!read.event.ctrl_key());
    assert_eq!(read.event.click_count(), 1);
    assert_eq!(read.event.x(), 96.5);
    assert!(CodePointer::from_payload(&EventPayload::CodeSaveRequested { version: 0 }).is_none());

    // The first 72 bytes are what a pointer event of the same fields writes.
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 3,
            handler_id: 9,
            payload: over,
        },
        &mut wire,
    )
    .unwrap();
    let mut pointer = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 3,
            handler_id: 9,
            payload: EventPayload::Pointer {
                kind: compose_rust::PointerKind::Down,
                pointer_id: 1,
                pointer_type: compose_rust::PointerType::Mouse,
                x: 96.5,
                y: 30.25,
                buttons: 1,
                button: 1,
                modifiers: 8,
                click_count: 1,
                delta_x: 0.0,
                delta_y: 0.0,
                delta_mode: compose_rust::WheelDeltaMode::Pixel,
                timestamp_nanos: 123_456_789_000,
            },
        },
        &mut pointer,
    )
    .unwrap();
    assert_eq!(&wire[0..4], &[66, 0, 80, 0]);
    assert_eq!(&wire[4..72], &pointer[4..72]);
    assert_eq!(&wire[72..80], &[1, 0, 0, 0, 13, 0, 0, 0]);

    // Over no character.
    let (length, decoded) = round_trip(code_pointer(compose_rust::PointerKind::Move, None));
    assert_eq!(length, 80);
    assert_eq!(
        CodePointer::from_payload(&decoded.payload)
            .unwrap()
            .position,
        None
    );
    let mut nowhere = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 3,
            handler_id: 9,
            payload: code_pointer(compose_rust::PointerKind::Move, None),
        },
        &mut nowhere,
    )
    .unwrap();
    assert_eq!(&nowhere[72..80], &[0xff; 8]);
    // Half of nowhere is a record the two sides disagree on, and a short one is refused.
    nowhere[72..76].copy_from_slice(&0_u32.to_le_bytes());
    assert!(decode_event(&nowhere).is_err());
    wire[2] = 72;
    assert_eq!(
        decode_event(&wire[..72]),
        Err(ProtocolError::InvalidRecordLength)
    );

    // The vector both sides test against holds it after the position answer.
    let vectors = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/vectors");
    let events = std::fs::read(format!("{vectors}/events.bin")).unwrap();
    let mut expected = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 12,
            handler_id: 31,
            payload: code_pointer(compose_rust::PointerKind::Down, Some(Position::new(1, 13))),
        },
        &mut expected,
    )
    .unwrap();
    assert_eq!(&events[1543..1623], expected.as_slice());
}

/// The two backgrounds are decoration kinds 5 and 6, after the four there were, in the
/// same 44 byte record: lines as a range from the start of the first to the start of the
/// last, characters as an ordinary range, each with the role it is tinted in. Read back,
/// they are what was written, and a list with the older kinds keeps its records.
#[test]
fn fr38_line_and_range_backgrounds_round_trip_on_the_wire() {
    assert_eq!(DecorationKind::LineBackground as u16, 5);
    assert_eq!(DecorationKind::RangeBackground as u16, 6);
    let line = Decoration::line_background(3, 4, 6, ColorRole::DiffAdded);
    let range = Decoration::range_background(3, CodeRange::of(5, 2, 5, 9), ColorRole::DiffRemoved);
    assert_eq!(line.range, CodeRange::of(4, 0, 6, 0));
    assert_eq!(line.color, Some(ColorRole::DiffAdded));
    assert_eq!(range.color, Some(ColorRole::DiffRemoved));
    let lens = Decoration::code_lens(3, 0, "Run", 41);
    let list = Decorations::new([lens.clone(), line.clone(), range.clone()]);
    assert_eq!(list.as_bytes().len(), 3 * DECORATION_LEN + "Run".len());
    let record = &list.as_bytes()[DECORATION_LEN..2 * DECORATION_LEN];
    assert_eq!(u16::from_le_bytes([record[4], record[5]]), 5);
    assert_eq!(
        u16::from_le_bytes([record[8], record[9]]),
        ColorRole::DiffAdded as u16
    );
    assert_eq!(list.decorations(), vec![lens, line, range]);

    // In a batch the blob travels as it was written.
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetProp {
            node_id: 2,
            property: PropertyKind::Decorations,
            value: PropertyValue::Bytes(list.as_bytes()),
        })
        .unwrap();
    let batch = encoder.finish().unwrap().to_vec();
    assert_eq!(
        records(&batch),
        vec![Record::Prop(
            2,
            PropertyKind::Decorations,
            Value::Bytes(list.as_bytes().to_vec())
        )]
    );
}

/// The grouped edit's command and result keep the numbers the wire table gives them.
#[test]
fn fr38_edit_group_keeps_its_assigned_tags() {
    assert_eq!(PropertyKind::OnEditGroupResult as u16, 194);
    let event = EVENT_SCHEMA
        .iter()
        .find(|event| event.name == "CodeEditGroupResult")
        .expect("CodeEditGroupResult is in the event schema");
    assert_eq!(event.tag, 51);
    assert_eq!(EditGroupStatus::Applied as u16, 1);
    assert_eq!(EditGroupStatus::Rejected as u16, 2);
    assert_eq!(EditGroupStatus::Invalid as u16, 3);
    assert_eq!(EditGroupStatus::TooOld as u16, 4);
    assert_eq!(CODE_EDIT_LEN, 24);
}

/// A group carries how many edits it has and every text exactly, a surrogate pair among
/// them, and the result carries every word; both are in the protocol vectors.
#[test]
fn fr38_edit_group_round_trips_on_the_wire() {
    let edits = [
        (CodeRange::of(1, 17, 1, 19), "🎉"),
        (CodeRange::of(0, 0, 0, 2), "pub fn"),
        (CodeRange::of(2, 0, 2, 0), ""),
        (CodeRange::of(3, 4, 5, 0), "한\r\n😀"),
    ];
    let mut batch = compose_rust::Batch::new();
    batch.begin();
    batch.edit_group(12, 99, 4, &edits);
    let bytes = batch.finish().unwrap().to_vec();
    // The envelope is twelve bytes, and the 24-byte record follows it.
    assert_eq!(&bytes[12..16], &[28, 0, 24, 0]);
    let decoded = decode_batch(&bytes).unwrap();
    let [
        Mutation::EditCodeGroup {
            node_id: 12,
            request_id: 99,
            base_version: 4,
            edits: blob,
        },
    ] = decoded.as_slice()
    else {
        panic!("expected one grouped edit, got {decoded:?}");
    };
    // The count, four records, then the texts, whose offsets count from where they start.
    assert_eq!(u32::from_le_bytes(blob[0..4].try_into().unwrap()), 4);
    let texts = 4 + 4 * CODE_EDIT_LEN;
    assert_eq!(&blob[texts..texts + "🎉".len()], "🎉".as_bytes());
    assert_eq!(u32::from_le_bytes(blob[20..24].try_into().unwrap()), 0);
    assert_eq!(
        u32::from_le_bytes(blob[44..48].try_into().unwrap()),
        "🎉".len() as u32
    );
    assert_eq!(decode_edit_group(blob).unwrap(), edits.to_vec());

    for status in [
        EditGroupStatus::Applied,
        EditGroupStatus::Rejected,
        EditGroupStatus::Invalid,
        EditGroupStatus::TooOld,
    ] {
        let (length, decoded) = round_trip(EventPayload::CodeEditGroupResult {
            request_id: 99,
            status,
            base_version: 4,
            current_version: 8,
            index: 3,
        });
        assert_eq!(length, 36);
        assert_eq!(
            EditGroupResult::from_payload(&decoded.payload),
            Some(EditGroupResult {
                request_id: 99,
                status,
                base_version: 4,
                current_version: 8,
                index: 3,
            })
        );
    }
    // A status this side does not know is a protocol error, not a guess.
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 12,
            handler_id: 32,
            payload: EventPayload::CodeEditGroupResult {
                request_id: 8,
                status: EditGroupStatus::Applied,
                base_version: 1,
                current_version: 3,
                index: 2,
            },
        },
        &mut wire,
    )
    .unwrap();
    assert_eq!(&wire[0..4], &[51, 0, 36, 0]);
    let mut unknown = wire.clone();
    unknown[20] = 5;
    assert_eq!(
        decode_event(&unknown),
        Err(ProtocolError::InvalidValueKind(5))
    );
    assert_eq!(
        decode_event(&wire[..32]),
        Err(ProtocolError::InvalidRecordLength)
    );

    // Both are in the vectors: the group after the single edit, and its result last.
    let vector = compose_rust::codegen::generate_mutation_vector().unwrap();
    let mutations = decode_batch(&vector).unwrap();
    let groups: Vec<_> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::EditCodeGroup {
                node_id,
                request_id,
                base_version,
                edits,
            } => Some((
                *node_id,
                *request_id,
                *base_version,
                decode_edit_group(edits),
            )),
            _ => None,
        })
        .collect();
    assert_eq!(
        groups,
        vec![(
            12,
            8,
            1,
            Some(vec![
                (CodeRange::of(1, 17, 1, 19), "🎉"),
                (CodeRange::of(0, 0, 0, 2), "pub fn"),
            ])
        )]
    );
    let events = compose_rust::codegen::generate_event_vector().unwrap();
    assert_eq!(events.len(), 1679);
    assert_eq!(&events[1623..1659], wire.as_slice());
}

/// An empty group is no request at all, so nothing is written for it.
#[test]
fn fr38_an_empty_edit_group_writes_nothing() {
    let mut batch = compose_rust::Batch::new();
    batch.begin();
    batch.edit_group(12, 5, 1, &[]);
    let empty = batch.finish().unwrap().to_vec();
    assert!(decode_batch(&empty).unwrap().is_empty());
    let mut encoder = BatchEncoder::default();
    assert_eq!(empty, encoder.finish().unwrap());

    // The same call with one edit writes exactly one record, and a warm batch reuses the
    // buffer it encodes the group into.
    batch.begin();
    batch.edit_group(12, 5, 1, &[(CodeRange::of(0, 0, 0, 1), "x")]);
    batch.edit_group(12, 6, 1, &[]);
    let one = batch.finish().unwrap().to_vec();
    assert_eq!(decode_batch(&one).unwrap().len(), 1);
}

/// A group record whose length is wrong, or whose edits cannot be read, is refused by the
/// decoder; a group that only makes no sense (edits that overlap, a range past the end) is
/// a well formed record, which the Renderer answers with `Invalid` and no protocol error.
#[test]
fn fr38_a_broken_group_record_is_a_protocol_error_on_the_wire() {
    let mut batch = compose_rust::Batch::new();
    batch.begin();
    batch.edit_group(
        12,
        5,
        1,
        &[
            (CodeRange::of(0, 0, 0, 4), "a"),
            (CodeRange::of(0, 2, 0, 6), "b"),
            (CodeRange::of(90, 0, 90, 1), "c"),
        ],
    );
    let overlapping = batch.finish().unwrap().to_vec();
    assert_eq!(decode_batch(&overlapping).unwrap().len(), 1);

    // The same record four bytes short: the length in its head says 20.
    let mut short = overlapping.clone();
    short[14] = 20;
    assert_eq!(
        decode_batch(&short),
        Err(ProtocolError::InvalidRecordLength)
    );

    // Blobs that are not a group: too short for the count, a count the blob cannot hold, a
    // text past the end, text that is not UTF-8, and bytes after the last text.
    let mut good = Vec::new();
    encode_edit_group(&[(CodeRange::of(0, 0, 0, 0), "ab")], &mut good).unwrap();
    assert!(decode_edit_group(&good).is_some());
    assert_eq!(decode_edit_group(&[1, 0]), None);
    let mut too_many = good.clone();
    too_many[0] = 2;
    assert_eq!(decode_edit_group(&too_many), None);
    let mut past_end = good.clone();
    past_end[24] = 3;
    assert_eq!(decode_edit_group(&past_end), None);
    let mut not_text = good.clone();
    not_text[28] = 0xff;
    assert_eq!(decode_edit_group(&not_text), None);
    let mut trailing = good.clone();
    trailing.push(b'!');
    assert_eq!(decode_edit_group(&trailing), None);
}

/// The minimap side travels as a `MinimapSide` tag on property 201, the right side as 1 and
/// the left as 2. `Off` is 0, the tag an editor that never sets it is read as.
#[test]
fn fr38_minimap_side_travels_as_a_tag() {
    use compose_rust::MinimapSide;
    assert_eq!(PropertyKind::Minimap as u16, 201);
    assert_eq!(PropertyKind::from_name("minimap"), Ok(PropertyKind::Minimap));
    assert_eq!(u16::from(MinimapSide::Off), 0);
    assert_eq!(u16::from(MinimapSide::Right), 1);
    assert_eq!(u16::from(MinimapSide::Left), 2);
    assert_eq!(MinimapSide::try_from(2), Ok(MinimapSide::Left));
    assert_eq!(MinimapSide::from_name("right"), Ok(MinimapSide::Right));

    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetProp {
            node_id: 2,
            property: PropertyKind::Minimap,
            value: PropertyValue::Integer(i64::from(u16::from(MinimapSide::Left))),
        })
        .unwrap();
    let batch = encoder.finish().unwrap().to_vec();
    assert_eq!(
        records(&batch),
        vec![Record::Prop(2, PropertyKind::Minimap, Value::Integer(2))]
    );
}
