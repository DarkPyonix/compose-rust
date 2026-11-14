# Changelog

## Unreleased

### Compose fork moved to 1.11.1-ext.3

The renderer now builds with the Compose fork's `1.11.1-ext.3` (tag `v1.11.1-ext.3`), for Compose, the window modules and skiko-awt alike, and the Compose itself is unchanged from `ext.2` apart from its version. The windows gain right and middle clicks, double clicks timed by when the platform says the press happened and by the system's double-click interval, a key typed once rather than twice on macOS, a slot for native views, HiDPI fixes (Xft.dpi on X11, the backing scale on macOS, a skipped stretched present on Windows, and text scaled once under GNOME and under KDE with forced fonts DPI), file dialogs, and the Windows caption buttons with Snap Layouts. The design systems are now published to Maven Central from `extended/design-systems/designsystem-<name>`.

## v0.0.10

v0.0.8 and v0.0.9 were tagged but published no renderer: the Windows GraalVM renderer failed to link, so their releases carry no assets. v0.0.10 is the first release with everything listed under v0.0.9 and v0.0.8 below.

### The Windows GraalVM renderer links again

The window pin of v0.0.8 made the renderer call the macOS window's appearance setter and the Linux window's text factor on every desktop, but only macOS and Linux defined them, so the Windows native image failed to link with two unresolved names. Each is now defined, doing nothing, on the desktops that have no such window, and the symbol check counts both ([#730](https://github.com/DarkPyonix/compose-rust/pull/730)).

### Word wrap in the code editor

The new `WordWrap` property (tag 200) breaks lines too long for a code editor onto the rows below: `WordWrap::Viewport` at the edge of the view, `WordWrap::Column(n)` at `n` columns, `WordWrap::Bounded(n)` at whichever of the two comes first, and `WordWrap::Off` (the default) at none. It travels as one integer, the `WordWrapMode` tag in the low 16 bits and the columns above them (`WordWrap::to_value`). A row breaks after the last space that fits, or inside a word longer than the row; a tab advances to its stop and an East Asian wide letter takes two columns. A broken line keeps its number on its first row only, Up, Down, Home and End follow the rows, selections and decorations are drawn on the rows, and the lines on screen are still reported as the document's.

### Multiple cursors in the code editor

The code editor holds several selections at once. Alt and a press adds a caret, Cmd or Ctrl and D adds the next place the selected text is (selecting the word at the caret first), and Alt with Cmd or Ctrl and Up or Down adds a caret on the line above or below. Typing, deleting and pasting happen at every selection as one undo step, the arrows move them all, and Escape goes back to the primary one. `Batch::set_selections(node_id, base_version, &[(anchor, active)])` sends a `SetSelections` command (tag 41, 20 bytes and 16 bytes per selection), and the editor's selection listener hears a `SelectionsChanged` event (tag 67, 28 bytes and 16 bytes per selection, read by `code::SelectionsChange::from_payload`) while there is more than one selection and once more when it comes back to one. `SelectionChanged` goes on reporting the primary selection alone.

### Snippets in the code editor

`Batch::insert_snippet(node_id, base_version, range, snippet)` sends an `InsertSnippet` command (tag 40, 36 bytes: the node, the base version, the range and the snippet's reference) that replaces the range with a snippet in a subset of VS Code's syntax: `$1`, `${1:placeholder}`, `${1|one,two|}`, `$0` and `\$`. The caret selects the first stop, Tab and Shift+Tab move between the stops, places with the same number change together, and a stop with choices opens a list that the arrows, Enter and a press pick from. The session ends at `$0`, on Escape, or when the caret leaves the snippet. The insertion is one undo step and reaches the Host as ordinary `CodeChanged` events. `code::escape_snippet_text` escapes plain text for a snippet.

### View zones in the code editor

`ViewZones::new([ViewZone::new(after_line, height_lines).with_text(..)])` on the new `ViewZones` property (tag 199) leaves room after a line that is not text, the way a diff lines its two sides up. A zone pushes the lines after it down by its height, which need not be whole, follows its line through edits, and is passed over by the caret, a selection and a copy; the visible range is still reported in the document's lines. Its text is drawn dimmed. The field path shows a zone as whole rows.

### Code editor minimap

The new `Minimap` property (tag 201) takes a `MinimapSide` tag, `Right` (1) or `Left` (2), and draws the whole document shrunk on that side, with the part on screen marked and line backgrounds, range backgrounds and diagnostics shown on it. A press or a drag on it scrolls the editor there. `Off` (0) draws none.

### Grouped edits for the code editor

`Batch::edit_group(node_id, request_id, base_version, &[(CodeRange, &str)])` sends several edits to a code editor as one `EditCodeGroup` command (tag 28). Every range is in the document at `base_version`, in any order, and they must not overlap. The editor applies all of them or none: it moves the ranges past what the reader typed since, applies them from the end of the document backwards with one `CodeChanged` per edit, and makes the group one undo step. It then answers with exactly one `CodeEditGroupResult` (event 51, handler property `OnEditGroupResult` 194): `Applied`, `Rejected` where the reader changed a place the group would write, `TooOld` where the version is older than the editor remembers, or `Invalid` where the group makes no sense. `EditGroupResult::from_payload` reads it. A record that cannot be read is a protocol error, followed by `Invalid` when its request can be read. An empty group writes nothing. The schema hash changes.

### The system's light or dark appearance reaches the Host

The Renderer now tells the Host whether the system appearance is light or dark, in a `ColorSchemeChanged` event (tag 52) addressed to the Host with node 0 and handler 0. The record is 20 bytes: the header and `scheme: u32`, 0 for light and 1 for dark, and any other value is a protocol error. It is sent once before the first frame and again on every change, never the same value twice in a row, whatever the theme's `color_scheme` is. Application code reads it with `system_color_scheme()`, which is `None` until the first report, and `resolved_color_scheme()` gives the scheme the theme is drawn in, the system's while the theme follows it. `subscribe_system_color_scheme` wakes a reader on each change, the way `subscribe_reduced_motion` does. Native widgets already followed the system without the Host; this is for what an application draws itself, such as an HTML page answering `prefers-color-scheme`. The schema hash changes.

### Text with no type role reaches the Renderer

`Text`, `TextField` and `Button` with `.type_role(TypeRole::None)` now send the role tag 0, so text that names its own fonts is set in them. `TypeRole::None` is tag 0, which a design property at its neutral value never sent, so such text arrived with no role at all, was drawn in the default role's font, and the Renderer reported its fonts as ignored. A role that is never set is still not sent, and a role that is cleared now removes the property instead of sending 0, so the text goes back to the default role rather than to no role. The minimal sample's serif letters were drawn in the theme's sans this way, and the report they caused failed the web page check ([#569](https://github.com/DarkPyonix/compose-rust/issues/569)).

## v0.0.9

### `user-select` is honoured

A node with `UserSelect::None` and everything under it now takes no part in text selection, so double-clicking a tab or a button label inside a selection region selects nothing, while its controls still click. `UserSelect::Text` opens a selection region, and `UserSelect::All` selects the whole node with one press and copies it with the copy shortcut. Text inside an HTML region is selectable by default, as in a browser: a page that does not want that sends `user-select: none` ([#724](https://github.com/DarkPyonix/compose-rust/pull/724)).

### Radio button colours and size, and radio groups

`RadioButton` takes `ring_color`, `dot_color` and `size(side)`, sent as the `ContainerColor`, `CheckColor` and `ControlSize` properties a `Checkbox` uses. The ring is drawn in its colour selected or not, and where the design system fills a selected button the fill takes it too; the dot scales with the size. A button that names none of them is drawn by the design system as before, in all seven. `Column::radio_group(true)` and `Row::radio_group(true)` send the new `RadioGroup` property (tag 198): the layout is a radio group to assistive technology, Tab stops at it once (on the selected button, or the first), and the arrow keys move focus along its buttons, wrapping at the ends, reporting each button they land on once as a click would. The selection stays the application's ([#545](https://github.com/DarkPyonix/compose-rust/issues/545)).

### Where a transient message is drawn

`Message::with_placement(MessagePlacement { vertical, horizontal })` puts a message along the top or the bottom edge, at its start, centre or end, and `Message::with_offset(dp)` sets how far it is from that edge. Both are measured from inside the safe area and the application's bars: a message at the bottom sits above a bottom tab bar and the gesture bar, and one at the top below the top bar the page opens with and the window's caption. A message that names neither is where the design system has always put it. The `ShowMessage` record grows from 32 to 40 bytes: the two placement tags take the old padding word and the next, and the offset follows as an `f32` (NaN for none), so the schema hash moves ([#577](https://github.com/DarkPyonix/compose-rust/issues/577)).

### Line and range backgrounds in the code editor

Two decoration kinds are appended: `Decoration::line_background(version, line_start, line_end, role)` (kind 5) tints whole lines across the editor's width, past the end of each line, and `Decoration::range_background(version, range, role)` (kind 6) tints the characters of a range more strongly, which is how a diff view marks a changed line and the words in it that changed. `DiffAdded` and `DiffRemoved` are drawn with the design system's container tint for a line and its emphasis tint for a range; a `*Container` or `*Emphasis` role is drawn as it is, and any other role, `DiffModified` among them, as a light wash of its colour. Both follow the reader's edits like every other decoration: a line typed in front of a tinted line moves the tint down with it ([#542](https://github.com/DarkPyonix/compose-rust/issues/542)).

### Code editor pointer events with the text under them

A `CodeEditor` with the new `OnCodePointer` handler property (tag 197) and a `PointerKinds` mask sends its pointer events in a `CodePointer` event (tag 66): the 72 byte `Pointer` record of a node's pointer event, then the line and the column of the character under the pointer, or none over the gutter, past the end of a line or below the last one. The kinds, the buttons, the modifiers, the click count, the capture, a move a frame and the consumption rules are a node's pointer event's, and a `Down` the handler consumes does not move the caret. Cmd or Ctrl and a click goes to a definition this way, and Cmd or Ctrl and a hover underlines a word ([#555](https://github.com/DarkPyonix/compose-rust/issues/555)).

### The Host hears the code editor's keys first

A `CodeEditor` with the new `OnKeyPreview` handler property (tag 196) offers every key press to the Host before it handles the key itself, as the `KeyDown` record a key handler hears. A press the handler consumes, through the same synchronous result as any key handler, is not the editor's: Down does not move the caret and Enter inserts no line. Nothing is offered while an input method is composing. A suggestion list takes Up, Down, Enter, Tab and Escape this way while it is open, and an application takes its own commands such as F12, F2, Ctrl+Space or Ctrl/Cmd+. ([#554](https://github.com/DarkPyonix/compose-rust/issues/554)).

### Where the code editor's caret is, and where any position is

A `CodeEditor` with the new `OnCaretRect` handler property (tag 195) reports where it draws its caret in a `CaretRect` event (tag 64): the line, the column and a rectangle in the window's dp, with the editor's scroll and the window's zoom already in it. It is sent when the caret moves or the view scrolls, at most once a frame. `Batch::query_position_rect(node, request, position)` asks where a caret at any other position would be drawn, in a `QueryPositionRect` command (tag 39), and `compose_rust::code::position_query(callback)` gives the request its number. Every query gets exactly one `PositionRect` event (tag 65) back: the rectangle, or `None` where the position is not on screen, is not in the document, or the editor is gone. A suggestion list, a parameter hint, a rename box or a hover card is placed with them ([#553](https://github.com/DarkPyonix/compose-rust/issues/553)).

## v0.0.8

### Typing on macOS inserts each character once

On the Kotlin/Native macOS window every typed character was inserted twice ("abc" became "aabbcc", and Korean jamo doubled before composing), because the key event carried the letter while the input method committed it as well. The window pin now includes the Compose fork fix: a key that also goes to the input method reaches Compose without its letter, and Command shortcuts keep theirs ([#563](https://github.com/DarkPyonix/compose-rust/issues/563)).

### A node hears its size motion settle

`on_motion_finished(|finished| ...)` on a container or a `TextField` runs once when the node's size, moving under `Modifier::motion`, reaches its target, with the size it settled at in `MotionFinished { width_dp, height_dp }`. A run that a newer size interrupts is not reported, only the one that settles; the first layout and a layout at the same size report nothing, and neither does a node removed while it moves. Under reduced motion the size changes in one frame and the report comes in that frame, so a screen that waits for a collapse before it takes a child out does not wait forever. In `rsx!` the listener is `onmotionfinished`. It is one new handler property, `OnMotionFinished` (193), and one `MotionFinished` event (tag 63, 24 bytes). A node with no handler sends nothing new and its modifier chain is unchanged ([#315](https://github.com/DarkPyonix/compose-rust/issues/315)).

### Font features: tabular figures for timers and tables

`Text::font_feature_settings(&[FontFeature])` and `TextField::font_feature_settings(&[FontFeature])` ask the font for OpenType features, such as `FontFeature::TABULAR_NUMS` for digits of one width, so a number that changes does not move the digits beside it. They are taken with or without a type role, because a feature picks a form the font already has rather than another font. The list travels as the new property 192 (`FontFeatureSettings`, 8-byte records, at most 16), and a node that does not call it sends exactly what it sent before. The Renderer makes Compose's `fontFeatureSettings` string once when the list arrives; a list it cannot read is protocol error 24 and the text is drawn without features. A font without the feature draws as it would have.

### A split pane chooses the width it stacks below

`SplitPane().collapse_below(dp)` sets the split pane's own width below which it shows one pane at a time, the one `selected_index` names. At or above that width it never stacks: the design system is asked as for a medium width, so two columns or a side pane laid over the body is still its answer. Without it the line stays at 600 dp. The width is measured on the Renderer, so crossing the line sends the Host nothing and the panes keep their state. It is the new `CollapseBelow` property (91, f32). A negative or non-finite width is a protocol error, and the split pane is drawn as if it had none ([#314](https://github.com/DarkPyonix/compose-rust/issues/314)).

### Long tokens break inside under `KeepAll`, and fields take a word break

`Text::overflow_wrap(OverflowWrap::Anywhere)` lets a token too long for its line, such as a path or a URL, break between its letters even under `WordBreak::KeepAll`, while the tokens that fit keep their breaks. Such a text is as narrow as its widest letter when a weighted `Row` asks, so a 40 letter path in a narrow column wraps instead of widening the text past the column. `TextField::word_break` and `TextField::overflow_wrap` apply the same rules to the typed text and the placeholder: a `KeepAll` field breaks a Korean hint only at its spaces. The field's value, and what the Host is told, stays exactly what was typed. Each sends the existing `WordBreak` (111) or `OverflowWrap` (112) property, and only when it is called ([#443](https://github.com/DarkPyonix/compose-rust/issues/443), [#444](https://github.com/DarkPyonix/compose-rust/issues/444)).

### SVG pictures draw on Android

Android has no SVG reader of its own, so every `AssetKind::Svg` registration was reported as unreadable (protocol error 7) and drew an empty box. The Android renderer now reads SVG itself, following the rules Skia draws by on the desktop and iOS, so the same file draws the same picture on every platform. It reads shapes, groups, `use`, fills and strokes with their opacities, dashes, caps and joins, transforms, `viewBox` and `preserveAspectRatio`, linear and radial gradients, patterns and clip paths. It does not read `text`, `image`, `mask`, `filter`, `marker` or `<style>` sheets ([#567](https://github.com/DarkPyonix/compose-rust/issues/567)).

### Desktop windows follow the system's text size

The Windows and Linux windows draw native text at the operating system's text size (Windows' Text size slider, GNOME's `text-scaling-factor`, KDE's font DPI) and follow it when it changes with the window open. macOS has no public text size setting, so it stays at 1. Files dropped on a window of the Windows Kotlin/Native renderer now reach the `FileDropTarget` under them, as on Linux and macOS ([#68](https://github.com/DarkPyonix/compose-rust/issues/68), [#72](https://github.com/DarkPyonix/compose-rust/issues/72)).

- `WINDOW_REVISION` moves to the fork commit that holds the text size reader and the Windows file drop.

### The Windows release renderer is the Kotlin/Native static kit

A version tag now releases `compose-rust-renderer-v<ver>-windows-x64.tar.gz` as the Kotlin/Native static kit, the one the crate's build script links on Windows, and checks it as one executable like the other desktop targets. Up to v0.0.7 that asset was the GraalVM native image, which the build script no longer links. The sample build takes this kit from the release for its crate version, checked against its `.sha256`, and builds the kit itself only when the release does not carry it ([#322](https://github.com/DarkPyonix/compose-rust/issues/322)).

### Copying text to the clipboard from application code

`compose_rust::clipboard::set_text(&str)` puts plain text on the platform clipboard through Compose's clipboard, for a "Copy link" button whose text the application already holds. It is one new `SetClipboardText` command (tag 29) with no answering event. Text over 64 KiB is refused with `Err` and nothing is sent, and where there is no clipboard the write does nothing. The Renderer never logs the text. Do not copy passwords or tokens this way: other applications can read the clipboard ([#471](https://github.com/DarkPyonix/compose-rust/issues/471)).

### Reading text from the clipboard

`compose_rust::clipboard::read_text(callback)` reads the plain text on the platform clipboard, for a paste shortcut, and `use_clipboard().read_text()` is the same read as a future. It is one new `ReadClipboard` command (tag 31) and one `ClipboardText` event (tag 56). Every read gets exactly one answer: the text, `None` when the clipboard is empty, holds no text, is missing, or the browser refused permission, or an error when the text is over 64 KiB, which is never cut short. The Renderer never logs the text, only its length ([#546](https://github.com/DarkPyonix/compose-rust/issues/546)).

### Scrolling any scroll container from the Host, and hearing where it is

`Batch::scroll_to(node, ScrollTo { .. })` asks a scroll container to go to an offset, and `Batch::scroll_into_view(node, ScrollIntoView { .. })` asks it to bring a rectangle, a descendant node or a lazy list item into view, aligned to the start, the centre, the end or the nearest edge. They are two new commands, `ScrollTo` (tag 35) and `ScrollIntoView` (tag 36), for `ScrollColumn`, `ScrollRow`, `LazyColumn`, `LazyRow`, `LazyGrid` and `CodeEditor`. A node with the new `OnScrollChanged` handler property (tag 186) reports its offset, range and viewport in a `ScrollChanged` event (tag 60): once after its first layout, at most once a frame while it moves, and once more when it comes to rest. A node without the handler sends nothing.

Every length is in physical pixels, so a value read from a report and sent back is the same place. Both commands are requests, because the scroll position belongs to the Renderer: an offset is kept inside the range, and a request that arrives while the user drags or flings the container is dropped. A command for a node that does not scroll is reported as `NOT_SCROLLABLE` (19) and one whose values make no sense as `INVALID_SCROLL` (20); the rest of the batch still applies. The `ui` scroll state and its dp wire are unchanged ([#591](https://github.com/DarkPyonix/compose-rust/issues/591)).

### Application storage

`compose_rust::storage` keeps named values only the application reads and writes, the same code on every platform: `read`, `read_string`, `write`, `remove` and `keys`, each a future. An application names its storage with `LaunchBuilder::new().identifier(..)`, the same value as `[bundle] identifier` in its `Dioxus.toml`; without one every operation answers `StorageError::Unavailable`, and an identifier that cannot name a directory stops the launch with a sentence and a failing status. Keys are 1 to 128 bytes of `a-z`, `0-9`, `-`, `_` and `.`; a value is at most 16 MiB. Operations on one key apply in the order they are called, and a write is atomic.

On macOS, iOS, Linux and Windows the values are files under the platform's data directory (`~/Library/Application Support`, `$XDG_DATA_HOME` or `~/.local/share`, `%APPDATA%`), read and written by a storage thread of the Host's; `COMPOSE_RUST_STORAGE_DIR` replaces the directory. Android keeps them in the application's files directory, which the Renderer reports at start with a new `StorageLocation` event (tag 59). In a browser the Renderer keeps them in IndexedDB, one database per identifier, through a new `StorageCall` command (tag 32) answered by one `IoAnswer` event (tag 57). A read there larger than the web's 4 KiB event buffer is reported as too large until #583 lets larger events through. Every sample now gives its identifier ([#611](https://github.com/DarkPyonix/compose-rust/issues/611), [#529](https://github.com/DarkPyonix/compose-rust/issues/529)).

### Reading and writing the files a dialog chose, in a browser too

`FileDialogResult::files()` turns a choice into `PickedFile`s, with `read`, `write`, `entries` and `child` as futures. The code is the same on every platform ([#609](https://github.com/DarkPyonix/compose-rust/issues/609)).

- On a desktop a `PickedFile` is the path. A Host worker reads and writes it with `std::fs`, and a write goes to a temporary file beside it that is then renamed over it. `PickedFile::from_path` makes one from a path without a dialog.
- In a browser the file dialog now works. Chromium uses the File System Access API pickers, and a write changes the same file again (`Delivery::Handle`). Firefox and Safari use a file input to open, which gives a copy that can only be read (`Delivery::Upload`), and a save becomes a download on every write (`Delivery::Download`). `can_write_again()` says which case it is.
- In a browser `Chosen.paths` holds `webfile:<handle>/<name>` references, because a page gets no paths. `files()` reads them, so an application does not take them apart.
- On the wire: the `FileCall` command (tag 33), the `ReleaseFileHandle` command (tag 34), the `FileHandles` event (tag 58), and the `IoAnswer` event (tag 57), which the application storage uses too. When the last `PickedFile` for a handle is dropped, the Host lets the Renderer release the handle.
- Until events over the web's 4096 byte event buffer can reach the Host ([#583](https://github.com/DarkPyonix/compose-rust/issues/583)), a choice whose names do not fit answers `Unavailable` and a read that does not fit answers `FileError::TooLarge`. Neither is cut short.

### Pointer events reach the nodes that subscribed

`on_pointer(PointerKinds::..., handler)` now runs its handler. The renderer sends `Down`, `Up`, `Move`, `Enter`, `Leave`, `Cancel`, `Wheel` and `ContextMenu` to a subscribed node, with the position in dp from the node's top left corner ([#417](https://github.com/DarkPyonix/compose-rust/issues/417)). Before this the subscription was written and accepted, and nothing was ever sent.

- A double click is a `Down` and an `Up` whose `click_count()` is 2. The secondary button, and Control with the primary button on macOS, arrive as `ContextMenu` with no `Down` beside it.
- The deepest subscribed node hears an event first. `consume()` keeps it from the nearest subscribed ancestor and from Compose, so a consumed press is not also a click.
- A node that took a `Down` keeps the pointer until it is released, and gets its `Move` and `Up` wherever the pointer goes.
- `Move` is sent once a frame for each node with the latest position, and `Wheel` once a frame with its travel added up. A wheel's travel is in lines.

A node that does not call `on_pointer` carries no pointer input, as before.

### Continuous corners with `Modifier.corner_curve`

`Modifier.corner_curve(smoothing)` draws a node's rounded corners with continuous curvature, the way Figma's corner smoothing does: 0 is the plain circular arc, 1 the longest curve, and 0.6 the look of iOS. The radius still comes from `corner_radius`, `corner_radii` or `shape_role`, and the background, border, shadow, clip and press feedback all follow the same outline, in every design system. A value outside 0..=1 is drawn as the nearer end ([#294](https://github.com/DarkPyonix/compose-rust/issues/294)). It is a new modifier, `CornerCurve` (wire tag 35), so the schema hash changes and a renderer and a crate from before this change no longer agree.

### `Popover`: a floating box of any content on an anchor

`Popover(open).anchor(..).content(..)` attaches a floating box to its anchor. `placement`
names the side it opens on (`Bottom` unless said), `align` the edge it lines up with
(`Start` unless said), and `offset` the gap in dp. The renderer keeps the box inside the
window: it opens on the opposite side when the asked-for one has no room, and slides along
the edge when it would reach past the window. `open` is the application's. A press outside
the box or Escape calls `on_dismiss` once and closes nothing. The schema hash changes, so
the renderer and the crate have to be built from the same version.

### `CodeEditor` type, gutter, colours, row height and pixel scroll

A `CodeEditor` node takes the `FontSize`, `LineHeight`, `TypeRole` and `Font` properties, and its line numbers are set in the same size as the code. Without them it is drawn in the design system's `Mono` role as before. Four properties space the gutter: `LineNumbers` (a boolean, 178), `GutterMinWidthDp` (179), `GutterPaddingDp` (180) and `TextInsetDp` (181). `CodeEditorColors` (182) is one 12 byte record of `ColorRole`s for the container, the gutter, a line number, the current line's number and the current line, built with `compose_rust::CodeEditorColors`; a slot left at zero keeps the design system's colour.

The editor reports the height of a row in physical pixels with `CodeMetrics` (event 54, handler property `OnCodeMetrics` 183) when it is first drawn and when the height changes, and its scroll offset with `CodeScrolled` (event 55, handler property `OnCodeScrolled` 184) at most once a frame. `Batch::set_scroll_offset(node_id, offset_px)` (the `SetScrollOffset` command, tag 30) scrolls an editor to an offset in the same pixels, so two editors side by side can be kept level ([#544](https://github.com/DarkPyonix/compose-rust/issues/544)). The schema hash changes, so the Host and the Renderer must be built from the same version.

## v0.0.7

### Native file open and save dialogs

The Host can ask for a platform file dialog with `FileDialog` (open a file, files, a folder or folders, or save with a suggested name and folder, filters, a title and an initial folder) and receives one result per request: the chosen absolute paths, a cancel, or "unavailable". On macOS the dialog is an `NSOpenPanel` or `NSSavePanel` sheet on the owning window. Windows, Linux, iOS, Android and the web answer "unavailable" until their dialogs are wired in ([#483](https://github.com/DarkPyonix/compose-rust/issues/483)).

### The composable attribute is spelled `#[Composable]`

The attribute that turns a function into a composable now has Compose's spelling,
`#[Composable]`, and is exported as `compose_rust::Composable`. Rust attribute names are
snake_case by convention only, and the widgets already use Compose's PascalCase names. The
lowercase `#[composable]` is gone, with no alias: the crate is before 1.0, so one release
moves every use. Rename each `#[composable]` in your code to `#[Composable]`.

### Host-driven focus and IME composition notifications

The Host can move input focus to a node, or clear it, with `Batch::request_focus(node_id)` (a new `RequestFocus` command, tag 25), and a `TextField` reports when an IME composition starts and ends with `.on_composition_change(impl FnMut(bool))` ([#410](https://github.com/DarkPyonix/compose-rust/issues/410), [#423](https://github.com/DarkPyonix/compose-rust/pull/423)).

### `Button` text alignment, label size and a content slot

`Button::text_align` and `Button::font_size` use the existing `TextAlign` and `FontSize` attributes, and a `Button` can take children that are drawn as a vertically centred row, so a button can be a list row with a left aligned label ([#425](https://github.com/DarkPyonix/compose-rust/issues/425), [#453](https://github.com/DarkPyonix/compose-rust/pull/453)). No schema change.

### A weighted `Row` or `Column` child keeps room for its longest word

A child with a weight on the main axis gets the larger of its share and its minimum content width, so a label in a narrow weighted cell is no longer broken into one glyph per line. Lazy lists and other lazy layouts keep the stock weight ([#427](https://github.com/DarkPyonix/compose-rust/issues/427), [#448](https://github.com/DarkPyonix/compose-rust/pull/448)).

### Colours and size for `Switch`, `Slider` and `Checkbox`

`Switch` has `track_color`, `thumb_color` and `size(width, height)`, `Slider` has `track_color`, `active_track_color` and `thumb_color`, and `Checkbox` has `container_color`, `check_color` and `size(side)` ([#426](https://github.com/DarkPyonix/compose-rust/issues/426), [#428](https://github.com/DarkPyonix/compose-rust/issues/428)). A value that is not set is not sent, and the design system draws that slot. On the wire these are the new `TrackColor` (121), `ThumbColor` (122), `ActiveTrackColor` (123), `ContainerColor` (124), `CheckColor` (125) and `ControlSize` (126) properties. The schema hash changes, so a renderer and a crate from before this change no longer agree.

### A `WindowDrag(false)` child keeps its press out of a drag region on macOS

Inside a region that is a window drag area, a node marked `WindowDrag(false)` (a button, a text field) now keeps its press: the press is not turned into a window drag ([#482](https://github.com/DarkPyonix/compose-rust/issues/482), [#458](https://github.com/DarkPyonix/compose-rust/pull/458)).

### Change-marker bars in the code editor gutter

A `Bar` gutter decoration whose colour is `DiffAdded`, `DiffModified` or `DiffRemoved` is drawn as a 4dp change-marker bar to the right of the line-number column, 12dp from the code. A line gets one record, so there is no wire change. When a line carries several, `DiffRemoved` wins over `DiffModified`, which wins over `DiffAdded`. Other `Dot`, `Bar` and `Glyph` decorations and an editor without bars are laid out as before. The editor's state description tells assistive technology which lines changed ([#477](https://github.com/DarkPyonix/compose-rust/pull/477)).

### Accessibility modifier and `accessible_name`

`Modifier` has `label`, `role`, `checked`, `selected` and `semantics(role, label, state)`, so any node can be given a role, a name and a checked or selected state for assistive technology ([#429](https://github.com/DarkPyonix/compose-rust/issues/429)). `Button`, `Switch`, `Checkbox`, `RadioButton` and `Slider` have `accessible_name`, which replaces a visible label and is read instead of an icon-only button's icon name. On the wire this is a new `Semantics` modifier (tag 34) and a new `AccessibleName` property (119). The schema hash changes, so a renderer and a crate from before this change no longer agree. `Modifier::label` keeps each distinct name for the life of the program, at most 4096 of them; a name past that limit is left off, and debug builds print a warning once.

### Node accessibility: roles, states and a click action

The accessibility modifier grows, add-only ([#473](https://github.com/DarkPyonix/compose-rust/issues/473), [#489](https://github.com/DarkPyonix/compose-rust/pull/489)). There are 11 more roles (`TabList`, `Tree`, `TreeItem`, `Menu`, `MenuItem`, `TextBox`, `Heading`, `Navigation`, `List`, `ListItem`, `Group`), the states `expanded`, `disabled`, `focused` and `hidden` (a hidden node leaves the accessibility tree), and a `click` action that calls the node's own click handler, so an assistive tool or an automated audit can press a control without touching the screen. A disabled node, or one whose `enabled` is false, cannot be pressed this way. Roles that Compose has no name for (everything except `Heading` and the ones it already had) are carried as a short role word in the node's state description for now.

Known limits: the `focus` action has a wire bit but is not exposed by the renderer yet (the node's focus requester is created after the accessibility modifier and only when a focus request exists), expanded and collapsed are a description and not an action, and the platform's own roles (macOS tab group, outline, menu) need a change in the Compose fork and are not part of this release. [#473](https://github.com/DarkPyonix/compose-rust/issues/473) stays open for them.

### Window size reports for every whole-dp change, and a first report for tall phone-sized windows

A window that sets `report_size` also receives `WindowSizeChanged` whenever its width or height, each rounded to the nearest whole dp, changes inside a size class, so a host that lays out from the exact size (an HTML and CSS host, for example) can reflow. Use `Window(on_close).report_size(true)` for a `Window` node and `Window::new().with_report_size(true)` for the implicit single window; a window that does not opt in behaves exactly as before. Each whole-dp change while the user drags an edge costs one call into the Host, so only a host that needs the exact size should turn it on ([#467](https://github.com/DarkPyonix/compose-rust/issues/467), [#470](https://github.com/DarkPyonix/compose-rust/pull/470), [#479](https://github.com/DarkPyonix/compose-rust/issues/479), [#489](https://github.com/DarkPyonix/compose-rust/pull/489)).

Separately, a window that is narrow but tall (390 by 844 dp: Compact wide, Medium high) never sent its first size, so `current_window_size()` kept 0 by 0. The renderer now sends it. This needs no opt-in.

### macOS safe area measured again after the window is shown

On macOS the window buttons' safe area (`WindowSafeArea`) could stay `{0, 0, 0}` for a window that never resizes, because the caption was measured once before the title bar was laid out. The Compose fork's macOS window now measures it again after it is shown, a few times at short intervals, and the window modules are pinned at that fork commit ([#468](https://github.com/DarkPyonix/compose-rust/issues/468), [#470](https://github.com/DarkPyonix/compose-rust/pull/470), fork [#182](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/182)). Known limit: this has not been confirmed at a 390 by 844 window yet. An application should keep a fallback for a zero safe area until it has been.

### Windows links the Kotlin/Native static renderer by default

Windows targets now resolve and link the Kotlin/Native static renderer kit (`renderer/build/windows` in a checkout or `compose-rust-renderer-v<ver>-windows-x64.tar.gz` from a release) as `RendererKind::Static` by default, producing a single executable with no GraalVM DLLs beside it ([#332](https://github.com/DarkPyonix/compose-rust/issues/332), [#333](https://github.com/DarkPyonix/compose-rust/pull/333), [#465](https://github.com/DarkPyonix/compose-rust/pull/465)). `DXC_WINDOWS_NATIVE_LIB` and `COMPOSE_RUST_RENDERER_DIR` still override the path.

### Schema hash and wire changes

The schema hash changes in this release, so a renderer and a crate from before it no longer agree; upgrade both together. All changes are add-only:

- `Modifier` tag 34 `Semantics { role, state }`; the property `AccessibleName` (119) carries the label. The `Semantics` role enum grows to 19 values and its state word to 13 bits (checked, selected, expanded, disabled, focused, hidden, and the click and focus action bits).
- Properties `TrackColor` (121), `ThumbColor` (122), `ActiveTrackColor` (123), `ContainerColor` (124), `CheckColor` (125) and `ControlSize` (126) for `Switch`, `Slider` and `Checkbox`.
- Property `ReportSize` (127) on a `Window` node.
- `SetWindow` flags word, bit 1: `report_size` for the implicit window. The record length is unchanged and a window that does not set it writes the same bytes as before.
- Command `RequestFocus` (tag 25) and the `OnCompositionChange` property with its event.
- `WINDOW_REVISION` moves to the fork commit that holds the macOS caption re-measure.

## v0.0.6

### Focus ring only for keyboard focus

A pressable container draws its focus ring only for keyboard focus, at the node's own outer bounds, so a mouse click no longer leaves a ring ([#430](https://github.com/DarkPyonix/compose-rust/issues/430), [#433](https://github.com/DarkPyonix/compose-rust/pull/433)).

### A weighted `Row` or `Column` fills the remaining space

A `Row` with a weight in a `Column` (or a `Column` with a weight in a `Row`) is no longer clamped to its content size on the cross axis, so the content no longer collects at the start when the window grows ([#439](https://github.com/DarkPyonix/compose-rust/issues/439), [#440](https://github.com/DarkPyonix/compose-rust/pull/440)).

### Colours and size for `Switch`, `Slider` and `Checkbox`

`Switch` has `track_color`, `thumb_color` and `size(width, height)`, `Slider` has `track_color`, `active_track_color` and `thumb_color`, and `Checkbox` has `container_color`, `check_color` and `size(side)` ([#426](https://github.com/DarkPyonix/compose-rust/issues/426), [#428](https://github.com/DarkPyonix/compose-rust/issues/428)). A value that is not set is not sent, and the design system draws that slot. On the wire these are the new `TrackColor` (121), `ThumbColor` (122), `ActiveTrackColor` (123), `ContainerColor` (124), `CheckColor` (125) and `ControlSize` (126) properties. The schema hash changes, so a renderer and a crate from before this change no longer agree.

### Accessibility modifier and `accessible_name`

`Modifier` has `label`, `role`, `checked`, `selected` and `semantics(role, label, state)`, so any node can be given a role, a name and a checked or selected state for assistive technology ([#429](https://github.com/DarkPyonix/compose-rust/issues/429)). `Button`, `Switch`, `Checkbox`, `RadioButton` and `Slider` have `accessible_name`, which replaces a visible label and is read instead of an icon-only button's icon name. On the wire this is a new `Semantics` modifier (tag 34) and a new `AccessibleName` property (119). The schema hash changes, so a renderer and a crate from before this change no longer agree. `Modifier::label` keeps each distinct name for the life of the program, at most 4096 of them; a name past that limit is left off, and debug builds print a warning once.

### Modifier `match_parent_size`

`Modifier` has a new `MatchParentSize` (wire tag 31) for a child of a `Box`: it fills exactly the parent's bounds without enlarging the parent, so an overlay such as a press tint needs no size or radius restated ([#431](https://github.com/DarkPyonix/compose-rust/issues/431)). The schema hash changes, so a renderer and a crate from before this change no longer agree.

### Content under the window buttons (`Chrome::FullSize`)

`Chrome::FullSize` lets content start at the top of the window, with no caption strip and no bar discovery, on macOS first ([#345](https://github.com/DarkPyonix/compose-rust/issues/345)). The window size event carries `WindowSafeArea { top, leading, trailing }` in dp, so the app can keep clear of the window buttons, and a new `WindowDrag(bool)` modifier (wire tag 32) makes a node a drag region or removes it from the default drag strip. The schema hash changes (the `WindowSizeChanged` record grows from 32 to 44 bytes), so a renderer and a crate from before this change no longer agree. Windows and Linux dragging follows in a later release.

### Press feedback

Every pressable surface (`on_click` containers, `Clickable` nodes and the toolkit `Button`) scales to 0.975 and shows a faint tint while pressed, inside the renderer with no Host round trip, and `Modifier::PressFeedback { scale, tint_alpha }` (wire tag 33) changes or turns it off ([#305](https://github.com/DarkPyonix/compose-rust/issues/305)). The schema hash changes.

### Disabled `Button` label keeps its contrast

A disabled `Button` keeps its label at 3:1 or better against its faded fill on the page, a card and a raised card, and an active `Button` is unchanged ([#432](https://github.com/DarkPyonix/compose-rust/issues/432), [#447](https://github.com/DarkPyonix/compose-rust/pull/447)).

### Schema hash and known test issue

The schema hash changes in this release: a `Modifier` gains `MatchParentSize` (tag 31), `WindowDrag` (tag 32) and `PressFeedback` (tag 33), and the `WindowSizeChanged` event record grows from 32 to 44 bytes. A renderer and a crate from different versions no longer agree, so upgrade both together. Three `fr33_1_*` paste and menu tests fail on a Mac with a non-English locale. They fail the same way on `develop` without these changes, so this is not a regression ([#436](https://github.com/DarkPyonix/compose-rust/issues/436)).

## v0.0.5

### Multiple windows in one process

An application can open, focus, move, resize and close several windows in one process on macOS
([#394](https://github.com/DarkPyonix/compose-rust/pull/394)), Linux
([#393](https://github.com/DarkPyonix/compose-rust/pull/393)) and Windows
([#395](https://github.com/DarkPyonix/compose-rust/pull/395)), and tear a tab out into its own
window or merge it back into another window's tab bar
([#355](https://github.com/DarkPyonix/compose-rust/issues/355),
[#370](https://github.com/DarkPyonix/compose-rust/pull/370),
[#382](https://github.com/DarkPyonix/compose-rust/pull/382), FR-53).

### Slot table buffer pool leak fixed and Compose fork pinned to 1.11.1-ext.2

- `Recomposer` and `NodeWriter` no longer grow their attribute and child-list buffer pools by one
  empty buffer per node update and group creation, and `NodeWriter::forget` clears per-node
  property, border, modifier-slot and size-token maps when a node is removed
  ([#362](https://github.com/DarkPyonix/compose-rust/issues/362),
  [#363](https://github.com/DarkPyonix/compose-rust/pull/363), NFR-17).
- The Compose fork and window modules are pinned to `1.11.1-ext.2` with multi-window window-host
  support ([#360](https://github.com/DarkPyonix/compose-rust/pull/360),
  [#369](https://github.com/DarkPyonix/compose-rust/pull/369)).

### Accessibility tree and immediate input turns on macOS

The Kotlin/Native macOS renderer exports the Compose semantics tree (roles, labels, states,
focus and actions) to `NSAccessibility` so VoiceOver and `AXPress` automation see and activate
controls, and mouse and keyboard events drive a host turn immediately without waiting for the
100 ms idle timer ([#418](https://github.com/DarkPyonix/compose-rust/issues/418),
[#420](https://github.com/DarkPyonix/compose-rust/pull/420), NFR-8).

### Authoring toolkit and widget styling

- Containers (`Row`, `Column`, `Box`, `Card`, `Surface`, `ScrollColumn`, `ScrollRow`) accept
  `on_click`, gaining keyboard focus, Enter/Space activation, `Button` semantics and hover/press
  feedback ([#372](https://github.com/DarkPyonix/compose-rust/issues/372),
  [#379](https://github.com/DarkPyonix/compose-rust/pull/379), FR-55).
- `Button` and `Chip` builders expose `max_lines` and `overflow`
  ([#373](https://github.com/DarkPyonix/compose-rust/issues/373),
  [#386](https://github.com/DarkPyonix/compose-rust/pull/386), FR-57),
  and disabled `Button` and `IconButton` controls honour explicit `color` and `background_color`
  overrides ([#387](https://github.com/DarkPyonix/compose-rust/issues/387),
  [#411](https://github.com/DarkPyonix/compose-rust/pull/411)).
- `TextField` honours node `Background`, `Border`, `Shape` and `Padding` modifiers
  ([#376](https://github.com/DarkPyonix/compose-rust/issues/376),
  [#381](https://github.com/DarkPyonix/compose-rust/pull/381), FR-56).
- `Modifier` exposes `shadow`, `shadows`, `offset`, `corner_radii` and `clip`, and `Text` and
  `TextField` expose `font` and `word_break`
  ([#374](https://github.com/DarkPyonix/compose-rust/issues/374),
  [#375](https://github.com/DarkPyonix/compose-rust/issues/375),
  [#390](https://github.com/DarkPyonix/compose-rust/pull/390), FR-58;
  [#378](https://github.com/DarkPyonix/compose-rust/issues/378),
  [#380](https://github.com/DarkPyonix/compose-rust/pull/380);
  [#392](https://github.com/DarkPyonix/compose-rust/issues/392),
  [#412](https://github.com/DarkPyonix/compose-rust/pull/412), FR-60).
- Apps can register bundled font stacks as the theme default
  ([#304](https://github.com/DarkPyonix/compose-rust/issues/304),
  [#349](https://github.com/DarkPyonix/compose-rust/pull/349), FR-52).
- Small circular `ProgressIndicator` rings scale their stroke width in proportion to their size
  ([#377](https://github.com/DarkPyonix/compose-rust/issues/377),
  [#391](https://github.com/DarkPyonix/compose-rust/pull/391), FR-59).
- `Select`, `Dropdown::closed(true)` and `MenuItem` draw closed-field selectors and left-aligned
  menu rows with below-anchor popups
  ([#383](https://github.com/DarkPyonix/compose-rust/issues/383),
  [#416](https://github.com/DarkPyonix/compose-rust/pull/416), FR-64).
- `ScrollState` and `remember_scroll_state()` read and drive `ScrollColumn` and `ScrollRow`
  offsets ([#385](https://github.com/DarkPyonix/compose-rust/issues/385),
  [#414](https://github.com/DarkPyonix/compose-rust/pull/414), FR-62).
- `Canvas` and `DrawListBuilder` support `view_box(w, h)` and `fractional()` coordinate scaling
  ([#388](https://github.com/DarkPyonix/compose-rust/issues/388),
  [#415](https://github.com/DarkPyonix/compose-rust/pull/415), FR-63).
- `Row` and `Column` size their cross axis to `IntrinsicSize.Min` when `equal_height` /
  `equal_width` is set or when a child carries `fill_max_height` / `fill_max_width`
  ([#389](https://github.com/DarkPyonix/compose-rust/issues/389),
  [#413](https://github.com/DarkPyonix/compose-rust/pull/413), FR-61).

### A shadow follows the corner radius of the node that casts it

A node can carry several `Shadow` modifiers, each its own layer with an offset, a blur, a
spread and a colour with alpha, drawn in order behind the node ([#299](https://github.com/DarkPyonix/compose-rust/issues/299),
FR-42.3). A shadow now takes its outline from the node's `Shape` or `ShapeRole` when the node
has no `CornerEach`, so a `Column`, `Row`, `Box` or `Card` with a corner radius casts a
rounded shadow instead of a rectangular one. The wire format is unchanged. All renderers
share the one Kotlin file that draws it.

## v0.0.4

### Windows windows have caption buttons again

The GraalVM Windows renderer, which is what ships, opened its window without configuring the
native chrome and composed the content without a caption or window actions, so minimise,
maximise and close were never drawn (#339). It now configures the window from the
application's `Chrome`, provides a 32 dp caption and the native window actions, and tracks the
maximised state. The window modules are pinned to a revision whose system chrome is on by
default and whose maximise button answers `HTMAXBUTTON`, so Snap Layouts open from it.

### The mouse wheel scrolls the right way on Windows

The Win32 window passed the wheel delta through unsigned, so content scrolled against the
wheel. The pinned window modules negate it into Compose's direction, and a script test checks
the sign.

### Button and Chip labels stay on one line

A parent that squeezed a Button or Chip measured its label at the width of one Hangul syllable,
so a label like 보내 broke over two lines. Labels are now one line with an ellipsis. A Button
still honours `max_lines` and `overflow` when the Host sets them (#328).

### Releases and documentation

The crates.io workflow installs every library the Linux renderer links and can be run by hand
for an existing tag. The documentation records that Windows ships the Kotlin/Native static
renderer only, and the SPEC adds centered sheets, full-size content under the title bar and
lists of shadows.

## v0.0.3

### The host can measure text, and text carries CSS properties

A new call across the boundary lets the Host ask the Renderer how big a piece of text is
laid out, before the frame is drawn. It measures at the region's zoom, so CSS pixel text is
set at font scale one and the answer matches what is drawn. Min-content and max-content
widths come from the intrinsics they are named for, and a tab stop lands where its
characters sit in a laid out line. The text properties HTML text needs are in the schema,
with tags 110 to 116, and a run can be a link. The Windows renderer answers the measure call
too. The protocol and the schema hash are regenerated, so a renderer built before this
release does not match this crate's schema.

### CodeEditor

`CodeEditor` is a widget the Renderer owns: it counts lines the way a language server does
(`a\r\nb\rc\n\r\nd` is five lines), draws them one at a time, and takes input through the
platform input session. The drawn path (`CodeEditorPath.Drawn`) uses Compose's clipboard and
key events rather than the Java toolkit, ignores a clipboard that fails to read, and is linked
into the Windows renderer. The widget, its records and its events are in the schema and the
protocol. The Rust component that declares it and the per design system code styles are not in
this release yet: they move to dioxus-compose and the Compose fork.

### Brushes can be released

`release_brush` releases a brush the application registered. A brush that never left the
Host is dropped without a release. Ids are counted rather than derived from how many brushes
are registered, so a released id is never handed out again.

### Linux CI installs the renderer's link libraries

Since 0.0.2, `build.rs` links the prebuilt Linux renderer, which needs X11, Xext, GL,
fontconfig and FreeType. The Ubuntu jobs of the Test workflow that link it now install their
development packages (#261).

## v0.0.2

### The GraalVM X11 window reads like the Kotlin/Native one

The GraalVM X11 window joins the AT-SPI accessibility bus, so Orca and other libatspi
clients read it and press its buttons, with the protocol code the Kotlin/Native X11 window
uses. `NO_AT_BRIDGE=1` turns it off. Its keys and input method composition now go through
the same key table and composition model as the Kotlin/Native window: a plain ASCII key is
one key event carrying its character, where the window used to commit the character as text
as well. Both live in the Compose fork's window modules.

Work sent to a Linux window's thread from another thread, a Host worker's frame request or
a coroutine resumed on `Dispatchers.Main`, now ends the window's wait at once rather than up
to a frame later. Both Linux windows and the Kotlin/Native macOS window report the same
latency and resize numbers to the parity checks, which CI runs on both paths.

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

### A calculator's keys are each platform's own

`Button` takes a `kind`. `ButtonKind::ActionKey` says the button is one key of a dense grid,
and the design system draws its own calculator's key: a circle on macOS, a rectangle at the
4 dp control corner on Windows 11, a rounder 10 dp lozenge on Deepin. The application names
no radius and asks no question about which system is running; the variant still chooses the
fill. A button that names no kind is unchanged and sends nothing new. The calculator sample
declares every key of its pad this way.

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
