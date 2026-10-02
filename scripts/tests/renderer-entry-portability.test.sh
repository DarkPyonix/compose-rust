#!/usr/bin/env bash
# The renderer entry point is one file compiled on three platforms, so everything it says
# to Windows has to sit inside a Windows guard.
#
# Nothing catches a guard in the wrong place except compiling the file somewhere that is
# not Windows, and that means the renderer build: tens of minutes, a machine-wide cache,
# and the one thing a background agent is not allowed to run. So the mistake reaches the
# branch and is found by whoever builds next. It already has: the console helper's guard
# was closed one function too early, which left the DPI declaration bare, and the macOS
# build stopped with twenty errors about WINAPI and HMODULE.
#
# This reads the file instead. It tracks which preprocessor branch each line is in and
# fails when a Win32 name appears on a line that Windows is not the only reader of.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

entry="dioxus-compose-renderer/desktop/c/renderer_entry.c"
status=0

if [ ! -f "$entry" ]; then
  echo "FAIL: $entry is missing"
  exit 1
fi

# The names below come from the Win32 headers and from nowhere else, so a line mentioning
# one of them only compiles where those headers were included.
python3 - "$entry" <<'PY'
import re
import sys

path = sys.argv[1]
win32_names = [
    "AttachConsole", "ATTACH_PARENT_PROCESS", "freopen_s",
    "LoadLibraryW", "FreeLibrary", "GetProcAddress", "GetModuleHandleW",
    "HMODULE", "WINAPI", "HRESULT", "S_OK", "BOOL",
    "SetProcessDPIAware", "SetProcessDpiAwareness", "SetProcessDpiAwarenessContext",
]
pattern = re.compile(r"\b(" + "|".join(win32_names) + r")\b")

# Each entry is True when the branch is reached only on Windows.
stack = []
failures = []

for number, line in enumerate(open(path), start=1):
    stripped = line.strip()
    if stripped.startswith("#ifdef _WIN32"):
        stack.append(True)
        continue
    if stripped.startswith("#ifndef _WIN32"):
        stack.append(False)
        continue
    if stripped.startswith(("#ifdef", "#ifndef", "#if ")):
        stack.append(False)
        continue
    if stripped.startswith("#else"):
        if stack:
            stack[-1] = not stack[-1]
        continue
    if stripped.startswith("#endif"):
        if stack:
            stack.pop()
        continue

    if any(stack):
        continue
    found = pattern.search(line)
    if found:
        failures.append((number, found.group(1), stripped))

if failures:
    print("FAIL: Win32 names outside a _WIN32 guard in " + path)
    for number, name, text in failures:
        print("  line %d: %s in: %s" % (number, name, text))
    sys.exit(1)

print("ok: every Win32 name in " + path + " is inside a Windows guard")
PY
status=$?

exit "$status"
