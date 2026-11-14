//! Changing the theme while the application runs, and the palettes that come with it.
//!
//! `LaunchBuilder::with_theme` settles the theme once, before anything is drawn, and a
//! `const` palette rides on it as a `&'static` reference. An application that lets the
//! reader pick a design system, a scheme or a brand colour at run time needs to send a new
//! `SetTheme`, and a palette built at run time has no `'static` home of its own.
//!
//! [`ThemeHandle`] is that path, and the Dioxus adapter's `use_theme` hook hands one out.
//! Setting a theme queues it and it rides out on the batch the current call produces, the
//! way a message does: one record, applied by the Renderer to the whole tree. A palette handed over by value is owned here, so application code never
//! leaks anything to satisfy the lifetime.

use crate::palette::Palette;
use crate::schema::{ColorScheme, DesignSystem, SystemColorScheme, Theme};
use std::cell::{Cell, RefCell};
use std::sync::Arc;

thread_local! {
    /// The theme the Host is running with, as the application last asked for it.
    static CURRENT: Cell<Theme> = const { Cell::new(Theme::unified(DesignSystem::Material3)) };
    /// A theme asked for since the last batch, not yet written into one.
    static PENDING: Cell<Option<Theme>> = const { Cell::new(None) };
    /// Every distinct palette handed over at run time, kept for the life of the process.
    ///
    /// Interned by value, so switching between the same few palettes any number of times
    /// holds each of them once. A palette has to outlive every batch and every Renderer
    /// frame that might still be resolving against it, and the theme that names it is
    /// `Copy`, so there is no point at which one could be known to be unused.
    static OWNED: RefCell<Vec<&'static Palette>> = const { RefCell::new(Vec::new()) };
}

/// The theme the application is running with: what it launched with, or what it last set.
pub fn current_theme() -> Theme {
    PENDING
        .with(Cell::get)
        .unwrap_or_else(|| CURRENT.with(Cell::get))
}

/// Replaces the whole theme. The Renderer receives it in the batch this call produces.
///
/// The theme is one record. Nothing in the tree is resent, because every node reads the
/// theme from the Renderer's side.
pub fn set_theme(theme: Theme) {
    #[cfg(debug_assertions)]
    if theme.palette != current_theme().palette {
        for line in crate::boundary::palette_report(&theme) {
            eprintln!("{line}");
        }
    }
    PENDING.with(|pending| pending.set(Some(theme)));
}

/// A palette made at run time, given a home that lives as long as the program.
///
/// The same palette handed over twice is the same home.
pub fn own_palette(palette: Palette) -> &'static Palette {
    OWNED.with_borrow_mut(|owned| {
        if let Some(existing) = owned.iter().find(|existing| ***existing == palette) {
            return *existing;
        }
        let home: &'static Palette = Box::leak(Box::new(palette));
        owned.push(home);
        home
    })
}

/// How many distinct run time palettes are held. Exposed for tests.
#[doc(hidden)]
pub fn owned_palette_count() -> usize {
    OWNED.with_borrow(Vec::len)
}

/// Records the theme a Host starts with and forgets anything queued for the one before.
pub(crate) fn install(theme: Theme) {
    CURRENT.with(|current| current.set(theme));
    PENDING.with(|pending| pending.set(None));
}

/// The theme queued since the last batch, if any. It becomes the current one.
pub(crate) fn take_pending() -> Option<Theme> {
    let theme = PENDING.with(Cell::take)?;
    CURRENT.with(|current| current.set(theme));
    Some(theme)
}

/// A handle on the running theme. `Copy`, so it can move into any number of handlers.
#[derive(Clone, Copy, Debug, Default)]
pub struct ThemeHandle {
    _private: (),
}

impl ThemeHandle {
    /// The theme in force, including one set earlier in this same call.
    pub fn get(&self) -> Theme {
        current_theme()
    }

    /// Replaces the whole theme.
    pub fn set(&self, theme: Theme) {
        set_theme(theme);
    }

    /// Lays a palette made at run time over the current theme. The palette is owned by
    /// this module; nothing has to be leaked by the caller.
    pub fn set_palette(&self, palette: Palette) {
        let theme = current_theme().with_palette(own_palette(palette));
        set_theme(theme);
    }

    /// Takes the application's palette away, leaving the design system's colours.
    pub fn clear_palette(&self) {
        let mut theme = current_theme();
        theme.palette = None;
        set_theme(theme);
    }

    /// Picks light, dark, or following the system, keeping everything else.
    pub fn set_color_scheme(&self, scheme: ColorScheme) {
        set_theme(current_theme().with_color_scheme(scheme));
    }
}

struct SchemeSubscriber {
    id: u64,
    notify: Arc<dyn Fn() + Send + Sync>,
}

thread_local! {
    /// The system appearance as the Renderer last reported it. Unknown until the first
    /// report, which the Renderer makes before its first frame.
    static SYSTEM_SCHEME: Cell<Option<SystemColorScheme>> = const { Cell::new(None) };
    static SCHEME_SUBSCRIBERS: RefCell<Vec<SchemeSubscriber>> = const { RefCell::new(Vec::new()) };
    static NEXT_SCHEME_SUBSCRIBER: Cell<u64> = const { Cell::new(1) };
}

/// Whether the system appearance is light or dark, as the Renderer last reported it, or
/// `None` before its first report.
///
/// This is the system's setting whatever the theme asks for. An application that draws
/// something of its own, such as an HTML page answering `prefers-color-scheme`, reads it
/// here; native widgets already follow the theme without it.
pub fn system_color_scheme() -> Option<SystemColorScheme> {
    SYSTEM_SCHEME.with(Cell::get)
}

/// The scheme the theme is drawn in: the theme's own choice when it names one, and the
/// system's when it follows the system. `None` only while it follows a system that has not
/// been reported yet.
pub fn resolved_color_scheme() -> Option<SystemColorScheme> {
    match current_theme().color_scheme {
        ColorScheme::Light => Some(SystemColorScheme::Light),
        ColorScheme::Dark => Some(SystemColorScheme::Dark),
        ColorScheme::FollowSystem => system_color_scheme(),
    }
}

/// Records the Renderer's report and wakes the readers. Returns whether any was woken.
///
/// A report of the value already held wakes nobody, so a reader is never told the same
/// thing twice even if the Renderer were to repeat itself.
pub(crate) fn publish_system_color_scheme(scheme: SystemColorScheme) -> bool {
    if SYSTEM_SCHEME.with(Cell::get) == Some(scheme) {
        return false;
    }
    SYSTEM_SCHEME.with(|current| current.set(Some(scheme)));
    // Copied out first, because a reader may subscribe or drop its subscription.
    let readers: Vec<Arc<dyn Fn() + Send + Sync>> = SCHEME_SUBSCRIBERS
        .with_borrow(|subscribers| subscribers.iter().map(|s| s.notify.clone()).collect());
    for notify in &readers {
        notify();
    }
    !readers.is_empty()
}

/// A registration for changes of the system appearance, ended by dropping it.
pub struct SystemColorSchemeSubscription {
    id: u64,
}

/// Registers `notify` to be called each time the system appearance changes. A runtime's
/// hook for the scheme holds one for as long as the reader it wakes is alive.
pub fn subscribe_system_color_scheme(
    notify: Arc<dyn Fn() + Send + Sync>,
) -> SystemColorSchemeSubscription {
    let id = NEXT_SCHEME_SUBSCRIBER.with(|next| {
        let id = next.get();
        next.set(id + 1);
        id
    });
    SCHEME_SUBSCRIBERS
        .with_borrow_mut(|subscribers| subscribers.push(SchemeSubscriber { id, notify }));
    SystemColorSchemeSubscription { id }
}

impl Drop for SystemColorSchemeSubscription {
    fn drop(&mut self) {
        SCHEME_SUBSCRIBERS.with_borrow_mut(|subscribers| subscribers.retain(|s| s.id != self.id));
    }
}

/// Forgets the reported appearance and every reader. Used between tests.
#[doc(hidden)]
pub fn reset_system_color_scheme() {
    SYSTEM_SCHEME.with(|current| current.set(None));
    SCHEME_SUBSCRIBERS.with_borrow_mut(Vec::clear);
}
