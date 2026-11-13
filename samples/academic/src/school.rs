//! What the school teaches, and what a pupil has got through.

use crate::palette;
use compose_rust::ui::Paint;

/// The four subject marks, drawn.
///
/// The reference puts a rendered three dimensional object on each tile: a camera, a safe,
/// a joystick, a turntable. A draw list has no lighting and no mesh, and what it made of
/// that was three concentric circles, four times, so four subjects came out as four
/// targets in four colours.
///
/// These are original drawings of the things a drummer actually touches, registered once
/// and drawn by id. The tile behind a mark is the reference's colour for that subject, from
/// `palette`.
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
    /// The colour of the subject's tile, which is the reference's: four equals, purple,
    /// coral, teal and amber, with white written on each.
    pub fill: Paint,
    /// The drawing on the subject's tile.
    pub mark: &'static [u8],
}

pub const SUBJECTS: [Subject; 4] = [
    Subject {
        name: "Technique",
        mark: MARKS[0],
        blurb: "Grip, rebound and the four strokes",
        progress: 0.75,
        fill: palette::PURPLE,
    },
    Subject {
        name: "Arsenal",
        mark: MARKS[1],
        blurb: "Fills, rolls and where to put them",
        progress: 0.4,
        fill: palette::ACCENT,
    },
    Subject {
        name: "Coordination",
        mark: MARKS[2],
        blurb: "Limbs that disagree, on purpose",
        progress: 0.55,
        fill: palette::TEAL,
    },
    Subject {
        name: "Songs",
        mark: MARKS[3],
        blurb: "Whole tunes, start to finish",
        progress: 0.2,
        fill: palette::AMBER,
    },
];

/// One lesson in a stage.
///
/// A title and nothing to summarise it: the reference's cards carry the title, which
/// lesson it is, and whether it is open, and a sentence under the title was a line the
/// picture does not have.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Lesson {
    pub stage: u32,
    pub number: u32,
    pub title: &'static str,
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
        open: true,
        recent: false,
    },
    Lesson {
        stage: 1,
        number: 2,
        title: "The single stroke",
        open: true,
        recent: false,
    },
    Lesson {
        stage: 2,
        number: 4,
        title: "Breaks, and where to show off",
        open: true,
        recent: true,
    },
    Lesson {
        stage: 2,
        number: 5,
        title: "Breaks inside the rhythm",
        open: false,
        recent: false,
    },
    Lesson {
        stage: 2,
        number: 6,
        title: "Advanced breaks",
        open: false,
        recent: false,
    },
    Lesson {
        stage: 3,
        number: 7,
        title: "Ghost notes",
        open: false,
        recent: false,
    },
    Lesson {
        stage: 3,
        number: 8,
        title: "Playing behind the beat",
        open: false,
        recent: false,
    },
    Lesson {
        stage: 4,
        number: 9,
        title: "Writing your own part",
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
/// Whether a pupil has reached a stage: any lesson in it is open. The strip names a stage
/// that has been reached in white and one that has not in grey, as the reference does.
pub fn reached(number: u32) -> bool {
    LESSONS
        .iter()
        .any(|lesson| lesson.stage == number && lesson.open)
}

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
                    first.fill, second.fill,
                    "{} and {} are drawn in one colour",
                    first.name, second.name
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

    /// The first two stages are reached and the last two are not, which is the strip the
    /// reference draws: "1 Stage" white, "2 Stage" chosen, "3 Stage" and on in grey.
    #[test]
    fn the_stages_reached_are_the_ones_with_an_open_lesson() {
        assert!(reached(1));
        assert!(reached(2));
        assert!(!reached(3));
        assert!(!reached(4));
    }

    #[test]
    fn progress_is_a_fraction_of_the_whole_course() {
        let progress = overall_progress();
        assert!((0.0..=1.0).contains(&progress), "{progress} is not a share");
        assert_eq!(progress, 3.0 / 8.0);
    }
}
