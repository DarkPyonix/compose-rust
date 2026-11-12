//! A self-care app: say how the day felt, say what is behind it, and pick something to
//! listen to.
//!
//! The screen the sample exists for is the check-in: a face on a pastel panel, a carousel
//! of feelings under it whose chosen answer is the panel's colour, and three steps counted
//! along the top.
//!
//! Unified, naming Cupertino and dark: the reference's check-in is black, and that is
//! the screen this opens on. `THEME` says both.
//!
//! The colours are this sample's own rather than the design system's. The reference names
//! them: four pastel feelings, a light cyan for whatever is chosen, and a pale pill under
//! the destination you are on. A role cannot say any of them, because whichever system is
//! active would answer with its own accent and containers, so the palette is written in
//! `palette.rs` and that is the only place it lives.
//!
//! The bar along the bottom is drawn here too. The reference puts the destination you are
//! on in a pill with its name under its icon and leaves the others as icons alone, which is
//! one picture's bar rather than any design system's, so it is not a `Navigation`.

mod mood;
mod palette;

use compose_rust::AssetKind;
use compose_rust::runtime::*;
use compose_rust::ui::*;
use mood::{Mood, OPEN_WORRY, SESSIONS, Session, WORRIES, week_line};
use std::rc::Rc;

/// A phone design in a desktop window is still a phone design.
const PAGE_MEASURE: f32 = 420.0;

/// The square the face is drawn into, the panel it stands on, and how round the panel's
/// lower corners are.
const FACE_SIDE: f32 = 240.0;
const PANEL_HEIGHT: f32 = FACE_SIDE * 1.5;
const PANEL_CORNER: f32 = 28.0;

/// The box the week's line gets on the profile.
const CHART: (f32, f32) = (340.0, 170.0);

/// How large a session card is on the row that runs off the edge.
const CARD_WIDTH: f32 = 172.0;
const CARD_HEIGHT: f32 = 156.0;

/// The pills: a feeling on the carousel, a worry in the grid and the pill that moves on.
/// The reference draws them all the same height.
const PILL_HEIGHT: f32 = 48.0;

/// The carousel. Each feeling takes one slot, its pill narrower than the slot so there is
/// a gap between two, and the chosen one is scrolled to the middle of the strip.
const PILL_WIDTH: f32 = 124.0;
const SLOT: f32 = 136.0;

/// The check-in's progress along the top: one segment per step.
const STEPS: usize = 3;
const SEGMENT_HEIGHT: f32 = 4.0;

/// The bar along the bottom, and the pill the destination you are on sits in.
const BAR_HEIGHT: f32 = 68.0;
const BAR_ITEM: (f32, f32) = (64.0, 54.0);

/// A play button, and the tile the quote mark sits on.
const PLAY: f32 = 36.0;
const QUOTE_TILE: f32 = 44.0;
const ICON: f32 = 22.0;

/// The icons this sample draws, as the bytes each registration carries: the meaning's
/// wire tag, little endian, and nothing else. The Renderer holds the artwork.
///
/// Three of the reference's icons have no meaning in the set yet: the play triangle, the
/// open book and the person (compose-rust issue #539). The nearest meanings stand in for
/// them until they exist, so the bar is the reference's shape with one system's glyphs.
mod icon {
    use compose_rust::ui::IconRole;

    pub static HOME: [u8; 2] = (IconRole::Home as u16).to_le_bytes();
    pub static LISTEN: [u8; 2] = (IconRole::Forward as u16).to_le_bytes();
    pub static LIBRARY: [u8; 2] = (IconRole::Library as u16).to_le_bytes();
    pub static PROFILE: [u8; 2] = (IconRole::Settings as u16).to_le_bytes();
    pub static SETTINGS: [u8; 2] = (IconRole::Settings as u16).to_le_bytes();
    pub static CHEVRON: [u8; 2] = (IconRole::Forward as u16).to_le_bytes();
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Destination {
    Home,
    Listen,
    Library,
    Profile,
}

impl Destination {
    const STRIP: [Destination; 4] = [
        Destination::Home,
        Destination::Listen,
        Destination::Library,
        Destination::Profile,
    ];

    /// The name written under the icon when this is the destination you are on, and
    /// nowhere else: the reference writes only the chosen one.
    fn label(self) -> &'static str {
        match self {
            Destination::Home => "Home",
            Destination::Listen => "Listen",
            Destination::Library => "Library",
            Destination::Profile => "Profile",
        }
    }

    fn icon(self) -> &'static [u8] {
        match self {
            Destination::Home => &icon::HOME,
            Destination::Listen => &icon::LISTEN,
            Destination::Library => &icon::LIBRARY,
            Destination::Profile => &icon::PROFILE,
        }
    }
}

/// The three steps of the check-in.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Step {
    Feeling,
    Worry,
    Done,
}

impl Step {
    /// How many of the segments along the top are finished on this step.
    fn reached(self) -> usize {
        match self {
            Step::Feeling => 1,
            Step::Worry => 2,
            Step::Done => 3,
        }
    }
}

/// What a press does. Shared, because one handler can reach more than one control.
type Action = Rc<dyn Fn()>;

/// The check-in's progress: three segments, the ones reached in the done colour.
///
/// Over a feeling's panel the reference draws the reached segment dark and the rest a
/// lighter shade of the panel; on the black page it draws them white and grey.
fn progress(reached: usize, on_panel: bool) {
    let (done, rest) = if on_panel {
        (palette::ON_LIGHT, palette::PANEL_TRACK)
    } else {
        (palette::INK, palette::OUTLINE)
    };
    Row()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Sm)
        .content(|| {
            for segment in 0..STEPS {
                key(segment, || {
                    Box().modifier(
                        Modifier
                            .weight(1.0)
                            .height(SEGMENT_HEIGHT)
                            .background(if segment < reached { done } else { rest })
                            .shape_role(ShapeRole::Full),
                    );
                });
            }
        });
}

/// A pill that is a control: a fill, a shape and a press, with whatever is written on it.
fn pill(modifier: Modifier, on_click: impl FnMut() + 'static, face: impl FnOnce()) {
    Box()
        .modifier(modifier.height(PILL_HEIGHT).shape_role(ShapeRole::Full))
        .alignment(Alignment::Center)
        .on_click(on_click)
        .content(face);
}

/// "Skip" in plain words on the left, and the white pill that moves on.
fn foot(on_skip: Action, on_next: Action) {
    Row()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Md)
        .alignment(Alignment::Center)
        .content(|| {
            Box()
                .modifier(Modifier.height(PILL_HEIGHT).padding_role(SpaceRole::Md))
                .alignment(Alignment::Center)
                .on_click(move || on_skip())
                .content(|| {
                    Text("Skip").type_role(TypeRole::Body).color(palette::INK);
                });
            pill(
                Modifier.weight(1.0).background(palette::INK),
                move || on_next(),
                || {
                    Row()
                        .space_role(SpaceRole::Xs)
                        .alignment(Alignment::Center)
                        .content(|| {
                            Text("Next")
                                .type_role(TypeRole::Body)
                                .color(palette::ON_LIGHT);
                            Text("\u{203a}")
                                .type_role(TypeRole::Body)
                                .color(palette::ON_LIGHT);
                        });
                },
            );
        });
}

/// The strip of feelings: a carousel with the chosen one in the middle.
///
/// The strip runs to the page's edges, and the slots either side of the chosen one are
/// cut off there, which is what the reference draws. A spacer half a page wide at each end
/// is what lets the first and the last feeling reach the middle, and with it the offset
/// that centres a feeling is its slot times its place along the strip whatever the page's
/// width. Pressing a feeling chooses it and turns the strip to it.
fn mood_strip(chosen: MutableState<Mood>, strip: ScrollState, viewport: f32) {
    let current = chosen.get();
    let lead = ((viewport - SLOT) / 2.0).max(0.0);
    ScrollRow()
        .modifier(Modifier.fill_max_width())
        .scroll_state(&strip)
        .content(|| {
            Spacer().modifier(Modifier.width(lead));
            for option in Mood::STRIP {
                let picked = option == current;
                let (fill, ink) = if picked {
                    option.pair()
                } else {
                    (palette::CHIP, palette::INK)
                };
                let (choose, turn) = (chosen, strip.clone());
                key(option.label(), || {
                    Box()
                        .modifier(Modifier.width(SLOT))
                        .alignment(Alignment::Center)
                        .content(|| {
                            pill(
                                Modifier.width(PILL_WIDTH).background(fill),
                                move || {
                                    choose.set(option);
                                    turn.animate_scroll_to(option.index() as f32 * SLOT);
                                },
                                || {
                                    Text(option.label()).type_role(TypeRole::Body).color(ink);
                                },
                            );
                        });
                });
            }
            Spacer().modifier(Modifier.width(lead));
        });
}

/// The mood picker: the face on its panel, the carousel under it, and the question.
#[Composable]
fn feeling_step(chosen: MutableState<Mood>, viewport: f32, on_skip: Action, on_next: Action) {
    let current = chosen.get();
    // The strip opens on the feeling already chosen, in the middle. Remembered so a
    // scroll the reader made by hand is not undone by the next frame.
    let opening = current.index();
    let strip = remember(move || {
        let strip = ScrollState::new();
        strip.scroll_to(opening as f32 * SLOT);
        strip
    });
    Column()
        .modifier(Modifier.fill_max_size())
        .space_role(SpaceRole::Lg)
        .content(|| {
            // The panel fills the top of the screen in a pale tint of the feeling it is
            // showing, which is the reference's whole idea: the answer is the colour. It
            // runs to the window's edges and rounds off at the bottom.
            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .height(PANEL_HEIGHT)
                        .background(current.panel())
                        .corner_radii([0.0, 0.0, PANEL_CORNER, PANEL_CORNER])
                        .padding_role(SpaceRole::Md),
                )
                .content(|| {
                    progress(Step::Feeling.reached(), true);
                    Box()
                        .modifier(Modifier.fill_max_width().weight(1.0))
                        .alignment(Alignment::Center)
                        .content(|| {
                            Image(asset(AssetKind::Svg, current.picture()))
                                .modifier(Modifier.size(FACE_SIDE, FACE_SIDE));
                        });
                });

            mood_strip(chosen, strip, viewport);

            // Everything under the strip, inset once and spaced once.
            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .weight(1.0)
                        .padding_role(SpaceRole::Md),
                )
                .space_role(SpaceRole::Lg)
                .alignment(Alignment::Center)
                .content(|| {
                    Text("How do you\nfeel today?")
                        .type_role(TypeRole::Headline)
                        .text_align(TextAlign::Center)
                        .color(palette::INK);
                    Spacer().modifier(Modifier.weight(1.0));
                    foot(Rc::clone(&on_skip), Rc::clone(&on_next));
                });
        });
}

/// The worry picker: ten pills, any number of them chosen.
#[Composable]
fn worry_step(picked: MutableState<Vec<&'static str>>, on_skip: Action, on_next: Action) {
    let chosen = picked.get();
    // Two to a row. There is no wrapping row in the vocabulary, so a grid is rows of a
    // fixed count and the code says how many rather than the layout working it out.
    let rows: Vec<&[&'static str]> = WORRIES.chunks(2).collect();
    Column()
        .modifier(Modifier.fill_max_size().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Lg)
        .content(|| {
            progress(Step::Worry.reached(), false);

            Text("What's\nworrying you?")
                .type_role(TypeRole::Headline)
                .text_align(TextAlign::Center)
                .color(palette::INK)
                .modifier(Modifier.fill_max_width());

            Column()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .content(|| {
                    for (index, row) in rows.iter().enumerate() {
                        key(index, || {
                            Row()
                                .modifier(Modifier.fill_max_width())
                                .space_role(SpaceRole::Sm)
                                .content(|| {
                                    for worry in row.iter().copied() {
                                        let on = chosen.contains(&worry);
                                        key(worry, || worry_pill(worry, on, picked));
                                    }
                                });
                        });
                    }
                });

            Spacer().modifier(Modifier.weight(1.0));
            foot(Rc::clone(&on_skip), Rc::clone(&on_next));
        });
}

/// One worry. Chosen ones are the reference's cyan with black ink, the rest dark grey with
/// white, and "Other" is only an outline until it is chosen.
fn worry_pill(worry: &'static str, on: bool, picked: MutableState<Vec<&'static str>>) {
    let shape = Modifier.weight(1.0);
    let (shape, ink) = if on {
        (shape.background(palette::CYAN), palette::ON_LIGHT)
    } else if worry == OPEN_WORRY {
        (shape.border(1.0, palette::OUTLINE), palette::INK)
    } else {
        (shape.background(palette::CHIP), palette::INK)
    };
    pill(
        shape,
        move || {
            picked.update(|list| match list.iter().position(|found| *found == worry) {
                Some(at) => {
                    list.remove(at);
                }
                None => list.push(worry),
            })
        },
        || {
            Text(worry).type_role(TypeRole::Body).color(ink);
        },
    );
}

/// What the check-in leaves behind: the day, in one line, and a way back into it.
#[Composable]
fn checked_in(chosen: Mood, worries: Vec<&'static str>, again: Action) {
    let (_, ink) = chosen.pair();
    let summary = if worries.is_empty() {
        "Nothing in particular.".to_owned()
    } else {
        worries.join(", ")
    };
    Column()
        .modifier(Modifier.fill_max_size().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            progress(Step::Done.reached(), false);
            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(chosen.panel())
                        .shape_role(ShapeRole::Large)
                        .padding_role(SpaceRole::Lg),
                )
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::Center)
                .content(|| {
                    Image(asset(AssetKind::Svg, chosen.picture()))
                        .modifier(Modifier.size(FACE_SIDE * 0.6, FACE_SIDE * 0.6));
                    Text(format!("Today felt {}.", chosen.label().to_lowercase()))
                        .type_role(TypeRole::Title)
                        .color(ink)
                        .text_align(TextAlign::Center);
                    Text(summary)
                        .type_role(TypeRole::Body)
                        .color(ink)
                        .text_align(TextAlign::Center);
                });
            pill(
                Modifier.fill_max_width().background(palette::CHIP),
                move || again(),
                || {
                    Text("Check in again")
                        .type_role(TypeRole::Body)
                        .color(palette::INK);
                },
            );
            Spacer().modifier(Modifier.weight(1.0));
        });
}

/// Says that playing a session is not part of this sample.
fn not_playable() {
    Message::new("Playback is not part of this sample").show();
}

/// The round play button: a filled circle with the triangle on it.
///
/// The triangle is a character rather than an icon because the set has no play meaning
/// yet (compose-rust issue #539).
fn play(fill: Paint, ink: Paint) {
    Box()
        .modifier(
            Modifier
                .size(PLAY, PLAY)
                .background(fill)
                .shape_role(ShapeRole::Full),
        )
        .alignment(Alignment::Center)
        .on_click(not_playable)
        .content(|| {
            Text("\u{25b6}").type_role(TypeRole::Caption).color(ink);
        });
}

/// A small pill saying something about a session: how long, or when.
fn tag(text: String, fill: Paint, ink: Paint) {
    Text(text).type_role(TypeRole::Caption).color(ink).modifier(
        Modifier
            .background(fill)
            .shape_role(ShapeRole::Full)
            .padding_role(SpaceRole::Sm),
    );
}

/// One session, as a card in the row that runs off the edge. The reference's pair is one
/// dark card and one light one, so the cards alternate.
fn session_card(session: &Session, dark: bool) {
    let (fill, ink, chip, chip_ink) = if dark {
        (palette::CARD, palette::INK, palette::CHIP, palette::INK)
    } else {
        (
            palette::INK,
            palette::ON_LIGHT,
            palette::LIGHT_CHIP,
            palette::ON_LIGHT,
        )
    };
    Column()
        .modifier(
            Modifier
                .fill_max_size()
                .background(fill)
                .shape_role(ShapeRole::Large)
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Xs)
        .content(|| {
            Text(session.title)
                .type_role(TypeRole::Title)
                .color(ink)
                .max_lines(2)
                .overflow(TextOverflow::Ellipsis);
            Spacer().modifier(Modifier.weight(1.0));
            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::Center)
                .content(|| {
                    tag(format!("{} min", session.minutes), chip, chip_ink);
                    Spacer().modifier(Modifier.weight(1.0));
                    play(palette::CYAN, palette::ON_LIGHT);
                });
        });
}

/// One of "Special for you": a wide card with the title, two tags and a play button. The
/// reference's first is the cyan card and the rest are light.
fn special_card(session: &Session, first: bool) {
    let (fill, chip, button, button_ink) = if first {
        (palette::CYAN, palette::INK, palette::INK, palette::ON_LIGHT)
    } else {
        (
            palette::INK,
            palette::LIGHT_CHIP,
            palette::CYAN,
            palette::ON_LIGHT,
        )
    };
    Row()
        .modifier(
            Modifier
                .fill_max_width()
                .background(fill)
                .shape_role(ShapeRole::Large)
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Sm)
        .alignment(Alignment::Center)
        .content(|| {
            Column()
                .modifier(Modifier.weight(1.0))
                .space_role(SpaceRole::Sm)
                .content(|| {
                    Text(session.title)
                        .type_role(TypeRole::Subtitle)
                        .color(palette::ON_LIGHT)
                        .max_lines(1)
                        .overflow(TextOverflow::Ellipsis);
                    Row().space_role(SpaceRole::Xs).content(|| {
                        tag(format!("{} min", session.minutes), chip, palette::ON_LIGHT);
                        tag(session.when.to_owned(), chip, palette::ON_LIGHT);
                    });
                });
            play(button, button_ink);
        });
}

/// The listening screen: a greeting, a search field, the pair of cards and the sessions
/// picked for you.
#[Composable]
fn listen_page() {
    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("Hello, Paul \u{1f44b}")
                .type_role(TypeRole::Headline)
                .color(palette::INK);
            TextField()
                .modifier(Modifier.fill_max_width())
                .placeholder("Search...");

            LazyRow()
                .modifier(Modifier.fill_max_width().height(CARD_HEIGHT))
                .content(|list| {
                    list.items_keyed(
                        SESSIONS.len(),
                        |position| SESSIONS[position].title.to_owned(),
                        |position| {
                            Box()
                                .modifier(
                                    Modifier
                                        .width(CARD_WIDTH)
                                        .fill_max_height()
                                        .padding_role(SpaceRole::Xs),
                                )
                                .content(|| session_card(&SESSIONS[position], position % 2 == 0));
                        },
                    );
                });

            Row()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::Center)
                .content(|| {
                    Text("Special for you")
                        .type_role(TypeRole::Title)
                        .color(palette::INK)
                        .modifier(Modifier.weight(1.0));
                    Text("See all \u{203a}")
                        .type_role(TypeRole::Caption)
                        .color(palette::MUTED);
                });
            for (index, session) in SESSIONS.iter().skip(2).enumerate() {
                key(session.title, || special_card(session, index == 0));
            }
        });
}

/// Everything there is, as a plain list.
#[Composable]
fn library_page() {
    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("Library")
                .type_role(TypeRole::Headline)
                .color(palette::INK);
            Column()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .content(|| {
                    for session in SESSIONS.iter() {
                        key(session.title, || {
                            Row()
                                .modifier(Modifier.fill_max_width())
                                .space_role(SpaceRole::Sm)
                                .alignment(Alignment::Center)
                                .content(|| {
                                    Box().modifier(
                                        Modifier
                                            .size(48.0, 48.0)
                                            .background(session.mood.pair().0)
                                            .shape_role(ShapeRole::Medium),
                                    );
                                    Column().modifier(Modifier.weight(1.0)).content(|| {
                                        Text(session.title)
                                            .type_role(TypeRole::Body)
                                            .color(palette::INK)
                                            .max_lines(1)
                                            .overflow(TextOverflow::Ellipsis);
                                        Text(format!(
                                            "{} min \u{00b7} {}",
                                            session.minutes, session.when
                                        ))
                                        .type_role(TypeRole::Caption)
                                        .color(palette::MUTED);
                                    });
                                });
                        });
                    }
                });
        });
}

/// The profile: who this is, how the week went, a quote, and the settings under it.
#[Composable]
fn profile_page(chosen: Mood) {
    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            Column()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Xs)
                .alignment(Alignment::Center)
                .content(|| {
                    Image(asset(AssetKind::Svg, chosen.picture()))
                        .modifier(Modifier.size(96.0, 96.0));
                    Text("Paul Wilson")
                        .type_role(TypeRole::Title)
                        .color(palette::INK);
                });

            // The week sits on the page itself, as in the reference, rather than on a card.
            Box()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::Center)
                .content(|| {
                    Canvas(week_line(CHART.0, CHART.1)).modifier(Modifier.size(CHART.0, CHART.1));
                });

            // The quote card: a dark card, the quote mark on a cyan tile, and the quote in
            // white beside it.
            Row()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(palette::CARD)
                        .shape_role(ShapeRole::Large)
                        .padding_role(SpaceRole::Md),
                )
                .space_role(SpaceRole::Md)
                .alignment(Alignment::Center)
                .content(|| {
                    Box()
                        .modifier(
                            Modifier
                                .size(QUOTE_TILE, QUOTE_TILE)
                                .background(palette::CYAN)
                                .shape_role(ShapeRole::Medium),
                        )
                        .alignment(Alignment::Center)
                        .content(|| {
                            Text("\u{201d}")
                                .type_role(TypeRole::Title)
                                .color(palette::ON_LIGHT);
                        });
                    Text("Every day is a new opportunity for growth and positive change.")
                        .type_role(TypeRole::Body)
                        .color(palette::INK)
                        .modifier(Modifier.weight(1.0));
                });

            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(palette::CARD)
                        .shape_role(ShapeRole::Large),
                )
                .content(|| {
                    // Settings and FAQ wear the reference's gear. Language has a glyph of
                    // its own there that the set has no meaning for yet (compose-rust issue
                    // #539), so its row keeps the slot and leaves it empty.
                    let entries: [(&'static str, Option<&'static [u8]>); 3] = [
                        ("Settings", Some(&icon::SETTINGS[..])),
                        ("Language", None),
                        ("FAQ", Some(&icon::SETTINGS[..])),
                    ];
                    for (index, (entry, glyph)) in entries.into_iter().enumerate() {
                        key(entry, || {
                            settings_row(entry, glyph, index + 1 < entries.len())
                        });
                    }
                });
        });
}

/// One entry under the profile, with the hairline under every entry but the last.
#[Composable]
fn settings_row(entry: &'static str, glyph: Option<&'static [u8]>, separated: bool) {
    Column().modifier(Modifier.fill_max_width()).content(|| {
        Row()
            .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
            .space_role(SpaceRole::Sm)
            .alignment(Alignment::Center)
            .content(|| {
                Box()
                    .modifier(Modifier.size(ICON, ICON))
                    .alignment(Alignment::Center)
                    .content(|| {
                        if let Some(glyph) = glyph {
                            Icon(asset(AssetKind::VectorIcon, glyph)).color(palette::INK);
                        }
                    });
                Text(entry)
                    .type_role(TypeRole::Body)
                    .color(palette::INK)
                    .modifier(Modifier.weight(1.0));
                Icon(asset(AssetKind::VectorIcon, &icon::CHEVRON)).color(palette::MUTED);
            });
        if separated {
            Separator(Some(palette::OUTLINE));
        }
    });
}

/// The bar along the bottom, as the reference draws it.
///
/// The destination you are on is its icon with its name under it, inside a pale pill.
/// The others are their icons alone. Drawn here rather than declared as a `Navigation`,
/// which is the widget for "the destinations, in whatever shape this design system and
/// this window call for"; this is the shape one picture calls for.
#[Composable]
fn bottom_bar(current: Destination, go: MutableState<Destination>) {
    Row()
        .modifier(
            Modifier
                .fill_max_width()
                .height(BAR_HEIGHT)
                .background(palette::PAGE),
        )
        .arrangement(Arrangement::SpaceAround)
        .alignment(Alignment::Center)
        .content(|| {
            for choice in Destination::STRIP {
                key(choice.label(), || bar_item(choice, choice == current, go));
            }
        });
}

/// One destination along the bottom.
#[Composable]
fn bar_item(choice: Destination, selected: bool, go: MutableState<Destination>) {
    let place = Modifier
        .size(BAR_ITEM.0, BAR_ITEM.1)
        .shape_role(ShapeRole::Large);
    let place = if selected {
        place.background(palette::PILL)
    } else {
        place
    };
    Box()
        .modifier(place)
        .alignment(Alignment::Center)
        .on_click(move || go.set(choice))
        .content(|| {
            if selected {
                Column()
                    .space_role(SpaceRole::Xs)
                    .alignment(Alignment::Center)
                    .content(|| {
                        Icon(asset(AssetKind::VectorIcon, choice.icon())).color(palette::ON_LIGHT);
                        Text(choice.label())
                            .type_role(TypeRole::Caption)
                            .color(palette::ON_LIGHT);
                    });
            } else {
                Icon(asset(AssetKind::VectorIcon, choice.icon())).color(palette::INK);
            }
        });
}

/// The page for one destination, and for the check-in the step it is on.
#[Composable]
fn page(
    destination: Destination,
    step: MutableState<Step>,
    chosen: MutableState<Mood>,
    picked: MutableState<Vec<&'static str>>,
    viewport: f32,
) {
    let to = |next: Step| -> Action { Rc::new(move || step.set(next)) };
    match destination {
        Destination::Home => match step.get() {
            Step::Feeling => feeling_step(chosen, viewport, to(Step::Done), to(Step::Worry)),
            Step::Worry => worry_step(picked, to(Step::Done), to(Step::Done)),
            Step::Done => checked_in(chosen.get(), picked.get(), to(Step::Feeling)),
        },
        Destination::Listen => listen_page(),
        Destination::Library => library_page(),
        Destination::Profile => profile_page(chosen.get()),
    }
}

#[Composable]
pub fn app() {
    let window = current_window_size();
    let measure = if window.is_compact() {
        None
    } else {
        Some(PAGE_MEASURE)
    };
    // How wide the page is, which is what the carousel centres its choice in. A phone's
    // page is the window; anything wider holds the page to a phone's measure.
    let viewport = match measure {
        Some(measure) => measure,
        None if window.width_dp > 0.0 => window.width_dp,
        None => PAGE_MEASURE,
    };

    let destination = remember(|| mutable_state_of(Destination::Home));
    let step = remember(|| mutable_state_of(Step::Feeling));
    let chosen = remember(|| mutable_state_of(Mood::Calm));
    let picked = remember(|| mutable_state_of(Vec::<&'static str>::new()));

    let current = destination.get();
    // The check-in runs to the window's edges, because the tint behind the face is the
    // screen rather than a card on it. The rest are pages that scroll.
    let scrolls = current != Destination::Home || step.get() != Step::Feeling;
    let frame = Modifier
        .width_if(measure)
        .fill_max_width_if(measure.is_none())
        .fill_max_height();

    Scaffold()
        .modifier(Modifier.background(palette::PAGE))
        .bottom_bar(|| {
            Box()
                .modifier(Modifier.fill_max_width().background(palette::PAGE))
                .alignment(Alignment::Center)
                .content(|| {
                    Column()
                        .modifier(
                            Modifier
                                .width_if(measure)
                                .fill_max_width_if(measure.is_none()),
                        )
                        .content(|| bottom_bar(current, destination));
                });
        })
        .content(|| {
            Column()
                .modifier(Modifier.fill_max_size().background(palette::PAGE))
                .content(|| {
                    Box()
                        .modifier(Modifier.fill_max_size())
                        .alignment(Alignment::TopCenter)
                        .content(|| {
                            let body = || page(current, step, chosen, picked, viewport);
                            if scrolls {
                                ScrollColumn().modifier(frame).content(body);
                            } else {
                                Column().modifier(frame).content(body);
                            }
                        });
                });
        });
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
        .with_theme(compose_rust::demo_theme_for(THEME))
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Self-care")
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
    use compose_rust::schema::Modifier as Wire;
    use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind};

    /// Named for what it defends: the reference is a dark design, and a machine set the
    /// other way drew this sample light with nothing to compare against.
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
        assert_eq!(theme.color_scheme, ColorScheme::Dark);
        assert!(!theme.adaptive, "the design is the product here");
    }

    /// The screen, driven the way a Renderer drives it, at a phone's width. Every batch is
    /// kept, because a batch is the change since the frame before it rather than what is
    /// on screen.
    struct Screen {
        host: ComposeHost,
        frames: Vec<Vec<u8>>,
    }

    impl Screen {
        fn new() -> Self {
            compose_rust::window::reset_window_size();
            let mut host = ComposeHost::new(app);
            let first = host.rebuild().expect("the first frame failed").to_vec();
            let mut screen = Self {
                host,
                frames: vec![first],
            };
            screen.resize(PHONE);
            screen
        }

        fn resize(&mut self, width_dp: f32) {
            self.send(HostEvent {
                node_id: 0,
                handler_id: 0,
                payload: EventPayload::WindowSizeChanged {
                    width_dp,
                    height_dp: 860.0,
                    class: WindowSizeClass::from_width_dp(width_dp),
                    height_class: WindowHeightClass::from_height_dp(860.0),
                    safe_area_top: 0.0,
                    safe_area_leading: 0.0,
                    safe_area_trailing: 0.0,
                },
            });
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

        /// What is on screen now, without the nodes later batches removed.
        fn live(&self) -> Vec<Mutation<'_>> {
            sample_frames::live(self.mutations())
        }

        fn parent_of(&self, node: u32) -> Option<u32> {
            parent_in(&self.live(), node)
        }

        fn children_of(&self, node: u32) -> Vec<u32> {
            children_in(&self.live(), node)
        }

        /// Every live node under this one, itself included.
        fn family_of(&self, node: u32) -> Vec<u32> {
            let live = self.live();
            let mut family = vec![node];
            let mut next = 0;
            while next < family.len() {
                let children = children_in(&live, family[next]);
                family.extend(children);
                next += 1;
            }
            family
        }

        fn handler_of(&self, node: u32) -> Option<u64> {
            handler_in(&self.live(), node)
        }

        fn text_of(&self, node: u32) -> Option<String> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                    } if *node_id == node => Some((*text).to_owned()),
                    _ => None,
                })
        }

        /// What a node is filled with now. Read slot by slot, because a fill that was taken
        /// away is a later record emptying its slot rather than the old record going away.
        fn background_of(&self, node: u32) -> Option<Paint> {
            modifiers_in(&self.live(), node)
                .into_iter()
                .find_map(|modifier| match modifier {
                    Wire::Background(paint) => Some(paint),
                    _ => None,
                })
        }

        fn color_of(&self, node: u32) -> Option<Paint> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Color,
                        value: PropertyValue::Integer(bits),
                    } if *node_id == node => Paint::from_bits(*bits as u64),
                    _ => None,
                })
        }

        fn nodes_of_height(&self, dp: f32) -> Vec<u32> {
            nodes_of_height_in(&self.live(), dp)
        }

        fn nodes_of(&self, widget: WidgetKind) -> Vec<u32> {
            self.live()
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

        /// Presses a node: itself if it answers a click, or the nearest one above it that
        /// does. The pills and the bar are containers that take the press, with the words
        /// on them as children.
        fn press_node(&mut self, node: u32) -> bool {
            let mut at = Some(node);
            while let Some(next) = at {
                if let Some(handler_id) = self.handler_of(next) {
                    self.send(HostEvent {
                        node_id: next,
                        handler_id,
                        payload: EventPayload::Clicked,
                    });
                    return true;
                }
                at = self.parent_of(next);
            }
            false
        }

        /// Presses whatever says `label`.
        fn press(&mut self, label: &str) -> bool {
            let node = self
                .live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                    } if *text == label => Some(*node_id),
                    _ => None,
                });
            match node {
                Some(node) => self.press_node(node),
                None => false,
            }
        }

        fn bar(&self) -> u32 {
            bar_in(&self.live())
        }

        /// Presses a destination along the bottom, which for all but the one you are on is
        /// an icon with nothing written under it.
        fn go(&mut self, destination: Destination) -> bool {
            let item = bar_item_in(&self.live(), destination);
            self.press_node(item)
        }

        /// What the bar has written on it.
        fn bar_texts(&self) -> Vec<String> {
            self.family_of(self.bar())
                .into_iter()
                .filter_map(|node| self.text_of(node))
                .collect()
        }

        fn texts(&self) -> Vec<String> {
            self.live()
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

        /// Where the carousel was last asked to scroll to.
        fn strip_target(&self) -> Option<f32> {
            let strip = *self
                .nodes_of(WidgetKind::ScrollRow)
                .first()
                .expect("the check-in has no carousel");
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Value,
                        value: PropertyValue::Float(dp),
                    } if *node_id == strip => Some(*dp),
                    _ => None,
                })
        }
    }

    // Reading a screen from its live records. Free functions rather than methods, so the
    // recordings below, which drive their own screen, find the bar the same way.

    /// What a node was last inserted into.
    fn parent_in(live: &[Mutation<'_>], node: u32) -> Option<u32> {
        live.iter().rev().find_map(|mutation| match mutation {
            Mutation::Insert {
                parent_id, node_id, ..
            }
            | Mutation::Move {
                parent_id, node_id, ..
            } if *node_id == node => Some(*parent_id),
            _ => None,
        })
    }

    /// The children of a node, in the order they were placed.
    fn children_in(live: &[Mutation<'_>], node: u32) -> Vec<u32> {
        let mut children: Vec<(u32, u32)> = Vec::new();
        for mutation in live {
            if let Mutation::Insert {
                parent_id,
                node_id,
                index,
            }
            | Mutation::Move {
                parent_id,
                node_id,
                index,
            } = mutation
            {
                children.retain(|(child, _)| child != node_id);
                if *parent_id == node {
                    children.push((*node_id, *index));
                }
            }
        }
        children.sort_by_key(|(_, index)| *index);
        children.into_iter().map(|(child, _)| child).collect()
    }

    fn handler_in(live: &[Mutation<'_>], node: u32) -> Option<u64> {
        live.iter().rev().find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnClick,
                value: PropertyValue::Integer(handler),
            } if *node_id == node => Some(*handler as u64),
            _ => None,
        })
    }

    /// The modifiers a node carries now: the last record in each slot, without the slots
    /// a later record emptied.
    fn modifiers_in(live: &[Mutation<'_>], node: u32) -> Vec<Wire> {
        let mut slots: Vec<(u16, Wire)> = Vec::new();
        for mutation in live {
            if let Mutation::SetModifier {
                node_id,
                index,
                modifier,
            } = mutation
            {
                if *node_id == node {
                    slots.retain(|(slot, _)| slot != index);
                    slots.push((*index, modifier.clone()));
                }
            }
        }
        slots
            .into_iter()
            .map(|(_, modifier)| modifier)
            .filter(|modifier| !matches!(modifier, Wire::Empty))
            .collect()
    }

    /// The nodes that are this tall now.
    fn nodes_of_height_in(live: &[Mutation<'_>], dp: f32) -> Vec<u32> {
        let mut nodes: Vec<u32> = live
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetModifier { node_id, .. } => Some(*node_id),
                _ => None,
            })
            .collect();
        nodes.sort_unstable();
        nodes.dedup();
        nodes.retain(|node| {
            modifiers_in(live, *node)
                .iter()
                .any(|modifier| matches!(modifier, Wire::Height(height) if *height == dp))
        });
        nodes
    }

    /// The bar along the bottom: the one row as tall as the bar.
    fn bar_in(live: &[Mutation<'_>]) -> u32 {
        let bars = nodes_of_height_in(live, BAR_HEIGHT);
        assert_eq!(
            bars.len(),
            1,
            "there should be exactly one bar along the bottom"
        );
        bars[0]
    }

    /// The place along the bar a destination takes.
    fn bar_item_in(live: &[Mutation<'_>], destination: Destination) -> u32 {
        let items = children_in(live, bar_in(live));
        assert_eq!(
            items.len(),
            Destination::STRIP.len(),
            "the bar is not four destinations"
        );
        let at = Destination::STRIP
            .iter()
            .position(|found| *found == destination)
            .expect("a destination off the strip");
        items[at]
    }

    /// Goes to a destination on a recording's screen, by its place along the bar.
    fn go_in(screen: &mut sample_frames::Screen, destination: Destination) -> bool {
        let found = {
            let live = screen.live_mutations();
            let item = bar_item_in(&live, destination);
            handler_in(&live, item).map(|handler_id| (item, handler_id))
        };
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

    /// The phone width the screen is driven at.
    const PHONE: f32 = 420.0;

    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        assert!(ComposeHost::new(app).rebuild().is_ok());
    }

    /// The face on the check-in has to reach the Renderer as a picture. An `Image` whose
    /// id names nothing is a blank square, which on this screen is most of the screen.
    #[test]
    fn fr16_the_check_in_draws_a_face() {
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
                .texts()
                .iter()
                .any(|text| text == "What's\nworrying you?"),
            "the second step did not arrive"
        );
        assert!(screen.press(WORRIES[2]), "no worry can be chosen");
        assert!(screen.press("Next"), "the worry step has no way on");
        assert!(
            screen.texts().iter().any(|text| text.contains(WORRIES[2])),
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
                screen.go(choice),
                "the bar has no way to {}",
                choice.label()
            );
            assert_eq!(
                screen.bar_texts(),
                vec![choice.label().to_owned()],
                "pressing {} did not make it the destination you are on",
                choice.label()
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// The bar is the reference's: the destination you are on is its icon with its name
    /// under it in the pale pill, and the others are icons alone, in the sample's colours.
    ///
    /// Named for what it defends: the bar was a `Navigation`, which every design system
    /// draws as its own labelled bar, rail or sidebar, and on a Mac at a phone's width it
    /// came out as a sidebar toggle drawn over the face.
    #[test]
    fn fr22_the_bar_names_only_the_destination_you_are_on() {
        let mut screen = Screen::new();
        assert!(
            screen.nodes_of(WidgetKind::Navigation).is_empty()
                && screen.nodes_of(WidgetKind::NavigationItem).is_empty(),
            "the bar is the design system's, not the reference's"
        );

        for choice in [Destination::Home, Destination::Profile] {
            if choice != Destination::Home {
                assert!(
                    screen.go(choice),
                    "the bar has no way to {}",
                    choice.label()
                );
            }
            assert_eq!(
                screen.bar_texts(),
                vec![choice.label().to_owned()],
                "the bar should name {} and nothing else",
                choice.label()
            );
            let items = screen.children_of(screen.bar());
            for (item, destination) in items.iter().zip(Destination::STRIP) {
                let pill = screen.background_of(*item);
                let icons: Vec<u32> = screen
                    .family_of(*item)
                    .into_iter()
                    .filter(|node| screen.nodes_of(WidgetKind::Icon).contains(node))
                    .collect();
                assert_eq!(icons.len(), 1, "{} is not one icon", destination.label());
                let tint = screen.color_of(icons[0]);
                if destination == choice {
                    assert_eq!(
                        pill,
                        Some(palette::PILL),
                        "the destination you are on has no pill"
                    );
                    assert_eq!(
                        tint,
                        Some(palette::ON_LIGHT),
                        "the chosen icon is not the pill's ink"
                    );
                } else {
                    assert_eq!(
                        pill,
                        None,
                        "{} has a pill it is not on",
                        destination.label()
                    );
                    assert_eq!(
                        tint,
                        Some(palette::INK),
                        "{} is not drawn in the ink",
                        destination.label()
                    );
                }
            }
        }
        compose_rust::window::reset_window_size();
    }

    /// Nothing on any screen is painted by the design system: every fill, outline and
    /// tint is a literal from the palette.
    ///
    /// Named for what it defends: the quote card was `PrimaryContainer`, the week was
    /// `Primary`, and the worry chips and the pill that moves on were `Filled`, so all of
    /// them came out in the theme's blue and its tints.
    #[test]
    fn fr22_nothing_on_the_screen_is_painted_by_a_role() {
        let mut screen = Screen::new();
        let check = |screen: &Screen, place: &str| {
            for mutation in screen.live() {
                match mutation {
                    Mutation::SetModifier {
                        node_id,
                        modifier: Wire::Background(paint) | Wire::Border { paint, .. },
                        ..
                    } => assert!(
                        matches!(paint, Paint::Literal(_)),
                        "on {place}, node {node_id} is filled with {paint:?}, which the design system picks"
                    ),
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Color,
                        value: PropertyValue::Integer(bits),
                    } => {
                        if let Some(paint) = Paint::from_bits(bits as u64) {
                            assert!(
                                matches!(paint, Paint::Literal(_)),
                                "on {place}, node {node_id} is drawn in {paint:?}, which the design system picks"
                            );
                        }
                    }
                    _ => {}
                }
            }
        };
        check(&screen, "the check-in");
        assert!(screen.press("Next"), "the feeling step has no way on");
        assert!(screen.press(WORRIES[2]), "no worry can be chosen");
        check(&screen, "the worry picker");
        for choice in [
            Destination::Listen,
            Destination::Library,
            Destination::Profile,
        ] {
            assert!(
                screen.go(choice),
                "the bar has no way to {}",
                choice.label()
            );
            check(&screen, choice.label());
        }
        compose_rust::window::reset_window_size();
    }

    /// The check-in counts its steps along the top, three segments with the ones reached
    /// filled, as the reference does.
    #[test]
    fn fr22_the_check_in_shows_which_step_it_is_on() {
        let mut screen = Screen::new();
        let reached = |screen: &Screen, done: Paint| {
            let segments = screen.nodes_of_height(SEGMENT_HEIGHT);
            assert_eq!(
                segments.len(),
                STEPS,
                "the progress is not {STEPS} segments"
            );
            segments
                .iter()
                .filter(|node| screen.background_of(**node) == Some(done))
                .count()
        };
        assert_eq!(
            reached(&screen, palette::ON_LIGHT),
            1,
            "the first step is not marked"
        );
        assert!(screen.press("Next"), "the feeling step has no way on");
        assert_eq!(
            reached(&screen, palette::INK),
            2,
            "the second step is not marked"
        );
        compose_rust::window::reset_window_size();
    }

    /// The feelings are a carousel with the chosen one in the middle, and choosing another
    /// turns the strip to it.
    ///
    /// Named for what it defends: the strip was four equal pills, all of them in view,
    /// where the reference centres the chosen feeling and cuts its neighbours off at the
    /// window's edges.
    #[test]
    fn fr22_the_moods_are_a_carousel_centred_on_the_choice() {
        let mut screen = Screen::new();
        assert_eq!(
            screen.strip_target(),
            Some(Mood::Calm.index() as f32 * SLOT),
            "the carousel does not open on the feeling already chosen"
        );
        let lead = (PHONE - SLOT) / 2.0;
        assert!(
            screen.live().iter().any(|mutation| matches!(
                mutation,
                Mutation::SetModifier { modifier: Wire::Width(dp), .. } if *dp == lead
            )),
            "the carousel has no room for its first and last feelings to reach the middle"
        );
        assert!(screen.press(Mood::Happy.label()), "no way to choose a mood");
        assert_eq!(
            screen.strip_target(),
            Some(Mood::Happy.index() as f32 * SLOT),
            "choosing a feeling did not turn the carousel to it"
        );
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

    /// The profile, which is the other drawing: a week as a line on the page.
    #[test]
    fn fr16_the_profile_is_recorded() {
        sample_frames::record_as(
            "SelfCareProfile",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    go_in(screen, Destination::Profile),
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
                    go_in(screen, Destination::Listen),
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
