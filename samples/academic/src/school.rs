//! What the school teaches, and what a pupil has got through.

use dioxus_compose::prelude::*;

/// The four subject marks, drawn.
///
/// The reference puts a rendered three dimensional object on each tile: a camera, a safe,
/// a joystick, a turntable. A draw list has no lighting and no mesh, and what it made of
/// that was three concentric circles, four times, so four subjects came out as four
/// targets in four colours.
///
/// These are original drawings of the things a drummer actually touches, registered once
/// and drawn by id. The tile behind a mark, the name under it and the ink on both are
/// still roles, so the four tiles are still the design system's four colours.
pub static MARKS: [&[u8]; 4] = [
    include_bytes!("../assets/mark-technique.svg"),
    include_bytes!("../assets/mark-arsenal.svg"),
    include_bytes!("../assets/mark-coordination.svg"),
    include_bytes!("../assets/mark-songs.svg"),
];

/// One of the four things a drummer works on.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Subject {
    pub name: &'static str,
    pub blurb: &'static str,
    /// How much of it is done, nought to one.
    pub progress: f32,
    pub tile: Tile,
    /// The drawing on the subject's tile.
    pub mark: &'static [u8],
}

/// The fill a subject's tile takes, and the ink that reads on it.
///
/// Four subjects, three accent families. The vocabulary has `Primary`, `Secondary` and
/// `Tertiary` with a container each, and nothing else that is a peer of those: `Error` is
/// a warning rather than a fourth colour, and using it for a subject would mean the tile
/// marked "Songs" is the one the design system reserves for something going wrong.
///
/// So the fourth tile is the neutral fill. It reads as quieter than its three neighbours,
/// which is a real loss against the reference, where the four subjects are four equals. A
/// fourth accent family is what the picture actually asked for.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Tile {
    First,
    Second,
    Third,
    Neutral,
}

impl Tile {
    /// The fill, the ink on it, and the stronger colour of the same family for a mark.
    pub fn roles(self) -> (ColorRole, ColorRole, ColorRole) {
        match self {
            Tile::First => (
                ColorRole::PrimaryContainer,
                ColorRole::OnPrimaryContainer,
                ColorRole::Primary,
            ),
            Tile::Second => (
                ColorRole::SecondaryContainer,
                ColorRole::OnSecondaryContainer,
                ColorRole::Secondary,
            ),
            Tile::Third => (
                ColorRole::TertiaryContainer,
                ColorRole::OnTertiaryContainer,
                ColorRole::Tertiary,
            ),
            Tile::Neutral => (
                ColorRole::SurfaceVariant,
                ColorRole::OnSurfaceVariant,
                ColorRole::Outline,
            ),
        }
    }
}

pub const SUBJECTS: [Subject; 4] = [
    Subject {
        name: "Technique",
        mark: MARKS[0],
        blurb: "Grip, rebound and the four strokes",
        progress: 0.75,
        tile: Tile::First,
    },
    Subject {
        name: "Arsenal",
        mark: MARKS[1],
        blurb: "Fills, rolls and where to put them",
        progress: 0.4,
        tile: Tile::Second,
    },
    Subject {
        name: "Coordination",
        mark: MARKS[2],
        blurb: "Limbs that disagree, on purpose",
        progress: 0.55,
        tile: Tile::Third,
    },
    Subject {
        name: "Songs",
        mark: MARKS[3],
        blurb: "Whole tunes, start to finish",
        progress: 0.2,
        tile: Tile::Neutral,
    },
];

/// One lesson in a stage.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Lesson {
    pub stage: u32,
    pub number: u32,
    pub title: &'static str,
    pub summary: &'static str,
    /// A lesson is open once the one before it is finished.
    pub open: bool,
    pub recent: bool,
}

pub const STAGES: [u32; 4] = [1, 2, 3, 4];

pub const LESSONS: [Lesson; 8] = [
    Lesson {
        stage: 1,
        number: 1,
        title: "Holding the sticks",
        summary: "Where the fulcrum is and why it matters",
        open: true,
        recent: false,
    },
    Lesson {
        stage: 1,
        number: 2,
        title: "The single stroke",
        summary: "One hand, then the other, evenly",
        open: true,
        recent: false,
    },
    Lesson {
        stage: 2,
        number: 4,
        title: "Breaks, and where to show off",
        summary: "The four bars everyone remembers",
        open: true,
        recent: true,
    },
    Lesson {
        stage: 2,
        number: 5,
        title: "Breaks inside the rhythm",
        summary: "Leaving without anyone noticing",
        open: false,
        recent: false,
    },
    Lesson {
        stage: 2,
        number: 6,
        title: "Advanced breaks",
        summary: "Coming back in on the right beat",
        open: false,
        recent: false,
    },
    Lesson {
        stage: 3,
        number: 7,
        title: "Ghost notes",
        summary: "The ones you feel rather than hear",
        open: false,
        recent: false,
    },
    Lesson {
        stage: 3,
        number: 8,
        title: "Playing behind the beat",
        summary: "Late, deliberately, and still in time",
        open: false,
        recent: false,
    },
    Lesson {
        stage: 4,
        number: 9,
        title: "Writing your own part",
        summary: "What the song needs rather than what you can do",
        open: false,
        recent: false,
    },
];

/// The lessons in one stage, in the order they are taken.
pub fn stage(number: u32) -> Vec<&'static Lesson> {
    LESSONS
        .iter()
        .filter(|lesson| lesson.stage == number)
        .collect()
}

/// How far through the whole course a pupil is, as a fraction.
pub fn overall_progress() -> f32 {
    let open = LESSONS.iter().filter(|lesson| lesson.open).count();
    open as f32 / LESSONS.len() as f32
}

/// An ordinal, because "4th lesson" is how a lesson is named to a pupil.
pub fn ordinal(number: u32) -> String {
    let suffix = match (number % 10, number % 100) {
        (_, 11..=13) => "th",
        (1, _) => "st",
        (2, _) => "nd",
        (3, _) => "rd",
        _ => "th",
    };
    format!("{number}{suffix}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_ordinal_reads_the_way_a_lesson_is_named() {
        assert_eq!(ordinal(1), "1st");
        assert_eq!(ordinal(2), "2nd");
        assert_eq!(ordinal(3), "3rd");
        assert_eq!(ordinal(4), "4th");
        assert_eq!(ordinal(11), "11th");
        assert_eq!(ordinal(12), "12th");
        assert_eq!(ordinal(13), "13th");
        assert_eq!(ordinal(21), "21st");
        assert_eq!(ordinal(111), "111th");
    }

    /// Every stage has lessons in it. A stage that shows nothing reads as a course that
    /// has not been written rather than as a stage nobody has reached.
    #[test]
    fn every_stage_has_lessons_in_it() {
        for number in STAGES {
            assert!(
                !stage(number).is_empty(),
                "stage {number} has no lessons at all"
            );
        }
    }

    /// Exactly one lesson is the recent one. Two of them is a screen with two "carry on
    /// here" cards, which is a screen with none.
    #[test]
    fn exactly_one_lesson_is_the_recent_one() {
        assert_eq!(LESSONS.iter().filter(|lesson| lesson.recent).count(), 1);
    }

    /// A lesson marked recent has to be one a pupil can actually open.
    #[test]
    fn the_recent_lesson_is_one_that_is_open() {
        let recent = LESSONS
            .iter()
            .find(|lesson| lesson.recent)
            .expect("no recent lesson");
        assert!(recent.open, "{} is recent and locked", recent.title);
    }

    /// Four subjects, four fills. Two subjects sharing one is a grid where two tiles look
    /// like the same subject.
    #[test]
    fn fr13_no_two_subjects_share_a_fill() {
        for (index, first) in SUBJECTS.iter().enumerate() {
            for second in &SUBJECTS[index + 1..] {
                assert_ne!(
                    first.tile.roles().0,
                    second.tile.roles().0,
                    "{} and {} are drawn in one colour",
                    first.name,
                    second.name
                );
            }
        }
    }

    /// Four subjects, four marks. Named for what it defends: three concentric circles in
    /// four colours is four subjects nobody can tell apart, which is what the draw list
    /// could say and why these are pictures.
    #[test]
    fn fr16_no_two_subjects_share_a_mark() {
        for (index, first) in SUBJECTS.iter().enumerate() {
            for second in &SUBJECTS[index + 1..] {
                assert_ne!(
                    first.mark, second.mark,
                    "{} and {} are the same drawing",
                    first.name, second.name
                );
            }
        }
    }

    /// A drawing that is not a drawing is a tile with a hole in it, and a drawing with no
    /// box to scale from is drawn at its own size in the corner of the tile.
    #[test]
    fn fr16_every_mark_is_a_document_that_can_be_scaled() {
        for subject in SUBJECTS {
            let text = std::str::from_utf8(subject.mark).expect("a mark is not text");
            assert!(
                text.starts_with("<svg"),
                "{}'s mark is not an svg document",
                subject.name
            );
            assert!(
                text.contains("viewBox='0 0 120 120'"),
                "{}'s mark has no box to scale from",
                subject.name
            );
        }
    }

    #[test]
    fn progress_is_a_fraction_of_the_whole_course() {
        let progress = overall_progress();
        assert!((0.0..=1.0).contains(&progress), "{progress} is not a share");
        assert_eq!(progress, 3.0 / 8.0);
    }
}
