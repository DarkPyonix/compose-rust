//! The desktop entry: how a menu, a launcher and a store's install button start the
//! application.

use crate::metadata::AppMetadata;

/// The entry's file name. Flatpak and AppStream both require it to be the application
/// identifier.
pub fn desktop_file_name(meta: &AppMetadata) -> String {
    format!("{}.desktop", meta.id)
}

/// The `[Desktop Entry]` group.
///
/// `Exec` is the bare executable name. Inside a Flatpak that name is on `PATH`; inside an
/// AppImage the runtime starts `AppRun` and never reads `Exec`, and the desktop
/// integration tools rewrite it to the AppImage's path when they install the entry.
pub fn desktop_entry(meta: &AppMetadata) -> String {
    let mut out = String::from("[Desktop Entry]\n");
    let mut line = |key: &str, value: &str| {
        out.push_str(key);
        out.push('=');
        out.push_str(value);
        out.push('\n');
    };
    line("Type", "Application");
    line("Version", "1.5");
    line("Name", &escape(&meta.name));
    line("Comment", &escape(&meta.summary));
    line("Exec", &meta.exec);
    line("Icon", &meta.id);
    line("Terminal", "false");
    line("Categories", &list(&meta.categories));
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
