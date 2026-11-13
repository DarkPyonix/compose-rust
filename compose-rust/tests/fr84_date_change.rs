//! The date change wake, through a real local midnight.
//!
//! One test in its own process, because it sets `TZ`, which every thread in a process
//! shares. The time zone is made up so that local midnight is three seconds away.

use compose_rust::time::{now_local, subscribe_date_change, today};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// A POSIX time zone in which the wall clock reads 23:59:57 now. Its offset is whole
/// seconds, which POSIX allows, and within twelve hours of UTC.
fn zone_three_seconds_before_midnight() -> String {
    let into_utc_day = (SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
        % 86_400) as i64;
    let mut ahead = (86_397 - into_utc_day).rem_euclid(86_400);
    if ahead > 43_200 {
        ahead -= 86_400;
    }
    // POSIX counts west of Greenwich as positive, the opposite of an offset from UTC.
    let sign = if ahead >= 0 { '-' } else { '+' };
    let ahead = ahead.abs();
    format!(
        "MDN{sign}{:02}:{:02}:{:02}",
        ahead / 3600,
        ahead / 60 % 60,
        ahead % 60
    )
}

/// A subscriber is woken once when local midnight passes, and not again after it.
#[test]
fn fr84_date_change_wakes_once() {
    // SAFETY: this process runs this one test, and no other thread exists yet.
    unsafe { std::env::set_var("TZ", zone_three_seconds_before_midnight()) };
    let now = now_local();
    assert_eq!(
        (now.hour, now.minute),
        (23, 59),
        "the made-up time zone puts the clock at {now:?}"
    );
    let before = today();

    let woken = Arc::new(AtomicUsize::new(0));
    let counter = woken.clone();
    let subscription = subscribe_date_change(Arc::new(move || {
        counter.fetch_add(1, Ordering::SeqCst);
    }));

    let deadline = Instant::now() + Duration::from_secs(10);
    while woken.load(Ordering::SeqCst) == 0 && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(50));
    }
    assert_eq!(
        woken.load(Ordering::SeqCst),
        1,
        "midnight passed without a wake"
    );
    assert_eq!(
        today().days_since_epoch(),
        before.days_since_epoch() + 1,
        "the wake came, and the date is the next day"
    );

    // Nothing changes again for a day, so nothing wakes again.
    std::thread::sleep(Duration::from_millis(1500));
    assert_eq!(
        woken.load(Ordering::SeqCst),
        1,
        "one midnight woke more than once"
    );

    // Dropped, it is not called for the next change.
    drop(subscription);
}
