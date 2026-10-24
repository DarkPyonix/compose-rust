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
use crate::schema::{ColorScheme, DesignSystem, Theme};
use std::cell::{Cell, RefCell};

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
