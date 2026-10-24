// Satisfies the Kotlin module's `import * as ... from 'rust'` (an import map in
// index.html points the bare specifier "rust" here).
//
// Kotlin must be instantiated before Rust, because Rust imports Kotlin's linear
// memory. So the functions handed to Kotlin here are the exports of the tiny
// `call_indirect` trampoline module, whose table is filled with the real Rust
// functions once the Rust instance exists. Nothing on the call path is JS.

const table = new WebAssembly.Table({ element: "anyfunc", initial: 3 });

const trampoline = (await WebAssembly.instantiateStreaming(
    fetch(new URL("./trampoline.wasm", import.meta.url)),
    { env: { table } },
)).instance;

/** Called by the harness once the Rust instance exists. */
export function bindRust(rustExports) {
    table.set(0, rustExports.compose_rust_trampoline_add);
    table.set(1, rustExports.compose_rust_trampoline_sum);
    table.set(2, rustExports.compose_rust_trampoline_fill);
}

export const { add, sum, fill } = trampoline.exports;
