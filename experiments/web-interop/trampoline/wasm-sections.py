#!/usr/bin/env python3
"""Minimal wasm import/export/memory section dumper.

Used by the experiment to check, without a browser, whether a module imports or
exports a linear memory and which functions cross the boundary.
"""
import sys

KIND = {0: "func", 1: "table", 2: "memory", 3: "global", 4: "tag"}


def uleb(b, i):
    r = 0
    s = 0
    while True:
        x = b[i]
        i += 1
        r |= (x & 0x7F) << s
        if not x & 0x80:
            return r, i
        s += 7


def name(b, i):
    n, i = uleb(b, i)
    return b[i:i + n].decode("utf-8", "replace"), i + n


def valtype(b, i):
    """Skip one valtype, including GC reference types (0x63/0x64 + heaptype)."""
    t = b[i]
    i += 1
    if t in (0x63, 0x64):
        _, i = uleb(b, i)  # heaptype (types >= 0 are indices; abstract ones fit in one byte)
    return i


def main(path):
    b = open(path, "rb").read()
    assert b[:4] == b"\0asm", "not a wasm module"
    i = 8
    imports, exports, mems = [], [], 0
    while i < len(b):
        sid = b[i]
        i += 1
        size, i = uleb(b, i)
        end = i + size
        j = i
        if sid == 2:  # import
            n, j = uleb(b, j)
            for _ in range(n):
                m, j = name(b, j)
                f, j = name(b, j)
                k = b[j]
                j += 1
                imports.append((m, f, KIND.get(k, k)))
                if k == 0:
                    _, j = uleb(b, j)
                elif k == 1:
                    j += 1
                    lim = b[j]
                    j += 1
                    _, j = uleb(b, j)
                    if lim:
                        _, j = uleb(b, j)
                elif k == 2:
                    lim = b[j]
                    j += 1
                    _, j = uleb(b, j)
                    if lim & 1:
                        _, j = uleb(b, j)
                elif k == 3:
                    j = valtype(b, j)
                    j += 1  # mutability
                elif k == 4:
                    j += 1
                    _, j = uleb(b, j)
        elif sid == 5:  # memory
            mems, j = uleb(b, j)
        elif sid == 7:  # export
            n, j = uleb(b, j)
            for _ in range(n):
                f, j = name(b, j)
                k = b[j]
                j += 1
                _, j = uleb(b, j)
                exports.append((f, KIND.get(k, k)))
        i = end

    print(f"== {path}")
    print(f"memories defined in this module: {mems}")
    print("imports:")
    for m, f, k in imports:
        print(f"  {k:6} {m}.{f}")
    print("exports:")
    for f, k in exports:
        print(f"  {k:6} {f}")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        main(p)
