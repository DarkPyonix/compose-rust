//! A packaged application that checks for, asks about, applies and restarts into an
//! update, the way a real one would, with standard input in place of a dialog.
//!
//! The packaging workflow builds it twice with different versions, wraps both as
//! AppImages, serves the newer one's `.zsync` on the runner, and runs the older one with
//! `--update`. The same program inside a Flatpak says the store updates it and changes
//! nothing.
//!
//! ```text
//! update-demo --version     print the version
//! update-demo --delivery    say how this copy was delivered
//! update-demo --check       exit 0 when up to date, 10 when an update is available
//! update-demo --update      check, ask, apply, and restart into the new version
//!             [--yes]       answer yes without asking
//! ```

use std::io::{BufRead, Write};
use std::process::ExitCode;

use dioxus_compose::update::{Delivery, UpdateCheck, delivery};

/// Set by the build, so two builds of the same source differ the way two releases do.
const VERSION: &str = match option_env!("DIOXUS_COMPOSE_APP_VERSION") {
    Some(version) => version,
    None => env!("CARGO_PKG_VERSION"),
};

const UPDATE_AVAILABLE: u8 = 10;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let yes = args.iter().any(|a| a == "--yes");
    match args.first().map(String::as_str) {
        Some("--version") | None => {
            println!("update-demo {VERSION}");
            ExitCode::SUCCESS
        }
        Some("--delivery") => {
            match delivery() {
                Delivery::AppImage { path, mount } => println!(
                    "AppImage {} mounted at {}",
                    path.display(),
                    mount.map_or("(unknown)".into(), |m| m.display().to_string())
                ),
                Delivery::Flatpak { app_id } => println!("Flatpak {app_id}"),
                Delivery::Unpackaged => println!("unpackaged"),
            }
            ExitCode::SUCCESS
        }
        Some("--check") => check(),
        Some("--update") => update(yes),
        Some(other) => {
            eprintln!("unknown argument {other}; see the comment at the top of main.rs");
            ExitCode::from(2)
        }
    }
}

fn check() -> ExitCode {
    let updater = match delivery().appimage_updater() {
        Ok(updater) => updater,
        Err(error) => {
            println!("no self-update: {error}");
            return ExitCode::SUCCESS;
        }
    };
    println!("update information: {}", updater.update_information());
    match updater.check() {
        Ok(UpdateCheck::UpToDate) => {
            println!("update-demo {VERSION} is up to date");
            ExitCode::SUCCESS
        }
        Ok(UpdateCheck::Available) => {
            println!("update-demo {VERSION}: an update is available");
            ExitCode::from(UPDATE_AVAILABLE)
        }
        Err(error) => {
            eprintln!("error: {error}");
            ExitCode::FAILURE
        }
    }
}

fn update(yes: bool) -> ExitCode {
    let updater = match delivery().appimage_updater() {
        Ok(updater) => updater,
        Err(error) => {
            println!("no self-update: {error}");
            return ExitCode::SUCCESS;
        }
    };
    println!("update-demo {VERSION} at {}", updater.appimage().display());
    println!("update information: {}", updater.update_information());

    // A real application does this on a worker thread and tells the UI through a signal.
    let checked = std::thread::spawn({
        let updater = updater.clone();
        move || updater.check()
    })
    .join()
    .expect("the check thread does not panic");
    match checked {
        Ok(UpdateCheck::UpToDate) => {
            println!("already up to date");
            return ExitCode::SUCCESS;
        }
        Ok(UpdateCheck::Available) => println!("an update is available"),
        Err(error) => {
            eprintln!("error: {error}");
            return ExitCode::FAILURE;
        }
    }

    if !yes && !ask("Install it and restart? [y/N] ") {
        println!("not updating");
        return ExitCode::SUCCESS;
    }
    if let Err(error) = updater.apply() {
        eprintln!("error: {error}");
        return ExitCode::FAILURE;
    }
    println!("updated; restarting");
    let error = updater.relaunch(["--version"]);
    eprintln!("error: could not restart: {error}");
    ExitCode::FAILURE
}

fn ask(question: &str) -> bool {
    print!("{question}");
    let _ = std::io::stdout().flush();
    let mut answer = String::new();
    if std::io::stdin().lock().read_line(&mut answer).is_err() {
        return false;
    }
    matches!(answer.trim(), "y" | "Y" | "yes")
}
