#!/usr/bin/env bash
# Verifies the synthetic-input frame soak checker, runner, fixture and CI wiring
# across macOS, Linux and Windows.
#
# The soak drives a Compose runtime for thousands of frames with synthetic state
# updates and asserts that end physical footprint stays strictly under 2x start.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
check_script="$repo_root/.github/scripts/frame-soak-check.sh"
run_script="$repo_root/.github/scripts/frame-soak-run.sh"
fixture="$repo_root/compose-rust/tests/fixtures/consumer/src/main.rs"
kn_workflow="$repo_root/.github/workflows/test-kn-desktop-renderer.yml"
win_workflow="$repo_root/.github/workflows/windows-static.yml"

red=0
fail() {
    echo "fail: $1" >&2
    red=1
}

for path in "$check_script" "$run_script" "$fixture" "$kn_workflow" "$win_workflow"; do
    [[ -f "$path" ]] || fail "missing ${path#"$repo_root"/}"
done
(( red == 0 )) || exit 1

[[ -x "$check_script" ]] || fail ".github/scripts/frame-soak-check.sh is not executable"
[[ -x "$run_script" ]] || fail ".github/scripts/frame-soak-run.sh is not executable"

mkdir -p "$repo_root/.scratch"
tmp="$(mktemp -d "$repo_root/.scratch/nfr17-frame-soak-test.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT

# 1. Threshold checker: pass when end footprint is strictly under 2x start.
cat > "$tmp/pass.log" <<'EOF'
compose-rust: frame-soak phase start frames=100 footprint_kb=40960 metric=phys_footprint
compose-rust: frame-soak phase end frames=2500 footprint_kb=73728 metric=phys_footprint
compose-rust: frame-soak done frames=2500
EOF
out="$("$check_script" "$tmp/pass.log" 2.0 2000 2>&1)" || fail "checker rejected 1.80x footprint: $out"
grep -q '^ok ' <<<"$out" || fail "checker pass output did not print ok: $out"

# 2. Threshold checker: fail when end footprint equals 2x start (must be strictly under 2x).
cat > "$tmp/equal2x.log" <<'EOF'
compose-rust: frame-soak phase start frames=100 footprint_kb=40960 metric=phys_footprint
compose-rust: frame-soak phase end frames=2500 footprint_kb=81920 metric=phys_footprint
compose-rust: frame-soak done frames=2500
EOF
if out="$("$check_script" "$tmp/equal2x.log" 2.0 2000 2>&1)"; then
    fail "checker accepted exactly 2.00x footprint: $out"
else
    grep -q '^FAIL ' <<<"$out" || fail "checker 2.00x failure did not print FAIL: $out"
fi

# 3. Threshold checker: fail when end footprint exceeds 2x start.
cat > "$tmp/over2x.log" <<'EOF'
compose-rust: frame-soak phase start frames=100 footprint_kb=40960 metric=phys_footprint
compose-rust: frame-soak phase end frames=2500 footprint_kb=102400 metric=phys_footprint
compose-rust: frame-soak done frames=2500
EOF
if out="$("$check_script" "$tmp/over2x.log" 2.0 2000 2>&1)"; then
    fail "checker accepted 2.50x footprint: $out"
else
    grep -q '^FAIL ' <<<"$out" || fail "checker 2.50x failure did not print FAIL: $out"
fi

# 4. Threshold checker: fail when fewer than min_frames were drawn.
cat > "$tmp/too-few.log" <<'EOF'
compose-rust: frame-soak phase start frames=100 footprint_kb=40960 metric=phys_footprint
compose-rust: frame-soak phase end frames=500 footprint_kb=41000 metric=phys_footprint
compose-rust: frame-soak done frames=500
EOF
if out="$("$check_script" "$tmp/too-few.log" 2.0 2000 2>&1)"; then
    fail "checker accepted 500 frames when 2000 were required: $out"
else
    grep -q '^FAIL ' <<<"$out" || fail "checker low-frames failure did not print FAIL: $out"
fi

# 5. Threshold checker: fail when start or end phase is missing.
cat > "$tmp/missing-end.log" <<'EOF'
compose-rust: frame-soak phase start frames=100 footprint_kb=40960 metric=phys_footprint
EOF
if out="$("$check_script" "$tmp/missing-end.log" 2.0 2000 2>&1)"; then
    fail "checker accepted log missing end phase: $out"
else
    grep -q '^FAIL ' <<<"$out" || fail "checker missing-end failure did not print FAIL: $out"
fi

# 6. Runner end-to-end with a stub app: samples real host memory and feeds checker.
cat > "$tmp/fake-app" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo "frame-soak: phase start pid=$$ frames=100"
sleep 1
echo "frame-soak: phase end pid=$$ frames=2500"
sleep 5
EOF
chmod +x "$tmp/fake-app"
DXC_FRAME_SOAK_TIMEOUT=30 "$run_script" "$tmp/fake-app" "$tmp/out" stub ||
    fail "frame-soak-run.sh failed on stub app"
[[ -f "$tmp/out/stub.log" ]] || fail "frame-soak-run.sh produced no stub.log"
out="$("$check_script" "$tmp/out/stub.log" 2.0 2000 2>&1)" ||
    fail "checker rejected stub runner output: $out"

# 7. Runner supports macOS, Linux and Windows memory sampling.
grep -Fq 'footprint' "$run_script" || fail "frame-soak-run.sh has no macOS footprint sampler"
grep -Fq '/proc/' "$run_script" || fail "frame-soak-run.sh has no Linux /proc sampler"
grep -Fq 'WorkingSet64' "$run_script" || fail "frame-soak-run.sh has no Windows WorkingSet64 sampler"

# 8. Consumer fixture supports --frame-soak on the slot-table Recomposer.
grep -Fq -- '--frame-soak' "$fixture" || fail "consumer fixture has no --frame-soak mode"
grep -Fq 'Recomposer::new' "$fixture" || fail "consumer fixture frame soak does not run Recomposer"
grep -Fq 'frame-soak: phase start' "$fixture" || fail "consumer fixture does not log phase start"
grep -Fq 'frame-soak: phase end' "$fixture" || fail "consumer fixture does not log phase end"

# 9. CI workflows wire the frame soak on macOS, Linux and Windows.
grep -Fq 'frame-soak-macos-kn' "$kn_workflow" ||
    fail "test-kn-desktop-renderer.yml does not run frame soak on macOS"
grep -Fq 'frame-soak-linux-kn' "$kn_workflow" ||
    fail "test-kn-desktop-renderer.yml does not run frame soak on Linux"
grep -Fq 'frame-soak-windows-kn' "$win_workflow" ||
    fail "windows-static.yml does not run frame soak on Windows"
grep -Fq 'frame-soak-check.sh' "$kn_workflow" ||
    fail "test-kn-desktop-renderer.yml does not check frame soak output"
grep -Fq 'frame-soak-check.sh' "$win_workflow" ||
    fail "windows-static.yml does not check frame soak output"

(( red == 0 )) || exit 1
echo "ok    synthetic-input frame soak checker, runner, fixture and macOS/Linux/Windows CI wiring verified"
