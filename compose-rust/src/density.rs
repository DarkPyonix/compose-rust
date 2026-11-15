//! How many device pixels one dp of a window covers, as the Renderer reports it.
//!
//! The Host lays out in dp and never needs the number to place a box. It needs it for the
//! things that are about pixels: a page's `devicePixelRatio` and its resolution media
//! queries, which image of a set to load, and snapping a hairline to the pixel grid. The
//! Renderer says what it is once before the first frame and again whenever it changes, as
//! when a window moves to a screen of another scale. Until it has, the value is 1.0.
//!
//! It is separate from the zoom. A move between screens changes the density and leaves
//! the zoom alone, and a zoom step does the reverse, so each has its own report.
//!
//! Anything derived from the density (a cache of measurements, a chosen image) is stale
//! once it changes. [`subscribe`] is how its owner hears: the callback runs when a
//! different value arrives and never for a repeat of the same one.

use std::cell::{Cell, RefCell};
use std::sync::Arc;

/// The density before the Renderer has reported one: one device pixel to the dp.
pub const DEFAULT_DENSITY: f32 = 1.0;

struct Subscriber {
    id: u64,
    notify: Arc<dyn Fn() + Send + Sync>,
}

thread_local! {
    static CURRENT: Cell<f32> = const { Cell::new(DEFAULT_DENSITY) };
    static SUBSCRIBERS: RefCell<Vec<Subscriber>> = const { RefCell::new(Vec::new()) };
    /// The density of each window of a multi-window application, by the id of its
    /// `Window` node. Two windows can sit on screens of different scales.
    static WINDOW_DENSITIES: RefCell<Vec<(u32, f32)>> = const { RefCell::new(Vec::new()) };
    static WINDOW_SUBSCRIBERS: RefCell<Vec<(u32, Subscriber)>> = const { RefCell::new(Vec::new()) };
    static NEXT_SUBSCRIBER_ID: Cell<u64> = const { Cell::new(1) };
}

/// What the Renderer last reported, or 1.0 before it has. Available outside a component
/// too.
///
/// In a multi-window application it is the density of the window the caller is in.
pub fn density() -> f32 {
    match crate::windows::current_window_id() {
        0 => CURRENT.with(Cell::get),
        window => density_of(window),
    }
}

/// The density of one window, by the id of its `Window` node, or 1.0 for one that has
/// not reported yet.
pub fn density_of(window: u32) -> f32 {
    WINDOW_DENSITIES.with_borrow(|densities| {
        densities
            .iter()
            .find(|(id, _)| *id == window)
            .map_or(DEFAULT_DENSITY, |(_, scale)| *scale)
    })
}

/// Records the Renderer's report and wakes whoever reads it.
///
/// Returns whether anyone was woken. A report nobody reads is still recorded, so a reader
/// that arrives later sees it, but nothing is redrawn for it.
pub(crate) fn publish(scale: f32) -> bool {
    if CURRENT.with(Cell::get) == scale {
        return false;
    }
    CURRENT.with(|current| current.set(scale));
    SUBSCRIBERS.with_borrow(|subscribers| {
        for subscriber in subscribers {
            (subscriber.notify)();
        }
        !subscribers.is_empty()
    })
}

/// Records one window's report and wakes whoever reads it for that window. Returns
/// whether anyone was woken.
///
/// A window's first report of 1.0 changes nothing a reader could have seen, because 1.0
/// is what it read before, so it wakes nobody either.
pub(crate) fn publish_window(window: u32, scale: f32) -> bool {
    let changed = WINDOW_DENSITIES.with_borrow_mut(|densities| {
        match densities.iter_mut().find(|(id, _)| *id == window) {
            Some(entry) => {
                if entry.1 == scale {
                    return false;
                }
                entry.1 = scale;
                true
            }
            None => {
                densities.push((window, scale));
                scale != DEFAULT_DENSITY
            }
        }
    });
    if !changed {
        return false;
    }
    WINDOW_SUBSCRIBERS.with_borrow(|subscribers| {
        let mut woke = false;
        for (id, subscriber) in subscribers {
            if *id == window {
                (subscriber.notify)();
                woke = true;
            }
        }
        woke
    })
}

/// How many windows' densities and subscriptions are held.
pub(crate) fn window_state_len() -> usize {
    WINDOW_DENSITIES.with_borrow(Vec::len) + WINDOW_SUBSCRIBERS.with_borrow(Vec::len)
}

/// Forgets a window that left the tree, and what it reported.
pub(crate) fn forget_window(window: u32) {
    WINDOW_DENSITIES.with_borrow_mut(|densities| densities.retain(|(id, _)| *id != window));
}

/// Clears the report and every subscription. Used when a Host is made and between tests.
#[doc(hidden)]
pub fn reset_density() {
    CURRENT.with(|current| current.set(DEFAULT_DENSITY));
    SUBSCRIBERS.with_borrow_mut(Vec::clear);
    WINDOW_DENSITIES.with_borrow_mut(Vec::clear);
    WINDOW_SUBSCRIBERS.with_borrow_mut(Vec::clear);
}

fn next_subscriber_id() -> u64 {
    NEXT_SUBSCRIBER_ID.with(|next| {
        let id = next.get();
        next.set(id + 1);
        id
    })
}

/// A registration for changes to the density. Dropping it ends the registration.
pub struct DensitySubscription {
    id: u64,
}

/// Registers `notify` to be called each time the Renderer reports a different density.
pub fn subscribe(notify: Arc<dyn Fn() + Send + Sync>) -> DensitySubscription {
    let id = next_subscriber_id();
    SUBSCRIBERS.with_borrow_mut(|subscribers| subscribers.push(Subscriber { id, notify }));
    DensitySubscription { id }
}

impl Drop for DensitySubscription {
    fn drop(&mut self) {
        SUBSCRIBERS.with_borrow_mut(|subscribers| {
            subscribers.retain(|subscriber| subscriber.id != self.id);
        });
    }
}

/// A registration for one window's density. Dropping it ends the registration.
pub struct WindowDensitySubscription {
    id: u64,
}

/// Registers `notify` to be called each time the window `window` reports a different
/// density.
pub fn subscribe_window(
    window: u32,
    notify: Arc<dyn Fn() + Send + Sync>,
) -> WindowDensitySubscription {
    let id = next_subscriber_id();
    WINDOW_SUBSCRIBERS.with_borrow_mut(|subscribers| {
        subscribers.push((window, Subscriber { id, notify }));
    });
    WindowDensitySubscription { id }
}

impl Drop for WindowDensitySubscription {
    fn drop(&mut self) {
        WINDOW_SUBSCRIBERS.with_borrow_mut(|subscribers| {
            subscribers.retain(|(_, subscriber)| subscriber.id != self.id);
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[test]
    fn fr43_density_defaults_to_one_before_the_first_event() {
        reset_density();
        assert_eq!(density(), 1.0);
        assert_eq!(density_of(7), 1.0);
    }

    #[test]
    fn fr43_a_density_report_wakes_only_on_change() {
        reset_density();
        let woken = Arc::new(AtomicUsize::new(0));
        let counter = woken.clone();
        let _subscription = subscribe(Arc::new(move || {
            counter.fetch_add(1, Ordering::SeqCst);
        }));
        assert!(!publish(1.0), "1.0 is what was read before any report");
        assert!(publish(2.0));
        assert!(!publish(2.0), "the same report twice is one change");
        assert_eq!(woken.load(Ordering::SeqCst), 1);
        assert_eq!(density(), 2.0);
        reset_density();
    }

    #[test]
    fn fr43_a_window_density_wakes_only_that_window() {
        reset_density();
        let woken = Arc::new(AtomicUsize::new(0));
        let counter = woken.clone();
        let _subscription = subscribe_window(
            4,
            Arc::new(move || {
                counter.fetch_add(1, Ordering::SeqCst);
            }),
        );
        assert!(!publish_window(5, 2.0), "nobody reads window 5");
        assert!(!publish_window(4, 1.0), "1.0 is what window 4 read before");
        assert!(publish_window(4, 1.5));
        assert!(!publish_window(4, 1.5));
        assert_eq!(woken.load(Ordering::SeqCst), 1);
        assert_eq!((density_of(4), density_of(5)), (1.5, 2.0));
        forget_window(4);
        assert_eq!(density_of(4), 1.0);
        reset_density();
    }
}
