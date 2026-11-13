//! The drawings: a dial, a week of columns, and the chevron in the bar.
//!
//! All are `DrawList`s rather than widgets, because a chart is pixels the widget
//! vocabulary has no name for, and a downward chevron is a mark no `IconRole` means.
//!
//! The colours are this sample's own rather than the design system's. The reference names
//! a white dial, a sage panel, a black column and an orange marker, and a role cannot say
//! any of them: whatever system is active would answer with its own accent instead. So
//! the drawings read the sample's palette, and the tests below hold them to it.

use crate::palette;
use compose_rust::ui::{DrawList, DrawListBuilder, Paint, TypeRole};

/// Where the ticks start and stop, as a share of the dial's radius.
const TICK_FROM: f32 = 0.73;
const TICK_TO: f32 = 0.84;
/// The disc in the middle that the reading sits on, as a share of the dial's radius.
const HUB: f32 = 0.56;
/// How many ticks go round the dial: one at every eighth of a turn, which is what the
/// reference draws.
pub const TICKS: usize = 8;

/// A dial reading `fraction` of the way round: a white face, a ring of ticks, a disc in
/// the page's colour for the number to sit on, and a marker where the reading ends.
///
/// The face is part of the drawing rather than a card behind it. The reference puts a
/// white circle straight on the page, and a white rectangle under the ticks is a card the
/// picture does not have.
///
/// The marker is the reading. Without it the dial would announce a number in the middle
/// and draw nothing that showed it. A dot on the tick ring rather than an arc along it,
/// because that is what the reference puts there.
///
/// Angles are measured from the top and go clockwise, so the marker and the ticks agree
/// about where zero is.
pub fn dial(size: f32, fraction: f32, ticks: Paint, marker: Paint) -> DrawList {
    let middle = size / 2.0;
    let face = middle;
    let from = face * TICK_FROM;
    let to = face * TICK_TO;
    let reading = (from + to) / 2.0;
    let fraction = fraction.clamp(0.0, 1.0);
    let mut list = DrawListBuilder::with_capacity(TICKS + 3, 0)
        .circle(palette::CARD, middle, middle, face, 0.0)
        .circle(palette::PAGE, middle, middle, face * HUB, 0.0);
    for step in 0..TICKS {
        let angle =
            (step as f32 / TICKS as f32) * std::f32::consts::TAU - std::f32::consts::FRAC_PI_2;
        let (sin, cos) = angle.sin_cos();
        list = list.line(
            ticks,
            middle + cos * from,
            middle + sin * from,
            middle + cos * to,
            middle + sin * to,
            size * 0.008,
        );
    }
    let angle = fraction * std::f32::consts::TAU - std::f32::consts::FRAC_PI_2;
    let (sin, cos) = angle.sin_cos();
    list.circle(
        marker,
        middle + cos * reading,
        middle + sin * reading,
        size * 0.028,
        0.0,
    )
    .build()
}

/// A thin chevron pointing down, centred in a `size` square: the control beside the
/// title. Two strokes and a dot where they meet, so the point is round rather than two
/// square ends overlapping.
pub fn chevron_down(size: f32, ink: Paint) -> DrawList {
    let stroke = (size * 0.08).max(1.5);
    let (left, right) = (size * 0.2, size * 0.8);
    let (top, point) = (size * 0.36, size * 0.64);
    let middle = size / 2.0;
    DrawListBuilder::with_capacity(3, 0)
        .line(ink, left, top, middle, point, stroke)
        .line(ink, middle, point, right, top, stroke)
        .circle(ink, middle, point, stroke / 2.0, 0.0)
        .build()
}

/// A plus, centred in a `size` square: the face of the round button.
pub fn plus(size: f32, ink: Paint) -> DrawList {
    let stroke = (size * 0.06).max(1.5);
    let (near, far) = (size * 0.25, size * 0.75);
    let middle = size / 2.0;
    DrawListBuilder::with_capacity(2, 0)
        .line(ink, near, middle, far, middle, stroke)
        .line(ink, middle, near, middle, far, stroke)
        .build()
}

/// Half of what a three letter day comes to at the caption rung, in dp.
///
/// A guess, and it has to be: the Host has no font and the draw list carries no measured
/// width. It is only used to inset a label so it reads as centred under its column.
const DAY_HALF_WIDTH: f32 = 11.0;

/// How tall a day label's line is: the tallest caption line any design system sets.
///
/// A label is drawn with its top-left corner at the point it is given, not its baseline,
/// so the line has to fit between that point and the bottom of the canvas. The tallest
/// rung keeps it inside whichever system draws it.
fn day_line_height() -> f32 {
    compose_rust::tokens::DESIGN_TOKENS
        .iter()
        .flat_map(|table| table.type_scale.iter())
        .filter(|token| token.role == TypeRole::Caption)
        .map(|token| token.line_height)
        .fold(0.0, f32::max)
}

/// How much of its slot a column takes. The reference's columns are wide, with a narrow
/// gap between them.
const COLUMN_SHARE: f32 = 0.8;
/// How round a column's corners are, as a share of its width.
const CORNER_SHARE: f32 = 0.26;

/// One column of the week's chart.
#[derive(Clone, Copy, Debug)]
pub struct Bar {
    pub day: &'static str,
    /// Nought to one, against the tallest column of the week.
    pub height: f32,
    /// The one column the chart is about. The rest are hatched.
    pub filled: bool,
}

/// Where one column sits in the chart's box.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Column {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    pub radius: f32,
}

impl Column {
    /// True when the point is inside the rounded column, give or take `slack`.
    #[cfg(test)]
    fn holds(&self, x: f32, y: f32, slack: f32) -> bool {
        let r = self.radius;
        let nearest_x = x.clamp(self.x + r, self.x + self.width - r);
        let nearest_y = y.clamp(self.y + r, self.y + self.height - r);
        let (dx, dy) = (x - nearest_x, y - nearest_y);
        (dx * dx + dy * dy).sqrt() <= r + slack
    }
}

/// The box above the day labels that the columns stand in, and the columns in it.
fn layout(width: f32, height: f32, bars: &[Bar]) -> (f32, Vec<Column>) {
    let plot = height * 0.82;
    let slot = width / bars.len().max(1) as f32;
    let column_width = slot * COLUMN_SHARE;
    let columns = bars
        .iter()
        .enumerate()
        .map(|(index, bar)| {
            // A day with nothing spent on it is still a column somebody can see. A bar
            // scaled straight from zero is a one pixel line, which reads as a fault.
            let tall = (bar.height.clamp(0.0, 1.0) * plot).max(column_width);
            Column {
                x: index as f32 * slot + (slot - column_width) / 2.0,
                y: plot - tall,
                width: column_width,
                height: tall,
                radius: column_width * CORNER_SHARE,
            }
        })
        .collect();
    (plot, columns)
}

/// A week of columns with the day under each.
///
/// `width` is the box the drawing is given, not a box of its own choosing: a draw list is
/// in the canvas's own coordinates and knows nothing about how wide the canvas turned out.
/// The caller passes the same number to both.
///
/// The dearest day is a solid ink column. The others are hatched with diagonal stripes in
/// a darker sage and have no outline, which is how the reference tells them apart. The
/// stripes are cut to each column's rounded shape here, in the drawing, so none of them
/// runs past a rounded corner.
pub fn week(width: f32, height: f32, bars: &[Bar]) -> DrawList {
    if bars.is_empty() {
        return DrawListBuilder::with_capacity(0, 0).build();
    }
    let (plot, columns) = layout(width, height, bars);
    let slot = width / bars.len() as f32;
    let label_inset = (slot / 2.0 - DAY_HALF_WIDTH).max(0.0);
    // The label's line is centred in the band under the columns. The point is the line's
    // top, so a band too short for the line still keeps its bottom on the canvas.
    let line = day_line_height();
    let label_top = (plot + ((height - plot) - line) / 2.0).min(height - line);

    let mut list = DrawListBuilder::with_capacity(bars.len() * 24, bars.len() * 6);
    for (index, (bar, column)) in bars.iter().zip(&columns).enumerate() {
        if bar.filled {
            list = list.round_rect(
                palette::INK,
                column.x,
                column.y,
                column.width,
                column.height,
                column.radius,
                0.0,
            );
        } else {
            for (x1, y1, x2, y2, stroke) in stripes(column) {
                list = list.line(palette::HATCH, x1, y1, x2, y2, stroke);
            }
        }
        list = list.text_at(
            palette::SAGE_INK,
            bar.day,
            index as f32 * slot + label_inset,
            label_top,
            TypeRole::Caption,
        );
    }
    list.build()
}

/// The diagonal stripes across one hatched column, rising to the right, each cut to the
/// column's rounded outline.
///
/// The outline the stripes are cut against is pulled in by half a stroke, so a stripe's
/// own width does not carry its ends past the edge either.
fn stripes(column: &Column) -> Vec<(f32, f32, f32, f32, f32)> {
    let period = column.width * 0.22;
    let stroke = period * 0.45;
    let half = stroke / 2.0;
    let inner = Column {
        x: column.x + half,
        y: column.y + half,
        width: column.width - stroke,
        height: column.height - stroke,
        radius: (column.radius - half).max(0.0),
    };
    // Lines x + y = c. Stepping c by the period times the square root of two puts the
    // stripes one period apart measured across them.
    let step = period * std::f32::consts::SQRT_2;
    let (left, right) = (column.x, column.x + column.width);
    let mut out = Vec::new();
    let mut c = left + column.y;
    while c <= right + column.y + column.height {
        let from = (left, c - left);
        let to = (right, c - right);
        // A stripe that only grazes a corner is a dot, not a stripe, so it is left out.
        let crossing =
            clip_to_column(from, to, &inner).filter(|(enter, leave)| leave - enter > 1e-3);
        if let Some((enter, leave)) = crossing {
            let at = |t: f32| (from.0 + (to.0 - from.0) * t, from.1 + (to.1 - from.1) * t);
            let (a, b) = (at(enter), at(leave));
            out.push((a.0, a.1, b.0, b.1, stroke));
        }
        c += step;
    }
    out
}

/// The part of the segment from `a` to `b` inside a rounded column, as the two fractions
/// along the segment where it goes in and comes out.
///
/// A rounded rectangle is convex, so a line crosses it in one piece. It is also the union
/// of two rectangles (one cut in at the sides, one at the top and bottom) and four corner
/// discs, so the piece is the widest span any of those six gives.
fn clip_to_column(a: (f32, f32), b: (f32, f32), column: &Column) -> Option<(f32, f32)> {
    let Column {
        x,
        y,
        width,
        height,
        ..
    } = *column;
    let r = column.radius.min(width / 2.0).min(height / 2.0);
    let mut span = None;
    widen(
        &mut span,
        clip_to_rect(a, b, (x, y + r, x + width, y + height - r)),
    );
    widen(
        &mut span,
        clip_to_rect(a, b, (x + r, y, x + width - r, y + height)),
    );
    for centre in [
        (x + r, y + r),
        (x + width - r, y + r),
        (x + r, y + height - r),
        (x + width - r, y + height - r),
    ] {
        widen(&mut span, clip_to_disc(a, b, centre, r));
    }
    span
}

fn widen(span: &mut Option<(f32, f32)>, piece: Option<(f32, f32)>) {
    if let Some((enter, leave)) = piece {
        *span = Some(match *span {
            None => (enter, leave),
            Some((first, last)) => (first.min(enter), last.max(leave)),
        });
    }
}

/// Liang and Barsky's clip of a segment to an upright rectangle.
fn clip_to_rect(
    a: (f32, f32),
    b: (f32, f32),
    (left, top, right, bottom): (f32, f32, f32, f32),
) -> Option<(f32, f32)> {
    if left > right || top > bottom {
        return None;
    }
    let (dx, dy) = (b.0 - a.0, b.1 - a.1);
    let (mut enter, mut leave) = (0.0_f32, 1.0_f32);
    for (p, q) in [
        (-dx, a.0 - left),
        (dx, right - a.0),
        (-dy, a.1 - top),
        (dy, bottom - a.1),
    ] {
        if p == 0.0 {
            if q < 0.0 {
                return None;
            }
            continue;
        }
        let t = q / p;
        if p < 0.0 {
            enter = enter.max(t);
        } else {
            leave = leave.min(t);
        }
    }
    (enter <= leave).then_some((enter, leave))
}

/// Where a segment runs inside a disc.
fn clip_to_disc(a: (f32, f32), b: (f32, f32), (cx, cy): (f32, f32), r: f32) -> Option<(f32, f32)> {
    if r <= 0.0 {
        return None;
    }
    let (dx, dy) = (b.0 - a.0, b.1 - a.1);
    let (fx, fy) = (a.0 - cx, a.1 - cy);
    let qa = dx * dx + dy * dy;
    if qa == 0.0 {
        return None;
    }
    let qb = 2.0 * (fx * dx + fy * dy);
    let qc = fx * fx + fy * fy - r * r;
    let discriminant = qb * qb - 4.0 * qa * qc;
    if discriminant < 0.0 {
        return None;
    }
    let root = discriminant.sqrt();
    let enter = ((-qb - root) / (2.0 * qa)).max(0.0);
    let leave = ((-qb + root) / (2.0 * qa)).min(1.0);
    (enter <= leave).then_some((enter, leave))
}

#[cfg(test)]
mod tests {
    use super::*;
    use compose_rust::ui::DrawCommand;

    fn sample_dial(size: f32, fraction: f32) -> Vec<DrawCommand> {
        dial(size, fraction, palette::TICK, palette::ORANGE)
            .decode()
            .expect("the dial did not decode")
    }

    #[test]
    fn fr16_a_dial_draws_a_tick_for_every_step_and_one_marker() {
        let commands = sample_dial(200.0, 0.55);
        assert_eq!(commands.len(), TICKS + 3);
        let markers = commands
            .iter()
            .filter(|command| {
                matches!(command, DrawCommand::Circle { .. }) && command.paint() == palette::ORANGE
            })
            .count();
        assert_eq!(markers, 1, "a dial has one reading on it");
    }

    /// Eight ticks, one at every eighth of a turn, in the light grey the reference draws
    /// them in. The dial had twelve, in a darker grey, as though it were a clock face.
    #[test]
    fn fr22_the_dial_has_the_eight_light_ticks_the_reference_draws() {
        let ticks: Vec<DrawCommand> = sample_dial(200.0, 0.55)
            .into_iter()
            .filter(|command| matches!(command, DrawCommand::Line { .. }))
            .collect();
        assert_eq!(ticks.len(), 8);
        for tick in ticks {
            assert_eq!(tick.paint(), palette::TICK);
        }
    }

    /// The dial is a white disc on the page, not ticks on a white rectangle: its first
    /// command fills the whole square's circle in the card colour, and the disc the
    /// reading sits on is the page's colour, as in the reference.
    #[test]
    fn fr22_the_dial_is_a_white_disc_with_a_page_coloured_hub() {
        const SIZE: f32 = 200.0;
        let commands = sample_dial(SIZE, 0.55);
        let DrawCommand::Circle {
            paint,
            radius,
            stroke_width,
            ..
        } = commands[0]
        else {
            panic!(
                "the dial starts with {:?} rather than its face",
                commands[0]
            );
        };
        assert_eq!(paint, palette::CARD);
        assert_eq!(radius, SIZE / 2.0);
        assert_eq!(stroke_width, 0.0, "the face is filled, not outlined");
        let DrawCommand::Circle { paint, radius, .. } = commands[1] else {
            panic!("the dial has no hub");
        };
        assert_eq!(paint, palette::PAGE);
        assert!(radius < SIZE / 2.0 * TICK_FROM, "the hub covers the ticks");
    }

    /// The marker is the reading: a quarter of the way round is a quarter turn from the
    /// top, clockwise, and fifty-five per cent lands just past six o'clock as in the
    /// reference.
    #[test]
    fn fr16_the_marker_sits_at_the_fraction_the_dial_reads() {
        const SIZE: f32 = 200.0;
        let middle = SIZE / 2.0;
        for (fraction, degrees) in [(0.0_f32, 0.0_f32), (0.25, 90.0), (0.55, 198.0)] {
            let (x, y) = sample_dial(SIZE, fraction)
                .iter()
                .find_map(|command| match command {
                    DrawCommand::Circle {
                        center_x, center_y, ..
                    } if command.paint() == palette::ORANGE => Some((*center_x, *center_y)),
                    _ => None,
                })
                .unwrap_or_else(|| panic!("a dial reading {fraction} draws no marker"));
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
        let (x, y) = sample_dial(SIZE, 0.0)
            .iter()
            .find_map(|command| match command {
                DrawCommand::Circle {
                    center_x, center_y, ..
                } if command.paint() == palette::ORANGE => Some((*center_x, *center_y)),
                _ => None,
            })
            .expect("an empty dial draws no marker");
        assert!(
            (x - SIZE / 2.0).abs() < 0.01 && y < SIZE / 2.0,
            "an empty dial put its marker at ({x}, {y}) rather than at the top"
        );
    }

    fn sample_week() -> Vec<Bar> {
        vec![
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
            Bar {
                day: "Wed",
                height: 0.05,
                filled: false,
            },
        ]
    }

    /// A drawing may only reach for the colours the screen around it uses, and every one
    /// of those is a literal from the palette.
    #[test]
    fn fr22_every_drawing_is_painted_in_the_palette() {
        let drawings = [
            dial(200.0, 0.55, palette::TICK, palette::ORANGE),
            week(300.0, 160.0, &sample_week()),
            chevron_down(24.0, palette::INK),
            plus(40.0, palette::CARD),
        ];
        for drawing in drawings {
            for command in drawing.decode().expect("a drawing did not decode") {
                let paint = command.paint();
                assert!(
                    matches!(paint, Paint::Literal(_)),
                    "{command:?} is painted with a role, which the design system would pick"
                );
                assert!(
                    palette::ALL.contains(&paint),
                    "{command:?} is painted in a colour the sample's palette does not hold"
                );
            }
        }
    }

    /// The hatched columns are stripes and nothing else. The reference draws no outline
    /// round them; the one round rectangle in the chart is the dearest day's.
    #[test]
    fn fr22_a_hatched_column_has_no_outline() {
        let commands = week(300.0, 160.0, &sample_week())
            .decode()
            .expect("the week did not decode");
        let solid: Vec<&DrawCommand> = commands
            .iter()
            .filter(|command| matches!(command, DrawCommand::RoundRect { .. }))
            .collect();
        assert_eq!(solid.len(), 1, "only the dearest day is a drawn shape");
        assert_eq!(solid[0].paint(), palette::INK);
        assert!(
            commands
                .iter()
                .any(|command| matches!(command, DrawCommand::Line { .. })
                    && command.paint() == palette::HATCH),
            "the other days carry no stripes"
        );
    }

    /// No stripe runs past its column's rounded corners. They used to be cut to the
    /// column's rectangle, so their ends stood out beyond the round top and bottom.
    #[test]
    fn fr22_every_stripe_stays_inside_its_rounded_column() {
        let bars = sample_week();
        let (_, columns) = layout(300.0, 160.0, &bars);
        for (bar, column) in bars.iter().zip(&columns) {
            if bar.filled {
                continue;
            }
            let drawn = stripes(column);
            assert!(!drawn.is_empty(), "{} has no stripes", bar.day);
            for (x1, y1, x2, y2, stroke) in drawn {
                // The centre line of the stroke stays half a stroke inside the outline, give
                // or take a twentieth of a dp of rounding.
                let slack = 0.05 - stroke / 2.0;
                assert!(
                    column.holds(x1, y1, slack) && column.holds(x2, y2, slack),
                    "a stripe on {} runs from ({x1}, {y1}) to ({x2}, {y2}), outside {column:?}",
                    bar.day
                );
            }
        }
    }

    /// Every day's label sits whole inside the canvas, under the columns. A label is drawn
    /// from its top-left corner, and the week used to place it as though the point were
    /// its baseline, so the lower half of each day ran off the bottom of the sage card.
    #[test]
    fn fr22_the_day_labels_sit_inside_the_chart_under_the_columns() {
        let line = day_line_height();
        assert!(line >= 15.0, "no design system sets a caption line");
        for (width, height) in [(200.0_f32, 170.0_f32), (340.0, 280.0), (300.0, 160.0)] {
            let (plot, _) = layout(width, height, &sample_week());
            let commands = week(width, height, &sample_week())
                .decode()
                .expect("the week did not decode");
            let labels: Vec<(f32, f32)> = commands
                .iter()
                .filter_map(|command| match command {
                    DrawCommand::TextAt { x, y, .. } => Some((*x, *y)),
                    _ => None,
                })
                .collect();
            assert_eq!(labels.len(), sample_week().len(), "a day has no label");
            for (x, y) in labels {
                assert!(
                    y >= plot,
                    "a label at {y} in a {width} by {height} chart overlaps the columns, \
                     which end at {plot}"
                );
                assert!(
                    y + line <= height,
                    "a label from {y} to {} runs past the bottom of a {width} by {height} \
                     chart",
                    y + line
                );
                assert!(
                    (0.0..width).contains(&x),
                    "a label at x {x} is off a chart {width} wide"
                );
            }
        }
    }

    /// A day with nothing spent on it still has to be a column somebody can see.
    #[test]
    fn fr16_a_quiet_day_is_still_drawn_as_a_column() {
        let bars = [Bar {
            day: "Mon",
            height: 0.0,
            filled: false,
        }];
        let (_, columns) = layout(300.0, 160.0, &bars);
        assert!(
            columns[0].height >= columns[0].width,
            "a column of {} by {} is thinner than it is tall",
            columns[0].width,
            columns[0].height
        );
        assert!(
            !stripes(&columns[0]).is_empty(),
            "the quiet day is not drawn"
        );
    }

    /// An empty week is an empty drawing rather than a panic or a division by zero.
    #[test]
    fn a_week_with_no_days_draws_nothing() {
        let list = week(300.0, 160.0, &[]);
        assert!(list.decode().expect("it did not decode").is_empty());
    }

    /// The marks stay inside the square they are given, or a canvas that size cuts them.
    #[test]
    fn the_chevron_and_the_plus_stay_inside_their_square() {
        for (size, drawing) in [
            (24.0, chevron_down(24.0, palette::INK)),
            (40.0, plus(40.0, palette::CARD)),
        ] {
            for command in drawing.decode().expect("a mark did not decode") {
                if let DrawCommand::Line { x1, y1, x2, y2, .. } = command {
                    for value in [x1, y1, x2, y2] {
                        assert!(
                            (0.0..=size).contains(&value),
                            "{command:?} leaves its square"
                        );
                    }
                }
            }
        }
    }
}
