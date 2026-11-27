"""The packaging templates this packager fills.

They are the Compose fork's, copied with a pin to scripts/packaging-templates/ (see its
README). A template is filled by putting each value, escaped for the format, where its
`{{placeholder}}` is; a placeholder with no value is an error, so a template that gains one
cannot be filled half way without anyone noticing. The packager then only adds to what the
template rendered.
"""

import re
from pathlib import Path

DIRECTORY = Path(__file__).resolve().parents[2] / "packaging-templates" / "templates"
PLACEHOLDER = re.compile(r"\{\{([a-z0-9_]+)\}\}")


class TemplateError(Exception):
    pass


def xml_escape(text):
    return (str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&apos;"))


def render(name, values, escape):
    """The template `name` with every placeholder filled from `values`."""
    path = DIRECTORY / name
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise TemplateError(f"there is no packaging template {path}") from None
    missing = sorted({key for key in PLACEHOLDER.findall(text) if key not in values})
    if missing:
        raise TemplateError(f"{name} needs a value for {', '.join('{{' + key + '}}' for key in missing)}")
    return PLACEHOLDER.sub(lambda match: escape(values[match.group(1)]), text)
