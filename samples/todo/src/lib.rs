//! A task list: add, edit in place, complete, delete, reorder, filter, and survive a
//! restart.
//!
//! The list is drawn with `LazyColumn`, so the number of widgets that exist is
//! proportional to what is on screen rather than to the number of tasks. Filling it with
//! five thousand rows is how that claim becomes something a person can check, and the
//! button that does it lives behind "About this sample" rather than on the list: a task
//! list does not have a button that invents five thousand tasks, and putting one on the
//! main surface says this is a demonstration of a list rather than a list.
//!
//! Every task carries an id that never changes, and that id is the list key. Editing one
//! row therefore rebuilds that row and leaves its neighbours alone.

mod store;

use compose_rust::prelude::*;
use store::{Filter, Task};

/// Enough rows that the window is a small fraction of the list.
const BULK_COUNT: usize = 5_000;

/// What opens the sheet, and what the sheet is called.
const ABOUT_LABEL: &str = "About";

/// The bar across the top of the window, with its contents held to the list's measure.
///
/// A bar spans its container because it belongs to the window. Its contents belong to the
/// list, and a title that starts at the window's edge while the list it counts starts
/// forty dp further in is a window whose two halves disagree about where the left side is.
///
/// `done` and `total` are the second thing this bar does: a list of tasks says how much of
/// itself is finished, and a number plus a bar says it twice, once for reading and once
/// for glancing.
fn list_bar(measure: Option<f32>, done: usize, total: usize, children: Element) -> Element {
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
            // An empty list has no progress to report, and a bar sitting at zero on a
            // screen with nothing on it reads as something that failed to load.
            if total > 0 {
                ProgressIndicator { value: done as f32 / total as f32 }
            }
        }
    }
}

/// What this sample is and the one control that only a sample has.
///
/// The button used to sit under the list, in the caption ink, with a sentence beside it
/// explaining that it was a demonstration control. That is an apology printed on the
/// primary surface. Behind a sheet it is simply somewhere else, and the list is a list.
fn about_panel(total: usize, fill: EventHandler<()>, close: EventHandler<()>) -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            space_role: SpaceRole::Md,
            Row {
                fill_max_width: true,
                alignment: Alignment::CenterStart,
                Text { text: "About this sample", type_role: TypeRole::Subtitle, weight: 1.0 }
                // "Close" rather than "Done", which is one of this screen's three
                // filters. Two things a sentence apart should not have one word.
                Button {
                    text: "Close",
                    variant: ButtonVariant::Filled,
                    on_click: move |_| close.call(()),
                }
            }
            Separator {}
            Text {
                text: "The list windows its rows: the widgets that exist are the ones on \
                       screen, not the ones in the list. A dozen tasks never exercises \
                       that, so this fills the list with enough rows that the window is a \
                       small fraction of it.",
                type_role: TypeRole::Body,
                color: Paint::Role(ColorRole::OnSurfaceVariant),
            }
            Row {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::CenterStart,
                Text {
                    text: "{total} tasks now",
                    type_role: TypeRole::Label,
                    color: Paint::Role(ColorRole::OnSurfaceVariant),
                    weight: 1.0,
                }
                Button {
                    text: "Add {BULK_COUNT} tasks",
                    variant: ButtonVariant::Tonal,
                    on_click: move |_| fill.call(()),
                }
            }
        }
    }
}

/// The composer: one grouped strip whose field grows with the window, so the field is the
/// thing you look at and the button is the thing beside it.
///
/// Authored once because it is one control. It stands at the head of the list where there
/// is room for it, and arrives in a sheet where there is not, which is what the reference
/// does: three of its four shots are an Add Task sheet.
fn composer(add: EventHandler<String>, draft: Signal<String>) -> Element {
    let mut draft = draft;
    rsx! {
        Surface {
            fill_max_width: true,
            Row {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::CenterStart,
                TextField {
                    weight: 1.0,
                    placeholder: "Add a task, then press Enter",
                    on_value_change: move |value| draft.set(value),
                    on_submit: move |value: String| add.call(value),
                }
                Button {
                    text: "Add",
                    variant: ButtonVariant::Filled,
                    on_click: move |_| add.call(draft()),
                }
            }
        }
    }
}

pub fn app() -> Element {
    let window = use_window_size();
    // A list of one line items read across 1200dp is a list nobody can scan: the eye has
    // to travel from the title to the actions and back for every row. Past an expanded
    // window the screen stops widening and centres, and the page shows either side.
    let measure = if window.is_expanded() {
        Some(WindowSizeClass::EXPANDED_MIN_WIDTH_DP)
    } else {
        None
    };
    let stacked = window.is_compact();
    let mut tasks = use_signal(store::load);
    let mut next_id = use_signal(|| {
        tasks
            .read()
            .iter()
            .map(|task| task.id + 1)
            .max()
            .unwrap_or(1)
    });
    let mut filter = use_signal(|| Filter::All);
    let mut about_open = use_signal(|| false);
    let mut compose_open = use_signal(|| false);

    // What the top field currently holds. The field is uncontrolled, so this is a copy the
    // field pushes up, not the field's value being driven from here.
    let mut draft = use_signal(String::new);
    // The task being edited in place, and the text its editor currently holds.
    let mut editing = use_signal(|| Option::<u64>::None);
    let mut edit_draft = use_signal(String::new);
    let mut menu_open = use_signal(|| Option::<u64>::None);

    let visible: Vec<usize> = tasks
        .read()
        .iter()
        .enumerate()
        .filter(|(_, task)| filter().accepts(task))
        .map(|(index, _)| index)
        .collect();
    let remaining = tasks.read().iter().filter(|task| !task.done).count();
    let total = tasks.read().len();

    let mut add = move |title: String| {
        let title = title.trim().to_owned();
        if title.is_empty() {
            return;
        }
        let id = next_id();
        next_id.set(id + 1);
        tasks.write().push(Task {
            id,
            title,
            done: false,
        });
        draft.set(String::new());
        store::save(&tasks.read());
    };

    let mut commit_edit = move |text: String| {
        let Some(id) = editing() else { return };
        let text = text.trim().to_owned();
        if !text.is_empty() {
            let mut list = tasks.write();
            if let Some(task) = list.iter_mut().find(|task| task.id == id) {
                task.title = text;
            }
        }
        editing.set(None);
        edit_draft.set(String::new());
        store::save(&tasks.read());
    };

    // Reordering is expressed in terms of what the user can see. Under a filter the
    // neighbour above a row is the previous visible row, not the previous stored one, so
    // each row works out its two neighbours and this only performs the swap. Keeping the
    // visible list out of the closure is what lets it stay `Copy`, which every handler
    // stored in the tree has to be.
    let mut swap_tasks = move |from: usize, to: usize| {
        tasks.write().swap(from, to);
        store::save(&tasks.read());
    };

    // One way in for a new task, whether the field is on the list or in the sheet.
    let compose = EventHandler::new(move |title: String| {
        add(title);
        compose_open.set(false);
    });

    let keys: Vec<String> = visible
        .iter()
        .map(|index| tasks.read()[*index].id.to_string())
        .collect();
    let rows = visible.clone();

    let list = rsx! {
        // An empty list explains itself rather than leaving a blank half window
        // that could just as well be a screen that failed to draw. It replaces the
        // list rather than sitting above it, so it gets the whole of the space the
        // list would have taken.
        if rows.is_empty() {
            compose_rust::Box {
                fill_max_width: true,
                weight: 1.0,
                alignment: Alignment::Center,
                Text {
                    text: match filter() {
                        Filter::All => "No tasks yet. Add one above.",
                        Filter::Active => "Nothing left to do under this filter.",
                        Filter::Done => "Nothing has been completed yet.",
                    },
                    type_role: TypeRole::Body,
                    color: Paint::Role(ColorRole::OnSurfaceVariant),
                }
            }
        } else {
        // The list is one grouped container, not a stack of cards. A task is a row
        // in a list of tasks, and a card each would say that every task is a
        // separate document. What separates one row from the next is a hairline.
        Surface {
            fill_max_width: true,
            weight: 1.0,
        LazyColumn {
            fill_max_width: true,
            fill_max_height: true,
            item_count: rows.len(),
            key_of: move |position: usize| keys[position].clone(),
            item: move |position: usize| {
                let index = rows[position];
                let previous = position.checked_sub(1).map(|above| rows[above]);
                let next = rows.get(position + 1).copied();
                let task = tasks.read()[index].clone();
                let editing_this = editing() == Some(task.id);
                let last = position + 1 == rows.len();
                rsx! {
                    Column {
                        fill_max_width: true,
                        Row {
                            fill_max_width: true,
                            padding_role: SpaceRole::Sm,
                            space_role: SpaceRole::Sm,
                            alignment: Alignment::CenterStart,
                            // A real checkbox, not a ballot box character in a text
                            // button. The glyph was the same shape everywhere, sized by
                            // the type ladder rather than by the control ladder, with no
                            // press state, no transition and nothing for a screen reader
                            // to call a checkbox. This one is the design system's, and it
                            // announces itself as a checkbox that is or is not ticked.
                            Checkbox {
                                checked: task.done,
                                on_change: move |_| {
                                    tasks.write()[index].done = !task.done;
                                    store::save(&tasks.read());
                                },
                            }
                            // The title takes the weight, so the actions sit at the
                            // far end of every row and line up down the list.
                            if editing_this {
                                TextField {
                                    weight: 1.0,
                                    placeholder: task.title.clone(),
                                    on_value_change: move |value| edit_draft.set(value),
                                    on_submit: move |value: String| commit_edit(value),
                                    on_focus_lost: move |_| commit_edit(edit_draft()),
                                }
                                Button {
                                    text: "Save",
                                    variant: ButtonVariant::Filled,
                                    on_click: move |_| commit_edit(edit_draft()),
                                }
                            } else {
                                Text {
                                    text: task.title.clone(),
                                    weight: 1.0,
                                    type_role: TypeRole::Body,
                                    color: if task.done {
                                        Paint::Role(ColorRole::OutlineVariant)
                                    } else {
                                        Paint::Role(ColorRole::OnSurface)
                                    },
                                    max_lines: 1,
                                    overflow: TextOverflow::Ellipsis,
                                }
                                // What a row can have done to it lives behind one control,
                                // not spread across four.
                                //
                                // Edit, move up, move down and delete on the face of every
                                // row is four permanently drawn controls per task, twenty
                                // thousand of them once this list is filled, and a row
                                // whose actions outweigh the task they act on. An overflow
                                // menu is the idiom every design system shares for
                                // this: Material calls it the overflow menu, Cupertino the
                                // ellipsis menu, Fluent a command flyout, and the three
                                // desktop Linux languages a hamburger. The other two
                                // answers are hover reveal and swipe, and neither is
                                // available: pointer enter and exit are not events the
                                // protocol carries, and a swipe is not a gesture the
                                // schema has.
                                //
                                // The entries exist only while the menu is open, so a
                                // row that nobody has asked anything of costs one
                                // control: the menu and the anchor it hangs off. Four
                                // buttons per visible row were being built and laid out
                                // for a popup that was not on screen.
                                Menu {
                                    expanded: menu_open() == Some(task.id),
                                    on_dismiss: move |_| menu_open.set(None),
                                    anchor: rsx! {
                                        Button {
                                            text: "\u{22ef}",
                                            variant: ButtonVariant::Text,
                                            color: Paint::Role(ColorRole::OnSurfaceVariant),
                                            on_click: move |_| menu_open.set(Some(task.id)),
                                        }
                                    },
                                    if menu_open() == Some(task.id) {
                                        Button {
                                            text: "Edit",
                                            variant: ButtonVariant::Text,
                                            fill_max_width: true,
                                            on_click: move |_| {
                                                menu_open.set(None);
                                                edit_draft.set(String::new());
                                                editing.set(Some(task.id));
                                            },
                                        }
                                        Button {
                                            text: "Move up",
                                            variant: ButtonVariant::Text,
                                            fill_max_width: true,
                                            enabled: previous.is_some(),
                                            on_click: move |_| {
                                                menu_open.set(None);
                                                if let Some(above) = previous {
                                                    swap_tasks(index, above);
                                                }
                                            },
                                        }
                                        Button {
                                            text: "Move down",
                                            variant: ButtonVariant::Text,
                                            fill_max_width: true,
                                            enabled: next.is_some(),
                                            on_click: move |_| {
                                                menu_open.set(None);
                                                if let Some(below) = next {
                                                    swap_tasks(index, below);
                                                }
                                            },
                                        }
                                        // Deleting is the one action here that throws work
                                        // away without asking first, so it says what it did
                                        // and offers the task back. The message is not a
                                        // widget this code places: it is handed over once and
                                        // the Renderer decides where it goes and how long it
                                        // stays, which is why nothing here holds a timer.
                                        Button {
                                            text: "Delete",
                                            variant: ButtonVariant::Text,
                                            fill_max_width: true,
                                            color: Paint::Role(ColorRole::Error),
                                            on_click: move |_| {
                                                menu_open.set(None);
                                                let removed = tasks.write().remove(index);
                                                store::save(&tasks.read());
                                                let title = removed.title.clone();
                                                Message::new(format!("Deleted \u{201c}{title}\u{201d}"))
                                                    .with_action("Undo", move |()| {
                                                        let at = index.min(tasks.read().len());
                                                        tasks.write().insert(at, removed.clone());
                                                        store::save(&tasks.read());
                                                    })
                                                    .with_duration(MessageDuration::Long)
                                                    .show();
                                            },
                                        }
                                    }
                                }
                            }
                        }
                        // The hairline belongs between two rows, so the last row
                        // does not draw one against the container's edge.
                        if !last {
                            Separator {}
                        }
                    }
                }
            },
        }
        }
        }
    };

    rsx! {
        // The three filters are three destinations, and one declaration is all of them.
        // This code never asks how wide the window is: the Renderer has measured it and
        // draws a bar along the bottom of a phone, a rail beside a tablet and a drawer
        // standing open on a desktop, from these same three items.
        Scaffold {
            bottom_bar: rsx! {
                Navigation {
                    // No fill here. In a frame the destinations are a slot and how much
                    // room they get is the frame's answer: a bar across the bottom on a
                    // phone, a strip down the leading edge on anything wider. Asking to
                    // fill the width took the whole row on a desktop and left the page
                    // with none of it.
                    selected_index: filter().index(),
                    for choice in Filter::STRIP {
                        NavigationItem {
                            key: "{choice.label()}",
                            text: choice.label(),
                            icon: choice.icon(),
                            on_click: move |()| filter.set(choice),
                        }
                    }
                }
            },
            // The bar names the frame's top slot rather than sitting inside the
            // page. A frame is asked which of its parts is the top of the window, and
            // a bar that is merely the first thing in the content is not an answer to
            // that: the window keeps the caption itself, the page starts below the
            // window buttons, and the strip above it is left in the window's colour
            // rather than the bar's. It read as a title bar nobody asked for.
            top_bar: rsx! {
            {list_bar(measure, total - remaining, total, rsx! {
                // Two lines, the way the reference heads its screens: what you are
                // looking at, and how it is going. The title used to be the application's
                // name, which is the one thing on the screen that never changes and so
                // the one thing least worth the largest type on it.
                Column {
                    weight: 1.0,
                    Text {
                        text: filter().label(),
                        type_role: TypeRole::Headline,
                        max_lines: 1,
                        overflow: TextOverflow::Ellipsis,
                    }
                    // Shortened on a phone, where the long form wraps to a second line
                    // and pushes the bar's own height out from under the title.
                    Text {
                        text: if stacked {
                            format!("{remaining} left")
                        } else {
                            format!("{remaining} of {total} remaining")
                        },
                        type_role: TypeRole::Label,
                        color: Paint::Role(ColorRole::OnSurfaceVariant),
                        max_lines: 1,
                        overflow: TextOverflow::Ellipsis,
                    }
                }
                // Where the composer is not on the list, the bar is how a task is added.
                if stacked {
                    Button {
                        text: "Add",
                        variant: ButtonVariant::Filled,
                        on_click: move |_| compose_open.set(true),
                    }
                }
                // Clearing throws work away, so it says so in the same colour a row's own
                // Delete uses, and offers the same way back.
                Button {
                    text: if stacked { "Clear" } else { "Clear completed" },
                    variant: ButtonVariant::Text,
                    color: Paint::Role(ColorRole::Error),
                    enabled: total > remaining,
                    on_click: move |_| {
                        let before = tasks();
                        let cleared = before.len() - remaining;
                        tasks.write().retain(|task| !task.done);
                        store::save(&tasks.read());
                        let plural = if cleared == 1 { "task" } else { "tasks" };
                        Message::new(format!("Cleared {cleared} completed {plural}"))
                            .with_action("Undo", move |()| {
                                tasks.set(before.clone());
                                store::save(&tasks.read());
                            })
                            .with_duration(MessageDuration::Long)
                            .show();
                    },
                }
                Button {
                    text: ABOUT_LABEL,
                    variant: ButtonVariant::Text,
                    on_click: move |_| about_open.set(true),
                }
            })}
            },

        Column {
            fill_max_width: true,
            fill_max_height: true,


            compose_rust::Box {
                fill_max_width: true,
                fill_max_height: true,
                alignment: Alignment::TopCenter,
            Column {
                fill_max_width: measure.is_none(),
                width: measure,
                fill_max_height: true,
                // The medium step, because that is what the bar insets its own contents
                // by. Anything else and the title and the list under it start at two
                // different places.
                padding_role: SpaceRole::Md,
                space_role: SpaceRole::Md,

                if !stacked {
                    {composer(compose, draft)}
                }

                {list}
            }
            }

            // The same composer, arriving from an edge, on the windows with no room for
            // it at the head of the list. Which edge is the Renderer's decision.
            Sheet {
                open: compose_open() && stacked,
                on_dismiss: move |_| compose_open.set(false),
                fill_max_width: true,
                Column {
                    fill_max_width: true,
                    space_role: SpaceRole::Md,
                    Row {
                        fill_max_width: true,
                        alignment: Alignment::CenterStart,
                        Text { text: "Add Task", type_role: TypeRole::Subtitle, weight: 1.0 }
                        Button {
                            text: "Close",
                            variant: ButtonVariant::Filled,
                            on_click: move |_| compose_open.set(false),
                        }
                    }
                    Separator {}
                    // Only where the composer is not already at the head of the list.
                    // Declaring it in both places would be two fields for one control:
                    // two things to focus, two drafts, and whichever one you did not type
                    // in holding the older text.
                    if stacked {
                        {composer(compose, draft)}
                    }
                }
            }

            // What this sample is, and the one control that belongs to the sample rather
            // than to the task list.
            Sheet {
                open: about_open(),
                on_dismiss: move |_| about_open.set(false),
                fill_max_width: true,
                {about_panel(
                    total,
                    EventHandler::new(move |()| {
                        let start = next_id();
                        {
                            let mut list = tasks.write();
                            list.reserve(BULK_COUNT);
                            for offset in 0..BULK_COUNT as u64 {
                                let id = start + offset;
                                list.push(Task {
                                    id,
                                    title: format!("Generated task {id}"),
                                    done: offset % 3 == 0,
                                });
                            }
                        }
                        next_id.set(start + BULK_COUNT as u64);
                        store::save(&tasks.read());
                        about_open.set(false);
                    }),
                    EventHandler::new(move |()| about_open.set(false)),
                )}
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
    prepare();
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
                .with_title("Todo")
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

/// What has to happen before the first frame, on every platform rather than only on the
/// one with a `main`. An Android Activity and a browser page reach the application
/// through their own entry points, and neither of them runs the desktop binary.
fn prepare() {
    store::start_saver();
}

// The platforms where the sample is not a program. Android's Activity and the browser's
// page both own the loop, so neither has a `main` to call: each names an entry point that
// registers the root component, and these macros define it.
//
// Both are declared unconditionally. Each macro compiles into nothing that runs off its
// own platform, and gating them here instead would mean a desktop build never checks that
// this sample can still be built for the other two.
compose_rust::android_main!(
    {
        prepare();
        launch_builder()
    },
    app
);
compose_rust::web_main!(
    {
        prepare();
        launch_builder()
    },
    app
);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;
    use compose_rust::Host;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
    use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind};
    use std::collections::HashMap;
    use std::sync::OnceLock;

    /// A saved list large enough that a window of twenty is a small fraction of it.
    const SAVED_TASKS: usize = 5_000;
    const WINDOW: usize = 20;

    /// What a row's action menu offers, and the glyph on the control that opens it.
    const ACTIONS: [&str; 4] = ["Edit", "Move up", "Move down", "Delete"];
    const ACTIONS_ANCHOR: &str = "\u{22ef}";

    /// Point the store at a generated file, once for the whole test binary. Tests run
    /// concurrently in one process, so the variable is written before any of them reads it
    /// rather than once per test.
    fn saved_list() {
        static PREPARED: OnceLock<()> = OnceLock::new();
        PREPARED.get_or_init(|| {
            let path = scratch("todo-window");
            let saved: String = (0..SAVED_TASKS)
                .map(|id| format!("{id}\t0\tGenerated task {id}\n"))
                .collect();
            std::fs::write(&path, saved).expect("the fixture list could not be written");
            // SAFETY: written once, before this binary's tests read it through
            // `store::load`, and nothing else in the process touches the environment.
            unsafe { std::env::set_var("SAMPLE_TODO_FILE", &path) };
        });
    }

    fn task_title(index: usize) -> String {
        format!("Generated task {index}")
    }

    fn expected_titles(start: usize, count: usize) -> Vec<String> {
        let first = start.min(SAVED_TASKS);
        let last = (first + count).min(SAVED_TASKS);
        let mut titles: Vec<_> = (first..last).map(task_title).collect();
        titles.sort();
        titles
    }

    /// Stand-in for the Compose interpreter: applies a batch to a node table so the test
    /// can ask what is alive rather than what was mentioned. Removing a node removes its
    /// whole subtree, as Compose does.
    #[derive(Default)]
    struct MockRenderer {
        widgets: HashMap<u32, WidgetKind>,
        texts: HashMap<u32, String>,
        item_keys: HashMap<u32, String>,
        parents: HashMap<u32, u32>,
        item_count: Option<i64>,
        /// The click handler each node declared, so a test can press what it found.
        click_handlers: HashMap<u32, u64>,
    }

    impl MockRenderer {
        fn apply(&mut self, batch: &[Mutation<'_>]) {
            for mutation in batch {
                match mutation {
                    Mutation::Create { node_id, widget } => {
                        self.widgets.insert(*node_id, *widget);
                    }
                    Mutation::Insert {
                        parent_id, node_id, ..
                    }
                    | Mutation::Move {
                        parent_id, node_id, ..
                    } => {
                        self.parents.insert(*node_id, *parent_id);
                    }
                    Mutation::Remove { node_id } => self.remove_subtree(*node_id),
                    Mutation::SetProp {
                        node_id,
                        property,
                        value,
                    } => match (property, value) {
                        (PropertyKind::Text, PropertyValue::String(value)) => {
                            self.texts.insert(*node_id, (*value).to_owned());
                        }
                        (PropertyKind::ItemKey, PropertyValue::String(value)) => {
                            self.item_keys.insert(*node_id, (*value).to_owned());
                        }
                        (PropertyKind::ItemCount, PropertyValue::Integer(value)) => {
                            self.item_count = Some(*value);
                        }
                        (PropertyKind::OnClick, PropertyValue::Integer(value)) => {
                            self.click_handlers.insert(*node_id, *value as u64);
                        }
                        _ => {}
                    },
                    _ => {}
                }
            }
        }

        fn remove_subtree(&mut self, node_id: u32) {
            let children: Vec<_> = self
                .parents
                .iter()
                .filter(|(_, parent)| **parent == node_id)
                .map(|(child, _)| *child)
                .collect();
            for child in children {
                self.remove_subtree(child);
            }
            self.widgets.remove(&node_id);
            self.texts.remove(&node_id);
            self.item_keys.remove(&node_id);
            self.parents.remove(&node_id);
        }

        /// The titles of the tasks that currently exist as widgets. The screen's own
        /// chrome carries text too, so a task title is recognised by its text.
        fn live_task_titles(&self) -> Vec<String> {
            let mut titles: Vec<_> = self
                .texts
                .iter()
                .filter(|(node_id, _)| self.widgets.contains_key(node_id))
                .map(|(_, text)| text.clone())
                .filter(|text| text.starts_with("Generated task "))
                .collect();
            titles.sort();
            titles
        }

        fn live_item_keys(&self) -> Vec<String> {
            let mut keys: Vec<_> = self
                .item_keys
                .iter()
                .filter(|(node_id, _)| self.widgets.contains_key(node_id))
                .map(|(_, key)| key.clone())
                .collect();
            keys.sort();
            keys
        }

        fn node_count(&self) -> usize {
            self.widgets.len()
        }

        /// How many live nodes are of one kind.
        fn count_of(&self, kind: WidgetKind) -> usize {
            self.widgets.values().filter(|got| **got == kind).count()
        }

        /// Whether a node has an ancestor of the given kind.
        fn is_inside(&self, node_id: u32, kind: WidgetKind) -> bool {
            let mut current = node_id;
            while let Some(parent) = self.parents.get(&current).copied() {
                if self.widgets.get(&parent) == Some(&kind) {
                    return true;
                }
                current = parent;
            }
            false
        }

        /// Every live node of one kind.
        fn nodes_of_kind(&self, kind: WidgetKind) -> Vec<u32> {
            let mut nodes: Vec<u32> = self
                .widgets
                .iter()
                .filter(|(_, got)| **got == kind)
                .map(|(node_id, _)| *node_id)
                .collect();
            nodes.sort_unstable();
            nodes
        }

        /// The nodes of one kind carrying one piece of text.
        fn nodes_of_kind_with_text(&self, kind: WidgetKind, wanted: &str) -> Vec<u32> {
            self.nodes_with_text(wanted)
                .into_iter()
                .filter(|node_id| self.widgets.get(node_id) == Some(&kind))
                .collect()
        }

        /// The nodes carrying one piece of text.
        fn nodes_with_text(&self, wanted: &str) -> Vec<u32> {
            self.texts
                .iter()
                .filter(|(node_id, text)| {
                    self.widgets.contains_key(node_id) && text.as_str() == wanted
                })
                .map(|(node_id, _)| *node_id)
                .collect()
        }
    }

    /// A record copied out of a batch so the batch can be dropped.
    #[derive(Debug)]
    enum OwnedRecord {
        Message {
            handler_id: u64,
            text: String,
            action: String,
        },
    }

    /// The real screen, driven the way the Renderer drives it.
    struct Screen {
        host: Host,
        mock: MockRenderer,
        list: u32,
        handler: u64,
        event: Vec<u8>,
    }

    impl Screen {
        fn new() -> Self {
            saved_list();
            let mut host = Host::new(app);
            let first = decode_batch(host.rebuild().expect("the first frame failed to encode"))
                .expect("the first frame did not decode");
            let mut mock = MockRenderer::default();
            mock.apply(&first);
            let list = first
                .iter()
                .find_map(|mutation| match mutation {
                    Mutation::Create {
                        node_id,
                        widget: WidgetKind::LazyColumn,
                    } => Some(*node_id),
                    _ => None,
                })
                .expect("the screen has no LazyColumn");
            let handler = first
                .iter()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::OnRangeRequested,
                        value: PropertyValue::Integer(id),
                    } if *node_id == list => Some(*id as u64),
                    _ => None,
                })
                .expect("the LazyColumn declared no range handler");
            drop(first);
            Self {
                host,
                mock,
                list,
                handler,
                event: Vec::new(),
            }
        }

        /// Opens the action menu on the first visible row, which is what builds its
        /// entries, and says which control it hangs off.
        fn open_first_menu(&mut self) -> u32 {
            let anchor = self
                .mock
                .nodes_with_text(ACTIONS_ANCHOR)
                .into_iter()
                .min()
                .expect("no visible row carried an action menu");
            self.click(anchor);
            anchor
        }

        /// The screen after the Renderer has reported a window of this width.
        fn at(width_dp: f32) -> Self {
            compose_rust::window::reset_window_size();
            let mut screen = Self::new();
            screen.dispatch(HostEvent {
                node_id: 0,
                handler_id: 0,
                payload: EventPayload::WindowSizeChanged {
                    width_dp,
                    height_dp: 900.0,
                    class: compose_rust::WindowSizeClass::from_width_dp(width_dp),
                    height_class: compose_rust::WindowHeightClass::from_height_dp(900.0),
                },
            });
            compose_rust::window::reset_window_size();
            screen
        }

        /// Presses a node, the way the Renderer reports a press, and returns what the
        /// Host said in reply.
        fn click(&mut self, node_id: u32) -> Vec<OwnedRecord> {
            let handler_id = *self
                .mock
                .click_handlers
                .get(&node_id)
                .unwrap_or_else(|| panic!("node {node_id} has no click handler"));
            self.dispatch(HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::Clicked,
            })
        }

        /// Sends one event and applies the batch that comes back.
        fn dispatch(&mut self, event: HostEvent<'_>) -> Vec<OwnedRecord> {
            encode_event(&event, &mut self.event).expect("the event did not encode");
            let bytes = self.event.clone();
            let (batch, _) = self.host.dispatch_event(&bytes).expect("the event failed");
            let decoded = decode_batch(batch).expect("the batch did not decode");
            self.mock.apply(&decoded);
            decoded
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::ShowMessage {
                        handler_id,
                        text,
                        action,
                        ..
                    } => Some(OwnedRecord::Message {
                        handler_id: *handler_id,
                        text: (*text).to_owned(),
                        action: (*action).to_owned(),
                    }),
                    _ => None,
                })
                .collect()
        }

        /// Scrolling: the Renderer asks for a window and the Host answers with a batch.
        /// Returns how many widgets that batch created.
        fn request_range(&mut self, start: usize, count: usize) -> usize {
            encode_event(
                &HostEvent {
                    node_id: self.list,
                    handler_id: self.handler,
                    payload: EventPayload::RangeRequested {
                        start: start as u32,
                        count: count as u32,
                    },
                },
                &mut self.event,
            )
            .expect("the range request did not encode");
            let (batch, _) = self
                .host
                .dispatch_event(&self.event)
                .expect("the range request failed");
            let decoded = decode_batch(batch).expect("the window batch did not decode");
            self.mock.apply(&decoded);
            decoded
                .iter()
                .filter(|mutation| matches!(mutation, Mutation::Create { .. }))
                .count()
        }
    }

    /// A filled list, under every design system, in both schemes, at all three widths.
    ///
    /// The calculator shows a keypad and a readout, which leaves the two things this
    /// screen has and that one does not: rows, and the entry above them. Those are where
    /// a spacing ladder and a separator colour stop being numbers in a table and become
    /// something a person either can or cannot read.
    ///
    /// A `LazyColumn` holds no rows until the Renderer asks for a window, so the recorder
    /// answers that request, which is what a real Renderer would have done before the
    /// first pixel.
    #[test]
    fn fr14_a_filled_list_is_recorded_under_every_design_system_and_width() {
        const WINDOW_SHOWN: u32 = 12;

        saved_list();
        sample_frames::record("Todo", app, |screen| {
            let lists = screen.fill_lists(WINDOW_SHOWN);
            assert_eq!(
                lists, 1,
                "the screen should hold exactly one windowing list, or the picture is of \
                 something other than the task list"
            );
            let rows = screen
                .mutations()
                .iter()
                .filter(|mutation| {
                    matches!(
                        mutation,
                        Mutation::SetProp {
                            property: PropertyKind::ItemKey,
                            ..
                        }
                    )
                })
                .count();
            assert_eq!(
                rows, WINDOW_SHOWN as usize,
                "a window of {WINDOW_SHOWN} was answered with {rows} rows, so the list drawn \
                 is not the list that was asked for"
            );
        });
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

    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        saved_list();
        assert!(Host::new(app).rebuild().is_ok());
    }

    /// The Renderer owns the scroll position, so it owns the read-ahead buffer too. The
    /// Host materialises the range it was asked for and no more: a Host that widened the
    /// range would leave the Renderer unable to say where the subtree it received belongs
    /// in the full list.
    #[test]
    fn fr8_only_the_requested_range_is_materialised() {
        let mut screen = Screen::new();
        assert_eq!(screen.mock.item_count, Some(SAVED_TASKS as i64));
        assert!(screen.mock.live_task_titles().len() < SAVED_TASKS);

        screen.request_range(100, WINDOW);

        assert_eq!(screen.mock.live_task_titles(), expected_titles(100, WINDOW));
    }

    /// The claim the "Add 5000" button exists to let a person check by hand, asserted so
    /// it cannot quietly stop being true. A window of twenty out of five thousand tasks
    /// Every string the screen holds after the Renderer reports a window of this width.
    fn texts_at(width_dp: f32) -> Vec<String> {
        saved_list();
        compose_rust::window::reset_window_size();
        let mut screen = Screen::new();
        screen.request_range(0, WINDOW);
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
        encode_event(&event, &mut screen.event).expect("the resize did not encode");
        let event_bytes = screen.event.clone();
        let (batch, _) = screen
            .host
            .dispatch_event(&event_bytes)
            .expect("the resize failed");
        let decoded = decode_batch(batch).expect("the resize batch did not decode");
        screen.mock.apply(&decoded);
        let texts = screen.mock.texts.values().cloned().collect();
        compose_rust::window::reset_window_size();
        texts
    }

    /// The widths the screen asks for after the Renderer reports a window of this width.
    fn widths_at(width_dp: f32) -> Vec<f32> {
        saved_list();
        compose_rust::window::reset_window_size();
        let mut host = Host::new(app);
        host.rebuild().expect("the first frame failed to encode");
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
        let widths = decode_batch(batch)
            .expect("the resize batch did not decode")
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetModifier {
                    modifier: compose_rust::Modifier::Width(width),
                    ..
                } => Some(*width),
                _ => None,
            })
            .collect();
        compose_rust::window::reset_window_size();
        widths
    }

    /// A phone shortens the worded action, because at 420dp the words leave the title no
    /// room. A desktop window stops the screen widening and centres it, because a one line
    /// item read across 1200dp cannot be scanned.
    ///
    /// The row's own actions are not in this: they are entries in the row's menu, so they
    /// are read at their full length at every width and never crowd the title.
    #[test]
    fn fr20_the_screen_shortens_on_a_phone_and_stops_widening_on_a_desktop() {
        let narrow = texts_at(420.0);
        assert!(narrow.iter().any(|text| text == "Clear"), "{narrow:?}");
        assert!(!narrow.iter().any(|text| text == "Clear completed"));

        let wide = texts_at(1200.0);
        assert!(
            wide.iter().any(|text| text == "Clear completed"),
            "{wide:?}"
        );

        assert!(widths_at(420.0).is_empty());
        assert!(
            widths_at(1200.0).contains(&compose_rust::WindowSizeClass::EXPANDED_MIN_WIDTH_DP),
            "the screen did not take a measure"
        );
    }

    /// The bar's contents and the list are held to the same measure, so the title starts
    /// where the list starts.
    #[test]
    fn fr20_the_bar_holds_its_contents_to_the_same_measure_as_the_list() {
        let measure = compose_rust::WindowSizeClass::EXPANDED_MIN_WIDTH_DP;
        let widths = widths_at(1200.0);
        assert_eq!(
            widths.iter().filter(|width| **width == measure).count(),
            2,
            "the bar and the list should both be the measure: {widths:?}"
        );
    }

    /// The reference heads its screens with two lines: what is being looked at, and how
    /// it is going. The application's name is the one thing on the screen that never
    /// changes, so it is the one thing least worth the largest type on it.
    #[test]
    fn fr22_the_screen_is_headed_by_what_it_is_showing() {
        let screen = Screen::new();
        assert!(
            screen
                .mock
                .nodes_of_kind_with_text(WidgetKind::Text, Filter::All.label())
                .len()
                == 1,
            "the screen does not name the filter it is showing"
        );
        assert!(
            screen.mock.nodes_with_text("Tasks").is_empty(),
            "the screen is still headed by the application's name"
        );
    }

    /// Three of the reference's four shots are an Add Task sheet. On a phone the composer
    /// is behind one, and on a window with room it stands at the head of the list, and it
    /// is one composer either way.
    #[test]
    fn fr22_the_composer_moves_into_a_sheet_on_a_phone() {
        let narrow = Screen::at(420.0);
        let fields = narrow.mock.nodes_of_kind(WidgetKind::TextField);
        assert_eq!(
            fields.len(),
            1,
            "a phone should declare the composer once and nowhere else"
        );
        assert!(
            narrow.mock.is_inside(fields[0], WidgetKind::Sheet),
            "a phone should reach the composer through a sheet"
        );

        let wide = Screen::at(1200.0);
        let fields = wide.mock.nodes_of_kind(WidgetKind::TextField);
        assert_eq!(fields.len(), 1);
        assert!(
            !wide.mock.is_inside(fields[0], WidgetKind::Sheet),
            "a window with room should stand the composer at the head of the list"
        );
    }

    /// The only control that invents five thousand tasks is behind the sheet that says
    /// what this sample is. A task list does not have one, and one on the main surface
    /// says this is a demonstration of a list rather than a list.
    #[test]
    fn fr21_the_bulk_fill_is_behind_the_about_sheet_rather_than_on_the_list() {
        let screen = Screen::new();
        let fill = screen
            .mock
            .nodes_with_text(&format!("Add {BULK_COUNT} tasks"));
        assert_eq!(fill.len(), 1, "the bulk fill control is not on the screen");
        assert!(
            screen.mock.is_inside(fill[0], WidgetKind::Sheet),
            "the bulk fill control is on the list rather than inside the sheet"
        );
    }

    /// A row ticks with the design system's own checkbox rather than with a ballot box
    /// character in a text button. The glyph was one shape everywhere, sized by the type
    /// ladder rather than by the control ladder, and announced itself to a screen reader
    /// as a button whose label was a box.
    #[test]
    fn fr15_2_4_a_row_is_ticked_with_a_checkbox() {
        let mut screen = Screen::new();
        screen.request_range(0, WINDOW);
        assert_eq!(
            screen.mock.count_of(WidgetKind::Checkbox),
            WINDOW,
            "each visible row should carry exactly one checkbox"
        );
        for glyph in ["\u{2610}", "\u{2611}"] {
            assert!(
                screen.mock.nodes_with_text(glyph).is_empty(),
                "a ballot box character is still standing in for a checkbox"
            );
        }
    }

    /// A row carries one action control, and what it can do sits behind it.
    ///
    /// Edit, an up arrow, a down arrow and a delete on the face of every row is four
    /// permanently drawn controls per task, and twenty thousand of them once this list is
    /// filled. They are entries in the row's menu now, which is one control on the row and
    /// nothing drawn until it is opened.
    #[test]
    fn fr20_a_row_carries_one_action_control_and_hides_the_rest() {
        let mut screen = Screen::new();
        screen.request_range(0, WINDOW);

        assert_eq!(
            screen.mock.count_of(WidgetKind::Menu),
            WINDOW,
            "each visible row should carry exactly one action menu"
        );
        assert_eq!(screen.mock.nodes_with_text(ACTIONS_ANCHOR).len(), WINDOW);
        for action in ACTIONS {
            assert!(
                screen.mock.nodes_with_text(action).is_empty(),
                "{action} was built for a menu nobody has opened"
            );
        }

        let closed = screen.mock.node_count();
        screen.open_first_menu();
        for action in ACTIONS {
            let nodes = screen.mock.nodes_with_text(action);
            assert_eq!(nodes.len(), 1, "{action} is not once for the one open menu");
            assert!(
                screen.mock.is_inside(nodes[0], WidgetKind::Menu),
                "{action} is on the face of a row rather than inside its menu"
            );
        }
        assert_eq!(
            screen.mock.node_count(),
            closed + ACTIONS.len(),
            "opening one menu cost more than its own entries"
        );
    }

    /// The three filters are three destinations, and this screen never asks how wide the
    /// window is to draw them. Which of the three shapes they take is the Renderer's,
    /// which is what the Renderer's own tests check at each width.
    #[test]
    fn fr21_the_filters_are_a_destination_set_rather_than_a_row_of_buttons() {
        let screen = Screen::new();
        assert_eq!(screen.mock.count_of(WidgetKind::Navigation), 1);
        assert_eq!(screen.mock.count_of(WidgetKind::NavigationItem), 3);
        for label in ["All", "Active", "Done"] {
            assert_eq!(
                screen
                    .mock
                    .nodes_of_kind_with_text(WidgetKind::NavigationItem, label)
                    .len(),
                1,
                "{label} should be one destination"
            );
            // The screen heads itself with the name of what it is showing, so the word
            // is on it twice; what there must not be is a button carrying it, which is
            // what a filter strip made of buttons would look like on the wire.
            assert!(
                screen
                    .mock
                    .nodes_of_kind_with_text(WidgetKind::Button, label)
                    .is_empty(),
                "{label} is a button as well as a destination"
            );
        }
    }

    /// Deleting throws work away without asking first, so it says what it did and offers
    /// the task back. The message is not a widget this screen placed: no node is created
    /// for it, and nothing here holds a timer for taking it away again.
    #[test]
    fn fr21_deleting_a_task_says_so_and_offers_it_back() {
        let mut screen = Screen::new();
        screen.request_range(0, WINDOW);
        // Deleting is behind the row's menu, and the entries do not exist until it is
        // opened.
        screen.open_first_menu();
        let before = screen.mock.node_count();
        let delete = screen
            .mock
            .nodes_with_text("Delete")
            .into_iter()
            .min()
            .expect("the open menu offered no Delete");

        let said = screen.click(delete);
        assert_eq!(screen.mock.item_count, Some(SAVED_TASKS as i64 - 1));
        let [
            OwnedRecord::Message {
                handler_id,
                text,
                action,
            },
        ] = said.as_slice()
        else {
            panic!("deleting said {said:?} rather than one message");
        };
        assert!(text.starts_with("Deleted"), "{text}");
        assert_eq!(action, "Undo");
        assert!(
            screen.mock.node_count() <= before,
            "saying something cost widgets: {before} nodes became {}",
            screen.mock.node_count(),
        );

        // The action names no node, because the message owns none.
        screen.dispatch(HostEvent {
            node_id: 0,
            handler_id: *handler_id,
            payload: EventPayload::Clicked,
        });
        assert_eq!(screen.mock.item_count, Some(SAVED_TASKS as i64));
    }

    /// costs widgets in proportion to the twenty. This is the test that fails the day
    /// windowing degrades into building everything.
    #[test]
    fn fr8_node_count_is_proportional_to_the_window() {
        let mut screen = Screen::new();
        screen.request_range(4_000, WINDOW);

        // A row is a handful of widgets: the column holding it, the row itself, the
        // toggle, the title, the action menu with its anchor and its four entries, and
        // the hairline under it. The screen's own chrome is a fixed handful on top of
        // that. What matters is that the total tracks the window and not the list behind
        // it, which is why this is a per-row multiplier rather than a fixed total.
        const PER_ROW: usize = 12;
        const CHROME: usize = 40;
        let nodes = screen.mock.node_count();
        assert_eq!(screen.mock.live_task_titles().len(), WINDOW);
        assert!(
            nodes <= PER_ROW * WINDOW + CHROME,
            "{nodes} nodes materialised for a window of {WINDOW} out of {SAVED_TASKS} tasks"
        );
    }

    /// Scrolling away and back rebuilds the identical subtree. The Host owns the data, so
    /// a row that left the window comes back with the same title under the same key.
    #[test]
    fn fr8_re_requesting_an_earlier_range_restores_it() {
        let mut screen = Screen::new();
        screen.request_range(0, 10);
        let first_titles = screen.mock.live_task_titles();
        let first_keys = screen.mock.live_item_keys();
        assert_eq!(first_titles, expected_titles(0, 10));

        screen.request_range(4_000, 10);
        assert_eq!(screen.mock.live_task_titles(), expected_titles(4_000, 10));

        screen.request_range(0, 10);
        assert_eq!(screen.mock.live_task_titles(), first_titles);
        assert_eq!(screen.mock.live_item_keys(), first_keys);
    }

    /// Scrolling stays flat. A window far down the list costs what the first one cost, or
    /// a long list gets slower the further it is read.
    #[test]
    fn fr8_a_later_window_costs_what_the_first_one_cost() {
        let mut screen = Screen::new();
        let first = screen.request_range(0, WINDOW);
        for start in (WINDOW..SAVED_TASKS).step_by(WINDOW).take(50) {
            let created = screen.request_range(start, WINDOW);
            assert!(
                created <= first,
                "the window at {start} created {created} widgets where the first created {first}"
            );
        }
    }
}
