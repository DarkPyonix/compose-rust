#!/usr/bin/env bash
# The Kotlin/Native Linux window's input method and accessibility: what is wired to what.
#
# Whether Korean can be typed and Orca can read the window is a fact about a desktop. What can
# be read from the sources is the wiring that makes either possible, and each item below has been
# the reason an input method or a screen reader got nothing from a window that otherwise worked:
#
# - the input method is given every event first, and what it takes is not handled twice;
# - composing text goes to the scene through the window's own event log and the text input
#   session Compose opened, and never past it into the Host;
# - the XIM preedit callbacks are registered, because without them the syllable being built is
#   drawn by the input method outside the field;
# - the start callback is reinterpreted as returning an int, because an input method reads the
#   return register and a Unit function leaves garbage in it;
# - the accessibility bridge is started before the loop and read once a turn on the window's own
#   thread, and the semantics listener it reads from is the one the scene reports to.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
src="$repo_root/renderer/linux/src"
window="$src/LinuxWindow.kt"
xim="$src/XimContext.kt"
ime="$src/InputMethod.kt"
entry="$src/LinuxRenderer.kt"
bridge="$src/AtspiBridge.kt"
server="$src/AtspiServer.kt"
# The Linux static renderer is built, and checked, in the reusable workflow the native
# renderer workflow calls for linux-x64.
workflow="$repo_root/.github/workflows/static-renderer.yml"

red=0
fail() {
    echo "fail: $1"
    red=1
}

for file in "$window" "$xim" "$ime" "$entry" "$bridge" "$server" "$src/AtspiSemantics.kt" \
            "$src/AtspiModel.kt" "$src/AtspiWire.kt" \
            "$repo_root/renderer/linux/test/InputMethodTest.kt" \
            "$repo_root/renderer/linux/test/AtspiTest.kt"; do
    [[ -f "$file" ]] || fail "missing $file"
done
(( red == 0 )) || exit 1

has() {
    local pattern="$1" file="$2" why="$3"
    grep -Eq -- "$pattern" "$file" || fail "$(basename "$file") does not match '$pattern': $why"
}
absent() {
    local pattern="$1" file="$2" why="$3"
    if grep -Eq -- "$pattern" "$file"; then
        fail "$(basename "$file") matches '$pattern': $why"
    fi
}

# ---- XIM -----------------------------------------------------------------------------------
has 'XOpenIM' "$xim" "no input method is ever opened"
has 'XCreateIC' "$xim" "no input context is ever made"
has 'XNPreeditStartCallback' "$xim" "the preedit start callback is not registered"
has 'XNPreeditDoneCallback' "$xim" "the preedit done callback is not registered"
has 'XNPreeditDrawCallback' "$xim" "the preedit draw callback is not registered, so the syllable is never drawn in the field"
has 'XNPreeditCaretCallback' "$xim" "the preedit caret callback is not registered; some input methods refuse a client without it"
has 'XNSpotLocation' "$xim" "the candidate window is never told where the caret is"
has 'Xutf8LookupString' "$xim" "committed text is not read as UTF-8"
has 'XFilterEvent' "$xim" "events are never offered to the input method"
has 'XSetICFocus' "$xim" "the input context never gets focus"
# The start callback returns an int. See the comment in XimContext.kt.
has 'staticCFunction<COpaquePointer\?, COpaquePointer\?, COpaquePointer\?, Int>' "$xim" \
    "the preedit start callback must return an int, which is the longest composition it will take"

# The window offers every event to the input method before handling it, and drops a key it took.
filter_line="$(grep -n 'xim?.filter(event)' "$window" | head -1 | cut -d: -f1)"
when_line="$(grep -n '        when (event.type) {' "$window" | head -1 | cut -d: -f1)"
[[ -n "$filter_line" && -n "$when_line" && "$filter_line" -lt "$when_line" ]] ||
    fail "the window handles events before the input method has been offered them"
has 'taken && \(event.type == KeyPress \|\| event.type == KeyRelease\)' "$window" \
    "a key the input method took is handled again by the window"
has 'EVENT_MASK or context.filterMask' "$window" "the events the input method asked for are not selected"
has 'updateInputMethod\(\)' "$window" "the input method is never told where the caret is or when a field has focus"
has 'finishComposition\(\)' "$window" "a click does not end a composition"

# Composing text reaches Compose through the window's own log and the text input session.
has 'ImeSession \{ event -> log.heard\(event\) \}' "$window" \
    "what an input method says does not go through the log every other event goes through"
has 'textInput.receive\(event\)' "$window" "recorded text events never reach the text input session"
absent 'compose_rust_host|HostConnection|dispatch_event' "$xim" "composition text must never be sent to the Host"
absent 'compose_rust_host|HostConnection|dispatch_event' "$ime" "composition text must never be sent to the Host"
absent 'PlatformTextInputService|setPlatformImeService' "$window" "this bypasses Compose's platform text input"

# ---- AT-SPI --------------------------------------------------------------------------------
has 'org.a11y.atspi.Socket' "$bridge" "the window never registers with the registry"
has '"Embed"' "$bridge" "the embedding call is not made"
has '"GetAddress"' "$bridge" "the accessibility bus is not looked up through the session bus"
has 'org.a11y.atspi.Accessible' "$server" "the accessible interface is not served"
has 'org.a11y.atspi.Component' "$server" "the component interface is not served, so no reader can place a control"
has 'org.a11y.atspi.Action' "$server" "the action interface is not served, so nothing can be pressed"
has 'startAccessibility\(' "$entry" "the entry point never joins the accessibility bus"
has 'accessibility\?\.pump\(\)' "$window" "calls from a reader are never answered"
has 'semanticsListeners' "$window" "the scene does not report its semantics to the bridge"
has 'NO_AT_BRIDGE' "$window" "the standard switch for turning the bridge off is not honoured"
absent 'java\.|JNI|org\.graalvm' "$bridge" "Kotlin/Native has no JVM"

# ---- the check that runs a real registry and input method -----------------------------------
has 'check-linux-input-access\.sh' "$workflow" "the headless check is not run where the Linux static renderer is built"
[[ -x "$repo_root/.github/scripts/check-linux-input-access.sh" ]] ||
    fail "the headless check is missing or not executable"

(( red == 0 )) || exit 1
echo "ok: the Linux window offers events to the input method first, composes through Compose's text input, and serves AT-SPI"
