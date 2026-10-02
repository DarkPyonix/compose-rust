// The `host` module the Kotlin/Wasm glue imports.
//
// Kotlin's `@WasmImport("host", "bump")` becomes a wasm import from module
// "host", and the generated import object resolves it with a plain ES module
// import (`import * as host from 'host'`). An import map points that specifier
// here, so whatever this file exports is what Kotlin's imports bind to.
//
// Two wiring modes, selected by ?mode= in the URL:
//
//   mode=direct  The Rust module is instantiated first and owns the memory.
//                This file re-exports Rust's *WebAssembly exported functions*,
//                so Kotlin's imports bind straight to Rust code objects and the
//                engine has no reason to build a JS frame.
//                Cost: Rust's own `renderer.renderer_bump` import cannot be the
//                real Kotlin export yet (Kotlin does not exist), so it gets a JS
//                stub. This is the instantiation cycle in the flesh.
//
//   mode=shim    Kotlin is instantiated first, so this file can only export JS
//                closures that forward to a Rust instance created afterwards.
//                That lets the Rust module import Kotlin's exported `memory`,
//                which is the only way the two modules can share one linear
//                memory - at the price of a JS frame on every call.
//
// Same Kotlin binary in both modes, so `bench_import` measures the direct call
// in one and the JS-shim call in the other.

const params = new URLSearchParams(location.search);
export const mode = params.get("mode") === "shim" ? "shim" : "direct";

/** Filled in once the Rust module is instantiated. */
let rust = null;
export function rustInstance() {
  return rust;
}

/** Rust's memory in mode=direct; Kotlin's memory in mode=shim. */
let sharedMemory = null;
export function sharedMemoryObject() {
  return sharedMemory;
}

async function compileRust(file) {
  return WebAssembly.compileStreaming(fetch(new URL(file, import.meta.url)));
}

// A stub for Rust's `renderer.renderer_bump` import. In mode=direct it is the
// only thing available, because Kotlin is instantiated after Rust.
let rendererBumpTarget = (x) => x + 1;
export function setRendererBumpTarget(fn) {
  rendererBumpTarget = fn;
}

/** Instantiates the Rust module that defines and exports its own memory. */
async function instantiateRustOwningMemory() {
  const module = await compileRust("./host_owns_memory.wasm");
  const instance = await WebAssembly.instantiate(module, {
    renderer: { renderer_bump: (x) => rendererBumpTarget(x) },
  });
  rust = instance.exports;
  sharedMemory = rust.memory;
  return rust;
}

/** Instantiates the Rust module that imports `env.memory` from Kotlin. */
export async function instantiateRustOnMemory(memory) {
  const module = await compileRust("./host_imports_memory.wasm");
  // The Rust module declares a minimum of 17 pages of imported memory; Kotlin's
  // memory starts at 0 pages, so it has to be grown before the types match.
  // This is wiring at instantiation, which PR-6 allows.
  const pagesNeeded = 17;
  const current = memory.buffer.byteLength / 65536;
  if (current < pagesNeeded) memory.grow(pagesNeeded - current);
  const instance = await WebAssembly.instantiate(module, {
    env: { memory },
    renderer: { renderer_bump: (x) => rendererBumpTarget(x) },
  });
  rust = instance.exports;
  sharedMemory = memory;
  return rust;
}

// --- What Kotlin's wasm imports bind to -----------------------------------

let exportedBump;
let exportedArenaPtr;
let exportedWritePattern;
let exportedChecksum;

if (mode === "direct") {
  await instantiateRustOwningMemory();
  // WebAssembly exported functions, handed straight to the next instantiation.
  exportedBump = rust.bump;
  exportedArenaPtr = rust.arena_ptr;
  exportedWritePattern = rust.write_pattern;
  exportedChecksum = rust.checksum;
} else {
  // JS closures: Rust does not exist yet and cannot exist yet, because it needs
  // the memory that only Kotlin's instantiation creates.
  exportedBump = (x) => rust.bump(x);
  exportedArenaPtr = () => rust.arena_ptr();
  exportedWritePattern = (len, seed) => rust.write_pattern(len, seed);
  exportedChecksum = (len) => rust.checksum(len);
}

export const bump = exportedBump;
export const arena_ptr = exportedArenaPtr;
export const write_pattern = exportedWritePattern;
export const checksum = exportedChecksum;

// The explicit JS shim Kotlin's `@JsFun` benchmark calls, always a JS frame.
globalThis.__jsShimBump = (x) => rust.bump(x);
