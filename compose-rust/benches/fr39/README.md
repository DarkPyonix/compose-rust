# The authoring path comparison

This workspace times the slot table path (`candidate`, this checkout) against the Dioxus
path (`baseline`, dioxus-compose at the commit `scripts/bench/fr39-baseline.env` pins) over
the frozen scenarios in `scenarios`. `parity.sh` checks that both paths build the same
Renderer trees. `run.sh` measures every scenario and writes the comparison into
`compose-rust/benches/baseline.json`.

## Measuring in one command

```sh
scripts/bench/fr39-measure.sh
```

It refuses to start unless the machine is idle. Idle means two things:

- All three machine-wide build slots (`slot1` to `slot3` beside `slot.sh` in
  darkpyonix-core's `.scratch/build-slots`) are free. A slot whose owner process is gone
  counts as free, because `slot.sh` reclaims it.
- The 1-minute load average is under 1.0, the line `run.sh` itself draws.
  `FR39_LOAD_LIMIT` raises it. The run then prints a warning, and the record says the
  limit was overridden.

Then it takes all three slots for the whole run, so no build starts beside it. The slots
are freed when the run ends. It also stops if `/Volumes/macMini` has less than 15G free.

Inside the slots, it runs `parity.sh` and stops there if the trees differ. A scenario
whose slot table path sends more records or bytes over the same trees does not stop the
run: it is listed as a violation in the summary and in the record (`record_violations`),
and the verdict is not met. Next it waits for the load from the build to
settle. Then it runs `run.sh` with its own defaults: 50 measured passes after 5 warm-ups,
per scenario and per path. The builds use `CARGO_BUILD_JOBS=2`, the release profile, and
this workspace's own `target/`.

The result goes to `compose-rust/benches/baseline.json`. `run.sh` writes the comparison,
and this script adds the conditions: date, machine, chip, memory, OS version, toolchain,
both revisions, the `Cargo.lock` hash, the load at the start and during each scenario, the
raw run directory and the verdict. The raw runs and both logs stay in
`.scratch/fr39/<timestamp>/`. At the end it prints each scenario's p50 and p99 on both
paths, their multiples, and the verdict line.

### The Renderer model and its roots

Both paths are applied to a Rust model of the Renderer's node table (`Model` in
`scenarios/src/lib.rs`). The trees compared after each step, and the widgets each step
clicks or types into, are read from that model.

The model keeps a node that is created and never inserted as a root, the way
`renderer/desktop/src/renderer/NodeTable.kt` does. Created nodes join the root list in
creation order. An `Insert` or `Move` takes a node off it and puts it under its parent, a
`Remove` drops it, and an `Insert` under node 0 goes into the same list at its index. No
record attaches an application's top node, so the Dioxus path at the pinned revision only
creates it, while the slot table path also inserts it under node 0. Both show the same
tree, with the same hash.

Until 2026-11-22 the model showed only nodes inserted under node 0. The Dioxus path's
screen was then empty in the model, and `parity.sh` stopped at `sweep_1`: nothing on
screen answered the `Increment` click. No measurement had run against the pinned baseline
before that correction. It changes no scenario and no verdict rule, and it adds no record,
so record and byte counts are unchanged. The tests at the end of `scenarios/src/lib.rs`
(`fr39_model_*`, `fr39_an_inferred_root_is_clickable_and_adds_no_record`) pin the rule
against NodeTable's behaviour.

One risk is open. The slot table path sends an `Insert` under node 0 for each top-level
node, and the Dioxus path does not. Parity refuses a scenario where the slot table path
sends more records or bytes, so it may refuse scenarios for that one record. If it does,
that is the project leader's decision, and the bench does not work around it.

What it does not measure: the real Renderer's apply time. The harness times the Host
(handler, recomposition, batch encoding) and a Rust model of the Renderer's apply. Whatever
the Kotlin interpreter adds is not in these numbers.

### How long it takes

These are estimates, not timings. Nobody has timed this bench yet.

| Step | Estimate |
| --- | --- |
| First build of both paths (Stylo, Blitz, Dioxus and two copies of compose-rust, two jobs) | 20 to 35 min |
| `parity.sh`, one pass of each scenario on each path | about 1 min |
| Load settling after the build | 1 to 3 min |
| `run.sh`, 55 passes per scenario and path | 15 to 20 min |

`chat_streaming` takes most of the measurement. Each pass waits for two replies, streamed
at 180 characters a second, and for the second of quiet that ends each one. That comes to
about 7 seconds a pass, or about 6.5 minutes per path.

A cold run does not fit a 40 minute freeze. Build ahead of it, under one ordinary build
slot:

```sh
scripts/bench/fr39-measure.sh --prepare
```

This runs `parity.sh` once. It builds everything and shows any parity failure before the
freeze, not during it. After that, the freeze run is about 20 to 25 minutes.

The workspace's `Cargo.lock` is committed, from the first builder run on 2026-11-21, so the
session that verifies builds the same dependency versions. If a run changes it, commit the
new one together with `baseline.json`.

After the model correction below, `--prepare` has to run again before any measurement:
the parity run that came before it stopped at the first scenario.

`scripts/bench/fr39-measure.sh --check` validates all of this without building or
measuring anything. It checks the tools, the pins, the slots, the load reading and the
record writer.

### Verifying from a second session

The result counts only after another session runs the same bench and gets the same
result. That session checks out the commit holding the recorded `baseline.json` and runs:

```sh
scripts/bench/fr39-measure.sh --verify compose-rust/benches/baseline.json
```

It measures the same way, under the same idle rules, and then compares itself with the
recorded file. It keeps its own result in `.scratch/fr39/measure-<timestamp>/rerun.json`
and puts the recorded file back unchanged.

The comparison lists every scenario's multiples in both runs and how far they moved. It
also shows the candidate commit, the baseline revision, the lock hash and the machine on
each side. The requirement states no numeric tolerance for a re-run, so the timing
differences are reported as they are and not judged. What is compared exactly is
categorical: the verdict, and which scenarios are valid. The command exits non-zero when
either differs.
