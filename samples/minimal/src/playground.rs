//! What the playground is made of: the three groups and the marks drawn on the hero.

use dioxus_compose::prelude::*;
use dioxus_compose::{DrawList, DrawListBuilder};

/// The three groups of the playground, which is what the tab strip selects.
///
/// Three rather than one long scroll, because the point of a playground is to be able to
/// look at one family of things at a time. Controls are the widgets a person operates,
/// surfaces are the containers those widgets sit in, and colour is the table underneath
/// both of them.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Group {
    Controls,
    Surfaces,
    Colour,
}

impl Group {
    pub const STRIP: [Group; 3] = [Group::Controls, Group::Surfaces, Group::Colour];

    /// Six letters each, deliberately.
    ///
    /// A segmented control gives every segment the same width and a button inside one
    /// carries its own horizontal padding, so on a phone an eight letter label breaks
    /// across two lines and the strip grows a second row. These are the shortest words
    /// that still name the group.
    pub fn label(self) -> &'static str {
        match self {
            Group::Controls => "Parts",
            Group::Surfaces => "Panels",
            Group::Colour => "Colour",
        }
    }

    pub fn index(self) -> usize {
        Self::STRIP
            .iter()
            .position(|found| *found == self)
            .unwrap_or(0)
    }

    /// One line saying what this group is for, under the strip.
    pub fn caption(self) -> &'static str {
        match self {
            Group::Controls => "Everything a person can operate.",
            Group::Surfaces => "The containers those controls sit in.",
            Group::Colour => "Nine fills, each with the ink that reads on it.",
        }
    }
}

/// The mark at the top of the screen: one solid disc with a second overlapping it.
///
/// The reference draws the second disc as a gradient, and a gradient is not something this
/// vocabulary can say: a fill is one colour, deliberately, because a gradient does not fit
/// the two words a modifier has and would need a resource to cross the boundary. So the
/// overlap is said with two roles instead, which is the part of the idea that survives
/// into a design system: a dense mark and a quiet one, in whatever the active system calls
/// those.
///
/// `size` is the square the canvas was given, so the mark scales with the window instead
/// of the drawing carrying its own idea of how big it is.
pub fn hero_marks(size: f32) -> DrawList {
    let radius = size * 0.32;
    let middle = size / 2.0;
    DrawListBuilder::with_capacity(2, 0)
        .circle(
            Paint::Role(ColorRole::OutlineVariant),
            middle + radius * 0.55,
            middle,
            radius,
            0.0,
        )
        .circle(
            Paint::Role(ColorRole::OnBackground),
            middle - radius * 0.55,
            middle,
            radius,
            0.0,
        )
        .build()
}

/// The nine fills the colour group shows, each with the ink that is promised to read on
/// it.
///
/// Ordered as three accents, three accent containers, then the three neutral layers, so
/// reading down the column is reading the table in the order a screen is built: what marks
/// a thing, what a panel of it is filled with, and what the page under both is.
pub const SWATCHES: [(&str, ColorRole, ColorRole); 9] = [
    ("Primary", ColorRole::Primary, ColorRole::OnPrimary),
    ("Secondary", ColorRole::Secondary, ColorRole::OnSecondary),
    ("Tertiary", ColorRole::Tertiary, ColorRole::OnTertiary),
    (
        "Primary container",
        ColorRole::PrimaryContainer,
        ColorRole::OnPrimaryContainer,
    ),
    (
        "Secondary container",
        ColorRole::SecondaryContainer,
        ColorRole::OnSecondaryContainer,
    ),
    (
        "Tertiary container",
        ColorRole::TertiaryContainer,
        ColorRole::OnTertiaryContainer,
    ),
    ("Surface", ColorRole::Surface, ColorRole::OnSurface),
    (
        "Surface variant",
        ColorRole::SurfaceVariant,
        ColorRole::OnSurfaceVariant,
    ),
    ("Error", ColorRole::Error, ColorRole::OnError),
];

/// The nine rungs of the type ladder, in the order they descend.
pub const LADDER: [(&str, TypeRole); 9] = [
    ("Display", TypeRole::Display),
    ("Headline", TypeRole::Headline),
    ("Title", TypeRole::Title),
    ("Subtitle", TypeRole::Subtitle),
    ("Body", TypeRole::Body),
    ("Body strong", TypeRole::BodyStrong),
    ("Label", TypeRole::Label),
    ("Caption", TypeRole::Caption),
    ("Mono", TypeRole::Mono),
];
