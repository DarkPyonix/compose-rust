//! Debian `.deb` staging: generates `DEBIAN/control` and lays out `/usr/bin`, desktop
//! entry, AppStream metainfo and hicolor icons from `AppMetadata`.

use std::fmt;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::time::{Duration, UNIX_EPOCH};

use crate::appstream::{metainfo, metainfo_file_name};
use crate::desktop::{desktop_entry, desktop_file_name};
use crate::icons::Icon;
use crate::metadata::AppMetadata;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DebError(pub String);

impl fmt::Display for DebError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for DebError {}

/// Maps a Rust or AppImage architecture name to the Debian architecture name.
pub fn deb_arch(arch: &str) -> Option<&'static str> {
    match arch {
        "x86_64" | "amd64" => Some("amd64"),
        "aarch64" | "arm64" => Some("arm64"),
        "x86" | "i686" | "i386" => Some("i386"),
        "arm" | "armv7" | "armhf" => Some("armhf"),
        _ => None,
    }
}

/// Derives a Debian policy compliant package name from `AppMetadata`.
///
/// Debian package names must be lowercase and contain only ASCII letters, digits, `+`,
/// `-` and `.`.
pub fn deb_package_name(meta: &AppMetadata) -> String {
    let mut name: String = meta
        .exec
        .chars()
        .map(|c| {
            let lower = c.to_ascii_lowercase();
            if lower.is_ascii_lowercase()
                || lower.is_ascii_digit()
                || matches!(lower, '+' | '-' | '.')
            {
                lower
            } else {
                '-'
            }
        })
        .collect();
    while name.starts_with(['-', '.', '+']) {
        name.remove(0);
    }
    if name.len() < 2 {
        name.push_str("app");
    }
    name
}

/// `<package>_<version>_<arch>.deb`.
pub fn deb_file_name(meta: &AppMetadata, arch: &str) -> String {
    format!("{}_{}_{arch}.deb", deb_package_name(meta), meta.version)
}

/// Maps the first freedesktop category to a Debian archive section.
pub fn deb_section(meta: &AppMetadata) -> &'static str {
    for category in &meta.categories {
        match category.as_str() {
            "Development" => return "devel",
            "Education" => return "education",
            "Game" => return "games",
            "Graphics" => return "graphics",
            "Network" => return "net",
            "Office" => return "editors",
            "Science" => return "science",
            "Audio" | "AudioVideo" => return "sound",
            "Video" => return "video",
            "System" | "Settings" => return "admin",
            "Utility" => return "utils",
            _ => {}
        }
    }
    "utils"
}

/// Formats `Maintainer: <Name> <dev@domain>` from `AppMetadata`.
pub fn deb_maintainer(meta: &AppMetadata) -> String {
    let domain = meta
        .developer_id
        .split('.')
        .rev()
        .collect::<Vec<_>>()
        .join(".");
    let clean_name = meta.developer_name.replace(['\r', '\n'], " ");
    format!("{clean_name} <dev@{domain}>")
}

/// Renders `DEBIAN/control` from `AppMetadata` for the given Debian architecture.
pub fn control_file(meta: &AppMetadata, arch: &str) -> String {
    let mut out = String::new();
    let mut field = |key: &str, value: &str| {
        out.push_str(key);
        out.push_str(": ");
        out.push_str(&value.replace(['\r', '\n'], " "));
        out.push('\n');
    };
    field("Package", &deb_package_name(meta));
    field("Version", &meta.version);
    field("Section", deb_section(meta));
    field("Priority", "optional");
    field("Architecture", arch);
    field("Maintainer", &deb_maintainer(meta));
    field("Homepage", &meta.homepage);
    field("Description", &meta.summary);
    for (index, paragraph) in meta.description.iter().enumerate() {
        if index > 0 {
            out.push_str(" .\n");
        }
        out.push(' ');
        out.push_str(&paragraph.replace(['\r', '\n'], " "));
        out.push('\n');
    }
    out
}

/// Inputs for staging a `.deb` package directory for `dpkg-deb --build`.
#[derive(Debug, Clone)]
pub struct DebInputs<'a> {
    pub meta: &'a AppMetadata,
    /// The compiled executable, or a directory holding it at its top level.
    pub payload: &'a Path,
    pub icons: &'a [Icon],
    /// Debian architecture name (for example `amd64` or `arm64`).
    pub arch: &'a str,
    /// Optional `SOURCE_DATE_EPOCH` in seconds for deterministic file timestamps.
    pub source_date_epoch: Option<u64>,
}

/// Stages a `.deb` package tree at `out`, which must not exist yet.
///
/// Writes `DEBIAN/control`, `usr/bin/<exec>`, `usr/share/applications/<id>.desktop`,
/// `usr/share/metainfo/<id>.metainfo.xml` and `usr/share/icons/hicolor/...`.
pub fn assemble_deb(inputs: &DebInputs, out: &Path) -> Result<Vec<PathBuf>, DebError> {
    let meta = inputs.meta;
    if out.exists() {
        return Err(DebError(format!(
            "{} already exists; a .deb stage is assembled from nothing",
            out.display()
        )));
    }
    if inputs.icons.is_empty() {
        return Err(DebError(
            "no icon: pass --icon or set [bundle] icon for a .deb package".into(),
        ));
    }
    let io_error = |what: &str, error: io::Error| DebError(format!("{what}: {error}"));
    let mtime = inputs
        .source_date_epoch
        .map(|seconds| UNIX_EPOCH + Duration::from_secs(seconds));
    let mut written = Vec::new();
    let mut write = |relative: &str, contents: &[u8], executable: bool| {
        let path = out.join(relative);
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| io_error(&parent.display().to_string(), e))?;
        }
        fs::write(&path, contents).map_err(|e| io_error(&path.display().to_string(), e))?;
        if executable {
            set_executable(&path).map_err(|e| io_error(&path.display().to_string(), e))?;
        }
        if let Some(time) = mtime {
            set_mtime(&path, time).map_err(|e| io_error(&path.display().to_string(), e))?;
        }
        written.push(PathBuf::from(relative));
        Ok::<(), DebError>(())
    };

    write(
        "DEBIAN/control",
        control_file(meta, inputs.arch).as_bytes(),
        false,
    )?;

    let exec_source = resolve_executable(inputs.payload, &meta.exec)?;
    let exec_bytes =
        fs::read(&exec_source).map_err(|e| io_error(&exec_source.display().to_string(), e))?;
    write(&format!("usr/bin/{}", meta.exec), &exec_bytes, true)?;

    write(
        &format!("usr/share/applications/{}", desktop_file_name(meta)),
        desktop_entry(meta).as_bytes(),
        false,
    )?;
    write(
        &format!("usr/share/metainfo/{}", metainfo_file_name(meta)),
        metainfo(meta).as_bytes(),
        false,
    )?;
    for icon in inputs.icons {
        let bytes =
            fs::read(&icon.source).map_err(|e| io_error(&icon.source.display().to_string(), e))?;
        write(
            &format!("usr/{}", icon.install_path(&meta.id)),
            &bytes,
            false,
        )?;
    }

    Ok(written)
}

fn resolve_executable(payload: &Path, exec: &str) -> Result<PathBuf, DebError> {
    if payload.is_dir() {
        let candidate = payload.join(exec);
        if candidate.is_file() {
            return Ok(candidate);
        }
        return Err(DebError(format!(
            "the payload directory {} has no {} at its top level",
            payload.display(),
            exec
        )));
    }
    if payload.is_file() {
        return Ok(payload.to_path_buf());
    }
    Err(DebError(format!(
        "the payload {} does not exist",
        payload.display()
    )))
}

fn set_mtime(path: &Path, time: std::time::SystemTime) -> io::Result<()> {
    let file = fs::File::options().write(true).open(path)?;
    file.set_modified(time)
}

#[cfg(unix)]
fn set_executable(path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mut permissions = fs::metadata(path)?.permissions();
    permissions.set_mode(permissions.mode() | 0o755);
    fs::set_permissions(path, permissions)
}

#[cfg(not(unix))]
fn set_executable(_path: &Path) -> io::Result<()> {
    Ok(())
}
