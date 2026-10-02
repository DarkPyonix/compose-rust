# Web interop experiment (SPEC PR-6, PROJECT Q3)

Can a Rust `wasm32-unknown-unknown` module and a Kotlin/Wasm module call each
other directly, and share one linear memory, with JavaScript only wiring the
modules at instantiation?

Nothing in the repository depends on this directory. The Rust crate declares its
own empty `[workspace]`, so the root `cargo` workspace never sees it, and
`scripts/check.sh` is unchanged.

## Verdict

**PR-6 does not hold as written.** Both halves of the claim are individually
true and they cannot both be true at the same time.

- Direct wasm-to-wasm calls: **yes**, and they are essentially free
  (**1.45 ns/call**, against 0.30 ns for a call that never leaves the module).
- One shared `WebAssembly.Memory`: **only if Kotlin owns it**, because a
  Kotlin/Wasm module always *defines and exports* its own memory and has no way
  to import one.
- Combining the two is impossible today, because Kotlin must be instantiated
  before its memory exists, and its wasm imports must be supplied before it can
  be instantiated. That cycle can only be broken with JS closures on the
  Kotlin-to-Rust import edge, which costs **12.05 ns/call**.

The recommendation is to take the shared memory and pay the ~11 ns: see
[What PR-6 should say](#what-pr-6-should-say).

## What was built

| Path | What it is |
|---|---|
| `rust-exporter/` | Rust crate, `cdylib`, `wasm32-unknown-unknown`. Owns a 64 KiB arena (PR-4), exports `bump`, `arena_ptr`, `write_pattern`, `checksum`, and imports `renderer.renderer_bump`. |
| `kotlin-renderer/` | Standalone Amper `wasm-js/app` module. No Compose, no `//shared`: the question is what the Kotlin/Wasm target itself allows. |
| `harness/index.html` | Instantiates and wires both modules, runs the probes and the benchmarks, POSTs the numbers back. |
| `harness/host-module.mjs` | The `host` ES module that Kotlin's generated import object resolves. Implements both wiring modes. |
| `harness/serve.py` | Serves the harness and writes `results-<mode>.json`. |
| `build.sh` | Builds everything into `harness/`. |

`dioxus-compose-renderer/kotlin` (the Amper/Kotlin CLI wrapper) builds a
standalone Kotlin/Wasm module fine; `kotlin-renderer/kotlin` is a copy of that
wrapper and `kotlin-renderer/module.yaml` is four lines.

### How to reproduce

```sh
./build.sh
python3 harness/serve.py 8765
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

## Why the two halves cannot be combined

`WebAssembly.instantiate` requires *every* import to be supplied at
instantiation time. Therefore:

- Kotlin cannot be instantiated until Rust's exports exist.
- Rust cannot be instantiated until Kotlin's memory exists.
- Kotlin's memory does not exist until Kotlin is instantiated.

The usual ways out do not apply here:

- **Single-module linking.** There is no linker that merges a WasmGC module and
  an LLVM linear-memory module. Not available.
- **A third module owning the memory.** Does not help: the blocker is that
  Kotlin cannot import a memory from anyone.
- **A funcref table plus `call_indirect`,** which is how a circular link is
  normally broken. Kotlin/Wasm cannot declare an imported table or a
  `call_indirect` through one.
- **Component model / module linking.** Not shipping in any browser.
- **JS closures on one import edge.** Works, costs 10.6 ns/call. This is the
  only option available today.

## What PR-6 should say

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
