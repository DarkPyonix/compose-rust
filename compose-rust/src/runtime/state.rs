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
use std::any::Any;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::fmt;
use std::marker::PhantomData;
use std::panic::Location;
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

impl<T> StateCell<T> {
    fn notify(&self) {
        let mut told = false;
        lock(&self.runtimes).retain(|runtime| match runtime.upgrade() {
            Some(signal) => {
                signal.push_dirty(self.id);
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

// ----- the arena ---------------------------------------------------------------------

/// A place in the arena: the slot's index, and the generation it had when the state was
/// put there. A slot that is freed and used again has a higher generation, so a handle
/// to the old state never reaches the new one.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) struct StateKey {
    index: u32,
    generation: u32,
}

struct Entry {
    generation: u32,
    cell: Option<Arc<dyn Any + Send + Sync>>,
}

struct Arena {
    entries: Vec<Entry>,
    free: Vec<u32>,
}

/// Every state's value lives here, not in the handle. One lock for the table, and a lock
/// of its own for each value, so a long read of one state does not hold up the others.
static ARENA: Mutex<Arena> = Mutex::new(Arena {
    entries: Vec::new(),
    free: Vec::new(),
});

/// The handles that have been warned about, for a write after the state was freed.
static WARNED: Mutex<Vec<StateKey>> = Mutex::new(Vec::new());

fn allocate(cell: Arc<dyn Any + Send + Sync>) -> StateKey {
    let mut arena = lock(&ARENA);
    if let Some(index) = arena.free.pop() {
        let entry = &mut arena.entries[index as usize];
        entry.cell = Some(cell);
        return StateKey {
            index,
            generation: entry.generation,
        };
    }
    let index = arena.entries.len() as u32;
    arena.entries.push(Entry {
        generation: 0,
        cell: Some(cell),
    });
    StateKey {
        index,
        generation: 0,
    }
}

/// Frees a state and gives its value back to be dropped by the caller, once, with no lock
/// held. `None` when the key was already freed.
pub(crate) fn release_state(key: StateKey) -> Option<Box<dyn Any>> {
    let mut arena = lock(&ARENA);
    let entry = arena.entries.get_mut(key.index as usize)?;
    if entry.generation != key.generation {
        return None;
    }
    let cell = entry.cell.take()?;
    entry.generation = entry.generation.wrapping_add(1);
    arena.free.push(key.index);
    Some(Box::new(cell))
}

// ----- who owns a state --------------------------------------------------------------

thread_local! {
    /// The states made since each open `remember` began, and where that call is.
    static PENDING: RefCell<Vec<StateKey>> = const { RefCell::new(Vec::new()) };
    static SITES: RefCell<Vec<&'static Location<'static>>> = const { RefCell::new(Vec::new()) };
}

/// The span of a `remember` call's initialiser. States made inside it belong to the
/// slot the call fills.
pub(crate) struct Capture {
    mark: usize,
}

impl Capture {
    pub(crate) fn begin(site: &'static Location<'static>) -> Self {
        let mark = PENDING.with_borrow(Vec::len);
        SITES.with_borrow_mut(|sites| sites.push(site));
        Self { mark }
    }

    /// The states made since `begin`.
    pub(crate) fn finish(self) -> Vec<StateKey> {
        PENDING.with_borrow_mut(|pending| pending.split_off(self.mark.min(pending.len())))
    }
}

impl Drop for Capture {
    fn drop(&mut self) {
        SITES.with_borrow_mut(|sites| {
            sites.pop();
        });
        PENDING.with_borrow_mut(|pending| pending.truncate(self.mark));
    }
}

/// Why a state could not be read or written.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StateError {
    /// The `remember` call that made the state has left the composition, and the state
    /// went with it.
    Released,
}

impl fmt::Display for StateError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Released => formatter.write_str(
                "Released: the state left the composition with the remember call that made it",
            ),
        }
    }
}

impl std::error::Error for StateError {}

/// A handle to a value that composition observes. Compose's `MutableState`.
///
/// The handle is `Copy`: it names a place in an arena, and the value is owned by the
/// composition. The value lives as long as the `remember` call that made it stays in the
/// composition and is dropped, once, when that call's group is removed. A handle used
/// after that is released: `set` and `update` do nothing, `get` panics, and the `try_`
/// forms return [`StateError::Released`]. A slot reused for another state has a new
/// generation, so an old handle never reaches the new value.
///
/// Two handles are equal when they name the same state, which is what makes a state a
/// stable parameter: passing the same state to a composable again does not make it run.
/// Equal values in two different states are not equal handles.
///
/// A handle is `Send` and `Sync`, so a worker thread can take one by `move` and write it.
/// The write is visible at once; the composables that read it run again on the next
/// frame, which the write asks for. A read on a worker clones the value and subscribes to
/// nothing.
///
/// Reading takes a lock for as long as the read lasts. Reading the same state again inside
/// [`MutableState::with`] would wait on itself.
pub struct MutableState<T> {
    key: StateKey,
    /// The `remember` call that made the state, for the message of a read that fails.
    site: &'static Location<'static>,
    marker: PhantomData<fn() -> T>,
}

impl<T> Clone for MutableState<T> {
    fn clone(&self) -> Self {
        *self
    }
}

impl<T> Copy for MutableState<T> {}

impl<T> PartialEq for MutableState<T> {
    fn eq(&self, other: &Self) -> bool {
        self.key == other.key
    }
}

impl<T> Eq for MutableState<T> {}

impl<T> std::hash::Hash for MutableState<T> {
    fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
        self.key.hash(state);
    }
}

impl<T> fmt::Debug for MutableState<T> {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("MutableState")
            .field("index", &self.key.index)
            .field("generation", &self.key.generation)
            .finish()
    }
}

/// A state whose writes of an equal value change nothing. Compose's `mutableStateOf`.
///
/// ```ignore
/// let count = remember(|| mutable_state_of(0));
/// Text!(format!("{}", count.get()));
/// Button!("More", move || count.update(|count| *count += 1));
/// ```
///
/// Made inside `remember`, the state belongs to that call. Made anywhere else, it is not
/// freed until the program ends.
#[track_caller]
pub fn mutable_state_of<T: PartialEq + Send + 'static>(value: T) -> MutableState<T> {
    mutable_state_of_with_policy(value, structural_equality_policy())
}

/// A state with a policy of its own for what counts as a change.
#[track_caller]
pub fn mutable_state_of_with_policy<T: Send + 'static>(
    value: T,
    policy: SnapshotMutationPolicy<T>,
) -> MutableState<T> {
    let here = Location::caller();
    let cell: Arc<dyn Any + Send + Sync> = Arc::new(StateCell {
        id: next_state_id(),
        value: Mutex::new(value),
        policy,
        runtimes: Mutex::new(Vec::new()),
    });
    let key = allocate(cell);
    let site = SITES.with_borrow(|sites| sites.last().copied());
    if site.is_some() {
        PENDING.with_borrow_mut(|pending| pending.push(key));
    }
    MutableState {
        key,
        site: site.unwrap_or(here),
        marker: PhantomData,
    }
}

impl<T: Send + 'static> MutableState<T> {
    fn resolve(&self) -> Option<Arc<StateCell<T>>> {
        let cell = {
            let arena = lock(&ARENA);
            let entry = arena.entries.get(self.key.index as usize)?;
            if entry.generation != self.key.generation {
                return None;
            }
            entry.cell.clone()?
        };
        cell.downcast::<StateCell<T>>().ok()
    }

    fn released(&self) -> ! {
        panic!(
            "Released: this MutableState was used after the remember call at {}:{} left the composition",
            self.site.file(),
            self.site.line()
        )
    }

    fn warn_released(&self) {
        if !cfg!(debug_assertions) {
            return;
        }
        {
            let mut warned = lock(&WARNED);
            if warned.contains(&self.key) {
                return;
            }
            warned.push(self.key);
        }
        eprintln!(
            "compose-rust: a write to a released MutableState was ignored (made by the remember call at {}:{})",
            self.site.file(),
            self.site.line()
        );
    }

    /// How many warnings this handle has caused: one in a debug build after its first
    /// write while released, never in a release build.
    #[doc(hidden)]
    pub fn released_warning_count(&self) -> usize {
        lock(&WARNED).iter().filter(|key| **key == self.key).count()
    }

    /// Whether the state is still in the composition.
    pub fn is_released(&self) -> bool {
        self.resolve().is_none()
    }

    /// The value, recorded as read by the scope composing now. Panics if the state is
    /// released.
    pub fn get(&self) -> T
    where
        T: Clone,
    {
        self.try_get().unwrap_or_else(|_| self.released())
    }

    /// Like [`MutableState::get`], and `Err(StateError::Released)` if the state is
    /// released.
    pub fn try_get(&self) -> Result<T, StateError>
    where
        T: Clone,
    {
        let cell = self.resolve().ok_or(StateError::Released)?;
        Self::track(&cell);
        let value = lock(&cell.value).clone();
        Ok(value)
    }

    /// The value, without recording a read. A scope that only peeks is not run again when
    /// the value changes. Panics if the state is released.
    pub fn get_untracked(&self) -> T
    where
        T: Clone,
    {
        match self.resolve() {
            Some(cell) => lock(&cell.value).clone(),
            None => self.released(),
        }
    }

    /// Reads the value in place, recorded as read. Panics if the state is released.
    pub fn with<R>(&self, read: impl FnOnce(&T) -> R) -> R {
        match self.resolve() {
            Some(cell) => {
                Self::track(&cell);
                let value = lock(&cell.value);
                read(&value)
            }
            None => self.released(),
        }
    }

    /// Replaces the value. Under the default policy an equal value changes nothing. Does
    /// nothing if the state is released.
    pub fn set(&self, value: T) {
        if self.try_set(value).is_err() {
            self.warn_released();
        }
    }

    /// Like [`MutableState::set`], and `Err(StateError::Released)` if the state is
    /// released.
    pub fn try_set(&self, value: T) -> Result<(), StateError> {
        let cell = self.resolve().ok_or(StateError::Released)?;
        {
            let mut current = lock(&cell.value);
            if (cell.policy.equivalent)(&current, &value) {
                return Ok(());
            }
            *current = value;
        }
        cell.notify();
        Ok(())
    }

    /// Changes the value in place. Always a change: there is no old value left to compare
    /// against without a copy, and a caller who wants the comparison can use `set`. Does
    /// nothing if the state is released.
    pub fn update(&self, change: impl FnOnce(&mut T)) {
        if self.try_update(change).is_err() {
            self.warn_released();
        }
    }

    /// Like [`MutableState::update`], and `Err(StateError::Released)` if the state is
    /// released.
    pub fn try_update(&self, change: impl FnOnce(&mut T)) -> Result<(), StateError> {
        let cell = self.resolve().ok_or(StateError::Released)?;
        change(&mut lock(&cell.value));
        cell.notify();
        Ok(())
    }

    /// This state as a read-only [`State`].
    pub fn as_state(&self) -> MutableState<T> {
        *self
    }

    fn track(cell: &Arc<StateCell<T>>) {
        let fresh = with_composer_if_idle(|composer| {
            composer
                .record_read(cell.id)
                .then(|| Arc::clone(&composer.signal))
        })
        .flatten();
        if let Some(signal) = fresh {
            let mut runtimes = lock(&cell.runtimes);
            if !runtimes
                .iter()
                .any(|known| std::ptr::eq(known.as_ptr(), Arc::as_ptr(&signal)))
            {
                runtimes.push(Arc::downgrade(&signal));
            }
        }
    }
}

/// Something composition can read. Compose's `State`.
pub trait State<T> {
    /// The value, recorded as read by the scope composing now.
    fn value(&self) -> T;
}

impl<T: Clone + Send + 'static> State<T> for MutableState<T> {
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
/// if empty.get() { Text!("Nothing to do"); }
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
    /// What each window of a multi-window application last measured, as a state per
    /// window, made when a composable in that window first reads it.
    pub(crate) windows: HashMap<u32, WindowAmbient>,
    pub(crate) deriveds: HashMap<u64, Weak<dyn DerivedNode>>,
}

/// One window's size as a state, and the subscription that moves it.
pub(crate) struct WindowAmbient {
    state: MutableState<WindowSize>,
    _subscription: crate::window::WindowSubscription,
}

impl Drop for WindowAmbient {
    fn drop(&mut self) {
        drop(release_state(self.state.key));
    }
}

impl Drop for Ambient {
    fn drop(&mut self) {
        let values = [
            release_state(self.window.key),
            release_state(self.design.key),
            release_state(self.permission.key),
        ];
        drop(values);
    }
}

impl Ambient {
    pub(crate) fn new() -> Self {
        Self {
            window: mutable_state_of(crate::window::window_size()),
            design: mutable_state_of(crate::design::design_system()),
            permission: mutable_state_of(crate::notification::notification_permission()),
            windows: HashMap::new(),
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
///
/// In a multi-window application it is the size of the window the composable is in,
/// and the composable runs again only when that window's class changes.
pub fn current_window_size() -> WindowSize {
    window_size_in(crate::windows::current_window_id())
}

/// One window's size, recorded as read: window `0` is the single window, any other id a
/// `Window` node of a multi-window application. Named rather than looked up, so a derived
/// state computed outside the composition still reads the window it was made in.
fn window_size_in(window: u32) -> WindowSize {
    if window != 0 {
        return match window_state(window) {
            Some(state) => state.get(),
            None => crate::window::window_size_of(window),
        };
    }
    match with_composer_if_idle(|composer| composer.ambient.window) {
        Some(state) => state.get(),
        None => crate::window::window_size(),
    }
}

const WINDOW_BREAKPOINT: u64 =
    super::composer::call_site("compose_rust::current_window_breakpoint", 0);

/// Which of the application's own width buckets the window is in, recorded as read: the
/// composable runs again only when the bucket changes, not on every size report.
///
/// `breakpoints` are widths in dp. The result is how many of them the window's width has
/// reached (`width_dp >= breakpoint`), so with `[560.0, 900.0, 1279.0]` a width of
/// 559.9 is bucket 0, 560 is 1, 900 is 2 and 1279 is 3. A width exactly on a breakpoint
/// belongs to the bucket above it, as with `min-width` in CSS; pass 561 for a CSS
/// `max-width: 560px`. The order of the list does not change the result. Values that are
/// not finite, or not above zero, are not counted, so a window nothing has measured yet
/// (0 dp) is always in bucket 0. A debug build warns when a call site first uses such a
/// list, or one out of order.
///
/// ```ignore
/// match current_window_breakpoint(&[560.0, 900.0, 1279.0]) {
///     0 => phone(),
///     1 | 2 => two_columns(),
///     _ => three_columns(),
/// }
/// ```
///
/// This does not make the window report its size more often. By default the Renderer
/// reports only when the size class or the safe area changes, so a bucket edge that is not
/// a class edge is seen at the next class change. To see every edge as it is crossed, open
/// the window with `report_size(true)` on its `Window` node, or
/// `Window::new().with_report_size(true)` for the single window; that costs a Host call per
/// whole dp while a window edge is dragged, which is why it stays the application's choice.
///
/// In a multi-window application it is the width of the window the composable is in.
#[track_caller]
pub fn current_window_breakpoint(breakpoints: &[f32]) -> usize {
    let site = Location::caller();
    let _group = super::group_guard(WINDOW_BREAKPOINT);
    let window = crate::windows::current_window_id();
    let counted: Vec<f32> = breakpoints
        .iter()
        .copied()
        .filter(|breakpoint| breakpoint.is_finite() && *breakpoint > 0.0)
        .collect();
    let key = (window, counted.clone());
    let bucket = super::remember_with_key(key, move || {
        warn_about_breakpoints(breakpoints, site);
        derived_state_of(move || {
            let width = window_size_in(window).width_dp;
            counted
                .iter()
                .filter(|breakpoint| width >= **breakpoint)
                .count()
        })
    });
    bucket.get()
}

/// Says, in a debug build, that a breakpoint list is out of order or holds values that are
/// not counted. Neither is an error: the result is the same as for the cleaned-up list.
fn warn_about_breakpoints(breakpoints: &[f32], site: &Location<'_>) {
    if !cfg!(debug_assertions) {
        return;
    }
    let ignored = breakpoints
        .iter()
        .any(|breakpoint| !breakpoint.is_finite() || *breakpoint <= 0.0);
    let unordered = breakpoints
        .windows(2)
        .any(|pair| pair[0].partial_cmp(&pair[1]) != Some(std::cmp::Ordering::Less));
    if ignored || unordered {
        eprintln!(
            "compose-rust: current_window_breakpoint at {}:{} was given {:?}; breakpoints are \
             counted as widths above zero in ascending order, so NaN, infinite and \
             non-positive values are left out and the order does not matter",
            site.file(),
            site.line(),
            breakpoints
        );
    }
}

/// The state that follows one window's size, made on first use.
fn window_state(window: u32) -> Option<MutableState<WindowSize>> {
    with_composer_if_idle(|composer| {
        if let Some(entry) = composer.ambient.windows.get(&window) {
            return entry.state;
        }
        let state = mutable_state_of(crate::window::window_size_of(window));
        // The ambient owns this state, not a `remember` call that happens to be open.
        PENDING.with_borrow_mut(|pending| pending.retain(|key| *key != state.key));
        let follow = state;
        let subscription = crate::window::subscribe_window(
            window,
            Arc::new(move || follow.set(crate::window::window_size_of(window))),
        );
        composer.ambient.windows.insert(
            window,
            WindowAmbient {
                state,
                _subscription: subscription,
            },
        );
        state
    })
}

/// The design system the Renderer resolved the theme to, recorded as read.
///
/// Not how an application chooses a design system, and not a way to paint by hand: it is
/// for the screen that is a different screen under a different system, such as a
/// calculator whose keys differ.
pub fn current_design_system() -> DesignSystem {
    match with_composer_if_idle(|composer| composer.ambient.design) {
        Some(state) => state.get(),
        None => crate::design::design_system(),
    }
}

/// Whether notifications may be shown, recorded as read.
pub fn current_notification_permission() -> NotificationPermission {
    match with_composer_if_idle(|composer| composer.ambient.permission) {
        Some(state) => state.get(),
        None => crate::notification::notification_permission(),
    }
}

const NODE_SIZE: u64 = super::composer::call_site("compose_rust::remember_node_size", 0);
const NODE_BREAKPOINT: u64 = super::composer::call_site("compose_rust::current_node_breakpoint", 0);
const NOTIFICATION_ACTIVATED: u64 =
    super::composer::call_site("compose_rust::on_notification_activated", 0);

/// The slot a node size lives in: the state the screen reads, and the subscription that
/// moves it. Leaving the composition drops both, and the measurement with them.
struct NodeSizeSlot {
    token: u32,
    state: MutableState<WindowSize>,
    _subscription: crate::window::NodeSizeSubscription,
}

/// Follows one node's measured size. Compose has no single equivalent; it is
/// `onSizeChanged` with the class judgement made for you.
///
/// Give the token to the node with `Modifier.observe_size(size.token())`. Nothing is
/// measured until a node carries it. Reading `size.measured()` or `size.is_*()` records
/// the size state as read by the composing scope; passing `size` to
/// [`current_node_breakpoint`] reads it inside a derived state so the scope runs again
/// only when the width bucket changes.
pub fn remember_node_size() -> crate::window::NodeSize {
    let _group = super::group_guard(NODE_SIZE);
    let found = with_composer_if_idle(|composer| {
        if !composer.composing() {
            return None;
        }
        if let Some(slot) = composer.slot_mut::<NodeSizeSlot>() {
            return Some((slot.token, slot.state));
        }
        let token = crate::window::next_node_token();
        let capture = Capture::begin(Location::caller());
        let state = mutable_state_of(crate::window::node_size(token));
        let owned = capture.finish();
        let follow = state;
        let subscription = crate::window::subscribe_node(
            token,
            std::sync::Arc::new(move || follow.set(crate::window::node_size(token))),
        );
        composer.remember_new_owning(
            NodeSizeSlot {
                token,
                state,
                _subscription: subscription,
            },
            owned,
        );
        Some((token, state))
    })
    .flatten();
    match found {
        Some((token, state)) => crate::window::NodeSize::from_state(token, state),
        None => crate::window::NodeSize::of(0),
    }
}

/// Which of the application's own width buckets an observed node is in, recorded as read:
/// the composable runs again only when the bucket changes, not on every size report.
///
/// `size` comes from [`remember_node_size`]. `breakpoints` are widths in dp. The result is
/// how many finite positive breakpoints the node's width has reached
/// (`width_dp >= breakpoint`), so with `[300.0, 600.0, 900.0]` a width of 299.9 is
/// bucket 0, 300 is 1, 600 is 2 and 900 is 3. A width exactly on a breakpoint belongs to
/// the bucket above it, matching `current_window_breakpoint`'s rules. The list is used as
/// given without sorting or deduplicating; because the result is a count, order does not
/// change it. Values that are not finite, or not above zero, are not counted, so a node
/// nothing has measured yet (0 dp) is always in bucket 0. A debug build warns when a call
/// site first uses such a list, or one out of order.
///
/// ```ignore
/// let size = remember_node_size();
/// let bucket = current_node_breakpoint(size, &[300.0, 600.0]);
/// Column!(modifier = Modifier.observe_size_every_dp(size.token()), {
///     match bucket {
///         0 => compact_pane(),
///         1 => medium_pane(),
///         _ => wide_pane(),
///     }
/// });
/// ```
///
/// This does not turn on per-dp reporting. Attach
/// `Modifier.observe_size_every_dp(size.token())` to the node to see every breakpoint edge
/// as it is crossed; with `Modifier.observe_size` the bucket updates only on the first
/// measurement and on size-class changes.
#[track_caller]
pub fn current_node_breakpoint(size: crate::window::NodeSize, breakpoints: &[f32]) -> usize {
    let site = Location::caller();
    let _group = super::group_guard(NODE_BREAKPOINT);
    let counted: Vec<f32> = breakpoints
        .iter()
        .copied()
        .filter(|breakpoint| breakpoint.is_finite() && *breakpoint > 0.0)
        .collect();
    let key = (size, counted.clone());
    let bucket = super::remember_with_key(key, move || {
        warn_about_node_breakpoints(breakpoints, site);
        derived_state_of(move || {
            let width = size.measured().width_dp;
            counted
                .iter()
                .filter(|breakpoint| width >= **breakpoint)
                .count()
        })
    });
    bucket.get()
}

/// Says, in a debug build, that a node breakpoint list is out of order or holds values
/// that are not counted. Neither is an error: the result is the same as for the cleaned-up
/// list.
fn warn_about_node_breakpoints(breakpoints: &[f32], site: &Location<'_>) {
    if !cfg!(debug_assertions) {
        return;
    }
    let ignored = breakpoints
        .iter()
        .any(|breakpoint| !breakpoint.is_finite() || *breakpoint <= 0.0);
    let unordered = breakpoints
        .windows(2)
        .any(|pair| pair[0].partial_cmp(&pair[1]) != Some(std::cmp::Ordering::Less));
    if ignored || unordered {
        eprintln!(
            "compose-rust: current_node_breakpoint at {}:{} was given {:?}; breakpoints are \
             counted as widths above zero in ascending order, so NaN, infinite and \
             non-positive values are left out and the order does not matter",
            site.file(),
            site.line(),
            breakpoints
        );
    }
}

/// A notification handler, shared between the slot and the registration that calls it.
type ActivationHandler = Rc<RefCell<Box<dyn FnMut(crate::NotificationActivation)>>>;

/// The slot a notification handler lives in: the latest handler, and the registration
/// that lasts as long as the slot does.
struct ActivationSlot {
    handler: ActivationHandler,
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
            move |activation: crate::NotificationActivation| (*forward.borrow_mut())(activation),
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

const WINDOW_REQUESTED: u64 = super::composer::call_site("compose_rust::on_window_requested", 0);
const APPLICATION_REOPEN: u64 =
    super::composer::call_site("compose_rust::on_application_reopen", 0);

/// The slot a registered handler lives in: the latest handler, and the registration that
/// lasts as long as the slot does.
type SharedHandler<A> = Rc<RefCell<Box<dyn FnMut(A)>>>;

struct HandlerSlot<A: 'static> {
    handler: SharedHandler<A>,
    _subscription: Box<dyn std::any::Any>,
}

/// Registers `subscribe`'s handler once for this call site and replaces the closure it
/// calls on every composition, so it always sees the states it captured this time. The
/// registration ends when the call site leaves the composition.
fn install_handler<A: 'static>(
    key: u64,
    handler: Box<dyn FnMut(A)>,
    subscribe: impl FnOnce(Rc<dyn Fn(A)>) -> Box<dyn std::any::Any>,
) {
    let _group = super::group_guard(key);
    let mut handler = Some(handler);
    let mut subscribe = Some(subscribe);
    let old = with_composer_if_idle(|composer| {
        if !composer.composing() {
            return None;
        }
        if let Some(slot) = composer.slot_mut::<HandlerSlot<A>>() {
            let new = handler.take().expect("the handler is installed once");
            return Some(std::mem::replace(&mut *slot.handler.borrow_mut(), new));
        }
        let shared = Rc::new(RefCell::new(
            handler.take().expect("the handler is installed once"),
        ));
        let forward = Rc::clone(&shared);
        let subscription = (subscribe.take().expect("the handler is installed once"))(Rc::new(
            move |argument: A| (*forward.borrow_mut())(argument),
        ));
        composer.remember_new(HandlerSlot {
            handler: shared,
            _subscription: subscription,
        });
        None
    })
    .flatten();
    // The handler it replaced is dropped here, with the composer free.
    drop(old);
}

/// Calls `handler` for each window the system or the user made that this application has
/// no `Window` node for, for as long as this call site is in the composition.
/// The handler puts a node with that `RestoreKey` into the tree, or the Renderer closes the
/// window.
pub fn on_window_requested(handler: impl FnMut(crate::windows::WindowRequest) + 'static) {
    install_handler::<crate::windows::WindowRequest>(
        WINDOW_REQUESTED,
        Box::new(handler),
        |forward| Box::new(crate::windows::on_window_request(forward)),
    );
}

/// Calls `handler` when the user opens the application again while it has no window, which
/// is the Dock icon on macOS. The handler puts a `Window` node into the tree.
pub fn on_application_reopen(handler: impl FnMut() + 'static) {
    let mut handler = handler;
    install_handler::<()>(
        APPLICATION_REOPEN,
        Box::new(move |(): ()| handler()),
        |forward| {
            Box::new(crate::windows::on_application_reopen_handler(Rc::new(
                move || forward(()),
            )))
        },
    );
}
