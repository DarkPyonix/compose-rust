//! The courses, the categories, and the scenes drawn on their cards.

use compose_rust::ui::Paint;

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
    pub fn roles(self) -> (Paint, Paint, Paint) {
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
    /// How large it is drawn on that shelf.
    pub slot: Slot,
    /// What it is, which is what the line beside its title says.
    pub kind: Kind,
    /// The heading the shelf puts above it, if it starts a section.
    pub heading: Option<&'static str>,
}

/// Which part of the app a course shows up in.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Shelf {
    ForYou,
    Meditate,
    Sleep,
}

/// How large a course is drawn: the picture across the top of a page, one of two to a
/// row, or a whole row to itself.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Slot {
    Hero,
    Tile,
    Wide,
}

/// What a card is, which decides what its line of meta says.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Kind {
    /// A course of sessions: "7 sessions".
    Course,
    /// The one course picked for today: "3 min · meditation of the day".
    OfTheDay,
    /// A category holding several courses: "10 courses".
    Category,
    /// A story to fall asleep to: "21 min".
    Story,
}

/// The shelves in the order the reference draws them, each card where it draws it.
///
/// Each course is on its shelf once. The shelf used to take its hero, then everything
/// after the hero as the grid, then the last course again as the wide card, so the third
/// course of every shelf was on screen twice.
pub const COURSES: [Course; 15] = [
    Course {
        id: 1,
        title: "Brief Beginner Meditation",
        sessions: 3,
        minutes: 3,
        palette: Palette::Third,
        shelf: Shelf::ForYou,
        slot: Slot::Hero,
        kind: Kind::OfTheDay,
        heading: None,
    },
    Course {
        id: 2,
        title: "7 days to improve your productivity",
        sessions: 7,
        minutes: 12,
        palette: Palette::First,
        shelf: Shelf::ForYou,
        slot: Slot::Tile,
        kind: Kind::Course,
        heading: Some("Courses just for you"),
    },
    Course {
        id: 3,
        title: "7 days to learn how to prepare your day",
        sessions: 7,
        minutes: 9,
        palette: Palette::Second,
        shelf: Shelf::ForYou,
        slot: Slot::Tile,
        kind: Kind::Course,
        heading: None,
    },
    Course {
        id: 4,
        title: "Favorite meditation",
        sessions: 10,
        minutes: 10,
        palette: Palette::Second,
        shelf: Shelf::ForYou,
        slot: Slot::Wide,
        kind: Kind::Category,
        heading: Some("Recommended category"),
    },
    Course {
        id: 5,
        title: "Basics of meditation",
        sessions: 7,
        minutes: 10,
        palette: Palette::Third,
        shelf: Shelf::Meditate,
        slot: Slot::Tile,
        kind: Kind::Course,
        heading: None,
    },
    Course {
        id: 6,
        title: "Control your breathing",
        sessions: 7,
        minutes: 8,
        palette: Palette::Second,
        shelf: Shelf::Meditate,
        slot: Slot::Tile,
        kind: Kind::Course,
        heading: None,
    },
    Course {
        id: 7,
        title: "Improve focus",
        sessions: 10,
        minutes: 15,
        palette: Palette::First,
        shelf: Shelf::Meditate,
        slot: Slot::Wide,
        kind: Kind::Category,
        heading: Some("Recommended for you"),
    },
    Course {
        id: 8,
        title: "Improve productivity",
        sessions: 7,
        minutes: 12,
        palette: Palette::First,
        shelf: Shelf::Meditate,
        slot: Slot::Tile,
        kind: Kind::Course,
        heading: None,
    },
    Course {
        id: 9,
        title: "Prepare your day",
        sessions: 7,
        minutes: 9,
        palette: Palette::Second,
        shelf: Shelf::Meditate,
        slot: Slot::Tile,
        kind: Kind::Course,
        heading: None,
    },
    Course {
        id: 10,
        title: "Walking meditation",
        sessions: 10,
        minutes: 12,
        palette: Palette::Third,
        shelf: Shelf::Meditate,
        slot: Slot::Wide,
        kind: Kind::Category,
        heading: None,
    },
    Course {
        id: 11,
        title: "The rear light",
        sessions: 1,
        minutes: 21,
        palette: Palette::Second,
        shelf: Shelf::Sleep,
        slot: Slot::Tile,
        kind: Kind::Story,
        heading: None,
    },
    Course {
        id: 12,
        title: "Imaginary ride",
        sessions: 1,
        minutes: 21,
        palette: Palette::First,
        shelf: Shelf::Sleep,
        slot: Slot::Tile,
        kind: Kind::Story,
        heading: None,
    },
    Course {
        id: 13,
        title: "The audition",
        sessions: 1,
        minutes: 21,
        palette: Palette::Third,
        shelf: Shelf::Sleep,
        slot: Slot::Tile,
        kind: Kind::Story,
        heading: None,
    },
    Course {
        id: 14,
        title: "Private planet",
        sessions: 1,
        minutes: 21,
        palette: Palette::Second,
        shelf: Shelf::Sleep,
        slot: Slot::Tile,
        kind: Kind::Story,
        heading: None,
    },
    Course {
        id: 15,
        title: "The bike ride",
        sessions: 1,
        minutes: 21,
        palette: Palette::Third,
        shelf: Shelf::Sleep,
        slot: Slot::Wide,
        kind: Kind::Story,
        heading: None,
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

/// The line of meta a card carries beside its title, before it is set in capitals.
pub fn meta_label(course: &Course) -> String {
    match course.kind {
        Kind::Course => {
            let word = if course.sessions == 1 {
                "session"
            } else {
                "sessions"
            };
            format!("{} {word}", course.sessions)
        }
        Kind::OfTheDay => format!("{} min \u{00b7} meditation of the day", course.minutes),
        Kind::Category => format!("{} courses", course.sessions),
        Kind::Story => format!("{} min", course.minutes),
    }
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
        let one = course(11).expect("no course 11");
        assert_eq!(sessions_label(one), "1 session \u{00b7} 21 min");
        let many = course(2).expect("no course 2");
        assert_eq!(sessions_label(many), "7 sessions \u{00b7} 12 min");
    }

    /// Each kind of card says what it is the way the reference says it.
    #[test]
    fn fr22_every_card_says_what_it_is_the_way_the_reference_does() {
        let label = |id| meta_label(course(id).expect("no such course")).to_uppercase();
        assert_eq!(label(1), "3 MIN \u{00b7} MEDITATION OF THE DAY");
        assert_eq!(label(2), "7 SESSIONS");
        assert_eq!(label(4), "10 COURSES");
        assert_eq!(label(11), "21 MIN");
    }

    /// A shelf has its hero first if it has one, and every heading starts a section
    /// rather than sitting between two tiles of the same row.
    #[test]
    fn fr22_every_shelf_is_laid_out_the_way_the_reference_is() {
        for shelf in [Shelf::ForYou, Shelf::Meditate, Shelf::Sleep] {
            let items = on(shelf);
            for (index, found) in items.iter().enumerate() {
                if found.slot == Slot::Hero {
                    assert_eq!(index, 0, "{} is a hero part way down", found.title);
                }
            }
            let mut in_row = 0;
            for found in &items {
                if found.heading.is_some() {
                    assert_eq!(in_row % 2, 0, "{} splits a row of tiles", found.title);
                }
                in_row = if found.slot == Slot::Tile {
                    in_row + 1
                } else {
                    0
                };
            }
        }
        assert_eq!(on(Shelf::Sleep).len(), 5, "the reference has five stories");
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
