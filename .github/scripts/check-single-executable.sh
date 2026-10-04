#!/usr/bin/env bash
# Usage: .github/scripts/check-single-executable.sh <renderer artifact .tar.gz> <scratch directory>
#
# Builds an application the way someone using this crate would, from a renderer artifact
# shaped exactly like the one the release publishes, and proves the result is one
# executable: it needs nothing but the system's own libraries, it carries no renderer,
# Skia or Java runtime beside it, and copied alone into an empty directory it opens its
# window and draws. With no ICU data file on the machine, it also has to lay Korean out
# correctly: every glyph found, words found whole, lines broken between words.
#
#   <renderer artifact>  compose-rust-renderer-v<version>-<target>.tar.gz, with its
#                        .sha256 beside it. The version has to be this checkout's crate
#                        version, because that is the file the build script asks for.
#   <scratch directory>  emptied first; it must be outside the checkout, so the only
#                        renderer the application can find is the one inside it
#
# The artifact is not pointed at with a variable. It is put in a download cache of its
# own, which is where a build with no network looks, so the build script takes the same
# path a consumer's first build does: verify the checksum, unpack, link. The checkout this
# runs in must not have built a renderer of its own, or the workspace build would win.
#
# Needs a display. On Linux run it under `xvfb-run -a`; a macOS runner has one.
#
# What it prints is the evidence: the executable's size, before and after stripping, and
# every library it names.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <renderer artifact .tar.gz> <scratch directory>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture="$repo_root/compose-rust/tests/fixtures/consumer"

fail() {
    echo "fail  $1" >&2
    shift
    for line in "$@"; do echo "      $line" >&2; done
    exit 1
}

os="$(uname -s)"
case "$os" in
    Darwin|Linux) ;;
    *) fail "this checks macOS and Linux executables; $os is not one" ;;
esac
if [[ "$os" == Linux && -z "${DISPLAY:-}" ]]; then
    fail "DISPLAY is not set" "Run it under xvfb-run -a."
fi
command -v cargo >/dev/null || fail "cargo is not on PATH"

artifact="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
[[ -f "$artifact" ]] || fail "no artifact at $1"
[[ -f "$artifact.sha256" ]] || fail "no checksum beside it at $artifact.sha256" \
    "The build script verifies every artifact before unpacking it, as it will for a consumer."

crate_version="$(sed -n 's/^version = "\(.*\)"$/\1/p' "$repo_root/compose-rust/Cargo.toml" | head -1)"
case "$(basename "$artifact")" in
    "compose-rust-renderer-v$crate_version-"*.tar.gz) ;;
    *) fail "$(basename "$artifact") is not an artifact for compose-rust $crate_version" \
        "The build script downloads compose-rust-renderer-v$crate_version-<target>.tar.gz." ;;
esac

# The archive, not a shared library: one executable is what this checks.
# Listed into a variable first: grep -q stops reading early, and under pipefail the tar it
# cut off would fail the check.
contents="$(tar -tzf "$artifact")"
grep -qE '^(\./)?lib/libcompose_rust_renderer\.a$' <<< "$contents" ||
    fail "$(basename "$artifact") does not carry lib/libcompose_rust_renderer.a" \
        "Only the static renderer makes an application one executable."

# A workspace build in this checkout would be found before the cache, and then this would
# test that instead of the artifact.
for built in renderer/build/macos renderer/build/linux \
             renderer/build/linux-arm64 renderer/build/native-image/dist/lib; do
    [[ ! -e "$repo_root/$built" ]] || fail "$built exists in this checkout" \
        "The build script would link that instead of the artifact. Run this in a clean checkout."
done

mkdir -p "$2"
scratch="$(cd "$2" && pwd)"
case "$scratch/" in
    "$repo_root"/*) fail "the scratch directory is inside the checkout" \
        "Use one that is not, so the application cannot reach anything the build left." ;;
esac
rm -rf "$scratch"
mkdir -p "$scratch/cache/downloads" "$scratch/run"
cp "$artifact" "$artifact.sha256" "$scratch/cache/downloads/"

# Nothing may point the build anywhere else.
unset COMPOSE_RUST_RENDERER_DIR DIOXUS_COMPOSE_RENDERER_DIR DXC_MACOS_NATIVE_LIB DXC_LINUX_NATIVE_LIB
# The Kotlin/Native renderer cannot close its own window, so the self-check ends the
# process once the frames are in.
unset COMPOSE_RUST_AUTOEXIT_MS
export COMPOSE_RUST_CACHE_DIR="$scratch/cache"
# The renderer lays Korean out before it opens the window and says whether every glyph was
# found, the word around a syllable was the whole word, and lines broke between words. It
# runs with no ICU data file on the machine, which is checked below, so what it proves is
# that the executable needs none.
export COMPOSE_RUST_TEXT_SELF_CHECK=1

# No ICU data file anywhere an application could be pointed at one. The executable must find
# words and line breaks with what is linked into it. A runner that ships one (a browser
# carries icudtl.dat) has to have it removed before this runs.
icu_files="$(find /usr /opt /Library /Applications /home /Users -name 'icudtl.dat' 2>/dev/null || true)"
[[ -z "$icu_files" ]] || fail "an ICU data file is on this machine, so the text check would not prove anything" \
    $icu_files

# What every system the application may name lives under. Anything else is a file that
# would have to travel with it.
system_library() {
    case "$os" in
        Darwin) [[ "$1" == /usr/lib/* || "$1" == /System/Library/* ]] ;;
        Linux) [[ "$1" == /lib/* || "$1" == /lib64/* || "$1" == /usr/lib/* || "$1" == /usr/lib64/* ]] ;;
    esac
}

# Prints what the executable names and fails on anything that is not the system's.
check_libraries() {
    local binary="$1" failures=0 library resolved
    if [[ "$os" == Darwin ]]; then
        echo "-- otool -L $(basename "$binary")"
        otool -L "$binary" | tail -n +2
        while read -r library; do
            system_library "$library" || {
                echo "      not a system library: $library" >&2
                failures=$((failures + 1))
            }
        done < <(otool -L "$binary" | tail -n +2 | awk '{ print $1 }')
        if otool -l "$binary" | grep -q LC_RPATH; then
            echo "      the executable carries an rpath, so it expects something beside it" >&2
            failures=$((failures + 1))
        fi
    else
        echo "-- readelf -d $(basename "$binary") (NEEDED, RPATH, RUNPATH)"
        readelf -d "$binary" | grep -E '\((NEEDED|RPATH|RUNPATH)\)' || true
        if readelf -d "$binary" | grep -qE '\((RPATH|RUNPATH)\)'; then
            echo "      the executable carries a search path, so it expects something beside it" >&2
            failures=$((failures + 1))
        fi
        while read -r library; do
            [[ "$library" != */* ]] || {
                echo "      named by path, so it is not found the way a system library is: $library" >&2
                failures=$((failures + 1))
                continue
            }
            # Where the loader finds it on this machine, which has to be the system's own
            # directories: a library only this build has would be found nowhere else.
            # `name => path`, or for the dynamic loader itself, the path alone.
            resolved="$(ldd "$binary" | awk -v name="$library" '
                $1 == name { print $3; exit }
                $1 ~ ("/" name "$") { print $1; exit }')"
            if [[ -z "$resolved" || "$resolved" == "not" ]] || ! system_library "$resolved"; then
                echo "      $library resolves to '${resolved:-nothing}', which is not a system directory" >&2
                failures=$((failures + 1))
            fi
        done < <(readelf -d "$binary" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
        echo "-- ldd $(basename "$binary")"
        ldd "$binary"
    fi
    # Named for what used to travel beside an application: the renderer, Skia, the Java
    # runtime's pieces, the Linux host exports library. (The system's own ICU, which macOS
    # keeps in /usr/lib, passed the check above and is not one of these.)
    if [[ "$os" == Darwin ]]; then
        otool -L "$binary" | tail -n +2 | awk '{ print $1 }'
    else
        readelf -d "$binary" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'
    fi | grep -iE 'dioxus|skiko|skia|jvm|jawt|awt|java' && {
        echo "      the executable still names a library of the renderer's" >&2
        failures=$((failures + 1))
    }
    # And nothing of a Java runtime linked into it either: a native image carries one,
    # and a statically linked JNI library announces itself with JNI_OnLoad_<name>.
    local symbols
    symbols="$(nm "$binary" 2>/dev/null || true)"
    if grep -E ' _?JNI_OnLoad' <<< "$symbols"; then
        echo "      the executable carries a JNI library, so a Java runtime is linked into it" >&2
        failures=$((failures + 1))
    fi
    [[ "$failures" -eq 0 ]] || fail "$(basename "$binary") needs $failures thing(s) that are not the system's"
}

size_of() {
    if [[ "$os" == Darwin ]]; then stat -f %z "$1"; else stat -c %s "$1"; fi
}

megabytes() {
    awk -v bytes="$1" 'BEGIN { printf "%.2f MB (%d bytes)", bytes / 1000000, bytes }'
}

# Builds the fixture into a target directory of its own inside the checkout's target/, as
# consumer-crate.test.sh does, so it never shares a build with anything else.
build() {
    local name="$1" flags="$2" log
    log="$scratch/build-$name.log"
    echo "== building the consumer ($name) against the artifact" >&2
    if ! CARGO_TARGET_DIR="$repo_root/target/single-executable/$name" RUSTFLAGS="$flags" \
        cargo build --release --manifest-path "$fixture/Cargo.toml" 2>&1 | tee "$log" >&2; then
        fail "the consumer did not build against the artifact; the log is above"
    fi
    # The build script says where it unpacked the renderer from. It has to be the cache
    # this run made, which is the route a consumer's first build takes.
    grep -q "unpacked the renderer for v$crate_version" "$log" ||
        grep -q "$scratch/cache/renderer" "$log" ||
        echo "note  the build log does not say it unpacked the artifact (a cached build replays nothing)" >&2
    echo "$repo_root/target/single-executable/$name/release/consumer"
}

run_alone() {
    local binary="$1" name="$2"
    local directory="$scratch/run/$name"
    rm -rf "$directory"
    mkdir -p "$directory"
    cp "$binary" "$directory/consumer"
    echo "-- ls -la $directory"
    ls -la "$directory"
    [[ "$(ls -A "$directory")" == "consumer" ]] || fail "something other than the executable is in $directory"
    echo "== running it alone in $directory"
    local log="$scratch/run-$name.log" status=0
    ( cd "$directory" && ./consumer --self-check ) > "$log" 2>&1 || status=$?
    cat "$log"
    grep -q "compose-rust text self-check: ok" "$log" ||
        fail "the renderer did not lay Korean text out correctly with no ICU data file" \
            "Its own report is above."
    [[ "$status" -eq 0 ]] || fail "the executable did not draw on its own (exit $status)"
}

report() {
    local binary="$1" stripped="$scratch/consumer.stripped"
    cp "$binary" "$stripped"
    if [[ "$os" == Darwin ]]; then strip -x "$stripped"; else strip "$stripped"; fi
    echo "-- size: $(megabytes "$(size_of "$binary")"), stripped $(megabytes "$(size_of "$stripped")")"
    rm -f "$stripped"
}

binary="$(build default "")"
[[ -x "$binary" ]] || fail "the build produced no $binary"
ls "$scratch/cache/renderer"/v"$crate_version"/* >/dev/null 2>&1 ||
    fail "the build did not unpack the artifact into $scratch/cache/renderer" \
        "It found a renderer somewhere else, so this did not test the artifact."
check_libraries "$binary"
report "$binary"
run_alone "$binary" default

if [[ "$os" == Linux ]] && grep -qi 'lld' <<< "$(readelf -p .comment "$binary" 2>/dev/null || true)"; then
    # GNU ld and LLVM's lld do not agree on everything an archive's weak references are
    # owed, and an application may be linked with either. The toolchain linked the one
    # above with lld; this is GNU ld.
    second="$(build gnu-ld "-Clink-arg=-fuse-ld=bfd")"
    check_libraries "$second"
    run_alone "$second" gnu-ld
fi

echo "ok    an application depending only on compose-rust is one executable"
echo "      $(basename "$artifact"): $(megabytes "$(size_of "$artifact")")"
