"""Assembling, signing and wrapping real bundles. macOS only: this is codesign and productbuild.

The executable is a copy of /usr/bin/true, which is a real Mach-O program that needs
nothing beside it, so these run in seconds and need no Rust build. What a dioxus-compose
executable adds, a renderer loaded from an absolute path, is made by pointing the copy's
load command at a stand-in library.
"""

import plistlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from support import CARGO, DIOXUS, REPOSITORY, SPARKLE_DIR, project

from dxc_packager import bundle, cli, metadata, plist

PUBLIC_KEY = "2kja3FFzveTqSefH+8D0npKfvngmu2zAMqubhIgBuh8="
ICON = REPOSITORY / "samples" / "calculator" / "assets" / "icon.png"


def executable_in(directory):
    path = Path(directory) / "sample-demo"
    shutil.copyfile("/usr/bin/true", path)
    path.chmod(0o755)
    return path


def compile_c(source, out, *flags):
    subprocess.run(
        # The flags come before `-x c`, which would otherwise read a library as C source.
        ["cc", "-o", str(out), "-Wl,-headerpad_max_install_names", *flags, "-x", "c", "-"],
        input=source + "\n", text=True, check=True,
    )


def store_app(out, executable):
    meta = metadata.with_overrides(metadata.load(project(CARGO, DIOXUS)), icon=ICON)
    app = bundle.assemble(meta, executable, out, plist.APP_STORE, uses_non_exempt_encryption=False)
    granted = plist.entitlements(
        plist.APP_STORE, capabilities=plist.Capabilities(network_client=True)
    )
    return bundle.sign(app, "-", plist.APP_STORE, granted)


@unittest.skipUnless(sys.platform == "darwin", "codesign and productbuild are macOS tools")
class StoreBundle(unittest.TestCase):
    def setUp(self):
        self.scratch = Path(tempfile.mkdtemp(prefix="packager-bundle-"))
        self.app = store_app(self.scratch / "out", executable_in(self.scratch))

    def test_fr34_1_layout_and_info_plist(self):
        contents = self.app / "Contents"
        self.assertEqual(self.app.name, "Demo App.app")
        self.assertTrue((contents / "MacOS" / "sample-demo").is_file())
        self.assertTrue((contents / "Resources" / "AppIcon.icns").is_file())
        self.assertEqual((contents / "PkgInfo").read_text(), "APPL????")
        info = plistlib.loads((contents / "Info.plist").read_bytes())
        self.assertEqual(info["CFBundleIdentifier"], "dev.example.demo")
        self.assertEqual(info["CFBundleIconFile"], "AppIcon")
        self.assertIs(info["NSHighResolutionCapable"], True)

    def test_fr34_5_signature_verifies_with_the_hardened_runtime(self):
        subprocess.run(
            ["codesign", "--verify", "--strict", "--deep", str(self.app)], check=True
        )
        details = subprocess.run(
            ["codesign", "-dv", str(self.app)], capture_output=True, text=True
        ).stderr
        self.assertIn("runtime", details)
        self.assertIn("Identifier=dev.example.demo", details)

    def test_fr34_4_store_bundle_is_sandboxed_with_declared_capabilities(self):
        self.assertEqual(
            bundle.entitlements_of(self.app),
            {
                "com.apple.security.app-sandbox": True,
                "com.apple.security.network.client": True,
            },
        )
        self.assertEqual(cli._verify(self.app, plist.APP_STORE), [])

    def test_fr34_6_store_bundle_has_no_sparkle_and_a_host_naming_it_is_caught(self):
        self.assertEqual(bundle.sparkle_residue(self.app), [])
        executable = self.app / "Contents" / "MacOS" / "sample-demo"
        with open(executable, "ab") as handle:
            handle.write(b"\0SPUStandardUpdaterController\0")
        problems = bundle.sparkle_residue(self.app)
        self.assertEqual(len(problems), 1)
        self.assertIn("without the updater", problems[0])

    def test_fr34_7_pkg_installs_the_app_into_applications(self):
        package = bundle.pkg(self.app, self.scratch / "Demo.pkg")
        expanded = self.scratch / "expanded"
        subprocess.run(["pkgutil", "--expand-full", str(package), str(expanded)], check=True)
        distribution = (expanded / "Distribution").read_text()
        self.assertIn("dev.example.demo", distribution)
        payloads = list(expanded.glob("*.pkg/Payload/Demo App.app/Contents/Info.plist"))
        self.assertEqual(len(payloads), 1)
        package_info = next(expanded.glob("*.pkg/PackageInfo")).read_text()
        self.assertIn('install-location="/Applications"', package_info)

    def test_fr34_7_pkg_refuses_a_bundle_that_is_not_for_the_store(self):
        info_path = self.app / "Contents" / "Info.plist"
        info = plistlib.loads(info_path.read_bytes())
        info["SUFeedURL"] = "https://example.com/appcast.xml"
        info_path.write_bytes(plistlib.dumps(info))
        status = cli.main(
            ["pkg", "--app", str(self.app), "--out", str(self.scratch / "Demo.pkg")]
        )
        self.assertEqual(status, 1)
        self.assertFalse((self.scratch / "Demo.pkg").exists())


@unittest.skipUnless(sys.platform == "darwin", "codesign is a macOS tool")
@unittest.skipUnless(SPARKLE_DIR, "SPARKLE_DIR names no unpacked Sparkle release")
class SparkleBundle(unittest.TestCase):
    def setUp(self):
        self.scratch = Path(tempfile.mkdtemp(prefix="packager-sparkle-"))
        # A stand-in renderer and an executable linked against it by its absolute path,
        # the way the crate's build links a real one: the library is named by where it
        # is, and both have header room for the longer relative name (rustc asks for
        # that room too).
        renderer = self.scratch / "renderer" / "lib"
        renderer.mkdir(parents=True)
        library = renderer / bundle.RENDERER_LIBRARY
        compile_c(
            "int dioxus_compose_renderer_stand_in(void) { return 0; }",
            library, "-dynamiclib", "-install_name", str(library),
        )
        compile_c("int skiko_stand_in(void) { return 0; }", renderer / "libskiko-stand-in.dylib",
                  "-dynamiclib")
        self.executable = self.scratch / "sample-demo"
        compile_c(
            "int dioxus_compose_renderer_stand_in(void);\n"
            "int main(void) { return dioxus_compose_renderer_stand_in(); }",
            self.executable, str(library),
        )
        meta = metadata.load(project(CARGO, DIOXUS))
        settings = plist.SparkleSettings(
            feed_url="https://example.com/appcast.xml", public_ed_key=PUBLIC_KEY
        )
        self.app = bundle.assemble(
            meta, self.executable, self.scratch / "out", plist.SPARKLE, sparkle=settings,
            sparkle_framework=SPARKLE_DIR / "Sparkle.framework",
        )
        bundle.sign(self.app, "-", plist.SPARKLE, plist.entitlements(plist.SPARKLE))

    def test_fr34_1_renderer_directory_travels_whole_and_is_loaded_relatively(self):
        lib = self.app / "Contents" / "Frameworks" / "lib"
        self.assertTrue((lib / bundle.RENDERER_LIBRARY).is_file())
        self.assertTrue((lib / "libskiko-stand-in.dylib").is_file())
        subprocess.run(["codesign", "--verify", "--strict", str(lib / "libskiko-stand-in.dylib")],
                       check=True)
        loads = subprocess.run(
            ["otool", "-L", str(self.app / "Contents" / "MacOS" / "sample-demo")],
            capture_output=True, text=True, check=True,
        ).stdout
        self.assertIn("@executable_path/../Frameworks/lib/" + bundle.RENDERER_LIBRARY, loads)
        self.assertEqual(bundle.absolute_references(self.app), [])

    def test_fr34_1_a_renderer_named_through_rpath_needs_its_directory_given(self):
        renderer = self.scratch / "rpath-renderer" / "lib"
        renderer.mkdir(parents=True)
        library = renderer / bundle.RENDERER_LIBRARY
        compile_c(
            "int dioxus_compose_renderer_stand_in(void) { return 0; }",
            library, "-dynamiclib", "-install_name", "@rpath/" + bundle.RENDERER_LIBRARY,
        )
        executable = self.scratch / "rpath-demo"
        compile_c(
            "int dioxus_compose_renderer_stand_in(void);\n"
            "int main(void) { return dioxus_compose_renderer_stand_in(); }",
            executable, str(library),
        )
        meta = metadata.with_overrides(
            metadata.load(project(CARGO, DIOXUS)), executable="sample-demo"
        )
        store = dict(uses_non_exempt_encryption=False)
        with self.assertRaisesRegex(metadata.PackagingError, "--renderer-dir"):
            bundle.assemble(meta, executable, self.scratch / "rpath", plist.APP_STORE, **store)
        app = bundle.assemble(
            meta, executable, self.scratch / "rpath", plist.APP_STORE, renderer_dir=renderer,
            **store,
        )
        self.assertTrue((app / "Contents/Frameworks/lib" / bundle.RENDERER_LIBRARY).is_file())
        self.assertIn(
            "@executable_path/../Frameworks/lib/" + bundle.RENDERER_LIBRARY,
            bundle.load_commands(app / "Contents/MacOS/sample-demo"),
        )

    def test_fr34_1_a_renderer_directory_holding_data_is_refused_by_name(self):
        renderer = self.scratch / "renderer" / "lib"
        (renderer / "fonts.conf").write_text("data")
        meta = metadata.load(project(CARGO, DIOXUS))
        settings = plist.SparkleSettings(
            feed_url="https://example.com/appcast.xml", public_ed_key=PUBLIC_KEY
        )
        with self.assertRaisesRegex(metadata.PackagingError, "fonts.conf"):
            bundle.assemble(
                meta, self.executable, self.scratch / "again", plist.SPARKLE, sparkle=settings,
                sparkle_framework=SPARKLE_DIR / "Sparkle.framework",
            )

    def test_fr34_5_framework_and_everything_in_it_is_signed(self):
        framework = self.app / "Contents" / "Frameworks" / "Sparkle.framework"
        self.assertTrue((framework / "Versions" / "Current").is_symlink())
        for nested in ("Versions/B/Autoupdate", "Versions/B/Updater.app",
                       "Versions/B/XPCServices/Downloader.xpc"):
            subprocess.run(
                ["codesign", "--verify", "--strict", str(framework / nested)], check=True
            )
        subprocess.run(
            ["codesign", "--verify", "--strict", "--deep", str(self.app)], check=True
        )
        self.assertEqual(cli._verify(self.app, plist.SPARKLE), [])

    def test_fr34_6_a_sparkle_bundle_is_not_a_store_bundle(self):
        problems = bundle.sparkle_residue(self.app)
        self.assertTrue(any("Sparkle.framework is in the bundle" in p for p in problems))
        self.assertTrue(any("SUFeedURL" in p for p in problems))
        self.assertTrue(cli._verify(self.app, plist.APP_STORE))

    def test_fr34_9_hardware_requirement_follows_the_executable(self):
        expected = "arm64" if subprocess.run(
            ["uname", "-m"], capture_output=True, text=True
        ).stdout.strip() == "arm64" else None
        self.assertEqual(bundle.hardware_requirements(self.app), expected)

    def test_fr34_8_archive_round_trips_with_links_intact(self):
        archive = bundle.zip_for_sparkle(self.app, self.scratch / "Demo-1.4.2.zip")
        unpacked = self.scratch / "unpacked"
        subprocess.run(["ditto", "-x", "-k", str(archive), str(unpacked)], check=True)
        copy = unpacked / self.app.name
        self.assertTrue(
            (copy / "Contents/Frameworks/Sparkle.framework/Versions/Current").is_symlink()
        )
        subprocess.run(["codesign", "--verify", "--strict", "--deep", str(copy)], check=True)


if __name__ == "__main__":
    unittest.main()
