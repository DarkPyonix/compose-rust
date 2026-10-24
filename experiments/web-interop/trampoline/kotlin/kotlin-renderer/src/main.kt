// Kotlin/Wasm half of the web-interop trampoline experiment.
//
// Questions under test:
//   1. Can a Kotlin/Wasm module read and write a linear memory shared with a
//      Rust wasm module?
//   2. Is a Kotlin import bound to a Rust export a direct wasm->wasm call, and
//      what does it cost compared with a JS shim?
//
// Kotlin/Wasm defines and exports its own `memory`; there is no way to make it
// import one. So the shared arena lives in *Kotlin's* memory and the Rust
// module imports it (`--import-memory`, `--global-base=16MiB`). Because that
// forces Kotlin to be instantiated first, Kotlin's imports of the Rust
// functions are bound to a tiny `call_indirect` trampoline module instead of
// directly to the Rust instance. See ../../../build-trampoline.py.

@file:OptIn(
    kotlin.js.ExperimentalWasmJsInterop::class,
    kotlin.wasm.ExperimentalWasmInterop::class,
    kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class,
)

package dev.darkpyonix.composerust.experiments.webinterop

import kotlin.wasm.WasmExport
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.Pointer

// ---------------------------------------------------------------------------
// Imports bound to Rust, with no JS on the call path
// ---------------------------------------------------------------------------

@WasmImport("rust", "add")
external fun rustAdd(a: Int, b: Int): Int

@WasmImport("rust", "sum")
external fun rustSum(ptr: Int, len: Int): Int

@WasmImport("rust", "fill")
external fun rustFill(ptr: Int, len: Int, seed: Int)

/** Same Rust function, reached through a JS arrow function, for comparison. */
@JsFun("(a, b) => globalThis.__composeRustAdd(a, b)")
external fun rustAddViaJs(a: Int, b: Int): Int

// ---------------------------------------------------------------------------
// Exports bound to Rust's imports (the Rust -> Kotlin direction)
// ---------------------------------------------------------------------------

@WasmExport("peer_add")
fun peerAdd(a: Int, b: Int): Int = a + b

// ---------------------------------------------------------------------------
// Shared-memory access from Kotlin
// ---------------------------------------------------------------------------

/** Sum `len` Int words at byte offset `ptr` of the shared linear memory. */
@WasmExport("kotlin_sum")
fun kotlinSum(ptr: Int, len: Int): Int {
    var acc = 0
    var i = 0
    while (i < len) {
        acc += Pointer((ptr + i * 4).toUInt()).loadInt()
        i++
    }
    return acc
}

/** Write a fixed-layout ramp of `len` Int words at byte offset `ptr`. */
@WasmExport("kotlin_fill")
fun kotlinFill(ptr: Int, len: Int, seed: Int) {
    var i = 0
    while (i < len) {
        Pointer((ptr + i * 4).toUInt()).storeInt(seed + i)
        i++
    }
}

// ---------------------------------------------------------------------------
// Benchmarks (the harness times these from JS with performance.now())
// ---------------------------------------------------------------------------

/** Loop with no boundary call: the baseline to subtract. */
@WasmExport("bench_local")
fun benchLocal(n: Int): Int {
    var acc = 0
    var i = 0
    while (i < n) {
        acc += i + 1
        i++
    }
    return acc
}

/** `n` Kotlin -> trampoline -> Rust calls. */
@WasmExport("bench_direct")
fun benchDirect(n: Int): Int {
    var acc = 0
    var i = 0
    while (i < n) {
        acc += rustAdd(i, 1)
        i++
    }
    return acc
}

/** `n` Kotlin -> JS -> Rust calls. */
@WasmExport("bench_shim")
fun benchShim(n: Int): Int {
    var acc = 0
    var i = 0
    while (i < n) {
        acc += rustAddViaJs(i, 1)
        i++
    }
    return acc
}

/** `reps` passes of `kotlinSum` over `len` words: shared-memory read cost. */
@WasmExport("bench_read")
fun benchRead(ptr: Int, len: Int, reps: Int): Int {
    var acc = 0
    var r = 0
    while (r < reps) {
        acc += kotlinSum(ptr, len)
        r++
    }
    return acc
}

/** `reps` passes of `kotlinFill` over `len` words: shared-memory write cost. */
@WasmExport("bench_write")
fun benchWrite(ptr: Int, len: Int, reps: Int) {
    var r = 0
    while (r < reps) {
        kotlinFill(ptr, len, r)
        r++
    }
}

/**
 * One read-in-place round trip over the shared arena: Rust writes the batch, Kotlin reads it back.
 * Returns the sum Kotlin observes, which the harness checks against Rust's.
 */
@WasmExport("round_trip")
fun roundTrip(ptr: Int, len: Int, seed: Int): Int {
    rustFill(ptr, len, seed)
    return kotlinSum(ptr, len)
}

/** Writes a sentinel deep in Kotlin's own heap so the harness can detect
 *  Rust's data segments overwriting Kotlin state. */
@WasmExport("kotlin_sentinel")
fun kotlinSentinel(): Int {
    val s = StringBuilder()
    for (i in 0 until 64) s.append(i)
    return s.toString().length
}

fun main() {
    // The module exists for its exports.
}
