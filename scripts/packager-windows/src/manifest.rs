//! `AppxManifest.xml` for a packaged full-trust desktop application.
//!
//! The application is an ordinary Win32 executable, so the package declares it as one:
//! `Windows.FullTrustApplication` as the entry point and the `runFullTrust` restricted
//! capability. That capability is what lets the renderer load its DLL, read its font
//! configuration and open files the way it does unpackaged; the Store accepts it for
//! desktop applications, with a sentence of justification at submission.

use crate::assets;
use crate::metadata::AppMetadata;
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

/// `executable` is the path of the program inside the package, with backslashes.
pub fn render(meta: &AppMetadata, version: PackageVersion, arch: Arch, executable: &str) -> String {
    let e = xml_escape;
    let mut x = String::new();
    x.push_str("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
    x.push_str(
        "<Package\n  xmlns=\"http://schemas.microsoft.com/appx/manifest/foundation/windows10\"\n  \
         xmlns:uap=\"http://schemas.microsoft.com/appx/manifest/uap/windows10\"\n  \
         xmlns:rescap=\"http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities\"\n  \
         IgnorableNamespaces=\"uap rescap\">\n",
    );
    x.push_str(&format!(
        "  <Identity Name=\"{}\" Publisher=\"{}\" Version=\"{}\" ProcessorArchitecture=\"{}\" />\n",
        e(&meta.identity_name),
        e(&meta.publisher),
        version,
        arch.as_str()
    ));
    x.push_str("  <Properties>\n");
    x.push_str(&format!(
        "    <DisplayName>{}</DisplayName>\n",
        e(&meta.display_name)
    ));
    x.push_str(&format!(
        "    <PublisherDisplayName>{}</PublisherDisplayName>\n",
        e(&meta.publisher_display_name)
    ));
    x.push_str(&format!(
        "    <Logo>{}\\{}</Logo>\n",
        assets::DIR,
        assets::STORE_LOGO.file
    ));
    x.push_str("  </Properties>\n");
    x.push_str("  <Dependencies>\n");
    x.push_str(&format!(
        "    <TargetDeviceFamily Name=\"Windows.Desktop\" MinVersion=\"{}\" MaxVersionTested=\"{}\" />\n",
        e(&meta.min_version),
        e(&meta.max_version_tested)
    ));
    x.push_str("  </Dependencies>\n");
    x.push_str("  <Resources>\n");
    for lang in &meta.languages {
        x.push_str(&format!("    <Resource Language=\"{}\" />\n", e(lang)));
    }
    x.push_str("  </Resources>\n");
    x.push_str("  <Applications>\n");
    x.push_str(&format!(
        "    <Application Id=\"{}\" Executable=\"{}\" EntryPoint=\"Windows.FullTrustApplication\">\n",
        application_id(&meta.display_name),
        e(executable)
    ));
    x.push_str(&format!(
        "      <uap:VisualElements DisplayName=\"{}\" Description=\"{}\" BackgroundColor=\"{}\" \
         Square150x150Logo=\"{d}\\{}\" Square44x44Logo=\"{d}\\{}\">\n",
        e(&meta.display_name),
        e(&meta.description),
        e(&meta.background_color),
        assets::SQUARE_150.file,
        assets::SQUARE_44.file,
        d = assets::DIR,
    ));
    x.push_str(&format!(
        "        <uap:DefaultTile Wide310x150Logo=\"{}\\{}\" />\n",
        assets::DIR,
        assets::WIDE_310.file
    ));
    x.push_str("      </uap:VisualElements>\n");
    x.push_str("    </Application>\n");
    x.push_str("  </Applications>\n");
    x.push_str("  <Capabilities>\n");
    // The schema wants every Capability before any DeviceCapability.
    for cap in meta
        .capabilities
        .iter()
        .filter(|c| !UAP_CAPABILITIES.contains(&c.as_str()))
    {
        x.push_str(&format!("    <Capability Name=\"{}\" />\n", e(cap)));
    }
    for cap in meta
        .capabilities
        .iter()
        .filter(|c| UAP_CAPABILITIES.contains(&c.as_str()))
    {
        x.push_str(&format!("    <uap:Capability Name=\"{}\" />\n", e(cap)));
    }
    x.push_str("    <rescap:Capability Name=\"runFullTrust\" />\n");
    for cap in meta
        .restricted_capabilities
        .iter()
        .filter(|c| c.as_str() != "runFullTrust")
    {
        x.push_str(&format!("    <rescap:Capability Name=\"{}\" />\n", e(cap)));
    }
    for cap in &meta.device_capabilities {
        x.push_str(&format!("    <DeviceCapability Name=\"{}\" />\n", e(cap)));
    }
    x.push_str("  </Capabilities>\n");
    x.push_str("</Package>\n");
    x
}

#[cfg(test)]
mod tests {
    use super::*;
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
    fn fr35_manifest_carries_identity_and_version() {
        let xml = render(&meta(), version(), Arch::X64, "ember.exe");
        assert!(xml.contains(
            "<Identity Name=\"DarkPyonix.Ember\" Publisher=\"CN=&quot;Dark, Pyonix&quot;\" Version=\"1.2.3.0\" ProcessorArchitecture=\"x64\" />"
        ), "{xml}");
        assert!(xml.contains("<DisplayName>Ember</DisplayName>"));
        assert!(xml.contains("<PublisherDisplayName>DarkPyonix</PublisherDisplayName>"));
    }

    #[test]
    fn fr35_manifest_declares_a_full_trust_desktop_application() {
        let xml = render(&meta(), version(), Arch::Arm64, "bin\\ember.exe");
        assert!(xml.contains("EntryPoint=\"Windows.FullTrustApplication\""));
        assert!(xml.contains("Executable=\"bin\\ember.exe\""));
        assert!(xml.contains("<rescap:Capability Name=\"runFullTrust\" />"));
        assert!(xml.contains("Name=\"Windows.Desktop\""));
        assert!(xml.contains("ProcessorArchitecture=\"arm64\""));
        assert_eq!(xml.matches("runFullTrust").count(), 1);
    }

    #[test]
    fn fr35_manifest_escapes_text() {
        let xml = render(&meta(), version(), Arch::X64, "ember.exe");
        assert!(xml.contains("Description=\"Notes &amp; &lt;things&gt;\""));
        assert!(!xml.contains("Notes & <"));
    }

    #[test]
    fn fr35_manifest_names_every_asset_it_ships() {
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
    fn fr35_capabilities_are_ordered_and_namespaced() {
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
    fn fr35_every_language_is_a_resource() {
        let xml = render(&meta(), version(), Arch::X64, "ember.exe");
        assert!(xml.contains("<Resource Language=\"en-us\" />"));
        assert!(xml.contains("<Resource Language=\"ko-kr\" />"));
    }

    #[test]
    fn fr35_application_id_is_alphanumeric() {
        assert_eq!(application_id("Ember"), "Ember");
        assert_eq!(application_id("My App 2"), "MyApp2");
        assert_eq!(application_id("2048"), "App");
        assert_eq!(application_id("노트"), "App");
    }
}
