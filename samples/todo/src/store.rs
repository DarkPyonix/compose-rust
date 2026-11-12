//! The task list and the file it survives a restart in.
//!
//! Saving is file I/O, so it goes through `with_worker`, which is how domain work leaves
//! the UI thread. The UI thread hands a snapshot to a worker and returns immediately; a
//! write that a newer snapshot has already overtaken is skipped, so a burst of edits
//! writes the newest list rather than every one of them. The sample starts no thread of
//! its own: a browser page cannot start one, and the one it used to start aborted the
//! page before its first frame.
//!
//! A platform with no filesystem (a browser page) answers every write with an error. The
//! list then lasts until the page is reloaded, and the first failure says so.

use compose_rust::IconRole;
use compose_rust::runtime::{CoroutineScope, with_worker};
use compose_rust::ui::{Message, MessageDuration};
use std::cell::{Cell, RefCell};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, PoisonError};

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

pub fn store_path() -> PathBuf {
    if let Ok(path) = std::env::var("SAMPLE_TODO_FILE") {
        return PathBuf::from(path);
    }
    let base = std::env::var("HOME").unwrap_or_else(|_| ".".to_owned());
    PathBuf::from(base).join(".sample-todo.tsv")
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

/// Read the saved list. A missing or unreadable file is an empty list rather than an
/// error: a first run has no file, and a corrupt one should not stop the app starting.
pub fn load() -> Vec<Task> {
    std::fs::read_to_string(store_path())
        .map(|text| decode(&text))
        .unwrap_or_default()
}

/// Whether edits are written to disk. Off until `start_saver`, so a test that drives the
/// list never writes over the file another test is reading.
static SAVING: AtomicBool = AtomicBool::new(false);

/// The newest snapshot handed out, and the newest one written. A worker holding an older
/// snapshot than `LATEST` has been overtaken and writes nothing; `WRITTEN` is behind a
/// lock so two workers never write the file at once.
static LATEST: AtomicU64 = AtomicU64::new(0);
static WRITTEN: Mutex<u64> = Mutex::new(0);

thread_local! {
    /// Where a save's future runs: the list's own coroutine scope, so its work is
    /// cancelled with the list. Every save starts on the UI thread, which is this one.
    static SCOPE: RefCell<Option<CoroutineScope>> = const { RefCell::new(None) };
    /// Whether the last save failed, so a platform that cannot write at all says so once
    /// rather than on every edit.
    static FAILING: Cell<bool> = const { Cell::new(false) };
}

/// Turn saving on. Called once before the first frame, on every platform.
pub fn start_saver() {
    SAVING.store(true, Ordering::Release);
}

/// Give the store the coroutine scope its saves run in. Called from the list's
/// composition, which is where a scope comes from.
pub fn attach(scope: CoroutineScope) {
    SCOPE.with(|slot| *slot.borrow_mut() = Some(scope));
}

/// Hand the current list to a worker. Returns without touching the disk.
pub fn save(tasks: &[Task]) {
    if !SAVING.load(Ordering::Acquire) {
        return;
    }
    let Some(scope) = SCOPE.with(|slot| slot.borrow().clone()) else {
        return;
    };
    let generation = LATEST.fetch_add(1, Ordering::AcqRel) + 1;
    let snapshot = tasks.to_vec();
    scope.launch(async move {
        let outcome = with_worker(move || write(generation, &snapshot)).await;
        report(outcome);
    });
}

/// Writes `snapshot` unless a newer one has been handed out since. Runs on a worker.
fn write(generation: u64, snapshot: &[Task]) -> std::io::Result<()> {
    let mut written = WRITTEN.lock().unwrap_or_else(PoisonError::into_inner);
    if generation <= *written || generation < LATEST.load(Ordering::Acquire) {
        return Ok(());
    }
    let path = store_path();
    let temporary = path.with_extension("tsv.tmp");
    std::fs::write(&temporary, encode(snapshot))?;
    std::fs::rename(&temporary, &path)?;
    *written = generation;
    Ok(())
}

/// Says a failed save out loud, once until a save succeeds again. A write error is an
/// ordinary outcome: a full disk, a read-only home directory, or a browser page, where
/// there is no file to write.
fn report(outcome: std::io::Result<()>) {
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

    /// A burst of edits hands out a snapshot each, and each goes to a worker. A worker
    /// whose snapshot a newer one has overtaken writes nothing, so the file ends up
    /// holding the newest list and is not written once per edit. It returns before it
    /// looks for the file, which is why this touches no disk.
    #[test]
    fn pr3_an_overtaken_save_writes_nothing() {
        let overtaken = LATEST.fetch_add(2, Ordering::AcqRel) + 1;
        let written_before = *WRITTEN.lock().unwrap_or_else(PoisonError::into_inner);
        assert!(write(overtaken, &[]).is_ok());
        assert_eq!(
            *WRITTEN.lock().unwrap_or_else(PoisonError::into_inner),
            written_before
        );
    }

    #[test]
    fn a_corrupt_line_is_skipped_rather_than_failing_the_load() {
        assert_eq!(decode("not a record\n9\t0\tkeep me\n").len(), 1);
    }
}
