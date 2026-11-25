//! The desktop entry: how a menu, a launcher and a store's install button start the
//! application.

use crate::metadata::AppMetadata;
use crate::templates;

/// The entry's file name. Flatpak and AppStream both require it to be the application
/// identifier.
pub fn desktop_file_name(meta: &AppMetadata) -> String {
    format!("{}.desktop", meta.id)
}

/// The `[Desktop Entry]` group: the fork's `app.desktop.in` filled from `meta`, then the
/// keys the template does not have.
///
/// `Exec` is the bare executable name. Inside a Flatpak that name is on `PATH`; inside an
/// AppImage the runtime starts `AppRun` and never reads `Exec`, and the desktop
/// integration tools rewrite it to the AppImage's path when they install the entry.
pub fn desktop_entry(meta: &AppMetadata) -> String {
    desktop_entry_with(templates::DESKTOP, meta)
}

/// [`desktop_entry`] from a given template, so a test can show the output follows it.
pub fn desktop_entry_with(template: &str, meta: &AppMetadata) -> String {
    let mut out = templates::render(
        template,
        &[
            ("name", &escape(&meta.name)),
            ("summary", &escape(&meta.summary)),
            ("executable", &meta.exec),
            ("identifier", &meta.id),
            ("category_xdg", &list(&meta.categories)),
        ],
    );
    if !out.ends_with('\n') {
        out.push('\n');
    }
    let mut line = |key: &str, value: &str| {
        out.push_str(key);
        out.push('=');
        out.push_str(value);
        out.push('\n');
    };
    if !meta.keywords.is_empty() {
        line("Keywords", &list(&meta.keywords));
    }
    if !meta.mime_types.is_empty() {
        line("MimeType", &list(&meta.mime_types));
    }
    if let Some(class) = &meta.startup_wm_class {
        line("StartupWMClass", &escape(class));
    }
    out
}

/// Escapes a value the way the desktop entry specification requires.
pub fn escape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for c in value.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            '\r' => out.push_str("\\r"),
            c => out.push(c),
        }
    }
    out
}

/// A `;`-terminated list, with `;` inside an item escaped.
fn list(items: &[String]) -> String {
    items
        .iter()
        .map(|item| format!("{};", escape(item).replace(';', "\\;")))
        .collect()
}
