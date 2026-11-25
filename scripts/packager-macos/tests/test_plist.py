"""Info.plist for an application bundle."""

import dataclasses
import plistlib
import unittest

from support import CARGO, DIOXUS, project

from dxc_packager import metadata, plist, templates
from test_bundle import ICON
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

    def test_fr22_6_11_the_plist_is_the_forks_template_filled_then_added_to(self):
        # Every key the template has is there with the template's value, filled from the
        # metadata; the packager only adds keys the template does not have.
        icon_meta = meta(icon=ICON)
        template = plistlib.loads(templates.render("Info.plist.in", {
            "name": "Demo App", "executable": "sample-demo", "identifier": "dev.example.demo",
            "version": "1.4.2", "build": "1.4.2",
            "category_mac": "public.app-category.developer-tools", "min_macos": "13.0",
        }, templates.xml_escape).encode())
        info = plist.info_plist(icon_meta, extra={"NSAppTransportSecurity": {"NSAllowsLocalNetworking": True}})
        for key, value in template.items():
            self.assertEqual(info.get(key), value, key)
        self.assertEqual(set(info) - set(template), {"NSHumanReadableCopyright", "NSAppTransportSecurity"})

    def test_fr22_6_11_a_bundle_without_a_category_gets_the_templates_default(self):
        info = plist.info_plist(dataclasses.replace(meta(), category=None))
        self.assertEqual(info["LSApplicationCategoryType"], "public.app-category.utilities")

    def test_fr22_6_11_text_is_escaped_into_the_template(self):
        info = plist.info_plist(meta(name="Tom & <Jerry>", display_name="Tom & <Jerry>"))
        self.assertEqual(info["CFBundleName"], "Tom & <Jerry>")
        self.assertEqual(info["CFBundleDisplayName"], "Tom & <Jerry>")

    def test_fr22_6_11_a_display_name_other_than_the_name_is_refused(self):
        # The template names the bundle and its display after {{name}}.
        with self.assertRaisesRegex(PackagingError, "display name"):
            plist.info_plist(meta(display_name="Other"))

    def test_fr22_6_11_an_unfilled_placeholder_is_an_error(self):
        with self.assertRaisesRegex(templates.TemplateError, "build"):
            templates.render("Info.plist.in", {"name": "x"}, templates.xml_escape)

    def test_fr22_6_3_plist_serialises_as_xml(self):
        data = plist.dumps(plist.info_plist(meta()))
        self.assertTrue(data.startswith(b"<?xml"))
        self.assertIn(b"<key>CFBundleIdentifier</key>", data)


if __name__ == "__main__":
    unittest.main()
