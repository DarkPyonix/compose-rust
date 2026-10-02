//! The two drawings: a dial and a week of bars.
//!
//! Both are `DrawList`s rather than widgets, because a chart is pixels the widget
//! vocabulary has no name for.
//!
//! The colours are this sample's own rather than the design system's. The reference these
//! charts are drawn from names a sage panel, a black column and an orange marker, and a
//! role cannot say any of the three: whatever system is active would answer with its own
//! accent instead. So the charts read the sample's palette, and the test below holds them
//! to it, which is the guarantee a role was giving before.
//!
//! `dial` still takes its two inks as arguments. A dial sitting on the page and the same
//! dial sitting on a tinted panel need different ink, and which panel it is on is
//! something only the screen knows.

use dioxus_compose::prelude::*;
use dioxus_compose::{DrawList, DrawListBuilder};

/// How much of the dial's radius a tick takes.
const TICK_LENGTH: f32 = 0.12;
/// How many ticks go round the dial. Twelve, like a clock face, which is what the
/// reference draws and what a reader can count without counting.
const TICKS: usize = 12;

/// A dial reading `fraction` of the way round: a ring of ticks, a pale disc for the number
/// to sit on, and a marker where the reading ends.
///
/// The marker is the reading. Without it the dial announced a number in the middle and drew
/// nothing that showed it: twelve identical ticks, which is a clock face rather than a
/// gauge, and the fifty-five per cent it said was the only place the fifty five existed.
/// A dot on the ring rather than an arc along it, because that is what the reference puts
/// there.
///
/// The ticks stay because they are what the reading is read against, and they are drawn as
/// short lines rather than as a dashed arc because an arc command has one sweep and a
/// dashed stroke is not something the draw vocabulary can say. Twelve lines is the same
/// picture and costs twelve records once.
///
/// Angles are measured from the right and go clockwise, which is how the arc command reads
/// them, so a dial that starts at the top starts a quarter turn back from zero.
pub fn dial(size: f32, fraction: f32, ink: Paint, marker: Paint) -> DrawList {
    let middle = size / 2.0;
    let outer = middle * 0.94;
    let inner = outer * (1.0 - TICK_LENGTH);
    let reading = (outer + inner) / 2.0;
    let fraction = fraction.clamp(0.0, 1.0);
    let mut list = DrawListBuilder::with_capacity(TICKS + 3, 0);
    for step in 0..TICKS {
        // From the top, clockwise, so the marker and the ticks agree about where zero is.
        let angle =
            (step as f32 / TICKS as f32) * std::f32::consts::TAU - std::f32::consts::FRAC_PI_2;
        let (sin, cos) = angle.sin_cos();
        list = list.line(
            ink,
            middle + cos * inner,
            middle + sin * inner,
            middle + cos * outer,
            middle + sin * outer,
            2.0,
        );
    }
    list = list.circle(
        Paint::Literal(crate::palette::LIGHT_GREY),
        middle,
        middle,
        inner - size * 0.02,
        0.0,
    );
    let angle = fraction * std::f32::consts::TAU - std::f32::consts::FRAC_PI_2;
    let (sin, cos) = angle.sin_cos();
    // The marker sits on the ring rather than on the arc, which is where the reference
    // puts it: the mark says exactly where.
    list.circle(
        marker,
        middle + cos * reading,
        middle + sin * reading,
        size * 0.028,
        0.0,
    )
    .build()
}

/// Half of what a three letter day comes to at the caption rung, in dp.
///
/// A guess, and it has to be: the Host has no font and the draw list carries no measured
/// width. It is only used to inset a label so the row reads as centred under its columns.
const DAY_HALF_WIDTH: f32 = 11.0;

/// One column of the week's chart.
#[derive(Clone, Copy, Debug)]
pub struct Bar {
    pub day: &'static str,
    /// Nought to one, against the tallest column of the week.
    pub height: f32,
    /// The one column the chart is about. The rest are drawn as outlines.
    pub filled: bool,
}

/// A week of columns with the day under each.
///
/// `width` is the box the drawing is given, not a box of its own choosing: a draw list is
/// in the canvas's own coordinates and knows nothing about how wide the canvas turned out,
/// so a chart drawn at three hundred inside a canvas that filled two hundred runs off the
/// side, and one drawn at three hundred inside a canvas that filled four stops short. The
/// caller passes the same number to both.
///
/// The columns that are not the subject are drawn as outlines rather than as a paler fill.
/// A paler fill would mean a colour between the ink and the ground, and there is no role
/// for "the ink, quieter": the vocabulary has fills and it has inks, and half of an ink is
/// a literal. An outline is the same distinction said in stroke width, which every design
/// system can draw and no design system has to invent a colour for.
pub fn week(width: f32, height: f32, bars: &[Bar]) -> DrawList {
    if bars.is_empty() {
        return DrawListBuilder::with_capacity(0, 0).build();
    }
    let label_band = height * 0.22;
    let plot = height - label_band;
    let baseline = plot + label_band * 0.62;
    let slot = width / bars.len() as f32;
    let label_inset = (slot / 2.0 - DAY_HALF_WIDTH).max(0.0);
    let bar_width = slot * 0.46;
    let radius = bar_width / 2.0;

    let mut list = DrawListBuilder::with_capacity(bars.len() * 4, bars.len() * 6);
    for (index, bar) in bars.iter().enumerate() {
        let column = bar.height.clamp(0.0, 1.0) * plot;
        let column = column.max(bar_width);
        let x = index as f32 * slot + (slot - bar_width) / 2.0;

        if bar.filled {
            list = list.round_rect(
                Paint::Literal(crate::palette::INK),
                x,
                plot - column,
                bar_width,
                column,
                radius,
                0.0,
            );
        } else {
            // Outline
            list = list.round_rect(
                Paint::Literal(crate::palette::GREY),
                x,
                plot - column,
                bar_width,
                column,
                radius,
                1.0,
            );
            // Hatched lines
            let step = 6.0;
            let mut y = plot - column;
            while y < plot + bar_width {
                let mut y1 = y;
                let mut y2 = y - bar_width;
                let mut x1 = x;
                let mut x2 = x + bar_width;

                if y2 < plot - column {
                    let diff = (plot - column) - y2;
                    y2 += diff;
                    x2 -= diff;
                }
                if y1 > plot {
                    let diff = y1 - plot;
                    y1 -= diff;
                    x1 += diff;
                }

                if x1 < x2 {
                    list = list.line(Paint::Literal(crate::palette::GREY), x1, y1, x2, y2, 1.0);
                }
                y += step;
            }
        }

        list = list.text_at(
            Paint::Literal(if bar.filled {
                crate::palette::INK
            } else {
                crate::palette::GREY
            }),
            bar.day,
            index as f32 * slot + label_inset,
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

    #[test]
    fn fr16_a_dial_draws_a_tick_for_every_step_and_one_marker() {
        let list = dial(
            200.0,
            0.55,
            Paint::Literal(crate::palette::GREY),
            Paint::Literal(crate::palette::ORANGE),
        );
        let commands = list.decode().expect("the dial did not decode");
        assert_eq!(commands.len(), TICKS + 2);
        // Two circles, and only one of them is the reading: the other is the pale disc
        // the number sits on. They are told apart by their paint, which is the only
        // thing that distinguishes them in the list.
        let markers = commands
            .iter()
            .filter(|command| {
                matches!(command, DrawCommand::Circle { .. })
                    && command.paint() == Paint::Literal(crate::palette::ORANGE)
            })
            .count();
        assert_eq!(markers, 1, "a dial has one reading on it");
    }

    /// The marker is the reading. Named for what it defends: the dial used to draw twelve
    /// identical ticks and nothing else, so the number in the middle was the only place
    /// the reading existed and the picture showed nothing.
    ///
    /// The reference puts a dot on the ring rather than an arc along it, so what has to
    /// hold is where the dot sits: a quarter of the way round is a quarter turn from the
    /// top, clockwise.
    #[test]
    fn fr16_the_marker_sits_at_the_fraction_the_dial_reads() {
        const SIZE: f32 = 200.0;
        let middle = SIZE / 2.0;
        for (fraction, degrees) in [(0.0_f32, 0.0_f32), (0.25, 90.0), (0.55, 198.0)] {
            let commands = dial(
                SIZE,
                fraction,
                Paint::Literal(crate::palette::GREY),
                Paint::Literal(crate::palette::ORANGE),
            )
            .decode()
            .expect("the dial did not decode");
            let (x, y) = commands
                .iter()
                .find_map(|command| match command {
                    DrawCommand::Circle {
                        center_x, center_y, ..
                    } if command.paint() == Paint::Literal(crate::palette::ORANGE) => {
                        Some((*center_x, *center_y))
                    }
                    _ => None,
                })
                .unwrap_or_else(|| panic!("a dial reading {fraction} draws no marker"));
            // Back out the angle the marker was placed at. atan2 answers from the right,
            // and the dial starts at the top, so a quarter turn is added back.
            let measured = (y - middle).atan2(x - middle).to_degrees() + 90.0;
            let measured = (measured + 360.0) % 360.0;
            assert!(
                (measured - degrees).abs() < 0.01,
                "a dial reading {fraction} puts its marker {measured} round rather than \
                 {degrees}"
            );
        }
    }

    /// A reading of nothing still puts the marker somewhere, and the somewhere is the
    /// top. Leaving it off instead would make an empty dial and a broken dial look alike.
    #[test]
    fn fr16_a_dial_reading_nothing_puts_its_marker_at_the_top() {
        const SIZE: f32 = 200.0;
        let commands = dial(
            SIZE,
            0.0,
            Paint::Literal(crate::palette::GREY),
            Paint::Literal(crate::palette::ORANGE),
        )
        .decode()
        .expect("the dial did not decode");
        let (x, y) = commands
            .iter()
            .find_map(|command| match command {
                DrawCommand::Circle {
                    center_x, center_y, ..
                } if command.paint() == Paint::Literal(crate::palette::ORANGE) => {
                    Some((*center_x, *center_y))
                }
                _ => None,
            })
            .expect("an empty dial draws no marker");
        assert!(
            (x - SIZE / 2.0).abs() < 0.01 && y < SIZE / 2.0,
            "an empty dial put its marker at ({x}, {y}) rather than at the top"
        );
    }

    /// A chart may only reach for the colours the screen around it uses. This sample
    /// names its reference's own palette rather than speaking in roles, so the guarantee a
    /// role used to give, that no drawing invents a shade of its own, is given here
    /// instead.
    #[test]
    fn fr13_no_command_in_either_chart_carries_a_colour_the_palette_does_not_hold() {
        let charts = [
            dial(
                200.0,
                0.55,
                Paint::Literal(crate::palette::GREY),
                Paint::Literal(crate::palette::ORANGE),
            ),
            week(
                300.0,
                160.0,
                &[
                    Bar {
                        day: "Mon",
                        height: 0.3,
                        filled: false,
                    },
                    Bar {
                        day: "Tue",
                        height: 1.0,
                        filled: true,
                    },
                ],
            ),
        ];
        // Every colour the two drawings are allowed to reach for. A chart that invents
        // one outside this list is the failure the old role rule was catching: a shade
        // that belongs to neither the reference nor the palette, arrived at inside a
        // drawing routine where nobody would look for it.
        let allowed = [
            crate::palette::INK,
            crate::palette::GREY,
            crate::palette::LIGHT_GREY,
            crate::palette::ORANGE,
        ];
        for chart in charts {
            for command in chart.decode().expect("a chart did not decode") {
                let Paint::Literal(colour) = command.paint() else {
                    panic!(
                        "{command:?} is painted with a role. This sample draws its \
                         reference's own colours, so every colour in it is named here \
                         rather than left to whichever design system is active."
                    )
                };
                assert!(
                    allowed.contains(&colour),
                    "{command:?} is painted #{:06x}, which the sample's palette does not \
                     hold. A drawing may only use the colours the screen around it uses.",
                    colour.0 & 0x00ff_ffff
                );
            }
        }
    }

    /// A day with nothing spent on it still has to be a column somebody can see. A bar
    /// scaled straight from zero is a one pixel line, which reads as a fault.
    #[test]
    fn fr16_a_quiet_day_is_still_drawn_as_a_column() {
        let list = week(
            300.0,
            160.0,
            &[Bar {
                day: "Mon",
                height: 0.0,
                filled: false,
            }],
        );
        let commands = list.decode().expect("the week did not decode");
        let drawn = commands
            .iter()
            .find_map(|command| match command {
                DrawCommand::RoundRect { height, width, .. } => Some((width, height)),
                _ => None,
            })
            .expect("no column was drawn at all");
        assert!(
            drawn.1 >= drawn.0,
            "a column of {} by {} is thinner than it is tall",
            drawn.0,
            drawn.1
        );
    }

    /// An empty week is an empty drawing rather than a panic or a division by zero.
    #[test]
    fn a_week_with_no_days_draws_nothing() {
        let list = week(300.0, 160.0, &[]);
        assert!(list.decode().expect("it did not decode").is_empty());
    }
}
