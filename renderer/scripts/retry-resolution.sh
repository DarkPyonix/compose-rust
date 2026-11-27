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
# DXC_RETRY_429_ATTEMPTS, DXC_RETRY_429_BACKOFF
#                   the same when the repository answered 429 Too Many Requests, a rate limit
#                   that lasts minutes: 4 runs in all, waiting 60s, 120s, then 240s.
#
# The command's stdout and stderr both go to stdout, as they are read in a CI log.
set -uo pipefail

[[ $# -gt 0 ]] || { echo "usage: DXC_RETRY_LOG=<file> $0 <command> [args...]" >&2; exit 2; }
log="${DXC_RETRY_LOG:?DXC_RETRY_LOG names the file the build output is kept in}"
attempts="${DXC_RETRY_ATTEMPTS:-3}"
backoff="${DXC_RETRY_BACKOFF:-15}"
attempts_429="${DXC_RETRY_429_ATTEMPTS:-4}"
backoff_429="${DXC_RETRY_429_BACKOFF:-60}"

# What Gradle and Amper print when a dependency or plugin could not be fetched.
pattern='Could not resolve all (artifacts|files|dependencies|task dependencies)|Could not resolve [^ ]+:[^ ]+|Could not find [^ ]+:[^ ]+:|Could not (GET|HEAD|download) |Plugin \[id: .*\] was not found|Unable to (resolve|download)|Read timed out|Connect(ion)? timed out|Connection reset|Remote host terminated the handshake|Received status code (429|5[0-9][0-9])|Too Many Requests|returned error: (429|5[0-9][0-9])'
# Amper says it as "Expected: 200, actual: 429", and curl -f (the skiko build's downloads) as
# "The requested URL returned error: 429".
rate_limited='status code 429|Too Many Requests|actual: 429|returned error: 429'

mkdir -p "$(dirname "$log")"
attempt=1
while :; do
    "$@" 2>&1 | tee "$log"
    status=${PIPESTATUS[0]}
    [[ $status -eq 0 ]] && exit 0
    if ! grep -Eq "$pattern" "$log"; then
        exit "$status"
    fi
    if grep -Eq "$rate_limited" "$log"; then
        limit=$attempts_429 wait=$((backoff_429 * (1 << (attempt - 1)))) why="rate limited (429)"
    else
        limit=$attempts wait=$((attempt * backoff)) why="failed resolving dependencies"
    fi
    if [[ $attempt -ge $limit ]]; then
        echo "==> $1 still $why after $attempt attempts" >&2
        exit "$status"
    fi
    echo "==> $1 $why on attempt $attempt; retrying in ${wait}s" >&2
    sleep "$wait"
    attempt=$((attempt + 1))
done
