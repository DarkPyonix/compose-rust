#!/usr/bin/env bash
# Usage: experiments/self-update/rehearse.sh
#
# Rehearses both macOS channels end to end on one machine, with no Apple identity:
#
#   Sparkle: builds the demo application at 1.0.0 and 2.0.0, packages both for the
#   sparkle channel (signed ad hoc), publishes 2.0.0 into an appcast served from
#   127.0.0.1, installs 1.0.0, runs it, and waits for it to replace itself with 2.0.0.
#   Twice: once installing as soon as the update is ready (and relaunching), once
#   installing when the application quits, which is Sparkle's default.
#
#   Mac App Store: builds the same application without the updater, packages it for the
#   app-store channel (sandboxed), checks nothing of Sparkle is in it, makes the .pkg, and
#   runs the sandboxed application.
#
# Everything goes under $DEMO_WORK (default .scratch/self-update in this repository):
# builds, bundles, the served site, the private key, and evidence/ with logs, plists,
# signatures and screenshots. evidence/result.txt says what passed.
#
# Needs Xcode's command line tools, Python 3.11 or newer, and Rust. Sparkle's release
# archive is downloaded into $DEMO_WORK and checked against a pinned SHA-256, unless
# SPARKLE_DIR names an unpacked one. Running the application writes what any macOS
# application writes: Sparkle's preferences and download cache under ~/Library for the
# demo's bundle identifier, and the sandbox container of the store build. The last step
# lists them.

set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="${DEMO_WORK:-$repo/.scratch/self-update}"
port="${DEMO_PORT:-8765}"
package="$repo/tools/packager/package-macos"
app_dir="$repo/experiments/self-update/app"
evidence="$work/evidence"
name="Self Update Demo"
identifier="dev.darkpyonix.dioxus.compose.experiments.selfupdate"
executable="self-update-demo"
archive_name="SelfUpdateDemo-2.0.0.zip"

sparkle_version="2.10.0"
sparkle_sha256="c2bf58aa8387266ac179357b1415d6f2635f044da8be41042af32425dae6da0c"

rm -rf "$work/build" "$work"/dist-* "$work"/install-* "$work/site" "$work/generated" "$evidence"
mkdir -p "$work/build" "$work/site" "$evidence"
result="$evidence/result.txt"
: >"$result"

step() { printf '\n== %s\n' "$*"; }
pass() { echo "PASS  $*" | tee -a "$result"; }
fail() {
    echo "FAIL  $*" | tee -a "$result"
    diagnose
    exit 1
}
# What a failure leaves to read: which demo processes are alive, and any crash reports.
diagnose() {
    {
        echo "== processes"
        ps -axo pid,ppid,stat,etime,command | grep -i "self.update\|Autoupdate\|Updater" | grep -v grep || true
        echo "== crash reports"
        ls -la "$HOME/Library/Logs/DiagnosticReports" 2>/dev/null | grep -i "self-update\|Autoupdate\|Updater" || true
    } >"$evidence/diagnosis.txt" 2>&1
    for report in "$HOME"/Library/Logs/DiagnosticReports/*self-update*; do
        [[ -f "$report" ]] && cp "$report" "$evidence/"
    done
    return 0
}
shot() {
    # The whole screen. A runner without screen recording permission gets a black or
    # missing picture, which is noted rather than fatal: the logs are the evidence and the
    # picture is for a person.
    sleep "${2:-0}"
    screencapture -x "$evidence/$1.png" 2>>"$evidence/screencapture.log" ||
        echo "screencapture failed for $1" >>"$evidence/screencapture.log"
}

pids=()
cleanup() {
    for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
    pkill -f "$work/install-" 2>/dev/null || true
    pkill -f "$work/dist-store" 2>/dev/null || true
}
trap cleanup EXIT

# Info.plist's CFBundleVersion of an installed app, or nothing while it is being replaced.
installed_build() {
    /usr/libexec/PlistBuddy -c "Print :CFBundleVersion" "$1/Contents/Info.plist" 2>/dev/null || true
}

wait_for() {
    local seconds="$1"
    shift
    local waited=0
    until "$@"; do
        ((waited >= seconds)) && return 1
        sleep 1
        waited=$((waited + 1))
    done
}

step "Sparkle $sparkle_version"
sparkle="${SPARKLE_DIR:-$work/sparkle}"
if [[ ! -x "$sparkle/bin/sign_update" ]]; then
    mkdir -p "$sparkle"
    curl -fsSL -o "$sparkle/Sparkle.tar.xz" \
        "https://github.com/sparkle-project/Sparkle/releases/download/$sparkle_version/Sparkle-$sparkle_version.tar.xz"
    echo "$sparkle_sha256  $sparkle/Sparkle.tar.xz" | shasum -a 256 -c -
    tar -xf "$sparkle/Sparkle.tar.xz" -C "$sparkle"
fi

step "EdDSA key (kept in $work/keys, never in the repository)"
key="$work/keys/sparkle.key"
[[ -f "$key" ]] || "$package" keygen --out "$key"
public_key="$("$package" public-key --key "$key")"
echo "SUPublicEDKey $public_key"

# Plain http to 127.0.0.1 is how an update is rehearsed on one machine. App Transport
# Security refuses it unless the application allows local networking.
cat >"$work/ats.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsLocalNetworking</key><true/></dict>
</dict></plist>
PLIST

build() {
    local version="$1" out="$2"
    shift 2
    DEMO_VERSION="$version" cargo build --release --manifest-path "$app_dir/Cargo.toml" "$@"
    cp "$app_dir/target/release/$executable" "$out"
}

step "Build and package 1.0.0 and 2.0.0 for the sparkle channel"
for major in 1 2; do
    build "$major.0.0" "$work/build/$executable-$major"
    "$package" app --channel sparkle --manifest-dir "$app_dir" \
        --executable "$work/build/$executable-$major" \
        --version "$major.0.0" --build "$major" --out "$work/dist-$major" \
        --feed-url "http://127.0.0.1:$port/appcast.xml" --ed-key-file "$key" \
        --sparkle-framework "$sparkle/Sparkle.framework" --automatically-update \
        --plist-file "$work/ats.plist" | tee "$evidence/package-$major.txt"
    plutil -p "$work/dist-$major/$name.app/Contents/Info.plist" >"$evidence/Info-$major.plist.txt"
    codesign -dvv "$work/dist-$major/$name.app" >"$evidence/codesign-$major.txt" 2>&1
    otool -L "$work/dist-$major/$name.app/Contents/MacOS/$executable" >"$evidence/otool-$major.txt"
    # Kept whole (ditto keeps the framework's links, which an artifact upload would not),
    # so a failure here can be reproduced by running the very bundle elsewhere.
    mkdir -p "$work/bundles"
    ditto -c -k --keepParent "$work/dist-$major/$name.app" "$work/bundles/sparkle-$major.0.0.zip"
done
pass "1.0.0 and 2.0.0 packaged, signed and verified for the sparkle channel"

step "Publish 2.0.0"
"$package" archive --app "$work/dist-2/$name.app" --out "$work/site/$archive_name"
"$package" appcast --appcast "$work/site/appcast.xml" --app "$work/dist-2/$name.app" \
    --archive "$work/site/$archive_name" --url "http://127.0.0.1:$port/$archive_name" \
    --ed-key-file "$key" --sign-update "$sparkle/bin/sign_update"
cp "$work/site/appcast.xml" "$evidence/appcast.xml"
signature="$(python3 -c '
import sys, xml.etree.ElementTree as ET
S = "{http://www.andymatuschak.org/xml-namespaces/sparkle}"
print(ET.parse(sys.argv[1]).find("channel/item/enclosure").get(S + "edSignature"))
' "$work/site/appcast.xml")"
"$sparkle/bin/sign_update" --ed-key-file "$key" --verify "$work/site/$archive_name" "$signature"
pass "the appcast's signature verifies with Sparkle's sign_update"

# Sparkle's own generator, given the same archive and key, must say the same thing.
mkdir -p "$work/generated"
cp "$work/site/$archive_name" "$work/generated/"
"$sparkle/bin/generate_appcast" --ed-key-file "$key" "$work/generated" >"$evidence/generate_appcast.txt" 2>&1
cp "$work/generated/appcast.xml" "$evidence/appcast-from-generate_appcast.xml"
python3 - "$work/site/appcast.xml" "$work/generated/appcast.xml" <<'PY' | tee -a "$evidence/appcast-comparison.txt"
import sys, xml.etree.ElementTree as ET
S = "{http://www.andymatuschak.org/xml-namespaces/sparkle}"
def read(path):
    item = ET.parse(path).find("channel/item")
    enclosure = item.find("enclosure")
    return {
        "version": item.findtext(S + "version"),
        "shortVersionString": item.findtext(S + "shortVersionString"),
        "minimumSystemVersion": item.findtext(S + "minimumSystemVersion"),
        "hardwareRequirements": item.findtext(S + "hardwareRequirements"),
        "length": enclosure.get("length"),
        "edSignature": enclosure.get(S + "edSignature"),
    }
ours, theirs = read(sys.argv[1]), read(sys.argv[2])
for key in ours:
    print(f"{key}: ours={ours[key]} generate_appcast={theirs[key]}")
sys.exit(0 if ours == theirs else 1)
PY
pass "the appcast item matches what Sparkle's generate_appcast writes for the same archive"

step "Serve the site on 127.0.0.1:$port"
python3 -m http.server "$port" --bind 127.0.0.1 --directory "$work/site" >"$evidence/http.log" 2>&1 &
pids+=($!)
wait_for 10 curl -fsS -o /dev/null "http://127.0.0.1:$port/appcast.xml" || fail "the server did not start"

log stream --style compact --level debug --predicate \
    'subsystem == "org.sparkle-project.Sparkle" OR process == "Autoupdate" OR process == "Updater" OR process == "'"$executable"'"' \
    >"$evidence/unified.log" 2>&1 &
pids+=($!)

downloads() { grep -c "GET /$archive_name" "$evidence/http.log" || true; }

step "Scenario 1: 1.0.0 installs 2.0.0 as soon as it is ready and relaunches"
install="$work/install-immediately"
mkdir -p "$install"
ditto "$work/dist-1/$name.app" "$install/$name.app"
DEMO_CHECK_AFTER_SECS=10 "$install/$name.app/Contents/MacOS/$executable" \
    >"$evidence/immediately-1.0.0.stderr" 2>&1 &
shot immediately-1-installed-1.0.0 7
ps -axo pid,stat,etime,command | grep "$install" | grep -v grep >"$evidence/immediately-processes-at-7s.txt" || true
[[ "$(installed_build "$install/$name.app")" == 1 ]] || fail "1.0.0 was replaced before it was seen"
relaunched() { grep -q "version 2.0.0 started" "$install/self-update-demo.log" 2>/dev/null; }
wait_for 180 relaunched || {
    cat "$evidence/immediately-1.0.0.stderr"
    fail "1.0.0 did not relaunch as 2.0.0 within three minutes"
}
[[ "$(installed_build "$install/$name.app")" == 2 ]] || fail "the installed bundle is not 2.0.0"
shot immediately-2-relaunched-2.0.0 8
cp "$install/self-update-demo.log" "$evidence/immediately-launches.log"
plutil -p "$install/$name.app/Contents/Info.plist" >"$evidence/immediately-installed-Info.plist.txt"
codesign --verify --strict --deep --verbose=2 "$install/$name.app" >"$evidence/immediately-installed-codesign.txt" 2>&1
pass "1.0.0 updated itself to 2.0.0 and relaunched (see immediately-launches.log)"
# The new version checked too, on its own launch. It must find nothing newer.
sleep 10
[[ "$(downloads)" == 1 ]] || fail "the archive was downloaded $(downloads) times; 2.0.0 offered itself again"
pass "2.0.0 checked the same feed and did not download anything"
pkill -f "$install/" || true

step "Scenario 2: 1.0.0 downloads 2.0.0 and installs it when it quits"
install="$work/install-on-quit"
mkdir -p "$install"
ditto "$work/dist-1/$name.app" "$install/$name.app"
DEMO_INSTALL=on-quit DEMO_CHECK_AFTER_SECS=3 DEMO_QUIT_AFTER_SECS=40 \
    "$install/$name.app/Contents/MacOS/$executable" >"$evidence/on-quit-1.0.0.stderr" 2>&1 &
app=$!
shot on-quit-1-running-1.0.0 8
wait "$app" || true
echo "1.0.0 exited" >>"$evidence/on-quit-1.0.0.stderr"
updated() { [[ "$(installed_build "$install/$name.app")" == 2 ]]; }
if wait_for 120 updated; then
    plutil -p "$install/$name.app/Contents/Info.plist" >"$evidence/on-quit-installed-Info.plist.txt"
    pass "1.0.0 quit and Sparkle installed 2.0.0 in its place"
else
    fail "1.0.0 quit and 2.0.0 was not installed within two minutes"
fi

step "Mac App Store: the same application without the updater"
build 1.0.0 "$work/build/$executable-store" --no-default-features
"$package" app --channel app-store --manifest-dir "$app_dir" \
    --executable "$work/build/$executable-store" --version 1.0.0 --build 1 \
    --out "$work/dist-store" --uses-non-exempt-encryption no --network-client \
    --user-selected-files read-write | tee "$evidence/package-store.txt"
store_app="$work/dist-store/$name.app"
ditto -c -k --keepParent "$store_app" "$work/bundles/store-1.0.0.zip"
"$package" verify --app "$store_app" --channel app-store | tee -a "$evidence/package-store.txt"
codesign -d --entitlements - --xml "$store_app" >"$evidence/store-entitlements.plist" 2>/dev/null
plutil -p "$store_app/Contents/Info.plist" >"$evidence/store-Info.plist.txt"
"$package" pkg --app "$store_app" --out "$work/dist-store/SelfUpdateDemo.pkg"
pkgutil --expand-full "$work/dist-store/SelfUpdateDemo.pkg" "$work/dist-store/expanded"
(cd "$work/dist-store/expanded" && find . -maxdepth 6 | sort) >"$evidence/store-pkg-contents.txt"
if grep -ri sparkle "$evidence/store-pkg-contents.txt" >/dev/null; then
    fail "the store package contains something named Sparkle"
fi
if grep -rl "SPUStandardUpdaterController\|Sparkle.framework" "$work/dist-store/expanded" >/dev/null; then
    fail "a file in the store package names Sparkle"
fi
pass "the store package installs into /Applications and contains nothing of Sparkle"

"$store_app/Contents/MacOS/$executable" >"$evidence/store.stderr" 2>&1 &
store_pid=$!
shot store-1-sandboxed-1.0.0 10
if kill -0 "$store_pid" 2>/dev/null; then
    pass "the sandboxed store build is running after ten seconds (see store-1-sandboxed-1.0.0.png)"
else
    cat "$evidence/store.stderr"
    fail "the sandboxed store build exited"
fi
if [[ -d "$HOME/Library/Containers/$identifier" ]]; then
    pass "the system created its sandbox container"
else
    fail "no sandbox container for $identifier: it did not run sandboxed"
fi
kill "$store_pid" 2>/dev/null || true

step "What running the demo left outside $work"
{
    ls -d "$HOME/Library/Containers/$identifier" 2>/dev/null || true
    ls -d "$HOME/Library/Caches/$identifier" 2>/dev/null || true
    ls "$HOME/Library/Preferences/$identifier.plist" 2>/dev/null || true
    ls -d "$HOME/Library/HTTPStorages/$identifier" 2>/dev/null || true
} | tee "$evidence/left-outside.txt"
echo "remove with: rm -rf <the paths above>; defaults delete $identifier"

echo
cat "$result"
