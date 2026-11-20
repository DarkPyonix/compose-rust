//! Lazy lists: the Renderer asks for the window it can show, and only that window is
//! composed.
//!
//! ```ignore
//! LazyColumn!(modifier = Modifier.fill_max_size(), content = |list| {
//!     list.item(|| { Text!("Header"); });
//!     list.items_keyed(tasks.len(), move |index| ids[index].clone(), move |index| {
//!         TaskRow(index);
//!     });
//! });
//! ```
//!
//! The content of a lazy list is not a block of children but a closure that is handed the
//! list and says what its items are, so it is given by name and not as a trailing block.

use super::{set, set_some};
use crate::MotionFinished;
use crate::ui::{LazyListScope, Modifier};
use compose_rust_macros::Composable;

/// A vertical list that composes only the items on screen. Compose's `LazyColumn`.
///
/// `on_motion_finished` runs once when the list's size motion comes to rest, with the
/// size it settled at in dp. The list needs `Modifier::motion` for its size to move.
#[Composable(widget)]
pub fn LazyColumn(
    modifier: Modifier,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    content: impl FnOnce(&mut LazyListScope),
) {
    let mut scope = crate::ui::lazy::LazyColumn().modifier(modifier);
    set_some!(scope; on_motion_finished);
    scope.content(content);
}

/// A horizontal list that composes only the items on screen. Compose's `LazyRow`. The
/// parameters are `LazyColumn`'s.
#[Composable(widget)]
pub fn LazyRow(
    modifier: Modifier,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    content: impl FnOnce(&mut LazyListScope),
) {
    let mut scope = crate::ui::lazy::LazyRow().modifier(modifier);
    set_some!(scope; on_motion_finished);
    scope.content(content);
}

/// A grid that composes only the rows on screen. Compose's `LazyVerticalGrid`.
///
/// Say how wide a column is with one of two parameters: `columns` is a fixed number of
/// columns (Compose's `GridCells.Fixed`), and `min_column_width` the narrowest a column
/// may be, in dp (Compose's `GridCells.Adaptive`).
#[Composable(widget)]
pub fn LazyGrid(
    modifier: Modifier,
    #[default(None::<u32>)] columns: impl Into<Option<u32>>,
    #[default(None::<f32>)] min_column_width: impl Into<Option<f32>>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    content: impl FnOnce(&mut LazyListScope),
) {
    let mut scope = crate::ui::lazy::LazyGrid().modifier(modifier);
    set!(scope; columns, min_column_width);
    set_some!(scope; on_motion_finished);
    scope.content(content);
}
