"""package-macos: bundle, sign and publish a dioxus-compose application for macOS.

The two channels, end to end:

  Sparkle (Developer ID, updates itself):
    package-macos keygen --out keys/sparkle.key            # once, ever; keep the key
    package-macos app --channel sparkle --manifest-dir . --executable target/release/app \\
        --feed-url https://example.com/appcast.xml --ed-key-file keys/sparkle.key \\
        --sparkle-framework Sparkle.framework --sign "Developer ID Application: ..." \\
        --out dist
    package-macos archive --app dist/App.app --out dist/App-1.2.0.zip
    package-macos notarize --path dist/App-1.2.0.zip --keychain-profile notary --staple dist/App.app
    package-macos archive --app dist/App.app --out dist/App-1.2.0.zip   # again, stapled
    package-macos appcast --appcast site/appcast.xml --app dist/App.app \\
        --archive dist/App-1.2.0.zip --url https://example.com/App-1.2.0.zip \\
        --ed-key-file keys/sparkle.key --sign-update Sparkle/bin/sign_update

  Mac App Store (sandboxed, updated by the store):
    package-macos app --channel app-store --manifest-dir . --executable target/release/app \\
        --uses-non-exempt-encryption no --network-client --user-selected-files read-write \\
        --provisioning-profile App.provisionprofile --team-id ABCDE12345 \\
        --sign "3rd Party Mac Developer Application: ..." --out dist-store
    package-macos pkg --app dist-store/App.app --out dist-store/App.pkg \\
        --sign "3rd Party Mac Developer Installer: ..."
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
    parser.add_argument("--channel", required=True, choices=plist.CHANNELS)
    parser.add_argument("--out", required=True, type=Path, help="directory to put Name.app in")
    parser.add_argument("--version", help="CFBundleShortVersionString; default from Cargo.toml")
    parser.add_argument("--build", help="CFBundleVersion; default the version")
    parser.add_argument("--category", help="LSApplicationCategoryType or a dx category name")
    parser.add_argument("--minimum-system-version")
    parser.add_argument("--plist", action="append", metavar="KEY=VALUE",
                        help="an extra Info.plist entry; repeatable")
    parser.add_argument("--plist-file", type=Path,
                        help="a plist whose entries are added to Info.plist")
    parser.add_argument("--sign", default="-",
                        help='codesign identity; "-" (the default) signs ad hoc')
    parser.add_argument("--keychain", type=Path, help="keychain holding the identity")

    sparkle = parser.add_argument_group("sparkle channel")
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

    store = parser.add_argument_group("app-store channel")
    store.add_argument("--uses-non-exempt-encryption", type=_yes_no)
    store.add_argument("--provisioning-profile", type=Path)
    store.add_argument("--team-id")
    store.add_argument("--network-client", action="store_true")
    store.add_argument("--network-server", action="store_true")
    store.add_argument("--user-selected-files", choices=("read-only", "read-write"))
    store.add_argument("--downloads-folder", choices=("read-only", "read-write"))


def command_app(args):
    meta = metadata.load(args.manifest_dir, version=args.version, build=args.build)
    meta = metadata.with_overrides(
        meta, category=args.category, minimum_system_version=args.minimum_system_version
    )
    extra = plist.parse_extra(args.plist)
    if args.plist_file:
        extra.update(plistlib.loads(args.plist_file.read_bytes()))

    sparkle = None
    if args.channel == plist.SPARKLE:
        public_key = args.public_ed_key
        if args.ed_key_file:
            public_key = signing.public_key_of(args.ed_key_file)
        if not args.feed_url or not public_key:
            raise PackagingError("the sparkle channel needs --feed-url and a key")
        sparkle = plist.SparkleSettings(
            feed_url=args.feed_url,
            public_ed_key=public_key,
            automatic_checks=not args.no_automatic_checks,
            check_interval=args.check_interval,
            automatically_update=args.automatically_update,
        )

    args.out.mkdir(parents=True, exist_ok=True)
    app = bundle.assemble(
        meta,
        args.executable,
        args.out,
        args.channel,
        sparkle=sparkle,
        sparkle_framework=args.sparkle_framework,
        uses_non_exempt_encryption=args.uses_non_exempt_encryption,
        extra_plist=extra,
        provisioning_profile=args.provisioning_profile,
    )
    capabilities = plist.Capabilities(
        network_client=args.network_client,
        network_server=args.network_server,
        user_selected_files=args.user_selected_files,
        downloads_folder=args.downloads_folder,
    )
    entitlements = plist.entitlements(
        args.channel, capabilities=capabilities, team_id=args.team_id, identifier=meta.identifier
    )
    if args.sign == "-":
        print("note: signing ad hoc. This bundle runs here and cannot be distributed.")
    bundle.sign(app, args.sign, args.channel, entitlements, keychain=args.keychain)
    problems = _verify(app, args.channel)
    if problems:
        raise PackagingError("the bundle is not shippable:\n  " + "\n  ".join(problems))
    print(app)


def _verify(app, channel):
    problems = bundle.absolute_references(app)
    granted = bundle.entitlements_of(app)
    if channel == plist.APP_STORE:
        problems += bundle.sparkle_residue(app)
        if not granted.get("com.apple.security.app-sandbox"):
            problems.append("the app-store build is not sandboxed")
    else:
        if not (app / "Contents" / "Frameworks" / "Sparkle.framework").exists():
            problems.append("the sparkle build has no Sparkle.framework")
        if granted.get("com.apple.security.app-sandbox"):
            problems.append("the sparkle build is sandboxed, and Sparkle cannot replace it")
    return problems


def command_verify(args):
    problems = _verify(args.app, args.channel)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"ok    {args.app} is a {args.channel} bundle")
    return 0


def command_pkg(args):
    problems = _verify(args.app, plist.APP_STORE)
    if problems:
        raise PackagingError(
            "only an app-store bundle goes into the store package:\n  " + "\n  ".join(problems)
        )
    if not args.sign:
        print("note: the package is unsigned. App Store Connect needs it signed with a "
              "3rd Party Mac Developer Installer (or Mac Installer Distribution) identity.")
    print(bundle.pkg(args.app, args.out, args.sign))


def command_archive(args):
    print(bundle.zip_for_sparkle(args.app, args.out))


def command_dmg(args):
    print(bundle.dmg(args.app, args.out, args.volume_name or args.app.stem))


def command_notarize(args):
    bundle.notarize(args.path, args.keychain_profile)
    if args.staple:
        bundle.staple(args.staple)


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

    app = commands.add_parser("app", help="make and sign Name.app for one channel")
    _add_app_arguments(app)
    app.set_defaults(run=command_app)

    verify = commands.add_parser("verify", help="check a bundle is what its channel needs")
    verify.add_argument("--app", required=True, type=Path)
    verify.add_argument("--channel", required=True, choices=plist.CHANNELS)
    verify.set_defaults(run=command_verify)

    pkg = commands.add_parser("pkg", help="the installer package for App Store Connect")
    pkg.add_argument("--app", required=True, type=Path)
    pkg.add_argument("--out", required=True, type=Path)
    pkg.add_argument("--sign", help="installer identity")
    pkg.set_defaults(run=command_pkg)

    archive = commands.add_parser("archive", help="the zip Sparkle downloads")
    archive.add_argument("--app", required=True, type=Path)
    archive.add_argument("--out", required=True, type=Path)
    archive.set_defaults(run=command_archive)

    dmg = commands.add_parser("dmg", help="a disk image for handing the app out directly")
    dmg.add_argument("--app", required=True, type=Path)
    dmg.add_argument("--out", required=True, type=Path)
    dmg.add_argument("--volume-name")
    dmg.set_defaults(run=command_dmg)

    notarize = commands.add_parser("notarize", help="notarize a zip, dmg or pkg and staple")
    notarize.add_argument("--path", required=True, type=Path)
    notarize.add_argument("--keychain-profile", required=True)
    notarize.add_argument("--staple", type=Path, help="app or dmg to staple the ticket to")
    notarize.set_defaults(run=command_notarize)

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
