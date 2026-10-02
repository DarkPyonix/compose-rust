#!/usr/bin/env bash
# Builds the Host and serves the page.
# Usage: ./serve.sh [--port N] [--no-host] [--debug] [example]
#
# Open http://127.0.0.1:<port>/ and the M0 screen is drawn by the Rust Host: the page
# compiles the Host's module, the renderer's module is instantiated and creates the one
# linear memory, and the renderer's `main` instantiates the Host on it.
#
# `--no-host` leaves the Host's module out, which is how the renderer is worked on without
# the other side being built: the page says so on the console and draws the scripted
# development host instead.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
module_dir="$(cd "$script_dir/.." && pwd)"
renderer_root="$(cd "$module_dir/.." && pwd)"

port=8080
with_host=1
build_args=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --port) port="$2"; shift 2 ;;
        --no-host) with_host=0; shift ;;
        *) build_args+=("$1"); shift ;;
    esac
done

module="$module_dir/resources/dioxus_compose_host.wasm"
if [[ $with_host -eq 1 ]]; then
    "$script_dir/build-host.sh" ${build_args[@]+"${build_args[@]}"}
else
    # Removed rather than skipped: the page fetches whatever is beside it, so a module left
    # over from an earlier run would be served and the page would not be the one asked for.
    rm -f "$module"
    echo "serving without a Host; the page will draw its development host"
fi

echo "serving on http://127.0.0.1:$port/"
cd "$renderer_root"
exec ./kotlin run -p wasmJs --port "$port" --no-open-browser
