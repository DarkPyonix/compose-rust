# fr39-codemod

Rewrites compose-rust widget builder chains into calls of the widget macros, for the move
off the builders before 1.0 (owner decision 2026-11-20).

```text
Column().modifier(m).spacing(8.0).content(|| { Text("a").font_size(14.0); });
Column!(modifier = m, spacing = 8.0, { Text!("a", font_size = 14.0); });
```

## Use

```sh
cargo build --release
./target/release/fr39-codemod --check <dir or file>...   # what would change, writes nothing
./target/release/fr39-codemod <dir or file>...           # rewrites in place
cargo fmt
```

Imports stay as they are: once the builders are gone the macros are `compose_rust::ui`
itself, so `use compose_rust::ui::*` brings them in. `--imports next` moves widget imports
to `ui::next` instead, for a crate built against a compose-rust from the migration, when
the macros lived there beside the builders.

`--crate-path crate` writes `crate::KeyEvent` instead of `::compose_rust::KeyEvent` in
the closure types it adds, for call sites inside compose-rust itself. The same binary
runs on any crate that uses compose-rust, such as Ember.

## What it does

The rewrite is textual. Every argument, closure body and comment inside an argument is
copied from the source unchanged; only the chain around them changes.

- A builder method becomes the parameter of the same name: `.font_size(14.0)` is
  `font_size = 14.0`. A method without arguments is `= true`, and one with several
  arguments is a tuple.
- The widget's main handler becomes the positional argument after its data, and an
  empty closure stands in when the chain had none. `TextField().text(t)` puts `t` first.
- Optional handlers and slots are wrapped in `Some(..)`, and a closure argument without
  a type gets the type the handler passes (rustc does not infer it inside `Some`).
- `.content(|| { .. })` becomes the trailing `{ .. }` block; a content closure with
  arguments (lazy lists) stays a named `content = ..`.
- `x_if(opt)` becomes `x = opt`, and the renames in `src/table.rs` apply. One of them changes
  the name: `.equal_height(x)` on a `Row` and `.equal_width(x)` on a `Column` are both
  `intrinsic_size = x`.
- A call through an alias of `ui` is a widget call, written `use compose_rust::ui as cu;`
  or `use compose_rust::ui::{self as cu, ..};`.
- A bare name is a widget unless the file defines it (`fn Badge(..)`) or imports it from
  somewhere other than `ui` (`use super::components::Select`, alacritty's `Column`). Next
  to a glob import from outside compose-rust (`use super::compat::*`) at the file's top, a bare widget name not
  imported from `ui` by name is left alone and reported, since the glob may bring in a
  function of that name.
- A widget called through a path (`compose_rust::ui::Text(..)`) becomes a block that
  brings the name into scope, because the macro calls its function by bare name:
  `{ use ::compose_rust::ui::Text; Text!(..) }`. A path through anything but `ui`, `ui::next` or a
  name the file gives `ui` (`use compose_rust::ui as cu;`) is not a widget: an enum variant
  such as `Value::Text` stays as it is.

## What it leaves alone and reports

- a builder whose value is used: bound with `let`, returned, or a match arm;
- the same parameter set twice on one chain;
- a comment inside a method's parentheses but outside its argument, which a rewrite would
  lose (a comment on the line before a method is carried in front of that method's
  argument);
- an optional handler whose closure has an untyped argument the table has no type for.

Chains inside other macros' token streams (`vec![..]`, `format!(..)`) are not parsed and
are not seen.

On develop (2026-11-20) a `--check` over `samples`, `compose-rust/tests`, `examples` and
`docs` reads 146 files: 97 would change, with 1257 chains and 94 imports rewritten, and
27 chains left alone for a hand edit.
