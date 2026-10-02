#!/usr/bin/env bash
# Fails if the Host's wasm module imports something the page does not supply.
#
# A browser refuses to instantiate a module whose imports are not all satisfied, whether or
# not anything calls them. So a dependency that adds an import turns the page blank, with
# nothing on the console but the failed instantiation, and the cause is a crate three levels
# down. This compares the module's imports against what the generated loader answers.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
export PATH="$HOME/.cargo/bin:$PATH"

target=wasm32-unknown-unknown
if ! command -v rustup >/dev/null 2>&1 ||
    ! rustup target list --installed | grep -qx "$target"; then
    echo "skip  the web Host's imports (rustup target add $target)"
    exit 0
fi

module=dioxus-compose-renderer/web/resources/dioxus_compose_host.wasm
dioxus-compose-renderer/web/scripts/build-host.sh >/dev/null

# The namespaces the instantiation supplies, read out of the generated file rather than
# listed here, so the two cannot disagree about what "supplied" means. It is in the Kotlin
# because only the Renderer's own module can name the memory and the export the Host binds
# to; the page's loader only compiles.
loader=dioxus-compose-renderer/web/src/bridge/HostBridge.gen.kt
supplied="$(python3 - "$loader" <<'PYLOADER'
import re
import sys

source = open(sys.argv[1]).read()
opening = "new WebAssembly.Instance(compiled, {"
start = source.index(opening) + len(opening)
block = source[start:source.index("}).exports;", start)]
# The keys of the import object itself, which are the ones at its own indentation.
keys = re.findall(r"^    ([A-Za-z_][A-Za-z0-9_]*):", block, re.MULTILINE)
for key in sorted(set(keys)):
    print(key)
PYLOADER
)"
if [[ -z "$supplied" ]]; then
    echo "error: could not read the import namespaces out of $loader" >&2
    exit 1
fi

# The module's own imports, by namespace. Parsed here rather than with a wasm tool so the
# gate needs nothing installed.
declared="$(python3 - "$module" <<'PY'
import sys

data = open(sys.argv[1], "rb").read()
assert data[:4] == b"\0asm", "not a wasm module"
position = 8


def varuint():
    global position
    value = 0
    shift = 0
    while True:
        byte = data[position]
        position += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value
        shift += 7


def name():
    global position
    length = varuint()
    text = data[position:position + length].decode()
    position += length
    return text


namespaces = set()
memory_import = None
while position < len(data):
    section = data[position]
    position += 1
    size = varuint()
    end = position + size
    if section == 2:
        for _ in range(varuint()):
            namespace = name()
            field = name()
            namespaces.add(namespace)
            kind = data[position]
            position += 1
            if kind == 2:
                memory_import = f"{namespace}.{field}"
            if kind == 0:
                varuint()
            elif kind == 1:
                position += 1
                flags = varuint()
                varuint()
                if flags & 1:
                    varuint()
            elif kind == 2:
                flags = varuint()
                varuint()
                if flags & 1:
                    varuint()
            elif kind == 3:
                position += 2
            else:
                raise SystemExit(f"unknown import kind {kind}")
    position = end

# The Renderer defines the one linear memory and this module imports it. A module that
# defined its own would share nothing, and every address that crossed would name a byte in
# the wrong memory.
if memory_import is None:
    raise SystemExit(
        "the Host's module defines its own memory instead of importing one. "
        "It has to be linked with --import-memory."
    )
print(f"# memory: {memory_import}", file=sys.stderr)

for namespace in sorted(namespaces):
    print(namespace)
PY
)"

missing="$(comm -23 <(echo "$declared") <(echo "$supplied") || true)"
if [[ -n "$missing" ]]; then
    echo "error: the Host's module imports namespaces the page does not supply:" >&2
    echo "$missing" | sed 's/^/  /' >&2
    echo "The page would not instantiate it. Either the dependency that added the" >&2
    echo "namespace should not be reachable from a wasm build, or the generated loader" >&2
    echo "has to answer it: see generate_web_loader_js in dioxus-compose/src/codegen.rs." >&2
    exit 1
fi
echo "ok    the web Host imports only what the page supplies ($(echo "$declared" | tr '\n' ' '))"
