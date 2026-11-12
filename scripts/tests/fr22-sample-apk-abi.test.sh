#!/usr/bin/env bash
# Usage: ./scripts/tests/fr22-sample-apk-abi.test.sh
#
# A sample APK named arm64-v8a carries its host library for arm64-v8a.
#
# Every APK the Samples test published as <sample>-android-arm64-v8a.apk held libmain.so
# only at lib/x86_64/, because dx builds for the machine it runs on when no target is
# named, and the runner is x86-64. On an arm64 phone the app died at launch, unable to
# find libmain.so. Nothing failed on the way there: the build succeeded and the file name
# said arm64.
#
# This runs scripts/build-sample-apks.sh against a fake dx and fake samples in a scratch
# copy of the repository, so it builds nothing. It checks that dx is asked for the arm64
# target, and that an APK without lib/arm64-v8a/libmain.so fails the run instead of being
# published under the arm64 name.
set -uo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

failures=0
fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
    shift
    local line
    for line in "$@"; do printf '        %s\n' "$line" >&2; done
}

for tool in zip unzip; do
    command -v "$tool" >/dev/null || {
        echo "FAIL  $tool is not on PATH; this test makes and reads fake APKs with it" >&2
        exit 1
    }
done

mkdir -p "$repo/.scratch"
work="$(mktemp -d "$repo/.scratch/fr22-sample-apk-abi-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# A fake dx. It records its arguments and leaves an APK where dx would. What the APK holds
# is chosen by the sample's name: `phone` gets the arm64 host library, `emulator` gets the
# x86_64 one that the published APKs had, beside the arm64 graphics library they also had.
mkdir -p "$work/bin"
cat > "$work/bin/dx" <<'EOF'
#!/usr/bin/env bash
set -eu
echo "$*" >> "$DX_LOG"
package=
while [[ $# -gt 0 ]]; do
    case "$1" in
        --package) package="$2"; shift 2 ;;
        *) shift ;;
    esac
done
out="target/dx/$package/release/android/app/app/build/outputs/apk/release"
mkdir -p "$out" "$DX_STAGE/$package"
cd "$DX_STAGE/$package"
case "$package" in
    sample-phone) mkdir -p lib/arm64-v8a; echo elf > lib/arm64-v8a/libmain.so ;;
    sample-emulator)
        mkdir -p lib/x86_64 lib/arm64-v8a
        echo elf > lib/x86_64/libmain.so
        echo elf > lib/arm64-v8a/libandroidx.graphics.path.so
        ;;
esac
echo manifest > AndroidManifest.xml
zip -qr "$OLDPWD/$out/app-release.apk" .
EOF
chmod +x "$work/bin/dx"

# A scratch repository holding the script and the samples a run builds.
make_repo() {
    local root="$1"
    shift
    mkdir -p "$root/scripts"
    cp "$repo/scripts/build-sample-apks.sh" "$root/scripts/"
    local sample
    for sample in "$@"; do
        mkdir -p "$root/samples/$sample/src"
        printf '[package]\nname = "sample-%s"\n' "$sample" > "$root/samples/$sample/Cargo.toml"
        echo 'fn main() {}' > "$root/samples/$sample/src/main.rs"
    done
}

run_script() {
    local root="$1"
    env -u JAVA_HOME PATH="$work/bin:$PATH" DX_LOG="$root/dx.log" DX_STAGE="$root/stage" \
        bash "$root/scripts/build-sample-apks.sh" "$root/out" > "$root/output.txt" 2>&1
}

# fr22_apk_carries_the_arm64_host_library: dx is asked for the arm64 target, and the APK
# it makes is published under the arm64 name.
good="$work/good"
make_repo "$good" phone
if ! run_script "$good"; then
    fail "fr22_apk_carries_the_arm64_host_library: a run with an arm64 APK failed" \
        "$(cat "$good/output.txt")"
fi
if ! grep -q -- '--target aarch64-linux-android' "$good/dx.log" 2>/dev/null; then
    fail "fr22_apk_carries_the_arm64_host_library: dx was not asked for aarch64-linux-android" \
        "dx was called with: $(cat "$good/dx.log" 2>/dev/null)" \
        "With no target dx builds for the machine it runs on, which on CI is x86-64."
fi
if [[ ! -f "$good/out/phone-android-arm64-v8a.apk" ]]; then
    fail "fr22_apk_carries_the_arm64_host_library: no phone-android-arm64-v8a.apk was published" \
        "published: $(ls "$good/out" 2>/dev/null | tr '\n' ' ')"
elif ! unzip -Z1 "$good/out/phone-android-arm64-v8a.apk" | grep -qx 'lib/arm64-v8a/libmain.so'; then
    fail "fr22_apk_carries_the_arm64_host_library: the published APK lost its arm64 libmain.so"
fi

# fr22_apk_without_the_arm64_host_library_fails: an APK whose host library is x86_64 only
# fails the run, names the sample, and is not published under the arm64 name. The good
# sample beside it still is, so one wrong APK does not hide the rest.
bad="$work/bad"
make_repo "$bad" emulator phone
if run_script "$bad"; then
    fail "fr22_apk_without_the_arm64_host_library_fails: a run with an x86_64 only APK passed" \
        "$(cat "$bad/output.txt")"
fi
if [[ -e "$bad/out/emulator-android-arm64-v8a.apk" ]]; then
    fail "fr22_apk_without_the_arm64_host_library_fails: the x86_64 APK was published as arm64-v8a"
fi
if ! grep -q 'emulator' "$bad/output.txt" || ! grep -q 'lib/arm64-v8a/libmain.so' "$bad/output.txt"; then
    fail "fr22_apk_without_the_arm64_host_library_fails: the failure does not name the sample and the missing library" \
        "$(cat "$bad/output.txt")"
fi
if [[ ! -f "$bad/out/phone-android-arm64-v8a.apk" ]]; then
    fail "fr22_apk_without_the_arm64_host_library_fails: the good sample beside it was not published"
fi

if [[ $failures -gt 0 ]]; then
    echo "$failures failure(s)" >&2
    exit 1
fi
echo "ok  the sample APKs carry the arm64-v8a host library"
