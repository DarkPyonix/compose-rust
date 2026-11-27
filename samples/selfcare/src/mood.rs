//! How the day felt, what is worrying you, and the faces that stand for both.

use crate::palette;
use compose_rust::ui::{DrawList, DrawListBuilder, Paint, TypeRole};

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

    /// Where this feeling sits along the strip.
    pub fn index(self) -> usize {
        match self {
            Mood::Sad => 0,
            Mood::Calm => 1,
            Mood::Happy => 2,
            Mood::Angry => 3,
        }
    }

    /// The drawing of this feeling's face.
    pub fn picture(self) -> &'static [u8] {
        FACES[self.index()]
    }

    pub fn label(self) -> &'static str {
        match self {
            Mood::Sad => "Sad",
            Mood::Calm => "Calm",
            Mood::Happy => "Happy",
            Mood::Angry => "Angry",
        }
    }

    /// The fill this feeling's chosen answer is drawn in, and the ink that reads on it.
    ///
    /// Literals, from the sample's own palette. The reference names four pastels and no
    /// design system holds them: asking for a container role instead would give whichever
    /// system is active its own tint, and the answer on this screen is the colour. The
    /// fill is the disc the face is drawn on, so the chosen answer and the face agree.
    pub fn pair(self) -> (Paint, Paint) {
        let fill = match self {
            Mood::Sad => palette::POWDER,
            Mood::Calm => palette::MINT,
            Mood::Happy => palette::PINK,
            Mood::Angry => palette::CORAL,
        };
        (fill, palette::ON_LIGHT)
    }

    /// The panel the face stands on: a paler tint of its disc, as in the reference.
    pub fn panel(self) -> Paint {
        match self {
            Mood::Sad => palette::POWDER_PANEL,
            Mood::Calm => palette::MINT_PANEL,
            Mood::Happy => palette::PINK_PANEL,
            Mood::Angry => palette::CORAL_PANEL,
        }
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
/// its own disc and its own near-black ink, so the face reads on its panel without the
/// drawing having to know what is behind it.
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

/// The answer that is drawn as an outline until it is chosen, as the reference draws it:
/// it stands for everything the other nine do not say.
pub const OPEN_WORRY: &str = "Other";

/// A guided session.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Session {
    pub title: &'static str,
    pub minutes: u32,
    pub when: &'static str,
    /// Which feeling's tint the session's swatch takes in the library.
    pub mood: Mood,
}

/// The sessions. The first two are the pair of cards at the top of the listening screen
/// and the rest are "Special for you", in the reference's order and with its titles.
pub const SESSIONS: [Session; 6] = [
    Session {
        title: "Emotional Balance",
        minutes: 15,
        when: "Anytime",
        mood: Mood::Calm,
    },
    Session {
        title: "Calm Relaxation",
        minutes: 12,
        when: "Evening",
        mood: Mood::Sad,
    },
    Session {
        title: "Morning Gratitude",
        minutes: 5,
        when: "Morning",
        mood: Mood::Happy,
    },
    Session {
        title: "Serenity Before Sleep",
        minutes: 10,
        when: "Evening",
        mood: Mood::Calm,
    },
    Session {
        title: "Letting Go of Anger",
        minutes: 8,
        when: "Anytime",
        mood: Mood::Angry,
    },
    Session {
        title: "Breathing Room",
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

/// About what one letter comes to at the caption rung, in dp. The same guess as above,
/// used to size the bubble a feeling's name is written in.
const LETTER_WIDTH: f32 = 6.5;

/// The week's readings, nought to one, for the line on the profile, and the feeling the
/// reference writes in a bubble over two of them.
pub const WEEK: [(&str, f32, Option<Mood>); 7] = [
    ("Mon", 0.2, None),
    ("Tue", 0.3, None),
    ("Wed", 0.62, None),
    ("Thu", 0.72, Some(Mood::Calm)),
    ("Fri", 0.6, None),
    ("Sat", 0.45, None),
    ("Sun", 0.82, Some(Mood::Happy)),
];

/// The week as a line, with a day written under each reading and a bubble naming the
/// feeling over the two the reference names.
///
/// The line is the reference's cyan, from the palette. The reference runs it from cyan to
/// pink and fills the area under it with the same gradient; a draw command resolves its
/// paint to one colour and there is no filled path, so neither can be drawn yet
/// (compose-rust issues #584 and #308). The line is drawn in the colour it mostly is.
///
/// A polyline would be one record, and a polyline's points live in a registered asset that
/// application code has no way to register, so the line is drawn as segments. Seven
/// readings is six of them.
pub fn week_line(width: f32, height: f32) -> DrawList {
    let label_band = height * 0.22;
    let plot = height - label_band;
    let baseline = plot + label_band * 0.62;
    let slot = width / WEEK.len() as f32;
    // A `TextAt` is placed by the left end of its string, and a draw list has no way to
    // measure a string: there is no font here, only a rung of the ladder that the Renderer
    // resolves. So the day is inset by half of what three caption letters come to, which
    // centres it closely enough and keeps the last day inside the box. Placed at the
    // slot's middle the whole row sat half a slot to the right and Sunday was clipped.
    let label_inset = (slot / 2.0 - DAY_HALF_WIDTH).max(0.0);
    let last_label_start = (width - DAY_HALF_WIDTH * 2.0).max(0.0);
    let x_of = |index: usize| index as f32 * slot + slot / 2.0;
    // The top of the plot is kept clear for the bubbles, which sit above their reading.
    let y_of = |value: f32| plot - value.clamp(0.0, 1.0) * (plot * 0.62) - plot * 0.07;

    let mut list = DrawListBuilder::with_capacity(WEEK.len() * 5, WEEK.len() * 8);
    for index in 1..WEEK.len() {
        list = list.line(
            palette::CYAN,
            x_of(index - 1),
            y_of(WEEK[index - 1].1),
            x_of(index),
            y_of(WEEK[index].1),
            3.0,
        );
    }
    for (index, (day, value, named)) in WEEK.iter().enumerate() {
        list = list.text_at(
            palette::MUTED,
            day,
            (index as f32 * slot + label_inset).min(last_label_start),
            baseline,
            TypeRole::Caption,
        );
        let Some(mood) = named else {
            continue;
        };
        // The bubble: a white pill over the reading, a dot in the feeling's colour, and
        // its name. Pulled back inside the box when the reading is the last one.
        let (x, y) = (x_of(index), y_of(*value));
        let bubble_height = 22.0;
        let bubble_width = 22.0 + mood.label().len() as f32 * LETTER_WIDTH;
        let left = (x - bubble_width / 2.0).clamp(0.0, (width - bubble_width).max(0.0));
        let top = (y - bubble_height - 8.0).max(0.0);
        list = list
            .circle(palette::CYAN, x, y, 4.0, 0.0)
            .round_rect(
                palette::INK,
                left,
                top,
                bubble_width,
                bubble_height,
                bubble_height / 2.0,
                0.0,
            )
            .circle(
                mood.pair().0,
                left + 11.0,
                top + bubble_height / 2.0,
                3.5,
                0.0,
            )
            .text_at(
                palette::ON_LIGHT,
                mood.label(),
                left + 18.0,
                top + bubble_height * 0.7,
                TypeRole::Caption,
            );
    }
    list.build()
}

#[cfg(test)]
mod tests {
    use super::*;
    use compose_rust::ui::DrawCommand;

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

    /// Four feelings, four fills and four panels. Two moods sharing one is a picker where
    /// two answers look like the same answer.
    #[test]
    fn fr13_no_two_moods_share_a_fill() {
        for (index, first) in Mood::STRIP.iter().enumerate() {
            assert_eq!(first.index(), index, "{} is out of place", first.label());
            for second in &Mood::STRIP[index + 1..] {
                assert_ne!(
                    first.pair().0,
                    second.pair().0,
                    "{} and {} are drawn in one colour",
                    first.label(),
                    second.label()
                );
                assert_ne!(
                    first.panel(),
                    second.panel(),
                    "{} and {} stand on one panel",
                    first.label(),
                    second.label()
                );
            }
        }
    }

    /// Every colour in the week is the reference's, from the palette, and the line itself
    /// is the cyan. Named for what it defends: the line was drawn in `Primary` and
    /// `OnSurfaceVariant`, which under Cupertino is the system blue and its grey.
    #[test]
    fn fr22_the_week_is_drawn_in_the_reference_colours() {
        let commands = week_line(300.0, 160.0)
            .decode()
            .expect("the week did not decode");
        for command in &commands {
            assert!(
                matches!(command.paint(), Paint::Literal(_)),
                "{command:?} is painted with something other than the palette"
            );
        }
        for command in &commands {
            if let DrawCommand::Line { paint, .. } = command {
                assert_eq!(*paint, palette::CYAN, "the week's line is not the cyan");
            }
        }
    }

    /// The two feelings the reference names over the week are written there.
    #[test]
    fn fr22_the_week_names_the_feelings_the_reference_names() {
        let list = week_line(300.0, 160.0);
        let commands = list.decode().expect("the week did not decode");
        let written: Vec<&str> = commands
            .iter()
            .filter_map(|command| match command {
                DrawCommand::TextAt {
                    text_offset,
                    text_length,
                    ..
                } => list.text_at(*text_offset, *text_length).ok(),
                _ => None,
            })
            .collect();
        for mood in [Mood::Calm, Mood::Happy] {
            assert!(
                written.contains(&mood.label()),
                "the week does not say {}",
                mood.label()
            );
        }
    }

    /// The last day stays inside the box. Named for what it defends: a label placed past
    /// the canvas edge is clipped there rather than merely mispositioned, so Sunday was
    /// drawn as "Su" and looked like a spelling nobody had noticed.
    #[test]
    fn fr16_the_last_day_is_written_inside_the_chart() {
        let width = 300.0;
        let commands = week_line(width, 160.0)
            .decode()
            .expect("the week did not decode");
        let days: Vec<f32> = commands
            .iter()
            .filter_map(|command| match command {
                DrawCommand::TextAt {
                    x,
                    type_role: TypeRole::Caption,
                    paint,
                    ..
                } if *paint == palette::MUTED => Some(*x),
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
        let commands = week_line(300.0, 160.0)
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
