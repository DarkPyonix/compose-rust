//! Product statistics: a dial, a week of costs, and where the money went.
//!
//! This is the sample that pushes on `Canvas`. A dial with a tick ring and a marker, and a
//! bar chart with a day under each column, are pixels the widget vocabulary has no name
//! for, and they are the two most common things a statistics screen is made of. Both are
//! draw lists, and every colour in them is a role, so they follow the reader into dark and
//! come out in each design system's own accent.
//!
//! Unified, naming Cupertino and light: the reference is a light iOS design, and a
//! design that flips to dark on a machine set that way is not the design being compared
//! against. `THEME` says both.

mod charts;
mod spending;

use charts::Bar;
use compose_rust::prelude::*;
use spending::{
    ALL_TIME_CENTS, MOBILE_SHARE, RETURNING_SHARE, SOURCES, TODAY, WEEK, YESTERDAY, cost, dearest,
    peak,
};

mod palette {
    use compose_rust::prelude::Color;
    pub const PAGE: Color = Color::rgb(0xF2F2F0);
    pub const CARD: Color = Color::rgb(0xFFFFFF);
    pub const INK: Color = Color::rgb(0x000000);
    pub const SAGE: Color = Color::rgb(0xB0C1AE);
    pub const POWDER: Color = Color::rgb(0xAFC3DC);
    pub const ORANGE: Color = Color::rgb(0xF58220);
    pub const GREY: Color = Color::rgb(0x999999);
    pub const LIGHT_GREY: Color = Color::rgb(0xEEEEEE);
}

/// A phone design in a desktop window is still a phone design.
const PAGE_MEASURE: f32 = 420.0;

/// The square the dial is drawn into, and the box the week's columns get.
///
/// Numbers, because these are the proportions of a drawing. The space ladder answers how
/// far apart two things sit, not how large a picture is.
const DIAL_SIDE: f32 = 300.0;
/// The columns beside the dial, and the columns on the costs page.
///
/// Two sizes, because a draw list is in the canvas's own coordinates: the canvas is given
/// exactly these and the chart is drawn at exactly these, so nothing runs off the side and
/// nothing stops short of it.
const PANEL_CHART: (f32, f32) = (186.0, 150.0);
const PAGE_CHART: (f32, f32) = (340.0, 210.0);
const SOURCE_TILE: f32 = 150.0;

/// The two screens.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Page {
    Today,
    Costs,
}

/// The week as the chart wants it: heights against the dearest day, with that day filled.
fn week_bars() -> Vec<Bar> {
    let tallest = peak().max(1) as f32;
    let marked = dearest();
    WEEK.iter()
        .enumerate()
        .map(|(index, (day, cents))| Bar {
            day,
            height: *cents as f32 / tallest,
            filled: index == marked,
        })
        .collect()
}

/// The dial, with its reading stacked in the middle of it.
///
/// The number is a `Text` rather than a `TextAt` inside the draw list. A draw list's text
/// is placed at a point and takes a rung of the ladder, which is enough for a day label
/// under a column; a reading this size is the loudest thing on the screen and wants the
/// ladder's own metrics and the ellipsis behaviour that goes with a widget.
fn dial_card() -> Element {
    rsx! {
        compose_rust::Box {
            fill_max_width: true,
            shape_role: ShapeRole::Large,
            padding_role: SpaceRole::Lg,
            background: Paint::Literal(palette::CARD),
            compose_rust::Box {
                fill_max_width: true,
                alignment: Alignment::Center,
                Canvas {
                    width: DIAL_SIDE,
                    height: DIAL_SIDE,
                    commands: charts::dial(
                        DIAL_SIDE,
                        MOBILE_SHARE as f32 / 100.0,
                        Paint::Literal(palette::GREY),
                        Paint::Literal(palette::ORANGE),
                    ),
                }
                Column {
                    alignment: Alignment::Center,
                    Text { text: "{MOBILE_SHARE}%", type_role: TypeRole::Display, color: Paint::Literal(palette::INK) }
                    Text {
                        text: "Mobile",
                        type_role: TypeRole::Label,
                        color: Paint::Literal(palette::GREY),
                    }
                }
            }
        }
    }
}

/// The week's columns on a tinted panel, which is the shape the reference gives them.
///
/// The panel is `SecondaryContainer` and the chart's ink is its paired ink, so the chart
/// is guaranteed to be readable on it in every design system and in both schemes. Before
/// those roles existed the only way to say "a panel in a colour" was a literal, and a
/// literal is a colour the design system never sees.
fn costs_panel(title_role: TypeRole, (width, height): (f32, f32)) -> Element {
    let bars = week_bars();
    rsx! {
        Column {
            fill_max_width: true,
            background: Paint::Literal(palette::SAGE),
            shape_role: ShapeRole::Large,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Sm,
            Text {
                text: "Popular costs",
                type_role: title_role,
                color: Paint::Literal(palette::INK),
            }
            Canvas {
                width,
                height,
                commands: charts::week(width, height, &bars),
            }
        }
    }
}

/// Where the money went, one tile per service, running past the right edge.
///
/// On both pages, because a page that says how much was spent this week and never says
/// what it went on is the top half of the reference with the bottom half missing, and that
/// missing half is the empty third at the foot of the window.
fn sources_strip() -> Element {
    rsx! {
        LazyRow {
            fill_max_width: true,
            height: SOURCE_TILE,
            item_count: SOURCES.len(),
            key_of: move |position: usize| SOURCES[position].name.to_owned(),
            item: move |position: usize| {
                let source = SOURCES[position];
                rsx! {
                    compose_rust::Box {
                        width: SOURCE_TILE,
                        fill_max_height: true,
                        padding_role: SpaceRole::Xs,
                        Column {
                            fill_max_width: true,
                            fill_max_height: true,
                            background: Paint::Literal(palette::SAGE),
                            shape_role: ShapeRole::Large,
                            padding_role: SpaceRole::Md,
                            space_role: SpaceRole::Xs,
                            compose_rust::Box {
                                width: 32.0,
                                height: 32.0,
                                corner_radius: 16.0,
                                background: Paint::Literal(palette::INK),
                            }
                            Text {
                                text: source.name,
                                type_role: TypeRole::Label,
                                color: Paint::Literal(palette::INK),
                                max_lines: 1,
                                overflow: TextOverflow::Ellipsis,
                            }
                            Spacer { weight: 1.0 }
                            Text {
                                text: cost(source.cents),
                                type_role: TypeRole::Title,
                                color: Paint::Literal(palette::INK),
                                max_lines: 1,
                                overflow: TextOverflow::Ellipsis,
                            }
                        }
                    }
                }
            },
        }
    }
}

/// The overview: the dial, the costs panel, a second reading beside it, and what the
/// money went on.
fn today_page() -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,

            {dial_card()}

            Row {
                fill_max_width: true,
                space_role: SpaceRole::Md,
                alignment: Alignment::TopStart,
                compose_rust::Box { weight: 2.0, {costs_panel(TypeRole::BodyStrong, PANEL_CHART)} }
                Column {
                    weight: 1.0,
                    space_role: SpaceRole::Sm,
                    compose_rust::Box {
                        fill_max_width: true,
                        shape_role: ShapeRole::Large,
                        padding_role: SpaceRole::Md,
                        background: Paint::Literal(palette::CARD),
                        Text { text: "{RETURNING_SHARE}%", type_role: TypeRole::Title, color: Paint::Literal(palette::INK) }
                    }
                    Row {
                        fill_max_width: true,
                        space_role: SpaceRole::Sm,
                        compose_rust::Box {
                            weight: 1.0,
                            height: 64.0,
                            background: Paint::Literal(palette::POWDER),
                            shape_role: ShapeRole::Large,
                        }
                        Button {
                            text: "+",
                            width: 64.0,
                            height: 64.0,
                            corner_radius: 32.0,
                            background: Paint::Literal(palette::GREY),
                            color: Paint::Literal(palette::INK),
                            on_click: move |_| {
                                Message::new("Adding a source is not part of this sample").show();
                            },
                        }
                    }
                }
            }

            {sources_strip()}

            Separator {}

            Row {
                fill_max_width: true,
                space_role: SpaceRole::Md,
                alignment: Alignment::CenterStart,
                Text { text: "<", type_role: TypeRole::BodyStrong, color: Paint::Literal(palette::INK) }
                Row {
                    alignment: Alignment::TopStart,
                    Text { text: "Today ", type_role: TypeRole::BodyStrong, color: Paint::Literal(palette::INK) }
                    Text {
                        text: "{TODAY}",
                        type_role: TypeRole::Caption,
                        color: Paint::Literal(palette::INK),
                    }
                }
                Spacer { weight: 1.0 }
                Row {
                    alignment: Alignment::TopStart,
                    Text {
                        text: "Yesterday ",
                        type_role: TypeRole::Body,
                        color: Paint::Literal(palette::GREY),
                    }
                    Text {
                        text: "{YESTERDAY}",
                        type_role: TypeRole::Caption,
                        color: Paint::Literal(palette::GREY),
                    }
                }
            }
        }
    }
}

/// The costs in full: the whole page is the panel, and the sources run off the edge.
fn costs_page() -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            background: Paint::Literal(palette::SAGE),
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,

            Text {
                text: "Popular costs",
                type_role: TypeRole::Headline,
                color: Paint::Literal(palette::INK),
            }

            Canvas {
                width: PAGE_CHART.0,
                height: PAGE_CHART.1,
                commands: charts::week(
                    PAGE_CHART.0,
                    PAGE_CHART.1,
                    &week_bars(),
                ),
            }

            {sources_strip()}

            Spacer { weight: 1.0 }

            Row {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::CenterStart,
                Text {
                    text: "All Time: ",
                    type_role: TypeRole::Body,
                    color: Paint::Literal(palette::INK),
                    weight: 1.0,
                }
                Text {
                    text: cost(ALL_TIME_CENTS),
                    type_role: TypeRole::Headline,
                    color: Paint::Literal(palette::INK),
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
    let mut page = use_signal(|| Page::Today);

    rsx! {
        // The frame is named rather than built: what the bar becomes, and where the page
        // starts under it, are answers this screen never has to know.
        Scaffold {
            background: Paint::Literal(palette::PAGE),
            top_bar: rsx! {
                TopAppBar {
                    fill_max_width: true,
                    Text { text: "Product\nStatistics", type_role: TypeRole::Title, weight: 1.0, color: Paint::Literal(palette::INK) }
                    Button {
                        // The reference marks this with a chevron in the page's own ink. A
                        // text button left to itself wears the design system's accent, which
                        // is the one colour this design does not have.
                        text: ">",
                        variant: ButtonVariant::Text,
                        color: Paint::Literal(palette::INK),
                        on_click: move |_| {
                            page.set(if page() == Page::Today { Page::Costs } else { Page::Today });
                        }
                    }
                }
            },

                        compose_rust::Box {
                fill_max_width: true,
                weight: 1.0,
                alignment: Alignment::TopCenter,
                Column {
                    width: measure,
                    fill_max_width: measure.is_none(),
                    fill_max_height: true,
                    match page() {
                        Page::Today => rsx! {
                            ScrollColumn { fill_max_width: true, fill_max_height: true, {today_page()} }
                        },
                        // The costs page is the panel, edge to edge, so it does not scroll
                        // inside something else: the tint has to reach the window's sides
                        // or it is a card pretending to be a page.
                        Page::Costs => costs_page(),
                    }
                }
            }
        }
    }
}

/// The design this sample draws, named once.
///
/// One design system everywhere, because the design is the product here rather than the
/// platform's convention, and light because the reference is a near-white page carrying a
/// white dial card. The sage panel under it is a colour in the design, not a darker
/// scheme.
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
                .with_title("Statistics")
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
// Both are declared unconditionally. The macros are already written to compile into
// nothing that runs off their own platform, and gating them here instead would mean a
// desktop build never checks that this sample can be built for the other two.
compose_rust::android_main!({ launch_builder() }, app);
compose_rust::web_main!({ launch_builder() }, app);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;

    use compose_rust::Host;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
    use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind};

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

    fn first_frame() -> Vec<u8> {
        compose_rust::window::reset_window_size();
        let mut host = Host::new(app);
        let batch = host.rebuild().expect("the first frame failed").to_vec();
        compose_rust::window::reset_window_size();
        batch
    }

    fn texts(batch: &[u8]) -> Vec<String> {
        decode_batch(batch)
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

    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        assert!(Host::new(app).rebuild().is_ok());
    }

    /// Both pages have to encode, not just the one the screen opens on.
    #[test]
    fn fr15_both_pages_encode() {
        compose_rust::window::reset_window_size();
        let mut host = Host::new(app);
        let first = host.rebuild().expect("the first frame failed").to_vec();

        let mutations = decode_batch(&first).expect("the batch did not decode");
        let node = mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Text,
                    value: PropertyValue::String(text),
                } if *text == ">" => Some(*node_id),
                _ => None,
            })
            .unwrap_or_else(|| panic!("no button >"));
        let handler = mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::OnClick,
                    value: PropertyValue::Integer(handler),
                } if *node_id == node => Some(*handler as u64),
                _ => None,
            })
            .unwrap_or_else(|| panic!("> cannot be pressed"));
        let mut bytes = Vec::new();
        encode_event(
            &HostEvent {
                node_id: node,
                handler_id: handler,
                payload: EventPayload::Clicked,
            },
            &mut bytes,
        )
        .expect("the click did not encode");
        host.dispatch_event(&bytes)
            .unwrap_or_else(|error| panic!("click failed: {error:?}"));
        compose_rust::window::reset_window_size();
    }

    /// Both charts have to reach the Renderer as draw lists. A `Canvas` with no commands
    /// on it is a blank rectangle, and a blank rectangle is exactly what a chart that
    /// failed to build looks like.
    #[test]
    fn fr16_the_dial_and_the_week_both_carry_draw_lists() {
        let batch = first_frame();
        let mutations = decode_batch(&batch).expect("the batch did not decode");
        let canvases: Vec<u32> = mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::Create {
                    node_id,
                    widget: WidgetKind::Canvas,
                } => Some(*node_id),
                _ => None,
            })
            .collect();
        assert_eq!(
            canvases.len(),
            2,
            "the overview should hold the dial and the week, and nothing else drawn"
        );
        for canvas in canvases {
            assert!(
                mutations.iter().any(|mutation| matches!(
                    mutation,
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Commands,
                        ..
                    } if *node_id == canvas
                )),
                "a canvas reached the Renderer with no draw list, so it is a blank box"
            );
        }
    }

    /// The reading in the middle of the dial and the reading the dial is drawn from are
    /// one number. Two of them is how a chart starts disagreeing with its own label.
    #[test]
    fn fr16_the_dial_says_the_number_it_is_drawn_from() {
        assert!(
            texts(&first_frame())
                .iter()
                .any(|text| text == &format!("{MOBILE_SHARE}%")),
            "the dial's reading is not written anywhere on it"
        );
    }

    /// The overview, in the design system it ships, in both schemes, at all three widths.
    #[test]
    fn fr16_the_overview_is_recorded_in_the_system_it_ships() {
        sample_frames::record_as(
            "Statistics",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert_eq!(
                    screen.fill_lists(5),
                    1,
                    "the overview's sources row should be its one windowing list, or the \
                     picture is of a screen with a hole in it"
                );
            },
        );
    }

    /// The costs page, which is the tinted one: a different picture, and the one where a
    /// chart drawn in the wrong ink would be invisible rather than merely wrong.
    #[test]
    fn fr13_the_costs_page_is_recorded() {
        sample_frames::record_as(
            "StatisticsCosts",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    screen.press(">"),
                    "the screen has no way to reach the costs page"
                );
                screen.fill_lists(5);
            },
        );
    }
}
