//! Metadata generation for the Linux packages: from `Dioxus.toml` to a desktop entry, an
//! AppStream file, icons, an AppDir, AppImage update information and a Flatpak manifest.
//!
//! Whether the generated files are accepted by the tools that consume them
//! (`desktop-file-validate`, `appstreamcli validate`, `appimagetool`, `flatpak-builder`
//! and Flathub's linter) is checked by `.github/workflows/packager-linux.yml`, which runs
//! them. These tests pin what the files say.

use std::path::{Path, PathBuf};

use packager_linux::appimage::{
    AppDirInputs, UPDATER_DIR, UpdateChannel, appimage_arch, appimage_file_name, apprun,
    assemble_appdir, file_safe,
};
use packager_linux::appstream::{appdata_file_name, metainfo, metainfo_file_name};
use packager_linux::desktop::{desktop_entry, desktop_file_name};
use packager_linux::flatpak::{
    DEFAULT_RUNTIME_VERSION, FlatpakOptions, Json, Payload, SourceRef, finish_args, manifest,
    shell_quote,
};
use packager_linux::icons::{Icon, IconKind, best, check_for_flathub, png_size};
use packager_linux::metadata::{check_app_id, check_screenshot_url, paragraphs, release_date};
use packager_linux::{AppMetadata, BuildFacts};

const DIOXUS_TOML: &str = r##"
[application]
name = "Calculator"
asset_dir = "assets"

[bundle]
identifier = "dev.example.Calculator"
publisher = "Example & Sons"
short_description = "A calculator built with compose-rust."
long_description = """
Dense button grids, nested Row and Column,
keyboard input.

Memory keys that <really> remember."""
icon = ["assets/icon-256.png"]

[macos]
bundle_name = "Calculator"

[linux]
categories = ["Utility", "Calculator"]
keywords = ["math", "arithmetic"]
flatpak_permissions = ["--share=network"]

[linux.store]
exec = "sample-calculator"
license = "Apache-2.0"
homepage = "https://example.dev/calculator"
bugtracker = "https://example.dev/calculator/issues"
brand_light = "#e8f0ff"
brand_dark = "#1b2a4a"
screenshots = [{ url = "https://example.dev/shot.png", caption = "The keypad" }]
"##;

fn facts() -> BuildFacts {
    BuildFacts {
        version: Some("1.2.3".into()),
        date: "2026-10-03".into(),
        exec: None,
    }
}

fn meta() -> AppMetadata {
    AppMetadata::resolve(DIOXUS_TOML, &[], &facts()).unwrap()
}

fn resolve_error(text: &str, overlays: &[&str]) -> String {
    AppMetadata::resolve(text, overlays, &facts())
        .unwrap_err()
        .to_string()
}

#[test]
fn fr35_metadata_comes_from_dioxus_toml() {
    let meta = meta();
    assert_eq!(meta.id, "dev.example.Calculator");
    assert_eq!(meta.name, "Calculator");
    // A summary is a fragment, not a sentence.
    assert_eq!(meta.summary, "A calculator built with compose-rust");
    assert_eq!(
        meta.description,
        vec![
            "Dense button grids, nested Row and Column, keyboard input.".to_owned(),
            "Memory keys that <really> remember.".to_owned(),
        ]
    );
    assert_eq!(meta.exec, "sample-calculator");
    assert_eq!(meta.version, "1.2.3");
    assert_eq!(meta.developer_name, "Example & Sons");
    assert_eq!(meta.developer_id, "dev.example");
    assert_eq!(meta.metadata_license, "CC0-1.0");
    assert_eq!(meta.categories, ["Utility", "Calculator"]);
    assert_eq!(meta.finish_args, ["--share=network"]);
    assert_eq!(meta.icons, ["assets/icon-256.png"]);
}

#[test]
fn fr35_dx_linux_keys_override_the_shared_bundle_table() {
    let overlay = r#"
[linux]
identifier = "dev.example.CalculatorLinux"
short_description = "Sums, on Linux"
publisher = "Linux Desk"
"#;
    let meta = AppMetadata::resolve(DIOXUS_TOML, &[overlay], &facts()).unwrap();
    assert_eq!(meta.id, "dev.example.CalculatorLinux");
    assert_eq!(meta.summary, "Sums, on Linux");
    assert_eq!(meta.developer_name, "Linux Desk");
}

#[test]
fn fr35_an_overlay_adds_what_the_application_file_lacks() {
    // The shape of a sample's own file: nothing Linux-specific at all.
    let bare = r#"
[application]
name = "Todo"
[bundle]
identifier = "dev.example.Todo"
publisher = "Example"
short_description = "Things to do."
"#;
    let overlay = r#"
[linux]
categories = ["Office"]
[linux.store]
exec = "sample-todo"
license = "Apache-2.0"
homepage = "https://example.dev/"
release_notes = ["Now updates itself."]
"#;
    let meta = AppMetadata::resolve(bare, &[overlay], &facts()).unwrap();
    assert_eq!(meta.exec, "sample-todo");
    assert_eq!(meta.release_notes, ["Now updates itself."]);
    // Without a long description the summary stands in, as a sentence.
    assert_eq!(meta.description, ["Things to do."]);
}

#[test]
fn fr35_every_sample_resolves_with_an_overlay() {
    let samples = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../samples");
    let overlay = r#"
[linux]
categories = ["Utility"]
[linux.store]
exec = "sample"
license = "Apache-2.0"
homepage = "https://github.com/darkpyonix/compose-rust"
"#;
    let mut seen = 0;
    for entry in std::fs::read_dir(&samples).unwrap() {
        let path = entry.unwrap().path().join("Dioxus.toml");
        let Ok(text) = std::fs::read_to_string(&path) else {
            continue;
        };
        let meta = AppMetadata::resolve(&text, &[overlay], &facts())
            .unwrap_or_else(|e| panic!("{}: {e}", path.display()));
        assert!(meta.id.starts_with("dev.darkpyonix."), "{}", meta.id);
        seen += 1;
    }
    assert!(seen >= 4, "found only {seen} samples");
}

#[test]
fn fr35_missing_store_facts_name_the_key_to_set() {
    let without = |key: &str| {
        DIOXUS_TOML
            .lines()
            .filter(|line| !line.starts_with(key))
            .collect::<Vec<_>>()
            .join("\n")
    };
    assert!(resolve_error(&without("license"), &[]).contains("[linux.store] license"));
    assert!(resolve_error(&without("homepage"), &[]).contains("[linux.store] homepage"));
    assert!(resolve_error(&without("identifier"), &[]).contains("[bundle] identifier"));
    assert!(resolve_error(&without("exec"), &[]).contains("--exec"));
    assert!(resolve_error(&without("categories"), &[]).contains("main category"));

    let no_version = AppMetadata::resolve(
        DIOXUS_TOML,
        &[],
        &BuildFacts {
            version: None,
            ..facts()
        },
    )
    .unwrap_err();
    assert!(no_version.to_string().contains("--version"), "{no_version}");
}

#[test]
fn fr35_bad_values_are_refused_before_any_file_is_written() {
    for (overlay, expected) in [
        ("[linux.store]\nhomepage = \"example.dev\"", "http(s) URL"),
        ("[linux.store]\nbrand_light = \"blue\"", "#rrggbb"),
        ("[linux.store]\nexec = \"bin/app\"", "bare file name"),
        ("[linux.store]\nexec = \"my app\"", "bare file name"),
        (
            "[linux.store.content_rating]\nviolence-cartoon = \"lots\"",
            "none, mild, moderate or intense",
        ),
        ("[linux]\nmime_types = [\"text\"]", "not a MIME type"),
        ("[linux.store]\nunknown_key = 1", "unknown_key"),
    ] {
        let message = resolve_error(DIOXUS_TOML, &[overlay]);
        assert!(message.contains(expected), "{overlay}: {message}");
    }
    let bad_date = AppMetadata::resolve(
        DIOXUS_TOML,
        &[],
        &BuildFacts {
            date: "3 Oct 2026".into(),
            ..facts()
        },
    )
    .unwrap_err();
    assert!(bad_date.to_string().contains("YYYY-MM-DD"));
}

#[test]
fn fr35_application_ids_follow_flatpak_and_dbus_rules() {
    for good in [
        "dev.darkpyonix.composerust.samples.calculator",
        "org.example.My_App",
        "org.example.my-app",
    ] {
        check_app_id(good).unwrap_or_else(|e| panic!("{good}: {e}"));
    }
    for bad in [
        "calculator",
        "dev.example",
        "dev..example.App",
        "dev.example.1App",
        "dev.ex-ample.App",
        "dev.example.App!",
    ] {
        assert!(check_app_id(bad).is_err(), "{bad}");
    }
}

#[test]
fn fr35_desktop_entry_names_the_icon_and_executable() {
    let meta = meta();
    assert_eq!(desktop_file_name(&meta), "dev.example.Calculator.desktop");
    assert_eq!(
        desktop_entry(&meta),
        "[Desktop Entry]\n\
         Type=Application\n\
         Version=1.5\n\
         Name=Calculator\n\
         Comment=A calculator built with compose-rust\n\
         Exec=sample-calculator\n\
         Icon=dev.example.Calculator\n\
         Terminal=false\n\
         Categories=Utility;Calculator;\n\
         Keywords=math;arithmetic;\n"
    );
}

#[test]
fn fr35_desktop_entry_escapes_what_the_format_reserves() {
    let mut meta = meta();
    meta.name = "Back\\slash\nNew".into();
    meta.keywords = vec!["semi;colon".into()];
    meta.startup_wm_class = Some("sample-calculator".into());
    let entry = desktop_entry(&meta);
    assert!(entry.contains("Name=Back\\\\slash\\nNew\n"), "{entry}");
    assert!(entry.contains("Keywords=semi\\;colon;\n"), "{entry}");
    assert!(
        entry.contains("StartupWMClass=sample-calculator\n"),
        "{entry}"
    );
}

#[test]
fn fr35_metainfo_carries_what_flathub_requires() {
    let meta = meta();
    assert_eq!(
        metainfo_file_name(&meta),
        "dev.example.Calculator.metainfo.xml"
    );
    assert_eq!(
        appdata_file_name(&meta),
        "dev.example.Calculator.appdata.xml"
    );
    let xml = metainfo(&meta);
    for expected in [
        "<component type=\"desktop-application\">",
        "<id>dev.example.Calculator</id>",
        "<metadata_license>CC0-1.0</metadata_license>",
        "<project_license>Apache-2.0</project_license>",
        "<summary>A calculator built with compose-rust</summary>",
        "<developer id=\"dev.example\">",
        "<name>Example &amp; Sons</name>",
        "<p>Memory keys that &lt;really&gt; remember.</p>",
        "<launchable type=\"desktop-id\">dev.example.Calculator.desktop</launchable>",
        "<url type=\"homepage\">https://example.dev/calculator</url>",
        "<url type=\"bugtracker\">https://example.dev/calculator/issues</url>",
        "<screenshot type=\"default\">",
        "<image>https://example.dev/shot.png</image>",
        "<caption>The keypad</caption>",
        "<color type=\"primary\" scheme_preference=\"light\">#e8f0ff</color>",
        "<color type=\"primary\" scheme_preference=\"dark\">#1b2a4a</color>",
        "<content_rating type=\"oars-1.1\"/>",
        "<binary>sample-calculator</binary>",
        "<release version=\"1.2.3\" date=\"2026-10-03\"/>",
    ] {
        assert!(xml.contains(expected), "missing {expected} in\n{xml}");
    }
    assert!(xml.starts_with("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"));
    assert!(xml.ends_with("</component>\n"));
}

#[test]
fn fr35_metainfo_writes_release_notes_and_content_rating() {
    let overlay = r#"
[linux.store]
release_notes = ["Updates itself & more."]
[linux.store.content_rating]
social-chat = "intense"
"#;
    let xml = metainfo(&AppMetadata::resolve(DIOXUS_TOML, &[overlay], &facts()).unwrap());
    assert!(
        xml.contains(
            "<release version=\"1.2.3\" date=\"2026-10-03\">\n      <description>\n        <p>Updates itself &amp; more.</p>"
        ),
        "{xml}"
    );
    assert!(
        xml.contains("<content_attribute id=\"social-chat\">intense</content_attribute>"),
        "{xml}"
    );
}

fn png(size: u32) -> Vec<u8> {
    let mut bytes = b"\x89PNG\r\n\x1a\n\0\0\0\x0dIHDR".to_vec();
    bytes.extend_from_slice(&size.to_be_bytes());
    bytes.extend_from_slice(&size.to_be_bytes());
    bytes.extend_from_slice(&[8, 6, 0, 0, 0]);
    bytes
}

#[test]
fn fr35_icons_go_where_their_real_size_says() {
    assert_eq!(png_size(&png(256)), Some((256, 256)));
    let icon = Icon::classify(Path::new("whatever.png"), &png(256)).unwrap();
    assert_eq!(icon.kind, IconKind::Png(256));
    assert_eq!(
        icon.install_path("dev.example.App"),
        "share/icons/hicolor/256x256/apps/dev.example.App.png"
    );
    let svg = Icon::classify(Path::new("logo.svg"), b"<svg xmlns='x'/>").unwrap();
    assert_eq!(
        svg.install_path("dev.example.App"),
        "share/icons/hicolor/scalable/apps/dev.example.App.svg"
    );
    assert_eq!(best(&[icon.clone(), svg.clone()]), Some(&svg));

    let mut wide = png(64);
    wide[16..20].copy_from_slice(&128u32.to_be_bytes());
    assert!(
        Icon::classify(Path::new("w.png"), &wide)
            .unwrap_err()
            .0
            .contains("square")
    );
    assert!(Icon::classify(Path::new("odd.png"), &png(100)).is_err());
    assert!(Icon::classify(Path::new("x.bmp"), b"BM....").is_err());
}

#[test]
fn fr35_flathub_needs_a_large_enough_icon() {
    let small = Icon::classify(Path::new("64.png"), &png(64)).unwrap();
    let large = Icon::classify(Path::new("128.png"), &png(128)).unwrap();
    assert!(
        check_for_flathub(std::slice::from_ref(&small))
            .unwrap_err()
            .0
            .contains("128")
    );
    check_for_flathub(&[small.clone(), large.clone()]).unwrap();
    assert!(check_for_flathub(&[]).is_err());
    assert!(check_for_flathub(&[large.clone(), large]).is_err());
}

#[test]
fn fr35_appimage_update_information_points_at_zsync_files() {
    let meta = meta();
    assert_eq!(appimage_arch("x86_64"), Some("x86_64"));
    assert_eq!(appimage_arch("aarch64"), Some("aarch64"));
    assert_eq!(appimage_arch("riscv64"), None);
    assert_eq!(
        appimage_file_name(&meta, "x86_64"),
        "Calculator-1.2.3-x86_64.AppImage"
    );
    assert_eq!(file_safe("My App: 2"), "My_App__2");

    let github = UpdateChannel::github("darkpyonix/compose-rust").unwrap();
    assert_eq!(
        github.update_information(&meta, "x86_64"),
        "gh-releases-zsync|darkpyonix|compose-rust|latest|Calculator-*-x86_64.AppImage.zsync"
    );
    // The pattern matches the file name of every later version.
    let pattern = "Calculator-*-aarch64.AppImage.zsync";
    assert!(
        github
            .update_information(&meta, "aarch64")
            .ends_with(pattern)
    );

    let local =
        UpdateChannel::zsync("http://127.0.0.1:8080/Calculator-x86_64.AppImage.zsync").unwrap();
    assert_eq!(
        local.update_information(&meta, "x86_64"),
        "zsync|http://127.0.0.1:8080/Calculator-x86_64.AppImage.zsync"
    );

    for bad in ["darkpyonix", "a/b/c", "a|b/c", "/repo"] {
        assert!(UpdateChannel::github(bad).is_err(), "{bad}");
    }
    for bad in [
        "ftp://x/y.zsync",
        "https://x/y.AppImage",
        "https://x/a|b.zsync",
    ] {
        assert!(UpdateChannel::zsync(bad).is_err(), "{bad}");
    }
}

#[test]
fn fr35_apprun_starts_the_payload_from_the_mount_point() {
    let script = apprun(&meta());
    assert!(script.starts_with("#!/bin/sh\n"));
    assert!(script.contains("APPDIR=\"${APPDIR:-$(dirname \"$(readlink -f \"$0\")\")}\"\n"));
    assert!(script.contains(
        "LD_LIBRARY_PATH=\"$APPDIR/usr/lib/sample-calculator/lib:$APPDIR/usr/lib/sample-calculator${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"\nexport LD_LIBRARY_PATH\n"
    ));
    assert!(
        script.ends_with("exec \"$APPDIR/usr/lib/sample-calculator/sample-calculator\" \"$@\"\n")
    );
}

fn scratch(test: &str) -> PathBuf {
    let dir = Path::new(env!("CARGO_TARGET_TMPDIR"))
        .join("packager_linux")
        .join(test);
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

#[test]
fn fr35_appdir_holds_entry_icon_metainfo_payload_and_updater() {
    let dir = scratch("appdir");
    let meta = meta();
    let payload = dir.join("payload");
    std::fs::create_dir_all(&payload).unwrap();
    std::fs::write(payload.join("sample-calculator"), b"#!/bin/sh\necho hi\n").unwrap();
    std::fs::create_dir_all(payload.join("lib")).unwrap();
    std::fs::write(
        payload.join("lib/libcompose_rust_renderer.so"),
        b"\x7fELF",
    )
    .unwrap();
    let updater = dir.join("squashfs-root");
    std::fs::create_dir_all(updater.join("usr/bin")).unwrap();
    std::fs::write(updater.join("usr/bin/appimageupdatetool"), b"tool").unwrap();
    #[cfg(unix)]
    std::os::unix::fs::symlink("usr/bin/appimageupdatetool", updater.join("AppRun")).unwrap();
    #[cfg(not(unix))]
    std::fs::write(updater.join("AppRun"), b"tool").unwrap();
    let icon_path = dir.join("icon.png");
    std::fs::write(&icon_path, png(256)).unwrap();
    let icons = vec![Icon::classify(&icon_path, &png(256)).unwrap()];

    let out = dir.join("Calculator.AppDir");
    let written = assemble_appdir(
        &AppDirInputs {
            meta: &meta,
            payload: &payload,
            icons: &icons,
            updater: Some(&updater),
        },
        &out,
    )
    .unwrap();
    for path in [
        "AppRun",
        "dev.example.Calculator.desktop",
        "dev.example.Calculator.png",
        "usr/share/applications/dev.example.Calculator.desktop",
        "usr/share/metainfo/dev.example.Calculator.appdata.xml",
        "usr/share/icons/hicolor/256x256/apps/dev.example.Calculator.png",
        "usr/lib/sample-calculator/sample-calculator",
        "usr/lib/sample-calculator/lib/libcompose_rust_renderer.so",
    ] {
        assert!(out.join(path).is_file(), "missing {path}");
    }
    assert!(written.contains(&PathBuf::from(format!("{UPDATER_DIR}/AppRun"))));
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let link = std::fs::read_link(out.join(UPDATER_DIR).join("AppRun")).unwrap();
        assert_eq!(link, Path::new("usr/bin/appimageupdatetool"));
        for executable in ["AppRun", "usr/lib/sample-calculator/sample-calculator"] {
            let mode = std::fs::metadata(out.join(executable))
                .unwrap()
                .permissions()
                .mode();
            assert_eq!(mode & 0o111, 0o111, "{executable} is not executable");
        }
    }
    assert_eq!(
        std::fs::read_to_string(out.join("dev.example.Calculator.desktop")).unwrap(),
        desktop_entry(&meta)
    );

    // Assembling over an existing directory would mix two builds.
    let again = assemble_appdir(
        &AppDirInputs {
            meta: &meta,
            payload: &payload,
            icons: &icons,
            updater: None,
        },
        &out,
    );
    assert!(again.unwrap_err().0.contains("already exists"));
}

#[test]
fn fr35_appdir_refuses_a_payload_without_the_executable() {
    let dir = scratch("appdir_missing");
    let payload = dir.join("payload");
    std::fs::create_dir_all(&payload).unwrap();
    std::fs::write(payload.join("other"), b"x").unwrap();
    let icon_path = dir.join("icon.png");
    std::fs::write(&icon_path, png(64)).unwrap();
    let icons = vec![Icon::classify(&icon_path, &png(64)).unwrap()];
    let error = assemble_appdir(
        &AppDirInputs {
            meta: &meta(),
            payload: &payload,
            icons: &icons,
            updater: None,
        },
        &dir.join("out"),
    )
    .unwrap_err();
    assert!(error.0.contains("sample-calculator"), "{error}");
}

#[test]
fn fr35_flatpak_sandbox_gets_x11_ipc_and_the_gpu() {
    let meta = meta();
    assert_eq!(
        finish_args(&meta, false),
        [
            "--share=ipc",
            "--socket=x11",
            "--device=dri",
            "--share=network"
        ]
    );
    assert_eq!(
        finish_args(&meta, true),
        [
            "--share=ipc",
            "--socket=wayland",
            "--socket=fallback-x11",
            "--device=dri",
            "--share=network"
        ]
    );
    let mut duplicate = meta.clone();
    duplicate.finish_args = vec!["--share=ipc".into()];
    assert_eq!(finish_args(&duplicate, false).len(), 3);
}

fn icons_256() -> Vec<Icon> {
    vec![Icon {
        source: "icon.png".into(),
        kind: IconKind::Png(256),
    }]
}

#[test]
fn fr35_flatpak_manifest_for_a_prebuilt_archive() {
    let meta = meta();
    let icons = icons_256();
    let text = manifest(
        &meta,
        &FlatpakOptions {
            payload: Payload::Prebuilt {
                archives: vec![
                    SourceRef::Url {
                        url: "https://example.dev/calculator-linux-x64.tar.gz".into(),
                        sha256: "ab".repeat(32),
                        arch: Some("x86_64".into()),
                    },
                    SourceRef::Url {
                        url: "https://example.dev/calculator-linux-arm64.tar.gz".into(),
                        sha256: "ef".repeat(32),
                        arch: Some("aarch64".into()),
                    },
                ],
            },
            icons: &icons,
            wayland: false,
        },
    );
    let expected_start = format!(
        "{{\n    \"id\": \"dev.example.Calculator\",\n    \"runtime\": \"org.freedesktop.Platform\",\n    \"runtime-version\": \"{DEFAULT_RUNTIME_VERSION}\",\n    \"sdk\": \"org.freedesktop.Sdk\",\n    \"command\": \"sample-calculator\",\n"
    );
    assert!(text.starts_with(&expected_start), "{text}");
    for expected in [
        "\"--socket=x11\"",
        "\"--share=ipc\"",
        "\"--device=dri\"",
        "\"type\": \"archive\"",
        "\"url\": \"https://example.dev/calculator-linux-x64.tar.gz\"",
        "\"url\": \"https://example.dev/calculator-linux-arm64.tar.gz\"",
        "\"only-arches\": [\n                        \"aarch64\"\n                    ]",
        "\"dest\": \"payload\"",
        "\"cp -a payload/. /app/lib/sample-calculator/\"",
        "\"test -x /app/lib/sample-calculator/sample-calculator\"",
        "\"ln -s ../lib/sample-calculator/sample-calculator /app/bin/sample-calculator\"",
        "\"install -Dm644 packaging/dev.example.Calculator.desktop /app/share/applications/dev.example.Calculator.desktop\"",
        "\"install -Dm644 packaging/dev.example.Calculator.metainfo.xml /app/share/metainfo/dev.example.Calculator.metainfo.xml\"",
        "\"install -Dm644 packaging/icons/256x256/dev.example.Calculator.png /app/share/icons/hicolor/256x256/apps/dev.example.Calculator.png\"",
        "\"dest\": \"packaging/icons/256x256\"",
    ] {
        assert!(text.contains(expected), "missing {expected} in\n{text}");
    }
    assert!(!text.contains("rust-stable"), "{text}");
    // An executable without an rpath is pointed at the renderer beside it.
    assert!(text.contains("\"name\": \"patchelf\""), "{text}");
    assert!(
        text.contains("readelf -d /app/lib/sample-calculator/sample-calculator | grep -qE '\\\\((RPATH|RUNPATH)\\\\)' || patchelf --set-rpath '$ORIGIN:$ORIGIN/lib' /app/lib/sample-calculator/sample-calculator"),
        "{text}"
    );
    assert!(!text.contains("cargo"), "{text}");
}

#[test]
fn fr35_flatpak_manifest_builds_from_source_offline() {
    let mut meta = meta();
    meta.runtime_version = Some("24.08".into());
    let icons = icons_256();
    let text = manifest(
        &meta,
        &FlatpakOptions {
            payload: Payload::FromSource {
                source: SourceRef::Path("source.tar.gz".into()),
                cargo_sources: "cargo-sources.json".into(),
                package: "sample-calculator".into(),
                bin: "sample-calculator".into(),
                cargo_args: vec!["--no-default-features".into(), "it's".into()],
                renderer: vec![SourceRef::Url {
                    url: "https://example.dev/renderer.tar.gz".into(),
                    sha256: "cd".repeat(32),
                    arch: None,
                }],
            },
            icons: &icons,
            wayland: true,
        },
    );
    for expected in [
        "\"runtime-version\": \"24.08\"",
        "\"sdk-extensions\": [\n        \"org.freedesktop.Sdk.Extension.rust-stable\"\n    ]",
        "\"append-path\": \"/usr/lib/sdk/rust-stable/bin\"",
        "\"CARGO_HOME\": \"/run/build/sample-calculator/cargo\"",
        "\"CARGO_NET_OFFLINE\": \"true\"",
        "\"COMPOSE_RUST_APP_VERSION\": \"1.2.3\"",
        "\"COMPOSE_RUST_RENDERER_DIR\": \"/run/build/sample-calculator/renderer\"",
        "\"path\": \"source.tar.gz\"",
        "\"cargo-sources.json\"",
        "\"dest\": \"renderer\"",
        "\"cargo --offline fetch --manifest-path Cargo.toml --verbose\"",
        "\"cargo --offline build --release --locked -p sample-calculator --bin sample-calculator --no-default-features 'it'\\\\''s'\"",
        "\"install -m755 target/release/sample-calculator /app/lib/sample-calculator/sample-calculator\"",
        "\"mkdir -p /app/lib/sample-calculator/lib && if [ -d renderer/lib ]; then cp -a renderer/lib/. /app/lib/sample-calculator/lib/; else cp -a renderer/. /app/lib/sample-calculator/lib/; fi\"",
        "\"name\": \"patchelf\"",
        "\"buildsystem\": \"autotools\"",
        "patchelf --set-rpath '$ORIGIN/lib' /app/lib/sample-calculator/sample-calculator",
        "\"--socket=wayland\"",
        "\"--socket=fallback-x11\"",
    ] {
        assert!(text.contains(expected), "missing {expected} in\n{text}");
    }
    // patchelf is built before the application that needs it.
    assert!(
        text.find("\"name\": \"patchelf\"").unwrap()
            < text.find("\"name\": \"sample-calculator\"").unwrap()
    );
    // The vendored crates come before the build that needs them.
    assert!(
        text.find("cargo --offline fetch").unwrap() < text.find("cargo --offline build").unwrap()
    );
}

#[test]
fn fr35_json_and_shell_quoting_survive_awkward_text() {
    assert_eq!(
        Json::Object(vec![(
            "k\"ey".into(),
            Json::Array(vec![Json::str("a\\b\n\u{1}"), Json::Array(vec![])])
        )])
        .render(0),
        "{\n    \"k\\\"ey\": [\n        \"a\\\\b\\n\\u0001\",\n        []\n    ]\n}"
    );
    assert_eq!(shell_quote("--features=a,b"), "--features=a,b");
    assert_eq!(shell_quote("two words"), "'two words'");
    assert_eq!(shell_quote(""), "''");
}

#[test]
fn fr35_release_dates_are_reproducible() {
    assert_eq!(release_date(None, 0), "1970-01-01");
    assert_eq!(release_date(None, 1_790_985_600), "2026-10-03");
    assert_eq!(release_date(Some("951782400"), 1_790_985_600), "2000-02-29");
    assert_eq!(release_date(Some("not a number"), 86_400), "1970-01-02");
    assert_eq!(paragraphs("a\nb\n\n\n c "), ["a b", "c"]);
}

#[test]
fn fr35_screenshots_are_pinned_to_a_tag_or_commit() {
    for good in [
        "https://raw.githubusercontent.com/DarkPyonix/darkpyonix-ember/v1.0.0/docs/shot.png",
        "https://raw.githubusercontent.com/DarkPyonix/darkpyonix-ember/refs/tags/v1.0.0/shot.png",
        "https://raw.githubusercontent.com/DarkPyonix/darkpyonix-ember/0123456789abcdef0123456789abcdef01234567/shot.png",
        "https://example.dev/shot.png",
    ] {
        check_screenshot_url(good).unwrap_or_else(|e| panic!("{good}: {e}"));
    }
    for bad in [
        "https://raw.githubusercontent.com/DarkPyonix/darkpyonix-ember/main/shot.png",
        "https://raw.githubusercontent.com/DarkPyonix/darkpyonix-ember/refs/heads/v1/shot.png",
        "https://raw.githubusercontent.com/DarkPyonix/darkpyonix-ember/shot.png",
        "ftp://example.dev/shot.png",
    ] {
        assert!(check_screenshot_url(bad).is_err(), "{bad}");
    }
    let overlay = "[linux.store]\nscreenshots = [{ url = \"https://raw.githubusercontent.com/a/b/develop/s.png\" }]";
    assert!(resolve_error(DIOXUS_TOML, &[overlay]).contains("pin it to a tag"));
}
