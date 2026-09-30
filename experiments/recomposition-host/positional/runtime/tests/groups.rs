//! What the groups are for, and whether anyone has to type them.
//!
//! The first two tests are the same component twice: once with state addressed by group
//! identity, once by a flat offset. The flat one is not a strawman, it is what a positional
//! slot table is before groups are added, and the point of running it is that the reason
//! for the groups is then a result rather than a claim.

use positional::{Change, compose, composable, emit, remember, remember_flat, reset};
use std::cell::RefCell;

thread_local! {
    /// What each call site saw, in the order the sites ran.
    static SEEN: RefCell<Vec<String>> = const { RefCell::new(Vec::new()) };
}

fn seen() -> Vec<String> {
    SEEN.with(|cell| std::mem::take(&mut *cell.borrow_mut()))
}

fn note(value: &str) {
    SEEN.with(|cell| cell.borrow_mut().push(value.to_string()));
}

/// A branch that stops being taken, with a call site after it that remembers something.
///
/// Nothing here names a group. If the macro is doing its job this is the whole of what
/// someone writes, and the groups that make it correct are in the expansion.
#[composable]
fn with_branch(show: bool) {
    if show {
        let first = remember(|| String::from("first"));
        note(&first.borrow());
        first.borrow_mut().push('!');
    }
    let second = remember(|| String::from("second"));
    note(&second.borrow());
    second.borrow_mut().push('!');
}

/// The same component with state addressed by a flat offset instead.
#[composable]
fn with_branch_flat(show: bool) {
    if show {
        let first = remember_flat(|| String::from("first"));
        note(&first.borrow());
        first.borrow_mut().push('!');
    }
    let second = remember_flat(|| String::from("second"));
    note(&second.borrow());
    second.borrow_mut().push('!');
}

#[test]
fn a_group_keeps_a_call_site_state_when_a_branch_before_it_disappears() {
    reset();
    compose(|| with_branch(true));
    assert_eq!(seen(), vec!["first", "second"]);

    compose(|| with_branch(true));
    assert_eq!(seen(), vec!["first!", "second!"]);

    // The branch stops being taken. The call site after it is now the first thing that
    // remembers anything, and it has to go on seeing its own value.
    compose(|| with_branch(false));
    assert_eq!(seen(), vec!["second!!"]);
}

#[test]
fn a_flat_offset_hands_one_call_site_another_ones_state() {
    reset();
    compose(|| with_branch_flat(true));
    assert_eq!(seen(), vec!["first", "second"]);

    // Offset 0 belonged to the branch. With the branch gone, the call site after it asks for
    // offset 0 and is given what the branch left there. This is the corruption groups exist
    // to prevent, and it is silent: both values are Strings, so nothing complains.
    compose(|| with_branch_flat(false));
    assert_eq!(seen(), vec!["first!"]);
}

#[test]
fn a_branch_that_stops_being_taken_loses_what_it_remembered() {
    reset();
    compose(|| with_branch(true));
    let _ = seen();
    compose(|| with_branch(false));
    let _ = seen();
    // Taken again, the branch starts from nothing rather than from what it held two
    // compositions ago, because its group was not matched and was therefore dropped.
    compose(|| with_branch(true));
    assert_eq!(seen(), vec!["first", "second!!"]);
}

/// A loop body is one call site used many times.
#[composable]
fn counted(width: usize) {
    for _ in 0..width {
        let count = remember(|| 0_u32);
        *count.borrow_mut() += 1;
        note(&count.borrow().to_string());
    }
}

#[test]
fn each_iteration_of_a_loop_keeps_its_own_state() {
    reset();
    compose(|| counted(3));
    assert_eq!(seen(), vec!["1", "1", "1"]);
    // Three iterations, three identities. One shared identity would have counted to three
    // in the first slot and left the other two at one.
    compose(|| counted(3));
    assert_eq!(seen(), vec!["2", "2", "2"]);
}

#[test]
fn a_shorter_loop_drops_the_iterations_it_no_longer_has() {
    reset();
    compose(|| counted(3));
    let _ = seen();
    compose(|| counted(1));
    assert_eq!(seen(), vec!["2"]);
    // The two iterations that went away took their state with them rather than leaving it
    // for whoever occupies those offsets next.
    compose(|| counted(3));
    assert_eq!(seen(), vec!["3", "1", "1"]);
}

/// A match with state in more than one arm.
#[composable]
fn branching(which: u8) {
    match which {
        0 => {
            let value = remember(|| String::from("zero"));
            note(&value.borrow());
            value.borrow_mut().push('!');
        }
        1 => {
            let value = remember(|| String::from("one"));
            note(&value.borrow());
            value.borrow_mut().push('!');
        }
        _ => {
            let value = remember(|| String::from("other"));
            note(&value.borrow());
            value.borrow_mut().push('!');
        }
    }
}

#[test]
fn each_arm_of_a_match_remembers_separately() {
    reset();
    compose(|| branching(0));
    assert_eq!(seen(), vec!["zero"]);
    compose(|| branching(1));
    assert_eq!(seen(), vec!["one"]);
    // Back to the first arm. Its state is gone, because the composition that took the other
    // arm did not match its group, and that is the same rule as the branch above.
    compose(|| branching(0));
    assert_eq!(seen(), vec!["zero"]);
}

/// A composition whose emitted value moves, and then does not.
#[composable]
fn emitting(value: u32) {
    emit(value.to_string());
}

#[test]
fn an_unchanged_value_emits_nothing() {
    reset();
    let first = compose(|| emitting(1));
    assert_eq!(first.len(), 1);
    assert_eq!(first[0].value, "1");

    let again = compose(|| emitting(1));
    assert_eq!(again, Vec::<Change>::new());

    let moved = compose(|| emitting(2));
    assert_eq!(moved.len(), 1);
    assert_eq!(moved[0].value, "2");
}

/// A composable that returns a value, to check the wrapping survives it.
#[composable]
fn with_a_result(value: u32) -> u32 {
    let doubled = if value > 0 {
        let remembered = remember(|| value);
        *remembered.borrow() * 2
    } else {
        0
    };
    doubled + 1
}

#[test]
fn wrapping_a_branch_does_not_change_what_it_evaluates_to() {
    reset();
    assert_eq!(compose(|| assert_eq!(with_a_result(4), 9)).len(), 0);
    assert_eq!(compose(|| assert_eq!(with_a_result(0), 1)).len(), 0);
}
