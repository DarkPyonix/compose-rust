#!/usr/bin/env bash
# Refresh the stored baseline of the fork's Compose publication after the pin moved.
#
#   .github/scripts/refresh-compose-baseline.sh <run id of a Compose from fork test run>
#   .github/scripts/refresh-compose-baseline.sh --local <target> <local Maven repository>
#
# The first form takes the manifests the run's two build jobs uploaded (the
# compose-baseline-<target> artifacts, made from the build of the pinned REVISION in
# renderer/scripts/build-compose.sh) and writes them to renderer/scripts/compose-baseline/. The
# second reads a repository that build-compose.sh published on this machine. Run it only after
# the run's other checks passed: the baseline is what later pins are compared with, so it must
# be a publication known to be good. Then commit renderer/scripts/compose-baseline/.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="$repo_root/renderer/scripts/compose-baseline"
mkdir -p "$out"

if [[ ${1:-} == --local ]]; then
  [[ $# -eq 3 ]] || { echo "usage: $0 --local <target> <repository>" >&2; exit 2; }
  revision="$(sed -n 's/^REVISION="\([0-9a-f]\{40\}\)"$/\1/p' "$repo_root/renderer/scripts/build-compose.sh")"
  python3 "$repo_root/.github/scripts/compose-baseline.py" manifest "$3" --revision "$revision" --target "$2" > "$out/$2.manifest"
  echo "wrote $out/$2.manifest"
  exit 0
fi

[[ $# -eq 1 ]] || { echo "usage: $0 <run id> | --local <target> <repository>" >&2; exit 2; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for target in linuxX64 macosArm64; do
  gh run download "$1" -n "compose-baseline-$target" -D "$tmp/$target"
  cp "$tmp/$target/$target.manifest" "$out/$target.manifest"
  echo "wrote $out/$target.manifest ($(sed -n 's/^# revision: //p' "$out/$target.manifest"))"
done
