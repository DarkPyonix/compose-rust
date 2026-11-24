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

use crate::icons::{Icon, IconKind};
use crate::metadata::AppMetadata;
use crate::templates;

/// The runtime branch used when the application's file does not name one.
pub const DEFAULT_RUNTIME_VERSION: &str = "25.08";

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

/// The manifest, as JSON: the fork's `flatpak-manifest.json.in` filled from `meta`, then
/// what the template does not have. The payload's sources and commands, the build options,
/// the window's socket and the application's permissions, the icons and the patchelf
/// module are added around what the template holds, keeping its order.
pub fn manifest(meta: &AppMetadata, options: &FlatpakOptions) -> String {
    manifest_with(templates::FLATPAK, meta, options)
}

/// [`manifest`] from a given template, so a test can show the output follows it.
pub fn manifest_with(template: &str, meta: &AppMetadata, options: &FlatpakOptions) -> String {
    let runtime_version = meta
        .runtime_version
        .clone()
        .unwrap_or_else(|| DEFAULT_RUNTIME_VERSION.to_owned());
    let rendered = templates::render(
        template,
        &[
            ("identifier", &templates::json_escape(&meta.id)),
            ("runtime_version", &templates::json_escape(&runtime_version)),
            ("executable", &templates::json_escape(&meta.exec)),
        ],
    );
    let mut top = match templates::parse_json(&rendered) {
        Ok(Json::Object(fields)) => fields,
        Ok(_) => panic!("the Flatpak template is not a JSON object"),
        Err(error) => panic!("the Flatpak template is not JSON once filled: {error}"),
    };

    let module = meta.exec.clone();
    let lib = format!("/app/lib/{}", meta.exec);
    // Sources and commands before and after the template's own.
    let mut payload_sources = Vec::new();
    let mut before_mkdir: Vec<String> = Vec::new();
    let mut after_mkdir: Vec<String> = Vec::new();
    let mut build_options: Vec<(String, Json)> = Vec::new();
    let mut top_extra: Vec<(String, Json)> = Vec::new();

    match &options.payload {
        Payload::Prebuilt { archives } => {
            for archive in archives {
                payload_sources.push(archive_source(archive, Some("payload")));
            }
            after_mkdir.push(format!("cp -a payload/. {lib}/"));
            // A release archive whose executable was built without an rpath only finds the
            // renderer beside it when told to look there.
            after_mkdir.push(format!(
                "readelf -d {lib}/{exec} | grep -qE '\\((RPATH|RUNPATH)\\)' || patchelf --set-rpath '$ORIGIN:$ORIGIN/lib' {lib}/{exec}",
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
                    "COMPOSE_RUST_APP_VERSION".to_owned(),
                    Json::str(&meta.version),
                ),
            ];
            payload_sources.push(archive_source(source, None));
            payload_sources.push(Json::str(cargo_sources));
            for archive in renderer {
                payload_sources.push(archive_source(archive, Some("renderer")));
            }
            if !renderer.is_empty() {
                env.push((
                    "COMPOSE_RUST_RENDERER_DIR".to_owned(),
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
            before_mkdir.push("cargo --offline fetch --manifest-path Cargo.toml --verbose".into());
            before_mkdir.push(build);
            if !renderer.is_empty() {
                // The renderer keeps its own `lib` directory beside the executable. AWT
                // looks for its companions in the parent of the renderer's directory plus
                // `lib`, so the files flattened beside the executable are not found.
                after_mkdir.push(format!(
                    "mkdir -p {lib}/lib && if [ -d renderer/lib ]; then cp -a renderer/lib/. {lib}/lib/; else cp -a renderer/. {lib}/lib/; fi"
                ));
            }
            after_mkdir.push(format!(
                "install -m755 target/release/{bin} {lib}/{}",
                meta.exec
            ));
            if !renderer.is_empty() {
                // The build records where the renderer was inside the build sandbox. Point
                // the executable at the copy installed beside it instead. Executables built
                // before the rename to compose-rust name the renderer
                // libdioxus_compose_renderer.so, so the match takes either name.
                after_mkdir.push(format!(
                    "recorded=\"$(readelf -d {lib}/{exec} | sed -n 's/.*(NEEDED).*\\[\\(.*lib[a-z_]*_renderer.so\\)\\]/\\1/p')\"; \
                     if [ -n \"$recorded\" ] && [ \"$recorded\" != \"${{recorded##*/}}\" ]; then \
                     patchelf --replace-needed \"$recorded\" \"${{recorded##*/}}\" {lib}/{exec}; fi; \
                     patchelf --set-rpath '$ORIGIN/lib' {lib}/{exec}",
                    exec = meta.exec
                ));
            }
        }
    }

    let mut icon_sources = Vec::new();
    let mut icon_commands = Vec::new();
    for icon in options.icons {
        let path = icon_source_path(meta, icon);
        let dir = path.rsplit_once('/').map(|(dir, _)| dir).unwrap_or("");
        icon_sources.push(file_source(&path, &format!("packaging/{dir}")));
        icon_commands.push(format!(
            "install -Dm644 packaging/{path} /app/{}",
            icon.install_path(&meta.id)
        ));
    }

    // The window's socket after the template's IPC, the application's permissions last.
    let wanted = finish_args(meta, options.wayland);
    let finish = field(&mut top, "finish-args");
    let Json::Array(args) = finish else {
        panic!("the Flatpak template's finish-args is not an array")
    };
    // Each template argument keeps its place; what the application wants before it goes
    // before it, and the rest after the last.
    let mut rest: Vec<Json> = wanted.iter().map(|a| Json::str(a)).collect();
    let mut merged: Vec<Json> = Vec::new();
    for arg in args.iter() {
        match rest.iter().position(|a| a == arg) {
            Some(index) => merged.extend(rest.drain(..=index)),
            None => merged.push(arg.clone()),
        }
    }
    merged.extend(rest);
    *finish = Json::Array(merged);

    let needs_patchelf = match &options.payload {
        Payload::Prebuilt { .. } => true,
        Payload::FromSource { renderer, .. } => !renderer.is_empty(),
    };
    {
        let Json::Array(modules) = field(&mut top, "modules") else {
            panic!("the Flatpak template's modules is not an array")
        };
        let Some(Json::Object(app)) = modules.last_mut() else {
            panic!("the Flatpak template has no application module")
        };
        if let Json::Array(commands) = field(app, "build-commands") {
            let mkdir = commands
                .iter()
                .position(|c| matches!(c, Json::String(s) if s.starts_with("mkdir -p ")))
                .expect("the Flatpak template makes the application's directories");
            for (offset, command) in after_mkdir.iter().enumerate() {
                commands.insert(mkdir + 1 + offset, Json::str(command));
            }
            for (offset, command) in before_mkdir.iter().enumerate() {
                commands.insert(mkdir + offset, Json::str(command));
            }
            commands.extend(icon_commands.iter().map(|c| Json::str(c)));
        }
        if let Json::Array(sources) = field(app, "sources") {
            for (offset, source) in payload_sources.into_iter().enumerate() {
                sources.insert(offset, source);
            }
            sources.extend(icon_sources);
        }
        if !build_options.is_empty() {
            let at = app
                .iter()
                .position(|(key, _)| key == "build-commands")
                .unwrap_or(app.len());
            app.insert(at, ("build-options".into(), Json::Object(build_options)));
        }
        if needs_patchelf {
            modules.insert(0, patchelf_module());
        }
    }
    if !top_extra.is_empty() {
        let at = top
            .iter()
            .position(|(key, _)| key == "command")
            .unwrap_or(top.len());
        for (offset, extra) in top_extra.into_iter().enumerate() {
            top.insert(at + offset, extra);
        }
    }
    render_manifest(top)
}

/// The value of `key` in an object the template holds.
fn field<'a>(object: &'a mut [(String, Json)], key: &str) -> &'a mut Json {
    object
        .iter_mut()
        .find(|(k, _)| k == key)
        .map(|(_, value)| value)
        .unwrap_or_else(|| panic!("the Flatpak template has no {key:?}"))
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
