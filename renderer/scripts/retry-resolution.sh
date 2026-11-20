#!/usr/bin/env bash
# Runs a Gradle or Amper build, and runs it again when it failed resolving or downloading a
# dependency. Maven Central and the Gradle plugin portal sometimes answer "not found" or time
# out for a moment: run 44692946847 lost a whole job to "Could not find
# com.google.protobuf:protobuf-java:3.25.5", and a rerun passed. Any other failure, a compile
# error or a failing test, fails at once, so a real break is never run three times.
#
# Usage: DXC_RETRY_LOG=<file> retry-resolution.sh <command> [args...]
#
# DXC_RETRY_LOG     where the last attempt's output is kept to read the failure from (required,
#                   so the caller decides where it goes).
# DXC_RETRY_ATTEMPTS how many times to run in all (default 3).
# DXC_RETRY_BACKOFF  seconds to wait after the first failure; the wait grows by as much each
#                   attempt (default 15: 15s, then 30s), as the fork desktop-module workflows do.
#
# The command's stdout and stderr both go to stdout, as they are read in a CI log.
set -uo pipefail

[[ $# -gt 0 ]] || { echo "usage: DXC_RETRY_LOG=<file> $0 <command> [args...]" >&2; exit 2; }
log="${DXC_RETRY_LOG:?DXC_RETRY_LOG names the file the build output is kept in}"
attempts="${DXC_RETRY_ATTEMPTS:-3}"
backoff="${DXC_RETRY_BACKOFF:-15}"

# What Gradle and Amper print when a dependency or plugin could not be fetched.
pattern='Could not resolve all (artifacts|files|dependencies|task dependencies)|Could not resolve [^ ]+:[^ ]+|Could not find [^ ]+:[^ ]+:|Could not (GET|HEAD|download) |Plugin \[id: .*\] was not found|Unable to (resolve|download)|Read timed out|Connect(ion)? timed out|Connection reset|Remote host terminated the handshake|Received status code (429|5[0-9][0-9])'

mkdir -p "$(dirname "$log")"
attempt=1
while :; do
    "$@" 2>&1 | tee "$log"
    status=${PIPESTATUS[0]}
    [[ $status -eq 0 ]] && exit 0
    if ! grep -Eq "$pattern" "$log"; then
        exit "$status"
    fi
    if [[ $attempt -ge $attempts ]]; then
        echo "==> $1 still failed resolving dependencies after $attempt attempts" >&2
        exit "$status"
    fi
    wait=$((attempt * backoff))
    echo "==> $1 failed resolving dependencies on attempt $attempt; retrying in ${wait}s" >&2
    sleep "$wait"
    attempt=$((attempt + 1))
done
