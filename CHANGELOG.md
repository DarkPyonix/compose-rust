# Changelog

## Unreleased

### Windows opens its own window, and Linux's is finished but not the default

The Win32 window is now the default on the native-image path, with no environment
variable. The X11 window is finished the same way but Linux keeps the toolkit's window
until typing, copy and paste and Korean input have been checked on a real desktop; set
`DXC_X11_WINDOW` to try it. Windows keeps its frame and takes the caption strip into the content as before
(the logic moved from the shim into the window's own procedure), and now honours the
application's minimum size, resizable choice and icon, has a clipboard, and takes files
dropped on a drop target. Linux draws its own caption and edges and hands the move and the
resize to the window manager, has the CLIPBOARD and PRIMARY selections, text input with
composition through XIM, drag and drop of files, the application's icon, and the display
scale. The caret position is sent to the input method so candidate windows open beside it.
The X11 window publishes its tree on the AT-SPI accessibility bus for Orca, through protocol code shared with the Kotlin/Native Linux window.

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
native-widget examples. No crate in this repository depends on Dioxus any more. compose-rust's
own samples return when they are rewritten on its authoring API (#64), and sample releases
are paused until then (#85).

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
