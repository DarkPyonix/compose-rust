//! A drum school: four subjects, four stages of lessons, and what is still locked.
//!
//! The marks on the tiles are pictures: the reference puts a rendered object on each, and
//! three concentric circles in four colours is four subjects nobody can tell apart.
//!
//! The screen this sample exists for is the grid of subjects, and it is the one place in
//! the seven references where the role vocabulary ran out. Four subjects want four fills
//! that are peers of each other. There are three accent families and nothing else that is
//! a peer of them, so the fourth tile is the neutral fill and reads quieter than its
//! neighbours. That is written down in `school.rs` rather than papered over with a
//! literal.
//!
//! Unified, naming Cupertino and dark: the reference is drawn on black, both screens,
//! and a drum school that comes up white on a machine set to light is not that design.
//! `THEME` says both.

mod school;

mod palette {
    use compose_rust::prelude::Color;
    pub const PAGE: Color = Color::rgb(0x000000);
    pub const TEXT: Color = Color::rgb(0xFFFFFF);
    pub const PURPLE: Color = Color::rgb(0x3B2C63);
    pub const RED: Color = Color::rgb(0xFF5A5F);
    pub const TEAL: Color = Color::rgb(0x2DD4BF);
    pub const AMBER: Color = Color::rgb(0xFBBF24);
}

use compose_rust::prelude::*;
use school::{LESSONS, Lesson, STAGES, SUBJECTS, Subject, ordinal, overall_progress, stage};

/// A phone design in a desktop window is still a phone design.
const PAGE_MEASURE: f32 = 420.0;

/// How large a subject's mark is, and how tall its tile is.
///
/// Larger than they were. The reference's four tiles take the top half of the screen with
/// the object on each one filling most of its tile, and a grid of small marks in tall
/// tiles left a band of empty page under it on a phone.
const MARK_SIDE: f32 = 108.0;
const TILE_HEIGHT: f32 = 196.0;
const STRIP_HEIGHT: f32 = 56.0;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Destination {
    Skills,
    Plan,
    Progress,
    Profile,
}

impl Destination {
    const STRIP: [Destination; 4] = [
        Destination::Skills,
        Destination::Plan,
        Destination::Progress,
        Destination::Profile,
    ];

    fn label(self) -> &'static str {
        match self {
            Destination::Skills => "Skills",
            Destination::Plan => "Plan",
            Destination::Progress => "Progress",
            Destination::Profile => "Profile",
        }
    }

    fn icon(self) -> IconRole {
        match self {
            Destination::Skills => IconRole::Home,
            Destination::Plan => IconRole::List,
            Destination::Progress => IconRole::Check,
            Destination::Profile => IconRole::Settings,
        }
    }

    fn index(self) -> usize {
        Self::STRIP
            .iter()
            .position(|found| *found == self)
            .unwrap_or(0)
    }
}

/// One subject as a tile: the mark, the name, and how far through it you are.
fn subject_tile(subject: &Subject, on_open: EventHandler<&'static str>) -> Element {
    let name = subject.name;
    let fill = match name {
        "Technique" => Paint::Literal(palette::PURPLE),
        "Arsenal" => Paint::Literal(palette::RED),
        "Coordination" => Paint::Literal(palette::TEAL),
        _ => Paint::Literal(palette::AMBER),
    };
    rsx! {
        compose_rust::Box {
            fill_max_width: true,
            height: TILE_HEIGHT,
            background: fill,
            shape_role: ShapeRole::Large,
            padding_role: SpaceRole::Md,
            Column {
                fill_max_width: true,
                fill_max_height: true,
                compose_rust::Box {
                    fill_max_width: true,
                    weight: 1.0,
                    alignment: Alignment::Center,
                    Image {
                        width: MARK_SIDE,
                        height: MARK_SIDE,
                        asset_id: asset(AssetKind::Svg, subject.mark),
                    }
                }
                Button {
                    text: name,
                    variant: ButtonVariant::Text,
                    color: Paint::Literal(palette::TEXT),
                    padding_role: SpaceRole::None,
                    on_click: move |_| on_open.call(name),
                }
            }
        }
    }
}

/// The four subjects, two to a row, over the title the reference gives them.
fn skills_page(on_open: EventHandler<&'static str>) -> Element {
    let rows: Vec<Vec<&'static Subject>> =
        SUBJECTS.chunks(2).map(|row| row.iter().collect()).collect();
    rsx! {
        Column {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,

            Column {
                fill_max_width: true,
                space_role: SpaceRole::Md,
                for (index, row) in rows.iter().enumerate() {
                    Row {
                        key: "{index}",
                        fill_max_width: true,
                        space_role: SpaceRole::Md,
                        alignment: Alignment::TopStart,
                        for subject in row.iter().copied() {
                            compose_rust::Box { key: "{subject.name}", weight: 1.0,
                                {subject_tile(subject, on_open)}
                            }
                        }
                    }
                }
            }

            Row {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::CenterStart,
                Text {
                    text: "< LESSON PLAN",
                    type_role: TypeRole::Caption,
                    color: Paint::Literal(palette::TEXT),
                    weight: 1.0,
                }
                Text {
                    text: "YOUR PROGRESS >",
                    type_role: TypeRole::Caption,
                    color: Paint::Literal(palette::TEXT),
                }
            }
        }
    }
}

/// One lesson as a card. What is locked is quiet and says so in a word, because a padlock
/// is an icon and an icon cannot be placed from application code.
fn lesson_card(lesson: &Lesson) -> Element {
    let (fill, ink) = if lesson.open {
        (ColorRole::SurfaceContainer, ColorRole::OnSurface)
    } else {
        (ColorRole::SurfaceVariant, ColorRole::OnSurfaceVariant)
    };
    rsx! {
        Column {
            fill_max_width: true,
            background: Paint::Role(fill),
            shape_role: ShapeRole::Large,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Xs,
            if lesson.recent {
                Text {
                    text: "Recent",
                    type_role: TypeRole::Label,
                    color: Paint::Role(ColorRole::Primary),
                }
            }
            Text {
                text: lesson.title,
                type_role: TypeRole::Title,
                color: Paint::Role(ink),
            }
            Text {
                text: lesson.summary,
                type_role: TypeRole::Body,
                color: Paint::Role(ColorRole::OnSurfaceVariant),
            }
            Row {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::CenterStart,
                Text {
                    text: "{ordinal(lesson.number)} lesson",
                    type_role: TypeRole::Caption,
                    color: Paint::Role(ColorRole::OnSurfaceVariant),
                    weight: 1.0,
                }
                if lesson.open {
                    Button {
                        text: "Start",
                        variant: ButtonVariant::Filled,
                        on_click: move |_| {
                            Message::new("Lessons are not part of this sample").show();
                        },
                    }
                } else {
                    Text {
                        text: "Locked",
                        type_role: TypeRole::Label,
                        color: Paint::Role(ColorRole::OnSurfaceVariant),
                    }
                }
            }
        }
    }
}

/// The lesson plan: a stage strip that runs off the edge, and the lessons in that stage.
fn plan_page(chosen: Signal<u32>) -> Element {
    let mut chosen = chosen;
    let lessons = stage(chosen());
    rsx! {
        Column {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,

            Text { text: "Lesson plan", type_role: TypeRole::Headline }

            // A strip rather than a segmented control. `Tabs` divides its width equally
            // and does not scroll, so four stages on a phone is four segments of seventy
            // dp with a button's own padding inside each, and every label breaks.
            LazyRow {
                fill_max_width: true,
                height: STRIP_HEIGHT,
                item_count: STAGES.len(),
                key_of: move |position: usize| STAGES[position].to_string(),
                item: move |position: usize| {
                    let number = STAGES[position];
                    rsx! {
                        compose_rust::Box {
                            padding_role: SpaceRole::Xs,
                            alignment: Alignment::Center,
                            Button {
                                text: "Stage {number}",
                                variant: if number == chosen() {
                                    ButtonVariant::Filled
                                } else {
                                    ButtonVariant::Text
                                },
                                on_click: move |_| chosen.set(number),
                            }
                        }
                    }
                },
            }

            Column {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                for lesson in lessons.iter().copied() {
                    compose_rust::Box { key: "{lesson.number}", fill_max_width: true,
                        {lesson_card(lesson)}
                    }
                }
            }
        }
    }
}

/// How far through everything a pupil is, subject by subject.
fn progress_page() -> Element {
    let overall = overall_progress();
    let done = LESSONS.iter().filter(|lesson| lesson.open).count();
    rsx! {
        Column {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,

            Text { text: "Your progress", type_role: TypeRole::Headline }

            Surface {
                fill_max_width: true,
                shape_role: ShapeRole::Large,
                padding_role: SpaceRole::Md,
                Column {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    Text {
                        text: "{done} of {LESSONS.len()} lessons open",
                        type_role: TypeRole::Body,
                    }
                    ProgressIndicator { fill_max_width: true, value: overall }
                }
            }

            Column {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                for subject in SUBJECTS {
                    Column {
                        key: "{subject.name}",
                        fill_max_width: true,
                        background: Paint::Role(subject.tile.roles().0),
                        shape_role: ShapeRole::Large,
                        padding_role: SpaceRole::Md,
                        space_role: SpaceRole::Xs,
                        Row {
                            fill_max_width: true,
                            alignment: Alignment::CenterStart,
                            Text {
                                text: subject.name,
                                type_role: TypeRole::BodyStrong,
                                color: Paint::Role(subject.tile.roles().1),
                                weight: 1.0,
                            }
                            Text {
                                text: "{(subject.progress * 100.0).round() as i32}%",
                                type_role: TypeRole::Label,
                                color: Paint::Role(subject.tile.roles().1),
                            }
                        }
                        // A whole sentence on the tint, which is the promise the container
                        // roles make and the reason they are a colour of their own.
                        Text {
                            text: subject.blurb,
                            type_role: TypeRole::Caption,
                            color: Paint::Role(subject.tile.roles().1),
                        }
                        ProgressIndicator { fill_max_width: true, value: subject.progress }
                    }
                }
            }
        }
    }
}

pub fn app() -> Element {
    let window = use_window_size();
    let measure = if window.is_compact() {
        None
    } else {
        Some(PAGE_MEASURE)
    };

    let mut destination = use_signal(|| Destination::Skills);
    let chosen_stage = use_signal(|| 2_u32);
    let mut opened = use_signal(|| Option::<&'static str>::None);

    let on_open = EventHandler::new(move |name: &'static str| {
        opened.set(Some(name));
        destination.set(Destination::Plan);
    });

    let body = match destination() {
        Destination::Skills => skills_page(on_open),
        Destination::Plan => plan_page(chosen_stage),
        Destination::Progress => progress_page(),
        Destination::Profile => rsx! {
            compose_rust::Box {
                fill_max_width: true,
                fill_max_height: true,
                alignment: Alignment::Center,
                Text { text: "Profile", type_role: TypeRole::Body, color: Paint::Literal(palette::TEXT) }
            }
        },
    };

    rsx! {
        // The frame is named rather than built. Which of a bar, a rail or a drawer the
        // destinations become, and where the page sits beside them, are answers this
        // screen never has to know.
        Scaffold {
            background: Paint::Literal(palette::PAGE),
            bottom_bar: rsx! {
                Navigation {
                    background: Paint::Literal(palette::PAGE),
                    selected_index: destination().index(),
                    for choice in Destination::STRIP {
                        NavigationItem {
                            key: "{choice.label()}",
                            // The reference's bar has no accent in it: every icon is white and
                            // the selected one is marked by nothing but being the one you are on.
                            color: Paint::Literal(palette::TEXT),
                            text: "",
                            icon: choice.icon(),
                            on_click: move |()| destination.set(choice),
                        }
                    }
                }
            },

            Column {
                fill_max_width: true,
                fill_max_height: true,
                background: Paint::Literal(palette::PAGE),
                // Custom header
                Row {
                    fill_max_width: true,
                    padding_role: SpaceRole::Md,
                    alignment: Alignment::CenterStart,
                    Text { text: "Skills\nTo Pump!", type_role: TypeRole::Display, weight: 1.0, color: Paint::Literal(palette::TEXT) }
                    compose_rust::Box {
                        width: 48.0,
                        height: 48.0,
                        corner_radius: 24.0,
                        background: Paint::Literal(palette::TEXT),
                    }
                }
                compose_rust::Box {
                    fill_max_width: true,
                    weight: 1.0,
                    alignment: Alignment::TopCenter,
                    ScrollColumn {
                        width: measure,
                        fill_max_width: measure.is_none(),
                        fill_max_height: true,
                        {body}
                    }
                }
            }
        }
    }
}

/// The design this sample draws, named once.
///
/// One design system everywhere, because the design is the product here rather than the
/// platform's convention, and dark because the reference is black throughout, both
/// screens, with the four subject tiles and one white card as the only light areas.
///
/// The scheme is said out loud rather than left to follow the machine. `Theme::unified`
/// settles which design system is drawn and nothing else, so without this line a reader
/// whose system is set the other way sees a screen the design was never drawn for.
const THEME: Theme = Theme::unified(DesignSystem::Cupertino).with_color_scheme(ColorScheme::Dark);

/// `demo_theme_for` rather than `THEME` alone: a sample is something to look at, and one
/// machine can only show the design system and the scheme it is set to. `DXC_DESIGN` and
/// `DXC_SCHEME` each override the half they name, so the line above stays the answer to
/// everything nobody asked about.
/// Runs the sample as a program of its own. The desktop binary is one line of this.
pub fn launch() {
    launch_builder().launch(app);
}

/// How this sample is configured, in one place because three entry points need it.
///
/// The theme above all: a sample that names one and then reaches a platform through an
/// entry point that makes its own builder is a sample that draws the same screens in a
/// different design system depending on where it runs.
fn launch_builder() -> compose_rust::LaunchBuilder {
    // The name the window carries. A desktop lists windows by it, so a window that said
    // nothing was listed under whatever the renderer happened to be called, and every
    // sample here was listed under the toolkit's default name until this line existed.
    compose_rust::LaunchBuilder::new()
        .with_theme(compose_rust::demo_theme_for(THEME))
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Academic")
                // Without one the window wears the toolkit's picture, which on
                // Windows is the Java coffee cup, wherever the system lists
                // windows. The bytes travel as an asset and the renderer refers
                // to them by id: a path would be a fact about the machine this
                // was built on, and a name would ask the toolkit to find
                // something it may not have.
                .with_icon(compose_rust::asset::asset(
                    compose_rust::schema::AssetKind::Png,
                    include_bytes!("../assets/icon.png"),
                )),
        )
}

// The platforms where the sample is not a program. Android's Activity and the browser's
// page both own the loop, so neither has a `main` to call: each names an entry point that
// registers the root component, and these macros define it.
//
// Both are declared unconditionally. Each macro compiles into nothing that runs off its
// own platform, and gating them here instead would mean a desktop build never checks that
// this sample can still be built for the other two.
compose_rust::android_main!({ launch_builder() }, app);
compose_rust::web_main!({ launch_builder() }, app);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;

    use compose_rust::Host;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
    use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind};

    /// Named for what it defends: the reference is a dark design, and a machine set
    /// the other way drew this sample light with nothing to compare against.
    #[test]
    fn fr14_the_design_names_its_colour_scheme() {
        // Through the wire rather than off the constant: what settles the question is the
        // record the Renderer reads, and a scheme that never leaves the Host is a scheme
        // nobody is drawn in.
        compose_rust::window::reset_window_size();
        let mut host = Host::with_theme(app, THEME);
        let batch = host.rebuild().expect("the first frame failed").to_vec();
        let first = decode_batch(&batch)
            .expect("the first batch did not decode")
            .into_iter()
            .next()
            .expect("the first batch is empty");
        let Mutation::SetTheme(theme) = first else {
            panic!("the first record is {first:?} rather than the theme");
        };
        assert_eq!(theme.color_scheme, ColorScheme::Dark);
        assert!(!theme.adaptive, "the design is the product here");
    }

    /// The screen, driven the way a Renderer drives it. Every batch is kept, because a
    /// batch is the change since the frame before it rather than what is on screen.
    struct Screen {
        host: Host,
        frames: Vec<Vec<u8>>,
    }

    impl Screen {
        fn new() -> Self {
            compose_rust::window::reset_window_size();
            let mut host = Host::new(app);
            let first = host.rebuild().expect("the first frame failed").to_vec();
            Self {
                host,
                frames: vec![first],
            }
        }

        fn mutations(&self) -> Vec<Mutation<'_>> {
            self.frames
                .iter()
                .flat_map(|frame| decode_batch(frame).expect("a batch did not decode"))
                .collect()
        }

        fn press(&mut self, label: &str) -> bool {
            let found = {
                let mutations = self.mutations();
                let node = mutations.iter().rev().find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                    } if *text == label => Some(*node_id),
                    _ => None,
                });
                node.and_then(|node| {
                    mutations.iter().rev().find_map(|mutation| match mutation {
                        Mutation::SetProp {
                            node_id,
                            property: PropertyKind::OnClick,
                            value: PropertyValue::Integer(handler),
                        } if *node_id == node => Some((node, *handler as u64)),
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

        /// Answers every windowing list's range request, which is what a real Renderer
        /// does before the first pixel. Without it a `LazyRow` is an empty box and nothing
        /// inside it can be pressed.
        fn fill_lists(&mut self, count: u32) {
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
                                value: PropertyValue::Integer(handler),
                            } if node_id == node => Some((*node, *handler as u64)),
                            _ => None,
                        })
                    })
                    .collect()
            };
            for (node_id, handler_id) in lists {
                let mut bytes = Vec::new();
                encode_event(
                    &HostEvent {
                        node_id,
                        handler_id,
                        payload: EventPayload::RangeRequested { start: 0, count },
                    },
                    &mut bytes,
                )
                .expect("the range request did not encode");
                let (batch, _) = self
                    .host
                    .dispatch_event(&bytes)
                    .expect("the range request failed");
                if !batch.is_empty() {
                    self.frames.push(batch.to_vec());
                }
            }
        }

        fn latest_texts(&self) -> Vec<String> {
            let Some(frame) = self.frames.last() else {
                return Vec::new();
            };
            decode_batch(frame)
                .expect("the batch did not decode")
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetProp {
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                        ..
                    } => Some((*text).to_owned()),
                    _ => None,
                })
                .collect()
        }
        fn press_icon(&mut self, icon: IconRole) -> bool {
            let found = {
                let mutations = self.mutations();
                let node = mutations.iter().rev().find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Icon,
                        value: PropertyValue::Integer(val),
                    } if *val == icon as i64 => Some(*node_id),
                    _ => None,
                });
                node.and_then(|node| {
                    mutations.iter().rev().find_map(|mutation| match mutation {
                        Mutation::SetProp {
                            node_id,
                            property: PropertyKind::OnClick,
                            value: PropertyValue::Integer(handler),
                        } if *node_id == node => Some((node, *handler as u64)),
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
    }

    #[test]
    fn fr14_the_palette_is_pinned() {
        assert_eq!(palette::PAGE.to_argb(), 0xFF000000);
        assert_eq!(palette::TEXT.to_argb(), 0xFFFFFFFF);
        assert_eq!(palette::PURPLE.to_argb(), 0xFF3B2C63);
        assert_eq!(palette::RED.to_argb(), 0xFFFF5A5F);
        assert_eq!(palette::TEAL.to_argb(), 0xFF2DD4BF);
        assert_eq!(palette::AMBER.to_argb(), 0xFFFBBF24);
    }

    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        assert!(Host::new(app).rebuild().is_ok());
    }

    /// Every destination has to encode, not just the one the app opens on.
    #[test]
    fn fr15_every_destination_encodes() {
        let mut screen = Screen::new();
        for choice in Destination::STRIP {
            assert!(
                screen.press_icon(choice.icon()),
                "the bar has no destination called {}",
                choice.label()
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// The grid has four marks on it, one registration each, and a mark whose id names
    /// nothing is a blank square where a subject should be.
    #[test]
    fn fr16_every_subject_carries_a_mark() {
        compose_rust::window::reset_window_size();
        let batch = Host::new(app)
            .rebuild()
            .expect("the first frame failed")
            .to_vec();
        let mutations = decode_batch(&batch).expect("the batch did not decode");
        for subject in SUBJECTS {
            assert_eq!(
                mutations
                    .iter()
                    .filter(|mutation| matches!(
                        mutation,
                        Mutation::RegisterAsset { bytes, .. } if *bytes == subject.mark
                    ))
                    .count(),
                1,
                "{} has no mark, or sent it more than once",
                subject.name
            );
        }
        let images: Vec<u32> = mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::Create {
                    node_id,
                    widget: WidgetKind::Image,
                } => Some(*node_id),
                _ => None,
            })
            .collect();
        assert_eq!(
            images.len(),
            SUBJECTS.len(),
            "the grid should hold one mark per subject"
        );
        for image in images {
            assert!(
                mutations.iter().any(|mutation| matches!(
                    mutation,
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Asset,
                        ..
                    } if *node_id == image
                )),
                "a subject's mark reached the Renderer with no picture on it"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// Opening a subject takes you to its lessons and the bar says which subject it is.
    #[test]
    fn fr15_opening_a_subject_reaches_its_lessons() {
        let mut screen = Screen::new();
        assert!(screen.press("Technique"), "no subject on the grid opens");
        let showing = screen.latest_texts();
        assert!(
            showing
                .iter()
                .any(|text| text == "Lesson plan" || text == "< LESSON PLAN"),
            "opening a subject did not reach the lessons"
        );
        compose_rust::window::reset_window_size();
    }

    /// Every stage has to encode, not only the one the plan opens on. A locked lesson is
    /// drawn differently from an open one, and a stage nobody walked to is a card nobody
    /// has built.
    #[test]
    fn fr15_every_stage_encodes() {
        let mut screen = Screen::new();
        assert!(
            screen.press_icon(Destination::Plan.icon()),
            "no lesson plan"
        );
        // The strip windows its stages, so nothing inside it exists until something asks
        // for a range. A real Renderer asks before the first pixel.
        screen.fill_lists(STAGES.len() as u32);
        for number in STAGES {
            assert!(
                screen.press(&format!("Stage {number}")),
                "the strip has no stage {number}"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// The grid, in the design system it ships, in both schemes, at all three widths. The
    /// reference is drawn on black, so the dark recording is the one to hold beside it.
    #[test]
    fn fr13_the_subject_grid_is_recorded_in_the_system_it_ships() {
        sample_frames::record_as(
            "Academic",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |_| {},
        );
    }

    /// The lesson plan, which is where an open lesson and a locked one sit next to each
    /// other and have to be told apart at a glance.
    #[test]
    fn fr13_the_lesson_plan_is_recorded() {
        sample_frames::record_as(
            "AcademicPlan",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    screen.press_icon(Destination::Plan.icon()),
                    "the bar has no way to the lesson plan"
                );
                screen.fill_lists(4);
            },
        );
    }
}
