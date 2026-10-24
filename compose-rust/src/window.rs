//! The window's measured size, and the registrations a runtime wakes its readers through.
//!
//! The UI is authored here but measured by the Renderer, so the only way a Rust component
//! can know how wide the window is, is for the Renderer to tell it. The Renderer sends one
//! event when the size class changes and nothing in between, so this module is a single
//! current value plus the list of readers that asked to be told when it changes. The
//! Dioxus adapter's `use_window_size` and `use_node_size` hooks are such readers.

use crate::schema::{WindowHeightClass, WindowSizeClass};
use std::cell::{Cell, RefCell};
use std::sync::Arc;

/// The window's size, as the Renderer last measured it.
///
/// `width_dp` and `height_dp` are density-independent pixels, the same unit gesture
/// coordinates use. They are the measurements taken at the moment the class last changed,
/// not a value that follows every pixel of a drag: a size that changed every layout pass
/// would run the runtime every layout pass.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WindowSize {
    pub width_dp: f32,
    pub height_dp: f32,
    pub class: WindowSizeClass,
    pub height_class: WindowHeightClass,
}

impl WindowSize {
    pub const fn new(
        width_dp: f32,
        height_dp: f32,
        class: WindowSizeClass,
        height_class: WindowHeightClass,
    ) -> Self {
        Self {
            width_dp,
            height_dp,
            class,
            height_class,
        }
    }

    /// Phone-shaped: narrower than 600dp. One column.
    pub fn is_compact(&self) -> bool {
        matches!(self.class, WindowSizeClass::Compact)
    }

    /// Tablet-shaped: 600dp to 840dp.
    pub fn is_medium(&self) -> bool {
        matches!(self.class, WindowSizeClass::Medium)
    }

    /// Desktop-shaped: 840dp and wider. Room for a permanent sidebar.
    pub fn is_expanded(&self) -> bool {
        matches!(self.class, WindowSizeClass::Expanded)
    }

    /// Short: under 480dp tall. A phone lying on its side is the usual one, and a column
    /// of stacked sections is what has to fold there.
    pub fn is_short(&self) -> bool {
        matches!(self.height_class, WindowHeightClass::Compact)
    }

    /// The ordinary height: 480dp to 900dp.
    pub fn is_medium_height(&self) -> bool {
        matches!(self.height_class, WindowHeightClass::Medium)
    }

    /// Tall: 900dp and more. A portrait tablet, or a window someone stretched.
    pub fn is_tall(&self) -> bool {
        matches!(self.height_class, WindowHeightClass::Expanded)
    }
}

impl Default for WindowSize {
    /// What a component sees before the Renderer has measured anything.
    ///
    /// Compact rather than a wider guess: a one-column layout is usable at any width, so
    /// the single render that happens before the first measurement arrives is never
    /// broken, only narrower than it needs to be.
    fn default() -> Self {
        Self::new(
            0.0,
            0.0,
            WindowSizeClass::Compact,
            WindowHeightClass::Compact,
        )
    }
}

struct Subscriber {
    id: u64,
    notify: Arc<dyn Fn() + Send + Sync>,
}

thread_local! {
    /// What each observed node last measured, by the token its screen gave it.
    ///
    /// A map rather than a field, because nothing is in it until a screen asks: a tree
    /// with no observers keeps an empty map and pays for nothing.
    static NODES: RefCell<Vec<(u32, WindowSize)>> = const { RefCell::new(Vec::new()) };
    static NODE_SUBSCRIBERS: RefCell<Vec<(u32, Subscriber)>> = const { RefCell::new(Vec::new()) };
    static CURRENT: Cell<WindowSize> = const { Cell::new(WindowSize {
        width_dp: 0.0,
        height_dp: 0.0,
        class: WindowSizeClass::Compact,
        height_class: WindowHeightClass::Compact,
    }) };
    static SUBSCRIBERS: RefCell<Vec<Subscriber>> = const { RefCell::new(Vec::new()) };
    static NEXT_SUBSCRIBER_ID: Cell<u64> = const { Cell::new(1) };
}

/// The size the Renderer last reported. Available outside a component too.
pub fn window_size() -> WindowSize {
    CURRENT.with(Cell::get)
}

/// Records a new measurement and wakes the components that asked about it.
///
/// Returns whether anything changed. Nothing is allocated here: the subscriber list is
/// walked in place, and waking a component only marks its scope dirty.
pub(crate) fn publish(size: WindowSize) -> bool {
    if CURRENT.with(Cell::get) == size {
        return false;
    }
    CURRENT.with(|current| current.set(size));
    SUBSCRIBERS.with_borrow(|subscribers| {
        for subscriber in subscribers {
            (subscriber.notify)();
        }
    });
    true
}

/// The size a node last measured, or a zero size for one nothing has reported yet.
pub fn node_size(token: u32) -> WindowSize {
    NODES.with_borrow(|nodes| {
        nodes
            .iter()
            .find(|(name, _)| *name == token)
            .map(|(_, size)| *size)
            .unwrap_or_default()
    })
}

/// Records a node's measurement and wakes whoever asked about that one.
///
/// Returns whether anything was woken, so a report about a node nobody is reading costs
/// a lookup and nothing else.
pub(crate) fn publish_node(token: u32, size: WindowSize) -> bool {
    let changed =
        NODES.with_borrow_mut(
            |nodes| match nodes.iter_mut().find(|(name, _)| *name == token) {
                Some(entry) => {
                    if entry.1 == size {
                        return false;
                    }
                    entry.1 = size;
                    true
                }
                None => {
                    nodes.push((token, size));
                    true
                }
            },
        );
    if !changed {
        return false;
    }
    NODE_SUBSCRIBERS.with_borrow(|subscribers| {
        let mut woke = false;
        for (name, subscriber) in subscribers {
            if *name == token {
                (subscriber.notify)();
                woke = true;
            }
        }
        woke
    })
}

/// Clears the measurement and every subscription. Used between tests.
#[doc(hidden)]
pub fn reset_window_size() {
    CURRENT.with(|current| current.set(WindowSize::default()));
    SUBSCRIBERS.with_borrow_mut(Vec::clear);
    NODES.with_borrow_mut(Vec::clear);
    NODE_SUBSCRIBERS.with_borrow_mut(Vec::clear);
}

/// A registration for changes of the window's size class. Dropping it ends the
/// registration, so a runtime keeps it for as long as the reader it wakes is alive.
pub struct WindowSizeSubscription {
    id: u64,
}

/// Registers `notify` to be called each time the window's measured size changes. What
/// `notify` does is the runtime's business: mark a scope dirty, most often.
pub fn subscribe(notify: Arc<dyn Fn() + Send + Sync>) -> WindowSizeSubscription {
    let id = next_subscriber_id();
    SUBSCRIBERS.with_borrow_mut(|subscribers| subscribers.push(Subscriber { id, notify }));
    WindowSizeSubscription { id }
}

fn next_subscriber_id() -> u64 {
    NEXT_SUBSCRIBER_ID.with(|next| {
        let id = next.get();
        next.set(id + 1);
        id
    })
}

impl Drop for WindowSizeSubscription {
    fn drop(&mut self) {
        SUBSCRIBERS.with_borrow_mut(|subscribers| {
            subscribers.retain(|subscriber| subscriber.id != self.id);
        });
    }
}

/// A node whose measured size a reader follows.
///
/// The token is the name the screen gives the node, because a node id belongs to the
/// Renderer and never crosses back as something the Host chose. Attach it with
/// `observe_size` and read the size here. The Dioxus adapter hands one out from its
/// `use_node_size` hook.
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
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct NodeSize {
    token: u32,
    size: WindowSize,
}

impl NodeSize {
    /// The node `token` names, with whatever it last measured.
    pub fn of(token: u32) -> Self {
        Self {
            token,
            size: node_size(token),
        }
    }

    /// The value to hand to `observe_size`.
    pub const fn token(&self) -> i64 {
        self.token as i64
    }

    /// Phone-shaped: narrower than 600dp.
    pub fn is_compact(&self) -> bool {
        self.size.is_compact()
    }

    /// Tablet-shaped.
    pub fn is_medium(&self) -> bool {
        self.size.is_medium()
    }

    /// Desktop-shaped.
    pub fn is_expanded(&self) -> bool {
        self.size.is_expanded()
    }

    /// The measurement itself, zero until the Renderer has reported one.
    pub const fn measured(&self) -> WindowSize {
        self.size
    }
}

thread_local! {
    static NEXT_TOKEN: Cell<u32> = const { Cell::new(1) };
}

/// A fresh name for a node a screen wants measured. Never zero, which is the window's.
pub fn next_node_token() -> u32 {
    NEXT_TOKEN.with(|next| {
        let token = next.get();
        next.set(token + 1);
        token
    })
}

/// A registration for one node's measured size, made with [`subscribe_node`].
///
/// Dropping it ends the registration and forgets what that node measured: the node going
/// out of the tree takes its measurement with it.
pub struct NodeSizeSubscription {
    token: u32,
    id: u64,
}

impl NodeSizeSubscription {
    /// The token this registration follows, the value `observe_size` takes.
    pub fn token(&self) -> u32 {
        self.token
    }
}

/// Registers `notify` to be called each time the node named `token` reports a size in a
/// different class.
pub fn subscribe_node(token: u32, notify: Arc<dyn Fn() + Send + Sync>) -> NodeSizeSubscription {
    let id = next_subscriber_id();
    NODE_SUBSCRIBERS.with_borrow_mut(|subscribers| {
        subscribers.push((token, Subscriber { id, notify }));
    });
    NodeSizeSubscription { token, id }
}

impl Drop for NodeSizeSubscription {
    fn drop(&mut self) {
        // The node going out of the tree takes the subscription with it, which is the
        // third thing this requirement asks for.
        NODE_SUBSCRIBERS.with_borrow_mut(|subscribers| {
            subscribers.retain(|(_, subscriber)| subscriber.id != self.id);
        });
        NODES.with_borrow_mut(|nodes| nodes.retain(|(token, _)| *token != self.token));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fr20_window_size_starts_compact_before_the_renderer_measures_anything() {
        reset_window_size();
        assert_eq!(window_size(), WindowSize::default());
        assert!(window_size().is_compact());
    }

    #[test]
    fn fr20_publishing_the_same_size_twice_wakes_nobody() {
        reset_window_size();
        let size = WindowSize::new(
            700.0,
            800.0,
            WindowSizeClass::Medium,
            WindowHeightClass::Medium,
        );
        assert!(publish(size));
        assert!(!publish(size));
        reset_window_size();
    }
}
