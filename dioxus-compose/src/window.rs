//! The window's measured size, and the hook components read it through.
//!
//! The UI is authored here but measured by the Renderer, so the only way a Rust component
//! can know how wide the window is, is for the Renderer to tell it. The Renderer sends one
//! event when the size class changes and nothing in between, so this module is a single
//! current value plus the list of components that asked to be told when it changes.

use crate::schema::{WindowHeightClass, WindowSizeClass};
use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::sync::Arc;

/// The window's size, as the Renderer last measured it.
///
/// `width_dp` and `height_dp` are density-independent pixels, the same unit gesture
/// coordinates use. They are the measurements taken at the moment the class last changed,
/// not a value that follows every pixel of a drag: a size that changed every layout pass
/// would run the VirtualDom every layout pass.
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

/// A component's registration, dropped with the hook state when the component unmounts.
struct WindowSizeSubscription {
    id: u64,
}

impl WindowSizeSubscription {
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

impl Drop for WindowSizeSubscription {
    fn drop(&mut self) {
        SUBSCRIBERS.with_borrow_mut(|subscribers| {
            subscribers.retain(|subscriber| subscriber.id != self.id);
        });
    }
}

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
/// A node whose measured size this component follows.
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
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct NodeSize {
    token: u32,
    size: WindowSize,
}

impl NodeSize {
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

/// Follows one node's size, and re-renders this component when its class changes.
/// A fresh name for a node a screen wants measured.
pub(crate) fn next_node_token() -> u32 {
    NEXT_TOKEN.with(|next| {
        let token = next.get();
        next.set(token + 1);
        token
    })
}

/// A node's measurement under its name, as the composable runtime hands it out.
pub(crate) fn node_size_of(token: u32, size: WindowSize) -> NodeSize {
    NodeSize { token, size }
}

/// Forgets what a node measured, once nothing will ask about it again.
pub(crate) fn forget_node(token: u32) {
    NODES.with_borrow_mut(|nodes| nodes.retain(|(name, _)| *name != token));
}

pub fn use_node_size() -> NodeSize {
    let token = dioxus_core::use_hook(|| {
        let token = NEXT_TOKEN.with(|next| {
            let token = next.get();
            next.set(token + 1);
            token
        });
        Rc::new(NodeSizeSubscription::new(
            token,
            dioxus_core::schedule_update(),
        ))
    });
    NodeSize {
        token: token.token,
        size: node_size(token.token),
    }
}

/// A component's registration for one node, dropped with its hook state.
struct NodeSizeSubscription {
    token: u32,
    id: u64,
}

impl NodeSizeSubscription {
    fn new(token: u32, notify: Arc<dyn Fn() + Send + Sync>) -> Self {
        let id = NEXT_SUBSCRIBER_ID.with(|next| {
            let id = next.get();
            next.set(id + 1);
            id
        });
        NODE_SUBSCRIBERS.with_borrow_mut(|subscribers| {
            subscribers.push((token, Subscriber { id, notify }));
        });
        Self { token, id }
    }
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

pub fn use_window_size() -> WindowSize {
    // Rc, because hook state has to be `Clone` and the registration must not be
    // duplicated: dropping the last handle with the component's hook state is what
    // removes the subscription.
    dioxus_core::use_hook(|| Rc::new(WindowSizeSubscription::new(dioxus_core::schedule_update())));
    window_size()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::boundary::Host;
    use crate::prelude::*;
    use crate::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
    use crate::schema::{EventPayload, PropertyKind};
    use std::sync::atomic::{AtomicUsize, Ordering};

    static RESPONSIVE_RENDERS: AtomicUsize = AtomicUsize::new(0);
    static SIBLING_RENDERS: AtomicUsize = AtomicUsize::new(0);

    #[component]
    fn Responsive() -> Element {
        RESPONSIVE_RENDERS.fetch_add(1, Ordering::SeqCst);
        let window = use_window_size();
        let label = if window.is_expanded() {
            "sidebar"
        } else if window.is_medium() {
            "two columns"
        } else {
            "one column"
        };
        rsx! { Text { text: label } }
    }

    #[component]
    fn ShortOrTall() -> Element {
        let window = use_window_size();
        let label = if window.is_tall() {
            "tall"
        } else if window.is_medium_height() {
            "ordinary"
        } else {
            "short"
        };
        rsx! { Text { text: label } }
    }

    fn height_app() -> Element {
        rsx! { Column { ShortOrTall {} } }
    }

    #[component]
    fn Sibling() -> Element {
        SIBLING_RENDERS.fetch_add(1, Ordering::SeqCst);
        rsx! { Text { text: "fixed" } }
    }

    fn responsive_app() -> Element {
        rsx! {
            Column {
                Responsive {}
                Sibling {}
            }
        }
    }

    fn resize(host: &mut Host, width_dp: f32) -> Vec<String> {
        resize_to(host, width_dp, 800.0)
    }

    fn resize_to(host: &mut Host, width_dp: f32, height_dp: f32) -> Vec<String> {
        let event = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: EventPayload::WindowSizeChanged {
                width_dp,
                height_dp,
                class: WindowSizeClass::from_width_dp(width_dp),
                height_class: WindowHeightClass::from_height_dp(height_dp),
            },
        };
        let (batch, _) = host.dispatch(event).unwrap();
        decode_batch(batch)
            .unwrap()
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetProp {
                    property: PropertyKind::Text,
                    value: PropertyValue::String(value),
                    ..
                } => Some((*value).to_owned()),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn fr20_crossing_a_boundary_rerenders_the_hook_and_resizing_within_a_class_does_not() {
        reset_window_size();
        RESPONSIVE_RENDERS.store(0, Ordering::SeqCst);
        SIBLING_RENDERS.store(0, Ordering::SeqCst);
        let mut host = Host::new(responsive_app);
        host.rebuild().unwrap();
        assert_eq!(RESPONSIVE_RENDERS.load(Ordering::SeqCst), 1);
        assert_eq!(SIBLING_RENDERS.load(Ordering::SeqCst), 1);

        // Crossing 600dp: the hook's component re-renders once, its sibling not at all.
        assert_eq!(resize(&mut host, 700.0), vec!["two columns".to_owned()]);
        assert_eq!(RESPONSIVE_RENDERS.load(Ordering::SeqCst), 2);
        assert_eq!(SIBLING_RENDERS.load(Ordering::SeqCst), 1);

        // A report that does not change the class changes nothing. The Renderer does not
        // send one, and a Host that receives one anyway must not run the VirtualDom for it.
        assert!(resize(&mut host, 700.0).is_empty());
        assert_eq!(RESPONSIVE_RENDERS.load(Ordering::SeqCst), 2);

        assert_eq!(resize(&mut host, 900.0), vec!["sidebar".to_owned()]);
        assert_eq!(RESPONSIVE_RENDERS.load(Ordering::SeqCst), 3);
        assert_eq!(SIBLING_RENDERS.load(Ordering::SeqCst), 1);
        reset_window_size();
    }

    #[test]
    fn fr20_crossing_a_height_boundary_rerenders_and_resizing_within_one_does_not() {
        reset_window_size();
        let mut host = Host::new(height_app);
        host.rebuild().unwrap();

        // 300dp tall is the class the Host already holds, so growing to 479dp inside it
        // changes nothing.
        assert!(resize_to(&mut host, 400.0, 479.0).is_empty());

        // 480dp crosses into the ordinary height, and 900dp into the tall one. One
        // mutation each, and nothing for the step in between.
        assert_eq!(
            resize_to(&mut host, 400.0, 480.0),
            vec!["ordinary".to_owned()]
        );
        assert!(resize_to(&mut host, 400.0, 899.0).is_empty());
        assert_eq!(resize_to(&mut host, 400.0, 900.0), vec!["tall".to_owned()]);
        reset_window_size();
    }

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
