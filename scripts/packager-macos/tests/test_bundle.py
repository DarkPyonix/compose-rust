"""Assembling, signing and wrapping real bundles. macOS only: codesign, ditto and hdiutil.

The executables are built from one line of C, so these run in seconds and need no Rust
build. What a compose-rust executable adds, a renderer library loaded from where the
build found it, is made with a stand-in library linked the same way.
"""

import plistlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from support import CARGO, DIOXUS, REPOSITORY, project

from dxc_packager import bundle, cli, metadata

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
        "int compose_rust_renderer_stand_in(void) { return 0; }",
        library, "-dynamiclib", "-install_name", install_name or str(library),
    )
    compile_c(
        "int skiko_stand_in(void) { return 0; }",
        renderer / "libskiko-stand-in.dylib", "-dynamiclib",
    )
    executable = Path(scratch) / "sample-demo"
    compile_c(
        "int compose_rust_renderer_stand_in(void);\n"
        "int main(void) { return compose_rust_renderer_stand_in(); }",
        executable, str(library),
    )
    return renderer, executable


@unittest.skipUnless(sys.platform == "darwin", "codesign and hdiutil are macOS tools")
class Bundle(unittest.TestCase):
    def setUp(self):
        self.scratch = Path(tempfile.mkdtemp(prefix="packager-bundle-"))
        self.renderer, self.executable = renderer_and_executable(self.scratch)
        meta = metadata.with_overrides(metadata.load(project(CARGO, DIOXUS)), icon=ICON)
        self.app = bundle.sign(bundle.assemble(meta, self.executable, self.scratch / "out"))

    def test_fr22_6_3_layout_and_info_plist(self):
        contents = self.app / "Contents"
        self.assertEqual(self.app.name, "Demo App.app")
        self.assertTrue((contents / "MacOS" / "sample-demo").is_file())
        self.assertTrue((contents / "Resources" / "AppIcon.icns").is_file())
        self.assertEqual((contents / "PkgInfo").read_text(), "APPL????")
        info = plistlib.loads((contents / "Info.plist").read_bytes())
        self.assertEqual(info["CFBundleIdentifier"], "dev.example.demo")
        self.assertEqual(info["CFBundleIconFile"], "AppIcon")
        self.assertIs(info["NSHighResolutionCapable"], True)
        self.assertFalse([key for key in info if key.startswith("SU")])

    def test_fr22_6_2_renderer_directory_travels_whole_and_is_loaded_relatively(self):
        lib = self.app / "Contents" / "Frameworks" / "lib"
        self.assertTrue((lib / bundle.RENDERER_LIBRARY).is_file())
        self.assertTrue((lib / "libskiko-stand-in.dylib").is_file())
        self.assertIn(
            "@executable_path/../Frameworks/lib/" + bundle.RENDERER_LIBRARY,
            bundle.load_commands(self.app / "Contents" / "MacOS" / "sample-demo"),
        )
        self.assertEqual(bundle.absolute_references(self.app), [])

    def test_fr22_6_2_every_piece_of_code_is_signed_ad_hoc(self):
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
        self.assertEqual(cli._verify(self.app), [])

    def test_fr22_6_2_the_signed_application_runs(self):
        # The point of signing at all: Apple silicon kills unsigned arm64 code on sight,
        # and rewriting the load command left the linker's signature invalid.
        result = subprocess.run([str(self.app / "Contents" / "MacOS" / "sample-demo")])
        self.assertEqual(result.returncode, 0)

    def test_fr22_6_2_code_whose_signature_broke_is_reported(self):
        library = self.app / "Contents/Frameworks/lib/libskiko-stand-in.dylib"
        with open(library, "ab") as handle:
            handle.write(b"\0changed after signing")
        problems = bundle.unsigned_code(self.app)
        self.assertTrue(any("libskiko-stand-in.dylib" in problem for problem in problems))

    def test_fr22_6_2_a_renderer_named_through_rpath_needs_its_directory_given(self):
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

    def test_fr22_6_2_a_renderer_directory_holding_data_is_refused_by_name(self):
        (self.renderer / "fonts.conf").write_text("data")
        meta = metadata.load(project(CARGO, DIOXUS))
        with self.assertRaisesRegex(metadata.PackagingError, "fonts.conf"):
            bundle.assemble(meta, self.executable, self.scratch / "again")

    def test_fr22_6_5_disk_image_carries_the_app_applications_and_install_notes(self):
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


if __name__ == "__main__":
    unittest.main()
