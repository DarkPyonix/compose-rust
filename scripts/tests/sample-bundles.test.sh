#!/usr/bin/env bash
# .github/scripts/check-sample-bundles.py: what the samples workflow packaged is checked
# before it is uploaded, and the check notices each way a bundle can be wrong.
#
# A desktop bundle holds the sample's one executable, its icon and its metadata, nothing
# else; the executable loads only the system's libraries and nothing by a build machine
# path; the name, identifier, description and version are the sample's; the MSIX is
# unsigned; every file is in the checksum list with its right digest. A format that is
# missing fails the check by default; passing --allow-missing downgrades it to a warning
# and records it in skipped-<target>.txt.
#
# The direct-distribution packages are Velopack's: the macOS .app in the .dmg
# and .app.zip carries UpdateMac, the Linux AppImage carries UpdateNix, Windows has
# Setup.exe and an .msi, and every target has its update feed releases.<sample>-<target>.json
# with the full .nupkg it names. The store .pkg carries no updater.
#
# The executables are a few hundred bytes made here (ELF, Mach-O and PE headers with the
# load commands that matter), so this needs no compiler and runs the same on every system.
# The disk image and the AppImage are opened only on the systems that can; those halves
# are covered by the samples workflow, which runs this check on each desktop row.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/.github/scripts/check-sample-bundles.py"
scratch="$repo_root/.scratch/sample-bundles-test"
rm -rf "$scratch"
mkdir -p "$scratch"
trap 'rm -rf "$scratch"' EXIT

command -v python3 >/dev/null || { echo "FAIL - python3 is needed to run the check"; exit 1; }

failures=0
pass() { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; failures=$((failures + 1)); }

# make <case directory> <target> [fault]: a sample and its artifacts for one target, made
# right except for the one fault named.
make_case() {
    python3 -I - "$@" <<'PY'
import base64, hashlib, io, os, plistlib, shutil, struct, subprocess, sys, tarfile, tempfile, zipfile
from pathlib import Path

case, target = Path(sys.argv[1]), sys.argv[2]
fault = sys.argv[3] if len(sys.argv) > 3 else ""
samples, artifacts = case / "samples", case / "artifacts"
(samples / "demo" / "assets").mkdir(parents=True)
artifacts.mkdir(parents=True)
(samples / "demo" / "assets" / "icon.png").write_bytes(b"\x89PNG\r\n\x1a\n")
(samples / "demo" / "Dioxus.toml").write_text(
    '[application]\nname = "Demo"\n\n'
    '[bundle]\nidentifier = "dev.example.samples.demo"\npublisher = "Example"\n'
    'short_description = "A demonstration."\n\n[macos]\nbundle_name = "Demo"\n'
)
(case / "built.txt").write_text("demo\n")
VERSION = "1.2.3"


def elf(needed, runpath=None):
    strings = b"\0"
    offsets = []
    for name in needed + ([runpath] if runpath else []):
        offsets.append(len(strings))
        strings += name.encode() + b"\0"
    dyn = b""
    for offset in offsets[:len(needed)]:
        dyn += struct.pack("<qQ", 1, offset)
    if runpath:
        dyn += struct.pack("<qQ", 29, offsets[-1])
    header, phdrs = 64, 2 * 56
    dyn_at = header + phdrs
    str_at = dyn_at + len(dyn) + 32
    dyn += struct.pack("<qQ", 5, str_at) + struct.pack("<qQ", 0, 0)
    size = str_at + len(strings)
    out = b"\x7fELF" + bytes([2, 1, 1]) + bytes(9)
    out += struct.pack("<HHIQQQIHHHHHH", 2, 0x3E, 1, 0, 64, 0, 0, 64, 56, 2, 64, 0, 0)
    out += struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, size, size, 0x1000)
    out += struct.pack("<IIQQQQQQ", 2, 6, dyn_at, dyn_at, dyn_at, len(dyn), len(dyn), 8)
    out += dyn
    return out + strings


def macho(libraries, rpaths=()):
    commands = b""
    for kind, name in [(0xC, n) for n in libraries] + [(0x8000001C, n) for n in rpaths]:
        body = name.encode() + b"\0"
        fixed = 24 if kind == 0xC else 12
        size = (fixed + len(body) + 7) // 8 * 8
        if kind == 0xC:
            command = struct.pack("<IIIIII", kind, size, 24, 2, 0x10000, 0x10000)
        else:
            command = struct.pack("<III", kind, size, 12)
        commands += (command + body).ljust(size, b"\0")
    count = len(libraries) + len(rpaths)
    return struct.pack("<IiiIIIII", 0xFEEDFACF, 0x0100000C, 0, 2, count, len(commands), 0, 0) + commands


def pe(dlls):
    # One section at RVA 0x1000, file offset 0x200, holding the import descriptors and names.
    names = b""
    name_rvas = []
    table_size = 20 * (len(dlls) + 1)
    for dll in dlls:
        name_rvas.append(0x1000 + table_size + len(names))
        names += dll.encode() + b"\0"
    section = b"".join(struct.pack("<IIIII", 0, 0, 0, rva, 0) for rva in name_rvas) + bytes(20) + names
    optional = bytearray(240)
    struct.pack_into("<H", optional, 0, 0x20B)
    struct.pack_into("<II", optional, 112 + 8, 0x1000, table_size)
    coff = struct.pack("<HHIIIHH", 0x8664, 1, 0, 0, 0, 240, 0x22)
    header = bytearray(0x40)
    header[0:2] = b"MZ"
    struct.pack_into("<I", header, 0x3C, 0x40)
    sect = struct.pack("<8sIIIIIIHHI", b".idata", len(section), 0x1000, len(section), 0x200, 0, 0, 0, 0, 0x40000040)
    image = bytes(header) + b"PE\0\0" + coff + bytes(optional) + sect
    return image.ljust(0x200, b"\0") + section


def tarball(path, member, data, extra=None):
    with tarfile.open(path, "w:gz") as archive:
        for name, payload in [(member, data)] + ([extra] if extra else []):
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            info.mode = 0o755
            archive.addfile(info, io.BytesIO(payload))


def zipped(path, entries):
    with zipfile.ZipFile(path, "w") as archive:
        for name, payload in entries:
            archive.writestr(name, payload)


def targz(members):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        for name, payload in members:
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            info.mode = 0o755
            archive.addfile(info, io.BytesIO(payload))
    return buffer.getvalue()


def deb(path, control, files):
    """A .deb as dpkg-deb --build writes it: an ar archive of debian-binary,
    control.tar.gz and data.tar.gz."""
    members = [
        ("debian-binary", b"2.0\n"),
        ("control.tar.gz", targz([("./control", control.encode())])),
        ("data.tar.gz", targz(files)),
    ]
    out = b"!<arch>\n"
    for name, data in members:
        out += f"{name:<16}{0:<12}{0:<6}{0:<6}{'100644':<8}{len(data):<10}`\n".encode() + data
        if len(data) % 2:
            out += b"\n"
    path.write_bytes(out)


def rpm_header(entries):
    """An RPM header structure: magic, index entries, then the data store."""
    index, store = b"", b""
    for tag, kind, value in entries:
        if kind == 4:
            store += bytes(-len(store) % 4)
            offset, count = len(store), len(value)
            store += b"".join(struct.pack(">i", v) for v in value)
        elif kind in (8, 9):
            offset, count = len(store), len(value)
            store += b"".join(v.encode() + b"\0" for v in value)
        else:
            offset, count = len(store), 1
            store += value.encode() + b"\0"
        index += struct.pack(">IIiI", tag, kind, offset, count)
    return b"\x8e\xad\xe8\x01" + bytes(4) + struct.pack(">II", len(entries), len(store)) + index + store


def rpm(path, name, version, summary, files):
    """An .rpm as rpmbuild writes it, as far as `rpm -qp --info` and `-qpl` read it."""
    lead = b"\xed\xab\xee\xdb" + bytes([3, 0]) + struct.pack(">hh", 0, 1) + name.encode().ljust(66, b"\0")
    lead += struct.pack(">hh", 1, 5) + bytes(16)
    signature = rpm_header([(1000, 4, [0])])
    signature += bytes(-len(signature) % 8)
    dirs = sorted({f.rsplit("/", 1)[0] + "/" for f in files})
    header = rpm_header([
        (1000, 6, name), (1001, 6, version), (1004, 9, [summary]),
        (1116, 4, [dirs.index(f.rsplit("/", 1)[0] + "/") for f in files]),
        (1117, 8, [f.rsplit("/", 1)[1] for f in files]),
        (1118, 8, dirs),
    ])
    path.write_bytes(lead + signature + header)


platform = target.split("-")[0]
if platform == "windows":
    imports = ["KERNEL32.dll", "USER32.dll"] + (["compose_rust_renderer.dll"] if fault == "renderer-dll" else [])
    exe = pe(imports)
    tarball(artifacts / f"demo-{target}.tar.gz", "demo/sample-demo.exe", exe)
    entries = [("sample-demo.exe", exe)] + ([("skiko-windows-x64.dll", b"MZ")] if fault == "extra-file" else [])
    zipped(artifacts / f"demo-{target}.zip", entries)
    display = "Not the sample" if fault == "wrong-name" else "Demo"
    manifest = f"""<?xml version="1.0" encoding="utf-8"?>
<Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10">
  <Identity Name="dev.example.samples.demo" Publisher="CN=Example" Version="{VERSION}.0" ProcessorArchitecture="x64" />
  <Properties><DisplayName>{display}</DisplayName></Properties>
  <Applications><Application Id="Demo" Executable="sample-demo.exe" EntryPoint="Windows.FullTrustApplication">
    <uap:VisualElements DisplayName="{display}" Description="A demonstration." />
  </Application></Applications>
</Package>"""
    msix = [("sample-demo.exe", exe), ("AppxManifest.xml", manifest), ("AppxBlockMap.xml", "<x/>"),
            ("[Content_Types].xml", "<x/>"), ("Assets/StoreLogo.png", b"\x89PNG")]
    if fault == "signed":
        msix.append(("AppxSignature.p7x", b"PKCX"))
    if fault != "missing-format":
        zipped(artifacts / f"demo-{target}.msix", msix)
    # Velopack's Setup.exe and .msi. Their insides are Velopack's own; the check reads
    # what kind of file each is.
    if fault != "missing-setup":
        (artifacts / f"demo-{target}.setup.exe").write_bytes(pe(["KERNEL32.dll"]))
    msi_magic = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"
    (artifacts / f"demo-{target}.msi").write_bytes(b"PK\x03\x04" if fault == "msi-not-msi" else msi_magic + bytes(504))
    nupkg_app = [("lib/app/sample-demo.exe", exe)]
elif platform == "linux":
    needed = ["libc.so.6", "libX11.so.6"]
    if fault == "absolute-renderer":
        needed.append("/home/runner/work/renderer/lib/libcompose_rust_renderer.so")
    exe = elf(needed, "/home/runner/work/renderer/lib" if fault == "runpath" else None)
    tarball(artifacts / f"demo-{target}.tar.gz", "demo/sample-demo", exe,
            ("demo/lib/libskiko.so", b"\x7fELF") if fault == "extra-file" else None)
    if fault != "missing-format":
        encoded_exe = base64.b64encode(exe).decode("ascii")
        # Velopack's AppImage: AppRun, the desktop entry and icon at the top, and the
        # package (the executable, UpdateNix and sq.version) in usr/bin.
        updater = "" if fault == "appimage-no-updater" else "  printf '\\177ELF' > squashfs-root/usr/bin/UpdateNix\n"
        appimage = (
            "#!/bin/sh\n"
            "set -eu\n"
            'if [ "${1:-}" = "--appimage-extract" ]; then\n'
            "  mkdir -p squashfs-root/usr/bin\n"
            "  : > squashfs-root/AppRun\n"
            "  : > squashfs-root/.DirIcon\n"
            "  cat > squashfs-root/dev.example.samples.demo.desktop <<'DESKTOP'\n"
            "[Desktop Entry]\n"
            "Type=Application\n"
            "Name=Demo\n"
            "Comment=Demo 1.2.3\n"
            "Icon=dev.example.samples.demo\n"
            "Exec=sample-demo\n"
            "DESKTOP\n"
            "  printf '\\211PNG\\r\\n\\032\\n' > squashfs-root/dev.example.samples.demo.png\n"
            "  mkdir -p squashfs-root/usr/share/icons/hicolor/scalable/apps\n"
            "  cp squashfs-root/dev.example.samples.demo.png squashfs-root/usr/share/icons/hicolor/scalable/apps/\n"
            f"  printf '%s' '{encoded_exe}' | base64 -d > squashfs-root/usr/bin/sample-demo\n"
            "  chmod 755 squashfs-root/usr/bin/sample-demo\n"
            + updater +
            "  printf '1.2.3' > squashfs-root/usr/bin/sq.version\n"
            "fi\n"
        )
        appimage_path = artifacts / f"demo-{target}.AppImage"
        appimage_path.write_text(appimage)
        appimage_path.chmod(0o755)
    ident = "dev.example.samples.demo"
    payload = [
        ("./usr/bin/sample-demo", exe),
        (f"./usr/share/applications/{ident}.desktop", b"[Desktop Entry]\nName=Demo\n"),
        (f"./usr/share/metainfo/{ident}.metainfo.xml", b"<component/>"),
        (f"./usr/share/icons/hicolor/64x64/apps/{ident}.png", b"\x89PNG\r\n\x1a\n"),
    ]
    if fault == "extra-file":
        payload.append(("./usr/lib/sample-demo/libskiko.so", b"\x7fELF"))
    deb_version = "9.9.9" if fault == "wrong-package-version" else VERSION
    if fault != "missing-deb":
        deb(artifacts / f"demo-{target}.deb",
            f"Package: sample-demo\nVersion: {deb_version}\nArchitecture: amd64\n"
            "Description: A demonstration.\n A demonstration.\n", payload)
    rpm_name = "not-the-sample" if fault == "wrong-package-name" else "sample-demo"
    rpm(artifacts / f"demo-{target}.rpm", rpm_name, VERSION, "A demonstration.",
        [path[1:] for path, _ in payload])
    if target == "linux-x64" and fault != "missing-flatpak":
        (artifacts / f"demo-{target}.flatpak").write_bytes(b"flatpak-bundle-fixture")
    nupkg_app = [("lib/app/sample-demo", exe)]
    if fault == "nupkg-appimage":
        # vpk can carry the AppImage whole on Linux, the executable inside it.
        nupkg_app = [("lib/app/dev.example.samples.demo.AppImage", b"\x7fELF-appimage")]
    elif fault == "nupkg-empty":
        nupkg_app = [("lib/app/README", b"nothing")]
else:
    libraries = ["/usr/lib/libSystem.B.dylib", "/System/Library/Frameworks/AppKit.framework/Versions/C/AppKit"]
    if fault == "absolute-renderer":
        libraries.append("/Users/runner/work/renderer/lib/libcompose_rust_renderer.dylib")
    exe = macho(libraries)
    tarball(artifacts / f"demo-{target}.tar.gz", "demo/sample-demo", exe)
    info = {
        "CFBundleIdentifier": "dev.example.wrong" if fault == "wrong-identifier" else "dev.example.samples.demo",
        "CFBundleName": "Demo", "CFBundleExecutable": "sample-demo",
        "CFBundleShortVersionString": VERSION, "CFBundleIconFile": "AppIcon",
        "NSHighResolutionCapable": True,
    }
    entries = [
        ("Demo.app/Contents/Info.plist", plistlib.dumps(info)),
        ("Demo.app/Contents/PkgInfo", b"APPL????"),
        ("Demo.app/Contents/MacOS/sample-demo", exe),
        ("Demo.app/Contents/_CodeSignature/CodeResources", b"<plist/>"),
    ]
    if fault != "no-icon":
        entries.append(("Demo.app/Contents/Resources/AppIcon.icns", b"icns"))
    if fault == "extra-file":
        entries.append(("Demo.app/Contents/Frameworks/lib/libskiko.dylib", b"\xcf\xfa\xed\xfe"))
    # The store .pkg installs the app as it is; the direct packages carry Velopack's
    # updater, which vpk puts in the bundle before it signs it.
    store_entries = list(entries) + ([("Demo.app/Contents/MacOS/UpdateMac", macho(["/usr/lib/libSystem.B.dylib"]))]
                                     if fault == "updater-in-pkg" else [])
    if fault != "no-updater":
        entries.append(("Demo.app/Contents/MacOS/UpdateMac", macho(["/usr/lib/libSystem.B.dylib"])))
    entries.append(("Demo.app/Contents/Resources/sq.version", b"1.2.3"))
    zipped(artifacts / f"demo-{target}.app.zip", entries)
    nupkg_app = [("lib/app/Demo.app/Contents/MacOS/sample-demo", exe)]
    if fault != "missing-format":
        dmg_path = artifacts / f"demo-{target}.dmg"
        if sys.platform != "darwin":
            dmg_path.write_bytes(b"dmg-fixture")
        elif os.environ.get("SAMPLE_BUNDLES_CAN_MOUNT_DMG") == "1":
            with tempfile.TemporaryDirectory(dir=case) as stage_dir:
                stage = Path(stage_dir)
                for rel, payload in entries:
                    dest = stage / rel
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(payload)
                os.symlink("/Applications", stage / "Applications")
                (stage / "If macOS will not open the app.txt").write_text("Right-click and choose Open.\n")
                subprocess.run(
                    ["hdiutil", "create", "-quiet", "-volname", "Demo",
                     "-srcfolder", str(stage), "-ov", "-format", "UDZO", str(dmg_path)],
                    check=True,
                )

if platform == "macos" and fault != "missing-pkg":
    pkg_path = artifacts / f"demo-{target}.pkg"
    if sys.platform != "darwin":
        pkg_path.write_bytes(b"xar!pkg-fixture")
    else:
        with tempfile.TemporaryDirectory(dir=case) as root_dir:
            root = Path(root_dir)
            for rel, payload in store_entries:
                dest = root / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(payload)
            if fault == "pkg-extra-file":
                (root / "Demo.app/Contents/Frameworks").mkdir(parents=True)
                (root / "Demo.app/Contents/Frameworks/libskiko.dylib").write_bytes(b"\xcf\xfa\xed\xfe")
            subprocess.run(
                ["pkgbuild", "--quiet", "--root", str(root), "--identifier", "dev.example.samples.demo",
                 "--version", VERSION, "--install-location", "/Applications", str(pkg_path)],
                check=True,
            )

# The update feed Velopack's SDK reads, and the full package it names.
channel = f"demo-{target}"
nupkg = f"dev.example.samples.demo-{VERSION}-{channel}-full.nupkg"
main_exe = {"windows": "sample-demo.exe", "macos": "Contents/MacOS/sample-demo"}.get(platform, "sample-demo")
pack_id = "dev.example.wrong" if fault == "nupkg-wrong-id" else "dev.example.samples.demo"
nuspec = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://schemas.microsoft.com/packages/2010/07/nuspec.xsd">
  <metadata>
    <id>{pack_id}</id><title>Demo</title><version>{VERSION}</version><authors>Example</authors>
    <description>Demo</description><channel>{channel}</channel><mainExe>{main_exe}</mainExe>
  </metadata>
</package>"""
if fault == "nupkg-renderer":
    nupkg_app = nupkg_app + [("lib/app/libskiko.so", b"\x7fELF")]
zipped(artifacts / nupkg, [("dev.example.samples.demo.nuspec", nuspec)] + nupkg_app)
digest = hashlib.sha256((artifacts / nupkg).read_bytes()).hexdigest().upper()
feed = {"Assets": [{
    "PackageId": "dev.example.samples.demo",
    "Version": "9.9.9" if fault == "feed-wrong-version" else VERSION,
    "Type": "Full", "FileName": nupkg,
    "SHA256": "0" * 64 if fault == "feed-bad-sha" else digest,
    "Size": (artifacts / nupkg).stat().st_size,
}]}
if fault != "missing-feed":
    import json
    (artifacts / f"releases.{channel}.json").write_text(json.dumps(feed))

sums = []
for path in sorted(artifacts.iterdir()):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if fault == "bad-digest" and path.name.endswith(".tar.gz"):
        digest = "0" * 64
    if fault == "unlisted" and path.name.endswith(".tar.gz"):
        continue
    sums.append(f"{digest}  {path.name}")
(artifacts / f"SHA256SUMS-{target}").write_text("\n".join(sums) + "\n")
PY
}

# run_case <name> <target> <fault> <expect: ok|fail> [text the output must hold] [extra check flags...]
run_case() {
    local name="$1" target="$2" fault="$3" expect="$4" text="${5:-}"
    shift 5 2>/dev/null || shift $#
    local case="$scratch/$name" out status=0
    make_case "$case" "$target" "$fault" || { fail "$name: could not make the fixture"; return; }
    out="$(cd "$case" && python3 -I "$script" --target "$target" --artifacts artifacts \
        --samples samples --built built.txt --version 1.2.3 "$@" 2>&1)" || status=$?
    if [[ "$expect" == ok && "$status" -ne 0 ]]; then
        fail "$name: refused a right bundle"; echo "$out" | sed 's/^/       /'; return
    fi
    if [[ "$expect" == fail && "$status" -eq 0 ]]; then
        fail "$name: passed a wrong bundle"; echo "$out" | sed 's/^/       /'; return
    fi
    if [[ -n "$text" && "$out" != *"$text"* ]]; then
        fail "$name: did not say \"$text\""; echo "$out" | sed 's/^/       /'; return
    fi
    pass "$name"
}

# Probe once whether this macOS environment permits mounting disk images with hdiutil.
# Sandboxed environments deny hdiutil attach; in that case we skip creating a .dmg for the
# happy-path macOS fixture while still testing that a missing .dmg fails by default.
SAMPLE_BUNDLES_CAN_MOUNT_DMG=0
if [[ "$(uname -s)" == "Darwin" ]]; then
    probe_dir="$scratch/dmg-probe"
    mkdir -p "$probe_dir/src" "$probe_dir/mnt"
    : > "$probe_dir/src/probe"
    if hdiutil create -quiet -volname Probe -srcfolder "$probe_dir/src" -ov -format UDZO "$probe_dir/probe.dmg" >/dev/null 2>&1 \
        && hdiutil attach -nobrowse -readonly -mountpoint "$probe_dir/mnt" "$probe_dir/probe.dmg" >/dev/null 2>&1; then
        hdiutil detach "$probe_dir/mnt" >/dev/null 2>&1 || true
        SAMPLE_BUNDLES_CAN_MOUNT_DMG=1
    fi
    rm -rf "$probe_dir"
fi
export SAMPLE_BUNDLES_CAN_MOUNT_DMG

run_case fr22_6_1_windows_bundles_that_are_right_pass windows-x64 "" ok "ok    demo-windows-x64.msix"
if [[ "$(uname -s)" == "Darwin" && "$SAMPLE_BUNDLES_CAN_MOUNT_DMG" -eq 0 ]]; then
    run_case fr22_6_1_macos_bundles_that_are_right_pass macos-aarch64 "" ok "ok    demo-macos-aarch64.app.zip" --allow-missing
else
    run_case fr22_6_1_macos_bundles_that_are_right_pass macos-aarch64 "" ok "ok    demo-macos-aarch64.app.zip"
fi
run_case fr22_6_1_linux_bundles_that_are_right_pass linux-x64 "" ok "ok    demo-linux-x64.tar.gz"
run_case fr22_6_5_a_missing_format_fails_by_default linux-x64 missing-format fail \
    "::error title=Format missing::sample-demo has no .AppImage for linux-x64"
if [[ ! -e "$scratch/fr22_6_5_a_missing_format_fails_by_default/artifacts/skipped-linux-x64.txt" ]]; then
    pass "fr22_6_5_a_missing_format_does_not_write_skipped_by_default"
else
    fail "fr22_6_5_a_missing_format_does_not_write_skipped_by_default: skipped-linux-x64.txt was created without --allow-missing"
fi
run_case fr22_6_5_a_missing_macos_format_fails_by_default macos-aarch64 missing-format fail \
    "::error title=Format missing::sample-demo has no .dmg for macos-aarch64"
run_case fr22_6_5_a_missing_windows_format_fails_by_default windows-x64 missing-format fail \
    "::error title=Format missing::sample-demo has no .msix for windows-x64"
run_case fr22_6_5_allow_missing_opt_in_warns_and_passes linux-x64 missing-format ok \
    "::warning title=Format missing::sample-demo has no .AppImage for linux-x64" --allow-missing
if grep -qx "demo linux-x64 AppImage" "$scratch/fr22_6_5_allow_missing_opt_in_warns_and_passes/artifacts/skipped-linux-x64.txt" 2>/dev/null; then
    pass "fr22_6_5_allow_missing_opt_in_records_skipped_format"
else
    fail "fr22_6_5_allow_missing_opt_in_records_skipped_format: skipped-linux-x64.txt does not name it"
fi
run_case fr22_6_5_linux_arm64_bundles_that_are_right_pass linux-arm64 "" ok "ok    demo-linux-arm64.rpm"
run_case fr22_6_5_a_missing_deb_fails linux-x64 missing-deb fail \
    "::error title=Format missing::sample-demo has no .deb for linux-x64"
run_case fr22_6_5_a_missing_flatpak_fails linux-x64 missing-flatpak fail \
    "::error title=Format missing::sample-demo has no .flatpak for linux-x64"
run_case fr22_6_5_a_missing_pkg_fails macos-aarch64 missing-pkg fail \
    "::error title=Format missing::sample-demo has no .pkg for macos-aarch64"
run_case fr22_6_5_a_renderer_library_in_the_deb_fails linux-x64 extra-file fail "holds usr/lib/sample-demo/libskiko.so"
run_case fr22_6_6_a_deb_version_other_than_the_release_fails linux-x64 wrong-package-version fail \
    "Version is '9.9.9'"
run_case fr22_6_6_an_rpm_name_other_than_the_samples_fails linux-x64 wrong-package-name fail \
    "Name is 'not-the-sample'"
if [[ "$(uname -s)" == "Darwin" ]]; then
    run_case fr22_6_8_a_renderer_library_in_the_pkg_fails macos-aarch64 pkg-extra-file fail \
        "Frameworks/libskiko.dylib" $([[ "$SAMPLE_BUNDLES_CAN_MOUNT_DMG" -eq 0 ]] && echo --allow-missing)
fi
run_case fr22_6_1_a_wrong_digest_fails windows-x64 bad-digest fail "records 0000"
run_case fr22_6_1_a_file_missing_from_the_checksums_fails macos-aarch64 unlisted fail "does not list demo-macos-aarch64.tar.gz"
run_case fr22_6_2_a_renderer_library_beside_the_executable_fails_on_windows windows-x64 extra-file fail \
    "holds skiko-windows-x64.dll"
run_case fr22_6_2_a_renderer_library_in_the_app_fails macos-aarch64 extra-file fail "Frameworks/lib/libskiko.dylib"
run_case fr22_6_2_a_renderer_library_in_the_archive_fails_on_linux linux-x64 extra-file fail "demo/lib/libskiko.so"
run_case fr22_6_2_a_renderer_loaded_by_its_build_path_fails_on_macos macos-aarch64 absolute-renderer fail \
    "/Users/runner/work/renderer/lib/libcompose_rust_renderer.dylib, which is not part of macOS"
run_case fr22_6_2_a_renderer_loaded_by_its_build_path_fails_on_linux linux-x64 absolute-renderer fail \
    "by its path on the build machine"
run_case fr22_6_2_a_search_path_into_the_build_fails_on_linux linux-x64 runpath fail \
    "carries the search path /home/runner/work/renderer/lib"
run_case fr22_6_2_a_renderer_dll_fails_on_windows windows-x64 renderer-dll fail \
    "loads compose_rust_renderer.dll"
run_case fr22_6_3_an_identifier_other_than_the_samples_fails macos-aarch64 wrong-identifier fail \
    "CFBundleIdentifier is 'dev.example.wrong'"
run_case fr22_6_3_a_name_other_than_the_samples_fails windows-x64 wrong-name fail \
    "DisplayName is 'Not the sample'"
run_case fr22_6_3_an_app_without_its_icon_fails macos-aarch64 no-icon fail "(an icon)"
run_case fr22_6_4_a_signed_msix_fails windows-x64 signed fail "is signed"

# Velopack's direct-distribution packages and the update feed.
run_case fr22_6_5_a_missing_setup_exe_fails windows-x64 missing-setup fail \
    "::error title=Format missing::sample-demo has no .setup.exe for windows-x64"
run_case fr22_6_10_an_msi_that_is_not_an_installer_database_fails windows-x64 msi-not-msi fail \
    "is not a Windows Installer database"
run_case fr22_6_7_a_missing_update_feed_fails linux-arm64 missing-feed fail \
    "::error title=Format missing::sample-demo has no update feed releases.demo-linux-arm64.json"
run_case fr22_6_7_a_feed_without_this_version_fails macos-aarch64 feed-wrong-version fail \
    "lists no full package of 1.2.3" --allow-missing
run_case fr22_6_7_a_feed_digest_other_than_the_packages_fails windows-x64 feed-bad-sha fail \
    "records SHA256 0000"
run_case fr22_6_7_a_package_id_other_than_the_samples_fails linux-x64 nupkg-wrong-id fail \
    "the .nuspec id is 'dev.example.wrong'"
run_case fr22_6_7_a_renderer_library_in_the_update_package_fails linux-x64 nupkg-renderer fail \
    "holds lib/app/libskiko.so"
run_case fr22_6_7_a_linux_update_package_carrying_the_appimage_passes linux-x64 nupkg-appimage ok \
    "ok    demo-linux-x64.AppImage"
run_case fr22_6_7_a_linux_update_package_without_the_app_says_what_it_holds linux-x64 nupkg-empty fail \
    "(it holds lib/app/README)"
run_case fr22_6_7_the_direct_app_without_velopacks_updater_fails macos-aarch64 no-updater fail \
    "has no Demo.app/Contents/MacOS/UpdateMac" --allow-missing
if [[ "$(uname -s)" == "Linux" ]]; then
    run_case fr22_6_7_the_appimage_without_velopacks_updater_fails linux-x64 appimage-no-updater fail \
        "has no usr/bin/UpdateNix"
fi
if [[ "$(uname -s)" == "Darwin" ]]; then
    run_case fr22_6_8_the_store_pkg_carries_no_updater macos-aarch64 updater-in-pkg fail \
        "Contents/MacOS/UpdateMac" $([[ "$SAMPLE_BUNDLES_CAN_MOUNT_DMG" -eq 0 ]] && echo --allow-missing)
fi

# The samples workflow runs the check on every desktop row, after the checksums.
workflow="$repo_root/.github/workflows/test-samples.yml"
if grep -q 'check-sample-bundles.py' "$workflow"; then
    pass "fr22_6_1_the_samples_workflow_runs_the_check"
else
    fail "fr22_6_1_the_samples_workflow_runs_the_check: test-samples.yml does not call it"
fi
for step in '"$packager" deb' 'dpkg-deb --root-owner-group -Zxz --build' '"$packager" rpm' 'rpmbuild ' \
            'flatpak build-bundle' 'package-macos pkg' 'scripts/package-velopack.py' '--msi' \
            '--channel "$channel"' '--delta-dir'; do
    if grep -qF -- "$step" "$workflow"; then
        pass "fr22_6_5_the_samples_workflow_makes ($step)"
    else
        fail "fr22_6_5_the_samples_workflow_makes ($step): test-samples.yml has no '$step'"
    fi
done
# The direct packages are Velopack's, so the other packagers no longer make them.
for step in 'package-macos dmg' 'scripts/packager-linux/appimage.sh'; do
    if grep -qF -- "$step" "$workflow"; then
        fail "fr22_6_7_the_direct_packages_are_velopacks: test-samples.yml still runs '$step'"
    else
        pass "fr22_6_7_the_direct_packages_are_velopacks (no '$step')"
    fi
done
# Velopack wants the SDK in the samples and the vpk that packs them at one version.
pin="$(sed -n 's/^velopack = "=\(.*\)"$/\1/p' "$repo_root/Cargo.toml")"
vpk="$(sed -n "s/^ *VPK_VERSION: '\(.*\)'$/\1/p" "$workflow" | sort -u)"
if [[ -n "$pin" && "$pin" == "$vpk" ]]; then
    pass "fr22_6_7_the_samples_workflow_packs_with_the_sdks_vpk ($pin)"
else
    fail "fr22_6_7_the_samples_workflow_packs_with_the_sdks_vpk: workspace velopack is '${pin:-unset}', test-samples.yml VPK_VERSION is '${vpk:-unset}'"
fi
if grep -q -- '--allow-missing' "$workflow"; then
    fail "fr22_6_5_the_samples_workflow_fails_on_missing_formats: test-samples.yml passes --allow-missing"
else
    pass "fr22_6_5_the_samples_workflow_fails_on_missing_formats"
fi

[[ "$failures" -eq 0 ]] || { echo "$failures case(s) failed"; exit 1; }
echo "all passed"
