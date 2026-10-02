#!/usr/bin/env bash
# Usage: ./scripts/tests/renderer-toolchain.test.sh
#
# Exercises dioxus-compose-renderer/desktop/scripts/env.sh, the file every native build
# script sources before it does anything else. Its job is to refuse a toolchain that
# cannot link Compose Desktop, and to say what to install instead, rather than letting the
# build discover it tens of minutes later in an unreadable linker failure.
#
# No native image is built here. Each case points GRAALVM_HOME at a directory made under a
# temporary folder, so the whole file costs milliseconds and needs no toolchain installed.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scripts_dir="$repo_root/dioxus-compose-renderer/desktop/scripts"
env_sh="$scripts_dir/env.sh"

if [[ "$(uname -s)" != "Darwin" ]]; then
    # env.sh refuses to run anywhere else, and says so as its first act. There is nothing
    # left for the toolchain cases to assert once that branch has been taken.
    output="$(bash -c 'set -euo pipefail; source "$0"' "$env_sh" 2>&1)"
    status=$?
    if [[ $status -eq 0 || "$output" != *"only macOS is scripted"* ]]; then
        printf 'FAIL pr8_env_refuses_a_non_macos_host: exit %s\n%s\n' "$status" "$output" >&2
        exit 1
    fi
    printf 'ok   pr8_env_refuses_a_non_macos_host\n'
    printf '\nskipping the toolchain cases: they describe a macOS install (this is %s)\n' "$(uname -s)"
    exit 0
fi

tests_run=0
tests_failed=0

# assert_env <name> <expected status> <expected substring|-> [NAME=VALUE | -u NAME ...]
#
# Sources env.sh the way build-native.sh does, under the same shell options, with the given
# environment. `source` is the point: env.sh sets variables for its caller and is never run
# as a program, so running it as one would not exercise what the build scripts get.
assert_env() {
    local name="$1" expected_status="$2" expected_substring="$3"
    shift 3
    tests_run=$((tests_run + 1))
    local output status
    output="$(env "$@" bash -c 'set -euo pipefail; source "$0"' "$env_sh" 2>&1)"
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
    # A refusal that does not say what to install leaves the reader exactly where the
    # unreadable linker failure would have.
    if [[ "$expected_status" != "0" && "$output" != *"bell-sw.com"* ]]; then
        tests_failed=$((tests_failed + 1))
        printf 'FAIL %s: the refusal named no way to fix it\n%s\n' "$name" "$output" >&2
        return
    fi
    printf 'ok   %s\n' "$name"
}

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

empty_home="$work_dir/home"
mkdir -p "$empty_home/Library/Java/JavaVirtualMachines"

# Nothing set and nothing installed. The default location is globbed under $HOME, so an
# empty one stands in for a machine that never installed NIK.
assert_env "pr8_env_rejects_a_machine_with_no_nik" 1 "no Liberica NIK 25 Full found" \
    -u GRAALVM_HOME "HOME=$empty_home"

assert_env "pr8_env_rejects_a_graalvm_home_that_does_not_exist" 1 "does not exist" \
    "HOME=$empty_home" "GRAALVM_HOME=$work_dir/absent"

# Set, existing, and not a native-image toolchain at all.
mkdir -p "$work_dir/bogus-jdk/bin"
assert_env "pr8_env_rejects_a_graalvm_home_without_native_image" 1 "no executable bin/native-image" \
    "HOME=$empty_home" "GRAALVM_HOME=$work_dir/bogus-jdk"

# Upstream GraalVM on macOS: native-image is there, the static AWT archive is not, because
# Darwin builds skip AWT entirely (oracle/graal#13272). Compose Desktop cannot be linked
# with it, and the missing archive is the only signal available before the link step.
fake_nik="$work_dir/fake-nik"
mkdir -p "$fake_nik/bin" "$fake_nik/lib/static/darwin-aarch64"
printf '#!/bin/sh\nexit 0\n' > "$fake_nik/bin/native-image"
chmod +x "$fake_nik/bin/native-image"
assert_env "pr8_env_rejects_a_toolchain_without_the_static_awt_archive" 1 "libawt_lwawt.a" \
    "HOME=$empty_home" "GRAALVM_HOME=$fake_nik"

# The positive control. Without it every assertion above would still pass if env.sh
# refused unconditionally, which would be a build that can never run.
: > "$fake_nik/lib/static/darwin-aarch64/libawt_lwawt.a"
assert_env "pr8_env_accepts_a_nik_full_shaped_install" 0 "-" \
    "HOME=$empty_home" "GRAALVM_HOME=$fake_nik"

# The refusal has to come before the build spends anything. Both scripts source env.sh on
# their first executable line; anything that compiles, downloads or runs a JVM must be
# after it.
for script in build-native.sh smoke-test.sh; do
    tests_run=$((tests_run + 1))
    first_statement="$(grep -n -v -e '^[[:space:]]*#' -e '^[[:space:]]*$' -e '^set ' "$scripts_dir/$script" | head -1)"
    if [[ "$first_statement" != *'source "$(dirname "$0")/env.sh"'* ]]; then
        tests_failed=$((tests_failed + 1))
        printf 'FAIL pr8_%s_validates_the_toolchain_first: first statement is %s\n' \
            "${script%.sh}" "$first_statement" >&2
        printf '     env.sh is what turns a wrong toolchain into an immediate message, so\n' >&2
        printf '     nothing may run ahead of it.\n' >&2
    else
        printf 'ok   pr8_%s_validates_the_toolchain_first\n' "${script%.sh}"
    fi
done

printf '\n%d test(s), %d failure(s)\n' "$tests_run" "$tests_failed"
[[ $tests_failed -eq 0 ]]
