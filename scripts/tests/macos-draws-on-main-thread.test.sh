#!/usr/bin/env bash
# The macOS renderer draws on the process main thread (8MB stack). A drawing thread the
# JDK or pthread creates gets the 512KB secondary-thread default, which overflowed in Skia
# RenderNode recording when a renderer still drew on AWT-EventQueue-0.
#
# This fails when the entry point stops requiring the main thread, creates its own thread
# on macOS, or the macOS image build links the toolkit again.
set -uo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
status=0
fail() { echo "FAIL: $1"; status=1; }
entry=renderer/desktop/c/renderer_entry.c
build=renderer/desktop/scripts/build-native.sh

grep -q 'pthread_main_np()' "$entry" || fail "$entry no longer checks pthread_main_np"
grep -q 'RUN_NOT_MAIN_THREAD' "$entry" || fail "$entry no longer refuses to run off the main thread"
if grep -vE '^[[:space:]]*(//|\*|/\*)' "$entry" | grep -q 'pthread_create'; then
    fail "$entry creates a pthread, which would get a small default stack"
fi
code="$(grep -vE '^[[:space:]]*#' "$build")"
grep -q 'skiko-awt-runtime-' <<< "$code" || fail "$build no longer filters the toolkit runtime out"
grep -q 'libawt_lwawt\|libjawt' <<< "$code" && fail "$build links the toolkit, whose event thread has a 512KB stack"
exit $status
