//! Copy state handles: an arena index and a generation, owned by the `remember` call that
//! made them, through the public surface and a Host with no Dioxus anywhere.

mod support;

use compose_rust::ComposeHost;
use compose_rust::runtime::*;
use compose_rust::ui::*;
use std::cell::{Cell, RefCell};
use std::collections::HashSet;
use support::{Tree, kinds};

thread_local! {
    static DROPS: Cell<u32> = const { Cell::new(0) };
    static HANDLE: Cell<Option<MutableState<Tracked>>> = const { Cell::new(None) };
    static HANDLES: RefCell<Vec<(u32, MutableState<Tracked>)>> = const { RefCell::new(Vec::new()) };
    static SITE_LINE: Cell<u32> = const { Cell::new(0) };
    static READER_RUNS: Cell<u32> = const { Cell::new(0) };
}

/// A value that counts its drops on the thread that composes.
#[derive(Clone, Debug, PartialEq)]
struct Tracked(i32);

impl Drop for Tracked {
    fn drop(&mut self) {
        DROPS.with(|drops| drops.set(drops.get() + 1));
    }
}

fn drops() -> u32 {
    DROPS.with(Cell::get)
}

fn reset() {
    DROPS.with(|drops| drops.set(0));
    HANDLE.with(|handle| handle.set(None));
    HANDLES.with_borrow_mut(Vec::clear);
    READER_RUNS.with(|runs| runs.set(0));
}

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

fn frame(host: &mut ComposeHost, tree: &mut Tree) -> Vec<String> {
    let batch = host.render_frame(0).expect("the frame encodes").to_vec();
    tree.apply(&batch);
    kinds(&batch)
}

#[composable]
fn Child() {
    SITE_LINE.with(|line| line.set(line!() + 1));
    let state = remember(|| {
        let state = mutable_state_of(Tracked(0));
        HANDLE.with(|handle| handle.set(Some(state)));
        state
    });
    Text(format!("child {}", state.with(|tracked| tracked.0)));
}

#[composable]
fn Gate(show: MutableState<bool>) {
    if show.get() {
        Child();
    }
    Text("after");
}

/// Starts a Gate that shows its child and returns the child's handle.
fn gate() -> (MutableState<bool>, MutableState<Tracked>, ComposeHost, Tree) {
    reset();
    let show = mutable_state_of(true);
    let (host, tree) = start(move || Gate(show));
    let handle = HANDLE
        .with(Cell::take)
        .expect("the child remembered its state");
    (show, handle, host, tree)
}

/// Removes the child and lets the recomposition apply.
fn remove_child(show: MutableState<bool>, host: &mut ComposeHost, tree: &mut Tree) {
    show.set(false);
    frame(host, tree);
}

// ----- Copy -------------------------------------------------------------------------------

#[test]
fn fr39_state_handle_is_copy_in_the_type_system() {
    fn assert_copy<T: Copy>() {}
    assert_copy::<MutableState<i32>>();
    assert_copy::<MutableState<String>>();
}

#[test]
fn fr39_state_handle_is_copy_into_two_move_closures_without_clone() {
    let count = mutable_state_of(0);
    let add_one = move || count.update(|value| *value += 1);
    let add_ten = move || count.update(|value| *value += 10);
    add_one();
    add_ten();
    assert_eq!(count.get(), 11, "both closures changed the same value");
}

// ----- release ----------------------------------------------------------------------------

#[test]
fn fr39_state_released_value_drops_once_after_the_group_is_removed() {
    let (show, handle, mut host, mut tree) = gate();
    assert_eq!(drops(), 0);
    assert!(!handle.is_released());
    remove_child(show, &mut host, &mut tree);
    assert_eq!(
        drops(),
        1,
        "the value dropped when the recomposition applied"
    );
    assert!(handle.is_released());
    frame(&mut host, &mut tree);
    assert_eq!(drops(), 1, "and only once");
}

#[test]
fn fr39_state_released_value_survives_a_recomposition_that_keeps_the_group() {
    let (show, handle, mut host, mut tree) = gate();
    handle.set(Tracked(7));
    frame(&mut host, &mut tree);
    show.set(true);
    show.update(|_| {});
    frame(&mut host, &mut tree);
    assert!(!handle.is_released());
    assert_eq!(handle.with(|tracked| tracked.0), 7);
    // The replaced value of the write above is the only drop so far.
    assert_eq!(drops(), 1);
}

#[composable]
fn Item(id: u32) {
    let state = remember(|| {
        let state = mutable_state_of(Tracked(id as i32));
        HANDLES.with_borrow_mut(|handles| handles.push((id, state)));
        state
    });
    Text(format!("item {} {}", id, state.with(|tracked| tracked.0)));
}

#[composable]
fn Items(order: MutableState<Vec<u32>>) {
    for id in order.get() {
        key(id, || Item(id));
    }
}

#[test]
fn fr39_state_released_keyed_item_that_moves_keeps_its_value_and_handle() {
    reset();
    let order = mutable_state_of(vec![1_u32, 2, 3]);
    let (mut host, mut tree) = start(move || Items(order));
    let before = HANDLES.with_borrow(Clone::clone);
    order.set(vec![3, 1, 2]);
    frame(&mut host, &mut tree);
    assert_eq!(drops(), 0, "a move is not a release");
    for (id, handle) in &before {
        assert!(!handle.is_released(), "item {id} kept its state");
        assert_eq!(handle.with(|tracked| tracked.0), *id as i32);
    }
    assert_eq!(
        HANDLES.with_borrow(Vec::len),
        3,
        "no item made its state again"
    );
}

#[test]
fn fr39_state_released_keyed_item_that_leaves_is_released_and_the_others_are_not() {
    reset();
    let order = mutable_state_of(vec![1_u32, 2, 3]);
    let (mut host, mut tree) = start(move || Items(order));
    let handles = HANDLES.with_borrow(Clone::clone);
    order.set(vec![1, 3]);
    frame(&mut host, &mut tree);
    assert_eq!(drops(), 1);
    for (id, handle) in handles {
        assert_eq!(handle.is_released(), id == 2, "item {id}");
    }
}

// ----- use after release ------------------------------------------------------------------

#[test]
fn fr39_state_use_after_release_set_is_a_no_op() {
    let (show, handle, mut host, mut tree) = gate();
    let other = mutable_state_of(1);
    remove_child(show, &mut host, &mut tree);
    let texts = tree.texts();
    handle.set(Tracked(5));
    handle.update(|tracked| tracked.0 += 1);
    frame(&mut host, &mut tree);
    assert_eq!(tree.texts(), texts, "the screen did not change");
    assert_eq!(other.get(), 1, "no other state changed");
    assert!(handle.is_released());
}

#[test]
fn fr39_state_use_after_release_warns_once_in_debug() {
    let (show, handle, mut host, mut tree) = gate();
    remove_child(show, &mut host, &mut tree);
    for round in 0..3 {
        handle.set(Tracked(round));
        handle.update(|tracked| tracked.0 += 1);
    }
    let expected = usize::from(cfg!(debug_assertions));
    assert_eq!(handle.released_warning_count(), expected);
}

#[test]
fn fr39_state_use_after_release_get_panics_with_the_creation_site() {
    let (show, handle, mut host, mut tree) = gate();
    let line = SITE_LINE.with(Cell::get);
    remove_child(show, &mut host, &mut tree);
    let panic = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| handle.get()))
        .expect_err("a released state cannot be read");
    let message = panic
        .downcast_ref::<String>()
        .cloned()
        .or_else(|| panic.downcast_ref::<&str>().map(|text| (*text).to_owned()))
        .expect("the panic carries a message");
    assert!(message.contains("Released"), "{message}");
    assert!(
        message.contains(&format!("{}:{}", file!(), line)),
        "{message} should name {}:{line}",
        file!()
    );
}

#[test]
fn fr39_state_use_after_release_try_returns_released() {
    let (show, handle, mut host, mut tree) = gate();
    assert!(handle.try_get().is_ok());
    remove_child(show, &mut host, &mut tree);
    assert_eq!(handle.try_get(), Err(StateError::Released));
    assert_eq!(handle.try_set(Tracked(1)), Err(StateError::Released));
    assert_eq!(handle.try_update(|_| {}), Err(StateError::Released));
}

#[test]
fn fr39_state_use_after_release_does_not_reach_a_reused_slot() {
    let (show, old, mut host, mut tree) = gate();
    remove_child(show, &mut host, &mut tree);
    show.set(true);
    frame(&mut host, &mut tree);
    let fresh = HANDLE.with(Cell::take).expect("the child made a new state");
    assert_ne!(old, fresh, "a new state is not the old handle");
    fresh.set(Tracked(42));
    assert_eq!(old.try_set(Tracked(1)), Err(StateError::Released));
    assert_eq!(old.try_get(), Err(StateError::Released));
    old.set(Tracked(2));
    assert_eq!(
        fresh.with(|tracked| tracked.0),
        42,
        "the old handle did not write the new value"
    );
}

// ----- workers ----------------------------------------------------------------------------

#[test]
fn fr39_state_worker_handle_is_send_and_sync() {
    fn assert_send_sync<T: Send + Sync>() {}
    assert_send_sync::<MutableState<i32>>();
}

#[composable]
fn Reader(count: MutableState<i32>) {
    READER_RUNS.with(|runs| runs.set(runs.get() + 1));
    Text(format!("{}", count.get()));
}

#[test]
fn fr39_state_worker_write_lands_on_the_next_frame() {
    reset();
    let count = mutable_state_of(0);
    let (mut host, mut tree) = start(move || Reader(count));
    std::thread::spawn(move || {
        assert_eq!(count.get(), 0, "a worker reads the current value");
        count.set(5);
    })
    .join()
    .unwrap();
    frame(&mut host, &mut tree);
    assert_eq!(tree.texts(), vec!["5"]);
}

#[test]
fn fr39_state_worker_and_ui_writes_in_one_frame_are_one_recomposition() {
    reset();
    let count = mutable_state_of(0);
    let (mut host, mut tree) = start(move || Reader(count));
    count.set(1);
    std::thread::spawn(move || {
        count.set(2);
        count.update(|value| *value += 1);
    })
    .join()
    .unwrap();
    frame(&mut host, &mut tree);
    assert_eq!(
        READER_RUNS.with(Cell::get),
        2,
        "one run for the first batch, one more"
    );
    assert_eq!(tree.texts(), vec!["3"]);
}

#[test]
fn fr39_state_worker_write_after_the_composition_is_gone_is_ignored() {
    let (show, handle, mut host, mut tree) = gate();
    remove_child(show, &mut host, &mut tree);
    let result = std::thread::spawn(move || {
        handle.set(Tracked(9));
        handle.update(|tracked| tracked.0 += 1);
        handle.try_set(Tracked(10))
    })
    .join()
    .expect("the worker did not panic");
    assert_eq!(result, Err(StateError::Released));
}

// ----- identity ---------------------------------------------------------------------------

#[test]
fn fr39_state_handle_identity_copies_are_equal_and_equal_values_are_not() {
    let first = mutable_state_of(1);
    let copy = first;
    let other = mutable_state_of(1);
    assert_eq!(first, copy);
    assert_ne!(
        first, other,
        "the same value in another state is another state"
    );
    let set: HashSet<_> = [first, copy, other].into_iter().collect();
    assert_eq!(set.len(), 2);
}
