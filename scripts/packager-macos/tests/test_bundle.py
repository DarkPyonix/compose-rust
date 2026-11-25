"""Assembling, signing and wrapping real bundles. macOS only: codesign, ditto and hdiutil.

The executables are built from one line of C, so these run in seconds and need no Rust
build. What a compose-rust executable adds, a renderer library loaded from where the
build found it, is made with a stand-in library linked the same way.
"""

import contextlib
import io
import plistlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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

    def test_fr22_6_14_every_piece_of_code_is_signed_ad_hoc(self):
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

    def test_fr22_6_14_the_signed_application_runs(self):
        # The point of signing at all: Apple silicon kills unsigned arm64 code on sight,
        # and rewriting the load command left the linker's signature invalid.
        result = subprocess.run([str(self.app / "Contents" / "MacOS" / "sample-demo")])
        self.assertEqual(result.returncode, 0)

    def test_fr22_6_14_code_whose_signature_broke_is_reported(self):
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

    def test_fr22_6_2_one_executable_is_the_whole_bundle_with_its_icon_and_metadata(self):
        # The renderer linked into the executable: the bundle holds that file, the icon and
        # the metadata, and no Frameworks directory waiting for a library that never comes.
        scratch = self.scratch / "single"
        scratch.mkdir()
        executable = scratch / "sample-demo"
        compile_c("int main(void) { return 0; }", executable)
        meta = metadata.with_overrides(metadata.load(project(CARGO, DIOXUS)), icon=ICON)
        app = bundle.sign(bundle.assemble(meta, executable, scratch / "out"))
        files = sorted(
            str(path.relative_to(app)) for path in app.rglob("*") if not path.is_dir()
        )
        self.assertEqual(files, [
            "Contents/Info.plist",
            "Contents/MacOS/sample-demo",
            "Contents/PkgInfo",
            "Contents/Resources/AppIcon.icns",
            "Contents/_CodeSignature/CodeResources",
        ])
        self.assertFalse((app / "Contents" / "Frameworks").exists())
        self.assertEqual(cli._verify(app), [])

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

    def test_fr22_6_8_pkg_builds_mas_component_and_passes_pkgutil_checks(self):
        out_pkg = self.scratch / "Demo App.pkg"
        built = bundle.pkg(self.app, out_pkg)
        self.assertEqual(built, out_pkg)
        self.assertTrue(out_pkg.is_file())

        report = bundle.verify_pkg(out_pkg, expect_signed=False)
        self.assertFalse(report["signed"])
        self.assertTrue(
            "no signature" in report["signature_output"].lower()
            or "not signed" in report["signature_output"].lower(),
            report["signature_output"],
        )
        self.assertTrue(
            any("Demo App.app/Contents/MacOS/sample-demo" in p for p in report["payload_files"]),
            report["payload_files"],
        )
        self.assertTrue(
            any("Demo App.app/Contents/Info.plist" in p for p in report["payload_files"]),
            report["payload_files"],
        )
        self.assertTrue(
            any("Demo App.app/Contents/Resources/AppIcon.icns" in p for p in report["payload_files"]),
            report["payload_files"],
        )
        with self.assertRaisesRegex(metadata.PackagingError, "expected .* to be signed"):
            bundle.verify_pkg(out_pkg, expect_signed=True)


class IconWarning(unittest.TestCase):
    """Runs anywhere: the macOS tools are replaced, only where the warning goes is checked."""

    def test_fr22_6_a_small_icon_warning_leaves_stdout_to_the_json_result(self):
        # package-velopack.py prints its result as JSON on stdout, and CI parses it. A
        # warning printed there made that JSON unreadable on macOS.
        def tools(command, **_):
            return mock.Mock(stdout="pixelWidth: 512\n" if command[:2] == ["sips", "-g"] else "")

        out, err = io.StringIO(), io.StringIO()
        with tempfile.TemporaryDirectory() as scratch, mock.patch.object(bundle, "run", tools), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            bundle.make_icns(Path(scratch) / "icon.png", Path(scratch) / "AppIcon.icns")
        self.assertEqual(out.getvalue(), "")
        self.assertIn("512 pixels wide", err.getvalue())


class PkgCommandTests(unittest.TestCase):
    """Cross-platform tests for `bundle.pkg`, `bundle.verify_pkg` and the `pkg` CLI subcommand."""

    def setUp(self):
        self.scratch = Path(tempfile.mkdtemp(prefix="packager-pkg-"))
        self.app = self.scratch / "Demo App.app"
        self.app.mkdir()
        self.out_pkg = self.scratch / "dist" / "Demo App.pkg"

    def test_fr22_6_8_pkg_invokes_productbuild_with_and_without_sign_identity(self):
        calls = []

        def fake_run(command, **kwargs):
            calls.append(command)
            Path(command[-1]).write_bytes(b"pkg")
            return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

        with mock.patch.object(bundle, "run", side_effect=fake_run):
            result = bundle.pkg(self.app, self.out_pkg)
            self.assertEqual(result, self.out_pkg)
            self.assertEqual(
                calls[-1],
                ["productbuild", "--component", str(self.app), "/Applications", str(self.out_pkg)],
            )

            bundle.pkg(
                self.app,
                self.out_pkg,
                sign_identity="3rd Party Mac Developer Installer: Example (TEAMID)",
            )
            self.assertEqual(
                calls[-1],
                [
                    "productbuild",
                    "--component",
                    str(self.app),
                    "/Applications",
                    "--sign",
                    "3rd Party Mac Developer Installer: Example (TEAMID)",
                    str(self.out_pkg),
                ],
            )

    def test_fr22_6_8_verify_pkg_accepts_unsigned_when_not_required_and_rejects_when_required(self):
        self.out_pkg.parent.mkdir(parents=True, exist_ok=True)
        self.out_pkg.write_bytes(b"pkg")

        payload_proc = subprocess.CompletedProcess(
            ["pkgutil", "--payload-files", str(self.out_pkg)],
            0,
            stdout="./Demo App.app/Contents/MacOS/sample-demo\n./Demo App.app/Contents/Info.plist\n",
            stderr="",
        )
        unsigned_proc = subprocess.CompletedProcess(
            ["pkgutil", "--check-signature", str(self.out_pkg)],
            1,
            stdout='Package "Demo App.pkg":\n   Status: package is not signed\n',
            stderr="",
        )
        signed_proc = subprocess.CompletedProcess(
            ["pkgutil", "--check-signature", str(self.out_pkg)],
            0,
            stdout='Package "Demo App.pkg":\n   Status: signed by a developer certificate\n',
            stderr="",
        )

        with mock.patch.object(bundle, "run", return_value=payload_proc), mock.patch.object(
            bundle.subprocess, "run", return_value=unsigned_proc
        ):
            report = bundle.verify_pkg(self.out_pkg, expect_signed=False)
            self.assertFalse(report["signed"])
            self.assertEqual(len(report["payload_files"]), 2)
            with self.assertRaises(metadata.PackagingError):
                bundle.verify_pkg(self.out_pkg, expect_signed=True)

        with mock.patch.object(bundle, "run", return_value=payload_proc), mock.patch.object(
            bundle.subprocess, "run", return_value=signed_proc
        ):
            report = bundle.verify_pkg(self.out_pkg, expect_signed=True)
            self.assertTrue(report["signed"])

    def test_fr22_6_8_cli_pkg_parses_options_and_runs_verify(self):
        with mock.patch.object(bundle, "pkg", return_value=self.out_pkg) as mock_pkg, mock.patch.object(
            bundle, "verify_pkg"
        ) as mock_verify:
            status = cli.main(
                [
                    "pkg",
                    "--app",
                    str(self.app),
                    "--out",
                    str(self.out_pkg),
                    "--sign-identity",
                    "Installer Cert",
                    "--verify",
                ]
            )
            self.assertEqual(status, 0)
            mock_pkg.assert_called_once_with(self.app, self.out_pkg, sign_identity="Installer Cert")
            mock_verify.assert_called_once_with(self.out_pkg, expect_signed=True)


if __name__ == "__main__":
    unittest.main()
