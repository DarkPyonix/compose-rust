"""Info.plist for a compose-rust application bundle.

The bundle carries no update settings: direct distribution is packaged and updated with
Velopack, which needs nothing in the Info.plist.

Nothing here asks for an entitlement. The bundle is signed ad hoc without the hardened
runtime and without a sandbox, which is what runs on a Mac without an Apple developer
account behind it, and the renderer needs no entitlement to run that way: it is compiled
ahead of time, so nothing generates code at run time, and Skia draws through Metal.
"""

import plistlib

from . import templates
from .metadata import PackagingError

# metadata-map.md's default for {{category_mac}}.
DEFAULT_CATEGORY = "public.app-category.utilities"


def info_plist(metadata, *, extra=None):
    """The Info.plist dictionary for `metadata`: the fork's Info.plist template filled from
    it, then the keys the template does not have (the copyright, `extra`)."""
    if metadata.display_name != metadata.name:
        raise PackagingError(
            f"the display name {metadata.display_name!r} is not the name {metadata.name!r}; the "
            f"Info.plist template names the bundle and its display after the one name"
        )
    try:
        text = templates.render("Info.plist.in", {
            "name": metadata.name,
            "executable": metadata.executable,
            "identifier": metadata.identifier,
            "version": metadata.version,
            "build": metadata.build,
            "category_mac": metadata.category or DEFAULT_CATEGORY,
            "min_macos": metadata.minimum_system_version,
        }, templates.xml_escape)
    except templates.TemplateError as error:
        raise PackagingError(str(error)) from error
    # Without NSHighResolutionCapable (the template has it) a bundled application is drawn
    # at 1x and scaled up, which shows as stepped edges on every rounded corner.
    plist = plistlib.loads(text.encode("utf-8"))

    added = {}
    if metadata.copyright:
        added["NSHumanReadableCopyright"] = metadata.copyright
    added.update(extra or {})
    for key, value in added.items():
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
