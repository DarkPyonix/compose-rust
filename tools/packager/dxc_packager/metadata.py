"""What an application says about itself, read from the files its project already has.

An application built with dioxus-compose is an ordinary dx project, so its name, bundle
identifier and description are in `Dioxus.toml` and its version is in `Cargo.toml`. The
packager reads them there rather than asking for them again, so the bundle cannot drift
from the project it was built from.

`Dioxus.toml` keys read (all optional except the identifier):

    [application] name
    [bundle]      identifier, publisher, copyright, category, short_description, icon
    [bundle.macos] or [macos]
                  bundle_name, minimum_system_version
"""

import re
import sys
from dataclasses import dataclass, replace
from pathlib import Path

if sys.version_info < (3, 11):
    raise SystemExit("the packager reads TOML with tomllib, which needs Python 3.11 or newer")

import tomllib


class PackagingError(Exception):
    """Something the person packaging has to fix, worded so they can."""


# dx (through tauri-bundler) names categories in CamelCase; macOS wants the uniform type
# identifier. Either spelling is accepted in Dioxus.toml.
CATEGORIES = {
    "Business": "public.app-category.business",
    "DeveloperTool": "public.app-category.developer-tools",
    "Education": "public.app-category.education",
    "Entertainment": "public.app-category.entertainment",
    "Finance": "public.app-category.finance",
    "Game": "public.app-category.games",
    "GraphicsAndDesign": "public.app-category.graphics-design",
    "HealthcareAndFitness": "public.app-category.healthcare-fitness",
    "Lifestyle": "public.app-category.lifestyle",
    "Medical": "public.app-category.medical",
    "Music": "public.app-category.music",
    "News": "public.app-category.news",
    "Photography": "public.app-category.photography",
    "Productivity": "public.app-category.productivity",
    "Reference": "public.app-category.reference",
    "SocialNetworking": "public.app-category.social-networking",
    "Sports": "public.app-category.sports",
    "Travel": "public.app-category.travel",
    "Utility": "public.app-category.utilities",
    "Video": "public.app-category.video",
    "Weather": "public.app-category.weather",
}

# The App Store and Sparkle both compare CFBundleVersion as dot separated integers, and App
# Store Connect refuses anything else, so a version is checked here rather than at upload.
_BUNDLE_VERSION = re.compile(r"^\d+(\.\d+){0,2}$")
_IDENTIFIER = re.compile(r"^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$")

# Compose's AWT and Metal paths and the renderer are built and tested on macOS 13 and
# newer, so nothing older is promised.
DEFAULT_MINIMUM_SYSTEM_VERSION = "13.0"


@dataclass(frozen=True)
class AppMetadata:
    name: str
    display_name: str
    identifier: str
    executable: str
    version: str
    build: str
    minimum_system_version: str = DEFAULT_MINIMUM_SYSTEM_VERSION
    copyright: str | None = None
    category: str | None = None
    description: str | None = None
    icon: Path | None = None


def category_type(value):
    if value is None:
        return None
    if value.startswith("public.app-category."):
        return value
    if value in CATEGORIES:
        return CATEGORIES[value]
    known = ", ".join(sorted(CATEGORIES))
    raise PackagingError(
        f"category {value!r} is neither a public.app-category.* identifier nor one of the "
        f"names dx uses ({known})"
    )


def check_bundle_version(field, value):
    if not _BUNDLE_VERSION.match(value):
        raise PackagingError(
            f"{field} is {value!r}, and macOS wants one to three dot separated integers "
            f"(1, 1.2 or 1.2.3). A prerelease version from Cargo.toml needs --version and "
            f"--build given explicitly."
        )
    return value


def _read_toml(path):
    try:
        with open(path, "rb") as handle:
            return tomllib.load(handle)
    except FileNotFoundError:
        return None
    except tomllib.TOMLDecodeError as error:
        raise PackagingError(f"{path} is not valid TOML: {error}") from error


def load(manifest_dir, *, executable=None, version=None, build=None):
    """Metadata for the project at `manifest_dir`, with explicit overrides applied last."""
    manifest_dir = Path(manifest_dir)
    cargo = _read_toml(manifest_dir / "Cargo.toml")
    if cargo is None:
        raise PackagingError(f"no Cargo.toml in {manifest_dir}")
    dioxus = _read_toml(manifest_dir / "Dioxus.toml") or {}
    package = cargo.get("package", {})

    cargo_version = package.get("version")
    if isinstance(cargo_version, dict):
        # `version.workspace = true`: the number is in a workspace this tool does not walk.
        cargo_version = None
    version = version or cargo_version
    if not version:
        raise PackagingError(
            f"{manifest_dir}/Cargo.toml does not state a version here (it may inherit one "
            f"from the workspace); pass --version"
        )

    application = dioxus.get("application", {})
    bundle = dioxus.get("bundle", {})
    macos = {**dioxus.get("macos", {}), **bundle.get("macos", {})}

    identifier = bundle.get("identifier")
    if not identifier:
        raise PackagingError(
            f"{manifest_dir}/Dioxus.toml has no [bundle] identifier. The bundle identifier "
            f"is what macOS, Sparkle and App Store Connect know the application by, so it "
            f"has to be chosen rather than made up here."
        )
    if not _IDENTIFIER.match(identifier):
        raise PackagingError(
            f"bundle identifier {identifier!r} must be reverse DNS made of letters, digits, "
            f"hyphens and dots"
        )

    name = macos.get("bundle_name") or application.get("name") or package.get("name")
    executable = executable or package.get("name")

    icon = None
    icons = bundle.get("icon")
    if isinstance(icons, str):
        icons = [icons]
    for candidate in icons or []:
        path = manifest_dir / candidate
        if path.suffix.lower() in (".png", ".icns") and path.exists():
            icon = path
            break
    if icon is None:
        fallback = manifest_dir / application.get("asset_dir", "assets") / "icon.png"
        if fallback.exists():
            icon = fallback

    copyright_text = bundle.get("copyright")
    if copyright_text is None and bundle.get("publisher"):
        copyright_text = f"Copyright {bundle['publisher']}"

    metadata = AppMetadata(
        name=name,
        display_name=name,
        identifier=identifier,
        executable=executable,
        version=version,
        build=build or version,
        minimum_system_version=macos.get(
            "minimum_system_version", DEFAULT_MINIMUM_SYSTEM_VERSION
        ),
        copyright=copyright_text,
        category=category_type(bundle.get("category")),
        description=bundle.get("short_description"),
        icon=icon,
    )
    check_bundle_version("CFBundleShortVersionString", metadata.version)
    check_bundle_version("CFBundleVersion", metadata.build)
    return metadata


def with_overrides(metadata, **changes):
    changes = {key: value for key, value in changes.items() if value is not None}
    if "category" in changes:
        changes["category"] = category_type(changes["category"])
    updated = replace(metadata, **changes)
    check_bundle_version("CFBundleShortVersionString", updated.version)
    check_bundle_version("CFBundleVersion", updated.build)
    return updated
