#!/usr/bin/env bash
# Writes a Flatpak manifest for an application and, optionally, builds, installs and lints
# it the way Flathub does.
#
#     tools/packager-linux/flatpak.sh \
#         --dioxus-toml samples/calculator/Dioxus.toml --overlay linux.toml \
#         --prebuilt-url https://.../calculator-linux-x64.tar.gz --prebuilt-sha256 <hex> \
#         --icon icon-256.png --version 1.2.3 --out flatpak/ --build repo/ --lint
#
# Every option packager-linux's `flatpak` command takes is passed through to it. A build
# from source also needs the crates vendored, because a Flathub build has no network:
#
#   --cargo-lock <file>   write <out>/cargo-sources.json from this lock file with the
#                         pinned flatpak-cargo-generator (and pass it as --cargo-sources)
#   --build <repo>        build with flatpak-builder into this OSTree repository and
#                         install the result for the current user
#   --lint                run Flathub's linter on the manifest, and on the repository
#                         when one was built. Findings are printed and written to
#                         <out>/lint-*.json; they do not stop the script, because some of
#                         them (an app ID whose domain is not verified yet) are only
#                         settled by Flathub
#
# The build uses flatpak-builder from the host (FLATPAK_BUILDER overrides it). The linter
# is Flathub's own, from org.flatpak.Builder, installed for the user when missing. flatpak
# itself must be installed.
#
# Downloads (the generator) go to PACKAGER_LINUX_CACHE, default .scratch/packager-linux/tools
# in this repository. packager-linux is run through `cargo run` unless PACKAGER_LINUX
# names a built one.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

fail() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

GENERATOR_COMMIT=41c20aa10819cdb2a4f3ca171758a96d1955c018
GENERATOR_SHA256=0a2db6be87d75910facef28ab46d4d6460802e8419ab850d0caa6a364d26b380

passthrough=()
out=""
cargo_lock=""
build_repo=""
lint=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --cargo-lock) cargo_lock="$2"; shift 2 ;;
        --build) build_repo="$2"; shift 2 ;;
        --lint) lint=1; shift ;;
        --out) out="$2"; passthrough+=("$1" "$2"); shift 2 ;;
        --wayland) passthrough+=("$1"); shift ;;
        --*)
            [[ $# -ge 2 ]] || fail "$1 needs a value"
            passthrough+=("$1" "$2")
            shift 2
            ;;
        *) fail "unexpected argument $1" "See the comment at the top of $0." ;;
    esac
done
[[ -n "$out" ]] || fail "--out is required"
mkdir -p "$out"
out="$(cd "$out" && pwd)"

packager() {
    if [[ -n "${PACKAGER_LINUX:-}" ]]; then
        "$PACKAGER_LINUX" "$@"
    else
        cargo run --quiet --release --manifest-path "$repo_root/Cargo.toml" \
            -p packager-linux -- "$@"
    fi
}

cache="${PACKAGER_LINUX_CACHE:-$repo_root/.scratch/packager-linux/tools}"
mkdir -p "$cache"

if [[ -n "$cargo_lock" ]]; then
    generator="$cache/flatpak-cargo-generator-$GENERATOR_COMMIT.py"
    if [[ ! -f "$generator" ]]; then
        curl --fail --location --silent --show-error --retry 3 -o "$generator.part" \
            "https://raw.githubusercontent.com/flatpak/flatpak-builder-tools/$GENERATOR_COMMIT/cargo/flatpak-cargo-generator.py"
        got="$(sha256sum "$generator.part" | cut -d' ' -f1)"
        [[ "$got" == "$GENERATOR_SHA256" ]] || fail \
            "flatpak-cargo-generator has digest $got, pinned is $GENERATOR_SHA256"
        mv "$generator.part" "$generator"
    fi
    python3 -c 'import aiohttp, tomlkit' 2>/dev/null || fail \
        "flatpak-cargo-generator needs the Python modules aiohttp and tomlkit" \
        "pip install 'aiohttp>=3.9.5,<4' 'tomlkit>=0.13.3,<1'"
    echo "== vendoring the crates in $cargo_lock"
    python3 "$generator" "$cargo_lock" -o "$out/cargo-sources.json"
    passthrough+=(--cargo-sources cargo-sources.json)
fi

# The packager prints the manifest's path as it was given; make it absolute.
manifest="$out/$(basename "$(packager flatpak "${passthrough[@]}")")"
echo "== $manifest"

# The build runs with the host's flatpak-builder when there is one. Flathub's own builder,
# org.flatpak.Builder, runs in a sandbox whose user installation is its own, so the
# runtimes installed on the host are invisible to it and it cannot install them either.
# It is still what lints, because the linter only reads files.
builder() {
    if [[ -n "${FLATPAK_BUILDER:-}" ]]; then
        $FLATPAK_BUILDER "$@"
    elif command -v flatpak-builder >/dev/null; then
        flatpak-builder "$@"
    else
        flatpak run --command=flatpak-builder org.flatpak.Builder "$@"
    fi
}

if [[ -n "$build_repo" || $lint -eq 1 ]]; then
    flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
    flatpak --user info org.flatpak.Builder >/dev/null 2>&1 ||
        flatpak --user install --noninteractive flathub org.flatpak.Builder
fi

if [[ -n "$build_repo" ]]; then
    mkdir -p "$build_repo"
    build_repo="$(cd "$build_repo" && pwd)"
    echo "== building into $build_repo"
    (
        cd "$out"
        builder --user --force-clean --install-deps-from=flathub \
            --repo="$build_repo" --install --state-dir="$out/.flatpak-builder" \
            "$out/build" "$manifest"
    )
fi

if [[ $lint -eq 1 ]]; then
    lint_run() {
        local name="$1"
        shift
        echo "== flatpak-builder-lint $*"
        if flatpak run --command=flatpak-builder-lint org.flatpak.Builder "$@" \
            >"$out/lint-$name.json"; then
            echo "   clean"
        else
            echo "   findings:"
        fi
        cat "$out/lint-$name.json"
    }
    lint_run manifest manifest "$manifest"
    if [[ -n "$build_repo" ]]; then
        lint_run repo repo "$build_repo"
    fi
fi
