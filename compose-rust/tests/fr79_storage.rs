//! The application's storage from the Host's side: the records and events it adds to the
//! protocol, the files the native storage thread keeps, and the one answer every request
//! the web's Renderer carries out gets.
//!
//! The browser's half, IndexedDB, is checked in the web renderer's Kotlin tests, and the
//! Renderer's handling of a request number that is already waiting in the desktop ones.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{
    BatchEncoder, HostEvent, Mutation, ProtocolError, decode_batch, decode_event, encode_event,
};
use compose_rust::runtime::*;
use compose_rust::schema::{
    BOUNDARY_SCHEMA, DesignSystem, EventPayload, IoStatus, PropertyKind, StorageOp, Theme,
};
use compose_rust::storage::{self, Backend, Platform, StorageError, testing};
use compose_rust::ui::*;
use std::cell::RefCell;
use std::future::Future;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};
use std::task::{Context, Poll, Wake, Waker};
use std::time::{Duration, Instant};
use support::Tree;

const ID: &str = "dev.darkpyonix.composerust.samples.todo";

/// The storage's settings, the environment and the storage thread are process wide, so the
/// tests take turns.
static SERIAL: Mutex<()> = Mutex::new(());

fn serial() -> MutexGuard<'static, ()> {
    SERIAL.lock().unwrap_or_else(|poison| poison.into_inner())
}

/// A directory of this test's own under the build's scratch directory, empty.
fn scratch(name: &str) -> PathBuf {
    let directory = PathBuf::from(env!("CARGO_TARGET_TMPDIR"))
        .join("fr79")
        .join(name);
    let _ = std::fs::remove_dir_all(&directory);
    std::fs::create_dir_all(&directory).expect("the scratch directory could not be made");
    directory
}

/// Points the storage at `directory` through the environment, as a test run would.
fn use_directory(directory: &std::path::Path) {
    // SAFETY: every test in this binary holds `SERIAL` while it touches the environment,
    // and nothing else in the process reads or writes it meanwhile.
    unsafe { std::env::set_var(storage::STORAGE_DIR_ENV, directory) };
}

fn no_directory() {
    // SAFETY: as in `use_directory`.
    unsafe { std::env::remove_var(storage::STORAGE_DIR_ENV) };
}

/// Starts over on the storage thread's files in a fresh directory, and returns it.
fn files(name: &str) -> PathBuf {
    let directory = scratch(name);
    use_directory(&directory);
    testing::reset(Some(ID), Backend::Files);
    directory
}

struct Unpark(std::thread::Thread);

impl Wake for Unpark {
    fn wake(self: Arc<Self>) {
        self.0.unpark();
    }
}

/// Runs a future to its end on this thread, the way an effect would, waking when the storage
/// thread answers.
fn block_on<F: Future>(future: F) -> F::Output {
    let waker = Waker::from(Arc::new(Unpark(std::thread::current())));
    let mut context = Context::from_waker(&waker);
    let mut future = std::pin::pin!(future);
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        if let Poll::Ready(value) = future.as_mut().poll(&mut context) {
            return value;
        }
        assert!(
            Instant::now() < deadline,
            "a storage operation did not finish in 30s"
        );
        std::thread::park_timeout(Duration::from_millis(20));
    }
}

/// Polls once. Whether the future had finished by then.
fn poll_once<F: Future + Unpin>(future: &mut F) -> Option<F::Output> {
    let waker = Waker::from(Arc::new(Unpark(std::thread::current())));
    let mut context = Context::from_waker(&waker);
    match std::pin::Pin::new(future).poll(&mut context) {
        Poll::Ready(value) => Some(value),
        Poll::Pending => None,
    }
}

fn entries(directory: &std::path::Path) -> Vec<String> {
    let mut names: Vec<String> = std::fs::read_dir(directory)
        .map(|entries| {
            entries
                .flatten()
                .map(|entry| entry.file_name().to_string_lossy().into_owned())
                .collect()
        })
        .unwrap_or_default();
    names.sort();
    names
}

// ----- the protocol ---------------------------------------------------------------------------

/// The request and both events keep their bytes both ways, sit in the vectors both sides
/// test against, and a word neither side knows is an error rather than a crash.
#[test]
fn fr79_storage_records_round_trip_and_unknown_enums_are_errors() {
    let _serial = serial();
    let value = "1\t0\t할 일\0".as_bytes();
    let call = Mutation::StorageCall {
        request: 11,
        op: StorageOp::Write,
        store: ID,
        key: "tasks.tsv",
        data: value,
    };
    let mut encoder = BatchEncoder::default();
    encoder.encode(&call).unwrap();
    let mut bytes = encoder.finish().unwrap().to_vec();
    assert_eq!(u16::from_le_bytes([bytes[12], bytes[13]]), 32, "tag 32");
    assert_eq!(
        u16::from_le_bytes([bytes[14], bytes[15]]),
        36,
        "header, request, operation, reserved half word and three references"
    );
    assert_eq!(decode_batch(&bytes).unwrap(), vec![call.clone()]);
    // Every operation the schema has round trips; one it does not is refused.
    for op in [
        StorageOp::Read,
        StorageOp::Write,
        StorageOp::Remove,
        StorageOp::Keys,
    ] {
        let mut encoder = BatchEncoder::default();
        let record = Mutation::StorageCall {
            request: 1,
            op,
            store: ID,
            key: "",
            data: &[],
        };
        encoder.encode(&record).unwrap();
        assert_eq!(
            decode_batch(encoder.finish().unwrap()).unwrap(),
            vec![record]
        );
    }
    bytes[20] = 9;
    bytes[21] = 0;
    assert_eq!(
        decode_batch(&bytes),
        Err(ProtocolError::InvalidValueKind(9))
    );

    let answer = HostEvent {
        node_id: 0,
        handler_id: 0,
        payload: EventPayload::IoAnswer {
            request: 11,
            status: IoStatus::Ok,
            data: &[0xea, 0xb0, 0x92, 0x00, 0xff],
        },
    };
    let mut encoded = Vec::new();
    encode_event(&answer, &mut encoded).unwrap();
    assert_eq!(u16::from_le_bytes([encoded[0], encoded[1]]), 57, "event 57");
    assert_eq!(u16::from_le_bytes([encoded[2], encoded[3]]), 32);
    assert_eq!(decode_event(&encoded).unwrap(), answer);
    let mut unknown = encoded.clone();
    unknown[20] = 99;
    assert_eq!(
        decode_event(&unknown),
        Err(ProtocolError::InvalidValueKind(99))
    );
    // The Host reports it and keeps running.
    testing::reset(Some(ID), Backend::Renderer);
    let mut host = ComposeHost::with_content(|| {});
    host.rebuild().unwrap();
    assert!(host.dispatch_event(&unknown).is_err());
    assert!(
        host.dispatch_event(&encoded).is_ok(),
        "the Host still answers"
    );

    let location = HostEvent {
        node_id: 0,
        handler_id: 0,
        payload: EventPayload::StorageLocation(
            "/data/user/0/dev.darkpyonix.composerust.samples.todo/files",
        ),
    };
    encode_event(&location, &mut encoded).unwrap();
    assert_eq!(u16::from_le_bytes([encoded[0], encoded[1]]), 59, "event 59");
    assert_eq!(decode_event(&encoded).unwrap(), location);

    // The vectors the Kotlin side reads carry the same records.
    let events = include_bytes!("vectors/events.bin");
    assert_eq!(decode_event(&events[1093..1130]).unwrap(), answer);
    assert_eq!(decode_event(&events[1237..1319]).unwrap(), location);
    let mutations = include_bytes!("vectors/mutations.bin");
    let calls: Vec<Mutation<'_>> = decode_batch(mutations)
        .unwrap()
        .into_iter()
        .filter(|mutation| matches!(mutation, Mutation::StorageCall { .. }))
        .collect();
    assert_eq!(calls, vec![call]);
}

/// Storage adds a record and two events to the protocol and nothing to the boundary.
#[test]
fn pr2_boundary_surface_unchanged_by_storage() {
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
    assert_eq!(exports, symbols.len());
    let storage = include_str!("../src/storage.rs");
    assert!(!storage.contains("no_mangle"));
    assert!(!storage.contains("extern \"C\""));
    assert!(!storage.contains("wasm_bindgen"));
}

// ----- the native files -----------------------------------------------------------------------

#[test]
fn fr79_write_then_read_returns_the_same_bytes() {
    let _serial = serial();
    let directory = files("same-bytes");
    let value = "할 일\0끝\u{1F642}".as_bytes().to_vec();

    assert_eq!(block_on(storage::read("draft")), Ok(None), "first run");
    block_on(storage::write("draft", value.clone())).unwrap();
    assert_eq!(block_on(storage::read("draft")), Ok(Some(value.clone())));
    assert_eq!(std::fs::read(directory.join("draft")).unwrap(), value);
    assert_eq!(
        block_on(storage::read_string("draft")),
        Ok(Some("할 일\0끝\u{1F642}".to_owned()))
    );

    block_on(storage::write("raw", vec![0xff, 0xfe])).unwrap();
    assert_eq!(
        block_on(storage::read_string("raw")),
        Err(StorageError::NotUtf8)
    );

    block_on(storage::remove("draft")).unwrap();
    assert_eq!(block_on(storage::read("draft")), Ok(None));
    block_on(storage::remove("draft")).expect("removing nothing succeeds");

    for key in ["c_2", "a-1", "b.txt"] {
        block_on(storage::write(key, "x")).unwrap();
    }
    assert_eq!(
        block_on(storage::keys()),
        Ok(vec![
            "a-1".to_owned(),
            "b.txt".to_owned(),
            "c_2".to_owned(),
            "raw".to_owned()
        ])
    );
    no_directory();
}

#[test]
fn fr79_operations_on_one_key_apply_in_call_order() {
    let _serial = serial();
    files("call-order");
    for round in 0..20 {
        let first = storage::write("order", format!("a{round}"));
        let second = storage::write("order", format!("b{round}"));
        let read = storage::read_string("order");
        // Neither write is awaited before the read, or at all.
        drop(first);
        drop(second);
        assert_eq!(block_on(read), Ok(Some(format!("b{round}"))));
    }
    no_directory();
}

#[test]
fn fr79_invalid_keys_are_refused_before_any_io() {
    let _serial = serial();
    let directory = files("invalid-keys");
    let long = "k".repeat(129);
    for key in [
        "Upper",
        "a/b",
        "..",
        ".hidden",
        "",
        long.as_str(),
        "a\\b",
        "공백",
    ] {
        assert!(!storage::is_valid_key(key), "{key:?}");
        assert_eq!(
            block_on(storage::write(key, "x")),
            Err(StorageError::InvalidKey)
        );
        assert_eq!(block_on(storage::read(key)), Err(StorageError::InvalidKey));
        assert_eq!(
            block_on(storage::remove(key)),
            Err(StorageError::InvalidKey)
        );
    }
    assert!(entries(&directory).is_empty(), "no file was made");
    let longest = "k".repeat(128);
    assert!(storage::is_valid_key(&longest));
    block_on(storage::write(longest.as_str(), "x")).unwrap();
    no_directory();
}

#[test]
fn fr79_an_oversized_value_is_refused_and_the_old_value_stays() {
    let _serial = serial();
    files("oversized");
    block_on(storage::write("state", "old")).unwrap();
    let huge = vec![7_u8; storage::MAX_VALUE_BYTES + 1];
    assert_eq!(
        block_on(storage::write("state", huge)),
        Err(StorageError::TooLarge {
            len: storage::MAX_VALUE_BYTES + 1,
            limit: storage::MAX_VALUE_BYTES,
        })
    );
    assert_eq!(
        block_on(storage::read_string("state")),
        Ok(Some("old".to_owned()))
    );
    let largest = vec![7_u8; storage::MAX_VALUE_BYTES];
    block_on(storage::write("state", largest.clone())).unwrap();
    assert_eq!(block_on(storage::read("state")), Ok(Some(largest)));
    no_directory();
}

/// A crash can stop a write at two places that matter: with the new bytes only in the
/// temporary file, or just after the rename. The test makes each state on disk by hand.
#[test]
fn fr79_a_write_is_atomic_across_a_crash() {
    let _serial = serial();
    let directory = files("crash");
    block_on(storage::write("session", "old")).unwrap();

    // Stopped before the rename: the new bytes are only in the temporary file.
    let interrupted = directory.join(storage::temporary_name("session"));
    std::fs::write(&interrupted, "new, half writ").unwrap();
    assert_eq!(
        block_on(storage::read_string("session")),
        Ok(Some("old".to_owned()))
    );
    assert_eq!(
        block_on(storage::keys()),
        Ok(vec!["session".to_owned()]),
        "a temporary file is never a key"
    );

    // Stopped just after the rename: the whole new value.
    std::fs::rename(&interrupted, directory.join("session")).unwrap();
    assert_eq!(
        block_on(storage::read_string("session")),
        Ok(Some("new, half writ".to_owned()))
    );

    // The next run finds a temporary file a crash left and removes it.
    let left = directory.join(storage::temporary_name("session"));
    std::fs::write(&left, "left behind").unwrap();
    testing::reset(Some(ID), Backend::Files);
    assert_eq!(
        block_on(storage::read_string("session")),
        Ok(Some("new, half writ".to_owned()))
    );
    assert!(!left.exists(), "the leftover temporary file is gone");
    assert_eq!(entries(&directory), vec!["session".to_owned()]);
    no_directory();
}

#[test]
fn fr79_native_storage_io_runs_off_the_ui_thread() {
    let _serial = serial();
    files("off-thread");
    block_on(storage::write("where", "here")).unwrap();
    let writer = testing::last_io_thread().expect("the write did its input and output");
    assert_ne!(writer, std::thread::current().id());
    testing::reset(Some(ID), Backend::Files);
    block_on(storage::read("where")).unwrap();
    let reader = testing::last_io_thread().expect("the read did its input and output");
    assert_ne!(reader, std::thread::current().id());
    no_directory();
}

#[test]
fn fr79_storage_lives_in_the_platform_data_directory() {
    let _serial = serial();
    let env = |pairs: &'static [(&'static str, &'static str)]| {
        move |name: &str| {
            pairs
                .iter()
                .find(|(key, _)| *key == name)
                .map(|(_, value)| (*value).to_owned())
        }
    };
    let home = env(&[("HOME", "/Users/me")]);
    assert_eq!(
        storage::platform_directory(Platform::MacOs, ID, None, home),
        Ok(format!(
            "/Users/me/Library/Application Support/{ID}/storage"
        ))
    );
    let container = env(&[("HOME", "/var/mobile/Containers/Data/Application/A1/")]);
    assert_eq!(
        storage::platform_directory(Platform::Ios, ID, None, container),
        Ok(format!(
            "/var/mobile/Containers/Data/Application/A1/Library/Application Support/{ID}/storage"
        ))
    );
    let xdg = env(&[("HOME", "/home/me"), ("XDG_DATA_HOME", "/data/me")]);
    assert_eq!(
        storage::platform_directory(Platform::Linux, ID, None, xdg),
        Ok(format!("/data/me/{ID}/storage"))
    );
    let relative = env(&[("HOME", "/home/me"), ("XDG_DATA_HOME", "data")]);
    assert_eq!(
        storage::platform_directory(Platform::Linux, ID, None, relative),
        Ok(format!("/home/me/.local/share/{ID}/storage")),
        "a relative XDG_DATA_HOME is ignored, as the XDG rules say"
    );
    let roaming = env(&[("APPDATA", "C:\\Users\\me\\AppData\\Roaming")]);
    assert_eq!(
        storage::platform_directory(Platform::Windows, ID, None, roaming),
        Ok(format!("C:\\Users\\me\\AppData\\Roaming\\{ID}\\storage"))
    );
    let nothing = env(&[]);
    assert_eq!(
        storage::platform_directory(
            Platform::Android,
            ID,
            Some("/data/user/0/app/files"),
            nothing
        ),
        Ok("/data/user/0/app/files/compose-rust/storage".to_owned())
    );
    assert!(storage::platform_directory(Platform::Android, ID, None, env(&[])).is_err());
    assert!(storage::platform_directory(Platform::Windows, ID, None, env(&[])).is_err());
    assert!(storage::platform_directory(Platform::MacOs, ID, None, env(&[])).is_err());
    assert!(storage::platform_directory(Platform::Web, ID, None, env(&[])).is_err());
    let overridden = env(&[
        ("HOME", "/Users/me"),
        ("APPDATA", "C:\\Roaming"),
        ("COMPOSE_RUST_STORAGE_DIR", "/tmp/fixture"),
    ]);
    for platform in [
        Platform::MacOs,
        Platform::Ios,
        Platform::Linux,
        Platform::Windows,
        Platform::Android,
    ] {
        assert_eq!(
            storage::platform_directory(platform, ID, None, overridden),
            Ok("/tmp/fixture".to_owned()),
            "{platform:?}"
        );
    }

    // With the variable set, the files go under it and nowhere else.
    let directory = files("environment");
    block_on(storage::write("only-here", "x")).unwrap();
    assert_eq!(entries(&directory), vec!["only-here".to_owned()]);
    no_directory();
}

#[Composable]
fn Nothing() {}

#[test]
fn fr79_without_an_identifier_storage_is_unavailable() {
    let _serial = serial();
    let directory = scratch("no-identifier");
    use_directory(&directory);
    testing::reset(None, Backend::Files);
    let said = |result: Result<(), StorageError>| match result {
        Err(StorageError::Unavailable(reason)) => {
            assert!(reason.contains("LaunchBuilder"), "{reason}");
            assert!(reason.contains("identifier"), "{reason}");
        }
        other => panic!("expected unavailable, got {other:?}"),
    };
    said(block_on(storage::write("a", "x")));
    said(block_on(storage::read("a")).map(|_| ()));
    said(block_on(storage::read_string("a")).map(|_| ()));
    said(block_on(storage::remove("a")));
    said(block_on(storage::keys()).map(|_| ()));
    assert!(entries(&directory).is_empty());
    no_directory();

    for bad in [
        "",
        "has space",
        "a/b",
        "..",
        ".hidden",
        "a..b",
        "한글",
        "x".repeat(256).as_str(),
    ] {
        let problem = storage::validate_identifier(bad).expect_err(bad);
        assert!(!problem.is_empty());
    }
    storage::validate_identifier(ID).unwrap();
    storage::validate_identifier(&"x".repeat(255)).unwrap();

    // A bad one ends the launch before anything starts, with a failing status.
    let status = compose_rust::LaunchBuilder::new()
        .identifier("not an identifier!")
        .with_mode(compose_rust::LoopMode::Platform)
        .try_launch(Nothing);
    assert_eq!(status, compose_rust::boundary::STATUS_INVALID_IDENTIFIER);
    assert_ne!(status, 0);
    // A good one is what storage uses.
    let status = compose_rust::LaunchBuilder::new()
        .identifier(ID)
        .with_mode(compose_rust::LoopMode::Platform)
        .try_launch(Nothing);
    assert_eq!(status, 0);
    assert_eq!(storage::identifier().as_deref(), Some(ID));
}

/// Every sample that has a bundle names the same identifier in its launch.
#[test]
fn fr79_sample_identifiers_match_their_bundle_identifier() {
    let samples = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../samples");
    let mut checked = 0;
    for entry in std::fs::read_dir(&samples).unwrap().flatten() {
        let bundle = entry.path().join("Dioxus.toml");
        let Ok(toml) = std::fs::read_to_string(&bundle) else {
            continue;
        };
        let name = entry.file_name().to_string_lossy().into_owned();
        let section = toml
            .split("\n[bundle]")
            .nth(1)
            .unwrap_or_else(|| panic!("{name}: Dioxus.toml has no [bundle]"));
        let section = section.split("\n[").next().unwrap();
        let expected = section
            .lines()
            .find_map(|line| {
                let line = line.trim();
                let value = line.strip_prefix("identifier")?.trim().strip_prefix('=')?;
                Some(value.trim().trim_matches('"').to_owned())
            })
            .unwrap_or_else(|| panic!("{name}: [bundle] has no identifier"));
        let source = std::fs::read_to_string(entry.path().join("src/lib.rs")).unwrap();
        let given: Vec<&str> = source
            .split(".identifier(\"")
            .skip(1)
            .map(|rest| rest.split('"').next().unwrap())
            .collect();
        assert_eq!(
            given,
            vec![expected.as_str()],
            "{name}: LaunchBuilder::identifier must be given once, as [bundle] identifier"
        );
        storage::validate_identifier(&expected).unwrap();
        checked += 1;
    }
    assert!(checked >= 2, "todo and notepad at least");
}

// ----- the Renderer's half, as the web has it -------------------------------------------------

thread_local! {
    static RESULTS: RefCell<Vec<(&'static str, String)>> = const { RefCell::new(Vec::new()) };
}

fn record(label: &'static str, outcome: impl std::fmt::Debug) {
    RESULTS.with_borrow_mut(|results| results.push((label, format!("{outcome:?}"))));
}

fn results() -> Vec<(&'static str, String)> {
    RESULTS.with_borrow(Clone::clone)
}

#[Composable]
fn Loader() {
    launched_effect((), || async {
        let outcome = storage::read("loader").await;
        record("loader", outcome);
    });
}

#[Composable]
fn Savers() {
    let scope = remember_coroutine_scope();
    let saving = scope.clone();
    Button("save").on_click(move || {
        saving.launch(async {
            let outcome = storage::write("draft", "v").await;
            record("save", outcome);
        })
    });
    Button("load").on_click(move || {
        scope.launch(async {
            let outcome = storage::read("draft").await;
            record("load", outcome);
        })
    });
    let shown = remember(|| mutable_state_of(true));
    let hide = shown;
    Button("hide").on_click(move || hide.set(false));
    if shown.get() {
        Loader();
    }
}

/// The storage calls a batch carries, as owned values.
fn calls(batch: &[u8]) -> Vec<(u32, StorageOp, String, String, Vec<u8>)> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::StorageCall {
                request,
                op,
                store,
                key,
                data,
            } => Some((request, op, store.to_owned(), key.to_owned(), data.to_vec())),
            _ => None,
        })
        .collect()
}

fn answer(request: u32, status: IoStatus, data: &[u8]) -> HostEvent<'_> {
    HostEvent {
        node_id: 0,
        handler_id: 0,
        payload: EventPayload::IoAnswer {
            request,
            status,
            data,
        },
    }
}

struct Session {
    host: ComposeHost,
    tree: Tree,
}

impl Session {
    fn press(&mut self, label: &str) -> Vec<(u32, StorageOp, String, String, Vec<u8>)> {
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
        let batch = batch.to_vec();
        self.tree.apply(&batch);
        calls(&batch)
    }

    fn answer(&mut self, event: HostEvent<'_>) {
        let (batch, _) = self.host.dispatch(event).expect("the answer dispatches");
        let batch = batch.to_vec();
        self.tree.apply(&batch);
    }
}

#[test]
fn fr79_every_storage_request_gets_exactly_one_answer() {
    let _serial = serial();
    testing::reset(Some(ID), Backend::Renderer);
    RESULTS.with_borrow_mut(Vec::clear);
    let mut host = ComposeHost::with_theme(Savers, Theme::unified(DesignSystem::Material3));
    let mut tree = Tree::default();
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    tree.apply(&first);
    let mut session = Session { host, tree };

    // The effect's read went out with the first batch, addressed to this application.
    let loader = calls(&first);
    assert_eq!(loader.len(), 1, "one read from the effect");
    assert_eq!(loader[0].1, StorageOp::Read);
    assert_eq!(loader[0].2, ID);
    assert_eq!(loader[0].3, "loader");

    // The component that awaited it leaves before the answer comes. The answer is still
    // taken, once, and does nothing.
    assert!(session.press("hide").is_empty());
    session.answer(answer(loader[0].0, IoStatus::Ok, b"late"));
    assert!(results().is_empty(), "nobody heard the late answer");
    assert_eq!(testing::waiting(), 0);

    // One press is one request, with the value in it, and its one answer is heard once.
    let save = session.press("save");
    assert_eq!(save.len(), 1);
    let (request, op, store, key, data) = save[0].clone();
    assert_eq!(
        (op, store.as_str(), key.as_str(), data.as_slice()),
        (StorageOp::Write, ID, "draft", b"v".as_slice())
    );
    assert_ne!(request, loader[0].0, "a new number");
    session.answer(answer(request, IoStatus::Ok, &[]));
    assert_eq!(results(), vec![("save", "Ok(())".to_owned())]);
    session.answer(answer(request, IoStatus::Ok, &[]));
    assert_eq!(
        results().len(),
        1,
        "a second answer to the same request is ignored"
    );
    session.answer(answer(9999, IoStatus::Ok, &[]));
    assert_eq!(
        results().len(),
        1,
        "an answer to a request nobody made is ignored"
    );

    // A read the browser has a value for.
    let load = session.press("load");
    assert_eq!(load.len(), 1);
    session.answer(answer(load[0].0, IoStatus::Ok, "값\0".as_bytes()));
    assert_eq!(
        results()[1],
        (
            "load",
            format!(
                "{:?}",
                Ok::<_, StorageError>(Some("값\0".as_bytes().to_vec()))
            )
        )
    );

    // A read for nothing.
    let load = session.press("load");
    session.answer(answer(load[0].0, IoStatus::NotFound, &[]));
    assert_eq!(results()[2], ("load", "Ok(None)".to_owned()));

    // A full quota is an error the application reads, not an exception.
    let save = session.press("save");
    session.answer(answer(save[0].0, IoStatus::QuotaExceeded, &[]));
    assert_eq!(results()[3], ("save", "Err(QuotaExceeded)".to_owned()));
    assert_eq!(testing::waiting(), 0, "every request was answered");
}

/// Until the web can carry an answer larger than its event buffer, a read that finds such a
/// value gets one too-large answer carrying its length, and the application hears how large
/// it was and what one answer could carry.
#[test]
fn fr79_web_an_answer_over_the_event_buffer_is_too_large() {
    let _serial = serial();
    testing::reset(Some(ID), Backend::Renderer);
    RESULTS.with_borrow_mut(Vec::clear);
    let mut host = ComposeHost::with_theme(Savers, Theme::unified(DesignSystem::Material3));
    let mut tree = Tree::default();
    let first = host.rebuild().unwrap().to_vec();
    tree.apply(&first);
    let mut session = Session { host, tree };
    assert!(session.press("hide").is_empty());
    let load = session.press("load");
    let length = 1024 * 1024_u32;
    session.answer(answer(load[0].0, IoStatus::TooLarge, &length.to_le_bytes()));
    let expected: Result<Option<Vec<u8>>, StorageError> = Err(StorageError::TooLarge {
        len: length as usize,
        limit: storage::WEB_ANSWER_LIMIT,
    });
    assert_eq!(
        results().last().cloned(),
        Some(("load", format!("{expected:?}")))
    );
    assert_eq!(storage::WEB_ANSWER_LIMIT, 4096 - 32);
}

#[test]
fn fr79_android_storage_waits_for_its_location() {
    let _serial = serial();
    no_directory();
    let files_dir = scratch("android-files");
    testing::reset(Some(ID), Backend::Android);

    let mut early_write = Box::pin(storage::write("early", "before the location"));
    let mut early_read = Box::pin(storage::read_string("early"));
    assert_eq!(testing::held(), 2, "both wait for the location");
    std::thread::sleep(Duration::from_millis(50));
    assert!(poll_once(&mut early_write).is_none());
    assert!(poll_once(&mut early_read).is_none());

    let mut host = ComposeHost::with_content(|| {});
    host.rebuild().unwrap();
    let path = files_dir.to_string_lossy().into_owned();
    host.dispatch(HostEvent {
        node_id: 0,
        handler_id: 0,
        payload: EventPayload::StorageLocation(&path),
    })
    .expect("the location dispatches");
    assert_eq!(testing::held(), 0);

    assert_eq!(block_on(early_write), Ok(()));
    assert_eq!(
        block_on(early_read),
        Ok(Some("before the location".to_owned()))
    );
    let stored = files_dir.join("compose-rust").join("storage").join("early");
    assert_eq!(
        std::fs::read_to_string(&stored).unwrap(),
        "before the location"
    );
    // Once it is known, an operation goes straight to the storage thread.
    block_on(storage::write("later", "after")).unwrap();
    assert!(
        files_dir
            .join("compose-rust")
            .join("storage")
            .join("later")
            .exists()
    );
}
