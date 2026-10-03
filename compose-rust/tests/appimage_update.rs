//! An AppImage updates itself; a Flatpak leaves updating to its store.
//!
//! The AppImages here are synthetic ELF files with exactly the sections that matter, and
//! `appimageupdatetool` is a shell script that answers the way the real one does. The real
//! pair runs in `.github/workflows/packager-linux.yml`, which updates a built AppImage
//! against `.zsync` files served on the runner.

use std::ffi::OsString;
use std::io::Cursor;
use std::path::{Path, PathBuf};

use compose_rust::update::{
    AppImageUpdater, BUNDLED_UPDATER, Delivery, UpdateCheck, UpdateError, UpdateInformation,
    delivery_from, find_tool, read_update_information, update_information_of,
};

/// An ELF file with a null section, the given sections and a section name table.
fn elf(wide: bool, big: bool, sections: &[(&str, &[u8])]) -> Vec<u8> {
    let put16 = |out: &mut Vec<u8>, v: u16| {
        out.extend_from_slice(&if big {
            v.to_be_bytes()
        } else {
            v.to_le_bytes()
        })
    };
    let put32 = |out: &mut Vec<u8>, v: u32| {
        out.extend_from_slice(&if big {
            v.to_be_bytes()
        } else {
            v.to_le_bytes()
        })
    };
    let put64 = |out: &mut Vec<u8>, v: u64| {
        out.extend_from_slice(&if big {
            v.to_be_bytes()
        } else {
            v.to_le_bytes()
        })
    };
    let header_size = if wide { 64 } else { 52 };
    let entry_size: u16 = if wide { 64 } else { 40 };

    let mut names = vec![0u8];
    let mut name_offsets = Vec::new();
    for (name, _) in sections {
        name_offsets.push(names.len() as u32);
        names.extend_from_slice(name.as_bytes());
        names.push(0);
    }
    let names_name = names.len() as u32;
    names.extend_from_slice(b".shstrtab\0");

    let mut body = Vec::new();
    let mut placed = Vec::new();
    for (_, data) in sections {
        placed.push(((header_size + body.len()) as u64, data.len() as u64));
        body.extend_from_slice(data);
    }
    let names_at = (header_size + body.len()) as u64;
    body.extend_from_slice(&names);
    let table_at = (header_size + body.len()) as u64;
    let count = sections.len() as u16 + 2;

    let mut out = Vec::new();
    out.extend_from_slice(b"\x7fELF");
    out.push(if wide { 2 } else { 1 });
    out.push(if big { 2 } else { 1 });
    out.push(1);
    out.extend_from_slice(&[0; 9]);
    put16(&mut out, 2); // executable
    put16(&mut out, if wide { 62 } else { 3 });
    put32(&mut out, 1);
    if wide {
        put64(&mut out, 0); // entry
        put64(&mut out, 0); // program headers
        put64(&mut out, table_at);
    } else {
        put32(&mut out, 0);
        put32(&mut out, 0);
        put32(&mut out, table_at as u32);
    }
    put32(&mut out, 0); // flags
    put16(&mut out, header_size as u16);
    put16(&mut out, 0);
    put16(&mut out, 0);
    put16(&mut out, entry_size);
    put16(&mut out, count);
    put16(&mut out, count - 1);
    assert_eq!(out.len(), header_size);
    out.extend_from_slice(&body);

    let entry = |out: &mut Vec<u8>, name: u32, offset: u64, size: u64| {
        let start = out.len();
        put32(out, name);
        put32(out, if name == 0 && size == 0 { 0 } else { 1 });
        if wide {
            put64(out, 0); // flags
            put64(out, 0); // address
            put64(out, offset);
            put64(out, size);
        } else {
            put32(out, 0);
            put32(out, 0);
            put32(out, offset as u32);
            put32(out, size as u32);
        }
        out.resize(start + entry_size as usize, 0);
    };
    entry(&mut out, 0, 0, 0);
    for (name, (offset, size)) in name_offsets.iter().zip(&placed) {
        entry(&mut out, *name, *offset, *size);
    }
    entry(&mut out, names_name, names_at, names.len() as u64);
    out
}

/// The section as the AppImage runtime reserves it: 1024 bytes, filled from the start.
fn upd_info(line: &str) -> Vec<u8> {
    let mut bytes = line.as_bytes().to_vec();
    bytes.resize(1024, 0);
    bytes
}

fn scratch(test: &str) -> PathBuf {
    let dir = Path::new(env!("CARGO_TARGET_TMPDIR"))
        .join("appimage_update")
        .join(test);
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

const LOCAL: &str = "zsync|http://127.0.0.1:8080/Demo-x86_64.AppImage.zsync";

#[test]
fn fr35_reads_update_information_from_every_elf_layout() {
    for wide in [true, false] {
        for big in [false, true] {
            let file = elf(
                wide,
                big,
                &[
                    (".text", b"\x90\x90"),
                    (".upd_info", &upd_info(LOCAL)),
                    (".sha256_sig", &[0; 1024]),
                ],
            );
            let found = read_update_information(&mut Cursor::new(file)).unwrap();
            assert_eq!(
                found.as_deref(),
                Some(LOCAL),
                "wide={wide} big_endian={big}"
            );
        }
    }
}

#[test]
fn fr35_an_empty_or_missing_section_means_no_update_information() {
    let reserved = elf(true, false, &[(".upd_info", &[0; 1024])]);
    assert_eq!(
        read_update_information(&mut Cursor::new(reserved)).unwrap(),
        None
    );
    let absent = elf(true, false, &[(".text", b"\x90")]);
    assert_eq!(
        read_update_information(&mut Cursor::new(absent)).unwrap(),
        None
    );
}

#[test]
fn fr35_a_file_that_is_not_elf_is_an_error_not_a_panic() {
    for bytes in [
        b"#!/bin/sh\necho hi\n".to_vec(),
        Vec::new(),
        b"\x7fELF".to_vec(),
    ] {
        let error = read_update_information(&mut Cursor::new(bytes)).unwrap_err();
        assert!(matches!(error, UpdateError::NotElf(_)), "{error}");
    }
    // A section table that points past the end of the file.
    let mut truncated = elf(true, false, &[(".upd_info", &upd_info(LOCAL))]);
    truncated.truncate(truncated.len() - 10);
    assert!(read_update_information(&mut Cursor::new(truncated)).is_err());
}

#[test]
fn fr35_update_information_parses_the_three_forms_and_prints_them_back() {
    let lines = [
        LOCAL,
        "gh-releases-zsync|darkpyonix|compose-rust|latest|Calculator-*-x86_64.AppImage.zsync",
        "pling-v1-zsync|1234567|Calculator-*-x86_64.AppImage",
    ];
    for line in lines {
        let parsed: UpdateInformation = line.parse().unwrap();
        assert_eq!(parsed.to_string(), line);
    }
    let parsed: UpdateInformation = lines[1].parse().unwrap();
    assert_eq!(
        parsed,
        UpdateInformation::GitHubReleasesZsync {
            owner: "darkpyonix".into(),
            repository: "compose-rust".into(),
            release: "latest".into(),
            file_name: "Calculator-*-x86_64.AppImage.zsync".into(),
        }
    );
}

#[test]
fn fr35_malformed_update_information_says_what_is_wrong() {
    for line in [
        "",
        "zsync|",
        "zsync|a|b",
        "gh-releases-zsync|owner|repo|latest",
        "gh-releases-zsync|owner||latest|file",
        "bintray-zsync|a|b|c|d",
    ] {
        let error = line.parse::<UpdateInformation>().unwrap_err();
        assert!(
            matches!(error, UpdateError::InvalidUpdateInformation { .. }),
            "{line}: {error}"
        );
        assert!(error.to_string().contains("is not usable"), "{error}");
    }
}

fn env_of(pairs: &'static [(&'static str, &'static str)]) -> impl Fn(&str) -> Option<OsString> {
    move |name| {
        pairs
            .iter()
            .find(|(key, _)| *key == name)
            .map(|(_, value)| OsString::from(value))
    }
}

#[test]
fn fr35_delivery_tells_an_appimage_from_a_flatpak_from_neither() {
    assert_eq!(
        delivery_from(
            env_of(&[
                ("APPIMAGE", "/home/a/Demo.AppImage"),
                ("APPDIR", "/tmp/.mount_Demo")
            ]),
            false
        ),
        Delivery::AppImage {
            path: "/home/a/Demo.AppImage".into(),
            mount: Some("/tmp/.mount_Demo".into()),
        }
    );
    assert_eq!(
        delivery_from(env_of(&[("FLATPAK_ID", "dev.example.Demo")]), true),
        Delivery::Flatpak {
            app_id: "dev.example.Demo".into()
        }
    );
    assert_eq!(delivery_from(env_of(&[]), false), Delivery::Unpackaged);
    assert_eq!(
        delivery_from(env_of(&[("APPIMAGE", "")]), false),
        Delivery::Unpackaged
    );
}

#[test]
fn fr35_a_flatpak_never_offers_to_update_itself() {
    // Even an AppImage variable leaking into the sandbox does not make it one.
    let inside = delivery_from(
        env_of(&[
            ("FLATPAK_ID", "dev.example.Demo"),
            ("APPIMAGE", "/x.AppImage"),
        ]),
        true,
    );
    let error = inside.appimage_updater().unwrap_err();
    assert!(
        matches!(error, UpdateError::ManagedByStore { .. }),
        "{error}"
    );
    assert!(error.to_string().contains("store"), "{error}");

    let error = Delivery::Unpackaged.appimage_updater().unwrap_err();
    assert!(matches!(error, UpdateError::NotAnAppImage), "{error}");
}

#[test]
fn fr35_the_bundled_updater_wins_over_path_and_the_override_wins_over_both() {
    let dir = scratch("find_tool");
    let mount = dir.join("mount");
    let bin = dir.join("bin");
    std::fs::create_dir_all(mount.join(BUNDLED_UPDATER).parent().unwrap()).unwrap();
    std::fs::write(mount.join(BUNDLED_UPDATER), b"").unwrap();
    std::fs::create_dir_all(&bin).unwrap();
    std::fs::write(bin.join("appimageupdatetool"), b"").unwrap();
    let path = std::env::join_paths([&bin]).unwrap();

    assert_eq!(
        find_tool(None, Some(&mount), Some(&path)),
        Some(mount.join(BUNDLED_UPDATER))
    );
    assert_eq!(
        find_tool(None, Some(&dir.join("elsewhere")), Some(&path)),
        Some(bin.join("appimageupdatetool"))
    );
    assert_eq!(
        find_tool(Some("/opt/tool".as_ref()), Some(&mount), Some(&path)),
        Some(PathBuf::from("/opt/tool"))
    );
    assert_eq!(find_tool(None, None, None), None);
}

#[test]
fn fr35_an_appimage_without_update_information_cannot_be_updated() {
    let dir = scratch("no_information");
    let appimage = dir.join("Demo.AppImage");
    std::fs::write(&appimage, elf(true, false, &[(".upd_info", &[0; 1024])])).unwrap();
    let error = AppImageUpdater::with_tool(&appimage, Path::new("/bin/true")).unwrap_err();
    assert!(matches!(error, UpdateError::NoUpdateInformation), "{error}");
    assert_eq!(update_information_of(&appimage).unwrap(), None);
}

#[cfg(unix)]
mod with_a_fake_tool {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    /// Answers like `appimageupdatetool`: 1 from a check while `new` differs from the
    /// target, 0 once they match, and `--overwrite` copies `new` over the target.
    const FAKE_TOOL: &str = r#"#!/bin/sh
here="$(dirname "$0")"
echo "APPDIR=${APPDIR:-} APPIMAGE=${APPIMAGE:-} $*" >> "$here/calls.log"
case "$1" in
  --check-for-update) cmp -s "$here/new" "$2" && exit 0 || exit 1 ;;
  --overwrite) cp "$here/new" "$2" && exit 0 ;;
esac
echo "unexpected arguments: $*" >&2
exit 2
"#;

    fn setup(test: &str) -> (PathBuf, PathBuf, PathBuf) {
        let dir = scratch(test);
        let tooldir = dir.join("appimageupdatetool");
        std::fs::create_dir_all(&tooldir).unwrap();
        let tool = tooldir.join("AppRun");
        std::fs::write(&tool, FAKE_TOOL).unwrap();
        std::fs::set_permissions(&tool, std::fs::Permissions::from_mode(0o755)).unwrap();
        let appimage = dir.join("Demo.AppImage");
        std::fs::write(
            &appimage,
            elf(
                true,
                false,
                &[(".upd_info", &upd_info(LOCAL)), (".v", b"1")],
            ),
        )
        .unwrap();
        std::fs::write(
            tooldir.join("new"),
            elf(
                true,
                false,
                &[(".upd_info", &upd_info(LOCAL)), (".v", b"2")],
            ),
        )
        .unwrap();
        (dir, appimage, tool)
    }

    #[test]
    fn fr35_check_then_apply_replaces_the_appimage_with_the_published_one() {
        let (_dir, appimage, tool) = setup("check_apply");
        let updater = AppImageUpdater::with_tool(&appimage, &tool).unwrap();
        assert_eq!(updater.update_information().to_string(), LOCAL);
        assert_eq!(updater.check().unwrap(), UpdateCheck::Available);
        updater.apply().unwrap();
        assert_eq!(
            std::fs::read(&appimage).unwrap(),
            std::fs::read(tool.parent().unwrap().join("new")).unwrap()
        );
        assert_eq!(updater.check().unwrap(), UpdateCheck::UpToDate);

        // The tool sees its own directory as APPDIR and never the application's AppImage.
        let calls = std::fs::read_to_string(tool.parent().unwrap().join("calls.log")).unwrap();
        for line in calls.lines() {
            assert!(
                line.starts_with(&format!(
                    "APPDIR={} APPIMAGE= ",
                    tool.parent().unwrap().display()
                )),
                "{line}"
            );
        }
    }

    #[test]
    fn fr35_a_failing_updater_is_reported_with_its_output() {
        let (_dir, appimage, tool) = setup("failure");
        std::fs::write(
            &tool,
            "#!/bin/sh\necho 'signature does not match' >&2\nexit 3\n",
        )
        .unwrap();
        let updater = AppImageUpdater::with_tool(&appimage, &tool).unwrap();
        for error in [updater.check().unwrap_err(), updater.apply().unwrap_err()] {
            match &error {
                UpdateError::UpdaterFailed { code, output } => {
                    assert_eq!(*code, Some(3));
                    assert!(output.contains("signature does not match"), "{output}");
                }
                other => panic!("expected UpdaterFailed, got {other}"),
            }
        }
    }
}
