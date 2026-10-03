//! The slot table, the walk that rebuilds it, and the bookkeeping that ties remembered
//! values, Renderer nodes, event handlers and state reads to the group that owns them.
//!
//! A group is one call site's identity: a key, what it remembered, the groups it called,
//! and the Renderer node it created, if it created one. Groups live in an arena and are
//! addressed by index, so a group that is recomposed on its own can be found again without
//! walking from the root, and a scope that read a state is named by its index alone.
//!
//! Composing a group matches each call against the children the group had last time, by
//! key, in order. A call that finds its key takes over that child with everything it
//! remembered; a call that does not gets a fresh child; a child no call claimed is disposed
//! when the group closes, and the Renderer nodes it created go with it. That is the whole
//! of positional memoisation, and the keys the macro computes are what make it safe.

use super::effects::Executor;
use crate::drawing::DrawList;
use crate::schema::WidgetKind;
use crate::spans::TextSpans;
use crate::writer::{AttrValue, NodeWriter};
use std::any::Any;
use std::cell::RefCell;
use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::rc::Rc;
use std::sync::{Arc, Mutex};

pub(crate) type GroupId = u32;

/// Marks an old child that a call has already claimed.
const TAKEN: GroupId = u32::MAX;

/// The root group. It is never disposed while the composition lives.
pub(crate) const ROOT: GroupId = 0;

/// How many times one frame may run invalidated scopes again because composing them
/// invalidated more. A composition that writes the state it reads would otherwise loop
/// for ever; this many rounds is far past anything a screen needs.
pub(crate) const MAX_RECOMPOSE_ROUNDS: usize = 16;

pub(crate) struct Group {
    pub(crate) key: u64,
    pub(crate) parent: GroupId,
    pub(crate) depth: u32,
    pub(crate) generation: u32,
    pub(crate) alive: bool,
    pub(crate) slots: Vec<Box<dyn Any>>,
    pub(crate) children: Vec<GroupId>,
    /// The Renderer node this group created, if it is a widget's group.
    pub(crate) node: Option<u32>,
    /// Present for a restartable function's group.
    pub(crate) scope: Option<Box<Scope>>,
}

/// What a restartable function's group knows about its last run.
pub(crate) struct Scope {
    /// The parameters it last ran with, for the comparison that decides a skip.
    pub(crate) params: Vec<Option<Box<dyn Any>>>,
    /// Runs the function again with those parameters. Absent when a parameter could not
    /// be kept, in which case a state it read re-runs its caller instead.
    pub(crate) restart: Option<Rc<dyn Fn()>>,
    /// A state it read has changed since it last ran.
    pub(crate) invalid: bool,
    /// A parameter differed from the last run, so the restart has to be captured again.
    pub(crate) params_changed: bool,
    /// The states this scope read, so it can stop observing the ones it no longer reads.
    pub(crate) reads: Vec<u64>,
    pub(crate) previous_reads: Vec<u64>,
}

impl Scope {
    fn new() -> Self {
        Self {
            params: Vec::new(),
            restart: None,
            invalid: false,
            params_changed: false,
            reads: Vec::new(),
            previous_reads: Vec::new(),
        }
    }
}

/// One open group in the walk.
struct Frame {
    group: GroupId,
    /// The children the group had last time, in order, with claimed ones marked `TAKEN`.
    old: Vec<GroupId>,
    /// Where the next call looks first. Siblings usually come out in the order they went
    /// in, so the child at the cursor is nearly always the one.
    cursor: usize,
    new: Vec<GroupId>,
    slot: usize,
    /// How often each key has been opened in this group, so one call site inside a loop
    /// is a distinct identity per iteration.
    seen: Vec<(u64, u32)>,
    /// When set, the next group opened is this one, whatever its key. That is how a
    /// scope is restarted in place: the frame below it stands for its parent without
    /// rebuilding the parent's children.
    forced: Option<GroupId>,
    /// This frame stands for the parent of a restarted scope and rebuilds nothing.
    standin: bool,
    /// This frame's group is a restartable scope, pushed on the scope stack.
    scope: bool,
    skipped: bool,
}

/// Where the nodes composed next go: under which node, and after which sibling.
#[derive(Clone, Copy, Debug)]
pub(crate) struct NodeContext {
    pub(crate) parent: u32,
    pub(crate) last: Option<u32>,
}

/// Who read a state.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Observer {
    Scope(GroupId, u32),
    Derived(u64),
}

/// A value an attribute had, kept to compare the next composition against.
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum OwnedAttr {
    None,
    Text(String),
    Float(f64),
    Int(i64),
    Bool(bool),
    Draw(DrawList),
    Spans(TextSpans),
}

impl OwnedAttr {
    pub(crate) fn as_value(&self) -> AttrValue<'_> {
        match self {
            Self::None => AttrValue::None,
            Self::Text(text) => AttrValue::Text(text),
            Self::Float(value) => AttrValue::Float(*value),
            Self::Int(value) => AttrValue::Int(*value),
            Self::Bool(value) => AttrValue::Bool(*value),
            Self::Draw(list) => AttrValue::Bytes(list.as_bytes()),
            Self::Spans(spans) => AttrValue::Bytes(spans.as_bytes()),
        }
    }

    /// Whether writing `next` over `self` changes anything. The same rule `dioxus-core`
    /// applies before it calls a sink, so both front ends write a changed attribute and
    /// nothing else.
    fn differs(&self, next: &Self) -> bool {
        match (self, next) {
            (Self::Float(left), Self::Float(right)) => left != right,
            _ => self != next,
        }
    }
}

/// The handler an event is delivered to.
#[derive(Clone)]
pub(crate) enum EventCallback {
    Unit(Rc<RefCell<dyn FnMut()>>),
    Text(Rc<RefCell<dyn FnMut(String)>>),
    Key(Rc<RefCell<dyn FnMut(crate::KeyEvent)>>),
    Value(Rc<RefCell<dyn FnMut(f64)>>),
    Range(Rc<RefCell<dyn FnMut(crate::RangeRequest)>>),
    Files(Rc<RefCell<dyn FnMut(crate::FileDrop)>>),
}

pub(crate) struct HandlerEntry {
    pub(crate) node: u32,
    pub(crate) callback: EventCallback,
}

/// The first slot of a widget's group: its node, what it last wrote, and its handlers.
pub(crate) struct NodeState {
    pub(crate) node: u32,
    pub(crate) attrs: Vec<(&'static str, OwnedAttr)>,
    pub(crate) handlers: Vec<(&'static str, u64)>,
}

/// Work that runs after a composition has been applied, never during it.
pub(crate) enum Effect {
    Run(Box<dyn FnOnce()>),
    Launch {
        handle: Rc<super::effects::LaunchHandle>,
        start: Box<dyn FnOnce() -> Pin<Box<dyn Future<Output = ()>>>>,
    },
}

/// What other threads hand the UI thread: states they wrote and tasks they woke.
///
/// The one part of a runtime that is shared. Everything else belongs to the UI thread and
/// is reached only from inside a Host call.
#[derive(Default)]
pub(crate) struct RuntimeSignal {
    pub(crate) dirty: Mutex<Vec<u64>>,
    pub(crate) woken: Mutex<Vec<usize>>,
}

impl RuntimeSignal {
    pub(crate) fn push_dirty(&self, id: u64) {
        self.dirty
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .push(id);
    }

    pub(crate) fn push_woken(&self, id: usize) {
        self.woken
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .push(id);
    }
}

pub(crate) struct Composer {
    pub(crate) groups: Vec<Group>,
    free: Vec<GroupId>,
    frames: Vec<Frame>,
    pub(crate) scope_stack: Vec<GroupId>,
    pub(crate) node_stack: Vec<NodeContext>,
    pub(crate) derived_stack: Vec<u64>,
    pub(crate) writer: NodeWriter,
    pub(crate) handlers: HashMap<u64, HandlerEntry>,
    pub(crate) observers: HashMap<u64, Vec<Observer>>,
    pub(crate) derived_deps: HashMap<u64, Vec<u64>>,
    pub(crate) invalid: Vec<GroupId>,
    pub(crate) signal: Arc<RuntimeSignal>,
    pub(crate) disposals: Vec<Box<dyn FnOnce()>>,
    pub(crate) effects: Vec<Effect>,
    /// Remembered values and closures that left the composition. They are dropped after
    /// the composer is released, because a destructor may reach back into the runtime.
    pub(crate) graveyard: Vec<Box<dyn Any>>,
    pub(crate) executor: Executor,
    pub(crate) ambient: super::state::Ambient,
    vec_pool: Vec<Vec<GroupId>>,
    attr_pool: Vec<Vec<(&'static str, OwnedAttr)>>,
    seen_pool: Vec<Vec<(u64, u32)>>,
    /// Scratch for the dirty states drained from the signal, kept for its capacity.
    pub(crate) dirty_scratch: Vec<u64>,
}

impl Composer {
    pub(crate) fn new() -> Self {
        let root = Group {
            key: 0,
            parent: ROOT,
            depth: 0,
            generation: 0,
            alive: true,
            slots: Vec::new(),
            children: Vec::new(),
            node: None,
            scope: Some(Box::new(Scope::new())),
        };
        Self {
            groups: vec![root],
            free: Vec::new(),
            frames: Vec::with_capacity(64),
            scope_stack: Vec::with_capacity(32),
            node_stack: Vec::with_capacity(32),
            derived_stack: Vec::new(),
            writer: NodeWriter::new(),
            handlers: HashMap::with_capacity(64),
            observers: HashMap::with_capacity(64),
            derived_deps: HashMap::new(),
            invalid: Vec::with_capacity(16),
            signal: Arc::new(RuntimeSignal::default()),
            disposals: Vec::new(),
            effects: Vec::new(),
            graveyard: Vec::new(),
            executor: Executor::default(),
            ambient: super::state::Ambient::new(),
            vec_pool: Vec::new(),
            attr_pool: Vec::new(),
            seen_pool: Vec::new(),
            dirty_scratch: Vec::new(),
        }
    }

    pub(crate) fn composing(&self) -> bool {
        !self.frames.is_empty()
    }

    fn take_vec(&mut self) -> Vec<GroupId> {
        self.vec_pool.pop().unwrap_or_default()
    }

    fn give_vec(&mut self, mut vec: Vec<GroupId>) {
        vec.clear();
        self.vec_pool.push(vec);
    }

    pub(crate) fn take_attrs(&mut self) -> Vec<(&'static str, OwnedAttr)> {
        self.attr_pool.pop().unwrap_or_default()
    }

    fn allocate_group(&mut self, key: u64, parent: GroupId) -> GroupId {
        let depth = self.groups[parent as usize].depth + 1;
        if let Some(id) = self.free.pop() {
            let group = &mut self.groups[id as usize];
            group.key = key;
            group.parent = parent;
            group.depth = depth;
            group.generation = group.generation.wrapping_add(1);
            group.alive = true;
            group.node = None;
            group.scope = None;
            return id;
        }
        let id = self.groups.len() as GroupId;
        self.groups.push(Group {
            key,
            parent,
            depth,
            generation: 0,
            alive: true,
            slots: Vec::new(),
            children: Vec::new(),
            node: None,
            scope: None,
        });
        id
    }

    /// Opens the root group for a whole composition.
    pub(crate) fn begin_root(&mut self) {
        let old = std::mem::take(&mut self.groups[ROOT as usize].children);
        let new = self.take_vec();
        let seen = self.seen_pool.pop().unwrap_or_default();
        self.frames.push(Frame {
            group: ROOT,
            old,
            cursor: 0,
            new,
            slot: 0,
            seen,
            forced: None,
            standin: false,
            scope: true,
            skipped: false,
        });
        self.begin_scope_reads(ROOT);
        self.scope_stack.push(ROOT);
        self.node_stack.push(NodeContext {
            parent: crate::writer::PLACEHOLDER_NODE,
            last: None,
        });
    }

    pub(crate) fn end_root(&mut self) {
        self.node_stack.pop();
        self.end_group();
    }

    /// Opens a child group of the current one, taking over the child that carried the
    /// same key last time if there is one.
    pub(crate) fn start_group(&mut self, key: u64) -> GroupId {
        let frame = self
            .frames
            .last_mut()
            .expect("a group is always inside the root");
        if let Some(forced) = frame.forced.take() {
            return self.push_existing(forced);
        }
        let occurrence = match frame.seen.iter_mut().find(|(seen, _)| *seen == key) {
            Some((_, count)) => {
                *count += 1;
                *count
            }
            None => {
                frame.seen.push((key, 0));
                0
            }
        };
        let identity = if occurrence == 0 {
            key
        } else {
            mix(key, u64::from(occurrence))
        };
        let parent = frame.group;
        let mut matched = None;
        if let Some(&candidate) = frame.old.get(frame.cursor) {
            if candidate != TAKEN && self.groups[candidate as usize].key == identity {
                matched = Some(frame.cursor);
            }
        }
        if matched.is_none() {
            matched = frame.old.iter().position(|&candidate| {
                candidate != TAKEN && self.groups[candidate as usize].key == identity
            });
        }
        let group = match matched {
            Some(position) => {
                let group = frame.old[position];
                frame.old[position] = TAKEN;
                frame.cursor = position + 1;
                group
            }
            None => self.allocate_group(identity, parent),
        };
        self.push_existing(group)
    }

    fn push_existing(&mut self, group: GroupId) -> GroupId {
        let old = std::mem::take(&mut self.groups[group as usize].children);
        let new = self.take_vec();
        let seen = self.seen_pool.pop().unwrap_or_default();
        self.frames.push(Frame {
            group,
            old,
            cursor: 0,
            new,
            slot: 0,
            seen,
            forced: None,
            standin: false,
            scope: false,
            skipped: false,
        });
        group
    }

    /// Closes the current group, disposing whatever of its old children no call claimed.
    pub(crate) fn end_group(&mut self) {
        let Some(mut frame) = self.frames.pop() else {
            return;
        };
        let mut seen = std::mem::take(&mut frame.seen);
        seen.clear();
        self.seen_pool.push(seen);
        if frame.standin {
            // Nothing was rebuilt here: the restarted scope was put back where it stood.
            self.give_vec(frame.old);
            self.give_vec(frame.new);
            return;
        }
        let group = frame.group;
        if frame.scope {
            self.end_scope_reads(group, frame.skipped);
            if self.scope_stack.last() == Some(&group) {
                self.scope_stack.pop();
            }
        }
        for index in 0..frame.old.len() {
            let child = frame.old[index];
            if child != TAKEN {
                self.dispose_group(child, true);
            }
        }
        // Slots this run did not reach belong to nothing now.
        let slots = &mut self.groups[group as usize].slots;
        if frame.slot < slots.len() {
            let gone: Vec<Box<dyn Any>> = slots.drain(frame.slot..).collect();
            for slot in gone {
                self.forget_slot(slot);
            }
        }
        let new = std::mem::replace(&mut frame.new, Vec::new());
        let old_buffer = std::mem::take(&mut frame.old);
        self.groups[group as usize].children = new;
        self.give_vec(old_buffer);
        if let Some(parent) = self.frames.last_mut() {
            if !parent.standin {
                parent.new.push(group);
            }
        }
    }

    /// Keeps everything the current group held last time without running any more of it.
    ///
    /// This is Compose's skip: the parameters compared equal and nothing it read changed,
    /// so its body is not run, and the slots and children it would have rebuilt are
    /// carried over as they were. Its nodes still have to be counted where they stand, or
    /// the siblings composed after it would be placed in front of them.
    pub(crate) fn skip_to_group_end(&mut self) {
        let Some(frame) = self.frames.last_mut() else {
            return;
        };
        frame.skipped = true;
        let group = frame.group;
        let old = std::mem::take(&mut frame.old);
        for child in old.iter().copied() {
            if child != TAKEN {
                frame.new.push(child);
            }
        }
        frame.old = old;
        frame.old.clear();
        frame.slot = self.groups[group as usize].slots.len();
        let mut nodes = Vec::new();
        self.top_nodes(group, &mut nodes);
        for node in nodes {
            self.place(node);
        }
    }

    /// The nodes directly under the current node context that this group created, in
    /// order. A node's own children leave and move with it, so only the first node on
    /// each path is collected.
    fn top_nodes(&self, group: GroupId, into: &mut Vec<u32>) {
        let data = &self.groups[group as usize];
        if let Some(node) = data.node {
            into.push(node);
            return;
        }
        for &child in &data.children {
            self.top_nodes(child, into);
        }
    }

    // ----- remembered values -------------------------------------------------------

    /// The value the current call site remembered, if it is of this type.
    pub(crate) fn remembered<T: Clone + 'static>(&mut self) -> Option<T> {
        let frame = self.frames.last_mut()?;
        let group = frame.group;
        let index = frame.slot;
        let slot = self.groups[group as usize].slots.get(index)?;
        let value = slot.downcast_ref::<T>()?.clone();
        self.frames.last_mut()?.slot += 1;
        Some(value)
    }

    /// Like `remembered`, but only takes the value, and only moves past it, when `accept`
    /// says it is still good.
    pub(crate) fn remembered_if<T: Clone + 'static>(
        &mut self,
        accept: impl FnOnce(&T) -> bool,
    ) -> Option<T> {
        let frame = self.frames.last()?;
        let group = frame.group;
        let index = frame.slot;
        let value = self.groups[group as usize]
            .slots
            .get(index)?
            .downcast_ref::<T>()?;
        if !accept(value) {
            return None;
        }
        let value = value.clone();
        self.frames.last_mut()?.slot += 1;
        Some(value)
    }

    /// Stores a fresh value in the current call site's slot, discarding whatever a call
    /// site of another type left there.
    pub(crate) fn remember_new<T: 'static>(&mut self, value: T) {
        let Some(frame) = self.frames.last_mut() else {
            return;
        };
        let group = frame.group;
        let index = frame.slot;
        frame.slot += 1;
        let slots = &mut self.groups[group as usize].slots;
        if index < slots.len() {
            let old = std::mem::replace(&mut slots[index], Box::new(value));
            self.forget_slot(old);
        } else {
            slots.push(Box::new(value));
        }
    }

    /// Mutable access to the current slot if it holds a `T`, advancing past it.
    pub(crate) fn slot_mut<T: 'static>(&mut self) -> Option<&mut T> {
        let frame = self.frames.last_mut()?;
        let group = frame.group;
        let index = frame.slot;
        let has = self.groups[group as usize]
            .slots
            .get(index)
            .is_some_and(|slot| slot.is::<T>());
        if !has {
            return None;
        }
        self.frames.last_mut()?.slot += 1;
        self.groups[group as usize].slots[index].downcast_mut::<T>()
    }

    /// The slot at the current position, without advancing, if it is a `T`.
    pub(crate) fn peek_slot_mut<T: 'static>(&mut self) -> Option<&mut T> {
        let frame = self.frames.last()?;
        let group = frame.group;
        let index = frame.slot;
        self.groups[group as usize]
            .slots
            .get_mut(index)?
            .downcast_mut::<T>()
    }

    fn forget_slot(&mut self, mut slot: Box<dyn Any>) {
        if let Some(state) = slot.downcast_mut::<NodeState>() {
            for (_, handler) in state.handlers.drain(..) {
                if let Some(entry) = self.handlers.remove(&handler) {
                    self.graveyard.push(Box::new(entry.callback));
                }
            }
            let attrs = std::mem::take(&mut state.attrs);
            self.graveyard.push(Box::new(attrs));
        } else if let Some(effect) = slot.downcast_mut::<super::effects::EffectSlot>() {
            effect.dispose(self);
        }
        self.graveyard.push(slot);
    }

    /// Takes a group and everything under it out of the composition.
    ///
    /// `top` is whether its nodes are the first on their paths, so the Renderer has to be
    /// told: a node under a removed node leaves with it and costs no record of its own.
    fn dispose_group(&mut self, group: GroupId, top: bool) {
        let mut top = top;
        if let Some(node) = self.groups[group as usize].node {
            if top {
                self.writer.remove_node(node);
            }
            top = false;
        }
        let children = std::mem::take(&mut self.groups[group as usize].children);
        for &child in &children {
            self.dispose_group(child, top);
        }
        self.give_vec(children);
        let slots = std::mem::take(&mut self.groups[group as usize].slots);
        for slot in slots.into_iter().rev() {
            self.forget_slot(slot);
        }
        if let Some(scope) = self.groups[group as usize].scope.take() {
            let generation = self.groups[group as usize].generation;
            for read in scope.reads.iter().chain(scope.previous_reads.iter()) {
                self.unobserve(*read, Observer::Scope(group, generation));
            }
            self.graveyard.push(scope);
        }
        let data = &mut self.groups[group as usize];
        data.alive = false;
        data.node = None;
        data.generation = data.generation.wrapping_add(1);
        self.free.push(group);
    }

    // ----- restartable scopes ------------------------------------------------------

    /// Opens a restartable function's group and makes it the scope reads are charged to.
    pub(crate) fn start_restart_group(&mut self, key: u64) -> GroupId {
        let group = self.start_group(key);
        if self.groups[group as usize].scope.is_none() {
            self.groups[group as usize].scope = Some(Box::new(Scope::new()));
        }
        if let Some(frame) = self.frames.last_mut() {
            frame.scope = true;
        }
        self.begin_scope_reads(group);
        self.scope_stack.push(group);
        group
    }

    fn begin_scope_reads(&mut self, group: GroupId) {
        if let Some(scope) = self.groups[group as usize].scope.as_mut() {
            std::mem::swap(&mut scope.reads, &mut scope.previous_reads);
            scope.reads.clear();
            scope.params_changed = false;
        }
    }

    fn end_scope_reads(&mut self, group: GroupId, skipped: bool) {
        let generation = self.groups[group as usize].generation;
        let Some(scope) = self.groups[group as usize].scope.as_mut() else {
            return;
        };
        if skipped {
            // Nothing ran, so it still reads exactly what it read before.
            std::mem::swap(&mut scope.reads, &mut scope.previous_reads);
            scope.previous_reads.clear();
            return;
        }
        scope.invalid = false;
        let previous = std::mem::take(&mut scope.previous_reads);
        let stale: Vec<u64> = previous
            .iter()
            .copied()
            .filter(|read| !scope.reads.contains(read))
            .collect();
        let mut previous = previous;
        previous.clear();
        if let Some(scope) = self.groups[group as usize].scope.as_mut() {
            scope.previous_reads = previous;
        }
        for read in stale {
            self.unobserve(read, Observer::Scope(group, generation));
        }
    }

    /// Whether the current scope has to run: it is new, something it read changed, or it
    /// cannot be restarted on its own and so cannot be skipped either.
    pub(crate) fn must_run(&self, group: GroupId) -> bool {
        match self.groups[group as usize].scope.as_ref() {
            Some(scope) => scope.invalid || scope.restart.is_none(),
            None => true,
        }
    }

    /// Compares one parameter with the one the scope last ran with, and keeps the new one.
    pub(crate) fn param_changed<T: PartialEq + Clone + 'static>(
        &mut self,
        group: GroupId,
        index: usize,
        value: &T,
    ) -> bool {
        let Some(scope) = self.groups[group as usize].scope.as_mut() else {
            return true;
        };
        if scope.params.len() <= index {
            scope.params.resize_with(index + 1, || None);
        }
        let changed = match scope.params[index]
            .as_mut()
            .and_then(|stored| stored.downcast_mut::<T>())
        {
            Some(stored) => {
                if stored == value {
                    false
                } else {
                    *stored = value.clone();
                    true
                }
            }
            None => {
                scope.params[index] = Some(Box::new(value.clone()));
                true
            }
        };
        if changed {
            scope.params_changed = true;
        }
        changed
    }

    pub(crate) fn wants_restart(&self, group: GroupId) -> bool {
        self.groups[group as usize]
            .scope
            .as_ref()
            .is_some_and(|scope| scope.restart.is_none() || scope.params_changed)
    }

    pub(crate) fn set_restart(&mut self, group: GroupId, restart: Rc<dyn Fn()>) {
        if let Some(scope) = self.groups[group as usize].scope.as_mut() {
            if let Some(old) = scope.restart.replace(restart) {
                self.graveyard.push(Box::new(old));
            }
        }
    }

    /// Marks a scope as needing to run again.
    pub(crate) fn invalidate(&mut self, group: GroupId, generation: u32) {
        let Some(data) = self.groups.get_mut(group as usize) else {
            return;
        };
        if !data.alive || data.generation != generation {
            return;
        }
        let restartable = group == ROOT
            || data
                .scope
                .as_ref()
                .is_some_and(|scope| scope.restart.is_some());
        if !restartable {
            // A scope that cannot run on its own is run by the nearest one that can.
            let mut ancestor = data.parent;
            loop {
                let candidate = &self.groups[ancestor as usize];
                if ancestor == ROOT
                    || candidate
                        .scope
                        .as_ref()
                        .is_some_and(|scope| scope.restart.is_some())
                {
                    let generation = candidate.generation;
                    return self.invalidate(ancestor, generation);
                }
                ancestor = candidate.parent;
            }
        }
        if let Some(scope) = data.scope.as_mut() {
            if !scope.invalid {
                scope.invalid = true;
                self.invalid.push(group);
            }
        }
    }

    /// The invalid scopes, outermost first, so that a scope its parent re-runs anyway is
    /// not also run on its own.
    pub(crate) fn take_invalid(&mut self) -> Vec<GroupId> {
        let mut invalid = std::mem::take(&mut self.invalid);
        invalid.sort_by_key(|group| (self.groups[*group as usize].depth, *group));
        invalid.dedup();
        invalid
    }

    /// Sets up the walk so the next group opened is `group`, in place, with the node
    /// context it had. Returns the function that re-runs it, or nothing when it no longer
    /// needs to run.
    ///
    /// The root is not restarted here: the Host composes it again from its content.
    pub(crate) fn prepare_restart(&mut self, group: GroupId) -> Option<Rc<dyn Fn()>> {
        if group == ROOT {
            return None;
        }
        let data = &self.groups[group as usize];
        if !data.alive {
            return None;
        }
        let parent = data.parent;
        let scope = data.scope.as_ref()?;
        if !scope.invalid {
            return None;
        }
        let restart = scope.restart.clone()?;
        let context = self.context_before(group);
        let new = self.take_vec();
        let old = self.take_vec();
        let seen = self.seen_pool.pop().unwrap_or_default();
        self.frames.push(Frame {
            group: parent,
            old,
            cursor: 0,
            new,
            slot: 0,
            seen,
            forced: Some(group),
            standin: true,
            scope: false,
            skipped: false,
        });
        self.node_stack.push(context);
        Some(restart)
    }

    /// Whether the root itself has to be composed again.
    pub(crate) fn root_invalid(&self) -> bool {
        self.groups[ROOT as usize]
            .scope
            .as_ref()
            .is_some_and(|scope| scope.invalid)
    }

    pub(crate) fn finish_restart(&mut self) {
        self.node_stack.pop();
        self.end_group();
    }

    /// The node a restarted group's first node goes after, and the node it goes under.
    fn context_before(&self, group: GroupId) -> NodeContext {
        let mut current = group;
        loop {
            if current == ROOT {
                return NodeContext {
                    parent: crate::writer::PLACEHOLDER_NODE,
                    last: None,
                };
            }
            let parent = self.groups[current as usize].parent;
            let siblings = &self.groups[parent as usize].children;
            if let Some(position) = siblings.iter().position(|&child| child == current) {
                for &sibling in siblings[..position].iter().rev() {
                    if let Some(node) = self.last_top_node(sibling) {
                        return NodeContext {
                            parent: self.container_of(parent),
                            last: Some(node),
                        };
                    }
                }
            }
            if self.groups[parent as usize].node.is_some() || parent == ROOT {
                return NodeContext {
                    parent: self.container_of(parent),
                    last: None,
                };
            }
            current = parent;
        }
    }

    /// The node that groups under `group` place their nodes into.
    fn container_of(&self, group: GroupId) -> u32 {
        let mut current = group;
        loop {
            if let Some(node) = self.groups[current as usize].node {
                return node;
            }
            if current == ROOT {
                return crate::writer::PLACEHOLDER_NODE;
            }
            current = self.groups[current as usize].parent;
        }
    }

    fn last_top_node(&self, group: GroupId) -> Option<u32> {
        let data = &self.groups[group as usize];
        if let Some(node) = data.node {
            return Some(node);
        }
        data.children
            .iter()
            .rev()
            .find_map(|&child| self.last_top_node(child))
    }

    // ----- state observation -------------------------------------------------------

    /// Charges a read of `state` to whatever is reading: a derived state being computed,
    /// or the innermost scope that can be restarted. Says whether this runtime had not
    /// observed the state before, so the state can learn where to send its changes.
    pub(crate) fn record_read(&mut self, state: u64) -> bool {
        let observer = if let Some(&derived) = self.derived_stack.last() {
            let deps = self.derived_deps.entry(derived).or_default();
            if !deps.contains(&state) {
                deps.push(state);
            }
            Observer::Derived(derived)
        } else if self.composing() {
            let Some(group) = self.reading_scope() else {
                return false;
            };
            let generation = self.groups[group as usize].generation;
            if let Some(scope) = self.groups[group as usize].scope.as_mut() {
                if !scope.reads.contains(&state) {
                    scope.reads.push(state);
                }
            }
            Observer::Scope(group, generation)
        } else {
            return false;
        };
        let fresh = !self.observers.contains_key(&state);
        let list = self.observers.entry(state).or_default();
        if !list.contains(&observer) {
            list.push(observer);
        }
        fresh
    }

    /// The innermost scope a state read re-runs: one that can be restarted on its own, or
    /// the root, which always can.
    fn reading_scope(&self) -> Option<GroupId> {
        self.scope_stack.iter().rev().copied().find(|&group| {
            group == ROOT
                || self.groups[group as usize]
                    .scope
                    .as_ref()
                    .is_some_and(|scope| scope.restart.is_some())
        })
    }

    pub(crate) fn unobserve(&mut self, state: u64, observer: Observer) {
        if let Some(list) = self.observers.get_mut(&state) {
            list.retain(|existing| *existing != observer);
        }
    }

    // ----- nodes -------------------------------------------------------------------

    /// Places a node of the current group after the last one placed in this context.
    pub(crate) fn place(&mut self, node: u32) {
        let Some(context) = self.node_stack.last_mut() else {
            return;
        };
        let (parent, last) = (context.parent, context.last);
        context.last = Some(node);
        self.writer.place_after(parent, node, last);
    }

    /// Creates or updates the current group's widget node.
    ///
    /// `attrs` is the full attribute list in the order the `rsx!` widget writes it. On the
    /// first composition every attribute is written, as `dioxus-core` writes every
    /// dynamic attribute of a new template; after that, only the ones whose value changed,
    /// as it does when it diffs one. Listeners get a handler id once, for the life of the
    /// node, and only their closure is replaced afterwards.
    pub(crate) fn update_node(
        &mut self,
        widget: WidgetKind,
        attrs: Vec<(&'static str, OwnedAttr)>,
        listeners: Vec<(&'static str, EventCallback)>,
    ) -> u32 {
        let group = self.frames.last().map_or(ROOT, |frame| frame.group);
        let mut attrs = attrs;
        let node = if let Some(state) = self.slot_mut::<NodeState>() {
            let node = state.node;
            let mut previous = std::mem::take(&mut state.attrs);
            let mut handlers = std::mem::take(&mut state.handlers);
            // An attribute the widget no longer writes is taken away, the way
            // `dioxus-core` removes one that left a template's attribute list.
            for (name, _) in &previous {
                if !attrs.iter().any(|(new_name, _)| new_name == name) {
                    self.writer.set_attribute(node, *name, &AttrValue::None);
                }
            }
            for (name, value) in &attrs {
                let changed = match previous.iter().find(|(old_name, _)| old_name == name) {
                    Some((_, old)) => old.differs(value),
                    None => true,
                };
                if !changed {
                    continue;
                }
                // A text that only grew at the end is a streamed tail: it goes out as the
                // tail rather than as the whole string again. Text with runs is resent
                // whole, because the runs are offsets into all of it.
                if widget == WidgetKind::Text && *name == "text" {
                    if let (Some((_, OwnedAttr::Text(old))), OwnedAttr::Text(new)) = (
                        previous.iter().find(|(old_name, _)| *old_name == "text"),
                        value,
                    ) {
                        let plain = !attrs
                            .iter()
                            .any(|(name, value)| *name == "spans" && *value != OwnedAttr::None);
                        if plain
                            && !old.is_empty()
                            && new.len() > old.len()
                            && new.starts_with(old.as_str())
                        {
                            self.writer.append_text_node(node, &new[old.len()..]);
                            continue;
                        }
                    }
                }
                self.writer.set_attribute(node, *name, &value.as_value());
            }
            for (name, callback) in listeners {
                match handlers.iter().find(|(existing, _)| *existing == name) {
                    Some(&(_, id)) => {
                        if let Some(entry) = self.handlers.get_mut(&id) {
                            let old = std::mem::replace(&mut entry.callback, callback);
                            self.graveyard.push(Box::new(old));
                        }
                    }
                    None => {
                        let id = self.writer.allocate_handler_id();
                        self.writer.set_listener(node, name, id);
                        self.handlers.insert(id, HandlerEntry { node, callback });
                        handlers.push((name, id));
                    }
                }
            }
            previous.clear();
            self.attr_pool.push(previous);
            // Put the state back with what was written this time.
            let index = self.frames.last().map_or(0, |frame| frame.slot) - 1;
            if let Some(state) =
                self.groups[group as usize].slots[index].downcast_mut::<NodeState>()
            {
                std::mem::swap(&mut state.attrs, &mut attrs);
                state.handlers = handlers;
            }
            node
        } else {
            let node = self.writer.allocate_node(widget);
            let mut handlers = Vec::with_capacity(listeners.len());
            // Attributes and listeners go out in the order the widget lists them, which
            // for every widget here is attributes first and listeners after.
            for (name, value) in &attrs {
                self.writer.set_attribute(node, *name, &value.as_value());
            }
            for (name, callback) in listeners {
                let id = self.writer.allocate_handler_id();
                self.writer.set_listener(node, name, id);
                self.handlers.insert(id, HandlerEntry { node, callback });
                handlers.push((name, id));
            }
            self.remember_new(NodeState {
                node,
                attrs: std::mem::take(&mut attrs),
                handlers,
            });
            node
        };
        attrs.clear();
        self.attr_pool.push(attrs);
        self.groups[group as usize].node = Some(node);
        self.place(node);
        node
    }

    /// Opens the node context a widget's children are placed in.
    pub(crate) fn push_node_context(&mut self, parent: u32) {
        self.node_stack.push(NodeContext { parent, last: None });
    }

    pub(crate) fn pop_node_context(&mut self) {
        self.node_stack.pop();
    }

    /// Queues work for after the composition is applied.
    pub(crate) fn push_effect(&mut self, effect: Effect) {
        self.effects.push(effect);
    }

    /// Forgets a walk a panic left half done.
    pub(crate) fn clear_walk(&mut self) {
        self.frames.clear();
        self.scope_stack.clear();
        self.node_stack.clear();
        self.derived_stack.clear();
    }

    pub(crate) fn current_group(&self) -> Option<GroupId> {
        self.frames.last().map(|frame| frame.group)
    }
}

/// Combines a call site with an occurrence count. Any mixing that keeps nearby small
/// numbers apart does; this is the one from splitmix64.
pub(crate) fn mix(key: u64, occurrence: u64) -> u64 {
    let mut value = key ^ occurrence.wrapping_mul(0x9E37_79B9_7F4A_7C15);
    value = (value ^ (value >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    value ^ (value >> 31)
}

/// A call site's key from a path and an ordinal, at compile time.
pub const fn call_site(path: &str, ordinal: u32) -> u64 {
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
