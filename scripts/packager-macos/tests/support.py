"""Shared helpers for the packager tests."""

import sys
import tempfile
from pathlib import Path

PACKAGER = Path(__file__).resolve().parents[1]
REPOSITORY = PACKAGER.parents[1]
sys.path.insert(0, str(PACKAGER))


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
