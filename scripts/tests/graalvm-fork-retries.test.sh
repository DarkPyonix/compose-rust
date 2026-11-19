#!/usr/bin/env bash
# Verifies that both workflows that build the Compose fork's desktop modules configure
# Gradle repository transport retries/timeouts and retry build-compose.sh on transient
# plugin/dependency resolution failures.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

for wf in \
    "$ROOT/.github/workflows/test-graalvm-renderer.yml" \
    "$ROOT/.github/workflows/fork-desktop-modules.yml"
do
    name="$(basename "$wf")"
    grep -q 'systemProp.org.gradle.internal.repository.max.retries=' "$wf" || { echo "FAIL: $name missing Gradle repository max.retries"; exit 1; }
    grep -q 'systemProp.org.gradle.internal.repository.initial.backoff=' "$wf" || { echo "FAIL: $name missing Gradle repository initial.backoff"; exit 1; }
    grep -q 'systemProp.org.gradle.internal.http.connectionTimeout=' "$wf" || { echo "FAIL: $name missing Gradle HTTP connectionTimeout"; exit 1; }
    grep -q 'systemProp.org.gradle.internal.http.socketTimeout=' "$wf" || { echo "FAIL: $name missing Gradle HTTP socketTimeout"; exit 1; }
    grep -q 'for attempt in 1 2 3; do' "$wf" || { echo "FAIL: $name missing build-compose.sh retry loop"; exit 1; }
done

echo "ok    both fork desktop-module workflows configure Gradle transport retries and build-compose.sh retry loop"
