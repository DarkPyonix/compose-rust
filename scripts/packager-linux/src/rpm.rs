//! RPM staging: generates `<package>.spec` and lays out an `rpmbuild` topdir (`BUILD`,
//! `RPMS`, `SOURCES`, `SPECS`, `SRPMS`) from `AppMetadata`.

use std::fmt;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::time::{Duration, UNIX_EPOCH};

use crate::appstream::{metainfo, metainfo_file_name};
use crate::desktop::{desktop_entry, desktop_file_name};
use crate::icons::{Icon, IconKind};
use crate::metadata::AppMetadata;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RpmError(pub String);

impl fmt::Display for RpmError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for RpmError {}

/// Maps a Rust or AppImage architecture name to the RPM architecture name.
pub fn rpm_arch(arch: &str) -> Option<&'static str> {
    match arch {
        "x86_64" | "amd64" => Some("x86_64"),
        "aarch64" | "arm64" => Some("aarch64"),
        "x86" | "i686" | "i386" => Some("i686"),
        "arm" | "armv7" | "armhf" | "armv7hl" => Some("armv7hl"),
        _ => None,
    }
}

/// Derives an RPM package name from `AppMetadata`.
pub fn rpm_package_name(meta: &AppMetadata) -> String {
    let mut name: String = meta
        .exec
        .chars()
        .map(|c| {
            let lower = c.to_ascii_lowercase();
            if lower.is_ascii_lowercase()
                || lower.is_ascii_digit()
                || matches!(lower, '+' | '-' | '.' | '_')
            {
                lower
            } else {
                '-'
            }
        })
        .collect();
    while name.starts_with(['-', '.', '+', '_']) {
        name.remove(0);
    }
    if name.is_empty() {
        name.push_str("app");
    }
    name
}

/// `<package>.spec`.
pub fn spec_file_name(meta: &AppMetadata) -> String {
    format!("{}.spec", rpm_package_name(meta))
}

/// Path of an icon inside `SOURCES/` so icons of different sizes never collide.
pub fn icon_source_path(meta: &AppMetadata, icon: &Icon) -> String {
    match icon.kind {
        IconKind::Png(size) => format!("icons/{size}x{size}/{}.png", meta.id),
        IconKind::Svg => format!("icons/scalable/{}.svg", meta.id),
    }
}

/// Renders an RPM `.spec` file from `AppMetadata` and `icons`.
pub fn rpm_spec(meta: &AppMetadata, icons: &[Icon]) -> String {
    let pkg = rpm_package_name(meta);
    let desktop = desktop_file_name(meta);
    let appstream = metainfo_file_name(meta);
    let clean = |s: &str| s.replace(['\r', '\n'], " ");

    let mut out = String::new();
    out.push_str(&format!("Name:           {pkg}\n"));
    out.push_str(&format!("Version:        {}\n", clean(&meta.version)));
    out.push_str("Release:        1\n");
    out.push_str(&format!("Summary:        {}\n", clean(&meta.summary)));
    out.push_str(&format!("License:        {}\n", clean(&meta.license)));
    out.push_str(&format!("URL:            {}\n", clean(&meta.homepage)));
    out.push_str(&format!(
        "Packager:       {}\n",
        clean(&meta.developer_name)
    ));
    out.push_str("%global debug_package %{nil}\n");
    out.push_str("%global _build_id_links none\n\n");

    out.push_str("%description\n");
    for (index, paragraph) in meta.description.iter().enumerate() {
        if index > 0 {
            out.push('\n');
        }
        out.push_str(&clean(paragraph));
        out.push('\n');
    }

    out.push_str("\n%install\n");
    out.push_str("mkdir -p %{buildroot}/usr/bin\n");
    out.push_str(&format!(
        "install -m 0755 %{{_sourcedir}}/{exec} %{{buildroot}}/usr/bin/{exec}\n",
        exec = meta.exec
    ));
    out.push_str("mkdir -p %{buildroot}/usr/share/applications\n");
    out.push_str(&format!(
        "install -m 0644 %{{_sourcedir}}/{desktop} %{{buildroot}}/usr/share/applications/{desktop}\n"
    ));
    out.push_str("mkdir -p %{buildroot}/usr/share/metainfo\n");
    out.push_str(&format!(
        "install -m 0644 %{{_sourcedir}}/{appstream} %{{buildroot}}/usr/share/metainfo/{appstream}\n"
    ));
    for icon in icons {
        let install = icon.install_path(&meta.id);
        let dir = Path::new(&install)
            .parent()
            .and_then(Path::to_str)
            .unwrap_or("share/icons/hicolor");
        let source = icon_source_path(meta, icon);
        out.push_str(&format!("mkdir -p %{{buildroot}}/usr/{dir}\n"));
        out.push_str(&format!(
            "install -m 0644 %{{_sourcedir}}/{source} %{{buildroot}}/usr/{install}\n"
        ));
    }

    out.push_str("\n%files\n");
    out.push_str(&format!("/usr/bin/{}\n", meta.exec));
    out.push_str(&format!("/usr/share/applications/{desktop}\n"));
    out.push_str(&format!("/usr/share/metainfo/{appstream}\n"));
    for icon in icons {
        out.push_str(&format!("/usr/{}\n", icon.install_path(&meta.id)));
    }

    out
}

/// Inputs for staging an `rpmbuild` topdir.
#[derive(Debug, Clone)]
pub struct RpmInputs<'a> {
    pub meta: &'a AppMetadata,
    /// The compiled executable, or a directory holding it at its top level.
    pub payload: &'a Path,
    pub icons: &'a [Icon],
    /// Optional `SOURCE_DATE_EPOCH` in seconds for deterministic file timestamps.
    pub source_date_epoch: Option<u64>,
}

/// Lays out an `rpmbuild` topdir at `out`, which must not exist yet.
///
/// Creates `BUILD`, `RPMS`, `SOURCES`, `SPECS`, `SRPMS`, writes `SPECS/<pkg>.spec`,
/// and stages the executable, desktop entry, AppStream metainfo and icons under `SOURCES/`.
pub fn assemble_rpm(inputs: &RpmInputs, out: &Path) -> Result<Vec<PathBuf>, RpmError> {
    let meta = inputs.meta;
    if out.exists() {
        return Err(RpmError(format!(
            "{} already exists; an rpmbuild topdir is assembled from nothing",
            out.display()
        )));
    }
    if inputs.icons.is_empty() {
        return Err(RpmError(
            "no icon: pass --icon or set [bundle] icon for an .rpm package".into(),
        ));
    }
    let io_error = |what: &str, error: io::Error| RpmError(format!("{what}: {error}"));
    for dir in ["BUILD", "RPMS", "SOURCES", "SPECS", "SRPMS"] {
        let path = out.join(dir);
        fs::create_dir_all(&path).map_err(|e| io_error(&path.display().to_string(), e))?;
    }

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
        Ok::<(), RpmError>(())
    };

    write(
        &format!("SPECS/{}", spec_file_name(meta)),
        rpm_spec(meta, inputs.icons).as_bytes(),
        false,
    )?;

    let exec_source = resolve_executable(inputs.payload, &meta.exec)?;
    let exec_bytes =
        fs::read(&exec_source).map_err(|e| io_error(&exec_source.display().to_string(), e))?;
    write(&format!("SOURCES/{}", meta.exec), &exec_bytes, true)?;

    write(
        &format!("SOURCES/{}", desktop_file_name(meta)),
        desktop_entry(meta).as_bytes(),
        false,
    )?;
    write(
        &format!("SOURCES/{}", metainfo_file_name(meta)),
        metainfo(meta).as_bytes(),
        false,
    )?;
    for icon in inputs.icons {
        let bytes =
            fs::read(&icon.source).map_err(|e| io_error(&icon.source.display().to_string(), e))?;
        write(
            &format!("SOURCES/{}", icon_source_path(meta, icon)),
            &bytes,
            false,
        )?;
    }

    Ok(written)
}

fn resolve_executable(payload: &Path, exec: &str) -> Result<PathBuf, RpmError> {
    if payload.is_dir() {
        let candidate = payload.join(exec);
        if candidate.is_file() {
            return Ok(candidate);
        }
        return Err(RpmError(format!(
            "the payload directory {} has no {} at its top level",
            payload.display(),
            exec
        )));
    }
    if payload.is_file() {
        return Ok(payload.to_path_buf());
    }
    Err(RpmError(format!(
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
