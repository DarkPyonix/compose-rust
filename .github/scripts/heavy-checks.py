"""Heavy CI on demand: the merge gate, and the lint that keeps the workflows honest.

Some workflows build Compose or the renderer ahead of time and take half an hour to two hours
each. They no longer run on every push to a pull request, because a busy day queued dozens of
them behind each other. They run when someone asks for them: the `run-heavy-ci` label on the
pull request, or a manual dispatch on its branch. That is a change of when they run, not of
whether they must pass, and this script is what holds the second half.

A heavy workflow is one with a job whose name is "Heavy checks requested". Every other job in
it depends on that job, so when nobody asked, the whole run is skipped, and the run cannot be
mistaken for a pass: a run only counts when that job itself succeeded.

  gate       The required check "Heavy checks passed on this head". For the pull request's
             current head commit it works out which heavy workflows apply (their own
             pull_request path filters against the files the pull request changes), then
             waits for them and passes only if each one has a successful run, with its
             request job succeeded, on exactly that commit. A new push moves the head and
             the old runs stop counting.
  lint       Fails if a heavy workflow can run without being asked for, or if a workflow
             that runs on pushes or pull requests has no concurrency group, or one that could
             cancel a run on develop or main. scripts/tests/ci-on-demand.test.sh runs it.
  self-test  Checks the path filter matcher against GitHub's documented examples.

No YAML library: the runners do not all have one, and the workflows here use a small, regular
subset (block mappings, block lists and one-line flow lists), which the reader below handles
and the lint keeps them to.
"""

import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

LABEL = "run-heavy-ci"
REQUEST_JOB_ID = "requested"
REQUEST_JOB_NAME = "Heavy checks requested"
# The request job's condition, word for word in every heavy workflow: any event that is not a
# pull request was asked for by whoever started it; on a pull request, the label going on, or
# a push or reopen while it is on. Another label going on is not a request.
REQUEST_IF = ("github.event_name != 'pull_request' || "
              f"(github.event.action == 'labeled' && github.event.label.name == '{LABEL}') || "
              "(github.event.action != 'labeled' && "
              f"contains(github.event.pull_request.labels.*.name, '{LABEL}'))")
GATE_FILE = "heavy-checks.yml"
GATE_JOB_NAME = "Heavy checks passed on this head"

# The workflows that cost tens of minutes or more per run. Each one present must be a heavy
# workflow in the sense above. A new workflow of that cost is added here when it is added.
KNOWN_HEAVY = [
    "test-compose-from-fork.yml",
    "test-kn-desktop-renderer.yml",
    "test-native-renderer.yml",
    "test-samples.yml",
    "test-windows-resize-metrics.yml",
    "test-windows-resize-probe.yml",
]

REPO_ROOT = Path(__file__).resolve().parents[2]
# The test points this at altered copies, to see the lint fail on them.
WORKFLOWS = Path(os.environ.get("HEAVY_CHECKS_WORKFLOWS", REPO_ROOT / ".github" / "workflows"))


# --- Reading the workflows -------------------------------------------------------------

def strip_comment(line):
    """Drops a trailing YAML comment, leaving '#' inside quotes alone."""
    out, quote = [], None
    for i, c in enumerate(line):
        if quote:
            if c == quote:
                quote = None
        elif c in "'\"":
            quote = c
        elif c == "#" and (i == 0 or line[i - 1] in " \t"):
            break
        out.append(c)
    return "".join(out).rstrip()


def unquote(s):
    s = s.strip()
    if len(s) >= 2 and s[0] == s[-1] and s[0] in "'\"":
        return s[1:-1]
    return s


def lines_of(text):
    """(indent, content) for each meaningful line, comments and blank lines dropped."""
    out = []
    for raw in text.splitlines():
        line = strip_comment(raw)
        if not line.strip():
            continue
        out.append((len(line) - len(line.lstrip(" ")), line.strip()))
    return out


def block(lines, start):
    """The lines indented under lines[start], and the index after them."""
    indent = lines[start][0]
    end = start + 1
    while end < len(lines) and lines[end][0] > indent:
        end += 1
    return lines[start + 1:end], end


def mapping(lines):
    """A block mapping one level deep: key -> (inline value, the lines under it)."""
    out = {}
    if not lines:
        return out
    top = min(i for i, _ in lines)
    i = 0
    while i < len(lines):
        indent, content = lines[i]
        if indent != top or content.startswith("- "):
            i += 1
            continue
        key, _, value = content.partition(":")
        sub, i = block(lines, i)
        out[unquote(key)] = (value.strip(), sub)
    return out


def as_list(value, sub):
    """A key's value as a list: a flow list, a block list or a single scalar."""
    if value.startswith("["):
        inner = value.strip("[]").strip()
        return [unquote(v) for v in inner.split(",") if v.strip()] if inner else []
    if value and value not in ("|", ">", "|-", ">-"):
        return [unquote(value)]
    return [unquote(c[2:]) for _, c in sub if c.startswith("- ")]


def as_text(value, sub):
    """A scalar, folded if it was written as a block (>- or |)."""
    if value in ("|", ">", "|-", ">-"):
        return " ".join(c for _, c in sub)
    return unquote(value)


class Workflow:
    def __init__(self, path):
        self.path = path
        self.file = path.name
        self.top = mapping(lines_of(path.read_text()))
        on_value, on_lines = self.top.get("on", self.top.get("true", ("", [])))
        if on_value:
            self.triggers = {t: ("", []) for t in as_list(on_value, [])}
        else:
            self.triggers = mapping(on_lines)
        self.jobs = {}
        for job_id, (_, job_lines) in mapping(self.top.get("jobs", ("", []))[1]).items():
            self.jobs[job_id] = mapping(job_lines)

    def name(self):
        return unquote(self.top.get("name", (self.file, []))[0])

    def trigger(self, event):
        if event not in self.triggers:
            return None
        return mapping(self.triggers[event][1])

    def concurrency(self):
        if "concurrency" not in self.top:
            return None
        value, sub = self.top["concurrency"]
        if value:
            return {"group": unquote(value), "cancel-in-progress": "false"}
        return {k: as_text(*v) for k, v in mapping(sub).items()}

    def job_name(self, job_id):
        job = self.jobs.get(job_id, {})
        return as_text(*job["name"]) if "name" in job else job_id

    def needs(self, job_id):
        job = self.jobs.get(job_id, {})
        return as_list(*job["needs"]) if "needs" in job else []

    def job_if(self, job_id):
        job = self.jobs.get(job_id, {})
        return as_text(*job["if"]) if "if" in job else ""

    def is_heavy(self):
        return (REQUEST_JOB_ID in self.jobs
                and self.job_name(REQUEST_JOB_ID) == REQUEST_JOB_NAME)

    def pr_filters(self):
        """(paths, paths_ignore) of the pull_request trigger; None for no filter."""
        pr = self.trigger("pull_request")
        if pr is None:
            return None, None
        paths = as_list(*pr["paths"]) if "paths" in pr else None
        ignore = as_list(*pr["paths-ignore"]) if "paths-ignore" in pr else None
        return paths, ignore


def workflows():
    return [Workflow(p) for p in sorted(WORKFLOWS.glob("*.yml"))]


# --- GitHub's path filters ------------------------------------------------------------

def glob_regex(pattern):
    """GitHub's filter pattern syntax: * stops at /, ** does not, ? is one character."""
    out, i = "", 0
    while i < len(pattern):
        c = pattern[i]
        if pattern.startswith("**", i):
            i += 2
            if pattern.startswith("/", i):
                out += "(?:.*/)?"
                i += 1
            else:
                out += ".*"
            continue
        if c == "*":
            out += "[^/]*"
        elif c == "?":
            out += "[^/]"
        else:
            out += re.escape(c)
        i += 1
    return re.compile(out + r"\Z")


def matches(patterns, path):
    """True if the last pattern that decides the path includes it; ! patterns exclude."""
    hit = False
    for pattern in patterns:
        if pattern.startswith("!"):
            if glob_regex(pattern[1:]).match(path):
                hit = False
        elif glob_regex(pattern).match(path):
            hit = True
    return hit


def applies(workflow, changed):
    """Whether the workflow's pull_request trigger would fire for these changed files."""
    paths, ignore = workflow.pr_filters()
    if paths is not None:
        return any(matches(paths, f) for f in changed)
    if ignore is not None:
        return any(not matches(ignore, f) for f in changed)
    return True


# --- The lint -------------------------------------------------------------------------

PR_CANCEL = "${{ github.event_name == 'pull_request' }}"


def lint():
    problems = []
    all_wf = workflows()
    by_file = {w.file: w for w in all_wf}

    for f in KNOWN_HEAVY:
        if f in by_file and not by_file[f].is_heavy():
            problems.append(f"{f}: runs for tens of minutes but has no '{REQUEST_JOB_ID}' job "
                            f"named '{REQUEST_JOB_NAME}', so it runs whether or not anyone asked")

    for w in all_wf:
        events = set(w.triggers)
        if "pull_request_target" in events:
            problems.append(f"{w.file}: pull_request_target runs fork code with write access")

        # Concurrency. A workflow only ever called from another one runs inside its caller's
        # run, and a group of its own would make the caller's parallel calls (three static
        # renderers in one run) cancel each other, so those are left without one.
        if events and events != {"workflow_call"}:
            conc = w.concurrency()
            if conc is None:
                problems.append(f"{w.file}: no concurrency group, so a superseded run keeps "
                                f"its runners busy")
            else:
                cancel = conc.get("cancel-in-progress", "false").strip()
                if cancel not in ("false", PR_CANCEL):
                    problems.append(f"{w.file}: cancel-in-progress is '{cancel}'; it has to be "
                                    f"false or {PR_CANCEL}, so a push to develop or main and a "
                                    f"manual run are never cancelled")
                if "pull_request" in events and "github.event.pull_request.number" not in conc.get("group", ""):
                    problems.append(f"{w.file}: the concurrency group is not keyed by the pull "
                                    f"request number, so a new push does not replace the older run")

        if not w.is_heavy():
            continue

        # A heavy workflow runs when asked, and every job waits for the request.
        if " ".join(w.job_if(REQUEST_JOB_ID).split()) != REQUEST_IF:
            problems.append(f"{w.file}: the request job does not test for the '{LABEL}' label "
                            f"the way heavy-checks.py expects: if: {REQUEST_IF}")
        for job_id in w.jobs:
            if job_id != REQUEST_JOB_ID and not reaches_request(w, job_id):
                problems.append(f"{w.file}: job '{job_id}' does not depend on "
                                f"'{REQUEST_JOB_ID}', so it runs without being asked")

        pr = w.trigger("pull_request")
        if pr is None:
            problems.append(f"{w.file}: a heavy workflow needs a pull_request trigger, so the "
                            f"label can start it on the pull request's head")
        else:
            types = as_list(*pr["types"]) if "types" in pr else []
            for t in ("labeled", "synchronize"):
                if t not in types:
                    problems.append(f"{w.file}: pull_request types lack '{t}'")
        if "workflow_dispatch" not in events:
            problems.append(f"{w.file}: a heavy workflow needs workflow_dispatch")
        for event in events - {"pull_request", "workflow_dispatch", "push", "schedule",
                               "workflow_call"}:
            problems.append(f"{w.file}: a heavy workflow should not run on '{event}'")
        push = w.trigger("push")
        if push is not None:
            branches = as_list(*push["branches"]) if "branches" in push else None
            if branches is None and "tags" not in push:
                problems.append(f"{w.file}: pushes to any branch start it")
            elif branches is not None and branches != ["main"]:
                problems.append(f"{w.file}: pushes to {branches} start it; only main (the "
                                f"publication) and tags may")

        group = (w.concurrency() or {}).get("group", "")
        if "github.event.label.name" not in group or "github.run_id" not in group:
            problems.append(f"{w.file}: the concurrency group does not set aside runs started "
                            f"by another label, so adding any label would cancel a heavy run "
                            f"in progress")

    gate = by_file.get(GATE_FILE)
    if gate is None:
        problems.append(f"{GATE_FILE} is missing, so nothing holds a merge until the heavy "
                        f"checks have passed")
    else:
        names = [gate.job_name(j) for j in gate.jobs]
        if GATE_JOB_NAME not in names:
            problems.append(f"{GATE_FILE}: no job named '{GATE_JOB_NAME}'")
        pr = gate.trigger("pull_request")
        types = as_list(*pr["types"]) if pr and "types" in pr else []
        for t in ("opened", "synchronize", "reopened", "labeled"):
            if t not in types:
                problems.append(f"{GATE_FILE}: pull_request types lack '{t}'")
        if pr and ("paths" in pr or "paths-ignore" in pr or "branches" in pr):
            problems.append(f"{GATE_FILE}: filtered, so some pull requests would have no gate")

    for p in problems:
        print(f"FAIL {p}", file=sys.stderr)
    if not problems:
        heavy = [w.file for w in all_wf if w.is_heavy()]
        print(f"ok    heavy workflows run on request only: {', '.join(heavy)}")
        print("ok    every workflow that runs on its own has a concurrency group")
    return 1 if problems else 0


def reaches_request(w, job_id, seen=None):
    seen = seen or set()
    if job_id in seen:
        return False
    seen.add(job_id)
    needs = w.needs(job_id)
    return REQUEST_JOB_ID in needs or any(reaches_request(w, n, seen) for n in needs if n in w.jobs)


# --- The gate -------------------------------------------------------------------------

def api(path, token):
    url = path if path.startswith("https://") else f"https://api.github.com{path}"
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    with urllib.request.urlopen(req, timeout=60) as resp:
        link = resp.headers.get("Link", "")
        nxt = re.search(r'<([^>]+)>;\s*rel="next"', link)
        return json.load(resp), (nxt.group(1) if nxt else None)


def api_all(path, token, key):
    items, nxt = [], path
    while nxt:
        page, nxt = api(nxt, token)
        items.extend(page[key] if key else page)
    return items


def gate():
    token = os.environ["GITHUB_TOKEN"]
    repo = os.environ["GITHUB_REPOSITORY"]
    number = os.environ["PR_NUMBER"]
    head = os.environ["HEAD_SHA"]
    labelled = os.environ.get("HAS_LABEL") == "true"
    poll = int(os.environ.get("POLL_SECONDS", "120"))
    # Runs started by the same event as this one take a little while to be listed.
    grace = int(os.environ.get("GRACE_SECONDS", "300"))

    changed = [f["filename"] for f in
               api_all(f"/repos/{repo}/pulls/{number}/files?per_page=100", token, None)]
    heavy = [w for w in workflows() if w.is_heavy()]
    required = [w for w in heavy if applies(w, changed)]

    print(f"head {head}, {len(changed)} changed file(s)")
    for w in heavy:
        print(f"  {'required' if w in required else 'not needed':10s} {w.name()} ({w.file})")
    if not required:
        summary("No heavy workflow's path filter matches this pull request, so none is needed.")
        return 0

    started = time.time()
    counted = {}
    while True:
        runs = api_all(f"/repos/{repo}/actions/runs?head_sha={head}&per_page=100", token,
                       "workflow_runs")
        state = {}
        for w in required:
            path = f".github/workflows/{w.file}"
            mine = [r for r in runs if r.get("path", "") == path
                    and r["head_sha"] == head]
            if w.file in counted:
                state[w.file] = ("passed", counted[w.file])
                continue
            passed = None
            for r in mine:
                if r["status"] == "completed" and r["conclusion"] == "success" and requested(r, repo, token):
                    passed = r
                    break
            if passed:
                counted[w.file] = passed["html_url"]
                state[w.file] = ("passed", passed["html_url"])
            elif any(r["status"] != "completed" for r in mine):
                state[w.file] = ("running", next(r["html_url"] for r in mine if r["status"] != "completed"))
            else:
                failed = [r for r in mine if r["conclusion"] not in ("success", "skipped")]
                if failed:
                    state[w.file] = ("failed", failed[0]["html_url"])
                else:
                    state[w.file] = ("missing", "")

        waiting = time.time() - started
        report = "\n".join(f"  {s:8s} {w.name()} ({w.file}) {u}"
                           for w in required for s, u in [state[w.file]])
        print(f"after {int(waiting)}s:\n{report}", flush=True)

        if all(state[w.file][0] == "passed" for w in required):
            summary("Every heavy workflow this pull request needs passed on its head:\n\n" + report)
            return 0
        # A failure fails the check at once instead of holding a runner until the rest end.
        if any(s == "running" for s, _ in state.values()) and not any(
                s == "failed" for s, _ in state.values()):
            time.sleep(poll)
            continue
        if any(s == "missing" for s, _ in state.values()) and labelled and waiting < grace:
            time.sleep(min(poll, 30))
            continue

        how = (f"Add the '{LABEL}' label to the pull request (gh pr edit {number} --add-label "
               f"{LABEL}), which runs them on this head and runs this check again. A new push "
               f"with the label on runs them again for the new head. If the label is already "
               f"on, remove and add it again, or re-run the failed runs and then this check.")
        summary("Heavy workflows have not passed on this head:\n\n" + report + "\n\n" + how)
        print(f"\nnot passed. {how}", file=sys.stderr)
        return 1


_requested_cache = {}


def requested(run, repo, token):
    """Whether the run's request job ran and succeeded, rather than being skipped."""
    key = (run["id"], run.get("run_attempt"))
    if key not in _requested_cache:
        jobs = api_all(f"/repos/{repo}/actions/runs/{run['id']}/jobs?filter=latest&per_page=100",
                       token, "jobs")
        _requested_cache[key] = any(j["name"] == REQUEST_JOB_NAME and j["conclusion"] == "success"
                                    for j in jobs)
    return _requested_cache[key]


def summary(text):
    out = os.environ.get("GITHUB_STEP_SUMMARY")
    if out:
        with open(out, "a") as f:
            f.write(text.replace("\n  ", "\n- ") + "\n")
    print(text)


# --- Self test ------------------------------------------------------------------------

def self_test():
    cases = [
        (["renderer/macos/**"], "renderer/macos/src/A.kt", True),
        (["renderer/macos/**"], "renderer/macosx/A.kt", False),
        (["renderer/kotlin"], "renderer/kotlin", True),
        (["renderer/kotlin"], "renderer/kotlin.bat", False),
        (["renderer/**/module.yaml"], "renderer/module.yaml", True),
        (["renderer/**/module.yaml"], "renderer/desktop/module.yaml", True),
        (["docs/*"], "docs/a/b.md", False),
        (["docs/*"], "docs/b.md", True),
        (["*.md"], "README.md", True),
        (["*.md"], "docs/README.md", False),
        (["**.md"], "docs/README.md", True),
        (["sub/**", "!sub/docs/**"], "sub/docs/a.md", False),
        (["sub/**", "!sub/docs/**"], "sub/src/a.rs", True),
        (["a/?.txt"], "a/b.txt", True),
        (["a/?.txt"], "a/bc.txt", False),
    ]
    bad = 0
    for patterns, path, want in cases:
        if matches(patterns, path) != want:
            print(f"FAIL {patterns} on {path}: expected {want}", file=sys.stderr)
            bad += 1

    text = """name: X
on:
  pull_request:
    types: [labeled, synchronize]
    paths:
      - 'a/**'   # comment
      - "b.txt"
  workflow_dispatch:
concurrency:
  group: x-${{ github.event.pull_request.number }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}
jobs:
  requested:
    name: Heavy checks requested
    if: >-
      github.event_name != 'pull_request' ||
      (github.event.action == 'labeled' && github.event.label.name == 'run-heavy-ci') ||
      (github.event.action != 'labeled' && contains(github.event.pull_request.labels.*.name, 'run-heavy-ci'))
    runs-on: ubuntu-latest
  build:
    needs: requested
    steps:
      - run: echo "# not a comment"
  after:
    needs: [build]
"""
    tmp = REPO_ROOT / ".scratch" / "heavy-checks-self-test"
    tmp.mkdir(parents=True, exist_ok=True)
    f = tmp / "x.yml"
    f.write_text(text)
    w = Workflow(f)
    checks = [
        (w.is_heavy(), True),
        (w.pr_filters(), (["a/**", "b.txt"], None)),
        (as_list(*w.trigger("pull_request")["types"]), ["labeled", "synchronize"]),
        (reaches_request(w, "after"), True),
        (" ".join(w.job_if("requested").split()), REQUEST_IF),
        (w.concurrency()["cancel-in-progress"], PR_CANCEL),
        (applies(w, ["c/d"]), False),
        (applies(w, ["a/x/y"]), True),
    ]
    for got, want in checks:
        if got != want:
            print(f"FAIL workflow reader: got {got!r}, expected {want!r}", file=sys.stderr)
            bad += 1
    f.unlink()
    tmp.rmdir()
    if not bad:
        print(f"ok    path filters and the workflow reader ({len(cases) + len(checks)} cases)")
    return 1 if bad else 0


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "gate":
        sys.exit(gate())
    if command == "lint":
        sys.exit(lint())
    if command == "self-test":
        sys.exit(self_test())
    print("usage: heavy-checks.py gate|lint|self-test", file=sys.stderr)
    sys.exit(2)
