#!/usr/bin/env bash
# Usage: ./scripts/tests/pr3-samples-start-no-threads.test.sh
#
# The samples start no threads of their own and call nothing that panics in a browser.
#
# Every sample is also a browser page, and a browser page here has no threads: the
# renderer owns the one memory, so there is nothing for Rust to run a second thread on.
# Domain work goes through `compose_rust::runtime::with_worker`, which runs on a worker
# thread where there is one and inline where there is not, and a wait is a `delay`.
# A sample that starts its own thread, or asks std for something only an operating system
# can answer, compiles for the web and aborts there at runtime with `unreachable`. Two
# samples shipped like that: todo started a saver thread before its first frame, and
# notepad asked for the temporary directory to name its starting file. chat started a
# thread for every reply.
#
# What this rejects in a sample's code (its test module excepted):
#   - starting or parking a thread: `thread::spawn`, `thread::Builder`, `thread::scope`,
#     `thread::sleep`;
#   - asking for a place or a time only an operating system has, which std answers with
#     a panic on `wasm32-unknown-unknown`: `temp_dir()`, `current_dir()`, `home_dir()`,
#     `Instant::now`, `SystemTime::now`. These are allowed inside an item gated with
#     `#[cfg(not(target_family = "wasm"))]`, which is how a sample says what it does
#     instead on the web.
#
# `samples/frames` is left out: it records the other samples' screens from their tests and
# is only ever a dev-dependency, so it is never part of a page.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

failures=0
fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
    shift
    local line
    for line in "$@"; do printf '        %s\n' "$line" >&2; done
}

# Prints file:line: text for every offending line of one Rust source.
scan() {
    awk -v file="$1" '
        /^#\[cfg\(test\)\]/ { exit }
        {
            line = $0
            code = line
            sub(/^[ \t]*\/\/.*/, "", code)
            if (code ~ /#\[cfg\(not\(target_family = "wasm"\)\)\]/) { pending = 1; next }
            if (pending && code ~ /^[ \t]*#\[/) { next }
            if (pending) { gated = 1; depth = 0; opened = 0; pending = 0 }
            if (code ~ /thread::(spawn|Builder|scope|sleep)/) {
                print file ":" NR ": " line
            } else if (!gated && code ~ /(temp_dir|current_dir|home_dir)\(|(Instant|SystemTime)::now/) {
                print file ":" NR ": " line
            }
            if (gated) {
                n = split(code, chars, "")
                for (i = 1; i <= n; i++) {
                    if (chars[i] == "{") { depth++; opened = 1 }
                    if (chars[i] == "}") { depth-- }
                }
                if ((opened && depth <= 0) || (!opened && code ~ /[;,][ \t]*$/)) { gated = 0 }
            }
        }
    ' "$1"
}

# The scanner has to find what it is looking for, or every sample passes on nothing.
probe_source() {
    cat <<'EOF'
fn start() {
    std::thread::Builder::new().spawn(|| {}).unwrap();
}
fn path() -> std::path::PathBuf {
    std::env::temp_dir()
}
#[cfg(not(target_family = "wasm"))]
fn allowed() -> std::path::PathBuf {
    std::env::temp_dir()
}
fn after_the_gate() -> std::time::Instant {
    std::time::Instant::now()
}
#[cfg(test)]
mod tests {
    fn sleeps() { std::thread::sleep(std::time::Duration::ZERO); }
}
EOF
}
probe="$(probe_source | scan -)"
expected_lines="2 5 12"
found_lines="$(echo "$probe" | sed -nE 's/^[^:]*:([0-9]+):.*/\1/p' | tr '\n' ' ' | sed 's/ $//')"
if [[ "$found_lines" != "$expected_lines" ]]; then
    fail "the scanner does not find what it looks for" \
        "on a probe it reported lines '$found_lines', expected '$expected_lines'," \
        "so its verdict on the samples below means nothing."
fi

scanned=0
for source in samples/*/src/*.rs; do
    case "$source" in samples/frames/*) continue ;; esac
    scanned=$((scanned + 1))
    found="$(scan "$source")"
    if [[ -n "$found" ]]; then
        # shellcheck disable=SC2086
        fail "$source starts a thread or asks for something a browser page cannot answer" \
            "$found" \
            "A browser page has no threads and no filesystem, and this aborts the page there." \
            "Run the work through with_worker, wait with delay, or gate the call with" \
            "#[cfg(not(target_family = \"wasm\"))] and say what the web does instead."
    fi
done

if [[ "$scanned" -eq 0 ]]; then
    fail "no sample sources were found under samples/*/src"
fi

if [[ "$failures" -eq 0 ]]; then
    echo "ok    $scanned sample sources start no threads and ask nothing a browser page cannot answer"
fi
exit "$failures"
