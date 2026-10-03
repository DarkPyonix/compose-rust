# FR-30 action key: unported salvage

This is unfinished work, recovered from the `adaptive-rules` agent worktree
(`dioxus-compose` branch `feat/adaptive-design-rules`, base `ecaae92a`) before that
worktree was deleted. It was never committed or compiled there, and it is not compiled
here either.

What it implemented:

- `ButtonKind { Standard, ActionKey }` and the `Button { kind }` prop in the Dioxus adapter;
- `ComponentRules.actionKeyShape()` in all seven design systems;
- `CaptionTitlePlacement { Bar, SystemCaption }` so a bar does not draw a title the
  platform caption already draws;
- `fr30_*` tests (Rust `design_primitives.rs`, Kotlin `BarTitleTest`,
  `DesignSystemDifferenceTest`).

**It used property tag 68 for `ButtonKind`. The SPEC now reserves tag 75.** The ported
patch rewrites the tag to 75, but the hunk that adds the property tag did not apply, so the
tag, the generated `Protocol.gen.kt` decoder entry, the schema hash and the vectors must be
regenerated when this is re-implemented.

On this branch the hunks that applied cleanly to the current layout are already in the
source tree (paths and Kotlin packages rewritten: `dioxus-compose/` to `compose-rust/` or
`adapters/dioxus/`, `dioxus-compose-renderer/` to `renderer/`, `dioxus.compose.*` to
`dev.darkpyonix.composerust.*`). That leaves the tree in a half-applied state that does
not build. Treat it as a starting point for re-implementation, not as working code.

Files here:

- `fr30-original.patch`: the diff exactly as it was in the old worktree;
- `fr30-ported.patch`: the same diff with paths, packages and the tag rewritten;
- `rejected/`: the `.rej` hunks that did not apply, under their current paths;
- `original/`: the old worktree's full versions of every file that had a rejected hunk,
  under their old paths (`.orig` suffix so the source scanners skip them).
