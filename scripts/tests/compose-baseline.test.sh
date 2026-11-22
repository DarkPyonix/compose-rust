#!/usr/bin/env bash
# .github/scripts/compose-baseline.py on a small synthetic Maven repository: the same build
# is identical, a version bump alone moves nothing, a changed archive entry fails unless the
# module's sources changed in the fork's diff, and a module that disappears fails.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tool="$repo_root/.github/scripts/compose-baseline.py"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0
check() { if "${@:2}"; then echo "ok   - $1"; else echo "FAIL - $1"; fail=1; fi; }
not() { ! "$@"; }

make_repo() { # <dir> <version> <ui class content>
  python3 -I - "$@" <<'PY'
import os, sys, zipfile
root, ver, ui = sys.argv[1:4]
for m, body in (("foundation/foundation", "f"), ("ui/ui", ui)):
    name = m.split("/")[1]
    d = f"{root}/org/thisisthepy/compose/{m}/{ver}"
    os.makedirs(d)
    with zipfile.ZipFile(f"{d}/{name}-{ver}.jar", "w") as z:
        z.writestr("a.class", body); z.writestr("META-INF/version", ver)
    open(f"{d}/{name}-{ver}.pom", "w").write(f"<version>{ver}</version>")
    open(f"{d}/../maven-metadata-local.xml", "w").write("<lastUpdated>1</lastUpdated>")
PY
}
make_repo "$tmp/base" 1.11.1-ext.5 one
make_repo "$tmp/bump" 1.11.1-ext.6 one
make_repo "$tmp/edit" 1.11.1-ext.6 two
python3 -I "$tool" manifest "$tmp/base" --revision 0123456789012345678901234567890123456789 --target t > "$tmp/m"
: > "$tmp/none"
echo "compose/ui/ui/src/commonMain/A.kt" > "$tmp/ui"
echo "compose/foundation/foundation/src/commonMain/A.kt" > "$tmp/found"
check "the baseline records the revision" grep -q '^# revision: 0123456789012345678901234567890123456789$' "$tmp/m"
check "the same build is identical" python3 -I "$tool" check "$tmp/base" "$tmp/m" --changed-from-diff "$tmp/none"
check "a version bump alone is identical" python3 -I "$tool" check "$tmp/bump" "$tmp/m" --changed-from-diff "$tmp/none"
check "a changed module fails with no changed sources" not python3 -I "$tool" check "$tmp/edit" "$tmp/m" --changed-from-diff "$tmp/none"
check "a changed module fails when other sources changed" not python3 -I "$tool" check "$tmp/edit" "$tmp/m" --changed-from-diff "$tmp/found"
check "a changed module passes when its sources changed" python3 -I "$tool" check "$tmp/edit" "$tmp/m" --changed-from-diff "$tmp/ui"
rm -rf "$tmp/edit/org/thisisthepy/compose/ui"
check "a missing module fails" not python3 -I "$tool" check "$tmp/edit" "$tmp/m" --changed-from-diff "$tmp/found"
[[ $fail -eq 0 ]] && echo "all passed"
exit $fail
