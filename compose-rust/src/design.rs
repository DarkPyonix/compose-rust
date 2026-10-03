//! Which design system the Renderer resolved the theme to, and the hook components read
//! it through.
//!
//! The theme names a system or asks for the platform's, and the answer is worked out in
//! the Renderer where the platform is known. Most screens never need it: colour, shape,
//! spacing and type are roles, and a role is the same declaration whatever answers it.
//!
//! What roles cannot carry is a screen that is a different screen. Apple's calculator has
//! no memory row and clears with `AC`; Windows' has one and clears with `C`. Both are
//! calculators and neither is a restyling of the other.
//!
//! So the Renderer reports its answer the way it reports a size class: once, and again
//! only when the answer changes. This module is that value plus the components that asked
//! to be told.

use crate::schema::DesignSystem;
use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::sync::Arc;

struct Subscriber {
    id: u64,
    notify: Arc<dyn Fn() + Send + Sync>,
}

thread_local! {
    /// Material 3 until the Renderer says otherwise, which is the same fallback the
    /// theme's own default carries.
    static CURRENT: Cell<DesignSystem> = const { Cell::new(DesignSystem::Material3) };
    static SUBSCRIBERS: RefCell<Vec<Subscriber>> = const { RefCell::new(Vec::new()) };
    static NEXT_SUBSCRIBER_ID: Cell<u64> = const { Cell::new(1) };
}

/// The system the Renderer last reported. Available outside a component too.
pub fn design_system() -> DesignSystem {
    CURRENT.with(Cell::get)
}

/// Records the Renderer's answer and wakes the components that asked about it.
///
/// Returns whether any component was woken, which is not the same as whether the value
/// changed. A screen that never asks which system is running must not be re-rendered
/// because the Renderer reported one: the answer is still recorded, so a component that
/// mounts later and does ask reads it, but nothing is redrawn for an audience of nobody.
pub(crate) fn publish(system: DesignSystem) -> bool {
    if CURRENT.with(Cell::get) == system {
        return false;
    }
    CURRENT.with(|current| current.set(system));
    SUBSCRIBERS.with_borrow(|subscribers| {
        for subscriber in subscribers {
            (subscriber.notify)();
        }
        !subscribers.is_empty()
    })
}

/// Clears the answer and every subscription. Used between tests.
#[doc(hidden)]
pub fn reset_design_system() {
    CURRENT.with(|current| current.set(DesignSystem::Material3));
    SUBSCRIBERS.with_borrow_mut(Vec::clear);
}

/// A component's registration, dropped with the hook state when the component unmounts.
struct DesignSystemSubscription {
    id: u64,
}

impl DesignSystemSubscription {
    fn new(notify: Arc<dyn Fn() + Send + Sync>) -> Self {
        let id = NEXT_SUBSCRIBER_ID.with(|next| {
            let id = next.get();
            next.set(id + 1);
            id
        });
        SUBSCRIBERS.with_borrow_mut(|subscribers| subscribers.push(Subscriber { id, notify }));
        Self { id }
    }
}

impl Drop for DesignSystemSubscription {
    fn drop(&mut self) {
        SUBSCRIBERS.with_borrow_mut(|subscribers| {
            subscribers.retain(|subscriber| subscriber.id != self.id);
        });
    }
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
        Rc::new(DesignSystemSubscription::new(dioxus_core::schedule_update()))
    });
    design_system()
}
