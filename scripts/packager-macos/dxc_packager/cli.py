"""package-macos: bundle, sign and publish a compose-rust application for macOS.

Every bundle is signed ad hoc, which needs no Apple developer account: Apple silicon runs
it, Gatekeeper asks the person once before the first launch of a downloaded copy (the
disk image carries the notes that say how).

    package-macos app --manifest-dir . --executable target/release/app --out dist
    package-macos dmg --app dist/App.app --out dist/App-1.2.0.dmg

The bundle carries no update settings: direct distribution is packaged and updated with
Velopack.
"""

import argparse
import plistlib
import sys
from pathlib import Path

from . import bundle, metadata, plist
from .metadata import PackagingError


def _yes_no(value):
    if value in ("yes", "true"):
        return True
    if value in ("no", "false"):
        return False
    raise argparse.ArgumentTypeError("answer yes or no")


def _add_app_arguments(parser):
    parser.add_argument("--manifest-dir", required=True, type=Path,
                        help="directory holding the application's Cargo.toml and Dioxus.toml")
    parser.add_argument("--executable", required=True, type=Path,
                        help="the executable cargo built (not one already taken from a bundle)")
    parser.add_argument("--renderer-dir", type=Path,
                        help="the renderer's lib directory, when the executable names it "
                             "through @rpath rather than by its path")
    parser.add_argument("--out", required=True, type=Path, help="directory to put Name.app in")
    parser.add_argument("--version", help="CFBundleShortVersionString; default from Cargo.toml")
    parser.add_argument("--build", help="CFBundleVersion; default the version")
    parser.add_argument("--category", help="LSApplicationCategoryType or a dx category name")
    parser.add_argument("--minimum-system-version")
    parser.add_argument("--plist", action="append", metavar="KEY=VALUE",
                        help="an extra Info.plist entry; repeatable")
    parser.add_argument("--plist-file", type=Path,
                        help="a plist whose entries are added to Info.plist")


def command_app(args):
    meta = metadata.load(args.manifest_dir, version=args.version, build=args.build)
    meta = metadata.with_overrides(
        meta, category=args.category, minimum_system_version=args.minimum_system_version
    )
    extra = plist.parse_extra(args.plist)
    if args.plist_file:
        extra.update(plistlib.loads(args.plist_file.read_bytes()))

    args.out.mkdir(parents=True, exist_ok=True)
    app = bundle.assemble(
        meta,
        args.executable,
        args.out,
        extra_plist=extra,
        renderer_dir=args.renderer_dir,
    )
    bundle.sign(app)
    problems = _verify(app)
    if problems:
        raise PackagingError("the bundle is not shippable:\n  " + "\n  ".join(problems))
    print(app)


def _verify(app):
    return bundle.unsigned_code(app) + bundle.absolute_references(app)


def command_verify(args):
    problems = _verify(args.app)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"ok    {args.app}: every piece of code is signed and loads nothing from outside")
    return 0


def command_dmg(args):
    print(bundle.dmg(args.app, args.out, args.volume_name or args.app.stem))


def parser():
    root = argparse.ArgumentParser(
        prog="package-macos", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    commands = root.add_subparsers(dest="command", required=True)

    app = commands.add_parser("app", help="make Name.app and sign it ad hoc")
    _add_app_arguments(app)
    app.set_defaults(run=command_app)

    verify = commands.add_parser("verify", help="check a bundle is signed and self-contained")
    verify.add_argument("--app", required=True, type=Path)
    verify.set_defaults(run=command_verify)

    dmg = commands.add_parser("dmg", help="a disk image for the first install, with install notes")
    dmg.add_argument("--app", required=True, type=Path)
    dmg.add_argument("--out", required=True, type=Path)
    dmg.add_argument("--volume-name")
    dmg.set_defaults(run=command_dmg)
    return root


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        status = args.run(args)
    except PackagingError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return status or 0
