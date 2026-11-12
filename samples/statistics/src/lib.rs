//! Product statistics: a dial, a week of costs, and where the money went.
//!
//! This is the sample that pushes on `Canvas`. A dial with a tick ring and a marker, and a
//! bar chart with a day under each column, are pixels the widget vocabulary has no name
//! for, and they are the two most common things a statistics screen is made of. Both are
//! draw lists, painted in this sample's own palette, because the reference names colours
//! no role can say.
//!
//! Unified, naming Cupertino and light: the reference is a light iOS design, and a
//! design that flips to dark on a machine set that way is not the design being compared
//! against. `THEME` says both.

mod charts;
mod palette;
mod spending;

use charts::Bar;
use compose_rust::AssetKind;
use compose_rust::runtime::*;
use compose_rust::ui::*;
use spending::{
    ALL_TIME_CENTS, MOBILE_SHARE, RETURNING_SHARE, SOURCES, Source, TODAY, WEEK, YESTERDAY, cost,
    dearest, peak, whole_cost,
};

/// A phone design in a desktop window is still a phone design.
const PAGE_MEASURE: f32 = 420.0;

/// The square the dial is drawn into.
///
/// Numbers, because these are the proportions of a drawing. The space ladder answers how
/// far apart two things sit, not how large a picture is.
const DIAL_SIDE: f32 = 300.0;
/// The columns on the overview's panel, and the columns on the costs page.
///
/// Two sizes, because a draw list is in the canvas's own coordinates: the canvas is given
/// exactly these and the chart is drawn at exactly these, so nothing runs off the side and
/// nothing stops short of it.
const PANEL_CHART: (f32, f32) = (200.0, 170.0);
const PAGE_CHART: (f32, f32) = (340.0, 280.0);
/// One service tile on the costs page.
const SOURCE_TILE: (f32, f32) = (160.0, 210.0);
/// The round "+" under the second reading: about as wide as the column it stands in.
const ADD_BUTTON: f32 = 104.0;
/// The chevron beside the title.
const CHEVRON: f32 = 28.0;

/// The two screens.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Page {
    Today,
    Costs,
}

impl Page {
    /// What the bar says on each page. The reference changes the title with the page
    /// rather than keeping one title and putting a heading under it.
    fn title(self) -> &'static str {
        match self {
            Page::Today => "Product\nStatistics",
            Page::Costs => "Popular\nCosts",
        }
    }

    /// The colour the whole window is on this page, behind the bar as well.
    fn ground(self) -> Paint {
        match self {
            Page::Today => palette::PAGE,
            Page::Costs => palette::SAGE,
        }
    }

    /// What the chevron does, said to assistive technology since nothing on screen says it.
    fn turn_label(self) -> &'static str {
        match self {
            Page::Today => "Show popular costs",
            Page::Costs => "Show product statistics",
        }
    }

    fn other(self) -> Self {
        match self {
            Page::Today => Page::Costs,
            Page::Costs => Page::Today,
        }
    }
}

/// The icon the foot of the overview uses, as the bytes its registration carries: the
/// meaning's wire tag, little endian. The design system draws it, so under Cupertino it is
/// the thin chevron the reference has.
mod icon {
    use compose_rust::ui::IconRole;

    pub static BACK: [u8; 2] = (IconRole::Back as u16).to_le_bytes();
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

/// A control whose face is a drawing: the fill, the mark, and the press over it.
///
/// The button carries no text and sits over the drawing, so the press lands on it
/// whatever was drawn underneath. Its name is for assistive technology.
fn drawn_button(
    size: f32,
    fill: Option<Paint>,
    face: DrawList,
    face_size: f32,
    name: &str,
    on_click: impl Fn() + 'static,
) {
    let mut frame = Modifier.size(size, size).shape_role(ShapeRole::Full);
    if let Some(fill) = fill {
        frame = frame.background(fill);
    }
    Box()
        .modifier(frame)
        .alignment(Alignment::Center)
        .content(|| {
            Canvas(face).modifier(Modifier.size(face_size, face_size));
            Button("")
                .variant(ButtonVariant::Text)
                .modifier(Modifier.fill_max_size().label(name))
                .on_click(on_click);
        });
}

/// The dial, with its reading stacked in the middle of it.
///
/// It stands straight on the page: the white face is the first thing the drawing paints,
/// so there is no card behind it, which is how the reference has it.
///
/// The number is a `Text` rather than a `TextAt` inside the draw list. A draw list's text
/// is placed at a point and takes a rung of the ladder, which is enough for a day label
/// under a column; a reading this size is the loudest thing on the screen and wants the
/// ladder's own metrics.
#[Composable]
fn dial() {
    Box()
        .modifier(Modifier.fill_max_width())
        .alignment(Alignment::Center)
        .content(|| {
            Canvas(charts::dial(
                DIAL_SIDE,
                MOBILE_SHARE as f32 / 100.0,
                palette::TICK,
                palette::ORANGE,
            ))
            .modifier(Modifier.size(DIAL_SIDE, DIAL_SIDE));
            Column().alignment(Alignment::Center).content(|| {
                Text(format!("{MOBILE_SHARE}%"))
                    .type_role(TypeRole::Display)
                    .font_weight(400)
                    .color(palette::INK);
                Text("Mobile")
                    .type_role(TypeRole::Label)
                    .font_weight(600)
                    .color(palette::GREY);
            });
        });
}

/// The week's columns on the sage panel, with the panel's two-line title over them.
#[Composable]
fn costs_panel() {
    let (width, height) = PANEL_CHART;
    let bars = week_bars();
    Column()
        .modifier(
            Modifier
                .fill_max_width()
                .background(palette::SAGE)
                .shape_role(ShapeRole::Medium)
                .padding_role(SpaceRole::Sm),
        )
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("Popular\nCosts")
                .type_role(TypeRole::BodyStrong)
                .font_weight(700)
                .color(palette::INK);
            Box()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::Center)
                .content(|| {
                    Canvas(charts::week(width, height, &bars))
                        .modifier(Modifier.size(width, height));
                });
        });
}

/// The column beside the panel: one white card holding the second reading over a powder
/// block, and the round "+" under it.
#[Composable]
fn returning_column() {
    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Sm)
        .alignment(Alignment::TopCenter)
        .content(|| {
            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(palette::CARD)
                        .shape_role(ShapeRole::Medium)
                        .padding_role(SpaceRole::Xs),
                )
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::TopCenter)
                .content(|| {
                    Text(format!("{RETURNING_SHARE}%"))
                        .type_role(TypeRole::Headline)
                        .font_weight(400)
                        .color(palette::INK);
                    Box().modifier(
                        Modifier
                            .fill_max_width()
                            .height(80.0)
                            .background(palette::POWDER)
                            .shape_role(ShapeRole::Medium),
                    );
                });
            drawn_button(
                ADD_BUTTON,
                Some(palette::BUTTON),
                charts::plus(40.0, palette::CARD),
                40.0,
                "Add a source",
                || {
                    Message::new("Adding a source is not part of this sample").show();
                },
            );
        });
}

/// One number at the foot with a smaller number raised beside it.
#[Composable]
fn day_total(day: &'static str, count: u32, ink: Paint) {
    Row().alignment(Alignment::TopStart).content(|| {
        Text(day)
            .type_role(TypeRole::Title)
            .font_weight(700)
            .color(ink);
        Text(count.to_string())
            .type_role(TypeRole::Caption)
            .color(ink);
    });
}

/// The overview: the dial, the costs panel with a second reading beside it, and the days
/// at the foot. Nothing else: the reference keeps the services for the costs page.
#[Composable]
fn today_page() {
    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            dial();

            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::TopStart)
                .content(|| {
                    Box().modifier(Modifier.weight(2.0)).content(costs_panel);
                    Box()
                        .modifier(Modifier.weight(1.0))
                        .content(returning_column);
                });

            // Today in the middle, with the chevron and yesterday taking equal room on
            // either side of it, so it is centred however wide the two are.
            Row()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Box()
                        .modifier(Modifier.weight(1.0))
                        .alignment(Alignment::CenterStart)
                        .content(|| {
                            Icon(asset(AssetKind::VectorIcon, &icon::BACK)).color(palette::GREY);
                        });
                    day_total("Today", TODAY, palette::INK);
                    Box()
                        .modifier(Modifier.weight(1.0))
                        .alignment(Alignment::CenterEnd)
                        .content(|| day_total("Yesterday", YESTERDAY, palette::FAINT));
                });
        });
}

/// One service: a badge where its logo would be, its name, and what it cost.
///
/// The badge carries the service's initial rather than its logo. The logos are other
/// companies' marks, and this sample does not copy them.
#[Composable]
fn source_tile(source: Source) {
    let (width, _) = SOURCE_TILE;
    Box()
        .modifier(
            Modifier
                .width(width)
                .fill_max_height()
                .padding_role(SpaceRole::Xs),
        )
        .content(|| {
            Column()
                .modifier(
                    Modifier
                        .fill_max_size()
                        .background(palette::TILE)
                        .shape_role(ShapeRole::Large)
                        .padding_role(SpaceRole::Sm),
                )
                .space_role(SpaceRole::Xs)
                .content(|| {
                    Box()
                        .modifier(
                            Modifier
                                .size(44.0, 44.0)
                                .shape_role(ShapeRole::Full)
                                .background(palette::BADGE),
                        )
                        .alignment(Alignment::Center)
                        .content(|| {
                            Text(&source.name[..1])
                                .type_role(TypeRole::BodyStrong)
                                .font_weight(700)
                                .color(palette::INK);
                        });
                    Spacer().modifier(Modifier.weight(1.0));
                    Text(source.name)
                        .type_role(TypeRole::Label)
                        .color(palette::SAGE_INK)
                        .max_lines(1)
                        .overflow(TextOverflow::Ellipsis);
                    Text(whole_cost(source.cents))
                        .type_role(TypeRole::Display)
                        .color(palette::INK)
                        .max_lines(1)
                        .overflow(TextOverflow::Ellipsis);
                });
        });
}

/// Where the money went, one tile per service, running past the right edge.
#[Composable]
fn sources_strip() {
    LazyRow()
        .modifier(Modifier.fill_max_width().height(SOURCE_TILE.1))
        .content(|list| {
            list.items_keyed(
                SOURCES.len(),
                |position| SOURCES[position].name.to_owned(),
                |position| source_tile(SOURCES[position]),
            );
        });
}

/// The costs in full: the week large, the services under it, and the total at the foot.
///
/// The sage is not painted here. It is the window's own colour on this page, behind the
/// bar and out to both edges at every width, so this column only lays things out on it.
#[Composable]
fn costs_page() {
    Column()
        .modifier(
            Modifier
                .fill_max_width()
                .fill_max_height()
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Md)
        .content(|| {
            Spacer().modifier(Modifier.weight(1.0));

            Box()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::Center)
                .content(|| {
                    Canvas(charts::week(PAGE_CHART.0, PAGE_CHART.1, &week_bars()))
                        .modifier(Modifier.size(PAGE_CHART.0, PAGE_CHART.1));
                });

            sources_strip();

            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Text("All Time:")
                        .type_role(TypeRole::Body)
                        .color(palette::SAGE_INK)
                        .modifier(Modifier.weight(1.0));
                    Text(cost(ALL_TIME_CENTS))
                        .type_role(TypeRole::Headline)
                        .font_weight(400)
                        .color(palette::INK);
                });
        });
}

/// The bar across the top of the window: the page's title in two bold lines, and the
/// chevron that turns to the other page.
///
/// The bar is painted in the page's own colour, so the costs page's sage runs behind it
/// to the top of the window as the reference's does. Its row is held to the page's
/// measure, so the title stands over the content rather than at the window's far edge.
#[Composable]
fn page_bar(showing: Page, measure: Option<f32>, turn: impl Fn() + 'static) {
    TopAppBar()
        .modifier(Modifier.fill_max_width().background(showing.ground()))
        .content(|| {
            Box()
                .modifier(Modifier.weight(1.0))
                .alignment(Alignment::Center)
                .content(|| {
                    Row()
                        .modifier(
                            Modifier
                                .width_if(measure)
                                .fill_max_width_if(measure.is_none())
                                .padding_role_if(measure.map(|_| SpaceRole::Md)),
                        )
                        .space_role(SpaceRole::Sm)
                        .alignment(Alignment::CenterStart)
                        .content(|| {
                            Text(showing.title())
                                .type_role(TypeRole::Headline)
                                .font_weight(700)
                                .color(palette::INK)
                                .modifier(Modifier.weight(1.0));
                            drawn_button(
                                CHEVRON + 16.0,
                                None,
                                charts::chevron_down(CHEVRON, palette::INK),
                                CHEVRON,
                                showing.turn_label(),
                                turn,
                            );
                        });
                });
        });
}

#[Composable]
pub fn app() {
    let window = current_window_size();
    let measure = if window.is_compact() {
        None
    } else {
        Some(PAGE_MEASURE)
    };
    let page = remember(|| mutable_state_of(Page::Today));
    let showing = page.get();

    // The frame is named rather than built: what the bar becomes, and where the page
    // starts under it, are answers this screen never has to know. Its background is the
    // page's, so whichever page is showing is the colour of the whole window, edge to
    // edge, at every width.
    Scaffold()
        .modifier(Modifier.background(showing.ground()))
        .top_bar(|| {
            let turn = page;
            page_bar(showing, measure, move || {
                turn.set(turn.get_untracked().other());
            });
        })
        .content(|| {
            Box()
                .modifier(Modifier.fill_max_width().weight(1.0))
                .alignment(Alignment::TopCenter)
                .content(|| {
                    Column()
                        .modifier(
                            Modifier
                                .width_if(measure)
                                .fill_max_width_if(measure.is_none())
                                .fill_max_height(),
                        )
                        .content(|| match showing {
                            Page::Today => {
                                ScrollColumn()
                                    .modifier(Modifier.fill_max_width().fill_max_height())
                                    .content(today_page);
                            }
                            Page::Costs => costs_page(),
                        });
                });
        });
}

/// The design this sample draws, named once.
///
/// One design system everywhere, because the design is the product here rather than the
/// platform's convention, and light because the reference is a near-white page carrying a
/// white dial. The sage panel under it is a colour in the design, not a darker
/// scheme.
///
/// The scheme is said out loud rather than left to follow the machine. `Theme::unified`
/// settles which design system is drawn and nothing else, so without this line a reader
/// whose system is set the other way sees a screen the design was never drawn for.
const THEME: Theme = Theme::unified(DesignSystem::Cupertino).with_color_scheme(ColorScheme::Light);

/// Runs the sample as a program of its own. The desktop binary is one line of this.
pub fn launch() {
    launch_builder().launch(app);
}

/// How this sample is configured, in one place because three entry points need it.
///
/// The theme above all: a sample that names one and then reaches a platform through an
/// entry point that makes its own builder is a sample that draws the same screens in a
/// different design system depending on where it runs. `demo_theme_for` rather than
/// `THEME` alone: a sample is something to look at, and one machine can only show the
/// design system and the scheme it is set to. `DXC_DESIGN` and `DXC_SCHEME` each override
/// the half they name, so `THEME` stays the answer to everything nobody asked about.
fn launch_builder() -> LaunchBuilder {
    // The name the window carries. A desktop lists windows by it, so a window that said
    // nothing was listed under whatever the renderer happened to be called.
    LaunchBuilder::new()
        .with_theme(compose_rust::demo_theme_for(THEME))
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Statistics")
                // Without one the window wears the toolkit's picture wherever the system
                // lists windows. The bytes travel as an asset and the renderer refers to
                // them by id: a path would be a fact about the machine this was built on.
                .with_icon(compose_rust::asset::asset(
                    compose_rust::schema::AssetKind::Png,
                    include_bytes!("../assets/icon.png"),
                )),
        )
}

// The platforms where the sample is not a program. Android's Activity and the browser's
// page both own the loop, so neither has a `main` to call: each names an entry point that
// registers the root composable, and these macros define it.
//
// Both are declared unconditionally. The macros compile into nothing that runs off their
// own platform, and gating them here instead would mean a desktop build never checks that
// this sample can be built for the other two.
compose_rust::android_application!({ launch_builder() }, app);
compose_rust::web_application!({ launch_builder() }, app);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;

    use compose_rust::ComposeHost as Host;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
    use compose_rust::schema::{
        EventPayload, Modifier as WireModifier, PropertyKind, WidgetKind, WindowHeightClass,
        WindowSizeClass,
    };

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

    /// The screen at one window width, and everything it has said so far.
    ///
    /// Its own rather than the recorder's, because these checks press things and look at
    /// the result, and a recorded screen is a picture written out under its name.
    struct Screen {
        host: Host,
        frames: Vec<Vec<u8>>,
    }

    impl Screen {
        fn at(width_dp: f32) -> Self {
            compose_rust::window::reset_window_size();
            let mut host = Host::with_theme(app, THEME);
            let first = host.rebuild().expect("the first frame failed").to_vec();
            let mut screen = Self {
                host,
                frames: vec![first],
            };
            let height_dp = 780.0;
            screen.send(HostEvent {
                node_id: 0,
                handler_id: 0,
                payload: EventPayload::WindowSizeChanged {
                    width_dp,
                    height_dp,
                    class: WindowSizeClass::from_width_dp(width_dp),
                    height_class: WindowHeightClass::from_height_dp(height_dp),
                    safe_area_top: 0.0,
                    safe_area_leading: 0.0,
                    safe_area_trailing: 0.0,
                },
            });
            screen
        }

        fn send(&mut self, event: HostEvent<'_>) {
            let mut bytes = Vec::new();
            encode_event(&event, &mut bytes).expect("the event did not encode");
            let (batch, _) = self.host.dispatch_event(&bytes).expect("the event failed");
            if !batch.is_empty() {
                self.frames.push(batch.to_vec());
            }
        }

        /// What is on screen now.
        fn live(&self) -> Vec<Mutation<'_>> {
            sample_frames::live(
                self.frames
                    .iter()
                    .flat_map(|frame| decode_batch(frame).expect("a batch did not decode"))
                    .collect(),
            )
        }

        /// Presses the chevron beside the bar's title: the one control in the bar.
        fn turn(&mut self) {
            let (node_id, handler_id) = {
                let live = self.live();
                let bar = created(&live, WidgetKind::TopAppBar)[0];
                let mut family = vec![bar];
                let mut found = None;
                while let Some(next) = family.pop() {
                    if let Some(handler) = live.iter().find_map(|mutation| match mutation {
                        Mutation::SetProp {
                            node_id,
                            property: PropertyKind::OnClick,
                            value: PropertyValue::Integer(id),
                        } if *node_id == next => Some(*id as u64),
                        _ => None,
                    }) {
                        found = Some((next, handler));
                        break;
                    }
                    family.extend(live.iter().filter_map(|mutation| match mutation {
                        Mutation::Insert {
                            parent_id, node_id, ..
                        } if *parent_id == next => Some(*node_id),
                        _ => None,
                    }));
                }
                found.expect("nothing in the bar can be pressed")
            };
            self.send(HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::Clicked,
            });
        }
    }

    impl Drop for Screen {
        fn drop(&mut self) {
            compose_rust::window::reset_window_size();
        }
    }

    /// The words on screen: the last text each node was given, since a title that changes
    /// is the same node saying something new.
    fn texts(mutations: &[Mutation<'_>]) -> Vec<String> {
        let mut said: Vec<(u32, String)> = Vec::new();
        for mutation in mutations {
            if let Mutation::SetProp {
                node_id,
                property: PropertyKind::Text,
                value: PropertyValue::String(text),
            } = mutation
            {
                match said.iter_mut().find(|(node, _)| node == node_id) {
                    Some(entry) => entry.1 = (*text).to_owned(),
                    None => said.push((*node_id, (*text).to_owned())),
                }
            }
        }
        said.into_iter().map(|(_, text)| text).collect()
    }

    /// The last background each node was given, in the order the nodes first got one.
    fn backgrounds(mutations: &[Mutation<'_>]) -> Vec<(u32, Paint)> {
        let mut found: Vec<(u32, Paint)> = Vec::new();
        for mutation in mutations {
            if let Mutation::SetModifier {
                node_id,
                modifier: WireModifier::Background(paint),
                ..
            } = mutation
            {
                match found.iter_mut().find(|(node, _)| node == node_id) {
                    Some(entry) => entry.1 = *paint,
                    None => found.push((*node_id, *paint)),
                }
            }
        }
        found
    }

    fn background_of(mutations: &[Mutation<'_>], node: u32) -> Option<Paint> {
        backgrounds(mutations)
            .into_iter()
            .find_map(|(found, paint)| (found == node).then_some(paint))
    }

    fn created(mutations: &[Mutation<'_>], widget: WidgetKind) -> Vec<u32> {
        mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::Create {
                    node_id,
                    widget: found,
                } if *found == widget => Some(*node_id),
                _ => None,
            })
            .collect()
    }

    /// Every fill and border on the screen is one of the palette's literals.
    fn assert_painted_from_the_palette(mutations: &[Mutation<'_>]) {
        for mutation in mutations {
            if let Mutation::SetModifier {
                modifier: WireModifier::Background(paint) | WireModifier::Border { paint, .. },
                node_id,
                ..
            } = mutation
            {
                assert!(
                    matches!(paint, Paint::Literal(_)),
                    "node {node_id} is filled with {paint:?}, which the design system picks"
                );
                assert!(
                    palette::ALL.contains(paint),
                    "node {node_id} is filled with {paint:?}, which the palette does not hold"
                );
            }
        }
    }

    const WIDTHS: [f32; 3] = [420.0, 760.0, 1180.0];

    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        assert!(Host::new(app).rebuild().is_ok());
    }

    /// Both pages have to encode, not just the one the screen opens on, and the chevron
    /// beside the title turns between them both ways.
    #[test]
    fn fr15_both_pages_encode() {
        let mut screen = Screen::at(420.0);
        screen.turn();
        assert!(
            texts(&screen.live())
                .iter()
                .any(|text| text == Page::Costs.title()),
            "pressing the chevron did not reach the costs page"
        );
        screen.turn();
        assert!(
            texts(&screen.live())
                .iter()
                .any(|text| text == Page::Today.title()),
            "the chevron on the costs page did not come back to the overview"
        );
    }

    /// Every drawing has to reach the Renderer as a draw list. A `Canvas` with no commands
    /// on it is a blank rectangle, and a blank rectangle is exactly what a chart that
    /// failed to build looks like.
    #[test]
    fn fr16_every_drawing_on_the_overview_carries_a_draw_list() {
        let screen = Screen::at(420.0);
        let live = screen.live();
        let canvases = created(&live, WidgetKind::Canvas);
        assert_eq!(
            canvases.len(),
            4,
            "the overview should hold the chevron, the dial, the week and the plus"
        );
        for canvas in canvases {
            assert!(
                live.iter().any(|mutation| matches!(
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
        let screen = Screen::at(420.0);
        assert!(
            texts(&screen.live())
                .iter()
                .any(|text| text == &format!("{MOBILE_SHARE}%")),
            "the dial's reading is not written anywhere on it"
        );
    }

    /// Nothing on either page is painted by the design system: every fill is one of the
    /// palette's literals, so no card or panel comes out of an accent container.
    #[test]
    fn fr22_nothing_on_either_page_is_painted_by_a_role() {
        for width in WIDTHS {
            let mut screen = Screen::at(width);
            assert_painted_from_the_palette(&screen.live());
            screen.turn();
            assert_painted_from_the_palette(&screen.live());
        }
    }

    /// The reference has no bar of destinations on either screen, so this sample declares
    /// none. A `Navigation` would bring the design system's own bar, which is the thing a
    /// unified sample may not show.
    #[test]
    fn fr22_there_is_no_bar_of_destinations() {
        let mut screen = Screen::at(420.0);
        assert!(created(&screen.live(), WidgetKind::Navigation).is_empty());
        screen.turn();
        assert!(created(&screen.live(), WidgetKind::Navigation).is_empty());
    }

    /// The dial stands on the page. It used to sit on a full-width white rectangle, which
    /// the reference does not have: the white there is the dial's own disc.
    #[test]
    fn fr22_the_dial_stands_on_the_page_rather_than_a_card() {
        let screen = Screen::at(420.0);
        let white = backgrounds(&screen.live())
            .into_iter()
            .filter(|(_, paint)| *paint == palette::CARD)
            .count();
        assert_eq!(
            white, 1,
            "the only white panel on the overview is the card holding the second reading"
        );
    }

    /// The overview carries what the reference's does and nothing more: no service tiles,
    /// which belong to the costs page, and no rule above the days at the foot.
    #[test]
    fn fr22_the_overview_has_no_tiles_and_no_rule() {
        let screen = Screen::at(420.0);
        let live = screen.live();
        assert!(created(&live, WidgetKind::LazyRow).is_empty());
        assert!(created(&live, WidgetKind::Divider).is_empty());
        let words = texts(&live);
        for name in SOURCES.map(|source| source.name) {
            assert!(
                !words.iter().any(|text| text == name),
                "{name} is on the overview"
            );
        }
    }

    /// The costs page is sage from edge to edge at every width, behind the bar as well.
    ///
    /// Named for what it defends: the sage used to be the column's own background, so at
    /// medium and expanded widths it stopped at the 420 dp column with the near-white page
    /// either side of it, and the bar above stayed near-white at every width. The frame
    /// and the bar both span the window, so their colour is the window's.
    #[test]
    fn fr22_the_costs_page_is_sage_to_the_window_edges() {
        for width in WIDTHS {
            let mut screen = Screen::at(width);
            screen.turn();
            let live = screen.live();
            let frame = created(&live, WidgetKind::Scaffold);
            let bar = created(&live, WidgetKind::TopAppBar);
            assert_eq!(frame.len(), 1, "one frame round the page");
            assert_eq!(bar.len(), 1, "one bar across the top");
            assert_eq!(
                background_of(&live, frame[0]),
                Some(palette::SAGE),
                "the window behind the costs page is not sage at {width} dp"
            );
            assert_eq!(
                background_of(&live, bar[0]),
                Some(palette::SAGE),
                "the bar over the costs page is not sage at {width} dp"
            );
        }
    }

    /// The overview's window and bar are the page colour, so the bar is not a band of the
    /// design system's own colour across the top.
    #[test]
    fn fr22_the_overview_bar_is_the_page_colour() {
        let screen = Screen::at(420.0);
        let live = screen.live();
        let frame = created(&live, WidgetKind::Scaffold);
        let bar = created(&live, WidgetKind::TopAppBar);
        assert_eq!(background_of(&live, frame[0]), Some(palette::PAGE));
        assert_eq!(background_of(&live, bar[0]), Some(palette::PAGE));
    }

    /// The bar's title is the page's, in two lines, as the reference sets it.
    #[test]
    fn fr22_each_page_has_its_own_two_line_title() {
        let mut screen = Screen::at(420.0);
        assert!(
            texts(&screen.live())
                .iter()
                .any(|text| text == "Product\nStatistics")
        );
        screen.turn();
        let words = texts(&screen.live());
        assert!(words.iter().any(|text| text == "Popular\nCosts"));
        assert!(!words.iter().any(|text| text == "Product\nStatistics"));
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
                    0,
                    "the overview has no windowing list: its services are on the costs page"
                );
            },
        );
    }

    /// The costs page, which is the tinted one: a different picture, and the one where a
    /// chart drawn in the wrong ink would be invisible rather than merely wrong.
    ///
    /// The service tiles are a darker sage than the page they sit on. They used to be the
    /// page's own sage, so on this page only their words could be seen.
    #[test]
    fn fr13_the_costs_page_is_recorded() {
        sample_frames::record_as(
            "StatisticsCosts",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    screen.press_beside(Page::Today.title()),
                    "the screen has no way to reach the costs page"
                );
                assert_eq!(
                    screen.fill_lists(5),
                    1,
                    "the costs page has no row of services"
                );
                let live = screen.live_mutations();
                assert_painted_from_the_palette(&live);
                assert!(
                    backgrounds(&live)
                        .iter()
                        .any(|(_, paint)| *paint == palette::TILE),
                    "no service tile is drawn in the tile colour"
                );
            },
        );
    }
}
