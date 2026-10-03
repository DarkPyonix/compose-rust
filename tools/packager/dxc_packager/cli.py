"""package-macos: bundle, sign and publish a dioxus-compose application for macOS.

Every bundle is signed ad hoc, which needs no Apple developer account: Apple silicon runs
it, Gatekeeper asks the person once before the first launch of a downloaded copy (the
disk image carries the notes that say how), and Sparkle verifies updates by their EdDSA
signature.

A bundle that updates itself:
    package-macos keygen --out keys/sparkle.key            # once, ever; keep the key
    package-macos app --updater sparkle --manifest-dir . --executable target/release/app \\
        --feed-url https://example.com/appcast.xml --ed-key-file keys/sparkle.key \\
        --sparkle-framework Sparkle.framework --out dist
    package-macos dmg --app dist/App.app --out dist/App-1.2.0.dmg      # first install
    package-macos archive --app dist/App.app --out site/App-1.2.0.zip  # what Sparkle fetches
    package-macos appcast --appcast site/appcast.xml --app dist/App.app \\
        --archive site/App-1.2.0.zip --url https://example.com/App-1.2.0.zip \\
        --ed-key-file keys/sparkle.key --sign-update Sparkle/bin/sign_update

A plain application (no updates): --updater none, and no Sparkle options.
"""

import argparse
import plistlib
import sys
from pathlib import Path

from . import appcast, bundle, metadata, plist, signing
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
    parser.add_argument("--updater", required=True, choices=plist.UPDATERS,
                        help="sparkle: the app updates itself from an appcast; none: it does not")
    parser.add_argument("--out", required=True, type=Path, help="directory to put Name.app in")
    parser.add_argument("--version", help="CFBundleShortVersionString; default from Cargo.toml")
    parser.add_argument("--build", help="CFBundleVersion; default the version")
    parser.add_argument("--category", help="LSApplicationCategoryType or a dx category name")
    parser.add_argument("--minimum-system-version")
    parser.add_argument("--plist", action="append", metavar="KEY=VALUE",
                        help="an extra Info.plist entry; repeatable")
    parser.add_argument("--plist-file", type=Path,
                        help="a plist whose entries are added to Info.plist")

    sparkle = parser.add_argument_group("--updater sparkle")
    sparkle.add_argument("--feed-url")
    key = sparkle.add_mutually_exclusive_group()
    key.add_argument("--public-ed-key", help="SUPublicEDKey, base64")
    key.add_argument("--ed-key-file", type=Path,
                     help="the private key file; SUPublicEDKey is derived from it")
    sparkle.add_argument("--sparkle-framework", type=Path)
    sparkle.add_argument("--check-interval", type=int, default=plist.DEFAULT_CHECK_INTERVAL)
    sparkle.add_argument("--no-automatic-checks", action="store_true")
    sparkle.add_argument("--automatically-update", action="store_true",
                         help="download and install without asking (SUAutomaticallyUpdate)")


def command_app(args):
    meta = metadata.load(args.manifest_dir, version=args.version, build=args.build)
    meta = metadata.with_overrides(
        meta, category=args.category, minimum_system_version=args.minimum_system_version
    )
    extra = plist.parse_extra(args.plist)
    if args.plist_file:
        extra.update(plistlib.loads(args.plist_file.read_bytes()))

    sparkle = None
    if args.updater == plist.SPARKLE:
        public_key = args.public_ed_key
        if args.ed_key_file:
            public_key = signing.public_key_of(args.ed_key_file)
        if not args.feed_url or not public_key:
            raise PackagingError(
                "--updater sparkle needs --feed-url and a key (--ed-key-file or "
                "--public-ed-key): without them the application has nowhere to look for "
                "updates and nothing to check them against"
            )
        sparkle = plist.SparkleSettings(
            feed_url=args.feed_url,
            public_ed_key=public_key,
            automatic_checks=not args.no_automatic_checks,
            check_interval=args.check_interval,
            automatically_update=args.automatically_update,
        )
    elif args.feed_url or args.public_ed_key or args.ed_key_file or args.sparkle_framework:
        raise PackagingError("Sparkle options were given with --updater none")

    args.out.mkdir(parents=True, exist_ok=True)
    app = bundle.assemble(
        meta,
        args.executable,
        args.out,
        sparkle=sparkle,
        sparkle_framework=args.sparkle_framework,
        extra_plist=extra,
        renderer_dir=args.renderer_dir,
    )
    bundle.sign(app)
    problems = _verify(app, args.updater)
    if problems:
        raise PackagingError("the bundle is not shippable:\n  " + "\n  ".join(problems))
    print(app)


def _verify(app, updater):
    problems = bundle.unsigned_code(app) + bundle.absolute_references(app)
    if updater == plist.SPARKLE and not bundle.sparkle_in(app):
        problems.append("the bundle updates itself but has no Sparkle.framework")
    if updater == plist.NONE and bundle.sparkle_in(app):
        problems.append("the bundle does not update itself but carries Sparkle.framework")
    return problems


def command_verify(args):
    problems = _verify(args.app, args.updater)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"ok    {args.app}: every piece of code is signed and loads nothing from outside")
    return 0


def command_archive(args):
    print(bundle.zip_for_sparkle(args.app, args.out))


def command_dmg(args):
    print(bundle.dmg(args.app, args.out, args.volume_name or args.app.stem))


def command_keygen(args):
    public = signing.keygen(args.out)
    print(f"private key written to {args.out}; keep it out of the repository")
    print(f"SUPublicEDKey: {public}")


def command_public_key(args):
    print(signing.public_key_of(args.key))


def command_appcast(args):
    info = plistlib.loads((args.app / "Contents" / "Info.plist").read_bytes())
    public_key = info.get("SUPublicEDKey")
    if not public_key:
        raise PackagingError(f"{args.app} has no SUPublicEDKey; it is not a sparkle build")
    signature = signing.sign_archive(
        args.archive, args.ed_key_file, sign_update=args.sign_update, public_key=public_key
    )
    item = appcast.Item(
        version=info["CFBundleVersion"],
        short_version=info["CFBundleShortVersionString"],
        url=args.url,
        length=args.archive.stat().st_size,
        ed_signature=signature,
        minimum_system_version=info.get("LSMinimumSystemVersion", "13.0"),
        pub_date=appcast.now(),
        title=args.title,
        release_notes_url=args.release_notes_url,
        critical=args.critical,
        hardware_requirements=bundle.hardware_requirements(args.app),
    )
    if args.appcast.exists():
        feed = appcast.parse(args.appcast.read_text())
    else:
        feed = appcast.new_feed(args.feed_title or info.get("CFBundleName", "Updates"))
    appcast.add(feed, item)
    args.appcast.parent.mkdir(parents=True, exist_ok=True)
    args.appcast.write_text(appcast.dumps(feed))
    print(f"{args.appcast}: {', '.join(appcast.versions(feed))}")


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
    verify.add_argument("--updater", required=True, choices=plist.UPDATERS)
    verify.set_defaults(run=command_verify)

    archive = commands.add_parser("archive", help="the zip Sparkle downloads")
    archive.add_argument("--app", required=True, type=Path)
    archive.add_argument("--out", required=True, type=Path)
    archive.set_defaults(run=command_archive)

    dmg = commands.add_parser("dmg", help="a disk image for the first install, with install notes")
    dmg.add_argument("--app", required=True, type=Path)
    dmg.add_argument("--out", required=True, type=Path)
    dmg.add_argument("--volume-name")
    dmg.set_defaults(run=command_dmg)

    keygen = commands.add_parser("keygen", help="a new EdDSA key pair for Sparkle")
    keygen.add_argument("--out", required=True, type=Path)
    keygen.set_defaults(run=command_keygen)

    public_key = commands.add_parser("public-key", help="SUPublicEDKey for a private key file")
    public_key.add_argument("--key", required=True, type=Path)
    public_key.set_defaults(run=command_public_key)

    feed = commands.add_parser("appcast", help="add a signed version to an appcast")
    feed.add_argument("--appcast", required=True, type=Path)
    feed.add_argument("--app", required=True, type=Path,
                      help="the bundle inside the archive; versions and key are read from it")
    feed.add_argument("--archive", required=True, type=Path)
    feed.add_argument("--url", required=True, help="where the archive will be downloaded from")
    feed.add_argument("--ed-key-file", required=True, type=Path)
    feed.add_argument("--sign-update", type=Path, help="Sparkle's sign_update, preferred when given")
    feed.add_argument("--title")
    feed.add_argument("--feed-title")
    feed.add_argument("--release-notes-url")
    feed.add_argument("--critical", action="store_true")
    feed.set_defaults(run=command_appcast)
    return root


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        status = args.run(args)
    except PackagingError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return status or 0
