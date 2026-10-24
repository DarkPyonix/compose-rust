//! The AppImage channel: an AppDir laid out for `appimagetool`.

use std::fmt;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use crate::appstream::{appdata_file_name, metainfo};
use crate::desktop::{desktop_entry, desktop_file_name};
use crate::icons::{Icon, best};
use crate::metadata::AppMetadata;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppImageError(pub String);

impl fmt::Display for AppImageError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for AppImageError {}

/// The architecture name AppImage files and tools use for a Rust target architecture.
pub fn appimage_arch(rust_arch: &str) -> Option<&'static str> {
    match rust_arch {
        "x86_64" => Some("x86_64"),
        "aarch64" => Some("aarch64"),
        "x86" | "i686" => Some("i686"),
        "arm" | "armv7" => Some("armhf"),
        _ => None,
    }
}

/// The application name as it appears in file names: anything but letters, digits, `.`,
/// `_` and `-` becomes `_`.
pub fn file_safe(name: &str) -> String {
    name.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
                c
            } else {
                '_'
            }
        })
        .collect()
}

/// `<Name>-<version>-<arch>.AppImage`, the shape the AppImage tools expect.
pub fn appimage_file_name(meta: &AppMetadata, arch: &str) -> String {
    format!("{}-{}-{arch}.AppImage", file_safe(&meta.name), meta.version)
}

/// The script the runtime starts. It runs the application from the directory it was
/// copied into, so the renderer and its companions sit beside it the way they did when
/// it was built.
pub fn apprun(meta: &AppMetadata) -> String {
    format!(
        "#!/bin/sh\n\
         # Written by packager-linux. Starts {name} from inside the AppImage.\n\
         # The runtime sets APPDIR to where the image is mounted. Falling back to this\n\
         # file's directory lets the unpacked AppDir run too.\n\
         APPDIR=\"${{APPDIR:-$(dirname \"$(readlink -f \"$0\")\")}}\"\n\
         export APPDIR\n\
         # The renderer sits in lib/ beside the executable (or, in older packages, beside\n\
         # it). An executable with an rpath finds it on its own; this covers one without.\n\
         LD_LIBRARY_PATH=\"$APPDIR/usr/lib/{exec}/lib:$APPDIR/usr/lib/{exec}${{LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}}\"\n\
         export LD_LIBRARY_PATH\n\
         exec \"$APPDIR/usr/lib/{exec}/{exec}\" \"$@\"\n",
        name = meta.name.replace('\n', " "),
        exec = meta.exec
    )
}

/// What goes into an AppDir.
#[derive(Debug, Clone)]
pub struct AppDirInputs<'a> {
    pub meta: &'a AppMetadata,
    /// A directory holding the executable and whatever must sit beside it (the renderer
    /// and its companions), or the executable alone.
    pub payload: &'a Path,
    pub icons: &'a [Icon],
}

/// Lays out an AppDir at `out`, which must not exist yet. Returns the files it wrote,
/// relative to `out`, in the order written.
pub fn assemble_appdir(inputs: &AppDirInputs, out: &Path) -> Result<Vec<PathBuf>, AppImageError> {
    let meta = inputs.meta;
    if out.exists() {
        return Err(AppImageError(format!(
            "{} already exists; an AppDir is assembled from nothing",
            out.display()
        )));
    }
    let io_error = |what: &str, error: io::Error| AppImageError(format!("{what}: {error}"));
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
        written.push(PathBuf::from(relative));
        Ok::<(), AppImageError>(())
    };

    let icon = best(inputs.icons).ok_or_else(|| {
        AppImageError("no icon: an AppImage shows one in file managers and menus".into())
    })?;

    let desktop = desktop_entry(meta);
    write("AppRun", apprun(meta).as_bytes(), true)?;
    write(&desktop_file_name(meta), desktop.as_bytes(), false)?;
    let icon_bytes =
        fs::read(&icon.source).map_err(|e| io_error(&icon.source.display().to_string(), e))?;
    write(&icon.root_name(&meta.id), &icon_bytes, false)?;
    write(
        &format!("usr/share/applications/{}", desktop_file_name(meta)),
        desktop.as_bytes(),
        false,
    )?;
    write(
        &format!("usr/share/metainfo/{}", appdata_file_name(meta)),
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

    let lib = out.join("usr/lib").join(&meta.exec);
    if inputs.payload.is_dir() {
        copy_tree(inputs.payload, &lib)
            .map_err(|e| io_error(&inputs.payload.display().to_string(), e))?;
    } else {
        fs::create_dir_all(&lib).map_err(|e| io_error(&lib.display().to_string(), e))?;
        fs::copy(inputs.payload, lib.join(&meta.exec))
            .map_err(|e| io_error(&inputs.payload.display().to_string(), e))?;
    }
    let executable = lib.join(&meta.exec);
    if !executable.is_file() {
        return Err(AppImageError(format!(
            "the payload has no {} at its top level",
            meta.exec
        )));
    }
    set_executable(&executable).map_err(|e| io_error(&executable.display().to_string(), e))?;
    written.push(PathBuf::from(format!("usr/lib/{0}/{0}", meta.exec)));

    Ok(written)
}

/// Copies a directory tree, keeping symbolic links as links and permissions as they are.
pub fn copy_tree(from: &Path, to: &Path) -> io::Result<()> {
    fs::create_dir_all(to)?;
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        let kind = entry.file_type()?;
        let target = to.join(entry.file_name());
        if kind.is_symlink() {
            let link = fs::read_link(entry.path())?;
            symlink(&link, &target, &entry.path())?;
        } else if kind.is_dir() {
            copy_tree(&entry.path(), &target)?;
        } else {
            fs::copy(entry.path(), &target)?;
        }
    }
    Ok(())
}

#[cfg(unix)]
fn symlink(link: &Path, target: &Path, _original: &Path) -> io::Result<()> {
    std::os::unix::fs::symlink(link, target)
}

#[cfg(not(unix))]
fn symlink(_link: &Path, target: &Path, original: &Path) -> io::Result<()> {
    if original.is_dir() {
        copy_tree(original, target)
    } else {
        fs::copy(original, target).map(|_| ())
    }
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
