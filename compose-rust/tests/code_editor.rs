//! Widget tag 43: a code editor whose document the Renderer owns and the Host follows one
//! committed edit at a time. Everything here goes through the wire and back.

use std::cell::RefCell;

use compose_rust::code::{
    DECORATION_LEN, DECORATION_RECORD, RecordFieldType, SYNTAX_SPAN_LEN, SYNTAX_SPAN_RECORD,
    byte_offset,
};
use compose_rust::prelude::*;
use compose_rust::protocol::{
    BatchEncoder, HostEvent, Mutation, PropertyValue, ProtocolError, decode_batch, decode_event,
    encode_event,
};
use compose_rust::schema::EVENT_SCHEMA;
use compose_rust::{DecorationKind, EventPayload, Host, PropertyKind, WidgetKind};

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
            _ => Record::Other,
        })
        .collect()
}

fn node_of(records: &[Record], widget: WidgetKind) -> u32 {
    records
        .iter()
        .find_map(|record| match record {
            Record::Create(node_id, kind) if *kind == widget => Some(*node_id),
            _ => None,
        })
        .unwrap_or_else(|| panic!("no {widget:?} was created"))
}

fn prop_of(records: &[Record], node: u32, property: PropertyKind) -> Option<Value> {
    records.iter().rev().find_map(|record| match record {
        Record::Prop(node_id, kind, value) if *node_id == node && *kind == property => {
            Some(value.clone())
        }
        _ => None,
    })
}

fn handler_of(records: &[Record], node: u32, property: PropertyKind) -> u64 {
    match prop_of(records, node, property) {
        Some(Value::Integer(handler)) => handler as u64,
        other => panic!("node {node} has no {property:?} handler, found {other:?}"),
    }
}

fn send(host: &mut Host, node: u32, handler: u64, payload: EventPayload<'_>) -> Vec<Record> {
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: node,
            handler_id: handler,
            payload,
        },
        &mut wire,
    )
    .unwrap();
    let (batch, _) = host.dispatch_event(&wire).unwrap();
    records(batch)
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
            RecordFieldType::U32 | RecordFieldType::Text => 4,
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

thread_local! {
    static CHANGES: RefCell<Vec<CodeChange>> = const { RefCell::new(Vec::new()) };
    static REJECTED: RefCell<Vec<EditRejected>> = const { RefCell::new(Vec::new()) };
    static HOVERS: RefCell<Vec<CodeHover>> = const { RefCell::new(Vec::new()) };
    static SAVES: RefCell<Vec<SaveRequest>> = const { RefCell::new(Vec::new()) };
    static ACTIVATED: RefCell<Vec<u64>> = const { RefCell::new(Vec::new()) };
}

fn editor_app() -> Element {
    rsx! {
        CodeEditor {
            text: "fn main() {}\n",
            syntax_spans: SyntaxSpans::new([SyntaxSpan::role(
                0,
                CodeRange::of(0, 0, 0, 2),
                ColorRole::Primary,
            )]),
            decorations: Decorations::new([
                Decoration::underline(0, CodeRange::of(0, 3, 0, 7), Severity::Warning),
                Decoration::code_lens(0, 0, "Run", 41),
            ]),
            tab_width: 4_u32,
            on_change: move |change: CodeChange| CHANGES.with_borrow_mut(|seen| seen.push(change)),
            on_edit_rejected: move |rejected: EditRejected| REJECTED.with_borrow_mut(|seen| seen.push(rejected)),
            on_hover: move |hover: CodeHover| HOVERS.with_borrow_mut(|seen| seen.push(hover)),
            on_save: move |save: SaveRequest| SAVES.with_borrow_mut(|seen| seen.push(save)),
            on_decoration_click: move |id: u64| ACTIVATED.with_borrow_mut(|seen| seen.push(id)),
        }
    }
}

/// The editor opens with its document as `Text`, its two lists as blobs, its tab width,
/// and a handler for each of its five events, and nothing it does not need.
#[test]
fn fr38_an_editor_sends_its_document_its_lists_and_its_handlers() {
    let mut host = Host::new(editor_app);
    let batch = records(host.rebuild().unwrap());
    let editor = node_of(&batch, WidgetKind::CodeEditor);
    assert_eq!(
        prop_of(&batch, editor, PropertyKind::Text),
        Some(Value::Text("fn main() {}\n".into()))
    );
    let Some(Value::Bytes(decorations)) = prop_of(&batch, editor, PropertyKind::Decorations) else {
        panic!("the decorations did not travel as bytes");
    };
    assert_eq!(
        Decorations::from_bytes(decorations).decorations(),
        vec![
            Decoration::underline(0, CodeRange::of(0, 3, 0, 7), Severity::Warning),
            Decoration::code_lens(0, 0, "Run", 41),
        ]
    );
    let Some(Value::Bytes(spans)) = prop_of(&batch, editor, PropertyKind::SyntaxSpans) else {
        panic!("the colour runs did not travel as bytes");
    };
    assert_eq!(spans.len(), SYNTAX_SPAN_LEN);
    assert_eq!(
        prop_of(&batch, editor, PropertyKind::TabWidth),
        Some(Value::Integer(4))
    );
    for property in [
        PropertyKind::OnValueChange,
        PropertyKind::OnEditRejected,
        PropertyKind::OnHover,
        PropertyKind::OnSave,
        PropertyKind::OnDecorationClick,
    ] {
        handler_of(&batch, editor, property);
    }
}

/// Every event reaches the handler it belongs to, carrying what the Renderer said, and a
/// hover with no anchor under it arrives as no decoration rather than as id zero.
#[test]
fn fr38_each_code_event_reaches_its_handler() {
    let mut host = Host::new(editor_app);
    let batch = records(host.rebuild().unwrap());
    let editor = node_of(&batch, WidgetKind::CodeEditor);

    send(
        &mut host,
        editor,
        handler_of(&batch, editor, PropertyKind::OnValueChange),
        EventPayload::CodeChanged {
            version: 1,
            start_line: 0,
            start_column: 12,
            end_line: 0,
            end_column: 12,
            text: " // 😀",
        },
    );
    CHANGES.with_borrow(|seen| {
        assert_eq!(
            seen.as_slice(),
            &[CodeChange {
                version: 1,
                range: CodeRange::of(0, 12, 0, 12),
                text: " // 😀".into(),
            }]
        );
    });

    send(
        &mut host,
        editor,
        handler_of(&batch, editor, PropertyKind::OnEditRejected),
        EventPayload::CodeEditRejected {
            request_id: 7,
            base_version: 0,
            current_version: 1,
            start_line: 0,
            start_column: 3,
            end_line: 0,
            end_column: 7,
        },
    );
    REJECTED.with_borrow(|seen| {
        assert_eq!(
            seen.as_slice(),
            &[EditRejected {
                request_id: 7,
                base_version: 0,
                current_version: 1,
                range: CodeRange::of(0, 3, 0, 7),
            }]
        );
    });

    let hover = handler_of(&batch, editor, PropertyKind::OnHover);
    send(
        &mut host,
        editor,
        hover,
        EventPayload::CodeHovered {
            decoration: 42,
            line: 0,
            column: 4,
            phase: HoverPhase::Rest,
        },
    );
    send(
        &mut host,
        editor,
        hover,
        EventPayload::CodeHovered {
            decoration: 0,
            line: 0,
            column: 9,
            phase: HoverPhase::Leave,
        },
    );
    HOVERS.with_borrow(|seen| {
        assert_eq!(
            seen.as_slice(),
            &[
                CodeHover {
                    decoration: Some(42),
                    position: Position::new(0, 4),
                    phase: HoverPhase::Rest,
                },
                CodeHover {
                    decoration: None,
                    position: Position::new(0, 9),
                    phase: HoverPhase::Leave,
                },
            ]
        );
    });

    send(
        &mut host,
        editor,
        handler_of(&batch, editor, PropertyKind::OnSave),
        EventPayload::CodeSaveRequested { version: 1 },
    );
    SAVES.with_borrow(|seen| assert_eq!(seen.as_slice(), &[SaveRequest { version: 1 }]));

    send(
        &mut host,
        editor,
        handler_of(&batch, editor, PropertyKind::OnDecorationClick),
        EventPayload::DecorationActivated { decoration: 41 },
    );
    ACTIVATED.with_borrow(|seen| assert_eq!(seen.as_slice(), &[41]));
}

thread_local! {
    static OPENED: RefCell<String> = RefCell::new(String::from("let a = 1;\n"));
}

fn formatting_app() -> Element {
    let handle = use_code_editor();
    let mut text = use_signal(|| OPENED.with_borrow(Clone::clone));
    rsx! {
        Column {
            CodeEditor {
                text: text(),
                handle,
                // Written back, the way an application keeps its own copy. The text that
                // arrives is the reader's buffer, which the editor treats as nothing new.
                on_change: move |change: CodeChange| {
                    let mut current = text();
                    change.apply_to(&mut current);
                    text.set(current);
                },
            }
            Button {
                text: "Format",
                on_click: move |_| handle.edit(7, 0, CodeRange::of(0, 5, 0, 5), " "),
            }
        }
    }
}

/// An edit asked of a handle goes out as one `EditCode`, addressed to the editor that
/// carries the handle, with its request id and its base version, in the batch the call
/// that asked for it produced. The handle itself never crosses the boundary.
#[test]
fn fr38_an_edit_asked_of_a_handle_goes_out_to_its_editor() {
    let mut host = Host::new(formatting_app);
    let batch = records(host.rebuild().unwrap());
    let editor = node_of(&batch, WidgetKind::CodeEditor);
    let button = node_of(&batch, WidgetKind::Button);
    assert!(
        !batch
            .iter()
            .any(|record| matches!(record, Record::EditCode { .. })),
        "nothing was asked yet"
    );

    let after = send(
        &mut host,
        button,
        handler_of(&batch, button, PropertyKind::OnClick),
        EventPayload::Clicked,
    );
    assert_eq!(
        after
            .iter()
            .filter(|record| matches!(record, Record::EditCode { .. }))
            .collect::<Vec<_>>(),
        vec![&Record::EditCode {
            node_id: editor,
            request_id: 7,
            base_version: 0,
            range: CodeRange::of(0, 5, 0, 5),
            text: " ".into(),
        }]
    );
}

/// The edit comes back as an ordinary change, and an application that writes it back into
/// the signal it passes as `text` sends the buffer it already has. That is the text the
/// Renderer recognises and leaves alone, so the caret and the undo history survive.
#[test]
fn fr38_writing_a_change_back_sends_the_text_the_editor_already_holds() {
    let mut host = Host::new(formatting_app);
    let batch = records(host.rebuild().unwrap());
    let editor = node_of(&batch, WidgetKind::CodeEditor);
    let after = send(
        &mut host,
        editor,
        handler_of(&batch, editor, PropertyKind::OnValueChange),
        EventPayload::CodeChanged {
            version: 1,
            start_line: 0,
            start_column: 5,
            end_line: 0,
            end_column: 5,
            text: " ",
        },
    );
    assert_eq!(
        prop_of(&after, editor, PropertyKind::Text),
        Some(Value::Text("let a  = 1;\n".into()))
    );
}

fn detached_app() -> Element {
    let handle = use_code_editor();
    rsx! {
        Column {
            CodeEditor { text: "x" }
            Button {
                text: "Format",
                on_click: move |_| handle.edit(1, 0, CodeRange::of(0, 0, 0, 0), "y"),
            }
        }
    }
}

/// A handle no editor carries names nothing, so its edit is dropped here instead of being
/// sent to a node the Renderer would have to report as unknown.
#[test]
fn fr38_an_edit_for_a_handle_no_editor_carries_is_dropped() {
    let mut host = Host::new(detached_app);
    let batch = records(host.rebuild().unwrap());
    let button = node_of(&batch, WidgetKind::Button);
    let after = send(
        &mut host,
        button,
        handler_of(&batch, button, PropertyKind::OnClick),
        EventPayload::Clicked,
    );
    assert!(
        !after
            .iter()
            .any(|record| matches!(record, Record::EditCode { .. }))
    );
}
