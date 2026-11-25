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

# Every shipped desktop platform uses the AWT-free Kotlin/Native renderer, so a machine
# without GRAALVM_HOME or Liberica NIK passes cleanly without requiring GraalVM or AWT.
assert_run "nfr14_setup_check_does_not_require_graalvm_or_awt" 0 "no GraalVM or AWT required" \
    env -u GRAALVM_HOME HOME="$empty_home" \
        RUSTUP_HOME="${RUSTUP_HOME:-$real_home/.rustup}" \
        CARGO_HOME="${CARGO_HOME:-$real_home/.cargo}" \
        "$setup_check"

# A GRAALVM_HOME that exists but has no native-image at all. Wrong on every platform:
# the variable was set, so someone meant to point at a toolchain.
bogus_home="$empty_home/bogus-jdk"
mkdir -p "$bogus_home/bin"
assert_run "setup_check_reports_graalvm_home_without_native_image" 1 "has no bin/native-image" \
    env GRAALVM_HOME="$bogus_home" "$setup_check"

# An optional GRAALVM_HOME with native-image is accepted without checking AWT archives.
fake_graal="$empty_home/fake-graalvm"
mkdir -p "$fake_graal/bin"
printf '#!/bin/sh\nexit 0\n' > "$fake_graal/bin/native-image"
chmod +x "$fake_graal/bin/native-image"
assert_run "setup_check_accepts_optional_graalvm_home" 0 "optional native-image: $fake_graal/bin/native-image" \
    env GRAALVM_HOME="$fake_graal" "$setup_check"

# Stale platform/toolchain claims must stay gone.
tests_run=$((tests_run + 1))
if grep -qE 'has not been run to a working window yet|still how Windows is built|libawt_lwawt\.a' "$setup_check"; then
    tests_failed=$((tests_failed + 1))
    printf 'FAIL nfr14_setup_check_has_no_stale_awt_or_linux_claims\n' >&2
else
    printf 'ok   nfr14_setup_check_has_no_stale_awt_or_linux_claims\n'
fi

# What the Kotlin/Native desktop renderer actually cannot be built without: the patched
# Compose in the local Maven repository. An empty HOME is an empty repository, so the
# missing branch is reachable without touching the real one.
compose_target=""
case "$(uname -s)" in
    Darwin)
        case "$(uname -m)" in
            arm64|aarch64) compose_target="ui-macosarm64" ;;
            *) compose_target="ui-macosx64" ;;
        esac
        ;;
    Linux)
        case "$(uname -m)" in
            aarch64|arm64) compose_target="ui-linuxarm64" ;;
            *) compose_target="ui-linuxx64" ;;
        esac
        ;;
    MINGW*|MSYS*|CYGWIN*) compose_target="ui-mingwx64" ;;
esac
if [[ -n "$compose_target" ]]; then
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
    patched_version="$(sed -n 's/^COMPOSE_AS="\([^"]*\)"$/\1/p' \
        "$repo_root/renderer/scripts/build-compose.sh" | head -1)"
    compose_home="$empty_home/compose-home"
    patched_compose="$compose_home/.m2/repository/org/thisisthepy/compose/ui/$compose_target/$patched_version"
    mkdir -p "$patched_compose"
    assert_run "setup_check_names_the_patched_compose_it_found" 0 "patched Compose: $patched_compose" \
        env -u GRAALVM_HOME HOME="$compose_home" \
            RUSTUP_HOME="${RUSTUP_HOME:-$real_home/.rustup}" \
            CARGO_HOME="${CARGO_HOME:-$real_home/.cargo}" \
            "$setup_check"
fi

printf '\n%d test(s), %d failure(s)\n' "$tests_run" "$tests_failed"
[[ $tests_failed -eq 0 ]]
