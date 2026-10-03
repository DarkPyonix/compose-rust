# AGENTS.md

Guidance for working in this repository.

## Project

compose-rust lets Rust code author declarative UI with **Dioxus** (`dioxus-core` VirtualDom, `rsx!`, hooks). An **AOT-compiled Compose Multiplatform** renderer draws the UI and handles text and IME.

- `compose-rust/`: Rust side (Host). Dioxus renderer crate, boundary shims, codegen.
- `renderer/`: Kotlin side (Renderer). Amper project. The schema interpreter
  lives in `desktop/src/renderer/`; `ios/src/shared/` symlinks the same files so there is one
  copy. `shared/` is the JVM development shell only, not the renderer.
- `design-systems/`: a separate Amper project holding the six design systems and the
  Liquid Glass material. It must never depend on the renderer, so it can be published alone.
- `docs/INTENT.md`: why the project exists, decisions (D1–D9), rejected alternatives.
- `docs/SPEC.md`: requirements (`FR-*`, `NFR-*`, `PR-*`) with acceptance criteria.
- `PROJECT.md`: scope, milestones (M0–M7), open questions.

## Spec Driven Development

1. **SPEC is the source of truth.** Before implementing a behavior, find its SPEC ID. If none exists, stop and ask for one rather than writing the behaviour and a requirement for it in the same breath (2). Once it is agreed, it lands in its own commit, before the code.
2. **Never edit `docs/SPEC.md`, `docs/INTENT.md` or `PROJECT.md` without being asked to.**
   Not a requirement, not an acceptance criterion, not a decision, not a status. Propose
   the change, say what it costs and what it buys, and wait. This holds even when the
   change looks like a correction, and it holds hardest when the code cannot do what the
   document says: the requirement is not the thing that gives way.

   This is written down because it happened. FR-19.2 says a bar's content sits on the same
   line as the window buttons. The code left a measured 15 pixel step, the way to fix it
   looked unreachable, and the rule was rewritten to describe what the code already did.
   The reachability was wrong as well, so the rewrite traded a real requirement for a
   false reason. A requirement that moves whenever the code cannot meet it records nothing.

   Fixing an ID that points at a renamed test, or a link that has rotted, is not this.
   Changing what the project promises is.
3. If code and SPEC disagree, fix the code. If the SPEC is wrong, say so and ask; do not
   edit it and carry on.
4. Decision changes go to `docs/INTENT.md` first, then SPEC, then code, and the decision
   itself is the owner's to make (2).
5. When a requirement's acceptance criteria are verified, update its status (`Draft` → `Agreed` → `Done`) in the same change that proves it. A status is a statement about evidence, so this one follows the evidence rather than needing to be asked for; every other edit to those documents does.
6. Resolved open questions are removed from `PROJECT.md` and recorded as decisions in INTENT/SPEC, once the question has actually been answered by the person who asked it.

## Branches

`develop` is where work happens. `release` and `main` are produced from it by
`scripts/publish-main.sh`, which strips `PROJECT.md`, `AGENTS.md`, `CLAUDE.md` and everything directly
under `docs/`.

1. **Publishing is one way: develop to release to main.** Never merge `release` or `main`
   back into `develop`. Their history contains the deletion of the planning documents, and
   merging it replays that deletion as if someone intended it.
2. **Start every branch and worktree from `develop`.** A worktree created from the default
   branch starts on `main`, which has neither the planning documents nor the recent work.
   Check with `git log --oneline -1` and `git reset --hard origin/develop` before touching
   anything. Merging `develop` in afterwards is not equivalent: the merge carries main's
   deletions with it.
3. `scripts/tests/planning-docs.test.sh` fails if those documents go missing, so the loss
   is caught rather than discovered weeks later.
4. **Every worktree builds into its own `target/`.** Two checkouts sharing one build
   directory run each other's binaries. Cargo leaves the package path out of the unit hash
   for a path package, so identical sources in two places are a single cache entry, and
   everything fixed at compile time (`env!("CARGO_MANIFEST_DIR")`, `include_str!`, what a
   build script left in `OUT_DIR`, the renderer's install name) comes from whichever
   checkout compiled first. A `cargo run --bin codegen` in the main checkout generated
   into an agent's worktree that way, and a sample built there came up with a black
   window. Run `scripts/setup-worktrees.sh --all` after adding a worktree, and never set
   `target-dir` or `CARGO_TARGET_DIR` anywhere that outlives a single command.
5. **Prune worktrees you are not using.** A worktree costs about 1GB once it is built
   and tested, and about 2GB once `scripts/check.sh` has run benchmarks in it. Ten of them filled a 349GB volume to 100% and took down every build then
   running, which is what sharing one build directory was trying to avoid.
   `scripts/setup-worktrees.sh` prints what each one costs and how much room is left.
6. **Start a background agent with `scripts/launch-agent.sh`.** It creates the worktree
   from `origin/develop` and gives it its own build directory.
7. **One worktree per agent, and never two workers in the same checkout.** Switching
   branches changes every file under that checkout, so a `git checkout` while an agent is
   working pulls the files out from under it. Work has been lost that way. Give a
   background agent its own worktree and leave that checkout alone until it finishes.

## Where files go

1. **Everything this project makes stays inside this repository.** Worktrees, experiments,
   probes, scratch files, build outputs, and anything downloaded to build or test with.
   Not `/tmp`, not a directory beside this checkout, not the home directory. The owner has
   to be able to find all of it in one place and remove it by removing one place, and has
   never agreed to anything being written anywhere else.
2. Where inside:
   - worktrees: `.claude/worktrees/<name>/` (ignored by git);
   - throwaway work, probes and downloads: `.scratch/<name>/` (ignored by git);
   - experiments worth keeping: `experiments/<name>/`, committed;
   - build outputs: `target/` and `build/`, where the tools already put them.
3. A change to Compose or skiko goes to `thisisthepy/compose-multiplatform-core-extended`
   (branch `extended`) as a commit. This repository uses a pinned commit of that fork
   (`renderer/scripts/build-compose.sh`,
   `renderer/scripts/compose-fork.changes`) and builds it inside `.scratch/`.
   No Compose or skiko change lives in this repository.
4. Caches a toolchain keeps for itself (`~/.cargo`, `~/.rustup`, `~/.gradle`, `~/.m2`,
   `~/.konan`, cargo-xwin's cache) belong to the tool and stay where it puts them.
   Downloading or installing a tool this project does not already use is not a cache: ask
   first, and put it inside `.scratch/`.
5. If a task seems to need a path outside the repository, ask before writing there. Being
   asked and told yes is the only exception, and it covers that path for that task.

This is written down because it happened. Probes and their builds went to `/tmp`, a Wine
download went to `/tmp`, and worktrees went to `../agent-runs`, and the owner had agreed to
none of it.

## Background agents

These bind the agent and whoever dispatches it equally. Both have been broken by the
person writing the task prompt, not by the agent reading it, so
`scripts/tests/agent-launchers.test.sh` checks that all three launchers state them and
the launchers inject them ahead of whatever the prompt says.

1. **A background agent never builds the renderer.** Not `./kotlin build`, not
   `./kotlin test`, not Amper, Gradle, dx or native-image, and nothing under
   `renderer/*/scripts/`. Those builds reach outside the worktree into
   caches every checkout on the machine shares (`~/.m2`, `~/.cache/JetBrains/Kotlin`,
   Gradle's), they cost tens of minutes each, and six agents were once told to run them
   and did, at the same time, which took the machine down.

   **Do not write a build command into a task prompt.** That is how it happened: the
   ban was spoken and never written down, so the next prompt asked for exactly the
   thing the ban forbade.

   An agent writes the Kotlin and writes its tests without watching them go green.
   Whoever merges the branch runs the renderer build and the Kotlin tests. That is a
   real cost and it is the deal: the Kotlin side is verified at the merge, not in the
   worktree. **Cargo is a build too: an agent runs no `cargo build`, `test`, `check`,
   `clippy` or `run`.** Rust builds alone have overloaded this machine (load 120 to 160)
   with several agents compiling at once. The owner's words: "서브 에이전트 알바들한테는
   러스트 빌드 시키지 마라고. 규정에도 추가시켜".

   **The one exception is a single temporary builder sub-agent, and the session does not
   build either.** The owner, 2026-10-03: "빌드 작업 니가 직접 하지 말고 서브 에이전트 하나
   임시로 만들어서 개한테 시켜야지", "니가 작업 붙잡고 있으면 다른 일들도 진행이 안되잖아".
   A session that holds a build holds up every other piece of work it is running, so it
   does not run builds or tests itself. It starts one builder for them with `--builder`
   (or `DXC_AGENT_BUILDER=1`) on any of the three launchers, and only one at a time. The
   builder builds and tests one command at a time with `CARGO_BUILD_JOBS=2`, in its own
   worktree and its own `target/`, never with a shared `CARGO_TARGET_DIR`, and edits no
   code beyond the minimal fixes a build needs, which it reports with the commands it ran
   and their output. Coding, research and documentation agents still build nothing. When
   no builder is needed, push and let CI build.

2. **A background agent never decides that part of its task is out of scope.** If the
   task says implement it, it gets implemented. No narrowing, no deferring, no "future
   work", no "1.1", no TODO standing in for the feature, and above all no writing that
   judgement into SPEC or INTENT as though it were settled. A whole section of the SPEC
   had to be withdrawn because exclusions accumulated that nobody had asked for.

   Disagreeing is allowed and wanted. Deliver everything else in full, then say in the
   final report what was not done and why. The failure is the silence, not the
   disagreement: a gap nobody mentions is found later by someone who assumed it was
   there.

   **The final report is not a licence.** Saying what was skipped does not discharge the
   obligation to do it. That clause is for work that turned out to be impossible or
   blocked, not for work judged not worth doing, and the difference is whether anything
   stopped you. "Nobody asked for it" and "the task did not name it" are not blockers.

   **Nothing that the thing cannot work without is out of scope.** The deliverable is the
   working whole, not the part the task happened to name. A platform whose renderer
   compiles into a library that nothing can link is not delivered; the build that produces
   it and the path that links it are part of it, named or not. The test is whether someone
   can use what you built without writing the missing piece themselves.

   This is written down because it happened, and because the rule above was already here
   when it did. A Linux window was built, and its report said a build script and the
   host-side link were "outside the task's stated scope" because writing them "seemed
   worse than naming it". They were the difference between a renderer and a directory of
   source files. The report was honest and the work was unfinished, and those are not
   alternatives.

3. **Whoever reads the report is bound by the same rule.** An "out of scope" in a report
   is not an answer to be relayed onward; it is a thing to send back. Passing it up as
   though the question were settled makes the reader the second person to decide it,
   which is exactly what rule 2 forbids the first from doing. The same incident above
   travelled a whole message further because it was repeated rather than caught.

## Writing

1. **Never use em dashes.** Not in docs, code comments, commit messages, pull request text or UI copy. Use a comma, a colon, parentheses, or start a new sentence. Hyphens in compound words and en dashes in numeric ranges are fine.
2. Language: `README.md` and the guide site (`docs/guide/`) are English, with translations under `docs/locales/` and `docs/guide/ko/`. The internal planning documents (`PROJECT.md`, `docs/INTENT.md`, `docs/SPEC.md`) are Korean. Code, code comments, scripts and this file are English.
3. **Never cite SPEC or INTENT from code.** No `(SPEC PR-4)`, no `NFR-8 needs this`, no
   `INTENT D9-macOS`, in comments, error messages, log lines or assertion text. Those
   documents are stripped from the published branch, so a citation points at nothing for
   most readers, and it goes stale the moment a requirement is renumbered. Worse, it reads
   as an explanation while explaining nothing: a reader who hits the error still does not
   know what went wrong.

   Say the actual reason instead. `"the protocol encodes strings as UTF-8 (SPEC PR-4)"`
   becomes `"this codec only encodes UTF-8; $name was requested"`. If the reason needs a
   paragraph, write the paragraph. The test is whether someone with no access to the
   planning documents can act on what you wrote.

   Two exceptions, both deliberate: **commit messages and pull request text** may reference
   requirement IDs, because they are addressed to people working in this repository, and
   **test names** (`fr4_set_prop_does_not_recompose_siblings`) keep theirs, because the
   traceability from a failing test to its requirement is the point of the TDD rules below.
   Anything a test *prints* follows the rule above.

## Test Driven Development

SDD says what to build; TDD is how it gets built. Tests come from SPEC acceptance criteria, so a requirement without a test is not done.

1. **Red, green, refactor.** Write the failing test first, make it pass with the simplest change, then clean up with the test still green.
2. **Name tests after the requirement**: `fr4_set_prop_does_not_recompose_siblings`, `pr2_batch_applies_atomically`. A reader should be able to go from a failing test to the SPEC line it defends.
3. **No production change without a test that would have caught its absence.** A bug fix starts with a test that reproduces the bug.
4. **Commit order**: the test and the code that makes it pass go in the same commit (the tree must build green at every commit). Say in the commit body which SPEC criterion it covers.
5. **Test through the public surface**: the Rust crate's API and C exports; the Renderer's interpreter and `HostConnection`. Do not assert on internals that the SPEC does not describe.
6. **Fakes, not mocks of our own protocol.** Use `FakeHostConnection` and the mock renderer; assert against the checked-in protocol vectors so both sides stay in lockstep.
7. **Performance is a test too.** §5.1 budgets are benchmarks with recorded numbers, and the allocation ceiling is an assertion, not a note.

Not everything can be automated. IME behaviour (§6) and accessibility (§7) are manual checklists run on the native-image build, and visual results are confirmed by running the app. When a requirement can only be checked by hand, say so in the SPEC item instead of leaving it untested silently.

## Hard constraints (INTENT §2)

Never introduce anything that violates these. If a task seems to require it, stop and ask.

- **No webview**: no WKWebView, WebView2, or WebKitGTK; no Tauri or wry.
- **No bundled JVM**: desktop ships as one executable per application with an AWT-free renderer linked in (Kotlin/Native on macOS, Linux and Windows; INTENT D4, NFR-14, NFR-15). GraalVM native-image remains only where a platform has no Kotlin/Native renderer yet. iOS ships as Kotlin/Native. The JVM is allowed only for the development shell.
- **No hand-written JNI or cinterop glue**: all boundary shims are generated from the Rust schema (FR-7).
- **Type safety on the Rust side**: schema types are defined in Rust as the single source; Kotlin types are generated.
- **Compose-quality text and IME**: never add a code path that bypasses Compose's platform text input.

## Architecture rules

- **Boundary model (PR-1)**: synchronous, same-thread direct calls, like JSI.
  - The VirtualDom runs on the Renderer UI thread.
  - Do not add async queues or ring buffers between Host and Renderer.
  - The batch buffer is a call argument, not a queue.
- **Boundary surface (PR-2)**: only primitives, pointers, and lengths cross it.
  - Keep it to the `compose_rust_host_*` / `compose_rust_renderer_*` functions.
  - Adding an entry point requires a SPEC change.
- **Threads (PR-3)**: no domain work on the UI thread.
  - PTY, network, streaming, and I/O run on Host worker threads.
  - Workers update Dioxus signals; the Host requests a frame internally. User code never calls boundary functions.
- **Encoding (PR-4)**: fixed-layout, zero-copy records generated by codegen. No serde formats (postcard, bincode, JSON) on the hot path.
- **UI-local state lives in Kotlin (D5)**:
  - `TextField` is uncontrolled.
  - Never round-trip IME composition text through Rust.
  - Scroll position, focus, and animation state stay in the Renderer.
- **Stable Compose API only (NFR-6)**: if an `@InternalComposeUiApi` or experimental API is unavoidable, isolate it in a single adapter file and pin the version.
- **Naming (PR-7)**: follow each ecosystem's conventions.
  - Rust: Dioxus names (`launch`, `use_*`, `VirtualDom`).
  - Kotlin: Compose names (`ComposeRustContent`, `rememberComposeRustHost`, `requestFrame`).
  - Widgets reuse Compose names (`Column`, `LazyColumn`).
  - C symbols use the `compose_rust_` prefix.
- **Frame budget (NFR-9, SPEC §5.1)**: performance must be indistinguishable from hand-written Compose.
  - Target: ≤ 10% frame-time overhead vs. a pure-Compose baseline.
  - Zero steady-state heap allocation in the Host.
  - No extra frames of input latency.
  - Treat budget regressions as bugs.
- **Crash isolation (NFR-7)**: protocol errors produce a `ProtocolError` event, never a process abort.
- **Web (PR-6)**: Kotlin/Wasm owns the single `WebAssembly.Memory`; Rust imports it and both read the arena in place. Function calls cross a generated JS forwarder (about 12 ns) because a browser cannot give you both direct binding and a shared memory. Never add serialisation, a data copy, an async queue or a thread hop.

## Commits

- **One logical change per commit.** Split work by feature or requirement. Do not mix SPEC edits, refactors, and features in one commit.
- Subject format: `<Type>: <imperative summary>`
  - `Feat`: new functionality
  - `Fix`: bug fix
  - `Refactor`: behavior-preserving code change
  - `Docs`: README, PROJECT, INTENT, SPEC, AGENTS.md
  - `Test`: tests only
  - `Chore`: build, tooling, dependencies, config
- Reference SPEC IDs in the subject or body when relevant, e.g. `Feat: Return handler result from dispatch_event (PR-2)`.
- **Do not add `Co-Authored-By` trailers or any AI attribution** to commits or PR descriptions.
- Commit only when asked, or when a task explicitly includes committing. Never force-push or rewrite published history without permission.
- Do not commit `.DS_Store`, build outputs (`target/`, `build/`, `.gradle/`, `.kotlin/`), or native-image artifacts.

## Verification

- A milestone is complete only when its SPEC acceptance criteria pass. Report failures with actual output; do not mark items `Done` on assumption.
- IME (SPEC §6) and accessibility (SPEC §7) checks run on the **native-image build**, not only on the JVM dev shell.
- Performance claims (call cost, frame time, RSS, binary size) require measured numbers, recorded alongside the relevant SPEC item.
