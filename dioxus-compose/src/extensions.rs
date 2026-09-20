//! FR-11 compile-time widget extensions.
//!
//! An application that builds a paired Renderer owns a patched copy of this registry. To add a
//! widget, update the descriptor, append its wire entries in both macros as needed, then declare
//! its Dioxus element and typed component here. Codegen consumes the resulting closed schema and
//! regenerates `Protocol.gen.kt`. The paired Kotlin source still has to render the new kind.

/// Hash input for extension property types, which the flat enum tables cannot express.
pub(crate) const SCHEMA_DESCRIPTOR: &str = "extension=LinearProgressIndicator#30(progress:f32#27);";

macro_rules! define_widget_schema_with_extensions {
    ($define:ident; $schema:ident, $name:ident { $($base:tt)* }) => {
        $define!($schema, $name {
            $($base)*
            // Extension widget tags are append-only, and they start above the core
            // vocabulary so an extension can never shadow a widget the core adds later.
            LinearProgressIndicator = 30,
        });
    };
}

macro_rules! define_property_schema_with_extensions {
    ($define:ident; $schema:ident, $name:ident { $($base:tt)* }) => {
        $define!($schema, $name {
            $($base)*
            // Extension property tags are append-only.
            Progress = 27,
        });
    };
}

pub(crate) use define_property_schema_with_extensions;
pub(crate) use define_widget_schema_with_extensions;

use crate as dioxus_elements;
use dioxus_core::Element;
use dioxus_core_macro::{Props, component, rsx};

pub mod elements {
    #![allow(non_upper_case_globals)]

    use crate::elements::AttributeDescription;

    pub mod linearprogressindicator {
        use super::AttributeDescription;

        pub const TAG_NAME: &str = "LinearProgressIndicator";
        pub const NAME_SPACE: Option<&str> = None;
        pub const progress: AttributeDescription = ("progress", None, false);
    }
}

/// Material 3's determinate linear progress indicator, exposed as an FR-11 extension example.
#[component]
pub fn LinearProgressIndicator(progress: f32) -> Element {
    rsx! { linearprogressindicator { progress } }
}
