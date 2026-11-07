//! File dialogs: asking the user to pick or name a file from the platform's own panel.
//!
//! The Host does not call the platform. The Renderer owns the windows and the platform's
//! panel, so a request crosses as a command record in an ordinary batch, and the answer
//! comes back as an ordinary event. A dialog is not a node: it has no place in the tree and
//! it is gone once it has answered.
//!
//! Every request gets exactly one answer, whether the user chose, cancelled, or this run has
//! no way to show a panel. The Host numbers each request, remembers the callback under that
//! number, calls it once when the matching event arrives, and drops it. An answer for a
//! number nobody is waiting on is ignored.
//!
//! ```ignore
//! FileDialog::open_files()
//!     .title("Choose files to send")
//!     .filter("Images", &["png", "jpg"])
//!     .show(|result| {
//!         if let FileDialogResult::Chosen { paths, .. } = result {
//!             send(paths);
//!         }
//!     });
//! ```
//!
//! [`FileDialog::show`] belongs on the Renderer's UI thread, from a handler or a component,
//! because its callback runs there. A worker thread asks through [`FileDialogSender`], whose
//! callback has to be `Send` for the same reason: it is stored by the Host and called on the
//! UI thread. Nothing here is a queue on the boundary. The queue is the Host's own and it is
//! written into the next batch inside a call.

use crate::schema::{FileDialogKind, FileDialogStatus};
use std::cell::RefCell;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Mutex, MutexGuard};

/// How a dialog ended.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum FileDialogResult {
    /// The user chose. `paths` holds at least one absolute path, exactly as the platform
    /// gave it. `filter` is the position of the filter that was in use, when the request
    /// carried filters.
    Chosen {
        paths: Vec<String>,
        filter: Option<usize>,
    },
    /// The user closed the dialog, or the window it belonged to went away while it was open.
    Cancelled,
    /// This run cannot show a dialog: the platform has none yet, the window does not exist,
    /// or the answer could not be delivered whole. Not an error, and not a cancel.
    Unavailable,
}

/// One file dialog, built and then shown.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FileDialog {
    kind: FileDialogKind,
    window: u32,
    title: String,
    directory: String,
    name: String,
    filters: Vec<(String, Vec<String>)>,
    default_filter: usize,
}

impl FileDialog {
    fn new(kind: FileDialogKind) -> Self {
        Self {
            kind,
            window: 0,
            title: String::new(),
            directory: String::new(),
            name: String::new(),
            filters: Vec::new(),
            default_filter: 0,
        }
    }

    /// Choose one file.
    pub fn open_file() -> Self {
        Self::new(FileDialogKind::OpenFile)
    }

    /// Choose one or more files.
    pub fn open_files() -> Self {
        Self::new(FileDialogKind::OpenFiles)
    }

    /// Choose one folder.
    pub fn open_folder() -> Self {
        Self::new(FileDialogKind::OpenFolder)
    }

    /// Choose one or more folders.
    pub fn open_folders() -> Self {
        Self::new(FileDialogKind::OpenFolders)
    }

    /// Choose a path to save to. The platform asks before overwriting; the Renderer only
    /// hands back the path and never writes the file.
    pub fn save() -> Self {
        Self::new(FileDialogKind::Save)
    }

    /// The dialog's title. Empty is the platform's default.
    pub fn title(mut self, title: impl Into<String>) -> Self {
        self.title = title.into();
        self
    }

    /// The folder the dialog opens in. It should be absolute. One that is empty, missing
    /// or unreadable falls back to the platform's default, and is not an error.
    pub fn directory(mut self, directory: impl Into<String>) -> Self {
        self.directory = directory.into();
        self
    }

    /// The file name a save dialog starts with. Ignored by the open kinds. A path
    /// separator in it keeps only what follows the last one.
    pub fn name(mut self, name: impl Into<String>) -> Self {
        self.name = name.into();
        self
    }

    /// Adds a filter: a label and the extensions it allows, without dots and compared
    /// without regard to case. Ignored when choosing folders. Without any filter every file
    /// can be chosen.
    pub fn filter(mut self, label: impl Into<String>, extensions: &[&str]) -> Self {
        self.filters.push((
            label.into(),
            extensions
                .iter()
                .map(|extension| (*extension).to_owned())
                .collect(),
        ));
        self
    }

    /// The position of the filter selected first. A position past the last filter selects
    /// the first.
    pub fn default_filter(mut self, position: usize) -> Self {
        self.default_filter = position;
        self
    }

    /// The `Window` node the dialog belongs to and is modal for. 0 is the implicit root
    /// window of an application with one window.
    pub fn window(mut self, node_id: u32) -> Self {
        self.window = node_id;
        self
    }

    /// The window the dialog belongs to, by handle.
    pub fn in_window(self, window: crate::windows::WindowHandle) -> Self {
        self.window(window.id())
    }

    /// Asks for the dialog. `on_result` is called once, on the UI thread, when it ends, and
    /// is then dropped.
    ///
    /// Call this on the UI thread, from a component or an event handler. From a worker use
    /// [`FileDialogSender`].
    pub fn show(self, on_result: impl FnOnce(FileDialogResult) + 'static) {
        let request = next_request();
        let callback: LocalCallback = Box::new(on_result);
        LOCAL.with_borrow_mut(|callbacks| {
            callbacks.insert(request, callback);
        });
        submit(self.into_request(request));
    }

    fn into_request(self, id: u32) -> Request {
        Request {
            id,
            window: self.window,
            kind: self.kind,
            default_filter: u16::try_from(self.default_filter).unwrap_or(u16::MAX),
            title: self.title,
            directory: self.directory,
            name: self.name,
            filters: encode_filters(&self.filters),
        }
    }
}

/// A handle a worker thread asks for dialogs through.
///
/// `Send`, `Copy` and free to make: the queue it feeds belongs to the Host. The callback is
/// stored by the Host and run on the UI thread, so it has to be `Send`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct FileDialogSender {
    _private: (),
}

impl FileDialogSender {
    pub const fn new() -> Self {
        Self { _private: () }
    }

    /// Asks for `dialog` and calls `on_result` once on the UI thread when it ends.
    pub fn show(
        &self,
        dialog: FileDialog,
        on_result: impl FnOnce(FileDialogResult) + Send + 'static,
    ) {
        let request = next_request();
        let callback: RemoteCallback = Box::new(on_result);
        remote().push((request, callback));
        submit(dialog.into_request(request));
    }
}

/// One request waiting for the next batch.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Request {
    pub(crate) id: u32,
    pub(crate) window: u32,
    pub(crate) kind: FileDialogKind,
    pub(crate) default_filter: u16,
    pub(crate) title: String,
    pub(crate) directory: String,
    pub(crate) name: String,
    pub(crate) filters: String,
}

/// The filters as the wire carries them: each label, then its extensions joined by commas,
/// all separated by NUL. A NUL or a comma inside a part would shift every later part, so
/// they are dropped from the parts rather than sent.
fn encode_filters(filters: &[(String, Vec<String>)]) -> String {
    let mut out = String::new();
    for (label, extensions) in filters {
        if !out.is_empty() {
            out.push('\0');
        }
        out.extend(label.chars().filter(|c| *c != '\0'));
        out.push('\0');
        let mut first = true;
        for extension in extensions {
            if !first {
                out.push(',');
            }
            first = false;
            out.extend(extension.chars().filter(|c| *c != '\0' && *c != ','));
        }
    }
    out
}

type LocalCallback = Box<dyn FnOnce(FileDialogResult)>;
type RemoteCallback = Box<dyn FnOnce(FileDialogResult) + Send>;

thread_local! {
    /// Callbacks asked for on the UI thread, which need not be `Send`.
    static LOCAL: RefCell<HashMap<u32, LocalCallback>> = RefCell::new(HashMap::new());
}

/// Callbacks a worker asked for, kept until the UI thread delivers the answer.
static REMOTE: Mutex<Vec<(u32, RemoteCallback)>> = Mutex::new(Vec::new());

/// Requests waiting for the next batch. Shared across threads, because a worker asks.
static QUEUE: Mutex<Vec<Request>> = Mutex::new(Vec::new());

/// Whether the queue has anything in it, read without taking the lock, so a batch that has
/// nothing to write pays one atomic load.
static PENDING: AtomicBool = AtomicBool::new(false);

/// The next request number. 0 is skipped so a zeroed record never looks like a request.
static NEXT_REQUEST: AtomicU32 = AtomicU32::new(1);

fn next_request() -> u32 {
    loop {
        let id = NEXT_REQUEST.fetch_add(1, Ordering::Relaxed);
        if id != 0 {
            return id;
        }
    }
}

fn queue() -> MutexGuard<'static, Vec<Request>> {
    // A worker that panicked while holding the lock does not make the queue wrong.
    QUEUE.lock().unwrap_or_else(|poison| poison.into_inner())
}

fn remote() -> MutexGuard<'static, Vec<(u32, RemoteCallback)>> {
    REMOTE.lock().unwrap_or_else(|poison| poison.into_inner())
}

fn submit(request: Request) {
    queue().push(request);
    PENDING.store(true, Ordering::Release);
    // Inside a Host call on the UI thread the batch that call produces carries it, so a
    // frame request would only buy an empty frame.
    if !crate::boundary::in_host_call() {
        crate::boundary::request_frame_from_worker();
    }
}

/// Hands every queued request to `emit` and empties the queue.
pub(crate) fn drain(mut emit: impl FnMut(&Request)) {
    if !PENDING.swap(false, Ordering::AcqRel) {
        return;
    }
    let mut taken = std::mem::take(&mut *queue());
    for request in &taken {
        emit(request);
    }
    taken.clear();
    let mut guard = queue();
    if guard.is_empty() {
        *guard = taken;
    } else {
        // Something was asked for while these were being written, and is still waiting.
        PENDING.store(true, Ordering::Release);
    }
}

/// Hands an answer to the callback that asked for it. Returns whether there was one.
///
/// A request that is not waiting, because it was already answered or never asked for, finds
/// no callback and is ignored. `paths` is the wire string: paths separated by NUL.
pub(crate) fn deliver(request: u32, status: FileDialogStatus, filter: u32, paths: &str) -> bool {
    let result = match status {
        FileDialogStatus::Chosen => {
            let paths: Vec<String> = paths
                .split('\0')
                .filter(|path| !path.is_empty())
                .map(str::to_owned)
                .collect();
            // A choice with nothing chosen is not one the Renderer sends. Reporting it as a
            // pick would hand the application an empty list to index into.
            if paths.is_empty() {
                FileDialogResult::Unavailable
            } else {
                FileDialogResult::Chosen {
                    paths,
                    filter: (filter != u32::MAX).then_some(filter as usize),
                }
            }
        }
        FileDialogStatus::Cancelled => FileDialogResult::Cancelled,
        FileDialogStatus::Unavailable => FileDialogResult::Unavailable,
    };
    // Taken out before it runs: the callback may ask for another dialog, which edits the
    // table being read here.
    if let Some(callback) = LOCAL.with_borrow_mut(|callbacks| callbacks.remove(&request)) {
        callback(result);
        return true;
    }
    let callback = {
        let mut waiting = remote();
        waiting
            .iter()
            .position(|(id, _)| *id == request)
            .map(|at| waiting.swap_remove(at).1)
    };
    match callback {
        Some(callback) => {
            callback(result);
            true
        }
        None => false,
    }
}

/// Forgets every callback and anything still queued. Used between tests.
#[doc(hidden)]
pub fn reset_file_dialogs() {
    queue().clear();
    PENDING.store(false, Ordering::Release);
    remote().clear();
    LOCAL.with_borrow_mut(HashMap::clear);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    use std::rc::Rc;

    #[test]
    fn fr72_filters_are_one_nul_separated_string() {
        assert_eq!(encode_filters(&[]), "");
        let filters = vec![
            (
                "Images".to_owned(),
                vec!["png".to_owned(), "jpg".to_owned()],
            ),
            ("Docs".to_owned(), vec!["pdf".to_owned(), "txt".to_owned()]),
        ];
        assert_eq!(encode_filters(&filters), "Images\0png,jpg\0Docs\0pdf,txt");
        let hostile = vec![("A\0B".to_owned(), vec!["p,n\0g".to_owned()])];
        assert_eq!(encode_filters(&hostile), "AB\0png");
    }

    #[test]
    fn fr72_paths_are_split_on_nul_and_a_stray_answer_is_ignored() {
        let seen = Rc::new(RefCell::new(Vec::new()));
        let sink = Rc::clone(&seen);
        let request = next_request();
        let callback: LocalCallback =
            Box::new(move |result: FileDialogResult| sink.borrow_mut().push(result));
        LOCAL.with_borrow_mut(|callbacks| {
            callbacks.insert(request, callback);
        });
        assert!(deliver(
            request,
            FileDialogStatus::Chosen,
            1,
            "/home/한글/문서.txt\0/tmp/a b.png"
        ));
        assert_eq!(
            *seen.borrow(),
            vec![FileDialogResult::Chosen {
                paths: vec!["/home/한글/문서.txt".to_owned(), "/tmp/a b.png".to_owned()],
                filter: Some(1),
            }]
        );
        // Answered once; the callback is gone.
        assert!(!deliver(request, FileDialogStatus::Cancelled, u32::MAX, ""));
        assert!(!deliver(
            u32::MAX,
            FileDialogStatus::Cancelled,
            u32::MAX,
            ""
        ));
        assert_eq!(seen.borrow().len(), 1);
    }

    #[test]
    fn fr72_a_pick_with_no_paths_is_unavailable_not_chosen() {
        let seen = Rc::new(Cell::new(None));
        let sink = Rc::clone(&seen);
        let request = next_request();
        let callback: LocalCallback =
            Box::new(move |result: FileDialogResult| sink.set(Some(result)));
        LOCAL.with_borrow_mut(|callbacks| {
            callbacks.insert(request, callback);
        });
        deliver(request, FileDialogStatus::Chosen, u32::MAX, "");
        assert_eq!(seen.take(), Some(FileDialogResult::Unavailable));
    }

    #[test]
    fn fr72_a_worker_callback_is_called_once_with_its_own_answer() {
        let seen = std::sync::Arc::new(Mutex::new(Vec::new()));
        let sink = std::sync::Arc::clone(&seen);
        let request = next_request();
        let callback: RemoteCallback =
            Box::new(move |result: FileDialogResult| sink.lock().unwrap().push(result));
        remote().push((request, callback));
        assert!(deliver(
            request,
            FileDialogStatus::Unavailable,
            u32::MAX,
            ""
        ));
        assert!(!deliver(
            request,
            FileDialogStatus::Unavailable,
            u32::MAX,
            ""
        ));
        assert_eq!(*seen.lock().unwrap(), vec![FileDialogResult::Unavailable]);
    }
}
