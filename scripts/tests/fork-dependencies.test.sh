#!/usr/bin/env bash
# The Compose fork publishes a few modules to ~/.m2 under a version of its own (EXTENDED_AS in
# renderer/scripts/build-compose.sh), and Gradle writes every dependency on a sibling module at
# that same version, built or not. foundation 1.11.1001 then asks for runtime 1.11.1001, which
# nobody published, and the Kotlin toolchain fails with "Unable to download checksums of file
# foundation-1.11.1001.pom". build-compose.sh points those dependencies back at upstream.
#
# It used to find what to repoint by asking which directories were newer than the run. A second
# run whose Gradle tasks were up to date overwrote the published files in directories that
# already existed, found nothing newer, stopped with "nothing was published", and left Gradle's
# unrepointed metadata in place. CI never saw it, because CI publishes once into an empty ~/.m2
# or restores a repository that was repointed when it was saved.
#
# This runs build-compose.sh in a copy of the tree with git stubbed and a fake ./gradlew that
# writes metadata the way the fork's build does, and checks:
#
#   a first run and an up-to-date second run both repoint what was not published
#   a desktop publish beside a macOS one keeps each target's own dependencies right
#   require-fork-artifacts.sh refuses a repository whose metadata names unpublished modules,
#     and names the module, and accepts the repaired one
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "the fork's published metadata names only modules that were published"

mkdir -p "$repo/.scratch"
work="$(mktemp -d "$repo/.scratch/fork-dependencies-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

compose_script="$repo/renderer/scripts/build-compose.sh"
fork_as="$(sed -n 's/^EXTENDED_AS="\([^"]*\)"$/\1/p' "$compose_script" | head -1)"
upstream_as="$(sed -n 's/^PUBLISHED_AS="\([^"]*\)"$/\1/p' "$compose_script")"
[[ -n "$fork_as" && -n "$upstream_as" ]] || { fail "build-compose.sh names no EXTENDED_AS or PUBLISHED_AS"; exit 1; }

tree="$work/tree"
mkdir -p "$tree/renderer/scripts" "$tree/bin"
cp "$repo"/renderer/scripts/*.sh "$tree/renderer/scripts/"
for module in macos desktop linux; do
    mkdir -p "$tree/renderer/$module"
    cp "$repo/renderer/$module/module.yaml" "$tree/renderer/$module/"
done
printf '#!/usr/bin/env bash\nexit 0\n' > "$tree/bin/git"
chmod +x "$tree/bin/git"

# The fake Compose checkout. Its gradlew publishes each module the tasks name, root and target,
# with dependencies at the publication version on every sibling, as the fork's build does. It
# writes over files in place, as Gradle does, so a second run adds no directory entries.
compose="$work/compose-build"
mkdir -p "$compose/.git"
cat > "$compose/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
set -euo pipefail
version=""
for argument in "$@"; do
    case "$argument" in -Pjetbrains.publication.version.COMPOSE=*) version="${argument#*=}" ;; esac
done
m2="$HOME/.m2/repository"
group_of() {
    case "$1" in
        animation*) echo org.jetbrains.compose.animation ;;
        foundation*) echo org.jetbrains.compose.foundation ;;
        runtime*) echo org.jetbrains.compose.runtime ;;
        *) echo org.jetbrains.compose.ui ;;
    esac
}
dependencies_of() {
    case "$1" in
        foundation) echo animation foundation-layout runtime ui ui-text ui-util ;;
        ui) echo runtime ui-geometry ui-text ui-util ;;
        ui-text) echo runtime ui-graphics ui-util ;;
    esac
}
# publish <module> <artifact> <suffix for dependencies in the pom>
publish() {
    local module="$1" artifact="$2" suffix="$3" group directory dependency separator
    group="$(group_of "$module")"
    directory="$m2/$(tr . / <<< "$group")/$artifact/$version"
    mkdir -p "$directory"
    {
        printf '{\n  "formatVersion": "1.1",\n  "component": {\n    "group": "%s",\n    "module": "%s",\n    "version": "%s"\n  },\n' "$group" "$artifact" "$version"
        printf '  "variants": [\n    {\n      "name": "apiElements",\n      "dependencies": [\n'
        separator=""
        for dependency in $(dependencies_of "$module"); do
            printf '%s        {\n          "group": "%s",\n          "module": "%s",\n          "version": {\n            "requires": "%s"\n          }\n        }' \
                "$separator" "$(group_of "$dependency")" "$dependency" "$version"
            separator=$',\n'
        done
        printf '\n      ],\n      "dependencyConstraints": [\n        {\n          "group": "org.jetbrains.compose.foundation",\n          "module": "foundation-layout",\n          "version": {\n            "requires": "%s"\n          },\n          "reason": "atomic group"\n        }\n      ]\n    }\n  ]\n}\n' "$version"
    } > "$directory/$artifact-$version.module"
    {
        printf '<project>\n  <groupId>%s</groupId>\n  <artifactId>%s</artifactId>\n  <version>%s</version>\n' "$group" "$artifact" "$version"
        printf '  <dependencyManagement>\n    <dependencies>\n      <dependency>\n        <groupId>org.jetbrains.compose.foundation</groupId>\n        <artifactId>foundation-layout</artifactId>\n        <version>%s</version>\n      </dependency>\n    </dependencies>\n  </dependencyManagement>\n  <dependencies>\n' "$version"
        for dependency in $(dependencies_of "$module"); do
            printf '    <dependency>\n      <groupId>%s</groupId>\n      <artifactId>%s%s</artifactId>\n      <version>%s</version>\n      <scope>compile</scope>\n    </dependency>\n' \
                "$(group_of "$dependency")" "$dependency" "$suffix" "$version"
        done
        printf '  </dependencies>\n</project>\n'
    } > "$directory/$artifact-$version.pom"
}
for task in "$@"; do
    case "$task" in
        :compose:*:publish*PublicationToMavenLocal) ;;
        *) continue ;;
    esac
    path="${task%:*}"
    module="${path##*:}"
    publication="${task##*:publish}"
    publication="${publication%PublicationToMavenLocal}"
    case "$publication" in
        KotlinMultiplatform) ;;
        KotlinMultiplatformDecorated) publish "$module" "$module" "" ;;
        *) target="$(tr '[:upper:]' '[:lower:]' <<< "$publication")"
           publish "$module" "$module-$target" "-$target" ;;
    esac
done
GRADLEW
chmod +x "$compose/gradlew"

# build <home> <target>: one build-compose.sh run; output in $work/out.
build() {
    HOME="$1" PATH="$tree/bin:$PATH" JAVA_HOME="$work/jdk" DXC_COMPOSE_BUILD="$compose" \
        bash "$tree/renderer/scripts/build-compose.sh" --target "$2" > "$work/out" 2>&1
}

# required <home> <file under ~/.m2/repository/org/jetbrains/compose> <dependency> : the version
# that file asks for the dependency at.
required() {
    local file="$1/.m2/repository/org/jetbrains/compose/$2"
    [[ -f "$file" ]] || { echo "absent"; return; }
    DEPENDENCY="$3" perl -0777 -ne '
        my $d = $ENV{DEPENDENCY};
        if (/"module":\s*"\Q$d\E",\s*"version":\s*\{\s*"requires":\s*"([^"]+)"/) { print $1; exit }
        if (m{<artifactId>\Q$d\E</artifactId>\s*<version>([^<]+)</version>}) { print $1; exit }
        print "none";
    ' "$file"
}

expect() {
    local home="$1" file="$2" dependency="$3" want="$4" context="$5" got
    got="$(required "$home" "$file" "$dependency")"
    [[ "$got" == "$want" ]] || fail "$context: $file asks for $dependency at $got, expected $want"
}

foundation="foundation/foundation/$fork_as/foundation-$fork_as"
foundation_macos="foundation/foundation-macosarm64/$fork_as/foundation-macosarm64-$fork_as"
foundation_desktop="foundation/foundation-desktop/$fork_as/foundation-desktop-$fork_as"

expect_macos_repointed() {
    local home="$1" context="$2"
    expect "$home" "$foundation.module" runtime "$upstream_as" "$context"
    expect "$home" "$foundation.module" animation "$upstream_as" "$context"
    expect "$home" "$foundation.module" ui-text "$upstream_as" "$context"
    expect "$home" "$foundation.module" ui "$fork_as" "$context"
    expect "$home" "$foundation.pom" foundation-layout "$upstream_as" "$context"
    expect "$home" "$foundation_macos.module" ui-util "$upstream_as" "$context"
    expect "$home" "$foundation_macos.module" ui "$fork_as" "$context"
    expect "$home" "$foundation_macos.pom" runtime-macosarm64 "$upstream_as" "$context"
    expect "$home" "$foundation_macos.pom" ui-macosarm64 "$fork_as" "$context"
    expect "$home" "ui/ui-macosarm64/$fork_as/ui-macosarm64-$fork_as.module" ui-geometry "$upstream_as" "$context"
}

# A first run into an empty repository.
home="$work/home-once"
mkdir -p "$home"
if build "$home" macosArm64; then
    expect_macos_repointed "$home" "first macOS run"
else
    fail "the first macOS run failed: $(tail -1 "$work/out")"
fi

# The same run again, Gradle up to date: the published files are overwritten in place.
if build "$home" macosArm64; then
    expect_macos_repointed "$home" "second macOS run"
else
    fail "a second macOS run, with Gradle up to date, failed: $(tail -1 "$work/out")"
fi

# Desktop and macOS in one repository: ui-text is published for desktop and not for macOS, and
# the root foundation serves both.
home="$work/home-both"
mkdir -p "$home"
if build "$home" desktop && build "$home" macosArm64; then
    expect "$home" "$foundation_desktop.module" ui-text "$fork_as" "desktop then macOS"
    expect "$home" "$foundation_desktop.pom" ui-text-desktop "$fork_as" "desktop then macOS"
    expect "$home" "$foundation_macos.module" ui-text "$upstream_as" "desktop then macOS"
    expect "$home" "$foundation.module" ui-text "$upstream_as" "desktop then macOS"
    expect "$home" "$foundation.module" ui "$fork_as" "desktop then macOS"
else
    fail "desktop then macOS failed: $(tail -1 "$work/out")"
fi

# require-fork-artifacts.sh, on a repository holding every file the macOS module names, as
# Gradle wrote them: it has to refuse, and name what is missing.
home="$work/home-raw"
mkdir -p "$home"
HOME="$home" bash -c "cd '$compose' && ./gradlew -Pjetbrains.publication.version.COMPOSE=$fork_as \
    :compose:foundation:foundation:publishMacosArm64PublicationToMavenLocal \
    :compose:foundation:foundation:publishKotlinMultiplatformDecoratedPublicationToMavenLocal \
    :compose:ui:ui:publishMacosArm64PublicationToMavenLocal \
    :compose:ui:ui:publishKotlinMultiplatformDecoratedPublicationToMavenLocal"
if HOME="$home" bash "$tree/renderer/scripts/require-fork-artifacts.sh" macos > "$work/out" 2>&1; then
    fail "require-fork-artifacts.sh accepted metadata that names runtime $fork_as, which nobody published"
else
    grep -q "org.jetbrains.compose.runtime:runtime:$fork_as" "$work/out" ||
        fail "require-fork-artifacts.sh refused without naming runtime $fork_as: $(tail -1 "$work/out")"
    grep -q "build-compose.sh --target macosArm64" "$work/out" ||
        fail "require-fork-artifacts.sh did not name the command that repairs it"
fi

# And the repositories build-compose.sh left are accepted.
for checked in "home-once macos" "home-both macos" "home-both desktop"; do
    read -r name module <<< "$checked"
    if ! HOME="$work/$name" bash "$tree/renderer/scripts/require-fork-artifacts.sh" "$module" > "$work/out" 2>&1; then
        # Desktop names skiko-awt too, which this does not publish; only a dependency complaint counts.
        if grep -q ", named by " "$work/out"; then
            fail "require-fork-artifacts.sh $module refused what build-compose.sh left in $name: $(grep ', named by ' "$work/out" | head -1)"
        elif [[ "$module" != desktop ]]; then
            fail "require-fork-artifacts.sh $module refused $name: $(grep -v '^ *$' "$work/out" | sed -n 7p)"
        fi
    fi
done

if [[ "$failures" -eq 0 ]]; then echo "  ok"; else exit 1; fi
