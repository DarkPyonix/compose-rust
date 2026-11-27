#!/usr/bin/env python3
"""Keep a compact baseline of the fork's Compose publication, and check a build against it.

Usage: compose-baseline.py manifest <repo> --revision <fork commit> [--target <name>] > <file>
       compose-baseline.py check <repo> <file> --changed-from-diff <name-only file> [--report <file>]
       compose-baseline.py allowed <name-only file>

The baseline is the fork line's own publication at a revision known to be good, kept as one
line per published module directory: `<directory> <files> <digest>`. The digest covers every
file under the directory, read the way compare-maven-repos.py reads them (an archive entry by
entry by content so zip timestamps do not count, a Gradle .module without its size and digest
fields, maven-metadata without <lastUpdated>, checksum sidecars left out), and the version
suffix `-ext.<N>` is read as `-ext.N`, so moving the fork to its next release alone moves
nothing.

`check` fails on any directory whose digest differs from the baseline, that is missing from
either side, unless its module is allowed to differ: a module whose sources
(compose/<group>/<module>/src/...) changed in the fork between the baseline's revision and the
pin. `allowed` prints those modules, one `org/thisisthepy/compose/<group>/<module>` per line,
from the output of `git diff --name-only <baseline revision> <pinned revision>`.

Refreshing the baseline when the pin moves: .github/scripts/refresh-compose-baseline.sh.
"""

import hashlib
import importlib.util
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GROUP_PATH = "org/thisisthepy/compose"
VERSION = re.compile(rb"-ext\.\d+")

spec = importlib.util.spec_from_file_location("compare_maven_repos", os.path.join(HERE, "compare-maven-repos.py"))
cmp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cmp)

# compare-maven-repos.py reads a `renamed` side through RENAMES; here the rename is one pattern
# on both sides, so its two functions are replaced and RENAMES only has to be non-empty.
cmp.RENAMES[:] = [("-ext.", "-ext.N")]
cmp.rename_text = lambda data: VERSION.sub(b"-ext.N", data)
cmp.rename_path = lambda path: VERSION.sub(b"-ext.N", path.encode()).decode()
cmp.UNORDERED = True


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def module_dir(relative):
    """The directory a file belongs to: its module's, above the version directory."""
    parent = os.path.dirname(relative)
    if os.path.basename(relative).startswith("maven-metadata"):
        return parent
    return os.path.dirname(parent)


def file_digest(root, relative):
    path = os.path.join(root, relative)
    if relative.endswith(cmp.ARCHIVES):
        entries = cmp.archive_entries(path, renamed=True)
        return sha("\n".join(f"{cmp.rename_path(n)} {h}" for n, h in sorted(entries.items())))
    return cmp.sha256(cmp.normalised(path, relative, renamed=True))


def manifest(root):
    groups = {}
    for relative in sorted(cmp.files_under(root)):
        if relative.endswith(cmp.SIDECARS):
            continue
        groups.setdefault(cmp.rename_path(module_dir(relative)), []).append(
            f"{cmp.rename_path(relative)} {file_digest(root, relative)}")
    return {d: (len(f), sha("\n".join(sorted(f)))) for d, f in groups.items()}


def read_manifest(path):
    header, modules = {}, {}
    with open(path) as handle:
        for line in handle:
            line = line.rstrip("\n")
            if line.startswith("# ") and ": " in line:
                key, _, value = line[2:].partition(": ")
                header[key] = value
            elif line and not line.startswith("#"):
                directory, files, digest = line.split(" ")
                modules[directory] = (int(files), digest)
    return header, modules


def allowed(names_file):
    prefixes = set()
    with open(names_file) as handle:
        for name in handle:
            m = re.match(r"compose/([^/]+)/([^/]+)/src/", name)
            if m:
                prefixes.add(f"{GROUP_PATH}/{m.group(1)}/{m.group(2)}")
    return sorted(prefixes)


PLATFORM = re.compile(r"-(macos|linux|mingw|ios|tvos|watchos|android|js|wasm|desktop|jvm|uikit)[a-z0-9]*")


def is_allowed(directory, prefixes):
    for prefix in prefixes:
        if directory == prefix:
            return True
        if directory.startswith(prefix + "-") and PLATFORM.fullmatch(directory[len(prefix):]):
            return True
    return False


def option(args, name):
    if name not in args:
        return None
    index = args.index(name)
    value = args[index + 1]
    del args[index:index + 2]
    return value


def main(argv):
    args = list(argv)
    if not args:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    command = args.pop(0)
    if command == "allowed" and len(args) == 1:
        print("\n".join(allowed(args[0])))
        return 0
    if command == "manifest":
        revision = option(args, "--revision")
        target = option(args, "--target") or "unknown"
        if len(args) != 1 or not revision or not os.path.isdir(args[0]):
            print("usage: manifest <repo> --revision <fork commit> [--target <name>]", file=sys.stderr)
            return 2
        modules = manifest(args[0])
        if not modules:
            print("the repository is empty", file=sys.stderr)
            return 1
        print("# Baseline of the fork's Compose publication, read by .github/scripts/compose-baseline.py.")
        print("# Refresh: .github/scripts/refresh-compose-baseline.sh <run id>")
        print(f"# revision: {revision}")
        print(f"# target: {target}")
        for directory in sorted(modules):
            files, digest = modules[directory]
            print(f"{directory} {files} {digest}")
        return 0
    if command == "check":
        names = option(args, "--changed-from-diff")
        report = option(args, "--report")
        if len(args) != 2 or not names:
            print("usage: check <repo> <file> --changed-from-diff <name-only file> [--report <file>]", file=sys.stderr)
            return 2
        header, expected = read_manifest(args[1])
        actual = manifest(args[0])
        prefixes = allowed(names)
        lines, failures = [], 0

        def say(text):
            print(text)
            lines.append(text)

        say(f"baseline revision {header.get('revision', '?')}, target {header.get('target', '?')}")
        for prefix in prefixes:
            say(f"allowed to differ: {prefix}")
        for directory in sorted(expected.keys() | actual.keys()):
            before, after = expected.get(directory), actual.get(directory)
            if before == after:
                continue
            what = "MISSING" if after is None else "NEW" if before is None else "DIFFERS"
            if is_allowed(directory, prefixes):
                say(f"CHANGED ON PURPOSE ({what}): {directory}")
            else:
                say(f"{what}: {directory} (baseline {before}, build {after})")
                failures += 1
        say(f"{len(expected)} baseline directories, {len(actual)} built, {failures} unexplained")
        say("IDENTICAL" if failures == 0 else "DIFFERENT")
        if report:
            with open(report, "w") as handle:
                handle.write("\n".join(lines) + "\n")
        return 0 if failures == 0 else 1
    print(__doc__.strip().splitlines()[2], file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
