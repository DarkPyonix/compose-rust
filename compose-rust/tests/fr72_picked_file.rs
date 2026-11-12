//! What a file dialog chose, read and written: `FileDialogResult::files()` and `PickedFile`.
//!
//! On a desktop a picked file is a path and the Host does the reading and writing itself, on
//! a worker. In a browser it is a handle the Renderer holds, named in `Chosen.paths` by a
//! `webfile:` reference, and every operation crosses as a `FileCall` and comes back as one
//! event. Both are checked here from the Host's side; the browser's half is checked in the
//! web renderer's tests, and the interpreter's rules in the Kotlin tests.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{
    BatchEncoder, HostEvent, Mutation, ProtocolError, decode_batch, decode_event, encode_event,
};
use compose_rust::runtime::*;
use compose_rust::schema::{
    BOUNDARY_SCHEMA, DesignSystem, EventPayload, FileDelivery, FileDialogStatus, FileOp, IoStatus,
    PropertyKind, Theme,
};
use compose_rust::ui::*;
use compose_rust::{Delivery, FileDialog, FileDialogResult, FileError, PickedFile};
use std::cell::RefCell;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll, Wake, Waker};
use support::Tree;

/// The Host's queues of file operations and releases are process wide, so the tests that
/// read them take turns.
static SERIAL: Mutex<()> = Mutex::new(());

thread_local! {
    static RESULTS: RefCell<Vec<FileDialogResult>> = const { RefCell::new(Vec::new()) };
}

fn results() -> Vec<FileDialogResult> {
    RESULTS.with_borrow(Clone::clone)
}

#[Composable]
fn Opener() {
    Button("open").on_click(|| {
        FileDialog::open_files()
            .filter("Text", &["txt"])
            .show(|result| RESULTS.with_borrow_mut(|results| results.push(result)));
    });
}

struct Session {
    host: ComposeHost,
    tree: Tree,
}

impl Session {
    fn start() -> Self {
        RESULTS.with_borrow_mut(Vec::clear);
        let mut host = ComposeHost::with_theme(Opener, Theme::unified(DesignSystem::Material3));
        let mut tree = Tree::default();
        let first = host.rebuild().expect("the first batch encodes").to_vec();
        tree.apply(&first);
        Self { host, tree }
    }

    /// Presses "open" and returns the dialog's request number.
    fn open(&mut self) -> u32 {
        let (node_id, handler_id) =
            self.tree
                .find("Button", Some("open"), 0, PropertyKind::OnClick);
        let (batch, _) = self
            .host
            .dispatch(HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::Clicked,
            })
            .expect("the press dispatches");
        let asked: Vec<u32> = decode_batch(batch)
            .unwrap()
            .into_iter()
            .filter_map(|mutation| match mutation {
                Mutation::ShowFileDialog { request, .. } => Some(request),
                _ => None,
            })
            .collect();
        assert_eq!(asked.len(), 1);
        asked[0]
    }

    /// Sends `payload` as the Renderer would and returns the batch it produced, decoded to
    /// the records that are about files.
    fn answer(&mut self, payload: EventPayload<'_>) -> Vec<String> {
        let (batch, _) = self
            .host
            .dispatch(HostEvent {
                node_id: 0,
                handler_id: 0,
                payload,
            })
            .expect("the answer dispatches");
        file_records(batch)
    }

    /// The file records the next frame carries.
    fn frame(&mut self) -> Vec<String> {
        let batch = self
            .host
            .render_frame(0)
            .expect("the frame renders")
            .to_vec();
        file_records(&batch)
    }
}

/// The file operations and releases in a batch, as short text.
fn file_records(batch: &[u8]) -> Vec<String> {
    decode_batch(batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::FileCall {
                request,
                op,
                create,
                handle,
                name,
                data,
            } => Some(format!(
                "call {request:#x} {op:?} {handle} {name:?} create={create} {}",
                data.len()
            )),
            Mutation::ReleaseFileHandle { handle } => Some(format!("release {handle}")),
            _ => None,
        })
        .collect()
}

fn handles<'a>(
    request: u32,
    delivery: FileDelivery,
    first: u32,
    names: &'a str,
    kinds: &'a str,
) -> EventPayload<'a> {
    EventPayload::FileHandles {
        request,
        status: FileDialogStatus::Chosen,
        filter: 0,
        delivery,
        first_handle: first,
        names,
        kinds,
    }
}

/// Polls `future` until it is ready, parking between polls. The futures here are woken by a
/// worker thread or are already complete.
fn block_on<F: Future>(future: F) -> F::Output {
    struct Unpark(std::thread::Thread);
    impl Wake for Unpark {
        fn wake(self: Arc<Self>) {
            self.0.unpark();
        }
    }
    let waker = Waker::from(Arc::new(Unpark(std::thread::current())));
    let mut context = Context::from_waker(&waker);
    let mut future = std::pin::pin!(future);
    loop {
        if let Poll::Ready(value) = future.as_mut().poll(&mut context) {
            return value;
        }
        std::thread::park_timeout(std::time::Duration::from_millis(20));
    }
}

/// The request number of a `call` line from [`file_records`].
fn request_of(line: &str) -> u32 {
    let hex = line.split_whitespace().nth(1).unwrap();
    u32::from_str_radix(hex.trim_start_matches("0x"), 16).unwrap()
}

/// Polls `future` once, so that an operation that crosses to the Renderer asks for it.
fn poll_once<F: Future>(future: Pin<&mut F>) -> Poll<F::Output> {
    let waker = Waker::noop();
    future.poll(&mut Context::from_waker(waker))
}

#[test]
fn fr72_web_file_records_round_trip() {
    let write = Mutation::FileCall {
        request: 0x8000_0011,
        op: FileOp::Write,
        create: false,
        handle: 3,
        name: "",
        data: &[0x00, 0xff, 0x7f, 0x80],
    };
    let child = Mutation::FileCall {
        request: 0x8000_0012,
        op: FileOp::Child,
        create: true,
        handle: 4,
        name: "새 문서.txt",
        data: &[],
    };
    let release = Mutation::ReleaseFileHandle { handle: 3 };
    let mut encoder = BatchEncoder::default();
    for mutation in [&write, &child, &release] {
        encoder.encode(mutation).unwrap();
    }
    let batch = encoder.finish().unwrap().to_vec();
    assert_eq!(
        decode_batch(&batch).unwrap(),
        vec![write.clone(), child.clone(), release.clone()]
    );

    // One operation alone: the envelope is 12 bytes, then the tag, the length, the request,
    // the operation and the create flag.
    let mut encoder = BatchEncoder::default();
    encoder.encode(&write).unwrap();
    let single = encoder.finish().unwrap().to_vec();
    assert_eq!(u16::from_le_bytes([single[12], single[13]]), 33);
    assert_eq!(u16::from_le_bytes([single[14], single[15]]), 32);
    for unknown in [0_u16, 5, 9] {
        let mut patched = single.clone();
        patched[20..22].copy_from_slice(&unknown.to_le_bytes());
        assert_eq!(
            decode_batch(&patched),
            Err(ProtocolError::InvalidValueKind(unknown)),
            "operation {unknown}"
        );
    }
    let mut patched = single.clone();
    patched[22..24].copy_from_slice(&2_u16.to_le_bytes());
    assert_eq!(
        decode_batch(&patched),
        Err(ProtocolError::InvalidValueKind(2))
    );

    // The answers, both ways, and what is not one.
    let answer = HostEvent {
        node_id: 0,
        handler_id: 0,
        payload: EventPayload::IoAnswer {
            request: 0x8000_0011,
            status: IoStatus::Ok,
            data: &[0x00, 0xff, 0x7f, 0x80],
        },
    };
    let mut answer_bytes = Vec::new();
    encode_event(&answer, &mut answer_bytes).unwrap();
    assert_eq!(answer_bytes.len(), 36);
    assert_eq!(decode_event(&answer_bytes).unwrap(), answer);
    for unknown in [0_u32, 9, 70_000] {
        let mut patched = answer_bytes.clone();
        patched[20..24].copy_from_slice(&unknown.to_le_bytes());
        assert!(decode_event(&patched).is_err(), "status {unknown}");
    }

    let chosen = HostEvent {
        node_id: 0,
        handler_id: 0,
        payload: handles(7, FileDelivery::Handle, 3, "문서.txt\0사진", "fd"),
    };
    let mut chosen_bytes = Vec::new();
    encode_event(&chosen, &mut chosen_bytes).unwrap();
    assert_eq!(chosen_bytes.len(), 71);
    assert_eq!(decode_event(&chosen_bytes).unwrap(), chosen);
    for unknown in [0_u32, 4] {
        let mut patched = chosen_bytes.clone();
        patched[28..32].copy_from_slice(&unknown.to_le_bytes());
        assert!(decode_event(&patched).is_err(), "delivery {unknown}");
    }
    let mut patched = chosen_bytes.clone();
    let last = patched.len() - 1;
    patched[last] = b'x';
    assert!(
        decode_event(&patched).is_err(),
        "a kind letter that is neither f nor d"
    );
    for (names, kinds) in [("a\0b", "f"), ("a", "fd"), ("a\0\0b", "fff"), ("", "f")] {
        let event = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: handles(1, FileDelivery::Upload, 1, names, kinds),
        };
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        assert!(
            decode_event(&bytes).is_err(),
            "{names:?} with kinds {kinds:?} does not line up"
        );
    }
    let empty = HostEvent {
        node_id: 0,
        handler_id: 0,
        payload: handles(1, FileDelivery::Handle, 1, "", ""),
    };
    let mut bytes = Vec::new();
    encode_event(&empty, &mut bytes).unwrap();
    assert_eq!(
        decode_event(&bytes).unwrap(),
        empty,
        "an empty folder lists nothing"
    );

    // The vectors both sides test against hold the same bytes.
    let vectors = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/vectors");
    let events = std::fs::read(format!("{vectors}/events.bin")).unwrap();
    assert_eq!(&events[1093..1093 + 36], answer_bytes.as_slice());
    assert_eq!(&events[1129..1129 + 71], chosen_bytes.as_slice());
    let mutations = std::fs::read(format!("{vectors}/mutations.bin")).unwrap();
    let in_vector: Vec<Mutation<'_>> = decode_batch(&mutations)
        .unwrap()
        .into_iter()
        .filter(|mutation| {
            matches!(
                mutation,
                Mutation::FileCall { .. } | Mutation::ReleaseFileHandle { .. }
            )
        })
        .collect();
    assert_eq!(in_vector, vec![write, child, release]);
}

/// Picked files add records and events to the protocol and nothing to the boundary.
#[test]
fn pr2_boundary_surface_unchanged_by_web_files() {
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
    let module = include_str!("../src/picked_file.rs");
    assert!(!module.contains("no_mangle"));
    assert!(!module.contains("extern \"C\""));
    assert!(!module.contains("wasm_bindgen"));
}

/// A dialog in a browser ends in one of two events, `FileHandles` for a choice and
/// `FileDialogResult` for the rest, and either reaches the callback exactly once.
#[test]
fn fr72_web_every_request_gets_exactly_one_answer() {
    let _turn = SERIAL.lock().unwrap_or_else(|poison| poison.into_inner());
    let mut session = Session::start();

    let chosen = session.open();
    let cancelled = session.open();
    let unavailable = session.open();
    let odd = session.open();
    session.answer(handles(chosen, FileDelivery::Handle, 100, "a.txt", "f"));
    session.answer(EventPayload::FileDialogResult {
        request: cancelled,
        status: FileDialogStatus::Cancelled,
        filter: u32::MAX,
        paths: "",
    });
    session.answer(EventPayload::FileDialogResult {
        request: unavailable,
        status: FileDialogStatus::Unavailable,
        filter: u32::MAX,
        paths: "",
    });
    // Handles that do not say "chosen" are not a choice.
    session.answer(EventPayload::FileHandles {
        request: odd,
        status: FileDialogStatus::Cancelled,
        filter: u32::MAX,
        delivery: FileDelivery::Handle,
        first_handle: 200,
        names: "b.txt",
        kinds: "f",
    });
    assert_eq!(
        results(),
        vec![
            FileDialogResult::Chosen {
                paths: vec!["webfile:100/a.txt".to_owned()],
                filter: Some(0),
            },
            FileDialogResult::Cancelled,
            FileDialogResult::Unavailable,
            FileDialogResult::Unavailable,
        ]
    );

    // A second answer of either kind finds nobody waiting.
    session.answer(handles(chosen, FileDelivery::Handle, 300, "c.txt", "f"));
    session.answer(EventPayload::FileDialogResult {
        request: cancelled,
        status: FileDialogStatus::Cancelled,
        filter: u32::MAX,
        paths: "",
    });
    assert_eq!(results().len(), 4);

    // The answer belongs to the Host: a node on it is a malformed event.
    assert!(
        session
            .host
            .dispatch(HostEvent {
                node_id: 9,
                handler_id: 0,
                payload: handles(chosen, FileDelivery::Handle, 400, "d.txt", "f"),
            })
            .is_err()
    );
}

/// A choice in a browser arrives as references, `files()` turns them into files that say
/// what the event said, operations cross as `FileCall`s and come back by request number, and
/// the last file dropped for a handle lets go of it once.
#[test]
fn fr72_web_files_come_from_references_and_release_their_handles() {
    let _turn = SERIAL.lock().unwrap_or_else(|poison| poison.into_inner());
    let mut session = Session::start();
    // Whatever an earlier test in this binary left queued goes out first.
    session.frame();

    let request = session.open();
    session.answer(handles(
        request,
        FileDelivery::Upload,
        3,
        "문서.txt\0사진",
        "fd",
    ));
    let result = results().pop().expect("the choice reached the callback");
    let FileDialogResult::Chosen { paths, filter } = &result else {
        panic!("expected a choice, got {result:?}");
    };
    assert_eq!(
        paths,
        &vec!["webfile:3/문서.txt".to_owned(), "webfile:4/사진".to_owned()]
    );
    assert_eq!(*filter, Some(0));

    let files = result.files();
    assert_eq!(files.len(), 2);
    assert_eq!(
        files.iter().map(PickedFile::name).collect::<Vec<_>>(),
        vec!["문서.txt", "사진"]
    );
    assert_eq!(
        files.iter().map(PickedFile::is_folder).collect::<Vec<_>>(),
        vec![false, true]
    );
    assert!(files.iter().all(|file| file.delivery() == Delivery::Upload));
    assert!(files.iter().all(|file| file.path().is_none()));
    assert!(files.iter().all(|file| !file.can_write_again()));

    // What the Host already knows is answered without asking.
    assert_eq!(
        block_on(files[0].write(b"x".to_vec())),
        Err(FileError::ReadOnly)
    );
    assert_eq!(block_on(files[0].entries()), Err(FileError::NotAFolder));
    assert_eq!(block_on(files[1].read()), Err(FileError::IsAFolder));
    assert!(matches!(
        block_on(files[1].child("../a", false)),
        Err(FileError::Io(_))
    ));
    assert!(
        session.frame().is_empty(),
        "nothing crossed for any of those"
    );

    // A read crosses, and its answer is found by number.
    {
        let mut read = std::pin::pin!(files[0].read());
        assert!(poll_once(read.as_mut()).is_pending());
        let asked = session.frame();
        assert_eq!(asked.len(), 1, "{asked:?}");
        assert!(
            asked[0].ends_with("Read 3 \"\" create=false 0"),
            "{asked:?}"
        );
        let number = request_of(&asked[0]);
        assert_ne!(
            number & 0x8000_0000,
            0,
            "a file operation's number never is a dialog's"
        );
        session.answer(EventPayload::IoAnswer {
            request: number,
            status: IoStatus::Ok,
            data: "안녕".as_bytes(),
        });
        assert_eq!(
            poll_once(read.as_mut()),
            Poll::Ready(Ok("안녕".as_bytes().to_vec()))
        );
    }

    // A listing comes back as more handles, which are files like the first.
    let inside = {
        let mut listing = std::pin::pin!(files[1].entries());
        assert!(poll_once(listing.as_mut()).is_pending());
        let asked = session.frame();
        assert!(asked[0].contains("Entries 4"), "{asked:?}");
        session.answer(EventPayload::FileHandles {
            request: request_of(&asked[0]),
            status: FileDialogStatus::Chosen,
            filter: u32::MAX,
            delivery: FileDelivery::Upload,
            first_handle: 9,
            names: "안.txt",
            kinds: "f",
        });
        let Poll::Ready(Ok(inside)) = poll_once(listing.as_mut()) else {
            panic!("the listing resolves");
        };
        inside
    };
    assert_eq!(inside.len(), 1);
    assert_eq!(inside[0].name(), "안.txt");
    assert_eq!(results().len(), 1, "a listing is not a dialog's answer");

    // An error the Renderer answers with is the file's error.
    {
        let mut failing = std::pin::pin!(inside[0].read());
        assert!(poll_once(failing.as_mut()).is_pending());
        let asked = session.frame();
        session.answer(EventPayload::IoAnswer {
            request: request_of(&asked[0]),
            status: IoStatus::TooLarge,
            data: &[],
        });
        let Poll::Ready(Err(FileError::TooLarge { len, limit })) = poll_once(failing.as_mut())
        else {
            panic!("a read over the event buffer is too large");
        };
        assert!(len > limit);
    }

    // Two `files()` of one choice are the same handles. Dropping every one lets go of each
    // handle once.
    let again = result.files();
    assert_eq!(again, files);
    drop(again);
    assert!(
        session.frame().is_empty(),
        "the first files still hold both handles"
    );
    drop(files);
    drop(inside);
    let mut released = session.frame();
    released.sort();
    assert_eq!(released, vec!["release 3", "release 4", "release 9"]);
    assert!(session.frame().is_empty(), "once");

    // A reference whose handle was let go of makes a file that is not found, without asking.
    let stale = result.files();
    assert_eq!(block_on(stale[0].read()), Err(FileError::NotFound));
    drop(stale);
    assert!(
        session.frame().is_empty(),
        "nothing crosses for a handle already let go of"
    );
}

/// On a desktop `Chosen { paths, filter }` is what it always was, and `files()` reads and
/// writes those paths, on a worker, a write replacing the file whole.
#[test]
fn fr72_native_picked_files_read_and_write_their_paths() {
    let folder = std::path::Path::new(env!("CARGO_TARGET_TMPDIR"))
        .join(format!("fr72_native_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&folder);
    std::fs::create_dir_all(folder.join("sub")).unwrap();
    let first = folder.join("a.txt");
    let second = folder.join("한글 파일.txt");
    std::fs::write(&first, "hello").unwrap();
    std::fs::write(&second, "안녕").unwrap();
    let path = |p: &std::path::Path| p.to_str().unwrap().to_owned();

    let result = FileDialogResult::Chosen {
        paths: vec![path(&first), path(&second)],
        filter: None,
    };
    // Code written against the variant's two fields still reads them.
    if let FileDialogResult::Chosen { paths, filter } = &result {
        assert_eq!(paths.len(), 2);
        assert_eq!(*filter, None);
    }
    let files = result.files();
    assert_eq!(
        files
            .iter()
            .map(|file| file.path().unwrap().to_owned())
            .collect::<Vec<_>>(),
        vec![path(&first), path(&second)]
    );
    assert_eq!(files[1].name(), "한글 파일.txt");
    assert!(files.iter().all(|file| file.delivery() == Delivery::Handle));
    assert!(files.iter().all(PickedFile::can_write_again));
    assert!(FileDialogResult::Cancelled.files().is_empty());
    assert!(FileDialogResult::Unavailable.files().is_empty());

    assert_eq!(block_on(files[0].read()), Ok(b"hello".to_vec()));
    assert_eq!(block_on(files[1].read()), Ok("안녕".as_bytes().to_vec()));
    block_on(files[0].write("고친 글".as_bytes().to_vec())).unwrap();
    assert_eq!(std::fs::read_to_string(&first).unwrap(), "고친 글");
    block_on(files[0].write(Vec::new())).unwrap();
    assert_eq!(std::fs::read(&first).unwrap(), Vec::<u8>::new());

    // The folder, by path: what it holds in name order, and its children.
    let dir = PickedFile::from_path(path(&folder));
    assert!(dir.is_folder());
    assert!(!dir.can_write_again());
    let inside = block_on(dir.entries()).unwrap();
    assert_eq!(
        inside.iter().map(PickedFile::name).collect::<Vec<_>>(),
        vec!["a.txt", "sub", "한글 파일.txt"],
        "a write leaves no temporary file behind"
    );
    assert_eq!(
        inside.iter().map(PickedFile::is_folder).collect::<Vec<_>>(),
        vec![false, true, false]
    );
    let made = block_on(dir.child("새 문서.txt", true)).unwrap();
    assert_eq!(
        made.path(),
        Some(path(&folder.join("새 문서.txt")).as_str())
    );
    assert_eq!(block_on(made.read()), Ok(Vec::new()));
    let found = block_on(dir.child("a.txt", false)).unwrap();
    assert_eq!(found, files[0]);
    assert_eq!(block_on(dir.child("없음", false)), Err(FileError::NotFound));
    assert!(matches!(
        block_on(dir.child("..", true)),
        Err(FileError::Io(_))
    ));
    assert!(matches!(
        block_on(dir.child("a/b", true)),
        Err(FileError::Io(_))
    ));

    // The wrong kind of file for the operation.
    assert_eq!(block_on(dir.read()), Err(FileError::IsAFolder));
    assert_eq!(
        block_on(dir.write(b"x".to_vec())),
        Err(FileError::IsAFolder)
    );
    assert_eq!(block_on(files[0].entries()), Err(FileError::NotAFolder));
    assert_eq!(
        block_on(files[0].child("x", false)),
        Err(FileError::NotAFolder)
    );
    let gone = PickedFile::from_path(path(&folder.join("gone.txt")));
    assert_eq!(block_on(gone.read()), Err(FileError::NotFound));

    // Longer than one read carries: refused before reading. Sparse, so it costs no disk.
    let big = folder.join("big.bin");
    std::fs::File::create(&big)
        .unwrap()
        .set_len(PickedFile::MAX_READ_BYTES + 1)
        .unwrap();
    assert_eq!(
        block_on(PickedFile::from_path(path(&big)).read()),
        Err(FileError::TooLarge {
            len: PickedFile::MAX_READ_BYTES + 1,
            limit: PickedFile::MAX_READ_BYTES,
        })
    );

    std::fs::remove_dir_all(&folder).unwrap();
}
