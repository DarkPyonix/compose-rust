#!/usr/bin/env bash
# Usage: ./scripts/tests/setup-check.test.sh
#
# Exercises scripts/setup-check.sh. The happy path asserts that this machine is
# actually set up; the failure cases run the script against a synthetic HOME and
# PATH so that each "missing tool" branch is covered without touching the machine.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
setup_check="$repo_root/scripts/setup-check.sh"

tests_run=0
tests_failed=0

# assert_run <name> <expected status> <expected substring|-> <command...>
assert_run() {
    local name="$1" expected_status="$2" expected_substring="$3"
    shift 3
    tests_run=$((tests_run + 1))
    local output status
    output="$("$@" 2>&1)"
    status=$?
    if [[ "$status" != "$expected_status" ]]; then
        tests_failed=$((tests_failed + 1))
        printf 'FAIL %s: expected exit %s, got %s\n%s\n' "$name" "$expected_status" "$status" "$output" >&2
        return
    fi
    if [[ "$expected_substring" != "-" && "$output" != *"$expected_substring"* ]]; then
        tests_failed=$((tests_failed + 1))
        printf 'FAIL %s: output did not contain %q\n%s\n' "$name" "$expected_substring" "$output" >&2
        return
    fi
    printf 'ok   %s\n' "$name"
}

# This machine is a configured development machine, so the real run must pass.
assert_run "setup_check_passes_on_this_machine" 0 "All checks passed." \
    "$setup_check"

assert_run "setup_check_quiet_mode_exits_zero" 0 "-" \
    "$setup_check" --quiet

assert_run "setup_check_rejects_unknown_argument" 2 "usage:" \
    "$setup_check" --nonsense

# No cargo on PATH, and an empty HOME so the NIK glob finds nothing.
real_home="$HOME"
empty_home="$(mktemp -d)"
trap 'rm -rf "$empty_home"' EXIT

assert_run "setup_check_reports_missing_cargo" 1 "cargo not found on PATH" \
    env -u GRAALVM_HOME PATH=/usr/bin:/bin HOME="$empty_home" "$setup_check"

# Which toolchain is missing depends on the platform: macOS needs Liberica NIK Full for the
# native image because upstream GraalVM ships no AWT there, and everywhere else upstream
# GraalVM is the right one. Not having it is never fatal. It blocks the native image and
# nothing else, and on macOS the renderer that ships is not the native image at all.
if [[ "$(uname -s)" == "Darwin" ]]; then
    assert_run "setup_check_reports_missing_nik" 1 "no Liberica NIK found" \
        env -u GRAALVM_HOME HOME="$empty_home" "$setup_check"
else
    # Keep rustup discoverable. The empty HOME above exists to make the macOS toolchain
    # glob find nothing, and that glob is not used here, but rustup does live under HOME,
    # so blanking it would report missing rustfmt and clippy that are installed.
    assert_run "setup_check_warns_about_a_missing_graalvm" 0 "GRAALVM_HOME is not set" \
        env -u GRAALVM_HOME HOME="$empty_home" \
            RUSTUP_HOME="${RUSTUP_HOME:-$real_home/.rustup}" \
            CARGO_HOME="${CARGO_HOME:-$real_home/.cargo}" \
            "$setup_check"
fi

# A GRAALVM_HOME that exists but has no native-image at all. Wrong on every platform:
# the variable was set, so someone meant to point at a toolchain.
bogus_home="$empty_home/bogus-jdk"
mkdir -p "$bogus_home/bin"
assert_run "setup_check_reports_graalvm_home_without_native_image" 1 "has no bin/native-image" \
    env GRAALVM_HOME="$bogus_home" "$setup_check"

# A GraalVM-shaped install with native-image but no static AWT archive: this is
# what upstream GraalVM looks like on macOS (oracle/graal#13272). It cannot build the
# native image, which used to be how macOS shipped and is not any more, so saying so is
# worth a line and is not a reason to call the machine misconfigured.
fake_graal="$empty_home/fake-graalvm"
mkdir -p "$fake_graal/bin" "$fake_graal/lib/static/darwin-aarch64"
printf '#!/bin/sh\nexit 0\n' > "$fake_graal/bin/native-image"
chmod +x "$fake_graal/bin/native-image"
if [[ "$(uname -s)" == "Darwin" ]]; then
    assert_run "setup_check_accepts_plain_graalvm_on_macos" 0 "libawt_lwawt.a" \
        env GRAALVM_HOME="$fake_graal" "$setup_check"

    # Adding the archive is what makes a NIK Full install able to build the native image.
    : > "$fake_graal/lib/static/darwin-aarch64/libawt_lwawt.a"
    assert_run "setup_check_accepts_nik_full_shaped_install" 0 "static AWT archive" \
        env GRAALVM_HOME="$fake_graal" "$setup_check"
fi

# What the macOS renderer actually cannot be built without: the patched Compose in the
# local Maven repository. An empty HOME is an empty repository, so the missing branch is
# reachable without touching the real one.
if [[ "$(uname -s)" == "Darwin" ]]; then
    # Exit zero, and that is the point: it blocks the renderer build and nothing else, so
    # a machine working on the Rust side is not a misconfigured one. rustup lives under
    # HOME, so its own directories are handed back or the run reports tools that are there.
    assert_run "setup_check_reports_missing_patched_compose" 0 "no patched Compose" \
        env -u GRAALVM_HOME HOME="$empty_home" \
            RUSTUP_HOME="${RUSTUP_HOME:-$real_home/.rustup}" \
            CARGO_HOME="${CARGO_HOME:-$real_home/.cargo}" \
            "$setup_check"

    # The found branch is built the same way, rather than read off whatever this machine has
    # published: a CI runner has never run build-compose.sh, and a developer who has is
    # testing their own ~/.m2, not the check. The version is read from where setup-check
    # reads it, so moving the pin does not leave this looking for the old one.
    patched_version="$(sed -n 's/^EXTENDED_AS="\([^"]*\)"$/\1/p' \
        "$repo_root/renderer/scripts/build-compose.sh" | head -1)"
    compose_home="$empty_home/compose-home"
    patched_compose="$compose_home/.m2/repository/org/jetbrains/compose/ui/ui-macosarm64/$patched_version"
    mkdir -p "$patched_compose"
    assert_run "setup_check_names_the_patched_compose_it_found" 0 "patched Compose: $patched_compose" \
        env -u GRAALVM_HOME HOME="$compose_home" \
            RUSTUP_HOME="${RUSTUP_HOME:-$real_home/.rustup}" \
            CARGO_HOME="${CARGO_HOME:-$real_home/.cargo}" \
            "$setup_check"
fi

printf '\n%d test(s), %d failure(s)\n' "$tests_run" "$tests_failed"
[[ $tests_failed -eq 0 ]]
