//! A podcast hub: a welcome screen, new episodes, an episode you can open, and a player.
//!
//! Covers are pictures: a podcast cover is a poster, made to be recognised at the size of
//! a thumbnail, and a draw list cannot say one. Each of the three is an original drawing
//! registered once and drawn by id after that.
//!
//! The drawing this sample is about is the waveform. Every design system's player has one,
//! nothing in the widget vocabulary is one, and it is the clearest case of a `Canvas`
//! earning its place: forty columns, the played part in the accent and the rest in grey,
//! built once into an attribute that only crosses the boundary when it changes.
//!
//! Unified rather than adaptive, and the stronger sense of the word: the reference is one
//! picture of one design, so the hub draws that picture everywhere rather than the
//! platform's version of it. `THEME` names the design system and the colour scheme, and
//! `palette` names the colours, because the picture has a single orange accent that no
//! design system's palette would have given it.

mod library;
mod palette;

use compose_rust::AssetKind;
use compose_rust::runtime::*;
use compose_rust::ui::*;
use library::{
    EPISODES, Episode, SHOWS, Show, WELCOME, clock, episode, short_count, show_of, waveform,
};
use std::rc::Rc;

/// A phone design in a desktop window is still a phone design.
const PAGE_MEASURE: f32 = 420.0;

/// How large each drawing is. Numbers, because these are the proportions of a picture.
const COVER_LARGE: f32 = 280.0;
const COVER_CARD: f32 = 140.0;
const COVER_SMALL: f32 = 72.0;
const COVER_ROW: f32 = 48.0;
const AVATAR: f32 = 24.0;
/// The box each waveform is laid out in. The drawing is scaled to whatever its canvas
/// measures, so these are proportions rather than sizes, apart from the height.
const WAVE: (f32, f32) = (340.0, 72.0);
const WAVE_SMALL: (f32, f32) = (300.0, 48.0);
/// How many columns a waveform is drawn with.
///
/// The reference draws a few hundred hair-thin ones. Forty reads as a waveform and costs
/// forty records, which is a budget a frame can carry without anyone thinking about it.
const WAVE_COLUMNS: usize = 40;

/// The round controls: the destinations along the bottom and the play buttons.
const ROUND: f32 = 52.0;
/// The outlined circles under an episode.
const ACTION: f32 = 36.0;
/// The outlined squares: the two at the top of the hub and the two on the player's art.
const SQUARE: f32 = 44.0;
const MARK: f32 = 18.0;

/// The rounded bottom of the player's artwork and the corners of the welcome picture.
const ART_CORNER: f32 = 36.0;
/// The welcome screen's "Let's Listen" pill.
const PILL: (f32, f32) = (236.0, 56.0);
/// How thick the welcome screen's progress segments are.
const SEGMENT: f32 = 4.0;
/// How many steps the welcome screen's progress says there are, and the one it is on.
const WELCOME_STEPS: usize = 3;

/// Where the player picks up, in seconds. Part way in, because a player sitting at zero
/// shows a waveform with nothing played and says nothing about what the drawing does.
const START_SECONDS: u32 = 326;

/// How far a skip moves the player.
const SKIP_SECONDS: u32 = 15;

/// How much of the half waveform on the left of a card is drawn as played.
///
/// All of it. The card draws one waveform in two halves with the cover between them, so
/// the playhead sits behind the artwork: everything to its left is the accent and
/// everything to its right is grey, which is what the reference draws.
const CARD_PLAYED: f32 = 1.0;

/// The show picked out under "Podcasts You May Like": the reference's orange row is The
/// Researcher, so that row comes first and the others follow it, quiet.
const RECOMMENDED: usize = 1;

/// The names the drawn controls answer to. A control whose face is a picture has no words
/// on it, so this is what assistive technology reads and what a test presses.
mod name {
    pub const BROWSE: &str = "Browse";
    pub const NOTIFICATIONS: &str = "Notifications";
    pub const SEARCH: &str = "Search";
    pub const LISTEN: &str = "Let's Listen";
    pub const BACK: &str = "Back";
    pub const SHARE: &str = "Share";
    pub const LIKES: &str = "Likes";
    pub const COMMENTS: &str = "Comments";
    pub const SHARES: &str = "Shares";
    pub const MORE: &str = "More";
    pub const SKIP_BACK: &str = "Back 15 seconds";
    pub const SKIP_AHEAD: &str = "Ahead 15 seconds";
}

/// What a press hands on. Shared, because one handler reaches several controls.
type Handler<T> = Rc<dyn Fn(T)>;

/// The icons this hub draws, as the bytes each registration carries: the meaning's wire
/// tag, little endian, and nothing else.
///
/// An icon is a meaning rather than a picture. The Renderer holds the artwork for every
/// design system, so `Home` comes out as this system's house and the hub never says what
/// a house looks like. The marks under an episode and the two at the top of the hub are
/// not in that set, so they are drawings instead; they live in `library` beside the
/// waveform.
mod icon {
    use compose_rust::ui::IconRole;

    pub static HOME: [u8; 2] = (IconRole::Home as u16).to_le_bytes();
    /// The reference's middle destination is a compass. The closed set of meanings has
    /// none, and looking for something to listen to is what the hub is for, so it is the
    /// search meaning.
    pub static EXPLORE: [u8; 2] = (IconRole::Search as u16).to_le_bytes();
    /// The reference's last destination is a person. The closed set has no person yet,
    /// so it is the settings meaning until it does.
    pub static YOU: [u8; 2] = (IconRole::Settings as u16).to_le_bytes();
    pub static MORE: [u8; 2] = (IconRole::More as u16).to_le_bytes();
    pub static ONWARD: [u8; 2] = (IconRole::Forward as u16).to_le_bytes();
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Destination {
    New,
    Shows,
    You,
}

impl Destination {
    /// Left to right. The hub is in the middle, because that is where the reference puts
    /// the destination it is drawn on: a house, then the compass filled orange, then a
    /// person.
    const STRIP: [Destination; 3] = [Destination::Shows, Destination::New, Destination::You];

    /// Where the bar opens: the hub, the middle circle.
    const FIRST: Destination = Destination::New;

    /// What the destination is called. Nothing on screen says it: the reference's bar is
    /// three bare icons. It names the screen you arrive at and it is what a test asks for.
    fn label(self) -> &'static str {
        match self {
            Destination::New => "New",
            Destination::Shows => "Shows",
            Destination::You => "You",
        }
    }

    fn icon(self) -> &'static [u8] {
        match self {
            Destination::Shows => &icon::HOME,
            Destination::New => &icon::EXPLORE,
            Destination::You => &icon::YOU,
        }
    }
}

/// A control whose face is a drawing: the fill or the outline, the mark, and the press
/// over it.
///
/// The button carries no text and sits over the drawing, so the press lands on it
/// whatever was drawn underneath. Its name is what is read out in place of words.
fn face_button(
    size: f32,
    shape: ShapeRole,
    fill: Option<Paint>,
    edge: Option<Paint>,
    name: &'static str,
    face: impl FnOnce(),
    on_click: Rc<dyn Fn()>,
) {
    let mut outline = Modifier
        .size(size, size)
        .background_if(fill)
        .shape_role(shape);
    if let Some(edge) = edge {
        outline = outline.border(1.0, edge);
    }
    Box()
        .modifier(outline)
        .alignment(Alignment::Center)
        .content(|| {
            face();
            Button("")
                .accessible_name(name)
                .variant(ButtonVariant::Text)
                .modifier(Modifier.fill_max_size())
                .on_click(move || on_click());
        });
}

/// A button that is one of the closed set of meanings, tinted, with nothing written on it.
fn icon_button(
    role: IconRole,
    name: &'static str,
    tint: Paint,
    modifier: Modifier,
    on_click: impl FnMut() + 'static,
) {
    Button("")
        .icon(role)
        .accessible_name(name)
        .variant(ButtonVariant::Text)
        .color(tint)
        .modifier(modifier)
        .on_click(on_click);
}

/// One registered meaning, tinted.
fn icon_face(picture: &'static [u8], tint: Paint) {
    Icon(asset(AssetKind::VectorIcon, picture)).color(tint);
}

/// One drawn mark, at the size the controls use.
fn mark_face(mark: DrawList) {
    Canvas(mark).modifier(Modifier.size(MARK, MARK));
}

/// A show's cover at some size, with its corners rounded.
///
/// The picture is registered here rather than up front. `asset` returns the same id for
/// the same bytes and queues nothing the second time, so a cover that appears in the strip,
/// on the episode card and again in the player is one registration and several nodes.
fn cover(size: f32, show: &Show) {
    cover_shaped(size, show, Some(ShapeRole::Large));
}

fn cover_shaped(size: f32, show: &Show, shape: Option<ShapeRole>) {
    let mut modifier = Modifier.size(size, size);
    if let Some(shape) = shape {
        modifier = modifier.shape_role(shape);
    }
    Image(asset(AssetKind::Svg, show.cover)).modifier(modifier);
}

/// One thing that can be done to an episode: the mark in its outlined circle, and, on the
/// player, how many people have done it. The card under "New Episodes" shows the circles
/// alone, which is where the reference keeps its counts off.
fn action(name: &'static str, face: impl FnOnce(), count: Option<u32>, on_press: Rc<dyn Fn()>) {
    Row()
        .space_role(SpaceRole::Xs)
        .alignment(Alignment::Center)
        .content(|| {
            face_button(
                ACTION,
                ShapeRole::Full,
                None,
                Some(palette::OUTLINE),
                name,
                face,
                on_press,
            );
            if let Some(count) = count {
                Text(short_count(count))
                    .type_role(TypeRole::Label)
                    .color(palette::INK);
            }
        });
}

/// The heart, the bubble and the arrow, with or without their counts.
fn engagement(found: &Episode, counted: bool) {
    let count = |value: u32| counted.then_some(value);
    action(
        name::LIKES,
        || mark_face(library::heart(MARK, palette::INK)),
        count(found.hearts),
        not_here("Liking"),
    );
    action(
        name::COMMENTS,
        || mark_face(library::comment(MARK, palette::INK)),
        count(found.comments),
        not_here("Replies"),
    );
    action(
        name::SHARES,
        || mark_face(library::share(MARK, palette::INK)),
        count(found.shares),
        not_here("Sharing"),
    );
}

/// The "more" circle at the end of a row of actions.
fn more_action() {
    action(
        name::MORE,
        || icon_face(&icon::MORE, palette::INK),
        None,
        not_here("More"),
    );
}

/// Says that something on the screen is not part of this sample.
fn not_here(what: &'static str) -> Rc<dyn Fn()> {
    Rc::new(move || Message::new(format!("{what} is not part of this sample")).show())
}

/// The welcome screen: where you are in the introduction, a picture, the promise, and the
/// way in.
#[Composable]
fn welcome_page(on_listen: Rc<dyn Fn()>) {
    Column()
        .modifier(
            Modifier
                .fill_max_size()
                .background(palette::PAGE)
                .padding_role(SpaceRole::Lg),
        )
        .space_role(SpaceRole::Lg)
        .content(|| {
            // Three segments with the first one in the accent: the first of three steps.
            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .content(|| {
                    for step in 0..WELCOME_STEPS {
                        let fill = if step == 0 {
                            palette::ACCENT
                        } else {
                            palette::TILE
                        };
                        Box()
                            .modifier(
                                Modifier
                                    .weight(1.0)
                                    .height(SEGMENT)
                                    .background(fill)
                                    .shape_role(ShapeRole::Full),
                            )
                            .content(|| {});
                    }
                });
            Box()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .weight(1.0)
                        .corner_radius(ART_CORNER)
                        .clip(true),
                )
                .content(|| {
                    Image(asset(AssetKind::Svg, WELCOME)).modifier(Modifier.fill_max_size());
                });
            Text("The Best Podcast\nof the Year")
                .type_role(TypeRole::Display)
                .font_weight(600)
                .color(palette::INK)
                .text_align(TextAlign::Center)
                .modifier(Modifier.fill_max_width());
            Box()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::Center)
                .content(|| listen_pill(on_listen));
        });
}

/// The way in: an orange pill with a play disc at its start, the words in the middle and
/// a faint arrow at its end, pressed as one control.
fn listen_pill(on_listen: Rc<dyn Fn()>) {
    let inset = 6.0;
    let disc = PILL.1 - 2.0 * inset;
    Box()
        .modifier(
            Modifier
                .size(PILL.0, PILL.1)
                .background(palette::ACCENT)
                .shape_role(ShapeRole::Full),
        )
        .alignment(Alignment::Center)
        .content(|| {
            Text(name::LISTEN)
                .type_role(TypeRole::BodyStrong)
                .color(palette::PAGE);
            Box()
                .modifier(Modifier.fill_max_size().padding(inset))
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Box()
                        .modifier(
                            Modifier
                                .size(disc, disc)
                                .background(palette::PAGE)
                                .shape_role(ShapeRole::Full),
                        )
                        .alignment(Alignment::Center)
                        .content(|| {
                            // A character rather than an icon: the closed set of meanings
                            // has no play yet.
                            Text("\u{25b6}")
                                .type_role(TypeRole::Label)
                                .color(palette::ACCENT);
                        });
                });
            Box()
                .modifier(Modifier.fill_max_size().padding_role(SpaceRole::Md))
                .alignment(Alignment::CenterEnd)
                .content(|| icon_face(&icon::ONWARD, palette::ON_ACCENT_EDGE));
            Button("")
                .accessible_name(name::LISTEN)
                .variant(ButtonVariant::Text)
                .modifier(Modifier.fill_max_size())
                .on_click(move || on_listen());
        });
}

/// One episode as a card: the title over the artwork, a waveform either side of it, and
/// what can be done to it.
fn episode_card(found: &Episode, on_play: &Handler<u32>) {
    let show = show_of(found);
    let id = found.id;
    Column()
        .modifier(
            Modifier
                .fill_max_width()
                .background(palette::PAGE)
                .shape_role(ShapeRole::Large)
                .border(1.0, palette::OUTLINE)
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Sm)
        .content(|| {
            Text(format!(
                "S{}E{}: {}",
                found.season, found.number, found.title
            ))
            .type_role(TypeRole::Subtitle)
            .color(palette::INK)
            .text_align(TextAlign::Center)
            .modifier(Modifier.fill_max_width());
            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::Center)
                .content(|| {
                    // The part that has been played is in the accent and the rest is grey,
                    // so the two halves either side of the cover are one waveform with the
                    // playhead behind the artwork. The cover is the card's largest thing,
                    // as it is in the reference.
                    Canvas(waveform(
                        WAVE_SMALL.0 / 2.0,
                        WAVE_SMALL.1,
                        WAVE_COLUMNS / 2,
                        found.seed,
                        CARD_PLAYED,
                        palette::ACCENT,
                        palette::WAVE,
                    ))
                    .modifier(Modifier.weight(1.0).height(WAVE_SMALL.1));
                    cover(COVER_CARD, show);
                    Canvas(waveform(
                        WAVE_SMALL.0 / 2.0,
                        WAVE_SMALL.1,
                        WAVE_COLUMNS / 2,
                        found.seed.wrapping_add(1),
                        0.0,
                        palette::ACCENT,
                        palette::WAVE,
                    ))
                    .modifier(Modifier.weight(1.0).height(WAVE_SMALL.1));
                });
            Text(found.blurb)
                .type_role(TypeRole::Body)
                .color(palette::MUTED)
                .max_lines(3)
                .overflow(TextOverflow::Ellipsis);
            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::Center)
                .content(|| {
                    engagement(found, false);
                    Spacer().modifier(Modifier.weight(1.0));
                    more_action();
                    // The one filled control on the card, and the only orange circle on
                    // the page that is not a destination. A character rather than an
                    // icon: the closed set of meanings has no play yet.
                    let play = Rc::clone(on_play);
                    Button("\u{25b6}")
                        .variant(ButtonVariant::Text)
                        .color(palette::PAGE)
                        .modifier(
                            Modifier
                                .size(ACTION, ACTION)
                                .background(palette::ACCENT)
                                .shape_role(ShapeRole::Full),
                        )
                        .on_click(move || play(id));
                });
        });
}

/// A show as a row, with a follow button.
///
/// The recommended row is a solid orange pill with white on it and a paler orange follow
/// button, and the rest are the same shape on the page with a hairline round them and the
/// orange on the button instead. That is the reference: one row picked out, the others
/// quiet.
#[Composable]
fn show_row(show: &Show, followed: bool, recommended: bool, on_toggle: Rc<dyn Fn()>) {
    let ink = if recommended {
        palette::PAGE
    } else {
        palette::INK
    };
    let quiet = if recommended {
        palette::PAGE
    } else {
        palette::MUTED
    };
    let fill = if recommended {
        palette::ACCENT
    } else {
        palette::PAGE
    };
    let edge = if recommended { 0.0 } else { 1.0 };
    Row()
        .modifier(
            Modifier
                .fill_max_width()
                .background(fill)
                .border(edge, palette::OUTLINE)
                .shape_role(ShapeRole::Full)
                .padding_role(SpaceRole::Sm),
        )
        .space_role(SpaceRole::Sm)
        .alignment(Alignment::CenterStart)
        .content(|| {
            cover_shaped(COVER_ROW, show, Some(ShapeRole::Full));
            Column().modifier(Modifier.weight(1.0)).content(|| {
                Text(show.name)
                    .type_role(TypeRole::BodyStrong)
                    .color(ink)
                    .max_lines(1)
                    .overflow(TextOverflow::Ellipsis);
                Text(format!("{} followers", short_count(show.followers)))
                    .type_role(TypeRole::Caption)
                    .color(quiet);
            });
            // On the recommended row the button is white let through onto the row's own
            // orange, and on the others it is the one filled thing on a white row.
            let button = if recommended {
                palette::ON_ACCENT_SOFT
            } else {
                palette::ACCENT
            };
            Button(if followed { "Following" } else { "Follow" })
                .variant(ButtonVariant::Text)
                .color(palette::PAGE)
                .modifier(Modifier.shape_role(ShapeRole::Full).background(button))
                .on_click(move || on_toggle());
        });
}

/// The two outlined squares above the headline: everything laid out on the left, what is
/// new for you on the right.
fn top_row() {
    Row()
        .modifier(Modifier.fill_max_width())
        .arrangement(Arrangement::SpaceBetween)
        .alignment(Alignment::Center)
        .content(|| {
            face_button(
                SQUARE,
                ShapeRole::Medium,
                None,
                Some(palette::OUTLINE),
                name::BROWSE,
                || mark_face(library::grid(MARK, palette::INK)),
                not_here("Browsing"),
            );
            face_button(
                SQUARE,
                ShapeRole::Medium,
                None,
                Some(palette::OUTLINE),
                name::NOTIFICATIONS,
                || mark_face(library::bell(MARK, palette::INK)),
                not_here("Notifications"),
            );
        });
}

/// The two squares, the headline and the shelf, then the newest episode in full, then who
/// to follow.
#[Composable]
fn new_page(followed: MutableState<Vec<usize>>, on_play: Handler<u32>, on_open: Handler<u32>) {
    let following = followed.get();
    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            top_row();

            Text("New Episodes")
                .type_role(TypeRole::Headline)
                .font_weight(500)
                .color(palette::INK);

            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::Center)
                .content(|| {
                    // The shelf begins with an orange tile the shape of a cover, which is
                    // how the reference separates "look for something" from "here is what
                    // is new".
                    Box()
                        .modifier(
                            Modifier
                                .size(COVER_SMALL, COVER_SMALL)
                                .background(palette::ACCENT)
                                .shape_role(ShapeRole::Large),
                        )
                        .content(|| {
                            let search = not_here("Search");
                            icon_button(
                                IconRole::Search,
                                name::SEARCH,
                                palette::PAGE,
                                Modifier.fill_max_size(),
                                move || search(),
                            );
                        });
                    // The shelf across, windowed. A cover that is not on screen is a
                    // picture nobody has asked the Renderer to draw.
                    let on_open = Rc::clone(&on_open);
                    LazyRow()
                        .modifier(Modifier.weight(1.0).height(COVER_SMALL + 16.0))
                        .content(move |list| {
                            list.items_keyed(
                                EPISODES.len(),
                                |position| EPISODES[position].id.to_string(),
                                move |position| {
                                    let found = EPISODES[position];
                                    let open = Rc::clone(&on_open);
                                    Box()
                                        .modifier(Modifier.padding_role(SpaceRole::Xs))
                                        .content(|| {
                                            // The inner box is measured, not wrapped. A
                                            // child that fills its parent inside a box
                                            // with no size of its own takes the whole
                                            // width the shelf was offered, and the strip
                                            // becomes one cover per screen.
                                            Box()
                                                .modifier(Modifier.size(COVER_SMALL, COVER_SMALL))
                                                .alignment(Alignment::Center)
                                                .content(|| {
                                                    cover(COVER_SMALL, &SHOWS[found.show]);
                                                    Button("")
                                                        .variant(ButtonVariant::Text)
                                                        .modifier(Modifier.fill_max_size())
                                                        .on_click(move || open(found.id));
                                                });
                                        });
                                },
                            );
                        });
                });

            episode_card(&EPISODES[0], &on_play);

            Text("Podcasts You May Like")
                .type_role(TypeRole::Subtitle)
                .color(palette::INK);
            Column()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .content(|| {
                    let order = std::iter::once(RECOMMENDED)
                        .chain((0..SHOWS.len()).filter(|index| *index != RECOMMENDED));
                    for index in order {
                        let show = &SHOWS[index];
                        let is_followed = following.contains(&index);
                        let list = followed;
                        key(show.name, || {
                            show_row(
                                show,
                                is_followed,
                                index == RECOMMENDED,
                                Rc::new(move || {
                                    list.update(|list| {
                                        match list.iter().position(|found| *found == index) {
                                            Some(at) => {
                                                list.remove(at);
                                            }
                                            None => list.push(index),
                                        }
                                    })
                                }),
                            );
                        });
                    }
                });
        });
}

/// Every episode, shortest first, as a plain list.
#[Composable]
fn shows_page(on_play: Handler<u32>) {
    let mut ordered: Vec<&'static Episode> = EPISODES.iter().collect();
    ordered.sort_by_key(|found| found.seconds);
    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("Shows")
                .type_role(TypeRole::Headline)
                .color(palette::INK);
            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(palette::PAGE)
                        .border(1.0, palette::OUTLINE)
                        .shape_role(ShapeRole::Large),
                )
                .content(|| {
                    for (index, found) in ordered.iter().copied().enumerate() {
                        let last = index + 1 == EPISODES.len();
                        let play = Rc::clone(&on_play);
                        key(found.id, || {
                            shows_row(found, last, play);
                        });
                    }
                });
        });
}

/// One row of the plain list, with the hairline under every row but the last.
#[Composable]
fn shows_row(found: &'static Episode, last: bool, on_play: Handler<u32>) {
    Column().modifier(Modifier.fill_max_width()).content(|| {
        Row()
            .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
            .space_role(SpaceRole::Sm)
            .alignment(Alignment::CenterStart)
            .content(|| {
                cover(44.0, &SHOWS[found.show]);
                Column().modifier(Modifier.weight(1.0)).content(|| {
                    Text(found.title)
                        .type_role(TypeRole::Body)
                        .color(palette::INK)
                        .max_lines(1)
                        .overflow(TextOverflow::Ellipsis);
                    Text(format!(
                        "{} \u{00b7} {}",
                        SHOWS[found.show].name,
                        clock(found.seconds)
                    ))
                    .type_role(TypeRole::Caption)
                    .color(palette::MUTED);
                });
                let id = found.id;
                Button("\u{25b6}")
                    .variant(ButtonVariant::Text)
                    .color(palette::PAGE)
                    .modifier(
                        Modifier
                            .height(ACTION + 8.0)
                            .background(palette::ACCENT)
                            .shape_role(ShapeRole::Full),
                    )
                    .on_click(move || on_play(id));
            });
        if !last {
            Separator(Some(palette::OUTLINE));
        }
    });
}

/// What you follow and what you have listened to.
#[Composable]
fn you_page(followed: usize) {
    let listened: u32 = EPISODES.iter().map(|found| found.seconds).sum();
    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("You")
                .type_role(TypeRole::Headline)
                .color(palette::INK);
            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Md)
                .content(|| {
                    Column()
                        .modifier(
                            Modifier
                                .weight(1.0)
                                .background(palette::ACCENT)
                                .shape_role(ShapeRole::Large)
                                .padding_role(SpaceRole::Md),
                        )
                        .content(|| {
                            Text(format!("{followed}"))
                                .type_role(TypeRole::Display)
                                .color(palette::PAGE);
                            Text("shows followed")
                                .type_role(TypeRole::Label)
                                .color(palette::PAGE);
                        });
                    Column()
                        .modifier(
                            Modifier
                                .weight(1.0)
                                .background(palette::TILE)
                                .shape_role(ShapeRole::Large)
                                .padding_role(SpaceRole::Md),
                        )
                        .content(|| {
                            Text(clock(listened))
                                .type_role(TypeRole::Display)
                                .color(palette::INK);
                            Text("in the queue")
                                .type_role(TypeRole::Label)
                                .color(palette::MUTED);
                        });
                });
        });
}

/// The player: the artwork full bleed across the top, then who and what, the counts, the
/// waveform and the transport.
///
/// The artwork runs to the top and both sides of the screen with only its bottom corners
/// rounded, and the way back and the way to share stand on it as outlined squares, which
/// is the reference's player: an orange picture rather than a header above one.
#[Composable]
fn player_page(found: &'static Episode, position: MutableState<u32>, on_back: Rc<dyn Fn()>) {
    let show = show_of(found);
    let at = position.get();
    let played = at as f32 / found.seconds.max(1) as f32;
    let length = found.seconds;

    ScrollColumn()
        .modifier(Modifier.fill_max_size().background(palette::PAGE))
        .content(|| {
            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(palette::ACCENT)
                        .corner_radii([0.0, 0.0, ART_CORNER, ART_CORNER])
                        .clip(true)
                        .padding_role(SpaceRole::Md),
                )
                .space_role(SpaceRole::Sm)
                .content(|| {
                    Row()
                        .modifier(Modifier.fill_max_width())
                        .alignment(Alignment::Center)
                        .content(|| {
                            Box()
                                .modifier(
                                    Modifier
                                        .size(SQUARE, SQUARE)
                                        .shape_role(ShapeRole::Medium)
                                        .border(1.0, palette::ON_ACCENT_EDGE),
                                )
                                .content(|| {
                                    let back = Rc::clone(&on_back);
                                    icon_button(
                                        IconRole::Back,
                                        name::BACK,
                                        palette::PAGE,
                                        Modifier.fill_max_size(),
                                        move || back(),
                                    );
                                });
                            Spacer().modifier(Modifier.weight(1.0));
                            Text(show.name)
                                .type_role(TypeRole::BodyStrong)
                                .color(palette::PAGE);
                            Spacer().modifier(Modifier.weight(1.0));
                            face_button(
                                SQUARE,
                                ShapeRole::Medium,
                                None,
                                Some(palette::ON_ACCENT_EDGE),
                                name::SHARE,
                                || mark_face(library::share(MARK, palette::PAGE)),
                                not_here("Sharing"),
                            );
                        });
                    // The cover square on the orange, unrounded, so it reads as the
                    // picture the orange belongs to rather than a tile laid on it.
                    Box()
                        .modifier(Modifier.fill_max_width())
                        .alignment(Alignment::Center)
                        .content(|| cover_shaped(COVER_LARGE, show, None));
                });

            Column()
                .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
                .space_role(SpaceRole::Sm)
                .content(|| {
                    Row()
                        .modifier(Modifier.fill_max_width())
                        .space_role(SpaceRole::Sm)
                        .alignment(Alignment::CenterStart)
                        .content(|| {
                            // The host's picture is the show's own cover in a circle: the
                            // sample carries no photographs of people.
                            cover_shaped(AVATAR, show, Some(ShapeRole::Full));
                            Text(show.host)
                                .type_role(TypeRole::Label)
                                .color(palette::INK)
                                .modifier(Modifier.weight(1.0));
                            // A character rather than an icon: the closed set of meanings
                            // has no star yet.
                            Text("\u{2605}")
                                .type_role(TypeRole::Label)
                                .color(palette::ACCENT);
                            Text("4.7").type_role(TypeRole::Label).color(palette::INK);
                        });
                    Text(found.title)
                        .type_role(TypeRole::Title)
                        .color(palette::INK);
                    Text(found.blurb)
                        .type_role(TypeRole::Body)
                        .color(palette::MUTED)
                        .max_lines(3)
                        .overflow(TextOverflow::Ellipsis);

                    // The counts live here rather than on the card, as they do in the
                    // reference.
                    Row()
                        .modifier(Modifier.fill_max_width())
                        .space_role(SpaceRole::Sm)
                        .alignment(Alignment::Center)
                        .content(|| {
                            engagement(found, true);
                            Spacer().modifier(Modifier.weight(1.0));
                            more_action();
                        });

                    Canvas(waveform(
                        WAVE.0,
                        WAVE.1,
                        WAVE_COLUMNS,
                        found.seed,
                        played,
                        palette::ACCENT,
                        palette::WAVE,
                    ))
                    .modifier(Modifier.fill_max_width().height(WAVE.1));
                    Row()
                        .modifier(Modifier.fill_max_width())
                        .alignment(Alignment::CenterStart)
                        .content(|| {
                            Text(clock(at))
                                .type_role(TypeRole::Caption)
                                .color(palette::ACCENT)
                                .modifier(Modifier.weight(1.0));
                            Text(clock(length))
                                .type_role(TypeRole::Caption)
                                .color(palette::INK);
                        });

                    // The transport. Skipping is the back and forward meanings; play is a
                    // character, because the closed set has no play, pause or shuffle yet,
                    // and the shuffle and sleep timer the reference puts at either end
                    // are left off rather than drawn as something they are not.
                    Row()
                        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
                        .space_role(SpaceRole::Lg)
                        .alignment(Alignment::Center)
                        .arrangement(Arrangement::Center)
                        .content(|| {
                            let back = position;
                            icon_button(
                                IconRole::Back,
                                name::SKIP_BACK,
                                palette::INK,
                                Modifier.size(ROUND, ROUND),
                                move || back.set(back.get_untracked().saturating_sub(SKIP_SECONDS)),
                            );
                            let play = not_here("Playback");
                            Button("\u{25b6}")
                                .variant(ButtonVariant::Text)
                                .color(palette::PAGE)
                                .modifier(
                                    Modifier
                                        .size(ROUND, ROUND)
                                        .background(palette::ACCENT)
                                        .shape_role(ShapeRole::Full),
                                )
                                .on_click(move || play());
                            let ahead = position;
                            icon_button(
                                IconRole::Forward,
                                name::SKIP_AHEAD,
                                palette::INK,
                                Modifier.size(ROUND, ROUND),
                                move || {
                                    ahead.set((ahead.get_untracked() + SKIP_SECONDS).min(length))
                                },
                            );
                        });
                });
        });
}

/// The destinations: three round buttons floating on the page.
///
/// Drawn here rather than declared as a `Navigation`, which is the widget for "the
/// destinations, in whatever shape this design system and this window call for": a
/// labelled bar with a selection pill under a hairline. The reference is three circles
/// sitting on the page, the one you are on filled with the accent, and that is a shape no
/// design system would be right to give a set of destinations.
#[Composable]
fn bottom_bar(destination: Destination, on_go: Handler<Destination>) {
    Row()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Sm))
        .space_role(SpaceRole::Sm)
        .arrangement(Arrangement::Center)
        .alignment(Alignment::Center)
        .content(|| {
            for choice in Destination::STRIP {
                let selected = choice == destination;
                let fill = if selected {
                    palette::ACCENT
                } else {
                    palette::TILE
                };
                let tint = if selected {
                    palette::PAGE
                } else {
                    palette::INK
                };
                let go = Rc::clone(&on_go);
                key(choice.label(), || {
                    face_button(
                        ROUND,
                        ShapeRole::Full,
                        Some(fill),
                        None,
                        choice.label(),
                        || icon_face(choice.icon(), tint),
                        Rc::new(move || go(choice)),
                    );
                });
            }
        });
}

/// A screen that covers the whole window, the bar included, laid out in the phone's
/// measure when the window is wider than a phone.
fn whole_window(measure: Option<f32>, content: impl FnOnce()) {
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
                .content(content);
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

    let welcomed = remember(|| mutable_state_of(false));
    let destination = remember(|| mutable_state_of(Destination::FIRST));
    let playing = remember(|| mutable_state_of(Option::<u32>::None));
    let position = remember(|| mutable_state_of(START_SECONDS));
    let followed = remember(|| mutable_state_of(vec![0_usize]));

    let on_play: Handler<u32> = { Rc::new(move |id: u32| playing.set(Some(id))) };

    // The welcome screen comes first and covers everything, as the reference's first
    // screen does: there is no bar under it, only the way in.
    if !welcomed.get() {
        whole_window(measure, || {
            welcome_page(Rc::new(move || welcomed.set(true)));
        });
        return;
    }

    // The player covers everything, including the bar along the bottom, because a full
    // screen player is what the reference draws and a bar under it would be a way out of
    // a screen whose only way out is its own back button.
    if let Some(found) = playing.get().and_then(episode) {
        whole_window(measure, || {
            let close = playing;
            player_page(found, position, Rc::new(move || close.set(None)));
        });
        return;
    }

    let current = destination.get();

    // The frame is named rather than built. Whether the bar runs across the bottom, stands
    // down the leading edge or opens as a drawer is the frame's answer from the width it
    // was given, and the page is laid out clear of whatever it took.
    Scaffold()
        .modifier(Modifier.background(palette::PAGE))
        .bottom_bar(|| {
            Box()
                .modifier(Modifier.fill_max_width())
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
                            bottom_bar(current, Rc::new(move |choice| go.set(choice)));
                        });
                });
        })
        .content(|| {
            Box()
                .modifier(Modifier.weight(1.0))
                .alignment(Alignment::TopCenter)
                .content(|| {
                    ScrollColumn()
                        .modifier(Modifier.width_if(measure))
                        .content(|| match current {
                            Destination::New => {
                                new_page(followed, Rc::clone(&on_play), Rc::clone(&on_play))
                            }
                            Destination::Shows => shows_page(Rc::clone(&on_play)),
                            Destination::You => you_page(followed.with(Vec::len)),
                        });
                });
        });
}

/// The design this sample draws, named once.
///
/// One design system everywhere, because the design is the product here rather than the
/// platform's convention, and light because the reference is white pages with orange as
/// the single accent. The player's cover art is a full-bleed orange picture rather than a
/// dark page.
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
/// The theme above all: a sample that names one and then reaches a platform through an
/// entry point that makes its own builder is a sample that draws the same screens in a
/// different design system depending on where it runs.
///
/// `demo_theme_for` rather than `THEME` alone: a sample is something to look at, and one
/// machine can only show the design system and the scheme it is set to. `DXC_DESIGN` and
/// `DXC_SCHEME` each override the half they name, so `THEME` stays the answer to
/// everything nobody asked about.
fn launch_builder() -> LaunchBuilder {
    // The name the window carries. A desktop lists windows by it, so a window that said
    // nothing was listed under whatever the renderer happened to be called.
    LaunchBuilder::new()
        // The same as [bundle] identifier in Dioxus.toml. It names the storage.
        .identifier("dev.darkpyonix.composerust.samples.podcast")
        .with_theme(compose_rust::demo_theme_for(THEME))
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Podcast")
                // Without one the window wears the toolkit's picture wherever the system
                // lists windows. The bytes travel as an asset and the renderer refers to
                // them by id: a path would be a fact about the machine this was built on.
                .with_icon(compose_rust::asset::asset(
                    AssetKind::Png,
                    include_bytes!("../assets/icon.png"),
                )),
        )
}

// The platforms where the sample is not a program. Android's Activity and the browser's
// page both own the loop, so neither has a `main` to call: each names an entry point that
// registers the root composable, and these macros define it.
//
// All three are declared unconditionally. Each compiles into nothing that runs off its own
// platform, and gating them here instead would mean a desktop build never checks that this
// sample can still be built for the others.
compose_rust::android_application!({ launch_builder() }, app);
compose_rust::web_application!({ launch_builder() }, app);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;

    use compose_rust::ComposeHost;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
    use compose_rust::schema::{EventPayload, Modifier as WireModifier, PropertyKind, WidgetKind};

    /// Named for what it defends: the reference is a light design, and a machine set
    /// the other way drew this sample dark with nothing to compare against.
    #[test]
    fn fr14_the_design_names_its_colour_scheme() {
        // Through the wire rather than off the constant: what settles the question is the
        // record the Renderer reads, and a scheme that never leaves the Host is a scheme
        // nobody is drawn in.
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
            let mut host = ComposeHost::new(app);
            let first = host.rebuild().expect("the first frame failed").to_vec();
            Self {
                host,
                frames: vec![first],
            }
        }

        fn send(&mut self, event: HostEvent<'_>) {
            let mut bytes = Vec::new();
            encode_event(&event, &mut bytes).expect("the event did not encode");
            let (batch, _) = self.host.dispatch_event(&bytes).expect("the event failed");
            if !batch.is_empty() {
                self.frames.push(batch.to_vec());
            }
        }

        fn mutations(&self) -> Vec<Mutation<'_>> {
            self.frames
                .iter()
                .flat_map(|frame| decode_batch(frame).expect("a batch did not decode"))
                .collect()
        }

        /// The node that last carried this text.
        fn node_saying(&self, label: &str) -> Option<u32> {
            self.mutations()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                    } if *text == label => Some(*node_id),
                    _ => None,
                })
        }

        /// The id an icon of this meaning was registered under. A registration carries the
        /// meaning's wire tag rather than a picture, so the bytes asked for here are the
        /// two the sample sent.
        fn icon_asset(&self, tag: &[u8]) -> Option<u32> {
            self.mutations().iter().find_map(|mutation| match mutation {
                Mutation::RegisterAsset {
                    asset_id,
                    kind: AssetKind::VectorIcon,
                    bytes,
                } if *bytes == tag => Some(*asset_id),
                _ => None,
            })
        }

        /// The node that last drew this asset.
        fn node_drawing(&self, asset_id: u32) -> Option<u32> {
            self.mutations()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Asset,
                        value: PropertyValue::Integer(id),
                    } if *id as u32 == asset_id => Some(*node_id),
                    _ => None,
                })
        }

        /// What each node was inserted into.
        fn parents(&self) -> Vec<(u32, u32)> {
            self.mutations()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::Insert {
                        parent_id, node_id, ..
                    } => Some((*node_id, *parent_id)),
                    _ => None,
                })
                .collect()
        }

        fn handler_of(&self, node: u32) -> Option<u64> {
            self.mutations()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::OnClick,
                        value: PropertyValue::Integer(handler),
                    } if *node_id == node => Some(*handler as u64),
                    _ => None,
                })
        }

        /// Presses the control placed beside this one.
        ///
        /// A control whose face is a drawing has no words to name it: what carries the
        /// press is a `Button` with no label sitting over the picture. So the press is
        /// asked for by what the reader can see, and found by walking one step up and back
        /// down to whatever under there answers a click.
        fn press_beside(&mut self, node: u32) -> bool {
            let parents = self.parents();
            let Some(parent) = parents
                .iter()
                .rev()
                .find_map(|(child, owner)| (*child == node).then_some(*owner))
            else {
                return false;
            };
            let mut family = vec![parent];
            let mut found = None;
            while let Some(next) = family.pop() {
                if next != node && self.handler_of(next).is_some() {
                    found = Some(next);
                    break;
                }
                family.extend(
                    parents
                        .iter()
                        .filter_map(|(child, owner)| (*owner == next).then_some(*child)),
                );
            }
            let Some(node_id) = found else { return false };
            let Some(handler_id) = self.handler_of(node_id) else {
                return false;
            };
            self.send(HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::Clicked,
            });
            true
        }

        fn press(&mut self, label: &str) -> bool {
            let Some(node) = self.node_saying(label) else {
                return false;
            };
            let Some(handler_id) = self.handler_of(node) else {
                return false;
            };
            self.send(HostEvent {
                node_id: node,
                handler_id,
                payload: EventPayload::Clicked,
            });
            true
        }

        /// Presses a destination along the bottom, which is an icon with nothing written
        /// under it.
        fn press_icon(&mut self, tag: &[u8]) -> bool {
            let Some(asset_id) = self.icon_asset(tag) else {
                return false;
            };
            let Some(node) = self.node_drawing(asset_id) else {
                return false;
            };
            self.press_beside(node)
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

        /// The mutations of the frame drawn last: what the screen became on the last press.
        fn latest(&self) -> Vec<Mutation<'_>> {
            self.frames
                .last()
                .map(|frame| decode_batch(frame).expect("the batch did not decode"))
                .unwrap_or_default()
        }

        /// The node that last carried this accessible name. A control whose face is a
        /// drawing has no words on it, so its name is how it is found.
        fn node_named(&self, name: &str) -> Option<u32> {
            self.mutations()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::AccessibleName,
                        value: PropertyValue::String(text),
                    } if *text == name => Some(*node_id),
                    _ => None,
                })
        }

        fn press_named(&mut self, name: &str) -> bool {
            let Some(node_id) = self.node_named(name) else {
                return false;
            };
            let Some(handler_id) = self.handler_of(node_id) else {
                return false;
            };
            self.send(HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::Clicked,
            });
            true
        }

        /// The screen past its welcome, on the hub.
        fn hub() -> Self {
            let mut screen = Self::new();
            assert!(
                screen.press_named(name::LISTEN),
                "the welcome screen has no way in"
            );
            screen
        }

        /// The hub, with its card's play pressed: the player.
        fn player() -> Self {
            let mut screen = Self::hub();
            assert!(screen.press("\u{25b6}"), "nothing on the hub plays");
            screen
        }

        fn parent_of(&self, node: u32) -> Option<u32> {
            self.parents()
                .iter()
                .rev()
                .find_map(|(child, owner)| (*child == node).then_some(*owner))
        }

        /// What a node was last filled with.
        fn background_of(&self, node: u32) -> Option<Paint> {
            self.mutations()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        node_id,
                        modifier: WireModifier::Background(paint),
                        ..
                    } if *node_id == node => Some(*paint),
                    _ => None,
                })
        }

        /// Every modifier the last frame gave this node.
        fn modifiers_of(&self, node: u32) -> Vec<WireModifier> {
            self.latest()
                .into_iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        node_id, modifier, ..
                    } if node_id == node => Some(modifier),
                    _ => None,
                })
                .collect()
        }

        /// Every meaning a button in the last frame carries.
        fn latest_button_icons(&self) -> Vec<i64> {
            self.latest()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetProp {
                        property: PropertyKind::Icon,
                        value: PropertyValue::Integer(value),
                        ..
                    } => Some(*value),
                    _ => None,
                })
                .collect()
        }

        /// What the last frame filled things with, in the order it filled them.
        fn latest_fills(&self) -> Vec<Paint> {
            self.latest()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        modifier: WireModifier::Background(paint),
                        ..
                    } => Some(*paint),
                    _ => None,
                })
                .collect()
        }
    }

    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        assert!(ComposeHost::new(app).rebuild().is_ok());
    }

    /// The reference opens on its welcome: a picture, the promise and one way in. That way
    /// in leads to the hub.
    #[test]
    fn fr22_the_app_opens_on_the_welcome_and_listening_leads_to_the_hub() {
        let mut screen = Screen::new();
        let first = screen.latest_texts();
        assert!(
            first
                .iter()
                .any(|text| text == "The Best Podcast\nof the Year"),
            "the app does not open on its welcome: {first:?}"
        );
        assert!(
            !first.iter().any(|text| text == "New Episodes"),
            "the hub is drawn under the welcome"
        );
        assert!(
            screen.press_named(name::LISTEN),
            "the welcome screen has no way in"
        );
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text == "New Episodes"),
            "listening did not lead to the hub"
        );
    }

    /// The welcome screen's progress is three segments with the first in the accent, and
    /// nothing on it is painted by the design system.
    #[test]
    fn fr22_the_welcome_shows_the_first_of_three_steps() {
        let screen = Screen::new();
        let fills = screen.latest_fills();
        assert_eq!(
            fills
                .iter()
                .filter(|paint| **paint == palette::TILE)
                .count(),
            WELCOME_STEPS - 1,
            "the steps still to come are the grey segments: {fills:?}"
        );
        assert!(
            screen.mutations().iter().any(|mutation| matches!(
                mutation,
                Mutation::RegisterAsset { bytes, .. } if *bytes == WELCOME
            )),
            "the welcome has no picture"
        );
    }

    /// The bar opens on the hub, and the hub is the middle circle: the reference's bar is a
    /// house, the compass filled orange, and a person, drawn on the hub.
    #[test]
    fn fr22_the_bar_opens_on_the_hub_in_the_middle() {
        assert_eq!(
            Destination::STRIP[Destination::STRIP.len() / 2],
            Destination::New,
            "the hub is not the middle destination"
        );
        assert_eq!(Destination::FIRST, Destination::New);
        let screen = Screen::hub();
        for choice in Destination::STRIP {
            let asset = screen
                .icon_asset(choice.icon())
                .unwrap_or_else(|| panic!("the bar has no icon for {}", choice.label()));
            let icon = screen
                .node_drawing(asset)
                .unwrap_or_else(|| panic!("nothing draws the icon for {}", choice.label()));
            let circle = screen
                .parent_of(icon)
                .unwrap_or_else(|| panic!("the icon for {} stands alone", choice.label()));
            let expected = if choice == Destination::New {
                palette::ACCENT
            } else {
                palette::TILE
            };
            assert_eq!(
                screen.background_of(circle),
                Some(expected),
                "{} is not filled as the reference fills it",
                choice.label()
            );
        }
    }

    /// Every destination has to encode, not just the one the app opens on.
    ///
    /// Pressed by its icon, because the bar has no words on it: three circles on the page
    /// with the one you are on filled, which is the reference's bar.
    #[test]
    fn fr15_every_destination_encodes() {
        let mut screen = Screen::hub();
        for choice in Destination::STRIP {
            assert!(
                screen.press_icon(choice.icon()),
                "the bar has no icon for {}",
                choice.label()
            );
        }
    }

    /// Above the headline stand two outlined squares, the grid at the start and the bell
    /// at the end, as in the reference.
    #[test]
    fn fr22_the_hub_has_its_top_row() {
        let mut screen = Screen::hub();
        for name in [name::BROWSE, name::NOTIFICATIONS] {
            let button = screen
                .node_named(name)
                .unwrap_or_else(|| panic!("the hub has no {name} button"));
            let square = screen
                .parent_of(button)
                .unwrap_or_else(|| panic!("the {name} button stands alone"));
            let modifiers = screen.modifiers_of(square);
            assert!(
                modifiers.iter().any(|modifier| matches!(
                    modifier,
                    WireModifier::Border { paint, .. } if *paint == palette::OUTLINE
                )),
                "the {name} square has no hairline: {modifiers:?}"
            );
            assert!(
                !modifiers
                    .iter()
                    .any(|modifier| matches!(modifier, WireModifier::Background(_))),
                "the {name} square is filled, where the reference only outlines it"
            );
        }
        // Pressed after looking, because a press draws a frame of its own and the squares
        // are looked up in the frame the hub was drawn in.
        for name in [name::BROWSE, name::NOTIFICATIONS] {
            assert!(
                screen.press_named(name),
                "the {name} button cannot be pressed"
            );
        }
    }

    /// The shelf starts with a search tile the shape of the covers beside it, rather than a
    /// circle, and the card keeps its counts off and ends in a "more" circle.
    #[test]
    fn fr22_the_shelf_and_card_match_the_reference() {
        let screen = Screen::hub();
        let search = screen
            .node_named(name::SEARCH)
            .expect("the shelf has no search");
        let tile = screen.parent_of(search).expect("the search stands alone");
        let modifiers = screen.modifiers_of(tile);
        assert!(
            modifiers
                .iter()
                .any(|modifier| *modifier == WireModifier::ShapeRole(ShapeRole::Large)),
            "the search tile is not the covers' shape: {modifiers:?}"
        );
        assert!(
            modifiers.contains(&WireModifier::Width(COVER_SMALL))
                && modifiers.contains(&WireModifier::Height(COVER_SMALL)),
            "the search tile is not the covers' size: {modifiers:?}"
        );
        assert!(
            screen
                .latest_button_icons()
                .contains(&(IconRole::Search as i64)),
            "the search tile does not carry the search meaning"
        );
        let texts = screen.latest_texts();
        let found = &EPISODES[0];
        for count in [found.hearts, found.comments, found.shares] {
            assert!(
                !texts.contains(&short_count(count)),
                "the card shows the count {count}, which the reference keeps for the player"
            );
        }
        assert!(
            screen.node_named(name::MORE).is_some(),
            "the card has no more circle"
        );
        assert!(
            screen.icon_asset(&icon::MORE).is_some(),
            "the more circle does not carry the more meaning"
        );
    }

    /// The recommended row is The Researcher, first in the list, as in the reference.
    #[test]
    fn fr22_the_recommended_row_is_the_researcher_first() {
        let screen = Screen::hub();
        let names: Vec<String> = screen
            .latest_texts()
            .into_iter()
            .filter(|text| SHOWS.iter().any(|show| show.name == text.as_str()))
            .collect();
        assert_eq!(
            names.first().map(String::as_str),
            Some(SHOWS[RECOMMENDED].name),
            "the list does not open on the recommended show: {names:?}"
        );
        assert_eq!(SHOWS[RECOMMENDED].name, "The Researcher");
    }

    /// Opening the player replaces the whole screen and its back button returns.
    #[test]
    fn fr15_the_player_opens_and_closes() {
        let mut screen = Screen::player();
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text == EPISODES[0].title),
            "the player did not come up"
        );
        assert!(screen.press_named(name::BACK), "the player has no way back");
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text == "New Episodes"),
            "closing the player did not bring the hub back"
        );
    }

    /// Skipping back moves the readout, and skipping ahead moves it back again.
    ///
    /// It does not necessarily move the waveform, and that is the point rather than a gap
    /// in the test. Forty columns over twelve minutes is eighteen seconds a column, so
    /// most fifteen second skips leave every column exactly as it was and the draw list
    /// compares equal to the one already on screen. Nothing about it crosses the boundary,
    /// which is what an attribute comparison is for. The waveform's own behaviour when the
    /// position does move a column is checked where it is built, in
    /// `fr16_the_played_part_of_the_waveform_grows_with_the_position`.
    #[test]
    fn fr4_skipping_back_moves_the_readout() {
        let mut screen = Screen::player();
        let before = clock(START_SECONDS);
        assert!(
            screen.latest_texts().contains(&before),
            "the player does not say where it is"
        );
        assert!(
            screen.press_named(name::SKIP_BACK),
            "the player cannot skip back"
        );
        assert!(
            screen
                .latest_texts()
                .contains(&clock(START_SECONDS - SKIP_SECONDS)),
            "skipping back left the readout where it was"
        );
        assert!(
            screen.press_named(name::SKIP_AHEAD),
            "the player cannot skip ahead"
        );
        assert!(
            screen.latest_texts().contains(&before),
            "skipping ahead did not bring the readout back"
        );
    }

    /// The player's controls are icons and drawings rather than words and arrows set in
    /// text, wherever the closed set of meanings has one: back and the skips are the back
    /// and forward meanings, share and the counts are drawn marks, and more is the more
    /// meaning. Play stays a character until the set has a play.
    #[test]
    fn fr22_the_player_controls_are_icons_rather_than_text() {
        let screen = Screen::player();
        let texts = screen.latest_texts();
        for glyph in ["\u{2190}", "Share", "\u{21ba} 15", "15 \u{21bb}"] {
            assert!(
                !texts.iter().any(|text| text == glyph),
                "the player still writes {glyph:?} where an icon belongs"
            );
        }
        let icons = screen.latest_button_icons();
        for role in [IconRole::Back, IconRole::Forward] {
            assert!(
                icons.contains(&(role as i64)),
                "the player has no {role:?} button: {icons:?}"
            );
        }
        for name in [
            name::BACK,
            name::SHARE,
            name::SKIP_BACK,
            name::SKIP_AHEAD,
            name::LIKES,
            name::COMMENTS,
            name::SHARES,
            name::MORE,
        ] {
            assert!(
                screen.node_named(name).is_some(),
                "the player has no {name} control"
            );
        }
        // The counts are on the player, as they are in the reference.
        let found = &EPISODES[0];
        for count in [found.hearts, found.comments, found.shares] {
            assert!(
                texts.contains(&short_count(count)),
                "the player does not show the count {count}"
            );
        }
    }

    /// The player's artwork runs to the top and both sides with only its bottom corners
    /// rounded, rather than an inset box under a header.
    #[test]
    fn fr22_the_player_artwork_is_full_bleed() {
        let screen = Screen::player();
        let latest = screen.latest();
        let art = latest
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetModifier {
                    node_id,
                    modifier:
                        WireModifier::Shape {
                            top_start,
                            top_end,
                            bottom_end,
                            bottom_start,
                        },
                    ..
                } if *top_start == 0.0
                    && *top_end == 0.0
                    && *bottom_end > 0.0
                    && *bottom_start > 0.0 =>
                {
                    Some(*node_id)
                }
                _ => None,
            })
            .expect("nothing on the player has square top corners and round bottom ones");
        assert_eq!(
            screen.background_of(art),
            Some(palette::ACCENT),
            "the artwork with the round bottom is not the orange"
        );
        assert!(
            screen.modifiers_of(art).contains(&WireModifier::Clip(true)),
            "the artwork is not cut to its rounded bottom"
        );
        // Nothing round it pushes it in from the edges of the screen.
        let mut at = screen.parent_of(art);
        while let Some(node) = at {
            let padded = screen.modifiers_of(node).into_iter().any(|modifier| {
                matches!(
                    modifier,
                    WireModifier::Padding(_)
                        | WireModifier::PaddingRole(_)
                        | WireModifier::PaddingEach { .. }
                )
            });
            assert!(!padded, "node {node} pads the artwork in from the edges");
            at = screen.parent_of(node);
        }
    }

    /// Every show's cover reaches the Renderer as a picture, and each one crosses once.
    ///
    /// Named for what it defends: a cover that appears in the strip, on the episode card
    /// and again in the player is several nodes and has to be one registration, or a
    /// screen with five episodes on it sends five copies of the same poster.
    #[test]
    fn fr16_every_cover_crosses_once_as_a_picture() {
        let screen = Screen::hub();
        let mutations = screen.mutations();
        for show in SHOWS {
            assert_eq!(
                mutations
                    .iter()
                    .filter(|mutation| matches!(
                        mutation,
                        Mutation::RegisterAsset { bytes, .. } if *bytes == show.cover
                    ))
                    .count(),
                1,
                "{} has no cover, or sent it more than once",
                show.name
            );
        }
    }

    /// Every drawing on the hub has to reach the Renderer with commands on it. A canvas
    /// with none is a blank square, and on this screen most of the artwork is canvases.
    #[test]
    fn fr16_every_canvas_on_the_shelf_carries_a_draw_list() {
        let screen = Screen::hub();
        let mutations = screen.mutations();
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
        assert!(!canvases.is_empty(), "the hub draws nothing at all");
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
                "a canvas reached the Renderer with no draw list"
            );
        }
    }

    /// Nothing on any screen is painted by the design system: the welcome, the hub and the
    /// player.
    ///
    /// Named for what it defends: the rows under "Podcasts You May Like" used to be the
    /// three accent containers, so they came out pale blue, lilac and pink, and every
    /// accent on the page was the theme's blue. The reference has one orange and one
    /// hairline grey.
    #[test]
    fn fr22_nothing_on_the_screen_is_painted_by_a_role() {
        let screen = Screen::player();
        for mutation in screen.mutations() {
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
            }
        }
    }

    /// The destination you are on is the one filled circle in the bar, and the row being
    /// recommended is the one orange row in the list.
    #[test]
    fn fr22_the_accent_marks_the_destination_and_the_recommended_row() {
        let screen = Screen::hub();
        let fills = screen.latest_fills();
        let accents = fills
            .iter()
            .filter(|paint| **paint == palette::ACCENT)
            .count();
        // The search tile, the play button on the card, the recommended row, the follow
        // button on each of the two quiet rows, and the destination the hub opens on.
        assert_eq!(
            accents, 6,
            "the accent is on the wrong number of things: {fills:?}"
        );
        assert_eq!(
            fills
                .iter()
                .filter(|paint| **paint == palette::ON_ACCENT_SOFT)
                .count(),
            1,
            "the recommended row's follow button is not the paler orange"
        );
        assert_eq!(
            fills
                .iter()
                .filter(|paint| **paint == palette::TILE)
                .count(),
            Destination::STRIP.len() - 1,
            "the destinations you are not on are the grey circles"
        );
    }

    /// The welcome, in the design system it ships, in both schemes, at all three widths.
    #[test]
    fn fr16_the_welcome_is_recorded() {
        sample_frames::record_as(
            "PodcastWelcome",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |_| {},
        );
    }

    /// The hub, in the design system it ships, in both schemes, at all three widths.
    #[test]
    fn fr16_the_shelf_is_recorded_in_the_system_it_ships() {
        sample_frames::record_as(
            "Podcast",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    screen.press_beside(name::LISTEN),
                    "the welcome screen has no way in"
                );
                assert_eq!(
                    screen.fill_lists(5),
                    1,
                    "the shelf should hold exactly one windowing list"
                );
            },
        );
    }

    /// The player, which is the picture the waveform is for.
    #[test]
    fn fr16_the_player_is_recorded() {
        sample_frames::record_as(
            "PodcastPlayer",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    screen.press_beside(name::LISTEN),
                    "the welcome screen has no way in"
                );
                screen.fill_lists(5);
                assert!(screen.press("\u{25b6}"), "nothing on the hub plays");
            },
        );
    }
}
