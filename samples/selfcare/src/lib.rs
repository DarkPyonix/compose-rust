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

use compose_rust::AssetKind;
use compose_rust::runtime::*;
use compose_rust::ui::*;
use mood::{Mood, SESSIONS, Session, WORRIES, week_line};
use std::rc::Rc;

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
}

/// The two steps of the check-in.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Step {
    Feeling,
    Worry,
    Done,
}

/// What a press does. Shared, because one handler can reach more than one control.
type Action = Rc<dyn Fn()>;

/// The mood picker: the face, the strip under it, and the question.
#[composable]
fn feeling_step(chosen: MutableState<Mood>, on_skip: Action, on_next: Action) {
    let current = chosen.get();
    let (fill, _ink) = current.pair();
    Column().modifier(Modifier.fill_max_size()).content(|| {
        // The face fills the top of the screen in the tint of the feeling it is showing,
        // which is the reference's whole idea: the answer is the colour. It runs to the
        // window's edges, so it is outside the inset column below rather than the first
        // row of it.
        Box()
            .modifier(
                Modifier
                    .fill_max_width()
                    .height(FACE_SIDE * 1.4)
                    .background(Paint::Literal(fill)),
            )
            .alignment(Alignment::Center)
            .content(|| {
                Image(asset(AssetKind::Svg, current.picture()))
                    .modifier(Modifier.size(FACE_SIDE, FACE_SIDE));
            });

        // Everything under the face, inset once and spaced once.
        //
        // Each of these used to carry its own padding inside a column that was also
        // spacing them, so the gap between two of them was the space rung plus two
        // paddings and the gap between two others was the rung alone. The ladder answers
        // how far apart two things sit; it cannot do that if half the answer is added
        // again by each thing.
        Column()
            .modifier(
                Modifier
                    .fill_max_width()
                    .weight(1.0)
                    .padding_role(SpaceRole::Md),
            )
            .space_role(SpaceRole::Lg)
            .content(|| {
                // The strip of answers. The chosen one is filled and the rest are plain,
                // so which colour "filled" means stays the design system's.
                Row()
                    .modifier(Modifier.fill_max_width())
                    .space_role(SpaceRole::Sm)
                    .content(|| {
                        for option in Mood::STRIP {
                            // The chosen answer is filled with the feeling's own colour
                            // and the rest are the quiet grey the reference gives them.
                            // The answer is the colour, so the colour is what changes.
                            let picked = option == current;
                            let (background, ink) = if picked {
                                (option.pair().0, option.pair().1)
                            } else {
                                (palette::CHIP, palette::INK)
                            };
                            let choose = chosen.clone();
                            key(option.label(), || {
                                Button(option.label())
                                    .variant(ButtonVariant::Filled)
                                    .color(Paint::Literal(ink))
                                    .modifier(
                                        Modifier
                                            .weight(1.0)
                                            .shape_role(ShapeRole::Full)
                                            .background(Paint::Literal(background)),
                                    )
                                    .on_click(move || choose.set(option));
                            });
                        }
                    });

                Column()
                    .modifier(Modifier.fill_max_width())
                    .space_role(SpaceRole::Xs)
                    .alignment(Alignment::Center)
                    .content(|| {
                        Text("How do you feel today?")
                            .type_role(TypeRole::Headline)
                            .text_align(TextAlign::Center)
                            .color(Paint::Literal(palette::INK));
                    });

                Spacer().modifier(Modifier.weight(1.0));

                Row()
                    .modifier(Modifier.fill_max_width())
                    .space_role(SpaceRole::Md)
                    .alignment(Alignment::CenterStart)
                    .content(|| {
                        let skip = Rc::clone(&on_skip);
                        Button("Skip")
                            .variant(ButtonVariant::Text)
                            .color(Paint::Literal(palette::INK))
                            .on_click(move || skip());
                        let next = Rc::clone(&on_next);
                        Button("Next")
                            .variant(ButtonVariant::Filled)
                            .color(Paint::Literal(palette::PAGE))
                            .modifier(
                                Modifier
                                    .weight(1.0)
                                    .shape_role(ShapeRole::Full)
                                    .background(Paint::Literal(palette::INK)),
                            )
                            .on_click(move || next());
                    });
            });
    });
}

/// The worry picker: ten chips, any number of them chosen.
#[composable]
fn worry_step(picked: MutableState<Vec<&'static str>>, on_skip: Action, on_next: Action) {
    let chosen = picked.get();
    // Two to a row. There is no wrapping row in the vocabulary, so a grid is rows of a
    // fixed count and the code says how many rather than the layout working it out.
    let rows: Vec<&[&'static str]> = WORRIES.chunks(2).collect();
    Column()
        .modifier(Modifier.fill_max_size().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("What's worrying you?")
                .type_role(TypeRole::Headline)
                .text_align(TextAlign::Center)
                .color(Paint::Literal(palette::INK))
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
                                        // Chosen worries are the accent with the page's black
                                        // on them, the rest the quiet grey the feelings'
                                        // strip uses.
                                        let (background, ink) = if chosen.contains(&worry) {
                                            (palette::ACCENT, palette::ON_MOOD)
                                        } else {
                                            (palette::CHIP, palette::INK)
                                        };
                                        let list = picked.clone();
                                        key(worry, || {
                                            Button(worry)
                                                .variant(ButtonVariant::Filled)
                                                .color(Paint::Literal(ink))
                                                .modifier(
                                                    Modifier
                                                        .weight(1.0)
                                                        .shape_role(ShapeRole::Full)
                                                        .background(Paint::Literal(background)),
                                                )
                                                .on_click(move || {
                                                    list.update(|list| {
                                                        match list
                                                            .iter()
                                                            .position(|found| *found == worry)
                                                        {
                                                            Some(at) => {
                                                                list.remove(at);
                                                            }
                                                            None => list.push(worry),
                                                        }
                                                    })
                                                });
                                        });
                                    }
                                });
                        });
                    }
                });

            Spacer().modifier(Modifier.weight(1.0));

            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Md)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    let skip = Rc::clone(&on_skip);
                    Button("Skip")
                        .variant(ButtonVariant::Text)
                        .color(Paint::Literal(palette::INK))
                        .on_click(move || skip());
                    let next = Rc::clone(&on_next);
                    Button("Next")
                        .variant(ButtonVariant::Filled)
                        .color(Paint::Literal(palette::PAGE))
                        .modifier(
                            Modifier
                                .weight(1.0)
                                .shape_role(ShapeRole::Full)
                                .background(Paint::Literal(palette::INK)),
                        )
                        .on_click(move || next());
                });
        });
}

/// What the check-in leaves behind: the day, in one line, and a way back into it.
#[composable]
fn checked_in(chosen: Mood, worries: Vec<&'static str>, again: Action) {
    let (fill, ink) = chosen.pair();
    let summary = if worries.is_empty() {
        "Nothing in particular.".to_owned()
    } else {
        worries.join(", ")
    };
    Column()
        .modifier(Modifier.fill_max_size().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(Paint::Literal(fill))
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
                        .color(Paint::Literal(ink))
                        .text_align(TextAlign::Center);
                    Text(summary)
                        .type_role(TypeRole::Body)
                        .color(Paint::Literal(ink))
                        .text_align(TextAlign::Center);
                });
            Button("Check in again")
                .variant(ButtonVariant::Filled)
                .color(Paint::Literal(palette::INK))
                .modifier(
                    Modifier
                        .fill_max_width()
                        .shape_role(ShapeRole::Full)
                        .background(Paint::Literal(palette::CHIP)),
                )
                .on_click(move || again());
            Spacer().modifier(Modifier.weight(1.0));
        });
}

/// Says that playing a session is not part of this sample.
fn not_playable() {
    Message::new("Playback is not part of this sample").show();
}

/// One session, as a card in the row that runs off the edge.
fn session_card(session: &Session) {
    let (fill, ink) = session.mood.pair();
    Column()
        .modifier(
            Modifier
                .fill_max_size()
                .background(Paint::Literal(fill))
                .shape_role(ShapeRole::Large)
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Xs)
        .content(|| {
            Text(session.title)
                .type_role(TypeRole::Subtitle)
                .color(Paint::Literal(ink))
                .max_lines(2)
                .overflow(TextOverflow::Ellipsis);
            Spacer().modifier(Modifier.weight(1.0));
            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Text(format!("{} min", session.minutes))
                        .type_role(TypeRole::Label)
                        .color(Paint::Literal(ink));
                    Spacer().modifier(Modifier.weight(1.0));
                    // A white disc on the pastel, as the reference draws its play keys.
                    Button("\u{25b6}")
                        .variant(ButtonVariant::Filled)
                        .color(Paint::Literal(palette::ON_MOOD))
                        .modifier(
                            Modifier
                                .shape_role(ShapeRole::Full)
                                .background(Paint::Literal(palette::INK)),
                        )
                        .on_click(not_playable);
                });
        });
}

/// The listening screen: a greeting, a search field and the sessions.
#[composable]
fn listen_page() {
    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("Hello, Paul")
                .type_role(TypeRole::Headline)
                .color(Paint::Literal(palette::INK));
            TextField()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(Paint::Literal(palette::CARD))
                        .shape_role(ShapeRole::Full),
                )
                .placeholder("Search");

            Text("Picked for today")
                .type_role(TypeRole::Label)
                .color(Paint::Literal(palette::MUTED));
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
                                .content(|| session_card(&SESSIONS[position]));
                        },
                    );
                });

            Text("Special for you")
                .type_role(TypeRole::Label)
                .color(Paint::Literal(palette::MUTED));
            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(Paint::Literal(palette::CARD))
                        .shape_role(ShapeRole::Large),
                )
                .content(|| {
                    Column().modifier(Modifier.fill_max_width()).content(|| {
                        for (index, session) in SESSIONS.iter().enumerate() {
                            key(session.title, || {
                                special_row(session, index + 1 < SESSIONS.len());
                            });
                        }
                    });
                });
        });
}

/// One row of "Special for you", with the hairline under every row but the last.
#[composable]
fn special_row(session: &'static Session, separated: bool) {
    Column().modifier(Modifier.fill_max_width()).content(|| {
        Row()
            .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
            .space_role(SpaceRole::Sm)
            .alignment(Alignment::CenterStart)
            .content(|| {
                Column()
                    .modifier(Modifier.weight(1.0))
                    .space_role(SpaceRole::Xs)
                    .content(|| {
                        Text(session.title)
                            .type_role(TypeRole::Body)
                            .color(Paint::Literal(palette::INK))
                            .max_lines(1)
                            .overflow(TextOverflow::Ellipsis);
                        Text(format!("{} min \u{00b7} {}", session.minutes, session.when))
                            .type_role(TypeRole::Caption)
                            .color(Paint::Literal(palette::MUTED));
                    });
                Button("\u{25b6}")
                    .variant(ButtonVariant::Filled)
                    .color(Paint::Literal(palette::ON_MOOD))
                    .modifier(
                        Modifier
                            .shape_role(ShapeRole::Full)
                            .background(Paint::Literal(palette::ACCENT)),
                    )
                    .on_click(not_playable);
            });
        if separated {
            Separator(Some(Paint::Literal(palette::CHIP)));
        }
    });
}

/// Everything there is, as a plain list.
#[composable]
fn library_page() {
    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("Library")
                .type_role(TypeRole::Headline)
                .color(Paint::Literal(palette::INK));
            Column()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .content(|| {
                    for session in SESSIONS.iter() {
                        key(session.title, || {
                            Row()
                                .modifier(Modifier.fill_max_width())
                                .space_role(SpaceRole::Sm)
                                .alignment(Alignment::CenterStart)
                                .content(|| {
                                    Box().modifier(
                                        Modifier
                                            .size(48.0, 48.0)
                                            .background(Paint::Literal(session.mood.pair().0))
                                            .shape_role(ShapeRole::Medium),
                                    );
                                    Column().modifier(Modifier.weight(1.0)).content(|| {
                                        Text(session.title)
                                            .type_role(TypeRole::Body)
                                            .color(Paint::Literal(palette::INK))
                                            .max_lines(1)
                                            .overflow(TextOverflow::Ellipsis);
                                        Text(format!(
                                            "{} min \u{00b7} {}",
                                            session.minutes, session.when
                                        ))
                                        .type_role(TypeRole::Caption)
                                        .color(Paint::Literal(palette::MUTED));
                                    });
                                });
                        });
                    }
                });
        });
}

/// The profile: who this is, how the week went, and the settings under it.
#[composable]
fn profile_page(chosen: Mood) {
    let (fill, ink) = chosen.pair();
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
                        .color(Paint::Literal(palette::INK));
                    Text(chosen.label())
                        .type_role(TypeRole::Label)
                        .color(Paint::Literal(ink))
                        .modifier(
                            Modifier
                                .background(Paint::Literal(fill))
                                .shape_role(ShapeRole::Full)
                                .padding_role(SpaceRole::Sm),
                        );
                });

            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(Paint::Literal(palette::CARD))
                        .shape_role(ShapeRole::Large)
                        .padding_role(SpaceRole::Md),
                )
                .content(|| {
                    Column()
                        .modifier(Modifier.fill_max_width())
                        .space_role(SpaceRole::Sm)
                        .content(|| {
                            Text("This week")
                                .type_role(TypeRole::Label)
                                .color(Paint::Literal(palette::MUTED));
                            Canvas(week_line(CHART.0, CHART.1, palette::MUTED, palette::ACCENT))
                                .modifier(Modifier.size(CHART.0, CHART.1));
                        });
                });

            // The quote card. The reference draws it as a dark card with the quotation
            // mark on a small tile of the accent, not as a panel of an accent container:
            // that came out of the running design system as a pale lilac the picture does
            // not have anywhere.
            Row()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(Paint::Literal(palette::CARD))
                        .shape_role(ShapeRole::Large)
                        .padding_role(SpaceRole::Md),
                )
                .space_role(SpaceRole::Md)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Text("\u{201c}")
                        .type_role(TypeRole::Title)
                        .color(Paint::Literal(palette::ON_MOOD))
                        .modifier(
                            Modifier
                                .background(Paint::Literal(palette::ACCENT))
                                .shape_role(ShapeRole::Small)
                                .padding_role(SpaceRole::Sm),
                        );
                    Text("Every day is a new opportunity for growth and positive change.")
                        .type_role(TypeRole::Body)
                        .color(Paint::Literal(palette::INK))
                        .modifier(Modifier.weight(1.0));
                });

            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(Paint::Literal(palette::CARD))
                        .shape_role(ShapeRole::Large),
                )
                .content(|| {
                    Column().modifier(Modifier.fill_max_width()).content(|| {
                        for (index, entry) in
                            ["Settings", "Language", "FAQ"].into_iter().enumerate()
                        {
                            key(entry, || settings_row(entry, index < 2));
                        }
                    });
                });
        });
}

/// One entry under the profile, with the hairline under every entry but the last.
#[composable]
fn settings_row(entry: &'static str, separated: bool) {
    Column().modifier(Modifier.fill_max_width()).content(|| {
        Row()
            .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
            .alignment(Alignment::CenterStart)
            .content(|| {
                Text(entry)
                    .type_role(TypeRole::Body)
                    .color(Paint::Literal(palette::INK))
                    .modifier(Modifier.weight(1.0));
                Text("\u{203a}")
                    .type_role(TypeRole::Body)
                    .color(Paint::Literal(palette::MUTED));
            });
        if separated {
            Separator(Some(Paint::Literal(palette::CHIP)));
        }
    });
}

/// The page for one destination, and for the check-in the step it is on.
#[composable]
fn page(
    destination: Destination,
    step: MutableState<Step>,
    chosen: MutableState<Mood>,
    picked: MutableState<Vec<&'static str>>,
) {
    let to = |next: Step| -> Action {
        let step = step.clone();
        Rc::new(move || step.set(next))
    };
    match destination {
        Destination::Today => match step.get() {
            Step::Feeling => feeling_step(chosen.clone(), to(Step::Done), to(Step::Worry)),
            Step::Worry => worry_step(picked.clone(), to(Step::Done), to(Step::Done)),
            Step::Done => checked_in(chosen.get(), picked.get(), to(Step::Feeling)),
        },
        Destination::Listen => listen_page(),
        Destination::Library => library_page(),
        Destination::Profile => profile_page(chosen.get()),
    }
}

/// The bar along the bottom: four icons and nothing else.
///
/// Drawn here rather than declared as a `Navigation`, which is the widget for "the
/// destinations, in whatever shape this design system and this window call for": a
/// labelled bar with a selection pill, in the running system's accent. This design marks
/// the destination you are on with its own colour, so the bar is the sample's to draw.
#[composable]
fn bottom_bar(destination: Destination, on_go: Rc<dyn Fn(Destination)>) {
    Row()
        .modifier(
            Modifier
                .fill_max_width()
                .background(Paint::Literal(palette::PAGE))
                .padding_role(SpaceRole::Sm),
        )
        .arrangement(Arrangement::SpaceAround)
        .alignment(Alignment::Center)
        .content(|| {
            for choice in Destination::STRIP {
                let tint = if choice == destination {
                    palette::ACCENT
                } else {
                    palette::MUTED
                };
                let go = Rc::clone(&on_go);
                key(choice.label(), || {
                    Button("")
                        .icon(choice.icon())
                        .variant(ButtonVariant::Text)
                        .color(Paint::Literal(tint))
                        .on_click(move || go(choice));
                });
            }
        });
}

#[composable]
pub fn app() {
    let window = current_window_size();
    let measure = if window.is_compact() {
        None
    } else {
        Some(PAGE_MEASURE)
    };

    let destination = remember(|| mutable_state_of(Destination::Today));
    let step = remember(|| mutable_state_of(Step::Feeling));
    let chosen = remember(|| mutable_state_of(Mood::Calm));
    let picked = remember(|| mutable_state_of(Vec::<&'static str>::new()));

    let current = destination.get();
    // The check-in runs to the window's edges, because the tint behind the face is the
    // screen rather than a card on it. The rest are pages that scroll.
    let scrolls = current != Destination::Today || step.get() != Step::Feeling;
    let frame = Modifier
        .width_if(measure)
        .fill_max_width_if(measure.is_none())
        .fill_max_height();

    // The frame is named rather than built. Which of a bar, a rail or a drawer the
    // destinations become, and where the page sits beside them, are answers this screen
    // never has to know.
    Scaffold()
        .modifier(Modifier.background(Paint::Literal(palette::PAGE)))
        .bottom_bar(|| {
            let on_go: Rc<dyn Fn(Destination)> = {
                let destination = destination.clone();
                Rc::new(move |choice: Destination| destination.set(choice))
            };
            bottom_bar(current, on_go);
        })
        .content(|| {
            Column()
                .modifier(
                    Modifier
                        .fill_max_size()
                        .background(Paint::Literal(palette::PAGE)),
                )
                .content(|| {
                    Box()
                        .modifier(Modifier.fill_max_size())
                        .alignment(Alignment::TopCenter)
                        .content(|| {
                            let body =
                                || page(current, step.clone(), chosen.clone(), picked.clone());
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
    use compose_rust::schema::{
        EventPayload, Modifier as SchemaModifier, PropertyKind, WidgetKind,
    };

    /// Named for what it defends: the reference is a dark design, and a machine set the
    /// other way drew this sample light with nothing to compare against.
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
        assert_eq!(theme.color_scheme, ColorScheme::Dark);
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

        /// Presses a destination along the bottom, which is an icon with no words.
        fn press_icon(&mut self, icon: IconRole) -> bool {
            let found = {
                let mutations = self.mutations();
                let node = mutations.iter().rev().find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Icon,
                        value: PropertyValue::Integer(value),
                    } if *value == icon as i64 => Some(*node_id),
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

        /// Every colour the screen set as a property, as the paint it decodes to.
        fn tints(&self) -> Vec<Paint> {
            self.mutations()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetProp {
                        property: PropertyKind::Color,
                        value: PropertyValue::Integer(bits),
                        ..
                    } => Paint::from_bits(*bits as u64),
                    _ => None,
                })
                .collect()
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
        assert!(ComposeHost::new(app).rebuild().is_ok());
    }

    /// The face on the check-in has to reach the Renderer as a picture. An `Image` whose
    /// id names nothing is a blank square, which on this screen is most of the screen.
    #[test]
    fn fr16_the_check_in_draws_a_face() {
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
    }

    /// Every destination has to encode, not just the one the app opens on.
    #[test]
    fn fr15_every_destination_encodes() {
        let mut screen = Screen::new();
        for choice in Destination::STRIP {
            assert!(
                screen.press_icon(choice.icon()),
                "the bar has no way to {}",
                choice.label()
            );
        }
    }

    /// The bar is icons alone, drawn by the sample, with the one you are on in the
    /// accent.
    #[test]
    fn fr22_the_bar_is_icons_with_the_accent_on_the_destination() {
        let mut screen = Screen::new();
        assert!(
            !screen.mutations().iter().any(|mutation| matches!(
                mutation,
                Mutation::Create {
                    widget: WidgetKind::Navigation | WidgetKind::NavigationItem,
                    ..
                }
            )),
            "a Navigation draws the design system's labelled bar, not the reference's"
        );
        // The ink of each destination's icon, read back from the wire.
        let bar = |screen: &Screen| -> Vec<Paint> {
            Destination::STRIP
                .iter()
                .map(|choice| {
                    let mutations = screen.mutations();
                    let node = mutations
                        .iter()
                        .rev()
                        .find_map(|mutation| match mutation {
                            Mutation::SetProp {
                                node_id,
                                property: PropertyKind::Icon,
                                value: PropertyValue::Integer(value),
                            } if *value == choice.icon() as i64 => Some(*node_id),
                            _ => None,
                        })
                        .unwrap_or_else(|| panic!("the bar has no {} icon", choice.label()));
                    mutations
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
                        .unwrap_or_else(|| panic!("the {} icon has no ink", choice.label()))
                })
                .collect()
        };
        assert_eq!(
            bar(&screen),
            vec![
                Paint::Literal(palette::ACCENT),
                Paint::Literal(palette::MUTED),
                Paint::Literal(palette::MUTED),
                Paint::Literal(palette::MUTED),
            ],
            "the destination you are on is the accent and the rest are quiet"
        );
        assert!(screen.press_icon(Destination::Library.icon()));
        assert_eq!(
            bar(&screen),
            vec![
                Paint::Literal(palette::MUTED),
                Paint::Literal(palette::MUTED),
                Paint::Literal(palette::ACCENT),
                Paint::Literal(palette::MUTED),
            ],
            "the accent did not follow the destination"
        );
        compose_rust::window::reset_window_size();
    }

    /// Nothing on any page is painted or inked by the design system.
    ///
    /// Named for what it defends: the profile's quote card was an accent container, which
    /// came out of the running system as a pale lilac, and the week's line was drawn in
    /// that system's accent. Neither is in the picture.
    #[test]
    fn fr22_nothing_on_the_screen_is_painted_by_a_role() {
        let mut screen = Screen::new();
        assert!(screen.press("Next"), "the feeling step has no way on");
        for choice in Destination::STRIP {
            assert!(screen.press_icon(choice.icon()));
        }
        for mutation in screen.mutations() {
            if let Mutation::SetModifier {
                modifier: SchemaModifier::Background(paint) | SchemaModifier::Border { paint, .. },
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
        for paint in screen.tints() {
            assert!(
                matches!(paint, Paint::Literal(_)),
                "something is inked with {paint:?}, which the design system picks"
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
                    screen.press_icon(Destination::Profile.icon()),
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
                    screen.press_icon(Destination::Listen.icon()),
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
