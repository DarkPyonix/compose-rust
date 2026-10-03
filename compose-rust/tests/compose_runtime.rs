//! The slot table runtime through its public surface: composables, state, effects and the
//! Host, with no Dioxus anywhere in the code under test.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{HostEvent, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{EventPayload, PropertyKind};
use compose_rust::ui::*;
use std::cell::{Cell, RefCell};
use std::rc::Rc;
use support::{Tree, kinds};

thread_local! {
    static RUNS: RefCell<Vec<&'static str>> = const { RefCell::new(Vec::new()) };
    static LOG: RefCell<Vec<String>> = const { RefCell::new(Vec::new()) };
}

fn ran(name: &'static str) {
    RUNS.with_borrow_mut(|runs| runs.push(name));
}

fn runs_of(name: &str) -> usize {
    RUNS.with_borrow(|runs| runs.iter().filter(|run| **run == name).count())
}

fn note(line: impl Into<String>) {
    LOG.with_borrow_mut(|log| log.push(line.into()));
}

fn take_log() -> Vec<String> {
    LOG.with_borrow_mut(std::mem::take)
}

fn reset() {
    RUNS.with_borrow_mut(Vec::clear);
    LOG.with_borrow_mut(Vec::clear);
}

/// Builds a Host for `content` and applies its first batch.
fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

/// Serves one frame and applies it, returning the kinds of record it carried.
fn frame(host: &mut ComposeHost, tree: &mut Tree) -> Vec<String> {
    let batch = host.render_frame(0).expect("the frame encodes").to_vec();
    tree.apply(&batch);
    kinds(&batch)
}

/// Clicks the nth button labelled `label`, returning the kinds of record the click sent.
fn click(host: &mut ComposeHost, tree: &mut Tree, label: &str, nth: usize) -> Vec<String> {
    let (node_id, handler_id) = tree.find("Button", Some(label), nth, PropertyKind::OnClick);
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .expect("the click is accepted");
    let batch = batch.to_vec();
    tree.apply(&batch);
    kinds(&batch)
}

// ----- skipping and scope invalidation ----------------------------------------------------

#[composable]
fn Reader(count: MutableState<i32>) {
    ran("reader");
    Text(format!("{}", count.get()));
}

#[composable]
fn Sibling() {
    ran("sibling");
    Text("fixed");
}

#[composable]
fn Parent(count: MutableState<i32>) {
    ran("parent");
    Column().content(|| {
        Reader(count.clone());
        Sibling();
    });
}

#[test]
fn fr39_a_state_change_runs_only_the_scope_that_read_it() {
    reset();
    let count = mutable_state_of(0);
    let state = count.clone();
    let (mut host, mut tree) = start(move || Parent(state.clone()));
    assert_eq!(
        (runs_of("parent"), runs_of("reader"), runs_of("sibling")),
        (1, 1, 1)
    );

    count.set(1);
    let records = frame(&mut host, &mut tree);

    assert_eq!(
        (runs_of("parent"), runs_of("reader"), runs_of("sibling")),
        (1, 2, 1),
        "only the scope that read the state ran again"
    );
    assert_eq!(
        records,
        vec!["SetProp Text"],
        "one text changed, one record"
    );
    assert_eq!(tree.texts(), vec!["1", "fixed"]);
}

#[composable]
fn Label(text: String) {
    ran("label");
    Text(text);
}

#[composable]
fn Screen(count: MutableState<i32>) {
    ran("screen");
    let shown = count.get();
    Column().content(|| {
        Text(format!("{shown}"));
        Label("the same every time".to_owned());
    });
}

#[test]
fn fr39_a_composable_whose_parameters_are_unchanged_is_skipped() {
    reset();
    let count = mutable_state_of(0);
    let state = count.clone();
    let (mut host, mut tree) = start(move || Screen(state.clone()));
    count.set(5);
    let records = frame(&mut host, &mut tree);
    assert_eq!(runs_of("screen"), 2, "the scope that read the state ran");
    assert_eq!(
        runs_of("label"),
        1,
        "its child with equal parameters did not"
    );
    assert_eq!(records, vec!["SetProp Text"]);
    assert_eq!(tree.texts(), vec!["5", "the same every time"]);
}

#[test]
fn fr39_an_unchanged_frame_sends_nothing() {
    reset();
    let count = mutable_state_of(0);
    let state = count.clone();
    let (mut host, mut tree) = start(move || Parent(state.clone()));
    assert!(frame(&mut host, &mut tree).is_empty());
    count.set(0);
    assert!(
        frame(&mut host, &mut tree).is_empty(),
        "an equal write changes nothing under the default policy"
    );
    assert_eq!(runs_of("reader"), 1);
}

// ----- positional and keyed identity ------------------------------------------------------

/// Remembers which call site created its slot and how often it has composed, and notes
/// both. A call site that keeps its own slot notes its own name and counts up; one handed
/// another's slot notes the other's name.
fn bump(name: &str) {
    let owner = name.to_owned();
    let slot = remember(move || Rc::new((owner, Cell::new(0_u32))));
    slot.1.set(slot.1.get() + 1);
    note(format!("{name}:{}#{}", slot.0, slot.1.get()));
}

#[composable]
fn WithBranch(show: bool) {
    if show {
        bump("first");
    }
    bump("second");
}

#[test]
fn fr39_a_call_site_keeps_its_own_state_when_a_branch_before_it_disappears() {
    reset();
    let show = mutable_state_of(true);
    let state = show.clone();
    let (mut host, mut tree) = start(move || WithBranch(state.get()));
    show.set(false);
    frame(&mut host, &mut tree);
    show.set(true);
    frame(&mut host, &mut tree);
    assert_eq!(
        take_log(),
        vec![
            "first:first#1",
            "second:second#1",
            // Hidden: the call site after the branch keeps its own slot.
            "second:second#2",
            // Shown again: the branch starts from nothing rather than from what it had.
            "first:first#1",
            "second:second#3",
        ]
    );
}

/// The same body without the groups `#[composable]` puts around a branch: the control. A
/// slot table without groups addresses state by flat position, and this is what that does.
fn without_branch_groups(show: bool) {
    if show {
        bump("first");
    }
    bump("second");
}

#[test]
fn fr39_without_branch_groups_a_call_site_is_handed_another_ones_state() {
    reset();
    let show = mutable_state_of(true);
    let state = show.clone();
    let (mut host, mut tree) = start(move || without_branch_groups(state.get()));
    show.set(false);
    frame(&mut host, &mut tree);
    assert_eq!(
        take_log(),
        vec!["first:first#1", "second:second#1", "second:first#2"],
        "the control must show the corruption the groups exist to prevent"
    );
}

#[composable]
fn Counted(rows: usize) {
    for index in 0..rows {
        bump(&format!("row{index}"));
    }
}

#[test]
fn fr39_a_shorter_loop_drops_the_iterations_it_lost_and_a_longer_one_starts_them_fresh() {
    reset();
    let rows = mutable_state_of(3_usize);
    let state = rows.clone();
    let (mut host, mut tree) = start(move || Counted(state.get()));
    rows.set(1);
    frame(&mut host, &mut tree);
    rows.set(3);
    frame(&mut host, &mut tree);
    assert_eq!(
        take_log(),
        vec![
            "row0:row0#1",
            "row1:row1#1",
            "row2:row2#1",
            "row0:row0#2",
            "row0:row0#3",
            "row1:row1#1",
            "row2:row2#1",
        ]
    );
}

#[composable]
fn Mode(mode: u8) {
    match mode {
        0 => bump("zero"),
        1 => bump("one"),
        _ => bump("other"),
    }
    bump("after");
}

#[test]
fn fr39_a_match_arm_that_changes_takes_its_state_with_it() {
    reset();
    let mode = mutable_state_of(0_u8);
    let state = mode.clone();
    let (mut host, mut tree) = start(move || Mode(state.get()));
    mode.set(1);
    frame(&mut host, &mut tree);
    mode.set(0);
    frame(&mut host, &mut tree);
    assert_eq!(
        take_log(),
        vec![
            "zero:zero#1",
            "after:after#1",
            "one:one#1",
            "after:after#2",
            "zero:zero#1",
            "after:after#3",
        ]
    );
}

#[composable]
fn Early(stop: bool) {
    bump("before");
    if stop {
        return;
    }
    bump("tail");
}

#[composable]
fn Looping(limit: usize) {
    for index in 0..5 {
        if index == 1 {
            continue;
        }
        if index == limit {
            break;
        }
        bump(&format!("item{index}"));
    }
}

#[composable]
fn Fallible(fail: bool) -> Result<(), String> {
    bump("start");
    if fail {
        Err::<(), String>("stopped".to_owned())?;
    }
    bump("finish");
    Ok(())
}

#[composable]
fn Panicky(panic: bool) {
    bump("calm");
    if panic {
        panic!("a deliberate panic inside a composable");
    }
}

#[composable]
fn ControlFlow(chaos: MutableState<bool>) {
    let on = chaos.get();
    Early(on);
    Looping(if on { 3 } else { 5 });
    let _ = Fallible(on);
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| Panicky(on)));
    bump("after_all");
}

#[test]
fn fr39_groups_close_on_return_continue_break_question_mark_and_unwind() {
    reset();
    let chaos = mutable_state_of(false);
    let state = chaos.clone();
    let (mut host, mut tree) = start(move || ControlFlow(state.clone()));
    chaos.set(true);
    frame(&mut host, &mut tree);
    chaos.set(false);
    frame(&mut host, &mut tree);
    let log = take_log();
    for line in &log {
        let (name, rest) = line
            .split_once(':')
            .expect("every line names its call site");
        let owner = rest.split('#').next().unwrap();
        assert_eq!(name, owner, "{line}: a call site read another one's slot");
    }
    let after: Vec<&String> = log
        .iter()
        .filter(|line| line.starts_with("after_all"))
        .collect();
    assert_eq!(
        after,
        vec![
            "after_all:after_all#1",
            "after_all:after_all#2",
            "after_all:after_all#3"
        ],
        "the call site after every early exit kept its own slot through all three passes"
    );
}

#[test]
fn fr39_a_keyed_item_that_moves_is_moved_and_keeps_what_it_remembered() {
    reset();
    let order = mutable_state_of(vec![1_u32, 2, 3]);
    let state = order.clone();
    let (mut host, mut tree) = start(move || {
        let state = state.clone();
        Column().content(move || {
            for id in state.get() {
                key(id, || {
                    bump(&format!("item{id}"));
                    Text(format!("item {id}"));
                });
            }
        });
    });
    take_log();
    order.set(vec![3, 1, 2]);
    let records = frame(&mut host, &mut tree);
    assert!(
        !records.is_empty() && records.iter().all(|record| record == "Move"),
        "a reorder is moves and nothing else, got {records:?}"
    );
    assert_eq!(tree.texts(), vec!["item 3", "item 1", "item 2"]);
    assert_eq!(
        take_log(),
        vec!["item3:item3#2", "item1:item1#2", "item2:item2#2"],
        "each item's remembered value went with it"
    );
}

// ----- state ------------------------------------------------------------------------------

#[test]
fn fr39_writes_from_a_worker_are_one_recomposition_on_the_next_frame() {
    reset();
    let count = mutable_state_of(0);
    let state = count.clone();
    let (mut host, mut tree) = start(move || Parent(state.clone()));
    let worker = count.clone();
    std::thread::spawn(move || {
        worker.set(1);
        worker.set(2);
        worker.set(3);
    })
    .join()
    .unwrap();
    let records = frame(&mut host, &mut tree);
    assert_eq!(runs_of("reader"), 2, "three writes, one recomposition");
    assert_eq!(records, vec!["SetProp Text"]);
    assert_eq!(tree.texts(), vec!["3", "fixed"]);
}

#[composable]
fn WritesWhileComposing(target: MutableState<i32>) {
    ran("writer");
    target.set(7);
}

#[test]
fn fr39_a_write_during_composition_waits_for_the_next_frame() {
    reset();
    let target = mutable_state_of(0);
    let state = target.clone();
    let (mut host, mut tree) = start(move || {
        Reader(state.clone());
        WritesWhileComposing(state.clone());
    });
    assert_eq!(
        tree.texts(),
        vec!["0"],
        "the write is not composed in the call it was made in"
    );
    frame(&mut host, &mut tree);
    assert_eq!(tree.texts(), vec!["7"]);
    assert_eq!(
        runs_of("writer"),
        1,
        "the writer's parameters did not change"
    );
}

#[composable]
fn ClickCounter() {
    let count = remember(|| mutable_state_of(0));
    Column().content(|| {
        Text(format!("{}", count.get()));
        let more = count.clone();
        Button("More").on_click(move || more.update(|count| *count += 1));
    });
}

#[test]
fn fr39_a_write_in_a_handler_is_recomposed_in_the_same_call() {
    reset();
    let (mut host, mut tree) = start(ClickCounter);
    let records = click(&mut host, &mut tree, "More", 0);
    assert_eq!(records, vec!["SetProp Text"]);
    assert_eq!(tree.texts(), vec!["1", "More"]);
    click(&mut host, &mut tree, "More", 0);
    assert_eq!(tree.texts(), vec!["2", "More"]);
}

#[composable]
fn Parity(count: MutableState<i32>) {
    let source = count.clone();
    let even = remember(move || derived_state_of(move || source.get() % 2 == 0));
    ran("parity");
    Text(if even.get() { "even" } else { "odd" });
}

#[test]
fn fr39_a_derived_state_runs_its_reader_only_when_its_value_changes() {
    reset();
    let count = mutable_state_of(0);
    let state = count.clone();
    let (mut host, mut tree) = start(move || Parity(state.clone()));
    count.set(2);
    assert!(frame(&mut host, &mut tree).is_empty());
    assert_eq!(
        runs_of("parity"),
        1,
        "still even, so the reader did not run"
    );
    count.set(3);
    frame(&mut host, &mut tree);
    assert_eq!(runs_of("parity"), 2);
    assert_eq!(tree.texts(), vec!["odd"]);
}

// ----- effects ----------------------------------------------------------------------------

/// Notes when it is dropped, which for a future is when its task was cancelled.
struct NoteOnDrop(String);

impl Drop for NoteOnDrop {
    fn drop(&mut self) {
        note(std::mem::take(&mut self.0));
    }
}

#[composable]
fn Effects(id: u32) {
    side_effect(|| note("side"));
    launched_effect(id, move || async move {
        note(format!("launched {id}"));
        let _cancelled = NoteOnDrop(format!("cancelled {id}"));
        std::future::pending::<()>().await;
    });
    disposable_effect(id, move || {
        note(format!("setup {id}"));
        on_dispose(move || note(format!("cleanup {id}")))
    });
}

#[composable]
fn EffectsRoot(id: MutableState<u32>, present: MutableState<bool>) {
    if present.get() {
        Effects(id.get());
    }
}

#[test]
fn fr39_effects_run_after_composition_restart_on_a_new_key_and_clean_up_when_they_leave() {
    reset();
    let id = mutable_state_of(1_u32);
    let present = mutable_state_of(true);
    let (state, shown) = (id.clone(), present.clone());
    let (mut host, mut tree) = start(move || EffectsRoot(state.clone(), shown.clone()));
    assert_eq!(take_log(), vec!["side", "setup 1", "launched 1"]);

    id.set(2);
    frame(&mut host, &mut tree);
    assert_eq!(
        take_log(),
        vec!["cancelled 1", "cleanup 1", "side", "setup 2", "launched 2"]
    );

    present.set(false);
    frame(&mut host, &mut tree);
    assert_eq!(take_log(), vec!["cancelled 2", "cleanup 2"]);

    // Nothing left to run or clean up.
    present.set(false);
    frame(&mut host, &mut tree);
    assert!(take_log().is_empty());
}

#[composable]
fn FrameClock() {
    let time = remember(|| mutable_state_of(0_u64));
    let writer = time.clone();
    launched_effect((), move || async move {
        loop {
            let now = with_frame_nanos(|now| now).await;
            writer.set(now);
        }
    });
    Text(format!("{}", time.get()));
}

#[test]
fn fr39_a_future_waiting_for_a_frame_gets_that_frames_time() {
    reset();
    let (mut host, mut tree) = start(FrameClock);
    for now in [100_u64, 200, 300] {
        let batch = host.render_frame(now).unwrap().to_vec();
        tree.apply(&batch);
        assert_eq!(tree.texts(), vec![now.to_string()]);
    }
}

#[composable]
fn Worked() {
    let answer = remember(|| mutable_state_of(0_u32));
    let writer = answer.clone();
    launched_effect((), move || async move {
        delay(std::time::Duration::from_millis(5)).await;
        let value = with_worker(|| 21 * 2).await;
        writer.set(value);
    });
    Text(format!("{}", answer.get()));
}

#[test]
fn fr39_work_handed_to_a_worker_resumes_on_the_ui_thread_and_recomposes() {
    reset();
    let (mut host, mut tree) = start(Worked);
    for attempt in 0..2_000 {
        let batch = host.render_frame(attempt).unwrap().to_vec();
        tree.apply(&batch);
        if tree.texts() == vec!["42"] {
            return;
        }
        std::thread::sleep(std::time::Duration::from_millis(1));
    }
    panic!(
        "the worker's answer never reached the screen: {:?}",
        tree.texts()
    );
}

// ----- lists ------------------------------------------------------------------------------

#[composable]
fn Rows(count: usize) {
    ran("rows");
    LazyColumn().content(move |list| {
        list.items(count, |index| {
            Text(format!("row {index}"));
        });
    });
}

fn request_range(host: &mut ComposeHost, tree: &mut Tree, start: u32, count: u32) -> Vec<String> {
    let (node_id, handler_id) = tree.find("LazyColumn", None, 0, PropertyKind::OnRangeRequested);
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::RangeRequested { start, count },
        })
        .unwrap();
    let batch = batch.to_vec();
    tree.apply(&batch);
    kinds(&batch)
}

#[test]
fn fr39_a_lazy_list_composes_the_window_it_is_asked_for_and_nothing_above_it() {
    reset();
    let (mut host, mut tree) = start(|| Rows(100));
    assert!(
        tree.texts().is_empty(),
        "nothing is composed before a window is asked for"
    );
    request_range(&mut host, &mut tree, 0, 3);
    assert_eq!(tree.texts(), vec!["row 0", "row 1", "row 2"]);

    let records = request_range(&mut host, &mut tree, 1, 3);
    assert_eq!(tree.texts(), vec!["row 1", "row 2", "row 3"]);
    assert_eq!(
        records.iter().filter(|record| *record == "Remove").count(),
        1,
        "the row that left the window is removed: {records:?}"
    );
    assert_eq!(
        records
            .iter()
            .filter(|record| *record == "SetProp Text")
            .count(),
        1,
        "only the row that entered the window writes its text: {records:?}"
    );
    assert!(
        !records.iter().any(|record| record == "Move"),
        "the rows that stayed are left where they are: {records:?}"
    );
    assert_eq!(
        runs_of("rows"),
        1,
        "scrolling ran the items, not the list's caller"
    );
}

// ----- the Host as a whole ----------------------------------------------------------------

#[composable]
fn Deleter() {
    let deleted = remember(|| mutable_state_of(false));
    let restore = deleted.clone();
    let delete = deleted.clone();
    Column().content(|| {
        Text(if deleted.get() { "gone" } else { "here" });
        Button("Delete").on_click(move || {
            delete.set(true);
            let restore = restore.clone();
            Message::new("Deleted")
                .with_action("Undo", move |()| restore.set(false))
                .show();
        });
    });
}

#[test]
fn fr39_a_message_action_runs_without_dioxus() {
    reset();
    let (mut host, mut tree) = start(Deleter);
    let (node_id, handler_id) = tree.find("Button", Some("Delete"), 0, PropertyKind::OnClick);
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .unwrap();
    let action = decode_batch(batch)
        .unwrap()
        .into_iter()
        .find_map(|mutation| match mutation {
            compose_rust::protocol::Mutation::ShowMessage { handler_id, .. } => Some(handler_id),
            _ => None,
        })
        .expect("the message rode out on the batch of the click that caused it");
    let batch = batch.to_vec();
    tree.apply(&batch);
    assert_eq!(tree.texts(), vec!["gone", "Delete"]);

    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: 0,
            handler_id: action,
            payload: EventPayload::Clicked,
        })
        .unwrap();
    let batch = batch.to_vec();
    tree.apply(&batch);
    assert_eq!(tree.texts(), vec!["here", "Delete"]);
}

#[composable]
fn Greeting(name: &'static str) {
    ran("greeting");
    Text(format!("Hello, {name}"));
}

#[test]
fn fr39_a_static_string_parameter_is_compared_and_skipped() {
    reset();
    let tick = mutable_state_of(0);
    let state = tick.clone();
    let (mut host, mut tree) = start(move || {
        let _ = state.get();
        Greeting("there");
    });
    tick.set(1);
    frame(&mut host, &mut tree);
    assert_eq!(runs_of("greeting"), 1);
}
