"""Info.plist and entitlements for the sparkle and app-store channels."""

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
    def test_fr34_1_common_keys_come_from_the_metadata(self):
        info = plist.info_plist(meta(), plist.SPARKLE, sparkle=sparkle())
        self.assertEqual(info["CFBundleIdentifier"], "dev.example.demo")
        self.assertEqual(info["CFBundleExecutable"], "sample-demo")
        self.assertEqual(info["CFBundleName"], "Demo App")
        self.assertEqual(info["CFBundleShortVersionString"], "1.4.2")
        self.assertEqual(info["CFBundleVersion"], "1.4.2")
        self.assertEqual(info["CFBundlePackageType"], "APPL")
        self.assertEqual(info["LSMinimumSystemVersion"], "13.0")
        self.assertEqual(info["LSApplicationCategoryType"], "public.app-category.developer-tools")
        self.assertIs(info["NSHighResolutionCapable"], True)

    def test_fr34_2_sparkle_build_carries_feed_key_and_schedule(self):
        info = plist.info_plist(
            meta(), plist.SPARKLE, sparkle=sparkle(check_interval=7200, automatically_update=True)
        )
        self.assertEqual(info["SUFeedURL"], "https://example.com/appcast.xml")
        self.assertEqual(info["SUPublicEDKey"], PUBLIC_KEY)
        self.assertIs(info["SUEnableAutomaticChecks"], True)
        self.assertEqual(info["SUScheduledCheckInterval"], 7200)
        self.assertIs(info["SUAutomaticallyUpdate"], True)
        self.assertNotIn("ITSAppUsesNonExemptEncryption", info)

    def test_fr34_2_sparkle_build_needs_its_settings(self):
        with self.assertRaisesRegex(PackagingError, "--feed-url"):
            plist.info_plist(meta(), plist.SPARKLE)

    def test_fr34_2_feed_must_be_https_except_on_loopback(self):
        for url in ("http://example.com/appcast.xml", "ftp://example.com/a", "appcast.xml"):
            with self.assertRaisesRegex(PackagingError, "https"):
                plist.info_plist(meta(), plist.SPARKLE, sparkle=sparkle(feed_url=url))
        for url in ("http://127.0.0.1:8000/appcast.xml", "http://localhost/appcast.xml"):
            info = plist.info_plist(meta(), plist.SPARKLE, sparkle=sparkle(feed_url=url))
            self.assertEqual(info["SUFeedURL"], url)

    def test_fr34_2_public_key_that_is_not_an_ed25519_key_is_refused(self):
        with self.assertRaisesRegex(ValueError, "32"):
            plist.info_plist(meta(), plist.SPARKLE, sparkle=sparkle(public_ed_key="AAAA"))

    def test_fr34_2_check_interval_below_sparkles_floor_is_refused(self):
        with self.assertRaisesRegex(PackagingError, "3600"):
            plist.info_plist(meta(), plist.SPARKLE, sparkle=sparkle(check_interval=60))

    def test_fr34_3_store_build_has_no_sparkle_keys_and_answers_export_compliance(self):
        info = plist.info_plist(meta(), plist.APP_STORE, uses_non_exempt_encryption=False)
        self.assertFalse([key for key in info if key.startswith("SU")])
        self.assertIs(info["ITSAppUsesNonExemptEncryption"], False)

    def test_fr34_3_store_build_refuses_sparkle_settings(self):
        with self.assertRaisesRegex(PackagingError, "updates itself"):
            plist.info_plist(
                meta(), plist.APP_STORE, sparkle=sparkle(), uses_non_exempt_encryption=False
            )
        with self.assertRaisesRegex(PackagingError, "Sparkle setting"):
            plist.info_plist(
                meta(), plist.APP_STORE, uses_non_exempt_encryption=False,
                extra={"SUFeedURL": "https://example.com"},
            )

    def test_fr34_3_store_build_needs_category_and_encryption_answer(self):
        directory = project(CARGO, DIOXUS.replace('category = "DeveloperTool"\n', ""))
        with self.assertRaisesRegex(PackagingError, "LSApplicationCategoryType"):
            plist.info_plist(
                metadata.load(directory), plist.APP_STORE, uses_non_exempt_encryption=False
            )
        with self.assertRaisesRegex(PackagingError, "non-exempt encryption"):
            plist.info_plist(meta(), plist.APP_STORE)

    def test_fr34_1_extra_entries_are_added_but_cannot_contradict_derived_ones(self):
        info = plist.info_plist(
            meta(), plist.SPARKLE, sparkle=sparkle(),
            extra={"NSAppTransportSecurity": {"NSAllowsLocalNetworking": True}},
        )
        self.assertEqual(info["NSAppTransportSecurity"], {"NSAllowsLocalNetworking": True})
        with self.assertRaisesRegex(PackagingError, "CFBundleIdentifier"):
            plist.info_plist(
                meta(), plist.SPARKLE, sparkle=sparkle(),
                extra={"CFBundleIdentifier": "other.id"},
            )

    def test_fr34_1_command_line_entries_are_typed(self):
        self.assertEqual(
            plist.parse_extra(["A=true", "B=12", "C=text"]), {"A": True, "B": 12, "C": "text"}
        )
        with self.assertRaisesRegex(PackagingError, "KEY=VALUE"):
            plist.parse_extra(["nothing"])

    def test_fr34_1_plist_serialises_as_xml(self):
        data = plist.dumps(plist.info_plist(meta(), plist.SPARKLE, sparkle=sparkle()))
        self.assertTrue(data.startswith(b"<?xml"))
        self.assertIn(b"<key>SUPublicEDKey</key>", data)


class Entitlements(unittest.TestCase):
    def test_fr34_4_sparkle_build_asks_for_nothing(self):
        self.assertEqual(plist.entitlements(plist.SPARKLE), {})
        self.assertEqual(
            plist.entitlements(
                plist.SPARKLE, capabilities=plist.Capabilities(network_client=True)
            ),
            {},
        )

    def test_fr34_4_store_build_is_sandboxed_with_only_what_was_declared(self):
        self.assertEqual(
            plist.entitlements(plist.APP_STORE), {"com.apple.security.app-sandbox": True}
        )
        granted = plist.entitlements(
            plist.APP_STORE,
            capabilities=plist.Capabilities(
                network_client=True, user_selected_files="read-write"
            ),
        )
        self.assertEqual(
            granted,
            {
                "com.apple.security.app-sandbox": True,
                "com.apple.security.network.client": True,
                "com.apple.security.files.user-selected.read-write": True,
            },
        )

    def test_fr34_4_no_channel_asks_for_a_hardened_runtime_exception(self):
        everything = plist.Capabilities(
            network_client=True, network_server=True, user_selected_files="read-only",
            downloads_folder="read-write",
        )
        for channel in plist.CHANNELS:
            granted = plist.entitlements(
                channel, capabilities=everything, team_id="ABCDE12345",
                identifier="dev.example.demo",
            )
            self.assertFalse([key for key in granted if key.startswith("com.apple.security.cs.")])

    def test_fr34_4_team_id_names_the_application_for_the_store(self):
        granted = plist.entitlements(
            plist.APP_STORE, team_id="ABCDE12345", identifier="dev.example.demo"
        )
        self.assertEqual(
            granted["com.apple.application-identifier"], "ABCDE12345.dev.example.demo"
        )
        self.assertEqual(granted["com.apple.developer.team-identifier"], "ABCDE12345")

    def test_fr34_4_file_access_is_read_only_or_read_write(self):
        with self.assertRaisesRegex(PackagingError, "read-only or read-write"):
            plist.entitlements(
                plist.APP_STORE, capabilities=plist.Capabilities(user_selected_files="all")
            )


if __name__ == "__main__":
    unittest.main()
