"""Info.plist and entitlements for the two macOS channels.

There are two ways an application reaches someone, and they want different bundles:

- `sparkle`: signed with a Developer ID, notarized, handed out as a download, and updated
  in place by Sparkle from an appcast. Hardened runtime, no sandbox, Sparkle inside.
- `app-store`: signed for the Mac App Store, sandboxed, installed and updated by the store.
  It must not contain Sparkle or anything else that updates the application by itself.

Everything that differs between them is decided here, from the channel, so a bundle
cannot be half one and half the other.
"""

import plistlib
from dataclasses import dataclass
from urllib.parse import urlparse

from . import ed25519
from .metadata import PackagingError

SPARKLE = "sparkle"
APP_STORE = "app-store"
CHANNELS = (SPARKLE, APP_STORE)

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
    raise PackagingError(
        f"SUFeedURL {url!r} must be https. Plain http is accepted only for localhost, "
        f"which is for rehearsing an update on one machine."
    )


def info_plist(metadata, channel, *, sparkle=None, uses_non_exempt_encryption=None, extra=None):
    """The Info.plist dictionary for `metadata` packaged for `channel`."""
    if channel not in CHANNELS:
        raise PackagingError(f"channel {channel!r} is not one of {', '.join(CHANNELS)}")

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

    if channel == SPARKLE:
        if sparkle is None:
            raise PackagingError(
                "the sparkle channel needs a feed URL and the public EdDSA key "
                "(--feed-url and --public-ed-key): an application without them has "
                "nowhere to look for updates and nothing to check them against"
            )
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
    else:
        if sparkle is not None:
            raise PackagingError(
                "the app-store channel cannot carry Sparkle settings: the store updates "
                "the application, and App Review rejects one that updates itself"
            )
        if not metadata.category:
            raise PackagingError(
                "the Mac App Store needs LSApplicationCategoryType; set [bundle] category "
                "in Dioxus.toml or pass --category"
            )
        if uses_non_exempt_encryption is None:
            raise PackagingError(
                "the Mac App Store asks every build whether it uses non-exempt encryption. "
                "Answer once with --uses-non-exempt-encryption yes|no so App Store Connect "
                "does not hold each upload for the question. HTTPS through the system "
                "alone is exempt (no)."
            )
        plist["ITSAppUsesNonExemptEncryption"] = bool(uses_non_exempt_encryption)

    for key, value in (extra or {}).items():
        if key in plist and plist[key] != value:
            raise PackagingError(
                f"the extra Info.plist entries set {key}, which the packager already "
                f"derives ({plist[key]!r}); change it where it comes from instead"
            )
        if channel == APP_STORE and key.startswith("SU"):
            raise PackagingError(f"{key} is a Sparkle setting and the app-store build has no Sparkle")
        plist[key] = value
    return plist


@dataclass(frozen=True)
class Capabilities:
    """What a sandboxed application asks the system to let it do.

    `user_selected_files` is None, "read-only" or "read-write": access to files the person
    chose in an open or save panel or dropped on the window, and nothing else.
    """

    network_client: bool = False
    network_server: bool = False
    user_selected_files: str | None = None
    downloads_folder: str | None = None


def entitlements(channel, *, capabilities=None, team_id=None, identifier=None):
    """The entitlements dictionary for `channel`.

    Neither channel needs any hardened runtime exception. The renderer is compiled ahead of
    time by GraalVM native-image, so nothing generates code at run time (no allow-jit, no
    allow-unsigned-executable-memory), Skia draws through Metal, which needs no
    entitlement, and every library the application loads is inside the bundle and signed
    by the same team, so library validation stays on.
    """
    capabilities = capabilities or Capabilities()
    if channel == SPARKLE:
        # No sandbox: Sparkle replaces the application in place, and the sandboxed form of
        # that needs Sparkle's XPC installer services and a different signing arrangement.
        # The store build is the sandboxed one. Network and file access need no
        # entitlement outside the sandbox, so capabilities have nothing to add here.
        return {}
    if channel != APP_STORE:
        raise PackagingError(f"channel {channel!r} is not one of {', '.join(CHANNELS)}")

    result = {"com.apple.security.app-sandbox": True}
    if capabilities.network_client:
        result["com.apple.security.network.client"] = True
    if capabilities.network_server:
        result["com.apple.security.network.server"] = True
    for name, value in (
        ("files.user-selected", capabilities.user_selected_files),
        ("files.downloads", capabilities.downloads_folder),
    ):
        if value is None:
            continue
        if value not in ("read-only", "read-write"):
            raise PackagingError(f"{name} is read-only or read-write, not {value!r}")
        result[f"com.apple.security.{name}.{value}"] = True

    if team_id is not None:
        # The store matches these against the provisioning profile. Xcode writes them for
        # an Xcode project; a bundle made outside Xcode has to say them itself, or App
        # Store Connect rejects the upload for a missing application identifier.
        if identifier is None:
            raise PackagingError("the application identifier needs the bundle identifier")
        result["com.apple.application-identifier"] = f"{team_id}.{identifier}"
        result["com.apple.developer.team-identifier"] = team_id
    return result


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
