#!/usr/bin/env bash
# Fails if a sample names a colour or a size instead of a role.
#
# The samples are what someone copies. A hex colour in one of them is a colour that does
# not change with the design system, and a dp constant is a size that does not change with
# it either: both quietly undo the thing the role vocabulary is for. Where the reference
# needs something the roles cannot say, the role is added to the schema and to every design
# system rather than worked around in the sample.
#
# Comments are exempt. Explaining why two colours were too close to tell apart needs the
# two colours, and the explanation is not what gets drawn.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

sources="$(git ls-files \
    'samples/todo/src/*.rs' \
    'samples/notepad/src/*.rs' \
    'samples/calculator/src/*.rs' \
    'samples/chat/src/*.rs')"
status=0

# A 24 or 32 bit colour literal, or the two ways to build one.
colours="$(grep -nE '0x[0-9a-fA-F]{6}|Color::(rgb|argb)|Paint::Literal' $sources \
    | grep -vE '^[^:]+:[0-9]+:\s*(//|///)' || true)"
if [[ -n "$colours" ]]; then
    echo "error: a sample names a colour instead of a ColorRole" >&2
    echo "$colours" >&2
    status=1
fi

# A size given to a widget as a number. `weight` is a share rather than a size, and the
# window measures come from the size class, so both stay.
sizes="$(grep -nE '^\s*(padding|spacing|corner_radius|elevation|border_width|width|height|font_size|line_height|letter_spacing):\s*-?[0-9]' $sources \
    | grep -vE '^[^:]+:[0-9]+:\s*(//|///)' || true)"
if [[ -n "$sizes" ]]; then
    echo "error: a sample gives a widget a size in dp instead of a role" >&2
    echo "$sizes" >&2
    status=1
fi

if [[ $status -eq 0 ]]; then
    echo "ok    samples speak in roles"
fi
exit $status
