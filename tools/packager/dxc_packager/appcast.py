"""The Sparkle appcast: an RSS feed whose items are the versions an application can update to.

Sparkle 2 reads, per item: `sparkle:version` (compared with the installed CFBundleVersion),
`sparkle:shortVersionString` (what the person is shown), `sparkle:minimumSystemVersion`,
an optional release notes link, and one `enclosure` whose `url`, `length` and
`sparkle:edSignature` say where the archive is and how to know it is the one that was
published.

Adding a version keeps every item already in the feed, including anything this module does
not itself write, so a feed that grew by hand or by Sparkle's generate_appcast can be
extended without losing what it says.
"""

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import format_datetime

from .metadata import PackagingError

SPARKLE_NS = "http://www.andymatuschak.org/xml-namespaces/sparkle"
DC_NS = "http://purl.org/dc/elements/1.1/"
ET.register_namespace("sparkle", SPARKLE_NS)
ET.register_namespace("dc", DC_NS)


def _s(name):
    return f"{{{SPARKLE_NS}}}{name}"


@dataclass(frozen=True)
class Item:
    version: str
    short_version: str
    url: str
    length: int
    ed_signature: str
    minimum_system_version: str
    pub_date: datetime
    title: str | None = None
    release_notes_url: str | None = None
    critical: bool = False
    # "arm64" for an application that has no Intel code, so an Intel Mac is not offered an
    # update it cannot run. Sparkle's generate_appcast writes the same from the binary.
    hardware_requirements: str | None = None


def version_key(version):
    """Order versions the way Sparkle's default comparator does for numeric versions."""
    return tuple(int(part) for part in re.findall(r"\d+", version))


def _item_element(item):
    element = ET.Element("item")
    ET.SubElement(element, "title").text = item.title or f"Version {item.short_version}"
    ET.SubElement(element, "pubDate").text = format_datetime(
        item.pub_date.astimezone(timezone.utc)
    )
    ET.SubElement(element, _s("version")).text = item.version
    ET.SubElement(element, _s("shortVersionString")).text = item.short_version
    ET.SubElement(element, _s("minimumSystemVersion")).text = item.minimum_system_version
    if item.hardware_requirements:
        ET.SubElement(element, _s("hardwareRequirements")).text = item.hardware_requirements
    if item.release_notes_url:
        ET.SubElement(element, _s("releaseNotesLink")).text = item.release_notes_url
    if item.critical:
        ET.SubElement(element, _s("criticalUpdate"))
    ET.SubElement(
        element,
        "enclosure",
        {
            "url": item.url,
            "length": str(item.length),
            "type": "application/octet-stream",
            _s("edSignature"): item.ed_signature,
        },
    )
    return element


def element_version(element):
    """The version an existing item offers, wherever its writer put it."""
    version = element.findtext(_s("version"))
    if version:
        return version.strip()
    # Sparkle 1 feeds put it on the enclosure.
    enclosure = element.find("enclosure")
    if enclosure is not None and enclosure.get(_s("version")):
        return enclosure.get(_s("version")).strip()
    raise PackagingError("an existing appcast item has no sparkle:version")


def new_feed(title, link=None, description=None):
    rss = ET.Element("rss", {"version": "2.0"})
    channel = ET.SubElement(rss, "channel")
    ET.SubElement(channel, "title").text = title
    if link:
        ET.SubElement(channel, "link").text = link
    if description:
        ET.SubElement(channel, "description").text = description
    ET.SubElement(channel, "language").text = "en"
    return rss


def parse(text):
    try:
        rss = ET.fromstring(text)
    except ET.ParseError as error:
        raise PackagingError(f"the existing appcast is not XML: {error}") from error
    if rss.tag != "rss" or rss.find("channel") is None:
        raise PackagingError("the existing appcast is not an RSS feed with a channel")
    return rss


def add(rss, item):
    """Add `item` to the feed, replacing an item for the same version, newest first."""
    channel = rss.find("channel")
    items = [element for element in channel.findall("item")]
    for element in items:
        channel.remove(element)
    kept = [element for element in items if element_version(element) != item.version]
    kept.append(_item_element(item))
    kept.sort(key=lambda element: version_key(element_version(element)), reverse=True)
    for element in kept:
        channel.append(element)
    return rss


def versions(rss):
    return [element_version(element) for element in rss.find("channel").findall("item")]


def dumps(rss):
    ET.indent(rss, space="    ")
    body = ET.tostring(rss, encoding="unicode")
    return '<?xml version="1.0" encoding="utf-8"?>\n' + body + "\n"


def now():
    return datetime.now(timezone.utc).replace(microsecond=0)
