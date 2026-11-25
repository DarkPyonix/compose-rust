#!/usr/bin/env bash
# Checks that the first Windows window is fitted into its monitor's work area
# (compose-rust#510): oversized or DPI-scaled requests clamp to rcWork so the caption
# stays visible, off-screen positions shift inside, secondary monitors with negative
# origins keep their own coordinates, and minimums larger than rcWork yield to rcWork.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

source "$repo_root/scripts/tests/fork-window.sh"
fork_window_or_skip "$repo_root"

header="$fork_window/windows-shared/c/win32_placement.h"
win32_c="$fork_window/windows-shared/c/win32_window.c"
status=0

fail() {
  echo "FAIL: $1"
  status=1
}

for file in "$header" "$win32_c"; do
  [ -f "$file" ] || { echo "FAIL: $file is missing"; exit 1; }
done

grep -q 'dxc_placement_fit(' "$win32_c" || fail "win32_window.c does not call dxc_placement_fit("
grep -q 'MonitorFromWindow' "$win32_c" || fail "win32_window.c does not query MonitorFromWindow"
grep -q 'GetMonitorInfoW' "$win32_c" || fail "win32_window.c does not query GetMonitorInfoW"

mkdir -p "$repo_root/.scratch"
tmp="$(mktemp -d "$repo_root/.scratch/placement-test.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/test_placement.c" << 'EOF'
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include "win32_placement.h"

static int failures = 0;

static void check_rect(
    const char *name,
    struct dxc_placement_rect got,
    int32_t left,
    int32_t top,
    int32_t right,
    int32_t bottom
) {
    if (got.left != left || got.top != top || got.right != right || got.bottom != bottom) {
        fprintf(
            stderr,
            "FAIL [%s]: got {%d, %d, %d, %d}, expected {%d, %d, %d, %d}\n",
            name,
            got.left, got.top, got.right, got.bottom,
            left, top, right, bottom
        );
        failures++;
    }
}

int main(void) {
    /* 1280x800 screen with 48px taskbar: rcWork = {0, 0, 1280, 752}. */
    struct dxc_placement_rect laptop = {0, 0, 1280, 752};

    /* 1. Oversized Ember request (1400x900) clamps to rcWork with caption visible (top == 0). */
    check_rect(
        "oversized-1280x800-taskbar",
        dxc_placement_fit(laptop, 1400, 900, 0, 0, 0, 0, 0),
        0, 0, 1280, 752
    );

    /* 2. 150% and 200% DPI-scaled outer sizes exceeding rcWork clamp to rcWork. */
    struct dxc_placement_rect work_hd = {0, 0, 1280, 672};
    check_rect(
        "dpi-150-clamped",
        dxc_placement_fit(work_hd, 1920, 1080, 0, 0, 0, 0, 0),
        0, 0, 1280, 672
    );
    struct dxc_placement_rect work_fhd = {0, 0, 1920, 1040};
    check_rect(
        "dpi-200-clamped",
        dxc_placement_fit(work_fhd, 2560, 1600, 0, 0, 0, 0, 0),
        0, 0, 1920, 1040
    );

    /* 3. Explicit off-screen positions shift inside rcWork. */
    check_rect(
        "offscreen-negative",
        dxc_placement_fit(laptop, 800, 600, 0, 0, 1, -200, -100),
        0, 0, 800, 600
    );
    check_rect(
        "offscreen-past-bottom-right",
        dxc_placement_fit(laptop, 800, 600, 0, 0, 1, 1000, 500),
        480, 152, 1280, 752
    );
    check_rect(
        "offscreen-oversized",
        dxc_placement_fit(laptop, 1600, 1000, 0, 0, 1, -50, 900),
        0, 0, 1280, 752
    );

    /* 4. Secondary monitor with negative origin {-1920, 0, 0, 1040}. */
    struct dxc_placement_rect secondary = {-1920, 0, 0, 1040};
    check_rect(
        "secondary-centred",
        dxc_placement_fit(secondary, 1000, 700, 0, 0, 0, 0, 0),
        -1460, 170, -460, 870
    );
    check_rect(
        "secondary-clamped",
        dxc_placement_fit(secondary, 2400, 1200, 0, 0, 0, 0, 0),
        -1920, 0, 0, 1040
    );
    check_rect(
        "secondary-offscreen-shifted",
        dxc_placement_fit(secondary, 800, 600, 0, 0, 1, -2500, -100),
        -1920, 0, -1120, 600
    );

    /* 5. Minimum size larger than rcWork yields to rcWork; smaller is respected. */
    check_rect(
        "min-larger-than-work",
        dxc_placement_fit(laptop, 1000, 700, 1600, 900, 0, 0, 0),
        0, 0, 1280, 752
    );
    check_rect(
        "min-smaller-than-work",
        dxc_placement_fit(laptop, 400, 300, 640, 480, 0, 0, 0),
        320, 136, 960, 616
    );

    /* 6. Size that fits stays unchanged and is centred on rcWork. */
    check_rect(
        "fits-centred",
        dxc_placement_fit(laptop, 800, 600, 0, 0, 0, 0, 0),
        240, 76, 1040, 676
    );

    return failures == 0 ? 0 : 1;
}
EOF

if ! cc -std=c11 -Wall -Wextra -Werror -I"$fork_window/windows-shared/c" "$tmp/test_placement.c" -o "$tmp/test_placement"; then
  fail "failed to compile win32_placement.h test driver"
elif ! "$tmp/test_placement"; then
  fail "win32_placement.h test driver reported failures"
fi

if [ "$status" -eq 0 ]; then
  echo "ok: win32_placement.h fits first-window placement into rcWork and win32_window.c wires it"
fi
exit "$status"
