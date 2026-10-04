#!/usr/bin/env bash
# Sourced by scripts/parity/linux.sh and scripts/parity/macos.sh. What the two have in common is
# everything that does not touch a display server: how a window's end-of-run output becomes
# rows, and how the table is printed. A row is `name<TAB>result`; a result that starts with FAIL
# is a failure.

row() { printf '%s\t%s\n' "$1" "$2" >> "$table"; }

# Rows from what the window printed on standard error ($err): the numbers LatencyTrace and the
# resize accounting leave, which every window that draws a scene prints in the same words.
report_measurements() {
value() { sed -nE "s/.*parity $1 ([^ ]+).*/\1/p" "$err" | tail -1; }
field() { sed -nE "s/.*parity $1 [^ ]+ .*$2=([^ ]+).*/\1/p" "$err" | tail -1; }
latency="$(value input_latency_avg_ms)"
resize="$(value frame_resize_avg_ms)"
idle="$(value frame_idle_avg_ms)"
row input_latency_avg_ms "${latency:-FAIL not reported}"
row frame_resize_avg_ms "${resize:-FAIL not reported}"
row frame_resize_worst_ms "$(field frame_resize_avg_ms worst_ms)"
row frame_idle_avg_ms "${idle:-FAIL not reported}"
resize_line="$(grep -E 'dxc resize: ' "$err" | tail -1)"
resize_field() { echo "$resize_line" | sed -nE "s/.*$1=([0-9]+).*/\1/p"; }
row resize_steps "$(resize_field steps || true)"
row resize_presented "$(resize_field presented || true)"
row resize_stale "$(resize_field stale || true)"
row resize_stretched "$(resize_field stretched || true)"
ratio="$(echo "$resize_line" | sed -nE 's/.*stale_ratio=([0-9.]+).*/\1/p')"
if [[ -z "$ratio" ]]; then
    row resize_stale_ratio "FAIL not reported"
elif [[ "$ratio" == "0" || "$ratio" =~ ^0\.0*$ ]]; then
    row resize_stale_ratio "ok $ratio"
else
    row resize_stale_ratio "FAIL $ratio of the frames were drawn at a size other than the window's"
fi
row system_theme "$(sed -nE 's/.*parity system_theme ([a-z]+).*/\1/p' "$err" | tail -1)"

}

# Prints the table, adds it to the job summary, and fails only when PARITY_STRICT=1.
print_table() {
    echo "== parity: $label"
    column -t -s $'\t' "$table" 2>/dev/null || cat "$table"
    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
        {
            echo "### Parity: $label"
            echo
            echo "| check | $label |"
            echo "|---|---|"
            while IFS=$'\t' read -r name result; do echo "| $name | $result |"; done < "$table"
            echo
        } >> "$GITHUB_STEP_SUMMARY"
    fi
    if grep -q $'\tFAIL' "$table"; then
        echo "::warning::parity $label has failing rows"
        [[ "${PARITY_STRICT:-}" == "1" ]] && exit 1
    fi
    exit 0
}
