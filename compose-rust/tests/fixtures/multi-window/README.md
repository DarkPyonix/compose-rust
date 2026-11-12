# multi-window

Two windows of one application, opened through `LaunchBuilder::windows()`. Both windows read
the same state: a click count, and the text last typed into either window's field. Each
window has a button that closes it, and the title bar's close button does the same. The first
window offers to open the second again once it is closed. The process ends when the last
window closes.

Every click, keystroke, focus change and close prints an `event:` line on standard output, so
what reached the Host can be read off the terminal.

`multi-window --check` runs the same windows with no person: it waits until both are laid
out, writes the shared state, closes the second, then the first, and exits 0 only if the
renderer loop then ended by itself. `scripts/check-windows-consumer.sh` runs it that way
(stage 3d).

## Getting it

On Windows, the "Windows static renderer" job builds it and keeps it in the
`windows-static-consumer` artifact as `multi-window/multi-window.exe`. It needs nothing beside
it. To build it yourself, build the renderer first (`renderer/desktop/scripts/build-windows.sh`),
then run `cargo run --release` in this directory.

A machine with no graphics card, such as a virtual machine, has to draw with Windows' software
adapter:

```powershell
$env:DXC_D3D12_WARP = "1"
.\multi-window.exe
```

## What to check by hand

The second window is the one under test. The first is there to compare with: whatever works
in the first has to work the same way in the second.

| Check | How | What you should see |
| --- | --- | --- |
| Two windows | Start it | Two windows, "compose-rust first window" and "compose-rust second window", each with the click count at 0 |
| Shared state | Press "Add one" in the second window | The count goes up in both windows at once; `event: window 2 added a click` prints |
| Input method | Switch to the Korean IME and type `han-geul` in the second window's field | The syllables compose underlined in the field; the candidate window opens beside the caret in the second window, not at the screen corner and not over the first window |
| Composition stays local | While a syllable is still composing, look at the first window | "Typed in either window" does not change until the syllable is committed; then both windows show it and `event: window 2 field = ...` prints |
| Focus between windows | Click into the first window's field, type, then click back into the second's | Typing goes to the window that has the caret; `event: window N focused = true` and `false` print for the right windows |
| Screen reader | Start Narrator (Ctrl+Win+Enter), bring the second window forward with Alt+Tab, and move through it with Tab and Narrator's scan mode | Narrator says the window's title, then reads "Add one" and "Close this window" as buttons and the field as an editable text with its placeholder; the texts read as they are on screen |
| Screen reader acts | With Narrator on "Add one" in the second window, activate it (Narrator key+Enter) | The count goes up in both windows, as with a click |
| Close one | Close the second window with its title bar's close button | Only the second window goes; `event: window 2 asked to close` prints; the first window shows "Open the second window" |
| Open again | Press "Open the second window" | The second window opens again and still shows the shared count |
| Exit on the last | Close both windows | The process ends; `$LASTEXITCODE` is 0 in PowerShell |

Nothing in the program is particular to Windows. On macOS and Linux it runs with that
platform's renderer, for VoiceOver and Orca, and the same table applies there.

A run is recorded with its date, the Windows version and build, the input method and the
screen reader, and what each row showed.
