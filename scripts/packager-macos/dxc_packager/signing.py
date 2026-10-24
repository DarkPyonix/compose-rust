"""EdDSA signatures over update archives, and the key pair they are made with.

The private key is a file holding the base64 of a 32 byte seed, which is the form Sparkle's
own `generate_keys -x` exports and `sign_update --ed-key-file` reads. It is kept out of
any keychain on purpose: a CI job gets it from a secret, writes it to a file only it can
read, and nothing about the key outlives the job.
"""

import base64
import os
import subprocess
from pathlib import Path

from . import ed25519
from .metadata import PackagingError


def keygen(private_key_path):
    """Write a new private key to `private_key_path` and return the public key (base64)."""
    path = Path(private_key_path)
    if path.exists():
        raise PackagingError(
            f"{path} already exists. A new key would orphan every installed copy that "
            f"trusts the old one, so this never overwrites a key."
        )
    seed = os.urandom(32)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as handle:
        handle.write(base64.b64encode(seed).decode() + "\n")
    return base64.b64encode(ed25519.public_key(seed)).decode()


def public_key_of(private_key_path):
    private = ed25519.read_private_key_file(private_key_path)
    return base64.b64encode(ed25519.public_key(private)).decode()


def _sign_with_sparkle(archive, private_key_path, sign_update):
    try:
        result = subprocess.run(
            [str(sign_update), "--ed-key-file", str(private_key_path), "-p", str(archive)],
            check=True,
            text=True,
            capture_output=True,
        )
    except subprocess.CalledProcessError as error:
        raise PackagingError(f"sign_update failed: {error.stdout}{error.stderr}") from error
    return result.stdout.strip()


def sign_archive(archive, private_key_path, *, sign_update=None, public_key=None):
    """The base64 EdDSA signature of `archive`, checked against `public_key` if given.

    With `sign_update` (the path of Sparkle's tool) Sparkle signs, and this module only
    checks the result. Without it the built-in implementation signs, for machines that
    have no Sparkle, such as a Linux runner publishing the feed.

    Checking against the public key the application carries is the point of the
    argument: a feed signed with a different key from the one in the installed
    application's Info.plist is a feed every installed copy refuses, and it is better
    found here than by every user at once.
    """
    data = Path(archive).read_bytes()
    if sign_update is not None:
        signature = _sign_with_sparkle(archive, private_key_path, sign_update)
    else:
        private = ed25519.read_private_key_file(private_key_path)
        signature = base64.b64encode(ed25519.sign(private, data)).decode()
    if public_key is not None:
        raw_public = ed25519.decode_public_key(public_key)
        if not ed25519.verify(raw_public, data, base64.b64decode(signature)):
            raise PackagingError(
                f"the signature of {archive} does not verify under SUPublicEDKey "
                f"{public_key}. The private key is not the one the application was built "
                f"to trust, and every installed copy would refuse this update."
            )
    return signature
