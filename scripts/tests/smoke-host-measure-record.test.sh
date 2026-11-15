#!/usr/bin/env bash
# The smoke host writes its measure requests by hand, in C, so nothing generated keeps its
# record length in step with the Rust one. When the record grew from 72 to 80 bytes for the
# font features and the C stayed at 72, the renderer read every request after the first at
# the wrong place, and the smoke test failed on every platform with "the measure round trip
# did not agree". This holds the two lengths equal.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
rust="$(sed -nE 's/^pub const MEASURE_RECORD_LEN: usize = ([0-9]+);$/\1/p' "$root/compose-rust/src/measure.rs")"
c="$(sed -nE 's/^#define MEASURE_RECORD ([0-9]+)$/\1/p' "$root/renderer/desktop/c/smoke_host.c")"
[[ -n "$rust" ]] || { echo "FAIL  MEASURE_RECORD_LEN not found in compose-rust/src/measure.rs" >&2; exit 1; }
[[ -n "$c" ]] || { echo "FAIL  MEASURE_RECORD not found in renderer/desktop/c/smoke_host.c" >&2; exit 1; }
if [[ "$rust" != "$c" ]]; then
    echo "FAIL  the smoke host writes $c byte measure records, the Host and the renderer use $rust" >&2
    exit 1
fi
echo "ok    the smoke host's measure record is $c bytes, the same as MEASURE_RECORD_LEN"
