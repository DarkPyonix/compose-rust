//! Editable rich text: what a `TextField` with `rich` on keeps, sends and is told.
//!
//! The Renderer owns the formatting of a field it is editing, the way it owns the text: a
//! letter typed inside a bold stretch has to stay bold without a trip to the Host. So the
//! Host does not send a field's runs while it is edited. It sends commands about the
//! selection ([`Format`]), and it hears the formatting at the selection
//! ([`TextFormat`]) and, when the user pauses, the whole document ([`RichDocument`]).
//!
//! A document is the text, the character runs and the paragraph formats. Every offset is a
//! byte offset into the UTF-8 text, as the runs of a `Text` are. Both lists are sparse: a run
//! is only there where the text is not plain, and a paragraph only where it is not an
//! ordinary body paragraph, so a plain document is its text and two empty lists.

use crate::schema::{FormatOp, ListKind, Paint, ParagraphRole};
use std::fmt;

/// Bytes of one character run record.
pub const CHARACTER_RUN_LEN: usize = 24;
/// Bytes of one paragraph record.
pub const PARAGRAPH_LEN: usize = 16;
/// The deepest indent a paragraph can have.
pub const MAX_INDENT: u8 = 8;

const FLAG_BOLD: u32 = 1;
const FLAG_ITALIC: u32 = 2;
const FLAG_UNDERLINE: u32 = 4;
const FLAG_STRIKETHROUGH: u32 = 8;
const KNOWN_FLAGS: u32 = FLAG_BOLD | FLAG_ITALIC | FLAG_UNDERLINE | FLAG_STRIKETHROUGH;

/// What is wrong with a document or a record of it. Every reader of the wire turns one of
/// these into a protocol error and goes on, and none of them is a panic.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RichTextError {
    /// A list of records whose length is not a whole number of them.
    NotWholeRecords { list: &'static str, len: usize },
    /// A run that is empty, or that ends past the text.
    RunOutOfRange { index: usize },
    /// A run that starts before the one in front of it ends, or out of order.
    RunsOverlap { index: usize },
    /// A run that starts or ends inside a character.
    RunSplitsCharacter { index: usize },
    /// A run that has no style and no colour, so it says nothing.
    RunSaysNothing { index: usize },
    /// A run with a style bit this reader does not know.
    UnknownStyleBits { index: usize, flags: u32 },
    /// A reserved word that is not zero.
    ReservedNotZero { list: &'static str, index: usize },
    /// A paint that is not a colour.
    InvalidPaint { bits: u64 },
    /// A paragraph that does not start a paragraph: not the start of the text and not just
    /// after a line break.
    ParagraphNotAtStart { index: usize },
    /// A paragraph that starts at or before the one in front of it.
    ParagraphsUnordered { index: usize },
    /// A paragraph that is an ordinary body paragraph, so it says nothing.
    ParagraphSaysNothing { index: usize },
    /// A paragraph role, list kind or quote flag this reader does not know.
    UnknownParagraphValue {
        index: usize,
        field: &'static str,
        value: u16,
    },
    /// An indent past [`MAX_INDENT`].
    IndentTooDeep { index: usize, indent: u16 },
    /// A `FormatText` whose operation, value or paint is not one this reader knows.
    InvalidFormat { op: u16, value: u16 },
    /// A selection state with a word this reader does not know.
    InvalidTextFormat { field: &'static str, value: u64 },
}

impl fmt::Display for RichTextError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NotWholeRecords { list, len } => {
                write!(
                    f,
                    "the {list} list is {len} bytes, which is not a whole number of records"
                )
            }
            Self::RunOutOfRange { index } => {
                write!(f, "character run {index} is empty or ends past the text")
            }
            Self::RunsOverlap { index } => {
                write!(
                    f,
                    "character run {index} starts before the run in front of it ends"
                )
            }
            Self::RunSplitsCharacter { index } => {
                write!(f, "character run {index} starts or ends inside a character")
            }
            Self::RunSaysNothing { index } => {
                write!(f, "character run {index} has no style and no colour")
            }
            Self::UnknownStyleBits { index, flags } => {
                write!(
                    f,
                    "character run {index} has style bits {flags:#x} this reader does not know"
                )
            }
            Self::ReservedNotZero { list, index } => {
                write!(
                    f,
                    "record {index} of the {list} list has a reserved word that is not zero"
                )
            }
            Self::InvalidPaint { bits } => write!(f, "{bits:#x} is not a paint"),
            Self::ParagraphNotAtStart { index } => {
                write!(
                    f,
                    "paragraph {index} does not start where a paragraph starts"
                )
            }
            Self::ParagraphsUnordered { index } => {
                write!(
                    f,
                    "paragraph {index} starts at or before the paragraph in front of it"
                )
            }
            Self::ParagraphSaysNothing { index } => {
                write!(f, "paragraph {index} is an ordinary body paragraph")
            }
            Self::UnknownParagraphValue {
                index,
                field,
                value,
            } => {
                write!(f, "paragraph {index} has {field} {value}, which is not one")
            }
            Self::IndentTooDeep { index, indent } => {
                write!(
                    f,
                    "paragraph {index} is indented {indent} levels, the most is {MAX_INDENT}"
                )
            }
            Self::InvalidFormat { op, value } => {
                write!(f, "format operation {op} with value {value} is not one")
            }
            Self::InvalidTextFormat { field, value } => {
                write!(
                    f,
                    "the selection format has {field} {value}, which is not one"
                )
            }
        }
    }
}

impl std::error::Error for RichTextError {}

/// One stretch of text with a character style and, perhaps, a colour.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CharacterRun {
    /// Byte offset of the first character.
    pub start: u32,
    /// Length in bytes.
    pub len: u32,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub strikethrough: bool,
    /// The text colour, or none for the field's own.
    pub color: Option<Paint>,
}

impl CharacterRun {
    /// A run with no style and no colour yet, over `start..start + len`.
    pub const fn new(start: u32, len: u32) -> Self {
        Self {
            start,
            len,
            bold: false,
            italic: false,
            underline: false,
            strikethrough: false,
            color: None,
        }
    }

    fn flags(&self) -> u32 {
        (u32::from(self.bold) * FLAG_BOLD)
            | (u32::from(self.italic) * FLAG_ITALIC)
            | (u32::from(self.underline) * FLAG_UNDERLINE)
            | (u32::from(self.strikethrough) * FLAG_STRIKETHROUGH)
    }

    fn says_something(&self) -> bool {
        self.flags() != 0 || self.color.is_some()
    }
}

/// What a paragraph is. A paragraph is the text between two line breaks.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ParagraphFormat {
    /// Byte offset of the paragraph's first character: 0, or just after a `'\n'`.
    pub start: u32,
    pub role: ParagraphRole,
    pub list: ListKind,
    /// Levels of indent, 0 to [`MAX_INDENT`].
    pub indent: u8,
    pub quote: bool,
}

impl ParagraphFormat {
    /// An ordinary body paragraph starting at `start`.
    pub const fn body(start: u32) -> Self {
        Self {
            start,
            role: ParagraphRole::Body,
            list: ListKind::None,
            indent: 0,
            quote: false,
        }
    }

    fn is_default(&self) -> bool {
        self.role == ParagraphRole::Body
            && self.list == ListKind::None
            && self.indent == 0
            && !self.quote
    }
}

/// A rich text field's whole document.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct RichDocument {
    pub text: String,
    /// The runs that are not plain, in order, without overlap.
    pub characters: Vec<CharacterRun>,
    /// The paragraphs that are not ordinary body paragraphs, in order.
    pub paragraphs: Vec<ParagraphFormat>,
}

impl RichDocument {
    /// A document that is `text` and nothing else.
    pub fn plain(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            characters: Vec::new(),
            paragraphs: Vec::new(),
        }
    }

    /// Checks every rule of the wire against this document: runs ascending, not
    /// overlapping, not empty, inside the text, on character boundaries and each saying
    /// something; paragraphs ascending, each at the start of a paragraph and each not an
    /// ordinary body paragraph; indents no deeper than [`MAX_INDENT`].
    pub fn validate(&self) -> Result<(), RichTextError> {
        let len = self.text.len();
        let mut end_of_last = 0usize;
        for (index, run) in self.characters.iter().enumerate() {
            let start = run.start as usize;
            let end = start
                .checked_add(run.len as usize)
                .filter(|end| run.len > 0 && *end <= len)
                .ok_or(RichTextError::RunOutOfRange { index })?;
            if start < end_of_last {
                return Err(RichTextError::RunsOverlap { index });
            }
            if !self.text.is_char_boundary(start) || !self.text.is_char_boundary(end) {
                return Err(RichTextError::RunSplitsCharacter { index });
            }
            if !run.says_something() {
                return Err(RichTextError::RunSaysNothing { index });
            }
            end_of_last = end;
        }
        let bytes = self.text.as_bytes();
        let mut last: Option<usize> = None;
        for (index, paragraph) in self.paragraphs.iter().enumerate() {
            let start = paragraph.start as usize;
            if last.is_some_and(|last| start <= last) {
                return Err(RichTextError::ParagraphsUnordered { index });
            }
            if start > len || (start != 0 && bytes[start - 1] != b'\n') {
                return Err(RichTextError::ParagraphNotAtStart { index });
            }
            if paragraph.indent > MAX_INDENT {
                return Err(RichTextError::IndentTooDeep {
                    index,
                    indent: u16::from(paragraph.indent),
                });
            }
            if paragraph.is_default() {
                return Err(RichTextError::ParagraphSaysNothing { index });
            }
            last = Some(start);
        }
        Ok(())
    }

    /// The character runs as the wire carries them, [`CHARACTER_RUN_LEN`] bytes each.
    pub fn encode_characters(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.characters.len() * CHARACTER_RUN_LEN);
        for run in &self.characters {
            out.extend_from_slice(&run.start.to_le_bytes());
            out.extend_from_slice(&run.len.to_le_bytes());
            out.extend_from_slice(&run.flags().to_le_bytes());
            out.extend_from_slice(&run.color.map_or(0, Paint::to_bits).to_le_bytes());
            out.extend_from_slice(&0u32.to_le_bytes());
        }
        out
    }

    /// The paragraph formats as the wire carries them, [`PARAGRAPH_LEN`] bytes each.
    pub fn encode_paragraphs(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.paragraphs.len() * PARAGRAPH_LEN);
        for paragraph in &self.paragraphs {
            out.extend_from_slice(&paragraph.start.to_le_bytes());
            out.extend_from_slice(&u16::from(paragraph.role).to_le_bytes());
            out.extend_from_slice(&u16::from(paragraph.list).to_le_bytes());
            out.extend_from_slice(&u16::from(paragraph.indent).to_le_bytes());
            out.extend_from_slice(&u16::from(paragraph.quote).to_le_bytes());
            out.extend_from_slice(&0u32.to_le_bytes());
        }
        out
    }

    /// Reads the records [`RichDocument::encode_characters`] wrote. It checks each record
    /// on its own (whole records, reserved words, known style bits, a real paint); the
    /// rules that need the text are [`RichDocument::validate`]'s.
    pub fn decode_characters(bytes: &[u8]) -> Result<Vec<CharacterRun>, RichTextError> {
        if bytes.len() % CHARACTER_RUN_LEN != 0 {
            return Err(RichTextError::NotWholeRecords {
                list: "character run",
                len: bytes.len(),
            });
        }
        let mut runs = Vec::with_capacity(bytes.len() / CHARACTER_RUN_LEN);
        for (index, record) in bytes.chunks_exact(CHARACTER_RUN_LEN).enumerate() {
            let flags = word(record, 8);
            if flags & !KNOWN_FLAGS != 0 {
                return Err(RichTextError::UnknownStyleBits { index, flags });
            }
            if word(record, 20) != 0 {
                return Err(RichTextError::ReservedNotZero {
                    list: "character run",
                    index,
                });
            }
            let paint = u64::from(word(record, 12)) | (u64::from(word(record, 16)) << 32);
            let color = if paint == 0 {
                None
            } else {
                Some(Paint::from_bits(paint).ok_or(RichTextError::InvalidPaint { bits: paint })?)
            };
            runs.push(CharacterRun {
                start: word(record, 0),
                len: word(record, 4),
                bold: flags & FLAG_BOLD != 0,
                italic: flags & FLAG_ITALIC != 0,
                underline: flags & FLAG_UNDERLINE != 0,
                strikethrough: flags & FLAG_STRIKETHROUGH != 0,
                color,
            });
        }
        Ok(runs)
    }

    /// Reads the records [`RichDocument::encode_paragraphs`] wrote, with the same limits as
    /// [`RichDocument::decode_characters`].
    pub fn decode_paragraphs(bytes: &[u8]) -> Result<Vec<ParagraphFormat>, RichTextError> {
        if bytes.len() % PARAGRAPH_LEN != 0 {
            return Err(RichTextError::NotWholeRecords {
                list: "paragraph",
                len: bytes.len(),
            });
        }
        let mut paragraphs = Vec::with_capacity(bytes.len() / PARAGRAPH_LEN);
        for (index, record) in bytes.chunks_exact(PARAGRAPH_LEN).enumerate() {
            let half = |at: usize| u16::from_le_bytes([record[at], record[at + 1]]);
            if word(record, 12) != 0 {
                return Err(RichTextError::ReservedNotZero {
                    list: "paragraph",
                    index,
                });
            }
            let role = ParagraphRole::try_from(half(4)).map_err(|()| {
                RichTextError::UnknownParagraphValue {
                    index,
                    field: "role",
                    value: half(4),
                }
            })?;
            let list =
                ListKind::try_from(half(6)).map_err(|()| RichTextError::UnknownParagraphValue {
                    index,
                    field: "list",
                    value: half(6),
                })?;
            let indent = half(8);
            if indent > u16::from(MAX_INDENT) {
                return Err(RichTextError::IndentTooDeep { index, indent });
            }
            if half(10) > 1 {
                return Err(RichTextError::UnknownParagraphValue {
                    index,
                    field: "quote",
                    value: half(10),
                });
            }
            paragraphs.push(ParagraphFormat {
                start: word(record, 0),
                role,
                list,
                indent: indent as u8,
                quote: half(10) == 1,
            });
        }
        Ok(paragraphs)
    }

    /// Reads and checks a document as the wire carries it: the text and the two lists of
    /// records. This is what every reader of a `SetRichText` or a `RichTextChanged` runs,
    /// so a record one side refuses is refused by the other.
    pub fn from_wire(
        text: &str,
        characters: &[u8],
        paragraphs: &[u8],
    ) -> Result<Self, RichTextError> {
        let document = Self {
            text: text.to_owned(),
            characters: Self::decode_characters(characters)?,
            paragraphs: Self::decode_paragraphs(paragraphs)?,
        };
        document.validate()?;
        Ok(document)
    }

    /// The document a `RichTextChanged` event carries, or none for any other event, or for
    /// one whose records do not hold together.
    pub fn from_payload(payload: &crate::schema::EventPayload<'_>) -> Option<Self> {
        match payload {
            crate::schema::EventPayload::RichTextChanged {
                text,
                characters,
                paragraphs,
            } => Self::from_wire(text, characters, paragraphs).ok(),
            _ => None,
        }
    }
}

/// Checks the three parts of a rich document as the wire carries them, without keeping
/// what it read.
pub(crate) fn validate_wire(
    text: &str,
    characters: &[u8],
    paragraphs: &[u8],
) -> Result<(), RichTextError> {
    RichDocument::from_wire(text, characters, paragraphs).map(drop)
}

fn word(record: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([record[at], record[at + 1], record[at + 2], record[at + 3]])
}

// ----- the formatting at the selection ---------------------------------------------------

/// Whether a style holds for none, all or only part of the selection.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum Tri {
    #[default]
    None,
    All,
    Mixed,
}

impl Tri {
    fn bits(self) -> u32 {
        match self {
            Self::None => 0,
            Self::All => 1,
            Self::Mixed => 2,
        }
    }

    fn from_bits(bits: u32) -> Option<Self> {
        match bits {
            0 => Some(Self::None),
            1 => Some(Self::All),
            2 => Some(Self::Mixed),
            _ => None,
        }
    }
}

/// The formatting at a rich field's selection, which is what a format sheet shows. A
/// caret is a selection of nothing, and then it is the formatting the next letter takes.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TextFormat {
    pub bold: Tri,
    pub italic: Tri,
    pub underline: Tri,
    pub strikethrough: Tri,
    /// The colour all of the selection shares, or none where it has the field's own or
    /// where the colours differ (see `color_mixed`).
    pub color: Option<Paint>,
    /// Whether the selection has more than one colour.
    pub color_mixed: bool,
    /// The paragraph role, or none where the paragraphs differ.
    pub role: Option<ParagraphRole>,
    /// The list kind, or none where the paragraphs differ.
    pub list: Option<ListKind>,
    /// The indent, or none where the paragraphs differ.
    pub indent: Option<u8>,
    pub quote: Tri,
}

impl Default for TextFormat {
    /// A caret in an ordinary body paragraph with nothing set.
    fn default() -> Self {
        Self {
            bold: Tri::None,
            italic: Tri::None,
            underline: Tri::None,
            strikethrough: Tri::None,
            color: None,
            color_mixed: false,
            role: Some(ParagraphRole::Body),
            list: Some(ListKind::None),
            indent: Some(0),
            quote: Tri::None,
        }
    }
}

const COLOR_MIXED_BIT: u32 = 1 << 8;
/// The word a mixed paragraph role, list kind or indent is sent as.
const MIXED: u16 = 0xFFFF;

/// A [`TextFormat`] as its wire words, which `TextFormatAtSelection` carries.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TextFormatWire {
    /// Two bits for each of bold, italic, underline and strikethrough from the lowest bit:
    /// 0 none, 1 all, 2 mixed. Bit 8 is set where the colour is mixed.
    pub flags: u32,
    /// 0 where the roles differ, else a `ParagraphRole`.
    pub role: u16,
    /// 0xFFFF where the list kinds differ, else a `ListKind`.
    pub list: u16,
    /// 0xFFFF where the indents differ, else 0 to 8.
    pub indent: u16,
    /// 0 none, 1 all, 2 mixed.
    pub quote: u16,
    /// The shared colour's paint word, 0 for none or mixed.
    pub paint: u64,
}

impl TextFormat {
    /// The wire words for this state, or the reason one cannot be sent.
    pub fn to_wire(&self) -> Result<TextFormatWire, RichTextError> {
        let flags = self.bold.bits()
            | (self.italic.bits() << 2)
            | (self.underline.bits() << 4)
            | (self.strikethrough.bits() << 6)
            | if self.color_mixed { COLOR_MIXED_BIT } else { 0 };
        let indent = match self.indent {
            None => MIXED,
            Some(indent) if indent <= MAX_INDENT => u16::from(indent),
            Some(indent) => {
                return Err(RichTextError::InvalidTextFormat {
                    field: "indent",
                    value: u64::from(indent),
                });
            }
        };
        Ok(TextFormatWire {
            flags,
            role: self.role.map_or(0, u16::from),
            list: self.list.map_or(MIXED, u16::from),
            indent,
            quote: self.quote.bits() as u16,
            paint: if self.color_mixed {
                0
            } else {
                self.color.map_or(0, Paint::to_bits)
            },
        })
    }

    /// Reads wire words back, refusing a word that is not one.
    pub fn from_wire(wire: TextFormatWire) -> Result<Self, RichTextError> {
        let bad =
            |field: &'static str, value: u64| RichTextError::InvalidTextFormat { field, value };
        if wire.flags >> 9 != 0 {
            return Err(bad("flags", u64::from(wire.flags)));
        }
        let tri = |shift: u32| {
            Tri::from_bits((wire.flags >> shift) & 3)
                .ok_or_else(|| bad("flags", u64::from(wire.flags)))
        };
        let color_mixed = wire.flags & COLOR_MIXED_BIT != 0;
        let color = if wire.paint == 0 {
            None
        } else {
            Some(
                Paint::from_bits(wire.paint)
                    .ok_or(RichTextError::InvalidPaint { bits: wire.paint })?,
            )
        };
        if color_mixed && color.is_some() {
            return Err(bad("paint", wire.paint));
        }
        let role = match wire.role {
            0 => None,
            role => Some(ParagraphRole::try_from(role).map_err(|()| bad("role", u64::from(role)))?),
        };
        let list = match wire.list {
            MIXED => None,
            list => Some(ListKind::try_from(list).map_err(|()| bad("list", u64::from(list)))?),
        };
        let indent = match wire.indent {
            MIXED => None,
            indent if indent <= u16::from(MAX_INDENT) => Some(indent as u8),
            indent => return Err(bad("indent", u64::from(indent))),
        };
        let quote = Tri::from_bits(u32::from(wire.quote))
            .ok_or_else(|| bad("quote", u64::from(wire.quote)))?;
        Ok(Self {
            bold: tri(0)?,
            italic: tri(2)?,
            underline: tri(4)?,
            strikethrough: tri(6)?,
            color,
            color_mixed,
            role,
            list,
            indent,
            quote,
        })
    }

    /// The format a `TextFormatAtSelection` event carries, or none for any other event.
    pub fn from_payload(payload: &crate::schema::EventPayload<'_>) -> Option<Self> {
        match payload {
            crate::schema::EventPayload::TextFormatAtSelection(format) => Some(*format),
            _ => None,
        }
    }
}

// ----- commands --------------------------------------------------------------------------

/// What a style command does to the selection.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Toggle {
    Off,
    On,
    /// On where the selection is not entirely on, off where it is.
    Flip,
}

impl Toggle {
    fn value(self) -> u16 {
        match self {
            Self::Off => 0,
            Self::On => 1,
            Self::Flip => 2,
        }
    }

    fn from_value(value: u16) -> Option<Self> {
        match value {
            0 => Some(Self::Off),
            1 => Some(Self::On),
            2 => Some(Self::Flip),
            _ => None,
        }
    }
}

/// One formatting command for the selection of a rich field. With nothing selected a
/// character style or a colour sets what the next letter takes; the paragraph commands
/// apply to every paragraph the selection touches.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Format {
    Bold(Toggle),
    Italic(Toggle),
    Underline(Toggle),
    Strikethrough(Toggle),
    /// A colour, or none to go back to the field's own.
    Color(Option<Paint>),
    Paragraph(ParagraphRole),
    List(ListKind),
    Indent,
    Outdent,
    Quote(Toggle),
}

impl Format {
    /// The operation, value and paint word of the `FormatText` command this is.
    pub fn to_command(self) -> (FormatOp, u16, Option<Paint>) {
        match self {
            Self::Bold(toggle) => (FormatOp::Bold, toggle.value(), None),
            Self::Italic(toggle) => (FormatOp::Italic, toggle.value(), None),
            Self::Underline(toggle) => (FormatOp::Underline, toggle.value(), None),
            Self::Strikethrough(toggle) => (FormatOp::Strikethrough, toggle.value(), None),
            Self::Color(paint) => (FormatOp::Color, 0, paint),
            Self::Paragraph(role) => (FormatOp::Paragraph, u16::from(role), None),
            Self::List(kind) => (FormatOp::List, u16::from(kind), None),
            Self::Indent => (FormatOp::Indent, 0, None),
            Self::Outdent => (FormatOp::Outdent, 0, None),
            Self::Quote(toggle) => (FormatOp::Quote, toggle.value(), None),
        }
    }

    /// Reads a command back, refusing a value the operation does not take and a paint on
    /// an operation that is not `Color`.
    pub fn from_command(
        op: FormatOp,
        value: u16,
        paint: Option<Paint>,
    ) -> Result<Self, RichTextError> {
        let bad = RichTextError::InvalidFormat {
            op: u16::from(op),
            value,
        };
        if paint.is_some() && op != FormatOp::Color {
            return Err(bad);
        }
        let toggle = || Toggle::from_value(value).ok_or_else(|| bad.clone());
        let zero = || if value == 0 { Ok(()) } else { Err(bad.clone()) };
        Ok(match op {
            FormatOp::Bold => Self::Bold(toggle()?),
            FormatOp::Italic => Self::Italic(toggle()?),
            FormatOp::Underline => Self::Underline(toggle()?),
            FormatOp::Strikethrough => Self::Strikethrough(toggle()?),
            FormatOp::Color => {
                zero()?;
                Self::Color(paint)
            }
            FormatOp::Paragraph => {
                Self::Paragraph(ParagraphRole::try_from(value).map_err(|()| bad.clone())?)
            }
            FormatOp::List => Self::List(ListKind::try_from(value).map_err(|()| bad.clone())?),
            FormatOp::Indent => {
                zero()?;
                Self::Indent
            }
            FormatOp::Outdent => {
                zero()?;
                Self::Outdent
            }
            FormatOp::Quote => Self::Quote(toggle()?),
        })
    }
}
