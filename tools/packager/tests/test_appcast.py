"""Writing and extending the Sparkle appcast."""

import unittest
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

from support import SPARKLE_DIR  # noqa: F401  (puts the packager on the path)

from dxc_packager import appcast
from dxc_packager.metadata import PackagingError

S = "{http://www.andymatuschak.org/xml-namespaces/sparkle}"


def item(version, **changes):
    fields = dict(
        version=version,
        short_version=f"{version}.0",
        url=f"https://example.com/Demo-{version}.zip",
        length=1234,
        ed_signature="c2lnbmF0dXJl",
        minimum_system_version="13.0",
        pub_date=datetime(2026, 10, 3, 12, 0, 0, tzinfo=timezone.utc),
    )
    fields.update(changes)
    return appcast.Item(**fields)


def feed_with(*items):
    rss = appcast.new_feed("Demo")
    for entry in items:
        appcast.add(rss, entry)
    return ET.fromstring(appcast.dumps(rss))


class Appcast(unittest.TestCase):
    def test_fr34_9_item_carries_what_sparkle_2_reads(self):
        rss = feed_with(item("2", release_notes_url="https://example.com/2.html"))
        element = rss.find("channel/item")
        self.assertEqual(element.findtext(f"{S}version"), "2")
        self.assertEqual(element.findtext(f"{S}shortVersionString"), "2.0")
        self.assertEqual(element.findtext(f"{S}minimumSystemVersion"), "13.0")
        self.assertEqual(element.findtext(f"{S}releaseNotesLink"), "https://example.com/2.html")
        self.assertEqual(element.findtext("pubDate"), "Sat, 03 Oct 2026 12:00:00 +0000")
        enclosure = element.find("enclosure")
        self.assertEqual(enclosure.get("url"), "https://example.com/Demo-2.zip")
        self.assertEqual(enclosure.get("length"), "1234")
        self.assertEqual(enclosure.get(f"{S}edSignature"), "c2lnbmF0dXJl")
        self.assertIsNone(element.find(f"{S}criticalUpdate"))

    def test_fr34_9_output_is_a_feed_declaring_the_sparkle_namespace(self):
        text = appcast.dumps(appcast.add(appcast.new_feed("Demo"), item("1")))
        self.assertTrue(text.startswith('<?xml version="1.0" encoding="utf-8"?>'))
        self.assertIn('xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle"', text)
        self.assertEqual(ET.fromstring(text).get("version"), "2.0")

    def test_fr34_9_newest_first_comparing_numbers_not_text(self):
        rss = feed_with(item("9"), item("10"), item("1.2"))
        self.assertEqual(appcast.versions(rss), ["10", "9", "1.2"])

    def test_fr34_9_publishing_a_version_again_replaces_it(self):
        rss = feed_with(item("1"), item("2"), item("2", length=99))
        self.assertEqual(appcast.versions(rss), ["2", "1"])
        self.assertEqual(rss.find("channel/item/enclosure").get("length"), "99")

    def test_fr34_9_existing_items_and_their_unknown_elements_survive(self):
        existing = """<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">
  <channel>
    <title>Demo</title>
    <item>
      <title>Version 1.0</title>
      <sparkle:version>1</sparkle:version>
      <sparkle:channel>beta</sparkle:channel>
      <enclosure url="https://example.com/1.zip" length="1" type="application/octet-stream"
                 sparkle:edSignature="b2xk"/>
    </item>
    <item>
      <title>Sparkle 1 style</title>
      <enclosure url="https://example.com/0.zip" sparkle:version="0.9" length="1"
                 type="application/octet-stream"/>
    </item>
  </channel>
</rss>"""
        rss = appcast.parse(existing)
        appcast.add(rss, item("2"))
        rss = ET.fromstring(appcast.dumps(rss))
        self.assertEqual(appcast.versions(rss), ["2", "1", "0.9"])
        old = rss.findall("channel/item")[1]
        self.assertEqual(old.findtext(f"{S}channel"), "beta")
        self.assertEqual(rss.findtext("channel/title"), "Demo")

    def test_fr34_9_an_apple_silicon_only_update_says_so(self):
        rss = feed_with(item("4", hardware_requirements="arm64"))
        self.assertEqual(rss.findtext(f"channel/item/{S}hardwareRequirements"), "arm64")
        self.assertIsNone(feed_with(item("4")).find(f"channel/item/{S}hardwareRequirements"))

    def test_fr34_9_critical_updates_are_marked(self):
        rss = feed_with(item("3", critical=True))
        self.assertIsNotNone(rss.find(f"channel/item/{S}criticalUpdate"))

    def test_fr34_9_something_that_is_not_a_feed_is_refused(self):
        with self.assertRaisesRegex(PackagingError, "not XML"):
            appcast.parse("<rss>")
        with self.assertRaisesRegex(PackagingError, "RSS"):
            appcast.parse("<html/>")
        with self.assertRaisesRegex(PackagingError, "sparkle:version"):
            appcast.add(appcast.parse("<rss><channel><item/></channel></rss>"), item("1"))


if __name__ == "__main__":
    unittest.main()
