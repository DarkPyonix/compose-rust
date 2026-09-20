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
    /// FR-13.1: a `Paint`, either a `ColorRole` or a literal ARGB `Color`.
    Paint,
    /// FR-13: a role enum, named so codegen can emit the matching Kotlin type.
    Role(&'static str),
}

/// Which half of which `(first, second)` word a modifier field occupies (FR-13.8).
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
    /// One signed integer. The pickers report their value this way, in their own unit.
    Integer,
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
    "widgets=Column,Row,Box,Text,TextField,Button,Spacer,LazyColumn,ScrollColumn,Image,Icon,DatePicker,TimePicker,Dropdown;",
    "properties=text,placeholder,enabled,multiline,on_click,on_value_change,on_submit,on_focus_lost,on_key_down,item_count,item_key,on_range_requested,type_role,font_size,font_weight,line_height,letter_spacing,color,text_align,max_lines,overflow,arrangement,spacing,space_role,alignment,variant,asset_id,value,min_value,max_value;",
    "modifiers=Empty,Padding,FillMaxWidth,FillMaxHeight,Width,Height,Size,Background,Clickable,PaddingRole,PaddingEach,Weight,Shape,ShapeRole,Border,Elevation;",
    "keys=Enter;",
    "events=Clicked,TextChanged,TextSubmitted,FocusLost,ProtocolError,KeyDown,RangeRequested,ValueChanged;",
    "commands=Create,SetProp,SetModifier,Insert,Move,Remove,SetText,AppendText,SetTheme,RegisterAsset,ReleaseAsset"
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
                EventPayloadType::Integer => 5,
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
    Image = 10,
    Icon = 11,
    DatePicker = 27,
    TimePicker = 28,
    Dropdown = 29,
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

/// A role enum that codegen mirrors into Kotlin (FR-13.8).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RoleEnumSchema {
    pub name: &'static str,
    pub variants: &'static [EnumVariantSchema],
}

// FR-13.1: an opaque ARGB colour. Gradients and image brushes are out of scope (13.7).
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

// FR-13.1: semantic colour slots. The design system resolves them (FR-14.4).
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

// FR-13.2: the nine-rung type ladder every supported design system maps onto.
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

// FR-13.3: corner roles. The radius is the design system's decision.
define_wire_enum!(SHAPE_ROLE_SCHEMA, ShapeRole {
    None = 1,
    ExtraSmall = 2,
    Small = 3,
    Medium = 4,
    Large = 5,
    Full = 6,
});

// FR-13.4: density roles, because dp density differs per design system.
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

// FR-14.2: the neutral component variant names the design system styles.
define_wire_enum!(BUTTON_VARIANT_SCHEMA, ButtonVariant {
    Filled = 1,
    Tonal = 2,
    Outlined = 3,
    Text = 4,
});

// FR-14.5: the design systems of phase one. Phase two appends variants here only.
define_wire_enum!(DESIGN_SYSTEM_SCHEMA, DesignSystem {
    Material3 = 1,
    Cupertino = 2,
    Fluent = 3,
});

// FR-14.3: light and dark selection. `FollowSystem` leaves the choice to the Renderer.
define_wire_enum!(COLOR_SCHEME_SCHEMA, ColorScheme {
    Light = 1,
    Dark = 2,
    FollowSystem = 3,
});

// The encodings the Renderer is asked to decode. The Renderer answers a kind it cannot
// decode with a protocol error rather than drawing nothing, so a Host that ships an
// unsupported format learns about it instead of seeing a blank rectangle.
define_wire_enum!(ASSET_KIND_SCHEMA, AssetKind {
    Png = 1,
    Jpeg = 2,
    Svg = 3,
    VectorIcon = 4,
});

// The closed set of icon meanings an application may ask for.
//
// This is a set of roles and not a set of icon names on purpose. A name ("chevron.left",
// "arrow_back") would be checked for existence only when the Renderer tried to look it up,
// and it would name one platform's artwork, so the same request would be wrong everywhere
// else. A role is checked by the compiler, and each design system answers it with its own
// artwork: the Cupertino set follows SF Symbols, the Material 3 set follows Material
// Symbols, and the Fluent set follows the Fluent icon geometry. That is what makes one
// `Icon` in the application look native in all three.
//
// Adding a role means drawing it in every set, which is the cost that keeps the promise.
define_wire_enum!(ICON_ROLE_SCHEMA, IconRole {
    Back = 1,
    Forward = 2,
    Close = 3,
    Search = 4,
    Add = 5,
    Delete = 6,
    Check = 7,
    More = 8,
    ChevronDown = 9,
    Settings = 10,
    Calendar = 11,
    Clock = 12,
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
    RoleEnumSchema {
        name: "AssetKind",
        variants: ASSET_KIND_SCHEMA,
    },
    RoleEnumSchema {
        name: "IconRole",
        variants: ICON_ROLE_SCHEMA,
    },
];

/// FR-13.1: every place that takes a colour takes a `Paint`, so colour is expressed once.
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

/// FR-14.3: a design system choice plus how it reacts to the host platform.
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

/// FR-14.3: saying nothing follows the host platform, with Material 3 where it has no look
/// of its own. An application that wants one design system everywhere says
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
    /// FR-13.1: a `Paint`, not a raw ARGB. Colour has exactly one wire representation.
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
    /// FR-13.5: one dp value. How the shadow is drawn is the design system's rule.
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
    // Tags 27 to 39 are left free for the widgets between Checkbox and Canvas, whose
    // properties are assigned with those widgets. Asset delivery and the pickers start at
    // 40 so the two sets can be added in either order without either renumbering.
    AssetId = 40,
    Value = 41,
    MinValue = 42,
    MaxValue = 43,
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
    /// FR-8: the Renderer asks the Host to materialise the visible item range.
    RangeRequested {
        start: u32,
        count: u32,
    },
    /// A picker reports the value the user chose, in the picker's own unit: whole days
    /// since 1970-01-01 for a date, whole minutes since midnight for a time, and the index
    /// of the chosen child for a dropdown. No formatted text crosses the boundary, because
    /// the calendar, the locale and the clock convention belong to the Renderer.
    ValueChanged {
        value: i64,
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
    EventSchema {
        name: "ValueChanged",
        tag: 8,
        payload: EventPayloadType::Integer,
    },
];
