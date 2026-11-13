//! The document and the session, kept apart from the UI so both can be tested without a
//! renderer.

/// What a document with no name is called, so the title line is never empty.
pub const UNTITLED: &str = "Untitled";

/// The name a document with no file of its own is offered under when it is first saved.
pub fn suggested_name(name: &str) -> String {
    if name == UNTITLED {
        format!("{UNTITLED}.txt")
    } else {
        name.to_owned()
    }
}

/// What a document is called on screen: its file's name without the extension.
///
/// A note is a thing with a name, and "note.txt" reads as a path rather than as one. The
/// file keeps its full name, and a save offers it unchanged. A name that is all extension,
/// like ".profile", keeps it, because without it there would be no name left.
pub fn display_name(name: &str) -> &str {
    match name.rsplit_once('.') {
        Some((stem, _)) if !stem.is_empty() => stem,
        _ => name,
    }
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

/// The storage key the open documents are kept under between runs.
pub const SESSION_KEY: &str = "session";

/// The first line of a saved session, so a value in some other shape is never read as one.
const SESSION_HEADER: &str = "notepad-session 1";

/// One open document, as the session keeps it.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct SavedDocument {
    pub name: String,
    pub text: String,
    /// Whether the text has changes its file does not have yet.
    pub unsaved: bool,
    /// Where the document's file is, on a platform with paths. A browser gives a page no
    /// path, so there it is always `None`.
    pub path: Option<String>,
}

/// The open documents, in the order they were opened, and which one is on screen.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct Session {
    pub documents: Vec<SavedDocument>,
    pub current: usize,
}

/// One line for the header, one naming the current document, then one per document: the
/// unsaved flag, the name, the path and the text, separated by tabs. A field's own
/// backslashes, tabs and line breaks are escaped, so no text can invent a field or a line.
pub fn encode_session(session: &Session) -> String {
    let mut out = String::new();
    out.push_str(SESSION_HEADER);
    out.push('\n');
    out.push_str(&format!("current\t{}\n", session.current));
    for document in &session.documents {
        out.push_str("document\t");
        out.push(if document.unsaved { '1' } else { '0' });
        out.push('\t');
        escape_into(&mut out, &document.name);
        out.push('\t');
        escape_into(&mut out, document.path.as_deref().unwrap_or(""));
        out.push('\t');
        escape_into(&mut out, &document.text);
        out.push('\n');
    }
    out
}

/// The session a value holds, or `None` when it is not one or holds no document. A line
/// that is not a document is skipped rather than losing the others.
pub fn decode_session(text: &str) -> Option<Session> {
    let mut lines = text.lines();
    if lines.next()? != SESSION_HEADER {
        return None;
    }
    let mut session = Session::default();
    for line in lines {
        let mut fields = line.splitn(5, '\t');
        match fields.next() {
            Some("current") => {
                if let Some(current) = fields.next().and_then(|field| field.parse().ok()) {
                    session.current = current;
                }
            }
            Some("document") => {
                let (Some(unsaved), Some(name), Some(path), Some(text)) =
                    (fields.next(), fields.next(), fields.next(), fields.next())
                else {
                    continue;
                };
                let path = unescape(path);
                session.documents.push(SavedDocument {
                    name: unescape(name),
                    text: unescape(text),
                    unsaved: unsaved == "1",
                    path: (!path.is_empty()).then_some(path),
                });
            }
            _ => {}
        }
    }
    if session.documents.is_empty() {
        return None;
    }
    session.current = session.current.min(session.documents.len() - 1);
    Some(session)
}

fn escape_into(out: &mut String, field: &str) {
    for character in field.chars() {
        match character {
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
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
            Some('r') => out.push('\r'),
            Some('\\') => out.push('\\'),
            Some(other) => out.push(other),
            None => out.push('\\'),
        }
    }
    out
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

    /// Korean, an emoji, tabs, both kinds of line break and a backslash are all text a
    /// document can hold, and none of them may come back as anything else.
    #[test]
    fn fr79_a_session_survives_a_round_trip() {
        let session = Session {
            documents: vec![
                SavedDocument {
                    name: "메모.txt".to_owned(),
                    text: "안녕하세요\n\t들여쓴 줄\r\n끝 \\ 👋".to_owned(),
                    unsaved: true,
                    path: Some("/home/사용자/메모.txt".to_owned()),
                },
                SavedDocument {
                    name: UNTITLED.to_owned(),
                    text: String::new(),
                    unsaved: false,
                    path: None,
                },
            ],
            current: 1,
        };
        assert_eq!(decode_session(&encode_session(&session)), Some(session));
    }

    #[test]
    fn fr79_a_value_that_is_not_a_session_restores_nothing() {
        assert_eq!(decode_session(""), None);
        assert_eq!(
            decode_session("something else\ndocument\t0\ta\t\tb\n"),
            None
        );
        assert_eq!(decode_session("notepad-session 1\ncurrent\t0\n"), None);
    }

    #[test]
    fn fr79_a_current_document_past_the_end_is_the_last_one() {
        let restored = decode_session("notepad-session 1\ncurrent\t9\ndocument\t0\ta\t\tb\n")
            .expect("one document is a session");
        assert_eq!(restored.current, 0);
        assert_eq!(restored.documents[0].text, "b");
    }

    #[test]
    fn fr22_a_document_is_shown_without_its_file_extension() {
        assert_eq!(display_name("note.txt"), "note");
        assert_eq!(display_name("plan.v2.md"), "plan.v2");
        assert_eq!(display_name("메모.txt"), "메모");
        assert_eq!(display_name(UNTITLED), UNTITLED);
        assert_eq!(display_name(".profile"), ".profile");
    }

    #[test]
    fn a_document_with_no_name_is_offered_as_a_text_file() {
        assert_eq!(suggested_name(UNTITLED), "Untitled.txt");
        assert_eq!(suggested_name("note.md"), "note.md");
    }
}
