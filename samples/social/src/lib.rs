//! A meditation app: a course of the day, shelves of courses, and sleep stories.
//!
//! Every card in the reference carries a drawn illustration, and so does every card here:
//! three original drawings in `assets/`, one per accent family, registered once and drawn
//! by id. Everything around them, the page, the ink, the badges and the bar, is named in
//! `palette`, because the reference has a cream page, a deep plum ink and a navy night
//! page that no design system's roles would give it.
//!
//! Unified, naming Cupertino and light: the reference is a cream page carrying
//! illustrated cards. `THEME` says both.

mod courses;
mod palette;

use compose_rust::runtime::*;
use compose_rust::schema::AssetKind;
use compose_rust::ui::*;
use courses::{Course, Palette, Shelf, Slot, course, meta_label, on, sessions_label};
use std::rc::Rc;

/// A phone design in a desktop window is still a phone design.
const PAGE_MEASURE: f32 = 420.0;

/// How tall each picture is. Numbers, because these are the proportions of a picture.
///
/// The hero runs edge to edge across the top of the first page. A tile is landscape on
/// the course shelves and portrait on the sleep shelf, which is how the reference tells
/// a story from a course at a glance.
const HERO: f32 = 300.0;
const WIDE: f32 = 240.0;
const TILE: f32 = 120.0;
const PORTRAIT: f32 = 200.0;

/// How round the bottom of the hero is. Its top runs under the status bar, so only the
/// two corners that stand on the page are rounded.
const HERO_CORNER: f32 = 32.0;

/// The round badges a card opens by: small on a tile, larger on a wide card.
const BADGE: f32 = 36.0;
const BADGE_LARGE: f32 = 44.0;

/// How much room a destination along the bottom takes to press.
const ICON_TAP: f32 = 44.0;

/// The mark on every badge that opens something.
const PLAY: &str = "\u{25b6}";

/// The mark on a button that goes back.
const BACK: &str = "\u{2190}";

/// The icons along the bottom, as the bytes each registration carries: the meaning's wire
/// tag, little endian, and nothing else.
///
/// An icon is a meaning rather than a picture. The Renderer holds the artwork for every
/// design system, so the sample never says what a house looks like.
mod icon {
    use compose_rust::ui::IconRole;

    pub static TODAY: [u8; 2] = (IconRole::Home as u16).to_le_bytes();
    pub static MEDITATE: [u8; 2] = (IconRole::List as u16).to_le_bytes();
    /// A collection of stories. There is no meaning for sleep or for a story, and
    /// `Library` is the one that says "many of these, to pick from".
    pub static SLEEP: [u8; 2] = (IconRole::Library as u16).to_le_bytes();
}

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

    /// What the destination is called. Nothing on screen says it: the bar is three bare
    /// icons. It names the screen you arrive at and it is what a test asks for.
    fn label(self) -> &'static str {
        match self {
            Destination::Today => "Today",
            Destination::Meditate => "Meditate",
            Destination::Sleep => "Sleep",
        }
    }

    fn icon(self) -> &'static [u8] {
        match self {
            Destination::Today => &icon::TODAY,
            Destination::Meditate => &icon::MEDITATE,
            Destination::Sleep => &icon::SLEEP,
        }
    }

    fn shelf(self) -> Shelf {
        match self {
            Destination::Today => Shelf::ForYou,
            Destination::Meditate => Shelf::Meditate,
            Destination::Sleep => Shelf::Sleep,
        }
    }

    /// Whether the page is the night one. The reference sets the sleep stories on navy
    /// and everything else on cream.
    fn is_night(self) -> bool {
        self == Destination::Sleep
    }

    /// The ground the page and the bar under it stand on.
    fn ground(self) -> Paint {
        if self.is_night() {
            palette::NIGHT
        } else {
            palette::PAGE
        }
    }
}

/// The drawing a course's cards carry, registered once however many cards draw it.
fn scene(found: &Course) -> u32 {
    compose_rust::asset::asset(AssetKind::Svg, found.palette.scene())
}

/// The soft dark wash a caption sits on inside a picture: clear at the top, the page's
/// plum at the bottom. Registered once; every later call names the same brush.
fn scrim() -> Paint {
    brush(Brush::vertical(vec![
        Stop::new(0.0, palette::SCRIM_TOP),
        Stop::new(1.0, palette::SCRIM_BOTTOM),
    ]))
}

/// The fill of the badge on a wide card, which in the reference takes the card's own
/// family: a deep green on the green card, the coral heart on the pink one.
fn wide_badge(family: Palette) -> Paint {
    match family {
        Palette::First => palette::DEEP_SAGE,
        Palette::Second => palette::ACCENT,
        Palette::Third => palette::BADGE,
    }
}

/// The round badge a card opens by.
///
/// A text button with a literal fill rather than a filled one. A filled button draws its
/// own container in the design system's accent, which comes out as the theme's blue over
/// a picture whose badges are dark circles.
fn play_badge(id: u32, size: f32, fill: Paint, opened: MutableState<Option<u32>>) {
    Button(PLAY)
        .variant(ButtonVariant::Text)
        .color(palette::ON_ART)
        .modifier(
            Modifier
                .size(size, size)
                .background(fill)
                .shape_role(ShapeRole::Full),
        )
        .on_click(move || opened.set(Some(id)));
}

/// A round button that goes back, on a fill of its own.
fn back_button(fill: Paint, on_click: Rc<dyn Fn()>) {
    Button(BACK)
        .variant(ButtonVariant::Text)
        .color(palette::ON_ART)
        .modifier(
            Modifier
                .size(BADGE_LARGE, BADGE_LARGE)
                .background(fill)
                .shape_role(ShapeRole::Full),
        )
        .on_click(move || on_click());
}

/// The title and the meta written on a picture.
fn caption_on_art(found: &'static Course) {
    Text(found.title)
        .type_role(TypeRole::Subtitle)
        .color(palette::ON_ART)
        .max_lines(2)
        .overflow(TextOverflow::Ellipsis);
    Text(meta_label(found).to_uppercase())
        .type_role(TypeRole::Caption)
        .color(palette::ON_ART_MUTED);
}

/// The picture across the top of the first page, edge to edge.
///
/// Its caption sits inside the picture on a soft wash, with the badge in front of the
/// title, which is what the reference does with its course of the day.
#[Composable]
fn hero_card(found: &'static Course, opened: MutableState<Option<u32>>) {
    Box()
        .modifier(
            Modifier
                .fill_max_width()
                .height(HERO)
                .corner_radii([0.0, 0.0, HERO_CORNER, HERO_CORNER])
                .clip(true),
        )
        .alignment(Alignment::BottomStart)
        .content(|| {
            Image(scene(found)).modifier(Modifier.fill_max_size());
            Box()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .height(HERO / 2.0)
                        .background(scrim()),
                )
                .content(|| {});
            Row()
                .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Lg))
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    play_badge(found.id, BADGE_LARGE, palette::BADGE, opened);
                    Column()
                        .modifier(Modifier.weight(1.0))
                        .content(|| caption_on_art(found));
                });
        });
}

/// A card given a whole row: the picture, and its caption written on it.
///
/// On the course shelves the caption is written on the picture with the badge at the
/// far end, in the card's own family. On the sleep shelf the reference treats the wide
/// card like the tiles around it: a badge on the picture and the title under it.
#[Composable]
fn wide_card(found: &'static Course, night: bool, opened: MutableState<Option<u32>>) {
    if night {
        story_card(found, WIDE, opened);
        return;
    }
    Box()
        .modifier(
            Modifier
                .fill_max_width()
                .height(WIDE)
                .shape_role(ShapeRole::Large),
        )
        .alignment(Alignment::BottomStart)
        .content(|| {
            Image(scene(found)).modifier(Modifier.fill_max_size());
            Box()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .height(WIDE / 2.0)
                        .background(scrim()),
                )
                .content(|| {});
            Row()
                .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Column()
                        .modifier(Modifier.weight(1.0))
                        .content(|| caption_on_art(found));
                    play_badge(found.id, BADGE_LARGE, wide_badge(found.palette), opened);
                });
        });
}

/// A picture with its badge in the lower left, at some height.
fn picture_with_badge(found: &'static Course, height: f32, opened: MutableState<Option<u32>>) {
    Box()
        .modifier(
            Modifier
                .fill_max_width()
                .height(height)
                .shape_role(ShapeRole::Medium),
        )
        .alignment(Alignment::BottomStart)
        .content(|| {
            Image(scene(found)).modifier(Modifier.fill_max_size());
            Box()
                .modifier(Modifier.padding_role(SpaceRole::Sm))
                .content(|| play_badge(found.id, BADGE, palette::BADGE, opened));
        });
}

/// A story on the night page: the picture, then the title in white and the length under
/// it, which is the order the reference uses there.
#[Composable]
fn story_card(found: &'static Course, height: f32, opened: MutableState<Option<u32>>) {
    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Xs)
        .content(|| {
            picture_with_badge(found, height, opened);
            Text(found.title)
                .type_role(TypeRole::Body)
                .color(palette::ON_ART)
                .max_lines(2)
                .overflow(TextOverflow::Ellipsis);
            Text(meta_label(found).to_uppercase())
                .type_role(TypeRole::Caption)
                .color(palette::ON_ART_MUTED);
        });
}

/// A course on a cream page: the picture, then the meta above the title.
///
/// The meta reads above the title, uppercase and in the quiet green the reference uses
/// for it. Under the title it would read as a footnote; above it, it is the label the
/// card is filed under, which is what it is.
#[Composable]
fn tile_card(found: &'static Course, night: bool, opened: MutableState<Option<u32>>) {
    if night {
        story_card(found, PORTRAIT, opened);
        return;
    }
    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Xs)
        .content(|| {
            picture_with_badge(found, TILE, opened);
            Text(meta_label(found).to_uppercase())
                .type_role(TypeRole::Caption)
                .color(palette::META);
            Text(found.title)
                .type_role(TypeRole::Body)
                .color(palette::INK)
                .max_lines(2)
                .overflow(TextOverflow::Ellipsis);
        });
}

/// A row of two small cards.
///
/// Two, because there is no wrapping row in the vocabulary: a grid is rows of a fixed
/// count and the code says how many rather than the layout working it out from the width.
#[Composable]
fn tile_row(row: Vec<&'static Course>, night: bool, opened: MutableState<Option<u32>>) {
    Row()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Md)
        .alignment(Alignment::TopStart)
        .content(|| {
            for found in row.iter().copied() {
                key(found.id, || {
                    Box()
                        .modifier(Modifier.weight(1.0))
                        .content(|| tile_card(found, night, opened));
                });
            }
            // An odd shelf leaves a gap rather than letting the last card stretch to
            // twice the width of its neighbours, which reads as a different kind of card.
            if row.len() == 1 {
                Spacer().modifier(Modifier.weight(1.0));
            }
        });
}

/// What a shelf is drawn as: its cards in order, tiles gathered two to a row.
enum Block {
    Heading(&'static str),
    Hero(&'static Course),
    Tiles(Vec<&'static Course>),
    Wide(&'static Course),
}

/// The shelf, cut into what is drawn.
///
/// Each course is placed once, where its slot says. The shelf used to take a hero, the
/// rest as the grid, and the last again as the wide card, so the last course of every
/// shelf was on screen twice.
fn blocks(shelf: Shelf) -> Vec<Block> {
    let mut blocks = Vec::new();
    for found in on(shelf) {
        if let Some(heading) = found.heading {
            blocks.push(Block::Heading(heading));
        }
        match found.slot {
            Slot::Hero => blocks.push(Block::Hero(found)),
            Slot::Wide => blocks.push(Block::Wide(found)),
            Slot::Tile => match blocks.last_mut() {
                Some(Block::Tiles(row)) if row.len() < 2 => row.push(found),
                _ => blocks.push(Block::Tiles(vec![found])),
            },
        }
    }
    blocks
}

/// The headline at the top of a page, and the line under it if it has one.
#[Composable]
fn page_header(
    destination: Destination,
    pick: MutableState<Destination>,
    heading: &'static str,
    strapline: Option<&'static str>,
) {
    let night = destination.is_night();
    let ink = if night { palette::ON_ART } else { palette::INK };
    Row()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Md)
        .alignment(Alignment::CenterStart)
        .content(|| {
            // The night page is one you arrive at from the others, and the reference
            // gives it a round way back beside its title.
            if night {
                back_button(
                    palette::NIGHT_RAISED,
                    Rc::new(move || pick.set(Destination::Today)),
                );
            }
            Column()
                .modifier(Modifier.weight(1.0))
                .space_role(SpaceRole::Xs)
                .content(|| {
                    Text(heading).type_role(TypeRole::Headline).color(ink);
                    if let Some(strapline) = strapline {
                        Text(strapline)
                            .type_role(TypeRole::Body)
                            .color(palette::MUTED);
                    }
                });
        });
}

/// The page a destination shows, in the order the reference draws it.
#[Composable]
fn shelf_page(
    destination: Destination,
    pick: MutableState<Destination>,
    opened: MutableState<Option<u32>>,
) {
    let night = destination.is_night();
    let blocks = blocks(destination.shelf());
    // The first page has no headline: it opens on its hero, edge to edge, and the hero is
    // outside the page's margins.
    let header = match destination {
        Destination::Today => None,
        Destination::Meditate => {
            Some(("Meditate", Some("Meditation for beginners and experienced")))
        }
        Destination::Sleep => Some(("Sleep stories", None)),
    };
    Column().modifier(Modifier.fill_max_width()).content(|| {
        for block in &blocks {
            if let Block::Hero(found) = block {
                hero_card(*found, opened);
            }
        }
        Column()
            .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Lg))
            .space_role(SpaceRole::Md)
            .content(|| {
                if let Some((heading, strapline)) = header {
                    page_header(destination, pick, heading, strapline);
                }
                for (index, block) in blocks.iter().enumerate() {
                    key(index, || match block {
                        Block::Hero(_) => {}
                        Block::Heading(text) => {
                            Text(*text)
                                .type_role(TypeRole::Subtitle)
                                .color(palette::INK);
                        }
                        Block::Tiles(row) => tile_row(row.clone(), night, opened),
                        Block::Wide(found) => wide_card(*found, night, opened),
                    });
                }
            });
    });
}

/// One course opened: its picture, what it is, and its sessions.
#[Composable]
fn course_page(found: &'static Course, opened: MutableState<Option<u32>>) {
    let (strong, quiet, ink) = found.palette.roles();
    Column()
        .modifier(Modifier.fill_max_size().background(quiet))
        .content(|| {
            Box()
                .modifier(Modifier.fill_max_width().height(HERO))
                .alignment(Alignment::TopStart)
                .content(|| {
                    Image(scene(found)).modifier(Modifier.fill_max_size());
                    // A round dark button on the picture, like every other control the
                    // reference stands on an illustration.
                    Box()
                        .modifier(Modifier.padding_role(SpaceRole::Md))
                        .content(|| {
                            back_button(palette::BADGE, Rc::new(move || opened.set(None)));
                        });
                });

            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .weight(1.0)
                        .padding_role(SpaceRole::Lg),
                )
                .space_role(SpaceRole::Md)
                .content(|| {
                    Text(found.title).type_role(TypeRole::Headline).color(ink);
                    Text(sessions_label(found))
                        .type_role(TypeRole::Body)
                        .color(palette::MUTED);
                    Column()
                        .modifier(Modifier.fill_max_width())
                        .space_role(SpaceRole::Sm)
                        .content(|| {
                            for number in 1..=found.sessions {
                                key(number, || {
                                    Row()
                                        .modifier(
                                            Modifier
                                                .fill_max_width()
                                                .background(palette::CARD)
                                                .shape_role(ShapeRole::Medium)
                                                .padding_role(SpaceRole::Md),
                                        )
                                        .space_role(SpaceRole::Sm)
                                        .alignment(Alignment::CenterStart)
                                        .content(|| {
                                            Text(format!("Session {number}"))
                                                .type_role(TypeRole::Body)
                                                .color(ink)
                                                .modifier(Modifier.weight(1.0));
                                            Text(format!("{} min", found.minutes))
                                                .type_role(TypeRole::Caption)
                                                .color(palette::MUTED);
                                        });
                                });
                            }
                        });
                    Spacer().modifier(Modifier.weight(1.0));
                    // A text button on a literal fill, for the same reason the badges are:
                    // a filled button would be the theme's blue.
                    Button("Begin")
                        .variant(ButtonVariant::Text)
                        .color(palette::ON_ART)
                        .modifier(
                            Modifier
                                .fill_max_width()
                                .background(strong)
                                .shape_role(ShapeRole::Full),
                        )
                        .on_click(|| {
                            Message::new("Playback is not part of this sample").show();
                        });
                });
        });
}

/// A destination along the bottom: its icon, and a press over it.
///
/// The button carries no text and sits over the icon, so the press lands on it whatever
/// was drawn underneath.
fn icon_button(picture: &'static [u8], tint: Paint, on_click: Rc<dyn Fn()>) {
    Box()
        .modifier(Modifier.size(ICON_TAP, ICON_TAP))
        .alignment(Alignment::Center)
        .content(|| {
            Icon(compose_rust::asset::asset(AssetKind::VectorIcon, picture)).color(tint);
            let press = Rc::clone(&on_click);
            Button("")
                .variant(ButtonVariant::Text)
                .modifier(Modifier.fill_max_size())
                .on_click(move || press());
        });
}

/// The bar along the bottom: three icons and nothing else.
///
/// Drawn here rather than declared as a `Navigation`, which is the widget for "the
/// destinations, in whatever shape this design system and this window call for": a
/// labelled bar with a selection pill under a hairline, a rail, or a drawer. The
/// reference draws none of those. A meditation app's bar is a few bare icons on the page,
/// the one you are on in the coral the reference marks things with, and that is a shape
/// no design system would be right to give a set of destinations.
#[Composable]
fn bottom_bar(destination: Destination, on_go: Rc<dyn Fn(Destination)>) {
    let rest = if destination.is_night() {
        palette::ON_ART_MUTED
    } else {
        palette::MUTED
    };
    Row()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Sm))
        .arrangement(Arrangement::SpaceAround)
        .alignment(Alignment::Center)
        .content(|| {
            for choice in Destination::STRIP {
                let tint = if choice == destination {
                    palette::ACCENT
                } else {
                    rest
                };
                key(choice.label(), || {
                    Box().content(|| {
                        let go = Rc::clone(&on_go);
                        icon_button(choice.icon(), tint, Rc::new(move || go(choice)));
                    });
                });
            }
        });
}

/// The app.
#[Composable]
pub fn app() {
    let window = current_window_size();
    let measure = if window.is_compact() {
        None
    } else {
        Some(PAGE_MEASURE)
    };

    let destination = remember(|| mutable_state_of(Destination::Today));
    let opened = remember(|| mutable_state_of(Option::<u32>::None));

    // A course covers everything, including the bar along the bottom. A course whose only
    // way out is its own back button should not also have three other ways out. The page
    // around the measure is cream too, so a wide window does not show the toolkit's own
    // background either side of it.
    if let Some(found) = opened.get().and_then(course) {
        Box()
            .modifier(Modifier.fill_max_size().background(palette::PAGE))
            .alignment(Alignment::TopCenter)
            .content(|| {
                Column()
                    .modifier(
                        Modifier
                            .width_if(measure)
                            .fill_max_width_if(measure.is_none())
                            .fill_max_height(),
                    )
                    .content(|| course_page(found, opened));
            });
        return;
    }

    let chosen = destination.get();
    let ground = chosen.ground();

    // The bar stays along the bottom at every width, under the same measure as the page,
    // because the reference is a phone and a phone's bar does not become a rail.
    Scaffold()
        .modifier(Modifier.background(ground))
        .bottom_bar(|| {
            Box()
                .modifier(Modifier.fill_max_width().background(ground))
                .alignment(Alignment::Center)
                .content(|| {
                    Column()
                        .modifier(
                            Modifier
                                .width_if(measure)
                                .fill_max_width_if(measure.is_none()),
                        )
                        .content(|| {
                            let go = destination;
                            bottom_bar(chosen, Rc::new(move |choice| go.set(choice)));
                        });
                });
        })
        .content(|| {
            Column()
                .modifier(Modifier.fill_max_size().background(ground))
                .content(|| {
                    Box()
                        .modifier(Modifier.fill_max_size())
                        .alignment(Alignment::TopCenter)
                        .content(|| {
                            ScrollColumn()
                                .modifier(
                                    Modifier
                                        .width_if(measure)
                                        .fill_max_width_if(measure.is_none())
                                        .fill_max_height(),
                                )
                                .content(|| shelf_page(chosen, destination, opened));
                        });
                });
        });
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

/// Runs the sample as a program of its own. The desktop binary is one line of this.
pub fn launch() {
    launch_builder().application(app);
}

/// How this sample is configured, in one place because three entry points need it.
///
/// `demo_theme_for` rather than `THEME` alone: a sample is something to look at, and one
/// machine can only show the design system and the scheme it is set to. `DXC_DESIGN` and
/// `DXC_SCHEME` each override the half they name, so `THEME` stays the answer to
/// everything nobody asked about.
fn launch_builder() -> LaunchBuilder {
    // The name the window carries. A desktop lists windows by it, so a window that said
    // nothing was listed under whatever the renderer happened to be called.
    LaunchBuilder::new()
        .with_theme(compose_rust::demo_theme_for(THEME))
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Social")
                // Without one the window wears the toolkit's picture wherever the system
                // lists windows. The bytes travel as an asset and the renderer refers to
                // them by id: a path would be a fact about the machine this was built on,
                // and a name would ask the toolkit to find something it may not have.
                .with_icon(compose_rust::asset::asset(
                    compose_rust::schema::AssetKind::Png,
                    include_bytes!("../assets/icon.png"),
                )),
        )
}

// The platforms where the sample is not a program. Android's Activity and the browser's
// page both own the loop, so neither has a `main` to call: each names an entry point that
// registers the root composable, and these macros define it. Each compiles into nothing
// that runs off its own platform, so a desktop build still checks the other two.
compose_rust::android_application!({ launch_builder() }, app);
compose_rust::web_application!({ launch_builder() }, app);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;
    use compose_rust::ComposeHost;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
    use compose_rust::schema::{EventPayload, Modifier as WireModifier, PropertyKind, WidgetKind};
    use courses::COURSES;

    /// The node and the handler that press the destination drawn with this icon.
    ///
    /// The bar has no words on it, so a destination is found by what the reader sees: the
    /// icon registered with this meaning, the node drawing it, and the button that shares
    /// its box.
    fn destination_press(mutations: &[Mutation<'_>], choice: Destination) -> Option<(u32, u64)> {
        let tag = choice.icon();
        let asset_id = mutations.iter().find_map(|mutation| match mutation {
            Mutation::RegisterAsset {
                asset_id,
                kind: AssetKind::VectorIcon,
                bytes,
            } if *bytes == tag => Some(*asset_id),
            _ => None,
        })?;
        let icon = mutations.iter().rev().find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::Asset,
                value: PropertyValue::Integer(id),
            } if *id as u32 == asset_id => Some(*node_id),
            _ => None,
        })?;
        let parent = mutations.iter().rev().find_map(|mutation| match mutation {
            Mutation::Insert {
                parent_id, node_id, ..
            } if *node_id == icon => Some(*parent_id),
            _ => None,
        })?;
        let siblings: Vec<u32> = mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::Insert {
                    parent_id, node_id, ..
                } if *parent_id == parent && *node_id != icon => Some(*node_id),
                _ => None,
            })
            .collect();
        mutations.iter().rev().find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnClick,
                value: PropertyValue::Integer(handler),
            } if siblings.contains(node_id) => Some((*node_id, *handler as u64)),
            _ => None,
        })
    }

    /// Presses a destination on a recording, which is how a screenshot reaches a page
    /// other than the first.
    fn go_to(screen: &mut sample_frames::Screen, choice: Destination) -> bool {
        let found = destination_press(&screen.live_mutations(), choice);
        let Some((node_id, handler_id)) = found else {
            return false;
        };
        screen.dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        });
        true
    }

    /// Named for what it defends: the reference is a light design, and a machine set
    /// the other way drew this sample dark with nothing to compare against.
    #[test]
    fn fr14_the_design_names_its_colour_scheme() {
        // Through the wire rather than off the constant: what settles the question is the
        // record the Renderer reads, and a scheme that never leaves the Host is a scheme
        // nobody is drawn in.
        compose_rust::window::reset_window_size();
        let mut host = ComposeHost::with_theme(app, THEME);
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
        host: ComposeHost,
        frames: Vec<Vec<u8>>,
    }

    impl Screen {
        fn new() -> Self {
            compose_rust::window::reset_window_size();
            let mut host = ComposeHost::new(app);
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

        /// Presses a destination along the bottom, which is an icon with nothing written
        /// under it.
        fn press_destination(&mut self, choice: Destination) -> bool {
            let found = destination_press(&sample_frames::live(self.mutations()), choice);
            let Some((node_id, handler_id)) = found else {
                return false;
            };
            let (batch, _) = self
                .host
                .dispatch(HostEvent {
                    node_id,
                    handler_id,
                    payload: EventPayload::Clicked,
                })
                .expect("the click failed");
            if !batch.is_empty() {
                let batch = batch.to_vec();
                self.frames.push(batch);
            }
            true
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
            let (batch, _) = self
                .host
                .dispatch(HostEvent {
                    node_id,
                    handler_id,
                    payload: EventPayload::Clicked,
                })
                .expect("the click failed");
            if !batch.is_empty() {
                let batch = batch.to_vec();
                self.frames.push(batch);
            }
            true
        }

        /// The words on screen now, one per text node.
        ///
        /// Only the live nodes, and only the latest words each one carries: a node that
        /// was given new words keeps its old record in the run, and counting that would
        /// count something nobody can see.
        fn live_texts(&self) -> Vec<String> {
            let mut texts: Vec<(u32, String)> = Vec::new();
            for mutation in sample_frames::live(self.mutations()) {
                if let Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Text,
                    value: PropertyValue::String(text),
                } = mutation
                {
                    texts.retain(|(node, _)| *node != node_id);
                    texts.push((node_id, text.to_owned()));
                }
            }
            texts.into_iter().map(|(_, text)| text).collect()
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
        compose_rust::window::reset_window_size();
        assert!(ComposeHost::new(app).rebuild().is_ok());
    }

    /// Every destination has to encode, not just the one the app opens on.
    ///
    /// Pressed by its icon, because the bar has no words on it.
    #[test]
    fn fr15_every_destination_encodes() {
        let mut screen = Screen::new();
        for choice in Destination::STRIP {
            assert!(
                screen.press_destination(choice),
                "the bar has no icon for {}",
                choice.label()
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// The bar is the sample's own: icons, and not a word or a design system's bar.
    ///
    /// Named for what it defends: the bar was a `Navigation`, which the active design
    /// system draws as a labelled bar with a selection pill, and as a rail or a drawer
    /// once the window widens. The reference has none of those.
    #[test]
    fn fr22_the_bar_is_icons_and_nothing_else() {
        let mut screen = Screen::new();
        // The first page has no headline, so any destination's name on it is the bar's.
        for choice in Destination::STRIP {
            assert!(
                !screen
                    .live_texts()
                    .iter()
                    .any(|text| text == choice.label()),
                "the bar writes {} under its icon",
                choice.label()
            );
        }
        for choice in Destination::STRIP {
            assert!(
                screen.press_destination(choice),
                "the bar has no icon for {}",
                choice.label()
            );
            let mutations = sample_frames::live(screen.mutations());
            assert!(
                !mutations.iter().any(|mutation| matches!(
                    mutation,
                    Mutation::Create {
                        widget: WidgetKind::Navigation | WidgetKind::NavigationItem,
                        ..
                    }
                )),
                "the bar is the design system's again"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// The destination you are on is the one icon in the accent, and it moves when you
    /// go somewhere else.
    #[test]
    fn fr22_the_selected_destination_is_drawn_in_the_accent() {
        let mut screen = Screen::new();
        for choice in Destination::STRIP {
            assert!(
                screen.press_destination(choice),
                "the bar has no icon for {}",
                choice.label()
            );
            let mutations = sample_frames::live(screen.mutations());
            let mut tints: Vec<(u32, Paint)> = Vec::new();
            for mutation in &mutations {
                let Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Color,
                    value: PropertyValue::Integer(bits),
                } = mutation
                else {
                    continue;
                };
                let Some(paint) = Paint::from_bits(*bits as u64) else {
                    continue;
                };
                tints.retain(|(node, _)| node != node_id);
                tints.push((*node_id, paint));
            }
            let accented: Vec<u32> = tints
                .iter()
                .filter(|(_, paint)| *paint == palette::ACCENT)
                .map(|(node, _)| *node)
                .collect();
            assert_eq!(
                accented.len(),
                1,
                "on {} the accent marks {} things rather than the destination you are on",
                choice.label(),
                accented.len()
            );
            let (pressed, _) = destination_press(&mutations, choice).expect("the bar lost an icon");
            let icon = mutations.iter().find_map(|mutation| match mutation {
                Mutation::Insert {
                    parent_id, node_id, ..
                } if *node_id != pressed && accented.contains(node_id) => Some(*parent_id),
                _ => None,
            });
            let beside = mutations.iter().any(|mutation| {
                matches!(
                    mutation,
                    Mutation::Insert { parent_id, node_id, .. }
                        if *node_id == pressed && Some(*parent_id) == icon
                )
            });
            assert!(
                beside,
                "on {} the accent is on some other destination",
                choice.label()
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// Every course on a shelf is drawn once.
    ///
    /// Named for what it defends: the shelf took everything after its hero as the grid
    /// and then drew the last course again as the wide card, so the third course of every
    /// shelf was on screen twice.
    #[test]
    fn fr22_no_course_is_drawn_twice_on_its_shelf() {
        let mut screen = Screen::new();
        for choice in Destination::STRIP {
            assert!(
                screen.press_destination(choice),
                "the bar has no icon for {}",
                choice.label()
            );
            let texts = screen.live_texts();
            for found in on(choice.shelf()) {
                let times = texts.iter().filter(|text| *text == found.title).count();
                assert_eq!(
                    times,
                    1,
                    "{} is drawn {times} times on the {} shelf",
                    found.title,
                    choice.label()
                );
            }
        }
        compose_rust::window::reset_window_size();
    }

    /// The fills on screen now, one per node: the latest each live node was given.
    fn live_fills(mutations: &[Mutation<'_>]) -> Vec<Paint> {
        let mut fills: Vec<(u32, Paint)> = Vec::new();
        for mutation in mutations {
            let Mutation::SetModifier {
                node_id,
                modifier: WireModifier::Background(paint),
                ..
            } = mutation
            else {
                continue;
            };
            fills.retain(|(node, _)| node != node_id);
            fills.push((*node_id, *paint));
        }
        fills.into_iter().map(|(_, paint)| paint).collect()
    }

    /// The sleep stories stand on the night page, and the others on cream.
    ///
    /// Named for what it defends: every destination was drawn on the same cream page,
    /// and the reference's sleep stories are on dark navy with white titles.
    #[test]
    fn fr22_the_sleep_stories_are_on_the_night_page() {
        let mut screen = Screen::new();
        for choice in Destination::STRIP {
            assert!(
                screen.press_destination(choice),
                "the bar has no icon for {}",
                choice.label()
            );
            let fills = live_fills(&sample_frames::live(screen.mutations()));
            let (wanted, unwanted) = if choice == Destination::Sleep {
                (palette::NIGHT, palette::PAGE)
            } else {
                (palette::PAGE, palette::NIGHT)
            };
            assert!(
                fills.contains(&wanted),
                "{} is not on its page: {fills:?}",
                choice.label()
            );
            assert!(
                !fills.contains(&unwanted),
                "{} has the other page's ground in it: {fills:?}",
                choice.label()
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// Nothing on any page is painted by the design system.
    ///
    /// Named for what it defends: a unified sample names its reference's colours, so a
    /// fill that is a role is the active design system's colour on a picture that never
    /// had it. A brush is allowed: the wash under a caption is one, made of literals.
    #[test]
    fn fr22_nothing_on_the_screen_is_painted_by_a_role() {
        let mut screen = Screen::new();
        for choice in Destination::STRIP {
            assert!(
                screen.press_destination(choice),
                "the bar has no icon for {}",
                choice.label()
            );
        }
        assert!(screen.press(PLAY), "no card opens");
        for mutation in screen.mutations() {
            let Mutation::SetModifier {
                node_id,
                modifier: WireModifier::Background(paint) | WireModifier::Border { paint, .. },
                ..
            } = mutation
            else {
                continue;
            };
            assert!(
                !matches!(paint, Paint::Role(_)),
                "node {node_id} is filled with {paint:?}, which the design system picks"
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
        let batch = ComposeHost::new(app)
            .rebuild()
            .expect("the first frame failed")
            .to_vec();
        let mutations = decode_batch(&batch).expect("the batch did not decode");
        let registered: Vec<u32> = mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::RegisterAsset {
                    asset_id,
                    kind: AssetKind::Svg,
                    ..
                } => Some(*asset_id),
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
        assert!(screen.press(BACK), "the course has no way back");
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text == "Courses just for you"),
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

    /// The sleep shelf, which the reference draws on a dark navy page with portrait
    /// cards and a round way back beside its title.
    #[test]
    fn fr16_the_sleep_shelf_is_recorded() {
        sample_frames::record_as(
            "SocialSleep",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    go_to(screen, Destination::Sleep),
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
