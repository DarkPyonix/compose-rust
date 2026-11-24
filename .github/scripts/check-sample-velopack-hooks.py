"""Runs every built sample with each of Velopack's hooks and requires it to exit at once.

    python3 -I .github/scripts/check-sample-velopack-hooks.py \
        --bin-dir target/release --built built.txt [--exe-suffix .exe]

Velopack's installer and updater start the installed program with one of
--veloapp-install, --veloapp-updated, --veloapp-obsolete or --veloapp-uninstall and a
version, and wait for it to answer and exit. A sample that calls
`velopack::VelopackApp::build().run()` first answers and exits with status 0 before its
window opens. One that does not opens its window instead, and an install would show the
application mid-install and wait on it: here that is a run that does not end within the
time limit (or, on a runner without a display, one that fails trying to open a window).
"""

import argparse
import subprocess
import sys
import time
from pathlib import Path

HOOKS = ("--veloapp-install", "--veloapp-updated", "--veloapp-obsolete", "--veloapp-uninstall")
LIMIT = 15.0


def check(bin_dir, built, suffix):
    problems = []
    for slug in built:
        exe = bin_dir / f"sample-{slug}{suffix}"
        if not exe.is_file():
            problems.append(f"sample-{slug}: no executable at {exe}")
            continue
        for hook in HOOKS:
            start = time.monotonic()
            try:
                done = subprocess.run([str(exe), hook, "1.0.0"], capture_output=True, text=True, timeout=LIMIT)
            except subprocess.TimeoutExpired:
                problems.append(f"sample-{slug} {hook}: still running after {LIMIT:.0f} s, so it opened its "
                                "window instead of answering the hook")
                continue
            took = time.monotonic() - start
            if done.returncode != 0:
                tail = (done.stderr or done.stdout).strip().splitlines()[-3:]
                problems.append(f"sample-{slug} {hook}: exited with {done.returncode} after {took:.1f} s: "
                                + " | ".join(tail))
            else:
                print(f"ok    sample-{slug} {hook} answered in {took:.2f} s")
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--bin-dir", required=True, type=Path)
    parser.add_argument("--built", required=True, type=Path)
    parser.add_argument("--exe-suffix", default="")
    args = parser.parse_args(argv)
    built = [line.strip() for line in args.built.read_text().splitlines() if line.strip()]
    problems = check(args.bin_dir, built, args.exe_suffix)
    for problem in problems:
        print(f"::error title=Velopack hook not answered::{problem}")
    if problems:
        return 1
    print(f"ok    {len(built)} sample(s) answer every Velopack hook and exit before opening a window")
    return 0


if __name__ == "__main__":
    sys.exit(main())
