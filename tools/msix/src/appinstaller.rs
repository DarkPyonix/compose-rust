//! The App Installer update feed.
//!
//! A package installed from the Store is updated by the Store. A package installed from
//! a download has no Store behind it, so it carries the address of a small XML file
//! instead. Windows reads that file when the application starts and in the background,
//! and when it names a greater version, downloads and installs it over the running
//! copy. Publishing an update is replacing two files on a web server: the bundle and
//! this one.
//!
//! The feed is written next to the bundle and names its own address, which is how
//! Windows knows where to look the next time.

use crate::Error;
use crate::manifest::xml_escape;
use crate::metadata::AppMetadata;
use crate::version::PackageVersion;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Feed {
    /// Where the feed itself is served, ending in `.appinstaller`.
    pub feed_uri: String,
    /// Where the bundle it points at is served.
    pub bundle_uri: String,
    /// How often, in hours, Windows checks the feed when the application starts. Zero
    /// means every start.
    pub hours_between_checks: u32,
}

fn check_uri(what: &str, uri: &str) -> Result<(), Error> {
    let ok = uri.starts_with("https://")
        || uri.starts_with("http://")
        || uri.starts_with("\\\\")
        || uri.starts_with("file://");
    if !ok {
        return Err(Error::new(format!(
            "{what} `{uri}` has to be an https:// address (or a file share for testing)"
        )));
    }
    Ok(())
}

impl Feed {
    /// A feed served from `base`, a directory URL, with the bundle beside it.
    pub fn beside(base: &str, feed_file: &str, bundle_file: &str) -> Result<Self, Error> {
        // A file share is joined the way Windows writes paths, everything else as a URL.
        let sep = if base.starts_with("\\\\") { '\\' } else { '/' };
        let base = base.trim_end_matches(['/', '\\']);
        let feed = Feed {
            feed_uri: format!("{base}{sep}{feed_file}"),
            bundle_uri: format!("{base}{sep}{bundle_file}"),
            hours_between_checks: 0,
        };
        check_uri("feed address", &feed.feed_uri)?;
        Ok(feed)
    }

    pub fn render(&self, meta: &AppMetadata, version: PackageVersion) -> Result<String, Error> {
        check_uri("feed address", &self.feed_uri)?;
        check_uri("bundle address", &self.bundle_uri)?;
        let e = xml_escape;
        Ok(format!(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n\
             <AppInstaller xmlns=\"http://schemas.microsoft.com/appx/appinstaller/2018\" \
             Version=\"{version}\" Uri=\"{feed}\">\n  \
             <MainBundle Name=\"{name}\" Publisher=\"{publisher}\" Version=\"{version}\" Uri=\"{bundle}\" />\n  \
             <UpdateSettings>\n    \
             <OnLaunch HoursBetweenUpdateChecks=\"{hours}\" />\n    \
             <AutomaticBackgroundTask />\n  \
             </UpdateSettings>\n\
             </AppInstaller>\n",
            feed = e(&self.feed_uri),
            bundle = e(&self.bundle_uri),
            name = e(&meta.identity_name),
            publisher = e(&meta.publisher),
            hours = self.hours_between_checks,
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::version::Channel;

    fn meta() -> AppMetadata {
        let d: toml::Table = "[application]\nname = \"Ember\"\n[bundle]\nidentifier = \"dev.darkpyonix.ember\"\npublisher = \"DarkPyonix\"\n"
            .parse()
            .unwrap();
        let c: toml::Table = "[package]\nname = \"ember\"\nversion = \"1.0.0\"\n"
            .parse()
            .unwrap();
        AppMetadata::from_tables(std::path::Path::new("/nowhere"), &d, &c, "1.0.0".into()).unwrap()
    }

    #[test]
    fn fr34_feed_points_at_the_bundle_and_at_itself() {
        let v = PackageVersion::from_semver("1.0.0", Channel::Sideload, Some(12)).unwrap();
        let feed = Feed::beside(
            "https://example.com/ember/",
            "Ember.appinstaller",
            "Ember_1.0.0.12.msixbundle",
        )
        .unwrap();
        let xml = feed.render(&meta(), v).unwrap();
        assert!(
            xml.contains("Uri=\"https://example.com/ember/Ember.appinstaller\""),
            "{xml}"
        );
        assert!(xml.contains(
            "<MainBundle Name=\"dev.darkpyonix.ember\" Publisher=\"CN=DarkPyonix\" Version=\"1.0.0.12\" Uri=\"https://example.com/ember/Ember_1.0.0.12.msixbundle\" />"
        ), "{xml}");
        assert!(xml.contains("<OnLaunch HoursBetweenUpdateChecks=\"0\" />"));
        assert!(xml.contains("<AutomaticBackgroundTask />"));
    }

    #[test]
    fn fr34_feed_on_a_file_share_uses_backslashes() {
        let feed = Feed::beside("\\\\host\\share\\", "a.appinstaller", "b.msixbundle").unwrap();
        assert_eq!(feed.feed_uri, "\\\\host\\share\\a.appinstaller");
        assert_eq!(feed.bundle_uri, "\\\\host\\share\\b.msixbundle");
    }

    #[test]
    fn fr34_feed_refuses_a_relative_address() {
        assert!(Feed::beside("ember", "a.appinstaller", "b.msixbundle").is_err());
    }
}
