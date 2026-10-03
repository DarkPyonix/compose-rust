# The toolkit the builder puts there

Nine and a half megabytes of an image that draws every control itself is the toolkit's
look and feel: aqua, metal, nimbus, motif and synth, their file choosers, their menus,
their table headers, their icons. Nothing in this renderer reaches any of it.

It is not reachable from this repository's code. It is put there by the builder.

## What was tried first, and what each was worth

Nine things, in order, each measured:

| | |
| --- | --- |
| the linker no longer forcing the toolkit's archive in | part of 8.8MB |
| the two features that registered its bridges, off | part of 8.8MB |
| thirty-four entries naming it, out of the reachability metadata | part of 8.8MB |
| the toolkit no longer woken while the image is built | part of 8.8MB |
| four Compose classes only its scene uses, out of the metadata | part of 8.8MB |
| four Skia classes and five look-and-feel classes, out | part of 8.8MB |
| every class naming it, out of the jars on the classpath | nothing |
| the two calls that hand back its events, substituted | nothing |
| its packages moved to run-time initialisation | nothing |
| the module excluded with `--limit-modules` | nothing |

The first six are in the branch this sits on and are worth 71.58MB to 62.77MB. The last
four moved the remaining 9.63MB by no bytes at all, which is the finding: nothing on the
classpath is asking.

## What is asking

`com.oracle.svm.hosted.jdk.JNIRegistrationAwt`, in the builder, in
`lib/svm/builder/svm.jar`. Its `beforeAnalysis` asks whether the platform is one the
toolkit runs on and, if so, registers `java.awt.Toolkit`, `java.awt.GraphicsEnvironment`,
`java.awt.image.ColorModel` and the rest. There is no option that says otherwise: the only
condition is the platform.

That is why asking the analysis for a reason answered `manually created constant` rather
than a call. There was no call to cut.

The package is `com.oracle.svm`, so this is not one distribution's doing. Every GraalVM
has it.

## Replacing it

`build.sh` compiles a class of the same name that does nothing and prints the flag that
patches it into the builder module. The JDK is not modified: the flag is read by the JVM
the builder runs in, and an ordinary build without it is unchanged.

    DXC_EXTRA_NI_FLAGS="$(experiments/no-toolkit-builder/build.sh)" \
        ./renderer/desktop/scripts/build-native.sh

62.77MB becomes 42.75MB, and java.desktop 9.63MB becomes 1.59MB.

## What is in the way

The image builds and does not yet run. Compose asks for the main thread, coroutines
answers with the toolkit's, and opening the toolkit's queue opens a library the image no
longer has. `FrameDispatcher` offers the frame loop as the main thread instead, and that
much compiles; it has not been through a build that finished.

This is worth as much to Windows and Linux as to macOS, and more: they have no other path
than this one.

## `NoToolkitSubstitutions.java`

The substitutions the second attempt needed, kept here rather than under the renderer's
own sources so that nothing compiles them. Three places where Compose keeps a toolkit
answer as its default, reached even by an image that never opens a toolkit window. Read
the file's own comment for what each one was for.

Recorded, not adopted: the four attempts this directory holds are the ones that did not
work. What the macOS renderer does instead is Kotlin/Native, which has no toolkit in it
to take out.
