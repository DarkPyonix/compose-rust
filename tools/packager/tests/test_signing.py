"""Keys and archive signatures for the Sparkle channel."""

import base64
import os
import stat
import tempfile
import unittest
from pathlib import Path

from support import SPARKLE_DIR

from dxc_packager import ed25519, signing
from dxc_packager.metadata import PackagingError


class Signing(unittest.TestCase):
    def setUp(self):
        self.scratch = Path(tempfile.mkdtemp(prefix="packager-signing-"))
        self.key = self.scratch / "keys" / "sparkle.key"
        self.public = signing.keygen(self.key)
        self.archive = self.scratch / "Demo-2.zip"
        self.archive.write_bytes(os.urandom(4096))

    def test_fr35_5_keygen_writes_a_private_file_and_prints_the_matching_public_key(self):
        self.assertEqual(stat.S_IMODE(self.key.stat().st_mode), 0o600)
        self.assertEqual(len(base64.b64decode(self.key.read_text())), 32)
        self.assertEqual(signing.public_key_of(self.key), self.public)
        self.assertEqual(len(ed25519.decode_public_key(self.public)), 32)

    def test_fr35_5_keygen_never_overwrites_a_key(self):
        before = self.key.read_bytes()
        with self.assertRaisesRegex(PackagingError, "never overwrites"):
            signing.keygen(self.key)
        self.assertEqual(self.key.read_bytes(), before)

    def test_fr35_5_signature_verifies_under_the_public_key(self):
        signature = signing.sign_archive(self.archive, self.key, public_key=self.public)
        self.assertTrue(
            ed25519.verify(
                base64.b64decode(self.public),
                self.archive.read_bytes(),
                base64.b64decode(signature),
            )
        )

    def test_fr35_5_signing_with_a_key_the_app_does_not_trust_is_refused(self):
        other = signing.keygen(self.scratch / "other.key")
        with self.assertRaisesRegex(PackagingError, "every installed copy would refuse"):
            signing.sign_archive(self.archive, self.key, public_key=other)

    @unittest.skipUnless(SPARKLE_DIR, "SPARKLE_DIR names no unpacked Sparkle release")
    def test_fr35_5_sign_update_is_used_when_given_and_checked(self):
        signature = signing.sign_archive(
            self.archive, self.key, sign_update=SPARKLE_DIR / "bin" / "sign_update",
            public_key=self.public,
        )
        self.assertEqual(signature, signing.sign_archive(self.archive, self.key))


if __name__ == "__main__":
    unittest.main()
