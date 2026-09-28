//! Fixed-layout little-endian boundary protocol.

use crate::schema::{
    AssetKind, ColorScheme, DesignSystem, Key, MaterialRole, MessageDuration, Modifier, MotionRole,
    Paint, PropertyKind, Selection, ShapeRole, SpaceRole, TYPE_ROLE_COUNT, Theme, TypeRole,
    WidgetKind,
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
const ENVELOPE_LEN: usize = 12;
/// The shortest mutation record on the wire (`Remove`: 4-byte header + `node_id`).
const MIN_RECORD_LEN: usize = 8;

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
    /// stands the window up: `dioxus_compose_host_init` answers with the first batch, so
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
// Tags 8 to 15 are reserved for the pointer gesture events, so this one starts at 16
// and the window size report continues from 17.
const EVENT_VALUE_CHANGED: u16 = 16;
const EVENT_WINDOW_SIZE_CHANGED: u16 = 17;
const EVENT_RESYNC: u16 = 18;
const EVENT_LIFECYCLE_START: u16 = 19;
const EVENT_LIFECYCLE_STOP: u16 = 20;
const EVENT_DESIGN_SYSTEM_RESOLVED: u16 = 21;
const EVENT_FILES_ENTERED: u16 = 22;
const EVENT_FILES_DROPPED: u16 = 23;

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
        EVENT_VALUE_CHANGED if record_len == 24 => {
            crate::schema::EventPayload::ValueChanged(f64::from_bits(read_u64(bytes, 16)?))
        }
        EVENT_WINDOW_SIZE_CHANGED if record_len == 28 => {
            let raw_class = read_u32(bytes, 24)?;
            let class = u16::try_from(raw_class)
                .ok()
                .and_then(|value| crate::schema::WindowSizeClass::try_from(value).ok())
                .ok_or(ProtocolError::InvalidValueKind(raw_class as u16))?;
            crate::schema::EventPayload::WindowSizeChanged {
                width_dp: f32::from_bits(read_u32(bytes, 16)?),
                height_dp: f32::from_bits(read_u32(bytes, 20)?),
                class,
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
        EVENT_RESYNC if record_len == 16 => crate::schema::EventPayload::Resync,
        EVENT_LIFECYCLE_START if record_len == 16 => crate::schema::EventPayload::LifecycleStart,
        EVENT_LIFECYCLE_STOP if record_len == 16 => crate::schema::EventPayload::LifecycleStop,
        EVENT_CLICK..=EVENT_RANGE_REQUESTED | EVENT_VALUE_CHANGED..=EVENT_LIFECYCLE_STOP => {
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
    } = event.payload
    {
        output.extend_from_slice(&EVENT_WINDOW_SIZE_CHANGED.to_le_bytes());
        output.extend_from_slice(&28_u16.to_le_bytes());
        output.extend_from_slice(&event.node_id.to_le_bytes());
        output.extend_from_slice(&event.handler_id.to_le_bytes());
        output.extend_from_slice(&width_dp.to_bits().to_le_bytes());
        output.extend_from_slice(&height_dp.to_bits().to_le_bytes());
        output.extend_from_slice(&u32::from(u16::from(class)).to_le_bytes());
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
        crate::schema::EventPayload::FilesEntered => (EVENT_FILES_ENTERED, 16, None, None),
        crate::schema::EventPayload::FilesDropped(paths) => {
            (EVENT_FILES_DROPPED, 24, Some(*paths), None)
        }
        crate::schema::EventPayload::Resync => (EVENT_RESYNC, 16, None, None),
        crate::schema::EventPayload::LifecycleStart => (EVENT_LIFECYCLE_START, 16, None, None),
        crate::schema::EventPayload::LifecycleStop => (EVENT_LIFECYCLE_STOP, 16, None, None),
        crate::schema::EventPayload::ProtocolError { code, message } => {
            (EVENT_PROTOCOL_ERROR, 28, Some(*message), Some(*code))
        }
        crate::schema::EventPayload::KeyDown { .. }
        | crate::schema::EventPayload::RangeRequested { .. }
        | crate::schema::EventPayload::ValueChanged(_)
        | crate::schema::EventPayload::WindowSizeChanged { .. }
        | crate::schema::EventPayload::DesignSystemResolved(_) => unreachable!(),
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
            Mutation::SetTheme(theme) => {
                self.begin_record(TAG_SET_THEME, 8 + 4 * TYPE_ROLE_COUNT as u16);
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
            }
            Mutation::SetWindow(window) => {
                self.begin_record(TAG_SET_WINDOW, 24);
                self.put_u16(window.chrome as u16);
                self.put_u16(window.width);
                self.put_u16(window.height);
                self.put_u16(window.min_width);
                self.put_u16(window.min_height);
                self.put_u16(u16::from(window.resizable));
                self.put_string_ref(window.title)?;
                self.put_u32(window.icon);
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

    fn begin_record(&mut self, tag: u16, payload_len: u16) {
        self.put_u16(tag);
        self.put_u16(payload_len + 4);
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
            TAG_SET_THEME if len == 12 + 4 * TYPE_ROLE_COUNT => {
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
                Mutation::SetTheme(Theme {
                    design_system: DesignSystem::try_from(design_system)
                        .map_err(|()| ProtocolError::InvalidTheme(design_system))?,
                    fallback: DesignSystem::try_from(fallback)
                        .map_err(|()| ProtocolError::InvalidTheme(fallback))?,
                    color_scheme: ColorScheme::try_from(color_scheme)
                        .map_err(|()| ProtocolError::InvalidTheme(color_scheme))?,
                    adaptive: adaptive == 1,
                    fonts,
                })
            }
            TAG_SET_WINDOW if len == 28 => {
                let chrome = read_u16(bytes, payload)?;
                let resizable = read_u16(bytes, payload + 10)?;
                if resizable > 1 {
                    return Err(ProtocolError::InvalidValueKind(resizable));
                }
                Mutation::SetWindow(crate::schema::Window {
                    chrome: crate::schema::Chrome::try_from(chrome)
                        .map_err(|()| ProtocolError::InvalidValueKind(chrome))?,
                    width: read_u16(bytes, payload + 2)?,
                    height: read_u16(bytes, payload + 4)?,
                    min_width: read_u16(bytes, payload + 6)?,
                    min_height: read_u16(bytes, payload + 8)?,
                    resizable: resizable == 1,
                    // Leaked on purpose, once per window. The record's owner is the
                    // window, the window outlives the batch it arrived in, and there is
                    // one of these per process.
                    icon: read_u32(bytes, payload + 20)?,
                    title: Box::leak(
                        read_string(bytes, payload + 12, records_len)?
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
            TAG_CREATE..=TAG_SHOW_MESSAGE => {
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
        Modifier::ObserveSize { token } => (16, u64::from(*token), 0),
        Modifier::Motion(role) => (17, *role as u64, 0),
        Modifier::Material(role) => (18, *role as u64, 0),
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
        16 => Ok(Modifier::ObserveSize {
            token: first as u32,
        }),
        17 => Ok(Modifier::Motion(decode_role::<MotionRole>(first)?)),
        18 => Ok(Modifier::Material(decode_role::<MaterialRole>(first)?)),
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
        let length = 12 + 4 * TYPE_ROLE_COUNT as u16;
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

    #[test]
    fn fr20_window_size_changed_round_trips_in_twenty_eight_bytes() {
        let event = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: crate::EventPayload::WindowSizeChanged {
                width_dp: 841.5,
                height_dp: 600.25,
                class: crate::WindowSizeClass::Expanded,
            },
        };
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).unwrap();
        assert_eq!(bytes.len(), 28);
        assert_eq!(decode_event(&bytes).unwrap(), event);
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
}
