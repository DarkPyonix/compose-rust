#!/usr/bin/env bash
# Usage: ./scripts/tests/owner-checks-download.test.sh
#
# The owner-checks downloads: the prebuilt app a person runs for the hand checks before a
# release, one per desktop platform, from the samples workflow. Nothing is built here.
#
# - .github/scripts/owner-checks-download.sh lays each one out from what the samples build
#   packaged, under the name the hand-check list gives (owner-checks-macos-arm64,
#   owner-checks-windows-x64, owner-checks-linux-x64, and linux-arm64 beside it), with a
#   README.txt that says how to start it on that platform, names the commit, and for Windows
#   says this is the build to run on a real Windows machine. A missing app fails it rather
#   than uploading a README alone.
# - test-samples.yml runs it after the samples are packaged and uploads what it laid out,
#   and the build loop takes the `samples` input.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/.github/scripts/owner-checks-download.sh"
workflow="$repo_root/.github/workflows/test-samples.yml"
scratch="$repo_root/.scratch/owner-checks-download-test"
rm -rf "$scratch"
mkdir -p "$scratch"
trap 'rm -rf "$scratch"' EXIT

failures=0
ok() { printf 'ok    %s\n' "$1"; }
fail() { failures=$((failures + 1)); printf 'FAIL  %s\n' "$1" >&2; }

commit=0123456789abcdef0123456789abcdef01234567

# A checkout after the samples build: what the packaging steps leave behind.
fixture() {
    local case_dir="$1"
    mkdir -p "$case_dir/artifacts" "$case_dir/staging/owner-checks"
    : > "$case_dir/artifacts/owner-checks-macos-aarch64.dmg"
    : > "$case_dir/artifacts/owner-checks-linux-x64.AppImage"
    : > "$case_dir/artifacts/owner-checks-linux-x64.tar.gz"
    : > "$case_dir/artifacts/owner-checks-linux-arm64.AppImage"
    : > "$case_dir/artifacts/owner-checks-windows-x64.msix"
    : > "$case_dir/staging/owner-checks/sample-owner-checks.exe"
}

check_target() {
    local target="$1" name="$2"
    shift 2
    local case_dir="$scratch/$target" out
    fixture "$case_dir"
    if ! out="$(cd "$case_dir" && "$script" "$target" "$commit" downloads 2>&1)"; then
        fail "$target: the script failed: $out"
        return
    fi
    [[ "$out" == *"name=$name"* ]] && ok "$target: the download is named $name" ||
        fail "$target: the script printed '$out', not name=$name"
    local dir="$case_dir/downloads/$name" file
    for file in "$@" README.txt; do
        [[ -f "$dir/$file" ]] && ok "$target: $name holds $file" || fail "$target: $name has no $file"
    done
    local readme="$dir/README.txt"
    [[ -f "$readme" ]] || return
    grep -qF "$commit" "$readme" && ok "$target: README.txt names the commit" ||
        fail "$target: README.txt does not name the commit"
    if LC_ALL=C grep -q $'\xe2\x80\x94' "$readme"; then
        fail "$target: README.txt has an em dash"
    fi
    case "$target" in
        macos-*)
            grep -q 'Open' "$readme" && grep -qi 'right-click\|control-click' "$readme" &&
                ok "$target: README.txt says how to open an unsigned app" ||
                fail "$target: README.txt does not say right-click, Open"
            ;;
        windows-*)
            grep -q 'More info' "$readme" && grep -q 'Run anyway' "$readme" &&
                ok "$target: README.txt says how to pass SmartScreen" ||
                fail "$target: README.txt does not say More info, Run anyway"
            grep -q 'real Windows machine' "$readme" &&
                ok "$target: README.txt says this is the build for a real Windows machine" ||
                fail "$target: README.txt does not say it is the build for a real Windows machine"
            ;;
        linux-*)
            grep -q 'chmod +x' "$readme" && ok "$target: README.txt says to make it executable" ||
                fail "$target: README.txt does not say chmod +x"
            ;;
    esac
}

check_target macos-aarch64 owner-checks-macos-arm64 owner-checks-macos-arm64.dmg
check_target windows-x64 owner-checks-windows-x64 owner-checks/sample-owner-checks.exe owner-checks-windows-x64.msix
check_target linux-x64 owner-checks-linux-x64 owner-checks-linux-x64.AppImage owner-checks-linux-x64.tar.gz
check_target linux-arm64 owner-checks-linux-arm64 owner-checks-linux-arm64.AppImage

# No app, no download: a README on its own would send someone to run nothing.
empty="$scratch/empty"
mkdir -p "$empty/artifacts"
if (cd "$empty" && "$script" macos-aarch64 "$commit" downloads >/dev/null 2>&1); then
    fail "a macOS download with no disk image was laid out"
else
    ok "a download with no app fails"
fi

# The workflow runs it and uploads what it laid out, and its build loop takes `samples`.
if [[ -f "$workflow" ]]; then
    grep -qF '.github/scripts/owner-checks-download.sh' "$workflow" &&
        ok "test-samples.yml lays out the owner-checks download" ||
        fail "test-samples.yml does not run owner-checks-download.sh"
    grep -qF 'name: ${{ steps.owner_checks.outputs.name }}' "$workflow" &&
        ok "test-samples.yml uploads it under the name the script gives" ||
        fail "test-samples.yml does not upload the download under its name"
    grep -qF 'COMPOSE_RUST_SAMPLE_COMMIT' "$workflow" &&
        ok "test-samples.yml tells the samples which commit they are built from" ||
        fail "test-samples.yml does not set COMPOSE_RUST_SAMPLE_COMMIT for the build"
    grep -qF 'SAMPLES: ${{ inputs.samples }}' "$workflow" &&
        ok "the build loop takes the samples input" ||
        fail "the build loop does not take the samples input"
else
    fail "$workflow does not exist"
fi

if [[ "$failures" -gt 0 ]]; then
    echo "$failures check(s) failed" >&2
    exit 1
fi
echo "all passed"
