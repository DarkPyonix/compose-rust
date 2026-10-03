# Web interop experiment (SPEC PR-6, PROJECT Q3)

Can a Rust `wasm32-unknown-unknown` module and a Kotlin/Wasm module call each
other directly, and share one linear memory, with JavaScript only wiring the
modules at instantiation?

Nothing in the repository depends on this directory. The Rust crate declares its
own empty `[workspace]`, so the root `cargo` workspace never sees it, and
`scripts/check.sh` is unchanged.

## Verdict (remeasured 2026-10-03)

**A shared memory and a JS-free Kotlin to Rust call can be had together.** The
first version of this experiment (2026-09-20, kept below) said they could not,
and that the Kotlin to Rust edge needed a JS closure. That conclusion was wrong.

- One shared `WebAssembly.Memory`: **yes, owned by Kotlin.** A Kotlin/Wasm module
  always *defines and exports* its own memory and cannot import one, so Rust
  imports Kotlin's (`--import-memory`).
- Rust to Kotlin: **direct.** Rust is instantiated last, so its import is bound
  straight to Kotlin's export.
- Kotlin to Rust: **direct, through a wasm trampoline.** Kotlin is instantiated
  before Rust exists, so it cannot import a Rust export. It imports the exports
  of a third, 109-byte wasm module instead, which imports a `WebAssembly.Table`
  and forwards each call with `call_indirect`. The table is filled with the
  Rust exports once Rust is instantiated. Kotlin never declares the table, so
  the Kotlin/Wasm limitation that ruled this out in the first version does not
  apply. JS wires the modules at instantiation and is on no call path after it.
  See [`trampoline/`](trampoline/).

Measured per call, loop overhead subtracted, median of 7 page loads:

| Kotlin -> Rust | Safari 26.5 | Chrome 154 (headless shell) |
|---|---:|---:|
| **through the wasm trampoline** | **4.35 ns** | **7.11 ns** |
| through a JS forwarder (`@JsFun` arrow function) | 12.45 ns | 25.57 ns |

The trampoline costs about a third of the JS forwarder in both engines. The
result recorded on 2026-09-20 (5.6 ns against 13.2 ns in Safari, one run)
reproduces. Details, raw numbers and the method are in
[Trampoline against JS forwarder](#trampoline-against-js-forwarder-2026-10-03).

## Trampoline against JS forwarder (2026-10-03)

### Setup

| | |
|---|---|
| Date | 2026-10-03 |
| Machine | Mac mini (Macmini9,1), Apple M1, 8 cores, 16 GB |
| OS | macOS 26.5.1 (25F80) |
| Safari | 26.5 (21624.2.5.11.4), JavaScriptCore |
| Chrome | Chrome for Testing 154.0.8037.92, `chrome-headless-shell` build, V8 |
| Rust | 1.98.0, `wasm32-unknown-unknown`, release, LTO |
| Kotlin/Wasm | Amper `./kotlin build`, debug variant (as in the 2026-09-20 run) |

The machine is shared. The recorded runs were taken with a load average of
about 6 to 8 on 8 cores. An earlier Safari set taken at a load average of about
33 is kept as `trampoline/results/safari-high-load.json`; it has the same
medians within 1 ns and three times the spread, and is not used below.

### Method

`trampoline/run-bench.py` serves `trampoline/dist/`, loads the harness page once
per run, and collects the JSON each page POSTs back into
`trampoline/results/<label>.json`. Every run is a fresh page load, so all four
modules (table, trampoline, Kotlin, Rust) are instantiated from scratch each time.

- Safari is driven with `open -a Safari <url>`, the method the first run used.
  (`safari-webdriver` is also supported, but needs "Allow Remote Automation",
  which was off on this machine.)
- Chrome is launched as a new process per run (`chrome-headless-shell <url>`)
  and killed after the page reports. Headless Chrome for Testing with
  `--headless=new` and chromedriver both failed to start on this machine
  (chromedriver: "DevToolsActivePort file doesn't exist"); the headless shell
  carries the same V8 and was stable, so headed Chrome was not needed.

Inside a page, each call edge is a loop that runs *inside wasm*: Kotlin's
`bench_direct` makes 20,000,000 calls to `rust.add` (bound to the trampoline),
and `bench_shim` makes 20,000,000 calls to a `@JsFun("(a, b) => ...")` arrow
function that calls the same Rust export. The only JS on the timed path of the
direct edge is the single call that starts the loop. Each edge is timed with
`performance.now()` over 9 samples after 2 warm-up passes; the per-run value is
the median sample, and the loop with no call in it (`bench_local`) is subtracted.
20,000,000 calls keeps a sample at 25 to 500 ms, far above Safari's 1 ms timer
granularity. The table reports the median of the 7 per-run values and their
range; "all samples" is the min and max over all 63 samples, before the
baseline is subtracted.

All four shared-memory checks pass in every run in both browsers: Kotlin reads
what Rust wrote, Rust reads what Kotlin wrote, JS sees the same bytes, and a
round trip driven from Kotlin returns the expected sum.

### Results

Net ns per call, median of 7 runs (range of the 7 per-run medians):

| Edge | Safari 26.5 | Chrome 154 |
|---|---:|---:|
| **Kotlin -> Rust, wasm trampoline (`call` + `call_indirect`)** | **4.35** (4.30 .. 4.45) | **7.11** (6.97 .. 7.15) |
| Kotlin -> JS forwarder -> Rust | 12.45 (11.95 .. 12.60) | 25.57 (24.91 .. 25.73) |
| Rust -> Kotlin, direct import | 1.55 (1.45 .. 1.55) | 2.36 (2.27 .. 2.39) |
| Rust -> JS -> Kotlin | 12.65 (12.05 .. 15.25) | 13.46 (13.06 .. 13.51) |

Gross ns per loop iteration, before subtraction, median of the 7 runs (all 63
samples min .. max):

| Loop | Safari 26.5 | Chrome 154 |
|---|---:|---:|
| Kotlin loop, no call | 0.70 (0.65 .. 0.80) | 0.39 (0.38 .. 0.56) |
| Kotlin -> Rust, trampoline | 5.05 (4.85 .. 5.25) | 7.51 (7.30 .. 7.99) |
| Kotlin -> JS -> Rust | 13.15 (12.50 .. 13.50) | 25.96 (25.29 .. 26.63) |
| Rust -> Kotlin, direct | 1.55 (1.40 .. 1.65) | 2.36 (2.23 .. 2.49) |
| Rust -> JS -> Kotlin | 12.65 (11.95 .. 15.75) | 13.46 (12.91 .. 19.61) |

(The Rust loop with no call compiles to a closed form and measures 0, so the
Rust rows have nothing to subtract.)

Per-run net values, Kotlin -> Rust:

| Run | Safari trampoline | Safari JS | Chrome trampoline | Chrome JS |
|---|---:|---:|---:|---:|
| 0 | 4.35 | 12.60 | 6.97 | 24.98 |
| 1 | 4.35 | 12.45 | 7.15 | 25.69 |
| 2 | 4.30 | 11.95 | 6.97 | 24.91 |
| 3 | 4.35 | 12.45 | 7.14 | 25.73 |
| 4 | 4.40 | 12.45 | 7.15 | 25.66 |
| 5 | 4.45 | 12.50 | 7.05 | 25.12 |
| 6 | 4.35 | 12.45 | 7.11 | 25.57 |

Shared memory from Kotlin, ns per `i32`: Safari 0.63 read, 0.54 write; Chrome
0.63 read, 0.61 write. Rust reads the same words at 0.20 (Safari) and 0.30
(Chrome).

The rows the harness drives from a JS loop (`js -> rust export`,
`js -> kotlin export`) are in the JSON but not reported: in V8 the JIT treats
the JS baseline loop and the calling loop differently enough that the
subtraction goes negative, so they say nothing about the boundary.

### Does the 2026-09-20 result reproduce?

Yes. The recovered Safari run measured 5.6 ns through the trampoline and 13.2 ns
through JS. Today's Safari medians are 4.35 ns and 12.45 ns: the JS forwarder
lands within 1 ns of both earlier Safari measurements (12.05 and 13.2), and the
trampoline is a little faster than the one recorded run, with all seven runs
inside 4.30 .. 4.45. The ratio holds in V8 as well, where the JS forwarder is
dearer (25.6 ns) and the trampoline costs 7.1 ns.

What the trampoline costs over a plain wasm-to-wasm call (1.45 to 1.55 ns in
Safari) is the second hop and the `call_indirect` signature check: about 3 ns in
Safari and 5 ns in V8.

### Reproducing

    cd trampoline
    ./build-rust.sh                 # cargo test, then the two wasm32 variants into dist/
    ./build-kotlin.sh               # the Kotlin/Wasm module into dist/
    python3 build-trampoline.py     # dist/trampoline.wasm
    python3 run-bench.py safari --runs 7
    python3 run-bench.py chrome --runs 7 --label chrome-headless-shell \
        --chrome <path>/chrome-headless-shell-mac-arm64/chrome-headless-shell

Chrome for Testing and its headless shell were downloaded into the repository's
`.scratch/chrome/`, from the Chrome for Testing `last-known-good-versions`
listing.

## What was built (2026-09-20)

| Path | What it is |
|---|---|
| `rust-exporter/` | Rust crate, `cdylib`, `wasm32-unknown-unknown`. Owns a 64 KiB arena (PR-4), exports `bump`, `arena_ptr`, `write_pattern`, `checksum`, and imports `renderer.renderer_bump`. |
| `kotlin-renderer/` | Standalone Amper `wasm-js/app` module. No Compose, no `//shared`: the question is what the Kotlin/Wasm target itself allows. |
| `harness/index.html` | Instantiates and wires both modules, runs the probes and the benchmarks, POSTs the numbers back. |
| `harness/host-module.mjs` | The `host` ES module that Kotlin's generated import object resolves. Implements both wiring modes. |
| `harness/serve.py` | Serves the harness and writes `results-<mode>.json`. |
| `build.sh` | Builds everything into `harness/`. |
| `trampoline/` | Added 2026-10-03: the same question answered with a `call_indirect` trampoline, with the JS forwarder measured beside it in one harness. See the top of this file. |

`renderer/kotlin` (the Amper/Kotlin CLI wrapper) builds a
standalone Kotlin/Wasm module fine; `kotlin-renderer/kotlin` is a copy of that
wrapper and `kotlin-renderer/module.yaml` is four lines.

### How to reproduce

The rest of this file, down to [What PR-6 should say](#what-pr-6-should-say), is
the first version of this experiment, dated 2026-09-20. Its measurements stand;
its conclusion that the call edge needs JS does not, see the corrections inline.

```sh
./build.sh
uv run harness/serve.py 8765
open -a Safari 'http://127.0.0.1:8765/?mode=direct'
open -a Safari 'http://127.0.0.1:8765/?mode=shim'
```

Measured on **Safari 26.5 (AppleWebKit 605.1.15)**, macOS 26.5.1, Apple silicon
Mac mini. Rust 1.98.0, Kotlin CLI 0.13.0-dev-4399. No other browser is installed
on this machine, so these numbers are WebKit/JavaScriptCore only; V8 and
SpiderMonkey should be re-measured before the SPEC is marked `Agreed`.

## Question 1: can Kotlin/Wasm read and write an imported linear memory?

### It cannot import one

Kotlin/Wasm is WasmGC-based and its objects do not live in linear memory, but it
still *has* a linear memory, for the `kotlin.wasm.unsafe` API. Disassembling the
compiled module shows it is defined locally and exported, never imported:

```wat
(memory (;0;) 0)
(export "memory" (memory 0))
```

There is no annotation, compiler flag or stdlib entry point that turns that into
an import. So the PR-6 wording - Rust owns the arena, Kotlin imports Rust's
memory - is not implementable.

### It can address a memory it did not allocate

The `kotlin.wasm.unsafe` API is usable for arbitrary addresses, with one wrinkle:
`Pointer`'s constructor is `internal`, so an address cannot be named directly.
The workaround is pointer arithmetic from an address the allocator did hand out
(`Pointer.plus` is `UInt` arithmetic, so it reaches lower addresses too) - see
`atAddress` in `kotlin-renderer/src/Probe.kt`.

### So the sharing has to go the other way

Rust *can* import a memory (`-Clink-arg=--import-memory`), and Kotlin exports
one. Wire it that way and the two modules genuinely share a single
`WebAssembly.Memory`:

```
memory_is_the_same_object:            true
round_trip_rust_writes_kotlin_reads:  true   (Rust fills 4 KiB, Kotlin checksums it)
round_trip_kotlin_writes_rust_reads:  true   (Kotlin fills 4 KiB, Rust checksums it)
```

Wire it the way PR-6 describes instead - Rust owns and exports the memory - and
Kotlin reading the arena address is a trap, not a slow path:

```
RuntimeError: Out of bounds memory access
```

Kotlin's own memory starts at 0 pages and has nothing at the arena address. That
is the proof that the two memories are unrelated.

Two practical notes on the working direction:

- Kotlin's memory starts at 0 pages while the Rust module declares a minimum
  (17 pages here), so JS has to `memory.grow()` before instantiating Rust. That
  is wiring at instantiation, which PR-6 already allows.
- Rust's data segments land at its link-time base (`arena_ptr` = 1 MiB here) in
  a memory Kotlin's `kotlin.wasm.unsafe` allocator also hands out addresses in.
  Nothing collided in this experiment, but production use needs the two ranges
  separated deliberately, with `--global-base` / `--stack-first` on the Rust
  side.

## Question 2: is a wasm-to-wasm call actually direct?

Yes. When a Rust `WebAssembly.Instance`'s exported function object is passed
straight into the next instantiation's import object, JavaScriptCore binds the
call edge without a JS frame, and it is roughly four times cheaper than the
JS-to-wasm entry that JS code itself pays.

All numbers are ns/call, best of 9 runs of 20,000,000 calls, with the loop
running *inside* wasm so the only JS on the path is the single call that starts
it. Raw output: `harness/results-direct.json`, `harness/results-shim.json`.

| Call edge | ns/call (min) | ns/call (median) |
|---|---:|---:|
| Kotlin -> Kotlin, same module (baseline) | 0.30 | 0.30 |
| **Kotlin -> Rust, wasm import bound to a wasm export** | **1.45** | **1.50** |
| JS -> Rust, calling an exported function from JS | 1.40 | 1.45 |
| Kotlin -> JS closure -> Rust (`@JsFun` shim) | 12.05 | 12.75 |
| Rust -> JS closure (plain JS function) | 4.35 | 4.50 |
| Rust -> JS closure -> Kotlin export | 12.65 | 13.45 |

The direct edge costs **1.15 ns** more than an in-module call and **10.6 ns**
less than the same call through a JS shim. Confirmation that the difference is
the linkage and not the code: the *same* Kotlin binary, whose `bench_import`
function contains a plain `@WasmImport` call, measures 1.45 ns when the import is
bound to a wasm export and 12.05 ns when it is bound to a JS closure.

### Memory access cost from Kotlin/Wasm

Reading the shared arena from Kotlin, one byte at a time through
`Pointer.loadByte()`:

**0.977 ns/byte**, i.e. **4.0 us per 4 KiB batch** (4096 bytes x 2000 reps).

This is ordinary in-bounds linear memory access, not a copy: nothing is
materialised on the Kotlin heap. A real interpreter reads `u16`/`u32` fields
rather than bytes and would be several times faster per record, so this is a
conservative upper bound.

## The instantiation cycle, and how it is broken

`WebAssembly.instantiate` requires *every* import to be supplied at
instantiation time. Therefore:

- Kotlin cannot be instantiated until whatever it imports exists.
- Rust cannot be instantiated until Kotlin's memory exists.
- Kotlin's memory does not exist until Kotlin is instantiated.

So Kotlin cannot import a Rust export directly. The ways out considered:

- **Single-module linking.** There is no linker that merges a WasmGC module and
  an LLVM linear-memory module. Not available.
- **A third module owning the memory.** Does not help: the blocker is that
  Kotlin cannot import a memory from anyone.
- **A funcref table plus `call_indirect`.** **Works.** The first version of this
  file ruled it out because Kotlin/Wasm cannot declare an imported table or a
  `call_indirect` through one. That is true and beside the point: Kotlin does
  not have to. A third wasm module imports the table and exports one
  `call_indirect` forwarder per boundary function; Kotlin imports those with
  ordinary `@WasmImport` declarations, and the table is filled with the Rust
  exports after Rust is instantiated. Measured at 4.35 ns (Safari) and 7.11 ns
  (Chrome) per call, see [`trampoline/`](trampoline/) and the section above.
- **Component model / module linking.** Not shipping in any browser.
- **JS closures on one import edge.** Works, 12.45 ns (Safari) and 25.57 ns
  (Chrome) per call. Slower than the trampoline in both engines.

## What PR-6 should say

**Superseded (2026-10-03).** This section was written before the trampoline was
measured. Its premise, that a shared memory forces a JS forwarder onto the
Kotlin to Rust edge, is wrong: wiring B with the trampoline keeps the shared
memory and takes JS off that edge, at 4.35 ns a call in Safari and 7.11 ns in
Chrome instead of 12.45 ns and 25.57 ns. The text below, including the
suggested Korean wording that says the two cannot hold at once ("동시에 성립할
수 없습니다"), is kept as the record of what was proposed then, not as a
recommendation.

The two candidate wirings, priced:

| | Wiring A (as PR-6 is written) | Wiring B (recommended) |
|---|---|---|
| Memory owner | Rust, exported | Kotlin, exported; Rust imports it |
| Shared arena | **no** - Kotlin traps | **yes** - verified both directions |
| Kotlin -> Rust call | 1.45 ns, direct | 12.05 ns, JS closure |
| Per-frame cost | 3 boundary calls: ~4 ns, **plus a copy of the whole arena through a JS `Uint8Array` every frame** | 3 boundary calls: ~36 ns, no copy |

Wiring B is ~32 ns/frame worse on call overhead and avoids a per-frame arena
copy that costs microseconds. Against a 16.7 ms frame both are noise, but B is
the one that keeps PR-4 intact, and PR-4 (zero-copy, read in place) is the
requirement that actually carries the frame budget. Choose B.

This does mean the absolute wording "the call path contains no JS frame" has to
go. The honest version is that JS is confined to a generated, fixed-shape
forwarder per boundary function, measured at 12 ns - which is still an order of
magnitude below the ~115 ns JNI call PR-5 accepts on Android, and is nothing
like the old React Native bridge that D8 rejects (serialisation, async, thread
hops). The constraint D8 actually protects is *no serialisation and no queue*,
and wiring B preserves that completely.

Suggested replacement for the PR-6 body (Korean, to match `docs/SPEC.md`):

> ### PR-6 Web 경계 (`Agreed`)
> Rust(wasm32)와 Kotlin/Wasm 모듈을 **arena 복사 없이** 연결합니다.
> `LoopMode::Platform`입니다.
>
> - **메모리**: Kotlin/Wasm 모듈이 유일한 `WebAssembly.Memory`를 정의하고
>   export합니다. Rust는 `-Clink-arg=--import-memory`로 그 메모리를
>   import합니다. PR-4의 arena는 이 공유 메모리 안에 있고, 양쪽 모두 제자리에서
>   읽습니다(zero-copy 유지). Rust의 data 영역과 Kotlin `kotlin.wasm.unsafe`
>   할당 영역은 `--global-base`로 분리합니다.
> - **함수 호출**: Kotlin의 `@WasmImport`가 Rust export에 직접 바인딩되면
>   호출당 1.45ns이지만, 이 배선은 위의 메모리 공유와 **동시에 성립할 수
>   없습니다**(인스턴스화 순환). 따라서 Renderer→Host 호출은 인스턴스화 시점에
>   생성되는 고정 형태의 JS forwarder를 거칩니다. 호출당 12.05ns로 측정했고,
>   프레임당 경계 호출 3회 기준 약 36ns입니다.
> - **금지 사항은 그대로입니다**: 직렬화, 비동기 큐, 스레드 홉, 데이터 복사는
>   경계에 두지 않습니다(D8). JS는 호출을 전달하기만 하며 인자는 i32뿐입니다.
> - **Kotlin/Wasm의 제약** (실측): 모듈은 자신의 linear memory를 정의·export할
>   뿐이며 import할 수 없습니다. `Pointer` 생성자가 internal이라 임의 주소는
>   할당받은 포인터의 산술로 만듭니다.
> - 수용 기준: `experiments/web-interop`을 Safari 26.5에서 실행해 양방향 4 KiB
>   round trip이 성공하고, Kotlin의 arena 읽기가 0.977 ns/byte 이하입니다.
>   V8·SpiderMonkey에서 재측정해야 합니다.

`docs/INTENT.md` D8's last line ("Web에서도 JS 브리지를 거치지 않습니다") should
become something like: Web에서도 직렬화·큐·복사는 없습니다. 모듈 인스턴스화
순환 때문에 호출 하나당 고정 형태의 JS forwarder(12ns)만 남고, 데이터는 공유
linear memory에 그대로 둡니다.

`PROJECT.md` Q3 can be closed by this experiment; the remaining open item is
re-measuring on V8 and SpiderMonkey.
