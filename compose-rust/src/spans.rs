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

use crate::font_features::{
    FONT_FEATURE_LEN, FONT_FEATURE_TAG_AT, FONT_FEATURE_VALUE_AT, FontFeature, MAX_FONT_FEATURES,
};
use crate::fonts::{FONT_REF_LEN, FontRefs};
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

/// Bytes of one entry in a span font table: which run, then where its font list starts
/// and how many candidates it holds.
///
/// The table is a blob of its own, beside the runs rather than inside their records, so a
/// run record is the same 36 bytes whether or not anything names a font.
pub const SPAN_FONT_ENTRY_LEN: usize = 12;
pub const SPAN_FONT_SPAN_AT: usize = 0;
pub const SPAN_FONT_OFFSET_AT: usize = 4;
pub const SPAN_FONT_COUNT_AT: usize = 8;

/// Bytes of one entry in a span font feature table: which run, then where its feature
/// records start and how many there are.
///
/// The table is a blob of its own, beside the runs rather than inside their records, so a
/// run record is the same 36 bytes whether or not anything names a font feature. It opens
/// with the number of entries as a `u32`, the entries follow, and the 8-byte feature
/// records (the ones `FontFeatureSettings` carries) come after them. Every offset counts
/// from the start of the blob and is a multiple of four.
pub const SPAN_FEATURE_ENTRY_LEN: usize = 12;
pub const SPAN_FEATURE_SPAN_AT: usize = 0;
pub const SPAN_FEATURE_OFFSET_AT: usize = 4;
pub const SPAN_FEATURE_COUNT_AT: usize = 8;

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
    /// The font table for runs that name one, which only text with no role reads.
    fonts: Option<Rc<[u8]>>,
    /// The font feature table for runs that name features of their own, which text takes
    /// with or without a role.
    features: Option<Rc<[u8]>>,
    /// The per-span metrics side table (16-byte entries: span u32, font_size f32,
    /// letter_spacing f32, reserved u32), read only by text with no role.
    metrics: Option<Rc<[u8]>>,
}

impl std::fmt::Debug for TextSpans {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("TextSpans")
            .field("spans", &(self.as_bytes().len() / SPAN_LEN))
            .field("fonts", &self.font_entry_count())
            .field("features", &self.feature_entry_count())
            .field("metrics", &self.metrics_entry_count())
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

    /// Runs, each with the fonts it is set in where the text has no role.
    ///
    /// An empty list leaves that run in the text's own font. A run of text that has a
    /// role is always in the theme's font, whatever it names here.
    pub fn new_with_fonts(spans: impl IntoIterator<Item = (TextSpan, FontRefs)>) -> Self {
        let mut bytes = Vec::new();
        let mut named: Vec<(u32, FontRefs)> = Vec::new();
        for (index, (span, fonts)) in spans.into_iter().enumerate() {
            span.write_into(&mut bytes);
            if !fonts.is_empty() {
                named.push((index as u32, fonts));
            }
        }
        let mut spans = Self::from_bytes(bytes);
        spans.fonts = encode_font_table(&named);
        spans
    }

    /// Runs, each with the OpenType features it is set with, such as
    /// `FontFeature::TABULAR_NUMS` on the one run that is a number.
    ///
    /// An empty list leaves that run with the features of the text it is in. A run that
    /// names features takes the text's as well, and where both name a tag the run's value
    /// is the one used. Unlike a font, features are taken by a run with a role and by a
    /// run of text that has one: they pick a form of the font and leave the font alone.
    /// At most `MAX_FONT_FEATURES` (16) are taken for one run; a longer list is still
    /// sent, so the Renderer can say what is wrong with it.
    pub fn new_with_features(
        spans: impl IntoIterator<Item = (TextSpan, impl AsRef<[FontFeature]>)>,
    ) -> Self {
        let mut bytes = Vec::new();
        let mut named: Vec<(u32, Vec<FontFeature>)> = Vec::new();
        for (index, (span, features)) in spans.into_iter().enumerate() {
            span.write_into(&mut bytes);
            let slice = features.as_ref();
            if !slice.is_empty() {
                named.push((index as u32, slice.to_vec()));
            }
        }
        let mut spans = Self::from_bytes(bytes);
        spans.features = encode_feature_table(&named);
        spans
    }

    /// Adopts an already encoded list, which is what the protocol vectors carry.
    pub fn from_bytes(bytes: impl Into<Rc<[u8]>>) -> Self {
        let bytes = bytes.into();
        Self {
            bytes: (!bytes.is_empty()).then_some(bytes),
            fonts: None,
            features: None,
            metrics: None,
        }
    }

    /// The same runs with an already encoded font table beside them.
    pub fn with_font_table(mut self, table: impl Into<Rc<[u8]>>) -> Self {
        let table = table.into();
        self.fonts = (!table.is_empty()).then_some(table);
        self
    }

    /// The same runs with an already encoded font feature table beside them, as
    /// [`encode_feature_table`] lays one out. An empty table is no table.
    pub fn with_feature_table(mut self, table: impl Into<Rc<[u8]>>) -> Self {
        let table = table.into();
        self.features = (!table.is_empty()).then_some(table);
        self
    }

    /// The same runs with an already encoded metrics table beside them, as
    /// [`encode_metrics_table`] lays one out. An empty table is no table.
    pub fn with_metrics_table(mut self, table: impl Into<Rc<[u8]>>) -> Self {
        let table = table.into();
        self.metrics = (!table.is_empty()).then_some(table);
        self
    }

    pub fn as_bytes(&self) -> &[u8] {
        self.bytes.as_deref().unwrap_or(&[])
    }

    /// The `SpanFonts` blob: a count, then one entry per run that names fonts, then the
    /// font lists themselves. Every offset in it counts from the start of the blob.
    pub fn font_table(&self) -> &[u8] {
        self.fonts.as_deref().unwrap_or(&[])
    }

    /// The `SpanFontFeatures` blob: a count, then one entry per run that names features,
    /// then the feature records themselves. Every offset in it counts from the start of
    /// the blob. Empty where no run names a feature, and then nothing is sent.
    pub fn feature_table(&self) -> &[u8] {
        self.features.as_deref().unwrap_or(&[])
    }

    /// The same table, shared rather than copied, for a widget to keep and compare.
    pub(crate) fn shared_feature_table(&self) -> Option<Rc<[u8]>> {
        self.features.clone()
    }

    /// The `SpanMetrics` blob: a count u32 followed by 16-byte entries (`span: u32`,
    /// `font_size: f32`, `letter_spacing: f32`, `reserved: u32`). Empty where no run
    /// overrides font size or letter spacing.
    pub fn metrics_table(&self) -> &[u8] {
        self.metrics.as_deref().unwrap_or(&[])
    }

    /// The same metrics table, shared rather than copied, for a widget to keep and compare.
    pub(crate) fn shared_metrics_table(&self) -> Option<Rc<[u8]>> {
        self.metrics.clone()
    }

    /// How many runs override font size or letter spacing.
    pub fn metrics_entry_count(&self) -> usize {
        let table = self.metrics_table();
        if table.len() < 4 {
            return 0;
        }
        u32::from_le_bytes([table[0], table[1], table[2], table[3]]) as usize
    }

    /// Decoded per-span metrics entries, or empty if absent or malformed.
    pub fn span_metrics(&self) -> Vec<SpanMetric> {
        decode_metrics_table(self.metrics_table()).unwrap_or_default()
    }

    pub fn is_empty(&self) -> bool {
        self.bytes.is_none()
    }

    /// How many runs name fonts.
    pub fn font_entry_count(&self) -> usize {
        let table = self.font_table();
        if table.len() < 4 {
            return 0;
        }
        u32::from_le_bytes([table[0], table[1], table[2], table[3]]) as usize
    }

    /// The fonts each run that names any is set in, by the run's index.
    pub fn span_fonts(&self) -> Vec<(u32, Vec<crate::fonts::FontRef>)> {
        let table = self.font_table();
        let word = |at: usize| -> Option<u32> {
            let slice = table.get(at..at + 4)?;
            Some(u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]))
        };
        (0..self.font_entry_count())
            .filter_map(|entry| {
                let at = 4 + entry * SPAN_FONT_ENTRY_LEN;
                let span = word(at + SPAN_FONT_SPAN_AT)?;
                let offset = word(at + SPAN_FONT_OFFSET_AT)? as usize;
                let count = word(at + SPAN_FONT_COUNT_AT)? as usize;
                let refs = (0..count)
                    .filter_map(|index| {
                        crate::fonts::decode_record(table, offset + index * FONT_REF_LEN)
                    })
                    .collect();
                Some((span, refs))
            })
            .collect()
    }

    /// How many runs name font features, as the table's own count says.
    pub fn feature_entry_count(&self) -> usize {
        let table = self.feature_table();
        if table.len() < 4 {
            return 0;
        }
        u32::from_le_bytes([table[0], table[1], table[2], table[3]]) as usize
    }

    /// The font features each run that names any is set with, by the run's index, in the
    /// order the table lists them.
    ///
    /// Empty where there is no table and where the table cannot be read by the rules the
    /// Renderer holds it to: an entry or a feature list that runs past the end, an offset
    /// that is not a multiple of four, more than `MAX_FONT_FEATURES` features for one run,
    /// or a run named twice. The Renderer reports such a table and draws the text without
    /// any of it, so nothing is read out of half of one here either.
    pub fn span_features(&self) -> Vec<(u32, Vec<FontFeature>)> {
        decode_feature_table(self.feature_table()).unwrap_or_default()
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

/// Lays out a span font table: the count, one entry per run, and after them each run's
/// font records, each followed by the names its records point at.
fn encode_font_table(named: &[(u32, FontRefs)]) -> Option<Rc<[u8]>> {
    if named.is_empty() {
        return None;
    }
    let mut entries = Vec::with_capacity(named.len() * SPAN_FONT_ENTRY_LEN);
    let mut lists: Vec<u8> = Vec::new();
    let lists_at = 4 + named.len() * SPAN_FONT_ENTRY_LEN;
    for (span, fonts) in named {
        let offset = (lists_at + lists.len()) as u32;
        let count = fonts.len() as u32;
        entries.extend_from_slice(&span.to_le_bytes());
        entries.extend_from_slice(&offset.to_le_bytes());
        entries.extend_from_slice(&count.to_le_bytes());
        let names_at = offset + count * FONT_REF_LEN as u32;
        fonts.write_relocated(&mut lists, names_at);
    }
    let mut table = Vec::with_capacity(lists_at + lists.len());
    table.extend_from_slice(&(named.len() as u32).to_le_bytes());
    table.extend_from_slice(&entries);
    table.extend_from_slice(&lists);
    Some(table.into())
}

/// Lays out a span font feature table: the count, one entry per run, and after them
/// each run's 8-byte feature records.
///
/// `named` pairs a run's index in its run list with that run's features. A run with no
/// features gets no entry, and a list with no entry at all is no table, so text that names
/// no feature sends nothing. Each run is named at most once: the Renderer reports a table
/// that names a run twice, or names a run the text does not have, and draws the text
/// without span features.
pub fn encode_feature_table(named: &[(u32, Vec<FontFeature>)]) -> Option<Rc<[u8]>> {
    let entry_count = named
        .iter()
        .filter(|(_, features)| !features.is_empty())
        .count();
    if entry_count == 0 {
        return None;
    }
    let lists_at = 4 + entry_count * SPAN_FEATURE_ENTRY_LEN;
    let mut entries = Vec::with_capacity(entry_count * SPAN_FEATURE_ENTRY_LEN);
    let mut lists: Vec<u8> = Vec::new();
    for (span, features) in named {
        if features.is_empty() {
            continue;
        }
        let offset = (lists_at + lists.len()) as u32;
        let count = features.len() as u32;
        entries.extend_from_slice(&span.to_le_bytes());
        entries.extend_from_slice(&offset.to_le_bytes());
        entries.extend_from_slice(&count.to_le_bytes());
        for feature in features {
            lists.extend_from_slice(&feature.to_bytes());
        }
    }
    let mut table = Vec::with_capacity(lists_at + lists.len());
    table.extend_from_slice(&(entry_count as u32).to_le_bytes());
    table.extend_from_slice(&entries);
    table.extend_from_slice(&lists);
    Some(table.into())
}

/// Reads a span font feature table back, or `None` where it breaks a rule of its layout.
/// An empty blob is a table with no entries.
fn decode_feature_table(table: &[u8]) -> Option<Vec<(u32, Vec<FontFeature>)>> {
    if table.is_empty() {
        return Some(Vec::new());
    }
    let word = |at: usize| -> Option<u32> {
        let bytes = table.get(at..at.checked_add(4)?)?;
        Some(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    };
    let entry_count = word(0)? as usize;
    // Checked before anything is allocated or walked, so a count that is only a large
    // number costs nothing.
    let entries_end = entry_count
        .checked_mul(SPAN_FEATURE_ENTRY_LEN)?
        .checked_add(4)?;
    if entries_end > table.len() {
        return None;
    }
    let mut decoded: Vec<(u32, Vec<FontFeature>)> = Vec::with_capacity(entry_count);
    for entry in 0..entry_count {
        let at = 4 + entry * SPAN_FEATURE_ENTRY_LEN;
        let span = word(at + SPAN_FEATURE_SPAN_AT)?;
        let offset = word(at + SPAN_FEATURE_OFFSET_AT)? as usize;
        let count = word(at + SPAN_FEATURE_COUNT_AT)? as usize;
        if count > MAX_FONT_FEATURES || offset % 4 != 0 || offset < entries_end {
            return None;
        }
        let end = offset.checked_add(count * FONT_FEATURE_LEN)?;
        if end > table.len() || decoded.iter().any(|(seen, _)| *seen == span) {
            return None;
        }
        let features = table[offset..end]
            .chunks_exact(FONT_FEATURE_LEN)
            .map(|record| {
                let tag_at = FONT_FEATURE_TAG_AT;
                let value_at = FONT_FEATURE_VALUE_AT;
                FontFeature::new(
                    &[
                        record[tag_at],
                        record[tag_at + 1],
                        record[tag_at + 2],
                        record[tag_at + 3],
                    ],
                    u32::from_le_bytes([
                        record[value_at],
                        record[value_at + 1],
                        record[value_at + 2],
                        record[value_at + 3],
                    ]),
                )
            })
            .collect();
        decoded.push((span, features));
    }
    Some(decoded)
}

fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

/// One 16-byte entry in a `SpanMetrics` side table: `span: u32`, `font_size: f32` (NaN = unset),
/// `letter_spacing: f32` (NaN = unset), `reserved: u32` (0).
pub const SPAN_METRICS_ENTRY_LEN: usize = 16;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SpanMetric {
    pub span: u32,
    pub font_size: f32,
    pub letter_spacing: f32,
}

impl SpanMetric {
    pub const fn new(span: u32, font_size: f32, letter_spacing: f32) -> Self {
        Self {
            span,
            font_size,
            letter_spacing,
        }
    }
}

/// Encodes per-span metrics into the `SpanMetrics` blob (`count: u32` followed by 16-byte entries).
pub fn encode_metrics_table(entries: &[SpanMetric]) -> Option<Rc<[u8]>> {
    if entries.is_empty() {
        return None;
    }
    let mut out = Vec::with_capacity(4 + entries.len() * SPAN_METRICS_ENTRY_LEN);
    out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
    for entry in entries {
        out.extend_from_slice(&entry.span.to_le_bytes());
        out.extend_from_slice(&entry.font_size.to_le_bytes());
        out.extend_from_slice(&entry.letter_spacing.to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
    }
    Some(Rc::from(out))
}

/// Decodes a `SpanMetrics` blob, rejecting truncated blobs, trailing bytes, non-zero reserved
/// words, or duplicate span indices.
pub fn decode_metrics_table(table: &[u8]) -> Option<Vec<SpanMetric>> {
    if table.is_empty() {
        return Some(Vec::new());
    }
    if table.len() < 4 {
        return None;
    }
    let count = u32::from_le_bytes([table[0], table[1], table[2], table[3]]) as usize;
    let expected = 4usize.checked_add(count.checked_mul(SPAN_METRICS_ENTRY_LEN)?)?;
    if table.len() != expected {
        return None;
    }
    let mut seen = Vec::with_capacity(count);
    let mut decoded = Vec::with_capacity(count);
    for i in 0..count {
        let at = 4 + i * SPAN_METRICS_ENTRY_LEN;
        let span = u32::from_le_bytes([table[at], table[at + 1], table[at + 2], table[at + 3]]);
        let font_size =
            f32::from_le_bytes([table[at + 4], table[at + 5], table[at + 6], table[at + 7]]);
        let letter_spacing =
            f32::from_le_bytes([table[at + 8], table[at + 9], table[at + 10], table[at + 11]]);
        let reserved = u32::from_le_bytes([
            table[at + 12],
            table[at + 13],
            table[at + 14],
            table[at + 15],
        ]);
        if reserved != 0 || seen.contains(&span) {
            return None;
        }
        seen.push(span);
        decoded.push(SpanMetric {
            span,
            font_size,
            letter_spacing,
        });
    }
    Some(decoded)
}
