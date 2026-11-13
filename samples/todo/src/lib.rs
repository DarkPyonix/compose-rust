//! A task list: add, edit in place, complete, delete, reorder, filter, search, keep tasks in
//! lists of their own, and survive a restart, or a reload of the page in a browser, through
//! the application's storage.
//!
//! The screen follows a phone task manager's references. A narrow window has no bar of
//! destinations: the list of lists is a screen of its own, reached from a back button
//! floating at the page's bottom left, and a button floating at the bottom right opens the
//! Add Task sheet. A wider window keeps the destinations in the design system's rail or
//! drawer and the composer at the head of the list.
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

use store::{Filter, NO_LIST, Task, TaskList, View};

/// Enough rows that the window is a small fraction of the list.
const BULK_COUNT: usize = 5_000;

/// What opens the sheet, and what the sheet is called.
const ABOUT_LABEL: &str = "About";

/// What stands where the list will be while the saved list is read.
const LOADING_LABEL: &str = "Loading tasks";

/// The destination that opens the search sheet rather than a list.
const SEARCH_LABEL: &str = "Search";

/// What makes a list, at the foot of the destinations.
const NEW_LIST_LABEL: &str = "New list";

/// The heading the user's own lists sit under, and the name of the screen that lists
/// every destination in a narrow window.
const LISTS_LABEL: &str = "Lists";

/// The names of the two floating buttons, read by assistive technology since neither has a
/// visible label.
const SHOW_LISTS: &str = "Show lists";
const ADD_TASK: &str = "Add task";

/// The headings of a view that holds both kinds of task.
const TO_DO_SECTION: &str = "To do";
const COMPLETED_SECTION: &str = "Completed";

/// Where the destinations sit in the `Navigation`: Search, then the three filters, then
/// the user's lists.
const FIRST_FILTER: usize = 1;
const FIRST_LIST: usize = FIRST_FILTER + Filter::STRIP.len();

/// The tasks and the lists, which are saved together.
#[derive(Clone, Copy)]
struct Data {
    tasks: MutableState<Vec<Task>>,
    lists: MutableState<Vec<TaskList>>,
}

impl Data {
    /// Hands both to storage. Returns without waiting.
    fn save(self) {
        store::save(&self.tasks.get_untracked(), &self.lists.get_untracked());
    }
}

/// A run of rows under one heading, or under none.
struct Section {
    title: Option<&'static str>,
    rows: Vec<usize>,
}

/// What this sample is and the one control that only a sample has.
#[Composable]
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

/// The composer at the head of the list in a window with room for it: one grouped strip
/// whose field grows with the window.
#[Composable]
fn composer(add: Rc<dyn Fn(String)>, draft: MutableState<String>) {
    Surface().modifier(Modifier.fill_max_width()).content(|| {
        Row()
            .modifier(Modifier.fill_max_width())
            .space_role(SpaceRole::Sm)
            .alignment(Alignment::CenterStart)
            .content(|| {
                let (typed, submit) = (draft, Rc::clone(&add));
                TextField()
                    .modifier(Modifier.weight(1.0))
                    .placeholder("Add a task, then press Enter")
                    .on_value_change(move |value| typed.set(value))
                    .on_submit(move |value: String| submit(value));
                let (pressed, current) = (Rc::clone(&add), draft);
                Button("Add")
                    .variant(ButtonVariant::Filled)
                    .on_click(move || pressed(current.get_untracked()));
            });
    });
}

/// The Add Task sheet of a narrow window, as the reference draws it: a large title, the
/// task and its note, and a close and an add button at the sheet's foot.
#[Composable]
fn add_task_panel(
    list_name: Option<String>,
    compose: Rc<dyn Fn(String)>,
    draft: MutableState<String>,
    note_draft: MutableState<String>,
    close: Rc<dyn Fn()>,
) {
    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("Add Task").type_role(TypeRole::Headline);
            // A task added on a list's page goes into that list, and the sheet says so.
            if let Some(list_name) = list_name.clone() {
                Text(format!("In {list_name}"))
                    .type_role(TypeRole::Label)
                    .color(Paint::Role(ColorRole::OnSurfaceVariant));
            }
            Separator(None);
            let (typed, submit) = (draft, Rc::clone(&compose));
            TextField()
                .modifier(Modifier.fill_max_width())
                .placeholder("What is there to do?")
                .on_value_change(move |value| typed.set(value))
                .on_submit(move |value: String| submit(value));
            let noted = note_draft;
            TextField()
                .modifier(Modifier.fill_max_width())
                .multiline(true)
                .placeholder("Note")
                .on_value_change(move |value| noted.set(value));
            Row()
                .modifier(Modifier.fill_max_width())
                .arrangement(Arrangement::SpaceBetween)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    let close = Rc::clone(&close);
                    Button("")
                        .icon(IconRole::Close)
                        .variant(ButtonVariant::Tonal)
                        .accessible_name("Close")
                        .on_click(move || close());
                    let (pressed, current) = (Rc::clone(&compose), draft);
                    Button("")
                        .icon(IconRole::Add)
                        .variant(ButtonVariant::Tonal)
                        .accessible_name(ADD_TASK)
                        .on_click(move || pressed(current.get_untracked()));
                });
        });
}

/// The two buttons floating at the foot of a narrow window's page: back to the list of
/// lists on the left, and a new task on the right.
#[Composable]
fn floating_actions(
    lists_open: MutableState<bool>,
    compose_open: MutableState<bool>,
    can_add: bool,
) {
    Row()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .arrangement(Arrangement::SpaceBetween)
        .alignment(Alignment::CenterStart)
        .content(|| {
            Button("")
                .icon(IconRole::Back)
                .variant(ButtonVariant::Tonal)
                .accessible_name(SHOW_LISTS)
                .on_click(move || lists_open.set(true));
            Button("")
                .icon(IconRole::Add)
                .variant(ButtonVariant::Tonal)
                .accessible_name(ADD_TASK)
                .enabled(can_add)
                .on_click(move || compose_open.set(true));
        });
}

/// One destination on the list of lists: its name, which picks it, and how many tasks it
/// holds.
#[Composable]
fn destination_row(label: String, icon: IconRole, count: usize, current: bool, pick: Rc<dyn Fn()>) {
    Row()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Sm)
        .alignment(Alignment::CenterStart)
        .content(|| {
            Button(label)
                .icon(icon)
                .variant(ButtonVariant::Text)
                .text_align(TextAlign::Start)
                .color(Paint::Role(if current {
                    ColorRole::Primary
                } else {
                    ColorRole::OnSurface
                }))
                .modifier(Modifier.weight(1.0))
                .on_click(move || pick());
            Text(count.to_string())
                .type_role(TypeRole::Label)
                .color(Paint::Role(ColorRole::OnSurfaceVariant));
        });
}

/// The list of lists a narrow window shows in place of the page: search, every
/// destination with what it holds, and a new list at the foot. Picking a destination goes
/// back to its page.
#[Composable]
fn lists_screen(
    current: View,
    filter_counts: [usize; 3],
    lists: Vec<(TaskList, usize)>,
    pick: Rc<dyn Fn(View)>,
    open_search: Rc<dyn Fn()>,
    new_list: Rc<dyn Fn()>,
) {
    Column()
        .modifier(
            Modifier
                .fill_max_width()
                .fill_max_height()
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Sm)
        .content(|| {
            let search = Rc::clone(&open_search);
            Button(SEARCH_LABEL)
                .icon(IconRole::Search)
                .variant(ButtonVariant::Tonal)
                .text_align(TextAlign::Start)
                .modifier(Modifier.fill_max_width())
                .on_click(move || search());
            ScrollColumn()
                .modifier(Modifier.fill_max_width().weight(1.0))
                .content(|| {
                    for choice in Filter::STRIP {
                        key(choice.label(), || {
                            let pick = Rc::clone(&pick);
                            destination_row(
                                choice.label().to_owned(),
                                choice.icon(),
                                filter_counts[choice.index()],
                                current == View::Filter(choice),
                                Rc::new(move || pick(View::Filter(choice))),
                            );
                        });
                    }
                    if !lists.is_empty() {
                        Separator(None);
                        Text(LISTS_LABEL)
                            .type_role(TypeRole::Label)
                            .color(Paint::Role(ColorRole::OnSurfaceVariant));
                        for (list, count) in lists.iter().cloned() {
                            key(format!("list {}", list.id), || {
                                let pick = Rc::clone(&pick);
                                let id = list.id;
                                destination_row(
                                    list.name,
                                    IconRole::List,
                                    count,
                                    current == View::List(id),
                                    Rc::new(move || pick(View::List(id))),
                                );
                            });
                        }
                    }
                });
            let new_list = Rc::clone(&new_list);
            Button(NEW_LIST_LABEL)
                .icon(IconRole::Add)
                .variant(ButtonVariant::Text)
                .on_click(move || new_list());
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
    // Saves run in this list's scope, and stop with it.
    let saves = remember_coroutine_scope();
    store::attach(saves);
    let tasks = remember(|| mutable_state_of_with_policy(Vec::<Task>::new(), never_equal_policy()));
    let lists =
        remember(|| mutable_state_of_with_policy(Vec::<TaskList>::new(), never_equal_policy()));
    let data = Data { tasks, lists };
    let next_id = remember(|| mutable_state_of(1_u64));
    let next_list_id = remember(|| mutable_state_of(1_u64));
    // The saved list is read once, by an effect, so the UI thread never waits for it. Until
    // it arrives the list says it is loading rather than showing an empty list that would
    // then fill, and nothing can be added to a list that is not there yet.
    let loading = remember(|| mutable_state_of(store::started()));
    launched_effect((), move || async move {
        if !loading.get_untracked() {
            return;
        }
        let saved = store::load().await;
        next_id.set(
            saved
                .tasks
                .iter()
                .map(|task| task.id + 1)
                .max()
                .unwrap_or(1),
        );
        next_list_id.set(
            saved
                .lists
                .iter()
                .map(|list| list.id + 1)
                .max()
                .unwrap_or(1),
        );
        tasks.set(saved.tasks);
        lists.set(saved.lists);
        loading.set(false);
    });
    let still_loading = loading.get();
    let view = remember(|| mutable_state_of(View::Filter(Filter::All)));
    let query = remember(|| mutable_state_of(String::new()));
    let about_open = remember(|| mutable_state_of(false));
    let compose_open = remember(|| mutable_state_of(false));
    let search_open = remember(|| mutable_state_of(false));
    let new_list_open = remember(|| mutable_state_of(false));
    let bar_menu_open = remember(|| mutable_state_of(false));
    // The list of lists, standing in for the page in a narrow window.
    let lists_open = remember(|| mutable_state_of(false));
    // What the fields currently hold: copies the fields push up, never pushed down.
    let draft = remember(|| mutable_state_of_with_policy(String::new(), never_equal_policy()));
    let note_draft = remember(|| mutable_state_of_with_policy(String::new(), never_equal_policy()));
    let list_draft = remember(|| mutable_state_of_with_policy(String::new(), never_equal_policy()));
    // The task being edited in place, and the text its editor currently holds.
    let editing = remember(|| mutable_state_of(Option::<u64>::None));
    let edit_draft = remember(|| mutable_state_of_with_policy(String::new(), never_equal_policy()));
    let menu_open = remember(|| mutable_state_of(Option::<u64>::None));

    let all_lists = lists.get();
    // A list that is gone leaves its page for All.
    let current_view = match view.get() {
        View::List(id) if !all_lists.iter().any(|list| list.id == id) => View::Filter(Filter::All),
        other => other,
    };
    let list_name = |id: u64| {
        all_lists
            .iter()
            .find(|list| list.id == id)
            .map(|list| list.name.clone())
    };
    let view_title = match current_view {
        View::Filter(filter) => filter.label().to_owned(),
        View::List(id) => list_name(id).unwrap_or_default(),
    };
    let on_lists_screen = stacked && lists_open.get();

    let searched = query.get();
    let needle = searched.trim().to_lowercase();
    let visible: Vec<usize> = tasks.with(|tasks| {
        tasks
            .iter()
            .enumerate()
            .filter(|(_, task)| {
                current_view.accepts(task)
                    && (needle.is_empty()
                        || task.title.to_lowercase().contains(&needle)
                        || task.note.to_lowercase().contains(&needle))
            })
            .map(|(index, _)| index)
            .collect()
    });
    // A view holding tasks still to do and tasks already done heads each kind; a view of
    // one kind needs no heading, because its title already says which.
    let sections: Vec<Section> = tasks.with(|tasks| {
        let (to_do, completed): (Vec<usize>, Vec<usize>) =
            visible.iter().partition(|index| !tasks[**index].done);
        if to_do.is_empty() || completed.is_empty() {
            vec![Section {
                title: None,
                rows: visible.clone(),
            }]
        } else {
            vec![
                Section {
                    title: Some(TO_DO_SECTION),
                    rows: to_do,
                },
                Section {
                    title: Some(COMPLETED_SECTION),
                    rows: completed,
                },
            ]
        }
    });
    let (remaining, total, completed_here) = tasks.with(|tasks| {
        let here = tasks.iter().filter(|task| current_view.accepts(task));
        let (total, done) = here.fold((0, 0), |(total, done), task| {
            (total + 1, done + usize::from(task.done))
        });
        (total - done, total, done)
    });
    let filter_counts = tasks.with(|tasks| {
        Filter::STRIP.map(|filter| tasks.iter().filter(|task| filter.accepts(task)).count())
    });
    let list_counts: Vec<(TaskList, usize)> = tasks.with(|tasks| {
        all_lists
            .iter()
            .map(|list| {
                let count = tasks.iter().filter(|task| task.list == list.id).count();
                (list.clone(), count)
            })
            .collect()
    });

    let add: Rc<dyn Fn(String, String)> = {
        let (next_id, draft, note_draft) = (next_id, draft, note_draft);
        Rc::new(move |title: String, note: String| {
            let title = title.trim().to_owned();
            if title.is_empty() || loading.get_untracked() {
                return;
            }
            let id = next_id.get_untracked();
            next_id.set(id + 1);
            let list = view.get_untracked().list();
            tasks.update(|tasks| {
                tasks.push(Task {
                    id,
                    title,
                    done: false,
                    list,
                    note: note.trim().to_owned(),
                })
            });
            draft.set(String::new());
            note_draft.set(String::new());
            data.save();
        })
    };

    let commit_edit: Rc<dyn Fn(String)> = {
        let (editing, edit_draft) = (editing, edit_draft);
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
            data.save();
        })
    };

    // Reordering is expressed in what the user can see; each row works out its two
    // neighbours and this only performs the swap.
    let swap_tasks: Rc<dyn Fn(usize, usize)> = {
        Rc::new(move |from: usize, to: usize| {
            tasks.update(|tasks| tasks.swap(from, to));
            data.save();
        })
    };

    // One way in for a new task, whether the field is on the list or in the sheet.
    let compose: Rc<dyn Fn(String)> = {
        let (add, compose_open, note_draft) = (Rc::clone(&add), compose_open, note_draft);
        Rc::new(move |title: String| {
            add(title, note_draft.get_untracked());
            compose_open.set(false);
        })
    };

    // Going to a destination, from the rail, the drawer or the list of lists.
    let pick: Rc<dyn Fn(View)> = Rc::new(move |next: View| {
        view.set(next);
        lists_open.set(false);
        menu_open.set(None);
    });

    let open_search: Rc<dyn Fn()> = Rc::new(move || {
        search_open.set(true);
        // The results are on the page, so the list of lists steps aside for them.
        lists_open.set(false);
    });

    let open_new_list: Rc<dyn Fn()> = Rc::new(move || new_list_open.set(true));

    let create_list: Rc<dyn Fn(String)> = {
        let (next_list_id, list_draft) = (next_list_id, list_draft);
        Rc::new(move |name: String| {
            let name = name.trim().to_owned();
            if name.is_empty() || loading.get_untracked() {
                return;
            }
            let id = next_list_id.get_untracked();
            next_list_id.set(id + 1);
            lists.update(|lists| lists.push(TaskList { id, name }));
            view.set(View::List(id));
            lists_open.set(false);
            new_list_open.set(false);
            list_draft.set(String::new());
            data.save();
        })
    };

    let keys: Vec<Vec<String>> = tasks.with(|tasks| {
        sections
            .iter()
            .map(|section| {
                section
                    .rows
                    .iter()
                    .map(|index| tasks[*index].id.to_string())
                    .collect()
            })
            .collect()
    });
    // A task kept in a list says which, except on that list's own page.
    let show_list_names = matches!(current_view, View::Filter(_));
    let names: Rc<Vec<TaskList>> = Rc::new(if show_list_names {
        all_lists.clone()
    } else {
        Vec::new()
    });

    let list = || {
        if still_loading {
            Box()
                .modifier(Modifier.fill_max_width().weight(1.0))
                .alignment(Alignment::Center)
                .content(|| {
                    Text(LOADING_LABEL)
                        .type_role(TypeRole::Body)
                        .color(Paint::Role(ColorRole::OnSurfaceVariant));
                });
        } else if visible.is_empty() {
            // An empty list explains itself rather than leaving a blank half window.
            Box()
                .modifier(Modifier.fill_max_width().weight(1.0))
                .alignment(Alignment::Center)
                .content(|| {
                    Text(if !needle.is_empty() {
                        "No task matches the search."
                    } else {
                        match current_view {
                            View::Filter(Filter::All) => "No tasks yet. Add one above.",
                            View::Filter(Filter::Active) => "Nothing left to do under this filter.",
                            View::Filter(Filter::Done) => "Nothing has been completed yet.",
                            View::List(_) => "This list is empty. Add a task to it.",
                        }
                    })
                    .type_role(TypeRole::Body)
                    .color(Paint::Role(ColorRole::OnSurfaceVariant));
                });
        } else {
            // A card for each task, as the reference draws them, under a heading for each
            // kind when the view holds both.
            let runs: Vec<(Option<&'static str>, Rc<Vec<usize>>, Rc<Vec<String>>)> = sections
                .iter()
                .zip(keys.iter())
                .map(|(section, keys)| {
                    (
                        section.title,
                        Rc::new(section.rows.clone()),
                        Rc::new(keys.clone()),
                    )
                })
                .collect();
            let (editing, edit_draft, menu_open) = (editing, edit_draft, menu_open);
            let (commit_edit, swap_tasks) = (Rc::clone(&commit_edit), Rc::clone(&swap_tasks));
            let names = Rc::clone(&names);
            LazyColumn()
                .modifier(Modifier.fill_max_width().weight(1.0))
                .content(move |list| {
                    for (title, rows, keys) in runs {
                        if let Some(title) = title {
                            list.items_keyed(
                                1,
                                move |_| format!("section {title}"),
                                move |_| section_heading(title),
                            );
                        }
                        let count = rows.len();
                        let (commit_edit, swap_tasks, names) = (
                            Rc::clone(&commit_edit),
                            Rc::clone(&swap_tasks),
                            Rc::clone(&names),
                        );
                        list.items_keyed(
                            count,
                            move |position| keys[position].clone(),
                            move |position| {
                                task_row(
                                    &rows,
                                    position,
                                    &data,
                                    &names,
                                    &editing,
                                    &edit_draft,
                                    &menu_open,
                                    &commit_edit,
                                    &swap_tasks,
                                )
                            },
                        );
                    }
                });
        }
    };

    let (title, second_line) = if on_lists_screen {
        (LISTS_LABEL.to_owned(), None)
    } else if !needle.is_empty() {
        (
            view_title.clone(),
            Some(format!(
                "{} matching \u{201c}{}\u{201d}",
                visible.len(),
                searched.trim()
            )),
        )
    } else {
        (
            view_title.clone(),
            Some(format!("{remaining} of {total} remaining")),
        )
    };

    let selected = match current_view {
        View::Filter(filter) => FIRST_FILTER + filter.index(),
        View::List(id) => {
            FIRST_LIST
                + all_lists
                    .iter()
                    .position(|list| list.id == id)
                    .unwrap_or_default()
        }
    };

    Scaffold()
        // The destinations are one set; the Renderer decides whether they are a rail or a
        // drawer.
        .bottom_bar(|| {
            // The reference has no bar in a narrow window: the list of lists is a screen of
            // its own, reached from a button floating over the page.
            Navigation(selected)
                .compact(CompactNavigation::None)
                .foot(|| {
                    let new_list = Rc::clone(&open_new_list);
                    Button(NEW_LIST_LABEL)
                        .icon(IconRole::Add)
                        .variant(ButtonVariant::Text)
                        .on_click(move || new_list());
                })
                .content(|| {
                    let search = Rc::clone(&open_search);
                    NavigationItem(SEARCH_LABEL)
                        .icon(IconRole::Search)
                        .on_click(move || search());
                    for choice in Filter::STRIP {
                        let pick = Rc::clone(&pick);
                        key(choice.label(), || {
                            NavigationItem(choice.label())
                                .icon(choice.icon())
                                .on_click(move || pick(View::Filter(choice)));
                        });
                    }
                    for list in all_lists.iter() {
                        let pick = Rc::clone(&pick);
                        let id = list.id;
                        key(format!("list {id}"), || {
                            NavigationItem(list.name.clone())
                                .icon(IconRole::List)
                                .section(LISTS_LABEL)
                                .on_click(move || pick(View::List(id)));
                        });
                    }
                });
        })
        .top_bar(|| {
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
                            .content(|| {
                                // Two lines, as the reference heads every list: what is on
                                // screen, and a second line nearly as large.
                                Column().modifier(Modifier.weight(1.0)).content(|| {
                                    Text(title.clone())
                                        .type_role(TypeRole::Headline)
                                        .max_lines(1)
                                        .overflow(TextOverflow::Ellipsis);
                                    if let Some(second_line) = second_line.clone() {
                                        Text(second_line)
                                            .type_role(TypeRole::Title)
                                            .max_lines(1)
                                            .overflow(TextOverflow::Ellipsis);
                                    }
                                });
                                bar_menu(
                                    current_view,
                                    completed_here,
                                    data,
                                    view,
                                    bar_menu_open,
                                    about_open,
                                );
                            });
                    });
            });
        })
        .content(|| {
            Column()
                .modifier(Modifier.fill_max_width().fill_max_height())
                .content(|| {
                    Box()
                        .modifier(Modifier.fill_max_width().fill_max_height())
                        .alignment(Alignment::BottomCenter)
                        .content(|| {
                            if on_lists_screen {
                                lists_screen(
                                    current_view,
                                    filter_counts,
                                    list_counts.clone(),
                                    Rc::clone(&pick),
                                    Rc::clone(&open_search),
                                    Rc::clone(&open_new_list),
                                );
                            } else {
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
                                            composer(Rc::clone(&compose), draft);
                                        }
                                        list();
                                    });
                                // Over the page's foot, where a thumb reaches, the way back
                                // to the list of lists and the way to a new task.
                                if stacked {
                                    floating_actions(lists_open, compose_open, !still_loading);
                                }
                            }
                        });

                    // Composing, arriving from an edge, where there is no room for it at
                    // the head of the list.
                    let close = compose_open;
                    Sheet(compose_open.get() && stacked)
                        .on_dismiss(move || close.set(false))
                        .modifier(Modifier.fill_max_width())
                        .content(|| {
                            if stacked {
                                let close: Rc<dyn Fn()> = Rc::new(move || compose_open.set(false));
                                let destination = match current_view {
                                    View::List(id) => list_name(id),
                                    View::Filter(_) => None,
                                };
                                add_task_panel(
                                    destination,
                                    Rc::clone(&compose),
                                    draft,
                                    note_draft,
                                    close,
                                );
                            }
                        });

                    // Search narrows the page it was opened over to what matches.
                    let close = search_open;
                    Sheet(search_open.get())
                        .on_dismiss(move || close.set(false))
                        .modifier(Modifier.fill_max_width())
                        .content(|| {
                            if search_open.get() {
                                search_panel(query, search_open);
                            }
                        });

                    let close = new_list_open;
                    Sheet(new_list_open.get())
                        .on_dismiss(move || close.set(false))
                        .modifier(Modifier.fill_max_width())
                        .content(|| {
                            if new_list_open.get() {
                                new_list_panel(Rc::clone(&create_list), list_draft, new_list_open);
                            }
                        });

                    // What this sample is, and the one control that belongs to the sample.
                    let dismiss = about_open;
                    Sheet(about_open.get())
                        .on_dismiss(move || dismiss.set(false))
                        .modifier(Modifier.fill_max_width())
                        .content(|| {
                            let fill: Rc<dyn Fn()> = {
                                let (next_id, about_open) = (next_id, about_open);
                                Rc::new(move || {
                                    if loading.get_untracked() {
                                        return;
                                    }
                                    let start = next_id.get_untracked();
                                    tasks.update(|list| {
                                        list.reserve(BULK_COUNT);
                                        for offset in 0..BULK_COUNT as u64 {
                                            let id = start + offset;
                                            list.push(Task {
                                                id,
                                                title: format!("Generated task {id}"),
                                                done: offset % 3 == 0,
                                                ..Task::default()
                                            });
                                        }
                                    });
                                    next_id.set(start + BULK_COUNT as u64);
                                    data.save();
                                    about_open.set(false);
                                })
                            };
                            let close: Rc<dyn Fn()> = { Rc::new(move || about_open.set(false)) };
                            about_panel(total_tasks(&data), fill, close);
                        });
                });
        });
}

/// How many tasks there are, in every list.
fn total_tasks(data: &Data) -> usize {
    data.tasks.with(Vec::len)
}

/// The header's one action, as the reference has one: what can be done to the list on
/// screen lives behind it.
#[Composable]
fn bar_menu(
    current: View,
    completed_here: usize,
    data: Data,
    view: MutableState<View>,
    open: MutableState<bool>,
    about_open: MutableState<bool>,
) {
    let expanded = open.get();
    let (dismiss, opener) = (open, open);
    Menu(expanded)
        .on_dismiss(move || dismiss.set(false))
        .anchor(move || {
            Button("")
                .icon(IconRole::More)
                .variant(ButtonVariant::Text)
                .accessible_name("More")
                .on_click(move || opener.set(true));
        })
        .content(|| {
            if !expanded {
                return;
            }
            // Clearing throws work away, so it says so and offers the way back.
            let close = open;
            Button("Clear completed")
                .variant(ButtonVariant::Text)
                .modifier(Modifier.fill_max_width())
                .color(Paint::Role(ColorRole::Error))
                .enabled(completed_here > 0)
                .on_click(move || {
                    close.set(false);
                    let before = data.tasks.get_untracked();
                    data.tasks
                        .update(|tasks| tasks.retain(|task| !(task.done && current.accepts(task))));
                    data.save();
                    let plural = if completed_here == 1 { "task" } else { "tasks" };
                    Message::new(format!("Cleared {completed_here} completed {plural}"))
                        .with_action("Undo", move |()| {
                            data.tasks.set(before.clone());
                            data.save();
                        })
                        .with_duration(MessageDuration::Long)
                        .show();
                });
            if let View::List(id) = current {
                // A list is a place to keep tasks, not the tasks themselves: deleting one
                // keeps its tasks, under All.
                let close = open;
                Button("Delete list")
                    .variant(ButtonVariant::Text)
                    .modifier(Modifier.fill_max_width())
                    .color(Paint::Role(ColorRole::Error))
                    .on_click(move || {
                        close.set(false);
                        let name = data
                            .lists
                            .get_untracked()
                            .into_iter()
                            .find(|list| list.id == id)
                            .map(|list| list.name)
                            .unwrap_or_default();
                        data.lists
                            .update(|lists| lists.retain(|list| list.id != id));
                        data.tasks.update(|tasks| {
                            for task in tasks.iter_mut().filter(|task| task.list == id) {
                                task.list = NO_LIST;
                            }
                        });
                        view.set(View::Filter(Filter::All));
                        data.save();
                        Message::new(format!(
                            "Deleted the list \u{201c}{name}\u{201d}; its tasks are under All"
                        ))
                        .with_duration(MessageDuration::Long)
                        .show();
                    });
            }
            let (close, about) = (open, about_open);
            Button(ABOUT_LABEL)
                .variant(ButtonVariant::Text)
                .modifier(Modifier.fill_max_width())
                .on_click(move || {
                    close.set(false);
                    about.set(true);
                });
        });
}

/// The search sheet: one field, and the page behind it narrows as it is typed into.
#[Composable]
fn search_panel(query: MutableState<String>, open: MutableState<bool>) {
    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Md)
        .content(|| {
            Row()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Text("Search tasks")
                        .type_role(TypeRole::Subtitle)
                        .modifier(Modifier.weight(1.0));
                    Button("Done")
                        .variant(ButtonVariant::Filled)
                        .on_click(move || open.set(false));
                });
            let typed = query;
            TextField()
                .modifier(Modifier.fill_max_width())
                .text(query.get_untracked())
                .placeholder("Search tasks")
                .on_value_change(move |value| typed.set(value));
            if !query.get().is_empty() {
                Button("Clear search")
                    .modifier(Modifier.fill_max_width())
                    .variant(ButtonVariant::Text)
                    .on_click(move || query.set(String::new()));
            }
        });
}

/// The sheet that names a new list.
#[Composable]
fn new_list_panel(
    create: Rc<dyn Fn(String)>,
    draft: MutableState<String>,
    open: MutableState<bool>,
) {
    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Md)
        .content(|| {
            Row()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Text("New list")
                        .type_role(TypeRole::Subtitle)
                        .modifier(Modifier.weight(1.0));
                    Button("Cancel")
                        .variant(ButtonVariant::Text)
                        .on_click(move || open.set(false));
                });
            let (typed, submit) = (draft, Rc::clone(&create));
            TextField()
                .modifier(Modifier.fill_max_width())
                .placeholder("List name")
                .on_value_change(move |value| typed.set(value))
                .on_submit(move |value: String| submit(value));
            let (pressed, current) = (Rc::clone(&create), draft);
            Button("Create")
                .variant(ButtonVariant::Filled)
                .modifier(Modifier.fill_max_width())
                .on_click(move || pressed(current.get_untracked()));
        });
}

/// The heading over the tasks of one kind.
#[Composable]
fn section_heading(title: &'static str) {
    Text(title)
        .type_role(TypeRole::Title)
        .modifier(Modifier.padding_role(SpaceRole::Sm));
}

/// One task, as a card of its own, composed when the Renderer's window reaches it.
#[Composable]
#[allow(clippy::too_many_arguments)]
fn task_row(
    rows: &[usize],
    position: usize,
    data: &Data,
    names: &[TaskList],
    editing: &MutableState<Option<u64>>,
    edit_draft: &MutableState<String>,
    menu_open: &MutableState<Option<u64>>,
    commit_edit: &Rc<dyn Fn(String)>,
    swap_tasks: &Rc<dyn Fn(usize, usize)>,
) {
    let tasks = &data.tasks;
    let index = rows[position];
    let previous = position.checked_sub(1).map(|above| rows[above]);
    let next = rows.get(position + 1).copied();
    let task = tasks.with(|tasks| tasks[index].clone());
    let editing_this = editing.get() == Some(task.id);
    let open = menu_open.get() == Some(task.id);
    let list_name = names
        .iter()
        .find(|list| list.id == task.list)
        .map(|list| list.name.clone());
    // The gap between two cards.
    Box()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Xs))
        .content(|| {
            Card().modifier(Modifier.fill_max_width()).content(|| {
                Row()
                    .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
                    .space_role(SpaceRole::Sm)
                    .alignment(Alignment::CenterStart)
                    .content(|| {
                        let (toggle, saver) = (*tasks, *data);
                        let done = task.done;
                        Checkbox(task.done).on_change(move |_| {
                            toggle.update(|tasks| tasks[index].done = !done);
                            saver.save();
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
                            TextField()
                                .modifier(Modifier.weight(1.0))
                                .text(task.title.clone())
                                .on_value_change(move |value| typed.set(value))
                                .on_submit(move |value: String| submit(value))
                                .on_focus_lost(move || lost(lost_draft.get_untracked()));
                            Button("Save")
                                .variant(ButtonVariant::Filled)
                                .on_click(move || saved(saved_draft.get_untracked()));
                        } else {
                            Column()
                                .modifier(Modifier.weight(1.0))
                                .space_role(SpaceRole::Xs)
                                .content(|| {
                                    Text(task.title.clone())
                                        .type_role(TypeRole::Body)
                                        .color(Paint::Role(if task.done {
                                            ColorRole::OnSurfaceVariant
                                        } else {
                                            ColorRole::OnSurface
                                        }))
                                        .max_lines(1)
                                        .overflow(TextOverflow::Ellipsis);
                                    if !task.note.is_empty() {
                                        Text(task.note.clone())
                                            .type_role(TypeRole::Caption)
                                            .color(Paint::Role(ColorRole::OnSurfaceVariant))
                                            .max_lines(3)
                                            .overflow(TextOverflow::Ellipsis);
                                    }
                                    if let Some(name) = list_name.clone() {
                                        Text(name)
                                            .type_role(TypeRole::Caption)
                                            .color(Paint::Role(ColorRole::Primary))
                                            .max_lines(1)
                                            .overflow(TextOverflow::Ellipsis);
                                    }
                                });
                            // What a card can have done to it lives behind one control. The
                            // entries exist only while the menu is open.
                            let (dismiss, opener) = (*menu_open, *menu_open);
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
                                            &task, index, previous, next, data, editing,
                                            edit_draft, menu_open, swap_tasks,
                                        );
                                    }
                                });
                        }
                    });
            });
        });
}

/// The four things a card can have done to it.
#[allow(clippy::too_many_arguments)]
fn menu_entries(
    task: &Task,
    index: usize,
    previous: Option<usize>,
    next: Option<usize>,
    data: &Data,
    editing: &MutableState<Option<u64>>,
    edit_draft: &MutableState<String>,
    menu_open: &MutableState<Option<u64>>,
    swap_tasks: &Rc<dyn Fn(usize, usize)>,
) {
    let id = task.id;
    let (close, draft, edit) = (*menu_open, *edit_draft, *editing);
    Button("Edit")
        .variant(ButtonVariant::Text)
        .modifier(Modifier.fill_max_width())
        .on_click(move || {
            close.set(None);
            draft.set(String::new());
            edit.set(Some(id));
        });
    let (close, swap) = (*menu_open, Rc::clone(swap_tasks));
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
    let (close, swap) = (*menu_open, Rc::clone(swap_tasks));
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
    let (close, data) = (*menu_open, *data);
    Button("Delete")
        .variant(ButtonVariant::Text)
        .modifier(Modifier.fill_max_width())
        .color(Paint::Role(ColorRole::Error))
        .on_click(move || {
            close.set(None);
            let mut removed = None;
            data.tasks.update(|list| removed = Some(list.remove(index)));
            data.save();
            let Some(removed) = removed else { return };
            let title = removed.title.clone();
            Message::new(format!("Deleted \u{201c}{title}\u{201d}"))
                .with_action("Undo", move |()| {
                    let at = index.min(data.tasks.with(Vec::len));
                    data.tasks.update(|list| list.insert(at, removed.clone()));
                    data.save();
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
        // The same as [bundle] identifier in Dioxus.toml. It names the storage.
        .identifier("dev.darkpyonix.composerust.samples.todo")
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
    store::start();
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
    use compose_rust::schema::{EventPayload, IoStatus, PropertyKind, StorageOp, WidgetKind};
    use compose_rust::storage::{Backend, testing};
    use std::collections::HashMap;
    use std::sync::OnceLock;

    /// The identifier the sample launches with, which names its storage.
    const IDENTIFIER: &str = "dev.darkpyonix.composerust.samples.todo";

    /// A saved list large enough that a window of twenty is a small fraction of it.
    const SAVED_TASKS: usize = 5_000;
    const WINDOW: usize = 20;

    /// What a row's action menu offers, and the glyph on the control that opens it.
    const ACTIONS: [&str; 4] = ["Edit", "Move up", "Move down", "Delete"];
    const ACTIONS_ANCHOR: &str = "\u{22ef}";

    /// Storage for every screen in this binary: the Renderer's, as in a browser, so each
    /// operation is a `StorageCall` record in a batch and the test answers it with an
    /// `IoAnswer`, the way the web Renderer does. Set once, before any screen is built,
    /// because the storage's settings are process wide and the tests run at once; each test
    /// thread keeps its own requests and answers.
    fn saved_list() {
        static PREPARED: OnceLock<()> = OnceLock::new();
        PREPARED.get_or_init(|| {
            testing::reset(Some(IDENTIFIER), Backend::Renderer);
            store::start();
        });
    }

    /// The saved list most screens start from, as the storage holds it.
    fn saved_tasks() -> &'static str {
        static SAVED: OnceLock<String> = OnceLock::new();
        SAVED.get_or_init(|| {
            (0..SAVED_TASKS)
                .map(|id| format!("{id}\t0\tGenerated task {id}\n"))
                .collect()
        })
    }

    /// The storage operations a batch carries: number, operation, store, key and value.
    fn storage_calls(batch: &[u8]) -> Vec<(u32, StorageOp, String, String, Vec<u8>)> {
        decode_batch(batch)
            .expect("the batch did not decode")
            .into_iter()
            .filter_map(|mutation| match mutation {
                Mutation::StorageCall {
                    request,
                    op,
                    store,
                    key,
                    data,
                } => Some((request, op, store.to_owned(), key.to_owned(), data.to_vec())),
                _ => None,
            })
            .collect()
    }

    /// The one read of the saved list a first frame asks for.
    fn the_load(batch: &[u8]) -> u32 {
        let reads: Vec<u32> = storage_calls(batch)
            .into_iter()
            .filter(|(_, op, store, key, _)| {
                *op == StorageOp::Read && store == IDENTIFIER && key == store::KEY
            })
            .map(|(request, ..)| request)
            .collect();
        assert_eq!(
            reads.len(),
            1,
            "the first frame should ask for the saved list once, under {}",
            store::KEY
        );
        reads[0]
    }

    /// What the storage says back, as the event the Renderer sends.
    fn storage_answer(request: u32, status: IoStatus, data: &[u8]) -> HostEvent<'_> {
        HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: EventPayload::IoAnswer {
                request,
                status,
                data,
            },
        }
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
        /// The submit handler each field declared.
        submit_handlers: HashMap<u32, u64>,
        /// The value handler each field declared, so a test can type into it.
        value_handlers: HashMap<u32, u64>,
        /// The integer properties a test reads back, by node and property: icons, type
        /// roles, alignments, arrangements and selected indices.
        integers: HashMap<(u32, u16), i64>,
        /// The string properties beside the text: accessible names and sections.
        strings: HashMap<(u32, u16), String>,
        /// Whether each sheet and menu is open.
        opens: HashMap<u32, bool>,
        /// Where each node sits among its parent's children.
        positions: HashMap<u32, u32>,
    }

    impl MockRenderer {
        fn apply(&mut self, batch: &[Mutation<'_>]) {
            for mutation in batch {
                match mutation {
                    Mutation::Create { node_id, widget } => {
                        self.widgets.insert(*node_id, *widget);
                    }
                    Mutation::Insert {
                        parent_id,
                        node_id,
                        index,
                    }
                    | Mutation::Move {
                        parent_id,
                        node_id,
                        index,
                    } => {
                        self.parents.insert(*node_id, *parent_id);
                        self.positions.insert(*node_id, *index);
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
                        (PropertyKind::OnSubmit, PropertyValue::Integer(value)) => {
                            self.submit_handlers.insert(*node_id, *value as u64);
                        }
                        (PropertyKind::OnValueChange, PropertyValue::Integer(value)) => {
                            self.value_handlers.insert(*node_id, *value as u64);
                        }
                        (PropertyKind::Open, PropertyValue::Bool(open)) => {
                            self.opens.insert(*node_id, *open);
                        }
                        (
                            PropertyKind::Icon
                            | PropertyKind::TypeRole
                            | PropertyKind::Alignment
                            | PropertyKind::Arrangement
                            | PropertyKind::SelectedIndex,
                            PropertyValue::Integer(value),
                        ) => {
                            self.integers.insert((*node_id, *property as u16), *value);
                        }
                        (
                            PropertyKind::AccessibleName | PropertyKind::Section,
                            PropertyValue::String(value),
                        ) => {
                            self.strings
                                .insert((*node_id, *property as u16), (*value).to_owned());
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
            self.positions.remove(&node_id);
            self.opens.remove(&node_id);
            self.click_handlers.remove(&node_id);
            self.submit_handlers.remove(&node_id);
            self.value_handlers.remove(&node_id);
            self.integers.retain(|(node, _), _| *node != node_id);
            self.strings.retain(|(node, _), _| *node != node_id);
        }

        /// An integer property of a live node.
        fn integer(&self, node_id: u32, property: PropertyKind) -> Option<i64> {
            self.integers.get(&(node_id, property as u16)).copied()
        }

        /// A string property of a live node.
        fn string(&self, node_id: u32, property: PropertyKind) -> Option<&str> {
            self.strings
                .get(&(node_id, property as u16))
                .map(String::as_str)
        }

        /// The live nodes assistive technology reads by this name.
        fn named(&self, name: &str) -> Vec<u32> {
            let mut nodes: Vec<u32> = self
                .strings
                .iter()
                .filter(|((node_id, property), value)| {
                    *property == PropertyKind::AccessibleName as u16
                        && value.as_str() == name
                        && self.widgets.contains_key(node_id)
                })
                .map(|((node_id, _), _)| *node_id)
                .collect();
            nodes.sort_unstable();
            nodes
        }

        /// The same, leaving out what sits in a sheet.
        fn named_on_the_page(&self, name: &str) -> Vec<u32> {
            self.named(name)
                .into_iter()
                .filter(|node_id| !self.is_inside(*node_id, WidgetKind::Sheet))
                .collect()
        }

        /// The nearest ancestor of a node that is of one kind.
        fn ancestor(&self, node_id: u32, kind: WidgetKind) -> Option<u32> {
            let mut current = node_id;
            while let Some(parent) = self.parents.get(&current).copied() {
                if self.widgets.get(&parent) == Some(&kind) {
                    return Some(parent);
                }
                current = parent;
            }
            None
        }

        fn is_open(&self, node_id: u32) -> bool {
            self.opens.get(&node_id).copied().unwrap_or(false)
        }

        /// The buttons carrying this label that are not destinations in the `Navigation`,
        /// which is where a narrow window's own list of lists puts them.
        fn buttons_off_the_navigation(&self, label: &str) -> Vec<u32> {
            self.nodes_of_kind_with_text(WidgetKind::Button, label)
                .into_iter()
                .filter(|node_id| !self.is_inside(*node_id, WidgetKind::Navigation))
                .collect()
        }

        /// The field inside an open sheet that answers Enter.
        fn field_in_an_open_sheet(&self) -> u32 {
            self.submit_handlers
                .keys()
                .copied()
                .filter(|node_id| self.widgets.contains_key(node_id))
                .find(|node_id| {
                    self.ancestor(*node_id, WidgetKind::Sheet)
                        .is_some_and(|sheet| self.is_open(sheet))
                })
                .expect("no open sheet holds a field that answers Enter")
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
        /// The windowing list and its range handler, once there is a list to window.
        list: Option<(u32, u64)>,
        event: Vec<u8>,
        /// The storage operations every batch so far carried, in order.
        storage: Vec<(u32, StorageOp, String, String, Vec<u8>)>,
        /// What stood on screen in the first frame, before the saved list arrived.
        first_texts: Vec<String>,
    }

    impl Screen {
        /// The screen on a saved list of [`SAVED_TASKS`] tasks.
        fn new() -> Self {
            Self::loaded(Some(saved_tasks()))
        }

        /// The screen once storage has answered its first read: with `saved`, or with
        /// nothing saved yet, which is what a first run finds.
        fn loaded(saved: Option<&str>) -> Self {
            saved_list();
            let mut host = ComposeHost::new(app);
            let first = host
                .rebuild()
                .expect("the first frame failed to encode")
                .to_vec();
            let mut mock = MockRenderer::default();
            mock.apply(&decode_batch(&first).expect("the first frame did not decode"));
            let first_texts = mock.texts.values().cloned().collect();
            let mut screen = Self {
                host,
                mock,
                list: None,
                event: Vec::new(),
                storage: storage_calls(&first),
                first_texts,
            };
            let load = the_load(&first);
            match saved {
                Some(saved) => screen.answer(load, IoStatus::Ok, saved.as_bytes()),
                None => screen.answer(load, IoStatus::NotFound, &[]),
            };
            screen
        }

        /// Answers a storage operation the way the browser's Renderer does, and applies
        /// the batch that comes back.
        fn answer(&mut self, request: u32, status: IoStatus, data: &[u8]) -> Vec<OwnedRecord> {
            self.dispatch(storage_answer(request, status, data))
        }

        /// The writes of the list the batches so far carried, as text, in order.
        fn written_lists(&self) -> Vec<(u32, String)> {
            self.storage
                .iter()
                .filter(|(_, op, store, key, _)| {
                    *op == StorageOp::Write && store == IDENTIFIER && key == store::KEY
                })
                .map(|(request, _, _, _, data)| {
                    (
                        *request,
                        String::from_utf8(data.clone()).expect("a saved list is UTF-8"),
                    )
                })
                .collect()
        }

        /// Types a title into the composer and submits it, as pressing Enter does.
        fn add_task(&mut self, title: &str) -> Vec<OwnedRecord> {
            let (node_id, handler_id) = self
                .mock
                .submit_handlers
                .iter()
                .map(|(node, handler)| (*node, *handler))
                .find(|(node, _)| self.mock.widgets.contains_key(node))
                .expect("the screen has no field to add a task with");
            self.dispatch(HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::TextSubmitted(title),
            })
        }

        /// Submits a field, as pressing Enter in it does.
        fn submit(&mut self, node_id: u32, text: &str) -> Vec<OwnedRecord> {
            let handler_id = self.mock.submit_handlers[&node_id];
            self.dispatch(HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::TextSubmitted(text),
            })
        }

        /// Types into a field, as each keystroke reports the field's whole text.
        fn type_into(&mut self, node_id: u32, text: &str) -> Vec<OwnedRecord> {
            let handler_id = self.mock.value_handlers[&node_id];
            self.dispatch(HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::TextChanged(text),
            })
        }

        /// Answers every write that is on its way, so the next save is handed over.
        fn settle(&mut self) {
            let mut answered = 0;
            while let Some((request, _)) = self.written_lists().get(answered).cloned() {
                self.answer(request, IoStatus::Ok, &[]);
                answered += 1;
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
                    safe_area_top: 0.0,
                    safe_area_leading: 0.0,
                    safe_area_trailing: 0.0,
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
            let batch = batch.to_vec();
            self.storage.extend(storage_calls(&batch));
            let decoded = decode_batch(&batch).expect("the batch did not decode");
            self.mock.apply(&decoded);
            self.find_list(&decoded);
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

        /// Notes the windowing list and its range handler when a batch creates them.
        fn find_list(&mut self, decoded: &[Mutation<'_>]) {
            let created = decoded.iter().find_map(|mutation| match mutation {
                Mutation::Create {
                    node_id,
                    widget: WidgetKind::LazyColumn,
                } => Some(*node_id),
                _ => None,
            });
            let Some(list) = created.or(self.list.map(|(list, _)| list)) else {
                return;
            };
            let handler = decoded.iter().find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::OnRangeRequested,
                    value: PropertyValue::Integer(id),
                } if *node_id == list => Some(*id as u64),
                _ => None,
            });
            if let Some(handler) = handler {
                self.list = Some((list, handler));
            }
        }

        /// Scrolling: the Renderer asks for a window and the Host answers with a batch.
        /// Returns how many widgets that batch created.
        fn request_range(&mut self, start: usize, count: usize) -> usize {
            let (list, handler) = self.list.expect("the screen has no LazyColumn");
            encode_event(
                &HostEvent {
                    node_id: list,
                    handler_id: handler,
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

    /// A saved list with every third task done, so the All page holds both kinds of task
    /// and heads each.
    fn mixed_tasks(count: usize) -> String {
        (0..count)
            .map(|id| format!("{id}\t{}\tGenerated task {id}\n", u8::from(id % 3 == 0)))
            .collect()
    }

    /// Answers the recorded screen's read of its saved list with `saved`.
    fn answer_the_load(screen: &mut sample_frames::Screen, saved: &str) {
        let load = screen
            .mutations()
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::StorageCall {
                    request,
                    op: StorageOp::Read,
                    key,
                    ..
                } if *key == store::KEY => Some(*request),
                _ => None,
            })
            .expect("the screen did not ask for its saved list");
        screen.dispatch(storage_answer(load, IoStatus::Ok, saved.as_bytes()));
    }

    /// Presses the first control assistive technology reads by this name.
    fn press_named(screen: &mut sample_frames::Screen, name: &str) -> bool {
        let node = screen
            .live_mutations()
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::AccessibleName,
                    value: PropertyValue::String(value),
                } if *value == name => Some(*node_id),
                _ => None,
            });
        let Some(node_id) = node else {
            return false;
        };
        let Some(handler_id) = screen.handler_of(node_id, PropertyKind::OnClick) else {
            return false;
        };
        screen.dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        });
        true
    }

    /// A filled list, under every design system, in both schemes, at all three widths.
    ///
    /// The calculator shows a keypad and a readout, which leaves the two things this
    /// screen has and that one does not: cards, and the headings over them. Those are
    /// where a spacing ladder and a card's fill stop being numbers in a table and become
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
            answer_the_load(screen, &mixed_tasks(SAVED_TASKS));
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

    /// The list of lists a narrow window shows in place of the page, under every design
    /// system. A wider window has no such screen, since the rail or the drawer holds the
    /// destinations, so there the recording is the page.
    #[test]
    fn fr22_3_6_the_list_of_lists_is_recorded_under_every_design_system() {
        saved_list();
        sample_frames::record("TodoLists", app, |screen| {
            answer_the_load(screen, &mixed_tasks(SAVED_TASKS));
            let pressed = press_named(screen, SHOW_LISTS);
            if screen.viewport().name == "compact" {
                assert!(pressed, "a narrow page has no way to its list of lists");
            }
        });
    }

    /// The Add Task sheet a narrow window composes in, under every design system. A wider
    /// window composes at the head of the list instead.
    #[test]
    fn fr22_3_6_the_add_task_sheet_is_recorded_under_every_design_system() {
        saved_list();
        sample_frames::record("TodoAddTask", app, |screen| {
            answer_the_load(screen, &mixed_tasks(SAVED_TASKS));
            screen.fill_lists(12);
            // The button floating over the page is composed before the sheet's own.
            let pressed = press_named(screen, ADD_TASK);
            if screen.viewport().name == "compact" {
                assert!(pressed, "a narrow page has no way to compose a task");
            }
        });
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
                safe_area_top: 0.0,
                safe_area_leading: 0.0,
                safe_area_trailing: 0.0,
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

    /// A desktop window stops the screen widening and centres it, because a one line item
    /// read across 1200dp cannot be scanned. A phone fills its width.
    #[test]
    fn fr20_the_screen_stops_widening_on_a_desktop() {
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
    /// is behind one, with the task and its note, and on a window with room it stands at
    /// the head of the list.
    #[test]
    fn fr22_the_composer_moves_into_a_sheet_on_a_phone() {
        let narrow = Screen::at(420.0);
        let fields = narrow.mock.nodes_of_kind(WidgetKind::TextField);
        assert_eq!(
            fields.len(),
            2,
            "a phone should declare the task and its note, and nothing else"
        );
        for field in fields {
            assert!(
                narrow.mock.is_inside(field, WidgetKind::Sheet),
                "a phone should reach the composer through a sheet"
            );
        }

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

        let row_menus = screen
            .mock
            .nodes_of_kind(WidgetKind::Menu)
            .into_iter()
            .filter(|menu| screen.mock.is_inside(*menu, WidgetKind::LazyColumn))
            .count();
        assert_eq!(
            row_menus, WINDOW,
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

    /// Search, the three filters and the user's lists are one destination set, and this
    /// screen never asks how wide the window is to draw them. Which shape they take is the
    /// Renderer's, which is what the Renderer's own tests check at each width.
    #[test]
    fn fr21_the_filters_are_a_destination_set_rather_than_a_row_of_buttons() {
        let screen = Screen::new();
        assert_eq!(screen.mock.count_of(WidgetKind::Navigation), 1);
        assert_eq!(
            screen.mock.count_of(WidgetKind::NavigationItem),
            FIRST_LIST,
            "a screen with no lists of its own should have search and the three filters"
        );
        for label in [SEARCH_LABEL, "All", "Active", "Done"] {
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
        let search = screen
            .mock
            .nodes_of_kind_with_text(WidgetKind::NavigationItem, SEARCH_LABEL)[0];
        assert_eq!(
            screen.mock.integer(search, PropertyKind::Icon),
            Some(i64::from(u16::from(IconRole::Search))),
            "search is a destination that says it is search"
        );
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

    /// The claim the "Add 5000" button exists to let a person check by hand, asserted so
    /// it cannot quietly stop being true. A window of twenty out of five thousand tasks
    /// costs widgets in proportion to the twenty. This is the test that fails the day
    /// windowing degrades into building everything.
    #[test]
    fn fr8_node_count_is_proportional_to_the_window() {
        let mut screen = Screen::new();
        screen.request_range(4_000, WINDOW);

        // A row is a handful of widgets: the item, the gap around its card, the card, the
        // row inside it, the toggle, the column of its lines and the title in it, and the
        // action menu with its anchor; the menu's four entries exist only while it is
        // open. The screen's own chrome is a fixed handful on top of that. What matters is
        // that the total tracks the window and not the list behind it, which is why this
        // is a per-row multiplier rather than a fixed total.
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

    /// A compact window gets no bottom bar and no toggle, as in the task list references, on
    /// every design system: the screen chooses it once and never reads the width.
    #[test]
    fn fr22_3_6_todo_chooses_no_bar_in_a_compact_window() {
        saved_list();
        let mut host = ComposeHost::new(app);
        let batch = host.rebuild().expect("the first frame encodes").to_vec();
        let mutations = decode_batch(&batch).expect("the first frame decodes");
        let navigations: Vec<u32> = mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::Create {
                    node_id,
                    widget: WidgetKind::Navigation,
                } => Some(*node_id),
                _ => None,
            })
            .collect();
        assert_eq!(navigations.len(), 1);
        let chosen: Vec<i64> = mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::CompactNavigation,
                    value: PropertyValue::Integer(tag),
                } if *node_id == navigations[0] => Some(*tag),
                _ => None,
            })
            .collect();
        assert_eq!(
            chosen,
            vec![i64::from(u16::from(CompactNavigation::None))],
            "the destinations do not choose what a compact window gets"
        );
    }

    /// The list is kept in the application's storage, so a browser keeps it across a reload
    /// of the page as a desktop keeps it across a restart. The storage here answers the way
    /// the web Renderer does, one `IoAnswer` per `StorageCall`, and a reload is a new Host
    /// whose storage still holds what the last one wrote.
    ///
    /// The first frame asks for the list rather than reading it, and says it is loading
    /// until the answer comes: the UI thread does not wait for storage, and no empty list
    /// shows before the saved one arrives.
    #[test]
    fn fr79_todo_list_survives_a_reload_on_the_web() {
        let mut first_run = Screen::loaded(None);
        assert!(
            first_run
                .first_texts
                .iter()
                .any(|text| text == LOADING_LABEL),
            "the first frame should say the list is loading: {:?}",
            first_run.first_texts
        );
        assert!(
            !first_run
                .first_texts
                .iter()
                .any(|text| text.starts_with("No tasks yet")),
            "an empty list showed before the saved one had been read"
        );
        assert!(first_run.mock.nodes_with_text(LOADING_LABEL).is_empty());
        assert_eq!(
            first_run
                .mock
                .nodes_with_text("No tasks yet. Add one above.")
                .len(),
            1,
            "a first run finds nothing saved, which is an empty list"
        );

        first_run.add_task("Buy milk");
        let written = first_run.written_lists();
        assert_eq!(written.len(), 1, "one task added is one write: {written:?}");
        assert_eq!(written[0].1, "1\t0\tBuy milk\n");
        first_run.answer(written[0].0, IoStatus::Ok, &[]);
        let saved = written[0].1.clone();
        drop(first_run);

        // The page is loaded again. The new Host asks for the list again and the storage
        // gives back what was written.
        let mut reloaded = Screen::loaded(Some(&saved));
        reloaded.request_range(0, WINDOW);
        assert_eq!(
            reloaded.mock.nodes_with_text("Buy milk").len(),
            1,
            "the task added before the reload is not on the list after it"
        );
        // A task added after the reload carries on from the saved ids rather than
        // starting again at one, which would give two tasks the same key.
        reloaded.add_task("Walk the dog");
        let after = reloaded.written_lists();
        assert_eq!(
            after.last().map(|(_, text)| text.as_str()),
            Some("1\t0\tBuy milk\n2\t0\tWalk the dog\n")
        );
    }

    /// A burst of edits writes the newest list, not every one of them. One write is on its
    /// way at a time, and an edit made meanwhile only replaces the list waiting behind it,
    /// so the lists it overtook are never written.
    #[test]
    fn pr3_an_overtaken_save_writes_nothing() {
        let mut screen = Screen::loaded(None);
        screen.add_task("one");
        screen.add_task("two");
        screen.add_task("three");
        let written = screen.written_lists();
        assert_eq!(
            written.len(),
            1,
            "only the first edit's write should be on its way: {written:?}"
        );
        assert_eq!(written[0].1, "1\t0\tone\n");

        screen.answer(written[0].0, IoStatus::Ok, &[]);
        let written = screen.written_lists();
        assert_eq!(
            written.len(),
            2,
            "the two edits behind the first should be one write: {written:?}"
        );
        assert_eq!(written[1].1, "1\t0\tone\n2\t0\ttwo\n3\t0\tthree\n");

        screen.answer(written[1].0, IoStatus::Ok, &[]);
        assert_eq!(screen.written_lists().len(), 2, "nothing was left to write");
    }

    /// A saved list that cannot be read is not replaced. The run says why and writes
    /// nothing, so the list is still there for a run that can read it, which is what a
    /// browser that cannot carry a long list in one answer yet needs.
    #[test]
    fn fr79_a_list_that_cannot_be_read_is_not_overwritten() {
        saved_list();
        let mut host = ComposeHost::new(app);
        let first = host
            .rebuild()
            .expect("the first frame failed to encode")
            .to_vec();
        let mut screen = Screen {
            host,
            mock: MockRenderer::default(),
            list: None,
            event: Vec::new(),
            storage: storage_calls(&first),
            first_texts: Vec::new(),
        };
        screen
            .mock
            .apply(&decode_batch(&first).expect("the first frame did not decode"));
        let load = the_load(&first);
        let said = screen.answer(load, IoStatus::TooLarge, &(64 * 1024_u32).to_le_bytes());
        assert!(
            said.iter().any(|record| matches!(
                record,
                OwnedRecord::Message { text, .. } if text.starts_with("The saved list could not be read")
            )),
            "a list that could not be read should be said: {said:?}"
        );
        screen.add_task("new");
        assert!(
            screen.written_lists().is_empty(),
            "a list nobody could read was written over: {:?}",
            screen.written_lists()
        );
    }

    /// A narrow window has no bar of destinations, as in the references: the page floats
    /// a back button at its bottom left, which goes to the list of lists, and an add
    /// button at its bottom right, which opens composing. A wider window has neither,
    /// because its rail or drawer holds the destinations and its composer heads the list.
    #[test]
    fn fr22_3_6_a_compact_page_floats_back_at_its_bottom_left_and_add_at_its_bottom_right() {
        let narrow = Screen::at(420.0);
        let mock = &narrow.mock;
        let back = mock.named_on_the_page(SHOW_LISTS);
        let add = mock.named_on_the_page(ADD_TASK);
        assert_eq!(
            back.len(),
            1,
            "the page should float one way back to its lists"
        );
        assert_eq!(add.len(), 1, "the page should float one way to a new task");
        let (back, add) = (back[0], add[0]);
        assert_eq!(
            mock.integer(back, PropertyKind::Icon),
            Some(i64::from(u16::from(IconRole::Back)))
        );
        assert_eq!(
            mock.integer(add, PropertyKind::Icon),
            Some(i64::from(u16::from(IconRole::Add)))
        );
        for button in [back, add] {
            for chrome in [
                WidgetKind::TopAppBar,
                WidgetKind::Navigation,
                WidgetKind::Sheet,
            ] {
                assert!(
                    !mock.is_inside(button, chrome),
                    "a floating button sits over the page, not in {chrome:?}"
                );
            }
        }

        // One row holds both, pushed to its two ends, the back button first.
        let row = mock.parents[&back];
        assert_eq!(
            mock.parents.get(&add),
            Some(&row),
            "the two buttons are not one row"
        );
        assert_eq!(mock.widgets.get(&row), Some(&WidgetKind::Row));
        assert_eq!(
            mock.integer(row, PropertyKind::Arrangement),
            Some(i64::from(u16::from(Arrangement::SpaceBetween))),
            "the buttons should sit at the row's two ends"
        );
        assert!(
            mock.positions[&back] < mock.positions[&add],
            "back belongs at the left and add at the right"
        );
        // The row is laid over the page at its foot.
        let over = mock.parents[&row];
        assert_eq!(mock.widgets.get(&over), Some(&WidgetKind::Box));
        assert_eq!(
            mock.integer(over, PropertyKind::Alignment),
            Some(i64::from(u16::from(Alignment::BottomCenter))),
            "the buttons should float at the foot of the page"
        );
        let page = mock.nodes_of_kind(WidgetKind::LazyColumn);
        assert_eq!(page.len(), 1);
        let mut current = page[0];
        while current != over {
            current = *mock
                .parents
                .get(&current)
                .expect("the page is not under the floating buttons");
        }

        let wide = Screen::at(1200.0);
        assert!(wide.mock.named_on_the_page(SHOW_LISTS).is_empty());
        assert!(wide.mock.named_on_the_page(ADD_TASK).is_empty());
    }

    /// The back button opens the list of lists, a screen of its own in place of the page:
    /// search, every destination, and a new list at its foot. Picking a destination goes
    /// to its page.
    #[test]
    fn fr22_3_6_the_back_button_opens_the_list_of_lists_and_a_pick_returns_to_a_page() {
        let mut screen = Screen::at(420.0);
        assert!(
            screen.mock.buttons_off_the_navigation("Active").is_empty(),
            "the list of lists is on screen before anyone asked for it"
        );
        let back = screen.mock.named_on_the_page(SHOW_LISTS)[0];
        screen.click(back);

        assert_eq!(
            screen.mock.count_of(WidgetKind::LazyColumn),
            0,
            "the list of lists should stand in place of the page, not beside it"
        );
        assert_eq!(
            screen
                .mock
                .nodes_of_kind_with_text(WidgetKind::Text, LISTS_LABEL)
                .len(),
            1,
            "the screen should be headed by what it is"
        );
        let search = screen.mock.buttons_off_the_navigation(SEARCH_LABEL);
        assert_eq!(search.len(), 1, "the list of lists has no search");
        assert_eq!(
            screen.mock.integer(search[0], PropertyKind::Icon),
            Some(i64::from(u16::from(IconRole::Search)))
        );
        for label in ["All", "Active", "Done", NEW_LIST_LABEL] {
            assert_eq!(
                screen.mock.buttons_off_the_navigation(label).len(),
                1,
                "the list of lists has no {label}"
            );
        }
        assert!(
            screen.mock.named_on_the_page(SHOW_LISTS).is_empty(),
            "the list of lists still floats the button that opens it"
        );
        // How many tasks each destination holds stands beside it.
        assert_eq!(
            screen
                .mock
                .nodes_of_kind_with_text(WidgetKind::Text, &SAVED_TASKS.to_string())
                .len(),
            2,
            "All and Active should each say they hold every saved task"
        );

        let active = screen.mock.buttons_off_the_navigation("Active")[0];
        screen.click(active);
        assert_eq!(screen.mock.count_of(WidgetKind::LazyColumn), 1);
        assert!(screen.mock.buttons_off_the_navigation("Active").is_empty());
        assert_eq!(
            screen
                .mock
                .nodes_of_kind_with_text(WidgetKind::Text, "Active")
                .len(),
            1,
            "the page should be headed by the destination picked"
        );
        let navigation = screen.mock.nodes_of_kind(WidgetKind::Navigation)[0];
        assert_eq!(
            screen.mock.integer(navigation, PropertyKind::SelectedIndex),
            Some((FIRST_FILTER + Filter::Active.index()) as i64),
            "the destination set marks the page on screen"
        );
    }

    /// The add button floating over the page opens composing in a sheet headed as the
    /// reference heads it, large and bold, and a task added there lands on the list.
    #[test]
    fn fr22_3_6_the_add_button_opens_composing_in_a_sheet() {
        let mut screen = Screen::at(420.0);
        let heading = screen
            .mock
            .nodes_of_kind_with_text(WidgetKind::Text, "Add Task");
        assert_eq!(heading.len(), 1);
        assert_eq!(
            screen.mock.integer(heading[0], PropertyKind::TypeRole),
            Some(i64::from(u16::from(TypeRole::Headline))),
            "the sheet's title should be as large as the reference's"
        );
        let sheet = screen
            .mock
            .ancestor(heading[0], WidgetKind::Sheet)
            .expect("composing is not in a sheet");
        assert!(
            !screen.mock.is_open(sheet),
            "the sheet is open before anyone asked"
        );

        let add = screen.mock.named_on_the_page(ADD_TASK)[0];
        screen.click(add);
        assert!(
            screen.mock.is_open(sheet),
            "the add button did not open composing"
        );
        // The sheet closes the way the reference's does, from a button at its foot.
        assert_eq!(
            screen
                .mock
                .named("Close")
                .into_iter()
                .filter(|button| screen.mock.ancestor(*button, WidgetKind::Sheet) == Some(sheet))
                .count(),
            1
        );

        let field = screen.mock.field_in_an_open_sheet();
        screen.submit(field, "Buy milk");
        assert!(
            !screen.mock.is_open(sheet),
            "adding a task should close the sheet"
        );
        let written = screen.written_lists();
        assert!(
            written
                .last()
                .is_some_and(|(_, text)| text.ends_with(&format!("{SAVED_TASKS}\t0\tBuy milk\n"))),
            "the task added in the sheet was not saved"
        );
        assert_eq!(screen.mock.item_count, Some(SAVED_TASKS as i64 + 1));
    }

    /// The reference heads every list with two lines, the second nearly as large as the
    /// first, and puts a single action beside them rather than a row of worded buttons.
    #[test]
    fn fr22_the_header_is_two_large_lines_and_one_action() {
        let screen = Screen::at(420.0);
        let bar = screen.mock.nodes_of_kind(WidgetKind::TopAppBar)[0];
        let mut lines: Vec<u32> = screen
            .mock
            .nodes_of_kind(WidgetKind::Text)
            .into_iter()
            .filter(|text| screen.mock.ancestor(*text, WidgetKind::TopAppBar) == Some(bar))
            .collect();
        lines.sort_by_key(|text| screen.mock.positions[text]);
        assert_eq!(lines.len(), 2, "the header should be two lines");
        let roles: Vec<Option<i64>> = lines
            .iter()
            .map(|text| screen.mock.integer(*text, PropertyKind::TypeRole))
            .collect();
        assert_eq!(
            roles,
            vec![
                Some(i64::from(u16::from(TypeRole::Headline))),
                Some(i64::from(u16::from(TypeRole::Title))),
            ],
            "the second line should be large, as the reference's is, not a small label"
        );

        let actions: Vec<u32> = screen
            .mock
            .nodes_of_kind(WidgetKind::Button)
            .into_iter()
            .filter(|button| screen.mock.ancestor(*button, WidgetKind::TopAppBar) == Some(bar))
            .collect();
        assert_eq!(actions.len(), 1, "the header should carry one action");
        assert!(
            screen
                .mock
                .texts
                .get(&actions[0])
                .is_none_or(|text| text.is_empty()),
            "the header's action should be a glyph, not a word"
        );
        assert_eq!(
            screen.mock.integer(actions[0], PropertyKind::Icon),
            Some(i64::from(u16::from(IconRole::More)))
        );
    }

    /// Each task is a card of its own, as in the reference, and a page that holds tasks
    /// still to do and tasks already done heads each kind. A page of one kind needs no
    /// heading, because its title already says which.
    #[test]
    fn fr22_tasks_are_cards_under_a_heading_for_each_kind() {
        const MIXED: usize = 30;
        let mut screen = Screen::loaded(Some(&mixed_tasks(MIXED)));
        assert_eq!(
            screen.mock.item_count,
            Some(MIXED as i64 + 2),
            "the page should hold every task and one heading for each kind"
        );
        screen.request_range(0, MIXED + 2);
        for heading in [TO_DO_SECTION, COMPLETED_SECTION] {
            let nodes = screen
                .mock
                .nodes_of_kind_with_text(WidgetKind::Text, heading);
            assert_eq!(nodes.len(), 1, "the page has no heading {heading}");
            assert!(screen.mock.is_inside(nodes[0], WidgetKind::LazyColumn));
            assert!(!screen.mock.is_inside(nodes[0], WidgetKind::Card));
        }
        let titles: Vec<u32> = screen
            .mock
            .texts
            .iter()
            .filter(|(node, text)| {
                screen.mock.widgets.contains_key(node) && text.starts_with("Generated task ")
            })
            .map(|(node, _)| *node)
            .collect();
        assert_eq!(titles.len(), MIXED);
        for title in titles {
            assert!(
                screen.mock.is_inside(title, WidgetKind::Card),
                "a task is not on a card of its own"
            );
        }
        assert_eq!(screen.mock.count_of(WidgetKind::Card), MIXED);
        assert!(
            screen
                .mock
                .nodes_of_kind(WidgetKind::Divider)
                .into_iter()
                .all(|divider| !screen.mock.is_inside(divider, WidgetKind::LazyColumn)),
            "the cards are still one container split by hairlines"
        );

        let active = screen
            .mock
            .nodes_of_kind_with_text(WidgetKind::NavigationItem, "Active")[0];
        screen.click(active);
        assert!(
            screen
                .mock
                .nodes_of_kind_with_text(WidgetKind::Text, TO_DO_SECTION)
                .is_empty(),
            "a page of one kind should not head it"
        );
        assert_eq!(screen.mock.item_count, Some((MIXED - MIXED / 3) as i64));
    }

    /// "New list" makes a list. The list is a destination under its own heading, its page
    /// is where a task added on it goes, and both survive a reload. A card on any other
    /// page says which list its task is kept in.
    #[test]
    fn fr22_a_new_list_is_a_destination_that_keeps_what_is_added_to_it() {
        let mut first_run = Screen::loaded(None);
        let back = first_run.mock.named_on_the_page(SHOW_LISTS)[0];
        first_run.click(back);
        let new_list = first_run.mock.buttons_off_the_navigation(NEW_LIST_LABEL)[0];
        first_run.click(new_list);
        let field = first_run.mock.field_in_an_open_sheet();
        first_run.submit(field, "Errands");

        assert_eq!(
            first_run
                .mock
                .nodes_of_kind_with_text(WidgetKind::Text, "Errands")
                .len(),
            1,
            "making a list should go to its page"
        );
        let destination = first_run
            .mock
            .nodes_of_kind_with_text(WidgetKind::NavigationItem, "Errands");
        assert_eq!(destination.len(), 1, "a new list is not a destination");
        assert_eq!(
            first_run.mock.string(destination[0], PropertyKind::Section),
            Some(LISTS_LABEL)
        );
        assert_eq!(first_run.written_lists()[0].1, "L\t1\tErrands\n");
        first_run.settle();

        let add = first_run.mock.named_on_the_page(ADD_TASK)[0];
        first_run.click(add);
        assert_eq!(
            first_run.mock.nodes_with_text("In Errands").len(),
            1,
            "the sheet should say which list the task goes into"
        );
        let field = first_run.mock.field_in_an_open_sheet();
        first_run.submit(field, "Buy stamps");
        let saved = first_run
            .written_lists()
            .last()
            .map(|(_, text)| text.clone())
            .expect("adding a task wrote nothing");
        assert_eq!(saved, "L\t1\tErrands\nT\t1\t0\t1\t\tBuy stamps\n");
        first_run.settle();
        drop(first_run);

        let mut reloaded = Screen::loaded(Some(&saved));
        let destination = reloaded
            .mock
            .nodes_of_kind_with_text(WidgetKind::NavigationItem, "Errands");
        assert_eq!(destination.len(), 1, "the list did not survive a reload");
        reloaded.request_range(0, WINDOW);
        let caption = reloaded.mock.nodes_with_text("Errands");
        assert!(
            caption
                .iter()
                .any(|node| reloaded.mock.is_inside(*node, WidgetKind::Card)),
            "a card on All should say which list its task is in"
        );

        reloaded.click(destination[0]);
        reloaded.request_range(0, WINDOW);
        assert_eq!(reloaded.mock.nodes_with_text("Buy stamps").len(), 1);
        assert!(
            reloaded
                .mock
                .nodes_with_text("Errands")
                .iter()
                .all(|node| !reloaded.mock.is_inside(*node, WidgetKind::Card)),
            "a card on its own list's page repeats the list's name"
        );
    }

    /// Search is a destination that opens a sheet, and the page behind it narrows to the
    /// tasks that match as the field is typed into.
    #[test]
    fn fr22_search_narrows_the_page_to_what_matches() {
        let mut screen = Screen::new();
        let search = screen
            .mock
            .nodes_of_kind_with_text(WidgetKind::NavigationItem, SEARCH_LABEL)[0];
        screen.click(search);
        let field = screen
            .mock
            .value_handlers
            .keys()
            .copied()
            .filter(|node| screen.mock.widgets.contains_key(node))
            .find(|node| {
                screen
                    .mock
                    .ancestor(*node, WidgetKind::Sheet)
                    .is_some_and(|sheet| screen.mock.is_open(sheet))
            })
            .expect("search did not open a sheet with a field");
        screen.type_into(field, "task 42");
        // "Generated task 42", 420 to 429 and 4200 to 4299.
        assert_eq!(screen.mock.item_count, Some(111));
        assert_eq!(
            screen
                .mock
                .nodes_with_text("111 matching \u{201c}task 42\u{201d}")
                .len(),
            1,
            "the header should say what the page is narrowed to"
        );

        let clear = screen
            .mock
            .nodes_of_kind_with_text(WidgetKind::Button, "Clear search")[0];
        screen.click(clear);
        assert_eq!(screen.mock.item_count, Some(SAVED_TASKS as i64));
    }
}
