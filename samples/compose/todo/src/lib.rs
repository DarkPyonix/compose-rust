//! The todo sample, written with compose-rust's own API.
//!
//! The same screen as `samples/todo`, call for call, over the same store: add, edit in
//! place, complete, delete, reorder, filter, and a five thousand row list that only
//! composes the rows on screen.

use compose_rust::runtime::*;
use compose_rust::ui::*;
use std::rc::Rc;

#[path = "../../../todo/src/store.rs"]
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

/// The same window as the other todo sample.
fn launch_builder() -> LaunchBuilder {
    LaunchBuilder::new()
        .with_theme(compose_rust::demo_theme())
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Todo")
                .with_icon(compose_rust::asset::asset(
                    compose_rust::schema::AssetKind::Png,
                    include_bytes!("../../../todo/assets/icon.png"),
                )),
        )
}

/// What has to happen before the first frame, on every platform.
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
