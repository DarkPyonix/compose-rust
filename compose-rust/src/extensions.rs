//! Compile-time widget extensions.
//!
//! An extension is a pair of source files, one Rust and one Kotlin, built into both sides.
//! It is not a runtime plugin: a deployed Renderer has no way to load a new widget.
//!
//! An application that builds a paired Renderer owns a patched copy of this registry. To add a
//! widget, update the descriptor and append its wire entries in both macros as needed. Codegen
//! consumes the resulting closed schema and regenerates `Protocol.gen.kt`. The paired Kotlin
//! source still has to render the new kind, and each authoring layer declares how it is written:
//! the Dioxus adapter's element and typed component live in its own `extensions.rs`.

/// Hash input for extension property types, which the flat enum tables cannot express.
/// Extension widget tags start at 100. Tags 1 to 29 belong to the core vocabulary, and an
/// extension that sat inside that range would collide with a core widget the next time one
/// was added. Extension property tags are append-only from 27, which is where the core
/// property tags stopped when this example was written.
pub(crate) const SCHEMA_DESCRIPTOR: &str =
    "extension=LinearProgressIndicator#100(progress:f32#27);";

macro_rules! define_widget_schema_with_extensions {
    ($define:ident; $schema:ident, $name:ident { $($base:tt)* }) => {
        $define!($schema, $name {
            $($base)*
            // Extension widget tags are append-only.
            LinearProgressIndicator = 100,
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
