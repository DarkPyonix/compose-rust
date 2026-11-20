#!/usr/bin/env bash
# A Maven repository that answers "not found" for a moment fails a whole CI job: run
# 44692946847 lost the linux renderer test to "Could not find
# com.google.protobuf:protobuf-java:3.25.5" in the fork's Compose build, and a rerun passed.
# renderer/scripts/retry-resolution.sh runs a Gradle or Amper build again when, and only when,
# it failed resolving or downloading a dependency. This pins how it behaves, with a stub build
# in place of Gradle, and that every dependency-resolving build CI runs goes through it: the
# fork's Gradle publish (and the Windows skiko build) in build-compose.sh, and the Amper
# wasmJs builds in test.yml.
set -uo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
retry="$repo/renderer/scripts/retry-resolution.sh"
failures=0
check() { if [ "$1" -eq 0 ]; then echo "ok   - $2"; else echo "FAIL - $2"; failures=$((failures + 1)); fi; }

mkdir -p "$repo/.scratch"
work="$(mktemp -d "$repo/.scratch/dependency-download-retries-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# A stub build: fails the first $1 runs with the message $2, then succeeds. Counts its runs.
cat > "$work/stub-build" <<'SH'
#!/usr/bin/env bash
count_file="$STUB_DIR/count"
n=$(( $(cat "$count_file" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$count_file"
if [ "$n" -le "$1" ]; then
    echo "FAILURE: Build failed with an exception." >&2
    echo "$2" >&2
    exit 1
fi
echo "BUILD SUCCESSFUL"
SH
chmod +x "$work/stub-build"

run() { # fails_before_success message -> sets status and runs
    rm -f "$work/count"
    STUB_DIR="$work" DXC_RETRY_BACKOFF=0 DXC_RETRY_LOG="$work/retry.log" \
        "$retry" "$work/stub-build" "$1" "$2" >"$work/out" 2>&1
    status=$?
    runs=$(cat "$work/count" 2>/dev/null || echo 0)
}

test -x "$retry"; check $? "renderer/scripts/retry-resolution.sh exists and is executable"

run 1 "> Could not resolve all artifacts for configuration 'classpath'.
   > Could not find com.google.protobuf:protobuf-java:3.25.5."
[ "$status" -eq 0 ] && [ "$runs" -eq 2 ]
check $? "a build that fails once resolving a dependency is run again and passes (status $status, runs $runs)"

run 1 "Plugin [id: 'org.jetbrains.kotlin.jvm'] was not found in any of the following sources:"
[ "$status" -eq 0 ] && [ "$runs" -eq 2 ]
check $? "a plugin that could not be resolved is retried (status $status, runs $runs)"

run 1 "Could not GET 'https://repo.maven.apache.org/maven2/x.pom'. Received status code 502"
[ "$status" -eq 0 ] && [ "$runs" -eq 2 ]
check $? "a failed download is retried (status $status, runs $runs)"

run 1 "Unable to resolve dependency org.jetbrains.skiko:skiko:0.9"
[ "$status" -eq 0 ] && [ "$runs" -eq 2 ]
check $? "an Amper dependency that could not be resolved is retried (status $status, runs $runs)"

run 9 "> Could not find com.google.protobuf:protobuf-java:3.25.5."
[ "$status" -ne 0 ] && [ "$runs" -eq 3 ]
check $? "a resolution failure that persists stops after three attempts and fails (status $status, runs $runs)"

run 1 "e: file:///x/Main.kt:3:5 Unresolved reference 'foo'."
[ "$status" -ne 0 ] && [ "$runs" -eq 1 ]
check $? "a compile error is not retried (status $status, runs $runs)"

run 0 ""
[ "$status" -eq 0 ] && [ "$runs" -eq 1 ] && grep -q "BUILD SUCCESSFUL" "$work/out"
check $? "a passing build runs once and its output still reaches the log (status $status, runs $runs)"

# Wiring: every dependency-resolving build CI runs goes through the wrapper.
build_compose="$repo/renderer/scripts/build-compose.sh"
grep -Eq '^[[:space:]]*"\$RETRY"[[:space:]]+"\$publish"' "$build_compose"
check $? "build-compose.sh runs the fork's Gradle publish through the retry wrapper"
grep -Eq '^[[:space:]]*"\$RETRY"[[:space:]]+bash[[:space:]]+"\$skiko_script"' "$build_compose"
check $? "build-compose.sh runs the Windows skiko Gradle build through the retry wrapper"
! grep -Eq '^[[:space:]]*("\$publish"|bash[[:space:]]+"\$skiko_script")' "$build_compose"
check $? "build-compose.sh has no unwrapped Gradle invocation left"

test_yml="$repo/.github/workflows/test.yml"
grep -Eq 'run: \./scripts/retry-resolution\.sh \./kotlin task :web:buildWasmJsAppWasmJsDebug' "$test_yml"
check $? "test.yml builds the wasmJs app bundle through the retry wrapper"
grep -Eq 'run: \./scripts/retry-resolution\.sh \./kotlin build -p \$\{\{ matrix\.platform \}\}' "$test_yml"
check $? "test.yml runs the Amper build (wasmJs and the other platforms) through the retry wrapper"
grep -q 'DXC_RETRY_LOG: ${{ runner.temp }}/' "$test_yml"
check $? "test.yml keeps the retry log in the runner's temp directory"

if [ "$failures" -ne 0 ]; then echo "$failures check(s) failed"; exit 1; fi
echo "ok    transient dependency downloads are retried in the fork Compose build and the Amper wasmJs build"
