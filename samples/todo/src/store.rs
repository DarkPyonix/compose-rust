//! The task list and where it survives a restart: the application's storage, under the
//! key [`KEY`].
//!
//! Loading and saving are storage operations, which are futures on every platform.
//! compose-rust carries them out off the UI thread: its own storage thread on a desktop or a
//! phone, and the browser's IndexedDB on the web, through the Renderer. The UI thread starts
//! one and goes on drawing, so the list is read without the window waiting on a disk, and
//! the same code keeps the list across a reload of the page.
//!
//! One write is on its way at a time. An edit made while one is on its way only replaces
//! the list waiting behind it, so a burst of edits writes the newest list rather than every
//! one of them: a save a newer list has overtaken writes nothing.
//!
//! Where the values live is compose-rust's: a directory named after the identifier the
//! application launched with, or the one `COMPOSE_RUST_STORAGE_DIR` names, which is how a
//! development run keeps its list out of the real one.

use compose_rust::IconRole;
use compose_rust::runtime::CoroutineScope;
use compose_rust::storage::{self, StorageError};
use compose_rust::ui::{Message, MessageDuration};
use std::cell::{Cell, RefCell};
use std::sync::atomic::{AtomicBool, Ordering};

/// The storage key the list is kept under.
pub const KEY: &str = "tasks.tsv";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Task {
    pub id: u64,
    pub title: String,
    pub done: bool,
}

/// `all`, `active` and `done`, in the order the filter strip shows them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Filter {
    All,
    Active,
    Done,
}

impl Filter {
    pub const STRIP: [Filter; 3] = [Filter::All, Filter::Active, Filter::Done];

    pub fn label(self) -> &'static str {
        match self {
            Filter::All => "All",
            Filter::Active => "Active",
            Filter::Done => "Done",
        }
    }

    /// Where this filter sits in the destination set, which is also where `STRIP` has it.
    pub fn index(self) -> usize {
        match self {
            Filter::All => 0,
            Filter::Active => 1,
            Filter::Done => 2,
        }
    }

    /// What the destination for this filter means, so the Renderer can draw its own icon.
    pub fn icon(self) -> IconRole {
        match self {
            Filter::All => IconRole::List,
            Filter::Active => IconRole::Inbox,
            Filter::Done => IconRole::Check,
        }
    }

    pub fn accepts(self, task: &Task) -> bool {
        match self {
            Filter::All => true,
            Filter::Active => !task.done,
            Filter::Done => task.done,
        }
    }
}

/// One task per line: id, done flag, title. Tabs separate the fields, and a title's own
/// tabs and newlines are escaped so a title can never invent a new record.
fn encode(tasks: &[Task]) -> String {
    let mut out = String::with_capacity(tasks.len() * 32);
    for task in tasks {
        out.push_str(&task.id.to_string());
        out.push('\t');
        out.push(if task.done { '1' } else { '0' });
        out.push('\t');
        for character in task.title.chars() {
            match character {
                '\\' => out.push_str("\\\\"),
                '\t' => out.push_str("\\t"),
                '\n' => out.push_str("\\n"),
                other => out.push(other),
            }
        }
        out.push('\n');
    }
    out
}

fn unescape(field: &str) -> String {
    let mut out = String::with_capacity(field.len());
    let mut characters = field.chars();
    while let Some(character) = characters.next() {
        if character != '\\' {
            out.push(character);
            continue;
        }
        match characters.next() {
            Some('t') => out.push('\t'),
            Some('n') => out.push('\n'),
            Some('\\') => out.push('\\'),
            Some(other) => out.push(other),
            None => out.push('\\'),
        }
    }
    out
}

fn decode(text: &str) -> Vec<Task> {
    text.lines()
        .filter_map(|line| {
            let mut fields = line.splitn(3, '\t');
            let id = fields.next()?.parse().ok()?;
            let done = fields.next()? == "1";
            let title = unescape(fields.next()?);
            Some(Task { id, title, done })
        })
        .collect()
}

/// Whether the list is read from and written to storage. Off until [`start`], so a test or
/// a benchmark that drives the screen without launching the application starts from an
/// empty list and never touches storage.
static STARTED: AtomicBool = AtomicBool::new(false);

thread_local! {
    /// Where a save's future runs: the list's own coroutine scope, so its work is
    /// cancelled with the list. Every save starts on the UI thread, which is this one.
    static SCOPE: RefCell<Option<CoroutineScope>> = const { RefCell::new(None) };
    /// Whether the saved list was read. Until it was, a save would replace a list nobody
    /// has seen, so nothing is written.
    static LOADED: Cell<bool> = const { Cell::new(false) };
    /// The newest list not yet handed to storage, waiting behind the write on its way.
    static WAITING: RefCell<Option<Vec<Task>>> = const { RefCell::new(None) };
    /// Whether a write is on its way.
    static WRITING: Cell<bool> = const { Cell::new(false) };
    /// Whether the last save failed, so a platform that cannot write at all says so once
    /// rather than on every edit.
    static FAILING: Cell<bool> = const { Cell::new(false) };
}

/// Turn storage on. Called once before the first frame, on every platform.
pub fn start() {
    STARTED.store(true, Ordering::Release);
}

/// Whether the list comes from storage, in which case the screen waits for it.
pub fn started() -> bool {
    STARTED.load(Ordering::Acquire)
}

/// Give the store the coroutine scope its saves run in. Called from the list's
/// composition, which is where a scope comes from.
pub fn attach(scope: CoroutineScope) {
    SCOPE.with(|slot| *slot.borrow_mut() = Some(scope));
}

/// Reads the saved list. A first run has none, which is an empty list.
///
/// A list that cannot be read is said out loud, and this run then writes nothing, so the
/// list that could not be read is still there for a run that can read it. A line that is
/// not a task is skipped rather than failing the whole list.
pub async fn load() -> Vec<Task> {
    LOADED.with(|loaded| loaded.set(false));
    match storage::read_string(KEY).await {
        Ok(saved) => {
            LOADED.with(|loaded| loaded.set(true));
            saved.map(|text| decode(&text)).unwrap_or_default()
        }
        Err(error) => {
            Message::new(format!(
                "The saved list could not be read, so changes are not saved this time: {error}"
            ))
            .with_duration(MessageDuration::Long)
            .show();
            Vec::new()
        }
    }
}

/// Puts the list behind the write on its way, or starts one. Returns without waiting.
pub fn save(tasks: &[Task]) {
    if !started() || !LOADED.with(Cell::get) {
        return;
    }
    let Some(scope) = SCOPE.with(|slot| slot.borrow().clone()) else {
        return;
    };
    WAITING.with(|waiting| *waiting.borrow_mut() = Some(tasks.to_vec()));
    if WRITING.with(Cell::get) {
        // The write on its way picks the newest list up when it ends.
        return;
    }
    let flight = Flight::start();
    scope.launch(async move {
        let _flight = flight;
        while let Some(snapshot) = WAITING.with(|waiting| waiting.borrow_mut().take()) {
            let outcome = storage::write(KEY, encode(&snapshot)).await;
            report(outcome);
        }
    });
}

/// A write on its way. Dropped when the writes end, or with the future when the list's
/// scope is cancelled first, so a cancelled write never leaves the next save waiting for
/// it.
struct Flight;

impl Flight {
    fn start() -> Self {
        WRITING.with(|writing| writing.set(true));
        Flight
    }
}

impl Drop for Flight {
    fn drop(&mut self) {
        WRITING.with(|writing| writing.set(false));
    }
}

/// Says a failed save out loud, once until a save succeeds again. A write error is an
/// ordinary outcome: a full disk, a browser that blocks storage, or a run without an
/// application identifier.
fn report(outcome: Result<(), StorageError>) {
    let already = FAILING.with(|failing| failing.replace(outcome.is_err()));
    if let Err(error) = outcome {
        if !already {
            Message::new(format!(
                "The list could not be saved, so it lasts until the app closes: {error}"
            ))
            .with_duration(MessageDuration::Long)
            .show();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_title_with_tabs_and_newlines_survives_a_round_trip() {
        let tasks = vec![Task {
            id: 7,
            title: "write\tthe\nreport\\now".to_owned(),
            done: true,
        }];
        assert_eq!(decode(&encode(&tasks)), tasks);
    }

    #[test]
    fn a_corrupt_line_is_skipped_rather_than_failing_the_load() {
        assert_eq!(decode("not a record\n9\t0\tkeep me\n").len(), 1);
    }
}
