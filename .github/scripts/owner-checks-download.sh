#!/usr/bin/env bash
# Usage: .github/scripts/owner-checks-download.sh <target> <commit> <out-dir>
#
# Lays out the owner-checks download for one desktop target, from what the samples build
# packaged in the current directory (artifacts/ and staging/), and prints `name=<artifact>`
# for $GITHUB_OUTPUT.
#
# The person making the hand checks before a release downloads this rather than building
# anything, so each download is the app as it runs on that platform plus a README.txt that
# says how to start it and which commit it was built from:
#
#   macos-aarch64  owner-checks-macos-arm64: the ad hoc signed .app in a disk image
#   windows-x64    owner-checks-windows-x64: the one executable in a folder, and the unsigned
#                  .msix beside it for the notification check
#   linux-x64      owner-checks-linux-x64: the AppImage, and the executable as a .tar.gz
#   linux-arm64    owner-checks-linux-arm64: the same for arm64
#
# A missing app is a failure: a README on its own would send someone to run nothing.
set -euo pipefail

target="${1:?the target, such as macos-aarch64}"
commit="${2:?the commit the build was made from}"
out_root="${3:?the directory to lay the download out in}"

missing() {
    echo "owner-checks-download: $1 is missing, so there is no owner-checks app for $target" >&2
    exit 1
}

case "$target" in
    macos-aarch64) name=owner-checks-macos-arm64 ;;
    windows-x64) name=owner-checks-windows-x64 ;;
    linux-x64) name=owner-checks-linux-x64 ;;
    linux-arm64) name=owner-checks-linux-arm64 ;;
    *) echo "owner-checks-download: no download is made for $target" >&2; exit 1 ;;
esac

out="$out_root/$name"
rm -rf "$out"
mkdir -p "$out"

case "$target" in
    macos-*)
        dmg="artifacts/owner-checks-$target.dmg"
        [[ -f "$dmg" ]] || missing "$dmg"
        cp "$dmg" "$out/$name.dmg"
        how="$(cat <<EOF
Start it on a Mac with Apple silicon:

1. Open $name.dmg and drag Owner-checks to Applications (or anywhere).
2. The app is signed ad hoc, not by an Apple developer account, so macOS will not
   open it with a double click the first time. Right-click (or Control-click) it,
   choose Open, then press Open in the dialog. From then on a double click works.
   If macOS still refuses, open System Settings > Privacy & Security and press
   Open Anyway beside the line about Owner-checks.
EOF
)"
        ;;
    windows-*)
        exe="staging/owner-checks/sample-owner-checks.exe"
        [[ -f "$exe" ]] || missing "$exe"
        mkdir -p "$out/owner-checks"
        cp "$exe" "$out/owner-checks/"
        msix="artifacts/owner-checks-$target.msix"
        [[ -f "$msix" ]] && cp "$msix" "$out/$name.msix"
        how="$(cat <<EOF
This is the build to run on a real Windows machine (your own laptop, not a virtual
machine) for the Windows checks before a release, including the consumer run: the
crate is from the commit below and the renderer was built in the same CI run.

Start it:

1. Unzip this download into an empty folder.
2. Double-click owner-checks\\sample-owner-checks.exe. It is one file and needs
   nothing installed beside it.
3. The executable is not signed, so SmartScreen may stop it the first time. Press
   More info, then Run anyway.

Notifications: an executable started from a folder has no Start menu entry, and
Windows shows toasts only for installed apps, so the Notifications screen says
Unsupported. That answer is expected. To see a toast, install $name.msix
(it is unsigned: turn on Developer Mode in Settings > System > For developers, or
install it with Add-AppxPackage -AllowUnsigned in PowerShell) and start
Owner-checks from the Start menu.
EOF
)"
        ;;
    linux-*)
        appimage="artifacts/owner-checks-$target.AppImage"
        [[ -f "$appimage" ]] || missing "$appimage"
        cp "$appimage" "$out/$name.AppImage"
        tarball="artifacts/owner-checks-$target.tar.gz"
        [[ -f "$tarball" ]] && cp "$tarball" "$out/$name.tar.gz"
        how="$(cat <<EOF
Start it:

1. A download from CI loses the executable bit, so set it first:
   chmod +x $name.AppImage
2. Run ./$name.AppImage. An AppImage needs FUSE 2 (libfuse2, on Ubuntu 24.04
   libfuse2t64).
3. Without FUSE, use the archive instead: tar -xzf $name.tar.gz, then run
   ./owner-checks/sample-owner-checks (the archive keeps the executable bit).
EOF
)"
        ;;
esac

cat > "$out/README.txt" <<EOF
Owner-checks, the app for the hand checks before a compose-rust release.

Built from commit $commit.
The About this build screen in the app shows the same commit, the compose-rust
version and the schema hash, so you can tell which build is running.

$how

Each screen in the list on the left is one check. It says what to do and what you
must see.
EOF

echo "name=$name"
