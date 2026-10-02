#!/usr/bin/env bash
# The Windows caption is drawn in Kotlin and hit-tested in C, and the two have to agree
# about the same strip.
#
# The native window procedure answers WM_NCHITTEST for the caption: the part of it that
# drags the window, and the part at the trailing edge that stays client area so the
# buttons can be clicked. Kotlin draws into that strip without knowing any of it. If the
# height the two use ever differs, the seam is invisible until someone on Windows finds a
# row of pixels that looks like the caption and does not drag, or buttons that cannot be
# pressed. Neither of those is a crash and neither shows up in CI.
#
# Nothing here needs Windows. Three numbers live in the tree and have to match.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

entry="dioxus-compose-renderer/desktop/c/renderer_entry.c"
chrome="dioxus-compose-renderer/desktop/src/WindowChrome.kt"
rules="dioxus-compose-renderer/desktop/src/renderer/ComponentRules.kt"
status=0

fail() {
  echo "FAIL: $1"
  status=1
}

for file in "$entry" "$chrome" "$rules"; do
  [ -f "$file" ] || { echo "FAIL: $file is missing"; exit 1; }
done

c_height="$(grep -oE '#define DXC_CAPTION_HEIGHT_DIP [0-9]+' "$entry" | grep -oE '[0-9]+$')"
c_button="$(grep -oE '#define DXC_CAPTION_BUTTON_WIDTH_DIP [0-9]+' "$entry" | grep -oE '[0-9]+$')"
c_count="$(grep -oE '#define DXC_CAPTION_BUTTON_COUNT [0-9]+' "$entry" | grep -oE '[0-9]+$')"
kt_height="$(grep -oE 'internal val windowsCaptionHeight = [0-9]+\.dp' "$chrome" | grep -oE '[0-9]+')"

[ -n "$c_height" ] || fail "the native side no longer defines a caption height"
[ -n "$c_button" ] || fail "the native side no longer reserves a button width"
[ -n "$c_count" ] || fail "the native side no longer says how many buttons it reserves for"
[ -n "$kt_height" ] || fail "WindowChrome.kt no longer states the Windows caption height"

if [ -n "$c_height" ] && [ -n "$kt_height" ] && [ "$c_height" != "$kt_height" ]; then
  fail "the caption strip is ${kt_height}dp in Kotlin and ${c_height}dip in C.
  Kotlin draws the caption and C answers the hit test for it, so a window on Windows
  would have a band that looks like caption and behaves like content, or the reverse."
fi

if [ "${c_count:-0}" != "3" ]; then
  fail "the native side reserves room for ${c_count} buttons, and a window has three"
fi

# The reserved width has to be at least the widest a design system actually draws. Less
# than that and the outermost button sits in the region the frame drags, where it cannot
# be pressed at all.
widest="$(grep -oE 'buttonWidth = [0-9]+\.dp' "$rules" | grep -oE '[0-9]+' | sort -n | tail -1)"
if [ -n "$widest" ] && [ -n "$c_button" ] && [ "$c_button" -lt "$widest" ]; then
  fail "the native side reserves ${c_button}dip per button and a design system draws
  ${widest}dp. The buttons past the reserved edge take the frame's drag instead of a
  click, so that window cannot be closed from its own close button."
fi

if [ "$status" -eq 0 ]; then
  echo "ok: the Windows caption is ${kt_height}dp on both sides, with ${c_count} buttons of up to ${c_button}dip reserved"
fi
exit "$status"
