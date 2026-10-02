# Skia inside the image

Skia reaches this process through JNI, and a JNI library is normally a file opened by name
at run time. That is one more file every application carries: 21MB beside the renderer on
macOS, and on Windows a 14MB DLL plus a 10MB ICU data file, which is why a single Windows
executable is not possible today.

This is how far linking it in has got, and what is still in the way.

## What works

- **A static Skia exists.** `build-static-skiko.sh` compiles skiko's own C++ and
  Objective-C bindings and archives them together with the Skia libraries skiko's build
  downloads. Nothing in skiko's build is modified: the link task's two inputs are still on
  disk when it finishes, and the archive is made from exactly those.
  About two minutes, and 79MB.
- **The image links it.** `StaticSkikoFeature` registers the name as a library that is
  already inside the image. It reaches three things under `com.oracle.svm.core` to do it,
  none of which is a public API, which is why the build passes `--add-exports` for them and
  why this is off unless `DXC_STATIC_SKIKO` names an archive.
- **The renderer comes out with no undefined Skia symbols.** 70.5MB becomes 83MB.

Three things had to be right and each failed first:

- `-force_load`, because a JNI entry point is reached by name and nothing refers to it by
  symbol, so ordinary archive semantics drop the member that defines it.
- The Objective-C compile task, which is a second task in skiko's build and supplies every
  Metal and AppKit entry point. Without it the archive is missing exactly the platform it
  was built for.
- Stubs for other platforms' entry points. Skiko declares every platform's native methods
  everywhere and compiles only one platform's. That is invisible while the library is
  loaded by name, and fatal once it is linked in, because macOS binds every symbol at load
  and the first Direct3D declaration kills the process before anything is drawn.
  `generate-foreign-stubs.sh` writes the 62 of them.

- **The loader no longer looks for the file.** Skiko's desktop loader does not use
  `System.loadLibrary`: it reads `skiko.library.path` or unpacks the library out of the jar,
  then calls `System.load` with an absolute path, so it failed on the missing file however
  the image was linked. Android is the one platform where it calls `System.loadLibrary`,
  and that is the call a built-in library replaces.

  `desktop/src/StaticSkikoLoader.java` substitutes the one private method that finds and
  opens the file. Skiko's `loadOnce` is a lock, then that method, then its own
  initialisation, then the lock is released and marked done, so replacing only that leaves
  the locking, the once-only guarantee and the initialisation as skiko's.

- **2026-09-24: a window drew with no Skia file on disk.** The minimal sample ran from a
  distribution whose `lib/` held the renderer and the two AWT forwarders and nothing else.

## What is in the way

Windows only, and the ICU data table. Skiko's Windows loader unpacks `icudtl.dat`, 10MB,
alongside the library, and the substitution above skips that with the rest of the lookup.
A data file is not something linking absorbs. macOS does not have it at all, because the
macOS Skia build carries ICU inside `libicu.a`, so the answer is in Skia's build
configuration rather than here.

Nothing here is on by default. An ordinary build loads Skia from the file beside it exactly
as before.
