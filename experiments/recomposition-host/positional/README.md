# Can the groups be kept out of the code someone writes

A slot table stores what a call site remembered at an offset, and an offset is exactly what
a conditional invalidates. Groups are what turn an offset into an identity. Compose's
compiler plugin inserts them; Rust has no plugin, so the question is whether a proc macro
rewriting the body can do the same, and whether what is left is something a person would
agree to write.

`macro/` is the attribute. `runtime/` is a slot table small enough to read, its tests, and a
cost probe. `cargo test` and `cargo run --release --bin cost -- 20000`.

## The answer

**Yes, and the macro is not where the difficulty is.** Eleven tests pass over components
whose source contains no group of any kind:

```rust
#[composable]
fn with_branch(show: bool) {
    if show {
        let first = remember(|| String::from("first"));
        note(&first.borrow());
        first.borrow_mut().push('!');
    }
    let second = remember(|| String::from("second"));
    note(&second.borrow());
    second.borrow_mut().push('!');
}
```

`a_group_keeps_a_call_site_state_when_a_branch_before_it_disappears` runs that with `show`
true, then false, and the second call site goes on seeing its own value.

The reason that is worth testing rather than assuming is the test beside it.
`a_flat_offset_hands_one_call_site_another_ones_state` is the same component with state
addressed by a flat offset, which is what a positional table is before groups are added:
with the branch gone, the call site after it asks for offset 0 and is handed what the branch
left there. Both values are Strings, so nothing complains. That silence is the failure the
groups are for.

Loops, `match` arms, shortened loops discarding the iterations they lost, and a branch
starting from nothing when it is taken again all behave, and none of it is written by hand.

## What the attempt did get wrong, and it matters

The first version wrapped each branch body in a closure, because a branch is an expression
and the group has to close after the value is produced. That is wrong three ways, and only
one of them is loud:

- `continue` and `break` become `error[E0267]: cannot continue inside of a closure`, with
  the diagnostic pointing at `#[composable]` as the enclosing closure. A macro whose errors
  name themselves rather than the mistake is a macro nobody can use.
- `return` compiles and means something else. It leaves the closure, not the function, so a
  loop guarded by an early return keeps going. Nothing reports this.

The fix is to bind a guard whose `Drop` closes the group. A local with a destructor lives to
the end of its block and is dropped after the tail expression is evaluated, so the group
closes in the right place on the way out and closes anyway on `continue`, `break`, `return`,
`?` and an unwind. `tests/control_flow.rs` is those three cases, and they pass now.

This is the useful part of doing the spike rather than reasoning about it. The closure form
is the obvious way to write the rewrite, it is what was written, and it is wrong in a way
that would have been found by whoever first put a `continue` in a list body.

## What it costs to re-run a scope here

`dioxus-core` spends 1904ns on a slot whose value changed and 381ns on one that did not
(`../results.json`). The same shape through this runtime:

| | changed slot | unchanged slot |
| --- | --- | --- |
| this slot table | 225 ns | 189 ns |
| `dioxus-core` | 2287 ns | 381 ns |

**Read this as a floor and not as a comparison.** There is no wire encoding here, no widget
schema, no props struct, no hook but `remember`, one thread, and an owned tree rather than a
gap buffer, which is worse than Compose on movement and better on nothing. What it says is
that the floor of a slot table sits an order of magnitude below what `dioxus-core` currently
spends per scope, not that a real implementation would land there.

One of the missing pieces is already measured and small: the wire encoder is 30ns per
changed slot. The unmeasured ones are props and the widget schema.

## What is still not known

- **Where `dioxus-core`'s 1904ns goes.** If most of it is something avoidable in how this
  project uses Dioxus, the ten-fold gap closes without a new runtime, and that is much the
  cheaper answer. This should be found out before anything else.
- **Whether the gap survives contact with the real widget layer.** Six design systems, forty
  seven properties, fourteen modifier slots and the wire schema all have to go through
  whatever replaces the props struct.
- **Keys for lists.** Compose needs an explicit `key()` for items that reorder, and so does
  this. That is not a regression: `rsx!` already asks for `key:` today.
- **Everything about state.** No snapshot system, no equivalent of a signal, no worker thread
  waking a composition. The boundary rules put all three in the Host's path.

## With the real widget layer

`src/widgets.rs` adds what the first numbers lacked: `text` takes the same props as
`compose_rust::Text`, compares them the same way, and writes Create, Insert, SetProp,
SetModifier and Remove through the project's own `BatchEncoder`. `tests/wire.rs` checks the
records are the ones the Dioxus path sends for the same change. Same sweep, same run:

| | changed slot | unchanged slot | one-slot frame |
| --- | --- | --- | --- |
| slot table, real widget layer | 276 ns | 345 ns | 1.3 us |
| `dioxus-core` | 2275 ns | 381 ns | 7.3 us |

A changed slot is about eight times cheaper. An unchanged one costs the same: both compare
the full props and skip.

Still missing, so still not the final number: signals and scope invalidation (both sides
here re-run the root on every click), event dispatch, and `Move` when items reorder.
