//! A self-care app: say how the day felt, say what is behind it, and pick something to
//! listen to.
//!
//! The screen the sample exists for is the check-in. A picker whose answers are colours
//! is the thing the role vocabulary was hardest pressed by: four feelings need four fills
//! that read as relatives, are quiet enough to hold a drawn face, and each arrive with an
//! ink that stays readable on them. A literal would have been easier and would have kept
//! its light-mode pink when the reader asked for dark.
//!
//! Unified, naming Cupertino and dark: the reference's check-in is black, and that is
//! the screen this opens on. `THEME` says both.
//!
//! The colours are this sample's own rather than the design system's. Four feelings need
//! four fills that read as relatives and are quiet enough to hold a drawn face, and the
//! reference names them: mint, pink, powder blue and coral. A role cannot say any of the
//! four, because whichever system is active would answer with its own containers, and
//! measuring the seven of them shows every light page landing within a few percent of
//! white. So the palette is written here and `palette.rs` is the only place it lives.

mod mood;
mod palette;

use compose_rust::prelude::*;
use mood::{Mood, SESSIONS, Session, WORRIES, week_line};

/// A phone design in a desktop window is still a phone design.
const PAGE_MEASURE: f32 = 420.0;

/// The square the face is drawn into, and the box the week's line gets.
const FACE_SIDE: f32 = 240.0;
const CHART: (f32, f32) = (340.0, 150.0);
/// How wide a session card is on the row that runs off the edge.
const CARD_WIDTH: f32 = 180.0;
const CARD_HEIGHT: f32 = 150.0;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Destination {
    Today,
    Listen,
    Library,
    Profile,
}

impl Destination {
    const STRIP: [Destination; 4] = [
        Destination::Today,
        Destination::Listen,
        Destination::Library,
        Destination::Profile,
    ];

    fn label(self) -> &'static str {
        match self {
            Destination::Today => "Today",
            Destination::Listen => "Listen",
            Destination::Library => "Library",
            Destination::Profile => "Profile",
        }
    }

    fn icon(self) -> IconRole {
        match self {
            Destination::Today => IconRole::Home,
            Destination::Listen => IconRole::Forward,
            Destination::Library => IconRole::List,
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

/// The two steps of the check-in.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Step {
    Feeling,
    Worry,
    Done,
}

/// The mood picker: the face, the strip under it, and the question.
fn feeling_step(
    chosen: Signal<Mood>,
    on_skip: EventHandler<()>,
    on_next: EventHandler<()>,
) -> Element {
    let mut chosen = chosen;
    let (fill, _ink) = chosen().pair();
    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,

            // The face fills the top of the screen in the tint of the feeling it is
            // showing, which is the reference's whole idea: the answer is the colour. It
            // runs to the window's edges, so it is outside the inset column below rather
            // than the first row of it.
            compose_rust::Box {
                fill_max_width: true,
                height: FACE_SIDE * 1.4,
                background: Paint::Literal(fill),
                alignment: Alignment::Center,
                Image {
                    width: FACE_SIDE,
                    height: FACE_SIDE,
                    asset_id: asset(AssetKind::Svg, chosen().picture()),
                }
            }

            // Everything under the face, inset once and spaced once.
            //
            // Each of these used to carry its own padding inside a column that was also
            // spacing them, so the gap between two of them was the space rung plus two
            // paddings and the gap between two others was the rung alone. The ladder
            // answers how far apart two things sit; it cannot do that if half the answer
            // is added again by each thing.
            Column {
                fill_max_width: true,
                weight: 1.0,
                padding_role: SpaceRole::Md,
                space_role: SpaceRole::Lg,

                // The strip of answers. The chosen one is filled and the rest are plain,
                // so which colour "filled" means stays the design system's.
                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    for option in Mood::STRIP {
                        Button {
                            key: "{option.label()}",
                            text: option.label(),
                            weight: 1.0,
                            variant: ButtonVariant::Filled,
                            shape_role: ShapeRole::Full,
                            // The chosen answer is filled with the feeling's own colour
                            // and the rest are the quiet grey the reference gives them.
                            // The answer is the colour, so the colour is what changes.
                            background: if option == chosen() {
                                Paint::Literal(option.pair().0)
                            } else {
                                Paint::Literal(palette::CHIP)
                            },
                            color: if option == chosen() {
                                Paint::Literal(option.pair().1)
                            } else {
                                Paint::Literal(palette::INK)
                            },
                            on_click: move |_| chosen.set(option),
                        }
                    }
                }

                Column {
                    fill_max_width: true,
                    space_role: SpaceRole::Xs,
                    alignment: Alignment::Center,
                    Text {
                        text: "How do you feel today?",
                        type_role: TypeRole::Headline,
                        text_align: TextAlign::Center,
                        color: Paint::Literal(palette::INK),
                    }
                }

                Spacer { weight: 1.0 }

                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Md,
                    alignment: Alignment::CenterStart,
                    Button {
                        text: "Skip",
                        variant: ButtonVariant::Text,
                        color: Paint::Literal(palette::INK),
                        on_click: move |_| on_skip.call(()),
                    }
                    Button {
                        text: "Next",
                        weight: 1.0,
                        variant: ButtonVariant::Filled,
                        shape_role: ShapeRole::Full,
                        background: Paint::Literal(palette::INK),
                        color: Paint::Literal(palette::PAGE),
                        on_click: move |_| on_next.call(()),
                    }
                }
            }
        }
    }
}

/// The worry picker: ten chips, any number of them chosen.
fn worry_step(
    picked: Signal<Vec<&'static str>>,
    on_skip: EventHandler<()>,
    on_next: EventHandler<()>,
) -> Element {
    let mut picked = picked;
    let chosen = picked();
    // Two to a row. There is no wrapping row in the vocabulary, so a grid is rows of a
    // fixed count and the code says how many rather than the layout working it out.
    let rows: Vec<&[&'static str]> = WORRIES.chunks(2).collect();
    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,

            Text {
                text: "What's worrying you?",
                type_role: TypeRole::Headline,
                text_align: TextAlign::Center,
                fill_max_width: true,
            }

            Column {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                for (index, row) in rows.iter().enumerate() {
                    Row {
                        key: "{index}",
                        fill_max_width: true,
                        space_role: SpaceRole::Sm,
                        for worry in row.iter().copied() {
                            Button {
                                key: "{worry}",
                                text: worry,
                                weight: 1.0,
                                variant: if chosen.contains(&worry) {
                                    ButtonVariant::Filled
                                } else {
                                    ButtonVariant::Tonal
                                },
                                on_click: move |_| {
                                    let mut list = picked.write();
                                    match list.iter().position(|found| *found == worry) {
                                        Some(at) => {
                                            list.remove(at);
                                        }
                                        None => list.push(worry),
                                    }
                                },
                            }
                        }
                    }
                }
            }

            Spacer { weight: 1.0 }

            Row {
                fill_max_width: true,
                space_role: SpaceRole::Md,
                alignment: Alignment::CenterStart,
                Button {
                    text: "Skip",
                    variant: ButtonVariant::Text,
                    on_click: move |_| on_skip.call(()),
                }
                Button {
                    text: "Next",
                    weight: 1.0,
                    variant: ButtonVariant::Filled,
                    on_click: move |_| on_next.call(()),
                }
            }
        }
    }
}

/// What the check-in leaves behind: the day, in one line, and a way back into it.
fn checked_in(chosen: Mood, worries: Vec<&'static str>, again: EventHandler<()>) -> Element {
    let (fill, ink) = chosen.pair();
    let summary = if worries.is_empty() {
        "Nothing in particular.".to_owned()
    } else {
        worries.join(", ")
    };
    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,
            Column {
                fill_max_width: true,
                background: Paint::Literal(fill),
                shape_role: ShapeRole::Large,
                padding_role: SpaceRole::Lg,
                space_role: SpaceRole::Sm,
                alignment: Alignment::Center,
                Image {
                    width: FACE_SIDE * 0.6,
                    height: FACE_SIDE * 0.6,
                    asset_id: asset(AssetKind::Svg, chosen.picture()),
                }
                Text {
                    text: "Today felt {chosen.label().to_lowercase()}.",
                    type_role: TypeRole::Title,
                    color: Paint::Literal(ink),
                    text_align: TextAlign::Center,
                }
                Text {
                    text: summary,
                    type_role: TypeRole::Body,
                    color: Paint::Literal(ink),
                    text_align: TextAlign::Center,
                }
            }
            Button {
                text: "Check in again",
                fill_max_width: true,
                variant: ButtonVariant::Tonal,
                on_click: move |_| again.call(()),
            }
            Spacer { weight: 1.0 }
        }
    }
}

/// One session, as a card in the row that runs off the edge.
fn session_card(session: &Session) -> Element {
    let (fill, ink) = session.mood.pair();
    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            background: Paint::Literal(fill),
            shape_role: ShapeRole::Large,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Xs,
            Text {
                text: session.title,
                type_role: TypeRole::Subtitle,
                color: Paint::Literal(ink),
                max_lines: 2,
                overflow: TextOverflow::Ellipsis,
            }
            Spacer { weight: 1.0 }
            Row {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::CenterStart,
                Text {
                    text: "{session.minutes} min",
                    type_role: TypeRole::Label,
                    color: Paint::Literal(ink),
                }
                Spacer { weight: 1.0 }
                Button {
                    text: "\u{25b6}",
                    variant: ButtonVariant::Filled,
                    on_click: move |_| {
                        Message::new("Playback is not part of this sample").show();
                    },
                }
            }
        }
    }
}

/// The listening screen: a greeting, a search field and the sessions.
fn listen_page() -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,

            Text { text: "Hello, Paul", type_role: TypeRole::Headline }
            TextField { fill_max_width: true, placeholder: "Search" }

            Text {
                text: "Picked for today",
                type_role: TypeRole::Label,
                color: Paint::Role(ColorRole::OnSurfaceVariant),
            }
            LazyRow {
                fill_max_width: true,
                height: CARD_HEIGHT,
                item_count: SESSIONS.len(),
                key_of: move |position: usize| SESSIONS[position].title.to_owned(),
                item: move |position: usize| {
                    rsx! {
                        compose_rust::Box {
                            width: CARD_WIDTH,
                            fill_max_height: true,
                            padding_role: SpaceRole::Xs,
                            {session_card(&SESSIONS[position])}
                        }
                    }
                },
            }

            Text {
                text: "Special for you",
                type_role: TypeRole::Label,
                color: Paint::Role(ColorRole::OnSurfaceVariant),
            }
            Surface {
                fill_max_width: true,
                shape_role: ShapeRole::Large,
                Column {
                    fill_max_width: true,
                    for (index, session) in SESSIONS.iter().enumerate() {
                        Column { key: "{session.title}", fill_max_width: true,
                            Row {
                                fill_max_width: true,
                                padding_role: SpaceRole::Md,
                                space_role: SpaceRole::Sm,
                                alignment: Alignment::CenterStart,
                                Column {
                                    weight: 1.0,
                                    space_role: SpaceRole::Xs,
                                    Text {
                                        text: session.title,
                                        type_role: TypeRole::Body,
                                        max_lines: 1,
                                        overflow: TextOverflow::Ellipsis,
                                    }
                                    Text {
                                        text: "{session.minutes} min \u{00b7} {session.when}",
                                        type_role: TypeRole::Caption,
                                        color: Paint::Role(ColorRole::OnSurfaceVariant),
                                    }
                                }
                                Button {
                                    text: "\u{25b6}",
                                    variant: ButtonVariant::Tonal,
                                    on_click: move |_| {
                                        Message::new("Playback is not part of this sample")
                                            .show();
                                    },
                                }
                            }
                            if index + 1 < SESSIONS.len() {
                                Separator {}
                            }
                        }
                    }
                }
            }
        }
    }
}

/// Everything there is, as a plain list.
fn library_page() -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,
            Text { text: "Library", type_role: TypeRole::Headline }
            Column {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                for session in SESSIONS {
                    Row {
                        key: "{session.title}",
                        fill_max_width: true,
                        space_role: SpaceRole::Sm,
                        alignment: Alignment::CenterStart,
                        compose_rust::Box {
                            width: 48.0,
                            height: 48.0,
                            background: Paint::Literal(session.mood.pair().0),
                            shape_role: ShapeRole::Medium,
                        }
                        Column {
                            weight: 1.0,
                            Text {
                                text: session.title,
                                type_role: TypeRole::Body,
                                max_lines: 1,
                                overflow: TextOverflow::Ellipsis,
                            }
                            Text {
                                text: "{session.minutes} min \u{00b7} {session.when}",
                                type_role: TypeRole::Caption,
                                color: Paint::Role(ColorRole::OnSurfaceVariant),
                            }
                        }
                    }
                }
            }
        }
    }
}

/// The profile: who this is, how the week went, and the settings under it.
fn profile_page(chosen: Mood) -> Element {
    let (fill, ink) = chosen.pair();
    rsx! {
        Column {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,

            Column {
                fill_max_width: true,
                space_role: SpaceRole::Xs,
                alignment: Alignment::Center,
                Image {
                    width: 96.0,
                    height: 96.0,
                    asset_id: asset(AssetKind::Svg, chosen.picture()),
                }
                Text { text: "Paul Wilson", type_role: TypeRole::Title }
                Text {
                    text: chosen.label(),
                    type_role: TypeRole::Label,
                    color: Paint::Literal(ink),
                    background: Paint::Literal(fill),
                    shape_role: ShapeRole::Full,
                    padding_role: SpaceRole::Sm,
                }
            }

            Surface {
                fill_max_width: true,
                shape_role: ShapeRole::Large,
                padding_role: SpaceRole::Md,
                Column {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    Text {
                        text: "This week",
                        type_role: TypeRole::Label,
                        color: Paint::Role(ColorRole::OnSurfaceVariant),
                    }
                    Canvas {
                        width: CHART.0,
                        height: CHART.1,
                        commands: week_line(
                            CHART.0,
                            CHART.1,
                            ColorRole::OnSurfaceVariant,
                            ColorRole::Primary,
                        ),
                    }
                }
            }

            // The quote card, which is the one place on the screen where a paragraph sits
            // on an accent fill rather than on a reading surface.
            Column {
                fill_max_width: true,
                background: Paint::Role(ColorRole::PrimaryContainer),
                shape_role: ShapeRole::Large,
                padding_role: SpaceRole::Md,
                space_role: SpaceRole::Xs,
                Text {
                    text: "\u{201c}",
                    type_role: TypeRole::Display,
                    color: Paint::Role(ColorRole::OnPrimaryContainer),
                }
                Text {
                    text: "Every day is a new opportunity for growth and positive change.",
                    type_role: TypeRole::Body,
                    color: Paint::Role(ColorRole::OnPrimaryContainer),
                }
            }

            Surface {
                fill_max_width: true,
                shape_role: ShapeRole::Large,
                Column {
                    fill_max_width: true,
                    for (index, entry) in ["Settings", "Language", "FAQ"].iter().enumerate() {
                        Column { key: "{entry}", fill_max_width: true,
                            Row {
                                fill_max_width: true,
                                padding_role: SpaceRole::Md,
                                alignment: Alignment::CenterStart,
                                Text { text: *entry, type_role: TypeRole::Body, weight: 1.0 }
                                Text {
                                    text: "\u{203a}",
                                    type_role: TypeRole::Body,
                                    color: Paint::Role(ColorRole::OnSurfaceVariant),
                                }
                            }
                            if index < 2 {
                                Separator {}
                            }
                        }
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

    let mut destination = use_signal(|| Destination::Today);
    let mut step = use_signal(|| Step::Feeling);
    let chosen = use_signal(|| Mood::Calm);
    let picked = use_signal(Vec::<&'static str>::new);

    let today = match step() {
        Step::Feeling => feeling_step(
            chosen,
            EventHandler::new(move |()| step.set(Step::Done)),
            EventHandler::new(move |()| step.set(Step::Worry)),
        ),
        Step::Worry => worry_step(
            picked,
            EventHandler::new(move |()| step.set(Step::Done)),
            EventHandler::new(move |()| step.set(Step::Done)),
        ),
        Step::Done => checked_in(
            chosen(),
            picked(),
            EventHandler::new(move |()| step.set(Step::Feeling)),
        ),
    };

    // The check-in runs to the window's edges, because the tint behind the face is the
    // screen rather than a card on it. The rest are pages that scroll.
    let scrolls = destination() != Destination::Today || step() != Step::Feeling;

    let body = match destination() {
        Destination::Today => today,
        Destination::Listen => listen_page(),
        Destination::Library => library_page(),
        Destination::Profile => profile_page(chosen()),
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
                    if scrolls {
                        ScrollColumn {
                            width: measure,
                            fill_max_width: measure.is_none(),
                            fill_max_height: true,
                            {body}
                        }
                    } else {
                        Column {
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
}

/// The design this sample draws, named once.
///
/// One design system everywhere, because the design is the product here rather than the
/// platform's convention, and dark because the check-in, which is the screen this opens
/// on and the screen the sample exists for, is black under a pastel panel in the
/// reference, and the worry picker is black throughout. The home and profile screens in
/// the same sheet are light, so this is the one reference whose halves disagree, and the
/// screen it opens on wins.
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
                .with_title("Self-care")
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

    /// Named for what it defends: the reference is a dark design, and a machine set the
    /// other way drew this sample light with nothing to compare against.
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

    /// The face on the check-in has to reach the Renderer as a picture. An `Image` whose
    /// id names nothing is a blank square, which on this screen is most of the screen.
    #[test]
    fn fr16_the_check_in_draws_a_face() {
        compose_rust::window::reset_window_size();
        let batch = Host::new(app)
            .rebuild()
            .expect("the first frame failed")
            .to_vec();
        let mutations = decode_batch(&batch).expect("the batch did not decode");
        let registered: Vec<u32> = mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::RegisterAsset {
                    asset_id, bytes, ..
                } if *bytes == Mood::Calm.picture() => Some(*asset_id),
                _ => None,
            })
            .collect();
        assert_eq!(
            registered.len(),
            1,
            "the face the check-in opens on was not registered"
        );
        let image = mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::Create {
                    node_id,
                    widget: WidgetKind::Image,
                } => Some(*node_id),
                _ => None,
            })
            .expect("the check-in has no image, so there is no face");
        assert!(
            mutations.iter().any(|mutation| matches!(
                mutation,
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Asset,
                    value: PropertyValue::Integer(id),
                } if *node_id == image && *id as u32 == registered[0]
            )),
            "the face draws an id that was never registered"
        );
        compose_rust::window::reset_window_size();
    }

    /// Choosing a mood changes the picture rather than only the label under it. A picker
    /// whose face does not follow the answer is a picker that looks broken.
    #[test]
    fn fr16_choosing_a_mood_redraws_the_face() {
        let mut screen = Screen::new();
        assert!(screen.press(Mood::Angry.label()), "no way to choose a mood");
        let changed = screen.frames.last().expect("a frame");
        let mutations = decode_batch(changed).expect("the batch did not decode");
        let angry = mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::RegisterAsset {
                    asset_id, bytes, ..
                } if *bytes == Mood::Angry.picture() => Some(*asset_id),
                _ => None,
            })
            .expect("the angry face was never registered");
        assert!(
            mutations.iter().any(|mutation| matches!(
                mutation,
                Mutation::SetProp {
                    property: PropertyKind::Asset,
                    value: PropertyValue::Integer(id),
                    ..
                } if *id as u32 == angry
            )),
            "the mood changed and the face did not"
        );
        compose_rust::window::reset_window_size();
    }

    /// The check-in is three steps and the last one has to be reachable. A flow whose end
    /// nobody walked to is a flow with an unencodable widget waiting in it.
    #[test]
    fn fr15_the_check_in_runs_to_the_end() {
        let mut screen = Screen::new();
        assert!(screen.press("Next"), "the feeling step has no way on");
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text == "What's worrying you?"),
            "the second step did not arrive"
        );
        assert!(screen.press(WORRIES[2]), "no worry can be chosen");
        assert!(screen.press("Next"), "the worry step has no way on");
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text.contains(WORRIES[2])),
            "the summary does not carry what was chosen"
        );
        compose_rust::window::reset_window_size();
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

    /// The check-in, in the design system it ships, in both schemes, at all three widths.
    #[test]
    fn fr16_the_check_in_is_recorded_in_the_system_it_ships() {
        sample_frames::record_as(
            "SelfCare",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |_| {},
        );
    }

    /// The profile, which is the other drawing: a week as a line, on a reading surface
    /// rather than on a tint.
    #[test]
    fn fr16_the_profile_is_recorded() {
        sample_frames::record_as(
            "SelfCareProfile",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    screen.press(Destination::Profile.label()),
                    "the bar has no way to the profile"
                );
            },
        );
    }

    /// The listening screen, which is where the session cards and the windowing row are.
    #[test]
    fn fr15_the_listening_screen_is_recorded() {
        sample_frames::record_as(
            "SelfCareListen",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    screen.press(Destination::Listen.label()),
                    "the bar has no way to the sessions"
                );
                assert_eq!(
                    screen.fill_lists(4),
                    1,
                    "the sessions row should be the screen's one windowing list"
                );
            },
        );
    }
}
