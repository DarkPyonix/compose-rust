#!/usr/bin/env python3
"""package-velopack: direct-distribution packages and an update feed, made with Velopack's vpk.

One run packs one version for the OS it runs on:

- macOS: an ad hoc signed .app with Velopack's UpdateMac, its portable .zip, a .dmg that
  holds that same .app with the Gatekeeper notes, and the full and delta .nupkg;
- Linux: Velopack's self-updating .AppImage and the full and delta .nupkg;
- Windows: Setup.exe, a portable .zip, optionally a .msi, and the full and delta .nupkg;

and, on every OS, `releases.<channel>.json`, the feed the Velopack SDK reads. See
scripts/package-velopack.md for the options, the files and how to publish them.

    package-velopack.py --project samples/calculator \\
        --executable target/release/sample-calculator --output-dir dist/releases
    package-velopack.py --project ember/app --pack-id dev.darkpyonix.ember \\
        --pack-version 1.2.0 --executable target/release/ember \\
        --delta-dir previous-release --output-dir dist/releases

What it prints on stdout is one JSON object naming the files it made and the ones to upload;
vpk's own output goes to stderr as it runs.
"""

import argparse
import contextlib
import hashlib
import json
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

if sys.version_info < (3, 11):
    raise SystemExit("package-velopack requires Python 3.11 or newer for tomllib")

import tomllib

SCRIPTS_DIR = Path(__file__).resolve().parent
PACKAGER_MACOS_DIR = SCRIPTS_DIR / "packager-macos"
if str(PACKAGER_MACOS_DIR) not in sys.path:
    sys.path.insert(0, str(PACKAGER_MACOS_DIR))

# The vpk the workflows install, and the velopack crate an application should use with it:
# Velopack wants the SDK and vpk to be the same version.
VPK_VERSION = "1.2.161"

# vpk refuses a pack id that is not a valid NuGet id.
_PACK_ID = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]*$")
# vpk refuses a version that is not SemVer 2.
_SEMVER = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$"
)

AD_HOC = "-"


class VelopackError(Exception):
    """An error that occurred during Velopack packaging or verification."""


@dataclass
class ProjectMetadata:
    pack_id: str
    version: str
    title: str
    authors: str
    executable_name: str
    icon: Path | None = None
    icons: list[Path] = field(default_factory=list)


def _read_toml(path: Path) -> dict:
    try:
        return tomllib.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except tomllib.TOMLDecodeError as error:
        raise VelopackError(f"{path} is not valid TOML: {error}") from error


def parse_metadata(project_dir: Path | None, overrides: dict) -> ProjectMetadata:
    """Metadata from Dioxus.toml and Cargo.toml, with the command line's values first."""
    pack_id = overrides.get("pack_id")
    version = overrides.get("pack_version")
    title = overrides.get("pack_title")
    authors = overrides.get("pack_authors")
    executable_name = overrides.get("executable_name")
    explicit_icon = overrides.get("icon")
    icons: list[Path] = []

    if project_dir is not None:
        project_dir = Path(project_dir)
        if not project_dir.is_dir():
            raise VelopackError(f"--project {project_dir} is not a directory")
        dioxus = _read_toml(project_dir / "Dioxus.toml")
        application = dioxus.get("application", {})
        bundle = dioxus.get("bundle", {})
        title = title or application.get("name")
        pack_id = pack_id or bundle.get("identifier")
        authors = authors or bundle.get("publisher")
        for candidate in bundle.get("icon", []) or []:
            path = project_dir / candidate
            if path.is_file():
                icons.append(path)

        package = _read_toml(project_dir / "Cargo.toml").get("package", {})
        cargo_version = package.get("version")
        # `version.workspace = true` is a table, and the number is in a workspace this
        # script does not walk; --pack-version has to say it.
        if isinstance(cargo_version, str):
            version = version or cargo_version
        pack_id = pack_id or package.get("name")
        title = title or package.get("name")
        cargo_authors = package.get("authors")
        if not authors and isinstance(cargo_authors, list) and cargo_authors:
            authors = ", ".join(cargo_authors)

        for candidate in (
            project_dir / "assets" / "icon.icns",
            project_dir / "assets" / "icon.ico",
            project_dir / "assets" / "icon.png",
            project_dir / "icon.png",
        ):
            if candidate.is_file() and candidate not in icons:
                icons.append(candidate)

    if explicit_icon is not None:
        explicit_icon = Path(explicit_icon)
        if not explicit_icon.is_file():
            raise VelopackError(f"--icon {explicit_icon} does not exist")
        icons.insert(0, explicit_icon)

    if not pack_id:
        raise VelopackError(
            "a pack id is required: pass --pack-id or set [bundle] identifier in Dioxus.toml"
        )
    if not _PACK_ID.match(pack_id):
        raise VelopackError(
            f"pack id {pack_id!r} is not one vpk accepts (letters, digits, '.', '-' and '_'); "
            f"it also names the install directory, so it is not rewritten here"
        )
    if not version:
        raise VelopackError(
            "a version is required: pass --pack-version or set [package] version in Cargo.toml"
        )
    if not _SEMVER.match(version):
        raise VelopackError(f"version {version!r} is not SemVer 2, which vpk requires")
    if not executable_name:
        raise VelopackError("the executable's name is unknown; pass --executable or --main-exe")

    return ProjectMetadata(
        pack_id=pack_id,
        version=version,
        title=title or pack_id,
        authors=authors or pack_id,
        executable_name=executable_name,
        icon=icons[0] if icons else None,
        icons=icons,
    )


def default_channel_for_os(target_os: str) -> str:
    """vpk's default channel for the OS (DefaultName.GetDefaultChannel)."""
    return {"macos": "osx", "linux": "linux", "windows": "win"}[target_os]


def release_name(pack_id: str, version: str, channel: str, delta: bool, target_os: str) -> str:
    """The .nupkg name vpk gives a release (DefaultName.GetSuggestedReleaseName)."""
    kind = "delta" if delta else "full"
    if target_os == "windows" and channel == "win":
        return f"{pack_id}-{version}-{kind}.nupkg"
    return f"{pack_id}-{version}-{channel}-{kind}.nupkg"


def portable_name(pack_id: str, channel: str, target_os: str) -> str:
    """The portable package name vpk gives (DefaultName.GetSuggestedPortableName)."""
    if target_os == "linux":
        return f"{pack_id}.AppImage" if channel == "linux" else f"{pack_id}-{channel}.AppImage"
    return f"{pack_id}-{channel}-Portable.zip"


def setup_name(pack_id: str, channel: str, target_os: str) -> str:
    """The installer name vpk gives (DefaultName.GetSuggestedSetupName)."""
    if target_os == "windows":
        return f"{pack_id}-{channel}-Setup.exe"
    if target_os == "macos":
        return f"{pack_id}-{channel}-Setup.pkg"
    raise VelopackError("vpk makes no installer on Linux")


def legacy_releases_name(channel: str) -> str:
    """The legacy RELEASES file vpk writes beside the feed (CoreUtil.GetReleasesFileName)."""
    return "RELEASES" if channel.lower() in ("", "win") else f"RELEASES-{channel.lower()}"


def main_exe_name(executable: Path, target_os: str, explicit: str | None = None) -> str:
    """The file name the package runs. Only Windows names it with .exe."""
    name = explicit or Path(executable).name
    if target_os == "windows":
        if not name.lower().endswith(".exe"):
            name = f"{name}.exe"
    elif name.lower().endswith(".exe"):
        name = name[: -len(".exe")]
    return name


def icon_for_os(meta: ProjectMetadata, target_os: str) -> Path | None:
    """The icon vpk (Windows, Linux) or the .app (macOS) gets.

    vpk takes only .ico on Windows and only .icns for a bundle it makes on macOS; this
    script makes the .app itself, and its packager turns a .png into .icns.
    """
    candidates = meta.icons or ([meta.icon] if meta.icon else [])
    preferred = {
        "windows": (".ico",),
        "macos": (".icns", ".png"),
        "linux": (".png", ".svg"),
    }[target_os]
    for suffix in preferred:
        for path in candidates:
            if path.suffix.lower() == suffix:
                return path
    if target_os == "windows" and candidates:
        print(
            f"warning: vpk takes only an .ico on Windows and the icons given are "
            f"{', '.join(str(p) for p in candidates)}; Setup.exe gets vpk's default icon",
            file=sys.stderr,
        )
    return None


def macos_bundle_version(version: str) -> str:
    """CFBundleVersion and CFBundleShortVersionString want one to three integers."""
    return re.match(r"^\d+(\.\d+){0,2}", version).group(0)


def copy_delta_files(delta_dir: Path, output_dir: Path) -> list[Path]:
    """Put the previous release's full packages where vpk looks for them.

    vpk builds a delta against the newest full package older than the new version that
    is in --outputDir (ReleaseEntryHelper.GetPreviousFullRelease), so the previous full
    .nupkg has to be copied in. Delta packages and the old feed are not needed: vpk
    regenerates the feed from the packages in the directory.
    """
    delta_dir = Path(delta_dir)
    if not delta_dir.is_dir():
        raise VelopackError(f"--delta-dir {delta_dir} is not a directory")
    output_dir.mkdir(parents=True, exist_ok=True)
    if delta_dir.resolve() == output_dir.resolve():
        return []
    copied = []
    for nupkg in sorted(delta_dir.glob("*-full.nupkg")):
        destination = output_dir / nupkg.name
        if not destination.exists():
            shutil.copy2(nupkg, destination)
        copied.append(destination)
    return copied


def nupkg_version(path: Path) -> str | None:
    """The version a .nupkg's .nuspec states, or None if it cannot be read."""
    try:
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if "/" not in name and name.endswith(".nuspec"):
                    text = archive.read(name).decode("utf-8", "replace")
                    match = re.search(r"<version>\s*([^<\s]+)\s*</version>", text)
                    return match.group(1) if match else None
    except (OSError, zipfile.BadZipFile):
        return None
    return None


def _assets(feed: dict) -> list[dict]:
    for key in ("Assets", "assets"):
        if isinstance(feed.get(key), list):
            return feed[key]
    return []


def _field(entry: dict, name: str):
    for key, value in entry.items():
        if key.lower() == name.lower():
            return value
    return None


def _asset_type(entry: dict) -> str:
    value = _field(entry, "Type")
    names = {1: "full", 2: "delta", 3: "portable", 4: "installer"}
    if isinstance(value, int):
        return names.get(value, str(value))
    return str(value or "").lower()


def verify_feed(output_dir: Path, channel: str, version: str, expect_delta: bool = False) -> dict:
    """Check that releases.<channel>.json lists this version and its packages exist."""
    output_dir = Path(output_dir)
    feed_path = output_dir / f"releases.{channel}.json"
    if not feed_path.is_file():
        raise VelopackError(f"vpk wrote no {feed_path.name} in {output_dir}")
    try:
        feed = json.loads(feed_path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as error:
        raise VelopackError(f"{feed_path} is not JSON: {error}") from error

    entries = [e for e in _assets(feed) if str(_field(e, "Version")) == version]
    full = [e for e in entries if _asset_type(e) == "full"]
    delta = [e for e in entries if _asset_type(e) == "delta"]
    if not full:
        raise VelopackError(f"{feed_path.name} lists no full package of {version}")
    if expect_delta and not delta:
        raise VelopackError(
            f"{feed_path.name} lists no delta package of {version}, though a previous "
            f"full package was in place to build one from"
        )
    found = {}
    for kind, chosen in (("full_nupkg", full), ("delta_nupkg", delta)):
        if not chosen:
            continue
        path = output_dir / str(_field(chosen[0], "FileName"))
        if not path.is_file():
            raise VelopackError(f"{feed_path.name} lists {path.name}, which is not in {output_dir}")
        found[kind] = path
    return {"feed": feed_path, **found}


def built_assets(output_dir: Path, channel: str) -> list[Path]:
    """The files this run of vpk made, from the assets.<channel>.json it writes."""
    path = Path(output_dir) / f"assets.{channel}.json"
    if not path.is_file():
        return []
    try:
        entries = json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError:
        return []
    files = []
    for entry in entries if isinstance(entries, list) else []:
        name = _field(entry, "RelativeFileName")
        if name and (Path(output_dir) / name).is_file():
            files.append(Path(output_dir) / name)
    return files


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_vpk(command: list[str], dry_run: bool) -> None:
    """Run vpk with its output on stderr, so a failure shows what vpk said."""
    print("vpk:", " ".join(command), file=sys.stderr)
    if dry_run:
        return
    try:
        # Read and passed on line by line, so it shows as vpk works and also reaches a
        # caller that has replaced sys.stderr.
        with subprocess.Popen(
            command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
        ) as process:
            for line in process.stdout:
                sys.stderr.write(line)
                sys.stderr.flush()
        returncode = process.returncode
    except FileNotFoundError as error:
        raise VelopackError(
            f"{command[0]} was not found ({error}). Install it with "
            f"`dotnet tool install -g vpk --version {VPK_VERSION}` or pass --vpk"
        ) from error
    if returncode != 0:
        raise VelopackError(f"vpk pack failed with status {returncode}; vpk's output is above")


@dataclass
class PackRequest:
    meta: ProjectMetadata
    executable: Path
    output_dir: Path
    channel: str
    target_os: str
    delta_dir: Path | None = None
    vpk: str = "vpk"
    dry_run: bool = False
    release_notes: Path | None = None
    vpk_args: list[str] = field(default_factory=list)
    main_exe: str | None = None
    # macOS
    sign_app_identity: str = AD_HOC
    sign_entitlements: Path | None = None
    notary_profile: str | None = None
    keychain: Path | None = None
    macos_installer: bool = False
    # Windows
    msi: bool = False


def base_command(request: PackRequest, pack_dir: Path, main_exe: str) -> list[str]:
    meta = request.meta
    command = [
        request.vpk,
        "pack",
        "--yes",
        "--packId", meta.pack_id,
        "--packVersion", meta.version,
        "--packDir", str(pack_dir),
        "--mainExe", main_exe,
        "--packTitle", meta.title,
        "--packAuthors", meta.authors,
        "--channel", request.channel,
        "--outputDir", str(request.output_dir),
    ]
    if request.release_notes is not None:
        command += ["--releaseNotes", str(request.release_notes)]
    return command


def _scratch(request: PackRequest):
    """A staging directory beside the output directory, removed afterwards.

    Beside it rather than in the system temporary directory, so a run leaves nothing
    outside the tree it was pointed at. vpk reads *.nupkg only at the top of --outputDir,
    so a sibling never reaches the feed. A dry run makes nothing.
    """
    if request.dry_run:
        return contextlib.nullcontext(str(request.output_dir.parent / f".{request.output_dir.name}-staging"))
    parent = request.output_dir.parent
    parent.mkdir(parents=True, exist_ok=True)
    return tempfile.TemporaryDirectory(prefix=f".{request.output_dir.name}-staging-", dir=parent)


def _staging(scratch_dir: str, request: PackRequest) -> Path:
    staging = Path(scratch_dir) / "app"
    if not request.dry_run:
        staging.mkdir()
    return staging


def package_macos(request: PackRequest) -> dict:
    """A signed .app with UpdateMac, the update packages, and a .dmg of the same .app.

    The order matters. vpk adds UpdateMac and sq.version to the bundle, then signs it
    (`codesign --deep` with --signAppIdentity), then makes the portable .zip and the full
    .nupkg from that signed bundle. Signing is therefore vpk's, with the ad hoc identity
    `-`, so the bundle an update delivers is the signed one. Re-signing a copy afterwards
    would change only that copy, and the .nupkg would still hold the bundle as vpk left it.
    """
    from dxc_packager import bundle, metadata as mac_meta

    meta = request.meta
    main_exe = main_exe_name(request.executable, "macos", request.main_exe)
    with _scratch(request) as scratch_dir:
        scratch = Path(scratch_dir)
        staging = _staging(scratch_dir, request)
        bundle_version = macos_bundle_version(meta.version)
        app_meta = mac_meta.AppMetadata(
            name=meta.title,
            display_name=meta.title,
            identifier=meta.pack_id,
            executable=main_exe,
            version=bundle_version,
            build=bundle_version,
            icon=icon_for_os(meta, "macos"),
        )
        if request.dry_run:
            app = staging / f"{meta.title}.app"
        else:
            app = bundle.assemble(app_meta, request.executable, staging)
            # Code nested below the bundle's standard places (a renderer's
            # Frameworks/lib) is signed innermost first here, because `codesign --deep`
            # does not reach every such file; vpk then signs the whole bundle over it.
            bundle.sign(app)

        command = base_command(request, app, main_exe)
        command += ["--signAppIdentity", request.sign_app_identity]
        if request.sign_entitlements is not None:
            command += ["--signEntitlements", str(request.sign_entitlements)]
        if request.notary_profile:
            command += ["--notaryProfile", request.notary_profile]
        if request.keychain is not None:
            command += ["--keychain", str(request.keychain)]
        if not request.macos_installer:
            # vpk's Setup.pkg installs into /Applications and would need an installer
            # signing identity to open; the .dmg is the direct package on macOS.
            command.append("--noInst")
        command += request.vpk_args
        run_vpk(command, request.dry_run)
        if request.dry_run:
            return {"command": command}

        portable = request.output_dir / portable_name(meta.pack_id, request.channel, "macos")
        if not portable.is_file():
            raise VelopackError(f"vpk pack did not write {portable.name} in {request.output_dir}")
        unpacked = scratch / "portable"
        unpacked.mkdir()
        bundle.run(["ditto", "-x", "-k", str(portable), str(unpacked)])
        apps = sorted(unpacked.glob("*.app"))
        if not apps:
            raise VelopackError(f"{portable.name} holds no .app")
        vpk_app = apps[0]
        if not (vpk_app / "Contents" / "MacOS" / "UpdateMac").is_file():
            raise VelopackError(f"{vpk_app.name} in {portable.name} has no Contents/MacOS/UpdateMac")
        # vpk zipped this bundle and the .nupkg's from the same signed directory, so a
        # signature that holds here holds for the update as well.
        try:
            bundle.run(["codesign", "--verify", "--deep", "--strict", "--verbose=2", str(vpk_app)])
        except mac_meta.PackagingError as error:
            raise VelopackError(f"the bundle vpk signed does not verify: {error}") from error

        dmg = request.output_dir / f"{meta.pack_id}-{request.channel}.dmg"
        bundle.dmg(vpk_app, dmg, volume_name=meta.title)
        return {"dmg": dmg, "portable": portable}


def package_linux(request: PackRequest) -> dict:
    """Velopack's self-updating AppImage and the update packages."""
    meta = request.meta
    main_exe = main_exe_name(request.executable, "linux", request.main_exe)
    with _scratch(request) as scratch_dir:
        staging = _staging(scratch_dir, request)
        if not request.dry_run:
            staged = staging / main_exe
            shutil.copy2(request.executable, staged)
            staged.chmod(0o755)
        command = base_command(request, staging, main_exe)
        icon = icon_for_os(meta, "linux")
        if icon is not None:
            command += ["--icon", str(icon)]
        command += request.vpk_args
        run_vpk(command, request.dry_run)
        if request.dry_run:
            return {"command": command}

    appimage = request.output_dir / portable_name(meta.pack_id, request.channel, "linux")
    if not appimage.is_file():
        raise VelopackError(f"vpk pack did not write {appimage.name} in {request.output_dir}")
    return {"appimage": appimage}


def package_windows(request: PackRequest) -> dict:
    """Setup.exe, the portable .zip, optionally the .msi, and the update packages."""
    meta = request.meta
    main_exe = main_exe_name(request.executable, "windows", request.main_exe)
    with _scratch(request) as scratch_dir:
        staging = _staging(scratch_dir, request)
        if not request.dry_run:
            shutil.copy2(request.executable, staging / main_exe)
        command = base_command(request, staging, main_exe)
        icon = icon_for_os(meta, "windows")
        if icon is not None:
            command += ["--icon", str(icon)]
        if request.msi:
            command.append("--msi")
        command += request.vpk_args
        run_vpk(command, request.dry_run)
        if request.dry_run:
            return {"command": command}

    setup = request.output_dir / setup_name(meta.pack_id, request.channel, "windows")
    if not setup.is_file():
        raise VelopackError(f"vpk pack did not write {setup.name} in {request.output_dir}")
    found = {"setup_exe": setup}
    portable = request.output_dir / portable_name(meta.pack_id, request.channel, "windows")
    if portable.is_file():
        found["portable"] = portable
    if request.msi:
        msi = request.output_dir / f"{meta.pack_id}-{request.channel}.msi"
        if not msi.is_file():
            raise VelopackError(f"vpk pack --msi did not write {msi.name} in {request.output_dir}")
        found["msi"] = msi
    return found


PACKAGERS = {"macos": package_macos, "linux": package_linux, "windows": package_windows}


def package(request: PackRequest) -> dict:
    """Pack one version and check what vpk wrote. Returns the files, by role."""
    request.output_dir = Path(request.output_dir).resolve()
    previous = []
    if not request.dry_run:
        request.output_dir.mkdir(parents=True, exist_ok=True)
        if request.delta_dir is not None:
            previous = copy_delta_files(Path(request.delta_dir), request.output_dir)

    made = PACKAGERS[request.target_os](request)
    if request.dry_run:
        return made

    # A delta is due when an older full package was put in place for vpk to diff against.
    expect_delta = any(nupkg_version(path) not in (None, request.meta.version) for path in previous)
    feed = verify_feed(request.output_dir, request.channel, request.meta.version, expect_delta)
    upload = built_assets(request.output_dir, request.channel)
    for extra in (feed["feed"], request.output_dir / legacy_releases_name(request.channel), made.get("dmg")):
        if extra is not None and extra.is_file() and extra not in upload:
            upload.append(extra)
    for path in made.values():
        if path not in upload:
            upload.append(path)
    return {
        "os": request.target_os,
        "channel": request.channel,
        "pack_id": request.meta.pack_id,
        "version": request.meta.version,
        **feed,
        **made,
        "previous": previous,
        "upload": upload,
        "sha256": {path.name: sha256(path) for path in upload},
    }


def host_os() -> str:
    name = platform.system().lower()
    if name == "darwin":
        return "macos"
    if name in ("linux", "windows"):
        return name
    raise VelopackError(f"unsupported operating system: {name}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="package-velopack",
        description="Pack an application with Velopack and write its update feed.",
    )
    parser.add_argument("--project", "--project-dir", dest="project", type=Path,
                        help="directory with the application's Cargo.toml and Dioxus.toml")
    parser.add_argument("--executable", type=Path, required=True,
                        help="the built application executable")
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="directory the packages and feed go to; one per release")
    parser.add_argument("--delta-dir", type=Path,
                        help="directory holding the previous release's full .nupkg, for the delta")
    parser.add_argument("--pack-id", help="Velopack package id (default: [bundle] identifier)")
    parser.add_argument("--pack-version", help="version to pack (default: Cargo.toml's)")
    parser.add_argument("--pack-title", help="display name (default: [application] name)")
    parser.add_argument("--pack-authors", help="authors (default: [bundle] publisher)")
    parser.add_argument("--main-exe", help="file name the package runs (default: the executable's)")
    parser.add_argument("--icon", type=Path,
                        help=".ico on Windows, .png on Linux, .icns or .png on macOS")
    parser.add_argument("--release-notes", type=Path, help="Markdown release notes for the feed")
    parser.add_argument("--channel", help="Velopack channel (default: osx, linux or win)")
    parser.add_argument("--target-os", choices=["macos", "osx", "linux", "windows", "win"],
                        help="OS to pack for (default: this one; vpk packs for the OS it runs on)")
    parser.add_argument("--vpk", default="vpk", help="vpk command")
    parser.add_argument("--vpk-arg", dest="vpk_args", action="append", default=[],
                        metavar="ARG", help="extra argument for vpk pack, repeatable (--vpk-arg=--delta=None)")
    parser.add_argument("--msi", action="store_true", help="Windows: also make the .msi")
    parser.add_argument("--sign-app-identity", default=AD_HOC,
                        help="macOS: codesign identity (default '-', ad hoc)")
    parser.add_argument("--sign-entitlements", type=Path, help="macOS: .entitlements file")
    parser.add_argument("--notary-profile", help="macOS: notarytool keychain profile")
    parser.add_argument("--keychain", type=Path, help="macOS: keychain for signing")
    parser.add_argument("--macos-installer", action="store_true",
                        help="macOS: keep vpk's Setup.pkg (off by default)")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the vpk command and stop")
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    target_os = {"osx": "macos", "win": "windows"}.get(args.target_os, args.target_os) or host_os()
    channel = args.channel or default_channel_for_os(target_os)
    meta = parse_metadata(
        args.project,
        {
            "pack_id": args.pack_id,
            "pack_version": args.pack_version,
            "pack_title": args.pack_title,
            "pack_authors": args.pack_authors,
            "executable_name": main_exe_name(args.executable, target_os, args.main_exe),
            "icon": args.icon,
        },
    )
    if not args.dry_run and not args.executable.is_file():
        raise VelopackError(f"--executable {args.executable} does not exist")
    request = PackRequest(
        meta=meta,
        executable=args.executable,
        output_dir=args.output_dir,
        channel=channel,
        target_os=target_os,
        delta_dir=args.delta_dir,
        vpk=args.vpk,
        dry_run=args.dry_run,
        release_notes=args.release_notes,
        vpk_args=args.vpk_args,
        main_exe=args.main_exe,
        sign_app_identity=args.sign_app_identity,
        sign_entitlements=args.sign_entitlements,
        notary_profile=args.notary_profile,
        keychain=args.keychain,
        macos_installer=args.macos_installer,
        msi=args.msi,
    )
    result = package(request)

    def plain(value):
        if isinstance(value, Path):
            return str(value)
        if isinstance(value, list):
            return [plain(v) for v in value]
        if isinstance(value, dict):
            return {k: plain(v) for k, v in value.items()}
        return value

    print(json.dumps(plain(result), indent=2))
    return 0


if __name__ == "__main__":
    from dxc_packager.metadata import PackagingError

    try:
        sys.exit(main())
    except (VelopackError, PackagingError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
