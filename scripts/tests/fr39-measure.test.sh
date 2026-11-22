#!/usr/bin/env bash
# Usage: scripts/tests/fr39-measure.test.sh
#
# Exercises scripts/bench/fr39-measure.sh without building or measuring anything.
#
# The script's real run takes every build slot on the machine and spends half an hour, so
# none of it can run here. What can run is everything that decides whether it starts and
# what it writes: --check, the quiet-machine gate against a fake slot directory and a
# faked load reading, the record writer on its canned sample, and the comparison a second
# session reads. The last check is that the script hands the work to the bench's own
# run.sh and parity.sh instead of building or timing anything itself, because a second
# copy of the measurement would be a second measurement.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/scripts/bench/fr39-measure.sh"

tests_run=0
tests_failed=0

fail_test() {
    tests_failed=$((tests_failed + 1))
    printf 'FAIL %s: %s\n' "$1" "$2" >&2
    [[ -n "${3:-}" ]] && printf '%s\n' "$3" >&2
}

# assert_run <name> <expected status> <expected substring|-> <command...>
assert_run() {
    local name="$1" expected_status="$2" expected_substring="$3"
    shift 3
    tests_run=$((tests_run + 1))
    local output status
    output="$("$@" 2>&1)"
    status=$?
    if [[ "$status" != "$expected_status" ]]; then
        fail_test "$name" "expected exit $expected_status, got $status" "$output"
        return
    fi
    if [[ "$expected_substring" != "-" && "$output" != *"$expected_substring"* ]]; then
        fail_test "$name" "output did not contain '$expected_substring'" "$output"
        return
    fi
    printf 'ok   %s\n' "$name"
}

if ! command -v jq >/dev/null 2>&1; then
    echo "skip  jq is not installed; the record writer and this test both need it"
    exit 0
fi
[[ -x "$script" ]] || {
    echo "FAIL $script is missing or not executable" >&2
    exit 1
}

work="$(mktemp -d "$repo_root/.scratch/fr39-measure-test.XXXXXX" 2>/dev/null ||
    { mkdir -p "$repo_root/.scratch" && mktemp -d "$repo_root/.scratch/fr39-measure-test.XXXXXX"; })"
check_dirs=()
cleanup() {
    rm -rf "$work"
    for dir in "${check_dirs[@]}"; do rm -rf "$dir"; done
}
trap cleanup EXIT

# A slot directory of our own, with a slot.sh that names it the way the real one does.
# Nothing here runs that slot.sh: the gate only reads it.
slots="$work/slots"
mkdir -p "$slots"
cat > "$slots/slot.sh" <<EOF
#!/bin/zsh
D=$slots
N=3
EOF
chmod +x "$slots/slot.sh"

# The environment every case starts from: the fake slots, this checkout's volume for the
# free space reading, and a quiet load. Each case changes one thing.
run_with() {
    env FR39_SLOTS_DIR="$slots" FR39_DF_PATH="$repo_root" FR39_FAKE_LOAD=0.20 "$@"
}

# ----- --check -------------------------------------------------------------------------

tests_run=$((tests_run + 1))
check_output="$(run_with "$script" --check 2>&1)"
check_status=$?
if [[ "$check_status" != 0 || "$check_output" != *"check passed"* ]]; then
    fail_test "fr39_measure_check_passes" "exit $check_status" "$check_output"
else
    printf 'ok   %s\n' "fr39_measure_check_passes"
fi
sample="$(sed -n 's/^.*sample record: //p' <<<"$check_output" | head -1)"
[[ -n "$sample" ]] && check_dirs+=("$(dirname "$sample")")

# ----- the record writer ------------------------------------------------------------------

# Every field the measurement has to leave behind: the machine and its load, how many
# passes and warm-ups, p50, p99 and the maximum per scenario on each path, where the raw
# runs are, both revisions, the toolchain, the geometric means and the verdict. Listed
# here rather than read from the script, so the script cannot drop one and still pass.
required=(
    .fr39.machine
    .fr39.load_average
    .fr39.baseline_commit
    .fr39.raw_runs
    .fr39.runs
    .fr39.comparisons
    .fr39.geometric_mean_p50_ratio
    .fr39.geometric_mean_p99_ratio
    .fr39.target_met
    .fr39.conditions.date
    .fr39.conditions.machine_model
    .fr39.conditions.chip
    .fr39.conditions.cpu_count
    .fr39.conditions.memory_bytes
    .fr39.conditions.os_version
    .fr39.conditions.kernel
    .fr39.conditions.toolchain.rustc
    .fr39.conditions.toolchain.cargo
    .fr39.conditions.candidate.repo
    .fr39.conditions.candidate.commit
    .fr39.conditions.candidate.dirty
    .fr39.conditions.baseline.repo
    .fr39.conditions.baseline.rev
    .fr39.conditions.baseline.compose_rust_rev
    .fr39.conditions.iterations
    .fr39.conditions.warmup
    .fr39.conditions.load_average.at_start
    .fr39.conditions.load_average.limit
    .fr39.conditions.load_average.limit_overridden
    .fr39.conditions.load_average.during_run
    .fr39.conditions.load_average.idle_throughout
    .fr39.conditions.raw_dir
    .fr39.conditions.parity_dir
    .fr39.conditions.log_dir
    .fr39.conditions.build.cargo_build_jobs
    .fr39.conditions.build.profile
    .fr39.conditions.cargo_lock_sha256
    .fr39.conditions.not_measured
    .fr39.verdict.met
    .fr39.verdict.geometric_mean_p50_multiple
    .fr39.verdict.geometric_mean_p99_multiple
    .fr39.verdict.line
    .fr39.verdict.record_violations
)

tests_run=$((tests_run + 1))
if [[ -z "$sample" || ! -f "$sample" ]]; then
    fail_test "fr39_measure_record_has_every_field" "--check printed no sample record path" "$check_output"
elif ! jq -e . "$sample" >/dev/null 2>&1; then
    fail_test "fr39_measure_record_has_every_field" "the sample record is not JSON" "$(cat "$sample")"
else
    missing=()
    for field in "${required[@]}"; do
        jq -e "$field != null" "$sample" >/dev/null 2>&1 || missing+=("$field")
    done
    # Per scenario, on each path and in each comparison.
    jq -e '.fr39.runs | length > 0 and all(has("iterations") and has("warmup")
        and has("p50_ns") and has("p99_ns") and has("max_ns"))' "$sample" >/dev/null ||
        missing+=(".fr39.runs[].{iterations,warmup,p50_ns,p99_ns,max_ns}")
    jq -e '.fr39.comparisons | length > 0 and all(has("name") and has("valid")
        and has("baseline_p50_ns") and has("candidate_p50_ns") and has("p50_ratio")
        and has("baseline_p99_ns") and has("candidate_p99_ns") and has("p99_ratio")
        and has("baseline_max_ns") and has("candidate_max_ns"))' "$sample" >/dev/null ||
        missing+=(".fr39.comparisons[].{p50,p99,max,ratios}")
    if ((${#missing[@]} > 0)); then
        fail_test "fr39_measure_record_has_every_field" "missing: ${missing[*]}" "$(cat "$sample")"
    else
        printf 'ok   %s\n' "fr39_measure_record_has_every_field"
    fi
fi

# ----- the quiet-machine gate -------------------------------------------------------------

assert_run "fr39_measure_preflight_starts_on_a_quiet_machine" 0 "quiet enough" \
    run_with "$script" --preflight

# A slot held by a live process: this test's own shell.
mkdir "$slots/slot2"
echo "$$ $(date +%s) $repo_root some other build" > "$slots/slot2/owner"
assert_run "fr39_measure_refuses_while_a_build_slot_is_busy" 1 "slot2 is busy" \
    run_with "$script" --preflight
rm -rf "$slots/slot2"

# A slot whose owner is gone is free: slot.sh reclaims it.
sleep 0 &
gone=$!
wait "$gone" 2>/dev/null
mkdir "$slots/slot3"
echo "$gone $(date +%s) $repo_root a build that was killed" > "$slots/slot3/owner"
assert_run "fr39_measure_counts_a_stale_slot_as_free" 0 "slot3 is stale" \
    run_with "$script" --preflight
rm -rf "$slots/slot3"

assert_run "fr39_measure_refuses_a_loaded_machine" 1 "load average 9.50 is not under 1.0" \
    run_with env FR39_FAKE_LOAD=9.50 "$script" --preflight

assert_run "fr39_measure_load_limit_override_is_loud" 0 "WARNING" \
    run_with env FR39_FAKE_LOAD=3.00 FR39_LOAD_LIMIT=5 "$script" --preflight

# A faked reading may decide a dry run and nothing else.
assert_run "fr39_measure_refuses_a_faked_load_for_a_real_run" 1 "FR39_FAKE_LOAD" \
    run_with "$script"

# The inner run measures only while it holds all three slots.
assert_run "fr39_measure_inside_refuses_without_the_slots" 1 "does not hold all three build slots" \
    env FR39_SLOTS_DIR="$slots" FR39_DF_PATH="$repo_root" "$script" --inside

assert_run "fr39_measure_rejects_an_unknown_argument" 2 "usage" \
    run_with "$script" --no-such-flag

# ----- the comparison a second session reads ----------------------------------------------

if [[ -n "$sample" && -f "$sample" ]]; then
    rerun="$work/rerun.json"
    jq '.fr39.comparisons[0].p50_ratio *= 1.1 | .fr39.geometric_mean_p50_ratio *= 1.05' \
        "$sample" > "$rerun"
    assert_run "fr39_measure_compare_lists_every_scenario" 0 "sweep_1" \
        "$script" --compare-records "$sample" "$rerun"
    assert_run "fr39_measure_compare_invents_no_tolerance" 0 "states no tolerance" \
        "$script" --compare-records "$sample" "$rerun"
    flipped="$work/flipped.json"
    jq '.fr39.target_met = (.fr39.target_met | not)
        | .fr39.verdict.met = (.fr39.verdict.met | not)' "$sample" > "$flipped"
    assert_run "fr39_measure_compare_fails_when_the_verdict_differs" 1 "verdict differs" \
        "$script" --compare-records "$sample" "$flipped"
fi

# ----- a run with stubbed bench scripts ---------------------------------------------------

# A copy of the script in a tree of its own, whose parity.sh and run.sh are stubs, so the
# measuring path runs end to end without building anything. parity.sh answers the way
# compare does: 0 clean, 1 when trees differ (whatever else), 3 for a count excess alone.
fake="$work/repo" bin="$work/bin" held="$work/held"
mkdir -p "$fake/scripts/bench" "$fake/compose-rust/benches/fr39/baseline" "$bin" "$held"
cp "$script" "$repo_root/scripts/bench/fr39-baseline.env" "$fake/scripts/bench/"
cp "$repo_root/compose-rust/benches/fr39/Cargo.toml" "$fake/compose-rust/benches/fr39/"
cp "$repo_root/compose-rust/benches/fr39/baseline/Cargo.toml" "$fake/compose-rust/benches/fr39/baseline/"
for i in 1 2 3; do mkdir "$held/slot$i" && echo "$$ 0 $fake fr39-measure.sh" > "$held/slot$i/owner"; done
printf '#!/bin/sh\necho stub\n' > "$bin/cargo" && cp "$bin/cargo" "$bin/rustc"
printf '#!/bin/sh\necho "Filesystem 1024-blocks Used Available Capacity Mounted"\necho "stub 999999999 0 999999999 0%% /"\n' > "$bin/df"
cat > "$fake/compose-rust/benches/fr39/parity.sh" <<'EOF'
mkdir -p "$(dirname "$0")/../../../.scratch/fr39/parity-$$"
violation="violation tab_switching: breaks the rule that the candidate sends no more records or bytes than the baseline: 3401 records against the baseline's 3386 (+15), 80339 bytes against the baseline's 79727 (+612)"
case "$FAKE_PARITY" in
    violation) echo "$violation" && exit 3 ;;
    trees) echo "sweep_1: the Renderer trees differ after step 0" && exit 1 ;;
    both) echo "$violation" && echo "sweep_1: the Renderer trees differ after step 0" && exit 1 ;;
esac
echo "every scenario built the same trees on both paths"
EOF
cat > "$fake/compose-rust/benches/fr39/run.sh" <<'EOF'
raw="$(dirname "$0")/../../../.scratch/fr39/run-$$" && mkdir -p "$raw" && touch "$FAKE_RAN"
jq --arg r "$raw/x.json" '.fr39.raw_runs = [$r]' "$FAKE_RECORD" > "$(dirname "$0")/../baseline.json"
EOF
chmod +x "$bin"/*
# The record run.sh's compare would write, with tab_switching over the baseline's counts or
# not. Both claim the timing target met, so a verdict that is not met comes from the violation.
clean_record="$work/clean.json" violation_record="$work/violation.json"
jq '.fr39.target_met = true | .fr39.record_violations = []' "$sample" > "$clean_record"
jq '{scenario: "tab_switching", candidate_records: 3401, baseline_records: 3386, excess_records: 15,
    candidate_bytes: 80339, baseline_bytes: 79727, excess_bytes: 612} as $v
    | .fr39.target_met = true | .fr39.record_violations = [$v]
    | .fr39.comparisons[1] |= (.name = "tab_switching" | .record_violation = $v)' "$sample" > "$violation_record"

# fake_run <parity outcome> <record> <mode>
fake_run() {
    rm -f "$work/ran" "$fake/compose-rust/benches/baseline.json"
    env PATH="$bin:$PATH" FR39_SLOTS_DIR="$held" FR39_DF_PATH="$work" FR39_SETTLE_SECONDS=0 \
        FAKE_PARITY="$1" FAKE_RECORD="$2" FAKE_RAN="$work/ran" "$fake/scripts/bench/fr39-measure.sh" "$3"
}
recorded() { jq -c "$1" "$fake/compose-rust/benches/baseline.json" 2>&1; }

assert_run "fr39_measure_goes_on_past_a_count_violation" 0 "tab_switching: 3401 records against 3386 (+15)" \
    fake_run violation "$violation_record" --inside
tests_run=$((tests_run + 1))
got="$(recorded '[.fr39.verdict.met, .fr39.verdict.record_violations, .fr39.conditions.parity_record_violations, .fr39.record_violations[0].excess_bytes, (.fr39.verdict.line | startswith("target not met"))]')"
if [[ "$got" == '[false,["tab_switching"],["tab_switching"],612,true]' ]]; then
    printf 'ok   %s\n' "fr39_measure_records_a_count_violation_and_does_not_meet_the_target"
else
    fail_test "fr39_measure_records_a_count_violation_and_does_not_meet_the_target" "got $got"
fi
assert_run "fr39_measure_prepare_goes_on_past_a_count_violation" 0 "more records or bytes than the baseline: tab_switching" \
    fake_run violation "$violation_record" --prepare-inside
for outcome in trees both; do
    assert_run "fr39_measure_stops_when_trees_differ_$outcome" 1 "nothing was measured" \
        fake_run "$outcome" "$violation_record" --inside
    [[ ! -e "$work/ran" ]] || fail_test "fr39_measure_stops_when_trees_differ_$outcome" "run.sh ran"
    assert_run "fr39_measure_prepare_stops_when_trees_differ_$outcome" 1 "parity.sh failed (exit 1)" \
        fake_run "$outcome" "$violation_record" --prepare-inside
done
assert_run "fr39_measure_clean_run_records_no_violation" 0 "target met: " \
    fake_run clean "$clean_record" --inside
tests_run=$((tests_run + 1))
got="$(recorded '[.fr39.verdict.met, .fr39.verdict.record_violations, .fr39.conditions.parity_record_violations, (.fr39.verdict.line | test("^target met: .*; 0 invalid, 0 slower than the baseline$"))]')"
if [[ "$got" == '[true,[],[],true]' ]]; then
    printf 'ok   %s\n' "fr39_measure_clean_run_meets_the_target_as_before"
else
    fail_test "fr39_measure_clean_run_meets_the_target_as_before" "got $got"
fi

# ----- the script measures through the bench's own scripts --------------------------------

tests_run=$((tests_run + 1))
problems=()
grep -q '"$bench/run.sh"' "$script" || problems+=("does not call the bench's run.sh")
grep -q '"$bench/parity.sh"' "$script" || problems+=("does not call the bench's parity.sh")
grep -nE '^[^#]*\bcargo +(build|run|test|bench)\b' "$script" >/dev/null &&
    problems+=("runs cargo itself instead of leaving the build to run.sh and parity.sh")
grep -nE '^[^#]*target/release/(fr39-|compare)' "$script" >/dev/null &&
    problems+=("runs the bench binaries itself instead of through run.sh")
grep -q '"$slot_sh" "$slot_sh" "$slot_sh" "$self" --inside' "$script" ||
    problems+=("does not take all three build slots by nesting slot.sh")
if ((${#problems[@]} > 0)); then
    fail_test "fr39_measure_calls_run_and_parity_rather_than_reimplementing" "$(printf '%s; ' "${problems[@]}")"
else
    printf 'ok   %s\n' "fr39_measure_calls_run_and_parity_rather_than_reimplementing"
fi

echo
if ((tests_failed > 0)); then
    echo "$tests_failed of $tests_run failed"
    exit 1
fi
echo "all $tests_run passed"
