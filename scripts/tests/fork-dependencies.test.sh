#!/usr/bin/env bash
# The Compose fork publishes its Compose to ~/.m2 under org.thisisthepy.compose at a version of
# its own (COMPOSE_AS in renderer/scripts/build-compose.sh), and every module names its siblings
# at that version. A sibling that is not there, because a build stopped half way or a module was
# left off the fork's publish-local.sh, sends the Kotlin toolchain to the network for a coordinate
# nobody published: "Unable to download checksums of file foundation-1.11.1-ext.1.pom", kept in
# its own cache after that.
#
# This runs build-compose.sh in a copy of the tree with git stubbed and a fake fork checkout
# whose extended/compose/publish-local.sh writes metadata the way the fork's build does, and
# checks:
#
#   build-compose.sh publishes through the fork's script, as COMPOSE_AS, and rewrites nothing
#   a second run, with everything already there, leaves the same files
#   build-compose.sh refuses a pin whose gradle.properties publishes another version
#   require-fork-artifacts.sh refuses a repository whose metadata names a sibling nobody
#     published, names it and the command that publishes it, and accepts the complete one
#   a constraint (an atomic group naming every module in it) is not a missing sibling, and
#     neither is a dependency of a root's variant for a target that was not built
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "the fork's published metadata names only modules that were published"

mkdir -p "$repo/.scratch"
work="$(mktemp -d "$repo/.scratch/fork-dependencies-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

compose_script="$repo/renderer/scripts/build-compose.sh"
fork_as="$(sed -n 's/^COMPOSE_AS="\([^"]*\)"$/\1/p' "$compose_script")"
material3_as="$(sed -n 's/^MATERIAL3_AS="\([^"]*\)"$/\1/p' "$compose_script")"
[[ -n "$fork_as" && -n "$material3_as" ]] || { fail "build-compose.sh names no COMPOSE_AS or MATERIAL3_AS"; exit 1; }

tree="$work/tree"
mkdir -p "$tree/renderer/scripts" "$tree/bin"
cp "$repo"/renderer/scripts/*.sh "$tree/renderer/scripts/"
for module in macos desktop linux; do
    mkdir -p "$tree/renderer/$module"
    cp "$repo/renderer/$module/module.yaml" "$tree/renderer/$module/"
done
printf '#!/usr/bin/env bash\nexit 0\n' > "$tree/bin/git"
chmod +x "$tree/bin/git"

# The fake fork checkout: the version lines build-compose.sh reads, a check that passes, and a
# publish-local.sh that writes each module's root and its target's publication, naming its
# siblings at the fork version and an atomic group of constraints, as the fork's build does.
compose="$work/compose-build"
mkdir -p "$compose/.git" "$compose/extended/compose"
printf 'jetbrains.publication.version.COMPOSE=%s\njetbrains.publication.version.COMPOSE_MATERIAL3=%s\n' \
    "$fork_as" "$material3_as" > "$compose/gradle.properties"
printf 'import sys\nsys.exit(0)\n' > "$compose/extended/compose/check-local.py"
cat > "$compose/extended/compose/publish-local.sh" <<'PUBLISH'
#!/usr/bin/env bash
set -euo pipefail
compose_version="$(sed -n 's/^jetbrains.publication.version.COMPOSE=//p' "$(dirname "$0")/../../gradle.properties")"
material3_version="$(sed -n 's/^jetbrains.publication.version.COMPOSE_MATERIAL3=//p' "$(dirname "$0")/../../gradle.properties")"
modules="${FAKE_MODULES:-runtime ui-util ui-text ui foundation material3}"
m2="$HOME/.m2/repository"
group_of() {
    case "$1" in
        foundation*) echo org.thisisthepy.compose.foundation ;;
        material3*) echo org.thisisthepy.compose.material3 ;;
        runtime*) echo org.thisisthepy.compose.runtime ;;
        *) echo org.thisisthepy.compose.ui ;;
    esac
}
dependencies_of() {
    case "$1" in
        foundation) echo runtime ui ui-text ui-util ;;
        ui) echo runtime ui-text ui-util ;;
        ui-text) echo runtime ui-util ;;
    esac
}
version_of() { case "$1" in material3*) echo "$material3_version" ;; *) echo "$compose_version" ;; esac; }
publish() { # <module> <artifact> <suffix>
    local module="$1" artifact="$2" suffix="$3" group directory dependency separator
    group="$(group_of "$module")"
    version="$(version_of "$module")"
    directory="$m2/$(tr . / <<< "$group")/$artifact/$version"
    mkdir -p "$directory"
    {
        printf '{\n  "formatVersion": "1.1",\n  "component": {\n    "group": "%s",\n    "module": "%s",\n    "version": "%s"\n  },\n' "$group" "$artifact" "$version"
        printf '  "variants": [\n    {\n      "name": "apiElements",\n      "dependencies": [\n'
        separator=""
        for dependency in $(dependencies_of "$module"); do
            printf '%s        {\n          "group": "%s",\n          "module": "%s%s",\n          "version": {\n            "requires": "%s"\n          }\n        }' \
                "$separator" "$(group_of "$dependency")" "$dependency" "$suffix" "$version"
            separator=$',\n'
        done
        printf '\n      ],\n      "dependencyConstraints": [\n        {\n          "group": "org.thisisthepy.compose.ui",\n          "module": "ui-test-junit4",\n          "version": {\n            "requires": "%s"\n          },\n          "reason": "atomic group"\n        }\n      ]\n    }' "$version"
        # A root lists every configured target, built here or not: an iOS variant naming
        # ui-uikit, which a macOS build never publishes.
        [[ -z "$suffix" ]] && printf ',\n    {\n      "name": "iosArm64ApiElements-published",\n      "dependencies": [\n        {\n          "group": "org.thisisthepy.compose.ui",\n          "module": "ui-uikit",\n          "version": {\n            "requires": "%s"\n          }\n        }\n      ]\n    }' "$version"
        printf '\n  ]\n}\n'
    } > "$directory/$artifact-$version.module"
    {
        printf '<project>\n  <groupId>%s</groupId>\n  <artifactId>%s</artifactId>\n  <version>%s</version>\n' "$group" "$artifact" "$version"
        printf '  <dependencyManagement>\n    <dependencies>\n      <dependency>\n        <groupId>org.thisisthepy.compose.ui</groupId>\n        <artifactId>ui-test-junit4</artifactId>\n        <version>%s</version>\n      </dependency>\n    </dependencies>\n  </dependencyManagement>\n  <dependencies>\n' "$version"
        for dependency in $(dependencies_of "$module"); do
            printf '    <dependency>\n      <groupId>%s</groupId>\n      <artifactId>%s%s</artifactId>\n      <version>%s</version>\n      <scope>compile</scope>\n    </dependency>\n' \
                "$(group_of "$dependency")" "$dependency" "$suffix" "$version"
        done
        printf '  </dependencies>\n</project>\n'
    } > "$directory/$artifact-$version.pom"
}
for target in "$@"; do
    case "$target" in
        macosArm64) suffix=macosarm64 ;;
        desktop) suffix=desktop ;;
        *) suffix="$(tr '[:upper:]' '[:lower:]' <<< "$target")" ;;
    esac
    for module in $modules; do
        publish "$module" "$module" ""
        publish "$module" "$module-$suffix" "-$suffix"
    done
done
PUBLISH
chmod +x "$compose/extended/compose/publish-local.sh"

# build <home> <target>: one build-compose.sh run; output in $work/out.
build() {
    HOME="$1" PATH="$tree/bin:$PATH" JAVA_HOME="$work/jdk" DXC_COMPOSE_BUILD="$compose" \
        bash "$tree/renderer/scripts/build-compose.sh" --target "$2" > "$work/out" 2>&1
}
digest() { find "$1/.m2" -type f -print0 | sort -z | xargs -0 shasum | shasum | cut -d' ' -f1; }
require() { HOME="$1" bash "$tree/renderer/scripts/require-fork-artifacts.sh" "$2" > "$work/out" 2>&1; }

foundation_module="$work/home-once/.m2/repository/org/thisisthepy/compose/foundation/foundation/$fork_as/foundation-$fork_as.module"

# A first run into an empty repository, and the same run again.
home="$work/home-once"
mkdir -p "$home"
if build "$home" macosArm64; then
    [[ -f "$foundation_module" ]] || fail "build-compose.sh did not publish foundation as $fork_as through the fork's script"
    grep -q '"requires": "'"$fork_as"'"' "$foundation_module" ||
        fail "foundation's metadata no longer names its siblings at $fork_as: build-compose.sh rewrote it"
    first="$(digest "$home")"
    if build "$home" macosArm64; then
        [[ "$(digest "$home")" == "$first" ]] || fail "a second macOS run changed what the first published"
    else
        fail "a second macOS run failed: $(tail -1 "$work/out")"
    fi
else
    fail "the first macOS run failed: $(tail -2 "$work/out" | head -1)"
fi

# What the fork published is complete, and its constraints are not missing siblings.
if ! require "$home" macos; then
    fail "require-fork-artifacts.sh macos refused what build-compose.sh left: $(grep -m1 -E ', named by |missing' "$work/out" || tail -1 "$work/out")"
fi

# A pin that publishes another version is refused before anything is built.
moved="$work/compose-moved"
cp -R "$compose" "$moved"
printf 'jetbrains.publication.version.COMPOSE=1.11.1-ext.9\njetbrains.publication.version.COMPOSE_MATERIAL3=%s\n' "$material3_as" > "$moved/gradle.properties"
mkdir -p "$work/home-moved"
if HOME="$work/home-moved" PATH="$tree/bin:$PATH" JAVA_HOME="$work/jdk" DXC_COMPOSE_BUILD="$moved" \
        bash "$tree/renderer/scripts/build-compose.sh" --target macosArm64 > "$work/out" 2>&1; then
    fail "build-compose.sh built a pin that publishes 1.11.1-ext.9 while the renderer asks for $fork_as"
else
    grep -q "does not publish jetbrains.publication.version.COMPOSE=$fork_as" "$work/out" ||
        fail "build-compose.sh refused the moved pin without saying which version: $(tail -1 "$work/out")"
    [[ ! -d "$work/home-moved/.m2" ]] || fail "build-compose.sh published something before refusing the moved pin"
fi

# A build that stopped half way: foundation and ui are there, the runtime they name is not.
home="$work/home-partial"
mkdir -p "$home"
HOME="$home" FAKE_MODULES="ui-util ui-text ui foundation" "$compose/extended/compose/publish-local.sh" macosArm64
if require "$home" macos; then
    fail "require-fork-artifacts.sh accepted metadata that names runtime $fork_as, which nobody published"
else
    grep -q "org.thisisthepy.compose.runtime:runtime:$fork_as" "$work/out" ||
        fail "require-fork-artifacts.sh refused without naming runtime $fork_as: $(tail -1 "$work/out")"
    grep -q "build-compose.sh --target macosArm64" "$work/out" ||
        fail "require-fork-artifacts.sh did not name the command that publishes it"
    grep -q "ui-test-junit4" "$work/out" &&
        fail "require-fork-artifacts.sh reported ui-test-junit4, which only a constraint names"
    grep -q "ui-uikit" "$work/out" &&
        fail "require-fork-artifacts.sh reported ui-uikit, which only the root's iOS variant names"
fi

if [[ "$failures" -eq 0 ]]; then echo "  ok"; else exit 1; fi
