#!/usr/bin/env bash
# The Kotlin/Native Linux and Windows renderers compile the desktop sources through the
# symlinks under renderer/<module>/src/shared/. Kotlin/Native has no Java class library, so
# a shared file may name a `java.*` class only when the module declares it itself, in one of
# its shims (renderer/<module>/src/*Shim*.kt, `package java.lang` and the like). A reference
# to anything else compiles on the JVM and fails only when the native renderer is built,
# which on a developer's Mac is never; `java.util.WeakHashMap` in WindowEvents.kt broke the
# Linux and Windows renderers that way (#854).
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

red=0
fail() { echo "FAIL  $1"; red=1; }
pass() { echo "ok    $1"; }

for module in linux windows; do
  dir="$repo_root/renderer/$module/src"
  shared="$dir/shared"
  [[ -d "$shared" ]] || { fail "$module: renderer/$module/src/shared exists"; continue; }

  # What the module's shims declare, as package.Name lines.
  declared="$(
    for shim in "$dir"/*.kt; do
      package="$(sed -n 's/^package \(java[a-z.]*\)$/\1/p' "$shim")"
      [[ -n "$package" ]] || continue
      sed -nE 's/^(public |internal )?(abstract |open |data )?(class|object|interface|typealias) ([A-Za-z_][A-Za-z0-9_]*).*/\4/p' "$shim" |
        sed "s/^/$package./"
    done | sort -u
  )"

  bad=""
  while IFS= read -r ref; do
    [[ -n "$ref" ]] || continue
    grep -qxF "$ref" <<<"$declared" || bad+="$ref "
  done < <(
    # Comments and string literals name Java properties (`"java.vm.vendor"`) and history;
    # only code is checked.
    find -L "$shared" -name '*.kt' -exec sed -E 's://.*$::; s/"[^"]*"//g' {} + |
      grep -oE '\bjavax?(\.[a-z][a-z0-9_]*)+\.[A-Z][A-Za-z0-9_]*' | sort -u
  )

  if [[ -z "$bad" ]]; then
    pass "$module: shared sources name only Java classes the module's shims declare"
  else
    fail "$module: shared sources name Java classes Kotlin/Native does not have: $bad"
  fi
done

exit $red
