//! A task list: add, edit in place, complete, delete, reorder, filter, and survive a
//! restart.
//!
//! The list is drawn with `LazyColumn`, so the number of widgets that exist is
//! proportional to what is on screen rather than to the number of tasks. Filling it with
//! five thousand rows is how that claim becomes something a person can check, and the
//! button that does it lives behind "About this sample" rather than on the list.
//!
//! Every task carries an id that never changes, and that id is the list key. Editing one
//! row therefore composes that row again and leaves its neighbours alone.

use compose_rust::runtime::*;
use compose_rust::ui::*;
use std::rc::Rc;

mod store;

use store::{Filter, Task};

/// Enough rows that the window is a small fraction of the list.
const BULK_COUNT: usize = 5_000;

/// What opens the sheet, and what the sheet is called.
const ABOUT_LABEL: &str = "About";

/// The bar across the top of the window, with its contents held to the list's measure.
#[composable]
fn list_bar(measure: Option<f32>, done: usize, total: usize, children: impl FnOnce()) {
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
        // An empty list has no progress to report.
        if total > 0 {
            ProgressIndicator(done as f32 / total as f32);
        }
    });
}

/// What this sample is and the one control that only a sample has.
#[composable]
fn about_panel(total: usize, fill: Rc<dyn Fn()>, close: Rc<dyn Fn()>) {
    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Md)
        .content(|| {
            Row()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Text("About this sample")
                        .type_role(TypeRole::Subtitle)
                        .modifier(Modifier.weight(1.0));
                    let close = Rc::clone(&close);
                    Button("Close")
                        .variant(ButtonVariant::Filled)
                        .on_click(move || close());
                });
            Separator(None);
            Text(
                "The list windows its rows: the widgets that exist are the ones on \
                 screen, not the ones in the list. A dozen tasks never exercises \
                 that, so this fills the list with enough rows that the window is a \
                 small fraction of it.",
            )
            .type_role(TypeRole::Body)
            .color(Paint::Role(ColorRole::OnSurfaceVariant));
            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Text(format!("{total} tasks now"))
                        .type_role(TypeRole::Label)
                        .color(Paint::Role(ColorRole::OnSurfaceVariant))
                        .modifier(Modifier.weight(1.0));
                    let fill = Rc::clone(&fill);
                    Button(format!("Add {BULK_COUNT} tasks"))
                        .variant(ButtonVariant::Tonal)
                        .on_click(move || fill());
                });
        });
}

/// The composer: one grouped strip whose field grows with the window.
#[composable]
fn composer(add: Rc<dyn Fn(String)>, draft: MutableState<String>) {
    Surface().modifier(Modifier.fill_max_width()).content(|| {
        Row()
            .modifier(Modifier.fill_max_width())
            .space_role(SpaceRole::Sm)
            .alignment(Alignment::CenterStart)
            .content(|| {
                let (typed, submit) = (draft.clone(), Rc::clone(&add));
                TextField()
                    .modifier(Modifier.weight(1.0))
                    .placeholder("Add a task, then press Enter")
                    .on_value_change(move |value| typed.set(value))
                    .on_submit(move |value: String| submit(value));
                let (pressed, current) = (Rc::clone(&add), draft.clone());
                Button("Add")
                    .variant(ButtonVariant::Filled)
                    .on_click(move || pressed(current.get_untracked()));
            });
    });
}

/// The task list.
#[composable]
pub fn app() {
    let window = current_window_size();
    // Past an expanded window the screen stops widening and centres.
    let measure = if window.is_expanded() {
        Some(WindowSizeClass::EXPANDED_MIN_WIDTH_DP)
    } else {
        None
    };
    let stacked = window.is_compact();
    let tasks = remember(|| mutable_state_of_with_policy(store::load(), never_equal_policy()));
    let next_id = remember(|| {
        mutable_state_of(
            tasks
                .get_untracked()
                .iter()
                .map(|task| task.id + 1)
                .max()
                .unwrap_or(1),
        )
    });
    let filter = remember(|| mutable_state_of(Filter::All));
    let about_open = remember(|| mutable_state_of(false));
    let compose_open = remember(|| mutable_state_of(false));
    // What the field currently holds: a copy the field pushes up, never pushed down.
    let draft = remember(|| mutable_state_of_with_policy(String::new(), never_equal_policy()));
    // The task being edited in place, and the text its editor currently holds.
    let editing = remember(|| mutable_state_of(Option::<u64>::None));
    let edit_draft = remember(|| mutable_state_of_with_policy(String::new(), never_equal_policy()));
    let menu_open = remember(|| mutable_state_of(Option::<u64>::None));

    let current_filter = filter.get();
    let visible: Vec<usize> = tasks.with(|tasks| {
        tasks
            .iter()
            .enumerate()
            .filter(|(_, task)| current_filter.accepts(task))
            .map(|(index, _)| index)
            .collect()
    });
    let remaining = tasks.with(|tasks| tasks.iter().filter(|task| !task.done).count());
    let total = tasks.with(Vec::len);

    let add: Rc<dyn Fn(String)> = {
        let (tasks, next_id, draft) = (tasks.clone(), next_id.clone(), draft.clone());
        Rc::new(move |title: String| {
            let title = title.trim().to_owned();
            if title.is_empty() {
                return;
            }
            let id = next_id.get_untracked();
            next_id.set(id + 1);
            tasks.update(|tasks| {
                tasks.push(Task {
                    id,
                    title,
                    done: false,
                })
            });
            draft.set(String::new());
            store::save(&tasks.get_untracked());
        })
    };

    let commit_edit: Rc<dyn Fn(String)> = {
        let (tasks, editing, edit_draft) = (tasks.clone(), editing.clone(), edit_draft.clone());
        Rc::new(move |text: String| {
            let Some(id) = editing.get_untracked() else {
                return;
            };
            let text = text.trim().to_owned();
            if !text.is_empty() {
                tasks.update(|list| {
                    if let Some(task) = list.iter_mut().find(|task| task.id == id) {
                        task.title = text;
                    }
                });
            }
            editing.set(None);
            edit_draft.set(String::new());
            store::save(&tasks.get_untracked());
        })
    };

    // Reordering is expressed in what the user can see; each row works out its two
    // neighbours and this only performs the swap.
    let swap_tasks: Rc<dyn Fn(usize, usize)> = {
        let tasks = tasks.clone();
        Rc::new(move |from: usize, to: usize| {
            tasks.update(|tasks| tasks.swap(from, to));
            store::save(&tasks.get_untracked());
        })
    };

    // One way in for a new task, whether the field is on the list or in the sheet.
    let compose: Rc<dyn Fn(String)> = {
        let (add, compose_open) = (Rc::clone(&add), compose_open.clone());
        Rc::new(move |title: String| {
            add(title);
            compose_open.set(false);
        })
    };

    let keys: Vec<String> = tasks.with(|tasks| {
        visible
            .iter()
            .map(|index| tasks[*index].id.to_string())
            .collect()
    });
    let rows = visible.clone();

    let list = || {
        // An empty list explains itself rather than leaving a blank half window.
        if rows.is_empty() {
            Box()
                .modifier(Modifier.fill_max_width().weight(1.0))
                .alignment(Alignment::Center)
                .content(|| {
                    Text(match current_filter {
                        Filter::All => "No tasks yet. Add one above.",
                        Filter::Active => "Nothing left to do under this filter.",
                        Filter::Done => "Nothing has been completed yet.",
                    })
                    .type_role(TypeRole::Body)
                    .color(Paint::Role(ColorRole::OnSurfaceVariant));
                });
        } else {
            // One grouped container, not a stack of cards; a hairline between rows.
            Surface()
                .modifier(Modifier.fill_max_width().weight(1.0))
                .content(|| {
                    let rows = rows.clone();
                    let keys = keys.clone();
                    let (tasks, editing, edit_draft, menu_open) = (
                        tasks.clone(),
                        editing.clone(),
                        edit_draft.clone(),
                        menu_open.clone(),
                    );
                    let (commit_edit, swap_tasks) =
                        (Rc::clone(&commit_edit), Rc::clone(&swap_tasks));
                    LazyColumn()
                        .modifier(Modifier.fill_max_width().fill_max_height())
                        .content(move |list| {
                            let count = rows.len();
                            list.items_keyed(
                                count,
                                move |position| keys[position].clone(),
                                move |position| {
                                    task_row(
                                        &rows,
                                        position,
                                        &tasks,
                                        &editing,
                                        &edit_draft,
                                        &menu_open,
                                        &commit_edit,
                                        &swap_tasks,
                                    )
                                },
                            );
                        });
                });
        }
    };

    Scaffold()
        // The three filters are three destinations; the Renderer decides whether they
        // are a bar, a rail or a drawer.
        .bottom_bar(|| {
            Navigation(current_filter.index()).content(|| {
                for choice in Filter::STRIP {
                    let pick = filter.clone();
                    key(choice.label(), || {
                        NavigationItem(choice.label())
                            .icon(choice.icon())
                            .on_click(move || pick.set(choice));
                    });
                }
            });
        })
        .top_bar(|| {
            list_bar(measure, total - remaining, total, || {
                Column().modifier(Modifier.weight(1.0)).content(|| {
                    Text(current_filter.label())
                        .type_role(TypeRole::Headline)
                        .max_lines(1)
                        .overflow(TextOverflow::Ellipsis);
                    Text(if stacked {
                        format!("{remaining} left")
                    } else {
                        format!("{remaining} of {total} remaining")
                    })
                    .type_role(TypeRole::Label)
                    .color(Paint::Role(ColorRole::OnSurfaceVariant))
                    .max_lines(1)
                    .overflow(TextOverflow::Ellipsis);
                });
                // Where the composer is not on the list, the bar is how a task is added.
                if stacked {
                    let open = compose_open.clone();
                    Button("Add")
                        .variant(ButtonVariant::Filled)
                        .on_click(move || open.set(true));
                }
                // Clearing throws work away, so it says so and offers the way back.
                let clearing = tasks.clone();
                Button(if stacked { "Clear" } else { "Clear completed" })
                    .variant(ButtonVariant::Text)
                    .color(Paint::Role(ColorRole::Error))
                    .enabled(total > remaining)
                    .on_click(move || {
                        let before = clearing.get_untracked();
                        let cleared = before.len() - remaining;
                        clearing.update(|tasks| tasks.retain(|task| !task.done));
                        store::save(&clearing.get_untracked());
                        let plural = if cleared == 1 { "task" } else { "tasks" };
                        let restore = clearing.clone();
                        Message::new(format!("Cleared {cleared} completed {plural}"))
                            .with_action("Undo", move |()| {
                                restore.set(before.clone());
                                store::save(&restore.get_untracked());
                            })
                            .with_duration(MessageDuration::Long)
                            .show();
                    });
                let open = about_open.clone();
                Button(ABOUT_LABEL)
                    .variant(ButtonVariant::Text)
                    .on_click(move || open.set(true));
            });
        })
        .content(|| {
            Column()
                .modifier(Modifier.fill_max_width().fill_max_height())
                .content(|| {
                    Box()
                        .modifier(Modifier.fill_max_width().fill_max_height())
                        .alignment(Alignment::TopCenter)
                        .content(|| {
                            Column()
                                .modifier(
                                    Modifier
                                        .fill_max_width_if(measure.is_none())
                                        .width_if(measure)
                                        .fill_max_height()
                                        .padding_role(SpaceRole::Md),
                                )
                                .space_role(SpaceRole::Md)
                                .content(|| {
                                    if !stacked {
                                        composer(Rc::clone(&compose), draft.clone());
                                    }
                                    list();
                                });
                        });

                    // The same composer, arriving from an edge, where there is no room for
                    // it at the head of the list.
                    let close = compose_open.clone();
                    Sheet(compose_open.get() && stacked)
                        .on_dismiss(move || close.set(false))
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
                                            Text("Add Task")
                                                .type_role(TypeRole::Subtitle)
                                                .modifier(Modifier.weight(1.0));
                                            let close = compose_open.clone();
                                            Button("Close")
                                                .variant(ButtonVariant::Filled)
                                                .on_click(move || close.set(false));
                                        });
                                    Separator(None);
                                    if stacked {
                                        composer(Rc::clone(&compose), draft.clone());
                                    }
                                });
                        });

                    // What this sample is, and the one control that belongs to the sample.
                    let dismiss = about_open.clone();
                    Sheet(about_open.get())
                        .on_dismiss(move || dismiss.set(false))
                        .modifier(Modifier.fill_max_width())
                        .content(|| {
                            let fill: Rc<dyn Fn()> = {
                                let (tasks, next_id, about_open) =
                                    (tasks.clone(), next_id.clone(), about_open.clone());
                                Rc::new(move || {
                                    let start = next_id.get_untracked();
                                    tasks.update(|list| {
                                        list.reserve(BULK_COUNT);
                                        for offset in 0..BULK_COUNT as u64 {
                                            let id = start + offset;
                                            list.push(Task {
                                                id,
                                                title: format!("Generated task {id}"),
                                                done: offset % 3 == 0,
                                            });
                                        }
                                    });
                                    next_id.set(start + BULK_COUNT as u64);
                                    store::save(&tasks.get_untracked());
                                    about_open.set(false);
                                })
                            };
                            let close: Rc<dyn Fn()> = {
                                let about_open = about_open.clone();
                                Rc::new(move || about_open.set(false))
                            };
                            about_panel(total, fill, close);
                        });
                });
        });
}

/// One row of the list, composed when the Renderer's window reaches it.
#[composable]
#[allow(clippy::too_many_arguments)]
fn task_row(
    rows: &[usize],
    position: usize,
    tasks: &MutableState<Vec<Task>>,
    editing: &MutableState<Option<u64>>,
    edit_draft: &MutableState<String>,
    menu_open: &MutableState<Option<u64>>,
    commit_edit: &Rc<dyn Fn(String)>,
    swap_tasks: &Rc<dyn Fn(usize, usize)>,
) {
    let index = rows[position];
    let previous = position.checked_sub(1).map(|above| rows[above]);
    let next = rows.get(position + 1).copied();
    let task = tasks.with(|tasks| tasks[index].clone());
    let editing_this = editing.get() == Some(task.id);
    let open = menu_open.get() == Some(task.id);
    let last = position + 1 == rows.len();
    Column().modifier(Modifier.fill_max_width()).content(|| {
        Row()
            .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Sm))
            .space_role(SpaceRole::Sm)
            .alignment(Alignment::CenterStart)
            .content(|| {
                let toggle = tasks.clone();
                let done = task.done;
                Checkbox(task.done).on_change(move |_| {
                    toggle.update(|tasks| tasks[index].done = !done);
                    store::save(&toggle.get_untracked());
                });
                // The title takes the weight, so the actions line up down the list.
                if editing_this {
                    let (typed, submit, lost, saved) = (
                        edit_draft.clone(),
                        Rc::clone(commit_edit),
                        Rc::clone(commit_edit),
                        Rc::clone(commit_edit),
                    );
                    let (lost_draft, saved_draft) = (edit_draft.clone(), edit_draft.clone());
                    TextField()
                        .modifier(Modifier.weight(1.0))
                        .placeholder(task.title.clone())
                        .on_value_change(move |value| typed.set(value))
                        .on_submit(move |value: String| submit(value))
                        .on_focus_lost(move || lost(lost_draft.get_untracked()));
                    Button("Save")
                        .variant(ButtonVariant::Filled)
                        .on_click(move || saved(saved_draft.get_untracked()));
                } else {
                    Text(task.title.clone())
                        .modifier(Modifier.weight(1.0))
                        .type_role(TypeRole::Body)
                        .color(if task.done {
                            Paint::Role(ColorRole::OutlineVariant)
                        } else {
                            Paint::Role(ColorRole::OnSurface)
                        })
                        .max_lines(1)
                        .overflow(TextOverflow::Ellipsis);
                    // What a row can have done to it lives behind one control. The
                    // entries exist only while the menu is open.
                    let (dismiss, opener) = (menu_open.clone(), menu_open.clone());
                    let id = task.id;
                    Menu(open)
                        .on_dismiss(move || dismiss.set(None))
                        .anchor(move || {
                            Button("\u{22ef}")
                                .variant(ButtonVariant::Text)
                                .color(Paint::Role(ColorRole::OnSurfaceVariant))
                                .on_click(move || opener.set(Some(id)));
                        })
                        .content(|| {
                            if open {
                                menu_entries(
                                    &task, index, previous, next, tasks, editing, edit_draft,
                                    menu_open, swap_tasks,
                                );
                            }
                        });
                }
            });
        // The hairline belongs between two rows.
        if !last {
            Separator(None);
        }
    });
}

/// The four things a row can have done to it.
#[allow(clippy::too_many_arguments)]
fn menu_entries(
    task: &Task,
    index: usize,
    previous: Option<usize>,
    next: Option<usize>,
    tasks: &MutableState<Vec<Task>>,
    editing: &MutableState<Option<u64>>,
    edit_draft: &MutableState<String>,
    menu_open: &MutableState<Option<u64>>,
    swap_tasks: &Rc<dyn Fn(usize, usize)>,
) {
    let id = task.id;
    let (close, draft, edit) = (menu_open.clone(), edit_draft.clone(), editing.clone());
    Button("Edit")
        .variant(ButtonVariant::Text)
        .modifier(Modifier.fill_max_width())
        .on_click(move || {
            close.set(None);
            draft.set(String::new());
            edit.set(Some(id));
        });
    let (close, swap) = (menu_open.clone(), Rc::clone(swap_tasks));
    Button("Move up")
        .variant(ButtonVariant::Text)
        .modifier(Modifier.fill_max_width())
        .enabled(previous.is_some())
        .on_click(move || {
            close.set(None);
            if let Some(above) = previous {
                swap(index, above);
            }
        });
    let (close, swap) = (menu_open.clone(), Rc::clone(swap_tasks));
    Button("Move down")
        .variant(ButtonVariant::Text)
        .modifier(Modifier.fill_max_width())
        .enabled(next.is_some())
        .on_click(move || {
            close.set(None);
            if let Some(below) = next {
                swap(index, below);
            }
        });
    // Deleting throws work away without asking, so it says what it did and offers the
    // task back.
    let (close, tasks) = (menu_open.clone(), tasks.clone());
    Button("Delete")
        .variant(ButtonVariant::Text)
        .modifier(Modifier.fill_max_width())
        .color(Paint::Role(ColorRole::Error))
        .on_click(move || {
            close.set(None);
            let mut removed = None;
            tasks.update(|list| removed = Some(list.remove(index)));
            store::save(&tasks.get_untracked());
            let Some(removed) = removed else { return };
            let title = removed.title.clone();
            let restore = tasks.clone();
            Message::new(format!("Deleted \u{201c}{title}\u{201d}"))
                .with_action("Undo", move |()| {
                    let at = index.min(restore.with(Vec::len));
                    restore.update(|list| list.insert(at, removed.clone()));
                    store::save(&restore.get_untracked());
                })
                .with_duration(MessageDuration::Long)
                .show();
        });
}

/// Runs the sample as a program of its own.
pub fn launch() {
    prepare();
    launch_builder().application(app);
}

/// How this sample is configured, in one place because three entry points need it.
fn launch_builder() -> LaunchBuilder {
    LaunchBuilder::new()
        .with_theme(compose_rust::demo_theme())
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Todo")
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

compose_rust::android_application!(
    {
        prepare();
        launch_builder()
    },
    app
);
compose_rust::web_application!(
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
    use compose_rust::ComposeHost;
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
        host: ComposeHost,
        mock: MockRenderer,
        list: u32,
        handler: u64,
        event: Vec<u8>,
    }

    impl Screen {
        fn new() -> Self {
            saved_list();
            let mut host = ComposeHost::new(app);
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
                    class: WindowSizeClass::from_width_dp(width_dp),
                    height_class: WindowHeightClass::from_height_dp(900.0),
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
        assert!(ComposeHost::new(app).rebuild().is_ok());
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
                class: WindowSizeClass::from_width_dp(width_dp),
                height_class: WindowHeightClass::from_height_dp(900.0),
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
        let mut host = ComposeHost::new(app);
        host.rebuild().expect("the first frame failed to encode");
        let event = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: EventPayload::WindowSizeChanged {
                width_dp,
                height_dp: 900.0,
                class: WindowSizeClass::from_width_dp(width_dp),
                height_class: WindowHeightClass::from_height_dp(900.0),
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
                    modifier: compose_rust::schema::Modifier::Width(width),
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
            widths_at(1200.0).contains(&WindowSizeClass::EXPANDED_MIN_WIDTH_DP),
            "the screen did not take a measure"
        );
    }

    /// The bar's contents and the list are held to the same measure, so the title starts
    /// where the list starts.
    #[test]
    fn fr20_the_bar_holds_its_contents_to_the_same_measure_as_the_list() {
        let measure = WindowSizeClass::EXPANDED_MIN_WIDTH_DP;
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
