#!/usr/bin/env bash
# Usage: ./scripts/setup-check.sh [--quiet]
#
# Preflight check for a development machine. Verifies every tool the build and
# scripts/check.sh need, and prints what is missing together with how to fix it.
# Exits non-zero if anything required is missing.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
renderer_dir="$repo_root/renderer"

quiet=0
case "${1:-}" in
    "") ;;
    --quiet) quiet=1 ;;
    *) echo "usage: $0 [--quiet]" >&2; exit 2 ;;
esac

failures=0

ok()   { [[ $quiet -eq 1 ]] || printf 'ok    %s\n' "$1"; }
warn() { printf 'warn  %s\n' "$1" >&2; }
fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
    shift
    local line
    for line in "$@"; do printf '        %s\n' "$line" >&2; done
}

# --- Rust -------------------------------------------------------------------
if command -v cargo >/dev/null 2>&1; then
    ok "cargo: $(cargo --version 2>/dev/null)"

    if cargo fmt --version >/dev/null 2>&1; then
        ok "rustfmt: $(cargo fmt --version 2>/dev/null)"
    else
        fail "rustfmt component missing (scripts/check.sh runs 'cargo fmt --all -- --check')" \
             "fix: rustup component add rustfmt"
    fi

    if cargo clippy --version >/dev/null 2>&1; then
        ok "clippy: $(cargo clippy --version 2>/dev/null)"
    else
        fail "clippy component missing (scripts/check.sh runs 'cargo clippy --workspace -- -D warnings')" \
             "fix: rustup component add clippy"
    fi
else
    fail "cargo not found on PATH" \
         "fix: install Rust from https://rustup.rs, then: rustup component add rustfmt clippy" \
         "note: rustup installs into ~/.cargo/bin; make sure it is on PATH"
fi

# --- Build directory --------------------------------------------------------
# This checkout has to build into its own target/. Two checkouts sharing one build
# directory run each other's binaries: cargo leaves the package path out of the unit hash
# for a path package, so identical sources in two places are a single cache entry, and
# everything fixed at compile time comes from whichever one compiled first.
build_override=""
if [[ -f "$repo_root/.cargo/config.toml" ]] &&
   grep -qE '^[[:space:]]*target[-_]dir[[:space:]]*=' "$repo_root/.cargo/config.toml"; then
    build_override="$repo_root/.cargo/config.toml"
fi
if [[ -n "${CARGO_TARGET_DIR:-}" ]]; then
    build_override="${build_override:+$build_override and }CARGO_TARGET_DIR=$CARGO_TARGET_DIR"
fi
if [[ -n "$build_override" ]]; then
    fail "this checkout builds somewhere other than its own target/ ($build_override)" \
         "Two checkouts sharing one build directory run each other's binaries." \
         "fix: ./scripts/setup-worktrees.sh --all, and unset CARGO_TARGET_DIR"
else
    ok "build directory: $repo_root/target, this checkout's own"
fi

# --- Platform ---------------------------------------------------------------
uname_s="$(uname -s)"
uname_m="$(uname -m)"
compose_target=""
case "$uname_s" in
    Darwin)
        ok "platform macOS: the AWT-free Kotlin/Native desktop renderer is built and verified here"
        case "$uname_m" in
            arm64|aarch64) compose_target="ui-macosarm64" ;;
            *) compose_target="ui-macosx64" ;;
        esac
        ;;
    Linux)
        ok "platform Linux: the AWT-free Kotlin/Native desktop renderer is built and verified here"
        case "$uname_m" in
            aarch64|arm64) compose_target="ui-linuxarm64" ;;
            *) compose_target="ui-linuxx64" ;;
        esac
        ;;
    MINGW*|MSYS*|CYGWIN*)
        ok "platform Windows: the AWT-free Kotlin/Native desktop renderer is built and verified here"
        compose_target="ui-mingwx64"
        ;;
    *)
        warn "platform $uname_s: no desktop renderer build is scripted here."
        warn "      The Rust workspace and the JVM dev shell (./kotlin run -m desktop) work."
        ;;
esac

# --- Optional legacy native-image toolchain ---------------------------------
# Every shipped desktop platform (macOS, Linux, and Windows) uses the AWT-free
# Kotlin/Native static renderer, which links no AWT and carries no Java runtime.
# Neither the Rust development loop nor any shipped desktop renderer needs GraalVM
# native-image. If GRAALVM_HOME is explicitly set, check that it points at a real
# native-image binary so a broken override is caught early.
graalvm_home="${GRAALVM_HOME:-}"
if [[ -n "$graalvm_home" ]]; then
    if [[ ! -x "$graalvm_home/bin/native-image" ]]; then
        fail "GRAALVM_HOME=$graalvm_home has no bin/native-image" \
             "GRAALVM_HOME is optional (the shipped desktop renderer is Kotlin/Native)," \
             "but when set it must point at a JDK with bin/native-image." \
             "fix: unset GRAALVM_HOME, or point it at a valid GraalVM installation"
    else
        ok "optional native-image: $graalvm_home/bin/native-image"
    fi
else
    ok "desktop renderer path: Kotlin/Native (no GraalVM or AWT required)"
fi

# --- The patched Compose ----------------------------------------------------
# What the Kotlin/Native desktop renderer cannot be built without. It resolves Compose
# from the local Maven repository, built from the fork commit pinned in
# renderer/scripts/build-compose.sh. The version is read from that script so there is
# one place to change it.
if [[ -n "$compose_target" ]]; then
    compose_build_script="$renderer_dir/scripts/build-compose.sh"
    patched_version="$(sed -n 's/^COMPOSE_AS="\([^"]*\)"$/\1/p' "$compose_build_script" 2>/dev/null | head -1)"
    if [[ -z "$patched_version" ]]; then
        fail "cannot tell which Compose version $compose_build_script publishes" \
             "It is read from the COMPOSE_AS assignment there. Either the script is" \
             "missing or that line was renamed, and this check has nothing to look for."
    else
        patched_compose="$HOME/.m2/repository/org/thisisthepy/compose/ui/$compose_target/$patched_version"
        if [[ -d "$patched_compose" ]]; then
            ok "patched Compose: $patched_compose"
        else
            warn "no patched Compose at $patched_compose"
            warn "      The Kotlin/Native desktop renderer resolves Compose from the local Maven"
            warn "      repository, so its build cannot start until this is published. Everything else works."
            warn "      fix: $compose_build_script"
        fi
    fi
fi

# --- Xcode command line tools ----------------------------------------------
if [[ "$uname_s" == "Darwin" ]]; then
    if xcode-select -p >/dev/null 2>&1 && command -v cc >/dev/null 2>&1; then
        ok "Xcode command line tools: $(xcode-select -p 2>/dev/null)"
    else
        fail "Xcode command line tools not found (needed for cc, ld and the AppKit headers)" \
             "fix: xcode-select --install"
    fi
fi

# --- Kotlin Toolchain wrapper ----------------------------------------------
kotlin_wrapper="$renderer_dir/kotlin"
if [[ ! -f "$kotlin_wrapper" ]]; then
    fail "missing $kotlin_wrapper" \
         "The wrapper is checked in; re-clone or restore it from git."
elif [[ ! -x "$kotlin_wrapper" ]]; then
    fail "$kotlin_wrapper is not executable" \
         "fix: chmod +x $kotlin_wrapper"
else
    ok "Kotlin Toolchain wrapper: $kotlin_wrapper (self-bootstrapping, no separate install)"
fi

# --- Result -----------------------------------------------------------------
if [[ $failures -gt 0 ]]; then
    printf '\n%d check(s) failed.\n' "$failures" >&2
    exit 1
fi
[[ $quiet -eq 1 ]] || echo "All checks passed."
