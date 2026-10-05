#!/usr/bin/env bash
# Run after build-native-linux.sh, which asks Native Image for its call tree.
#
# The Linux image is built with the toolkit switched off (compose.awt=false, so the fork's
# desktop code never names it, and dxc.toolkit.window=false). Native Image's analysis still walks
# every branch of code it can reach, so a java.awt class stays in the image when some branch
# names one. The ones that remain are listed below, each with its reason. Any other frame of
# application code that calls into the toolkit is a new way in, and fails here with the frame.
#
# Allowed:
#   org.jetbrains.skiko.Actuals_awtKt.setSystemLookAndFeel
#       Skiko's loader calls it only when skiko.rendering.laf.global is true, which is a run-time
#       property and so cannot be folded away. Skiko is not built here, and the loader maps no
#       AWT library when the property is unset.
#   DMarlinRenderingEngine
#       The JDK's own rendering engine factory, registered by Native Image itself.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
lib="${1:-$repo_root/renderer/build/native-image-linux/dist/lib}"
report="$(ls "$lib"/reports/call_tree_*.txt 2>/dev/null | head -1 || true)"
[[ -n "$report" ]] || { echo "FAIL: no call tree report under $lib/reports; build-native-linux.sh asks for one" >&2; exit 1; }

python3 - "$report" <<'PY'
import re
import sys

allowed = ("org.jetbrains.skiko.Actuals_awtKt.setSystemLookAndFeel", "DMarlinRenderingEngine")
jdk = re.compile(r"^directly calls null:(java\.awt|javax\.swing|sun\.awt|sun\.java2d|java\.beans|javax\.accessibility)")
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
    app = next((t for d, t in reversed(stack[:-1]) if "NativeImageClassLoader:" in t or t.startswith("entry app")), None)
    if app is None:
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
print("the Linux image's only ways into the toolkit are the two allowed ones: ok (%d seen)" % len(found))
PY
status=$?
# The report is large and is not part of what ships.
rm -rf "$lib/reports"
exit $status
