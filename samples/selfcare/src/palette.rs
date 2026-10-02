//! The colours of the reference's check-in, written out.
//!
//! A unified sample draws the design its reference specifies on every platform, so these
//! are literals rather than roles. A role would hand the question to whichever design
//! system happened to be active, and none of the seven answers with a mint or a coral.
//!
//! The values are read off the reference picture rather than chosen: the four feelings
//! are the pastels it fills the panel with, and the page under them is black because the
//! check-in is the screen this sample opens on.

use dioxus_compose::prelude::Color;

/// The page. Black, as the check-in and the worry picker both are.
pub const PAGE: Color = Color::rgb(0x000000);

/// Everything written on the page.
pub const INK: Color = Color::rgb(0xFFFFFF);

/// An answer that was not chosen. Dark enough to sit on the page as a shape rather than
/// as a hole in it, and quiet enough that the chosen one is the only colour in the row.
pub const CHIP: Color = Color::rgb(0x262626);

/// The four feelings, and the ink that reads on each. All four inks are the page's own
/// black: these are pastels, and a pastel carrying white text is the one way to make four
/// quiet colours unreadable.
pub const MINT: Color = Color::rgb(0xA7F3D0);
pub const PINK: Color = Color::rgb(0xF9A8D4);
pub const POWDER: Color = Color::rgb(0xBAE6FD);
pub const CORAL: Color = Color::rgb(0xFCA5A5);

/// What is written on any of the four.
pub const ON_MOOD: Color = Color::rgb(0x000000);
