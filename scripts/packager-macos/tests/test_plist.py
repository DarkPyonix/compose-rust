"""Info.plist for an application bundle."""

import unittest

from support import CARGO, DIOXUS, project

from dxc_packager import metadata, plist
from dxc_packager.metadata import PackagingError


def meta(**changes):
    return metadata.with_overrides(metadata.load(project(CARGO, DIOXUS)), **changes)


class InfoPlist(unittest.TestCase):
    def test_fr22_6_3_common_keys_come_from_the_metadata(self):
        info = plist.info_plist(meta())
        self.assertEqual(info["CFBundleIdentifier"], "dev.example.demo")
        self.assertEqual(info["CFBundleExecutable"], "sample-demo")
        self.assertEqual(info["CFBundleName"], "Demo App")
        self.assertEqual(info["CFBundleShortVersionString"], "1.4.2")
        self.assertEqual(info["CFBundleVersion"], "1.4.2")
        self.assertEqual(info["CFBundlePackageType"], "APPL")
        self.assertEqual(info["LSMinimumSystemVersion"], "13.0")
        self.assertEqual(info["LSApplicationCategoryType"], "public.app-category.developer-tools")
        self.assertIs(info["NSHighResolutionCapable"], True)

    def test_fr22_6_3_a_bundle_has_no_update_settings(self):
        info = plist.info_plist(meta())
        self.assertFalse([key for key in info if key.startswith("SU")])

    def test_fr22_6_3_extra_entries_are_added_but_cannot_contradict_derived_ones(self):
        info = plist.info_plist(
            meta(),
            extra={"NSAppTransportSecurity": {"NSAllowsLocalNetworking": True}},
        )
        self.assertEqual(info["NSAppTransportSecurity"], {"NSAllowsLocalNetworking": True})
        with self.assertRaisesRegex(PackagingError, "CFBundleIdentifier"):
            plist.info_plist(meta(), extra={"CFBundleIdentifier": "other.id"})

    def test_fr22_6_3_command_line_entries_are_typed(self):
        self.assertEqual(
            plist.parse_extra(["A=true", "B=12", "C=text"]), {"A": True, "B": 12, "C": "text"}
        )
        with self.assertRaisesRegex(PackagingError, "KEY=VALUE"):
            plist.parse_extra(["nothing"])

    def test_fr22_6_3_plist_serialises_as_xml(self):
        data = plist.dumps(plist.info_plist(meta()))
        self.assertTrue(data.startswith(b"<?xml"))
        self.assertIn(b"<key>CFBundleIdentifier</key>", data)


if __name__ == "__main__":
    unittest.main()
