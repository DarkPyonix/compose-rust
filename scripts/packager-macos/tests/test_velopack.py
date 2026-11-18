"""scripts/package-velopack.py: the vpk commands it builds and what it reads back.

These run without vpk. A stand-in vpk, written below, lays out the files vpk 1.2.161 writes
(names from Velopack.Core/DefaultName.cs, feed shape from ReleaseEntryHelper.cs), so the
part of the script that reads vpk's output is tested as well as the command it sends.
The real vpk runs in .github/workflows/packager-macos.yml.
"""

import contextlib
import importlib.util
import io
import json
import os
import stat
import sys
import tempfile
import textwrap
import unittest
import zipfile
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("package_velopack", SCRIPTS_DIR / "package-velopack.py")
package_velopack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package_velopack)

# Writes what vpk pack writes for Linux and Windows: the full .nupkg (a zip with a .nuspec),
# a delta when an older full package is in --outputDir, the portable package or Setup.exe,
# assets.<channel>.json and releases.<channel>.json listing every .nupkg in the directory.
FAKE_VPK = textwrap.dedent(
    """\
    #!/usr/bin/env python3
    import json, os, shutil, subprocess, sys, zipfile
    from pathlib import Path
    args = sys.argv[1:]
    def opt(name):
        return args[args.index(name) + 1]
    pack_id, version, channel = opt("--packId"), opt("--packVersion"), opt("--channel")
    out = Path(opt("--outputDir"))
    pack_dir = Path(opt("--packDir"))
    macos = pack_dir.suffix == ".app"
    exe = pack_dir / ("Contents/MacOS" if macos else "") / opt("--mainExe")
    assert exe.is_file(), exe
    windows = exe.suffix == ".exe"
    def name(kind):
        if windows and channel == "win":
            return f"{pack_id}-{version}-{kind}.nupkg"
        return f"{pack_id}-{version}-{channel}-{kind}.nupkg"
    def nupkg(path, ver):
        with zipfile.ZipFile(path, "w") as z:
            z.writestr(f"{pack_id}.nuspec", f"<package><metadata><version>{ver}</version></metadata></package>")
    previous = [p for p in out.glob("*-full.nupkg")]
    assets = []
    nupkg(out / name("full"), version); assets.append({"RelativeFileName": name("full"), "Type": "Full"})
    if previous:
        nupkg(out / name("delta"), version); assets.append({"RelativeFileName": name("delta"), "Type": "Delta"})
    if macos:
        # What vpk does to a .app: add UpdateMac and sq.version, sign it all, zip it.
        work = out.parent / ".fake-vpk"
        app = work / (opt("--packTitle") + ".app")
        shutil.rmtree(work, ignore_errors=True)
        shutil.copytree(pack_dir, app, symlinks=True)
        (app / "Contents/MacOS/UpdateMac").write_bytes(Path("/bin/ls").read_bytes())
        (app / "Contents/MacOS/UpdateMac").chmod(0o755)
        (app / "Contents/Resources/sq.version").write_text(version)
        os.symlink("../Resources/sq.version", app / "Contents/MacOS/sq.version")
        subprocess.run(["codesign", "-s", opt("--signAppIdentity"), "-f", "--options", "runtime",
                        "--deep", str(app)], check=True, capture_output=True)
        portable = f"{pack_id}-{channel}-Portable.zip"
        subprocess.run(["ditto", "-c", "-k", "--keepParent", str(app), str(out / portable)], check=True)
        shutil.rmtree(work)
        assets.append({"RelativeFileName": portable, "Type": "Portable"})
    elif windows:
        setup = f"{pack_id}-{channel}-Setup.exe"
        (out / setup).write_bytes(b"MZ"); assets.append({"RelativeFileName": setup, "Type": "Installer"})
        if "--msi" in args:
            (out / f"{pack_id}-{channel}.msi").write_bytes(b"msi")
    else:
        image = f"{pack_id}.AppImage" if channel == "linux" else f"{pack_id}-{channel}.AppImage"
        (out / image).write_bytes(b"ELF"); assets.append({"RelativeFileName": image, "Type": "Portable"})
    (out / f"assets.{channel}.json").write_text(json.dumps(assets))
    feed = []
    for p in sorted(out.glob("*.nupkg")):
        v = p.name[len(pack_id) + 1:].split("-")[0]
        feed.append({"PackageId": pack_id, "Version": v, "Type": "Delta" if p.name.endswith("-delta.nupkg") else "Full",
                     "FileName": p.name, "SHA1": "", "SHA256": "", "Size": p.stat().st_size})
    (out / f"releases.{channel}.json").write_text(json.dumps({"Assets": feed}))
    (out / ("RELEASES" if channel == "win" else f"RELEASES-{channel}")).write_text("")
    print("fake vpk packed", version)
    """
)


def meta(**changes):
    values = dict(
        pack_id="dev.darkpyonix.ember",
        version="1.2.3",
        title="Ember",
        authors="DarkPyonix",
        executable_name="ember",
    )
    values.update(changes)
    return package_velopack.ProjectMetadata(**values)


class Scratch:
    def __enter__(self):
        self._dir = tempfile.TemporaryDirectory()
        return Path(self._dir.name)

    def __exit__(self, *exc):
        self._dir.cleanup()


def run_main(argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = package_velopack.main(argv)
    return code, out.getvalue(), err.getvalue()


class MetadataTests(unittest.TestCase):
    def test_fr22_6_7_metadata_comes_from_the_sample(self):
        calculator = SCRIPTS_DIR.parent / "samples" / "calculator"
        if not calculator.is_dir():
            self.skipTest("samples/calculator not found")
        found = package_velopack.parse_metadata(calculator, {"executable_name": "sample-calculator"})
        self.assertEqual(found.pack_id, "dev.darkpyonix.composerust.samples.calculator")
        self.assertEqual(found.title, "Calculator")
        self.assertEqual(found.authors, "DarkPyonix")
        self.assertTrue(found.version)

    def test_fr22_6_7_pack_version_overrides_cargo(self):
        with Scratch() as scratch:
            (scratch / "Cargo.toml").write_text('[package]\nname = "app"\nversion = "0.1.0"\n')
            (scratch / "Dioxus.toml").write_text('[bundle]\nidentifier = "dev.example.app"\n')
            found = package_velopack.parse_metadata(
                scratch, {"pack_version": "2.0.0", "executable_name": "app"}
            )
            self.assertEqual(found.version, "2.0.0")
            self.assertEqual(found.pack_id, "dev.example.app")

    def test_fr22_6_7_a_workspace_version_needs_pack_version(self):
        with Scratch() as scratch:
            (scratch / "Cargo.toml").write_text('[package]\nname = "app"\nversion.workspace = true\n')
            with self.assertRaisesRegex(package_velopack.VelopackError, "--pack-version"):
                package_velopack.parse_metadata(scratch, {"executable_name": "app"})

    def test_fr22_6_7_a_pack_id_vpk_refuses_is_refused_not_rewritten(self):
        with self.assertRaisesRegex(package_velopack.VelopackError, "not one vpk accepts"):
            package_velopack.parse_metadata(
                None, {"pack_id": "my app", "pack_version": "1.0.0", "executable_name": "a"}
            )

    def test_fr22_6_7_a_version_that_is_not_semver_is_refused(self):
        with self.assertRaisesRegex(package_velopack.VelopackError, "SemVer"):
            package_velopack.parse_metadata(
                None, {"pack_id": "a.b", "pack_version": "v1.0", "executable_name": "a"}
            )

    def test_fr22_6_7_the_main_executable_keeps_its_name(self):
        name = package_velopack.main_exe_name
        self.assertEqual(name(Path("target/release/ember"), "windows"), "ember.exe")
        self.assertEqual(name(Path("target/release/ember.exe"), "windows"), "ember.exe")
        self.assertEqual(name(Path("target/release/my.tool"), "linux"), "my.tool")
        self.assertEqual(name(Path("target/release/ember"), "macos"), "ember")
        self.assertEqual(name(Path("x"), "linux", "renamed"), "renamed")

    def test_fr22_6_7_each_os_gets_the_icon_format_vpk_takes(self):
        icons = [Path("a.png"), Path("a.ico"), Path("a.icns")]
        found = meta(icons=icons, icon=icons[0])
        self.assertEqual(package_velopack.icon_for_os(found, "windows"), Path("a.ico"))
        self.assertEqual(package_velopack.icon_for_os(found, "macos"), Path("a.icns"))
        self.assertEqual(package_velopack.icon_for_os(found, "linux"), Path("a.png"))
        png_only = meta(icons=[Path("a.png")], icon=Path("a.png"))
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertIsNone(package_velopack.icon_for_os(png_only, "windows"))

    def test_fr22_6_7_a_prerelease_version_has_a_numeric_bundle_version(self):
        self.assertEqual(package_velopack.macos_bundle_version("1.2.0-beta.1"), "1.2.0")


class NameTests(unittest.TestCase):
    def test_fr22_6_7_file_names_match_vpk(self):
        rn = package_velopack.release_name
        self.assertEqual(rn("a.b", "1.0.1", "win", False, "windows"), "a.b-1.0.1-full.nupkg")
        self.assertEqual(rn("a.b", "1.0.1", "win", True, "windows"), "a.b-1.0.1-delta.nupkg")
        self.assertEqual(rn("a.b", "1.0.1", "osx", False, "macos"), "a.b-1.0.1-osx-full.nupkg")
        self.assertEqual(rn("a.b", "1.0.1", "linux", True, "linux"), "a.b-1.0.1-linux-delta.nupkg")
        self.assertEqual(package_velopack.portable_name("a.b", "linux", "linux"), "a.b.AppImage")
        self.assertEqual(package_velopack.portable_name("a.b", "beta", "linux"), "a.b-beta.AppImage")
        self.assertEqual(package_velopack.portable_name("a.b", "osx", "macos"), "a.b-osx-Portable.zip")
        self.assertEqual(package_velopack.setup_name("a.b", "win", "windows"), "a.b-win-Setup.exe")
        self.assertEqual(package_velopack.legacy_releases_name("win"), "RELEASES")
        self.assertEqual(package_velopack.legacy_releases_name("osx"), "RELEASES-osx")


class CommandTests(unittest.TestCase):
    def dry(self, target_os, **changes):
        with Scratch() as scratch:
            request = package_velopack.PackRequest(
                meta=changes.pop("meta", meta()),
                executable=scratch / "ember",
                output_dir=scratch / "out",
                channel=package_velopack.default_channel_for_os(target_os),
                target_os=target_os,
                dry_run=True,
                **changes,
            )
            with contextlib.redirect_stderr(io.StringIO()):
                command = package_velopack.package(request)["command"]
            self.assertFalse((scratch / "out").exists(), "a dry run writes nothing")
            return command

    def assertOption(self, command, name, value):
        self.assertIn(name, command)
        self.assertEqual(command[command.index(name) + 1], value)

    def test_fr22_6_7_commands_carry_the_pack_facts_and_no_hidden_windows_flag(self):
        for target_os, channel in (("macos", "osx"), ("linux", "linux"), ("windows", "win")):
            command = self.dry(target_os)
            self.assertEqual(command[:3], ["vpk", "pack", "--yes"])
            self.assertOption(command, "--packId", "dev.darkpyonix.ember")
            self.assertOption(command, "--packVersion", "1.2.3")
            self.assertOption(command, "--channel", channel)
            # A Windows-only, hidden option: vpk on macOS and Linux rejects it, and a Rust
            # executable passes the check it skips anyway (CompatUtil reads .NET only).
            self.assertNotIn("--skipVeloAppCheck", command)

    def test_fr22_6_12_macos_signs_ad_hoc_through_vpk_before_the_packages(self):
        command = self.dry("macos")
        self.assertOption(command, "--signAppIdentity", "-")
        self.assertIn("--noInst", command)
        self.assertTrue(command[command.index("--packDir") + 1].endswith("Ember.app"))
        self.assertOption(command, "--mainExe", "ember")
        command = self.dry("macos", sign_app_identity="Developer ID Application: X", notary_profile="p",
                           macos_installer=True)
        self.assertOption(command, "--signAppIdentity", "Developer ID Application: X")
        self.assertOption(command, "--notaryProfile", "p")
        self.assertNotIn("--noInst", command)

    def test_fr22_6_7_windows_names_the_exe_and_takes_only_an_ico(self):
        command = self.dry("windows", meta=meta(icons=[Path("i.png"), Path("i.ico")]), msi=True)
        self.assertOption(command, "--mainExe", "ember.exe")
        self.assertOption(command, "--icon", "i.ico")
        self.assertIn("--msi", command)

    def test_fr22_6_7_linux_passes_a_png_icon(self):
        command = self.dry("linux", meta=meta(icons=[Path("i.png")]))
        self.assertOption(command, "--icon", "i.png")

    def test_fr22_6_7_extra_vpk_arguments_go_last(self):
        command = self.dry("linux", vpk_args=["--delta", "None"])
        self.assertEqual(command[-2:], ["--delta", "None"])


class FeedTests(unittest.TestCase):
    def test_fr22_6_7_the_feed_is_read_as_vpk_writes_it(self):
        with Scratch() as out:
            feed = {
                "Assets": [
                    {"PackageId": "a.b", "Version": "1.0.1", "Type": "Full", "FileName": "a.b-1.0.1-osx-full.nupkg"},
                    {"PackageId": "a.b", "Version": "1.0.1", "Type": "Delta", "FileName": "a.b-1.0.1-osx-delta.nupkg"},
                    {"PackageId": "a.b", "Version": "1.0.0", "Type": "Full", "FileName": "a.b-1.0.0-osx-full.nupkg"},
                ]
            }
            (out / "releases.osx.json").write_text(json.dumps(feed))
            (out / "a.b-1.0.1-osx-full.nupkg").touch()
            (out / "a.b-1.0.1-osx-delta.nupkg").touch()
            found = package_velopack.verify_feed(out, "osx", "1.0.1", expect_delta=True)
            self.assertEqual(found["full_nupkg"].name, "a.b-1.0.1-osx-full.nupkg")
            self.assertEqual(found["delta_nupkg"].name, "a.b-1.0.1-osx-delta.nupkg")
            with self.assertRaisesRegex(package_velopack.VelopackError, "no full package of 2.0.0"):
                package_velopack.verify_feed(out, "osx", "2.0.0")

    def test_fr22_6_7_a_missing_delta_is_an_error_when_one_was_due(self):
        with Scratch() as out:
            feed = {"Assets": [{"Version": "1.0.1", "Type": 1, "FileName": "a-1.0.1-full.nupkg"}]}
            (out / "releases.win.json").write_text(json.dumps(feed))
            (out / "a-1.0.1-full.nupkg").touch()
            package_velopack.verify_feed(out, "win", "1.0.1")
            with self.assertRaisesRegex(package_velopack.VelopackError, "no delta"):
                package_velopack.verify_feed(out, "win", "1.0.1", expect_delta=True)

    def test_fr22_6_7_only_full_packages_are_carried_into_the_new_output(self):
        with Scratch() as scratch:
            previous, out = scratch / "previous", scratch / "out"
            previous.mkdir()
            for name in ("a-1.0.0-full.nupkg", "a-1.0.0-delta.nupkg", "releases.win.json", "a-win-Setup.exe"):
                (previous / name).touch()
            copied = package_velopack.copy_delta_files(previous, out)
            self.assertEqual([p.name for p in copied], ["a-1.0.0-full.nupkg"])
            self.assertEqual(sorted(p.name for p in out.iterdir()), ["a-1.0.0-full.nupkg"])


@unittest.skipIf(os.name == "nt", "the stand-in vpk is a POSIX script")
class StandInVpkTests(unittest.TestCase):
    """The whole run against the stand-in vpk: delta carry-over, checks and upload list."""

    def setUp(self):
        self._scratch = tempfile.TemporaryDirectory()
        self.scratch = Path(self._scratch.name)
        self.vpk = self.scratch / "vpk"
        self.vpk.write_text(FAKE_VPK)
        self.vpk.chmod(self.vpk.stat().st_mode | stat.S_IEXEC)
        self.exe = self.scratch / "velopack-fixture"
        self.exe.write_bytes(b"\x7fELF")

    def tearDown(self):
        self._scratch.cleanup()

    def pack(self, target_os, version, out, delta=None, extra=()):
        argv = [
            "--pack-id", "dev.darkpyonix.fixture", "--pack-version", version,
            "--executable", str(self.exe), "--output-dir", str(out),
            "--target-os", target_os, "--vpk", str(self.vpk), *extra,
        ]
        if delta is not None:
            argv += ["--delta-dir", str(delta)]
        code, stdout, stderr = run_main(argv)
        self.assertEqual(code, 0, stderr)
        self.assertIn("fake vpk packed", stderr, "vpk's output reaches stderr")
        return json.loads(stdout)

    def test_fr22_6_7_two_linux_releases_make_a_delta_and_an_upload_list(self):
        v1, v2 = self.scratch / "v1", self.scratch / "v2"
        first = self.pack("linux", "1.0.0", v1)
        self.assertNotIn("delta_nupkg", first)
        second = self.pack("linux", "1.0.1", v2, delta=v1)
        self.assertTrue(second["delta_nupkg"].endswith("dev.darkpyonix.fixture-1.0.1-linux-delta.nupkg"))
        self.assertTrue(second["appimage"].endswith("dev.darkpyonix.fixture.AppImage"))
        upload = sorted(Path(p).name for p in second["upload"])
        self.assertEqual(
            upload,
            sorted([
                "dev.darkpyonix.fixture-1.0.1-linux-full.nupkg",
                "dev.darkpyonix.fixture-1.0.1-linux-delta.nupkg",
                "dev.darkpyonix.fixture.AppImage",
                "releases.linux.json",
                "RELEASES-linux",
            ]),
        )
        self.assertNotIn("dev.darkpyonix.fixture-1.0.0-linux-full.nupkg", upload,
                         "the previous release's package is not uploaded again")
        self.assertEqual(set(second["sha256"]), set(upload))
        self.assertFalse(any(p.name.startswith(".v2-staging") for p in self.scratch.iterdir()),
                         "the staging directory is removed")

    def test_fr22_6_7_windows_release_has_setup_and_msi(self):
        out = self.scratch / "win"
        result = self.pack("windows", "1.0.0", out, extra=["--msi"])
        self.assertTrue(result["setup_exe"].endswith("dev.darkpyonix.fixture-win-Setup.exe"))
        self.assertTrue(result["msi"].endswith("dev.darkpyonix.fixture-win.msi"))
        self.assertTrue(result["full_nupkg"].endswith("dev.darkpyonix.fixture-1.0.0-full.nupkg"))
        self.assertTrue((out / "dev.darkpyonix.fixture-win" "-Setup.exe").is_file())

    @unittest.skipUnless(sys.platform == "darwin", "makes and signs a .app and a .dmg")
    def test_fr22_6_12_the_dmg_holds_the_app_vpk_signed(self):
        executable = self.scratch / "fixture"
        executable.write_bytes(Path("/bin/echo").read_bytes())
        executable.chmod(0o755)
        argv = [
            "--pack-id", "dev.darkpyonix.fixture", "--pack-version", "1.0.0", "--pack-title", "Fixture",
            "--executable", str(executable), "--output-dir", str(self.scratch / "mac"),
            "--target-os", "macos", "--vpk", str(self.vpk),
        ]
        code, stdout, stderr = run_main(argv)
        self.assertEqual(code, 0, stderr)
        result = json.loads(stdout)
        self.assertTrue(result["dmg"].endswith("dev.darkpyonix.fixture-osx.dmg"))
        self.assertTrue(Path(result["dmg"]).is_file())
        self.assertIn("dev.darkpyonix.fixture-osx.dmg", result["sha256"])
        self.assertIn("dev.darkpyonix.fixture-osx-Portable.zip", result["sha256"])

    def test_fr22_6_7_a_failing_vpk_says_so(self):
        failing = self.scratch / "failing-vpk"
        failing.write_text("#!/bin/sh\necho 'vpk said no' >&2\nexit 3\n")
        failing.chmod(0o755)
        err = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            with self.assertRaisesRegex(package_velopack.VelopackError, "status 3"):
                package_velopack.main([
                    "--pack-id", "a.b", "--pack-version", "1.0.0", "--executable", str(self.exe),
                    "--output-dir", str(self.scratch / "o"), "--target-os", "linux", "--vpk", str(failing),
                ])

    def test_fr22_6_7_a_missing_vpk_names_the_install_command(self):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaisesRegex(package_velopack.VelopackError, "dotnet tool install -g vpk --version"):
                package_velopack.main([
                    "--pack-id", "a.b", "--pack-version", "1.0.0", "--executable", str(self.exe),
                    "--output-dir", str(self.scratch / "o"), "--target-os", "linux",
                    "--vpk", str(self.scratch / "no-such-vpk"),
                ])


if __name__ == "__main__":
    unittest.main()
