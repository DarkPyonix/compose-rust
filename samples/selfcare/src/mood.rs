//! How the day felt, what is worrying you, and the faces that stand for both.

use dioxus_compose::prelude::*;
use dioxus_compose::{DrawList, DrawListBuilder};

/// The four answers to "how do you feel today".
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Mood {
    Sad,
    Calm,
    Happy,
    Angry,
}

impl Mood {
    pub const STRIP: [Mood; 4] = [Mood::Sad, Mood::Calm, Mood::Happy, Mood::Angry];

    /// The drawing of this feeling's face.
    pub fn picture(self) -> &'static [u8] {
        FACES[match self {
            Mood::Sad => 0,
            Mood::Calm => 1,
            Mood::Happy => 2,
            Mood::Angry => 3,
        }]
    }

    pub fn label(self) -> &'static str {
        match self {
            Mood::Sad => "Sad",
            Mood::Calm => "Calm",
            Mood::Happy => "Happy",
            Mood::Angry => "Angry",
        }
    }

    /// The fill a mood is drawn in, and the ink that reads on it.
    ///
    /// Roles, not literals. The reference's pink and mint are one app's brand; what
    /// survives into a design system is that four feelings need four fills that are
    /// relatives of one another, are quiet enough to hold a drawn face, and each come with
    /// an ink that stays readable. That is the accent containers, plus the one fill the
    /// vocabulary already had for something going wrong.
    /// The fill this feeling is drawn in, and the ink that reads on it.
    ///
    /// Literals, from the sample's own palette. The reference names four pastels and no
    /// design system holds them: asking for a container role instead would give whichever
    /// system is active its own tint, and the answer in this screen is the colour.
    pub fn pair(self) -> (Color, Color) {
        let fill = match self {
            Mood::Sad => crate::palette::POWDER,
            Mood::Calm => crate::palette::MINT,
            Mood::Happy => crate::palette::PINK,
            Mood::Angry => crate::palette::CORAL,
        };
        (fill, crate::palette::ON_MOOD)
    }
}

/// The faces, drawn.
///
/// The reference's faces are hand-drawn line art: a loop of hair, a nose that is one
/// stroke, a mouth that says the whole feeling. A draw list cannot say that. It has
/// circles, arcs and lines, and what came out of it was a disc with two dots on it, which
/// is a face the way a rectangle is a garment.
///
/// So each face is an original drawing, registered once and drawn by id. Each one carries
/// its own disc and its own near-black ink, which is what lets it sit on the feeling's
/// panel in either colour scheme: the disc is light and the lines on it are dark whatever
/// is behind them, so the face reads on a pale panel and on a dark one without the drawing
/// having to know which it is.
pub static FACES: [&[u8]; 4] = [
    include_bytes!("../assets/face-sad.svg"),
    include_bytes!("../assets/face-calm.svg"),
    include_bytes!("../assets/face-happy.svg"),
    include_bytes!("../assets/face-angry.svg"),
];

/// What might be behind the feeling. Chosen from, not typed in.
pub const WORRIES: [&str; 10] = [
    "Sleepiness",
    "Sadness",
    "Anxiety",
    "Stress",
    "Loneliness",
    "Insomnia",
    "Anger",
    "Apathy",
    "Envy",
    "Other",
];

/// A guided session.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Session {
    pub title: &'static str,
    pub minutes: u32,
    pub when: &'static str,
    /// Which of the three tints the session's card takes.
    pub mood: Mood,
}

pub const SESSIONS: [Session; 6] = [
    Session {
        title: "Emotional balance",
        minutes: 15,
        when: "Anytime",
        mood: Mood::Calm,
    },
    Session {
        title: "Calm relaxation",
        minutes: 12,
        when: "Evening",
        mood: Mood::Sad,
    },
    Session {
        title: "Morning gratitude",
        minutes: 5,
        when: "Morning",
        mood: Mood::Happy,
    },
    Session {
        title: "Serenity before sleep",
        minutes: 10,
        when: "Evening",
        mood: Mood::Calm,
    },
    Session {
        title: "Letting go of anger",
        minutes: 8,
        when: "Anytime",
        mood: Mood::Sad,
    },
    Session {
        title: "Breathing room",
        minutes: 3,
        when: "Anytime",
        mood: Mood::Happy,
    },
];

/// Half of what a three letter day comes to at the caption rung, in dp.
///
/// A guess, and it has to be: the Host has no font and the draw list carries no measured
/// width. It is only used to inset a label so the row reads as centred, so being a few dp
/// out costs nothing, while not doing it at all put Sunday off the end of the chart.
const DAY_HALF_WIDTH: f32 = 11.0;

/// The week's readings, nought to one, for the line on the profile.
pub const WEEK: [(&str, f32); 7] = [
    ("Mon", 0.35),
    ("Tue", 0.5),
    ("Wed", 0.42),
    ("Thu", 0.7),
    ("Fri", 0.62),
    ("Sat", 0.86),
    ("Sun", 0.78),
];

/// The week as a line, with a day written under each reading.
///
/// A polyline would be one record, and a polyline's points live in a registered asset that
/// application code has no way to register, so the line is drawn as segments. Seven
/// readings is six of them.
pub fn week_line(width: f32, height: f32, ink: ColorRole, mark: ColorRole) -> DrawList {
    let label_band = height * 0.22;
    let plot = height - label_band;
    let baseline = plot + label_band * 0.62;
    let slot = width / WEEK.len() as f32;
    // A `TextAt` is placed by the left end of its string, and a draw list has no way to
    // measure a string: there is no font here, only a rung of the ladder that the Renderer
    // resolves. So the day is inset by half of what three caption letters come to, which
    // centres it closely enough and, more importantly, keeps the last day inside the box.
    // Placed at the slot's middle the whole row sat half a slot to the right and Sunday
    // was drawn past the edge and clipped to its first letter.
    let label_inset = (slot / 2.0 - DAY_HALF_WIDTH).max(0.0);
    // The rightmost label is the one that can leave the box, and leaving it is not a few
    // dp out of place: the Renderer clips at the canvas edge, so Sunday came out as "Su".
    // Its left end is pulled back to where a three letter word still ends inside.
    let last_label_start = (width - DAY_HALF_WIDTH * 2.0).max(0.0);
    let x_of = |index: usize| index as f32 * slot + slot / 2.0;
    let y_of = |value: f32| plot - value.clamp(0.0, 1.0) * (plot * 0.86) - plot * 0.07;

    let mut list = DrawListBuilder::with_capacity(WEEK.len() * 3, WEEK.len() * 4);
    for index in 1..WEEK.len() {
        list = list.line(
            Paint::Role(ink),
            x_of(index - 1),
            y_of(WEEK[index - 1].1),
            x_of(index),
            y_of(WEEK[index].1),
            2.0,
        );
    }
    for (index, (day, value)) in WEEK.iter().enumerate() {
        list = list
            .circle(Paint::Role(mark), x_of(index), y_of(*value), 3.0, 0.0)
            .text_at(
                Paint::Role(ink),
                day,
                (index as f32 * slot + label_inset).min(last_label_start),
                baseline,
                TypeRole::Caption,
            );
    }
    list.build()
}

#[cfg(test)]
mod tests {
    use super::*;
    use dioxus_compose::DrawCommand;

    /// Four feelings, four faces. Two moods sharing a drawing is a picker where two
    /// answers look like the same answer, which is what the disc with two dots on it was.
    #[test]
    fn fr16_no_two_moods_share_a_face() {
        for (index, first) in Mood::STRIP.iter().enumerate() {
            for second in &Mood::STRIP[index + 1..] {
                assert_ne!(
                    first.picture().as_ptr(),
                    second.picture().as_ptr(),
                    "{} and {} are the same drawing",
                    first.label(),
                    second.label()
                );
            }
        }
    }

    /// Four feelings, four fills. Two moods sharing one fill is a picker where two
    /// answers look like the same answer.
    #[test]
    fn fr13_no_two_moods_share_a_fill() {
        for (index, first) in Mood::STRIP.iter().enumerate() {
            for second in &Mood::STRIP[index + 1..] {
                assert_ne!(
                    first.pair().0,
                    second.pair().0,
                    "{} and {} are drawn in one colour",
                    first.label(),
                    second.label()
                );
            }
        }
    }

    /// Every colour in the week's line is a role. The faces are pictures and carry their
    /// own, which is what makes them pictures; everything a draw list says is a role.
    #[test]
    fn fr13_nothing_drawn_here_carries_a_literal_colour() {
        let drawings = [week_line(
            300.0,
            160.0,
            ColorRole::OnSurface,
            ColorRole::Primary,
        )];
        for drawing in drawings {
            for command in drawing.decode().expect("a drawing did not decode") {
                assert!(
                    matches!(command.paint(), Paint::Role(_)),
                    "{command:?} is painted with something other than a role"
                );
            }
        }
    }

    /// The last day stays inside the box. Named for what it defends: a label placed past
    /// the canvas edge is clipped there rather than merely mispositioned, so Sunday was
    /// drawn as "Su" and looked like a spelling nobody had noticed.
    #[test]
    fn fr16_the_last_day_is_written_inside_the_chart() {
        let width = 300.0;
        let commands = week_line(width, 160.0, ColorRole::OnSurface, ColorRole::Primary)
            .decode()
            .expect("the week did not decode");
        let days: Vec<f32> = commands
            .iter()
            .filter_map(|command| match command {
                DrawCommand::TextAt { x, .. } => Some(*x),
                _ => None,
            })
            .collect();
        assert_eq!(days.len(), WEEK.len(), "a day was not written at all");
        let last = days[days.len() - 1];
        assert!(
            last + DAY_HALF_WIDTH * 2.0 <= width,
            "{} starts at {last} and runs past the chart's {width}",
            WEEK[WEEK.len() - 1].0
        );
    }

    /// Seven readings make six segments. A line with a segment missing is a chart with a
    /// gap in it that reads as missing data.
    #[test]
    fn fr16_the_week_joins_every_reading_to_the_next() {
        let commands = week_line(300.0, 160.0, ColorRole::OnSurface, ColorRole::Primary)
            .decode()
            .expect("the week did not decode");
        assert_eq!(
            commands
                .iter()
                .filter(|command| matches!(command, DrawCommand::Line { .. }))
                .count(),
            WEEK.len() - 1
        );
    }

    /// Every session is one a person can finish, and says when it suits.
    #[test]
    fn every_session_has_a_length_and_a_time_of_day() {
        for session in SESSIONS {
            assert!(session.minutes > 0, "{} takes no time", session.title);
            assert!(!session.when.is_empty(), "{} suits no time", session.title);
        }
    }
}
