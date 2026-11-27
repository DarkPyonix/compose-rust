//! Buttons, controls, pictures, pickers and the small widgets that sit among them.

use super::{set, set_some};
use crate::MotionFinished;
use crate::ui::{
    AssetId, ButtonKind, ButtonVariant, ColorRole, ContentScale, DrawList, FocusHandle, IconRole,
    Modifier, Paint, TextAlign, TextOverflow, TypeRole,
};
use compose_rust_macros::Composable;

// ----- buttons ----------------------------------------------------------------------------

/// A button with a label. Compose's `Button`; the variant is the design system's seam, so
/// `ButtonVariant::Text` is Compose's `TextButton` and `Outlined` its `OutlinedButton`.
///
/// An empty label with an icon is an icon button, named for assistive technology by its
/// icon's meaning. `on_click` runs on a click and on Enter or Space.
///
/// `accessible_name` is the name assistive technology reads: with no visible label it is
/// the control's name, with one it replaces the label, and on an icon-only button it is
/// read instead of the icon's meaning. `text_align` is where the label sits in the button,
/// centred when it is not given. `font_size` (sp) and `font_weight` (400, 500, 600, 700)
/// win over the values of `type_role`, the rung of the type ladder the label is set in;
/// each one not given keeps the design system's label style for the variant.
/// `content_padding` is `(horizontal, vertical)` dp between the button's edge and its label:
/// it replaces the design system's padding for this button only and still gives way when
/// the button is too small for the label and the padding together, while
/// `Modifier::padding` stays the room around the button. `icon` is the meaning of the glyph
/// on it, never a picture. `kind` is what the button is apart from how loud it is:
/// `ButtonKind::ActionKey` is one key of a dense grid, drawn in the design system's own
/// calculator-key shape. `max_lines` is how many lines the label may take: a label is one
/// line unless this says more, so a Korean label is never broken between syllables by a
/// squeezed parent. `overflow` is what happens to a label longer than its lines. `color`
/// is the label's colour, for the rare button whose meaning is not its variant's.
/// `focus_handle` binds a handle, so `handle.request()` puts keyboard focus here.
/// `on_motion_finished` runs once when the button's size motion comes to rest, with the
/// size it settled at; it needs `Modifier::motion`. `content` is drawn inside the button.
#[Composable(widget)]
pub fn Button<'a>(
    text: impl Into<String>,
    on_click: impl FnMut() + 'static,
    modifier: Modifier,
    #[default(None::<&str>)] accessible_name: impl Into<Option<&'a str>>,
    #[default(None::<::compose_rust::ui::TextAlign>)] text_align: impl Into<Option<TextAlign>>,
    #[default(None::<f32>)] font_size: impl Into<Option<f32>>,
    #[default(None::<u16>)] font_weight: impl Into<Option<u16>>,
    #[default(None::<::compose_rust::ui::TypeRole>)] type_role: impl Into<Option<TypeRole>>,
    #[default(None::<(f32, f32)>)] content_padding: impl Into<Option<(f32, f32)>>,
    #[default(None::<::compose_rust::ui::IconRole>)] icon: impl Into<Option<IconRole>>,
    #[default(true)] enabled: bool,
    #[default(None::<::compose_rust::ui::ButtonVariant>)] variant: impl Into<Option<ButtonVariant>>,
    #[default(None::<::compose_rust::ui::ButtonKind>)] kind: impl Into<Option<ButtonKind>>,
    #[default(None::<u32>)] max_lines: impl Into<Option<u32>>,
    #[default(None::<::compose_rust::ui::TextOverflow>)] overflow: impl Into<Option<TextOverflow>>,
    #[default(None::<::compose_rust::ui::Paint>)] color: impl Into<Option<Paint>>,
    #[default(None::<&::compose_rust::ui::FocusHandle>)] focus_handle: impl Into<
        Option<&'a FocusHandle>,
    >,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::Button(text)
        .on_click(on_click)
        .modifier(modifier)
        .enabled(enabled);
    set!(
        scope;
        accessible_name,
        text_align,
        font_size,
        font_weight,
        type_role,
    );
    if let Some((horizontal, vertical)) = content_padding.into() {
        scope = scope.content_padding(horizontal, vertical);
    }
    set!(
        scope;
        icon,
        variant,
        kind,
        max_lines,
        overflow,
        color,
        focus_handle,
    );
    set_some!(scope; on_motion_finished);
    scope.content(content);
}

// ----- two-state controls -----------------------------------------------------------------

/// A two-state control: the Host owns the value, the control draws exactly what it is told
/// and reports what the user asked for through `on_change`. The parameters every one of
/// them takes, around its own.
macro_rules! toggle {
    (
        $(#[$doc:meta])*
        $name:ident($state:ident),
        params: { $($(#[$attr:meta])* $param:ident: $ty:ty,)* },
        apply: |$scope:ident| $apply:block
    ) => {
        $(#[$doc])*
        #[Composable(widget)]
        pub fn $name<'a>(
            $state: bool,
            on_change: impl FnMut(bool) + 'static,
            modifier: Modifier,
            #[default(None::<&str>)] accessible_name: impl Into<Option<&'a str>>,
            #[default(true)] enabled: bool,
            $($(#[$attr])* $param: $ty,)*
            #[default(None::<fn(::compose_rust::MotionFinished)>)]
            on_motion_finished: Option<impl FnMut(MotionFinished) + 'static>,
        ) {
            let mut $scope = crate::ui::widgets::$name($state)
                .on_change(on_change)
                .modifier(modifier)
                .enabled(enabled);
            set!($scope; accessible_name);
            $apply;
            set_some!($scope; on_motion_finished);
            drop($scope);
        }
    };
}

toggle! {
    /// An on and off control. Compose's `Switch(checked, onCheckedChange)`.
    ///
    /// `on_change` is called with the state the user asked for; the switch changes only
    /// when `checked` does. `accessible_name` is the name assistive technology reads.
    /// `track_color` is the track, whichever state it is in: the Host owns the state, so it
    /// passes the colour that goes with it. `thumb_color` is the thumb that travels along
    /// the track. `size` is the track's `(width, height)` in dp; the thumb scales with the
    /// height. `thumb_elevation` lifts the thumb by that many dp with a soft shadow under it
    /// that travels with it and stays inside the track; `0.0` is no shadow, and leaving it
    /// out keeps the design system's, which draws none today. `track_border` is
    /// `(width, paint)`: a line of that many dp around the track in both states, `0.0` wide
    /// for none; leaving it out keeps the design system's border. `on_motion_finished` runs
    /// once when the node's size motion comes to rest.
    Switch(checked),
    params: {
        #[default(None::<::compose_rust::ui::Paint>)] track_color: impl Into<Option<Paint>>,
        #[default(None::<::compose_rust::ui::Paint>)] thumb_color: impl Into<Option<Paint>>,
        #[default(None::<(f32, f32)>)] size: impl Into<Option<(f32, f32)>>,
        #[default(None::<f32>)] thumb_elevation: impl Into<Option<f32>>,
        #[default(None::<(f32, ::compose_rust::ui::Paint)>)]
        track_border: impl Into<Option<(f32, Paint)>>,
    },
    apply: |scope| {
        set!(scope; track_color, thumb_color);
        if let Some((width, height)) = size.into() {
            scope = scope.size(width, height);
        }
        set!(scope; thumb_elevation, track_border);
    }
}

toggle! {
    /// A box that is ticked or not. Compose's `Checkbox(checked, onCheckedChange)`.
    ///
    /// `on_change` is called with the state the user asked for; the box changes only when
    /// `checked` does. `accessible_name` is the name assistive technology reads.
    /// `container_color` is the box when it is checked or mixed, `check_color` the check
    /// mark or the dash, and `size` the length of the box's side in dp. The box's corners
    /// follow the modifier's `corner_radius`, `corner_radii` or `shape_role`.
    /// `indeterminate` makes the box mixed, whatever `checked` says: it is filled like a
    /// checked box with a dash instead of the check, assistive technology hears it as
    /// mixed, and a press asks for `true`. `outline_color` is the box's border in every
    /// state. `on_motion_finished` runs once when the node's size motion comes to rest.
    Checkbox(checked),
    params: {
        #[default(None::<::compose_rust::ui::Paint>)]
        container_color: impl Into<Option<Paint>>,
        #[default(None::<::compose_rust::ui::Paint>)] check_color: impl Into<Option<Paint>>,
        #[default(None::<f32>)] size: impl Into<Option<f32>>,
        #[default(false)] indeterminate: bool,
        #[default(None::<::compose_rust::ui::Paint>)]
        outline_color: impl Into<Option<Paint>>,
    },
    apply: |scope| {
        scope = scope.indeterminate(indeterminate);
        set!(scope; container_color, check_color, size, outline_color);
    }
}

toggle! {
    /// One choice out of several. Compose's `RadioButton(selected, onClick)`.
    ///
    /// `on_change` is called with the state the user asked for; the button changes only
    /// when `selected` does. `accessible_name` is the name assistive technology reads.
    /// `ring_color` is the ring around the button, selected or not; where the design
    /// system fills a selected button, the fill takes this colour as well. `dot_color` is
    /// the dot inside a selected button, and `size` the diameter of the ring in dp, which
    /// the dot scales with. `on_motion_finished` runs once when the node's size motion
    /// comes to rest.
    RadioButton(selected),
    params: {
        #[default(None::<::compose_rust::ui::Paint>)] ring_color: impl Into<Option<Paint>>,
        #[default(None::<::compose_rust::ui::Paint>)] dot_color: impl Into<Option<Paint>>,
        #[default(None::<f32>)] size: impl Into<Option<f32>>,
    },
    apply: |scope| {
        set!(scope; ring_color, dot_color, size);
    }
}

/// A value picked from a range. Compose's `Slider`. The drag is the Renderer's; `value`
/// seeds it and moves it when the change came from elsewhere.
///
/// `on_change` is called with the value the user dragged to. `accessible_name` is the name
/// assistive technology reads. `track_color` is the part of the track that is not filled,
/// `thumb_color` the thumb, and `active_track_color` the part of the track up to the
/// thumb. `range` is the two ends, `(min, max)`: Compose's `valueRange`. `steps` is the
/// number of stops between the ends; zero is continuous. `color` is the filled part of the
/// track and the thumb. `thumb_size` is the thumb's diameter in dp, the design system's
/// size when it is not given; the track and the area that takes a press do not change with
/// it, so a small thumb is still easy to grab. `on_motion_finished` runs once when the
/// node's size motion comes to rest.
#[Composable(widget)]
pub fn Slider<'a>(
    value: f32,
    on_change: impl FnMut(f32) + 'static,
    modifier: Modifier,
    #[default(None::<&str>)] accessible_name: impl Into<Option<&'a str>>,
    #[default(None::<::compose_rust::ui::Paint>)] track_color: impl Into<Option<Paint>>,
    #[default(None::<::compose_rust::ui::Paint>)] thumb_color: impl Into<Option<Paint>>,
    #[default(None::<::compose_rust::ui::Paint>)] active_track_color: impl Into<Option<Paint>>,
    #[default((0.0, 1.0))] range: (f32, f32),
    #[default(0)] steps: u32,
    #[default(true)] enabled: bool,
    #[default(None::<::compose_rust::ui::Paint>)] color: impl Into<Option<Paint>>,
    #[default(None::<f32>)] thumb_size: impl Into<Option<f32>>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
) {
    let (min, max) = range;
    let mut scope = crate::ui::widgets::Slider(value)
        .on_change(on_change)
        .modifier(modifier)
        .range(min, max)
        .steps(steps)
        .enabled(enabled);
    set!(
        scope;
        accessible_name,
        track_color,
        thumb_color,
        active_track_color,
        color,
        thumb_size,
    );
    set_some!(scope; on_motion_finished);
    drop(scope);
}

// ----- progress and separators ------------------------------------------------------------

/// The parameters `ProgressIndicator` and `IndeterminateProgressIndicator` share.
macro_rules! progress {
    (
        $(#[$doc:meta])*
        $name:ident($($value:ident: $value_ty:ty)?),
        determinate: $determinate:tt
    ) => {
        $(#[$doc])*
        #[Composable(widget)]
        pub fn $name(
            $($value: $value_ty,)?
            modifier: Modifier,
            #[default($determinate)] determinate: bool,
            #[default(false)] circular: bool,
            #[default(None::<::compose_rust::ui::Paint>)] color: impl Into<Option<Paint>>,
            #[default(None::<::compose_rust::ui::Paint>)] track_color: impl Into<Option<Paint>>,
            #[default(None::<fn(::compose_rust::MotionFinished)>)]
            on_motion_finished: Option<impl FnMut(MotionFinished) + 'static>,
        ) {
            let mut scope = crate::ui::widgets::$name($($value)?)
                .modifier(modifier)
                .determinate(determinate)
                .circular(circular);
            set!(scope; color, track_color);
            set_some!(scope; on_motion_finished);
            drop(scope);
        }
    };
}

progress! {
    /// How far along something is, as a fraction. Compose's `LinearProgressIndicator(progress)`.
    ///
    /// `determinate` set to `false` makes it work of no known length. `circular` draws the
    /// circular form rather than the linear one. `color` is the indicator's colour (the
    /// builder's `indicator_color` is the same thing) and `track_color` the track's.
    /// `on_motion_finished` runs once when the node's size motion comes to rest.
    ProgressIndicator(value: f32),
    determinate: true
}

progress! {
    /// Work in progress of no known length. The parameters are `ProgressIndicator`'s, with
    /// `determinate` already `false`.
    IndeterminateProgressIndicator(),
    determinate: false
}

/// Material 3's determinate linear progress indicator: the extension widget the schema
/// carries as its example. `progress` is how far along, as a fraction.
/// `on_motion_finished` runs once when the node's size motion comes to rest.
#[Composable(widget)]
pub fn LinearProgressIndicator(
    progress: f32,
    modifier: Modifier,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
) {
    let mut scope = crate::ui::widgets::LinearProgressIndicator(progress).modifier(modifier);
    set_some!(scope; on_motion_finished);
    drop(scope);
}

/// A line between two things. Compose's `HorizontalDivider`; `vertical` makes it Compose's
/// `VerticalDivider`. `on_motion_finished` runs once when the node's size motion comes to
/// rest.
#[Composable(widget)]
pub fn Divider(
    modifier: Modifier,
    #[default(false)] vertical: bool,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
) {
    let mut scope = crate::ui::widgets::Divider()
        .modifier(modifier)
        .vertical(vertical);
    set_some!(scope; on_motion_finished);
    drop(scope);
}

/// A hairline between two rows of a grouped list, in the design system's quiet edge or in
/// `color`. The same node as a `Divider` whose `modifier` has `background(color)`.
///
/// `color` is laid on `modifier` as its background, so a `modifier` given here keeps the
/// colour, and with no `color` a background the `modifier` has is kept. `vertical` and
/// `on_motion_finished` are `Divider`'s.
#[Composable(widget)]
pub fn Separator(
    color: Option<Paint>,
    modifier: Modifier,
    #[default(false)] vertical: bool,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
) {
    // The builder's own modifier carries only the colour, so the one given here takes its
    // place with the colour laid on it.
    let modifier = match color {
        Some(paint) => modifier.background(paint),
        None => modifier,
    };
    let mut scope = crate::ui::widgets::Separator(color)
        .modifier(modifier)
        .vertical(vertical);
    set_some!(scope; on_motion_finished);
    drop(scope);
}

// ----- pictures ---------------------------------------------------------------------------

/// The parameters `Image`, `Icon` and `IconAsset` share.
macro_rules! picture {
    (
        $(#[$doc:meta])*
        $name:ident(asset_id: $asset_ty:ty) = $build:path
    ) => {
        $(#[$doc])*
        ///
        /// `color` is the tint: an icon only, an image ignores it. `content_scale` is how the
        /// picture is laid into its box: an image only, an icon takes its design system's
        /// optical size instead. Without it the picture is drawn `ContentScale::Fit`, whole
        /// and in its own proportions; `ContentScale::FillBounds` stretches it to the box, as
        /// CSS `object-fit: fill` does. `on_motion_finished` runs once when the node's size
        /// motion comes to rest.
        #[Composable(widget)]
        pub fn $name(
            asset_id: $asset_ty,
            modifier: Modifier,
            #[default(None::<::compose_rust::ui::Paint>)] color: impl Into<Option<Paint>>,
            #[default(None::<::compose_rust::ui::ContentScale>)]
            content_scale: impl Into<Option<ContentScale>>,
            #[default(None::<fn(::compose_rust::MotionFinished)>)]
            on_motion_finished: Option<impl FnMut(MotionFinished) + 'static>,
        ) {
            let mut scope = $build(asset_id).modifier(modifier);
            set!(scope; color, content_scale);
            set_some!(scope; on_motion_finished);
            drop(scope);
        }
    };
}

picture! {
    /// A registered asset drawn as a picture. Compose's `Image`.
    Image(asset_id: u32) = crate::ui::widgets::Image
}

picture! {
    /// A registered icon, tinted. Compose's `Icon`. `IconAsset` draws an SVG the
    /// application ships instead.
    Icon(asset_id: u32) = crate::ui::widgets::Icon
}

picture! {
    /// An icon drawn from an SVG the application ships, registered with
    /// [`asset`](crate::asset::asset) as [`AssetKind::Svg`](crate::schema::AssetKind::Svg).
    ///
    /// The picture is drawn in the icon slot at the size an [`IconRole`] icon has in the
    /// design system, and in the icon's colour: every fill and every stroke takes the
    /// `color` given, or the colour an icon has when none is, and keeps its own alpha. A
    /// colour given as a role follows the light and the dark scheme.
    ///
    /// ```ignore
    /// static BELL: &[u8] = include_bytes!("../icons/bell.svg");
    ///
    /// IconAsset!(asset(AssetKind::Svg, BELL), color = Paint::Role(ColorRole::Primary));
    /// ```
    IconAsset(asset_id: AssetId) = crate::ui::widgets::Icon::asset
}

/// Pixels the widget vocabulary cannot produce. Compose's `Canvas`, with the drawing as a
/// value rather than a callback: drawing code cannot cross into the Renderer, and an equal
/// list costs no record.
///
/// `view_box` is `(width, height)`: the command list's coordinates are scaled from it to
/// the `Canvas`'s measured width and height. `fractional` takes the coordinates as
/// `0.0..=1.0` fractions of the measured size instead, and wins over `view_box`. Stroke
/// widths stay in dp either way. Without either, the view box the command list carries is
/// used. `on_motion_finished` runs once when the node's size motion comes to rest.
#[Composable(widget)]
pub fn Canvas(
    commands: DrawList,
    modifier: Modifier,
    #[default(None::<(f32, f32)>)] view_box: impl Into<Option<(f32, f32)>>,
    #[default(false)] fractional: bool,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
) {
    let mut scope = crate::ui::widgets::Canvas(commands).modifier(modifier);
    if let Some((width, height)) = view_box.into() {
        scope = scope.view_box(width, height);
    }
    if fractional {
        scope = scope.fractional();
    }
    set_some!(scope; on_motion_finished);
    drop(scope);
}

// ----- pickers ----------------------------------------------------------------------------

/// A date, as whole days since 1970-01-01. How it is picked is the design system's.
///
/// `on_change` is called with the day the user picked. `min` and `max` are the earliest
/// and the latest day that can be picked, unbounded when they are not given.
/// `on_motion_finished` runs once when the node's size motion comes to rest.
#[Composable(widget)]
pub fn DatePicker(
    value: i64,
    on_change: impl FnMut(i64) + 'static,
    modifier: Modifier,
    #[default(None::<i64>)] min: impl Into<Option<i64>>,
    #[default(None::<i64>)] max: impl Into<Option<i64>>,
    #[default(true)] enabled: bool,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
) {
    let mut scope = crate::ui::widgets::DatePicker(value)
        .on_change(on_change)
        .modifier(modifier)
        .enabled(enabled);
    set!(scope; min, max);
    set_some!(scope; on_motion_finished);
    drop(scope);
}

/// A time of day, as minutes since midnight.
///
/// `on_change` is called with the time the user picked. `min` and `max` are the earliest
/// and the latest minute that can be picked, the whole day when they are not given.
/// `on_motion_finished` runs once when the node's size motion comes to rest.
#[Composable(widget)]
pub fn TimePicker(
    value: u32,
    on_change: impl FnMut(u32) + 'static,
    modifier: Modifier,
    #[default(None::<u32>)] min: impl Into<Option<u32>>,
    #[default(None::<u32>)] max: impl Into<Option<u32>>,
    #[default(true)] enabled: bool,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
) {
    let mut scope = crate::ui::widgets::TimePicker(value)
        .on_change(on_change)
        .modifier(modifier)
        .enabled(enabled);
    set!(scope; min, max);
    set_some!(scope; on_motion_finished);
    drop(scope);
}

/// The parameters `Dropdown` and `Select` share.
macro_rules! dropdown {
    ($(#[$doc:meta])* $name:ident, closed: $closed:tt) => {
        $(#[$doc])*
        ///
        /// `on_change` is called with the index of the option the user picked. `closed`
        /// keeps the control as a closed single-line field that opens a popup menu on
        /// click, even on design systems whose default picker presentation is a wheel.
        /// `bare` draws the closed field without the design system's frame: no fill, line,
        /// corner clip, inner padding, minimum height or focus line, for a select that sits
        /// inside a box which already draws its own frame; a `background` or `border` given
        /// on the `modifier` is still drawn. `on_motion_finished` runs once when the node's
        /// size motion comes to rest. Each child in `content` is one option: a `MenuItem`
        /// or a `Text`, and a `Separator` between them. Where the window puts menus up as
        /// the operating system's own, the options of a field that opens them are one,
        /// with the chosen one checked; any other node among them is a protocol error.
        #[Composable(widget)]
        pub fn $name(
            selected_index: usize,
            on_change: impl FnMut(usize) + 'static,
            modifier: Modifier,
            #[default(true)] enabled: bool,
            #[default($closed)] closed: bool,
            #[default(false)] bare: bool,
            #[default(None::<fn(::compose_rust::MotionFinished)>)]
            on_motion_finished: Option<impl FnMut(MotionFinished) + 'static>,
            #[default(|| {})] content: impl FnOnce(),
        ) {
            let mut scope = crate::ui::widgets::$name(selected_index)
                .on_change(on_change)
                .modifier(modifier)
                .enabled(enabled)
                .closed(closed);
            if bare {
                scope = scope.bare();
            }
            set_some!(scope; on_motion_finished);
            scope.content(content);
        }
    };
}

dropdown! {
    /// One choice out of a list; each child is one option.
    Dropdown, closed: false
}

dropdown! {
    /// A compact closed-field selector that always rests as a single line and opens its
    /// options in a popup below the field on every design system. A `Dropdown` with
    /// `closed` already `true`.
    Select, closed: true
}

// ----- menus, chips and the rest ----------------------------------------------------------

/// A single row in a `Menu` or action list: left-aligned label, optional leading icon, and
/// optional selected state.
///
/// A `Menu` holds entries and nothing else: `MenuItem`, `Submenu`, and `Separator` between
/// them. Where the window puts menus up as the operating system's own, a menu of entries is
/// one; anything else in it is a protocol error, and a menu of arbitrary content is a
/// `Popover`.
///
/// `on_click` runs when the row is chosen. `icon` is the leading semantic icon of the row.
/// `selected` marks the row as the currently selected option, a check mark in an operating
/// system menu. `color` overrides the row's label and icon colour, for example for a
/// destructive action; an operating system menu has no colour of its own and shows the row
/// in its usual colour. `shortcut_label` is the text shown at the row's trailing edge for
/// the key that does the same thing, such as "Ctrl+S"; it is only text, and the key stays
/// the application's to handle. `on_motion_finished` runs once when the node's size motion
/// comes to rest.
#[Composable(widget)]
pub fn MenuItem<'a>(
    text: impl Into<String>,
    on_click: impl FnMut() + 'static,
    modifier: Modifier,
    #[default(None::<::compose_rust::ui::IconRole>)] icon: impl Into<Option<IconRole>>,
    #[default(false)] selected: bool,
    #[default(true)] enabled: bool,
    #[default(None::<::compose_rust::ui::Paint>)] color: impl Into<Option<Paint>>,
    #[default(None::<&str>)] shortcut_label: impl Into<Option<&'a str>>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
) {
    let mut scope = crate::ui::widgets::MenuItem(text)
        .on_click(on_click)
        .modifier(modifier)
        .selected(selected)
        .enabled(enabled);
    set!(scope; icon, color, shortcut_label);
    set_some!(scope; on_motion_finished);
    drop(scope);
}

/// An entry of a `Menu` that opens a menu of its own. Compose Desktop's nested `Menu` in a
/// menu bar.
///
/// `text` names the row, `icon` is its leading semantic icon, and a submenu that is not
/// `enabled` shows its row greyed and does not open. Each child in `content` is one entry
/// of the menu it opens, on the same terms as a `Menu`'s: `MenuItem`, `Submenu` and
/// `Separator`. Whether it is open is the Renderer's, as it is in every platform's menu.
/// `on_motion_finished` runs once when the node's size motion comes to rest.
#[Composable(widget)]
pub fn Submenu(
    text: impl Into<String>,
    modifier: Modifier,
    #[default(None::<::compose_rust::ui::IconRole>)] icon: impl Into<Option<IconRole>>,
    #[default(true)] enabled: bool,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::Submenu(text)
        .modifier(modifier)
        .enabled(enabled);
    set!(scope; icon);
    set_some!(scope; on_motion_finished);
    scope.content(content);
}

/// A small token that is chosen or filters. Compose's `FilterChip`.
///
/// `on_click` runs when the chip is pressed. `icon` is the meaning of the glyph on it.
/// `max_lines` is how many lines the label may take: a chip label is one line unless this
/// says more. `overflow` is what happens to a label longer than its lines.
/// `on_motion_finished` runs once when the node's size motion comes to rest.
#[Composable(widget)]
pub fn Chip(
    text: impl Into<String>,
    on_click: impl FnMut() + 'static,
    modifier: Modifier,
    #[default(None::<::compose_rust::ui::IconRole>)] icon: impl Into<Option<IconRole>>,
    #[default(false)] selected: bool,
    #[default(true)] enabled: bool,
    #[default(None::<u32>)] max_lines: impl Into<Option<u32>>,
    #[default(None::<::compose_rust::ui::TextOverflow>)] overflow: impl Into<Option<TextOverflow>>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
) {
    let mut scope = crate::ui::widgets::Chip(text)
        .on_click(on_click)
        .modifier(modifier)
        .selected(selected)
        .enabled(enabled);
    set!(scope; icon, max_lines, overflow);
    set_some!(scope; on_motion_finished);
    drop(scope);
}

/// The one action a screen is about. Compose's `ExtendedFloatingActionButton`; where it
/// goes is the design system's answer.
///
/// `on_click` runs when it is pressed. `icon` is the meaning of the glyph on it.
/// `on_motion_finished` runs once when the node's size motion comes to rest.
#[Composable(widget)]
pub fn FloatingAction(
    text: impl Into<String>,
    on_click: impl FnMut() + 'static,
    modifier: Modifier,
    #[default(None::<::compose_rust::ui::IconRole>)] icon: impl Into<Option<IconRole>>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
) {
    let mut scope = crate::ui::widgets::FloatingAction(text)
        .on_click(on_click)
        .modifier(modifier);
    set!(scope; icon);
    set_some!(scope; on_motion_finished);
    drop(scope);
}

/// A count, a word or a dot, on its child or on its own. Compose's `BadgedBox` and
/// `Badge`.
///
/// `value` is how many. `text` is one short word, shown where a count would be. Neither
/// makes it a dot. `color` is the role the mark is filled with, the error role when it is
/// not given. `on_motion_finished` runs once when the node's size motion comes to rest.
/// `content` is what the badge sits on.
#[Composable(widget)]
pub fn Badge<'a>(
    modifier: Modifier,
    #[default(None::<u32>)] value: impl Into<Option<u32>>,
    #[default(None::<&str>)] text: impl Into<Option<&'a str>>,
    #[default(None::<::compose_rust::ui::ColorRole>)] color: impl Into<Option<ColorRole>>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::Badge().modifier(modifier);
    set!(scope; value, text, color);
    set_some!(scope; on_motion_finished);
    scope.content(content);
}
