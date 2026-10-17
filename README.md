# compose-rust

[![Test](https://github.com/DarkPyonix/compose-rust/actions/workflows/test.yml/badge.svg)](https://github.com/DarkPyonix/compose-rust/actions/workflows/test.yml)
[![Native renderer test](https://github.com/DarkPyonix/compose-rust/actions/workflows/test-native-renderer.yml/badge.svg)](https://github.com/DarkPyonix/compose-rust/actions/workflows/test-native-renderer.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Rust 1.85+](https://img.shields.io/badge/rust-1.85%2B-orange.svg)](https://www.rust-lang.org)

**English** · [한국어](docs/locales/README_ko.md)

**Compose-shaped UI in Rust, drawn by an AOT-compiled Compose renderer.**

*Native UI with no webview and no bundled JVM: one executable per desktop app.*

compose-rust is four things put together:

- **A Compose-shaped Rust API** with a slot table and a recomposition runtime, in 0.0.2
  (see [Status](#-status)). A composable is a function, a widget is a
  call with Compose's name, state lives in `remember`, and when state changes only the scopes
  that read it run again. Nothing compares trees to find what changed.
- **A narrow C ABI boundary and a schema.** Rust defines the widgets, properties and records
  once; the Kotlin types and the platform shims are generated from that definition, never
  written by hand.
- **An AOT-compiled Compose renderer.** Compiled with Kotlin/Native on the desktop and iOS, to
  Kotlin/Wasm in the browser and to an Android library on Android. Compose's own text layout,
  widgets and platform IME do the drawing and the typing. The desktop window, its input, IME,
  menus, chrome and resizing come from the Compose fork,
  [thisisthepy/compose-multiplatform-core-extended](https://github.com/thisisthepy/compose-multiplatform-core-extended).
- **No webview, and no bundled JVM.** On the desktop an application is one executable that links
  only the system's own libraries. From 0.0.2, macOS and Linux (x64 and arm64) download a
  Kotlin/Native static archive, and the build links it into the executable; CI proves the result
  on all three. Windows downloads a GraalVM native-image shared library beside the application
  until its Kotlin/Native static archive is published
  ([#24](https://github.com/DarkPyonix/compose-rust/issues/24)). From a checkout the Windows
  Kotlin/Native renderer already builds into one MSVC-linked executable.

---

## 🚦 Status

**0.0.x is the name reservation and renderer distribution stage.** The API will change.

What the 0.0.2 crate contains:

- **The authoring API.** `#[composable]`, `remember`, the states and effects, the `Recomposer`,
  `launch` and one function per widget
  ([#23](https://github.com/DarkPyonix/compose-rust/issues/23), [#64](https://github.com/DarkPyonix/compose-rust/issues/64)).
  It is new in 0.0.2, with animation: `Modifier.graphics_layer`, `Modifier.alpha`, and
  `animate_float_as_state`, `animate_color_as_state` and `animate_offset_as_state`, whose
  values go to `Modifier.animated_alpha`, `animated_background` and `animated_offset`. Each new
  target sends one transition that the renderer plays on its own clock, and nothing is composed
  again while it plays ([Animation](https://darkpyonix.dev/compose-rust/en/writing-ui.html#animation)).
  It is a pre-1.0 API that may still change, and it is stable from **1.0.0, planned for
  2026-11-14**.
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
- **Drawing, transforms and animations**, new in 0.0.2. HTML/CSS drawing elements (`AbsoluteBox`
  and the modifiers `RequiredSize`, `BorderEach`, `CornerEach`, `Shadow`, `Clip` and `Alpha`), a
  `Transform` modifier for 2D CSS transforms, and animations the renderer plays itself
  (`StartAnimation`, `ControlAnimation`, `AnimationEvent` and `ReducedMotionChanged`). A layout
  container's text colour is inherited by the text inside it, and a border's colour can animate.
- **The renderer download.** The build script fetches the prebuilt renderer for your target,
  checks it and links it (see [Getting started](#-getting-started)).

### What it looks like

```rust
use compose_rust::runtime::*;
use compose_rust::ui::*;

#[composable]
fn counter() {
    let count = remember(|| mutable_state_of(0));
    Column().modifier(Modifier.padding(16.0).fill_max_width()).content(|| {
        Text(format!("Count: {}", count.get())).type_role(TypeRole::Title);
        let more = count.clone();
        Button("Increment").on_click(move || more.update(|count| *count += 1));
    });
}

fn main() {
    compose_rust::launch(counter);
}
```

A widget is a statement: `Text("Hello")` describes a widget, its methods set what Compose takes
as named arguments, and a container's children are the closure given to `content`.
`#[composable]` inserts the groups, so you never write them by hand, and they close correctly
through branches, early `return`, `?` and unwinding. Names follow Compose. The widget vocabulary
is the one in the schema; nothing is defined twice. The
[guide](https://darkpyonix.dev/compose-rust/en/writing-ui.html) covers the rest.

> 🧩 **Prefer Dioxus and `rsx!`?** Use [dioxus-compose](https://github.com/DarkPyonix/dioxus-compose), which runs on top of compose-rust and supports both HTML/CSS `rsx!` and Compose-widget `rsx!`.

### Designed, not yet on develop

These are designed and agreed, but none of them is in the crate or the renderer on `develop`.
Where work exists, it sits in an open pull request or a branch.

| Item | Status | Issue | Work in progress |
|---|---|---|---|
| Direct Kotlin to Rust calls on the web, replacing the JavaScript forwarder | partial | [#54](https://github.com/DarkPyonix/compose-rust/issues/54) | [#84](https://github.com/DarkPyonix/compose-rust/pull/84) |
| 3D transforms | planned | [#105](https://github.com/DarkPyonix/compose-rust/issues/105) | none |
| HTML text properties and the measure call | partial | [#106](https://github.com/DarkPyonix/compose-rust/issues/106) | branch `feat/measure-call` |
| OS text size and app zoom | partial | [#68](https://github.com/DarkPyonix/compose-rust/issues/68) | [#77](https://github.com/DarkPyonix/compose-rust/pull/77) (OS text size on the desktop only) |
| Action keys (dense keypad buttons) | partial | [#57](https://github.com/DarkPyonix/compose-rust/issues/57) | [#76](https://github.com/DarkPyonix/compose-rust/pull/76) |
| The bar's title in the platform caption | planned | [#79](https://github.com/DarkPyonix/compose-rust/issues/79) | none |
| Design systems split into per-system libraries and an adaptive layer | partial | [#39](https://github.com/DarkPyonix/compose-rust/issues/39) | branch `chore/thisisthepy-coordinates` in the Compose forks |
| The Windows Kotlin/Native renderer as the downloaded default | partial: builds from a checkout | [#24](https://github.com/DarkPyonix/compose-rust/issues/24) | [#174](https://github.com/DarkPyonix/compose-rust/pull/174) |
| An event when the system colour scheme changes | proposed, awaiting approval | [#97](https://github.com/DarkPyonix/compose-rust/issues/97) | none |
| Focus-gained and key-up events per node | proposed, awaiting approval | [#98](https://github.com/DarkPyonix/compose-rust/issues/98) | none |

---

## 🖥 Platforms

| Platform | State | Renderer |
|---|---|---|
| 🍎 **macOS (arm64)** | **Works end to end** | The Kotlin/Native renderer linked into the executable, drawing through Metal into the fork's AppKit window. 0.0.2 ships it as a static archive the build links into the executable, so the app is one executable. Basic Korean IME input works; the full IME checklist is not finished |
| 🐧 Linux (x64, arm64) | Builds and starts | The Kotlin/Native renderer, drawing through GLX into the fork's X11 window; arm64 is cross compiled on x64. 0.0.2 ships it as a static archive the build links into the executable. In CI an application built from it is one executable naming only libc, X11, GL, fontconfig and the C++ runtime (and EGL on arm64), and copied alone into an empty directory it draws under Xvfb. Nobody has used it interactively yet |
| 🪟 Windows | Builds and starts | Downloaded: a GraalVM native image, smoke-tested on every renderer change. From a checkout: the Kotlin/Native renderer, linked by MSVC into one executable and checked in CI. It becomes the download once its static archive is published ([#24](https://github.com/DarkPyonix/compose-rust/issues/24)) |
| 📱 iOS | Builds and starts | Kotlin/Native static archive exporting the same C symbols, released as an XCFramework |
| 🤖 Android | **Works end to end** | A Kotlin Activity owns the process and the loop, Rust is a cdylib, and the JNI shims on both sides are generated from the schema. The crate carries the renderer's Kotlin sources and its build script stages them into the Gradle project |
| 🌐 Web (wasm) | **Works end to end** | One `WebAssembly.Memory`, owned by the Kotlin/Wasm module and imported by the Rust one, so a batch is read where it was written. Calls from the renderer to the Host cross a generated JavaScript forwarder, measured at about 12 ns. Direct calls without the forwarder are planned ([#54](https://github.com/DarkPyonix/compose-rust/issues/54)) |

### What it weighs

The notepad sample, built for release and stripped, with the Kotlin/Native renderer linked in
(the renderer 0.0.2 ships on macOS and Linux). One executable: no runtime beside it, no
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
│  composables: slot table, recomposer (Runtime)              │
│  compose-rust: schema, fixed-layout records, one batch      │
└──────────────────────────────┬──────────────────────────────┘
                               │
              synchronous, same-thread direct calls
              primitives, pointers and lengths only
                               │
┌──────────────────────────────┴──────────────────────────────┐
│  generated shims (C exports, JNI, wasm)                     │
│  protocol decoder ──► node table                            │
│  schema interpreter ──► Compose ──► the fork's window       │
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

**The window is the Compose fork's.** The desktop window, input, IME, menus, chrome, resizing
and accessibility live in
[thisisthepy/compose-multiplatform-core-extended](https://github.com/thisisthepy/compose-multiplatform-core-extended)
under `extended/window`, at the commit this repository pins. The renderer keeps only its glue to
the window, so a window fix is made in the fork and the pin moves here.

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

```bash
cargo add compose-rust@0.0.2
```

`cargo build` works out which renderer the target needs, downloads the release artifact for the
crate's exact version, checks it against the published `.sha256`, unpacks it into a cache
outside `target/`, and links it. There is no environment variable to set and no script to run.
The cache is keyed by version and target, so it survives `cargo clean` and is shared between
projects.

Where the artifact is the static archive (macOS and Linux from 0.0.2), what `cargo
build` produces is **one executable**: the renderer, Compose, Skia and ICU are inside it, nothing
sits beside it, and it loads only the system's libraries (frameworks and `/usr/lib` on macOS;
libc, X11, GL, fontconfig and FreeType on Linux). Copy it anywhere and it runs. Windows downloads
the GraalVM native image, a DLL with its companions beside it, until its static renderer is
published ([#24](https://github.com/DarkPyonix/compose-rust/issues/24)); built from a checkout,
the Windows Kotlin/Native renderer is already one executable.

| Variable | Effect |
|---|---|
| `COMPOSE_RUST_RENDERER_DIR` | Use the renderer in this directory: an unpacked artifact, or the directory a renderer build wrote. It may hold the static archive or a shared library, and whichever it holds is linked. Checked first, and nothing is downloaded when it is set, so a renderer you built yourself, a vendored copy or an offline build all work through it |
| `COMPOSE_RUST_CACHE_DIR` | Move the cache off `$HOME/.cache/compose-rust` (`%LOCALAPPDATA%\compose-rust` on Windows) |

`default-features = false` builds with no renderer at all, for a headless or documentation
build. A binary built that way says what is missing and exits non-zero rather than opening no
window.

### Using the authoring API from `develop`

0.0.2 carries the authoring API and a renderer built from the same schema, so a project that
depends on it writes screens with no further setup. A renderer is published only with a
release, and the build refuses a renderer whose schema differs. So to use what is on `develop`
after 0.0.2, build the renderer in a checkout (below), then run a sample. In a checkout the
build links that renderer before anything it could download.

```bash
cargo run -p sample-minimal
```

A project outside the checkout depends on `compose-rust` by path and names the renderer with
`COMPOSE_RUST_RENDERER_DIR` (an absolute path, such as `$HOME/src/compose-rust/renderer/build/macos`).

### Samples

Eleven applications under [`samples/`](https://github.com/DarkPyonix/compose-rust/tree/main/samples),
each a clone of a real one, written on the authoring API:

| Sample | What it exercises |
|---|---|
| [`calculator`](https://github.com/DarkPyonix/compose-rust/tree/main/samples/calculator) | Dense button grids, a memory register, a tape beside the keys or in a sheet |
| [`notepad`](https://github.com/DarkPyonix/compose-rust/tree/main/samples/notepad) | Multiline editing, IME, file I/O from a worker thread |
| [`todo`](https://github.com/DarkPyonix/compose-rust/tree/main/samples/todo) | Stable keys, filters as destinations, a `LazyColumn` over thousands of rows |
| [`chat`](https://github.com/DarkPyonix/compose-rust/tree/main/samples/chat) | Enter sends and Shift+Enter starts a line, a reply streamed from a worker thread |
| [`minimal`](https://github.com/DarkPyonix/compose-rust/tree/main/samples/minimal) | Every control the schema has, the fills, the type and corner ladders, a `Canvas` |
| [`store`](https://github.com/DarkPyonix/compose-rust/tree/main/samples/store) | A carousel, a category strip, a two-up grid, a bag that adds up |
| [`statistics`](https://github.com/DarkPyonix/compose-rust/tree/main/samples/statistics) | A dial and a week of costs drawn with `Canvas`, painted in roles |
| [`selfcare`](https://github.com/DarkPyonix/compose-rust/tree/main/samples/selfcare) | A mood picker, drawn faces, a chip grid, a windowed row of cards |
| [`podcast`](https://github.com/DarkPyonix/compose-rust/tree/main/samples/podcast) | A waveform drawn with `Canvas`, a full-screen page over the navigation bar |
| [`academic`](https://github.com/DarkPyonix/compose-rust/tree/main/samples/academic) | A grid of subject tiles, open and locked lessons |
| [`social`](https://github.com/DarkPyonix/compose-rust/tree/main/samples/social) | Illustrated cards, three shelves, a course page over the navigation bar |

The first four are adaptive: they take the platform's design system. The other seven are
unified on Cupertino. The [guide's sample table](https://darkpyonix.dev/compose-rust/en/getting-started.html#samples)
links each one to the section it illustrates.

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
menu, the copy keys) and owns the desktop window code. Publish the fork into `~/.m2` first, then
build the renderer. The renderer scripts check for the fork's artifacts before the Kotlin
toolchain runs and name the command that publishes whatever is missing:

```bash
# macOS
./renderer/scripts/build-compose.sh --target macosArm64
./renderer/desktop/scripts/build-macos.sh --release                # build/macos/

# Linux (both architectures' Compose; arm64 is cross compiled on x64)
./renderer/scripts/build-compose.sh --target linux
./renderer/desktop/scripts/build-linux.sh --release                # build/linux/
./renderer/desktop/scripts/build-linux.sh --release --arch arm64   # build/linux-arm64/
```

Each renderer script also publishes the fork's window modules at the pinned commit, and produces one static library, `libcompose_rust_renderer.a`, with Compose, Skia and the
interpreter inside it, the same archive the release ships, with `schema-hash.txt` beside it. In a
checkout the build links that workspace build before anything it could download, then a native
image in `renderer/build/native-image/dist/lib`; `DXC_MACOS_NATIVE_LIB` and
`DXC_LINUX_NATIVE_LIB` still name a static renderer directory directly, ahead of everything.
On Windows, `renderer/desktop/scripts/build-windows.sh` builds the Kotlin/Native renderer, and
`DXC_WINDOWS_NATIVE_LIB` pointing at its output (`renderer/build/windows`) makes the build link it:
one executable, linked by MSVC, with nothing beside it. Without that variable Windows uses the
GraalVM native image, built with `renderer/desktop/scripts/build-native-windows.ps1`, which runs
the bash parts under Git Bash. The Kotlin/Native archive becomes the download once it is
published ([#24](https://github.com/DarkPyonix/compose-rust/issues/24)).
When a build fails, the [troubleshooting page](https://darkpyonix.dev/compose-rust/en/troubleshooting.html)
covers the fork's artifacts, stale resolution state and Git Bash's `PATH` on Windows.

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
samples/          11 sample applications on the authoring API, and the frames library their tests record through
docs/             the user guide (docs/guide) and translations (docs/locales)
scripts/          setup check, quality gate, release and publishing scripts, script tests
.github/          CI workflows
```

The adapter for dioxus-compose and its baseline moved to [dioxus-compose](https://github.com/DarkPyonix/dioxus-compose).
The samples under `samples/` are written on the compose-rust authoring API and build with the
workspace. The design systems moved to the Compose fork, under `extended/design-systems/`, and the
desktop window code under `extended/window/`, both in
[thisisthepy/compose-multiplatform-core-extended](https://github.com/thisisthepy/compose-multiplatform-core-extended).

---

## 📚 Documentation

- **Guide:** [darkpyonix.dev/compose-rust](https://darkpyonix.dev/compose-rust/), in English and
  Korean.
- **Repository:** [github.com/DarkPyonix/compose-rust](https://github.com/DarkPyonix/compose-rust)

---

## 📄 License

[Apache License 2.0](LICENSE).
