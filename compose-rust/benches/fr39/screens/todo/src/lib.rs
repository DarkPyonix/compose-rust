//! The todo sample's task list as it was when the benchmark scenarios were frozen: a
//! filtered, windowed list with a composer, a row menu and an About sheet.
//!
//! Measurement fixture. Taken from `samples/todo/src/lib.rs` at fea50dc5, the last commit
//! before e92b33a6 redrew the todo sample on 2026-11-13, and checked widget by widget
//! against the rsx! screen the Dioxus path measures (dioxus-compose dd1af76b,
//! `samples/native-widgets/todo/src/lib.rs`, the same screen compose-rust held at
//! 22822c13). The two built the same tree in every place but one, and there the rsx!
//! screen decided: the field that edits a row in place carries the row's title as its
//! placeholder and starts with no text, where the October port started it with the title
//! as its text. Builder chains became today's function and call macro form, and nothing
//! else changed.
//!
//! It lives in the bench, not in `samples/`, because the comparison needs the same screen
//! on both paths. The Dioxus path is pinned with this screen, and the shipped sample has
//! since been redrawn after its design reference, so it no longer builds the tree the
//! pinned path builds. What is measured here is the authoring path and the runtime under
//! this screen, not the look of today's sample. Change nothing that changes the tree it
//! builds: the comparison only counts runs where both paths build the same tree after
//! every step.
//!
//! The list lives in memory and starts empty. The sample's file, its loading and its saving
//! are left out, because the Dioxus path starts every bench run from a file that does not
//! exist and never writes one. Nothing on the screen shows a saved mark or a date, so
//! nothing else depends on it. The launch, the theme, the window and the platform entry
//! points of the sample are left out too. The bench starts the root composable on a Host of
//! its own and never opens a window.

mod store;

use compose_rust::runtime::*;
use compose_rust::ui::*;
use std::rc::Rc;
use store::{Filter, Task};

/// Enough rows that the window is a small fraction of the list.
const BULK_COUNT: usize = 5_000;

/// What opens the sheet, and what the sheet is called.
const ABOUT_LABEL: &str = "About";

/// The bar across the top of the window, with its contents held to the list's measure.
fn list_bar(measure: Option<f32>, done: usize, total: usize, children: impl FnOnce()) {
    Column!(modifier = Modifier.fill_max_width(), {
        TopAppBar!(modifier = Modifier.fill_max_width(), {
            Box!(
                modifier = Modifier.weight(1.0),
                alignment = Alignment::Center,
                {
                    Row!(
                        modifier = Modifier
                            .width_if(measure)
                            .fill_max_width_if(measure.is_none())
                            .padding_role_if(measure.map(|_| SpaceRole::Md)),
                        space_role = SpaceRole::Sm,
                        alignment = Alignment::CenterStart,
                        {
                            children();
                        }
                    );
                }
            );
        });
        // An empty list has no progress to report.
        if total > 0 {
            ProgressIndicator!(done as f32 / total as f32);
        }
    });
}

/// What this sample is and the one control that only a sample has.
#[Composable]
fn about_panel(total: usize, fill: Rc<dyn Fn()>, close: Rc<dyn Fn()>) {
    Column!(
        modifier = Modifier.fill_max_width(),
        space_role = SpaceRole::Md,
        {
            Row!(
                modifier = Modifier.fill_max_width(),
                alignment = Alignment::CenterStart,
                {
                    Text!(
                        "About this sample",
                        type_role = TypeRole::Subtitle,
                        modifier = Modifier.weight(1.0)
                    );
                    let close = Rc::clone(&close);
                    Button!("Close", move || close(), variant = ButtonVariant::Filled);
                }
            );
            Separator!(None);
            Text!(
                "The list windows its rows: the widgets that exist are the ones on \
                 screen, not the ones in the list. A dozen tasks never exercises \
                 that, so this fills the list with enough rows that the window is a \
                 small fraction of it.",
                type_role = TypeRole::Body,
                color = Paint::Role(ColorRole::OnSurfaceVariant)
            );
            Row!(
                modifier = Modifier.fill_max_width(),
                space_role = SpaceRole::Sm,
                alignment = Alignment::CenterStart,
                {
                    Text!(
                        format!("{total} tasks now"),
                        type_role = TypeRole::Label,
                        color = Paint::Role(ColorRole::OnSurfaceVariant),
                        modifier = Modifier.weight(1.0)
                    );
                    let fill = Rc::clone(&fill);
                    Button!(
                        format!("Add {BULK_COUNT} tasks"),
                        move || fill(),
                        variant = ButtonVariant::Tonal
                    );
                }
            );
        }
    );
}

/// The composer: one grouped strip whose field grows with the window.
#[Composable]
fn composer(add: Rc<dyn Fn(String)>, draft: MutableState<String>) {
    Surface!(modifier = Modifier.fill_max_width(), {
        Row!(
            modifier = Modifier.fill_max_width(),
            space_role = SpaceRole::Sm,
            alignment = Alignment::CenterStart,
            {
                let (typed, submit) = (draft, Rc::clone(&add));
                TextField!(
                    "",
                    move |value| typed.set(value),
                    modifier = Modifier.weight(1.0),
                    placeholder = "Add a task, then press Enter",
                    on_submit = Some(move |value: String| submit(value))
                );
                let (pressed, current) = (Rc::clone(&add), draft);
                Button!(
                    "Add",
                    move || pressed(current.get_untracked()),
                    variant = ButtonVariant::Filled
                );
            }
        );
    });
}

/// The task list.
#[Composable]
pub fn app() {
    let window = current_window_size();
    // Past an expanded window the screen stops widening and centres.
    let measure = if window.is_expanded() {
        Some(WindowSizeClass::EXPANDED_MIN_WIDTH_DP)
    } else {
        None
    };
    let stacked = window.is_compact();
    let tasks = remember(|| mutable_state_of_with_policy(Vec::<Task>::new(), never_equal_policy()));
    let next_id = remember(|| mutable_state_of(1_u64));
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
        let (tasks, next_id, draft) = (tasks, next_id, draft);
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
        })
    };

    let commit_edit: Rc<dyn Fn(String)> = {
        let (tasks, editing, edit_draft) = (tasks, editing, edit_draft);
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
        })
    };

    // Reordering is expressed in what the user can see; each row works out its two
    // neighbours and this only performs the swap.
    let swap_tasks: Rc<dyn Fn(usize, usize)> = {
        Rc::new(move |from: usize, to: usize| {
            tasks.update(|tasks| tasks.swap(from, to));
        })
    };

    // One way in for a new task, whether the field is on the list or in the sheet.
    let compose: Rc<dyn Fn(String)> = {
        let (add, compose_open) = (Rc::clone(&add), compose_open);
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

    let list_view = || {
        // An empty list explains itself rather than leaving a blank half window.
        if rows.is_empty() {
            Box!(
                modifier = Modifier.fill_max_width().weight(1.0),
                alignment = Alignment::Center,
                {
                    Text!(
                        match current_filter {
                            Filter::All => "No tasks yet. Add one above.",
                            Filter::Active => "Nothing left to do under this filter.",
                            Filter::Done => "Nothing has been completed yet.",
                        },
                        type_role = TypeRole::Body,
                        color = Paint::Role(ColorRole::OnSurfaceVariant)
                    );
                }
            );
        } else {
            // One grouped container, not a stack of cards; a hairline between rows.
            Surface!(modifier = Modifier.fill_max_width().weight(1.0), {
                let rows = rows.clone();
                let keys = keys.clone();
                let (tasks, editing, edit_draft, menu_open) =
                    (tasks, editing, edit_draft, menu_open);
                let (commit_edit, swap_tasks) = (Rc::clone(&commit_edit), Rc::clone(&swap_tasks));
                LazyColumn!(
                    modifier = Modifier.fill_max_width().fill_max_height(),
                    content = move |list| {
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
                    }
                );
            });
        }
    };

    Scaffold!(
        // The three filters are three destinations; the Renderer decides whether they
        // are a bar, a rail or a drawer.
        bottom_bar = Some(|| {
            Navigation!(current_filter.index(), {
                for choice in Filter::STRIP {
                    let pick = filter;
                    key(choice.label(), || {
                        NavigationItem!(
                            choice.label(),
                            move || pick.set(choice),
                            icon = choice.icon()
                        );
                    });
                }
            });
        }),
        top_bar = Some(|| {
            list_bar(measure, total - remaining, total, || {
                Column!(modifier = Modifier.weight(1.0), {
                    Text!(
                        current_filter.label(),
                        type_role = TypeRole::Headline,
                        max_lines = 1,
                        overflow = TextOverflow::Ellipsis
                    );
                    Text!(
                        if stacked {
                            format!("{remaining} left")
                        } else {
                            format!("{remaining} of {total} remaining")
                        },
                        type_role = TypeRole::Label,
                        color = Paint::Role(ColorRole::OnSurfaceVariant),
                        max_lines = 1,
                        overflow = TextOverflow::Ellipsis
                    );
                });
                // Where the composer is not on the list, the bar is how a task is added.
                if stacked {
                    let open = compose_open;
                    Button!(
                        "Add",
                        move || open.set(true),
                        variant = ButtonVariant::Filled
                    );
                }
                // Clearing throws work away, so it says so and offers the way back.
                let clearing = tasks;
                Button!(
                    if stacked { "Clear" } else { "Clear completed" },
                    move || {
                        let before = clearing.get_untracked();
                        let cleared = before.len() - remaining;
                        clearing.update(|tasks| tasks.retain(|task| !task.done));
                        let plural = if cleared == 1 { "task" } else { "tasks" };
                        let restore = clearing;
                        Message::new(format!("Cleared {cleared} completed {plural}"))
                            .with_action("Undo", move |()| {
                                restore.set(before.clone());
                            })
                            .with_duration(MessageDuration::Long)
                            .show();
                    },
                    variant = ButtonVariant::Text,
                    color = Paint::Role(ColorRole::Error),
                    enabled = total > remaining
                );
                let open = about_open;
                Button!(
                    ABOUT_LABEL,
                    move || open.set(true),
                    variant = ButtonVariant::Text
                );
            });
        }),
        {
            Column!(modifier = Modifier.fill_max_width().fill_max_height(), {
                Box!(
                    modifier = Modifier.fill_max_width().fill_max_height(),
                    alignment = Alignment::TopCenter,
                    {
                        Column!(
                            modifier = Modifier
                                .fill_max_width_if(measure.is_none())
                                .width_if(measure)
                                .fill_max_height()
                                .padding_role(SpaceRole::Md),
                            space_role = SpaceRole::Md,
                            {
                                if !stacked {
                                    composer(Rc::clone(&compose), draft);
                                }
                                list_view();
                            }
                        );
                    }
                );

                // The same composer, arriving from an edge, where there is no room for
                // it at the head of the list.
                let close = compose_open;
                Sheet!(
                    compose_open.get() && stacked,
                    move || close.set(false),
                    modifier = Modifier.fill_max_width(),
                    {
                        Column!(
                            modifier = Modifier.fill_max_width(),
                            space_role = SpaceRole::Md,
                            {
                                Row!(
                                    modifier = Modifier.fill_max_width(),
                                    alignment = Alignment::CenterStart,
                                    {
                                        Text!(
                                            "Add Task",
                                            type_role = TypeRole::Subtitle,
                                            modifier = Modifier.weight(1.0)
                                        );
                                        let close = compose_open;
                                        Button!(
                                            "Close",
                                            move || close.set(false),
                                            variant = ButtonVariant::Filled
                                        );
                                    }
                                );
                                Separator!(None);
                                if stacked {
                                    composer(Rc::clone(&compose), draft);
                                }
                            }
                        );
                    }
                );

                // What this sample is, and the one control that belongs to the sample.
                let dismiss = about_open;
                Sheet!(
                    about_open.get(),
                    move || dismiss.set(false),
                    modifier = Modifier.fill_max_width(),
                    {
                        let fill: Rc<dyn Fn()> = {
                            let (tasks, next_id, about_open) = (tasks, next_id, about_open);
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
                                about_open.set(false);
                            })
                        };
                        let close: Rc<dyn Fn()> = { Rc::new(move || about_open.set(false)) };
                        about_panel(total, fill, close);
                    }
                );
            });
        }
    );
}

/// One row of the list, composed when the Renderer's window reaches it.
#[Composable]
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
    Column!(modifier = Modifier.fill_max_width(), {
        Row!(
            modifier = Modifier.fill_max_width().padding_role(SpaceRole::Sm),
            space_role = SpaceRole::Sm,
            alignment = Alignment::CenterStart,
            {
                let toggle = *tasks;
                let done = task.done;
                Checkbox!(task.done, move |_| {
                    toggle.update(|tasks| tasks[index].done = !done);
                });
                // The title takes the weight, so the actions line up down the list.
                if editing_this {
                    let (typed, submit, lost, saved) = (
                        *edit_draft,
                        Rc::clone(commit_edit),
                        Rc::clone(commit_edit),
                        Rc::clone(commit_edit),
                    );
                    let (lost_draft, saved_draft) = (*edit_draft, *edit_draft);
                    // The field starts empty and shows the title as its placeholder.
                    TextField!(
                        "",
                        move |value| typed.set(value),
                        modifier = Modifier.weight(1.0),
                        placeholder = task.title.clone(),
                        on_submit = Some(move |value: String| submit(value)),
                        on_focus_lost = Some(move || lost(lost_draft.get_untracked()))
                    );
                    Button!(
                        "Save",
                        move || saved(saved_draft.get_untracked()),
                        variant = ButtonVariant::Filled
                    );
                } else {
                    Text!(
                        task.title.clone(),
                        modifier = Modifier.weight(1.0),
                        type_role = TypeRole::Body,
                        color = if task.done {
                            Paint::Role(ColorRole::OutlineVariant)
                        } else {
                            Paint::Role(ColorRole::OnSurface)
                        },
                        max_lines = 1,
                        overflow = TextOverflow::Ellipsis
                    );
                    // What a row can have done to it lives behind one control. The
                    // entries exist only while the menu is open.
                    let (dismiss, opener) = (*menu_open, *menu_open);
                    let id = task.id;
                    Menu!(
                        open,
                        move || dismiss.set(None),
                        anchor = Some(move || {
                            Button!(
                                "\u{22ef}",
                                move || opener.set(Some(id)),
                                variant = ButtonVariant::Text,
                                color = Paint::Role(ColorRole::OnSurfaceVariant)
                            );
                        }),
                        {
                            if open {
                                menu_entries(
                                    &task, index, previous, next, tasks, editing, edit_draft,
                                    menu_open, swap_tasks,
                                );
                            }
                        }
                    );
                }
            }
        );
        // The hairline belongs between two rows.
        if !last {
            Separator!(None);
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
    let (close, draft, edit) = (*menu_open, *edit_draft, *editing);
    Button!(
        "Edit",
        move || {
            close.set(None);
            draft.set(String::new());
            edit.set(Some(id));
        },
        variant = ButtonVariant::Text,
        modifier = Modifier.fill_max_width()
    );
    let (close, swap) = (*menu_open, Rc::clone(swap_tasks));
    Button!(
        "Move up",
        move || {
            close.set(None);
            if let Some(above) = previous {
                swap(index, above);
            }
        },
        variant = ButtonVariant::Text,
        modifier = Modifier.fill_max_width(),
        enabled = previous.is_some()
    );
    let (close, swap) = (*menu_open, Rc::clone(swap_tasks));
    Button!(
        "Move down",
        move || {
            close.set(None);
            if let Some(below) = next {
                swap(index, below);
            }
        },
        variant = ButtonVariant::Text,
        modifier = Modifier.fill_max_width(),
        enabled = next.is_some()
    );
    // Deleting throws work away without asking, so it says what it did and offers the
    // task back.
    let (close, tasks) = (*menu_open, *tasks);
    Button!(
        "Delete",
        move || {
            close.set(None);
            let mut removed = None;
            tasks.update(|list| removed = Some(list.remove(index)));
            let Some(removed) = removed else { return };
            let title = removed.title.clone();
            let restore = tasks;
            Message::new(format!("Deleted \u{201c}{title}\u{201d}"))
                .with_action("Undo", move |()| {
                    let at = index.min(restore.with(Vec::len));
                    restore.update(|list| list.insert(at, removed.clone()));
                })
                .with_duration(MessageDuration::Long)
                .show();
        },
        variant = ButtonVariant::Text,
        modifier = Modifier.fill_max_width(),
        color = Paint::Role(ColorRole::Error)
    );
}
