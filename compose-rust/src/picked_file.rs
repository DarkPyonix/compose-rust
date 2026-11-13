//! Reading and writing what the user chose in a file dialog, the same way on every platform.
//!
//! [`FileDialogResult::files`] turns a choice into [`PickedFile`]s. What one holds depends on
//! where the application runs, and the application does not have to know:
//!
//! - On a desktop or a phone it is a path. [`PickedFile::read`], [`PickedFile::write`],
//!   [`PickedFile::entries`] and [`PickedFile::child`] run on a Host worker with `std::fs`, so
//!   the UI thread never waits for the disk. A write goes to a temporary file beside the
//!   target and is renamed over it, so a reader never sees half of it.
//! - In a browser there is no path. The Renderer keeps the browser's handle for the file and
//!   the Host keeps its number. The dialog's answer carries one reference per file in
//!   `Chosen.paths`, written `webfile:<handle>/<name>`, and `files()` turns each into a
//!   `PickedFile`. Every operation then crosses to the Renderer as one `FileCall` record and
//!   comes back as one event: an `IoAnswer` with the bytes or the reason, or a `FileHandles`
//!   with the files a folder holds. When the last `PickedFile` for a handle is dropped, the
//!   Host tells the Renderer to let go of it with `ReleaseFileHandle`.
//!
//! ```ignore
//! FileDialog::open_file().filter("Text", &["txt", "md"]).show(move |result| {
//!     if let Some(file) = result.files().into_iter().next() {
//!         scope.launch(async move {
//!             if let Ok(bytes) = file.read().await {
//!                 document.set(String::from_utf8_lossy(&bytes).into_owned());
//!             }
//!         });
//!     }
//! });
//! ```
//!
//! How a file reached the application is [`PickedFile::delivery`]. Only a
//! [`Delivery::Handle`] can be written again in place: a browser without the File System
//! Access API hands over a copy to read ([`Delivery::Upload`]) or turns a save into a download
//! ([`Delivery::Download`]), and an application treats its next save as "save as" when
//! [`PickedFile::can_write_again`] is false.
//!
//! The operations are futures to await on the UI thread, from an effect, a coroutine scope or
//! a handler. They are not `Send` on the web, where the answer comes back on the UI thread
//! inside a Host call.

use crate::file_dialog::FileDialogResult;
use crate::schema::{FileDelivery, FileDialogStatus, FileOp, IoStatus};
use std::cell::RefCell;
use std::collections::HashMap;
use std::fmt;
use std::future::Future;
use std::pin::Pin;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock, Weak};
use std::task::{Context, Poll, Waker};

/// How a file reached the application.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub enum Delivery {
    /// The file itself, in the file system. Writing it again changes the same file. Every
    /// file on a desktop or a phone is delivered this way.
    Handle,
    /// A copy the browser handed over from a file input. It can be read and not written.
    Upload,
    /// A save that became a download. Every write is a new download, and the browser may
    /// rename it, so the same file cannot be changed again.
    Download,
}

impl From<FileDelivery> for Delivery {
    fn from(value: FileDelivery) -> Self {
        match value {
            FileDelivery::Handle => Delivery::Handle,
            FileDelivery::Upload => Delivery::Upload,
            FileDelivery::Download => Delivery::Download,
        }
    }
}

/// Why an operation on a [`PickedFile`] did not happen.
#[non_exhaustive]
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum FileError {
    /// The file is not there any more, or, in a browser, its handle was let go of or belongs
    /// to a page that has since been reloaded.
    NotFound,
    /// The platform or the user refused access.
    PermissionDenied,
    /// The file can only be read: an upload, or one the platform will not let this run write.
    ReadOnly,
    /// A listing or a child was asked of a file.
    NotAFolder,
    /// A read or a write was asked of a folder.
    IsAFolder,
    /// The file is longer than one read may carry. Nothing was read. `len` is its length when
    /// it is known, and `limit + 1` when all that is known is that it is longer.
    TooLarge { len: u64, limit: u64 },
    /// This run has no way to do it. The reason is in the text.
    Unavailable(String),
    /// Any other failure, as the platform described it.
    Io(String),
}

impl fmt::Display for FileError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            FileError::NotFound => f.write_str("the file is not there any more"),
            FileError::PermissionDenied => f.write_str("access to the file was refused"),
            FileError::ReadOnly => f.write_str("the file can only be read"),
            FileError::NotAFolder => f.write_str("the file is not a folder"),
            FileError::IsAFolder => f.write_str("the file is a folder"),
            FileError::TooLarge { len, limit } => write!(
                f,
                "the file is {len} bytes, more than the {limit} bytes one read may carry"
            ),
            FileError::Unavailable(reason) => write!(f, "unavailable here: {reason}"),
            FileError::Io(reason) => f.write_str(reason),
        }
    }
}

impl std::error::Error for FileError {}

/// One file or folder the user chose, to read and write.
///
/// Cheap to clone: a clone names the same file. See the module documentation for what it
/// holds on each platform.
#[derive(Clone)]
pub struct PickedFile {
    inner: Inner,
}

#[derive(Clone)]
enum Inner {
    Path(PathFile),
    Web(Arc<WebFile>),
}

#[derive(Clone)]
struct PathFile {
    path: String,
    name: String,
    /// Asked of the file system the first time it is wanted, because a dialog's answer says
    /// only where a path is and not what it is.
    folder: OnceLock<bool>,
}

/// The Host's half of a handle the Renderer keeps. Shared by every `PickedFile` for it; the
/// last one dropped lets the Renderer go of the handle.
struct WebFile {
    handle: u32,
    name: String,
    folder: bool,
    delivery: Delivery,
    /// Made from a reference whose handle was already let go of. Every operation on it is
    /// `NotFound` without asking, and dropping it releases nothing.
    released: bool,
}

impl Drop for WebFile {
    fn drop(&mut self) {
        if self.released {
            return;
        }
        let mut handles = registry();
        // A `files()` racing this drop may have made a new holder for the handle already;
        // then the handle is still in use and stays.
        let still_held = handles
            .get(&self.handle)
            .and_then(|entry| entry.live.as_ref())
            .is_some_and(|live| live.strong_count() > 0);
        if still_held {
            return;
        }
        handles.remove(&self.handle);
        drop(handles);
        queue_release(self.handle);
    }
}

impl fmt::Debug for PickedFile {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match &self.inner {
            Inner::Path(file) => f
                .debug_struct("PickedFile")
                .field("path", &file.path)
                .finish(),
            Inner::Web(file) => f
                .debug_struct("PickedFile")
                .field("handle", &file.handle)
                .field("name", &file.name)
                .field("folder", &file.folder)
                .field("delivery", &file.delivery)
                .finish(),
        }
    }
}

impl PartialEq for PickedFile {
    fn eq(&self, other: &Self) -> bool {
        match (&self.inner, &other.inner) {
            (Inner::Path(left), Inner::Path(right)) => left.path == right.path,
            (Inner::Web(left), Inner::Web(right)) => left.handle == right.handle,
            _ => false,
        }
    }
}

impl Eq for PickedFile {}

/// What a `webfile:` reference starts with.
const WEB_REFERENCE_PREFIX: &str = "webfile:";

/// The bytes one answer from a browser Renderer can carry: the largest event buffer the Host
/// lends it, less the `IoAnswer` record in front of the bytes.
const WEB_ANSWER_BYTES: u64 = crate::schema::WEB_EVENT_BUFFER_MAX_BYTES as u64 - 32;

impl PickedFile {
    /// The longest file [`PickedFile::read`] reads, in bytes. A longer one is
    /// [`FileError::TooLarge`] before anything is read.
    pub const MAX_READ_BYTES: u64 = 256 * 1024 * 1024;

    /// A file by its path, without a dialog. For a path the application already had, such as
    /// one it saved in its own storage last time.
    ///
    /// Only where there are paths: a browser gives a page none, so there is nothing to make
    /// one from there.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn from_path(path: impl Into<String>) -> PickedFile {
        PickedFile::at_path(path.into(), None)
    }

    fn at_path(path: String, folder: Option<bool>) -> PickedFile {
        let name = std::path::Path::new(&path)
            .file_name()
            .map(|name| name.to_string_lossy().into_owned())
            .unwrap_or_else(|| path.clone());
        let known = OnceLock::new();
        if let Some(folder) = folder {
            let _ = known.set(folder);
        }
        PickedFile {
            inner: Inner::Path(PathFile {
                path,
                name,
                folder: known,
            }),
        }
    }

    /// The file's name, without any folder in front of it.
    pub fn name(&self) -> &str {
        match &self.inner {
            Inner::Path(file) => &file.name,
            Inner::Web(file) => &file.name,
        }
    }

    /// The file's absolute path, where there is one. Never in a browser.
    pub fn path(&self) -> Option<&str> {
        match &self.inner {
            Inner::Path(file) => Some(&file.path),
            Inner::Web(_) => None,
        }
    }

    /// Whether this is a folder.
    ///
    /// For a path, the file system is asked the first time, which is one small call on the
    /// calling thread. A browser said so when it handed the file over.
    pub fn is_folder(&self) -> bool {
        match &self.inner {
            Inner::Path(file) => *file
                .folder
                .get_or_init(|| std::path::Path::new(&file.path).is_dir()),
            Inner::Web(file) => file.folder,
        }
    }

    /// How the file reached the application. Always [`Delivery::Handle`] for a path.
    pub fn delivery(&self) -> Delivery {
        match &self.inner {
            Inner::Path(_) => Delivery::Handle,
            Inner::Web(file) => file.delivery,
        }
    }

    /// Whether a write changes this same file again: a [`Delivery::Handle`] that is not a
    /// folder. When it is false the next save is a "save as".
    pub fn can_write_again(&self) -> bool {
        self.delivery() == Delivery::Handle && !self.is_folder()
    }

    /// Reads the whole file.
    pub async fn read(&self) -> Result<Vec<u8>, FileError> {
        match &self.inner {
            Inner::Path(file) => {
                let path = file.path.clone();
                crate::runtime::with_worker(move || native::read(&path)).await
            }
            Inner::Web(file) => {
                if file.released {
                    return Err(FileError::NotFound);
                }
                if file.folder {
                    return Err(FileError::IsAFolder);
                }
                match web_call(FileOp::Read, false, file.handle, String::new(), Vec::new()).await {
                    Answer::Io {
                        status: IoStatus::Ok,
                        data,
                    } => Ok(data),
                    Answer::Io { status, .. } => Err(error_of(status)),
                    Answer::Files { .. } => Err(unexpected_answer("a read")),
                }
            }
        }
    }

    /// Replaces the file's contents with `bytes`.
    ///
    /// For a path the new contents go to a temporary file in the same folder, which is then
    /// renamed over the file, so the file is always either what it was or what was written.
    /// A browser does the same inside the File System Access API's writable stream. On a
    /// [`Delivery::Download`] every write is a new download of `bytes` under the file's name.
    pub async fn write(&self, bytes: impl Into<Vec<u8>>) -> Result<(), FileError> {
        let bytes = bytes.into();
        match &self.inner {
            Inner::Path(file) => {
                let path = file.path.clone();
                crate::runtime::with_worker(move || native::write(&path, &bytes)).await
            }
            Inner::Web(file) => {
                if file.released {
                    return Err(FileError::NotFound);
                }
                if file.folder {
                    return Err(FileError::IsAFolder);
                }
                if file.delivery == Delivery::Upload {
                    return Err(FileError::ReadOnly);
                }
                match web_call(FileOp::Write, false, file.handle, String::new(), bytes).await {
                    Answer::Io {
                        status: IoStatus::Ok,
                        ..
                    } => Ok(()),
                    Answer::Io { status, .. } => Err(error_of(status)),
                    Answer::Files { .. } => Err(unexpected_answer("a write")),
                }
            }
        }
    }

    /// What a folder holds directly, files and folders both, in name order for a path and
    /// in the browser's order in a browser.
    pub async fn entries(&self) -> Result<Vec<PickedFile>, FileError> {
        match &self.inner {
            Inner::Path(file) => {
                let path = file.path.clone();
                crate::runtime::with_worker(move || native::entries(&path)).await
            }
            Inner::Web(file) => {
                if file.released {
                    return Err(FileError::NotFound);
                }
                if !file.folder {
                    return Err(FileError::NotAFolder);
                }
                match web_call(
                    FileOp::Entries,
                    false,
                    file.handle,
                    String::new(),
                    Vec::new(),
                )
                .await
                {
                    Answer::Files(files) => Ok(files),
                    Answer::Io { status, .. } => Err(error_of(status)),
                }
            }
        }
    }

    /// The file or folder called `name` directly inside this folder. With `create`, a file of
    /// that name is made, empty, when there is none.
    ///
    /// `name` is one name: one with a path separator in it, or `.` or `..`, is refused before
    /// anything is asked.
    pub async fn child(&self, name: &str, create: bool) -> Result<PickedFile, FileError> {
        if !is_plain_name(name) {
            return Err(FileError::Io(format!(
                "{name:?} is not the name of one file: it is empty, holds a path separator or a NUL, or is . or .."
            )));
        }
        match &self.inner {
            Inner::Path(file) => {
                let path = file.path.clone();
                let name = name.to_owned();
                crate::runtime::with_worker(move || native::child(&path, &name, create)).await
            }
            Inner::Web(file) => {
                if file.released {
                    return Err(FileError::NotFound);
                }
                if !file.folder {
                    return Err(FileError::NotAFolder);
                }
                match web_call(
                    FileOp::Child,
                    create,
                    file.handle,
                    name.to_owned(),
                    Vec::new(),
                )
                .await
                {
                    Answer::Files(mut files) if files.len() == 1 => Ok(files.remove(0)),
                    Answer::Files(files) => Err(FileError::Io(format!(
                        "the browser answered a request for one child with {} files",
                        files.len()
                    ))),
                    Answer::Io { status, .. } => Err(error_of(status)),
                }
            }
        }
    }
}

fn is_plain_name(name: &str) -> bool {
    !name.is_empty() && name != "." && name != ".." && !name.contains(['/', '\\', '\0'])
}

fn unexpected_answer(what: &str) -> FileError {
    FileError::Io(format!(
        "the browser answered {what} with a list of files instead of bytes"
    ))
}

/// What an `IoAnswer` status means for a file. `Ok` never gets here.
fn error_of(status: IoStatus) -> FileError {
    match status {
        IoStatus::NotFound => FileError::NotFound,
        IoStatus::PermissionDenied => FileError::PermissionDenied,
        IoStatus::ReadOnly => FileError::ReadOnly,
        IoStatus::TooLarge => FileError::TooLarge {
            len: WEB_ANSWER_BYTES + 1,
            limit: WEB_ANSWER_BYTES,
        },
        IoStatus::QuotaExceeded => {
            FileError::Io("the browser has no room left to write the file".to_owned())
        }
        IoStatus::Unavailable => FileError::Unavailable(
            "the Renderer has no way to reach this file in this run".to_owned(),
        ),
        IoStatus::Failed | IoStatus::Ok => {
            FileError::Io("the browser could not finish the operation".to_owned())
        }
    }
}

impl FileDialogResult {
    /// What was chosen, as files to read and write: in the same order as `paths` and as
    /// many, or none when the dialog did not end in a choice.
    pub fn files(&self) -> Vec<PickedFile> {
        match self {
            FileDialogResult::Chosen { paths, .. } => {
                paths.iter().map(|path| picked_from_entry(path)).collect()
            }
            FileDialogResult::Cancelled | FileDialogResult::Unavailable => Vec::new(),
        }
    }
}

/// One entry of `Chosen.paths` as a file: a `webfile:` reference becomes the Renderer's
/// handle, anything else is a path. No path on any platform starts with `webfile:`, because
/// a dialog's paths are absolute.
fn picked_from_entry(entry: &str) -> PickedFile {
    match parse_reference(entry) {
        Some((handle, name)) => web_file(handle, name),
        None => PickedFile::at_path(entry.to_owned(), None),
    }
}

/// The handle and name of a `webfile:<handle>/<name>` reference.
fn parse_reference(entry: &str) -> Option<(u32, &str)> {
    let rest = entry.strip_prefix(WEB_REFERENCE_PREFIX)?;
    let (number, name) = rest.split_once('/')?;
    if number.is_empty() || !number.bytes().all(|digit| digit.is_ascii_digit()) {
        return None;
    }
    Some((number.parse().ok()?, name))
}

fn reference(handle: u32, name: &str) -> String {
    format!("{WEB_REFERENCE_PREFIX}{handle}/{name}")
}

// ----- the handles the Renderer holds ------------------------------------------------------

/// What the Host knows about one handle the Renderer holds, from the event that named it.
struct HandleEntry {
    name: String,
    folder: bool,
    delivery: Delivery,
    /// The `PickedFile`s for it share this. None until `files()` made the first one.
    live: Option<Weak<WebFile>>,
}

static REGISTRY: OnceLock<Mutex<HashMap<u32, HandleEntry>>> = OnceLock::new();

fn registry() -> MutexGuard<'static, HashMap<u32, HandleEntry>> {
    REGISTRY
        .get_or_init(|| Mutex::new(HashMap::new()))
        .lock()
        .unwrap_or_else(|poison| poison.into_inner())
}

/// Remembers the handles one `FileHandles` event named, and returns their references in
/// order.
fn register(
    first_handle: u32,
    delivery: Delivery,
    names: &str,
    kinds: &str,
) -> Vec<(u32, String, bool)> {
    let names: Vec<&str> = if names.is_empty() {
        Vec::new()
    } else {
        names.split('\0').collect()
    };
    let mut handles = registry();
    names
        .iter()
        .zip(kinds.bytes())
        .enumerate()
        .map(|(index, (name, kind))| {
            let handle = first_handle.wrapping_add(index as u32);
            let folder = kind == b'd';
            handles.insert(
                handle,
                HandleEntry {
                    name: (*name).to_owned(),
                    folder,
                    delivery,
                    live: None,
                },
            );
            (handle, (*name).to_owned(), folder)
        })
        .collect()
}

/// The `PickedFile` for a handle, shared with every other one for the same handle.
fn web_file(handle: u32, name: &str) -> PickedFile {
    let mut handles = registry();
    let Some(entry) = handles.get_mut(&handle) else {
        // Let go of already, or never named: its operations are `NotFound`, which is what
        // the Renderer would answer too.
        return PickedFile {
            inner: Inner::Web(Arc::new(WebFile {
                handle,
                name: name.to_owned(),
                folder: false,
                delivery: Delivery::Handle,
                released: true,
            })),
        };
    };
    if let Some(live) = entry.live.as_ref().and_then(Weak::upgrade) {
        return PickedFile {
            inner: Inner::Web(live),
        };
    }
    let file = Arc::new(WebFile {
        handle,
        name: entry.name.clone(),
        folder: entry.folder,
        delivery: entry.delivery,
        released: false,
    });
    entry.live = Some(Arc::downgrade(&file));
    PickedFile {
        inner: Inner::Web(file),
    }
}

// ----- what leaves for the Renderer --------------------------------------------------------

/// One `FileCall` waiting for the next batch.
pub(crate) struct Call {
    pub(crate) request: u32,
    pub(crate) op: FileOp,
    pub(crate) create: bool,
    pub(crate) handle: u32,
    pub(crate) name: String,
    pub(crate) data: Vec<u8>,
}

static CALLS: Mutex<Vec<Call>> = Mutex::new(Vec::new());
static RELEASES: Mutex<Vec<u32>> = Mutex::new(Vec::new());
/// Whether either queue has anything in it, read without a lock, so a batch with nothing to
/// write pays one atomic load.
static PENDING: AtomicBool = AtomicBool::new(false);

/// The next request number. File operations number theirs with the top bit set, so a
/// request of theirs is never one a dialog or the application's storage numbered, and an
/// answer finds its waiter by number alone.
static NEXT_REQUEST: AtomicU32 = AtomicU32::new(1);
pub(crate) const REQUEST_BIT: u32 = 0x8000_0000;

fn next_request() -> u32 {
    loop {
        let low = NEXT_REQUEST.fetch_add(1, Ordering::Relaxed) & !REQUEST_BIT;
        if low != 0 {
            return REQUEST_BIT | low;
        }
    }
}

fn lock<T>(mutex: &'static Mutex<T>) -> MutexGuard<'static, T> {
    // A thread that panicked while holding the lock does not make the queue wrong.
    mutex.lock().unwrap_or_else(|poison| poison.into_inner())
}

fn wake_batch() {
    PENDING.store(true, Ordering::Release);
    // Inside a Host call the batch that call produces carries it.
    if !crate::boundary::in_host_call() {
        crate::boundary::request_frame_from_worker();
    }
}

fn queue_release(handle: u32) {
    lock(&RELEASES).push(handle);
    wake_batch();
}

/// Hands every queued release and call to the batch, releases first, and empties the queues.
pub(crate) fn drain(mut release: impl FnMut(u32), mut call: impl FnMut(&Call)) {
    if !PENDING.swap(false, Ordering::AcqRel) {
        return;
    }
    let releases = std::mem::take(&mut *lock(&RELEASES));
    for handle in releases {
        release(handle);
    }
    let calls = std::mem::take(&mut *lock(&CALLS));
    for waiting in &calls {
        call(waiting);
    }
    if !lock(&RELEASES).is_empty() || !lock(&CALLS).is_empty() {
        // Something was queued while these were being written, and is still waiting.
        PENDING.store(true, Ordering::Release);
    }
}

// ----- answers ------------------------------------------------------------------------------

enum Answer {
    Io { status: IoStatus, data: Vec<u8> },
    Files(Vec<PickedFile>),
}

#[derive(Default)]
struct Slot {
    answer: Option<Answer>,
    waker: Option<Waker>,
}

thread_local! {
    /// The operations waiting for their answer on this thread, by request number.
    static WAITING: RefCell<HashMap<u32, Rc<RefCell<Slot>>>> = RefCell::new(HashMap::new());
}

fn answer(request: u32, answer: Answer) -> bool {
    let Some(slot) = WAITING.with_borrow_mut(|waiting| waiting.remove(&request)) else {
        return false;
    };
    let waker = {
        let mut slot = slot.borrow_mut();
        slot.answer = Some(answer);
        slot.waker.take()
    };
    if let Some(waker) = waker {
        waker.wake();
    }
    true
}

/// Hands an `IoAnswer` to the file operation that asked for it. Returns whether one did:
/// a request that is not a file operation's, or is no longer waiting, is someone else's.
pub(crate) fn deliver_io(request: u32, status: IoStatus, data: &[u8]) -> bool {
    if request & REQUEST_BIT == 0 {
        return false;
    }
    self::answer(
        request,
        Answer::Io {
            status,
            data: data.to_vec(),
        },
    )
}

/// Hands a `FileHandles` event to whoever asked: a folder's listing or child, or else the
/// file dialog with that number, as a choice of `webfile:` references. Returns whether
/// anybody was waiting.
pub(crate) fn deliver_handles(
    request: u32,
    status: FileDialogStatus,
    filter: u32,
    delivery: FileDelivery,
    first_handle: u32,
    names: &str,
    kinds: &str,
) -> bool {
    let delivery = Delivery::from(delivery);
    let named = register(first_handle, delivery, names, kinds);
    if request & REQUEST_BIT != 0 {
        let files = named
            .iter()
            .map(|(handle, name, _)| web_file(*handle, name))
            .collect();
        return self::answer(request, Answer::Files(files));
    }
    if status != FileDialogStatus::Chosen {
        // Only a choice comes this way; the other two ends come as `FileDialogResult`.
        return crate::file_dialog::deliver(request, FileDialogStatus::Unavailable, u32::MAX, "");
    }
    let paths: Vec<String> = named
        .iter()
        .map(|(handle, name, _)| reference(*handle, name))
        .collect();
    crate::file_dialog::deliver(request, status, filter, &paths.join("\0"))
}

/// One operation sent to the Renderer, resolved by its answer. Asked for on the first poll;
/// a future dropped before then sends nothing, and one dropped after leaves its answer to
/// be ignored.
fn web_call(op: FileOp, create: bool, handle: u32, name: String, data: Vec<u8>) -> WebCall {
    WebCall {
        call: Some(Call {
            request: 0,
            op,
            create,
            handle,
            name,
            data,
        }),
        slot: None,
    }
}

struct WebCall {
    call: Option<Call>,
    slot: Option<Rc<RefCell<Slot>>>,
}

impl Unpin for WebCall {}

impl Future for WebCall {
    type Output = Answer;

    fn poll(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<Answer> {
        if let Some(mut call) = self.call.take() {
            let request = next_request();
            call.request = request;
            let slot = Rc::new(RefCell::new(Slot {
                answer: None,
                waker: Some(context.waker().clone()),
            }));
            WAITING.with_borrow_mut(|waiting| waiting.insert(request, Rc::clone(&slot)));
            self.slot = Some(slot);
            lock(&CALLS).push(call);
            wake_batch();
            return Poll::Pending;
        }
        let Some(slot) = &self.slot else {
            return Poll::Pending;
        };
        let mut slot = slot.borrow_mut();
        match slot.answer.take() {
            Some(answer) => Poll::Ready(answer),
            None => {
                slot.waker = Some(context.waker().clone());
                Poll::Pending
            }
        }
    }
}

// ----- paths ------------------------------------------------------------------------------

mod native {
    use super::{FileError, PickedFile};
    use std::io::Write;
    use std::path::Path;
    use std::sync::atomic::{AtomicU64, Ordering};

    fn error(failure: std::io::Error) -> FileError {
        match failure.kind() {
            std::io::ErrorKind::NotFound => FileError::NotFound,
            std::io::ErrorKind::PermissionDenied => FileError::PermissionDenied,
            std::io::ErrorKind::Unsupported => FileError::Unavailable(failure.to_string()),
            _ => FileError::Io(failure.to_string()),
        }
    }

    pub(super) fn read(path: &str) -> Result<Vec<u8>, FileError> {
        let metadata = std::fs::metadata(path).map_err(error)?;
        if metadata.is_dir() {
            return Err(FileError::IsAFolder);
        }
        if metadata.len() > PickedFile::MAX_READ_BYTES {
            return Err(FileError::TooLarge {
                len: metadata.len(),
                limit: PickedFile::MAX_READ_BYTES,
            });
        }
        let bytes = std::fs::read(path).map_err(error)?;
        // The file may have grown between the two calls; the limit holds for what was read.
        if bytes.len() as u64 > PickedFile::MAX_READ_BYTES {
            return Err(FileError::TooLarge {
                len: bytes.len() as u64,
                limit: PickedFile::MAX_READ_BYTES,
            });
        }
        Ok(bytes)
    }

    static TEMPORARY: AtomicU64 = AtomicU64::new(0);

    pub(super) fn write(path: &str, bytes: &[u8]) -> Result<(), FileError> {
        let target = Path::new(path);
        let existing = match std::fs::metadata(target) {
            Ok(metadata) if metadata.is_dir() => return Err(FileError::IsAFolder),
            Ok(metadata) => Some(metadata),
            Err(failure) if failure.kind() == std::io::ErrorKind::NotFound => None,
            Err(failure) => return Err(error(failure)),
        };
        if existing
            .as_ref()
            .is_some_and(|metadata| metadata.permissions().readonly())
        {
            return Err(FileError::ReadOnly);
        }
        let folder = target
            .parent()
            .filter(|parent| !parent.as_os_str().is_empty())
            .unwrap_or_else(|| Path::new("."));
        let name = target
            .file_name()
            .map(|name| name.to_string_lossy().into_owned())
            .unwrap_or_default();
        // Beside the target, so the rename stays on one file system and replaces it whole.
        let temporary = folder.join(format!(
            ".{name}.{}.{}.compose-rust.tmp",
            std::process::id(),
            TEMPORARY.fetch_add(1, Ordering::Relaxed)
        ));
        let written = (|| {
            let mut file = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temporary)?;
            file.write_all(bytes)?;
            file.sync_all()?;
            drop(file);
            // The file keeps the permissions it had, not the new file's defaults.
            if let Some(metadata) = &existing {
                std::fs::set_permissions(&temporary, metadata.permissions())?;
            }
            std::fs::rename(&temporary, target)
        })();
        if let Err(failure) = written {
            let _ = std::fs::remove_file(&temporary);
            return Err(error(failure));
        }
        Ok(())
    }

    fn folder_of(path: &str) -> Result<(), FileError> {
        let metadata = std::fs::metadata(path).map_err(error)?;
        if !metadata.is_dir() {
            return Err(FileError::NotAFolder);
        }
        Ok(())
    }

    fn joined(path: &str, name: &str) -> Result<String, FileError> {
        Path::new(path)
            .join(name)
            .into_os_string()
            .into_string()
            .map_err(|_| FileError::Io(format!("{name:?} inside {path:?} is not UTF-8")))
    }

    pub(super) fn entries(path: &str) -> Result<Vec<PickedFile>, FileError> {
        folder_of(path)?;
        let mut found = Vec::new();
        for entry in std::fs::read_dir(path).map_err(error)? {
            let entry = entry.map_err(error)?;
            // A name that is not UTF-8 cannot be handed over as a path; it is left out
            // rather than given a name that is not its own.
            let Ok(name) = entry.file_name().into_string() else {
                continue;
            };
            let folder = entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false);
            found.push((name, folder));
        }
        found.sort();
        found
            .into_iter()
            .map(|(name, folder)| Ok(PickedFile::at_path(joined(path, &name)?, Some(folder))))
            .collect()
    }

    pub(super) fn child(path: &str, name: &str, create: bool) -> Result<PickedFile, FileError> {
        folder_of(path)?;
        let child = joined(path, name)?;
        match std::fs::metadata(&child) {
            Ok(metadata) => Ok(PickedFile::at_path(child, Some(metadata.is_dir()))),
            Err(failure) if failure.kind() == std::io::ErrorKind::NotFound && create => {
                match std::fs::OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(&child)
                {
                    Ok(_) => Ok(PickedFile::at_path(child, Some(false))),
                    // Made by someone else in between: it is there now, which is what was
                    // asked for.
                    Err(again) if again.kind() == std::io::ErrorKind::AlreadyExists => {
                        let folder = Path::new(&child).is_dir();
                        Ok(PickedFile::at_path(child, Some(folder)))
                    }
                    Err(again) => Err(error(again)),
                }
            }
            Err(failure) => Err(error(failure)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fr72_a_reference_names_its_handle_and_a_path_is_not_one() {
        assert_eq!(
            parse_reference("webfile:7/notes.txt"),
            Some((7, "notes.txt"))
        );
        assert_eq!(parse_reference("webfile:12/"), Some((12, "")));
        assert_eq!(parse_reference("webfile:/a"), None);
        assert_eq!(parse_reference("webfile:x/a"), None);
        assert_eq!(parse_reference("/home/webfile:1/a"), None);
        assert_eq!(parse_reference("C:\\webfile:1\\a"), None);
        assert_eq!(reference(3, "한글 파일.txt"), "webfile:3/한글 파일.txt");
    }

    #[test]
    fn fr72_a_child_name_is_one_name() {
        for refused in ["", ".", "..", "a/b", "a\\b", "a\0b"] {
            assert!(!is_plain_name(refused), "{refused:?}");
        }
        for accepted in ["a.txt", "한글", ".hidden", "a b"] {
            assert!(is_plain_name(accepted), "{accepted:?}");
        }
    }

    #[test]
    fn fr72_file_requests_never_share_a_number_with_a_dialog() {
        for _ in 0..4 {
            let request = next_request();
            assert_ne!(request & REQUEST_BIT, 0);
            assert_ne!(request & !REQUEST_BIT, 0);
        }
        assert!(
            !deliver_io(5, IoStatus::Ok, b""),
            "a dialog's number is not ours"
        );
    }
}
