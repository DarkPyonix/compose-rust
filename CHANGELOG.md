# Changelog

## v0.0.2

### Windows opens its own window on the native image too

The Win32 window, which the Kotlin/Native renderer already ships, is now what every Windows
run of the native image opens, with no environment variable asking for it. Both renderers
compile the same window code and draw resize frames the same way: on the CPU, copied into
the window before the resize returns (`DXC_RASTER_RESIZE=0` keeps them on the GPU). Control
with a letter or a digit now reaches a text field as that key, so select all, copy, cut,
paste and undo work in the window of our own.

The Windows native image carries no Java toolkit any more, as the macOS and Linux ones do
not: no `awt.dll` beside it, no preserved `java.desktop` module, no toolkit metadata, and
Compose's main-thread work on the window's own dispatcher (`FrameMainDispatcher`) instead of
Swing's. The clipboard is the window's own. skiko's library still imports `JAWT_GetAWT`, so a
stub `jawt.dll` that answers "no toolkit" goes beside it. The toolkit's window remains only in
the development shell on a JVM.

The Windows window, its C and its Kotlin/Native layer, now comes from the Compose fork's
`extended/window/windows-shared` module at the pinned commit, as the macOS and Linux windows
do. Korean is checked on Windows too: the renderer's text self-check now also draws two Hangul
syllables and fails if they do not ink or come out as the same missing-glyph box.

### compose-rust has an authoring API of its own

Composables, with Compose's shape and Compose's names, on a recomposition runtime that
compares nothing to find out what changed. `#[composable]` gives a function and every
branch in it a group, so `remember` belongs to its call site and a branch that stops being
taken takes its state with it; `mutable_state_of` records who read it and runs exactly
those again; `key` keeps an item's identity when it moves. Effects run after the
composition is applied: `side_effect`, `launched_effect`, `disposable_effect`, and futures
that wait on the frame clock, a timer or a worker thread.

Every widget in the schema is a composable that writes what its `rsx!` counterpart writes,
so the same Renderer draws both. `compose-rust/benches/fr39/` measures the two paths
against each other over scenarios fixed before the runtime was written.

```rust
#[composable]
fn counter() {
    let count = remember(|| mutable_state_of(0));
    Column().content(|| {
        Text(format!("{}", count.get()));
        let more = count.clone();
        Button("+1").on_click(move || more.update(|count| *count += 1));
    });
}
```

### The samples are written on the authoring API

All eleven sample applications under `samples/` are rewritten on compose-rust's own API:
calculator, chat, minimal, todo, notepad, social, podcast, selfcare, store, statistics and
academic. They are workspace members again, their tests run with the workspace's, and
`samples/frames` is the library their recording tests draw their screens through. The todo
editor opens holding the task's title, now that a `TextField` can start with text of its
own. Sample releases are back: a `sample-v*` tag builds every sample for the desktop
platforms, Android, the iOS simulator and the browser (#85).

### The Linux renderer no longer links the Java toolkit

The Linux native image is built without the toolkit: no preserved `java.desktop` module, no
input method or accessibility reachability features. Native Image still writes the JDK's
desktop libraries beside the image because Skiko's classes name them; nothing loads them.
`libjawt.so` is a stub whose `JAWT_GetAWT` reports failure, so
Skiko's load by path still succeeds. A notification's press raises the X11 window directly.
The Kotlin/Native Linux renderer never had a toolkit. Follows #173, which did the same for
macOS. Windows still opens the toolkit's window.

### The macOS renderer no longer links the Java toolkit's window code

The macOS native image does not force-load the toolkit's `libawt_lwawt` archive, its
accessibility class roots, or the input method and accessibility reachability features: the
AppKit window handles text input and accessibility itself. The divider's resize pointer is an
icon of our own on macOS. Windows and Linux still open the toolkit's window by default, so
their sources and the shared reachability metadata stay as they are until those windows
become the default (#134 for Windows, #160 for Linux).

### Linux opens its own window by default

On the GraalVM native-image path Linux now opens the X11 window the renderer makes itself,
with no environment variable and no toolkit between the scene and the screen. It draws its
own caption and edges and hands the move and the resize to the window manager, has the
CLIPBOARD and PRIMARY selections (the middle button pastes PRIMARY), text input with
composition through XIM, drag and drop of files, the application's icon and the display
scale, and sends the caret position to the input method. Shortcuts reach Compose as the
keys they are: control with C, V, X, Z and A, shift with Insert, control with Insert and
shift with Delete. A paste of any size arrives as one edit. Windows still opens the
toolkit's window. Not done on Linux: AT-SPI accessibility, which is being built for the
Kotlin/Native window; IME candidate windows and the feel of a live resize still need a
person on a real desktop.

### The native-image renderer opens its own window on macOS

On the GraalVM native-image path, macOS now opens the AppKit window the renderer makes
itself, with no environment variable and no toolkit between the scene and the screen. It
carries what the toolkit's window did: the application's title, size, minimum size and
resizable choice, the system or the transparent title bar, the icon, the material behind a
glass design, files dropped on a drop target, and the window closing by itself for unattended
runs. Pointer positions and accessibility rectangles are now converted between points and
pixels, which they were not at 200% before. Korean input, VoiceOver and the feel of a live
resize at 100% and 200% still need a person on a Mac.

### The Dioxus path lives in dioxus-compose

The Dioxus adapter, the Dioxus baseline and the twelve `rsx!` samples moved to
[dioxus-compose](https://github.com/DarkPyonix/dioxus-compose), where the samples are
native-widget examples. No crate in this repository depends on Dioxus any more.


### Elements for drawing an HTML and CSS screen

A layout engine on the Host can now hand its result to the renderer as it is. `AbsoluteBox`
places each child at its own `Offset` without laying anything out again, and six more
modifiers carry what CSS decorates a box with: `RequiredSize`, a border with its own width
and paint on each side (`BorderEach`), a radius for each corner (`CornerEach`), `box-shadow`
(`Shadow`), `overflow: hidden` (`Clip`) and `opacity` (`Alpha`, applied once to the whole
group). A `SetModifier` record is now as long as its modifier's tag says, so the border and
the shadow carry their values whole; every modifier that existed before keeps its 28 bytes
exactly.

### CSS transforms, and animations the renderer plays itself

A `Transform` modifier carries a 2D CSS transform as a matrix about a fractional origin,
so `translate`, `rotate`, `scale`, `skew`, `matrix()` and their products draw with the
box's text and children, and a press is tested against the transformed shape. A matrix
that collapses the box draws nothing and takes no input. The transform does not change
layout, the same as in CSS. 3D transforms are not in yet (#105).

`StartAnimation` describes an animation once, with its keyframes, timing function,
delay, iterations, direction and fill, and the renderer plays it on its own frame clock
with the Web Animations timing model, so the Host does no work per frame. It animates
`Alpha`, `Color`, `Background`, `Transform` and `BorderColor`, the colour of every side of
a border. A layout container's `color` is the colour the text inside it inherits, as CSS
`color` is, so one animation on a container recolours all of its text and sends nothing to
any of it. `ControlAnimation` pauses, resumes or cancels one, and `AnimationEvent` reports
the start, each iteration and the end the Host asked to hear about. `ReducedMotionChanged` tells the Host the platform's reduced
motion setting at start and when the window comes back into use, on macOS, Windows,
Linux, iOS, Android and the web. Windows now reads "Show animations in Windows" being off
as reduced motion; it was read the wrong way round (#89).

`GraphicsLayer`, `AnimationSpec` and `transition()` build these records from Rust. In
compose-rust's own API, `Modifier.graphics_layer` draws a Compose graphics layer as a
transform and an opacity, and `animate_float_as_state`, `animate_color_as_state` and
`animate_offset_as_state` give a Modifier an opacity, a background or a translation that
the renderer animates to whenever the target changes, with one record and no composition
while it plays. The same hooks for `rsx!` are in dioxus-compose.

### The renderer is built for the platform where Compose publishes one

macOS no longer carries a Java runtime. Where Compose publishes a target of its own, the
renderer is compiled for the platform directly; where it does not, it stays a native image.
Nothing an application writes changes: the same C ABI, the same protocol bytes, the same
interpreter sources.

| | Before | Now |
| --- | --- | --- |
| A calculator on macOS | 93.1 MB across four files | **28.95 MB in one** |
| What it holds while open | 56 MB | **39 MB** |
| Threads | 22 | 10 |

For comparison, Apple's own Calculator holds 45 MB, and its heap is more than twice ours.

Linux and Windows keep the native image, and are lighter for a second reason: the toolkit
has been taken out of what the image is asked to keep. Nine ways of removing it were
measured; the six that worked are in the build, and the four that were worth nothing are
written down, because their failing is what found the cause.

`docs/platforms.md` has the measurements, what each platform can and cannot do, and why.

### Checked by hand on the macOS native path

The calculator draws, a screen reader is given every button by name, a press reaches the
scene, and the window is the one the application asked for.

## v0.0.1

Published 2026-10-03. The first compose-rust release an application can use as named:
0.0.0 reserved the crate name with a README that still introduced the project as
dioxus-compose.

- The project is called compose-rust everywhere a reader sees its name: the README and
  its Korean translation, the guide, build and renderer messages, and the default window
  title.
- New widgets: ScrollRow, Chip and FloatingAction (taken from dioxus-compose, the schema
  hash matches it), Badge, SelectionContainer, SplitPane.
- Application colours over the design system (`use_theme`, palettes), 22 code colour
  roles for all seven design systems, and a background paint on text runs.
- OS notifications on macOS, Windows, Linux, Android, iOS and the web, posted from
  components or Host worker threads; the Renderer serves one frame itself when the frame
  clock is stopped, so a minimised window still delivers them.
- Linux: an application that depends only on this crate finds the renderer and exports
  the Host functions the renderer calls, on both the native-image and the Kotlin/Native
  static renderer; proven by building and running a consumer in CI.
- Compose is built from the thisisthepy/compose-multiplatform-core-extended fork at a
  pinned commit instead of upstream plus patches; the two produce identical artifacts.
- The renderer builds on every platform again (macOS, Windows, Linux x64 and arm64, iOS).

## v0.0.0

The first published version. It is numbered 0.0.0 because the approach is proven and the
product is not: you can write a real application in Rust and watch it run, and you will
also run out of widgets partway through.

### What works, and was verified by running it

- **Declarative UI in Rust, drawn by Compose.** `rsx!`, hooks and signals on the Rust
  side; an AOT-compiled Compose Multiplatform renderer draws the pixels. No webview and
  no bundled JVM.
- **Korean IME.** Composition display, jamo-level backspace, arrow keys committing before
  moving, insertion mid-sentence and font fallback, all checked by hand on the macOS
  native image. This was the question the project existed to answer.
- **Accessibility.** VoiceOver reads the tree on the native image.
- **One boundary across two runtimes.** The desktop C smoke host links against the iOS
  static archive unmodified and produces the same handshake, byte for byte.
- **Four sample applications** that run: a calculator, a notepad, a task list over five
  thousand items, and a chat interface whose reply streams in from a worker thread.
- **Memory.** 60MB physical footprint for a running sample on macOS. The number most tools
  show is around 128MB, which counts shared read-only library pages that every other
  application on the machine is also counting.

### What is in the box

Twenty-three of the twenty-nine core widgets, custom drawing, pointer gestures, asset
delivery, six design systems behind one role-based contract, and a modern window that
draws its own title bar and follows the system colour scheme.

### What is not

- Six widgets are missing their renderer half: Checkbox, RadioButton, Switch, Slider,
  ProgressIndicator and Divider.
- Windows and Linux have never been run. The build scripts exist and CI now exercises
  them, which is how their first real bugs were found; neither has drawn a window yet.
- Android and web are designed and unimplemented.
- The design system contract does not yet cover pickers or selection controls, so those
  widgets cannot express what each system does differently.
- The samples work and do not yet look like applications, because until this version an
  application could not write a padding or a background from `rsx!`.

### Installing

The crate builds without a renderer, which is what a plain `cargo add` gets you: enough to
compile against, not enough to draw. Drawing needs the `native-renderer` feature and a
renderer built for your platform, which for now means building it yourself from this
repository. Release artifacts come later.
