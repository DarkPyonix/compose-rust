//! The AppStream metainfo file: what a store page shows. Flathub builds its listing from
//! this file alone, and `appstreamcli validate` is what decides whether it is accepted.

use crate::metadata::AppMetadata;

/// The file name Flathub expects under `share/metainfo`.
pub fn metainfo_file_name(meta: &AppMetadata) -> String {
    format!("{}.metainfo.xml", meta.id)
}

/// The file name `appimagetool` looks for under `usr/share/metainfo`. It derives the name
/// from the desktop entry's and only knows the older suffix.
pub fn appdata_file_name(meta: &AppMetadata) -> String {
    format!("{}.appdata.xml", meta.id)
}

/// The metainfo XML document.
pub fn metainfo(meta: &AppMetadata) -> String {
    let mut x = String::new();
    x.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    x.push_str("<component type=\"desktop-application\">\n");
    element(&mut x, 1, "id", &meta.id);
    element(&mut x, 1, "metadata_license", &meta.metadata_license);
    element(&mut x, 1, "project_license", &meta.license);
    element(&mut x, 1, "name", &meta.name);
    element(&mut x, 1, "summary", &meta.summary);
    x.push_str(&format!(
        "  <developer id=\"{}\">\n",
        escape(&meta.developer_id)
    ));
    element(&mut x, 2, "name", &meta.developer_name);
    x.push_str("  </developer>\n");

    x.push_str("  <description>\n");
    for paragraph in &meta.description {
        element(&mut x, 2, "p", paragraph);
    }
    x.push_str("  </description>\n");

    x.push_str(&format!(
        "  <launchable type=\"desktop-id\">{}.desktop</launchable>\n",
        escape(&meta.id)
    ));
    for (kind, url) in [
        ("homepage", Some(&meta.homepage)),
        ("bugtracker", meta.bugtracker.as_ref()),
        ("vcs-browser", meta.vcs_browser.as_ref()),
        ("donation", meta.donation.as_ref()),
    ] {
        if let Some(url) = url {
            x.push_str(&format!("  <url type=\"{kind}\">{}</url>\n", escape(url)));
        }
    }

    if !meta.keywords.is_empty() {
        x.push_str("  <keywords>\n");
        for keyword in &meta.keywords {
            element(&mut x, 2, "keyword", keyword);
        }
        x.push_str("  </keywords>\n");
    }

    if !meta.screenshots.is_empty() {
        x.push_str("  <screenshots>\n");
        for (index, shot) in meta.screenshots.iter().enumerate() {
            if index == 0 {
                x.push_str("    <screenshot type=\"default\">\n");
            } else {
                x.push_str("    <screenshot>\n");
            }
            element(&mut x, 3, "image", &shot.url);
            if let Some(caption) = &shot.caption {
                element(&mut x, 3, "caption", caption);
            }
            x.push_str("    </screenshot>\n");
        }
        x.push_str("  </screenshots>\n");
    }

    if meta.brand_light.is_some() || meta.brand_dark.is_some() {
        x.push_str("  <branding>\n");
        for (scheme, colour) in [
            ("light", meta.brand_light.as_ref()),
            ("dark", meta.brand_dark.as_ref()),
        ] {
            if let Some(colour) = colour {
                x.push_str(&format!(
                    "    <color type=\"primary\" scheme_preference=\"{scheme}\">{}</color>\n",
                    escape(colour)
                ));
            }
        }
        x.push_str("  </branding>\n");
    }

    if meta.content_rating.is_empty() {
        x.push_str("  <content_rating type=\"oars-1.1\"/>\n");
    } else {
        x.push_str("  <content_rating type=\"oars-1.1\">\n");
        for (attribute, value) in &meta.content_rating {
            x.push_str(&format!(
                "    <content_attribute id=\"{}\">{}</content_attribute>\n",
                escape(attribute),
                escape(value)
            ));
        }
        x.push_str("  </content_rating>\n");
    }

    x.push_str("  <provides>\n");
    element(&mut x, 2, "binary", &meta.exec);
    x.push_str("  </provides>\n");

    x.push_str("  <releases>\n");
    let release = format!(
        "    <release version=\"{}\" date=\"{}\"",
        escape(&meta.version),
        escape(&meta.date)
    );
    if meta.release_notes.is_empty() {
        x.push_str(&release);
        x.push_str("/>\n");
    } else {
        x.push_str(&release);
        x.push_str(">\n      <description>\n");
        for note in &meta.release_notes {
            element(&mut x, 4, "p", note);
        }
        x.push_str("      </description>\n    </release>\n");
    }
    x.push_str("  </releases>\n");
    x.push_str("</component>\n");
    x
}

fn element(x: &mut String, depth: usize, name: &str, text: &str) {
    for _ in 0..depth {
        x.push_str("  ");
    }
    x.push_str(&format!("<{name}>{}</{name}>\n", escape(text)));
}

/// Escapes text and attribute values.
pub fn escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            c => out.push(c),
        }
    }
    out
}
