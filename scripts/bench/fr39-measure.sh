#!/usr/bin/env bash
# Measures the slot table path against the pinned Dioxus path in one command, on a machine
# that is quiet enough for the numbers to mean something, and records what it measured.
#
#   scripts/bench/fr39-measure.sh                     measure and record
#   scripts/bench/fr39-measure.sh --prepare           build the bench and check parity
#                                                     under one build slot, ahead of time
#   scripts/bench/fr39-measure.sh --verify FILE       measure again and compare with FILE
#   scripts/bench/fr39-measure.sh --check             validate everything; build nothing
#   scripts/bench/fr39-measure.sh --preflight         only say whether the machine is quiet
#   scripts/bench/fr39-measure.sh --compare-records RECORDED RERUN
#                                                     compare two records; build nothing
#
# The measurement itself is compose-rust/benches/fr39: parity.sh checks that both paths
# build the same trees and that the slot table path sends no more records or bytes, and
# run.sh builds both paths in release, runs every scenario on each, and writes the
# comparison into compose-rust/benches/baseline.json. This script decides when those two
# may run and writes down the conditions they ran under. It does not build or time
# anything itself: a second copy of the measurement would be a second measurement, and
# the one that counts is run.sh's.
#
# Why the ceremony. A number taken while another build runs measures the other build. So
# the run refuses to start unless every build slot on the machine is free and the load
# average is under the limit below, then takes all three slots for its whole run, so no
# build can start beside it, and frees them when it ends. The slots are the machine-wide
# ones every session builds through (slot.sh); nesting it three times takes three slots.
#
# What this does not measure: the real Renderer's apply time. The harness times the Host
# (handler, recomposition, batch encoding) and a Rust model of the Renderer's apply to its
# node table, so whatever the Kotlin interpreter adds on top is not in these numbers.
#
# Environment:
#   FR39_LOAD_LIMIT       start even though the 1-minute load average is at or above the
#                         default limit; warned about loudly and recorded in the result
#   FR39_SLOTS_DIR        the build slot directory (default: darkpyonix-core's)
#   FR39_SLOT_SH          the slot wrapper (default: slot.sh in that directory)
#   FR39_DF_PATH          where free space is read (default: /Volumes/macMini when it
#                         exists, otherwise this checkout)
#   FR39_SETTLE_SECONDS   how long to wait after the build for the load to fall back
#                         under the limit before measuring (default 300)
#   FR39_FAKE_LOAD        a load reading for --check and --preflight only, for tests
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
self="$repo_root/scripts/bench/$(basename "${BASH_SOURCE[0]}")"
bench="$repo_root/compose-rust/benches/fr39"
pin="$repo_root/scripts/bench/fr39-baseline.env"
record="$repo_root/compose-rust/benches/baseline.json"
scratch="$repo_root/.scratch/fr39"

slots_dir="${FR39_SLOTS_DIR:-/Volumes/macMini/darkpyonix/darkpyonix-core/.scratch/build-slots}"
slot_sh="${FR39_SLOT_SH:-$slots_dir/slot.sh}"
slot_count=3
# run.sh records a load above 1.0 as it is and says the verdict is only to be read from a
# run on an idle machine. This is that line, applied before the run rather than after.
default_load_limit=1.0
load_limit="${FR39_LOAD_LIMIT:-$default_load_limit}"
min_free_gb=15
if [[ -n "${FR39_DF_PATH:-}" ]]; then
    df_path="$FR39_DF_PATH"
elif [[ -d /Volumes/macMini ]]; then
    df_path=/Volumes/macMini
else
    df_path="$repo_root"
fi
settle_seconds="${FR39_SETTLE_SECONDS:-300}"
not_measured="The real Renderer's apply time. The harness times the Host (handler, recomposition, batch encoding) and a Rust model of the Renderer's apply to its node table; what the Kotlin interpreter adds is not in these numbers."

say() { printf '%s\n' "$*"; }
fail() {
    printf 'fail  %s\n' "$1" >&2
    shift
    local line
    for line in "$@"; do printf '      %s\n' "$line" >&2; done
    exit 1
}
loud() {
    printf '\n'
    printf '!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n'
    printf 'WARNING  %s\n' "$@"
    printf '!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n'
    printf '\n'
}
usage() {
    sed -n '2,13p' "$self" | sed 's/^# \{0,1\}//' >&2
    echo "usage: see above" >&2
    exit 2
}

# The scenarios a parity log lists as violations, space separated.
parity_violations() { sed -n "s/^violation \([^:]*\):.*/\1/p" "$1" | paste -sd " " -; }

# ----- readings ----------------------------------------------------------------------------

# The 1-minute load average, the same reading run.sh records per scenario.
load_now() {
    if [[ -n "${FR39_FAKE_LOAD:-}" ]]; then
        echo "$FR39_FAKE_LOAD"
    elif [[ -r /proc/loadavg ]]; then
        cut -d' ' -f1 /proc/loadavg
    else
        sysctl -n vm.loadavg | awk '{print $2}'
    fi
}

is_number() { [[ "$1" =~ ^[0-9]+(\.[0-9]+)?$ ]]; }

# Whether $1 < $2, as decimals.
below() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a < b) }'; }

free_gb() { df -Pk "$df_path" | awk 'NR == 2 { print int($4 / 1024 / 1024) }'; }

# free, stale, or busy, with the owner line for the last two.
slot_state() {
    local dir="$slots_dir/slot$1" owner pid
    if [[ ! -d "$dir" ]]; then
        echo "free"
        return
    fi
    if [[ ! -f "$dir/owner" ]]; then
        echo "busy (being taken right now; no owner written yet)"
        return
    fi
    owner="$(head -1 "$dir/owner" 2>/dev/null || true)"
    pid="${owner%% *}"
    if [[ "$pid" =~ ^[0-9]+$ ]] && ! kill -0 "$pid" 2>/dev/null; then
        echo "stale (owner $pid is gone, so slot.sh reclaims it): $owner"
    else
        echo "busy: $owner"
    fi
}

# The slot directory slot.sh itself writes to. It is fixed inside slot.sh, so a slot
# directory named here that differs from it would check one set of slots and take another.
slot_sh_dir() { sed -n 's/^D=//p' "$slot_sh" | head -1; }

sha256() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        sha256sum "$1" | cut -d' ' -f1
    fi
}

# ----- static checks ----------------------------------------------------------------------

check_tools() {
    local missing=() tool
    for tool in bash git jq cargo rustc awk df sed; do
        command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
    done
    ((${#missing[@]} == 0)) || fail "missing tools: ${missing[*]}"
    say "ok    tools: bash git jq cargo rustc awk df sed"
}

# The same agreement run.sh and parity.sh insist on, checked before any slot is taken.
check_pins() {
    [[ -f "$pin" ]] || fail "no pin at $pin"
    # shellcheck source=/dev/null
    source "$pin"
    local name
    for name in FR39_BASELINE_REPO FR39_BASELINE_REV FR39_BASELINE_COMPOSE_RUST_REV; do
        [[ -n "${!name:-}" ]] || fail "$pin does not set $name"
    done
    [[ "$FR39_BASELINE_REV" =~ ^[0-9a-f]{40}$ ]] ||
        fail "the pinned dioxus-compose rev is not a full commit id: $FR39_BASELINE_REV"
    [[ "$FR39_BASELINE_COMPOSE_RUST_REV" =~ ^[0-9a-f]{40}$ ]] ||
        fail "the pinned compose-rust rev is not a full commit id: $FR39_BASELINE_COMPOSE_RUST_REV"
    local named
    named="$(grep -c "rev = \"$FR39_BASELINE_REV\"" "$bench/baseline/Cargo.toml" || true)"
    [[ "$named" -ge 1 ]] ||
        fail "baseline/Cargo.toml does not build the pinned dioxus-compose $FR39_BASELINE_REV"
    local other
    other="$(grep -oE 'rev = "[0-9a-f]+"' "$bench/baseline/Cargo.toml" | grep -v "$FR39_BASELINE_REV" || true)"
    [[ -z "$other" ]] || fail "baseline/Cargo.toml names another rev as well: $other"
    say "ok    baseline pinned: dioxus-compose ${FR39_BASELINE_REV:0:8}, compose-rust ${FR39_BASELINE_COMPOSE_RUST_REV:0:8}"
}

check_bench_scripts() {
    local script
    for script in run.sh parity.sh; do
        [[ -f "$bench/$script" ]] || fail "the bench has no $script at $bench"
    done
    # Where run.sh writes is where this script reads the result back from.
    grep -q -- '--record "$repo_root/compose-rust/benches/baseline.json"' "$bench/run.sh" ||
        fail "run.sh no longer records into compose-rust/benches/baseline.json" \
            "This script reads the result from there; change both together."
    say "ok    bench scripts: run.sh and parity.sh, recording into compose-rust/benches/baseline.json"
}

check_slot_wrapper() {
    [[ -d "$slots_dir" && -r "$slots_dir" ]] || fail "cannot read the build slot directory $slots_dir"
    [[ -r "$slot_sh" && -x "$slot_sh" ]] || fail "cannot read or run the slot wrapper $slot_sh"
    local fixed
    fixed="$(slot_sh_dir)"
    [[ "$fixed" == "$slots_dir" ]] ||
        fail "$slot_sh takes slots in ${fixed:-an unnamed directory}, not $slots_dir" \
            "The check would read one set of slots and the run would take another."
    local count
    count="$(sed -n 's/^N=//p' "$slot_sh" | head -1)"
    [[ "$count" == "$slot_count" ]] ||
        fail "$slot_sh has ${count:-an unknown number of} slots, and this script takes $slot_count"
    say "ok    slot wrapper: $slot_sh, $slot_count slots in $slots_dir"
}

check_target_dir() {
    if [[ -n "${CARGO_TARGET_DIR:-}${CARGO_BUILD_TARGET_DIR:-}" ]]; then
        say "note  CARGO_TARGET_DIR is set; the run unsets it so the bench builds into its own target/"
    fi
    local config
    for config in "$HOME/.cargo/config.toml" "$HOME/.cargo/config"; do
        if [[ -f "$config" ]] && grep -qE '^\s*target-dir\s*=' "$config"; then
            fail "$config sets target-dir" \
                "The bench has to build into compose-rust/benches/fr39/target, which run.sh runs from."
        fi
    done
}

check_free_space() {
    local free
    free="$(free_gb)"
    is_number "$free" || fail "could not read the free space on $df_path"
    if ((free < min_free_gb)); then
        fail "${free}G free on $df_path, under the ${min_free_gb}G the build needs" \
            "A full volume stops every build on the machine. Free some space first."
    fi
    say "ok    free space: ${free}G on $df_path (at least ${min_free_gb}G)"
}

# Without a lock file the first build resolves every dependency, which needs crates.io and
# GitHub. With one, everything it names may already be on disk, so a failed probe is only
# a warning.
check_network() {
    command -v curl >/dev/null 2>&1 || {
        say "note  curl is missing; cannot tell whether crates.io and GitHub are reachable"
        return
    }
    local unreachable=() url
    for url in https://index.crates.io/config.json https://github.com; do
        curl -sSf -o /dev/null --max-time 15 "$url" 2>/dev/null || unreachable+=("$url")
    done
    if ((${#unreachable[@]} == 0)); then
        say "ok    network: crates.io and GitHub answer"
    elif [[ -f "$bench/Cargo.lock" ]]; then
        say "note  unreachable: ${unreachable[*]}; the lock file exists, so the build may still resolve offline"
    else
        fail "unreachable: ${unreachable[*]}" \
            "The bench has no Cargo.lock yet, so its first build has to resolve dependencies online."
    fi
}

# Prints every slot and the load, and returns non-zero when the machine is not quiet.
quiet_check() {
    local quiet=0 i state load
    for ((i = 1; i <= slot_count; i++)); do
        state="$(slot_state "$i")"
        case "$state" in
            free) say "ok    slot$i is free" ;;
            stale*) say "ok    slot$i is $state" ;;
            *)
                say "no    slot$i is $state"
                quiet=1
                ;;
        esac
    done
    is_number "$load_limit" || fail "FR39_LOAD_LIMIT is not a number: $load_limit"
    load="$(load_now)"
    is_number "$load" || fail "could not read the load average (got '$load')"
    if [[ "$load_limit" != "$default_load_limit" ]]; then
        loud "The load limit is $load_limit, not the default $default_load_limit (FR39_LOAD_LIMIT)." \
            "A run started above the default is recorded as such, and its verdict is not one" \
            "to report: the verdict is read from a run on an idle machine."
    fi
    if below "$load" "$load_limit"; then
        say "ok    load average $load is under $load_limit"
    else
        say "no    load average $load is not under $load_limit"
        quiet=1
    fi
    return "$quiet"
}

# ----- the record ---------------------------------------------------------------------------

# The machine, as far as this platform says.
machine_json() {
    local model chip cpus memory os kernel
    kernel="$(uname -sr)"
    if [[ "$(uname -s)" == Darwin ]]; then
        model="$(sysctl -n hw.model 2>/dev/null || echo unknown)"
        chip="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)"
        cpus="$(sysctl -n hw.ncpu 2>/dev/null || echo 0)"
        memory="$(sysctl -n hw.memsize 2>/dev/null || echo 0)"
        os="$(sw_vers -productName 2>/dev/null) $(sw_vers -productVersion 2>/dev/null) ($(sw_vers -buildVersion 2>/dev/null))"
    else
        model="$(cat /sys/devices/virtual/dmi/id/product_name 2>/dev/null || uname -m)"
        chip="$(sed -n 's/^model name\s*:\s*//p' /proc/cpuinfo 2>/dev/null | head -1)"
        [[ -n "$chip" ]] || chip="$(uname -m)"
        cpus="$(nproc 2>/dev/null || echo 0)"
        memory="$(awk '/^MemTotal:/ { print $2 * 1024 }' /proc/meminfo 2>/dev/null || echo 0)"
        os="$( (. /etc/os-release 2>/dev/null && echo "$PRETTY_NAME") || uname -s)"
    fi
    jq -n --arg model "$model" --arg chip "$chip" --argjson cpus "${cpus:-0}" \
        --argjson memory "${memory:-0}" --arg os "$os" --arg kernel "$kernel" \
        --arg arch "$(uname -m)" \
        '{machine_model: $model, chip: $chip, cpu_count: $cpus, memory_bytes: $memory,
          os_version: $os, kernel: $kernel, architecture: $arch}'
}

# conditions_json <load at start> <raw dir> <parity dir> <log dir> <started> <finished> <toolchain mode>
#                 [scenarios parity.sh listed as violations, space separated]
conditions_json() {
    local at_start="$1" raw_dir="$2" parity_dir="$3" log_dir="$4" started="$5" finished="$6"
    local rustc_version cargo_version
    if [[ "$7" == read ]]; then
        rustc_version="$(rustc -vV 2>&1 | tr '\n' ';' | sed 's/;$//')"
        cargo_version="$(cargo -V 2>&1)"
    else
        rustc_version="not read by --check"
        cargo_version="not read by --check"
    fi
    local commit dirty lock_sha profile_release
    commit="$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || echo unknown)"
    dirty="$(git -C "$repo_root" status --porcelain -- compose-rust samples \
        ':!compose-rust/benches/baseline.json' ':!compose-rust/benches/fr39/Cargo.lock' 2>/dev/null |
        head -50 || true)"
    if [[ -f "$bench/Cargo.lock" ]]; then
        lock_sha="$(sha256 "$bench/Cargo.lock")"
    else
        lock_sha="no Cargo.lock"
    fi
    profile_release="$(awk '/^\[profile\.release\]/ { inside = 1; next } /^\[/ { inside = 0 } inside && NF' \
        "$bench/Cargo.toml" | tr '\n' ';' | sed 's/;$//')"
    local overridden=false
    [[ "$load_limit" != "$default_load_limit" ]] && overridden=true
    machine_json | jq \
        --arg date "$started" --arg finished "$finished" \
        --arg candidate_repo "$(git -C "$repo_root" remote get-url origin 2>/dev/null || echo unknown)" \
        --arg commit "$commit" --arg dirty "$dirty" \
        --arg baseline_repo "$FR39_BASELINE_REPO" --arg baseline_rev "$FR39_BASELINE_REV" \
        --arg compose_rust_rev "$FR39_BASELINE_COMPOSE_RUST_REV" \
        --arg rustc "$rustc_version" --arg cargo "$cargo_version" \
        --argjson at_start "$at_start" --argjson limit "$load_limit" \
        --argjson default_limit "$default_load_limit" --argjson overridden "$overridden" \
        --arg raw_dir "$raw_dir" --arg parity_dir "$parity_dir" --arg log_dir "$log_dir" \
        --arg lock "$lock_sha" --arg profile_release "$profile_release" \
        --arg not_measured "$not_measured" \
        --arg slots "$slots_dir" \
        --arg parity_violations "${8:-}" \
        '. + {
            date: $date,
            finished: $finished,
            toolchain: {rustc: $rustc, cargo: $cargo},
            candidate: {repo: $candidate_repo, commit: $commit,
                        dirty: ($dirty != ""), dirty_paths: ($dirty | split("\n") | map(select(. != "")))},
            baseline: {repo: $baseline_repo, rev: $baseline_rev, compose_rust_rev: $compose_rust_rev},
            load_average: {at_start: $at_start, limit: $limit, default_limit: $default_limit,
                           limit_overridden: $overridden},
            build: {cargo_build_jobs: 2, cargo_profile_dev_debug: 0, profile: "release",
                    profile_release: $profile_release,
                    target_dir: "compose-rust/benches/fr39/target"},
            build_slots: {directory: $slots, held: 3},
            raw_dir: $raw_dir, parity_dir: $parity_dir, log_dir: $log_dir,
            cargo_lock_sha256: $lock,
            parity_record_violations: ($parity_violations | split(" ") | map(select(. != ""))),
            not_measured: $not_measured
        }'
}

# Adds the conditions and the verdict to the fr39 entry run.sh's comparison wrote.
augment_record() {
    local file="$1" conditions="$2" tmp
    tmp="$file.tmp"
    jq --slurpfile c "$conditions" '
        .fr39 as $f
        | ($f.load_average | tostring | split(" ") | map(select(length > 0) | tonumber?)) as $loads
        | ($f.runs | map(.iterations) | unique) as $iterations
        | ($f.runs | map(.warmup) | unique) as $warmup
        | ($f.comparisons | map(select(.valid | not) | .name)) as $invalid
        | ($f.comparisons | map(select(.p50_ratio < 1 or .p99_ratio < 1) | .name)) as $slower
        | ($f.record_violations // [] | map(.scenario)) as $violations
        | ($f.target_met and ($violations | length) == 0) as $met
        | .fr39.conditions = ($c[0] + {
            iterations: (if ($iterations | length) == 1 then $iterations[0] else $iterations end),
            warmup: (if ($warmup | length) == 1 then $warmup[0] else $warmup end)
          })
        | .fr39.conditions.load_average += {
            during_run: $loads,
            max_during_run: ($loads | max),
            idle_throughout: (($loads | length) > 0 and ($loads | max) < $c[0].load_average.default_limit)
          }
        | .fr39.verdict = {
            met: $met,
            geometric_mean_p50_multiple: $f.geometric_mean_p50_ratio,
            geometric_mean_p99_multiple: $f.geometric_mean_p99_ratio,
            target_p50_multiple: 10,
            target_p99_multiple: 5,
            invalid_scenarios: $invalid,
            slower_than_baseline: $slower,
            record_violations: $violations,
            line: ("target " + (if $met then "met" else "not met" end)
                + ": geometric mean of p50 multiples "
                + ($f.geometric_mean_p50_ratio * 100 | round / 100 | tostring)
                + " (target 10), of p99 multiples "
                + ($f.geometric_mean_p99_ratio * 100 | round / 100 | tostring)
                + " (target 5); " + ($invalid | length | tostring) + " invalid, "
                + ($slower | length | tostring) + " slower than the baseline"
                + (if ($violations | length) == 0 then "" else "; record violations"
                   + " (more records or bytes than the baseline): " + ($violations | join(", ")) end))
          }
    ' "$file" > "$tmp"
    mv "$tmp" "$file"
}

# Prints each required field that is missing, and returns non-zero if any is.
validate_record() {
    local file="$1"
    jq -e . "$file" >/dev/null 2>&1 || {
        say "the record at $file is not JSON"
        return 1
    }
    local missing
    missing="$(jq -r '
        [ ["fr39","machine"], ["fr39","load_average"], ["fr39","baseline_commit"],
          ["fr39","raw_runs"], ["fr39","runs"], ["fr39","comparisons"],
          ["fr39","geometric_mean_p50_ratio"], ["fr39","geometric_mean_p99_ratio"],
          ["fr39","target_met"],
          ["fr39","conditions","date"], ["fr39","conditions","machine_model"],
          ["fr39","conditions","chip"], ["fr39","conditions","cpu_count"],
          ["fr39","conditions","memory_bytes"], ["fr39","conditions","os_version"],
          ["fr39","conditions","kernel"],
          ["fr39","conditions","toolchain","rustc"], ["fr39","conditions","toolchain","cargo"],
          ["fr39","conditions","candidate","repo"], ["fr39","conditions","candidate","commit"],
          ["fr39","conditions","candidate","dirty"],
          ["fr39","conditions","baseline","repo"], ["fr39","conditions","baseline","rev"],
          ["fr39","conditions","baseline","compose_rust_rev"],
          ["fr39","conditions","iterations"], ["fr39","conditions","warmup"],
          ["fr39","conditions","load_average","at_start"],
          ["fr39","conditions","load_average","limit"],
          ["fr39","conditions","load_average","limit_overridden"],
          ["fr39","conditions","load_average","during_run"],
          ["fr39","conditions","load_average","idle_throughout"],
          ["fr39","conditions","raw_dir"], ["fr39","conditions","parity_dir"],
          ["fr39","conditions","log_dir"],
          ["fr39","conditions","build","cargo_build_jobs"],
          ["fr39","conditions","build","profile"],
          ["fr39","conditions","cargo_lock_sha256"], ["fr39","conditions","not_measured"],
          ["fr39","verdict","met"], ["fr39","verdict","geometric_mean_p50_multiple"],
          ["fr39","verdict","geometric_mean_p99_multiple"], ["fr39","verdict","line"],
          ["fr39","verdict","record_violations"] ] as $required
        | . as $doc
        | ($required[] | select(. as $p | $doc | getpath($p) == null) | join(".")),
          (if ($doc.fr39.runs | length) == 0 then "fr39.runs is empty" else empty end),
          ($doc.fr39.runs[]? | select((has("iterations") and has("warmup") and has("p50_ns")
              and has("p99_ns") and has("max_ns")) | not)
              | "fr39.runs[" + (.scenario // "?") + "/" + (.path // "?") + "] lacks p50, p99, max or the pass counts"),
          ($doc.fr39.comparisons[]? | select((has("baseline_p50_ns") and has("candidate_p50_ns")
              and has("baseline_p99_ns") and has("candidate_p99_ns") and has("baseline_max_ns")
              and has("candidate_max_ns") and has("p50_ratio") and has("p99_ratio")) | not)
              | "fr39.comparisons[" + (.name // "?") + "] lacks a time or a multiple")
    ' "$file")"
    if [[ -n "$missing" ]]; then
        say "the record at $file is missing:"
        sed 's/^/      /' <<<"$missing"
        return 1
    fi
}

print_summary() {
    local file="$1"
    say ""
    say "Times are one whole interaction (Host plus the model apply), in microseconds."
    jq -r '.fr39.comparisons[] | [.name, .baseline_p50_ns, .candidate_p50_ns, .p50_ratio,
            .baseline_p99_ns, .candidate_p99_ns, .p99_ratio,
            (if (.valid | not) then "INVALID: " + .reason
             elif .record_violation then "VIOLATION: more records or bytes than the baseline"
             else "ok" end)] | @tsv' "$file" |
        awk -F'\t' '
            BEGIN {
                printf "%-18s %12s %12s %8s %12s %12s %8s  %s\n", "scenario", "baseline p50",
                    "candidate p50", "x p50", "baseline p99", "candidate p99", "x p99", "valid"
            }
            {
                printf "%-18s %12.1f %12.1f %8.2f %12.1f %12.1f %8.2f  %s\n", $1, $2 / 1000,
                    $3 / 1000, $4, $5 / 1000, $6 / 1000, $7, $8
            }'
    say ""
    say "$(jq -r '.fr39.verdict.line' "$file")"
    local violations
    violations="$(jq -r '.fr39.record_violations // [] | .[] | "\(.scenario): "
        + "\(.candidate_records) records against \(.baseline_records) (+\(.excess_records)), "
        + "\(.candidate_bytes) bytes against \(.baseline_bytes) (+\(.excess_bytes))"' "$file")"
    if [[ -n "$violations" ]]; then
        loud "These scenarios broke the rule that the candidate sends no more records or bytes" \
            "than the baseline. Their timings are recorded, and the target is not met:" \
            "${violations//$'\n'/; }"
    fi
    if [[ "$(jq -r '.fr39.conditions.load_average.idle_throughout' "$file")" != true ]]; then
        loud "The load average reached $(jq -r '.fr39.conditions.load_average.max_during_run' "$file") during the run, not under $default_load_limit throughout." \
            "The run is recorded with that fact. The verdict is read from a run on an idle machine."
    fi
    if [[ "$(jq -r '.fr39.conditions.load_average.limit_overridden' "$file")" == true ]]; then
        loud "This run started under an overridden load limit; the record says so."
    fi
    if [[ "$(jq -r '.fr39.conditions.candidate.dirty' "$file")" == true ]]; then
        loud "The checkout had uncommitted changes under compose-rust/ or samples/," \
            "so the recorded commit does not fully identify what was measured."
    fi
    say ""
    say "Not measured: $not_measured"
}

# Compares a re-run with a recorded result, scenario by scenario.
compare_records() {
    local recorded="$1" rerun="$2" file
    for file in "$recorded" "$rerun"; do
        jq -e '.fr39.comparisons | length > 0' "$file" >/dev/null 2>&1 ||
            fail "$file holds no fr39 comparisons"
    done
    say "recorded: $recorded"
    say "re-run:   $rerun"
    say ""
    local field
    for field in date candidate.commit baseline.rev cargo_lock_sha256 machine_model chip os_version; do
        jq -r -n --slurpfile a "$recorded" --slurpfile b "$rerun" --arg f "$field" '
            ($f | split(".")) as $p
            | ($a[0].fr39.conditions // {} | getpath($p) // "not recorded" | tostring) as $x
            | ($b[0].fr39.conditions // {} | getpath($p) // "not recorded" | tostring) as $y
            | "\($f): \($x)" + (if $x == $y then " (same)" else " -> \($y)" end)'
    done
    say ""
    jq -r -n --slurpfile a "$recorded" --slurpfile b "$rerun" '
        def field($c; $k): if $c == null then "absent" else ($c[$k] | tostring) end;
        ($a[0].fr39.comparisons | map({key: .name, value: .}) | from_entries) as $A
        | ($b[0].fr39.comparisons | map({key: .name, value: .}) | from_entries) as $B
        | ($a[0].fr39.comparisons + $b[0].fr39.comparisons | map(.name)
            | reduce .[] as $n ([]; if index([$n]) then . else . + [$n] end))[] as $n
        | [$n, field($A[$n]; "p50_ratio"), field($B[$n]; "p50_ratio"),
           field($A[$n]; "p99_ratio"), field($B[$n]; "p99_ratio"),
           field($A[$n]; "valid"), field($B[$n]; "valid"),
           field($A[$n]; "candidate_mutations"), field($B[$n]; "candidate_mutations"),
           field($A[$n]; "candidate_bytes"), field($B[$n]; "candidate_bytes")] | @tsv' |
        awk -F'\t' '
            function change(a, b) {
                if (a == "absent" || b == "absent" || a + 0 == 0) return "n/a"
                return sprintf("%+.1f%%", (b - a) / a * 100)
            }
            BEGIN {
                printf "%-18s %9s %9s %8s %9s %9s %8s %-11s %-13s %s\n", "scenario", "x p50",
                    "x p50 now", "change", "x p99", "x p99 now", "change", "valid",
                    "records", "bytes"
            }
            {
                printf "%-18s %9.2f %9.2f %8s %9.2f %9.2f %8s %-11s %-13s %s\n", $1, $2, $3,
                    change($2, $3), $4, $5, change($4, $5), $6 "/" $7, $8 "/" $9, $10 "/" $11
            }'
    say ""
    say "The requirement states no tolerance for a re-run's timings, so the changes above"
    say "are reported as they are and not judged. What is compared exactly is categorical:"
    local verdict_a verdict_b
    verdict_a="$(jq -r '.fr39.target_met' "$recorded")"
    verdict_b="$(jq -r '.fr39.target_met' "$rerun")"
    local status=0
    if [[ "$verdict_a" == "$verdict_b" ]]; then
        say "  verdict: the same (target met: $verdict_a)"
    else
        say "  verdict differs: recorded target met $verdict_a, re-run $verdict_b"
        status=1
    fi
    if jq -e -n --slurpfile a "$recorded" --slurpfile b "$rerun" '
        ($a[0].fr39.comparisons | map({key: .name, value: .valid}) | from_entries)
        == ($b[0].fr39.comparisons | map({key: .name, value: .valid}) | from_entries)' >/dev/null; then
        say "  scenarios and their validity: the same"
    else
        say "  scenarios or their validity differ between the two records"
        status=1
    fi
    say "  record and byte counts: listed above; the streamed reply's chunking follows the"
    say "  clock, so a difference there is not by itself a failure to reproduce"
    return "$status"
}

# ----- the canned sample --check writes a record from -------------------------------------

canned_sample() {
    cat <<'JSON'
{
  "fr39": {
    "machine": "canned sample for --check, not a measurement",
    "load_average": "0.40 0.41 0.42 0.43",
    "baseline_commit": "canned",
    "raw_runs": ["baseline-sweep_1.json", "baseline-chat_streaming.json",
                 "candidate-sweep_1.json", "candidate-chat_streaming.json"],
    "runs": [
      {"scenario": "sweep_1", "path": "dioxus", "iterations": 50, "warmup": 5,
       "p50_ns": 40000, "p99_ns": 90000, "max_ns": 120000, "host_p50_ns": 35000,
       "apply_p50_ns": 5000, "mutations": 20, "bytes": 800},
      {"scenario": "sweep_1", "path": "compose", "iterations": 50, "warmup": 5,
       "p50_ns": 8000, "p99_ns": 20000, "max_ns": 30000, "host_p50_ns": 6000,
       "apply_p50_ns": 2000, "mutations": 20, "bytes": 800},
      {"scenario": "chat_streaming", "path": "dioxus", "iterations": 50, "warmup": 5,
       "p50_ns": 60000, "p99_ns": 150000, "max_ns": 200000, "host_p50_ns": 50000,
       "apply_p50_ns": 10000, "mutations": 300, "bytes": 12000},
      {"scenario": "chat_streaming", "path": "compose", "iterations": 50, "warmup": 5,
       "p50_ns": 12000, "p99_ns": 40000, "max_ns": 50000, "host_p50_ns": 9000,
       "apply_p50_ns": 3000, "mutations": 290, "bytes": 11000}
    ],
    "comparisons": [
      {"name": "sweep_1", "valid": true, "reason": "",
       "baseline_p50_ns": 40000, "baseline_p99_ns": 90000, "baseline_max_ns": 120000,
       "candidate_p50_ns": 8000, "candidate_p99_ns": 20000, "candidate_max_ns": 30000,
       "p50_ratio": 5.0, "p99_ratio": 4.5, "baseline_mutations": 20,
       "candidate_mutations": 20, "baseline_bytes": 800, "candidate_bytes": 800},
      {"name": "chat_streaming", "valid": true, "reason": "",
       "baseline_p50_ns": 60000, "baseline_p99_ns": 150000, "baseline_max_ns": 200000,
       "candidate_p50_ns": 12000, "candidate_p99_ns": 40000, "candidate_max_ns": 50000,
       "p50_ratio": 5.0, "p99_ratio": 3.75, "baseline_mutations": 300,
       "candidate_mutations": 290, "baseline_bytes": 12000, "candidate_bytes": 11000}
    ],
    "geometric_mean_p50_ratio": 5.0,
    "geometric_mean_p99_ratio": 4.108,
    "target_met": false
  }
}
JSON
}

# ----- modes ------------------------------------------------------------------------------

mode=measure
verify_file=""
case "${1:-}" in
    "") ;;
    --check | --preflight | --prepare | --prepare-inside | --inside)
        mode="${1#--}"
        if [[ "$mode" == inside && "${2:-}" == --verify ]]; then
            verify_file="${3:-}"
            [[ -n "$verify_file" ]] || usage
        elif (($# > 1)); then
            usage
        fi
        ;;
    --verify)
        mode=verify
        verify_file="${2:-}"
        [[ -n "$verify_file" && $# -eq 2 ]] || usage
        ;;
    --compare-records)
        [[ $# -eq 3 ]] || usage
        compare_records "$2" "$3"
        exit $?
        ;;
    -h | --help) usage ;;
    *) usage ;;
esac

# A faked load reading exists so the gate can be tested. It must never let a real run start.
if [[ -n "${FR39_FAKE_LOAD:-}" && "$mode" != check && "$mode" != preflight ]]; then
    fail "FR39_FAKE_LOAD is set; it is for --check and --preflight only" \
        "A run started on a faked reading would record a load nobody measured."
fi

case "$mode" in
    check)
        say "Checking the one-command measurement; nothing is built or measured."
        check_tools
        check_pins
        check_bench_scripts
        check_slot_wrapper
        check_target_dir
        free="$(free_gb)"
        is_number "$free" || fail "could not read the free space on $df_path"
        if ((free < min_free_gb)); then
            say "note  ${free}G free on $df_path; a run would stop until there are ${min_free_gb}G"
        else
            say "ok    free space: ${free}G on $df_path"
        fi
        if quiet_check; then
            say "ok    the machine is quiet enough to measure right now"
        else
            say "note  the machine is not quiet enough to measure right now; a run would refuse"
        fi
        say "note  network not probed by --check; the run probes crates.io and GitHub"
        iterations="$(sed -n 's/^iterations="${1:-\([0-9]*\)}"$/\1/p' "$bench/run.sh")"
        warmup="$(sed -n 's/^warmup="${2:-\([0-9]*\)}"$/\1/p' "$bench/run.sh")"
        [[ -n "$iterations" && -n "$warmup" ]] ||
            fail "could not read run.sh's default iterations and warm-ups"
        say "ok    run.sh measures $iterations passes after $warmup warm-ups per scenario and path"

        stamp="$(date +%Y%m%d-%H%M%S)"
        dir="$scratch/check-$stamp-$$"
        mkdir -p "$dir"
        canned_sample > "$dir/baseline.json"
        conditions_json "$(load_now)" "$dir (canned)" "$dir (canned)" "$dir" \
            "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" check \
            > "$dir/conditions.json"
        augment_record "$dir/baseline.json" "$dir/conditions.json"
        validate_record "$dir/baseline.json" || fail "the record writer left fields out"
        compare_records "$dir/baseline.json" "$dir/baseline.json" > "$dir/compare.txt" ||
            fail "the record comparison does not accept a record against itself" "$(cat "$dir/compare.txt")"
        say "ok    the record writer produced every field; sample record: $dir/baseline.json"
        say "check passed"
        ;;

    preflight)
        if quiet_check; then
            say "quiet enough to measure"
        else
            say "not quiet enough to measure"
            exit 1
        fi
        ;;

    measure | verify)
        if [[ "$mode" == verify ]]; then
            [[ -f "$verify_file" ]] || fail "no recorded result at $verify_file"
            verify_file="$(cd "$(dirname "$verify_file")" && pwd)/$(basename "$verify_file")"
            jq -e '.fr39.comparisons | length > 0' "$verify_file" >/dev/null 2>&1 ||
                fail "$verify_file holds no fr39 comparisons to verify against"
        fi
        check_tools
        check_pins
        check_bench_scripts
        check_slot_wrapper
        check_target_dir
        check_free_space
        check_network
        quiet_check || fail "the machine is not quiet enough to measure; nothing was started" \
            "Wait until no build holds a slot and the load has settled, then run this again."
        say ""
        say "Taking all three build slots for the whole run; they are freed when it ends."
        if [[ "$mode" == verify ]]; then
            exec "$slot_sh" "$slot_sh" "$slot_sh" "$self" --inside --verify "$verify_file"
        fi
        exec "$slot_sh" "$slot_sh" "$slot_sh" "$self" --inside
        ;;

    prepare)
        check_tools
        check_pins
        check_bench_scripts
        check_slot_wrapper
        check_target_dir
        check_free_space
        check_network
        say ""
        say "Building the bench and checking parity under one build slot."
        exec "$slot_sh" "$self" --prepare-inside
        ;;

    prepare-inside)
        unset CARGO_TARGET_DIR CARGO_BUILD_TARGET_DIR
        export CARGO_BUILD_JOBS=2 CARGO_PROFILE_DEV_DEBUG=0
        dir="$scratch/prepare-$(date +%Y%m%d-%H%M%S)"
        mkdir -p "$dir"
        set +e
        bash "$bench/parity.sh" 2>&1 | tee "$dir/parity.log"
        status="${PIPESTATUS[0]}"
        set -e
        if [[ "$status" != 0 && "$status" != 3 ]]; then
            fail "parity.sh failed (exit $status); its output is above and in $dir/parity.log" \
                "The measurement would stop at the same place. Fix this before the freeze."
        fi
        say ""
        say "Prepared: the bench is built and both paths build the same trees."
        violations="$(parity_violations "$dir/parity.log")"
        [[ -z "$violations" ]] ||
            loud "Sending more records or bytes than the baseline: $violations" \
                "The measurement goes on with these recorded as violations; the target is not met."
        say "During the freeze, run: scripts/bench/fr39-measure.sh"
        ;;

    inside)
        # Started by the nested slot.sh calls. Measure only while all three are ours.
        held=0
        for ((i = 1; i <= slot_count; i++)); do
            owner="$(head -1 "$slots_dir/slot$i/owner" 2>/dev/null || true)"
            pid="${owner%% *}"
            if [[ "$owner" == *fr39-measure.sh* && "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
                held=$((held + 1))
            fi
        done
        ((held == slot_count)) ||
            fail "this run does not hold all three build slots ($held of $slot_count)" \
                "Start it as scripts/bench/fr39-measure.sh, which takes them first."
        check_free_space
        # shellcheck source=/dev/null
        source "$pin"
        unset CARGO_TARGET_DIR CARGO_BUILD_TARGET_DIR
        export CARGO_BUILD_JOBS=2 CARGO_PROFILE_DEV_DEBUG=0

        started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        stamp="$(date +%Y%m%d-%H%M%S)"
        log_dir="$scratch/measure-$stamp"
        mkdir -p "$log_dir"
        at_start="$(load_now)"
        say "Holding all three build slots. Load average at start: $at_start. Logs: $log_dir"

        backup=""
        if [[ -n "$verify_file" ]]; then
            cp "$verify_file" "$log_dir/recorded.json"
            if [[ -f "$record" ]]; then
                backup="$log_dir/baseline.json.before"
                cp "$record" "$backup"
            fi
        fi

        # Parity first: a run whose trees differ is not a comparison, and there is no point
        # timing it. Exit 3 is equal trees with more records or bytes: recorded, not fatal.
        before_parity="$(ls -1 "$scratch" 2>/dev/null | grep '^parity-' || true)"
        set +e
        bash "$bench/parity.sh" 2>&1 | tee "$log_dir/parity.log"
        status="${PIPESTATUS[0]}"
        set -e
        if [[ "$status" != 0 && "$status" != 3 ]]; then
            fail "parity.sh failed (exit $status); nothing was measured" \
                "Its output is above and in $log_dir/parity.log."
        fi
        parity_dir="$(comm -13 <(echo "$before_parity") <(ls -1 "$scratch" | grep '^parity-' || true) | tail -1)"
        parity_dir="${parity_dir:+$scratch/$parity_dir}"
        violations="$(parity_violations "$log_dir/parity.log")"

        # The build just ran on this machine, and the load average remembers it for a
        # minute or two. Measuring before it settles would time the build's tail.
        waited=0
        while ! below "$(load_now)" "$load_limit" && ((waited < settle_seconds)); do
            ((waited % 30 == 0)) && say "waiting for the load to settle: $(load_now) (limit $load_limit, ${waited}s of ${settle_seconds}s)"
            sleep 5
            waited=$((waited + 5))
        done
        if ! below "$(load_now)" "$load_limit"; then
            loud "The load average is $(load_now) after ${settle_seconds}s, not under $load_limit." \
                "Measuring anyway, as run.sh does; the record carries the load of every scenario."
        fi

        before_runs="$(ls -1 "$scratch" 2>/dev/null || true)"
        set +e
        bash "$bench/run.sh" 2>&1 | tee "$log_dir/run.log"
        status="${PIPESTATUS[0]}"
        set -e
        finished="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

        # run.sh exits non-zero both when it fails and when the target is not met, so the
        # record is what says which: a fresh fr39 entry pointing into a new run directory.
        [[ -f "$record" ]] || fail "run.sh wrote no record (exit $status); see $log_dir/run.log"
        first_raw="$(jq -r '.fr39.raw_runs[0] // empty' "$record" 2>/dev/null || true)"
        raw_dir="$(dirname "${first_raw:-/nonexistent/x}")"
        if [[ -z "$first_raw" || ! -d "$raw_dir" ]] || grep -qx "$(basename "$raw_dir")" <<<"$before_runs"; then
            fail "run.sh did not record a new measurement (exit $status)" \
                "Its output is above and in $log_dir/run.log."
        fi

        conditions_json "$at_start" "$raw_dir" "${parity_dir:-not found}" "$log_dir" \
            "$started" "$finished" read "$violations" > "$log_dir/conditions.json"
        augment_record "$record" "$log_dir/conditions.json"
        validate_record "$record" || fail "the record is missing fields; see above"
        cp "$log_dir/parity.log" "$raw_dir/measure-parity.log"
        cp "$log_dir/run.log" "$raw_dir/measure-run.log"
        [[ -f "$bench/Cargo.lock" ]] && cp "$bench/Cargo.lock" "$raw_dir/Cargo.lock"
        cp "$record" "$raw_dir/baseline.json"

        say ""
        say "Recorded: $record"
        say "Raw runs: $raw_dir"
        print_summary "$record"

        if [[ -n "$verify_file" ]]; then
            # A re-run never replaces the result it checks.
            mv "$record" "$log_dir/rerun.json"
            if [[ -n "$backup" ]]; then
                cp "$backup" "$record"
            fi
            say ""
            say "Comparing this re-run with the recorded result."
            compare_records "$log_dir/recorded.json" "$log_dir/rerun.json"
            exit $?
        fi
        say ""
        say "Commit compose-rust/benches/baseline.json and compose-rust/benches/fr39/Cargo.lock"
        say "together, so the session that verifies builds the same dependency versions."
        ;;
esac
