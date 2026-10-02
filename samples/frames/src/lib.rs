//! Recording a sample's screen as the bytes a Renderer would decode.
//!
//! A batch that encodes is not the same as a screen someone can read. A panel filled with
//! a colour that matches the page behind it is drawn full size, in the right colour, and
//! cannot be seen, and every assertion about the batch passes the whole time. The only
//! thing that settles it is looking, so each sample records its screen here and the
//! Renderer's screenshot test turns the recordings into pictures.
//!
//! Three widths, because the samples change shape with the window: a layout that is right
//! on a phone and broken on a desktop is a layout nobody has checked until both have been
//! looked at. The width is part of the file name, so the Renderer draws each recording in
//! a window of the size the Host was told about rather than guessing one.

use dioxus_compose::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
use dioxus_compose::schema::{
    ColorScheme, DesignSystem, EventPayload, PropertyKind, Theme, WidgetKind, WindowHeightClass,
    WindowSizeClass,
};
use dioxus_compose::{Element, Host};
use std::path::{Path, PathBuf};

/// A window the Host is told about and the Renderer draws into.
///
/// The heights are equal so the three pictures of one screen differ only in the thing
/// being examined. The widths sit inside their class rather than on its boundary: a page
/// that stops at the width its class starts at would have no margin left in a window of
/// exactly that width, and the margin is half of what these pictures are for.
#[derive(Clone, Copy, Debug)]
pub struct Viewport {
    pub name: &'static str,
    pub width_dp: f32,
    pub height_dp: f32,
}

pub const VIEWPORTS: [Viewport; 3] = [
    Viewport {
        name: "compact",
        width_dp: 420.0,
        height_dp: 780.0,
    },
    Viewport {
        name: "medium",
        width_dp: 760.0,
        height_dp: 780.0,
    },
    Viewport {
        name: "expanded",
        width_dp: 1180.0,
        height_dp: 780.0,
    },
];

/// Every design system, in the order the schema declares them.
pub const SYSTEMS: [DesignSystem; 7] = [
    DesignSystem::Material3,
    DesignSystem::Cupertino,
    DesignSystem::Fluent,
    DesignSystem::Gnome,
    DesignSystem::Breeze,
    DesignSystem::Deepin,
    DesignSystem::LiquidGlass,
];

pub const SCHEMES: [ColorScheme; 2] = [ColorScheme::Light, ColorScheme::Dark];

/// One sample's screen, part way through being built.
///
/// It holds the batches in the order the Renderer would have applied them, because a
/// screen is rarely one batch: a list holds no rows until something asks for a window, and
/// a window size arrives as an event rather than as part of the first frame.
pub struct Screen {
    host: Host,
    frames: Vec<Vec<u8>>,
    event: Vec<u8>,
    viewport: Viewport,
}

impl Screen {
    /// The window this screen was laid out for, for a sample that wants to drive it
    /// differently depending on the shape.
    pub fn viewport(&self) -> Viewport {
        self.viewport
    }

    /// Sends one event and keeps the batch it produced.
    pub fn dispatch(&mut self, event: HostEvent<'_>) -> i64 {
        encode_event(&event, &mut self.event).expect("the event did not encode");
        let (batch, result) = self
            .host
            .dispatch_event(&self.event)
            .expect("the event failed");
        if !batch.is_empty() {
            self.frames.push(batch.to_vec());
        }
        result
    }

    /// Everything the screen has said so far, decoded.
    pub fn mutations(&self) -> Vec<Mutation<'_>> {
        self.frames
            .iter()
            .flat_map(|frame| decode_batch(frame).expect("a recorded batch did not decode"))
            .collect()
    }

    /// The node a widget of this kind was created as, if the screen has exactly one.
    pub fn node_of(&self, widget: WidgetKind) -> Option<u32> {
        self.mutations().iter().find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: found,
            } if *found == widget => Some(*node_id),
            _ => None,
        })
    }

    /// The handler a node declared for a property, if it declared one.
    pub fn handler_of(&self, node: u32, property: PropertyKind) -> Option<u64> {
        self.mutations().iter().find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: found,
                value: PropertyValue::Integer(id),
            } if *node_id == node && *found == property => Some(*id as u64),
            _ => None,
        })
    }

    /// Answers every windowing list's range request, which is what a real Renderer does
    /// before the first pixel. Without it a `LazyColumn` is an empty box.
    ///
    /// Returns how many lists were filled, so a caller can assert its list exists rather
    /// than photographing an empty one and wondering.
    pub fn fill_lists(&mut self, count: u32) -> usize {
        let lists: Vec<(u32, u64)> = {
            let mutations = self.mutations();
            let nodes: Vec<u32> = mutations
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::Create {
                        node_id,
                        widget: WidgetKind::LazyColumn | WidgetKind::LazyRow,
                    } => Some(*node_id),
                    _ => None,
                })
                .collect();
            nodes
                .iter()
                .filter_map(|node| {
                    mutations.iter().find_map(|mutation| match mutation {
                        Mutation::SetProp {
                            node_id,
                            property: PropertyKind::OnRangeRequested,
                            value: PropertyValue::Integer(id),
                        } if node_id == node => Some((*node, *id as u64)),
                        _ => None,
                    })
                })
                .collect()
        };
        for (node_id, handler_id) in &lists {
            self.dispatch(HostEvent {
                node_id: *node_id,
                handler_id: *handler_id,
                payload: EventPayload::RangeRequested { start: 0, count },
            });
        }
        lists.len()
    }

    pub fn press_icon(&mut self, icon: dioxus_compose::schema::IconRole) -> bool {
        let found = {
            let mutations = self.mutations();
            let node = mutations.iter().find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Icon,
                    value: PropertyValue::Integer(val),
                } if *val == icon as i64 => Some(*node_id),
                _ => None,
            });
            node.and_then(|node| {
                mutations.iter().find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::OnClick,
                        value: PropertyValue::Integer(id),
                    } if *node_id == node => Some((node, *id as u64)),
                    _ => None,
                })
            })
        };
        let Some((node_id, handler_id)) = found else {
            return false;
        };
        let mut bytes = Vec::new();
        encode_event(
            &HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::Clicked,
            },
            &mut bytes,
        )
        .expect("the click did not encode");
        let (batch, _) = self.host.dispatch_event(&bytes).expect("the click failed");
        if !batch.is_empty() {
            self.frames.push(batch.to_vec());
        }
        true
    }

    /// Finds what says `label` and presses it.
    pub fn press(&mut self, label: &str) -> bool {
        let found = {
            let mutations = self.mutations();
            let node = mutations.iter().find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Text,
                    value: PropertyValue::String(text),
                } if *text == label => Some(*node_id),
                _ => None,
            });
            node.and_then(|node| {
                mutations.iter().find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::OnClick,
                        value: PropertyValue::Integer(id),
                    } if *node_id == node => Some((node, *id as u64)),
                    _ => None,
                })
            })
        };
        let Some((node_id, handler_id)) = found else {
            return false;
        };
        self.dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        });
        true
    }

    /// Presses the control placed beside this label.
    ///
    /// A card whose face is a picture has no button carrying its words: what takes the
    /// press is a `Button` with no label, sitting over the picture, and the name is a
    /// `Text` beside it. So the label is looked up, and the press is whatever under the
    /// container that label was placed in answers a click.
    pub fn press_beside(&mut self, label: &str) -> bool {
        let found = {
            let mutations = self.mutations();
            let named = mutations.iter().rev().find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Text,
                    value: PropertyValue::String(text),
                } if *text == label => Some(*node_id),
                _ => None,
            });
            named.and_then(|named| {
                let parents: Vec<(u32, u32)> = mutations
                    .iter()
                    .filter_map(|mutation| match mutation {
                        Mutation::Insert {
                            parent_id, node_id, ..
                        } => Some((*node_id, *parent_id)),
                        _ => None,
                    })
                    .collect();
                let handler = |node: u32| {
                    mutations.iter().rev().find_map(|mutation| match mutation {
                        Mutation::SetProp {
                            node_id,
                            property: PropertyKind::OnClick,
                            value: PropertyValue::Integer(id),
                        } if *node_id == node => Some(*id as u64),
                        _ => None,
                    })
                };
                let parent = parents
                    .iter()
                    .rev()
                    .find_map(|(child, owner)| (*child == named).then_some(*owner))?;
                let mut family = vec![parent];
                while let Some(next) = family.pop() {
                    if next != named {
                        if let Some(id) = handler(next) {
                            return Some((next, id));
                        }
                    }
                    family.extend(
                        parents
                            .iter()
                            .filter_map(|(child, owner)| (*owner == next).then_some(*child)),
                    );
                }
                None
            })
        };
        let Some((node_id, handler_id)) = found else {
            return false;
        };
        self.dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        });
        true
    }

    /// The recording, as the Renderer's screenshot test reads it: each batch behind four
    /// little endian bytes of its length, because a batch is a single envelope and cannot
    /// simply be appended to another one.
    pub fn bytes(&self) -> Vec<u8> {
        let mut bytes = Vec::new();
        for frame in &self.frames {
            bytes.extend_from_slice(&(frame.len() as u32).to_le_bytes());
            bytes.extend_from_slice(frame);
        }
        bytes
    }
}

/// Where the recordings go, or `None` on the ordinary run that only checks they encode.
pub fn frame_dir() -> Option<PathBuf> {
    let directory = std::env::var_os("DXC_FRAME_DIR")?;
    let directory = PathBuf::from(directory);
    std::fs::create_dir_all(&directory).expect("the frame directory can be created");
    Some(directory)
}

/// Builds `app` once per design system, colour scheme and window, hands each screen to
/// `prepare`, and writes the results out when `DXC_FRAME_DIR` says where.
///
/// Unset, which is the normal run, it still builds all thirty-six: a design system or a
/// width nobody can encode is exactly the failure this is here to catch, and catching it
/// should not depend on someone having asked for pictures.
pub fn record(screen: &str, app: fn() -> Element, prepare: impl FnMut(&mut Screen)) {
    record_in(screen, &SYSTEMS, app, prepare);
}

/// The same, for a screen that ships one design system rather than adapting to the host.
///
/// An adaptive sample is drawn by whichever system the platform picks, so all six of them
/// are its real appearance and all six have to be looked at. A unified sample names one,
/// and the other five are screens it will never show: recording them would be five sixths
/// of the pictures being of something nobody can reach.
///
/// What is still worth checking is that the screen encodes, which `record` does for every
/// system it is given, so a caller who wants that breadth passes the whole list.
pub fn record_in(
    screen: &str,
    systems: &[DesignSystem],
    app: fn() -> Element,
    prepare: impl FnMut(&mut Screen),
) {
    let themes: Vec<Theme> = systems
        .iter()
        .copied()
        .flat_map(|system| SCHEMES.map(|scheme| Theme::unified(system).with_color_scheme(scheme)))
        .collect();
    record_as(screen, &themes, app, prepare);
}

/// The systems a design drawn for the iPhone is worth being looked at in.
///
/// Both of Apple's own languages, because both are that platform's and which one an
/// application wants is the application's decision. A design taken from an iOS reference
/// is the case where seeing it in each of them is the point.
pub const APPLE: [DesignSystem; 2] = [DesignSystem::Cupertino, DesignSystem::LiquidGlass];

/// The themes a unified sample is drawn in: its own colour scheme, and each system.
///
/// The scheme is the sample's rather than both of them. A sample whose design is a light
/// one is not drawn dark by anything a reader can reach without saying so, and a picture
/// of a screen nobody opens is a picture that gets compared against a reference it was
/// never meant to match.
pub fn as_designed(theme: Theme, systems: &[DesignSystem]) -> Vec<Theme> {
    systems
        .iter()
        .copied()
        .map(|system| Theme::unified(system).with_color_scheme(theme.color_scheme))
        .collect()
}

/// Records one screen once per theme and window.
pub fn record_as(
    screen: &str,
    themes: &[Theme],
    app: fn() -> Element,
    mut prepare: impl FnMut(&mut Screen),
) {
    let directory = frame_dir();
    for theme in themes.iter().copied() {
        let system = theme.design_system;
        let scheme = theme.color_scheme;
        for viewport in VIEWPORTS {
            dioxus_compose::window::reset_window_size();
            let mut host = Host::with_theme(app, theme);
            let first = host
                .rebuild()
                .unwrap_or_else(|error| {
                    panic!("{screen} {system:?} {scheme:?} does not encode: {error:?}")
                })
                .to_vec();
            assert!(
                !first.is_empty(),
                "{screen} {system:?} {scheme:?} produced an empty first frame, so there is \
                 nothing to draw"
            );
            let mut recording = Screen {
                host,
                frames: vec![first],
                event: Vec::new(),
                viewport,
            };
            recording.dispatch(HostEvent {
                node_id: 0,
                handler_id: 0,
                payload: EventPayload::WindowSizeChanged {
                    width_dp: viewport.width_dp,
                    height_dp: viewport.height_dp,
                    class: WindowSizeClass::from_width_dp(viewport.width_dp),
                    height_class: WindowHeightClass::from_height_dp(viewport.height_dp),
                },
            });
            prepare(&mut recording);
            if let Some(directory) = &directory {
                write(
                    &file_name(directory, screen, system, scheme, viewport),
                    &recording,
                );
            }
        }
    }
    dioxus_compose::window::reset_window_size();
}

/// The name carries the window it was recorded for, so the Renderer draws each recording
/// at the size the Host was laying out for instead of one size for all of them.
fn file_name(
    directory: &Path,
    screen: &str,
    system: DesignSystem,
    scheme: ColorScheme,
    viewport: Viewport,
) -> PathBuf {
    directory.join(format!(
        "{screen}-{system:?}-{scheme:?}-{}-{}x{}.bin",
        viewport.name, viewport.width_dp as u32, viewport.height_dp as u32
    ))
}

fn write(path: &Path, recording: &Screen) {
    std::fs::write(path, recording.bytes())
        .unwrap_or_else(|error| panic!("{} cannot be written: {error}", path.display()));
}
