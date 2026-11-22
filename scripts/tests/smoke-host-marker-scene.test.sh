#!/usr/bin/env bash
# The GraalVM Windows renderer is dragged by the resize probe through the C smoke host, because
# compose-rust links only the static Kotlin/Native kit on Windows and hand-check cannot link the
# GraalVM image. The probe judges every frame by the marker scene hand-check draws, so the smoke
# host has to draw the same scene: the same window, the same nodes, the same colours, widths
# and ruler, selected by the same variable. It writes those records by hand, in C, and nothing
# generated keeps them in step with the Rust Host or with hand-check. This holds them equal:
#   - every constant of the scene in smoke_host.c is hand-check's;
#   - every tag and record length it writes is the one compose-rust's encoder writes;
#   - its batch buffer for the scene holds the whole scene, and the default runs keep the
#     1024 byte buffer their recorded byte counts come from;
#   - the scene is asked for by DXC_RESIZE_MARKER, read as hand-check reads it, says so on a
#     line the probe gate looks for, and asks for no autoexit of its own.
# It reads files only and builds nothing.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
c="$root/renderer/desktop/c/smoke_host.c"
rust="$root/compose-rust/tests/fixtures/hand-check/src/main.rs"
protocol="$root/compose-rust/src/protocol.rs"
schema="$root/compose-rust/src/schema.rs"
red=0
ok() { echo "ok    $1"; }
fail() { echo "FAIL  $1: $2"; red=1; }
note() { [[ -n "$problem" ]] || problem="$1"; }

for file in "$c" "$rust" "$protocol" "$schema"; do
    [[ -f "$file" ]] || { echo "missing $file"; exit 1; }
done

# The value of `#define NAME value` in the C, and of `const NAME: type = value;` in the Rust.
c_define() { sed -nE "s/^#define $1 ([^ ]+)( .*)?$/\1/p" "$c" | head -1; }
rust_const() { sed -nE "s/^const $1: [a-z0-9]+ = ([^;]+);$/\1/p" "$rust" | head -1; }
# A float as the number it is: 2.0 and 2.0f and 2 are one value.
number() { sed -E 's/f$//; s/\.0+$//' <<<"$1"; }
# The discriminant of `Name = value,` inside `define_wire_enum!(..., Enum {` in the schema.
schema_value() {
    awk -v enum="$1" -v name="$2" '
        $0 ~ "[ (]" enum " \\{$" { on = 1; next }
        on && /^}\);?$/ { exit }
        on && $1 == name && $2 == "=" { sub(/,$/, "", $3); print $3; exit }
    ' "$schema"
}

name=fr19_8_4_smoke_host_marker_scene_has_hand_checks_constants
problem=""
for pair in MARKER_GREEN:MARKER_GREEN FIELD_LIGHT:FIELD_LIGHT RULER_DARK:RULER_DARK \
    RULER_PERIODS:RULER_PERIODS MARKER_ROOT:MARKER_ROOT MARKER_BODY:MARKER_BODY \
    MARKER_LEFT:MARKER_LEFT MARKER_RULER:MARKER_RULER MARKER_RIGHT:MARKER_RIGHT \
    MARKER_BOTTOM:MARKER_BOTTOM MARKER_EDGES:MARKER_EDGES RULER_LEAD:RULER_LEAD \
    RULER_MARK0:RULER_MARK0 MARKER_DP:MARKER_DP RULER_PERIOD_DP:RULER_PERIOD_DP; do
    want="$(rust_const "${pair%%:*}")"
    have="$(c_define "${pair#*:}")"
    [[ -n "$want" ]] || { note "hand-check has no const ${pair%%:*}"; continue; }
    [[ -n "$have" ]] || { note "smoke_host.c has no #define ${pair#*:}"; continue; }
    if [[ "$(number "$want" | tr 'A-F' 'a-f')" != "$(number "$have" | tr 'A-F' 'a-f')" ]]; then
        note "${pair#*:} is $have in smoke_host.c and $want in hand-check"
    fi
done
# The window hand-check asks for.
for field in width:MARKER_WINDOW_WIDTH height:MARKER_WINDOW_HEIGHT min_width:MARKER_WINDOW_MIN_WIDTH \
    min_height:MARKER_WINDOW_MIN_HEIGHT; do
    want="$(sed -nE "s/^        window\.${field%%:*} = ([0-9]+);$/\1/p" "$rust" | head -1)"
    have="$(c_define "${field#*:}")"
    [[ -n "$want" && "$want" == "$have" ]] ||
        note "the window's ${field%%:*} is '$have' in smoke_host.c and '$want' in hand-check"
done
want_title="$(sed -nE 's/^        window\.title = "([^"]*)";$/\1/p' "$rust" | head -1)"
grep -qF "#define MARKER_WINDOW_TITLE \"$want_title\"" "$c" ||
    note "the window title is not hand-check's \"$want_title\""
grep -qE '^        window\.resizable = true;$' "$rust" && grep -qE '^#define WINDOW_RESIZABLE 1$' "$c" ||
    note "the window is not resizable in both"
if [[ -n "$problem" ]]; then fail "$name" "$problem"; else ok "$name"; fi

name=fr19_8_4_smoke_host_marker_scene_writes_the_hosts_records
problem=""
for tag in CREATE SET_PROP SET_MODIFIER INSERT SET_WINDOW; do
    want="$(sed -nE "s/^const TAG_$tag: u16 = ([0-9]+);$/\1/p" "$protocol")"
    have="$(grep -oE "\\bTAG_$tag = [0-9]+" "$c" | head -1 | sed -E 's/.* = //')"
    [[ -n "$want" && "$want" == "$have" ]] || note "TAG_$tag is '$have' in smoke_host.c and '$want' in protocol.rs"
done
want="$(sed -nE 's/^const VALUE_I64: u16 = ([0-9]+);$/\1/p' "$protocol")"
have="$(grep -oE '\bVALUE_INTEGER = [0-9]+' "$c" | sed -E 's/.* = //')"
[[ "$want" == "$have" ]] || note "an integer value is tagged $have in smoke_host.c and $want in protocol.rs"
# Record lengths: the encoder's payload plus the four byte header.
setmod="$(grep -oE 'begin_record\(TAG_SET_MODIFIER, 24 \+ 8' "$protocol" | head -1)"
[[ -n "$setmod" ]] || note "protocol.rs no longer writes SetModifier as 24 + 8 * extra"
grep -qE 'begin_record\(TAG_SET_MODIFIER, 28\)' "$c" || note "smoke_host.c does not write 28 byte SetModifier records"
grep -qE 'self\.begin_record\(TAG_SET_WINDOW, 28\);' "$protocol" || note "protocol.rs no longer writes a 28 byte SetWindow payload"
grep -qE 'begin_record\(TAG_SET_WINDOW, 32\)' "$c" || note "smoke_host.c does not write 32 byte SetWindow records"
# The modifiers the scene uses, by the tag modifier_fields gives each, none with extra words.
for pair in FillMaxWidth:MODIFIER_FILL_MAX_WIDTH FillMaxHeight:MODIFIER_FILL_MAX_HEIGHT Width:MODIFIER_WIDTH \
    Height:MODIFIER_HEIGHT Background:MODIFIER_BACKGROUND Weight:MODIFIER_WEIGHT; do
    want="$(grep -E "^        Modifier::${pair%%:*}( |\\()" "$protocol" | grep -oE '=> \([0-9]+,' | head -1 | tr -dc '0-9')"
    have="$(c_define "${pair#*:}")"
    [[ -n "$want" && "$want" == "$have" ]] || note "${pair#*:} is '$have' in smoke_host.c and Modifier::${pair%%:*} is '$want' in protocol.rs"
    extra="$(awk -v name="\"${pair%%:*}\"" '$1 == "name:" && $2 == name "," { on = 1; next } on && $1 == "extra_words:" { sub(/,$/, "", $2); print $2; exit }' "$schema")"
    [[ "$extra" == 0 ]] || note "Modifier::${pair%%:*} has $extra extra words, so its record is not 28 bytes"
done
for pair in WidgetKind:Column:WIDGET_COLUMN WidgetKind:Row:WIDGET_ROW WidgetKind:Box:WIDGET_BOX \
    PropertyKind:Arrangement:PROP_ARRANGEMENT Arrangement:SpaceBetween:ARRANGEMENT_SPACE_BETWEEN \
    Chrome:Modern:CHROME_MODERN TitleBar:Normal:TITLE_BAR_NORMAL; do
    IFS=: read -r enum entry symbol <<<"$pair"
    want="$(schema_value "$enum" "$entry")"
    have="$(grep -oE "\\b$symbol = [0-9]+" "$c" | head -1 | sed -E 's/.* = //')"
    [[ -z "$have" ]] && have="$(c_define "$symbol")"
    [[ -n "$want" && "$want" == "$have" ]] || note "$symbol is '$have' in smoke_host.c and $enum::$entry is '$want' in schema.rs"
done
want="$(sed -nE 's/^const PAINT_KIND_LITERAL: u64 = ([0-9]+);$/\1/p' "$schema")"
grep -qE "^#define PAINT_LITERAL \\(\\(uint64_t\\)$want << 32\\)$" "$c" ||
    note "a literal paint is not kind $want in the high word in smoke_host.c"
grep -qE 'pub const fn rgb\(value: u32\) -> Self \{' "$schema" && grep -qF 'Self(0xff00_0000 | (value & 0x00ff_ffff))' "$schema" &&
    grep -qE '^#define OPAQUE\(rgb\) \(0xff000000u \| \(\(rgb\) & 0x00ffffffu\)\)$' "$c" ||
    note "a colour is not made opaque the way Color::rgb makes it"
if [[ -n "$problem" ]]; then fail "$name" "$problem"; else ok "$name"; fi

name=fr19_8_4_smoke_host_marker_scene_fits_its_buffer_and_leaves_the_default_alone
# Records: SetWindow 32; the root 12 + 3 modifiers; the body, the ruler row, the edge row and the
# lead 12 + 2 each, with the arrangement 24; three markers 12 + 3 each; per period a gap 12 + 2
# and a line 12 + 3; inserts of 16 for every ruler child and two each for edges, body and root.
periods="$(c_define RULER_PERIODS)"
title="$(sed -nE 's/^#define MARKER_WINDOW_TITLE "([^"]*)"$/\1/p' "$c")"
scene=$((12 + 32 + (12 + 3 * 28) + 4 * (12 + 2 * 28) + 24 + 3 * (12 + 3 * 28) \
    + periods * ((12 + 2 * 28) + (12 + 3 * 28)) + (1 + 2 * periods + 6) * 16 + ${#title}))
buffer="$(sed -nE 's/^static uint8_t marker_batch\[([0-9]+)\];$/\1/p' "$c")"
if [[ -z "$periods" || -z "$buffer" ]]; then
    fail "$name" "smoke_host.c has no RULER_PERIODS or no marker_batch buffer"
elif (( buffer < scene )); then
    fail "$name" "the marker scene is about $scene bytes and its buffer $buffer"
elif ! grep -qE '^static uint8_t small_batch\[1024\];$' "$c"; then
    fail "$name" "the default batch buffer is no longer 1024 bytes"
elif ! grep -qE '^static uint32_t batch_capacity = sizeof small_batch;$' "$c"; then
    fail "$name" "the default runs do not start on the 1024 byte buffer"
elif ! grep -qF 'memset(batch_bytes, 0, batch_capacity);' "$c"; then
    fail "$name" "a batch does not clear only the buffer it writes"
else
    ok "$name"
fi

name=fr19_8_4_smoke_host_marker_scene_is_asked_for_as_hand_check_asks
if ! grep -qF 'std::env::var("DXC_RESIZE_MARKER").is_ok_and(|value| !value.is_empty() && value != "0")' "$rust"; then
    fail "$name" "hand-check no longer reads DXC_RESIZE_MARKER as set, not empty and not 0; read it the same way here"
elif ! grep -qF 'getenv("DXC_RESIZE_MARKER")' "$c" ||
    ! grep -qF 'flag != NULL && flag[0] != '"'"'\0'"'"' && strcmp(flag, "0") != 0' "$c"; then
    fail "$name" "smoke_host.c does not read DXC_RESIZE_MARKER as hand-check does"
elif ! grep -qF 'smoke host: marker scene (DXC_RESIZE_MARKER)' "$c"; then
    fail "$name" "smoke_host.c does not say it draws the marker scene"
elif grep -qE 'COMPOSE_RUST_AUTOEXIT_MS' "$c"; then
    fail "$name" "smoke_host.c sets its own autoexit; the marker scene must stay open until it is closed"
else
    ok "$name"
fi

exit "$red"
