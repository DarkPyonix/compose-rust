#!/usr/bin/env python3
"""Compare two local Maven repositories by what they hold, not by how it was packed.

Usage: compare-maven-repos.py <expected repo> <actual repo> [--report <file>] [--changed <prefix>]...

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

Exits 0 when the two hold the same contents, 1 when anything differs, and prints every
difference with its path.
"""

import hashlib
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


def archive_entries(path):
    entries = {}
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                entries.setdefault(info.filename, "<directory>")
                continue
            entries[info.filename] = sha256(archive.read(info))
    return entries


def strip_digests(node):
    if isinstance(node, dict):
        return {k: strip_digests(v) for k, v in node.items() if k not in DIGEST_KEYS}
    if isinstance(node, list):
        return [strip_digests(v) for v in node]
    return node


def normalised(path, relative):
    with open(path, "rb") as handle:
        data = handle.read()
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
    actual = {p for p in files_under(actual_root) if not p.endswith(SIDECARS)}

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
        say(f"ONLY IN {actual_root}: {path}")
    differences += len(only_expected) + len(only_actual)

    common = sorted(expected & actual)
    archives = 0
    entries_compared = 0
    others = 0
    for relative in common:
        left = os.path.join(expected_root, relative)
        right = os.path.join(actual_root, relative)
        if relative.endswith(ARCHIVES):
            archives += 1
            try:
                left_entries = archive_entries(left)
                right_entries = archive_entries(right)
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
            if changed:
                if intended(relative):
                    say(f"CHANGED ON PURPOSE: {relative}")
                    intended_changes += 1
                else:
                    differences += 1
        else:
            others += 1
            if normalised(left, relative) != normalised(right, relative):
                say(f"FILE DIFFERS: {relative}")
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
