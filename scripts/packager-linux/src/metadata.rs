//! What a Linux package says about an application, read from its `Dioxus.toml`.
//!
//! The file is dx's, and so are most of the keys read here: `[application] name`, the
//! `[bundle]` table every platform shares, and dx's own `[linux]` table, whose keys
//! (`identifier`, `publisher`, `short_description`, `categories`, `keywords`,
//! `mime_types`, `flatpak_permissions` and the rest) override `[bundle]` for Linux. What
//! only a store listing needs and dx has no key for (a licence, a homepage, screenshots,
//! brand colours, a content rating) goes in a `[linux.store]` table, which dx ignores.
//!
//! A second file passed with `--overlay` has the same shape and is merged over the first,
//! table by table, so a release can add what does not belong in the application's own
//! file (release notes, for one) without editing it.

use std::collections::BTreeMap;
use std::fmt;

use serde::Deserialize;

/// The freedesktop main categories. A desktop entry needs at least one, or menus file it
/// under "Other" and Flathub rejects it.
pub const MAIN_CATEGORIES: &[&str] = &[
    "AudioVideo",
    "Audio",
    "Video",
    "Development",
    "Education",
    "Game",
    "Graphics",
    "Network",
    "Office",
    "Science",
    "Settings",
    "System",
    "Utility",
];

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct DioxusToml {
    application: ApplicationTable,
    bundle: BundleTable,
    linux: DxLinuxTable,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ApplicationTable {
    name: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct BundleTable {
    identifier: Option<String>,
    publisher: Option<String>,
    short_description: Option<String>,
    long_description: Option<String>,
    category: Option<String>,
    icon: Option<Vec<String>>,
    version: Option<String>,
}

/// dx's `[linux]` table: overrides of `[bundle]`, plus desktop entry and Flatpak keys.
#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct DxLinuxTable {
    identifier: Option<String>,
    publisher: Option<String>,
    short_description: Option<String>,
    long_description: Option<String>,
    category: Option<String>,
    icon: Option<Vec<String>>,
    flatpak_permissions: Vec<String>,
    categories: Vec<String>,
    keywords: Vec<String>,
    mime_types: Vec<String>,
    store: StoreTable,
}

/// The `[linux.store]` table: what a store listing needs that dx has no key for. Every
/// key is optional here; [`AppMetadata::resolve`] says which ones a package cannot do
/// without.
#[derive(Debug, Default, Clone, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct StoreTable {
    /// The executable's file name, as installed.
    pub exec: Option<String>,
    /// SPDX expression for the application's own licence.
    pub license: Option<String>,
    /// SPDX expression for the AppStream file itself. Defaults to `CC0-1.0`, which is what
    /// Flathub asks for.
    pub metadata_license: Option<String>,
    pub homepage: Option<String>,
    pub bugtracker: Option<String>,
    pub vcs_browser: Option<String>,
    pub donation: Option<String>,
    /// Reverse-DNS identifier of the developer. Defaults to the first two components of
    /// the application identifier.
    pub developer_id: Option<String>,
    /// Defaults to the publisher.
    pub developer_name: Option<String>,
    pub screenshots: Vec<Screenshot>,
    /// Brand colours for store pages, `#rrggbb`.
    pub brand_light: Option<String>,
    pub brand_dark: Option<String>,
    /// The `WM_CLASS` the window reports, so a desktop can match the window to its entry.
    pub startup_wm_class: Option<String>,
    /// Release notes for this version, one paragraph per entry.
    pub release_notes: Vec<String>,
    /// OARS 1.1 content rating attributes that are not `none`, such as
    /// `social-chat = "intense"`.
    pub content_rating: BTreeMap<String, String>,
    /// Branch of `org.freedesktop.Platform` the Flatpak runs on.
    pub flatpak_runtime_version: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Screenshot {
    pub url: String,
    #[serde(default)]
    pub caption: Option<String>,
}

/// What the command line adds: the facts that are about a build, not about the
/// application.
#[derive(Debug, Default, Clone)]
pub struct BuildFacts {
    /// Overrides `[bundle] version`.
    pub version: Option<String>,
    /// `YYYY-MM-DD`.
    pub date: String,
    /// Overrides `[linux] exec`.
    pub exec: Option<String>,
}

/// Everything a desktop entry, an AppStream file, an AppDir and a Flatpak manifest need,
/// checked.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppMetadata {
    pub id: String,
    pub name: String,
    pub summary: String,
    pub description: Vec<String>,
    pub exec: String,
    pub version: String,
    pub date: String,
    pub license: String,
    pub metadata_license: String,
    pub homepage: String,
    pub bugtracker: Option<String>,
    pub vcs_browser: Option<String>,
    pub donation: Option<String>,
    pub developer_id: String,
    pub developer_name: String,
    pub categories: Vec<String>,
    pub keywords: Vec<String>,
    pub mime_types: Vec<String>,
    /// Icon paths as the file lists them, relative to its directory.
    pub icons: Vec<String>,
    pub screenshots: Vec<Screenshot>,
    pub brand_light: Option<String>,
    pub brand_dark: Option<String>,
    pub startup_wm_class: Option<String>,
    pub release_notes: Vec<String>,
    pub content_rating: BTreeMap<String, String>,
    pub runtime_version: Option<String>,
    pub finish_args: Vec<String>,
}

/// Why metadata could not be produced. Each one names the key to set.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetadataError(pub String);

impl fmt::Display for MetadataError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for MetadataError {}

fn error<T>(message: impl Into<String>) -> Result<T, MetadataError> {
    Err(MetadataError(message.into()))
}

/// Merges `top` over `base`: tables recursively, every other value replaced.
pub fn merge(base: &mut toml::Table, top: toml::Table) {
    for (key, value) in top {
        match (base.get_mut(&key), value) {
            (Some(toml::Value::Table(base)), toml::Value::Table(top)) => merge(base, top),
            (_, value) => {
                base.insert(key, value);
            }
        }
    }
}

/// Parses `Dioxus.toml` and any overlays, in order, into one table.
pub fn merged(dioxus_toml: &str, overlays: &[&str]) -> Result<toml::Table, MetadataError> {
    let mut table: toml::Table = toml::from_str(dioxus_toml)
        .map_err(|e| MetadataError(format!("Dioxus.toml is not valid TOML: {e}")))?;
    for overlay in overlays {
        let top: toml::Table = toml::from_str(overlay)
            .map_err(|e| MetadataError(format!("the overlay is not valid TOML: {e}")))?;
        merge(&mut table, top);
    }
    Ok(table)
}

impl AppMetadata {
    /// Reads `Dioxus.toml` with its overlays merged in, adds the build facts, and checks
    /// the result.
    pub fn resolve(
        dioxus_toml: &str,
        overlays: &[&str],
        facts: &BuildFacts,
    ) -> Result<Self, MetadataError> {
        let table = merged(dioxus_toml, overlays)?;
        let config: DioxusToml = toml::Value::Table(table)
            .try_into()
            .map_err(|e| MetadataError(format!("Dioxus.toml is not usable: {e}")))?;
        let DioxusToml {
            application,
            bundle,
            linux: dx,
        } = config;
        let linux = dx.store;

        let id = required(dx.identifier.or(bundle.identifier), "[bundle] identifier")?;
        check_app_id(&id)?;
        let name = required(application.name, "[application] name")?;
        let summary = required(
            dx.short_description.or(bundle.short_description),
            "[bundle] short_description",
        )?;
        // AppStream reads a summary as a fragment, not a sentence.
        let summary = summary.trim().trim_end_matches('.').trim().to_owned();
        let description = paragraphs(
            dx.long_description
                .or(bundle.long_description)
                .as_deref()
                .unwrap_or(""),
        );
        let description = if description.is_empty() {
            vec![format!("{summary}.")]
        } else {
            description
        };

        let exec = facts.exec.clone().or(linux.exec).ok_or_else(|| {
            MetadataError("no executable name: pass --exec or set [linux.store] exec".into())
        })?;
        if exec.is_empty()
            || !exec
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-' | '+'))
        {
            return error(format!(
                "the executable name `{exec}` must be a bare file name of letters, digits, `.`, `_`, `-` and `+`"
            ));
        }

        let version = required(
            facts.version.clone().or(bundle.version),
            "a version (--version or [bundle] version)",
        )?;
        if version
            .chars()
            .any(|c| c.is_whitespace() || c == '/' || c == '|')
        {
            return error(format!(
                "the version `{version}` must not contain spaces, slashes or `|`"
            ));
        }
        check_date(&facts.date)?;

        let license = required(linux.license, "[linux.store] license (an SPDX expression)")?;
        let homepage = required(linux.homepage, "[linux.store] homepage")?;
        for (key, url) in [
            ("homepage", Some(&homepage)),
            ("bugtracker", linux.bugtracker.as_ref()),
            ("vcs_browser", linux.vcs_browser.as_ref()),
            ("donation", linux.donation.as_ref()),
        ] {
            if let Some(url) = url {
                check_url(key, url)?;
            }
        }
        for shot in &linux.screenshots {
            check_screenshot_url(&shot.url)?;
        }

        let developer_name = linux
            .developer_name
            .or(dx.publisher)
            .or(bundle.publisher)
            .ok_or_else(|| {
                MetadataError(
                    "no developer: set [linux.store] developer_name or [bundle] publisher".into(),
                )
            })?;
        let developer_id = linux
            .developer_id
            .unwrap_or_else(|| id.split('.').take(2).collect::<Vec<_>>().join("."));

        let mut categories = dx.categories;
        if categories.is_empty() {
            if let Some(category) = dx.category.or(bundle.category) {
                categories.push(dx_category(&category).to_owned());
            }
        }
        if !categories
            .iter()
            .any(|c| MAIN_CATEGORIES.contains(&c.as_str()))
        {
            return error(format!(
                "[linux] categories needs at least one freedesktop main category ({})",
                MAIN_CATEGORIES.join(", ")
            ));
        }
        for mime in &dx.mime_types {
            if !mime.contains('/') || mime.contains(';') {
                return error(format!("[linux] mime_types: `{mime}` is not a MIME type"));
            }
        }
        for category in &categories {
            if category.is_empty() || category.contains(';') {
                return error(format!("the category `{category}` is not a single name"));
            }
        }

        for (key, colour) in [
            ("brand_light", linux.brand_light.as_ref()),
            ("brand_dark", linux.brand_dark.as_ref()),
        ] {
            if let Some(colour) = colour {
                let hex = colour.strip_prefix('#').unwrap_or("");
                if hex.len() != 6 || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
                    return error(format!(
                        "[linux.store] {key} must be #rrggbb, not `{colour}`"
                    ));
                }
            }
        }
        for (attribute, value) in &linux.content_rating {
            if !["none", "mild", "moderate", "intense"].contains(&value.as_str()) {
                return error(format!(
                    "[linux.store.content_rating] {attribute} must be none, mild, moderate or intense"
                ));
            }
        }

        Ok(AppMetadata {
            id,
            name,
            summary,
            description,
            exec,
            version,
            date: facts.date.clone(),
            license,
            metadata_license: linux.metadata_license.unwrap_or_else(|| "CC0-1.0".into()),
            homepage,
            bugtracker: linux.bugtracker,
            vcs_browser: linux.vcs_browser,
            donation: linux.donation,
            developer_id,
            developer_name,
            categories,
            keywords: dx.keywords,
            mime_types: dx.mime_types,
            icons: dx.icon.or(bundle.icon).unwrap_or_default(),
            screenshots: linux.screenshots,
            brand_light: linux.brand_light,
            brand_dark: linux.brand_dark,
            startup_wm_class: linux.startup_wm_class,
            release_notes: linux.release_notes,
            content_rating: linux.content_rating,
            runtime_version: linux.flatpak_runtime_version,
            finish_args: dx.flatpak_permissions,
        })
    }
}

fn required(value: Option<String>, key: &str) -> Result<String, MetadataError> {
    match value {
        Some(value) if !value.trim().is_empty() => Ok(value.trim().to_owned()),
        _ => error(format!("{key} is required for a Linux package")),
    }
}

/// dx's bundle category names, mapped to freedesktop categories. Anything else is passed
/// through, so a freedesktop name written there also works.
fn dx_category(category: &str) -> &str {
    match category {
        "Developer Tool" | "DeveloperTool" => "Development",
        "Entertainment" | "Game" => "Game",
        "Graphics and Design" | "GraphicsAndDesign" | "Photography" => "Graphics",
        "Music" => "Audio",
        "Productivity" | "Business" => "Office",
        "Social Networking" | "SocialNetworking" | "News" => "Network",
        "Utility" | "Utilities" => "Utility",
        other => other,
    }
}

/// Blank lines separate paragraphs; single newlines inside one are folded to spaces.
pub fn paragraphs(text: &str) -> Vec<String> {
    text.split("\n\n")
        .map(|p| p.split_whitespace().collect::<Vec<_>>().join(" "))
        .filter(|p| !p.is_empty())
        .collect()
}

/// A reverse-DNS application identifier that Flatpak, AppStream and desktop entries all
/// accept.
pub fn check_app_id(id: &str) -> Result<(), MetadataError> {
    let components: Vec<&str> = id.split('.').collect();
    if id.len() > 255 {
        return error(format!(
            "the identifier `{id}` is longer than 255 characters"
        ));
    }
    if components.len() < 3 {
        return error(format!(
            "the identifier `{id}` needs at least three dot-separated components, like dev.example.App"
        ));
    }
    for component in &components {
        let Some(first) = component.chars().next() else {
            return error(format!("the identifier `{id}` has an empty component"));
        };
        if first.is_ascii_digit() {
            return error(format!(
                "the identifier `{id}` has a component starting with a digit, which Flatpak refuses"
            ));
        }
        if !component
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
        {
            return error(format!(
                "the identifier `{id}` may only use letters, digits, `_` and `-`"
            ));
        }
    }
    // Flatpak allows a hyphen anywhere, D-Bus (which a desktop entry's name is checked
    // against) only in the last component.
    if components[..components.len() - 1]
        .iter()
        .any(|c| c.contains('-'))
    {
        return error(format!(
            "the identifier `{id}` has `-` before its last component; use `_`"
        ));
    }
    Ok(())
}

fn check_date(date: &str) -> Result<(), MetadataError> {
    let bytes = date.as_bytes();
    let shape = bytes.len() == 10
        && bytes[4] == b'-'
        && bytes[7] == b'-'
        && date
            .chars()
            .enumerate()
            .all(|(i, c)| i == 4 || i == 7 || c.is_ascii_digit());
    let month = date.get(5..7).and_then(|m| m.parse::<u32>().ok());
    let day = date.get(8..10).and_then(|d| d.parse::<u32>().ok());
    if !shape || !matches!(month, Some(1..=12)) || !matches!(day, Some(1..=31)) {
        return error(format!("the release date `{date}` must be YYYY-MM-DD"));
    }
    Ok(())
}

/// A screenshot URL a store can keep showing. Flathub copies the image when it builds, but
/// a listing that points at a branch shows whatever the branch holds next, so a raw GitHub
/// URL has to name a tag or a commit.
pub fn check_screenshot_url(url: &str) -> Result<(), MetadataError> {
    check_url("screenshots.url", url)?;
    let Some(rest) = url
        .strip_prefix("https://raw.githubusercontent.com/")
        .or_else(|| url.strip_prefix("http://raw.githubusercontent.com/"))
    else {
        return Ok(());
    };
    let parts: Vec<&str> = rest.split('/').collect();
    // owner / repository / ref / path..., or owner / repository / refs/tags/<tag> / path...
    let reference = match parts.as_slice() {
        [_, _, "refs", "tags", tag, _, ..] => return check_tag(url, tag),
        [_, _, "refs", "heads", branch, _, ..] => branch,
        [_, _, reference, _, ..] => reference,
        _ => {
            return error(format!(
                "the screenshot `{url}` is not owner/repository/ref/path on raw.githubusercontent.com"
            ));
        }
    };
    if ["main", "master", "develop", "HEAD", "trunk"].contains(reference)
        || url.contains("/refs/heads/")
    {
        return error(format!(
            "the screenshot `{url}` follows the branch `{reference}`; pin it to a tag or a commit"
        ));
    }
    Ok(())
}

fn check_tag(url: &str, tag: &str) -> Result<(), MetadataError> {
    if tag.is_empty() {
        return error(format!("the screenshot `{url}` names an empty tag"));
    }
    Ok(())
}

fn check_url(key: &str, url: &str) -> Result<(), MetadataError> {
    if !(url.starts_with("https://") || url.starts_with("http://")) || url.contains(' ') {
        return error(format!(
            "[linux.store] {key} must be an http(s) URL, not `{url}`"
        ));
    }
    Ok(())
}

/// Today's date in UTC, or the date of `SOURCE_DATE_EPOCH` when that is set, so that two
/// builds of the same commit write the same files.
pub fn release_date(source_date_epoch: Option<&str>, now_unix: u64) -> String {
    let seconds = source_date_epoch
        .and_then(|value| value.trim().parse::<u64>().ok())
        .unwrap_or(now_unix);
    let (y, m, d) = civil_from_days((seconds / 86_400) as i64);
    format!("{y:04}-{m:02}-{d:02}")
}

/// Days since 1970-01-01 to a proleptic Gregorian date (Howard Hinnant's algorithm).
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}
