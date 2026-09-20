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
    Alignment, Arrangement, AssetKind, ButtonVariant, Color, ColorRole, ColorScheme, DesignSystem,
    EventPayload, IconRole, Key, LoopMode, Modifier, Paint, PropertyKind, SCHEMA_HASH, Selection,
    ShapeRole, SpaceRole, TextAlign, TextOverflow, Theme, TypeRole, WidgetKind,
};
pub use widgets::{
    Button, Column, ComposeBox as Box, DatePicker, Dropdown, Icon, Image, KeyEvent, LazyColumn,
    RangeRequest, Row, ScrollColumn, Spacer, Text, TextField, TimePicker,
};

pub mod prelude {
    pub use crate as dioxus_elements;
    // SPEC-GAP: dioxus-core 0.7's rsx! expansion uses unqualified `Box<T>`.
    // Exporting the Compose `Box` through this glob prelude shadows it. Use
    // `dioxus_compose::Box { ... }` in RSX until upstream qualifies std::boxed::Box.
    pub use crate::{
        Alignment, Arrangement, AssetKind, Button, ButtonVariant, Color, ColorRole, ColorScheme,
        Column, DatePicker, DesignSystem, Dropdown, Element, Icon, IconRole, Image, Key, KeyEvent,
        LaunchBuilder, LazyColumn, LinearProgressIndicator, LoopMode, Modifier, Paint,
        RangeRequest, Row, ScrollColumn, ShapeRole, SpaceRole, Spacer, Text, TextAlign, TextField,
        TextOverflow, Theme, TimePicker, TypeRole, component, launch, rsx,
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
        ($module:ident, $tag:literal, [$($attribute:ident),* $(,)?]) => {
            pub mod $module {
                use super::AttributeDescription;

                pub const TAG_NAME: &str = $tag;
                pub const NAME_SPACE: Option<&str> = None;
                $(pub const $attribute: AttributeDescription = (stringify!($attribute), None, false);)*
            }
        };
    }

    // FR-13.4: layout containers carry arrangement, spacing and cross-axis alignment.
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
    // FR-13.2: the type role plus one attribute per override axis, so changing one axis
    // is one SetProp (FR-4).
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
    // The asset handle and nothing else: the bytes reached the Renderer through
    // RegisterAsset, so the per-frame record stays fixed length.
    element!(image, "Image", [asset_id]);
    element!(icon, "Icon", [asset_id, color]);
    // A picker carries a value, a range and a change handler. There is deliberately no
    // attribute for how the user picks: a calendar grid, a dial and a wheel are the same
    // widget here, and which one appears is the design system's decision.
    element!(
        datepicker,
        "DatePicker",
        [value, min_value, max_value, enabled]
    );
    element!(
        timepicker,
        "TimePicker",
        [value, min_value, max_value, enabled]
    );
    element!(dropdown, "Dropdown", [value, enabled]);
    // FR-13.6: whole content plus a vertical scroll. The position stays in the Renderer.
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
            image {},
            icon {},
            datepicker {},
            timepicker {},
            dropdown {},
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
    event!(onpick, i64);
}
