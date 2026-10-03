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
    /// One `f64`. Every value-carrying widget shares it, so the same concept has one
    /// name on the wire whether the value counts days or slides between two ends.
    Double,
    WindowSize,
    /// One `u16`: the tag of the design system the Renderer resolved the theme to.
    DesignSystem,
    /// A `u32` saying what was pressed (0 for the body, 1 or 2 for an action button), then
    /// the notification's key as a string reference.
    NotificationActivation,
    /// One `u32`: the notification permission the Renderer last saw.
    NotificationPermission,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct EventSchema {
    pub name: &'static str,
    pub tag: u16,
    pub payload: EventPayloadType,
}

/// Canonical schema text. Variant order is wire-significant and must only be appended to.
pub const SCHEMA_DESCRIPTOR: &str = concat!(
    "compose-rust/v1;",
    "widgets=Column,Row,Box,Text,TextField,Button,Spacer,LazyColumn,ScrollColumn,Image,Icon,Checkbox,RadioButton,Switch,Slider,ProgressIndicator,Divider,Card,Surface,Dialog,Menu,Tabs,TopAppBar,LazyRow,Tooltip,Canvas,DatePicker,TimePicker,Dropdown,Navigation,NavigationItem,Sheet,Scaffold,ScaffoldSlot,LazyGrid,FileDropTarget,ScrollRow,Chip,FloatingAction,Badge,SelectionContainer,SplitPane;",
    "properties=text,placeholder,enabled,multiline,on_click,on_value_change,on_submit,on_focus_lost,on_key_down,item_count,item_key,on_range_requested,type_role,font_size,font_weight,line_height,letter_spacing,color,text_align,max_lines,overflow,arrangement,spacing,space_role,alignment,variant,asset,checked,steps,determinate,circular,vertical,open,on_dismiss,selected_index,commands,value,min,max,icon,slot,columns,min_column_width,spans,on_files_entered,on_files_dropped,section,count,collapsible,font,word_break,overflow_wrap,tab_size,absolute_size,soft_wrap,span_fonts;",
    "modifiers=Empty,Padding,FillMaxWidth,FillMaxHeight,Width,Height,Size,Background,Clickable,PaddingRole,PaddingEach,Weight,Shape,ShapeRole,Border,Elevation,ObserveSize,Motion,Material;",
    "keys=Enter;",
    "events=Clicked,TextChanged,TextSubmitted,FocusLost,ProtocolError,KeyDown,RangeRequested,ValueChanged,WindowSizeChanged,DesignSystemResolved,FilesEntered,FilesDropped,NotificationActivated,NotificationPermissionChanged;",
    "windowsizeclasses=Compact,Medium,Expanded;",
    "windowheightclasses=Compact,Medium,Expanded;",
    "commands=Create,SetProp,SetModifier,Insert,Move,Remove,SetText,AppendText,SetTheme,SetWindow,RegisterAsset,ReleaseAsset,ShowMessage,PostNotification,WithdrawNotification,RequestNotificationPermission"
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
    hash = hash_bytes(hash, crate::measure::SCHEMA_DESCRIPTOR.as_bytes());
    hash = hash_enum_schema(hash, WIDGET_SCHEMA);
    hash = hash_enum_schema(hash, PROPERTY_SCHEMA);
    hash = hash_enum_schema(hash, KEY_SCHEMA);
    hash = hash_enum_schema(hash, WINDOW_SIZE_CLASS_SCHEMA);
    hash = hash_enum_schema(hash, WINDOW_HEIGHT_CLASS_SCHEMA);
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
    while index < crate::drawing::DRAW_COMMAND_SCHEMA.len() {
        let command = crate::drawing::DRAW_COMMAND_SCHEMA[index];
        hash = hash_bytes(hash, command.name.as_bytes());
        hash = hash_bytes(hash, &command.tag.to_le_bytes());
        let mut field_index = 0;
        while field_index < command.fields.len() {
            let field = command.fields[field_index];
            hash = hash_bytes(hash, field.name.as_bytes());
            hash = hash_bytes(hash, &[field.word]);
            hash = hash_bytes(
                hash,
                &[match field.ty {
                    crate::drawing::DrawFieldType::Float => 1,
                    crate::drawing::DrawFieldType::U32 => 2,
                    crate::drawing::DrawFieldType::Role(_) => 3,
                }],
            );
            if let crate::drawing::DrawFieldType::Role(role) = field.ty {
                hash = hash_bytes(hash, role.as_bytes());
            }
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
                EventPayloadType::Double => 5,
                EventPayloadType::WindowSize => 6,
                EventPayloadType::DesignSystem => 7,
                EventPayloadType::NotificationActivation => 8,
                EventPayloadType::NotificationPermission => 9,
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
        define_wire_enum!($schema, $name { $($variant = $tag),+ } unlisted {});
    };
    // `unlisted` variants exist in Rust and travel as their tag, but are not in the schema
    // table, so codegen mirrors no Kotlin enum entry for them and `TryFrom` does not
    // produce them. The one there is, `TypeRole::None`, is tag 0, which every role
    // decoder already reads as "no role", and an entry for it in the Kotlin enum would
    // shift every ordinal the Renderer indexes its token tables by.
    (
        $schema:ident,
        $name:ident { $($variant:ident = $tag:literal),+ $(,)? }
        unlisted { $($extra:ident = $extra_tag:literal),* $(,)? }
    ) => {
        #[derive(Clone, Copy, Debug, Eq, PartialEq)]
        #[repr(u16)]
        pub enum $name {
            $($extra = $extra_tag,)*
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
            // The unit error is the wire's own convention: a name either is a variant or is
            // not, and the caller reports which name it was.
            #[allow(dead_code, clippy::result_unit_err)]
            #[doc(hidden)]
            pub fn from_name(value: &str) -> Result<Self, ()> {
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
    // The selection controls and the indicators. Each one emits a state and a role and
    // nothing about how it is drawn: the tick, the track, the thumb and the sweep of an
    // indeterminate bar are the design system's.
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
    // Drawing commands instead of child nodes. The command list is its only property.
    Canvas = 26,
    DatePicker = 27,
    TimePicker = 28,
    Dropdown = 29,
    // One declaration, three presentations. The Renderer picks a bottom bar, a rail or a
    // permanent drawer from the width it has already measured, so the same tree looks
    // native on a phone and on a desktop without the Host branching on the size class.
    Navigation = 30,
    // One destination. Its label and its icon are properties rather than children,
    // because a child tree would fix the arrangement the presentations need to differ in.
    NavigationItem = 31,
    // A temporary surface that slides in from an edge of the screen. Which edge is the
    // Renderer's decision, for the same reason the navigation presentation is.
    Sheet = 32,
    // The screen's frame. The application fills slots and the Renderer decides what each
    // one becomes on this platform and at this width, which is the same decision
    // `Navigation` already makes for itself, widened to the whole frame.
    Scaffold = 33,
    // One filled slot. A wrapper rather than a property on the slot's own root, because
    // an application may put any tree in a slot and the Renderer has to know which slot
    // it is looking at without reading into it.
    ScaffoldSlot = 34,
    // A lazy grid. The windowing protocol is the list's, unchanged: the Renderer asks for a
    // range of items and the Host materialises exactly that range. What the grid adds is
    // that the Renderer rounds the range it asks for to whole rows, because a row is what
    // it lays out, and how many items a row holds is its own decision when the columns
    // were given as a minimum width rather than a count.
    LazyGrid = 35,
    // A place files may be dropped. Being this widget is the willingness: a node that is
    // not one is never offered as a target, so the platform shows no drop cursor over it
    // and nothing is reported.
    FileDropTarget = 36,
    // The whole content with a horizontal scroll, which is a ScrollColumn turned on its
    // side. Every child is materialised and the scroll position is the Renderer's, so
    // nothing about scrolling crosses the boundary; a long horizontal list is a LazyRow.
    ScrollRow = 37,
    // A small token that is chosen or filters. It carries its label and whether it is
    // chosen, and the chosen state is the Host's: it changes only because the Host's own
    // click handler changed it. The shape is the design system's.
    Chip = 38,
    // The one action a screen is about. It carries an icon, a label and a click. Where it
    // goes and what it is drawn as is the design system's: a disc floating over the page
    // is one system's answer, a plus at the trailing end of the bar is another's, and an
    // accent button at the head of the command bar is a third.
    FloatingAction = 39,
    // A small mark on something else that says how many, that there is something new, or
    // one short word. With one child it is attached to that child; with none it stands on
    // its own, at the end of a row. Where it sits on the child, its shape, and how a large
    // count is shortened are the design system's: nothing here can ask for a corner or a
    // ceiling.
    Badge = 40,
    // A region whose text can be dragged over and copied, as one selection across every
    // Text inside it. Text outside one cannot be selected. The selection and the copy are
    // the Renderer's entirely, so nothing about either crosses the boundary.
    SelectionContainer = 41,
    // Two panes side by side, a side pane and a body, with a divider the user drags to
    // change the side pane's width. Exactly two children, the side pane first.
    //
    // The drag is the Renderer's, like a scroll position: nothing crosses the boundary
    // while it moves, and the width it ends on is reported once through the value change
    // event. How the divider looks and how far its grip reaches, the side pane's default
    // width, the body's minimum, and whether a narrow place shows the two panes side by
    // side, with the side pane laid over the body, or one at a time, are the design
    // system's answers.
    SplitPane = 42,
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

define_wire_enum!(WINDOW_SIZE_CLASS_SCHEMA, WindowSizeClass {
    Compact = 0,
    Medium = 1,
    Expanded = 2,
});

impl WindowSizeClass {
    /// The width in dp at which `Medium` begins, and the width at which `Expanded` does.
    ///
    /// These are the Material 3 window size class boundaries. Using different numbers
    /// would make the Compose components adapt at one width and the Rust layout branch at
    /// another, and that mismatch is visible on screen.
    pub const MEDIUM_MIN_WIDTH_DP: f32 = 600.0;
    pub const EXPANDED_MIN_WIDTH_DP: f32 = 840.0;

    /// The class a window of this width belongs to. Height does not take part.
    pub fn from_width_dp(width_dp: f32) -> Self {
        if width_dp >= Self::EXPANDED_MIN_WIDTH_DP {
            Self::Expanded
        } else if width_dp >= Self::MEDIUM_MIN_WIDTH_DP {
            Self::Medium
        } else {
            Self::Compact
        }
    }
}

define_wire_enum!(WINDOW_HEIGHT_CLASS_SCHEMA, WindowHeightClass {
    Compact = 0,
    Medium = 1,
    Expanded = 2,
});

impl WindowHeightClass {
    /// The height in dp at which `Medium` begins, and the height at which `Expanded` does.
    ///
    /// The same published table the width boundaries came from, so a layout that folds
    /// when it runs out of vertical room folds at the height Compose's own components
    /// treat as short.
    pub const MEDIUM_MIN_HEIGHT_DP: f32 = 480.0;
    pub const EXPANDED_MIN_HEIGHT_DP: f32 = 900.0;

    /// The class a window of this height belongs to. Width does not take part.
    pub fn from_height_dp(height_dp: f32) -> Self {
        if height_dp >= Self::EXPANDED_MIN_HEIGHT_DP {
            Self::Expanded
        } else if height_dp >= Self::MEDIUM_MIN_HEIGHT_DP {
            Self::Medium
        } else {
            Self::Compact
        }
    }
}

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
    // The layer a panel is made of: a thing raised off the page, holding the page's own
    // reading ink. It exists because `Surface` cannot do that job everywhere. Material 3
    // gives `Surface` and `Background` one value on purpose and expresses depth through
    // tonal containers instead, so a panel filled with `Surface` on a page of `Background`
    // is drawn, in the right colour, and is invisible. `SurfaceVariant` is not the answer
    // either: its ink pair is the muted secondary ink, so a panel holding primary content
    // would have to set that content half faded. This is the one role every design system
    // promises to make visible against the page, and `OnSurface` is what reads on it.
    SurfaceContainer = 15,
    // The third accent, and a quiet fill for each of the three.
    //
    // Two accents and a page say a button, a bar and a heading. They cannot say a grid of
    // subject tiles where the subject is the colour, a mood picker, or a panel of costs
    // beside a panel of totals. Those screens need fills that read as relatives of one
    // another, are not reading surfaces, and are quiet enough that body text sits on them.
    //
    // A container is a colour in the table rather than its accent at a lower opacity.
    // Opacity only means something once you know what is behind it, and a role has to
    // answer before anyone knows that. So each container is its own value and carries its
    // own ink, held to the body-text bound rather than the label bound, because the reason
    // it exists is that paragraphs land on it.
    //
    // The three containers are not required to be told apart from each other. Material 3's
    // baseline primary and secondary containers are neighbouring tones of one palette, and
    // that is its published scheme, not a mistake. A caller who needs three fills that
    // separate at a glance reaches for the tertiary pair.
    Tertiary = 16,
    OnTertiary = 17,
    PrimaryContainer = 18,
    OnPrimaryContainer = 19,
    SecondaryContainer = 20,
    OnSecondaryContainer = 21,
    TertiaryContainer = 22,
    OnTertiaryContainer = 23,
    // What a highlighter paints code with. A conversation shows code blocks and diffs, and
    // with only literals to paint them the same grey comment would be stamped into seven
    // systems and two schemes, which is how a light comment grey disappears on a dark page.
    // So a token kind is a role like any other, and each system answers it from the code
    // editor of its own platform.
    //
    // The split follows what tree-sitter's highlight names, VS Code's semantic token kinds
    // and TextMate scopes all agree on. Each of those divides more finely, and the finer
    // names fold into one of these (`keyword.return` is a keyword). A token that fits none
    // is left in the body ink rather than given a colour of its own.
    //
    // The fifteen syntax inks hold the body-text bound on the code block's panel, comments
    // included: a comment can be quieter in hue and lightness, but one nobody can read is
    // a comment that is not there.
    SyntaxKeyword = 24,
    SyntaxString = 25,
    SyntaxComment = 26,
    SyntaxNumber = 27,
    SyntaxConstant = 28,
    SyntaxType = 29,
    SyntaxFunction = 30,
    SyntaxVariable = 31,
    SyntaxProperty = 32,
    SyntaxOperator = 33,
    SyntaxPunctuation = 34,
    SyntaxTag = 35,
    SyntaxAttribute = 36,
    SyntaxEscape = 37,
    SyntaxMacro = 38,
    // A diff. The ink marks an added or removed line and its text; the container is the
    // whole line's background, drawn as the line node's own background; the emphasis is
    // the background behind the words that actually changed, drawn by the span. Added and
    // removed are never told apart by colour alone: the `+` and `-` at the head of a line
    // stay, because the two backgrounds can look the same to a red-green colour blind
    // reader.
    DiffAdded = 39,
    DiffRemoved = 40,
    DiffModified = 41,
    DiffAddedContainer = 42,
    DiffRemovedContainer = 43,
    DiffAddedEmphasis = 44,
    DiffRemovedEmphasis = 45,
});

/// How many colour roles there are, which is how many rows every token table has and how
/// many slots a palette carries per scheme.
pub const COLOR_ROLE_COUNT: usize = 45;

// The nine-rung type ladder every supported design system maps onto.
//
// `None` is text with no rung at all, which is what a page laid out by CSS draws: its size,
// weight, line height, letter spacing and font come from the text's own values and nothing
// from the design system. It is the only text that may name a font of its own. It is not
// in the schema table, because it is not a rung: see `define_wire_enum!`.
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
} unlisted {
    None = 0,
});

// Where a line of text may break, as CSS `word-break` says it. `Normal` breaks where the
// language does: between words, and between syllables in Korean. `KeepAll` breaks only at
// spaces, which is how Korean prose is usually set. `BreakAll` breaks between any two
// characters.
define_wire_enum!(WORD_BREAK_SCHEMA, WordBreak {
    Normal = 1,
    KeepAll = 2,
    BreakAll = 3,
});

// Whether a word too long for its line may be broken inside, as CSS `overflow-wrap` says
// it. `Anywhere` also lets that break count when the narrowest width the text can take is
// worked out; `BreakWord` does not.
define_wire_enum!(OVERFLOW_WRAP_SCHEMA, OverflowWrap {
    Normal = 1,
    Anywhere = 2,
    BreakWord = 3,
});

// The families CSS names without naming a font. Which installed font each one is, is the
// platform's answer.
define_wire_enum!(GENERIC_FAMILY_SCHEMA, GenericFamily {
    SystemUi = 1,
    SansSerif = 2,
    Serif = 3,
    Monospace = 4,
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

// How long a change takes and along which curve. A role rather than a duration, because
// Material's emphasized curve and Cupertino's spring are different answers to the same
// question, and a milliseconds figure from the application would settle it in the wrong
// place. A system asked to reduce motion answers every one of these instantly.
define_wire_enum!(MOTION_ROLE_SCHEMA, MotionRole {
    Instant = 1,
    Quick = 2,
    Standard = 3,
    Slow = 4,
    Emphasized = 5,
});

// What a surface is made of. Four roles rather than a blur radius, because Cupertino and
// Liquid Glass answer with blur, Material 3 with elevation and a tone laid over the
// surface, and the GNOME and KDE systems with an opaque fill. A radius from the
// application would be a blur instruction to systems that do not blur.
define_wire_enum!(MATERIAL_ROLE_SCHEMA, MaterialRole {
    Thin = 1,
    Regular = 2,
    Thick = 3,
    Chrome = 4,
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
    Operator = 5,
});

// What the bytes behind an asset id are. A kind the Renderer cannot read is a reported
// protocol error, not a guess.
define_wire_enum!(ASSET_KIND_SCHEMA, AssetKind {
    Png = 1,
    Jpeg = 2,
    Svg = 3,
    VectorIcon = 4,
    // A font file, registered so that a type role can resolve to it. The bytes are the
    // font, which is why this is an asset and not a name: a name would put the check that
    // the font exists at run time on the reader's machine, and a missing one there is a
    // screen in the wrong typeface with nobody to tell.
    Font = 5,
    // A gradient or an image fill, registered so that any Paint may name it. The bytes
    // are the brush's own fixed layout rather than a file: nothing on any platform stores
    // a list of stops as a document, and a format would be a parser to write and keep.
    Brush = 6,
});

// How a brush carries on past the area it was given.
define_wire_enum!(TILE_MODE_SCHEMA, TileMode {
    // Stops at the edge and drags the last colour outwards, which is what a gradient
    // filling a surface wants.
    Clamp = 1,
    Repeat = 2,
    // Repeats, flipping every other copy, so the seams do not show.
    Mirror = 3,
});

// The closed set of icon meanings. An icon is addressed by what it is for, never by a
// system icon name: a name would defer the check that the icon exists to run time, and it
// would pin one platform's artwork into the protocol. The role is what lets the same
// declaration come out as SF Symbols under Cupertino and Material Symbols under Material 3.
define_wire_enum!(ICON_ROLE_SCHEMA, IconRole {
    Back = 1,
    Forward = 2,
    Close = 3,
    Search = 4,
    Add = 5,
    Check = 6,
    Settings = 7,
    More = 8,
    // The meanings a set of destinations needs before any other: where the application
    // starts, the list it is mostly about, and what has arrived.
    Home = 9,
    List = 10,
    Inbox = 11,
    // What a window's own bar needs: the button that opens everything this screen does
    // not have room for, and the one that goes back over what has already happened. Both
    // are in the reference bar of more than one sample, and neither is a destination, so
    // neither could be said with the three above.
    Menu = 12,
    History = 13,
    // What putting the samples beside the applications they are drawn from turned up: eight
    // meanings the set could not say. Each is a meaning rather than a picture, so a system
    // that draws sending as a paper aeroplane is still answering `Send`.
    //
    // None of them is an existing role used loosely. A composer's send was drawn with
    // `Forward` for a while, and an arrow pointing sideways says "on to the next one"
    // rather than "send this".
    Send = 14,
    Mic = 15,
    Image = 16,
    Video = 17,
    Library = 18,
    Sidebar = 19,
    Compose = 20,
    Collapse = 21,
});

// How long a transient message stays on screen. Closed, and deliberately short: a message
// that has to be acknowledged is a `Dialog`, so there is no indefinite duration. What the
// two names mean in milliseconds is the design system's decision.
define_wire_enum!(MESSAGE_DURATION_SCHEMA, MessageDuration {
    Short = 1,
    Long = 2,
});

// How much a notification matters. Closed for the same reason a message's duration is: what
// it is worth in sound, in an Android channel's importance or in getting through Do Not
// Disturb is the platform's answer, so the Host has no way to send a sound file or a
// priority number.
define_wire_enum!(NOTIFICATION_IMPORTANCE_SCHEMA, NotificationImportance {
    Normal = 1,
    Urgent = 2,
});

// Whether a notification is shown while the application's window is the active one.
// `WhenInactive` is dropped by the Renderer when the window has focus: whether it has is
// the Renderer's state, and a Host that wanted to judge it would need every focus change
// sent across.
define_wire_enum!(NOTIFICATION_PRESENTATION_SCHEMA, NotificationPresentation {
    Always = 1,
    WhenInactive = 2,
});

// Whether this run may show notifications. `Unsupported` is a platform or a run that has
// no way to show one (no bundle identifier, no AppUserModelID, no notification daemon),
// which is an answer and not an error.
define_wire_enum!(NOTIFICATION_PERMISSION_SCHEMA, NotificationPermission {
    NotDetermined = 1,
    Granted = 2,
    Denied = 3,
    Unsupported = 4,
});

// The design systems of phase one. Later ones append variants here and nowhere else: a new
// design system is one variant plus one token table and rule implementation in the Renderer.
//
// `LiquidGlass` is the language macOS 26 and iOS 26 draw, and it sits beside `Cupertino`
// rather than replacing it: one draws grouped inset lists on a grey page, the other draws
// translucent floating capsules, and an application that wants one does not want the
// other. Tags are append only, so it lands after the Linux three even though it belongs
// next to `Cupertino` in any list a reader would write.
define_wire_enum!(DESIGN_SYSTEM_SCHEMA, DesignSystem {
    Material3 = 1,
    Cupertino = 2,
    Fluent = 3,
    Gnome = 4,
    Breeze = 5,
    Deepin = 6,
    LiquidGlass = 7,
});

// Light and dark selection. `FollowSystem` leaves the choice to the Renderer, which learns
// of a system change first and applies it without involving the Host.
define_wire_enum!(COLOR_SCHEME_SCHEMA, ColorScheme {
    Light = 1,
    Dark = 2,
    FollowSystem = 3,
});

// Which part of the screen's frame a `Scaffold` slot fills.
//
// What each one becomes is the Renderer's: a top bar may be the window's caption, an
// ordinary bar under the system title bar, or a large title that shrinks as the page
// scrolls; a bottom bar may be a bar, a rail or a permanent drawer; a floating action
// may float, sit in the toolbar, or fold into a menu. The application says which slot
// it filled and nothing about the answer.
define_wire_enum!(SLOT_ROLE_SCHEMA, SlotRole {
    TopBar = 1,
    BottomBar = 2,
    FloatingAction = 3,
    Content = 4,
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
        name: "MotionRole",
        variants: MOTION_ROLE_SCHEMA,
    },
    RoleEnumSchema {
        name: "TileMode",
        variants: TILE_MODE_SCHEMA,
    },
    RoleEnumSchema {
        name: "MaterialRole",
        variants: MATERIAL_ROLE_SCHEMA,
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
    RoleEnumSchema {
        name: "MessageDuration",
        variants: MESSAGE_DURATION_SCHEMA,
    },
    RoleEnumSchema {
        name: "Chrome",
        variants: CHROME_SCHEMA,
    },
    RoleEnumSchema {
        name: "TitleBar",
        variants: TITLE_BAR_SCHEMA,
    },
    RoleEnumSchema {
        name: "SlotRole",
        variants: SLOT_ROLE_SCHEMA,
    },
    RoleEnumSchema {
        name: "NotificationImportance",
        variants: NOTIFICATION_IMPORTANCE_SCHEMA,
    },
    RoleEnumSchema {
        name: "NotificationPresentation",
        variants: NOTIFICATION_PRESENTATION_SCHEMA,
    },
    RoleEnumSchema {
        name: "NotificationPermission",
        variants: NOTIFICATION_PERMISSION_SCHEMA,
    },
    RoleEnumSchema {
        name: "WordBreak",
        variants: WORD_BREAK_SCHEMA,
    },
    RoleEnumSchema {
        name: "OverflowWrap",
        variants: OVERFLOW_WRAP_SCHEMA,
    },
    RoleEnumSchema {
        name: "GenericFamily",
        variants: GENERIC_FAMILY_SCHEMA,
    },
];

/// Every place that takes a colour takes a `Paint`, so colour is expressed once.
///
/// Encoded as one `u64`: the high 32 bits are the kind, the low 32 bits are the value.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Paint {
    Role(ColorRole),
    Literal(Color),
    /// A registered brush: a gradient, or a picture laid out as a fill.
    ///
    /// An id rather than the thing itself, because a list of stops does not fit the two
    /// words a modifier has and because a brush outlives the frame that draws it, which
    /// is what the asset path already exists for.
    Asset(u32),
}

const PAINT_KIND_ROLE: u64 = 1;
const PAINT_KIND_LITERAL: u64 = 2;
const PAINT_KIND_ASSET: u64 = 3;

impl Paint {
    pub const fn to_bits(self) -> u64 {
        match self {
            Self::Role(role) => (PAINT_KIND_ROLE << 32) | role as u64,
            Self::Literal(color) => (PAINT_KIND_LITERAL << 32) | color.0 as u64,
            Self::Asset(id) => (PAINT_KIND_ASSET << 32) | id as u64,
        }
    }

    pub fn from_bits(bits: u64) -> Option<Self> {
        let value = bits as u32;
        match bits >> 32 {
            PAINT_KIND_ROLE => Some(Self::Role(
                ColorRole::try_from(u16::try_from(value).ok()?).ok()?,
            )),
            PAINT_KIND_LITERAL => Some(Self::Literal(Color(value))),
            PAINT_KIND_ASSET => Some(Self::Asset(value)),
            _ => None,
        }
    }
}

// How the window wears its title bar.
//
// `Modern` runs content into the title bar area and is the default, because every desktop
// platform now expects it and a window with a separate system bar looks a decade old.
//
// `System` is the platform's ordinary bar, and it is kept deliberately. Some tool-shaped
// applications want one, and more importantly it is where an application goes when
// `Modern` turns out to be wrong on some machine: a default with no way out of it is a
// default that strands people.
define_wire_enum!(CHROME_SCHEMA, Chrome {
    Modern = 1,
    System = 2,
});

// Which of two title bars this window has. `Chrome` chooses who draws the caption; this
// chooses, inside that, what kind of window the caption belongs to.
//
// What each one means is not the same on every platform, and the numbers are never the
// Host's. On macOS the buttons sit further in and the window is rounded more; on Windows
// and Linux the caption is a different height and the buttons stay where that platform
// puts them.
define_wire_enum!(TITLE_BAR_SCHEMA, TitleBar {
    // What an application on that platform actually looks like now, which is why it is the
    // default.
    Normal = 1,
    // An ordinary window. For tool applications, for the places the other reads oddly, and
    // as the way back.
    Simple = 2,
});

/// What an application may decide about its own window.
///
/// The window belongs to the Renderer, so this is short and stays short. What is here is
/// what an application knows and the Renderer cannot guess: what the window is called and
/// how large it should open. Nothing here names a colour or a corner, because those are
/// the design system's, and nothing here is read more than once.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Window {
    pub chrome: Chrome,
    /// Which of the two title bars this window has.
    pub title_bar: TitleBar,
    /// What the window calls itself.
    ///
    /// Empty means the application said nothing, and the renderer uses its own name for
    /// it. It is not decoration: a desktop lists windows by this, and a window with no
    /// title of its own is listed under whatever the renderer happened to be called.
    pub title: &'static str,
    /// The picture the window wears, as an asset id, or zero for none.
    ///
    /// An id rather than a path or a name, because a path is a fact about the machine the
    /// application was built on and a name asks the toolkit to find something it may not
    /// have. Registering the bytes and referring to them is what every other picture in
    /// this protocol does.
    pub icon: u32,
    /// Zero means the Renderer chooses, which is what an application that said nothing
    /// gets. A size is in the same density independent pixels gestures are measured in.
    pub width: u16,
    pub height: u16,
    pub min_width: u16,
    pub min_height: u16,
    pub resizable: bool,
}

impl Window {
    pub const fn new() -> Self {
        Self {
            chrome: Chrome::Modern,
            title_bar: TitleBar::Normal,
            title: "",
            icon: 0,
            width: 0,
            height: 0,
            min_width: 0,
            min_height: 0,
            resizable: true,
        }
    }

    /// Which of the two title bars this window has.
    ///
    /// `Normal` unless it is said otherwise, because that is the window an application on
    /// this platform has now. What the mode is worth in pixels is the design system's, so
    /// nothing here is a measurement.
    pub const fn with_title_bar(mut self, title_bar: TitleBar) -> Self {
        self.title_bar = title_bar;
        self
    }

    /// Names the window. A literal, because this is read once before the window is stood
    /// up and never again, so there is nothing for a computed title to change.
    pub const fn with_title(mut self, title: &'static str) -> Self {
        self.title = title;
        self
    }

    /// Dresses the window in an asset that was registered with [`AssetKind::Png`].
    pub const fn with_icon(mut self, asset_id: u32) -> Self {
        self.icon = asset_id;
        self
    }

    pub const fn with_chrome(mut self, chrome: Chrome) -> Self {
        self.chrome = chrome;
        self
    }

    pub const fn with_size(mut self, width: u16, height: u16) -> Self {
        self.width = width;
        self.height = height;
        self
    }

    pub const fn with_min_size(mut self, width: u16, height: u16) -> Self {
        self.min_width = width;
        self.min_height = height;
        self
    }

    pub const fn resizable(mut self, resizable: bool) -> Self {
        self.resizable = resizable;
        self
    }
}

impl Default for Window {
    fn default() -> Self {
        Self::new()
    }
}

/// A design system choice plus how it reacts to the host platform.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Theme {
    pub design_system: DesignSystem,
    pub fallback: DesignSystem,
    pub color_scheme: ColorScheme,
    pub adaptive: bool,
    /// The font asset each type role resolves to, or zero for the system font.
    ///
    /// Per theme and not per node. A node that could name its own font would be a node
    /// deciding typography, and the whole point of roles is that it does not: an
    /// application changes what `Display` is made of, and every title changes with it.
    ///
    /// Indexed by the role's wire tag minus one, so the array and the enum cannot drift
    /// apart without the compiler saying so.
    pub fonts: [u32; TYPE_ROLE_COUNT],
    /// The application's own colours, laid over whichever design system is chosen.
    ///
    /// A reference to something that lives for the whole program, so that a theme stays
    /// `Copy` and can still be built in a `const`. The same palette goes onto every system
    /// an adaptive theme may pick, because a brand colour does not change with the
    /// platform. A palette built while the application runs is handed to `use_theme`,
    /// which owns it, so application code never has to give it that lifetime itself.
    pub palette: Option<&'static crate::palette::Palette>,
}

/// How many type roles there are, which is how many font slots a theme carries.
pub const TYPE_ROLE_COUNT: usize = 9;

impl Theme {
    /// The same design system on every platform.
    pub const fn unified(design_system: DesignSystem) -> Self {
        Self {
            design_system,
            fallback: design_system,
            color_scheme: ColorScheme::FollowSystem,
            adaptive: false,
            fonts: [0; TYPE_ROLE_COUNT],
            palette: None,
        }
    }

    /// Follows the host platform. The fallback is required, so no platform is left undecided.
    pub const fn adaptive(fallback: DesignSystem) -> Self {
        Self {
            design_system: fallback,
            fallback,
            color_scheme: ColorScheme::FollowSystem,
            adaptive: true,
            fonts: [0; TYPE_ROLE_COUNT],
            palette: None,
        }
    }

    pub const fn with_color_scheme(mut self, color_scheme: ColorScheme) -> Self {
        self.color_scheme = color_scheme;
        self
    }

    /// Resolves one type role to a registered font, leaving every other role alone.
    ///
    /// The asset has to be registered before the theme that names it reaches the
    /// Renderer, the same way a picture does. An id that names nothing is reported and
    /// the role falls back to the system font, because a screen in the wrong typeface is
    /// better than no screen.
    ///
    /// `TypeRole::None` has no font slot: text with no role names its own font, so asking
    /// the theme to give it one changes nothing.
    pub const fn with_font(mut self, role: TypeRole, asset: u32) -> Self {
        if let TypeRole::None = role {
            return self;
        }
        self.fonts[role as usize - 1] = asset;
        self
    }

    /// Lays the application's own colours over the design system.
    ///
    /// Only colour roles change. Shape, type, spacing, elevation, motion and how each
    /// component is drawn stay the design system's, so the same brand colour reads as
    /// that system's button rather than as a copy of another's. Both schemes go to the
    /// Renderer at once, so a switch between light and dark never asks the Host.
    pub const fn with_palette(mut self, palette: &'static crate::palette::Palette) -> Self {
        self.palette = Some(palette);
        self
    }

    /// The font this role resolves to, or `None` for the system font.
    pub const fn font(&self, role: TypeRole) -> Option<u32> {
        if let TypeRole::None = role {
            return None;
        }
        match self.fonts[role as usize - 1] {
            0 => None,
            asset => Some(asset),
        }
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
    /// Asks the Renderer to report this node's measured size, the way it reports the
    /// window's.
    ///
    /// The token is the application's own name for the node. A node id belongs to the
    /// Renderer and is never something the Host chose, so a screen that wants to know how
    /// wide one of its own containers ended up needs a name it gave itself. The Renderer
    /// does not read it.
    ObserveSize {
        token: u32,
    },
    /// Which curve and length this node's changes run along.
    ///
    /// The node says how important the change is, not how long it takes. Appearing,
    /// disappearing, a selection moving and a size changing all read this.
    Motion(MotionRole),
    /// What this node's surface is made of.
    ///
    /// A role, so that a system which blurs blurs and a system which does not lifts its
    /// surface instead. Nothing here claims a particular effect was achieved.
    Material(MaterialRole),
}

const NO_FIELDS: &[FieldSchema] = &[];
const TOKEN_U32_FIELD: &[FieldSchema] = &[FieldSchema {
    name: "token",
    ty: FieldType::U32,
    slot: FieldSlot::FirstLow,
}];
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
const MOTION_ROLE_FIELD: &[FieldSchema] = &[FieldSchema {
    name: "role",
    ty: FieldType::Role("MotionRole"),
    slot: FieldSlot::FirstLow,
}];
const MATERIAL_ROLE_FIELD: &[FieldSchema] = &[FieldSchema {
    name: "role",
    ty: FieldType::Role("MaterialRole"),
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
    VariantSchema {
        name: "ObserveSize",
        tag: 16,
        fields: TOKEN_U32_FIELD,
    },
    VariantSchema {
        name: "Motion",
        tag: 17,
        fields: MOTION_ROLE_FIELD,
    },
    VariantSchema {
        name: "Material",
        tag: 18,
        fields: MATERIAL_ROLE_FIELD,
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
    // The id of an asset the Host registered. Image and Icon carry nothing else: the
    // bytes were copied into the Renderer's cache once, at registration.
    Asset = 28,
    // Whether a toggle is on. One boolean for all three of them: a selected radio button
    // and a switch that is on are the same fact, and the schema names a concept once.
    Checked = 32,
    // Discrete stops between a slider's two ends. Zero leaves it continuous.
    Steps = 33,
    // Whether a progress indicator knows how far along it is. When it does not, `Value`
    // is not read at all.
    Determinate = 34,
    // Which of the two forms a progress indicator takes. It picks a shape, not a size.
    Circular = 35,
    // A divider's axis, which is the only thing a divider carries.
    Vertical = 36,
    // Whether an overlay is showing. The Renderer owns the state; this seeds it and
    // carries changes that came from outside the Renderer.
    Open = 40,
    OnDismiss = 41,
    SelectedIndex = 42,
    // The Canvas drawing command list, a byte blob in the batch arena.
    Commands = 50,
    // The control's current value, in the widget's own unit: an epoch day count for a
    // date, minutes since midnight for a time, the chosen position for a Dropdown, a
    // position between the ends for a Slider, a fraction for a ProgressIndicator. One tag,
    // because "this control's value" is one concept whichever widget holds it.
    Value = 51,
    // The ends of the selectable range, in the same unit as `Value`.
    Min = 52,
    Max = 53,
    // The meaning of the icon a destination carries, as an `IconRole` tag. Tag 0 is "not
    // sent", so a destination without an icon is label only.
    Icon = 60,
    // Which slot of a Scaffold this subtree fills.
    Slot = 61,
    // How many columns a grid has, where the screen said a number.
    Columns = 62,
    // How narrow a column may get before the grid drops one, where the screen said a
    // width instead of a number. The Renderer divides its own width by this, which is the
    // same kind of judgement as a size class and for the same reason is not the Host's.
    MinColumnWidth = 63,
    // Runs of different treatment inside one string, as a blob of fixed-length records.
    // A Text that says nothing about runs carries none of this and travels as it always
    // did.
    Spans = 64,
    // A node that is willing to have files dropped on it. The handler is the willingness:
    // a node without one is never told that files are over it, which is what keeps a
    // screen from lighting up every container it has.
    OnFilesEntered = 65,
    OnFilesDropped = 66,
    // Which named group of a navigation's strip this destination belongs to. Neighbouring
    // destinations carrying the same string are one group and the string is its heading.
    //
    // 76 rather than 67, because 67 through 74 are reserved for the gestures and 75 for a
    // button's kind. A reservation is not a free tag.
    Section = 76,
    // How many a badge counts. Absent rather than zero when the badge is a dot or a word,
    // because "no count" and "a count of nothing" are different things to show.
    //
    // 80 opens a fresh block of ten for widget tags 40 onwards, after the last tag in use
    // anywhere, so two pieces of work landing in either order cannot collide.
    Count = 80,
    // Whether a split pane's side pane may be folded away by dragging past its minimum, or
    // by the keyboard, or by the platform's own show and hide sidebar command. Whether a
    // side pane can be hidden is a fact about the screen rather than about the design
    // system: a list of sessions can, a settings screen's list of sections cannot.
    //
    // 90 opens the split pane's block of ten, after the badge's.
    Collapsible = 90,
    // The text a page laid out by CSS draws, which takes its letters from its own values
    // rather than from a rung of the type ladder. 100 opens the block for it.
    //
    // The fonts to try, in order, for text whose role is `TypeRole::None`: a list of up to
    // eight registered font assets, installed families by name and generic families, as a
    // blob. Text with a role ignores it, because its font is the theme's.
    Font = 100,
    // Where a line may break, and whether a word too long for its line may be broken
    // inside. The `WordBreak` and `OverflowWrap` roles.
    WordBreak = 101,
    OverflowWrap = 102,
    // How many spaces' width apart the tab stops are. Absent is eight. A tab advances to
    // the next stop, which only the side that measures the letters can work out.
    TabSize = 103,
    // Whether the sizes are CSS pixels, which the system's font scale does not enlarge.
    AbsoluteSize = 104,
    // Whether lines wrap at the width they are given. Absent is yes; `false` is CSS
    // `nowrap` and `pre`, whose lines run as long as their text.
    SoftWrap = 105,
    // The font each run of a `Text` with no role is set in, as a blob beside `Spans`
    // rather than inside its records: a run record keeps the 36 bytes it has always had,
    // and only text that names fonts carries this at all.
    SpanFonts = 106,
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
    /// A control's new value, in the widget's own unit: an epoch count for a picker, a
    /// position for a slider, 0.0 or 1.0 for a toggle. The Renderer decided how the user
    /// reached it, so nothing about calendars, wheels, tracks or thumbs crosses here.
    ///
    /// One `f64` holds all of them: integers up to 2^53 survive it exactly, so splitting
    /// the event in two would only give the same concept two names.
    ValueChanged(f64),
    /// The window, or one observed node, moved into a different size class. The Renderer
    /// measures it and sends this only when a class changes, never on every layout pass.
    /// A `node_id` of 0 is the window; anything else is the node that asked to be
    /// measured.
    WindowSizeChanged {
        width_dp: f32,
        height_dp: f32,
        class: WindowSizeClass,
        height_class: WindowHeightClass,
    },
    /// Files are over a node that said it would take them. Nothing about what they are:
    /// the platforms disagree about what is knowable before a drop, and a screen that
    /// only needs to light up does not need to know.
    FilesEntered,
    /// Files were let go over a node. The paths arrive together, separated by a byte no
    /// path on any of the three desktops may contain.
    FilesDropped(&'a str),
    /// The Renderer resolved the theme and this is what it chose. Sent once and then
    /// only when the answer changes, the same way a size class is.
    DesignSystemResolved(DesignSystem),
    /// The Renderer lost its node table and asks for the whole tree again. A recreated
    /// Activity is the case that produces it.
    Resync,
    /// The platform brought the UI back. Timers and animations resume.
    LifecycleStart,
    /// The platform stopped the UI. Timers and animations are suppressed, so a process
    /// that is not on screen is not asked to draw.
    LifecycleStop,
    /// The user pressed a notification the application posted. `action` is 0 for the body
    /// and 1 or 2 for an action button; `key` is the name the application gave it.
    ///
    /// A name rather than a handler, because a notification outlives the component that
    /// posted it by hours and a callback dies with its scope.
    NotificationActivated {
        action: u32,
        key: &'a str,
    },
    /// Whether notifications may be shown, sent once after start and then only when the
    /// answer changes.
    NotificationPermissionChanged(NotificationPermission),
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
        // Tags 8 to 15 are reserved for the pointer gesture events.
        tag: 16,
        payload: EventPayloadType::Double,
    },
    EventSchema {
        name: "WindowSizeChanged",
        tag: 17,
        payload: EventPayloadType::WindowSize,
    },
    // The three below address the Host itself rather than a node, so they carry no handler
    // and the Host answers them before it looks one up.
    EventSchema {
        name: "Resync",
        tag: 18,
        payload: EventPayloadType::None,
    },
    EventSchema {
        name: "LifecycleStart",
        tag: 19,
        payload: EventPayloadType::None,
    },
    EventSchema {
        name: "LifecycleStop",
        tag: 20,
        payload: EventPayloadType::None,
    },
    // Addressed to the Host rather than to a node, like the three above: it is a fact
    // about the window, not something that happened inside it.
    EventSchema {
        name: "DesignSystemResolved",
        tag: 21,
        payload: EventPayloadType::DesignSystem,
    },
    EventSchema {
        name: "FilesEntered",
        tag: 22,
        payload: EventPayloadType::None,
    },
    // One string for however many paths, which is the string convention unchanged. A
    // second way of carrying a list would be a second thing to keep in step.
    EventSchema {
        name: "FilesDropped",
        tag: 23,
        payload: EventPayloadType::Text,
    },
    // Both address the Host itself, like the resolved design system: a notification is not
    // in the tree, so there is no node and no handler to name.
    EventSchema {
        name: "NotificationActivated",
        tag: 24,
        payload: EventPayloadType::NotificationActivation,
    },
    EventSchema {
        name: "NotificationPermissionChanged",
        tag: 25,
        payload: EventPayloadType::NotificationPermission,
    },
];

/// One argument of a boundary operation.
///
/// The set is deliberately tiny, because only primitives, pointers and lengths cross the
/// boundary. Every platform binding can then be generated from the same list.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BoundaryParam {
    /// A readable byte range. It arrives as a direct byte buffer and a length on Android,
    /// as a pointer and a length on desktop.
    Bytes { name: &'static str },
    /// A 64-bit platform frame timestamp.
    Nanos { name: &'static str },
}

impl BoundaryParam {
    pub const fn name(self) -> &'static str {
        match self {
            Self::Bytes { name } | Self::Nanos { name } => name,
        }
    }
}

/// One logical boundary operation, stated without saying which side calls it.
///
/// `symbol` is the Host's C export. Every platform binding, the GraalVM function
/// declarations, the Android shims and the browser forwarders, is a rendering of this
/// table, which is why none of them is written by hand.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct BoundaryOp {
    /// Direction-neutral name, in PascalCase.
    pub name: &'static str,
    pub symbol: &'static str,
    pub params: &'static [BoundaryParam],
    /// Whether the operation answers with a mutation batch.
    pub returns_batch: bool,
    /// Whether the call may skip the runtime's thread state transition on Android. That
    /// is only sound for a call that cannot run long, because the thread stays runnable
    /// and the garbage collector cannot suspend it while the call is in flight. A call
    /// that runs the VirtualDom can run long.
    pub fast: bool,
}

pub const BOUNDARY_SCHEMA: &[BoundaryOp] = &[
    BoundaryOp {
        name: "Init",
        symbol: "compose_rust_host_init",
        params: &[BoundaryParam::Bytes { name: "handshake" }],
        returns_batch: true,
        fast: false,
    },
    BoundaryOp {
        name: "DispatchEvent",
        symbol: "compose_rust_host_dispatch_event",
        params: &[BoundaryParam::Bytes { name: "event" }],
        returns_batch: true,
        fast: false,
    },
    BoundaryOp {
        name: "RenderFrame",
        symbol: "compose_rust_host_render_frame",
        params: &[BoundaryParam::Nanos {
            name: "frameTimeNanos",
        }],
        returns_batch: true,
        fast: false,
    },
    BoundaryOp {
        name: "ReleaseBatch",
        symbol: "compose_rust_host_release_batch",
        params: &[],
        returns_batch: false,
        fast: true,
    },
    BoundaryOp {
        name: "Shutdown",
        symbol: "compose_rust_host_shutdown",
        params: &[],
        returns_batch: false,
        fast: false,
    },
];

/// The JVM class the Android shims bind to. The Kotlin file is generated under the same
/// name, so the two sides cannot drift.
pub const ANDROID_BRIDGE_CLASS: &str = "dev/darkpyonix/composerust/ui/platform/HostBridge";

// ---------------------------------------------------------------------------------------
// The browser's shared linear memory.
//
// One `WebAssembly.Memory` holds both modules' data. The Kotlin module defines and exports
// it, because a Kotlin/Wasm module has no way to import one, and the Rust module is linked
// with `--import-memory` so that the arena the Host writes is the arena the Renderer reads.
// Nothing below crosses the boundary as data: these are the addresses and offsets both
// generated halves are written against.
// ---------------------------------------------------------------------------------------

/// The first address that belongs to the Rust module.
///
/// Below it is the Kotlin module's `kotlin.wasm.unsafe` allocator; at and above it are the
/// Rust module's data, stack and heap, which the linker is told with `--global-base`. Two
/// allocators handing out addresses in one memory is the hazard: an overlap does not crash,
/// it draws the wrong screen, so the Renderer checks which side of this line its own
/// addresses fall on before it makes the first boundary call.
pub const WEB_RUST_REGION_BASE: u32 = 4 * 1024 * 1024;

/// One field of the `MutationBatch` record the Host writes into the shared memory.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct WebBatchField {
    /// PascalCase, so the generated Kotlin constant reads as a name rather than an offset.
    pub name: &'static str,
    pub offset: u32,
    pub width: u32,
    pub description: &'static str,
}

/// `MutationBatch` as a wasm32 target lays it out.
///
/// The offsets are written down rather than read from `offset_of!` because codegen runs on
/// the machine that generates, not the machine that runs: a 64-bit host makes the same
/// `#[repr(C)]` struct 24 bytes with an 8 byte pointer. The generated wasm glue asserts
/// this table against the real layout, so a compiler that disagrees stops that build
/// instead of producing a Renderer that reads the wrong four bytes.
pub const WEB_BATCH_FIELDS: &[WebBatchField] = &[
    WebBatchField {
        name: "Address",
        offset: 0,
        width: 4,
        description: "where the batch starts, as an address in the shared memory",
    },
    WebBatchField {
        name: "Length",
        offset: 4,
        width: 4,
        description: "the batch's length in bytes",
    },
    WebBatchField {
        name: "Result",
        offset: 8,
        width: 8,
        description: "the handler's synchronous result",
    },
];

/// How long that record is, including the padding `#[repr(C)]` adds before the result.
pub const WEB_BATCH_BYTES: u32 = 16;

/// How much room the Host lends the Renderer to encode one event into.
///
/// The same figure the Android connection allocates for the same job. An event that would
/// not fit is refused by the encoder rather than truncated.
pub const WEB_EVENT_BUFFER_BYTES: u32 = 4096;

/// Where the event buffer starts inside the block `compose_rust_host_web_start` reports.
///
/// The out record comes first, at offset zero, and the event buffer follows it.
pub const WEB_EVENT_BUFFER_OFFSET: u32 = WEB_BATCH_BYTES;

/// The extra Host export a browser needs, because a page has no library loader.
///
/// It does what `JNI_OnLoad` does on Android, registering the root component and installing
/// the renderer API, and it answers with the address of the block above.
pub const WEB_START_SYMBOL: &str = "compose_rust_host_web_start";

/// The wasm import module the Host's frame request comes from.
///
/// This direction has no JavaScript on it: the page hands Kotlin's exported function object
/// straight to the Rust instantiation, which the engine binds as a wasm-to-wasm call. It can,
/// because Rust is instantiated second and Kotlin's exports already exist by then.
pub const WEB_RENDERER_IMPORT_MODULE: &str = "compose_rust_renderer";

/// The global the generated forwarders read the Host's exports off.
///
/// A `@JsFun` body is inlined into the import object module Kotlin generates, so the only
/// name it can reach the loader through is a property of the global object.
pub const WEB_HOST_GLOBAL: &str = "__composeRustHost";

/// The global the loader module publishes the compiled Host module under.
///
/// Compiling is all the page does, and the reason the page has to do it at all is that
/// compiling is asynchronous while the boundary is not: the fetch has to have finished
/// before the Renderer's `main` runs. Everything after it, the memory, the imports and the
/// entry point, is in the generated Kotlin, because only the Renderer's own module can name
/// them.
pub const WEB_MODULE_GLOBAL: &str = "__composeRustHostModule";
