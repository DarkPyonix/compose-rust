//! The AppStream metainfo file: what a store page shows. Flathub builds its listing from
//! this file alone, and `appstreamcli validate` is what decides whether it is accepted.

use crate::metadata::AppMetadata;
use crate::templates;

/// The file name Flathub expects under `share/metainfo`.
pub fn metainfo_file_name(meta: &AppMetadata) -> String {
    format!("{}.metainfo.xml", meta.id)
}

/// The file name `appimagetool` looks for under `usr/share/metainfo`. It derives the name
/// from the desktop entry's and only knows the older suffix.
pub fn appdata_file_name(meta: &AppMetadata) -> String {
    format!("{}.appdata.xml", meta.id)
}

/// The metainfo XML document: the fork's `app.metainfo.xml.in` filled from `meta`, then
/// what the template does not have. The links, keywords, screenshots and branding go
/// before the content rating; more description paragraphs after the first; the content
/// rating's attributes and the release notes inside the elements the template leaves empty.
pub fn metainfo(meta: &AppMetadata) -> String {
    metainfo_with(templates::METAINFO, meta)
}

/// [`metainfo`] from a given template, so a test can show the output follows it.
pub fn metainfo_with(template: &str, meta: &AppMetadata) -> String {
    let first = meta.description.first().map(String::as_str).unwrap_or(meta.summary.as_str());
    let mut x = templates::render(
        template,
        &[
            ("identifier", &escape(&meta.id)),
            ("metadata_license", &escape(&meta.metadata_license)),
            ("license", &escape(&meta.license)),
            ("name", &escape(&meta.name)),
            ("summary", &escape(&meta.summary)),
            ("developer_id", &escape(&meta.developer_id)),
            ("publisher", &escape(&meta.developer_name)),
            ("description", &escape(first)),
            ("executable", &escape(&meta.exec)),
            ("version", &escape(&meta.version)),
            ("date", &escape(&meta.date)),
        ],
    );

    let mut more = String::new();
    for paragraph in meta.description.iter().skip(1) {
        element(&mut more, 2, "p", paragraph);
    }
    insert_before(&mut x, "  </description>\n", &more);

    let mut extra = String::new();
    for (kind, url) in [
        ("homepage", Some(&meta.homepage)),
        ("bugtracker", meta.bugtracker.as_ref()),
        ("vcs-browser", meta.vcs_browser.as_ref()),
        ("donation", meta.donation.as_ref()),
    ] {
        if let Some(url) = url {
            extra.push_str(&format!("  <url type=\"{kind}\">{}</url>\n", escape(url)));
        }
    }
    if !meta.keywords.is_empty() {
        extra.push_str("  <keywords>\n");
        for keyword in &meta.keywords {
            element(&mut extra, 2, "keyword", keyword);
        }
        extra.push_str("  </keywords>\n");
    }
    if !meta.screenshots.is_empty() {
        extra.push_str("  <screenshots>\n");
        for (index, shot) in meta.screenshots.iter().enumerate() {
            if index == 0 {
                extra.push_str("    <screenshot type=\"default\">\n");
            } else {
                extra.push_str("    <screenshot>\n");
            }
            element(&mut extra, 3, "image", &shot.url);
            if let Some(caption) = &shot.caption {
                element(&mut extra, 3, "caption", caption);
            }
            extra.push_str("    </screenshot>\n");
        }
        extra.push_str("  </screenshots>\n");
    }
    if meta.brand_light.is_some() || meta.brand_dark.is_some() {
        extra.push_str("  <branding>\n");
        for (scheme, colour) in [
            ("light", meta.brand_light.as_ref()),
            ("dark", meta.brand_dark.as_ref()),
        ] {
            if let Some(colour) = colour {
                extra.push_str(&format!(
                    "    <color type=\"primary\" scheme_preference=\"{scheme}\">{}</color>\n",
                    escape(colour)
                ));
            }
        }
        extra.push_str("  </branding>\n");
    }
    insert_before(&mut x, "  <content_rating ", &extra);

    if !meta.content_rating.is_empty() {
        let mut children = String::new();
        for (attribute, value) in &meta.content_rating {
            children.push_str(&format!(
                "    <content_attribute id=\"{}\">{}</content_attribute>\n",
                escape(attribute),
                escape(value)
            ));
        }
        fill_empty(&mut x, "  <content_rating ", "  </content_rating>\n", &children);
    }
    if !meta.release_notes.is_empty() {
        let mut children = String::from("      <description>\n");
        for note in &meta.release_notes {
            element(&mut children, 4, "p", note);
        }
        children.push_str("      </description>\n");
        fill_empty(&mut x, "    <release ", "    </release>\n", &children);
    }
    x
}

/// Puts `text` before the line that starts with `anchor`. The anchor is the template's, so
/// a template without it is out of step with this program.
fn insert_before(doc: &mut String, anchor: &str, text: &str) {
    if text.is_empty() {
        return;
    }
    let at = doc
        .find(anchor)
        .unwrap_or_else(|| panic!("the metainfo template has no {anchor:?}"));
    doc.insert_str(at, text);
}

/// Opens the empty element on the line starting with `anchor` (`<name .../>`) and puts
/// `children` and `close` after it.
fn fill_empty(doc: &mut String, anchor: &str, close: &str, children: &str) {
    let start = doc
        .find(anchor)
        .unwrap_or_else(|| panic!("the metainfo template has no {anchor:?}"));
    let end = start + doc[start..].find('\n').expect("a template line ends");
    let line = &doc[start..end];
    let open = line
        .strip_suffix("/>")
        .unwrap_or_else(|| panic!("the metainfo template's {anchor:?} is not empty"));
    let replacement = format!("{open}>\n{children}{}", close.trim_end_matches('\n'));
    doc.replace_range(start..end, &replacement);
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
