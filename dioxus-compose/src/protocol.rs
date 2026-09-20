//! Fixed-layout little-endian boundary protocol.

use crate::schema::{
    ColorScheme, DesignSystem, Key, Modifier, Paint, PropertyKind, Selection, ShapeRole, SpaceRole,
    Theme, WidgetKind,
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
const ENVELOPE_LEN: usize = 12;
/// The shortest mutation record on the wire (`Remove`: 4-byte header + `node_id`).
const MIN_RECORD_LEN: usize = 8;

const VALUE_NONE: u16 = 0;
const VALUE_STRING: u16 = 1;
const VALUE_BOOL: u16 = 2;
const VALUE_I64: u16 = 3;
const VALUE_F32: u16 = 4;

#[derive(Clone, Debug, PartialEq)]
pub enum PropertyValue<'a> {
    None,
    String(&'a str),
    Bool(bool),
    Integer(i64),
    Float(f32),
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
    InvalidStringRange,
    InvalidUtf8,
    LengthOverflow,
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
// Tags 8 to 15 are held for the pointer gesture events.
const EVENT_VALUE_CHANGED: u16 = 16;

const MODIFIER_SHIFT: u8 = 1 << 0;
const MODIFIER_CTRL: u8 = 1 << 1;
const MODIFIER_ALT: u8 = 1 << 2;
const MODIFIER_META: u8 = 1 << 3;

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
        EVENT_PROTOCOL_ERROR if record_len == 28 => crate::schema::EventPayload::ProtocolError {
            code: read_u32(bytes, 16)?,
            message: read_string(bytes, 20, record_len)?,
        },
        EVENT_KEY_DOWN if record_len == 20 => {
            let raw_key = read_u16(bytes, 16)?;
            let modifiers = *bytes.get(18).ok_or(ProtocolError::Truncated)?;
            crate::schema::EventPayload::KeyDown {
                key: Key::try_from(raw_key)
                    .map_err(|()| ProtocolError::InvalidValueKind(raw_key))?,
                shift_key: modifiers & MODIFIER_SHIFT != 0,
                ctrl_key: modifiers & MODIFIER_CTRL != 0,
                alt_key: modifiers & MODIFIER_ALT != 0,
                meta_key: modifiers & MODIFIER_META != 0,
            }
        }
        EVENT_RANGE_REQUESTED if record_len == 24 => crate::schema::EventPayload::RangeRequested {
            start: read_u32(bytes, 16)?,
            count: read_u32(bytes, 20)?,
        },
        EVENT_VALUE_CHANGED if record_len == 20 => crate::schema::EventPayload::ValueChanged {
            value: f32::from_bits(read_u32(bytes, 16)?),
        },
        EVENT_CLICK..=EVENT_RANGE_REQUESTED | EVENT_VALUE_CHANGED => {
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
    } = event.payload
    {
        output.extend_from_slice(&EVENT_KEY_DOWN.to_le_bytes());
        output.extend_from_slice(&20_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&(key as u16).to_le_bytes());
        let modifiers = (u8::from(shift_key) * MODIFIER_SHIFT)
            | (u8::from(ctrl_key) * MODIFIER_CTRL)
            | (u8::from(alt_key) * MODIFIER_ALT)
            | (u8::from(meta_key) * MODIFIER_META);
        output.push(modifiers);
        output.push(0);
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
    if let crate::schema::EventPayload::ValueChanged { value } = event.payload {
        output.extend_from_slice(&EVENT_VALUE_CHANGED.to_le_bytes());
        output.extend_from_slice(&20_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&value.to_bits().to_le_bytes());
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
        crate::schema::EventPayload::ProtocolError { code, message } => {
            (EVENT_PROTOCOL_ERROR, 28, Some(*message), Some(*code))
        }
        crate::schema::EventPayload::KeyDown { .. }
        | crate::schema::EventPayload::RangeRequested { .. }
        | crate::schema::EventPayload::ValueChanged { .. } => unreachable!(),
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
                }
            }
            Mutation::SetModifier {
                node_id,
                index,
                modifier,
            } => {
                self.begin_record(TAG_SET_MODIFIER, 24);
                self.put_u32(*node_id);
                self.put_u16(*index);
                let (tag, first, second) = modifier_fields(modifier);
                self.put_u16(tag);
                self.put_u64(first);
                self.put_u64(second);
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
            Mutation::SetTheme(theme) => {
                self.begin_record(TAG_SET_THEME, 8);
                self.put_u16(theme.design_system as u16);
                self.put_u16(theme.fallback as u16);
                self.put_u16(theme.color_scheme as u16);
                self.put_u16(u16::from(theme.adaptive));
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

    pub fn capacity(&self) -> usize {
        self.records.capacity() + self.strings.capacity() + self.arena.capacity()
    }

    fn begin_record(&mut self, tag: u16, payload_len: u16) {
        self.put_u16(tag);
        self.put_u16(payload_len + 4);
    }

    fn put_string_ref(&mut self, value: &str) -> Result<(), ProtocolError> {
        let offset =
            u32::try_from(self.strings.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        let len = u32::try_from(value.len()).map_err(|_| ProtocolError::LengthOverflow)?;
        self.string_fixups.push(self.records.len());
        self.put_u32(offset);
        self.put_u32(len);
        self.strings.extend_from_slice(value.as_bytes());
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
    // record region could actually hold. The shortest mutation record is `Remove` at 8
    // bytes, so that is the ceiling. Reserving `count` directly let a 12-byte message ask
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
                    other => return Err(ProtocolError::InvalidValueKind(other)),
                };
                Mutation::SetProp {
                    node_id: read_u32(bytes, payload)?,
                    property: PropertyKind::try_from(property_raw)
                        .map_err(|()| ProtocolError::InvalidProperty(property_raw))?,
                    value,
                }
            }
            TAG_SET_MODIFIER if len == 28 => Mutation::SetModifier {
                node_id: read_u32(bytes, payload)?,
                index: read_u16(bytes, payload + 4)?,
                modifier: decode_modifier(
                    read_u16(bytes, payload + 6)?,
                    read_u64(bytes, payload + 8)?,
                    read_u64(bytes, payload + 16)?,
                )?,
            },
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
            TAG_SET_THEME if len == 12 => {
                let design_system = read_u16(bytes, payload)?;
                let fallback = read_u16(bytes, payload + 2)?;
                let color_scheme = read_u16(bytes, payload + 4)?;
                let adaptive = read_u16(bytes, payload + 6)?;
                if adaptive > 1 {
                    return Err(ProtocolError::InvalidTheme(adaptive));
                }
                Mutation::SetTheme(Theme {
                    design_system: DesignSystem::try_from(design_system)
                        .map_err(|()| ProtocolError::InvalidTheme(design_system))?,
                    fallback: DesignSystem::try_from(fallback)
                        .map_err(|()| ProtocolError::InvalidTheme(fallback))?,
                    color_scheme: ColorScheme::try_from(color_scheme)
                        .map_err(|()| ProtocolError::InvalidTheme(color_scheme))?,
                    adaptive: adaptive == 1,
                })
            }
            TAG_CREATE..=TAG_SET_THEME => {
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

fn modifier_fields(modifier: &Modifier) -> (u16, u64, u64) {
    match modifier {
        Modifier::Empty => (0, 0, 0),
        Modifier::Padding(value) => (1, u64::from(value.to_bits()), 0),
        Modifier::FillMaxWidth => (2, 0, 0),
        Modifier::FillMaxHeight => (3, 0, 0),
        Modifier::Width(value) => (4, u64::from(value.to_bits()), 0),
        Modifier::Height(value) => (5, u64::from(value.to_bits()), 0),
        Modifier::Size { width, height } => {
            (6, u64::from(width.to_bits()), u64::from(height.to_bits()))
        }
        Modifier::Background(paint) => (7, paint.to_bits(), 0),
        Modifier::Clickable { handler_id } => (8, *handler_id, 0),
        Modifier::PaddingRole(role) => (9, *role as u64, 0),
        Modifier::PaddingEach {
            start,
            top,
            end,
            bottom,
        } => (10, pack_floats(*start, *top), pack_floats(*end, *bottom)),
        Modifier::Weight(value) => (11, u64::from(value.to_bits()), 0),
        Modifier::Shape {
            top_start,
            top_end,
            bottom_end,
            bottom_start,
        } => (
            12,
            pack_floats(*top_start, *top_end),
            pack_floats(*bottom_end, *bottom_start),
        ),
        Modifier::ShapeRole(role) => (13, *role as u64, 0),
        Modifier::Border { width, paint } => (14, u64::from(width.to_bits()), paint.to_bits()),
        Modifier::Elevation(dp) => (15, u64::from(dp.to_bits()), 0),
    }
}

fn decode_role<T: TryFrom<u16, Error = ()>>(first: u64) -> Result<T, ProtocolError> {
    let tag = u16::try_from(first).map_err(|_| ProtocolError::InvalidModifier(0))?;
    T::try_from(tag).map_err(|()| ProtocolError::InvalidModifier(tag))
}

fn decode_paint(bits: u64) -> Result<Paint, ProtocolError> {
    Paint::from_bits(bits).ok_or(ProtocolError::InvalidModifier(0))
}

fn decode_modifier(tag: u16, first: u64, second: u64) -> Result<Modifier, ProtocolError> {
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
    let value = bytes
        .get(offset..end)
        .ok_or(ProtocolError::InvalidStringRange)?;
    std::str::from_utf8(value).map_err(|_| ProtocolError::InvalidUtf8)
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

    /// `SetTheme` is a 12-byte record of four `u16` fields.
    #[test]
    fn fr14_set_theme_round_trips_in_twelve_bytes() {
        let theme = Theme::adaptive(DesignSystem::Cupertino).with_color_scheme(ColorScheme::Dark);
        let mut encoder = BatchEncoder::default();
        encoder.encode(&Mutation::SetTheme(theme)).unwrap();
        let bytes = encoder.finish().unwrap();
        assert_eq!(bytes.len(), ENVELOPE_LEN + 12);
        assert_eq!(read_u16(bytes, ENVELOPE_LEN).unwrap(), TAG_SET_THEME);
        assert_eq!(read_u16(bytes, ENVELOPE_LEN + 2).unwrap(), 12);
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
                },
            },
        ];
        let mut bytes = Vec::new();
        for event in events {
            encode_event(&event, &mut bytes).unwrap();
            assert_eq!(decode_event(&bytes).unwrap(), event);
        }
    }
}
