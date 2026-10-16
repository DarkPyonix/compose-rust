#!/usr/bin/env bash
# The Kotlin/Native desktop renderers say how far their start got in the same three lines the
# native image does, and their smoke runs fail without them.
#
# The native image's windows print a window line, a scene line naming the main dispatcher and
# a first-frame line, and its smoke runs refuse a run that lacks any of them, because a
# renderer that returns 0 has not shown that it drew anything. The Kotlin/Native renderers
# printed none of them, and their CI ran an application without reading what it printed, so a
# window that opened and never drew passed.
#
# Checks three things without a renderer, a toolchain or a display:
#   - renderer/desktop/scripts/smoke-test-kn.sh, against stand-in applications that print what
#     a run that came up prints and the ways a run fails to;
#   - that both renderers compile the one file that writes those lines, and say each of them;
#   - that every place CI runs a Kotlin/Native application reads the lines.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
smoke="$repo_root/renderer/desktop/scripts/smoke-test-kn.sh"
scratch="$repo_root/.scratch/kn-start-report-test"
rm -rf "$scratch"
mkdir -p "$scratch"
trap 'rm -rf "$scratch"' EXIT

tests_run=0
tests_failed=0

failed() {
    tests_failed=$((tests_failed + 1))
    printf 'FAIL %s: %s\n' "$1" "$2" >&2
}

passed() { printf 'ok   %s\n' "$1"; }

# A stand-in application: prints <output> and exits <status>, and refuses to start unless it
# was asked for the self-check, which is the mode that ends once the frames are in.
fake_app() {
    local name="$1" status="$2" output="$3" app="$scratch/$1"
    printf '%s\n' "$output" > "$app.out"
    cat > "$app" <<EOF
#!/usr/bin/env bash
[[ "\${1:-}" == --self-check ]] || { echo "asked for '\${1:-}', not --self-check" >&2; exit 64; }
cat "$app.out" >&2
exit $status
EOF
    chmod +x "$app"
    echo "$app"
}

# expect <name> <pass|fail> <platform> <status> <output> [text the refusal must name]
expect() {
    local name="$1" want="$2" platform="$3" status="$4" out="$5" names="${6:-}" app said got
    tests_run=$((tests_run + 1))
    if [[ ! -x "$smoke" ]]; then
        failed "$name" "$smoke is missing or not executable"
        return
    fi
    app="$(fake_app "$name" "$status" "$out")"
    said="$("$smoke" "$app" "$platform" 2>&1)" && got=pass || got=fail
    if [[ "$got" != "$want" ]]; then
        failed "$name" "the smoke said $got, expected $want"$'\n'"$said"
        return
    fi
    if [[ -n "$names" && "$said" != *"$names"* ]]; then
        failed "$name" "the refusal does not name $names:"$'\n'"$said"
        return
    fi
    passed "$name"
}

# What the macOS renderer prints. Its standard error goes through NSLog, which puts the date
# and the process in front of every line.
nslog='2026-10-16 13:09:01.123 consumer[4242:9001] '
macos_window="${nslog}compose-rust: a window of our own, 1040x720 at 2.0x, with no toolkit in it"
macos_scene="${nslog}compose-rust: Compose scene created, main dispatcher kotlinx.coroutines.DarwinMainDispatcher (compose.main.dispatcher=unset), window 1040x720 at 2.0x"
macos_frame="${nslog}compose-rust: first frame drawn, 1040x720"
linux_window='compose-rust: a window of our own, 520x360 at 1.0x, with no toolkit in it'
linux_scene='compose-rust: Compose scene created, main dispatcher kotlinx.coroutines.MissingMainDispatcher (compose.main.dispatcher=unset), window 520x360 at 1.0x'
linux_frame='compose-rust: first frame drawn, 520x360'
drew='self-check: the renderer drew 2 frames'

expect nfr14_kn_macos_smoke_passes_a_run_that_drew_a_frame pass macos 0 \
    "$macos_window"$'\n'"$macos_scene"$'\n'"$macos_frame"$'\n'"$drew"
expect nfr14_kn_linux_smoke_passes_a_run_that_drew_a_frame pass linux 0 \
    "$linux_window"$'\n'"$linux_scene"$'\n'"$linux_frame"$'\n'"$drew"

# What both renderers printed before they reported their start: the application's own word
# that it drew, and nothing from the renderer. This is the run the old checks let through.
expect nfr14_kn_smoke_fails_a_run_with_no_start_lines fail macos 0 "$drew" "a window of our own"
expect nfr14_kn_linux_smoke_fails_a_run_with_no_start_lines fail linux 0 "$drew" "a window of our own"

expect nfr14_kn_smoke_fails_when_no_scene_was_made fail macos 0 \
    "$macos_window"$'\n'"$macos_frame"$'\n'"$drew" "Compose scene created"
expect nfr14_kn_smoke_fails_when_no_frame_was_drawn fail linux 0 \
    "$linux_window"$'\n'"$linux_scene"$'\n'"$drew" "first frame drawn"
# Each platform names its own main dispatcher, and a scene on the other one is not this one.
expect nfr14_kn_smoke_fails_a_scene_on_another_main_dispatcher fail linux 0 \
    "$linux_window"$'\n'"${macos_scene#"$nslog"}"$'\n'"$linux_frame"$'\n'"$drew" "MissingMainDispatcher"
expect nfr14_kn_smoke_fails_a_nonzero_exit fail macos 1 \
    "$macos_window"$'\n'"$macos_scene"$'\n'"$macos_frame" "exited with status 1"
expect nfr14_kn_smoke_refuses_an_unknown_platform fail windows 0 \
    "$linux_window"$'\n'"$linux_scene"$'\n'"$linux_frame" "windows"

# Both renderers compile the one file that writes the lines, rather than each its own copy.
tests_run=$((tests_run + 1))
shared="$repo_root/renderer/desktop/src/WindowStartReport.kt"
missing=""
for platform in macos linux; do
    link="$repo_root/renderer/$platform/src/shared/WindowStartReport.kt"
    if [[ ! -L "$link" || "$(cd "$(dirname "$link")" && cd "$(dirname "$(readlink "$link")")" && pwd)/$(basename "$(readlink "$link")")" != "$shared" ]]; then
        missing+=" $platform"
    fi
done
if [[ -n "$missing" ]]; then
    failed nfr14_kn_renderers_link_the_shared_start_report "not linked to $shared by:$missing"
else
    passed nfr14_kn_renderers_link_the_shared_start_report
fi

# And each says all three, where the window, the scene and a frame come into being.
tests_run=$((tests_run + 1))
said=""
for source in renderer/macos/src/MacosRenderer.kt renderer/linux/src/LinuxWindow.kt; do
    for call in 'WindowStartReport.windowOpened(' 'WindowStartReport.sceneCreated(' 'WindowStartReport.frameDrawn('; do
        grep -qF "$call" "$repo_root/$source" || said+=$'\n'"  $source does not call $call"
    done
done
if [[ -n "$said" ]]; then
    failed nfr14_kn_renderers_report_each_start_line "$said"
else
    passed nfr14_kn_renderers_report_each_start_line
fi

# Every place CI runs a Kotlin/Native application reads the lines: the pull request jobs for
# both platforms, and the release check of the single executable.
tests_run=$((tests_run + 1))
workflow="$repo_root/.github/workflows/test-kn-desktop-renderer.yml"
said=""
for platform in macos linux; do
    grep -qE "renderer/desktop/scripts/smoke-test-kn\.sh .*[[:space:]]$platform\$" "$workflow" ||
        said+=$'\n'"  $workflow runs no smoke-test-kn.sh for $platform"
done
grep -qF 'check_window_start' "$repo_root/.github/scripts/check-single-executable.sh" ||
    said+=$'\n'"  .github/scripts/check-single-executable.sh does not check the start lines"
if [[ -n "$said" ]]; then
    failed nfr14_kn_ci_requires_the_start_lines "$said"
else
    passed nfr14_kn_ci_requires_the_start_lines
fi

printf '\n%d test(s), %d failure(s)\n' "$tests_run" "$tests_failed"
[[ $tests_failed -eq 0 ]]
