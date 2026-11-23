// Acquiring the Renderer distribution for the `native-renderer` feature.
//
// Adding `compose-rust` to Cargo.toml is meant to be the whole setup: no variable to
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
pub const RENDERER_DIR_ENV: &str = "COMPOSE_RUST_RENDERER_DIR";

/// Moves the download cache. Optional: not setting it is the supported case, and the
/// default lands outside `target/` either way.
pub const CACHE_DIR_ENV: &str = "COMPOSE_RUST_CACHE_DIR";

/// Where the release artifacts live. One release per crate version, tagged `v{version}`,
/// and the one release that carries renderers by schema hash, [`SCHEMA_RELEASE_TAG`].
pub const RELEASE_BASE_URL: &str = "https://github.com/DarkPyonix/compose-rust/releases";

/// Replaces [`RELEASE_BASE_URL`] for a mirror, or for a CI machine that serves the same
/// files from somewhere it can reach. Optional: not setting it is the supported case, and
/// the same two addresses are then looked up under the default.
pub const RENDERER_URL_ENV: &str = "COMPOSE_RUST_RENDERER_URL";

/// The release `develop` publishes a renderer to for every schema it reaches, named by the
/// schema hash rather than by a crate version.
///
/// A crate version stays the same between two releases while its schema moves, so a git
/// dependency on a revision after a release gets a crate whose version names a renderer of
/// another schema. That renderer would be refused, rightly, and before this release there
/// was nothing else to download.
pub const SCHEMA_RELEASE_TAG: &str = "renderer-dev";

/// The targets the release publishes a renderer for. Anything else has to say so in
/// those words: a bare 404 cannot tell "this platform is not built" from "the release is
/// missing", and the two have completely different answers.
pub const PUBLISHED_TARGETS: &[&str] = &["macos-aarch64", "windows-x64", "linux-x64", "linux-arm64"];

/// What a renderer distribution carries: the renderer as a static archive, or as a shared
/// library.
///
/// The archive is what the release ships for macOS and Linux. It is Kotlin/Native, with
/// Compose, Skia and ICU inside it, and it is linked into the application, so the
/// application is one executable that needs nothing but the system's own libraries. The
/// shared library is the GraalVM native image, which a checkout can still build for macOS
/// and Linux. Windows has no shared renderer: its distribution is the static kit that
/// `build-windows.sh` writes (the archive with `native/`, `gcc/` and `skiko/` beside it).
///
/// Which one a distribution is, is read from what is in it rather than from the platform.
/// That is what lets a crate version whose release still carries shared libraries go on
/// building, and what lets a platform move to the archive by publishing one, with nothing
/// here to change.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RendererKind {
    /// `libcompose_rust_renderer.a`, linked into the executable.
    Static,
    /// The platform's shared library, loaded beside the executable.
    Shared,
}

/// The release publishes these targets as a static archive. The others are a shared
/// library for now; the build script says which in its messages, and links whichever
/// the distribution holds.
pub const STATIC_TARGETS: &[&str] = &["macos-aarch64", "windows-x64", "linux-x64", "linux-arm64"];

/// The static archive a distribution of the Renderer contains when it is one. Kotlin/Native
/// names it the same way on every platform it builds one for.
pub fn renderer_static_file(_target_os: &str) -> &'static str {
    "libcompose_rust_renderer.a"
}

/// The file a distribution of the given kind holds the renderer in.
pub fn renderer_file(target_os: &str, kind: RendererKind) -> &'static str {
    match kind {
        RendererKind::Static => renderer_static_file(target_os),
        RendererKind::Shared => renderer_lib_file(target_os),
    }
}

/// The renderer in `dir`, and which kind it is.
///
/// Either the directory itself holds it, or its `lib` or `bin` subdirectory does, because
/// both an unpacked artifact and the directory holding the file are natural things to point
/// at. The archive wins where a directory somehow holds both, because it is what makes the
/// application one file.
pub fn find_renderer_in(dir: &Path, target_os: &str) -> Option<(PathBuf, RendererKind)> {
    // The Windows kit is the directory itself: the archive sits at its root with the MSVC
    // bridge libraries beside it, and linking needs all of them, so a `lib/` or `bin/`
    // holding only an archive is not a kit. There is no Windows DLL to find.
    if target_os == "windows" {
        let file = renderer_static_file(target_os);
        return dir
            .join(file)
            .is_file()
            .then(|| (dir.to_path_buf(), RendererKind::Static));
    }
    for kind in [RendererKind::Static, RendererKind::Shared] {
        let file = renderer_file(target_os, kind);
        for candidate in [dir.join("lib"), dir.join("bin"), dir.to_path_buf()] {
            if candidate.join(file).is_file() {
                return Some((candidate, kind));
            }
        }
    }
    None
}

/// The shared library a distribution of the Renderer contains when it is one, whatever
/// else travels alongside it. The name follows the platform's own convention, which is
/// what its loader will look for.
pub fn renderer_lib_file(target_os: &str) -> &'static str {
    match target_os {
        "macos" => "libcompose_rust_renderer.dylib",
        _ => "libcompose_rust_renderer.so",
    }
}

/// Where a platform's build script puts the renderer inside the distribution.
///
/// Every shared library goes in `lib`. Windows has none: its renderer is the static kit.
pub fn renderer_lib_subdir(_target_os: &str) -> &'static str {
    "lib"
}

/// One line holding the crate version the artifact was built for. The release packaging
/// script writes it into the artifact root; a renderer built straight from the workspace
/// has no such file.
pub const RENDERER_VERSION_FILE: &str = "renderer.version";

/// The release artifact for a crate version and platform target, as
/// `.github/workflows/test-native-renderer.yml` names it.
pub fn artifact_file_name(crate_version: &str, target: &str) -> String {
    format!("compose-rust-renderer-v{crate_version}-{target}.tar.gz")
}

/// Where that artifact and its checksum are downloaded from.
pub fn artifact_url(crate_version: &str, file_name: &str) -> String {
    version_artifact_url(RELEASE_BASE_URL, crate_version, file_name)
}

/// The same address under another base, which is what [`RENDERER_URL_ENV`] changes.
pub fn version_artifact_url(base: &str, crate_version: &str, file_name: &str) -> String {
    format!("{base}/download/v{crate_version}/{file_name}")
}

/// The base every download address is built on: the configured one, without a trailing
/// slash, or [`RELEASE_BASE_URL`] when none is configured or it is empty.
pub fn release_base_url(configured: Option<&str>) -> String {
    match configured.map(str::trim).filter(|url| !url.is_empty()) {
        Some(url) => url.trim_end_matches('/').to_owned(),
        None => RELEASE_BASE_URL.to_owned(),
    }
}

/// A schema hash as file names spell it: sixteen lowercase hex digits and no `0x`.
///
/// `schema-hash.txt` writes `0x` and lowercase, and file names leave the prefix out because
/// a name without it is safe everywhere. Any spelling of the same number gives the same
/// name. `None` for something that is not a 64-bit hex number at all.
pub fn schema_hash_in_file_names(hash: &str) -> Option<String> {
    let trimmed = hash.trim();
    let digits = trimmed
        .strip_prefix("0x")
        .or_else(|| trimmed.strip_prefix("0X"))
        .unwrap_or(trimmed);
    if digits.is_empty() || digits.len() > 16 || !digits.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let value = u64::from_str_radix(digits, 16).ok()?;
    Some(format!("{value:016x}"))
}

/// A schema hash as logs and messages spell it: `0x` and sixteen lowercase hex digits, the
/// same shape `schema-hash.txt` has, so a hash in a message can be searched for in a file.
pub fn schema_hash_in_messages(hash: &str) -> String {
    match schema_hash_in_file_names(hash) {
        Some(digits) => format!("0x{digits}"),
        None => hash.trim().to_owned(),
    }
}

fn file_name_digits(hash: &str) -> String {
    schema_hash_in_file_names(hash).unwrap_or_else(|| hash.trim().to_ascii_lowercase())
}

/// The renderer published for a schema rather than for a crate version. Its contents and
/// its `.sha256` are laid out exactly like the version artifact's.
pub fn schema_artifact_file_name(hash: &str, target: &str) -> String {
    format!(
        "compose-rust-renderer-schema-{}-{target}.tar.gz",
        file_name_digits(hash)
    )
}

/// Where a renderer published for a schema is downloaded from.
pub fn schema_artifact_url(base: &str, file_name: &str) -> String {
    format!("{base}/download/{SCHEMA_RELEASE_TAG}/{file_name}")
}

/// The page that lists what the release for a crate version carries.
pub fn version_release_page(base: &str, crate_version: &str) -> String {
    format!("{base}/tag/v{crate_version}")
}

/// The page that lists the renderers published by schema.
pub fn schema_release_page(base: &str) -> String {
    format!("{base}/tag/{SCHEMA_RELEASE_TAG}")
}

/// Every name and address one lookup by schema uses, worked out before anything is read or
/// fetched, so which file comes from where is a rule that can be checked on its own.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LookupPlan {
    /// Where a renderer of this schema and target is unpacked.
    pub cache_dir: PathBuf,
    pub version_artifact: String,
    pub version_url: String,
    pub schema_artifact: String,
    pub schema_url: String,
}

pub fn lookup_plan(
    base: &str,
    cache_root: &Path,
    crate_version: &str,
    crate_hash: &str,
    target: &str,
) -> LookupPlan {
    let version_artifact = artifact_file_name(crate_version, target);
    let schema_artifact = schema_artifact_file_name(crate_hash, target);
    LookupPlan {
        cache_dir: schema_cached_renderer_dir(cache_root, crate_hash, target),
        version_url: version_artifact_url(base, crate_version, &version_artifact),
        schema_url: schema_artifact_url(base, &schema_artifact),
        version_artifact,
        schema_artifact,
    }
}

/// What to do once the release for the crate version has been unpacked and its schema read.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AfterVersionRenderer {
    /// It was built from this crate's schema, or it is from before renderers said which
    /// schema they were built from and is used as it always was. The schema release is not
    /// asked for anything.
    Use,
    /// It was built from another schema. The renderer published for this crate's schema is
    /// the one to fetch.
    TrySchemaRenderer,
}

pub fn after_version_renderer(version_renderer_hash: Option<&str>, crate_hash: &str) -> AfterVersionRenderer {
    match schema_agreement(version_renderer_hash, Some(crate_hash)) {
        SchemaAgreement::Different { .. } => AfterVersionRenderer::TrySchemaRenderer,
        SchemaAgreement::Same | SchemaAgreement::Unknown => AfterVersionRenderer::Use,
    }
}

/// What one of the two releases turned out to hold, for the failure that names both.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Tried {
    /// The server answered that there is no such file.
    NotPublished,
    /// It was there, and it was built from this schema (`None`: it does not say).
    Carries(Option<String>),
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
        return local_app_data.map(|dir| dir.join("compose-rust"));
    }
    home.map(|dir| dir.join(".cache").join("compose-rust"))
}

/// One unpacked renderer, keyed so that two crate versions and two targets never collide.
///
/// The key builds used before renderers were found by schema, and the one a crate that
/// does not know its own schema hash still uses. A crate that knows it keys by schema
/// ([`schema_cached_renderer_dir`]) and reads an entry here only when the renderer in it
/// says it was built from the same schema.
pub fn cached_renderer_dir(cache_root: &Path, crate_version: &str, target: &str) -> PathBuf {
    cache_root
        .join("renderer")
        .join(format!("v{crate_version}"))
        .join(target)
}

/// One unpacked renderer, keyed by the schema it was built from and its target.
///
/// Not by crate version: two revisions of one version can carry different schemas, and the
/// renderer that works for one of them is refused by the other. Keyed by schema, one entry
/// also serves every crate version that shares it.
pub fn schema_cached_renderer_dir(cache_root: &Path, hash: &str, target: &str) -> PathBuf {
    cache_root
        .join("renderer")
        .join(format!("schema-{}", file_name_digits(hash)))
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
    /// The directory to add to the link search path.
    pub lib_dir: PathBuf,
    /// Whether that directory holds the static archive or the shared library.
    pub kind: RendererKind,
    /// The version recorded in the artifact, when it carries one.
    pub artifact_version: Option<String>,
    /// What answered: used for the one line the build prints about where its renderer
    /// came from, so a surprising build is diagnosable from the log alone.
    pub source: RendererSource,
    /// Whether it was unpacked from the renderer published for this crate's schema
    /// ([`SCHEMA_RELEASE_TAG`]) rather than from the release for its version.
    pub schema_release: bool,
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
    /// The in-repository build outputs, in the order they are preferred. None of them
    /// exists for a consumer of the published crate, which is the case every error path
    /// here is written for.
    pub workspace_dirs: &'a [&'a Path],
    /// The cache root, or `None` when the platform gave us nowhere to put one.
    pub cache_root: Option<&'a Path>,
    pub crate_version: &'a str,
    pub target: &'a str,
    pub target_os: &'a str,
    /// This crate's schema hash, as the `schema-hash.txt` beside its manifest says it.
    /// With it the cache is keyed by schema, and a release whose renderer was built from
    /// another schema is followed by the renderer published for this one. Without it (a
    /// checkout that has not run codegen) everything is keyed by crate version, as it was
    /// before renderers were published by schema.
    pub crate_schema_hash: Option<&'a str>,
    /// The value of [`RENDERER_URL_ENV`], if it is set.
    pub release_base_url: Option<&'a str>,
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
/// 2. The workspace build outputs, which only a checkout of this repository has.
/// 3. The cache, keyed by schema hash and target, which costs no network. A crate that does
///    not know its schema hash keys by crate version instead.
/// 4. The release for this crate version: the tarball in the download directory if it is
///    there, downloaded if not, verified and unpacked. When the renderer in it was built
///    from another schema than this crate's, the renderer published for this crate's schema
///    ([`SCHEMA_RELEASE_TAG`]) is fetched the same way. When neither matches, the build
///    stops and says what each one was built from.
///
/// Each place may hold either kind of renderer (see [`RendererKind`]); what is found there
/// is what is linked.
pub fn acquire_renderer(request: &Request) -> Result<Renderer, String> {
    if let Some(dir) = request.env_dir {
        let Some((lib_dir, kind)) = find_renderer_in(dir, request.target_os) else {
            return Err(missing_from_env_message(dir, request.target_os));
        };
        return finish(lib_dir, kind, request, RendererSource::Environment);
    }

    for workspace in request.workspace_dirs {
        if let Some((lib_dir, kind)) = find_renderer_in(workspace, request.target_os) {
            return finish(lib_dir, kind, request, RendererSource::Workspace);
        }
    }

    let Some(cache_root) = request.cache_root else {
        return Err(no_cache_root_message(request.crate_version, request.target));
    };

    match request.crate_schema_hash.and_then(schema_hash_in_file_names) {
        Some(crate_hash) => acquire_by_schema(request, cache_root, &crate_hash),
        None => acquire_by_version(request, cache_root),
    }
}

/// Steps 3 and 4 for a crate that does not know its schema hash: the cache and the release
/// for its version, and nothing else, because there is no hash to look anything else up by.
fn acquire_by_version(request: &Request, cache_root: &Path) -> Result<Renderer, String> {
    let cached = cached_renderer_dir(cache_root, request.crate_version, request.target);
    if let Some((lib_dir, kind)) = find_renderer_in(&cached, request.target_os) {
        return finish(lib_dir, kind, request, RendererSource::Cache);
    }

    if !PUBLISHED_TARGETS.contains(&request.target) {
        return Err(unpublished_target_message(
            request.crate_version,
            request.target,
        ));
    }

    let base = release_base_url(request.release_base_url);
    let artifact = artifact_file_name(request.crate_version, request.target);
    let page = version_release_page(&base, request.crate_version);
    let url_of = |name: &str| version_artifact_url(&base, request.crate_version, name);
    let source = match obtain(request, cache_root, &artifact, &url_of, &page) {
        Ok(source) => source,
        Err(Unobtained::NotFound { file, .. }) => {
            return Err(not_in_release_message(
                request.crate_version,
                request.target,
                &file,
                &page,
            ));
        }
        Err(Unobtained::Failed(message)) => return Err(message),
    };
    let tarball = download_dir(cache_root).join(&artifact);
    verify_checksum(&tarball, cache_root)?;
    unpack(
        &tarball,
        &cached,
        request.target_os,
        request.crate_version,
        request.target,
    )?;

    let Some((lib_dir, kind)) = find_renderer_in(&cached, request.target_os) else {
        return Err(unexpected_layout_message(
            &tarball,
            &cached,
            request.target_os,
        ));
    };
    finish(lib_dir, kind, request, source)
}

/// Steps 3 and 4 for a crate that knows its schema hash, which is every published crate.
fn acquire_by_schema(request: &Request, cache_root: &Path, crate_hash: &str) -> Result<Renderer, String> {
    let base = release_base_url(request.release_base_url);
    let plan = lookup_plan(
        &base,
        cache_root,
        request.crate_version,
        crate_hash,
        request.target,
    );

    // An entry counts only when the renderer in it says it was built from this schema. The
    // key already says so, and the file says it again because a directory can be left there
    // by anything: an interrupted run of an older build, or a hand.
    if let Some((lib_dir, kind)) = cached_for_schema(&plan.cache_dir, request.target_os, crate_hash, false) {
        return finish(lib_dir, kind, request, RendererSource::Cache);
    }
    // What builds left before the cache was keyed by schema. Used when the renderer in it
    // was built from this schema, or predates renderers saying so (which is how the entry
    // was used before). One built from another schema is passed over, not trusted and not
    // left to fail the build.
    let by_version = cached_renderer_dir(cache_root, request.crate_version, request.target);
    if let Some((lib_dir, kind)) = cached_for_schema(&by_version, request.target_os, crate_hash, true) {
        return finish(lib_dir, kind, request, RendererSource::Cache);
    }

    if !PUBLISHED_TARGETS.contains(&request.target) {
        return Err(unpublished_target_message(
            request.crate_version,
            request.target,
        ));
    }

    // The release for the crate version first, because it is the right renderer for every
    // build of a released version, and then nothing else is asked for.
    let version_page = version_release_page(&base, request.crate_version);
    let version_url_of = |name: &str| version_artifact_url(&base, request.crate_version, name);
    let version_tried = match obtain(
        request,
        cache_root,
        &plan.version_artifact,
        &version_url_of,
        &version_page,
    ) {
        Ok(source) => {
            let tarball = download_dir(cache_root).join(&plan.version_artifact);
            verify_checksum(&tarball, cache_root)?;
            let scratch = extract(&tarball, &cache_root.join("renderer"), &plan.version_artifact)?;
            let hash = read_schema_hash_in(&scratch);
            match after_version_renderer(hash.as_deref(), crate_hash) {
                AfterVersionRenderer::Use => {
                    // A renderer that does not say which schema it is from cannot be filed
                    // under one, so it goes where the version key put it before.
                    let destination = if hash.is_some() { &plan.cache_dir } else { &by_version };
                    return install_and_finish(&tarball, &scratch, destination, request, source, false);
                }
                AfterVersionRenderer::TrySchemaRenderer => {
                    // Still the right renderer for the schema it was built from, so it is
                    // kept under that one. Failing to keep it costs a later build an unpack,
                    // and this build nothing.
                    let other = schema_cached_renderer_dir(
                        cache_root,
                        hash.as_deref().unwrap_or_default(),
                        request.target,
                    );
                    if install(&scratch, &other, request.target_os, request.crate_version, request.target).is_err() {
                        let _ = std::fs::remove_dir_all(&scratch);
                    }
                    Tried::Carries(hash)
                }
            }
        }
        // A version that has not been released yet, which a git dependency on a revision
        // after a version bump is. The renderer published for its schema can still be there.
        Err(Unobtained::NotFound { .. }) => Tried::NotPublished,
        Err(Unobtained::Failed(message)) => return Err(message),
    };

    let schema_page = schema_release_page(&base);
    let schema_url_of = |name: &str| schema_artifact_url(&base, name);
    let source = match obtain(
        request,
        cache_root,
        &plan.schema_artifact,
        &schema_url_of,
        &schema_page,
    ) {
        Ok(source) => source,
        Err(Unobtained::NotFound { .. }) => {
            return Err(no_matching_renderer_message(
                crate_hash,
                &plan,
                &version_tried,
                &Tried::NotPublished,
                request.crate_version,
                request.target,
            ));
        }
        Err(Unobtained::Failed(message)) => return Err(message),
    };
    let tarball = download_dir(cache_root).join(&plan.schema_artifact);
    verify_checksum(&tarball, cache_root)?;
    let scratch = extract(&tarball, &cache_root.join("renderer"), &plan.schema_artifact)?;
    let hash = read_schema_hash_in(&scratch);
    if schema_agreement(hash.as_deref(), Some(crate_hash)) != SchemaAgreement::Same {
        let _ = std::fs::remove_dir_all(&scratch);
        return Err(no_matching_renderer_message(
            crate_hash,
            &plan,
            &version_tried,
            &Tried::Carries(hash),
            request.crate_version,
            request.target,
        ));
    }
    install_and_finish(&tarball, &scratch, &plan.cache_dir, request, source, true)
}

/// The renderer in a cache entry, when the entry is one this crate's schema may use.
fn cached_for_schema(
    dir: &Path,
    target_os: &str,
    crate_hash: &str,
    accept_unknown: bool,
) -> Option<(PathBuf, RendererKind)> {
    let found = find_renderer_in(dir, target_os)?;
    match schema_agreement(read_schema_hash_in(dir).as_deref(), Some(crate_hash)) {
        SchemaAgreement::Same => Some(found),
        SchemaAgreement::Unknown if accept_unknown => Some(found),
        _ => None,
    }
}

/// The schema hash in an artifact's root, where every renderer artifact carries it.
pub fn read_schema_hash_in(dir: &Path) -> Option<String> {
    let contents = std::fs::read_to_string(dir.join("schema-hash.txt")).ok()?;
    let trimmed = contents.trim();
    (!trimmed.is_empty()).then(|| trimmed.to_owned())
}

/// The schema hash of the renderer whose library sits in `lib_dir`: in the artifact root
/// above it, or beside it for a directory that holds the renderer itself. The same two
/// places the build script reads it from.
fn read_schema_hash_near(lib_dir: &Path) -> Option<String> {
    read_schema_hash_in(&lib_dir.join("..")).or_else(|| read_schema_hash_in(lib_dir))
}

/// Move an unpacked artifact into its cache entry and answer with the renderer in it.
fn install_and_finish(
    tarball: &Path,
    scratch: &Path,
    destination: &Path,
    request: &Request,
    source: RendererSource,
    schema_release: bool,
) -> Result<Renderer, String> {
    install(
        scratch,
        destination,
        request.target_os,
        request.crate_version,
        request.target,
    )?;
    let Some((lib_dir, kind)) = find_renderer_in(destination, request.target_os) else {
        return Err(unexpected_layout_message(
            tarball,
            destination,
            request.target_os,
        ));
    };
    let mut renderer = finish(lib_dir, kind, request, source)?;
    renderer.schema_release = schema_release;
    Ok(renderer)
}

/// Why an artifact could not be had.
enum Unobtained {
    /// The server answered that there is no such file. Whether that is the end depends on
    /// which file it was, so the caller words it.
    NotFound { file: String, url: String },
    /// Anything else, already worded for the person reading the build.
    Failed(String),
}

/// Make sure a tarball and its checksum are both in the download directory, by downloading
/// them if they are not. Returns which of those two it was.
fn obtain(
    request: &Request,
    cache_root: &Path,
    artifact: &str,
    url_of: &dyn Fn(&str) -> String,
    release_page: &str,
) -> Result<RendererSource, Unobtained> {
    let checksum_name = format!("{artifact}.sha256");
    let downloads = download_dir(cache_root);
    let tarball = downloads.join(artifact);
    let checksum = downloads.join(&checksum_name);

    if tarball.is_file() && checksum.is_file() {
        return Ok(RendererSource::LocalArtifact);
    }

    let Some(fetch) = request.fetch else {
        return Err(Unobtained::Failed(offline_message(
            artifact,
            release_page,
            request.crate_version,
            request.target,
            &downloads,
            "this build was told not to use the network",
        )));
    };

    std::fs::create_dir_all(&downloads).map_err(|error| {
        Unobtained::Failed(cannot_write_message(&downloads, &error.to_string()))
    })?;

    for (name, destination) in [(artifact, &tarball), (checksum_name.as_str(), &checksum)] {
        if destination.is_file() {
            continue;
        }
        let url = url_of(name);
        // Downloaded beside the final name and moved into place, so an interrupted
        // download is never mistaken for a complete one by the next build.
        let partial = destination.with_extension("partial");
        let _ = std::fs::remove_file(&partial);
        match fetch(&url, &partial) {
            Ok(()) => std::fs::rename(&partial, destination).map_err(|error| {
                Unobtained::Failed(cannot_write_message(destination, &error.to_string()))
            })?,
            Err(error) => {
                let _ = std::fs::remove_file(&partial);
                // Whatever is already downloaded is left alone: half an artifact with no
                // checksum beside it is simply not a cache hit next time.
                return Err(match error {
                    FetchError::Unreachable(detail) => Unobtained::Failed(offline_message(
                        artifact,
                        release_page,
                        request.crate_version,
                        request.target,
                        &downloads,
                        &detail,
                    )),
                    FetchError::NotFound => Unobtained::NotFound {
                        file: name.to_owned(),
                        url,
                    },
                    FetchError::Failed(detail) => {
                        Unobtained::Failed(download_failed_message(&url, &detail))
                    }
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
    let label = destination
        .file_name()
        .unwrap_or_default()
        .to_string_lossy()
        .into_owned();
    let scratch = extract(tarball, parent, &label)?;
    install(&scratch, destination, target_os, crate_version, target)
}

/// Extract a tarball into a scratch directory under `parent`, where no build looks for a
/// renderer, and return it. The schema it was built from can be read there before it is
/// decided where it goes.
fn extract(tarball: &Path, parent: &Path, label: &str) -> Result<PathBuf, String> {
    std::fs::create_dir_all(parent)
        .map_err(|error| cannot_write_message(parent, &error.to_string()))?;

    let scratch = parent.join(format!(".unpacking-{label}-{}", std::process::id()));
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
    Ok(scratch)
}

/// Name the renderer in a scratch directory after where it is going, and move it there.
/// The scratch directory is gone afterwards either way.
fn install(
    scratch: &Path,
    destination: &Path,
    target_os: &str,
    crate_version: &str,
    target: &str,
) -> Result<(), String> {
    // While it is still invisible to any other build, and using the name it is about to
    // have. Doing it after the rename would mean a second build could link the library in
    // the moment between the two. A static archive has no name to give: it is copied into
    // whatever links it. A missing renderer is reported properly by the layout check
    // downstream.
    if let Some((unpacked_dir, RendererKind::Shared)) = find_renderer_in(scratch, target_os) {
        let lib_file = renderer_lib_file(target_os);
        let relative = unpacked_dir
            .strip_prefix(scratch)
            .unwrap_or(Path::new(""))
            .to_path_buf();
        if let Err(message) = name_after_its_location(
            &unpacked_dir.join(lib_file),
            &destination.join(relative).join(lib_file),
            target_os,
            crate_version,
            target,
        ) {
            let _ = std::fs::remove_dir_all(scratch);
            return Err(message);
        }
    }

    let Some(parent) = destination.parent() else {
        let _ = std::fs::remove_dir_all(scratch);
        return Err(format!(
            "the renderer cache path {} has no parent",
            destination.display()
        ));
    };
    if let Err(error) = std::fs::create_dir_all(parent) {
        let _ = std::fs::remove_dir_all(scratch);
        return Err(cannot_write_message(parent, &error.to_string()));
    }
    let _ = std::fs::remove_dir_all(destination);
    std::fs::rename(scratch, destination).map_err(|error| {
        let _ = std::fs::remove_dir_all(scratch);
        cannot_write_message(destination, &error.to_string())
    })
}

/// Teach the renderer where it lives, so that anything linking it records that location
/// and can load it.
///
/// A library carries the name its dependents will look it up by. The macOS artifact
/// carries `@rpath/libcompose_rust_renderer.dylib`, and `@rpath` is resolved against the
/// rpaths of whatever loaded it. An application that merely depends on this crate has
/// none: Cargo passes a dependency's link search paths and link libraries down to the
/// final binary, but not its link arguments, and an rpath is a link argument. The
/// application would link cleanly and then die on startup with the loader unable to find
/// a library that is sitting right there on disk.
///
/// Linux has the same problem in a different shape. The ELF artifact carries no SONAME,
/// and a library with none that the linker found through `-l` and a search directory is
/// recorded by its bare file name, which the loader then searches for on a path the
/// application does not have either. The SONAME is the ELF counterpart of the install
/// name: whatever it says is what every dependent records. So on both platforms the
/// library's own name is set to its absolute path.
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
        "linux" => set_soname(library, name, crate_version, target),
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

/// The ELF side: `DT_SONAME`, which every linker copies into the `DT_NEEDED` entry of
/// whatever links the library.
///
/// Without one, GNU ld records a library it found by searching the `-L` directories under
/// its bare file name, and the loader has to search for that. With one that is an absolute
/// path, the dependent records that path and the loader opens it directly. Whether the
/// artifact arrives with no SONAME, which is how the renderer is built, or with a bare one,
/// the result is the same. A library that already answers to the right path is not written
/// to, as on macOS.
fn set_soname(library: &Path, name: &Path, crate_version: &str, target: &str) -> Result<(), String> {
    let Some(text) = name.to_str() else {
        return Err(soname_message(
            name,
            "the path is not UTF-8, and this build only writes UTF-8 names",
            crate_version,
            target,
        ));
    };
    elf::set_soname(library, text)
        .map(|_| ())
        .map_err(|detail| soname_message(name, &detail, crate_version, target))
}

/// Check the version the artifact declares, if it declares one, name the library after
/// where it sits, and package the answer.
///
/// The naming happens here rather than only where an artifact is unpacked, so that the
/// renderer a developer of this repository builds from source is loaded exactly the way
/// the one a consumer downloads is. Two different loading paths meant the one people use
/// every day was not the one that was broken.
fn finish(
    lib_dir: PathBuf,
    kind: RendererKind,
    request: &Request,
    source: RendererSource,
) -> Result<Renderer, String> {
    // Whatever was found, from here on it is an absolute path. The name written into the
    // library is the name every application that links it will look it up by, and a
    // relative one would be resolved against whatever directory that application happens
    // to be started from. `COMPOSE_RUST_RENDERER_DIR` is often set to a path relative to
    // the build, which is how this would otherwise happen.
    let lib_dir = absolute(&lib_dir);
    let artifact_version = read_artifact_version(&lib_dir);
    // The schema is what has to match, and the version file stood in for it before
    // renderers said which schema they were built from. A renderer that says it was built
    // from this crate's schema is the right one whatever version it was packaged for: the
    // release for one version and the renderer published for a schema are the same build
    // when the schema has not moved, and the cache is shared by every version of a schema.
    let vouched_for_by_schema = request.crate_schema_hash.is_some_and(|ours| {
        schema_agreement(read_schema_hash_near(&lib_dir).as_deref(), Some(ours))
            == SchemaAgreement::Same
    });
    if let Some(found) = artifact_version.as_deref().filter(|_| !vouched_for_by_schema) {
        if found != request.crate_version {
            return Err(version_mismatch_message(
                &lib_dir,
                found,
                request.crate_version,
                request.target,
            ));
        }
    }

    // Only a shared library is looked up by name when the application starts. An archive
    // is inside the application by then.
    if kind == RendererKind::Shared {
        let library = lib_dir.join(renderer_lib_file(request.target_os));
        name_after_its_location(
            &library,
            &library,
            request.target_os,
            request.crate_version,
            request.target,
        )?;
    }

    Ok(Renderer {
        lib_dir,
        kind,
        artifact_version,
        source,
        schema_release: false,
    })
}

/// The same directory, spelled absolutely.
///
/// The name written into the library is the name every application that links it will
/// look it up by, so it has to mean the same thing from any working directory.
/// `COMPOSE_RUST_RENDERER_DIR` is routinely set to a path relative to the build, which
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

/// Where the build scripts these messages name live. The published crate carries none of
/// them, so a message that names one says it is in a checkout of this repository.
pub const REPOSITORY_URL: &str = "https://github.com/DarkPyonix/compose-rust";

/// The escape hatch every failure here ends with. It is the same lines every time on
/// purpose: whatever went wrong, pointing the variable at a renderer is the way past it.
fn build_it_yourself(crate_version: &str, target: &str) -> String {
    let artifact = artifact_file_name(crate_version, target);
    format!(
        "Any renderer you already have works too. Unpack {artifact}, or build one in a\n\
         checkout of the compose-rust repository ({REPOSITORY_URL}): the static archive\n\
         with renderer/desktop/scripts/build-macos.sh or\n\
         renderer/desktop/scripts/build-linux.sh, the shared library with\n\
         renderer/desktop/scripts/build-native.sh. Then set\n\
         {RENDERER_DIR_ENV} to the directory it wrote. The variable is checked first and\n\
         nothing is downloaded when it is set."
    )
}

/// A static-renderer variable (`COMPOSE_RUST_MACOS_STATIC_DIR` and its Linux twin) names a
/// directory without the archive in it.
pub fn static_override_missing_message(dir: &Path, archive: &str) -> String {
    format!(
        "compose-rust: {dir} does not hold {archive}.\n\
         \n\
         The variable names the directory a static renderer build wrote. In a checkout of\n\
         the compose-rust repository ({REPOSITORY_URL}),\n\
         renderer/desktop/scripts/build-macos.sh writes renderer/build/macos and\n\
         renderer/desktop/scripts/build-linux.sh writes renderer/build/linux. Unset the\n\
         variable and this build finds or downloads a renderer by itself.",
        dir = dir.display()
    )
}

/// What to do about a Windows renderer kit that is incomplete or unreadable. The kit is
/// either a download in the cache, which can simply be fetched again, or a directory
/// someone built and named with a variable.
fn windows_kit_way_out(dir: &Path) -> String {
    format!(
        "If {dir} is in the renderer cache, delete it and build again: the next build\n\
         downloads the kit again. If you built the kit yourself in a checkout of the\n\
         compose-rust repository ({REPOSITORY_URL}), run\n\
         renderer/desktop/scripts/build-windows.sh again, or set DXC_WINDOWS_NATIVE_LIB\n\
         (or {RENDERER_DIR_ENV}) to the directory it wrote.",
        dir = dir.display()
    )
}

/// A Windows renderer kit without one of the pieces an MSVC link needs.
pub fn windows_kit_incomplete_message(dir: &Path, piece: &str, what: &str) -> String {
    format!(
        "compose-rust: the Windows renderer kit at {} has no {piece} ({what}).\n\
         \n\
         {}",
        dir.display(),
        windows_kit_way_out(dir)
    )
}

/// A library the Windows link rewrites that could not be read: one of the kit at `kit`, or,
/// with no kit, one of the MSVC tools themselves.
pub fn windows_library_unreadable_message(library: &Path, error: &str, kit: Option<&Path>) -> String {
    let way_out = match kit {
        Some(kit) => windows_kit_way_out(kit),
        None => "It comes with the MSVC C++ build tools. Install or repair them (the Visual\n\
                 Studio Installer's \"Desktop development with C++\") and build again."
            .to_owned(),
    };
    format!(
        "compose-rust: could not read {}: {error}\n\
         \n\
         {way_out}",
        library.display()
    )
}

fn missing_from_env_message(env_dir: &Path, target_os: &str) -> String {
    format!(
        "compose-rust: {RENDERER_DIR_ENV} is set to {env_dir}, but no renderer is there.\n\
         \n\
         Looked for {archive} (the static renderer) and for {shared} (the shared one) in\n\
         {env_dir}, in {env_dir}/lib and in {env_dir}/bin. Point the variable either at the\n\
         directory an artifact was unpacked into or straight at the directory holding the\n\
         renderer.\n\
         \n\
         Nothing was downloaded, because the variable is set. Unset it and this build\n\
         fetches the renderer for its own version by itself.",
        env_dir = env_dir.display(),
        archive = renderer_static_file(target_os),
        shared = renderer_lib_file(target_os),
    )
}

fn unpublished_target_message(crate_version: &str, target: &str) -> String {
    format!(
        "compose-rust: no renderer is published for {target}.\n\
         \n\
         The release builds these targets: {published}. The static renderer, which makes an\n\
         application one executable, is published for {static_targets}. This build is for\n\
         {target}, so there is nothing to download, and a download would only have produced\n\
         a 404 that does not say which of the two is missing.\n\
         \n\
         {build_it_yourself}",
        published = PUBLISHED_TARGETS.join(", "),
        static_targets = STATIC_TARGETS.join(", "),
        build_it_yourself = build_it_yourself(crate_version, target),
    )
}

fn offline_message(
    artifact: &str,
    release_page: &str,
    crate_version: &str,
    target: &str,
    downloads: &Path,
    detail: &str,
) -> String {
    format!(
        "compose-rust: could not download the renderer ({detail}).\n\
         \n\
         Put these two files in {downloads} and build again. Nothing else is needed, and\n\
         the next build will verify the checksum and unpack them without a network:\n\
         \n\
         \x20   {artifact}\n\
         \x20   {artifact}.sha256\n\
         \n\
         Both are attached to this release:\n\
         \x20   {release_page}\n\
         \n\
         {build_it_yourself}",
        downloads = downloads.display(),
        build_it_yourself = build_it_yourself(crate_version, target),
    )
}

fn not_in_release_message(
    crate_version: &str,
    target: &str,
    file_name: &str,
    release_page: &str,
) -> String {
    format!(
        "compose-rust: the release for v{crate_version} does not carry {file_name}.\n\
         \n\
         The server was reached and answered that there is no such file, so this is not a\n\
         network problem. Either that release was published without the {target} renderer,\n\
         or it predates this crate version being tagged at all. What it does carry is\n\
         listed at:\n\
         \x20   {release_page}\n\
         \n\
         {build_it_yourself}",
        build_it_yourself = build_it_yourself(crate_version, target),
    )
}

/// Neither the release for the crate version nor the renderer published for the crate's
/// schema was built from that schema. Every hash and both addresses are in the message,
/// because which of them is wrong decides what to do about it.
fn no_matching_renderer_message(
    crate_hash: &str,
    plan: &LookupPlan,
    version: &Tried,
    schema: &Tried,
    crate_version: &str,
    target: &str,
) -> String {
    let describe = |tried: &Tried| match tried {
        Tried::NotPublished => "not published".to_owned(),
        Tried::Carries(Some(hash)) => format!("built from {}", schema_hash_in_messages(hash)),
        Tried::Carries(None) => "does not say which schema it was built from".to_owned(),
    };
    format!(
        "compose-rust: no published renderer was built from this crate's schema.\n\
         \n\
         \x20   crate              {crate_hash}\n\
         \x20   version release    {version}\n\
         \x20                      {version_url}\n\
         \x20   schema renderer    {schema}\n\
         \x20                      {schema_url}\n\
         \n\
         The release for v{crate_version} is the renderer for every build of that version,\n\
         and `develop` publishes a renderer under each schema hash it reaches. This crate is a\n\
         revision whose schema has neither: most likely one that has not been through\n\
         `develop`, or one published so recently that its renderer is still being built.\n\
         Linking a renderer of another schema would build a program that opens a window and\n\
         draws nothing, so the build stops here.\n\
         \n\
         {build_it_yourself}",
        crate_hash = schema_hash_in_messages(crate_hash),
        version = describe(version),
        version_url = plan.version_url,
        schema = describe(schema),
        schema_url = plan.schema_url,
        build_it_yourself = build_it_yourself(crate_version, target),
    )
}

fn download_failed_message(url: &str, detail: &str) -> String {
    format!(
        "compose-rust: downloading the renderer failed ({detail}).\n\
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
        "compose-rust: the renderer artifact does not match its checksum, so it was not\n\
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
        "compose-rust: cannot read the renderer checksum at {checksum_path} ({detail}).\n\
         \n\
         The file holds one line: the SHA-256 digest, then the artifact name, exactly as\n\
         `shasum -a 256` writes it. Delete it and build again to download it afresh.",
        checksum_path = checksum_path.display(),
    )
}

fn cannot_read_artifact_message(tarball: &Path, detail: &str) -> String {
    format!(
        "compose-rust: cannot read the renderer artifact at {tarball} ({detail}).\n\
         \n\
         Delete it and build again to download it afresh.",
        tarball = tarball.display(),
    )
}

fn cannot_write_message(path: &Path, detail: &str) -> String {
    format!(
        "compose-rust: cannot write to {path} ({detail}).\n\
         \n\
         That path is the renderer cache. Set {CACHE_DIR_ENV} to a directory this build can\n\
         write to, or set {RENDERER_DIR_ENV} to a renderer you already have, which skips\n\
         the cache entirely.",
        path = path.display(),
    )
}

fn no_cache_root_message(crate_version: &str, target: &str) -> String {
    format!(
        "compose-rust: there is nowhere to cache the renderer.\n\
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
        "compose-rust: could not set the renderer's install name ({detail}).\n\
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
        "compose-rust: could not set the renderer's SONAME ({detail}).\n\
         \n\
         The library has to be told that it lives at\n\
         \n\
         \x20   {library}\n\
         \n\
         or anything linking it records its bare file name, `{file}`, and the loader cannot\n\
         find that in an application that has no matching rpath. This build makes the change\n\
         by editing the file, with no tool needed. `patchelf --set-soname` makes the same\n\
         change and can make room where this could not. A renderer in a directory this build\n\
         cannot write to has to carry that name already; copy it somewhere writable and\n\
         point {RENDERER_DIR_ENV} at the copy.\n\
         \n\
         {build_it_yourself}",
        library = library.display(),
        file = renderer_lib_file("linux"),
        build_it_yourself = build_it_yourself(crate_version, target),
    )
}

fn unpack_failed_message(tarball: &Path, detail: &str) -> String {
    format!(
        "compose-rust: could not unpack the renderer artifact ({detail}).\n\
         \n\
         The file is {tarball} and its checksum matched, so it arrived intact. What failed\n\
         was extracting it, which needs `tar` on PATH. macOS and Linux always have it, and\n\
         Windows has carried it in System32 since Windows 10 1803.",
        tarball = tarball.display(),
    )
}

fn unexpected_layout_message(tarball: &Path, unpacked_into: &Path, target_os: &str) -> String {
    format!(
        "compose-rust: the renderer artifact unpacked, but does not contain a renderer.\n\
         \n\
         Expected lib/{archive} or {subdir}/{shared} under {unpacked_into}, from {tarball}.\n\
         An artifact built for a different platform would look exactly like this. Delete that\n\
         directory and the downloaded artifact, then build again.",
        tarball = tarball.display(),
        unpacked_into = unpacked_into.display(),
        archive = renderer_static_file(target_os),
        subdir = renderer_lib_subdir(target_os),
        shared = renderer_lib_file(target_os),
    )
}

/// Why a static renderer cannot be linked for a platform, when nothing says what it needs
/// from the system.
///
/// Each platform's archive calls into a different set of system libraries, and the build
/// script holds that list per platform. A platform with an archive and no list is one whose
/// static renderer has not been brought up yet; linking it anyway fails with thousands of
/// undefined references that say nothing about why.
pub fn no_static_link_message(target: &str, lib_dir: &Path) -> String {
    format!(
        "compose-rust: {lib_dir} holds a static renderer for {target}, and this crate does not\n\
         yet know how to link one there.\n\
         \n\
         The static renderer is linked for {static_targets}. For {target} use the shared\n\
         renderer the release publishes: unset {RENDERER_DIR_ENV}, or point it at a\n\
         distribution that holds the shared library.",
        lib_dir = lib_dir.display(),
        static_targets = STATIC_TARGETS.join(", "),
    )
}

fn version_mismatch_message(
    lib_dir: &Path,
    artifact_version: &str,
    crate_version: &str,
    target: &str,
) -> String {
    format!(
        "compose-rust: renderer version mismatch.\n\
         \n\
         The renderer in {lib_dir} declares version {artifact_version} ({file}), but this\n\
         crate is version {crate_version}. Linking them would pair a Host against a Renderer\n\
         it was never built for, and the mismatch would surface as a protocol error at\n\
         runtime instead of here.\n\
         \n\
         Use the artifact for this crate version, {artifact}, or depend on\n\
         compose-rust {artifact_version} instead.",
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
    let plan = lookup_plan(
        RELEASE_BASE_URL,
        sample_dir,
        version,
        "0x62b9a55ed371acad",
        target,
    );
    vec![
        missing_from_env_message(sample_dir, "macos"),
        unpublished_target_message(version, "freebsd-x86_64"),
        offline_message(
            &artifact_file_name(version, target),
            &version_release_page(RELEASE_BASE_URL, version),
            version,
            target,
            sample_dir,
            "curl could not resolve the host",
        ),
        not_in_release_message(
            version,
            target,
            &artifact_file_name(version, target),
            &version_release_page(RELEASE_BASE_URL, version),
        ),
        no_matching_renderer_message(
            "0x62b9a55ed371acad",
            &plan,
            &Tried::Carries(Some("0x284215b3caced78f".to_owned())),
            &Tried::Carries(None),
            version,
            target,
        ),
        no_matching_renderer_message(
            "0x62b9a55ed371acad",
            &plan,
            &Tried::NotPublished,
            &Tried::NotPublished,
            version,
            target,
        ),
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
        unexpected_layout_message(&tarball, sample_dir, "macos"),
        no_static_link_message("freebsd-x64", sample_dir),
        version_mismatch_message(sample_dir, "0.1.0", version, target),
        build_it_yourself(version, target),
        static_override_missing_message(sample_dir, renderer_static_file("macos")),
        windows_kit_incomplete_message(sample_dir, "skiko/skia.lib", "Skia"),
        windows_library_unreadable_message(
            &sample_dir.join("skiko").join("skia.lib"),
            "permission denied",
            Some(sample_dir),
        ),
        windows_library_unreadable_message(&sample_dir.join("libcpmt.lib"), "no such file", None),
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
/// `compose_rust_renderer_run` for it. On iOS that symbol is really there by the time
/// anything runs. On Android the renderer is Kotlin in ART and there is no native symbol
/// of that name at all, so every launch ended at `dlopen failed: cannot locate symbol
/// "compose_rust_renderer_run"` before a single line of the application ran. The JNI
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
    "androidx.activity:activity-compose:1.10.1",
    "androidx.compose.ui:ui:1.9.0",
    "androidx.compose.foundation:foundation:1.9.0",
    "androidx.compose.material3:material3:1.4.0",
];

/// The lowest `compileSdk` the Compose libraries above accept. Compose 1.9 carries AAR
/// metadata that refuses a module compiled against anything older, and the dx template
/// pins 34.
const ANDROID_COMPOSE_MIN_COMPILE_SDK: u32 = 35;

/// The module text with `compileSdk` raised to [`ANDROID_COMPOSE_MIN_COMPILE_SDK`], or
/// `None` when it is already that high or the module does not set one in a form this reads
/// (`compileSdk = 34`).
fn with_compile_sdk(module: &str) -> Option<String> {
    let key = "compileSdk";
    let at = module.find(key)?;
    let rest = &module[at + key.len()..];
    let digits_at = rest.find(|c: char| c.is_ascii_digit())?;
    if !rest[..digits_at].trim().starts_with('=') {
        return None;
    }
    let digits: String = rest[digits_at..].chars().take_while(char::is_ascii_digit).collect();
    if digits.parse::<u32>().ok()? >= ANDROID_COMPOSE_MIN_COMPILE_SDK {
        return None;
    }
    let from = at + key.len() + digits_at;
    let mut text = module.to_owned();
    text.replace_range(from..from + digits.len(), &ANDROID_COMPOSE_MIN_COMPILE_SDK.to_string());
    Some(text)
}

/// The Gradle plugin that compiles `@Composable`, which Kotlin 2.0 made compulsory.
const COMPOSE_PLUGIN_ID: &str = "org.jetbrains.kotlin.plugin.compose";

/// The application's manifest with the notification permission declared, or `None` when it
/// already declares it or has no `<manifest>` element to put it in.
///
/// The declaration goes directly inside `<manifest>`, before anything else in it, which is
/// where Android reads permissions from.
pub fn with_notification_permission(manifest: &str) -> Option<String> {
    const PERMISSION: &str = "android.permission.POST_NOTIFICATIONS";
    if manifest.contains(PERMISSION) {
        return None;
    }
    let open = manifest.find("<manifest")?;
    let end = open + manifest[open..].find('>')? + 1;
    let mut text = String::with_capacity(manifest.len() + 96);
    text.push_str(&manifest[..end]);
    text.push_str("\n    <uses-permission android:name=\"");
    text.push_str(PERMISSION);
    text.push_str("\" />");
    text.push_str(&manifest[end..]);
    Some(text)
}

/// The application's manifest with its first Activity told to resize for the keyboard, or
/// `None` when it already says so or has no `<activity>` to say it on.
///
/// Without it Android pans the window instead: the whole screen slides up by the
/// keyboard's height, so the bar at the top leaves the screen and runs under the status
/// bar, and nothing below the focused field is laid out for the space that is left. With
/// it, an edge-to-edge window is not resized either, but it is told how much the keyboard
/// covers, and the renderer stops the page above it while bars and overlays keep their
/// place. The Activity is the one the build generates, so the setting goes on that.
///
/// Any `state...` part of an existing value is kept; an `adjust...` part other than
/// `adjustResize` is what causes the pan, so it is replaced.
pub fn with_keyboard_resize(manifest: &str) -> Option<String> {
    const ATTRIBUTE: &str = "android:windowSoftInputMode=\"";
    const RESIZE: &str = "adjustResize";
    let open = manifest
        .match_indices("<activity")
        .map(|(at, _)| at)
        .find(|&at| {
            manifest[at + "<activity".len()..]
                .chars()
                .next()
                .is_some_and(char::is_whitespace)
        })?;
    let end = open + manifest[open..].find('>')?;
    let tag = &manifest[open..end];
    let Some(attribute_at) = tag.find(ATTRIBUTE) else {
        let after = open + "<activity".len();
        let mut text = String::with_capacity(manifest.len() + 64);
        text.push_str(&manifest[..after]);
        text.push_str(&format!("\n            {ATTRIBUTE}{RESIZE}\""));
        text.push_str(&manifest[after..]);
        return Some(text);
    };
    let value_at = open + attribute_at + ATTRIBUTE.len();
    let value_end = value_at + manifest[value_at..end].find('"')?;
    let value = &manifest[value_at..value_end];
    if value.split('|').any(|part| part.trim() == RESIZE) {
        return None;
    }
    let mut parts: Vec<&str> = value
        .split('|')
        .map(str::trim)
        .filter(|part| !part.is_empty() && !part.starts_with("adjust"))
        .collect();
    parts.push(RESIZE);
    let mut text = String::with_capacity(manifest.len() + 16);
    text.push_str(&manifest[..value_at]);
    text.push_str(&parts.join("|"));
    text.push_str(&manifest[value_end..]);
    Some(text)
}

/// The manifest property that tells the system UI an Activity may run as several
/// instances (`WindowManager.PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI`, API 35).
pub const ANDROID_MULTI_INSTANCE_PROPERTY: &str =
    "android.window.PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI";

/// The application's manifest with its first Activity declaring that the system UI may
/// start it as another instance, or `None` when it already declares the property (either
/// way) or has no `<activity>` to declare it on.
///
/// A window torn off a multi-window application is started by the system: the drag
/// carries an intent for the same Activity, and split screen or desktop windowing launches
/// it when the drop lands at a screen edge or on the desktop. Android 16 refuses that
/// launch for an Activity that does not declare the property, and logs that the app does
/// not support multi-instance. The Activity is the one the build generates, and it is the
/// one the tear-off starts, so the property goes on that.
pub fn with_multi_instance(manifest: &str) -> Option<String> {
    if manifest.contains(ANDROID_MULTI_INSTANCE_PROPERTY) {
        return None;
    }
    let open = manifest
        .match_indices("<activity")
        .map(|(at, _)| at)
        .find(|&at| {
            manifest[at + "<activity".len()..]
                .chars()
                .next()
                .is_some_and(char::is_whitespace)
        })?;
    let close = open + manifest[open..].find('>')?;
    let property = format!(
        "<property android:name=\"{ANDROID_MULTI_INSTANCE_PROPERTY}\" android:value=\"true\" />"
    );
    let mut text = String::with_capacity(manifest.len() + property.len() + 32);
    if manifest[..close].ends_with('/') {
        // `<activity ... />` has no body to put the property in, so it gets one.
        text.push_str(manifest[..close - 1].trim_end());
        text.push_str(">\n            ");
        text.push_str(&property);
        text.push_str("\n        </activity>");
    } else {
        text.push_str(&manifest[..=close]);
        text.push_str("\n            ");
        text.push_str(&property);
    }
    text.push_str(&manifest[close + 1..]);
    Some(text)
}

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
    if let Some(raised) = with_compile_sdk(&module_text) {
        module_text = raised;
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
    // The same number however it is spelled: `0x` or not, either case.
    if file_name_digits(renderer) == file_name_digits(crate_hash) {
        SchemaAgreement::Same
    } else {
        SchemaAgreement::Different {
            renderer: renderer.to_owned(),
            crate_hash: crate_hash.to_owned(),
        }
    }
}
