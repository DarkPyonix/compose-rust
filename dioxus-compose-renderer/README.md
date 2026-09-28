# dioxus-compose-renderer

The Kotlin half of dioxus-compose: the Compose Multiplatform renderer that interprets the
protocol the Rust Host sends and draws it with real Compose widgets.

Built with the [Kotlin Toolchain](https://kotlin-toolchain.org/dev/). The `kotlin` (macOS,
Linux) and `kotlin.bat` (Windows) wrappers in this directory bootstrap the pinned toolchain
on first use, so nothing has to be installed separately.

## Modules

| Module | What it is |
|---|---|
| `native` | **The renderer.** The schema interpreter, the `HostConnection` implementations, the C entry points, and the macOS native-image build (`desktop/scripts/`, `desktop/c/`). This is the module that matters. |
| `shared` | Compose code shared across platforms, including the IME test screen used to verify text input in a native build. |
| `desktop` | JVM development shell for working on Compose code with hot reload and `@Preview`. |
| `ios` | **The renderer for iOS.** The same interpreter sources (`ios/src/shared/` symlinks `desktop/src/`) compiled by Kotlin/Native, plus the iOS half of the boundary: `IosHostConnection`, the UIKit entry, and the `java.nio` shim the generated codec needs. |
| `staticlib` | The two `@CName` functions that become the C symbols of the iOS static library. Separate so that `-produce static` generates a C header for them and not for the whole of Compose. |
| `macos` | **The renderer for macOS without a virtual machine in it.** The same interpreter sources (`macos/src/shared/` symlinks `desktop/src/`) compiled by Kotlin/Native, plus `MacosWindow` and the Metal layer it draws into. Takes patched Compose from the local Maven repository; see `patches/`. |
| `staticlib-macos` | The macOS twin of `staticlib`, sharing the same two entry points. |
| `linux` | **The renderer for Linux without a virtual machine in it.** The same interpreter sources (`linux/src/shared/` symlinks `desktop/src/`), plus `LinuxWindow`: Xlib, GLX and the sync extension reached through `cinterop/x11.def`. Needs patched Compose for linuxX64 in the local Maven repository (`scripts/build-compose.sh --target linuxX64`) and the X11, Xext and GL development headers installed. |
| `staticlib-linux` | The Linux twin of `staticlib`, sharing the same two entry points. |
| `android` | **The renderer for Android.** The same interpreter sources (`android/src/shared/` symlinks `desktop/src/`), plus the Android half of the boundary under `android/src/bridge/`: the generated JNI declarations, a `HostConnection` that reads the Host's arena through a direct `ByteBuffer`, and Android's own picture decoding. |
| `web` | **The renderer for the browser.** The same interpreter sources (`web/src/shared/` symlinks `desktop/src/`), plus the web half of the boundary under `web/src/bridge/`: the generated forwarders and instantiation, a `HostConnection` that reads the Host's arena in place through a `java.nio` shim over wasm linear memory, and the browser's own `Intl` tables for the pickers. `web/scripts/build-host.sh` builds the Rust Host beside the page, `serve.sh` serves them together, and `test-boundary.sh` runs the boundary tests against a real Host. |

The generated protocol bindings live in `desktop/src/protocol/Protocol.gen.kt`. They are
produced from the Rust schema by `cargo run -p dioxus-compose --bin codegen`, edit the Rust
schema, never that file.

## Running

```bash
./kotlin run -m desktop     # the renderer's development harness on the JVM
./kotlin run -m desktop    # the Compose development shell
```

## Testing

```bash
./kotlin test              # everything
./kotlin test -m desktop    # the interpreter's tests
```

## Native image

The desktop renderer ships as a native shared library, built with Liberica NIK (upstream
GraalVM skips AWT on macOS). From this directory:

```bash
./desktop/scripts/build-native.sh   # build the shared library and stage lib/
./desktop/scripts/smoke-test.sh     # link a C host against it and open a window
```

## iOS

iOS ships as a Kotlin/Native static library that exports the same two C symbols the desktop
library does, so the Host does not know which platform it is linked against (SPEC PR-2, M5).
There is no GraalVM and therefore no isolate, so there is no C shim either: `@CName` puts the
symbol on the Kotlin function. `dioxus_compose_renderer_run` must be called on the process
main thread, where `UIApplicationMain` installs the run loop, and it never returns.

```bash
./desktop/scripts/build-ios.sh                    # arm64 simulator static library
./desktop/scripts/build-ios.sh --target device    # arm64 iPhone
./desktop/scripts/ios-smoke-test.sh --screenshot /tmp/ios.png   # run it on the simulator
```

The smoke test links `desktop/c/smoke_host.c`, the same stand-in Host the desktop smoke test
uses, so a passing run on both platforms is evidence that the C ABI really is one ABI.

## Android

On Android the host relationship is inverted: a Kotlin Activity owns the process and the
frame loop, and the Rust Host is a cdylib it loads. The two sides meet at JNI, and both
halves of that boundary are generated from the Rust schema, so the symbol names and the
argument order cannot drift apart.

This module is a library and has no application in it. An Android application is built by
the Dioxus CLI, which generates the Gradle project; the `dioxus-compose` crate's build
script unpacks this Kotlin into it and generates the Activity that hosts it, with that
application's own package and library name.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
    dx build --package sample-minimal --platform android --release
./kotlin build -m android                   # this module on its own, a compile check
```

The JDK matters: the Android Gradle plugin runs `jlink` and that fails above Java 21.

Building an application any other way is how this repository once shipped sample APKs by a
route no user takes, so there is deliberately no second way to do it here.

See the root [README](../README.md) for prerequisites and the
[guide](http://darkpyonix.dev/dioxus-compose/) for everything else.
