#!/usr/bin/env bash
# Fails if the web page cannot start a Host module larger than 8MB.
#
# Chromium refuses to compile or instantiate a module over 8MB synchronously on the page's
# main thread: "WebAssembly.Instance is disallowed on the main thread, if the buffer size is
# larger than 8MB. Use WebAssembly.instantiate". A Host that carries an HTML engine is over
# that, and a page that tried the synchronous form stayed on its spinner. Node has no such
# limit, so this puts the same rule in front of Node's WebAssembly and runs the generated
# start-up against it: the loader module as it is checked in, then the generated
# `installHost` body, with a stand-in Renderer module (its memory and its two exports) and a
# stand-in Host whose bytes are padded past 8MB with a custom section.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

if ! command -v node >/dev/null 2>&1; then
    echo "skip  the web Host's start-up (no node on PATH)"
    exit 0
fi

loader="$repo_root/renderer/web/resources/compose-rust-host.gen.mjs"
bridge="$repo_root/renderer/web/src/bridge/HostBridge.gen.kt"

# The start-up, run in Node. Read into a variable rather than inlined in a command
# substitution, because macOS's bash 3.2 miscounts quotes in a heredoc inside "$(...)".
read -r -d '' start_up <<'JS' || true
const [loaderPath, bridgePath, scenario] = process.argv.slice(1);
const { readFileSync } = await import('node:fs');
const { pathToFileURL } = await import('node:url');

const LIMIT = 8 * 1024 * 1024;

// Chromium's rule, put in front of Node's WebAssembly. A module remembers how many bytes
// it was compiled from, and the synchronous forms refuse anything over the limit.
const sizes = new WeakMap();
const Original = {
  Module: WebAssembly.Module,
  Instance: WebAssembly.Instance,
  compile: WebAssembly.compile.bind(WebAssembly),
  instantiate: WebAssembly.instantiate.bind(WebAssembly),
};
const byteLength = (bytes) => bytes.byteLength;
WebAssembly.Module = function (bytes, ...rest) {
  if (byteLength(bytes) > LIMIT) {
    throw new RangeError('WebAssembly.Compile is disallowed on the main thread, if the ' +
      'buffer size is larger than 8MB. Use WebAssembly.compile, or compile on a worker ' +
      'thread.');
  }
  const module = new Original.Module(bytes, ...rest);
  sizes.set(module, byteLength(bytes));
  return module;
};
WebAssembly.Instance = function (module, imports) {
  if ((sizes.get(module) ?? 0) > LIMIT) {
    throw new RangeError('WebAssembly.Instance is disallowed on the main thread, if the ' +
      'buffer size is larger than 8MB. Use WebAssembly.instantiate.');
  }
  return new Original.Instance(module, imports);
};
WebAssembly.compile = async (bytes, ...rest) => {
  const module = await Original.compile(bytes, ...rest);
  sizes.set(module, byteLength(bytes));
  return module;
};
WebAssembly.compileStreaming = async (source) => {
  const response = await source;
  return WebAssembly.compile(await response.arrayBuffer());
};

// What the generated text says, read out of it rather than restated here.
const bridge = readFileSync(bridgePath, 'utf8');
const bodyStart = bridge.indexOf('@JsFun(\n    """') + '@JsFun(\n    """'.length;
const body = bridge.slice(bodyStart, bridge.indexOf('""",', bodyStart));
const startSymbol = /const block = host\.([A-Za-z0-9_]+)\(\);/.exec(body)[1];
const regionBase = Number(/if \(block < (\d+)\)/.exec(body)[1]);
const minPages = Number(/memory\.grow\((\d+) - pages\)/.exec(body)[1]);
const slots = [...body.matchAll(/table\.set\((\d+), host\.([A-Za-z0-9_]+)\);/g)]
  .map((match) => [Number(match[1]), match[2]]);
if (slots.length === 0) throw new Error('no table slots in ' + bridgePath);

// A stand-in Host: imports env.memory with the minimum the bridge grows to, exports the
// start symbol (answering an address inside the Host's region) and one function for every
// slot, and carries a custom section that takes it past the limit.
const uleb = (value) => {
  const out = [];
  do {
    let byte = value & 0x7f;
    value >>>= 7;
    if (value !== 0) byte |= 0x80;
    out.push(byte);
  } while (value !== 0);
  return out;
};
const sleb = (value) => {
  const out = [];
  for (;;) {
    const byte = value & 0x7f;
    value >>= 7;
    if ((value === 0 && !(byte & 0x40)) || (value === -1 && (byte & 0x40))) {
      out.push(byte);
      return out;
    }
    out.push(byte | 0x80);
  }
};
const name = (text) => [...uleb(Buffer.byteLength(text)), ...Buffer.from(text)];
const section = (id, content) => [id, ...uleb(content.length), ...content];
const exported = [startSymbol, ...slots.map(([, symbol]) => symbol)];
const types = [1, 0x60, 0, 1, 0x7f];
const imports = [1, ...name('env'), ...name('memory'), 0x02, 0x00, ...uleb(minPages)];
const functions = [...uleb(exported.length), ...exported.map(() => 0)];
const exports = [...uleb(exported.length),
  ...exported.flatMap((symbol, index) => [...name(symbol), 0x00, ...uleb(index)])];
const bodies = exported.map((symbol) => {
  const value = symbol === startSymbol ? regionBase + 16 : 0;
  const code = [0, 0x41, ...sleb(value), 0x0b];
  return [...uleb(code.length), ...code];
});
const code = [...uleb(exported.length), ...bodies.flat()];
const header = Buffer.from([
  0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
  ...section(1, types), ...section(2, imports), ...section(3, functions),
  ...section(7, exports), ...section(10, code),
]);
const padding = Buffer.alloc(LIMIT + 1024 * 1024);
const customName = Buffer.from(name('padding'));
const customLength = Buffer.from(uleb(customName.length + padding.length));
const host = Buffer.concat([header, Buffer.from([0]), customLength, customName, padding]);
if (host.byteLength <= LIMIT) throw new Error('the stand-in Host is not over 8MB');

// The rule is real: the synchronous form refuses this module, so a pass below means the
// generated start-up did not use it.
let refused = false;
try {
  new WebAssembly.Instance(await WebAssembly.compile(host), {});
} catch (error) {
  refused = error instanceof RangeError;
}
if (!refused) throw new Error('the stand-in for Chromium did not refuse a synchronous instantiation');

// The page fetches the Host from beside the loader.
globalThis.fetch = async (url) => {
  if (!String(url).endsWith('/compose_rust_host.wasm')) throw new Error('unexpected fetch ' + url);
  if (scenario === 'no-host') throw new TypeError('Failed to fetch');
  return new Response(host, { headers: { 'content-type': 'application/wasm' } });
};
const info = console.info;
console.info = () => {};
await import(pathToFileURL(loaderPath).href);
console.info = info;

// The Renderer's module: its memory starts at zero pages, as Kotlin's does, and it exports
// the two functions the Host binds to.
const wasmExports = {
  memory: new WebAssembly.Memory({ initial: 0 }),
  compose_rust_renderer_request_frame: () => {},
  compose_rust_renderer_measure: () => 0,
};
const installHost = new Function('wasmExports', 'return (' + body + ');')(wasmExports);
const answer = installHost();
if (!(answer instanceof Promise)) {
  throw new Error('installHost answered ' + answer + ' instead of a promise');
}
const block = await answer;
if (scenario === 'no-host') {
  if (block !== 0) throw new Error('a page without a Host answered ' + block + ', not 0');
  console.log('ok');
  process.exit(0);
}
if (block !== regionBase + 16) {
  throw new Error('the Host reported ' + block + ', not the block it returned');
}
const table = globalThis.__composeRustHostTable;
for (const [slot, symbol] of slots) {
  if (table.get(slot) === null) throw new Error('slot ' + slot + ' (' + symbol + ') is empty');
}
console.log('ok');
JS

run() {
    node --input-type=module -e "$start_up" "$loader" "$bridge" "$1"
}

failures=0

pr6_a_host_module_over_8mb_starts() {
    run host
}

nfr5_a_page_without_a_host_still_starts() {
    run no-host
}

for test in pr6_a_host_module_over_8mb_starts nfr5_a_page_without_a_host_still_starts; do
    if output="$("$test" 2>&1)" && [[ "$output" == ok ]]; then
        echo "ok    $test"
    else
        echo "FAIL  $test" >&2
        echo "$output" | sed 's/^/      /' >&2
        failures=$((failures + 1))
    fi
done

exit "$failures"
