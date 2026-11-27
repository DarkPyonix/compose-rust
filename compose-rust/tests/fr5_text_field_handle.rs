//! A `TextFieldHandle` bound to a `TextField` gives that live field a new text with one
//! `SetText` on the same node, the empty text included.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{HostEvent, Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{EventPayload, PropertyKind, Selection};
use compose_rust::ui::*;
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree, Vec<u8>) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    tree.apply(&batch);
    (host, tree, batch)
}

/// The `SetText` records of a batch, as (node, text, selection).
fn set_texts(batch: &[u8]) -> Vec<(u32, String, Option<Selection>)> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetText {
                node_id,
                text,
                selection,
            } => Some((node_id, text.to_owned(), selection)),
            _ => None,
        })
        .collect()
}

/// How many nodes the batch creates.
fn creates(batch: &[u8]) -> usize {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter(|mutation| matches!(mutation, Mutation::Create { .. }))
        .count()
}

/// Clicks the button labelled `label` and returns the batch that answers it.
fn click(host: &mut ComposeHost, tree: &mut Tree, label: &str) -> Vec<u8> {
    let (node_id, handler_id) = tree.find("Button", Some(label), 0, PropertyKind::OnClick);
    let (batch, _) = host
        .dispatch(HostEvent::new(node_id, handler_id, EventPayload::Clicked))
        .expect("the click is accepted");
    let batch = batch.to_vec();
    tree.apply(&batch);
    batch
}

fn field_node(tree: &Tree) -> u32 {
    tree.find("TextField", None, 0, PropertyKind::OnValueChange)
        .0
}

#[test]
fn fr5_text_field_handle_sets_live_field_text() {
    let (mut host, mut tree, first) = start(|| {
        let field = use_text_field_handle();
        Column!({
            TextField!("hello", |_| {}, text_handle = &field);
            let reset = field.clone();
            Button!("Reset", move || reset.set_text("world"));
            let pick = field.clone();
            Button!("Pick", move || {
                pick.set_text_with_selection("picked", Selection { start: 0, end: 6 })
            });
        });
    });
    assert!(set_texts(&first).is_empty(), "nothing was given a text yet");
    let field = field_node(&tree);

    let batch = click(&mut host, &mut tree, "Reset");
    assert_eq!(set_texts(&batch), vec![(field, "world".to_owned(), None)]);
    assert_eq!(creates(&batch), 0, "the field kept its node");
    assert_eq!(field_node(&tree), field);

    let batch = click(&mut host, &mut tree, "Pick");
    assert_eq!(
        set_texts(&batch),
        vec![(
            field,
            "picked".to_owned(),
            Some(Selection { start: 0, end: 6 })
        )]
    );
}

#[test]
fn fr5_text_field_handle_clears_with_empty_string() {
    let (mut host, mut tree, _) = start(|| {
        let field = use_text_field_handle();
        Column!({
            TextField!("draft", |_| {}, text_handle = &field);
            let clear = field.clone();
            Button!("Clear", move || clear.set_text(""));
        });
    });
    let field = field_node(&tree);
    let batch = click(&mut host, &mut tree, "Clear");
    // The empty text goes out as a value, not as a removed property.
    assert_eq!(set_texts(&batch), vec![(field, String::new(), None)]);
    assert_eq!(creates(&batch), 0, "the field kept its node");
}

#[test]
fn fr5_text_field_handle_last_text_before_the_batch_wins() {
    let (mut host, mut tree, _) = start(|| {
        let field = use_text_field_handle();
        Column!({
            TextField!("", |_| {}, text_handle = &field);
            let set = field.clone();
            Button!("Twice", move || {
                set.set_text("first");
                set.set_text("second");
            });
        });
    });
    let field = field_node(&tree);
    let batch = click(&mut host, &mut tree, "Twice");
    assert_eq!(set_texts(&batch), vec![(field, "second".to_owned(), None)]);
    // Nothing is left over for the batch after.
    let batch = click(&mut host, &mut tree, "Twice");
    assert_eq!(set_texts(&batch), vec![(field, "second".to_owned(), None)]);
}

#[test]
fn fr5_text_field_handle_unbound_sends_nothing() {
    let (mut host, mut tree, first) = start(|| {
        let early = use_text_field_handle();
        let never = use_text_field_handle();
        // Given a text before the field that takes the handle is composed.
        early.set_text("too early");
        Column!({
            TextField!("", |_| {}, text_handle = &early);
            let ask = never.clone();
            Button!("Ask", move || ask.set_text("nobody"));
        });
    });
    assert!(
        set_texts(&first).is_empty(),
        "an unbound handle sent a text"
    );
    assert!(set_texts(&click(&mut host, &mut tree, "Ask")).is_empty());
    let loose = TextFieldHandle::new();
    loose.set_text("loose");
    assert!(!loose.is_bound());
}

#[test]
fn fr5_text_field_handle_field_that_left_sends_nothing() {
    let (mut host, mut tree, _) = start(|| {
        let shown = remember(|| mutable_state_of(true));
        let field = use_text_field_handle();
        Column!({
            if shown.get() {
                TextField!("", |_| {}, text_handle = &field);
            }
            let set = field.clone();
            Button!("Hide and set", move || {
                shown.set(false);
                set.set_text("gone");
            });
        });
    });
    let batch = click(&mut host, &mut tree, "Hide and set");
    assert!(
        set_texts(&batch).is_empty(),
        "a text went to a field that left: {:?}",
        set_texts(&batch)
    );
}
