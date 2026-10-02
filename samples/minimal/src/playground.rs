//! What the playground is made of: the three groups, the marks drawn on the hero, and the
//! colours the colour group lays out.

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
            Group::Colour => "The nine colours of the sheet, each with the ink that reads on it.",
        }
    }
}

/// The mark at the top of the screen: one solid disc with a second overlapping it.
///
/// The reference draws the second disc as a gradient. Here it is the sheet's pale grey,
/// which is the part of the idea a flat fill can carry: a dense mark and a quiet one.
///
/// `size` is the square the canvas was given, so the mark scales with the window instead
/// of the drawing carrying its own idea of how big it is.
pub fn hero_marks(size: f32) -> DrawList {
    let radius = size * 0.32;
    let middle = size / 2.0;
    DrawListBuilder::with_capacity(2, 0)
        .circle(
            Paint::Literal(crate::palette::PALE),
            middle + radius * 0.55,
            middle,
            radius,
            0.0,
        )
        .circle(
            Paint::Literal(crate::palette::INK),
            middle - radius * 0.55,
            middle,
            radius,
            0.0,
        )
        .build()
}

/// The nine fills the sheet is made of, each with the ink that reads on it.
///
/// This group used to show the design system's accents and their containers, which is a
/// picture of whichever system is running rather than of this design: the sheet has no
/// accent and no container in it. What it does have is paper, three greys, ink, and the
/// two colours a delete and a switch carry, so those are what the colour group shows.
pub const SWATCHES: [(&str, Color, Color); 9] = [
    ("Page", crate::palette::PAGE, crate::palette::INK),
    ("Panel", crate::palette::PANEL, crate::palette::INK),
    ("Recess", crate::palette::RECESS, crate::palette::INK),
    ("Grey card", crate::palette::GREY, crate::palette::ON_DARK),
    ("Dark card", crate::palette::DARK, crate::palette::ON_DARK),
    ("Ink", crate::palette::INK, crate::palette::ON_DARK),
    ("Muted", crate::palette::MUTED, crate::palette::ON_DARK),
    ("Delete", crate::palette::ALERT, crate::palette::ON_DARK),
    ("Switched on", crate::palette::ON, crate::palette::INK),
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
