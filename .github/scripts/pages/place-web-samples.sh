#!/usr/bin/env bash
# Usage: .github/scripts/pages/place-web-samples.sh <archive> <site-directory>
#
# Unpacks the web sample archive into <site-directory>/samples/<name>/, one page per
# sample, and fails unless every sample in this checkout is there.
#
# A sample is a directory under samples/ with a src/main.rs: an application rather than a
# library (`frames` is the library the others record their screens through). The guide
# lists exactly that set, so a sample the archive lacks would be a dead link on the site.
# It fails rather than skipping it, because a deployment that goes ahead without one
# hides the broken build instead of reporting it.
#
# A page counts when it holds what a browser needs to draw it: the built page (not the
# template with its `{{kotlin.scripts}}` placeholder), the renderer's module and its
# loader, Skia's module, and the sample's own module with the generated loader that carries
# the trampoline between the two.
set -euo pipefail

archive="${1:?usage: place-web-samples.sh <archive> <site-directory>}"
site="${2:?usage: place-web-samples.sh <archive> <site-directory>}"
repo_root="${SAMPLES_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)}"

page_files=(
    index.html
    web.mjs
    web.wasm
    skiko.mjs
    skiko.wasm
    compose_rust_host.wasm
    compose-rust-host.gen.mjs
)

[[ -f "$archive" ]] || { echo "error: $archive does not exist" >&2; exit 1; }
[[ -d "$site" ]] || { echo "error: $site is not a directory" >&2; exit 1; }

samples=()
for main in "$repo_root"/samples/*/src/main.rs; do
    [[ -f "$main" ]] || continue
    samples+=("$(basename "$(dirname "$(dirname "$main")")")")
done
[[ ${#samples[@]} -gt 0 ]] || {
    echo "error: no sample with a src/main.rs under $repo_root/samples" >&2
    exit 1
}

# Unpacked beside the site rather than in a system temporary directory, and removed on
# the way out, so nothing is left behind outside the directory being assembled.
staging="$(mktemp -d "$site/.web-samples.XXXXXX")"
trap 'rm -rf "$staging"' EXIT
tar -xzf "$archive" -C "$staging"

missing=()
for sample in "${samples[@]}"; do
    page="$staging/$sample"
    lacks=()
    for file in "${page_files[@]}"; do
        [[ -s "$page/$file" ]] || lacks+=("$file")
    done
    if [[ -f "$page/index.html" ]] && grep -q '{{kotlin.scripts}}' "$page/index.html"; then
        lacks+=("a built index.html (this one is the template)")
    fi
    if [[ ${#lacks[@]} -gt 0 ]]; then
        missing+=("$sample: ${lacks[*]}")
    fi
done

if [[ ${#missing[@]} -gt 0 ]]; then
    echo "error: ${#missing[@]} of ${#samples[@]} samples have no complete page in $archive:" >&2
    printf '         %s\n' "${missing[@]}" >&2
    echo "       Every sample has to build for the web. Fix the build, release the samples" >&2
    echo "       again with a sample-v* tag, then deploy the guide again." >&2
    exit 1
fi

rm -rf "$site/samples"
mkdir -p "$site/samples"
for sample in "${samples[@]}"; do
    cp -R "$staging/$sample" "$site/samples/$sample"
done

# /samples/ itself goes to the guide page that describes them, in English unless the
# reader has chosen Korean, the same way the site's own index does.
cat > "$site/samples/index.html" <<'HTML'
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>compose-rust samples</title>
<link rel="canonical" href="../en/samples.html">
<meta http-equiv="refresh" content="0; url=../en/samples.html">
<script>
  (function () {
    try {
      if (localStorage.getItem("dxc-lang") === "ko") { location.replace("../ko/samples.html"); return; }
    } catch (e) { /* ignore */ }
    location.replace("../en/samples.html");
  })();
</script>
</head>
<body>
<p><a href="../en/samples.html">Samples</a> &middot; <a href="../ko/samples.html">샘플</a></p>
</body>
</html>
HTML

echo "placed ${#samples[@]} sample pages under $site/samples: ${samples[*]}"
