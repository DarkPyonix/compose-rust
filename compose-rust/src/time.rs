//! Today's date and the time of day, in the user's time zone.
//!
//! The Host reads them itself, so nothing about them crosses the boundary. On desktop, iOS
//! and Android the C library converts an instant with the time zone the operating system
//! is set to; a `TZ` in the environment wins, as it does for every program on those
//! systems. In a browser the page's `Date` answers, which is the browser's time zone.
//!
//! [`subscribe_date_change`] wakes a reader once when the date changes: at local midnight,
//! or when the user moves to a time zone where it is already another day.

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// A day of the week.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub enum Weekday {
    Monday,
    Tuesday,
    Wednesday,
    Thursday,
    Friday,
    Saturday,
    Sunday,
}

impl Weekday {
    /// The day `days` days after 1970-01-01, which was a Thursday. Negative goes back.
    fn from_days_since_epoch(days: i64) -> Self {
        // Monday is 0 here, so the epoch's Thursday is 3.
        match (days + 3).rem_euclid(7) {
            0 => Weekday::Monday,
            1 => Weekday::Tuesday,
            2 => Weekday::Wednesday,
            3 => Weekday::Thursday,
            4 => Weekday::Friday,
            5 => Weekday::Saturday,
            _ => Weekday::Sunday,
        }
    }

    /// One for Monday up to seven for Sunday, as ISO 8601 numbers them.
    pub fn number_from_monday(self) -> u8 {
        self as u8 + 1
    }
}

/// A date on the calendar of the user's time zone.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct LocalDate {
    pub year: i32,
    /// One for January up to twelve.
    pub month: u8,
    /// One up to the last day of the month.
    pub day: u8,
    pub weekday: Weekday,
}

impl LocalDate {
    /// The date of the day `days` days after 1970-01-01.
    fn from_days_since_epoch(days: i64) -> Self {
        // Howard Hinnant's civil_from_days: the proleptic Gregorian calendar, with years
        // counted from March so that the leap day is the last day of a year.
        let z = days + 719_468;
        let era = z.div_euclid(146_097);
        let day_of_era = z.rem_euclid(146_097);
        let year_of_era =
            (day_of_era - day_of_era / 1460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
        let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
        let shifted_month = (5 * day_of_year + 2) / 153;
        let day = day_of_year - (153 * shifted_month + 2) / 5 + 1;
        let month = if shifted_month < 10 {
            shifted_month + 3
        } else {
            shifted_month - 9
        };
        let year = year_of_era + era * 400 + i64::from(month <= 2);
        LocalDate {
            year: year as i32,
            month: month as u8,
            day: day as u8,
            weekday: Weekday::from_days_since_epoch(days),
        }
    }

    /// How many days after 1970-01-01 this date is. Negative for a date before it.
    pub fn days_since_epoch(&self) -> i64 {
        let month = i64::from(self.month);
        let year = i64::from(self.year) - i64::from(month <= 2);
        let era = year.div_euclid(400);
        let year_of_era = year.rem_euclid(400);
        let shifted_month = if month > 2 { month - 3 } else { month + 9 };
        let day_of_year = (153 * shifted_month + 2) / 5 + i64::from(self.day) - 1;
        let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
        era * 146_097 + day_of_era - 719_468
    }
}

/// A moment as the clock on the user's wall shows it.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct LocalDateTime {
    pub date: LocalDate,
    /// Zero up to 23.
    pub hour: u8,
    pub minute: u8,
    /// Zero up to 59, or 60 in a leap second where the platform reports one.
    pub second: u8,
    pub nanosecond: u32,
    /// How far ahead of UTC the user's time zone is at this moment, daylight saving
    /// included. Negative west of Greenwich.
    pub utc_offset_seconds: i32,
}

/// Today's date in the user's time zone.
pub fn today() -> LocalDate {
    now_local().date
}

/// The current date and time of day in the user's time zone.
pub fn now_local() -> LocalDateTime {
    local_at(platform::now())
}

/// What the wall clock in the user's time zone showed, or will show, at `instant`.
pub fn local_at(instant: SystemTime) -> LocalDateTime {
    let (seconds, nanosecond) = match instant.duration_since(UNIX_EPOCH) {
        Ok(after) => (after.as_secs() as i64, after.subsec_nanos()),
        Err(before) => {
            let before = before.duration();
            let whole = before.as_secs() as i64;
            match before.subsec_nanos() {
                0 => (-whole, 0),
                part => (-whole - 1, 1_000_000_000 - part),
            }
        }
    };
    let offset = platform::utc_offset_seconds(seconds);
    let local = seconds + i64::from(offset);
    let days = local.div_euclid(86_400);
    let second_of_day = local.rem_euclid(86_400);
    LocalDateTime {
        date: LocalDate::from_days_since_epoch(days),
        hour: (second_of_day / 3600) as u8,
        minute: (second_of_day / 60 % 60) as u8,
        second: (second_of_day % 60) as u8,
        nanosecond,
        utc_offset_seconds: offset,
    }
}

/// How long from `now` until the next local midnight, as `now`'s offset counts it.
fn until_midnight(now: &LocalDateTime) -> Duration {
    let into_day = u64::from(now.hour) * 3600 + u64::from(now.minute) * 60 + u64::from(now.second);
    let left = 86_400u64.saturating_sub(into_day).max(1);
    Duration::from_secs(left).saturating_sub(Duration::from_nanos(u64::from(now.nanosecond)))
}

/// The longest the date goes unchecked. A time zone the user changes, or a daylight saving
/// change that moves midnight, is noticed within this.
const LONGEST_WAIT: Duration = Duration::from_secs(60);

/// A little past midnight, so the check that follows it is on the new date.
const PAST_MIDNIGHT: Duration = Duration::from_millis(20);

/// A registration for date changes. Dropping it ends the registration.
pub struct DateChangeSubscription {
    id: u64,
}

impl Drop for DateChangeSubscription {
    fn drop(&mut self) {
        watch::remove(self.id);
    }
}

/// Registers `notify` to be called once each time the date in the user's time zone
/// changes: when local midnight passes, or when the user moves to a time zone where it is
/// another day. Called on a Host worker thread, or in a browser at the start of a frame,
/// so it does what any worker does with a change: update a signal, which asks for a frame.
pub fn subscribe_date_change(notify: Arc<dyn Fn() + Send + Sync>) -> DateChangeSubscription {
    DateChangeSubscription {
        id: watch::add(notify),
    }
}

/// The subscribers and the date they last heard of, shared by every platform.
mod watch {
    use super::{LocalDate, today};
    use std::sync::{Arc, Mutex, MutexGuard};

    pub(super) type Notify = Arc<dyn Fn() + Send + Sync>;

    pub(super) struct Watch {
        pub(super) subscribers: Vec<(u64, Notify)>,
        pub(super) next_id: u64,
        /// The date the subscribers were last told of, or found on subscribing.
        pub(super) seen: Option<LocalDate>,
        /// Whether something is already waiting to check the date again.
        pub(super) armed: bool,
    }

    pub(super) static WATCH: Mutex<Watch> = Mutex::new(Watch {
        subscribers: Vec::new(),
        next_id: 1,
        seen: None,
        armed: false,
    });

    pub(super) fn lock() -> MutexGuard<'static, Watch> {
        WATCH.lock().unwrap_or_else(|poison| poison.into_inner())
    }

    pub(super) fn add(notify: Notify) -> u64 {
        let mut watch = lock();
        let id = watch.next_id;
        watch.next_id += 1;
        // The date the first subscriber starts from is read now, on the caller's thread,
        // so a midnight that passes before anything waits is still a change.
        if watch.subscribers.is_empty() {
            watch.seen = Some(today());
        }
        watch.subscribers.push((id, notify));
        if !watch.armed {
            watch.armed = true;
            super::platform::arm();
        }
        drop(watch);
        super::platform::changed();
        id
    }

    pub(super) fn remove(id: u64) {
        let mut watch = lock();
        watch
            .subscribers
            .retain(|(subscriber, _)| *subscriber != id);
        drop(watch);
        super::platform::changed();
    }

    /// Reads the date, and calls every subscriber once if it is not the one they last
    /// heard of. The calls are made with nothing held, so a subscriber may subscribe or
    /// drop a subscription from inside one.
    pub(super) fn check() {
        let now = today();
        let woken: Vec<Notify> = {
            let mut watch = lock();
            if watch.seen == Some(now) {
                return;
            }
            watch.seen = Some(now);
            watch
                .subscribers
                .iter()
                .map(|(_, notify)| notify.clone())
                .collect()
        };
        for notify in woken {
            notify();
        }
    }
}

#[cfg(not(target_family = "wasm"))]
mod platform {
    use super::{LONGEST_WAIT, PAST_MIDNIGHT, now_local, until_midnight, watch};
    use std::sync::Condvar;
    use std::time::SystemTime;

    pub(super) fn now() -> SystemTime {
        SystemTime::now()
    }

    /// Wakes the watching thread early: a subscriber came or went.
    static CHANGED: Condvar = Condvar::new();

    pub(super) fn changed() {
        CHANGED.notify_all();
    }

    /// Starts the thread that checks the date at each local midnight, and at least once a
    /// minute between them. It ends when the last subscriber has gone.
    pub(super) fn arm() {
        let started = std::thread::Builder::new()
            .name("compose-rust-date".to_owned())
            .spawn(run);
        if started.is_err() {
            // Without a thread there is nothing to wake anyone; the next subscriber tries
            // again rather than finding it armed.
            watch::lock().armed = false;
        }
    }

    fn run() {
        loop {
            let wait = until_midnight(&now_local()).min(LONGEST_WAIT) + PAST_MIDNIGHT;
            let mut watch = watch::lock();
            if !watch.subscribers.is_empty() {
                watch = CHANGED
                    .wait_timeout(watch, wait)
                    .unwrap_or_else(|poison| poison.into_inner())
                    .0;
            }
            if watch.subscribers.is_empty() {
                watch.armed = false;
                return;
            }
            drop(watch);
            watch::check();
        }
    }

    #[cfg(unix)]
    pub(super) fn utc_offset_seconds(seconds: i64) -> i32 {
        use std::ffi::{c_char, c_int, c_long};

        // `struct tm` as glibc, musl, bionic and Apple's libc lay it out: nine ints, then
        // the offset from UTC and the zone's abbreviation, which all four have.
        #[repr(C)]
        struct Tm {
            tm_sec: c_int,
            tm_min: c_int,
            tm_hour: c_int,
            tm_mday: c_int,
            tm_mon: c_int,
            tm_year: c_int,
            tm_wday: c_int,
            tm_yday: c_int,
            tm_isdst: c_int,
            tm_gmtoff: c_long,
            tm_zone: *const c_char,
        }

        unsafe extern "C" {
            fn tzset();
            fn localtime_r(time: *const c_long, result: *mut Tm) -> *mut Tm;
        }

        let Some(time) = c_long::try_from(seconds).ok() else {
            return 0;
        };
        let mut tm = Tm {
            tm_sec: 0,
            tm_min: 0,
            tm_hour: 0,
            tm_mday: 0,
            tm_mon: 0,
            tm_year: 0,
            tm_wday: 0,
            tm_yday: 0,
            tm_isdst: 0,
            tm_gmtoff: 0,
            tm_zone: std::ptr::null(),
        };
        // `localtime_r` need not look at the time zone again, and a user who changed it
        // expects the next read to show it, so it is read again every time.
        // SAFETY: both pointers are to locals that outlive the call, and `tm` has the
        // layout this platform's libc writes.
        let converted = unsafe {
            tzset();
            localtime_r(&time, &mut tm)
        };
        if converted.is_null() {
            return 0;
        }
        tm.tm_gmtoff as i32
    }

    #[cfg(windows)]
    pub(super) fn utc_offset_seconds(seconds: i64) -> i32 {
        use std::ffi::{CString, c_char, c_int};
        use std::sync::Mutex;

        // The C runtime's `struct tm`: nine ints and nothing after them.
        #[repr(C)]
        #[derive(Default)]
        struct Tm {
            tm_sec: c_int,
            tm_min: c_int,
            tm_hour: c_int,
            tm_mday: c_int,
            tm_mon: c_int,
            tm_year: c_int,
            tm_wday: c_int,
            tm_yday: c_int,
            tm_isdst: c_int,
        }

        unsafe extern "C" {
            fn _tzset();
            fn _putenv_s(name: *const c_char, value: *const c_char) -> c_int;
            fn _localtime64_s(result: *mut Tm, time: *const i64) -> c_int;
        }

        // The C runtime keeps its own copy of the environment, which a `TZ` set through
        // the process's environment does not reach. It is copied across when it changed,
        // so `TZ` wins here as it does on every other desktop.
        static SYNCED: Mutex<Option<String>> = Mutex::new(None);
        let wanted = std::env::var("TZ").ok();
        {
            let mut synced = SYNCED.lock().unwrap_or_else(|poison| poison.into_inner());
            if *synced != wanted {
                if let Ok(value) = CString::new(wanted.clone().unwrap_or_default()) {
                    // SAFETY: both are NUL-terminated strings that outlive the call.
                    unsafe { _putenv_s(c"TZ".as_ptr(), value.as_ptr()) };
                }
                *synced = wanted;
            }
        }
        let mut tm = Tm::default();
        // SAFETY: both pointers are to locals that outlive the call.
        let failed = unsafe {
            _tzset();
            _localtime64_s(&mut tm, &seconds)
        };
        if failed != 0 {
            return 0;
        }
        // The runtime reports the fields and not the offset, so the offset is how far the
        // wall clock it reports is from the instant read as UTC.
        let date = super::LocalDate {
            year: tm.tm_year + 1900,
            month: (tm.tm_mon + 1) as u8,
            day: tm.tm_mday as u8,
            weekday: super::Weekday::Monday,
        };
        let wall = date.days_since_epoch() * 86_400
            + i64::from(tm.tm_hour) * 3600
            + i64::from(tm.tm_min) * 60
            + i64::from(tm.tm_sec);
        (wall - seconds) as i32
    }
}

#[cfg(target_family = "wasm")]
mod platform {
    use super::{PAST_MIDNIGHT, now_local, until_midnight, watch};
    use std::cell::Cell;
    use std::time::{Duration, SystemTime, UNIX_EPOCH};

    // The page answers these from `Date`; the generated instantiation of the Host supplies
    // them. They are not boundary calls: nothing goes to the Renderer through them.
    #[link(wasm_import_module = "compose_rust_time")]
    unsafe extern "C" {
        /// `Date.now()`: milliseconds since 1970 in UTC.
        fn now_millis() -> f64;
        /// How many minutes the browser's time zone is ahead of UTC at that instant.
        fn utc_offset_minutes(millis: f64) -> i32;
        /// Asks the Renderer for a frame after `millis`, from a browser timer, so the date
        /// is checked then without a frame drawn in every moment between.
        fn wake_after(millis: f64);
    }

    pub(super) fn now() -> SystemTime {
        // SAFETY: an import the page supplies with no arguments.
        let millis = unsafe { now_millis() };
        UNIX_EPOCH + Duration::from_millis(millis.max(0.0) as u64)
    }

    pub(super) fn utc_offset_seconds(seconds: i64) -> i32 {
        // SAFETY: an import the page supplies, given a number.
        unsafe { utc_offset_minutes(seconds as f64 * 1000.0) * 60 }
    }

    pub(super) fn changed() {}

    thread_local! {
        /// When the next check is due, in `Date.now()` milliseconds. A frame before then,
        /// and an animation draws many, checks nothing and sets no timer.
        static DUE: Cell<f64> = const { Cell::new(0.0) };
    }

    pub(super) fn arm() {
        schedule();
    }

    fn schedule() {
        // A browser has no thread to wait on, so the timer is the browser's, and what it
        // wakes is a frame, at whose start the date is checked.
        let wait = (until_midnight(&now_local()).min(super::LONGEST_WAIT) + PAST_MIDNIGHT)
            .as_millis() as f64;
        // SAFETY: imports the page supplies, given a number or nothing.
        unsafe {
            DUE.with(|due| due.set(now_millis() + wait));
            wake_after(wait);
        }
    }

    /// Checks the date at the start of a frame once the timer is due, and waits for the
    /// next check.
    pub(crate) fn frame() {
        let armed = {
            let mut watch = watch::lock();
            if watch.subscribers.is_empty() {
                watch.armed = false;
            }
            watch.armed
        };
        // SAFETY: an import the page supplies with no arguments.
        if !armed || unsafe { now_millis() } < DUE.with(Cell::get) {
            return;
        }
        watch::check();
        schedule();
    }
}

/// What a browser's frame does for the date: checks it, at the start of the frame, if
/// anyone listens. Nothing anywhere else, where a thread waits for midnight instead.
#[cfg(target_family = "wasm")]
pub(crate) fn on_frame() {
    platform::frame();
}
