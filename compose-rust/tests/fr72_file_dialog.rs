//! File dialogs from the Host's side: what a request looks like in a batch, how the answer
//! finds the callback that asked, and that the boundary gained nothing for any of it.
//!
//! The Renderer's half, showing the platform's panel, is not here. What is here is
//! everything the Host promises: one request number per ask, one callback call per number,
//! and paths that arrive whole.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{
    HostEvent, Mutation, ProtocolError, decode_batch, decode_event, encode_event,
};
use compose_rust::runtime::*;
use compose_rust::schema::{
    BOUNDARY_SCHEMA, DesignSystem, EventPayload, FileDialogKind, FileDialogStatus, PropertyKind,
    Theme,
};
use compose_rust::ui::*;
use compose_rust::{FileDialog, FileDialogResult, FileDialogSender};
use std::cell::RefCell;
use std::sync::Mutex;
use support::Tree;

/// The tests below share the Host's request queue, which is process wide, so they take
/// turns. Each one drains everything it asked for before it lets go.
static SERIAL: Mutex<()> = Mutex::new(());

thread_local! {
    static RESULTS: RefCell<Vec<(&'static str, FileDialogResult)>> = const { RefCell::new(Vec::new()) };
}

static WORKER_RESULTS: Mutex<Vec<FileDialogResult>> = Mutex::new(Vec::new());

fn record(label: &'static str) -> impl FnOnce(FileDialogResult) + 'static {
    move |result| RESULTS.with_borrow_mut(|results| results.push((label, result)))
}

fn results() -> Vec<(&'static str, FileDialogResult)> {
    RESULTS.with_borrow(Clone::clone)
}

#[composable]
fn Askers() {
    Button("choose").on_click(|| {
        FileDialog::open_files()
            .title("Choose")
            .filter("Images", &["png", "jpg"])
            .show(record("choose"));
    });
    Button("cancel").on_click(|| {
        FileDialog::save().name("a.txt").show(record("cancel"));
    });
    Button("unavailable").on_click(|| {
        FileDialog::open_folder()
            .window(0)
            .show(record("unavailable"));
    });
    Button("worker").on_click(|| {
        FileDialogSender::new().show(FileDialog::open_file(), |result| {
            WORKER_RESULTS.lock().unwrap().push(result);
        });
    });
}

fn event(payload: EventPayload<'static>) -> HostEvent<'static> {
    HostEvent {
        node_id: 0,
        handler_id: 0,
        payload,
    }
}

fn result_event(
    request: u32,
    status: FileDialogStatus,
    filter: u32,
    paths: &'static str,
) -> HostEvent<'static> {
    event(EventPayload::FileDialogResult {
        request,
        status,
        filter,
        paths,
    })
}

/// The dialogs a batch asks for, as (request, kind, window).
fn dialogs(batch: &[u8]) -> Vec<(u32, FileDialogKind, u32)> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::ShowFileDialog {
                request,
                kind,
                window,
                ..
            } => Some((request, kind, window)),
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
        let mut host = ComposeHost::with_theme(Askers, Theme::unified(DesignSystem::Material3));
        let mut tree = Tree::default();
        let first = host.rebuild().expect("the first batch encodes").to_vec();
        tree.apply(&first);
        assert!(dialogs(&first).is_empty(), "nothing is asked for at start");
        Self { host, tree }
    }

    /// Presses the button and returns the one dialog request the press produced.
    fn press(&mut self, label: &str) -> (u32, FileDialogKind, u32) {
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
        let mut asked = dialogs(batch);
        assert_eq!(asked.len(), 1, "one press, one request: {asked:?}");
        asked.remove(0)
    }

    fn answer(&mut self, event: HostEvent<'static>) {
        self.host.dispatch(event).expect("the answer dispatches");
    }
}

/// Selecting, cancelling and being unable to show all end in exactly one call of the
/// callback that asked, and an answer that comes twice or for nobody calls nothing.
#[test]
fn fr72_every_request_gets_exactly_one_result() {
    let _turn = SERIAL.lock().unwrap_or_else(|poison| poison.into_inner());
    RESULTS.with_borrow_mut(Vec::clear);
    WORKER_RESULTS.lock().unwrap().clear();
    let mut session = Session::start();

    let (chosen, chosen_kind, chosen_window) = session.press("choose");
    let (cancelled, cancelled_kind, _) = session.press("cancel");
    let (unavailable, unavailable_kind, _) = session.press("unavailable");
    let (worker, worker_kind, _) = session.press("worker");
    assert_eq!(chosen_kind, FileDialogKind::OpenFiles);
    assert_eq!(
        chosen_window, 0,
        "no window named is the implicit root window"
    );
    assert_eq!(cancelled_kind, FileDialogKind::Save);
    assert_eq!(unavailable_kind, FileDialogKind::OpenFolder);
    assert_eq!(worker_kind, FileDialogKind::OpenFile);
    let mut numbers = vec![chosen, cancelled, unavailable, worker];
    numbers.sort_unstable();
    numbers.dedup();
    assert_eq!(numbers.len(), 4, "every request has a number of its own");
    assert!(results().is_empty(), "nobody is called before the answer");

    session.answer(result_event(
        chosen,
        FileDialogStatus::Chosen,
        1,
        "/home/a.png\0/home/b.jpg",
    ));
    session.answer(result_event(
        cancelled,
        FileDialogStatus::Cancelled,
        u32::MAX,
        "",
    ));
    session.answer(result_event(
        unavailable,
        FileDialogStatus::Unavailable,
        u32::MAX,
        "",
    ));
    session.answer(result_event(
        worker,
        FileDialogStatus::Cancelled,
        u32::MAX,
        "",
    ));
    assert_eq!(
        results(),
        vec![
            (
                "choose",
                FileDialogResult::Chosen {
                    paths: vec!["/home/a.png".to_owned(), "/home/b.jpg".to_owned()],
                    filter: Some(1),
                }
            ),
            ("cancel", FileDialogResult::Cancelled),
            ("unavailable", FileDialogResult::Unavailable),
        ]
    );
    assert_eq!(
        *WORKER_RESULTS.lock().unwrap(),
        vec![FileDialogResult::Cancelled],
        "a worker's callback is called on the UI thread, once"
    );

    // The same answers again, and one for a number nobody asked for: nothing is called and
    // nothing fails.
    session.answer(result_event(
        chosen,
        FileDialogStatus::Cancelled,
        u32::MAX,
        "",
    ));
    session.answer(result_event(
        worker,
        FileDialogStatus::Cancelled,
        u32::MAX,
        "",
    ));
    session.answer(result_event(
        u32::MAX,
        FileDialogStatus::Unavailable,
        u32::MAX,
        "",
    ));
    assert_eq!(results().len(), 3);
    assert_eq!(WORKER_RESULTS.lock().unwrap().len(), 1);

    // The answer belongs to the Host: a node or a handler on it is a malformed event.
    let on_a_node = HostEvent {
        node_id: 5,
        handler_id: 0,
        payload: EventPayload::FileDialogResult {
            request: chosen,
            status: FileDialogStatus::Cancelled,
            filter: u32::MAX,
            paths: "",
        },
    };
    assert!(session.host.dispatch(on_a_node).is_err());
}

/// A path is whatever the platform gave, whole: Korean text and paths longer than 260
/// characters arrive unchanged and in order. One that is not UTF-8 never gets as far as the
/// callback, because the event does not decode.
#[test]
fn fr72_paths_arrive_whole_utf8_and_a_non_utf8_path_answers_unavailable() {
    let _turn = SERIAL.lock().unwrap_or_else(|poison| poison.into_inner());
    RESULTS.with_borrow_mut(Vec::clear);
    let mut session = Session::start();
    let (request, _, _) = session.press("choose");

    let long: &'static str = Box::leak(format!("/data/{}.bin", "가".repeat(300)).into_boxed_str());
    assert!(long.chars().count() > 260);
    let paths: &'static str = Box::leak(format!("/Users/한글/문서 1.txt\0{long}").into_boxed_str());

    // Over the wire and back, the way the Renderer's event reaches the Host.
    let sent = result_event(request, FileDialogStatus::Chosen, 0, paths);
    let mut bytes = Vec::new();
    encode_event(&sent, &mut bytes).unwrap();
    assert_eq!(decode_event(&bytes).unwrap(), sent);

    // A path that is not UTF-8 makes the whole event a protocol error, and the request
    // stays waiting for the answer the Renderer sends instead.
    let mut corrupt = bytes.clone();
    let last = corrupt.len() - 1;
    corrupt[last] = 0xFF;
    assert_eq!(decode_event(&corrupt), Err(ProtocolError::InvalidUtf8));
    assert!(results().is_empty());

    session.answer(sent);
    assert_eq!(
        results(),
        vec![(
            "choose",
            FileDialogResult::Chosen {
                paths: vec!["/Users/한글/문서 1.txt".to_owned(), long.to_owned()],
                filter: Some(0),
            }
        )]
    );
}

/// A dialog adds a record and an event to the protocol and nothing to the boundary: the
/// same five entry points, and no exported symbol in the dialog's module.
#[test]
fn pr2_boundary_surface_unchanged_by_file_dialog() {
    let symbols: Vec<&str> = BOUNDARY_SCHEMA
        .iter()
        .map(|operation| operation.symbol)
        .collect();
    assert_eq!(
        symbols,
        vec![
            "compose_rust_host_init",
            "compose_rust_host_dispatch_event",
            "compose_rust_host_render_frame",
            "compose_rust_host_release_batch",
            "compose_rust_host_shutdown",
        ]
    );
    let exports = include_str!("../src/boundary.rs")
        .matches("#[unsafe(no_mangle)]")
        .count();
    assert_eq!(
        exports,
        symbols.len(),
        "boundary.rs exports one function per entry point"
    );
    let dialog = include_str!("../src/file_dialog.rs");
    assert!(!dialog.contains("no_mangle"));
    assert!(!dialog.contains("extern \"C\""));
}
