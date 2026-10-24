#!/usr/bin/env python3
"""Serves a directory over HTTP with byte ranges, for testing an App Installer feed.

Windows downloads a bundle named by an App Installer feed in pieces and refuses a server
that cannot answer a Range request (0x80D05011), which Python's own http.server cannot.
This adds single-range support and the media types App Installer expects, and nothing
else. It is for a test on one machine, not for publishing.

Usage: serve_feed.py <directory> <port>
"""

import http.server
import os
import re
import sys


class RangeHandler(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".appinstaller": "application/appinstaller",
        ".msix": "application/msix",
        ".msixbundle": "application/msixbundle",
    }

    def send_head(self):
        header = self.headers.get("Range")
        path = self.translate_path(self.path)
        if not header or not os.path.isfile(path):
            return super().send_head()
        match = re.fullmatch(r"bytes=(\d*)-(\d*)", header.strip())
        size = os.path.getsize(path)
        if not match or (match.group(1) == "" and match.group(2) == ""):
            self.send_error(416)
            return None
        if match.group(1) == "":
            start = max(0, size - int(match.group(2)))
            end = size - 1
        else:
            start = int(match.group(1))
            end = int(match.group(2)) if match.group(2) else size - 1
        end = min(end, size - 1)
        if start > end:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.end_headers()
            return None
        f = open(path, "rb")
        f.seek(start)
        self._remaining = end - start + 1
        self.send_response(206)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(self._remaining))
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()
        return f

    def copyfile(self, source, outputfile):
        remaining = getattr(self, "_remaining", None)
        if remaining is None:
            return super().copyfile(source, outputfile)
        self._remaining = None
        while remaining > 0:
            chunk = source.read(min(65536, remaining))
            if not chunk:
                break
            outputfile.write(chunk)
            remaining -= len(chunk)

    def end_headers(self):
        if not self.headers.get("Range"):
            self.send_header("Accept-Ranges", "bytes")
        super().end_headers()


def main():
    directory, port = sys.argv[1], int(sys.argv[2])
    handler = lambda *a, **kw: RangeHandler(*a, directory=directory, **kw)  # noqa: E731
    http.server.ThreadingHTTPServer(("127.0.0.1", port), handler).serve_forever()


if __name__ == "__main__":
    main()
