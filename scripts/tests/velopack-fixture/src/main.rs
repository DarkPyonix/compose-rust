//! A Velopack application small enough to install and update in CI.
//!
//! `velopack-fixture --version` prints the version it was built as.
//! `velopack-fixture --installed-version` prints the version Velopack's manifest says.
//! `velopack-fixture --update <feed>` checks the feed (a directory or a URL), downloads the
//! update, hands it to the updater (Update.exe, UpdateMac or UpdateNix) and exits without
//! restarting, so the caller can wait for the updater and run the installed copy again.

use std::process::ExitCode;

use velopack::sources::AutoSource;
use velopack::{UpdateCheck, UpdateManager, VelopackApp};

/// The version this build reports. The test builds the same source twice, with
/// FIXTURE_VERSION=1.0.0 and FIXTURE_VERSION=1.0.1, and packs each as that version.
const VERSION: &str = match option_env!("FIXTURE_VERSION") {
    Some(version) => version,
    None => env!("CARGO_PKG_VERSION"),
};

/// Velopack logs through the `log` crate; sending it to stderr puts the SDK's own account
/// of what it chose (full or delta, which files) into the CI log.
struct StderrLog;

impl log::Log for StderrLog {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        metadata.level() <= log::Level::Info
    }

    fn log(&self, record: &log::Record) {
        if self.enabled(record.metadata()) {
            eprintln!("[{}] {}", record.level(), record.args());
        }
    }

    fn flush(&self) {}
}

static LOGGER: StderrLog = StderrLog;

fn main() -> ExitCode {
    let _ = log::set_logger(&LOGGER).map(|()| log::set_max_level(log::LevelFilter::Info));

    // First, as Velopack requires: it answers the install, update and uninstall hooks the
    // updater calls the app with, and exits for them. Applying a downloaded update on
    // start is off, so `--version` never starts an update of its own while the test
    // waits for the one it asked for.
    VelopackApp::build().set_auto_apply_on_startup(false).run();

    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("--version") | None => {
            println!("{VERSION}");
            ExitCode::SUCCESS
        }
        Some("--installed-version") => match UpdateManager::new(AutoSource::new("."), None, None) {
            Ok(manager) => {
                println!("{}", manager.get_current_version_as_string());
                ExitCode::SUCCESS
            }
            Err(error) => {
                eprintln!("velopack-fixture: not installed: {error}");
                ExitCode::from(1)
            }
        },
        Some("--update") => {
            let Some(feed) = args.get(1) else {
                eprintln!("velopack-fixture: --update needs a feed directory or URL");
                return ExitCode::from(64);
            };
            update(feed)
        }
        Some(other) => {
            eprintln!("velopack-fixture: unknown argument {other}");
            ExitCode::from(64)
        }
    }
}

fn update(feed: &str) -> ExitCode {
    let manager = match UpdateManager::new(AutoSource::new(feed), None, None) {
        Ok(manager) => manager,
        Err(error) => {
            eprintln!("velopack-fixture: not installed: {error}");
            return ExitCode::from(1);
        }
    };
    let info = match manager.check_for_updates() {
        Ok(UpdateCheck::UpdateAvailable(info)) => info,
        Ok(UpdateCheck::NoUpdateAvailable) => {
            println!("no update");
            return ExitCode::from(2);
        }
        Ok(UpdateCheck::RemoteIsEmpty) => {
            println!("feed is empty");
            return ExitCode::from(3);
        }
        Err(error) => {
            eprintln!("velopack-fixture: checking {feed} failed: {error}");
            return ExitCode::from(1);
        }
    };
    println!(
        "update {} -> {}, deltas {}",
        manager.get_current_version_as_string(),
        info.TargetFullRelease.Version,
        info.DeltasToTarget.len()
    );
    if let Err(error) = manager.download_updates(&info, None) {
        eprintln!("velopack-fixture: downloading failed: {error}");
        return ExitCode::from(1);
    }
    // Silent, so no progress window is asked of a runner with no display, and no
    // restart, so the test decides when the updated copy runs.
    if let Err(error) = manager.wait_exit_then_apply_updates(&*info, true, false, Vec::<String>::new()) {
        eprintln!("velopack-fixture: starting the updater failed: {error}");
        return ExitCode::from(1);
    }
    println!("updater started");
    ExitCode::SUCCESS
}
