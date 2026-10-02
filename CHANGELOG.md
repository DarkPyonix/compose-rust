# Changelog

## Unreleased

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
