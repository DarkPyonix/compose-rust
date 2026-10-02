//! The document and the file work, kept apart from the UI so both can be tested without a
//! renderer.

use std::path::{Path, PathBuf};

/// What a finished piece of file work has to say.
#[derive(Clone, Debug, PartialEq)]
pub enum Outcome {
    Opened { path: PathBuf, text: String },
    Saved { path: PathBuf, bytes: usize },
    Failed(String),
}

/// Reads a file. Runs on a worker thread.
pub fn open(path: PathBuf) -> Outcome {
    match std::fs::read(&path) {
        Ok(bytes) => match String::from_utf8(bytes) {
            Ok(text) => Outcome::Opened { path, text },
            Err(_) => Outcome::Failed(format!(
                "{} is not UTF-8 text, so it cannot be edited here",
                display_path(&path)
            )),
        },
        Err(error) => Outcome::Failed(format!("Could not open {}: {error}", display_path(&path))),
    }
}

/// Writes a file. Runs on a worker thread.
pub fn save(path: PathBuf, text: String) -> Outcome {
    match std::fs::write(&path, text.as_bytes()) {
        Ok(()) => Outcome::Saved {
            bytes: text.len(),
            path,
        },
        Err(error) => Outcome::Failed(format!("Could not save {}: {error}", display_path(&path))),
    }
}

pub fn display_path(path: &Path) -> String {
    path.display().to_string()
}

/// What a document with no name is called, so the title line is never empty.
pub const UNTITLED: &str = "Untitled";

/// The document's name, which is what stands at the top of the page.
///
/// Both memo references put the document's name in the document rather than in a path
/// strip above it: the iOS note's title is the first thing on the page, and the Loop
/// document's is the heading under its own toolbar. The full path is still what the file
/// work uses, and it is still editable, just not the first thing the page says.
pub fn file_name(path: &str) -> String {
    let trimmed = path.trim();
    if trimmed.is_empty() {
        return UNTITLED.to_owned();
    }
    Path::new(trimmed)
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| UNTITLED.to_owned())
}

/// The three numbers shown under the editor.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct Counts {
    pub words: usize,
    pub characters: usize,
    pub lines: usize,
}

/// Characters are counted as characters, not as bytes: one Hangul syllable is one
/// character even though it takes three bytes of UTF-8.
pub fn counts(text: &str) -> Counts {
    Counts {
        words: text.split_whitespace().count(),
        characters: text.chars().count(),
        lines: text.lines().count().max(1),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_characters_not_bytes() {
        let counted = counts("안녕 세상");
        assert_eq!(counted.characters, 5);
        assert_eq!(counted.words, 2);
        assert_eq!(counted.lines, 1);
    }

    #[test]
    fn counts_every_line_including_the_last() {
        assert_eq!(counts("one\ntwo\nthree").lines, 3);
        assert_eq!(counts("").lines, 1);
    }

    #[test]
    fn korean_text_survives_a_save_and_an_open() {
        let path = scratch("roundtrip");
        let text = "안녕하세요\n또 만나요 👋".to_owned();
        assert!(matches!(
            save(path.clone(), text.clone()),
            Outcome::Saved { .. }
        ));
        match open(path.clone()) {
            Outcome::Opened { text: reopened, .. } => assert_eq!(reopened, text),
            other => panic!("expected the file to reopen, got {other:?}"),
        }
        let _ = std::fs::remove_file(path);
    }

    /// A path no other run of these tests can touch.
    ///
    /// They used to share fixed names in the system temp directory, so two checkouts
    /// testing at once wrote and deleted each other's files and one of them failed with
    /// "expected a refusal, got Opened". The process id is what makes them separate; the
    /// name keeps them readable when one is left behind.
    fn scratch(name: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!(
            "dioxus-compose-notepad-{name}-{}.tmp",
            std::process::id()
        ))
    }

    #[test]
    fn a_missing_file_reports_why_rather_than_panicking() {
        let path = scratch("absent");
        let _ = std::fs::remove_file(&path);
        assert!(matches!(open(path), Outcome::Failed(_)));
    }

    #[test]
    fn a_file_that_is_not_text_is_refused() {
        let path = scratch("binary");
        std::fs::write(&path, [0xff_u8, 0xfe, 0x00]).unwrap();
        match open(path.clone()) {
            Outcome::Failed(message) => assert!(message.contains("UTF-8")),
            other => panic!("expected a refusal, got {other:?}"),
        }
        let _ = std::fs::remove_file(path);
    }
}
