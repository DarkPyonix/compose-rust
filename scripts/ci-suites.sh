#!/usr/bin/env bash
# Usage: scripts/ci-suites.sh <suites>
#
# Which jobs of ci.yml a dispatch runs. <suites> is what the dispatch was given:
#
#   all      every job: what a release check runs, and the default, so a dispatch that says
#            nothing is still complete.
#   fast     the Rust, JVM and script tests on every system, the Windows C compile, and the
#            GraalVM renderer built and smoke tested on macOS and Linux: an answer in about two
#            hours instead of five, for checking a branch. Windows has no short renderer job:
#            its renderer is the Kotlin/Native static one, the longest job of a run, so it is
#            only in `all` or named on its own.
#   a,b,...  exactly these jobs, by their ci.yml names (test, test_samples, ...), and what they
#            need: a job that takes the Windows kit brings windows_static_kit with it.
#
# Prints one `name=true` or `name=false` line per job, for $GITHUB_OUTPUT. An unknown name is an
# error rather than a run that quietly checks less than was asked for.
set -euo pipefail

# Every job ci.yml runs, in its order. ci-suites.test.sh fails if ci.yml has a job this list
# does not name, or names one ci.yml does not have.
jobs=(
    test
    test_graalvm_renderer
    test_windows_resize_metrics
    windows_static_kit
    windows_static_consumer
    test_native_renderer
    test_windows_resize_probe
    test_windows_c
    package_windows_host
    packager_linux
    test_kn_desktop_renderer
    package_windows
    packager_macos
    test_compose_from_fork
    test_samples
)
fast=(test test_windows_c test_graalvm_renderer)
# The jobs that download the Windows renderer kit windows_static_kit builds for the run.
needs_windows_kit=(windows_static_consumer test_native_renderer package_windows_host test_samples)

asked="${1:-all}"
asked="${asked// /}"
[[ -n "$asked" ]] || asked=all

# A space between and around the names, so a membership test is one pattern match; bash 3.2,
# which macOS has, has no associative arrays.
selected=" "
select_job() { case "$selected" in *" $1 "*) ;; *) selected="$selected$1 " ;; esac; }
is_selected() { case "$selected" in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

case "$asked" in
    all) for job in "${jobs[@]}"; do select_job "$job"; done ;;
    fast) for job in "${fast[@]}"; do select_job "$job"; done ;;
    *)
        IFS=',' read -r -a names <<< "$asked"
        for name in "${names[@]}"; do
            [[ -n "$name" ]] || continue
            known=0
            for job in "${jobs[@]}"; do [[ "$job" == "$name" ]] && known=1; done
            if [[ "$known" == 0 ]]; then
                echo "ci-suites: '$name' is not a suite (all, fast) or a ci.yml job: ${jobs[*]}" >&2
                exit 1
            fi
            select_job "$name"
        done
        ;;
esac

for job in "${needs_windows_kit[@]}"; do
    if is_selected "$job"; then select_job windows_static_kit; fi
done

for job in "${jobs[@]}"; do
    if is_selected "$job"; then echo "$job=true"; else echo "$job=false"; fi
done
