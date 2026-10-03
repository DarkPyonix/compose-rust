#!/usr/bin/env bash
# Usage: experiments/self-update/rehearse.sh
#
# Rehearses a Sparkle self-update end to end on one machine with no Apple developer
# account: every bundle is signed ad hoc, and the only thing that vouches for an update is
# its EdDSA signature.
#
#   1. Builds the demo application at 1.0.0 and 2.0.0 and packages both (ad hoc).
#   2. Publishes 2.0.0: a zip, and an appcast signed with a key made here, checked against
#      Sparkle's own sign_update and generate_appcast.
#   3. Gatekeeper: a copy marked as downloaded (com.apple.quarantine, as a browser leaves
#      it) is held at launch; after `xattr -dr com.apple.quarantine`, the step the install
#      notes give, the same copy launches.
#   4. That copy, 1.0.0, finds 2.0.0 in the appcast served from 127.0.0.1, installs it at
#      once and relaunches as 2.0.0. The new bundle carries no quarantine mark, so
#      Gatekeeper lets the relaunch through, and 2.0.0 checks the feed and downloads
#      nothing more.
#   5. A second 1.0.0 installs the update when it quits, which is Sparkle's default.
#
# Everything goes under $DEMO_WORK (default .scratch/self-update in this repository):
# builds, bundles, the served site, the private key, and evidence/ with logs, plists,
# signatures and screenshots. evidence/result.txt says what passed.
#
# DEMO_REUSE=1 skips steps 1 and 2 and uses the dist-1, dist-2 and site directories already
# in $DEMO_WORK, for example ones unpacked from the CI artifact, so the update can be
# watched on a Mac that has not built anything.
#
# Needs Xcode's command line tools, Python 3.11 or newer, and (unless reusing) Rust.
# Sparkle's release archive and the released renderer are downloaded into $DEMO_WORK and
# checked against pinned SHA-256 sums. Running the application writes what any macOS
# application writes: Sparkle's preferences and download cache under ~/Library for the
# demo's bundle identifier. The last step lists them.

set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="${DEMO_WORK:-$repo/.scratch/self-update}"
port="${DEMO_PORT:-8765}"
reuse="${DEMO_REUSE:-0}"
package="$repo/tools/packager/package-macos"
app_dir="$repo/experiments/self-update/app"
evidence="$work/evidence"
name="Self Update Demo"
identifier="dev.darkpyonix.dioxus.compose.experiments.selfupdate"
executable="self-update-demo"
archive_name="SelfUpdateDemo-2.0.0.zip"

renderer_version="0.0.0"
sparkle_version="2.10.0"
sparkle_sha256="c2bf58aa8387266ac179357b1415d6f2635f044da8be41042af32425dae6da0c"

rm -rf "$work"/install-* "$evidence"
if [[ "$reuse" != 1 ]]; then
    rm -rf "$work/build" "$work"/dist-* "$work/site" "$work/generated" "$work/bundles"
fi
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
    # The whole screen. A machine where this shell may not record the screen gets no
    # picture, which is noted rather than fatal: the logs are the evidence and the picture
    # is for a person.
    sleep "${2:-0}"
    screencapture -x "$evidence/$1.png" 2>>"$evidence/screencapture.log" ||
        echo "screencapture failed for $1" >>"$evidence/screencapture.log"
}

pids=()
cleanup() {
    for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
    pkill -f "$work/install-" 2>/dev/null || true
    pkill -f "AppTranslocation/.*/$name.app" 2>/dev/null || true
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

# Mark a bundle the way a browser and Archive Utility leave a download.
quarantine() {
    local value
    value="0083;$(printf %x "$(date +%s)");Safari;$(uuidgen)"
    xattr -w -r com.apple.quarantine "$value" "$1"
}

# Launch through LaunchServices, as a double click does, so that Gatekeeper is consulted.
launch() {
    local app="$1" log="$2"
    shift 2
    local env_args=()
    for assignment in "$@"; do env_args+=(--env "$assignment"); done
    open -n ${env_args[@]+"${env_args[@]}"} --stdout "$log" --stderr "$log" "$app"
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

if [[ "$reuse" != 1 ]]; then
    step "Renderer $renderer_version, as released for the dioxus-compose the demo depends on"
    renderer="$work/renderer"
    if [[ ! -f "$renderer/lib/libdioxus_compose_renderer.dylib" ]]; then
        artifact="dioxus-compose-renderer-v$renderer_version-macos-aarch64.tar.gz"
        release="https://github.com/DarkPyonix/dioxus-compose/releases/download/v$renderer_version"
        mkdir -p "$work/downloads" "$renderer"
        curl -fsSL -o "$work/downloads/$artifact" "$release/$artifact"
        curl -fsSL -o "$work/downloads/$artifact.sha256" "$release/$artifact.sha256"
        (cd "$work/downloads" && shasum -a 256 -c "$artifact.sha256")
        tar -xzf "$work/downloads/$artifact" -C "$renderer"
    fi
    export DIOXUS_COMPOSE_RENDERER_DIR="$renderer"

    step "EdDSA key (kept in $work/keys, never in the repository)"
    key="$work/keys/sparkle.key"
    [[ -f "$key" ]] || "$package" keygen --out "$key"
    echo "SUPublicEDKey $("$package" public-key --key "$key")"

    # Plain http to 127.0.0.1 is how an update is rehearsed on one machine. App Transport
    # Security refuses it unless the application allows local networking.
    cat >"$work/ats.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsLocalNetworking</key><true/></dict>
</dict></plist>
PLIST

    step "Build and package 1.0.0 and 2.0.0, signed ad hoc"
    mkdir -p "$work/bundles"
    for major in 1 2; do
        DEMO_VERSION="$major.0.0" cargo build --release --manifest-path "$app_dir/Cargo.toml"
        cp "$app_dir/target/release/$executable" "$work/build/$executable-$major"
        "$package" app --updater sparkle --manifest-dir "$app_dir" \
            --executable "$work/build/$executable-$major" --renderer-dir "$renderer/lib" \
            --version "$major.0.0" --build "$major" --out "$work/dist-$major" \
            --feed-url "http://127.0.0.1:$port/appcast.xml" --ed-key-file "$key" \
            --sparkle-framework "$sparkle/Sparkle.framework" --automatically-update \
            --plist-file "$work/ats.plist" | tee "$evidence/package-$major.txt"
        # Kept whole (ditto keeps the framework's links, which an artifact upload would
        # not), so the update can be watched again on another Mac without building.
        ditto -c -k --keepParent "$work/dist-$major/$name.app" "$work/bundles/$name-$major.0.0.zip"
    done
    "$package" dmg --app "$work/dist-1/$name.app" --out "$work/bundles/$name-1.0.0.dmg"
    pass "1.0.0 and 2.0.0 packaged; every piece of code in them is signed ad hoc"

    step "Publish 2.0.0"
    "$package" archive --app "$work/dist-2/$name.app" --out "$work/site/$archive_name"
    "$package" appcast --appcast "$work/site/appcast.xml" --app "$work/dist-2/$name.app" \
        --archive "$work/site/$archive_name" --url "http://127.0.0.1:$port/$archive_name" \
        --ed-key-file "$key" --sign-update "$sparkle/bin/sign_update"
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
    python3 - "$work/site/appcast.xml" "$work/generated/appcast.xml" <<'PY' | tee "$evidence/appcast-comparison.txt"
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
    cp -R "$work/site" "$work/bundles/site"
fi

for major in 1 2; do
    plutil -p "$work/dist-$major/$name.app/Contents/Info.plist" >"$evidence/Info-$major.plist.txt"
    codesign -dvv "$work/dist-$major/$name.app" >"$evidence/codesign-$major.txt" 2>&1
    codesign --verify --strict --deep --verbose=2 "$work/dist-$major/$name.app" \
        >>"$evidence/codesign-$major.txt" 2>&1
    find "$work/dist-$major/$name.app" -type f \( -perm +111 -o -name "*.dylib" \) | while read -r code; do
        printf '%s: ' "${code#"$work/dist-$major/"}"
        codesign -dv "$code" 2>&1 | grep -E "^Signature=" || echo "not signed"
    done >"$evidence/signatures-$major.txt"
    "$package" verify --app "$work/dist-$major/$name.app" --updater sparkle >>"$evidence/codesign-$major.txt"
done
cp "$work/site/appcast.xml" "$evidence/appcast.xml"

step "Serve the site on 127.0.0.1:$port"
# http.server's own server_bind looks the address up by name, which on macOS 15 and newer
# asks the person whether Python may use the local network. Nothing here needs a name.
python3 - "$work/site" "$port" >"$evidence/http.log" 2>&1 <<'PY' &
import functools, http.server, socketserver, sys
handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=sys.argv[1])
class Server(http.server.ThreadingHTTPServer):
    def server_bind(self):
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]
Server(("127.0.0.1", int(sys.argv[2])), handler).serve_forever()
PY
pids+=($!)
wait_for 10 curl -fsS -o /dev/null "http://127.0.0.1:$port/appcast.xml" || fail "the server did not start"

/usr/bin/log stream --style compact --level debug --predicate \
    'subsystem == "org.sparkle-project.Sparkle" OR process == "Autoupdate" OR process == "Updater" OR process == "'"$executable"'" OR (process == "syspolicyd" AND eventMessage CONTAINS "selfupdate")' \
    >"$evidence/unified.log" 2>&1 &
pids+=($!)

downloads() { grep -c "GET /$archive_name" "$evidence/http.log" || true; }
running_from() { pgrep -f "$1/Contents/MacOS/$executable" >/dev/null; }

step "Gatekeeper holds a downloaded copy until its quarantine mark is removed"
install="$work/install-immediately"
mkdir -p "$install"
ditto "$work/dist-1/$name.app" "$install/$name.app"
quarantine "$install/$name.app"
xattr -p com.apple.quarantine "$install/$name.app" >"$evidence/quarantine-before.txt"
spctl --assess --type execute -vv "$install/$name.app" >>"$evidence/quarantine-before.txt" 2>&1 || true
# Checked after 20 seconds: a copy Gatekeeper holds has a process, parked until the person
# answers, but it never reaches main, so it never writes its launch line.
launch "$install/$name.app" "$evidence/held-1.0.0.log" DEMO_CHECK_AFTER_SECS=1000
shot gatekeeper-1-quarantined-copy-held 20
if grep -q "version 1.0.0 started" "$install/self-update-demo.log" 2>/dev/null; then
    fail "a quarantined ad hoc signed copy started without being approved"
fi
pass "a copy marked as downloaded does not start until the person approves it"
pkill -f "$name.app/Contents/MacOS/$executable" || true
sleep 2

xattr -dr com.apple.quarantine "$install/$name.app"
xattr -l "$install/$name.app" >"$evidence/quarantine-after-xattr.txt"

step "1.0.0 installs 2.0.0 as soon as it is ready and relaunches"
launch "$install/$name.app" "$evidence/immediately-1.0.0.log" DEMO_CHECK_AFTER_SECS=10
started() { grep -q "version 1.0.0 started" "$install/self-update-demo.log" 2>/dev/null; }
wait_for 30 started || fail "1.0.0 did not start after its quarantine mark was removed"
pass "after xattr -dr com.apple.quarantine, the same copy starts"
shot immediately-1-installed-1.0.0 5
ps -axo pid,stat,etime,command | grep "$install" | grep -v grep >"$evidence/immediately-processes-1.0.0.txt" || true
[[ "$(installed_build "$install/$name.app")" == 1 ]] || fail "1.0.0 was replaced before it was seen"
relaunched() { grep -q "version 2.0.0 started" "$install/self-update-demo.log" 2>/dev/null; }
wait_for 180 relaunched || fail "1.0.0 did not relaunch as 2.0.0 within three minutes"
[[ "$(installed_build "$install/$name.app")" == 2 ]] || fail "the installed bundle is not 2.0.0"
shot immediately-2-relaunched-2.0.0 8
cp "$install/self-update-demo.log" "$evidence/immediately-launches.log"
ps -axo pid,stat,etime,command | grep "$install" | grep -v grep >"$evidence/immediately-processes-2.0.0.txt" || true
running_from "$install/$name.app" || fail "2.0.0 is not running from where 1.0.0 was installed"
pass "1.0.0 updated itself to 2.0.0 and relaunched in place (see immediately-launches.log)"
plutil -p "$install/$name.app/Contents/Info.plist" >"$evidence/immediately-installed-Info.plist.txt"
codesign --verify --strict --deep --verbose=2 "$install/$name.app" >"$evidence/immediately-installed-codesign.txt" 2>&1
xattr -lr "$install/$name.app" >"$evidence/immediately-installed-xattr.txt" 2>&1 || true
if grep -q "com.apple.quarantine" "$evidence/immediately-installed-xattr.txt"; then
    fail "the update Sparkle installed carries a quarantine mark"
fi
pass "the installed 2.0.0 carries no quarantine mark, so Gatekeeper let its relaunch through"
# The new version checks too, on its own launch. It must find nothing newer.
sleep 15
[[ "$(downloads)" == 1 ]] || fail "the archive was downloaded $(downloads) times; 2.0.0 offered itself again"
pass "2.0.0 checked the same feed and downloaded nothing"
pkill -f "$install/" || true

step "1.0.0 downloads 2.0.0 and installs it when it quits"
install="$work/install-on-quit"
mkdir -p "$install"
ditto "$work/dist-1/$name.app" "$install/$name.app"
launch "$install/$name.app" "$evidence/on-quit-1.0.0.log" \
    DEMO_INSTALL=on-quit DEMO_CHECK_AFTER_SECS=3 DEMO_QUIT_AFTER_SECS=40
shot on-quit-1-running-1.0.0 8
quit() { ! running_from "$install/$name.app"; }
wait_for 90 quit || fail "1.0.0 did not quit"
updated() { [[ "$(installed_build "$install/$name.app")" == 2 ]]; }
wait_for 120 updated || fail "1.0.0 quit and 2.0.0 was not installed within two minutes"
plutil -p "$install/$name.app/Contents/Info.plist" >"$evidence/on-quit-installed-Info.plist.txt"
pass "1.0.0 quit and Sparkle installed 2.0.0 in its place"
launch "$install/$name.app" "$evidence/on-quit-2.0.0.log"
on_quit_relaunched() { grep -q "version 2.0.0 started" "$install/self-update-demo.log" 2>/dev/null; }
wait_for 30 on_quit_relaunched || fail "the 2.0.0 installed on quit does not start"
cp "$install/self-update-demo.log" "$evidence/on-quit-launches.log"
pass "the 2.0.0 installed on quit starts"
pkill -f "$install/" || true

step "What running the demo left outside $work"
{
    ls -d "$HOME/Library/Caches/$identifier" 2>/dev/null || true
    ls "$HOME/Library/Preferences/$identifier.plist" 2>/dev/null || true
    ls -d "$HOME/Library/HTTPStorages/$identifier" 2>/dev/null || true
    ls -d "$HOME/Library/Saved Application State/$identifier.savedState" 2>/dev/null || true
} | tee "$evidence/left-outside.txt"
echo "remove with: rm -rf <the paths above>; defaults delete $identifier"

echo
cat "$result"
