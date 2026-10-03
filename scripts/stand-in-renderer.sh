#!/usr/bin/env bash
# Usage: scripts/stand-in-renderer.sh <output directory>
#        scripts/stand-in-renderer.sh --if-refused <output directory>
#
# Build a stand-in for the renderer: a shared library with the renderer's file name and
# its two entry points, generated from this checkout's schema, that draws nothing.
#
# What it is for. A build of this crate links a renderer, and the build script refuses
# one generated from a different schema than the crate, which is right: the program would
# start and draw nothing. On a checkout whose schema has moved since the last release that
# leaves nothing to link at all, because the only renderer to be had without tens of
# minutes of native-image building is the published one, and it was built from the old
# schema. A version bump does the same from the other side: until that version is
# released there is no published renderer for it at all. A check about packaging or about
# how a binary finds its renderer then fails for a reason that has nothing to do with what
# it checks, and keeps failing until the next release.
#
# This answers those checks with a library that is what they are about: a file of the
# right name, in the layout a distribution has, that a binary links against and the
# loader has to find, carrying this checkout's schema hash so the build script accepts it
# on the same terms it accepts a real one. It is never a renderer anyone can use. Asked to
# run, it says it is a stand-in and fails.
#
# The layout is the one COMPOSE_RUST_RENDERER_DIR accepts:
#
#     <output>/lib/libcompose_rust_renderer.{dylib,so}
#     <output>/schema-hash.txt
#
# With --if-refused it first asks the build script, the way any build of this crate
# would, for the renderer it finds. Only if that renderer is refused for its schema, or
# the release has no renderer for this crate version at all, is the stand-in built, and then the one line `COMPOSE_RUST_RENDERER_DIR=<output>` is written
# to standard output, ready to append to $GITHUB_ENV. When the renderer is accepted
# nothing is built and nothing is written there, so the real one stays in use. Any other
# failure of that build is reported and fails this script. Everything else this script
# says goes to standard error.

set -euo pipefail

usage() {
    echo "usage: $0 [--if-refused] <output directory>" >&2
    exit 2
}

if_refused=0
if [[ "${1:-}" == "--if-refused" ]]; then
    if_refused=1
    shift
fi
[[ $# -eq 1 ]] || usage

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$1"
[[ "$out" == /* ]] || out="$PWD/$out"

if [[ $if_refused -eq 1 ]]; then
    mkdir -p "$repo_root/target"
    log="$(mktemp "$repo_root/target/stand-in-probe.XXXXXX")"
    # A check is enough: the build script runs, finds a renderer and checks its schema,
    # and nothing is linked.
    if (cd "$repo_root" && cargo check -p compose-rust --lib --quiet) 2>"$log"; then
        rm -f "$log"
        echo "the renderer this build finds was generated from this checkout's schema" >&2
        exit 0
    fi
    # The two refusals that mean no published renderer fits this tree. The first is the
    # schema check. The second is a release that answered, with no artifact for this
    # version, which is what every version bump looks like until it is released. A network
    # failure, a bad checksum or anything else is not one of them and still fails.
    if grep -q "generated from a different schema" "$log"; then
        reason="the renderer this build finds was generated from a different schema than this checkout"
    elif grep -q "The server was reached and answered that there is no such file" "$log"; then
        reason="no renderer is published for this crate version yet"
    else
        cat "$log" >&2
        rm -f "$log"
        echo "error: building compose-rust failed for a reason other than having no published renderer that fits" >&2
        exit 1
    fi
    rm -f "$log"
    echo "$reason, so a stand-in with this checkout's schema is built in its place" >&2
fi

case "$(uname -s)" in
    Darwin) library=libcompose_rust_renderer.dylib ;;
    Linux) library=libcompose_rust_renderer.so ;;
    *)
        echo "error: no stand-in renderer for $(uname -s)" >&2
        exit 1
        ;;
esac

compiler="${CC:-cc}"
command -v "$compiler" >/dev/null || {
    echo "error: no C compiler ($compiler) on PATH to build the stand-in renderer with" >&2
    exit 1
}

rm -rf "$out"
mkdir -p "$out/lib"

# The three functions the Host declares and calls. Nothing else is asked of a renderer at
# link time.
#
# Its measure answers by the mock renderer's fixed rules (`compose_rust::measure`), so a
# binary linked against it gets the same deterministic sizes a test of the Host gets: every
# character half the font size wide, a line one and a quarter font sizes tall, lines broken
# at spaces, and no node known. It has no UI thread to check against and never runs one.
cat > "$out/stand-in.c" <<'C'
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

int32_t compose_rust_renderer_run(void) {
    fputs("compose-rust: this is a stand-in renderer, built only so that a binary has a "
          "renderer to link against. It cannot draw. Build the real one for this platform "
          "and point COMPOSE_RUST_RENDERER_DIR at it.\n",
          stderr);
    return 1;
}

void compose_rust_renderer_request_frame(void) {}

typedef struct {
    float width, height, first_baseline, last_baseline, last_line_width;
    uint32_t line_count, flags, status;
} MeasureResult;

enum { RECORD = 72, KIND_TEXT = 1, KIND_NODE = 2 };
enum { OK = 0, UNKNOWN_NODE = 1, MALFORMED = 2, TRUNCATED = 1 };

static uint32_t word(const uint8_t *at) {
    uint32_t value;
    memcpy(&value, at, 4);
    return value;
}

static float real(const uint8_t *at) {
    float value;
    memcpy(&value, at, 4);
    return value;
}

/* Characters, not bytes: a UTF-8 continuation byte starts nothing. */
static uint32_t characters(const uint8_t *text, uint32_t length) {
    uint32_t count = 0;
    for (uint32_t i = 0; i < length; i++) {
        if ((text[i] & 0xC0) != 0x80) count++;
    }
    return count;
}

/* The mock's line breaking: at spaces and newlines, greedily, into lines of `width`. */
static void lay_out(
    const uint8_t *text, uint32_t length, float width, float advance, MeasureResult *out,
    uint32_t max_lines
) {
    float widest = 0, last = 0;
    uint32_t lines = 0, shown = 0;
    uint32_t start = 0;
    while (start <= length) {
        uint32_t end = start;
        while (end < length && text[end] != '\n') end++;
        uint32_t line = 0;
        uint32_t at = start;
        while (at <= end) {
            uint32_t word_end = at;
            while (word_end < end && text[word_end] != ' ') word_end++;
            uint32_t word_length = characters(text + at, word_end - at);
            uint32_t joined = line == 0 ? word_length : line + 1 + word_length;
            if (line != 0 && (float)joined * advance > width) {
                lines++;
                if (max_lines == 0 || lines <= max_lines) {
                    shown = lines;
                    last = (float)line * advance;
                    if (last > widest) widest = last;
                }
                line = word_length;
            } else {
                line = joined;
            }
            at = word_end + 1;
        }
        lines++;
        if (max_lines == 0 || lines <= max_lines) {
            shown = lines;
            last = (float)line * advance;
            if (last > widest) widest = last;
        }
        start = end + 1;
    }
    out->width = widest;
    out->line_count = shown;
    out->last_line_width = last;
    out->flags = shown < lines ? TRUNCATED : 0;
}

int32_t compose_rust_renderer_measure(
    const uint8_t *requests, uint32_t length, uint32_t count, MeasureResult *results
) {
    if (requests == NULL || results == NULL) return -1;
    if ((uint64_t)count * RECORD > length) return -1;
    for (uint32_t index = 0; index < count; index++) {
        const uint8_t *record = requests + (uint64_t)index * RECORD;
        MeasureResult *out = &results[index];
        MeasureResult failed = {0, 0, NAN, NAN, NAN, 0, 0, MALFORMED};
        uint16_t kind = (uint16_t)(record[0] | (record[1] << 8));
        if (kind == KIND_NODE) {
            failed.status = UNKNOWN_NODE;
            *out = failed;
            continue;
        }
        uint32_t at = word(record + 4), text_length = word(record + 8);
        if (kind != KIND_TEXT || (uint64_t)at + text_length > length) {
            *out = failed;
            continue;
        }
        float size = real(record + 36);
        if (!(size > 0)) size = 14;
        float line_height = real(record + 48);
        if (isnan(line_height) || line_height <= 0) line_height = size * 1.25f;
        uint32_t max_lines = word(record + 52);
        int wrap = record[43] != 0;
        float width = real(record + 64);
        float limit;
        switch (word(record + 60)) {
            case 1: limit = wrap ? 0 : INFINITY; break;
            case 2: limit = INFINITY; break;
            case 3:
                if (!(width >= 0)) { *out = failed; continue; }
                limit = wrap ? width : INFINITY;
                break;
            default: *out = failed; continue;
        }
        lay_out(requests + at, text_length, limit, size * 0.5f, out, max_lines);
        out->height = (float)out->line_count * line_height;
        out->first_baseline = size;
        out->last_baseline = (float)(out->line_count > 0 ? out->line_count - 1 : 0) * line_height + size;
        out->status = OK;
    }
    return 0;
}
C

if [[ "$(uname -s)" == "Darwin" ]]; then
    # Room in the header for the absolute install name the build script writes into it.
    "$compiler" -dynamiclib -Wl,-headerpad_max_install_names \
        -o "$out/lib/$library" "$out/stand-in.c"
else
    # With the same table of Host references the real library carries, which is what
    # makes an application export the functions the renderer calls back into. No SONAME,
    # like the real one: the build script gives it one naming where it sits.
    "$compiler" -shared -fPIC -o "$out/lib/$library" "$out/stand-in.c" \
        "$repo_root/renderer/desktop/c/linux_host_references.c"
fi

cp "$repo_root/compose-rust/schema-hash.txt" "$out/schema-hash.txt"

echo "stand-in renderer: $out/lib/$library (schema $(cat "$out/schema-hash.txt"))" >&2
if [[ $if_refused -eq 1 ]]; then
    echo "COMPOSE_RUST_RENDERER_DIR=$out"
fi
