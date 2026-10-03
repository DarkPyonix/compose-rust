//! Command line for the Linux packager. Run with no arguments for usage.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::{SystemTime, UNIX_EPOCH};

use packager_linux::appimage::{
    AppDirInputs, UpdateChannel, appimage_arch, appimage_file_name, assemble_appdir,
};
use packager_linux::appstream::{metainfo, metainfo_file_name};
use packager_linux::desktop::{desktop_entry, desktop_file_name};
use packager_linux::flatpak::{FlatpakOptions, Payload, SourceRef, icon_source_path, manifest};
use packager_linux::icons::{Icon, check_for_flathub};
use packager_linux::metadata::{
    AppMetadata, BuildFacts, Screenshot, check_screenshot_url, release_date,
};

const USAGE: &str = "\
usage: packager-linux <command> --dioxus-toml <file> [common options] [command options]

commands:
  metadata            write <id>.desktop, <id>.metainfo.xml and the icons into --out
  appdir              lay out an AppDir for appimagetool at --out
  update-information  print the AppImage update information line
  file-name           print the AppImage file name, <Name>-<version>-<arch>.AppImage
  flatpak             write a flatpak-builder manifest and its packaging files into --out

common options:
  --dioxus-toml <file>   the application's Dioxus.toml
  --overlay <file>       a file of the same shape merged over it (repeatable)
  --version <version>    overrides [bundle] version
  --date <YYYY-MM-DD>    release date; defaults to SOURCE_DATE_EPOCH, then today (UTC)
  --exec <name>          installed executable name; overrides [linux.store] exec
  --icon <file>          a square PNG or an SVG (repeatable); overrides [bundle] icon
  --arch <arch>          x86_64 or aarch64; defaults to this machine's
  --app-id <id>          overrides the identifier, for a store listing under another ID
  --screenshot <url>     a screenshot for the store listing (repeatable, first is the
                         default); replaces [linux.store] screenshots. A raw GitHub URL
                         must be pinned to a tag or a commit
  --screenshot-caption <text>  a caption for each --screenshot, in the same order

appdir:
  --payload <dir|file>   the executable, or a directory holding it and its renderer
  --updater <dir>        an extracted appimageupdatetool to carry inside
  --out <dir>

update-information:
  --github <owner/repo> --release <tag>
                         gh-releases-zsync, the release with that tag (or `latest`,
                         `latest-pre`, `latest-all` to search)
  --zsync-url <url>      zsync, one fixed URL

flatpak:
  --out <dir>
  --prebuilt-url <url> --prebuilt-sha256 <hex> [--prebuilt-arch <arch>] (repeatable,
      one per architecture) | --prebuilt-path <archive>
  --source-url <url> --source-sha256 <hex> | --source-path <archive>
      with --cargo-sources <file> --package <name> --bin <name> [--cargo-arg <arg>]...
      and optionally --renderer-url <url> --renderer-sha256 <hex> [--renderer-arch <arch>]
      (repeatable) | --renderer-path <archive>
  --wayland              Wayland socket with X11 as fallback, for a Wayland-capable renderer
";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("error: {message}");
            ExitCode::FAILURE
        }
    }
}

/// Options as given: each name maps to every value it was given, in order.
struct Options {
    values: BTreeMap<String, Vec<String>>,
}

const FLAGS: &[&str] = &["wayland"];

impl Options {
    fn parse(args: &[String]) -> Result<Options, String> {
        let mut values: BTreeMap<String, Vec<String>> = BTreeMap::new();
        let mut iter = args.iter();
        while let Some(arg) = iter.next() {
            let Some(name) = arg.strip_prefix("--") else {
                return Err(format!("unexpected argument `{arg}`\n\n{USAGE}"));
            };
            let (name, value) = match name.split_once('=') {
                Some((name, value)) => (name.to_owned(), value.to_owned()),
                None if FLAGS.contains(&name) => (name.to_owned(), String::new()),
                None => {
                    let value = iter
                        .next()
                        .ok_or_else(|| format!("--{name} needs a value"))?;
                    (name.to_owned(), value.clone())
                }
            };
            values.entry(name).or_default().push(value);
        }
        Ok(Options { values })
    }

    fn one(&self, name: &str) -> Result<Option<String>, String> {
        match self.values.get(name).map(Vec::as_slice) {
            None => Ok(None),
            Some([value]) => Ok(Some(value.clone())),
            Some(_) => Err(format!("--{name} was given more than once")),
        }
    }

    fn required(&self, name: &str) -> Result<String, String> {
        self.one(name)?
            .ok_or_else(|| format!("--{name} is required"))
    }

    fn all(&self, name: &str) -> Vec<String> {
        self.values.get(name).cloned().unwrap_or_default()
    }

    fn flag(&self, name: &str) -> bool {
        self.values.contains_key(name)
    }

    fn reject_unknown(&self, known: &[&str]) -> Result<(), String> {
        for name in self.values.keys() {
            if !known.contains(&name.as_str()) {
                return Err(format!(
                    "--{name} is not an option of this command\n\n{USAGE}"
                ));
            }
        }
        Ok(())
    }
}

const COMMON: &[&str] = &[
    "dioxus-toml",
    "overlay",
    "version",
    "date",
    "exec",
    "icon",
    "arch",
    "app-id",
    "screenshot",
    "screenshot-caption",
];

fn run(args: &[String]) -> Result<(), String> {
    let Some((command, rest)) = args.split_first() else {
        return Err(USAGE.to_owned());
    };
    let options = Options::parse(rest)?;
    let extra: &[&str] = match command.as_str() {
        "metadata" => &["out"],
        "appdir" => &["payload", "updater", "out"],
        "update-information" => &["github", "release", "zsync-url"],
        "file-name" => &[],
        "flatpak" => &[
            "out",
            "prebuilt-url",
            "prebuilt-sha256",
            "prebuilt-path",
            "prebuilt-arch",
            "source-url",
            "source-sha256",
            "source-path",
            "cargo-sources",
            "package",
            "bin",
            "cargo-arg",
            "renderer-url",
            "renderer-sha256",
            "renderer-path",
            "renderer-arch",
            "wayland",
        ],
        "help" | "--help" | "-h" => {
            print!("{USAGE}");
            return Ok(());
        }
        other => return Err(format!("unknown command `{other}`\n\n{USAGE}")),
    };
    let known: Vec<&str> = COMMON.iter().chain(extra).copied().collect();
    options.reject_unknown(&known)?;

    let (meta, icons) = load(&options)?;
    let arch = match options.one("arch")? {
        Some(arch) => arch,
        None => appimage_arch(std::env::consts::ARCH)
            .ok_or_else(|| format!("no AppImage architecture for {}", std::env::consts::ARCH))?
            .to_owned(),
    };

    match command.as_str() {
        "metadata" => {
            let out = PathBuf::from(options.required("out")?);
            write(
                &out.join(desktop_file_name(&meta)),
                desktop_entry(&meta).as_bytes(),
            )?;
            write(
                &out.join(metainfo_file_name(&meta)),
                metainfo(&meta).as_bytes(),
            )?;
            for icon in &icons {
                copy(&icon.source, &out.join(icon_source_path(&meta, icon)))?;
            }
        }
        "appdir" => {
            let out = PathBuf::from(options.required("out")?);
            let payload = PathBuf::from(options.required("payload")?);
            let updater = options.one("updater")?.map(PathBuf::from);
            let written = assemble_appdir(
                &AppDirInputs {
                    meta: &meta,
                    payload: &payload,
                    icons: &icons,
                    updater: updater.as_deref(),
                },
                &out,
            )
            .map_err(|e| e.to_string())?;
            for path in written {
                println!("{}", path.display());
            }
        }
        "update-information" => {
            let release = options.one("release")?;
            let channel = match (options.one("github")?, options.one("zsync-url")?) {
                (Some(slug), None) => {
                    let release = release.ok_or(
                        "--github needs --release <tag>: the release the AppImage looks in \
                         for its updates, or `latest` for the newest one that is not a \
                         pre-release",
                    )?;
                    UpdateChannel::github(&slug, &release)
                }
                (None, Some(url)) => {
                    if release.is_some() {
                        return Err("--release goes with --github, not --zsync-url".into());
                    }
                    UpdateChannel::zsync(&url)
                }
                _ => return Err("pass exactly one of --github and --zsync-url".into()),
            }
            .map_err(|e| e.to_string())?;
            println!("{}", channel.update_information(&meta, &arch));
        }
        "file-name" => println!("{}", appimage_file_name(&meta, &arch)),
        "flatpak" => {
            check_for_flathub(&icons).map_err(|e| e.to_string())?;
            let out = PathBuf::from(options.required("out")?);
            let payload = flatpak_payload(&options)?;
            let text = manifest(
                &meta,
                &FlatpakOptions {
                    payload,
                    icons: &icons,
                    wayland: options.flag("wayland"),
                },
            );
            write(&out.join(format!("{}.json", meta.id)), text.as_bytes())?;
            write(
                &out.join(desktop_file_name(&meta)),
                desktop_entry(&meta).as_bytes(),
            )?;
            write(
                &out.join(metainfo_file_name(&meta)),
                metainfo(&meta).as_bytes(),
            )?;
            for icon in &icons {
                copy(&icon.source, &out.join(icon_source_path(&meta, icon)))?;
            }
            println!("{}", out.join(format!("{}.json", meta.id)).display());
        }
        _ => unreachable!("every command was matched above"),
    }
    Ok(())
}

fn load(options: &Options) -> Result<(AppMetadata, Vec<Icon>), String> {
    let toml_path = PathBuf::from(options.required("dioxus-toml")?);
    let text = read_text(&toml_path)?;
    let mut overlays: Vec<String> = options
        .all("overlay")
        .iter()
        .map(|path| read_text(Path::new(path)))
        .collect::<Result<_, _>>()?;
    if let Some(id) = options.one("app-id")? {
        if id.contains(['"', '\\', '\n']) {
            return Err(format!("--app-id `{id}` is not an application identifier"));
        }
        overlays.push(format!("[linux]\nidentifier = \"{id}\"\n"));
    }
    let overlay_refs: Vec<&str> = overlays.iter().map(String::as_str).collect();
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let date = match options.one("date")? {
        Some(date) => date,
        None => release_date(std::env::var("SOURCE_DATE_EPOCH").ok().as_deref(), now),
    };
    let facts = BuildFacts {
        version: options.one("version")?,
        date,
        exec: options.one("exec")?,
    };
    let mut meta = AppMetadata::resolve(&text, &overlay_refs, &facts).map_err(|e| e.to_string())?;
    let shots = options.all("screenshot");
    let captions = options.all("screenshot-caption");
    if !captions.is_empty() && captions.len() != shots.len() {
        return Err("give --screenshot-caption for every --screenshot or for none".into());
    }
    if !shots.is_empty() {
        meta.screenshots = shots
            .into_iter()
            .enumerate()
            .map(|(index, url)| {
                check_screenshot_url(&url).map_err(|e| e.to_string())?;
                Ok(Screenshot {
                    url,
                    caption: captions.get(index).cloned(),
                })
            })
            .collect::<Result<_, String>>()?;
    }

    let given = options.all("icon");
    let paths: Vec<PathBuf> = if given.is_empty() {
        let base = toml_path.parent().unwrap_or(Path::new("."));
        meta.icons.iter().map(|icon| base.join(icon)).collect()
    } else {
        given.iter().map(PathBuf::from).collect()
    };
    let icons = paths
        .iter()
        .map(|path| {
            let bytes = fs::read(path).map_err(|e| format!("{}: {e}", path.display()))?;
            Icon::classify(path, &bytes).map_err(|e| e.to_string())
        })
        .collect::<Result<Vec<_>, _>>()?;
    Ok((meta, icons))
}

/// The sources named by `--<prefix>-url`/`--<prefix>-sha256` (repeatable, with an
/// optional `--<prefix>-arch` for each) or by one `--<prefix>-path`.
fn sources(options: &Options, prefix: &str) -> Result<Vec<SourceRef>, String> {
    let urls = options.all(&format!("{prefix}-url"));
    let shas = options.all(&format!("{prefix}-sha256"));
    let arches = options.all(&format!("{prefix}-arch"));
    let path = options.one(&format!("{prefix}-path"))?;
    if let Some(path) = path {
        if !urls.is_empty() || !shas.is_empty() || !arches.is_empty() {
            return Err(format!("--{prefix}-path stands alone"));
        }
        return Ok(vec![SourceRef::Path(path)]);
    }
    if urls.len() != shas.len() {
        return Err(format!(
            "every --{prefix}-url needs its own --{prefix}-sha256, in the same order"
        ));
    }
    if !arches.is_empty() && arches.len() != urls.len() {
        return Err(format!(
            "give --{prefix}-arch for every --{prefix}-url or for none"
        ));
    }
    if urls.len() > 1 && arches.is_empty() {
        return Err(format!(
            "several --{prefix}-url need a --{prefix}-arch each"
        ));
    }
    urls.into_iter()
        .zip(shas)
        .enumerate()
        .map(|(index, (url, sha256))| {
            if sha256.len() != 64 || !sha256.chars().all(|c| c.is_ascii_hexdigit()) {
                return Err(format!("--{prefix}-sha256 must be 64 hex digits"));
            }
            Ok(SourceRef::Url {
                url,
                sha256: sha256.to_ascii_lowercase(),
                arch: arches.get(index).cloned(),
            })
        })
        .collect()
}

fn flatpak_payload(options: &Options) -> Result<Payload, String> {
    let prebuilt = sources(options, "prebuilt")?;
    let source = sources(options, "source")?;
    match (prebuilt.is_empty(), source.len()) {
        (false, 0) => {
            for name in [
                "cargo-sources",
                "package",
                "bin",
                "cargo-arg",
                "renderer-url",
                "renderer-path",
            ] {
                if options.flag(name) {
                    return Err(format!("--{name} belongs to a build from source"));
                }
            }
            Ok(Payload::Prebuilt { archives: prebuilt })
        }
        (true, 1) => Ok(Payload::FromSource {
            source: source.into_iter().next().expect("one source"),
            cargo_sources: options.required("cargo-sources")?,
            package: options.required("package")?,
            bin: options.required("bin")?,
            cargo_args: options.all("cargo-arg"),
            renderer: sources(options, "renderer")?,
        }),
        _ => Err("pass prebuilt archives or one source archive, not both and not neither".into()),
    }
}

fn read_text(path: &Path) -> Result<String, String> {
    fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))
}

fn write(path: &Path, bytes: &[u8]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    }
    fs::write(path, bytes).map_err(|e| format!("{}: {e}", path.display()))
}

fn copy(from: &Path, to: &Path) -> Result<(), String> {
    let bytes = fs::read(from).map_err(|e| format!("{}: {e}", from.display()))?;
    write(to, &bytes)
}
