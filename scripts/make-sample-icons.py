#!/usr/bin/env python3
"""Draws each sample's window icon.

A window with no picture of its own wears the toolkit's, which on Windows is the Java
coffee cup. Only one sample named one, so the rest wore the cup, which is what the
application icon requirement exists to stop.

The colours are read off the reference designs rather than chosen here: the notes in
docs/references/design-systems/README.md say what each unified sample's page and accent
are, and the adaptive samples, which have no fixed palette because they follow the
machine, take a neutral mark in the colour their reference screenshots use for the one
thing that stands out.

Marks are drawn from squares, circles and bars only. An icon is read at 16 pixels more
often than at 64, and a drawing that needs detail to be recognised is not one.

Run from the repository root. Writes samples/<name>/assets/icon.png.
"""

import math
import os
import struct
import zlib

SIZE = 64
CORNER = 14


def blend(dst, src, alpha):
    return tuple(round(d + (s - d) * alpha) for d, s in zip(dst, src))


class Canvas:
    def __init__(self):
        self.pixels = [[(0, 0, 0, 0)] * SIZE for _ in range(SIZE)]

    def put(self, x, y, colour, alpha=1.0):
        if not (0 <= x < SIZE and 0 <= y < SIZE) or alpha <= 0:
            return
        r, g, b = colour
        existing = self.pixels[y][x]
        if existing[3] == 0:
            self.pixels[y][x] = (r, g, b, round(255 * alpha))
        else:
            mixed = blend(existing[:3], (r, g, b), alpha)
            self.pixels[y][x] = (*mixed, max(existing[3], round(255 * alpha)))

    def coverage(self, x, y, inside):
        """How much of this pixel the shape covers, sampled on a 4 by 4 grid.

        Sampling rather than an exact area, because every shape here is described by a
        predicate and the edges that matter are circles. Sixteen samples is the point
        where a 64 pixel circle stops showing steps.
        """
        hits = 0
        for sy in range(4):
            for sx in range(4):
                if inside(x + (sx + 0.5) / 4, y + (sy + 0.5) / 4):
                    hits += 1
        return hits / 16

    def fill(self, inside, colour):
        for y in range(SIZE):
            for x in range(SIZE):
                a = self.coverage(x, y, inside)
                if a > 0:
                    self.put(x, y, colour, a)


def rounded_square(radius=CORNER):
    def inside(x, y):
        cx = min(max(x, radius), SIZE - radius)
        cy = min(max(y, radius), SIZE - radius)
        return (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2
    return inside


def disc(cx, cy, r):
    return lambda x, y: (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def ring(cx, cy, r, thickness):
    inner = r - thickness
    return lambda x, y: inner * inner <= (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def box(x0, y0, x1, y1):
    return lambda x, y: x0 <= x <= x1 and y0 <= y <= y1


def frame(x0, y0, x1, y1, thickness):
    def inside(x, y):
        if not (x0 <= x <= x1 and y0 <= y <= y1):
            return False
        return not (x0 + thickness <= x <= x1 - thickness and y0 + thickness <= y <= y1 - thickness)
    return inside


def bar(x0, y0, x1, y1, radius):
    """A horizontal or vertical bar with round ends."""
    def inside(x, y):
        cx = min(max(x, x0 + radius), x1 - radius)
        cy = min(max(y, y0 + radius), y1 - radius)
        return (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2
    return inside


def stroke(ax, ay, bx, by, width):
    """A round ended line from a to b."""
    dx, dy = bx - ax, by - ay
    length2 = dx * dx + dy * dy

    def inside(x, y):
        if length2 == 0:
            t = 0.0
        else:
            t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / length2))
        px, py = ax + t * dx, ay + t * dy
        return (x - px) ** 2 + (y - py) ** 2 <= (width / 2) ** 2
    return inside


def write_png(path, pixels):
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(data)
    return len(data)


# Each entry is the sample, its background, its mark colour, and the shapes of the mark.
# The comment on each says where the colour comes from.
def marks():
    white = (255, 255, 255)
    ink = (17, 17, 17)
    return {
        # Adaptive. The reference calculators differ in everything but the one orange
        # column of operators, which both Apple's and Deepin's have.
        "calculator": ((38, 38, 41), (255, 149, 0), [
            disc(23, 23, 5), disc(41, 23, 5), disc(23, 41, 5), disc(41, 41, 5),
        ]),
        # Adaptive. A list with things struck off it is the whole of what this sample is.
        "todo": ((45, 106, 79), white, [
            stroke(19, 33, 28, 43, 7), stroke(28, 43, 46, 22, 7),
        ]),
        # Adaptive. The two reference notepads are a page of ruled lines.
        "notepad": ((240, 179, 35), ink, [
            bar(18, 21, 46, 26, 2.5), bar(18, 30, 46, 35, 2.5), bar(18, 39, 38, 44, 2.5),
        ]),
        # Adaptive, and the reference is Gemini, whose mark is a four pointed star in
        # violet. A star at this size is the shape that survives it.
        "chat": ((109, 90, 207), white, [
            disc(32, 32, 13.5),
        ]),
        # Unified, light, orange as the single accent.
        "podcast": ((255, 107, 53), white, [
            ring(32, 32, 15, 4), disc(32, 32, 5),
        ]),
        # Unified, light, one yellow accent on black ink.
        "store": ((245, 197, 24), ink, [
            box(20, 28, 44, 46), stroke(26, 28, 26, 20, 4), stroke(38, 28, 38, 20, 4),
            stroke(26, 20, 38, 20, 4),
        ]),
        # Unified, light. The sage panel under the dial is the colour this sample is.
        "statistics": ((167, 185, 154), ink, [
            bar(19, 34, 25, 46, 3), bar(29, 24, 35, 46, 3), bar(39, 29, 45, 46, 3),
        ]),
        # Unified, dark. Black under a pastel panel, and the panel is the part with colour.
        "selfcare": ((26, 26, 28), (201, 182, 228), [
            disc(32, 32, 14),
        ]),
        # Unified, dark. Black throughout, with the subject tiles as the only light areas.
        "academic": ((17, 17, 19), white, [
            box(17, 17, 30, 30), box(34, 17, 47, 30), box(17, 34, 30, 47), box(34, 34, 47, 47),
        ]),
        # Unified, light. A cream page with illustrated cards.
        "social": ((240, 230, 210), (58, 74, 92), [
            disc(26, 32, 11), disc(40, 32, 11),
        ]),
    }


def main():
    for name, (background, mark, shapes) in marks().items():
        canvas = Canvas()
        canvas.fill(rounded_square(), background)
        for shape in shapes:
            canvas.fill(shape, mark)
        path = os.path.join("samples", name, "assets", "icon.png")
        size = write_png(path, canvas.pixels)
        print(f"{path} ({size} bytes)")


if __name__ == "__main__":
    main()
