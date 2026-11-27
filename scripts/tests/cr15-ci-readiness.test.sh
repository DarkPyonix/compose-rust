#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

input_script="$repo_root/.github/scripts/check-linux-input-access.sh"
grep -q '@server=ibus' "$input_script" || {
    echo "fail  check-linux-input-access.sh does not wait for @server=ibus on XIM_SERVERS" >&2
    exit 1
}
grep -q 'engine_now.*==.*"hangul"' "$input_script" || {
    echo "fail  check-linux-input-access.sh does not verify ibus engine == hangul" >&2
    exit 1
}

fork_wf="$repo_root/.github/workflows/test-compose-from-fork.yml"
grep -q 'steps.compare.outcome' "$fork_wf" || {
    echo "fail  test-compose-from-fork.yml missing comparison step" >&2
    exit 1
}

msi_wf="$repo_root/.github/workflows/packager-macos.yml"
grep -q 'vpk pack.*--msi' "$msi_wf" || {
    echo "fail  packager-macos.yml missing Windows .msi build check" >&2
    exit 1
}

grep -q 'scripts/tests/msi-info.ps1' "$msi_wf" && grep -q 'instLocation' "$msi_wf" || {
    echo "fail  packager-macos.yml does not read the .msi Property table for PerUser and PerMachine" >&2
    exit 1
}

wack_wf="$repo_root/.github/workflows/package-windows.yml"
wack_host_wf="$repo_root/.github/workflows/package-windows-host.yml"
for wf in "$wack_wf" "$wack_host_wf"; do
    grep -q 'run-wack.ps1' "$wf" && grep -q 'RequirePass DPIAwarenessValidation' "$wf" || {
        echo "fail  $(basename "$wf") does not run WACK with DPIAwarenessValidation required" >&2
        exit 1
    }
done

linux_wf="$repo_root/.github/workflows/packager-linux.yml"
grep -q 'dpkg-deb --info' "$linux_wf" || {
    echo "fail  packager-linux.yml missing dpkg-deb --info check" >&2
    exit 1
}
grep -q 'rpm -qp --info' "$linux_wf" || {
    echo "fail  packager-linux.yml missing rpm -qp --info check" >&2
    exit 1
}

echo "ok    CR-15 ibus XIM readiness, .msi, WACK, and .deb/.rpm CI checks"
