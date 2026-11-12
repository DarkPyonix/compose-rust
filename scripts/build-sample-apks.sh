#!/usr/bin/env bash
# Usage: ./scripts/build-sample-apks.sh [--debug] [output-directory]
#
# One APK per sample, built the way anyone else would build one.
#
# `dx build --platform android` is the whole thing. It generates the Gradle project, and
# this crate's build script puts the renderer's Kotlin into it and generates the Activity
# that hosts it, so a sample's own Dioxus.toml says nothing about the renderer at all.
#
# That is the point of building them this way. An earlier version of this script put each
# sample's library into the renderer's own Android module and built that instead, which
# produced APKs by a route no user takes: it proved that the renderer works on Android and
# said nothing about whether anyone else could get there.
#
# A sample that does not build is skipped and named at the end. Eleven samples do not all
# have to work for the ten that do to be worth shipping.
#
# The ABI is named, because dx does not pick a phone's. With no target it builds for the
# machine it runs on, so on an x86-64 runner every APK carried its host library only at
# lib/x86_64/ and died on an arm64 phone with "libmain.so is not in this APK". The name
# this script gives an APK is a claim about what is inside it, so the APK is opened and the
# claim checked: an APK without lib/$abi/libmain.so is not published under that name, and
# the run fails naming it. That is not a sample that failed to build, it is every APK being
# wrong, and it must not look like a smaller release.
set -uo pipefail

# The phone ABI and the Rust target that produces it. Every arm64 phone runs arm64-v8a.
abi=arm64-v8a
rust_target=aarch64-linux-android

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

profile=--release
output=
for argument in "$@"; do
    case "$argument" in
        --debug) profile= ;;
        -*) echo "unknown option $argument" >&2; exit 2 ;;
        *) output="$argument" ;;
    esac
done
output="${output:-$repo_root/target/sample-apks}"
case "$output" in /*) ;; *) output="$PWD/$output" ;; esac

command -v dx >/dev/null || {
    echo "dx is not on PATH. It is what builds an Android application here:" >&2
    echo "  cargo install dioxus-cli" >&2
    exit 1
}
command -v unzip >/dev/null || {
    echo "unzip is not on PATH. It is how each APK is checked for its $abi host library." >&2
    exit 1
}
# Android Gradle 8.7 runs jlink to make a system image and that fails on a JDK newer than
# 21. The failure names jlink and a cache directory and never mentions the JDK, so the
# version is checked here instead of being discovered an hour later.
if [[ -n "${JAVA_HOME:-}" ]]; then
    java_version="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
    if [[ "$java_version" -gt 21 ]]; then
        echo "JAVA_HOME is Java $java_version. The Android Gradle plugin's jlink step" >&2
        echo "fails above 21, so point JAVA_HOME at a 17 or 21 JDK." >&2
        exit 1
    fi
fi

mkdir -p "$output"
built=()
skipped=()
wrong_abi=()

for manifest in samples/*/Cargo.toml; do
    sample="$(basename "$(dirname "$manifest")")"
    # An application rather than a library. Every sample is a library now, so a lib.rs
    # no longer tells them apart: `frames` is the one the others record their screens
    # through and it has no application in it. The binary is what says so.
    [[ -f "samples/$sample/src/main.rs" ]] || continue
    echo "== $sample"

    if ! dx build --package "sample-$sample" --platform android --target "$rust_target" \
        ${profile:+$profile}; then
        skipped+=("$sample")
        continue
    fi
    apk="$(find target/dx/sample-$sample -name '*.apk' -print -quit)"
    if [[ -z "$apk" ]]; then
        skipped+=("$sample (no apk)")
        continue
    fi
    # `unzip -Z1` lists one entry name per line, so the match is on the whole path.
    if ! unzip -Z1 "$apk" 2>/dev/null | grep -qx "lib/$abi/libmain.so"; then
        echo "ERROR: $apk has no lib/$abi/libmain.so, so it is not an $abi APK." >&2
        echo "       Its host libraries are:" >&2
        unzip -Z1 "$apk" 2>/dev/null | grep -E '^lib/[^/]+/libmain\.so$' | sed 's/^/         /' >&2 ||
            echo "         none" >&2
        echo "       A phone would fail to load libmain.so at launch." >&2
        wrong_abi+=("$sample")
        continue
    fi
    cp "$apk" "$output/$sample-android-$abi.apk"
    built+=("$sample")
done

echo
echo "built ${#built[@]}: ${built[*]:-none}"
[[ ${#skipped[@]} -eq 0 ]] || echo "skipped ${#skipped[@]}: ${skipped[*]}"
if [[ ${#wrong_abi[@]} -gt 0 ]]; then
    echo "FAILED ${#wrong_abi[@]}: ${wrong_abi[*]} built an APK without lib/$abi/libmain.so" >&2
    exit 1
fi
[[ ${#built[@]} -gt 0 ]] || { echo "no APK was built at all, which is the toolchain rather than a sample" >&2; exit 1; }
