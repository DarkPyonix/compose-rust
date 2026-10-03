#!/usr/bin/env bash
# Usage: ./scripts/tests/compose-coordinates.test.sh
#
# Which Compose the renderer and the design systems are built against.
#
# They draw with thisisthepy/compose-multiplatform-core-extended, which publishes under its
# own coordinates (org.thisisthepy.compose.* at <upstream version>-ext.<N>) and which
# renderer/scripts/build-compose.sh builds into the local Maven repository.
# Three ways that goes wrong without any build failing where the mistake is:
#
#   - a module asks for JetBrains' Compose again, through the toolchain's `$compose.*`
#     catalog, through `compose: enabled` (which adds JetBrains' runtime to the module behind
#     the build's back), or by coordinate. Both Composes then end up in one program, the same
#     classes twice under two groups that nothing reconciles;
#   - a version here stops matching what build-compose.sh publishes, and the build looks for
#     an artifact nobody built;
#   - a module draws with Compose without the compiler plugin, or without the local
#     repository the fork's Compose is in.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
renderer="$repo_root/renderer"
design="$repo_root/design-systems"
script="$renderer/scripts/build-compose.sh"

failures=0
fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
    shift
    local line
    for line in "$@"; do printf '        %s\n' "$line" >&2; done
}

echo "compose coordinates"

[[ -f "$script" ]] || { fail "no $script"; exit 1; }
assignment() { sed -n "s/^$1=\"\\([^\"]*\\)\"\$/\\1/p" "$script"; }
group="$(assignment GROUP)"
compose_version="$(assignment PUBLISHED_AS)"
material3_version="$(assignment MATERIAL3_PUBLISHED_AS)"
lifecycle_version="$(assignment LIFECYCLE_PUBLISHED_AS)"

[[ "$group" == "org.thisisthepy.compose" ]] ||
    fail "build-compose.sh publishes under '$group', not the fork's org.thisisthepy.compose"
for pair in "PUBLISHED_AS:$compose_version" "MATERIAL3_PUBLISHED_AS:$material3_version" \
            "LIFECYCLE_PUBLISHED_AS:$lifecycle_version"; do
    name="${pair%%:*}"
    value="${pair#*:}"
    [[ "$value" =~ -ext\.[0-9]+(-dev)?$ ]] ||
        fail "build-compose.sh's $name is '$value', which is not <upstream version>-ext.<N>" \
            "The fork publishes nothing under a version JetBrains could publish too."
done

modules=()
while IFS= read -r file; do modules+=("$file"); done < <(
    find "$renderer" "$design" -mindepth 2 -maxdepth 2 -name module.yaml | sort)
[[ ${#modules[@]} -gt 0 ]] || { fail "no module.yaml found"; exit 1; }

for file in "${modules[@]}"; do
    name="${file#"$repo_root"/}"
    # Comments say what was replaced and why; only what the toolchain reads counts.
    body="$(sed 's/[[:space:]]#.*$//; /^[[:space:]]*#/d' "$file")"

    grep -Eq '\$compose\.' <<< "$body" &&
        fail "$name uses the toolchain's \$compose catalog, which names JetBrains' Compose" \
            "Use \$libs.compose.* from libs.versions.toml."
    grep -Eq '^[[:space:]]+compose:[[:space:]]*enabled' <<< "$body" &&
        fail "$name sets compose: enabled, which adds org.jetbrains.compose.runtime to it" \
            "Apply ../compose.module-template.yaml for the compiler plugin instead."
    if awk '/^[[:space:]]+compose:[[:space:]]*$/ { inside = 1; next }
            inside && /^[[:space:]]+enabled:[[:space:]]*true/ { found = 1 }
            /^[^[:space:]]/ { inside = 0 }
            END { exit !found }' <<< "$body"; then
        fail "$name sets compose.enabled, which adds org.jetbrains.compose.runtime to it" \
            "Apply ../compose.module-template.yaml for the compiler plugin instead."
    fi
    grep -Eq 'org\.jetbrains\.(compose|androidx)\.' <<< "$body" &&
        fail "$name names a JetBrains Compose coordinate" \
            "The fork's are org.thisisthepy.compose.*; JetBrains' next to them is a second copy."

    # Anything that draws with Compose needs the compiler plugin and the local repository.
    if grep -Eq '\$libs\.compose\.|org\.thisisthepy\.compose\.' <<< "$body"; then
        grep -Fq -- '- ../compose.module-template.yaml' <<< "$body" ||
            fail "$name depends on Compose and does not apply ../compose.module-template.yaml" \
                "Without it nothing compiles @Composable."
        grep -Eq '^[[:space:]]+- mavenLocal' <<< "$body" ||
            fail "$name depends on Compose and does not read mavenLocal" \
                "The fork's Compose is in the local repository and nowhere else yet."
    fi
done

# The versions every project asks for are the ones the script publishes.
for toml in "$renderer/libs.versions.toml" "$design/libs.versions.toml"; do
    name="${toml#"$repo_root"/}"
    [[ -f "$toml" ]] || { fail "no $name"; continue; }
    version_of() { sed -n "s/^$1[[:space:]]*=[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" "$toml"; }
    [[ "$(version_of compose)" == "$compose_version" ]] ||
        fail "$name asks for Compose $(version_of compose), build-compose.sh publishes $compose_version"
    [[ "$(version_of compose-material3)" == "$material3_version" ]] ||
        fail "$name asks for Material 3 $(version_of compose-material3), build-compose.sh publishes $material3_version"
    [[ "$(version_of compose-lifecycle)" == "$lifecycle_version" ]] ||
        fail "$name asks for lifecycle $(version_of compose-lifecycle), build-compose.sh publishes $lifecycle_version"
    grep -Eq 'org\.jetbrains\.(compose|androidx)\.' "$toml" &&
        fail "$name names a JetBrains Compose coordinate"
    while IFS= read -r module; do
        [[ "$module" == "$group".* ]] ||
            fail "$name has a Compose library outside $group: $module"
    done < <(sed -n 's/^compose-[a-z0-9-]*[[:space:]]*=.*module[[:space:]]*=[[:space:]]*"\([^":]*\):.*/\1/p' "$toml" |
        grep -v '^org\.jetbrains\.kotlin$')
done
cmp -s "$renderer/compose.module-template.yaml" "$design/compose.module-template.yaml" ||
    fail "the two projects' compose.module-template.yaml differ" \
        "They apply the same compiler plugin; a difference is one project compiling Compose differently."

# The Linux module names each target by coordinate; each has to be one the script publishes for
# linuxX64, at the version it publishes.
linux_publications="$(sed -n '/^        linuxX64)/,/;;/p' "$script")"
while IFS= read -r coordinate; do
    module_group="${coordinate%%:*}"
    rest="${coordinate#*:}"
    artifact="${rest%%:*}"
    version="${rest#*:}"
    artifact="${artifact%-linuxx64}"
    gradle_path="compose:${module_group#"$group".}:$artifact"
    gradle_path="${gradle_path//./:}"
    grep -Eq "^[[:space:]]*${gradle_path}[[:space:]]*\$" <<< "$linux_publications" ||
        fail "linux/module.yaml asks for $coordinate, and build-compose.sh does not publish $gradle_path for linuxX64"
    case "$artifact" in
        material3) expected="$material3_version" ;;
        *) expected="$compose_version" ;;
    esac
    [[ "$version" == "$expected" ]] ||
        fail "linux/module.yaml asks for $coordinate, and build-compose.sh publishes $expected"
done < <(sed -n 's/^[[:space:]]*- \(org\.thisisthepy\.compose\.[^[:space:]]*\)[[:space:]]*$/\1/p' "$renderer/linux/module.yaml")

if [[ $failures -eq 0 ]]; then
    echo "  ok: ${#modules[@]} modules ask for the fork's Compose at the versions build-compose.sh publishes"
else
    echo "  $failures failed" >&2
    exit 1
fi
