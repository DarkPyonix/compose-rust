# Linux native renderer build evidence

Status: **untested on Linux as of 2026-09-20**. The files were written and statically
checked on macOS. No Linux native image was built or run, so this is a build hypothesis
with executable checks, not a support claim.

## Why upstream GraalVM is the first toolchain to try

Upstream Native Image implements AWT support on Linux. Its Linux path registers both
`awt_headless` and `awt_xawt`, copies reachable JDK shared libraries beside the image, and
creates `libjava.so` and `libjvm.so` shims. The macOS exclusion that required Liberica NIK
Full is not present on Linux. Relevant upstream sources:

- <https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/jdk/JNIRegistrationAWTSupport.java>
- <https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/jdk/JNIRegistrationSupport.java>
- <https://www.graalvm.org/latest/reference-manual/native-image/overview/BuildOutput/>

Therefore `build-native-linux.sh` starts with upstream GraalVM for JDK 25 and rejects a
build that does not emit `libawt.so`, `libawt_headless.so`, `libawt_xawt.so`,
`libfontmanager.so`, `libjava.so`, and `libjvm.so`. Liberica NIK is not expected to be
needed. That expectation is untested.

## How the AWT classes reach the JNI tables

Staging the AWT libraries and registering `awt_xawt` decides which native libraries load. It
puts nothing in the image's JNI tables. `libawt`'s `JNI_OnLoad` runs first and resolves
`java/awt/GraphicsEnvironment` with `FindClass`, to ask `isHeadless` which toolkit to load,
so a build with no registration for that class dies during `java.awt.Toolkit.loadLibraries`
with `NoClassDefFoundError` on a class the JDK plainly declares. That is the Linux twin of
the Windows `NoSuchMethodError: java.awt.Toolkit.getDefaultToolkit`, and it has the same
cause: a JNI lookup against a class the image never registered.

The build passes `-H:Preserve=module=java.desktop` rather than a hand-written list of X11
classes.

- Native Image's own non-headless `java.desktop` integration test, the only AWT image
  upstream runs, is built with exactly that flag and no metadata files:
  `java_desktop_integration_task` in
  <https://github.com/oracle/graal/blob/master/substratevm/mx.substratevm/mx_substratevm.py>.
  The test itself only runs on Linux, and skips elsewhere:
  <https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.test/src/com/oracle/svm/integrationtest/NonHeadlessJavaDesktopTest.java>.
- `-H:Preserve` was introduced in GraalVM for JDK 25 and is documented at
  <https://docs.oracle.com/en/graalvm/jdk/25/docs/reference-manual/native-image/overview/Options/>.
  Its own help text says that for what it selects "it is not necessary to provide
  reachability metadata", and warns about build time and image size, which is what the
  existing `-Os` is for. The Linux job prints the staged size after every build.
- Preserved classes are registered for JNI, not only for reflection:
  `PreserveOptionsSupport.registerPreservedClasses` calls `RuntimeJNIAccessSupport.register`
  for each preserved class and its declared members when JNI is enabled, and `java.desktop`
  is listed in `JDK_MODULES_TO_PRESERVE`:
  <https://github.com/oracle/graal/blob/release/graal-vm/25.0/substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/image/PreserveOptionsSupport.java>.

Nothing outside `java.desktop` is preserved, and nothing needs to be. The Compose, Skiko and
Skia registrations are shared by all three desktop platforms and already ship as classpath
metadata in `desktop/resources/META-INF/native-image/dioxus.compose/dioxus-compose-renderer`,
which `native-image` reads on every platform without a configuration-directory argument. The
Windows overlay in `scripts/windows-metadata` exists because the Windows toolkit classes are
Windows-only; pointing Linux at it would register nothing that exists there.

Linux needs no Skiko overlay of its own. On macOS `MetalRedrawer.onOcclusionStateChanged` and
on Windows `Direct3DRedrawer.isAdapterSupported` are called from native code and are
registered for JNI. `javap` on `skiko-awt-0.144.6` shows `LinuxOpenGLRedrawer` and
`LinuxSoftwareRedrawer` declaring no such callback: their only native boundary is
`private final native long createDevice(...)` and friends, which is Java calling out, not
native calling in. `tests/linux-metadata.test.sh` fails if a Linux redrawer registration ever
appears, because that would mean this paragraph is stale.

All of this is reasoning from upstream sources and from class files. None of it has been run
on Linux.

## Why Linux ships two libraries where macOS ships one

On macOS the C shim (`c/renderer_entry.c`) is handed to `native-image` as
`-H:NativeLinkerOption=<obj>` and exported with `-Wl,-exported_symbol`. That does not work on
Linux. When Native Image links a shared library it writes its own linker version script and
passes it as `-Wl,--version-script=<file>`. The script lists the image's own `@CEntryPoint`
symbols under `global:` and ends with `local: *;`, and `-Wl,-x` then strips the local
entries. Any symbol Native Image did not generate itself is therefore local and then gone,
however it entered the link. Neither `-Wl,--export-dynamic-symbol` nor `-Wl,-u` changes that:
the first only chooses among symbols a version script already left global, the second only
forces a definition to be pulled in, and a second `--version-script` of our own is refused by
GNU ld with "anonymous version tag cannot be combined with other version tags" because the
generated one is anonymous.

- Version script and `-Wl,-x`: <https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/image/CCLinkerInvocation.java>
- Symbol versioning and the anonymous-tag restriction: <https://maskray.me/blog/2020-11-26-all-about-symbol-versioning>

So `build-native-linux.sh` lets Native Image build `libdioxus_compose_renderer_image.so`
(with `-Wl,-soname` so the dependency stays relocatable) and then links
`libdioxus_compose_renderer.so` itself from `renderer_entry.o` against that image library.
The public library is produced by a plain `cc -shared` command, so the two exported names no
longer depend on how `native-image` forwards linker arguments. The C ABI the Host sees is
unchanged: the same two argument-free functions, in a library with the same file name.

## macOS workaround mapping

| macOS workaround | Expected Linux result | Verification status |
|---|---|---|
| Liberica NIK Full | Use upstream GraalVM for JDK 25 because Linux AWT is supported upstream | Untested |
| `libawt_lwawt.dylib` placeholder | None. Native Image should emit the real `libawt_xawt.so` | Untested, enforced by an artifact check |
| `libjawt.dylib` forwarder | None expected. Stage the toolchain's real `libjawt.so` if Native Image does not emit it | Untested fallback |
| `JNI_OnLoad_osxui` definition | None. `osxui` is Darwin-only | Supported by source inspection only |
| Force-load `libawt_lwawt.a` | None. Linux AWT libraries are dynamic output artifacts | Supported by source inspection only |
| `ImeReachabilityFeature` | Generic AWT IM classes are retained, but Linux XIM coverage still needs an ibus and fcitx agent run | Untested and incomplete until exercised |
| AppKit main-thread host loop | None. Call the renderer on the caller thread; AWT creates its own event dispatch thread | Untested |
| Skia staging | Extract `libskiko-linux-<arch>.so` from the platform runtime jar into `lib/` | Untested, enforced by an artifact check |
| Apple font APIs | Install system fontconfig, FreeType, and CJK fonts. Nothing is bundled except Skia | Untested, build host checked with `fc-match` |

The existing runtime code sets `java.home` to the parent of staged `lib/` and points
`skiko.library.path` and `skiko.data.path` at `lib/`. That layout matches Linux AWT's
`<java.home>/lib` lookup and Skiko's explicit path support. The `java.home` behavior is
known from the Windows reference, but this exact shared-library layout is untested on Linux.

## Exact Linux verification commands

Use Ubuntu 24.04 x86_64 first. AArch64 has a Skiko artifact and is scripted, but should be a
second target after x86_64 works.

```bash
sudo apt-get update
sudo apt-get install -y build-essential zlib1g-dev unzip pkg-config fontconfig \
  libx11-dev libxext-dev libxi-dev libxrender-dev libxtst-dev libxrandr-dev \
  libfontconfig1-dev libfreetype6-dev libgl1 libgl1-mesa-dri \
  fonts-noto-cjk xvfb xauth dbus-x11
export GRAALVM_HOME=/absolute/path/to/graalvm-jdk-25
cd /absolute/path/to/dioxus-compose-renderer
xvfb-run -a ./desktop/scripts/build-native-linux.sh
bash ./desktop/scripts/tests/linux-build.test.sh
find build/native-image-linux/dist/lib -maxdepth 1 -type f -print | sort
ldd build/native-image-linux/dist/lib/*.so
readelf -d build/native-image-linux/dist/lib/libdioxus_compose_renderer.so
DIOXUS_COMPOSE_AUTOEXIT_MS=5000 xvfb-run -a ./desktop/scripts/smoke-test-linux.sh
```

The last command can prove startup, rendering far enough to auto-close, shutdown, and return
status 0. It cannot prove real input, IME, GPU behavior, or Wayland behavior.

For a real X11 ibus session, run this from a desktop terminal and interact with the text
field before closing the window:

```bash
export LANG=ko_KR.UTF-8
export XMODIFIERS=@im=ibus
DIOXUS_COMPOSE_AUTOEXIT_MS=60000 ./desktop/scripts/collect-metadata-linux.sh
unset DIOXUS_COMPOSE_AUTOEXIT_MS
./desktop/scripts/smoke-test-linux.sh
```

Repeat in a separate configured fcitx session with:

```bash
export LANG=ko_KR.UTF-8
export XMODIFIERS=@im=fcitx
LINUX_METADATA_OUTPUT="$PWD/build/native-image-linux/fcitx-agent-metadata" \
  DIOXUS_COMPOSE_AUTOEXIT_MS=60000 ./desktop/scripts/collect-metadata-linux.sh
unset DIOXUS_COMPOSE_AUTOEXIT_MS
./desktop/scripts/smoke-test-linux.sh
```

For each IME, verify composition, candidate selection, Enter to confirm composition, Enter
to submit after composition, Shift+Enter, backspace across a composition boundary, focus
loss, mouse input, resize, and clean shutdown. Diff the ibus and fcitx agent output. Do not
merge either blindly because the agent only records the interactions that occurred.

The C smoke host adds a bare editable `TextField` only when compiled on Linux. That preserves
the macOS smoke tree while giving the native image a real XIM target. It has no submit or
change handler, so it proves composition and editing only. The full SPEC 6 event semantics
still need a Rust Host fixture on Linux and remain untested.

## X11 and Wayland expectation

This JDK 25 AWT path is X11. On an X11 session it talks to the X server directly. On a
Wayland desktop it is expected to run as an X11 client through XWayland, so both XWayland and
`DISPLAY` are required. `WAYLAND_DISPLAY` alone is not sufficient. IBus or Fcitx must expose
an XIM endpoint selected by `XMODIFIERS` even if its native Wayland frontend also runs.

Expected differences are compositor scaling, focus, clipboard, popup placement, and IME
candidate-window positioning through the XWayland bridge. There is no AppKit-style rule that
the process main thread must own a Wayland or X11 event loop. None of these expectations have
been run against this renderer.

## CI shape

The disabled Linux experiment in `.github/workflows/native-renderer.yml` uses
`ubuntu-24.04`, upstream GraalVM Community for JDK 25, the packages listed above, and Xvfb.
Its 90 minute timeout matches the macOS native job. A reasonable initial estimate is 20 to
40 minutes on an uncached hosted runner, but no Linux timing exists yet. Record the action's
build-step duration before replacing that estimate.

A headless runner can run the Xvfb startup smoke test. It cannot validate a real Wayland
session, hardware rendering, ibus or fcitx integration, candidate windows, or keyboard
composition. Those need an interactive Linux desktop runner, ideally one X11 session and one
Wayland session with XWayland.

## Recommended SPEC wording, not applied

PR-8 should keep its existing macOS requirements and add a Linux subsection with wording
equivalent to:

> Linux 데스크톱 네이티브 이미지는 JDK 25용 upstream GraalVM을 기본 툴체인으로 사용한다.
> 배포 `lib/`에는 Renderer, `libskiko-linux-<arch>.so`, `libjawt.so`, Native Image가
> 생성한 `libawt.so`, `libawt_headless.so`, `libawt_xawt.so`, `libfontmanager.so`,
> `libjava.so`, `libjvm.so`를
> 둔다. Linux 대상은 fontconfig, FreeType, X11 또는 Wayland 세션의 XWayland를 시스템
> 의존성으로 요구한다. ibus와 fcitx는 각각 실제 XIM 세션에서 네이티브 이미지로
> SPEC 6 체크리스트를 통과해야 한다. Xvfb smoke test는 IME 수용 기준을 대신하지 않는다.

NFR-4 should expose per-platform evidence instead of implying equal completion:

> 플랫폼 지원 상태는 플랫폼별 빌드, 시작, 렌더링, 입력, IME, 종료 증거 표로 관리한다.
> 한 플랫폼의 통과 결과를 다른 플랫폼에 적용하지 않는다. Linux는 X11과
> Wayland/XWayland를 구분하고 ibus와 fcitx 결과를 각각 기록한다.

Both additions should remain `Draft` until the Linux commands above have been run and their
results recorded.

## Most likely first failure

The first two failures were the C link (the version script, fixed above) and then AWT's JNI
registration (`NoClassDefFoundError: java/awt/GraphicsEnvironment`, addressed by preserving
`java.desktop`). The next expected failure is still metadata, but on the input side: a window
may render and ignore mouse or keyboard input, exactly as the Windows reference did before an
interaction-rich agent run. `-H:Preserve` covers the JDK half of that, not Compose's own. The next likely failures are JAWT resolution from
`<java.home>/lib/libjawt.so`, then a missing system library reported by `ldd`, especially
fontconfig, FreeType, or GL.
