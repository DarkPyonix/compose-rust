#!/usr/bin/env bash
# .github/scripts/check-sample-bundles.py: what the samples workflow packaged is checked
# before it is uploaded, and the check notices each way a bundle can be wrong.
#
# A desktop bundle holds the sample's one executable, its icon and its metadata, nothing
# else; the executable loads only the system's libraries and nothing by a build machine
# path; the name, identifier, description and version are the sample's; the MSIX is
# unsigned; every file is in the checksum list with its right digest. A format that is
# missing is a warning and a line in skipped-<target>.txt, not a failure.
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
import hashlib, io, plistlib, struct, sys, tarfile, zipfile
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
    zipped(artifacts / f"demo-{target}.msix", msix)
elif platform == "linux":
    needed = ["libc.so.6", "libX11.so.6"]
    if fault == "absolute-renderer":
        needed.append("/home/runner/work/renderer/lib/libcompose_rust_renderer.so")
    exe = elf(needed, "/home/runner/work/renderer/lib" if fault == "runpath" else None)
    tarball(artifacts / f"demo-{target}.tar.gz", "demo/sample-demo", exe,
            ("demo/lib/libskiko.so", b"\x7fELF") if fault == "extra-file" else None)
    # No AppImage: assembling one needs Linux tools, and a missing format is a case too.
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
    zipped(artifacts / f"demo-{target}.app.zip", entries)
    # No .dmg: only macOS makes one, and a missing format is a case too.

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

# run_case <name> <target> <fault> <expect: ok|fail> [text the output must hold]
run_case() {
    local name="$1" target="$2" fault="$3" expect="$4" text="${5:-}"
    local case="$scratch/$name" out status=0
    make_case "$case" "$target" "$fault" || { fail "$name: could not make the fixture"; return; }
    out="$(cd "$case" && python3 -I "$script" --target "$target" --artifacts artifacts \
        --samples samples --built built.txt --version 1.2.3 2>&1)" || status=$?
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

run_case fr22_6_1_windows_bundles_that_are_right_pass windows-x64 "" ok "ok    demo-windows-x64.msix"
run_case fr22_6_1_macos_bundles_that_are_right_pass macos-aarch64 "" ok "ok    demo-macos-aarch64.app.zip"
run_case fr22_6_1_linux_bundles_that_are_right_pass linux-x64 "" ok "ok    demo-linux-x64.tar.gz"
run_case fr22_6_1_a_missing_format_is_a_warning_not_a_failure linux-x64 "" ok \
    "::warning title=Format missing::sample-demo has no .AppImage for linux-x64"
if grep -qx "demo linux-x64 AppImage" "$scratch/fr22_6_1_a_missing_format_is_a_warning_not_a_failure/artifacts/skipped-linux-x64.txt" 2>/dev/null; then
    pass "fr22_6_1_a_missing_format_is_listed_for_the_release_notes"
else
    fail "fr22_6_1_a_missing_format_is_listed_for_the_release_notes: skipped-linux-x64.txt does not name it"
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

# The samples workflow runs the check on every desktop row, after the checksums.
workflow="$repo_root/.github/workflows/test-samples.yml"
if grep -q 'check-sample-bundles.py' "$workflow"; then
    pass "fr22_6_1_the_samples_workflow_runs_the_check"
else
    fail "fr22_6_1_the_samples_workflow_runs_the_check: test-samples.yml does not call it"
fi

[[ "$failures" -eq 0 ]] || { echo "$failures case(s) failed"; exit 1; }
echo "all passed"
