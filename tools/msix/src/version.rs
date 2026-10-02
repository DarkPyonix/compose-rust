//! The four-part package version.
//!
//! An application states one version, the `version` in its `Cargo.toml`, and every
//! package is versioned from it. MSIX wants `Major.Minor.Build.Revision`, each part a
//! 16-bit number, and Windows only ever replaces an installed package with a strictly
//! greater one. Both delivery channels lean on that rule, so the mapping has to be
//! monotonic: a later release of the application must never produce a smaller version.

use std::fmt;

use crate::Error;

/// Where a package is going. The two differ in who owns the fourth part of the version.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Channel {
    /// Partner Center. The Store reserves the revision for its own use and rejects a
    /// package whose revision is not zero, so a Store release is `Major.Minor.Patch.0`.
    Store,
    /// Direct download kept current by App Installer. The revision is a build number
    /// that only grows, so successive builds of one release (or of a pre-release) each
    /// install over the last.
    Sideload,
}

impl Channel {
    pub fn parse(text: &str) -> Result<Self, Error> {
        match text {
            "store" => Ok(Channel::Store),
            "sideload" => Ok(Channel::Sideload),
            other => Err(Error::new(format!(
                "unknown channel `{other}`: use `store` for Partner Center or `sideload` for App Installer"
            ))),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct PackageVersion {
    pub major: u16,
    pub minor: u16,
    pub build: u16,
    pub revision: u16,
}

impl fmt::Display for PackageVersion {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}.{}.{}.{}",
            self.major, self.minor, self.build, self.revision
        )
    }
}

fn part(name: &str, text: &str, whole: &str) -> Result<u16, Error> {
    let value: u64 = text.parse().map_err(|_| {
        Error::new(format!(
            "version `{whole}`: the {name} part `{text}` is not a number"
        ))
    })?;
    u16::try_from(value).map_err(|_| {
        Error::new(format!(
            "version `{whole}`: the {name} part is {value}, and a package version part cannot exceed 65535"
        ))
    })
}

impl PackageVersion {
    /// Maps an application's semantic version onto a package version for `channel`.
    ///
    /// `revision` is the build number for the sideload channel. It is refused for the
    /// Store, where the revision has to stay zero. A pre-release (`1.2.0-beta.3`) is
    /// refused for the Store as well: the Store orders packages by version alone, so
    /// `1.2.0-beta.3` and `1.2.0` would both be `1.2.0.0` and the release could never
    /// replace the beta. Ship a pre-release on the sideload channel, or give it its
    /// own patch number.
    pub fn from_semver(
        semver: &str,
        channel: Channel,
        revision: Option<u32>,
    ) -> Result<Self, Error> {
        let trimmed = semver.trim();
        // Build metadata never takes part in ordering, so it is dropped first.
        let without_build = trimmed.split('+').next().unwrap_or(trimmed);
        let (core, pre) = match without_build.split_once('-') {
            Some((core, pre)) => (core, Some(pre)),
            None => (without_build, None),
        };
        let parts: Vec<&str> = core.split('.').collect();
        if parts.len() != 3 {
            return Err(Error::new(format!(
                "version `{trimmed}` is not MAJOR.MINOR.PATCH"
            )));
        }
        let major = part("major", parts[0], trimmed)?;
        let minor = part("minor", parts[1], trimmed)?;
        let build = part("patch", parts[2], trimmed)?;

        let revision = match channel {
            Channel::Store => {
                if let Some(pre) = pre {
                    return Err(Error::new(format!(
                        "version `{trimmed}` is a pre-release (`{pre}`), and the Store cannot tell it apart \
                         from the release it precedes: both would be {major}.{minor}.{build}.0. \
                         Package it for the sideload channel, or give it a patch number of its own"
                    )));
                }
                if let Some(r) = revision.filter(|r| *r != 0) {
                    return Err(Error::new(format!(
                        "a Store package must have revision 0 (the Store reserves the fourth part), \
                         and revision {r} was given"
                    )));
                }
                0
            }
            Channel::Sideload => {
                let r = revision.unwrap_or(0);
                u16::try_from(r).map_err(|_| {
                    Error::new(format!(
                        "revision {r} cannot exceed 65535; use a counter that restarts with each release"
                    ))
                })?
            }
        };

        Ok(PackageVersion {
            major,
            minor,
            build,
            revision,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fr34_store_version_is_the_crate_version_with_revision_zero() {
        let v = PackageVersion::from_semver("1.4.2", Channel::Store, None).unwrap();
        assert_eq!(v.to_string(), "1.4.2.0");
    }

    #[test]
    fn fr34_store_refuses_a_nonzero_revision() {
        assert!(PackageVersion::from_semver("1.4.2", Channel::Store, Some(7)).is_err());
        assert!(PackageVersion::from_semver("1.4.2", Channel::Store, Some(0)).is_ok());
    }

    #[test]
    fn fr34_store_refuses_a_prerelease() {
        let err = PackageVersion::from_semver("1.4.2-beta.1", Channel::Store, None).unwrap_err();
        assert!(err.to_string().contains("pre-release"), "{err}");
    }

    #[test]
    fn fr34_sideload_revision_is_the_build_number() {
        let v =
            PackageVersion::from_semver("1.4.2-beta.1+abc", Channel::Sideload, Some(31)).unwrap();
        assert_eq!(v.to_string(), "1.4.2.31");
    }

    #[test]
    fn fr34_build_metadata_is_ignored() {
        let v = PackageVersion::from_semver("2.0.1+git.abc", Channel::Store, None).unwrap();
        assert_eq!(v.to_string(), "2.0.1.0");
    }

    #[test]
    fn fr34_parts_above_sixteen_bits_are_refused() {
        assert!(PackageVersion::from_semver("1.70000.0", Channel::Store, None).is_err());
        assert!(PackageVersion::from_semver("1.0.0", Channel::Sideload, Some(70_000)).is_err());
    }

    #[test]
    fn fr34_malformed_versions_are_refused() {
        for bad in ["1.0", "1.0.0.0", "a.b.c", "", "1..0"] {
            assert!(
                PackageVersion::from_semver(bad, Channel::Store, None).is_err(),
                "{bad} was accepted"
            );
        }
    }

    #[test]
    fn fr34_later_releases_map_to_greater_versions() {
        let order = ["0.9.9", "1.0.0", "1.0.1", "1.1.0", "2.0.0"];
        let mapped: Vec<_> = order
            .iter()
            .map(|v| PackageVersion::from_semver(v, Channel::Store, None).unwrap())
            .collect();
        assert!(mapped.windows(2).all(|w| w[0] < w[1]));

        let a = PackageVersion::from_semver("1.0.0", Channel::Sideload, Some(4)).unwrap();
        let b = PackageVersion::from_semver("1.0.0", Channel::Sideload, Some(5)).unwrap();
        assert!(a < b);
    }

    #[test]
    fn fr34_channel_names_parse() {
        assert_eq!(Channel::parse("store").unwrap(), Channel::Store);
        assert_eq!(Channel::parse("sideload").unwrap(), Channel::Sideload);
        assert!(Channel::parse("beta").is_err());
    }
}
