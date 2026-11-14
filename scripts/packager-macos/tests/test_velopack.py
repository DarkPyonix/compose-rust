import json
import tempfile
import unittest
from pathlib import Path

# Add scripts directory to path to import package_velopack
SCRIPTS_DIR = Path(__file__).resolve().parents[2]
import sys
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import importlib.util
spec = importlib.util.spec_from_file_location("package_velopack", str(SCRIPTS_DIR / "package-velopack.py"))
package_velopack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package_velopack)


class VelopackTests(unittest.TestCase):
    def setUp(self):
        self.repo_root = SCRIPTS_DIR.parent
        self.samples = self.repo_root / "samples"

    def test_fr22_6_7_metadata_parsing_from_sample(self):
        calc = self.samples / "calculator"
        if not calc.is_dir():
            self.skipTest("samples/calculator not found")
        meta = package_velopack.parse_metadata(calc, {})
        self.assertEqual(meta.pack_id, "dev.darkpyonix.composerust.samples.calculator")
        self.assertEqual(meta.title, "Calculator")
        self.assertTrue(bool(meta.version))

    def test_fr22_6_7_dry_run_command_construction_macos(self):
        with tempfile.TemporaryDirectory() as scratch_dir:
            scratch = Path(scratch_dir)
            out_dir = scratch / "releases"
            fake_exe = scratch / "fake_app"
            fake_exe.touch()

            meta = package_velopack.ProjectMetadata(
                pack_id="com.test.app",
                version="1.2.3",
                title="TestApp",
                authors="TestAuthor",
                executable_name="fake_app",
            )

            result = package_velopack.package_macos(
                meta=meta,
                executable=fake_exe,
                output_dir=out_dir,
                channel="osx",
                vpk_cmd="vpk",
                dry_run=True,
            )
            cmd = result["command"]
            self.assertIn("pack", cmd)
            self.assertIn("--packId", cmd)
            self.assertIn("com.test.app", cmd)
            self.assertIn("--packVersion", cmd)
            self.assertIn("1.2.3", cmd)
            self.assertIn("--skipVeloAppCheck", cmd)
            self.assertIn("--channel", cmd)
            self.assertIn("osx", cmd)

    def test_fr22_6_7_dry_run_command_construction_linux(self):
        with tempfile.TemporaryDirectory() as scratch_dir:
            scratch = Path(scratch_dir)
            out_dir = scratch / "releases"
            fake_exe = scratch / "fake_app"
            fake_exe.touch()

            meta = package_velopack.ProjectMetadata(
                pack_id="com.test.app",
                version="1.2.3",
                title="TestApp",
                authors="TestAuthor",
                executable_name="fake_app",
            )

            result = package_velopack.package_linux(
                meta=meta,
                executable=fake_exe,
                output_dir=out_dir,
                channel="linux",
                vpk_cmd="vpk",
                dry_run=True,
            )
            cmd = result["command"]
            self.assertIn("pack", cmd)
            self.assertIn("--packId", cmd)
            self.assertIn("com.test.app", cmd)
            self.assertIn("--packVersion", cmd)
            self.assertIn("1.2.3", cmd)
            self.assertIn("--skipVeloAppCheck", cmd)
            self.assertIn("--channel", cmd)
            self.assertIn("linux", cmd)

    def test_fr22_6_7_feed_verification_and_delta(self):
        with tempfile.TemporaryDirectory() as scratch_dir:
            scratch = Path(scratch_dir)
            out_dir = scratch / "releases"
            out_dir.mkdir()

            feed_content = {
                "releases": [
                    {
                        "version": "1.0.0",
                        "packageId": "com.test.app",
                        "files": ["com.test.app-1.0.0-full.nupkg"],
                    },
                    {
                        "version": "1.0.1",
                        "packageId": "com.test.app",
                        "files": ["com.test.app-1.0.1-full.nupkg", "com.test.app-1.0.1-delta.nupkg"],
                    },
                ]
            }
            (out_dir / "releases.osx.json").write_text(json.dumps(feed_content), encoding="utf-8")
            (out_dir / "com.test.app-1.0.1-full.nupkg").touch()
            (out_dir / "com.test.app-1.0.1-delta.nupkg").touch()

            verified = package_velopack.verify_feed(out_dir, "osx", "com.test.app", "1.0.1", expect_delta=True)
            self.assertEqual(verified["release"]["version"], "1.0.1")
            self.assertTrue(verified["delta_nupkg"].name.endswith("-delta.nupkg"))

    def test_fr22_6_7_copy_delta_files(self):
        with tempfile.TemporaryDirectory() as scratch_dir:
            scratch = Path(scratch_dir)
            v1_dir = scratch / "v1"
            v1_dir.mkdir()
            v2_dir = scratch / "v2"
            v2_dir.mkdir()

            (v1_dir / "releases.osx.json").write_text('{"releases":[]}', encoding="utf-8")
            (v1_dir / "com.test.app-1.0.0-full.nupkg").touch()

            package_velopack.copy_delta_files(v1_dir, v2_dir, "osx")
            self.assertTrue((v2_dir / "releases.osx.json").is_file())
            self.assertTrue((v2_dir / "com.test.app-1.0.0-full.nupkg").is_file())


if __name__ == "__main__":
    unittest.main()
