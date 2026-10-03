//! The rules the build script acquires the renderer by, exercised directly.
//!
//! `build/renderer_dir.rs` is included from `build.rs`, and a build script is never
//! compiled for test, so anything asserted inside it is asserted nowhere. Including the
//! same file here is what gives it coverage.
//!
//! Nothing here touches the network. The module takes its downloader as a closure for
//! exactly that reason, so every branch of the search order, including the one that
//! downloads, runs from a temporary directory in a few milliseconds.

#![allow(dead_code)]

mod renderer_dir {
    include!("../build/renderer_dir.rs");
}

use renderer_dir::elf;
use renderer_dir::sha256::{Sha256, checksum_from_sha256_file, sha256_file, sha256_hex};
use renderer_dir::{
    CACHE_DIR_ENV, Fetch, FetchError, PUBLISHED_TARGETS, RENDERER_DIR_ENV, Renderer,
    RendererSource, Request, acquire_renderer, artifact_file_name, artifact_target, artifact_url,
    cached_renderer_dir, default_cache_root, download_dir, every_failure_message,
    renderer_lib_file, renderer_lib_subdir, sibling_checksum,
};
use std::path::{Path, PathBuf};

/// The crate version the published release this test suite describes was cut for.
const SAMPLE_VERSION: &str = "0.0.0";
/// The desktop target these tests build their fixtures for. Passed in rather than taken
/// from the host, so the same assertions run identically on every machine, and Linux
/// rather than macOS because a macOS fixture would have to be a real Mach-O library for
/// the naming step to accept it. That step has a test of its own, further down, which
/// only runs where it can build one.
const SAMPLE_TARGET: &str = "linux-x64";
const SAMPLE_TARGET_OS: &str = "linux";

// -------------------------------------------------------------------------------------
// Artifact naming
// -------------------------------------------------------------------------------------

/// Every one of these has to name a file the release actually publishes, or the crate's
/// instructions send someone to a download link for something that does not exist.
///
/// Cargo and the release artifacts do not spell architectures the same way. Joining the
/// two halves raw was correct on exactly one platform, macOS, which is the platform
/// everything here was checked on.
#[test]
fn nfr4_target_names_match_the_published_artifacts() {
    assert_eq!(artifact_target("macos", "aarch64"), "macos-aarch64");
    assert_eq!(artifact_target("linux", "x86_64"), "linux-x64");
    assert_eq!(artifact_target("linux", "aarch64"), "linux-arm64");
    assert_eq!(artifact_target("windows", "x86_64"), "windows-x64");
}

/// The four names above are the four the build script will download for, and no more.
/// A target that reaches the download step with a name outside this list would ask for a
/// URL that does not exist.
#[test]
fn nfr11_the_downloadable_targets_are_the_ones_the_release_builds() {
    let built_by_the_release_workflow: Vec<String> = [
        ("macos", "aarch64"),
        ("windows", "x86_64"),
        ("linux", "x86_64"),
        ("linux", "aarch64"),
    ]
    .into_iter()
    .map(|(os, arch)| artifact_target(os, arch))
    .collect();
    assert_eq!(PUBLISHED_TARGETS, built_by_the_release_workflow);
}

/// The address the build script fetches from, spelled out once so that a change to it
/// has to be a deliberate edit to a test rather than a silent 404 on someone's machine.
#[test]
fn nfr11_the_download_address_is_the_release_for_this_crate_version() {
    let artifact = artifact_file_name("0.0.0", "macos-aarch64");
    assert_eq!(
        artifact,
        "dioxus-compose-renderer-v0.0.0-macos-aarch64.tar.gz"
    );
    assert_eq!(
        artifact_url("0.0.0", &artifact),
        "https://github.com/DarkPyonix/compose-rust/releases/download/v0.0.0/\
         dioxus-compose-renderer-v0.0.0-macos-aarch64.tar.gz"
    );
    assert_eq!(
        sibling_checksum(Path::new(&artifact)).to_string_lossy(),
        format!("{artifact}.sha256")
    );
}

// -------------------------------------------------------------------------------------
// The checksum the build script verifies with
// -------------------------------------------------------------------------------------

/// FIPS 180-4's own vectors. The build script refuses to unpack anything whose digest it
/// cannot reproduce, so a hash that is subtly wrong would refuse every artifact the
/// release publishes, and a hash that is wrong in the other direction would accept
/// anything at all.
#[test]
fn nfr11_sha256_reproduces_the_published_vectors() {
    assert_eq!(
        sha256_hex(b""),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    );
    assert_eq!(
        sha256_hex(b"abc"),
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    );
    assert_eq!(
        sha256_hex(b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    );
    // A million bytes, which is the vector that catches a broken length field: everything
    // shorter fits in a block count whose top bytes are zero.
    let mut million = Sha256::new();
    for _ in 0..1000 {
        million.update(&[b'a'; 1000]);
    }
    assert_eq!(
        million.finish_hex(),
        "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
    );
}

/// The `.sha256` files on the release are `shasum -a 256` output: the digest, two spaces,
/// the file name. Only the digest is read, because the name in the file is whatever the
/// release job passed on its command line.
#[test]
fn nfr11_the_checksum_file_is_read_as_shasum_writes_it() {
    let line = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  \
                dioxus-compose-renderer-v0.0.0-macos-aarch64.tar.gz\n";
    assert_eq!(
        checksum_from_sha256_file(line).as_deref(),
        Some("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    );
    assert_eq!(checksum_from_sha256_file(""), None);
    assert_eq!(checksum_from_sha256_file("not a digest  file"), None);
    // 63 characters. Close enough to look right in a log and wrong enough to accept a
    // different file.
    assert_eq!(
        checksum_from_sha256_file(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85  file"
        ),
        None
    );
}

// -------------------------------------------------------------------------------------
// The cache
// -------------------------------------------------------------------------------------

/// Outside `target/`, and keyed by both version and target. Inside `target/` it would be
/// re-downloaded after every `cargo clean` and duplicated for every project on the
/// machine; keyed by less than both, two crate versions or two architectures would land
/// on top of each other.
#[test]
fn nfr11_the_cache_lives_outside_target_and_is_keyed_by_version_and_target() {
    // Both names are a published contract: people set them in CI files and shell profiles,
    // so renaming one is a breaking change rather than a refactor.
    assert_eq!(RENDERER_DIR_ENV, "DIOXUS_COMPOSE_RENDERER_DIR");
    assert_eq!(CACHE_DIR_ENV, "DIOXUS_COMPOSE_CACHE_DIR");

    let unix = default_cache_root(Some(Path::new("/home/dev")), None, false).unwrap();
    assert_eq!(unix, Path::new("/home/dev/.cache/dioxus-compose"));

    let windows = default_cache_root(None, Some(Path::new("C:/Users/dev/AppData/Local")), true);
    assert_eq!(
        windows.unwrap(),
        Path::new("C:/Users/dev/AppData/Local/dioxus-compose")
    );
    // Nowhere to put it is a case with its own message, not a panic and not `target/`.
    assert_eq!(default_cache_root(None, None, false), None);

    let root = Path::new("/cache");
    assert_eq!(
        cached_renderer_dir(root, "0.1.0", "linux-x64"),
        Path::new("/cache/renderer/v0.1.0/linux-x64")
    );
    for (version, target) in [("0.1.0", "linux-arm64"), ("0.2.0", "linux-x64")] {
        assert_ne!(
            cached_renderer_dir(root, version, target),
            cached_renderer_dir(root, "0.1.0", "linux-x64"),
            "{version} {target} must not share a directory with 0.1.0 linux-x64"
        );
    }
    assert!(
        !cached_renderer_dir(root, "0.1.0", "linux-x64")
            .to_string_lossy()
            .contains("target"),
        "the cache must survive cargo clean"
    );
}

// -------------------------------------------------------------------------------------
// The search order
// -------------------------------------------------------------------------------------

/// The variable wins over everything, including a cache that is already populated and a
/// download that would otherwise happen. A build that was deliberately pointed at a
/// renderer, to test a local build of it or because the machine is air-gapped, must not
/// quietly acquire a different one.
#[test]
fn nfr10_the_renderer_dir_variable_beats_the_cache_and_the_download() {
    let temp = TempDir::new("env-wins");
    let pointed_at = renderer_tree(&temp.path().join("somewhere-else"));
    // A cache entry that would have answered, had the variable not been set.
    renderer_tree(&cached_renderer_dir(
        &temp.path().join("cache"),
        SAMPLE_VERSION,
        SAMPLE_TARGET,
    ));

    let renderer =
        acquire(&temp, Some(&pointed_at), Network::Forbidden).expect("the variable is set");
    assert_eq!(renderer.source, RendererSource::Environment);
    assert_eq!(
        renderer.lib_dir,
        pointed_at.join(renderer_lib_subdir(SAMPLE_TARGET_OS))
    );
}

/// Set but wrong is a failure, not a fallback. Falling through to the download would mean
/// a typo in the variable silently builds against something other than what it names.
#[test]
fn nfr10_a_renderer_dir_with_nothing_in_it_fails_rather_than_downloading() {
    let temp = TempDir::new("env-empty");
    let empty = temp.path().join("empty");
    std::fs::create_dir_all(&empty).unwrap();
    renderer_tree(&cached_renderer_dir(
        &temp.path().join("cache"),
        SAMPLE_VERSION,
        SAMPLE_TARGET,
    ));

    let message = acquire(&temp, Some(&empty), Network::Forbidden).expect_err("nothing is there");
    assert!(message.contains(RENDERER_DIR_ENV), "{message}");
    assert!(
        message.contains(renderer_lib_file(SAMPLE_TARGET_OS)),
        "the message has to name what it looked for: {message}"
    );
    assert!(
        message.contains("Nothing was downloaded"),
        "the message has to say why the cache and the release were not consulted: {message}"
    );
}

/// An unpacked renderer in the cache is the whole point of the cache: the second project
/// on the machine, and every build after `cargo clean`, costs no network at all.
#[test]
fn nfr11_a_cached_renderer_is_used_with_no_network() {
    let temp = TempDir::new("cache-hit");
    let cached = renderer_tree(&cached_renderer_dir(
        &temp.path().join("cache"),
        SAMPLE_VERSION,
        SAMPLE_TARGET,
    ));

    let renderer = acquire(&temp, None, Network::Forbidden).expect("the cache holds one");
    assert_eq!(renderer.source, RendererSource::Cache);
    assert_eq!(
        renderer.lib_dir,
        cached.join(renderer_lib_subdir(SAMPLE_TARGET_OS))
    );
}

/// The cache is keyed by version, so a renderer cached for another version is not this
/// version's renderer and must not be linked into it.
#[test]
fn nfr11_a_renderer_cached_for_another_version_is_not_a_cache_hit() {
    let temp = TempDir::new("cache-other-version");
    renderer_tree(&cached_renderer_dir(
        &temp.path().join("cache"),
        "0.9.9",
        SAMPLE_TARGET,
    ));

    let message = acquire(&temp, None, Network::Forbidden).expect_err("no entry for this version");
    assert!(
        message.contains(&artifact_file_name(SAMPLE_VERSION, SAMPLE_TARGET)),
        "{message}"
    );
}

/// The artifact already sitting in the download directory is unpacked without a network.
/// This is what makes the offline instructions real rather than an apology: the message
/// says to put two files somewhere, and putting them there works.
#[test]
fn nfr11_an_artifact_already_downloaded_is_verified_and_unpacked_offline() {
    let temp = TempDir::new("local-artifact");
    stage_artifact(&temp, Checksum::Correct);

    let renderer = acquire(&temp, None, Network::Forbidden).expect("the tarball is here");
    assert_eq!(renderer.source, RendererSource::LocalArtifact);
    assert!(
        renderer
            .lib_dir
            .join(renderer_lib_file(SAMPLE_TARGET_OS))
            .is_file(),
        "the renderer has to be unpacked at {}",
        renderer.lib_dir.display()
    );
}

/// The download path, with the network stubbed by a closure that copies a prepared file.
/// Everything after the transfer, which is the verification, the unpacking and the layout
/// check, is the real code.
#[test]
fn nfr11_a_downloaded_artifact_is_verified_and_unpacked() {
    let temp = TempDir::new("download");
    let prepared = build_artifact(&temp.path().join("release"), Checksum::Correct);

    let served = prepared.clone();
    let fetch = move |url: &str, destination: &Path| -> Result<(), FetchError> {
        let source = if url.ends_with(".sha256") {
            sibling_checksum(&served)
        } else {
            served.clone()
        };
        std::fs::copy(source, destination)
            .map(|_| ())
            .map_err(|error| FetchError::Failed(error.to_string()))
    };

    let renderer =
        acquire(&temp, None, Network::Allowed(&fetch)).expect("the release serves this target");
    assert_eq!(renderer.source, RendererSource::Download);
    assert!(
        renderer
            .lib_dir
            .join(renderer_lib_file(SAMPLE_TARGET_OS))
            .is_file(),
        "the renderer has to be unpacked at {}",
        renderer.lib_dir.display()
    );

    // And the next build is a cache hit that needs nothing at all.
    let again = acquire(&temp, None, Network::Forbidden).expect("now cached");
    assert_eq!(again.source, RendererSource::Cache);
    assert_eq!(again.lib_dir, renderer.lib_dir);
}

// -------------------------------------------------------------------------------------
// Refusals
// -------------------------------------------------------------------------------------

/// A digest that does not match means the bytes are not the bytes the release published,
/// and they are not unpacked. Much the likeliest cause is a transfer that was cut short,
/// which would otherwise unpack into a renderer that fails at link time with nothing
/// pointing back at the download.
#[test]
fn nfr11_an_artifact_whose_checksum_does_not_match_is_not_unpacked() {
    let temp = TempDir::new("bad-checksum");
    let tarball = stage_artifact(&temp, Checksum::Wrong);
    let unpacked = cached_renderer_dir(&temp.path().join("cache"), SAMPLE_VERSION, SAMPLE_TARGET);

    let message = acquire(&temp, None, Network::Forbidden).expect_err("the digest is wrong");
    assert!(
        message.contains(&sha256_file(&tarball).unwrap()),
        "the message has to show what the file actually hashes to: {message}"
    );
    assert!(
        message.contains("0000000000000000000000000000000000000000000000000000000000000000"),
        "the message has to show what was expected: {message}"
    );
    assert!(
        !unpacked.exists(),
        "nothing may be unpacked at {}",
        unpacked.display()
    );
}

/// A target the release does not build is told so by name. A bare 404 cannot distinguish
/// "this platform has no renderer" from "this release is missing", and those have
/// completely different answers.
#[test]
fn nfr11_a_target_with_no_published_artifact_says_so_instead_of_downloading() {
    let temp = TempDir::new("unpublished");
    let cache = temp.path().join("cache");
    let message = acquire_for(
        "freebsd-x86_64",
        "freebsd",
        &temp,
        None,
        Network::Forbidden,
        &cache,
    )
    .expect_err("freebsd is not published");

    assert!(message.contains("freebsd-x86_64"), "{message}");
    for published in PUBLISHED_TARGETS {
        assert!(
            message.contains(published),
            "the message has to list what is published, and {published} is missing: {message}"
        );
    }
    assert!(
        !message.contains("Put these two files"),
        "this is not the offline failure and must not read like one: {message}"
    );
}

/// No network means the build names the two files and the directory to put them in. A
/// build that could only say "network error" would leave an air-gapped machine with no
/// route at all.
#[test]
fn nfr11_an_offline_build_names_the_file_and_where_to_put_it() {
    let temp = TempDir::new("offline");
    let unreachable = |_: &str, _: &Path| -> Result<(), FetchError> {
        Err(FetchError::Unreachable(
            "curl could not resolve github.com".to_string(),
        ))
    };
    let message = acquire(&temp, None, Network::Allowed(&unreachable)).expect_err("no network");

    let artifact = artifact_file_name(SAMPLE_VERSION, SAMPLE_TARGET);
    let downloads = download_dir(&temp.path().join("cache"));
    assert!(message.contains(&artifact), "{message}");
    assert!(message.contains(&format!("{artifact}.sha256")), "{message}");
    assert!(
        message.contains(&downloads.display().to_string()),
        "the message has to give the directory to put them in: {message}"
    );
    assert!(
        message.contains("curl could not resolve github.com"),
        "the message has to say what actually failed: {message}"
    );

    // And the route it describes is a route: put the files there and the build works.
    stage_artifact(&temp, Checksum::Correct);
    let renderer = acquire(&temp, None, Network::Forbidden).expect("the instructions work");
    assert_eq!(renderer.source, RendererSource::LocalArtifact);
}

/// The server answering "no such file" is a different failure from not reaching it, and
/// gets a different message. This is the one a 404 would otherwise be.
#[test]
fn nfr11_a_release_that_does_not_carry_the_artifact_is_told_apart_from_being_offline() {
    let temp = TempDir::new("not-in-release");
    let missing = |_: &str, _: &Path| -> Result<(), FetchError> { Err(FetchError::NotFound) };
    let message = acquire(&temp, None, Network::Allowed(&missing)).expect_err("not published");

    assert!(
        message.contains(&artifact_file_name(SAMPLE_VERSION, SAMPLE_TARGET)),
        "{message}"
    );
    assert!(
        message.contains("network problem"),
        "the message has to rule out the failure it is not: {message}"
    );
    assert!(
        message.contains(&format!("releases/tag/v{SAMPLE_VERSION}")),
        "the message has to say where to look: {message}"
    );
}

/// A partially transferred file is never left where the next build would read it as a
/// complete download.
#[test]
fn nfr11_an_interrupted_download_is_not_mistaken_for_a_complete_one() {
    let temp = TempDir::new("interrupted");
    let cut_short = |_: &str, destination: &Path| -> Result<(), FetchError> {
        std::fs::write(destination, b"half a tarball").unwrap();
        Err(FetchError::Failed("the connection was reset".to_string()))
    };
    acquire(&temp, None, Network::Allowed(&cut_short)).expect_err("the transfer failed");

    let downloads = download_dir(&temp.path().join("cache"));
    let left_behind: Vec<PathBuf> = std::fs::read_dir(&downloads)
        .map(|entries| entries.flatten().map(|entry| entry.path()).collect())
        .unwrap_or_default();
    assert!(
        left_behind.is_empty(),
        "a failed download must leave nothing behind, found {left_behind:?}"
    );
}

/// An artifact for the wrong platform unpacks perfectly well and contains no renderer.
/// Saying that beats an undefined-symbol dump from the linker.
#[test]
fn nfr11_an_artifact_without_a_renderer_in_it_is_reported_as_such() {
    let temp = TempDir::new("wrong-platform");
    let staging = temp.path().join("staging");
    std::fs::create_dir_all(staging.join("lib")).unwrap();
    std::fs::write(staging.join("lib").join("something-else.so"), b"not it").unwrap();
    let tarball = pack(
        &staging,
        &download_dir(&temp.path().join("cache")),
        Checksum::Correct,
        SAMPLE_TARGET,
    );

    let message = acquire(&temp, None, Network::Forbidden).expect_err("no renderer inside");
    assert!(
        message.contains(renderer_lib_file(SAMPLE_TARGET_OS)),
        "the message has to name what was missing: {message}"
    );
    assert!(
        message.contains(&tarball.display().to_string()),
        "{message}"
    );
}

/// An unpacked macOS renderer is named after the cache entry it lives in, so that an
/// application which only depends on this crate can load it.
///
/// The artifact is built with the name `@rpath/libdioxus_compose_renderer.dylib`, and
/// `@rpath` is resolved against the rpaths of whatever loaded it. Cargo hands a
/// dependency's link search paths and link libraries down to the final binary but not its
/// link arguments, and an rpath is a link argument, so the application has none. It links
/// cleanly and then dies on startup with dyld unable to find a library that is sitting in
/// the cache. This is the step that stops that, and a real Mach-O library is the only
/// thing that can prove it happened.
#[cfg(target_os = "macos")]
#[test]
fn nfr11_the_unpacked_macos_renderer_is_named_after_where_it_will_live() {
    let temp = TempDir::new("install-name");
    let staging = temp.path().join("staging");
    let lib_dir = staging.join(renderer_lib_subdir("macos"));
    std::fs::create_dir_all(&lib_dir).unwrap();

    // A real dylib, carrying the same name the release artifact carries.
    let source = temp.path().join("renderer.c");
    std::fs::write(
        &source,
        "int dioxus_compose_renderer_run(void) { return 0; }\n",
    )
    .unwrap();
    let library = lib_dir.join(renderer_lib_file("macos"));
    let built = std::process::Command::new("cc")
        // The renderer's own image is linked with room to grow its load commands, which
        // is what lets its install name be replaced with a longer one. A fixture linked
        // without that padding is rejected, correctly, so it asks for the same padding.
        .args([
            "-dynamiclib",
            "-Wl,-headerpad_max_install_names",
            "-install_name",
        ])
        .arg(format!("@rpath/{}", renderer_lib_file("macos")))
        .arg("-o")
        .arg(&library)
        .arg(&source)
        .status()
        .expect("cc comes with the tools the Rust linker already needs");
    assert!(built.success(), "building the fixture library failed");

    let cache = temp.path().join("cache");
    pack(
        &staging,
        &download_dir(&cache),
        Checksum::Correct,
        "macos-aarch64",
    );
    let renderer = acquire_for(
        "macos-aarch64",
        "macos",
        &temp,
        None,
        Network::Forbidden,
        &cache,
    )
    .expect("the tarball is here");

    let installed = renderer.lib_dir.join(renderer_lib_file("macos"));
    let name = std::process::Command::new("otool")
        .arg("-D")
        .arg(&installed)
        .output()
        .expect("otool comes with the same tools");
    let name = String::from_utf8_lossy(&name.stdout);
    let recorded = name.lines().nth(1).unwrap_or_default().trim();
    assert_eq!(
        recorded,
        installed.to_string_lossy(),
        "the unpacked library still answers to {recorded}, which no dependent can resolve"
    );
}

/// The same naming happens to a renderer that was never downloaded.
///
/// A checkout of this repository builds its own renderer and the build script finds it in
/// the workspace, which is the path everyone working here uses every day. Naming only the
/// unpacked artifact would leave that path loading the renderer by an rpath no consumer
/// has, and an rpath is exactly what hid this failure for as long as it did.
#[cfg(target_os = "macos")]
#[test]
fn nfr11_a_renderer_built_in_the_workspace_is_named_after_where_it_sits() {
    let temp = TempDir::new("workspace-name");
    let lib_dir = temp.path().join("dist/lib");
    let library = macos_renderer(&lib_dir, "@rpath/libdioxus_compose_renderer.dylib");

    let renderer = acquire_renderer(&Request {
        env_dir: None,
        workspace_lib_dir: &lib_dir,
        cache_root: Some(&temp.path().join("cache")),
        crate_version: SAMPLE_VERSION,
        target: "macos-aarch64",
        target_os: "macos",
        fetch: None,
    })
    .expect("the workspace has a renderer in it");

    assert_eq!(renderer.source, RendererSource::Workspace);
    assert_eq!(
        install_name(&library),
        library.to_string_lossy(),
        "a renderer built in the workspace still answers to a name no application can \
         resolve"
    );
}

/// And to one the variable points at, which is how a vendored or hand built renderer
/// arrives. Every route through the search order ends in the same place.
#[cfg(target_os = "macos")]
#[test]
fn nfr10_a_renderer_the_variable_points_at_is_named_after_where_it_sits() {
    let temp = TempDir::new("env-name");
    let root = temp.path().join("vendored");
    let library = macos_renderer(&root.join("lib"), "@rpath/libdioxus_compose_renderer.dylib");

    let renderer = acquire_renderer(&Request {
        env_dir: Some(&root),
        workspace_lib_dir: &temp.path().join("no-workspace/lib"),
        cache_root: Some(&temp.path().join("cache")),
        crate_version: SAMPLE_VERSION,
        target: "macos-aarch64",
        target_os: "macos",
        fetch: None,
    })
    .expect("the variable points at a renderer");

    assert_eq!(renderer.source, RendererSource::Environment);
    assert_eq!(install_name(&library), library.to_string_lossy());
}

/// A directory reached through a relative path is spelled absolutely before it becomes a
/// name.
///
/// `DIOXUS_COMPOSE_RENDERER_DIR` is routinely set to a path relative to the build, which
/// is what the sample release workflow does. The name written into the library is the name
/// every application that links it looks it up by, and a relative one would be resolved
/// against whatever directory that application happens to be started from.
#[test]
fn nfr10_a_renderer_reached_by_a_relative_path_is_named_absolutely() {
    let resolved = renderer_dir::absolute(Path::new("dist/lib"));
    assert!(
        resolved.is_absolute(),
        "{} would be resolved against the working directory of whatever runs the program",
        resolved.display()
    );
    assert!(resolved.ends_with("dist/lib"));

    let already = Path::new("/somewhere/lib");
    assert_eq!(
        renderer_dir::absolute(already),
        already,
        "an absolute path is left exactly as it was spelled, symbolic links and all"
    );
}

/// A renderer that already carries the right name is not touched.
///
/// Every build runs through this step. Rewriting sixty megabytes each time would be a
/// waste, and it would turn a renderer someone has vendored into a read-only directory
/// from something that works into a build failure. The read-only library here is the
/// assertion: a build that writes fails, and this one must not.
#[cfg(target_os = "macos")]
#[test]
fn nfr11_a_renderer_that_already_answers_to_its_location_is_left_alone() {
    let temp = TempDir::new("already-named");
    let lib_dir = temp.path().join("dist/lib");
    let library = macos_renderer(&lib_dir, "placeholder");
    let absolute = library.to_string_lossy().to_string();
    let named = std::process::Command::new("install_name_tool")
        .args(["-id", &absolute])
        .arg(&library)
        .status()
        .expect("install_name_tool comes with the tools the Rust linker needs");
    assert!(named.success());
    read_only(&library);

    let renderer = acquire_renderer(&Request {
        env_dir: None,
        workspace_lib_dir: &lib_dir,
        cache_root: Some(&temp.path().join("cache")),
        crate_version: SAMPLE_VERSION,
        target: "macos-aarch64",
        target_os: "macos",
        fetch: None,
    });

    let renderer = renderer.unwrap_or_else(|message| {
        panic!("a renderer that is already named after itself was rewritten anyway:\n{message}")
    });
    assert_eq!(renderer.source, RendererSource::Workspace);
    assert_eq!(install_name(&library), absolute);
}

// -------------------------------------------------------------------------------------
// The Linux half of the same rule: a SONAME that is the library's absolute path
// -------------------------------------------------------------------------------------

/// The renderer is published with no SONAME, and GNU ld records a library with none that
/// it found through `-l` by its bare file name. The loader then has to search for that on
/// a path an application that merely depends on this crate does not have, which is how
/// every Linux binary linking the renderer died with "cannot open shared object file".
/// Every linker copies a SONAME into `DT_NEEDED` verbatim, so naming the library after its
/// absolute path is what makes the dependents record that path.
#[test]
fn nfr11_a_linux_renderer_is_named_after_where_it_is_unpacked() {
    let temp = TempDir::new("soname");
    let staging = temp.path().join("staging");
    renderer_tree(&staging);
    let cache = temp.path().join("cache");
    pack(
        &staging,
        &download_dir(&cache),
        Checksum::Correct,
        SAMPLE_TARGET,
    );

    let before = staging
        .join(renderer_lib_subdir(SAMPLE_TARGET_OS))
        .join(renderer_lib_file(SAMPLE_TARGET_OS));
    assert_eq!(
        elf::soname(&before).unwrap(),
        None,
        "the fixture was supposed to look like the published renderer, which has no SONAME"
    );

    let renderer = acquire_for(
        SAMPLE_TARGET,
        SAMPLE_TARGET_OS,
        &temp,
        None,
        Network::Forbidden,
        &cache,
    )
    .expect("the tarball is here");

    // The name is where the library ended up, not the scratch directory it was unpacked
    // into before being moved there.
    let after = renderer.lib_dir.join(renderer_lib_file(SAMPLE_TARGET_OS));
    assert!(after.is_absolute());
    assert_eq!(
        elf::soname(&after).unwrap().as_deref(),
        Some(after.to_str().unwrap()),
        "the unpacked library does not answer to its own path, so anything linking it would \
         look for a bare file name on a search path it does not have"
    );
}

/// An artifact that was linked with a bare SONAME is renamed the same way, rather than
/// left to be recorded by that bare name.
#[test]
fn nfr11_a_linux_renderer_that_carries_a_bare_soname_is_renamed() {
    let temp = TempDir::new("bare-soname");
    let staging = temp.path().join("staging");
    renderer_tree_named(&staging, Some("libdioxus_compose_renderer.so"));
    let cache = temp.path().join("cache");
    pack(
        &staging,
        &download_dir(&cache),
        Checksum::Correct,
        SAMPLE_TARGET,
    );

    let renderer = acquire_for(
        SAMPLE_TARGET,
        SAMPLE_TARGET_OS,
        &temp,
        None,
        Network::Forbidden,
        &cache,
    )
    .expect("the tarball is here");

    let after = renderer.lib_dir.join(renderer_lib_file(SAMPLE_TARGET_OS));
    assert_eq!(
        elf::soname(&after).unwrap().as_deref(),
        Some(after.to_str().unwrap())
    );
}

/// A renderer built in the workspace or pointed at by the variable is named the same way
/// as one that was downloaded, so the route people use every day is the route consumers
/// get.
#[test]
fn nfr10_a_linux_renderer_the_variable_points_at_is_named_after_where_it_sits() {
    let temp = TempDir::new("env-soname");
    let root = renderer_tree(&temp.path().join("vendored"));

    let renderer = acquire(&temp, Some(&root), Network::Forbidden).expect("the variable is set");
    assert_eq!(renderer.source, RendererSource::Environment);

    let library = renderer.lib_dir.join(renderer_lib_file(SAMPLE_TARGET_OS));
    assert_eq!(
        elf::soname(&library).unwrap().as_deref(),
        Some(library.to_str().unwrap())
    );
}

/// A Linux renderer that already answers to its location is not written to. The read-only
/// file is the assertion: a build that writes fails, and this one must not.
#[cfg(unix)]
#[test]
fn nfr11_a_linux_renderer_that_already_answers_to_its_location_is_left_alone() {
    let temp = TempDir::new("soname-already");
    let root = renderer_tree(&temp.path().join("vendored"));
    let library = root
        .join(renderer_lib_subdir(SAMPLE_TARGET_OS))
        .join(renderer_lib_file(SAMPLE_TARGET_OS));
    assert!(elf::set_soname(&library, library.to_str().unwrap()).unwrap());
    let named = std::fs::read(&library).unwrap();
    read_only(&library);

    let renderer = acquire(&temp, Some(&root), Network::Forbidden);
    let renderer = renderer.unwrap_or_else(|message| {
        panic!("a renderer that is already named after itself was rewritten anyway:\n{message}")
    });
    assert_eq!(renderer.source, RendererSource::Environment);
    assert_eq!(std::fs::read(&library).unwrap(), named);
}

/// A renderer that cannot be named fails the build with the message that says why and
/// what to do, rather than producing binaries that link and cannot start.
#[test]
fn nfr11_a_linux_renderer_that_cannot_be_named_fails_the_build() {
    let temp = TempDir::new("soname-no-slot");
    let root = temp.path().join("vendored");
    let lib_dir = root.join(renderer_lib_subdir(SAMPLE_TARGET_OS));
    std::fs::create_dir_all(&lib_dir).unwrap();
    std::fs::write(
        lib_dir.join(renderer_lib_file(SAMPLE_TARGET_OS)),
        elf::fixture::shared_object(elf::fixture::Shape {
            spare_slots: 0,
            ..elf::fixture::Shape::default()
        }),
    )
    .unwrap();

    let message = acquire(&temp, Some(&root), Network::Forbidden)
        .expect_err("there is no slot for the SONAME");
    assert!(message.contains("SONAME"), "{message}");
    assert!(message.contains("spare slot"), "{message}");
    assert!(message.contains(RENDERER_DIR_ENV), "{message}");
}

/// "There is no SONAME" and "this file was not read" have different answers, and a file
/// that is not a shared object at all has to give the second one. Reporting it as having
/// no SONAME would let a truncated download through to the linker.
#[test]
fn nfr11_a_library_that_cannot_be_read_is_not_reported_as_having_no_soname() {
    let temp = TempDir::new("not-elf");
    let library = temp.path().join("renderer.so");
    std::fs::write(&library, b"not a shared object").unwrap();

    let failure = elf::soname(&library).expect_err("this is not an ELF file");
    assert!(
        failure.contains("renderer.so"),
        "the failure has to name the file it read: {failure}"
    );
}

// -------------------------------------------------------------------------------------
// Instructions that rot
// -------------------------------------------------------------------------------------

/// No message may tell someone to run or read a file that is not in this repository.
///
/// This is the general form of a specific fault: the failure these messages replace said
/// to run `scripts/fetch-renderer.sh`, which has never existed here. The one automatic
/// route the crate offered was a dead end, and nothing noticed, because no test had ever
/// read the message. Any message added to the build script is walked by this test, so the
/// next dead path fails here instead of on someone's first build.
#[test]
fn nfr11_no_message_names_a_file_that_is_not_in_the_repository() {
    let repo_root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("the crate sits in the repository");
    let temp = TempDir::new("messages");

    let mut messages = every_failure_message(temp.path());
    // The run-time half of the same promise: what an application with no renderer prints.
    messages.push(compose_rust::boundary::no_renderer_message());

    let mut checked = 0;
    for message in &messages {
        for token in message.split_whitespace() {
            let Some(candidate) = repository_path(token) else {
                continue;
            };
            checked += 1;
            assert!(
                repo_root.join(candidate).exists(),
                "a message names {candidate}, which is not in the repository:\n\n{message}"
            );
        }
    }
    assert!(
        checked > 0,
        "the messages named no repository file at all, so this test proved nothing"
    );
}

/// A token out of a message that is meant to be a path in this repository.
///
/// Absolute paths are someone's cache or home directory, `http` is a link, and a
/// placeholder in angle brackets is a placeholder. What is left is a relative path with a
/// source or script extension, which is a claim about this tree.
fn repository_path(token: &str) -> Option<&str> {
    let trimmed = token.trim_matches(|c: char| "`'\"(),.;:".contains(c));
    if trimmed.is_empty()
        || trimmed.starts_with("http")
        || trimmed.starts_with('/')
        || trimmed.starts_with('~')
        || trimmed.starts_with('<')
        || trimmed.starts_with('$')
        || trimmed.starts_with('%')
        || trimmed.contains('\\')
        || trimmed.contains(':')
    {
        return None;
    }
    let extension = Path::new(trimmed).extension()?.to_str()?;
    matches!(
        extension,
        "sh" | "rs" | "toml" | "yml" | "yaml" | "kt" | "kts" | "md" | "ps1"
    )
    .then_some(trimmed)
}

// -------------------------------------------------------------------------------------
// The run-time half: a build with no renderer does not pretend to have worked
// -------------------------------------------------------------------------------------

/// Whatever this test run was built with, the message an application with no renderer
/// prints has to say what is missing and how a build gets one.
#[test]
fn nfr11_the_no_renderer_message_says_what_is_missing_and_how_builds_get_one() {
    let message = compose_rust::boundary::no_renderer_message();
    assert!(message.contains("without a renderer"), "{message}");
    assert!(
        message.contains("default-features = false"),
        "the message has to name what turns the renderer off: {message}"
    );
    assert_ne!(
        compose_rust::boundary::STATUS_NO_RENDERER,
        compose_rust::boundary::STATUS_OK,
        "a build with no renderer must not report success"
    );
}

/// Starting the application with no renderer and no mock does not return success.
///
/// Only a build that really has no renderer can answer this, so it compiles in exactly
/// that configuration. `scripts/check.sh` runs the suite a second time with
/// `--no-default-features`, which is where this actually executes.
#[cfg(not(any(renderer_linked, feature = "mock-renderer")))]
#[test]
fn nfr11_launching_without_a_renderer_does_not_return_success() {
    use compose_rust::boundary::{STATUS_NO_RENDERER, STATUS_OK};
    use compose_rust::prelude::*;

    fn app() -> Element {
        rsx! { Text { text: "nothing will draw this" } }
    }

    let status = LaunchBuilder::new().try_launch(app);
    assert_ne!(
        status, STATUS_OK,
        "a binary with no renderer drew nothing, so it must not report success"
    );
    assert_eq!(status, STATUS_NO_RENDERER);
}

// -------------------------------------------------------------------------------------
// Fixtures
// -------------------------------------------------------------------------------------

/// Whether the build under test may use the network. Every test here says no, except the
/// one that stubs the transfer with a file copy.
enum Network<'a> {
    Forbidden,
    Allowed(Fetch<'a>),
}

fn acquire(temp: &TempDir, env_dir: Option<&Path>, fetch: Network) -> Result<Renderer, String> {
    let cache = temp.path().join("cache");
    acquire_for(
        SAMPLE_TARGET,
        SAMPLE_TARGET_OS,
        temp,
        env_dir,
        fetch,
        &cache,
    )
}

fn acquire_for(
    target: &str,
    target_os: &str,
    temp: &TempDir,
    env_dir: Option<&Path>,
    fetch: Network,
    cache: &Path,
) -> Result<Renderer, String> {
    // A directory that does not exist, which is what a consumer of the published crate
    // has instead of a workspace.
    let no_workspace = temp.path().join("no-workspace/lib");
    acquire_renderer(&Request {
        env_dir,
        workspace_lib_dir: &no_workspace,
        cache_root: Some(cache),
        crate_version: SAMPLE_VERSION,
        target,
        target_os,
        fetch: match fetch {
            Network::Forbidden => None,
            Network::Allowed(fetch) => Some(fetch),
        },
    })
}

/// An unpacked renderer: the directory layout the artifact has inside it.
///
/// The library is a real ELF shared object rather than a few bytes of text, because
/// acquiring a Linux renderer rewrites its dynamic section to name it after where it sits.
/// A placeholder would exercise only the error path for a file that is not an ELF at all.
fn renderer_tree(root: &Path) -> PathBuf {
    renderer_tree_named(root, None)
}

/// The same tree, with a SONAME baked into the library.
fn renderer_tree_named(root: &Path, soname: Option<&str>) -> PathBuf {
    let lib_dir = root.join(renderer_lib_subdir(SAMPLE_TARGET_OS));
    std::fs::create_dir_all(&lib_dir).unwrap();
    std::fs::write(
        lib_dir.join(renderer_lib_file(SAMPLE_TARGET_OS)),
        elf::fixture::shared_object(elf::fixture::Shape {
            soname,
            ..elf::fixture::Shape::default()
        }),
    )
    .unwrap();
    root.to_path_buf()
}

#[derive(Clone, Copy)]
enum Checksum {
    Correct,
    /// All zeroes: a digest that is the right shape and the wrong value, which is what a
    /// truncated download looks like from here.
    Wrong,
}

/// A tarball in the download directory, where a build with no network looks.
fn stage_artifact(temp: &TempDir, checksum: Checksum) -> PathBuf {
    let staging = temp.path().join("staging");
    renderer_tree(&staging);
    pack(
        &staging,
        &download_dir(&temp.path().join("cache")),
        checksum,
        SAMPLE_TARGET,
    )
}

/// The same tarball somewhere the build script will not find on its own, for the test
/// that hands it over through a stubbed download.
fn build_artifact(dir: &Path, checksum: Checksum) -> PathBuf {
    let staging = dir.join("staging");
    renderer_tree(&staging);
    pack(&staging, dir, checksum, SAMPLE_TARGET)
}

fn pack(staging: &Path, into: &Path, checksum: Checksum, target: &str) -> PathBuf {
    std::fs::create_dir_all(into).unwrap();
    let tarball = into.join(artifact_file_name(SAMPLE_VERSION, target));
    let status = std::process::Command::new("tar")
        .arg("-czf")
        .arg(&tarball)
        .arg("-C")
        .arg(staging)
        .arg(".")
        .status()
        .expect("tar is on PATH: the build script needs it too");
    assert!(status.success(), "packing the fixture failed: {status}");

    let digest = match checksum {
        Checksum::Correct => sha256_file(&tarball).unwrap(),
        Checksum::Wrong => "0".repeat(64),
    };
    std::fs::write(
        sibling_checksum(&tarball),
        format!("{digest}  {}\n", artifact_file_name(SAMPLE_VERSION, target)),
    )
    .unwrap();
    tarball
}

/// A real Mach-O library carrying the install name it is given, which is the only kind of
/// file the naming step will accept.
#[cfg(target_os = "macos")]
fn macos_renderer(lib_dir: &Path, install_name: &str) -> PathBuf {
    std::fs::create_dir_all(lib_dir).unwrap();
    let source = lib_dir.join("renderer.c");
    std::fs::write(
        &source,
        "int dioxus_compose_renderer_run(void) { return 0; }\n",
    )
    .unwrap();
    let library = lib_dir.join(renderer_lib_file("macos"));
    let built = std::process::Command::new("cc")
        // The renderer's own image is linked with room to grow its load commands, which
        // is what lets its install name be replaced with a longer one. A fixture linked
        // without that padding is rejected, correctly, so it asks for the same padding.
        .args([
            "-dynamiclib",
            "-Wl,-headerpad_max_install_names",
            "-install_name",
            install_name,
        ])
        .arg("-o")
        .arg(&library)
        .arg(&source)
        .status()
        .expect("cc comes with the tools the Rust linker already needs");
    assert!(built.success(), "building the fixture library failed");
    std::fs::remove_file(&source).unwrap();
    library
}

/// The name a Mach-O library answers to.
#[cfg(target_os = "macos")]
fn install_name(library: &Path) -> String {
    let printed = std::process::Command::new("otool")
        .arg("-D")
        .arg(library)
        .output()
        .expect("otool comes with the same tools");
    String::from_utf8_lossy(&printed.stdout)
        .lines()
        .nth(1)
        .unwrap_or_default()
        .trim()
        .to_string()
}

/// Take the write bit off, so that a step which was supposed to do nothing fails loudly
/// if it writes after all.
#[cfg(unix)]
fn read_only(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let mut permissions = std::fs::metadata(path).unwrap().permissions();
    permissions.set_mode(0o444);
    std::fs::set_permissions(path, permissions).unwrap();
}

/// A throwaway directory. Small enough not to be worth a dependency, and a dependency in
/// this crate's dev tree would be one more thing a consumer's `cargo test` has to build.
struct TempDir(PathBuf);

impl TempDir {
    fn new(label: &str) -> Self {
        use std::sync::atomic::{AtomicU32, Ordering};
        static COUNTER: AtomicU32 = AtomicU32::new(0);
        let path = std::env::temp_dir().join(format!(
            "dioxus-compose-{label}-{}-{}",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::Relaxed)
        ));
        let _ = std::fs::remove_dir_all(&path);
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }

    fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// Who resolves the renderer's symbols, per target.
///
/// The Android answer is the one this exists for. It used to fall in with iOS, because
/// the rule was written as "desktop or not", and the Host declared the desktop renderer's
/// C symbols for it. iOS really does have them by the time anything runs; Android's
/// renderer is Kotlin in ART and has no native symbol of that name, so the library could
/// not be opened at all and the application died in `onCreate` on every launch.
#[test]
fn pr5_android_does_not_declare_the_desktop_renderers_symbols() {
    use renderer_dir::{RendererLinkage, renderer_linkage};

    assert_eq!(
        renderer_linkage("android", "unix", false),
        RendererLinkage::Installed,
        "Android installs its entry points from JNI_OnLoad, so declaring them here \
         leaves a symbol the loader cannot find and the process dies before it starts"
    );
    assert_eq!(
        renderer_linkage("ios", "unix", false),
        RendererLinkage::Provided,
        "iOS links the XCFramework through Xcode, so the symbols are real by the time \
         anything runs"
    );
    for desktop in ["macos", "windows", "linux"] {
        assert_eq!(
            renderer_linkage(desktop, "unix", false),
            RendererLinkage::Linked,
            "{desktop} links the shared library through Cargo"
        );
    }
    assert_eq!(
        renderer_linkage("unknown", "wasm", false),
        RendererLinkage::Installed,
        "a browser resolves nothing at load time, so the generated web shims install the \
         entry points before anything can ask for a frame"
    );
    assert_eq!(
        renderer_linkage("macos", "unix", true),
        RendererLinkage::None,
        "a mock build draws nothing, so there is nothing to link"
    );
}

/// The Kotlin that travels is the renderer and nothing else.
///
/// No Activity and no manifest. Both are the application's, and its own tooling writes
/// them: the Activity is generated per application because the package and the library
/// name come from that application's build. A copy of this repository's own Activity
/// travelling with the renderer would put a second one into someone else's project and
/// declare a launcher twice, and holding one here is how this repository came to ship
/// sample APKs built by a route no user takes.
#[test]
fn pr5_no_application_of_ours_travels_with_the_renderer() {
    let staged = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("android-kotlin");
    assert!(
        staged.is_dir(),
        "the crate carries no Android Kotlin at {}, so an Android application has no \
         interpreter to compile",
        staged.display()
    );
    for unwanted in ["MainActivity.kt", "AndroidManifest.xml"] {
        assert!(
            !staged.join(unwanted).exists(),
            "{unwanted} is in the tree that travels to an application, and it belongs to \
             whichever application is being built rather than to the renderer"
        );
    }
    assert!(
        staged.join("shared").join("NodeTable.kt").is_file(),
        "the interpreter is missing from the staged tree, so what travels is not the \
         renderer"
    );
}

/// An APK comes out of the Dioxus CLI with nothing written about this crate.
///
/// The CLI exports where a generated Activity goes and which package it belongs to. Those
/// two values are all this crate needs to put its Kotlin into the application's own build,
/// and reading them is the difference between "add a dependency" and "add a dependency and
/// then export two variables nobody told you about".
#[test]
fn pr5_an_android_build_finds_the_gradle_source_root_from_the_cli() {
    let (root, package) = renderer_dir::android_gradle_kotlin(
        None,
        None,
        Some("/tmp/app/app/src/main/kotlin/dev/dioxus/main"),
        Some("dev.dioxus.main"),
    )
    .expect("the CLI said where its Kotlin goes");
    assert_eq!(
        root,
        std::path::Path::new("/tmp/app/app/src/main/kotlin"),
        "the CLI names the directory of one package and this crate fills a dozen, so the \
         source root is what it unpacks into"
    );
    assert_eq!(package.as_deref(), Some("dev.dioxus.main"));
}

/// Driving the build by hand wins over what the CLI said.
#[test]
fn pr5_an_android_build_prefers_the_directory_it_was_given() {
    let (root, package) = renderer_dir::android_gradle_kotlin(
        Some("/elsewhere/kotlin"),
        Some("com.example.app"),
        Some("/tmp/app/app/src/main/kotlin/dev/dioxus/main"),
        Some("dev.dioxus.main"),
    )
    .expect("a directory was named");
    assert_eq!(root, std::path::Path::new("/elsewhere/kotlin"));
    assert_eq!(package.as_deref(), Some("com.example.app"));
}

/// An ordinary `cargo build` of the crate on its own unpacks nothing.
#[test]
fn pr5_a_build_outside_an_android_project_unpacks_nothing() {
    assert!(
        renderer_dir::android_gradle_kotlin(None, None, None, None).is_none(),
        "there is no Gradle project here, and inventing a directory to write Kotlin into \
         would put it somewhere nobody asked for"
    );
}

/// Compose reaches the generated build file, both halves of it.
///
/// The compiler plugin and the libraries are two separate edits to two different blocks,
/// and an application with only one of them fails to compile with an error that names
/// neither.
#[test]
fn pr5_compose_is_added_to_a_generated_gradle_project() {
    let module = "plugins {\n    id(\"com.android.application\")\n}\n\n\
                  dependencies {\n    implementation(\"androidx.webkit:webkit:1.13.0\")\n}\n";
    let root = "buildscript {\n    dependencies {\n        \
                classpath(\"com.android.tools.build:gradle:8.7.0\")\n        \
                classpath(\"org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20\")\n    }\n}\n";
    let (module_text, root_text) =
        renderer_dir::with_compose(module, root).expect("there was something to add");
    assert!(
        module_text.contains("id(\"org.jetbrains.kotlin.plugin.compose\")"),
        "Kotlin 2.0 compiles no @Composable without this plugin:\n{module_text}"
    );
    for coordinate in renderer_dir::ANDROID_COMPOSE_DEPENDENCIES {
        assert!(
            module_text.contains(coordinate),
            "{coordinate} is missing, so the renderer's own Kotlin has nothing to \
             compile against:\n{module_text}"
        );
    }
    let root_text = root_text.expect("the plugin's jar has to reach the build's classpath");
    assert!(
        root_text.contains("compose-compiler-gradle-plugin:2.0.20"),
        "the version has to follow the one the project already pinned for Kotlin, \
         because the two are released together:\n{root_text}"
    );
    // Running twice is one build, so a project that already has all of it is left alone.
    assert!(
        renderer_dir::with_compose(&module_text, &root_text).is_none(),
        "a second pass found something to change, so every build would rewrite the file \
         and Gradle would configure again for nothing"
    );
}

/// A sample that asked for the platform's design says so on the wire.
#[test]
fn fr14_an_adaptive_sample_sends_an_adaptive_theme() {
    let theme = compose_rust::demo_theme();
    assert!(
        theme.adaptive,
        "the theme a sample launches with is fixed to {:?}, so it draws the same design \
         on every platform",
        theme.design_system
    );
}

/// On Windows the renderer has to sit beside whatever is about to run.
///
/// A DLL is found on the loader's search path and nowhere else, and the directory the
/// executable is in is on it. Telling the person building to put a directory on PATH
/// instead made adding this crate two steps rather than one, and it broke something that
/// has nothing to do with drawing: the code generator in this package links the renderer
/// only because it shares a package with it, and it died on startup before generating a
/// line.
///
/// Cargo does not tell a build script where binaries land. OUT_DIR does, three levels up,
/// and this is the arithmetic that says so.
#[test]
fn nfr11_the_profile_directory_is_three_levels_above_out_dir() {
    let out_dir = std::path::Path::new("/w/target/release/build/dioxus-compose-1a2b3c/out");
    let profile = out_dir
        .parent()
        .and_then(std::path::Path::parent)
        .and_then(std::path::Path::parent)
        .expect("a build directory has three levels above its out directory");
    assert_eq!(
        profile,
        std::path::Path::new("/w/target/release"),
        "binaries land in the profile directory, and anything else puts the renderer \
         somewhere no loader looks"
    );
    // Examples and tests land beside it rather than in it, so both are written to as well.
    assert_eq!(
        profile.join("examples"),
        std::path::Path::new("/w/target/release/examples")
    );
    assert_eq!(
        profile.join("deps"),
        std::path::Path::new("/w/target/release/deps")
    );
}

/// A window wears the picture the application registered.
///
/// Every window this project opened wore the toolkit's own icon, which on Windows is a
/// Java coffee cup and is visible in the list Task Manager draws under a process. An id
/// rather than a path or a name: a path is a fact about the machine the application was
/// built on, and a name asks the toolkit to find something it may not have.
#[test]
fn fr19_3_a_window_carries_the_icon_it_was_given() {
    use compose_rust::protocol::{BatchEncoder, Mutation};
    let window = compose_rust::schema::Window::new().with_icon(7);
    assert_eq!(window.icon, 7);
    assert_eq!(
        compose_rust::schema::Window::new().icon,
        0,
        "an application that said nothing keeps the toolkit's icon, and zero is how it \
         says nothing"
    );

    let mut encoder = BatchEncoder::with_capacity(64, 64, 4);
    encoder
        .encode(&Mutation::SetWindow(window))
        .expect("the record encodes");
    let bytes = encoder.finish().expect("the batch finishes");
    let decoded = compose_rust::protocol::decode_batch(bytes).expect("the batch decodes");
    let Some(Mutation::SetWindow(round_tripped)) = decoded.first() else {
        panic!("the batch holds something other than the window: {decoded:?}");
    };
    assert_eq!(
        round_tripped.icon, 7,
        "the id did not survive the boundary, so the renderer has nothing to dress the \
         window with"
    );
}

/// A window says what the application called it.
///
/// Every window this ever opened was listed on the desktop as "DioxusCompose", which is
/// the library's name and no application's. Windows Task Manager shows one row per
/// top-level window under the process, labelled with its title, so the row under
/// sample-todo.exe read DioxusCompose.
#[test]
fn fr19_3_a_window_carries_the_title_it_was_given() {
    use compose_rust::protocol::{BatchEncoder, Mutation};
    let window = compose_rust::schema::Window::new().with_title("Todo");
    assert_eq!(window.title, "Todo");

    let mut encoder = BatchEncoder::with_capacity(64, 64, 4);
    encoder
        .encode(&Mutation::SetWindow(window))
        .expect("the record encodes");
    let bytes = encoder.finish().expect("the batch finishes");
    let decoded = compose_rust::protocol::decode_batch(bytes).expect("the batch decodes");
    let Some(Mutation::SetWindow(round_tripped)) = decoded.first() else {
        panic!("the batch holds something other than the window: {decoded:?}");
    };
    assert_eq!(
        round_tripped.title, "Todo",
        "the title did not survive the boundary, so the renderer has nothing to name the \
         window with"
    );
}

/// A renderer from another schema is caught while there is still a build to stop.
///
/// The handshake at the first boundary call catches it too, and too late to be read
/// correctly: the program builds, starts, opens a window and draws nothing, and the report
/// that comes back is a white window rather than two artifacts that do not match. That is
/// exactly how it was reported.
#[test]
fn nfr10_a_renderer_from_another_schema_is_caught_at_build_time() {
    use renderer_dir::{SchemaAgreement, schema_agreement};

    assert_eq!(
        schema_agreement(Some("0x14b870f8e4c8b976"), Some("0x14b870f8e4c8b976\n")),
        SchemaAgreement::Same,
        "trailing whitespace from a file is not a disagreement"
    );
    assert_eq!(
        schema_agreement(Some("0X14B870F8E4C8B976"), Some("0x14b870f8e4c8b976")),
        SchemaAgreement::Same,
        "the same number written in two cases is the same number"
    );
    assert!(matches!(
        schema_agreement(Some("0x6c56ae4445f42b7f"), Some("0x14b870f8e4c8b976")),
        SchemaAgreement::Different { .. }
    ));

    // A distribution built before the hash was written beside it, or a checkout that has
    // not run codegen. Refusing to build against a renderer that might be perfectly
    // compatible would be worse than the problem being solved.
    for pair in [
        (None, Some("0x14b870f8e4c8b976")),
        (Some("0x14b870f8e4c8b976"), None),
        (Some(""), Some("0x14b870f8e4c8b976")),
    ] {
        assert_eq!(
            schema_agreement(pair.0, pair.1),
            SchemaAgreement::Unknown,
            "a missing answer is not a wrong one"
        );
    }
}

/// Nothing is measured unless a screen asked.
///
/// The first thing this requirement promises: a tree that observes nothing costs exactly
/// what it cost before observing existed. The modifier is the only thing that makes the
/// Renderer measure, so a batch that carries none of them is the proof.
#[test]
fn fr28_a_tree_that_observes_nothing_sends_no_observation() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, decode_batch};
    use compose_rust::schema::Modifier;

    fn quiet() -> Element {
        rsx! { Column { Text { text: "nothing is watching this" } } }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(quiet);
    let batch = host.rebuild().expect("the first frame failed to encode");
    let mutations = decode_batch(batch).expect("decode");
    assert!(
        !mutations.iter().any(|mutation| matches!(
            mutation,
            Mutation::SetModifier {
                modifier: Modifier::ObserveSize { .. },
                ..
            }
        )),
        "a tree nobody is observing asked the Renderer to measure something",
    );
}

/// A node that asked carries the token its screen gave it.
#[test]
fn fr28_an_observed_node_carries_its_own_token() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, decode_batch};
    use compose_rust::schema::Modifier;

    fn watched() -> Element {
        let panel = use_node_size();
        rsx! {
            Column {
                observe_size: panel.token(),
                Text { text: "this one is watched" }
            }
        }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(watched);
    let batch = host.rebuild().expect("the first frame failed to encode");
    let mutations = decode_batch(batch).expect("decode");
    let observed: Vec<_> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                modifier: Modifier::ObserveSize { token },
                ..
            } => Some(*token),
            _ => None,
        })
        .collect();
    assert_eq!(observed.len(), 1, "one node asked, so one modifier travels");
    assert_ne!(observed[0], 0, "a token of zero is the window, not a node");
}

/// A Text that says nothing about runs travels as it always did.
///
/// The requirement is explicit that the record and the path are unchanged where there
/// are no runs, because a feature nobody used must not cost every string in every screen
/// one record per node.
#[test]
fn fr26_a_text_without_runs_carries_no_run_record() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, decode_batch};
    use compose_rust::schema::PropertyKind;

    fn plain() -> Element {
        rsx! { Text { text: "nothing special about this" } }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(plain);
    let batch = host.rebuild().expect("the first frame failed to encode");
    assert!(
        !decode_batch(batch)
            .expect("decode")
            .iter()
            .any(|mutation| matches!(
                mutation,
                Mutation::SetProp {
                    property: PropertyKind::Spans,
                    ..
                }
            )),
        "a string with no runs paid a record for saying so",
    );
}

/// Runs survive the wire exactly as they were written.
#[test]
fn fr26_runs_round_trip_through_the_boundary() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
    use compose_rust::schema::PropertyKind;
    use compose_rust::spans::{TextSpan, TextSpans};

    fn marked() -> Element {
        let spans = TextSpans::new([
            TextSpan::new(0, 5).bold(),
            TextSpan::new(6, 4)
                .underline()
                .with_color(Paint::Role(ColorRole::Primary)),
        ]);
        rsx! { Text { text: "Plain link here", spans } }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(marked);
    let batch = host.rebuild().expect("the first frame failed to encode");
    let bytes = decode_batch(batch)
        .expect("decode")
        .into_iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                property: PropertyKind::Spans,
                value: PropertyValue::Bytes(bytes),
                ..
            } => Some(bytes.to_vec()),
            _ => None,
        })
        .expect("the runs did not travel");

    let decoded: Vec<_> = TextSpans::from_bytes(bytes).spans().collect();
    assert_eq!(decoded.len(), 2);
    assert_eq!((decoded[0].start, decoded[0].length), (0, 5));
    assert!(decoded[0].bold && !decoded[0].underline);
    assert!(decoded[1].underline && !decoded[1].bold);
    assert_eq!(decoded[1].color, Some(Paint::Role(ColorRole::Primary)));
}

/// The markdown convenience is a Host convenience, and leaves what it does not know.
#[test]
fn fr26_markdown_becomes_runs_and_never_crosses_the_boundary() {
    use compose_rust::spans::TextSpans;

    let (text, spans) = TextSpans::from_markdown("a **bold** and *slanted* word");
    assert_eq!(text, "a bold and slanted word");
    let runs: Vec<_> = spans.spans().collect();
    assert_eq!(runs.len(), 2);
    assert!(runs[0].bold);
    assert!(runs[1].italic);
    assert_eq!(
        &text[runs[0].start as usize..][..runs[0].length as usize],
        "bold"
    );
    assert_eq!(
        &text[runs[1].start as usize..][..runs[1].length as usize],
        "slanted"
    );

    // A marker with nothing closing it is a character the reader typed, not a marker.
    let (kept, none) = TextSpans::from_markdown("2 * 3 = 6");
    assert_eq!(kept, "2 * 3 = 6");
    assert!(none.is_empty());
}

/// Paths arrive together and come apart again as they were.
#[test]
fn fr27_two_paths_arrive_as_one_event() {
    use compose_rust::FileDrop;

    let drop = FileDrop::new("/tmp/one.txt\0/tmp/two.txt");
    assert_eq!(drop.paths(), ["/tmp/one.txt", "/tmp/two.txt"]);
}

/// A path the platform could not give as text is dropped, and the rest are delivered.
///
/// The requirement asks for this by name. One unreadable file must not lose the other
/// nine and must not end the process.
#[test]
fn fr27_an_unreadable_path_is_dropped_and_the_rest_arrive() {
    use compose_rust::FileDrop;

    let drop = FileDrop::new("/tmp/kept.txt\0\0/tmp/also-kept.txt");
    assert_eq!(drop.paths(), ["/tmp/kept.txt", "/tmp/also-kept.txt"]);
}

/// A node that said nothing about files is not a place files may be dropped.
///
/// Said as "pays nothing" rather than "sends false", because a container that is silent
/// about files is the common case: every Box, Column, Card and Surface in every screen.
#[test]
fn fr27_a_node_that_did_not_ask_is_not_a_drop_target() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, decode_batch};
    use compose_rust::schema::PropertyKind;

    fn plain() -> Element {
        rsx! { compose_rust::Box { Text { text: "not a target" } } }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(plain);
    let batch = host.rebuild().expect("the first frame failed to encode");
    let said = decode_batch(batch)
        .expect("decode")
        .into_iter()
        .filter(|mutation| {
            matches!(
                mutation,
                Mutation::SetProp {
                    property: PropertyKind::OnFilesEntered | PropertyKind::OnFilesDropped,
                    ..
                }
            )
        })
        .count();
    assert_eq!(
        said, 0,
        "a node that said nothing was offered as a drop target"
    );
}

/// The widget that exists to receive files is the one that carries the handlers.
#[test]
fn fr27_a_drop_target_carries_both_handlers() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, decode_batch};
    use compose_rust::schema::{PropertyKind, WidgetKind};

    fn target() -> Element {
        rsx! {
            FileDropTarget {
                on_files_entered: move |_| {},
                on_files_dropped: move |_| {},
                Text { text: "drop files here" }
            }
        }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(target);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    assert!(mutations.iter().any(|mutation| matches!(
        mutation,
        Mutation::Create {
            widget: WidgetKind::FileDropTarget,
            ..
        }
    )));
    for wanted in [PropertyKind::OnFilesEntered, PropertyKind::OnFilesDropped] {
        assert!(
            mutations.iter().any(|mutation| matches!(
                mutation,
                Mutation::SetProp { property, .. } if *property == wanted
            )),
            "{wanted:?} did not reach the Renderer",
        );
    }
}

/// A node says how important its changes are, and nothing about how long they take.
#[test]
fn fr24_a_motion_role_reaches_the_renderer_as_a_role() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, decode_batch};

    fn moving() -> Element {
        rsx! {
            Card { motion: MotionRole::Emphasized, Text { text: "opens" } }
        }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(moving);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    assert!(
        mutations.iter().any(|mutation| matches!(
            mutation,
            Mutation::SetModifier {
                modifier: Modifier::Motion(MotionRole::Emphasized),
                ..
            }
        )),
        "the motion role did not reach the Renderer: {mutations:?}",
    );
}

/// A node that said nothing about motion pays nothing.
#[test]
fn fr24_silence_about_motion_costs_no_record() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, decode_batch};

    fn still() -> Element {
        rsx! { Card { Text { text: "still" } } }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(still);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    assert!(!mutations.iter().any(|mutation| matches!(
        mutation,
        Mutation::SetModifier {
            modifier: Modifier::Motion(_),
            ..
        }
    )));
}

/// The five roles survive the round trip in the order the wire fixes them in.
#[test]
fn fr24_every_motion_role_survives_the_wire() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, decode_batch};

    const ROLES: [MotionRole; 5] = [
        MotionRole::Instant,
        MotionRole::Quick,
        MotionRole::Standard,
        MotionRole::Slow,
        MotionRole::Emphasized,
    ];

    fn all_five() -> Element {
        rsx! {
            Column {
                for role in ROLES {
                    Card { motion: role, Text { text: "{role:?}" } }
                }
            }
        }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(all_five);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    let arrived: Vec<MotionRole> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                modifier: Modifier::Motion(role),
                ..
            } => Some(*role),
            _ => None,
        })
        .collect();
    assert_eq!(arrived, ROLES);
}

/// A surface says what it is made of, and nothing about blur.
#[test]
fn fr23_a_material_role_reaches_the_renderer_as_a_role() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, decode_batch};

    fn sheet() -> Element {
        rsx! {
            Surface { material: MaterialRole::Regular, Text { text: "over the page" } }
        }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(sheet);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    assert!(
        mutations.iter().any(|mutation| matches!(
            mutation,
            Mutation::SetModifier {
                modifier: Modifier::Material(MaterialRole::Regular),
                ..
            }
        )),
        "the material role did not reach the Renderer: {mutations:?}",
    );
}

/// The four roles survive the round trip in the order the wire fixes them in.
#[test]
fn fr23_every_material_role_survives_the_wire() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, decode_batch};

    const ROLES: [MaterialRole; 4] = [
        MaterialRole::Thin,
        MaterialRole::Regular,
        MaterialRole::Thick,
        MaterialRole::Chrome,
    ];

    fn all_four() -> Element {
        rsx! {
            Column {
                for role in ROLES {
                    Surface { material: role, Text { text: "{role:?}" } }
                }
            }
        }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(all_four);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    let arrived: Vec<MaterialRole> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                modifier: Modifier::Material(role),
                ..
            } => Some(*role),
            _ => None,
        })
        .collect();
    assert_eq!(arrived, ROLES);
}

/// A node that said nothing about material pays nothing.
#[test]
fn fr23_silence_about_material_costs_no_record() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, decode_batch};

    fn plain() -> Element {
        rsx! { Surface { Text { text: "flat" } } }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(plain);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    assert!(!mutations.iter().any(|mutation| matches!(
        mutation,
        Mutation::SetModifier {
            modifier: Modifier::Material(_),
            ..
        }
    )));
}

/// A gradient reaches the Renderer as a registration and an id, not as a list of stops.
#[test]
fn fr23_a_gradient_is_registered_once_and_named_by_id() {
    use compose_rust::prelude::*;
    use compose_rust::protocol::{Mutation, decode_batch};
    use compose_rust::schema::{AssetKind, Color, Paint};

    fn sky() -> Element {
        let paint = brush(Brush::vertical(vec![
            Stop::new(0.0, Color::rgb(0x4a90d9)),
            Stop::new(0.5, Color::rgb(0x9ec9f0)),
            Stop::new(1.0, Color::rgb(0xffffff)),
        ]));
        rsx! { Surface { background: paint, Text { text: "over a gradient" } } }
    }

    compose_rust::window::reset_window_size();
    let mut host = compose_rust::Host::new(sky);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");

    let registration = mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::RegisterAsset {
                asset_id,
                kind: AssetKind::Brush,
                bytes,
            } => Some((*asset_id, *bytes)),
            _ => None,
        })
        .expect("the brush was never registered");
    // Header, then one record per stop.
    assert_eq!(
        registration.1.len(),
        compose_rust::brush::HEADER_LEN + 3 * compose_rust::brush::STOP_LEN,
    );

    let named = mutations.iter().any(|mutation| {
        matches!(
            mutation,
            Mutation::SetModifier { modifier: Modifier::Background(Paint::Asset(id)), .. }
                if *id == registration.0
        )
    });
    assert!(
        named,
        "the surface did not name the brush it registered: {mutations:?}"
    );
}

/// The same gradient asked for twice is one registration.
#[test]
fn fr23_the_same_brush_is_registered_once() {
    use compose_rust::prelude::*;
    use compose_rust::schema::Color;

    let first = brush(Brush::horizontal(vec![
        Stop::new(0.0, Color::rgb(0x101010)),
        Stop::new(1.0, Color::rgb(0xf0f0f0)),
    ]));
    let again = brush(Brush::horizontal(vec![
        Stop::new(0.0, Color::rgb(0x101010)),
        Stop::new(1.0, Color::rgb(0xf0f0f0)),
    ]));
    assert_eq!(first, again);
}

/// A brush travels as the two words a Paint has, whichever kind it is.
#[test]
fn fr23_a_brush_paint_survives_the_wire() {
    use compose_rust::schema::{ColorRole, Paint};

    for paint in [
        Paint::Asset(1),
        Paint::Asset(4_000_000),
        Paint::Role(ColorRole::Primary),
    ] {
        assert_eq!(Paint::from_bits(paint.to_bits()), Some(paint));
    }
}

/// Android 13 shows a notification only for an application that declares the permission,
/// and the renderer has no manifest of its own to merge, so the build declares it in the
/// application's, once.
#[test]
fn fr36_the_android_manifest_declares_the_notification_permission_once() {
    let manifest = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application />\n</manifest>\n";
    let declared = renderer_dir::with_notification_permission(manifest).unwrap();
    assert!(declared.contains(
        "<uses-permission android:name=\"android.permission.POST_NOTIFICATIONS\" />"
    ));
    let manifest_at = declared.find("<manifest").unwrap();
    let permission_at = declared.find("<uses-permission").unwrap();
    let application_at = declared.find("<application").unwrap();
    assert!(manifest_at < permission_at && permission_at < application_at);
    assert_eq!(renderer_dir::with_notification_permission(&declared), None);
    assert_eq!(renderer_dir::with_notification_permission("<application />"), None);
}

/// The generated Activity installs the notification centre before it asks for the Host and
/// hands it every intent, which is how a press that started the process becomes the Host's
/// first event.
#[test]
fn fr36_the_generated_activity_hands_notification_presses_to_the_renderer() {
    let build = include_str!("../build.rs");
    let install = build.find("AndroidNotifications.install(this)").unwrap();
    let handle = build.find("AndroidNotifications.handle(intent)").unwrap();
    let host = build.find("val host = DioxusRuntime.host()").unwrap();
    assert!(install < handle && handle < host);
    assert!(build.contains("override fun onNewIntent(intent: Intent)"));
    assert!(build.contains("RequestPermission()"));
}
