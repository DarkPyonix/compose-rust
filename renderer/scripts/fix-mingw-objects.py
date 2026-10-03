"""Makes MinGW-built COFF objects mean the same thing to an MSVC-mode linker, in place.

Two conventions differ, and both are fixed here:

1. Static constructors. MinGW puts them in .ctors and its own startup code runs them. The
   MSVC runtime runs the function pointers it finds in .CRT$XCU. Both are arrays of
   pointers to void(void) functions, so the section is renamed. Both names fit the 8-byte
   short name field. Its flags are made the ones the MSVC compiler gives .CRT$XCU
   (initialised data, read only, 8-byte aligned). MinGW's .ctors is writable, and the
   linker keeps .CRT sections whose flags differ apart (LNK4078): the runtime then walks
   the group between its own first and last entries, and a constructor in the other group
   is never run. That left Skia's constructors, or ours, out of a Windows build.

2. Unwind data. For a function in COMDAT section .text$NAME, the MinGW toolchain emits its
   unwind data as .pdata$NAME and .xdata$NAME, each a COMDAT of its own ("select any"), and
   the MinGW linker pairs them with the code by name. An MSVC-mode linker follows the COFF
   rules instead, sees two sections nothing refers to, and drops them. The function then has
   no entry in the exception table, and an exception passing through it is unwound as if it
   were a leaf function: the return address is read from the wrong slot and the unwinder
   loops. Marking them associative to .text$NAME (selection 5) says, in the form the
   format defines, that they live and die with that code.
"""
import struct, sys

CTORS, CRT = b".ctors\0\0", b".CRT$XCU"
# IMAGE_SCN_CNT_INITIALIZED_DATA | IMAGE_SCN_ALIGN_8BYTES | IMAGE_SCN_MEM_READ, which is what
# `#pragma section(".CRT$XCU", read)` gives a section in MSVC's own objects.
CRT_CHARACTERISTICS = 0x40400040
SELECT_ASSOCIATIVE = 5

def section_name(buf, at, strtab):
    raw = bytes(buf[at:at + 8])
    name = raw.rstrip(b"\0").decode(errors="replace")
    if name.startswith("//"):
        # An offset past seven decimal digits is written in base64, six characters.
        alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        o = 0
        for character in name[2:]:
            o = o * 64 + alphabet.index(character)
        e = buf.index(b"\0", strtab + o)
        name = bytes(buf[strtab + o:e]).decode()
    elif name.startswith("/"):
        o = int(name[1:]); e = buf.index(b"\0", strtab + o)
        name = bytes(buf[strtab + o:e]).decode()
    return name

# Two layouts of the same thing. An ordinary COFF object has a 20-byte header, 16-bit
# section numbers and 18-byte symbols. Kotlin/Native writes its object in the "big object"
# layout once it has more sections than 16 bits can number, which the renderer's always
# does: a 56-byte header, 32-bit section numbers and 20-byte symbols. Reading only the
# first was how a whole renderer once went through this unchanged.
def layout(buf, base):
    """Section table, section count, symbol table, symbol count, record size, or None."""
    machine = struct.unpack_from("<H", buf, base)[0]
    if machine == 0x8664:
        _, nsec, _, symptr, nsym, optsize, _ = struct.unpack_from("<HHIIIHH", buf, base)
        return base + 20 + optsize, nsec, base + symptr, nsym, 18
    sig1, sig2, version, machine = struct.unpack_from("<HHHH", buf, base)
    if sig1 == 0 and sig2 == 0xFFFF and version >= 2 and machine == 0x8664:
        nsec, symptr, nsym = struct.unpack_from("<III", buf, base + 44)
        return base + 56, nsec, base + symptr, nsym, 20
    return None


def patch(buf, base):
    found = layout(buf, base)
    if found is None:
        return 0, 0
    table, nsec, symtab, nsym, record = found
    big = record == 20
    strtab = symtab + nsym * record
    names = []
    ctors = 0
    for i in range(nsec):
        at = table + i * 40
        if bytes(buf[at:at + 8]) == CTORS:
            buf[at:at + 8] = CRT
            struct.pack_into("<I", buf, at + 36, CRT_CHARACTERISTICS)
            ctors += 1
        names.append(section_name(buf, at, strtab))
    text_index = {n[len(".text$"):]: i + 1 for i, n in enumerate(names) if n.startswith(".text$")}
    # Unwind data for the object's plain .text carries an empty suffix.
    if "" not in text_index and ".text" in names:
        text_index[""] = names.index(".text") + 1
    associated = 0
    i = 0
    while i < nsym:
        at = symtab + i * record
        if bytes(buf[at:at + 8]) == CTORS:
            buf[at:at + 8] = CRT
        if big:
            secnum = struct.unpack_from("<i", buf, at + 12)[0]
            storage, aux = buf[at + 18], buf[at + 19]
        else:
            secnum = struct.unpack_from("<h", buf, at + 12)[0]
            storage, aux = buf[at + 16], buf[at + 17]
        if storage == 3 and aux and secnum > 0:
            name = names[secnum - 1]
            for prefix in (".pdata$", ".xdata$"):
                if name.startswith(prefix):
                    target = text_index.get(name[len(prefix):])
                    characteristics = struct.unpack_from("<I", buf, table + (secnum - 1) * 40 + 36)[0]
                    if target and characteristics & 0x1000:
                        a = at + record
                        # Number is 16 bits, plus 16 high bits in the big layout.
                        struct.pack_into("<H", buf, a + 12, target & 0xFFFF)
                        buf[a + 14] = SELECT_ASSOCIATIVE
                        if big:
                            struct.pack_into("<H", buf, a + 16, target >> 16)
                        associated += 1
        i += 1 + aux
    return ctors, associated

path = sys.argv[1]
buf = bytearray(open(path, "rb").read())
assert buf[:8] == b"!<arch>\n"
pos, ctors, associated = 8, 0, 0
while pos + 60 <= len(buf):
    size = int(buf[pos + 48:pos + 58].decode().strip())
    body = pos + 60
    if layout(buf, body) is not None:
        c, a = patch(buf, body)
        ctors += c; associated += a
    pos = body + size + (size & 1)
open(path, "wb").write(buf)
print(f"{path}: moved {ctors} .ctors sections, made {associated} unwind sections associative")
