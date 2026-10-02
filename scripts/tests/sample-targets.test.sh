#!/usr/bin/env bash
# Usage: ./scripts/tests/sample-targets.test.sh
#
# The samples are built for the platforms the renderer is published for, and named the
# same way.
#
# Two lists that have to agree and had nothing between them. `PUBLISHED_TARGETS` in the
# build script's search rules says which platforms a renderer artifact exists for; the
# matrix in .github/workflows/samples.yml says which ones a sample is built for. The
# first sample release went out for three of the four, because Linux on arm64 was in one
# list and not the other, and the two spelled x86_64 differently besides.
#
# A sample cannot be built for a platform the renderer is not published for, so this
# checks one direction as an error and the other as an error too: a published renderer
# with no samples is a platform whose users get a library and no examples.
#
# Only the desktop matrix is compared. Android, iOS and the browser are jobs of their own
# rather than matrix rows, because on those a sample is not a program: it is a library an
# Activity loads, an executable the renderer is linked into, or a module a page fetches.
# That they exist at all is checked below instead.
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

published="$(grep -o 'PUBLISHED_TARGETS: &\[&str\] = &\[[^]]*\]' dioxus-compose/build/renderer_dir.rs |
    grep -o '"[a-z0-9_-]*"' | tr -d '"' | sort)"
built="$(grep -E '^\s+target: ' .github/workflows/samples.yml |
    sed -E 's/.*target: *//' | tr -d '"' | sort)"

if [[ -z "$published" ]]; then
    fail "the published target list could not be read" \
        "PUBLISHED_TARGETS in dioxus-compose/build/renderer_dir.rs no longer has the" \
        "shape this reads, so nothing was compared and this test passed on nothing."
elif [[ -z "$built" ]]; then
    fail "the sample matrix could not be read" \
        "No target: lines in .github/workflows/samples.yml."
elif [[ "$published" != "$built" ]]; then
    fail "the samples are not built for the platforms the renderer is published for" \
        "published: $(echo "$published" | tr '\n' ' ')" \
        "built:     $(echo "$built" | tr '\n' ' ')" \
        "A name in one and not the other is a platform whose users get a library with" \
        "no examples, or an archive that cannot find a renderer to run against."
fi

# The three that are not desktop platforms. Each is a job rather than a matrix row, and
# each was missing from the first sample release, so their absence is worth an assertion
# rather than a reader's memory.
for job in android ios web; do
    if ! grep -qE "^  $job:$" .github/workflows/samples.yml; then
        fail "the samples are not built for $job" \
            "It is a job of its own rather than a matrix row, because there a sample is" \
            "not a program. Without it that platform ships a library and no examples."
    fi
done

if ! grep -q "needs: \[build, android, ios, web\]" .github/workflows/samples.yml; then
    fail "the release does not wait for every platform" \
        "A release attached before a platform finishes is a release missing it."
fi

if [[ $failures -eq 0 ]]; then
    echo "ok    samples are built for every published target and every other platform"
fi
exit "$failures"
