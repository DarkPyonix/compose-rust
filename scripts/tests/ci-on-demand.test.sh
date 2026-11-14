#!/usr/bin/env bash
# Check that the leader's dispatch is the only development CI entry point.
set -eu
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 -I - "$root" <<'PY'
import pathlib, re, sys
root = pathlib.Path(sys.argv[1]) / '.github' / 'workflows'
ci = (root / 'ci.yml').read_text()
assert re.search(r'^  workflow_dispatch:', ci, re.M)
assert re.search(r'^  group: dev-ci$', ci, re.M)
assert re.search(r'^  cancel-in-progress: false$', ci, re.M)
for path in sorted(root.glob('*.yml')):
    text = path.read_text()
    if not re.search(r'uses: \./\.github/workflows/' + re.escape(path.name) + r'\s*$', ci, re.M):
        continue
    on = re.search(r'^on:\n(.*?)(?=^[a-zA-Z_][\w-]*:|\Z)', text, re.M | re.S)
    assert on and re.search(r'^  workflow_call:', on.group(1), re.M), path.name
    assert not re.search(r'^  (pull_request|schedule|workflow_dispatch):', on.group(1), re.M), path.name
    if re.search(r'^  push:', on.group(1), re.M):
        assert path.name in ('test-native-renderer.yml', 'test-samples.yml'), path.name
        assert re.search(r'^    tags:', on.group(1), re.M), path.name
print('ok    one manually dispatched development CI with reusable suites')
PY
