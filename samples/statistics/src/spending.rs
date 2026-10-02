//! The numbers the screen is about.
//!
//! Money is whole cents and never a float, so the totals on two screens agree.

/// What one service cost over the week.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Source {
    pub name: &'static str,
    /// Cents, always a cost, so it is written with a minus in front of it.
    pub cents: u32,
}

pub const SOURCES: [Source; 5] = [
    Source {
        name: "TikTok",
        cents: 1200,
    },
    Source {
        name: "GitHub",
        cents: 2400,
    },
    Source {
        name: "Dropbox",
        cents: 1100,
    },
    Source {
        name: "Figma",
        cents: 1500,
    },
    Source {
        name: "Notion",
        cents: 800,
    },
];

/// What a day of the working week cost, in cents.
pub const WEEK: [(&str, u32); 5] = [
    ("Mon", 1800),
    ("Tue", 6400),
    ("Wed", 1200),
    ("Thu", 2600),
    ("Fri", 5200),
];

/// Which day the chart is about: the dearest one, which is the day worth looking at.
pub fn dearest() -> usize {
    WEEK.iter()
        .enumerate()
        .max_by_key(|(_, (_, cents))| *cents)
        .map_or(0, |(index, _)| index)
}

/// The tallest day, which everything else is drawn against.
pub fn peak() -> u32 {
    WEEK.iter().map(|(_, cents)| *cents).max().unwrap_or(0)
}

/// Everything spent since the beginning, which is far more than this week.
pub const ALL_TIME_CENTS: u32 = 119_250;

/// The share of readers on a phone, in whole percent.
pub const MOBILE_SHARE: u32 = 55;
/// The share that came back a second time.
pub const RETURNING_SHARE: u32 = 45;

/// How many things happened today, and how many yesterday.
pub const TODAY: u32 = 19;
pub const YESTERDAY: u32 = 53;

/// A cost, written the way a statement writes one: a minus, then the amount.
pub fn cost(cents: u32) -> String {
    format!("-{}", amount(cents))
}

/// An amount in dollars and cents, with a separator every three digits.
pub fn amount(cents: u32) -> String {
    let dollars = cents / 100;
    let digits = dollars.to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3 + 4);
    out.push('$');
    for (position, digit) in digits.chars().enumerate() {
        if position > 0 && (digits.len() - position) % 3 == 0 {
            out.push(',');
        }
        out.push(digit);
    }
    out.push('.');
    out.push_str(&format!("{:02}", cents % 100));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_amount_separates_thousands_and_keeps_both_cents() {
        assert_eq!(amount(119_250), "$1,192.50");
        assert_eq!(amount(1200), "$12.00");
        assert_eq!(amount(5), "$0.05");
        assert_eq!(amount(0), "$0.00");
    }

    #[test]
    fn a_cost_is_written_with_a_minus_in_front_of_it() {
        assert_eq!(cost(1200), "-$12.00");
    }

    /// The chart marks the day worth looking at, so "the day worth looking at" has to be
    /// worked out from the numbers rather than written down beside them, where the two
    /// would drift apart the first time a figure changed.
    #[test]
    fn fr16_the_marked_day_is_the_dearest_one() {
        let marked = dearest();
        assert_eq!(WEEK[marked].0, "Tue");
        assert_eq!(WEEK[marked].1, peak());
        for (index, (day, cents)) in WEEK.iter().enumerate() {
            assert!(
                index == marked || *cents < peak(),
                "{day} is as dear as the marked day, so the mark is arbitrary"
            );
        }
    }

    /// Nothing on this screen is a float, so nothing on it rounds differently on two
    /// runs.
    #[test]
    fn the_sources_add_up_to_a_whole_number_of_cents() {
        let sum: u32 = SOURCES.iter().map(|source| source.cents).sum();
        assert_eq!(sum, 7000);
        assert_eq!(amount(sum), "$70.00");
    }
}
