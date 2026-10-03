//! Ordinary Rust control flow inside a composable.
//!
//! The macro has to leave the language alone. A body someone writes uses `continue`,
//! `return` and `?` without thinking about it, and a rewrite that cannot carry those is not
//! a rewrite that can be hidden.

use positional::{compose, composable, emit, reset};

#[composable]
fn with_continue(width: usize) {
    for index in 0..width {
        if index % 2 == 0 {
            continue;
        }
        emit(index.to_string());
    }
}

#[composable]
fn with_early_return(width: usize) {
    for index in 0..width {
        if index == 2 {
            return;
        }
        emit(index.to_string());
    }
}

#[composable]
fn with_break(width: usize) {
    for index in 0..width {
        if index == 2 {
            break;
        }
        emit(index.to_string());
    }
}

#[test]
fn continue_inside_a_composable_loop_still_works() {
    reset();
    let changes = compose(|| with_continue(5));
    let values: Vec<&str> = changes.iter().map(|change| change.value.as_str()).collect();
    assert_eq!(values, vec!["1", "3"]);
}

#[test]
fn an_early_return_inside_a_composable_still_works() {
    reset();
    let changes = compose(|| with_early_return(5));
    let values: Vec<&str> = changes.iter().map(|change| change.value.as_str()).collect();
    assert_eq!(values, vec!["0", "1"]);
}

#[test]
fn break_inside_a_composable_loop_still_works() {
    reset();
    let changes = compose(|| with_break(5));
    let values: Vec<&str> = changes.iter().map(|change| change.value.as_str()).collect();
    assert_eq!(values, vec!["0", "1"]);
}
