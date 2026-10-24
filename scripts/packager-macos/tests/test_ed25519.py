"""The signature arithmetic, against RFC 8032 and against Sparkle's own sign_update."""

import base64
import subprocess
import tempfile
import unittest
from pathlib import Path

from support import SPARKLE_DIR  # noqa: F401  (puts the packager on the path)

from dxc_packager import ed25519, signing

# RFC 8032, section 7.1, tests 1 and 2.
VECTORS = [
    (
        "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
        "",
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bac"
        "c61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
    ),
    (
        "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
        "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
        "72",
        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e"
        "458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
    ),
]


class Rfc8032(unittest.TestCase):
    def test_fr35_5_public_key_and_signature_match_the_rfc_vectors(self):
        for secret, public, message, signature in VECTORS:
            secret = bytes.fromhex(secret)
            message = bytes.fromhex(message)
            self.assertEqual(ed25519.public_key(secret).hex(), public)
            self.assertEqual(ed25519.sign(secret, message).hex(), signature)
            self.assertTrue(
                ed25519.verify(bytes.fromhex(public), message, bytes.fromhex(signature))
            )

    def test_fr35_5_a_changed_byte_does_not_verify(self):
        secret, public, _, _ = VECTORS[1]
        signature = ed25519.sign(bytes.fromhex(secret), b"archive")
        self.assertFalse(ed25519.verify(bytes.fromhex(public), b"archivf", signature))
        tampered = bytes([signature[0] ^ 1]) + signature[1:]
        self.assertFalse(ed25519.verify(bytes.fromhex(public), b"archive", tampered))

    def test_fr35_5_a_key_that_is_not_a_seed_is_refused_with_the_way_out(self):
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / "key"
            path.write_text(base64.b64encode(bytes(64)).decode())
            with self.assertRaisesRegex(ed25519.InvalidKey, "generate_keys -x"):
                ed25519.read_private_key_file(path)

    def test_fr35_5_public_key_must_be_32_bytes(self):
        with self.assertRaisesRegex(ValueError, "32"):
            ed25519.decode_public_key(base64.b64encode(bytes(31)).decode())


@unittest.skipUnless(SPARKLE_DIR, "SPARKLE_DIR names no unpacked Sparkle release")
class AgreesWithSparkle(unittest.TestCase):
    def test_fr35_5_signature_is_the_one_sign_update_makes(self):
        with tempfile.TemporaryDirectory() as scratch:
            key = Path(scratch) / "sparkle.key"
            signing.keygen(key)
            archive = Path(scratch) / "update.zip"
            archive.write_bytes(bytes(range(256)) * 1000)
            ours = signing.sign_archive(archive, key)
            theirs = subprocess.run(
                [str(SPARKLE_DIR / "bin" / "sign_update"), "--ed-key-file", str(key), "-p",
                 str(archive)],
                check=True, text=True, capture_output=True,
            ).stdout.strip()
            self.assertEqual(ours, theirs)
            checked = subprocess.run(
                [str(SPARKLE_DIR / "bin" / "sign_update"), "--ed-key-file", str(key),
                 "--verify", str(archive), ours],
                capture_output=True,
            )
            self.assertEqual(checked.returncode, 0, checked.stderr)


if __name__ == "__main__":
    unittest.main()
