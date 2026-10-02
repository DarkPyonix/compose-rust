//! The task list and the file it survives a restart in.
//!
//! Saving is file I/O, so it never runs on the UI thread. The UI thread hands a snapshot
//! to a worker over a channel and returns immediately; the worker coalesces bursts and
//! writes one file per burst. Loading happens once, in `main`, before the renderer loop
//! starts, so there is no UI thread to block yet.

use dioxus_compose::IconRole;
use std::path::PathBuf;
use std::sync::OnceLock;
use std::sync::mpsc::{Sender, channel};

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

static SAVER: OnceLock<Sender<Vec<Task>>> = OnceLock::new();

/// Start the writer thread. Called once from `main`.
pub fn start_saver() {
    let (sender, receiver) = channel::<Vec<Task>>();
    std::thread::Builder::new()
        .name("sample-todo-saver".to_owned())
        .spawn(move || {
            while let Ok(mut snapshot) = receiver.recv() {
                // A burst of edits queues a snapshot each. Only the newest is worth
                // writing, so drain what has already arrived and write that one.
                while let Ok(newer) = receiver.try_recv() {
                    snapshot = newer;
                }
                let path = store_path();
                let temporary = path.with_extension("tsv.tmp");
                if std::fs::write(&temporary, encode(&snapshot)).is_ok() {
                    let _ = std::fs::rename(&temporary, &path);
                }
            }
        })
        .expect("the saver thread could not be started");
    let _ = SAVER.set(sender);
}

/// Hand the current list to the writer thread. Returns without touching the disk.
pub fn save(tasks: &[Task]) {
    if let Some(sender) = SAVER.get() {
        let _ = sender.send(tasks.to_vec());
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
