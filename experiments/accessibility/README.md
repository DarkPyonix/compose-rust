# Desktop accessibility experiment (SPEC NFR-8, §7)

Does the AWT accessibility bridge survive the native-image build?

The question §7 asks is not whether Compose supports accessibility. It is whether the
native image keeps the path Compose already has. Those two failures look identical from
the outside, so every check here is run twice, once on the JVM dev shell and once on the
native image, and the difference between them is the answer.

## How the bridge works

1. Compose attaches the window's `Accessible` to the platform. On macOS
   `androidx.compose.ui.platform.a11y.Accessibility_desktopKt.initializeAccessible` calls
   `org.jetbrains.skiko.AccessibilityKt.initializeCAccessible`, which is a native method in
   `libskiko-macos-<arch>.dylib`.
2. That native method looks up `sun.lwawt.macosx.CAccessible.getCAccessible` through JNI.
3. From then on AppKit drives the tree from Objective-C. For every element it calls back
   into `sun.lwawt.macosx.CAccessibility` to ask for the children, the role, the name and
   the value.

Step 3 is the part the closed-world analysis cannot see, and it is where the image broke.

## Tools

`ax-dump.swift` walks a running process's accessibility tree with
`AXUIElementCreateApplication` and prints roles, labels and values. This is the same tree
VoiceOver reads, so it is real evidence rather than a proxy, and it can run unattended.

    swift ax-dump.swift <pid> [maxDepth]

It needs the calling terminal to hold the Accessibility permission (System Settings >
Privacy & Security > Accessibility). Without it the reads fail with `-25211`
(`kAXErrorAPIDisabled`) and the tool says so, rather than printing an empty tree and
letting an unrelated permission problem look like a renderer failure.

What it cannot tell you is whether VoiceOver's *speech* is sensible: reading order, whether
a label is useful rather than merely present, and whether focus follows the caret. That
part needs a human, and the procedure is at the end of this file.

## Automated procedure

Run both halves and compare. The trees will not match element for element, because the JVM
run and the smoke test compose different screens, but the shape must match: a window with a
labelled `AXStaticText` and `AXButton` under it, not a bare window.

JVM dev shell:

    cd dioxus-compose-renderer
    ./kotlin run -m desktop &
    # find the app process, not the Compose Hot Reload dev tools process
    pgrep -fl jbrsdk
    swift ../experiments/accessibility/ax-dump.swift <pid>

Native image:

    cd dioxus-compose-renderer
    ./desktop/scripts/build-native.sh
    ./desktop/scripts/smoke-test.sh &
    swift ../experiments/accessibility/ax-dump.swift "$(pgrep -f smoke_host)"

A native-image run that aborts with `Abort trap: 6` and an
`NSInvalidArgumentException ... object cannot be nil` the moment the dumper touches it is
the regression this experiment exists to catch. The abort happens inside AppKit while it
builds the children array, and there is no Java stack trace, so the smoke test's exit code
(134) is the signal to watch. That was the state until 2026-09-21; see the result below for
what caused it and how to tell if it comes back.

## Result, 2026-09-21, macOS arm64, Liberica NIK 25 Full

| | JVM dev shell | native image, before | native image, after |
|---|---|---|---|
| elements in tree | 14 | 1 | 12 |
| labelled controls | `AXStaticText`, `AXTextField`, `AXButton` | none | `AXStaticText`, `AXButton` |
| process after the dump | alive | aborted, exit 134 | alive, smoke test exits 0 |

The three runs compose different screens, so the counts are not meant to match: the JVM run
draws the M0 demo and the smoke test draws a label and a button. What matters is the shape.

The JVM tree:

    AXWindow subrole=AXStandardWindow title=DioxusCompose
      AXUnknown
        AXGroup
          AXUnknown
            AXStaticText desc=clicks: 0 value=clicks: 0
            AXTextField desc=type here
            AXButton desc=increment

The native image now produces the same shape, and the process survives the dump:

    AXWindow subrole=AXStandardWindow title=DioxusCompose
      AXUnknown value=0
        AXGroup value=0
          AXUnknown value=0
            AXUnknown value=0
              AXStaticText desc=smoke host: 0 clicks value=smoke host: 0 clicks
              AXButton desc=click me
      AXButton subrole=AXCloseButton
      AXButton subrole=AXFullScreenButton
      AXButton subrole=AXMinimizeButton
      AXStaticText value=DioxusCompose

Before the fix it was one element and an abort. **The automated half of NFR-8 now holds on
the native image.** The manual VoiceOver checklist below has not been run yet, so NFR-8 is
not finished; what has changed is that the checklist can now get past step 2.

### The cause: the link dropped the Objective-C role classes

Objective-C keeps a table from a Java role to the name of the class that implements it
(`pushbutton` to `ButtonAccessibility`, `groupbox` to `GroupAccessibility`, and every role
the platform ignores to `IgnoreAccessibility`). It resolves that name with
`NSClassFromString`, so **nothing in the image ever names those classes**. They are in the
archive, `-force_load` brings their objects in, and the link then drops them as dead code
because no symbol refers to them. `NSClassFromString` returns nil, `[nil alloc]` is nil, and
`childrenOfParent` inserts that nil into the array it is building for AppKit. That is the
`NSInvalidArgumentException: object cannot be nil`.

Only the few classes that some other Objective-C code happens to mention survived:

    otool -oV build/native-image/dist/lib/libdioxus_compose_renderer.dylib \
      | awk '$1 == "name" && $NF ~ /Accessibility$/ { print $NF }' | sort -u

Before the fix that printed 12 names, and `GroupAccessibility`, `ButtonAccessibility`,
`StaticTextAccessibility` and `IgnoreAccessibility` were not among them. The fix in
`build-native.sh` reads the class list back out of `libawt_lwawt.a` and makes every one a
root of the link with `-Wl,-u`, so the list cannot rot when the JDK adds a role.
`desktop/scripts/tests/accessibility-link.test.sh` compares the two lists and fails if the
image is missing any, and it runs in CI after the build.

### How it was found, and what it rules out

`ax-probe.m` in this directory is the tool. The library is stripped, so lldb cannot resolve
a selector in it and a breakpoint by name never binds; a debug image would have worked but
costs a full rebuild. Swizzling does not need symbols, because the Objective-C runtime still
carries the class, so the probe wraps `createWithParent:withClass:...`,
`getComponentAccessibilityClass:andParent:`, `getCAccessible:withEnv:`, `childrenOfParent:`
and `initializeRolesMap`, and it exports `Java_sun_lwawt_macosx_CAccessibility_roleKey` so
that an inserted copy shadows the real one.

    clang -dynamiclib -framework Foundation -framework AppKit \
        -I"$GRAALVM_HOME/include" -I"$GRAALVM_HOME/include/darwin" \
        -o /tmp/ax-probe.dylib experiments/accessibility/ax-probe.m
    cd dioxus-compose-renderer
    DYLD_INSERT_LIBRARIES=/tmp/ax-probe.dylib ./build/native-image/smoke_host

Insert it into the host binary directly. Going through `smoke-test.sh` does not work:
`/bin/bash` is protected by SIP, so the loader strips `DYLD_*` before the script runs.

The probe printed the answer on the first try:

    [axprobe] childrenOfParent enter which=-1 allowIgnored=0
    [axprobe] initializeRolesMap enter
    [axprobe] getComponentAccessibilityClass role=rootpane -> (nil)
    [axprobe] getCAccessible accessible=0xd -> 0x24
    [axprobe] createWithParent role=rootpane classType=(nil) accessible=0xd -> (nil)

`getCAccessible` returned a real object, so the Java half of the call was working the whole
time. The nil is the **class**, not the accessible and not the role string.

That rules out the two hypotheses this experiment had been carrying:

- **The `key` field.** `roleKey` was never called at all, so no `GetFieldID(AccessibleRole,
  "key")` ever ran. The JNI field lookup was never the problem, which is why registering the
  `AccessibleBundle` superclass chain changed nothing.
- **A missing registration.** Nothing on the Java side failed. The closed-world analysis was
  right to report nothing: the gap was in the native link, which it cannot see.

A JNI lookup that misses would also not have produced this abort. The JDK's `LOG_NULL`
raises `NSGenericException` and logs `Bad JNI lookup`, and a Java exception raises
`NSGenericException` through `CHECK_EXCEPTION`. Running with `JNU_APPKIT_TRACE=1`, which
makes `CHECK_EXCEPTION` call `ExceptionDescribe`, printed no Java stack at all. The
exception was `NSInvalidArgumentException` from `insertObject:atIndex:`, which only the nil
child can produce.

### A second ordering bug, visible but harmless once the classes are linked

The probe also showed `childrenOfParent` entering **before** `initializeRolesMap`. That
matters because `initializeRolesMap` is the only thing that ever calls
`CAccessibility.getAccessibility(String[])`, which is the only thing that fills
`CAccessibility.ignoredRoles`. On the first query `ignoredRoles` is still null, `_addChildren`
guards on it, and so no role is ignored: `rootpane`, which the platform ignores, is handed
to Objective-C instead of being skipped. With `IgnoreAccessibility` linked in that is
harmless, because Objective-C then builds an ignored element and AppKit drops it, which is
what the tree above shows. It was worth chasing only because it was the first nil to appear.

### The resource bundle, registered earlier

`desktop/src/AccessibilityReachabilityFeature.kt` registers
`com.sun.accessibility.internal.resources.accessibility`. That was a real gap, found with
`--exact-reachability-metadata -R:MissingRegistrationReportingMode=Warn`:

    MissingResourceRegistrationError: Cannot access resource bundle with name
      'com.sun.accessibility.internal.resources.accessibility'
      javax.accessibility.AccessibleBundle.toDisplayString(AccessibleBundle.java:92)
      sun.lwawt.macosx.CAccessibility.getAccessibleRole(CAccessibility.java:943)

It is not what caused the abort, and registering it alone did not fix anything. It stays
because the role display names would otherwise be missing from the binary.

### What was already fine

The Objective-C half and the `Java_sun_lwawt_macosx_CAccessib*` entry points were never
missing. `build-native.sh` force-loads the whole `libawt_lwawt.a`, which carries all 14 of
them, so the link needed no change. Checking the built `.dylib` with `nm` is not a useful
test here: it is stripped, and the IME natives that are known to work show up as absent
too. Check the archive instead:

    nm -g "$GRAALVM_HOME/lib/static/darwin-aarch64/libawt_lwawt.a" \
      | grep -c "T _Java_sun_lwawt_macosx_CAccessib"    # 14

### Note on the smoke test

`smoke-test.sh` composes a Column, a Text and a Button, not two text fields. It exits 0 on
its own, because nothing asks the process for its accessibility tree, and it used to exit
134 as soon as the dumper or VoiceOver attached. A green smoke test is therefore not
evidence for NFR-8 either way, and the accessibility check has to be run as its own step:
start the smoke test, attach the dumper, and check both the tree and the exit code.

## Manual VoiceOver procedure (needs a human)

§7's first checkbox cannot be automated. The tree dump proves the elements and labels are
exposed; only a listener can confirm VoiceOver speaks them usefully. Run this on the
native image, then repeat on the JVM run and record any difference.

It has not been run on the native image yet. Until 2026-09-21 it could not be: turning
VoiceOver on aborted the process at step 2. The tree dump above says the elements and the
labels are there now, so the procedure should run to the end, and running it is what is
left of NFR-8.

Setup. Grant Accessibility permission to the terminal you will use, or the dump step
fails with `-25211`. Build and start the app:

    cd dioxus-compose-renderer
    ./desktop/scripts/build-native.sh
    ./desktop/scripts/smoke-test.sh

Turn VoiceOver on with Command-F5. Keep the VoiceOver caption panel visible (VoiceOver
Utility > General > Show caption panel) so the spoken text can be transcribed and pasted
into the result rather than recalled.

Steps, recording the spoken text verbatim for each:

1. Click the app window. VoiceOver should announce the window by name.
2. Press Control-Option-Shift-Down to enter the window's contents. Expected: it does not
   announce an empty group.
3. Press Control-Option-Right repeatedly to walk every element. Expected: the static text
   is read with its content, and the button is read with its label followed by "button".
   Record the order and compare it with the visual order.
4. On the button, press Control-Option-Space. Expected: the button activates and the label
   text updates, and VoiceOver announces the new value.
5. Press Tab, then Shift-Tab. Expected: focus moves between the focusable controls, the
   focus ring is visible, and VoiceOver announces each newly focused element. This is §7's
   Tab traversal checkbox.
6. If the screen under test has a text field, focus it and type. Expected: the typed
   characters are echoed. Then switch to a Korean input source and type a syllable.
   Expected: the composing text is announced without the process aborting. This crosses the
   input method path and the accessibility path at once, which is where the two Features
   could interfere.
7. Turn VoiceOver off with Command-F5 and confirm the app is still running. A crash that
   only appears on VoiceOver teardown is a distinct failure worth recording.

Record for each step: spoken text, whether it matched the expectation, and whether the JVM
run differed. A step that differs between the two is a native-image configuration gap. A
step that fails identically on both is a Compose or a semantics gap in the screen, and
belongs upstream or in the widget layer rather than here.

Windows Narrator is the same shape of check and is not covered by this experiment; the
renderer has no Windows build yet.
