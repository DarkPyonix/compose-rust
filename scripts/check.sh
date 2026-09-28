#!/usr/bin/env bash
# Usage: ./scripts/check.sh [--full] [--no-kotlin]
#
# Runs the repository's quality gates: the Rust workspace, then the Kotlin renderer
# project. The default uses Criterion's quick mode for local and CI presubmit checks;
# --full runs the full benchmark sample.
#
# The Kotlin gate runs by default. Skip it with --no-kotlin or DXC_SKIP_KOTLIN=1 when
# the Kotlin Toolchain has not been downloaded yet (the wrapper fetches it on first use).

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

full=0
skip_kotlin="${DXC_SKIP_KOTLIN:-0}"

for arg in "$@"; do
    case "$arg" in
        --full) full=1 ;;
        --no-kotlin) skip_kotlin=1 ;;
        *) echo "usage: $0 [--full] [--no-kotlin]" >&2; exit 2 ;;
    esac
done

if [[ $full -eq 1 ]]; then
    bench_args=(--noplot)
    unset DXC_BENCH_QUICK
else
    bench_args=(--quick --noplot)
    export DXC_BENCH_QUICK=1
fi

cargo fmt --all -- --check
cargo clippy --workspace -- -D warnings
cargo test --workspace
# The renderer is a default feature, so the run above never builds the crate the way a
# headless or documentation build gets it. That build has to compile, and the tests that
# assert it does not quietly pretend to have a renderer only exist in it.
cargo test -p dioxus-compose --no-default-features
# The generated JNI shims are behind cfg(target_os = "android"), so nothing above compiles
# them. The staleness test proves the checked-in file is what the generator writes; this
# proves the generator writes something that builds. Skipped where the target is missing,
# because adding it is a download and this gate is meant to run anywhere.
if rustup target list --installed | grep -qx aarch64-linux-android; then
    cargo clippy -p dioxus-compose --target aarch64-linux-android -- -D warnings
else
    echo "skipping the Android target (rustup target add aarch64-linux-android)"
fi
# The same for the browser's shims, which are behind cfg(target_family = "wasm"). The
# renderer feature is off because a browser links no renderer: the generated web shims
# install the renderer API from the entry point the page calls.
if rustup target list --installed | grep -qx wasm32-unknown-unknown; then
    cargo clippy -p dioxus-compose --no-default-features \
        --target wasm32-unknown-unknown -- -D warnings
else
    echo "skipping the wasm target (rustup target add wasm32-unknown-unknown)"
fi
# What docs.rs does: the feature is on and there is no network to fetch a renderer with.
# The documentation still has to build.
DOCS_RS=1 cargo check -p dioxus-compose --all-features
# --benches restricts the run to Criterion bench targets. Without it cargo also runs the
# lib's default test harness in bench mode, and that harness rejects Criterion's flags.
cargo bench --workspace --benches -- "${bench_args[@]}"

if [[ "$skip_kotlin" != "0" ]]; then
    echo "skipping the Kotlin gate (--no-kotlin or DXC_SKIP_KOTLIN)"
    exit 0
fi

renderer_gate="$repo_root/dioxus-compose-renderer/scripts/gate-platforms.sh"

cd "$repo_root/dioxus-compose-renderer"
# Which platforms this machine can build, and which it can run tests for. Named rather than
# left to the default, because three of them need something the machine may not have: the
# X11 development headers that the Linux window's cinterop compiles against, a device or an
# emulator for Android's instrumented tests, and a test task that an iOS device target does
# not have at all. The same reasoning as the Android and wasm targets above: a gate that
# demands an install nobody needs is a gate that stops being run.
build_platforms=()
while read -r platform; do
    build_platforms+=(--platform "$platform")
done < <("$renderer_gate" build)
test_platforms=()
while read -r platform; do
    test_platforms+=(--platform "$platform")
done < <("$renderer_gate" test)

echo "building for: ${build_platforms[*]//--platform /}"
echo "testing on:   ${test_platforms[*]//--platform /}"
./kotlin build "${build_platforms[@]}"
./kotlin test "${test_platforms[@]}"

# The design systems are their own Amper project, so they have their own build and their
# own tests, and nothing here ran either of them. Ten test files, including every rule the
# Liquid Glass material is checked by, went unrun by the gate that is supposed to be the
# thing you can believe. Same platform question, asked of that project.
design_systems="$repo_root/dioxus-design-systems"
design_build=()
while read -r platform; do
    design_build+=(--platform "$platform")
done < <("$renderer_gate" build "$design_systems")
design_test=()
while read -r platform; do
    design_test+=(--platform "$platform")
done < <("$renderer_gate" test "$design_systems")

cd "$design_systems"
echo "design systems, building for: ${design_build[*]//--platform /}"
echo "design systems, testing on:   ${design_test[*]//--platform /}"
./kotlin build "${design_build[@]}"
./kotlin test "${design_test[@]}"
