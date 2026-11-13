"""Rewrites every ordinary x86-64 COFF member of an ar archive in the "big object" layout.

LLVM writes that layout only once an object has more than 65,279 sections, which is how the
Windows renderer's object always comes out and no fixture small enough to keep in a
repository ever does. So the test makes one: the same sections, data and symbols, with the
56-byte header, 32-bit section numbers and 20-byte symbol records the layout defines
(Microsoft's PE/COFF specification, ANON_OBJECT_HEADER_BIGOBJ and IMAGE_SYMBOL_EX).

Usage: to-bigobj.py <in.a> <out.a>
"""
import struct, sys

BIGOBJ_CLASS = bytes([0xC7, 0xA1, 0xBA, 0xD1, 0xEE, 0xBA, 0xA9, 0x4B,
                      0xAF, 0x20, 0xFA, 0xF6, 0x6A, 0xA4, 0xDC, 0xB8])


def convert(obj):
    machine, nsec, stamp, symptr, nsym, optsize, flags = struct.unpack_from("<HHIIIHH", obj, 0)
    assert machine == 0x8664 and optsize == 0
    grow = 56 - 20
    sections = bytearray(obj[20:20 + nsec * 40])
    for i in range(nsec):
        at = i * 40
        for field in (20, 24, 28):  # raw data, relocations, line numbers
            value = struct.unpack_from("<I", sections, at + field)[0]
            if value:
                struct.pack_into("<I", sections, at + field, value + grow)
    body = obj[20 + nsec * 40:symptr]
    symbols = bytearray()
    i = 0
    while i < nsym:
        at = symptr + i * 18
        name, value, section, kind, storage, aux = struct.unpack_from("<8sIhHBB", obj, at)
        symbols += struct.pack("<8sIiHBB", name, value, section, kind, storage, aux)
        for j in range(aux):
            record = obj[at + 18 * (j + 1):at + 18 * (j + 2)]
            if storage == 3:  # a section definition: Number gains 16 high bits at offset 16
                symbols += record[:16] + b"\0\0" + b"\0\0"
            else:
                symbols += record + b"\0\0"
        i += 1 + aux
    strings = obj[symptr + nsym * 18:]
    new_symptr = 56 + len(sections) + len(body)
    header = struct.pack("<HHHHI16sIIIIIII", 0, 0xFFFF, 2, machine, stamp, BIGOBJ_CLASS,
                         0, 0, 0, 0, nsec, new_symptr, nsym)
    return header + sections + body + symbols + strings


source, target = sys.argv[1], sys.argv[2]
buf = open(source, "rb").read()
out = bytearray(buf[:8])
pos = 8
while pos + 60 <= len(buf):
    size = int(buf[pos + 48:pos + 58].decode().strip())
    member = buf[pos + 60:pos + 60 + size]
    header = bytearray(buf[pos:pos + 60])
    if member[:2] == b"\x64\x86":
        member = convert(member)
        header[48:58] = str(len(member)).ljust(10).encode()
    out += header + member
    if len(member) & 1:
        out += b"\n"
    pos += 60 + size + (size & 1)
open(target, "wb").write(out)
