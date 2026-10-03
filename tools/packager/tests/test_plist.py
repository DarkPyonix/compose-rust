"""Info.plist for bundles that update themselves with Sparkle and bundles that do not."""

import unittest

from support import CARGO, DIOXUS, project

from dxc_packager import metadata, plist
from dxc_packager.metadata import PackagingError

PUBLIC_KEY = "2kja3FFzveTqSefH+8D0npKfvngmu2zAMqubhIgBuh8="


def meta(**changes):
    return metadata.with_overrides(metadata.load(project(CARGO, DIOXUS)), **changes)


def sparkle(**changes):
    settings = {"feed_url": "https://example.com/appcast.xml", "public_ed_key": PUBLIC_KEY}
    settings.update(changes)
    return plist.SparkleSettings(**settings)


class InfoPlist(unittest.TestCase):
    def test_fr35_1_common_keys_come_from_the_metadata(self):
        info = plist.info_plist(meta(), sparkle=sparkle())
        self.assertEqual(info["CFBundleIdentifier"], "dev.example.demo")
        self.assertEqual(info["CFBundleExecutable"], "sample-demo")
        self.assertEqual(info["CFBundleName"], "Demo App")
        self.assertEqual(info["CFBundleShortVersionString"], "1.4.2")
        self.assertEqual(info["CFBundleVersion"], "1.4.2")
        self.assertEqual(info["CFBundlePackageType"], "APPL")
        self.assertEqual(info["LSMinimumSystemVersion"], "13.0")
        self.assertEqual(info["LSApplicationCategoryType"], "public.app-category.developer-tools")
        self.assertIs(info["NSHighResolutionCapable"], True)

    def test_fr35_1_a_bundle_without_updates_has_no_sparkle_keys(self):
        info = plist.info_plist(meta())
        self.assertFalse([key for key in info if key.startswith("SU")])
        with self.assertRaisesRegex(PackagingError, "--updater sparkle"):
            plist.info_plist(meta(), extra={"SUFeedURL": "https://example.com/appcast.xml"})

    def test_fr35_2_sparkle_bundle_carries_feed_key_and_schedule(self):
        info = plist.info_plist(
            meta(), sparkle=sparkle(check_interval=7200, automatically_update=True)
        )
        self.assertEqual(info["SUFeedURL"], "https://example.com/appcast.xml")
        self.assertEqual(info["SUPublicEDKey"], PUBLIC_KEY)
        self.assertIs(info["SUEnableAutomaticChecks"], True)
        self.assertEqual(info["SUScheduledCheckInterval"], 7200)
        self.assertIs(info["SUAutomaticallyUpdate"], True)

    def test_fr35_2_feed_must_be_https_except_on_this_machine(self):
        for url in ("http://example.com/appcast.xml", "ftp://example.com/a", "appcast.xml"):
            with self.assertRaisesRegex(PackagingError, "https"):
                plist.info_plist(meta(), sparkle=sparkle(feed_url=url))
        for url in (
            "http://127.0.0.1:8000/appcast.xml",
            "http://localhost/appcast.xml",
            "file:///Users/someone/site/appcast.xml",
        ):
            info = plist.info_plist(meta(), sparkle=sparkle(feed_url=url))
            self.assertEqual(info["SUFeedURL"], url)

    def test_fr35_2_public_key_that_is_not_an_ed25519_key_is_refused(self):
        with self.assertRaisesRegex(ValueError, "32"):
            plist.info_plist(meta(), sparkle=sparkle(public_ed_key="AAAA"))

    def test_fr35_2_check_interval_below_sparkles_floor_is_refused(self):
        with self.assertRaisesRegex(PackagingError, "3600"):
            plist.info_plist(meta(), sparkle=sparkle(check_interval=60))

    def test_fr35_1_extra_entries_are_added_but_cannot_contradict_derived_ones(self):
        info = plist.info_plist(
            meta(), sparkle=sparkle(),
            extra={"NSAppTransportSecurity": {"NSAllowsLocalNetworking": True}},
        )
        self.assertEqual(info["NSAppTransportSecurity"], {"NSAllowsLocalNetworking": True})
        with self.assertRaisesRegex(PackagingError, "CFBundleIdentifier"):
            plist.info_plist(meta(), sparkle=sparkle(), extra={"CFBundleIdentifier": "other.id"})

    def test_fr35_1_command_line_entries_are_typed(self):
        self.assertEqual(
            plist.parse_extra(["A=true", "B=12", "C=text"]), {"A": True, "B": 12, "C": "text"}
        )
        with self.assertRaisesRegex(PackagingError, "KEY=VALUE"):
            plist.parse_extra(["nothing"])

    def test_fr35_1_plist_serialises_as_xml(self):
        data = plist.dumps(plist.info_plist(meta(), sparkle=sparkle()))
        self.assertTrue(data.startswith(b"<?xml"))
        self.assertIn(b"<key>SUPublicEDKey</key>", data)


if __name__ == "__main__":
    unittest.main()
