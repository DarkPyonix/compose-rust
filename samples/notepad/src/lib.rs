//! A working text editor, clone coded from the iOS and Windows memo applications.
//!
//! Written with compose-rust's own API: composables, state and the widget composables, no
//! Kotlin and no protocol types. Reading and writing files happens on a worker thread and
//! comes back as a state write, so the UI thread never waits on a disk.
//!
//! Several documents can be open at once. The one being written lives in the states the
//! editor reads, and the rest wait on a shelf, so choosing a document is a swap rather
//! than a different code path through the whole screen. The list of them stands beside the
//! page on a desktop window and arrives in a sheet on anything narrower.

use std::path::PathBuf;
use std::rc::Rc;

use compose_rust::runtime::*;
use compose_rust::ui::*;

mod document;

use document::{Counts, Outcome, counts, display_path, file_name};

/// The file the editor starts on, so the sample has something to open and save without a
/// file picker.
fn starting_path() -> PathBuf {
    std::env::temp_dir().join("compose-rust-notepad.txt")
}

/// A piece of file work, handed to a worker.
type Work = std::boxed::Box<dyn FnOnce() -> Outcome + Send + 'static>;

/// The editor field.
///
/// The field is uncontrolled: what the user types stays in the Renderer, and the Host only
/// hears about it. Opening a file is the one time the contents come from outside, so the
/// field is keyed by `stamp`, which changes only then. A new key is a new field, and the
/// field takes the contents it was built with. Ordinary typing leaves `stamp` alone, so the
/// caret and the IME composition are never disturbed and nothing is written back.
#[composable]
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

/// A single-line field for the path, rebuilt under the same stamp as the editor so a
/// document taken off the shelf brings its own path with it.
///
/// It takes the weight it is given, so the path grows with the sheet instead of being
/// sized by whatever happens to be typed in it.
#[composable]
fn path_field(stamp: u64, contents: String, on_edit: Rc<dyn Fn(String)>) {
    key(stamp, || {
        let initial = remember(|| contents.clone());
        TextField()
            .text(initial)
            .modifier(Modifier.weight(1.0))
            // A path is a machine string, so it is set in the monospace rung: the
            // separators line up and it stops competing with the document's own text.
            .type_role(TypeRole::Mono)
            .placeholder("Path to a file")
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
#[composable]
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
        // Reading and writing happen on a worker, so the window stays live while they
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
    path: String,
    text: String,
    on_disk: String,
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
#[composable]
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
#[composable]
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
#[composable]
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
    let text = remember(|| mutable_state_of(String::new()));
    let stamp = remember(|| mutable_state_of(0_u64));
    let path = remember(|| mutable_state_of(display_path(&starting_path())));
    let status = remember(|| mutable_state_of("Ready".to_owned()));
    let busy = remember(|| mutable_state_of(false));
    // What was last read from or written to disk. The document is unsaved exactly when it
    // has drifted from this, which is a comparison rather than a flag: a flag has to be
    // cleared in every place that saves, and the one that forgets is the bug.
    let on_disk = remember(|| mutable_state_of(String::new()));
    let file_open = remember(|| mutable_state_of(false));
    // The documents that are open but not on screen, and which one is.
    let shelved =
        remember(|| mutable_state_of_with_policy(Vec::<Shelved>::new(), never_equal_policy()));
    let current = remember(|| mutable_state_of(1_u64));
    let next_document = remember(|| mutable_state_of(2_u64));
    let list_open = remember(|| mutable_state_of(false));
    let format_open = remember(|| mutable_state_of(false));
    let document_type = remember(|| mutable_state_of(TypeRole::Body));
    let scope = remember_coroutine_scope();
    // A desktop window has room for the list to stand beside the document. Narrower than
    // that it is a sheet, which is the same list arriving from an edge instead.
    let list_beside = window.is_expanded();
    let crowded = window.is_compact();

    // The worker's result comes back here, on the UI thread, and the state writes happen
    // where every other state write in the app happens.
    let settle: Rc<dyn Fn(Outcome)> = {
        let (busy, status, on_disk, text, path, stamp) = (busy, status, on_disk, text, path, stamp);
        Rc::new(move |outcome: Outcome| {
            busy.set(false);
            match outcome {
                Outcome::Opened {
                    path: opened,
                    text: contents,
                } => {
                    status.set(format!(
                        "Opened {} ({} characters)",
                        display_path(&opened),
                        contents.chars().count()
                    ));
                    on_disk.set(contents.clone());
                    text.set(contents);
                    path.set(display_path(&opened));
                    // The field's contents came from outside it, so the field is rebuilt.
                    stamp.update(|stamp| *stamp += 1);
                }
                Outcome::Saved { path: saved, bytes } => {
                    status.set(format!("Saved {} ({bytes} bytes)", display_path(&saved)));
                    on_disk.set(text.get_untracked());
                }
                // A failure is worth saying out loud as well as writing down. The status
                // line is where you look afterwards; the message is what reaches someone
                // who was looking at the document when it happened.
                Outcome::Failed(message) => {
                    Message::new(message.clone())
                        .with_duration(MessageDuration::Long)
                        .show();
                    status.set(message);
                }
            }
        })
    };

    // One piece of file work: a worker thread does the blocking call and the awaiting task
    // applies the answer on the UI thread.
    let run_on_worker: Rc<dyn Fn(Work)> = {
        let (busy, scope, settle) = (busy, scope.clone(), Rc::clone(&settle));
        Rc::new(move |work: Work| {
            busy.set(true);
            let settle = Rc::clone(&settle);
            scope.launch(async move {
                // A worker that panics never answers, which would leave the bar saying the
                // work is still running forever. The panic is caught on the worker and
                // turned into the same failure every other one is.
                let outcome = with_worker(move || {
                    std::panic::catch_unwind(std::panic::AssertUnwindSafe(work)).unwrap_or_else(
                        |_| {
                            Outcome::Failed("The file worker stopped before it answered".to_owned())
                        },
                    )
                })
                .await;
                settle(outcome);
            });
        })
    };

    // Putting the document on screen on the shelf and taking another one off it. The
    // editor's states are the open document, so choosing one is a swap rather than a
    // different code path through the whole screen.
    let choose: Rc<dyn Fn(u64)> = {
        let (current, list_open, shelved, path, text, on_disk, status, stamp) = (
            current, list_open, shelved, path, text, on_disk, status, stamp,
        );
        Rc::new(move |id: u64| {
            if id == current.get_untracked() {
                list_open.set(false);
                return;
            }
            let Some(at) = shelved.with(|list| list.iter().position(|document| document.id == id))
            else {
                return;
            };
            let on_screen = Shelved {
                id: current.get_untracked(),
                path: path.get_untracked(),
                text: text.get_untracked(),
                on_disk: on_disk.get_untracked(),
            };
            let mut taken = Shelved::default();
            shelved.update(|list| {
                taken = list.remove(at);
                list.push(on_screen);
            });
            current.set(taken.id);
            status.set(format!("Opened {}", file_name(&taken.path)));
            path.set(taken.path);
            on_disk.set(taken.on_disk);
            text.set(taken.text);
            // The field's contents came from outside it, so the field is rebuilt.
            stamp.update(|stamp| *stamp += 1);
            list_open.set(false);
        })
    };

    let new_document: Rc<dyn Fn()> = {
        let (shelved, current, path, text, on_disk, next_document, status, stamp, list_open) = (
            shelved,
            current,
            path,
            text,
            on_disk,
            next_document,
            status,
            stamp,
            list_open,
        );
        Rc::new(move || {
            let on_screen = Shelved {
                id: current.get_untracked(),
                path: path.get_untracked(),
                text: text.get_untracked(),
                on_disk: on_disk.get_untracked(),
            };
            shelved.update(|list| list.push(on_screen));
            let id = next_document.get_untracked();
            next_document.set(id + 1);
            current.set(id);
            path.set(String::new());
            text.set(String::new());
            on_disk.set(String::new());
            status.set("New document".to_owned());
            stamp.update(|stamp| *stamp += 1);
            list_open.set(false);
        })
    };

    // Every open document, in the order they were opened, with the one on screen in its
    // own place rather than at the end: the list is not allowed to jump about because the
    // reader moved between two documents.
    let current_id = current.get();
    let current_path = path.get();
    let mut entries: Vec<(u64, String)> = shelved.with(|list| {
        list.iter()
            .map(|document| (document.id, file_name(&document.path)))
            .collect()
    });
    entries.push((current_id, file_name(&current_path)));
    entries.sort_by_key(|(id, _)| *id);

    let contents = text.get();
    let Counts {
        words,
        characters,
        lines,
    } = counts(&contents);
    let working = busy.get();
    let edited = contents != on_disk.get();
    let generation = stamp.get();
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
                let (run, path, text) = (Rc::clone(&run_on_worker), path, text);
                Button("Save")
                    .variant(ButtonVariant::Filled)
                    .enabled(!working)
                    .on_click(move || {
                        let target = PathBuf::from(path.get_untracked());
                        let contents = text.get_untracked();
                        run(std::boxed::Box::new(move || {
                            document::save(target, contents)
                        }));
                    });
            });
        })
        .content(|| {
            // Which file the document is, and the two things that can be done with it.
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
                            let typed = path;
                            path_field(
                                generation,
                                current_path.clone(),
                                Rc::new(move |value| typed.set(value)),
                            );
                            Row()
                                .modifier(Modifier.fill_max_width())
                                .space_role(SpaceRole::Sm)
                                .alignment(Alignment::CenterStart)
                                .content(|| {
                                    Text(
                                        "The document is saved to this path, and Open reads \
                                         it back.",
                                    )
                                    .type_role(TypeRole::Caption)
                                    .color(Paint::Role(ColorRole::OnSurfaceVariant))
                                    .modifier(Modifier.weight(1.0));
                                    let (close, run, path) =
                                        (file_open, Rc::clone(&run_on_worker), path);
                                    Button("Open")
                                        .variant(ButtonVariant::Tonal)
                                        .enabled(!working)
                                        .on_click(move || {
                                            close.set(false);
                                            let target = PathBuf::from(path.get_untracked());
                                            run(std::boxed::Box::new(move || {
                                                document::open(target)
                                            }));
                                        });
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
                                    Text(file_name(&current_path))
                                        .modifier(Modifier.fill_max_width())
                                        .type_role(TypeRole::Headline)
                                        .max_lines(1)
                                        .overflow(TextOverflow::Ellipsis);

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
                                            let typed = text;
                                            editor(
                                                generation,
                                                contents.clone(),
                                                style,
                                                Rc::new(move |value| typed.set(value)),
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
    use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind};
    use std::collections::HashMap;

    /// Hangul, a combining mark and an emoji: three ways a byte count and a character count
    /// come apart, all of which have to survive the trip to disk and back.
    const KOREAN: &str = "안녕하세요\n오늘도 좋은 하루 보내세요 \u{1f44b}";

    const DOCUMENT_PLACEHOLDER: &str = "Type here, or open a file";
    const PATH_PLACEHOLDER: &str = "Path to a file";

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

    /// The real editor, driven the way the Renderer drives it: every batch is applied to a
    /// model of the Renderer's node table, so a node that has left the screen is gone.
    struct Editor {
        host: ComposeHost,
        nodes: HashMap<u32, Node>,
        /// Every message the screen has said, in order, with its action label.
        messages: Vec<(String, String)>,
    }

    impl Editor {
        fn new() -> Self {
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
            };
            editor.absorb(&first);
            assert_eq!(
                editor.of(WidgetKind::TextField).len(),
                2,
                "the screen should have a path field and a document field"
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

        /// The field with this placeholder, which is how the two fields are told apart:
        /// both are rebuilt with new ids whenever a document's contents come from outside.
        fn field(&self, placeholder: &str) -> u32 {
            self.of(WidgetKind::TextField)
                .into_iter()
                .find(|id| self.nodes[id].placeholder.as_deref() == Some(placeholder))
                .unwrap_or_else(|| panic!("the screen has no field for {placeholder}"))
        }

        fn document_field(&self) -> u32 {
            self.field(DOCUMENT_PLACEHOLDER)
        }

        /// Typing into a field. The field keeps what was typed, as a real one does, and
        /// the Host is only told.
        fn type_into(&mut self, placeholder: &str, text: &str) {
            let node_id = self.field(placeholder);
            let handler = self.nodes[&node_id]
                .on_value_change
                .expect("the field declared no change handler");
            self.nodes.get_mut(&node_id).unwrap().text = Some(text.to_owned());
            self.dispatch(node_id, handler, EventPayload::TextChanged(text));
        }

        fn type_document(&mut self, text: &str) {
            self.type_into(DOCUMENT_PLACEHOLDER, text);
        }

        /// Setting the path the Open and Save buttons work on.
        fn set_path(&mut self, path: &str) {
            self.type_into(PATH_PLACEHOLDER, path);
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

        /// File work happens on a worker and lands on the UI thread in a later frame, so
        /// the test polls frames the way the Renderer would.
        fn settle_until(&mut self, contains: &str) {
            for frame in 0..400 {
                std::thread::sleep(std::time::Duration::from_millis(5));
                let batch = self
                    .host
                    .render_frame(frame)
                    .expect("a frame failed to encode")
                    .to_vec();
                self.absorb(&batch);
                if self.status().contains(contains) {
                    return;
                }
            }
            panic!(
                "the screen never reported {contains}; the status line says {}",
                self.status()
            );
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
                    text.starts_with("Opened ")
                        || text.starts_with("Saved ")
                        || text.starts_with("Could not ")
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
        sample_frames::record("Notepad", app, |_| {});
    }

    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        compose_rust::window::reset_window_size();
        assert!(ComposeHost::new(app).rebuild().is_ok());
    }

    /// Editing, saving, clearing and reopening, all through the screen. Korean text has to
    /// come back exactly as it went in: the file is bytes, the field is characters, and
    /// every step between them is somewhere the two can be confused.
    #[test]
    fn fr6_a_document_round_trips_through_editing_and_saving_with_korean_intact() {
        let path = scratch("notepad-round-trip");
        let _ = std::fs::remove_file(&path);

        let mut editor = Editor::new();
        editor.set_path(&display_path(&path));
        editor.type_document(KOREAN);
        assert_eq!(editor.document_text(), KOREAN);

        editor.click("Save");
        editor.settle_until("Saved ");
        assert_eq!(
            std::fs::read_to_string(&path).expect("the file was not written"),
            KOREAN
        );

        // Start a different document, so reopening has to put the text back rather than
        // leave it. The saved one is still open behind this one, which is why the path has
        // to be named again.
        editor.click("New");
        assert_eq!(editor.document_text(), "");

        editor.set_path(&display_path(&path));
        editor.click("Open");
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
    /// references put it, and it follows the file the document is saved to.
    #[test]
    fn fr22_the_page_opens_with_the_document_name() {
        let mut editor = Editor::new();
        let starting = file_name(&display_path(&starting_path()));
        assert!(
            editor.texts().any(|text| text == starting),
            "the page does not name the document it is showing"
        );

        editor.set_path("/tmp/a-different-note.txt");
        assert!(
            editor.texts().any(|text| text == "a-different-note.txt"),
            "the name did not follow the file the document is saved to"
        );
    }

    /// Where the bytes live is something you go and ask for, not a strip across the top of
    /// every document. The two things that act on the file are in the sheet with it.
    #[test]
    fn fr22_the_file_controls_are_behind_a_sheet() {
        let editor = Editor::new();
        let path_field = editor.field(PATH_PLACEHOLDER);
        let sheet = editor
            .ancestors(path_field)
            .into_iter()
            .find(|node| editor.nodes[node].widget == Some(WidgetKind::Sheet))
            .expect("the path field is not in a sheet");
        let (open, _) = editor.button("Open");
        assert!(
            editor.ancestors(open).contains(&sheet),
            "Open is not in the sheet the path field is in"
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
        editor.set_path("/tmp/first-note.txt");
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

    /// A document that has drifted from what is on disk says so, and stops saying so once
    /// it has been saved.
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

        editor.set_path(&display_path(&path));
        editor.click("Save");
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
        editor.set_path(&display_path(&path));
        editor.click("Open");
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

    /// A path that is not there is a message on the status line, not a crash and not a
    /// silently emptied document.
    #[test]
    fn fr6_opening_a_missing_file_reports_why() {
        let path = scratch("notepad-absent");
        let _ = std::fs::remove_file(&path);

        let mut editor = Editor::new();
        editor.type_document("keep me");
        editor.set_path(&display_path(&path));
        editor.click("Open");
        editor.settle_until("Could not open");

        assert_eq!(
            editor.document_text(),
            "keep me",
            "a failed open threw the document away"
        );
    }
}
