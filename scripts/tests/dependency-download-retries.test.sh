#!/usr/bin/env bash
# A Maven repository that answers "not found" for a moment fails a whole CI job: run
# 44692946847 lost the linux renderer test to "Could not find
# com.google.protobuf:protobuf-java:3.25.5" in the fork's Compose build, and a rerun passed.
# renderer/scripts/retry-resolution.sh runs a Gradle or Amper build again when, and only when,
# it failed resolving or downloading a dependency. This pins how it behaves, with a stub build
# in place of Gradle, and that every dependency-resolving build CI runs goes through it: the
# fork's Gradle publish (and the Windows skiko build) in build-compose.sh, and the Amper
# wasmJs builds in test.yml.
set -uo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
retry="$repo/renderer/scripts/retry-resolution.sh"
failures=0
check() { if [ "$1" -eq 0 ]; then echo "ok   - $2"; else echo "FAIL - $2"; failures=$((failures + 1)); fi; }

mkdir -p "$repo/.scratch"
work="$(mktemp -d "$repo/.scratch/dependency-download-retries-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# A stub build: fails the first $1 runs with the message $2, then succeeds. Counts its runs.
cat > "$work/stub-build" <<'SH'
#!/usr/bin/env bash
count_file="$STUB_DIR/count"
n=$(( $(cat "$count_file" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$count_file"
if [ "$n" -le "$1" ]; then
    echo "FAILURE: Build failed with an exception." >&2
    echo "$2" >&2
    exit 1
fi
echo "BUILD SUCCESSFUL"
SH
chmod +x "$work/stub-build"

# A sleep that only writes down how long it was asked to wait, so the backoff is checked
# without waiting it out.
mkdir -p "$work/bin"
printf '#!/usr/bin/env bash\necho "$1" >> "$STUB_DIR/waits"\n' > "$work/bin/sleep"
chmod +x "$work/bin/sleep"

run() { # fails_before_success message -> sets status, runs and waits
    rm -f "$work/count" "$work/waits"
    PATH="$work/bin:$PATH" STUB_DIR="$work" DXC_RETRY_LOG="$work/retry.log" \
        "$retry" "$work/stub-build" "$1" "$2" >"$work/out" 2>&1
    status=$?
    runs=$(cat "$work/count" 2>/dev/null || echo 0)
    waits=$(paste -sd' ' "$work/waits" 2>/dev/null || true)
}

test -x "$retry"; check $? "renderer/scripts/retry-resolution.sh exists and is executable"

run 1 "> Could not resolve all artifacts for configuration 'classpath'.
   > Could not find com.google.protobuf:protobuf-java:3.25.5."
[ "$status" -eq 0 ] && [ "$runs" -eq 2 ]
check $? "a build that fails once resolving a dependency is run again and passes (status $status, runs $runs)"

run 1 "Plugin [id: 'org.jetbrains.kotlin.jvm'] was not found in any of the following sources:"
[ "$status" -eq 0 ] && [ "$runs" -eq 2 ]
check $? "a plugin that could not be resolved is retried (status $status, runs $runs)"

run 1 "Could not GET 'https://repo.maven.apache.org/maven2/x.pom'. Received status code 502"
[ "$status" -eq 0 ] && [ "$runs" -eq 2 ]
check $? "a failed download is retried (status $status, runs $runs)"

run 1 "Unable to resolve dependency org.jetbrains.skiko:skiko:0.9"
[ "$status" -eq 0 ] && [ "$runs" -eq 2 ]
check $? "an Amper dependency that could not be resolved is retried (status $status, runs $runs)"

run 9 "> Could not find com.google.protobuf:protobuf-java:3.25.5."
[ "$status" -ne 0 ] && [ "$runs" -eq 3 ]
check $? "a resolution failure that persists stops after three attempts and fails (status $status, runs $runs)"
[ "$waits" = "15 30" ]
check $? "a resolution failure waits 15s, then 30s (waited: $waits)"

run 9 "> Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom'. Received status code 429 from server: Too Many Requests"
[ "$status" -ne 0 ] && [ "$runs" -eq 4 ] && [ "$waits" = "60 120 240" ]
check $? "a 429 from the repository is retried four times in all, waiting 60s, 120s, 240s (status $status, runs $runs, waited: $waits)"

run 2 "> Could not GET 'https://repo.maven.apache.org/maven2/x.pom'. Received status code 429 from server: Too Many Requests"
[ "$status" -eq 0 ] && [ "$runs" -eq 3 ]
check $? "a rate limit that lifts lets the build pass (status $status, runs $runs)"

run 1 "e: file:///x/Main.kt:3:5 Unresolved reference 'foo'."
[ "$status" -ne 0 ] && [ "$runs" -eq 1 ]
check $? "a compile error is not retried (status $status, runs $runs)"

run 0 ""
[ "$status" -eq 0 ] && [ "$runs" -eq 1 ] && grep -q "BUILD SUCCESSFUL" "$work/out"
check $? "a passing build runs once and its output still reaches the log (status $status, runs $runs)"

run 2 "WARN  https://repo1.maven.org/maven2: Failed to download https://repo1.maven.org/maven2/x.pom.sha512: java.io.IOException: Unexpected response code for https://repo1.maven.org/maven2/x.pom.sha512. Expected: 200, actual: 429
ERROR: Unable to resolve dependency org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10"
[ "$status" -eq 0 ] && [ "$runs" -eq 3 ] && [ "$waits" = "60 120" ]
check $? "an Amper 429 waits like a Gradle one (status $status, runs $runs, waited: $waits)"

run 2 "curl: (22) The requested URL returned error: 429"
[ "$status" -eq 0 ] && [ "$runs" -eq 3 ] && [ "$waits" = "60 120" ]
check $? "a curl download answered 429 waits like a Gradle one (status $status, runs $runs, waited: $waits)"

run 1 "curl: (22) The requested URL returned error: 503"
[ "$status" -eq 0 ] && [ "$runs" -eq 2 ]
check $? "a curl download answered 5xx is retried (status $status, runs $runs)"

run 1 "curl: (22) The requested URL returned error: 404"
[ "$status" -ne 0 ] && [ "$runs" -eq 1 ]
check $? "a curl download answered 404 is not retried (status $status, runs $runs)"

# The Maven Central mirror: on a GitHub runner build-compose.sh installs the init script into
# the Gradle user home before it builds anything, and installs nothing elsewhere. The bogus
# target stops it right after that, so nothing is fetched or built.
mirror_init="$repo/renderer/scripts/maven-central-mirror.gradle"
grep -q "@MIRROR@" "$mirror_init" && grep -q "repo.maven.apache.org" "$mirror_init"
check $? "maven-central-mirror.gradle points Maven Central at @MIRROR@ and keeps Central behind it"
# Where Central goes: Gradle gives up on a 429 rather than asking the next repository, so
# Central behind the mirror has to come after every other repository. Run 45488752618 lost
# the fork build to it: the plugin portal's develocity-gradle-plugin and Google's
# com.android.settings.gradle.plugin are not on the mirror, and Central, asked before the
# portal and Google, answered 429. A real Gradle applies the init script to a probe and prints
# each repository list; nothing is resolved. Gradle comes from DXC_GRADLE or PATH.
gradle_bin="${DXC_GRADLE:-$(command -v gradle || true)}"
if [ -n "$gradle_bin" ]; then
    probe="$work/mirror-probe"
    mkdir -p "$probe"
    sed "s#@MIRROR@#https://mirror.example/m2/#" "$mirror_init" > "$work/mirror-init.gradle"
    cat > "$probe/settings.gradle" <<'GRADLE'
pluginManagement { repositories { mavenCentral(); google(); gradlePluginPortal() } }
buildscript { repositories { mavenLocal(); mavenCentral(); google(); gradlePluginPortal() } }
rootProject.name = 'mirror-probe'
def show(String label, repos) {
    println(label + ': ' + repos.collect { it instanceof MavenArtifactRepository ? it.url.toString().replaceAll('/+$', '') : it.name }.join(' '))
}
show('pluginManagement', settings.pluginManagement.repositories)
show('settings-buildscript', settings.buildscript.repositories)
gradle.beforeProject { p ->
    p.repositories { mavenLocal(); mavenCentral(); google(); gradlePluginPortal(); maven { url = 'https://repo1.maven.org/maven2/' } }
}
gradle.afterProject { p -> show('project', p.repositories) }
GRADLE
    "$gradle_bin" -q --offline --no-daemon --gradle-user-home "$work/probe-home" \
        -I "$work/mirror-init.gradle" -p "$probe" help > "$work/probe.out" 2>&1
    probe_status=$?
    line() { grep "^$1: " "$work/probe.out" | sed "s/^$1: //" | sed 's#file:[^ ]*#local#g'; }
    central=https://repo.maven.apache.org/maven2
    m=https://mirror.example/m2
    google=https://dl.google.com/dl/android/maven2
    portal=https://plugins.gradle.org/m2
    [ "$probe_status" -eq 0 ] && [ "$(line pluginManagement)" = "$m $google $portal $central" ]
    check $? "plugin repositories read the mirror first and Central last ($(line pluginManagement); status $probe_status)"
    [ "$(line settings-buildscript)" = "local $m $google $portal $central" ]
    check $? "the settings classpath asks Google and the plugin portal before Central ($(line settings-buildscript))"
    [ "$(line project)" = "local $m $google $portal $m $central" ]
    check $? "a project's repositories keep their order with Central once, at the end ($(line project))"
    [ "$probe_status" -eq 0 ] || sed 's/^/    /' "$work/probe.out"
else
    echo "skip - no Gradle on PATH or in DXC_GRADLE, so the repository order is not run"
fi

compose_at() { # env... -> runs build-compose.sh up to its argument check
    env -u GITHUB_ACTIONS -u DXC_MAVEN_CENTRAL_MIRROR "$@" GRADLE_USER_HOME="$work/gh" \
        DXC_COMPOSE_BUILD="$work/compose-build" \
        "$repo/renderer/scripts/build-compose.sh" --bogus >/dev/null 2>&1
}
rm -rf "$work/gh"; compose_at GITHUB_ACTIONS=true
installed="$work/gh/init.d/dxc-maven-central-mirror.gradle"
grep -q "def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'" "$installed" 2>/dev/null
check $? "on a GitHub runner build-compose.sh installs the Google Maven Central mirror into init.d"
awk '/MAVEN_CENTRAL_MIRROR" != off/{on=1} on && /export COMPOSE_MAVEN_MIRROR="\$MAVEN_CENTRAL_MIRROR"/{found=1} on && /^fi/{on=0} END{exit !found}' "$repo/renderer/scripts/build-compose.sh"
check $? "build-compose.sh names the mirror to the fork's own repository lists (COMPOSE_MAVEN_MIRROR) only when it installs it"
rm -rf "$work/gh"; compose_at GITHUB_ACTIONS=true DXC_MAVEN_CENTRAL_MIRROR=https://mirror.example/m2/
grep -q "def mirror = 'https://mirror.example/m2/'" "$installed" 2>/dev/null
check $? "DXC_MAVEN_CENTRAL_MIRROR names another mirror"
rm -rf "$work/gh"; compose_at GITHUB_ACTIONS=true DXC_MAVEN_CENTRAL_MIRROR=off
[ ! -e "$installed" ]
check $? "DXC_MAVEN_CENTRAL_MIRROR=off installs no mirror"
rm -rf "$work/gh"; compose_at
[ ! -e "$installed" ]
check $? "off CI build-compose.sh leaves the Gradle user home alone"

# The Gradle downloads of the fork build are kept between runs in every job that builds the
# fork's Compose without setup-gradle (which keeps the Gradle home itself), restored from an
# older key when build-compose.sh changed, and saved only off pull requests.
for wf in test-kn-desktop-renderer.yml static-renderer.yml windows-static.yml test-windows-resize-metrics.yml; do
    f="$repo/.github/workflows/$wf"
    n_build=$(grep -c 'run: ./renderer/scripts/build-compose.sh' "$f")
    n_restore=$(grep -c 'id: gradle-downloads' "$f")
    [ "$n_restore" -ge 1 ] && [ "$n_restore" -le "$n_build" ] &&
        grep -q 'key: gradle-downloads-.*hashFiles(.renderer/scripts/build-compose.sh.' "$f" &&
        grep -q 'gradle-downloads-.*-$' "$f" &&
        grep -q '~/.gradle/caches/modules-2' "$f" &&
        grep -q 'key: ${{ steps.gradle-downloads.outputs.cache-primary-key }}' "$f"
    check $? "$wf keeps the fork build's Gradle downloads between runs"
done

# Wiring: every dependency-resolving build CI runs goes through the wrapper.
build_compose="$repo/renderer/scripts/build-compose.sh"
grep -Eq '^[[:space:]]*"\$RETRY"[[:space:]]+"\$publish"' "$build_compose"
check $? "build-compose.sh runs the fork's Gradle publish through the retry wrapper"
grep -Eq '^[[:space:]]*"\$RETRY"[[:space:]]+bash[[:space:]]+"\$skiko_script"' "$build_compose"
check $? "build-compose.sh runs the Windows skiko Gradle build through the retry wrapper"
! grep -Eq '^[[:space:]]*("\$publish"|bash[[:space:]]+"\$skiko_script")' "$build_compose"
check $? "build-compose.sh has no unwrapped Gradle invocation left"

test_yml="$repo/.github/workflows/test.yml"
grep -Eq 'run: \./scripts/retry-resolution\.sh \./kotlin task :web:buildWasmJsAppWasmJsDebug' "$test_yml"
check $? "test.yml builds the wasmJs app bundle through the retry wrapper"
grep -Eq 'run: \./scripts/retry-resolution\.sh \./kotlin build -p \$\{\{ matrix\.platform \}\}' "$test_yml"
check $? "test.yml runs the Amper build (wasmJs and the other platforms) through the retry wrapper"
grep -Eq '\./scripts/retry-resolution\.sh \./kotlin task :web:buildWasmJsAppWasmJsDebug' "$repo/scripts/build-sample-pages.sh"
check $? "build-sample-pages.sh builds the wasm module through the retry wrapper"
grep -Eq 'run: \./scripts/retry-resolution\.sh \./kotlin test -p \$\{\{ matrix\.platform \}\}' "$test_yml"
check $? "test.yml runs the Amper tests through the retry wrapper"
grep -q 'DXC_RETRY_LOG: ${{ runner.temp }}/' "$test_yml"
check $? "test.yml keeps the retry log in the runner's temp directory"

# Every Amper module declares the Google Maven Central mirror ahead of the implicit Central fallback.
for mod in "$repo"/renderer/*/module.yaml; do
    grep -q 'https://maven-central.storage-download.googleapis.com/maven2/' "$mod"
    check $? "$(basename "$(dirname "$mod")")/module.yaml reads Maven Central through Google's mirror"
done

# Every ./kotlin test step in CI workflows runs through retry-resolution.sh.
for wf in static-renderer.yml test-kn-desktop-renderer.yml test-native-renderer.yml test.yml; do
    ! grep -Eq 'run: (xvfb-run [^#]* )?\./kotlin test' "$repo/.github/workflows/$wf"
    check $? "$wf has no unwrapped ./kotlin test invocation"
done

if [ "$failures" -ne 0 ]; then echo "$failures check(s) failed"; exit 1; fi
echo "ok    transient dependency downloads are retried in the fork Compose build and Amper builds"
