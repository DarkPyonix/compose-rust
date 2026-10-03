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
/// The rule the code colours of the two systems without a code editor scheme are derived
/// by. Only the tests run it: the values themselves are literals in the token tables.
#[cfg(test)]
mod code_colours;
pub mod contrast;
#[cfg(target_family = "wasm")]
#[doc(hidden)]
pub use boundary_wasm::web_start as __web_start;
#[doc(hidden)]
pub mod codegen;
pub mod design;
pub mod drawing;
mod extensions;
pub mod highlight;
pub mod input;
pub mod message;
pub mod notification;
pub mod palette;
pub mod protocol;
pub mod runtime;
pub mod schema;
pub mod spans;
pub mod theme;
pub mod tokens;
pub mod window;

pub use asset::asset;
pub use boundary::{
    Host, LaunchBuilder, MutationBatch, RendererApi, RuntimeFactory, demo_theme, demo_theme_for,
    install_renderer_api, launch_runtime, request_frame_from_worker,
};
pub use brush::{Brush, Stop, brush};
pub use design::design_system;
pub use drawing::{DrawCommand, DrawList, DrawListBuilder};
pub use input::{FileDrop, KeyEvent, RangeRequest};
pub use message::{Message, show_message};
pub use notification::{
    Notification, NotificationActivation, NotificationSender, notification_permission,
    request_notification_permission, withdraw_notification,
};
pub use palette::{Palette, PaletteViolation};
pub use runtime::{Batch, Runtime};
pub use schema::{
    Alignment, Arrangement, AssetKind, ButtonVariant, Chrome, Color, ColorRole, ColorScheme,
    DesignSystem, EventPayload, IconRole, Key, LoopMode, MaterialRole, MessageDuration, Modifier,
    MotionRole, NotificationImportance, NotificationPermission, NotificationPresentation, Paint,
    PropertyKind, SCHEMA_HASH, Selection, ShapeRole, SpaceRole, TextAlign, TextOverflow, Theme,
    TileMode, TypeRole, WidgetKind, WindowHeightClass, WindowSizeClass,
};
pub use theme::ThemeHandle;
pub use window::{NodeSize, WindowSize, node_size, window_size};

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
