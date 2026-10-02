//! What the package says about the application, read from the files the application
//! already has.
//!
//! An application describes itself once: its name, identifier, publisher and
//! description in `Dioxus.toml` (the file `dx` reads for every other bundle it makes),
//! and its version in `Cargo.toml`. The package is built from those, so a release only
//! ever changes one number in one place.
//!
//! The Store adds two values that nothing else has: the package identity name and the
//! publisher certificate subject that Partner Center assigns when the application's name
//! is reserved. They go in `[windows.msix]`, which `dx` does not read:
//!
//! ```toml
//! [windows.msix]
//! identity_name = "DarkPyonix.Ember"   # Partner Center: Package/Identity/Name
//! publisher = "CN=01234567-89AB-..."  # Partner Center: Package/Identity/Publisher
//! display_name = "Ember"              # the reserved name, if it differs from [application]
//! languages = ["en-us", "ko-kr"]
//! ```
//!
//! Without them the identity is derived from `[bundle] identifier` and the publisher
//! becomes `CN=<publisher>`, which is enough to build, sign with a test certificate,
//! install and certify, but is not what the Store will accept for upload.

use std::path::{Path, PathBuf};

use toml::{Table, Value};

use crate::Error;

/// The oldest Windows a package installs on. 1809 is the first release that runs a
/// packaged full-trust application with everything this packager writes, and the
/// oldest the Store still offers desktop packages to.
pub const DEFAULT_MIN_VERSION: &str = "10.0.17763.0";
/// The newest Windows the package was tested on. Windows uses it to decide which
/// compatibility behaviour applies, so it moves when a newer Windows has been tried.
pub const DEFAULT_MAX_VERSION_TESTED: &str = "10.0.26100.0";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AppMetadata {
    pub display_name: String,
    pub publisher_display_name: String,
    pub description: String,
    pub identity_name: String,
    pub publisher: String,
    /// Whether the identity came from `[windows.msix]` rather than being derived. The
    /// Store channel insists on it.
    pub store_identity: bool,
    pub version: String,
    pub executable: String,
    pub icon: Option<PathBuf>,
    pub languages: Vec<String>,
    pub capabilities: Vec<String>,
    pub restricted_capabilities: Vec<String>,
    pub device_capabilities: Vec<String>,
    pub min_version: String,
    pub max_version_tested: String,
    pub background_color: String,
}

fn read_table(path: &Path) -> Result<Table, Error> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| Error::new(format!("cannot read {}: {e}", path.display())))?;
    text.parse::<Table>()
        .map_err(|e| Error::new(format!("{} is not valid TOML: {e}", path.display())))
}

fn get<'a>(table: &'a Table, path: &[&str]) -> Option<&'a Value> {
    let (last, parents) = path.split_last()?;
    let mut current = table;
    for key in parents {
        current = current.get(*key)?.as_table()?;
    }
    current.get(*last)
}

fn get_str(table: &Table, path: &[&str]) -> Option<String> {
    get(table, path)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
}

fn get_strings(table: &Table, path: &[&str]) -> Vec<String> {
    match get(table, path) {
        Some(Value::Array(items)) => items
            .iter()
            .filter_map(Value::as_str)
            .map(str::to_owned)
            .collect(),
        Some(Value::String(one)) => vec![one.clone()],
        _ => Vec::new(),
    }
}

/// The package version from `Cargo.toml`, following `version.workspace = true` up to
/// the workspace that defines it.
fn cargo_version(project: &Path, cargo: &Table) -> Result<String, Error> {
    match get(cargo, &["package", "version"]) {
        Some(Value::String(v)) => return Ok(v.clone()),
        Some(Value::Table(t)) if t.get("workspace").and_then(Value::as_bool) == Some(true) => {}
        None => {
            return Err(Error::new(format!(
                "{}/Cargo.toml has no [package] version",
                project.display()
            )));
        }
        Some(other) => {
            return Err(Error::new(format!(
                "{}/Cargo.toml: [package] version is `{other}`, expected a string or `{{ workspace = true }}`",
                project.display()
            )));
        }
    }
    let mut dir = project.parent();
    while let Some(d) = dir {
        let candidate = d.join("Cargo.toml");
        if candidate.is_file() {
            let root = read_table(&candidate)?;
            if root.contains_key("workspace") {
                return get_str(&root, &["workspace", "package", "version"]).ok_or_else(|| {
                    Error::new(format!(
                        "{} inherits its version from the workspace, and {} has no [workspace.package] version",
                        project.display(),
                        candidate.display()
                    ))
                });
            }
        }
        dir = d.parent();
    }
    Err(Error::new(format!(
        "{} inherits its version from a workspace, and no workspace was found above it",
        project.display()
    )))
}

/// Turns a reverse-DNS identifier into a valid package identity name.
///
/// A package name is 3 to 50 characters of ASCII letters, digits, periods and hyphens.
/// Anything else is dropped (an underscore becomes a hyphen), and a name that is too long
/// keeps its last labels, which are the ones that tell applications from one publisher
/// apart.
pub fn identity_from_identifier(identifier: &str) -> Result<String, Error> {
    let cleaned: String = identifier
        .chars()
        .filter_map(|c| match c {
            'a'..='z' | 'A'..='Z' | '0'..='9' | '.' | '-' => Some(c),
            '_' => Some('-'),
            _ => None,
        })
        .collect();
    let labels: Vec<&str> = cleaned.split('.').filter(|l| !l.is_empty()).collect();
    let mut name = labels.join(".");
    let mut start = 0;
    while name.len() > 50 && start + 1 < labels.len() {
        start += 1;
        name = labels[start..].join(".");
    }
    if name.len() > 50 {
        name.truncate(50);
    }
    validate_identity_name(&name).map(|()| name)
}

pub fn validate_identity_name(name: &str) -> Result<(), Error> {
    let ok_chars = name
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-');
    if !(3..=50).contains(&name.len()) || !ok_chars {
        return Err(Error::new(format!(
            "package identity name `{name}` must be 3 to 50 characters of letters, digits, `.` and `-`"
        )));
    }
    if name.starts_with('.') || name.ends_with('.') {
        return Err(Error::new(format!(
            "package identity name `{name}` cannot start or end with `.`"
        )));
    }
    Ok(())
}

/// Quotes a value for a distinguished name when it holds a character a DN treats as
/// syntax.
pub fn dn_value(value: &str) -> String {
    let needs_quotes = value
        .chars()
        .any(|c| matches!(c, ',' | '+' | '=' | '"' | '\\' | '<' | '>' | ';' | '#'))
        || value.starts_with(' ')
        || value.ends_with(' ');
    if needs_quotes {
        format!("\"{}\"", value.replace('"', "\"\""))
    } else {
        value.to_owned()
    }
}

pub fn validate_publisher(publisher: &str) -> Result<(), Error> {
    if !publisher.starts_with("CN=") && !publisher.contains(", CN=") && !publisher.contains(",CN=")
    {
        return Err(Error::new(format!(
            "publisher `{publisher}` is not a certificate subject: it has to be a distinguished name \
             with a CN, such as `CN=Contoso` or the value Partner Center shows under Package/Identity/Publisher"
        )));
    }
    if publisher.len() > 8192 {
        return Err(Error::new("publisher is longer than 8192 characters"));
    }
    Ok(())
}

fn validate_label(what: &str, value: &str) -> Result<(), Error> {
    if value.is_empty() || value.chars().count() > 256 {
        return Err(Error::new(format!(
            "{what} must be 1 to 256 characters, and is `{value}`"
        )));
    }
    Ok(())
}

impl AppMetadata {
    /// Reads `<project>/Dioxus.toml` and `<project>/Cargo.toml`.
    pub fn load(project: &Path) -> Result<Self, Error> {
        let dioxus_path = project.join("Dioxus.toml");
        let dioxus = read_table(&dioxus_path)?;
        let cargo = read_table(&project.join("Cargo.toml"))?;
        let version = cargo_version(project, &cargo)?;
        Self::from_tables(project, &dioxus, &cargo, version)
    }

    pub fn from_tables(
        project: &Path,
        dioxus: &Table,
        cargo: &Table,
        version: String,
    ) -> Result<Self, Error> {
        let package_name = get_str(cargo, &["package", "name"]).ok_or_else(|| {
            Error::new(format!(
                "{}/Cargo.toml has no [package] name",
                project.display()
            ))
        })?;

        let display_name = get_str(dioxus, &["windows", "msix", "display_name"])
            .or_else(|| get_str(dioxus, &["application", "name"]))
            .unwrap_or_else(|| package_name.clone());
        let publisher_display_name = get_str(dioxus, &["windows", "publisher"])
            .or_else(|| get_str(dioxus, &["bundle", "publisher"]))
            .ok_or_else(|| {
                Error::new(
                    "Dioxus.toml has no publisher: set [bundle] publisher to the name the Store shows as the developer",
                )
            })?;
        let description = get_str(dioxus, &["windows", "short_description"])
            .or_else(|| get_str(dioxus, &["bundle", "short_description"]))
            .unwrap_or_else(|| display_name.clone());

        let explicit_identity = get_str(dioxus, &["windows", "msix", "identity_name"]);
        let explicit_publisher = get_str(dioxus, &["windows", "msix", "publisher"]);
        let store_identity = explicit_identity.is_some() && explicit_publisher.is_some();

        let identity_name = match explicit_identity {
            Some(name) => {
                validate_identity_name(&name)?;
                name
            }
            None => {
                let identifier = get_str(dioxus, &["windows", "identifier"])
                    .or_else(|| get_str(dioxus, &["bundle", "identifier"]))
                    .ok_or_else(|| {
                        Error::new(
                            "Dioxus.toml has neither [windows.msix] identity_name nor [bundle] identifier to derive one from",
                        )
                    })?;
                identity_from_identifier(&identifier)?
            }
        };
        let publisher = explicit_publisher
            .unwrap_or_else(|| format!("CN={}", dn_value(&publisher_display_name)));
        validate_publisher(&publisher)?;
        validate_label("display name", &display_name)?;
        validate_label("publisher display name", &publisher_display_name)?;

        // The executable is the binary Cargo builds for this package. A package with more
        // than one binary names the one to launch in [windows.msix] executable.
        let executable = get_str(dioxus, &["windows", "msix", "executable"])
            .or_else(|| {
                cargo
                    .get("bin")
                    .and_then(Value::as_array)
                    .and_then(|bins| bins.first())
                    .and_then(|b| b.get("name"))
                    .and_then(Value::as_str)
                    .map(str::to_owned)
            })
            .unwrap_or(package_name);
        let executable = if executable.to_ascii_lowercase().ends_with(".exe") {
            executable
        } else {
            format!("{executable}.exe")
        };

        let icon = icon_path(project, dioxus);

        let mut languages = get_strings(dioxus, &["windows", "msix", "languages"]);
        if languages.is_empty() {
            languages.push("en-us".to_owned());
        }

        Ok(AppMetadata {
            display_name,
            publisher_display_name,
            description,
            identity_name,
            publisher,
            store_identity,
            version,
            executable,
            icon,
            languages,
            capabilities: get_strings(dioxus, &["windows", "capabilities"]),
            restricted_capabilities: get_strings(dioxus, &["windows", "restricted_capabilities"]),
            device_capabilities: get_strings(dioxus, &["windows", "device_capabilities"]),
            min_version: get_str(dioxus, &["windows", "msix", "min_version"])
                .unwrap_or_else(|| DEFAULT_MIN_VERSION.to_owned()),
            max_version_tested: get_str(dioxus, &["windows", "msix", "max_version_tested"])
                .unwrap_or_else(|| DEFAULT_MAX_VERSION_TESTED.to_owned()),
            background_color: get_str(dioxus, &["windows", "msix", "background_color"])
                .unwrap_or_else(|| "transparent".to_owned()),
        })
    }
}

/// The largest PNG the application names as its icon, or `<asset_dir>/icon.png`.
fn icon_path(project: &Path, dioxus: &Table) -> Option<PathBuf> {
    let mut named = get_strings(dioxus, &["windows", "icon"]);
    if named.is_empty() {
        named = get_strings(dioxus, &["bundle", "icon"]);
    }
    let mut pngs: Vec<PathBuf> = named
        .iter()
        .filter(|p| p.to_ascii_lowercase().ends_with(".png"))
        .map(|p| project.join(p))
        .filter(|p| p.is_file())
        .collect();
    pngs.sort_by_key(|p| std::fs::metadata(p).map(|m| m.len()).unwrap_or(0));
    if let Some(largest) = pngs.pop() {
        return Some(largest);
    }
    let asset_dir =
        get_str(dioxus, &["application", "asset_dir"]).unwrap_or_else(|| "assets".into());
    let fallback = project.join(asset_dir).join("icon.png");
    fallback.is_file().then_some(fallback)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tables(dioxus: &str, cargo: &str) -> (Table, Table) {
        (dioxus.parse().unwrap(), cargo.parse().unwrap())
    }

    const NOTEPAD: &str = r#"
[application]
name = "Notepad"
asset_dir = "assets"

[bundle]
identifier = "dev.darkpyonix.dioxus.compose.samples.notepad"
publisher = "DarkPyonix"
short_description = "A plain text editor built with dioxus-compose."
"#;

    const CARGO: &str = r#"
[package]
name = "sample-notepad"
version = "0.1.0"

[[bin]]
name = "sample-notepad"
path = "src/main.rs"
"#;

    #[test]
    fn fr34_metadata_comes_from_dioxus_toml_and_cargo_toml() {
        let (d, c) = tables(NOTEPAD, CARGO);
        let m = AppMetadata::from_tables(Path::new("/nowhere"), &d, &c, "0.1.0".into()).unwrap();
        assert_eq!(m.display_name, "Notepad");
        assert_eq!(m.publisher_display_name, "DarkPyonix");
        assert_eq!(
            m.description,
            "A plain text editor built with dioxus-compose."
        );
        assert_eq!(
            m.identity_name,
            "dev.darkpyonix.dioxus.compose.samples.notepad"
        );
        assert_eq!(m.publisher, "CN=DarkPyonix");
        assert!(!m.store_identity);
        assert_eq!(m.executable, "sample-notepad.exe");
        assert_eq!(m.languages, vec!["en-us"]);
        assert_eq!(m.min_version, DEFAULT_MIN_VERSION);
    }

    #[test]
    fn fr34_store_identity_overrides_the_derived_one() {
        let dioxus = format!(
            "{NOTEPAD}\n[windows.msix]\nidentity_name = \"DarkPyonix.Ember\"\npublisher = \"CN=1F2E3D4C-0000-1111-2222-333344445555\"\ndisplay_name = \"Ember\"\nlanguages = [\"en-us\", \"ko-kr\"]\n"
        );
        let (d, c) = tables(&dioxus, CARGO);
        let m = AppMetadata::from_tables(Path::new("/nowhere"), &d, &c, "1.0.0".into()).unwrap();
        assert_eq!(m.identity_name, "DarkPyonix.Ember");
        assert_eq!(m.publisher, "CN=1F2E3D4C-0000-1111-2222-333344445555");
        assert_eq!(m.display_name, "Ember");
        assert!(m.store_identity);
        assert_eq!(m.languages, vec!["en-us", "ko-kr"]);
    }

    #[test]
    fn fr34_windows_section_overrides_bundle() {
        let dioxus = format!(
            "{NOTEPAD}\n[windows]\npublisher = \"Dark Pyonix, Inc.\"\nshort_description = \"Windows text\"\ncapabilities = [\"internetClient\"]\n"
        );
        let (d, c) = tables(&dioxus, CARGO);
        let m = AppMetadata::from_tables(Path::new("/nowhere"), &d, &c, "1.0.0".into()).unwrap();
        assert_eq!(m.publisher_display_name, "Dark Pyonix, Inc.");
        assert_eq!(m.publisher, "CN=\"Dark Pyonix, Inc.\"");
        assert_eq!(m.description, "Windows text");
        assert_eq!(m.capabilities, vec!["internetClient"]);
    }

    #[test]
    fn fr34_missing_publisher_is_an_error() {
        let (d, c) = tables(
            "[application]\nname = \"X\"\n[bundle]\nidentifier = \"a.b.c\"\n",
            CARGO,
        );
        let err =
            AppMetadata::from_tables(Path::new("/nowhere"), &d, &c, "1.0.0".into()).unwrap_err();
        assert!(err.to_string().contains("publisher"), "{err}");
    }

    #[test]
    fn fr34_identity_names_are_made_valid() {
        assert_eq!(
            identity_from_identifier("com.example.my_app").unwrap(),
            "com.example.my-app"
        );
        assert_eq!(
            identity_from_identifier("com.ex ample.app!").unwrap(),
            "com.example.app"
        );
        let long = "dev.darkpyonix.dioxus.compose.samples.a-very-long-application-name";
        let name = identity_from_identifier(long).unwrap();
        assert!(name.len() <= 50, "{name}");
        assert!(name.ends_with("a-very-long-application-name"), "{name}");
        assert!(identity_from_identifier("ab").is_err());
    }

    #[test]
    fn fr34_publisher_must_be_a_distinguished_name() {
        assert!(validate_publisher("CN=DarkPyonix").is_ok());
        assert!(validate_publisher("O=Org, CN=Name").is_ok());
        assert!(validate_publisher("DarkPyonix").is_err());
    }

    #[test]
    fn fr34_executable_follows_the_explicit_override() {
        let dioxus = format!("{NOTEPAD}\n[windows.msix]\nexecutable = \"ember\"\n");
        let (d, c) = tables(&dioxus, CARGO);
        let m = AppMetadata::from_tables(Path::new("/nowhere"), &d, &c, "1.0.0".into()).unwrap();
        assert_eq!(m.executable, "ember.exe");
    }

    #[test]
    fn fr34_the_real_samples_produce_valid_metadata() {
        let samples = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../samples");
        let mut seen = 0;
        for entry in std::fs::read_dir(&samples).unwrap() {
            let dir = entry.unwrap().path();
            if !dir.join("Dioxus.toml").is_file() {
                continue;
            }
            let m = AppMetadata::load(&dir).unwrap_or_else(|e| panic!("{}: {e}", dir.display()));
            assert!(m.icon.is_some(), "{} has no icon", dir.display());
            seen += 1;
        }
        assert!(seen > 0, "no samples found under {}", samples.display());
    }
}
