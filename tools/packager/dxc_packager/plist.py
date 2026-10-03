"""Info.plist for a dioxus-compose application bundle.

A bundle either updates itself with Sparkle or does not update at all (the plain .app a
sample release hands out). Everything that differs between the two is decided here, from
whether Sparkle settings were given, so a bundle cannot carry half of Sparkle.

Nothing here asks for an entitlement. The bundle is signed ad hoc without the hardened
runtime and without a sandbox, which is what runs on a Mac without an Apple developer
account behind it, and the renderer needs no entitlement to run that way: it is compiled
ahead of time, so nothing generates code at run time, and Skia draws through Metal.
"""

import plistlib
from dataclasses import dataclass
from urllib.parse import urlparse

from . import ed25519
from .metadata import PackagingError

SPARKLE = "sparkle"
NONE = "none"
UPDATERS = (SPARKLE, NONE)

# Sparkle checks once a day unless told otherwise, and will not check more often than
# once an hour however it is told.
DEFAULT_CHECK_INTERVAL = 86400
MINIMUM_CHECK_INTERVAL = 3600

_LOOPBACK = {"localhost", "127.0.0.1", "::1"}


@dataclass(frozen=True)
class SparkleSettings:
    feed_url: str
    public_ed_key: str
    automatic_checks: bool = True
    check_interval: int = DEFAULT_CHECK_INTERVAL
    automatically_update: bool = False


def check_feed_url(url):
    parsed = urlparse(url)
    if parsed.scheme == "https" and parsed.netloc:
        return url
    if parsed.scheme == "http" and parsed.hostname in _LOOPBACK:
        # Loopback is how an update is rehearsed on one machine. Nothing else gets http:
        # the EdDSA signature protects the archive, but not the appcast that says which
        # archive is newest or what its release notes say.
        return url
    if parsed.scheme == "file" and parsed.path:
        # A feed on this disk: the same rehearsal with no server at all.
        return url
    raise PackagingError(
        f"SUFeedURL {url!r} must be https. Plain http to localhost and file:// are "
        f"accepted for rehearsing an update on one machine."
    )


def info_plist(metadata, *, sparkle=None, extra=None):
    """The Info.plist dictionary for `metadata`, updating itself when `sparkle` is given."""
    plist = {
        "CFBundleDevelopmentRegion": "en",
        "CFBundleDisplayName": metadata.display_name,
        "CFBundleExecutable": metadata.executable,
        "CFBundleIdentifier": metadata.identifier,
        "CFBundleInfoDictionaryVersion": "6.0",
        "CFBundleName": metadata.name,
        "CFBundlePackageType": "APPL",
        "CFBundleShortVersionString": metadata.version,
        "CFBundleSupportedPlatforms": ["MacOSX"],
        "CFBundleVersion": metadata.build,
        "LSMinimumSystemVersion": metadata.minimum_system_version,
        # Without this a bundled application is drawn at 1x and scaled up, which shows as
        # stepped edges on every rounded corner. A bare executable gets 2x by default, so
        # the regression appears the moment an application is first put in a bundle.
        "NSHighResolutionCapable": True,
        "NSPrincipalClass": "NSApplication",
        "NSSupportsAutomaticGraphicsSwitching": True,
    }
    if metadata.icon is not None:
        plist["CFBundleIconFile"] = "AppIcon"
    if metadata.copyright:
        plist["NSHumanReadableCopyright"] = metadata.copyright
    if metadata.category:
        plist["LSApplicationCategoryType"] = metadata.category

    if sparkle is not None:
        ed25519.decode_public_key(sparkle.public_ed_key)
        if sparkle.check_interval < MINIMUM_CHECK_INTERVAL:
            raise PackagingError(
                f"Sparkle does not check more often than every {MINIMUM_CHECK_INTERVAL} "
                f"seconds; {sparkle.check_interval} was asked for"
            )
        plist.update(
            {
                "SUFeedURL": check_feed_url(sparkle.feed_url),
                "SUPublicEDKey": sparkle.public_ed_key,
                "SUEnableAutomaticChecks": sparkle.automatic_checks,
                "SUScheduledCheckInterval": sparkle.check_interval,
                "SUAutomaticallyUpdate": sparkle.automatically_update,
            }
        )

    for key, value in (extra or {}).items():
        if key in plist and plist[key] != value:
            raise PackagingError(
                f"the extra Info.plist entries set {key}, which the packager already "
                f"derives ({plist[key]!r}); change it where it comes from instead"
            )
        if sparkle is None and key.startswith("SU"):
            raise PackagingError(
                f"{key} is a Sparkle setting, and this bundle has no Sparkle; package it "
                f"with --updater sparkle"
            )
        plist[key] = value
    return plist


def dumps(dictionary):
    return plistlib.dumps(dictionary, fmt=plistlib.FMT_XML, sort_keys=True)


def parse_extra(pairs):
    """`KEY=VALUE` pairs from the command line. true/false become booleans, digits integers."""
    extra = {}
    for pair in pairs or []:
        key, separator, value = pair.partition("=")
        if not separator or not key:
            raise PackagingError(f"--plist expects KEY=VALUE, not {pair!r}")
        if value in ("true", "false"):
            extra[key] = value == "true"
        elif value.isdigit():
            extra[key] = int(value)
        else:
            extra[key] = value
    return extra
