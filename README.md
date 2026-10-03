# compose-rust

[![CI](https://github.com/DarkPyonix/compose-rust/actions/workflows/ci.yml/badge.svg)](https://github.com/DarkPyonix/compose-rust/actions/workflows/ci.yml)
[![Native renderer](https://github.com/DarkPyonix/compose-rust/actions/workflows/native-renderer.yml/badge.svg)](https://github.com/DarkPyonix/compose-rust/actions/workflows/native-renderer.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Rust 1.85+](https://img.shields.io/badge/rust-1.85%2B-orange.svg)](https://www.rust-lang.org)

**English** · [한국어](docs/locales/README_ko.md)

**Declarative UI in Rust, rendered by an AOT-compiled Compose Multiplatform engine.**

*Native desktop UI in Rust with no webview and no bundled JVM.*

```rust
rsx! {
    Column {
        fill_max_width: true,
        Text { text: "compose-rust chat" }
        Button { text: "Send", on_click: move |_| send() }
    }
}
```

You author components with `rsx!`, hooks and signals. `dioxus-core`'s VirtualDom turns them into
mutations, a narrow C ABI carries those mutations across the boundary, and a Kotlin/Compose
interpreter materialises them as a real Compose tree, with Compose's text layout, its widgets, and
its platform IME.

---

## 📑 Contents

- [Why this exists](#-why-this-exists)
- [Status](#-status)
- [A taste of the API](#-a-taste-of-the-api)
- [Design systems](#-design-systems)
- [Architecture](#-architecture)
- [Getting started](#-getting-started)
- [Performance](#-performance)
- [Project layout](#-project-layout)
- [Contributing](#-contributing)
- [Documentation](#-documentation)
- [License](#-license)

---

## 🎯 Why this exists

Two problems meet here.

**Web-stack desktop apps are heavy.** For an application that stays open all day, the memory
footprint and the download size are the problem, not responsiveness. Embedding a browser, or
shipping a JVM alongside your app, costs tens to hundreds of megabytes before your own code runs.

**Rust has no toolkit with Compose-grade text.** The Rust GUI ecosystem renders well, but text
shaping, selection, accessibility and above all **IME** are not at the level Compose reached years
ago. For an app where typing *is* the interface, an IME that works 90% of the time is an app that
does not work. Korean, Japanese and Chinese composition is not a nice-to-have.

compose-rust takes Compose's renderer without taking Compose's runtime cost: the Kotlin side is
compiled ahead of time into a native library (Kotlin/Native on macOS, Linux and iOS, a GraalVM
native image where Kotlin has no target that can be linked against Skia), so there is no JVM in the
shipped artifact.

### Weight, roughly

The comparisons are **approximate**, taken from [`docs/INTENT.md`](docs/INTENT.md), and are
order-of-magnitude rather than benchmarks. What this project weighs is measured.

| Approach | Approximate weight | Notes |
|---|---|---|
| Webview stack (Electron, Tauri-class) | Heaviest: a browser engine per app or per system | Rejected by **C1**: memory and size |
| Compose + bundled JVM (jlink) | ~80–120 MB of JVM alone | Rejected by **C2**. AppCDS fixes startup, not size |
| Pure Rust toolkit (Iced-class) | ~10–20 MB | Rejected by **C5**: text and IME maturity |
| **compose-rust** | 28.76 MB on macOS, one file | Larger than Iced, far smaller than a webview or JVM stack |

### What it actually weighs

The notepad sample, built for release and stripped. One executable: no runtime beside it, no
virtual machine inside it, and nothing linked but the system's own libraries.

| Platform | Executable | Physical footprint | How it is built |
|---|---|---|---|
| macOS (arm64) | **28.76 MB** | **35.1 MB** | Kotlin/Native, drawing through Metal |
| Linux (x86-64) | **37.93 MB** | not yet measured | Kotlin/Native, drawing through GLX |
| Windows | not yet measured | not yet measured | GraalVM native image, drawing through Direct3D 12 |

For scale, the same sample on macOS before the Kotlin/Native path was four files weighing
93.1 MB with a 56 MB footprint. Most of that was the Java runtime the native image carried.

`NFR-3` asks for under 100 MB distributed and under 56 MB of physical footprint for an empty
window. macOS meets both with room, measured on an application rather than an empty window.
Linux and Windows are not measured for footprint yet, and nothing here is claimed for them.

### Non-negotiables

| ID | Constraint |
|---|---|
| **C1** | No webview: no WKWebView, WebView2, WebKitGTK, Tauri or wry |
| **C2** | No bundled JVM: the JVM is allowed only in the development shell |
| **C3** | No hand-written JNI or cinterop glue; boundary shims are generated |
| **C4** | UI is authored declaratively **in Rust**. Kotlin is a renderer implementation detail |
| **C5** | Compose-grade text, IME and widget quality, never bypassed |

---

## 🚦 Status

A **young project under active development**, built spec-first. It is not published to crates.io and
the API will change.

### Platforms

| Platform | State | Detail |
|---|---|---|
| 🍎 **macOS (arm64)** | **Works end to end** | Rust host → C ABI → Kotlin/Native renderer → window on screen, one file with no Java runtime in it. The window, its Metal layer and its text input are this renderer's own. Basic Korean IME input works; the full IME checklist (`SPEC §6`) is not finished |
| 🪟 Windows desktop | Builds and starts | Built with upstream GraalVM 25 and smoke-tested on every renderer change. |
| 🐧 Linux desktop | Builds and starts | Both architectures (x64 and arm64) build under Xvfb in CI and pass a headless startup smoke test. |
| 📱 iOS | Builds and starts | Kotlin/Native `-produce static` exporting the same C symbols. Released as an XCFramework. |
| 🤖 Android | **Works end to end** | A Kotlin Activity owns the process and the loop, Rust is a cdylib, and the JNI shims on both sides are generated from the schema. `dx build --platform android` is the whole build: the crate carries the renderer's Kotlin, and its build script unpacks it into the generated Gradle project and writes the Activity that hosts it, so an application's `Dioxus.toml` says nothing about the renderer. Sample APKs are built that way and run on an emulator (`PR-5`, milestone M6) |
| 🌐 Web (wasm) | **Works end to end** | One `WebAssembly.Memory`, defined by the Kotlin/Wasm module and imported by the Rust one, so a batch is read where it was written and nothing is copied. Renderer to Host calls cross a generated JavaScript forwarder, measured at 12 ns, because a browser will not give you a shared memory and a direct binding at the same time; the other direction has no JavaScript on it. The M0 screen comes up in a browser from the same Rust source the desktop demo runs, a click reaches the Rust handler, and the state change appears on the page. Tested and photographed in a browser by `web/scripts/test-boundary.sh` and `screenshot.sh` (`PR-6`, milestone M7) |

### The two halves

The Rust **Host** is well ahead of the Kotlin **Renderer**. The Compose interpreter is the piece
being finished right now, so several requirements pass on the Host side while the Renderer half is
still landing.

| Capability | Host (Rust) | Renderer (Kotlin) |
|---|---|---|
| Node tree mutations: `FR-1` | ✅ | ✅ |
| Schema-driven rendering: `FR-2` | ✅ | ✅ 33 composables, all implemented |
| Synchronous event dispatch: `FR-3`, `FR-12` | ✅ | ✅ `on_click`, `on_change`, `on_dismiss`, key consumption |
| Uncontrolled `TextField`, IME ownership: `D5` | ✅ | ✅ |
| Schema codegen in lockstep: `FR-7` | ✅ | ✅ generated `Protocol.gen.kt` |
| `LazyColumn` windowing: `FR-8` | ✅ | ✅ renderer asks for a range |
| Streaming text `AppendText`: `FR-9` | ✅ | ✅ coalesced per frame |
| Modifiers: `FR-10` | ✅ | ✅ 13 attributes on every widget |
| Design primitives and design systems: `FR-13`, `FR-14` | ✅ | ✅ Seven systems, role-based contract |
| Window size classes | ✅ | ✅ `use_window_size()` reports classes |
| Navigation and sheets | ✅ | ✅ Rail, drawer, bottom bar, sheets |
| Third-party widget extension: `FR-11` | ⚠️ Compile time only | ⚠️ `LinearProgressIndicator` as worked example |

Milestones live in [`PROJECT.md`](PROJECT.md) (M0–M8). **M1 decides the project**: if Korean IME
composition holds up in a native-image build, the rest is volume of work.

---

## ✨ A taste of the API

The snippet below is the real
[`dioxus-compose/examples/desktop_demo.rs`](dioxus-compose/examples/desktop_demo.rs), trimmed for
length. It compiles in this repository.

```rust
use compose_rust::prelude::*;

fn app() -> Element {
    let mut messages = use_signal(Vec::<String>::new);
    let mut draft = use_signal(String::new);

    rsx! {
        Column {
            fill_max_width: true,
            Text { text: "compose-rust chat" }
            for message in messages() {
                Text { text: message }
            }
            TextField {
                placeholder: "Write a message",
                multiline: true,
                on_value_change: move |value| draft.set(value),
                on_key_down: move |event: KeyEvent| {
                    if event.key() == Key::Enter && !event.shift_key() {
                        let message = draft().trim().to_owned();
                        if !message.is_empty() {
                            messages.write().push(message);
                            draft.set(String::new());
                        }
                        event.consume();   // the Renderer's onKeyEvent returns true
                    }
                }
            }
            Button {
                text: "Send",
                on_click: move |_| { /* ... */ }
            }
        }
    }
}

fn main() {
    compose_rust::launch(app);
}
```

Two details worth noticing:

- **`event.consume()`** is how a handler tells the Renderer it handled the key, the same idea as
  `preventDefault()` on the web, or `PointerInputChange.consume()` in Compose. Dioxus 0.7 handlers
  have no return value, so the flag rides back on the event object (`FR-12`).
- **Key events are not sent to Rust while an IME composition is in progress.** Enter during
  composition commits the composition; it does not submit. Getting this wrong is exactly how Korean
  input loses the syllable being typed.

> ⚠️ `compose_rust::Box` has to be written qualified inside `rsx!`, `dioxus-core` 0.7's macro
> expansion uses an unqualified `Box<T>`, which the prelude glob would shadow.

---

## 🎨 Design systems

The plan is seven first-class design systems, including **Material 3**, **Apple HIG**, **WinUI/Fluent**, and **Liquid Glass**, chosen per application, either unified across every platform or adapted to the host platform:

```rust
// The same design system everywhere
LaunchBuilder::new().with_theme(Theme::unified(DesignSystem::Material3)).launch(app);

// Follow the host platform. The fallback argument is mandatory
LaunchBuilder::new().with_theme(Theme::adaptive(DesignSystem::Material3)).launch(app);
```

The design is worked out in detail in `FR-13` and `FR-14` of [`docs/SPEC.md`](docs/SPEC.md):

- Widgets emit **roles**, never literals: `ColorRole`, `TypeRole`, `ShapeRole`, `SpaceRole`, and
  component variants such as `ButtonVariant::{Filled, Tonal, Outlined, Text}`.
- **The Renderer resolves roles into tokens**, not the Host. A dark-mode switch is then one
  `SetTheme` mutation plus a `CompositionLocal` invalidation, instead of an `O(nodes)` storm of
  `SetProp` calls charged against the frame budget.
- Adding an eighth design system must not touch widget code, properties, modifiers or the wire
  format: one Rust enum variant, one Kotlin token table, one rules implementation.
- `adaptive` is **not** the default. Without `with_theme` you get
  `Theme::unified(DesignSystem::Material3)`, because a default that looks different on every
  platform is a bad default.

> **Status: `Draft`, design only.** ⚠️ None of this exists in code yet. `Theme`, `DesignSystem`,
> `ColorRole`, `TypeRole` and `ScrollColumn` appear nowhere in `dioxus-compose/src/` or in the Kotlin
> renderer today. What ships now is the literal subset: `Modifier::Background(u32 ARGB)`,
> `Modifier::Padding(f32)` and friends. The snippet above is **illustrative of the specified API**,
> not of a working one.

---

## 🏗 Architecture

```
┌────────────────────── Host (Rust) ───────────────────────┐
│  your components, rsx!, hooks, signals                  │
│  dioxus-core VirtualDom                                  │
│  compose-rust renderer:    Mutations ──► fixed-layout    │
│                                          byte records    │
└───────────────────────────┬──────────────────────────────┘
                            │
             synchronous, same-thread direct calls
             (JSI-style) + one batch buffer per call
                            │
┌───────────────────────────┴──────────────────────────────┐
│  generated shims  (@CEntryPoint / @CName / JNI / wasm)   │
│  protocol decoder ──► node table (Compose snapshot state)│
│  schema interpreter  @Composable RenderNode              │
│  Compose Desktop (AWT) · Compose iOS (UIKit)             │
└────────────────────── Renderer (Kotlin) ─────────────────┘
```

**Rust describes the UI; Compose interprets it.** Rust never calls the Compose API directly, it
cannot. GraalVM's `@CEntryPoint` passes only primitives and word-sized values, so objects like
`Modifier` or `MutableState` can never cross. Instead the UI tree travels **as a value** and a
general-purpose interpreter on the Kotlin side rebuilds it. Cash App's Redwood and Jetpack Glance
use the same pattern.

**The boundary is synchronous and same-thread** (`PR-1`). The VirtualDom runs on the Renderer's UI
thread and the two sides call each other directly, the way JSI replaced React Native's old bridge.
There is no queue, no ring buffer and no thread hop, and an event handler can return a result within
the same call. Heavy work, I/O, network, PTY, runs on Host worker threads that update signals and
request a frame (`PR-3`); user code never touches a boundary function.

**Only primitives, pointers and lengths cross** (`PR-2`). Payloads are fixed-layout, zero-copy
records produced by codegen and read in place, no postcard, bincode or JSON on the hot path
(`PR-4`). Type safety is restored above that `bytes` boundary by generating the Kotlin types from a
single Rust source of truth, with a schema hash that fails the build when the two drift (`FR-7`,
`D6`).

**UI-local state stays in Kotlin** (`D5`). `TextField` is uncontrolled and composition text never
makes a round trip through Rust. Scroll position, focus and animation state belong to the Renderer
too.

<details>
<summary><b>Why the native image needs Liberica NIK, and three small shims</b></summary>

This is the GraalVM native image, which is how Windows is built and how macOS was built before the
Kotlin/Native path. The renderer macOS ships links no AWT and needs none of what follows.

Compose Desktop's window is an AWT `JFrame`, and AOT compilation does not change which code path
runs, so the AWT IME path survives native-image (`D4`). But upstream GraalVM **skips AWT entirely
on Darwin** ([oracle/graal#13272](https://github.com/oracle/graal/issues/13272)), which means no
static AWT archive and no way to link the renderer. Liberica NIK Full links AWT statically.

Statically linked macOS AWT then looks for three things by file path at runtime, each met by a thin
shim in `dioxus-compose-renderer/desktop/c/`:

| Missing thing | Shim |
|---|---|
| `libawt_lwawt.dylib`, which libawt loads by path | A placeholder dylib; the JNI functions resolve inside the executable image |
| `libjawt.dylib`, which Skiko `dlopen`s from `<java.home>/lib` | A forwarder into the image's own `JAWT_GetAWT` |
| `JNI_OnLoad_osxui`, required of a statically linked JNI library and absent from NIK's archive | Defined directly |

Also: **AppKit demands the main thread.** The renderer runs on a secondary thread while the main
thread creates and runs `NSApplication` itself, putting AWT into embedded mode, the same mode SWT
and JavaFX hosts use. Letting AWT own the loop re-enters `[NSApp run]` forever, and control never
returns to the Host after the window closes.

The JNI mentioned here is entirely internal to the JDK. The Host ↔ Renderer boundary is pure C ABI.
</details>

---

## 🚀 Getting started

### Using it in your own project

One line. `cargo build` works out which renderer this target needs, downloads the release
artifact for the crate's exact version, checks it against the published `.sha256`, unpacks it
into a cache outside `target/`, and links it.

On macOS (arm64) and Linux (x64 and arm64) that artifact is the renderer as a static archive,
so what `cargo build` produces is **one executable**: the renderer, Compose, Skia and ICU are
inside it, nothing sits beside it, and it loads nothing but the system's own libraries
(frameworks and `/usr/lib` on macOS; libc, X11, GL, fontconfig and FreeType on Linux). Copy it
anywhere and run it. Windows still downloads the GraalVM native image, a DLL with its
companions beside it, until the Windows static renderer lands.

```toml
[dependencies]
compose-rust = "0.0.1"
```

There is no environment variable to set, no artifact to fetch by hand and no script to run. The
cache is keyed by version and target, so it survives `cargo clean` and is shared between projects
on the machine.

Two variables exist for the cases that need them, and neither is part of installing:

| Variable | Effect |
|---|---|
| `DIOXUS_COMPOSE_RENDERER_DIR` | Use the renderer in this directory: an unpacked artifact, or the directory a renderer build wrote. It may hold the static archive (`libdioxus_compose_renderer.a`) or a shared library, and whichever it holds is linked. Checked first, and nothing is downloaded when it is set, so a renderer you built yourself, a vendored copy or an air-gapped build all work through it. |
| `DIOXUS_COMPOSE_CACHE_DIR` | Move the cache off `$HOME/.cache/dioxus-compose` (`%LOCALAPPDATA%\dioxus-compose` on Windows). |

A build with no network says which two files to put where, and putting them there is all it takes.
`default-features = false` builds with no renderer at all, for a headless or documentation build;
running a binary built that way prints what is missing and exits non-zero rather than opening no
window and returning 0.

Everything below this point is about working on **this repository**, which needs the renderer
toolchain as well.

### 0. Check your machine

```bash
./scripts/setup-check.sh          # --quiet for CI-style output
```

This verifies every tool the build needs and prints the exact fix for anything missing. Start here;
everything below assumes it passes.

### 1. Rust

Install through [rustup](https://rustup.rs). `scripts/check.sh` runs `cargo fmt` and `cargo clippy`,
so both components are required. The workspace targets Rust **1.85+** (edition 2024).

```bash
rustup component add rustfmt clippy
```

### 2. Liberica NIK 25 **Full**, only for the GraalVM native image

Not needed to build the renderer macOS ships. That one is Kotlin/Native, and what it needs is the
patched Compose in step 3.

> ⚠️ **Upstream GraalVM does not work on macOS.** It skips AWT support on Darwin
> ([oracle/graal#13272](https://github.com/oracle/graal/issues/13272), still open as of 2026-09), so
> Compose Desktop cannot be linked into the image. Use BellSoft **Liberica NIK 25 Full**, the
> *Full* variant, not the standard one.

```bash
brew install --cask liberica-nik-full
# or download "NIK 25 Full" from https://bell-sw.com/pages/downloads/native-image-kit/
# or, scripted and version-pinned (macOS only):
./scripts/install-nik.sh
```

`dioxus-compose-renderer/desktop/scripts/env.sh` discovers it in this order:

1. `$GRAALVM_HOME`, if set
2. the newest match of
   `~/Library/Java/JavaVirtualMachines/bellsoft-liberica-vm-full-openjdk25*/Contents/Home`

Development uses `bellsoft-liberica-vm-full-openjdk25-25.0.4.1`. The scripts **reject** any install
without `lib/static/darwin-*/libawt_lwawt.a`. That catches plain GraalVM up front, instead of after
a long build ends in a link failure.

macOS also needs the Xcode command line tools (`xcode-select --install`) for `cc`, `ld` and the
AppKit headers used by `dioxus-compose-renderer/desktop/c/`.

**The scripts support macOS only today.** Linux and Windows native-image builds are not scripted.

### 3. The patched Compose

The renderer resolves Compose from your local Maven repository, not from what JetBrains published.
Three things it needs are empty in the published build for this platform and cannot be filled from
outside the module that holds them: the entries a text selection's menu offers, the keys that copy,
and the Linux targets. The changes are commits in a fork of Compose,
[`thisisthepy/compose-multiplatform-core-extended`](https://github.com/thisisthepy/compose-multiplatform-core-extended),
and the build script pins one commit of it.

```bash
./dioxus-compose-renderer/scripts/build-compose.sh
```

It fetches the pinned commit and publishes the modules the renderer asks for. Slow, and run once rather than once per build. Everything else still resolves from what
JetBrains published.

### 4. Kotlin

Nothing to install. `dioxus-compose-renderer/kotlin` (`kotlin.bat` on Windows) is a
self-bootstrapping wrapper that downloads the pinned toolchain on first use.

### 5. Build the renderer

Produces one static library, with Compose, Skia and the interpreter inside it (`PR-8`). Takes
several minutes. This is the same archive the release ships.

```bash
cd dioxus-compose-renderer
./desktop/scripts/build-macos.sh --release                   # macOS: build/macos/
./desktop/scripts/build-linux.sh --release                   # Linux x64: build/linux/
./desktop/scripts/build-linux.sh --release --arch arm64      # Linux arm64, cross compiled on x64: build/linux-arm64/
```

```
build/macos/
  libdioxus_compose_renderer.a       the renderer (Compose, Skia, the interpreter, our code)
  libdioxus_compose_renderer_api.h   the header Kotlin/Native generates for it
  schema-hash.txt                    the schema it was generated from, checked by the Host's build
```

`scripts/package-static-renderer.sh` turns that directory into the release artifact,
`dioxus-compose-renderer-v<version>-<target>.tar.gz` and its `.sha256`, and
`scripts/check-single-executable.sh` builds an application from such an artifact and proves it
is one executable: it reads `otool -L` or `readelf -d`, then copies the executable alone into an
empty directory and requires it to draw.

<details>
<summary><b>The GraalVM native image, and what lands in <code>dist/lib/</code></b></summary>

`./desktop/scripts/build-native.sh` builds the native image instead. That is how Windows is built
and what `smoke-test.sh` links against, and it is no longer how macOS ships. Its output is a
directory rather than one file, because statically linked AWT resolves some things by path:

```
build/native-image/dist/lib/
  libdioxus_compose_renderer.dylib   the renderer (AWT, Skiko JNI, Compose, our code)
  libskiko-macos-<arch>.dylib        Skia, loaded by Skiko by path
  libjawt.dylib                      forwards JAWT_GetAWT into the renderer
  libawt_lwawt.dylib                 placeholder that libawt loads by path
```
</details>

### 6. Smoke-test it

Links a minimal C host against the library and calls `dioxus_compose_renderer_run`. A window should
open, and closing it should return 0, the `PR-8` acceptance criterion.

```bash
cd dioxus-compose-renderer
./desktop/scripts/smoke-test.sh
```

For an unattended run, set `DIOXUS_COMPOSE_AUTOEXIT_MS=6000` to make the window close itself.

### 7. Run the Rust demo

```bash
cargo run -p compose-rust --example desktop_demo --features native-renderer
```

In a checkout of this repository the build script prefers the renderer you just built over
anything it could download: the static archive in `dioxus-compose-renderer/build/macos` (or
`build/linux`, `build/linux-arm64`) first, then a native image in
`dioxus-compose-renderer/build/native-image/dist/lib`. The full order is
`DIOXUS_COMPOSE_RENDERER_DIR`, then that workspace build, then the cache, then the release for the
crate's version (`NFR-10`). `DXC_MACOS_NATIVE_LIB` and `DXC_LINUX_NATIVE_LIB` still name a static
renderer directory directly, ahead of all of them.

### 8. The JVM dev shell

The fastest loop when you are working on the renderer itself: hot reload and `@Preview` work, and no
native-image build is needed (`NFR-5`, `D7`).

```bash
cd dioxus-compose-renderer
./kotlin run -m desktop   # the Compose development shell
./kotlin run -m desktop    # the renderer module itself on the JVM, driven by a scripted Host
```

> The JVM is permitted **only here**. Shipped artifacts never contain one (`C2`).

---

## 📊 Performance

The goal (`NFR-9`) is to be **indistinguishable from the same screen written by hand in
Kotlin/Compose**. The reference is a 120 Hz display: 8.33 ms per frame.

### Budgets, `SPEC §5.1`

| Item | Budget (p99, release build) |
|---|---|
| Overhead versus a pure-Compose baseline | ≤ 10% frame time |
| Host work (handler + diff + batch encode) | ≤ 0.5 ms normal interaction, ≤ 1 ms streaming frame |
| One boundary call | ≤ 100 ns desktop/iOS, ≤ 200 ns Android with `@FastNative` |
| Batch apply (decode + snapshot apply) | ≤ 0.3 ms per 100 mutations |
| Input → pixels | Same frame count as the baseline; zero extra frames of latency |
| Steady-state allocation | 0 in boundary encoding (arena reuse); ≤ 200 per frame across the Host path, and not growing |
| Dropped frames | 0 while streaming at 100 appends/s and scrolling a 10,000-item list |

Budget regressions are treated as bugs, and CI fails the build on them.

### Measured, 2026-09-20

Recorded in [`dioxus-compose/benches/baseline.json`](dioxus-compose/benches/baseline.json) and
re-run by `cargo bench` inside `scripts/check.sh`.

> **Machine:** Mac mini (Macmini9,1) · Apple M1, 8 cores (4 performance + 4 efficiency) · 16 GiB ·
> macOS 26.5.1 (25F80) · `aarch64-apple-darwin` · rustc 1.98.1 · release profile.

| Measurement | Result | Budget |
|---|---|---|
| Click → dispatch → diff → encode | **13.3 µs** p99 | ≤ 500 µs |
| Encode 100 mutations | **2.9 µs** p99, **0** steady-state allocations | ≤ 0.3 ms |
| Streaming: 100 appends into a 10,000-message conversation | **220 µs** p99 | ≤ 1 ms |
| Allocations per interaction, whole Host path | **99** | ≤ 200, and must not grow |

Those 99 allocations are Dioxus's own, inside diffing and event handling. Driving them to zero would
mean forking Dioxus, which contradicts `D2`; Rust has no GC, so they do not turn into frame pauses.
The criterion is that the number **does not grow** across repeated identical interactions, growth is
treated as a leak or a dead cache and investigated.

These are **Host-side numbers**. Renderer-side frame timing, and the 10%-versus-baseline comparison,
still have to be measured on the native-image build.

---

## 🗂 Project layout

```
compose-rust/
├─ dioxus-compose/                  # Rust: the Host, Dioxus renderer crate
│  ├─ src/
│  │  ├─ lib.rs                     #   public API, rsx! elements, event attributes
│  │  ├─ widgets.rs                 #   Column, Row, Box, Text, TextField, Button, Spacer, LazyColumn
│  │  ├─ schema.rs                  #   the single source of truth for the wire schema
│  │  ├─ protocol.rs                #   fixed-layout encoding (PR-4)
│  │  ├─ boundary.rs                #   C ABI surface, launch / LaunchBuilder
│  │  └─ codegen.rs                 #   Rust schema → Kotlin types
│  ├─ examples/desktop_demo.rs      #   the runnable demo
│  ├─ benches/baseline.json         #   the recorded performance baseline
│  └─ tests/vectors/                #   protocol vectors both sides assert against
├─ dioxus-compose-renderer/         # Kotlin: the Renderer (Kotlin Toolchain / Amper)
│  ├─ native/                       #   the interpreter, C shims, native-image build scripts
│  ├─ desktop/                      #   JVM development shell
│  ├─ shared/                       #   shared Compose code
│  └─ ios/  android/  web/          #   platform targets
├─ samples/                         # eleven sample apps (four adaptive, seven unified)
├─ scripts/                         # setup-check.sh, check.sh, install-nik.sh, publish-main.sh
└─ docs/
   ├─ INTENT.md                     # why, decisions D1–D10, rejected alternatives
   ├─ SPEC.md                       # FR-*, NFR-*, PR-* with acceptance criteria
   ├─ guide/                        # the user guide site (hand-written HTML, en + ko)
   └─ locales/README_ko.md          # this README, in Korean
```

---

## 🤝 Contributing

### Spec Driven Development

**The SPEC is the source of truth.** Before implementing a behaviour, find its SPEC ID (`FR-*`,
`NFR-*`, `PR-*`). If none exists, amend the SPEC first, in its own commit. If code and SPEC disagree,
the code is wrong, unless the SPEC is, in which case fix the SPEC first and explain why. Decisions
change in [`docs/INTENT.md`](docs/INTENT.md) first, then SPEC, then code.

### Test Driven Development

SDD says what to build; TDD is how it gets built. Tests come from acceptance criteria, so **a
requirement with no test is not done**.

- Red, green, refactor. Test and implementation land in the **same commit**, the tree builds green
  at every commit.
- Name tests after the requirement: `fr4_set_prop_does_not_recompose_siblings`,
  `pr2_batch_applies_atomically`.
- A bug fix starts with a test that reproduces the bug.
- Test through the public surface: the crate API and the C exports; the interpreter and
  `HostConnection`.
- Use the checked-in protocol vectors and `FakeHostConnection`, not mocks of our own protocol.
- Performance is a test too: §5.1 is a benchmark suite, and the allocation ceiling is an assertion.

IME (`§6`) and accessibility (`§7`) are manual checklists run on the **native-image build**, and the
SPEC says so explicitly rather than leaving them silently untested.

### The quality gate

```bash
./scripts/check.sh              # fmt, clippy, tests, quick benchmarks, Kotlin build and tests
./scripts/check.sh --full       # the same, with the full benchmark sample
./scripts/check.sh --no-kotlin  # Rust only (DXC_SKIP_KOTLIN=1 does the same)
```

CI mirrors that split. [`ci.yml`](.github/workflows/ci.yml) runs the Rust gate on macOS and Linux for
every push and pull request, while [`native-renderer.yml`](.github/workflows/native-renderer.yml)
builds the shipped renderers on pushes to `main`, nightly, and on demand: the Kotlin/Native static
archives for macOS and Linux (x64 and arm64), the Windows native image and the iOS XCFramework.
Its `single-executable` job builds an application from each static archive exactly as the release
carries it and checks that the result is one executable. Those builds compile Compose and the
renderer ahead of time and take tens of minutes, which is too slow to put in front of every push
(`NFR-5`, `D7`).

### Commits

One logical change per commit; do not mix SPEC edits, refactors and features. Subject format:

```
<Type>: <imperative summary>
```

`Feat` · `Fix` · `Refactor` · `Docs` · `Test` · `Chore`. Reference SPEC IDs where relevant, for
example `Feat: Return handler result from dispatch_event (PR-2)`. Do **not** add `Co-Authored-By`
trailers or any AI attribution.

---

## 📚 Documentation

**📖 Guide: [docs/guide](https://github.com/DarkPyonix/compose-rust/tree/develop/docs/guide)**, English and Korean, covering getting
started, writing UI, lists and streaming, architecture and troubleshooting.

| Document | What is in it |
|---|---|
| [PROJECT.md](PROJECT.md) | Scope, method, milestones M0–M8, open questions |
| [docs/INTENT.md](docs/INTENT.md) | Motivation, non-negotiables, decisions D1–D10, rejected alternatives |
| [docs/SPEC.md](docs/SPEC.md) | Functional and non-functional requirements, boundary protocol, acceptance criteria |
| [AGENTS.md](AGENTS.md) | Working agreements for this repository |

The planning documents (`PROJECT.md`, `INTENT.md`, `SPEC.md`) are written in Korean. This README and
the guide site are English, with Korean translations.

---

## 📄 License

[Apache License 2.0](LICENSE).
