//! Clipboard reads from the Host's side: one request record per read, one answer per
//! request, and over-long text reported as an error rather than cut.
//!
//! The Renderer's half, reading the platform clipboard, is checked in the Kotlin tests
//! against a fake clipboard.

mod support;

use compose_rust::ComposeHost;
use compose_rust::clipboard::{self, ClipboardError, MAX_TEXT_BYTES, ReadResult, read_status};
use compose_rust::protocol::{
    BatchEncoder, HostEvent, Mutation, decode_batch, decode_event, encode_event,
};
use compose_rust::runtime::*;
use compose_rust::schema::{DesignSystem, EventPayload, PropertyKind, Theme};
use compose_rust::ui::*;
use std::cell::RefCell;
use support::Tree;

thread_local! {
    static RESULTS: RefCell<Vec<(&'static str, ReadResult)>> = const { RefCell::new(Vec::new()) };
}

fn record(label: &'static str) -> impl FnOnce(ReadResult) + 'static {
    move |result| RESULTS.with_borrow_mut(|results| results.push((label, result)))
}

fn results() -> Vec<(&'static str, ReadResult)> {
    RESULTS.with_borrow(Clone::clone)
}

#[Composable]
fn Pasters() {
    Button("paste").on_click(|| clipboard::read_text(record("paste")));
    let scope = remember_coroutine_scope();
    Button("await").on_click(move || {
        scope.launch(async {
            let result = clipboard::use_clipboard().read_text().await;
            RESULTS.with_borrow_mut(|results| results.push(("await", result)));
        })
    });
}

/// The read requests a batch carries.
fn reads(batch: &[u8]) -> Vec<u32> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::ReadClipboard { request } => Some(request),
            _ => None,
        })
        .collect()
}

fn answer(request: u32, status: u32, len: u32, text: &str) -> HostEvent<'_> {
    HostEvent {
        node_id: 0,
        handler_id: 0,
        payload: EventPayload::ClipboardText {
            request,
            status,
            len,
            text,
        },
    }
}

struct Session {
    host: ComposeHost,
    tree: Tree,
}

impl Session {
    fn start() -> Self {
        RESULTS.with_borrow_mut(Vec::clear);
        let mut host = ComposeHost::with_theme(Pasters, Theme::unified(DesignSystem::Material3));
        let mut tree = Tree::default();
        let first = host.rebuild().expect("the first batch encodes").to_vec();
        tree.apply(&first);
        assert!(reads(&first).is_empty(), "nothing is read at start");
        Self { host, tree }
    }

    /// Presses the button and returns the one read request the press produced.
    fn press(&mut self, label: &str) -> u32 {
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
        let requests = reads(batch);
        assert_eq!(requests.len(), 1, "one press is one read request");
        requests[0]
    }

    fn answer(&mut self, event: HostEvent<'_>) {
        let (batch, _) = self.host.dispatch(event).expect("the answer dispatches");
        let batch = batch.to_vec();
        self.tree.apply(&batch);
    }
}

/// The text comes back whole with the request number it was asked under, the record and the
/// event keep their bytes both ways, and the vector both sides test against holds them.
#[test]
fn fr78_read_clipboard_round_trips() {
    let text = "한글\n줄 🙂";

    // The request: tag 31, a 4-byte header and one word.
    let mut encoder = BatchEncoder::default();
    encoder
        .encode(&Mutation::ReadClipboard { request: 9 })
        .unwrap();
    let bytes = encoder.finish().unwrap().to_vec();
    assert_eq!(u16::from_le_bytes([bytes[12], bytes[13]]), 31, "tag 31");
    assert_eq!(
        u16::from_le_bytes([bytes[14], bytes[15]]),
        8,
        "header and one word"
    );
    assert_eq!(
        decode_batch(&bytes).unwrap(),
        vec![Mutation::ReadClipboard { request: 9 }]
    );

    // The answer: event tag 56, three words and one string reference.
    let event = answer(9, read_status::TEXT, text.len() as u32, text);
    let mut encoded = Vec::new();
    encode_event(&event, &mut encoded).unwrap();
    assert_eq!(
        u16::from_le_bytes([encoded[0], encoded[1]]),
        56,
        "event tag 56"
    );
    assert_eq!(u16::from_le_bytes([encoded[2], encoded[3]]), 36);
    assert_eq!(encoded.len(), 36 + text.len());
    assert_eq!(decode_event(&encoded).unwrap(), event);
    for cut in 0..encoded.len() {
        assert!(
            decode_event(&encoded[..cut]).is_err(),
            "an answer cut at {cut} bytes decoded"
        );
    }

    // The vectors carry both, at the place vectors.json names.
    let vectors = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/vectors");
    let mutations = std::fs::read(format!("{vectors}/mutations.bin")).unwrap();
    assert_eq!(reads(&mutations), vec![9]);
    let events = std::fs::read(format!("{vectors}/events.bin")).unwrap();
    assert_eq!(&events[1042..1042 + 51], encoded.as_slice());

    // Through a Host: a press asks, the answer reaches the callback once.
    let mut session = Session::start();
    let request = session.press("paste");
    assert_ne!(request, 0, "0 is never a request number");
    session.answer(answer(request, read_status::TEXT, text.len() as u32, text));
    assert_eq!(results(), vec![("paste", Ok(Some(text.to_owned())))]);
    // A second answer to the same number finds nobody waiting.
    session.answer(answer(request, read_status::TEXT, 1, "x"));
    assert_eq!(results().len(), 1);

    // Two reads get two numbers, and each answer goes to its own caller.
    let first = session.press("paste");
    let second = session.press("paste");
    assert_ne!(first, second);
    session.answer(answer(second, read_status::TEXT, 3, "둘"));
    session.answer(answer(first, read_status::TEXT, 3, "하"));
    assert_eq!(
        results()[1..],
        [
            ("paste", Ok(Some("둘".to_owned()))),
            ("paste", Ok(Some("하".to_owned())))
        ]
    );
}

/// The future form resolves with the same answer, from a coroutine a handler launched.
#[test]
fn fr78_read_text_async_resolves_with_the_answer() {
    let mut session = Session::start();
    let request = session.press("await");
    assert!(results().is_empty(), "the future waits for the answer");
    session.answer(answer(request, read_status::TEXT, 5, "hello"));
    assert_eq!(results(), vec![("await", Ok(Some("hello".to_owned())))]);
}

/// An empty clipboard and one that holds no text both answer `None`.
#[test]
fn fr78_empty_clipboard_reads_none() {
    let mut session = Session::start();
    let request = session.press("paste");
    session.answer(answer(request, read_status::NONE, 0, ""));
    // Text that is empty is no text either.
    let request = session.press("paste");
    session.answer(answer(request, read_status::TEXT, 0, ""));
    assert_eq!(results(), vec![("paste", Ok(None)), ("paste", Ok(None))]);
}

/// Where there is no clipboard the Renderer still answers once, with none, and a status
/// word this side does not know is read the same way. The Host keeps going.
#[test]
fn fr78_unavailable_clipboard_reads_none() {
    let mut session = Session::start();
    let request = session.press("paste");
    session.answer(answer(request, read_status::NONE, 0, ""));
    let request = session.press("paste");
    session.answer(answer(request, 77, 0, ""));
    assert_eq!(results(), vec![("paste", Ok(None)), ("paste", Ok(None))]);

    // An answer for a number nobody asked for is ignored, not an error.
    session.answer(answer(u32::MAX, read_status::NONE, 0, ""));
    // An answer that names a node is malformed: the Host says so instead of stopping.
    assert!(
        session
            .host
            .dispatch(HostEvent {
                node_id: 1,
                handler_id: 0,
                payload: EventPayload::ClipboardText {
                    request: 1,
                    status: read_status::NONE,
                    len: 0,
                    text: "",
                },
            })
            .is_err()
    );
    let request = session.press("paste");
    session.answer(answer(request, read_status::TEXT, 1, "a"));
    assert_eq!(results().len(), 3);
}

/// Text over the limit is an error the application sees, with its length, never part of
/// the text; and the Host refuses a text answer that is over the limit the same way.
#[test]
fn fr78_oversized_clipboard_text_is_an_error() {
    let mut session = Session::start();
    let request = session.press("paste");
    session.answer(answer(
        request,
        read_status::TOO_LONG,
        (MAX_TEXT_BYTES + 10) as u32,
        "",
    ));
    let long = "a".repeat(MAX_TEXT_BYTES + 1);
    let request = session.press("paste");
    session.answer(answer(request, read_status::TEXT, long.len() as u32, &long));
    let at_limit = "a".repeat(MAX_TEXT_BYTES);
    let request = session.press("paste");
    session.answer(answer(
        request,
        read_status::TEXT,
        at_limit.len() as u32,
        &at_limit,
    ));

    assert_eq!(
        results(),
        vec![
            (
                "paste",
                Err(ClipboardError::TooLong {
                    len: MAX_TEXT_BYTES + 10
                })
            ),
            (
                "paste",
                Err(ClipboardError::TooLong {
                    len: MAX_TEXT_BYTES + 1
                })
            ),
            ("paste", Ok(Some(at_limit))),
        ]
    );
}
