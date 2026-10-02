"""Shared helpers for the packager tests."""

import os
import sys
import tempfile
from pathlib import Path

PACKAGER = Path(__file__).resolve().parents[1]
REPOSITORY = PACKAGER.parents[1]
sys.path.insert(0, str(PACKAGER))

# Where Sparkle's release archive was unpacked, when the run has one. Tests that compare
# against Sparkle's own tools are skipped without it rather than downloading it.
SPARKLE_DIR = Path(os.environ["SPARKLE_DIR"]) if os.environ.get("SPARKLE_DIR") else None


def project(cargo, dioxus=None, files=()):
    """A throwaway project directory with the given Cargo.toml and Dioxus.toml text."""
    directory = Path(tempfile.mkdtemp(prefix="packager-test-"))
    (directory / "Cargo.toml").write_text(cargo)
    if dioxus is not None:
        (directory / "Dioxus.toml").write_text(dioxus)
    for relative, data in files:
        path = directory / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    return directory


CARGO = """
[package]
name = "sample-demo"
version = "1.4.2"
"""

DIOXUS = """
[application]
name = "Demo"
asset_dir = "assets"

[bundle]
identifier = "dev.example.demo"
publisher = "Example"
category = "DeveloperTool"
short_description = "A demonstration."

[macos]
bundle_name = "Demo App"
"""
