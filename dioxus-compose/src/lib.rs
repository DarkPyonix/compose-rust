#![deny(unsafe_op_in_unsafe_fn)]

pub mod asset;
pub mod boundary;
/// Generated JNI shims. Compiled only for Android, where the Host is a cdylib that the
/// Kotlin Activity loads.
#[cfg(target_os = "android")]
#[path = "boundary_jni.gen.rs"]
mod boundary_jni;
/// Generated wasm shims. Compiled only for the browser, where the page owns the loop and
/// the Renderer's module owns the one linear memory both halves read.
#[cfg(target_family = "wasm")]
#[path = "boundary_wasm.gen.rs"]
mod boundary_wasm;
pub mod brush;
#[cfg(target_family = "wasm")]
#[doc(hidden)]
pub use boundary_wasm::web_start as __web_start;
#[doc(hidden)]
pub mod codegen;
pub mod design;
pub mod drawing;
mod extensions;
pub mod message;
pub mod protocol;
pub mod renderer;
pub mod schema;
pub mod spans;
pub mod tokens;
mod widgets;
pub mod window;

pub use asset::asset;
pub use boundary::{
    Host, LaunchBuilder, MutationBatch, RendererApi, demo_theme, demo_theme_for,
    install_renderer_api, launch, request_frame_from_worker,
};
pub use dioxus_core::{Element, VirtualDom};
// `Props` goes out with `component` because `#[component]` expands into a
// `#[derive(Props)]`. Without it an application that writes a component of its own fails
// to compile on a macro it never typed, and the fix is to add `dioxus-core-macro` as a
// second dependency, which defeats the promise that one dependency is enough.
pub use brush::{Brush, Stop, brush};
pub use design::{design_system, use_design_system};
pub use dioxus_core_macro::{Props, component, rsx};
pub use drawing::{DrawCommand, DrawList, DrawListBuilder};
pub use elements::*;
pub use extensions::LinearProgressIndicator;
pub use message::{Message, show_message};
pub use schema::{
    Alignment, Arrangement, AssetKind, ButtonVariant, Chrome, Color, ColorRole, ColorScheme,
    DesignSystem, EventPayload, IconRole, Key, LoopMode, MaterialRole, MessageDuration, Modifier,
    MotionRole, Paint, PropertyKind, SCHEMA_HASH, Selection, ShapeRole, SpaceRole, TextAlign,
    TextOverflow, Theme, TileMode, TypeRole, WidgetKind, WindowHeightClass, WindowSizeClass,
};
pub use widgets::{
    Badge, Button, Canvas, Card, Checkbox, Chip, Column, ComposeBox as Box, DatePicker, Dialog,
    Divider, Dropdown, FileDrop, FileDropTarget, FloatingAction, Icon, Image, KeyEvent, LazyColumn,
    LazyGrid, LazyRow, Menu, Navigation, NavigationItem, ProgressIndicator, RadioButton,
    RangeRequest, Row, Scaffold, ScrollColumn, ScrollRow, SelectionContainer, Separator, Sheet,
    Slider, Spacer, Surface, Switch, Tabs, Text, TextField, TimePicker, Tooltip, TopAppBar,
};
pub use window::{NodeSize, WindowSize, node_size, use_node_size, use_window_size, window_size};

/// Declares the Android entry point for an application's cdylib.
///
/// Android has no `main`: the Kotlin Activity owns the process and the frame loop, so the
/// root component is registered from `JNI_OnLoad`, which the generated shims reach through
/// the symbol this macro defines.
///
/// ```ignore
/// compose_rust::android_main!(app);
/// ```
#[macro_export]
macro_rules! android_main {
    ($app:path) => {
        $crate::android_main!($crate::LaunchBuilder::new(), $app);
    };
    ($builder:expr, $app:path) => {
        #[unsafe(no_mangle)]
        pub extern "C" fn dioxus_compose_android_main() {
            $builder.with_mode($crate::LoopMode::Platform).launch($app);
        }
    };
}

/// Declares the entry point an iOS application starts at.
///
/// iOS is the one platform where the application is the library: the renderer is a
/// Kotlin/Native archive and the two are linked into a single executable, so there is no
/// Activity to load anything and no page to fetch anything. What there is instead is a
/// `main`, and a `main` in an application bundle has to be C, so this exports the launch
/// under a name that C can call.
///
/// ```ignore
/// compose_rust::ios_main!(launch);
/// ```
#[macro_export]
macro_rules! ios_main {
    ($launch:path) => {
        #[unsafe(no_mangle)]
        pub extern "C" fn dioxus_compose_ios_main() -> i32 {
            $launch();
            0
        }
    };
}

/// Declares the browser entry point for an application's wasm module.
///
/// A page has no library loader and no `main` of its own to run: the Renderer's module
/// owns the loop and calls this once both wasm modules exist, and it answers with the
/// address of the block the Host lends the Renderer.
///
/// The export is here rather than in this crate because a wasm module cannot be linked
/// with an undefined symbol the way an ELF shared library can. Android's cdylib imports
/// `dioxus_compose_android_main` from the application and the dynamic linker resolves it
/// at load time; a browser refuses to instantiate a module whose imports are not all
/// supplied, so the entry point is defined where the root component is.
///
/// ```ignore
/// compose_rust::web_main!(app);
/// ```
#[macro_export]
macro_rules! web_main {
    ($app:path) => {
        $crate::web_main!($crate::LaunchBuilder::new(), $app);
    };
    ($builder:expr, $app:path) => {
        #[cfg(target_family = "wasm")]
        #[unsafe(no_mangle)]
        pub extern "C" fn dioxus_compose_host_web_start() -> u32 {
            $crate::__web_start($builder, $app)
        }

        /// Off the web there is no page to call this and no shared memory to report an
        /// address in, but it stays defined so that a build for the machine you are
        /// working on still compiles the component rather than leaving it unreferenced.
        #[cfg(not(target_family = "wasm"))]
        #[unsafe(no_mangle)]
        pub extern "C" fn dioxus_compose_host_web_start() -> u32 {
            let _: fn() -> $crate::Element = $app;
            let _ = $builder;
            0
        }
    };
}

pub mod prelude {
    pub use crate as dioxus_elements;
    // dioxus-core 0.7's rsx! expansion uses unqualified `Box<T>`.
    // Exporting the Compose `Box` through this glob prelude shadows it. Use
    // `compose_rust::Box { ... }` in RSX until upstream qualifies std::boxed::Box.
    pub use crate::{
        Alignment, Arrangement, AssetKind, Badge, Brush, Button, ButtonVariant, Canvas, Card,
        Checkbox, Chip, Color, ColorRole, ColorScheme, Column, DatePicker, DesignSystem, Dialog,
        Divider, DrawCommand, DrawList, Dropdown, Element, FileDrop, FileDropTarget,
        FloatingAction, Icon, IconRole, Image, Key, KeyEvent, LaunchBuilder, LazyColumn, LazyGrid,
        LazyRow, LinearProgressIndicator, LoopMode, MaterialRole, Menu, Message, MessageDuration,
        Modifier, MotionRole, Navigation, NavigationItem, Paint, ProgressIndicator, Props,
        RadioButton, RangeRequest, Row, Scaffold, ScrollColumn, ScrollRow, SelectionContainer,
        Separator, ShapeRole, Sheet, Slider, SpaceRole, Spacer, Stop, Surface, Switch, Tabs, Text,
        TextAlign, TextField, TextOverflow, Theme, TileMode, TimePicker, Tooltip, TopAppBar,
        TypeRole, WindowHeightClass, WindowSize, WindowSizeClass, asset, brush, component, launch,
        rsx, show_message, use_design_system, use_node_size, use_window_size,
    };
    // Under its own name, and the one thing in this list that could shadow something a
    // reader already has: an application that draws its own `Window` component would find
    // this one instead. It is here because the alternative is a fully qualified path in
    // every `main`, and because `Chrome` beside it is meaningless on its own.
    pub use crate::schema::{Chrome, Window};
    // The crates `rsx!` expands into references to, under the names it expands into. A
    // consumer who added only `dioxus-compose` does not have `dioxus_core` or
    // `dioxus_signals` in their dependency graph by name, so without these the macro
    // fails to resolve them and the crate cannot be used at all with one dependency,
    // which is the whole promise.
    pub use dioxus_core;
    pub use dioxus_signals;

    // `use_hook` is how a component starts something once and keeps it: a worker thread,
    // a connection, a subscription. Domain work runs on worker threads, so a consumer who
    // added only this crate needs it by name.
    pub use dioxus_core::{Callback, Event, EventHandler, Properties, VirtualDom, use_hook};
    pub use dioxus_hooks::*;
    pub use dioxus_signals::*;
}

pub mod elements {
    #![allow(non_upper_case_globals)]

    pub type AttributeDescription = (&'static str, Option<&'static str>, bool);

    pub use crate::extensions::elements::*;

    /// The Modifier attributes every widget accepts.
    ///
    /// In Compose a Modifier applies to any composable, and the same has to be true here
    /// or an application cannot give a widget padding, a background, a corner or a border.
    /// Without them a sample can only place bare text and buttons against the window edge,
    /// which is what these samples looked like before this existed.
    ///
    /// They are declared once, on every widget, rather than listed per element, because
    /// the set that applies to a Card and to a Text is the same set.
    macro_rules! modifier_attributes {
        () => {
            pub const weight: AttributeDescription = ("weight", None, false);
            pub const fill_max_width: AttributeDescription = ("fill_max_width", None, false);
            pub const fill_max_height: AttributeDescription = ("fill_max_height", None, false);
            pub const width: AttributeDescription = ("width", None, false);
            pub const height: AttributeDescription = ("height", None, false);
            pub const padding: AttributeDescription = ("padding", None, false);
            pub const padding_role: AttributeDescription = ("padding_role", None, false);
            pub const background: AttributeDescription = ("background", None, false);
            pub const shape_role: AttributeDescription = ("shape_role", None, false);
            pub const corner_radius: AttributeDescription = ("corner_radius", None, false);
            pub const border_width: AttributeDescription = ("border_width", None, false);
            pub const border_color: AttributeDescription = ("border_color", None, false);
            pub const elevation: AttributeDescription = ("elevation", None, false);
            pub const onclickable: AttributeDescription = ("onclickable", None, false);
            pub const observe_size: AttributeDescription = ("observe_size", None, false);
            // How important this node's changes are. Every widget takes it, because
            // anything that appears, moves or resizes has changes to run.
            pub const motion: AttributeDescription = ("motion", None, false);
            // What this node's surface is made of, where it has one.
            pub const material: AttributeDescription = ("material", None, false);
            // Willingness to have files dropped, said by having somewhere to report them.
            // A node without a handler is never told files are over it, which is what
            // keeps a screen from lighting up every container it has.
            pub const onfilesentered: AttributeDescription = ("onfilesentered", None, false);
            pub const onfilesdropped: AttributeDescription = ("onfilesdropped", None, false);
        };
    }

    macro_rules! element {
        // A widget with no attributes of its own still takes Modifiers, so this arm is the
        // same as the one below with an empty list rather than a smaller module.
        ($module:ident, $tag:literal, []) => {
            pub mod $module {
                use super::AttributeDescription;

                pub const TAG_NAME: &str = $tag;
                pub const NAME_SPACE: Option<&str> = None;
                modifier_attributes!();
            }
        };
        ($module:ident, $tag:literal, [$($attribute:ident),* $(,)?]) => {
            pub mod $module {
                use super::AttributeDescription;

                pub const TAG_NAME: &str = $tag;
                pub const NAME_SPACE: Option<&str> = None;
                $(pub const $attribute: AttributeDescription = (stringify!($attribute), None, false);)*
                modifier_attributes!();
            }
        };
    }

    // Layout containers carry arrangement, spacing and cross-axis alignment.
    element!(
        column,
        "Column",
        [arrangement, spacing, space_role, alignment]
    );
    element!(row, "Row", [arrangement, spacing, space_role, alignment]);
    element!(composebox, "Box", [item_key, alignment]);
    // The type role plus one attribute per override axis, so changing one axis is one
    // SetProp and the rest of the text's styling is not resent.
    element!(
        text,
        "Text",
        [
            text,
            spans,
            type_role,
            font_size,
            font_weight,
            line_height,
            letter_spacing,
            color,
            text_align,
            max_lines,
            overflow
        ]
    );
    element!(
        textfield,
        "TextField",
        [placeholder, enabled, multiline, type_role]
    );
    // `icon` is the meaning of the glyph on it and never a picture. A button is the
    // second place a role icon reaches the tree, because a toolbar is a row of icon
    // buttons and nothing else in the vocabulary can place one.
    element!(button, "Button", [text, icon, enabled, variant, color]);
    // Spacer has no attributes of its own: its size comes from the Modifier attributes
    // every widget carries, which is also how a Compose Spacer is sized.
    element!(spacer, "Spacer", []);
    element!(lazycolumn, "LazyColumn", [item_count]);
    // Widget tags 10 and 11. An asset id is the whole of what they carry: the bytes were
    // copied into the Renderer's cache once, at registration.
    element!(image, "Image", [asset]);
    // An Icon takes a tint as well, through the same Paint attribute Text uses.
    element!(icon, "Icon", [asset, color]);
    // Widget tags 12 to 17. A toggle is controlled: `checked` is the whole of what it
    // draws, so the box on screen and the value the Host holds can never disagree.
    element!(checkbox, "Checkbox", [checked, enabled]);
    element!(radiobutton, "RadioButton", [checked, enabled]);
    element!(switch, "Switch", [checked, enabled]);
    // The position a drag is passing through is the Renderer's, like scroll and focus, so
    // following a finger costs no boundary call. `value` seeds it and carries a change
    // that came from somewhere else.
    element!(slider, "Slider", [value, min, max, steps, enabled, color]);
    // `determinate` says whether `value` means anything and `circular` picks the form. How
    // fast an indeterminate indicator travels is motion, and motion is the design system's.
    element!(
        progressindicator,
        "ProgressIndicator",
        [value, determinate, circular]
    );
    // The thickness, the colour and the inset come from the design system. The axis is the
    // only decision left to make.
    element!(divider, "Divider", [vertical]);
    // Widget tags 18 to 25. Each one emits roles and children only: how a card, a bar or a
    // popup is drawn belongs to the design system, not to the Host that declared it.
    element!(card, "Card", []);
    element!(surface, "Surface", []);
    // `open` seeds the Renderer's own open state rather than driving it frame by frame: the
    // Renderer owns the enter and exit so neither crosses the boundary.
    element!(dialog, "Dialog", [open]);
    element!(menu, "Menu", [open]);
    // The selection is the Renderer's too. `selected_index` seeds it and moves it when the
    // change came from outside the Renderer.
    element!(tabs, "Tabs", [selected_index, color]);
    element!(topappbar, "TopAppBar", [text]);
    element!(lazyrow, "LazyRow", [item_count]);
    element!(tooltip, "Tooltip", [text]);
    // The command list is a byte blob, so it is one attribute and one SetProp. An
    // unchanged list compares equal and produces no mutation at all.
    element!(canvas, "Canvas", [commands]);
    // Widget tags 27 to 29. A picker carries a value, a range and a change event, and
    // nothing that says how the user should pick: a calendar grid, a dial, a wheel or a
    // flyout is the design system's decision.
    element!(datepicker, "DatePicker", [value, min, max, enabled]);
    element!(timepicker, "TimePicker", [value, min, max, enabled]);
    element!(dropdown, "Dropdown", [selected_index, enabled]);
    // Whole content plus a vertical scroll. The position stays in the Renderer.
    element!(scrollcolumn, "ScrollColumn", []);
    // Widget tags 30 to 32. `Navigation` carries the selection and nothing about whether
    // it is a bar, a rail or a drawer: the Renderer has measured the window and chooses.
    // The destinations are the `NavigationItem` children and the rest is the screen.
    element!(navigation, "Navigation", [selected_index]);
    // A destination's label and icon are properties, not a child tree. A child tree would
    // fix the arrangement, and the three presentations exist because it differs: a bar
    // stacks the label under the icon, a drawer sets it beside.
    element!(
        navigationitem,
        "NavigationItem",
        [text, icon, color, enabled, section]
    );
    // Like a Dialog on the wire: `open` seeds the Renderer's own state and `on_dismiss`
    // says once that the user asked to close it. Which edge it enters from is the
    // Renderer's, because it is the side that knows how wide the window is.
    element!(sheet, "Sheet", [open]);
    // The screen's frame. It carries nothing of its own: what it is made of arrives as
    // slots, and what each slot becomes is decided where the window's width is known.
    element!(scaffold, "Scaffold", []);
    element!(scaffoldslot, "ScaffoldSlot", [slot]);
    // A grid whose window is the list's, unchanged. The two ways of saying how wide a column is
    // are separate attributes because they are separate questions: a count the screen
    // insists on, or a width below which the Renderer drops one.
    element!(
        lazygrid,
        "LazyGrid",
        [item_count, columns, min_column_width]
    );
    element!(filedroptarget, "FileDropTarget", [alignment]);
    // Whole content plus a horizontal scroll, the same contract as the vertical one: every
    // child is sent and the position stays in the Renderer.
    element!(scrollrow, "ScrollRow", []);
    // Widget tags 38 and 39. A chip is a label, an optional icon and whether it is chosen,
    // on the same boolean the toggles use: a chosen chip and a ticked box are one fact.
    // The chosen state is the Host's, so the chip draws exactly what `checked` says.
    element!(chip, "Chip", [text, icon, checked, enabled]);
    // The one action a screen is about: what it means, what it is called, and a click.
    // Nothing that could say where it goes, because that is the design system's answer.
    element!(floatingaction, "FloatingAction", [text, icon]);
    // A count, a word, or with neither a dot. The colour is a role. Where it sits on its
    // child and how a large count is written are the design system's, so there is nothing
    // here that could ask for a corner or a ceiling.
    element!(badge, "Badge", [count, text, color]);
    // Nothing of its own. Being this widget is the whole of what it says: the text inside
    // may be selected and copied, and the selection never crosses the boundary.
    element!(selectioncontainer, "SelectionContainer", []);

    #[doc(hidden)]
    pub mod completions {
        #[allow(non_camel_case_types)]
        pub enum CompleteWithBraces {
            column {},
            scaffold {},
            lazygrid {},
            scaffoldslot {},
            row {},
            composebox {},
            text {},
            textfield {},
            button {},
            spacer {},
            lazycolumn {},
            scrollcolumn {},
            scrollrow {},
            checkbox {},
            radiobutton {},
            switch {},
            slider {},
            progressindicator {},
            divider {},
            card {},
            surface {},
            dialog {},
            menu {},
            tabs {},
            topappbar {},
            lazyrow {},
            tooltip {},
            canvas {},
            image {},
            icon {},
            datepicker {},
            timepicker {},
            dropdown {},
            navigation {},
            navigationitem {},
            sheet {},
            chip {},
            floatingaction {},
            badge {},
            selectioncontainer {},
        }
    }
}

pub mod events {
    use dioxus_core::{Attribute, Event, ListenerCallback, SpawnIfAsync, SuperInto};

    macro_rules! event {
        ($name:ident, $data:ty) => {
            pub fn $name<Marker>(
                handler: impl SuperInto<ListenerCallback<$data>, Marker>,
            ) -> Attribute {
                Attribute::new(stringify!($name), handler.super_into(), None, false)
            }

            pub mod $name {
                use super::*;

                pub fn call_with_explicit_closure<Marker, Return>(
                    handler: impl FnMut(Event<$data>) -> Return + 'static,
                ) -> Attribute
                where
                    Return: SpawnIfAsync<Marker> + 'static,
                {
                    Attribute::new(
                        stringify!($name),
                        ListenerCallback::new(handler),
                        None,
                        false,
                    )
                }
            }
        };
    }

    event!(onclick, ());
    event!(onvaluechange, String);
    event!(onsubmit, String);
    event!(onfocuslost, ());
    event!(onkeydown, crate::KeyEvent);
    event!(onrangerequest, crate::RangeRequest);
    // A dismissal carries no value, so it reuses the empty event payload a click uses.
    event!(ondismiss, ());
    // Files over a node, and files let go on it. The first carries nothing: the platforms
    // disagree about what is knowable before a drop, and a node that only lights up does
    // not need to know.
    event!(onfilesentered, ());
    event!(onfilesdropped, crate::FileDrop);
    // A control reports the value the user landed on, as one f64. A picker reads it as
    // the epoch count it speaks, a slider as a position, a toggle as off or on. It shares
    // the wire property with the text field's value change, because both are "this
    // control's value is now this".
    event!(onchange, f64);
}
