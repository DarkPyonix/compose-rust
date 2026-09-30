//! A slot table small enough to read, to find out what positional memoization costs the
//! person writing the UI.
//!
//! The question this exists for is not whether a slot table can be built in Rust. It is
//! whether the groups such a table needs can be kept out of the code someone writes.
//! Compose's compiler plugin inserts them; Rust has no plugin, so the only candidate is a
//! proc macro rewriting the body, and the only way to find out how far that goes is to
//! write one and read what is left behind.
//!
//! What is deliberately missing: the gap buffer (this uses an owned tree, which has the
//! same identity semantics and worse asymptotics on movement), snapshot state, stability
//! inference, and any thread but this one.

extern crate self as positional;

use std::any::Any;
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

pub use composable_macro::composable;

/// A group's identity: what the compiler plugin would derive from a source position, and
/// what the proc macro here derives from a call site counter.
pub type Key = u64;

/// One entry in the change list a composition produces.
///
/// The real thing would be a wire record. This is the shape that matters: a composition
/// does not hand back a tree to be compared, it hands back the changes it already knows
/// about.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Change {
    pub key: Key,
    pub value: String,
}

/// A group and everything remembered inside it.
///
/// Children are held in order and matched by key rather than by offset, which is the whole
/// point: an offset is what a conditional invalidates.
#[derive(Default)]
struct Group {
    key: Key,
    slots: Vec<Rc<dyn Any>>,
    children: Vec<Option<Group>>,
    /// The Renderer node this group created, if it created one. A group that is dropped
    /// takes its node with it, so this is what turns a vanished branch into a `Remove`.
    node: Option<u32>,
}

/// One level of the walk: the group being rebuilt and the one it is being matched against.
struct Frame {
    old: Option<Group>,
    /// Where to resume searching `old.children`. Ordered lookup rather than a map, because
    /// siblings are usually in the same order and a scan from here is one comparison.
    cursor: usize,
    new: Group,
    slot: usize,
    /// How many times each key has been started at this level, so a loop body reusing one
    /// call site gets a distinct identity per iteration.
    seen: HashMap<Key, u32>,
}

#[derive(Default)]
struct Composition {
    previous: Option<Group>,
    stack: Vec<Frame>,
    changes: Vec<Change>,
    /// A flat cursor used only by the positional-only control below.
    flat: Vec<Rc<dyn Any>>,
    flat_cursor: usize,
    /// Set while a composition is running, so a stray call outside one is a clear error
    /// rather than a silently fresh slot.
    running: bool,
    /// Nodes whose groups were dropped this composition, removed when the frame closes.
    removed: Vec<u32>,
}

thread_local! {
    static COMPOSITION: RefCell<Composition> = RefCell::new(Composition::default());
}

/// Runs one composition of `content` and returns what changed.
///
/// The composer is ambient rather than an argument. Compose passes it as a hidden
/// parameter the plugin threads through every call; a thread local is the same idea with
/// the threading done at run time, and it is what lets a `#[composable]` function keep an
/// ordinary Rust signature.
pub fn compose(content: impl FnOnce()) -> Vec<Change> {
    COMPOSITION.with(|cell| {
        let mut composition = cell.borrow_mut();
        assert!(!composition.running, "a composition cannot run inside another");
        composition.running = true;
        let previous = composition.previous.take();
        composition.stack.push(Frame {
            old: previous,
            cursor: 0,
            new: Group::default(),
            slot: 0,
            seen: HashMap::new(),
        });
        composition.changes.clear();
        composition.flat_cursor = 0;
    });

    content();

    COMPOSITION.with(|cell| {
        let mut composition = cell.borrow_mut();
        let root = composition
            .stack
            .pop()
            .expect("every group that was started was also ended");
        assert!(
            composition.stack.is_empty(),
            "a group was started and never ended"
        );
        composition.previous = Some(root.new);
        composition.running = false;
        std::mem::take(&mut composition.changes)
    })
}

/// Discards everything remembered, so a test can start from nothing.
pub fn reset() {
    COMPOSITION.with(|cell| {
        let mut composition = cell.borrow_mut();
        *composition = Composition::default();
    });
}

/// Opens a group, taking over the state of the one that carried this key last time.
///
/// This is the call the person writing the UI must not have to make. Everything about
/// whether that is achievable is about who writes this.
pub fn start_group(key: Key) {
    COMPOSITION.with(|cell| {
        let mut composition = cell.borrow_mut();
        debug_assert!(composition.running, "no composition is running");
        let parent = composition
            .stack
            .last_mut()
            .expect("a group is always inside the root group");

        // A call site inside a loop is one key used many times. Counting occurrences at
        // this level turns it into one identity per iteration, which is what keeps the
        // second iteration from inheriting the first one's state.
        let occurrence = parent.seen.entry(key).or_insert(0);
        let identity = mix(key, u64::from(*occurrence));
        *occurrence += 1;

        let matched = parent.old.as_mut().and_then(|old| {
            let found = old.children[parent.cursor..]
                .iter()
                .position(|child| child.as_ref().is_some_and(|group| group.key == identity))
                .map(|offset| parent.cursor + offset)?;
            parent.cursor = found + 1;
            old.children[found].take()
        });

        composition.stack.push(Frame {
            old: matched,
            cursor: 0,
            new: Group {
                key: identity,
                slots: Vec::new(),
                children: Vec::new(),
                node: None,
            },
            slot: 0,
            seen: HashMap::new(),
        });
    });
}

/// Holds a group open for the rest of a block.
///
/// A group has to close on every way out of a block, and a block has more ways out than a
/// rewrite can enumerate: `continue`, `break`, `return`, `?`, and an unwind. Closing it in
/// `Drop` covers all of them, and it is why the macro does not wrap a body in a closure:
/// a closure turns the first two into compile errors and the third into a silent change of
/// meaning, since `return` would leave the closure rather than the function.
#[must_use = "the group closes when this is dropped, so it has to be bound for the length of the block"]
pub struct GroupGuard;

impl Drop for GroupGuard {
    fn drop(&mut self) {
        end_group();
    }
}

/// Opens a group that closes at the end of the enclosing block.
pub fn group(key: Key) -> GroupGuard {
    start_group(key);
    GroupGuard
}

/// Closes the current group.
///
/// Whatever of the old group was not matched is dropped here, which is how the state of a
/// branch that stopped being taken is discarded rather than inherited by whoever occupies
/// its offsets next.
pub fn end_group() {
    COMPOSITION.with(|cell| {
        let mut composition = cell.borrow_mut();
        let mut frame = composition
            .stack
            .pop()
            .expect("end_group without a matching start_group");
        // Whatever of the old group was not matched is going away. Its nodes have to go
        // with it, or the Renderer keeps drawing a branch the Host no longer takes.
        if let Some(old) = frame.old.take() {
            for child in old.children.into_iter().flatten() {
                collect_nodes(child, &mut composition.removed);
            }
        }
        composition
            .stack
            .last_mut()
            .expect("the root group is never ended by this")
            .new
            .children
            .push(Some(frame.new));
    });
}

/// The topmost nodes under a dropped group. A node's own children leave with it, so only the
/// first node on each path needs a record.
fn collect_nodes(group: Group, removed: &mut Vec<u32>) {
    if let Some(node) = group.node {
        removed.push(node);
        return;
    }
    for child in group.children.into_iter().flatten() {
        collect_nodes(child, removed);
    }
}

/// Keeps everything the current group held last time without running any more of it.
///
/// This is Compose's skip: parameters compared equal, so the body is not run, and the
/// slots and child groups it would have rebuilt are carried over as they were. Without it
/// a skipped body's children would look unmatched and be dropped.
pub fn skip_to_group_end() {
    COMPOSITION.with(|cell| {
        let mut composition = cell.borrow_mut();
        let frame = composition
            .stack
            .last_mut()
            .expect("skip outside a composition");
        if let Some(mut old) = frame.old.take() {
            let rest = old.slots.split_off(frame.slot.min(old.slots.len()));
            frame.new.slots.extend(rest);
            frame.slot = frame.new.slots.len();
            frame
                .new
                .children
                .extend(old.children.into_iter().filter(Option::is_some));
            frame.new.node = frame.new.node.or(old.node);
        }
    });
}

/// Marks the current group as the owner of a Renderer node.
pub fn own_node(node: u32) {
    COMPOSITION.with(|cell| {
        cell.borrow_mut()
            .stack
            .last_mut()
            .expect("own_node outside a composition")
            .new
            .node = Some(node);
    });
}

/// Nodes dropped since the last call.
pub fn take_removed() -> Vec<u32> {
    COMPOSITION.with(|cell| std::mem::take(&mut cell.borrow_mut().removed))
}

pub mod widgets;

/// The value this call site remembered, or a fresh one.
pub fn remember<T: Any + 'static>(init: impl FnOnce() -> T) -> Rc<RefCell<T>> {
    COMPOSITION.with(|cell| {
        let mut composition = cell.borrow_mut();
        let frame = composition
            .stack
            .last_mut()
            .expect("remember outside a composition");
        let existing = frame
            .old
            .as_ref()
            .and_then(|old| old.slots.get(frame.slot))
            .cloned();
        frame.slot += 1;
        let slot = existing.unwrap_or_else(|| Rc::new(RefCell::new(init())) as Rc<dyn Any>);
        frame.new.slots.push(slot.clone());
        downcast(slot)
    })
}

/// The same, addressed by a flat offset across the whole composition and paying no
/// attention to groups.
///
/// This is the control. It is what a positional slot table is without the groups, and it is
/// here so that the reason groups exist can be demonstrated rather than asserted.
pub fn remember_flat<T: Any + 'static>(init: impl FnOnce() -> T) -> Rc<RefCell<T>> {
    COMPOSITION.with(|cell| {
        let mut composition = cell.borrow_mut();
        let cursor = composition.flat_cursor;
        composition.flat_cursor += 1;
        if let Some(slot) = composition.flat.get(cursor).cloned() {
            return downcast(slot);
        }
        let slot = Rc::new(RefCell::new(init())) as Rc<dyn Any>;
        composition.flat.push(slot.clone());
        downcast(slot)
    })
}

/// Records a change if this call site's value moved, and nothing otherwise.
///
/// The comparison is against what this slot last held, which is the same comparison a
/// reconciler makes against the previous tree. Both models compare; this one just has the
/// previous value to hand rather than a tree to walk.
pub fn emit(value: impl Into<String>) {
    let value = value.into();
    let last = remember(|| Option::<String>::None);
    let key = COMPOSITION.with(|cell| {
        let composition = cell.borrow();
        composition
            .stack
            .last()
            .expect("emit outside a composition")
            .new
            .key
    });
    let mut last = last.borrow_mut();
    if last.as_deref() == Some(value.as_str()) {
        return;
    }
    *last = Some(value.clone());
    COMPOSITION.with(|cell| {
        cell.borrow_mut().changes.push(Change { key, value });
    });
}

fn downcast<T: Any + 'static>(slot: Rc<dyn Any>) -> Rc<RefCell<T>> {
    // A call site writes and reads its own slot, and `start_group` is what keeps that slot
    // from being some other call site's between compositions. When the identity does move,
    // this is where it surfaces, so the message says that rather than naming the types.
    slot.downcast::<RefCell<T>>().unwrap_or_else(|_| {
        panic!(
            "a call site remembered one type and read back another, which means its slot \
             identity moved between compositions"
        )
    })
}

/// Combines a call site with an occurrence count. Any mixing that avoids collisions between
/// nearby small numbers does; this is the one from splitmix64.
fn mix(key: Key, occurrence: u64) -> Key {
    let mut value = key ^ occurrence.wrapping_mul(0x9E37_79B9_7F4A_7C15);
    value = (value ^ (value >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    value ^ (value >> 31)
}

/// A call site's key, from the module path and function name the macro was applied to plus
/// the macro's own counter within that body.
pub const fn call_site(path: &str, ordinal: u32) -> Key {
    let bytes = path.as_bytes();
    let mut hash = 0xcbf2_9ce4_8422_2325_u64;
    let mut index = 0;
    while index < bytes.len() {
        hash ^= bytes[index] as u64;
        hash = hash.wrapping_mul(0x100_0000_01b3);
        index += 1;
    }
    hash ^= ordinal as u64;
    hash.wrapping_mul(0x100_0000_01b3)
}
