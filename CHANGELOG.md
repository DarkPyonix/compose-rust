# Changelog

## Unreleased

### Compose fork moved to 1.11.1-ext.6

The renderer now builds with the Compose fork's `1.11.1-ext.6` (tag `v1.11.1-ext.6`) for Compose, the window modules and skiko-awt alike. On Windows a toast's answer (a press on it, or the permission) now asks for the frame that delivers it through a callback the renderer registers with the fork, instead of the fork calling a compose-rust symbol by name; nothing changes for an application. The fork's other window changes since ext.5 (the CSS cursor keywords, the caption strip height by title bar mode, UI Automation patterns, native views on Windows and Linux, the GDI route as the Windows default) reach applications as the renderer adopts them.

### Fonts that load on the first letter that needs them

`lazy_asset(AssetKind::Font, bytes)` returns the id `asset()` would give the same bytes and sends nothing. `FontRef::LazyAsset(id)` names such a font in a font list, and `FontStack::lazy_family(ids)` in a stack. The Renderer asks for the bytes with the new `AssetWanted(id)` event the first time a letter that no font before it has reaches it, once per id, and the Host answers with the `RegisterAsset` it already had, on the next batch. Until the bytes arrive the letter is drawn as a missing glyph, never in a font of the machine's own, and measuring and drawing agree before and after. An application can ship a cut-down Korean face for the first batch and keep the full 1.5 MB face on the Host until a letter such as 똠 needs it. A request for an id the Host has nothing for is one `ProtocolError::UnknownAsset`, and a face let go of with `release_asset` is asked for again when it is needed. The schema hash moves (event 71, payload 46, font candidate kind 4) (FR-48.4, [#1057](https://github.com/DarkPyonix/compose-rust/issues/1057)).

### A Linux window redraws when the display scale changes

The Kotlin/Native Linux renderer now draws a new frame as soon as `Xft.dpi` changes, at the new scale. Before, the old frame stayed on the screen until something else asked for one. With the fork's `1.11.1-ext.4` window, which keeps the window's size in dp, the window comes back at the same size in dp and sharp. The CI checks this, and the Windows renderer's sharpness at 100, 125, 150 and 200% and across a change of scale, with the renderer's own `fr82_*` checks ([#18](https://github.com/DarkPyonix/compose-rust/issues/18)).

### Build failures no longer send a crate user to a script only a checkout has

When the build cannot use a renderer, its message names the repository's build scripts only as something to run in a checkout of the compose-rust repository, with its address. An incomplete or unreadable Windows renderer kit now says to delete the cached download so the next build fetches it again, and an unreadable MSVC library points at the C++ build tools; before, all of these said to run `build-windows.sh`, which the published crate does not carry (NFR-10).

### A scroll container removed in the middle of a frame's reports no longer reports

A frame's scroll reports go out one after another, and the Host's answer to one could remove a container whose report came later in the same frame. That container still reported with the handler the Host had just forgotten, the Host refused it, and the desktop renderer printed `dispatch_event returned -1` (protocol error 100). Ember saw it at its editor window's first frame. A container removed during the reports now sends nothing (FR-80, #1077).

### `CodeEditor` takes font feature settings

A coding font such as JetBrains Mono draws `->`, `=>` and `!=` as ligatures by default, and an editor had no way to turn that off: `FontFeatureSettings` was taken by `Text` and `TextField` only. A `CodeEditor` now takes the same feature records and sets its text with them, so `liga 0` and `calt 0` draw `->` as two glyphs a column each. The line numbers keep the design system's features (FR-83, #1078).

### `release_asset(id)` lets go of a registered font or picture

`compose_rust::release_asset(id)` releases what `asset(kind, bytes)` registered, of any kind, and puts one `ReleaseAsset` on the next batch. It returns false for an id that was never registered or was already released, a registration that has not left yet is dropped without a release, and the id is never handed out again. Before this only brushes could be released (`release_brush`), so a `@font-face` font registered through `asset()` stayed in the Renderer for the life of the app (FR-48 rule 5).

### `TextFieldHandle` gives a live text field a new text

A `TextField` takes its `text` when its node is made, so an application could not reset or clear a field already on screen except by giving it a new `key`, which made a new node. `use_text_field_handle()` returns a handle that `TextField!(..., text_handle = &handle)` binds to the field; `handle.set_text(text)` (or `set_text_with_selection`) puts one `SetText` for that node on the next batch, the empty text included, with the last text asked for before the batch. The field stays uncontrolled: the Renderer keeps the caret where it still fits and waits for an input method's composition to end first (FR-5, #1075).

### A memory report for a blank window on Linux and Windows

`compose-rust/tests/fixtures/blank-window` is one 800 by 600 dp window with one empty box, so what its process holds is what the renderer, its graphics surface and the window cost. A development CI dispatch with the `memory_report` suite builds it against the Kotlin/Native renderer of the same commit and starts it three times on ubuntu-latest (under Xvfb) and on windows-latest. It reads PSS on Linux and the private working set on Windows, 10 and 60 seconds after each start, and puts the median of the three in the job summary, with every sample kept as an artifact. The report fails only when the window does not come up or nothing could be read, never on a number. No default suite runs it, and nothing it measures gates a merge (#59).

### CI reads the `.msi` back and requires the DPI check of the Windows App Certification Kit

The Windows job that packs with Velopack now builds the `.msi` twice, with `--instLocation PerUser` and `PerMachine`, and `scripts/tests/msi-info.ps1` opens each one's Property table and checks the product name, the manufacturer, the codes and that the version is the one packed. The two jobs that run `appcert.exe` on the `.msix` and `.msixbundle` (`package-windows.yml`, `package-windows-host.yml`) both require `DPIAwarenessValidation` to pass, and FR-22.6 no longer calls that check manual. The Store submission and the Store's update of an installed bundle still wait for a Partner Center account ([#3](https://github.com/DarkPyonix/compose-rust/issues/3)).

### A button that names no kind sends no kind

A `Button` that names no `kind` sends no `ButtonKind` record. It sent one with the value 0, which the renderer reads as no kind at all, so every plain button cost the Host one record for nothing. A button that drops a kind it had still sends the 0 once, which returns it to the standard button. Nothing changes on screen, and the schema and its hash do not move.

### Compose from fork test compares the fork with its own baseline

The workflow no longer builds Compose a second time from JetBrains' sources and the three patches. It compares the fork's publication with a stored baseline of the fork line, one digest per published module (`renderer/scripts/compose-baseline/`), and fails on any module that differs unless the fork changed that module's sources since the baseline's revision. `.github/scripts/refresh-compose-baseline.sh <run id>` refreshes the baseline when the pin moves ([#361](https://github.com/DarkPyonix/compose-rust/issues/361)).

### The macOS renderer's Kotlin heap stops near what is in use

The macOS renderer tells the Kotlin/Native collector to let its heap be 80 % full after a collection, where the default is 50 %. An application that opened eight screens four times held 69 to 87 MB of heap pages for about 20 MB of objects; it now holds about 53 MB. On Ember (1440x900, median of three runs) the process footprint after those screens goes from 182 to 200 MB down to 158 to 168 MB, and from 129 MB to 118 MB after five idle minutes. The cost is about a quarter more process CPU while screens are being opened, on the collector's own thread (#939, NFR-3).

### The build finds its renderer by schema hash

A git dependency on a revision after a release builds. The crate's version stays the same between two releases while its schema moves on, and the build used to fetch the release for that version, whose renderer was built from the older schema, and then stop at the schema check with nothing else to try. The build now reads the schema hash of the release's renderer, and when it is not the crate's, fetches the renderer published for the crate's schema from the `renderer-dev` release (`compose-rust-renderer-schema-<hash>-<target>.tar.gz`, verified against its `.sha256` like any other). When neither matches, the build stops and names the crate's schema hash, each renderer's hash and both addresses. A released version costs no extra request. The cache is keyed by schema hash and target (`renderer/schema-<hash>/<target>`) instead of by version, so two revisions of one version never share an entry; an entry an earlier build left under the version is used only when its renderer says it was built from the same schema. A renderer that says it was built from the crate's schema is no longer refused for the version written in its `renderer.version`. `COMPOSE_RUST_RENDERER_URL` moves both addresses to a mirror; it is optional, and a build without it uses GitHub as before ([#293](https://github.com/DarkPyonix/compose-rust/issues/293)).

### Rich text in a text field (wire)

### Rich text in a text field (wire and Rust API)

A `TextField` can keep character and paragraph formatting that the Renderer owns: `TextField!(rich = &state, on_format = ..., on_rich_change = ...)` turns it on, `RichTextState::format(Format::Bold(Toggle::Flip))` formats the selection, and the field reports the formatting at the selection and its document. `RichDocument` has `to_markdown()` and `from_markdown()` for storing a document. This adds the properties 220 to 222, the commands `FormatText` and `SetRichText`, the events `TextFormatAtSelection` and `RichTextChanged`, and the enums `FormatOp`, `ParagraphRole` and `ListKind`, so the schema hash moves; a `TextField` without `rich` sends the records it always did. The Renderer only stores the new requests for now. See [#638](https://github.com/DarkPyonix/compose-rust/issues/638).


### Widgets are functions and call macros, not builder chains

This is a breaking change. Every widget is now one function that takes all of its parameters, and the call macro of the same name, which takes them by name with defaults. The builder chains (`Column().modifier(..).content(..)`, `Button(..).on_click(..)`) are gone, so a call site written with them no longer compiles. The parameters come in one order: what the widget cannot do without, its handler, `modifier`, the optional parameters, and the content last. The macro takes the leading ones by position and the rest as `name = value`, and a container's children as a trailing `{ .. }` block. A builder method is the parameter of the same name, a method without arguments is `name = true`, and a method with several arguments is a tuple (`size = (900.0, 600.0)`). A widget's main handler is the positional argument after its data (`Button!("Save", move || ..)`, `Slider!(value, move |value| ..)`), and an optional handler or slot is `Some(closure)`, whose closure names the type of its argument because rustc does not infer it inside `Some`. A lazy list's content stays named (`LazyColumn!(content = |list| ..)`), `Icon::asset(id)` is `IconAsset!(id)`, and `color_if(opt)` is `color = opt`. `compose_rust::ui::next`, where v0.0.12 had these functions beside the builders, is gone: they are `compose_rust::ui` now, so `use compose_rust::ui::next::*` becomes `use compose_rust::ui::*`. What reaches the renderer is unchanged: each widget writes the same records it wrote before, and the schema hash does not move.

```rust
// Before
Column().modifier(m).spacing(8.0).content(|| { Text("a").font_size(14.0); });
// After
Column!(modifier = m, spacing = 8.0, { Text!("a", font_size = 14.0); });
```

`experiments/fr39-codemod`, on the `develop` branch, rewrites the builder chains of a crate into call macros. It reports the few call sites it leaves for a hand edit, such as a builder bound with `let`.

### The event types are non-exhaustive

`HostEvent` and `EventPayload` are `#[non_exhaustive]`, so a new event or a new field is not a breaking change after 1.0. This is a breaking change now for code outside the crate that matches `EventPayload` without a `_` arm, builds a `HostEvent` with a struct literal, or destructures one without `..`. Add a `_` arm to the match, build the event with `HostEvent::new(node_id, handler_id, payload)`, and end the pattern with `..`. `EventPayload` variants are still built the same way. The wire format and the schema hash do not change ([#23](https://github.com/DarkPyonix/compose-rust/issues/23), [#105](https://github.com/DarkPyonix/compose-rust/issues/105)).

### `SemanticsState` is non-exhaustive

`SemanticsState` is `#[non_exhaustive]`, so a new field is not a breaking change after 1.0. This is a breaking change now for code outside the crate that builds one with a struct literal, with or without `..Default::default()`, or destructures one without `..`. Start from `SemanticsState::default()` or from the constructor named after a field, and chain the new `with_` method of each further field: `SemanticsState { checked: Some(true), ..SemanticsState::mixed() }` becomes `SemanticsState::mixed().with_checked(true)`. There is one per public field (`with_checked`, `with_mixed`, `with_selected`, `with_expanded`, `with_disabled`, `with_focused`, `with_hidden`, `with_click_action`, `with_focus_action`, `with_has_popup`). The fields stay public, so reading one and assigning to a `let mut` state still work. The wire format and the schema hash do not change ([#767](https://github.com/DarkPyonix/compose-rust/issues/767)).

### A switch can lift its thumb and draw a line around its track

`Switch!` takes `thumb_elevation`, a soft shadow under the thumb in dp, and `track_border`, a `(width, paint)` line around the track in both states. Leaving either out draws the switch as before in every design system: none of them shadows the thumb, and each keeps its own border. These add properties 212 and 213, so the schema hash moves and a renderer built before this change refuses the Host ([#818](https://github.com/DarkPyonix/compose-rust/issues/818)).

### `ScrollState` knows how large its viewport is

`ScrollState::viewport_size()` is the visible length of a `ScrollColumn` or `ScrollRow` along the axis it scrolls on, in dp, so a "scroll one page" control moves by it instead of guessing from the window. The state now reads the renderer's `ScrollChanged` report, in pixels divided by the window's density and zoom step, instead of `RangeRequested`. `offset()` and `max_offset()` return the same values as before, and all three are `0.0` until the first report. The `RangeRequested` report is still on the wire for a host that asks for it ([#818](https://github.com/DarkPyonix/compose-rust/issues/818)).

### A page that measures nodes is answered when the window is resized

A Host that lays its own page out, as an HTML view does, answers a window size by asking the renderer to measure the nodes on the page before the call returns. The size was sent from inside the layout pass that measured the window, where Compose refuses to start the layout a node measurement is, so every resize logged protocol error 3, "measure request N could not be measured: performMeasureAndLayout called during measure layout", and each such node came back unmeasured. The window's size, and the size class of a node that asked with `observe_size`, are now sent once that pass is over, in the same frame and before it is drawn (FR-20, FR-28). A `CodeEditor` first put on screen inside a split pane or a lazy list is attached during a measure pass too, so what it has to say at that moment is sent just after the pass; everything it says later, a key's echo included, is sent at once as before (FR-38).
### A `CodeEditor`'s two scroll reports arrive together

A `CodeEditor` with both an `OnCodeScrolled` and an `OnScrollChanged` handler now sends `ScrollChanged` straight after `CodeScrolled` for the same move, in the same frame, and `ScrollChanged.y_px` equals `CodeScrolled`'s offset. Before, `ScrollChanged` for a move could reach the Host a frame after `CodeScrolled`, or in the same frame with the offset of the frame before. `CodeScrolled` is sent when it always was. Such an editor sends at most one `ScrollChanged` in a frame even when it moves along its lines and down the page in that frame, so a report that does not come with a `CodeScrolled` (a move along the lines, a new range, coming to rest) is sent when the frame is composed, which can be one frame later than before. An animated `ScrollTo` on a `CodeEditor` now moves down the page towards the offset asked for. It moved the other way. An editor with one of the two handlers, and every other scroll container, reports as before ([#591](https://github.com/DarkPyonix/compose-rust/issues/591)).
### A Checkbox can be mixed, take an outline colour, and round its corners under Material 3

`Checkbox!` takes `indeterminate = true` for a box that stands for a set of which only some are checked. It wins over `checked`: the box is filled as a checked box is, with a dash in the check colour instead of the check, assistive technology hears it as mixed, and a press or Space asks for `true`. It travels as the `Indeterminate` property (tag 216, a boolean, only on a `Checkbox`), sent only while it is true. `outline_color` is the box's border in every state (`OutlineColor`, tag 217, a `Paint`, only on a `Checkbox`), drawn even where the design system draws none. A Checkbox's corners now follow `Modifier::corner_radius`, `corner_radii` or `shape_role` under Material 3 as well, with no colour or size given. For `Modifier::semantics`, `SemanticsState::mixed()` gives the checked field its fourth value, 3, which reads as mixed, and `SemanticsState` gains a public `mixed` field. The schema hash changes ([#767](https://github.com/DarkPyonix/compose-rust/issues/767)).

### A bottom sheet's content starts below its handle

A sheet's first line no longer sits level with its drag handle. Material 3 keeps 22 dp above and below the handle, as Compose's `BottomSheetDefaults.DragHandle` does, and every other design system keeps its own sheet padding there. A sheet with `handle(false)` keeps no space for one. No schema change ([#914](https://github.com/DarkPyonix/compose-rust/issues/914)).

### Window breakpoints the application chooses

`current_window_breakpoint(&[560.0, 900.0, 1279.0])` returns which of the application's own width buckets the window is in: the number of breakpoints the width has reached, so a width exactly on a breakpoint is in the bucket above it. The composable that calls it runs again only when the bucket changes, not on every size report, and in a multi-window application it reads its own window. It does not turn on `report_size`: a window reports a resize inside one size class only when it opts in, so without it the bucket follows the width at each class change. Nothing changes on the wire ([#301](https://github.com/DarkPyonix/compose-rust/issues/301)).

### A request to go back reaches the application

A layout container and `Window!` take `on_back_requested`. Its handler hears Android's back gesture and button, Escape, a mouse's back button and the swipe in from the leading edge on iOS, when no open overlay and no focused control took it first. The innermost node with a handler hears it first. `BackRequested::consume()` ends the request there; a handler that leaves it passes it to the next handler out, and after the last of them to the platform, which on Android does what it does with a back nobody claimed. `BackRequested::source()` says which of the four it was, as a `BackSource`. A window with no handler does not claim the platform's back at all.

On the wire this is the event `BackRequested` (tag 70, 20 bytes: the header and a `BackSource` word) and the handler property `OnBackRequested` (tag 223). The schema hash changes, so a renderer and a crate from before this change no longer agree.

### A bottom sheet rests at a medium and a large height, and a pull need not close it

`Sheet!` takes `detents` and `drag_to_dismiss`. `detents = SheetDetents::MEDIUM | SheetDetents::LARGE` opens a bottom sheet at half the window; pulling it up opens it out to the window's height less the top safe area and the design system's margin, and pulling it down brings it back to half. One of the two alone is that one height. Which height the sheet is at is the renderer's state and is never reported. `drag_to_dismiss = false` stops a pull at the lowest resting height and sends no dismissal for it, so a sheet holding unsaved input is not closed by a stray drag; Escape, the platform's back and a press outside still call `on_dismiss`. A centered sheet and one that comes from the side take neither. They travel as the properties `SheetDetents` (tag 224, the bits of the set) and `SheetDragToDismiss` (tag 225, a boolean), each sent only when it differs from the default, so a sheet that sets neither writes what it did before. Bits that name no height are a protocol error. The schema hash changes.

### `Modifier::backdrop`: a blur the application chooses

`Modifier.backdrop(blur_dp, saturation, tint)` blurs what the application drew behind a node, inside the node's outline, multiplies its saturation and lays `tint` over it. The values are the application's and draw the same on all seven design systems, where `material` leaves the look to the design system. It is for a bar, a popover or a sheet that has to look like glass everywhere, Material 3 included.

The renderer can only blur what it recorded apart from the node. That is the earlier children of the same `Box`, a `Scaffold`'s page under its bars, and the window's page under a sheet, a dialog or a popover. A node with a backdrop anywhere else is painted with `tint` at full opacity. The same happens under reduced transparency, where the build cannot blur (Android before 12), and for a blur or a saturation that is negative or not a number, which is also reported as a protocol error. The desktop behind the window is never blurred. On a node that has a `material` as well, the backdrop is what is drawn.

On the wire it is the modifier `Backdrop` (tag 47, two words: the blur and the saturation, then the tint). The schema hash changes.

### `CompactNavigation::Floating`, and the top of the safe area

`Navigation!(.., compact = CompactNavigation::Floating)` draws the destinations of a compact window as a pill-shaped bar floating over the bottom of the page, on all seven design systems. The bar is held off the sides of the window and off its bottom by the design system's margin and the bottom safe area. The page runs on under it and shows through it: where the design system's floating surfaces are glass (Liquid Glass and Cupertino) the page behind is blurred the way `Modifier::backdrop` blurs it, and elsewhere the bar is the system's own bar colour, lifted off the page. A `ScrollColumn`, a `LazyColumn` and a `LazyGrid` in the page end their content lower by what the bar covers, so the last row scrolls clear of it. It works where the `Navigation` holds its page and where it sits in a `Scaffold`'s bottom slot. Medium and Expanded windows keep the design system's rail and drawer. It travels as tag 3 of the `CompactNavigation` property (185). The schema hash changes.

**Behavior change.** `window_size().safe_area.top` now carries the strip the system keeps at the top of the window, a status bar or a notch, where it used to carry only a desktop window's caption. On a phone it was 0 before. A window with both reports their sum. An application that added its own status bar padding on a phone now gets that room twice: remove its own padding and use `safe_area.top`.

## v0.0.12

### Fixed from v0.0.11's known issues

The Windows window pin moves to `146d920b`. A window resized by dragging an edge on Windows 10 and Windows Server 2022 no longer shows a gap at the moving edge: the resize check measured 0 of 24 faulty drags on both Windows Server 2022 and 2025, and that check is strict again. A Windows window also keeps its size in dp when the display scale changes while it is open.

### Call macros: the widgets as functions in `ui::next`

`compose_rust::ui::next` has every widget as one function that takes all of its parameters, plus a call macro of the same name, so `Row!(modifier = m, { .. })` replaces a `Row` builder chain. `#[Composable(widget)]` gives a function its call macro and its `#[default(..)]` values. Each function writes the same bytes as the builder chain it replaces. The samples and tests now use the call macros. This is additive: the builder API is still public in v0.0.12 and is removed before 1.0 ([#23](https://github.com/DarkPyonix/compose-rust/issues/23)).

### A macOS window gives back its memory when it closes

A Kotlin/Native macOS window now releases its Metal surface when it closes, and the renderer reads the VoiceOver setting once and again only when it changes, instead of on every frame. Opening and closing windows in one process no longer makes the next one slower; a test opens and closes 20 windows and checks that frame time and memory stay bounded. The Compose fork's window pin moves to `98002181` ([#857](https://github.com/DarkPyonix/compose-rust/issues/857)).

### A `Material` added after the first frame keeps the window opaque

Whether a window gets a material backdrop is decided once, when the window opens. A `Material` node mounted after the first frame used to make the page translucent over a window with no backdrop behind it; the page now stays opaque ([#743](https://github.com/DarkPyonix/compose-rust/issues/743)).

### A `Button`'s text style reaches its child `Text`

A `Button`'s `type_role` and `font_weight` now apply to a `Text` inside it, unless that `Text` sets its own. No schema change ([#500](https://github.com/DarkPyonix/compose-rust/issues/500)).

### An SVG ellipse with a gradient fill draws its gradient

On the desktop, iOS and the web, an SVG `<ellipse>` filled with an `objectBoundingBox` gradient drew solid black. The renderer now turns such an ellipse into an equivalent path before Skia draws it ([#678](https://github.com/DarkPyonix/compose-rust/issues/678)).

### Each desktop window keeps its own pointer position

The desktop renderer kept one last pointer position for the whole process, so a wheel scroll in a second window before the pointer moved there used another window's position. Each window now keeps its own ([#854](https://github.com/DarkPyonix/compose-rust/issues/854)).

### A sheet's handle can be turned off, and a bottom sheet clears the bottom safe area

`Sheet::handle(false)` removes a bottom sheet's drag handle. It travels as the `SheetHandle` property (tag 214, a boolean, only on a `Sheet`), sent only to turn the handle off, so a sheet that keeps it writes no record. The sheet still drags shut the same way. A bottom sheet now pads its content by the larger of the bottom safe area and the keyboard, so its last row ends above the gesture handle or the navigation bar while its surface still runs to the bottom edge. The schema hash changes.

### The window size event carries the bottom safe area

`WindowSafeArea` has a `bottom` side in dp: the room the system keeps at the bottom edge, which is the navigation bar or gesture handle on Android and the home indicator on iOS. The renderer measures it from `WindowInsets.safeDrawing` with the keyboard left out, and a desktop window sends 0. It travels as `safe_area_bottom`, appended to the end of the `WindowSizeChanged` record, which grows from 44 to 48 bytes; every field before it keeps its offset. A change of the bottom side alone sends the event again, as the other sides do. The schema hash changes, so a renderer and a crate from before this change no longer agree.

### Dashed borders

`Modifier::border_dashed(width_dp, paint, dash_dp, gap_dp)` draws a uniform border around the node's outline as repeating dashes of `dash_dp` and gaps of `gap_dp` in dp, following the node's corner shape (`Shape`, `ShapeRole`, `CornerEach`, `CornerCurve`). It writes `Border` together with the new `BorderDash` modifier (tag 42, two `f32` in the first word) and clears any previous `border_each` or `border_side`, while calling `border`, `border_each` or `border_side` clears `BorderDash`. A non-positive dash or gap falls back to a solid border. The new modifier moves the schema hash ([#817](https://github.com/DarkPyonix/compose-rust/issues/817)).

### Hover style, focus ring and press style

`Modifier::hover_style(HoverStyle)` sets how a pressable node (a container with `on_click`, a `Clickable` node, or a toolkit `Button`) looks while a mouse is over it: `HoverStyle::fill(paint, alpha)` lays that paint at that alpha in place of the default `OnSurface` 8% layer, `.border(paint)` swaps the node's border colour until the mouse leaves, and `.content_alpha(a)` multiplies the whole node's opacity. Hovering still sends nothing to the Host. `Modifier::focus_ring(paint, width_dp, offset_dp)` sets the keyboard focus ring; a positive offset draws it that far outside the node's outline with its corners grown by the same amount, as CSS `outline-offset` does, and it takes no room. Focus a pointer gave still draws no ring. `Modifier::press_style(PressStyle)` sets the press tint's colour (`PressStyle::tint(paint)`, at the alpha `press_feedback` gives or the default) and the press-in curve (`.press_easing(x1, y1, x2, y2)`); the release keeps its curve. The toolkit `Button` now has the default hover layer and draws the keyboard focus ring by the same rules as a pressable container. These are the new modifiers `HoverStyle` (tag 43), `FocusRing` (tag 44) and `PressStyle` (tag 45), whose optional paints use a zero word for none, and they move the schema hash. A node without them looks and behaves as before ([#822](https://github.com/DarkPyonix/compose-rust/issues/822)).

### Syntax roles have a font style

A `CodeEditor` syntax span can carry a font weight, italic and underline as well as a colour: `SyntaxSpan::italic`, `bold`, `with_weight`, `underline` and `with_style`. `style_for_role` and `style_for_highlight` give the defaults: comments are italic, types weight 500, and everything else keeps the editor's font. `SyntaxSpanRecord` grows from 28 to 32 bytes with a `style` field at offset 28, so the schema hash changes ([#863](https://github.com/DarkPyonix/compose-rust/issues/863)).

### SVG arcs with packed flags draw right on Android

An SVG path writes an arc's two flags as single digits that need no separator, as in `a3 3 0 100-6`. Android's path parser read `100` as one number and drew such a path wrong. The renderer now separates the flags before the path is parsed, on every platform ([#842](https://github.com/DarkPyonix/compose-rust/issues/842)).

### Declared roles reach VoiceOver in the Kotlin/Native macOS window

A group, list, tab list or other declared role now reaches `NSAccessibility` in the Kotlin/Native macOS window, where before it read as static text. A `Button` with its own accessible name exposes one element, not its visible label as well ([#782](https://github.com/DarkPyonix/compose-rust/issues/782), [#838](https://github.com/DarkPyonix/compose-rust/issues/838)). `has_popup` reaches TalkBack and VoiceOver on Android and iOS as the state description "has popup" ([#810](https://github.com/DarkPyonix/compose-rust/issues/810)).

### A drag region moves a Kotlin/Native Linux or Windows window

`WindowDrag(true)` content moves a Kotlin/Native Linux or Windows window, as it does on macOS. On Windows, `Chrome.FullSize` now also lets the page draw under the title bar ([#345](https://github.com/DarkPyonix/compose-rust/issues/345)).

### Deeply nested rows and columns measure in linear time

Alternating `Column` and `Row` levels with a child that fills the width no longer make the intrinsic measurement grow exponentially with depth. A tree 100 levels deep measures at once, where before it hung ([#846](https://github.com/DarkPyonix/compose-rust/issues/846)).

### Less memory after large batches and registered fonts

The Host's batch encoder gives back its buffers after a batch larger than its retained caps (64 KiB of records and strings, 128 KiB of arena), such as one that registers fonts; ordinary frames still allocate nothing. The renderer keeps one Skia typeface per registered font instead of two ([#862](https://github.com/DarkPyonix/compose-rust/issues/862)).

### A macOS native view costs less per frame

A `NativeView` that only moves or only resizes while scrolling updates just that part of its frame, and a view with nothing drawn above it makes no hole list. The saving is small, below what the frame budget test resolves ([#855](https://github.com/DarkPyonix/compose-rust/issues/855)).

### A bare single-line `TextField` centres its text vertically

A bare single-line `TextField` now centres its text in the height it is given, as a decorated one does; before, a bare field put its text at the top. This changes how bare fields given a height look, for example a 48 dp search field. Multiline fields still start at the top. Padding set with `padding_each` sits inside the field, so a click in it still focuses the field ([#771](https://github.com/DarkPyonix/compose-rust/issues/771)).

### A read-only `TextField`

`TextFieldScope::read_only(true)` keeps a field's text selectable and copyable but not editable, and accessibility reads it as not editable. It travels as the `ReadOnly` property (tag 215, a boolean), so the schema hash changes ([#826](https://github.com/DarkPyonix/compose-rust/issues/826)).

### `padding_each` and `padding_xy`

`Modifier::padding_each(start, top, end, bottom)` sets each side's padding in dp, and `Modifier::padding_xy(horizontal, vertical)` sets the two axes. No schema change ([#826](https://github.com/DarkPyonix/compose-rust/issues/826)).

### A border of width 0 draws nothing

`Modifier::border` with a width of 0 or less now draws no border. Before, Compose read 0 dp as a hairline and drew a 1 pixel line ([#770](https://github.com/DarkPyonix/compose-rust/issues/770)).

### `equal_height` rows with a scrolling child

In a `Row` with `equal_height(true)`, a child holding a scrolling column of fixed height now measures at that height. Before, it measured 0 and the row collapsed ([#766](https://github.com/DarkPyonix/compose-rust/issues/766)).

### The settings icon is a gear

`IconRole::Settings` draws an eight-tooth gear with a hub. Before, at bar size, it read as a crosshair ([#537](https://github.com/DarkPyonix/compose-rust/issues/537)).

### A refresh icon

`IconRole::Refresh` (tag 39) draws a clockwise arrow on desktop and Android and the `arrow.clockwise` system symbol on iOS. The schema hash changes ([#908](https://github.com/DarkPyonix/compose-rust/issues/908)).

### A delete icon

`IconRole::Delete` (tag 40) draws a bin on desktop and Android and the `trash` system symbol on iOS. `samples/minimal` shows its delete action as the white bin on the red square instead of the word "Delete". The schema hash changes ([#593](https://github.com/DarkPyonix/compose-rust/issues/593)).

### Seventeen more icon roles

`IconRole` gains `Lock`, `Favorite`, `Book`, `Play`, `Shuffle`, `ExpandMore`, `Person`, `Clock`, `Tune`, `Chat`, `Star`, `Bag`, `Bell`, `Share`, `Language`, `CompactOverlay` and `Calculator` (tags 22 to 38, the ones already reserved for them). Each has drawn artwork on desktop and Android and a system symbol on iOS, and works in an icon-only button. The schema hash changes ([#539](https://github.com/DarkPyonix/compose-rust/issues/539), [#602](https://github.com/DarkPyonix/compose-rust/issues/602)).

### The design-system parity test says where to fetch from

When `design_system_parity` cannot find the design systems, its failure now names the checkout it looked in and tells you to run `scripts/fetch-design-systems.sh` there ([#495](https://github.com/DarkPyonix/compose-rust/issues/495)).

### `Canvas` draws with any brush

A `Canvas` draw command whose paint is a registered brush (a gradient or an image brush) now draws with that brush: lines, rectangles, rounded rectangles, circles, arcs and text alike. Before, the generated decoder read a brush paint as a plain colour, so the shape came out in a single colour. The wire format is unchanged; the generated Kotlin decoder changes, so a renderer and a crate should both be at this version ([#584](https://github.com/DarkPyonix/compose-rust/issues/584)).

## v0.0.11

### Known issues

- On Windows 10 and Windows Server 2022, a window resized by dragging an edge can show a gap at the moving edge for one to five frames of a drag. In the resize check 15 of 24 drags showed it on Windows Server 2022, and none of 24 on Windows Server 2025, the Windows 11 generation. Nothing is black, torn or stale, and the window is right once the drag stops. v0.0.10 has the same Windows window code, so this is not new in v0.0.11; it was found by a check that ran to its end for the first time. It is the Compose fork's window ([compose-multiplatform-core-extended#245](https://github.com/thisisthepy/compose-multiplatform-core-extended/issues/245)).
- A Windows window does not keep its size in dp when the display scale changes while it is open: it ends a few pixels off (398 by 297 pixels where 400 by 300 was right, going from 200 % to 125 %). Fixed in the fork's window after this release's pin.

### Compose fork moved to 1.11.1-ext.5: the wheel scrolls on every desktop

The renderer now builds with the Compose fork's `1.11.1-ext.5` (tag `v1.11.1-ext.5`) for Compose, the window modules and skiko-awt alike. On the Kotlin/Native macOS window a mouse wheel or a trackpad now scrolls what is under the pointer: before, nothing scrolled there at all, because every scroll reached the scene at the window's top left corner and its distance came out as zero. A wheel line moves 10 points, as in an AppKit view, and a trackpad moves the content as far as the fingers went. On the Kotlin/Native Windows and Linux windows the wheel now scrolls the way it turns (toward the reader brings what is below into view) and as far as a desktop Compose application on the same system: before, it scrolled the wrong way, and on Windows three times too far. The GraalVM windows are unchanged.

### Windows applications no longer overflow their stack at start

On Windows `launch` now runs the renderer on a thread compose-rust creates, named `compose-rust-ui`, with a 64 MB stack, and the calling thread waits for it. Before, the Kotlin/Native renderer composed, measured and drew on the thread that called `launch`, the main thread, whose stack is 1 MB on Windows unless the executable's own link asks for more (macOS and Linux give 8 MB). A screen of ordinary depth overflowed it, and a released application ended with "thread 'main' has overflowed its stack" right after its window opened. The 64 MB is reserved address space; Windows commits only the pages that are used. Every entry point is covered, because they all end in `LaunchBuilder::try_launch_runtime`. The status `launch` returns, its failing exit and what a panic does are unchanged. Code before `launch` still runs on the main thread, while composables and handlers run on the UI thread. On macOS and Linux the calling thread is the UI thread, so a program that does UI-thread work before `launch` runs there and loses that work on Windows; the rule is the same on every platform. Before `launch` a program may create state with `mutable_state_of` and read and write it, make the `LaunchBuilder` choices (`with_theme`, `with_window`, `windows`, `identifier`), register the assets its theme and window name (`asset::asset`, `register_owned`; they keep their ids on the UI thread), post or withdraw notifications and ask for their permission, and ask for a file dialog through `FileDialogSender`. From the root composable or later, because they live on the UI thread: `set_theme` and `set_zoom_level` and their subscriptions; `show_message` and `Message::show`; `storage::read`, `read_string`, `write`, `remove` and `keys`; `clipboard::set_text` and `read_text`; `FileDialog::show`; the handlers `notification::on_activation`, `windows::on_window_request`, `windows::on_application_reopen_handler` and `animation::on_animation_event`; subscriptions to the design system, the density, window and node sizes, reduced motion and the notification permission; and any thread-local of the application's own that a composable reads. `COMPOSE_RUST_UI_STACK_BYTES` sets another size for diagnosis. An application that calls `compose_rust_renderer_run` itself, without `launch`, must provide the stack through its own link (`cargo:rustc-link-arg-bins=/STACK:...` in its build script). macOS and Linux are unchanged: the calling thread is the UI thread.

### Dragging a selection past the editor's edge scrolls again

In a `CodeEditor`, holding the mouse below the bottom edge (or above the top) while dragging a selection scrolls the document again and carries the selection with it. The drawn path, which never scrolled during a drag, now does too: each frame the pointer is held past the edge it scrolls further the further past it is, and the selection follows the line under the pointer, until the button is released or the pointer comes back inside. Keeping the field's own scroll requests away from the vertical scroll, so the editor could follow the caret itself, had stopped it. Those requests now go through only while a mouse button is down over the field, so the keyboard caret is still followed by the editor alone. ([#841](https://github.com/DarkPyonix/compose-rust/issues/841)).


### Window buttons follow the operating system

A window's minimise, maximise and close buttons are now the operating system's, whatever design system the application chose. An application under an Apple design system (Cupertino or Liquid Glass, for example through `Theme::unified`) drew the traffic lights at the left on Windows and Linux; on Windows, under the default `Chrome::Modern`, those lights could not be pressed, because the native window treats the three cells at the right as the buttons. It now gets Windows 11's three 46 by 32 dp buttons at the right on Windows, with Snap Layouts opening from maximise, and the desktop's own buttons at the right on Linux (Adwaita under GNOME, Breeze under KDE, Deepin elsewhere). The same holds for Material 3 and for a Linux design system on Windows, or Fluent on Linux. macOS keeps the system's traffic lights as before. The buttons' colours still come from the application's design system.

An application that lays its own bar out around the buttons should read `WindowSafeArea` rather than assume a side: on Windows `leading` is now 0 and `trailing` is 138 dp under every design system, on a Linux window whose buttons the renderer draws `trailing` is the desktop's set and `leading` is 0, and on macOS `leading` is the traffic lights' width and `trailing` is 0. A bar that left room on the left under an Apple design system on Windows or Linux now leaves room on the right.

### Compose fork moved to 1.11.1-ext.4

The renderer now builds with the Compose fork's `1.11.1-ext.4` (tag `v1.11.1-ext.4`) for Compose, and with the tip of the fork's `extended` past that tag for the window modules and skiko-awt, all published as `1.11.1-ext.4`. The Compose itself is unchanged from `ext.3` apart from its version. The design systems and the window modules are Java 11 class files (graalvm-linux Java 17), so a JDK 17 or 21 application can load them, and skiko-awt declares its own capability only. The windows gain F1 to F12 and the Menu key everywhere, a single text menu on a right click in a macOS field, an X11 window keeping its size in dp when the scale changes, and on Windows a new size drawn before it is shown after a maximise, restore or snap. Beyond the tag, a macOS native view moves in the same frame as the content under it, and an application can declare a node's accessible role to the Kotlin/Native macOS window. While a native view is shown, a macOS window draws its background material as a window that is not key does, with no live blur of what is behind it, so the view and the frame of the same turn are composited together; this is provisional. A view's frame and visible region can be set in one call, and placing views costs work only where a view changed. A Windows window can leave its caption strip to the application and be moved from the scene.

### A moved caret keeps its whole line in view

Moving the caret in a `CodeEditor` with the keyboard now scrolls exactly enough to show the whole caret line. Past the bottom, the line's bottom edge lands on the view's bottom edge; before, the field path stopped about a line short and left the caret below the view, and the drawn path did not follow the caret at all. Past the top, the line's top lands on the view's top. A move inside the view does not scroll. Content padding is counted, and typing a new line at the end scrolls to it too. A Host that revealed the caret itself after each move to work around this no longer needs to ([#841](https://github.com/DarkPyonix/compose-rust/issues/841)).

### Container roles keep their children

A node whose `Semantics` role is a container (`Menu`, `List`, `Group`, `TabList`, `Tree` or `Navigation`) is now a traversal group, the way a popover menu already was, so its rows stay separate accessible nodes instead of reading as one static text. `Menu`, `List`, `TabList` and `Tree` also carry Compose's `CollectionInfo` with one row per child, which TalkBack reads as "list, N items". The platform role is unchanged: the AppKit, Windows and Linux windows still report `AXMenu`, `AXList`, `AXGroup` and their UIA and AT-SPI counterparts. No schema value is added and the schema hash does not move ([#838](https://github.com/DarkPyonix/compose-rust/issues/838)).

### A named `Button` is read once

A `Button` given its own `accessible_name` no longer exposes its visible label as a separate text node. The button keeps its name, its role and its press action, and VoiceOver, Narrator and TalkBack read it once by that name instead of reading the name and then the label. A button without a name of its own is read by its label as before ([#838](https://github.com/DarkPyonix/compose-rust/issues/838)).

### Code editor padding, fixed gutter columns and a current-line gutter colour

A `CodeEditor` can now keep room above its first line and below its last that scrolls with the document: the `ContentPaddingDp` property (tag 210) carries eight bytes, `top_dp` then `bottom_dp`, each an `f32`, written by `ContentPadding::to_bytes`. At scroll 0 the first line starts `top` dp down and scrolling moves it up with the text, and the last line can scroll `bottom` dp clear of the view's end. The gutter and its divider still fill the view. Clicks, the caret, the visible range, `RevealRange`, `SetScrollOffset` and folding all count the padding. A side below zero or not a number is a `ProtocolError` and the editor is drawn without padding. `FixedGutterColumns` (tag 211, a bool) keeps the fold column and the 16dp change-bar column even when the file folds nothing and changes nothing, so the text starts at the same x in every file. `CodeEditorColors` takes `current_line_gutter` in its last two bytes, offset 10, which were reserved: the gutter on the caret's row is painted that role, under the line number and the marks. None of them set draws as before. Both drawing paths and iOS draw them the same. The schema hash moves, so a Renderer from before this is refused at the handshake ([#816](https://github.com/DarkPyonix/compose-rust/issues/816)).

### Run font features are measured as they are drawn

A `MeasureText` request now carries the text's run font feature table, the same bytes the `SpanFontFeatures` property (tag 209) sends, and the Renderer measures each run with the features it is drawn with. Before, only the text's own `FontFeatureSettings` reached the measurement, so a run with `tnum 1` was measured with proportional figures and drawn with tabular ones, and a Host laying out a page placed it at the wrong width. The request record grows from 80 to 88 bytes: `span_features_offset@80 u32` and `span_features_length@84 u32`, the offset and byte length of the table as it is, its entry count first and its offsets counted from its own start. A length of zero is no table. A table the Renderer cannot read answers that request as malformed. A Host that writes measure records by hand writes 88-byte records, and the schema hash moves, so a Renderer from before this is refused at the handshake ([#823](https://github.com/DarkPyonix/compose-rust/issues/823)).

### Menu keyboard navigation inside a `Popover` and `has_popup` semantics

A `Popover` whose content is a menu (declaring `Role::Menu` on the popover or its content container, or holding `Role::MenuItem` rows) now runs the same keyboard loop as `Menu`: opening focuses the item marked `selected(true)` (or the first focusable item when none is selected), `ArrowDown` and `ArrowUp` move between focusable items and wrap at the ends while skipping non-focusable headers and disabled rows, `Home` and `End` jump to the first and last items, `Enter` and `Space` activate the focused item once, and `Escape` dispatches `OnDismiss` once and restores focus to the anchor without leaking navigation keys to the Host. `Modifier::has_popup(true)` and `SemanticsState::has_popup(true)` add bit 13 (`1 << 13`) to the `Semantics` modifier state word (included in `SCHEMA_HASH`), exposed as `AccessibleState.EXPANDABLE` on AppKit and `"has popup"` in `stateDescription` where the platform has no native popup-trigger attribute ([#810](https://github.com/DarkPyonix/compose-rust/issues/810)).

### Releasing bytes registered at run time

`asset::release_owned(asset_id, kind)` pairs with `asset::register_owned`: it takes the bytes out of the registry and sends the Renderer one `ReleaseAsset` record on the next batch, the same record `release_brush` sends. Registering the same bytes again afterwards gives a new id and a new `RegisterAsset`. An id that is not registered as that kind, or was already released, returns false and sends nothing. A screen that builds pictures at run time, such as an SVG drawn once per colour, can now keep the Renderer's cache from growing for the life of the app ([DarkPyonix/dioxus-compose#273](https://github.com/DarkPyonix/dioxus-compose/issues/273)).

### A plain `Text` sends no empty font records

A `Text` that names no run fonts, no font list, no `word_break` and no `overflow_wrap` no longer sends a `SetProp` of nothing for each of them on its first composition: `SpanFonts` (tag 116), `Font` (110), `WordBreak` (111) and `OverflowWrap` (112) are now listed only when set, as `SpanFontFeatures` already was. A `TextField` leaves out `Font`, `WordBreak` and `OverflowWrap` the same way. Such a text sends four records fewer, and its records are those an `rsx!` text sends. One that was set and is taken away is still cleared by exactly one record. The Renderer removes a property on a record of nothing, the same state as one never sent, so nothing it draws changes, and the schema hash does not move ([#824](https://github.com/DarkPyonix/compose-rust/issues/824)).

### Per-span font features

OpenType font features can now be set on single runs inside a `Text`: `TextSpans::new_with_features` gives each run its own list, and `spans::encode_feature_table` with `TextSpans::with_feature_table` attaches an already built table. The table travels beside the runs as the new `SpanFontFeatures` property (tag 209), and a `Text` none of whose runs names a feature sends no such record. A run that names features is set with the text's own `FontFeatureSettings` first and its own after them, so a tag both name takes the run's value, and a run that names none is drawn with the text's. Runs take features with or without a type role. A table the Renderer cannot read, or one that names a run the text does not have, is one `ProtocolError` and the text is drawn without span features ([#702](https://github.com/DarkPyonix/compose-rust/issues/702)).

### A Button's content padding

`Button::content_padding(horizontal, vertical)` sets the room between a button's edge and its label, in dp, for that button only. It replaces the design system's label padding and, like it, gives way when the button is too small for the label and the padding together. A button that never calls it sends nothing and is drawn as before. `Modifier::padding` is still the room around the button. No tag is added and the schema hash does not move: the value goes out as the existing `PaddingEach` modifier, in a slot of its own, and the renderer now reads `PaddingEach` on a `Button` as the room inside it. A Host that writes records itself and sent `PaddingEach` to a `Button` for room around it sees that room move inside; `Padding` and `PaddingRole` are unchanged ([#789](https://github.com/DarkPyonix/compose-rust/issues/789)).

### Crossing a window size class no longer composes the whole tree

A window that crossed 600 dp or 840 dp wide, or 480 dp tall, composed every node again in that one frame, because the class was part of the resolved theme and every node reads the theme. The theme is now kept until one of its real inputs changes (the design system, colour scheme, fonts, brushes, material backdrop, high contrast, overlay tokens), and the few things that answer by class (a navigation's bar, rail or drawer; Liquid Glass cards and the dark desktop page) ask for it where they draw. A page of 81 nodes composes none of them again when it crosses a class, where it composed all 81 before. Nothing on the wire changes ([#757](https://github.com/DarkPyonix/compose-rust/issues/757)).

### `on_motion_finished` on every `ui` builder

Every widget builder in the `ui` API that takes `.modifier(...)` now has `on_motion_finished`, with the signature and the behaviour the containers and `TextField` already had: `Text`, `Button`, `MenuItem`, `Checkbox`, `RadioButton`, `Switch`, `Slider`, `Chip`, `FloatingAction`, `NavigationItem`, `ProgressIndicator`, `LinearProgressIndicator`, `Divider`, `Image`, `Icon`, `Canvas`, `Badge`, `DatePicker`, `TimePicker`, `Dropdown`, `Dialog`, `Sheet`, `Menu`, `Popover`, `Tooltip`, `FlowRow`, `Tabs`, `TopAppBar`, `Scaffold`, `Navigation`, `FileDropTarget`, `SplitPane`, `LazyColumn`, `LazyRow` and `LazyGrid`. A builder given no handler writes nothing new, so its wire is unchanged. `Window` has no Modifier and so no size motion to report. Nothing changes in the schema or the Renderer, which already took the handler on any node ([#707](https://github.com/DarkPyonix/compose-rust/issues/707)).

### Symbols the web's default family lacks draw as glyphs

On the web, a character Roboto has no glyph for was an empty box, because a canvas in a browser has none of the machine's fonts to fall back on: the backspace sign `U+232B`, the small down triangle `U+25BE` and the play triangle `U+25B6` drew as glyphs on every target but that one. The web bundle now carries a fallback face beside the Roboto faces, a cut of Noto Sans Symbols 2 (SIL Open Font License 1.1, `renderer/web/resources/fonts/OFL.txt`) with all of Geometric Shapes and the keyboard and media signs of Arrows and Miscellaneous Technical. Text in the default family (a role's face, `system-ui`, `sans-serif`, `ui-sans-serif`, and text that names no font), a font list of registered faces, a registered stack and a role set in a registered face all draw a letter none of their families has from it. Other targets are unchanged: they have no fallback faces of their own and leave such a letter to the machine's fonts ([#525](https://github.com/DarkPyonix/compose-rust/issues/525)).

### A pill at any height is `ShapeRole::Full`

`Modifier.shape_role(ShapeRole::Full)` gives a node fully round ends whatever its height: a node 40 dp tall gets 20 dp corners, the same node at 80 dp gets 40 dp, and the application never works out half the height. The background, border, clip, shadow and press feedback follow the same outline, in every design system, including the two whose other corners are continuous. The role was already in the schema (`Full`, tag 6) and already drawn this way; what is new is that tests hold it there, on the wire and in pixels, and the documentation says it. Nothing on the wire changes, so the schema hash does not move ([#310](https://github.com/DarkPyonix/compose-rust/issues/310)).

### The window's density reaches the Host

The Renderer tells the Host how many device pixels one dp of a window covers with a new event, `DensityChanged` (event tag 53, one `f32`, a 20 byte record): once before the first frame, and again when the value changes, as when a window moves to a screen of another scale. `compose_rust::density()` reads it and is 1.0 until the first report. `compose_rust::density::subscribe` calls back on a change and not on a repeat of the same value, which is where a cache of measurements made at the old density is emptied. In a multi-window application the report names its `Window` node, and `WindowHandle::density()`, `density::density_of` and `density::subscribe_window` read and follow one window. The zoom is not part of the value and keeps its own report. A scale of zero, below zero or NaN is a protocol error. The new event moves the schema hash and adds a record to the `events.bin` protocol vector, so a Host and a renderer must come from the same release ([DarkPyonix/dioxus-compose#240](https://github.com/DarkPyonix/dioxus-compose/issues/240)).

### One right-click text menu on Windows, the system's

A right click in a focused text field on a Windows window now brings up one menu, the system's popup, with Cut, Copy, Paste and Select All greyed where they cannot run. The Kotlin/Native renderer turns on the window's own edit menu (`dxc_native_set_edit_menu`, from the fork's `1.11.1-ext.4`) while a field has focus, and keeps the menu Compose draws for a field shut while it is on. The Menu key and Shift and F10 bring it up as well. The GraalVM renderer shows Compose's menu through the system's popup, as the macOS window does. An application that draws a menu of its own, or consumes the right click for one, still gets only its own. Linux keeps the menu Compose draws, because the X11 window has no system menu to show ([#142](https://github.com/DarkPyonix/compose-rust/issues/142)).

### A sticky child in a scrolling row or column

`Modifier::sticky()` holds a child of a `ScrollRow` or `ScrollColumn`, at any depth, at the container's start edge while the rest scrolls under it: a diff line's check or line number stays on the screen when the line is scrolled sideways. Its background, border and clip stay with it. `LazyRow` and `LazyColumn` are not covered. A new modifier (tag 41) moves the schema hash ([#424](https://github.com/DarkPyonix/compose-rust/issues/424)).

### A window's size reaches the Host from its first layout

The window's size is reported to the Host from the layout pass that measured it, as an observed node's already was, rather than from the recomposition that pass schedules. On macOS a window draws only when something asks for a frame, so the Host could stay at 0x0, the compact class, for a long time after the first frame of a 1440x900 window, and lay its page out for a phone ([#773](https://github.com/DarkPyonix/compose-rust/issues/773)).

### A single-line field's placeholder stays on one line

A `TextField` that is not multiline now lays its placeholder out on one line, ending in an ellipsis where it is wider than the field. Before, a long placeholder wrapped onto further lines that a one-line field clipped away. A multiline field's placeholder wraps as before. This changes how existing single-line fields draw a long placeholder ([#786](https://github.com/DarkPyonix/compose-rust/issues/786)).

### The transient message's colours, line, padding and type

`OverlayTokens` gains the message's fill and text colour for the light and the dark scheme (`toast_container(light, dark)`, `toast_content(light, dark)`), the width of its line (`toast_border_width`, where 0 draws none), its padding (`toast_padding(horizontal, vertical)`) and its text size and line height (`toast_type(size, line_height)`). Each left unset keeps the design system's, so a message with none of them is drawn as before. The renderer takes the colour for the scheme it draws in, so a message can stay inverted against the page in both. The `SetOverlayTokens` record grows from 28 to 56 bytes and the schema hash moves, so a Host and a renderer must come from the same release ([#781](https://github.com/DarkPyonix/compose-rust/issues/781)).

### Centered Sheet presentation with scrim and focus trap

A `Sheet` can now be presented in the center of the window rather than as an edge drawer: `.presentation(SheetPresentation::Centered)` sets the new `Presentation` property (tag 206) to `Centered` (1). In expanded and medium windows, the sheet appears centered in the window with 24dp rounded corners, a scrim with alpha about 0.4 behind it in both light and dark themes, and no drag handle or drag-to-dismiss gesture. The sheet's width defaults to 560dp and can be configured with `.max_width(dp)` on the new `MaxWidth` property (tag 207), clamped to available window width minus margins. Its height follows content height up to available window height minus margins, scrolling internally when content overflows. Keyboard focus is trapped inside the centered sheet while open, cycling between its focusable controls on Tab and Shift+Tab, and Esc or clicking the scrim dispatches `OnDismiss` once. On compact windows, a centered sheet falls back to bottom sheet presentation with grab handle and drag-to-dismiss ([#344](https://github.com/DarkPyonix/compose-rust/issues/344)).

### A place in the window for a platform view the application made

`ui::NativeView(handle)` is a node over which the window places a platform view the application made itself, such as a web view, a video surface or a map: an `NSView` on macOS. Make the view against the handle the window reports in `compose_rust::native_view::native_parent(window)` (the `NativeViewParent` event, sent once a `NativeView` is in a window, again when its backing scale changes, and with a zero handle when it closes), then hand it over. `Batch::attach_native_view` and `Batch::focus_native_view` are the same two commands for a Host that writes its own batches.

The Renderer moves the view with the layout and with every scroll, in the same frame as the content around it, cuts it to what its ancestors leave showing, and cuts out every popover, menu, dropdown list, dialog, sheet, tooltip and message drawn above it as a rounded hole with its shadow, so they show on top. `visible(false)` hides the view and keeps it, with its state. Keyboard focus moves into the view on a click or `focus_native_view(node, true)`, back to the scene on `false`, a Compose focus request, hiding or removing it, and `on_focus_change` hears each move. The view stays the application's: the Renderer never destroys it.

macOS Kotlin/Native places views now. The GraalVM macOS renderer and the other platforms refuse an attach as `NATIVE_VIEW_UNSUPPORTED` (23); a node that is not a `NativeView` is `NOT_NATIVE_VIEW` (21), and a handle the window will not take, or one already attached elsewhere, is `NATIVE_VIEW_REJECTED` (22) ([#680](https://github.com/DarkPyonix/compose-rust/issues/680)).

### Today's date and the local time

`compose_rust::time::today()` answers today's date in the user's time zone as a `LocalDate { year, month, day, weekday }`, `now_local()` the date and the time of day with the offset from UTC, and `local_at(instant)` the same for any `SystemTime`. `subscribe_date_change(notify)` calls `notify` once each time the date changes, at local midnight or when the user moves to a time zone where it is another day. The Host reads them itself, with no boundary call: the C library's local time on desktop, iOS and Android, where a `TZ` in the environment wins as it does for every program there, and the page's `Date` in a browser, whose Host instantiation answers three `compose_rust_time` imports ([#630](https://github.com/DarkPyonix/compose-rust/issues/630)).

### Text after the end of a line in the code editor

A decoration kind is appended: `Decoration::after_line(version, line, text, role)` (kind 7) draws muted text after the last character of a line, in the role given, the way an editor shows who last changed a line. It is not text: it cannot be selected or copied, the caret and End stop in front of it, a pointer over it is over no character, and it follows its line through the reader's edits. A copy from the code editor is now always the document's own text, on both paths, and never what the field shows of lenses, suggestions or view zones ([#528](https://github.com/DarkPyonix/compose-rust/issues/528)).

### Layout modifiers: `size_in`, `width_in`, `height_in`, and `aspect_ratio`

`Modifier` now supports bounded size constraints and aspect ratios: `Modifier.size_in(min_w, max_w, min_h, max_h)`, `Modifier.width_in(min, max)`, `Modifier.height_in(min, max)`, `Modifier.max_width(max)`, `Modifier.max_height(max)`, `Modifier.min_width(min)`, `Modifier.min_height(min)`, and `Modifier.aspect_ratio(ratio)` (with `aspect_ratio_match_height(ratio, match_height)`). Unconstrained bounds are passed as `f32::NAN` and map to `Dp.Unspecified` in Compose's `sizeIn` modifier.

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
