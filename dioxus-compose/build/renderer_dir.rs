// Acquiring the Renderer distribution for the `native-renderer` feature.
//
// Adding `dioxus-compose` to Cargo.toml is meant to be the whole setup: no variable to
// export, no file to download by hand, no script to run. `cargo build` gets the renderer.
// This file holds every rule that goes into that and every message it can fail with.
//
// It is `include!`d by `build.rs` and by `tests/renderer_resolution.rs`, so the rules a
// consumer hits at build time are the rules the tests exercise. It touches the filesystem
// and nothing else: the network is a closure the caller supplies, which is what lets the
// tests run the whole search order offline. No Cargo directives and no process exit
// either. The caller decides what to do with an error.

pub mod sha256 {
    include!("sha256.rs");
}

pub mod elf {
    include!("elf.rs");
}

use sha256::{checksum_from_sha256_file, sha256_file};
use std::path::{Path, PathBuf};

/// Points at a directory laid out like the release artifact, or at its `lib`
/// subdirectory. Beats the workspace build output, the cache and the download, so a
/// renderer built from source, a vendored copy or an air-gapped build can all be pointed
/// at with one variable and nothing will quietly fetch something else instead.
pub const RENDERER_DIR_ENV: &str = "DIOXUS_COMPOSE_RENDERER_DIR";

/// Moves the download cache. Optional: not setting it is the supported case, and the
/// default lands outside `target/` either way.
pub const CACHE_DIR_ENV: &str = "DIOXUS_COMPOSE_CACHE_DIR";

/// Where the release artifacts live. One release per crate version, tagged `v{version}`.
pub const RELEASE_BASE_URL: &str = "https://github.com/DarkPyonix/dioxus-compose/releases";

/// The targets the release publishes a renderer for. Anything else has to say so in
/// those words: a bare 404 cannot tell "this platform is not built" from "the release is
/// missing", and the two have completely different answers.
pub const PUBLISHED_TARGETS: &[&str] = &["macos-aarch64", "windows-x64", "linux-x64", "linux-arm64"];

/// The shared library every distribution of the Renderer contains, whatever else
/// travels alongside it. The name follows the platform's own convention, which is what
/// its loader will look for.
pub fn renderer_lib_file(target_os: &str) -> &'static str {
    match target_os {
        // The Windows build names the image `libdioxus_compose_renderer`, so the DLL keeps
        // the `lib` prefix that Windows itself would not have added.
        "windows" => "libdioxus_compose_renderer.dll",
        "macos" => "libdioxus_compose_renderer.dylib",
        _ => "libdioxus_compose_renderer.so",
    }
}

/// Where a platform's build script puts the renderer inside the distribution.
///
/// Windows keeps the DLL beside the AWT and Skia DLLs in `bin`, because the loader
/// searches the directory of the module that needs them and they have to be found
/// together. The other platforms put the shared library in `lib`.
pub fn renderer_lib_subdir(target_os: &str) -> &'static str {
    match target_os {
        "windows" => "bin",
        _ => "lib",
    }
}

/// One line holding the crate version the artifact was built for. The release packaging
/// script writes it into the artifact root; a renderer built straight from the workspace
/// has no such file.
pub const RENDERER_VERSION_FILE: &str = "dioxus-compose-renderer.version";

/// The release artifact for a crate version and platform target, as
/// `.github/workflows/native-renderer.yml` names it.
pub fn artifact_file_name(crate_version: &str, target: &str) -> String {
    format!("dioxus-compose-renderer-v{crate_version}-{target}.tar.gz")
}

/// Where that artifact and its checksum are downloaded from.
pub fn artifact_url(crate_version: &str, file_name: &str) -> String {
    format!("{RELEASE_BASE_URL}/download/v{crate_version}/{file_name}")
}

/// The platform target used in artifact names, built from Cargo's target triple parts.
///
/// Cargo and the release artifacts do not spell architectures the same way, and joining
/// the two halves raw quietly produced names no release ever had: Cargo says `x86_64`
/// where the artifacts say `x64`, and `aarch64` on Linux where they say `arm64`. It was
/// right on exactly one platform, macOS, where the two spellings agree, and that is the
/// platform everything was checked on. Everywhere else it sent people to a download link
/// for a file that does not exist.
pub fn artifact_target(target_os: &str, target_arch: &str) -> String {
    let arch = match (target_os, target_arch) {
        // Apple's own name for its silicon, which is what the macOS artifact carries.
        ("macos", "aarch64") => "aarch64",
        (_, "aarch64") => "arm64",
        (_, "x86_64") => "x64",
        _ => target_arch,
    };
    format!("{target_os}-{arch}")
}

/// The cache root, which is deliberately not under `target/`.
///
/// `cargo clean` removes tens of megabytes of downloaded renderer along with everything
/// else it cleans, and every project on the machine would keep its own copy. Keyed by
/// version and target below, one download serves every project on the same version.
///
/// `home` and `local_app_data` are passed in rather than read here so this stays a rule
/// rather than an environment lookup, and so the tests can ask what Windows would do.
pub fn default_cache_root(
    home: Option<&Path>,
    local_app_data: Option<&Path>,
    host_is_windows: bool,
) -> Option<PathBuf> {
    if host_is_windows {
        return local_app_data.map(|dir| dir.join("dioxus-compose"));
    }
    home.map(|dir| dir.join(".cache").join("dioxus-compose"))
}

/// One unpacked renderer, keyed so that two crate versions and two targets never collide.
pub fn cached_renderer_dir(cache_root: &Path, crate_version: &str, target: &str) -> PathBuf {
    cache_root
        .join("renderer")
        .join(format!("v{crate_version}"))
        .join(target)
}

/// Where the tarball and its `.sha256` are kept. Also where a build with no network
/// looks, which is what makes the offline instructions a real route rather than an
/// apology: put the two files here and the next build unpacks them.
pub fn download_dir(cache_root: &Path) -> PathBuf {
    cache_root.join("downloads")
}

/// Where the Renderer was found, and which version file (if any) vouched for it.
#[derive(Debug)]
pub struct Renderer {
    /// The directory to add to the link search path and the rpath.
    pub lib_dir: PathBuf,
    /// The version recorded in the artifact, when it carries one.
    pub artifact_version: Option<String>,
    /// What answered: used for the one line the build prints about where its renderer
    /// came from, so a surprising build is diagnosable from the log alone.
    pub source: RendererSource,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum RendererSource {
    /// [`RENDERER_DIR_ENV`] pointed at it.
    Environment,
    /// A checkout of this repository had already built one.
    Workspace,
    /// Already unpacked in the cache. No network was used.
    Cache,
    /// Unpacked from a tarball that was already in the download directory, either left by
    /// an earlier build or placed there by hand. No network was used.
    LocalArtifact,
    /// Downloaded from the release for this crate version.
    Download,
}

/// One GET: a URL and where to put what comes back. The build script supplies the
/// implementation, which is what keeps this file free of the network and lets the tests
/// drive every branch of the search order from a temporary directory.
pub type Fetch<'a> = &'a dyn Fn(&str, &Path) -> Result<(), FetchError>;

/// Why a download did not produce a file. The distinction is the whole point: "no
/// network" and "this version has no such artifact" need different things from the
/// person reading the failure.
#[derive(Debug)]
pub enum FetchError {
    /// The request never reached the server, or there is no tool to make it with.
    Unreachable(String),
    /// The server answered, and the answer was that there is no such file.
    NotFound,
    /// The server answered with something else, or the transfer failed part way.
    Failed(String),
}

/// Everything [`acquire_renderer`] needs, gathered so the call site reads as a list of
/// facts rather than six positional arguments of the same type.
pub struct Request<'a> {
    /// The value of [`RENDERER_DIR_ENV`], if it is set.
    pub env_dir: Option<&'a Path>,
    /// The in-repository build output. Does not exist for a consumer of the published
    /// crate, which is the case every error path here is written for.
    pub workspace_lib_dir: &'a Path,
    /// The cache root, or `None` when the platform gave us nowhere to put one.
    pub cache_root: Option<&'a Path>,
    pub crate_version: &'a str,
    pub target: &'a str,
    pub target_os: &'a str,
    /// `None` means this build may not use the network, which is how the tests run the
    /// whole search order offline.
    pub fetch: Option<Fetch<'a>>,
}

/// Find a renderer, downloading one if that is what it takes, or explain exactly what to
/// do instead.
///
/// The order is fixed and each step is tried in turn:
///
/// 1. [`RENDERER_DIR_ENV`]. Set means set: if there is no renderer there this fails
///    rather than falling through, because a build that was pointed at a renderer on
///    purpose must not quietly turn into a download of a different one.
/// 2. The workspace build output, which only a checkout of this repository has.
/// 3. The version-and-target cache, which costs no network.
/// 4. The tarball in the download directory, verified and unpacked.
/// 5. The release for this crate version.
pub fn acquire_renderer(request: &Request) -> Result<Renderer, String> {
    let lib_file = renderer_lib_file(request.target_os);

    if let Some(dir) = request.env_dir {
        let lib_dir = lib_dir_within(dir, lib_file);
        if !lib_dir.join(lib_file).is_file() {
            return Err(missing_from_env_message(dir, &lib_dir, lib_file));
        }
        return finish(lib_dir, request, RendererSource::Environment);
    }

    if request.workspace_lib_dir.join(lib_file).is_file() {
        return finish(
            request.workspace_lib_dir.to_path_buf(),
            request,
            RendererSource::Workspace,
        );
    }

    let Some(cache_root) = request.cache_root else {
        return Err(no_cache_root_message(request.crate_version, request.target));
    };

    let cached = cached_renderer_dir(cache_root, request.crate_version, request.target);
    let cached_lib_dir = cached.join(renderer_lib_subdir(request.target_os));
    if cached_lib_dir.join(lib_file).is_file() {
        return finish(cached_lib_dir, request, RendererSource::Cache);
    }

    if !PUBLISHED_TARGETS.contains(&request.target) {
        return Err(unpublished_target_message(
            request.crate_version,
            request.target,
        ));
    }

    let source = fetch_artifact(request, cache_root)?;
    let artifact = artifact_file_name(request.crate_version, request.target);
    let tarball = download_dir(cache_root).join(&artifact);
    verify_checksum(&tarball, cache_root)?;
    unpack(
        &tarball,
        &cached,
        request.target_os,
        request.crate_version,
        request.target,
    )?;

    if !cached_lib_dir.join(lib_file).is_file() {
        return Err(unexpected_layout_message(
            &tarball,
            &cached,
            lib_file,
            renderer_lib_subdir(request.target_os),
        ));
    }
    finish(cached_lib_dir, request, source)
}

/// Make sure the tarball and its checksum are both in the download directory, by
/// downloading them if they are not. Returns which of those two it was.
fn fetch_artifact(request: &Request, cache_root: &Path) -> Result<RendererSource, String> {
    let artifact = artifact_file_name(request.crate_version, request.target);
    let checksum_name = format!("{artifact}.sha256");
    let downloads = download_dir(cache_root);
    let tarball = downloads.join(&artifact);
    let checksum = downloads.join(&checksum_name);

    if tarball.is_file() && checksum.is_file() {
        return Ok(RendererSource::LocalArtifact);
    }

    let Some(fetch) = request.fetch else {
        return Err(offline_message(
            request.crate_version,
            request.target,
            &downloads,
            "this build was told not to use the network",
        ));
    };

    std::fs::create_dir_all(&downloads)
        .map_err(|error| cannot_write_message(&downloads, &error.to_string()))?;

    for (name, destination) in [(&artifact, &tarball), (&checksum_name, &checksum)] {
        if destination.is_file() {
            continue;
        }
        let url = artifact_url(request.crate_version, name);
        // Downloaded beside the final name and moved into place, so an interrupted
        // download is never mistaken for a complete one by the next build.
        let partial = destination.with_extension("partial");
        let _ = std::fs::remove_file(&partial);
        match fetch(&url, &partial) {
            Ok(()) => std::fs::rename(&partial, destination)
                .map_err(|error| cannot_write_message(destination, &error.to_string()))?,
            Err(error) => {
                let _ = std::fs::remove_file(&partial);
                // Whatever is already downloaded is left alone: half an artifact with no
                // checksum beside it is simply not a cache hit next time.
                return Err(match error {
                    FetchError::Unreachable(detail) => offline_message(
                        request.crate_version,
                        request.target,
                        &downloads,
                        &detail,
                    ),
                    FetchError::NotFound => {
                        not_in_release_message(request.crate_version, request.target, name)
                    }
                    FetchError::Failed(detail) => download_failed_message(&url, &detail),
                });
            }
        }
    }

    Ok(RendererSource::Download)
}

/// Every unpack is preceded by this. The trust boundary is the GitHub release itself, and
/// the `.sha256` comes from the same release as the artifact, so what this catches is a
/// truncated download or a corrupted cache, not a supply chain attack. Catching those is
/// worth doing on its own: a tarball that lost its last megabyte unpacks into a renderer
/// that fails at link time with nothing pointing back at the download.
fn verify_checksum(tarball: &Path, cache_root: &Path) -> Result<(), String> {
    let checksum_path = sibling_checksum(tarball);
    let contents = std::fs::read_to_string(&checksum_path)
        .map_err(|error| unreadable_checksum_message(&checksum_path, &error.to_string()))?;
    let Some(expected) = checksum_from_sha256_file(&contents) else {
        return Err(unreadable_checksum_message(
            &checksum_path,
            "it does not start with a 64 character hex digest",
        ));
    };

    let actual = sha256_file(tarball)
        .map_err(|error| cannot_read_artifact_message(tarball, &error.to_string()))?;
    if actual != expected {
        return Err(checksum_mismatch_message(
            tarball,
            &checksum_path,
            &expected,
            &actual,
            &download_dir(cache_root),
        ));
    }
    Ok(())
}

pub fn sibling_checksum(tarball: &Path) -> PathBuf {
    let mut name = tarball.file_name().unwrap_or_default().to_os_string();
    name.push(".sha256");
    tarball.with_file_name(name)
}

/// Unpack into a scratch directory and move it into place, so a build that is interrupted
/// mid-extraction does not leave a half-unpacked directory that the next build reads as a
/// cache hit.
fn unpack(
    tarball: &Path,
    destination: &Path,
    target_os: &str,
    crate_version: &str,
    target: &str,
) -> Result<(), String> {
    let parent = destination
        .parent()
        .ok_or_else(|| format!("the renderer cache path {} has no parent", destination.display()))?;
    std::fs::create_dir_all(parent)
        .map_err(|error| cannot_write_message(parent, &error.to_string()))?;

    let scratch = parent.join(format!(
        ".unpacking-{}-{}",
        destination.file_name().unwrap_or_default().to_string_lossy(),
        std::process::id()
    ));
    let _ = std::fs::remove_dir_all(&scratch);
    std::fs::create_dir_all(&scratch)
        .map_err(|error| cannot_write_message(&scratch, &error.to_string()))?;

    // `tar` rather than an extraction crate: it is present on macOS and Linux, and
    // Windows has carried bsdtar in System32 since Windows 10 1803. A crate would put a
    // decompression tree into every consumer's build for one command.
    let status = std::process::Command::new("tar")
        .arg("-xzf")
        .arg(tarball)
        .arg("-C")
        .arg(&scratch)
        .status();

    let result = match status {
        Ok(status) if status.success() => Ok(()),
        Ok(status) => Err(unpack_failed_message(
            tarball,
            &format!("tar exited with {status}"),
        )),
        Err(error) => Err(unpack_failed_message(tarball, &error.to_string())),
    };
    if let Err(message) = result {
        let _ = std::fs::remove_dir_all(&scratch);
        return Err(message);
    }

    // While it is still invisible to any other build, and using the name it is about to
    // have. Doing it after the rename would mean a second build could link the library in
    // the moment between the two.
    let subdir = renderer_lib_subdir(target_os);
    let lib_file = renderer_lib_file(target_os);
    let unpacked = scratch.join(subdir).join(lib_file);
    // A missing library is reported properly by the layout check downstream.
    if unpacked.is_file() {
        if let Err(message) = name_after_its_location(
            &unpacked,
            &destination.join(subdir).join(lib_file),
            target_os,
            crate_version,
            target,
        ) {
            let _ = std::fs::remove_dir_all(&scratch);
            return Err(message);
        }
    }

    let _ = std::fs::remove_dir_all(destination);
    std::fs::rename(&scratch, destination).map_err(|error| {
        let _ = std::fs::remove_dir_all(&scratch);
        cannot_write_message(destination, &error.to_string())
    })
}

/// Teach the unpacked renderer where it lives, so that anything linking it records that
/// location and can load it.
///
/// A library carries the name its dependents will look it up by. The macOS artifact
/// carries `@rpath/libdioxus_compose_renderer.dylib`, and `@rpath` is resolved against the
/// rpaths of whatever loaded it. An application that merely depends on this crate has
/// none: Cargo passes a dependency's link search paths and link libraries down to the
/// final binary, but not its link arguments, and an rpath is a link argument. The
/// application would link cleanly and then die on startup with the loader unable to find
/// a library that is sitting right there on disk.
///
/// Pointing the name at the directory the library is in makes the lookup absolute, which
/// is exactly as specific as it should be: this is where that library is going to stay,
/// and the renderer finds its own Skia and AWT companions relative to itself, so the
/// library has to be named after its real home rather than after a copy of it.
///
/// `library` is the file to edit and `name` is the path it will answer to, which are the
/// same path except while an artifact is being unpacked under a scratch name.
pub fn name_after_its_location(
    library: &Path,
    name: &Path,
    target_os: &str,
    crate_version: &str,
    target: &str,
) -> Result<(), String> {
    match target_os {
        "macos" => set_install_name(library, name, crate_version, target),
        "linux" => drop_soname(library, crate_version, target),
        // Windows resolves a DLL through the loader's search path, which no name inside
        // the file can affect. The build script says what to do about that instead.
        _ => Ok(()),
    }
}

/// The Mach-O side: `LC_ID_DYLIB`.
fn set_install_name(
    library: &Path,
    name: &Path,
    crate_version: &str,
    target: &str,
) -> Result<(), String> {
    // Every build runs through here, and rewriting a sixty megabyte library on each one
    // would be a waste of the build's time and would fail outright on a renderer someone
    // has vendored into a read-only directory but already named correctly.
    if install_name(library).as_deref() == Some(name.to_string_lossy().as_ref()) {
        return Ok(());
    }

    let status = std::process::Command::new("install_name_tool")
        .arg("-id")
        .arg(name)
        .arg(library)
        .output();
    match status {
        Ok(output) if output.status.success() => Ok(()),
        Ok(output) => Err(install_name_message(
            name,
            &String::from_utf8_lossy(&output.stderr),
            crate_version,
            target,
        )),
        Err(error) => Err(install_name_message(
            name,
            &error.to_string(),
            crate_version,
            target,
        )),
    }
}

/// The name the library answers to now, or `None` if that could not be read. `None` means
/// "unknown", so the caller goes ahead and sets the name rather than assuming either way.
fn install_name(library: &Path) -> Option<String> {
    // `otool -D` prints the file name, then the install name. A library with no install
    // name prints only the first line.
    let output = std::process::Command::new("otool")
        .arg("-D")
        .arg(library)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&output.stdout);
    let name = text.lines().nth(1)?.trim();
    (!name.is_empty()).then(|| name.to_string())
}

/// The ELF side: no SONAME at all.
///
/// A shared object with a SONAME is recorded by that bare name, and the loader then has
/// to find it on a search path the application does not have. One with no SONAME is
/// recorded by the path the linker opened it at, which is absolute here, so there is
/// nothing left to search for. The renderer is built without one; an artifact that
/// carries one anyway is fixed here rather than turned into a binary that links and
/// cannot start.
fn drop_soname(library: &Path, crate_version: &str, target: &str) -> Result<(), String> {
    match elf::soname(library) {
        Ok(None) => Ok(()),
        Ok(Some(_)) => elf::remove_soname(library)
            .map(|_| ())
            .map_err(|detail| soname_message(library, &detail, crate_version, target)),
        Err(detail) => Err(soname_message(library, &detail, crate_version, target)),
    }
}

/// Check the version the artifact declares, if it declares one, name the library after
/// where it sits, and package the answer.
///
/// The naming happens here rather than only where an artifact is unpacked, so that the
/// renderer a developer of this repository builds from source is loaded exactly the way
/// the one a consumer downloads is. Two different loading paths meant the one people use
/// every day was not the one that was broken.
fn finish(lib_dir: PathBuf, request: &Request, source: RendererSource) -> Result<Renderer, String> {
    // Whatever was found, from here on it is an absolute path. The name written into the
    // library is the name every application that links it will look it up by, and a
    // relative one would be resolved against whatever directory that application happens
    // to be started from. `DIOXUS_COMPOSE_RENDERER_DIR` is often set to a path relative to
    // the build, which is how this would otherwise happen.
    let lib_dir = absolute(&lib_dir);
    let artifact_version = read_artifact_version(&lib_dir);
    if let Some(found) = artifact_version.as_deref() {
        if found != request.crate_version {
            return Err(version_mismatch_message(
                &lib_dir,
                found,
                request.crate_version,
                request.target,
            ));
        }
    }

    let library = lib_dir.join(renderer_lib_file(request.target_os));
    name_after_its_location(
        &library,
        &library,
        request.target_os,
        request.crate_version,
        request.target,
    )?;

    Ok(Renderer {
        lib_dir,
        artifact_version,
        source,
    })
}

/// The same directory, spelled absolutely.
///
/// The name written into the library is the name every application that links it will
/// look it up by, so it has to mean the same thing from any working directory.
/// `DIOXUS_COMPOSE_RENDERER_DIR` is routinely set to a path relative to the build, which
/// is how a relative name would otherwise be baked in.
///
/// Symbolic links are left alone rather than resolved. A renderer reached through a link
/// is a deliberate arrangement, the directory it points at holds the same companion
/// libraries either way, and resolving would replace the path someone chose with one they
/// did not.
pub fn absolute(dir: &Path) -> PathBuf {
    if dir.is_absolute() {
        return dir.to_path_buf();
    }
    match std::env::current_dir() {
        Ok(working) => working.join(dir),
        // Nowhere to resolve against. A build that would have worked keeps working, and
        // the name is no worse than it was before this step existed.
        Err(_) => dir.to_path_buf(),
    }
}

/// Accept either the unpacked artifact root or the directory holding the library. Both are
/// natural things for a human to point the variable at, and guessing wrong is a linker
/// error rather than a message.
///
/// The subdirectory differs by platform: Windows keeps the renderer in `bin` beside the
/// AWT and Skia DLLs, which the loader needs to find together, and the others use `lib`.
/// Both are tried, so pointing at the wrong one of the two still works.
fn lib_dir_within(dir: &Path, lib_file: &str) -> PathBuf {
    for subdir in ["lib", "bin"] {
        let nested = dir.join(subdir);
        if nested.join(lib_file).is_file() {
            return nested;
        }
    }
    dir.to_path_buf()
}

/// The version file lives in the artifact root, which is the parent of `lib/` for an
/// unpacked artifact. It is also accepted inside the library directory, so a vendored
/// copy that keeps only `lib/` can still declare its version.
fn read_artifact_version(lib_dir: &Path) -> Option<String> {
    let candidates = [
        lib_dir.join(RENDERER_VERSION_FILE),
        lib_dir.join("..").join(RENDERER_VERSION_FILE),
    ];
    for candidate in candidates {
        if let Ok(contents) = std::fs::read_to_string(&candidate) {
            let trimmed = contents.trim();
            if !trimmed.is_empty() {
                return Some(trimmed.to_string());
            }
        }
    }
    None
}

/// The escape hatch every failure here ends with. It is the same two lines every time on
/// purpose: whatever went wrong, pointing the variable at a renderer is the way past it.
fn build_it_yourself(crate_version: &str, target: &str) -> String {
    let artifact = artifact_file_name(crate_version, target);
    format!(
        "Any renderer you already have works too. Unpack {artifact}, or build one with\n\
         dioxus-compose-renderer/desktop/scripts/build-native.sh from a checkout of the\n\
         repository, then set {RENDERER_DIR_ENV} to that directory. The variable is checked\n\
         first and nothing is downloaded when it is set."
    )
}

fn missing_from_env_message(env_dir: &Path, looked_in: &Path, lib_file: &str) -> String {
    format!(
        "dioxus-compose: {RENDERER_DIR_ENV} is set to {env_dir}, but no renderer is there.\n\
         \n\
         Looked for {lib_file} in {looked_in}, in {env_dir}/lib and in {env_dir}/bin. Point\n\
         the variable either at the directory an artifact was unpacked into or straight at\n\
         the directory holding the library.\n\
         \n\
         Nothing was downloaded, because the variable is set. Unset it and this build\n\
         fetches the renderer for its own version by itself.",
        env_dir = env_dir.display(),
        looked_in = looked_in.display(),
    )
}

fn unpublished_target_message(crate_version: &str, target: &str) -> String {
    format!(
        "dioxus-compose: no renderer is published for {target}.\n\
         \n\
         The release builds these targets: {published}. This build is for {target}, so\n\
         there is nothing to download, and a download would only have produced a 404 that\n\
         does not say which of the two is missing.\n\
         \n\
         {build_it_yourself}",
        published = PUBLISHED_TARGETS.join(", "),
        build_it_yourself = build_it_yourself(crate_version, target),
    )
}

fn offline_message(
    crate_version: &str,
    target: &str,
    downloads: &Path,
    detail: &str,
) -> String {
    let artifact = artifact_file_name(crate_version, target);
    format!(
        "dioxus-compose: could not download the renderer ({detail}).\n\
         \n\
         Put these two files in {downloads} and build again. Nothing else is needed, and\n\
         the next build will verify the checksum and unpack them without a network:\n\
         \n\
         \x20   {artifact}\n\
         \x20   {artifact}.sha256\n\
         \n\
         Both are attached to the release for this crate version:\n\
         \x20   {tag_url}\n\
         \n\
         {build_it_yourself}",
        downloads = downloads.display(),
        tag_url = format_args!("{RELEASE_BASE_URL}/tag/v{crate_version}"),
        build_it_yourself = build_it_yourself(crate_version, target),
    )
}

fn not_in_release_message(crate_version: &str, target: &str, file_name: &str) -> String {
    format!(
        "dioxus-compose: the release for v{crate_version} does not carry {file_name}.\n\
         \n\
         The server was reached and answered that there is no such file, so this is not a\n\
         network problem. Either that release was published without the {target} renderer,\n\
         or it predates this crate version being tagged at all. What it does carry is\n\
         listed at:\n\
         \x20   {tag_url}\n\
         \n\
         {build_it_yourself}",
        tag_url = format_args!("{RELEASE_BASE_URL}/tag/v{crate_version}"),
        build_it_yourself = build_it_yourself(crate_version, target),
    )
}

fn download_failed_message(url: &str, detail: &str) -> String {
    format!(
        "dioxus-compose: downloading the renderer failed ({detail}).\n\
         \n\
         The address was {url}. Retrying the build retries the download; nothing partial\n\
         was kept."
    )
}

fn checksum_mismatch_message(
    tarball: &Path,
    checksum_path: &Path,
    expected: &str,
    actual: &str,
    downloads: &Path,
) -> String {
    format!(
        "dioxus-compose: the renderer artifact does not match its checksum, so it was not\n\
         unpacked.\n\
         \n\
         \x20   file     {tarball}\n\
         \x20   expected {expected}\n\
         \x20   actual   {actual}\n\
         \n\
         {checksum_path} is what says the first of those. A download that was cut short is\n\
         much the likelier cause than a tampered release, and either way the fix is the\n\
         same: delete both files and build again.\n\
         \n\
         \x20   rm -rf {downloads}",
        tarball = tarball.display(),
        checksum_path = checksum_path.display(),
        downloads = downloads.display(),
    )
}

fn unreadable_checksum_message(checksum_path: &Path, detail: &str) -> String {
    format!(
        "dioxus-compose: cannot read the renderer checksum at {checksum_path} ({detail}).\n\
         \n\
         The file holds one line: the SHA-256 digest, then the artifact name, exactly as\n\
         `shasum -a 256` writes it. Delete it and build again to download it afresh.",
        checksum_path = checksum_path.display(),
    )
}

fn cannot_read_artifact_message(tarball: &Path, detail: &str) -> String {
    format!(
        "dioxus-compose: cannot read the renderer artifact at {tarball} ({detail}).\n\
         \n\
         Delete it and build again to download it afresh.",
        tarball = tarball.display(),
    )
}

fn cannot_write_message(path: &Path, detail: &str) -> String {
    format!(
        "dioxus-compose: cannot write to {path} ({detail}).\n\
         \n\
         That path is the renderer cache. Set {CACHE_DIR_ENV} to a directory this build can\n\
         write to, or set {RENDERER_DIR_ENV} to a renderer you already have, which skips\n\
         the cache entirely.",
        path = path.display(),
    )
}

fn no_cache_root_message(crate_version: &str, target: &str) -> String {
    format!(
        "dioxus-compose: there is nowhere to cache the renderer.\n\
         \n\
         The cache normally goes under $HOME/.cache on Unix and %LOCALAPPDATA% on Windows,\n\
         and neither is set for this build. Set {CACHE_DIR_ENV} to a directory to download\n\
         into.\n\
         \n\
         {build_it_yourself}",
        build_it_yourself = build_it_yourself(crate_version, target),
    )
}

fn install_name_message(library: &Path, detail: &str, crate_version: &str, target: &str) -> String {
    format!(
        "dioxus-compose: could not set the renderer's install name ({detail}).\n\
         \n\
         The library has to be told that it lives at\n\
         \n\
         \x20   {library}\n\
         \n\
         or anything linking it records the name it came with, `@rpath/{file}`, and dyld\n\
         cannot resolve that in an application that has no matching rpath. `install_name_tool`\n\
         does it and comes with the Xcode command line tools, which the Rust linker needs\n\
         anyway: `xcode-select --install`. A renderer in a directory this build cannot write\n\
         to has to carry that name already; copy it somewhere writable and point\n\
         {RENDERER_DIR_ENV} at the copy.\n\
         \n\
         {build_it_yourself}",
        library = library.display(),
        file = renderer_lib_file("macos"),
        build_it_yourself = build_it_yourself(crate_version, target),
    )
}

fn soname_message(library: &Path, detail: &str, crate_version: &str, target: &str) -> String {
    format!(
        "dioxus-compose: could not read or clear the renderer's SONAME ({detail}).\n\
         \n\
         \x20   {library}\n\
         \n\
         A shared object that records a SONAME is looked up by that bare name, and the\n\
         application linking it has no search path to find it on, so it would link and then\n\
         fail to start. One with no SONAME is recorded by its full path instead, which is\n\
         what the renderer is built to be. Check the file with `readelf -d`; the renderer\n\
         published for this crate version has no SONAME line.\n\
         \n\
         {build_it_yourself}",
        library = library.display(),
        build_it_yourself = build_it_yourself(crate_version, target),
    )
}

fn unpack_failed_message(tarball: &Path, detail: &str) -> String {
    format!(
        "dioxus-compose: could not unpack the renderer artifact ({detail}).\n\
         \n\
         The file is {tarball} and its checksum matched, so it arrived intact. What failed\n\
         was extracting it, which needs `tar` on PATH. macOS and Linux always have it, and\n\
         Windows has carried it in System32 since Windows 10 1803.",
        tarball = tarball.display(),
    )
}

fn unexpected_layout_message(
    tarball: &Path,
    unpacked_into: &Path,
    lib_file: &str,
    subdir: &str,
) -> String {
    format!(
        "dioxus-compose: the renderer artifact unpacked, but does not contain a renderer.\n\
         \n\
         Expected {subdir}/{lib_file} under {unpacked_into}, from {tarball}. An artifact\n\
         built for a different platform would look exactly like this. Delete that directory\n\
         and the downloaded artifact, then build again.",
        tarball = tarball.display(),
        unpacked_into = unpacked_into.display(),
    )
}

fn version_mismatch_message(
    lib_dir: &Path,
    artifact_version: &str,
    crate_version: &str,
    target: &str,
) -> String {
    format!(
        "dioxus-compose: renderer version mismatch.\n\
         \n\
         The renderer in {lib_dir} declares version {artifact_version} ({file}), but this\n\
         crate is version {crate_version}. Linking them would pair a Host against a Renderer\n\
         it was never built for, and the mismatch would surface as a protocol error at\n\
         runtime instead of here.\n\
         \n\
         Use the artifact for this crate version, {artifact}, or depend on\n\
         dioxus-compose {artifact_version} instead.",
        lib_dir = lib_dir.display(),
        file = RENDERER_VERSION_FILE,
        artifact = artifact_file_name(crate_version, target),
    )
}

/// Every message this module can produce, with inputs that stand in for the real ones.
///
/// Instructions rot silently: the failure these replace told people to run
/// `scripts/fetch-renderer.sh`, a file that never existed in this repository, and nothing
/// noticed because no test ever read the message. Anything added here is covered by the
/// test that walks this list looking for paths that are not in the tree, so keep new
/// messages in it.
pub fn every_failure_message(sample_dir: &Path) -> Vec<String> {
    let version = "9.9.9";
    let target = "macos-aarch64";
    let tarball = sample_dir.join(artifact_file_name(version, target));
    vec![
        missing_from_env_message(sample_dir, sample_dir, renderer_lib_file("macos")),
        unpublished_target_message(version, "freebsd-x86_64"),
        offline_message(version, target, sample_dir, "curl could not resolve the host"),
        not_in_release_message(version, target, &artifact_file_name(version, target)),
        download_failed_message(&artifact_url(version, &artifact_file_name(version, target)), "HTTP 503"),
        checksum_mismatch_message(&tarball, &sibling_checksum(&tarball), "aa", "bb", sample_dir),
        unreadable_checksum_message(&sibling_checksum(&tarball), "no such file"),
        cannot_read_artifact_message(&tarball, "permission denied"),
        cannot_write_message(sample_dir, "read-only file system"),
        no_cache_root_message(version, target),
        unpack_failed_message(&tarball, "tar exited with 2"),
        install_name_message(
            &sample_dir.join(renderer_lib_file("macos")),
            "not found",
            version,
            target,
        ),
        soname_message(
            &sample_dir.join(renderer_lib_file("linux")),
            "permission denied",
            version,
            "linux-x64",
        ),
        unexpected_layout_message(&tarball, sample_dir, renderer_lib_file("macos"), "lib"),
        version_mismatch_message(sample_dir, "0.1.0", version, target),
        build_it_yourself(version, target),
    ]
}

/// How the Host reaches the Renderer on this target.
///
/// The answer is not "is there a renderer" but "who resolves its symbols, and when".
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RendererLinkage {
    /// Cargo links the shared library here, so the Host declares the C symbols and the
    /// linker resolves them now.
    Linked,
    /// Something outside Cargo resolves them before anything runs: Xcode links the
    /// XCFramework into the application. The Host still declares them.
    Provided,
    /// Nobody resolves them, because there are none. The renderer runs in a managed
    /// runtime beside the Host and installs its entry points at load time instead, so a
    /// declared symbol here is a symbol that can never be found.
    Installed,
    /// A build that draws nothing and says so.
    None,
}

/// Which of the four this target is.
///
/// Android is the one that had to be learned from a device. It reached the same branch as
/// iOS, because both are "not a desktop", and the Host declared
/// `dioxus_compose_renderer_run` for it. On iOS that symbol is really there by the time
/// anything runs. On Android the renderer is Kotlin in ART and there is no native symbol
/// of that name at all, so every launch ended at `dlopen failed: cannot locate symbol
/// "dioxus_compose_renderer_run"` before a single line of the application ran. The JNI
/// entry points install the renderer API from `JNI_OnLoad`, which is the same arrangement
/// the browser uses, so Android belongs with the browser rather than with iOS.
pub fn renderer_linkage(target_os: &str, target_family: &str, mock: bool) -> RendererLinkage {
    if mock {
        return RendererLinkage::None;
    }
    if target_family.split(',').any(|family| family == "wasm") {
        return RendererLinkage::Installed;
    }
    match target_os {
        "macos" | "windows" | "linux" => RendererLinkage::Linked,
        "android" => RendererLinkage::Installed,
        _ => RendererLinkage::Provided,
    }
}

/// Where an Android application's Gradle project keeps the Kotlin it compiles, and the
/// package the generated Activity belongs to.
///
/// Two ways in. `ours` and `ours_package` are this crate's own variables, set by someone
/// driving a build by hand. `dx_dir` and `dx_package` are what the Dioxus CLI exports for
/// every Android build: they carry wry's names, because the CLI sets them so that wry can
/// generate the Activity hosting its webview. We draw with Compose and have no webview,
/// but the two values say the same thing either way, which is the Gradle project this
/// build is part of. Reading them is what lets an APK come out of `dx build` with nothing
/// written about this crate in `Dioxus.toml`.
///
/// The CLI names a directory inside the package, since it generates one file into one
/// package. This crate carries dozens of files across a dozen packages, so the source root
/// is what it needs, and that is the named directory with the package's own components
/// removed. Counting components rather than looking for a directory called `kotlin` keeps
/// this right for a project whose source root is named something else.
pub fn android_gradle_kotlin(
    ours: Option<&str>,
    ours_package: Option<&str>,
    dx_dir: Option<&str>,
    dx_package: Option<&str>,
) -> Option<(PathBuf, Option<String>)> {
    if let Some(ours) = ours {
        return Some((PathBuf::from(ours), ours_package.map(str::to_owned)));
    }
    let package = dx_package?;
    let mut root = PathBuf::from(dx_dir?);
    for _ in package.split('.') {
        root = root.parent()?.to_path_buf();
    }
    Some((root, Some(package.to_owned())))
}

/// The Compose libraries the renderer's Kotlin is written against.
///
/// They are androidx's, on mavenCentral already, so this declares coordinates rather than
/// republishing anything. The versions belong here rather than in an application's own
/// build file because it is the renderer that is written against them, and an application
/// that had to name them would be guessing at what its dependency needs.
pub const ANDROID_COMPOSE_DEPENDENCIES: [&str; 4] = [
    "androidx.activity:activity-compose:1.9.3",
    "androidx.compose.ui:ui:1.7.5",
    "androidx.compose.foundation:foundation:1.7.5",
    "androidx.compose.material3:material3:1.3.1",
];

/// The Gradle plugin that compiles `@Composable`, which Kotlin 2.0 made compulsory.
const COMPOSE_PLUGIN_ID: &str = "org.jetbrains.kotlin.plugin.compose";

/// Puts Compose into an application module's generated `build.gradle.kts`.
///
/// The Dioxus CLI generates this file and offers `gradle_plugins` for adding to it, but
/// the entries are escaped before they reach the file, so there is no way to write a
/// plugin with a version through it and a Kotlin compiler plugin is exactly what Compose
/// needs. An application that could not get past that would have to keep a patched build
/// file of its own, which is the hand-written glue this project exists to avoid.
///
/// Returns None when there is nothing to do, so that a rewrite of an unchanged file is
/// not mistaken for a change.
pub fn with_compose(module: &str, root: &str) -> Option<(String, Option<String>)> {
    let kotlin = kotlin_gradle_plugin_version(root)?;
    let mut module_text = module.to_owned();
    if !module.contains(COMPOSE_PLUGIN_ID) {
        let plugins = module.find("plugins {")?;
        let after = module[plugins..].find('\n')? + plugins + 1;
        module_text.insert_str(after, &format!("    id(\"{COMPOSE_PLUGIN_ID}\")\n"));
    }
    let missing: Vec<&str> = ANDROID_COMPOSE_DEPENDENCIES
        .iter()
        .copied()
        .filter(|coordinate| !module_text.contains(coordinate))
        .collect();
    if !missing.is_empty() {
        // The last `dependencies {` in the file, because `buildscript` has one of its own
        // in some templates and the module's own block is what takes `implementation`.
        let block = module_text.rfind("dependencies {")?;
        let after = module_text[block..].find('\n')? + block + 1;
        let lines: String = missing
            .iter()
            .map(|coordinate| format!("    implementation(\"{coordinate}\")\n"))
            .collect();
        module_text.insert_str(after, &lines);
    }
    // The plugin is applied by id with no version, so the jar has to be on the build's
    // own classpath. The version is the one the template already pinned for Kotlin: the
    // two are released together and a mismatch is refused at configuration time.
    let classpath = format!("org.jetbrains.kotlin:compose-compiler-gradle-plugin:{kotlin}");
    let root_text = if root.contains(&classpath) {
        None
    } else {
        let anchor = root.find("classpath(\"org.jetbrains.kotlin:kotlin-gradle-plugin")?;
        let after = root[anchor..].find('\n')? + anchor + 1;
        let mut text = root.to_owned();
        text.insert_str(after, &format!("        classpath(\"{classpath}\")\n"));
        Some(text)
    };
    if module_text == module && root_text.is_none() {
        return None;
    }
    Some((module_text, root_text))
}

/// The Kotlin version the generated project pinned for its own plugin.
fn kotlin_gradle_plugin_version(root: &str) -> Option<String> {
    let marker = "org.jetbrains.kotlin:kotlin-gradle-plugin:";
    let at = root.find(marker)? + marker.len();
    let rest = &root[at..];
    let end = rest.find(['"', '\''])?;
    Some(rest[..end].to_owned())
}

/// What a renderer distribution and this crate say about the schema they were built from.
#[derive(Debug, Eq, PartialEq)]
pub enum SchemaAgreement {
    /// The two were generated from the same schema.
    Same,
    /// One of them does not say, so the question cannot be answered here.
    Unknown,
    /// They disagree, and a program built from this pair opens a window and draws nothing.
    Different { renderer: String, crate_hash: String },
}

/// Compares the schema hash beside the renderer with the one beside this crate.
///
/// The handshake at the first boundary call already catches this. It catches it at run
/// time, which is late enough to be mistaken for something else: the program builds,
/// starts, opens a window and stays empty, and the report comes back as a white window
/// rather than as two mismatched artifacts. Both numbers are on disk at build time.
///
/// A distribution built before this file existed does not carry one, and neither does a
/// crate that has not run codegen. That is Unknown, not Different: refusing to build
/// against an older renderer that might be perfectly compatible would be worse than the
/// problem.
pub fn schema_agreement(renderer_hash: Option<&str>, crate_hash: Option<&str>) -> SchemaAgreement {
    let (Some(renderer), Some(crate_hash)) = (renderer_hash, crate_hash) else {
        return SchemaAgreement::Unknown;
    };
    let renderer = renderer.trim();
    let crate_hash = crate_hash.trim();
    if renderer.is_empty() || crate_hash.is_empty() {
        return SchemaAgreement::Unknown;
    }
    if renderer.eq_ignore_ascii_case(crate_hash) {
        SchemaAgreement::Same
    } else {
        SchemaAgreement::Different {
            renderer: renderer.to_owned(),
            crate_hash: crate_hash.to_owned(),
        }
    }
}
