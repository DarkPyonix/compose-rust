//! Packages a built compose-rust application for Linux: an AppImage that updates itself
//! and a Flatpak manifest for Flathub, both described from the application's
//! `Dioxus.toml`.
//!
//! The pieces are plain functions from metadata to file contents, so they are tested
//! without building anything. `main.rs` reads files and writes the results; the shell
//! scripts beside this crate fetch the AppImage tools and run them.

pub mod appimage;
pub mod appstream;
pub mod desktop;
pub mod flatpak;
pub mod icons;
pub mod metadata;

pub use metadata::{AppMetadata, BuildFacts, MetadataError};
