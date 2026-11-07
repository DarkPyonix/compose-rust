"""Info.plist for a compose-rust application bundle.

The bundle carries no update settings: direct distribution is packaged and updated with
Velopack, which needs nothing in the Info.plist.

Nothing here asks for an entitlement. The bundle is signed ad hoc without the hardened
runtime and without a sandbox, which is what runs on a Mac without an Apple developer
account behind it, and the renderer needs no entitlement to run that way: it is compiled
ahead of time, so nothing generates code at run time, and Skia draws through Metal.
"""

import plistlib

from .metadata import PackagingError


def info_plist(metadata, *, extra=None):
    """The Info.plist dictionary for `metadata`."""
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

    for key, value in (extra or {}).items():
        if key in plist and plist[key] != value:
            raise PackagingError(
                f"the extra Info.plist entries set {key}, which the packager already "
                f"derives ({plist[key]!r}); change it where it comes from instead"
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
