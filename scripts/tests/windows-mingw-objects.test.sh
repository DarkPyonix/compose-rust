#!/usr/bin/env bash
# The Windows renderer is a MinGW object linked by an MSVC-mode linker, and two MinGW
# conventions mean something else there. Left as they are, the link succeeds and the
# program is wrong: static constructors never run, so the Kotlin runtime starts without its
# globals and the first call hangs; and every function's unwind data is dropped, so a Kotlin
# exception that crosses two functions sends the unwinder into a loop. Both were seen.
#
# `renderer/scripts/fix-mingw-objects.py` rewrites the objects before the
# link. This runs it on small MinGW objects built the way Kotlin/Native builds its own
# (`fixtures/mingw-object.cpp` says how), in both COFF layouts: the ordinary one, and the
# "big object" one Kotlin/Native writes once an object has more sections than 16 bits can
# number, which the renderer's always has. Then it reads the result back and checks:
#
#   - no .ctors section is left, and the constructor is in .CRT$XCU, where the MSVC
#     runtime looks;
#   - every .pdata$NAME and .xdata$NAME is an associative COMDAT of the .text$NAME it
#     describes, which is what keeps an MSVC-mode linker from discarding it.
#
# It needs python3 and nothing else; no compiler, no Windows.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixer="$repo_root/renderer/scripts/fix-mingw-objects.py"
fixture="$repo_root/scripts/tests/fixtures/mingw-object.a"
to_bigobj="$repo_root/scripts/tests/fixtures/to-bigobj.py"

for file in "$fixer" "$fixture" "$to_bigobj"; do
    [[ -f "$file" ]] || { echo "missing $file"; exit 1; }
done
command -v python3 >/dev/null || { echo "skip: no python3"; exit 0; }

# Inside the repository, as everything this project makes is.
mkdir -p "$repo_root/.scratch"
work="$(mktemp -d "$repo_root/.scratch/windows-mingw-objects.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# The big-object layout, made from the same fixture: see to-bigobj.py for why it is made
# rather than kept.
python3 "$to_bigobj" "$fixture" "$work/bigobj.a" || { echo "FAIL could not make the big-object fixture"; exit 1; }
fixtures=("$fixture" "$work/bigobj.a")

red=0
for fixture in "${fixtures[@]}"; do
    cp "$fixture" "$work/object.a"
    python3 "$fixer" "$work/object.a" >/dev/null ||
        { echo "FAIL the fixer failed on $(basename "$fixture")"; red=1; continue; }
    python3 - "$work/object.a" "$fixture" <<'PY' || red=1
import struct, sys


def members(path):
    buf = open(path, "rb").read()
    pos = 8
    while pos + 60 <= len(buf):
        size = int(buf[pos + 48:pos + 58].decode().strip())
        yield buf, pos + 60
        pos = pos + 60 + size + (size & 1)


def layout(buf, base):
    if struct.unpack_from("<H", buf, base)[0] == 0x8664:
        _, nsec, _, symptr, nsym, optsize, _ = struct.unpack_from("<HHIIIHH", buf, base)
        return base + 20 + optsize, nsec, base + symptr, nsym, 18
    sig1, sig2, version, machine = struct.unpack_from("<HHHH", buf, base)
    if sig1 == 0 and sig2 == 0xFFFF and version >= 2 and machine == 0x8664:
        nsec, symptr, nsym = struct.unpack_from("<III", buf, base + 44)
        return base + 56, nsec, base + symptr, nsym, 20
    return None


def read(path):
    """Every COFF member: its layout, section names, and COMDAT (selection, number)."""
    out = []
    for buf, base in members(path):
        found = layout(buf, base)
        if found is None:
            continue
        table, nsec, symtab, nsym, record = found
        strtab = symtab + nsym * record
        names = []
        for i in range(nsec):
            at = table + i * 40
            name = buf[at:at + 8].rstrip(b"\0").decode()
            if name.startswith("/") and not name.startswith("//"):
                o = int(name[1:]); end = buf.index(b"\0", strtab + o)
                name = buf[strtab + o:end].decode()
            names.append(name)
        comdat = {}
        i = 0
        while i < nsym:
            at = symtab + i * record
            if record == 20:
                section = struct.unpack_from("<i", buf, at + 12)[0]
                storage, aux = buf[at + 18], buf[at + 19]
            else:
                section = struct.unpack_from("<h", buf, at + 12)[0]
                storage, aux = buf[at + 16], buf[at + 17]
            if storage == 3 and aux and section > 0:
                characteristics = struct.unpack_from("<I", buf, table + (section - 1) * 40 + 36)[0]
                if characteristics & 0x1000:
                    a = at + record
                    number = struct.unpack_from("<H", buf, a + 12)[0]
                    if record == 20:
                        number |= struct.unpack_from("<H", buf, a + 16)[0] << 16
                    comdat[section] = (buf[a + 14], number)
            i += 1 + aux
        out.append(("big object" if record == 20 else "ordinary", names, comdat))
    return out


fixed, original = sys.argv[1], sys.argv[2]
red = 0


def fail(message):
    global red
    print("FAIL " + message)
    red = 1


before = read(original)
if not before:
    fail(f"{original} holds no x86-64 COFF object")
for kind, names, _ in before:
    # The fixture has to contain what is being fixed, or the checks below prove nothing.
    if ".ctors" not in names:
        fail(f"the {kind} fixture has no .ctors section to move; rebuild it as its source says")
    if not any(n.startswith(".pdata$") for n in names):
        fail(f"the {kind} fixture has no per-function unwind data; rebuild it with -ffunction-sections")

for kind, names, comdat in read(fixed):
    if ".ctors" in names:
        fail(f"{kind}: a .ctors section is still there, and the MSVC runtime will never run it")
    if ".CRT$XCU" not in names:
        fail(f"{kind}: no .CRT$XCU section, so the constructor is not where the MSVC runtime looks")
    for index, name in enumerate(names, 1):
        for prefix in (".pdata$", ".xdata$"):
            if not name.startswith(prefix):
                continue
            selection, number = comdat.get(index, (None, None))
            code = ".text$" + name[len(prefix):]
            if selection != 5:
                fail(f"{kind}: {name} is COMDAT selection {selection}, not associative, so an "
                     "MSVC-mode linker drops it and the function has no unwind entry")
            elif names[number - 1] != code:
                fail(f"{kind}: {name} is associated with {names[number - 1]}, not {code}")
    if not red:
        print(f"ok   {kind}: constructors moved to .CRT$XCU and unwind data made associative")

sys.exit(red)
PY
done
exit $red
