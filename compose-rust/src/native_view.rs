//! Where a window's platform views are made: the handle the Renderer reports for each
//! window that holds a `NativeView`.
//!
//! A platform view (a web view, a video surface, a map) is made by the application, or by a
//! crate it uses, against the window it will sit in: an `NSView` is made for the window's
//! content view, a child `HWND` for the top-level window. Only the Renderer knows those, so
//! it reports one `NativeViewParent` per window once a `NativeView` is in it, again when the
//! handle or the backing scale changes, and with a zero handle when the window closes. This
//! module keeps the last report of each window for the code that makes the views.
//!
//! The view is then handed over with `ui::NativeView` (or `Batch::attach_native_view`) and
//! stays the application's: the Renderer places it and never destroys it.

use crate::schema::{NativeHandle, NativeParentKind};
use std::cell::{Cell, RefCell};
use std::sync::Arc;

/// What one window reported about the handle its platform views are made against.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct NativeParent {
    /// The `Window` node the report is about, or 0 for the single window.
    pub window: u32,
    /// The handle a view is made against. Zero once the window has closed.
    pub handle: NativeHandle,
    pub kind: NativeParentKind,
    /// The window's backing scale: how many pixels make one point.
    pub scale: f32,
}

struct Subscriber {
    id: u64,
    notify: Arc<dyn Fn() + Send + Sync>,
}

thread_local! {
    static PARENTS: RefCell<Vec<NativeParent>> = const { RefCell::new(Vec::new()) };
    static SUBSCRIBERS: RefCell<Vec<Subscriber>> = const { RefCell::new(Vec::new()) };
    static NEXT_SUBSCRIBER_ID: Cell<u64> = const { Cell::new(1) };
}

/// The last report for `window` (0 for the single window), or `None` before there is one.
/// A window that closed reports a zero handle, which is returned as it came.
pub fn native_parent(window: u32) -> Option<NativeParent> {
    PARENTS.with_borrow(|parents| {
        parents
            .iter()
            .find(|parent| parent.window == window)
            .copied()
    })
}

/// Every window's last report, in the order they first reported.
pub fn native_parents() -> Vec<NativeParent> {
    PARENTS.with_borrow(Clone::clone)
}

/// Records a report and wakes whoever subscribed. Returns whether anyone was woken; a
/// report equal to the last one for its window changes nothing and wakes nobody.
pub(crate) fn publish(parent: NativeParent) -> bool {
    let changed = PARENTS.with_borrow_mut(|parents| {
        match parents
            .iter_mut()
            .find(|known| known.window == parent.window)
        {
            Some(known) if *known == parent => false,
            Some(known) => {
                *known = parent;
                true
            }
            None => {
                parents.push(parent);
                true
            }
        }
    });
    if !changed {
        return false;
    }
    SUBSCRIBERS.with_borrow(|subscribers| {
        for subscriber in subscribers {
            (subscriber.notify)();
        }
        !subscribers.is_empty()
    })
}

/// A registration for parent reports. Dropping it ends the registration.
pub struct NativeParentSubscription {
    id: u64,
}

/// Registers `notify` to be called each time a window reports a different parent.
pub fn subscribe_native_parent(notify: Arc<dyn Fn() + Send + Sync>) -> NativeParentSubscription {
    let id = NEXT_SUBSCRIBER_ID.with(|next| {
        let id = next.get();
        next.set(id + 1);
        id
    });
    SUBSCRIBERS.with_borrow_mut(|subscribers| subscribers.push(Subscriber { id, notify }));
    NativeParentSubscription { id }
}

impl Drop for NativeParentSubscription {
    fn drop(&mut self) {
        SUBSCRIBERS.with_borrow_mut(|subscribers| {
            subscribers.retain(|subscriber| subscriber.id != self.id);
        });
    }
}

/// Forgets every report and subscription. Used when a Host is made and between tests.
#[doc(hidden)]
pub fn reset_native_parents() {
    PARENTS.with_borrow_mut(Vec::clear);
    SUBSCRIBERS.with_borrow_mut(Vec::clear);
}
