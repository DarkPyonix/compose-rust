//! The Dioxus side of the compile-time widget extensions.
//!
//! The wire half of an extension (its tags, its property types, the descriptor the schema
//! hash covers) is registered in compose-rust's `extensions.rs`. What is here is how a
//! Dioxus screen writes it: the element `rsx!` resolves the tag through, and the typed
//! component that wraps it. Add both when the registry gains a widget.

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

/// Material 3's determinate linear progress indicator, as a worked extension example.
#[component]
pub fn LinearProgressIndicator(progress: f32) -> Element {
    rsx! { linearprogressindicator { progress } }
}
