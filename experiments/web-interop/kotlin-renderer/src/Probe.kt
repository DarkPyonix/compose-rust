// Web-interop probe: the Kotlin/Wasm (Renderer) side of a two-module WebAssembly link.
//
// Everything here is deliberately raw: no Compose, no //shared dependency. The
// question is what the Kotlin/Wasm target itself allows at the module boundary.
//
// Three things are under test:
//   1. Can Kotlin declare a real wasm function import bound to another module's
//      export (`@WasmImport`), with no JS frame on the call path?
//   2. Can Kotlin read and write a linear memory the Rust module also sees?
//   3. What does a call cost, direct versus through a JS shim?
//
// The benchmark loops run *inside* this module, so the only JS involved in a
// measurement is the single call that starts the loop.

@file:OptIn(
    kotlin.wasm.ExperimentalWasmInterop::class,
    kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
)

import kotlin.wasm.WasmExport
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// --- Imports bound directly to the Rust module's exports -------------------

@WasmImport("host", "bump")
external fun hostBump(x: Int): Int

@WasmImport("host", "arena_ptr")
external fun hostArenaPtr(): Int

@WasmImport("host", "write_pattern")
external fun hostWritePattern(len: Int, seed: Int): Int

@WasmImport("host", "checksum")
external fun hostChecksum(len: Int): Int

// --- The same call, but routed through JavaScript, for comparison ----------

@kotlin.JsFun("(x) => globalThis.__jsShimBump(x)")
external fun hostBumpViaJs(x: Int): Int

// --- Baseline: a call that never leaves this module ------------------------

fun localBump(x: Int): Int = x + 1

// --- Benchmarks ------------------------------------------------------------

@WasmExport("bench_import")
fun benchImport(n: Int, x: Int): Int {
    var acc = x
    for (i in 0 until n) acc = hostBump(acc)
    return acc
}

@WasmExport("bench_js_shim")
fun benchJsShim(n: Int, x: Int): Int {
    var acc = x
    for (i in 0 until n) acc = hostBumpViaJs(acc)
    return acc
}

@WasmExport("bench_local")
fun benchLocal(n: Int, x: Int): Int {
    var acc = x
    for (i in 0 until n) acc = localBump(acc)
    return acc
}

// --- Linear memory access --------------------------------------------------

/**
 * Turns a raw byte address into a [Pointer].
 *
 * The [Pointer] constructor is internal to the standard library, so the only way
 * to name an address the allocator did not hand out is to take a pointer it did
 * hand out and do arithmetic on it. `plus` is UInt arithmetic, so this reaches
 * addresses below the allocation as well as above it.
 */
private inline fun <T> atAddress(address: Int, block: (Pointer) -> T): T =
    withScopedMemoryAllocator { allocator ->
        val anchor = allocator.allocate(4)
        block(anchor + (address.toUInt() - anchor.address))
    }

/** Reads `len` bytes at `address` and returns their checksum, as Rust computes it. */
@WasmExport("read_memory")
fun readMemory(address: Int, len: Int): Int = atAddress(address) { base ->
    var sum = 0
    for (i in 0 until len) sum += (base + i.toUInt()).loadByte().toInt() and 0xff
    sum
}

/** Writes `len` bytes of the same deterministic pattern Rust writes. */
@WasmExport("write_memory")
fun writeMemory(address: Int, len: Int, seed: Int): Int = atAddress(address) { base ->
    var sum = 0
    for (i in 0 until len) {
        val b = (seed + i) and 0xff
        (base + i.toUInt()).storeByte(b.toByte())
        sum += b
    }
    sum
}

/** Repeated reads, so the per-byte cost of linear memory access can be timed. */
@WasmExport("bench_read_memory")
fun benchReadMemory(address: Int, len: Int, reps: Int): Int {
    var sum = 0
    for (r in 0 until reps) sum = sum xor readMemory(address, len)
    return sum
}

/** A full round trip: ask Rust to fill the arena, then verify it from Kotlin. */
@WasmExport("round_trip")
fun roundTrip(len: Int, seed: Int): Int {
    val expected = hostWritePattern(len, seed)
    val actual = readMemory(hostArenaPtr(), len)
    return if (expected == actual) 1 else 0
}

/** The reverse round trip: Kotlin fills the arena, Rust verifies it. */
@WasmExport("round_trip_back")
fun roundTripBack(len: Int, seed: Int): Int {
    val expected = writeMemory(hostArenaPtr(), len, seed)
    val actual = hostChecksum(len)
    return if (expected == actual) 1 else 0
}

@WasmExport("arena_ptr_from_host")
fun arenaPtrFromHost(): Int = hostArenaPtr()

// --- The Host -> Renderer direction ---------------------------------------

/** What the Rust module tries to import as `renderer.renderer_bump`. */
@WasmExport("renderer_bump")
fun rendererBump(x: Int): Int = x + 1

fun main() {
    // Nothing to do at startup; the harness drives every probe explicitly.
}
