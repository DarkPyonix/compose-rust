//! Staging a package directory from a real sample, through the public API.

use std::path::{Path, PathBuf};

use dioxus_compose_msix::{
    AppMetadata, Arch, Channel, PackageVersion, Payload, Plan, assets, stage,
};

fn scratch(name: &str) -> PathBuf {
    let dir = Path::new(env!("CARGO_TARGET_TMPDIR"))
        .join("msix")
        .join(name);
    if dir.exists() {
        std::fs::remove_dir_all(&dir).unwrap();
    }
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn notepad() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../samples/notepad")
}

fn plan(meta: AppMetadata, payload: Payload) -> Plan {
    let version = PackageVersion::from_semver(&meta.version, Channel::Store, None).unwrap();
    Plan {
        meta,
        version,
        channel: Channel::Store,
        arch: Arch::X64,
        payload,
        icon: None,
    }
}

#[test]
fn fr35_a_single_executable_is_staged_with_manifest_and_images() {
    let dir = scratch("single");
    let exe = dir.join("sample-notepad.exe");
    std::fs::write(&exe, b"MZ not really").unwrap();
    let meta = AppMetadata::load(&notepad()).unwrap();
    let p = plan(meta, Payload::Executable(exe));
    let layout = dir.join("layout");
    let warnings = stage(&p, &layout).unwrap();

    assert!(layout.join("sample-notepad.exe").is_file());
    let manifest = std::fs::read_to_string(layout.join("AppxManifest.xml")).unwrap();
    assert!(
        manifest.contains("Executable=\"sample-notepad.exe\""),
        "{manifest}"
    );
    assert!(manifest.contains("Version=\"0.1.0.0\""), "{manifest}");
    assert!(manifest.contains("<DisplayName>Notepad</DisplayName>"));

    for spec in assets::ALL {
        let bytes = std::fs::read(layout.join("Assets").join(spec.file)).unwrap();
        let img = assets::Rgba::decode(&bytes).unwrap();
        assert_eq!(
            (img.width, img.height),
            (spec.width, spec.height),
            "{}",
            spec.file
        );
    }
    // The samples draw 64 pixel icons and the derived identity is not the Store's, so
    // both are reported rather than passed over.
    assert!(
        warnings.iter().any(|w| w.contains("enlarged")),
        "{warnings:?}"
    );
    assert!(
        warnings.iter().any(|w| w.contains("Partner Center")),
        "{warnings:?}"
    );
}

#[test]
fn fr35_a_payload_directory_is_copied_whole() {
    let dir = scratch("directory");
    let payload = dir.join("payload");
    std::fs::create_dir_all(payload.join("bin")).unwrap();
    std::fs::create_dir_all(payload.join("lib").join("fonts")).unwrap();
    std::fs::write(payload.join("bin").join("sample-notepad.exe"), b"MZ").unwrap();
    std::fs::write(payload.join("bin").join("renderer.dll"), b"MZ").unwrap();
    std::fs::write(
        payload
            .join("lib")
            .join("fonts")
            .join("fontconfig.properties"),
        b"x",
    )
    .unwrap();

    let meta = AppMetadata::load(&notepad()).unwrap();
    let p = plan(
        meta,
        Payload::Directory {
            root: payload,
            executable: Path::new("bin").join("sample-notepad.exe"),
        },
    );
    let layout = dir.join("layout");
    stage(&p, &layout).unwrap();
    assert!(layout.join("bin").join("renderer.dll").is_file());
    assert!(
        layout
            .join("lib")
            .join("fonts")
            .join("fontconfig.properties")
            .is_file()
    );
    let manifest = std::fs::read_to_string(layout.join("AppxManifest.xml")).unwrap();
    assert!(
        manifest.contains("Executable=\"bin\\sample-notepad.exe\""),
        "{manifest}"
    );
}

#[test]
fn fr35_a_payload_that_already_has_a_manifest_is_refused() {
    let dir = scratch("reserved");
    let payload = dir.join("payload");
    std::fs::create_dir_all(&payload).unwrap();
    std::fs::write(payload.join("app.exe"), b"MZ").unwrap();
    std::fs::write(payload.join("AppxManifest.xml"), b"<x/>").unwrap();
    let meta = AppMetadata::load(&notepad()).unwrap();
    let p = plan(
        meta,
        Payload::Directory {
            root: payload,
            executable: "app.exe".into(),
        },
    );
    let err = stage(&p, &dir.join("layout")).unwrap_err();
    assert!(err.to_string().contains("AppxManifest.xml"), "{err}");
}

#[test]
fn fr35_a_missing_executable_is_refused() {
    let dir = scratch("missing");
    let meta = AppMetadata::load(&notepad()).unwrap();
    let p = plan(meta, Payload::Executable(dir.join("nothing.exe")));
    assert!(stage(&p, &dir.join("layout")).is_err());
}

#[test]
fn fr35_workspace_version_is_followed() {
    let root = scratch("workspace");
    let project = root.join("app");
    std::fs::create_dir_all(&project).unwrap();
    std::fs::write(
        root.join("Cargo.toml"),
        "[workspace]\nmembers = [\"app\"]\n[workspace.package]\nversion = \"3.2.1\"\n",
    )
    .unwrap();
    std::fs::write(
        project.join("Cargo.toml"),
        "[package]\nname = \"app\"\nversion.workspace = true\n",
    )
    .unwrap();
    std::fs::write(
        project.join("Dioxus.toml"),
        "[application]\nname = \"App\"\n[bundle]\nidentifier = \"dev.example.app\"\npublisher = \"Example\"\n",
    )
    .unwrap();
    let m = AppMetadata::load(&project).unwrap();
    assert_eq!(m.version, "3.2.1");
    assert_eq!(m.executable, "app.exe");
    assert!(m.icon.is_none());
}

#[test]
fn fr35_every_sample_stages() {
    let samples = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../samples");
    let dir = scratch("every");
    let mut staged = 0;
    for entry in std::fs::read_dir(&samples).unwrap() {
        let project = entry.unwrap().path();
        if !project.join("Dioxus.toml").is_file() {
            continue;
        }
        let meta = AppMetadata::load(&project).unwrap();
        let exe = dir.join(&meta.executable);
        std::fs::write(&exe, b"MZ").unwrap();
        let name = project.file_name().unwrap().to_owned();
        let p = plan(meta, Payload::Executable(exe));
        stage(&p, &dir.join(name)).unwrap();
        staged += 1;
    }
    assert!(staged >= 10, "only {staged} samples staged");
}
