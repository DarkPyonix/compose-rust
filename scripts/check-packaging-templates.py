#!/usr/bin/env python3
"""Checks that scripts/packaging-templates/ is the Compose fork's extended/packaging at the
pinned commit, and moves the pin.

    python3 scripts/check-packaging-templates.py              # the copy matches PIN
    python3 scripts/check-packaging-templates.py --fetch      # ... and PIN matches the fork
    python3 scripts/check-packaging-templates.py --fork-dir D # ... against a checkout at D
    python3 scripts/check-packaging-templates.py --bump REV   # copy REV's files, rewrite PIN

The packagers render these templates (the format templates and their metadata come from the
fork and are pinned to a commit). PIN names the fork, the revision, the directory in the
fork, and the git blob id of every file copied from it. Without --fetch or --fork-dir only
the copy is checked against PIN, which needs no network: a hand edit of a template shows up
as a blob id that is not the pinned one. With --fetch the fork's directory at the revision
is fetched (sparse, blobs of that directory only) into .scratch/packaging-templates/<rev>/
and every pinned blob id has to be the fork's; a template the fork has and the copy lacks
fails too. So a template can change only in the fork, and reaches the packagers only by
moving the pin with --bump.
"""

import argparse
import hashlib
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
COPY = REPO / "scripts" / "packaging-templates"
PIN = COPY / "PIN"
# What the copy holds of the fork's directory: the templates and the placeholder map they
# are filled by. The fork's own notes and scripts are not used here.
COPIED = re.compile(r"(templates/[^/]+\.in|metadata-map\.md)")
# Files of the copy that are this repository's, not the fork's.
OWN = {"PIN", "README.md"}


class PinError(Exception):
    pass


def blob_id(data):
    """The id git gives a file's content (`git hash-object`)."""
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def read_pin(path=PIN):
    """{"fork", "revision", "path", "files": {relative path: blob id}}."""
    pin = {"files": {}}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError:
        raise PinError(f"{path} does not exist")
    for number, line in enumerate(lines, 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, _, rest = line.partition(" ")
        rest = rest.strip()
        if key in ("fork", "revision", "path"):
            pin[key] = rest
        elif key == "file":
            name, _, blob = rest.partition(" ")
            if not re.fullmatch(r"[0-9a-f]{40}", blob.strip()):
                raise PinError(f"{path.name} line {number}: {name} has no 40 character blob id")
            pin["files"][name] = blob.strip()
        else:
            raise PinError(f"{path.name} line {number}: unknown entry {key!r}")
    for key in ("fork", "revision", "path"):
        if not pin.get(key):
            raise PinError(f"{path.name} names no {key}")
    if not re.fullmatch(r"[0-9a-f]{40}", pin["revision"]):
        raise PinError(f"{path.name}: revision {pin['revision']!r} is not a 40 character commit id")
    if not pin["files"]:
        raise PinError(f"{path.name} pins no file")
    return pin


def copy_problems(pin, copy=COPY):
    """The copy against PIN: every pinned file there with its blob id, nothing unpinned."""
    problems = []
    for name, blob in sorted(pin["files"].items()):
        path = copy / name
        if not path.is_file():
            problems.append(f"{name} is pinned and not in {copy.relative_to(REPO)}")
        elif blob_id(path.read_bytes()) != blob:
            problems.append(f"{name} is not the pinned file (blob {blob_id(path.read_bytes())}, "
                            f"pinned {blob}): change it in the fork and move the pin with --bump")
    for path in sorted(copy.rglob("*")):
        name = path.relative_to(copy).as_posix()
        if path.is_file() and name not in OWN and name not in pin["files"]:
            problems.append(f"{name} is in {copy.relative_to(REPO)} and not pinned")
    return problems


def fork_files(root):
    """{relative path: blob id} of what the copy takes from a checkout of the fork's directory."""
    return {path.relative_to(root).as_posix(): blob_id(path.read_bytes())
            for path in root.rglob("*")
            if path.is_file() and COPIED.fullmatch(path.relative_to(root).as_posix())}


def fork_problems(pin, root):
    found = fork_files(root)
    problems = []
    for name, blob in sorted(pin["files"].items()):
        if name not in found:
            problems.append(f"{name} is pinned and the fork has no {pin['path']}/{name} at {pin['revision'][:12]}")
        elif found[name] != blob:
            problems.append(f"{name}: PIN says blob {blob}, the fork at {pin['revision'][:12]} has {found[name]}")
    for name in sorted(set(found) - set(pin["files"])):
        problems.append(f"the fork has {pin['path']}/{name} at {pin['revision'][:12]} and the copy does not")
    return problems


def git(*args, cwd):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True)


def fetch(fork, revision, path):
    """A sparse checkout of `path` at `revision`; returns the directory holding `path`."""
    dest = REPO / ".scratch" / "packaging-templates" / revision
    if (dest / ".complete").is_file():
        return dest / path
    shutil.rmtree(dest, ignore_errors=True)
    dest.mkdir(parents=True)
    try:
        git("init", "-q", cwd=dest)
        git("remote", "add", "origin", fork, cwd=dest)
        # On standard input, so Git Bash cannot rewrite the pattern as a Windows path.
        subprocess.run(["git", "sparse-checkout", "set", "--no-cone", "--stdin"], cwd=dest, check=True,
                       input=f"/{path}/\n", capture_output=True, text=True)
        git("fetch", "-q", "--depth", "1", "--filter=blob:none", "origin", revision, cwd=dest)
        git("-c", "advice.detachedHead=false", "checkout", "-q", revision, cwd=dest)
    except subprocess.CalledProcessError as error:
        shutil.rmtree(dest, ignore_errors=True)
        raise PinError(f"could not fetch {revision} of {fork}: {(error.stderr or '').strip()}")
    if not (dest / path).is_dir():
        shutil.rmtree(dest, ignore_errors=True)
        raise PinError(f"{revision} of {fork} has no {path}")
    (dest / ".complete").touch()
    return dest / path


def write_pin(pin, files):
    lines = [
        "# The Compose fork's packaging templates, copied here at one commit.",
        "# Change a template in the fork, then move this pin:",
        "#   python3 scripts/check-packaging-templates.py --bump <revision>",
        f"fork {pin['fork']}",
        f"revision {pin['revision']}",
        f"path {pin['path']}",
    ]
    lines += [f"file {name} {blob}" for name, blob in sorted(files.items())]
    PIN.write_text("\n".join(lines) + "\n", encoding="utf-8")


def bump(pin, revision, root):
    """Replace the copy with the fork's files at `revision` and pin them."""
    for path in list(COPY.rglob("*")):
        if path.is_file() and path.relative_to(COPY).as_posix() not in OWN:
            path.unlink()
    files = {}
    for name in sorted(fork_files(root)):
        target = COPY / name
        target.parent.mkdir(parents=True, exist_ok=True)
        data = (root / name).read_bytes()
        target.write_bytes(data)
        files[name] = blob_id(data)
    write_pin({**pin, "revision": revision}, files)
    return files


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    where = parser.add_mutually_exclusive_group()
    where.add_argument("--fetch", action="store_true", help="also compare PIN with the fork, fetched")
    where.add_argument("--fork-dir", type=Path, help="also compare PIN with this checkout of the pinned directory")
    where.add_argument("--bump", metavar="REVISION", help="copy the fork's files at REVISION and pin them")
    args = parser.parse_args(argv)
    try:
        pin = read_pin()
        if args.bump:
            if not re.fullmatch(r"[0-9a-f]{40}", args.bump):
                raise PinError(f"--bump wants a 40 character commit id, not {args.bump!r}")
            files = bump(pin, args.bump, fetch(pin["fork"], args.bump, pin["path"]))
            print(f"ok    pinned {len(files)} files of {pin['path']} at {args.bump}")
            return 0
        problems = copy_problems(pin)
        if args.fetch or args.fork_dir:
            root = args.fork_dir if args.fork_dir else fetch(pin["fork"], pin["revision"], pin["path"])
            problems += fork_problems(pin, Path(root))
    except PinError as error:
        print(f"::error title=Packaging templates::{error}")
        return 1
    for problem in problems:
        print(f"::error title=Packaging templates::{problem}")
    if problems:
        return 1
    against = " and the fork" if (args.fetch or args.fork_dir) else ""
    print(f"ok    {len(pin['files'])} packaging template files match PIN{against} "
          f"({pin['path']} at {pin['revision'][:12]})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
