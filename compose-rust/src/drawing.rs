//! The closed drawing command set a `Canvas` node carries.
//!
//! The Renderer is compiled ahead of time, so drawing code cannot cross the boundary. A
//! fixed vocabulary of commands does instead. Every command is the same 36 bytes, which is
//! what lets a reader skip a command it does not know and what keeps the list free of
//! pointers: a path with many points is registered once as an asset and referenced by id.
//!
//! Colour is a [`Paint`], never a raw colour, so a canvas can draw in a `ColorRole` and the
//! design system still decides what that role looks like.

use crate::protocol::ProtocolError;
use crate::schema::{Paint, TypeRole};
use std::rc::Rc;

/// Bytes of one command record: tag, length, paint, then six 32-bit words.
pub const COMMAND_LEN: usize = 36;

pub const TAG_LINE: u16 = 1;
pub const TAG_RECT: u16 = 2;
pub const TAG_ROUND_RECT: u16 = 3;
pub const TAG_CIRCLE: u16 = 4;
pub const TAG_ARC: u16 = 5;
pub const TAG_POLYLINE_REF: u16 = 6;
pub const TAG_TEXT_AT: u16 = 7;

/// How one of the six words of a command is read back.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DrawFieldType {
    /// A dp measurement, or degrees for an arc.
    Float,
    /// An asset id or a string offset.
    U32,
    /// A role enum, named so codegen can emit the matching Kotlin type.
    Role(&'static str),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct DrawFieldSchema {
    pub name: &'static str,
    pub ty: DrawFieldType,
    /// Which of the six words, counting from zero.
    pub word: u8,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct DrawCommandSchema {
    pub name: &'static str,
    pub tag: u16,
    pub fields: &'static [DrawFieldSchema],
}

const fn float(name: &'static str, word: u8) -> DrawFieldSchema {
    DrawFieldSchema {
        name,
        ty: DrawFieldType::Float,
        word,
    }
}

const fn unsigned(name: &'static str, word: u8) -> DrawFieldSchema {
    DrawFieldSchema {
        name,
        ty: DrawFieldType::U32,
        word,
    }
}

/// Every command in wire order. Appending is the only allowed edit.
pub const DRAW_COMMAND_SCHEMA: &[DrawCommandSchema] = &[
    DrawCommandSchema {
        name: "Line",
        tag: TAG_LINE,
        fields: &[
            float("x1", 0),
            float("y1", 1),
            float("x2", 2),
            float("y2", 3),
            float("strokeWidth", 4),
        ],
    },
    DrawCommandSchema {
        name: "Rect",
        tag: TAG_RECT,
        fields: &[
            float("x", 0),
            float("y", 1),
            float("width", 2),
            float("height", 3),
            // Zero fills the rectangle rather than stroking an invisible outline.
            float("strokeWidth", 4),
        ],
    },
    DrawCommandSchema {
        name: "RoundRect",
        tag: TAG_ROUND_RECT,
        fields: &[
            float("x", 0),
            float("y", 1),
            float("width", 2),
            float("height", 3),
            float("radius", 4),
            float("strokeWidth", 5),
        ],
    },
    DrawCommandSchema {
        name: "Circle",
        tag: TAG_CIRCLE,
        fields: &[
            float("centerX", 0),
            float("centerY", 1),
            float("radius", 2),
            float("strokeWidth", 3),
        ],
    },
    DrawCommandSchema {
        name: "Arc",
        tag: TAG_ARC,
        fields: &[
            float("centerX", 0),
            float("centerY", 1),
            float("radius", 2),
            float("startDegrees", 3),
            float("sweepDegrees", 4),
            float("strokeWidth", 5),
        ],
    },
    DrawCommandSchema {
        name: "PolylineRef",
        tag: TAG_POLYLINE_REF,
        fields: &[unsigned("assetId", 0), float("strokeWidth", 1)],
    },
    DrawCommandSchema {
        name: "TextAt",
        tag: TAG_TEXT_AT,
        fields: &[
            unsigned("textOffset", 0),
            unsigned("textLength", 1),
            float("x", 2),
            float("y", 3),
            DrawFieldSchema {
                name: "typeRole",
                ty: DrawFieldType::Role("TypeRole"),
                word: 4,
            },
        ],
    },
];

/// One drawing command. Coordinates are dp from the widget's top-left corner.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum DrawCommand {
    Line {
        paint: Paint,
        x1: f32,
        y1: f32,
        x2: f32,
        y2: f32,
        stroke_width: f32,
    },
    /// A stroke width of zero fills the rectangle.
    Rect {
        paint: Paint,
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        stroke_width: f32,
    },
    RoundRect {
        paint: Paint,
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        radius: f32,
        stroke_width: f32,
    },
    Circle {
        paint: Paint,
        center_x: f32,
        center_y: f32,
        radius: f32,
        stroke_width: f32,
    },
    Arc {
        paint: Paint,
        center_x: f32,
        center_y: f32,
        radius: f32,
        start_degrees: f32,
        sweep_degrees: f32,
        stroke_width: f32,
    },
    /// Points live in a registered asset, so a path of any length costs one record.
    PolylineRef {
        paint: Paint,
        asset_id: u32,
        stroke_width: f32,
    },
    /// The text bytes follow the command array inside the same list, and the offset is
    /// measured from the start of that list, so the list decodes on its own.
    TextAt {
        paint: Paint,
        text_offset: u32,
        text_length: u32,
        x: f32,
        y: f32,
        type_role: TypeRole,
    },
}

impl DrawCommand {
    pub const fn tag(&self) -> u16 {
        match self {
            Self::Line { .. } => TAG_LINE,
            Self::Rect { .. } => TAG_RECT,
            Self::RoundRect { .. } => TAG_ROUND_RECT,
            Self::Circle { .. } => TAG_CIRCLE,
            Self::Arc { .. } => TAG_ARC,
            Self::PolylineRef { .. } => TAG_POLYLINE_REF,
            Self::TextAt { .. } => TAG_TEXT_AT,
        }
    }

    pub const fn paint(&self) -> Paint {
        match *self {
            Self::Line { paint, .. }
            | Self::Rect { paint, .. }
            | Self::RoundRect { paint, .. }
            | Self::Circle { paint, .. }
            | Self::Arc { paint, .. }
            | Self::PolylineRef { paint, .. }
            | Self::TextAt { paint, .. } => paint,
        }
    }

    /// The six words, in order, as raw 32-bit patterns.
    fn words(&self) -> [u32; 6] {
        let mut words = [0_u32; 6];
        match *self {
            Self::Line {
                x1,
                y1,
                x2,
                y2,
                stroke_width,
                ..
            } => {
                words[0] = x1.to_bits();
                words[1] = y1.to_bits();
                words[2] = x2.to_bits();
                words[3] = y2.to_bits();
                words[4] = stroke_width.to_bits();
            }
            Self::Rect {
                x,
                y,
                width,
                height,
                stroke_width,
                ..
            } => {
                words[0] = x.to_bits();
                words[1] = y.to_bits();
                words[2] = width.to_bits();
                words[3] = height.to_bits();
                words[4] = stroke_width.to_bits();
            }
            Self::RoundRect {
                x,
                y,
                width,
                height,
                radius,
                stroke_width,
                ..
            } => {
                words[0] = x.to_bits();
                words[1] = y.to_bits();
                words[2] = width.to_bits();
                words[3] = height.to_bits();
                words[4] = radius.to_bits();
                words[5] = stroke_width.to_bits();
            }
            Self::Circle {
                center_x,
                center_y,
                radius,
                stroke_width,
                ..
            } => {
                words[0] = center_x.to_bits();
                words[1] = center_y.to_bits();
                words[2] = radius.to_bits();
                words[3] = stroke_width.to_bits();
            }
            Self::Arc {
                center_x,
                center_y,
                radius,
                start_degrees,
                sweep_degrees,
                stroke_width,
                ..
            } => {
                words[0] = center_x.to_bits();
                words[1] = center_y.to_bits();
                words[2] = radius.to_bits();
                words[3] = start_degrees.to_bits();
                words[4] = sweep_degrees.to_bits();
                words[5] = stroke_width.to_bits();
            }
            Self::PolylineRef {
                asset_id,
                stroke_width,
                ..
            } => {
                words[0] = asset_id;
                words[1] = stroke_width.to_bits();
            }
            Self::TextAt {
                text_offset,
                text_length,
                x,
                y,
                type_role,
                ..
            } => {
                words[0] = text_offset;
                words[1] = text_length;
                words[2] = x.to_bits();
                words[3] = y.to_bits();
                words[4] = u32::from(type_role as u16);
            }
        }
        words
    }

    fn write_to(&self, output: &mut Vec<u8>) {
        output.extend_from_slice(&self.tag().to_le_bytes());
        output.extend_from_slice(&(COMMAND_LEN as u16).to_le_bytes());
        output.extend_from_slice(&self.paint().to_bits().to_le_bytes());
        for word in self.words() {
            output.extend_from_slice(&word.to_le_bytes());
        }
    }

    fn read_from(record: &[u8]) -> Result<Self, ProtocolError> {
        let tag = u16::from_le_bytes([record[0], record[1]]);
        let length = u16::from_le_bytes([record[2], record[3]]);
        if usize::from(length) != COMMAND_LEN {
            return Err(ProtocolError::InvalidRecordLength);
        }
        let paint_bits = u64::from_le_bytes(record[4..12].try_into().expect("eight bytes"));
        let paint = Paint::from_bits(paint_bits).ok_or(ProtocolError::InvalidValueKind(0))?;
        let word = |index: usize| {
            let start = 12 + index * 4;
            u32::from_le_bytes(
                record[start..start + 4]
                    .try_into()
                    .expect("four bytes inside a 36 byte record"),
            )
        };
        let real = |index: usize| f32::from_bits(word(index));
        Ok(match tag {
            TAG_LINE => Self::Line {
                paint,
                x1: real(0),
                y1: real(1),
                x2: real(2),
                y2: real(3),
                stroke_width: real(4),
            },
            TAG_RECT => Self::Rect {
                paint,
                x: real(0),
                y: real(1),
                width: real(2),
                height: real(3),
                stroke_width: real(4),
            },
            TAG_ROUND_RECT => Self::RoundRect {
                paint,
                x: real(0),
                y: real(1),
                width: real(2),
                height: real(3),
                radius: real(4),
                stroke_width: real(5),
            },
            TAG_CIRCLE => Self::Circle {
                paint,
                center_x: real(0),
                center_y: real(1),
                radius: real(2),
                stroke_width: real(3),
            },
            TAG_ARC => Self::Arc {
                paint,
                center_x: real(0),
                center_y: real(1),
                radius: real(2),
                start_degrees: real(3),
                sweep_degrees: real(4),
                stroke_width: real(5),
            },
            TAG_POLYLINE_REF => Self::PolylineRef {
                paint,
                asset_id: word(0),
                stroke_width: real(1),
            },
            TAG_TEXT_AT => {
                let raw_role = u16::try_from(word(4))
                    .map_err(|_| ProtocolError::InvalidValueKind(u16::MAX))?;
                Self::TextAt {
                    paint,
                    text_offset: word(0),
                    text_length: word(1),
                    x: real(2),
                    y: real(3),
                    type_role: TypeRole::try_from(raw_role)
                        .map_err(|()| ProtocolError::InvalidValueKind(raw_role))?,
                }
            }
            other => return Err(ProtocolError::InvalidTag(other)),
        })
    }
}

/// An encoded drawing command list, ready to go on the wire as one property value.
///
/// Cloning shares the bytes, and equality compares them, so a frame that rebuilds the same
/// list produces no `SetProp` at all: `dioxus-core` compares the attribute and skips it.
#[derive(Clone, Default, Eq, PartialEq)]
pub struct DrawList {
    bytes: Option<Rc<[u8]>>,
}

impl std::fmt::Debug for DrawList {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("DrawList")
            .field("bytes", &self.as_bytes().len())
            .finish()
    }
}

impl DrawList {
    pub fn builder() -> DrawListBuilder {
        DrawListBuilder::default()
    }

    /// Adopts an already encoded list, which is what the protocol vectors carry.
    pub fn from_bytes(bytes: impl Into<Rc<[u8]>>) -> Self {
        Self {
            bytes: Some(bytes.into()),
        }
    }

    pub fn as_bytes(&self) -> &[u8] {
        self.bytes.as_deref().unwrap_or(&[])
    }

    pub fn is_empty(&self) -> bool {
        self.as_bytes().is_empty()
    }

    /// The commands, in order. Errors if the bytes are not a well-formed list.
    pub fn decode(&self) -> Result<Vec<DrawCommand>, ProtocolError> {
        decode_commands(self.as_bytes())
    }

    /// The string a `TextAt` command points at.
    pub fn text_at(&self, offset: u32, length: u32) -> Result<&str, ProtocolError> {
        let bytes = self.as_bytes();
        let start = usize::try_from(offset).map_err(|_| ProtocolError::LengthOverflow)?;
        let len = usize::try_from(length).map_err(|_| ProtocolError::LengthOverflow)?;
        let end = start
            .checked_add(len)
            .ok_or(ProtocolError::InvalidStringRange)?;
        let slice = bytes
            .get(start..end)
            .ok_or(ProtocolError::InvalidStringRange)?;
        std::str::from_utf8(slice).map_err(|_| ProtocolError::InvalidUtf8)
    }
}

/// Decodes a command list: `n` fixed-size records followed by the `TextAt` strings.
pub fn decode_commands(bytes: &[u8]) -> Result<Vec<DrawCommand>, ProtocolError> {
    let mut commands = Vec::new();
    let mut position = 0;
    while position + COMMAND_LEN <= bytes.len() {
        let record = &bytes[position..position + COMMAND_LEN];
        // The text region begins where the records stop looking like records. A zero tag
        // cannot be a command, and neither can the first byte of a UTF-8 string that
        // happens to sit here, so the list ends at the first record that does not decode.
        let Ok(command) = DrawCommand::read_from(record) else {
            break;
        };
        commands.push(command);
        position += COMMAND_LEN;
    }
    Ok(commands)
}

/// Builds a command list. Each call appends one fixed-size record; `TextAt` also appends
/// its bytes to the text region, which is written after every record.
#[derive(Debug, Default)]
pub struct DrawListBuilder {
    records: Vec<u8>,
    text: Vec<u8>,
}

impl DrawListBuilder {
    pub fn with_capacity(commands: usize, text_bytes: usize) -> Self {
        Self {
            records: Vec::with_capacity(commands * COMMAND_LEN),
            text: Vec::with_capacity(text_bytes),
        }
    }

    /// Appends any command. `TextAt` offsets are fixed up by [`DrawListBuilder::build`],
    /// so prefer [`DrawListBuilder::text_at`] for text.
    pub fn push(mut self, command: DrawCommand) -> Self {
        command.write_to(&mut self.records);
        self
    }

    pub fn line(self, paint: Paint, x1: f32, y1: f32, x2: f32, y2: f32, stroke_width: f32) -> Self {
        self.push(DrawCommand::Line {
            paint,
            x1,
            y1,
            x2,
            y2,
            stroke_width,
        })
    }

    /// A stroke width of zero fills the rectangle.
    pub fn rect(
        self,
        paint: Paint,
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        stroke_width: f32,
    ) -> Self {
        self.push(DrawCommand::Rect {
            paint,
            x,
            y,
            width,
            height,
            stroke_width,
        })
    }

    #[allow(clippy::too_many_arguments)]
    pub fn round_rect(
        self,
        paint: Paint,
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        radius: f32,
        stroke_width: f32,
    ) -> Self {
        self.push(DrawCommand::RoundRect {
            paint,
            x,
            y,
            width,
            height,
            radius,
            stroke_width,
        })
    }

    pub fn circle(
        self,
        paint: Paint,
        center_x: f32,
        center_y: f32,
        radius: f32,
        stroke_width: f32,
    ) -> Self {
        self.push(DrawCommand::Circle {
            paint,
            center_x,
            center_y,
            radius,
            stroke_width,
        })
    }

    #[allow(clippy::too_many_arguments)]
    pub fn arc(
        self,
        paint: Paint,
        center_x: f32,
        center_y: f32,
        radius: f32,
        start_degrees: f32,
        sweep_degrees: f32,
        stroke_width: f32,
    ) -> Self {
        self.push(DrawCommand::Arc {
            paint,
            center_x,
            center_y,
            radius,
            start_degrees,
            sweep_degrees,
            stroke_width,
        })
    }

    /// Draws a registered point list. The asset holds the points, so the record stays the
    /// same size however long the path is.
    pub fn polyline_ref(self, paint: Paint, asset_id: u32, stroke_width: f32) -> Self {
        self.push(DrawCommand::PolylineRef {
            paint,
            asset_id,
            stroke_width,
        })
    }

    pub fn text_at(
        mut self,
        paint: Paint,
        text: &str,
        x: f32,
        y: f32,
        type_role: TypeRole,
    ) -> Self {
        // Relative to the text region while building; `build` adds the record region's
        // length so the offset ends up relative to the whole list.
        let offset = self.text.len() as u32;
        self.text.extend_from_slice(text.as_bytes());
        self.push(DrawCommand::TextAt {
            paint,
            text_offset: offset,
            text_length: text.len() as u32,
            x,
            y,
            type_role,
        })
    }

    pub fn build(mut self) -> DrawList {
        let records_len = self.records.len() as u32;
        let mut position = 0;
        while position + COMMAND_LEN <= self.records.len() {
            if u16::from_le_bytes([self.records[position], self.records[position + 1]])
                == TAG_TEXT_AT
            {
                let field = position + 12;
                let relative = u32::from_le_bytes(
                    self.records[field..field + 4]
                        .try_into()
                        .expect("four bytes inside a 36 byte record"),
                );
                self.records[field..field + 4]
                    .copy_from_slice(&relative.saturating_add(records_len).to_le_bytes());
            }
            position += COMMAND_LEN;
        }
        self.records.append(&mut self.text);
        DrawList::from_bytes(self.records)
    }
}

impl dioxus_core::IntoAttributeValue for DrawList {
    fn into_value(self) -> dioxus_core::AttributeValue {
        dioxus_core::AttributeValue::any_value(self)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::{Color, ColorRole};

    #[test]
    fn fr17_every_command_round_trips_in_a_fixed_length_record() {
        let list = DrawList::builder()
            .line(Paint::Role(ColorRole::Primary), 1.0, 2.0, 3.0, 4.0, 1.5)
            .rect(
                Paint::Literal(Color::rgb(0x1b1b1f)),
                0.0,
                0.0,
                8.0,
                9.0,
                0.0,
            )
            .round_rect(
                Paint::Role(ColorRole::Surface),
                1.0,
                2.0,
                3.0,
                4.0,
                5.0,
                6.0,
            )
            .circle(Paint::Role(ColorRole::Error), 4.0, 5.0, 6.0, 0.5)
            .arc(
                Paint::Role(ColorRole::Outline),
                1.0,
                2.0,
                3.0,
                0.0,
                90.0,
                2.0,
            )
            .polyline_ref(Paint::Role(ColorRole::Secondary), 42, 3.0)
            .text_at(
                Paint::Role(ColorRole::OnSurface),
                "한글",
                7.0,
                8.0,
                TypeRole::Label,
            )
            .build();

        let commands = list.decode().unwrap();

        assert_eq!(commands.len(), DRAW_COMMAND_SCHEMA.len());
        assert_eq!(
            list.as_bytes().len(),
            DRAW_COMMAND_SCHEMA.len() * COMMAND_LEN + "한글".len(),
        );
        for (command, schema) in commands.iter().zip(DRAW_COMMAND_SCHEMA) {
            assert_eq!(command.tag(), schema.tag, "{} is out of order", schema.name);
        }
    }

    #[test]
    fn fr17_text_offsets_are_relative_to_the_list_itself() {
        let list = DrawList::builder()
            .circle(Paint::Role(ColorRole::Primary), 0.0, 0.0, 1.0, 0.0)
            .text_at(
                Paint::Role(ColorRole::OnSurface),
                "hi",
                1.0,
                2.0,
                TypeRole::Body,
            )
            .text_at(
                Paint::Role(ColorRole::OnSurface),
                "there",
                3.0,
                4.0,
                TypeRole::Body,
            )
            .build();

        let texts: Vec<_> = list
            .decode()
            .unwrap()
            .into_iter()
            .filter_map(|command| match command {
                DrawCommand::TextAt {
                    text_offset,
                    text_length,
                    ..
                } => Some(list.text_at(text_offset, text_length).unwrap().to_owned()),
                _ => None,
            })
            .collect();

        assert_eq!(texts, ["hi", "there"]);
    }

    #[test]
    fn fr17_paint_carries_a_color_role_into_the_canvas() {
        let list = DrawList::builder()
            .line(Paint::Role(ColorRole::Primary), 0.0, 0.0, 1.0, 1.0, 1.0)
            .build();

        assert_eq!(
            list.decode().unwrap()[0].paint(),
            Paint::Role(ColorRole::Primary),
        );
    }

    #[test]
    fn fr17_two_identical_lists_compare_equal() {
        let build = || {
            DrawList::builder()
                .rect(Paint::Role(ColorRole::Surface), 0.0, 0.0, 4.0, 4.0, 0.0)
                .build()
        };

        assert_eq!(build(), build());
    }
}
