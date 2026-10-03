//! A code editor's document as the Host sees it.
//!
//! The Renderer owns the text. It owns the caret, the selection, the scroll position, the
//! undo history and the composition an input method is building, for the same reason a
//! text field owns them: a round trip to this side per keystroke would add a frame of
//! latency to every key. What the Host gets is each committed edit, in order, with the
//! version of the document it produced, which is exactly what a language server or an
//! extension host needs to follow along.
//!
//! What the Host says back is said as ranges. Colour runs for the syntax and decorations
//! for diagnostics, lenses, hover anchors and inline suggestions, each a blob of
//! fixed-length records carrying the document version its ranges were written against.
//! The Renderer moves them along with whatever the reader typed since, so a list that took
//! a language server a second to compute still lands on the text it was about.
//!
//! Positions are a line and a column counted from zero, and a column counts UTF-16 code
//! units. That is the unit a language server and VS Code count in, and any other unit
//! would make every application recount every range on the way through. A line ends at
//! `\n`, `\r\n` or a lone `\r`, which is also the language server's rule.

use crate::schema::{ColorRole, DecorationKind, HoverPhase, Paint, Severity};
use std::cell::{Cell, RefCell};
use std::rc::Rc;

/// Bytes of one decoration record.
pub const DECORATION_LEN: usize = 44;

/// Bytes of one syntax colour run record.
pub const SYNTAX_SPAN_LEN: usize = 28;

/// What one field of a code editor record holds.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RecordFieldType {
    U32,
    U64,
    /// A role tag in a `u16`. `optional` says that zero means "not given" rather than a
    /// broken record.
    Role {
        name: &'static str,
        optional: bool,
    },
    /// A `Paint` in a `u64`.
    Paint,
    /// An `(offset, length)` pair of `u32` naming UTF-8 bytes behind the records, measured
    /// from the start of the blob, the way a `Canvas` names the text it draws.
    Text,
}

/// One field of a fixed-length record, at the byte it starts at.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RecordField {
    pub name: &'static str,
    pub offset: u16,
    pub ty: RecordFieldType,
}

/// A fixed-length record that travels inside a property's blob.
///
/// Codegen writes the Renderer's decoder from this, so the two sides read the same bytes
/// at the same offsets and nobody keeps a second copy of the layout by hand.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RecordSchema {
    /// The Kotlin class the decoder produces.
    pub name: &'static str,
    pub length: u16,
    pub fields: &'static [RecordField],
}

const fn field(name: &'static str, offset: u16, ty: RecordFieldType) -> RecordField {
    RecordField { name, offset, ty }
}

/// One decoration: a version, a kind, a severity and a colour role, a range, the id the
/// application gave it, and the text a lens or a suggestion shows. Bytes 10 and 11 are
/// reserved and written as zero.
pub const DECORATION_RECORD: RecordSchema = RecordSchema {
    name: "DecorationRecord",
    length: DECORATION_LEN as u16,
    fields: &[
        field("version", 0, RecordFieldType::U32),
        field(
            "kind",
            4,
            RecordFieldType::Role {
                name: "DecorationKind",
                optional: false,
            },
        ),
        field(
            "severity",
            6,
            RecordFieldType::Role {
                name: "Severity",
                optional: true,
            },
        ),
        field(
            "color",
            8,
            RecordFieldType::Role {
                name: "ColorRole",
                optional: true,
            },
        ),
        field("startLine", 12, RecordFieldType::U32),
        field("startColumn", 16, RecordFieldType::U32),
        field("endLine", 20, RecordFieldType::U32),
        field("endColumn", 24, RecordFieldType::U32),
        field("id", 28, RecordFieldType::U64),
        field("text", 36, RecordFieldType::Text),
    ],
};

/// One colour run of the syntax: a version, a range and the paint it is drawn in.
pub const SYNTAX_SPAN_RECORD: RecordSchema = RecordSchema {
    name: "SyntaxSpanRecord",
    length: SYNTAX_SPAN_LEN as u16,
    fields: &[
        field("version", 0, RecordFieldType::U32),
        field("startLine", 4, RecordFieldType::U32),
        field("startColumn", 8, RecordFieldType::U32),
        field("endLine", 12, RecordFieldType::U32),
        field("endColumn", 16, RecordFieldType::U32),
        field("paint", 20, RecordFieldType::Paint),
    ],
};

const fn hash_bytes(mut hash: u64, bytes: &[u8]) -> u64 {
    let mut index = 0;
    while index < bytes.len() {
        hash ^= bytes[index] as u64;
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
        index += 1;
    }
    hash
}

/// Folds a record layout into the schema hash, so a Renderer built against another
/// layout is refused at the handshake rather than reading the wrong bytes.
pub(crate) const fn hash_record_schema(mut hash: u64, schema: RecordSchema) -> u64 {
    hash = hash_bytes(hash, schema.name.as_bytes());
    hash = hash_bytes(hash, &schema.length.to_le_bytes());
    let mut index = 0;
    while index < schema.fields.len() {
        let field = schema.fields[index];
        hash = hash_bytes(hash, field.name.as_bytes());
        hash = hash_bytes(hash, &field.offset.to_le_bytes());
        hash = match field.ty {
            RecordFieldType::U32 => hash_bytes(hash, &[1]),
            RecordFieldType::U64 => hash_bytes(hash, &[2]),
            RecordFieldType::Role { name, optional } => {
                let hash = hash_bytes(hash, &[3, optional as u8]);
                hash_bytes(hash, name.as_bytes())
            }
            RecordFieldType::Paint => hash_bytes(hash, &[4]),
            RecordFieldType::Text => hash_bytes(hash, &[5]),
        };
        index += 1;
    }
    hash
}

/// A place in the document: a line and a column, both from zero, the column in UTF-16
/// code units.
#[derive(Clone, Copy, Debug, Default, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct Position {
    pub line: u32,
    pub column: u32,
}

impl Position {
    pub const fn new(line: u32, column: u32) -> Self {
        Self { line, column }
    }
}

/// A stretch of the document from `start` up to, and not including, `end`.
#[derive(Clone, Copy, Debug, Default, Eq, Hash, PartialEq)]
pub struct CodeRange {
    pub start: Position,
    pub end: Position,
}

impl CodeRange {
    pub const fn new(start: Position, end: Position) -> Self {
        Self { start, end }
    }

    /// The empty range at one place, which is where an insertion goes.
    pub const fn at(position: Position) -> Self {
        Self {
            start: position,
            end: position,
        }
    }

    /// A range given as four numbers, in the order a language server writes them.
    pub const fn of(start_line: u32, start_column: u32, end_line: u32, end_column: u32) -> Self {
        Self {
            start: Position::new(start_line, start_column),
            end: Position::new(end_line, end_column),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.start == self.end
    }
}

/// One thing drawn over the document, said as a range and never as a picture.
///
/// The colour of an underline is a role, never a literal, and is left to the design system
/// where it is not given; the shape of the mark, a squiggle or a line, and its weight are
/// always the design system's.
#[derive(Clone, Debug, PartialEq)]
pub struct Decoration {
    /// The document version the range was written against.
    pub version: u32,
    pub kind: DecorationKind,
    /// Only an underline has one.
    pub severity: Option<Severity>,
    /// The role an underline is drawn in. None leaves it to the design system, which picks
    /// one that suits the severity.
    pub color: Option<ColorRole>,
    pub range: CodeRange,
    /// The application's own name for this decoration, reported when it is pressed,
    /// accepted or hovered. Zero means it cannot be pressed and is never reported.
    pub id: u64,
    /// What a lens or a suggestion shows. Empty for the other two kinds.
    pub text: String,
}

impl Decoration {
    /// A diagnostic mark under a range.
    pub fn underline(version: u32, range: CodeRange, severity: Severity) -> Self {
        Self {
            version,
            kind: DecorationKind::Underline,
            severity: Some(severity),
            color: None,
            range,
            id: 0,
            text: String::new(),
        }
    }

    /// A short line of text shown above `line`, which reports `id` when pressed.
    pub fn code_lens(version: u32, line: u32, text: impl Into<String>, id: u64) -> Self {
        Self {
            version,
            kind: DecorationKind::CodeLens,
            severity: None,
            color: None,
            range: CodeRange::at(Position::new(line, 0)),
            id,
            text: text.into(),
        }
    }

    /// A range that reports `id` when a pointer comes to rest over it.
    pub fn hover_anchor(version: u32, range: CodeRange, id: u64) -> Self {
        Self {
            version,
            kind: DecorationKind::HoverAnchor,
            severity: None,
            color: None,
            range,
            id,
            text: String::new(),
        }
    }

    /// A suggestion shown faintly at `at`. Accepting it inserts `text` there as an
    /// ordinary edit and then reports `id`.
    pub fn ghost_text(version: u32, at: Position, text: impl Into<String>, id: u64) -> Self {
        Self {
            version,
            kind: DecorationKind::GhostText,
            severity: None,
            color: None,
            range: CodeRange::at(at),
            id,
            text: text.into(),
        }
    }

    /// Draws an underline in this role rather than the one the design system would pick.
    pub fn with_color(mut self, role: ColorRole) -> Self {
        self.color = Some(role);
        self
    }

    pub fn with_id(mut self, id: u64) -> Self {
        self.id = id;
        self
    }
}

fn put_u16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn put_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn put_u64(out: &mut Vec<u8>, value: u64) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn put_range(out: &mut Vec<u8>, range: CodeRange) {
    put_u32(out, range.start.line);
    put_u32(out, range.start.column);
    put_u32(out, range.end.line);
    put_u32(out, range.end.column);
}

fn word(record: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([record[at], record[at + 1], record[at + 2], record[at + 3]])
}

fn half(record: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([record[at], record[at + 1]])
}

fn long(record: &[u8], at: usize) -> u64 {
    u64::from(word(record, at)) | (u64::from(word(record, at + 4)) << 32)
}

fn range_at(record: &[u8], at: usize) -> CodeRange {
    CodeRange::of(
        word(record, at),
        word(record, at + 4),
        word(record, at + 8),
        word(record, at + 12),
    )
}

/// The decorations over one editor, encoded and ready to travel.
///
/// Records first and the text they show after them, so the records stay fixed-length.
/// Shared rather than copied, so the same list given again compares equal and costs no
/// record at all.
#[derive(Clone, Default, PartialEq)]
pub struct Decorations {
    bytes: Option<Rc<[u8]>>,
}

impl std::fmt::Debug for Decorations {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("Decorations")
            .field("decorations", &self.decorations().len())
            .finish()
    }
}

impl Decorations {
    pub fn new(decorations: impl IntoIterator<Item = Decoration>) -> Self {
        let decorations: Vec<Decoration> = decorations.into_iter().collect();
        let records_len = decorations.len() * DECORATION_LEN;
        let mut bytes = Vec::with_capacity(records_len);
        let mut texts = Vec::new();
        for decoration in &decorations {
            put_u32(&mut bytes, decoration.version);
            put_u16(&mut bytes, decoration.kind as u16);
            put_u16(
                &mut bytes,
                decoration.severity.map_or(0, |value| value as u16),
            );
            put_u16(&mut bytes, decoration.color.map_or(0, |value| value as u16));
            put_u16(&mut bytes, 0);
            put_range(&mut bytes, decoration.range);
            put_u64(&mut bytes, decoration.id);
            // An empty text points at the end of the records, which is where the text
            // region starts, so a reader never sees an offset into the records themselves.
            put_u32(&mut bytes, (records_len + texts.len()) as u32);
            put_u32(&mut bytes, decoration.text.len() as u32);
            texts.extend_from_slice(decoration.text.as_bytes());
        }
        bytes.extend_from_slice(&texts);
        Self::from_bytes(bytes)
    }

    /// Adopts an already encoded list, which is what the protocol vectors carry.
    pub fn from_bytes(bytes: impl Into<Rc<[u8]>>) -> Self {
        let bytes = bytes.into();
        Self {
            bytes: (!bytes.is_empty()).then_some(bytes),
        }
    }

    pub fn as_bytes(&self) -> &[u8] {
        self.bytes.as_deref().unwrap_or(&[])
    }

    pub fn is_empty(&self) -> bool {
        self.bytes.is_none()
    }

    /// The decorations this list holds, read back.
    ///
    /// The records end where the text region begins, which is the first offset any record
    /// names for its text, or the end of the blob where none names one. A record whose
    /// kind, severity or colour is not one this side knows is skipped, which is what the
    /// Renderer does with it too.
    pub fn decorations(&self) -> Vec<Decoration> {
        let bytes = self.as_bytes();
        let mut end = bytes.len();
        let mut at = 0;
        let mut found = Vec::new();
        while at + DECORATION_LEN <= end {
            let record = &bytes[at..at + DECORATION_LEN];
            at += DECORATION_LEN;
            let text_offset = word(record, 36) as usize;
            let text_len = word(record, 40) as usize;
            if text_len > 0 {
                end = end.min(text_offset);
            }
            let Ok(kind) = DecorationKind::try_from(half(record, 4)) else {
                continue;
            };
            let severity = match half(record, 6) {
                0 => None,
                tag => match Severity::try_from(tag) {
                    Ok(severity) => Some(severity),
                    Err(()) => continue,
                },
            };
            let color = match half(record, 8) {
                0 => None,
                tag => match ColorRole::try_from(tag) {
                    Ok(role) => Some(role),
                    Err(()) => continue,
                },
            };
            let text = if text_len == 0 {
                String::new()
            } else {
                match bytes
                    .get(text_offset..text_offset + text_len)
                    .and_then(|run| std::str::from_utf8(run).ok())
                {
                    Some(text) => text.to_owned(),
                    None => continue,
                }
            };
            found.push(Decoration {
                version: word(record, 0),
                kind,
                severity,
                color,
                range: range_at(record, 12),
                id: long(record, 28),
                text,
            });
        }
        found
    }
}

impl dioxus_core::IntoAttributeValue for Decorations {
    fn into_value(self) -> dioxus_core::AttributeValue {
        dioxus_core::AttributeValue::any_value(self)
    }
}

/// One colour run of the syntax.
///
/// A paint rather than a colour, and in practice a role: the code colour roles are what a
/// highlighter on this side maps its token kinds onto, so the same run comes out in the
/// right colour under every design system and in both light and dark.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SyntaxSpan {
    pub version: u32,
    pub range: CodeRange,
    pub paint: Paint,
}

impl SyntaxSpan {
    pub const fn new(version: u32, range: CodeRange, paint: Paint) -> Self {
        Self {
            version,
            range,
            paint,
        }
    }

    /// A run drawn in one colour role.
    pub const fn role(version: u32, range: CodeRange, role: ColorRole) -> Self {
        Self::new(version, range, Paint::Role(role))
    }
}

/// The colour runs of one editor's syntax, encoded and ready to travel.
#[derive(Clone, Default, PartialEq)]
pub struct SyntaxSpans {
    bytes: Option<Rc<[u8]>>,
}

impl std::fmt::Debug for SyntaxSpans {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("SyntaxSpans")
            .field("spans", &(self.as_bytes().len() / SYNTAX_SPAN_LEN))
            .finish()
    }
}

impl SyntaxSpans {
    pub fn new(spans: impl IntoIterator<Item = SyntaxSpan>) -> Self {
        let mut bytes = Vec::new();
        for span in spans {
            put_u32(&mut bytes, span.version);
            put_range(&mut bytes, span.range);
            put_u64(&mut bytes, span.paint.to_bits());
        }
        Self::from_bytes(bytes)
    }

    pub fn from_bytes(bytes: impl Into<Rc<[u8]>>) -> Self {
        let bytes = bytes.into();
        Self {
            bytes: (!bytes.is_empty()).then_some(bytes),
        }
    }

    pub fn as_bytes(&self) -> &[u8] {
        self.bytes.as_deref().unwrap_or(&[])
    }

    pub fn is_empty(&self) -> bool {
        self.bytes.is_none()
    }

    /// The runs this list holds, read back. A run whose paint this side cannot read is
    /// skipped.
    pub fn spans(&self) -> Vec<SyntaxSpan> {
        self.as_bytes()
            .chunks_exact(SYNTAX_SPAN_LEN)
            .filter_map(|record| {
                Some(SyntaxSpan {
                    version: word(record, 0),
                    range: range_at(record, 4),
                    paint: Paint::from_bits(long(record, 20))?,
                })
            })
            .collect()
    }
}

impl dioxus_core::IntoAttributeValue for SyntaxSpans {
    fn into_value(self) -> dioxus_core::AttributeValue {
        dioxus_core::AttributeValue::any_value(self)
    }
}

/// Where a position falls in `text`, as a byte offset, or None where the document has no
/// such place: a line past the last one, a column past the end of its line, or a column
/// that would split a character written as two UTF-16 units.
pub fn byte_offset(text: &str, position: Position) -> Option<usize> {
    let bytes = text.as_bytes();
    let mut start = 0;
    for _ in 0..position.line {
        let at = bytes[start..]
            .iter()
            .position(|byte| *byte == b'\n' || *byte == b'\r')?
            + start;
        start = if bytes[at] == b'\r' && bytes.get(at + 1) == Some(&b'\n') {
            at + 2
        } else {
            at + 1
        };
    }
    let end = bytes[start..]
        .iter()
        .position(|byte| *byte == b'\n' || *byte == b'\r')
        .map_or(bytes.len(), |offset| start + offset);
    let mut units = 0_u32;
    let mut offset = start;
    for character in text[start..end].chars() {
        if units == position.column {
            return Some(offset);
        }
        units += character.len_utf16() as u32;
        offset += character.len_utf8();
        if units > position.column {
            return None;
        }
    }
    (units == position.column).then_some(offset)
}

/// One committed edit, as the Renderer reported it.
///
/// `range` is in the document as it stood before the edit, and `version` is the version
/// the edit produced. The shape is a language server's `TextDocumentContentChangeEvent`,
/// so forwarding one is a matter of copying four numbers and a string.
#[derive(Clone, Debug, PartialEq)]
pub struct CodeChange {
    pub version: u32,
    pub range: CodeRange,
    pub text: String,
}

impl CodeChange {
    /// Applies this edit to a copy of the document the Host keeps.
    ///
    /// Applying every change in the order they arrived, starting from the text the editor
    /// was opened with, gives the text the reader sees. Returns false, and leaves `text`
    /// alone, where the range does not fit the document, which means the copy has fallen
    /// out of step with the editor.
    pub fn apply_to(&self, text: &mut String) -> bool {
        let (Some(start), Some(end)) = (
            byte_offset(text, self.range.start),
            byte_offset(text, self.range.end),
        ) else {
            return false;
        };
        if start > end {
            return false;
        }
        text.replace_range(start..end, &self.text);
        true
    }
}

/// A Host edit the Renderer did not apply, because the reader had changed the same place
/// since the version the edit was written against.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct EditRejected {
    /// The id the application gave the edit.
    pub request_id: u32,
    pub base_version: u32,
    /// The version the document had reached when the edit arrived.
    pub current_version: u32,
    /// The range exactly as it was sent.
    pub range: CodeRange,
}

/// A pointer came to rest over a place in the editor, or left it.
///
/// How long it has to rest is the design system's, the same kind of delay a tooltip
/// waits, and nothing is sent again while it stays. What to show is the application's,
/// drawn with the overlay widgets it already has.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CodeHover {
    /// The id of the hover anchor under the pointer, or None where there is none.
    pub decoration: Option<u64>,
    pub position: Position,
    pub phase: HoverPhase,
}

/// The reader pressed the save shortcut: Command S on macOS, Control S elsewhere.
///
/// Writing the file is this side's job, on a worker, never on the thread the editor runs
/// on.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SaveRequest {
    /// The version of the document the reader saw when they pressed it.
    pub version: u32,
}

/// One edit asked of an editor, waiting for the batch it goes out in.
pub(crate) struct PendingEdit {
    pub(crate) token: u32,
    pub(crate) request_id: u32,
    pub(crate) base_version: u32,
    pub(crate) range: CodeRange,
    pub(crate) text: String,
}

thread_local! {
    static EDITS: RefCell<Vec<PendingEdit>> = const { RefCell::new(Vec::new()) };
    static NEXT_TOKEN: Cell<u32> = const { Cell::new(1) };
}

/// A way to change the document an editor holds, for formatting, accepting a completion
/// or reloading a file that changed underneath it.
///
/// Created with [`use_code_editor`] and handed to the editor as its `handle`. An edit is
/// written against a version, the one the application last heard of. Where the reader has
/// typed since, the Renderer moves the range along with what they typed; where they typed
/// in the same place, it refuses the edit and says so through `on_edit_rejected`, because
/// an application must never type over its reader. An edit that is applied comes back as
/// an ordinary change, so the version stays one sequence whoever made the edit.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct CodeEditorHandle {
    token: u32,
}

impl CodeEditorHandle {
    /// A handle attached to no editor yet. [`use_code_editor`] is the usual way to get one,
    /// because it keeps the same handle across renders.
    #[allow(clippy::new_without_default)]
    pub fn new() -> Self {
        let token = NEXT_TOKEN.with(|next| {
            let token = next.get();
            next.set(token.checked_add(1).unwrap_or(1));
            token
        });
        Self { token }
    }

    pub(crate) fn token(self) -> u32 {
        self.token
    }

    /// Replaces `range` of the document as it stood at `base_version` with `text`.
    ///
    /// It goes out with the batch the current call produces, so call it from a component,
    /// an event handler or a task on the thread the application runs on. A worker updates
    /// a signal instead, and the component that reads the signal calls this.
    ///
    /// `request_id` is the application's own name for the edit, returned if it is refused.
    pub fn edit(
        &self,
        request_id: u32,
        base_version: u32,
        range: CodeRange,
        text: impl Into<String>,
    ) {
        let edit = PendingEdit {
            token: self.token,
            request_id,
            base_version,
            range,
            text: text.into(),
        };
        EDITS.with_borrow_mut(|edits| edits.push(edit));
    }
}

/// A handle to the editor this component draws, the same one on every render.
pub fn use_code_editor() -> CodeEditorHandle {
    dioxus_core::use_hook(CodeEditorHandle::new)
}

/// Hands every queued edit to `emit` and empties the queue.
pub(crate) fn drain_edits(mut emit: impl FnMut(&PendingEdit)) {
    let mut taken = EDITS.with_borrow_mut(std::mem::take);
    if taken.is_empty() {
        return;
    }
    for edit in &taken {
        emit(edit);
    }
    taken.clear();
    EDITS.with_borrow_mut(|edits| {
        if edits.is_empty() {
            *edits = taken;
        }
    });
}

/// Forgets every queued edit. Used when a new `Host` takes over the thread, because an
/// edit addressed to the old one's editor names nothing in the new one.
pub(crate) fn reset_edits() {
    EDITS.with_borrow_mut(Vec::clear);
}
