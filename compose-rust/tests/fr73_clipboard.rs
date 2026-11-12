//! Clipboard writes from the Host's side: one record per write, the same bytes both ways,
//! and nothing sent when the text is too long.
//!
//! The Renderer's half, putting the text on the platform clipboard, is checked in the
//! Kotlin tests against a fake clipboard.

mod support;

use compose_rust::ComposeHost;
use compose_rust::clipboard::{self, ClipboardError, MAX_TEXT_BYTES};
use compose_rust::protocol::{BatchEncoder, HostEvent, Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{DesignSystem, EventPayload, PropertyKind, Theme};
use compose_rust::ui::*;
use std::cell::RefCell;
use support::Tree;

thread_local! {
    static OUTCOMES: RefCell<Vec<Result<(), ClipboardError>>> = const { RefCell::new(Vec::new()) };
}

fn outcomes() -> Vec<Result<(), ClipboardError>> {
    OUTCOMES.with_borrow(Clone::clone)
}

#[Composable]
fn Copiers() {
    Button("invite").on_click(|| {
        let outcome = clipboard::set_text("초대 링크");
        OUTCOMES.with_borrow_mut(|outcomes| outcomes.push(outcome));
    });
    Button("oversized").on_click(|| {
        let outcome = clipboard::set_text(&"a".repeat(MAX_TEXT_BYTES + 1));
        OUTCOMES.with_borrow_mut(|outcomes| outcomes.push(outcome));
    });
    Button("limit").on_click(|| {
        let outcome = clipboard::set_text(&"a".repeat(MAX_TEXT_BYTES));
        OUTCOMES.with_borrow_mut(|outcomes| outcomes.push(outcome));
    });
}

/// The clipboard writes a batch carries, as owned strings.
fn writes(batch: &[u8]) -> Vec<String> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetClipboardText { text } => Some(text.to_owned()),
            _ => None,
        })
        .collect()
}

struct Session {
    host: ComposeHost,
    tree: Tree,
}

impl Session {
    fn start() -> Self {
        OUTCOMES.with_borrow_mut(Vec::clear);
        let mut host = ComposeHost::with_theme(Copiers, Theme::unified(DesignSystem::Material3));
        let mut tree = Tree::default();
        let first = host.rebuild().expect("the first batch encodes").to_vec();
        tree.apply(&first);
        assert!(writes(&first).is_empty(), "nothing is copied at start");
        Self { host, tree }
    }

    /// Presses the button and returns the batch the press produced.
    fn press(&mut self, label: &str) -> Vec<u8> {
        let (node_id, handler_id) = self
            .tree
            .find("Button", Some(label), 0, PropertyKind::OnClick);
        let (batch, _) = self
            .host
            .dispatch(HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::Clicked,
            })
            .expect("the press dispatches");
        batch.to_vec()
    }
}

/// The record is tag 28, a 4-byte header and one string reference, and it decodes to the
/// text it was given. The Kotlin side reads the same record from the checked-in vector.
#[test]
fn fr73_set_clipboard_text_round_trips() {
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::SetClipboardText {
            text: "초대 링크"
        })
        .unwrap();
    let bytes = encoder.finish().unwrap().to_vec();

    // The envelope is 12 bytes, then the record's tag and length.
    assert_eq!(u16::from_le_bytes([bytes[12], bytes[13]]), 28, "tag 28");
    assert_eq!(
        u16::from_le_bytes([bytes[14], bytes[15]]),
        12,
        "header and one reference"
    );
    assert_eq!(
        decode_batch(&bytes).unwrap(),
        vec![Mutation::SetClipboardText {
            text: "초대 링크"
        }]
    );

    // From a handler, the write goes out as one record on the batch the press produced.
    let mut session = Session::start();
    let batch = session.press("invite");
    assert_eq!(writes(&batch), vec!["초대 링크".to_owned()]);
    assert_eq!(outcomes(), vec![Ok(())]);

    // The vector both sides test against holds the same record.
    let vector = std::fs::read(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/vectors/mutations.bin"
    ))
    .expect("the mutation vector is checked in");
    assert_eq!(writes(&vector), vec!["초대 링크".to_owned()]);
}

/// One byte over the limit is refused and sends nothing; the limit itself is allowed.
#[test]
fn fr73_oversized_text_is_refused_by_the_host() {
    let mut session = Session::start();

    let batch = session.press("oversized");
    assert!(writes(&batch).is_empty(), "nothing rides out on the batch");
    assert_eq!(
        outcomes(),
        vec![Err(ClipboardError::TooLong {
            len: MAX_TEXT_BYTES + 1
        })]
    );

    // A later batch does not carry it either: refused means dropped, not deferred.
    let batch = session.press("limit");
    assert_eq!(writes(&batch), vec!["a".repeat(MAX_TEXT_BYTES)]);
    assert_eq!(outcomes().len(), 2);
    assert_eq!(outcomes()[1], Ok(()));
}
