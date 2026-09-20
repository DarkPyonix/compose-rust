//! Closed Rust-side schema and metadata used for Kotlin code generation.

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct EnumVariantSchema {
    pub name: &'static str,
    pub tag: u16,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FieldType {
    Float,
    U32,
    U64,
    /// A `Paint`, either a `ColorRole` or a literal ARGB `Color`.
    Paint,
    /// A role enum, named so codegen can emit the matching Kotlin type.
    Role(&'static str),
}

/// Which half of which `(first, second)` word a modifier field occupies.
///
/// Two `f32` share one `u64`: the low 32 bits hold the first value.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FieldSlot {
    FirstLow,
    FirstHigh,
    First,
    SecondLow,
    SecondHigh,
    Second,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FieldSchema {
    pub name: &'static str,
    pub ty: FieldType,
    pub slot: FieldSlot,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct VariantSchema {
    pub name: &'static str,
    pub tag: u16,
    pub fields: &'static [FieldSchema],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum EventPayloadType {
    None,
    Text,
    ProtocolError,
    KeyDown,
    Range,
    Value,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct EventSchema {
    pub name: &'static str,
    pub tag: u16,
    pub payload: EventPayloadType,
}

/// Canonical schema text. Variant order is wire-significant and must only be appended to.
pub const SCHEMA_DESCRIPTOR: &str = concat!(
    "dioxus-compose/v1;",
    "widgets=Column,Row,Box,Text,TextField,Button,Spacer,LazyColumn,ScrollColumn,Checkbox,RadioButton,Switch,Slider,ProgressIndicator,Divider,Card,Surface,Dialog,Menu,Tabs,TopAppBar,LazyRow,Tooltip;",
    "properties=text,placeholder,enabled,multiline,on_click,on_value_change,on_submit,on_focus_lost,on_key_down,item_count,item_key,on_range_requested,type_role,font_size,font_weight,line_height,letter_spacing,color,text_align,max_lines,overflow,arrangement,spacing,space_role,alignment,variant,checked,value,min_value,max_value,steps,determinate,circular,vertical,open,on_dismiss,selected_index;",
    "modifiers=Empty,Padding,FillMaxWidth,FillMaxHeight,Width,Height,Size,Background,Clickable,PaddingRole,PaddingEach,Weight,Shape,ShapeRole,Border,Elevation;",
    "keys=Enter;",
    "events=Clicked,TextChanged,TextSubmitted,FocusLost,ProtocolError,KeyDown,RangeRequested,ValueChanged;",
    "commands=Create,SetProp,SetModifier,Insert,Move,Remove,SetText,AppendText,SetTheme"
);

const fn hash_bytes(mut hash: u64, bytes: &[u8]) -> u64 {
    let mut index = 0;
    while index < bytes.len() {
        hash ^= bytes[index] as u64;
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
        index += 1;
    }
    hash
}

const fn hash_enum_schema(mut hash: u64, schema: &[EnumVariantSchema]) -> u64 {
    let mut index = 0;
    while index < schema.len() {
        hash = hash_bytes(hash, schema[index].name.as_bytes());
        hash = hash_bytes(hash, &schema[index].tag.to_le_bytes());
        index += 1;
    }
    hash
}

const fn schema_hash() -> u64 {
    let mut hash = hash_bytes(0xcbf2_9ce4_8422_2325_u64, SCHEMA_DESCRIPTOR.as_bytes());
    hash = hash_bytes(hash, crate::extensions::SCHEMA_DESCRIPTOR.as_bytes());
    hash = hash_enum_schema(hash, WIDGET_SCHEMA);
    hash = hash_enum_schema(hash, PROPERTY_SCHEMA);
    hash = hash_enum_schema(hash, KEY_SCHEMA);
    let mut role_index = 0;
    while role_index < ROLE_ENUM_SCHEMA.len() {
        hash = hash_bytes(hash, ROLE_ENUM_SCHEMA[role_index].name.as_bytes());
        hash = hash_enum_schema(hash, ROLE_ENUM_SCHEMA[role_index].variants);
        role_index += 1;
    }
    let mut index = 0;
    while index < MODIFIER_SCHEMA.len() {
        let modifier = MODIFIER_SCHEMA[index];
        hash = hash_bytes(hash, modifier.name.as_bytes());
        hash = hash_bytes(hash, &modifier.tag.to_le_bytes());
        let mut field_index = 0;
        while field_index < modifier.fields.len() {
            let field = modifier.fields[field_index];
            hash = hash_bytes(hash, field.name.as_bytes());
            hash = hash_bytes(
                hash,
                &[match field.ty {
                    FieldType::Float => 1,
                    FieldType::U32 => 2,
                    FieldType::U64 => 3,
                    FieldType::Paint => 4,
                    FieldType::Role(_) => 5,
                }],
            );
            if let FieldType::Role(role) = field.ty {
                hash = hash_bytes(hash, role.as_bytes());
            }
            hash = hash_bytes(
                hash,
                &[match field.slot {
                    FieldSlot::FirstLow => 0,
                    FieldSlot::FirstHigh => 1,
                    FieldSlot::First => 2,
                    FieldSlot::SecondLow => 3,
                    FieldSlot::SecondHigh => 4,
                    FieldSlot::Second => 5,
                }],
            );
            field_index += 1;
        }
        index += 1;
    }
    index = 0;
    while index < EVENT_SCHEMA.len() {
        let event = EVENT_SCHEMA[index];
        hash = hash_bytes(hash, event.name.as_bytes());
        hash = hash_bytes(hash, &event.tag.to_le_bytes());
        hash = hash_bytes(
            hash,
            &[match event.payload {
                EventPayloadType::None => 0,
                EventPayloadType::Text => 1,
                EventPayloadType::ProtocolError => 2,
                EventPayloadType::KeyDown => 3,
                EventPayloadType::Range => 4,
                EventPayloadType::Value => 5,
            }],
        );
        index += 1;
    }
    hash
}

/// Stable handshake hash of the complete ordered schema metadata.
pub const SCHEMA_HASH: u64 = schema_hash();
pub const PROTOCOL_VERSION: u16 = 1;

fn wire_name_eq(input: &str, schema_name: &str) -> bool {
    input
        .bytes()
        .filter(|byte| *byte != b'_')
        .map(|byte| byte.to_ascii_lowercase())
        .eq(schema_name.bytes().map(|byte| byte.to_ascii_lowercase()))
}

macro_rules! define_wire_enum {
    ($schema:ident, $name:ident { $($variant:ident = $tag:literal),+ $(,)? }) => {
        #[derive(Clone, Copy, Debug, Eq, PartialEq)]
        #[repr(u16)]
        pub enum $name {
            $($variant = $tag),+
        }

        pub const $schema: &[EnumVariantSchema] = &[
            $(EnumVariantSchema { name: stringify!($variant), tag: $tag }),+
        ];

        impl From<$name> for u16 {
            fn from(value: $name) -> Self {
                value as Self
            }
        }

        impl TryFrom<u16> for $name {
            type Error = ();

            // Spelled `()` rather than `Self::Error`: a `ColorRole::Error` variant would
            // otherwise make the associated item ambiguous.
            fn try_from(value: u16) -> Result<Self, ()> {
                match value {
                    $($tag => Ok(Self::$variant),)+
                    _ => Err(()),
                }
            }
        }

        impl $name {
            #[allow(dead_code)]
            pub(crate) fn from_name(value: &str) -> Result<Self, ()> {
                $(if wire_name_eq(value, stringify!($variant)) {
                    return Ok(Self::$variant);
                })+
                Err(())
            }
        }
    };
}

crate::extensions::define_widget_schema_with_extensions!(define_wire_enum; WIDGET_SCHEMA, WidgetKind {
    Column = 1,
    Row = 2,
    Box = 3,
    Text = 4,
    TextField = 5,
    Button = 6,
    Spacer = 7,
    LazyColumn = 8,
    ScrollColumn = 9,
    Checkbox = 12,
    RadioButton = 13,
    Switch = 14,
    Slider = 15,
    ProgressIndicator = 16,
    Divider = 17,
    Card = 18,
    Surface = 19,
    Dialog = 20,
    Menu = 21,
    Tabs = 22,
    TopAppBar = 23,
    LazyRow = 24,
    Tooltip = 25,
});

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
#[repr(u8)]
pub enum LoopMode {
    #[default]
    Renderer = 0,
    Platform = 1,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Selection {
    pub start: u32,
    pub end: u32,
}

// Compose key identities supported by the M0 schema.
define_wire_enum!(KEY_SCHEMA, Key {
    Enter = 1,
});

/// A role enum that codegen mirrors into Kotlin.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RoleEnumSchema {
    pub name: &'static str,
    pub variants: &'static [EnumVariantSchema],
}

// An opaque ARGB colour. Gradients and image brushes are deliberately absent: they do not
// fit the two words a modifier variant has, and they would need resources to cross the
// boundary, which the fixed-layout records cannot carry.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
#[repr(transparent)]
pub struct Color(pub u32);

impl Color {
    /// An opaque colour from a 24-bit `0xRRGGBB` value.
    pub const fn rgb(value: u32) -> Self {
        Self(0xff00_0000 | (value & 0x00ff_ffff))
    }

    /// A colour from a full 32-bit `0xAARRGGBB` value.
    pub const fn argb(value: u32) -> Self {
        Self(value)
    }

    pub const fn to_argb(self) -> u32 {
        self.0
    }
}

// Semantic colour slots. The design system resolves them, inside the Renderer.
define_wire_enum!(COLOR_ROLE_SCHEMA, ColorRole {
    Primary = 1,
    OnPrimary = 2,
    Secondary = 3,
    OnSecondary = 4,
    Surface = 5,
    OnSurface = 6,
    SurfaceVariant = 7,
    OnSurfaceVariant = 8,
    Background = 9,
    OnBackground = 10,
    Outline = 11,
    OutlineVariant = 12,
    Error = 13,
    OnError = 14,
});

// The nine-rung type ladder every supported design system maps onto.
define_wire_enum!(TYPE_ROLE_SCHEMA, TypeRole {
    Display = 1,
    Headline = 2,
    Title = 3,
    Subtitle = 4,
    Body = 5,
    BodyStrong = 6,
    Label = 7,
    Caption = 8,
    Mono = 9,
});

// Corner roles. The radius is the design system's decision.
define_wire_enum!(SHAPE_ROLE_SCHEMA, ShapeRole {
    None = 1,
    ExtraSmall = 2,
    Small = 3,
    Medium = 4,
    Large = 5,
    Full = 6,
});

// Density roles, because dp density differs per design system.
define_wire_enum!(SPACE_ROLE_SCHEMA, SpaceRole {
    None = 1,
    Xs = 2,
    Sm = 3,
    Md = 4,
    Lg = 5,
    Xl = 6,
    Xxl = 7,
});

define_wire_enum!(TEXT_ALIGN_SCHEMA, TextAlign {
    Start = 1,
    Center = 2,
    End = 3,
    Justify = 4,
});

define_wire_enum!(TEXT_OVERFLOW_SCHEMA, TextOverflow {
    Clip = 1,
    Ellipsis = 2,
    Visible = 3,
});

define_wire_enum!(ARRANGEMENT_SCHEMA, Arrangement {
    Start = 1,
    Center = 2,
    End = 3,
    SpaceBetween = 4,
    SpaceAround = 5,
    SpaceEvenly = 6,
});

define_wire_enum!(ALIGNMENT_SCHEMA, Alignment {
    TopStart = 1,
    TopCenter = 2,
    TopEnd = 3,
    CenterStart = 4,
    Center = 5,
    CenterEnd = 6,
    BottomStart = 7,
    BottomCenter = 8,
    BottomEnd = 9,
});

// The neutral component variant names the design system styles.
define_wire_enum!(BUTTON_VARIANT_SCHEMA, ButtonVariant {
    Filled = 1,
    Tonal = 2,
    Outlined = 3,
    Text = 4,
});

// The design systems of phase one. Later ones append variants here and nowhere else: a new
// design system is one variant plus one token table and rule implementation in the Renderer.
define_wire_enum!(DESIGN_SYSTEM_SCHEMA, DesignSystem {
    Material3 = 1,
    Cupertino = 2,
    Fluent = 3,
});

// Light and dark selection. `FollowSystem` leaves the choice to the Renderer, which learns
// of a system change first and applies it without involving the Host.
define_wire_enum!(COLOR_SCHEME_SCHEMA, ColorScheme {
    Light = 1,
    Dark = 2,
    FollowSystem = 3,
});

/// Every role enum codegen mirrors, in wire order. Appending is the only allowed edit.
pub const ROLE_ENUM_SCHEMA: &[RoleEnumSchema] = &[
    RoleEnumSchema {
        name: "ColorRole",
        variants: COLOR_ROLE_SCHEMA,
    },
    RoleEnumSchema {
        name: "TypeRole",
        variants: TYPE_ROLE_SCHEMA,
    },
    RoleEnumSchema {
        name: "ShapeRole",
        variants: SHAPE_ROLE_SCHEMA,
    },
    RoleEnumSchema {
        name: "SpaceRole",
        variants: SPACE_ROLE_SCHEMA,
    },
    RoleEnumSchema {
        name: "TextAlign",
        variants: TEXT_ALIGN_SCHEMA,
    },
    RoleEnumSchema {
        name: "TextOverflow",
        variants: TEXT_OVERFLOW_SCHEMA,
    },
    RoleEnumSchema {
        name: "Arrangement",
        variants: ARRANGEMENT_SCHEMA,
    },
    RoleEnumSchema {
        name: "Alignment",
        variants: ALIGNMENT_SCHEMA,
    },
    RoleEnumSchema {
        name: "ButtonVariant",
        variants: BUTTON_VARIANT_SCHEMA,
    },
    RoleEnumSchema {
        name: "DesignSystem",
        variants: DESIGN_SYSTEM_SCHEMA,
    },
    RoleEnumSchema {
        name: "ColorScheme",
        variants: COLOR_SCHEME_SCHEMA,
    },
];

/// Every place that takes a colour takes a `Paint`, so colour is expressed once.
///
/// Encoded as one `u64`: the high 32 bits are the kind, the low 32 bits are the value.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Paint {
    Role(ColorRole),
    Literal(Color),
}

const PAINT_KIND_ROLE: u64 = 1;
const PAINT_KIND_LITERAL: u64 = 2;

impl Paint {
    pub const fn to_bits(self) -> u64 {
        match self {
            Self::Role(role) => (PAINT_KIND_ROLE << 32) | role as u64,
            Self::Literal(color) => (PAINT_KIND_LITERAL << 32) | color.0 as u64,
        }
    }

    pub fn from_bits(bits: u64) -> Option<Self> {
        let value = bits as u32;
        match bits >> 32 {
            PAINT_KIND_ROLE => Some(Self::Role(
                ColorRole::try_from(u16::try_from(value).ok()?).ok()?,
            )),
            PAINT_KIND_LITERAL => Some(Self::Literal(Color(value))),
            _ => None,
        }
    }
}

/// A design system choice plus how it reacts to the host platform.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Theme {
    pub design_system: DesignSystem,
    pub fallback: DesignSystem,
    pub color_scheme: ColorScheme,
    pub adaptive: bool,
}

impl Theme {
    /// The same design system on every platform.
    pub const fn unified(design_system: DesignSystem) -> Self {
        Self {
            design_system,
            fallback: design_system,
            color_scheme: ColorScheme::FollowSystem,
            adaptive: false,
        }
    }

    /// Follows the host platform. The fallback is required, so no platform is left undecided.
    pub const fn adaptive(fallback: DesignSystem) -> Self {
        Self {
            design_system: fallback,
            fallback,
            color_scheme: ColorScheme::FollowSystem,
            adaptive: true,
        }
    }

    pub const fn with_color_scheme(mut self, color_scheme: ColorScheme) -> Self {
        self.color_scheme = color_scheme;
        self
    }
}

/// Saying nothing follows the host platform, with Material 3 where the platform has no look
/// of its own. The default used to be `unified(Material3)`, on the theory that a default
/// looking the same everywhere is more predictable. In practice that meant macOS showed a
/// Material screen with no configuration, which is the wrong first impression for a toolkit
/// that claims native desktop UI, and it meant nothing on the default path ever exercised
/// platform adaptation. A default that can be broken without anyone noticing is not
/// predictability. An application that wants one design system everywhere says
/// `Theme::unified(..)`, which is a clearer statement of that intent than silence was.
impl Default for Theme {
    fn default() -> Self {
        Self::adaptive(DesignSystem::Material3)
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum Modifier {
    Empty,
    Padding(f32),
    FillMaxWidth,
    FillMaxHeight,
    Width(f32),
    Height(f32),
    Size {
        width: f32,
        height: f32,
    },
    /// A `Paint`, not a raw ARGB. Colour has exactly one wire representation.
    Background(Paint),
    Clickable {
        handler_id: u64,
    },
    PaddingRole(SpaceRole),
    PaddingEach {
        start: f32,
        top: f32,
        end: f32,
        bottom: f32,
    },
    Weight(f32),
    Shape {
        top_start: f32,
        top_end: f32,
        bottom_end: f32,
        bottom_start: f32,
    },
    ShapeRole(ShapeRole),
    Border {
        width: f32,
        paint: Paint,
    },
    /// One dp value. How the shadow is drawn is the design system's rule: tonal lift plus a
    /// shadow, a wide soft shadow, or layered shadow plus a hairline stroke.
    Elevation(f32),
}

const NO_FIELDS: &[FieldSchema] = &[];
const VALUE_FLOAT_FIELD: &[FieldSchema] = &[FieldSchema {
    name: "value",
    ty: FieldType::Float,
    slot: FieldSlot::FirstLow,
}];
const SIZE_FIELDS: &[FieldSchema] = &[
    FieldSchema {
        name: "width",
        ty: FieldType::Float,
        slot: FieldSlot::FirstLow,
    },
    FieldSchema {
        name: "height",
        ty: FieldType::Float,
        slot: FieldSlot::SecondLow,
    },
];
const PAINT_FIELD: &[FieldSchema] = &[FieldSchema {
    name: "paint",
    ty: FieldType::Paint,
    slot: FieldSlot::First,
}];
const HANDLER_ID_FIELD: &[FieldSchema] = &[FieldSchema {
    name: "handlerId",
    ty: FieldType::U64,
    slot: FieldSlot::First,
}];
const SPACE_ROLE_FIELD: &[FieldSchema] = &[FieldSchema {
    name: "role",
    ty: FieldType::Role("SpaceRole"),
    slot: FieldSlot::FirstLow,
}];
const SHAPE_ROLE_FIELD: &[FieldSchema] = &[FieldSchema {
    name: "role",
    ty: FieldType::Role("ShapeRole"),
    slot: FieldSlot::FirstLow,
}];
const PADDING_EACH_FIELDS: &[FieldSchema] = &[
    FieldSchema {
        name: "start",
        ty: FieldType::Float,
        slot: FieldSlot::FirstLow,
    },
    FieldSchema {
        name: "top",
        ty: FieldType::Float,
        slot: FieldSlot::FirstHigh,
    },
    FieldSchema {
        name: "end",
        ty: FieldType::Float,
        slot: FieldSlot::SecondLow,
    },
    FieldSchema {
        name: "bottom",
        ty: FieldType::Float,
        slot: FieldSlot::SecondHigh,
    },
];
const SHAPE_FIELDS: &[FieldSchema] = &[
    FieldSchema {
        name: "topStart",
        ty: FieldType::Float,
        slot: FieldSlot::FirstLow,
    },
    FieldSchema {
        name: "topEnd",
        ty: FieldType::Float,
        slot: FieldSlot::FirstHigh,
    },
    FieldSchema {
        name: "bottomEnd",
        ty: FieldType::Float,
        slot: FieldSlot::SecondLow,
    },
    FieldSchema {
        name: "bottomStart",
        ty: FieldType::Float,
        slot: FieldSlot::SecondHigh,
    },
];
const BORDER_FIELDS: &[FieldSchema] = &[
    FieldSchema {
        name: "width",
        ty: FieldType::Float,
        slot: FieldSlot::FirstLow,
    },
    FieldSchema {
        name: "paint",
        ty: FieldType::Paint,
        slot: FieldSlot::Second,
    },
];

pub const MODIFIER_SCHEMA: &[VariantSchema] = &[
    VariantSchema {
        name: "Empty",
        tag: 0,
        fields: NO_FIELDS,
    },
    VariantSchema {
        name: "Padding",
        tag: 1,
        fields: VALUE_FLOAT_FIELD,
    },
    VariantSchema {
        name: "FillMaxWidth",
        tag: 2,
        fields: NO_FIELDS,
    },
    VariantSchema {
        name: "FillMaxHeight",
        tag: 3,
        fields: NO_FIELDS,
    },
    VariantSchema {
        name: "Width",
        tag: 4,
        fields: VALUE_FLOAT_FIELD,
    },
    VariantSchema {
        name: "Height",
        tag: 5,
        fields: VALUE_FLOAT_FIELD,
    },
    VariantSchema {
        name: "Size",
        tag: 6,
        fields: SIZE_FIELDS,
    },
    VariantSchema {
        name: "Background",
        tag: 7,
        fields: PAINT_FIELD,
    },
    VariantSchema {
        name: "Clickable",
        tag: 8,
        fields: HANDLER_ID_FIELD,
    },
    VariantSchema {
        name: "PaddingRole",
        tag: 9,
        fields: SPACE_ROLE_FIELD,
    },
    VariantSchema {
        name: "PaddingEach",
        tag: 10,
        fields: PADDING_EACH_FIELDS,
    },
    VariantSchema {
        name: "Weight",
        tag: 11,
        fields: VALUE_FLOAT_FIELD,
    },
    VariantSchema {
        name: "Shape",
        tag: 12,
        fields: SHAPE_FIELDS,
    },
    VariantSchema {
        name: "ShapeRole",
        tag: 13,
        fields: SHAPE_ROLE_FIELD,
    },
    VariantSchema {
        name: "Border",
        tag: 14,
        fields: BORDER_FIELDS,
    },
    VariantSchema {
        name: "Elevation",
        tag: 15,
        fields: VALUE_FLOAT_FIELD,
    },
];

#[derive(Clone, Debug, PartialEq)]
pub struct ColumnProps {
    pub enabled: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct RowProps {
    pub enabled: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct BoxProps {
    pub enabled: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct TextProps<'a> {
    pub text: &'a str,
}

#[derive(Clone, Debug, PartialEq)]
pub struct TextFieldProps<'a> {
    pub placeholder: &'a str,
    pub enabled: bool,
    pub multiline: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ButtonProps<'a> {
    pub text: &'a str,
    pub enabled: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SpacerProps {
    pub width: f32,
    pub height: f32,
}

crate::extensions::define_property_schema_with_extensions!(define_wire_enum; PROPERTY_SCHEMA, PropertyKind {
    Text = 1,
    Placeholder = 2,
    Enabled = 3,
    Multiline = 4,
    OnClick = 5,
    OnValueChange = 6,
    OnSubmit = 7,
    OnFocusLost = 8,
    OnKeyDown = 9,
    ItemCount = 10,
    ItemKey = 11,
    OnRangeRequested = 12,
    TypeRole = 13,
    FontSize = 14,
    FontWeight = 15,
    LineHeight = 16,
    LetterSpacing = 17,
    Color = 18,
    TextAlign = 19,
    MaxLines = 20,
    Overflow = 21,
    Arrangement = 22,
    Spacing = 23,
    SpaceRole = 24,
    Alignment = 25,
    Variant = 26,
    // The selection controls and the indicators. One bool says "is this on" for all three
    // toggles: a radio button that is selected and a switch that is on are the same fact on
    // the wire, and the schema carries each concept once.
    Checked = 32,
    // The slider's position, and the progress indicator's completion.
    Value = 33,
    MinValue = 34,
    MaxValue = 35,
    // Discrete stops between the two ends. Zero means the slider is continuous.
    Steps = 36,
    Determinate = 37,
    Circular = 38,
    Vertical = 39,
    // Whether an overlay is showing. The Renderer owns the state; this seeds it and
    // carries changes that came from outside the Renderer.
    Open = 40,
    OnDismiss = 41,
    SelectedIndex = 42,
});

#[derive(Clone, Debug, PartialEq)]
pub enum EventPayload<'a> {
    Clicked,
    TextChanged(&'a str),
    TextSubmitted(&'a str),
    FocusLost,
    ProtocolError {
        code: u32,
        message: &'a str,
    },
    KeyDown {
        key: Key,
        shift_key: bool,
        ctrl_key: bool,
        alt_key: bool,
        meta_key: bool,
    },
    /// The Renderer asks the Host to materialise the visible item range.
    RangeRequested {
        start: u32,
        count: u32,
    },
    /// A continuous value the Host cannot derive from what it already sent. A slider drag
    /// lands here; the toggles do not, because flipping a boolean needs no payload.
    ValueChanged {
        value: f32,
    },
}

pub const EVENT_SCHEMA: &[EventSchema] = &[
    EventSchema {
        name: "Clicked",
        tag: 1,
        payload: EventPayloadType::None,
    },
    EventSchema {
        name: "TextChanged",
        tag: 2,
        payload: EventPayloadType::Text,
    },
    EventSchema {
        name: "TextSubmitted",
        tag: 3,
        payload: EventPayloadType::Text,
    },
    EventSchema {
        name: "FocusLost",
        tag: 4,
        payload: EventPayloadType::None,
    },
    EventSchema {
        name: "ProtocolError",
        tag: 5,
        payload: EventPayloadType::ProtocolError,
    },
    EventSchema {
        name: "KeyDown",
        tag: 6,
        payload: EventPayloadType::KeyDown,
    },
    EventSchema {
        name: "RangeRequested",
        tag: 7,
        payload: EventPayloadType::Range,
    },
    // Tags 8 to 15 are held for the pointer gesture events, so this one starts at 16.
    EventSchema {
        name: "ValueChanged",
        tag: 16,
        payload: EventPayloadType::Value,
    },
];
