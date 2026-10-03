"""Assembling, signing and wrapping real bundles. macOS only: codesign, ditto and hdiutil.

The executables are built from one line of C, so these run in seconds and need no Rust
build. What a dioxus-compose executable adds, a renderer library loaded from where the
build found it, is made with a stand-in library linked the same way.
"""

import plistlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from support import CARGO, DIOXUS, REPOSITORY, SPARKLE_DIR, project

from dxc_packager import bundle, cli, metadata, plist

PUBLIC_KEY = "2kja3FFzveTqSefH+8D0npKfvngmu2zAMqubhIgBuh8="
ICON = REPOSITORY / "samples" / "calculator" / "assets" / "icon.png"


def compile_c(source, out, *flags):
    subprocess.run(
        # The flags come before `-x c`, which would otherwise read a library as C source.
        ["cc", "-o", str(out), "-Wl,-headerpad_max_install_names", *flags, "-x", "c", "-"],
        input=source + "\n", text=True, check=True,
    )


def renderer_and_executable(scratch, install_name=None):
    """A stand-in renderer directory and an executable linked against it.

    By default the library is named by its absolute path, as the crate's build names the
    real one; both have header room for the longer relative name, which rustc asks for too.
    """
    renderer = Path(scratch) / "renderer" / "lib"
    renderer.mkdir(parents=True)
    library = renderer / bundle.RENDERER_LIBRARY
    compile_c(
        "int dioxus_compose_renderer_stand_in(void) { return 0; }",
        library, "-dynamiclib", "-install_name", install_name or str(library),
    )
    compile_c(
        "int skiko_stand_in(void) { return 0; }",
        renderer / "libskiko-stand-in.dylib", "-dynamiclib",
    )
    executable = Path(scratch) / "sample-demo"
    compile_c(
        "int dioxus_compose_renderer_stand_in(void);\n"
        "int main(void) { return dioxus_compose_renderer_stand_in(); }",
        executable, str(library),
    )
    return renderer, executable


def settings():
    return plist.SparkleSettings(
        feed_url="https://example.com/appcast.xml", public_ed_key=PUBLIC_KEY
    )


@unittest.skipUnless(sys.platform == "darwin", "codesign and hdiutil are macOS tools")
class PlainBundle(unittest.TestCase):
    def setUp(self):
        self.scratch = Path(tempfile.mkdtemp(prefix="packager-plain-"))
        self.renderer, self.executable = renderer_and_executable(self.scratch)
        meta = metadata.with_overrides(metadata.load(project(CARGO, DIOXUS)), icon=ICON)
        self.app = bundle.sign(bundle.assemble(meta, self.executable, self.scratch / "out"))

    def test_fr35_1_layout_and_info_plist(self):
        contents = self.app / "Contents"
        self.assertEqual(self.app.name, "Demo App.app")
        self.assertTrue((contents / "MacOS" / "sample-demo").is_file())
        self.assertTrue((contents / "Resources" / "AppIcon.icns").is_file())
        self.assertEqual((contents / "PkgInfo").read_text(), "APPL????")
        self.assertFalse((contents / "Frameworks" / "Sparkle.framework").exists())
        info = plistlib.loads((contents / "Info.plist").read_bytes())
        self.assertEqual(info["CFBundleIdentifier"], "dev.example.demo")
        self.assertEqual(info["CFBundleIconFile"], "AppIcon")
        self.assertIs(info["NSHighResolutionCapable"], True)
        self.assertFalse([key for key in info if key.startswith("SU")])

    def test_fr35_3_renderer_directory_travels_whole_and_is_loaded_relatively(self):
        lib = self.app / "Contents" / "Frameworks" / "lib"
        self.assertTrue((lib / bundle.RENDERER_LIBRARY).is_file())
        self.assertTrue((lib / "libskiko-stand-in.dylib").is_file())
        self.assertIn(
            "@executable_path/../Frameworks/lib/" + bundle.RENDERER_LIBRARY,
            bundle.load_commands(self.app / "Contents" / "MacOS" / "sample-demo"),
        )
        self.assertEqual(bundle.absolute_references(self.app), [])

    def test_fr35_3_every_piece_of_code_is_signed_ad_hoc(self):
        subprocess.run(["codesign", "--verify", "--strict", "--deep", str(self.app)], check=True)
        self.assertEqual(bundle.unsigned_code(self.app), [])
        for path in (
            self.app,
            self.app / "Contents/Frameworks/lib" / bundle.RENDERER_LIBRARY,
            self.app / "Contents/Frameworks/lib/libskiko-stand-in.dylib",
        ):
            details = subprocess.run(
                ["codesign", "-dv", str(path)], capture_output=True, text=True
            ).stderr
            self.assertIn("Signature=adhoc", details, path)
        details = subprocess.run(
            ["codesign", "-dv", str(self.app)], capture_output=True, text=True
        ).stderr
        self.assertIn("Identifier=dev.example.demo", details)
        self.assertEqual(cli._verify(self.app, plist.NONE), [])

    def test_fr35_3_the_signed_application_runs(self):
        # The point of signing at all: Apple silicon kills unsigned arm64 code on sight,
        # and rewriting the load command left the linker's signature invalid.
        result = subprocess.run([str(self.app / "Contents" / "MacOS" / "sample-demo")])
        self.assertEqual(result.returncode, 0)

    def test_fr35_3_code_whose_signature_broke_is_reported(self):
        library = self.app / "Contents/Frameworks/lib/libskiko-stand-in.dylib"
        with open(library, "ab") as handle:
            handle.write(b"\0changed after signing")
        problems = bundle.unsigned_code(self.app)
        self.assertTrue(any("libskiko-stand-in.dylib" in problem for problem in problems))

    def test_fr35_3_a_renderer_named_through_rpath_needs_its_directory_given(self):
        scratch = self.scratch / "rpath"
        renderer, executable = renderer_and_executable(
            scratch, install_name="@rpath/" + bundle.RENDERER_LIBRARY
        )
        meta = metadata.load(project(CARGO, DIOXUS))
        with self.assertRaisesRegex(metadata.PackagingError, "--renderer-dir"):
            bundle.assemble(meta, executable, scratch / "out")
        app = bundle.assemble(meta, executable, scratch / "out", renderer_dir=renderer)
        self.assertIn(
            "@executable_path/../Frameworks/lib/" + bundle.RENDERER_LIBRARY,
            bundle.load_commands(app / "Contents/MacOS/sample-demo"),
        )

    def test_fr35_3_a_renderer_directory_holding_data_is_refused_by_name(self):
        (self.renderer / "fonts.conf").write_text("data")
        meta = metadata.load(project(CARGO, DIOXUS))
        with self.assertRaisesRegex(metadata.PackagingError, "fonts.conf"):
            bundle.assemble(meta, self.executable, self.scratch / "again")

    def test_fr35_4_disk_image_carries_the_app_applications_and_install_notes(self):
        image = bundle.dmg(self.app, self.scratch / "Demo.dmg", "Demo App")
        mount = self.scratch / "mount"
        attached = subprocess.run(
            ["hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", str(mount), str(image)],
            capture_output=True, text=True,
        )
        if attached.returncode != 0:
            # A sandboxed shell may make images but not mount them; CI can do both.
            self.skipTest(f"this shell cannot mount a disk image: {attached.stderr.strip()}")
        try:
            self.assertTrue((mount / "Demo App.app" / "Contents" / "Info.plist").is_file())
            self.assertEqual(str((mount / "Applications").readlink()), "/Applications")
            notes = (mount / "If macOS will not open the app.txt").read_text()
            self.assertIn("Open Anyway", notes)
            self.assertIn("xattr -dr com.apple.quarantine", notes)
            subprocess.run(
                ["codesign", "--verify", "--strict", "--deep", str(mount / "Demo App.app")],
                check=True,
            )
        finally:
            subprocess.run(["hdiutil", "detach", str(mount)], capture_output=True)

    def test_fr35_1_sparkle_options_without_a_feed_are_refused(self):
        meta = metadata.load(project(CARGO, DIOXUS))
        with self.assertRaisesRegex(metadata.PackagingError, "--updater sparkle"):
            bundle.assemble(
                meta, self.executable, self.scratch / "x", sparkle_framework=Path("Sparkle.framework")
            )


@unittest.skipUnless(sys.platform == "darwin", "codesign is a macOS tool")
@unittest.skipUnless(SPARKLE_DIR, "SPARKLE_DIR names no unpacked Sparkle release")
class SparkleBundle(unittest.TestCase):
    def setUp(self):
        self.scratch = Path(tempfile.mkdtemp(prefix="packager-sparkle-"))
        _, self.executable = renderer_and_executable(self.scratch)
        meta = metadata.load(project(CARGO, DIOXUS))
        self.app = bundle.sign(
            bundle.assemble(
                meta, self.executable, self.scratch / "out", sparkle=settings(),
                sparkle_framework=SPARKLE_DIR / "Sparkle.framework",
            )
        )

    def test_fr35_3_framework_and_everything_in_it_is_signed_ad_hoc(self):
        framework = self.app / "Contents" / "Frameworks" / "Sparkle.framework"
        self.assertTrue((framework / "Versions" / "Current").is_symlink())
        for nested in ("Versions/B/Sparkle", "Versions/B/Autoupdate", "Versions/B/Updater.app",
                       "Versions/B/XPCServices/Downloader.xpc",
                       "Versions/B/XPCServices/Installer.xpc"):
            details = subprocess.run(
                ["codesign", "-dv", str(framework / nested)], capture_output=True, text=True
            ).stderr
            self.assertIn("Signature=adhoc", details, nested)
        subprocess.run(["codesign", "--verify", "--strict", "--deep", str(self.app)], check=True)
        self.assertEqual(cli._verify(self.app, plist.SPARKLE), [])

    def test_fr35_3_a_sparkle_bundle_is_not_a_plain_one(self):
        self.assertTrue(cli._verify(self.app, plist.NONE))

    def test_fr35_5_hardware_requirement_follows_the_executable(self):
        machine = subprocess.run(["uname", "-m"], capture_output=True, text=True).stdout.strip()
        expected = "arm64" if machine == "arm64" else None
        self.assertEqual(bundle.hardware_requirements(self.app), expected)

    def test_fr35_4_archive_round_trips_with_links_and_signatures_intact(self):
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
