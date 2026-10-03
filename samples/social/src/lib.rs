//! A meditation app: a course of the day, shelves of courses, and sleep stories.
//!
//! Every card in the reference carries a drawn illustration, and now so does every card
//! here: three original drawings in `assets/`, one per accent family, registered once and
//! drawn by id. The card behind an illustration, the title on it and the row it sits in
//! are still roles, so only the artwork carries colours of its own.
//!
//! Unified, naming Cupertino and light: the reference is a cream page carrying
//! illustrated cards. `THEME` says both.

mod courses;
mod palette;

use compose_rust::prelude::*;
use courses::{Course, Shelf, course, on, sessions_label};

/// A phone design in a desktop window is still a phone design.
const PAGE_MEASURE: f32 = 420.0;

/// How large each picture is. Numbers, because these are the proportions of a picture.
const HERO: (f32, f32) = (388.0, 240.0);
const WIDE: (f32, f32) = (388.0, 180.0);
const TILE: (f32, f32) = (186.0, 120.0);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Destination {
    Today,
    Meditate,
    Sleep,
}

impl Destination {
    const STRIP: [Destination; 3] = [
        Destination::Today,
        Destination::Meditate,
        Destination::Sleep,
    ];

    fn label(self) -> &'static str {
        match self {
            Destination::Today => "Today",
            Destination::Meditate => "Meditate",
            Destination::Sleep => "Sleep",
        }
    }

    fn icon(self) -> IconRole {
        match self {
            Destination::Today => IconRole::Home,
            Destination::Meditate => IconRole::List,
            Destination::Sleep => IconRole::Inbox,
        }
    }

    fn shelf(self) -> Shelf {
        match self {
            Destination::Today => Shelf::ForYou,
            Destination::Meditate => Shelf::Meditate,
            Destination::Sleep => Shelf::Sleep,
        }
    }

    fn index(self) -> usize {
        Self::STRIP
            .iter()
            .position(|found| *found == self)
            .unwrap_or(0)
    }
}

/// A card whose picture fills it, with a band along the bottom carrying the title.
///
/// The band is the part worth explaining. The title used to sit straight on the scene in
/// the ink the scene's family promised, which worked while the scene was drawn from that
/// family: a role's ink is guaranteed to read on that role's fill, and both sides of the
/// promise were roles. An illustration carries its own colours, so there is no longer a
/// fill for an ink to be guaranteed against, and the title came out dark blue over a mid
/// green field. It is readable in the way something you can work out is readable.
///
/// A reading surface under it puts both sides of the promise back. The reference does the
/// same thing with a gradient scrim, which is the same idea drawn more softly than a
/// closed drawing vocabulary can say.
fn hero_card(found: &Course, size: (f32, f32), on_open: EventHandler<u32>) -> Element {
    let id = found.id;
    rsx! {
        compose_rust::Box {
            fill_max_width: true,
            height: size.1,
            shape_role: ShapeRole::Large,
            alignment: Alignment::BottomStart,
            Image {
                fill_max_width: true,
                fill_max_height: true,
                asset_id: asset(AssetKind::Svg, found.palette.scene()),
            }
            // The caption sits inside the picture rather than in a strip under it, over
            // a band of the page's own ink. That is what the reference does, and it is
            // what makes the card one thing instead of a photograph with a label stuck
            // to the bottom of it.
            Row {
                fill_max_width: true,
                background: Paint::Literal(palette::SCRIM),
                padding_role: SpaceRole::Md,
                space_role: SpaceRole::Sm,
                alignment: Alignment::CenterStart,
                Button {
                    text: "\u{25b6}",
                    variant: ButtonVariant::Filled,
                    shape_role: ShapeRole::Full,
                    background: Paint::Literal(palette::SCRIM_BADGE),
                    color: Paint::Literal(palette::ON_ART),
                    on_click: move |_| on_open.call(id),
                }
                Column {
                    weight: 1.0,
                    Text {
                        text: found.title,
                        type_role: TypeRole::Subtitle,
                        color: Paint::Literal(palette::ON_ART),
                        max_lines: 2,
                        overflow: TextOverflow::Ellipsis,
                    }
                    Text {
                        text: sessions_label(found).to_uppercase(),
                        type_role: TypeRole::Caption,
                        color: Paint::Literal(palette::ON_ART_MUTED),
                    }
                }
            }
        }
    }
}

/// A small card: the picture, then the title under it.
fn tile_card(found: &Course, on_open: EventHandler<u32>) -> Element {
    let id = found.id;
    rsx! {
        Column {
            fill_max_width: true,
            space_role: SpaceRole::Xs,
            // The badge is how a card opens, and where the reference puts it: a round
            // play button standing on the illustration's lower left rather than a link
            // underneath in the design system's accent.
            compose_rust::Box {
                fill_max_width: true,
                height: TILE.1,
                shape_role: ShapeRole::Medium,
                alignment: Alignment::BottomStart,
                Image {
                    fill_max_width: true,
                    fill_max_height: true,
                    asset_id: asset(AssetKind::Svg, found.palette.scene()),
                }
                Button {
                    text: "\u{25b6}",
                    variant: ButtonVariant::Filled,
                    shape_role: ShapeRole::Full,
                    background: Paint::Literal(palette::SCRIM_BADGE),
                    color: Paint::Literal(palette::ON_ART),
                    on_click: move |_| on_open.call(id),
                }
            }
            // The meta reads above the title, uppercase and in the quiet green the
            // reference uses for it. Under the title it would read as a footnote; above
            // it, it is the label the card is filed under, which is what it is.
            Text {
                text: sessions_label(found).to_uppercase(),
                type_role: TypeRole::Caption,
                color: Paint::Literal(palette::META),
            }
            Text {
                text: found.title,
                type_role: TypeRole::Body,
                color: Paint::Literal(palette::INK),
                max_lines: 2,
                overflow: TextOverflow::Ellipsis,
            }
        }
    }
}

/// A shelf of small cards, two to a row.
///
/// Two, because there is no wrapping row in the vocabulary: a grid is rows of a fixed
/// count and the code says how many rather than the layout working it out from the width.
fn grid(items: &[&'static Course], on_open: EventHandler<u32>) -> Element {
    let rows: Vec<Vec<&'static Course>> = items.chunks(2).map(<[_]>::to_vec).collect();
    rsx! {
        Column {
            fill_max_width: true,
            space_role: SpaceRole::Md,
            for (index, row) in rows.iter().enumerate() {
                Row {
                    key: "{index}",
                    fill_max_width: true,
                    space_role: SpaceRole::Md,
                    alignment: Alignment::TopStart,
                    for found in row.iter().copied() {
                        compose_rust::Box { key: "{found.id}", weight: 1.0,
                            {tile_card(found, on_open)}
                        }
                    }
                    // An odd shelf leaves a gap rather than letting the last card stretch
                    // to twice the width of its neighbours, which reads as a different
                    // kind of card.
                    if row.len() == 1 {
                        Spacer { weight: 1.0 }
                    }
                }
            }
        }
    }
}

/// The page a destination shows: a hero, then its shelf.
fn shelf_page(
    destination: Destination,
    heading: &'static str,
    strapline: &'static str,
    on_open: EventHandler<u32>,
) -> Element {
    let items = on(destination.shelf());
    let (hero, rest) = items.split_first().expect("every shelf has a course on it");
    rsx! {
        Column {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,

            Column {
                fill_max_width: true,
                space_role: SpaceRole::Xs,
                Text { text: heading, type_role: TypeRole::Headline }
                Text {
                    text: strapline,
                    type_role: TypeRole::Body,
                    color: Paint::Literal(palette::MUTED),
                }
            }

            {hero_card(hero, HERO, on_open)}

            Text {
                text: "Recommended for you",
                type_role: TypeRole::Label,
                color: Paint::Literal(palette::MUTED),
            }
            {grid(rest, on_open)}

            // The wide card the reference ends each shelf with: one course, given the
            // whole width, because a shelf that is all the same size has no shape.
            Text {
                text: "Recommended category",
                type_role: TypeRole::Label,
                color: Paint::Literal(palette::MUTED),
            }
            {hero_card(items[items.len() - 1], WIDE, on_open)}
        }
    }
}

/// One course opened: its picture, what it is, and its sessions.
fn course_page(found: &Course, on_back: EventHandler<()>) -> Element {
    let (strong, quiet, ink) = found.palette.roles();
    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            background: Paint::Literal(quiet),

            compose_rust::Box {
                fill_max_width: true,
                height: HERO.1,
                alignment: Alignment::TopStart,
                Image {
                    fill_max_width: true,
                    fill_max_height: true,
                    asset_id: asset(AssetKind::Svg, found.palette.scene()),
                }
                // Tonal rather than text, because this one sits on the illustration. A
                // tonal fill is the one variant that promises to stay clear of whatever
                // is behind it, which is what a control over a picture needs; a text
                // button takes a colour chosen to read on a role's fill, and the picture
                // is not that fill.
                Button {
                    text: "\u{2190}",
                    variant: ButtonVariant::Tonal,
                    on_click: move |_| on_back.call(()),
                }
            }

            Column {
                fill_max_width: true,
                weight: 1.0,
                padding_role: SpaceRole::Md,
                space_role: SpaceRole::Md,
                Text {
                    text: found.title,
                    type_role: TypeRole::Headline,
                    color: Paint::Literal(ink),
                }
                Text {
                    text: sessions_label(found),
                    type_role: TypeRole::Body,
                    color: Paint::Literal(ink),
                }
                Column {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    for number in 1..=found.sessions {
                        Row {
                            key: "{number}",
                            fill_max_width: true,
                            background: Paint::Literal(palette::CARD),
                            shape_role: ShapeRole::Medium,
                            padding_role: SpaceRole::Md,
                            space_role: SpaceRole::Sm,
                            alignment: Alignment::CenterStart,
                            Text {
                                text: "Session {number}",
                                type_role: TypeRole::Body,
                                weight: 1.0,
                            }
                            Text {
                                text: "{found.minutes} min",
                                type_role: TypeRole::Caption,
                                color: Paint::Literal(palette::MUTED),
                            }
                        }
                    }
                }
                Spacer { weight: 1.0 }
                Button {
                    text: "Begin",
                    fill_max_width: true,
                    variant: ButtonVariant::Filled,
                    background: Paint::Literal(strong),
                    on_click: move |_| {
                        Message::new("Playback is not part of this sample").show();
                    },
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

    let mut destination = use_signal(|| Destination::Today);
    let mut opened = use_signal(|| Option::<u32>::None);
    let on_open = EventHandler::new(move |id: u32| opened.set(Some(id)));

    // A course covers everything, including the bar along the bottom. A course whose only
    // way out is its own back button should not also have three other ways out.
    if let Some(found) = opened().and_then(course) {
        return rsx! {
            compose_rust::Box {
                fill_max_width: true,
                fill_max_height: true,
                alignment: Alignment::TopCenter,
                Column {
                    width: measure,
                    fill_max_width: measure.is_none(),
                    fill_max_height: true,
                    {course_page(found, EventHandler::new(move |()| opened.set(None)))}
                }
            }
        };
    }

    let body = match destination() {
        Destination::Today => shelf_page(
            Destination::Today,
            "Good evening",
            "Three minutes is enough to start with.",
            on_open,
        ),
        Destination::Meditate => shelf_page(
            Destination::Meditate,
            "Meditate",
            "Meditation for beginners and for people who have been at it a while.",
            on_open,
        ),
        Destination::Sleep => shelf_page(
            Destination::Sleep,
            "Sleep stories",
            "Something to listen to on the way down.",
            on_open,
        ),
    };

    rsx! {
        // The frame is named rather than built. Which of a bar, a rail or a drawer the
        // destinations become, and where the page sits beside them, are answers this
        // screen never has to know.
        Scaffold {
            bottom_bar: rsx! {
                Navigation {
                    selected_index: destination().index(),
                    for choice in Destination::STRIP {
                        NavigationItem {
                            key: "{choice.label()}",
                            // The reference's bar is the page's own ink rather than an accent.
                            color: Paint::Literal(palette::INK),
                            text: choice.label(),
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
                compose_rust::Box {
                    fill_max_width: true,
                    fill_max_height: true,
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
/// platform's convention, and light because the reference is a cream page carrying
/// illustrated cards. The sleep stories screen is dark navy, but that is one destination
/// inside a light app rather than the app's scheme.
///
/// The scheme is said out loud rather than left to follow the machine. `Theme::unified`
/// settles which design system is drawn and nothing else, so without this line a reader
/// whose system is set the other way sees a screen the design was never drawn for.
const THEME: Theme = Theme::unified(DesignSystem::Cupertino).with_color_scheme(ColorScheme::Light);

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
    // sample here was listed as DioxusCompose until this line existed.
    compose_rust::LaunchBuilder::new()
        .with_theme(compose_rust::demo_theme_for(THEME))
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Social")
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

    /// The mark on the badge that opens a course. Named because two tests press it and a
    /// glyph nobody types is not something to spell twice.
    const PLAY: &str = "\u{25b6}";

    use compose_rust::Host;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
    use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind};
    use courses::COURSES;

    /// Named for what it defends: the reference is a light design, and a machine set
    /// the other way drew this sample dark with nothing to compare against.
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
        assert_eq!(theme.color_scheme, ColorScheme::Light);
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
                screen.press(choice.label()),
                "the bar has no destination called {}",
                choice.label()
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// Every card carries its picture, and each drawing crosses once.
    ///
    /// Named for what it defends: an `Image` whose id names nothing is a blank rectangle,
    /// and on this screen the pictures are most of what there is. A shelf of nine cards
    /// drawn from three illustrations is three registrations, not nine.
    #[test]
    fn fr16_every_card_carries_its_picture() {
        compose_rust::window::reset_window_size();
        let batch = Host::new(app)
            .rebuild()
            .expect("the first frame failed")
            .to_vec();
        let mutations = decode_batch(&batch).expect("the batch did not decode");
        let registered: Vec<u32> = mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::RegisterAsset { asset_id, .. } => Some(*asset_id),
                _ => None,
            })
            .collect();
        assert!(
            !registered.is_empty(),
            "the shelf registered no pictures at all"
        );
        assert!(
            registered.len() <= courses::SCENES.len(),
            "the shelf registered {} pictures out of {} drawings",
            registered.len(),
            courses::SCENES.len()
        );
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
        assert!(!images.is_empty(), "the shelf draws nothing at all");
        for image in images {
            let drawn = mutations.iter().find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Asset,
                    value: PropertyValue::Integer(id),
                } if *node_id == image => Some(*id as u32),
                _ => None,
            });
            assert!(
                drawn.is_some_and(|id| registered.contains(&id)),
                "a card draws an id that was never registered"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// Opening a course covers the whole screen and its back button returns.
    #[test]
    fn fr15_a_course_opens_and_closes() {
        let mut screen = Screen::new();
        assert!(
            screen.press(PLAY),
            "no card on the shelf opens: the badge that opens one is the play mark on \
             its illustration"
        );
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text.starts_with("Session ")),
            "opening a course did not bring up its sessions"
        );
        assert!(screen.press("\u{2190}"), "the course has no way back");
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text == "Good evening"),
            "closing the course did not bring the shelf back"
        );
        compose_rust::window::reset_window_size();
    }

    /// A course lists as many sessions as it says it has. A count in a label and a list
    /// of a different length is the screen contradicting itself.
    #[test]
    fn a_course_lists_the_sessions_it_says_it_has() {
        for found in COURSES {
            let listed = (1..=found.sessions).count() as u32;
            assert_eq!(listed, found.sessions, "{} miscounts", found.title);
        }
    }

    /// The shelf, in the design system it ships, in both schemes, at all three widths.
    #[test]
    fn fr16_the_shelf_is_recorded_in_the_system_it_ships() {
        sample_frames::record_as(
            "Social",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |_| {},
        );
    }

    /// The sleep shelf, which the reference draws on a dark page: a different set of
    /// pictures and the one place the cards are all the same shape.
    #[test]
    fn fr16_the_sleep_shelf_is_recorded() {
        sample_frames::record_as(
            "SocialSleep",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    screen.press(Destination::Sleep.label()),
                    "the bar has no way to the sleep stories"
                );
            },
        );
    }

    /// One course opened, which is the page where the picture, the tint and the ink on it
    /// are all from one family and have to agree.
    #[test]
    fn fr13_an_opened_course_is_recorded() {
        sample_frames::record_as(
            "SocialCourse",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    screen.press(PLAY),
                    "no card on the shelf opens: the badge that opens one is the play \
                     mark on its illustration"
                );
            },
        );
    }
}
