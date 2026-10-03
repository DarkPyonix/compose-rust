//! Runs of different treatment inside one `Text`.
//!
//! Not a widget. A paragraph with a bold phrase and a link in it is one piece of text, and
//! splitting it into three widgets to say so would break line wrapping at the seams: the
//! renderer would wrap each piece on its own and the phrase would never share a line with
//! what surrounds it.
//!
//! So the spans travel beside the string, as a blob of fixed-length records in the same
//! shape a `Canvas` carries its commands. Nothing new crosses the boundary to do it.
//!
//! Markdown is a convenience on this side. [`TextSpans::from_markdown`] turns a small,
//! closed subset into spans, and the Renderer never hears the word.

use crate::schema::{Paint, TypeRole};
use std::rc::Rc;

/// Bytes of one span record: two ranges' worth of offsets, a role, flags, the text's paint,
/// the background's paint and a handler. Four-byte aligned throughout, which is the
/// protocol's invariant.
pub const SPAN_LEN: usize = 36;

/// Where each field of a span record starts. The Kotlin decoder is generated from these, so
/// the two sides read the same bytes.
pub const SPAN_START_AT: usize = 0;
pub const SPAN_LENGTH_AT: usize = 4;
pub const SPAN_ROLE_AT: usize = 8;
pub const SPAN_FLAGS_AT: usize = 10;
pub const SPAN_COLOR_AT: usize = 12;
pub const SPAN_BACKGROUND_AT: usize = 20;
pub const SPAN_HANDLER_AT: usize = 28;

pub const FLAG_BOLD: u16 = 1;
pub const FLAG_ITALIC: u16 = 2;
pub const FLAG_UNDERLINE: u16 = 4;
pub const FLAG_STRIKETHROUGH: u16 = 8;

/// One run, given in bytes into the string rather than characters.
///
/// Bytes because that is what the string is measured in on both sides, and a character
/// count would have to be recomputed against a UTF-8 buffer at every use.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct TextSpan {
    pub start: u32,
    pub length: u32,
    pub type_role: Option<TypeRole>,
    pub color: Option<Paint>,
    /// What is painted behind this run's letters, and only behind them.
    ///
    /// The word that actually changed inside a changed line is what this is for: the line
    /// is the node's own background, and this marks the part of it that differs. A run
    /// that wraps is painted on each line it reaches. There is no shape and no inset,
    /// because nothing yet needs one, and when something does it is the design system's
    /// to say.
    pub background: Option<Paint>,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub strikethrough: bool,
    /// The handler a press on this run reports to, if it is a link.
    pub on_click: Option<u64>,
}

impl TextSpan {
    /// A run of the given bytes with nothing said about it yet.
    pub const fn new(start: u32, length: u32) -> Self {
        Self {
            start,
            length,
            type_role: None,
            color: None,
            background: None,
            bold: false,
            italic: false,
            underline: false,
            strikethrough: false,
            on_click: None,
        }
    }

    pub const fn bold(mut self) -> Self {
        self.bold = true;
        self
    }

    pub const fn italic(mut self) -> Self {
        self.italic = true;
        self
    }

    pub const fn underline(mut self) -> Self {
        self.underline = true;
        self
    }

    pub const fn strikethrough(mut self) -> Self {
        self.strikethrough = true;
        self
    }

    pub const fn with_role(mut self, role: TypeRole) -> Self {
        self.type_role = Some(role);
        self
    }

    pub const fn with_color(mut self, color: Paint) -> Self {
        self.color = Some(color);
        self
    }

    /// Paints behind this run. A role first, as everywhere else a colour is taken: the
    /// diff's word backgrounds are roles, so they follow the scheme and the system.
    pub const fn with_background(mut self, background: Paint) -> Self {
        self.background = Some(background);
        self
    }

    fn flags(&self) -> u16 {
        let mut flags = 0;
        if self.bold {
            flags |= FLAG_BOLD;
        }
        if self.italic {
            flags |= FLAG_ITALIC;
        }
        if self.underline {
            flags |= FLAG_UNDERLINE;
        }
        if self.strikethrough {
            flags |= FLAG_STRIKETHROUGH;
        }
        flags
    }

    fn write_into(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.start.to_le_bytes());
        out.extend_from_slice(&self.length.to_le_bytes());
        out.extend_from_slice(&self.type_role.map_or(0, u16::from).to_le_bytes());
        out.extend_from_slice(&self.flags().to_le_bytes());
        // Zero is "none" for both paints, which no real paint encodes to: every kind
        // has a non-zero tag in its high word.
        let paint = self.color.map_or(0, Paint::to_bits);
        out.extend_from_slice(&(paint as u32).to_le_bytes());
        out.extend_from_slice(&((paint >> 32) as u32).to_le_bytes());
        let background = self.background.map_or(0, Paint::to_bits);
        out.extend_from_slice(&(background as u32).to_le_bytes());
        out.extend_from_slice(&((background >> 32) as u32).to_le_bytes());
        let handler = self.on_click.unwrap_or(0);
        out.extend_from_slice(&(handler as u32).to_le_bytes());
        out.extend_from_slice(&((handler >> 32) as u32).to_le_bytes());
    }
}

/// What a marker turns its body into: the bytes that open and close it, and the span the
/// enclosed run becomes.
type Marked = (&'static [u8], fn(u32, u32) -> TextSpan);

/// An encoded list of spans, ready to travel.
///
/// Shared rather than copied, for the same reason a draw list is: the same list redrawn
/// every frame compares equal and produces no mutation at all.
#[derive(Clone, Default, PartialEq)]
pub struct TextSpans {
    bytes: Option<Rc<[u8]>>,
}

impl std::fmt::Debug for TextSpans {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("TextSpans")
            .field("spans", &(self.as_bytes().len() / SPAN_LEN))
            .finish()
    }
}

impl TextSpans {
    pub fn new(spans: impl IntoIterator<Item = TextSpan>) -> Self {
        let mut bytes = Vec::new();
        for span in spans {
            span.write_into(&mut bytes);
        }
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

    /// The runs this list holds, decoded again.
    pub fn spans(&self) -> impl Iterator<Item = TextSpan> + '_ {
        self.as_bytes().chunks_exact(SPAN_LEN).map(|record| {
            let word = |at: usize| {
                u32::from_le_bytes([record[at], record[at + 1], record[at + 2], record[at + 3]])
            };
            let half = |at: usize| u16::from_le_bytes([record[at], record[at + 1]]);
            let flags = half(SPAN_FLAGS_AT);
            let paint = u64::from(word(SPAN_COLOR_AT)) | (u64::from(word(SPAN_COLOR_AT + 4)) << 32);
            let background = u64::from(word(SPAN_BACKGROUND_AT))
                | (u64::from(word(SPAN_BACKGROUND_AT + 4)) << 32);
            let handler =
                u64::from(word(SPAN_HANDLER_AT)) | (u64::from(word(SPAN_HANDLER_AT + 4)) << 32);
            TextSpan {
                start: word(SPAN_START_AT),
                length: word(SPAN_LENGTH_AT),
                type_role: TypeRole::try_from(half(SPAN_ROLE_AT)).ok(),
                color: Paint::from_bits(paint),
                background: Paint::from_bits(background),
                bold: flags & FLAG_BOLD != 0,
                italic: flags & FLAG_ITALIC != 0,
                underline: flags & FLAG_UNDERLINE != 0,
                strikethrough: flags & FLAG_STRIKETHROUGH != 0,
                on_click: (handler != 0).then_some(handler),
            }
        })
    }

    /// Turns a small, closed subset of markdown into a plain string and its spans.
    ///
    /// `**bold**`, `*italic*` and `` `code` ``, and nothing else. It is a convenience, not
    /// a parser: anything it does not recognise is left in the string exactly as written,
    /// because silently eating a character a reader typed is worse than showing it.
    ///
    /// Links are not here. A link needs a handler, and a handler is something the screen
    /// has rather than something a string can name.
    pub fn from_markdown(source: &str) -> (String, Self) {
        let mut text = String::with_capacity(source.len());
        let mut spans = Vec::new();
        let bytes = source.as_bytes();
        let mut at = 0;
        while at < bytes.len() {
            let (marker, span_of): Marked = if bytes[at..].starts_with(b"**") {
                (b"**", |start, length| TextSpan::new(start, length).bold())
            } else if bytes[at] == b'*' {
                (b"*", |start, length| TextSpan::new(start, length).italic())
            } else if bytes[at] == b'`' {
                (b"`", |start, length| {
                    TextSpan::new(start, length).with_role(TypeRole::Mono)
                })
            } else {
                text.push(source[at..].chars().next().expect("a char boundary"));
                at += source[at..].chars().next().map_or(1, char::len_utf8);
                continue;
            };
            let body = at + marker.len();
            let Some(end) = find(&bytes[body..], marker).map(|offset| body + offset) else {
                // No closing marker, so this is not a marker at all.
                text.push_str(&source[at..body]);
                at = body;
                continue;
            };
            let start = text.len() as u32;
            text.push_str(&source[body..end]);
            spans.push(span_of(start, text.len() as u32 - start));
            at = end + marker.len();
        }
        (text, Self::new(spans))
    }
}

fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

impl dioxus_core::IntoAttributeValue for TextSpans {
    fn into_value(self) -> dioxus_core::AttributeValue {
        dioxus_core::AttributeValue::any_value(self)
    }
}
