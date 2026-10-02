//! Design token tables.
//!
//! The tables are authored here, in Rust, because Rust is the single source for everything
//! that has two representations, and because values written in Kotlin by hand would go
//! unverified. They are *executed* in the Renderer: codegen writes them into
//! `Protocol.gen.kt` as Kotlin objects, so they enter the Renderer binary at build time and
//! never cross the boundary at run time. The Host still sends roles and never a token
//! table.
//!
//! What the Renderer implementer still owns is the rules these values feed: how an
//! elevation is drawn, how each `ButtonVariant` is styled, and motion.

use crate::schema::{Color, ColorRole, ColorScheme, DesignSystem, ShapeRole, SpaceRole, TypeRole};

/// One `ColorRole` slot in both schemes, in `ColorRole` tag order.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ColorToken {
    pub role: ColorRole,
    pub light: Color,
    pub dark: Color,
}

/// One rung of the nine-rung type ladder (13.2).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TypeToken {
    pub role: TypeRole,
    /// Font size in sp.
    pub size: f32,
    /// 100 to 900.
    pub weight: u16,
    /// Line height in sp.
    pub line_height: f32,
    /// Letter spacing in sp, which may be negative.
    pub letter_spacing: f32,
    /// True when the rung uses the monospace family rather than the default one.
    pub monospace: bool,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShapeToken {
    pub role: ShapeRole,
    /// Corner radius in dp. `Full` uses a radius large enough to read as a pill.
    pub radius: f32,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SpaceToken {
    pub role: SpaceRole,
    /// Spacing in dp.
    pub value: f32,
}

/// Items 1 to 4 of the 14.6 table for one design system.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DesignTokenTable {
    pub system: DesignSystem,
    /// The published guideline the values come from, recorded as 14.3 asks.
    pub reference: &'static str,
    pub default_family: &'static str,
    pub monospace_family: &'static str,
    pub colors: &'static [ColorToken; 23],
    pub type_scale: &'static [TypeToken; 9],
    pub shapes: &'static [ShapeToken; 6],
    pub spaces: &'static [SpaceToken; 7],
}

impl DesignTokenTable {
    pub fn color(&self, role: ColorRole, scheme: ColorScheme) -> Color {
        let token = self.colors[role as usize - 1];
        // `FollowSystem` is resolved by the Renderer, which is the side that knows.
        // Asking the Host for it can only mean light.
        if scheme == ColorScheme::Dark {
            token.dark
        } else {
            token.light
        }
    }
}

/// Every design system's table, in `DesignSystem` tag order.
///
/// A fourth design system is one entry here plus one Renderer rule implementation.
/// Nothing in `widgets.rs`, the Modifier schema or the Property schema moves (14.1).
pub const DESIGN_TOKENS: &[DesignTokenTable] = &[
    MATERIAL3,
    APPLE_HIG,
    FLUENT,
    ADWAITA,
    BREEZE,
    DEEPIN,
    LIQUID_GLASS,
];

pub fn table(system: DesignSystem) -> &'static DesignTokenTable {
    &DESIGN_TOKENS[system as usize - 1]
}

macro_rules! colors {
    ($($role:ident: $light:literal / $dark:literal),+ $(,)?) => {
        &[$(ColorToken {
            role: ColorRole::$role,
            light: Color::rgb($light),
            dark: Color::rgb($dark),
        }),+]
    };
}

macro_rules! type_scale {
    ($($role:ident: $size:literal / $weight:literal / $line:literal / $spacing:literal / $mono:literal),+ $(,)?) => {
        &[$(TypeToken {
            role: TypeRole::$role,
            size: $size,
            weight: $weight,
            line_height: $line,
            letter_spacing: $spacing,
            monospace: $mono,
        }),+]
    };
}

macro_rules! shapes {
    ($($role:ident: $radius:literal),+ $(,)?) => {
        &[$(ShapeToken { role: ShapeRole::$role, radius: $radius }),+]
    };
}

macro_rules! spaces {
    ($($role:ident: $value:literal),+ $(,)?) => {
        &[$(SpaceToken { role: SpaceRole::$role, value: $value }),+]
    };
}

const MATERIAL3: DesignTokenTable = DesignTokenTable {
    system: DesignSystem::Material3,
    reference: "Material 3 Expressive shape, colour and type scales, m3.material.io, 2025",
    default_family: "Roboto",
    monospace_family: "Roboto Mono",
    colors: colors! {
        Primary: 0x6750a4 / 0xd0bcff,
        OnPrimary: 0xffffff / 0x381e72,
        Secondary: 0x625b71 / 0xccc2dc,
        OnSecondary: 0xffffff / 0x332d41,
        Surface: 0xfef7ff / 0x141218,
        OnSurface: 0x1d1b20 / 0xe6e0e9,
        SurfaceVariant: 0xe7e0ec / 0x49454f,
        OnSurfaceVariant: 0x49454f / 0xcac4d0,
        Background: 0xfef7ff / 0x141218,
        OnBackground: 0x1d1b20 / 0xe6e0e9,
        Outline: 0x79747e / 0x938f99,
        OutlineVariant: 0xcac4d0 / 0x49454f,
        Error: 0xb3261e / 0xf2b8b5,
        OnError: 0xffffff / 0x601410,
        // Material 3's own `surfaceContainer`, which is how this system raises a panel:
        // `Surface` and `Background` are one value here by design, so the container roles
        // are the only thing that separates a layer from the page.
        SurfaceContainer: 0xf3edf7 / 0x211f26,
        // The baseline scheme's own third accent and its four container tones. Material 3
        // is where the container idea comes from, so these are its published values rather
        // than anything derived here.
        Tertiary: 0x7d5260 / 0xefb8c8,
        OnTertiary: 0xffffff / 0x492532,
        PrimaryContainer: 0xeaddff / 0x4f378b,
        OnPrimaryContainer: 0x21005d / 0xeaddff,
        SecondaryContainer: 0xe8def8 / 0x4a4458,
        OnSecondaryContainer: 0x1d192b / 0xe8def8,
        TertiaryContainer: 0xffd8e4 / 0x633b48,
        OnTertiaryContainer: 0x31111d / 0xffd8e4,
    },
    type_scale: type_scale! {
        Display: 57.0 / 400 / 64.0 / 0.0 / false,
        Headline: 32.0 / 400 / 40.0 / 0.0 / false,
        Title: 22.0 / 400 / 28.0 / 0.0 / false,
        Subtitle: 16.0 / 500 / 24.0 / 0.15 / false,
        Body: 16.0 / 400 / 24.0 / 0.5 / false,
        BodyStrong: 16.0 / 500 / 24.0 / 0.15 / false,
        Label: 14.0 / 500 / 20.0 / 0.1 / false,
        Caption: 12.0 / 400 / 16.0 / 0.4 / false,
        Mono: 14.0 / 400 / 20.0 / 0.0 / true,
    },
    // The expressive ladder, which is the baseline one shifted up a rung at every step
    // above the smallest. This is the loudest thing about the reference screens: a card
    // there is cut at twenty eight rather than sixteen, a sheet is rounder still, and the
    // buttons and the chips and the selected destination are all capsules. The baseline
    // ladder drew the same layout with corners half the size, which reads as the previous
    // version of Material rather than as this one.
    shapes: shapes! {
        None: 0.0,
        ExtraSmall: 4.0,
        Small: 12.0,
        Medium: 16.0,
        Large: 28.0,
        Full: 1000.0,
    },
    spaces: spaces! {
        None: 0.0,
        Xs: 4.0,
        Sm: 8.0,
        Md: 16.0,
        Lg: 24.0,
        Xl: 32.0,
        Xxl: 48.0,
    },
};

const APPLE_HIG: DesignTokenTable = DesignTokenTable {
    system: DesignSystem::Cupertino,
    reference: "Apple Human Interface Guidelines, system colors and Dynamic Type, 2024",
    default_family: "SF Pro",
    monospace_family: "SF Mono",
    colors: colors! {
        Primary: 0x007aff / 0x0a84ff,
        OnPrimary: 0xffffff / 0xffffff,
        Secondary: 0x5856d6 / 0x5e5ce6,
        OnSecondary: 0xffffff / 0xffffff,
        Surface: 0xffffff / 0x1c1c1e,
        OnSurface: 0x000000 / 0xffffff,
        // The page is the grouped background and the variant is the colour an incoming
        // message bubble has. They used to carry the same value, so anything filled with
        // one on a page of the other was drawn, in the right colour, and invisible.
        SurfaceVariant: 0xe9e9eb / 0x2c2c2e,
        OnSurfaceVariant: 0x3c3c43 / 0xebebf5,
        Background: 0xf2f2f7 / 0x000000,
        OnBackground: 0x000000 / 0xffffff,
        Outline: 0xc6c6c8 / 0x38383a,
        OutlineVariant: 0xe5e5ea / 0x48484a,
        Error: 0xff3b30 / 0xff453a,
        OnError: 0xffffff / 0xffffff,
        // secondarySystemGroupedBackground: the colour of a grouped box sitting on the
        // grouped page. It matches `Surface` here, and that is correct rather than a
        // duplicate: on iOS a panel on the grouped page is the plain reading surface, and
        // what makes it a panel is that the page underneath is not.
        SurfaceContainer: 0xffffff / 0x1c1c1e,
        // systemPurple, the third of the platform accents after blue and indigo.
        Tertiary: 0xaf52de / 0xbf5af2,
        OnTertiary: 0xffffff / 0xffffff,
        // iOS has no published container tones, so these are the tinted fills the platform
        // draws by hand: a wash of the accent in light, and a deep, desaturated version of
        // it in dark, which is what a selected row or a tinted card looks like there.
        PrimaryContainer: 0xd6e4ff / 0x0a2d52,
        OnPrimaryContainer: 0x003070 / 0xcfe3ff,
        SecondaryContainer: 0xe2e0ff / 0x262663,
        OnSecondaryContainer: 0x2a1b70 / 0xdedcff,
        TertiaryContainer: 0xf3ddfb / 0x3f1a52,
        OnTertiaryContainer: 0x3d0b52 / 0xf1d9fa,
    },
    // The large title is bold. Both reference screens set it that way, "Contacts" over a
    // grouped list and "Cupertino" over a search field, and a large title at book weight
    // is the one thing that stops an iOS screen reading as an iOS screen.
    type_scale: type_scale! {
        Display: 34.0 / 700 / 41.0 / 0.37 / false,
        Headline: 28.0 / 400 / 34.0 / 0.36 / false,
        Title: 22.0 / 400 / 28.0 / 0.35 / false,
        Subtitle: 17.0 / 600 / 22.0 / -0.41 / false,
        Body: 17.0 / 400 / 22.0 / -0.41 / false,
        BodyStrong: 17.0 / 600 / 22.0 / -0.41 / false,
        Label: 15.0 / 400 / 20.0 / -0.24 / false,
        Caption: 12.0 / 400 / 16.0 / 0.0 / false,
        Mono: 15.0 / 400 / 20.0 / 0.0 / true,
    },
    // HIG corners are continuous curvature. The radius is the same number, but the
    // Renderer is expected to draw it with a squircle rather than a circular arc.
    shapes: shapes! {
        None: 0.0,
        ExtraSmall: 4.0,
        Small: 8.0,
        Medium: 10.0,
        Large: 14.0,
        Full: 1000.0,
    },
    spaces: spaces! {
        None: 0.0,
        Xs: 4.0,
        Sm: 8.0,
        Md: 16.0,
        Lg: 20.0,
        Xl: 32.0,
        Xxl: 44.0,
    },
};

const FLUENT: DesignTokenTable = DesignTokenTable {
    system: DesignSystem::Fluent,
    reference: "WinUI / Fluent 2 web and Windows tokens, fluent2.microsoft.design, 2024",
    default_family: "Segoe UI Variable",
    monospace_family: "Cascadia Mono",
    colors: colors! {
        Primary: 0x0f6cbd / 0x479ef5,
        OnPrimary: 0xffffff / 0x000000,
        Secondary: 0xebf3fc / 0x0c3b5e,
        OnSecondary: 0x0f548c / 0xffffff,
        Surface: 0xffffff / 0x292929,
        OnSurface: 0x242424 / 0xffffff,
        // Fluent layers by lightness rather than by outline, and the page it layers on is
        // the solid background base. The old trio sat within five parts of each other, so
        // the layering was there in the numbers and not on the screen.
        SurfaceVariant: 0xeaeaea / 0x333333,
        OnSurfaceVariant: 0x424242 / 0xd6d6d6,
        Background: 0xf3f3f3 / 0x1f1f1f,
        OnBackground: 0x242424 / 0xffffff,
        Outline: 0xd1d1d1 / 0x666666,
        OutlineVariant: 0xe0e0e0 / 0x3d3d3d,
        Error: 0xc50f1f / 0xdc626d,
        OnError: 0xffffff / 0x000000,
        // The card layer, which Fluent lifts off the solid background base by lightness.
        SurfaceContainer: 0xffffff / 0x2b2b2b,
        // The shared purple ramp, which is Fluent's accent beside the brand blue.
        Tertiary: 0x8764b8 / 0xb18cd9,
        OnTertiary: 0xffffff / 0x22103a,
        // Fluent's brand tints, which are how it fills a selected or highlighted region:
        // two steps of the brand ramp in light, and the dark shades of it in dark. The
        // secondary tint is a step deeper than the primary one rather than a different
        // hue, because Fluent's second accent is the same blue used more strongly.
        PrimaryContainer: 0xcfe4fa / 0x0c3b5e,
        OnPrimaryContainer: 0x0c3b5e / 0xcfe4fa,
        SecondaryContainer: 0xb4d6fa / 0x123d61,
        OnSecondaryContainer: 0x0b3350 / 0xb4d6fa,
        TertiaryContainer: 0xe8dcf7 / 0x3b2159,
        OnTertiaryContainer: 0x341a5e / 0xe8dcf7,
    },
    type_scale: type_scale! {
        Display: 40.0 / 600 / 52.0 / 0.0 / false,
        Headline: 28.0 / 600 / 36.0 / 0.0 / false,
        Title: 20.0 / 600 / 28.0 / 0.0 / false,
        Subtitle: 16.0 / 600 / 22.0 / 0.0 / false,
        Body: 14.0 / 400 / 20.0 / 0.0 / false,
        BodyStrong: 14.0 / 600 / 20.0 / 0.0 / false,
        Label: 12.0 / 400 / 16.0 / 0.0 / false,
        Caption: 12.0 / 400 / 16.0 / 0.0 / false,
        Mono: 13.0 / 400 / 18.0 / 0.0 / true,
    },
    shapes: shapes! {
        None: 0.0,
        ExtraSmall: 2.0,
        Small: 3.0,
        Medium: 4.0,
        Large: 8.0,
        Full: 1000.0,
    },
    spaces: spaces! {
        None: 0.0,
        Xs: 2.0,
        Sm: 4.0,
        Md: 8.0,
        Lg: 12.0,
        Xl: 20.0,
        Xxl: 32.0,
    },
};

const ADWAITA: DesignTokenTable = DesignTokenTable {
    system: DesignSystem::Gnome,
    reference: "GNOME Human Interface Guidelines and the libadwaita named colours, GNOME 50",
    default_family: "Cantarell",
    monospace_family: "Source Code Pro",
    colors: colors! {
        // accent_bg_color, blue 3 in the GNOME palette.
        Primary: 0x3584e4 / 0x3584e4,
        OnPrimary: 0xffffff / 0xffffff,
        // purple 3, the rare second accent. Never a second button colour.
        Secondary: 0x9141ac / 0xc061cb,
        // White in both schemes, which is what libadwaita puts on every accent fill but
        // yellow. On the lighter purple 2 of the dark scheme that measures 3.6:1, which
        // carries a short control label but would not carry body text. Nothing puts body
        // text on the second accent.
        OnSecondary: 0xffffff / 0xffffff,
        // view_bg_color: the white of a list or a text view.
        Surface: 0xffffff / 0x1e1e1e,
        OnSurface: 0x2e3436 / 0xffffff,
        // headerbar_bg_color: the slightly darker grey of chrome.
        SurfaceVariant: 0xebebeb / 0x303030,
        OnSurfaceVariant: 0x5e5c64 / 0xc0bfbc,
        // window_bg_color.
        Background: 0xfafafa / 0x242424,
        OnBackground: 0x2e3436 / 0xffffff,
        Outline: 0xcdc7c2 / 0x52514f,
        OutlineVariant: 0xe6e3e1 / 0x3a3a3a,
        // red 3 in light. Dark takes red 1, because the darker destructive red loses too
        // much contrast against a dark window.
        Error: 0xe01b24 / 0xff7b63,
        OnError: 0xffffff / 0x2a0a06,
        // An Adwaita card in light is white with a hairline around it, and white against
        // window_bg_color is five parts of grey: the border is what you actually see. A
        // role that has to be visible on the page by itself cannot be that, so the layer
        // is sidebar_bg_color, the grey Adwaita already uses for a panel beside the view.
        SurfaceContainer: 0xebebeb / 0x303030,
        // teal 4, the third of the GNOME accent colours after blue and purple. Like the
        // other two it holds one value across both schemes, which is how libadwaita ships
        // its accents.
        Tertiary: 0x2190a4 / 0x2190a4,
        OnTertiary: 0xffffff / 0xffffff,
        // Adwaita has no container tones of its own: it tints by drawing the accent at a
        // low alpha over whatever is behind. That cannot be a role, because a role answers
        // before anything knows what is behind it, so these are that same tint resolved
        // against the window colour, one per accent.
        PrimaryContainer: 0xd4e5fb / 0x1b3c5e,
        OnPrimaryContainer: 0x0d3b70 / 0xcfe0f7,
        SecondaryContainer: 0xecd9f1 / 0x44234c,
        OnSecondaryContainer: 0x45164f / 0xecd9f1,
        TertiaryContainer: 0xcfe9ed / 0x134249,
        OnTertiaryContainer: 0x0a3d45 / 0xcfe9ed,
    },
    // libadwaita declares its title classes in points against an 11pt Cantarell body, and
    // they are heavy: the largest title is weight 800, not 700. Those point values are
    // carried over to sp here, which is why the body is 15 and not the 14 a Material scale
    // would use.
    type_scale: type_scale! {
        Display: 44.0 / 800 / 52.0 / -0.5 / false,
        Headline: 32.0 / 800 / 40.0 / -0.25 / false,
        Title: 24.0 / 700 / 32.0 / 0.0 / false,
        Subtitle: 20.0 / 700 / 28.0 / 0.0 / false,
        Body: 15.0 / 400 / 22.0 / 0.0 / false,
        BodyStrong: 15.0 / 700 / 22.0 / 0.0 / false,
        Label: 13.0 / 700 / 18.0 / 0.1 / false,
        Caption: 12.0 / 400 / 16.0 / 0.0 / false,
        Mono: 14.0 / 400 / 20.0 / 0.0 / true,
    },
    // libadwaita's own named radii: six on a button or an entry, twelve on a card or a
    // popover, fifteen on a window. The middle and top rungs were eight and twelve, which
    // put a card where a button belongs and a window where a card belongs, and the GNOME
    // 50 screen in docs/references/design-systems/gnome50/ shows the difference plainly:
    // the preferences groups are rounded well past the buttons inside them, and the
    // window is rounder again. Pills are kept for suggested actions and search entries.
    shapes: shapes! {
        None: 0.0,
        ExtraSmall: 4.0,
        Small: 6.0,
        Medium: 12.0,
        Large: 15.0,
        Full: 1000.0,
    },
    // GNOME lays out on a six pixel grid, and its dialogs are roomy.
    spaces: spaces! {
        None: 0.0,
        Xs: 3.0,
        Sm: 6.0,
        Md: 12.0,
        Lg: 18.0,
        Xl: 24.0,
        Xxl: 36.0,
    },
};

const BREEZE: DesignTokenTable = DesignTokenTable {
    system: DesignSystem::Breeze,
    reference: "KDE Breeze colour schemes and the KDE Human Interface Guidelines, Plasma 6",
    default_family: "Noto Sans",
    monospace_family: "Hack",
    colors: colors! {
        // Plasma blue, the default highlight in both schemes.
        Primary: 0x3daee9 / 0x3daee9,
        // Breeze puts white on the highlight, which measures under three to one against
        // this blue. Dark ink keeps the same blue and stays readable at small sizes.
        OnPrimary: 0x06222e / 0x06222e,
        // The Breeze "positive" teal, its second accent. Dark ink again, for the same
        // reason: white on this green is about two to one.
        Secondary: 0x16a085 / 0x1abc9c,
        OnSecondary: 0x03201b / 0x03201b,
        // View background: the white of a list or an entry.
        Surface: 0xfcfcfc / 0x1b1e20,
        OnSurface: 0x232629 / 0xfcfcfc,
        // A step deeper than the window, in Breeze's own grey.
        SurfaceVariant: 0xe1e3e5 / 0x31363b,
        OnSurfaceVariant: 0x4d5052 / 0xbdc3c7,
        // Window background, a touch cooler and darker than the view.
        Background: 0xeff0f1 / 0x232629,
        OnBackground: 0x232629 / 0xfcfcfc,
        Outline: 0xbdc3c7 / 0x4d5155,
        OutlineVariant: 0xd8dbdd / 0x3f4449,
        // Breeze "negative".
        Error: 0xda4453 / 0xed8079,
        OnError: 0xffffff / 0x2a0806,
        // A framed panel in Plasma is the view colour sitting on the window: a step
        // lighter than the window in light, and a step darker in dark, because Breeze
        // Dark's view really is darker than its window. Either way the panel separates
        // from the page, which is the whole promise of the role.
        SurfaceContainer: 0xfcfcfc / 0x1b1e20,
        // Breeze "neutral", the amber it uses for a warning state, taken here as the third
        // accent. Dark ink on it for the same reason the other two carry dark ink: white
        // on any of these Breeze fills measures under three to one.
        Tertiary: 0xf67400 / 0xf8a44c,
        OnTertiary: 0x2b1200 / 0x2b1200,
        // Plasma tints a selected region with the highlight colour at low alpha over the
        // view. Resolved against the window colour, once per accent, so the role can answer
        // without knowing what it is over.
        PrimaryContainer: 0xd3ecf9 / 0x123b4f,
        OnPrimaryContainer: 0x0b3b52 / 0xcde6f5,
        SecondaryContainer: 0xd2ece5 / 0x103a32,
        OnSecondaryContainer: 0x083b31 / 0xcfe8e0,
        TertiaryContainer: 0xfae0c4 / 0x4a3113,
        OnTertiaryContainer: 0x4a2c00 / 0xf8dfc3,
    },
    // Plasma sets its interface in Noto Sans at 10pt, a step smaller than Adwaita's 11pt
    // Cantarell, and its headings are semi bold rather than the near black weights
    // libadwaita uses. Smaller and lighter at every rung, with tighter line heights to
    // match the denser spacing.
    type_scale: type_scale! {
        Display: 34.0 / 600 / 40.0 / 0.0 / false,
        Headline: 26.0 / 600 / 32.0 / 0.0 / false,
        Title: 19.0 / 600 / 24.0 / 0.0 / false,
        Subtitle: 16.0 / 500 / 21.0 / 0.0 / false,
        Body: 13.0 / 400 / 19.0 / 0.0 / false,
        BodyStrong: 13.0 / 600 / 19.0 / 0.0 / false,
        Label: 12.0 / 500 / 16.0 / 0.2 / false,
        Caption: 11.0 / 400 / 15.0 / 0.0 / false,
        Mono: 12.0 / 400 / 17.0 / 0.0 / true,
    },
    // Breeze barely rounds. Frames and buttons are a two to four pixel radius and the
    // largest containers stop at six, which is where the language gets its drafted look.
    shapes: shapes! {
        None: 0.0,
        ExtraSmall: 2.0,
        Small: 3.0,
        Medium: 4.0,
        Large: 6.0,
        Full: 1000.0,
    },
    // Kirigami's ladder: small spacing 4, large spacing 8, grid unit 18.
    spaces: spaces! {
        None: 0.0,
        Xs: 2.0,
        Sm: 4.0,
        Md: 8.0,
        Lg: 12.0,
        Xl: 18.0,
        Xxl: 24.0,
    },
};

const DEEPIN: DesignTokenTable = DesignTokenTable {
    system: DesignSystem::Deepin,
    reference: "Deepin Design specification and the DTK control defaults, deepin 23",
    default_family: "Noto Sans",
    monospace_family: "Noto Sans Mono",
    // Neutral greys, not warm ones. The palette here was written from memory once and
    // described itself as warm, with a trace of brown through every neutral. Deepin's own
    // screens are not: its system monitor is a white list on a light grey window with a
    // plain grey search field, and its calculator is a near black window of dark grey
    // keys. Nothing in either is warm, and the warmth was the most visible thing about
    // this system, so it was the most visible thing that was wrong.
    colors: colors! {
        // The brand blue.
        Primary: 0x0081ff / 0x3ba2ff,
        OnPrimary: 0xffffff / 0x04203a,
        // The amber that sits beside the blue in Deepin's own readouts, where a second
        // series is drawn in it.
        Secondary: 0xf2a13c / 0xffb964,
        OnSecondary: 0x2b1a05 / 0x33200a,
        // The reading surface: a white list in light, a dark grey key in dark.
        Surface: 0xffffff / 0x2a2a2a,
        OnSurface: 0x202020 / 0xf0f0f0,
        // The fill of a search field, and of the alternate row in a list.
        SurfaceVariant: 0xe6e6e6 / 0x3a3a3a,
        OnSurfaceVariant: 0x5a5a5a / 0xb4b4b4,
        // The window, which is the reading surface itself in light: a Deepin window is
        // white and what separates from it is the grey well beside the content, not a
        // white card on a grey page.
        Background: 0xffffff / 0x1a1a1a,
        OnBackground: 0x202020 / 0xf0f0f0,
        Outline: 0xcdcdcd / 0x4d4d4d,
        OutlineVariant: 0xe0e0e0 / 0x333333,
        Error: 0xff5736 / 0xff8a73,
        OnError: 0xffffff / 0x34110a,
        // The panel: the grey of a sidebar sunk into the white window, and in dark the
        // key grey, which is a step lighter than the near black window. Either way the
        // panel separates from the page, which is the promise of the role.
        SurfaceContainer: 0xf1f1f1 / 0x2a2a2a,
        // The violet of the deepin palette, the one accent here that is neither the brand
        // blue nor a warm colour.
        Tertiary: 0x7a5bd6 / 0x9f8ae3,
        OnTertiary: 0xffffff / 0x1d0f45,
        // Tints of the three accents, resolved neutrally against the page colour.
        PrimaryContainer: 0xd8ecff / 0x203547,
        OnPrimaryContainer: 0x00407f / 0x9dd0ff,
        SecondaryContainer: 0xfdf0e1 / 0x473928,
        OnSecondaryContainer: 0x79501e / 0xffdcb1,
        TertiaryContainer: 0xebe6f8 / 0x343042,
        OnTertiaryContainer: 0x3d2d6b / 0xcfc4f1,
    },
    // A middle weight ladder. The body sits between Breeze's 13 and Adwaita's 15, and the
    // headings are semi bold with loose line heights, which suits the rounded shapes and
    // survives on a desktop whose default font is unknown.
    type_scale: type_scale! {
        Display: 40.0 / 600 / 50.0 / 0.0 / false,
        Headline: 30.0 / 600 / 38.0 / 0.0 / false,
        Title: 22.0 / 500 / 30.0 / 0.0 / false,
        Subtitle: 18.0 / 500 / 26.0 / 0.0 / false,
        Body: 14.0 / 400 / 21.0 / 0.0 / false,
        BodyStrong: 14.0 / 600 / 21.0 / 0.0 / false,
        Label: 13.0 / 500 / 18.0 / 0.3 / false,
        Caption: 12.0 / 400 / 17.0 / 0.0 / false,
        Mono: 13.0 / 400 / 19.0 / 0.0 / true,
    },
    // The roundest of the three Linux systems. Deepin's windows are rounded far past
    // anything GTK or Qt does, and its controls follow at a smaller radius, so even a
    // button reads as a lozenge next to a Breeze rectangle. The middle rung is ten rather
    // than twelve: the keys in the calculator screen are cut at about a sixth of their
    // height, which is where a Deepin button sits, and twelve was Adwaita's card radius
    // borrowed for a button.
    shapes: shapes! {
        None: 0.0,
        ExtraSmall: 6.0,
        Small: 8.0,
        Medium: 10.0,
        Large: 18.0,
        Full: 1000.0,
    },
    // Roomy, and on a ten pixel rhythm rather than GNOME's six or Kirigami's four.
    spaces: spaces! {
        None: 0.0,
        Xs: 4.0,
        Sm: 10.0,
        Md: 16.0,
        Lg: 20.0,
        Xl: 30.0,
        Xxl: 40.0,
    },
};

/// Apple's current language, the one macOS 26 and iOS 26 draw.
///
/// It shares a palette with `APPLE_HIG` above, because the system colours did not change:
/// what changed is what a surface is made of, how deep a corner is cut, and how far a
/// control is allowed to float. Two of those three are rules rather than tokens, so the
/// visible difference in this table is the shape ladder.
const LIQUID_GLASS: DesignTokenTable = DesignTokenTable {
    system: DesignSystem::LiquidGlass,
    reference: "Apple Human Interface Guidelines, Liquid Glass, system colors and Dynamic Type, 2026",
    default_family: "SF Pro",
    monospace_family: "SF Mono",
    colors: colors! {
        Primary: 0x007aff / 0x0a84ff,
        OnPrimary: 0xffffff / 0xffffff,
        Secondary: 0x5856d6 / 0x5e5ce6,
        OnSecondary: 0xffffff / 0xffffff,
        Surface: 0xffffff / 0x1c1c1e,
        OnSurface: 0x000000 / 0xffffff,
        // The secondary fill, which is what a glass surface tints towards when the blur
        // is unavailable and what a grouped row sits on.
        SurfaceVariant: 0xe9e9eb / 0x2c2c2e,
        OnSurfaceVariant: 0x3c3c43 / 0xebebf5,
        // The page is the document, not a grey well for it to sit in. That is the sharpest
        // difference from the flat language beside it, where a grouped page is grey and
        // the white rectangles on it are what a reader looks at: here the separation
        // comes from the material a panel is made of, so the page underneath can be the
        // reading surface itself.
        //
        // The dark page is pure black, which is right on a phone and wrong in a desktop
        // window. A window is the other value Apple specifies for this one role, and the
        // component rules choose between the two by window size. No other role is
        // specified twice, so no other role is chosen anywhere but here.
        Background: 0xffffff / 0x000000,
        OnBackground: 0x000000 / 0xffffff,
        Outline: 0xc6c6c8 / 0x38383a,
        OutlineVariant: 0xe5e5ea / 0x48484a,
        Error: 0xff3b30 / 0xff453a,
        OnError: 0xffffff / 0xffffff,
        // A panel has to lift off a page that is already white, so this is the lightest
        // system grey rather than another white.
        SurfaceContainer: 0xf2f2f7 / 0x1c1c1e,
        // Liquid Glass keeps Cupertino's accents, because it is the same platform
        // palette seen through a different material. What changes is how a surface is
        // drawn, not which purple Apple uses.
        Tertiary: 0xaf52de / 0xbf5af2,
        OnTertiary: 0xffffff / 0xffffff,
        PrimaryContainer: 0xd6e4ff / 0x0a2d52,
        OnPrimaryContainer: 0x003070 / 0xcfe3ff,
        SecondaryContainer: 0xe2e0ff / 0x262663,
        OnSecondaryContainer: 0x2a1b70 / 0xdedcff,
        TertiaryContainer: 0xf3ddfb / 0x3f1a52,
        OnTertiaryContainer: 0x3d0b52 / 0xf1d9fa,
    },
    // Same sizes as the flat language, because Dynamic Type did not move, and heavier at
    // every rung that labels something. A label sitting on a translucent surface competes
    // with whatever shows through it, and every control label in the reference screens is
    // set semibold for that reason: the buttons in an alert, the segments of a picker, the
    // section heads of a formatting sheet.
    type_scale: type_scale! {
        Display: 34.0 / 700 / 41.0 / 0.37 / false,
        Headline: 28.0 / 700 / 34.0 / 0.36 / false,
        Title: 22.0 / 700 / 28.0 / 0.35 / false,
        Subtitle: 17.0 / 600 / 22.0 / -0.41 / false,
        Body: 17.0 / 400 / 22.0 / -0.41 / false,
        BodyStrong: 17.0 / 600 / 22.0 / -0.41 / false,
        Label: 15.0 / 600 / 20.0 / -0.24 / false,
        Caption: 12.0 / 500 / 16.0 / 0.0 / false,
        Mono: 15.0 / 400 / 20.0 / 0.0 / true,
    },
    // Deeper than the flat language, and continuous rather than circular. A surface that
    // catches light along its rim needs a corner long enough for the rim to travel round
    // it; a ten dp arc pinches that highlight into a point. The Renderer draws these as a
    // superellipse, which is the other half of the same decision.
    shapes: shapes! {
        None: 0.0,
        ExtraSmall: 6.0,
        Small: 10.0,
        Medium: 16.0,
        Large: 22.0,
        Full: 1000.0,
    },
    // Roomier than the flat language at every step above the smallest. Two reasons, both
    // visible in the reference: a twenty two dp corner needs more room inside it before
    // text stops crowding the curve, and these surfaces float with a margin around them
    // instead of running to the window edge, so the margin is a spacing step as well.
    spaces: spaces! {
        None: 0.0,
        Xs: 4.0,
        Sm: 10.0,
        Md: 18.0,
        Lg: 24.0,
        Xl: 36.0,
        Xxl: 48.0,
    },
};

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::DESIGN_SYSTEM_SCHEMA;

    fn channel(value: u32) -> f64 {
        let value = f64::from(value) / 255.0;
        if value <= 0.040_45 {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).powf(2.4)
        }
    }

    fn luminance(color: Color) -> f64 {
        let argb = color.to_argb();
        0.2126 * channel((argb >> 16) & 0xff)
            + 0.7152 * channel((argb >> 8) & 0xff)
            + 0.0722 * channel(argb & 0xff)
    }

    fn contrast(first: Color, second: Color) -> f64 {
        let (high, low) = {
            let (a, b) = (luminance(first), luminance(second));
            if a > b { (a, b) } else { (b, a) }
        };
        (high + 0.05) / (low + 0.05)
    }

    /// Every `DesignSystem` variant has a table, so adding a variant without
    /// its values fails here rather than at run time.
    #[test]
    fn fr14_every_design_system_has_a_token_table() {
        assert_eq!(DESIGN_TOKENS.len(), DESIGN_SYSTEM_SCHEMA.len());
        for (index, variant) in DESIGN_SYSTEM_SCHEMA.iter().enumerate() {
            let table = DESIGN_TOKENS[index];
            assert_eq!(table.system as u16, variant.tag);
            assert!(
                !table.reference.is_empty(),
                "{} has no source",
                variant.name
            );
            assert!(!table.default_family.is_empty());
            assert!(!table.monospace_family.is_empty());
        }
    }

    /// The tables are ordered by role tag, so a Renderer can index them directly.
    #[test]
    fn fr14_tokens_are_stored_in_role_tag_order() {
        for table in DESIGN_TOKENS {
            for (index, token) in table.colors.iter().enumerate() {
                assert_eq!(token.role as usize, index + 1);
            }
            for (index, token) in table.type_scale.iter().enumerate() {
                assert_eq!(token.role as usize, index + 1);
            }
            for (index, token) in table.shapes.iter().enumerate() {
                assert_eq!(token.role as usize, index + 1);
            }
            for (index, token) in table.spaces.iter().enumerate() {
                assert_eq!(token.role as usize, index + 1);
            }
        }
    }

    /// A filled thing has to be visible on the page it sits on.
    ///
    /// Cupertino gave `SurfaceVariant` and `Background` the same value, so a chat bubble
    /// filled with the variant on a page of the background was drawn, with the right
    /// colour, and was invisible. Layering is the entire reason three roles exist.
    ///
    /// `Background` and `Surface` are deliberately not compared. Material 3 gives them the
    /// same value on purpose and expresses depth through tonal containers and elevation,
    /// so requiring those to differ would be requiring Material 3 to stop being itself.
    ///
    /// That is exactly why `SurfaceContainer` is checked here too. Material 3 having no
    /// gap between the page and the surface is not a defect, but it does mean `Surface` is
    /// not a role a panel can be made of, and until this role existed there was nothing in
    /// the vocabulary that promised to be visible on the page while holding `OnSurface`
    /// ink. A calculator readout filled with `Surface` disappeared under Material 3 and
    /// nothing here noticed, because nothing here was asked about it.
    #[test]
    fn fr14_surface_variant_is_visible_against_the_page_and_the_surface() {
        /// Summed channel distance in eight bit terms, which is enough to catch a collapse.
        fn apart(first: Color, second: Color) -> u32 {
            (0..3)
                .map(|channel| {
                    let shift = channel * 8;
                    let a = (first.0 >> shift) & 0xff;
                    let b = (second.0 >> shift) & 0xff;
                    a.abs_diff(b)
                })
                .sum()
        }

        for table in DESIGN_TOKENS {
            for scheme in [ColorScheme::Light, ColorScheme::Dark] {
                let variant = table.color(ColorRole::SurfaceVariant, scheme);
                for under in [ColorRole::Background, ColorRole::Surface] {
                    let distance = apart(variant, table.color(under, scheme));
                    assert!(
                        distance >= 24,
                        "{:?} {:?}: SurfaceVariant and {:?} are {} apart, which reads as \
                         one flat surface. Anything filled with one on a page of the other \
                         disappears.",
                        table.system,
                        scheme,
                        under,
                        distance
                    );
                }
                // A panel is raised off the page, so the layer role has to be visible
                // against the page. It is not compared against `Surface`, because in
                // Cupertino a panel on the grouped page is the plain reading surface and
                // the two carrying one value there is the correct answer, not a collapse.
                let container = table.color(ColorRole::SurfaceContainer, scheme);
                let distance = apart(container, table.color(ColorRole::Background, scheme));
                assert!(
                    distance >= 24,
                    "{:?} {:?}: SurfaceContainer and Background are {} apart, so a panel \
                     made of it is drawn and cannot be seen. This is the one role a panel \
                     can rely on, so it has to lift off the page in every system.",
                    table.system,
                    scheme,
                    distance
                );
                // The accent containers answer the same question for a tinted panel. A
                // tint nobody can see is a panel that is not there, and these are the
                // fills a screen made of coloured tiles is built out of.
                for container in [
                    ColorRole::PrimaryContainer,
                    ColorRole::SecondaryContainer,
                    ColorRole::TertiaryContainer,
                ] {
                    let distance = apart(
                        table.color(container, scheme),
                        table.color(ColorRole::Background, scheme),
                    );
                    assert!(
                        distance >= 24,
                        "{:?} {:?}: {:?} and Background are {} apart, so a tinted panel is \
                         drawn and cannot be seen.",
                        table.system,
                        scheme,
                        container,
                        distance
                    );
                }
            }
        }
    }

    /// The ladder descends from Display to Caption in every system. `Mono` sits
    /// outside the ladder, because it is a family choice and not a rung.
    #[test]
    fn fr13_type_ladder_is_monotonic() {
        for table in DESIGN_TOKENS {
            let ladder = &table.type_scale[..8];
            for pair in ladder.windows(2) {
                assert!(
                    pair[0].size >= pair[1].size,
                    "{:?}: {:?} is not larger than {:?}",
                    table.system,
                    pair[0].role,
                    pair[1].role
                );
            }
            for token in table.type_scale.iter() {
                assert!(token.size > 0.0);
                assert!((100..=900).contains(&token.weight));
                assert!(token.line_height >= token.size);
            }
            assert!(table.type_scale[TypeRole::Mono as usize - 1].monospace);
            assert!(
                table.type_scale[TypeRole::BodyStrong as usize - 1].weight
                    > table.type_scale[TypeRole::Body as usize - 1].weight
            );
        }
    }

    /// Radii and spacing grow with the role, starting at zero.
    #[test]
    fn fr13_shape_and_space_ladders_grow() {
        for table in DESIGN_TOKENS {
            assert_eq!(table.shapes[0].radius, 0.0);
            for pair in table.shapes.windows(2) {
                assert!(pair[1].radius > pair[0].radius, "{:?}", table.system);
            }
            assert_eq!(table.spaces[0].value, 0.0);
            for pair in table.spaces.windows(2) {
                assert!(pair[1].value > pair[0].value, "{:?}", table.system);
            }
        }
    }

    /// `On*` is readable on its pair in both schemes.
    ///
    /// Reading surfaces hold WCAG AA body text (4.5:1). Accent fills are held to 3:1,
    /// which is the AA large-text and non-text bound: Apple's systemBlue with white
    /// sits at about 4.0:1 and its systemRed at about 3.4:1, and those are the shipped
    /// platform colours. Tightening past that would mean inventing colours that no
    /// platform actually uses, which is worse than the looser bound.
    #[test]
    fn fr14_on_roles_stay_readable() {
        let reading = [
            (ColorRole::Surface, ColorRole::OnSurface),
            (ColorRole::SurfaceVariant, ColorRole::OnSurfaceVariant),
            (ColorRole::Background, ColorRole::OnBackground),
            // The layer role has no ink of its own: it holds the page's reading ink, and
            // that is the promise a caller relies on when filling a panel with it.
            (ColorRole::SurfaceContainer, ColorRole::OnSurface),
            // The accent containers exist so that a paragraph can land on a tinted panel,
            // not just a word, so they are held to the reading bound rather than to the
            // looser bound their accents keep.
            (ColorRole::PrimaryContainer, ColorRole::OnPrimaryContainer),
            (
                ColorRole::SecondaryContainer,
                ColorRole::OnSecondaryContainer,
            ),
            (ColorRole::TertiaryContainer, ColorRole::OnTertiaryContainer),
        ];
        let accent = [
            (ColorRole::Primary, ColorRole::OnPrimary),
            (ColorRole::Secondary, ColorRole::OnSecondary),
            (ColorRole::Tertiary, ColorRole::OnTertiary),
            (ColorRole::Error, ColorRole::OnError),
        ];
        for table in DESIGN_TOKENS {
            for scheme in [ColorScheme::Light, ColorScheme::Dark] {
                for (base, on) in reading {
                    let ratio = contrast(table.color(base, scheme), table.color(on, scheme));
                    assert!(
                        ratio >= 4.5,
                        "{:?} {scheme:?} {base:?}/{on:?} is {ratio:.2}:1",
                        table.system
                    );
                }
                for (base, on) in accent {
                    let ratio = contrast(table.color(base, scheme), table.color(on, scheme));
                    assert!(
                        ratio >= 3.0,
                        "{:?} {scheme:?} {base:?}/{on:?} is {ratio:.2}:1",
                        table.system
                    );
                }
            }
        }
    }

    /// No two design systems answer a whole ladder with the same values.
    ///
    /// A design system that agrees with another one on every colour, every type rung,
    /// every radius or every spacing step is that other system wearing a different name,
    /// and a caller who selected it would see no change at all. Checking each ladder
    /// separately is what catches a table that was copied and then edited in one place.
    #[test]
    fn fr14_no_two_design_systems_answer_a_whole_ladder_identically() {
        for (index, first) in DESIGN_TOKENS.iter().enumerate() {
            for second in &DESIGN_TOKENS[index + 1..] {
                assert_ne!(
                    first.colors, second.colors,
                    "{:?} and {:?} have the same palette",
                    first.system, second.system
                );
                assert_ne!(
                    first.type_scale, second.type_scale,
                    "{:?} and {:?} have the same type scale",
                    first.system, second.system
                );
                assert_ne!(
                    first.shapes, second.shapes,
                    "{:?} and {:?} round everything the same way",
                    first.system, second.system
                );
                assert_ne!(
                    first.spaces, second.spaces,
                    "{:?} and {:?} have the same spacing ladder",
                    first.system, second.system
                );
            }
        }
    }

    /// `FollowSystem` is the Renderer's call, so the Host-side lookup can only
    /// report the light value. This documents that, rather than leaving it a surprise.
    #[test]
    fn fr14_follow_system_reads_as_light_on_the_host() {
        let table = table(DesignSystem::Material3);
        assert_eq!(
            table.color(ColorRole::Surface, ColorScheme::FollowSystem),
            table.color(ColorRole::Surface, ColorScheme::Light)
        );
    }
}
