//! A code editor written with `rsx!`: what the component puts on the wire, how its five
//! events reach their handlers, and edits asked of a handle.

use std::cell::RefCell;

use dioxus_compose_adapter::code::SYNTAX_SPAN_LEN;
use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{
    HostEvent, Mutation, PropertyValue, decode_batch, encode_event,
};
use dioxus_compose_adapter::{EventPayload, Host, PropertyKind, WidgetKind};

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
            text: "fn main() {{}}\n",
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
