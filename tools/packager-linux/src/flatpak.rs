//! The Flatpak channel: a manifest `flatpak-builder` builds, in the shape Flathub reviews.
//!
//! Two kinds of payload, because Flathub accepts both and an application built with this
//! project can need either:
//!
//! - **From source.** The Rust program is compiled inside the sandbox from a source
//!   archive, with every crate vendored by `flatpak-cargo-generator` (the build has no
//!   network). This is what Flathub expects of an open source application.
//! - **Prebuilt.** An archive of the built program, fetched by URL and checked by SHA-256.
//!   This is the path for the renderer: it is compiled by GraalVM native-image, which no
//!   Flathub SDK extension provides, so it arrives as a release artifact either way.
//!
//! The window is AWT's on X11, so the sandbox gets an X11 socket, shared IPC (X11's
//! shared memory transport needs it, and without it every frame is copied through the
//! socket) and the GPU. Wayland sessions reach it through XWayland. A Wayland socket with
//! X11 only as a fallback is for a renderer that speaks Wayland itself, which this one
//! does not yet.

use crate::appstream::metainfo_file_name;
use crate::desktop::desktop_file_name;
use crate::icons::{Icon, IconKind};
use crate::metadata::AppMetadata;

/// The runtime branch used when the application's file does not name one.
pub const DEFAULT_RUNTIME_VERSION: &str = "26.08";

/// Where a source comes from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SourceRef {
    /// Fetched by `flatpak-builder`, checked against the digest. What Flathub requires.
    /// `arch` limits it to one architecture, for an archive built per architecture.
    Url {
        url: String,
        sha256: String,
        arch: Option<String>,
    },
    /// A file beside the manifest. For local builds and tests; Flathub refuses it for
    /// anything but small packaging files.
    Path(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Payload {
    /// An archive whose top directory holds the executable and everything beside it, one
    /// per architecture.
    Prebuilt { archives: Vec<SourceRef> },
    /// A source archive of a Cargo workspace.
    FromSource {
        source: SourceRef,
        /// The file `flatpak-cargo-generator` wrote, relative to the manifest.
        cargo_sources: String,
        package: String,
        bin: String,
        /// Extra arguments for `cargo build`, such as `--no-default-features`.
        cargo_args: Vec<String>,
        /// The renderer's release archive, when the program draws, one per architecture.
        renderer: Vec<SourceRef>,
    },
}

#[derive(Debug, Clone)]
pub struct FlatpakOptions<'a> {
    pub payload: Payload,
    pub icons: &'a [Icon],
    /// Give the sandbox a Wayland socket and keep X11 only as a fallback.
    pub wayland: bool,
}

/// The sandbox permissions: the window's own, then the application's extras.
pub fn finish_args(meta: &AppMetadata, wayland: bool) -> Vec<String> {
    let mut args: Vec<String> = if wayland {
        vec![
            "--share=ipc".into(),
            "--socket=wayland".into(),
            "--socket=fallback-x11".into(),
            "--device=dri".into(),
        ]
    } else {
        vec![
            "--share=ipc".into(),
            "--socket=x11".into(),
            "--device=dri".into(),
        ]
    };
    for extra in &meta.finish_args {
        if !args.contains(extra) {
            args.push(extra.clone());
        }
    }
    args
}

/// Where a packaging file sits beside the manifest, and where the build finds it.
pub fn icon_source_path(meta: &AppMetadata, icon: &Icon) -> String {
    match icon.kind {
        IconKind::Png(size) => format!("icons/{size}x{size}/{}.png", meta.id),
        IconKind::Svg => format!("icons/scalable/{}.svg", meta.id),
    }
}

/// The manifest, as JSON.
pub fn manifest(meta: &AppMetadata, options: &FlatpakOptions) -> String {
    let module = meta.exec.clone();
    let lib = format!("/app/lib/{}", meta.exec);
    let mut sources = Vec::new();
    let mut commands: Vec<String> = Vec::new();
    let mut build_options: Vec<(String, Json)> = Vec::new();
    let mut top_extra: Vec<(String, Json)> = Vec::new();

    match &options.payload {
        Payload::Prebuilt { archives } => {
            for archive in archives {
                sources.push(archive_source(archive, Some("payload")));
            }
            commands.push(format!("mkdir -p {lib} /app/bin"));
            commands.push(format!("cp -a payload/. {lib}/"));
            // A release archive whose executable was built without an rpath only finds the
            // renderer beside it when told to look there.
            commands.push(format!(
                "readelf -d {lib}/{exec} | grep -qE '\\((RPATH|RUNPATH)\\)' || patchelf --set-rpath '$ORIGIN' {lib}/{exec}",
                exec = meta.exec
            ));
        }
        Payload::FromSource {
            source,
            cargo_sources,
            package,
            bin,
            cargo_args,
            renderer,
        } => {
            top_extra.push((
                "sdk-extensions".into(),
                Json::Array(vec![Json::str("org.freedesktop.Sdk.Extension.rust-stable")]),
            ));
            build_options.push((
                "append-path".into(),
                Json::str("/usr/lib/sdk/rust-stable/bin"),
            ));
            let mut env = vec![
                (
                    "CARGO_HOME".to_owned(),
                    Json::str(&format!("/run/build/{module}/cargo")),
                ),
                ("CARGO_NET_OFFLINE".to_owned(), Json::str("true")),
                (
                    "DIOXUS_COMPOSE_APP_VERSION".to_owned(),
                    Json::str(&meta.version),
                ),
            ];
            sources.push(archive_source(source, None));
            sources.push(Json::str(cargo_sources));
            for archive in renderer {
                sources.push(archive_source(archive, Some("renderer")));
            }
            if !renderer.is_empty() {
                env.push((
                    "DIOXUS_COMPOSE_RENDERER_DIR".to_owned(),
                    Json::str(&format!("/run/build/{module}/renderer")),
                ));
            }
            build_options.push(("env".into(), Json::Object(env)));

            let mut build =
                format!("cargo --offline build --release --locked -p {package} --bin {bin}");
            for arg in cargo_args {
                build.push(' ');
                build.push_str(&shell_quote(arg));
            }
            commands.push("cargo --offline fetch --manifest-path Cargo.toml --verbose".into());
            commands.push(build);
            commands.push(format!("mkdir -p {lib} /app/bin"));
            if !renderer.is_empty() {
                // The renderer's files go beside the executable, the layout it finds its
                // companions from. A release archive keeps them in `lib/`.
                commands.push(format!(
                    "if [ -d renderer/lib ]; then cp -a renderer/lib/. {lib}/; else cp -a renderer/. {lib}/; fi"
                ));
            }
            commands.push(format!(
                "install -m755 target/release/{bin} {lib}/{}",
                meta.exec
            ));
            if !renderer.is_empty() {
                // The build records where the renderer was inside the build sandbox. Point
                // the executable at the copy installed beside it instead.
                commands.push(format!(
                    "recorded=\"$(readelf -d {lib}/{exec} | sed -n 's/.*(NEEDED).*\\[\\(.*libdioxus_compose_renderer.so\\)\\]/\\1/p')\"; \
                     if [ -n \"$recorded\" ] && [ \"$recorded\" != libdioxus_compose_renderer.so ]; then \
                     patchelf --replace-needed \"$recorded\" libdioxus_compose_renderer.so {lib}/{exec}; fi; \
                     patchelf --set-rpath '$ORIGIN' {lib}/{exec}",
                    exec = meta.exec
                ));
            }
        }
    }
    commands.push(format!("test -x {lib}/{}", meta.exec));
    commands.push(format!("ln -s ../lib/{0}/{0} /app/bin/{0}", meta.exec));

    let desktop = desktop_file_name(meta);
    let metainfo = metainfo_file_name(meta);
    sources.push(file_source(&desktop, "packaging"));
    sources.push(file_source(&metainfo, "packaging"));
    commands.push(format!(
        "install -Dm644 packaging/{desktop} /app/share/applications/{desktop}"
    ));
    commands.push(format!(
        "install -Dm644 packaging/{metainfo} /app/share/metainfo/{metainfo}"
    ));
    for icon in options.icons {
        let path = icon_source_path(meta, icon);
        let dir = path.rsplit_once('/').map(|(dir, _)| dir).unwrap_or("");
        sources.push(file_source(&path, &format!("packaging/{dir}")));
        commands.push(format!(
            "install -Dm644 packaging/{path} /app/{}",
            icon.install_path(&meta.id)
        ));
    }

    let mut module_fields = vec![
        ("name".to_owned(), Json::str(&module)),
        ("buildsystem".to_owned(), Json::str("simple")),
    ];
    if !build_options.is_empty() {
        module_fields.push(("build-options".to_owned(), Json::Object(build_options)));
    }
    module_fields.push((
        "build-commands".to_owned(),
        Json::Array(commands.iter().map(|c| Json::str(c)).collect()),
    ));
    module_fields.push(("sources".to_owned(), Json::Array(sources)));

    let runtime_version = meta
        .runtime_version
        .clone()
        .unwrap_or_else(|| DEFAULT_RUNTIME_VERSION.to_owned());
    let mut top = vec![
        ("id".to_owned(), Json::str(&meta.id)),
        ("runtime".to_owned(), Json::str("org.freedesktop.Platform")),
        ("runtime-version".to_owned(), Json::str(&runtime_version)),
        ("sdk".to_owned(), Json::str("org.freedesktop.Sdk")),
    ];
    top.extend(top_extra);
    top.push(("command".to_owned(), Json::str(&meta.exec)));
    top.push((
        "finish-args".to_owned(),
        Json::Array(
            finish_args(meta, options.wayland)
                .iter()
                .map(|a| Json::str(a))
                .collect(),
        ),
    ));
    let needs_patchelf = match &options.payload {
        Payload::Prebuilt { .. } => true,
        Payload::FromSource { renderer, .. } => !renderer.is_empty(),
    };
    let mut modules = Vec::new();
    if needs_patchelf {
        modules.push(patchelf_module());
    }
    modules.push(Json::Object(module_fields));
    top.push(("modules".to_owned(), Json::Array(modules)));
    render_manifest(top)
}

/// The SDK has `readelf` but not `patchelf`, so a build that has to rewrite the
/// executable's library names builds it first. `cleanup` keeps it out of the application.
pub const PATCHELF_URL: &str =
    "https://github.com/NixOS/patchelf/releases/download/0.19.1/patchelf-0.19.1.tar.gz";
pub const PATCHELF_SHA256: &str =
    "491108728f120ce05b539934b41a750235031a6df8abc6b47e57aff7de15094d";

fn patchelf_module() -> Json {
    Json::Object(vec![
        ("name".to_owned(), Json::str("patchelf")),
        ("buildsystem".to_owned(), Json::str("autotools")),
        ("cleanup".to_owned(), Json::Array(vec![Json::str("*")])),
        (
            "sources".to_owned(),
            Json::Array(vec![archive_source(
                &SourceRef::Url {
                    url: PATCHELF_URL.to_owned(),
                    sha256: PATCHELF_SHA256.to_owned(),
                    arch: None,
                },
                None,
            )]),
        ),
    ])
}

fn render_manifest(top: Vec<(String, Json)>) -> String {
    let mut out = Json::Object(top).render(0);
    out.push('\n');
    out
}

fn archive_source(source: &SourceRef, dest: Option<&str>) -> Json {
    let mut fields = vec![("type".to_owned(), Json::str("archive"))];
    match source {
        SourceRef::Url { url, sha256, arch } => {
            fields.push(("url".to_owned(), Json::str(url)));
            fields.push(("sha256".to_owned(), Json::str(sha256)));
            if let Some(arch) = arch {
                fields.push(("only-arches".to_owned(), Json::Array(vec![Json::str(arch)])));
            }
        }
        SourceRef::Path(path) => fields.push(("path".to_owned(), Json::str(path))),
    }
    if let Some(dest) = dest {
        fields.push(("dest".to_owned(), Json::str(dest)));
    }
    Json::Object(fields)
}

fn file_source(path: &str, dest: &str) -> Json {
    Json::Object(vec![
        ("type".to_owned(), Json::str("file")),
        ("path".to_owned(), Json::str(path)),
        ("dest".to_owned(), Json::str(dest)),
    ])
}

/// Quotes one word for `sh` unless it is plainly safe.
pub fn shell_quote(word: &str) -> String {
    if !word.is_empty()
        && word
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "-_=.,/:+@".contains(c))
    {
        word.to_owned()
    } else {
        format!("'{}'", word.replace('\'', "'\\''"))
    }
}

/// Just enough JSON to write a manifest whose keys stay in the order a reader expects.
#[derive(Debug, Clone, PartialEq)]
pub enum Json {
    String(String),
    Array(Vec<Json>),
    Object(Vec<(String, Json)>),
}

impl Json {
    pub fn str(value: &str) -> Json {
        Json::String(value.to_owned())
    }

    pub fn render(&self, depth: usize) -> String {
        let pad = "    ".repeat(depth + 1);
        let close = "    ".repeat(depth);
        match self {
            Json::String(value) => quote(value),
            Json::Array(items) if items.is_empty() => "[]".to_owned(),
            Json::Array(items) => {
                let inner: Vec<String> = items
                    .iter()
                    .map(|item| format!("{pad}{}", item.render(depth + 1)))
                    .collect();
                format!("[\n{}\n{close}]", inner.join(",\n"))
            }
            Json::Object(fields) if fields.is_empty() => "{}".to_owned(),
            Json::Object(fields) => {
                let inner: Vec<String> = fields
                    .iter()
                    .map(|(key, value)| format!("{pad}{}: {}", quote(key), value.render(depth + 1)))
                    .collect();
                format!("{{\n{}\n{close}}}", inner.join(",\n"))
            }
        }
    }
}

fn quote(value: &str) -> String {
    let mut out = String::with_capacity(value.len() + 2);
    out.push('"');
    for c in value.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}
