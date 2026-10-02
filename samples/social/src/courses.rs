//! The courses, the categories, and the scenes drawn on their cards.

use dioxus_compose::prelude::*;

/// The scenes, drawn.
///
/// The reference's cards carry illustrations of people: someone at a drawing board,
/// two people sitting cross-legged, someone asleep in the back of a car. A draw list has
/// lines, arcs, circles and rounded rectangles. What it made of that idea was a disc for a
/// hill, a disc for a sun and two strokes for a frond, and nine cards drawn from it are
/// nine of the same picture with the discs in slightly different places.
///
/// These are original drawings, registered once and drawn by id. An illustration carries
/// its own colours, which is what makes it an illustration, and so does everything around
/// it: this is a unified sample, so the card behind it and the title on it are named in
/// `palette.rs` rather than left to whichever design system is active.
pub static SCENES: [&[u8]; 3] = [
    include_bytes!("../assets/scene-sitting.svg"),
    include_bytes!("../assets/scene-standing.svg"),
    include_bytes!("../assets/scene-resting.svg"),
];

/// Which accent family a card's chrome is drawn from, and which scene it carries.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Palette {
    First,
    Second,
    Third,
}

impl Palette {
    /// The illustration this family's cards carry.
    pub fn scene(self) -> &'static [u8] {
        SCENES[match self {
            Palette::First => 0,
            Palette::Second => 1,
            Palette::Third => 2,
        }]
    }

    /// The three colours a course is drawn in: the fill of its strongest control, the
    /// ground its page sits on, and the ink that reads on both.
    ///
    /// Literals, from the sample's own palette. The reference alternates its illustrations
    /// between a sage and a blush and writes on them in the same deep plum throughout, and
    /// no design system holds any of the three.
    pub fn roles(self) -> (Color, Color, Color) {
        match self {
            Palette::First => (
                crate::palette::SAGE,
                crate::palette::PAGE,
                crate::palette::INK,
            ),
            Palette::Second => (
                crate::palette::BLUSH,
                crate::palette::PAGE,
                crate::palette::INK,
            ),
            Palette::Third => (
                crate::palette::INK,
                crate::palette::PAGE,
                crate::palette::INK,
            ),
        }
    }
}

/// One course.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Course {
    pub id: u32,
    pub title: &'static str,
    pub sessions: u32,
    pub minutes: u32,
    pub palette: Palette,
    /// Which shelf it belongs to.
    pub shelf: Shelf,
}

/// Which part of the app a course shows up in.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Shelf {
    ForYou,
    Meditate,
    Sleep,
}

pub const COURSES: [Course; 9] = [
    Course {
        id: 1,
        title: "Brief beginner meditation",
        sessions: 3,
        minutes: 3,
        palette: Palette::First,
        shelf: Shelf::ForYou,
    },
    Course {
        id: 2,
        title: "Seven days to improve your productivity",
        sessions: 7,
        minutes: 12,
        palette: Palette::Second,
        shelf: Shelf::ForYou,
    },
    Course {
        id: 3,
        title: "Seven days to prepare your day",
        sessions: 7,
        minutes: 9,
        palette: Palette::Third,
        shelf: Shelf::ForYou,
    },
    Course {
        id: 4,
        title: "Basics of meditation",
        sessions: 7,
        minutes: 10,
        palette: Palette::First,
        shelf: Shelf::Meditate,
    },
    Course {
        id: 5,
        title: "Control your breathing",
        sessions: 7,
        minutes: 8,
        palette: Palette::Second,
        shelf: Shelf::Meditate,
    },
    Course {
        id: 6,
        title: "Improve focus",
        sessions: 10,
        minutes: 15,
        palette: Palette::Third,
        shelf: Shelf::Meditate,
    },
    Course {
        id: 7,
        title: "The rear light",
        sessions: 1,
        minutes: 21,
        palette: Palette::First,
        shelf: Shelf::Sleep,
    },
    Course {
        id: 8,
        title: "Imaginary ride",
        sessions: 1,
        minutes: 21,
        palette: Palette::Second,
        shelf: Shelf::Sleep,
    },
    Course {
        id: 9,
        title: "Private planet",
        sessions: 1,
        minutes: 21,
        palette: Palette::Third,
        shelf: Shelf::Sleep,
    },
];

pub fn on(shelf: Shelf) -> Vec<&'static Course> {
    COURSES
        .iter()
        .filter(|course| course.shelf == shelf)
        .collect()
}

pub fn course(id: u32) -> Option<&'static Course> {
    COURSES.iter().find(|found| found.id == id)
}

/// How a course is described under its title.
pub fn sessions_label(course: &Course) -> String {
    let word = if course.sessions == 1 {
        "session"
    } else {
        "sessions"
    };
    format!("{} {word} \u{00b7} {} min", course.sessions, course.minutes)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every shelf has courses on it. An empty shelf reads as an app that failed to load
    /// rather than as a shelf nobody has filled.
    #[test]
    fn every_shelf_has_courses_on_it() {
        for shelf in [Shelf::ForYou, Shelf::Meditate, Shelf::Sleep] {
            assert!(!on(shelf).is_empty(), "{shelf:?} is empty");
        }
    }

    #[test]
    fn a_single_session_course_is_described_in_the_singular() {
        let one = course(7).expect("no course 7");
        assert_eq!(sessions_label(one), "1 session \u{00b7} 21 min");
        let many = course(2).expect("no course 2");
        assert_eq!(sessions_label(many), "7 sessions \u{00b7} 12 min");
    }

    /// Every id is its own. Two courses sharing one is a shelf where opening either gives
    /// you the first.
    #[test]
    fn every_course_has_an_id_of_its_own() {
        for (index, first) in COURSES.iter().enumerate() {
            for second in &COURSES[index + 1..] {
                assert_ne!(first.id, second.id, "{} repeats an id", second.title);
            }
        }
    }

    /// Three families, three illustrations. Two families sharing a picture is a shelf
    /// that reads as one card repeated, which is what nine cards built from one draw list
    /// and a seed looked like.
    #[test]
    fn fr16_no_two_families_share_a_scene() {
        let palettes = [Palette::First, Palette::Second, Palette::Third];
        for (index, first) in palettes.iter().enumerate() {
            for second in &palettes[index + 1..] {
                assert_ne!(
                    first.scene(),
                    second.scene(),
                    "{first:?} and {second:?} are the same drawing"
                );
            }
        }
    }

    /// A drawing that is not a drawing is a card with a hole in it.
    #[test]
    fn fr16_every_scene_is_a_document_with_something_in_it() {
        for scene in SCENES {
            let text = std::str::from_utf8(scene).expect("a scene is not text");
            assert!(text.starts_with("<svg"), "a scene is not an svg document");
            assert!(
                text.contains("viewBox='0 0 320 200'"),
                "a scene with no box to scale from is drawn at its own size in a corner"
            );
        }
    }
}
