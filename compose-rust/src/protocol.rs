//! Fixed-layout little-endian boundary protocol.

use crate::schema::{
    AssetKind, ColorScheme, DesignSystem, FileDelivery, FileDialogKind, FileDialogStatus, FileOp,
    IoStatus, Key, MAX_MODIFIER_WORDS, MaterialRole, MessageDuration, Modifier, MotionRole,
    NotificationImportance, NotificationPermission, NotificationPresentation, Paint, PropertyKind,
    SEMANTICS_STATE_MASK, Selection, SemanticsRole, ShapeRole, SpaceRole, TYPE_ROLE_COUNT, Theme,
    WidgetKind, modifier_extra_words,
};
use core::fmt;

const TAG_ENVELOPE: u16 = 0;
const TAG_CREATE: u16 = 1;
const TAG_SET_PROP: u16 = 2;
const TAG_SET_MODIFIER: u16 = 3;
const TAG_INSERT: u16 = 4;
const TAG_MOVE: u16 = 5;
const TAG_REMOVE: u16 = 6;
const TAG_SET_TEXT: u16 = 7;
const TAG_APPEND_TEXT: u16 = 8;
const TAG_SET_THEME: u16 = 9;
const TAG_REGISTER_ASSET: u16 = 10;
const TAG_RELEASE_ASSET: u16 = 11;
const TAG_SHOW_MESSAGE: u16 = 12;
const TAG_SET_WINDOW: u16 = 13;
const TAG_POST_NOTIFICATION: u16 = 14;
const TAG_WITHDRAW_NOTIFICATION: u16 = 15;
const TAG_REQUEST_NOTIFICATION_PERMISSION: u16 = 16;
const TAG_EDIT_CODE: u16 = 17;
const TAG_START_ANIMATION: u16 = 18;
const TAG_CONTROL_ANIMATION: u16 = 19;
// 20 to 22 are the code editor's: the selection, a range to bring into view, a fold.
const TAG_SET_SELECTION: u16 = 20;
const TAG_REVEAL_RANGE: u16 = 21;
const TAG_SET_FOLDED: u16 = 22;
// 23 and 24 are the windows': a theme for one window, and bringing one forward.
const TAG_SET_WINDOW_THEME: u16 = 23;
const TAG_FOCUS_WINDOW: u16 = 24;
const TAG_REQUEST_FOCUS: u16 = 25;
// 26 asks for a platform file dialog. Its answer is the event numbered 50.
const TAG_SHOW_FILE_DIALOG: u16 = 26;
/// `ShowFileDialog` after its header: the request, the window, the kind and the default
/// filter, then four string references. 44 bytes, so the record is 48.
const SHOW_FILE_DIALOG_PAYLOAD_LEN: usize = 4 + 4 + 2 + 2 + 4 * 8;
// 27 sets application-level overlay theme tokens for Dialog, Menu, Dropdown and Sheet.
const TAG_SET_OVERLAY_TOKENS: u16 = 27;
// 28 and 29 are numbered for commands that are not here yet.
const TAG_SET_SCROLL_OFFSET: u16 = 30;
/// 9 u16 fields (18 bytes) + 1 u16 reserved (2 bytes) + 1 u32 scrim (4 bytes) = 24 bytes.
const OVERLAY_TOKENS_PAYLOAD_LEN: usize = 24;
// 29 puts plain text on the platform clipboard. Nothing answers it. 28 is held for the
// code editor's grouped edit.
const TAG_SET_CLIPBOARD_TEXT: u16 = 29;
// 30 is held for the code editor's scroll offset. 31 asks for the clipboard's plain text,
// answered by one `ClipboardText` event carrying the same request number.
const TAG_READ_CLIPBOARD: u16 = 31;
// 32 asks the Renderer to carry out one storage operation, answered by one `IoAnswer`
// event carrying the same request number.
const TAG_STORAGE_CALL: u16 = 32;
/// `StorageCall` after its header: the request, the operation, a reserved half word, then
/// the store, the key and the bytes as three references. 32 bytes, so the record is 36.
const STORAGE_CALL_PAYLOAD_LEN: usize = 4 + 2 + 2 + 3 * 8;
// 33 asks something of a file the Renderer holds a handle for, answered by one `IoAnswer`
// or `FileHandles` event carrying the same request number. 34 lets go of such a handle and
// is not answered.
const TAG_FILE_CALL: u16 = 33;
/// `FileCall` after its header: the request, the operation, the create flag and the handle,
/// then the name's and the bytes' references. 28 bytes, so the record is 32.
const FILE_CALL_PAYLOAD_LEN: usize = 4 + 2 + 2 + 4 + 8 + 8;
const TAG_RELEASE_FILE_HANDLE: u16 = 34;
// 35 asks a scroll
// container to go to an offset and 36 to bring a rectangle into view. Both are requests:
// the Renderer owns the position and reports what it did in `ScrollChanged`.
const TAG_SCROLL_TO: u16 = 35;
const TAG_SCROLL_INTO_VIEW: u16 = 36;
/// `ScrollTo` in full: the 4 byte head, the node, the item, the flags and two `f64`.
const SCROLL_TO_LEN: usize = 32;
/// `ScrollIntoView` in full: the 4 byte head, the node, the target, four `u16` and four `f64`.
const SCROLL_INTO_VIEW_LEN: usize = 52;
// 37 puts a platform view the Host made into a `NativeView` node's place, or takes it out
// with handle 0. 38 moves keyboard focus into that view or back to the scene. Neither is
// answered; focus moving is reported in `NativeViewFocus`.
const TAG_ATTACH_NATIVE_VIEW: u16 = 37;
const TAG_FOCUS_NATIVE_VIEW: u16 = 38;
/// `AttachNativeView` in full: the 4 byte head, the node, a reserved word, the handle as a
/// `u64`, and a trailing reserved word.
const ATTACH_NATIVE_VIEW_LEN: usize = 24;
/// `FocusNativeView` in full: the 4 byte head, the node, the focus word, and a trailing
/// reserved word.
const FOCUS_NATIVE_VIEW_LEN: usize = 16;
/// The head of a `StartAnimation` record, before its keyframes.
const ANIMATION_HEADER_LEN: usize = 52;
/// A keyframe before its value: offset, timing, step position, flags and four numbers.
const KEYFRAME_HEAD_LEN: usize = 24;
/// One transform function: its kind and six numbers.
const TRANSFORM_FUNCTION_LEN: usize = 28;
const KEYFRAME_FROM_PRESENTED: u16 = 1;
const ENVELOPE_LEN: usize = 12;
/// `SetTheme` after its header: four `u16` fields, one font slot per type role, and the
/// palette's `(offset, len)` reference. 52 bytes, so the record is 56.
pub const THEME_PAYLOAD_LEN: usize = 8 + 4 * TYPE_ROLE_COUNT + 8;
/// The shortest mutation record on the wire (`RequestNotificationPermission`: the 4-byte
/// header and nothing else).
const MIN_RECORD_LEN: usize = 4;

const VALUE_NONE: u16 = 0;
const VALUE_STRING: u16 = 1;
const VALUE_BOOL: u16 = 2;
const VALUE_I64: u16 = 3;
const VALUE_F32: u16 = 4;
/// An opaque byte run in the arena. Same `(offset, length)` layout as a string, with no
/// UTF-8 check, because a Canvas command list is not text.
const VALUE_BYTES: u16 = 5;

#[derive(Clone, Debug, PartialEq)]
pub enum PropertyValue<'a> {
    None,
    String(&'a str),
    Bool(bool),
    Integer(i64),
    Float(f32),
    Bytes(&'a [u8]),
}

#[derive(Clone, Debug, PartialEq)]
pub enum Mutation<'a> {
    Create {
        node_id: u32,
        widget: WidgetKind,
    },
    SetProp {
        node_id: u32,
        property: PropertyKind,
        value: PropertyValue<'a>,
    },
    SetModifier {
        node_id: u32,
        index: u16,
        modifier: Modifier,
    },
    Insert {
        parent_id: u32,
        node_id: u32,
        index: u32,
    },
    Move {
        parent_id: u32,
        node_id: u32,
        index: u32,
    },
    Remove {
        node_id: u32,
    },
    SetText {
        node_id: u32,
        text: &'a str,
        selection: Option<Selection>,
    },
    /// Appends the streamed tail to a Text node instead of resending the whole string.
    AppendText {
        node_id: u32,
        text: &'a str,
    },
    /// The root theme. Sent once as the first record of the initial batch.
    SetTheme(Theme),
    /// What the application asked of its own window.
    ///
    /// Written once per rebuild beside the theme, and read by the Renderer before it
    /// stands the window up: `compose_rust_host_init` answers with the first batch, so
    /// the setting is in hand before there is a window to apply it to. Platforms where
    /// the window is not ours ignore it, which is not an error but an absence of anywhere
    /// to put it.
    SetWindow(crate::schema::Window),
    /// Hands the Renderer the bytes of one asset. The Renderer copies them into its own
    /// cache inside this call, because the batch buffer is only valid for the call that
    /// carries it and an image has to outlive the frame that draws it.
    RegisterAsset {
        asset_id: u32,
        kind: AssetKind,
        bytes: &'a [u8],
    },
    /// Drops an asset from the Renderer's cache. The Host owns the lifetime.
    ReleaseAsset {
        asset_id: u32,
    },
    /// Says a sentence to the user once. It is not a node, because the Host would then
    /// have to hold "showing until four seconds from now" and run a render to take it
    /// away again. How long it stays, where it sits and what happens when a second one
    /// arrives while the first is up are all the Renderer's.
    ///
    /// `handler_id` is what the action label reports when it is pressed, or 0 when the
    /// message has no action, in which case `action` is empty too.
    ShowMessage {
        handler_id: u64,
        text: &'a str,
        action: &'a str,
        duration: MessageDuration,
    },
    /// Asks the platform to show one notification outside the window.
    ///
    /// Not a node, for the same reason a message is not: it outlives the component that
    /// posted it, and when it goes away depends on what the user does in the notification
    /// centre, which the Host never hears about. Posting again under the same `key`
    /// replaces it. An empty `key` asks the Renderer to name it, and such a notification
    /// can be neither replaced nor withdrawn. An empty action label means no button.
    PostNotification {
        key: &'a str,
        title: &'a str,
        body: &'a str,
        channel: &'a str,
        action_1: &'a str,
        action_2: &'a str,
        importance: NotificationImportance,
        presentation: NotificationPresentation,
    },
    /// Takes back the notification posted under `key`, if it is still showing.
    WithdrawNotification {
        key: &'a str,
    },
    /// Asks the platform for permission to show notifications. It carries nothing: the
    /// answer comes back as a permission event.
    RequestNotificationPermission,
    /// Replaces a range of a code editor's document, as it stood at `base_version`, with
    /// `text`.
    ///
    /// The Renderer owns the document, so this is a request and not an assignment: where
    /// the reader has typed since that version the range is moved along with what they
    /// typed, and where they typed in the same place the edit is refused and
    /// `CodeEditRejected` names `request_id`. An edit that is applied comes back as an
    /// ordinary `CodeChanged`, so the version stays one sequence whoever made the edit.
    EditCode {
        node_id: u32,
        request_id: u32,
        base_version: u32,
        start_line: u32,
        start_column: u32,
        end_line: u32,
        end_column: u32,
        text: &'a str,
    },
    /// Hands the Renderer one animation to play on its own frame clock until it ends.
    /// Nothing crosses the boundary while it plays unless it asked for events.
    StartAnimation(crate::schema::Animation<'a>),
    /// Pauses, resumes or cancels one animation the Renderer is playing. A key the Renderer
    /// does not hold, or an id that is not the one it holds, is left alone: an animation
    /// that ended on its own is not an error to stop.
    ControlAnimation {
        node_id: u32,
        animation_id: u32,
        property: crate::schema::AnimatedProperty,
        slot: u8,
        op: crate::schema::AnimationControl,
        /// On the Renderer's frame clock. Zero means the first frame that draws it.
        at_time_nanos: u64,
    },
    /// Puts a code editor's selection between two places in the document as it stood at
    /// `base_version`. Both ends are moved along with whatever was typed since, and an end
    /// that is outside the document, or between the two halves of a pair, drops the
    /// command and comes back as a protocol error.
    SetSelection {
        node_id: u32,
        base_version: u32,
        anchor_line: u32,
        anchor_column: u32,
        active_line: u32,
        active_column: u32,
    },
    /// Scrolls a code editor until a range of the document, as it stood at `base_version`,
    /// is on screen. A range inside a folded stretch unfolds it first.
    RevealRange {
        node_id: u32,
        base_version: u32,
        start_line: u32,
        start_column: u32,
        end_line: u32,
        end_column: u32,
        align: crate::schema::RevealAlign,
    },
    /// Folds or unfolds the stretch of a code editor that starts on `start_line`.
    SetFolded {
        node_id: u32,
        start_line: u32,
        collapsed: bool,
    },
    /// A theme for one `Window` node, over the application's. The payload is
    /// `SetTheme`'s, after the node id. A window without one follows the application's.
    SetWindowTheme {
        node_id: u32,
        theme: Theme,
    },
    /// Brings a window forward and makes it the key window. A command and not a
    /// property: it happens once.
    FocusWindow {
        node_id: u32,
    },
    /// Moves input focus to `node_id`, or clears focus when `node_id` is 0.
    RequestFocus {
        node_id: u32,
    },
    /// Asks the platform to show a file dialog on a window and answers later, once, with a
    /// `FileDialogResult` event that carries `request`.
    ///
    /// `window` is a `Window` node id, or 0 for the implicit root window. `filters` is one
    /// string: a label and its comma-joined extensions as a pair, pairs joined by NUL
    /// (`"Images\0png,jpg\0Docs\0pdf,txt"`). `default_filter` is the position of the
    /// filter selected first.
    ShowFileDialog {
        request: u32,
        window: u32,
        kind: FileDialogKind,
        default_filter: u16,
        title: &'a str,
        directory: &'a str,
        name: &'a str,
        filters: &'a str,
    },
    /// Application-level overlay theme tokens for Dialog, Menu, Dropdown and Sheet.
    SetOverlayTokens(crate::schema::OverlayTokens),
    /// Puts `text` on the platform clipboard as plain text.
    ///
    /// Not a node and not answered: a Renderer with no clipboard drops it.
    SetClipboardText {
        text: &'a str,
    },
    /// Asks for the plain text on the platform clipboard. Answered exactly once, later, by a
    /// `ClipboardText` event that carries `request`.
    ReadClipboard {
        request: u32,
    },
    /// Scrolls a code editor so that `offset_px` physical pixels of its document are above
    /// the top of its view. The Renderer keeps the offset inside what the document allows,
    /// and reports where it ended up through `CodeScrolled` like any other scroll.
    SetScrollOffset {
        node_id: u32,
        offset_px: f64,
    },
    /// Asks the Renderer to carry out one operation on the application's storage, where the
    /// Renderer is the side that can reach it (a browser's IndexedDB). Answered exactly
    /// once, later, by an `IoAnswer` event that carries `request`.
    ///
    /// `store` is the application identifier, `key` the value's name (empty for `Keys`),
    /// and `data` the value to write (empty for every operation but `Write`).
    StorageCall {
        request: u32,
        op: crate::schema::StorageOp,
        store: &'a str,
        key: &'a str,
        data: &'a [u8],
    },
    /// Asks something of a file the Renderer holds a handle for: reading it, writing
    /// `data` to it, listing a folder, or finding (and with `create`, making) the child
    /// called `name`. Answered exactly once, later: a read or a write by an `IoAnswer`, a
    /// listing or a child by a `FileHandles`, and any of them that failed by an `IoAnswer`
    /// with the reason. `name` is empty but for `Child`, `data` but for `Write`.
    FileCall {
        request: u32,
        op: FileOp,
        create: bool,
        handle: u32,
        name: &'a str,
        data: &'a [u8],
    },
    /// The Host holds nothing that names `handle` any more, so the Renderer may let go of
    /// the file behind it. Not answered. A handle the Renderer does not hold is ignored.
    ReleaseFileHandle {
        handle: u32,
    },
    /// Asks the scroll container `node_id` to go to an offset in physical pixels. `flags`
    /// is the wire word (`schema::scroll_to_flags`): animate, apply `x_px`, apply `y_px`.
    /// An axis whose bit is off is not touched. `item` is the lazy list item the offset is
    /// measured from, and 0 elsewhere.
    ScrollTo {
        node_id: u32,
        item: u32,
        flags: u32,
        x_px: f64,
        y_px: f64,
    },
    /// Asks the scroll container `node_id` to bring a rectangle into view. The fields are
    /// the wire's: `target_kind` from `schema::scroll_target_kind`, the two alignments as
    /// `schema::ScrollAlign` values, and `flags` from `schema::scroll_into_view_flags`.
    ScrollIntoView {
        node_id: u32,
        target: u32,
        target_kind: u16,
        align_x: u16,
        align_y: u16,
        flags: u16,
        rect: crate::schema::Rect,
    },
    /// Puts the platform view `handle` in the place of the `NativeView` node `node_id`,
    /// replacing any view it had. `NativeHandle(0)` takes the node's view out. The view is
    /// still the Host's: the Renderer never destroys it.
    AttachNativeView {
        node_id: u32,
        handle: crate::schema::NativeHandle,
    },
    /// Gives keyboard focus to the platform view of the `NativeView` node `node_id`
    /// (`focus` true), or hands it back to the scene.
    FocusNativeView {
        node_id: u32,
        focus: bool,
    },
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ProtocolError {
    Truncated,
    InvalidEnvelope,
    InvalidRecordLength,
    InvalidTag(u16),
    InvalidWidget(u16),
    InvalidProperty(u16),
    InvalidValueKind(u16),
    InvalidModifier(u16),
    InvalidTheme(u16),
    InvalidAssetKind(u16),
    InvalidMessageDuration(u16),
    /// A notification importance or presentation this decoder does not know.
    InvalidNotification(u16),
    InvalidStringRange,
    InvalidUtf8,
    LengthOverflow,
    /// The Renderer refused a whole measure call with this status: its buffer could not be
    /// read, it came from the wrong thread, or there was nothing to measure with yet.
    MeasureRefused(i32),
    /// An animation record that is well formed bytes but not an animation: an unknown
    /// value, fewer than two keyframes, offsets that do not run from 0 to 1, a negative or
    /// non-numeric duration or iteration count, keyframes whose values or transform lists
    /// do not match, or a length that does not hold its keyframes.
    InvalidAnimation,
    /// A `Window` node that is not a child of the root, or a node moved from one window
    /// into another. Compose keeps one composition per window and has no
    /// way to hand a node from one to the next.
    MisplacedWindow,
}

#[derive(Clone, Debug, PartialEq)]
pub struct HostEvent<'a> {
    pub node_id: u32,
    pub handler_id: u64,
    pub payload: crate::schema::EventPayload<'a>,
}

const EVENT_CLICK: u16 = 1;
const EVENT_TEXT_CHANGED: u16 = 2;
const EVENT_TEXT_SUBMITTED: u16 = 3;
const EVENT_FOCUS_LOST: u16 = 4;
const EVENT_PROTOCOL_ERROR: u16 = 5;
const EVENT_KEY_DOWN: u16 = 6;
const EVENT_RANGE_REQUESTED: u16 = 7;
// Tag 8 is the pointer event and 9 to 15 are reserved for the gesture events, so this one
// starts at 16 and the window size report continues from 17.
const EVENT_POINTER: u16 = 8;
const POINTER_RECORD_LEN: usize = 72;
const EVENT_VALUE_CHANGED: u16 = 16;
const EVENT_WINDOW_SIZE_CHANGED: u16 = 17;
const EVENT_RESYNC: u16 = 18;
const EVENT_LIFECYCLE_START: u16 = 19;
const EVENT_LIFECYCLE_STOP: u16 = 20;
const EVENT_DESIGN_SYSTEM_RESOLVED: u16 = 21;
const EVENT_FILES_ENTERED: u16 = 22;
const EVENT_FILES_DROPPED: u16 = 23;
const EVENT_NOTIFICATION_ACTIVATED: u16 = 24;
const EVENT_NOTIFICATION_PERMISSION_CHANGED: u16 = 25;
const EVENT_CODE_CHANGED: u16 = 26;
const EVENT_CODE_EDIT_REJECTED: u16 = 27;
const EVENT_CODE_HOVERED: u16 = 28;
const EVENT_CODE_SAVE_REQUESTED: u16 = 29;
const EVENT_DECORATION_ACTIVATED: u16 = 30;
const EVENT_ANIMATION: u16 = 31;
const EVENT_REDUCED_MOTION_CHANGED: u16 = 32;
// Tags 26 to 32 belong to events other requirements have numbered.
const EVENT_ZOOM_CHANGED: u16 = 33;
// 33 is the zoom event's. 34 to 38 are the code editor's.
const EVENT_SELECTION_CHANGED: u16 = 34;
const EVENT_VISIBLE_RANGE_CHANGED: u16 = 35;
const EVENT_EDITOR_FOCUS_CHANGED: u16 = 36;
const EVENT_FOLD_CHANGED: u16 = 37;
const EVENT_COMPOSITION_CHANGED: u16 = 38;
// 39 to 47 are the windows'.
const EVENT_WINDOW_CLOSE_REQUESTED: u16 = 39;
const EVENT_WINDOW_FOCUS_CHANGED: u16 = 40;
const EVENT_WINDOW_MOVED: u16 = 41;
const EVENT_WINDOW_DRAG: u16 = 42;
const EVENT_WINDOW_DRAG_OVER: u16 = 43;
const EVENT_WINDOW_UNAVAILABLE: u16 = 44;
const EVENT_WINDOW_CLOSED: u16 = 45;
const EVENT_WINDOW_REQUESTED: u16 = 46;
const EVENT_APPLICATION_REOPEN: u16 = 47;
// 48 and 49 are the node focus-gained and key-up events.
const EVENT_FOCUS_GAINED: u16 = 48;
const EVENT_KEY_UP: u16 = 49;
const EVENT_FILE_DIALOG_RESULT: u16 = 50;
// 51 to 53 are numbered for events that are not here yet. 54 and 55 are the code editor's
// row height and scroll offset, each one `f64` of physical pixels.
const EVENT_CODE_METRICS: u16 = 54;
const EVENT_CODE_SCROLLED: u16 = 55;
/// `FileDialogResult` in full: the 16 byte header, three words and the paths' reference.
const FILE_DIALOG_RESULT_LEN: usize = 36;
// 51 to 55 are held by events other requirements have numbered.
const EVENT_CLIPBOARD_TEXT: u16 = 56;
/// `ClipboardText` in full: the 16 byte header, three words and the text's reference.
const CLIPBOARD_TEXT_LEN: usize = 36;
const EVENT_IO_ANSWER: u16 = 57;
/// `IoAnswer` in full: the 16 byte header, two words and the data's reference.
const IO_ANSWER_LEN: usize = 32;
const EVENT_FILE_HANDLES: u16 = 58;
/// `FileHandles` in full: the 16 byte header, five words and two string references.
const FILE_HANDLES_LEN: usize = 52;
const EVENT_STORAGE_LOCATION: u16 = 59;
const EVENT_SCROLL_CHANGED: u16 = 60;
/// `ScrollChanged` in full: the 16 byte header, the item, the flags and six `f64`.
const SCROLL_CHANGED_LEN: usize = 72;
const EVENT_NATIVE_VIEW_PARENT: u16 = 61;
/// `NativeViewParent` in full: the 16 byte header, the handle, the kind, a reserved half
/// word and the scale.
const NATIVE_VIEW_PARENT_LEN: usize = 32;
const EVENT_NATIVE_VIEW_FOCUS: u16 = 62;
/// `NativeViewFocus` in full: the 16 byte header, the focus word and its padding.
const NATIVE_VIEW_FOCUS_LEN: usize = 24;

/// Where `SetWindow` keeps the mode and the last window policy: in the bits of the
/// `resizable` word above the flag.
const WINDOW_MODE_SHIFT: u32 = 8;
const WINDOW_POLICY_SHIFT: u32 = 12;

/// The phases of a `WindowDrag`.
pub const WINDOW_DRAG_MOVE: u32 = 1;
pub const WINDOW_DRAG_END: u32 = 2;
pub const WINDOW_DRAG_CANCEL: u32 = 3;
/// The kinds of a `WindowDragOver`.
pub const WINDOW_DRAG_OVER_ENTER: u32 = 1;
pub const WINDOW_DRAG_OVER_MOVE: u32 = 2;
pub const WINDOW_DRAG_OVER_LEAVE: u32 = 3;
pub const WINDOW_DRAG_OVER_DROP: u32 = 4;

const MODIFIER_SHIFT: u8 = 1 << 0;
const MODIFIER_CTRL: u8 = 1 << 1;
const MODIFIER_ALT: u8 = 1 << 2;
const MODIFIER_META: u8 = 1 << 3;

/// Reads a pointer enum's tag out of a `u32` word. A word that does not fit a tag, or names
/// none, is a value the sides disagree on.
fn pointer_tag<T: TryFrom<u16, Error = ()>>(
    bytes: &[u8],
    offset: usize,
) -> Result<T, ProtocolError> {
    let raw = read_u32(bytes, offset)?;
    u16::try_from(raw)
        .ok()
        .and_then(|tag| T::try_from(tag).ok())
        .ok_or(ProtocolError::InvalidValueKind(
            raw.min(u32::from(u16::MAX)) as u16,
        ))
}

/// The 72-byte pointer record: see the field table of the pointer event.
fn decode_pointer(bytes: &[u8]) -> Result<crate::schema::EventPayload<'_>, ProtocolError> {
    Ok(crate::schema::EventPayload::Pointer {
        kind: pointer_tag(bytes, 16)?,
        pointer_id: read_u32(bytes, 20)?,
        pointer_type: pointer_tag(bytes, 24)?,
        x: f32::from_bits(read_u32(bytes, 28)?),
        y: f32::from_bits(read_u32(bytes, 32)?),
        buttons: read_u32(bytes, 36)?,
        button: read_u32(bytes, 40)?,
        modifiers: read_u32(bytes, 44)?,
        click_count: read_u32(bytes, 48)?,
        delta_x: f32::from_bits(read_u32(bytes, 52)?),
        delta_y: f32::from_bits(read_u32(bytes, 56)?),
        delta_mode: pointer_tag(bytes, 60)?,
        timestamp_nanos: read_u64(bytes, 64)?,
    })
}

/// Decode one Renderer-to-Host event record.
pub fn decode_event(bytes: &[u8]) -> Result<HostEvent<'_>, ProtocolError> {
    if bytes.len() < 16 {
        return Err(ProtocolError::Truncated);
    }
    let tag = read_u16(bytes, 0)?;
    let record_len = usize::from(read_u16(bytes, 2)?);
    if record_len < 16 || record_len % 4 != 0 || record_len > bytes.len() {
        return Err(ProtocolError::InvalidRecordLength);
    }
    let node_id = read_u32(bytes, 4)?;
    let handler_id = read_u64(bytes, 8)?;
    let payload = match tag {
        EVENT_CLICK if record_len == 16 => crate::schema::EventPayload::Clicked,
        EVENT_TEXT_CHANGED if record_len == 24 => {
            crate::schema::EventPayload::TextChanged(read_string(bytes, 16, record_len)?)
        }
        EVENT_TEXT_SUBMITTED if record_len == 24 => {
            crate::schema::EventPayload::TextSubmitted(read_string(bytes, 16, record_len)?)
        }
        EVENT_FOCUS_LOST if record_len == 16 => crate::schema::EventPayload::FocusLost,
        EVENT_FOCUS_GAINED if record_len == 16 => crate::schema::EventPayload::FocusGained,
        EVENT_PROTOCOL_ERROR if record_len == 28 => crate::schema::EventPayload::ProtocolError {
            code: read_u32(bytes, 16)?,
            message: read_string(bytes, 20, record_len)?,
        },
        EVENT_KEY_DOWN if record_len == 32 => {
            let raw_key = read_u16(bytes, 16)?;
            let modifiers = *bytes.get(18).ok_or(ProtocolError::Truncated)?;
            let repeat = *bytes.get(19).ok_or(ProtocolError::Truncated)? != 0;
            crate::schema::EventPayload::KeyDown {
                key: Key::try_from(raw_key)
                    .map_err(|()| ProtocolError::InvalidValueKind(raw_key))?,
                shift_key: modifiers & MODIFIER_SHIFT != 0,
                ctrl_key: modifiers & MODIFIER_CTRL != 0,
                alt_key: modifiers & MODIFIER_ALT != 0,
                meta_key: modifiers & MODIFIER_META != 0,
                repeat,
                code: read_u32(bytes, 20)?,
                text: read_string(bytes, 24, record_len)?,
            }
        }
        // A key-up has the key-down's layout, so a Host handler reads both the same way.
        EVENT_KEY_UP if record_len == 32 => {
            let raw_key = read_u16(bytes, 16)?;
            let modifiers = *bytes.get(18).ok_or(ProtocolError::Truncated)?;
            let repeat = *bytes.get(19).ok_or(ProtocolError::Truncated)? != 0;
            crate::schema::EventPayload::KeyUp {
                key: Key::try_from(raw_key)
                    .map_err(|()| ProtocolError::InvalidValueKind(raw_key))?,
                shift_key: modifiers & MODIFIER_SHIFT != 0,
                ctrl_key: modifiers & MODIFIER_CTRL != 0,
                alt_key: modifiers & MODIFIER_ALT != 0,
                meta_key: modifiers & MODIFIER_META != 0,
                repeat,
                code: read_u32(bytes, 20)?,
                text: read_string(bytes, 24, record_len)?,
            }
        }
        EVENT_RANGE_REQUESTED if record_len == 24 => crate::schema::EventPayload::RangeRequested {
            start: read_u32(bytes, 16)?,
            count: read_u32(bytes, 20)?,
        },
        EVENT_VALUE_CHANGED if record_len == 24 => {
            crate::schema::EventPayload::ValueChanged(f64::from_bits(read_u64(bytes, 16)?))
        }
        EVENT_WINDOW_SIZE_CHANGED if record_len == 44 => {
            let raw_class = read_u32(bytes, 24)?;
            let class = u16::try_from(raw_class)
                .ok()
                .and_then(|value| crate::schema::WindowSizeClass::try_from(value).ok())
                .ok_or(ProtocolError::InvalidValueKind(raw_class as u16))?;
            let raw_height_class = read_u32(bytes, 28)?;
            let height_class = u16::try_from(raw_height_class)
                .ok()
                .and_then(|value| crate::schema::WindowHeightClass::try_from(value).ok())
                .ok_or(ProtocolError::InvalidValueKind(raw_height_class as u16))?;
            crate::schema::EventPayload::WindowSizeChanged {
                width_dp: f32::from_bits(read_u32(bytes, 16)?),
                height_dp: f32::from_bits(read_u32(bytes, 20)?),
                class,
                height_class,
                safe_area_top: f32::from_bits(read_u32(bytes, 32)?),
                safe_area_leading: f32::from_bits(read_u32(bytes, 36)?),
                safe_area_trailing: f32::from_bits(read_u32(bytes, 40)?),
            }
        }
        EVENT_DESIGN_SYSTEM_RESOLVED if record_len == 20 => {
            let raw = read_u32(bytes, 16)?;
            let system = u16::try_from(raw)
                .ok()
                .and_then(|tag| crate::schema::DesignSystem::try_from(tag).ok())
                .ok_or(ProtocolError::InvalidValueKind(raw as u16))?;
            crate::schema::EventPayload::DesignSystemResolved(system)
        }
        EVENT_FILES_ENTERED if record_len == 16 => crate::schema::EventPayload::FilesEntered,
        EVENT_FILES_DROPPED if record_len == 24 => {
            crate::schema::EventPayload::FilesDropped(read_string(bytes, 16, record_len)?)
        }
        EVENT_NOTIFICATION_ACTIVATED if record_len == 28 => {
            let action = read_u32(bytes, 16)?;
            // Zero is the body and one and two are the two buttons a notification can have.
            // Anything else names a button that cannot exist.
            if action > 2 {
                return Err(ProtocolError::InvalidValueKind(
                    action.min(u32::from(u16::MAX)) as u16,
                ));
            }
            crate::schema::EventPayload::NotificationActivated {
                action,
                key: read_string(bytes, 20, record_len)?,
            }
        }
        EVENT_NOTIFICATION_PERMISSION_CHANGED if record_len == 20 => {
            let raw = read_u32(bytes, 16)?;
            let state = u16::try_from(raw)
                .ok()
                .and_then(|tag| NotificationPermission::try_from(tag).ok())
                .ok_or(ProtocolError::InvalidValueKind(
                    raw.min(u32::from(u16::MAX)) as u16,
                ))?;
            crate::schema::EventPayload::NotificationPermissionChanged(state)
        }
        EVENT_ANIMATION if record_len == 40 => {
            let raw_kind = u16::from(read_u8(bytes, 20)?);
            let raw_property = u16::from(read_u8(bytes, 21)?);
            crate::schema::EventPayload::AnimationEvent {
                animation_id: read_u32(bytes, 16)?,
                kind: crate::schema::AnimationEventKind::try_from(raw_kind)
                    .map_err(|()| ProtocolError::InvalidValueKind(raw_kind))?,
                property: crate::schema::AnimatedProperty::try_from(raw_property)
                    .map_err(|()| ProtocolError::InvalidValueKind(raw_property))?,
                slot: read_u8(bytes, 22)?,
                iteration: read_u32(bytes, 24)?,
                elapsed_ms: f32::from_bits(read_u32(bytes, 28)?),
                time_nanos: read_u64(bytes, 32)?,
            }
        }
        EVENT_POINTER if record_len == POINTER_RECORD_LEN => decode_pointer(bytes)?,
        EVENT_REDUCED_MOTION_CHANGED if record_len == 20 => {
            let raw = read_u32(bytes, 16)?;
            let state = u16::try_from(raw)
                .ok()
                .and_then(|tag| crate::schema::ReducedMotion::try_from(tag).ok())
                .ok_or(ProtocolError::InvalidValueKind(
                    raw.min(u32::from(u16::MAX)) as u16,
                ))?;
            crate::schema::EventPayload::ReducedMotionChanged(state)
        }
        EVENT_CODE_CHANGED if record_len == 44 => crate::schema::EventPayload::CodeChanged {
            version: read_u32(bytes, 16)?,
            start_line: read_u32(bytes, 20)?,
            start_column: read_u32(bytes, 24)?,
            end_line: read_u32(bytes, 28)?,
            end_column: read_u32(bytes, 32)?,
            text: read_string(bytes, 36, record_len)?,
        },
        EVENT_CODE_EDIT_REJECTED if record_len == 44 => {
            crate::schema::EventPayload::CodeEditRejected {
                request_id: read_u32(bytes, 16)?,
                base_version: read_u32(bytes, 20)?,
                current_version: read_u32(bytes, 24)?,
                start_line: read_u32(bytes, 28)?,
                start_column: read_u32(bytes, 32)?,
                end_line: read_u32(bytes, 36)?,
                end_column: read_u32(bytes, 40)?,
            }
        }
        EVENT_CODE_HOVERED if record_len == 36 => {
            let raw_phase = read_u32(bytes, 32)?;
            let phase = u16::try_from(raw_phase)
                .ok()
                .and_then(|tag| crate::schema::HoverPhase::try_from(tag).ok())
                .ok_or(ProtocolError::InvalidValueKind(raw_phase as u16))?;
            crate::schema::EventPayload::CodeHovered {
                decoration: read_u64(bytes, 16)?,
                line: read_u32(bytes, 24)?,
                column: read_u32(bytes, 28)?,
                phase,
            }
        }
        EVENT_CODE_SAVE_REQUESTED if record_len == 24 => {
            crate::schema::EventPayload::CodeSaveRequested {
                version: read_u32(bytes, 16)?,
            }
        }
        EVENT_DECORATION_ACTIVATED if record_len == 24 => {
            crate::schema::EventPayload::DecorationActivated {
                decoration: read_u64(bytes, 16)?,
            }
        }
        EVENT_CODE_CHANGED..=EVENT_DECORATION_ACTIVATED => {
            return Err(ProtocolError::InvalidRecordLength);
        }
        EVENT_SELECTION_CHANGED if record_len == 36 => {
            crate::schema::EventPayload::SelectionChanged {
                version: read_u32(bytes, 16)?,
                anchor_line: read_u32(bytes, 20)?,
                anchor_column: read_u32(bytes, 24)?,
                active_line: read_u32(bytes, 28)?,
                active_column: read_u32(bytes, 32)?,
            }
        }
        EVENT_VISIBLE_RANGE_CHANGED if record_len == 28 => {
            crate::schema::EventPayload::VisibleRangeChanged {
                version: read_u32(bytes, 16)?,
                first_line: read_u32(bytes, 20)?,
                last_line: read_u32(bytes, 24)?,
            }
        }
        EVENT_EDITOR_FOCUS_CHANGED if record_len == 24 => {
            crate::schema::EventPayload::EditorFocusChanged {
                focused: read_flag(bytes, 16)?,
            }
        }
        EVENT_FOLD_CHANGED if record_len == 24 => crate::schema::EventPayload::FoldChanged {
            start_line: read_u32(bytes, 16)?,
            collapsed: read_flag(bytes, 20)?,
        },
        EVENT_COMPOSITION_CHANGED if record_len == 24 => {
            crate::schema::EventPayload::CompositionChanged {
                composing: read_flag(bytes, 16)?,
            }
        }
        EVENT_SELECTION_CHANGED..=EVENT_COMPOSITION_CHANGED => {
            return Err(ProtocolError::InvalidRecordLength);
        }
        EVENT_WINDOW_CLOSE_REQUESTED if record_len == 16 => {
            crate::schema::EventPayload::WindowCloseRequested
        }
        EVENT_WINDOW_FOCUS_CHANGED if record_len == 20 => {
            crate::schema::EventPayload::WindowFocusChanged {
                focused: read_flag(bytes, 16)?,
            }
        }
        EVENT_WINDOW_MOVED if record_len == 32 => crate::schema::EventPayload::WindowMoved {
            x: f32::from_bits(read_u32(bytes, 16)?),
            y: f32::from_bits(read_u32(bytes, 20)?),
            width: f32::from_bits(read_u32(bytes, 24)?),
            height: f32::from_bits(read_u32(bytes, 28)?),
        },
        EVENT_WINDOW_DRAG if record_len == 32 => {
            let phase = read_u32(bytes, 16)?;
            if !(WINDOW_DRAG_MOVE..=WINDOW_DRAG_CANCEL).contains(&phase) {
                return Err(ProtocolError::InvalidValueKind(
                    phase.min(u32::from(u16::MAX)) as u16,
                ));
            }
            crate::schema::EventPayload::WindowDrag {
                phase,
                screen_x: f32::from_bits(read_u32(bytes, 20)?),
                screen_y: f32::from_bits(read_u32(bytes, 24)?),
                over_window: read_u32(bytes, 28)?,
            }
        }
        EVENT_WINDOW_DRAG_OVER if record_len == 32 => {
            let kind = read_u32(bytes, 16)?;
            if !(WINDOW_DRAG_OVER_ENTER..=WINDOW_DRAG_OVER_DROP).contains(&kind) {
                return Err(ProtocolError::InvalidValueKind(
                    kind.min(u32::from(u16::MAX)) as u16,
                ));
            }
            crate::schema::EventPayload::WindowDragOver {
                kind,
                window: read_u32(bytes, 20)?,
                x: f32::from_bits(read_u32(bytes, 24)?),
                y: f32::from_bits(read_u32(bytes, 28)?),
            }
        }
        EVENT_WINDOW_UNAVAILABLE if record_len == 16 => {
            crate::schema::EventPayload::WindowUnavailable
        }
        EVENT_WINDOW_CLOSED if record_len == 16 => crate::schema::EventPayload::WindowClosed,
        EVENT_WINDOW_REQUESTED if record_len == 28 => {
            crate::schema::EventPayload::WindowRequested {
                request: read_u32(bytes, 16)?,
                key: read_string(bytes, 20, record_len)?,
            }
        }
        EVENT_APPLICATION_REOPEN if record_len == 16 => {
            crate::schema::EventPayload::ApplicationReopen
        }
        EVENT_FILE_DIALOG_RESULT if record_len == FILE_DIALOG_RESULT_LEN => {
            let raw_status = read_u32(bytes, 20)?;
            let status = u16::try_from(raw_status)
                .ok()
                .and_then(|tag| FileDialogStatus::try_from(tag).ok())
                .ok_or(ProtocolError::InvalidValueKind(
                    raw_status.min(u32::from(u16::MAX)) as u16,
                ))?;
            crate::schema::EventPayload::FileDialogResult {
                request: read_u32(bytes, 16)?,
                status,
                filter: read_u32(bytes, 24)?,
                paths: read_string(bytes, 28, record_len)?,
            }
        }
        EVENT_FILE_DIALOG_RESULT => return Err(ProtocolError::InvalidRecordLength),
        EVENT_CLIPBOARD_TEXT if record_len == CLIPBOARD_TEXT_LEN => {
            crate::schema::EventPayload::ClipboardText {
                request: read_u32(bytes, 16)?,
                status: read_u32(bytes, 20)?,
                len: read_u32(bytes, 24)?,
                text: read_string(bytes, 28, record_len)?,
            }
        }
        EVENT_CLIPBOARD_TEXT => return Err(ProtocolError::InvalidRecordLength),
        EVENT_CODE_METRICS if record_len == 24 => crate::schema::EventPayload::CodeMetrics {
            line_height_px: f64::from_bits(read_u64(bytes, 16)?),
        },
        EVENT_CODE_SCROLLED if record_len == 24 => crate::schema::EventPayload::CodeScrolled {
            offset_px: f64::from_bits(read_u64(bytes, 16)?),
        },
        EVENT_CODE_METRICS | EVENT_CODE_SCROLLED => {
            return Err(ProtocolError::InvalidRecordLength);
        }
        EVENT_IO_ANSWER if record_len == IO_ANSWER_LEN => {
            let raw_status = read_u32(bytes, 20)?;
            let status = u16::try_from(raw_status)
                .ok()
                .and_then(|tag| IoStatus::try_from(tag).ok())
                .ok_or(ProtocolError::InvalidValueKind(
                    raw_status.min(u32::from(u16::MAX)) as u16,
                ))?;
            crate::schema::EventPayload::IoAnswer {
                request: read_u32(bytes, 16)?,
                status,
                data: read_bytes(bytes, 24, record_len)?,
            }
        }
        EVENT_IO_ANSWER => return Err(ProtocolError::InvalidRecordLength),
        EVENT_FILE_HANDLES if record_len == FILE_HANDLES_LEN => {
            let raw_status = read_u32(bytes, 20)?;
            let status = u16::try_from(raw_status)
                .ok()
                .and_then(|tag| FileDialogStatus::try_from(tag).ok())
                .ok_or(ProtocolError::InvalidValueKind(
                    raw_status.min(u32::from(u16::MAX)) as u16,
                ))?;
            let raw_delivery = read_u32(bytes, 28)?;
            let delivery = u16::try_from(raw_delivery)
                .ok()
                .and_then(|tag| FileDelivery::try_from(tag).ok())
                .ok_or(ProtocolError::InvalidValueKind(
                    raw_delivery.min(u32::from(u16::MAX)) as u16,
                ))?;
            let names = read_string(bytes, 36, record_len)?;
            let kinds = read_string(bytes, 44, record_len)?;
            // One letter per name, and only the two letters there are. A list whose kinds do
            // not line up with its names would hand the application a file as a folder.
            if let Some(unknown) = kinds.bytes().find(|kind| *kind != b'f' && *kind != b'd') {
                return Err(ProtocolError::InvalidValueKind(u16::from(unknown)));
            }
            let count = if names.is_empty() {
                0
            } else {
                names.split('\0').count()
            };
            if count != kinds.len() || names.split('\0').any(|name| count != 0 && name.is_empty()) {
                return Err(ProtocolError::InvalidStringRange);
            }
            crate::schema::EventPayload::FileHandles {
                request: read_u32(bytes, 16)?,
                status,
                filter: read_u32(bytes, 24)?,
                delivery,
                first_handle: read_u32(bytes, 32)?,
                names,
                kinds,
            }
        }
        EVENT_FILE_HANDLES => return Err(ProtocolError::InvalidRecordLength),
        EVENT_STORAGE_LOCATION if record_len == 24 => {
            crate::schema::EventPayload::StorageLocation(read_string(bytes, 16, record_len)?)
        }
        EVENT_STORAGE_LOCATION => return Err(ProtocolError::InvalidRecordLength),
        EVENT_SCROLL_CHANGED if record_len == SCROLL_CHANGED_LEN => {
            use crate::schema::scroll_changed_flags as flag;
            let flags = read_u32(bytes, 20)?;
            if flags & !flag::KNOWN != 0 {
                // A bit with no meaning here is a newer or a broken Renderer, and guessing
                // what it says about the position would be worse than refusing the report.
                return Err(ProtocolError::InvalidValueKind(
                    (flags & !flag::KNOWN).trailing_zeros() as u16,
                ));
            }
            crate::schema::EventPayload::ScrollChanged {
                item: read_u32(bytes, 16)?,
                settled: flags & flag::SETTLED != 0,
                can_scroll_backward: flags & flag::CAN_SCROLL_BACKWARD != 0,
                can_scroll_forward: flags & flag::CAN_SCROLL_FORWARD != 0,
                x_px: f64::from_bits(read_u64(bytes, 24)?),
                y_px: f64::from_bits(read_u64(bytes, 32)?),
                max_x_px: f64::from_bits(read_u64(bytes, 40)?),
                max_y_px: f64::from_bits(read_u64(bytes, 48)?),
                viewport_width_px: f64::from_bits(read_u64(bytes, 56)?),
                viewport_height_px: f64::from_bits(read_u64(bytes, 64)?),
            }
        }
        EVENT_SCROLL_CHANGED => return Err(ProtocolError::InvalidRecordLength),
        EVENT_NATIVE_VIEW_PARENT if record_len == NATIVE_VIEW_PARENT_LEN => {
            let raw_kind = read_u16(bytes, 24)?;
            let kind = crate::schema::NativeParentKind::try_from(raw_kind)
                .map_err(|()| ProtocolError::InvalidValueKind(raw_kind))?;
            let reserved = read_u16(bytes, 26)?;
            if reserved != 0 {
                // A newer Renderer saying something here that this Host cannot read.
                return Err(ProtocolError::InvalidValueKind(reserved));
            }
            let scale = f32::from_bits(read_u32(bytes, 28)?);
            if !scale.is_finite() || scale <= 0.0 {
                // A view sized with this would be sized wrong or not at all.
                return Err(ProtocolError::InvalidValueKind(0));
            }
            crate::schema::EventPayload::NativeViewParent {
                handle: crate::schema::NativeHandle(read_u64(bytes, 16)?),
                kind,
                scale,
            }
        }
        EVENT_NATIVE_VIEW_FOCUS if record_len == NATIVE_VIEW_FOCUS_LEN => {
            crate::schema::EventPayload::NativeViewFocus {
                focused: read_flag(bytes, 16)?,
            }
        }
        EVENT_NATIVE_VIEW_PARENT | EVENT_NATIVE_VIEW_FOCUS => {
            return Err(ProtocolError::InvalidRecordLength);
        }
        EVENT_WINDOW_CLOSE_REQUESTED..=EVENT_APPLICATION_REOPEN => {
            return Err(ProtocolError::InvalidRecordLength);
        }
        EVENT_ZOOM_CHANGED if record_len == 28 => {
            let level = read_u32(bytes, 24)? as i32;
            if !(i32::from(crate::zoom::MIN_ZOOM_LEVEL)..=i32::from(crate::zoom::MAX_ZOOM_LEVEL))
                .contains(&level)
            {
                return Err(ProtocolError::InvalidValueKind(level as u16));
            }
            crate::schema::EventPayload::ZoomChanged {
                k: f32::from_bits(read_u32(bytes, 16)?),
                os: f32::from_bits(read_u32(bytes, 20)?),
                level,
            }
        }
        EVENT_RESYNC if record_len == 16 => crate::schema::EventPayload::Resync,
        EVENT_LIFECYCLE_START if record_len == 16 => crate::schema::EventPayload::LifecycleStart,
        EVENT_LIFECYCLE_STOP if record_len == 16 => crate::schema::EventPayload::LifecycleStop,
        EVENT_CLICK..=EVENT_RANGE_REQUESTED
        | EVENT_VALUE_CHANGED..=EVENT_NOTIFICATION_PERMISSION_CHANGED
        | EVENT_ANIMATION
        | EVENT_POINTER
        | EVENT_REDUCED_MOTION_CHANGED
        | EVENT_ZOOM_CHANGED
        | EVENT_FOCUS_GAINED
        | EVENT_KEY_UP => {
            return Err(ProtocolError::InvalidRecordLength);
        }
        other => return Err(ProtocolError::InvalidTag(other)),
    };
    Ok(HostEvent {
        node_id,
        handler_id,
        payload,
    })
}

/// Test/mock helper for constructing a Renderer-to-Host event.
pub fn encode_event(event: &HostEvent<'_>, output: &mut Vec<u8>) -> Result<(), ProtocolError> {
    output.clear();
    if let crate::schema::EventPayload::KeyDown {
        key,
        shift_key,
        ctrl_key,
        alt_key,
        meta_key,
        repeat,
        code,
        text,
    } = event.payload
    {
        output.extend_from_slice(&EVENT_KEY_DOWN.to_le_bytes());
        output.extend_from_slice(&32_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&(key as u16).to_le_bytes());
        let modifiers = (u8::from(shift_key) * MODIFIER_SHIFT)
            | (u8::from(ctrl_key) * MODIFIER_CTRL)
            | (u8::from(alt_key) * MODIFIER_ALT)
            | (u8::from(meta_key) * MODIFIER_META);
        output.push(modifiers);
        output.push(u8::from(repeat));
        output.extend_from_slice(&code.to_le_bytes());
        let len = u32::try_from(text.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        output.extend_from_slice(&32_u32.to_le_bytes());
        output.extend_from_slice(&len.to_le_bytes());
        output.extend_from_slice(text.as_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::KeyUp {
        key,
        shift_key,
        ctrl_key,
        alt_key,
        meta_key,
        repeat,
        code,
        text,
    } = event.payload
    {
        output.extend_from_slice(&EVENT_KEY_UP.to_le_bytes());
        output.extend_from_slice(&32_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&(key as u16).to_le_bytes());
        let modifiers = (u8::from(shift_key) * MODIFIER_SHIFT)
            | (u8::from(ctrl_key) * MODIFIER_CTRL)
            | (u8::from(alt_key) * MODIFIER_ALT)
            | (u8::from(meta_key) * MODIFIER_META);
        output.push(modifiers);
        output.push(u8::from(repeat));
        output.extend_from_slice(&code.to_le_bytes());
        let len = u32::try_from(text.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        output.extend_from_slice(&32_u32.to_le_bytes());
        output.extend_from_slice(&len.to_le_bytes());
        output.extend_from_slice(text.as_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::ValueChanged(value) = event.payload {
        output.extend_from_slice(&EVENT_VALUE_CHANGED.to_le_bytes());
        output.extend_from_slice(&24_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&value.to_bits().to_le_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::WindowSizeChanged {
        width_dp,
        height_dp,
        class,
        height_class,
        safe_area_top,
        safe_area_leading,
        safe_area_trailing,
    } = event.payload
    {
        output.extend_from_slice(&EVENT_WINDOW_SIZE_CHANGED.to_le_bytes());
        output.extend_from_slice(&44_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&width_dp.to_bits().to_le_bytes());
        output.extend_from_slice(&height_dp.to_bits().to_le_bytes());
        output.extend_from_slice(&u32::from(u16::from(class)).to_le_bytes());
        output.extend_from_slice(&u32::from(u16::from(height_class)).to_le_bytes());
        output.extend_from_slice(&safe_area_top.to_bits().to_le_bytes());
        output.extend_from_slice(&safe_area_leading.to_bits().to_le_bytes());
        output.extend_from_slice(&safe_area_trailing.to_bits().to_le_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::DesignSystemResolved(system) = event.payload {
        output.extend_from_slice(&EVENT_DESIGN_SYSTEM_RESOLVED.to_le_bytes());
        output.extend_from_slice(&20_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        // The tag in a word, which keeps the record a multiple of four the way every
        // other record here is.
        output.extend_from_slice(&u32::from(u16::from(system)).to_le_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::NotificationPermissionChanged(state) = event.payload {
        output.extend_from_slice(&EVENT_NOTIFICATION_PERMISSION_CHANGED.to_le_bytes());
        output.extend_from_slice(&20_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&u32::from(u16::from(state)).to_le_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::AnimationEvent {
        animation_id,
        kind,
        property,
        slot,
        iteration,
        elapsed_ms,
        time_nanos,
    } = event.payload
    {
        output.extend_from_slice(&EVENT_ANIMATION.to_le_bytes());
        output.extend_from_slice(&40_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&animation_id.to_le_bytes());
        output.push(kind as u8);
        output.push(property as u8);
        output.push(slot);
        output.push(0);
        output.extend_from_slice(&iteration.to_le_bytes());
        output.extend_from_slice(&elapsed_ms.to_bits().to_le_bytes());
        output.extend_from_slice(&time_nanos.to_le_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::Pointer {
        kind,
        pointer_id,
        pointer_type,
        x,
        y,
        buttons,
        button,
        modifiers,
        click_count,
        delta_x,
        delta_y,
        delta_mode,
        timestamp_nanos,
    } = event.payload
    {
        output.extend_from_slice(&EVENT_POINTER.to_le_bytes());
        output.extend_from_slice(&(POINTER_RECORD_LEN as u16).to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&u32::from(u16::from(kind)).to_le_bytes());
        output.extend_from_slice(&pointer_id.to_le_bytes());
        output.extend_from_slice(&u32::from(u16::from(pointer_type)).to_le_bytes());
        output.extend_from_slice(&x.to_bits().to_le_bytes());
        output.extend_from_slice(&y.to_bits().to_le_bytes());
        output.extend_from_slice(&buttons.to_le_bytes());
        output.extend_from_slice(&button.to_le_bytes());
        output.extend_from_slice(&modifiers.to_le_bytes());
        output.extend_from_slice(&click_count.to_le_bytes());
        output.extend_from_slice(&delta_x.to_bits().to_le_bytes());
        output.extend_from_slice(&delta_y.to_bits().to_le_bytes());
        output.extend_from_slice(&u32::from(u16::from(delta_mode)).to_le_bytes());
        output.extend_from_slice(&timestamp_nanos.to_le_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::ReducedMotionChanged(state) = event.payload {
        output.extend_from_slice(&EVENT_REDUCED_MOTION_CHANGED.to_le_bytes());
        output.extend_from_slice(&20_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&u32::from(u16::from(state)).to_le_bytes());
        return Ok(());
    }
    match event.payload {
        crate::schema::EventPayload::CodeChanged {
            version,
            start_line,
            start_column,
            end_line,
            end_column,
            text,
        } => {
            put_event_header(output, EVENT_CODE_CHANGED, 44, event);
            for value in [version, start_line, start_column, end_line, end_column] {
                output.extend_from_slice(&value.to_le_bytes());
            }
            let len = u32::try_from(text.len()).map_err(|_| ProtocolError::LengthOverflow)?;
            output.extend_from_slice(&44_u32.to_le_bytes());
            output.extend_from_slice(&len.to_le_bytes());
            output.extend_from_slice(text.as_bytes());
            return Ok(());
        }
        crate::schema::EventPayload::CodeEditRejected {
            request_id,
            base_version,
            current_version,
            start_line,
            start_column,
            end_line,
            end_column,
        } => {
            put_event_header(output, EVENT_CODE_EDIT_REJECTED, 44, event);
            for value in [
                request_id,
                base_version,
                current_version,
                start_line,
                start_column,
                end_line,
                end_column,
            ] {
                output.extend_from_slice(&value.to_le_bytes());
            }
            return Ok(());
        }
        crate::schema::EventPayload::CodeHovered {
            decoration,
            line,
            column,
            phase,
        } => {
            put_event_header(output, EVENT_CODE_HOVERED, 36, event);
            output.extend_from_slice(&decoration.to_le_bytes());
            output.extend_from_slice(&line.to_le_bytes());
            output.extend_from_slice(&column.to_le_bytes());
            output.extend_from_slice(&u32::from(u16::from(phase)).to_le_bytes());
            return Ok(());
        }
        crate::schema::EventPayload::CodeSaveRequested { version } => {
            put_event_header(output, EVENT_CODE_SAVE_REQUESTED, 24, event);
            output.extend_from_slice(&version.to_le_bytes());
            // The padding that keeps the record a multiple of four bytes.
            output.extend_from_slice(&0_u32.to_le_bytes());
            return Ok(());
        }
        crate::schema::EventPayload::DecorationActivated { decoration } => {
            put_event_header(output, EVENT_DECORATION_ACTIVATED, 24, event);
            output.extend_from_slice(&decoration.to_le_bytes());
            return Ok(());
        }
        crate::schema::EventPayload::SelectionChanged {
            version,
            anchor_line,
            anchor_column,
            active_line,
            active_column,
        } => {
            put_event_header(output, EVENT_SELECTION_CHANGED, 36, event);
            for value in [
                version,
                anchor_line,
                anchor_column,
                active_line,
                active_column,
            ] {
                output.extend_from_slice(&value.to_le_bytes());
            }
            return Ok(());
        }
        crate::schema::EventPayload::VisibleRangeChanged {
            version,
            first_line,
            last_line,
        } => {
            put_event_header(output, EVENT_VISIBLE_RANGE_CHANGED, 28, event);
            for value in [version, first_line, last_line] {
                output.extend_from_slice(&value.to_le_bytes());
            }
            return Ok(());
        }
        crate::schema::EventPayload::EditorFocusChanged { focused } => {
            put_event_header(output, EVENT_EDITOR_FOCUS_CHANGED, 24, event);
            output.extend_from_slice(&u32::from(focused).to_le_bytes());
            output.extend_from_slice(&0_u32.to_le_bytes());
            return Ok(());
        }
        crate::schema::EventPayload::FoldChanged {
            start_line,
            collapsed,
        } => {
            put_event_header(output, EVENT_FOLD_CHANGED, 24, event);
            output.extend_from_slice(&start_line.to_le_bytes());
            output.extend_from_slice(&u32::from(collapsed).to_le_bytes());
            return Ok(());
        }
        crate::schema::EventPayload::CompositionChanged { composing } => {
            put_event_header(output, EVENT_COMPOSITION_CHANGED, 24, event);
            output.extend_from_slice(&u32::from(composing).to_le_bytes());
            output.extend_from_slice(&0_u32.to_le_bytes());
            return Ok(());
        }
        _ => {}
    }
    match event.payload {
        crate::schema::EventPayload::WindowFocusChanged { focused } => {
            put_event_header(output, EVENT_WINDOW_FOCUS_CHANGED, 20, event);
            output.extend_from_slice(&u32::from(focused).to_le_bytes());
            return Ok(());
        }
        crate::schema::EventPayload::WindowMoved {
            x,
            y,
            width,
            height,
        } => {
            put_event_header(output, EVENT_WINDOW_MOVED, 32, event);
            for value in [x, y, width, height] {
                output.extend_from_slice(&value.to_bits().to_le_bytes());
            }
            return Ok(());
        }
        crate::schema::EventPayload::WindowDrag {
            phase,
            screen_x,
            screen_y,
            over_window,
        } => {
            put_event_header(output, EVENT_WINDOW_DRAG, 32, event);
            output.extend_from_slice(&phase.to_le_bytes());
            output.extend_from_slice(&screen_x.to_bits().to_le_bytes());
            output.extend_from_slice(&screen_y.to_bits().to_le_bytes());
            output.extend_from_slice(&over_window.to_le_bytes());
            return Ok(());
        }
        crate::schema::EventPayload::WindowDragOver { kind, window, x, y } => {
            put_event_header(output, EVENT_WINDOW_DRAG_OVER, 32, event);
            output.extend_from_slice(&kind.to_le_bytes());
            output.extend_from_slice(&window.to_le_bytes());
            output.extend_from_slice(&x.to_bits().to_le_bytes());
            output.extend_from_slice(&y.to_bits().to_le_bytes());
            return Ok(());
        }
        _ => {}
    }
    if let crate::schema::EventPayload::FileDialogResult {
        request,
        status,
        filter,
        paths,
    } = event.payload
    {
        put_event_header(
            output,
            EVENT_FILE_DIALOG_RESULT,
            FILE_DIALOG_RESULT_LEN as u16,
            event,
        );
        output.extend_from_slice(&request.to_le_bytes());
        output.extend_from_slice(&u32::from(status as u16).to_le_bytes());
        output.extend_from_slice(&filter.to_le_bytes());
        let len = u32::try_from(paths.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        output.extend_from_slice(&(FILE_DIALOG_RESULT_LEN as u32).to_le_bytes());
        output.extend_from_slice(&len.to_le_bytes());
        output.extend_from_slice(paths.as_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::ClipboardText {
        request,
        status,
        len,
        text,
    } = event.payload
    {
        put_event_header(
            output,
            EVENT_CLIPBOARD_TEXT,
            CLIPBOARD_TEXT_LEN as u16,
            event,
        );
        output.extend_from_slice(&request.to_le_bytes());
        output.extend_from_slice(&status.to_le_bytes());
        output.extend_from_slice(&len.to_le_bytes());
        let text_len = u32::try_from(text.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        output.extend_from_slice(&(CLIPBOARD_TEXT_LEN as u32).to_le_bytes());
        output.extend_from_slice(&text_len.to_le_bytes());
        output.extend_from_slice(text.as_bytes());
        return Ok(());
    }
    match event.payload {
        crate::schema::EventPayload::CodeMetrics { line_height_px } => {
            put_event_header(output, EVENT_CODE_METRICS, 24, event);
            output.extend_from_slice(&line_height_px.to_bits().to_le_bytes());
            return Ok(());
        }
        crate::schema::EventPayload::CodeScrolled { offset_px } => {
            put_event_header(output, EVENT_CODE_SCROLLED, 24, event);
            output.extend_from_slice(&offset_px.to_bits().to_le_bytes());
            return Ok(());
        }
        _ => {}
    }
    if let crate::schema::EventPayload::IoAnswer {
        request,
        status,
        data,
    } = event.payload
    {
        put_event_header(output, EVENT_IO_ANSWER, IO_ANSWER_LEN as u16, event);
        output.extend_from_slice(&request.to_le_bytes());
        output.extend_from_slice(&u32::from(u16::from(status)).to_le_bytes());
        let data_len = u32::try_from(data.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        output.extend_from_slice(&(IO_ANSWER_LEN as u32).to_le_bytes());
        output.extend_from_slice(&data_len.to_le_bytes());
        output.extend_from_slice(data);
        return Ok(());
    }
    if let crate::schema::EventPayload::FileHandles {
        request,
        status,
        filter,
        delivery,
        first_handle,
        names,
        kinds,
    } = event.payload
    {
        put_event_header(output, EVENT_FILE_HANDLES, FILE_HANDLES_LEN as u16, event);
        output.extend_from_slice(&request.to_le_bytes());
        output.extend_from_slice(&u32::from(status as u16).to_le_bytes());
        output.extend_from_slice(&filter.to_le_bytes());
        output.extend_from_slice(&u32::from(delivery as u16).to_le_bytes());
        output.extend_from_slice(&first_handle.to_le_bytes());
        let names_len = u32::try_from(names.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        let kinds_len = u32::try_from(kinds.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        let kinds_at = (FILE_HANDLES_LEN as u32)
            .checked_add(names_len)
            .ok_or(ProtocolError::LengthOverflow)?;
        output.extend_from_slice(&(FILE_HANDLES_LEN as u32).to_le_bytes());
        output.extend_from_slice(&names_len.to_le_bytes());
        output.extend_from_slice(&kinds_at.to_le_bytes());
        output.extend_from_slice(&kinds_len.to_le_bytes());
        output.extend_from_slice(names.as_bytes());
        output.extend_from_slice(kinds.as_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::ScrollChanged {
        item,
        settled,
        can_scroll_backward,
        can_scroll_forward,
        x_px,
        y_px,
        max_x_px,
        max_y_px,
        viewport_width_px,
        viewport_height_px,
    } = event.payload
    {
        use crate::schema::scroll_changed_flags as flag;
        put_event_header(
            output,
            EVENT_SCROLL_CHANGED,
            SCROLL_CHANGED_LEN as u16,
            event,
        );
        let mut flags = 0_u32;
        if settled {
            flags |= flag::SETTLED;
        }
        if can_scroll_backward {
            flags |= flag::CAN_SCROLL_BACKWARD;
        }
        if can_scroll_forward {
            flags |= flag::CAN_SCROLL_FORWARD;
        }
        output.extend_from_slice(&item.to_le_bytes());
        output.extend_from_slice(&flags.to_le_bytes());
        for value in [
            x_px,
            y_px,
            max_x_px,
            max_y_px,
            viewport_width_px,
            viewport_height_px,
        ] {
            output.extend_from_slice(&value.to_bits().to_le_bytes());
        }
        return Ok(());
    }
    if let crate::schema::EventPayload::NativeViewParent {
        handle,
        kind,
        scale,
    } = event.payload
    {
        put_event_header(
            output,
            EVENT_NATIVE_VIEW_PARENT,
            NATIVE_VIEW_PARENT_LEN as u16,
            event,
        );
        output.extend_from_slice(&handle.0.to_le_bytes());
        output.extend_from_slice(&(kind as u16).to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&scale.to_bits().to_le_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::NativeViewFocus { focused } = event.payload {
        put_event_header(
            output,
            EVENT_NATIVE_VIEW_FOCUS,
            NATIVE_VIEW_FOCUS_LEN as u16,
            event,
        );
        output.extend_from_slice(&u32::from(focused).to_le_bytes());
        output.extend_from_slice(&0_u32.to_le_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::ZoomChanged { k, os, level } = event.payload {
        output.extend_from_slice(&EVENT_ZOOM_CHANGED.to_le_bytes());
        output.extend_from_slice(&28_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&k.to_bits().to_le_bytes());
        output.extend_from_slice(&os.to_bits().to_le_bytes());
        output.extend_from_slice(&level.to_le_bytes());
        return Ok(());
    }
    if let crate::schema::EventPayload::RangeRequested { start, count } = event.payload {
        output.extend_from_slice(&EVENT_RANGE_REQUESTED.to_le_bytes());
        output.extend_from_slice(&24_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&start.to_le_bytes());
        output.extend_from_slice(&count.to_le_bytes());
        return Ok(());
    }
    let (tag, record_len, text, error_code) = match &event.payload {
        crate::schema::EventPayload::Clicked => (EVENT_CLICK, 16_u16, None, None),
        crate::schema::EventPayload::TextChanged(value) => {
            (EVENT_TEXT_CHANGED, 24, Some(*value), None)
        }
        crate::schema::EventPayload::TextSubmitted(value) => {
            (EVENT_TEXT_SUBMITTED, 24, Some(*value), None)
        }
        crate::schema::EventPayload::FocusLost => (EVENT_FOCUS_LOST, 16, None, None),
        crate::schema::EventPayload::FocusGained => (EVENT_FOCUS_GAINED, 16, None, None),
        crate::schema::EventPayload::FilesEntered => (EVENT_FILES_ENTERED, 16, None, None),
        crate::schema::EventPayload::FilesDropped(paths) => {
            (EVENT_FILES_DROPPED, 24, Some(*paths), None)
        }
        crate::schema::EventPayload::StorageLocation(path) => {
            (EVENT_STORAGE_LOCATION, 24, Some(*path), None)
        }
        crate::schema::EventPayload::Resync => (EVENT_RESYNC, 16, None, None),
        crate::schema::EventPayload::WindowCloseRequested => {
            (EVENT_WINDOW_CLOSE_REQUESTED, 16, None, None)
        }
        crate::schema::EventPayload::WindowUnavailable => {
            (EVENT_WINDOW_UNAVAILABLE, 16, None, None)
        }
        crate::schema::EventPayload::WindowClosed => (EVENT_WINDOW_CLOSED, 16, None, None),
        crate::schema::EventPayload::ApplicationReopen => {
            (EVENT_APPLICATION_REOPEN, 16, None, None)
        }
        // The shape of a protocol error: one word, then one string.
        crate::schema::EventPayload::WindowRequested { key, request } => {
            (EVENT_WINDOW_REQUESTED, 28, Some(*key), Some(*request))
        }
        crate::schema::EventPayload::LifecycleStart => (EVENT_LIFECYCLE_START, 16, None, None),
        crate::schema::EventPayload::LifecycleStop => (EVENT_LIFECYCLE_STOP, 16, None, None),
        crate::schema::EventPayload::ProtocolError { code, message } => {
            (EVENT_PROTOCOL_ERROR, 28, Some(*message), Some(*code))
        }
        // The same shape as a protocol error: one word, then one string.
        crate::schema::EventPayload::NotificationActivated { action, key } => {
            (EVENT_NOTIFICATION_ACTIVATED, 28, Some(*key), Some(*action))
        }
        crate::schema::EventPayload::KeyDown { .. }
        | crate::schema::EventPayload::KeyUp { .. }
        | crate::schema::EventPayload::RangeRequested { .. }
        | crate::schema::EventPayload::ValueChanged(_)
        | crate::schema::EventPayload::WindowSizeChanged { .. }
        | crate::schema::EventPayload::DesignSystemResolved(_)
        | crate::schema::EventPayload::NotificationPermissionChanged(_)
        | crate::schema::EventPayload::CodeChanged { .. }
        | crate::schema::EventPayload::CodeEditRejected { .. }
        | crate::schema::EventPayload::CodeHovered { .. }
        | crate::schema::EventPayload::CodeSaveRequested { .. }
        | crate::schema::EventPayload::DecorationActivated { .. }
        | crate::schema::EventPayload::SelectionChanged { .. }
        | crate::schema::EventPayload::VisibleRangeChanged { .. }
        | crate::schema::EventPayload::EditorFocusChanged { .. }
        | crate::schema::EventPayload::FoldChanged { .. }
        | crate::schema::EventPayload::CompositionChanged { .. }
        | crate::schema::EventPayload::WindowFocusChanged { .. }
        | crate::schema::EventPayload::WindowMoved { .. }
        | crate::schema::EventPayload::WindowDrag { .. }
        | crate::schema::EventPayload::WindowDragOver { .. }
        | crate::schema::EventPayload::CodeMetrics { .. }
        | crate::schema::EventPayload::CodeScrolled { .. }
        | crate::schema::EventPayload::FileDialogResult { .. }
        | crate::schema::EventPayload::ClipboardText { .. }
        | crate::schema::EventPayload::IoAnswer { .. }
        | crate::schema::EventPayload::FileHandles { .. }
        | crate::schema::EventPayload::ScrollChanged { .. }
        | crate::schema::EventPayload::NativeViewParent { .. }
        | crate::schema::EventPayload::NativeViewFocus { .. } => unreachable!(),
        crate::schema::EventPayload::AnimationEvent { .. }
        | crate::schema::EventPayload::Pointer { .. }
        | crate::schema::EventPayload::ReducedMotionChanged(_)
        | crate::schema::EventPayload::ZoomChanged { .. } => unreachable!(),
    };
    output.extend_from_slice(&tag.to_le_bytes());
    output.extend_from_slice(&record_len.to_le_bytes());
    output.extend_from_slice(&event.node_id.to_le_bytes());
    output.extend_from_slice(&event.handler_id.to_le_bytes());
    if let Some(code) = error_code {
        output.extend_from_slice(&code.to_le_bytes());
    }
    if let Some(text) = text {
        let offset = u32::from(record_len);
        let len = u32::try_from(text.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        output.extend_from_slice(&offset.to_le_bytes());
        output.extend_from_slice(&len.to_le_bytes());
        output.extend_from_slice(text.as_bytes());
    }
    Ok(())
}

/// The sixteen bytes every event starts with: the tag, the record's length, the node and
/// the handler.
fn put_event_header(output: &mut Vec<u8>, tag: u16, record_len: u16, event: &HostEvent<'_>) {
    output.extend_from_slice(&tag.to_le_bytes());
    output.extend_from_slice(&record_len.to_le_bytes());
    output.extend_from_slice(&event.node_id.to_le_bytes());
    output.extend_from_slice(&event.handler_id.to_le_bytes());
}

impl fmt::Display for ProtocolError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "protocol error: {self:?}")
    }
}

impl std::error::Error for ProtocolError {}

/// Reusable encoder. Once capacities are warmed, `clear` and `finish` allocate no memory.
pub struct BatchEncoder {
    records: Vec<u8>,
    strings: Vec<u8>,
    string_fixups: Vec<usize>,
    arena: Vec<u8>,
    record_count: u32,
}

impl Default for BatchEncoder {
    fn default() -> Self {
        Self::with_capacity(4096, 1024, 64)
    }
}

impl BatchEncoder {
    pub fn with_capacity(record_bytes: usize, string_bytes: usize, string_count: usize) -> Self {
        let mut encoder = Self {
            records: Vec::with_capacity(record_bytes),
            strings: Vec::with_capacity(string_bytes),
            string_fixups: Vec::with_capacity(string_count),
            arena: Vec::with_capacity(record_bytes + string_bytes),
            record_count: 0,
        };
        encoder.clear();
        encoder
    }

    pub fn clear(&mut self) {
        self.records.clear();
        self.strings.clear();
        self.string_fixups.clear();
        self.arena.clear();
        self.record_count = 0;
        self.begin_record(TAG_ENVELOPE, 8);
        self.put_u32(0);
        self.put_u32(0);
    }

    pub fn encode(&mut self, mutation: &Mutation<'_>) -> Result<(), ProtocolError> {
        match mutation {
            Mutation::Create { node_id, widget } => {
                self.begin_record(TAG_CREATE, 8);
                self.put_u32(*node_id);
                self.put_u16(*widget as u16);
                self.put_u16(0);
            }
            Mutation::SetProp {
                node_id,
                property,
                value,
            } => {
                self.begin_record(TAG_SET_PROP, 20);
                self.put_u32(*node_id);
                self.put_u16(*property as u16);
                match value {
                    PropertyValue::None => {
                        self.put_u16(VALUE_NONE);
                        self.put_u64(0);
                        self.put_u32(0);
                    }
                    PropertyValue::String(value) => {
                        self.put_u16(VALUE_STRING);
                        self.put_string_ref(value)?;
                        self.put_u32(0);
                    }
                    PropertyValue::Bool(value) => {
                        self.put_u16(VALUE_BOOL);
                        self.put_u64(u64::from(*value));
                        self.put_u32(0);
                    }
                    PropertyValue::Integer(value) => {
                        self.put_u16(VALUE_I64);
                        self.put_u64(*value as u64);
                        self.put_u32(0);
                    }
                    PropertyValue::Float(value) => {
                        self.put_u16(VALUE_F32);
                        self.put_u64(u64::from(value.to_bits()));
                        self.put_u32(0);
                    }
                    PropertyValue::Bytes(value) => {
                        self.put_u16(VALUE_BYTES);
                        self.put_bytes_ref(value)?;
                        self.put_u32(0);
                    }
                }
            }
            Mutation::SetModifier {
                node_id,
                index,
                modifier,
            } => {
                let (tag, words) = modifier_fields(modifier);
                // The tag fixes how long the value is, so the record is as long as the
                // schema says that tag's records are and every other modifier keeps the
                // 28 bytes it always had.
                let extra = modifier_extra_words(tag).ok_or(ProtocolError::InvalidModifier(tag))?;
                self.begin_record(TAG_SET_MODIFIER, 24 + 8 * u16::from(extra));
                self.put_u32(*node_id);
                self.put_u16(*index);
                self.put_u16(tag);
                for word in &words[..2 + usize::from(extra)] {
                    self.put_u64(*word);
                }
            }
            Mutation::Insert {
                parent_id,
                node_id,
                index,
            } => {
                self.begin_record(TAG_INSERT, 12);
                self.put_u32(*parent_id);
                self.put_u32(*node_id);
                self.put_u32(*index);
            }
            Mutation::Move {
                parent_id,
                node_id,
                index,
            } => {
                self.begin_record(TAG_MOVE, 12);
                self.put_u32(*parent_id);
                self.put_u32(*node_id);
                self.put_u32(*index);
            }
            Mutation::Remove { node_id } => {
                self.begin_record(TAG_REMOVE, 4);
                self.put_u32(*node_id);
            }
            Mutation::SetText {
                node_id,
                text,
                selection,
            } => {
                self.begin_record(TAG_SET_TEXT, 20);
                self.put_u32(*node_id);
                self.put_string_ref(text)?;
                let (start, end) =
                    selection.map_or((u32::MAX, u32::MAX), |value| (value.start, value.end));
                self.put_u32(start);
                self.put_u32(end);
            }
            Mutation::AppendText { node_id, text } => {
                self.begin_record(TAG_APPEND_TEXT, 12);
                self.put_u32(*node_id);
                self.put_string_ref(text)?;
            }
            Mutation::RegisterAsset {
                asset_id,
                kind,
                bytes,
            } => {
                self.begin_record(TAG_REGISTER_ASSET, 16);
                self.put_u32(*asset_id);
                self.put_u16(*kind as u16);
                self.put_u16(0);
                self.put_bytes_ref(bytes)?;
            }
            Mutation::ReleaseAsset { asset_id } => {
                self.begin_record(TAG_RELEASE_ASSET, 4);
                self.put_u32(*asset_id);
            }
            Mutation::ShowMessage {
                handler_id,
                text,
                action,
                duration,
            } => {
                self.begin_record(TAG_SHOW_MESSAGE, 28);
                self.put_u64(*handler_id);
                self.put_string_ref(text)?;
                self.put_string_ref(action)?;
                self.put_u16(*duration as u16);
                self.put_u16(0);
            }
            Mutation::EditCode {
                node_id,
                request_id,
                base_version,
                start_line,
                start_column,
                end_line,
                end_column,
                text,
            } => {
                self.begin_record(TAG_EDIT_CODE, 36);
                self.put_u32(*node_id);
                self.put_u32(*request_id);
                self.put_u32(*base_version);
                self.put_u32(*start_line);
                self.put_u32(*start_column);
                self.put_u32(*end_line);
                self.put_u32(*end_column);
                self.put_string_ref(text)?;
            }
            Mutation::SetSelection {
                node_id,
                base_version,
                anchor_line,
                anchor_column,
                active_line,
                active_column,
            } => {
                self.begin_record(TAG_SET_SELECTION, 24);
                self.put_u32(*node_id);
                self.put_u32(*base_version);
                self.put_u32(*anchor_line);
                self.put_u32(*anchor_column);
                self.put_u32(*active_line);
                self.put_u32(*active_column);
            }
            Mutation::RevealRange {
                node_id,
                base_version,
                start_line,
                start_column,
                end_line,
                end_column,
                align,
            } => {
                self.begin_record(TAG_REVEAL_RANGE, 28);
                self.put_u32(*node_id);
                self.put_u32(*base_version);
                self.put_u32(*start_line);
                self.put_u32(*start_column);
                self.put_u32(*end_line);
                self.put_u32(*end_column);
                self.put_u32(u32::from(*align as u16));
            }
            Mutation::SetFolded {
                node_id,
                start_line,
                collapsed,
            } => {
                self.begin_record(TAG_SET_FOLDED, 12);
                self.put_u32(*node_id);
                self.put_u32(*start_line);
                self.put_u32(u32::from(*collapsed));
            }
            Mutation::SetTheme(theme) => {
                self.begin_record(TAG_SET_THEME, THEME_PAYLOAD_LEN as u16);
                self.put_theme(theme)?;
            }
            Mutation::SetWindowTheme { node_id, theme } => {
                self.begin_record(TAG_SET_WINDOW_THEME, 4 + THEME_PAYLOAD_LEN as u16);
                self.put_u32(*node_id);
                self.put_theme(theme)?;
            }
            Mutation::FocusWindow { node_id } => {
                self.begin_record(TAG_FOCUS_WINDOW, 4);
                self.put_u32(*node_id);
            }
            Mutation::RequestFocus { node_id } => {
                self.begin_record(TAG_REQUEST_FOCUS, 4);
                self.put_u32(*node_id);
            }
            Mutation::SetScrollOffset { node_id, offset_px } => {
                self.begin_record(TAG_SET_SCROLL_OFFSET, 12);
                self.put_u32(*node_id);
                self.put_u64(offset_px.to_bits());
            }
            Mutation::ShowFileDialog {
                request,
                window,
                kind,
                default_filter,
                title,
                directory,
                name,
                filters,
            } => {
                self.begin_record(TAG_SHOW_FILE_DIALOG, SHOW_FILE_DIALOG_PAYLOAD_LEN as u16);
                self.put_u32(*request);
                self.put_u32(*window);
                self.put_u16(*kind as u16);
                self.put_u16(*default_filter);
                self.put_string_ref(title)?;
                self.put_string_ref(directory)?;
                self.put_string_ref(name)?;
                self.put_string_ref(filters)?;
            }
            Mutation::SetOverlayTokens(overlay) => {
                self.begin_record(TAG_SET_OVERLAY_TOKENS, OVERLAY_TOKENS_PAYLOAD_LEN as u16);
                self.put_u16(overlay.sheet_corner_radius);
                self.put_u16(overlay.dialog_corner_radius);
                self.put_u16(overlay.popup_corner_radius);
                self.put_u16(overlay.sheet_elevation);
                self.put_u16(overlay.popup_elevation);
                self.put_u16(overlay.dialog_elevation);
                self.put_u16(overlay.sheet_padding);
                self.put_u16(overlay.dialog_padding);
                self.put_u16(overlay.popup_padding);
                self.put_u16(0);
                self.put_u32(overlay.scrim);
            }
            Mutation::SetClipboardText { text } => {
                self.begin_record(TAG_SET_CLIPBOARD_TEXT, 8);
                self.put_string_ref(text)?;
            }
            Mutation::ReadClipboard { request } => {
                self.begin_record(TAG_READ_CLIPBOARD, 4);
                self.put_u32(*request);
            }
            Mutation::StorageCall {
                request,
                op,
                store,
                key,
                data,
            } => {
                self.begin_record(TAG_STORAGE_CALL, STORAGE_CALL_PAYLOAD_LEN as u16);
                self.put_u32(*request);
                self.put_u16(u16::from(*op));
                self.put_u16(0);
                self.put_string_ref(store)?;
                self.put_string_ref(key)?;
                self.put_bytes_ref(data)?;
            }
            Mutation::FileCall {
                request,
                op,
                create,
                handle,
                name,
                data,
            } => {
                self.begin_record(TAG_FILE_CALL, FILE_CALL_PAYLOAD_LEN as u16);
                self.put_u32(*request);
                self.put_u16(*op as u16);
                self.put_u16(u16::from(*create));
                self.put_u32(*handle);
                self.put_string_ref(name)?;
                self.put_bytes_ref(data)?;
            }
            Mutation::ReleaseFileHandle { handle } => {
                self.begin_record(TAG_RELEASE_FILE_HANDLE, 4);
                self.put_u32(*handle);
            }
            Mutation::ScrollTo {
                node_id,
                item,
                flags,
                x_px,
                y_px,
            } => {
                self.begin_record(TAG_SCROLL_TO, (SCROLL_TO_LEN - 4) as u16);
                self.put_u32(*node_id);
                self.put_u32(*item);
                self.put_u32(*flags);
                self.put_u64(x_px.to_bits());
                self.put_u64(y_px.to_bits());
            }
            Mutation::ScrollIntoView {
                node_id,
                target,
                target_kind,
                align_x,
                align_y,
                flags,
                rect,
            } => {
                self.begin_record(TAG_SCROLL_INTO_VIEW, (SCROLL_INTO_VIEW_LEN - 4) as u16);
                self.put_u32(*node_id);
                self.put_u32(*target);
                self.put_u16(*target_kind);
                self.put_u16(*align_x);
                self.put_u16(*align_y);
                self.put_u16(*flags);
                self.put_u64(rect.x_px.to_bits());
                self.put_u64(rect.y_px.to_bits());
                self.put_u64(rect.width_px.to_bits());
                self.put_u64(rect.height_px.to_bits());
            }
            Mutation::AttachNativeView { node_id, handle } => {
                self.begin_record(TAG_ATTACH_NATIVE_VIEW, (ATTACH_NATIVE_VIEW_LEN - 4) as u16);
                self.put_u32(*node_id);
                self.put_u32(0);
                self.put_u64(handle.0);
                self.put_u32(0);
            }
            Mutation::FocusNativeView { node_id, focus } => {
                self.begin_record(TAG_FOCUS_NATIVE_VIEW, (FOCUS_NATIVE_VIEW_LEN - 4) as u16);
                self.put_u32(*node_id);
                self.put_u32(u32::from(*focus));
                self.put_u32(0);
            }
            Mutation::SetWindow(window) => {
                self.begin_record(TAG_SET_WINDOW, 28);
                self.put_u16(window.chrome as u16);
                self.put_u16(window.title_bar as u16);
                self.put_u16(window.width);
                self.put_u16(window.height);
                self.put_u16(window.min_width);
                self.put_u16(window.min_height);
                // Bit 0 is `resizable` and bit 1 asks for every whole-dp size change. The
                // mode and the last window policy ride in the bits above them that were
                // always zero, so a window that asks for none writes the bytes it always
                // wrote.
                self.put_u16(
                    u16::from(window.resizable)
                        | (u16::from(window.report_size) << 1)
                        | ((window.mode as u16) << WINDOW_MODE_SHIFT)
                        | ((window.last_window_closed as u16) << WINDOW_POLICY_SHIFT),
                );
                // The application's zoom level, in what used to be the padding that keeps
                // the record a multiple of four: a byte saying whether there is one, then
                // the level as a signed byte. Zero is "none", so a window that asks for
                // nothing reads exactly as it did before the level existed.
                self.put_u16(match window.zoom_level {
                    None => 0,
                    Some(level) => 1 | (u16::from(crate::zoom::clamp_level(level) as u8) << 8),
                });
                self.put_string_ref(window.title)?;
                self.put_u32(window.icon);
            }
            Mutation::PostNotification {
                key,
                title,
                body,
                channel,
                action_1,
                action_2,
                importance,
                presentation,
            } => {
                self.begin_record(TAG_POST_NOTIFICATION, 52);
                self.put_string_ref(key)?;
                self.put_string_ref(title)?;
                self.put_string_ref(body)?;
                self.put_string_ref(channel)?;
                self.put_string_ref(action_1)?;
                self.put_string_ref(action_2)?;
                self.put_u16(*importance as u16);
                self.put_u16(*presentation as u16);
            }
            Mutation::WithdrawNotification { key } => {
                self.begin_record(TAG_WITHDRAW_NOTIFICATION, 8);
                self.put_string_ref(key)?;
            }
            Mutation::RequestNotificationPermission => {
                self.begin_record(TAG_REQUEST_NOTIFICATION_PERMISSION, 0);
            }
            Mutation::StartAnimation(animation) => self.put_animation(animation)?,
            Mutation::ControlAnimation {
                node_id,
                animation_id,
                property,
                slot,
                op,
                at_time_nanos,
            } => {
                self.begin_record(TAG_CONTROL_ANIMATION, 20);
                self.put_u32(*node_id);
                self.put_u32(*animation_id);
                self.records.push(*property as u8);
                self.records.push(*slot);
                self.records.push(*op as u8);
                self.records.push(0);
                self.put_u64(*at_time_nanos);
            }
        }
        self.record_count = self
            .record_count
            .checked_add(1)
            .ok_or(ProtocolError::LengthOverflow)?;
        Ok(())
    }

    pub fn finish(&mut self) -> Result<&[u8], ProtocolError> {
        let records_len =
            u32::try_from(self.records.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        self.records[4..8].copy_from_slice(&records_len.to_le_bytes());
        self.records[8..12].copy_from_slice(&self.record_count.to_le_bytes());
        let total = self
            .records
            .len()
            .checked_add(self.strings.len())
            .ok_or(ProtocolError::LengthOverflow)?;
        self.arena.clear();
        if self.arena.capacity() < total {
            self.arena.reserve(total);
        }
        self.arena.extend_from_slice(&self.records);
        self.arena.extend_from_slice(&self.strings);
        for &position in &self.string_fixups {
            let relative = read_u32(&self.arena, position)?;
            let absolute = records_len
                .checked_add(relative)
                .ok_or(ProtocolError::LengthOverflow)?;
            self.arena[position..position + 4].copy_from_slice(&absolute.to_le_bytes());
        }
        Ok(&self.arena)
    }

    /// Where the arena a finished batch lives in starts, and how much of it is mapped.
    ///
    /// A runtime that cannot read the Host's memory directly wraps this range once and
    /// keeps the view: every batch is a prefix of the arena, so it is read where it lies
    /// and no byte is copied out. Growing the arena moves it, which is why the address is
    /// reported again on every call rather than once at startup.
    pub fn arena(&self) -> (*const u8, usize) {
        (self.arena.as_ptr(), self.arena.capacity())
    }

    pub fn capacity(&self) -> usize {
        self.records.capacity() + self.strings.capacity() + self.arena.capacity()
    }

    /// Writes a `StartAnimation`: the 52 byte head, then each keyframe with its value.
    ///
    /// The bytes are written as they are given; whether they make an animation is the
    /// decoder's question, asked on both sides, so a Host that sends a bad one hears about
    /// it as a protocol error rather than not at all.
    fn put_animation(
        &mut self,
        animation: &crate::schema::Animation<'_>,
    ) -> Result<(), ProtocolError> {
        use crate::schema::{KeyframeValue, Timing};
        let keyframes_len: usize = animation
            .keyframes
            .iter()
            .map(|keyframe| {
                KEYFRAME_HEAD_LEN
                    + match &keyframe.value {
                        KeyframeValue::Alpha(_) => 4,
                        KeyframeValue::Paint(_) => 8,
                        KeyframeValue::Transform(functions) => {
                            4 + TRANSFORM_FUNCTION_LEN * functions.len()
                        }
                    }
            })
            .sum();
        let payload = u16::try_from(ANIMATION_HEADER_LEN - 4 + keyframes_len)
            .ok()
            .filter(|payload| *payload <= u16::MAX - 4)
            .ok_or(ProtocolError::LengthOverflow)?;
        let keyframe_count =
            u16::try_from(animation.keyframes.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        self.begin_record(TAG_START_ANIMATION, payload);
        self.put_u32(animation.node_id);
        self.put_u32(animation.animation_id);
        self.records.push(animation.property as u8);
        self.records.push(animation.slot);
        self.records.push(animation.direction as u8);
        self.records.push(animation.fill as u8);
        self.records.push(animation.play_state as u8);
        self.records
            .push(animation.interpolation.map_or(0, |value| value as u8));
        self.put_u16(keyframe_count);
        self.put_u64(animation.start_time_nanos);
        self.put_u32(animation.delay_ms.to_bits());
        self.put_u32(animation.duration_ms.to_bits());
        self.put_u32(animation.iterations.to_bits());
        self.put_u32(animation.origin_x.to_bits());
        self.put_u32(animation.origin_y.to_bits());
        self.records.push(animation.events.0);
        self.records.extend_from_slice(&[0, 0, 0]);
        for keyframe in animation.keyframes.iter() {
            self.put_u32(keyframe.offset.to_bits());
            let (kind, position, numbers) = match keyframe.timing {
                Timing::Linear => (1_u8, 0_u8, [0_u32; 4]),
                Timing::CubicBezier { x1, y1, x2, y2 } => (
                    2,
                    0,
                    [x1.to_bits(), y1.to_bits(), x2.to_bits(), y2.to_bits()],
                ),
                Timing::Steps { count, position } => (3, position as u8, [count, 0, 0, 0]),
            };
            self.records.push(kind);
            self.records.push(position);
            self.put_u16(if keyframe.from_presented {
                KEYFRAME_FROM_PRESENTED
            } else {
                0
            });
            for number in numbers {
                self.put_u32(number);
            }
            match &keyframe.value {
                KeyframeValue::Alpha(value) => self.put_u32(value.to_bits()),
                KeyframeValue::Paint(paint) => self.put_u64(paint.to_bits()),
                KeyframeValue::Transform(functions) => {
                    let count = u16::try_from(functions.len())
                        .map_err(|_| ProtocolError::LengthOverflow)?;
                    self.put_u16(count);
                    self.put_u16(0);
                    for function in functions.iter() {
                        self.put_u32(u32::from(u16::from(function.kind)));
                        for value in function.values {
                            self.put_u32(value.to_bits());
                        }
                    }
                }
            }
        }
        Ok(())
    }

    /// The body of a `SetTheme`, which `SetWindowTheme` carries after its node id.
    fn put_theme(&mut self, theme: &Theme) -> Result<(), ProtocolError> {
        self.put_u16(theme.design_system as u16);
        self.put_u16(theme.fallback as u16);
        self.put_u16(theme.color_scheme as u16);
        self.put_u16(u16::from(theme.adaptive));
        // One slot per type role, zero where the role keeps the system font. Sent
        // whether or not any of them is filled, because the record is fixed
        // layout and a theme change is one record rather than a per frame cost.
        for asset in theme.fonts {
            self.put_u32(asset);
        }
        // The application's colours, as a run of eight byte entries behind the
        // records the way a string is. Zero long where there is no palette, so a
        // theme without one costs the eight bytes of the reference and nothing
        // else.
        self.put_palette_ref(theme.palette)
    }

    fn begin_record(&mut self, tag: u16, payload_len: u16) {
        self.put_u16(tag);
        self.put_u16(payload_len + 4);
    }

    /// Writes a palette's entries straight into the trailing region, so encoding a theme
    /// allocates nothing beyond what that region already has.
    fn put_palette_ref(
        &mut self,
        palette: Option<&crate::palette::Palette>,
    ) -> Result<(), ProtocolError> {
        let start = self.strings.len();
        let offset = u32::try_from(start).map_err(|_| ProtocolError::LengthOverflow)?;
        if let Some(palette) = palette {
            palette.write_into(&mut self.strings);
        }
        let len =
            u32::try_from(self.strings.len() - start).map_err(|_| ProtocolError::LengthOverflow)?;
        self.string_fixups.push(self.records.len());
        self.put_u32(offset);
        self.put_u32(len);
        Ok(())
    }

    fn put_string_ref(&mut self, value: &str) -> Result<(), ProtocolError> {
        self.put_bytes_ref(value.as_bytes())
    }

    /// Asset bytes ride in the same trailing region as strings, with the same
    /// `(offset, len)` reference and the same fixup, so the record stays fixed layout.
    fn put_bytes_ref(&mut self, value: &[u8]) -> Result<(), ProtocolError> {
        let offset =
            u32::try_from(self.strings.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        let len = u32::try_from(value.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        self.string_fixups.push(self.records.len());
        self.put_u32(offset);
        self.put_u32(len);
        self.strings.extend_from_slice(value);
        Ok(())
    }

    fn put_u16(&mut self, value: u16) {
        self.records.extend_from_slice(&value.to_le_bytes());
    }

    fn put_u32(&mut self, value: u32) {
        self.records.extend_from_slice(&value.to_le_bytes());
    }

    fn put_u64(&mut self, value: u64) {
        self.records.extend_from_slice(&value.to_le_bytes());
    }
}

/// Reads the body of a `SetTheme` that starts at `payload`, which `SetWindowTheme` also
/// carries, after its node id.
fn decode_theme(bytes: &[u8], payload: usize, records_len: usize) -> Result<Theme, ProtocolError> {
    let design_system = read_u16(bytes, payload)?;
    let fallback = read_u16(bytes, payload + 2)?;
    let color_scheme = read_u16(bytes, payload + 4)?;
    let adaptive = read_u16(bytes, payload + 6)?;
    if adaptive > 1 {
        return Err(ProtocolError::InvalidTheme(adaptive));
    }
    let mut fonts = [0u32; TYPE_ROLE_COUNT];
    for (index, slot) in fonts.iter_mut().enumerate() {
        *slot = read_u32(bytes, payload + 8 + 4 * index)?;
    }
    // Entries that are wrong are left out and the rest stand, which is what
    // the Renderer does with them as well. Leaked like the window's title: a
    // theme holds its palette for the life of the program, and this decoder
    // is what tests and the mock renderer read a batch back with.
    let palette_bytes = read_bytes(bytes, payload + 8 + 4 * TYPE_ROLE_COUNT, records_len)?;
    let palette = if palette_bytes.is_empty() {
        None
    } else {
        let (palette, _problems) = crate::palette::decode_palette(palette_bytes);
        Some(&*Box::leak(Box::new(palette)))
    };
    Ok(Theme {
        design_system: DesignSystem::try_from(design_system)
            .map_err(|()| ProtocolError::InvalidTheme(design_system))?,
        fallback: DesignSystem::try_from(fallback)
            .map_err(|()| ProtocolError::InvalidTheme(fallback))?,
        color_scheme: ColorScheme::try_from(color_scheme)
            .map_err(|()| ProtocolError::InvalidTheme(color_scheme))?,
        adaptive: adaptive == 1,
        fonts,
        palette,
        overlay: None,
    })
}

pub fn decode_batch(bytes: &[u8]) -> Result<Vec<Mutation<'_>>, ProtocolError> {
    if bytes.len() < ENVELOPE_LEN
        || read_u16(bytes, 0)? != TAG_ENVELOPE
        || read_u16(bytes, 2)? != 12
    {
        return Err(ProtocolError::InvalidEnvelope);
    }
    let records_len =
        usize::try_from(read_u32(bytes, 4)?).map_err(|_| ProtocolError::LengthOverflow)?;
    let count = usize::try_from(read_u32(bytes, 8)?).map_err(|_| ProtocolError::LengthOverflow)?;
    if records_len < ENVELOPE_LEN || records_len > bytes.len() || records_len % 4 != 0 {
        return Err(ProtocolError::InvalidEnvelope);
    }
    // `count` is attacker-controlled, so it only sizes the buffer up to what the
    // record region could actually hold. The shortest mutation record is a bare header at
    // 4 bytes, so that is the ceiling. Reserving `count` directly let a 12-byte message ask
    // for a >100 GB allocation.
    let capacity = count.min((records_len - ENVELOPE_LEN) / MIN_RECORD_LEN);
    let mut output = Vec::with_capacity(capacity);
    let mut position = ENVELOPE_LEN;
    while position < records_len {
        let tag = read_u16(bytes, position)?;
        let len = usize::from(read_u16(bytes, position + 2)?);
        if len < 4 || len % 4 != 0 || position + len > records_len {
            return Err(ProtocolError::InvalidRecordLength);
        }
        let payload = position + 4;
        let mutation = match tag {
            TAG_CREATE if len == 12 => Mutation::Create {
                node_id: read_u32(bytes, payload)?,
                widget: WidgetKind::try_from(read_u16(bytes, payload + 4)?).map_err(|()| {
                    ProtocolError::InvalidWidget(read_u16(bytes, payload + 4).unwrap_or(0))
                })?,
            },
            TAG_SET_PROP if len == 24 => {
                let property_raw = read_u16(bytes, payload + 4)?;
                let value_kind = read_u16(bytes, payload + 6)?;
                let value = match value_kind {
                    VALUE_NONE => PropertyValue::None,
                    VALUE_STRING => {
                        PropertyValue::String(read_string(bytes, payload + 8, records_len)?)
                    }
                    VALUE_BOOL => PropertyValue::Bool(read_u64(bytes, payload + 8)? != 0),
                    VALUE_I64 => PropertyValue::Integer(read_u64(bytes, payload + 8)? as i64),
                    VALUE_F32 => {
                        PropertyValue::Float(f32::from_bits(read_u64(bytes, payload + 8)? as u32))
                    }
                    VALUE_BYTES => {
                        PropertyValue::Bytes(read_bytes(bytes, payload + 8, records_len)?)
                    }
                    other => return Err(ProtocolError::InvalidValueKind(other)),
                };
                Mutation::SetProp {
                    node_id: read_u32(bytes, payload)?,
                    property: PropertyKind::try_from(property_raw)
                        .map_err(|()| ProtocolError::InvalidProperty(property_raw))?,
                    value,
                }
            }
            TAG_SET_MODIFIER if len >= 28 => {
                // The modifier's tag says how long its record is: 28 bytes and eight more
                // for each word the schema gives it beyond the first two. A record of any
                // other length is refused before its value is read.
                let modifier_tag = read_u16(bytes, payload + 6)?;
                let extra = usize::from(
                    modifier_extra_words(modifier_tag)
                        .ok_or(ProtocolError::InvalidModifier(modifier_tag))?,
                );
                if len != 28 + 8 * extra {
                    return Err(ProtocolError::InvalidRecordLength);
                }
                let mut words = [0_u64; MAX_MODIFIER_WORDS];
                for (word_index, word) in words.iter_mut().take(2 + extra).enumerate() {
                    *word = read_u64(bytes, payload + 8 + 8 * word_index)?;
                }
                Mutation::SetModifier {
                    node_id: read_u32(bytes, payload)?,
                    index: read_u16(bytes, payload + 4)?,
                    modifier: decode_modifier(modifier_tag, &words)?,
                }
            }
            TAG_INSERT if len == 16 => Mutation::Insert {
                parent_id: read_u32(bytes, payload)?,
                node_id: read_u32(bytes, payload + 4)?,
                index: read_u32(bytes, payload + 8)?,
            },
            TAG_MOVE if len == 16 => Mutation::Move {
                parent_id: read_u32(bytes, payload)?,
                node_id: read_u32(bytes, payload + 4)?,
                index: read_u32(bytes, payload + 8)?,
            },
            TAG_REMOVE if len == 8 => Mutation::Remove {
                node_id: read_u32(bytes, payload)?,
            },
            TAG_SET_TEXT if len == 24 => {
                let start = read_u32(bytes, payload + 12)?;
                let end = read_u32(bytes, payload + 16)?;
                Mutation::SetText {
                    node_id: read_u32(bytes, payload)?,
                    text: read_string(bytes, payload + 4, records_len)?,
                    selection: (start != u32::MAX || end != u32::MAX)
                        .then_some(Selection { start, end }),
                }
            }
            TAG_APPEND_TEXT if len == 16 => Mutation::AppendText {
                node_id: read_u32(bytes, payload)?,
                text: read_string(bytes, payload + 4, records_len)?,
            },
            TAG_SET_THEME if len == 4 + THEME_PAYLOAD_LEN => {
                Mutation::SetTheme(decode_theme(bytes, payload, records_len)?)
            }
            TAG_SET_WINDOW_THEME if len == 8 + THEME_PAYLOAD_LEN => Mutation::SetWindowTheme {
                node_id: read_u32(bytes, payload)?,
                theme: decode_theme(bytes, payload + 4, records_len)?,
            },
            TAG_FOCUS_WINDOW if len == 8 => Mutation::FocusWindow {
                node_id: read_u32(bytes, payload)?,
            },
            TAG_REQUEST_FOCUS if len == 8 => Mutation::RequestFocus {
                node_id: read_u32(bytes, payload)?,
            },
            TAG_SHOW_FILE_DIALOG if len == 4 + SHOW_FILE_DIALOG_PAYLOAD_LEN => {
                let raw_kind = read_u16(bytes, payload + 8)?;
                Mutation::ShowFileDialog {
                    request: read_u32(bytes, payload)?,
                    window: read_u32(bytes, payload + 4)?,
                    kind: FileDialogKind::try_from(raw_kind)
                        .map_err(|()| ProtocolError::InvalidValueKind(raw_kind))?,
                    default_filter: read_u16(bytes, payload + 10)?,
                    title: read_string(bytes, payload + 12, records_len)?,
                    directory: read_string(bytes, payload + 20, records_len)?,
                    name: read_string(bytes, payload + 28, records_len)?,
                    filters: read_string(bytes, payload + 36, records_len)?,
                }
            }
            TAG_SHOW_FILE_DIALOG => return Err(ProtocolError::InvalidRecordLength),
            TAG_SET_OVERLAY_TOKENS if len == 4 + OVERLAY_TOKENS_PAYLOAD_LEN => {
                let sheet_corner_radius = read_u16(bytes, payload)?;
                let dialog_corner_radius = read_u16(bytes, payload + 2)?;
                let popup_corner_radius = read_u16(bytes, payload + 4)?;
                let sheet_elevation = read_u16(bytes, payload + 6)?;
                let popup_elevation = read_u16(bytes, payload + 8)?;
                let dialog_elevation = read_u16(bytes, payload + 10)?;
                let sheet_padding = read_u16(bytes, payload + 12)?;
                let dialog_padding = read_u16(bytes, payload + 14)?;
                let popup_padding = read_u16(bytes, payload + 16)?;
                let scrim = read_u32(bytes, payload + 20)?;
                Mutation::SetOverlayTokens(crate::schema::OverlayTokens {
                    sheet_corner_radius,
                    dialog_corner_radius,
                    popup_corner_radius,
                    sheet_elevation,
                    popup_elevation,
                    dialog_elevation,
                    sheet_padding,
                    dialog_padding,
                    popup_padding,
                    scrim,
                })
            }
            TAG_SET_OVERLAY_TOKENS => return Err(ProtocolError::InvalidRecordLength),
            TAG_SET_CLIPBOARD_TEXT if len == 12 => Mutation::SetClipboardText {
                text: read_string(bytes, payload, records_len)?,
            },
            TAG_READ_CLIPBOARD if len == 8 => Mutation::ReadClipboard {
                request: read_u32(bytes, payload)?,
            },
            TAG_STORAGE_CALL if len == 4 + STORAGE_CALL_PAYLOAD_LEN => {
                let raw_op = read_u16(bytes, payload + 4)?;
                Mutation::StorageCall {
                    request: read_u32(bytes, payload)?,
                    op: crate::schema::StorageOp::try_from(raw_op)
                        .map_err(|()| ProtocolError::InvalidValueKind(raw_op))?,
                    store: read_string(bytes, payload + 8, records_len)?,
                    key: read_string(bytes, payload + 16, records_len)?,
                    data: read_bytes(bytes, payload + 24, records_len)?,
                }
            }
            TAG_FILE_CALL if len == 4 + FILE_CALL_PAYLOAD_LEN => {
                let raw_op = read_u16(bytes, payload + 4)?;
                let raw_create = read_u16(bytes, payload + 6)?;
                if raw_create > 1 {
                    return Err(ProtocolError::InvalidValueKind(raw_create));
                }
                Mutation::FileCall {
                    request: read_u32(bytes, payload)?,
                    op: FileOp::try_from(raw_op)
                        .map_err(|()| ProtocolError::InvalidValueKind(raw_op))?,
                    create: raw_create == 1,
                    handle: read_u32(bytes, payload + 8)?,
                    name: read_string(bytes, payload + 12, records_len)?,
                    data: read_bytes(bytes, payload + 20, records_len)?,
                }
            }
            TAG_RELEASE_FILE_HANDLE if len == 8 => Mutation::ReleaseFileHandle {
                handle: read_u32(bytes, payload)?,
            },
            TAG_SCROLL_TO if len == SCROLL_TO_LEN => Mutation::ScrollTo {
                node_id: read_u32(bytes, payload)?,
                item: read_u32(bytes, payload + 4)?,
                flags: read_u32(bytes, payload + 8)?,
                x_px: f64::from_bits(read_u64(bytes, payload + 12)?),
                y_px: f64::from_bits(read_u64(bytes, payload + 20)?),
            },
            TAG_SCROLL_INTO_VIEW if len == SCROLL_INTO_VIEW_LEN => Mutation::ScrollIntoView {
                node_id: read_u32(bytes, payload)?,
                target: read_u32(bytes, payload + 4)?,
                target_kind: read_u16(bytes, payload + 8)?,
                align_x: read_u16(bytes, payload + 10)?,
                align_y: read_u16(bytes, payload + 12)?,
                flags: read_u16(bytes, payload + 14)?,
                rect: crate::schema::Rect {
                    x_px: f64::from_bits(read_u64(bytes, payload + 16)?),
                    y_px: f64::from_bits(read_u64(bytes, payload + 24)?),
                    width_px: f64::from_bits(read_u64(bytes, payload + 32)?),
                    height_px: f64::from_bits(read_u64(bytes, payload + 40)?),
                },
            },
            TAG_ATTACH_NATIVE_VIEW if len == ATTACH_NATIVE_VIEW_LEN => {
                require_zero_word(bytes, payload + 4)?;
                require_zero_word(bytes, payload + 16)?;
                Mutation::AttachNativeView {
                    node_id: read_u32(bytes, payload)?,
                    handle: crate::schema::NativeHandle(read_u64(bytes, payload + 8)?),
                }
            }
            TAG_FOCUS_NATIVE_VIEW if len == FOCUS_NATIVE_VIEW_LEN => {
                require_zero_word(bytes, payload + 8)?;
                Mutation::FocusNativeView {
                    node_id: read_u32(bytes, payload)?,
                    focus: read_flag(bytes, payload + 4)?,
                }
            }
            TAG_SET_WINDOW if len == 32 => {
                let chrome = read_u16(bytes, payload)?;
                let title_bar = read_u16(bytes, payload + 2)?;
                // Bit 0 is `resizable`; the mode and the last window policy are the
                // nibbles above the second byte. Anything else standing is a record the
                // sides disagree on.
                let flags = read_u16(bytes, payload + 12)?;
                if flags & 0x00FC != 0 {
                    return Err(ProtocolError::InvalidValueKind(flags));
                }
                let resizable = flags & 1;
                let report_size = flags & 0x0002 != 0;
                let mode_tag = (flags >> WINDOW_MODE_SHIFT) & 0xF;
                let policy_tag = flags >> WINDOW_POLICY_SHIFT;
                let zoom = read_u16(bytes, payload + 14)?;
                let zoom_level = match zoom & 0xFF {
                    0 => None,
                    1 => {
                        let level = (zoom >> 8) as u8 as i8;
                        if !(crate::zoom::MIN_ZOOM_LEVEL..=crate::zoom::MAX_ZOOM_LEVEL)
                            .contains(&level)
                        {
                            return Err(ProtocolError::InvalidValueKind(zoom));
                        }
                        Some(level)
                    }
                    _ => return Err(ProtocolError::InvalidValueKind(zoom)),
                };
                Mutation::SetWindow(crate::schema::Window {
                    chrome: crate::schema::Chrome::try_from(chrome)
                        .map_err(|()| ProtocolError::InvalidValueKind(chrome))?,
                    title_bar: crate::schema::TitleBar::try_from(title_bar)
                        .map_err(|()| ProtocolError::InvalidValueKind(title_bar))?,
                    width: read_u16(bytes, payload + 4)?,
                    height: read_u16(bytes, payload + 6)?,
                    min_width: read_u16(bytes, payload + 8)?,
                    min_height: read_u16(bytes, payload + 10)?,
                    resizable: resizable == 1,
                    zoom_level,
                    report_size,
                    mode: crate::schema::WindowMode::try_from(mode_tag)
                        .map_err(|()| ProtocolError::InvalidValueKind(flags))?,
                    last_window_closed: crate::schema::LastWindowClosed::try_from(policy_tag)
                        .map_err(|()| ProtocolError::InvalidValueKind(flags))?,
                    // Leaked on purpose, once per window. The record's owner is the
                    // window, the window outlives the batch it arrived in, and there is
                    // one of these per process.
                    icon: read_u32(bytes, payload + 24)?,
                    title: Box::leak(
                        read_string(bytes, payload + 16, records_len)?
                            .to_owned()
                            .into_boxed_str(),
                    ),
                })
            }
            TAG_REGISTER_ASSET if len == 20 => {
                let raw_kind = read_u16(bytes, payload + 4)?;
                Mutation::RegisterAsset {
                    asset_id: read_u32(bytes, payload)?,
                    kind: AssetKind::try_from(raw_kind)
                        .map_err(|()| ProtocolError::InvalidAssetKind(raw_kind))?,
                    bytes: read_bytes(bytes, payload + 8, records_len)?,
                }
            }
            TAG_RELEASE_ASSET if len == 8 => Mutation::ReleaseAsset {
                asset_id: read_u32(bytes, payload)?,
            },
            TAG_SHOW_MESSAGE if len == 32 => {
                let raw_duration = read_u16(bytes, payload + 24)?;
                Mutation::ShowMessage {
                    handler_id: read_u64(bytes, payload)?,
                    text: read_string(bytes, payload + 8, records_len)?,
                    action: read_string(bytes, payload + 16, records_len)?,
                    duration: MessageDuration::try_from(raw_duration)
                        .map_err(|()| ProtocolError::InvalidMessageDuration(raw_duration))?,
                }
            }
            TAG_EDIT_CODE if len == 40 => Mutation::EditCode {
                node_id: read_u32(bytes, payload)?,
                request_id: read_u32(bytes, payload + 4)?,
                base_version: read_u32(bytes, payload + 8)?,
                start_line: read_u32(bytes, payload + 12)?,
                start_column: read_u32(bytes, payload + 16)?,
                end_line: read_u32(bytes, payload + 20)?,
                end_column: read_u32(bytes, payload + 24)?,
                text: read_string(bytes, payload + 28, records_len)?,
            },
            TAG_POST_NOTIFICATION if len == 56 => {
                let raw_importance = read_u16(bytes, payload + 48)?;
                let raw_presentation = read_u16(bytes, payload + 50)?;
                Mutation::PostNotification {
                    key: read_string(bytes, payload, records_len)?,
                    title: read_string(bytes, payload + 8, records_len)?,
                    body: read_string(bytes, payload + 16, records_len)?,
                    channel: read_string(bytes, payload + 24, records_len)?,
                    action_1: read_string(bytes, payload + 32, records_len)?,
                    action_2: read_string(bytes, payload + 40, records_len)?,
                    importance: NotificationImportance::try_from(raw_importance)
                        .map_err(|()| ProtocolError::InvalidNotification(raw_importance))?,
                    presentation: NotificationPresentation::try_from(raw_presentation)
                        .map_err(|()| ProtocolError::InvalidNotification(raw_presentation))?,
                }
            }
            TAG_WITHDRAW_NOTIFICATION if len == 12 => Mutation::WithdrawNotification {
                key: read_string(bytes, payload, records_len)?,
            },
            TAG_REQUEST_NOTIFICATION_PERMISSION if len == 4 => {
                Mutation::RequestNotificationPermission
            }
            TAG_START_ANIMATION if len >= ANIMATION_HEADER_LEN => {
                // A keyframe that runs past the end of its record is a count that does not
                // match the length, which is the record being wrong rather than the batch.
                Mutation::StartAnimation(
                    decode_animation(&bytes[position..position + len]).map_err(|error| {
                        if error == ProtocolError::Truncated {
                            ProtocolError::InvalidAnimation
                        } else {
                            error
                        }
                    })?,
                )
            }
            TAG_CONTROL_ANIMATION if len == 24 => {
                let raw_property = u16::from(read_u8(bytes, payload + 8)?);
                let raw_op = u16::from(read_u8(bytes, payload + 10)?);
                Mutation::ControlAnimation {
                    node_id: read_u32(bytes, payload)?,
                    animation_id: read_u32(bytes, payload + 4)?,
                    property: crate::schema::AnimatedProperty::try_from(raw_property)
                        .map_err(|()| ProtocolError::InvalidAnimation)?,
                    slot: read_u8(bytes, payload + 9)?,
                    op: crate::schema::AnimationControl::try_from(raw_op)
                        .map_err(|()| ProtocolError::InvalidAnimation)?,
                    at_time_nanos: read_u64(bytes, payload + 12)?,
                }
            }
            TAG_SET_SELECTION if len == 28 => Mutation::SetSelection {
                node_id: read_u32(bytes, payload)?,
                base_version: read_u32(bytes, payload + 4)?,
                anchor_line: read_u32(bytes, payload + 8)?,
                anchor_column: read_u32(bytes, payload + 12)?,
                active_line: read_u32(bytes, payload + 16)?,
                active_column: read_u32(bytes, payload + 20)?,
            },
            TAG_REVEAL_RANGE if len == 32 => {
                let raw_align = read_u32(bytes, payload + 24)?;
                Mutation::RevealRange {
                    node_id: read_u32(bytes, payload)?,
                    base_version: read_u32(bytes, payload + 4)?,
                    start_line: read_u32(bytes, payload + 8)?,
                    start_column: read_u32(bytes, payload + 12)?,
                    end_line: read_u32(bytes, payload + 16)?,
                    end_column: read_u32(bytes, payload + 20)?,
                    align: u16::try_from(raw_align)
                        .ok()
                        .and_then(|tag| crate::schema::RevealAlign::try_from(tag).ok())
                        .ok_or(ProtocolError::InvalidValueKind(
                            raw_align.min(u32::from(u16::MAX)) as u16,
                        ))?,
                }
            }
            TAG_SET_FOLDED if len == 16 => Mutation::SetFolded {
                node_id: read_u32(bytes, payload)?,
                start_line: read_u32(bytes, payload + 4)?,
                collapsed: read_flag(bytes, payload + 8)?,
            },
            TAG_SET_SCROLL_OFFSET if len == 16 => Mutation::SetScrollOffset {
                node_id: read_u32(bytes, payload)?,
                offset_px: f64::from_bits(read_u64(bytes, payload + 4)?),
            },
            TAG_SET_SCROLL_OFFSET => return Err(ProtocolError::InvalidRecordLength),
            TAG_CREATE..=TAG_EDIT_CODE
            | TAG_START_ANIMATION
            | TAG_CONTROL_ANIMATION
            | TAG_SET_SELECTION..=TAG_SET_FOLDED
            | TAG_SET_WINDOW_THEME..=TAG_SET_CLIPBOARD_TEXT
            | TAG_READ_CLIPBOARD
            | TAG_STORAGE_CALL
            | TAG_FILE_CALL
            | TAG_RELEASE_FILE_HANDLE
            | TAG_SCROLL_TO
            | TAG_SCROLL_INTO_VIEW
            | TAG_ATTACH_NATIVE_VIEW
            | TAG_FOCUS_NATIVE_VIEW => {
                return Err(ProtocolError::InvalidRecordLength);
            }
            other => return Err(ProtocolError::InvalidTag(other)),
        };
        output.push(mutation);
        position += len;
    }
    if output.len() != count {
        return Err(ProtocolError::InvalidEnvelope);
    }
    Ok(output)
}

/// Reads one `StartAnimation` record, given whole, and refuses anything that is not an
/// animation the Renderer could play. The Renderer asks the same questions of the same
/// bytes, so a record one side refuses is refused on the other as well.
fn decode_animation(record: &[u8]) -> Result<crate::schema::Animation<'static>, ProtocolError> {
    use crate::schema::{
        AnimatedProperty, Animation, AnimationEvents, ColorInterpolation, FillMode, Keyframe,
        KeyframeValue, PlayState, PlaybackDirection, StepPosition, Timing, TransformFunction,
        TransformFunctionKind,
    };
    let bad = ProtocolError::InvalidAnimation;
    fn byte_enum<T: TryFrom<u16, Error = ()>>(
        record: &[u8],
        at: usize,
    ) -> Result<T, ProtocolError> {
        T::try_from(u16::from(read_u8(record, at)?)).map_err(|()| ProtocolError::InvalidAnimation)
    }
    let float = |at: usize| read_u32(record, at).map(f32::from_bits);
    let property: AnimatedProperty = byte_enum(record, 12)?;
    let direction: PlaybackDirection = byte_enum(record, 14)?;
    let fill: FillMode = byte_enum(record, 15)?;
    let play_state: PlayState = byte_enum(record, 16)?;
    let colour = matches!(
        property,
        AnimatedProperty::Color | AnimatedProperty::Background | AnimatedProperty::BorderColor
    );
    let interpolation = match (colour, read_u8(record, 17)?) {
        (false, 0) => None,
        (true, raw) => Some(
            ColorInterpolation::try_from(u16::from(raw))
                .map_err(|()| ProtocolError::InvalidAnimation)?,
        ),
        (false, _) => return Err(bad),
    };
    let keyframe_count = usize::from(read_u16(record, 18)?);
    let delay_ms = float(28)?;
    let duration_ms = float(32)?;
    let iterations = float(36)?;
    let origin_x = float(40)?;
    let origin_y = float(44)?;
    let events = read_u8(record, 48)?;
    if keyframe_count < 2
        || !delay_ms.is_finite()
        || !(duration_ms.is_finite() && duration_ms >= 0.0)
        || iterations.is_nan()
        || iterations < 0.0
        || !(origin_x.is_finite() && origin_y.is_finite())
        || events & !AnimationEvents::ALL.0 != 0
    {
        return Err(bad);
    }
    let mut keyframes = Vec::with_capacity(keyframe_count);
    let mut at = ANIMATION_HEADER_LEN;
    for index in 0..keyframe_count {
        let offset = float(at)?;
        let raw_timing = read_u8(record, at + 4)?;
        let raw_position = read_u8(record, at + 5)?;
        let flags = read_u16(record, at + 6)?;
        let timing = match raw_timing {
            1 => Timing::Linear,
            2 => {
                let (x1, y1, x2, y2) = (
                    float(at + 8)?,
                    float(at + 12)?,
                    float(at + 16)?,
                    float(at + 20)?,
                );
                if ![x1, y1, x2, y2].iter().all(|value| value.is_finite())
                    || !(0.0..=1.0).contains(&x1)
                    || !(0.0..=1.0).contains(&x2)
                {
                    return Err(bad);
                }
                Timing::CubicBezier { x1, y1, x2, y2 }
            }
            3 => {
                let count = read_u32(record, at + 8)?;
                let position =
                    StepPosition::try_from(u16::from(raw_position)).map_err(|()| bad.clone())?;
                let least = if position == StepPosition::JumpNone {
                    2
                } else {
                    1
                };
                if count < least {
                    return Err(bad);
                }
                Timing::Steps { count, position }
            }
            _ => return Err(bad),
        };
        if flags & !KEYFRAME_FROM_PRESENTED != 0
            || (index > 0 && flags & KEYFRAME_FROM_PRESENTED != 0)
            || !(0.0..=1.0).contains(&offset)
        {
            return Err(bad);
        }
        let value_at = at + KEYFRAME_HEAD_LEN;
        let (value, value_len) = match property {
            AnimatedProperty::Alpha => {
                let value = float(value_at)?;
                if !value.is_finite() {
                    return Err(bad);
                }
                (KeyframeValue::Alpha(value), 4)
            }
            AnimatedProperty::Color
            | AnimatedProperty::Background
            | AnimatedProperty::BorderColor => {
                // A colour can be mixed with another colour; a brush cannot.
                let paint = Paint::from_bits(read_u64(record, value_at)?)
                    .filter(|paint| !matches!(paint, Paint::Asset(_)))
                    .ok_or(bad.clone())?;
                (KeyframeValue::Paint(paint), 8)
            }
            AnimatedProperty::Transform => {
                let count = usize::from(read_u16(record, value_at)?);
                let mut functions = Vec::with_capacity(count);
                for function in 0..count {
                    let function_at = value_at + 4 + TRANSFORM_FUNCTION_LEN * function;
                    let raw_kind = read_u32(record, function_at)?;
                    let kind = u16::try_from(raw_kind)
                        .ok()
                        .and_then(|kind| TransformFunctionKind::try_from(kind).ok())
                        .ok_or(bad.clone())?;
                    let mut values = [0.0_f32; 6];
                    for (slot, value) in values.iter_mut().enumerate() {
                        *value = float(function_at + 4 + 4 * slot)?;
                    }
                    if !values.iter().all(|value| value.is_finite()) {
                        return Err(bad);
                    }
                    functions.push(TransformFunction { kind, values });
                }
                (
                    KeyframeValue::Transform(std::borrow::Cow::Owned(functions)),
                    4 + TRANSFORM_FUNCTION_LEN * count,
                )
            }
        };
        keyframes.push(Keyframe {
            offset,
            timing,
            from_presented: flags & KEYFRAME_FROM_PRESENTED != 0,
            value,
        });
        at = value_at + value_len;
    }
    if at != record.len() {
        return Err(bad);
    }
    // Offsets start at 0, end at 1 and never go back, and every transform list has the
    // same kinds in the same order. A list the Host did not line up cannot be interpolated
    // one function to the next, which is the only way the Renderer interpolates them.
    let first = keyframes.first().map(|keyframe| keyframe.offset);
    let last = keyframes.last().map(|keyframe| keyframe.offset);
    if first != Some(0.0)
        || last != Some(1.0)
        || keyframes
            .windows(2)
            .any(|pair| pair[1].offset < pair[0].offset)
    {
        return Err(bad);
    }
    if let Some(KeyframeValue::Transform(shape)) = keyframes.first().map(|keyframe| &keyframe.value)
    {
        let kinds: Vec<_> = shape.iter().map(|function| function.kind).collect();
        let lined_up = keyframes.iter().all(|keyframe| match &keyframe.value {
            KeyframeValue::Transform(functions) => functions
                .iter()
                .map(|function| function.kind)
                .eq(kinds.iter().copied()),
            _ => false,
        });
        if !lined_up {
            return Err(bad);
        }
    }
    Ok(Animation {
        node_id: read_u32(record, 4)?,
        animation_id: read_u32(record, 8)?,
        property,
        slot: read_u8(record, 13)?,
        direction,
        fill,
        play_state,
        interpolation,
        start_time_nanos: read_u64(record, 20)?,
        delay_ms,
        duration_ms,
        iterations,
        origin_x,
        origin_y,
        events: AnimationEvents(events),
        keyframes: std::borrow::Cow::Owned(keyframes),
    })
}

/// Two `f32` share one `u64`, with the first value in the low 32 bits.
const fn pack_floats(low: f32, high: f32) -> u64 {
    (low.to_bits() as u64) | ((high.to_bits() as u64) << 32)
}

const fn unpack_low(word: u64) -> f32 {
    f32::from_bits(word as u32)
}

const fn unpack_high(word: u64) -> f32 {
    f32::from_bits((word >> 32) as u32)
}

/// The value's words with nothing past the first two, which is every modifier but the few
/// whose schema entry gives them more.
const fn two_words(first: u64, second: u64) -> [u64; MAX_MODIFIER_WORDS] {
    [first, second, 0, 0, 0, 0, 0, 0, 0, 0]
}

fn float_word(value: f32) -> u64 {
    u64::from(value.to_bits())
}

/// A modifier's tag and the words of its value. Only as many words as the schema gives
/// that tag are written; the rest are zero and never leave this function.
fn modifier_fields(modifier: &Modifier) -> (u16, [u64; MAX_MODIFIER_WORDS]) {
    match modifier {
        Modifier::Empty => (0, two_words(0, 0)),
        Modifier::Padding(value) => (1, two_words(float_word(*value), 0)),
        Modifier::FillMaxWidth => (2, two_words(0, 0)),
        Modifier::MatchParentSize => (31, two_words(0, 0)),
        Modifier::FillMaxHeight => (3, two_words(0, 0)),
        Modifier::Width(value) => (4, two_words(float_word(*value), 0)),
        Modifier::Height(value) => (5, two_words(float_word(*value), 0)),
        Modifier::Size { width, height } => (6, two_words(float_word(*width), float_word(*height))),
        Modifier::Background(paint) => (7, two_words(paint.to_bits(), 0)),
        Modifier::Clickable { handler_id } => (8, two_words(*handler_id, 0)),
        Modifier::PaddingRole(role) => (9, two_words(*role as u64, 0)),
        Modifier::PaddingEach {
            start,
            top,
            end,
            bottom,
        } => (
            10,
            two_words(pack_floats(*start, *top), pack_floats(*end, *bottom)),
        ),
        Modifier::Weight(value) => (11, two_words(float_word(*value), 0)),
        Modifier::Shape {
            top_start,
            top_end,
            bottom_end,
            bottom_start,
        } => (
            12,
            two_words(
                pack_floats(*top_start, *top_end),
                pack_floats(*bottom_end, *bottom_start),
            ),
        ),
        Modifier::ShapeRole(role) => (13, two_words(*role as u64, 0)),
        Modifier::Border { width, paint } => (14, two_words(float_word(*width), paint.to_bits())),
        Modifier::Elevation(dp) => (15, two_words(float_word(*dp), 0)),
        Modifier::ObserveSize { token, every_dp } => {
            (16, two_words(u64::from(*token), u64::from(*every_dp)))
        }
        Modifier::Motion(role) => (17, two_words(*role as u64, 0)),
        Modifier::Material(role) => (18, two_words(*role as u64, 0)),
        Modifier::Offset { x, y } => (19, two_words(float_word(*x), float_word(*y))),
        Modifier::RequiredSize { width, height } => {
            (20, two_words(float_word(*width), float_word(*height)))
        }
        Modifier::BorderEach {
            top,
            right,
            bottom,
            left,
            top_paint,
            right_paint,
            bottom_paint,
            left_paint,
        } => (
            21,
            [
                pack_floats(*top, *right),
                pack_floats(*bottom, *left),
                top_paint.to_bits(),
                right_paint.to_bits(),
                bottom_paint.to_bits(),
                left_paint.to_bits(),
                0,
                0,
                0,
                0,
            ],
        ),
        Modifier::CornerEach {
            top_left,
            top_right,
            bottom_right,
            bottom_left,
        } => (
            22,
            two_words(
                pack_floats(*top_left, *top_right),
                pack_floats(*bottom_right, *bottom_left),
            ),
        ),
        Modifier::Shadow {
            x,
            y,
            blur,
            spread,
            paint,
        } => (
            23,
            [
                pack_floats(*x, *y),
                pack_floats(*blur, *spread),
                paint.to_bits(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
            ],
        ),
        Modifier::WindowDrag(enabled) => (32, two_words(u64::from(*enabled), 0)),
        Modifier::Clip(enabled) => (24, two_words(u64::from(*enabled), 0)),
        Modifier::Alpha(value) => (25, two_words(float_word(*value), 0)),
        Modifier::PressFeedback { scale, tint_alpha } => {
            (33, two_words(pack_floats(*scale, *tint_alpha), 0))
        }
        Modifier::Semantics { role, state } => (34, two_words(*role as u64, u64::from(*state))),
        Modifier::CornerCurve { smoothing } => (35, two_words(float_word(*smoothing), 0)),
        Modifier::Transform {
            a,
            b,
            c,
            d,
            e,
            f,
            origin_x,
            origin_y,
        } => (
            26,
            [
                pack_floats(*a, *b),
                pack_floats(*c, *d),
                pack_floats(*e, *f),
                pack_floats(*origin_x, *origin_y),
                0,
                0,
                0,
                0,
                0,
                0,
            ],
        ),
        Modifier::Transform3D {
            m,
            origin_x,
            origin_y,
            backface_hidden,
        } => (
            27,
            [
                pack_floats(m[0], m[1]),
                pack_floats(m[2], m[3]),
                pack_floats(m[4], m[5]),
                pack_floats(m[6], m[7]),
                pack_floats(m[8], m[9]),
                pack_floats(m[10], m[11]),
                pack_floats(m[12], m[13]),
                pack_floats(m[14], m[15]),
                pack_floats(*origin_x, *origin_y),
                u64::from(*backface_hidden),
            ],
        ),
    }
}

fn decode_role<T: TryFrom<u16, Error = ()>>(first: u64) -> Result<T, ProtocolError> {
    let tag = u16::try_from(first).map_err(|_| ProtocolError::InvalidModifier(0))?;
    T::try_from(tag).map_err(|()| ProtocolError::InvalidModifier(tag))
}

fn decode_paint(bits: u64) -> Result<Paint, ProtocolError> {
    Paint::from_bits(bits).ok_or(ProtocolError::InvalidModifier(0))
}

fn decode_modifier(tag: u16, words: &[u64; MAX_MODIFIER_WORDS]) -> Result<Modifier, ProtocolError> {
    let [
        first,
        second,
        third,
        fourth,
        fifth,
        sixth,
        seventh,
        eighth,
        ninth,
        tenth,
    ] = *words;
    match tag {
        0 => Ok(Modifier::Empty),
        1 => Ok(Modifier::Padding(f32::from_bits(first as u32))),
        2 => Ok(Modifier::FillMaxWidth),
        3 => Ok(Modifier::FillMaxHeight),
        4 => Ok(Modifier::Width(f32::from_bits(first as u32))),
        5 => Ok(Modifier::Height(f32::from_bits(first as u32))),
        6 => Ok(Modifier::Size {
            width: f32::from_bits(first as u32),
            height: f32::from_bits(second as u32),
        }),
        7 => Ok(Modifier::Background(decode_paint(first)?)),
        8 => Ok(Modifier::Clickable { handler_id: first }),
        9 => Ok(Modifier::PaddingRole(decode_role::<SpaceRole>(first)?)),
        10 => Ok(Modifier::PaddingEach {
            start: unpack_low(first),
            top: unpack_high(first),
            end: unpack_low(second),
            bottom: unpack_high(second),
        }),
        11 => Ok(Modifier::Weight(f32::from_bits(first as u32))),
        12 => Ok(Modifier::Shape {
            top_start: unpack_low(first),
            top_end: unpack_high(first),
            bottom_end: unpack_low(second),
            bottom_start: unpack_high(second),
        }),
        13 => Ok(Modifier::ShapeRole(decode_role::<ShapeRole>(first)?)),
        14 => Ok(Modifier::Border {
            width: f32::from_bits(first as u32),
            paint: decode_paint(second)?,
        }),
        15 => Ok(Modifier::Elevation(f32::from_bits(first as u32))),
        16 => Ok(Modifier::ObserveSize {
            token: first as u32,
            every_dp: match second {
                0 => false,
                1 => true,
                _ => return Err(ProtocolError::InvalidModifier(16)),
            },
        }),
        17 => Ok(Modifier::Motion(decode_role::<MotionRole>(first)?)),
        18 => Ok(Modifier::Material(decode_role::<MaterialRole>(first)?)),
        19 => Ok(Modifier::Offset {
            x: f32::from_bits(first as u32),
            y: f32::from_bits(second as u32),
        }),
        20 => Ok(Modifier::RequiredSize {
            width: f32::from_bits(first as u32),
            height: f32::from_bits(second as u32),
        }),
        21 => Ok(Modifier::BorderEach {
            top: unpack_low(first),
            right: unpack_high(first),
            bottom: unpack_low(second),
            left: unpack_high(second),
            top_paint: decode_paint(third)?,
            right_paint: decode_paint(fourth)?,
            bottom_paint: decode_paint(fifth)?,
            left_paint: decode_paint(sixth)?,
        }),
        22 => Ok(Modifier::CornerEach {
            top_left: unpack_low(first),
            top_right: unpack_high(first),
            bottom_right: unpack_low(second),
            bottom_left: unpack_high(second),
        }),
        23 => Ok(Modifier::Shadow {
            x: unpack_low(first),
            y: unpack_high(first),
            blur: unpack_low(second),
            spread: unpack_high(second),
            paint: decode_paint(third)?,
        }),
        // A flag is 0 or 1. Anything else is a record the two sides disagree about, and
        // reading it as "on" would hide that.
        24 => match first {
            0 => Ok(Modifier::Clip(false)),
            1 => Ok(Modifier::Clip(true)),
            _ => Err(ProtocolError::InvalidModifier(24)),
        },
        32 => match first {
            0 => Ok(Modifier::WindowDrag(false)),
            1 => Ok(Modifier::WindowDrag(true)),
            _ => Err(ProtocolError::InvalidModifier(32)),
        },
        25 => Ok(Modifier::Alpha(f32::from_bits(first as u32))),
        26 => {
            let values = [
                unpack_low(first),
                unpack_high(first),
                unpack_low(second),
                unpack_high(second),
                unpack_low(third),
                unpack_high(third),
                unpack_low(fourth),
                unpack_high(fourth),
            ];
            // A NaN or an infinity in a matrix makes everything under it undrawable, and
            // the two sides would not even agree on how. Refused here, as the Renderer
            // refuses it.
            if values.iter().any(|value| !value.is_finite()) {
                return Err(ProtocolError::InvalidModifier(26));
            }
            let [a, b, c, d, e, f, origin_x, origin_y] = values;
            Ok(Modifier::Transform {
                a,
                b,
                c,
                d,
                e,
                f,
                origin_x,
                origin_y,
            })
        }
        27 => {
            let values = [
                unpack_low(first),
                unpack_high(first),
                unpack_low(second),
                unpack_high(second),
                unpack_low(third),
                unpack_high(third),
                unpack_low(fourth),
                unpack_high(fourth),
                unpack_low(fifth),
                unpack_high(fifth),
                unpack_low(sixth),
                unpack_high(sixth),
                unpack_low(seventh),
                unpack_high(seventh),
                unpack_low(eighth),
                unpack_high(eighth),
                unpack_low(ninth),
                unpack_high(ninth),
            ];
            // The same refusal as the 2D matrix: a NaN or an infinity would draw nothing
            // on one side and something on the other.
            if values.iter().any(|value| !value.is_finite()) {
                return Err(ProtocolError::InvalidModifier(27));
            }
            let backface_hidden = match tenth {
                0 => false,
                1 => true,
                _ => return Err(ProtocolError::InvalidModifier(27)),
            };
            let mut m = [0.0; 16];
            m.copy_from_slice(&values[..16]);
            Ok(Modifier::Transform3D {
                m,
                origin_x: values[16],
                origin_y: values[17],
                backface_hidden,
            })
        }
        31 => Ok(Modifier::MatchParentSize),
        33 => Ok(Modifier::PressFeedback {
            scale: unpack_low(first),
            tint_alpha: unpack_high(first),
        }),
        34 => {
            let state = u32::try_from(second)
                .ok()
                .filter(|state| state & !SEMANTICS_STATE_MASK == 0)
                .ok_or(ProtocolError::InvalidModifier(34))?;
            Ok(Modifier::Semantics {
                role: decode_role::<SemanticsRole>(first)?,
                state,
            })
        }
        // Carried as sent. An out-of-range value is not an error; the Renderer draws it as
        // the nearer end of 0..=1.
        35 => Ok(Modifier::CornerCurve {
            smoothing: unpack_low(first),
        }),
        other => Err(ProtocolError::InvalidModifier(other)),
    }
}

/// Reads a `(offset: u32, len: u32)` string reference at `position`.
///
/// Strings live in the arena that follows the records, so `arena_start` is the
/// first byte a string may legally point at. Without that floor a hostile Renderer can
/// aim a string at the record region and have the decoder reinterpret record headers as
/// text, garbage decoding into a valid-looking mutation.
fn read_string(bytes: &[u8], position: usize, arena_start: usize) -> Result<&str, ProtocolError> {
    let value = read_bytes(bytes, position, arena_start)?;
    std::str::from_utf8(value).map_err(|_| ProtocolError::InvalidUtf8)
}

/// The same arena range a string uses, without the UTF-8 requirement.
fn read_bytes(bytes: &[u8], position: usize, arena_start: usize) -> Result<&[u8], ProtocolError> {
    let offset =
        usize::try_from(read_u32(bytes, position)?).map_err(|_| ProtocolError::LengthOverflow)?;
    let len = usize::try_from(read_u32(bytes, position + 4)?)
        .map_err(|_| ProtocolError::LengthOverflow)?;
    let end = offset
        .checked_add(len)
        .ok_or(ProtocolError::InvalidStringRange)?;
    if offset < arena_start {
        return Err(ProtocolError::InvalidStringRange);
    }
    bytes
        .get(offset..end)
        .ok_or(ProtocolError::InvalidStringRange)
}

fn read_u8(bytes: &[u8], position: usize) -> Result<u8, ProtocolError> {
    bytes.get(position).copied().ok_or(ProtocolError::Truncated)
}

fn read_u16(bytes: &[u8], position: usize) -> Result<u16, ProtocolError> {
    let value = bytes
        .get(position..position + 2)
        .ok_or(ProtocolError::Truncated)?;
    Ok(u16::from_le_bytes([value[0], value[1]]))
}

fn read_u32(bytes: &[u8], position: usize) -> Result<u32, ProtocolError> {
    let value = bytes
        .get(position..position + 4)
        .ok_or(ProtocolError::Truncated)?;
    Ok(u32::from_le_bytes([value[0], value[1], value[2], value[3]]))
}

/// A word that is 0 or 1. Any other value is a protocol error, not a third state.
/// Refuses a reserved word that is not zero: a record from a newer or a broken side, whose
/// meaning here would be a guess.
fn require_zero_word(bytes: &[u8], position: usize) -> Result<(), ProtocolError> {
    match read_u32(bytes, position)? {
        0 => Ok(()),
        other => Err(ProtocolError::InvalidValueKind(
            other.min(u32::from(u16::MAX)) as u16,
        )),
    }
}

fn read_flag(bytes: &[u8], position: usize) -> Result<bool, ProtocolError> {
    match read_u32(bytes, position)? {
        0 => Ok(false),
        1 => Ok(true),
        other => Err(ProtocolError::InvalidValueKind(
            other.min(u32::from(u16::MAX)) as u16,
        )),
    }
}

fn read_u64(bytes: &[u8], position: usize) -> Result<u64, ProtocolError> {
    let value = bytes
        .get(position..position + 8)
        .ok_or(ProtocolError::Truncated)?;
    Ok(u64::from_le_bytes(
        value.try_into().map_err(|_| ProtocolError::Truncated)?,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::TypeRole;

    #[test]
    fn round_trips_all_m0_mutations() {
        let mutations = [
            Mutation::Create {
                node_id: 1,
                widget: WidgetKind::Column,
            },
            Mutation::SetProp {
                node_id: 2,
                property: PropertyKind::Text,
                value: PropertyValue::String("안녕"),
            },
            Mutation::SetProp {
                node_id: 3,
                property: PropertyKind::Enabled,
                value: PropertyValue::Bool(true),
            },
            Mutation::SetModifier {
                node_id: 1,
                index: 0,
                modifier: Modifier::Padding(16.0),
            },
            Mutation::Insert {
                parent_id: 1,
                node_id: 2,
                index: 0,
            },
            Mutation::Move {
                parent_id: 1,
                node_id: 2,
                index: 1,
            },
            Mutation::Remove { node_id: 3 },
            Mutation::SetText {
                node_id: 4,
                text: "compose",
                selection: Some(Selection { start: 1, end: 4 }),
            },
            Mutation::AppendText {
                node_id: 4,
                text: " token",
            },
        ];
        let mut encoder = BatchEncoder::default();
        for mutation in &mutations {
            encoder.encode(mutation).unwrap();
        }
        let decoded = decode_batch(encoder.finish().unwrap()).unwrap();
        assert_eq!(decoded, mutations);
    }

    /// A role Paint and a literal Paint occupy the same record length and
    /// decode back to the value that was encoded.
    #[test]
    fn fr13_paint_role_and_literal_share_one_record_length() {
        let encode_one = |modifier| {
            let mut encoder = BatchEncoder::default();
            encoder
                .encode(&Mutation::SetModifier {
                    node_id: 1,
                    index: 0,
                    modifier,
                })
                .unwrap();
            encoder.finish().unwrap().to_vec()
        };
        let role = encode_one(Modifier::Background(Paint::Role(
            crate::schema::ColorRole::Surface,
        )));
        let literal = encode_one(Modifier::Background(Paint::Literal(
            crate::schema::Color::rgb(0x1b1b1f),
        )));
        assert_eq!(role.len(), literal.len());
        assert_eq!(
            decode_batch(&role).unwrap(),
            [Mutation::SetModifier {
                node_id: 1,
                index: 0,
                modifier: Modifier::Background(Paint::Role(crate::schema::ColorRole::Surface)),
            }]
        );
        assert_eq!(
            decode_batch(&literal).unwrap(),
            [Mutation::SetModifier {
                node_id: 1,
                index: 0,
                modifier: Modifier::Background(Paint::Literal(crate::schema::Color::rgb(0x1b1b1f))),
            }]
        );
    }

    #[test]
    fn fr68_semantics_round_trips_and_refuses_unknown_state_bits() {
        let expected = Modifier::Semantics {
            role: SemanticsRole::Switch,
            state: (2 << crate::schema::SEMANTICS_CHECKED_SHIFT)
                | (1 << crate::schema::SEMANTICS_SELECTED_SHIFT),
        };
        let mutation = Mutation::SetModifier {
            node_id: 1,
            index: 0,
            modifier: expected.clone(),
        };
        let mut encoder = BatchEncoder::default();
        encoder.encode(&mutation).unwrap();
        assert_eq!(decode_batch(encoder.finish().unwrap()).unwrap(), [mutation]);

        let words = [SemanticsRole::Button as u64, 0x2000, 0, 0, 0, 0, 0, 0, 0, 0];
        assert_eq!(
            decode_modifier(34, &words),
            Err(ProtocolError::InvalidModifier(34))
        );
    }

    #[test]
    fn fr71_every_new_role_round_trips_and_is_exposed() {
        use crate::schema::*;
        let roles = [
            (SemanticsRole::TabList, 9),
            (SemanticsRole::Tree, 10),
            (SemanticsRole::TreeItem, 11),
            (SemanticsRole::Menu, 12),
            (SemanticsRole::MenuItem, 13),
            (SemanticsRole::TextBox, 14),
            (SemanticsRole::Heading, 15),
            (SemanticsRole::Navigation, 16),
            (SemanticsRole::List, 17),
            (SemanticsRole::ListItem, 18),
            (SemanticsRole::Group, 19),
        ];
        let state = (2 << SEMANTICS_EXPANDED_SHIFT)
            | (2 << SEMANTICS_DISABLED_SHIFT)
            | (1 << SEMANTICS_FOCUSED_SHIFT)
            | SEMANTICS_HIDDEN_BIT
            | SEMANTICS_CLICK_ACTION_BIT
            | SEMANTICS_FOCUS_ACTION_BIT;
        assert_eq!(state & !SEMANTICS_STATE_MASK, 0);
        for (role, value) in roles {
            assert_eq!(role as u64, value, "{role:?} changed its wire value");
            let mutation = Mutation::SetModifier {
                node_id: 1,
                index: 0,
                modifier: Modifier::Semantics { role, state },
            };
            let mut encoder = BatchEncoder::default();
            encoder.encode(&mutation).unwrap();
            assert_eq!(decode_batch(encoder.finish().unwrap()).unwrap(), [mutation]);
        }
        assert!(
            ROLE_ENUM_SCHEMA
                .iter()
                .any(|e| e.name == "SemanticsRole" && e.variants.len() == 19),
            "the schema must list all 19 roles"
        );
    }

    #[test]
    fn nfr7_unknown_semantics_bit_is_a_protocol_error() {
        // Bit 13 is the first one above the mask.
        let words = [SemanticsRole::Group as u64, 1 << 13, 0, 0, 0, 0, 0, 0, 0, 0];
        assert_eq!(
            decode_modifier(34, &words),
            Err(ProtocolError::InvalidModifier(34))
        );
        // The highest bit the mask allows still decodes.
        let words = [SemanticsRole::Group as u64, 1 << 12, 0, 0, 0, 0, 0, 0, 0, 0];
        assert!(decode_modifier(34, &words).is_ok());
    }

    #[test]
    fn fr51_press_feedback_round_trips() {
        let expected = [Mutation::SetModifier {
            node_id: 1,
            index: 0,
            modifier: Modifier::PressFeedback {
                scale: 0.975,
                tint_alpha: 0.07,
            },
        }];
        let mut encoder = BatchEncoder::default();
        encoder.encode(&expected[0]).unwrap();
        assert_eq!(decode_batch(encoder.finish().unwrap()).unwrap(), expected);
    }

    #[test]
    fn fr50_corner_curve_round_trips() {
        for smoothing in [0.0, 0.6, 1.0, -2.5, 7.0] {
            let expected = [Mutation::SetModifier {
                node_id: 1,
                index: 0,
                modifier: Modifier::CornerCurve { smoothing },
            }];
            let mut encoder = BatchEncoder::default();
            encoder.encode(&expected[0]).unwrap();
            let bytes = encoder.finish().unwrap().to_vec();
            assert_eq!(u16::from_le_bytes([bytes[22], bytes[23]]), 35);
            assert_eq!(decode_batch(&bytes).unwrap(), expected);
        }
    }

    /// Every design primitive fits in `(tag, u64, u64)` and survives a round trip.
    #[test]
    fn fr13_design_modifiers_round_trip_in_two_words() {
        let modifiers = [
            Modifier::PaddingRole(SpaceRole::Md),
            Modifier::PaddingEach {
                start: 1.0,
                top: 2.0,
                end: 3.0,
                bottom: 4.0,
            },
            Modifier::Weight(0.25),
            Modifier::Shape {
                top_start: 4.0,
                top_end: 8.0,
                bottom_end: 12.0,
                bottom_start: 16.0,
            },
            Modifier::ShapeRole(ShapeRole::Full),
            Modifier::Border {
                width: 2.0,
                paint: Paint::Role(crate::schema::ColorRole::Outline),
            },
            Modifier::Elevation(6.0),
        ];
        let mut encoder = BatchEncoder::default();
        let expected: Vec<_> = modifiers
            .iter()
            .enumerate()
            .map(|(index, modifier)| Mutation::SetModifier {
                node_id: 1,
                index: index as u16,
                modifier: modifier.clone(),
            })
            .collect();
        for mutation in &expected {
            encoder.encode(mutation).unwrap();
        }
        let bytes = encoder.finish().unwrap();
        // Every modifier record is the same fixed 28 bytes.
        assert_eq!(bytes.len(), ENVELOPE_LEN + expected.len() * 28);
        assert_eq!(decode_batch(bytes).unwrap(), expected);
    }

    /// A node's shadows are several `Shadow` records at consecutive chain positions,
    /// each with its own offset, blur, spread and paint (alpha included), and every one
    /// survives a round trip in the order it was written.
    #[test]
    fn fr42_shadow_a_list_of_shadows_round_trips_in_order() {
        let shadows = [
            Modifier::Shadow {
                x: 0.0,
                y: 1.0,
                blur: 2.0,
                spread: 0.0,
                paint: Paint::Literal(crate::schema::Color::argb(0x1400_0000)),
            },
            Modifier::Shadow {
                x: 0.0,
                y: 4.0,
                blur: 12.0,
                spread: -2.0,
                paint: Paint::Literal(crate::schema::Color::argb(0x2900_0000)),
            },
            Modifier::Shadow {
                x: -3.5,
                y: 24.0,
                blur: 48.0,
                spread: 4.0,
                paint: Paint::Role(crate::schema::ColorRole::Outline),
            },
        ];
        let expected: Vec<_> = shadows
            .iter()
            .enumerate()
            .map(|(index, modifier)| Mutation::SetModifier {
                node_id: 7,
                index: 9 + index as u16,
                modifier: modifier.clone(),
            })
            .collect();
        let mut encoder = BatchEncoder::default();
        for mutation in &expected {
            encoder.encode(mutation).unwrap();
        }
        let bytes = encoder.finish().unwrap();
        assert_eq!(decode_batch(bytes).unwrap(), expected);
    }

    /// `SetTheme` is four `u16` fields and one font slot per type role.
    ///
    /// Fixed length whether or not any font is named, because the record is fixed layout
    /// and a theme is sent once rather than per frame. The slots cost 36 bytes on a
    /// message that appears once in a session.
    #[test]
    fn fr23_set_theme_round_trips_with_a_slot_for_every_type_role() {
        let theme = Theme::adaptive(DesignSystem::Cupertino)
            .with_color_scheme(ColorScheme::Dark)
            .with_font(TypeRole::Display, 7);
        let length = 20 + 4 * TYPE_ROLE_COUNT as u16;
        let mut encoder = BatchEncoder::default();
        encoder.encode(&Mutation::SetTheme(theme)).unwrap();
        let bytes = encoder.finish().unwrap();
        assert_eq!(bytes.len(), ENVELOPE_LEN + length as usize);
        assert_eq!(read_u16(bytes, ENVELOPE_LEN).unwrap(), TAG_SET_THEME);
        assert_eq!(read_u16(bytes, ENVELOPE_LEN + 2).unwrap(), length);
        assert_eq!(decode_batch(bytes).unwrap(), [Mutation::SetTheme(theme)]);
    }

    /// A font named for one role leaves every other role on the system font.
    #[test]
    fn fr23_a_font_is_named_for_one_role_and_no_others() {
        let theme = Theme::unified(DesignSystem::Material3).with_font(TypeRole::Display, 3);
        assert_eq!(theme.font(TypeRole::Display), Some(3));
        for role in [
            TypeRole::Body,
            TypeRole::Title,
            TypeRole::Mono,
            TypeRole::Caption,
        ] {
            assert_eq!(theme.font(role), None);
        }
    }

    /// One font for the whole screen: every role but code takes it, and code stays
    /// monospaced until a font is named for it.
    #[test]
    fn fr52_a_default_font_names_every_role_but_mono() {
        let theme = Theme::unified(DesignSystem::Material3).with_default_font(5);
        for role in [
            TypeRole::Display,
            TypeRole::Headline,
            TypeRole::Title,
            TypeRole::Subtitle,
            TypeRole::Body,
            TypeRole::BodyStrong,
            TypeRole::Label,
            TypeRole::Caption,
        ] {
            assert_eq!(theme.font(role), Some(5), "{role:?}");
        }
        assert_eq!(theme.font(TypeRole::Mono), None);
        assert_eq!(theme.font(TypeRole::None), None);

        let both = theme
            .with_font(TypeRole::Mono, 9)
            .with_font(TypeRole::Display, 3);
        assert_eq!(both.font(TypeRole::Mono), Some(9));
        assert_eq!(
            both.font(TypeRole::Display),
            Some(3),
            "the role named last wins"
        );
        assert_eq!(both.font(TypeRole::Body), Some(5));

        let mut encoder = BatchEncoder::default();
        encoder.encode(&Mutation::SetTheme(theme)).unwrap();
        let bytes = encoder.finish().unwrap();
        assert_eq!(decode_batch(bytes).unwrap(), [Mutation::SetTheme(theme)]);
    }

    /// An out-of-range theme tag is an error, never a panic.
    #[test]
    fn fr14_rejects_unknown_theme_tags() {
        let mut encoder = BatchEncoder::default();
        encoder
            .encode(&Mutation::SetTheme(Theme::default()))
            .unwrap();
        let mut bytes = encoder.finish().unwrap().to_vec();
        bytes[ENVELOPE_LEN + 4..ENVELOPE_LEN + 6].copy_from_slice(&99_u16.to_le_bytes());
        assert_eq!(decode_batch(&bytes), Err(ProtocolError::InvalidTheme(99)));
    }

    #[test]
    fn rejects_unknown_tags_without_panicking() {
        let mut bytes = vec![0, 0, 12, 0, 20, 0, 0, 0, 1, 0, 0, 0];
        bytes.extend_from_slice(&999_u16.to_le_bytes());
        bytes.extend_from_slice(&8_u16.to_le_bytes());
        bytes.extend_from_slice(&0_u32.to_le_bytes());
        assert_eq!(decode_batch(&bytes), Err(ProtocolError::InvalidTag(999)));
    }

    #[test]
    fn pr4_renderer_events_round_trip_fixed_layout() {
        let events = [
            HostEvent {
                node_id: 7,
                handler_id: 11,
                payload: crate::EventPayload::Clicked,
            },
            HostEvent {
                node_id: 8,
                handler_id: 12,
                payload: crate::EventPayload::TextChanged("한글"),
            },
            HostEvent {
                node_id: 8,
                handler_id: 13,
                payload: crate::EventPayload::TextSubmitted("done"),
            },
            HostEvent {
                node_id: 8,
                handler_id: 14,
                payload: crate::EventPayload::FocusLost,
            },
            HostEvent {
                node_id: 9,
                handler_id: 16,
                payload: crate::EventPayload::RangeRequested {
                    start: 100,
                    count: 20,
                },
            },
            HostEvent {
                node_id: 8,
                handler_id: 15,
                payload: crate::EventPayload::KeyDown {
                    key: Key::Enter,
                    shift_key: true,
                    ctrl_key: false,
                    alt_key: true,
                    meta_key: false,
                    repeat: true,
                    code: 0x28,
                    text: "Enter",
                },
            },
        ];
        let mut bytes = Vec::new();
        for event in events {
            encode_event(&event, &mut bytes).unwrap();
            assert_eq!(decode_event(&bytes).unwrap(), event);
        }
    }

    #[test]
    fn fr19_9_window_safe_area_round_trips() {
        let event = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: crate::EventPayload::WindowSizeChanged {
                width_dp: 841.5,
                height_dp: 600.25,
                class: crate::WindowSizeClass::Expanded,
                height_class: crate::WindowHeightClass::Medium,
                safe_area_top: 28.5,
                safe_area_leading: 72.0,
                safe_area_trailing: 13.0,
            },
        };
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        assert_eq!(bytes.len(), 44);
        assert_eq!(decode_event(&bytes).unwrap(), event);
    }

    #[test]
    fn nfr7_malformed_key_down_text_is_a_protocol_error() {
        let event = HostEvent {
            node_id: 1,
            handler_id: 2,
            payload: crate::EventPayload::KeyDown {
                key: Key::Enter,
                shift_key: false,
                ctrl_key: false,
                alt_key: false,
                meta_key: false,
                repeat: false,
                code: 0x51,
                text: "ArrowDown",
            },
        };
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        assert_eq!(decode_event(&bytes).unwrap(), event);
        // The text points past the end of the record.
        let mut past_end = bytes.clone();
        past_end[28..32].copy_from_slice(&200_u32.to_le_bytes());
        assert_eq!(
            decode_event(&past_end),
            Err(ProtocolError::InvalidStringRange)
        );
        // The text starts inside the header, before the string area.
        let mut inside_header = bytes.clone();
        inside_header[24..28].copy_from_slice(&8_u32.to_le_bytes());
        assert_eq!(
            decode_event(&inside_header),
            Err(ProtocolError::InvalidStringRange)
        );
        // The text is not UTF-8.
        let mut not_utf8 = bytes;
        not_utf8[32] = 0xff;
        assert_eq!(decode_event(&not_utf8), Err(ProtocolError::InvalidUtf8));
    }

    #[test]
    fn fr19_9_short_window_size_record_is_a_protocol_error() {
        let mut bytes = vec![0; 32];
        bytes[0..2].copy_from_slice(&EVENT_WINDOW_SIZE_CHANGED.to_le_bytes());
        bytes[2..4].copy_from_slice(&32_u16.to_le_bytes());
        assert!(decode_event(&bytes).is_err());
        bytes[2..4].copy_from_slice(&44_u16.to_le_bytes());
        assert!(decode_event(&bytes).is_err());
    }

    #[test]
    fn fr20_unknown_window_size_class_is_a_protocol_error() {
        let event = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: crate::EventPayload::WindowSizeChanged {
                width_dp: 320.0,
                height_dp: 640.0,
                class: crate::WindowSizeClass::Compact,
                height_class: crate::WindowHeightClass::Medium,
                safe_area_top: 0.0,
                safe_area_leading: 0.0,
                safe_area_trailing: 0.0,
            },
        };
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        bytes[24..28].copy_from_slice(&99_u32.to_le_bytes());
        assert_eq!(
            decode_event(&bytes),
            Err(ProtocolError::InvalidValueKind(99))
        );
    }

    #[test]
    fn fr20_unknown_window_height_class_is_a_protocol_error() {
        let event = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: crate::EventPayload::WindowSizeChanged {
                width_dp: 320.0,
                height_dp: 640.0,
                class: crate::WindowSizeClass::Compact,
                height_class: crate::WindowHeightClass::Medium,
                safe_area_top: 0.0,
                safe_area_leading: 0.0,
                safe_area_trailing: 0.0,
            },
        };
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        bytes[28..32].copy_from_slice(&99_u32.to_le_bytes());
        assert_eq!(
            decode_event(&bytes),
            Err(ProtocolError::InvalidValueKind(99))
        );
    }

    #[test]
    fn fr20_window_size_classes_follow_the_material_boundaries() {
        use crate::WindowSizeClass;
        assert_eq!(
            WindowSizeClass::from_width_dp(599.9),
            WindowSizeClass::Compact
        );
        assert_eq!(
            WindowSizeClass::from_width_dp(600.0),
            WindowSizeClass::Medium
        );
        assert_eq!(
            WindowSizeClass::from_width_dp(839.9),
            WindowSizeClass::Medium
        );
        assert_eq!(
            WindowSizeClass::from_width_dp(840.0),
            WindowSizeClass::Expanded
        );
    }

    #[test]
    fn fr20_window_height_classes_follow_the_material_boundaries() {
        use crate::WindowHeightClass;
        assert_eq!(
            WindowHeightClass::from_height_dp(479.9),
            WindowHeightClass::Compact
        );
        assert_eq!(
            WindowHeightClass::from_height_dp(480.0),
            WindowHeightClass::Medium
        );
        assert_eq!(
            WindowHeightClass::from_height_dp(899.9),
            WindowHeightClass::Medium
        );
        assert_eq!(
            WindowHeightClass::from_height_dp(900.0),
            WindowHeightClass::Expanded
        );
    }

    fn post_notification() -> Mutation<'static> {
        Mutation::PostNotification {
            key: "session/7",
            title: "세션이 끝났습니다",
            body: "refactor-parser: 테스트 214개 통과",
            channel: "세션",
            action_1: "열기",
            action_2: "",
            importance: NotificationImportance::Urgent,
            presentation: NotificationPresentation::WhenInactive,
        }
    }

    /// The three notification commands keep the lengths the wire fixes for them: six string
    /// references and two closed enums, one string reference, and a bare header.
    #[test]
    fn fr36_notification_commands_round_trip_at_their_fixed_lengths() {
        let mutations = [
            post_notification(),
            Mutation::WithdrawNotification { key: "session/7" },
            Mutation::RequestNotificationPermission,
        ];
        let mut lengths = Vec::new();
        let mut encoder = BatchEncoder::default();
        for mutation in &mutations {
            encoder.encode(mutation).unwrap();
        }
        let bytes = encoder.finish().unwrap();
        let mut position = ENVELOPE_LEN;
        for _ in &mutations {
            let length = read_u16(bytes, position + 2).unwrap();
            lengths.push((read_u16(bytes, position).unwrap(), length));
            position += usize::from(length);
        }
        assert_eq!(
            lengths,
            [
                (TAG_POST_NOTIFICATION, 56),
                (TAG_WITHDRAW_NOTIFICATION, 12),
                (TAG_REQUEST_NOTIFICATION_PERMISSION, 4),
            ]
        );
        assert_eq!(decode_batch(bytes).unwrap(), mutations);
    }

    /// An importance or a presentation this side does not know is an error, never a guess
    /// and never a panic.
    #[test]
    fn fr36_unknown_importance_and_presentation_are_protocol_errors() {
        for (offset, expected) in [(52, 9_u16), (54, 7_u16)] {
            let mut encoder = BatchEncoder::default();
            encoder.encode(&post_notification()).unwrap();
            let mut bytes = encoder.finish().unwrap().to_vec();
            bytes[ENVELOPE_LEN + offset..ENVELOPE_LEN + offset + 2]
                .copy_from_slice(&expected.to_le_bytes());
            assert_eq!(
                decode_batch(&bytes),
                Err(ProtocolError::InvalidNotification(expected))
            );
        }
    }

    /// A known notification tag at the wrong length is a length error, the same as any
    /// other known record.
    #[test]
    fn fr36_a_notification_record_of_the_wrong_length_is_rejected() {
        let mut bytes = vec![0, 0, 12, 0, 20, 0, 0, 0, 1, 0, 0, 0];
        bytes.extend_from_slice(&TAG_REQUEST_NOTIFICATION_PERMISSION.to_le_bytes());
        bytes.extend_from_slice(&8_u16.to_le_bytes());
        bytes.extend_from_slice(&0_u32.to_le_bytes());
        assert_eq!(
            decode_batch(&bytes),
            Err(ProtocolError::InvalidRecordLength)
        );
    }

    /// The two notification events: a word and a string after the common head, and a word
    /// on its own.
    #[test]
    fn fr36_notification_events_round_trip_fixed_layout() {
        let activated = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: crate::EventPayload::NotificationActivated {
                action: 2,
                key: "session/7",
            },
        };
        let mut bytes = Vec::new();
        encode_event(&activated, &mut bytes).unwrap();
        assert_eq!(read_u16(&bytes, 0).unwrap(), EVENT_NOTIFICATION_ACTIVATED);
        assert_eq!(read_u16(&bytes, 2).unwrap(), 28);
        assert_eq!(bytes.len(), 28 + "session/7".len());
        assert_eq!(decode_event(&bytes).unwrap(), activated);

        for state in [
            NotificationPermission::NotDetermined,
            NotificationPermission::Granted,
            NotificationPermission::Denied,
            NotificationPermission::Unsupported,
        ] {
            let changed = HostEvent {
                node_id: 0,
                handler_id: 0,
                payload: crate::EventPayload::NotificationPermissionChanged(state),
            };
            encode_event(&changed, &mut bytes).unwrap();
            assert_eq!(bytes.len(), 20);
            assert_eq!(decode_event(&bytes).unwrap(), changed);
        }
    }

    /// A permission state outside the four is reported, not mapped onto one of them.
    #[test]
    fn fr36_unknown_permission_state_is_a_protocol_error() {
        let mut bytes = Vec::new();
        encode_event(
            &HostEvent {
                node_id: 0,
                handler_id: 0,
                payload: crate::EventPayload::NotificationPermissionChanged(
                    NotificationPermission::Granted,
                ),
            },
            &mut bytes,
        )
        .unwrap();
        bytes[16..20].copy_from_slice(&5_u32.to_le_bytes());
        assert_eq!(
            decode_event(&bytes),
            Err(ProtocolError::InvalidValueKind(5))
        );
    }

    /// A notification has a body and at most two buttons, so an activation naming a third
    /// is a malformed event.
    #[test]
    fn fr36_an_activation_naming_a_third_button_is_a_protocol_error() {
        let mut bytes = Vec::new();
        encode_event(
            &HostEvent {
                node_id: 0,
                handler_id: 0,
                payload: crate::EventPayload::NotificationActivated {
                    action: 1,
                    key: "k",
                },
            },
            &mut bytes,
        )
        .unwrap();
        bytes[16..20].copy_from_slice(&3_u32.to_le_bytes());
        assert_eq!(
            decode_event(&bytes),
            Err(ProtocolError::InvalidValueKind(3))
        );
    }

    /// How large the Renderer is drawing: two floats and a signed level after the head.
    #[test]
    fn fr43_zoom_changed_round_trips_fixed_layout() {
        let event = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: crate::EventPayload::ZoomChanged {
                k: 1.25 * 1.44,
                os: 1.25,
                level: 2,
            },
        };
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        assert_eq!(read_u16(&bytes, 0).unwrap(), 33);
        assert_eq!(read_u16(&bytes, 2).unwrap(), 28);
        assert_eq!(bytes.len(), 28);
        assert_eq!(decode_event(&bytes).unwrap(), event);

        let smaller = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: crate::EventPayload::ZoomChanged {
                k: 0.5,
                os: 1.0,
                level: -4,
            },
        };
        encode_event(&smaller, &mut bytes).unwrap();
        assert_eq!(decode_event(&bytes).unwrap(), smaller);
    }

    /// A level outside the range the Renderer can be at is a protocol error.
    #[test]
    fn fr43_a_zoom_level_out_of_range_is_rejected() {
        let event = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: crate::EventPayload::ZoomChanged {
                k: 1.0,
                os: 1.0,
                level: 9,
            },
        };
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        assert_eq!(
            decode_event(&bytes),
            Err(ProtocolError::InvalidValueKind(9))
        );
        bytes[2..4].copy_from_slice(&24_u16.to_le_bytes());
        assert_eq!(
            decode_event(&bytes),
            Err(ProtocolError::InvalidRecordLength)
        );
    }

    /// The window record carries the application's zoom level where it asked for one, and
    /// a window that asked for nothing is byte for byte what it was before the level.
    #[test]
    fn fr43_the_window_record_carries_a_zoom_level() {
        for level in [None, Some(-8), Some(0), Some(2), Some(8)] {
            let mut window = crate::schema::Window::new().with_title("zoom");
            window.zoom_level = level;
            let mut encoder = BatchEncoder::default();
            encoder.encode(&Mutation::SetWindow(window)).unwrap();
            let bytes = encoder.finish().unwrap().to_vec();
            if level.is_none() {
                assert_eq!(read_u16(&bytes, ENVELOPE_LEN + 18).unwrap(), 0);
            }
            let decoded = decode_batch(&bytes).unwrap();
            assert_eq!(decoded, vec![Mutation::SetWindow(window)]);
        }
        assert_eq!(
            crate::schema::Window::new().with_zoom_level(20).zoom_level,
            Some(8)
        );
    }

    /// Bit 1 of the flags word asks the implicit window for every whole-dp size change. A
    /// window that does not ask encodes the bytes it always did.
    #[test]
    fn fr20_8_set_window_report_size_bit_roundtrips() {
        for report in [false, true] {
            let window = crate::schema::Window::new()
                .with_title("size")
                .with_report_size(report);
            let mut encoder = BatchEncoder::default();
            encoder.encode(&Mutation::SetWindow(window)).unwrap();
            let bytes = encoder.finish().unwrap().to_vec();
            let flags = read_u16(&bytes, ENVELOPE_LEN + 16).unwrap();
            assert_eq!(flags, 1 | (u16::from(report) << 1));
            assert_eq!(
                decode_batch(&bytes).unwrap(),
                vec![Mutation::SetWindow(window)]
            );
        }
        // The default window, byte by byte from the record layout.
        let window = crate::schema::Window::new();
        let mut encoder = BatchEncoder::default();
        encoder.encode(&Mutation::SetWindow(window)).unwrap();
        let bytes = encoder.finish().unwrap().to_vec();
        let record = &bytes[ENVELOPE_LEN..];
        assert_eq!(record.len(), 32);
        assert_eq!(read_u16(record, 0).unwrap(), TAG_SET_WINDOW);
        assert_eq!(read_u16(record, 2).unwrap(), 32);
        assert_eq!(read_u16(record, 4).unwrap(), window.chrome as u16);
        assert_eq!(read_u16(record, 6).unwrap(), window.title_bar as u16);
        for at in [8, 10, 12, 14] {
            assert_eq!(read_u16(record, at).unwrap(), 0);
        }
        // Resizable only: the mode and the policy are the defaults for the platform.
        let expected_flags = 1
            | ((window.mode as u16) << WINDOW_MODE_SHIFT)
            | ((window.last_window_closed as u16) << WINDOW_POLICY_SHIFT);
        assert_eq!(read_u16(record, 16).unwrap(), expected_flags);
        assert_eq!(read_u16(record, 18).unwrap(), 0);
        assert_eq!(read_u32(record, 28).unwrap(), 0);
    }

    /// The other low bits of the flags word are still nobody's, so a record that sets
    /// one is a protocol error.
    #[test]
    fn fr20_8_set_window_rejects_unknown_flag_bits() {
        let mut encoder = BatchEncoder::default();
        encoder
            .encode(&Mutation::SetWindow(crate::schema::Window::new()))
            .unwrap();
        let bytes = encoder.finish().unwrap().to_vec();
        let flags = read_u16(&bytes, ENVELOPE_LEN + 16).unwrap();
        for bit in 2..8 {
            let bad = flags | (1 << bit);
            let mut copy = bytes.clone();
            copy[ENVELOPE_LEN + 16..ENVELOPE_LEN + 18].copy_from_slice(&bad.to_le_bytes());
            assert_eq!(
                decode_batch(&copy),
                Err(ProtocolError::InvalidValueKind(bad)),
                "bit {bit}"
            );
        }
    }

    #[test]
    fn fr65_request_focus_round_trips_in_eight_bytes() {
        for node_id in [0_u32, 7, u32::MAX] {
            let mut encoder = BatchEncoder::default();
            encoder.encode(&Mutation::RequestFocus { node_id }).unwrap();
            let bytes = encoder.finish().unwrap().to_vec();
            assert_eq!(bytes.len(), ENVELOPE_LEN + 8);
            assert_eq!(read_u16(&bytes, ENVELOPE_LEN).unwrap(), TAG_REQUEST_FOCUS);
            assert_eq!(read_u16(&bytes, ENVELOPE_LEN + 2).unwrap(), 8);
            assert_eq!(read_u32(&bytes, ENVELOPE_LEN + 4).unwrap(), node_id);
            assert_eq!(
                decode_batch(&bytes).unwrap(),
                vec![Mutation::RequestFocus { node_id }]
            );
        }
        let mut encoder = BatchEncoder::default();
        encoder
            .encode(&Mutation::RequestFocus { node_id: 7 })
            .unwrap();
        let mut bad = encoder.finish().unwrap().to_vec();
        bad.extend_from_slice(&[0; 4]);
        let records_len = u32::try_from(bad.len()).unwrap();
        bad[4..8].copy_from_slice(&records_len.to_le_bytes());
        bad[ENVELOPE_LEN + 2..ENVELOPE_LEN + 4].copy_from_slice(&12_u16.to_le_bytes());
        assert_eq!(decode_batch(&bad), Err(ProtocolError::InvalidRecordLength));
    }

    /// The request and the result are fixed records. Both survive a round trip with
    /// Korean text and NUL separated lists in them, an unknown kind or status is a
    /// protocol error, and no prefix of either record decodes or panics.
    #[test]
    fn fr72_file_dialog_records_round_trip_and_unknown_enums_are_errors() {
        let filters = "이미지\0png,jpg\0Docs\0pdf,txt";
        for kind in [
            FileDialogKind::OpenFile,
            FileDialogKind::OpenFiles,
            FileDialogKind::OpenFolder,
            FileDialogKind::OpenFolders,
            FileDialogKind::Save,
        ] {
            let request = Mutation::ShowFileDialog {
                request: 7,
                window: 20,
                kind,
                default_filter: 1,
                title: "파일 고르기",
                directory: "/home/한글",
                name: "보고서.txt",
                filters,
            };
            let mut encoder = BatchEncoder::default();
            encoder.encode(&request).unwrap();
            let bytes = encoder.finish().unwrap().to_vec();
            assert_eq!(
                read_u16(&bytes, ENVELOPE_LEN).unwrap(),
                TAG_SHOW_FILE_DIALOG
            );
            assert_eq!(read_u16(&bytes, ENVELOPE_LEN + 2).unwrap(), 48);
            assert_eq!(decode_batch(&bytes).unwrap(), vec![request]);
        }

        let empty = Mutation::ShowFileDialog {
            request: u32::MAX,
            window: 0,
            kind: FileDialogKind::Save,
            default_filter: 0,
            title: "",
            directory: "",
            name: "",
            filters: "",
        };
        let mut encoder = BatchEncoder::default();
        encoder.encode(&empty).unwrap();
        assert_eq!(
            decode_batch(encoder.finish().unwrap()).unwrap(),
            vec![empty]
        );

        let full = Mutation::ShowFileDialog {
            request: 1,
            window: 2,
            kind: FileDialogKind::OpenFile,
            default_filter: 3,
            title: "t",
            directory: "/d",
            name: "n",
            filters,
        };
        let mut encoder = BatchEncoder::default();
        encoder.encode(&full).unwrap();
        let bytes = encoder.finish().unwrap().to_vec();
        for raw_kind in [0_u16, 6, 9, u16::MAX] {
            let mut bad = bytes.clone();
            bad[ENVELOPE_LEN + 12..ENVELOPE_LEN + 14].copy_from_slice(&raw_kind.to_le_bytes());
            assert_eq!(
                decode_batch(&bad),
                Err(ProtocolError::InvalidValueKind(raw_kind))
            );
        }
        let mut short = bytes.clone();
        short[ENVELOPE_LEN + 2..ENVELOPE_LEN + 4].copy_from_slice(&44_u16.to_le_bytes());
        assert_eq!(
            decode_batch(&short),
            Err(ProtocolError::InvalidRecordLength)
        );
        for cut in 0..bytes.len() {
            assert!(
                decode_batch(&bytes[..cut]).is_err(),
                "a batch cut at {cut} bytes decoded"
            );
        }

        let paths = "/Users/한글/문서.txt\0/tmp/b c.png";
        for (status, filter) in [
            (FileDialogStatus::Chosen, 1_u32),
            (FileDialogStatus::Cancelled, u32::MAX),
            (FileDialogStatus::Unavailable, u32::MAX),
        ] {
            let result = HostEvent {
                node_id: 0,
                handler_id: 0,
                payload: crate::EventPayload::FileDialogResult {
                    request: 41,
                    status,
                    filter,
                    paths: if status == FileDialogStatus::Chosen {
                        paths
                    } else {
                        ""
                    },
                },
            };
            let mut bytes = Vec::new();
            encode_event(&result, &mut bytes).unwrap();
            assert_eq!(read_u16(&bytes, 0).unwrap(), EVENT_FILE_DIALOG_RESULT);
            assert_eq!(
                usize::from(read_u16(&bytes, 2).unwrap()),
                FILE_DIALOG_RESULT_LEN
            );
            assert_eq!(decode_event(&bytes).unwrap(), result);
        }

        let result = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: crate::EventPayload::FileDialogResult {
                request: 3,
                status: FileDialogStatus::Chosen,
                filter: 0,
                paths,
            },
        };
        let mut bytes = Vec::new();
        encode_event(&result, &mut bytes).unwrap();
        for raw_status in [0_u32, 4, 0x1_0000, u32::MAX] {
            let mut bad = bytes.clone();
            bad[20..24].copy_from_slice(&raw_status.to_le_bytes());
            assert_eq!(
                decode_event(&bad),
                Err(ProtocolError::InvalidValueKind(
                    raw_status.min(u32::from(u16::MAX)) as u16
                ))
            );
        }
        let mut wrong_length = bytes.clone();
        wrong_length[2..4].copy_from_slice(&32_u16.to_le_bytes());
        assert_eq!(
            decode_event(&wrong_length),
            Err(ProtocolError::InvalidRecordLength)
        );
        let mut not_utf8 = bytes.clone();
        let last = not_utf8.len() - 1;
        not_utf8[last] = 0xFF;
        assert_eq!(decode_event(&not_utf8), Err(ProtocolError::InvalidUtf8));
        for cut in 0..bytes.len() {
            assert!(
                decode_event(&bytes[..cut]).is_err(),
                "an event cut at {cut} bytes decoded"
            );
        }
    }

    #[test]
    fn fr65_text_field_on_composition_change_delivers_bool() {
        use std::cell::RefCell;
        use std::rc::Rc;
        use std::sync::Arc;
        use std::sync::atomic::{AtomicUsize, Ordering};

        let seen: Rc<RefCell<Vec<bool>>> = Rc::new(RefCell::new(Vec::new()));
        let calls = Arc::new(AtomicUsize::new(0));
        let calls_for_root = Arc::clone(&calls);
        let mut host = crate::ComposeHost::with_content(move || {
            let calls_inner = Arc::clone(&calls_for_root);
            crate::ui::TextField().on_composition_change(move |composing| {
                calls_inner.fetch_add(if composing { 10 } else { 1 }, Ordering::SeqCst);
            });
        });
        let init = host.rebuild().unwrap().to_vec();
        let mutations = decode_batch(&init).unwrap();
        let prop = mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::OnCompositionChange,
                    value: PropertyValue::Integer(handler),
                } => Some((*node_id, *handler as u64)),
                _ => None,
            })
            .expect("TextField with on_composition_change must emit OnCompositionChange");
        let mut wire = Vec::new();
        encode_event(
            &HostEvent {
                node_id: prop.0,
                handler_id: prop.1,
                payload: crate::EventPayload::CompositionChanged { composing: true },
            },
            &mut wire,
        )
        .unwrap();
        host.dispatch_event(&wire).unwrap();
        encode_event(
            &HostEvent {
                node_id: prop.0,
                handler_id: prop.1,
                payload: crate::EventPayload::CompositionChanged { composing: false },
            },
            &mut wire,
        )
        .unwrap();
        host.dispatch_event(&wire).unwrap();
        assert_eq!(calls.load(Ordering::SeqCst), 11);
        drop(seen);
    }

    #[test]
    fn fr45_focus_gained_and_lost_pair() {
        use std::sync::Arc;
        use std::sync::Mutex;

        let trace: Arc<Mutex<Vec<&'static str>>> = Arc::new(Mutex::new(Vec::new()));
        let trace_for_root = Arc::clone(&trace);
        let mut host = crate::ComposeHost::with_content(move || {
            let gained_trace = Arc::clone(&trace_for_root);
            let lost_trace = Arc::clone(&trace_for_root);
            crate::ui::TextField()
                .on_focus_gained(move || {
                    gained_trace.lock().unwrap().push("gained");
                })
                .on_focus_lost(move || {
                    lost_trace.lock().unwrap().push("lost");
                });
        });
        let init = host.rebuild().unwrap().to_vec();
        let mutations = decode_batch(&init).unwrap();
        let gained_prop = mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::OnFocusGained,
                    value: PropertyValue::Integer(handler),
                } => Some((*node_id, *handler as u64)),
                _ => None,
            })
            .expect("TextField with on_focus_gained must emit OnFocusGained");
        let lost_prop = mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::OnFocusLost,
                    value: PropertyValue::Integer(handler),
                } => Some((*node_id, *handler as u64)),
                _ => None,
            })
            .expect("TextField with on_focus_lost must emit OnFocusLost");
        assert_eq!(gained_prop.0, lost_prop.0);
        assert_eq!(PropertyKind::OnFocusGained as u16, 117);

        let gained_event = HostEvent {
            node_id: gained_prop.0,
            handler_id: gained_prop.1,
            payload: crate::EventPayload::FocusGained,
        };
        let mut wire = Vec::new();
        encode_event(&gained_event, &mut wire).unwrap();
        assert_eq!(wire.len(), 16);
        assert_eq!(read_u16(&wire, 0).unwrap(), EVENT_FOCUS_GAINED);
        assert_eq!(EVENT_FOCUS_GAINED, 48);
        assert_eq!(decode_event(&wire).unwrap(), gained_event);
        host.dispatch_event(&wire).unwrap();

        let lost_event = HostEvent {
            node_id: lost_prop.0,
            handler_id: lost_prop.1,
            payload: crate::EventPayload::FocusLost,
        };
        encode_event(&lost_event, &mut wire).unwrap();
        assert_eq!(decode_event(&wire).unwrap(), lost_event);
        host.dispatch_event(&wire).unwrap();

        assert_eq!(*trace.lock().unwrap(), vec!["gained", "lost"]);
    }

    #[test]
    fn fr45_key_up_follows_key_down() {
        use std::sync::Arc;
        use std::sync::Mutex;

        // What each key event carried: which handler, the key, then the four modifiers.
        type KeyTrace = Vec<(&'static str, Key, bool, bool, bool, bool)>;
        let trace: Arc<Mutex<KeyTrace>> = Arc::new(Mutex::new(Vec::new()));
        let trace_for_root = Arc::clone(&trace);
        let mut host = crate::ComposeHost::with_content(move || {
            let down_trace = Arc::clone(&trace_for_root);
            let up_trace = Arc::clone(&trace_for_root);
            crate::ui::TextField()
                .on_key_down(move |event| {
                    down_trace.lock().unwrap().push((
                        "down",
                        event.key(),
                        event.shift_key(),
                        event.ctrl_key(),
                        event.alt_key(),
                        event.meta_key(),
                    ));
                })
                .on_key_up(move |event| {
                    up_trace.lock().unwrap().push((
                        "up",
                        event.key(),
                        event.shift_key(),
                        event.ctrl_key(),
                        event.alt_key(),
                        event.meta_key(),
                    ));
                });
        });
        let init = host.rebuild().unwrap().to_vec();
        let mutations = decode_batch(&init).unwrap();
        let down_prop = mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::OnKeyDown,
                    value: PropertyValue::Integer(handler),
                } => Some((*node_id, *handler as u64)),
                _ => None,
            })
            .expect("TextField with on_key_down must emit OnKeyDown");
        let up_prop = mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::OnKeyUp,
                    value: PropertyValue::Integer(handler),
                } => Some((*node_id, *handler as u64)),
                _ => None,
            })
            .expect("TextField with on_key_up must emit OnKeyUp");
        assert_eq!(down_prop.0, up_prop.0);
        assert_eq!(PropertyKind::OnKeyUp as u16, 118);

        let down_event = HostEvent {
            node_id: down_prop.0,
            handler_id: down_prop.1,
            payload: crate::EventPayload::KeyDown {
                key: Key::Enter,
                shift_key: true,
                ctrl_key: false,
                alt_key: true,
                meta_key: false,
                repeat: false,
                code: 0x28,
                text: "Enter",
            },
        };
        let mut wire = Vec::new();
        encode_event(&down_event, &mut wire).unwrap();
        assert_eq!(decode_event(&wire).unwrap(), down_event);
        host.dispatch_event(&wire).unwrap();

        let up_event = HostEvent {
            node_id: up_prop.0,
            handler_id: up_prop.1,
            payload: crate::EventPayload::KeyUp {
                key: Key::Enter,
                shift_key: true,
                ctrl_key: false,
                alt_key: true,
                meta_key: false,
                repeat: false,
                code: 0x28,
                text: "Enter",
            },
        };
        encode_event(&up_event, &mut wire).unwrap();
        assert_eq!(wire.len(), 37);
        assert_eq!(read_u16(&wire, 0).unwrap(), EVENT_KEY_UP);
        assert_eq!(EVENT_KEY_UP, 49);
        assert_eq!(decode_event(&wire).unwrap(), up_event);
        host.dispatch_event(&wire).unwrap();

        assert_eq!(
            *trace.lock().unwrap(),
            vec![
                ("down", Key::Enter, true, false, true, false),
                ("up", Key::Enter, true, false, true, false),
            ]
        );
    }

    #[test]
    fn fr45_unregistered_nodes_get_no_events() {
        let mut host = crate::ComposeHost::with_content(|| {
            crate::ui::TextField();
            crate::ui::Box().content(|| {});
        });
        let init = host.rebuild().unwrap().to_vec();
        let mutations = decode_batch(&init).unwrap();
        for mutation in &mutations {
            if let Mutation::SetProp { property, .. } = mutation {
                assert_ne!(
                    *property,
                    PropertyKind::OnFocusGained,
                    "unregistered node must not emit OnFocusGained"
                );
                assert_ne!(
                    *property,
                    PropertyKind::OnKeyUp,
                    "unregistered node must not emit OnKeyUp"
                );
            }
        }
    }

    /// The keys menus, lists and overlays act on have their own name, appended after
    /// `Other` so no earlier tag moves, and each one survives the wire.
    #[test]
    fn fr12_named_keys_are_appended_and_round_trip() {
        let named = [
            (Key::Enter, 1u16, 0x28u32, "Enter"),
            (Key::Other, 2, 0x04, "a"),
            (Key::Escape, 3, 0x29, "Escape"),
            (Key::Tab, 4, 0x2B, "Tab"),
            (Key::Backspace, 5, 0x2A, "Backspace"),
            (Key::Delete, 6, 0x4C, "Delete"),
            (Key::ArrowUp, 7, 0x52, "ArrowUp"),
            (Key::ArrowDown, 8, 0x51, "ArrowDown"),
            (Key::ArrowLeft, 9, 0x50, "ArrowLeft"),
            (Key::ArrowRight, 10, 0x4F, "ArrowRight"),
            (Key::Home, 11, 0x4A, "Home"),
            (Key::End, 12, 0x4D, "End"),
            (Key::PageUp, 13, 0x4B, "PageUp"),
            (Key::PageDown, 14, 0x4E, "PageDown"),
        ];
        let mut wire = Vec::new();
        for (key, tag, code, text) in named {
            assert_eq!(Key::try_from(tag), Ok(key), "tag {tag} names {text}");
            let event = HostEvent {
                node_id: 4,
                handler_id: 9,
                payload: crate::EventPayload::KeyDown {
                    key,
                    shift_key: false,
                    ctrl_key: false,
                    alt_key: false,
                    meta_key: false,
                    repeat: false,
                    code,
                    text,
                },
            };
            encode_event(&event, &mut wire).unwrap();
            assert_eq!(read_u16(&wire, 16).unwrap(), tag);
            assert_eq!(decode_event(&wire).unwrap(), event);
        }
        assert_eq!(Key::try_from(15), Err(()));
    }

    /// A drawer or a popup menu is a container, not a `Dialog` or a `Sheet`, so the
    /// Renderer does not dismiss it. Its own `on_key_down` sees Escape by its DOM key
    /// string and HID usage, and consuming it is the call's result.
    #[test]
    fn fr45_container_key_down_sees_escape_and_consumes_it() {
        use std::sync::{Arc, Mutex};

        let seen: Arc<Mutex<Vec<(String, u32)>>> = Arc::new(Mutex::new(Vec::new()));
        let seen_for_root = Arc::clone(&seen);
        let mut host = crate::ComposeHost::with_content(move || {
            let seen = Arc::clone(&seen_for_root);
            crate::ui::Column()
                .on_key_down(move |event| {
                    seen.lock()
                        .unwrap()
                        .push((event.text().to_owned(), event.code()));
                    if event.text() == "Escape" {
                        event.consume();
                    }
                })
                .content(|| {
                    crate::ui::Text("session list");
                });
        });
        let init = host.rebuild().unwrap().to_vec();
        let mutations = decode_batch(&init).unwrap();
        let (node_id, handler_id) = mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::OnKeyDown,
                    value: PropertyValue::Integer(handler),
                } => Some((*node_id, *handler as u64)),
                _ => None,
            })
            .expect("a Column with on_key_down must emit OnKeyDown");
        let created_column = mutations.iter().any(|mutation| {
            matches!(
                mutation,
                Mutation::Create { node_id: id, widget: crate::WidgetKind::Column, .. } if *id == node_id
            )
        });
        assert!(
            created_column,
            "the key handler belongs to the Column itself"
        );

        let mut wire = Vec::new();
        let mut press = |code: u32, text: &'static str| {
            encode_event(
                &HostEvent {
                    node_id,
                    handler_id,
                    payload: crate::EventPayload::KeyDown {
                        key: if code == 0x29 {
                            Key::Escape
                        } else {
                            Key::ArrowDown
                        },
                        shift_key: false,
                        ctrl_key: false,
                        alt_key: false,
                        meta_key: false,
                        repeat: false,
                        code,
                        text,
                    },
                },
                &mut wire,
            )
            .unwrap();
            host.dispatch_event(&wire).unwrap().1
        };
        assert_ne!(
            press(0x29, "Escape"),
            0,
            "Escape was consumed by the container"
        );
        assert_eq!(
            press(0x51, "ArrowDown"),
            0,
            "an unhandled key is left to the Renderer"
        );

        assert_eq!(
            *seen.lock().unwrap(),
            vec![("Escape".to_owned(), 0x29), ("ArrowDown".to_owned(), 0x51)]
        );
    }
}
