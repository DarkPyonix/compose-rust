//! Named values only this application reads and writes, kept until the next run.
//!
//! A task list, a draft, a setting: state an application has to find again after it
//! restarts, and that the user does not expect to see in a file manager. Files the user
//! picked are something else, and go through the file dialog.
//!
//! The same code runs everywhere, and every operation is a future, because the browser
//! has no synchronous storage the page can wait on:
//!
//! ```ignore
//! scope.launch(async move {
//!     let saved = storage::read_string("tasks.tsv").await;
//!     // ...
//!     storage::write("tasks.tsv", text).await?;
//! });
//! ```
//!
//! Where the values live:
//!
//! - macOS and iOS: `$HOME/Library/Application Support/<identifier>/storage/<key>`;
//! - Linux: `$XDG_DATA_HOME/<identifier>/storage/<key>`, or
//!   `~/.local/share/<identifier>/storage/<key>` without it;
//! - Windows: `%APPDATA%\<identifier>\storage\<key>`;
//! - Android: `<files directory>/compose-rust/storage/<key>`, the directory the Renderer
//!   reports at start, because only a `Context` knows it;
//! - the web: the IndexedDB database `compose-rust/<identifier>`, store `values`.
//!
//! `COMPOSE_RUST_STORAGE_DIR`, when set, replaces the whole `.../storage` directory on every
//! platform but the web, so a test or a development run never touches the user's real data.
//!
//! On the native platforms the files are read and written by one storage thread of the
//! Host's, never on the UI thread, and a write goes to a temporary file in the same
//! directory that replaces the old one with a rename, so a crash leaves the old value or the
//! new one and never half of either. On the web the Renderer does the work in the browser
//! and answers each request with one event.
//!
//! Each application has its own storage, named by the identifier it gives
//! `LaunchBuilder::identifier`. An application that gives none has no storage: every
//! operation answers [`StorageError::Unavailable`] and says so. The identifier is never
//! guessed from a bundle, a package name or an executable name, because the same program
//! would then see different storage depending on how it was started.

use std::collections::{HashMap, HashSet, VecDeque};
use std::fmt;
use std::future::Future;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::task::{Context, Poll, Waker};

use crate::schema::{IoStatus, StorageOp};

/// The longest key, in bytes.
pub const MAX_KEY_BYTES: usize = 128;

/// The largest value one key holds, in bytes.
///
/// The storage is for application state, and a value travels whole, so it has a ceiling.
/// Anything larger belongs in a file the user picked.
pub const MAX_VALUE_BYTES: usize = 16 * 1024 * 1024;

/// The longest application identifier, in bytes.
pub const MAX_IDENTIFIER_BYTES: usize = 255;

/// The environment variable that replaces the storage directory on the native platforms.
pub const STORAGE_DIR_ENV: &str = "COMPOSE_RUST_STORAGE_DIR";

/// The most bytes one answer can carry back from the web Renderer today: its event buffer,
/// less the answer's own record. A larger value read there is reported as too large rather
/// than cut short.
pub const WEB_ANSWER_LIMIT: usize =
    crate::schema::WEB_EVENT_BUFFER_BYTES as usize - IO_ANSWER_RECORD_BYTES;

/// The `IoAnswer` record before the bytes it carries.
const IO_ANSWER_RECORD_BYTES: usize = 32;

/// Why a storage operation did not do what it was asked.
#[non_exhaustive]
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum StorageError {
    /// The key breaks the rules: 1 to 128 bytes of `a-z`, `0-9`, `-`, `_` and `.`, not
    /// starting with `.`. Nothing was read or written.
    InvalidKey,
    /// The value is `len` bytes, more than the `limit` it may be. Nothing was written, and
    /// nothing was cut short.
    TooLarge { len: usize, limit: usize },
    /// The disk or the browser's quota is full. The value that was there is still there.
    QuotaExceeded,
    /// This run has no storage: no application identifier, no directory the platform can
    /// name, or a browser that refuses storage. The text says which.
    Unavailable(String),
    /// [`read_string`] found a value that is not UTF-8.
    NotUtf8,
    /// Any other failure, in the words the platform gave.
    Io(String),
}

impl fmt::Display for StorageError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            StorageError::InvalidKey => write!(
                f,
                "a storage key is 1 to {MAX_KEY_BYTES} bytes of a-z, 0-9, '-', '_' and '.', \
                 and does not start with '.'"
            ),
            StorageError::TooLarge { len, limit } => {
                write!(
                    f,
                    "the value is {len} bytes, more than the {limit} bytes allowed"
                )
            }
            StorageError::QuotaExceeded => {
                write!(f, "there is no room left for this application's storage")
            }
            StorageError::Unavailable(reason) => write!(f, "storage is unavailable: {reason}"),
            StorageError::NotUtf8 => write!(f, "the stored value is not UTF-8 text"),
            StorageError::Io(reason) => write!(f, "storage failed: {reason}"),
        }
    }
}

impl std::error::Error for StorageError {}

/// The sentence every operation answers with when the application named no identifier.
const NO_IDENTIFIER: &str = "this run has no application identifier, so there is no storage \
     to use. Give one with LaunchBuilder::new().identifier(\"com.example.app\"), the same \
     value as [bundle] identifier in Dioxus.toml";

// ----- the API --------------------------------------------------------------------------------

/// Reads the value stored under `key`. `Ok(None)` when there is none, which is what the
/// first run of an application finds.
///
/// The operation is placed when this is called, not when the future is first polled, so
/// operations on one key apply in the order they were called even if nobody awaits them.
pub fn read(
    key: impl Into<String>,
) -> impl Future<Output = Result<Option<Vec<u8>>, StorageError>> + 'static {
    let pending = submit(StorageOp::Read, key.into(), Vec::new());
    async move {
        match pending.await? {
            Answer::Value(value) => Ok(value),
            Answer::Done | Answer::Keys(_) => Ok(None),
        }
    }
}

/// [`read`] for a value that is text. [`StorageError::NotUtf8`] when it is not.
pub fn read_string(
    key: impl Into<String>,
) -> impl Future<Output = Result<Option<String>, StorageError>> + 'static {
    let read = read(key);
    async move {
        match read.await? {
            Some(bytes) => String::from_utf8(bytes)
                .map(Some)
                .map_err(|_| StorageError::NotUtf8),
            None => Ok(None),
        }
    }
}

/// Stores `value` under `key`, replacing what was there.
///
/// The write is atomic: a crash or a power cut in the middle of it leaves either the old
/// value or the new one. A value over [`MAX_VALUE_BYTES`] is refused with
/// [`StorageError::TooLarge`] and the old value stays.
pub fn write(
    key: impl Into<String>,
    value: impl Into<Vec<u8>>,
) -> impl Future<Output = Result<(), StorageError>> + 'static {
    let pending = submit(StorageOp::Write, key.into(), value.into());
    async move { pending.await.map(|_| ()) }
}

/// Removes the value stored under `key`. Removing a key that holds nothing succeeds.
pub fn remove(key: impl Into<String>) -> impl Future<Output = Result<(), StorageError>> + 'static {
    let pending = submit(StorageOp::Remove, key.into(), Vec::new());
    async move { pending.await.map(|_| ()) }
}

/// Every key this application has stored a value under, in byte order.
pub fn keys() -> impl Future<Output = Result<Vec<String>, StorageError>> + 'static {
    let pending = submit(StorageOp::Keys, String::new(), Vec::new());
    async move {
        match pending.await? {
            Answer::Keys(keys) => Ok(keys),
            Answer::Value(_) | Answer::Done => Ok(Vec::new()),
        }
    }
}

/// Whether `key` follows the rules for a key: 1 to [`MAX_KEY_BYTES`] bytes of lower case
/// ASCII letters, digits, `-`, `_` and `.`, not starting with `.`.
///
/// Lower case only so two keys never become one file on a file system that ignores case,
/// which is the default on macOS and Windows. The key is the file name as it is, so
/// nothing that a path would read as a separator or a parent is allowed.
pub fn is_valid_key(key: &str) -> bool {
    !key.is_empty()
        && key.len() <= MAX_KEY_BYTES
        && !key.starts_with('.')
        && key
            .bytes()
            .all(|byte| matches!(byte, b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.'))
}

/// Checks an application identifier: 1 to [`MAX_IDENTIFIER_BYTES`] bytes of ASCII letters,
/// digits, `.` and `-`. It names a directory and a database as it is, so nothing else is
/// allowed. The error says what is wrong with it.
pub fn validate_identifier(identifier: &str) -> Result<(), String> {
    if identifier.is_empty() {
        return Err(
            "the application identifier is empty; give a reverse domain name such \
             as \"com.example.app\""
                .to_owned(),
        );
    }
    if identifier.len() > MAX_IDENTIFIER_BYTES {
        return Err(format!(
            "the application identifier is {} bytes, longer than the {MAX_IDENTIFIER_BYTES} \
             bytes a directory name may safely be",
            identifier.len()
        ));
    }
    if let Some(bad) = identifier
        .chars()
        .find(|c| !(c.is_ascii_alphanumeric() || *c == '.' || *c == '-'))
    {
        return Err(format!(
            "the application identifier {identifier:?} contains {bad:?}; only ASCII letters, \
             digits, '.' and '-' are allowed, because it is used as a directory and a \
             database name as it is"
        ));
    }
    if identifier.starts_with('.') || identifier.contains("..") {
        return Err(format!(
            "the application identifier {identifier:?} starts with '.' or holds \"..\", \
             which a path would read as a hidden or a parent directory"
        ));
    }
    Ok(())
}

/// The identifier the application launched with, if it gave one.
pub fn identifier() -> Option<String> {
    config().identifier.clone()
}

/// Sets the identifier for the run. Called by the launch, after checking it.
pub(crate) fn set_identifier(identifier: Option<&str>) {
    config().identifier = identifier.map(str::to_owned);
}

// ----- where the files go -----------------------------------------------------------------

/// The platforms whose storage directory is worked out differently.
#[doc(hidden)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Platform {
    MacOs,
    Ios,
    Linux,
    Windows,
    Android,
    Web,
}

impl Platform {
    /// The platform this build runs on.
    pub fn current() -> Self {
        if cfg!(target_family = "wasm") {
            Platform::Web
        } else if cfg!(target_os = "android") {
            Platform::Android
        } else if cfg!(target_os = "ios") {
            Platform::Ios
        } else if cfg!(target_os = "macos") {
            Platform::MacOs
        } else if cfg!(target_os = "windows") {
            Platform::Windows
        } else {
            Platform::Linux
        }
    }
}

/// The directory a platform keeps an application's values in, as the platform writes paths.
///
/// `env` answers an environment variable. `android_files` is the files directory the
/// Android Renderer reported, if it has. The error says what is missing.
///
/// Every value comes from the environment, so the Host calls no platform function to find
/// it, and that is why it can be tested for any platform on any other.
#[doc(hidden)]
pub fn platform_directory(
    platform: Platform,
    identifier: &str,
    android_files: Option<&str>,
    env: impl Fn(&str) -> Option<String>,
) -> Result<String, String> {
    let set = |name: &str| env(name).filter(|value| !value.is_empty());
    if platform == Platform::Web {
        return Err("the web keeps its values in the browser, not in a directory".to_owned());
    }
    if let Some(directory) = set(STORAGE_DIR_ENV) {
        return Ok(directory);
    }
    let home = || {
        set("HOME").ok_or_else(|| {
            "HOME is not set, so there is no directory to keep this application's values in"
                .to_owned()
        })
    };
    match platform {
        Platform::MacOs | Platform::Ios => Ok(format!(
            "{}/Library/Application Support/{identifier}/storage",
            trim_end(&home()?, '/')
        )),
        Platform::Linux => match set("XDG_DATA_HOME").filter(|value| value.starts_with('/')) {
            Some(data) => Ok(format!("{}/{identifier}/storage", trim_end(&data, '/'))),
            None => Ok(format!(
                "{}/.local/share/{identifier}/storage",
                trim_end(&home()?, '/')
            )),
        },
        Platform::Windows => {
            let roaming = set("APPDATA").ok_or_else(|| {
                "APPDATA is not set, so there is no directory to keep this application's \
                 values in"
                    .to_owned()
            })?;
            Ok(format!(
                "{}\\{identifier}\\storage",
                trim_end(&roaming, '\\')
            ))
        }
        Platform::Android => match android_files.filter(|value| !value.is_empty()) {
            Some(files) => Ok(format!("{}/compose-rust/storage", trim_end(files, '/'))),
            None => Err(
                "the Android Renderer has not said where the application's files \
                 directory is"
                    .to_owned(),
            ),
        },
        Platform::Web => unreachable!("answered above"),
    }
}

fn trim_end(value: &str, separator: char) -> &str {
    let trimmed = value.trim_end_matches(separator);
    if trimmed.is_empty() { value } else { trimmed }
}

/// The name a write's temporary file has before the rename, in the key's own directory.
///
/// It starts with `.`, which no key does, so it can never be taken for a value, and it ends
/// in `.tmp`, which is how the storage thread finds one a crash left behind.
#[doc(hidden)]
pub fn temporary_name(key: &str) -> String {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    format!(
        ".{key}.{}-{}.tmp",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    )
}

fn is_temporary(name: &str) -> bool {
    name.starts_with('.') && name.ends_with(".tmp")
}

// ----- configuration ------------------------------------------------------------------------

/// Who carries an operation out.
#[doc(hidden)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Backend {
    /// The Host's storage thread, in a directory the environment names.
    Files,
    /// The Host's storage thread, in the directory the Android Renderer reports.
    Android,
    /// The Renderer, through a `StorageCall` command and its `IoAnswer`. The web.
    Renderer,
}

impl Backend {
    fn for_this_build() -> Self {
        match Platform::current() {
            Platform::Web => Backend::Renderer,
            Platform::Android => Backend::Android,
            _ => Backend::Files,
        }
    }
}

struct Config {
    identifier: Option<String>,
    backend: Option<Backend>,
    /// The files directory the Android Renderer reported.
    android_files: Option<String>,
    /// Operations started before the Android Renderer said where the files go, in order.
    held: Vec<Job>,
    /// Bumped by a test that starts over, so the storage thread looks for leftover
    /// temporary files again as it would at the start of a run.
    run: u64,
}

static CONFIG: Mutex<Config> = Mutex::new(Config {
    identifier: None,
    backend: None,
    android_files: None,
    held: Vec::new(),
    run: 0,
});

fn config() -> std::sync::MutexGuard<'static, Config> {
    CONFIG.lock().unwrap_or_else(|poison| poison.into_inner())
}

/// Hooks for tests, which run several storages one after the other in one process.
#[doc(hidden)]
pub mod testing {
    use super::*;

    /// Starts over: the given identifier and backend, nothing held, nothing waiting, and
    /// leftover temporary files looked for again as at the start of a run.
    pub fn reset(identifier: Option<&str>, backend: Backend) {
        let mut config = config();
        config.identifier = identifier.map(str::to_owned);
        config.backend = Some(backend);
        config.android_files = None;
        config.held.clear();
        config.run += 1;
        drop(config);
        OUTBOX.with_borrow_mut(Vec::clear);
        WAITING.with_borrow_mut(HashMap::clear);
        *LAST_IO_THREAD
            .lock()
            .unwrap_or_else(|poison| poison.into_inner()) = None;
    }

    /// The thread the storage thread last did input or output on.
    pub fn last_io_thread() -> Option<std::thread::ThreadId> {
        *LAST_IO_THREAD
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
    }

    /// How many operations wait for the Android Renderer's directory.
    pub fn held() -> usize {
        config().held.len()
    }

    /// How many requests wait for the Renderer's answer.
    pub fn waiting() -> usize {
        WAITING.with_borrow(HashMap::len)
    }
}

// ----- the futures ----------------------------------------------------------------------------

/// What a finished operation hands back, before the public function shapes it.
#[derive(Debug)]
enum Answer {
    Value(Option<Vec<u8>>),
    Done,
    Keys(Vec<String>),
}

type Outcome = Result<Answer, StorageError>;

#[derive(Default)]
struct Slot {
    outcome: Option<Outcome>,
    waker: Option<Waker>,
}

type Shared = Arc<Mutex<Slot>>;

fn complete(shared: &Shared, outcome: Outcome) {
    let waker = {
        let mut slot = shared.lock().unwrap_or_else(|poison| poison.into_inner());
        if slot.outcome.is_some() {
            return;
        }
        slot.outcome = Some(outcome);
        slot.waker.take()
    };
    if let Some(waker) = waker {
        waker.wake();
    }
}

/// One operation on its way. Ready once whoever carries it out has answered.
struct Pending {
    shared: Shared,
}

impl Future for Pending {
    type Output = Outcome;

    fn poll(self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<Outcome> {
        let mut slot = self
            .shared
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        match slot.outcome.take() {
            Some(outcome) => Poll::Ready(outcome),
            None => {
                slot.waker = Some(context.waker().clone());
                Poll::Pending
            }
        }
    }
}

fn ready(outcome: Outcome) -> Pending {
    let shared = Shared::default();
    shared
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
        .outcome = Some(outcome);
    Pending { shared }
}

/// Places one operation with whoever carries it out, now, and returns its answer to come.
fn submit(op: StorageOp, key: String, data: Vec<u8>) -> Pending {
    if op != StorageOp::Keys && !is_valid_key(&key) {
        return ready(Err(StorageError::InvalidKey));
    }
    if data.len() > MAX_VALUE_BYTES {
        return ready(Err(StorageError::TooLarge {
            len: data.len(),
            limit: MAX_VALUE_BYTES,
        }));
    }
    let mut config = config();
    let Some(identifier) = config.identifier.clone() else {
        return ready(Err(StorageError::Unavailable(NO_IDENTIFIER.to_owned())));
    };
    let backend = *config.backend.get_or_insert_with(Backend::for_this_build);
    let shared = Shared::default();
    let pending = Pending {
        shared: Arc::clone(&shared),
    };
    match backend {
        Backend::Renderer => {
            drop(config);
            send_to_renderer(op, identifier, key, data, shared);
        }
        Backend::Files | Backend::Android => {
            let platform = if backend == Backend::Android {
                Platform::Android
            } else {
                Platform::current().native()
            };
            let mut job = Job {
                op,
                key,
                data,
                directory: PathBuf::new(),
                run: config.run,
                shared,
            };
            let env = |name: &str| std::env::var(name).ok();
            let android_files = config.android_files.clone();
            let resolved = platform_directory(platform, &identifier, android_files.as_deref(), env);
            match resolved {
                Ok(directory) => {
                    drop(config);
                    job.directory = PathBuf::from(directory);
                    storage_thread().push(job);
                }
                // Android's directory arrives with the Renderer's first event. What is
                // asked before then waits for it, in order.
                Err(_) if backend == Backend::Android && android_files.is_none() => {
                    config.held.push(job);
                }
                Err(reason) => {
                    drop(config);
                    complete(&job.shared, Err(StorageError::Unavailable(reason)));
                }
            }
        }
    }
    pending
}

impl Platform {
    /// The platform whose directory rule a native build follows. A test that runs the
    /// file backend on the web's build target has none, and gets Linux's.
    fn native(self) -> Self {
        if self == Platform::Web {
            Platform::Linux
        } else {
            self
        }
    }
}

/// Takes the files directory the Android Renderer reported, and lets every operation that
/// was waiting for it go, in the order they were asked.
pub(crate) fn set_android_files(path: &str) {
    let mut config = config();
    config.android_files = Some(path.to_owned());
    let held = std::mem::take(&mut config.held);
    let identifier = config.identifier.clone().unwrap_or_default();
    let directory = platform_directory(Platform::Android, &identifier, Some(path), |name| {
        std::env::var(name).ok()
    });
    drop(config);
    for mut job in held {
        match &directory {
            Ok(directory) => {
                job.directory = PathBuf::from(directory);
                storage_thread().push(job);
            }
            Err(reason) => complete(&job.shared, Err(StorageError::Unavailable(reason.clone()))),
        }
    }
}

// ----- the storage thread -------------------------------------------------------------------

struct Job {
    op: StorageOp,
    key: String,
    data: Vec<u8>,
    directory: PathBuf,
    run: u64,
    shared: Shared,
}

/// The one thread that reads and writes the files, and the operations in front of it.
///
/// One thread and one list keep the operations on a key in the order they were asked.
/// The list is the Host's own, between the UI thread and this thread; nothing about it
/// crosses to the Renderer.
struct StorageThread {
    queue: Mutex<VecDeque<Job>>,
    ready: Condvar,
}

impl StorageThread {
    fn push(&self, job: Job) {
        self.queue
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .push_back(job);
        self.ready.notify_one();
    }

    fn run(&self) {
        let mut cleaned: HashSet<PathBuf> = HashSet::new();
        let mut run = 0;
        loop {
            let job = {
                let mut queue = self
                    .queue
                    .lock()
                    .unwrap_or_else(|poison| poison.into_inner());
                loop {
                    if let Some(job) = queue.pop_front() {
                        break job;
                    }
                    queue = self
                        .ready
                        .wait(queue)
                        .unwrap_or_else(|poison| poison.into_inner());
                }
            };
            *LAST_IO_THREAD
                .lock()
                .unwrap_or_else(|poison| poison.into_inner()) = Some(std::thread::current().id());
            if job.run != run {
                run = job.run;
                cleaned.clear();
            }
            // A write that a crash interrupted left its temporary file. The first time a
            // run touches a directory, those go.
            if cleaned.insert(job.directory.clone()) {
                remove_leftovers(&job.directory);
            }
            let outcome = carry_out(&job);
            complete(&job.shared, outcome);
        }
    }
}

static LAST_IO_THREAD: Mutex<Option<std::thread::ThreadId>> = Mutex::new(None);

fn storage_thread() -> &'static StorageThread {
    static THREAD: OnceLock<&'static StorageThread> = OnceLock::new();
    THREAD.get_or_init(|| {
        let thread: &'static StorageThread = Box::leak(Box::new(StorageThread {
            queue: Mutex::new(VecDeque::new()),
            ready: Condvar::new(),
        }));
        std::thread::Builder::new()
            .name("compose-rust-storage".to_owned())
            .spawn(move || thread.run())
            .expect("the storage thread could not be started");
        thread
    })
}

fn remove_leftovers(directory: &Path) {
    let Ok(entries) = std::fs::read_dir(directory) else {
        return;
    };
    for entry in entries.flatten() {
        if entry.file_name().to_str().is_some_and(is_temporary) {
            let _ = std::fs::remove_file(entry.path());
        }
    }
}

fn carry_out(job: &Job) -> Outcome {
    let path = job.directory.join(&job.key);
    match job.op {
        StorageOp::Read => match std::fs::read(&path) {
            Ok(bytes) => Ok(Answer::Value(Some(bytes))),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(Answer::Value(None)),
            Err(error) => Err(io_error(&error)),
        },
        StorageOp::Write => write_atomically(&job.directory, &job.key, &job.data)
            .map(|()| Answer::Done)
            .map_err(|error| io_error(&error)),
        StorageOp::Remove => match std::fs::remove_file(&path) {
            Ok(()) => Ok(Answer::Done),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(Answer::Done),
            Err(error) => Err(io_error(&error)),
        },
        StorageOp::Keys => match std::fs::read_dir(&job.directory) {
            Ok(entries) => {
                let mut keys: Vec<String> = entries
                    .flatten()
                    .filter(|entry| entry.file_type().is_ok_and(|kind| kind.is_file()))
                    .filter_map(|entry| entry.file_name().into_string().ok())
                    .filter(|name| is_valid_key(name))
                    .collect();
                keys.sort();
                Ok(Answer::Keys(keys))
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                Ok(Answer::Keys(Vec::new()))
            }
            Err(error) => Err(io_error(&error)),
        },
    }
}

/// Writes `data` to a temporary file beside the key's, flushes it to the disk, and renames
/// it over the key's file. A rename within one directory replaces the target in one step on
/// every platform this runs on, Windows included, so the key holds the old bytes or the new
/// ones whatever moment the process stops at.
fn write_atomically(directory: &Path, key: &str, data: &[u8]) -> std::io::Result<()> {
    std::fs::create_dir_all(directory)?;
    let temporary = directory.join(temporary_name(key));
    let written = (|| {
        let mut file = std::fs::File::create(&temporary)?;
        file.write_all(data)?;
        file.sync_all()?;
        drop(file);
        std::fs::rename(&temporary, directory.join(key))
    })();
    if written.is_err() {
        let _ = std::fs::remove_file(&temporary);
        return written;
    }
    // The rename itself is in the directory's entry, which a crash right after could lose
    // on some file systems without this. Not every platform can open a directory to sync it,
    // and a failure here leaves the old value at worst, so it is not an error.
    #[cfg(unix)]
    if let Ok(handle) = std::fs::File::open(directory) {
        let _ = handle.sync_all();
    }
    Ok(())
}

fn io_error(error: &std::io::Error) -> StorageError {
    use std::io::ErrorKind;
    if is_full(error) {
        return StorageError::QuotaExceeded;
    }
    match error.kind() {
        ErrorKind::PermissionDenied | ErrorKind::ReadOnlyFilesystem => {
            StorageError::Unavailable(error.to_string())
        }
        _ => StorageError::Io(error.to_string()),
    }
}

/// Whether the disk, or the user's share of it, is full. The platform's own numbers are
/// read as well as the error's kind, because a quota has no kind of its own on every
/// toolchain this builds with.
fn is_full(error: &std::io::Error) -> bool {
    if error.kind() == std::io::ErrorKind::StorageFull {
        return true;
    }
    let Some(code) = error.raw_os_error() else {
        return false;
    };
    if cfg!(windows) {
        // ERROR_HANDLE_DISK_FULL, ERROR_DISK_FULL, ERROR_DISK_QUOTA_EXCEEDED.
        matches!(code, 39 | 112 | 1295)
    } else if cfg!(any(target_os = "macos", target_os = "ios")) {
        // ENOSPC, EDQUOT.
        matches!(code, 28 | 69)
    } else {
        // ENOSPC, EDQUOT.
        matches!(code, 28 | 122)
    }
}

// ----- the Renderer's half, on the web -------------------------------------------------------

/// One operation the Renderer carries out, waiting for the next batch.
pub(crate) struct RendererCall {
    pub request: u32,
    pub op: StorageOp,
    pub store: String,
    pub key: String,
    pub data: Vec<u8>,
}

thread_local! {
    /// Operations for the Renderer, in the order they were asked, waiting for a batch.
    static OUTBOX: std::cell::RefCell<Vec<RendererCall>> = const { std::cell::RefCell::new(Vec::new()) };
    /// Requests waiting for their answer, by number, with the operation each one was.
    static WAITING: std::cell::RefCell<HashMap<u32, (StorageOp, Shared)>> =
        std::cell::RefCell::new(HashMap::new());
    /// The next request number. 0 is skipped so a zeroed record never looks like a request.
    static NEXT_REQUEST: std::cell::Cell<u32> = const { std::cell::Cell::new(1) };
}

fn next_request() -> u32 {
    NEXT_REQUEST.with(|next| {
        loop {
            // The top bit belongs to the operations on files picked on the web, which share
            // `IoAnswer` with storage and are told apart by it, so storage wraps before it.
            let id = next.get() & !crate::picked_file::REQUEST_BIT;
            next.set(id.wrapping_add(1));
            // A number still waiting is not handed out twice, even after the counter wraps.
            if id != 0 && !WAITING.with_borrow(|waiting| waiting.contains_key(&id)) {
                return id;
            }
        }
    })
}

fn send_to_renderer(op: StorageOp, store: String, key: String, data: Vec<u8>, shared: Shared) {
    let request = next_request();
    WAITING.with_borrow_mut(|waiting| waiting.insert(request, (op, shared)));
    OUTBOX.with_borrow_mut(|outbox| {
        outbox.push(RendererCall {
            request,
            op,
            store,
            key,
            data,
        })
    });
    // Inside a Host call the batch that call produces carries it. Anywhere else, a frame
    // is what gets it out.
    if !crate::boundary::in_host_call() {
        crate::boundary::request_frame_from_worker();
    }
}

/// Hands every operation waiting for the Renderer to `emit`, oldest first, and empties the
/// list.
pub(crate) fn drain_calls(mut emit: impl FnMut(&RendererCall)) {
    let taken = OUTBOX.with_borrow_mut(std::mem::take);
    for call in &taken {
        emit(call);
    }
}

/// Hands the Renderer's answer to the operation that asked. Returns whether anyone was still
/// waiting for it: an answer to a request nobody knows, or to one whose future was dropped
/// with the component that awaited it, changes nothing.
pub(crate) fn deliver_answer(request: u32, status: IoStatus, data: &[u8]) -> bool {
    let Some((op, shared)) = WAITING.with_borrow_mut(|waiting| waiting.remove(&request)) else {
        return false;
    };
    let outcome = answer_from(op, status, data);
    let awaited = Arc::strong_count(&shared) > 1;
    complete(&shared, outcome);
    awaited
}

fn answer_from(op: StorageOp, status: IoStatus, data: &[u8]) -> Outcome {
    let said = |fallback: &str| {
        std::str::from_utf8(data)
            .ok()
            .filter(|text| !text.is_empty())
            .unwrap_or(fallback)
            .to_owned()
    };
    match status {
        IoStatus::Ok => match op {
            StorageOp::Read => Ok(Answer::Value(Some(data.to_vec()))),
            StorageOp::Write | StorageOp::Remove => Ok(Answer::Done),
            StorageOp::Keys => {
                let text = std::str::from_utf8(data).map_err(|_| {
                    StorageError::Io("the Renderer sent a key that is not UTF-8".to_owned())
                })?;
                let mut keys: Vec<String> = text
                    .split('\0')
                    .filter(|key| !key.is_empty())
                    .map(str::to_owned)
                    .collect();
                keys.sort();
                Ok(Answer::Keys(keys))
            }
        },
        IoStatus::NotFound => match op {
            StorageOp::Read => Ok(Answer::Value(None)),
            StorageOp::Remove => Ok(Answer::Done),
            StorageOp::Keys => Ok(Answer::Keys(Vec::new())),
            StorageOp::Write => Err(StorageError::Io(said(
                "the store to write to was not found",
            ))),
        },
        IoStatus::QuotaExceeded => Err(StorageError::QuotaExceeded),
        IoStatus::Unavailable => Err(StorageError::Unavailable(said(
            "the Renderer has no storage for this application",
        ))),
        IoStatus::PermissionDenied => Err(StorageError::Unavailable(said(
            "the platform refused access to this application's storage",
        ))),
        IoStatus::ReadOnly => Err(StorageError::Io(said("the storage is read only"))),
        IoStatus::Failed => Err(StorageError::Io(said(
            "the Renderer could not carry it out",
        ))),
        // A value too large to carry back carries its length instead, as four bytes, when
        // the Renderer knows it. A write the Renderer refused for its size is held to the
        // value limit, a read to what one answer can carry.
        IoStatus::TooLarge => {
            let limit = if op == StorageOp::Read {
                WEB_ANSWER_LIMIT
            } else {
                MAX_VALUE_BYTES
            };
            let len = match data {
                [a, b, c, d] => u32::from_le_bytes([*a, *b, *c, *d]) as usize,
                _ => limit + 1,
            };
            Err(StorageError::TooLarge {
                len: len.max(limit + 1),
                limit,
            })
        }
    }
}
