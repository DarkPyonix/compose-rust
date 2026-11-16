#!/usr/bin/env bash
# Usage: ./scripts/tests/fr22-velopack.test.sh [macos|linux|windows]
#
# Velopack direct distribution: packages, the update feed, and an installed app updating
# itself on each desktop OS.
#
# With no argument it runs the packager's unit tests, which need neither vpk nor a build,
# and says that the end-to-end checks were skipped; the basic Test suite runs it that way.
#
# With an OS it also runs that OS's end-to-end check, and a missing tool is a failure:
# .github/workflows/packager-macos.yml runs it so on each runner, with vpk installed. The
# check builds scripts/tests/velopack-fixture (a real Velopack app) as 1.0.0 and 1.0.1,
# packs both with scripts/package-velopack.py (the second against the first, so there is
# a delta), installs 1.0.0 the way a person would, has it update itself from the 1.0.1
# feed, waits for the updater, and runs the installed copy again expecting 1.0.1.
#
# The end-to-end checks install into this machine's user account (%LocalAppData% on
# Windows, Velopack's package cache in ~/Library/Caches or /var/tmp), which is why they run
# only on a CI runner. Their scratch files go to $RUNNER_TEMP, or .scratch/ elsewhere.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

target="${1:-}"
python="${PYTHON:-python3}"
pack_id="dev.darkpyonix.velopackfixture"
fixture_dir="scripts/tests/velopack-fixture"

failures=0
fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
    shift
    local line
    for line in "$@"; do printf '        %s\n' "$line" >&2; done
}
pass() { printf 'ok    %s\n' "$1"; }
note() { printf 'note  %s\n' "$1"; }

# Git Bash on Windows: bash wants /d/a/..., the programs it starts want D:\a\...
to_unix() { if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi; }
to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

work_root() {
    if [[ -n "${RUNNER_TEMP:-}" ]]; then
        printf '%s/fr22-velopack' "$(to_unix "$RUNNER_TEMP")"
    else
        printf '%s/.scratch/fr22-velopack' "$repo_root"
    fi
}

fr22_6_7_the_packager_builds_vpk_commands_and_reads_the_feed() {
    local name="fr22_6_7_the_packager_builds_vpk_commands_and_reads_the_feed"
    if ! "$python" -c 'import tomllib' >/dev/null 2>&1; then
        if [[ -z "$target" ]]; then
            note "$name needs Python 3.11 or newer as $python"
            return 0
        fi
        fail "$name" "$python has no tomllib; Python 3.11 or newer is needed"
        return 1
    fi
    local output
    if ! output="$("$python" -m unittest scripts/packager-macos/tests/test_velopack.py 2>&1)"; then
        fail "$name" "scripts/packager-macos/tests/test_velopack.py failed:" "$output"
        return 1
    fi
    pass "$name"
}

# Whether an end-to-end check can run here. Without an OS argument a missing tool is a
# skip; with one it is a failure, so the CI job cannot pass by doing nothing.
have_tools() {
    local name="$1"
    shift
    local tool missing=()
    for tool in "$@"; do
        command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
    done
    if [[ ${#missing[@]} -eq 0 ]]; then
        return 0
    fi
    fail "$name" "missing: ${missing[*]}"
    return 1
}

exe_suffix() { [[ "$target" == windows ]] && printf '.exe'; }

# Builds the fixture as $1 and copies it to $2. The fixture's Cargo.lock is checked in, so
# the build uses exactly it. FIXTURE_VERSION is read with option_env!, which cargo tracks, so
# the second build recompiles the fixture with the new version.
build_fixture() {
    local version="$1" dest="$2"
    FIXTURE_VERSION="$version" cargo build --release --locked --manifest-path "$fixture_dir/Cargo.toml" >&2 ||
        return 1
    cp "$fixture_dir/target/release/velopack-fixture$(exe_suffix)" "$dest"
}

# Packs $1 (an executable) as version $2 into $3, against the previous release in $4 if
# given; any further arguments go to the packager. Its JSON goes to $3.json. The copy is
# named after its version, so the packaged name is given: otherwise it would be
# fixture-1.0.0, a different main executable in each release, and not the
# velopack-fixture the checks run.
pack() {
    local executable="$1" version="$2" out="$3" previous="${4:-}"
    shift 4 || shift $#
    local args=(
        scripts/package-velopack.py
        --pack-id "$pack_id" --pack-title VelopackFixture --pack-authors DarkPyonix
        --pack-version "$version"
        --main-exe velopack-fixture
        --executable "$(to_native "$executable")"
        --output-dir "$(to_native "$out")"
    )
    if [[ -n "$previous" ]]; then
        args+=(--delta-dir "$(to_native "$previous")")
    fi
    "$python" "${args[@]}" "$@" >"$out.json"
}

# Builds and packs 1.0.0 into $work/v1 and 1.0.1 into $work/v2, then checks the files
# every OS must have. $1 is the check's name, $2 the channel; the rest goes to
# the first pack.
build_and_pack_both() {
    local name="$1" channel="$2"
    shift 2
    rm -rf "$work"
    mkdir -p "$work"
    if ! build_fixture 1.0.0 "$work/fixture-1.0.0$(exe_suffix)"; then
        fail "$name" "building the fixture as 1.0.0 failed"
        return 1
    fi
    if ! pack "$work/fixture-1.0.0$(exe_suffix)" 1.0.0 "$work/v1" "" "$@"; then
        fail "$name" "packing 1.0.0 failed (vpk's output is above)"
        return 1
    fi
    if ! build_fixture 1.0.1 "$work/fixture-1.0.1$(exe_suffix)"; then
        fail "$name" "building the fixture as 1.0.1 failed"
        return 1
    fi
    if ! pack "$work/fixture-1.0.1$(exe_suffix)" 1.0.1 "$work/v2" "$work/v1"; then
        fail "$name" "packing 1.0.1 against 1.0.0 failed (vpk's output is above)"
        return 1
    fi

    local full1 full2 delta2
    if [[ "$channel" == win ]]; then
        full1="$pack_id-1.0.0-full.nupkg" full2="$pack_id-1.0.1-full.nupkg" delta2="$pack_id-1.0.1-delta.nupkg"
    else
        full1="$pack_id-1.0.0-$channel-full.nupkg"
        full2="$pack_id-1.0.1-$channel-full.nupkg"
        delta2="$pack_id-1.0.1-$channel-delta.nupkg"
    fi
    local file missing=()
    for file in "v1/$full1" "v1/releases.$channel.json" "v2/$full2" "v2/$delta2" "v2/releases.$channel.json"; do
        [[ -f "$work/$file" ]] || missing+=("$file")
    done
    if [[ ${#missing[@]} -gt 0 ]]; then
        fail "$name" "vpk did not write: ${missing[*]}" "v1: $(ls "$work/v1" | tr '\n' ' ')" \
            "v2: $(ls "$work/v2" | tr '\n' ' ')"
        return 1
    fi
    if ! grep -q "\"$delta2\"" "$work/v2/releases.$channel.json"; then
        fail "$name" "releases.$channel.json of 1.0.1 does not list $delta2" \
            "$(cat "$work/v2/releases.$channel.json")"
        return 1
    fi
}

# Waits until no process named $1 is left, up to three minutes.
wait_for_updater() {
    local process="$1" i
    if [[ "$target" == windows ]]; then
        powershell.exe -NoProfile -Command \
            "Get-Process -Name '$process' -ErrorAction SilentlyContinue | Wait-Process -Timeout 180 -ErrorAction SilentlyContinue" ||
            true
        return 0
    fi
    for ((i = 0; i < 180; i++)); do
        pgrep -x "$process" >/dev/null 2>&1 || return 0
        sleep 1
    done
}

# Runs `$1 --version` until it prints $2, up to a minute. Prints what it last printed.
await_version() {
    local executable="$1" expected="$2" got="" i
    for ((i = 0; i < 30; i++)); do
        got="$("$executable" --version 2>/dev/null | tr -d '\r')" || true
        [[ "$got" == "$expected" ]] && break
        sleep 2
    done
    printf '%s' "$got"
}

fr22_6_7_windows_setup_installs_and_a_delta_update_applies() {
    local name="fr22_6_7_windows_setup_installs_and_a_delta_update_applies"
    have_tools "$name" vpk cargo "$python" powershell.exe || return 1
    if [[ -z "${LOCALAPPDATA:-}" ]]; then
        fail "$name" "LOCALAPPDATA is not set"
        return 1
    fi
    build_and_pack_both "$name" win --msi || return 1

    local setup="$work/v1/$pack_id-win-Setup.exe" file
    for file in "$setup" "$work/v1/$pack_id-win.msi" "$work/v2/$pack_id-win-Setup.exe"; do
        if [[ ! -f "$file" ]]; then
            fail "$name" "vpk did not write $(basename "$file")" "$(ls "$work/v1" | tr '\n' ' ')"
            return 1
        fi
    done

    # Setup.exe --silent installs per user into %LocalAppData%\<packId>, keeps the full
    # package in packages\ (the base a delta applies to), and does not start the app.
    local root installed
    root="$(to_unix "$LOCALAPPDATA")/$pack_id"
    installed="$root/current/velopack-fixture.exe"
    "$setup" --silent --verbose --log "$(to_native "$work/setup.log")"
    local i=0
    while [[ ! -f "$installed" && $i -lt 60 ]]; do
        sleep 1
        i=$((i + 1))
    done
    if [[ ! -f "$installed" ]]; then
        fail "$name" "Setup.exe --silent installed nothing at $installed" "$(tail -n 40 "$work/setup.log" 2>/dev/null)"
        return 1
    fi
    local got
    got="$("$installed" --version | tr -d '\r')"
    if [[ "$got" != 1.0.0 ]]; then
        fail "$name" "the installed app printed '$got', not 1.0.0"
        return 1
    fi

    local update_output
    if ! update_output="$("$installed" --update "$(to_native "$work/v2")" 2>&1)"; then
        fail "$name" "the installed app could not update from the 1.0.1 feed:" "$update_output"
        return 1
    fi
    printf '%s\n' "$update_output"
    if ! grep -q "update 1.0.0 -> 1.0.1, deltas 1" <<<"$update_output"; then
        fail "$name" "the update was not the one delta from 1.0.0 to 1.0.1:" "$update_output"
        return 1
    fi
    wait_for_updater Update
    got="$(await_version "$installed" 1.0.1)"
    if [[ "$got" != 1.0.1 ]]; then
        fail "$name" "after the update the installed app printed '$got', not 1.0.1" \
            "$(tail -n 40 "$(to_unix "$LOCALAPPDATA")/velopack/velopack_$pack_id.log" 2>/dev/null)"
        return 1
    fi
    got="$("$installed" --installed-version | tr -d '\r')"
    if [[ "$got" != 1.0.1 ]]; then
        fail "$name" "Velopack's manifest says '$got' after the update, not 1.0.1"
        return 1
    fi
    # Applying the delta to the 1.0.0 base rebuilds the 1.0.1 full package locally.
    if [[ ! -f "$root/packages/$pack_id-1.0.1-full.nupkg" ]]; then
        note "$name: no rebuilt 1.0.1 package in $root/packages ($(ls "$root/packages" 2>/dev/null | tr '\n' ' '))"
    fi
    pass "$name"
}

fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches() {
    local name="fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches"
    have_tools "$name" vpk cargo "$python" codesign hdiutil xattr ditto || return 1
    if [[ "$(uname -m)" != arm64 ]]; then
        note "$name runs on $(uname -m), not Apple silicon"
    fi
    build_and_pack_both "$name" osx || return 1

    local dmg="$work/v1/$pack_id-osx.dmg"
    if [[ ! -f "$dmg" ]]; then
        fail "$name" "the 1.0.0 .dmg was not made" "$(ls "$work/v1" | tr '\n' ' ')"
        return 1
    fi

    # Install as a person does: open the .dmg and copy the app out of it.
    local mount="$work/mount" applications="$work/Applications"
    mkdir -p "$mount" "$applications"
    if ! hdiutil attach "$dmg" -mountpoint "$mount" -nobrowse -readonly -quiet; then
        fail "$name" "hdiutil could not attach $dmg"
        return 1
    fi
    local source
    source="$(find "$mount" -maxdepth 1 -name '*.app' | head -n 1)"
    local notes_ok=true
    [[ -f "$mount/If macOS will not open the app.txt" ]] || notes_ok=false
    [[ -n "$source" ]] && ditto "$source" "$applications/$(basename "$source")"
    hdiutil detach "$mount" -quiet || true
    if [[ "$notes_ok" != true || -z "$source" ]]; then
        fail "$name" "the .dmg lacks the app or 'If macOS will not open the app.txt'"
        return 1
    fi
    local app="$applications/$(basename "$source")"
    local executable="$app/Contents/MacOS/velopack-fixture"

    # The mark a browser leaves on a download. The executable inside is run directly, as
    # a person's copy runs once they have let Gatekeeper open it; that first approval is
    # the part only a person can do, on a real Mac.
    xattr -w com.apple.quarantine "0081;$(printf '%x' "$(date +%s)");Safari;" "$app"
    note "$name: Gatekeeper's verdict on the quarantined copy: $(spctl --assess --type execute -v "$app" 2>&1 | tr '\n' ' ')"

    local verify
    if ! verify="$(codesign --verify --deep --strict --verbose=2 "$app" 2>&1)"; then
        fail "$name" "the installed 1.0.0 app does not pass codesign --verify --deep --strict" "$verify"
        return 1
    fi
    local got
    got="$("$executable" --version)"
    if [[ "$got" != 1.0.0 ]]; then
        fail "$name" "the installed app printed '$got', not 1.0.0"
        return 1
    fi

    local update_output
    if ! update_output="$("$executable" --update "$work/v2" 2>&1)"; then
        fail "$name" "the installed app could not update from the 1.0.1 feed:" "$update_output"
        return 1
    fi
    printf '%s\n' "$update_output"
    if ! grep -q "update 1.0.0 -> 1.0.1" <<<"$update_output"; then
        fail "$name" "the app did not find the 1.0.1 update:" "$update_output"
        return 1
    fi
    wait_for_updater UpdateMac
    got="$(await_version "$executable" 1.0.1)"
    if [[ "$got" != 1.0.1 ]]; then
        fail "$name" "after the update the app printed '$got', not 1.0.1" \
            "$(tail -n 40 "$HOME/Library/Logs/velopack_$pack_id.log" 2>/dev/null)"
        return 1
    fi
    if ! verify="$(codesign --verify --deep --strict --verbose=2 "$app" 2>&1)"; then
        fail "$name" "the updated app does not pass codesign --verify --deep --strict" "$verify"
        return 1
    fi
    note "$name: updated signature: $(codesign -dv "$app" 2>&1 | grep -E '^(Signature|CodeDirectory)' | tr '\n' ' ')"
    pass "$name"
}

fr22_6_7_linux_appimage_replaces_itself_with_the_update() {
    local name="fr22_6_7_linux_appimage_replaces_itself_with_the_update"
    have_tools "$name" vpk cargo "$python" mksquashfs || return 1
    build_and_pack_both "$name" linux || return 1

    local image="$work/v1/$pack_id.AppImage" image2="$work/v2/$pack_id.AppImage"
    if [[ ! -f "$image" || ! -f "$image2" ]]; then
        fail "$name" "vpk did not write $pack_id.AppImage" "$(ls "$work/v1" | tr '\n' ' ')"
        return 1
    fi

    # Where a person keeps a downloaded AppImage. It runs through FUSE (libfuse2), the
    # way it does on a desktop; the updater replaces the file at $APPIMAGE.
    local installed="$work/Applications/velopack-fixture.AppImage"
    mkdir -p "$work/Applications"
    cp "$image" "$installed"
    chmod +x "$installed"
    local got
    got="$("$installed" --version 2>"$work/appimage.stderr")" || true
    if [[ "$got" != 1.0.0 ]]; then
        fail "$name" "the 1.0.0 AppImage printed '$got', not 1.0.0 (an AppImage needs libfuse2):" \
            "$(tail -n 20 "$work/appimage.stderr")"
        return 1
    fi

    local update_output
    if ! update_output="$("$installed" --update "$work/v2" 2>&1)"; then
        fail "$name" "the AppImage could not update from the 1.0.1 feed:" "$update_output"
        return 1
    fi
    printf '%s\n' "$update_output"
    if ! grep -q "update 1.0.0 -> 1.0.1" <<<"$update_output"; then
        fail "$name" "the AppImage did not find the 1.0.1 update:" "$update_output"
        return 1
    fi
    wait_for_updater UpdateNix
    got="$(await_version "$installed" 1.0.1)"
    if [[ "$got" != 1.0.1 ]]; then
        fail "$name" "after the update the AppImage printed '$got', not 1.0.1" \
            "$(tail -n 40 "${TMPDIR:-/tmp}/velopack_$pack_id.log" 2>/dev/null)"
        return 1
    fi
    if ! cmp -s "$installed" "$image2"; then
        fail "$name" "the file at the AppImage's path is not the 1.0.1 AppImage"
        return 1
    fi
    pass "$name"
}

fr22_6_7_the_packager_builds_vpk_commands_and_reads_the_feed

work="$(work_root)/$target"
case "$target" in
    "")
        note "the Velopack end-to-end checks run with an OS argument (macos, linux, windows) on a CI runner"
        ;;
    windows) fr22_6_7_windows_setup_installs_and_a_delta_update_applies ;;
    macos) fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches ;;
    linux) fr22_6_7_linux_appimage_replaces_itself_with_the_update ;;
    *)
        fail "usage" "unknown OS '$target'; use macos, linux or windows"
        ;;
esac

if [[ "$failures" -ne 0 ]]; then
    exit 1
fi
