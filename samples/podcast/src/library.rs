//! The shows, the episodes, and the two drawings a podcast screen is made of.

use dioxus_compose::prelude::*;
use dioxus_compose::{DrawList, DrawListBuilder};

/// The covers, drawn.
///
/// A podcast cover is a poster: a strong ground, one large mark and nothing else, made to
/// be recognised at the size of a thumbnail. A draw list cannot say one. Three overlapping
/// accent shapes was what it could say, and what it drew was a circle next to a square,
/// three times, which is a show nobody can tell from another show.
///
/// These are original drawings, registered once and drawn by id. A cover carries its own
/// colours, because that is what a cover is: the thing on a shelf that is recognised
/// before it is read.
pub static COVERS: [&[u8]; 3] = [
    include_bytes!("../assets/cover-heavyweight.svg"),
    include_bytes!("../assets/cover-researcher.svg"),
    include_bytes!("../assets/cover-sunpath.svg"),
];

/// One show.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Show {
    pub name: &'static str,
    pub host: &'static str,
    pub followers: u32,
    /// The show's cover art.
    pub cover: &'static [u8],
}

/// One episode.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Episode {
    pub id: u32,
    pub show: usize,
    pub season: u32,
    pub number: u32,
    pub title: &'static str,
    pub blurb: &'static str,
    /// Whole seconds, so a position and a length are the same kind of number.
    pub seconds: u32,
    pub hearts: u32,
    pub comments: u32,
    pub shares: u32,
    /// What the artwork is built from, and what the waveform is drawn from.
    pub seed: u32,
}

pub const SHOWS: [Show; 3] = [
    Show {
        name: "Heavyweight",
        cover: COVERS[0],
        host: "Andrew Biletski",
        followers: 25_400,
    },
    Show {
        name: "The Researcher",
        cover: COVERS[1],
        host: "Mira Okafor",
        followers: 12_100,
    },
    Show {
        name: "Sunpath",
        cover: COVERS[2],
        host: "Joel Iwu",
        followers: 8_300,
    },
];

pub const EPISODES: [Episode; 5] = [
    Episode {
        id: 1,
        show: 0,
        season: 1,
        number: 2,
        title: "Unstoppable ambition",
        blurb: "In this empowering episode we explore the mindset and habits that turn \
                ambition into momentum, and how to keep going once the novelty has worn \
                off.",
        seconds: 752,
        hearts: 198,
        comments: 54,
        shares: 32,
        seed: 17,
    },
    Episode {
        id: 2,
        show: 1,
        season: 2,
        number: 4,
        title: "Fuel your fire",
        blurb: "We dive into the spark that fuels lasting motivation, and how to tap into \
                your own drive when nothing external is pushing you.",
        seconds: 1_284,
        hearts: 142,
        comments: 31,
        shares: 18,
        seed: 41,
    },
    Episode {
        id: 3,
        show: 2,
        season: 1,
        number: 9,
        title: "The long quiet",
        blurb: "What happens in the months nobody writes about, and why the people who \
                last are rarely the ones who started loudest.",
        seconds: 2_015,
        hearts: 88,
        comments: 12,
        shares: 7,
        seed: 73,
    },
    Episode {
        id: 4,
        show: 0,
        season: 1,
        number: 3,
        title: "Room to be wrong",
        blurb: "On building the kind of working life where changing your mind costs you \
                nothing.",
        seconds: 1_640,
        hearts: 301,
        comments: 77,
        shares: 44,
        seed: 5,
    },
    Episode {
        id: 5,
        show: 1,
        season: 2,
        number: 5,
        title: "Small and finished",
        blurb: "Why a small thing that exists beats a large thing that is nearly ready, \
                every time, for everyone involved.",
        seconds: 934,
        hearts: 64,
        comments: 9,
        shares: 3,
        seed: 29,
    },
];

pub fn episode(id: u32) -> Option<&'static Episode> {
    EPISODES.iter().find(|found| found.id == id)
}

pub fn show_of(episode: &Episode) -> &'static Show {
    &SHOWS[episode.show]
}

/// A length or a position, as minutes and seconds.
pub fn clock(seconds: u32) -> String {
    format!("{}:{:02}", seconds / 60, seconds % 60)
}

/// A count, shortened once it stops being a number people read digit by digit.
pub fn short_count(value: u32) -> String {
    if value < 1_000 {
        return value.to_string();
    }
    let thousands = value / 1_000;
    let remainder = (value % 1_000) / 100;
    if remainder == 0 {
        format!("{thousands}k")
    } else {
        format!("{thousands}.{remainder}k")
    }
}

/// A repeatable sequence from a seed.
///
/// A waveform has to look irregular and has to be the same irregular shape every frame: a
/// chart that reshuffles itself on each redraw is a chart that flickers, and a chart built
/// from a real random source cannot be tested. This is the smallest thing that is both.
fn wobble(seed: u32, step: u32) -> f32 {
    let mixed = seed
        .wrapping_mul(1_664_525)
        .wrapping_add(step.wrapping_mul(1_013_904_223))
        .wrapping_mul(2_246_822_519);
    (mixed >> 8) as f32 / (1_u32 << 24) as f32
}

/// The waveform behind a player: one column per sample, the played part in one colour and
/// the rest in another.
///
/// The reference draws a few hundred hair-thin columns. This draws `columns` of them,
/// because every column is a record and a frame's worth of records is the budget the whole
/// project is built around. Forty reads as a waveform and costs forty records once, held
/// in an attribute that only re-crosses the boundary when it changes.
pub fn waveform(
    width: f32,
    height: f32,
    columns: usize,
    seed: u32,
    played: f32,
    played_paint: Paint,
    rest_paint: Paint,
) -> DrawList {
    if columns == 0 {
        return DrawListBuilder::with_capacity(0, 0).build();
    }
    let slot = width / columns as f32;
    let bar = slot * 0.5;
    let middle = height / 2.0;
    let mut list = DrawListBuilder::with_capacity(columns, 0);
    for index in 0..columns {
        // Never shorter than the column is wide, or a quiet moment is a speck that reads
        // as a rendering fault.
        let tall = (wobble(seed, index as u32) * height).max(bar);
        let paint = if (index as f32 + 0.5) / columns as f32 <= played.clamp(0.0, 1.0) {
            played_paint
        } else {
            rest_paint
        };
        list = list.round_rect(
            paint,
            index as f32 * slot + (slot - bar) / 2.0,
            middle - tall / 2.0,
            bar,
            tall,
            bar / 2.0,
            0.0,
        );
    }
    list.build()
}

/// The marks under an episode: what you can do to it.
///
/// Drawn rather than registered, because the closed set of icon meanings has no heart, no
/// comment bubble and no share in it, and a meaning is something every design system owes
/// artwork for rather than something one sample needs. Written rather than set in a
/// character, because a heart is the only one of the three that every font carries: the
/// nearest characters to the other two arrive as an empty box on most systems.
///
/// Each is drawn in a square of `size`, from a shape laid out on eighteen units, so the
/// three line up whatever size the row asks for.
pub fn heart(size: f32, paint: Paint) -> DrawList {
    let unit = |value: f32| value * size / 18.0;
    let stroke = unit(1.6);
    // Two lobes and the point they run down to. The arcs start just below the horizontal
    // and sweep over the top, so each one ends where the other side of the heart begins.
    DrawListBuilder::with_capacity(4, 0)
        .arc(paint, unit(5.4), unit(6.4), unit(3.6), 160.0, 200.0, stroke)
        .arc(
            paint,
            unit(11.6),
            unit(6.4),
            unit(3.6),
            180.0,
            200.0,
            stroke,
        )
        .line(paint, unit(2.0), unit(7.6), unit(9.0), unit(15.4), stroke)
        .line(paint, unit(15.0), unit(7.6), unit(9.0), unit(15.4), stroke)
        .build()
}

/// A bubble with a tail, which is the comment mark every set draws.
pub fn comment(size: f32, paint: Paint) -> DrawList {
    let unit = |value: f32| value * size / 18.0;
    let stroke = unit(1.6);
    DrawListBuilder::with_capacity(2, 0)
        .round_rect(
            paint,
            unit(2.4),
            unit(3.2),
            unit(13.2),
            unit(9.6),
            unit(3.2),
            stroke,
        )
        .line(paint, unit(6.6), unit(12.4), unit(5.0), unit(16.0), stroke)
        .build()
}

/// An arrow pointing out of the screen, which is the share mark beside the other two.
pub fn share(size: f32, paint: Paint) -> DrawList {
    let unit = |value: f32| value * size / 18.0;
    let stroke = unit(1.6);
    DrawListBuilder::with_capacity(3, 0)
        .line(paint, unit(5.0), unit(3.4), unit(14.2), unit(9.0), stroke)
        .line(paint, unit(14.2), unit(9.0), unit(5.0), unit(14.6), stroke)
        .line(paint, unit(5.0), unit(14.6), unit(5.0), unit(3.4), stroke)
        .build()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::palette;
    use dioxus_compose::DrawCommand;

    #[test]
    fn a_clock_reads_as_minutes_and_seconds() {
        assert_eq!(clock(752), "12:32");
        assert_eq!(clock(59), "0:59");
        assert_eq!(clock(0), "0:00");
        assert_eq!(clock(3_601), "60:01");
    }

    #[test]
    fn a_count_is_shortened_once_it_stops_being_read_digit_by_digit() {
        assert_eq!(short_count(999), "999");
        assert_eq!(short_count(1_000), "1k");
        assert_eq!(short_count(25_400), "25.4k");
        assert_eq!(short_count(12_100), "12.1k");
    }

    /// A waveform that reshuffles itself on every redraw is a waveform that flickers, and
    /// one built from a real random source cannot be tested at all.
    #[test]
    fn fr16_a_waveform_is_the_same_shape_every_time_it_is_drawn() {
        let once = waveform(300.0, 80.0, 40, 17, 0.4, palette::ACCENT, palette::WAVE);
        let again = waveform(300.0, 80.0, 40, 17, 0.4, palette::ACCENT, palette::WAVE);
        assert_eq!(once.as_bytes(), again.as_bytes());
    }

    /// Two episodes have to look different, or a shelf of them reads as one thing five
    /// times.
    #[test]
    fn fr16_two_seeds_draw_two_different_waveforms() {
        let first = waveform(300.0, 80.0, 40, 17, 0.4, palette::ACCENT, palette::WAVE);
        let second = waveform(300.0, 80.0, 40, 41, 0.4, palette::ACCENT, palette::WAVE);
        assert_ne!(first.as_bytes(), second.as_bytes());
    }

    /// The played part of the waveform is what says how far in you are, so it has to grow
    /// with the position rather than being drawn once and left.
    #[test]
    fn fr16_the_played_part_of_the_waveform_grows_with_the_position() {
        fn played(at: f32) -> usize {
            waveform(300.0, 80.0, 40, 17, at, palette::ACCENT, palette::WAVE)
                .decode()
                .expect("it did not decode")
                .iter()
                .filter(|command| command.paint() == palette::ACCENT)
                .count()
        }
        assert_eq!(played(0.0), 0);
        assert_eq!(played(1.0), 40);
        assert!(played(0.25) < played(0.75));
    }

    /// Every drawing here is painted in the hub's own colours.
    ///
    /// Named for what it defends: these were roles once, so the played part of a waveform
    /// came out as whatever the running design system calls its primary, which is blue in
    /// every one of them. The reference's waveform is orange over grey.
    #[test]
    fn fr22_every_drawing_is_painted_in_the_reference_colours() {
        let size = 18.0;
        let drawings = [
            waveform(300.0, 80.0, 40, 17, 0.4, palette::ACCENT, palette::WAVE),
            heart(size, palette::INK),
            comment(size, palette::INK),
            share(size, palette::INK),
        ];
        for drawing in drawings {
            for command in drawing.decode().expect("a drawing did not decode") {
                assert!(
                    matches!(command.paint(), Paint::Literal(_)),
                    "{command:?} is painted with something the design system would pick"
                );
            }
        }
    }

    /// The three marks are three different shapes drawn inside the square they were asked
    /// for. A row of identical marks is a row nobody can read, and one that spills out of
    /// its circle is drawn over the outline round it.
    #[test]
    fn fr22_the_three_action_marks_differ_and_stay_inside_their_square() {
        let size = 24.0;
        let marks = [
            heart(size, palette::INK),
            comment(size, palette::INK),
            share(size, palette::INK),
        ];
        for (index, mark) in marks.iter().enumerate() {
            let commands = mark.decode().expect("a mark did not decode");
            assert!(!commands.is_empty(), "mark {index} draws nothing");
            for other in &marks[index + 1..] {
                assert_ne!(mark.as_bytes(), other.as_bytes(), "two marks are one shape");
            }
        }
        for mark in &marks {
            for command in mark.decode().expect("a mark did not decode") {
                for value in corners(&command) {
                    assert!(
                        (0.0..=size).contains(&value),
                        "{command:?} reaches {value}, which is outside the square it was
                         asked for"
                    );
                }
            }
        }
    }

    /// Where a command reaches, as the numbers that have to stay inside the square.
    fn corners(command: &DrawCommand) -> Vec<f32> {
        match *command {
            DrawCommand::Line { x1, y1, x2, y2, .. } => vec![x1, y1, x2, y2],
            DrawCommand::RoundRect {
                x,
                y,
                width,
                height,
                ..
            }
            | DrawCommand::Rect {
                x,
                y,
                width,
                height,
                ..
            } => vec![x, y, x + width, y + height],
            DrawCommand::Arc {
                center_x,
                center_y,
                radius,
                ..
            }
            | DrawCommand::Circle {
                center_x,
                center_y,
                radius,
                ..
            } => vec![
                center_x - radius,
                center_y - radius,
                center_x + radius,
                center_y + radius,
            ],
            _ => Vec::new(),
        }
    }

    /// An empty waveform is an empty drawing rather than a division by zero.
    #[test]
    fn a_waveform_with_no_columns_draws_nothing() {
        let list = waveform(300.0, 80.0, 0, 17, 0.4, palette::ACCENT, palette::WAVE);
        assert!(list.decode().expect("it did not decode").is_empty());
    }

    /// A quiet moment is still a column somebody can see.
    #[test]
    fn fr16_a_quiet_moment_is_still_drawn() {
        for command in waveform(300.0, 80.0, 40, 17, 0.4, palette::ACCENT, palette::WAVE)
            .decode()
            .expect("it did not decode")
        {
            if let DrawCommand::RoundRect { width, height, .. } = command {
                assert!(
                    height >= width,
                    "a column of {width} by {height} is thinner than it is tall"
                );
            }
        }
    }

    /// Every episode names a show that exists, so a shelf never has a row with no name on
    /// it.
    #[test]
    fn every_episode_belongs_to_a_show() {
        for found in EPISODES {
            assert!(found.show < SHOWS.len(), "{} has no show", found.title);
            assert!(found.seconds > 0, "{} has no length", found.title);
        }
    }
}
