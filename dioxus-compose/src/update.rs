//! Updating an application that was delivered outside a store.
//!
//! Linux delivers an application one of two ways, and they update differently.
//!
//! - **A Flatpak is updated by whoever installed it**: Flathub through GNOME Software,
//!   KDE Discover or `flatpak update`. The sandbox cannot write to its own installation,
//!   and an application that tried would be fighting the store. [`delivery`] says
//!   [`Delivery::Flatpak`] and nothing here offers to update it.
//! - **An AppImage is one file the person downloaded, and it updates itself.** The file
//!   carries a line saying where newer versions are published (its *update information*,
//!   in an ELF section named `.upd_info`). Each published version has a `.zsync` file
//!   beside it, and `appimageupdatetool` reads the line, fetches the `.zsync`, and
//!   downloads only the blocks of the new file that the old one does not already have.
//!
//! Nothing here runs on its own. The application decides when to check, asks the person,
//! and then applies. Checking reaches the network, so it belongs on a worker thread, never
//! on the thread that draws:
//!
//! ```no_run
//! use dioxus_compose::update::{UpdateCheck, delivery};
//!
//! if let Ok(updater) = delivery().appimage_updater() {
//!     std::thread::spawn(move || {
//!         if let Ok(UpdateCheck::Available) = updater.check() {
//!             // Tell the UI through a signal, ask the person, and only then:
//!             updater.apply().expect("the update downloads and verifies");
//!             let error = updater.relaunch(std::env::args_os().skip(1));
//!             eprintln!("could not start the updated application: {error}");
//!         }
//!     });
//! }
//! ```
//!
//! The work of updating belongs to `appimageupdatetool`, which the packager places inside
//! the AppImage. It verifies the result against the SHA-1 the `.zsync` file publishes, and
//! when the running AppImage is signed it refuses a new file that is not signed by the
//! same key. Reimplementing either here would only add a second place for them to be
//! wrong.

use std::ffi::{OsStr, OsString};
use std::fmt;
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::str::FromStr;

/// The ELF section an AppImage runtime reserves for the update information.
pub const UPDATE_INFORMATION_SECTION: &str = ".upd_info";

/// Names an `appimageupdatetool` to use instead of the one inside the AppImage.
pub const UPDATER_ENV: &str = "DIOXUS_COMPOSE_APPIMAGEUPDATETOOL";

/// Where the packager puts `appimageupdatetool`, relative to the AppImage's mount point.
pub const BUNDLED_UPDATER: &str = "usr/lib/appimageupdatetool/AppRun";

/// How the running application reached this machine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Delivery {
    /// Started from an AppImage. `path` is the AppImage file itself, which is what gets
    /// replaced; `mount` is where its contents are visible while it runs.
    AppImage {
        path: PathBuf,
        mount: Option<PathBuf>,
    },
    /// Running inside a Flatpak sandbox. Updates are the store's job.
    Flatpak { app_id: String },
    /// Neither: a binary someone built or unpacked themselves.
    Unpackaged,
}

/// Finds out how the running application was delivered.
///
/// Reads only the environment and one file: an AppImage runtime sets `APPIMAGE` and
/// `APPDIR` for the program it starts, and Flatpak sets `FLATPAK_ID` and places
/// `/.flatpak-info` in every sandbox.
pub fn delivery() -> Delivery {
    delivery_from(
        |name| std::env::var_os(name),
        Path::new("/.flatpak-info").exists(),
    )
}

/// [`delivery`] with the environment and the sandbox marker given rather than read, so a
/// caller (and a test) can ask what a given environment means.
pub fn delivery_from(env: impl Fn(&str) -> Option<OsString>, flatpak_info: bool) -> Delivery {
    let present = |value: Option<OsString>| value.filter(|value| !value.is_empty());
    if let Some(app_id) = present(env("FLATPAK_ID")) {
        return Delivery::Flatpak {
            app_id: app_id.to_string_lossy().into_owned(),
        };
    }
    if flatpak_info {
        return Delivery::Flatpak {
            app_id: String::new(),
        };
    }
    if let Some(path) = present(env("APPIMAGE")) {
        return Delivery::AppImage {
            path: PathBuf::from(path),
            mount: present(env("APPDIR")).map(PathBuf::from),
        };
    }
    Delivery::Unpackaged
}

impl Delivery {
    /// An updater for the running AppImage, found the way [`AppImageUpdater::new`] finds
    /// one. Anything other than an AppImage answers with the reason it cannot update
    /// itself.
    pub fn appimage_updater(&self) -> Result<AppImageUpdater, UpdateError> {
        match self {
            Delivery::AppImage { path, mount } => AppImageUpdater::new(path, mount.as_deref()),
            Delivery::Flatpak { app_id } => Err(UpdateError::ManagedByStore {
                app_id: app_id.clone(),
            }),
            Delivery::Unpackaged => Err(UpdateError::NotAnAppImage),
        }
    }
}

/// Where an AppImage's newer versions are published.
///
/// The three forms are the ones the AppImage tools define. Each one ends at a `.zsync`
/// file; the `.zsync` file then names the AppImage it describes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UpdateInformation {
    /// `zsync|<url of the .zsync file>`.
    Zsync { url: String },
    /// `gh-releases-zsync|<owner>|<repository>|<release>|<file name pattern>`.
    ///
    /// `release` is `latest` (the newest release that is not a pre-release) or a tag. The
    /// pattern may use `*`, which is how one line keeps matching files whose names carry
    /// a version.
    GitHubReleasesZsync {
        owner: String,
        repository: String,
        release: String,
        file_name: String,
    },
    /// `pling-v1-zsync|<product id>|<file name pattern>`, for the Pling store.
    PlingV1Zsync {
        product_id: String,
        file_name: String,
    },
}

impl FromStr for UpdateInformation {
    type Err = UpdateError;

    fn from_str(line: &str) -> Result<Self, Self::Err> {
        let invalid = |why: &str| UpdateError::InvalidUpdateInformation {
            line: line.to_owned(),
            reason: why.to_owned(),
        };
        let fields: Vec<&str> = line.split('|').collect();
        let nonempty = |fields: &[&str]| fields.iter().all(|field| !field.trim().is_empty());
        match fields.as_slice() {
            ["zsync", url] if nonempty(&[*url]) => Ok(UpdateInformation::Zsync {
                url: (*url).to_owned(),
            }),
            ["zsync", ..] => Err(invalid("`zsync` takes exactly one field, the URL")),
            ["gh-releases-zsync", owner, repository, release, file_name]
                if nonempty(&[*owner, *repository, *release, *file_name]) =>
            {
                Ok(UpdateInformation::GitHubReleasesZsync {
                    owner: (*owner).to_owned(),
                    repository: (*repository).to_owned(),
                    release: (*release).to_owned(),
                    file_name: (*file_name).to_owned(),
                })
            }
            ["gh-releases-zsync", ..] => Err(invalid(
                "`gh-releases-zsync` takes four fields: owner, repository, release and file name",
            )),
            ["pling-v1-zsync", product_id, file_name] if nonempty(&[*product_id, *file_name]) => {
                Ok(UpdateInformation::PlingV1Zsync {
                    product_id: (*product_id).to_owned(),
                    file_name: (*file_name).to_owned(),
                })
            }
            ["pling-v1-zsync", ..] => Err(invalid(
                "`pling-v1-zsync` takes two fields: product id and file name",
            )),
            _ => Err(invalid(
                "the first field must be `zsync`, `gh-releases-zsync` or `pling-v1-zsync`",
            )),
        }
    }
}

impl fmt::Display for UpdateInformation {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            UpdateInformation::Zsync { url } => write!(f, "zsync|{url}"),
            UpdateInformation::GitHubReleasesZsync {
                owner,
                repository,
                release,
                file_name,
            } => write!(
                f,
                "gh-releases-zsync|{owner}|{repository}|{release}|{file_name}"
            ),
            UpdateInformation::PlingV1Zsync {
                product_id,
                file_name,
            } => write!(f, "pling-v1-zsync|{product_id}|{file_name}"),
        }
    }
}

/// Reads the update information an AppImage carries, without reading the whole file.
///
/// `Ok(None)` is an AppImage built without any: the runtime always reserves the section,
/// and an empty one means nobody filled it in.
pub fn read_update_information(
    file: &mut (impl Read + Seek),
) -> Result<Option<String>, UpdateError> {
    let Some(bytes) = read_elf_section(file, UPDATE_INFORMATION_SECTION)? else {
        return Ok(None);
    };
    let end = bytes.iter().position(|&b| b == 0).unwrap_or(bytes.len());
    let text = std::str::from_utf8(&bytes[..end]).map_err(|_| {
        UpdateError::NotElf("the update information section is not UTF-8 text".to_owned())
    })?;
    let text = text.trim();
    Ok((!text.is_empty()).then(|| text.to_owned()))
}

/// The update information of the AppImage at `path`, parsed.
pub fn update_information_of(path: &Path) -> Result<Option<UpdateInformation>, UpdateError> {
    let mut file = File::open(path).map_err(|source| UpdateError::Io {
        action: format!("open {}", path.display()),
        source,
    })?;
    read_update_information(&mut file)?
        .map(|line| line.parse())
        .transpose()
}

/// Section headers are read whole, so a corrupt count must not turn into a huge read.
const MAX_SECTIONS: u64 = 1 << 16;
/// The runtime reserves 1024 bytes for update information; anything near this is not one.
const MAX_SECTION_BYTES: u64 = 1 << 20;

fn read_elf_section(
    file: &mut (impl Read + Seek),
    wanted: &str,
) -> Result<Option<Vec<u8>>, UpdateError> {
    let not_elf = |why: &str| UpdateError::NotElf(why.to_owned());
    let mut ident = [0u8; 64];
    file.seek(SeekFrom::Start(0)).map_err(UpdateError::read)?;
    let read = read_up_to(file, &mut ident).map_err(UpdateError::read)?;
    if read < 52 || &ident[..4] != b"\x7fELF" {
        return Err(not_elf("the file does not start with an ELF header"));
    }
    let wide = match ident[4] {
        1 => false,
        2 => true,
        _ => return Err(not_elf("the ELF class is neither 32 nor 64 bit")),
    };
    let big = match ident[5] {
        1 => false,
        2 => true,
        _ => {
            return Err(not_elf(
                "the ELF byte order is neither little nor big endian",
            ));
        }
    };
    let int = Endian { big };
    let (shoff, shentsize, shnum, shstrndx) = if wide {
        if read < 64 {
            return Err(not_elf("the 64-bit ELF header is cut short"));
        }
        (
            int.u64(&ident[0x28..0x30]),
            int.u16(&ident[0x3a..0x3c]),
            int.u16(&ident[0x3c..0x3e]),
            int.u16(&ident[0x3e..0x40]),
        )
    } else {
        (
            int.u32(&ident[0x20..0x24]) as u64,
            int.u16(&ident[0x2e..0x30]),
            int.u16(&ident[0x30..0x32]),
            int.u16(&ident[0x32..0x34]),
        )
    };
    let minimum = if wide { 64 } else { 40 };
    if shoff == 0 || shnum == 0 {
        return Ok(None);
    }
    if (shentsize as usize) < minimum || shnum as u64 > MAX_SECTIONS || shstrndx >= shnum {
        return Err(not_elf("the section header table is malformed"));
    }
    let mut table = vec![0u8; shentsize as usize * shnum as usize];
    file.seek(SeekFrom::Start(shoff))
        .map_err(UpdateError::read)?;
    file.read_exact(&mut table)
        .map_err(|_| not_elf("the section header table runs past the end of the file"))?;
    let section = |index: usize| {
        let header = &table[index * shentsize as usize..][..shentsize as usize];
        if wide {
            (
                int.u32(&header[0..4]),
                int.u64(&header[24..32]),
                int.u64(&header[32..40]),
            )
        } else {
            (
                int.u32(&header[0..4]),
                int.u32(&header[16..20]) as u64,
                int.u32(&header[20..24]) as u64,
            )
        }
    };
    let (_, names_offset, names_size) = section(shstrndx as usize);
    let names = read_at(file, names_offset, names_size)?;
    for index in 0..shnum as usize {
        let (name, offset, size) = section(index);
        let Some(rest) = names.get(name as usize..) else {
            continue;
        };
        let end = rest.iter().position(|&b| b == 0).unwrap_or(rest.len());
        if &rest[..end] == wanted.as_bytes() {
            return read_at(file, offset, size).map(Some);
        }
    }
    Ok(None)
}

fn read_at(file: &mut (impl Read + Seek), offset: u64, size: u64) -> Result<Vec<u8>, UpdateError> {
    if size > MAX_SECTION_BYTES {
        return Err(UpdateError::NotElf(
            "a section is larger than any this reader expects".to_owned(),
        ));
    }
    let mut bytes = vec![0u8; size as usize];
    file.seek(SeekFrom::Start(offset))
        .map_err(UpdateError::read)?;
    file.read_exact(&mut bytes)
        .map_err(|_| UpdateError::NotElf("a section runs past the end of the file".to_owned()))?;
    Ok(bytes)
}

fn read_up_to(file: &mut impl Read, buffer: &mut [u8]) -> io::Result<usize> {
    let mut filled = 0;
    while filled < buffer.len() {
        match file.read(&mut buffer[filled..]) {
            Ok(0) => break,
            Ok(n) => filled += n,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
            Err(error) => return Err(error),
        }
    }
    Ok(filled)
}

#[derive(Clone, Copy)]
struct Endian {
    big: bool,
}

impl Endian {
    fn u16(self, bytes: &[u8]) -> u16 {
        let bytes = [bytes[0], bytes[1]];
        if self.big {
            u16::from_be_bytes(bytes)
        } else {
            u16::from_le_bytes(bytes)
        }
    }

    fn u32(self, bytes: &[u8]) -> u32 {
        let bytes = [bytes[0], bytes[1], bytes[2], bytes[3]];
        if self.big {
            u32::from_be_bytes(bytes)
        } else {
            u32::from_le_bytes(bytes)
        }
    }

    fn u64(self, bytes: &[u8]) -> u64 {
        let mut array = [0u8; 8];
        array.copy_from_slice(&bytes[..8]);
        if self.big {
            u64::from_be_bytes(array)
        } else {
            u64::from_le_bytes(array)
        }
    }
}

/// What a check found.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UpdateCheck {
    /// The published file differs from the running one.
    Available,
    /// The published file is the one that is running.
    UpToDate,
}

/// Checks for and applies updates to one AppImage, through `appimageupdatetool`.
#[derive(Debug, Clone)]
pub struct AppImageUpdater {
    appimage: PathBuf,
    information: UpdateInformation,
    tool: PathBuf,
}

impl AppImageUpdater {
    /// An updater for the AppImage at `appimage`, whose contents are mounted at `mount`.
    ///
    /// The tool is looked for in three places, first match wins: the path in
    /// `DIOXUS_COMPOSE_APPIMAGEUPDATETOOL`, the copy the packager places inside the
    /// AppImage, and `appimageupdatetool` on `PATH`.
    pub fn new(appimage: &Path, mount: Option<&Path>) -> Result<Self, UpdateError> {
        let tool = find_tool(
            std::env::var_os(UPDATER_ENV).as_deref(),
            mount,
            std::env::var_os("PATH").as_deref(),
        )
        .ok_or(UpdateError::UpdaterMissing)?;
        Self::with_tool(appimage, &tool)
    }

    /// An updater that runs the given `appimageupdatetool`.
    pub fn with_tool(appimage: &Path, tool: &Path) -> Result<Self, UpdateError> {
        let information =
            update_information_of(appimage)?.ok_or(UpdateError::NoUpdateInformation)?;
        Ok(Self {
            appimage: appimage.to_owned(),
            information,
            tool: tool.to_owned(),
        })
    }

    /// The AppImage this updater replaces.
    pub fn appimage(&self) -> &Path {
        &self.appimage
    }

    /// Where the AppImage says its newer versions are.
    pub fn update_information(&self) -> &UpdateInformation {
        &self.information
    }

    /// Asks whether the published file differs from the one on disk. Reaches the
    /// network, so call it from a worker thread.
    pub fn check(&self) -> Result<UpdateCheck, UpdateError> {
        let output = self
            .command()
            .arg("--check-for-update")
            .arg(&self.appimage)
            .output();
        let output = output.map_err(|source| UpdateError::Io {
            action: format!("run {}", self.tool.display()),
            source,
        })?;
        // The tool's own contract: 1 when the published file differs, 0 when it does
        // not, anything else when it could not tell.
        match output.status.code() {
            Some(0) => Ok(UpdateCheck::UpToDate),
            Some(1) => Ok(UpdateCheck::Available),
            code => Err(UpdateError::UpdaterFailed {
                code,
                output: describe_output(&output.stdout, &output.stderr),
            }),
        }
    }

    /// Downloads the new version and puts it where the running AppImage is.
    ///
    /// Only the blocks that changed are downloaded. The file is replaced, not edited in
    /// place, so the running process keeps reading the version it started from until it
    /// exits or [`relaunch`](Self::relaunch)es.
    pub fn apply(&self) -> Result<(), UpdateError> {
        let output = self
            .command()
            .arg("--overwrite")
            .arg(&self.appimage)
            .output();
        let output = output.map_err(|source| UpdateError::Io {
            action: format!("run {}", self.tool.display()),
            source,
        })?;
        if output.status.success() {
            Ok(())
        } else {
            Err(UpdateError::UpdaterFailed {
                code: output.status.code(),
                output: describe_output(&output.stdout, &output.stderr),
            })
        }
    }

    /// Starts the AppImage on disk in place of this process. Returns only on failure.
    pub fn relaunch<I, S>(&self, args: I) -> io::Error
    where
        I: IntoIterator<Item = S>,
        S: AsRef<OsStr>,
    {
        let mut command = Command::new(&self.appimage);
        command.args(args);
        // Each runtime sets these for the program it starts. Left in place, the new
        // process would report the old mount point until its own runtime replaced them.
        for name in ["APPDIR", "APPIMAGE", "ARGV0", "OWD"] {
            command.env_remove(name);
        }
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            command.exec()
        }
        #[cfg(not(unix))]
        {
            match command.spawn() {
                Ok(_) => std::process::exit(0),
                Err(error) => error,
            }
        }
    }

    fn command(&self) -> Command {
        let mut command = Command::new(&self.tool);
        // The tool is itself an extracted AppImage. It must see its own directory, not
        // the mount point of the application that started it.
        for name in ["APPDIR", "APPIMAGE", "ARGV0", "OWD"] {
            command.env_remove(name);
        }
        if self.tool.file_name() == Some(OsStr::new("AppRun")) {
            if let Some(dir) = self.tool.parent() {
                command.env("APPDIR", dir);
            }
        }
        command
    }
}

/// Where `appimageupdatetool` is, given the override, the AppImage's mount point and
/// `PATH`, first match wins.
pub fn find_tool(
    override_path: Option<&OsStr>,
    mount: Option<&Path>,
    path: Option<&OsStr>,
) -> Option<PathBuf> {
    if let Some(explicit) = override_path.filter(|value| !value.is_empty()) {
        return Some(PathBuf::from(explicit));
    }
    if let Some(bundled) = mount.map(|mount| mount.join(BUNDLED_UPDATER)) {
        if bundled.is_file() {
            return Some(bundled);
        }
    }
    std::env::split_paths(path?)
        .map(|dir| dir.join("appimageupdatetool"))
        .find(|candidate| candidate.is_file())
}

fn describe_output(stdout: &[u8], stderr: &[u8]) -> String {
    let mut text = String::from_utf8_lossy(stdout).trim().to_owned();
    let stderr = String::from_utf8_lossy(stderr);
    if !stderr.trim().is_empty() {
        if !text.is_empty() {
            text.push('\n');
        }
        text.push_str(stderr.trim());
    }
    text
}

/// Why an update could not be checked for or applied.
#[derive(Debug)]
pub enum UpdateError {
    /// The application was not started from an AppImage, so there is no file to replace.
    NotAnAppImage,
    /// A store installed this application and updates it; replacing it from inside would
    /// fight the store.
    ManagedByStore { app_id: String },
    /// The AppImage was built without update information, so it does not know where its
    /// newer versions are.
    NoUpdateInformation,
    /// The update information is not in a form the AppImage tools define.
    InvalidUpdateInformation { line: String, reason: String },
    /// The file is not an ELF executable, or its section table is damaged.
    NotElf(String),
    /// No `appimageupdatetool` inside the AppImage, in the override variable, or on `PATH`.
    UpdaterMissing,
    /// `appimageupdatetool` ran and reported a failure.
    UpdaterFailed { code: Option<i32>, output: String },
    /// Reading the AppImage or starting the tool failed.
    Io { action: String, source: io::Error },
}

impl UpdateError {
    fn read(source: io::Error) -> Self {
        UpdateError::Io {
            action: "read the AppImage".to_owned(),
            source,
        }
    }
}

impl fmt::Display for UpdateError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            UpdateError::NotAnAppImage => write!(
                f,
                "this application was not started from an AppImage, so there is no file to update"
            ),
            UpdateError::ManagedByStore { app_id } if app_id.is_empty() => write!(
                f,
                "this application runs in a Flatpak sandbox; the store that installed it updates it"
            ),
            UpdateError::ManagedByStore { app_id } => write!(
                f,
                "this application is the Flatpak {app_id}; the store that installed it updates it"
            ),
            UpdateError::NoUpdateInformation => write!(
                f,
                "this AppImage was built without update information, so it does not know where newer versions are published"
            ),
            UpdateError::InvalidUpdateInformation { line, reason } => {
                write!(f, "the update information `{line}` is not usable: {reason}")
            }
            UpdateError::NotElf(reason) => write!(f, "not a readable AppImage: {reason}"),
            UpdateError::UpdaterMissing => write!(
                f,
                "appimageupdatetool was not found inside the AppImage ({BUNDLED_UPDATER}), in {UPDATER_ENV}, or on PATH"
            ),
            UpdateError::UpdaterFailed { code, output } => {
                match code {
                    Some(code) => write!(f, "appimageupdatetool exited with status {code}")?,
                    None => write!(f, "appimageupdatetool was stopped by a signal")?,
                }
                if !output.is_empty() {
                    write!(f, ":\n{output}")?;
                }
                Ok(())
            }
            UpdateError::Io { action, source } => write!(f, "could not {action}: {source}"),
        }
    }
}

impl std::error::Error for UpdateError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            UpdateError::Io { source, .. } => Some(source),
            _ => None,
        }
    }
}
