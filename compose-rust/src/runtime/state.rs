//! Observable state: what a composition reads, and what tells it to run again.
//!
//! A [`MutableState`] remembers which scopes read it during composition. Writing it, from
//! the UI thread or from a worker, queues its id for the runtime that observed it and asks
//! for a frame. At the next frame the runtime looks up who read that id and runs exactly
//! those scopes again, and nothing else. This is Compose's snapshot invalidation without
//! the isolation of separate snapshots: a write is visible to the next read at once, and
//! what is deferred to the frame is the recomposition, not the value.
//!
//! A worker never touches the composition. It writes a value behind a lock and pushes an
//! id onto a queue, and the UI thread does the rest inside its own Host call.

use super::composer::Observer;
use super::{with_composer, with_composer_if_idle};
use crate::schema::{DesignSystem, NotificationPermission};
use crate::window::WindowSize;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::fmt;
use std::rc::{Rc, Weak};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

use super::composer::RuntimeSignal;

/// Ids for states and derived states. Shared between the two so an observer list never
/// confuses one with the other.
static NEXT_STATE_ID: AtomicU64 = AtomicU64::new(1);

fn next_state_id() -> u64 {
    NEXT_STATE_ID.fetch_add(1, Ordering::Relaxed)
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poison| poison.into_inner())
}

/// When a write counts as a change. Compose calls this a `SnapshotMutationPolicy`.
pub struct SnapshotMutationPolicy<T> {
    equivalent: fn(&T, &T) -> bool,
}

impl<T> Clone for SnapshotMutationPolicy<T> {
    fn clone(&self) -> Self {
        *self
    }
}

impl<T> Copy for SnapshotMutationPolicy<T> {}

/// A write of an equal value changes nothing and runs nothing again.
pub fn structural_equality_policy<T: PartialEq>() -> SnapshotMutationPolicy<T> {
    SnapshotMutationPolicy {
        equivalent: |left, right| left == right,
    }
}

/// Every write is a change, whatever it wrote.
pub fn never_equal_policy<T>() -> SnapshotMutationPolicy<T> {
    SnapshotMutationPolicy {
        equivalent: |_, _| false,
    }
}

struct StateCell<T> {
    id: u64,
    value: Mutex<T>,
    policy: SnapshotMutationPolicy<T>,
    /// The runtimes that have read this state, so a write knows whom to tell.
    runtimes: Mutex<Vec<std::sync::Weak<RuntimeSignal>>>,
}

/// A value that composition observes. Compose's `MutableState`.
///
/// Cloning gives another handle to the same value. Two handles compare equal when they
/// are the same state, which is what makes a state a stable parameter: passing the same
/// state to a composable again does not make it run.
///
/// It is `Send` and `Sync` when the value is `Send`, so a worker thread can hold one and
/// write it. The write is visible at once; the composables that read it run again on the
/// next frame, which the write asks for.
///
/// Reading takes a lock for as long as the read lasts. Reading the same state again inside
/// [`MutableState::with`] would wait on itself.
pub struct MutableState<T> {
    cell: Arc<StateCell<T>>,
}

impl<T> Clone for MutableState<T> {
    fn clone(&self) -> Self {
        Self {
            cell: Arc::clone(&self.cell),
        }
    }
}

impl<T> PartialEq for MutableState<T> {
    fn eq(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.cell, &other.cell)
    }
}

impl<T> Eq for MutableState<T> {}

impl<T: fmt::Debug> fmt::Debug for MutableState<T> {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_tuple("MutableState")
            .field(&*lock(&self.cell.value))
            .finish()
    }
}

/// A state whose writes of an equal value change nothing. Compose's `mutableStateOf`.
///
/// ```ignore
/// let count = remember(|| mutable_state_of(0));
/// Text(format!("{}", count.get()));
/// Button("More").on_click(move || count.update(|count| *count += 1));
/// ```
pub fn mutable_state_of<T: PartialEq + 'static>(value: T) -> MutableState<T> {
    mutable_state_of_with_policy(value, structural_equality_policy())
}

/// A state with a policy of its own for what counts as a change.
pub fn mutable_state_of_with_policy<T: 'static>(
    value: T,
    policy: SnapshotMutationPolicy<T>,
) -> MutableState<T> {
    MutableState {
        cell: Arc::new(StateCell {
            id: next_state_id(),
            value: Mutex::new(value),
            policy,
            runtimes: Mutex::new(Vec::new()),
        }),
    }
}

impl<T: 'static> MutableState<T> {
    /// The value, recorded as read by the scope composing now.
    pub fn get(&self) -> T
    where
        T: Clone,
    {
        self.track();
        lock(&self.cell.value).clone()
    }

    /// The value, without recording a read. A scope that only peeks is not run again when
    /// the value changes.
    pub fn get_untracked(&self) -> T
    where
        T: Clone,
    {
        lock(&self.cell.value).clone()
    }

    /// Reads the value in place, recorded as read.
    pub fn with<R>(&self, read: impl FnOnce(&T) -> R) -> R {
        self.track();
        read(&lock(&self.cell.value))
    }

    /// Replaces the value. Under the default policy an equal value changes nothing.
    pub fn set(&self, value: T) {
        {
            let mut current = lock(&self.cell.value);
            if (self.cell.policy.equivalent)(&current, &value) {
                return;
            }
            *current = value;
        }
        self.notify();
    }

    /// Changes the value in place. Always a change: there is no old value left to compare
    /// against without a copy, and a caller who wants the comparison can use `set`.
    pub fn update(&self, change: impl FnOnce(&mut T)) {
        change(&mut lock(&self.cell.value));
        self.notify();
    }

    /// This state as a read-only [`State`].
    pub fn as_state(&self) -> MutableState<T> {
        self.clone()
    }

    fn track(&self) {
        let fresh = with_composer_if_idle(|composer| {
            composer
                .record_read(self.cell.id)
                .then(|| Arc::clone(&composer.signal))
        })
        .flatten();
        if let Some(signal) = fresh {
            let mut runtimes = lock(&self.cell.runtimes);
            if !runtimes
                .iter()
                .any(|known| std::ptr::eq(known.as_ptr(), Arc::as_ptr(&signal)))
            {
                runtimes.push(Arc::downgrade(&signal));
            }
        }
    }

    fn notify(&self) {
        let mut told = false;
        lock(&self.cell.runtimes).retain(|runtime| match runtime.upgrade() {
            Some(signal) => {
                signal.push_dirty(self.cell.id);
                told = true;
                true
            }
            None => false,
        });
        // Inside a Host call the change is picked up before the call returns. Anywhere
        // else, a worker above all, nothing would look until something asked for a frame.
        if told && !crate::boundary::in_host_call() {
            crate::boundary::request_frame_from_worker();
        }
    }
}

/// Something composition can read. Compose's `State`.
pub trait State<T> {
    /// The value, recorded as read by the scope composing now.
    fn value(&self) -> T;
}

impl<T: Clone + 'static> State<T> for MutableState<T> {
    fn value(&self) -> T {
        self.get()
    }
}

impl<T: Clone + PartialEq + 'static> State<T> for DerivedState<T> {
    fn value(&self) -> T {
        self.get()
    }
}

/// A derived state's half of change propagation.
pub(crate) trait DerivedNode {
    /// Computes the value again and says whether it changed.
    fn refresh(&self) -> bool;
    /// Notes that the value is out of date, to be computed when next read.
    fn mark_dirty(&self);
}

struct DerivedCell<T> {
    id: u64,
    compute: Box<dyn Fn() -> T>,
    value: RefCell<Option<T>>,
    dirty: Cell<bool>,
}

/// A value computed from other states, which tells its readers only when the result
/// changes. Compose's `derivedStateOf`.
///
/// A list that changes on every keystroke and a screen that only cares whether the list is
/// empty is the case it is for: the screen observes the derived boolean, and the boolean
/// is recomputed on every change but only runs the screen again when it flips.
///
/// It belongs to the UI thread. Remember it, so it is computed once per change rather than
/// once per composition.
pub struct DerivedState<T> {
    cell: Rc<DerivedCell<T>>,
}

impl<T> Clone for DerivedState<T> {
    fn clone(&self) -> Self {
        Self {
            cell: Rc::clone(&self.cell),
        }
    }
}

impl<T> PartialEq for DerivedState<T> {
    fn eq(&self, other: &Self) -> bool {
        Rc::ptr_eq(&self.cell, &other.cell)
    }
}

/// A state computed from the states `calculation` reads.
///
/// ```ignore
/// let empty = remember(move || derived_state_of(move || tasks.with(Vec::is_empty)));
/// if empty.get() { Text("Nothing to do") }
/// ```
pub fn derived_state_of<T: Clone + PartialEq + 'static>(
    calculation: impl Fn() -> T + 'static,
) -> DerivedState<T> {
    DerivedState {
        cell: Rc::new(DerivedCell {
            id: next_state_id(),
            compute: Box::new(calculation),
            value: RefCell::new(None),
            dirty: Cell::new(true),
        }),
    }
}

impl<T: Clone + PartialEq + 'static> DerivedState<T> {
    /// The value, recorded as read by the scope composing now.
    pub fn get(&self) -> T {
        let in_runtime = with_composer_if_idle(|_| ()).is_some();
        if !in_runtime {
            // Nothing outside a runtime can say when the inputs change, so nothing is
            // cached: the value is computed fresh.
            return (self.cell.compute)();
        }
        if self.cell.dirty.get() || self.cell.value.borrow().is_none() {
            self.recompute();
        }
        with_composer_if_idle(|composer| composer.record_read(self.cell.id));
        self.cell
            .value
            .borrow()
            .clone()
            .expect("a derived state holds a value once computed")
    }

    fn recompute(&self) -> bool {
        let id = self.cell.id;
        let node: Rc<dyn DerivedNode> = self.cell.clone();
        let weak = Rc::downgrade(&node);
        with_composer(|composer| {
            if let Some(deps) = composer.derived_deps.remove(&id) {
                for dep in deps {
                    composer.unobserve(dep, Observer::Derived(id));
                }
            }
            composer.ambient.deriveds.insert(id, weak);
            composer.derived_stack.push(id);
        });
        let computed = (self.cell.compute)();
        with_composer(|composer| {
            composer.derived_stack.pop();
        });
        self.cell.dirty.set(false);
        let mut stored = self.cell.value.borrow_mut();
        let changed = stored.as_ref() != Some(&computed);
        *stored = Some(computed);
        changed
    }
}

impl<T: Clone + PartialEq + 'static> DerivedNode for DerivedCell<T> {
    fn refresh(&self) -> bool {
        // The same as `DerivedState::recompute`, reached through the cell.
        let state = DerivedState {
            cell: rc_from_cell(self),
        };
        state.recompute()
    }

    fn mark_dirty(&self) {
        self.dirty.set(true);
    }
}

/// Recovers the `Rc` a derived cell lives in. Every cell is created inside an `Rc` by
/// `derived_state_of` and only reached through one, so the registry's weak handle is
/// always upgraded before this is called and the strong count is at least one.
fn rc_from_cell<T>(cell: &DerivedCell<T>) -> Rc<DerivedCell<T>> {
    let pointer = cell as *const DerivedCell<T>;
    // SAFETY: `cell` is the value of an `Rc<DerivedCell<T>>` allocation (see above), and
    // a strong reference is held by the caller for the duration of this call, so
    // incrementing the count and rebuilding a second `Rc` is sound.
    unsafe {
        Rc::increment_strong_count(pointer);
        Rc::from_raw(pointer)
    }
}

/// Values the Renderer reports, as states, so a composable that reads one runs again when
/// the report changes and nothing else does.
pub(crate) struct Ambient {
    pub(crate) window: MutableState<WindowSize>,
    pub(crate) design: MutableState<DesignSystem>,
    pub(crate) permission: MutableState<NotificationPermission>,
    pub(crate) deriveds: HashMap<u64, Weak<dyn DerivedNode>>,
}

impl Ambient {
    pub(crate) fn new() -> Self {
        Self {
            window: mutable_state_of(crate::window::window_size()),
            design: mutable_state_of(crate::design::design_system()),
            permission: mutable_state_of(crate::notification::notification_permission()),
            deriveds: HashMap::new(),
        }
    }
}

/// Runs again whatever read the states written since the last look.
///
/// Scopes are marked invalid. A derived state that somebody reads is computed again on
/// the spot, and its readers are marked only if its value moved; one nobody reads is
/// marked out of date and left for its next read. Called with the composer free, because
/// computing a derived state reads states.
pub(crate) fn apply_state_changes() {
    loop {
        let Some(mut changed) = with_composer(|composer| {
            let mut scratch = std::mem::take(&mut composer.dirty_scratch);
            std::mem::swap(&mut *lock(&composer.signal.dirty), &mut scratch);
            scratch
        }) else {
            return;
        };
        if changed.is_empty() {
            with_composer(|composer| composer.dirty_scratch = changed);
            return;
        }
        let mut index = 0;
        while index < changed.len() {
            let state = changed[index];
            index += 1;
            let observers = with_composer(|composer| {
                composer.observers.get(&state).cloned().unwrap_or_default()
            })
            .unwrap_or_default();
            for observer in observers {
                match observer {
                    Observer::Scope(group, generation) => {
                        with_composer(|composer| composer.invalidate(group, generation));
                    }
                    Observer::Derived(derived) => {
                        let (node, watched) = with_composer(|composer| {
                            let node = composer
                                .ambient
                                .deriveds
                                .get(&derived)
                                .and_then(Weak::upgrade);
                            let watched = composer
                                .observers
                                .get(&derived)
                                .is_some_and(|list| !list.is_empty());
                            (node, watched)
                        })
                        .unwrap_or((None, false));
                        let Some(node) = node else {
                            continue;
                        };
                        if watched {
                            if node.refresh() && !changed.contains(&derived) {
                                changed.push(derived);
                            }
                        } else {
                            node.mark_dirty();
                        }
                    }
                }
            }
        }
        changed.clear();
        with_composer(|composer| composer.dirty_scratch = changed);
    }
}

/// The window's size, recorded as read: the composable runs again when the Renderer
/// reports a different size class.
pub fn current_window_size() -> WindowSize {
    match with_composer_if_idle(|composer| composer.ambient.window.clone()) {
        Some(state) => state.get(),
        None => crate::window::window_size(),
    }
}

/// The design system the Renderer resolved the theme to, recorded as read.
///
/// Not how an application chooses a design system, and not a way to paint by hand: it is
/// for the screen that is a different screen under a different system, such as a
/// calculator whose keys differ.
pub fn current_design_system() -> DesignSystem {
    match with_composer_if_idle(|composer| composer.ambient.design.clone()) {
        Some(state) => state.get(),
        None => crate::design::design_system(),
    }
}

/// Whether notifications may be shown, recorded as read.
pub fn current_notification_permission() -> NotificationPermission {
    match with_composer_if_idle(|composer| composer.ambient.permission.clone()) {
        Some(state) => state.get(),
        None => crate::notification::notification_permission(),
    }
}

const NODE_SIZE: u64 = super::composer::call_site("compose_rust::remember_node_size", 0);
const NOTIFICATION_ACTIVATED: u64 =
    super::composer::call_site("compose_rust::on_notification_activated", 0);

/// The slot a node size lives in: the state the screen reads, and the subscription that
/// moves it. Leaving the composition drops both, and the measurement with them.
struct NodeSizeSlot {
    token: u32,
    state: MutableState<WindowSize>,
    _subscription: crate::window::NodeSizeSubscription,
}

/// Follows one node's measured size, recorded as read. Compose has no single equivalent;
/// it is `onSizeChanged` with the class judgement made for you.
///
/// Give the token to the node with `Modifier.observe_size(size.token())`. Nothing is
/// measured until a node carries it, and the composable reading it runs again only when
/// the node's size class changes.
pub fn remember_node_size() -> crate::window::NodeSize {
    let _group = super::group_guard(NODE_SIZE);
    let found = with_composer_if_idle(|composer| {
        if !composer.composing() {
            return None;
        }
        if let Some(slot) = composer.slot_mut::<NodeSizeSlot>() {
            return Some((slot.token, slot.state.clone()));
        }
        let token = crate::window::next_node_token();
        let state = mutable_state_of(crate::window::node_size(token));
        let follow = state.clone();
        let subscription = crate::window::subscribe_node(
            token,
            std::sync::Arc::new(move || follow.set(crate::window::node_size(token))),
        );
        composer.remember_new(NodeSizeSlot {
            token,
            state: state.clone(),
            _subscription: subscription,
        });
        Some((token, state))
    })
    .flatten();
    match found {
        Some((token, state)) => {
            // Read through the state, so this scope runs again when the class changes;
            // the value itself is the module's.
            let _ = state.get();
            crate::window::NodeSize::of(token)
        }
        None => crate::window::NodeSize::of(0),
    }
}

/// The slot a notification handler lives in: the latest handler, and the registration
/// that lasts as long as the slot does.
struct ActivationSlot {
    handler: Rc<RefCell<Box<dyn FnMut(crate::NotificationActivation)>>>,
    _subscription: crate::notification::ActivationSubscription,
}

/// Calls `handler` when the user presses a notification this application posted, for as
/// long as this call site is in the composition.
///
/// The handler is replaced by the latest one on every composition, so it always sees the
/// states it captured this time.
pub fn on_notification_activated(handler: impl FnMut(crate::NotificationActivation) + 'static) {
    let _group = super::group_guard(NOTIFICATION_ACTIVATED);
    let handler: Box<dyn FnMut(crate::NotificationActivation)> = Box::new(handler);
    let mut handler = Some(handler);
    let old = with_composer_if_idle(|composer| {
        if !composer.composing() {
            return None;
        }
        if let Some(slot) = composer.slot_mut::<ActivationSlot>() {
            let new = handler.take().expect("the handler is installed once");
            return Some(std::mem::replace(&mut *slot.handler.borrow_mut(), new));
        }
        let shared = Rc::new(RefCell::new(
            handler.take().expect("the handler is installed once"),
        ));
        let forward = Rc::clone(&shared);
        let subscription = crate::notification::on_activation(Rc::new(
            move |activation: crate::NotificationActivation| {
                (&mut *forward.borrow_mut())(activation)
            },
        ));
        composer.remember_new(ActivationSlot {
            handler: shared,
            _subscription: subscription,
        });
        None
    })
    .flatten();
    // The handler it replaced is dropped here, with the composer free.
    drop(old);
}
