//! The tasks, the lists they are kept in, and where both survive a restart: the
//! application's storage, under the key [`KEY`].
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

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Task {
    pub id: u64,
    pub title: String,
    pub done: bool,
    /// The list it is kept in, or [`NO_LIST`].
    pub list: u64,
    /// What the task says beyond its title, empty when it says nothing more.
    pub note: String,
}

/// The list of a task kept in none.
pub const NO_LIST: u64 = 0;

/// A list the user made with "New list". Each one is a destination of its own.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TaskList {
    pub id: u64,
    pub name: String,
}

/// Everything the storage keeps.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Saved {
    pub tasks: Vec<Task>,
    pub lists: Vec<TaskList>,
}

/// What a screen shows: one of the filters, or one of the user's lists.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum View {
    Filter(Filter),
    List(u64),
}

impl View {
    pub fn accepts(self, task: &Task) -> bool {
        match self {
            View::Filter(filter) => filter.accepts(task),
            View::List(list) => task.list == list,
        }
    }

    /// The list a task added while this is on screen goes into.
    pub fn list(self) -> u64 {
        match self {
            View::Filter(_) => NO_LIST,
            View::List(list) => list,
        }
    }
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

/// One record per line, fields separated by tabs. A field's own tabs, newlines and
/// backslashes are escaped, so no text can invent a record.
///
/// - a list: `L`, id, name;
/// - a task kept in no list and with no note: id, done flag, title, which is the only record
///   there was before lists, so a list saved then still reads;
/// - any other task: `T`, id, done flag, list id, note, title.
///
/// Lists come first, so a task never names a list that has not been read yet.
fn encode(tasks: &[Task], lists: &[TaskList]) -> String {
    let mut out = String::with_capacity(tasks.len() * 32 + lists.len() * 16);
    for list in lists {
        out.push_str("L\t");
        out.push_str(&list.id.to_string());
        out.push('\t');
        escape_into(&list.name, &mut out);
        out.push('\n');
    }
    for task in tasks {
        let plain = task.list == NO_LIST && task.note.is_empty();
        if !plain {
            out.push_str("T\t");
        }
        out.push_str(&task.id.to_string());
        out.push('\t');
        out.push(if task.done { '1' } else { '0' });
        out.push('\t');
        if !plain {
            out.push_str(&task.list.to_string());
            out.push('\t');
            escape_into(&task.note, &mut out);
            out.push('\t');
        }
        escape_into(&task.title, &mut out);
        out.push('\n');
    }
    out
}

fn escape_into(field: &str, out: &mut String) {
    for character in field.chars() {
        match character {
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            other => out.push(other),
        }
    }
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

fn decode(text: &str) -> Saved {
    let mut saved = Saved::default();
    for line in text.lines() {
        if let Some(record) = line.strip_prefix("L\t") {
            if let Some(list) = decode_list(record) {
                saved.lists.push(list);
            }
        } else if let Some(record) = line.strip_prefix("T\t") {
            if let Some(task) = decode_task(record) {
                saved.tasks.push(task);
            }
        } else if let Some(task) = decode_plain_task(line) {
            saved.tasks.push(task);
        }
    }
    saved
}

fn decode_list(record: &str) -> Option<TaskList> {
    let mut fields = record.splitn(2, '\t');
    let id = fields.next()?.parse().ok()?;
    let name = unescape(fields.next()?);
    Some(TaskList { id, name })
}

fn decode_task(record: &str) -> Option<Task> {
    let mut fields = record.splitn(5, '\t');
    let id = fields.next()?.parse().ok()?;
    let done = fields.next()? == "1";
    let list = fields.next()?.parse().ok()?;
    let note = unescape(fields.next()?);
    let title = unescape(fields.next()?);
    Some(Task {
        id,
        title,
        done,
        list,
        note,
    })
}

fn decode_plain_task(line: &str) -> Option<Task> {
    let mut fields = line.splitn(3, '\t');
    let id = fields.next()?.parse().ok()?;
    let done = fields.next()? == "1";
    let title = unescape(fields.next()?);
    Some(Task {
        id,
        title,
        done,
        ..Task::default()
    })
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
    /// The newest tasks and lists not yet handed to storage, waiting behind the write on
    /// its way.
    static WAITING: RefCell<Option<(Vec<Task>, Vec<TaskList>)>> = const { RefCell::new(None) };
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

/// Reads the saved tasks and lists. A first run has none, which is an empty list.
///
/// A list that cannot be read is said out loud, and this run then writes nothing, so the
/// list that could not be read is still there for a run that can read it. A line that is
/// not a task is skipped rather than failing the whole list.
pub async fn load() -> Saved {
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
            Saved::default()
        }
    }
}

/// Puts the tasks and lists behind the write on its way, or starts one. Returns without
/// waiting.
pub fn save(tasks: &[Task], lists: &[TaskList]) {
    if !started() || !LOADED.with(Cell::get) {
        return;
    }
    let Some(scope) = SCOPE.with(|slot| slot.borrow().clone()) else {
        return;
    };
    WAITING.with(|waiting| *waiting.borrow_mut() = Some((tasks.to_vec(), lists.to_vec())));
    if WRITING.with(Cell::get) {
        // The write on its way picks the newest list up when it ends.
        return;
    }
    let flight = Flight::start();
    scope.launch(async move {
        let _flight = flight;
        while let Some((tasks, lists)) = WAITING.with(|waiting| waiting.borrow_mut().take()) {
            let outcome = storage::write(KEY, encode(&tasks, &lists)).await;
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
            ..Task::default()
        }];
        assert_eq!(decode(&encode(&tasks, &[])).tasks, tasks);
    }

    #[test]
    fn a_corrupt_line_is_skipped_rather_than_failing_the_load() {
        assert_eq!(decode("not a record\n9\t0\tkeep me\n").tasks.len(), 1);
    }

    /// A list, a task kept in it and a note all come back as they went, with the same
    /// escaping as a title, so a name or a note with a tab in it cannot split its record.
    #[test]
    fn fr22_lists_and_notes_survive_a_round_trip() {
        let saved = Saved {
            lists: vec![TaskList {
                id: 3,
                name: "Errands\tand more".to_owned(),
            }],
            tasks: vec![
                Task {
                    id: 1,
                    title: "Buy milk".to_owned(),
                    done: false,
                    list: 3,
                    note: "two\nbottles".to_owned(),
                },
                Task {
                    id: 2,
                    title: "Call Jason".to_owned(),
                    done: true,
                    ..Task::default()
                },
            ],
        };
        let text = encode(&saved.tasks, &saved.lists);
        assert_eq!(decode(&text), saved);
        // A task in no list and with no note keeps the record a list saved before lists
        // were written in, so nothing saved then reads differently now.
        assert!(text.ends_with("2\t1\tCall Jason\n"), "{text}");
    }

    /// A list saved before there were lists is all plain records, and every task in it is
    /// kept in no list.
    #[test]
    fn fr22_a_list_saved_before_lists_still_reads() {
        let saved = decode("1\t0\tBuy milk\n2\t1\tWalk the dog\n");
        assert!(saved.lists.is_empty());
        assert_eq!(saved.tasks.len(), 2);
        assert!(saved.tasks.iter().all(|task| task.list == NO_LIST));
        assert!(saved.tasks[1].done);
    }
}
