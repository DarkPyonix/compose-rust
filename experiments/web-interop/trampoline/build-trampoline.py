#!/usr/bin/env python3
"""Hand-assemble `dist/trampoline.wasm`.

Why this module exists
----------------------
Kotlin/Wasm defines and exports its own linear memory and cannot import one, so
the Rust module has to import Kotlin's memory. That forces Kotlin to be
instantiated first, which in turn makes a *direct* Kotlin import of a Rust
export impossible: the Rust instance does not exist yet.

The cycle is broken without any JS on the call path by a tiny wasm module that
imports a `WebAssembly.Table` and forwards each boundary function with
`call_indirect`. Instantiation order becomes:

    table (JS) -> trampoline -> Kotlin -> Rust -> table.set(rust exports)

so JS still only wires things up at instantiation, and a Kotlin -> Rust call is
`call` + `call_indirect`, entirely inside wasm.

Slots: 0 = compose_rust_trampoline_add, 1 = compose_rust_trampoline_sum, 2 = compose_rust_trampoline_fill.
"""
import pathlib
import struct

I32 = 0x7F


def uleb(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def vec(items):
    return uleb(len(items)) + b"".join(items)


def section(sid, payload):
    return bytes([sid]) + uleb(len(payload)) + payload


def name(s):
    b = s.encode()
    return uleb(len(b)) + b


def functype(params, results):
    return b"\x60" + vec([bytes([p]) for p in params]) + vec([bytes([r]) for r in results])


def body(nparams, slot, typeidx):
    code = bytearray()
    code += b"\x00"  # no locals
    for i in range(nparams):
        code += b"\x20" + uleb(i)  # local.get i
    code += b"\x41" + uleb(slot)  # i32.const slot
    code += b"\x11" + uleb(typeidx) + b"\x00"  # call_indirect type, table 0
    code += b"\x0b"  # end
    return uleb(len(code)) + bytes(code)


def main():
    types = section(1, vec([
        functype([I32, I32], [I32]),        # 0: add / sum
        functype([I32, I32, I32], []),      # 1: fill
    ]))
    # import: (table (import "env" "table") 3 funcref)
    imports = section(2, vec([
        name("env") + name("table") + b"\x01" + b"\x70" + b"\x00" + uleb(3),
    ]))
    funcs = section(3, vec([uleb(0), uleb(0), uleb(1)]))
    exports = section(7, vec([
        name("add") + b"\x00" + uleb(0),
        name("sum") + b"\x00" + uleb(1),
        name("fill") + b"\x00" + uleb(2),
    ]))
    code = section(10, vec([
        body(2, 0, 0),
        body(2, 1, 0),
        body(3, 2, 1),
    ]))

    module = b"\x00asm" + struct.pack("<I", 1) + types + imports + funcs + exports + code
    out = pathlib.Path(__file__).parent / "dist" / "trampoline.wasm"
    out.parent.mkdir(exist_ok=True)
    out.write_bytes(module)
    print(f"wrote {out} ({len(module)} bytes)")


if __name__ == "__main__":
    main()
