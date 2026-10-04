# Web interop through a wasm trampoline

Kotlin to Rust calls in the browser with no JavaScript on the call path, while
both modules share one linear memory. The measurement and its comparison with
the JS forwarder are written up in [`../README.md`](../README.md); this file
says what is here and how to run it.

The experiment was first built on 2026-09-20 and measured once in Safari, then
lost when its worktree was removed. It was recovered from the salvaged patch,
brought into this repository on 2026-10-03 with the boundary symbols renamed to
`compose_rust_trampoline_*` and the Kotlin moved to the
`dev.darkpyonix.composerust.experiments.webinterop` package, and remeasured in
Safari and Chrome.

## The question

The Rust Host and the Kotlin/Wasm Renderer have to share one linear memory and
call each other. Kotlin/Wasm can only define and export its own memory, so Rust
imports Kotlin's. That forces Kotlin to be instantiated first, before any Rust
export exists for Kotlin to import. The first version of `../README.md`
concluded that only a JS closure on the Kotlin to Rust edge could break that
cycle, and ruled out a funcref table with `call_indirect` because Kotlin/Wasm
cannot declare an imported table.

## What this experiment does instead

Kotlin does not need to declare the table. A third, hand-assembled wasm module
(`build-trampoline.py` writes `dist/trampoline.wasm`, 109 bytes) imports a
`WebAssembly.Table` and exports one function per boundary call, each a
`call_indirect` into its slot. Instantiation order:

    table (JS) -> trampoline -> Kotlin (imports the trampoline) -> Rust (imports Kotlin's memory) -> table.set(Rust exports)

JS only wires the modules together at instantiation. After that a Kotlin to
Rust call is `call` plus `call_indirect`, entirely inside wasm. The Rust to
Kotlin direction needs no trampoline: Rust is instantiated last and imports
Kotlin's export directly.

The same Kotlin module also calls the same Rust export through a `@JsFun`
arrow function, the shape the generated web boundary uses today, so both paths
are measured in one page load under identical conditions.

## Result (2026-10-03)

Net ns per call, median of 7 page loads; raw data in `results/`.

| Edge | Safari 26.5 | Chrome 154 |
|---|---:|---:|
| Kotlin -> Rust, trampoline | 4.35 | 7.11 |
| Kotlin -> JS -> Rust | 12.45 | 25.57 |
| Rust -> Kotlin, direct import | 1.55 | 2.36 |
| Rust -> JS -> Kotlin | 12.65 | 13.46 |

All four shared-memory checks pass in every run.

## Files

| Path | What it is |
|---|---|
| `rust/` | Standalone crate (own empty `[workspace]`), built twice: `rust_owner.wasm` exports its memory, `rust_importer.wasm` imports `env.memory` and sits above 16 MiB with `--global-base` |
| `kotlin/` | Amper project with one `wasm-js/app` module, `kotlin-renderer`; `kotlin/kotlin` is a copy of `renderer/kotlin` |
| `build-trampoline.py` | Writes `dist/trampoline.wasm` byte by byte |
| `dist/index.html`, `dist/harness.mjs` | Instantiates and wires the four modules, runs the checks and the benchmarks, POSTs the results |
| `dist/rust-imports.mjs` | The `rust` module Kotlin's import object resolves: the table and the trampoline's exports |
| `run-bench.py` | Serves `dist/`, loads the page once per run in Safari or Chrome, writes `results/<label>.json` |
| `serve.py` | Serves `dist/` for one run opened by hand; writes `results.json` |
| `wasm-sections.py` | Prints a module's imports, exports and memories without a browser |
| `results/` | Raw JSON, one entry per run: `safari.json`, `chrome-headless-shell.json`, and `safari-high-load.json` (taken while the machine was loaded; not used for the table) |

## Running it

    ./build-rust.sh                 # cargo test, then the two wasm32 variants into dist/
    ./build-kotlin.sh               # the Kotlin/Wasm module into dist/
    uv run build-trampoline.py     # dist/trampoline.wasm
    uv run run-bench.py safari --runs 7
    uv run run-bench.py chrome --runs 7 --chrome <chrome or chrome-headless-shell binary>

`run-bench.py --n` sets the calls per sample (default 20,000,000) and `--reps`
the samples per edge (default 9).

Nothing outside this directory depends on it, and the root workspace and
`scripts/check.sh` do not see the crate.
