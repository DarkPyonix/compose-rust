#!/usr/bin/env python3
"""Compare two local Maven repositories by what they hold, not by how it was packed.

Usage: compare-maven-repos.py <expected repo> <actual repo> [--report <file>] [--changed <prefix>]...
                              [--rename <actual>=<expected>]... [--extra-allowed]

Two builds of the same source can still differ in bytes nobody chose: the time an archive
entry was written, the timestamp Maven writes into maven-metadata-local.xml, and the
checksums Gradle records for archives whose only difference is those times. This reads past
exactly those and nothing else:

  - every file published on either side is listed, and a file present on one side only is a
    difference;
  - a .jar, .klib, .aar or .zip is opened and compared entry by entry, by the SHA-256 of each
    entry's contents, so a timestamp in the zip headers does not count but a changed byte in
    any entry does, and so does an entry present on one side only;
  - a Gradle .module file is compared with the size and digest fields of each listed file
    removed, because those describe the archive bytes, which the entry comparison above
    already covers more precisely;
  - maven-metadata-local.xml is compared with its <lastUpdated> removed;
  - checksum sidecar files (.md5, .sha1, .sha256, .sha512) are skipped for the same reason as
    the .module digests;
  - everything else is compared byte for byte.

A --changed prefix names a publication whose sources changed on purpose since the expected
build's source, such as `org/jetbrains/compose/foundation/foundation`. A difference under it,
in that directory or its per-platform one such as `foundation-macosarm64`, is printed as CHANGED ON PURPOSE
and not counted: every other publication must still be the same.

A --rename names a string the actual side writes where the expected side writes another, such
as the fork's group and version (`org.thisisthepy.compose.=org.jetbrains.compose.`,
`1.11.1-ext.1=1.11.1`). Each is applied to the actual side's paths (with dots as slashes too)
and to its text: .pom, .module and maven-metadata files, every archive entry that is UTF-8
text, and the header of a klib (`linkdata/module`), whose module name is a length-prefixed
protobuf string and is rewritten with its length. What is left is compared as above, so a
publication that differs only in its coordinates is the same.

--extra-allowed reports a file present on the actual side only as EXTRA and does not count it:
for an actual build that publishes more modules than the expected one, such as every sibling
under its own group where the expected build took them from JetBrains.

Exits 0 when the two hold the same contents, 1 when anything differs, and prints every
difference with its path.
"""

import hashlib
import difflib
import json
import os
import re
import sys
import zipfile

ARCHIVES = (".jar", ".klib", ".aar", ".zip")
SIDECARS = (".md5", ".sha1", ".sha256", ".sha512")
DIGEST_KEYS = {"size", "sha512", "sha256", "sha1", "md5"}


def files_under(root):
    found = set()
    for directory, _, names in os.walk(root):
        for name in names:
            path = os.path.join(directory, name)
            found.add(os.path.relpath(path, root))
    return found


def sha256(data):
    return hashlib.sha256(data).hexdigest()


RENAMES = []


def rename_text(data):
    for old, new in RENAMES:
        data = data.replace(old.encode(), new.encode())
    return data


def rename_path(path):
    for old, new in RENAMES:
        path = path.replace(old, new).replace(old.replace(".", "/"), new.replace(".", "/"))
    return path


def varint(data, index):
    shift = value = 0
    while True:
        byte = data[index]
        index += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, index
        shift += 7


def encode_varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def rename_protobuf(data):
    """The top-level length-delimited fields of a protobuf message, renamed, with new lengths."""
    out = bytearray()
    index = 0
    try:
        while index < len(data):
            start = index
            key, index = varint(data, index)
            wire = key & 7
            if wire == 0:
                _, index = varint(data, index)
                out += data[start:index]
            elif wire == 1:
                index += 8
                out += data[start:index]
            elif wire == 5:
                index += 4
                out += data[start:index]
            elif wire == 2:
                length, body = varint(data, index)
                field = data[body:body + length]
                index = body + length
                renamed = rename_text(field)
                out += encode_varint(key) + encode_varint(len(renamed)) + renamed
            else:
                return rename_text(data)
    except IndexError:
        return rename_text(data)
    return bytes(out)


def entry_contents(name, data, renamed):
    if not renamed or not RENAMES:
        return data
    if name.endswith("linkdata/module"):
        return rename_protobuf(data)
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return data
    return rename_text(data)


def text_diff(expected, actual, label, limit=40):
    """The first lines of a unified diff of two texts, or nothing when either is not text."""
    try:
        left = expected.decode("utf-8").splitlines()
        right = actual.decode("utf-8").splitlines()
    except UnicodeDecodeError:
        return []
    lines = list(difflib.unified_diff(left, right, f"expected/{label}", f"actual/{label}", n=1, lineterm=""))
    if len(lines) > limit:
        lines = lines[:limit] + [f"... {len(lines) - limit} more diff lines"]
    return ["    " + line for line in lines]


def archive_entry(path, name, renamed=False):
    with zipfile.ZipFile(path) as archive:
        return entry_contents(name, archive.read(name), renamed)


def archive_entries(path, renamed=False):
    entries = {}
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                entries.setdefault(info.filename, "<directory>")
                continue
            entries[info.filename] = sha256(entry_contents(info.filename, archive.read(info), renamed))
    return entries


def strip_digests(node):
    if isinstance(node, dict):
        return {k: strip_digests(v) for k, v in node.items() if k not in DIGEST_KEYS}
    if isinstance(node, list):
        return [strip_digests(v) for v in node]
    return node


def normalised(path, relative, renamed=False):
    with open(path, "rb") as handle:
        data = handle.read()
    if renamed:
        data = rename_text(data)
    if relative.endswith(".module"):
        return json.dumps(strip_digests(json.loads(data)), sort_keys=True, indent=1).encode()
    if os.path.basename(relative).startswith("maven-metadata"):
        return re.sub(rb"<lastUpdated>\d+</lastUpdated>", b"", data)
    return data


def main(argv):
    report_path = None
    args = list(argv)
    if "--report" in args:
        index = args.index("--report")
        report_path = args[index + 1]
        del args[index:index + 2]
    changed_prefixes = []
    while "--changed" in args:
        index = args.index("--changed")
        changed_prefixes.append(args[index + 1].rstrip("/"))
        del args[index:index + 2]
    while "--rename" in args:
        index = args.index("--rename")
        old, _, new = args[index + 1].partition("=")
        RENAMES.append((old, new))
        del args[index:index + 2]
    # Longest first, so a version that contains another is renamed whole.
    RENAMES.sort(key=lambda pair: len(pair[0]), reverse=True)
    extra_allowed = "--extra-allowed" in args
    if extra_allowed:
        args.remove("--extra-allowed")
    if len(args) != 2:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    expected_root, actual_root = args
    for root in (expected_root, actual_root):
        if not os.path.isdir(root):
            print(f"{root} is not a directory", file=sys.stderr)
            return 2

    lines = []

    def say(text):
        print(text)
        lines.append(text)

    expected = {p for p in files_under(expected_root) if not p.endswith(SIDECARS)}
    # The actual side under the expected side's names; actual_path finds the file again.
    actual_path = {rename_path(p): p for p in files_under(actual_root) if not p.endswith(SIDECARS)}
    actual = set(actual_path)

    if not expected or not actual:
        print("one of the repositories is empty, so there is nothing to compare", file=sys.stderr)
        return 1

    # A module publishes under its own name and once per platform with the platform's name
    # appended. Only those count as the module: `ui/ui` must not take in `ui/ui-graphics`.
    platform = re.compile(r"-(macos|linux|mingw|ios|tvos|watchos|android|js|wasm|desktop|jvm|uikit)[a-z0-9]*")

    def intended(relative):
        parts = relative.split("/")
        for prefix in changed_prefixes:
            depth = prefix.count("/") + 1
            if len(parts) <= depth:
                continue
            head = "/".join(parts[:depth])
            if head == prefix:
                return True
            if head.startswith(prefix + "-") and platform.fullmatch(head[len(prefix):]):
                return True
        return False

    differences = 0
    intended_changes = 0
    only_expected = sorted(expected - actual)
    only_actual = sorted(actual - expected)
    for path in only_expected:
        say(f"ONLY IN {expected_root}: {path}")
    for path in only_actual:
        say(f"{'EXTRA' if extra_allowed else 'ONLY'} IN {actual_root}: {actual_path[path]}")
    differences += len(only_expected) + (0 if extra_allowed else len(only_actual))

    common = sorted(expected & actual)
    archives = 0
    entries_compared = 0
    others = 0
    for relative in common:
        left = os.path.join(expected_root, relative)
        right = os.path.join(actual_root, actual_path[relative])
        if relative.endswith(ARCHIVES):
            archives += 1
            try:
                left_entries = archive_entries(left)
                right_entries = archive_entries(right, renamed=True)
            except zipfile.BadZipFile as error:
                say(f"UNREADABLE ARCHIVE: {relative}: {error}")
                differences += 1
                continue
            entries_compared += len(left_entries.keys() | right_entries.keys())
            changed = False
            for entry in sorted(left_entries.keys() | right_entries.keys()):
                a = left_entries.get(entry)
                b = right_entries.get(entry)
                if a == b:
                    continue
                changed = True
                if a is None:
                    say(f"ENTRY ONLY IN ACTUAL: {relative}!/{entry}")
                elif b is None:
                    say(f"ENTRY ONLY IN EXPECTED: {relative}!/{entry}")
                else:
                    say(f"ENTRY DIFFERS: {relative}!/{entry} ({a[:12]} vs {b[:12]})")
                    if not entry.endswith((".class", ".knm", "linkdata/module")):
                        for line in text_diff(archive_entry(left, entry), archive_entry(right, entry, renamed=True), entry):
                            say(line)
            if changed:
                if intended(relative):
                    say(f"CHANGED ON PURPOSE: {relative}")
                    intended_changes += 1
                else:
                    differences += 1
        else:
            others += 1
            expected_text = normalised(left, relative)
            actual_text = normalised(right, relative, renamed=True)
            if expected_text != actual_text:
                say(f"FILE DIFFERS: {relative}")
                for line in text_diff(expected_text, actual_text, os.path.basename(relative)):
                    say(line)
                if intended(relative):
                    intended_changes += 1
                else:
                    differences += 1

    say("")
    say(f"published files: {len(expected)} expected, {len(actual)} actual, {len(common)} on both sides")
    say(f"archives compared entry by entry: {archives} ({entries_compared} entries)")
    say(f"other files compared: {others}")
    say(f"files that differ because their sources changed on purpose: {intended_changes}")
    say(f"files that differ or are missing on one side: {differences}")
    say("IDENTICAL" if differences == 0 else "DIFFERENT")

    if report_path:
        with open(report_path, "w") as handle:
            handle.write("\n".join(lines) + "\n")
    return 0 if differences == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
