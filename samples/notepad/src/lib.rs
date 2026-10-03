//! A working text editor, clone coded from the iOS and Windows memo applications.
//!
//! Written the way an application using this library would be written: `rsx!` and hooks,
//! no Kotlin and no protocol types. Reading and writing files happens on a worker thread
//! and comes back as a signal update, so the UI thread never waits on a disk.
//!
//! Several documents can be open at once. The one being written lives in the signals the
//! editor reads, and the rest wait on a shelf, so choosing a document is a swap rather
//! than a different code path through the whole screen. The list of them stands beside the
//! page on a desktop window and arrives in a sheet on anything narrower.

use std::path::PathBuf;

use compose_rust::prelude::*;

mod document;

use document::{Counts, Outcome, counts, display_path, file_name};

/// The file the editor starts on, so the sample has something to open and save without a
/// file picker.
fn starting_path() -> PathBuf {
    std::env::temp_dir().join("dioxus-compose-notepad.txt")
}

/// The editor field.
///
/// A plain function rather than a `#[component]`, because `#[component]` expands to a
/// `#[derive(Props)]` and the derive macro is not exported to applications.
///
/// It is the raw `textfield` element rather than the `TextField` component, and the
/// contents arrive through a quoted attribute name, because neither the component nor the
/// declared attribute list offers a way to put text into a field. Opening a file has to.
///
/// `stamp` changes only when the text is replaced from outside the field, and the change of
/// key rebuilds the field so it picks the new contents up. Ordinary typing leaves `stamp`
/// alone, so the caret and the IME composition are never disturbed.
fn editor(
    stamp: u64,
    contents: String,
    type_role: TypeRole,
    on_edit: EventHandler<String>,
) -> Element {
    rsx! {
        for generation in [stamp] {
            textfield {
                key: "editor-{generation}",
                "text": "{contents}",
                multiline: true,
                fill_max_width: true,
                fill_max_height: true,
                type_role: i64::from(u16::from(type_role)),
                placeholder: "Type here, or open a file",
                onvaluechange: move |event: Event<String>| on_edit.call((*event.data()).clone()),
            }
        }
    }
}

/// A single-line field whose contents are set once, for the path.
///
/// It takes the weight the toolbar gives it, so the path grows with the window instead of
/// being sized by whatever happens to be typed in it.
fn path_field(contents: String, on_edit: EventHandler<String>) -> Element {
    rsx! {
        textfield {
            "text": "{contents}",
            weight: 1.0,
            // A path is a machine string, so it is set in the monospace rung: the
            // separators line up and it stops competing with the document's own text.
            type_role: i64::from(u16::from(TypeRole::Mono)),
            placeholder: "Path to a file",
            onvaluechange: move |event: Event<String>| on_edit.call((*event.data()).clone()),
        }
    }
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
fn document_bar(measure: Option<f32>, working: bool, children: Element) -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            TopAppBar {
                fill_max_width: true,
                compose_rust::Box {
                    weight: 1.0,
                    alignment: Alignment::Center,
                    Row {
                        width: measure,
                        fill_max_width: measure.is_none(),
                        padding_role: measure.map(|_| SpaceRole::Md),
                        space_role: SpaceRole::Sm,
                        alignment: Alignment::CenterStart,
                        {children}
                    }
                }
            }
            // Reading and writing happen on a worker, so the window stays live while they
            // run and there has to be something that says they are running. Indeterminate,
            // because a file system does not report how far through a read it is.
            // The bar spans its container without being told to: how wide a rule or a
            // progress track is on the axis it runs along is the design system's.
            if working {
                ProgressIndicator { determinate: false }
            }
        }
    }
}

/// A document that is open but not on screen.
///
/// The one being edited lives in the signals the editor writes to; this is where the
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
fn document_list(
    entries: Vec<(u64, String)>,
    current: u64,
    choose: EventHandler<u64>,
    new_document: EventHandler<()>,
) -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            space_role: SpaceRole::Sm,
            Row {
                fill_max_width: true,
                alignment: Alignment::CenterStart,
                Text { text: "Documents", type_role: TypeRole::Subtitle, weight: 1.0 }
                Button {
                    text: "New",
                    variant: ButtonVariant::Tonal,
                    on_click: move |_| new_document.call(()),
                }
            }
            Separator {}
            ScrollColumn {
                fill_max_width: true,
                weight: 1.0,
                for (id , name) in entries {
                    Button {
                        key: "{id}",
                        text: name,
                        fill_max_width: true,
                        // The one being edited is the filled one. A list where every row
                        // looks the same is a list that does not say where you are.
                        variant: if id == current {
                            ButtonVariant::Tonal
                        } else {
                            ButtonVariant::Text
                        },
                        color: Paint::Role(ColorRole::OnSurface),
                        on_click: move |_| choose.call(id),
                    }
                }
            }
        }
    }
}

pub fn app() -> Element {
    let window = use_window_size();
    // A document is read across its lines, so past a certain width a page that keeps
    // growing is a page nobody can read. On a desktop window the page stops at the width
    // an expanded window starts at and takes a margin either side, which is what a page
    // is. Narrower than that the page is the window, because there is nothing to spare.
    let page_width = if window.is_expanded() {
        Some(WindowSizeClass::EXPANDED_MIN_WIDTH_DP)
    } else {
        None
    };
    let mut text = use_signal(String::new);
    let mut stamp = use_signal(|| 0_u64);
    let mut path = use_signal(|| display_path(&starting_path()));
    let mut status = use_signal(|| "Ready".to_owned());
    let mut busy = use_signal(|| false);
    // What was last read from or written to disk. The document is unsaved exactly when it
    // has drifted from this, which is a comparison rather than a flag: a flag has to be
    // cleared in every place that saves, and the one that forgets is the bug.
    let mut on_disk = use_signal(String::new);
    let mut file_open = use_signal(|| false);
    // The documents that are open but not on screen, and which one is.
    let mut shelved = use_signal(Vec::<Shelved>::new);
    let mut current = use_signal(|| 1_u64);
    let mut next_document = use_signal(|| 2_u64);
    let mut list_open = use_signal(|| false);
    let mut format_open = use_signal(|| false);
    let mut document_type = use_signal(|| TypeRole::Body);
    // A desktop window has room for the list to stand beside the document. Narrower than
    // that it is a sheet, which is the same list arriving from an edge instead.
    let list_beside = window.is_expanded();
    let crowded = window.is_compact();

    // The worker's result comes back here, on the UI thread, and the signal writes happen
    // where every other signal write in the app happens.
    let mut settle = move |outcome: Outcome| {
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
                stamp += 1;
            }
            Outcome::Saved { path: saved, bytes } => {
                status.set(format!("Saved {} ({bytes} bytes)", display_path(&saved)));
                on_disk.set(text());
            }
            // A failure is worth saying out loud as well as writing down. The status line
            // is where you look afterwards; the message is what reaches someone who was
            // looking at the document when it happened.
            Outcome::Failed(message) => {
                Message::new(message.clone())
                    .with_duration(MessageDuration::Long)
                    .show();
                status.set(message);
            }
        }
    };

    // One piece of file work: a real thread does the blocking call, a one-shot channel
    // carries the answer back, and the awaiting task applies it on the UI thread.
    let mut run_on_worker = move |work: Box<dyn FnOnce() -> Outcome + Send + 'static>| {
        busy.set(true);
        let (sender, receiver) = futures_channel::oneshot::channel();
        std::thread::spawn(move || {
            let _ = sender.send(work());
        });
        dioxus_core::spawn(async move {
            match receiver.await {
                Ok(outcome) => settle(outcome),
                Err(_) => settle(Outcome::Failed(
                    "The file worker stopped before it answered".to_owned(),
                )),
            }
        });
    };

    // Putting the document on screen on the shelf and taking another one off it. The
    // editor's signals are the open document, so choosing one is a swap rather than a
    // different code path through the whole screen.
    let choose = move |id: u64| {
        if id == current() {
            list_open.set(false);
            return;
        }
        let taken = {
            let mut list = shelved.write();
            let Some(at) = list.iter().position(|document| document.id == id) else {
                return;
            };
            let taken = list.remove(at);
            list.push(Shelved {
                id: current(),
                path: path(),
                text: text(),
                on_disk: on_disk(),
            });
            taken
        };
        current.set(taken.id);
        path.set(taken.path);
        on_disk.set(taken.on_disk);
        text.set(taken.text);
        status.set(format!("Opened {}", file_name(&path())));
        // The field's contents came from outside it, so the field is rebuilt.
        stamp += 1;
        list_open.set(false);
    };

    let mut new_document = move |()| {
        shelved.write().push(Shelved {
            id: current(),
            path: path(),
            text: text(),
            on_disk: on_disk(),
        });
        let id = next_document();
        next_document.set(id + 1);
        current.set(id);
        path.set(String::new());
        text.set(String::new());
        on_disk.set(String::new());
        status.set("New document".to_owned());
        stamp += 1;
        list_open.set(false);
    };

    // Every open document, in the order they were opened, with the one on screen in its
    // own place rather than at the end: the list is not allowed to jump about because the
    // reader moved between two documents.
    let mut entries: Vec<(u64, String)> = shelved
        .read()
        .iter()
        .map(|document| (document.id, file_name(&document.path)))
        .collect();
    entries.push((current(), file_name(&path())));
    entries.sort_by_key(|(id, _)| *id);

    let Counts {
        words,
        characters,
        lines,
    } = counts(&text());
    let working = busy();
    let edited = text() != on_disk();

    rsx! {
        // The frame is named rather than built. Whether this bar is the window's own
        // caption or a bar beneath the system's, and where the page starts under it, are
        // answers this screen never has to know.
        Scaffold {
            top_bar: rsx! {
                {document_bar(page_width, working, rsx! {
                    // The application's name, on the windows with room for it. On a phone the
                    // four actions need the whole bar, and the page already says what document
                    // this is, which is the thing a title is for. Left in, the name was
                    // squeezed to one letter per line.
                    if crowded {
                        compose_rust::Box { weight: 1.0 }
                    } else {
                        Text {
                            text: "Notepad",
                            type_role: TypeRole::Title,
                            weight: 1.0,
                            max_lines: 1,
                            overflow: TextOverflow::Ellipsis,
                        }
                    }
                    // Whether there is anything to lose, said where the actions that could
                    // lose it are. Not the error colour: unsaved work is an ordinary state of
                    // a document being written, not a fault.
                    if edited {
                        Text {
                            text: "Edited",
                            type_role: TypeRole::Label,
                            color: Paint::Role(ColorRole::OnSurfaceVariant),
                        }
                    }
                    // Only where the list is not already on screen. A button that opens what
                    // you are looking at is a button that does nothing.
                    if !list_beside {
                        Button {
                            text: "Documents",
                            variant: ButtonVariant::Text,
                            on_click: move |_| list_open.set(true),
                        }
                    }
                    // Starting a document no longer throws one away: the one that was on
                    // screen is in the list, which is why this offers nothing back.
                    Button {
                        text: "New",
                        variant: ButtonVariant::Text,
                        enabled: !working,
                        on_click: move |_| new_document(()),
                    }
                    Button {
                        text: "File",
                        variant: ButtonVariant::Tonal,
                        on_click: move |_| file_open.set(true),
                    }
                    Button {
                        text: "Format",
                        variant: ButtonVariant::Text,
                        on_click: move |_| format_open.set(true),
                    }
                    Button {
                        text: "Save",
                        variant: ButtonVariant::Filled,
                        enabled: !working,
                        on_click: move |_| {
                            let target = PathBuf::from(path());
                            let contents = text();
                            run_on_worker(Box::new(move || document::save(target, contents)));
                        },
                    }
                })}
            },

            // The document's actions belong in the bar, not in a line of buttons above the
            // text.


            // Which file the document is, and the two things that can be done with it.
            //
            // It used to be a strip above the page, carrying a path in monospace across
            // the top of a document. Neither memo reference has one: a note is a page with
            // a name on it, and where the bytes live is something you go and ask for. A
            // sheet is where you ask. It is declared here rather than at the foot of the
            // screen because it belongs to the button in the bar above, and a sheet is
            // drawn over the page wherever it is declared.
            Sheet {
                open: file_open(),
                on_dismiss: move |_| file_open.set(false),
                fill_max_width: true,
                Column {
                    fill_max_width: true,
                    space_role: SpaceRole::Md,
                    Row {
                        fill_max_width: true,
                        alignment: Alignment::CenterStart,
                        Text { text: "File", type_role: TypeRole::Subtitle, weight: 1.0 }
                        Button {
                            text: "Close",
                            variant: ButtonVariant::Filled,
                            on_click: move |_| file_open.set(false),
                        }
                    }
                    Separator {}
                    {path_field(path(), EventHandler::new(move |value| path.set(value)))}
                    Row {
                        fill_max_width: true,
                        space_role: SpaceRole::Sm,
                        alignment: Alignment::CenterStart,
                        Text {
                            text: "The document is saved to this path, and Open reads it \
                                   back.",
                            type_role: TypeRole::Caption,
                            color: Paint::Role(ColorRole::OnSurfaceVariant),
                            weight: 1.0,
                        }
                        Button {
                            text: "Open",
                            variant: ButtonVariant::Tonal,
                            enabled: !working,
                            on_click: move |_| {
                                file_open.set(false);
                                let target = PathBuf::from(path());
                                run_on_worker(Box::new(move || document::open(target)));
                            },
                        }
                    }
                }
            }

            // The iOS reference keeps document formatting in a sheet rather than adding
            // another permanent strip around the page. These choices affect the editor's
            // type role directly, so every design system supplies its own face, size and
            // weight for the selected document style.
            Sheet {
                open: format_open(),
                on_dismiss: move |_| format_open.set(false),
                fill_max_width: true,
                Column {
                    fill_max_width: true,
                    space_role: SpaceRole::Md,
                    Row {
                        fill_max_width: true,
                        alignment: Alignment::CenterStart,
                        Text { text: "Formatting", type_role: TypeRole::Subtitle, weight: 1.0 }
                        Button {
                            text: "Done",
                            variant: ButtonVariant::Filled,
                            on_click: move |_| format_open.set(false),
                        }
                    }
                    Separator {}
                    Text {
                        text: "Document style",
                        type_role: TypeRole::Label,
                        color: Paint::Role(ColorRole::OnSurfaceVariant),
                    }
                    Row {
                        fill_max_width: true,
                        space_role: SpaceRole::Sm,
                        Button {
                            text: "Heading",
                            weight: 1.0,
                            variant: if document_type() == TypeRole::Headline {
                                ButtonVariant::Tonal
                            } else {
                                ButtonVariant::Text
                            },
                            on_click: move |_| document_type.set(TypeRole::Headline),
                        }
                        Button {
                            text: "Body",
                            weight: 1.0,
                            variant: if document_type() == TypeRole::Body {
                                ButtonVariant::Tonal
                            } else {
                                ButtonVariant::Text
                            },
                            on_click: move |_| document_type.set(TypeRole::Body),
                        }
                        Button {
                            text: "Monospaced",
                            weight: 1.0,
                            variant: if document_type() == TypeRole::Mono {
                                ButtonVariant::Tonal
                            } else {
                                ButtonVariant::Text
                            },
                            on_click: move |_| document_type.set(TypeRole::Mono),
                        }
                    }
                    Surface {
                        fill_max_width: true,
                        shape_role: ShapeRole::Large,
                        padding_role: SpaceRole::Md,
                        Text {
                            text: "The quick brown fox jumps over the lazy dog.",
                            type_role: document_type(),
                        }
                    }
                }
            }

            // The same list, arriving from an edge, for the windows with no room beside
            // the document. Which edge is the Renderer's decision.
            Sheet {
                open: list_open() && !list_beside,
                on_dismiss: move |_| list_open.set(false),
                fill_max_width: true,
                {document_list(
                    entries.clone(),
                    current(),
                    EventHandler::new(choose),
                    EventHandler::new(move |()| new_document(())),
                )}
            }

            // The list and the document, side by side where there is room. Both memo
            // references do this on a wide window: Loop keeps its documents down the left
            // and iOS opens the note over the list it came from.
            Row {
                fill_max_width: true,
                fill_max_height: true,
                if list_beside {
                    Column {
                        weight: LIST_SHARE,
                        fill_max_height: true,
                        padding_role: SpaceRole::Md,
                        {document_list(
                            entries.clone(),
                            current(),
                            EventHandler::new(choose),
                            EventHandler::new(move |()| new_document(())),
                        )}
                    }
                    Divider { vertical: true }
                }

            // The page defines a column, and everything under the bar lines up with it.
            //
            // The page alone used to be centred and measured while the file strip and the
            // status line ran to the window edges, so the three things a document window
            // is made of shared no edge at all. A page is a measure: the chrome that
            // belongs to the document sits on the same measure or the document is not a
            // page, it is a rectangle floating between two full width strips.
            compose_rust::Box {
                weight: DOCUMENT_SHARE,
                fill_max_width: true,
                fill_max_height: true,
                alignment: Alignment::TopCenter,
                Column {
                    fill_max_width: page_width.is_none(),
                    width: page_width,
                    fill_max_height: true,
                    // The medium step, because that is what the bar insets its own
                    // contents by. Anything else and the title and the page below it start
                    // at two different places.
                    padding_role: SpaceRole::Md,
                    space_role: SpaceRole::Md,

                    // The document's name, which is the first thing on the page in both
                    // memo references. A document is a thing with a name, and a page that
                    // opens straight into body text is a page you cannot tell from the
                    // one beside it.
                    Text {
                        text: file_name(&path()),
                        fill_max_width: true,
                        type_role: TypeRole::Headline,
                        max_lines: 1,
                        overflow: TextOverflow::Ellipsis,
                    }

                    // The page: the field itself, taking everything the title above it
                    // and the status line below it leave.
                    //
                    // It used to be a field inside a scrolling column inside a surface.
                    // Height cannot reach a field through a scrolling column, because what
                    // a scroll offers its content is unbounded, so the editor came out one
                    // line tall at the top of a page-sized panel: a text editor you could
                    // not see your document in. The field scrolls itself once it is taller
                    // than the window, which is the behaviour the column was there for.
                    compose_rust::Box {
                        fill_max_width: true,
                        weight: 1.0,
                        {editor(
                            stamp(),
                            text(),
                            document_type(),
                            EventHandler::new(move |value| text.set(value)),
                        )}
                    }

                    // The status line is not part of the page, so a rule separates them.
                    // Its thickness and colour are the design system's.
                    Separator {}

                    // The status line: what the last file operation did on the left, the
                    // document's measurements on the right.
                    Row {
                        fill_max_width: true,
                        space_role: SpaceRole::Md,
                        alignment: Alignment::CenterStart,
                        Text {
                            text: status(),
                            weight: 1.0,
                            type_role: TypeRole::Caption,
                            color: Paint::Role(ColorRole::OnSurfaceVariant),
                            max_lines: 1,
                            overflow: TextOverflow::Ellipsis,
                        }
                        Text {
                            text: "{words} words",
                            type_role: TypeRole::Caption,
                            color: Paint::Role(ColorRole::OnSurfaceVariant),
                        }
                        Text {
                            text: "{characters} characters",
                            type_role: TypeRole::Caption,
                            color: Paint::Role(ColorRole::OnSurfaceVariant),
                        }
                        Text {
                            text: "{lines} lines",
                            type_role: TypeRole::Caption,
                            color: Paint::Role(ColorRole::OnSurfaceVariant),
                        }
                    }
                }
            }
            }

        }
    }
}

// Samples are demonstrations, so they let you see any of the design systems rather than
// only the one this machine happens to select. Unset, the app adapts to the host platform,
// which is what a real application wants.
/// Runs the sample as a program of its own. The desktop binary is one line of this.
pub fn launch() {
    launch_builder().launch(app);
}

/// How this sample is configured, in one place because three entry points need it.
///
/// The theme above all: a sample that names one and then reaches a platform through an
/// entry point that makes its own builder is a sample that draws the same screens in a
/// different design system depending on where it runs.
fn launch_builder() -> compose_rust::LaunchBuilder {
    // The name the window carries. A desktop lists windows by it, so a window that said
    // nothing was listed under whatever the renderer happened to be called, and every
    // sample here was listed as DioxusCompose until this line existed.
    compose_rust::LaunchBuilder::new()
        .with_theme(compose_rust::demo_theme())
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Notepad")
                // Without one the window wears the toolkit's picture, which on
                // Windows is the Java coffee cup, wherever the system lists
                // windows. The bytes travel as an asset and the renderer refers
                // to them by id: a path would be a fact about the machine this
                // was built on, and a name would ask the toolkit to find
                // something it may not have.
                .with_icon(compose_rust::asset::asset(
                    compose_rust::schema::AssetKind::Png,
                    include_bytes!("../assets/icon.png"),
                )),
        )
}

// The platforms where the sample is not a program. Android's Activity and the browser's
// page both own the loop, so neither has a `main` to call: each names an entry point that
// registers the root component, and these macros define it.
//
// Both are declared unconditionally. Each macro compiles into nothing that runs off its
// own platform, and gating them here instead would mean a desktop build never checks that
// this sample can still be built for the other two.
compose_rust::android_main!({ launch_builder() }, app);
compose_rust::web_main!({ launch_builder() }, app);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;
    use compose_rust::Host;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
    use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind};
    use std::collections::HashMap;

    /// Hangul, a combining mark and an emoji: three ways a byte count and a character count
    /// come apart, all of which have to survive the trip to disk and back.
    const KOREAN: &str = "안녕하세요\n오늘도 좋은 하루 보내세요 \u{1f44b}";

    /// The real editor, driven the way the Renderer drives it.
    struct Editor {
        host: Host,
        /// The path field and the document field, in the order the screen declares them.
        fields: Vec<u32>,
        /// Node to the handler its `onvaluechange` was given.
        changes: HashMap<u32, u64>,
        type_roles: HashMap<u32, i64>,
        /// Button label to its node and click handler.
        buttons: HashMap<String, (u32, u64)>,
        texts: HashMap<u32, String>,
        /// Node to the node it was inserted under, so a removal takes the subtree with it.
        parents: HashMap<u32, u32>,
        /// What each node was created as, so a test can ask where something sits.
        widgets: HashMap<u32, WidgetKind>,
        /// Every message the screen has said, in order, with its action label.
        messages: Vec<(String, String)>,
        event: Vec<u8>,
    }

    impl Editor {
        fn new() -> Self {
            let mut editor = Self {
                host: Host::new(app),
                fields: Vec::new(),
                changes: HashMap::new(),
                type_roles: HashMap::new(),
                buttons: HashMap::new(),
                texts: HashMap::new(),
                parents: HashMap::new(),
                widgets: HashMap::new(),
                messages: Vec::new(),
                event: Vec::new(),
            };
            let batch = editor
                .host
                .rebuild()
                .expect("the first frame failed to encode");
            let decoded = decode_batch(batch).expect("the first frame did not decode");
            let mut fields = Vec::new();
            let mut clicks = HashMap::new();
            let mut changes = HashMap::new();
            let mut texts = HashMap::new();
            let mut widgets = HashMap::new();
            let mut parents = HashMap::new();
            for mutation in &decoded {
                match mutation {
                    Mutation::Insert {
                        parent_id, node_id, ..
                    } => {
                        parents.insert(*node_id, *parent_id);
                    }
                    Mutation::Create { node_id, widget } => {
                        widgets.insert(*node_id, *widget);
                        if *widget == WidgetKind::TextField {
                            fields.push(*node_id);
                        }
                    }
                    Mutation::SetProp {
                        node_id,
                        property,
                        value,
                    } => match (property, value) {
                        (PropertyKind::Text, PropertyValue::String(text)) => {
                            texts.insert(*node_id, (*text).to_owned());
                        }
                        (PropertyKind::OnClick, PropertyValue::Integer(id)) => {
                            clicks.insert(*node_id, *id as u64);
                        }
                        (PropertyKind::OnValueChange, PropertyValue::Integer(id)) => {
                            changes.insert(*node_id, *id as u64);
                        }
                        (PropertyKind::TypeRole, PropertyValue::Integer(role)) => {
                            editor.type_roles.insert(*node_id, *role);
                        }
                        _ => {}
                    },
                    _ => {}
                }
            }
            drop(decoded);
            editor.buttons = clicks
                .iter()
                .filter_map(|(node_id, handler)| {
                    texts
                        .get(node_id)
                        .map(|label| (label.clone(), (*node_id, *handler)))
                })
                .collect();
            editor.fields = fields;
            editor.changes = changes;
            editor.texts = texts;
            editor.widgets = widgets;
            editor.parents = parents;
            assert_eq!(
                editor.fields.len(),
                2,
                "the screen should have a path field and a document field"
            );
            editor
        }

        /// Drops a node and everything under it, the way the Renderer's node table does.
        /// Without it a label that has been taken off the screen is still there as far as
        /// this harness can tell.
        fn forget(&mut self, node_id: u32) {
            let children: Vec<u32> = self
                .parents
                .iter()
                .filter(|(_, parent)| **parent == node_id)
                .map(|(child, _)| *child)
                .collect();
            for child in children {
                self.forget(child);
            }
            self.texts.remove(&node_id);
            self.buttons.retain(|_, (node, _)| *node != node_id);
            self.parents.remove(&node_id);
        }

        /// Whether a node hangs from another, which is how a test says "inside the sheet".
        fn descends_from(&self, node: u32, ancestor: u32) -> bool {
            self.ancestors(node).contains(&ancestor)
        }

        /// Everything a node hangs from, nearest first.
        fn ancestors(&self, node: u32) -> Vec<u32> {
            let mut walk = node;
            let mut chain = Vec::new();
            while let Some(parent) = self.parents.get(&walk) {
                chain.push(*parent);
                walk = *parent;
            }
            chain
        }

        fn absorb(&mut self, batch: &[u8]) {
            let mut clicks: Vec<(u32, u64)> = Vec::new();
            for mutation in decode_batch(batch).expect("a frame did not decode") {
                match mutation {
                    Mutation::Create { node_id, widget } => {
                        self.widgets.insert(node_id, widget);
                        // The document field is rebuilt under a new key whenever its
                        // contents come from outside it, so the node id changes.
                        if widget == WidgetKind::TextField && !self.fields.contains(&node_id) {
                            self.fields.push(node_id);
                        }
                    }
                    Mutation::Insert {
                        parent_id, node_id, ..
                    }
                    | Mutation::Move {
                        parent_id, node_id, ..
                    } => {
                        self.parents.insert(node_id, parent_id);
                    }
                    Mutation::Remove { node_id } => self.forget(node_id),
                    Mutation::ShowMessage { text, action, .. } => {
                        self.messages.push((text.to_owned(), action.to_owned()));
                    }
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::OnClick,
                        value: PropertyValue::Integer(id),
                    } => clicks.push((node_id, id as u64)),
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                    } => {
                        self.texts.insert(node_id, text.to_owned());
                    }
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::OnValueChange,
                        value: PropertyValue::Integer(id),
                    } => {
                        self.changes.insert(node_id, id as u64);
                    }
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::TypeRole,
                        value: PropertyValue::Integer(role),
                    } => {
                        self.type_roles.insert(node_id, role);
                    }
                    _ => {}
                }
            }
            // The label has to be known before a button can be recorded under it, and the
            // two arrive in the same batch in either order.
            for (node_id, handler) in clicks {
                if let Some(label) = self.texts.get(&node_id) {
                    self.buttons.insert(label.clone(), (node_id, handler));
                }
            }
        }

        fn dispatch(&mut self, node_id: u32, handler_id: u64, payload: EventPayload<'_>) {
            encode_event(
                &HostEvent {
                    node_id,
                    handler_id,
                    payload,
                },
                &mut self.event,
            )
            .expect("the event did not encode");
            let (batch, _) = self
                .host
                .dispatch_event(&self.event)
                .expect("the event failed");
            // The borrow of the Host's buffer has to end before the next call, so the
            // frame is read into the table here.
            let owned = batch.to_vec();
            self.absorb(&owned);
        }

        /// The document field, which is the last text field the screen declared.
        fn document_field(&self) -> u32 {
            *self
                .fields
                .last()
                .expect("the screen has no document field")
        }

        /// Typing into the document.
        fn type_document(&mut self, text: &str) {
            let node_id = self.document_field();
            let handler = *self
                .changes
                .get(&node_id)
                .expect("the document field declared no change handler");
            self.dispatch(node_id, handler, EventPayload::TextChanged(text));
        }

        /// Setting the path the Open and Save buttons work on.
        fn set_path(&mut self, path: &str) {
            let node_id = self.fields[0];
            let handler = *self
                .changes
                .get(&node_id)
                .expect("the path field declared no change handler");
            self.dispatch(node_id, handler, EventPayload::TextChanged(path));
        }

        /// Tells the screen the window changed size, the way the Renderer does.
        fn resize(&mut self, width_dp: f32) {
            self.dispatch(
                0,
                0,
                EventPayload::WindowSizeChanged {
                    width_dp,
                    height_dp: 900.0,
                    class: compose_rust::WindowSizeClass::from_width_dp(width_dp),
                    height_class: compose_rust::WindowHeightClass::from_height_dp(900.0),
                },
            );
        }

        /// Every node of this kind whose text reads exactly this.
        ///
        /// The kind matters: a list's heading and the button that opens the list say the
        /// same word, and a test about where the list is would otherwise be answered by
        /// the button in the bar.
        fn labelled(&self, widget: WidgetKind, label: &str) -> Vec<u32> {
            self.texts
                .iter()
                .filter(|(node_id, text)| {
                    *text == label && self.widgets.get(node_id) == Some(&widget)
                })
                .map(|(node_id, _)| *node_id)
                .collect()
        }

        fn in_a_sheet(&self, node: u32) -> bool {
            self.ancestors(node)
                .iter()
                .any(|parent| self.widgets.get(parent) == Some(&WidgetKind::Sheet))
        }

        fn click(&mut self, label: &str) {
            let (node_id, handler) = *self
                .buttons
                .get(label)
                .unwrap_or_else(|| panic!("the screen has no {label} button"));
            self.dispatch(node_id, handler, EventPayload::Clicked);
        }

        /// File work happens on a worker and lands on the UI thread in a later frame, so
        /// the test polls frames the way the Renderer would.
        fn settle_until(&mut self, contains: &str) {
            for _ in 0..400 {
                std::thread::sleep(std::time::Duration::from_millis(5));
                let batch = self
                    .host
                    .render_frame(0)
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

        /// The status line is the only text on the screen that reports what the file work
        /// did, so it is recognised by what it says.
        fn status(&self) -> String {
            self.texts
                .values()
                .find(|text| {
                    text.starts_with("Opened ")
                        || text.starts_with("Saved ")
                        || text.starts_with("Could not ")
                        || text.as_str() == "Ready"
                        || text.as_str() == "New document"
                })
                .cloned()
                .unwrap_or_default()
        }

        /// Whether the screen is showing this exact label anywhere.
        fn showing(&self, label: &str) -> bool {
            self.texts.values().any(|text| text == label)
        }

        fn document_text(&self) -> String {
            self.texts
                .get(&self.document_field())
                .cloned()
                .unwrap_or_default()
        }

        fn counter(&self, suffix: &str) -> String {
            self.texts
                .values()
                .find(|text| text.ends_with(suffix))
                .cloned()
                .unwrap_or_default()
        }
    }

    /// Every property this screen sets has to be one the wire can name. A property the
    /// schema does not have fails the whole batch rather than just itself, so a screen that
    /// builds in Rust can still be blank on screen.
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
    /// This is the screen where the page, the strip above it and the line below it either
    /// share an edge or do not, and no assertion about a batch can tell the difference.
    #[test]
    fn fr14_the_document_is_recorded_under_every_design_system_and_width() {
        sample_frames::record("Notepad", app, |_| {});
    }

    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        assert!(Host::new(app).rebuild().is_ok());
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

    /// The page widths and the strip's labels after the Renderer reports a window of the
    /// given width.
    fn page_at(width_dp: f32) -> (Vec<f32>, Vec<String>) {
        compose_rust::window::reset_window_size();
        let mut host = Host::new(app);
        let first = host.rebuild().expect("the first frame failed to encode");
        let mut widths = widths_of(first);
        let mut labels = texts_of(first);
        let event = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: EventPayload::WindowSizeChanged {
                width_dp,
                height_dp: 900.0,
                class: compose_rust::WindowSizeClass::from_width_dp(width_dp),
                height_class: compose_rust::WindowHeightClass::from_height_dp(900.0),
            },
        };
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).expect("the resize did not encode");
        let (batch, _) = host.dispatch_event(&bytes).expect("the resize failed");
        // A window that stayed in its class produces no batch at all, and then what the
        // screen is showing is still what the first frame said.
        if !batch.is_empty() {
            widths = widths_of(batch);
            labels = texts_of(batch);
        }
        compose_rust::window::reset_window_size();
        (widths, labels)
    }

    fn widths_of(batch: &[u8]) -> Vec<f32> {
        decode_batch(batch)
            .expect("the batch did not decode")
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetModifier {
                    modifier: compose_rust::Modifier::Width(width),
                    ..
                } => Some(*width),
                _ => None,
            })
            .collect()
    }

    fn texts_of(batch: &[u8]) -> Vec<String> {
        decode_batch(batch)
            .expect("the batch did not decode")
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetProp {
                    property: PropertyKind::Text,
                    value: PropertyValue::String(text),
                    ..
                } => Some((*text).to_owned()),
                _ => None,
            })
            .collect()
    }

    /// The document's name stands at the top of the page, which is where both memo
    /// references put it, and it follows the file the document is saved to.
    #[test]
    fn fr22_the_page_opens_with_the_document_name() {
        let mut editor = Editor::new();
        let starting = file_name(&display_path(&starting_path()));
        assert!(
            editor.texts.values().any(|text| *text == starting),
            "the page does not name the document it is showing"
        );

        editor.set_path("/tmp/a-different-note.txt");
        assert!(
            editor
                .texts
                .values()
                .any(|text| text == "a-different-note.txt"),
            "the name did not follow the file the document is saved to"
        );
    }

    /// Where the bytes live is something you go and ask for, not a strip across the top of
    /// every document. The two things that act on the file are in the sheet with it.
    #[test]
    fn fr22_the_file_controls_are_behind_a_sheet() {
        let editor = Editor::new();
        let path_field = editor.fields[0];
        let sheet = editor
            .ancestors(path_field)
            .into_iter()
            .find(|node| editor.widgets.get(node) == Some(&WidgetKind::Sheet))
            .expect("the path field is not in a sheet");
        let (open, _) = editor.buttons["Open"];
        assert!(
            editor.descends_from(open, sheet),
            "Open is not in the sheet the path field is in"
        );
    }

    /// Formatting stays off the page until it is requested, and choosing a style changes
    /// the role sent for the document field rather than painting a look into the sample.
    #[test]
    fn fr22_the_format_sheet_changes_the_document_type_role() {
        let mut editor = Editor::new();
        let heading = editor.buttons["Heading"].0;
        assert!(
            editor.in_a_sheet(heading),
            "the document style controls are not in a sheet"
        );

        editor.click("Heading");
        assert_eq!(
            editor.type_roles.get(&editor.document_field()),
            Some(&i64::from(u16::from(TypeRole::Headline))),
            "the selected style did not reach the document field"
        );
    }

    /// A desktop window has room for the list of documents to stand beside the one being
    /// written, which is what both memo references do. Anything narrower and the same list
    /// arrives from an edge instead.
    #[test]
    fn fr22_the_document_list_stands_beside_the_page_on_a_desktop_window() {
        compose_rust::window::reset_window_size();
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
            editor.texts.values().any(|text| text == "first-note.txt"),
            "the document that was open is not in the list"
        );
    }

    /// A desktop window gives the document a margin and stops the page growing with the
    /// window. A phone keeps the page full width, because there is nothing to spare.
    #[test]
    fn fr20_the_page_takes_a_margin_on_a_desktop_window() {
        let (narrow_widths, _) = page_at(420.0);
        assert!(narrow_widths.is_empty(), "{narrow_widths:?}");

        let (wide_widths, _) = page_at(1200.0);
        assert!(
            wide_widths.contains(&compose_rust::WindowSizeClass::EXPANDED_MIN_WIDTH_DP),
            "the page did not take a measure: {wide_widths:?}"
        );
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
        let measure = compose_rust::WindowSizeClass::EXPANDED_MIN_WIDTH_DP;
        let (wide_widths, _) = page_at(1200.0);
        assert_eq!(
            wide_widths
                .iter()
                .filter(|width| **width == measure)
                .count(),
            2,
            "the bar and the page should both be the measure: {wide_widths:?}"
        );

        // Narrower, neither is measured: the page is the window and the bar's own inset is
        // already the page's.
        let (narrow_widths, _) = page_at(420.0);
        assert!(narrow_widths.is_empty(), "{narrow_widths:?}");
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
