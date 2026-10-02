# Sample apps

Each of these is a real application written the way someone using this project would
write one: Rust only, `rsx!` and hooks, no Kotlin.

They exist to be used rather than admired. The widget vocabulary was chosen by reasoning
about what applications need, and reasoning is not evidence. Every place a sample has to
reach for an extension, work around a missing widget, or give up, is a finding about the
vocabulary, and belongs in an issue rather than in a workaround.

Each one is also clone coded from a real application, and the reference is part of the
requirement rather than a mood board: the pictures are in
`docs/references/design-systems/README.md` under "Sample Apps". The design follows the
platform the application is running on, and the layout follows the window size class, so
one declaration is a phone screen, a tablet screen and a desktop window.

| Sample | Clone coded from | What it exercises |
|---|---|---|
| `calculator` | The Windows, macOS and Deepin calculators | Dense button grids, `Row` and `Column` nesting, a memory register, numeric formatting, a tape that stands beside the keys or arrives in a sheet |
| `notepad` | The iOS and Windows memo applications | Multiline text editing, IME, file I/O from a worker thread, several open documents with the list beside the page or in a sheet |
| `todo` | A macOS task list and a mobile task scheduler | Lists with stable keys, checkboxes, filtering as a destination set, `LazyColumn` windowing over thousands of rows, file persistence from a worker thread |
| `chat` | Google Gemini | Conversations as a destination set, scrollback, a composer where Enter sends and Shift+Enter starts a new line, and a reply streamed in from a worker thread |

The four above are **adaptive**: they take whichever design system the platform picks, so
the same declaration is a Material 3 screen on Android and a Liquid Glass one on a Mac. The
ones below are **unified**. Each names one design system and draws the same way
everywhere, which is what an application does when the design is the product rather than
the platform's convention. All of them are rebuilds of published iOS designs, so all of
them name Cupertino.

| Sample | What it exercises |
|---|---|
| `minimal` | A playground: every control the schema has, all nine fills with the ink each carries, the type and corner ladders, and a `Canvas` drawing |
| `store` | A clothing shop: a carousel with page dots, a category strip, a two-up grid, a product page with sizes and a stepper, and a bag that adds up |
| `statistics` | Charts: a dial and a week of costs drawn with `Canvas`, both painted entirely in roles, on a tinted page |
| `selfcare` | A mood picker whose answers are colours, a drawn face per feeling, a chip grid, a windowing row of session cards and a week as a line |
| `podcast` | A player whose waveform is a `Canvas`, a cover per show, and a full-screen page that covers the navigation bar |
| `academic` | A grid of subject tiles, a stage strip, open and locked lessons, and the one place the role vocabulary ran out |
| `social` | A meditation app whose every card carries an illustration, three shelves, and a course page that covers the navigation bar |

Each of them names its colour scheme as well as its design system, because the design each
is a rebuild of is a light one or a dark one and a screen that flips with the machine it is
running on is not that design. `DXC_DESIGN` and `DXC_SCHEME` override the half they name,
so any of them can be looked at in any of the seven systems in either scheme.

A unified sample names its colours too. The picture a reference shows has its own accent
and its own flat fills, and a `ColorRole` hands those back to whichever design system is
running: wherever the reference had a colour of its own, the screen came out in the
theme's blue, and the cards came out of the accent containers as pale lilac and powder
blue. So a unified sample holds a `palette` module of the literals its picture is made of,
with a test that pins each value and asserts each one is a literal. For the same reason it
draws its own bar along the bottom: the references have icons alone, with the one you are
on in the accent, and a labelled bar with a selection pill is what `Navigation` is right to
give an application that has not been drawn one.

## The pictures

Five of the unified samples carry artwork, in each one's `assets/` directory: the shop's
eight garments and its banner, the check-in's four faces, the podcast's three covers, the
meditation app's three scenes and the drum school's four subject marks. Twenty-two files,
about eighty kilobytes in total.

**All of them are original drawings made for this repository**, hand-written SVG rather
than exported from a tool, which is why they are a few hundred bytes each and why they are
legible as source. Nothing here is taken from the references: the reference designs are
photography and commissioned illustration, and neither is something a repository can carry.
Where the reference has a photograph, the sample draws the same subject instead, and says
so where it matters.

The samples with no artwork have none because their references have none. `minimal` is a
component sheet, and `statistics` is charts and typography, apart from two service logos
this deliberately does not copy.

An application registers a picture by drawing it:

```rust
static HERO: &[u8] = include_bytes!("../assets/hero.svg");

rsx! { Image { asset_id: asset(AssetKind::Svg, HERO), height: 240.0 } }
```

The bytes cross the boundary once. Everything after that is the id, so drawing the same
picture on every frame costs a lookup. A drawing has to say its `viewBox`, or it has no
size to be scaled from and is drawn at one user unit to the pixel in the corner of whatever
box it was given.

Each one is an ordinary dx project: a `Dioxus.toml`, an `assets/` directory and
`src/main.rs`, the layout `dx new` produces. That is deliberate, because a sample exists to
be copied, and an arrangement that only works inside this repository teaches nothing.

```
cd samples/calculator
dx serve
```

Building with cargo directly works too:

```
cargo run -p sample-calculator
```

That downloads the released renderer for the crate's version on first use. To run against a
renderer you have just built instead, name it. The path must be absolute: a build script runs
with the package directory as its working directory, not the workspace root.

```
DIOXUS_COMPOSE_RENDERER_DIR=$PWD/dioxus-compose-renderer/build/native-image/dist/lib \
  cargo run -p sample-calculator
```

## The other platforms

A sample is a library with a one line binary in front of it, because only the desktop
runs a `main` of ours. Android's Activity and a browser page each own the process and the
frame loop and reach the application through an entry point instead, and on iOS the
renderer is a static archive linked into the application, so there the application is the
library.

```
./scripts/build-sample-apks.sh                  # an APK per sample
./scripts/build-sample-pages.sh                 # a browser page per sample, plus an index
./dioxus-compose-renderer/desktop/scripts/build-sample-ios.sh calculator
```

Everything a sample settles before it launches is in one `launch_builder()` that all
three entry points share, so a page and an APK draw the same design its desktop binary
does. An entry point that made its own builder would drop the theme, and seven of the
eleven samples name one.

iOS is the simulator only. A bundle for a device has to be signed by a certificate Apple
issued, against a provisioning profile naming that device, and a self-signed one is
refused; the simulator does not check.

Pushing a `sample-v*` tag builds all of them for all seven platforms and attaches the
results to a GitHub Release: four desktop archives, an APK, a simulator bundle, and the
pages as one archive.

## Pictures

`scripts/sample-shots.sh` photographs every sample in every design system, both colour
schemes and the three window widths each of them changes shape at. It is two steps, because
the two halves of a screen live in two languages: the samples record the bytes their Host
would have sent, and the Renderer draws those bytes in a window of the size the recording's
name carries. Nothing is restated in Kotlin, so what comes out is what an application would
really have produced.

```
./scripts/sample-shots.sh /tmp/shots            # all of them
./scripts/sample-shots.sh /tmp/shots Todo-      # one sample, everywhere
```
