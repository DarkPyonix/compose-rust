//! Effects: work that belongs to a place in the composition but must not run while it is
//! being composed.
//!
//! Composing describes the screen and has no business doing anything else, because a
//! scope can be run, skipped or run again at the runtime's discretion. What a composable
//! wants done is queued while it composes and run after the composition is applied, on
//! the UI thread, inside the same Host call: [`SideEffect`] every time, [`LaunchedEffect`]
//! when its key changes, [`DisposableEffect`] with a cleanup for when it leaves.
//!
//! Asynchronous work is a future polled on the UI thread by a small executor the runtime
//! owns. A future that waits is woken from wherever its waker is called, a worker thread
//! included, and waking asks the Renderer for a frame, the same single wake signal a state
//! written from a worker uses. Domain work does not run on the UI thread: [`with_worker`]
//! hands a closure to a worker thread and resumes the future with its result.

use super::composer::{Composer, Effect, GroupId, RuntimeSignal};
use super::{group_guard, with_composer, with_composer_if_idle};
use std::any::Any;
use std::cell::{Cell, RefCell};
use std::future::Future;
use std::pin::Pin;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, Weak};
use std::task::{Context, Poll, Wake, Waker};
use std::time::Duration;

type LocalFuture = Pin<Box<dyn Future<Output = ()>>>;

/// One task: a future and the waker that queues it to be polled again.
struct Task {
    future: Option<LocalFuture>,
    waker: Waker,
}

struct TaskWake {
    id: usize,
    queued: AtomicBool,
    signal: Weak<RuntimeSignal>,
}

impl Wake for TaskWake {
    fn wake(self: Arc<Self>) {
        self.wake_by_ref();
    }

    fn wake_by_ref(self: &Arc<Self>) {
        if self.queued.swap(true, Ordering::AcqRel) {
            return;
        }
        if let Some(signal) = self.signal.upgrade() {
            signal.push_woken(self.id);
            if !crate::boundary::in_host_call() {
                crate::boundary::request_frame_from_worker();
            }
        }
    }
}

/// The futures the composition started, polled on the UI thread.
#[derive(Default)]
pub(crate) struct Executor {
    tasks: Vec<Option<Task>>,
    wakes: Vec<Option<Arc<TaskWake>>>,
    free: Vec<usize>,
    /// Futures waiting for the next frame, with the cell the frame time is left in.
    frame_waiters: Vec<(Waker, Rc<Cell<Option<u64>>>)>,
    /// The time of the frame being served, for the timers that count frames.
    pub(crate) frame_time: Option<u64>,
}

impl Executor {
    /// Starts a task. It is polled for the first time at the next opportunity in this call.
    pub(crate) fn spawn(&mut self, future: LocalFuture, signal: &Arc<RuntimeSignal>) -> usize {
        let id = self.free.pop().unwrap_or(self.tasks.len());
        let wake = Arc::new(TaskWake {
            id,
            queued: AtomicBool::new(true),
            signal: Arc::downgrade(signal),
        });
        let task = Task {
            future: Some(future),
            waker: Waker::from(Arc::clone(&wake)),
        };
        if id == self.tasks.len() {
            self.tasks.push(Some(task));
            self.wakes.push(Some(Arc::clone(&wake)));
        } else {
            self.tasks[id] = Some(task);
            self.wakes[id] = Some(Arc::clone(&wake));
        }
        signal.push_woken(id);
        id
    }

    /// Stops a task. Its future is dropped after the composer is released.
    pub(crate) fn cancel(&mut self, id: usize, graveyard: &mut Vec<Box<dyn Any>>) {
        if let Some(slot) = self.tasks.get_mut(id) {
            if let Some(task) = slot.take() {
                if let Some(future) = task.future {
                    graveyard.push(Box::new(future));
                }
                self.wakes[id] = None;
                self.free.push(id);
            }
        }
    }

    fn take_for_poll(&mut self, id: usize) -> Option<(LocalFuture, Waker)> {
        let task = self.tasks.get_mut(id)?.as_mut()?;
        let future = task.future.take()?;
        if let Some(Some(wake)) = self.wakes.get(id) {
            wake.queued.store(false, Ordering::Release);
        }
        Some((future, task.waker.clone()))
    }

    fn put_back(&mut self, id: usize, future: LocalFuture, graveyard: &mut Vec<Box<dyn Any>>) {
        match self.tasks.get_mut(id).and_then(Option::as_mut) {
            Some(task) => task.future = Some(future),
            // Cancelled while it was being polled.
            None => graveyard.push(Box::new(future)),
        }
    }

    fn finish(&mut self, id: usize) {
        if let Some(slot) = self.tasks.get_mut(id) {
            if slot.take().is_some() {
                self.wakes[id] = None;
                self.free.push(id);
            }
        }
    }

    /// Hands every future waiting for a frame the frame's time, and wakes it.
    pub(crate) fn deliver_frame(&mut self, frame_time_nanos: u64) {
        self.frame_time = Some(frame_time_nanos);
        for (waker, cell) in self.frame_waiters.drain(..) {
            cell.set(Some(frame_time_nanos));
            waker.wake();
        }
    }

    pub(crate) fn has_frame_waiters(&self) -> bool {
        !self.frame_waiters.is_empty()
    }
}

impl Composer {
    pub(crate) fn spawn(&mut self, future: LocalFuture) -> usize {
        let signal = Arc::clone(&self.signal);
        self.executor.spawn(future, &signal)
    }
}

/// Polls every task that has been woken, until none is left woken. Called with the
/// composer free, because a future runs application code.
pub(crate) fn poll_woken() {
    for _ in 0..64 {
        let woken = with_composer(|composer| {
            std::mem::take(
                &mut *composer
                    .signal
                    .woken
                    .lock()
                    .unwrap_or_else(|poison| poison.into_inner()),
            )
        })
        .unwrap_or_default();
        if woken.is_empty() {
            return;
        }
        for id in woken {
            let Some((mut future, waker)) =
                with_composer(|composer| composer.executor.take_for_poll(id)).flatten()
            else {
                continue;
            };
            let mut context = Context::from_waker(&waker);
            match future.as_mut().poll(&mut context) {
                Poll::Ready(()) => {
                    with_composer(|composer| {
                        composer.executor.finish(id);
                        composer.graveyard.push(Box::new(future));
                    });
                }
                Poll::Pending => {
                    with_composer(|composer| {
                        let Composer {
                            executor,
                            graveyard,
                            ..
                        } = composer;
                        executor.put_back(id, future, graveyard);
                    });
                }
            }
        }
        super::drain_graveyard();
    }
}

// ----- the effect slots ------------------------------------------------------------------

/// Whether a launched effect is still wanted, and the task it became.
#[derive(Default)]
pub(crate) struct LaunchHandle {
    pub(crate) task: Cell<Option<usize>>,
    pub(crate) cancelled: Cell<bool>,
}

/// A disposable effect's cleanup, once it has run.
#[derive(Default)]
pub(crate) struct DisposeHandle {
    on_dispose: RefCell<Option<Box<dyn FnOnce()>>>,
    cancelled: Cell<bool>,
}

/// The tasks a coroutine scope started, cancelled with it.
#[derive(Default)]
pub(crate) struct CoroutineState {
    tasks: RefCell<Vec<usize>>,
    cancelled: Cell<bool>,
}

/// What an effect keeps in its call site's slot.
pub(crate) enum EffectSlot {
    Launched {
        key: Box<dyn Any>,
        handle: Rc<LaunchHandle>,
    },
    Disposable {
        key: Box<dyn Any>,
        handle: Rc<DisposeHandle>,
    },
    Coroutines(Rc<CoroutineState>),
}

impl EffectSlot {
    /// The call site left the composition, or its key changed: whatever it started stops,
    /// and a cleanup it registered runs after the composition is applied.
    pub(crate) fn dispose(&mut self, composer: &mut Composer) {
        match self {
            Self::Launched { handle, .. } => {
                handle.cancelled.set(true);
                if let Some(task) = handle.task.take() {
                    let Composer {
                        executor,
                        graveyard,
                        ..
                    } = composer;
                    executor.cancel(task, graveyard);
                }
            }
            Self::Disposable { handle, .. } => {
                handle.cancelled.set(true);
                if let Some(cleanup) = handle.on_dispose.borrow_mut().take() {
                    composer.disposals.push(cleanup);
                }
            }
            Self::Coroutines(state) => {
                state.cancelled.set(true);
                let tasks = std::mem::take(&mut *state.tasks.borrow_mut());
                let Composer {
                    executor,
                    graveyard,
                    ..
                } = composer;
                for task in tasks {
                    executor.cancel(task, graveyard);
                }
            }
        }
    }
}

const LAUNCHED_EFFECT: u64 = super::composer::call_site("compose_rust::LaunchedEffect", 0);
const DISPOSABLE_EFFECT: u64 = super::composer::call_site("compose_rust::DisposableEffect", 0);
const COROUTINE_SCOPE: u64 =
    super::composer::call_site("compose_rust::remember_coroutine_scope", 0);

/// Whether the current slot is an effect of this kind whose key equals `key`. Advances
/// past the slot when it is.
fn same_key<K: PartialEq + 'static>(composer: &mut Composer, launched: bool, key: &K) -> bool {
    let same = match composer.peek_slot_mut::<EffectSlot>() {
        Some(EffectSlot::Launched { key: old, .. }) if launched => {
            old.downcast_ref::<K>() == Some(key)
        }
        Some(EffectSlot::Disposable { key: old, .. }) if !launched => {
            old.downcast_ref::<K>() == Some(key)
        }
        _ => false,
    };
    if same {
        composer.slot_mut::<EffectSlot>();
    }
    same
}

/// Runs `block`'s future when this call site enters the composition, and again, after
/// cancelling the last one, whenever `key` changes. Cancelled when the call site leaves.
/// Compose's `LaunchedEffect`.
///
/// The future is polled on the UI thread. It may wait as long as it likes: waiting costs
/// nothing, and whatever wakes it asks for a frame. Work that takes time to compute goes
/// through [`with_worker`] so that the UI thread only ever resumes with the answer.
///
/// ```ignore
/// let seconds = remember(|| mutable_state_of(0));
/// LaunchedEffect((), move || async move {
///     loop {
///         delay(Duration::from_secs(1)).await;
///         seconds.update(|seconds| *seconds += 1);
///     }
/// });
/// ```
#[allow(non_snake_case)]
pub fn LaunchedEffect<K, F, Fut>(key: K, block: F)
where
    K: PartialEq + 'static,
    F: FnOnce() -> Fut + 'static,
    Fut: Future<Output = ()> + 'static,
{
    let _group = group_guard(LAUNCHED_EFFECT);
    with_composer_if_idle(|composer| {
        if !composer.composing() || same_key(composer, true, &key) {
            return;
        }
        let handle = Rc::new(LaunchHandle::default());
        composer.remember_new(EffectSlot::Launched {
            key: Box::new(key),
            handle: Rc::clone(&handle),
        });
        composer.push_effect(Effect::Launch {
            handle,
            start: Box::new(move || Box::pin(block()) as LocalFuture),
        });
    });
}

/// What a [`DisposableEffect`] leaves behind: the cleanup to run when it leaves.
pub struct DisposableEffectResult {
    cleanup: Box<dyn FnOnce()>,
}

/// The cleanup a [`DisposableEffect`] returns. Compose's `onDispose`.
pub fn on_dispose(cleanup: impl FnOnce() + 'static) -> DisposableEffectResult {
    DisposableEffectResult {
        cleanup: Box::new(cleanup),
    }
}

/// Runs `effect` after this call site enters the composition and again whenever `key`
/// changes, and runs the cleanup it returned before the next run and when the call site
/// leaves. Compose's `DisposableEffect`.
///
/// ```ignore
/// DisposableEffect(channel.clone(), move || {
///     let subscription = subscribe(&channel);
///     on_dispose(move || drop(subscription))
/// });
/// ```
#[allow(non_snake_case)]
pub fn DisposableEffect<K, F>(key: K, effect: F)
where
    K: PartialEq + 'static,
    F: FnOnce() -> DisposableEffectResult + 'static,
{
    let _group = group_guard(DISPOSABLE_EFFECT);
    with_composer_if_idle(|composer| {
        if !composer.composing() || same_key(composer, false, &key) {
            return;
        }
        let handle = Rc::new(DisposeHandle::default());
        composer.remember_new(EffectSlot::Disposable {
            key: Box::new(key),
            handle: Rc::clone(&handle),
        });
        composer.push_effect(Effect::Run(Box::new(move || {
            if handle.cancelled.get() {
                return;
            }
            let result = effect();
            if handle.cancelled.get() {
                (result.cleanup)();
            } else {
                *handle.on_dispose.borrow_mut() = Some(result.cleanup);
            }
        })));
    });
}

/// Runs `effect` after every composition of the scope it is in that is applied. Compose's
/// `SideEffect`: how a composable publishes something to an object the composition does
/// not manage.
#[allow(non_snake_case)]
pub fn SideEffect(effect: impl FnOnce() + 'static) {
    with_composer_if_idle(|composer| {
        if composer.composing() {
            composer.push_effect(Effect::Run(Box::new(effect)));
        }
    });
}

/// A place to start futures from an event handler, whose futures are cancelled when the
/// call site that remembered it leaves the composition. Compose's `CoroutineScope`.
#[derive(Clone)]
pub struct CoroutineScope {
    state: Rc<CoroutineState>,
}

impl CoroutineScope {
    /// Starts `future` on the UI thread's executor. It is polled for the first time before
    /// the Host call that started it returns.
    pub fn launch(&self, future: impl Future<Output = ()> + 'static) {
        if self.state.cancelled.get() {
            return;
        }
        let id = with_composer_if_idle(|composer| composer.spawn(Box::pin(future)));
        if let Some(id) = id {
            let mut tasks = self.state.tasks.borrow_mut();
            tasks.push(id);
        }
    }
}

/// A coroutine scope bound to this call site. Compose's `rememberCoroutineScope`.
///
/// ```ignore
/// let scope = remember_coroutine_scope();
/// Button("Refresh").on_click(move || scope.launch(async move {
///     let fresh = with_worker(fetch).await;
///     items.set(fresh);
/// }));
/// ```
pub fn remember_coroutine_scope() -> CoroutineScope {
    let _group = group_guard(COROUTINE_SCOPE);
    let state = with_composer_if_idle(|composer| {
        if !composer.composing() {
            return None;
        }
        if let Some(EffectSlot::Coroutines(state)) = composer.slot_mut::<EffectSlot>() {
            return Some(Rc::clone(state));
        }
        let state = Rc::new(CoroutineState::default());
        composer.remember_new(EffectSlot::Coroutines(Rc::clone(&state)));
        Some(state)
    })
    .flatten();
    CoroutineScope {
        state: state.unwrap_or_default(),
    }
}

/// Runs the effects a composition queued: cleanups first, then new effects, as Compose
/// runs forgotten observers before remembered ones. Called with the composer free.
pub(crate) fn apply_effects() {
    let (disposals, effects) = with_composer(|composer| {
        (
            std::mem::take(&mut composer.disposals),
            std::mem::take(&mut composer.effects),
        )
    })
    .unwrap_or_default();
    for cleanup in disposals {
        cleanup();
    }
    for effect in effects {
        match effect {
            Effect::Run(run) => run(),
            Effect::Launch { handle, start } => {
                if handle.cancelled.get() {
                    continue;
                }
                let future = start();
                let id = with_composer(|composer| composer.spawn(future));
                if let Some(id) = id {
                    handle.task.set(Some(id));
                }
            }
        }
    }
}

// ----- futures ------------------------------------------------------------------------------

/// Resolves on the next frame with that frame's time, in nanoseconds. Compose's
/// `withFrameNanos`, the clock animations run on.
pub fn with_frame_nanos<R>(on_frame: impl FnOnce(u64) -> R) -> impl Future<Output = R> {
    FrameFuture {
        on_frame: Some(on_frame),
        cell: None,
    }
}

struct FrameFuture<F> {
    on_frame: Option<F>,
    cell: Option<Rc<Cell<Option<u64>>>>,
}

impl<F> Unpin for FrameFuture<F> {}

impl<R, F: FnOnce(u64) -> R> Future for FrameFuture<F> {
    type Output = R;

    fn poll(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<R> {
        if let Some(time) = self.cell.as_ref().and_then(|cell| cell.get()) {
            let on_frame = self.on_frame.take().expect("a frame future resolves once");
            return Poll::Ready(on_frame(time));
        }
        let cell = Rc::clone(self.cell.get_or_insert_with(Rc::default));
        let waker = context.waker().clone();
        let registered = with_composer(|composer| {
            composer.executor.frame_waiters.push((waker, cell));
        });
        if registered.is_some() {
            crate::boundary::request_frame_from_worker();
        }
        Poll::Pending
    }
}

/// Resolves after `duration`. Compose's `delay`.
///
/// The wait happens on a timer thread, so the UI thread does nothing while it lasts and
/// the frame that resumes the future is asked for when it ends. In a browser, which has no
/// threads here, the wait is counted on the frame clock instead.
pub fn delay(duration: Duration) -> impl Future<Output = ()> {
    Delay {
        duration,
        state: DelayState::Unstarted,
    }
}

enum DelayState {
    Unstarted,
    #[cfg(not(target_family = "wasm"))]
    Waiting(std::time::Instant),
    #[cfg(target_family = "wasm")]
    Counting(Option<u64>),
}

struct Delay {
    duration: Duration,
    state: DelayState,
}

impl Future for Delay {
    type Output = ();

    #[cfg(not(target_family = "wasm"))]
    fn poll(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<()> {
        let deadline = match self.state {
            DelayState::Waiting(deadline) => deadline,
            DelayState::Unstarted => {
                let deadline = std::time::Instant::now() + self.duration;
                self.state = DelayState::Waiting(deadline);
                deadline
            }
        };
        if std::time::Instant::now() >= deadline {
            return Poll::Ready(());
        }
        timer::register(deadline, context.waker().clone());
        Poll::Pending
    }

    #[cfg(target_family = "wasm")]
    fn poll(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<()> {
        let now = with_composer(|composer| composer.executor.frame_time).flatten();
        let start = match self.state {
            DelayState::Counting(Some(start)) => Some(start),
            _ => now,
        };
        self.state = DelayState::Counting(start);
        if let (Some(start), Some(now)) = (start, now) {
            if now.saturating_sub(start) >= self.duration.as_nanos() as u64 {
                return Poll::Ready(());
            }
        }
        let cell = Rc::new(Cell::new(None));
        let waker = context.waker().clone();
        with_composer(|composer| composer.executor.frame_waiters.push((waker, cell)));
        crate::boundary::request_frame_from_worker();
        Poll::Pending
    }
}

#[cfg(not(target_family = "wasm"))]
mod timer {
    use std::sync::{Condvar, Mutex, OnceLock};
    use std::task::Waker;
    use std::time::Instant;

    struct Timer {
        waiting: Mutex<Vec<(Instant, Waker)>>,
        changed: Condvar,
    }

    static TIMER: OnceLock<&'static Timer> = OnceLock::new();

    fn timer() -> &'static Timer {
        TIMER.get_or_init(|| {
            let timer: &'static Timer = Box::leak(Box::new(Timer {
                waiting: Mutex::new(Vec::new()),
                changed: Condvar::new(),
            }));
            std::thread::Builder::new()
                .name("compose-rust-timer".to_owned())
                .spawn(move || run(timer))
                .expect("the timer thread could not be started");
            timer
        })
    }

    /// Wakes `waker` at `deadline`. One thread serves every delay in the process.
    pub(super) fn register(deadline: Instant, waker: Waker) {
        let timer = timer();
        timer
            .waiting
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .push((deadline, waker));
        timer.changed.notify_one();
    }

    fn run(timer: &'static Timer) {
        let mut waiting = timer
            .waiting
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        loop {
            let now = Instant::now();
            let mut index = 0;
            while index < waiting.len() {
                if waiting[index].0 <= now {
                    let (_, waker) = waiting.swap_remove(index);
                    waker.wake();
                } else {
                    index += 1;
                }
            }
            let next = waiting.iter().map(|(deadline, _)| *deadline).min();
            waiting = match next {
                Some(deadline) => {
                    timer
                        .changed
                        .wait_timeout(waiting, deadline.saturating_duration_since(now))
                        .unwrap_or_else(|poison| poison.into_inner())
                        .0
                }
                None => timer
                    .changed
                    .wait(waiting)
                    .unwrap_or_else(|poison| poison.into_inner()),
            };
        }
    }
}

/// Runs `work` on a worker thread and resolves with its result on the UI thread.
/// Compose's `withContext(Dispatchers.IO)`: the way domain work leaves the UI thread.
///
/// In a browser, which has no threads here, `work` runs where the future is first polled.
pub fn with_worker<R, W>(work: W) -> impl Future<Output = R>
where
    R: Send + 'static,
    W: FnOnce() -> R + Send + 'static,
{
    WorkerFuture {
        work: Some(work),
        shared: Arc::new(Mutex::new((None, None))),
    }
}

type WorkerShared<R> = Arc<Mutex<(Option<R>, Option<Waker>)>>;

struct WorkerFuture<R, W> {
    work: Option<W>,
    shared: WorkerShared<R>,
}

impl<R, W> Unpin for WorkerFuture<R, W> {}

impl<R, W> Future for WorkerFuture<R, W>
where
    R: Send + 'static,
    W: FnOnce() -> R + Send + 'static,
{
    type Output = R;

    fn poll(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<R> {
        if let Some(work) = self.work.take() {
            #[cfg(target_family = "wasm")]
            {
                return Poll::Ready(work());
            }
            #[cfg(not(target_family = "wasm"))]
            {
                let shared = Arc::clone(&self.shared);
                shared.lock().unwrap_or_else(|poison| poison.into_inner()).1 =
                    Some(context.waker().clone());
                std::thread::Builder::new()
                    .name("compose-rust-worker".to_owned())
                    .spawn(move || {
                        let result = work();
                        let waker = {
                            let mut guard =
                                shared.lock().unwrap_or_else(|poison| poison.into_inner());
                            guard.0 = Some(result);
                            guard.1.take()
                        };
                        if let Some(waker) = waker {
                            waker.wake();
                        }
                    })
                    .expect("a worker thread could not be started");
                return Poll::Pending;
            }
        }
        let mut guard = self
            .shared
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        match guard.0.take() {
            Some(result) => Poll::Ready(result),
            None => {
                guard.1 = Some(context.waker().clone());
                Poll::Pending
            }
        }
    }
}

/// The group an effect's slot belongs to, for tests and for the Host.
#[allow(dead_code)]
pub(crate) fn current_group() -> Option<GroupId> {
    with_composer_if_idle(|composer| composer.current_group()).flatten()
}
