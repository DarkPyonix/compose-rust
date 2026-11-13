//! Today's date in the user's time zone, read by the Host with no boundary call.
//!
//! One test in its own process, because it sets `TZ`, which every thread in a process
//! shares.

use compose_rust::time::{LocalDate, Weekday, local_at, now_local, today};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// 2026-03-01T20:00:00Z.
const SUNDAY_EVENING_UTC: u64 = 1_772_395_200;
/// 2026-03-02T05:00:00Z.
const MONDAY_MORNING_UTC: u64 = 1_772_427_600;

fn set_time_zone(zone: &str) {
    // SAFETY: this process runs this one test, on one thread, and nothing else reads the
    // environment while it is being changed.
    unsafe { std::env::set_var("TZ", zone) };
}

/// In a time zone nine hours ahead of UTC, 20:00 UTC on a Sunday is 05:00 on Monday, and
/// in one ten hours behind, 05:00 UTC on a Monday is 19:00 on Sunday: the date and the
/// weekday are the time zone's, not UTC's. `today()` is the date the clock shows there now.
#[test]
fn fr84_today_in_the_user_time_zone() {
    set_time_zone("KST-9");
    let seoul = local_at(UNIX_EPOCH + Duration::from_secs(SUNDAY_EVENING_UTC));
    assert_eq!(
        seoul.date,
        LocalDate {
            year: 2026,
            month: 3,
            day: 2,
            weekday: Weekday::Monday,
        }
    );
    assert_eq!((seoul.hour, seoul.minute, seoul.second), (5, 0, 0));
    assert_eq!(seoul.utc_offset_seconds, 9 * 3600);

    // Now, read twice around today() so a midnight in between cannot fail the test.
    let seoul_days = |instant: SystemTime| {
        let seconds = instant.duration_since(UNIX_EPOCH).unwrap().as_secs() + 9 * 3600;
        (seconds / 86_400) as i64
    };
    let before = SystemTime::now();
    let date = today();
    let after = SystemTime::now();
    assert!(
        date.days_since_epoch() == seoul_days(before)
            || date.days_since_epoch() == seoul_days(after),
        "today() is {date:?}, which is not the date nine hours ahead of UTC"
    );
    let now = now_local();
    assert_eq!(now.utc_offset_seconds, 9 * 3600);

    set_time_zone("HST10");
    let honolulu = local_at(UNIX_EPOCH + Duration::from_secs(MONDAY_MORNING_UTC));
    assert_eq!(
        honolulu.date,
        LocalDate {
            year: 2026,
            month: 3,
            day: 1,
            weekday: Weekday::Sunday,
        }
    );
    assert_eq!((honolulu.hour, honolulu.minute), (19, 0));
    assert_eq!(honolulu.utc_offset_seconds, -10 * 3600);
    assert_eq!(honolulu.date.weekday.number_from_monday(), 7);

    // The calendar holds on either side of a leap day and before 1970.
    let leap = local_at(UNIX_EPOCH + Duration::from_secs(1_709_164_800 + 12 * 3600));
    assert_eq!(
        (leap.date.year, leap.date.month, leap.date.day),
        (2024, 2, 29)
    );
    let before_epoch = local_at(UNIX_EPOCH - Duration::from_secs(86_400 * 365));
    assert_eq!(
        (
            before_epoch.date.year,
            before_epoch.date.month,
            before_epoch.date.day
        ),
        (1968, 12, 31)
    );
}
