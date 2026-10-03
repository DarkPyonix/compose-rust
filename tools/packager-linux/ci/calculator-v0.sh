#!/usr/bin/env bash
# Stages a calculator whose executable and renderer belong together, for the packaging
# workflow to wrap and start. x86_64 only.
#
#     tools/packager-linux/ci/calculator-v0.sh <work dir>
#
# leaves <work dir>/calculator/ (the executable, and the renderer in lib/ beside it) and
# <work dir>/calculator-linux-x64.tar.gz (the same directory as a release archive).
#
# Why this and not a published sample: the sample-v0.1.1 Linux archives do not start.
# Their executables carry no rpath, the x86_64 renderer asks the executable for a host
# function it does not export (a renderer and host from different commits), and the
# renderer sits beside the executable rather than in lib/, where AWT looks for its
# companions. So the workflow builds the calculator at the v0.0.0 tag of
# DarkPyonix/dioxus-compose against that tag's own released renderer. That is a Rust
# build only; the renderer is the published artifact, checked by its digest.

set -euo pipefail

work="$1"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
renderer_url=https://github.com/DarkPyonix/dioxus-compose/releases/download/v0.0.0/dioxus-compose-renderer-v0.0.0-linux-x64.tar.gz
renderer_sha256=5fe6ba31e359753a8e3c3a743f2f8ecadac409fda2634ba3b569e00f27ec19aa

[[ "$(uname -m)" == x86_64 ]] || { echo "error: the v0.0.0 renderer was published for x86_64 only" >&2; exit 1; }
mkdir -p "$work"
work="$(cd "$work" && pwd)"

if [[ ! -d "$work/dioxus-compose" ]]; then
    git clone --quiet --depth 1 --branch v0.0.0 https://github.com/DarkPyonix/dioxus-compose "$work/dioxus-compose"
fi
mkdir -p "$work/renderer"
curl --fail --location --silent --show-error --retry 3 -o "$work/renderer.tar.gz" "$renderer_url"
echo "$renderer_sha256  $work/renderer.tar.gz" | sha256sum -c -
tar -xzf "$work/renderer.tar.gz" -C "$work/renderer"

(cd "$work/dioxus-compose" &&
    DIOXUS_COMPOSE_RENDERER_DIR="$work/renderer" cargo build --quiet --release -p sample-calculator)

stage="$work/calculator/calculator"
rm -rf "$work/calculator"
mkdir -p "$stage/lib"
cp "$work/dioxus-compose/target/release/sample-calculator" "$stage/"
cp -a "$work/renderer/lib/." "$stage/lib/"
"$repo_root/scripts/bundle-renderer.sh" "$stage/sample-calculator" lib
# bundle-renderer.sh only sets the rpath when it had a path to rewrite.
patchelf --set-rpath '$ORIGIN/lib' "$stage/sample-calculator"
readelf -d "$stage/sample-calculator" | grep -E '\((NEEDED|RUNPATH|RPATH)\)'
tar -czf "$work/calculator-linux-x64.tar.gz" -C "$work/calculator" calculator
sha256sum "$work/calculator-linux-x64.tar.gz"
