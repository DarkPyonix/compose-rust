//! compose-rust's own runtime: positional memoisation on a slot table, with Compose's
//! shape and Compose's names.
//!
//! ```ignore
//! use compose_rust::runtime::*;
//! use compose_rust::ui::*;
//!
//! #[composable]
//! fn Counter() {
//!     let count = remember(|| mutable_state_of(0));
//!     Column().content(|| {
//!         Text(format!("Count: {}", count.get()));
//!         Button("Increment").on_click(move || count.update(|count| *count += 1));
//!     });
//! }
//!
//! fn main() {
//!     compose_rust::ui::application(Counter);
//! }
//! ```
//!
//! **Nothing is compared to find out what changed.** A state remembers which scopes read
//! it; writing it marks those scopes invalid, and the next frame runs exactly them again.
//! Inside a scope that runs, a composable whose parameters equal the ones it last had is
//! skipped, its slots and nodes carried over untouched. What a widget writes is compared
//! with what it wrote last time, one attribute at a time, and only an attribute that
//! changed becomes a record.
//!
//! **Positional memoisation.** [`remember`] belongs to its call site: the group path the
//! `#[composable]` macro builds around every function and every branch, and the order of
//! calls inside each group. A branch that stops being taken takes what it remembered with
//! it; a loop that gets shorter drops the iterations it lost. Where order is not identity,
//! because items move, [`key`] says which item is which.
//!
//! **One thread.** Composition, effects and event handlers run on the Renderer's UI
//! thread, inside a Host call. Other threads write [`MutableState`]s and wake futures; both
//! queue an id and ask for a frame, and the UI thread does the rest.

pub(crate) mod composer;
pub(crate) mod effects;
pub(crate) mod host;
pub(crate) mod state;

pub use compose_rust_macros::composable;
pub use effects::{
    CoroutineScope, DisposableEffectResult, delay, disposable_effect, launched_effect, on_dispose,
    remember_coroutine_scope, side_effect, with_frame_nanos, with_worker,
};
pub use host::{ComposeHost, Recomposer};
pub use state::{
    DerivedState, MutableState, SnapshotMutationPolicy, State, current_design_system,
    current_notification_permission, current_window_size, derived_state_of, mutable_state_of,
    mutable_state_of_with_policy, never_equal_policy, on_notification_activated,
    remember_node_size, structural_equality_policy,
};

use composer::{Composer, GroupId};
use std::cell::RefCell;
use std::hash::{Hash, Hasher};
use std::rc::Rc;

/// One composition and everything it owns. Compose's `Composition`.
pub(crate) struct Composition {
    pub(crate) composer: RefCell<Composer>,
}

thread_local! {
    /// The runtime whose Host call is running on this thread.
    static CURRENT: RefCell<Option<Rc<Composition>>> = const { RefCell::new(None) };
}

/// Makes a runtime current for the length of a Host call, and puts back whatever was
/// current before when dropped.
pub(crate) struct Enter {
    previous: Option<Rc<Composition>>,
}

impl Composition {
    pub(crate) fn new() -> Rc<Self> {
        Rc::new(Self {
            composer: RefCell::new(Composer::new()),
        })
    }

    pub(crate) fn enter(self: &Rc<Self>) -> Enter {
        let previous = CURRENT.with(|current| current.borrow_mut().replace(Rc::clone(self)));
        Enter { previous }
    }
}

impl Drop for Enter {
    fn drop(&mut self) {
        let previous = self.previous.take();
        let _ = CURRENT.try_with(|current| *current.borrow_mut() = previous);
    }
}

/// Runs `f` on the current composer.
///
/// Panics if the composer is already in use further up this thread's stack. Nothing in
/// the runtime calls application code while it holds the composer, so reaching that state
/// means a destructor or a callback was run at the wrong moment, and saying so is better
/// than corrupting the slot table.
pub(crate) fn with_composer<R>(f: impl FnOnce(&mut Composer) -> R) -> Option<R> {
    CURRENT.with(|current| {
        let current = current.borrow();
        let runtime = current.as_ref()?;
        let mut composer = runtime
            .composer
            .try_borrow_mut()
            .expect("the composer was reached again while it was already in use");
        Some(f(&mut composer))
    })
}

/// Like [`with_composer`], but answers `None` rather than panicking when there is no
/// runtime or the composer is busy. For reads that are allowed anywhere, such as a state
/// read in a destructor.
pub(crate) fn with_composer_if_idle<R>(f: impl FnOnce(&mut Composer) -> R) -> Option<R> {
    CURRENT
        .try_with(|current| {
            let current = current.try_borrow().ok()?;
            let runtime = current.as_ref()?;
            let mut composer = runtime.composer.try_borrow_mut().ok()?;
            Some(f(&mut composer))
        })
        .ok()
        .flatten()
}

/// Drops what left the composition, now that the composer is free for any destructor
/// that reaches back into the runtime.
pub(crate) fn drain_graveyard() {
    loop {
        let dead =
            with_composer(|composer| std::mem::take(&mut composer.graveyard)).unwrap_or_default();
        if dead.is_empty() {
            return;
        }
        drop(dead);
    }
}

/// Holds a group open for the rest of a block. The group closes on every way out of the
/// block, `return`, `?`, `break` and an unwind included, because it closes in `Drop`.
#[must_use = "the group closes when this is dropped, so it has to be bound for the length of the block"]
pub struct GroupGuard {
    active: bool,
}

impl Drop for GroupGuard {
    fn drop(&mut self) {
        // Closed on an unwind too: a panic caught further up the composition leaves the
        // walk where it would have been had the block returned. The composer is free by
        // then, because the borrow that was held when the panic started was released on
        // the way out, and if it is not, nothing is done rather than panicking twice.
        if self.active {
            with_composer_if_idle(Composer::end_group);
        }
    }
}

/// Opens a group with this key, if a composition is running.
pub(crate) fn group_guard(key: u64) -> GroupGuard {
    let active = with_composer_if_idle(|composer| {
        if composer.composing() {
            composer.start_group(key);
            true
        } else {
            false
        }
    })
    .unwrap_or(false);
    GroupGuard { active }
}

/// The value this call site remembered, or `init()`'s, kept for next time. Compose's
/// `remember`.
///
/// The value is returned by clone, so what is remembered is usually a handle: a
/// [`MutableState`], an `Rc`, a derived state. Outside a composition nothing is kept and
/// `init` runs every time.
pub fn remember<T: Clone + 'static>(init: impl FnOnce() -> T) -> T {
    let found = with_composer_if_idle(|composer| {
        if composer.composing() {
            Some(composer.remembered::<T>())
        } else {
            None
        }
    })
    .flatten();
    match found {
        Some(Some(value)) => value,
        Some(None) => {
            let value = init();
            with_composer(|composer| composer.remember_new(value.clone()));
            value
        }
        None => init(),
    }
}

/// Like [`remember`], but computed again whenever `key` changes. Compose's
/// `remember(key) { }`.
pub fn remember_with_key<K, T>(key: K, init: impl FnOnce() -> T) -> T
where
    K: PartialEq + Clone + 'static,
    T: Clone + 'static,
{
    let found = with_composer_if_idle(|composer| {
        if composer.composing() {
            Some(composer.remembered_if::<(K, T)>(|(stored, _)| *stored == key))
        } else {
            None
        }
    })
    .flatten();
    match found {
        Some(Some((_, value))) => value,
        Some(None) => {
            let value = init();
            // The cursor did not move, so this replaces the stale value where it stood.
            with_composer(|composer| composer.remember_new((key, value.clone())));
            value
        }
        None => init(),
    }
}

const KEY_BASE: u64 = composer::call_site("compose_rust::key", 0);

/// Gives `content` an identity of its own, made from `key` rather than from its position.
/// Compose's `key`.
///
/// In a loop over items that can move, each iteration's state belongs to whichever item it
/// was composed for, and a reordered item's node is moved rather than rebuilt:
///
/// ```ignore
/// for task in tasks.iter() {
///     key(task.id, || TaskRow(task.clone()));
/// }
/// ```
pub fn key<K: Hash, R>(key: K, content: impl FnOnce() -> R) -> R {
    let mut hasher = Fnv(0xcbf2_9ce4_8422_2325);
    key.hash(&mut hasher);
    let _group = group_guard(composer::mix(KEY_BASE, hasher.finish()));
    content()
}

/// FNV-1a, for keys: deterministic, so a key hashes the same in every run, and fast on
/// the small values keys usually are.
struct Fnv(u64);

impl Hasher for Fnv {
    fn finish(&self) -> u64 {
        self.0
    }

    fn write(&mut self, bytes: &[u8]) {
        for byte in bytes {
            self.0 ^= u64::from(*byte);
            self.0 = self.0.wrapping_mul(0x100_0000_01b3);
        }
    }
}

/// The scope composing now, to be told to run again by hand. Compose's
/// `currentRecomposeScope`.
#[derive(Clone, Copy, Debug)]
pub struct RecomposeScope {
    group: GroupId,
    generation: u32,
}

impl RecomposeScope {
    /// Runs the scope again on the next frame, whether or not anything it read changed.
    /// Called on the UI thread; a worker writes a state instead.
    pub fn invalidate(&self) {
        let woke = with_composer_if_idle(|composer| {
            composer.invalidate(self.group, self.generation);
        });
        if woke.is_some() && !crate::boundary::in_host_call() {
            crate::boundary::request_frame_from_worker();
        }
    }
}

/// The innermost scope composing now.
pub fn current_recompose_scope() -> Option<RecomposeScope> {
    with_composer_if_idle(|composer| {
        let group = *composer.scope_stack.last()?;
        Some(RecomposeScope {
            group,
            generation: composer.groups[group as usize].generation,
        })
    })
    .flatten()
}

/// What `#[composable]` expands into. Not for calling by hand: the macro's guarantees are
/// what make these safe to use.
#[doc(hidden)]
pub mod __private {
    use super::composer::GroupId;
    use super::{GroupGuard, group_guard, with_composer, with_composer_if_idle};
    use std::rc::Rc;

    pub use super::composer::call_site;

    /// Opens a branch group.
    pub fn group(key: u64) -> GroupGuard {
        group_guard(key)
    }

    /// A restartable function's group, open until dropped.
    #[must_use]
    pub struct RestartGroup {
        group: Option<GroupId>,
    }

    pub fn start_restart_group(key: u64) -> RestartGroup {
        let group = with_composer_if_idle(|composer| {
            composer
                .composing()
                .then(|| composer.start_restart_group(key))
        })
        .flatten();
        RestartGroup { group }
    }

    impl RestartGroup {
        /// Whether the body has to run regardless of the parameters.
        pub fn must_run(&self) -> bool {
            match self.group {
                Some(group) => with_composer(|composer| composer.must_run(group)).unwrap_or(true),
                None => true,
            }
        }

        /// Carries the group over as it was, without running the body.
        pub fn skip(&self) {
            if self.group.is_some() {
                with_composer(super::composer::Composer::skip_to_group_end);
            }
        }

        /// Whether the restart has to be captured again: there is none yet, or a parameter
        /// changed.
        pub fn wants_restart(&self) -> bool {
            match self.group {
                Some(group) => {
                    with_composer(|composer| composer.wants_restart(group)).unwrap_or(false)
                }
                None => false,
            }
        }

        pub fn set_restart(&self, restart: impl Fn() + 'static) {
            if let Some(group) = self.group {
                let restart: Rc<dyn Fn()> = Rc::new(restart);
                with_composer(|composer| composer.set_restart(group, restart));
            }
        }

        /// Compares one parameter with the one the group last ran with, and keeps it.
        pub fn param_changed<T: PartialEq + Clone + 'static>(
            &self,
            index: usize,
            value: &T,
        ) -> bool {
            match self.group {
                Some(group) => {
                    with_composer(|composer| composer.param_changed(group, index, value))
                        .unwrap_or(true)
                }
                None => true,
            }
        }
    }

    impl Drop for RestartGroup {
        fn drop(&mut self) {
            if self.group.is_some() {
                with_composer_if_idle(super::composer::Composer::end_group);
            }
        }
    }

    /// A parameter, for the comparison that decides a skip. Which trait method resolves
    /// for it is decided at compile time: a `PartialEq + Clone + 'static` value is
    /// compared and kept, anything else is always treated as changed.
    pub struct Param<'a, T>(pub &'a T);

    pub trait StableParam {
        type Value;
        fn __compose_changed(&self, scope: &RestartGroup, index: usize) -> bool;
        fn __compose_clone(&self) -> Option<Self::Value>;
    }

    impl<T: PartialEq + Clone + 'static> StableParam for Param<'_, T> {
        type Value = T;

        fn __compose_changed(&self, scope: &RestartGroup, index: usize) -> bool {
            scope.param_changed(index, self.0)
        }

        fn __compose_clone(&self) -> Option<T> {
            Some(self.0.clone())
        }
    }

    pub trait UnstableParam {
        type Value;
        fn __compose_changed(&self, scope: &RestartGroup, index: usize) -> bool;
        fn __compose_clone(&self) -> Option<Self::Value>;
    }

    impl<T> UnstableParam for &Param<'_, T> {
        type Value = T;

        fn __compose_changed(&self, _scope: &RestartGroup, _index: usize) -> bool {
            true
        }

        fn __compose_clone(&self) -> Option<T> {
            None
        }
    }
}
