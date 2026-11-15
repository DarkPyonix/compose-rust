#!/usr/bin/env bash
# The UI thread's stack on Windows: the wiring of the check that would have caught the crash.
#
# Released applications ended with "thread 'main' has overflowed its stack" as their window
# opened: the renderer composed and drew on the 1 MB Windows main thread. The Host now runs
# the renderer on a thread it makes with a large stack, and the proof is on a Windows runner:
# scripts/check-windows-consumer.sh draws a deep tree, and draws it again on a 1 MB stack to
# see it overflow. Nothing here can run that. What this can say is that the pieces point at
# each other: the script asks the fixture for the deep tree both ways, the fixture knows the
# argument, and the variable the script holds the stack down with is the one the Host reads.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/scripts/check-windows-consumer.sh"
fixture="$repo_root/compose-rust/tests/fixtures/consumer/src/main.rs"
boundary="$repo_root/compose-rust/src/boundary.rs"
red=0
fail() {
    echo "fail: $1"
    red=1
}

for file in "$script" "$fixture" "$boundary"; do
    [[ -f "$file" ]] || fail "missing ${file#"$repo_root"/}"
done
(( red == 0 )) || exit 1

# The Host makes the thread on Windows, with a stack and a name, and reads the override.
grep -Eq '^const HOST_MAKES_UI_THREAD: bool = cfg!\(windows\);$' "$boundary" ||
    fail "boundary.rs no longer makes the UI thread on Windows"
grep -Eq '^const UI_THREAD_STACK_BYTES: usize = 64 \* 1024 \* 1024;$' "$boundary" ||
    fail "boundary.rs no longer gives the UI thread 64 MB; the guide and the changelog say that number"
grep -Fq 'self.launch_through(runtime, renderer_api().run, HOST_MAKES_UI_THREAD)' "$boundary" ||
    fail "launching no longer hands the renderer loop to the thread the Host makes"
grep -Fq 'run_renderer_loop(run, on_ui_thread)' "$boundary" ||
    fail "launching no longer runs the renderer loop through run_renderer_loop"
[[ "$(grep -c '(renderer_api().run)()' "$boundary")" -eq 0 ]] ||
    fail "something in boundary.rs calls the renderer loop on its own thread again"
variable="$(sed -n 's/^const UI_THREAD_STACK_VARIABLE: &str = "\([A-Z_]*\)";$/\1/p' "$boundary")"
[[ -n "$variable" ]] || fail "boundary.rs names no variable for the UI thread's stack"
name="$(sed -n 's/^const UI_THREAD_NAME: &str = "\([a-z-]*\)";$/\1/p' "$boundary")"
[[ -n "$name" ]] || fail "boundary.rs gives the UI thread no name"

# The script holds the stack down with that variable and looks for that thread's overflow.
if [[ -n "$variable" ]]; then
    grep -Fq "export $variable=" "$script" ||
        fail "check-windows-consumer.sh does not set $variable, so it never shows the small stack overflowing"
fi
if [[ -n "$name" ]]; then
    grep -Fq "thread '$name' has overflowed its stack" "$script" ||
        fail "check-windows-consumer.sh does not look for the overflow of the thread named $name"
fi
grep -Fq 'old_stack_bytes=1048576' "$script" ||
    fail "check-windows-consumer.sh no longer tries the 1 MB a Windows main thread has"
grep -Fq -- '--self-check "--deep-tree=$depth"' "$script" ||
    fail "check-windows-consumer.sh does not ask the fixture for the deep tree"
grep -Fq 'deep_tree "$deep_depth" || status=$?' "$script" ||
    fail "check-windows-consumer.sh does not draw the deep tree on the stack the Host gives"
grep -Fq 'deep_tree "$deep_depth" "$old_stack_bytes" || status=$?' "$script" ||
    fail "check-windows-consumer.sh does not draw the deep tree on the old stack"
grep -Fq 'drew on a $old_stack_bytes byte stack' "$script" ||
    fail "check-windows-consumer.sh accepts a depth the old stack already held"

# The fixture knows the argument and counts frames for it as for any self-check.
grep -Fq 'strip_prefix("--deep-tree=")' "$fixture" ||
    fail "the consumer fixture does not read --deep-tree=<depth>"
grep -Fq 'with_deep_tree(deep_tree)' "$fixture" ||
    fail "the consumer fixture's self-check does not put the deep tree under its screen"

(( red == 0 )) || exit 1
echo "ok    the deep tree check is wired: script, fixture and Host name the same variable and thread"
