//! Packages a dioxus-compose desktop application as MSIX.
//!
//! The input is what a release build already produces: one executable, or a directory
//! holding the executable with the renderer beside it. The output is a package the
//! Microsoft Store accepts for submission, a bundle wrapping it, and optionally an App
//! Installer feed that keeps a downloaded copy current. Name, publisher, description,
//! icon and version come from the application's own `Dioxus.toml` and `Cargo.toml`.
//!
//! The steps are split so that every decision can be checked anywhere: [`stage`] builds
//! the package directory (payload, manifest, images) on any host, and [`pack`] hands that
//! directory to the Windows SDK's MakeAppx, which only exists on Windows.

pub mod appinstaller;
pub mod assets;
pub mod manifest;
pub mod metadata;
pub mod sdk;
pub mod version;

use std::ffi::OsStr;
use std::fmt;
use std::path::{Path, PathBuf};

pub use manifest::Arch;
pub use metadata::AppMetadata;
pub use version::{Channel, PackageVersion};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Error(String);

impl Error {
    pub fn new(message: impl Into<String>) -> Self {
        Error(message.into())
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

/// What goes into the package besides the manifest and images.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Payload {
    /// One self-contained executable, placed at the root of the package.
    Executable(PathBuf),
    /// A directory copied whole, with the executable at `executable` inside it. This is
    /// the shape a build with the renderer beside the program has (`bin\app.exe` next
    /// to the renderer DLL, `lib` next to `bin`).
    Directory { root: PathBuf, executable: PathBuf },
}

impl Payload {
    /// The executable's path inside the package, with backslashes as the manifest wants.
    pub fn executable_in_package(&self) -> Result<String, Error> {
        let relative: PathBuf = match self {
            Payload::Executable(file) => PathBuf::from(
                file.file_name()
                    .ok_or_else(|| Error::new(format!("{} is not a file", file.display())))?,
            ),
            Payload::Directory { executable, .. } => executable.clone(),
        };
        let parts: Vec<String> = relative
            .components()
            .map(|c| match c {
                std::path::Component::Normal(s) => Ok(s.to_string_lossy().into_owned()),
                _ => Err(Error::new(format!(
                    "the executable `{}` has to be a plain path inside the payload",
                    relative.display()
                ))),
            })
            .collect::<Result<_, _>>()?;
        if parts.is_empty() {
            return Err(Error::new("the executable path is empty"));
        }
        let joined = parts.join("\\");
        if !joined.to_ascii_lowercase().ends_with(".exe") {
            return Err(Error::new(format!(
                "the executable `{joined}` does not end in .exe"
            )));
        }
        Ok(joined)
    }
}

/// Everything one packaging run needs to know.
#[derive(Clone, Debug)]
pub struct Plan {
    pub meta: AppMetadata,
    pub version: PackageVersion,
    pub channel: Channel,
    pub arch: Arch,
    pub payload: Payload,
    pub icon: Option<PathBuf>,
}

impl Plan {
    /// `<identity>_<version>_<arch>.msix`, the name MakeAppx and the Store expect.
    pub fn package_file_name(&self) -> String {
        format!(
            "{}_{}_{}.msix",
            self.meta.identity_name,
            self.version,
            self.arch.as_str()
        )
    }

    pub fn bundle_file_name(&self) -> String {
        format!("{}_{}.msixbundle", self.meta.identity_name, self.version)
    }

    pub fn feed_file_name(&self) -> String {
        format!("{}.appinstaller", self.meta.identity_name)
    }
}

/// Names a package directory must keep for itself.
const RESERVED: &[&str] = &[
    "AppxManifest.xml",
    "AppxBlockMap.xml",
    "AppxSignature.p7x",
    "[Content_Types].xml",
    "AppxMetadata",
];

fn copy_dir(from: &Path, to: &Path) -> Result<(), Error> {
    std::fs::create_dir_all(to)
        .map_err(|e| Error::new(format!("cannot create {}: {e}", to.display())))?;
    let entries = std::fs::read_dir(from)
        .map_err(|e| Error::new(format!("cannot read {}: {e}", from.display())))?;
    for entry in entries {
        let entry =
            entry.map_err(|e| Error::new(format!("cannot read {}: {e}", from.display())))?;
        let src = entry.path();
        let dst = to.join(entry.file_name());
        let kind = entry
            .file_type()
            .map_err(|e| Error::new(format!("cannot inspect {}: {e}", src.display())))?;
        if kind.is_dir() {
            copy_dir(&src, &dst)?;
        } else {
            std::fs::copy(&src, &dst).map_err(|e| {
                Error::new(format!(
                    "cannot copy {} to {}: {e}",
                    src.display(),
                    dst.display()
                ))
            })?;
        }
    }
    Ok(())
}

/// Builds the package directory at `layout`: the payload, `AppxManifest.xml` and the
/// images. Runs anywhere. Returns warnings worth showing the person packaging.
pub fn stage(plan: &Plan, layout: &Path) -> Result<Vec<String>, Error> {
    let mut warnings = Vec::new();
    if layout.exists() {
        std::fs::remove_dir_all(layout)
            .map_err(|e| Error::new(format!("cannot clear {}: {e}", layout.display())))?;
    }
    std::fs::create_dir_all(layout)
        .map_err(|e| Error::new(format!("cannot create {}: {e}", layout.display())))?;

    let executable = plan.payload.executable_in_package()?;
    match &plan.payload {
        Payload::Executable(file) => {
            if !file.is_file() {
                return Err(Error::new(format!("{} does not exist", file.display())));
            }
            std::fs::copy(file, layout.join(&executable))
                .map_err(|e| Error::new(format!("cannot copy {}: {e}", file.display())))?;
        }
        Payload::Directory {
            root,
            executable: exe,
        } => {
            if !root.join(exe).is_file() {
                return Err(Error::new(format!(
                    "{} is not in the payload directory {}",
                    exe.display(),
                    root.display()
                )));
            }
            for name in RESERVED.iter().chain([&assets::DIR]) {
                if root.join(name).exists() {
                    return Err(Error::new(format!(
                        "the payload already has `{name}`, which the package writes itself"
                    )));
                }
            }
            copy_dir(root, layout)?;
        }
    }

    let xml = manifest::render(&plan.meta, plan.version, plan.arch, &executable);
    std::fs::write(layout.join("AppxManifest.xml"), xml)
        .map_err(|e| Error::new(format!("cannot write the manifest: {e}")))?;

    let icon_path = plan.icon.clone().or_else(|| plan.meta.icon.clone()).ok_or_else(|| {
        Error::new(
            "the application has no icon: name a PNG in [bundle] icon, or put one at <asset_dir>/icon.png",
        )
    })?;
    let icon = std::fs::read(&icon_path)
        .map_err(|e| Error::new(format!("cannot read the icon {}: {e}", icon_path.display())))?;
    if let Some(w) = assets::write_all(&icon, layout)? {
        warnings.push(w);
    }

    if plan.channel == Channel::Store && !plan.meta.store_identity {
        warnings.push(format!(
            "the identity `{}` / `{}` was derived, not taken from [windows.msix]; Partner Center will refuse \
             this package until identity_name and publisher are set to the values it shows for the reserved name",
            plan.meta.identity_name, plan.meta.publisher
        ));
    }
    Ok(warnings)
}

/// What [`pack`] produced.
#[derive(Clone, Debug)]
pub struct Outputs {
    pub package: PathBuf,
    pub bundle: PathBuf,
    pub feed: Option<PathBuf>,
}

/// Turns a staged directory into `.msix` and `.msixbundle` with MakeAppx, and writes the
/// App Installer feed when `feed_base` is given. Windows only, because MakeAppx is.
pub fn pack(
    plan: &Plan,
    layout: &Path,
    out_dir: &Path,
    feed_base: Option<&str>,
) -> Result<Outputs, Error> {
    let makeappx = sdk::find_tool("makeappx.exe")?;
    std::fs::create_dir_all(out_dir)
        .map_err(|e| Error::new(format!("cannot create {}: {e}", out_dir.display())))?;
    let package = out_dir.join(plan.package_file_name());
    sdk::run(
        &makeappx,
        &[
            OsStr::new("pack"),
            OsStr::new("/o"),
            OsStr::new("/d"),
            layout.as_os_str(),
            OsStr::new("/p"),
            package.as_os_str(),
        ],
    )?;

    // MakeAppx bundles every package in a directory, so the package goes into one of
    // its own. Other architectures join it here when they are built.
    let bundle_src = out_dir.join("bundle-src");
    if bundle_src.exists() {
        std::fs::remove_dir_all(&bundle_src)
            .map_err(|e| Error::new(format!("cannot clear {}: {e}", bundle_src.display())))?;
    }
    std::fs::create_dir_all(&bundle_src)
        .map_err(|e| Error::new(format!("cannot create {}: {e}", bundle_src.display())))?;
    std::fs::copy(&package, bundle_src.join(plan.package_file_name()))
        .map_err(|e| Error::new(format!("cannot copy the package: {e}")))?;
    let bundle = out_dir.join(plan.bundle_file_name());
    let version = plan.version.to_string();
    sdk::run(
        &makeappx,
        &[
            OsStr::new("bundle"),
            OsStr::new("/o"),
            OsStr::new("/bv"),
            OsStr::new(&version),
            OsStr::new("/d"),
            bundle_src.as_os_str(),
            OsStr::new("/p"),
            bundle.as_os_str(),
        ],
    )?;
    std::fs::remove_dir_all(&bundle_src).ok();

    let feed = match feed_base {
        Some(base) => {
            let feed =
                appinstaller::Feed::beside(base, &plan.feed_file_name(), &plan.bundle_file_name())?;
            let path = out_dir.join(plan.feed_file_name());
            std::fs::write(&path, feed.render(&plan.meta, plan.version)?)
                .map_err(|e| Error::new(format!("cannot write {}: {e}", path.display())))?;
            Some(path)
        }
        None => None,
    };
    Ok(Outputs {
        package,
        bundle,
        feed,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fr35_executable_paths_use_backslashes() {
        let p = Payload::Directory {
            root: "/x".into(),
            executable: Path::new("bin").join("app.exe"),
        };
        assert_eq!(p.executable_in_package().unwrap(), "bin\\app.exe");
        let single = Payload::Executable(Path::new("/build/release/ember.exe").into());
        assert_eq!(single.executable_in_package().unwrap(), "ember.exe");
    }

    #[test]
    fn fr35_executable_must_stay_inside_the_payload() {
        let p = Payload::Directory {
            root: "/x".into(),
            executable: Path::new("..").join("app.exe"),
        };
        assert!(p.executable_in_package().is_err());
        let not_exe = Payload::Executable("/build/ember".into());
        assert!(not_exe.executable_in_package().is_err());
    }
}
