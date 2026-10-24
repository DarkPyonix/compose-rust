"""Ed25519 as RFC 8032 writes it, for the keys Sparkle uses to sign updates.

Sparkle keeps its private key as the base64 of a 32 byte seed, and puts the base64 of the
32 byte public key in the application's Info.plist as SUPublicEDKey. Those two numbers are
the whole of the trust an installed application places in an update, so the packager has
to be able to answer three questions without a keychain: which public key belongs to this
private key, does this signature verify under the key the application carries, and, on a
machine without Sparkle's tools (Linux CI), what is the signature.

This is the reference algorithm and not a hardened one: its arithmetic takes time that
depends on the secret. Signing on a shared machine should go through Sparkle's
sign_update, which the packager prefers whenever it is given one; this module then only
checks that what sign_update produced verifies under the application's public key.
"""

import base64
import hashlib

_P = 2**255 - 19
_L = 2**252 + 27742317777372353535851937790883648493
_D = -121665 * pow(121666, _P - 2, _P) % _P
_SQRT_M1 = pow(2, (_P - 1) // 4, _P)


def _sha512(data):
    return hashlib.sha512(data).digest()


def _sha512_mod_l(data):
    return int.from_bytes(_sha512(data), "little") % _L


def _add(a, b):
    x1, y1, z1, t1 = a
    x2, y2, z2, t2 = b
    pa = (y1 - x1) * (y2 - x2) % _P
    pb = (y1 + x1) * (y2 + x2) % _P
    pc = 2 * t1 * t2 * _D % _P
    pd = 2 * z1 * z2 % _P
    e, f, g, h = pb - pa, pd - pc, pd + pc, pb + pa
    return (e * f % _P, g * h % _P, f * g % _P, e * h % _P)


def _mul(scalar, point):
    result = (0, 1, 1, 0)
    while scalar > 0:
        if scalar & 1:
            result = _add(result, point)
        point = _add(point, point)
        scalar >>= 1
    return result


def _equal(a, b):
    return (a[0] * b[2] - b[0] * a[2]) % _P == 0 and (a[1] * b[2] - b[1] * a[2]) % _P == 0


def _recover_x(y, sign):
    if y >= _P:
        return None
    x2 = (y * y - 1) * pow(_D * y * y + 1, _P - 2, _P)
    if x2 == 0:
        return None if sign else 0
    x = pow(x2, (_P + 3) // 8, _P)
    if (x * x - x2) % _P != 0:
        x = x * _SQRT_M1 % _P
    if (x * x - x2) % _P != 0:
        return None
    if (x & 1) != sign:
        x = _P - x
    return x


_GY = 4 * pow(5, _P - 2, _P) % _P
_GX = _recover_x(_GY, 0)
_G = (_GX, _GY, 1, _GX * _GY % _P)


def _compress(point):
    zinv = pow(point[2], _P - 2, _P)
    x = point[0] * zinv % _P
    y = point[1] * zinv % _P
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


def _decompress(data):
    if len(data) != 32:
        return None
    y = int.from_bytes(data, "little")
    sign = y >> 255
    y &= (1 << 255) - 1
    x = _recover_x(y, sign)
    if x is None:
        return None
    return (x, y, 1, x * y % _P)


def _expand_seed(seed):
    digest = _sha512(seed)
    return _clamp(digest[:32]), digest[32:]


def _clamp(scalar_bytes):
    a = int.from_bytes(scalar_bytes, "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    return a


class InvalidKey(ValueError):
    """A private key that is not one Sparkle could have written."""


def _expanded_from_private(private):
    """The secret scalar and nonce prefix for a Sparkle 2 private key.

    Sparkle 2's generate_keys exports a 32 byte seed, and that is the form sign_update was
    checked against. Keys from Sparkle 1 are longer and carry the public key beside the
    secret; generate_keys can import one (`-f`) and export it again as a seed (`-x`).
    """
    if len(private) == 32:
        return _expand_seed(private)
    raise InvalidKey(
        f"a Sparkle 2 private key is a 32 byte seed, and this one is {len(private)} bytes. "
        f"A key from Sparkle 1 can be converted by importing it with `generate_keys -f` "
        f"and exporting it with `generate_keys -x`."
    )


def public_key(private):
    scalar, _ = _expanded_from_private(private)
    return _compress(_mul(scalar, _G))


def sign(private, message):
    scalar, prefix = _expanded_from_private(private)
    public = _compress(_mul(scalar, _G))
    r = _sha512_mod_l(prefix + message)
    encoded_r = _compress(_mul(r, _G))
    h = _sha512_mod_l(encoded_r + public + message)
    s = (r + h * scalar) % _L
    return encoded_r + int.to_bytes(s, 32, "little")


def verify(public, message, signature):
    if len(public) != 32 or len(signature) != 64:
        return False
    point_a = _decompress(public)
    if point_a is None:
        return False
    encoded_r = signature[:32]
    point_r = _decompress(encoded_r)
    if point_r is None:
        return False
    s = int.from_bytes(signature[32:], "little")
    if s >= _L:
        return False
    h = _sha512_mod_l(encoded_r + public + message)
    return _equal(_mul(s, _G), _add(point_r, _mul(h, point_a)))


def read_private_key_file(path):
    """The key bytes from a file holding the base64 Sparkle's tools write."""
    with open(path, "rb") as handle:
        text = handle.read().strip()
    try:
        private = base64.b64decode(text, validate=True)
    except ValueError as error:
        raise InvalidKey(f"{path} is not base64: {error}") from error
    _expanded_from_private(private)
    return private


def decode_public_key(text):
    """The 32 bytes behind an SUPublicEDKey value, or a ValueError saying what is wrong."""
    try:
        raw = base64.b64decode(text, validate=True)
    except ValueError as error:
        raise ValueError(f"SUPublicEDKey {text!r} is not base64: {error}") from error
    if len(raw) != 32:
        raise ValueError(
            f"SUPublicEDKey decodes to {len(raw)} bytes; an Ed25519 public key is 32. "
            f"It is the line generate_keys prints, or `package-macos keygen` writes."
        )
    return raw
