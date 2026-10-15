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
case "$uname_s" in
    Darwin) ok "platform macOS: the renderer is built and verified here" ;;
    Linux)
        warn "platform Linux: the renderer is scripted (desktop/scripts/build-native-linux.sh)"
        warn "      but has not been run to a working window yet. CI builds it on each release."
        ;;
    *)
        warn "platform $uname_s: no renderer build is scripted here."
        warn "      The Rust workspace and the JVM dev shell (./kotlin run -m desktop) work."
        ;;
esac

# --- The native-image toolchain ---------------------------------------------
# Which toolchain is correct depends on the platform. The native image needs Liberica NIK
# Full on macOS, because upstream GraalVM ships no AWT on Darwin (oracle/graal#13272) and
# AWT is what the image draws through. Everywhere else upstream GraalVM is the right one
# and NIK is not required, so demanding it on Linux failed a setup that was in fact
# correct.
#
# None of this is required to build the renderer this platform ships. That one is
# Kotlin/Native, it links no AWT and carries no Java runtime, and what it needs instead is
# checked below. The native image is still how Windows is built and still what
# smoke-test.sh links against, so an installation that cannot produce one is worth a line.
graalvm_home="${GRAALVM_HOME:-}"
if [[ -z "$graalvm_home" && "$uname_s" == "Darwin" ]]; then
    # Same discovery order as renderer/desktop/scripts/env.sh.
    for candidate in "$HOME"/Library/Java/JavaVirtualMachines/bellsoft-liberica-vm-full-openjdk25*/Contents/Home; do
        [[ -x "$candidate/bin/native-image" ]] && graalvm_home="$candidate"
    done
fi

if [[ "$uname_s" == "Darwin" ]]; then
    nik_install_hint=(
        "Only the native image needs this. The renderer macOS ships is Kotlin/Native and"
        "does not, so a machine without it builds and runs everything but that image."
        "fix: install Liberica NIK 25 Full (Java 25, 'Full' variant, NOT the standard one):"
        "       https://bell-sw.com/pages/downloads/native-image-kit/"
        "     or: brew install --cask liberica-nik-full"
        "expected location: ~/Library/Java/JavaVirtualMachines/bellsoft-liberica-vm-full-openjdk25-*"
        "desktop/scripts/env.sh uses \$GRAALVM_HOME if set, otherwise globs that path."
    )
    missing_message="no Liberica NIK found (GRAALVM_HOME unset and nothing matched the default location)"
else
    nik_install_hint=(
        "fix: install upstream GraalVM for JDK 25 with native-image and set GRAALVM_HOME:"
        "       https://www.graalvm.org/downloads/"
        "Liberica NIK is a macOS requirement only; upstream GraalVM supports AWT here."
    )
    missing_message="GRAALVM_HOME is not set"
fi

if [[ -z "$graalvm_home" ]]; then
    # Not having it is not a broken setup on a machine that only builds the Rust side,
    # which is the whole development loop apart from the renderer itself.
    warn "$missing_message"
    for hint in "${nik_install_hint[@]}"; do
        warn "      $hint"
    done
elif [[ ! -x "$graalvm_home/bin/native-image" ]]; then
    fail "GRAALVM_HOME=$graalvm_home has no bin/native-image" \
         "${nik_install_hint[@]}"
else
    ok "native-image: $graalvm_home/bin/native-image"

    if [[ "$uname_s" == "Darwin" ]]; then
        # Upstream GraalVM skips AWT on Darwin (oracle/graal#13272), so it ships no
        # static AWT archive and the Compose renderer cannot be linked with it.
        awt_archive=""
        for candidate in "$graalvm_home"/lib/static/darwin-*/libawt_lwawt.a; do
            [[ -f "$candidate" ]] && awt_archive="$candidate"
        done
        if [[ -n "$awt_archive" ]]; then
            ok "static AWT archive: $awt_archive"
        else
            warn "$graalvm_home has no lib/static/darwin-*/libawt_lwawt.a"
            warn "      This looks like upstream GraalVM (or a non-Full NIK). On macOS it skips"
            warn "      AWT entirely (oracle/graal#13272), so the native image cannot be linked"
            warn "      with it. The renderer this platform ships does not need it."
            for hint in "${nik_install_hint[@]}"; do
                warn "      $hint"
            done
        fi
    fi
fi

# --- The patched Compose ----------------------------------------------------
# What the macOS renderer cannot be built without. It resolves Compose from the local
# Maven repository, because the entries a text selection offers and the keys that copy are
# empty in the build JetBrains publishes for this platform and cannot be filled from
# outside the module that holds them. The version is read from the script that publishes
# it so that there is one place to change it.
if [[ "$uname_s" == "Darwin" ]]; then
    compose_build_script="$renderer_dir/scripts/build-compose.sh"
    patched_version="$(sed -n 's/^EXTENDED_AS="\([^"]*\)"$/\1/p' "$compose_build_script" 2>/dev/null | head -1)"
    if [[ -z "$patched_version" ]]; then
        fail "cannot tell which Compose version $compose_build_script publishes" \
             "It is read from the EXTENDED_AS assignment there. Either the script is" \
             "missing or that line was renamed, and this check has nothing to look for."
    else
        patched_compose="$HOME/.m2/repository/org/jetbrains/compose/ui/ui-macosarm64/$patched_version"
        if [[ -d "$patched_compose" ]]; then
            ok "patched Compose: $patched_compose"
        else
            warn "no patched Compose at $patched_compose"
            warn "      The macOS renderer resolves Compose from the local Maven repository, so"
            warn "      its build cannot start until this is published. Everything else works."
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
