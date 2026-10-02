//! The widget layer writes the same records the Dioxus path writes for the same change.

use dioxus_compose::protocol::{BatchEncoder, Mutation, PropertyValue, decode_batch};
use dioxus_compose::schema::{PropertyKind, WidgetKind};
use positional::widgets::{TextProps, column, compose_frame, reset_frame, text};
use positional::{composable, reset};
use std::cell::RefCell;

thread_local! {
    static LAST: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

#[composable]
fn list(width: usize, count: u64) {
    column(|| {
        for slot in 0..width {
            text(TextProps {
                text: format!("{count}-{slot}"),
                ..TextProps::default()
            });
        }
    });
}

/// Runs a frame and decodes what it produced. The batch is re-encoded into an owned buffer
/// first because the encoder's arena is reused by the next frame.
fn frame(width: usize, count: u64) -> Vec<String> {
    let mut bytes = Vec::new();
    let _ = compose_frame(|| list(width, count));
    positional::widgets::with_last_batch(|batch| bytes.extend_from_slice(batch));
    LAST.with(|cell| *cell.borrow_mut() = bytes.clone());
    decode_batch(&bytes)
        .unwrap()
        .iter()
        .map(|mutation| describe(mutation))
        .collect()
}

fn describe(mutation: &Mutation<'_>) -> String {
    match mutation {
        Mutation::Create { node_id, widget } => format!("create {node_id} {widget:?}"),
        Mutation::Insert { parent_id, node_id, index } => {
            format!("insert {node_id} under {parent_id} at {index}")
        }
        Mutation::SetProp {
            node_id,
            property: PropertyKind::Text,
            value: PropertyValue::String(value),
        } => format!("text {node_id} {value}"),
        Mutation::Remove { node_id } => format!("remove {node_id}"),
        other => format!("{other:?}"),
    }
}

#[test]
fn the_first_frame_creates_inserts_and_sets_text() {
    reset();
    reset_frame();
    assert_eq!(
        frame(2, 0),
        vec![
            "create 1 Column",
            "insert 1 under 0 at 0",
            "create 2 Text",
            "insert 2 under 1 at 0",
            "text 2 0-0",
            "create 3 Text",
            "insert 3 under 1 at 1",
            "text 3 0-1",
        ]
    );
    let _ = WidgetKind::Text;
    let _ = BatchEncoder::default();
}

#[test]
fn a_changed_value_is_one_text_record_per_node_and_nothing_else() {
    reset();
    reset_frame();
    frame(3, 0);
    assert_eq!(frame(3, 1), vec!["text 2 1-0", "text 3 1-1", "text 4 1-2"]);
}

#[test]
fn an_unchanged_frame_writes_no_records() {
    reset();
    reset_frame();
    frame(3, 0);
    assert_eq!(frame(3, 0), Vec::<String>::new());
    // And the skipped nodes are still there to be changed afterwards.
    assert_eq!(frame(3, 1), vec!["text 2 1-0", "text 3 1-1", "text 4 1-2"]);
}

#[test]
fn a_shorter_list_removes_the_nodes_it_lost() {
    reset();
    reset_frame();
    frame(3, 0);
    assert_eq!(frame(1, 0), vec!["remove 3", "remove 4"]);
}
