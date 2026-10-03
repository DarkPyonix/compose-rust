//! Asking the Renderer how big something will be, while the Host is laying it out.
//!
//! A Host that lays out a page itself (a flexbox, a grid, text flowing around a picture)
//! needs to know how wide a run of text is before it can decide where anything goes, and
//! only the side that draws the letters can say: the font, the density, the system's font
//! scale and the design system's type scale are all the Renderer's. So the Host asks, in
//! the middle of its own work, and the Renderer answers on the same call stack.
//!
//! One call carries many questions. A sidebar lays out a couple of hundred labels, and a
//! flexbox asks each of them for its narrowest width, its widest width and its height at a
//! given width, so one call per question would pay the crossing six hundred times.
//!
//! The questions travel the way a batch does: fixed-length records first, then the strings
//! and run lists they point at. The answers come back as fixed-length records in an array
//! the Host owns. Nothing is serialised and nothing is copied on the way across.
//!
//! Application code does not call the boundary. A layout engine asks [`measurer`] for a
//! [`Measurer`], which it gets only while the Host is inside one of its own calls, which is
//! the only time the Renderer is standing still on the other end of the same thread.

use crate::fonts::{FONT_REF_LEN, FONT_REF_SYSTEM, FontRefs};
use crate::protocol::ProtocolError;
use crate::schema::{OverflowWrap, TypeRole, WordBreak};
use crate::spans::{SPAN_FONT_ENTRY_LEN, SPAN_FONT_OFFSET_AT, SPAN_LEN, TextSpans};
use std::marker::PhantomData;

/// Bytes of one request record, whichever kind it is.
pub const MEASURE_RECORD_LEN: usize = 72;
/// Bytes of one result record.
pub const MEASURE_RESULT_LEN: usize = 32;

/// A run of text, its style and a width constraint.
pub const MEASURE_TEXT: u16 = 1;
/// A node the Renderer has already applied, and size constraints.
pub const MEASURE_NODE: u16 = 2;

// Where each field of a request record starts. Both kinds start with the kind.
pub const MEASURE_KIND_AT: usize = 0;

pub const TEXT_TYPE_ROLE_AT: usize = 2;
pub const TEXT_TEXT_OFFSET_AT: usize = 4;
pub const TEXT_TEXT_LENGTH_AT: usize = 8;
pub const TEXT_SPANS_OFFSET_AT: usize = 12;
pub const TEXT_SPANS_COUNT_AT: usize = 16;
pub const TEXT_SPAN_FONTS_OFFSET_AT: usize = 20;
pub const TEXT_SPAN_FONTS_COUNT_AT: usize = 24;
pub const TEXT_FONT_OFFSET_AT: usize = 28;
pub const TEXT_FONT_COUNT_AT: usize = 32;
pub const TEXT_FONT_SIZE_AT: usize = 36;
pub const TEXT_FONT_WEIGHT_AT: usize = 40;
pub const TEXT_ITALIC_AT: usize = 42;
pub const TEXT_WRAP_AT: usize = 43;
pub const TEXT_LETTER_SPACING_AT: usize = 44;
pub const TEXT_LINE_HEIGHT_AT: usize = 48;
pub const TEXT_MAX_LINES_AT: usize = 52;
pub const TEXT_TAB_SIZE_AT: usize = 56;
pub const TEXT_WORD_BREAK_AT: usize = 57;
pub const TEXT_OVERFLOW_WRAP_AT: usize = 58;
pub const TEXT_ABSOLUTE_SIZE_AT: usize = 59;
pub const TEXT_CONSTRAINT_AT: usize = 60;
pub const TEXT_WIDTH_AT: usize = 64;
pub const TEXT_ZOOM_AT: usize = 68;

pub const NODE_ID_AT: usize = 4;
pub const NODE_MIN_WIDTH_AT: usize = 8;
pub const NODE_MAX_WIDTH_AT: usize = 12;
pub const NODE_MIN_HEIGHT_AT: usize = 16;
pub const NODE_MAX_HEIGHT_AT: usize = 20;
pub const NODE_ZOOM_AT: usize = 24;

/// The narrowest the text can be: its longest piece that cannot be broken.
pub const CONSTRAINT_MIN_CONTENT: u32 = 1;
/// The text with no line broken except where it says so.
pub const CONSTRAINT_MAX_CONTENT: u32 = 2;
/// The text broken into lines no wider than the given width.
pub const CONSTRAINT_AT_MOST: u32 = 3;

// Where each field of a result record starts.
pub const RESULT_WIDTH_AT: usize = 0;
pub const RESULT_HEIGHT_AT: usize = 4;
pub const RESULT_FIRST_BASELINE_AT: usize = 8;
pub const RESULT_LAST_BASELINE_AT: usize = 12;
pub const RESULT_LAST_LINE_WIDTH_AT: usize = 16;
pub const RESULT_LINE_COUNT_AT: usize = 20;
pub const RESULT_FLAGS_AT: usize = 24;
pub const RESULT_STATUS_AT: usize = 28;

/// The text had more lines than `max_lines` allowed, and some were not shown.
pub const FLAG_TRUNCATED: u32 = 1;

pub const RESULT_OK: u32 = 0;
/// The node is not in the Renderer's table: never sent, removed, or sent in the batch the
/// Host is computing right now, which the Renderer applies only once the call returns.
pub const RESULT_UNKNOWN_NODE: u32 = 1;
/// The record points outside the buffer, or is a kind or value the Renderer does not know.
pub const RESULT_MALFORMED: u32 = 2;

/// The request buffer as a whole cannot be read: shorter than its records, or null.
pub const MEASURE_UNREADABLE: i32 = -1;
/// The call came from a thread other than the Renderer's UI thread, and nothing was
/// measured. The same number `run` gives for the same mistake.
pub const MEASURE_OFF_UI_THREAD: i32 = -4;
/// There is nothing to measure with yet: the Renderer has not composed its first frame,
/// so it has no density, no fonts and no design system. Also what a build with no
/// renderer answers.
pub const MEASURE_UNAVAILABLE: i32 = -5;

/// Hash input for the record layouts above, so a Renderer generated from another layout
/// is refused at the handshake rather than reading the wrong bytes.
pub(crate) const SCHEMA_DESCRIPTOR: &str = concat!(
    "measure=v1;record=72;result=32;",
    "text=kind@0,type_role@2,text@4,spans@12,span_fonts@20,font@28,font_size@36,",
    "font_weight@40,italic@42,wrap@43,letter_spacing@44,line_height@48,max_lines@52,",
    "tab_size@56,word_break@57,overflow_wrap@58,absolute_size@59,constraint@60,width@64,",
    "zoom@68;",
    "node=kind@0,node@4,min_width@8,max_width@12,min_height@16,max_height@20,zoom@24;",
    "result=width@0,height@4,first_baseline@8,last_baseline@12,last_line_width@16,",
    "line_count@20,flags@24,status@28;",
    "fontref=12,kind@0,value@4,length@8;spanfont=12,span@0,offset@4,count@8;",
);

/// The Renderer's answer to one request, in dp.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct MeasureResult {
    pub width: f32,
    pub height: f32,
    /// NaN where there is no baseline.
    pub first_baseline: f32,
    pub last_baseline: f32,
    /// How wide the last line is, which is where inline content after the text starts.
    /// NaN for a node.
    pub last_line_width: f32,
    /// Zero for a node.
    pub line_count: u32,
    pub flags: u32,
    pub status: u32,
}

const _: () = assert!(std::mem::size_of::<MeasureResult>() == MEASURE_RESULT_LEN);

impl MeasureResult {
    /// What a refused call leaves in every slot: no size, and a status that says it was
    /// not measured.
    pub const REFUSED: Self = Self {
        width: 0.0,
        height: 0.0,
        first_baseline: f32::NAN,
        last_baseline: f32::NAN,
        last_line_width: f32::NAN,
        line_count: 0,
        flags: 0,
        status: RESULT_MALFORMED,
    };

    pub fn status(&self) -> MeasureStatus {
        match self.status {
            RESULT_OK => MeasureStatus::Ok,
            RESULT_UNKNOWN_NODE => MeasureStatus::UnknownNode,
            RESULT_MALFORMED => MeasureStatus::Malformed,
            other => MeasureStatus::Other(other),
        }
    }

    /// Whether `max_lines` cut the text short.
    pub fn truncated(&self) -> bool {
        self.flags & FLAG_TRUNCATED != 0
    }
}

/// How one request went.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MeasureStatus {
    Ok,
    UnknownNode,
    Malformed,
    /// A status this build does not know, from a Renderer newer than it.
    Other(u32),
}

/// How wide the text may be.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum TextConstraint {
    /// The narrowest it can be: CSS `min-content`.
    MinContent,
    /// As wide as it is with no line broken except where it says so: CSS `max-content`.
    MaxContent,
    /// Lines broken to fit this many dp.
    AtMost(f32),
}

/// How a run of text is set, as the Renderer reads it.
///
/// The same fields a `Text` carries, meaning the same things: the Renderer resolves both
/// with one function, so text measured with a style is the size the same text drawn with
/// the same properties turns out to be.
#[derive(Clone, Debug, PartialEq)]
pub struct TextStyle {
    pub type_role: TypeRole,
    /// The fonts to try, for text with no role. Ignored where there is a role.
    pub font: FontRefs,
    /// `None` takes the role's size, or 14 for text with no role.
    pub font_size: Option<f32>,
    /// `None` takes the role's weight, or 400 for text with no role.
    pub font_weight: Option<u16>,
    pub italic: bool,
    /// `None` takes the role's spacing, or none for text with no role.
    pub letter_spacing: Option<f32>,
    /// `None` is the font's own line height.
    pub line_height: Option<f32>,
    /// `None` is no limit.
    pub max_lines: Option<u32>,
    /// Whether lines break at the width given. `false` is CSS `nowrap` and `pre`.
    pub wrap: bool,
    /// How many spaces' width apart the tab stops are.
    pub tab_size: u8,
    pub word_break: Option<WordBreak>,
    pub overflow_wrap: Option<OverflowWrap>,
    /// Sizes are CSS pixels: the system's font scale does not enlarge them.
    pub absolute_size: bool,
}

impl Default for TextStyle {
    fn default() -> Self {
        Self {
            type_role: TypeRole::Body,
            font: FontRefs::default(),
            font_size: None,
            font_weight: None,
            italic: false,
            letter_spacing: None,
            line_height: None,
            max_lines: None,
            wrap: true,
            tab_size: 8,
            word_break: None,
            overflow_wrap: None,
            absolute_size: false,
        }
    }
}

impl TextStyle {
    /// Text set by the design system's rung `role`.
    pub fn role(role: TypeRole) -> Self {
        Self {
            type_role: role,
            ..Self::default()
        }
    }
}

/// The size a node may take, in dp. `f32::INFINITY` is no limit.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct NodeConstraints {
    pub min_width: f32,
    pub max_width: f32,
    pub min_height: f32,
    pub max_height: f32,
}

impl NodeConstraints {
    /// Any size at all.
    pub const UNBOUNDED: Self = Self {
        min_width: 0.0,
        max_width: f32::INFINITY,
        min_height: 0.0,
        max_height: f32::INFINITY,
    };

    /// Exactly this width, any height.
    pub const fn width(width: f32) -> Self {
        Self {
            min_width: width,
            max_width: width,
            min_height: 0.0,
            max_height: f32::INFINITY,
        }
    }
}

/// The fields of one record, kept until the buffer is laid out.
#[derive(Clone, Copy, Debug)]
struct PendingRecord {
    bytes: [u8; MEASURE_RECORD_LEN],
}

/// A set of questions for one call, built up and then asked at once.
///
/// Reused across frames: clearing keeps every buffer's capacity, so once warm, asking the
/// same number of questions allocates nothing.
#[derive(Debug, Default)]
pub struct MeasureRequests {
    records: Vec<PendingRecord>,
    /// The strings and lists the records point at, counted from the start of this area
    /// until the buffer is laid out.
    payload: Vec<u8>,
    /// Payload positions holding an offset that is counted from the start of the payload
    /// and has to be counted from the start of the buffer instead.
    payload_patches: Vec<u32>,
    /// The laid out buffer.
    buffer: Vec<u8>,
    /// The zoom the requests asked from here on are measured at, where it was set.
    zoom: Option<f32>,
}

impl MeasureRequests {
    pub fn new() -> Self {
        Self::default()
    }

    /// Measures the requests asked from here on at zoom `zoom`: the zoom of the page
    /// region they belong to, which is the operating system's text size times the
    /// application's own zoom. The Renderer measures them at its density times `zoom`,
    /// with no font scale of its own, and answers in the region's CSS pixels.
    ///
    /// A Host can hold several regions at different zooms, which is why this travels with
    /// every request rather than being the Renderer's state. Never set, it is one.
    pub fn set_zoom(&mut self, zoom: f32) {
        self.zoom = Some(zoom);
    }

    /// Forgets every question, keeping the room they took.
    pub fn clear(&mut self) {
        self.records.clear();
        self.payload.clear();
        self.payload_patches.clear();
        self.buffer.clear();
    }

    /// How many questions there are.
    pub fn len(&self) -> usize {
        self.records.len()
    }

    pub fn is_empty(&self) -> bool {
        self.records.is_empty()
    }

    /// Asks how big `text`, with `spans` laid over it and set in `style`, is under
    /// `constraint`. Returns the index its answer will have.
    pub fn text(
        &mut self,
        text: &str,
        spans: &TextSpans,
        style: &TextStyle,
        constraint: TextConstraint,
    ) -> usize {
        let mut record = [0u8; MEASURE_RECORD_LEN];
        put_u16(&mut record, MEASURE_KIND_AT, MEASURE_TEXT);
        put_u16(&mut record, TEXT_TYPE_ROLE_AT, style.type_role as u16);

        let text_at = self.payload.len() as u32;
        self.payload.extend_from_slice(text.as_bytes());
        put_u32(&mut record, TEXT_TEXT_OFFSET_AT, text_at);
        put_u32(&mut record, TEXT_TEXT_LENGTH_AT, text.len() as u32);

        let runs = spans.as_bytes();
        let spans_at = self.payload.len() as u32;
        self.payload.extend_from_slice(runs);
        put_u32(&mut record, TEXT_SPANS_OFFSET_AT, spans_at);
        put_u32(
            &mut record,
            TEXT_SPANS_COUNT_AT,
            (runs.len() / SPAN_LEN) as u32,
        );

        // Both font tables are copied as they are, records and names together, and only the
        // offsets inside them are moved: a request costs no allocation once warm.
        let (entries_at, entries) = self.copy_span_font_table(spans.font_table());
        put_u32(&mut record, TEXT_SPAN_FONTS_OFFSET_AT, entries_at);
        put_u32(&mut record, TEXT_SPAN_FONTS_COUNT_AT, entries);
        let (font_at, fonts) = self.copy_font_list(style.font.as_bytes());
        put_u32(&mut record, TEXT_FONT_OFFSET_AT, font_at);
        put_u32(&mut record, TEXT_FONT_COUNT_AT, fonts);

        put_f32(
            &mut record,
            TEXT_FONT_SIZE_AT,
            style.font_size.unwrap_or(0.0),
        );
        put_u16(
            &mut record,
            TEXT_FONT_WEIGHT_AT,
            style.font_weight.unwrap_or(0),
        );
        record[TEXT_ITALIC_AT] = u8::from(style.italic);
        record[TEXT_WRAP_AT] = u8::from(style.wrap);
        put_f32(
            &mut record,
            TEXT_LETTER_SPACING_AT,
            style.letter_spacing.unwrap_or(f32::NAN),
        );
        put_f32(
            &mut record,
            TEXT_LINE_HEIGHT_AT,
            style.line_height.unwrap_or(f32::NAN),
        );
        put_u32(&mut record, TEXT_MAX_LINES_AT, style.max_lines.unwrap_or(0));
        record[TEXT_TAB_SIZE_AT] = style.tab_size;
        record[TEXT_WORD_BREAK_AT] = style.word_break.map_or(0, |value| value as u8);
        record[TEXT_OVERFLOW_WRAP_AT] = style.overflow_wrap.map_or(0, |value| value as u8);
        record[TEXT_ABSOLUTE_SIZE_AT] = u8::from(style.absolute_size);
        let (constraint, width) = match constraint {
            TextConstraint::MinContent => (CONSTRAINT_MIN_CONTENT, 0.0),
            TextConstraint::MaxContent => (CONSTRAINT_MAX_CONTENT, 0.0),
            TextConstraint::AtMost(width) => (CONSTRAINT_AT_MOST, width),
        };
        put_u32(&mut record, TEXT_CONSTRAINT_AT, constraint);
        put_f32(&mut record, TEXT_WIDTH_AT, width);
        put_f32(&mut record, TEXT_ZOOM_AT, self.zoom.unwrap_or(0.0));
        self.records.push(PendingRecord { bytes: record });
        self.records.len() - 1
    }

    /// Asks how big the node `node_id`, which the Renderer has already applied, is under
    /// `constraints`. Returns the index its answer will have.
    pub fn node(&mut self, node_id: u32, constraints: NodeConstraints) -> usize {
        let mut record = [0u8; MEASURE_RECORD_LEN];
        put_u16(&mut record, MEASURE_KIND_AT, MEASURE_NODE);
        put_u32(&mut record, NODE_ID_AT, node_id);
        put_f32(&mut record, NODE_MIN_WIDTH_AT, constraints.min_width);
        put_f32(&mut record, NODE_MAX_WIDTH_AT, constraints.max_width);
        put_f32(&mut record, NODE_MIN_HEIGHT_AT, constraints.min_height);
        put_f32(&mut record, NODE_MAX_HEIGHT_AT, constraints.max_height);
        put_f32(&mut record, NODE_ZOOM_AT, self.zoom.unwrap_or(0.0));
        self.records.push(PendingRecord { bytes: record });
        self.records.len() - 1
    }

    /// Copies a `Font` property blob into the payload without its count, moving every
    /// name it points at to where it now sits, and answers where the records start and how
    /// many there are.
    fn copy_font_list(&mut self, blob: &[u8]) -> (u32, u32) {
        let at = self.payload.len();
        if blob.len() < 4 {
            return (at as u32, 0);
        }
        let count = read_u32(blob, 0);
        self.payload.extend_from_slice(&blob[4..]);
        // Everything in the blob moved from `offset` to `at - 4 + offset`.
        let moved = (at as u32).wrapping_sub(4);
        self.move_font_records(at, count, moved);
        (at as u32, count)
    }

    /// Copies a `SpanFonts` table without its count, moving every offset inside it, and
    /// answers where the entries start and how many there are.
    fn copy_span_font_table(&mut self, table: &[u8]) -> (u32, u32) {
        let at = self.payload.len();
        if table.len() < 4 {
            return (at as u32, 0);
        }
        let entries = read_u32(table, 0);
        self.payload.extend_from_slice(&table[4..]);
        let moved = (at as u32).wrapping_sub(4);
        for entry in 0..entries as usize {
            let field = at + entry * SPAN_FONT_ENTRY_LEN + SPAN_FONT_OFFSET_AT;
            if field + 4 > self.payload.len() {
                break;
            }
            let list = read_u32(&self.payload, field).wrapping_add(moved);
            self.payload[field..field + 4].copy_from_slice(&list.to_le_bytes());
            self.payload_patches.push(field as u32);
            let count = read_u32(&self.payload, at + entry * SPAN_FONT_ENTRY_LEN + 8);
            self.move_font_records(list as usize, count, moved);
        }
        (at as u32, entries)
    }

    /// Moves the name offset of every family named by name among the `count` records at
    /// `at`, and marks it to be counted from the start of the buffer.
    fn move_font_records(&mut self, at: usize, count: u32, moved: u32) {
        for index in 0..count as usize {
            let record = at + index * FONT_REF_LEN;
            if record + FONT_REF_LEN > self.payload.len() {
                break;
            }
            if read_u32(&self.payload, record) != FONT_REF_SYSTEM {
                continue;
            }
            let field = record + 4;
            let name = read_u32(&self.payload, field).wrapping_add(moved);
            self.payload[field..field + 4].copy_from_slice(&name.to_le_bytes());
            self.payload_patches.push(field as u32);
        }
    }

    /// The buffer these requests are sent as: the records, then the payload, every offset
    /// counted from its start.
    pub fn encoded(&mut self) -> &[u8] {
        self.finish().0
    }

    /// Lays the records and the payload out as one buffer, every offset counted from its
    /// start, and answers with it and the number of records.
    fn finish(&mut self) -> (&[u8], u32) {
        let base = (self.records.len() * MEASURE_RECORD_LEN) as u32;
        self.buffer.clear();
        self.buffer.reserve(base as usize + self.payload.len());
        for record in &self.records {
            let mut bytes = record.bytes;
            if u16::from_le_bytes([bytes[0], bytes[1]]) == MEASURE_TEXT {
                for field in [
                    TEXT_TEXT_OFFSET_AT,
                    TEXT_SPANS_OFFSET_AT,
                    TEXT_SPAN_FONTS_OFFSET_AT,
                    TEXT_FONT_OFFSET_AT,
                ] {
                    let value = read_u32(&bytes, field) + base;
                    put_u32(&mut bytes, field, value);
                }
            }
            self.buffer.extend_from_slice(&bytes);
        }
        let payload_at = self.buffer.len();
        self.buffer.extend_from_slice(&self.payload);
        for &patch in &self.payload_patches {
            let at = payload_at + patch as usize;
            let value = read_u32(&self.buffer, at) + base;
            self.buffer[at..at + 4].copy_from_slice(&value.to_le_bytes());
        }
        (&self.buffer, self.records.len() as u32)
    }
}

/// The right to ask, for the length of one Host call.
///
/// Not `Send`: the Renderer answers on its UI thread only, and the Host call this was
/// handed out in is on that thread.
pub struct Measurer {
    _on_this_thread: PhantomData<*const ()>,
}

/// Why nothing was measured.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum MeasureError {
    /// Not inside a Host call, so the Renderer is not waiting on this thread to answer.
    OutsideHostCall,
    /// The Renderer refused the whole call. The answers were left at zero size, and the
    /// layout carries on with them.
    Refused(ProtocolError),
}

/// A measurer, if the Host is inside one of its own calls on this thread, which is the
/// only time there is a Renderer standing still to answer.
pub fn measurer() -> Result<Measurer, MeasureError> {
    if crate::boundary::in_host_call() {
        Ok(Measurer {
            _on_this_thread: PhantomData,
        })
    } else {
        Err(MeasureError::OutsideHostCall)
    }
}

impl Measurer {
    /// Asks every question in `requests` in one call and puts the answers in `results`, in
    /// the order they were asked.
    ///
    /// A request the Renderer could not answer says so in its own status, and the rest are
    /// answered. A call the Renderer refused as a whole leaves every answer at zero size
    /// and comes back as an error, which the layout is free to carry on past: nothing
    /// stops, and the next frame asks again.
    pub fn measure(
        &mut self,
        requests: &mut MeasureRequests,
        results: &mut Vec<MeasureResult>,
    ) -> Result<(), MeasureError> {
        let (buffer, count) = requests.finish();
        results.clear();
        results.resize(count as usize, MeasureResult::default());
        self.measure_encoded(buffer, count, results)
    }

    /// The same, for a request buffer encoded by hand. `results` must have one slot per
    /// record.
    pub fn measure_encoded(
        &mut self,
        buffer: &[u8],
        count: u32,
        results: &mut [MeasureResult],
    ) -> Result<(), MeasureError> {
        if results.len() < count as usize {
            return Err(MeasureError::Refused(ProtocolError::LengthOverflow));
        }
        if count == 0 {
            return Ok(());
        }
        let length = u32::try_from(buffer.len())
            .map_err(|_| MeasureError::Refused(ProtocolError::LengthOverflow))?;
        let status =
            crate::boundary::renderer_measure(buffer.as_ptr(), length, count, results.as_mut_ptr());
        if status < 0 {
            for result in results.iter_mut().take(count as usize) {
                *result = MeasureResult::REFUSED;
            }
            report_refusal(status);
            return Err(MeasureError::Refused(ProtocolError::MeasureRefused(status)));
        }
        Ok(())
    }
}

/// Says once per kind of refusal what went wrong, on standard error. A refusal is the
/// Renderer's or the Host's bug rather than the application's, and a layout that silently
/// had every size at zero is the hardest kind of wrong to find.
fn report_refusal(status: i32) {
    use std::sync::atomic::{AtomicU32, Ordering};
    static SAID: AtomicU32 = AtomicU32::new(0);
    let bit = 1u32 << (status.unsigned_abs().min(31));
    if SAID.fetch_or(bit, Ordering::Relaxed) & bit != 0 {
        return;
    }
    let reason = match status {
        MEASURE_UNREADABLE => "the request buffer could not be read",
        MEASURE_OFF_UI_THREAD => "the call was made off the Renderer's UI thread",
        MEASURE_UNAVAILABLE => {
            "the Renderer has nothing to measure with yet (no first frame, or no renderer)"
        }
        _ => "the Renderer gave no reason",
    };
    eprintln!(
        "compose-rust: a measure call was refused with status {status}: {reason}. Every \
         size in it is zero and the layout carried on."
    );
}

fn put_u16(bytes: &mut [u8], at: usize, value: u16) {
    bytes[at..at + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_u32(bytes: &mut [u8], at: usize, value: u32) {
    bytes[at..at + 4].copy_from_slice(&value.to_le_bytes());
}

fn put_f32(bytes: &mut [u8], at: usize, value: f32) {
    bytes[at..at + 4].copy_from_slice(&value.to_bits().to_le_bytes());
}

fn read_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

// ---------------------------------------------------------------------------------------
// The mock renderer's answers.
//
// A test of the Host has no Renderer to ask, and wants answers it can predict. These are
// the same rules the stand-in renderer's C answers by: every character is half the font
// size wide, a line is one and a quarter font sizes tall, the first baseline sits one font
// size down, and lines break at spaces. No node is known, because nothing applies a tree.
// ---------------------------------------------------------------------------------------

/// The font size the mock takes for text that does not give one.
pub const FAKE_FONT_SIZE: f32 = 14.0;

/// Answers a measure call the way the mock renderer does. Refuses a call from a thread
/// that is not inside a Host call, which is what "off the UI thread" means to a Renderer
/// that has no UI thread of its own.
///
/// # Safety
/// `requests` must address `length` readable bytes, and `results` writable room for
/// `count` results.
#[doc(hidden)]
pub unsafe extern "C" fn fake_measure(
    requests: *const u8,
    length: u32,
    count: u32,
    results: *mut MeasureResult,
) -> i32 {
    if !crate::boundary::in_host_call() {
        return MEASURE_OFF_UI_THREAD;
    }
    if requests.is_null() || results.is_null() {
        return MEASURE_UNREADABLE;
    }
    // SAFETY: the caller promises `length` readable bytes.
    let buffer = unsafe { std::slice::from_raw_parts(requests, length as usize) };
    // SAFETY: the caller promises room for `count` results.
    let results = unsafe { std::slice::from_raw_parts_mut(results, count as usize) };
    fake_measure_slices(buffer, results)
}

/// The mock's rules over slices, for whoever already has them.
#[doc(hidden)]
pub fn fake_measure_slices(buffer: &[u8], results: &mut [MeasureResult]) -> i32 {
    let count = results.len();
    if buffer.len() < count * MEASURE_RECORD_LEN {
        return MEASURE_UNREADABLE;
    }
    for (index, result) in results.iter_mut().enumerate() {
        let record = &buffer[index * MEASURE_RECORD_LEN..(index + 1) * MEASURE_RECORD_LEN];
        *result = fake_one(buffer, record);
    }
    0
}

fn fake_one(buffer: &[u8], record: &[u8]) -> MeasureResult {
    let malformed = MeasureResult {
        status: RESULT_MALFORMED,
        ..MeasureResult::REFUSED
    };
    match u16::from_le_bytes([record[0], record[1]]) {
        MEASURE_NODE => MeasureResult {
            status: RESULT_UNKNOWN_NODE,
            ..MeasureResult::REFUSED
        },
        MEASURE_TEXT => {
            let at = read_u32(record, TEXT_TEXT_OFFSET_AT) as usize;
            let length = read_u32(record, TEXT_TEXT_LENGTH_AT) as usize;
            let Some(text) = at
                .checked_add(length)
                .and_then(|end| buffer.get(at..end))
                .and_then(|bytes| std::str::from_utf8(bytes).ok())
            else {
                return malformed;
            };
            let size = f32::from_bits(read_u32(record, TEXT_FONT_SIZE_AT));
            let size = if size > 0.0 { size } else { FAKE_FONT_SIZE };
            let line_height = f32::from_bits(read_u32(record, TEXT_LINE_HEIGHT_AT));
            let line_height = if line_height.is_nan() || line_height <= 0.0 {
                size * 1.25
            } else {
                line_height
            };
            let max_lines = read_u32(record, TEXT_MAX_LINES_AT);
            let wrap = record[TEXT_WRAP_AT] != 0;
            let advance = size * 0.5;
            let width = f32::from_bits(read_u32(record, TEXT_WIDTH_AT));
            let lines = match read_u32(record, TEXT_CONSTRAINT_AT) {
                CONSTRAINT_MIN_CONTENT => {
                    fake_lines(text, if wrap { 0.0 } else { f32::INFINITY }, advance)
                }
                CONSTRAINT_MAX_CONTENT => fake_lines(text, f32::INFINITY, advance),
                CONSTRAINT_AT_MOST if width >= 0.0 => {
                    fake_lines(text, if wrap { width } else { f32::INFINITY }, advance)
                }
                _ => return malformed,
            };
            let shown = if max_lines == 0 {
                lines.len()
            } else {
                lines.len().min(max_lines as usize)
            };
            let widest = lines[..shown].iter().copied().fold(0.0f32, f32::max);
            MeasureResult {
                width: widest,
                height: shown as f32 * line_height,
                first_baseline: size,
                last_baseline: (shown.max(1) - 1) as f32 * line_height + size,
                last_line_width: lines[shown.max(1) - 1],
                line_count: shown as u32,
                flags: if shown < lines.len() {
                    FLAG_TRUNCATED
                } else {
                    0
                },
                status: RESULT_OK,
            }
        }
        _ => malformed,
    }
}

/// The widths of the lines `text` breaks into at `width`, breaking at spaces and at
/// newlines. A width of zero puts every word on its own line.
fn fake_lines(text: &str, width: f32, advance: f32) -> Vec<f32> {
    let mut lines = Vec::new();
    for paragraph in text.split('\n') {
        let mut line = 0usize;
        for word in paragraph.split(' ') {
            let word_length = word.chars().count();
            let joined = if line == 0 {
                word_length
            } else {
                line + 1 + word_length
            };
            if line != 0 && joined as f32 * advance > width {
                lines.push(line as f32 * advance);
                line = word_length;
            } else {
                line = joined;
            }
        }
        lines.push(line as f32 * advance);
    }
    lines
}
