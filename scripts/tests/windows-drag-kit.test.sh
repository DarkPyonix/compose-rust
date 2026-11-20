#!/usr/bin/env bash
# The Windows drag kit: what a person unzips on a real-GPU PC to drag a real sample's window
# edge on each of the window's three routes and compare them by eye. Nothing in it can run
# here, and the person who runs it cannot fix it, so its static facts are checked here.
#
# - Three launchers, 1-gdi.cmd, 2-dcomp-cpu-drag.cmd and 3-dcomp-gpu-drag.cmd. Each is CRLF
#   (cmd.exe misreads a batch file with bare LF line ends: a label or a block can be skipped),
#   ASCII, and kept so by .gitattributes, since nothing converts a file marked -text.
# - Each sets exactly the route variables it is for and DXC_REPORT_LATENCY=1, clears the other
#   route variable, keeps its settings to itself (setlocal), and starts the executable beside
#   it directly with its standard output and error in a log beside it named for the route.
#   Directly, not with `start`: started that way cmd.exe does not wait, and the redirection
#   applies to `start` rather than to the program. And without PowerShell, whose execution
#   policy a person's machine may refuse.
# - The Korean note is UTF-8 with a byte order mark, which is how Notepad knows it is UTF-8
#   on a machine whose code page is not, names each launcher, and fits on a page.
# - The English README.txt has the commit and the fork revision filled in by the workflow.
# - The workflow is called by ci.yml on the Windows renderer kit of the same run, runs only
#   when named, and uploads the kit as drag-kit-windows-x64 for 30 days.
set -uo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

python3 -I - "$root" <<'PY'
import pathlib, re, sys

root = pathlib.Path(sys.argv[1])
kit = root / '.github' / 'scripts' / 'drag-kit'
workflow = root / '.github' / 'workflows' / 'windows-drag-kit.yml'
ci = root / '.github' / 'workflows' / 'ci.yml'
suites = root / 'scripts' / 'ci-suites.sh'
failures = []

def check(ok, message):
    if ok:
        print('ok    ' + message)
    else:
        print('FAIL  ' + message, file=sys.stderr)
        failures.append(message)

# The route variables and what each launcher leaves them as. Empty is cleared.
routes = {
    '1-gdi.cmd': {'DXC_DCOMP': '0', 'DXC_DCOMP_GPU_DRAG': '', 'DXC_REPORT_LATENCY': '1'},
    '2-dcomp-cpu-drag.cmd': {'DXC_DCOMP': '', 'DXC_DCOMP_GPU_DRAG': '', 'DXC_REPORT_LATENCY': '1'},
    '3-dcomp-gpu-drag.cmd': {'DXC_DCOMP': '', 'DXC_DCOMP_GPU_DRAG': '1', 'DXC_REPORT_LATENCY': '1'},
}

for name, wanted in routes.items():
    path = kit / name
    if not path.is_file():
        check(False, f'{name} exists')
        continue
    data = path.read_bytes()
    check(data.endswith(b'\r\n') and b'\n' not in data.replace(b'\r\n', b''),
          f'{name} has CRLF line ends and no bare LF')
    check(all(byte < 128 for byte in data), f'{name} is ASCII')
    text = data.decode('ascii', 'replace').replace('\r\n', '\n')
    lines = [line.strip() for line in text.split('\n')]
    check(any(line.lower() == 'setlocal' or line.lower().startswith('setlocal ') for line in lines),
          f'{name} keeps its variables to itself (setlocal)')
    sets = {}
    for line in lines:
        m = re.match(r'(?i)^set\s+"(DXC_[A-Z0-9_]*)=([^"]*)"$', line)
        if m:
            sets[m.group(1)] = m.group(2)
        elif re.search(r'(?i)\bset\s+"?DXC_', line):
            check(False, f'{name} sets a DXC_ variable in the quoted form only: {line}')
    check(sets == wanted, f'{name} sets exactly {wanted} (it sets {sets})')
    check(any(re.match(r'(?i)^for %%\w in \("%~dp0sample-\*\.exe"\) do set "exe=%%~f\w"$', line) for line in lines),
          f'{name} starts the sample executable beside it')
    run = [line for line in lines if line.startswith('"%exe%"')]
    check(len(run) == 1, f'{name} starts the executable once, directly rather than through start')
    if run:
        line = run[0]
        check(re.search(r'>>?\s*"%log%"\s+2>&1\s*$', line, re.I) is not None,
              f'{name} sends standard output and standard error to the log ({line})')
    check(re.search(r'(?im)^\s*start\b', text) is None, f'{name} does not use start')
    check('powershell' not in text.lower(), f'{name} does not need PowerShell')
    check('%~dp0' in text, f'{name} finds the executable and writes the log beside itself (%~dp0)')
    route = name[:-len('.cmd')]
    log_line = next((line for line in lines if re.match(r'(?i)^set\s+"log=', line)), '')
    check(route in log_line and '%stamp%' in log_line and log_line.endswith('.log"'),
          f'{name} names its log for the route and a time ({log_line})')

attributes = kit / '.gitattributes'
check(attributes.is_file() and re.search(r'(?m)^\*\s+-text\b', attributes.read_text()) is not None,
      'the kit folder marks its files -text, so Git never changes their line ends')

note = kit / 'README-ko.txt'
if note.is_file():
    data = note.read_bytes()
    check(data.startswith(b'\xef\xbb\xbf'), 'the Korean note starts with the UTF-8 byte order mark')
    try:
        text = data[3:].decode('utf-8')
        check(True, 'the Korean note is UTF-8')
    except UnicodeDecodeError:
        text = ''
        check(False, 'the Korean note is UTF-8')
    check(re.search(r'[가-힣]', text) is not None, 'the Korean note is written in Korean')
    for name in routes:
        check(name in text, f'the Korean note names {name}')
    check(len(text.splitlines()) <= 60, f'the Korean note fits on one page ({len(text.splitlines())} lines)')
    check(chr(0x2014) not in text, 'the Korean note has no em dash')
else:
    check(False, 'README-ko.txt exists')

readme = kit / 'README.txt'
if readme.is_file():
    text = readme.read_text(encoding='ascii', errors='replace')
    check('@COMMIT@' in text and '@FORK_REVISION@' in text,
          'README.txt has the commit and the fork revision for the workflow to fill in')
else:
    check(False, 'README.txt exists')

if workflow.is_file():
    text = workflow.read_text(encoding='utf-8')
    on = re.search(r'^on:\n(.*?)(?=^[a-zA-Z_][\w-]*:|\Z)', text, re.M | re.S)
    check(on is not None and re.search(r'^  workflow_call:', on.group(1), re.M) is not None
          and re.search(r'^  (push|pull_request|schedule|workflow_dispatch):', on.group(1), re.M) is None,
          'the workflow is only called (by ci.yml)')
    steps = re.split(r'\n(?=      - )', text)
    upload = [step for step in steps if 'uses: actions/upload-artifact@' in step
              and re.search(r'^          name: drag-kit-windows-x64$', step, re.M)]
    check(len(upload) == 1, 'the workflow uploads the kit as drag-kit-windows-x64')
    check(len(upload) == 1 and re.search(r'^          retention-days: 30$', upload[0], re.M) is not None,
          'the kit is kept for 30 days')
    check('name: windows-static-renderer-kit' in text,
          'the samples link the Windows renderer kit ci.yml built in the same run')
    check('README-ko.txt' in text and '읽어주세요.txt' in text,
          'the Korean note ships as 읽어주세요.txt')
    for name in routes:
        check(name in text, f'the workflow ships and starts {name}')
    check('@COMMIT@' in text and '@FORK_REVISION@' in text and 'WINDOW_REVISION' in text,
          'the workflow fills in the commit and the window pin')
    check('DXC_D3D12_WARP' in text and 'first frame drawn' in text,
          'the workflow starts each launcher on the runner and waits for a drawn frame')
    check('dumpbin' in text, 'the workflow lists the DLLs each executable needs')
else:
    check(False, 'the workflow .github/workflows/windows-drag-kit.yml exists')

ci_text = ci.read_text()
job = re.search(r'^  windows_drag_kit:\n((?:    .*\n)*)', ci_text, re.M)
check(job is not None and 'uses: ./.github/workflows/windows-drag-kit.yml' in job.group(1)
      and re.search(r'needs: \[plan, windows_static_kit\]', job.group(1)) is not None,
      'ci.yml runs the kit as windows_drag_kit on the Windows renderer kit of the same run')
suites_text = suites.read_text()
check(re.search(r'^on_request=\(([^)]*\b)?windows_drag_kit\b', suites_text, re.M) is not None,
      'scripts/ci-suites.sh runs windows_drag_kit only when it is named')

if failures:
    print(f'{len(failures)} problem(s) in the Windows drag kit', file=sys.stderr)
    sys.exit(1)
print('all passed')
PY
