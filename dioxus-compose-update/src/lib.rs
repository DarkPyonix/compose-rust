//! Updates for a dioxus-compose application that is handed out directly rather than
//! through a store.
//!
//! On macOS this drives [Sparkle 2](https://sparkle-project.org): the application checks
//! an appcast, shows Sparkle's own update window (or installs silently when the person
//! allowed that), verifies the archive's EdDSA signature against the key in its
//! Info.plist, and replaces itself. Everything about where to look and which key to trust
//! is in the bundle, written by `tools/packager/package-macos app --channel sparkle`;
//! this crate only starts Sparkle and passes on the person's requests.
//!
//! ```no_run
//! fn main() {
//!     // Before launch: the request waits on the main thread's queue and runs once
//!     // the renderer has AppKit running there.
//!     if let Err(error) = dioxus_compose_update::start(Default::default()) {
//!         eprintln!("updates are off: {error}");
//!     }
//!     // dioxus_compose::launch(app);
//! }
//!
//! // From a menu item or a button:
//! // on_click: move |_| dioxus_compose_update::check_for_updates(),
//! ```
//!
//! A build for the Mac App Store must not contain this crate: the store updates the
//! application, App Review rejects one that updates itself, and the packager refuses a
//! store bundle whose executable names Sparkle. Put the dependency behind a Cargo feature
//! that the store build leaves off.
//!
//! Elsewhere (Windows, Linux, iOS, Android, the browser) [`start`] answers
//! [`UpdaterError::Unsupported`] and the other functions do nothing, so calling code
//! needs no `cfg` of its own.

use std::fmt;
use std::path::{Path, PathBuf};

#[cfg(target_os = "macos")]
mod sparkle;

/// When an update that was downloaded without asking gets installed.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Install {
    /// When the application quits, which is Sparkle's default: nothing interrupts the
    /// person, and the next launch is the new version.
    #[default]
    OnQuit,
    /// As soon as it is ready, relaunching the application. For applications that run for
    /// weeks without quitting, and for rehearsing an update unattended.
    Immediately,
}

/// How the updater starts.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct UpdaterOptions {
    /// Check once at launch, in the background, besides Sparkle's own schedule.
    pub check_on_start: bool,
    /// What happens to an update Sparkle downloaded on its own.
    pub install: Install,
}

#[derive(Debug, PartialEq, Eq)]
pub enum UpdaterError {
    /// This platform has no updater here; its store or package manager updates it.
    Unsupported,
    /// The executable is not inside an application bundle, so there is no Info.plist to
    /// read the feed and key from and nowhere Sparkle could install to.
    NotInBundle(PathBuf),
    /// The bundle has no Sparkle.framework where the packager puts it.
    FrameworkMissing(PathBuf),
    /// [`start`] was already called in this process.
    AlreadyStarted,
    /// The executable's own path could not be read.
    ExecutablePath(String),
}

impl fmt::Display for UpdaterError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Unsupported => write!(f, "this platform has no self-updater"),
            Self::NotInBundle(path) => write!(
                f,
                "{} is not inside an application bundle (Name.app/Contents/MacOS), so there \
                 is no Info.plist to read the update feed from; run the packaged application",
                path.display()
            ),
            Self::FrameworkMissing(path) => write!(
                f,
                "there is no Sparkle framework at {}; package the application with \
                 `package-macos app --channel sparkle --sparkle-framework ...`",
                path.display()
            ),
            Self::AlreadyStarted => write!(f, "the updater was already started"),
            Self::ExecutablePath(error) => {
                write!(f, "could not read this executable's path: {error}")
            }
        }
    }
}

impl std::error::Error for UpdaterError {}

/// Where the packager puts Sparkle for an executable at `executable`.
///
/// The executable of a bundle is `Name.app/Contents/MacOS/<name>`, and frameworks are in
/// `Name.app/Contents/Frameworks`. Anything else is not a bundle.
pub fn framework_path(executable: &Path) -> Result<PathBuf, UpdaterError> {
    let not_in_bundle = || UpdaterError::NotInBundle(executable.to_path_buf());
    let macos = executable.parent().ok_or_else(not_in_bundle)?;
    if macos.file_name().and_then(|name| name.to_str()) != Some("MacOS") {
        return Err(not_in_bundle());
    }
    let contents = macos.parent().ok_or_else(not_in_bundle)?;
    if contents.file_name().and_then(|name| name.to_str()) != Some("Contents") {
        return Err(not_in_bundle());
    }
    let app = contents.parent().ok_or_else(not_in_bundle)?;
    if app.extension().and_then(|extension| extension.to_str()) != Some("app") {
        return Err(not_in_bundle());
    }
    Ok(contents.join("Frameworks").join("Sparkle.framework"))
}

/// Whether `framework` looks like a Sparkle 2 framework, judged by its binary.
pub fn is_sparkle_framework(framework: &Path) -> bool {
    framework.join("Versions").join("B").join("Sparkle").is_file()
}

/// Start the updater. Call it once, before `dioxus_compose::launch`.
///
/// The framework is checked for here, on the calling thread, so a missing one is an error
/// the caller sees. Loading it and creating Sparkle's controller happen on the main thread
/// once AppKit runs there, which is after `launch` has started the renderer; a failure at
/// that point is written to standard error.
pub fn start(options: UpdaterOptions) -> Result<(), UpdaterError> {
    #[cfg(target_os = "macos")]
    {
        let executable = std::env::current_exe()
            .map_err(|error| UpdaterError::ExecutablePath(error.to_string()))?;
        let framework = framework_path(&executable)?;
        if !is_sparkle_framework(&framework) {
            return Err(UpdaterError::FrameworkMissing(framework));
        }
        sparkle::start(framework, options)
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = options;
        Err(UpdaterError::Unsupported)
    }
}

/// Check now, showing Sparkle's window: the "Check for Updates..." menu item.
///
/// Does nothing if [`start`] was not called or failed.
pub fn check_for_updates() {
    #[cfg(target_os = "macos")]
    sparkle::check_for_updates();
}

/// Check now without a window unless an update is found.
pub fn check_in_background() {
    #[cfg(target_os = "macos")]
    sparkle::check_in_background();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fr34_10_framework_is_found_beside_the_executable_in_its_bundle() {
        assert_eq!(
            framework_path(Path::new("/Applications/Demo.app/Contents/MacOS/demo")),
            Ok(PathBuf::from(
                "/Applications/Demo.app/Contents/Frameworks/Sparkle.framework"
            ))
        );
    }

    #[test]
    fn fr34_10_an_executable_outside_a_bundle_is_named_in_the_error() {
        for path in [
            "/usr/local/bin/demo",
            "/Applications/Demo.app/Contents/demo",
            "/Applications/Demo/Contents/MacOS/demo",
            "demo",
        ] {
            let error = framework_path(Path::new(path)).unwrap_err();
            assert_eq!(error, UpdaterError::NotInBundle(PathBuf::from(path)));
            assert!(error.to_string().contains(path), "{error}");
        }
    }

    #[test]
    fn fr34_10_a_directory_without_the_binary_is_not_a_framework() {
        let scratch = std::env::temp_dir().join(format!(
            "dioxus-compose-update-test-{}",
            std::process::id()
        ));
        let framework = scratch.join("Sparkle.framework");
        std::fs::create_dir_all(framework.join("Versions").join("B")).unwrap();
        assert!(!is_sparkle_framework(&framework));
        std::fs::write(framework.join("Versions").join("B").join("Sparkle"), b"").unwrap();
        assert!(is_sparkle_framework(&framework));
        std::fs::remove_dir_all(&scratch).unwrap();
    }

    #[test]
    fn fr34_10_an_executable_outside_a_bundle_cannot_start_the_updater() {
        // The test binary lives in target/, not in a bundle, which is exactly the case of
        // running an application with `cargo run`. That must be an error the caller can
        // print, not a crash and not a silent success.
        let error = start(UpdaterOptions::default()).unwrap_err();
        if cfg!(target_os = "macos") {
            assert!(matches!(error, UpdaterError::NotInBundle(_)), "{error:?}");
        } else {
            assert_eq!(error, UpdaterError::Unsupported);
        }
    }

    #[test]
    fn fr34_10_checks_before_start_do_nothing() {
        // Neither may panic or block: on macOS they queue work for the main thread, which
        // in a test binary never runs, and the queued work finds no controller.
        check_for_updates();
        check_in_background();
    }
}
