#!/usr/bin/env bash
# Offline tests for scripts/install-nik.sh.
#
# The download path needs the network and ~1GB of disk, so it is exercised by
# CI, not here. What is tested here is the logic CI depends on being right:
# the short-circuit that makes a warm cache free, and the argument handling.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
install_nik="$script_dir/../install-nik.sh"

# Liberica NIK is a macOS requirement: upstream GraalVM ships no AWT on Darwin, and
# everywhere else the stock GraalVM is the right toolchain. install-nik.sh therefore
# refuses to run anywhere else, which means these tests can only exercise it on macOS.
# Skipping is the honest outcome; pretending otherwise made every reuse test fail on a
# Linux runner against a script that had correctly declined to do anything.
if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "skip  install-nik.sh is macOS only; these tests run on the macOS runner"
    exit 0
fi

failures=0
pass() { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; failures=$((failures + 1)); }
check() { if [[ "$2" == "$3" ]]; then pass "$1"; else fail "$1: expected [$3], got [$2]"; fi; }

# A fake installation, so the short-circuit can be exercised without a download.
host_arch() {
    case "$(uname -m)" in
        arm64|aarch64) echo aarch64 ;;
        *) echo amd64 ;;
    esac
}

fake_install() {
    local root="$1"
    local home="$root/25.0.4.1-macos-$(host_arch)"
    mkdir -p "$home/bin"
    printf '#!/bin/sh\n' > "$home/bin/native-image"
    chmod +x "$home/bin/native-image"
    # A NIK Full home is only usable if it carries the AWT static libraries;
    # build-native.sh links libawt_lwawt.a directly, and refuses to start an
    # image build without it.
    mkdir -p "$home/lib/static/darwin-$(host_arch)"
    : > "$home/lib/static/darwin-$(host_arch)/libawt_lwawt.a"
    echo "$home"
}

# --- an existing installation is reused, and its path is printed ------------
tmp="$(mktemp -d)"
expected_home="$(fake_install "$tmp/nik")"
actual_home="$(NIK_INSTALL_DIR="$tmp/nik" "$install_nik" 2>/dev/null)"
check "reuses an existing installation" "$actual_home" "$expected_home"

# --- rerunning changes nothing: no download, same answer -------------------
before="$(find "$tmp/nik" | sort)"
second_home="$(NIK_INSTALL_DIR="$tmp/nik" "$install_nik" 2>/dev/null)"
after="$(find "$tmp/nik" | sort)"
check "is idempotent (same GRAALVM_HOME)" "$second_home" "$expected_home"
check "is idempotent (tree untouched)" "$after" "$before"

# --- --github-env appends GRAALVM_HOME for later workflow steps ------------
GITHUB_ENV="$tmp/github_env" NIK_INSTALL_DIR="$tmp/nik" "$install_nik" --github-env >/dev/null 2>&1
check "--github-env writes GRAALVM_HOME" "$(cat "$tmp/github_env")" "GRAALVM_HOME=$expected_home"

# --- --github-env without $GITHUB_ENV is an error, not a silent no-op ------
env -u GITHUB_ENV NIK_INSTALL_DIR="$tmp/nik" "$install_nik" --github-env >/dev/null 2>&1
check "--github-env fails without \$GITHUB_ENV" "$?" "1"

# --- an install without the AWT static libraries is not reused -------------
# Regression: the installer used to locate java.home by searching for
# bin/native-image, and `find -type f` skips the symlink at Contents/Home/bin,
# so it matched Contents/Home/lib/svm/bin/native-image and installed lib/svm as
# GRAALVM_HOME. That directory runs native-image and has no AWT, and because
# the reuse check only looked for bin/native-image, a CI cache holding it was
# handed back on every run. build-native.sh failed the same way every time.
broken="$tmp/broken"
broken_home="$(fake_install "$broken")"
rm -rf "$broken_home/lib"
# The download that follows will fail in a sandbox, which is fine: the
# "Reinstalling" notice is printed before it, so this asserts the reuse
# decision alone rather than pulling a 1GB archive in a unit test.
out="$(NIK_INSTALL_DIR="$broken" "$install_nik" 2>&1 || true)"
case "$out" in
    *"Reinstalling"*) result="rejected" ;;
    *) result="reused: $out" ;;
esac
check "does not reuse an install with no libawt_lwawt.a" "$result" "rejected"

# --- unknown flags are rejected with the usage exit code -------------------
NIK_INSTALL_DIR="$tmp/nik" "$install_nik" --wat >/dev/null 2>&1
check "rejects unknown flags" "$?" "2"

rm -rf "$tmp"

if (( failures )); then
    echo "$failures test(s) failed" >&2
    exit 1
fi
echo "all install-nik.sh tests passed"

# The Windows job downloads NIK itself rather than using the installer, because the
# installer is a shell script and the runner is PowerShell. Two places naming the same
# version is two places to forget, so they are checked against each other here: a Windows
# renderer built from a different NIK than the macOS one is a difference nobody chose.
repo_root="$(cd "$script_dir/../.." && pwd)"
workflow="$repo_root/.github/workflows/native-renderer.yml"
if [[ -f "$workflow" ]]; then
    jdk_in_script="$(grep -o 'NIK_JDK_BUILD="[^"]*"' "$repo_root/scripts/install-nik.sh" | cut -d'"' -f2)"
    vm_in_script="$(grep -o 'NIK_VM_BUILD="[^"]*"' "$repo_root/scripts/install-nik.sh" | cut -d'"' -f2)"
    jdk_in_workflow="$(grep -o "\\\$jdk = '[^']*'" "$workflow" | head -1 | cut -d"'" -f2)"
    vm_in_workflow="$(grep -o "\\\$vm = '[^']*'" "$workflow" | head -1 | cut -d"'" -f2)"
    if [[ -n "$jdk_in_workflow" ]]; then
        if [[ "$jdk_in_script" != "$jdk_in_workflow" || "$vm_in_script" != "$vm_in_workflow" ]]; then
            echo "fail  the installer and the Windows job pin different NIK builds" >&2
            echo "      installer: $vm_in_script / $jdk_in_script" >&2
            echo "      workflow:  $vm_in_workflow / $jdk_in_workflow" >&2
            exit 1
        fi
        echo "ok    the installer and the Windows job pin the same NIK build"
    fi
fi
