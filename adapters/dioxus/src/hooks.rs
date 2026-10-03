//! The hooks a component reads the Renderer's reports through.
//!
//! The values are compose-rust's: the window's size class, a node's measured size, the
//! design system the theme resolved to, the notification permission. Each is recorded by
//! the Host when the Renderer reports it, and each keeps a list of readers to wake. A hook
//! here is one such reader, registered for as long as the component holds its hook state,
//! woken by marking the component dirty.

use compose_rust::notification::NotificationActivation;
use compose_rust::schema::{DesignSystem, NotificationPermission};
use compose_rust::theme::ThemeHandle;
use compose_rust::window::{NodeSize, WindowSize};
use std::rc::Rc;

/// Reads the window's size class inside a component, and re-renders it when the class
/// changes.
///
/// ```ignore
/// let window = use_window_size();
/// rsx! {
///     if window.is_expanded() {
///         Row { Sidebar {} Content {} }
///     } else {
///         Content {}
///     }
/// }
/// ```
///
/// Only components that call this are woken, and only when the class actually changes.
/// Resizing inside one class re-renders nothing.
pub fn use_window_size() -> WindowSize {
    // Rc, because hook state has to be `Clone` and the registration must not be
    // duplicated: dropping the last handle with the component's hook state is what
    // removes the subscription.
    dioxus_core::use_hook(|| {
        Rc::new(compose_rust::window::subscribe(
            dioxus_core::schedule_update(),
        ))
    });
    compose_rust::window::window_size()
}

/// Follows one node's size, and re-renders this component when its class changes.
///
/// The token is the name the screen gives the node, because a node id belongs to the
/// Renderer and never crosses back as something the Host chose. Attach it with
/// `observe_size` and read the size here.
///
/// ```ignore
/// let panel = use_node_size();
/// rsx! {
///     Card {
///         observe_size: panel.token(),
///         if panel.is_expanded() { Row { Left {} Right {} } } else { Left {} }
///     }
/// }
/// ```
///
/// A component that never calls this costs nothing: the modifier is the only thing that
/// makes the Renderer measure, and the modifier comes from here.
pub fn use_node_size() -> NodeSize {
    let subscription = dioxus_core::use_hook(|| {
        let token = compose_rust::window::next_node_token();
        Rc::new(compose_rust::window::subscribe_node(
            token,
            dioxus_core::schedule_update(),
        ))
    });
    NodeSize::of(subscription.token())
}

/// Reads the resolved design system inside a component, and re-renders it when the answer
/// changes.
///
/// ```ignore
/// let design = use_design_system();
/// if design.is_apple() {
///     // AC, and no memory row
/// } else {
///     // C, and a memory row
/// }
/// ```
///
/// **This is not how an application chooses a design system.** Choosing is
/// `Theme::unified`; this reads an answer already given. And it is not a way to paint
/// colours by hand: what a role resolves to is still the design system's, and reaching
/// for a literal because this told you which system is running is the thing the role
/// vocabulary exists to prevent.
pub fn use_design_system() -> DesignSystem {
    // Rc for the same reason the window size hook uses one: hook state has to be `Clone`,
    // and the registration must be dropped exactly once, with the last handle.
    dioxus_core::use_hook(|| {
        Rc::new(compose_rust::design::subscribe(
            dioxus_core::schedule_update(),
        ))
    });
    compose_rust::design::design_system()
}

/// The application's theme, to read and to change while it runs.
///
/// ```ignore
/// let theme = use_theme();
/// rsx! {
///     Button {
///         text: "Ember",
///         on_click: move |_| theme.set_palette(
///             Palette::new().with(ColorRole::Primary, Color::rgb(0xE8590C), Color::rgb(0xFF8A4C)),
///         ),
///     }
/// }
/// ```
///
/// A change is one `SetTheme` record in the batch the handler produces. Turning dark
/// because the platform did is not this: that is the Renderer's, and the Host never hears
/// about it.
pub fn use_theme() -> ThemeHandle {
    ThemeHandle::default()
}

/// Reads whether notifications may be shown, and re-renders when that changes.
///
/// ```ignore
/// match use_notification_permission() {
///     NotificationPermission::Denied => rsx! { Text { text: "Notifications are off in Settings" } },
///     _ => rsx! { Switch { checked: wants, onchange: move |_| request_notification_permission() } },
/// }
/// ```
///
/// What to show instead of a notification nobody will see is the application's decision.
/// The Renderer never swaps one for a message inside the window: the moment a notification
/// is for is the moment nobody is looking at the window.
pub fn use_notification_permission() -> NotificationPermission {
    dioxus_core::use_hook(|| {
        Rc::new(compose_rust::notification::subscribe_permission(
            dioxus_core::schedule_update(),
        ))
    });
    compose_rust::notification::notification_permission()
}

/// Runs `handler` when the user presses a notification this application posted.
///
/// One hook for the whole application rather than a callback per notification. A
/// notification stays in the notification centre for hours, long after the component that
/// posted it is gone, so what it carries back is the key it was posted under, which the
/// application can read at any time:
///
/// ```ignore
/// use_notification_activated(move |activation| {
///     if let Some(id) = activation.key.strip_prefix("session/") {
///         open_session(id);
///     }
/// });
/// ```
///
/// With no component holding this hook, an activation is dropped.
pub fn use_notification_activated(handler: impl FnMut(NotificationActivation) + 'static) {
    // A Callback rather than the closure itself, so the handler runs inside the scope of the
    // component that registered it and can write that component's signals.
    let callback = dioxus_hooks::use_callback(handler);
    dioxus_core::use_hook(|| {
        Rc::new(compose_rust::notification::on_activation(Rc::new(
            move |activation: NotificationActivation| callback.call(activation),
        )))
    });
}

/// Whether the platform asks for reduced motion, and a re-render when that changes.
///
/// The Renderer reports it once after start and again only when the setting changes.
/// Nothing is reduced on the application's behalf: an animation is played as it was
/// described, so a screen that should hold still when asked reads this and asks for less.
pub fn use_reduced_motion() -> compose_rust::ReducedMotion {
    dioxus_core::use_hook(|| {
        Rc::new(compose_rust::animation::subscribe_reduced_motion(
            dioxus_core::schedule_update(),
        ))
    });
    compose_rust::reduced_motion()
}

/// Runs `handler` for every event the Renderer reports about an animation it is playing.
///
/// Only the kinds an animation asked for are ever reported, so an application that asks
/// for none hears nothing and pays nothing while its animations play.
pub fn use_animation_event(handler: impl FnMut(compose_rust::AnimationMoment) + 'static) {
    let callback = dioxus_hooks::use_callback(handler);
    dioxus_core::use_hook(|| {
        Rc::new(compose_rust::animation::on_animation_event(Rc::new(
            move |moment: compose_rust::AnimationMoment| callback.call(moment),
        )))
    });
}

/// A value the Renderer animates to whenever it changes: what
/// [`animate_float_as_state`] returns. [`value`](Self::value) is the target, because the
/// value in between is the Renderer's and is never sent back.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Animated<T> {
    pub target: T,
    pub spec: compose_rust::AnimationSpec,
}

impl<T: Copy> Animated<T> {
    pub fn value(&self) -> T {
        self.target
    }
}

/// An opacity the Renderer animates to whenever `target` changes, given to an
/// `AbsoluteBox` as `animated_alpha`. A change is one `StartAnimation` and the Renderer
/// plays it on its own clock: this component is not rendered again while it plays.
pub fn animate_float_as_state(target: f32, spec: compose_rust::AnimationSpec) -> Animated<f32> {
    Animated { target, spec }
}

/// A background the Renderer animates to whenever `target` changes, given to an
/// `AbsoluteBox` as `animated_background`.
pub fn animate_color_as_state(
    target: compose_rust::Paint,
    spec: compose_rust::AnimationSpec,
) -> Animated<compose_rust::Paint> {
    Animated { target, spec }
}

/// A translation in dp the Renderer animates to whenever `target` changes, given to an
/// `AbsoluteBox` as `animated_offset`. Drawn as a transform, so the layout does not move.
pub fn animate_offset_as_state(
    target: (f32, f32),
    spec: compose_rust::AnimationSpec,
) -> Animated<(f32, f32)> {
    Animated { target, spec }
}
