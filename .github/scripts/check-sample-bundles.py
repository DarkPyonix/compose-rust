"""Checks what the samples workflow packaged for one desktop target, before it is uploaded.

    python3 -I .github/scripts/check-sample-bundles.py \
        --target macos-aarch64 --artifacts artifacts --samples samples \
        --built built.txt --version 0.0.7

For every sample in `--built` (one name per line, the ones that compiled), the formats
its platform ships are looked for in `--artifacts`, named `<sample>-<target>.<ext>`:

    macos-aarch64              .tar.gz, .app.zip, .dmg
    linux-x64, linux-arm64     .tar.gz, .AppImage
    windows-x64                .tar.gz, .zip, .msix

Each one that is there is opened and has to hold the sample's one executable, its icon
and the metadata the platform reads, and nothing else: the renderer is linked into the
executable, so a library or a data file beside it is a packaging mistake. The executable
may load nothing but the system's own libraries, and nothing by an absolute path from the
build machine. The name, identifier, description and version the bundle declares have to
be the ones in the sample's `Dioxus.toml` and the version given. The MSIX has to be
unsigned, as the release notes say. `SHA256SUMS-<target>` has to list every file with
its right digest.

A format that is missing is reported as a warning and written to
`skipped-<target>.txt`, because the packaging steps let one sample lose one format rather
than hold the release back; the release notes list it. Anything wrong inside a file that
is there fails the check: that is the packager being wrong for every sample.

The disk image is mounted with hdiutil, so it is only opened on macOS; the AppImage is
unpacked by running it with --appimage-extract, so only on Linux. Elsewhere those two are
checked for presence and digest alone, and the check says so.
"""

import argparse
import hashlib
import os
import plistlib
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

if sys.version_info < (3, 11):
    raise SystemExit("this check reads TOML with tomllib, which needs Python 3.11 or newer")

import tomllib

FORMATS = {
    "macos-aarch64": ["tar.gz", "app.zip", "dmg"],
    "linux-x64": ["tar.gz", "AppImage"],
    "linux-arm64": ["tar.gz", "AppImage"],
    "windows-x64": ["tar.gz", "zip", "msix"],
}

# What an executable may load. A library outside these is one that would have to travel
# with it, and nothing travels with it.
MACHO_SYSTEM = ("/usr/lib/", "/System/")
# Named for what used to sit beside an application: the renderer, Skia, a Java runtime.
RENDERER_PIECES = re.compile(r"compose_rust|dioxus|skiko|skia|jvm|jawt|awt|java", re.I)


class Sample:
    """What a sample says about itself in Dioxus.toml."""

    def __init__(self, directory):
        self.directory = Path(directory)
        self.slug = self.directory.name
        path = self.directory / "Dioxus.toml"
        try:
            with open(path, "rb") as handle:
                config = tomllib.load(handle)
        except FileNotFoundError:
            raise SystemExit(f"{path} does not exist, so there is nothing to check the bundle against")
        application = config.get("application", {})
        bundle = config.get("bundle", {})
        macos = {**config.get("macos", {}), **bundle.get("macos", {})}
        self.name = application.get("name") or self.slug
        self.macos_name = macos.get("bundle_name") or self.name
        self.identifier = bundle.get("identifier")
        self.description = bundle.get("short_description")
        missing = [key for key, value in (("[bundle] identifier", self.identifier),
                                          ("[bundle] short_description", self.description))
                   if not value]
        if missing:
            raise SystemExit(f"{path} has no {' and no '.join(missing)}; set it")
        self.executable = f"sample-{self.slug}"


# --- what an executable loads -------------------------------------------------------------


def elf_references(data):
    """(needed, search paths) from an ELF file's dynamic section."""
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    wide = data[4] == 2
    end = "<" if data[5] == 1 else ">"
    if wide:
        phoff, = struct.unpack_from(end + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(end + "HH", data, 0x36)
    else:
        phoff, = struct.unpack_from(end + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(end + "HH", data, 0x2A)
    loads, dynamic = [], None
    for index in range(phnum):
        at = phoff + index * phentsize
        if wide:
            kind, _flags, offset, vaddr, _paddr, filesz = struct.unpack_from(end + "IIQQQQ", data, at)
        else:
            kind, offset, vaddr, _paddr, filesz = struct.unpack_from(end + "IIIII", data, at)
        if kind == 1:
            loads.append((vaddr, offset, filesz))
        elif kind == 2:
            dynamic = (offset, filesz)
    if dynamic is None:
        return [], []  # statically linked: it loads nothing at all
    entry = struct.Struct(end + ("qQ" if wide else "iI"))
    tags = []
    for at in range(dynamic[0], dynamic[0] + dynamic[1], entry.size):
        tag, value = entry.unpack_from(data, at)
        if tag == 0:
            break
        tags.append((tag, value))
    strtab = next((value for tag, value in tags if tag == 5), None)
    if strtab is None:
        raise ValueError("the dynamic section has no string table")
    base = next((offset + strtab - vaddr for vaddr, offset, size in loads
                 if vaddr <= strtab < vaddr + size), None)
    if base is None:
        raise ValueError("the string table is in no loaded segment")

    def string(offset):
        start = base + offset
        return data[start:data.index(b"\0", start)].decode("utf-8", "replace")

    needed = [string(value) for tag, value in tags if tag == 1]
    paths = [string(value) for tag, value in tags if tag in (15, 29)]  # RPATH, RUNPATH
    return needed, paths


MACHO_LOADS = {0xC, 0x80000018, 0x8000001F, 0x20, 0x80000023}
MACHO_RPATH = 0x8000001C


def macho_references(data):
    """(libraries, rpaths) from a Mach-O file, every architecture of a universal one."""
    magic = data[:4]
    if magic in (b"\xca\xfe\xba\xbe",):
        count, = struct.unpack_from(">I", data, 4)
        libraries, rpaths = [], []
        for index in range(count):
            _cpu, _sub, offset, size, _align = struct.unpack_from(">iiIII", data, 8 + index * 20)
            more = macho_references(data[offset:offset + size])
            libraries += more[0]
            rpaths += more[1]
        return libraries, rpaths
    if magic not in (b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe"):
        raise ValueError("not a Mach-O file")
    ncmds, _size = struct.unpack_from("<II", data, 16)
    at = 32 if magic == b"\xcf\xfa\xed\xfe" else 28
    libraries, rpaths = [], []
    for _ in range(ncmds):
        cmd, cmdsize = struct.unpack_from("<II", data, at)
        if cmd in MACHO_LOADS or cmd == MACHO_RPATH:
            name_offset, = struct.unpack_from("<I", data, at + 8)
            raw = data[at + name_offset:at + cmdsize]
            name = raw.split(b"\0", 1)[0].decode("utf-8", "replace")
            (rpaths if cmd == MACHO_RPATH else libraries).append(name)
        at += cmdsize
    return libraries, rpaths


def pe_imports(data):
    """The DLL names a PE file imports."""
    if data[:2] != b"MZ":
        raise ValueError("not a PE file")
    pe, = struct.unpack_from("<I", data, 0x3C)
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("no PE signature")
    sections, optional_size = struct.unpack_from("<H12xH", data, pe + 6)
    optional = pe + 24
    magic, = struct.unpack_from("<H", data, optional)
    directories = optional + (112 if magic == 0x20B else 96)
    import_rva, _ = struct.unpack_from("<II", data, directories + 8)
    table = []
    for index in range(sections):
        at = optional + optional_size + index * 40
        vsize, vaddr, rawsize, rawptr = struct.unpack_from("<IIII", data, at + 8)
        table.append((vaddr, max(vsize, rawsize), rawptr))

    def offset(rva):
        for vaddr, size, rawptr in table:
            if vaddr <= rva < vaddr + size:
                return rawptr + rva - vaddr
        raise ValueError(f"RVA {rva:#x} is in no section")

    if import_rva == 0:
        return []
    names = []
    at = offset(import_rva)
    while True:
        descriptor = struct.unpack_from("<IIIII", data, at)
        if not any(descriptor):
            break
        start = offset(descriptor[3])
        names.append(data[start:data.index(b"\0", start)].decode("ascii", "replace"))
        at += 20
    return names


def reference_problems(label, data, platform):
    """What is wrong with what an executable loads, as sentences naming it."""
    problems = []
    try:
        if platform == "macos":
            libraries, rpaths = macho_references(data)
            for library in libraries:
                if not library.startswith(MACHO_SYSTEM):
                    problems.append(f"{label} loads {library}, which is not part of macOS")
            for path in rpaths:
                problems.append(f"{label} carries the search path {path}, so it expects something beside it")
        elif platform == "linux":
            needed, paths = elf_references(data)
            for library in needed:
                if "/" in library:
                    problems.append(f"{label} names {library} by its path on the build machine")
                elif RENDERER_PIECES.search(library):
                    problems.append(f"{label} loads {library}, a piece of the renderer that is to be linked in")
            for path in paths:
                problems.append(f"{label} carries the search path {path}, so it expects something beside it")
        else:
            for dll in pe_imports(data):
                if RENDERER_PIECES.search(dll) or dll.lower().startswith(("vcruntime", "msvcp", "libgcc", "libstdc++", "libwinpthread")):
                    problems.append(f"{label} loads {dll}, which is not part of Windows")
    except (ValueError, struct.error, IndexError) as error:
        problems.append(f"{label} is not an executable this platform runs: {error}")
    return problems


# --- the formats -------------------------------------------------------------------------


def unexpected(label, files, allowed):
    """Files a bundle holds beyond the executable, icon and metadata."""
    return [f"{label} holds {name}, and a bundle holds only the executable, its icon and its metadata"
            for name in sorted(files) if not any(re.fullmatch(pattern, name) for pattern in allowed)]


def check_tarball(path, sample, platform):
    executable = sample.executable + (".exe" if platform == "windows" else "")
    with tarfile.open(path) as archive:
        members = {member.name.rstrip("/"): member for member in archive.getmembers()}
        problems = unexpected(path.name, [name for name, member in members.items() if not member.isdir()],
                              [re.escape(f"{sample.slug}/{executable}")])
        member = members.get(f"{sample.slug}/{executable}")
        if member is None:
            return problems + [f"{path.name} has no {sample.slug}/{executable}"]
        problems += reference_problems(f"{path.name}:{member.name}", archive.extractfile(member).read(), platform)
    return problems


def check_windows_zip(path, sample):
    executable = sample.executable + ".exe"
    with zipfile.ZipFile(path) as archive:
        files = [name for name in archive.namelist() if not name.endswith("/")]
        problems = unexpected(path.name, files, [re.escape(executable)])
        if executable not in files:
            return problems + [f"{path.name} has no {executable}"]
        problems += reference_problems(f"{path.name}:{executable}", archive.read(executable), "windows")
    return problems


def app_problems(label, files, read, sample, version):
    """An .app, given as its file list (relative to the bundle's parent) and a reader."""
    app = f"{sample.macos_name}.app"
    contents = f"{re.escape(app)}/Contents"
    problems = unexpected(label, files, [
        f"{contents}/Info\\.plist",
        f"{contents}/PkgInfo",
        f"{contents}/MacOS/{re.escape(sample.executable)}",
        f"{contents}/Resources/AppIcon\\.icns",
        f"{contents}/_CodeSignature/CodeResources",
    ])
    for required in ("Info.plist", f"MacOS/{sample.executable}", "Resources/AppIcon.icns",
                     "_CodeSignature/CodeResources"):
        if f"{app}/Contents/{required}" not in files:
            what = {"Resources/AppIcon.icns": "an icon", "_CodeSignature/CodeResources": "a signature"}.get(required, required)
            problems.append(f"{label} has no {app}/Contents/{required} ({what})")
    if f"{app}/Contents/Info.plist" in files:
        info = plistlib.loads(read(f"{app}/Contents/Info.plist"))
        for key, want in (("CFBundleIdentifier", sample.identifier),
                          ("CFBundleName", sample.macos_name),
                          ("CFBundleExecutable", sample.executable),
                          ("CFBundleShortVersionString", version),
                          ("CFBundleIconFile", "AppIcon"),
                          ("NSHighResolutionCapable", True)):
            if info.get(key) != want:
                problems.append(f"{label}: Info.plist {key} is {info.get(key)!r}, and the sample says {want!r}")
    if f"{app}/Contents/MacOS/{sample.executable}" in files:
        problems += reference_problems(f"{label}:{sample.executable}",
                                       read(f"{app}/Contents/MacOS/{sample.executable}"), "macos")
    return problems


def check_app_zip(path, sample, version):
    with zipfile.ZipFile(path) as archive:
        files = [name for name in archive.namelist() if not name.endswith("/")]
        return app_problems(path.name, files, archive.read, sample, version)


def files_under(root):
    """Every file and symbolic link under `root`, relative and with / separators."""
    found = []
    for directory, subdirectories, names in os.walk(root):
        for name in names + [d for d in subdirectories if os.path.islink(os.path.join(directory, d))]:
            found.append(os.path.relpath(os.path.join(directory, name), root).replace(os.sep, "/"))
    return found


def check_dmg(path, sample, version, scratch):
    mount = scratch / "dmg"
    mount.mkdir()
    attached = subprocess.run(["hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", str(mount), str(path)],
                              capture_output=True, text=True)
    if attached.returncode != 0:
        return [f"{path.name} does not mount: {attached.stderr.strip()}"]
    try:
        files = files_under(mount)
        app = f"{sample.macos_name}.app"
        outside = [name for name in files if not name.startswith(app + "/")]
        problems = unexpected(path.name, outside, [r"Applications", r"If macOS will not open the app\.txt",
                                                   r"\.fseventsd/.*", r"\.Trashes.*", r"\.DS_Store"])
        if "Applications" not in files or os.readlink(mount / "Applications") != "/Applications":
            problems.append(f"{path.name} has no link to /Applications to drag the app onto")
        if "If macOS will not open the app.txt" not in files:
            problems.append(f"{path.name} does not carry the notes on opening an app signed ad hoc")
        inside = [name for name in files if name.startswith(app + "/")]
        problems += app_problems(path.name, inside, lambda name: (mount / name).read_bytes(), sample, version)
        return problems
    finally:
        subprocess.run(["hdiutil", "detach", str(mount)], capture_output=True)


def check_msix(path, sample, version):
    executable = sample.executable + ".exe"
    with zipfile.ZipFile(path) as archive:
        files = [name for name in archive.namelist() if not name.endswith("/")]
        problems = unexpected(path.name, files, [
            re.escape(executable), r"AppxManifest\.xml", r"AppxBlockMap\.xml",
            r"\[Content_Types\]\.xml", r"Assets/[A-Za-z0-9_.-]+\.png", r"AppxSignature\.p7x",
        ])
        if "AppxSignature.p7x" in files:
            problems.append(f"{path.name} is signed, and the release says it is not: drop the signature or the note")
        if not any(name.startswith("Assets/") for name in files):
            problems.append(f"{path.name} has no icons under Assets/")
        if executable not in files:
            problems.append(f"{path.name} has no {executable}")
        else:
            problems += reference_problems(f"{path.name}:{executable}", archive.read(executable), "windows")
        if "AppxManifest.xml" not in files:
            return problems + [f"{path.name} has no AppxManifest.xml"]
        manifest = ET.fromstring(archive.read("AppxManifest.xml"))
    ns = {"m": "http://schemas.microsoft.com/appx/manifest/foundation/windows10",
          "uap": "http://schemas.microsoft.com/appx/manifest/uap/windows10"}
    identity = manifest.find("m:Identity", ns)
    display = manifest.find("m:Properties/m:DisplayName", ns)
    application = manifest.find("m:Applications/m:Application", ns)
    visual = manifest.find("m:Applications/m:Application/uap:VisualElements", ns)
    found = {
        "Identity Name": identity.get("Name") if identity is not None else None,
        "Identity Version": identity.get("Version") if identity is not None else None,
        "DisplayName": display.text if display is not None else None,
        "Application Executable": application.get("Executable") if application is not None else None,
        "VisualElements DisplayName": visual.get("DisplayName") if visual is not None else None,
        "VisualElements Description": visual.get("Description") if visual is not None else None,
    }
    wanted = {
        "Identity Name": sample.identifier,
        "Identity Version": f"{version}.0",
        "DisplayName": sample.name,
        "Application Executable": executable,
        "VisualElements DisplayName": sample.name,
        "VisualElements Description": sample.description,
    }
    for key, want in wanted.items():
        if key == "Identity Version":
            # The fourth part is the sideload build number, so only the first three are the release's.
            have = found[key] or ""
            if have.rsplit(".", 1)[0] != version:
                problems.append(f"{path.name}: AppxManifest {key} is {have!r}, and the release is {version}")
        elif found[key] != want:
            problems.append(f"{path.name}: AppxManifest {key} is {found[key]!r}, and the sample says {want!r}")
    return problems


def desktop_entry(text):
    entry, group = {}, None
    for line in text.splitlines():
        if line.startswith("["):
            group = line.strip()
        elif group == "[Desktop Entry]" and "=" in line:
            key, value = line.split("=", 1)
            entry[key] = value
    return entry


def check_appimage(path, sample, scratch):
    workdir = scratch / "appimage"
    workdir.mkdir()
    copy = workdir / path.name
    shutil.copyfile(path, copy)
    copy.chmod(0o755)
    extracted = subprocess.run([str(copy), "--appimage-extract"], cwd=workdir, capture_output=True, text=True)
    root = workdir / "squashfs-root"
    if extracted.returncode != 0 or not root.is_dir():
        return [f"{path.name} does not unpack: {extracted.stderr.strip() or extracted.stdout.strip()}"]
    files = files_under(root)
    ident = re.escape(sample.identifier)
    payload = f"usr/lib/{sample.executable}/{sample.executable}"
    problems = unexpected(path.name, files, [
        r"AppRun", r"\.DirIcon", f"{ident}\\.desktop", f"{ident}\\.(png|svg)",
        f"usr/share/applications/{ident}\\.desktop",
        f"usr/share/metainfo/{ident}\\.(appdata|metainfo)\\.xml",
        f"usr/share/icons/hicolor/[0-9a-z]+(x[0-9]+)?/apps/{ident}\\.(png|svg)",
        re.escape(payload),
    ])
    if payload not in files:
        problems.append(f"{path.name} has no {payload}")
    else:
        problems += reference_problems(f"{path.name}:{payload}", (root / payload).read_bytes(), "linux")
    if not any(re.fullmatch(f"usr/share/icons/hicolor/.*/apps/{ident}\\.(png|svg)", name) for name in files):
        problems.append(f"{path.name} has no icon for {sample.identifier}")
    desktop = root / f"{sample.identifier}.desktop"
    if not desktop.is_file():
        problems.append(f"{path.name} has no {sample.identifier}.desktop, so the sample's identifier is not its name")
    else:
        entry = desktop_entry(desktop.read_text())
        for key, want in (("Name", sample.name), ("Comment", sample.description), ("Icon", sample.identifier)):
            have = entry.get(key)
            # AppStream and desktop entries read a summary as a fragment, without the full stop.
            if (have or "").rstrip(".") != want.rstrip("."):
                problems.append(f"{path.name}: the desktop entry's {key} is {have!r}, and the sample says {want!r}")
    return problems


# --- checksums ---------------------------------------------------------------------------


def checksum_problems(artifacts, target, names):
    sums = artifacts / f"SHA256SUMS-{target}"
    if not sums.is_file():
        return [f"there is no SHA256SUMS-{target}"]
    listed = {}
    for line in sums.read_text().splitlines():
        if line.strip():
            digest, name = line.split(None, 1)
            listed[name.lstrip("*").strip()] = digest.lower()
    problems = []
    for name in names:
        if name not in listed:
            problems.append(f"SHA256SUMS-{target} does not list {name}")
            continue
        digest = hashlib.sha256((artifacts / name).read_bytes()).hexdigest()
        if digest != listed[name]:
            problems.append(f"SHA256SUMS-{target} records {listed[name]} for {name}, and the file is {digest}")
    return problems


# --- the run -----------------------------------------------------------------------------


def check(target, artifacts, samples, built, version):
    """(problems, missing), each a list of sentences."""
    if target not in FORMATS:
        raise SystemExit(f"{target} is not a desktop release target ({', '.join(FORMATS)})")
    platform = target.split("-")[0]
    problems, missing, present = [], [], []
    for slug in built:
        sample = Sample(samples / slug)
        if not any((sample.directory / "assets" / name).is_file() for name in ("icon.png", "icon.svg", "icon.icns")):
            problems.append(f"{slug} has no assets/icon.png for the bundles to wear")
        for extension in FORMATS[target]:
            path = artifacts / f"{slug}-{target}.{extension}"
            if not path.is_file():
                missing.append(f"{slug} {target} {extension}")
                continue
            present.append(path.name)
            with tempfile.TemporaryDirectory(dir=artifacts.parent) as scratch:
                scratch = Path(scratch)
                if extension == "tar.gz":
                    found = check_tarball(path, sample, platform)
                elif extension == "zip":
                    found = check_windows_zip(path, sample)
                elif extension == "app.zip":
                    found = check_app_zip(path, sample, version)
                elif extension == "msix":
                    found = check_msix(path, sample, version)
                elif extension == "dmg":
                    if sys.platform != "darwin":
                        print(f"note  {path.name}: only macOS mounts a disk image, so its digest is all that is checked")
                        found = []
                    else:
                        found = check_dmg(path, sample, version, scratch)
                else:
                    if not sys.platform.startswith("linux"):
                        print(f"note  {path.name}: only Linux unpacks an AppImage, so its digest is all that is checked")
                        found = []
                    else:
                        found = check_appimage(path, sample, scratch)
            problems += found
            if not found:
                print(f"ok    {path.name}")
    problems += checksum_problems(artifacts, target, present)
    return problems, missing


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--target", required=True)
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--samples", required=True, type=Path)
    parser.add_argument("--built", required=True, type=Path)
    parser.add_argument("--version", required=True)
    args = parser.parse_args(argv)
    built = [line.strip() for line in args.built.read_text().splitlines() if line.strip()]
    problems, missing = check(args.target, args.artifacts.resolve(), args.samples, built, args.version)
    if missing:
        with open(args.artifacts / f"skipped-{args.target}.txt", "a") as handle:
            for line in missing:
                handle.write(line + "\n")
        for line in missing:
            slug, target, extension = line.split(" ")
            print(f"::warning title=Format missing::sample-{slug} has no .{extension} for {target}")
    for problem in problems:
        print(f"::error title=Bundle is wrong::{problem}")
    if problems:
        print(f"{len(problems)} problem(s) in what was packaged for {args.target}", file=sys.stderr)
        return 1
    print(f"ok    every bundle for {args.target} holds one executable, its icon and the sample's metadata")
    return 0


if __name__ == "__main__":
    sys.exit(main())
