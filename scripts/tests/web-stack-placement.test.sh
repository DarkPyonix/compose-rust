#!/usr/bin/env bash
# The Web Host's shadow stack must sit above the Renderer's half of the shared memory.
#
# The Host links with --global-base=RUST_REGION_BASE (4194304) so its data, stack
# and heap sit above the addresses the Renderer's allocator hands out. rustc's
# wasm32-unknown-unknown target passes --stack-first to wasm-ld, which puts the shadow stack
# at the bottom of memory instead (measured __stack_pointer 1048576), inside the Renderer's
# half. build-host.sh therefore has to add -Clink-arg=--no-stack-first after rustc's own
# argument. This test reads the first mutable i32 global of a linked Host module (the shadow
# stack pointer, global 0) and fails unless it is above the region base.
#
# Usage: web-stack-placement.test.sh [host.wasm]
#   WEB_HOST_WASM may name the module instead. With no module the decoder is only
#   self-tested, against hand-built modules, so the check runs in CI without a wasm build.
#   REGION_BASE overrides the boundary (default 4194304, the schema's RUST_REGION_BASE).
set -euo pipefail

wasm="${1:-${WEB_HOST_WASM:-}}"
region_base="${REGION_BASE:-4194304}"

python3 -I - "$region_base" "$wasm" <<'PY'
import sys

region_base = int(sys.argv[1])
path = sys.argv[2]


def uleb(data, i):
    result = shift = 0
    while True:
        b = data[i]
        i += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            return result, i


def sleb(data, i):
    result = shift = 0
    while True:
        b = data[i]
        i += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            if b & 0x40:
                result -= 1 << shift
            return result, i


def stack_pointer(data):
    """Initial value of the first mutable i32 global defined by the module."""
    assert data[:4] == b"\0asm", "not a wasm module"
    i = 8
    while i < len(data):
        section = data[i]
        size, i = uleb(data, i + 1)
        end = i + size
        if section == 6:
            count, j = uleb(data, i)
            for _ in range(count):
                valtype, mutable = data[j], data[j + 1]
                assert data[j + 2] == 0x41, "global init is not i32.const"
                value, j = sleb(data, j + 3)
                assert data[j] == 0x0B, "global init is not a bare constant"
                j += 1
                if valtype == 0x7F and mutable == 1:
                    return value
            raise SystemExit("error: no mutable i32 global")
        i = end
    raise SystemExit("error: no global section")


def module(initial):
    value = bytearray()
    n = initial
    while True:
        b = n & 0x7F
        n >>= 7
        if (n == 0 and not b & 0x40) or (n == -1 and b & 0x40):
            value.append(b)
            break
        value.append(b | 0x80)
    body = bytes([1, 0x7F, 1, 0x41]) + bytes(value) + bytes([0x0B])
    return b"\0asm\1\0\0\0" + bytes([6, len(body)]) + body


def safe(sp):
    return sp > region_base


# The decoder, against the two values issue #53 is about.
assert stack_pointer(module(1048576)) == 1048576
assert stack_pointer(module(4300000)) == 4300000
assert not safe(1048576), "a stack at 1 MiB must be reported as unsafe"
assert safe(4300000), "a stack above the region base must be reported as safe"
print("ok    stack pointer decoder")

if not path:
    print("skip  no host module given (set WEB_HOST_WASM or pass a path)")
    sys.exit(0)

sp = stack_pointer(open(path, "rb").read())
if not safe(sp):
    sys.exit(
        f"error: __stack_pointer starts at {sp}, not above the region base {region_base}: "
        "the shadow stack is in the Renderer's half. Link with -Clink-arg=--no-stack-first."
    )
print(f"ok    __stack_pointer {sp} is above the region base {region_base}")
PY
