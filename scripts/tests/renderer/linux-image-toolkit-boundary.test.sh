#!/usr/bin/env bash
# Run after build-native-linux.sh, which asks Native Image for its call tree.
#
# The Linux image is built with the toolkit switched off (compose.awt=false, so the fork's
# desktop code never names it, and dxc.toolkit.window=false). Native Image's analysis still walks
# every branch of code it can reach, so a java.awt class stays in the image when some branch
# names one. A frame of application code that calls into the toolkit is a new way in, and fails
# here with the frame.
#
# Nothing is allowed: with the fork's ExtendedAwt and skiko's AwtSwitch off, no frame of application
# code calls into java.awt or Swing. (Native Image still keeps some toolkit types, reached through
# the JDK's own classes and not through any call of ours, so -H:ReportAnalysisForbiddenType cannot
# be the guard; the staged libraries are removed and the smoke test runs without them.)
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
lib="${1:-$repo_root/renderer/build/native-image-linux/dist/lib}"
report="$(ls "$lib"/reports/call_tree_*.txt 2>/dev/null | head -1 || true)"
[[ -n "$report" ]] || { echo "FAIL: no call tree report under $lib/reports; build-native-linux.sh asks for one" >&2; exit 1; }

python3 - "$report" <<'PY'
import re
import sys

allowed = ()
jdk = re.compile(r"^directly calls null:(java\.awt|javax\.swing|javax\.accessibility|sun\.java2d\.marlin)")
stack = []
found = {}
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    m = re.match(r"^([ │]*)(?:├|└)── (.*)$", line.rstrip("\n"))
    if not m:
        continue
    depth, text = len(m.group(1)), m.group(2)
    while stack and stack[-1][0] >= depth:
        stack.pop()
    stack.append((depth, text))
    if not jdk.search(text):
        continue
    # Only a direct call from application code. Deeper in, the tree is the analysis assuming that
    # any override of a method it can reach is reachable too, which says nothing about us.
    app = stack[-2][1] if len(stack) > 1 else None
    if app is None or not ("NativeImageClassLoader:" in app or app.startswith("entry app")):
        continue
    key = re.sub(r"^(entry |directly calls |virtually calls )", "", app)
    key = key.replace("com.oracle.svm.hosted.NativeImageClassLoader:", "")
    found.setdefault(key, text)

bad = {k: v for k, v in found.items() if not any(a in k for a in allowed)}
if bad:
    print("FAIL: application code reaches the Java toolkit in the Linux image:", file=sys.stderr)
    for k, v in bad.items():
        print("  " + k[:200] + "\n      calls " + v[:160], file=sys.stderr)
    sys.exit(1)
print("no application code calls into the toolkit in the Linux image: ok (%d seen)" % len(found))
PY
status=$?
# The report is large and is not part of what ships.
rm -rf "$lib/reports"
exit $status
