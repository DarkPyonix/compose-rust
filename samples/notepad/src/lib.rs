//! A working text editor, clone coded from the iOS and Windows memo applications.
//!
//! Written with compose-rust's own API: composables, state and the widget composables, no
//! Kotlin and no protocol types. Files are chosen in the platform's file dialog and read and
//! written through what it chose, which in a browser is the browser's own file handling. The
//! reading and writing happen off the UI thread and come back as a state write, so the UI
//! thread never waits on a disk.
//!
//! Several documents can be open at once. The one being written lives in the states the
//! editor reads, and the rest wait on a shelf, so choosing a document is a swap rather
//! than a different code path through the whole screen. The list of them stands beside the
//! page on a desktop window and arrives in a sheet on anything narrower.
//!
//! The open documents are kept in the application's storage while they are written, and
//! come back the next time the editor starts, or the next time the page loads in a browser.

use std::rc::Rc;
use std::time::Duration;

use compose_rust::runtime::*;
use compose_rust::storage;
use compose_rust::ui::*;
use compose_rust::{Delivery, FileDialog, FileDialogResult, FileError, PickedFile};

mod document;

use document::{
    Counts, SESSION_KEY, SavedDocument, Session, UNTITLED, counts, decode_session, encode_session,
    suggested_name,
};

/// What the open and save dialogs offer to show.
const TEXT_LABEL: &str = "Text";
const TEXT_EXTENSIONS: &[&str] = &["txt", "md"];

/// How long the document has to rest after a change before the open documents are kept
/// for next time. Writing on every key would write the whole session once per character.
const SESSION_PAUSE: Duration = Duration::from_millis(500);

/// What the status line says when this run has no file dialog to show.
const NO_DIALOG: &str = "There is no file dialog here, so files cannot be opened or saved";

/// The editor field.
///
/// The field is uncontrolled: what the user types stays in the Renderer, and the Host only
/// hears about it. Opening a file is the one time the contents come from outside, so the
/// field is keyed by `stamp`, which changes only then. A new key is a new field, and the
/// field takes the contents it was built with. Ordinary typing leaves `stamp` alone, so the
/// caret and the IME composition are never disturbed and nothing is written back.
#[Composable]
fn editor(stamp: u64, contents: String, type_role: TypeRole, on_edit: Rc<dyn Fn(String)>) {
    key(stamp, || {
        // What the field was built with, kept for as long as this key lives. Later writes
        // to the document are the field's own typing coming back, not something to send.
        let initial = remember(|| contents.clone());
        TextField()
            .text(initial)
            .modifier(Modifier.fill_max_width().fill_max_height())
            .multiline(true)
            .type_role(type_role)
            .placeholder("Type here, or open a file")
            .on_value_change(move |value| on_edit(value));
    });
}

/// The bar across the top of the window, with its contents held to the page's measure.
///
/// A bar spans the window because it belongs to the window. Its contents belong to the
/// document, and a title that starts at the window's edge while the page it names starts
/// two hundred dp further in is a window whose two halves disagree about where the left
/// side is. So the bar fills, and the row inside it is the same width as the page and
/// carries the same inset.
///
/// `measure` is `None` on a window with nothing to spare, where the row fills the bar and
/// the bar's own inset is already the page's.
#[Composable]
fn document_bar(measure: Option<f32>, working: bool, children: impl FnOnce()) {
    Column().modifier(Modifier.fill_max_width()).content(|| {
        TopAppBar().modifier(Modifier.fill_max_width()).content(|| {
            Box()
                .modifier(Modifier.weight(1.0))
                .alignment(Alignment::Center)
                .content(|| {
                    Row()
                        .modifier(
                            Modifier
                                .width_if(measure)
                                .fill_max_width_if(measure.is_none())
                                .padding_role_if(measure.map(|_| SpaceRole::Md)),
                        )
                        .space_role(SpaceRole::Sm)
                        .alignment(Alignment::CenterStart)
                        .content(children);
                });
        });
        // Reading and writing happen off the UI thread, so the window stays live while they
        // run and there has to be something that says they are running. Indeterminate,
        // because a file system does not report how far through a read it is.
        // The bar spans its container without being told to: how wide a rule or a
        // progress track is on the axis it runs along is the design system's.
        if working {
            IndeterminateProgressIndicator();
        }
    });
}

/// A document that is open but not on screen.
///
/// The one being edited lives in the states the editor writes to; this is where the
/// others wait. Swapping the two is what choosing a document does, which is why the
/// editor code below never has to know that there is more than one.
#[derive(Clone, Debug, Default, PartialEq)]
struct Shelved {
    /// Never reused, so it is the list key.
    id: u64,
    name: String,
    /// The file the document was opened from or last saved to, if it has one.
    file: Option<PickedFile>,
    text: String,
    /// What the file held when it was last read or written, or `None` when the text has
    /// changes no file has: a document restored with unsaved changes.
    on_disk: Option<String>,
}

impl Shelved {
    fn untitled(id: u64) -> Self {
        Shelved {
            id,
            name: UNTITLED.to_owned(),
            file: None,
            text: String::new(),
            on_disk: Some(String::new()),
        }
    }
}

/// The file a restored document had, from the path the session kept.
#[cfg(not(target_arch = "wasm32"))]
fn file_at(path: Option<&str>) -> Option<PickedFile> {
    path.map(PickedFile::from_path)
}

/// A browser gives a page no paths, so a document restored there has no file, and its next
/// save asks where to save it.
#[cfg(target_arch = "wasm32")]
fn file_at(_path: Option<&str>) -> Option<PickedFile> {
    None
}

/// The editor's states, in one place so the handlers below can share them.
///
/// The document on screen is the first group; the shelf, the next id and the status line
/// are the rest of the editor. `revision` changes whenever anything the session keeps does,
/// which is what tells the session to write itself again.
#[derive(Clone, Copy)]
struct Docs {
    text: MutableState<String>,
    stamp: MutableState<u64>,
    name: MutableState<String>,
    file: MutableState<Option<PickedFile>>,
    // The document is unsaved exactly when it has drifted from this, which is a comparison
    // rather than a flag: a flag has to be cleared in every place that saves, and the one
    // that forgets is the bug.
    on_disk: MutableState<Option<String>>,
    current: MutableState<u64>,
    shelved: MutableState<Vec<Shelved>>,
    next_document: MutableState<u64>,
    status: MutableState<String>,
    busy: MutableState<bool>,
    revision: MutableState<u64>,
}

impl Docs {
    /// The document on screen, as the shelf would hold it.
    fn on_screen(&self) -> Shelved {
        Shelved {
            id: self.current.get_untracked(),
            name: self.name.get_untracked(),
            file: self.file.get_untracked(),
            text: self.text.get_untracked(),
            on_disk: self.on_disk.get_untracked(),
        }
    }

    /// Puts a document in the editor. Its contents came from outside the field, so the
    /// field is rebuilt.
    fn show(&self, document: Shelved) {
        self.current.set(document.id);
        self.name.set(document.name);
        self.file.set(document.file);
        self.on_disk.set(document.on_disk);
        self.text.set(document.text);
        self.stamp.update(|stamp| *stamp += 1);
    }

    /// Puts the document on screen on the shelf and returns the id the next one takes.
    fn shelve(&self) -> u64 {
        let on_screen = self.on_screen();
        self.shelved.update(|list| list.push(on_screen));
        let id = self.next_document.get_untracked();
        self.next_document.set(id + 1);
        id
    }

    /// Whether the document on screen is one nobody has written in and that has no file,
    /// which an opened file replaces rather than standing beside.
    fn is_blank(&self) -> bool {
        self.file.with(Option::is_none) && self.text.with(String::is_empty)
    }

    /// Something the session keeps has changed.
    fn changed(&self) {
        self.revision.update(|revision| *revision += 1);
    }

    /// A failure is worth saying out loud as well as writing down. The status line is
    /// where you look afterwards; the message is what reaches someone who was looking at
    /// the document when it happened.
    fn fail(&self, message: String) {
        self.busy.set(false);
        Message::new(message.clone())
            .with_duration(MessageDuration::Long)
            .show();
        self.status.set(message);
    }

    /// A read the open dialog started has finished.
    fn opened(&self, picked: PickedFile, outcome: Result<Vec<u8>, FileError>) {
        self.busy.set(false);
        let name = picked.name().to_owned();
        let text = match outcome.map(String::from_utf8) {
            Ok(Ok(text)) => text,
            Ok(Err(_)) => {
                return self.fail(format!(
                    "{name} is not UTF-8 text, so it cannot be edited here"
                ));
            }
            Err(error) => return self.fail(format!("Could not open {name}: {error}")),
        };
        let id = if self.is_blank() {
            self.current.get_untracked()
        } else {
            self.shelve()
        };
        self.status.set(format!(
            "Opened {name} ({} characters)",
            text.chars().count()
        ));
        self.show(Shelved {
            id,
            name,
            file: Some(picked),
            on_disk: Some(text.clone()),
            text,
        });
        self.changed();
    }

    /// A write a save started has finished. `id` is the document it saved, which is not
    /// the one on screen any more if the reader moved on while it was written.
    fn saved(&self, id: u64, target: PickedFile, written: String, outcome: Result<(), FileError>) {
        self.busy.set(false);
        let name = target.name().to_owned();
        if let Err(error) = outcome {
            return self.fail(format!("Could not save {name}: {error}"));
        }
        // A browser without direct file access turns a save into a download. That is not
        // a failure, but it is not the same as the file being changed either, so it says
        // which it was.
        self.status.set(if target.delivery() == Delivery::Download {
            format!("This browser cannot save to a file directly, so {name} was downloaded")
        } else {
            format!("Saved {name} ({} bytes)", written.len())
        });
        if self.current.get_untracked() == id {
            self.name.set(name);
            self.file.set(Some(target));
            self.on_disk.set(Some(written));
        } else {
            self.shelved.update(|list| {
                if let Some(document) = list.iter_mut().find(|document| document.id == id) {
                    document.name = name;
                    document.file = Some(target);
                    document.on_disk = Some(written);
                }
            });
        }
        self.changed();
    }

    /// Every open document, in the order they were opened, as the session keeps them.
    fn session(&self) -> Session {
        let on_screen = self.on_screen();
        let current = on_screen.id;
        let mut all = self.shelved.get_untracked();
        all.push(on_screen);
        all.sort_by_key(|document| document.id);
        let index = all
            .iter()
            .position(|document| document.id == current)
            .unwrap_or(0);
        let documents = all
            .into_iter()
            .map(|document| {
                let unsaved = document.on_disk.as_deref() != Some(document.text.as_str());
                let path = document
                    .file
                    .as_ref()
                    .and_then(PickedFile::path)
                    .map(str::to_owned);
                SavedDocument {
                    name: document.name,
                    text: document.text,
                    unsaved,
                    path,
                }
            })
            .collect();
        Session {
            documents,
            current: index,
        }
    }

    /// Puts back the documents the last run left open.
    fn restore(&self, session: Session) {
        let count = session.documents.len();
        let mut documents: Vec<Shelved> = session
            .documents
            .into_iter()
            .enumerate()
            .map(|(index, saved)| Shelved {
                id: index as u64 + 1,
                file: file_at(saved.path.as_deref()),
                on_disk: (!saved.unsaved).then(|| saved.text.clone()),
                name: saved.name,
                text: saved.text,
            })
            .collect();
        let current = documents.remove(session.current.min(count - 1));
        self.shelved.set(documents);
        self.next_document.set(count as u64 + 1);
        self.show(current);
        self.status.set(if count == 1 {
            "Restored 1 document".to_owned()
        } else {
            format!("Restored {count} documents")
        });
    }
}

/// How the list and the document share an expanded window.
///
/// The Loop reference gives its sidebar about a quarter of the window, which is what a
/// list of names needs and no more: the document is what the window is for.
const LIST_SHARE: f32 = 1.0;
const DOCUMENT_SHARE: f32 = 3.0;

/// The open documents, newest last, with the one being edited marked.
///
/// The same element stands beside the document on a desktop window and arrives in a sheet
/// on anything narrower. It is written once because it is one list: a list that had to be
/// authored twice would drift the first time either copy changed.
#[Composable]
fn document_list(
    entries: Vec<(u64, String)>,
    current: u64,
    choose: Rc<dyn Fn(u64)>,
    new_document: Rc<dyn Fn()>,
) {
    Column()
        .modifier(Modifier.fill_max_width().fill_max_height())
        .space_role(SpaceRole::Sm)
        .content(|| {
            Row()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Text("Documents")
                        .type_role(TypeRole::Subtitle)
                        .modifier(Modifier.weight(1.0));
                    let new_document = Rc::clone(&new_document);
                    Button("New")
                        .variant(ButtonVariant::Tonal)
                        .on_click(move || new_document());
                });
            Separator(None);
            ScrollColumn()
                .modifier(Modifier.fill_max_width().weight(1.0))
                .content(|| {
                    for (id, name) in entries.iter().cloned() {
                        let choose = Rc::clone(&choose);
                        key(id, || {
                            Button(name)
                                .modifier(Modifier.fill_max_width())
                                // The one being edited is the filled one. A list where
                                // every row looks the same is a list that does not say
                                // where you are.
                                .variant(if id == current {
                                    ButtonVariant::Tonal
                                } else {
                                    ButtonVariant::Text
                                })
                                .color(Paint::Role(ColorRole::OnSurface))
                                .on_click(move || choose(id));
                        });
                    }
                });
        });
}

/// One of the three document styles in the format sheet.
#[Composable]
fn style_choice(
    label: &'static str,
    role: TypeRole,
    chosen: TypeRole,
    pick: MutableState<TypeRole>,
) {
    Button(label)
        .modifier(Modifier.weight(1.0))
        .variant(if chosen == role {
            ButtonVariant::Tonal
        } else {
            ButtonVariant::Text
        })
        .on_click(move || pick.set(role));
}

/// The editor.
#[Composable]
pub fn app() {
    let window = current_window_size();
    // A document is read across its lines, so past a certain width a page that keeps
    // growing is a page nobody can read. On a desktop window the page stops at the width
    // an expanded window starts at and takes a margin either side, which is what a page
    // is. Narrower than that the page is the window, because there is nothing to spare.
    let page_width = if window.is_expanded() {
        Some(WindowSizeClass::EXPANDED_MIN_WIDTH_DP)
    } else {
        None
    };
    let docs = Docs {
        text: remember(|| mutable_state_of(String::new())),
        stamp: remember(|| mutable_state_of(0_u64)),
        name: remember(|| mutable_state_of(UNTITLED.to_owned())),
        file: remember(|| mutable_state_of(Option::<PickedFile>::None)),
        on_disk: remember(|| mutable_state_of(Some(String::new()))),
        current: remember(|| mutable_state_of(1_u64)),
        shelved: remember(|| {
            mutable_state_of_with_policy(Vec::<Shelved>::new(), never_equal_policy())
        }),
        next_document: remember(|| mutable_state_of(2_u64)),
        status: remember(|| mutable_state_of("Ready".to_owned())),
        busy: remember(|| mutable_state_of(false)),
        revision: remember(|| mutable_state_of(0_u64)),
    };
    let status = docs.status;
    let file_open = remember(|| mutable_state_of(false));
    let list_open = remember(|| mutable_state_of(false));
    let format_open = remember(|| mutable_state_of(false));
    let document_type = remember(|| mutable_state_of(TypeRole::Body));
    // Whether the last session has been asked for and answered. Until then nothing is
    // written over it.
    let restored = remember(|| mutable_state_of(false));
    let scope = remember_coroutine_scope();
    // A desktop window has room for the list to stand beside the document. Narrower than
    // that it is a sheet, which is the same list arriving from an edge instead.
    let list_beside = window.is_expanded();
    let crowded = window.is_compact();

    // The documents the last run left open come back, unless the reader has already
    // started on something in the moment it took to read them. The read is a storage
    // operation, so the UI thread does not wait for it.
    launched_effect((), move || async move {
        match storage::read_string(SESSION_KEY).await {
            Ok(Some(saved)) => {
                if docs.revision.get_untracked() == 0 {
                    if let Some(session) = decode_session(&saved) {
                        docs.restore(session);
                    }
                }
            }
            Ok(None) => {}
            Err(error) => docs
                .status
                .set(format!("The last session could not be restored: {error}")),
        }
        restored.set(true);
    });

    // The open documents are kept for next time once the reader pauses. Each change starts
    // the wait again, so a run of typing is one write at its end.
    let revision = docs.revision.get();
    let ready = restored.get();
    launched_effect((revision, ready), move || async move {
        if revision == 0 || !ready {
            return;
        }
        delay(SESSION_PAUSE).await;
        if let Err(error) = storage::write(SESSION_KEY, encode_session(&docs.session())).await {
            docs.status.set(format!(
                "The open documents could not be kept for next time: {error}"
            ));
        }
    });

    // Reading a file the open dialog chose. Off the UI thread, and back on it with the
    // answer.
    let open_file: Rc<dyn Fn()> = {
        let scope = scope.clone();
        Rc::new(move || {
            file_open.set(false);
            let scope = scope.clone();
            FileDialog::open_file()
                .filter(TEXT_LABEL, TEXT_EXTENSIONS)
                .show(move |result| {
                    let Some(picked) = result.files().into_iter().next() else {
                        if result == FileDialogResult::Unavailable {
                            docs.status.set(NO_DIALOG.to_owned());
                        }
                        return;
                    };
                    docs.busy.set(true);
                    scope.launch(async move {
                        let outcome = picked.read().await;
                        docs.opened(picked, outcome);
                    });
                });
        })
    };

    // Writing the document on screen to a file.
    let write_to: Rc<dyn Fn(PickedFile)> = {
        let scope = scope.clone();
        Rc::new(move |target: PickedFile| {
            docs.busy.set(true);
            let id = docs.current.get_untracked();
            let contents = docs.text.get_untracked();
            scope.launch(async move {
                let outcome = target.write(contents.clone().into_bytes()).await;
                docs.saved(id, target, contents, outcome);
            });
        })
    };

    // Asking where to save, then saving there.
    let save_as: Rc<dyn Fn()> = {
        let write_to = Rc::clone(&write_to);
        Rc::new(move || {
            file_open.set(false);
            let write_to = Rc::clone(&write_to);
            FileDialog::save()
                .name(suggested_name(&docs.name.get_untracked()))
                .filter(TEXT_LABEL, TEXT_EXTENSIONS)
                .show(move |result| match result.files().into_iter().next() {
                    Some(target) => write_to(target),
                    None => {
                        if result == FileDialogResult::Unavailable {
                            docs.status.set(NO_DIALOG.to_owned());
                        }
                    }
                });
        })
    };

    // Saving writes the file the document came from again when that file can be written
    // again, and asks where to save it otherwise: a document that never had a file, a copy
    // a browser handed over to read, or a save that a browser could only download.
    let save: Rc<dyn Fn()> = {
        let (write_to, save_as) = (Rc::clone(&write_to), Rc::clone(&save_as));
        Rc::new(move || match docs.file.get_untracked() {
            Some(file) if file.can_write_again() => write_to(file),
            _ => save_as(),
        })
    };

    // Putting the document on screen on the shelf and taking another one off it. The
    // editor's states are the open document, so choosing one is a swap rather than a
    // different code path through the whole screen.
    let choose: Rc<dyn Fn(u64)> = Rc::new(move |id: u64| {
        if id == docs.current.get_untracked() {
            list_open.set(false);
            return;
        }
        let Some(at) = docs
            .shelved
            .with(|list| list.iter().position(|document| document.id == id))
        else {
            return;
        };
        let on_screen = docs.on_screen();
        let mut taken = Shelved::default();
        docs.shelved.update(|list| {
            taken = list.remove(at);
            list.push(on_screen);
        });
        docs.status.set(format!("Opened {}", taken.name));
        docs.show(taken);
        docs.changed();
        list_open.set(false);
    });

    let new_document: Rc<dyn Fn()> = Rc::new(move || {
        let id = docs.shelve();
        docs.show(Shelved::untitled(id));
        docs.status.set("New document".to_owned());
        docs.changed();
        list_open.set(false);
    });

    // Every open document, in the order they were opened, with the one on screen in its
    // own place rather than at the end: the list is not allowed to jump about because the
    // reader moved between two documents.
    let current_id = docs.current.get();
    let current_name = docs.name.get();
    let current_file = docs.file.get();
    let current_path = current_file
        .as_ref()
        .and_then(PickedFile::path)
        .map(str::to_owned);
    let mut entries: Vec<(u64, String)> = docs.shelved.with(|list| {
        list.iter()
            .map(|document| (document.id, document.name.clone()))
            .collect()
    });
    entries.push((current_id, current_name.clone()));
    entries.sort_by_key(|(id, _)| *id);

    let contents = docs.text.get();
    let Counts {
        words,
        characters,
        lines,
    } = counts(&contents);
    let working = docs.busy.get();
    let edited = docs.on_disk.get().as_deref() != Some(contents.as_str());
    let generation = docs.stamp.get();
    let style = document_type.get();

    // The frame is named rather than built. Whether this bar is the window's own caption
    // or a bar beneath the system's, and where the page starts under it, are answers this
    // screen never has to know.
    Scaffold()
        .top_bar(|| {
            document_bar(page_width, working, || {
                // The application's name, on the windows with room for it. On a phone the
                // four actions need the whole bar, and the page already says what
                // document this is, which is the thing a title is for. Left in, the name
                // was squeezed to one letter per line.
                if crowded {
                    Box().modifier(Modifier.weight(1.0));
                } else {
                    Text("Notepad")
                        .type_role(TypeRole::Title)
                        .modifier(Modifier.weight(1.0))
                        .max_lines(1)
                        .overflow(TextOverflow::Ellipsis);
                }
                // Whether there is anything to lose, said where the actions that could
                // lose it are. Not the error colour: unsaved work is an ordinary state of
                // a document being written, not a fault.
                if edited {
                    Text("Edited")
                        .type_role(TypeRole::Label)
                        .color(Paint::Role(ColorRole::OnSurfaceVariant));
                }
                // Only where the list is not already on screen. A button that opens what
                // you are looking at is a button that does nothing.
                if !list_beside {
                    let open = list_open;
                    Button("Documents")
                        .variant(ButtonVariant::Text)
                        .on_click(move || open.set(true));
                }
                // Starting a document no longer throws one away: the one that was on
                // screen is in the list, which is why this offers nothing back.
                let start = Rc::clone(&new_document);
                Button("New")
                    .variant(ButtonVariant::Text)
                    .enabled(!working)
                    .on_click(move || start());
                let open = file_open;
                Button("File")
                    .variant(ButtonVariant::Tonal)
                    .on_click(move || open.set(true));
                let open = format_open;
                Button("Format")
                    .variant(ButtonVariant::Text)
                    .on_click(move || open.set(true));
                let save = Rc::clone(&save);
                Button("Save")
                    .variant(ButtonVariant::Filled)
                    .enabled(!working)
                    .on_click(move || save());
            });
        })
        .content(|| {
            // Which file the document is, and what can be done with files.
            //
            // It used to be a strip above the page, carrying a path in monospace across
            // the top of a document. Neither memo reference has one: a note is a page with
            // a name on it, and where the bytes live is something you go and ask for. A
            // sheet is where you ask. It is declared here rather than at the foot of the
            // screen because it belongs to the button in the bar above, and a sheet is
            // drawn over the page wherever it is declared.
            let dismiss = file_open;
            Sheet(file_open.get())
                .on_dismiss(move || dismiss.set(false))
                .modifier(Modifier.fill_max_width())
                .content(|| {
                    Column()
                        .modifier(Modifier.fill_max_width())
                        .space_role(SpaceRole::Md)
                        .content(|| {
                            Row()
                                .modifier(Modifier.fill_max_width())
                                .alignment(Alignment::CenterStart)
                                .content(|| {
                                    Text("File")
                                        .type_role(TypeRole::Subtitle)
                                        .modifier(Modifier.weight(1.0));
                                    let close = file_open;
                                    Button("Close")
                                        .variant(ButtonVariant::Filled)
                                        .on_click(move || close.set(false));
                                });
                            Separator(None);
                            Text(current_name.clone())
                                .type_role(TypeRole::Label)
                                .max_lines(1)
                                .overflow(TextOverflow::Ellipsis);
                            // Where the file is, where there is a where. A browser gives a
                            // page the file and its name but never its place on the disk.
                            if let Some(path) = &current_path {
                                Text(path.clone())
                                    .type_role(TypeRole::Caption)
                                    .color(Paint::Role(ColorRole::OnSurfaceVariant))
                                    .max_lines(2)
                                    .overflow(TextOverflow::Ellipsis);
                            }
                            Row()
                                .modifier(Modifier.fill_max_width())
                                .space_role(SpaceRole::Sm)
                                .alignment(Alignment::CenterStart)
                                .content(|| {
                                    Text(
                                        "Open reads a file into a document of its own. Save as \
                                         writes this one to a file you choose.",
                                    )
                                    .type_role(TypeRole::Caption)
                                    .color(Paint::Role(ColorRole::OnSurfaceVariant))
                                    .modifier(Modifier.weight(1.0));
                                    let open = Rc::clone(&open_file);
                                    Button("Open")
                                        .variant(ButtonVariant::Tonal)
                                        .enabled(!working)
                                        .on_click(move || open());
                                    let save_as = Rc::clone(&save_as);
                                    Button("Save as")
                                        .variant(ButtonVariant::Text)
                                        .enabled(!working)
                                        .on_click(move || save_as());
                                });
                        });
                });

            // The iOS reference keeps document formatting in a sheet rather than adding
            // another permanent strip around the page. These choices affect the editor's
            // type role directly, so every design system supplies its own face, size and
            // weight for the selected document style.
            let dismiss = format_open;
            Sheet(format_open.get())
                .on_dismiss(move || dismiss.set(false))
                .modifier(Modifier.fill_max_width())
                .content(|| {
                    Column()
                        .modifier(Modifier.fill_max_width())
                        .space_role(SpaceRole::Md)
                        .content(|| {
                            Row()
                                .modifier(Modifier.fill_max_width())
                                .alignment(Alignment::CenterStart)
                                .content(|| {
                                    Text("Formatting")
                                        .type_role(TypeRole::Subtitle)
                                        .modifier(Modifier.weight(1.0));
                                    let close = format_open;
                                    Button("Done")
                                        .variant(ButtonVariant::Filled)
                                        .on_click(move || close.set(false));
                                });
                            Separator(None);
                            Text("Document style")
                                .type_role(TypeRole::Label)
                                .color(Paint::Role(ColorRole::OnSurfaceVariant));
                            Row()
                                .modifier(Modifier.fill_max_width())
                                .space_role(SpaceRole::Sm)
                                .content(|| {
                                    style_choice(
                                        "Heading",
                                        TypeRole::Headline,
                                        style,
                                        document_type,
                                    );
                                    style_choice("Body", TypeRole::Body, style, document_type);
                                    style_choice(
                                        "Monospaced",
                                        TypeRole::Mono,
                                        style,
                                        document_type,
                                    );
                                });
                            Surface()
                                .modifier(
                                    Modifier
                                        .fill_max_width()
                                        .shape_role(ShapeRole::Large)
                                        .padding_role(SpaceRole::Md),
                                )
                                .content(|| {
                                    Text("The quick brown fox jumps over the lazy dog.")
                                        .type_role(style);
                                });
                        });
                });

            // The same list, arriving from an edge, for the windows with no room beside
            // the document. Which edge is the Renderer's decision.
            let dismiss = list_open;
            Sheet(list_open.get() && !list_beside)
                .on_dismiss(move || dismiss.set(false))
                .modifier(Modifier.fill_max_width())
                .content(|| {
                    let start = Rc::clone(&new_document);
                    document_list(
                        entries.clone(),
                        current_id,
                        Rc::clone(&choose),
                        Rc::new(move || start()),
                    );
                });

            // The list and the document, side by side where there is room. Both memo
            // references do this on a wide window: Loop keeps its documents down the left
            // and iOS opens the note over the list it came from.
            Row()
                .modifier(Modifier.fill_max_width().fill_max_height())
                .content(|| {
                    if list_beside {
                        Column()
                            .modifier(
                                Modifier
                                    .weight(LIST_SHARE)
                                    .fill_max_height()
                                    .padding_role(SpaceRole::Md),
                            )
                            .content(|| {
                                let start = Rc::clone(&new_document);
                                document_list(
                                    entries.clone(),
                                    current_id,
                                    Rc::clone(&choose),
                                    Rc::new(move || start()),
                                );
                            });
                        Divider().vertical(true);
                    }

                    // The page defines a column, and everything under the bar lines up
                    // with it.
                    //
                    // The page alone used to be centred and measured while the file strip
                    // and the status line ran to the window edges, so the three things a
                    // document window is made of shared no edge at all. A page is a
                    // measure: the chrome that belongs to the document sits on the same
                    // measure or the document is not a page, it is a rectangle floating
                    // between two full width strips.
                    Box()
                        .modifier(
                            Modifier
                                .weight(DOCUMENT_SHARE)
                                .fill_max_width()
                                .fill_max_height(),
                        )
                        .alignment(Alignment::TopCenter)
                        .content(|| {
                            Column()
                                .modifier(
                                    Modifier
                                        .fill_max_width_if(page_width.is_none())
                                        .width_if(page_width)
                                        .fill_max_height()
                                        // The medium step, because that is what the bar
                                        // insets its own contents by. Anything else and the
                                        // title and the page below it start at two
                                        // different places.
                                        .padding_role(SpaceRole::Md),
                                )
                                .space_role(SpaceRole::Md)
                                .content(|| {
                                    // The document's name, which is the first thing on the
                                    // page in both memo references. A document is a thing
                                    // with a name, and a page that opens straight into body
                                    // text is a page you cannot tell from the one beside it.
                                    //
                                    // Where the file is, on a platform with paths, is
                                    // help for the name rather than a second heading.
                                    let title = || {
                                        Text(current_name.clone())
                                            .modifier(Modifier.fill_max_width())
                                            .type_role(TypeRole::Headline)
                                            .max_lines(1)
                                            .overflow(TextOverflow::Ellipsis);
                                    };
                                    match &current_path {
                                        Some(path) => Tooltip(path.clone())
                                            .modifier(Modifier.fill_max_width())
                                            .content(title),
                                        None => title(),
                                    }

                                    // The page: the field itself, taking everything the
                                    // title above it and the status line below it leave.
                                    //
                                    // Height cannot reach a field through a scrolling
                                    // column, because what a scroll offers its content is
                                    // unbounded, so the field is not inside one. It scrolls
                                    // itself once it is taller than the window.
                                    Box()
                                        .modifier(Modifier.fill_max_width().weight(1.0))
                                        .content(|| {
                                            editor(
                                                generation,
                                                contents.clone(),
                                                style,
                                                Rc::new(move |value| {
                                                    docs.text.set(value);
                                                    docs.changed();
                                                }),
                                            );
                                        });

                                    // The status line is not part of the page, so a rule
                                    // separates them. Its thickness and colour are the
                                    // design system's.
                                    Separator(None);

                                    // The status line: what the last file operation did on
                                    // the left, the document's measurements on the right.
                                    Row()
                                        .modifier(Modifier.fill_max_width())
                                        .space_role(SpaceRole::Md)
                                        .alignment(Alignment::CenterStart)
                                        .content(|| {
                                            Text(status.get())
                                                .modifier(Modifier.weight(1.0))
                                                .type_role(TypeRole::Caption)
                                                .color(Paint::Role(ColorRole::OnSurfaceVariant))
                                                .max_lines(1)
                                                .overflow(TextOverflow::Ellipsis);
                                            for counter in [
                                                format!("{words} words"),
                                                format!("{characters} characters"),
                                                format!("{lines} lines"),
                                            ] {
                                                Text(counter).type_role(TypeRole::Caption).color(
                                                    Paint::Role(ColorRole::OnSurfaceVariant),
                                                );
                                            }
                                        });
                                });
                        });
                });
        });
}

/// Runs the sample as a program of its own. The desktop binary is one line of this.
pub fn launch() {
    launch_builder().application(app);
}

/// How this sample is configured, in one place because three entry points need it.
///
/// The theme above all: a sample that names one and then reaches a platform through an
/// entry point that makes its own builder is a sample that draws the same screens in a
/// different design system depending on where it runs. Samples are demonstrations, so
/// they let you see any of the design systems rather than only the one this machine
/// happens to select. Unset, the app adapts to the host platform, which is what a real
/// application wants.
fn launch_builder() -> LaunchBuilder {
    // The name the window carries. A desktop lists windows by it, so a window that said
    // nothing was listed under whatever the renderer happened to be called.
    LaunchBuilder::new()
        // The same as [bundle] identifier in Dioxus.toml. It names the storage.
        .identifier("dev.darkpyonix.composerust.samples.notepad")
        .with_theme(compose_rust::demo_theme())
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Notepad")
                // Without one the window wears the toolkit's picture wherever the system
                // lists windows. The bytes travel as an asset and the renderer refers to
                // them by id: a path would be a fact about the machine this was built on,
                // and a name would ask the toolkit to find something it may not have.
                .with_icon(compose_rust::asset::asset(
                    compose_rust::schema::AssetKind::Png,
                    include_bytes!("../assets/icon.png"),
                )),
        )
}

// The platforms where the sample is not a program. Android's Activity and the browser's
// page both own the loop, so neither has a `main` to call: each names an entry point that
// registers the root composable, and these macros define it. Each compiles into nothing
// that runs off its own platform, so a desktop build still checks the other two.
compose_rust::android_application!({ launch_builder() }, app);
compose_rust::web_application!({ launch_builder() }, app);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;
    use compose_rust::ComposeHost;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
    use compose_rust::schema::{
        EventPayload, FileDelivery, FileDialogKind, FileDialogStatus, FileOp, IoStatus,
        PropertyKind, StorageOp, WidgetKind,
    };
    use compose_rust::storage::{Backend, testing};
    use std::collections::HashMap;
    use std::sync::{Mutex, MutexGuard, OnceLock};

    /// Hangul, a combining mark and an emoji: three ways a byte count and a character count
    /// come apart, all of which have to survive the trip to disk and back.
    const KOREAN: &str = "안녕하세요\n오늘도 좋은 하루 보내세요 \u{1f44b}";

    const DOCUMENT_PLACEHOLDER: &str = "Type here, or open a file";

    /// The identifier the sample launches with, which names its storage.
    const IDENTIFIER: &str = "dev.darkpyonix.composerust.samples.notepad";

    /// The Host keeps the file dialogs and the file operations it has not sent yet in one
    /// queue for the whole process, and any Host's next batch carries them. Two editors
    /// built at once would carry each other's, so the tests that build one take turns.
    static SERIAL: Mutex<()> = Mutex::new(());

    fn take_turn() -> MutexGuard<'static, ()> {
        SERIAL.lock().unwrap_or_else(|poison| poison.into_inner())
    }

    /// Storage for every editor in this binary: the Renderer's, as in a browser, so each
    /// operation is a `StorageCall` record in a batch and the test answers it with an
    /// `IoAnswer`, the way the web Renderer does. Set once, because the storage's settings
    /// are process wide; each test thread keeps its own requests and answers.
    fn storage() {
        static PREPARED: OnceLock<()> = OnceLock::new();
        PREPARED.get_or_init(|| testing::reset(Some(IDENTIFIER), Backend::Renderer));
    }

    /// One node as the Renderer holds it, with only what these tests read.
    #[derive(Default)]
    struct Node {
        widget: Option<WidgetKind>,
        parent: Option<u32>,
        children: Vec<u32>,
        text: Option<String>,
        placeholder: Option<String>,
        type_role: Option<i64>,
        on_click: Option<u64>,
        on_value_change: Option<u64>,
        modifiers: HashMap<u16, compose_rust::schema::Modifier>,
    }

    /// A storage operation a batch carried: number, operation, key and value.
    type StorageCall = (u32, StorageOp, String, Vec<u8>);

    /// A file dialog a batch asked for: number, kind and the name it suggests.
    type DialogCall = (u32, FileDialogKind, String);

    /// An operation on a file a browser holds: number, operation, handle and bytes.
    type FileCall = (u32, FileOp, u32, Vec<u8>);

    /// The real editor, driven the way the Renderer drives it: every batch is applied to a
    /// model of the Renderer's node table, so a node that has left the screen is gone.
    struct Editor {
        host: ComposeHost,
        nodes: HashMap<u32, Node>,
        /// Every message the screen has said, in order, with its action label.
        messages: Vec<(String, String)>,
        /// What the batches asked of storage, of the file dialog and of the browser's
        /// files, in order.
        storage: Vec<StorageCall>,
        dialogs: Vec<DialogCall>,
        files: Vec<FileCall>,
        frame: u64,
        _turn: MutexGuard<'static, ()>,
    }

    impl Editor {
        /// A first run: nothing was kept from a last one.
        fn new() -> Self {
            Self::with_session(None)
        }

        /// The editor once storage has answered its first read with `session`, the value
        /// a last run kept, or with nothing.
        fn with_session(session: Option<&str>) -> Self {
            let turn = take_turn();
            storage();
            compose_rust::file_dialog::reset_file_dialogs();
            compose_rust::window::reset_window_size();
            let mut host = ComposeHost::new(app);
            let first = host
                .rebuild()
                .expect("the first frame failed to encode")
                .to_vec();
            let mut editor = Self {
                host,
                nodes: HashMap::new(),
                messages: Vec::new(),
                storage: Vec::new(),
                dialogs: Vec::new(),
                files: Vec::new(),
                frame: 0,
                _turn: turn,
            };
            editor.absorb(&first);
            let reads: Vec<u32> = editor
                .storage
                .iter()
                .filter(|(_, op, key, _)| *op == StorageOp::Read && key == SESSION_KEY)
                .map(|(request, ..)| *request)
                .collect();
            assert_eq!(
                reads.len(),
                1,
                "the first frame should ask for the last session once"
            );
            match session {
                Some(saved) => editor.answer_io(reads[0], IoStatus::Ok, saved.as_bytes()),
                None => editor.answer_io(reads[0], IoStatus::NotFound, &[]),
            }
            assert_eq!(
                editor.of(WidgetKind::TextField).len(),
                1,
                "the screen should have the document field and no other"
            );
            editor
        }

        fn absorb(&mut self, batch: &[u8]) {
            for mutation in decode_batch(batch).expect("a frame did not decode") {
                match mutation {
                    Mutation::Create { node_id, widget } => {
                        self.nodes.insert(
                            node_id,
                            Node {
                                widget: Some(widget),
                                ..Node::default()
                            },
                        );
                    }
                    Mutation::Insert {
                        parent_id, node_id, ..
                    }
                    | Mutation::Move {
                        parent_id, node_id, ..
                    } => {
                        self.detach(node_id);
                        self.nodes
                            .entry(parent_id)
                            .or_default()
                            .children
                            .push(node_id);
                        self.nodes.entry(node_id).or_default().parent = Some(parent_id);
                    }
                    Mutation::Remove { node_id } => {
                        self.detach(node_id);
                        self.forget(node_id);
                    }
                    Mutation::ShowMessage { text, action, .. } => {
                        self.messages.push((text.to_owned(), action.to_owned()));
                    }
                    Mutation::StorageCall {
                        request,
                        op,
                        store,
                        key,
                        data,
                    } => {
                        assert_eq!(store, IDENTIFIER, "storage named by another application");
                        self.storage
                            .push((request, op, key.to_owned(), data.to_vec()));
                    }
                    Mutation::ShowFileDialog {
                        request,
                        kind,
                        name,
                        ..
                    } => {
                        self.dialogs.push((request, kind, name.to_owned()));
                    }
                    Mutation::FileCall {
                        request,
                        op,
                        handle,
                        data,
                        ..
                    } => {
                        self.files.push((request, op, handle, data.to_vec()));
                    }
                    Mutation::SetText { node_id, text, .. } => {
                        self.nodes.entry(node_id).or_default().text = Some(text.to_owned());
                    }
                    Mutation::SetModifier {
                        node_id,
                        index,
                        modifier,
                    } => {
                        let node = self.nodes.entry(node_id).or_default();
                        if modifier == compose_rust::schema::Modifier::Empty {
                            node.modifiers.remove(&index);
                        } else {
                            node.modifiers.insert(index, modifier);
                        }
                    }
                    Mutation::SetProp {
                        node_id,
                        property,
                        value,
                    } => {
                        let node = self.nodes.entry(node_id).or_default();
                        match (property, value) {
                            (PropertyKind::Text, PropertyValue::String(text)) => {
                                node.text = Some(text.to_owned());
                            }
                            (PropertyKind::Placeholder, PropertyValue::String(text)) => {
                                node.placeholder = Some(text.to_owned());
                            }
                            (PropertyKind::TypeRole, PropertyValue::Integer(role)) => {
                                node.type_role = Some(role);
                            }
                            (PropertyKind::OnClick, PropertyValue::Integer(id)) => {
                                node.on_click = Some(id as u64);
                            }
                            (PropertyKind::OnValueChange, PropertyValue::Integer(id)) => {
                                node.on_value_change = Some(id as u64);
                            }
                            _ => {}
                        }
                    }
                    _ => {}
                }
            }
        }

        fn detach(&mut self, node_id: u32) {
            let parent = self.nodes.get(&node_id).and_then(|node| node.parent);
            if let Some(parent) = parent.and_then(|parent| self.nodes.get_mut(&parent)) {
                parent.children.retain(|child| *child != node_id);
            }
        }

        /// Drops a node and everything under it, the way the Renderer's node table does.
        fn forget(&mut self, node_id: u32) {
            if let Some(node) = self.nodes.remove(&node_id) {
                for child in node.children {
                    self.forget(child);
                }
            }
        }

        fn dispatch(&mut self, node_id: u32, handler_id: u64, payload: EventPayload<'_>) {
            let (batch, _) = self
                .host
                .dispatch(HostEvent {
                    node_id,
                    handler_id,
                    payload,
                })
                .expect("the event failed");
            // The borrow of the Host's buffer has to end before the next call, so the
            // frame is copied out here.
            let owned = batch.to_vec();
            self.absorb(&owned);
        }

        /// One frame, as the Renderer asks for one when the Host has said there is work.
        fn render(&mut self) {
            self.frame += 16_000_000;
            let batch = self
                .host
                .render_frame(self.frame)
                .expect("a frame failed to encode")
                .to_vec();
            self.absorb(&batch);
        }

        /// What storage or a browser says back, as the event the Renderer sends.
        fn answer_io(&mut self, request: u32, status: IoStatus, data: &[u8]) {
            self.dispatch(
                0,
                0,
                EventPayload::IoAnswer {
                    request,
                    status,
                    data,
                },
            );
        }

        /// The file dialog asked for last.
        fn last_dialog(&self) -> DialogCall {
            self.dialogs
                .last()
                .cloned()
                .expect("the screen never asked for a file dialog")
        }

        /// A browser's answer to a dialog: one file it now holds a handle for.
        fn choose_in_browser(
            &mut self,
            request: u32,
            delivery: FileDelivery,
            handle: u32,
            name: &str,
        ) {
            self.dispatch(
                0,
                0,
                EventPayload::FileHandles {
                    request,
                    status: FileDialogStatus::Chosen,
                    filter: 0,
                    delivery,
                    first_handle: handle,
                    names: name,
                    kinds: "f",
                },
            );
        }

        /// A desktop's answer to a dialog: one path.
        fn choose_path(&mut self, request: u32, path: &std::path::Path) {
            let path = path.to_str().expect("a test path is UTF-8");
            self.dispatch(
                0,
                0,
                EventPayload::FileDialogResult {
                    request,
                    status: FileDialogStatus::Chosen,
                    filter: 0,
                    paths: path,
                },
            );
        }

        /// The operation on a browser's file asked for last, after `before` of them. The
        /// batch that started it carries it; a frame is asked for in case it did not.
        fn file_call_after(&mut self, before: usize) -> FileCall {
            if self.files.len() == before {
                self.render();
            }
            assert_eq!(
                self.files.len(),
                before + 1,
                "one file operation should have been asked for: {:?}",
                self.files
            );
            self.files.last().cloned().unwrap()
        }

        /// Opens a file the way a browser hands one over: the dialog, the handle, the read.
        fn open_in_browser(&mut self, delivery: FileDelivery, handle: u32, name: &str, text: &str) {
            self.click("Open");
            let (request, kind, _) = self.last_dialog();
            assert_eq!(kind, FileDialogKind::OpenFile);
            let before = self.files.len();
            self.choose_in_browser(request, delivery, handle, name);
            let (read, op, asked, _) = self.file_call_after(before);
            assert_eq!((op, asked), (FileOp::Read, handle));
            self.answer_io(read, IoStatus::Ok, text.as_bytes());
        }

        /// Every node of this kind.
        fn of(&self, widget: WidgetKind) -> Vec<u32> {
            self.nodes
                .iter()
                .filter(|(_, node)| node.widget == Some(widget))
                .map(|(id, _)| *id)
                .collect()
        }

        /// Every node of this kind whose text reads exactly this.
        ///
        /// The kind matters: a list's heading and the button that opens the list say the
        /// same word, and a test about where the list is would otherwise be answered by
        /// the button in the bar.
        fn labelled(&self, widget: WidgetKind, label: &str) -> Vec<u32> {
            self.of(widget)
                .into_iter()
                .filter(|id| self.nodes[id].text.as_deref() == Some(label))
                .collect()
        }

        /// The field with this placeholder. It is rebuilt with a new id whenever the
        /// document's contents come from outside it.
        fn field(&self, placeholder: &str) -> u32 {
            self.of(WidgetKind::TextField)
                .into_iter()
                .find(|id| self.nodes[id].placeholder.as_deref() == Some(placeholder))
                .unwrap_or_else(|| panic!("the screen has no field for {placeholder}"))
        }

        fn document_field(&self) -> u32 {
            self.field(DOCUMENT_PLACEHOLDER)
        }

        /// Typing into the document. The field keeps what was typed, as a real one does,
        /// and the Host is only told.
        fn type_document(&mut self, text: &str) {
            let node_id = self.document_field();
            let handler = self.nodes[&node_id]
                .on_value_change
                .expect("the field declared no change handler");
            self.nodes.get_mut(&node_id).unwrap().text = Some(text.to_owned());
            self.dispatch(node_id, handler, EventPayload::TextChanged(text));
        }

        /// Tells the screen the window changed size, the way the Renderer does.
        fn resize(&mut self, width_dp: f32) {
            self.dispatch(
                0,
                0,
                EventPayload::WindowSizeChanged {
                    width_dp,
                    height_dp: 900.0,
                    class: WindowSizeClass::from_width_dp(width_dp),
                    height_class: WindowHeightClass::from_height_dp(900.0),
                    safe_area_top: 0.0,
                    safe_area_leading: 0.0,
                    safe_area_trailing: 0.0,
                },
            );
        }

        /// Everything a node hangs from, nearest first.
        fn ancestors(&self, node: u32) -> Vec<u32> {
            let mut walk = node;
            let mut chain = Vec::new();
            while let Some(parent) = self.nodes.get(&walk).and_then(|node| node.parent) {
                chain.push(parent);
                walk = parent;
            }
            chain
        }

        fn in_a_sheet(&self, node: u32) -> bool {
            self.ancestors(node).iter().any(|parent| {
                self.nodes.get(parent).and_then(|node| node.widget) == Some(WidgetKind::Sheet)
            })
        }

        /// A button by its label. Where two say the same thing, they do the same thing.
        fn button(&self, label: &str) -> (u32, u64) {
            self.labelled(WidgetKind::Button, label)
                .into_iter()
                .find_map(|id| self.nodes[&id].on_click.map(|handler| (id, handler)))
                .unwrap_or_else(|| panic!("the screen has no {label} button"))
        }

        fn click(&mut self, label: &str) {
            let (node_id, handler) = self.button(label);
            self.dispatch(node_id, handler, EventPayload::Clicked);
        }

        /// File work on a desktop happens on a worker and lands on the UI thread in a later
        /// frame, so the test asks for frames the way the Renderer would.
        fn settle_until(&mut self, contains: &str) {
            for _ in 0..400 {
                std::thread::sleep(std::time::Duration::from_millis(5));
                self.render();
                if self.status().contains(contains) {
                    return;
                }
            }
            panic!(
                "the screen never reported {contains}; the status line says {}",
                self.status()
            );
        }

        /// What the editor kept for next time once it paused, answered as storage would.
        /// The wait is a timer, so this asks for frames until the write goes out.
        fn kept_session(&mut self) -> String {
            let written = |editor: &Self| {
                editor
                    .storage
                    .iter()
                    .filter(|(_, op, key, _)| *op == StorageOp::Write && key == SESSION_KEY)
                    .count()
            };
            let before = written(self);
            for _ in 0..600 {
                if written(self) > before {
                    break;
                }
                std::thread::sleep(std::time::Duration::from_millis(10));
                self.render();
            }
            let (request, _, _, data) = self
                .storage
                .iter()
                .rev()
                .find(|(_, op, key, _)| *op == StorageOp::Write && key == SESSION_KEY)
                .cloned()
                .expect("the open documents were never kept for next time");
            self.answer_io(request, IoStatus::Ok, &[]);
            String::from_utf8(data).expect("a session is UTF-8")
        }

        fn texts(&self) -> impl Iterator<Item = &str> {
            self.of(WidgetKind::Text)
                .into_iter()
                .filter_map(|id| self.nodes[&id].text.as_deref())
                .collect::<Vec<_>>()
                .into_iter()
        }

        /// The status line is the only text on the screen that reports what the file work
        /// did, so it is recognised by what it says.
        fn status(&self) -> String {
            self.texts()
                .find(|text| {
                    [
                        "Opened ",
                        "Saved ",
                        "Could not ",
                        "Restored ",
                        "This browser ",
                        "There is no file dialog",
                        "The last session",
                        "The open documents",
                    ]
                    .iter()
                    .any(|start| text.starts_with(start))
                        || *text == "Ready"
                        || *text == "New document"
                })
                .unwrap_or_default()
                .to_owned()
        }

        /// Whether the screen is showing this exact label anywhere.
        fn showing(&self, label: &str) -> bool {
            self.nodes
                .values()
                .any(|node| node.text.as_deref() == Some(label))
        }

        fn document_text(&self) -> String {
            self.nodes[&self.document_field()]
                .text
                .clone()
                .unwrap_or_default()
        }

        fn counter(&self, suffix: &str) -> String {
            self.texts()
                .find(|text| text.ends_with(suffix))
                .unwrap_or_default()
                .to_owned()
        }

        /// Every width a node on screen is held to.
        fn widths(&self) -> Vec<f32> {
            self.nodes
                .values()
                .flat_map(|node| node.modifiers.values())
                .filter_map(|modifier| match modifier {
                    compose_rust::schema::Modifier::Width(width) => Some(*width),
                    _ => None,
                })
                .collect()
        }
    }

    /// A path no other run of these tests can touch.
    ///
    /// Fixed names in the system temp directory meant two checkouts testing at once wrote
    /// and deleted each other's files, and whichever lost the race failed for a reason
    /// that had nothing to do with the code. The process id is what keeps them apart.
    fn scratch(name: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("sample-scratch-{name}-{}.tmp", std::process::id()))
    }

    /// The document, under every design system, in both schemes, at all three widths.
    ///
    /// This is the screen where the page, the bar above it and the line below it either
    /// share an edge or do not, and no assertion about a batch can tell the difference.
    #[test]
    fn fr14_the_document_is_recorded_under_every_design_system_and_width() {
        let _turn = take_turn();
        storage();
        sample_frames::record("Notepad", app, |_| {});
    }

    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        let _turn = take_turn();
        storage();
        compose_rust::window::reset_window_size();
        assert!(ComposeHost::new(app).rebuild().is_ok());
    }

    /// A first run is one empty document with no name and no file, and nothing to save.
    /// There is no starting file made in a temporary directory, which a browser page does
    /// not have.
    #[test]
    fn fr79_the_first_run_is_one_empty_document() {
        let editor = Editor::new();
        assert_eq!(editor.document_text(), "");
        assert!(
            editor.showing(UNTITLED),
            "the page does not name the document"
        );
        assert!(!editor.showing("Edited"));
        assert_eq!(
            editor.labelled(WidgetKind::Button, UNTITLED).len(),
            1,
            "the list should hold the one document"
        );
        assert!(editor.dialogs.is_empty() && editor.files.is_empty());
    }

    /// The open documents come back on the next run: their names, their text, and which of
    /// them have changes no file has. In a browser the handles went with the page, so a
    /// restored document saves through the save dialog; on a desktop it gets its file back
    /// from the path that was kept, and saves straight to it.
    #[test]
    fn fr79_notepad_restores_its_session() {
        let mut editor = Editor::new();
        editor.open_in_browser(FileDelivery::Handle, 41, "note.txt", "안녕");
        assert_eq!(editor.document_text(), "안녕");
        assert!(editor.showing("note.txt"));
        editor.type_document("안녕 고침");
        editor.click("New");
        editor.type_document("second");
        let kept = editor.kept_session();
        assert_eq!(
            decode_session(&kept),
            Some(Session {
                documents: vec![
                    SavedDocument {
                        name: "note.txt".to_owned(),
                        text: "안녕 고침".to_owned(),
                        unsaved: true,
                        path: None,
                    },
                    SavedDocument {
                        name: UNTITLED.to_owned(),
                        text: "second".to_owned(),
                        unsaved: true,
                        path: None,
                    },
                ],
                current: 1,
            })
        );
        drop(editor);

        // The page loads again, and storage hands back what the last one kept.
        let mut reloaded = Editor::with_session(Some(&kept));
        assert_eq!(reloaded.document_text(), "second");
        assert!(
            reloaded.showing("Edited"),
            "the unsaved mark did not come back"
        );
        reloaded.click("note.txt");
        assert_eq!(reloaded.document_text(), "안녕 고침");
        assert!(reloaded.showing("Edited"));
        reloaded.click("Save");
        let (_, kind, name) = reloaded.last_dialog();
        assert_eq!(
            (kind, name.as_str()),
            (FileDialogKind::Save, "note.txt"),
            "a browser's handle does not outlive the page, so the save asks where"
        );
        drop(reloaded);

        // On a desktop the kept path is the file again.
        let path = scratch("notepad-restored");
        let _ = std::fs::remove_file(&path);
        let shown = path.to_str().unwrap().to_owned();
        let session = Session {
            documents: vec![SavedDocument {
                name: "notepad-restored".to_owned(),
                text: KOREAN.to_owned(),
                unsaved: true,
                path: Some(shown.clone()),
            }],
            current: 0,
        };
        let mut native = Editor::with_session(Some(&encode_session(&session)));
        assert_eq!(native.document_text(), KOREAN);
        assert!(
            native.showing(&shown),
            "the path is not offered beside the name"
        );
        native.click("Save");
        assert!(
            native.dialogs.is_empty(),
            "a restored file asked where to save itself: {:?}",
            native.dialogs
        );
        native.settle_until("Saved ");
        assert_eq!(std::fs::read_to_string(&path).unwrap(), KOREAN);
        assert!(!native.showing("Edited"));
        let _ = std::fs::remove_file(&path);
    }

    /// A file a browser handed over with its handle is saved by writing that handle again,
    /// every time, without asking where.
    #[test]
    fn fr72_notepad_saves_again_through_the_same_handle() {
        let mut editor = Editor::new();
        editor.open_in_browser(FileDelivery::Handle, 51, "plan.md", "first");
        for text in ["second", "third"] {
            editor.type_document(text);
            let (dialogs, before) = (editor.dialogs.len(), editor.files.len());
            editor.click("Save");
            assert_eq!(editor.dialogs.len(), dialogs, "saving asked where to save");
            let (write, op, handle, data) = editor.file_call_after(before);
            assert_eq!(
                (op, handle, data.as_slice()),
                (FileOp::Write, 51, text.as_bytes())
            );
            editor.answer_io(write, IoStatus::Ok, &[]);
            assert!(
                editor.status().starts_with("Saved plan.md"),
                "{}",
                editor.status()
            );
            assert!(!editor.showing("Edited"));
        }
    }

    /// A browser without direct file access hands over a copy to read, and its save is a
    /// download. Saving the copy asks where, the download says it was one, and the next
    /// save asks again, because a download cannot be changed afterwards.
    #[test]
    fn fr72_notepad_saves_as_when_a_browser_can_only_download() {
        let mut editor = Editor::new();
        editor.open_in_browser(FileDelivery::Upload, 61, "draft.txt", "x");
        editor.type_document("changed");
        editor.click("Save");
        let (request, kind, name) = editor.last_dialog();
        assert_eq!((kind, name.as_str()), (FileDialogKind::Save, "draft.txt"));
        let before = editor.files.len();
        editor.choose_in_browser(request, FileDelivery::Download, 62, "draft.txt");
        let (write, op, handle, data) = editor.file_call_after(before);
        assert_eq!(
            (op, handle, data.as_slice()),
            (FileOp::Write, 62, b"changed".as_slice())
        );
        editor.answer_io(write, IoStatus::Ok, &[]);
        assert!(
            editor
                .status()
                .starts_with("This browser cannot save to a file directly"),
            "the download was not said: {}",
            editor.status()
        );
        assert!(!editor.showing("Edited"));

        let dialogs = editor.dialogs.len();
        editor.type_document("changed again");
        editor.click("Save");
        assert_eq!(
            editor.dialogs.len(),
            dialogs + 1,
            "a download was written again"
        );
    }

    /// Editing, saving, starting another document and opening the file again, through the
    /// dialogs a desktop shows. Korean text has to come back exactly as it went in: the
    /// file is bytes, the field is characters, and every step between them is somewhere the
    /// two can be confused.
    #[test]
    fn fr6_a_document_round_trips_through_editing_and_saving_with_korean_intact() {
        let path = scratch("notepad-round-trip");
        let _ = std::fs::remove_file(&path);

        let mut editor = Editor::new();
        editor.type_document(KOREAN);
        assert_eq!(editor.document_text(), KOREAN);

        editor.click("Save");
        let (request, kind, name) = editor.last_dialog();
        assert_eq!(
            (kind, name.as_str()),
            (FileDialogKind::Save, "Untitled.txt")
        );
        editor.choose_path(request, &path);
        editor.settle_until("Saved ");
        assert_eq!(
            std::fs::read_to_string(&path).expect("the file was not written"),
            KOREAN
        );

        // Start a different document, so opening has to put the text back rather than
        // leave it.
        editor.click("New");
        assert_eq!(editor.document_text(), "");

        editor.click("Open");
        let (request, kind, _) = editor.last_dialog();
        assert_eq!(kind, FileDialogKind::OpenFile);
        editor.choose_path(request, &path);
        editor.settle_until("Opened ");
        assert_eq!(
            editor.document_text(),
            KOREAN,
            "the reopened document does not match what was saved"
        );

        let _ = std::fs::remove_file(&path);
    }

    /// The counters under the editor count characters, not bytes. One Hangul syllable is
    /// one character even though it takes three bytes of UTF-8, so a screen that counted
    /// bytes would report three times the length of a Korean document.
    #[test]
    fn fr6_the_counters_report_characters_rather_than_bytes() {
        let mut editor = Editor::new();
        editor.type_document(KOREAN);

        let expected = KOREAN.chars().count();
        assert!(
            expected < KOREAN.len(),
            "the fixture is not multi-byte text"
        );
        assert_eq!(
            editor.counter(" characters"),
            format!("{expected} characters")
        );
        assert_eq!(editor.counter(" lines"), "2 lines");
    }

    /// The document's name stands at the top of the page, which is where both memo
    /// references put it, and it follows the file the document was opened from.
    #[test]
    fn fr22_the_page_opens_with_the_document_name() {
        let mut editor = Editor::new();
        assert!(
            editor.texts().any(|text| text == UNTITLED),
            "the page does not name the document it is showing"
        );

        editor.open_in_browser(FileDelivery::Handle, 71, "a-different-note.txt", "");
        assert!(
            editor.texts().any(|text| text == "a-different-note.txt"),
            "the name did not follow the file the document came from"
        );
    }

    /// Where the bytes live is something you go and ask for, not a strip across the top of
    /// every document. What acts on files is in a sheet, and there is no field to type a
    /// path into: the platform's dialog is where a file is chosen.
    #[test]
    fn fr22_the_file_controls_are_behind_a_sheet() {
        let editor = Editor::new();
        let (open, _) = editor.button("Open");
        let (save_as, _) = editor.button("Save as");
        assert!(editor.in_a_sheet(open), "Open is not in a sheet");
        assert!(editor.in_a_sheet(save_as), "Save as is not in a sheet");
        assert_eq!(
            editor.of(WidgetKind::TextField),
            vec![editor.document_field()]
        );
    }

    /// Formatting stays off the page until it is requested, and choosing a style changes
    /// the role sent for the document field rather than painting a look into the sample.
    #[test]
    fn fr22_the_format_sheet_changes_the_document_type_role() {
        let mut editor = Editor::new();
        let (heading, _) = editor.button("Heading");
        assert!(
            editor.in_a_sheet(heading),
            "the document style controls are not in a sheet"
        );

        editor.click("Heading");
        assert_eq!(
            editor.nodes[&editor.document_field()].type_role,
            Some(i64::from(u16::from(TypeRole::Headline))),
            "the selected style did not reach the document field"
        );
    }

    /// A desktop window has room for the list of documents to stand beside the one being
    /// written, which is what both memo references do. Anything narrower and the same list
    /// arrives from an edge instead.
    #[test]
    fn fr22_the_document_list_stands_beside_the_page_on_a_desktop_window() {
        let mut editor = Editor::new();
        let heading = "Documents";
        assert!(
            !editor.labelled(WidgetKind::Text, heading).is_empty(),
            "the screen never offers the list at all"
        );
        assert!(
            editor
                .labelled(WidgetKind::Text, heading)
                .iter()
                .all(|node| editor.in_a_sheet(*node)),
            "a phone should reach the list through a sheet"
        );

        editor.resize(1200.0);
        assert!(
            editor
                .labelled(WidgetKind::Text, heading)
                .iter()
                .any(|node| !editor.in_a_sheet(*node)),
            "a desktop window should stand the list beside the document"
        );
        compose_rust::window::reset_window_size();
    }

    /// Starting a document keeps the one that was on screen, which is what makes the list
    /// worth having and what stopped New from throwing work away.
    #[test]
    fn fr22_a_new_document_keeps_the_one_that_was_open() {
        let mut editor = Editor::new();
        editor.open_in_browser(FileDelivery::Handle, 81, "first-note.txt", "");
        editor.type_document("something worth keeping");

        editor.click("New");
        assert_eq!(editor.document_text(), "");
        assert!(
            editor.showing("first-note.txt"),
            "the document that was open is not in the list"
        );
    }

    /// A desktop window gives the document a margin and stops the page growing with the
    /// window. A phone keeps the page full width, because there is nothing to spare.
    #[test]
    fn fr20_the_page_takes_a_margin_on_a_desktop_window() {
        let mut editor = Editor::new();
        editor.resize(420.0);
        assert!(editor.widths().is_empty(), "{:?}", editor.widths());

        editor.resize(1200.0);
        assert!(
            editor
                .widths()
                .contains(&WindowSizeClass::EXPANDED_MIN_WIDTH_DP),
            "the page did not take a measure: {:?}",
            editor.widths()
        );
        compose_rust::window::reset_window_size();
    }

    /// The bar's contents and the page are held to the same measure, so the title starts
    /// where the page starts.
    ///
    /// A window has one left edge for the document in it. The bar used to span the window
    /// while the page was centred at 840dp, so on a desktop window the title began two
    /// hundred dp to the left of the page it named. Two nodes carrying the measure is what
    /// that agreement looks like on the wire: one is the row inside the bar, the other is
    /// the page column.
    #[test]
    fn fr20_the_bar_holds_its_contents_to_the_same_measure_as_the_page() {
        let measure = WindowSizeClass::EXPANDED_MIN_WIDTH_DP;
        let mut editor = Editor::new();
        editor.resize(1200.0);
        let wide = editor.widths();
        assert_eq!(
            wide.iter().filter(|width| **width == measure).count(),
            2,
            "the bar and the page should both be the measure: {wide:?}"
        );

        // Narrower, neither is measured: the page is the window and the bar's own inset is
        // already the page's.
        editor.resize(420.0);
        assert!(editor.widths().is_empty(), "{:?}", editor.widths());
        compose_rust::window::reset_window_size();
    }

    /// A document that has drifted from its file says so, and stops saying so once it has
    /// been saved.
    #[test]
    fn fr6_a_document_says_when_it_has_unsaved_changes() {
        let path = scratch("notepad-edited");
        let _ = std::fs::remove_file(&path);

        let mut editor = Editor::new();
        assert!(
            !editor.showing("Edited"),
            "an empty document that has never been touched is not edited"
        );

        editor.type_document("something new");
        assert!(
            editor.showing("Edited"),
            "a typed document has unsaved changes and should say so"
        );

        editor.click("Save");
        let (request, ..) = editor.last_dialog();
        editor.choose_path(request, &path);
        editor.settle_until("Saved ");
        assert!(
            !editor.showing("Edited"),
            "a document that has just been saved has nothing unsaved in it"
        );

        let _ = std::fs::remove_file(&path);
    }

    /// A file that cannot be opened is said out loud as well as written on the status
    /// line. Someone looking at the document rather than at its footer still finds out.
    #[test]
    fn fr21_a_failed_file_operation_is_said_as_a_message() {
        let path = scratch("notepad-unreadable");
        let _ = std::fs::remove_file(&path);

        let mut editor = Editor::new();
        editor.click("Open");
        let (request, ..) = editor.last_dialog();
        editor.choose_path(request, &path);
        editor.settle_until("Could not open");
        assert!(
            editor
                .messages
                .iter()
                .any(|(text, _)| text.starts_with("Could not open")),
            "the failure never reached a message: {:?}",
            editor.messages
        );
    }

    /// A file that is not there is a message on the status line, not a crash and not a
    /// silently emptied document.
    #[test]
    fn fr6_opening_a_missing_file_reports_why() {
        let path = scratch("notepad-absent");
        let _ = std::fs::remove_file(&path);

        let mut editor = Editor::new();
        editor.type_document("keep me");
        editor.click("Open");
        let (request, ..) = editor.last_dialog();
        editor.choose_path(request, &path);
        editor.settle_until("Could not open");

        assert_eq!(
            editor.document_text(),
            "keep me",
            "a failed open threw the document away"
        );
    }
}
