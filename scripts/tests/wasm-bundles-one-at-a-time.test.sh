#!/usr/bin/env bash
# Usage: ./scripts/tests/wasm-bundles-one-at-a-time.test.sh
#
# The Kotlin toolchain unpacks skiko's wasm runtime into one fixed directory,
# renderer/build/temp/skiko-js-wasm-runtime, for every wasm bundle task, deleting and
# recreating each file there. A `kotlin build -p wasmJs` on a cold tree runs the web app
# bundle and the web test app bundle in parallel, and one of them fails with
# FileAlreadyExistsException (#494). CI starts from a cold tree every time, so a wasm
# build there has to make the app bundle on its own before anything that also makes the
# test bundle, and the page build has to make the app bundle alone.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/test.yml"
pages="$repo_root/scripts/build-sample-pages.sh"
app_task=':web:buildWasmJsAppWasmJsDebug'

failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "wasm bundles one at a time"

# The renderer job's steps, in order: the line of the app bundle step has to come before
# the shared build step, and that step has to be limited to the wasmJs leg.
app_line="$(grep -nF "run: ./kotlin task $app_task" "$workflow" | head -1 | cut -d: -f1)"
build_line="$(grep -nF 'run: ./kotlin build -p ${{ matrix.platform }}' "$workflow" |
    head -1 | cut -d: -f1)"
if [[ -z "$app_line" ]]; then
    fail "test.yml no longer builds $app_task on its own before the wasmJs build"
elif [[ -z "$build_line" ]]; then
    fail "test.yml no longer has the renderer build step this test orders against"
elif ((app_line > build_line)); then
    fail "test.yml builds $app_task after the build that also makes the test bundle"
else
    step="$(sed -n "$((app_line - 12)),${app_line}p" "$workflow")"
    grep -qF "if: matrix.platform == 'wasmJs'" <<<"$step" ||
        fail "the step that builds $app_task alone is not limited to the wasmJs leg"
fi

# The page build needs the app bundle only, so it never asks for both.
if grep -qE '\./kotlin build( |$)' "$pages"; then
    fail "build-sample-pages.sh runs kotlin build, which makes both bundles at once"
fi
grep -qF "./kotlin task $app_task" "$pages" ||
    fail "build-sample-pages.sh does not build $app_task"

if ((failures > 0)); then
    echo "$failures failure(s)" >&2
    exit 1
fi
echo "  ok"
