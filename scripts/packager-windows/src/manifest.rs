//! `AppxManifest.xml` for a packaged full-trust desktop application.
//!
//! The application is an ordinary Win32 executable, so the package declares it as one:
//! `Windows.FullTrustApplication` as the entry point and the `runFullTrust` restricted
//! capability. That capability is what lets the renderer load its DLL, read its font
//! configuration and open files the way it does unpackaged; the Store accepts it for
//! desktop applications, with a sentence of justification at submission.

use crate::metadata::AppMetadata;
use crate::templates;
use crate::version::PackageVersion;

/// Escapes text for an XML attribute or element.
pub fn xml_escape(text: &str) -> String {
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

/// Capabilities declared in the `uap` namespace. Everything else in
/// `[windows] capabilities` is a foundation capability (`internetClient` and the like).
const UAP_CAPABILITIES: &[&str] = &[
    "appointments",
    "blockedChatMessages",
    "chat",
    "contacts",
    "documentsLibrary",
    "enterpriseAuthentication",
    "musicLibrary",
    "objects3D",
    "phoneCall",
    "picturesLibrary",
    "removableStorage",
    "sharedUserCertificates",
    "userAccountInformation",
    "videosLibrary",
    "voipCall",
];

/// The application id inside the package: a letter followed by letters and digits.
pub fn application_id(display_name: &str) -> String {
    let id: String = display_name
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .take(64)
        .collect();
    if id.chars().next().is_some_and(|c| c.is_ascii_alphabetic()) {
        id
    } else {
        "App".to_owned()
    }
}

/// The CPU the executable was built for, as the manifest spells it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Arch {
    X64,
    Arm64,
}

impl Arch {
    pub fn parse(text: &str) -> Option<Self> {
        match text {
            "x64" => Some(Arch::X64),
            "arm64" => Some(Arch::Arm64),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Arch::X64 => "x64",
            Arch::Arm64 => "arm64",
        }
    }
}

/// The manifest: the fork's `AppxManifest.xml.in` filled from `meta`, then what the
/// template does not have. More languages follow the first; the application's foundation
/// and `uap` capabilities go before `runFullTrust`, its other restricted capabilities and
/// its device capabilities after it.
///
/// `executable` is the path of the program inside the package, with backslashes.
pub fn render(meta: &AppMetadata, version: PackageVersion, arch: Arch, executable: &str) -> String {
    render_with(templates::APPX_MANIFEST, meta, version, arch, executable)
}

/// [`render`] from a given template, so a test can show the output follows it.
pub fn render_with(
    template: &str,
    meta: &AppMetadata,
    version: PackageVersion,
    arch: Arch,
    executable: &str,
) -> String {
    let e = xml_escape;
    let language = meta.languages.first().map(String::as_str).unwrap_or("en-us");
    let mut x = templates::render(
        template,
        &[
            ("identity_name", &e(&meta.identity_name)),
            ("publisher_id", &e(&meta.publisher)),
            ("version4", &version.to_string()),
            ("arch", arch.as_str()),
            ("name", &e(&meta.display_name)),
            ("publisher", &e(&meta.publisher_display_name)),
            ("min_windows", &e(&meta.min_version)),
            ("max_windows_tested", &e(&meta.max_version_tested)),
            ("language", &e(language)),
            ("app_id", &application_id(&meta.display_name)),
            ("executable", &e(executable)),
            ("description", &e(&meta.description)),
            ("background_color", &e(&meta.background_color)),
        ],
    );

    let more_languages: String = meta
        .languages
        .iter()
        .skip(1)
        .map(|lang| format!("    <Resource Language=\"{}\" />\n", e(lang)))
        .collect();
    insert_after_line(&mut x, "    <Resource Language=", &more_languages);

    // The schema wants every Capability before any DeviceCapability.
    let mut before = String::new();
    for cap in meta
        .capabilities
        .iter()
        .filter(|c| !UAP_CAPABILITIES.contains(&c.as_str()))
    {
        before.push_str(&format!("    <Capability Name=\"{}\" />\n", e(cap)));
    }
    for cap in meta
        .capabilities
        .iter()
        .filter(|c| UAP_CAPABILITIES.contains(&c.as_str()))
    {
        before.push_str(&format!("    <uap:Capability Name=\"{}\" />\n", e(cap)));
    }
    let mut after = String::new();
    for cap in meta
        .restricted_capabilities
        .iter()
        .filter(|c| c.as_str() != "runFullTrust")
    {
        after.push_str(&format!("    <rescap:Capability Name=\"{}\" />\n", e(cap)));
    }
    for cap in &meta.device_capabilities {
        after.push_str(&format!("    <DeviceCapability Name=\"{}\" />\n", e(cap)));
    }
    let full_trust = "    <rescap:Capability Name=\"runFullTrust\" />\n";
    let at = x
        .find(full_trust)
        .expect("the AppxManifest template declares runFullTrust");
    x.insert_str(at + full_trust.len(), &after);
    x.insert_str(at, &before);
    x
}

/// Puts `text` after the first line that starts with `anchor`.
fn insert_after_line(doc: &mut String, anchor: &str, text: &str) {
    if text.is_empty() {
        return;
    }
    let start = doc
        .find(anchor)
        .unwrap_or_else(|| panic!("the AppxManifest template has no {anchor:?}"));
    let end = start + doc[start..].find('\n').expect("a template line ends") + 1;
    doc.insert_str(end, text);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::assets;
    use crate::metadata::{DEFAULT_MAX_VERSION_TESTED, DEFAULT_MIN_VERSION};
    use crate::version::Channel;

    fn meta() -> AppMetadata {
        AppMetadata {
            display_name: "Ember".into(),
            publisher_display_name: "DarkPyonix".into(),
            description: "Notes & <things>".into(),
            identity_name: "DarkPyonix.Ember".into(),
            publisher: "CN=\"Dark, Pyonix\"".into(),
            store_identity: true,
            version: "1.2.3".into(),
            executable: "ember.exe".into(),
            icon: None,
            languages: vec!["en-us".into(), "ko-kr".into()],
            capabilities: vec!["internetClient".into(), "picturesLibrary".into()],
            restricted_capabilities: vec![],
            device_capabilities: vec!["microphone".into()],
            min_version: DEFAULT_MIN_VERSION.into(),
            max_version_tested: DEFAULT_MAX_VERSION_TESTED.into(),
            background_color: "transparent".into(),
        }
    }

    fn version() -> PackageVersion {
        PackageVersion::from_semver("1.2.3", Channel::Store, None).unwrap()
    }

    #[test]
    fn fr22_6_4_manifest_carries_identity_and_version() {
        let xml = render(&meta(), version(), Arch::X64, "ember.exe");
        assert!(xml.contains(
            "<Identity Name=\"DarkPyonix.Ember\" Publisher=\"CN=&quot;Dark, Pyonix&quot;\" Version=\"1.2.3.0\" ProcessorArchitecture=\"x64\" />"
        ), "{xml}");
        assert!(xml.contains("<DisplayName>Ember</DisplayName>"));
        assert!(xml.contains("<PublisherDisplayName>DarkPyonix</PublisherDisplayName>"));
    }

    #[test]
    fn fr22_6_4_manifest_declares_a_full_trust_desktop_application() {
        let xml = render(&meta(), version(), Arch::Arm64, "bin\\ember.exe");
        assert!(xml.contains("EntryPoint=\"Windows.FullTrustApplication\""));
        assert!(xml.contains("Executable=\"bin\\ember.exe\""));
        assert!(xml.contains("<rescap:Capability Name=\"runFullTrust\" />"));
        assert!(xml.contains("Name=\"Windows.Desktop\""));
        assert!(xml.contains("ProcessorArchitecture=\"arm64\""));
        assert_eq!(xml.matches("runFullTrust").count(), 1);
    }

    #[test]
    fn fr22_6_4_manifest_escapes_text() {
        let xml = render(&meta(), version(), Arch::X64, "ember.exe");
        assert!(xml.contains("Description=\"Notes &amp; &lt;things&gt;\""));
        assert!(!xml.contains("Notes & <"));
    }

    #[test]
    fn fr22_6_4_manifest_names_every_asset_it_ships() {
        let xml = render(&meta(), version(), Arch::X64, "ember.exe");
        for spec in assets::ALL {
            assert!(
                xml.contains(&format!("Assets\\{}", spec.file)),
                "{} is generated but not referenced",
                spec.file
            );
        }
    }

    #[test]
    fn fr22_6_4_capabilities_are_ordered_and_namespaced() {
        let xml = render(&meta(), version(), Arch::X64, "ember.exe");
        let foundation = xml.find("<Capability Name=\"internetClient\"").unwrap();
        let uap = xml
            .find("<uap:Capability Name=\"picturesLibrary\"")
            .unwrap();
        let rescap = xml
            .find("<rescap:Capability Name=\"runFullTrust\"")
            .unwrap();
        let device = xml.find("<DeviceCapability Name=\"microphone\"").unwrap();
        assert!(foundation < uap && uap < rescap && rescap < device, "{xml}");
    }

    #[test]
    fn fr22_6_4_every_language_is_a_resource() {
        let xml = render(&meta(), version(), Arch::X64, "ember.exe");
        assert!(xml.contains("<Resource Language=\"en-us\" />"));
        assert!(xml.contains("<Resource Language=\"ko-kr\" />"));
    }

    #[test]
    fn fr22_6_4_application_id_is_alphanumeric() {
        assert_eq!(application_id("Ember"), "Ember");
        assert_eq!(application_id("My App 2"), "MyApp2");
        assert_eq!(application_id("2048"), "App");
        assert_eq!(application_id("노트"), "App");
    }

    #[test]
    fn fr22_6_11_the_manifest_is_its_template_filled() {
        // With one language and no extra capability, nothing is added to the template.
        let mut m = meta();
        m.languages = vec!["en-us".into()];
        m.capabilities.clear();
        m.restricted_capabilities.clear();
        m.device_capabilities.clear();
        let xml = render(&m, version(), Arch::X64, "Ember.exe");
        let template = templates::render(
            templates::APPX_MANIFEST,
            &[
                ("identity_name", "DarkPyonix.Ember"),
                ("publisher_id", "CN=&quot;Dark, Pyonix&quot;"),
                ("version4", "1.2.3.0"),
                ("arch", "x64"),
                ("name", "Ember"),
                ("publisher", "DarkPyonix"),
                ("min_windows", DEFAULT_MIN_VERSION),
                ("max_windows_tested", DEFAULT_MAX_VERSION_TESTED),
                ("language", "en-us"),
                ("app_id", "Ember"),
                ("executable", "Ember.exe"),
                ("description", "Notes &amp; &lt;things&gt;"),
                ("background_color", "transparent"),
            ],
        );
        assert_eq!(xml, template);
    }

    #[test]
    fn fr22_6_11_additions_keep_every_template_line_in_order() {
        let xml = render(&meta(), version(), Arch::Arm64, "Ember.exe");
        let mut have = xml.lines();
        let template = templates::APPX_MANIFEST;
        for line in template.lines().filter(|l| !l.contains("{{")) {
            assert!(have.any(|l| l == line), "{line:?} missing or out of order in\n{xml}");
        }
        assert!(xml.contains("ProcessorArchitecture=\"arm64\""));
    }

    #[test]
    fn fr22_6_11_the_manifest_follows_its_template() {
        let template = templates::APPX_MANIFEST.replace(
            "  <Capabilities>",
            "  <Extensions />\n  <Capabilities>",
        );
        let xml = render_with(&template, &meta(), version(), Arch::X64, "Ember.exe");
        assert!(xml.contains("  <Extensions />\n  <Capabilities>\n"), "{xml}");
    }

    #[test]
    fn fr22_6_11_the_template_names_the_assets_the_packager_makes() {
        for spec in assets::ALL {
            let path = format!("{}\\{}", assets::DIR, spec.file);
            assert!(templates::APPX_MANIFEST.contains(&path), "the template does not name {path}");
        }
    }

    #[test]
    #[should_panic(expected = "needs a value for {{nope}}")]
    fn fr22_6_11_an_unfilled_placeholder_stops_the_packager() {
        render_with("<Package Name=\"{{nope}}\" />", &meta(), version(), Arch::X64, "a.exe");
    }
}
