"""Reading what an application says about itself from Cargo.toml and Dioxus.toml."""

import unittest

from support import CARGO, DIOXUS, REPOSITORY, project

from dxc_packager import metadata
from dxc_packager.metadata import PackagingError


class Load(unittest.TestCase):
    def test_fr34_1_metadata_comes_from_the_project_files(self):
        meta = metadata.load(project(CARGO, DIOXUS))
        self.assertEqual(meta.identifier, "dev.example.demo")
        self.assertEqual(meta.name, "Demo App")
        self.assertEqual(meta.executable, "sample-demo")
        self.assertEqual(meta.version, "1.4.2")
        self.assertEqual(meta.build, "1.4.2")
        self.assertEqual(meta.category, "public.app-category.developer-tools")
        self.assertEqual(meta.copyright, "Copyright Example")
        self.assertEqual(meta.minimum_system_version, "13.0")

    def test_fr34_1_every_sample_in_the_repository_loads(self):
        samples = sorted(path for path in (REPOSITORY / "samples").iterdir()
                         if (path / "Dioxus.toml").exists())
        self.assertTrue(samples)
        for sample in samples:
            meta = metadata.load(sample)
            self.assertTrue(meta.identifier.startswith("dev.darkpyonix."), sample)
            self.assertTrue(meta.executable.startswith("sample-"), sample)

    def test_fr34_1_bundle_macos_table_is_read_as_well_as_the_samples_macos_table(self):
        dioxus = DIOXUS.replace("[macos]\nbundle_name = \"Demo App\"", "") + (
            "\n[bundle.macos]\nbundle_name = \"Other\"\nminimum_system_version = \"14.0\"\n"
        )
        meta = metadata.load(project(CARGO, dioxus))
        self.assertEqual(meta.name, "Other")
        self.assertEqual(meta.minimum_system_version, "14.0")

    def test_fr34_1_overrides_win_and_build_follows_version(self):
        meta = metadata.load(project(CARGO, DIOXUS), version="2.0.0")
        self.assertEqual((meta.version, meta.build), ("2.0.0", "2.0.0"))
        meta = metadata.load(project(CARGO, DIOXUS), version="2.0.0", build="57")
        self.assertEqual(meta.build, "57")

    def test_fr34_1_icon_falls_back_to_the_asset_directory(self):
        directory = project(CARGO, DIOXUS, files=[("assets/icon.png", b"png")])
        self.assertEqual(metadata.load(directory).icon, directory / "assets" / "icon.png")

    def test_fr34_1_missing_identifier_is_an_error_not_a_guess(self):
        with self.assertRaisesRegex(PackagingError, "identifier"):
            metadata.load(project(CARGO, "[application]\nname = \"X\"\n"))

    def test_fr34_1_prerelease_version_needs_explicit_numbers(self):
        cargo = CARGO.replace("1.4.2", "1.4.2-beta.1")
        with self.assertRaisesRegex(PackagingError, "one to three dot separated integers"):
            metadata.load(project(cargo, DIOXUS))
        meta = metadata.load(project(cargo, DIOXUS), version="1.4.2", build="142")
        self.assertEqual(meta.version, "1.4.2")

    def test_fr34_1_workspace_version_has_to_be_given(self):
        cargo = CARGO.replace('version = "1.4.2"', "version.workspace = true")
        with self.assertRaisesRegex(PackagingError, "--version"):
            metadata.load(project(cargo, DIOXUS))

    def test_fr34_1_categories_accept_both_spellings_and_refuse_others(self):
        self.assertEqual(metadata.category_type("Utility"), "public.app-category.utilities")
        self.assertEqual(
            metadata.category_type("public.app-category.music"), "public.app-category.music"
        )
        with self.assertRaisesRegex(PackagingError, "DeveloperTool"):
            metadata.category_type("Tools")


if __name__ == "__main__":
    unittest.main()
