#![deny(unsafe_op_in_unsafe_fn)]

pub mod boundary;
#[doc(hidden)]
pub mod codegen;
mod extensions;
pub mod protocol;
pub mod renderer;
pub mod schema;
pub mod tokens;
mod widgets;

pub use boundary::{
    Host, LaunchBuilder, MutationBatch, RendererApi, install_renderer_api, launch,
    request_frame_from_worker,
};
pub use dioxus_core::{Element, VirtualDom};
pub use dioxus_core_macro::{component, rsx};
pub use elements::*;
pub use extensions::LinearProgressIndicator;
pub use schema::{
    Alignment, Arrangement, ButtonVariant, Color, ColorRole, ColorScheme, DesignSystem,
    EventPayload, Key, LoopMode, Modifier, Paint, PropertyKind, SCHEMA_HASH, Selection, ShapeRole,
    SpaceRole, TextAlign, TextOverflow, Theme, TypeRole, WidgetKind,
};
pub use widgets::{
    Button, Card, Checkbox, Column, ComposeBox as Box, Dialog, Divider, KeyEvent, LazyColumn,
    LazyRow, Menu, ProgressIndicator, RadioButton, RangeRequest, Row, ScrollColumn, Slider, Spacer,
    Surface, Switch, Tabs, Text, TextField, Tooltip, TopAppBar,
};

pub mod prelude {
    pub use crate as dioxus_elements;
    // dioxus-core 0.7's rsx! expansion uses unqualified `Box<T>`.
    // Exporting the Compose `Box` through this glob prelude shadows it. Use
    // `dioxus_compose::Box { ... }` in RSX until upstream qualifies std::boxed::Box.
    pub use crate::{
        Alignment, Arrangement, Button, ButtonVariant, Card, Checkbox, Color, ColorRole,
        ColorScheme, Column, DesignSystem, Dialog, Divider, Element, Key, KeyEvent, LaunchBuilder,
        LazyColumn, LazyRow, LinearProgressIndicator, LoopMode, Menu, Modifier, Paint,
        ProgressIndicator, RadioButton, RangeRequest, Row, ScrollColumn, ShapeRole, Slider,
        SpaceRole, Spacer, Surface, Switch, Tabs, Text, TextAlign, TextField, TextOverflow, Theme,
        Tooltip, TopAppBar, TypeRole, component, launch, rsx,
    };
    pub use dioxus_core::{Callback, Event, EventHandler, Properties, VirtualDom};
    pub use dioxus_hooks::*;
    pub use dioxus_signals::*;
}

pub mod elements {
    #![allow(non_upper_case_globals)]

    pub type AttributeDescription = (&'static str, Option<&'static str>, bool);

    pub use crate::extensions::elements::*;

    macro_rules! element {
        // A widget with no attributes of its own, which is what a container that emits
        // only roles and children looks like. It needs no attribute descriptions, so it
        // must not import the type that describes them.
        ($module:ident, $tag:literal, []) => {
            pub mod $module {
                pub const TAG_NAME: &str = $tag;
                pub const NAME_SPACE: Option<&str> = None;
            }
        };
        ($module:ident, $tag:literal, [$($attribute:ident),* $(,)?]) => {
            pub mod $module {
                use super::AttributeDescription;

                pub const TAG_NAME: &str = $tag;
                pub const NAME_SPACE: Option<&str> = None;
                $(pub const $attribute: AttributeDescription = (stringify!($attribute), None, false);)*
            }
        };
    }

    // Layout containers carry arrangement, spacing and cross-axis alignment.
    element!(
        column,
        "Column",
        [
            fill_max_width,
            fill_max_height,
            arrangement,
            spacing,
            space_role,
            alignment
        ]
    );
    element!(
        row,
        "Row",
        [
            fill_max_width,
            fill_max_height,
            arrangement,
            spacing,
            space_role,
            alignment
        ]
    );
    element!(
        composebox,
        "Box",
        [fill_max_width, fill_max_height, item_key, alignment]
    );
    // The type role plus one attribute per override axis, so changing one axis is one
    // SetProp and the rest of the text's styling is not resent.
    element!(
        text,
        "Text",
        [
            text,
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
    element!(textfield, "TextField", [placeholder, enabled, multiline]);
    element!(button, "Button", [text, enabled, variant]);
    element!(spacer, "Spacer", [width, height]);
    element!(lazycolumn, "LazyColumn", [item_count]);
    // Widget tags 12 to 17. The toggles are controlled: `checked` is the whole of what they
    // draw, and a press comes back as a click, so the Host is the one that flips the value
    // and the Renderer can never disagree with it.
    element!(checkbox, "Checkbox", [checked, enabled]);
    element!(radiobutton, "RadioButton", [checked, enabled]);
    element!(switch, "Switch", [checked, enabled]);
    // The in-flight drag position is the Renderer's, like scroll and focus, so a drag does
    // not cross the boundary once per frame. `value` seeds it and carries outside changes.
    element!(
        slider,
        "Slider",
        [value, min_value, max_value, steps, enabled]
    );
    // `determinate` says whether `value` means anything, and `circular` picks the form. How
    // fast an indeterminate indicator travels is the design system's rule, not a property.
    element!(
        progressindicator,
        "ProgressIndicator",
        [value, determinate, circular]
    );
    // Thickness and colour come from the design system. The axis is the only choice left.
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
    element!(tabs, "Tabs", [selected_index]);
    element!(topappbar, "TopAppBar", []);
    element!(lazyrow, "LazyRow", [item_count]);
    element!(tooltip, "Tooltip", [text]);
    // Whole content plus a vertical scroll. The position stays in the Renderer.
    element!(
        scrollcolumn,
        "ScrollColumn",
        [fill_max_width, fill_max_height]
    );

    #[doc(hidden)]
    pub mod completions {
        #[allow(non_camel_case_types)]
        pub enum CompleteWithBraces {
            column {},
            row {},
            composebox {},
            text {},
            textfield {},
            button {},
            spacer {},
            lazycolumn {},
            scrollcolumn {},
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
    // A slider reports where it was dragged to. The toggles use `onclick` instead, because
    // a boolean the Host already holds needs no payload to flip.
    event!(onvaluechanged, f32);
    // A dismissal carries no value, so it reuses the empty event payload a click uses.
    event!(ondismiss, ());
}
