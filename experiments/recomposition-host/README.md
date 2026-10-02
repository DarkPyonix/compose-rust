# What a Compose-style runtime on the Host could actually buy

The question behind this: the Host authors with `dioxus-core`, which discovers what changed
by comparing, and Compose discovers it by re-running the scopes that read the state that
moved. Replacing the first with the second would remove the comparison. This measures what
the comparison costs, because a change justified by a saving should know the size of it.

The recorded interaction number covers handler, diff and encoding together, and nothing
outside the call can separate them: the mutation sink writes wire records as the diff
walks, so they are one pass. `breakdown/` drives `dioxus-core` by hand instead, over two
VirtualDoms of the same component, one writing records and one writing nothing.

Numbers are in `results.json`. Run it with `cargo run --release -- 20000` in `breakdown/`.

## The answer, first

**The comparison is not where the time goes, and the encoder is not where it goes either.
Re-running a component scope is where it goes, and both models pay that.**

Per dynamic slot that a click changes:

| | ns |
| --- | --- |
| a slot whose value changed, in total | 2315 |
| the same slot with the value left alone, so nothing is emitted | 381 |
| what the wire encoder contributes to a changed slot | 30 |
| the rest, inside `dioxus-core`, before any sink is called | 1904 |

The encoder is 1.3 percent of a changed slot. The fixed-layout records, the four-byte
alignment the envelope insists on, the arena: none of it is a cost worth reasoning about at
this scale, and a change that makes the emit side cheaper has 30ns to win.

## The 1904ns is not the comparison

Three checks, each of which would have shown the comparison if it were there.

**It does not depend on the type of the value.** A changed number costs 1961ns against a
changed string's 1934ns. If the cost were comparing values, a `u32` and a `String` would
not agree to within 2 percent.

**It does not depend on the width of the props.** `Text` carries some twenty optional
fields. A component carrying one costs 2315ns per changed slot, slightly more rather than
less. Constructing and comparing props is the 245ns to 381ns paid by the slots that did
*not* change, and that is the part that behaves like a comparison: it is what an unchanged
slot costs, and it is 6 times cheaper than a changed one.

**It scales with changed slots and nothing else.** Both lines are straight from 1 slot to
129 (the medians in `results.json`), so there is no traversal proportional to the tree
underneath it. Dioxus is already doing work proportional to what changed. The template
splitting in `rsx!` has already taken the static structure out of the comparison, which is
the same thing Compose's groups do.

What is left is the flat price of a scope re-running: a new props value, a scope arena
entry, the generational boxes its hooks live in, and the child's own rsx built and handed
on. Compose re-runs its scopes too. `composer.changed(value)` is a comparison against the
slot table, and a composable whose parameters compare equal is skipped exactly as a
component whose props compare equal is skipped here. Neither model has a comparison the
other lacks.

## So the saving is not the one the change was for

Removing the reconciler removes the 30ns. Everything above it survives, because a
Compose-style runtime re-runs the same component bodies for the same reasons.

That does not mean a Compose-style runtime would perform the same. 1904ns to re-run one
scope is a large number: 4 changed slots fill a 120Hz frame's 10 percent overhead budget,
and 260 of them fill the 0.5ms the Host is allowed for an interaction. Compose's restart
scopes are lighter than this, and if a Rust runtime could re-run a scope in 200ns rather
than 2000ns that would be worth an order of magnitude on any screen that changes more than
a handful of slots at once.

But that is a different claim from the one this started with, and it is a claim about
`dioxus-core`'s scope machinery rather than about diffing. It also cuts the other way:
`dioxus-core` is mature and its templates are already doing the optimisation that matters
most, and a hand-rolled slot table without a compiler plugin to insert the groups is more
likely to be slower than faster on its first attempt.

## What this says about the decision

Not enough to make it, and enough to change what to ask.

- **The performance argument for removing the diff is dead.** It is 30ns per changed slot.
- **A performance argument about scope re-entry is open and larger than expected.** 1904ns
  per re-run is the number to attack, and attacking it does not require a new authoring
  model: it is worth first finding out what `dioxus-core` spends it on.
- **The reasons that survive are the ones that were never about speed**: one set of
  semantics across the boundary, and `LazyColumn`, which needs a windowing protocol because
  a tree comparison cannot keep virtualisation.

## Whether the groups can be hidden

They can. `positional/` is a slot table, a `#[composable]` macro that inserts the groups, and
eleven passing tests over components whose source contains none. The control beside them
shows the corruption a flat offset produces when a branch stops being taken, so the reason
for the groups is a result rather than a claim.

Re-running a scope costs 225ns there against `dioxus-core`'s 2287ns, which is a floor rather
than a comparison: that runtime has no wire encoding, no props and no widget schema. One of
the three is already measured and small (30ns per changed slot). Read `positional/README.md`
before quoting the number.

## What has not been done

**Where `dioxus-core`'s 1904ns per re-run goes.** This is now the first question rather than
the last, because it is the one whose answer could remove the reason to change anything: if
most of that number is avoidable in how this project uses Dioxus, the ten-fold gap closes
without a new authoring model.

Also untouched: whether the gap survives the real widget layer (forty seven properties,
fourteen modifier slots, six design systems), and state. There is no snapshot system here,
no equivalent of a signal, and nothing for a worker thread waking a composition, and the
boundary rules put all three on the Host's path.
