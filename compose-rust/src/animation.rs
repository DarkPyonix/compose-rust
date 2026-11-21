//! Animations the Renderer plays on its own frame clock, and the platform's reduced motion
//! setting.
//!
//! An animation is described once and played to its end by the Renderer. Nothing crosses
//! the boundary while it plays unless it asked for events, and the value it shows at any
//! moment is never sent back: animation state is the Renderer's.
//!
//! [`start_animation`] and [`control_animation`] queue the record for the next batch, the
//! way a message or a notification is queued, so they can be called from an event handler,
//! a component or a worker thread alike. A runtime that writes the tree itself can write
//! [`Mutation::StartAnimation`] into its batch directly; the result is the same record.
//!
//! [`Mutation::StartAnimation`]: crate::protocol::Mutation::StartAnimation

use crate::protocol::Mutation;
use crate::schema::{
    AnimatedProperty, Animation, AnimationControl, AnimationEventKind, ReducedMotion,
};
use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

/// One moment the Renderer reported about an animation it is playing.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AnimationMoment {
    /// The node the animation plays on.
    pub node_id: u32,
    pub animation_id: u32,
    pub kind: AnimationEventKind,
    pub property: AnimatedProperty,
    pub slot: u8,
    pub iteration: u32,
    /// Active time without the delay, in ms, which is CSS's `elapsedTime`.
    pub elapsed_ms: f32,
    /// The frame it happened in, on the Renderer's frame clock. For `Ready`, the start
    /// time the animation was given.
    pub time_nanos: u64,
}

enum Command {
    Start(Animation<'static>),
    Control {
        node_id: u32,
        animation_id: u32,
        property: AnimatedProperty,
        slot: u8,
        op: AnimationControl,
        at_time_nanos: u64,
    },
}

/// Records waiting for the next batch. Shared across threads, because a worker may start
/// an animation as well as a handler can.
static QUEUE: Mutex<Vec<Command>> = Mutex::new(Vec::new());

/// Whether the queue has anything in it, read without the lock. Almost every batch finds
/// nothing, and then looking costs one atomic load.
static PENDING: AtomicBool = AtomicBool::new(false);

fn queue() -> MutexGuard<'static, Vec<Command>> {
    QUEUE.lock().unwrap_or_else(|poison| poison.into_inner())
}

fn submit(command: Command) {
    queue().push(command);
    PENDING.store(true, Ordering::Release);
    // Inside a Host call the batch that call produces carries it.
    if !crate::boundary::in_host_call() {
        crate::boundary::request_frame_from_worker();
    }
}

/// Asks the Renderer to play `animation` to its end. It goes out with the next batch.
pub fn start_animation(animation: Animation<'static>) {
    submit(Command::Start(animation));
}

/// Pauses, resumes or cancels the animation playing under `(node_id, property, slot)`,
/// if it is still the one called `animation_id`. It goes out with the next batch.
pub fn control_animation(
    node_id: u32,
    animation_id: u32,
    property: AnimatedProperty,
    slot: u8,
    op: AnimationControl,
    at_time_nanos: u64,
) {
    submit(Command::Control {
        node_id,
        animation_id,
        property,
        slot,
        op,
        at_time_nanos,
    });
}

/// Hands every queued record to `emit` and empties the queue, reusing its buffer.
pub(crate) fn drain(mut emit: impl FnMut(Mutation<'_>)) {
    if !PENDING.swap(false, Ordering::AcqRel) {
        return;
    }
    let mut taken = std::mem::take(&mut *queue());
    for command in taken.drain(..) {
        emit(match command {
            Command::Start(animation) => Mutation::StartAnimation(animation),
            Command::Control {
                node_id,
                animation_id,
                property,
                slot,
                op,
                at_time_nanos,
            } => Mutation::ControlAnimation {
                node_id,
                animation_id,
                property,
                slot,
                op,
                at_time_nanos,
            },
        });
    }
    let mut guard = queue();
    if guard.is_empty() {
        *guard = taken;
    }
}

struct Subscriber {
    id: u64,
    notify: Arc<dyn Fn() + Send + Sync>,
}

type MomentHandler = Rc<dyn Fn(AnimationMoment)>;

thread_local! {
    /// Unknown until the Renderer says, which it does once after start.
    static REDUCED_MOTION: Cell<ReducedMotion> = const { Cell::new(ReducedMotion::Unknown) };
    static SUBSCRIBERS: RefCell<Vec<Subscriber>> = const { RefCell::new(Vec::new()) };
    static MOMENT_HANDLERS: RefCell<Vec<(u64, MomentHandler)>> = const { RefCell::new(Vec::new()) };
    static NEXT_ID: Cell<u64> = const { Cell::new(1) };
}

fn next_id() -> u64 {
    NEXT_ID.with(|next| {
        let id = next.get();
        next.set(id + 1);
        id
    })
}

/// Whether the platform asks for reduced motion, as the Renderer last reported it.
///
/// What to do about it is the application's decision, the way a CSS media query is the
/// page's: the Renderer plays every animation it is sent as it was described.
pub fn reduced_motion() -> ReducedMotion {
    REDUCED_MOTION.with(Cell::get)
}

/// Records the Renderer's answer and wakes the readers. Returns whether any was woken.
pub(crate) fn publish_reduced_motion(state: ReducedMotion) -> bool {
    if REDUCED_MOTION.with(Cell::get) == state {
        return false;
    }
    REDUCED_MOTION.with(|current| current.set(state));
    SUBSCRIBERS.with_borrow(|subscribers| {
        for subscriber in subscribers {
            (subscriber.notify)();
        }
        !subscribers.is_empty()
    })
}

/// A registration for changes of the reduced motion setting, ended by dropping it.
pub struct ReducedMotionSubscription {
    id: u64,
}

/// Registers `notify` to be called each time the setting changes. A runtime's
/// `use_reduced_motion` holds one for as long as the reader it wakes is alive.
pub fn subscribe_reduced_motion(notify: Arc<dyn Fn() + Send + Sync>) -> ReducedMotionSubscription {
    let id = next_id();
    SUBSCRIBERS.with_borrow_mut(|subscribers| subscribers.push(Subscriber { id, notify }));
    ReducedMotionSubscription { id }
}

impl Drop for ReducedMotionSubscription {
    fn drop(&mut self) {
        SUBSCRIBERS.with_borrow_mut(|subscribers| subscribers.retain(|s| s.id != self.id));
    }
}

/// A registration for animation events, ended by dropping it.
pub struct AnimationEventSubscription {
    id: u64,
}

/// Calls `handler` with every animation event the Renderer reports. Only the kinds an
/// animation asked for are ever reported.
pub fn on_animation_event(handler: Rc<dyn Fn(AnimationMoment)>) -> AnimationEventSubscription {
    let id = next_id();
    MOMENT_HANDLERS.with_borrow_mut(|handlers| handlers.push((id, handler)));
    AnimationEventSubscription { id }
}

impl Drop for AnimationEventSubscription {
    fn drop(&mut self) {
        MOMENT_HANDLERS.with_borrow_mut(|handlers| handlers.retain(|(id, _)| *id != self.id));
    }
}

/// Hands one moment to every registered handler. Returns whether any was registered.
pub(crate) fn deliver(moment: AnimationMoment) -> bool {
    // Copied out first, because a handler may register or drop one.
    let handlers: Vec<MomentHandler> =
        MOMENT_HANDLERS.with_borrow(|handlers| handlers.iter().map(|(_, h)| h.clone()).collect());
    for handler in &handlers {
        handler(moment);
    }
    !handlers.is_empty()
}

/// Forgets the setting, the readers, the handlers and anything still queued. Used between
/// tests.
#[doc(hidden)]
pub fn reset_animations() {
    queue().clear();
    PENDING.store(false, Ordering::Release);
    REDUCED_MOTION.with(|current| current.set(ReducedMotion::Unknown));
    SUBSCRIBERS.with_borrow_mut(Vec::clear);
    MOMENT_HANDLERS.with_borrow_mut(Vec::clear);
}

/// How long a change takes and along which curve.
///
/// A role is the default way to say this (`MotionRole`), and the design system answers
/// it; this is the escape hatch for a screen whose author has already decided, as CSS
/// does. Without one, [`AnimationSpec::STANDARD`] is used: the standard motion of
/// Material 3, the length and curve the design systems' own standard role is built on.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AnimationSpec {
    pub duration_ms: f32,
    pub delay_ms: f32,
    pub timing: crate::schema::Timing,
}

impl AnimationSpec {
    /// 300 ms along cubic-bezier(0.2, 0, 0, 1).
    pub const STANDARD: Self = Self {
        duration_ms: 300.0,
        delay_ms: 0.0,
        timing: crate::schema::Timing::CubicBezier {
            x1: 0.2,
            y1: 0.0,
            x2: 0.0,
            y2: 1.0,
        },
    };

    /// A change of `duration_ms` along `timing`, starting at once.
    pub const fn tween(duration_ms: f32, timing: crate::schema::Timing) -> Self {
        Self {
            duration_ms,
            delay_ms: 0.0,
            timing,
        }
    }

    /// Compose's `spring(dampingRatio, stiffness)`, starting at once.
    ///
    /// The length is the time the spring takes to settle, from [`spring_settle_ms`], so
    /// the change moves at the speed Compose's spring of the same stiffness and damping
    /// would. Below a damping ratio of 1 it overshoots its target and comes back. Both
    /// numbers have to be finite and positive; anything else is refused by the Renderer
    /// as a protocol error.
    pub fn spring(stiffness: f32, damping_ratio: f32) -> Self {
        Self {
            duration_ms: spring_settle_ms(stiffness, damping_ratio),
            delay_ms: 0.0,
            timing: crate::schema::Timing::Spring {
                stiffness,
                damping_ratio,
            },
        }
    }
}

/// How long, in ms, a spring of mass 1 that starts at rest at 0 takes to come within
/// 0.001 of 1 and stay there.
///
/// This is Compose's `estimateAnimationDurationMillis` with a visibility threshold of
/// 0.001, followed step for step, so that a `Timing::Spring` stretched over this length
/// moves the way Compose's own spring does. The Renderer computes the same number from
/// the same two values, which is why it never travels on the wire. Unlike Compose's
/// estimate, the fraction of a millisecond is kept.
///
/// A stiffness or damping ratio that is not finite and positive has no settle time; the
/// result is then not a meaningful length.
pub fn spring_settle_ms(stiffness: f32, damping_ratio: f32) -> f32 {
    // Compose measures the distance in units of the threshold: the spring starts a
    // thousand of them away and has settled once it stays within one.
    const THRESHOLD: f64 = 0.001;
    let stiffness = f64::from(stiffness);
    let damping_ratio = f64::from(damping_ratio);
    let p0 = 1.0 / THRESHOLD;
    let v0 = 0.0;
    let delta = 1.0;

    // The roots of x^2 + c x + k = 0, the first of which may be complex.
    let damping = 2.0 * damping_ratio * stiffness.sqrt();
    let partial = damping * damping - 4.0 * stiffness;
    let (partial_real, partial_imaginary) = if partial < 0.0 {
        (0.0, partial.abs().sqrt())
    } else {
        (partial.sqrt(), 0.0)
    };
    let first_real = (-damping + partial_real) * 0.5;
    let first_imaginary = partial_imaginary * 0.5;
    let second_real = (-damping - partial_real) * 0.5;

    let seconds = if damping_ratio > 1.0 {
        spring_over_damped(first_real, second_real, p0, v0, delta)
    } else if damping_ratio < 1.0 {
        spring_under_damped(first_real, first_imaginary, p0, v0, delta)
    } else {
        spring_critically_damped(first_real, p0, v0, delta)
    };
    (seconds * 1000.0) as f32
}

fn spring_under_damped(r: f64, imaginary: f64, p0: f64, v0: f64, delta: f64) -> f64 {
    let c1 = p0;
    let c2 = (v0 - r * c1) / imaginary;
    let c = (c1 * c1 + c2 * c2).sqrt();
    (delta / c).ln() / r
}

fn spring_critically_damped(r: f64, p0: f64, v0: f64, delta: f64) -> f64 {
    let c1 = p0;
    let c2 = v0 - r * c1;

    // The first guess is the later of c1 e^(rt) = delta and c2 t e^(rt) = delta, the
    // second solved with a few rounds of Lambert's W.
    let t1 = (delta / c1).abs().ln() / r;
    let t2 = {
        let guess = (delta / c2).abs().ln();
        let mut t = guess;
        for _ in 0..=5 {
            t = guess - (t / r).abs().ln();
        }
        t
    } / r;
    let mut t = if !t1.is_finite() {
        t2
    } else if !t2.is_finite() {
        t1
    } else {
        t1.max(t2)
    };

    // Where the curve bends matters when it bends after the start.
    let t_inflection = -(r * c1 + c2) / (r * c2);
    let x_inflection = c1 * (r * t_inflection).exp() + c2 * t_inflection * (r * t_inflection).exp();
    let signed_delta = if t_inflection.is_nan() || t_inflection <= 0.0 {
        -delta
    } else if -x_inflection < delta {
        if c2 < 0.0 && c1 > 0.0 {
            t = 0.0;
        }
        -delta
    } else {
        // Three crossings of the threshold: start between the change of concavity and the
        // inflection, so that Newton's method lands on the last one.
        t = -(2.0 / r) - (c1 / c2);
        delta
    };

    let mut step = f64::MAX;
    let mut iterations = 0;
    while step > 0.001 && iterations < 100 {
        iterations += 1;
        let last = t;
        let value = (c1 + c2 * t) * (r * t).exp() + signed_delta;
        let slope = (c2 * (r * t + 1.0) + c1 * r) * (r * t).exp();
        t -= value / slope;
        step = (last - t).abs();
    }
    t
}

fn spring_over_damped(r1: f64, r2: f64, p0: f64, v0: f64, delta: f64) -> f64 {
    let c2 = (r1 * p0 - v0) / (r1 - r2);
    let c1 = p0 - c2;

    // The first guess is the later of c1 e^(r1 t) = delta and c2 e^(r2 t) = delta.
    let t1 = (delta / c1).abs().ln() / r1;
    let t2 = (delta / c2).abs().ln() / r2;
    let mut t = if !t1.is_finite() {
        t2
    } else if !t2.is_finite() {
        t1
    } else {
        t1.max(t2)
    };

    let t_inflection = ((c1 * r1) / (-c2 * r2)).ln() / (r2 - r1);
    let x_inflection = c1 * (r1 * t_inflection).exp() + c2 * (r2 * t_inflection).exp();
    let signed_delta = if t_inflection.is_nan() || t_inflection <= 0.0 {
        -delta
    } else if -x_inflection < delta {
        if c2 > 0.0 && c1 < 0.0 {
            t = 0.0;
        }
        -delta
    } else {
        t = (-(c2 * r2 * r2) / (c1 * r1 * r1)).ln() / (r1 - r2);
        delta
    };

    let slope_at = |t: f64| c1 * r1 * (r1 * t).exp() + c2 * r2 * (r2 * t).exp();
    if slope_at(t).abs() < 0.0001 {
        return t;
    }
    let mut step = f64::MAX;
    let mut iterations = 0;
    while step > 0.001 && iterations < 100 {
        iterations += 1;
        let last = t;
        let value = c1 * (r1 * t).exp() + c2 * (r2 * t).exp() + signed_delta;
        t -= value / slope_at(t);
        step = (last - t).abs();
    }
    t
}

impl Default for AnimationSpec {
    fn default() -> Self {
        Self::STANDARD
    }
}

/// A transition to `to`: from whatever the node shows the frame it starts, to `to`, once,
/// along `spec`, in slot 0, with no events. What a CSS transition and an
/// `animate_*_as_state` both are.
pub fn transition(
    node_id: u32,
    animation_id: u32,
    property: AnimatedProperty,
    to: crate::schema::KeyframeValue,
    spec: AnimationSpec,
) -> Animation<'static> {
    use crate::schema::{
        AnimationEvents, ColorInterpolation, FillMode, Keyframe, PlayState, PlaybackDirection,
        Timing,
    };
    let colour = matches!(
        property,
        AnimatedProperty::Color | AnimatedProperty::Background | AnimatedProperty::BorderColor
    );
    let transform = property == AnimatedProperty::Transform;
    Animation {
        node_id,
        animation_id,
        property,
        slot: 0,
        direction: PlaybackDirection::Normal,
        fill: FillMode::None,
        play_state: PlayState::Running,
        interpolation: colour.then_some(ColorInterpolation::SrgbPremultiplied),
        start_time_nanos: 0,
        delay_ms: spec.delay_ms,
        duration_ms: spec.duration_ms,
        iterations: 1.0,
        origin_x: if transform { 0.5 } else { 0.0 },
        origin_y: if transform { 0.5 } else { 0.0 },
        events: AnimationEvents::NONE,
        keyframes: std::borrow::Cow::Owned(vec![
            Keyframe {
                offset: 0.0,
                timing: spec.timing,
                from_presented: true,
                value: to.clone(),
            },
            Keyframe {
                offset: 1.0,
                timing: Timing::Linear,
                from_presented: false,
                value: to,
            },
        ]),
    }
}

/// What Compose's `graphicsLayer` takes, drawn without moving the layout: a rotation in
/// degrees, a scale, a translation in dp, the origin they turn about as a fraction of the
/// node's size, and an opacity for the whole group.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct GraphicsLayer {
    pub rotation_z: f32,
    pub scale_x: f32,
    pub scale_y: f32,
    pub translation_x: f32,
    pub translation_y: f32,
    pub transform_origin: (f32, f32),
    pub alpha: f32,
}

impl Default for GraphicsLayer {
    fn default() -> Self {
        Self {
            rotation_z: 0.0,
            scale_x: 1.0,
            scale_y: 1.0,
            translation_x: 0.0,
            translation_y: 0.0,
            transform_origin: (0.5, 0.5),
            alpha: 1.0,
        }
    }
}

impl GraphicsLayer {
    /// The `Transform` this layer draws: scaled, then turned, about its origin, then moved,
    /// which is the order a Compose layer applies them in.
    pub fn transform(&self) -> crate::schema::Modifier {
        let radians = self.rotation_z.to_radians();
        let (sin, cos) = radians.sin_cos();
        crate::schema::Modifier::Transform {
            a: self.scale_x * cos,
            b: self.scale_x * sin,
            c: -self.scale_y * sin,
            d: self.scale_y * cos,
            e: self.translation_x,
            f: self.translation_y,
            origin_x: self.transform_origin.0,
            origin_y: self.transform_origin.1,
        }
    }

    /// The `Alpha` this layer fades its group with.
    pub fn alpha(&self) -> crate::schema::Modifier {
        crate::schema::Modifier::Alpha(self.alpha)
    }
}

/// A value the Renderer animates to whenever it changes: what [`animate_float_as_state`]
/// and its siblings return. [`value`](Self::value) is the target, because the value in
/// between is the Renderer's and is never sent back.
///
/// Given to a Modifier (`animated_alpha`, `animated_background`, `animated_offset`). The
/// first value a node is given is where it starts; every later one is a change, written
/// as one `StartAnimation` that the Renderer plays on its own clock, so nothing is composed
/// again while it plays.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Animated<T> {
    pub target: T,
    pub spec: AnimationSpec,
}

impl<T: Copy> Animated<T> {
    /// The target, which is also what the screen shows once the change has played.
    pub fn value(&self) -> T {
        self.target
    }
}

/// An opacity the Renderer animates to whenever `target` changes. Compose's
/// `animateFloatAsState`, for the one float a node draws without laying out again.
pub fn animate_float_as_state(target: f32, spec: AnimationSpec) -> Animated<f32> {
    Animated { target, spec }
}

/// A background the Renderer animates to whenever `target` changes.
pub fn animate_color_as_state(
    target: crate::schema::Paint,
    spec: AnimationSpec,
) -> Animated<crate::schema::Paint> {
    Animated { target, spec }
}

/// A translation in dp the Renderer animates to whenever `target` changes. Drawn as a
/// transform, so the layout does not move.
pub fn animate_offset_as_state(target: (f32, f32), spec: AnimationSpec) -> Animated<(f32, f32)> {
    Animated { target, spec }
}

/// How an animated value travels as a widget attribute, which carries scalars and bytes
/// only: the target's words, then the spec. Read back by [`read_animated`].
pub(crate) fn animated_bytes(target: &[u32], spec: &AnimationSpec) -> Vec<u8> {
    use crate::schema::Timing;
    let mut out = Vec::with_capacity(4 * target.len() + 28);
    out.push(target.len() as u8);
    for word in target {
        out.extend_from_slice(&word.to_le_bytes());
    }
    out.extend_from_slice(&spec.duration_ms.to_le_bytes());
    out.extend_from_slice(&spec.delay_ms.to_le_bytes());
    match spec.timing {
        Timing::Linear => out.push(1),
        Timing::CubicBezier { x1, y1, x2, y2 } => {
            out.push(2);
            for value in [x1, y1, x2, y2] {
                out.extend_from_slice(&value.to_le_bytes());
            }
        }
        Timing::Steps { count, position } => {
            out.push(3);
            out.extend_from_slice(&count.to_le_bytes());
            out.extend_from_slice(&u16::from(position).to_le_bytes());
        }
        Timing::Spring {
            stiffness,
            damping_ratio,
        } => {
            out.push(4);
            out.extend_from_slice(&stiffness.to_le_bytes());
            out.extend_from_slice(&damping_ratio.to_le_bytes());
        }
    }
    out
}

/// The target's words and the spec [`animated_bytes`] wrote, or `None` for bytes it did
/// not write.
pub(crate) fn read_animated(bytes: &[u8]) -> Option<(Vec<u32>, AnimationSpec)> {
    use crate::schema::{StepPosition, Timing};
    let word = |at: usize| -> Option<u32> {
        Some(u32::from_le_bytes(bytes.get(at..at + 4)?.try_into().ok()?))
    };
    let float = |at: usize| word(at).map(f32::from_bits);
    let count = usize::from(*bytes.first()?);
    let target = (0..count)
        .map(|index| word(1 + 4 * index))
        .collect::<Option<Vec<_>>>()?;
    let at = 1 + 4 * count;
    let duration_ms = float(at)?;
    let delay_ms = float(at + 4)?;
    let timing = match *bytes.get(at + 8)? {
        1 => Timing::Linear,
        2 => Timing::CubicBezier {
            x1: float(at + 9)?,
            y1: float(at + 13)?,
            x2: float(at + 17)?,
            y2: float(at + 21)?,
        },
        3 => Timing::Steps {
            count: word(at + 9)?,
            position: StepPosition::try_from(u16::from_le_bytes(
                bytes.get(at + 13..at + 15)?.try_into().ok()?,
            ))
            .ok()?,
        },
        4 => Timing::Spring {
            stiffness: float(at + 9)?,
            damping_ratio: float(at + 13)?,
        },
        _ => return None,
    };
    Some((
        target,
        AnimationSpec {
            duration_ms,
            delay_ms,
            timing,
        },
    ))
}
