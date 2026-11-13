#!/usr/bin/env bash
# Usage: ./scripts/tests/web-samples-on-the-guide.test.sh
#
# The guide site carries every sample as a page a reader can run in the browser, and lists
# the same set.
#
# The pages are not built here. The samples workflow builds them for a sample-v* tag and
# releases them as one archive; the Pages workflow downloads that archive and puts each
# sample at samples/<name>/ (.github/scripts/pages/). This checks those two scripts against
# archives made to look like the release's, with a fake gh standing in for GitHub, and
# checks that the workflows call them the way the scripts expect. Opening a page in a
# browser and seeing it draw is checked by hand.
#
# A sample is a directory under samples/ with a src/main.rs. `frames` has none: it is the
# library the others record their screens through, not an application.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
fetch="$repo_root/.github/scripts/pages/fetch-web-samples.sh"
place="$repo_root/.github/scripts/pages/place-web-samples.sh"
scratch="$repo_root/.scratch/web-samples-on-the-guide-test"
rm -rf "$scratch"
mkdir -p "$scratch"
trap 'rm -rf "$scratch"' EXIT

failures=0
fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
}
ok() { printf 'ok    %s\n' "$1"; }

samples=()
for main in samples/*/src/main.rs; do
    samples+=("$(basename "$(dirname "$(dirname "$main")")")")
done

page_files=(index.html web.mjs web.wasm skiko.mjs skiko.wasm
    compose_rust_host.wasm compose-rust-host.gen.mjs)

# An archive laid out like samples-web.tar.gz: a directory per sample with its page, and
# the index build-sample-pages.sh writes beside them. Names given after the first argument
# are left out.
make_archive() {
    local out="$1"
    shift
    local tree="$scratch/tree-$RANDOM$RANDOM"
    mkdir -p "$tree"
    local sample file skip
    for sample in "${samples[@]}"; do
        for skip in "$@"; do
            [[ "$skip" == "$sample" ]] && continue 2
        done
        mkdir -p "$tree/$sample/vendors"
        for file in "${page_files[@]}"; do
            echo "$sample $file" > "$tree/$sample/$file"
        done
        echo '<script src="web.mjs" type="module"></script>' > "$tree/$sample/index.html"
    done
    echo '<ul></ul>' > "$tree/index.html"
    tar -czf "$out" -C "$tree" .
}

fr22_7_1_pages_publishes_every_sample() {
    local archive site out

    # Every sample present: each one lands at samples/<name>/ with its whole page.
    archive="$scratch/all.tar.gz"
    make_archive "$archive"
    site="$scratch/site-all"
    cp -R docs/guide "$site"
    if out="$("$place" "$archive" "$site" 2>&1)"; then
        local sample file complete=true
        for sample in "${samples[@]}"; do
            for file in "${page_files[@]}"; do
                [[ -s "$site/samples/$sample/$file" ]] || {
                    complete=false
                    fail "1: $sample/$file was not placed"
                }
            done
        done
        [[ "$complete" == true ]] && ok "1: all ${#samples[@]} samples are placed at samples/<name>/"
        [[ -f "$site/samples/index.html" ]] && grep -q 'samples.html' "$site/samples/index.html" &&
            ok "1: samples/ itself leads to the guide's samples page" ||
            fail "1: samples/index.html does not lead to the guide's samples page"
        [[ ! -e "$site/samples/frames" ]] && ok "1: frames, which has no application, is not a page" ||
            fail "1: frames was published as a page"
        if find "$site" -maxdepth 1 -name '.web-samples.*' | grep -q .; then
            fail "1: the unpacked archive was left in the site"
        else
            ok "1: nothing unpacked is left behind in the site"
        fi
    else
        fail "1: placing a complete archive failed: $out"
    fi

    # One sample missing: the run fails, names it, and publishes nothing.
    local gone="${samples[0]}"
    archive="$scratch/missing.tar.gz"
    make_archive "$archive" "$gone"
    site="$scratch/site-missing"
    cp -R docs/guide "$site"
    if out="$("$place" "$archive" "$site" 2>&1)"; then
        fail "1: an archive without $gone was published"
    elif [[ "$out" != *"$gone"* ]]; then
        fail "1: the failure for a missing sample does not name it: $out"
    elif [[ -e "$site/samples" ]]; then
        fail "1: a failed run still wrote samples/ into the site"
    else
        ok "1: a sample missing from the archive fails the deployment and is named"
    fi

    # A page that is only the template, or that lacks a module, is not a page.
    local tree="$scratch/broken"
    mkdir -p "$tree"
    tar -xzf "$scratch/all.tar.gz" -C "$tree"
    echo '{{kotlin.scripts}}' > "$tree/${samples[1]}/index.html"
    : > "$tree/${samples[2]}/compose_rust_host.wasm"
    rm -f "$tree/${samples[3]}/skiko.wasm"
    tar -czf "$scratch/broken.tar.gz" -C "$tree" .
    site="$scratch/site-broken"
    cp -R docs/guide "$site"
    if out="$("$place" "$scratch/broken.tar.gz" "$site" 2>&1)"; then
        fail "1: a template page, an empty module and a missing module were published"
    elif [[ "$out" != *"${samples[1]}"* || "$out" != *"${samples[2]}"* || "$out" != *"${samples[3]}"* ]]; then
        fail "1: not every incomplete page is named: $out"
    else
        ok "1: a template page, an empty module and a missing module each fail"
    fi

    # The release the pages come from, through a fake gh.
    local releases="$scratch/releases" bin="$scratch/bin"
    mkdir -p "$bin"
    cat > "$bin/gh" <<'GH'
#!/usr/bin/env bash
# release view <tag> --json assets --jq ... | release list ... | release download <tag> ...
root="$FAKE_RELEASES"
case "$1 $2" in
    "release view")
        [[ -d "$root/$3" ]] || { echo "release not found" >&2; exit 1; }
        ls -1 "$root/$3" ;;
    "release list")
        ls -1 "$root" ;;
    "release download")
        tag="$3"; shift 3; dir=.; patterns=()
        while [[ $# -gt 0 ]]; do
            case "$1" in
                --pattern) patterns+=("$2"); shift 2 ;;
                --dir) dir="$2"; shift 2 ;;
                *) shift ;;
            esac
        done
        for p in "${patterns[@]}"; do cp "$root/$tag/$p" "$dir/"; done ;;
    *) echo "fake gh: $*" >&2; exit 1 ;;
esac
GH
    chmod +x "$bin/gh"
    release_with_archive() {
        mkdir -p "$releases/$1"
        cp "$scratch/all.tar.gz" "$releases/$1/samples-web.tar.gz"
        (cd "$releases/$1" && { sha256sum samples-web.tar.gz 2>/dev/null ||
            shasum -a 256 samples-web.tar.gz; } > SHA256SUMS-web)
    }
    run_fetch() { PATH="$bin:$PATH" FAKE_RELEASES="$releases" "$fetch" "$@" 2>&1; }

    rm -rf "$releases"
    mkdir -p "$releases/v0.0.9"
    release_with_archive sample-v0.0.3
    release_with_archive sample-v0.0.10
    out="$(run_fetch "$scratch/fetch-a")"
    [[ "$out" == *"release=sample-v0.0.10"* && -s "$scratch/fetch-a/samples-web.tar.gz" ]] &&
        ok "1: with no samples-latest, the newest sample-v* release is used" ||
        fail "1: without samples-latest, expected sample-v0.0.10: $out"

    release_with_archive samples-latest
    out="$(run_fetch "$scratch/fetch-b")"
    [[ "$out" == *"release=samples-latest"* ]] &&
        ok "1: samples-latest is used once it exists" ||
        fail "1: expected samples-latest: $out"

    out="$(run_fetch "$scratch/fetch-c" sample-v0.0.3)"
    [[ "$out" == *"release=sample-v0.0.3"* ]] &&
        ok "1: a named release is used" || fail "1: expected the named release: $out"

    echo tampered >> "$releases/samples-latest/samples-web.tar.gz"
    if out="$(run_fetch "$scratch/fetch-d")"; then
        fail "1: an archive that does not match its checksum was accepted"
    else
        ok "1: an archive that does not match its checksum fails"
    fi

    rm -rf "$releases"
    mkdir -p "$releases/v0.0.9"
    if out="$(run_fetch "$scratch/fetch-e")"; then
        fail "1: no release with web samples, and the fetch passed"
    else
        ok "1: no release carrying the web samples fails the deployment"
    fi

    # The workflow runs both scripts, on the directory it uploads, and deploys from main only.
    local pages=.github/workflows/pages.yml
    grep -q '\.github/scripts/pages/fetch-web-samples\.sh' "$pages" &&
        grep -q '\.github/scripts/pages/place-web-samples\.sh' "$pages" &&
        ok "1: the Pages workflow downloads and places the samples" ||
        fail "1: $pages does not run the fetch and place scripts"
    grep -q 'path: \${{ runner.temp }}/site' "$pages" && grep -q '"\$RUNNER_TEMP/site"' "$pages" &&
        ok "1: the directory the samples are placed in is the one uploaded" ||
        fail "1: $pages uploads a directory other than the one the samples go into"
    if grep -qE 'continue-on-error|\|\| *true' "$pages"; then
        fail "1: $pages lets a step fail without failing the run"
    else
        ok "1: no step of the Pages workflow is allowed to fail quietly"
    fi

    # The web build that makes the archive fails when a sample does not build. It runs in
    # a copy of the tree with the renderer build and the Host build stubbed out, so that
    # only the script's own decision is under test.
    local fake="$scratch/fake-root"
    mkdir -p "$fake/scripts" "$fake/renderer/web/scripts" "$fake/renderer/web/resources" \
        "$fake/renderer/build/tasks/_web_dist"
    cp scripts/build-sample-pages.sh "$fake/scripts/"
    printf '#!/usr/bin/env bash\nexit 0\n' > "$fake/renderer/kotlin"
    cat > "$fake/renderer/web/scripts/build-host.sh" <<'HOST'
#!/usr/bin/env bash
sample="${@: -1}"
[[ "$sample" != broken ]] || exit 1
echo "$sample" > "$(dirname "$0")/../resources/compose_rust_host.wasm"
HOST
    chmod +x "$fake/renderer/kotlin" "$fake/renderer/web/scripts/build-host.sh"
    echo '<script src="web.mjs"></script>' > "$fake/renderer/build/tasks/_web_dist/index.html"
    local name
    for name in fine broken; do
        mkdir -p "$fake/samples/$name/src"
        touch "$fake/samples/$name/Cargo.toml" "$fake/samples/$name/src/main.rs"
    done
    if out="$("$fake/scripts/build-sample-pages.sh" "$fake/out" 2>&1)"; then
        fail "1: build-sample-pages.sh passed with a sample that did not build"
    elif [[ "$out" != *broken* || ! -f "$fake/out/fine/compose_rust_host.wasm" ]]; then
        fail "1: build-sample-pages.sh failed without naming the sample, or stopped early: $out"
    else
        ok "1: build-sample-pages.sh fails when a sample does not build, and names it"
    fi
    rm -rf "$fake/samples/broken" "$fake/out"
    if out="$("$fake/scripts/build-sample-pages.sh" "$fake/out" 2>&1)"; then
        ok "1: build-sample-pages.sh passes when every sample builds"
    else
        fail "1: build-sample-pages.sh failed with every sample built: $out"
    fi
    local web_job
    web_job="$(awk '/^  web:/{on=1} on && /^  [a-z]+:/ && !/^  web:/{exit} on' .github/workflows/test-samples.yml)"
    if grep -q 'build-sample-pages.sh' <<<"$web_job" &&
        ! grep -qE 'continue-on-error|\|\| *true' <<<"$web_job"; then
        ok "1: the samples workflow's web job fails with the page build"
    else
        fail "1: the web job does not run build-sample-pages.sh, or lets it fail"
    fi
}

fr22_7_3_only_main_deploys() {
    local pages=.github/workflows/pages.yml deploy
    deploy="$(awk '/^  deploy:/{on=1} on' "$pages")"
    if grep -qF "if: github.ref == 'refs/heads/main' && github.event_name != 'pull_request'" <<<"$deploy" &&
        grep -q 'deploy-pages' <<<"$deploy"; then
        ok "3: only a run on main deploys"
    else
        fail "3: the deploy job of $pages is not limited to main"
    fi
    local build
    build="$(awk '/^  build:/{on=1} /^  deploy:/{exit} on' "$pages")"
    if grep -q 'deploy-pages' <<<"$build"; then
        fail "3: the job every branch runs also deploys"
    elif grep -q 'upload-pages-artifact' <<<"$build"; then
        ok "3: every other run assembles the site as an artifact only"
    else
        fail "3: the build job does not upload the assembled site"
    fi
}

# The text of each card: the samples.html line linking ../samples/<name>/, its <p>.
fr22_7_2_guide_lists_every_published_sample() {
    local lang page
    local expected
    expected="$(printf '%s\n' "${samples[@]}" | sort)"
    for lang in en ko; do
        page="docs/guide/$lang/samples.html"
        if [[ ! -f "$page" ]]; then
            fail "2: $page does not exist"
            continue
        fi
        local listed
        listed="$(grep -oE 'href="\.\./samples/[a-z0-9_-]+/"' "$page" |
            sed -E 's#href="\.\./samples/([^/]+)/"#\1#' | sort)"
        if [[ "$listed" == "$expected" ]]; then
            ok "2: $page links exactly the ${#samples[@]} samples"
        else
            fail "2: $page lists a different set of samples"
            diff <(echo "$expected") <(echo "$listed") | sed 's/^/        /' >&2
        fi
        local sample line described=true
        for sample in "${samples[@]}"; do
            line="$(grep -F "href=\"../samples/$sample/\"" "$page" | head -n 1)"
            if ! grep -qE '<p>[^<]{10,}</p>' <<<"$line"; then
                described=false
                fail "2: $page has no one-line description for $sample"
            fi
        done
        [[ "$described" == true ]] && ok "2: $page describes each sample in a line"

        # The page is part of the guide: every page of the language links it in the
        # sidebar, it marks itself current, and it names its other language.
        local other missing_nav=()
        for other in docs/guide/"$lang"/*.html; do
            [[ "$(basename "$other")" == index.html ]] && continue
            grep -qE '<li><a href="samples\.html"( aria-current="page")?>' "$other" ||
                missing_nav+=("$(basename "$other")")
        done
        [[ ${#missing_nav[@]} -eq 0 ]] && ok "2: every $lang page links the samples page in its sidebar" ||
            fail "2: $lang pages without the samples page in the sidebar: ${missing_nav[*]}"
        grep -q '<li><a href="samples.html" aria-current="page">' "$page" &&
            grep -q 'hreflang="en" href="../en/samples.html"' "$page" &&
            grep -q 'hreflang="ko" href="../ko/samples.html"' "$page" &&
            ok "2: $page is current in its sidebar and links both languages" ||
            fail "2: $page lacks its current marker or a language link"
    done
    grep -q '/en/samples.html</loc>' docs/guide/sitemap.xml &&
        grep -q '/ko/samples.html</loc>' docs/guide/sitemap.xml &&
        ok "2: the sitemap names the samples page in both languages" ||
        fail "2: the sitemap does not name the samples page"
}

fr22_7_1_pages_publishes_every_sample
fr22_7_2_guide_lists_every_published_sample
fr22_7_3_only_main_deploys

if [[ $failures -gt 0 ]]; then
    echo "$failures check(s) failed" >&2
    exit 1
fi
echo "all passed"
