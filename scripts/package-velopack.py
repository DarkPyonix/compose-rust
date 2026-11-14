#!/usr/bin/env python3
"""package-velopack: Package applications using Velopack (vpk) with delta updates and feeds.

Produces direct-distribution packages and update feeds across platforms:
- macOS: ad-hoc signed .app (including Velopack's UpdateMac), portable .zip,
  .dmg containing Gatekeeper instructions, full/delta .nupkg, and releases.{channel}.json
- Linux: self-updating .AppImage, full/delta .nupkg, and releases.{channel}.json
- Windows: Setup.exe, full/delta .nupkg, and releases.{channel}.json

Can be run for compose-rust sample projects or external consumers (such as darkpyonix-ember):
    package-velopack.py --project samples/calculator --executable target/release/sample-calculator --output-dir dist/releases
    package-velopack.py --pack-id com.darkpyonix.ember --pack-version 1.0.0 --executable target/release/ember --output-dir dist/releases
"""

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

if sys.version_info < (3, 11):
    raise SystemExit("package-velopack requires Python 3.11 or newer for tomllib")

import tomllib

SCRIPTS_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPTS_DIR.parent
PACKAGER_MACOS_DIR = SCRIPTS_DIR / "packager-macos"
if str(PACKAGER_MACOS_DIR) not in sys.path:
    sys.path.insert(0, str(PACKAGER_MACOS_DIR))


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


def parse_metadata(project_dir: Path | None, overrides: dict) -> ProjectMetadata:
    """Extract metadata from Dioxus.toml and Cargo.toml with CLI overrides."""
    pack_id = overrides.get("pack_id")
    version = overrides.get("pack_version")
    title = overrides.get("pack_title")
    authors = overrides.get("pack_authors")
    executable_name = overrides.get("executable_name")
    icon_path = overrides.get("icon")

    if project_dir is not None:
        project_dir = Path(project_dir)
        cargo_path = project_dir / "Cargo.toml"
        dioxus_path = project_dir / "Dioxus.toml"

        if dioxus_path.is_file():
            dx_data = tomllib.loads(dioxus_path.read_text(encoding="utf-8"))
            app_sec = dx_data.get("application", {})
            bundle_sec = dx_data.get("bundle", {})
            if not title and "name" in app_sec:
                title = app_sec["name"]
            if not pack_id and "identifier" in bundle_sec:
                pack_id = bundle_sec["identifier"]
            if not authors and "publisher" in bundle_sec:
                authors = bundle_sec["publisher"]
            if not icon_path and "icon" in bundle_sec:
                for icon_candidate in bundle_sec["icon"]:
                    p = project_dir / icon_candidate
                    if p.is_file():
                        icon_path = p
                        break

        if cargo_path.is_file():
            cargo_data = tomllib.loads(cargo_path.read_text(encoding="utf-8"))
            pkg = cargo_data.get("package", {})
            if not pack_id and "name" in pkg:
                pack_id = pkg["name"]
            if not version and "version" in pkg:
                version = str(pkg["version"])
            if not title and "name" in pkg:
                title = pkg["name"]
            if not authors and "authors" in pkg:
                authors_list = pkg["authors"]
                if isinstance(authors_list, list) and authors_list:
                    authors = ", ".join(authors_list)
            if not executable_name and "name" in pkg:
                executable_name = pkg["name"]

        # Fallback for icon if not in Dioxus.toml
        if not icon_path:
            for candidate in [
                project_dir / "assets" / "icon.icns",
                project_dir / "assets" / "icon.png",
                project_dir / "assets" / "icon.ico",
                project_dir / "icon.png",
            ]:
                if candidate.is_file():
                    icon_path = candidate
                    break

    # Required defaults if still unset
    if not pack_id:
        raise VelopackError("Pack ID is required (pass --pack-id or set in Dioxus.toml/Cargo.toml)")
    if not version:
        raise VelopackError("Version is required (pass --pack-version or set in Cargo.toml)")
    if not title:
        title = pack_id
    if not authors:
        authors = "DarkPyonix"
    if not executable_name:
        executable_name = pack_id

    # Sanitize pack_id for Velopack (alphanumeric and dot/hyphen/underscore)
    clean_id = re.sub(r"[^A-Za-z0-9_.-]", "_", pack_id)

    return ProjectMetadata(
        pack_id=clean_id,
        version=version,
        title=title,
        authors=authors,
        executable_name=executable_name,
        icon=Path(icon_path) if icon_path else None,
    )


def default_channel_for_os(target_os: str) -> str:
    """Default Velopack channel name for target OS."""
    if target_os == "macos":
        return "osx"
    if target_os == "linux":
        return "linux"
    if target_os == "windows":
        return "win"
    return "stable"


def copy_delta_files(delta_dir: Path, output_dir: Path, channel: str) -> None:
    """Copy previous release nupkg and releases.{channel}.json to enable delta packaging."""
    if not delta_dir.is_dir():
        return
    if delta_dir.resolve() == output_dir.resolve():
        return

    output_dir.mkdir(parents=True, exist_ok=True)
    feed_name = f"releases.{channel}.json"
    source_feed = delta_dir / feed_name
    if source_feed.is_file():
        shutil.copy2(source_feed, output_dir / feed_name)

    # Also check generic releases.json if channel-specific one not found
    if not (output_dir / feed_name).is_file():
        generic_feed = delta_dir / "releases.json"
        if generic_feed.is_file():
            shutil.copy2(generic_feed, output_dir / feed_name)

    # Copy all nupkg files
    for nupkg in delta_dir.glob("*.nupkg"):
        dest = output_dir / nupkg.name
        if not dest.exists():
            shutil.copy2(nupkg, dest)


def verify_feed(output_dir: Path, channel: str, pack_id: str, version: str, expect_delta: bool = False) -> dict:
    """Verify releases.{channel}.json and that full/delta nupkg files exist."""
    feed_path = output_dir / f"releases.{channel}.json"
    if not feed_path.is_file():
        # Fall back to releases.json if channel-specific file doesn't exist
        alt_feed = output_dir / "releases.json"
        if alt_feed.is_file():
            feed_path = alt_feed
        else:
            raise VelopackError(f"Feed file releases.{channel}.json not found in {output_dir}")

    try:
        data = json.loads(feed_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise VelopackError(f"Failed to parse {feed_path}: {exc}") from exc

    releases = data.get("releases", [])
    if not isinstance(releases, list) or not releases:
        raise VelopackError(f"No releases found in {feed_path}")

    # Find the release matching the target version
    matched_release = None
    for rel in releases:
        rel_ver = str(rel.get("version", ""))
        if rel_ver == version:
            matched_release = rel
            break

    if not matched_release:
        raise VelopackError(f"Version {version} not found in {feed_path}")

    # Verify packages on disk
    full_packages = list(output_dir.glob(f"*{version}*full*.nupkg"))
    if not full_packages:
        raise VelopackError(f"No full package found for version {version} in {output_dir}")

    delta_packages = list(output_dir.glob(f"*{version}*delta*.nupkg"))
    if expect_delta and not delta_packages:
        raise VelopackError(f"Expected delta package for version {version} in {output_dir}, but none was found")

    return {
        "feed": feed_path,
        "release": matched_release,
        "full_nupkg": full_packages[0],
        "delta_nupkg": delta_packages[0] if delta_packages else None,
    }


def package_macos(
    meta: ProjectMetadata,
    executable: Path,
    output_dir: Path,
    channel: str,
    delta_dir: Path | None = None,
    vpk_cmd: str = "vpk",
    dry_run: bool = False,
    extra_args: list[str] | None = None,
) -> dict:
    """Package macOS .app with vpk, re-sign ad-hoc, and create .dmg with install notes."""
    from dxc_packager import bundle, metadata as mac_meta

    output_dir = Path(output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    if delta_dir:
        copy_delta_files(Path(delta_dir), output_dir, channel)

    with tempfile.TemporaryDirectory() as scratch_dir:
        scratch = Path(scratch_dir)
        app_staging = scratch / "app_staging"
        app_staging.mkdir()

        # Step 1: Assemble and sign initial .app bundle
        loaded_meta = mac_meta.AppMetadata(
            name=meta.title,
            display_name=meta.title,
            identifier=meta.pack_id,
            executable=meta.executable_name,
            version=meta.version,
            build=meta.version,
            icon=meta.icon,
        )

        if not dry_run:
            app_bundle = bundle.assemble(loaded_meta, executable, app_staging)
            bundle.sign(app_bundle)
        else:
            app_bundle = app_staging / f"{meta.title}.app"
            app_bundle.mkdir()

        # Step 2: Invoke vpk pack
        cmd = [
            vpk_cmd,
            "pack",
            "--packId", meta.pack_id,
            "--packVersion", meta.version,
            "--packDir", str(app_bundle),
            "--mainExe", meta.executable_name,
            "--packTitle", meta.title,
            "--packAuthors", meta.authors,
            "--channel", channel,
            "--outputDir", str(output_dir),
            "--skipVeloAppCheck",
        ]
        if meta.icon and meta.icon.is_file():
            cmd.extend(["--icon", str(meta.icon)])
        if extra_args:
            cmd.extend(extra_args)

        if dry_run:
            print("dry-run:", " ".join(cmd))
            return {"command": cmd, "output_dir": output_dir}

        try:
            subprocess.run(cmd, check=True, text=True, capture_output=True)
        except (subprocess.CalledProcessError, FileNotFoundError) as err:
            err_msg = getattr(err, "stderr", str(err))
            raise VelopackError(f"vpk pack failed: {err_msg}") from err

        # Step 3: Find the portable zip, unpack .app, re-sign ad-hoc with UpdateMac
        portable_zips = list(output_dir.glob(f"*{meta.version}*Portable.zip")) or \
                        list(output_dir.glob(f"*{meta.version}*portable.zip")) or \
                        list(output_dir.glob(f"*{meta.version}*osx*.zip"))

        if not portable_zips:
            raise VelopackError(f"vpk pack did not generate a portable zip in {output_dir}")

        portable_zip = portable_zips[0]
        unpack_dir = scratch / "unpacked"
        unpack_dir.mkdir()

        # Unpack .app
        subprocess.run(["ditto", "-x", "-k", str(portable_zip), str(unpack_dir)], check=True)
        unpacked_apps = list(unpack_dir.glob("*.app"))
        if not unpacked_apps:
            raise VelopackError(f"No .app found inside {portable_zip}")
        vpk_app = unpacked_apps[0]

        # Re-sign all code ad-hoc (UpdateMac + app)
        bundle.sign(vpk_app)

        # Repack the re-signed .app into the portable zip so updates deliver signed binaries
        portable_zip.unlink()
        subprocess.run(
            ["ditto", "-c", "-k", "--keepParent", str(vpk_app), str(portable_zip)],
            check=True,
        )

        # Step 4: Create the final .dmg containing Gatekeeper instructions
        dmg_path = output_dir / f"{meta.title}-{meta.version}.dmg"
        bundle.dmg(vpk_app, dmg_path, volume_name=meta.title)

        feed_info = verify_feed(output_dir, channel, meta.pack_id, meta.version, expect_delta=(delta_dir is not None))
        feed_info.update({
            "dmg": dmg_path,
            "portable_zip": portable_zip,
        })
        return feed_info


def package_linux(
    meta: ProjectMetadata,
    executable: Path,
    output_dir: Path,
    channel: str,
    delta_dir: Path | None = None,
    vpk_cmd: str = "vpk",
    dry_run: bool = False,
    extra_args: list[str] | None = None,
) -> dict:
    """Package Linux self-updating AppImage with vpk."""
    output_dir = Path(output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    if delta_dir:
        copy_delta_files(Path(delta_dir), output_dir, channel)

    with tempfile.TemporaryDirectory() as scratch_dir:
        scratch = Path(scratch_dir)
        staging = scratch / "staging"
        staging.mkdir()

        staged_exe = staging / meta.executable_name
        if not dry_run:
            shutil.copy2(executable, staged_exe)
            staged_exe.chmod(0o755)

        cmd = [
            vpk_cmd,
            "pack",
            "--packId", meta.pack_id,
            "--packVersion", meta.version,
            "--packDir", str(staging),
            "--mainExe", meta.executable_name,
            "--packTitle", meta.title,
            "--packAuthors", meta.authors,
            "--channel", channel,
            "--outputDir", str(output_dir),
            "--skipVeloAppCheck",
        ]
        if meta.icon and meta.icon.is_file():
            cmd.extend(["--icon", str(meta.icon)])
        if extra_args:
            cmd.extend(extra_args)

        if dry_run:
            print("dry-run:", " ".join(cmd))
            return {"command": cmd, "output_dir": output_dir}

        try:
            subprocess.run(cmd, check=True, text=True, capture_output=True)
        except (subprocess.CalledProcessError, FileNotFoundError) as err:
            err_msg = getattr(err, "stderr", str(err))
            raise VelopackError(f"vpk pack failed: {err_msg}") from err

        appimages = list(output_dir.glob(f"*{meta.version}*.AppImage")) or \
                    list(output_dir.glob(f"*{meta.pack_id}*.AppImage"))
        if not appimages:
            raise VelopackError(f"vpk pack did not generate an AppImage in {output_dir}")

        feed_info = verify_feed(output_dir, channel, meta.pack_id, meta.version, expect_delta=(delta_dir is not None))
        feed_info.update({
            "appimage": appimages[0],
        })
        return feed_info


def package_windows(
    meta: ProjectMetadata,
    executable: Path,
    output_dir: Path,
    channel: str,
    delta_dir: Path | None = None,
    vpk_cmd: str = "vpk",
    dry_run: bool = False,
    extra_args: list[str] | None = None,
) -> dict:
    """Package Windows Setup.exe with vpk."""
    output_dir = Path(output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    if delta_dir:
        copy_delta_files(Path(delta_dir), output_dir, channel)

    with tempfile.TemporaryDirectory() as scratch_dir:
        scratch = Path(scratch_dir)
        staging = scratch / "staging"
        staging.mkdir()

        exe_name = meta.executable_name
        if not exe_name.lower().endswith(".exe"):
            exe_name = f"{exe_name}.exe"

        staged_exe = staging / exe_name
        if not dry_run:
            shutil.copy2(executable, staged_exe)

        cmd = [
            vpk_cmd,
            "pack",
            "--packId", meta.pack_id,
            "--packVersion", meta.version,
            "--packDir", str(staging),
            "--mainExe", exe_name,
            "--packTitle", meta.title,
            "--packAuthors", meta.authors,
            "--channel", channel,
            "--outputDir", str(output_dir),
            "--skipVeloAppCheck",
        ]
        if meta.icon and meta.icon.is_file():
            cmd.extend(["--icon", str(meta.icon)])
        if extra_args:
            cmd.extend(extra_args)

        if dry_run:
            print("dry-run:", " ".join(cmd))
            return {"command": cmd, "output_dir": output_dir}

        try:
            subprocess.run(cmd, check=True, text=True, capture_output=True)
        except (subprocess.CalledProcessError, FileNotFoundError) as err:
            err_msg = getattr(err, "stderr", str(err))
            raise VelopackError(f"vpk pack failed: {err_msg}") from err

        setup_exes = list(output_dir.glob(f"*{meta.pack_id}*Setup.exe")) or \
                     list(output_dir.glob("*Setup.exe"))
        if not setup_exes:
            raise VelopackError(f"vpk pack did not generate Setup.exe in {output_dir}")

        feed_info = verify_feed(output_dir, channel, meta.pack_id, meta.version, expect_delta=(delta_dir is not None))
        feed_info.update({
            "setup_exe": setup_exes[0],
        })
        return feed_info


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="package-velopack",
        description="Package application with Velopack and generate delta feeds",
    )
    parser.add_argument("--project", type=Path, help="Directory containing Cargo.toml and/or Dioxus.toml")
    parser.add_argument("--executable", type=Path, required=True, help="Path to compiled application binary")
    parser.add_argument("--output-dir", type=Path, required=True, help="Directory to write releases and feed")
    parser.add_argument("--delta-dir", type=Path, help="Directory holding previous release for delta generation")
    parser.add_argument("--pack-id", help="Velopack package ID")
    parser.add_argument("--pack-version", help="Velopack package version")
    parser.add_argument("--pack-title", help="Application display title")
    parser.add_argument("--pack-authors", help="Application authors")
    parser.add_argument("--icon", type=Path, help="Icon path")
    parser.add_argument("--channel", help="Velopack channel (default based on OS)")
    parser.add_argument("--target-os", choices=["macos", "linux", "windows"], help="Target OS override")
    parser.add_argument("--vpk", default="vpk", help="Path to vpk CLI binary")
    parser.add_argument("--dry-run", action="store_true", help="Print commands without executing")

    args = parser.parse_args(argv)

    target_os = args.target_os
    if not target_os:
        sys_name = platform.system().lower()
        if sys_name == "darwin":
            target_os = "macos"
        elif sys_name == "linux":
            target_os = "linux"
        elif sys_name == "windows":
            target_os = "windows"
        else:
            raise VelopackError(f"Unsupported operating system: {sys_name}")

    channel = args.channel or default_channel_for_os(target_os)

    overrides = {
        "pack_id": args.pack_id,
        "pack_version": args.pack_version,
        "pack_title": args.pack_title,
        "pack_authors": args.pack_authors,
        "executable_name": args.executable.stem if args.executable else None,
        "icon": args.icon,
    }

    meta = parse_metadata(args.project, overrides)

    if target_os == "macos":
        result = package_macos(
            meta=meta,
            executable=args.executable,
            output_dir=args.output_dir,
            channel=channel,
            delta_dir=args.delta_dir,
            vpk_cmd=args.vpk,
            dry_run=args.dry_run,
        )
    elif target_os == "linux":
        result = package_linux(
            meta=meta,
            executable=args.executable,
            output_dir=args.output_dir,
            channel=channel,
            delta_dir=args.delta_dir,
            vpk_cmd=args.vpk,
            dry_run=args.dry_run,
        )
    elif target_os == "windows":
        result = package_windows(
            meta=meta,
            executable=args.executable,
            output_dir=args.output_dir,
            channel=channel,
            delta_dir=args.delta_dir,
            vpk_cmd=args.vpk,
            dry_run=args.dry_run,
        )
    else:
        raise VelopackError(f"Unsupported target OS: {target_os}")

    print(json.dumps({k: str(v) for k, v in result.items() if v is not None}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except VelopackError as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
