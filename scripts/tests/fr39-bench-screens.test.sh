#!/usr/bin/env bash
# Usage: scripts/tests/fr39-bench-screens.test.sh
#
# Guards the screens the authoring path comparison measures on the slot table side.
#
# The frozen scenarios in compose-rust/benches/fr39/scenarios click controls of the sample
# screens as they were when the scenarios were written, and the pinned Dioxus path still
# draws those screens. The shipped samples were redrawn later, so the candidate draws
# copies of the old screens kept in compose-rust/benches/fr39/screens, written in today's
# call form. Three things keep that true:
#
# 1. The candidate depends on no shipped sample crate for a screen that has a fixture.
#    Linking the shipped sample again would compare a different screen with the pinned one.
# 2. Every source file of a fixture opens with where it came from: the words
#    "Measurement fixture." and "Taken from", and a commit id. A reader who meets one of
#    these files has to learn that it is a copy kept on purpose, not a sample to update.
# 3. No widget is written as a builder chain in the fixtures. The fixtures are written in
#    today's function and call macro form; a chain would mean an old form crept back in,
#    or that a fixture was pasted from the October sources without being ported.
#
# The chain check is text-level and simple, and these are its limits. It sees a widget
# function call (a name the runtime's `ui::functions` exports, called without `!`) whose
# argument list nests parentheses at most three deep and is followed by `.method(`. It does
# not see a chain through a variable (`let b = Button(..); b.on_click(..)`), and a string
# literal holding an unbalanced parenthesis inside such a call can hide one. It is not a
# parser; compose-rust/tests/fr39_no_builder_chains.rs is the check on the runtime itself.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

bench=compose-rust/benches/fr39
screens="$bench/screens"
candidate_manifest="$bench/candidate/Cargo.toml"
candidate_main="$bench/candidate/src/main.rs"
functions=compose-rust/src/ui/functions/mod.rs

# The screens whose fixtures have landed. Each one must exist and be what the candidate
# links. The list grows to every screen the scenarios use: calculator, chat, minimal, todo.
required=(calculator minimal)

tests_run=0
tests_failed=0

fail_test() {
    tests_failed=$((tests_failed + 1))
    printf 'FAIL %s: %s\n' "$1" "$2" >&2
    [[ -n "${3:-}" ]] && printf '%s\n' "$3" | sed 's/^/      /' >&2
}

pass_test() {
    printf 'ok   %s\n' "$1"
}

# The widget names the runtime exports as functions, read from its re-export list so the
# check follows the API instead of a copy of it.
widget_names() {
    perl -0777 -ne 'while (/pub use \w+::\{([^}]*)\}/g) { print "$1\n" }' "$functions" |
        tr ',' '\n' | tr -d ' \t' | grep -E '^[A-Z][A-Za-z0-9]*$' | sort -u
}

# Prints `file:line: text` for every widget builder chain in the files given.
chains_in() {
    local names
    names="$(widget_names | paste -sd '|' -)"
    [[ -n "$names" ]] || {
        echo "no widget names found in $functions" >&2
        return 2
    }
    NAMES="$names" perl -0777 -ne '
        my $names = $ENV{NAMES};
        # Up to three levels of nested parentheses inside the call.
        my $inner = qr/(?:[^()]|\((?:[^()]|\((?:[^()]|\([^()]*\))*\))*\))*/s;
        while (/(?<![!\w:])\b($names)\s*\(($inner)\)\s*\.\s*([a-z_][a-z0-9_]*)\s*\(/g) {
            my ($at, $widget, $method) = ($-[0], $1, $3);
            my $before = substr($_, 0, $at);
            my $line = 1 + ($before =~ tr/\n//);
            print "$ARGV:$line: $widget(..).$method(..)\n";
        }
    ' "$@"
}

# The check has to be able to fail, or it proves nothing: it is fed a chain first.
fr39_bench_screens_chain_check_sees_a_chain() {
    tests_run=$((tests_run + 1))
    local work sample found
    work="$(mktemp -d "$repo_root/.scratch/fr39-bench-screens.XXXXXX" 2>/dev/null ||
        { mkdir -p "$repo_root/.scratch" &&
            mktemp -d "$repo_root/.scratch/fr39-bench-screens.XXXXXX"; })"
    sample="$work/sample.rs"
    cat > "$sample" <<'RUST'
fn chained() {
    Button("Save")
        .on_click(|| {});
    Column().modifier(Modifier.fill_max_width()).content(|| {});
    Text(format!("{}", (1 + 2))).color(Paint::Role(ColorRole::OnSurface));
}
fn fine() {
    Button!("Save", || {});
    Text!(format!("{}", 1), modifier = Modifier.fill_max_width().weight(1.0));
    let list = DrawListBuilder::with_capacity(2, 0).circle(paint, 1.0, 1.0, 1.0, 0.0);
    my_Button(1).x();
}
RUST
    found="$(chains_in "$sample")"
    rm -rf "$work"
    local count
    count="$(grep -c . <<< "$found" || true)"
    if [[ "$count" != 3 ]] ||
        ! grep -q 'sample.rs:2: Button(..).on_click(..)' <<< "$found" ||
        ! grep -q 'sample.rs:4: Column(..).modifier(..)' <<< "$found" ||
        ! grep -q 'sample.rs:5: Text(..).color(..)' <<< "$found"; then
        fail_test "${FUNCNAME[0]}" "expected exactly the three chains in the sample" "$found"
        return
    fi
    pass_test "${FUNCNAME[0]}"
}

fr39_bench_screens_exist_for_every_required_screen() {
    tests_run=$((tests_run + 1))
    local missing=()
    local name
    for name in "${required[@]}"; do
        [[ -f "$screens/$name/Cargo.toml" && -f "$screens/$name/src/lib.rs" ]] ||
            missing+=("$screens/$name")
        grep -qE "^\s*\"screens/$name\"" "$bench/Cargo.toml" ||
            missing+=("$name is not a member of $bench/Cargo.toml")
    done
    if [[ ${#missing[@]} -gt 0 ]]; then
        fail_test "${FUNCNAME[0]}" "fixtures missing" "$(printf '%s\n' "${missing[@]}")"
        return
    fi
    pass_test "${FUNCNAME[0]}"
}

fr39_bench_screens_candidate_uses_no_shipped_sample() {
    tests_run=$((tests_run + 1))
    local problems=()
    local name
    for name in "${required[@]}"; do
        grep -qE "^\s*sample-$name\s*=" "$candidate_manifest" &&
            problems+=("$candidate_manifest depends on the shipped sample-$name")
        grep -qE "\bsample_$name\b" "$candidate_main" &&
            problems+=("$candidate_main names the shipped sample_$name")
        grep -qE "^\s*fr39-screen-$name\s*=\s*\{\s*path\s*=\s*\"\.\./screens/$name\"" \
            "$candidate_manifest" ||
            problems+=("$candidate_manifest does not depend on ../screens/$name")
        grep -qE "\bfr39_screen_$name::app\b" "$candidate_main" ||
            problems+=("$candidate_main does not start fr39_screen_$name::app")
    done
    if [[ ${#problems[@]} -gt 0 ]]; then
        fail_test "${FUNCNAME[0]}" "the candidate is not drawing the frozen screens" \
            "$(printf '%s\n' "${problems[@]}")"
        return
    fi
    pass_test "${FUNCNAME[0]}"
}

fr39_bench_screens_say_where_they_came_from() {
    tests_run=$((tests_run + 1))
    local missing=()
    local file head
    while IFS= read -r file; do
        head="$(sed -n '1,40p' "$file")"
        if [[ "$head" != *"Measurement fixture."* || "$head" != *"Taken from"* ]] ||
            ! grep -qE '\b[0-9a-f]{8}\b' <<< "$head"; then
            missing+=("$file")
        fi
    done < <(find "$screens" -path '*/src/*' -name '*.rs' | sort)
    if [[ ${#missing[@]} -gt 0 ]]; then
        fail_test "${FUNCNAME[0]}" "files without a provenance header" \
            "$(printf '%s\n' "${missing[@]}")"
        return
    fi
    pass_test "${FUNCNAME[0]}"
}

fr39_bench_screens_have_no_builder_chains() {
    tests_run=$((tests_run + 1))
    local files found
    files=()
    while IFS= read -r file; do files+=("$file"); done < \
        <(find "$screens" -path '*/src/*' -name '*.rs' | sort)
    if [[ ${#files[@]} -eq 0 ]]; then
        fail_test "${FUNCNAME[0]}" "no fixture sources under $screens"
        return
    fi
    found="$(chains_in "${files[@]}")"
    if [[ -n "$found" ]]; then
        fail_test "${FUNCNAME[0]}" "widget builder chains in the fixtures" "$found"
        return
    fi
    pass_test "${FUNCNAME[0]}"
}

if ! command -v perl >/dev/null 2>&1; then
    echo "FAIL perl is not installed; the chain check needs it" >&2
    exit 1
fi

fr39_bench_screens_chain_check_sees_a_chain
fr39_bench_screens_exist_for_every_required_screen
fr39_bench_screens_candidate_uses_no_shipped_sample
fr39_bench_screens_say_where_they_came_from
fr39_bench_screens_have_no_builder_chains

echo "$tests_run tests, $tests_failed failed"
[[ "$tests_failed" -eq 0 ]]
