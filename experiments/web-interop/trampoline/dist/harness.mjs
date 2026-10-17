// Web-interop trampoline harness.
//
// Wiring order (JS is present only here, at instantiation):
//   1. `rust-imports.mjs` creates a Table and instantiates the trampoline.
//   2. importing `kotlin-renderer.mjs` instantiates the Kotlin module, whose `rust.*`
//      imports are bound to the trampoline's exports.
//   3. Kotlin's exported `memory` is grown past the Rust image base (16 MiB).
//   4. The Rust module is instantiated with `env.memory` = Kotlin's memory and
//      `peer.peer_add` = Kotlin's export.
//   5. The trampoline table is filled with the Rust exports.
//
// After step 5 no JS frame exists on any boundary call except the ones
// deliberately routed through a shim for comparison.

import * as kotlin from "./kotlin-renderer.mjs";
import { bindRust } from "rust";

const log = [];
function say(line) {
    log.push(line);
    const pre = document.getElementById("out");
    if (pre) pre.textContent = log.join("\n");
    console.log(line);
}

const RUST_BASE_PAGES = (16 * 1024 * 1024) / 65536; // --global-base
const HEADROOM_PAGES = 512; // 32 MiB total

// ---------------------------------------------------------------------------
// Instantiation
// ---------------------------------------------------------------------------

const memory = kotlin.memory;
const pagesBefore = memory.buffer.byteLength / 65536;
memory.grow(Math.max(0, RUST_BASE_PAGES + HEADROOM_PAGES - pagesBefore));
say(`kotlin memory: ${pagesBefore} pages at start, ${memory.buffer.byteLength / 65536} after grow`);

const jsShimCalls = { n: 0 };
const rustModule = await WebAssembly.compileStreaming(fetch(new URL("./rust_importer.wasm", import.meta.url)));
say(`rust imports: ${WebAssembly.Module.imports(rustModule).map((i) => `${i.module}.${i.name}:${i.kind}`).join(", ")}`);

const rust = (await WebAssembly.instantiate(rustModule, {
    env: { memory },
    peer: { peer_add: kotlin.peer_add },
    jsshim: { shim_add: (a, b) => kotlin.peer_add(a, b) },
})).exports;

bindRust(rust);
globalThis.__composeRustAdd = rust.compose_rust_trampoline_add;

say(`sentinel after rust instantiation: kotlin_sentinel() = ${kotlin.kotlin_sentinel()}`);

const arena = rust.compose_rust_trampoline_arena_ptr();
const arenaLen = rust.compose_rust_trampoline_arena_len();
say(`arena at 0x${arena.toString(16)} (${arenaLen} bytes) inside the shared memory`);

// ---------------------------------------------------------------------------
// Correctness: shared linear memory
// ---------------------------------------------------------------------------

const results = { ua: navigator.userAgent, label: new URLSearchParams(location.search).get("label") ?? "", checks: [], benches: [] };

function check(name, actual, expected) {
    const ok = actual === expected;
    results.checks.push({ name, actual, expected, ok });
    say(`${ok ? "PASS" : "FAIL"} ${name}: got ${actual}, expected ${expected}`);
}

const WORDS = 256;
const expectedSum = (seed) => {
    let s = 0;
    for (let i = 0; i < WORDS; i++) s = (s + seed + i) | 0;
    return s | 0;
};

// Rust writes, Kotlin reads.
rust.compose_rust_trampoline_fill(arena, WORDS, 7);
check("kotlin reads what rust wrote", kotlin.kotlin_sum(arena, WORDS), expectedSum(7));

// Kotlin writes, Rust reads.
kotlin.kotlin_fill(arena, WORDS, 11);
check("rust reads what kotlin wrote", rust.compose_rust_trampoline_sum(arena, WORDS), expectedSum(11));

// JS sees the same bytes.
const view = new Int32Array(memory.buffer, arena, WORDS);
check("js sees the same memory", view[3], 14);

// Full round trip driven from inside Kotlin.
check("round trip inside kotlin", kotlin.round_trip(arena, WORDS, 3), expectedSum(3));

// ---------------------------------------------------------------------------
// Benchmarks
// ---------------------------------------------------------------------------

function bench(name, fn, n, reps = REPS_PER_EDGE) {
    fn(n); // warmup
    fn(n);
    const samples = [];
    for (let r = 0; r < reps; r++) {
        const t0 = performance.now();
        const sink = fn(n);
        const t1 = performance.now();
        samples.push((t1 - t0) * 1e6 / n); // ns per iteration
        globalThis.__sink = sink; // keep the result observable
    }
    samples.sort((a, b) => a - b);
    const median = samples[(samples.length / 2) | 0];
    results.benches.push({ name, n, nsPerOp: median, samples });
    say(`${name.padEnd(34)} ${median.toFixed(2)} ns/op  (n=${n}, min ${samples[0].toFixed(2)}, max ${samples[samples.length - 1].toFixed(2)})`);
    return median;
}

// `?n=` sets the calls per sample and `?reps=` the samples per edge. The
// defaults keep one sample at tens of milliseconds, well above the coarsest
// timer a browser hands a page (Safari clamps performance.now() to 1 ms
// outside a cross-origin isolated context).
const params = new URLSearchParams(location.search);
const N = Number(params.get("n") ?? 20_000_000);
const REPS_PER_EDGE = Number(params.get("reps") ?? 9);

say("\n-- call overhead, loop driven from Kotlin --");
const kLocal = bench("kotlin loop, no call", (n) => kotlin.bench_local(n), N);
const kDirect = bench("kotlin -> rust (trampoline)", (n) => kotlin.bench_direct(n), N);
const kShim = bench("kotlin -> js -> rust", (n) => kotlin.bench_shim(n), N);

say("\n-- call overhead, loop driven from Rust --");
const rLocal = bench("rust loop, no call", (n) => rust.compose_rust_trampoline_bench_local(n), N);
const rDirect = bench("rust -> kotlin (direct import)", (n) => rust.compose_rust_trampoline_bench_peer(n), N);
const rShim = bench("rust -> js -> kotlin", (n) => rust.compose_rust_trampoline_bench_shim(n), N);

say("\n-- call overhead, loop driven from JS --");
const jsLocal = bench("js loop, no call", (n) => { let a = 0; for (let i = 0; i < n; i++) a = (a + i + 1) | 0; return a; }, N);
const jsRust = bench("js -> rust export", (n) => { let a = 0; for (let i = 0; i < n; i++) a = (a + rust.compose_rust_trampoline_add(i, 1)) | 0; return a; }, N);
const jsKotlin = bench("js -> kotlin export", (n) => { let a = 0; for (let i = 0; i < n; i++) a = (a + kotlin.peer_add(i, 1)) | 0; return a; }, N);

say("\n-- shared linear memory access --");
const REPS = 80000;
const memRead = bench("kotlin read, ns per i32", (r) => kotlin.bench_read(arena, WORDS, r), REPS) / WORDS;
const memWrite = bench("kotlin write, ns per i32", (r) => kotlin.bench_write(arena, WORDS, r), REPS) / WORDS;
const rustRead = bench("rust read, ns per i32", (r) => { let a = 0; for (let i = 0; i < r; i++) a = (a + rust.compose_rust_trampoline_sum(arena, WORDS)) | 0; return a; }, REPS) / WORDS;

results.derived = {
    kotlinToRustDirectNs: kDirect - kLocal,
    kotlinToRustViaJsNs: kShim - kLocal,
    rustToKotlinDirectNs: rDirect - rLocal,
    rustToKotlinViaJsNs: rShim - rLocal,
    jsToRustNs: jsRust - jsLocal,
    jsToKotlinNs: jsKotlin - jsLocal,
    kotlinMemReadNsPerI32: memRead,
    kotlinMemWriteNsPerI32: memWrite,
    rustMemReadNsPerI32: rustRead,
};

say("\n-- net cost per boundary call (loop baseline subtracted) --");
for (const [k, v] of Object.entries(results.derived)) {
    say(`${k.padEnd(34)} ${v.toFixed(2)} ns`);
}

results.log = log;
await fetch(`/results?label=${encodeURIComponent(results.label)}`, { method: "POST", body: JSON.stringify(results, null, 2) }).catch(() => {});
say("\nDONE");
document.title = "DONE";
