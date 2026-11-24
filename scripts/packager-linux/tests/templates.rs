//! The desktop entry, the metainfo and the Flatpak manifest are the fork's templates
//! (scripts/packaging-templates/) filled from the application, and the packager only adds
//! to what a template renders.

use packager_linux::appstream::{metainfo, metainfo_with};
use packager_linux::desktop::{desktop_entry, desktop_entry_with};
use packager_linux::flatpak::{FlatpakOptions, Json, Payload, SourceRef, manifest, manifest_with};
use packager_linux::icons::{Icon, IconKind};
use packager_linux::templates::{self, json_holds, parse_json};
use packager_linux::{AppMetadata, BuildFacts};

const DIOXUS_TOML: &str = r##"
[application]
name = "Calculator"

[bundle]
identifier = "dev.example.Calculator"
publisher = "Example & Sons"
short_description = "A calculator built with compose-rust."
long_description = """
Dense button grids.

Memory keys that <really> remember."""

[linux]
categories = ["Utility", "Calculator"]
keywords = ["math"]
flatpak_permissions = ["--share=network"]

[linux.store]
exec = "sample-calculator"
license = "Apache-2.0"
homepage = "https://example.dev/calculator"
developer_id = "dev.example"
brand_light = "#e8f0ff"
screenshots = [{ url = "https://example.dev/shot.png", caption = "The keypad" }]
release_notes = ["Faster."]

[linux.store.content_rating]
social-chat = "none"
"##;

fn meta() -> AppMetadata {
    let facts = BuildFacts {
        version: Some("1.2.3".into()),
        date: "2026-10-03".into(),
        exec: None,
    };
    AppMetadata::resolve(DIOXUS_TOML, &[], &facts).unwrap()
}

fn icons() -> Vec<Icon> {
    vec![Icon {
        source: "icon.png".into(),
        kind: IconKind::Png(256),
    }]
}

fn prebuilt() -> Payload {
    Payload::Prebuilt {
        archives: vec![SourceRef::Url {
            url: "https://example.dev/calculator-linux-x64.tar.gz".into(),
            sha256: "ab".repeat(32),
            arch: Some("x86_64".into()),
        }],
    }
}

fn from_source() -> Payload {
    Payload::FromSource {
        source: SourceRef::Path("source.tar.gz".into()),
        cargo_sources: "cargo-sources.json".into(),
        package: "sample-calculator".into(),
        bin: "sample-calculator".into(),
        cargo_args: vec![],
        renderer: vec![SourceRef::Url {
            url: "https://example.dev/renderer.tar.gz".into(),
            sha256: "cd".repeat(32),
            arch: None,
        }],
    }
}

/// Whether the lines of `template` appear in `output` in the same order.
fn lines_in_order(template: &str, output: &str) -> Result<(), String> {
    let mut have = output.lines();
    for line in template.lines() {
        if !have.any(|l| l == line) {
            return Err(format!("{line:?} is missing or out of order in\n{output}"));
        }
    }
    Ok(())
}

#[test]
fn fr22_6_11_the_desktop_entry_is_its_template_then_additions() {
    let meta = meta();
    let entry = desktop_entry(&meta);
    let template = templates::render(
        templates::DESKTOP,
        &[
            ("name", "Calculator"),
            ("summary", "A calculator built with compose-rust"),
            ("executable", "sample-calculator"),
            ("identifier", "dev.example.Calculator"),
            ("category_xdg", "Utility;Calculator;"),
        ],
    );
    assert!(entry.starts_with(&template), "{entry}");
    assert_eq!(&entry[template.len()..], "Keywords=math;\n");
}

#[test]
fn fr22_6_11_the_desktop_entry_follows_its_template() {
    let template = templates::DESKTOP.replace("Version=1.5", "Version=1.6") + "X-From-Template=true\n";
    let entry = desktop_entry_with(&template, &meta());
    assert!(entry.contains("Version=1.6\n"), "{entry}");
    assert!(entry.contains("X-From-Template=true\nKeywords=math;\n"), "{entry}");
}

#[test]
fn fr22_6_11_the_metainfo_keeps_every_line_its_template_renders() {
    let meta = meta();
    let xml = metainfo(&meta);
    let template = templates::render(
        templates::METAINFO,
        &[
            ("identifier", "dev.example.Calculator"),
            ("metadata_license", "CC0-1.0"),
            ("license", "Apache-2.0"),
            ("name", "Calculator"),
            ("summary", "A calculator built with compose-rust"),
            ("developer_id", "dev.example"),
            ("publisher", "Example &amp; Sons"),
            ("description", "Dense button grids."),
            ("executable", "sample-calculator"),
            ("version", "1.2.3"),
            ("date", "2026-10-03"),
        ],
    );
    // The two elements the template leaves empty are opened for their children; every
    // other line is the template's, in its order.
    let fixed: String = template
        .lines()
        .filter(|l| !l.contains("<content_rating ") && !l.contains("<release "))
        .map(|l| format!("{l}\n"))
        .collect();
    lines_in_order(&fixed, &xml).unwrap();
    for added in [
        "    <p>Memory keys that &lt;really&gt; remember.</p>",
        "  <url type=\"homepage\">https://example.dev/calculator</url>",
        "  <content_rating type=\"oars-1.1\">",
        "    <content_attribute id=\"social-chat\">none</content_attribute>",
        "    <release version=\"1.2.3\" date=\"2026-10-03\">",
        "        <p>Faster.</p>",
    ] {
        assert!(xml.lines().any(|l| l == added), "missing {added:?} in\n{xml}");
    }
}

#[test]
fn fr22_6_11_the_metainfo_follows_its_template() {
    let template = templates::METAINFO.replace(
        "  <provides>",
        "  <categories>\n    <category>FromTemplate</category>\n  </categories>\n  <provides>",
    );
    let xml = metainfo_with(&template, &meta());
    assert!(xml.contains("    <category>FromTemplate</category>\n"), "{xml}");
}

#[test]
fn fr22_6_11_the_flatpak_manifest_holds_its_template() {
    let meta = meta();
    let icons = icons();
    let template = parse_json(&templates::render(
        templates::FLATPAK,
        &[
            ("identifier", "dev.example.Calculator"),
            ("runtime_version", "25.08"),
            ("executable", "sample-calculator"),
        ],
    ))
    .unwrap();
    for (payload, wayland) in [(prebuilt(), false), (from_source(), true)] {
        let text = manifest(
            &meta,
            &FlatpakOptions {
                payload,
                icons: &icons,
                wayland,
            },
        );
        let output = parse_json(&text).unwrap();
        json_holds(&template, &output, "manifest").unwrap();
    }
}

#[test]
fn fr22_6_11_the_flatpak_manifest_follows_its_template() {
    let template = templates::FLATPAK.replace(
        "\"--device=dri\"",
        "\"--device=dri\",\n    \"--talk-name=org.freedesktop.Notifications\"",
    );
    let icons = icons();
    let text = manifest_with(
        &template,
        &meta(),
        &FlatpakOptions {
            payload: prebuilt(),
            icons: &icons,
            wayland: false,
        },
    );
    let output = parse_json(&text).unwrap();
    let Json::Object(top) = output else { panic!() };
    let args = &top.iter().find(|(k, _)| k == "finish-args").unwrap().1;
    assert_eq!(
        args.render(0),
        Json::Array(
            [
                "--share=ipc",
                "--socket=x11",
                "--device=dri",
                "--talk-name=org.freedesktop.Notifications",
                "--share=network"
            ]
            .iter()
            .map(|a| Json::str(a))
            .collect()
        )
        .render(0)
    );
}

#[test]
#[should_panic(expected = "needs a value for {{nope}}")]
fn fr22_6_11_an_unfilled_placeholder_stops_the_packager() {
    desktop_entry_with("[Desktop Entry]\nName={{nope}}\n", &meta());
}

#[test]
fn fr22_6_11_a_change_the_packager_did_not_make_is_reported() {
    let template = parse_json(r#"{"a": ["x", "y"], "b": {"c": "d"}}"#).unwrap();
    let holds = parse_json(r#"{"a": ["x", "added", "y"], "b": {"c": "d", "e": "f"}, "g": "h"}"#).unwrap();
    json_holds(&template, &holds, "t").unwrap();
    let reordered = parse_json(r#"{"a": ["y", "x"], "b": {"c": "d"}}"#).unwrap();
    assert!(json_holds(&template, &reordered, "t").unwrap_err().contains("t.a[1]"));
    let changed = parse_json(r#"{"a": ["x", "y"], "b": {"c": "changed"}}"#).unwrap();
    assert!(json_holds(&template, &changed, "t").unwrap_err().contains("t.b.c"));
}

#[test]
fn fr22_6_11_template_json_reads_back_what_the_packager_writes() {
    let value = Json::Object(vec![(
        "k\"ey".into(),
        Json::Array(vec![Json::str("a\\b\n\u{1}"), Json::Array(vec![]), Json::Object(vec![])]),
    )]);
    assert_eq!(parse_json(&value.render(0)).unwrap(), value);
    assert!(parse_json("{\"a\": 1}").is_err(), "numbers are not in any template");
}
