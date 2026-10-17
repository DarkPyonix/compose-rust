# compose-rust

[![Test](https://github.com/DarkPyonix/compose-rust/actions/workflows/test.yml/badge.svg)](https://github.com/DarkPyonix/compose-rust/actions/workflows/test.yml)
[![Native renderer test](https://github.com/DarkPyonix/compose-rust/actions/workflows/test-native-renderer.yml/badge.svg)](https://github.com/DarkPyonix/compose-rust/actions/workflows/test-native-renderer.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Rust 1.85+](https://img.shields.io/badge/rust-1.85%2B-orange.svg)](https://www.rust-lang.org)

**English** · [한국어](docs/locales/README_ko.md)

**Compose-shaped UI in Rust, drawn by an AOT-compiled Compose renderer.**

*Native UI with no webview and no bundled JVM, aiming at one executable per desktop app.*

compose-rust is four things put together:

- **A Compose-shaped Rust API** with a slot table and a recomposition runtime, in progress
  (see [Status](#-status)). A composable is a function, state lives in `remember`, and when
  state changes only the scopes that read it run again. Nothing compares trees to find what
  changed.
- **A narrow C ABI boundary and a schema.** Rust defines the widgets, properties and records
  once; the Kotlin types and the platform shims are generated from that definition, never
  written by hand.
- **An AOT-compiled Compose renderer.** Compiled ahead of time to native code on every
  platform, Kotlin/Wasm in the browser and an Android library on Android. Compose's own text
  layout, widgets and platform IME do the drawing and the typing.
- **No webview, and no bundled JVM.** On the desktop an application is one executable that links
  only the system's own libraries. The 0.0.1 release does not do that yet: its desktop renderers
  are GraalVM native-image shared libraries that sit beside the application. From the next
  release, macOS and Linux (x64 and arm64) download a Kotlin/Native static archive instead, and
  the build links it into the executable; CI proves the result on all three. Windows follows.

---

## 🚦 Status

**0.0.x is the name reservation and renderer distribution stage.** The API will change.

What the crate contains today:

- **The boundary.** The `compose_rust_host_*` C functions the renderer calls, `Host`,
  `LaunchBuilder`, `launch_runtime`, and `request_frame_from_worker` for waking a frame from a
  worker thread.
- **The protocol.** Fixed-layout mutation records, written into one batch buffer and read in
  place by the renderer, with no serialisation format on the hot path.
- **The schema.** Widgets, properties, modifiers, design roles, `Theme` and `DesignSystem`,
  with a schema hash that stops a Host and a renderer built from different schemas from
  running together. The `codegen` binary generates the Kotlin side from it.
- **The `Runtime` trait**, the interface whatever builds the tree implements, plus the
  supporting types around it: assets, custom drawing, input events, notifications, messages,
  palettes and window size classes.
- **The renderer download.** The build script fetches the prebuilt renderer for your target,
  checks it and links it (see [Getting started](#-getting-started)).

What it does **not** contain yet is its own authoring API. `#[composable]`, the `Recomposer`
and `launch` are in progress ([#23](https://github.com/DarkPyonix/compose-rust/issues/23), [#64](https://github.com/DarkPyonix/compose-rust/issues/64)), targeting **1.0.0 on 2026-11-14** (the window-code move to the fork finishes on 2026-10-31, plus two weeks of stabilisation). Until then the crate is a
foundation for a layer that builds the tree, not something you write screens with directly.

### The planned shape

> ⚠️ **Planned, not yet in the crate.** The code below shows the intended shape of the
> authoring API. It does not compile against 0.0.x, and the names are not final.

```rust
#[composable]
fn counter() {
    let count = remember(|| mutable_state_of(0));
    Column(Modifier::new().fill_max_width(), || {
        Text(format!("{}", count.get()));
        Button(|| count.set(count.get() + 1), || Text("+1"));
    });
}

fn main() {
    compose_rust::launch(counter);
}
```

What is settled about it: `#[composable]` inserts the groups, so you never write groups or keys
by hand, and they close correctly through branches, loops, early `return`, `?` and unwinding.
Names follow Compose. The widget vocabulary is the one in the schema; nothing is defined twice.

> 🧩 **Prefer Dioxus and `rsx!`?** Use [dioxus-compose](https://github.com/DarkPyonix/dioxus-compose), which runs on top of compose-rust and supports both HTML/CSS `rsx!` and Compose-widget `rsx!`.

### Designed, not yet on develop

These are designed and agreed, but none of them is in the crate or the renderer on `develop`.
Where work exists, it sits in an open pull request or a branch.

| Item | Status | Issue | Work in progress |
|---|---|---|---|
| The authoring API (`#[composable]`, `remember`, `launch`) | partial | [#23](https://github.com/DarkPyonix/compose-rust/issues/23), [#64](https://github.com/DarkPyonix/compose-rust/issues/64) | branch `feature/compose-api` |
| Direct Kotlin to Rust calls on the web, replacing the JavaScript forwarder | partial | [#54](https://github.com/DarkPyonix/compose-rust/issues/54) | [#84](https://github.com/DarkPyonix/compose-rust/pull/84) |
| HTML/CSS drawing elements (`AbsoluteBox` and its modifiers) | partial | [#104](https://github.com/DarkPyonix/compose-rust/issues/104) | [#74](https://github.com/DarkPyonix/compose-rust/pull/74) |
| Transforms and animations the renderer plays | partial | [#105](https://github.com/DarkPyonix/compose-rust/issues/105) | [#83](https://github.com/DarkPyonix/compose-rust/pull/83) |
| HTML text properties and the measure call | partial | [#106](https://github.com/DarkPyonix/compose-rust/issues/106) | branch `feat/measure-call` |
| OS text size and app zoom | partial | [#68](https://github.com/DarkPyonix/compose-rust/issues/68) | [#77](https://github.com/DarkPyonix/compose-rust/pull/77) (OS text size on the desktop only) |
| Action keys (dense keypad buttons) | partial | [#57](https://github.com/DarkPyonix/compose-rust/issues/57) | [#76](https://github.com/DarkPyonix/compose-rust/pull/76) |
| The bar's title in the platform caption | planned | [#79](https://github.com/DarkPyonix/compose-rust/issues/79) | none |
| Design systems split into per-system libraries and an adaptive layer | partial | [#39](https://github.com/DarkPyonix/compose-rust/issues/39) | branch `chore/thisisthepy-coordinates` in the Compose forks |
| The AWT-free Kotlin/Native renderer as the default on macOS and Linux | partial | [#22](https://github.com/DarkPyonix/compose-rust/issues/22) | branch `feature/native-default-renderer` |
| The Windows Kotlin/Native renderer | partial | [#24](https://github.com/DarkPyonix/compose-rust/issues/24) | branch `feature/windows-kotlin-native` |
| Samples rewritten on the authoring API | planned | [#85](https://github.com/DarkPyonix/compose-rust/issues/85) | none |
| An event when the system colour scheme changes | proposed, awaiting approval | [#97](https://github.com/DarkPyonix/compose-rust/issues/97) | none |
| Focus-gained and key-up events per node | proposed, awaiting approval | [#98](https://github.com/DarkPyonix/compose-rust/issues/98) | none |

---

## 🖥 Platforms

| Platform | State | Renderer |
|---|---|---|
| 🍎 **macOS (arm64)** | **Works end to end** | 0.0.1 ships a GraalVM native-image library beside the app. The Kotlin/Native renderer linked into the executable (its own window, drawing through Metal) runs in CI and replaces it in a following release. Basic Korean IME input works; the full IME checklist is not finished |
| 🐧 Linux (x64, arm64) | Builds and starts | 0.0.1 ships a GraalVM native-image library. The Kotlin/Native renderer (its own X11 window, drawing through GLX; arm64 cross compiled on x64) replaces it in a following release. In CI an application built from it is one executable naming only libc, X11, GL, fontconfig and the C++ runtime (and EGL on arm64), and copied alone into an empty directory it draws under Xvfb. Nobody has used it interactively yet |
| 🪟 Windows | Builds and starts | GraalVM native image, smoke-tested on every renderer change. The Kotlin/Native renderer that makes Windows one executable too is planned ([#24](https://github.com/DarkPyonix/compose-rust/issues/24)) |
| 📱 iOS | Builds and starts | Kotlin/Native static archive exporting the same C symbols, released as an XCFramework |
| 🤖 Android | **Works end to end** | A Kotlin Activity owns the process and the loop, Rust is a cdylib, and the JNI shims on both sides are generated from the schema. The crate carries the renderer's Kotlin sources and its build script stages them into the Gradle project |
| 🌐 Web (wasm) | **Works end to end** | One `WebAssembly.Memory`, owned by the Kotlin/Wasm module and imported by the Rust one, so a batch is read where it was written. Calls from the renderer to the Host cross a generated JavaScript forwarder, measured at about 12 ns. Direct calls without the forwarder are planned ([#54](https://github.com/DarkPyonix/compose-rust/issues/54)) |

### What it weighs

The notepad sample, built for release and stripped, with the Kotlin/Native renderer linked in
(the path that becomes the default after 0.0.1). One executable: no runtime beside it, no
virtual machine inside it, and nothing linked but the system's own libraries.

| Platform | Executable | Physical footprint |
|---|---|---|
| macOS (arm64) | **28.76 MB** | **35.1 MB** |
| Linux (x86-64) | **37.93 MB** | not yet measured |
| Windows | not yet measured | not yet measured |

For scale, the same kind of app on macOS was four files and 93.1 MB, with a 56 MB footprint,
while the renderer still carried a Java runtime. In the browser, the renderer and Skia together
are 6.95 MB gzipped.

---

## 🏗 How it works

```
┌──────────────────────── Host (Rust) ────────────────────────┐
│  your code                                                  │
│  the layer that builds the tree (Runtime)                   │
│  compose-rust: schema, fixed-layout records, one batch      │
└──────────────────────────────┬──────────────────────────────┘
                               │
              synchronous, same-thread direct calls
              primitives, pointers and lengths only
                               │
┌──────────────────────────────┴──────────────────────────────┐
│  generated shims (C exports, JNI, wasm)                     │
│  protocol decoder ──► node table                            │
│  schema interpreter ──► Compose                             │
└──────────────────── Renderer (Kotlin, AOT) ─────────────────┘
```

**Rust describes the UI; Compose interprets it.** Rust never calls the Compose API directly.
The tree travels as a value, as fixed-layout records, and a general-purpose interpreter on the
Kotlin side turns them into a real Compose tree. Cash App's Redwood and Jetpack Glance use the
same pattern.

**The boundary is synchronous and on one thread.** The Host runs on the renderer's UI thread and
the two sides call each other directly, the way JSI replaced React Native's old bridge. There is
no queue and no thread hop, so an event handler can return a result within the same call (for
example, whether a key press was consumed). Heavy work runs on Host worker threads, which update
state and ask for a frame; application code never calls a boundary function.

**Only primitives, pointers and lengths cross it.** Type safety comes back above that line
because the Kotlin types are generated from the single Rust definition, and the schema hash
fails the build when the two drift.

**UI-local state stays in Kotlin.** `TextField` is uncontrolled and IME composition text never
makes a round trip through Rust. Scroll position, focus and animation state belong to the
renderer too.

---

## 🎨 Design systems

Seven design systems, including Material 3, Apple HIG, Fluent and Liquid Glass, are chosen per
application. Widgets emit roles (colour, type, shape, space), and the renderer resolves them to
tokens, so switching to dark mode is one theme change rather than a property update on every
node.

The design systems have moved out of this repository into the thisisthepy Compose fork, where
they are becoming ordinary Compose libraries under `org.thisisthepy.compose.*`: one component library per system
(`org.thisisthepy.compose.material3`, `.cupertino`, `.fluent`, `.liquidglass` and the others),
`org.thisisthepy.compose.adaptive` on top of them, which follows the platform's own system by
default, and `org.thisisthepy.compose.designsystem` for the contract both layers share.

---

## 🚀 Getting started

### Using the crate

```toml
[dependencies]
compose-rust = "0.0.1"
```

`cargo build` works out which renderer the target needs, downloads the release artifact for the
crate's exact version, checks it against the published `.sha256`, unpacks it into a cache
outside `target/`, and links it. There is no environment variable to set and no script to run.
The cache is keyed by version and target, so it survives `cargo clean` and is shared between
projects.

Where the artifact is the static archive (macOS and Linux from the next release), what `cargo
build` produces is **one executable**: the renderer, Compose, Skia and ICU are inside it, nothing
sits beside it, and it loads only the system's libraries (frameworks and `/usr/lib` on macOS;
libc, X11, GL, fontconfig and FreeType on Linux). Copy it anywhere and it runs. Windows downloads
the GraalVM native image, a DLL with its companions beside it, until its static renderer lands.

| Variable | Effect |
|---|---|
| `COMPOSE_RUST_RENDERER_DIR` | Use the renderer in this directory: an unpacked artifact, or the directory a renderer build wrote. It may hold the static archive or a shared library, and whichever it holds is linked. Checked first, and nothing is downloaded when it is set, so a renderer you built yourself, a vendored copy or an offline build all work through it |
| `COMPOSE_RUST_CACHE_DIR` | Move the cache off `$HOME/.cache/compose-rust` (`%LOCALAPPDATA%\compose-rust` on Windows) |

`default-features = false` builds with no renderer at all, for a headless or documentation
build. A binary built that way says what is missing and exits non-zero rather than opening no
window.

### Working on this repository

```bash
./scripts/setup-check.sh   # checks every tool the build needs and prints the fix for anything missing
./scripts/check.sh         # fmt, clippy, tests, quick benchmarks, Kotlin build and tests
```

The workspace targets Rust **1.85+** (edition 2024); `rustfmt` and `clippy` are required. Kotlin
needs nothing installed: `renderer/kotlin` (`kotlin.bat` on Windows) downloads the pinned
toolchain on first use.

**Build the renderer.** It uses a pinned commit of
[`thisisthepy/compose-multiplatform-core-extended`](https://github.com/thisisthepy/compose-multiplatform-core-extended),
a Compose fork that adds what the published build lacks (the Linux targets, the text selection
menu, the copy keys). Build that once, then the renderer:

```bash
# macOS
./renderer/scripts/build-compose.sh
./renderer/desktop/scripts/build-macos.sh --release                # build/macos/

# Linux (both architectures' Compose; arm64 is cross compiled on x64)
./renderer/scripts/build-compose.sh --target linux
./renderer/desktop/scripts/build-linux.sh --release                # build/linux/
./renderer/desktop/scripts/build-linux.sh --release --arch arm64   # build/linux-arm64/
```

Each produces one static library, `libcompose_rust_renderer.a`, with Compose, Skia and the
interpreter inside it, the same archive the release ships, with `schema-hash.txt` beside it. In a
checkout the build links that workspace build before anything it could download, then a native
image in `renderer/build/native-image/dist/lib`; `DXC_MACOS_NATIVE_LIB` and
`DXC_LINUX_NATIVE_LIB` still name a static renderer directory directly, ahead of everything.
Windows still builds the GraalVM native image, with
`renderer/desktop/scripts/build-native-windows.ps1`.

CI packages the archive with `.github/scripts/package-static-renderer.sh` and proves the result
with `.github/scripts/check-single-executable.sh`: it builds an application from the artifact
exactly as the release carries it, reads `otool -L` or `readelf -d`, then copies the executable
alone into an empty directory and requires it to draw and, with no ICU data file on the machine,
to lay Korean out correctly (every glyph found, words found whole, lines broken between words).

**The JVM development shell** is the fastest loop when working on the renderer itself, with hot
reload and `@Preview`. The JVM is allowed only here; shipped artifacts never contain one.

```bash
cd renderer && ./kotlin run -m desktop
```

---

## 🗂 Project layout

```
compose-rust/     the crate: boundary, protocol, schema, codegen, renderer download
renderer/         the Kotlin renderer: interpreter, generated shims, one module per platform
samples/          12 sample applications, being rewritten; they do not build until then (#85)
docs/             the user guide (docs/guide) and translations (docs/locales)
scripts/          setup check, quality gate, release and publishing scripts, script tests
.github/          CI workflows
```

The Dioxus adapter and its baseline moved to [dioxus-compose](https://github.com/DarkPyonix/dioxus-compose).
The samples under `samples/` are being rewritten on the compose-rust authoring API (#85) and do
not build until then. The design systems moved to the Compose fork, under `extended/design-systems/` in
[thisisthepy/compose-multiplatform-core-extended](https://github.com/thisisthepy/compose-multiplatform-core-extended).

---

## 📚 Documentation

- **Guide:** [darkpyonix.dev/compose-rust](https://darkpyonix.dev/compose-rust/), in English and
  Korean.
- **Repository:** [github.com/DarkPyonix/compose-rust](https://github.com/DarkPyonix/compose-rust)

---

## 📄 License

[Apache License 2.0](LICENSE).
