//! Lazy lists: the Renderer asks for the window it can show, and only that window is
//! composed.
//!
//! The shape is Compose's: a list takes a builder that says what its items are, in one or
//! more runs, and composes none of them itself.
//!
//! ```ignore
//! LazyColumn().modifier(Modifier.fill_max_size()).content(|list| {
//!     list.item(|| { Text("Header"); });
//!     list.items_keyed(tasks.len(), move |index| ids[index].clone(), move |index| {
//!         TaskRow(index);
//!     });
//! });
//! ```
//!
//! On the wire it is the windowing the `rsx!` lists use: the list declares how many items
//! there are, the Renderer reports the range it shows, and exactly that range is composed,
//! each item in a `Box` carrying its key. Scrolling runs the list's items and nothing
//! above it, because the range is a state only the items read.

#![allow(non_snake_case)]

use super::{Attrs, Modifier, Mods, emit_node, push_modifiers, widget_key};
use crate::RangeRequest;
use crate::runtime::__private::{RestartGroup, start_restart_group};
use crate::runtime::composer::{EventCallback, OwnedAttr};
use crate::runtime::{MutableState, group_guard, key, mutable_state_of, remember};
use crate::schema::WidgetKind;
use std::cell::RefCell;
use std::rc::Rc;

/// One run of items.
struct Interval {
    count: usize,
    key: Option<Rc<dyn Fn(usize) -> String>>,
    content: Rc<dyn Fn(usize)>,
}

/// What a lazy list's builder is handed. Compose's `LazyListScope`.
#[derive(Default)]
pub struct LazyListScope {
    intervals: Vec<Interval>,
}

impl LazyListScope {
    /// One item.
    pub fn item(&mut self, content: impl Fn() + 'static) {
        self.intervals.push(Interval {
            count: 1,
            key: None,
            content: Rc::new(move |_| content()),
        });
    }

    /// `count` items, each composed by `content` with its index in this run. An item's
    /// key is its position in the whole list, so items that move are rebuilt; use
    /// [`LazyListScope::items_keyed`] for a list whose items move.
    pub fn items(&mut self, count: usize, content: impl Fn(usize) + 'static) {
        self.intervals.push(Interval {
            count,
            key: None,
            content: Rc::new(content),
        });
    }

    /// The same, with a stable key per item: an item that moves keeps its node and what
    /// it remembered.
    pub fn items_keyed(
        &mut self,
        count: usize,
        key: impl Fn(usize) -> String + 'static,
        content: impl Fn(usize) + 'static,
    ) {
        self.intervals.push(Interval {
            count,
            key: Some(Rc::new(key)),
            content: Rc::new(content),
        });
    }

    fn total(&self) -> usize {
        self.intervals.iter().map(|interval| interval.count).sum()
    }
}

/// Which list it is.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    Column,
    Row,
    Grid,
}

/// A lazy list and its builder.
pub struct LazyList {
    kind: Kind,
    modifier: Modifier,
    columns: Option<u32>,
    min_column_width: Option<f32>,
    done: bool,
}

/// A vertical list that composes only the items on screen. Compose's `LazyColumn`.
pub fn LazyColumn() -> LazyList {
    LazyList::new(Kind::Column)
}

/// A horizontal list that composes only the items on screen. Compose's `LazyRow`.
pub fn LazyRow() -> LazyList {
    LazyList::new(Kind::Row)
}

/// A grid that composes only the rows on screen. Compose's `LazyVerticalGrid`; say how
/// wide a column is with `columns` or `min_column_width`.
pub fn LazyGrid() -> LazyList {
    LazyList::new(Kind::Grid)
}

impl LazyList {
    fn new(kind: Kind) -> Self {
        Self {
            kind,
            modifier: Modifier::new(),
            columns: None,
            min_column_width: None,
            done: false,
        }
    }

    pub fn modifier(mut self, modifier: Modifier) -> Self {
        self.modifier = modifier;
        self
    }

    /// A grid's fixed number of columns. Compose's `GridCells.Fixed`.
    pub fn columns(mut self, columns: u32) -> Self {
        self.columns = Some(columns);
        self
    }

    /// The narrowest a grid column may be. Compose's `GridCells.Adaptive`.
    pub fn min_column_width(mut self, dp: f32) -> Self {
        self.min_column_width = Some(dp);
        self
    }

    /// Composes the list with the items `build` declares.
    pub fn content(mut self, build: impl FnOnce(&mut LazyListScope)) {
        self.done = true;
        let mut scope = LazyListScope::default();
        build(&mut scope);
        self.compose(scope);
    }

    fn compose(&mut self, scope: LazyListScope) {
        let (kind, modifier, columns, min_column_width) = (
            self.kind,
            self.modifier,
            self.columns,
            self.min_column_width,
        );
        let (widget, name) = match kind {
            Kind::Column => (WidgetKind::LazyColumn, "compose_rust::ui::LazyColumn"),
            Kind::Row => (WidgetKind::LazyRow, "compose_rust::ui::LazyRow"),
            Kind::Grid => (WidgetKind::LazyGrid, "compose_rust::ui::LazyGrid"),
        };
        let _group = group_guard(widget_key(name));
        // The window the Renderer last asked for. Only the items read it, so a scroll runs
        // them and nothing else.
        let range = remember(|| mutable_state_of((0_usize, 0_usize)));
        let total = scope.total();
        let request = range.clone();
        let listener = EventCallback::Range(Rc::new(RefCell::new(move |asked: RangeRequest| {
            request.set((asked.start(), asked.count()));
        })));
        let intervals = Rc::new(scope.intervals);
        emit_node(
            widget,
            |attrs: &mut Attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("item_count", OwnedAttr::Int(total as i64)));
                if kind == Kind::Grid {
                    attrs.push((
                        "columns",
                        columns.map_or(OwnedAttr::None, |count| OwnedAttr::Int(i64::from(count))),
                    ));
                    attrs.push((
                        "min_column_width",
                        min_column_width
                            .map_or(OwnedAttr::None, |dp| OwnedAttr::Float(f64::from(dp))),
                    ));
                }
            },
            vec![("onrangerequest", listener)],
            || Items(range, intervals),
        );
    }
}

impl Drop for LazyList {
    fn drop(&mut self) {
        if !self.done && !std::thread::panicking() {
            self.done = true;
            self.compose(LazyListScope::default());
        }
    }
}

const ITEMS: u64 = widget_key("compose_rust::ui::LazyList::items");
const ITEM: u64 = widget_key("compose_rust::ui::LazyList::item");
const ITEM_BOX: u64 = widget_key("compose_rust::ui::LazyList::item_box");

/// The items a list's builder declared, compared by identity: a list composed again
/// declares them again, and a list whose window moved did not.
#[derive(Clone)]
struct SameIntervals(Rc<Vec<Interval>>);

impl PartialEq for SameIntervals {
    fn eq(&self, other: &Self) -> bool {
        Rc::ptr_eq(&self.0, &other.0)
    }
}

/// The window of items, as a scope of its own that the range state invalidates.
fn Items(range: MutableState<(usize, usize)>, intervals: Rc<Vec<Interval>>) {
    let scope: RestartGroup = start_restart_group(ITEMS);
    let fresh = scope.param_changed(0, &SameIntervals(Rc::clone(&intervals)));
    if !scope.must_run() && !fresh {
        scope.skip();
        return;
    }
    {
        let (range, intervals) = (range.clone(), Rc::clone(&intervals));
        scope.set_restart(move || Items(range.clone(), Rc::clone(&intervals)));
    }
    let total: usize = intervals.iter().map(|interval| interval.count).sum();
    let (start, count) = range.get();
    let first = start.min(total);
    let last = first.saturating_add(count).min(total);
    for index in first..last {
        let mut offset = index;
        let Some(interval) = intervals.iter().find(|interval| {
            if offset < interval.count {
                true
            } else {
                offset -= interval.count;
                false
            }
        }) else {
            continue;
        };
        let item_key = interval
            .key
            .as_ref()
            .map_or_else(|| index.to_string(), |key| key(offset));
        let content = Rc::clone(&interval.content);
        let local = offset;
        key(item_key.clone(), || {
            let _group = group_guard(ITEM_BOX);
            emit_node(
                WidgetKind::Box,
                |attrs: &mut Attrs| attrs.push(("item_key", OwnedAttr::Text(item_key))),
                Vec::new(),
                || Item(content, local),
            );
        });
    }
}

/// One item's content, compared by the closure that composes it and its index.
#[derive(Clone)]
struct SameItem(Rc<dyn Fn(usize)>, usize);

impl PartialEq for SameItem {
    fn eq(&self, other: &Self) -> bool {
        Rc::ptr_eq(&self.0, &other.0) && self.1 == other.1
    }
}

/// One item, as a scope of its own: a state its content reads runs this item again and
/// no other.
fn Item(content: Rc<dyn Fn(usize)>, index: usize) {
    let scope: RestartGroup = start_restart_group(ITEM);
    let changed = scope.param_changed(0, &SameItem(Rc::clone(&content), index));
    if !scope.must_run() && !changed {
        scope.skip();
        return;
    }
    {
        let content = Rc::clone(&content);
        scope.set_restart(move || Item(Rc::clone(&content), index));
    }
    content(index);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fr39_the_items_of_several_runs_are_one_index_space() {
        let mut scope = LazyListScope::default();
        scope.item(|| {});
        scope.items(3, |_| {});
        scope.items_keyed(2, |index| format!("k{index}"), |_| {});
        assert_eq!(scope.total(), 6);
    }
}
