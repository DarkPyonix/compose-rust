#!/usr/bin/env bash
# test.yml builds with one named Rust version, not with whatever "stable" is that day.
#
# The compile-fail fixtures under compose-rust/macros/tests compare rustc's error text
# word for word. That text changes between Rust releases, so a job that installs "stable"
# starts failing them on the day a release comes out, in a change that touched nothing.
# The version is written once, in RUST_TOOLCHAIN at the top of the workflow, so moving it
# is one edit made together with the regenerated .stderr files.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/test.yml"
failures=0
ok()   { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; failures=$((failures + 1)); }

# What a workflow text does about the toolchain: prints one line per fault.
faults() { # file
    local file="$1" version
    version="$(sed -n 's/^  RUST_TOOLCHAIN: *"\([^"]*\)".*/\1/p' "$file")"
    if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "RUST_TOOLCHAIN is not an exact version: '${version}'"
    fi
    # One rustup call per line of output, so a line that installs the named version and
    # then makes another one the default is still seen.
    grep -noE 'rustup (toolchain install|default) [^ ]+' "$file" |
        grep -v '"\$RUST_TOOLCHAIN"$' |
        sed 's/^/a step names its own toolchain: /'
    if ! grep -qE 'rustup toolchain install "\$RUST_TOOLCHAIN"' "$file"; then
        echo "no step installs \$RUST_TOOLCHAIN"
    fi
}

# The shell job runs setup-check.test.sh, whose real run of scripts/setup-check.sh fails
# without rustfmt and clippy. A minimal install of the pinned version has neither, so the
# job has to name them; the runner's own stable used to supply them unasked.
shell_job_faults() { # file
    local job
    job="$(awk '/^  shell:/{on=1; next} on && /^  [A-Za-z_-]+:/{exit} on' "$1")"
    if ! grep -qE 'rustup toolchain install "\$RUST_TOOLCHAIN".*--component rustfmt,clippy' <<< "$job"; then
        echo "the shell job installs \$RUST_TOOLCHAIN without rustfmt and clippy"
    fi
}

got="$(faults "$workflow")"
if [[ -z "$got" ]]; then ok "test.yml installs the one version RUST_TOOLCHAIN names"; else fail "test.yml: $got"; fi

# The check can fail: each fault it looks for, in a workflow written to have it.
scratch="$(mktemp -d "${TMPDIR:-/tmp}/rust-toolchain-pin.XXXXXX")"
trap 'rm -rf "$scratch"' EXIT
expect_fault() { # description wanted-text workflow-text
    printf '%s\n' "$3" > "$scratch/w.yml"
    if faults "$scratch/w.yml" | grep -q "$2"; then ok "$1"; else fail "$1 was not reported"; fi
}
expect_fault "a moving channel in RUST_TOOLCHAIN" "not an exact version" \
'env:
  RUST_TOOLCHAIN: "stable"
      run: rustup toolchain install "$RUST_TOOLCHAIN"'
expect_fault "a step that installs stable" "names its own toolchain" \
'env:
  RUST_TOOLCHAIN: "1.100.0"
      run: rustup toolchain install "$RUST_TOOLCHAIN"
      run: rustup toolchain install stable --profile minimal'
expect_fault "a step that makes stable the default" "names its own toolchain" \
'env:
  RUST_TOOLCHAIN: "1.100.0"
      run: rustup toolchain install "$RUST_TOOLCHAIN" && rustup default stable'
expect_fault "a workflow that never installs the version" "no step installs" \
'env:
  RUST_TOOLCHAIN: "1.100.0"'

got="$(shell_job_faults "$workflow")"
if [[ -z "$got" ]]; then ok "the shell job installs rustfmt and clippy for setup-check.test.sh"; else fail "test.yml: $got"; fi
printf '%s\n' 'jobs:
  shell:
    steps:
      - run: rustup toolchain install "$RUST_TOOLCHAIN" --profile minimal && rustup default "$RUST_TOOLCHAIN"
  next:
      - run: rustup toolchain install "$RUST_TOOLCHAIN" --profile minimal --component rustfmt,clippy' > "$scratch/w.yml"
if shell_job_faults "$scratch/w.yml" | grep -q "without rustfmt and clippy"; then
    ok "a shell job without the components"
else
    fail "a shell job without the components was not reported"
fi

[[ $failures -eq 0 ]] || { echo "$failures failed"; exit 1; }
echo "all passed"
