#!/usr/bin/env bash
# Builds the Compose this renderer draws with from the Compose fork, and publishes it locally.
#
# Three things the renderer draws with are not in the build JetBrains publishes and cannot
# be reached from outside the module that holds them: the Kotlin/Native targets for Linux,
# the native text context menu, and the keys that copy. They are `internal actual`
# declarations, and an `expect` can only be answered inside its own module. The changes
# live as commits in a fork of Compose, thisisthepy/compose-multiplatform-core-extended,
# on its `extended` branch. This fetches one pinned commit of it into a checkout nobody
# edits by hand, and runs the fork's own extended/compose/publish-local.sh, which publishes
# every module the renderer draws with to the local Maven repository.
#
# The fork publishes under its own group and version, org.thisisthepy.compose.*:1.11.1-ext.6
# (its extended/COORDINATES.md), and the renderer modules ask for exactly those. Nothing
# JetBrains publishes sits at those coordinates, so a resolver that already holds JetBrains'
# Compose (Amper's cache does) cannot take JetBrains' jars in their place, and nothing here
# rewrites what the fork published.
#
# Usage: build-compose.sh [--target macosArm64|desktop|linux|linuxX64|linuxArm64|mingwX64] [--clean]
#
# The work directory is a sibling of the renderer called compose-build. Set
# DXC_COMPOSE_BUILD to put it elsewhere. It is not inside the renderer because it is a
# checkout of someone else's repository and a build of it costs several gigabytes.
set -euo pipefail

# The commit, not the branch. A branch that moves is a build that changes for a reason
# nobody chose here. This one is JetBrains release/1.11 at 73ac849 with the Linux targets, the
# native text context menu (opening at the pointer on macOS), the AWT-free copy, text direction
# and main dispatcher, the AppKit pump on the main thread, the window modules, the design systems
# under extended/design-systems (which scripts/fetch-design-systems.sh reads), and the
# org.thisisthepy.compose coordinates with extended/compose/publish-local.sh, which this runs.
# It was the fork's v1.11.1-ext.4 tag. Since then it is the commit the fork's v1.11.1-ext.5 tag
# goes on, which publishes as 1.11.1-ext.5. The ext.4 tag kept the ext.3 build's Compose sources,
# moved the version line, built the design systems and the window modules into Java 11 class
# files (graalvm-linux into Java 17 ones), which JDK 17 and 21 consumers can load where ext.3's
# Java 25 and 21 class files failed (fork #226), and published skiko-awt with its own capability
# only, the desktop modules naming the fork's skiko-awt rather than JetBrains' skiko (fork #167).
# It also carried the Windows window's system edit menu through `dxc_native_set_edit_menu` and
# the scene leaving the text menu to Compose (fork #221), an X11 window keeping its size in dp
# across a scale change (fork #216), F1 to F12 and the Menu key on every window (fork #219), the
# Windows window drawing its new size before it shows it after a maximise, restore or snap (fork
# #222), and one menu on a right click in a macOS text field (fork #224).
# ext.5 is the first tag since the ext.2 build to change Compose itself, in foundation: the mouse
# wheel scrolls the Kotlin/Native Windows and Linux windows the way it turns and as far as
# desktop Compose does on the same system (`WindowsScrollConfig`, `LinuxScrollConfig`), and the
# macOS scroll config, given no NSEvent, works out the distance from the travel, 10 dp a line
# (`MacOsScrollConfig`).
# With the scene sending a scroll where the pointer last was instead of at the top left corner,
# and the macOS window telling it where the pointer is before a scroll that came before any move,
# that makes the wheel and the trackpad scroll on the Kotlin/Native macOS window, where nothing
# scrolled before (fork #240). Everything the window pin below held past the ext.4 tag (fork
# #232, #235, #239, #236, #234, #237) is in the tag as well, and so is the AT-SPI readback tool
# matching roles by number (fork #241, a test tool).
# It is now the commit the fork's v1.11.1-ext.6 tag goes on (the merge of fork #300), which
# publishes as 1.11.1-ext.6. Past ext.5 the Compose sources change in the desktop paragraph line
# height (fork #284), web absolute alignment (fork #285), the JS DOM API (fork #288) and the
# mingwX64 targets of animation-graphics and material-navigation, which are dropped because no
# one used them (fork #286; the renderer uses neither). compose-fork.changes lists what this commit
# must hold at every path it changes, and scripts/tests/compose-fork.test.sh checks it.
FORK="https://github.com/thisisthepy/compose-multiplatform-core-extended.git"
REVISION="328f40fbe81e25c1c1861dec10c309d8c081c2cf"
# The window modules and skiko's builds are not Compose sources: the renderer builds them from
# this commit (scripts/fetch-fork-window.sh and fetch-fork-skiko.sh) into artifacts of its own,
# so they can move without moving the Compose build above. This is the tip of the fork's
# `extended` branch, which holds the Windows caption buttons safe default and HTMAXBUTTON hit test
# for Snap Layouts (fork #172), the Windows wheel direction fix (fork #170) and the windows' own
# accessibility bridges (the renderer hands them the scene's semantics). It replaces the
# backport branch feat/windows-window-backport-57b4a0e, which carried those two fixes on an
# older base; multi-window work builds on `extended` itself. It also holds the macOS file open
# and save sheets (fork #183, `MacosFileDialogs`), which the macOS renderer shows when the Host
# asks for a file dialog, the operating system's text size (fork #156, `WindowPlatform.textScale`
# and `WindowEvent.TextScaleChanged`), which every desktop window hands its scene as
# `Density.fontScale`, and the Windows window's file drop through IDropTarget (fork #157), which
# the Windows renderer routes to the node under the files. The Windows file dialogs (fork #190)
# are a C file of their own, `win32_file_dialog.c`, which desktop/scripts/build-windows.sh
# compiles beside the window. It also holds the macOS right click's single system edit menu and
# the GraalVM window's dropping of Control characters (fork #158), and the Kotlin/Native macOS
# window's `setApplicationIcon`, live appearance watch and explicit text clipboard (fork #159),
# all Kotlin in the window modules, so nothing new is compiled or published here. It also holds
# the IME text staying at the caret after a key moves it (fork #197), the GraalVM Windows
# window's text size as a multiplier (fork #198), the X11 window's _NET_WM_PID and
# WM_CLIENT_MACHINE (fork #199) and the macOS window host delivering heard events from its draw
# path and event handlers (fork #200), so the macOS renderer needs no timer of its own to drain
# them. Those changes sit in files the window build already compiles. It also holds the code
# editor style (fork #155), the new accessibility roles (fork #201), the GraalVM macOS window's
# appearance setter `dxc_native_set_appearance` (fork #203) and the Windows caption buttons with
# Snap Layouts (fork #202, a header `win32_caption.h` beside `win32_window.c`). The Kotlin/Native
# Windows window configures its system chrome itself, so its windows keep their caption.
# It also holds the macOS window typing a key once instead of twice (fork #209), the X11
# windows reporting Xft.dpi as their scale and following its changes (fork #205), and the
# Windows window skipping a stretched present when ResizeBuffers fails (fork #206, a header
# `win32_resize.h` beside `win32_window.c`), the native view slot and both macOS windows following
# a backing-scale change (fork #207), and the GNOME text scale applied once on Linux (fork #210).
# At the fork's v1.11.1-ext.3 tag it added the KDE text scale applied once when the fonts DPI is
# forced (fork #211), which hands the X11 window the desktop's scale beside the text factor
# through `dxc_native_set_text_factor_for`, and pointer events timed by the platform with its own
# double-click interval (fork #214), which grows the window's event record and adds
# `dxc_native_double_click_millis` to the AppKit and Windows windows. The event record is read
# only by the fork's Kotlin; the renderer's C stand-ins answer the new text factor call on the
# desktops without an X11 window.
# At a13a628 it was the tip of `extended` past the v1.11.1-ext.4 tag, and published as
# 1.11.1-ext.4 like the tag. The tag brings what the Compose pin above lists for the windows; the
# X11 and AppKit C answer `dxc_native_set_edit_menu` themselves, so nothing new needs a stand-in
# here. Beyond the tag it holds the Windows window showing, on a restore or a snap, the frame
# drawn ahead through the swapchain, its probe gated on a rate of faulty frames because DWM can
# still compose one in between (fork #232; a surface that takes size and picture in one step stays open in fork
# #223), the macOS native view's frame committed in the frame's own transaction, so a slot no
# longer runs a frame ahead of the content under it (fork #235, for #233), a node's accessible
# role that an application declares reaching the Kotlin/Native macOS window
# (`DeclaredElementRoles`, fork #234), and two CI changes (fork #229 and #231). All of it is
# window Kotlin and C the window build already compiles.
# Past that it holds the macOS window holding its backdrop blur still while a native view slot
# shows, so the view and the frame of one turn are composited together, with `place()` doing
# work only where a slot's input changed and `setFrameAndVisibleRegion`, which sets both in one
# call (fork #239, for #233); the Windows window letting the application own its caption strip
# and move the window from the scene (fork #236, `dxc_native_window_configure_app_caption`); and
# quoted booleans in the publish workflows (fork #237).
# It is now the Compose pin's commit again, the one the v1.11.1-ext.5 tag goes on, and publishes
# as 1.11.1-ext.5. Past a187dd1 it holds the scene sending a scroll to where the last move,
# press, release or enter put the pointer (`ScenePointer`, held by `ComposeWindowHost` and
# handed to `ComposeScene.receive`), the macOS window telling the scene where the pointer is
# before a scroll that came before any move, and a table in `WindowEvent.SCROLL` of the units and
# the sign each window layer sends (fork #240, with the foundation change above). The renderer's
# Linux and Windows windows hand their events to the scene through their own WindowEvents.kt,
# which already sends a scroll where the last pointer event was, and the macOS renderer goes
# through the fork's scene, so nothing here changes with it. The new scene code is Kotlin the
# window build already compiles; the Windows C keeps its symbols.
# At 07ab59e, past the Windows pin, the macOS native view moves with `setFrameOrigin:` when only
# its place changes and resizes with `setFrameSize:` when only its size does, which costs less
# per view per frame while scrolling than a full `setFrame:` (fork #247), and the scene's
# accessibility honours `DeclaredElementRoles` (fork #248). Both are Kotlin the window build
# already compiles.
# At ext.6 it is the Compose pin's commit again. Past ext.5 the windows carry the 36 CSS cursor
# keywords (`CursorShape`, fork #282), the Windows caption strip as tall as its title bar style
# asks (`Win32Chrome.NORMAL_CAPTION_HEIGHT` and `dxc_native_window_configure_caption_height`,
# fork #281), the toasts calling what `dxc_notify_set_wake` registered instead of a renderer
# symbol (fork #296; the renderer registers its frame request in renderer_entry.c and
# Win32Notifications.kt), UI Automation patterns (fork #261), Windows and Linux native views
# (fork #262, #268), the GDI route as the Windows default with a composition wait per drag step
# (fork #259, #277) and the GraalVM vertical resize sync (fork #273).
WINDOW_REVISION="328f40fbe81e25c1c1861dec10c309d8c081c2cf"
# The commit the Windows build takes. It was its own pin while the Windows Compose lived on a
# branch apart from the Compose pin; the fork's `extended` now holds mingwX64 for every Compose UI
# module, the skiko build for mingwX64 (extended/skiko/build-skiko-mingw.sh, which also compiles
# Skia's ICU data in as embedded_icu.obj) and publish-local.sh mingwX64, so it is the Compose
# pin's commit, the one the v1.11.1-ext.6 tag goes on, as the window pin is.
# The name stays so the Windows pin can move apart again. compose-fork-mingw.changes lists
# what this commit must hold, and scripts/tests/compose-fork.test.sh checks both pins.
MINGW_REVISION="328f40fbe81e25c1c1861dec10c309d8c081c2cf"
# What the pinned commit publishes as: its gradle.properties gives every library line a version
# of the form <upstream version>-ext.<N>. The renderer modules ask for these by name, and
# scripts/tests/fork-versions.test.sh checks that they do; this script checks the pinned
# commit's gradle.properties says the same before it builds anything.
COMPOSE_AS="1.11.1-ext.6"
MATERIAL3_AS="1.11.1-ext.6"
# The fork's patched skiko-awt, published by its extended/skiko/build-skiko-awt.sh (through
# scripts/publish-skiko-awt.sh). It goes out as org.thisisthepy.compose.skiko:skiko-awt on the
# fork's version line. Since 1.11.1-ext.4 its module metadata declares its own capability only
# (fork #167); ext.3's also declared JetBrains' skiko-awt, which publish-skiko-awt.sh drops.
SKIKO_AWT_AS="1.11.1-ext.6"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDERER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK="${DXC_COMPOSE_BUILD:-$(dirname "$RENDERER_DIR")/compose-build}"
SKIKO_WORK="${DXC_SKIKO_BUILD:-$(dirname "$RENDERER_DIR")/.scratch/skiko-build}"
# Every Gradle build below resolves from Maven Central and the plugin portal, which sometimes
# answer "not found" for a moment. The wrapper runs a build again only when it failed fetching
# a dependency (scripts/tests/dependency-download-retries.test.sh).
RETRY="$SCRIPT_DIR/retry-resolution.sh"
export DXC_RETRY_LOG="${DXC_RETRY_LOG:-$WORK/build/retry-resolution.log}"
# On a CI runner Maven Central answers shared addresses "429 Too Many Requests" for minutes,
# so every Gradle build there reads a mirror of it first (maven-central-mirror.gradle, installed
# into the Gradle user home's init.d). DXC_MAVEN_CENTRAL_MIRROR names another mirror, or "off".
MAVEN_CENTRAL_MIRROR="${DXC_MAVEN_CENTRAL_MIRROR:-}"
if [[ -z "$MAVEN_CENTRAL_MIRROR" && "${GITHUB_ACTIONS:-}" == true ]]; then
    MAVEN_CENTRAL_MIRROR="https://maven-central.storage-download.googleapis.com/maven2/"
fi
if [[ -n "$MAVEN_CENTRAL_MIRROR" && "$MAVEN_CENTRAL_MIRROR" != off ]]; then
    init_d="${GRADLE_USER_HOME:-$HOME/.gradle}/init.d"
    mkdir -p "$init_d"
    sed "s#@MIRROR@#$MAVEN_CENTRAL_MIRROR#" "$SCRIPT_DIR/maven-central-mirror.gradle" \
        > "$init_d/dxc-maven-central-mirror.gradle"
    echo "==> Gradle reads Maven Central through $MAVEN_CENTRAL_MIRROR first"
fi

die() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

target="macosArm64"
clean=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --target) target="${2:-}"; shift 2 ;;
        --clean) clean=1; shift ;;
        -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
        *) die "unknown argument '$1'" "usage: build-compose.sh [--target <target>] [--clean]" ;;
    esac
done

# The fork's publish-local.sh targets. `linux` is both architectures, which is what building
# the renderer's Linux module takes: the toolchain resolves every platform a module declares,
# even when it is asked to build one. arm64 is cross compiled on an x86-64 machine, because
# Kotlin/Native has no arm64 Linux host.
case "$target" in
    macosArm64|desktop|linuxX64|linuxArm64) publish_targets=("$target") ;;
    linux) publish_targets=(linuxX64 linuxArm64) ;;
    mingwX64)
        publish_targets=(mingwX64)
        revision="$MINGW_REVISION"
        ;;
    *) die "unknown target '$target'" "known: macosArm64, desktop, linux (both of the next two), linuxX64, linuxArm64, mingwX64" ;;
esac

revision="${revision:-$REVISION}"

[[ $clean -eq 1 ]] && rm -rf "$WORK"

# Fetched at the revision alone rather than cloned whole: the history of this repository
# is large and none of it is read here. The remote is set every time because a work
# directory made before the fork existed still points at JetBrains, which does not have
# this commit.
if [[ ! -d "$WORK/.git" ]]; then
    mkdir -p "$WORK"
    git -C "$WORK" init -q
    git -C "$WORK" remote add origin "$FORK"
fi
git -C "$WORK" remote set-url origin "$FORK"
if ! git -C "$WORK" cat-file -e "$revision^{commit}" 2>/dev/null; then
    echo "==> fetching $revision"
    git -C "$WORK" fetch -q --depth 1 origin "$revision"
fi

# Reset rather than built on top of whatever is there. A tree somebody edited builds
# something nobody can reproduce, and a work directory from before the fork still holds
# the patches that used to be applied here.
echo "==> checking out $revision"
git -C "$WORK" -c advice.detachedHead=false checkout -q --force "$revision"
git -C "$WORK" clean -qfd -e build -e '.gradle' -e 'out'

[[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME is not set" \
    "The Compose build needs a JDK 17 or 21; the toolchain wrapper's does not apply here."

# skiko before Compose on Windows, from the same commit, so the two cannot disagree about
# which skiko the Compose modules were built against.
if [[ "$target" == mingwX64 ]]; then
    skiko_script="$WORK/extended/skiko/build-skiko-mingw.sh"
    [[ -f "$skiko_script" ]] || die "$revision has no extended/skiko/build-skiko-mingw.sh" \
        "The Windows pin has to be a commit of the fork that carries the skiko build."
    echo "==> building skiko for mingwX64 into $SKIKO_WORK"
    "$RETRY" bash "$skiko_script" "$SKIKO_WORK"
fi

publish="$WORK/extended/compose/publish-local.sh"
check="$WORK/extended/compose/check-local.py"
[[ -x "$publish" && -f "$check" ]] || die "$revision has no extended/compose/publish-local.sh" \
    "Pin a commit of the fork that publishes under org.thisisthepy.compose."

# The versions the pin publishes as are the ones the renderer modules name. Read before the
# build, so a pin that moved the versions fails in seconds rather than after an hour of Gradle.
for line in "COMPOSE=$COMPOSE_AS" "COMPOSE_MATERIAL3=$MATERIAL3_AS"; do
    grep -qx "jetbrains.publication.version.$line" "$WORK/gradle.properties" ||
        die "the fork at $REVISION does not publish jetbrains.publication.version.$line" \
            "Its gradle.properties holds: $(grep '^jetbrains.publication.version.COMPOSE' "$WORK/gradle.properties" | paste -sd' ' -)" \
            "Set COMPOSE_AS and MATERIAL3_AS here to what it publishes, and the renderer modules with them."
done

echo "==> publishing the fork's Compose for ${publish_targets[*]} as $COMPOSE_AS"
"$RETRY" "$publish" "${publish_targets[@]}"

# Every sibling a published module names is there, nothing names JetBrains' copy of a module the
# fork publishes, and nothing at JetBrains' coordinates carries a fork version.
python3 -I "$check"

echo
echo "published to $HOME/.m2/repository/org/thisisthepy/compose as $COMPOSE_AS"
echo "the renderer's macos, linux and desktop modules read mavenLocal first, so the next build links these"
