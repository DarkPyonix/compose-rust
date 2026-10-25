//! How large the Renderer is drawing, and the application's way to change it.
//!
//! Two things make a screen larger. The reader's operating system has a text size (`os`),
//! and the application has a zoom level (`level`), stepped with Command or Control and
//! plus, minus or zero, the way an editor zooms. The level is a whole number from
//! [`MIN_ZOOM_LEVEL`] to [`MAX_ZOOM_LEVEL`], and each step is a factor of 1.2. What the
//! screen is drawn at is their product, `k = os × 1.2^level`.
//!
//! The Renderer owns both: it reads the system's setting, answers the keys and remembers
//! the level between runs. It tells the Host the result once at start and again whenever
//! it changes, the way a size class is reported, and this module is where that report is
//! kept for the components that read it. An application that lays out text of its own
//! (an HTML island, a measured paragraph) uses `k`; one that only uses native widgets needs
//! nothing here, because those are already drawn at the right size.
//!
//! The application can also set the level, from a menu's "Zoom In" for example. That rides
//! out on the window record, in the batch the call produces.

use std::cell::{Cell, RefCell};
use std::sync::Arc;

/// The smallest application zoom level. Eight steps of 1.2 down is about a quarter size.
pub const MIN_ZOOM_LEVEL: i8 = -8;
/// The largest application zoom level. Eight steps of 1.2 up is about four times the size.
pub const MAX_ZOOM_LEVEL: i8 = 8;

/// The factor one application zoom step multiplies by.
pub const ZOOM_STEP: f32 = 1.2;

/// What the Renderer is drawing at, as it last reported.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Zoom {
    /// The whole factor, `os × app`.
    pub k: f32,
    /// The operating system's text size, where 1 is the default.
    pub os: f32,
    /// The application's zoom level, from [`MIN_ZOOM_LEVEL`] to [`MAX_ZOOM_LEVEL`].
    pub level: i8,
}

impl Zoom {
    /// Nothing enlarged: the size before the Renderer has said anything.
    pub const DEFAULT: Zoom = Zoom {
        k: 1.0,
        os: 1.0,
        level: 0,
    };

    /// The application's share of the factor, `1.2^level`.
    pub fn app(&self) -> f32 {
        app_scale(self.level)
    }
}

impl Default for Zoom {
    fn default() -> Self {
        Self::DEFAULT
    }
}

/// `1.2^level`, the application's share of the factor at a level.
pub fn app_scale(level: i8) -> f32 {
    ZOOM_STEP.powi(i32::from(clamp_level(level)))
}

/// A level kept inside the range the Renderer accepts.
pub fn clamp_level(level: i8) -> i8 {
    level.clamp(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)
}

struct Subscriber {
    id: u64,
    notify: Arc<dyn Fn() + Send + Sync>,
}

thread_local! {
    static CURRENT: Cell<Zoom> = const { Cell::new(Zoom::DEFAULT) };
    /// A level the application asked for since the last batch, not yet written into one.
    static PENDING_LEVEL: Cell<Option<i8>> = const { Cell::new(None) };
    static SUBSCRIBERS: RefCell<Vec<Subscriber>> = const { RefCell::new(Vec::new()) };
    static NEXT_SUBSCRIBER_ID: Cell<u64> = const { Cell::new(1) };
}

/// What the Renderer last reported. Available outside a component too.
pub fn zoom() -> Zoom {
    CURRENT.with(Cell::get)
}

/// Records the Renderer's report and wakes the components that read it.
///
/// Returns whether any component was woken. A report nobody reads is still recorded, so a
/// component that mounts later reads it, but nothing is redrawn for it.
pub(crate) fn publish(zoom: Zoom) -> bool {
    if CURRENT.with(Cell::get) == zoom {
        return false;
    }
    CURRENT.with(|current| current.set(zoom));
    SUBSCRIBERS.with_borrow(|subscribers| {
        for subscriber in subscribers {
            (subscriber.notify)();
        }
        !subscribers.is_empty()
    })
}

/// Asks the Renderer to draw at `level`. It goes out on the window record in the batch the
/// current call produces, and the Renderer answers with a new report.
pub fn set_zoom_level(level: i8) {
    PENDING_LEVEL.with(|pending| pending.set(Some(clamp_level(level))));
}

/// The level asked for since the last batch, if any.
pub(crate) fn take_pending_level() -> Option<i8> {
    PENDING_LEVEL.with(Cell::take)
}

/// Clears the report, anything asked for, and every subscription. Used when a Host is made
/// and between tests.
#[doc(hidden)]
pub fn reset_zoom() {
    CURRENT.with(|current| current.set(Zoom::DEFAULT));
    PENDING_LEVEL.with(|pending| pending.set(None));
    SUBSCRIBERS.with_borrow_mut(Vec::clear);
}

/// A registration for changes to the report. Dropping it ends the registration.
pub struct ZoomSubscription {
    id: u64,
}

/// Registers `notify` to be called each time the Renderer reports a different zoom.
pub fn subscribe(notify: Arc<dyn Fn() + Send + Sync>) -> ZoomSubscription {
    let id = NEXT_SUBSCRIBER_ID.with(|next| {
        let id = next.get();
        next.set(id + 1);
        id
    });
    SUBSCRIBERS.with_borrow_mut(|subscribers| subscribers.push(Subscriber { id, notify }));
    ZoomSubscription { id }
}

impl Drop for ZoomSubscription {
    fn drop(&mut self) {
        SUBSCRIBERS.with_borrow_mut(|subscribers| {
            subscribers.retain(|subscriber| subscriber.id != self.id);
        });
    }
}

/// A handle on the zoom. `Copy`, so it can move into any number of handlers.
#[derive(Clone, Copy, Debug, Default)]
pub struct ZoomHandle {
    _private: (),
}

impl ZoomHandle {
    /// What the Renderer last reported.
    pub fn get(&self) -> Zoom {
        zoom()
    }

    /// Sets the application zoom level, kept inside its range.
    pub fn set_level(&self, level: i8) {
        set_zoom_level(level);
    }

    /// One step larger, as a "Zoom In" menu item does.
    pub fn zoom_in(&self) {
        set_zoom_level(self.current_level().saturating_add(1));
    }

    /// One step smaller.
    pub fn zoom_out(&self) {
        set_zoom_level(self.current_level().saturating_sub(1));
    }

    /// Back to the system's own size.
    pub fn reset(&self) {
        set_zoom_level(0);
    }

    /// The level asked for in this call, or else the one last reported, so two steps in
    /// one handler are two steps.
    fn current_level(&self) -> i8 {
        PENDING_LEVEL
            .with(Cell::get)
            .unwrap_or_else(|| zoom().level)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fr43_app_scale_is_one_point_two_to_the_level() {
        assert_eq!(app_scale(0), 1.0);
        assert!((app_scale(2) - 1.44).abs() < 1e-6);
        assert!((app_scale(-1) - 1.0 / 1.2).abs() < 1e-6);
        assert_eq!(app_scale(20), app_scale(MAX_ZOOM_LEVEL));
    }

    #[test]
    fn fr43_two_steps_in_one_handler_are_two_steps() {
        reset_zoom();
        let handle = ZoomHandle::default();
        handle.zoom_in();
        handle.zoom_in();
        assert_eq!(take_pending_level(), Some(2));
        assert_eq!(take_pending_level(), None);
        handle.set_level(40);
        assert_eq!(take_pending_level(), Some(MAX_ZOOM_LEVEL));
        reset_zoom();
    }

    #[test]
    fn fr43_a_report_wakes_only_on_change() {
        reset_zoom();
        let woken = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let counter = woken.clone();
        let _subscription = subscribe(Arc::new(move || {
            counter.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        }));
        let report = Zoom {
            k: 1.8,
            os: 1.25,
            level: 2,
        };
        assert!(publish(report));
        assert!(!publish(report), "the same report twice is one change");
        assert_eq!(woken.load(std::sync::atomic::Ordering::SeqCst), 1);
        assert_eq!(zoom(), report);
        reset_zoom();
    }
}
