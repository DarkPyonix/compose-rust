//! A podcast hub: new episodes, an episode you can open, and a player.
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

use dioxus_compose::DrawList;
use dioxus_compose::prelude::*;
use library::{EPISODES, Episode, SHOWS, Show, clock, episode, short_count, show_of, waveform};

/// A phone design in a desktop window is still a phone design.
const PAGE_MEASURE: f32 = 420.0;

/// How large each drawing is. Numbers, because these are the proportions of a picture.
const COVER_LARGE: f32 = 300.0;
const COVER_SMALL: f32 = 72.0;
const WAVE: (f32, f32) = (340.0, 72.0);
const WAVE_SMALL: (f32, f32) = (300.0, 48.0);
/// How many columns a waveform is drawn with.
///
/// The reference draws a few hundred hair-thin ones. Forty reads as a waveform and costs
/// forty records, which is a budget a frame can carry without anyone thinking about it.
const WAVE_COLUMNS: usize = 40;

/// The round controls: the search button at the head of the shelf, the destinations along
/// the bottom, and the three actions under an episode.
const ROUND: f32 = 52.0;
const ACTION: f32 = 36.0;
const MARK: f32 = 18.0;

/// Where the player picks up, in seconds. Part way in, because a player sitting at zero
/// shows a waveform with nothing played and says nothing about what the drawing does.
const START_SECONDS: u32 = 326;

/// How much of the half waveform on the left of a card is drawn as played.
///
/// All of it. The card draws one waveform in two halves with the cover between them, so
/// the playhead sits behind the artwork: everything to its left is the accent and
/// everything to its right is grey, which is what the reference draws.
const CARD_PLAYED: f32 = 1.0;

/// The icons this hub draws, as the bytes each registration carries: the meaning's wire
/// tag, little endian, and nothing else.
///
/// An icon is a meaning rather than a picture. The Renderer holds the artwork for every
/// design system, so `Home` comes out as this system's house and the hub never says what
/// a house looks like. The three marks under an episode are not in that set, so they are
/// drawings instead; they live in `library` beside the waveform.
mod icon {
    use dioxus_compose::prelude::IconRole;

    pub static NEW: [u8; 2] = (IconRole::Home as u16).to_le_bytes();
    pub static SHOWS: [u8; 2] = (IconRole::Search as u16).to_le_bytes();
    pub static YOU: [u8; 2] = (IconRole::Settings as u16).to_le_bytes();
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Destination {
    New,
    Shows,
    You,
}

impl Destination {
    const STRIP: [Destination; 3] = [Destination::New, Destination::Shows, Destination::You];

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
            Destination::New => &icon::NEW,
            Destination::Shows => &icon::SHOWS,
            Destination::You => &icon::YOU,
        }
    }
}

/// A round control whose face is a drawing: the fill, the mark, and the press over it.
///
/// The button carries no text and sits over the drawing, so the press lands on it
/// whatever was drawn underneath.
fn round_button(size: f32, fill: Paint, face: Element, on_click: EventHandler<()>) -> Element {
    rsx! {
        dioxus_compose::Box {
            width: size,
            height: size,
            background: fill,
            shape_role: ShapeRole::Full,
            alignment: Alignment::Center,
            {face}
            Button {
                text: "",
                variant: ButtonVariant::Text,
                fill_max_width: true,
                fill_max_height: true,
                on_click: move |_| on_click.call(()),
            }
        }
    }
}

/// One registered meaning, tinted.
fn icon_face(picture: &'static [u8], tint: Paint) -> Element {
    rsx! {
        Icon {
            asset_id: asset(AssetKind::VectorIcon, picture),
            color: tint,
        }
    }
}

/// One drawn mark, at the size the row of actions uses.
fn mark_face(mark: DrawList) -> Element {
    rsx! {
        Canvas { width: MARK, height: MARK, commands: mark }
    }
}

/// A show's cover at some size.
///
/// The picture is registered here rather than up front. `asset` returns the same id for
/// the same bytes and queues nothing the second time, so a cover that appears in the strip,
/// on the episode card and again in the player is one registration and three nodes.
fn cover(size: f32, show: &Show) -> Element {
    rsx! {
        Image {
            width: size,
            height: size,
            shape_role: ShapeRole::Large,
            asset_id: asset(AssetKind::Svg, show.cover),
        }
    }
}

/// One of the three things that can be done to an episode: the mark in its outlined
/// circle, and how many people have done it.
fn action(mark: DrawList, count: u32, on_press: EventHandler<()>) -> Element {
    rsx! {
        Row {
            space_role: SpaceRole::Xs,
            alignment: Alignment::Center,
            dioxus_compose::Box {
                width: ACTION,
                height: ACTION,
                shape_role: ShapeRole::Full,
                border_width: 1.0,
                border_color: palette::OUTLINE,
                alignment: Alignment::Center,
                {mark_face(mark)}
                Button {
                    text: "",
                    variant: ButtonVariant::Text,
                    fill_max_width: true,
                    fill_max_height: true,
                    on_click: move |_| on_press.call(()),
                }
            }
            Text {
                text: short_count(count),
                type_role: TypeRole::Label,
                color: palette::MUTED,
            }
        }
    }
}

/// One episode as a card: the title over the artwork, a waveform either side of it, and
/// what can be done to it.
fn episode_card(found: &Episode, on_play: EventHandler<u32>) -> Element {
    let show = show_of(found);
    let id = found.id;
    rsx! {
        Column {
            fill_max_width: true,
            background: palette::PAGE,
            shape_role: ShapeRole::Large,
            border_width: 1.0,
            border_color: palette::OUTLINE,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Sm,
            Text {
                text: "S{found.season}E{found.number}: {found.title}",
                type_role: TypeRole::Subtitle,
                color: palette::INK,
                text_align: TextAlign::Center,
                fill_max_width: true,
            }
            Row {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::Center,
                // The part that has been played is in the accent and the rest is grey, so
                // the two halves either side of the cover are one waveform with the
                // playhead behind the artwork.
                Canvas {
                    weight: 1.0,
                    height: WAVE_SMALL.1,
                    commands: waveform(
                        WAVE_SMALL.0 / 2.0,
                        WAVE_SMALL.1,
                        WAVE_COLUMNS / 2,
                        found.seed,
                        CARD_PLAYED,
                        palette::ACCENT,
                        palette::WAVE,
                    ),
                }
                {cover(COVER_SMALL, show)}
                Canvas {
                    weight: 1.0,
                    height: WAVE_SMALL.1,
                    commands: waveform(
                        WAVE_SMALL.0 / 2.0,
                        WAVE_SMALL.1,
                        WAVE_COLUMNS / 2,
                        found.seed.wrapping_add(1),
                        0.0,
                        palette::ACCENT,
                        palette::WAVE,
                    ),
                }
            }
            Text {
                text: found.blurb,
                type_role: TypeRole::Body,
                color: palette::MUTED,
                max_lines: 3,
                overflow: TextOverflow::Ellipsis,
            }
            Row {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::Center,
                {action(library::heart(MARK, palette::INK), found.hearts, EventHandler::new(move |()| {
                    Message::new("Liking is not part of this sample").show();
                }))}
                {action(library::comment(MARK, palette::INK), found.comments, EventHandler::new(move |()| {
                    Message::new("Replies are not part of this sample").show();
                }))}
                {action(library::share(MARK, palette::INK), found.shares, EventHandler::new(move |()| {
                    Message::new("Sharing is not part of this sample").show();
                }))}
                Spacer { weight: 1.0 }
                // The one filled control on the card, and the only orange circle on the
                // page that is not a destination.
                Button {
                    text: "\u{25b6}",
                    height: ROUND,
                    variant: ButtonVariant::Text,
                    color: palette::PAGE,
                    background: palette::ACCENT,
                    shape_role: ShapeRole::Full,
                    on_click: move |_| on_play.call(id),
                }
            }
        }
    }
}

/// A show as a row, with a follow button.
///
/// The first row is the one being recommended, so it is a solid orange pill with white on
/// it and an outlined white button, and the rest are the same shape on the page with a
/// hairline round them and the orange on the button instead. That is the reference: one
/// row picked out, the others quiet.
fn show_row(
    show: &Show,
    followed: bool,
    recommended: bool,
    on_toggle: EventHandler<()>,
) -> Element {
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
    rsx! {
        Row {
            fill_max_width: true,
            background: if recommended { palette::ACCENT } else { palette::PAGE },
            border_width: if recommended { 0.0 } else { 1.0 },
            border_color: palette::OUTLINE,
            shape_role: ShapeRole::Full,
            padding_role: SpaceRole::Sm,
            space_role: SpaceRole::Sm,
            alignment: Alignment::CenterStart,
            {cover(48.0, show)}
            Column {
                weight: 1.0,
                Text {
                    text: show.name,
                    type_role: TypeRole::BodyStrong,
                    color: ink,
                    max_lines: 1,
                    overflow: TextOverflow::Ellipsis,
                }
                Text {
                    text: "{short_count(show.followers)} followers",
                    type_role: TypeRole::Caption,
                    color: quiet,
                }
            }
            // Orange either way. On the recommended row that is the row's own colour,
            // so what is left of the button is the white outline round it, and on the
            // others it is the one filled thing on a white row.
            Button {
                text: if followed { "Following" } else { "Follow" },
                variant: ButtonVariant::Text,
                shape_role: ShapeRole::Full,
                color: palette::PAGE,
                background: palette::ACCENT,
                border_width: if recommended { 1.0 } else { 0.0 },
                border_color: palette::PAGE,
                on_click: move |_| on_toggle.call(()),
            }
        }
    }
}

/// The shelf: the search button and a row of covers, then the newest episode in full,
/// then who to follow.
fn new_page(
    followed: Signal<Vec<usize>>,
    on_play: EventHandler<u32>,
    on_open: EventHandler<u32>,
) -> Element {
    let mut followed = followed;
    let following = followed();
    rsx! {
        Column {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,

            Text {
                text: "New Episodes",
                type_role: TypeRole::Headline,
                color: palette::INK,
            }

            Row {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::Center,
                // The shelf begins with the one round thing on it, which is how the
                // reference separates "look for something" from "here is what is new".
                {round_button(
                    ROUND,
                    palette::ACCENT,
                    icon_face(&icon::SHOWS, palette::PAGE),
                    EventHandler::new(move |()| {
                        Message::new("Search is not part of this sample").show();
                    }),
                )}
                // The shelf across, windowed. A cover that is not on screen is a picture
                // nobody has asked the Renderer to draw.
                LazyRow {
                    weight: 1.0,
                    height: COVER_SMALL + 16.0,
                    item_count: EPISODES.len(),
                    key_of: move |position: usize| EPISODES[position].id.to_string(),
                    item: move |position: usize| {
                        let found = EPISODES[position];
                        rsx! {
                            dioxus_compose::Box {
                                padding_role: SpaceRole::Xs,
                                // The inner box is measured, not wrapped. A child that
                                // fills its parent inside a box with no size of its own
                                // takes the whole width the shelf was offered, and the
                                // strip becomes one cover per screen.
                                dioxus_compose::Box {
                                    width: COVER_SMALL,
                                    height: COVER_SMALL,
                                    alignment: Alignment::Center,
                                    {cover(COVER_SMALL, &SHOWS[found.show])}
                                    Button {
                                        text: "",
                                        variant: ButtonVariant::Text,
                                        fill_max_width: true,
                                        fill_max_height: true,
                                        on_click: move |_| on_open.call(found.id),
                                    }
                                }
                            }
                        }
                    },
                }
            }

            {episode_card(&EPISODES[0], on_play)}

            Text {
                text: "Podcasts You May Like",
                type_role: TypeRole::Subtitle,
                color: palette::INK,
            }
            Column {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                for (index, show) in SHOWS.iter().enumerate() {
                    {
                        let is_followed = following.contains(&index);
                        rsx! {
                            dioxus_compose::Box { key: "{show.name}", fill_max_width: true,
                                {show_row(show, is_followed, index == 0, EventHandler::new(move |()| {
                                    let mut list = followed.write();
                                    match list.iter().position(|found| *found == index) {
                                        Some(at) => {
                                            list.remove(at);
                                        }
                                        None => list.push(index),
                                    }
                                }))}
                            }
                        }
                    }
                }
            }
        }
    }
}

/// Every episode, shortest first, as a plain list.
fn shows_page(on_play: EventHandler<u32>) -> Element {
    let mut ordered: Vec<&'static Episode> = EPISODES.iter().collect();
    ordered.sort_by_key(|found| found.seconds);
    rsx! {
        Column {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,
            Text { text: "Shows", type_role: TypeRole::Headline, color: palette::INK }
            Column {
                fill_max_width: true,
                background: palette::PAGE,
                border_width: 1.0,
                border_color: palette::OUTLINE,
                shape_role: ShapeRole::Large,
                for (index, found) in ordered.iter().enumerate() {
                    {
                        let found: &'static Episode = found;
                        let last = index + 1 == EPISODES.len();
                        rsx! {
                            Column { key: "{found.id}", fill_max_width: true,
                                Row {
                                    fill_max_width: true,
                                    padding_role: SpaceRole::Md,
                                    space_role: SpaceRole::Sm,
                                    alignment: Alignment::CenterStart,
                                    {cover(44.0, &SHOWS[found.show])}
                                    Column {
                                        weight: 1.0,
                                        Text {
                                            text: found.title,
                                            type_role: TypeRole::Body,
                                            color: palette::INK,
                                            max_lines: 1,
                                            overflow: TextOverflow::Ellipsis,
                                        }
                                        Text {
                                            text: "{SHOWS[found.show].name} \u{00b7} {clock(found.seconds)}",
                                            type_role: TypeRole::Caption,
                                            color: palette::MUTED,
                                        }
                                    }
                                    Button {
                                        text: "\u{25b6}",
                                        height: ACTION + 8.0,
                                        variant: ButtonVariant::Text,
                                        color: palette::PAGE,
                                        background: palette::ACCENT,
                                        shape_role: ShapeRole::Full,
                                        on_click: move |_| on_play.call(found.id),
                                    }
                                }
                                if !last {
                                    Separator { color: palette::OUTLINE }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/// What you follow and what you have listened to.
fn you_page(followed: Vec<usize>) -> Element {
    let listened: u32 = EPISODES.iter().map(|found| found.seconds).sum();
    rsx! {
        Column {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,
            Text { text: "You", type_role: TypeRole::Headline, color: palette::INK }
            Row {
                fill_max_width: true,
                space_role: SpaceRole::Md,
                Column {
                    weight: 1.0,
                    background: palette::ACCENT,
                    shape_role: ShapeRole::Large,
                    padding_role: SpaceRole::Md,
                    Text {
                        text: "{followed.len()}",
                        type_role: TypeRole::Display,
                        color: palette::PAGE,
                    }
                    Text {
                        text: "shows followed",
                        type_role: TypeRole::Label,
                        color: palette::PAGE,
                    }
                }
                Column {
                    weight: 1.0,
                    background: palette::TILE,
                    shape_role: ShapeRole::Large,
                    padding_role: SpaceRole::Md,
                    Text {
                        text: clock(listened),
                        type_role: TypeRole::Display,
                        color: palette::INK,
                    }
                    Text {
                        text: "in the queue",
                        type_role: TypeRole::Label,
                        color: palette::MUTED,
                    }
                }
            }
        }
    }
}

/// The player: the cover full bleed at the top of a white page, with the transport under
/// it.
fn player_page(found: &Episode, position: Signal<u32>, on_back: EventHandler<()>) -> Element {
    let mut position = position;
    let show = show_of(found);
    let played = position() as f32 / found.seconds.max(1) as f32;
    let id_seed = found.seed;
    let length = found.seconds;

    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            background: palette::PAGE,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,

            Row {
                fill_max_width: true,
                alignment: Alignment::CenterStart,
                Button {
                    text: "\u{2190}",
                    variant: ButtonVariant::Text,
                    color: palette::INK,
                    on_click: move |_| on_back.call(()),
                }
                Spacer { weight: 1.0 }
                Text {
                    text: show.name,
                    type_role: TypeRole::BodyStrong,
                    color: palette::INK,
                }
                Spacer { weight: 1.0 }
                Button {
                    text: "Share",
                    variant: ButtonVariant::Text,
                    color: palette::ACCENT,
                    on_click: move |_| {
                        Message::new("Sharing is not part of this sample").show();
                    },
                }
            }

            // The artwork, on the accent, which is the reference's player: a cover that
            // runs to the edges of the page rather than a dark screen behind it.
            dioxus_compose::Box {
                fill_max_width: true,
                background: palette::ACCENT,
                shape_role: ShapeRole::Large,
                padding_role: SpaceRole::Md,
                alignment: Alignment::Center,
                {cover(COVER_LARGE, show)}
            }

            Column {
                fill_max_width: true,
                space_role: SpaceRole::Xs,
                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    alignment: Alignment::CenterStart,
                    Text {
                        text: show.host,
                        type_role: TypeRole::Label,
                        color: palette::MUTED,
                        weight: 1.0,
                    }
                    Text {
                        text: "\u{2605} 4.7",
                        type_role: TypeRole::Label,
                        color: palette::ACCENT,
                    }
                }
                Text {
                    text: found.title,
                    type_role: TypeRole::Title,
                    color: palette::INK,
                }
                Text {
                    text: found.blurb,
                    type_role: TypeRole::Body,
                    color: palette::MUTED,
                    max_lines: 3,
                    overflow: TextOverflow::Ellipsis,
                }
            }

            Canvas {
                width: WAVE.0,
                height: WAVE.1,
                commands: waveform(
                    WAVE.0,
                    WAVE.1,
                    WAVE_COLUMNS,
                    id_seed,
                    played,
                    palette::ACCENT,
                    palette::WAVE,
                ),
            }
            Row {
                fill_max_width: true,
                alignment: Alignment::CenterStart,
                Text {
                    text: clock(position()),
                    type_role: TypeRole::Caption,
                    color: palette::MUTED,
                    weight: 1.0,
                }
                Text {
                    text: clock(length),
                    type_role: TypeRole::Caption,
                    color: palette::MUTED,
                }
            }

            Spacer { weight: 1.0 }

            Row {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::Center,
                arrangement: Arrangement::Center,
                Button {
                    text: "\u{21ba} 15",
                    variant: ButtonVariant::Text,
                    color: palette::INK,
                    on_click: move |_| position.set(position().saturating_sub(15)),
                }
                Button {
                    text: "\u{25b6}",
                    height: ROUND + 8.0,
                    variant: ButtonVariant::Text,
                    color: palette::PAGE,
                    background: palette::ACCENT,
                    shape_role: ShapeRole::Full,
                    on_click: move |_| {
                        Message::new("Playback is not part of this sample").show();
                    },
                }
                Button {
                    text: "15 \u{21bb}",
                    variant: ButtonVariant::Text,
                    color: palette::INK,
                    on_click: move |_| position.set((position() + 15).min(length)),
                }
            }
        }
    }
}

/// The destinations: three round buttons floating on the page.
///
/// Drawn here rather than declared as a `Navigation`, which is the widget for "the
/// destinations, in whatever shape this design system and this window call for": a
/// labelled bar with a selection pill under a hairline. The reference is three circles
/// sitting on the page, the one you are on filled with the accent, and that is a shape no
/// design system would be right to give a set of destinations.
fn bottom_bar(destination: Destination, on_go: EventHandler<Destination>) -> Element {
    rsx! {
        Row {
            fill_max_width: true,
            padding_role: SpaceRole::Sm,
            space_role: SpaceRole::Sm,
            arrangement: Arrangement::Center,
            alignment: Alignment::Center,
            for choice in Destination::STRIP {
                {
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
                    rsx! {
                        dioxus_compose::Box { key: "{choice.label()}",
                            {round_button(
                                ROUND,
                                fill,
                                icon_face(choice.icon(), tint),
                                EventHandler::new(move |()| on_go.call(choice)),
                            )}
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

    let mut destination = use_signal(|| Destination::New);
    let mut playing = use_signal(|| Option::<u32>::None);
    let position = use_signal(|| START_SECONDS);
    let followed = use_signal(|| vec![0_usize]);

    let on_play = EventHandler::new(move |id: u32| playing.set(Some(id)));

    // The player covers everything, including the bar along the bottom, because a full
    // screen player is what the reference draws and a bar under it would be a way out of
    // a screen whose only way out is its own back button.
    if let Some(found) = playing().and_then(episode) {
        return rsx! {
            dioxus_compose::Box {
                fill_max_width: true,
                fill_max_height: true,
                background: palette::PAGE,
                alignment: Alignment::TopCenter,
                Column {
                    width: measure,
                    fill_max_width: measure.is_none(),
                    fill_max_height: true,
                    {player_page(
                        found,
                        position,
                        EventHandler::new(move |()| playing.set(None)),
                    )}
                }
            }
        };
    }

    let body = match destination() {
        Destination::New => new_page(followed, on_play, on_play),
        Destination::Shows => shows_page(on_play),
        Destination::You => you_page(followed()),
    };

    rsx! {
        // The frame is named rather than built. Whether the bar runs across the bottom,
        // stands down the leading edge or opens as a drawer is the frame's answer from
        // the width it was given, and the page is laid out clear of whatever it took.
        Scaffold {
            background: palette::PAGE,
            bottom_bar: rsx! {
                dioxus_compose::Box {
                    fill_max_width: true,
                    alignment: Alignment::Center,
                    Column {
                        width: measure,
                        fill_max_width: measure.is_none(),
                        {bottom_bar(destination(), EventHandler::new(move |choice: Destination| {
                            destination.set(choice);
                        }))}
                    }
                }
            },

            dioxus_compose::Box {
                weight: 1.0,
                alignment: Alignment::TopCenter,
                ScrollColumn {
                    width: measure,
                    {body}
                }
            }
        }
    }
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
fn launch_builder() -> dioxus_compose::LaunchBuilder {
    // The name the window carries. A desktop lists windows by it, so a window that said
    // nothing was listed under whatever the renderer happened to be called, and every
    // sample here was listed as DioxusCompose until this line existed.
    dioxus_compose::LaunchBuilder::new()
        .with_theme(dioxus_compose::demo_theme_for(THEME))
        .with_window(
            dioxus_compose::schema::Window::new()
                .with_title("Podcast")
                // Without one the window wears the toolkit's picture, which on
                // Windows is the Java coffee cup, wherever the system lists
                // windows. The bytes travel as an asset and the renderer refers
                // to them by id: a path would be a fact about the machine this
                // was built on, and a name would ask the toolkit to find
                // something it may not have.
                .with_icon(dioxus_compose::asset::asset(
                    dioxus_compose::schema::AssetKind::Png,
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
dioxus_compose::android_main!({ launch_builder() }, app);
dioxus_compose::web_main!({ launch_builder() }, app);
dioxus_compose::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;

    use dioxus_compose::Host;
    use dioxus_compose::protocol::{
        HostEvent, Mutation, PropertyValue, decode_batch, encode_event,
    };
    use dioxus_compose::schema::{EventPayload, PropertyKind, WidgetKind};

    /// Named for what it defends: the reference is a light design, and a machine set
    /// the other way drew this sample dark with nothing to compare against.
    #[test]
    fn fr14_the_design_names_its_colour_scheme() {
        // Through the wire rather than off the constant: what settles the question is the
        // record the Renderer reads, and a scheme that never leaves the Host is a scheme
        // nobody is drawn in.
        dioxus_compose::window::reset_window_size();
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
            dioxus_compose::window::reset_window_size();
            let mut host = Host::new(app);
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
    }

    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        assert!(Host::new(app).rebuild().is_ok());
    }

    /// Every destination has to encode, not just the one the app opens on.
    ///
    /// Pressed by its icon, because the bar has no words on it: three circles on the page
    /// with the one you are on filled, which is the reference's bar.
    #[test]
    fn fr15_every_destination_encodes() {
        let mut screen = Screen::new();
        for choice in Destination::STRIP {
            assert!(
                screen.press_icon(choice.icon()),
                "the bar has no icon for {}",
                choice.label()
            );
        }
        dioxus_compose::window::reset_window_size();
    }

    /// Opening the player replaces the whole screen and its back button returns.
    #[test]
    fn fr15_the_player_opens_and_closes() {
        let mut screen = Screen::new();
        assert!(screen.press("\u{25b6}"), "nothing on the shelf plays");
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text == EPISODES[0].title),
            "the player did not come up"
        );
        assert!(screen.press("\u{2190}"), "the player has no way back");
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text == "New Episodes"),
            "closing the player did not bring the shelf back"
        );
        dioxus_compose::window::reset_window_size();
    }

    /// Skipping back moves the readout.
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
        let mut screen = Screen::new();
        assert!(screen.press("\u{25b6}"), "nothing on the shelf plays");
        let before = clock(START_SECONDS);
        assert!(
            screen.latest_texts().contains(&before),
            "the player does not say where it is"
        );
        assert!(screen.press("\u{21ba} 15"), "the player cannot skip back");
        assert!(
            screen.latest_texts().contains(&clock(START_SECONDS - 15)),
            "skipping back left the readout where it was"
        );
        dioxus_compose::window::reset_window_size();
    }

    /// Every show's cover reaches the Renderer as a picture, and each one crosses once.
    ///
    /// Named for what it defends: a cover that appears in the strip, on the episode card
    /// and again in the player is three nodes and has to be one registration, or a screen
    /// with five episodes on it sends five copies of the same poster.
    #[test]
    fn fr16_every_cover_crosses_once_as_a_picture() {
        let screen = Screen::new();
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

    /// Every drawing on the shelf has to reach the Renderer with commands on it. A canvas
    /// with none is a blank square, and on this screen most of the artwork is canvases.
    #[test]
    fn fr16_every_canvas_on_the_shelf_carries_a_draw_list() {
        dioxus_compose::window::reset_window_size();
        let batch = Host::new(app)
            .rebuild()
            .expect("the first frame failed")
            .to_vec();
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
        assert!(!canvases.is_empty(), "the shelf draws nothing at all");
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
        dioxus_compose::window::reset_window_size();
    }

    /// Nothing on the screen is painted by the design system.
    ///
    /// Named for what it defends: the rows under "Podcasts You May Like" used to be the
    /// three accent containers, so they came out pale blue, lilac and pink, and every
    /// accent on the page was the theme's blue. The reference has one orange and one
    /// hairline grey.
    #[test]
    fn fr22_nothing_on_the_screen_is_painted_by_a_role() {
        let screen = Screen::new();
        for mutation in screen.mutations() {
            if let Mutation::SetModifier {
                modifier: Modifier::Background(paint) | Modifier::Border { paint, .. },
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
    /// recommended is the one filled row in the list.
    #[test]
    fn fr22_the_accent_marks_the_destination_and_the_recommended_row() {
        let screen = Screen::new();
        let fills: Vec<Paint> = screen
            .mutations()
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetModifier {
                    modifier: Modifier::Background(paint),
                    ..
                } => Some(*paint),
                _ => None,
            })
            .collect();
        let accents = fills
            .iter()
            .filter(|paint| **paint == palette::ACCENT)
            .count();
        // The search button, the play button on the card, the recommended row, the button
        // on each of the three rows, and the destination the hub opens on.
        assert_eq!(
            accents, 7,
            "the accent is on the wrong number of things: {fills:?}"
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

    /// The shelf, in the design system it ships, in both schemes, at all three widths.
    #[test]
    fn fr16_the_shelf_is_recorded_in_the_system_it_ships() {
        sample_frames::record_as(
            "Podcast",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
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
                screen.fill_lists(5);
                assert!(screen.press("\u{25b6}"), "nothing on the shelf plays");
            },
        );
    }
}
