//! A drum school: four subjects, four stages of lessons, and what is still locked.
//!
//! The marks on the tiles are pictures: the reference puts a rendered object on each, and
//! three concentric circles in four colours is four subjects nobody can tell apart.
//!
//! Unified, naming Cupertino and dark: the reference is drawn on black, both screens,
//! and a drum school that comes up white on a machine set to light is not that design.
//! `THEME` says both. The colours are the picture's own, named in `palette`: four subjects
//! that are four equals, one white card and dark grey locked ones. Said with roles they
//! were the running design system's blue and its pale accent containers, which the
//! picture never had.

mod palette;
mod school;

use compose_rust::AssetKind;
use compose_rust::runtime::*;
use compose_rust::ui::*;
use school::{
    LESSONS, Lesson, STAGES, SUBJECTS, Subject, ordinal, overall_progress, reached, stage,
};
use std::rc::Rc;

/// A phone design in a desktop window is still a phone design.
const PAGE_MEASURE: f32 = 420.0;

/// How large a subject's mark is, and how tall its tile is.
///
/// The reference's four tiles take the top half of the screen with the object on each one
/// filling most of its tile, and a grid of small marks in tall tiles left a band of empty
/// page under it on a phone.
const MARK_SIDE: f32 = 108.0;
const TILE_HEIGHT: f32 = 196.0;
const STRIP_HEIGHT: f32 = 56.0;

/// How large a control whose face is an icon is: the bar's four, and the plan's back,
/// search and menu.
const ICON_TAP: f32 = 44.0;

/// The chevrons beside "LESSON PLAN" and "YOUR PROGRESS", which are set at the size of the
/// small capitals next to them rather than at a control's size.
const CHEVRON_SIDE: f32 = 16.0;

/// The picture in the top corner of the subjects page.
const AVATAR_SIDE: f32 = 52.0;

/// The coral dots: the one on the destination you are on and the one beside "RECENT".
const DOT: f32 = 6.0;

/// The coral circle in the corner of the open card, and how far it is pushed past the
/// card's edges. The card clips it, so what shows is the quarter the reference draws.
const CORNER_SIDE: f32 = 112.0;
const CORNER_PUSH: f32 = 40.0;

/// The picture standing in for the pupil, drawn rather than photographed: a photograph of
/// a person belongs to whoever took it.
static AVATAR: &[u8] = include_bytes!("../assets/avatar.svg");

/// The icons this school draws outside a button, as the bytes each registration carries:
/// the meaning's wire tag, little endian, and nothing else. The Renderer holds the artwork
/// for every design system.
mod icon {
    use compose_rust::ui::IconRole;

    pub static BACK: [u8; 2] = (IconRole::Back as u16).to_le_bytes();
    pub static FORWARD: [u8; 2] = (IconRole::Forward as u16).to_le_bytes();
}

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

    /// What the destination is called. Nothing on screen says it: the reference's bar is
    /// icons alone. It is the name assistive technology reads for the icon.
    fn label(self) -> &'static str {
        match self {
            Destination::Skills => "Skills",
            Destination::Plan => "Plan",
            Destination::Progress => "Progress",
            Destination::Profile => "Profile",
        }
    }

    /// What the destination means, so the Renderer draws its own artwork for it.
    fn icon(self) -> IconRole {
        match self {
            Destination::Skills => IconRole::Home,
            Destination::Plan => IconRole::List,
            Destination::Progress => IconRole::Check,
            Destination::Profile => IconRole::Settings,
        }
    }
}

/// What opening a subject does, handed down to every tile.
type OpenSubject = Rc<dyn Fn(&'static str)>;

/// A control whose face is an icon and nothing else, in the page's white.
fn icon_button(icon: IconRole, name: &'static str, on_click: impl FnMut() + 'static) {
    Button("")
        .icon(icon)
        .accessible_name(name)
        .variant(ButtonVariant::Text)
        .color(palette::TEXT)
        .modifier(Modifier.size(ICON_TAP, ICON_TAP))
        .on_click(on_click);
}

/// Small bold capitals, spaced out, which is how the reference sets its labels.
fn capitals(label: &'static str, ink: Paint) {
    Text(label)
        .type_role(TypeRole::Caption)
        .font_weight(700)
        .letter_spacing(1.0)
        .color(ink);
}

/// One subject as a tile: the mark, and the name centred under it.
fn subject_tile(subject: &'static Subject, on_open: &OpenSubject) {
    let name = subject.name;
    Box()
        .modifier(
            Modifier
                .fill_max_width()
                .height(TILE_HEIGHT)
                .background(subject.fill)
                .shape_role(ShapeRole::Large)
                .padding_role(SpaceRole::Md),
        )
        .content(|| {
            Column()
                .modifier(Modifier.fill_max_size())
                .alignment(Alignment::Center)
                .content(|| {
                    Box()
                        .modifier(Modifier.fill_max_width().weight(1.0))
                        .alignment(Alignment::Center)
                        .content(|| {
                            Image(compose_rust::asset(AssetKind::Svg, subject.mark))
                                .modifier(Modifier.size(MARK_SIDE, MARK_SIDE));
                        });
                    let open = Rc::clone(on_open);
                    Button(name)
                        .variant(ButtonVariant::Text)
                        .color(palette::TEXT)
                        .modifier(Modifier.padding_role(SpaceRole::None))
                        .on_click(move || open(name));
                });
        });
}

/// The subjects page: the pupil's picture on a line of its own, the title under it, the
/// four subjects two to a row, and the two ways on from here.
fn skills_page(on_open: &OpenSubject, destination: MutableState<Destination>) {
    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            Row()
                .modifier(Modifier.fill_max_width())
                .arrangement(Arrangement::End)
                .content(|| {
                    Image(compose_rust::asset(AssetKind::Svg, AVATAR)).modifier(
                        Modifier
                            .size(AVATAR_SIDE, AVATAR_SIDE)
                            .shape_role(ShapeRole::Full)
                            .clip(true),
                    );
                });

            Text("Skills\nTo Pump!")
                .type_role(TypeRole::Display)
                .font_weight(600)
                .color(palette::TEXT);

            Column()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Md)
                .content(|| {
                    // Promoted to a static, so each tile can hold its subject for as long
                    // as the composition keeps it.
                    let subjects: &'static [Subject; 4] = &SUBJECTS;
                    for (index, row) in subjects.chunks(2).enumerate() {
                        key(index, || {
                            Row()
                                .modifier(Modifier.fill_max_width())
                                .space_role(SpaceRole::Md)
                                .alignment(Alignment::TopStart)
                                .content(|| {
                                    for subject in row {
                                        key(subject.name, || {
                                            Box().modifier(Modifier.weight(1.0)).content(|| {
                                                subject_tile(subject, on_open);
                                            });
                                        });
                                    }
                                });
                        });
                    }
                });

            Row()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Row()
                        .space_role(SpaceRole::Sm)
                        .alignment(Alignment::CenterStart)
                        .on_click(move || destination.set(Destination::Plan))
                        .content(|| {
                            Icon(compose_rust::asset(AssetKind::VectorIcon, &icon::BACK))
                                .color(palette::TEXT)
                                .modifier(Modifier.size(CHEVRON_SIDE, CHEVRON_SIDE));
                            capitals("LESSON PLAN", palette::TEXT);
                        });
                    Spacer().modifier(Modifier.weight(1.0));
                    Row()
                        .space_role(SpaceRole::Sm)
                        .alignment(Alignment::CenterStart)
                        .on_click(move || destination.set(Destination::Progress))
                        .content(|| {
                            capitals("YOUR PROGRESS", palette::TEXT);
                            Icon(compose_rust::asset(AssetKind::VectorIcon, &icon::FORWARD))
                                .color(palette::TEXT)
                                .modifier(Modifier.size(CHEVRON_SIDE, CHEVRON_SIDE));
                        });
                });
        });
}

/// One lesson as a card.
///
/// The open one is the one light thing on the screen: white, black ink, a chevron, and a
/// coral quarter circle in its corner. A locked one is a dark grey card with grey ink. The
/// reference marks a locked card with a padlock as well, and there is no padlock to draw
/// (`IconRole` has none), so the card names its state to assistive technology only.
#[Composable]
fn lesson_card(lesson: &'static Lesson) {
    if lesson.open {
        Box()
            .modifier(
                Modifier
                    .fill_max_width()
                    .background(palette::CARD)
                    .shape_role(ShapeRole::Large)
                    .clip(true),
            )
            .alignment(Alignment::BottomEnd)
            .content(|| {
                // Under the words, pushed past the corner so the card's clip leaves a
                // quarter of it.
                Box().modifier(
                    Modifier
                        .size(CORNER_SIDE, CORNER_SIDE)
                        .offset(CORNER_PUSH, CORNER_PUSH)
                        .background(palette::ACCENT)
                        .shape_role(ShapeRole::Full),
                );
                Row()
                    .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Lg))
                    .space_role(SpaceRole::Sm)
                    .alignment(Alignment::CenterStart)
                    .content(|| {
                        Column()
                            .modifier(Modifier.weight(1.0))
                            .space_role(SpaceRole::Sm)
                            .content(|| {
                                if lesson.recent {
                                    Row()
                                        .space_role(SpaceRole::Xs)
                                        .alignment(Alignment::TopStart)
                                        .content(|| {
                                            capitals("RECENT", palette::CARD_INK);
                                            Box().modifier(
                                                Modifier
                                                    .size(DOT, DOT)
                                                    .background(palette::ACCENT)
                                                    .shape_role(ShapeRole::Full),
                                            );
                                        });
                                }
                                Text(lesson.title)
                                    .type_role(TypeRole::Headline)
                                    .font_weight(700)
                                    .color(palette::CARD_INK);
                                Text(format!("{} lesson", ordinal(lesson.number)))
                                    .type_role(TypeRole::Body)
                                    .color(palette::CARD_MUTED);
                            });
                        Button("")
                            .icon(IconRole::Forward)
                            .accessible_name(lesson.title)
                            .variant(ButtonVariant::Text)
                            .color(palette::CHEVRON)
                            .modifier(Modifier.size(ICON_TAP, ICON_TAP))
                            .on_click(|| {
                                Message::new("Lessons are not part of this sample").show()
                            });
                    });
            });
    } else {
        Column()
            .modifier(
                Modifier
                    .fill_max_width()
                    .background(palette::LOCKED)
                    .shape_role(ShapeRole::Large)
                    .padding_role(SpaceRole::Lg)
                    .label("Locked"),
            )
            .space_role(SpaceRole::Sm)
            .content(|| {
                Text(lesson.title)
                    .type_role(TypeRole::Title)
                    .color(palette::LOCKED_INK);
                Text(format!("{} lesson", ordinal(lesson.number)))
                    .type_role(TypeRole::Body)
                    .color(palette::LOCKED_INK);
            });
    }
}

/// The lesson plan: a row of ways out, a stage strip that runs off the edge, the lessons
/// in that stage, and "MORE" under them.
fn plan_page(chosen: &MutableState<u32>, destination: MutableState<Destination>) {
    let lessons = stage(chosen.get());
    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            // The reference's plan screen has no title. Its top is a way back, search and
            // a menu.
            Row()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::CenterStart)
                .content(|| {
                    icon_button(IconRole::Back, "Back", move || {
                        destination.set(Destination::Skills)
                    });
                    Spacer().modifier(Modifier.weight(1.0));
                    icon_button(IconRole::Search, "Search", || {
                        Message::new("Search is not part of this sample").show()
                    });
                    icon_button(IconRole::Menu, "Menu", || {
                        Message::new("The menu is not part of this sample").show()
                    });
                });

            // A strip rather than a segmented control. `Tabs` divides its width equally
            // and does not scroll, so four stages on a phone is four segments of seventy
            // dp with a button's own padding inside each, and every label breaks.
            let strip = *chosen;
            LazyRow()
                .modifier(Modifier.fill_max_width().height(STRIP_HEIGHT))
                .content(move |list| {
                    list.items_keyed(
                        STAGES.len(),
                        |position| STAGES[position].to_string(),
                        move |position| {
                            let number = STAGES[position];
                            let current = strip.get() == number;
                            let pick = strip;
                            // The stage being looked at is the amber pill. The rest are
                            // words: white for a stage the pupil has reached, grey for one
                            // still ahead.
                            let ink = if current || reached(number) {
                                palette::TEXT
                            } else {
                                palette::MUTED
                            };
                            Box()
                                .modifier(Modifier.padding_role(SpaceRole::Xs))
                                .alignment(Alignment::Center)
                                .content(|| {
                                    Button(format!("{number} Stage"))
                                        .variant(ButtonVariant::Text)
                                        .color(ink)
                                        .modifier(
                                            Modifier
                                                .background_if(current.then_some(palette::AMBER))
                                                .shape_role(ShapeRole::Full),
                                        )
                                        .on_click(move || pick.set(number));
                                });
                        },
                    );
                });

            Column()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Md)
                .content(|| {
                    for lesson in lessons.iter().copied() {
                        key(lesson.number, || {
                            Box().modifier(Modifier.fill_max_width()).content(|| {
                                lesson_card(lesson);
                            });
                        });
                    }
                });

            Column()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::Center)
                .content(|| {
                    Column()
                        .alignment(Alignment::Center)
                        .on_click(|| Message::new("Later lessons open as you finish these").show())
                        .content(|| capitals("MORE", palette::TEXT));
                });
        });
}

/// How far through everything a pupil is, subject by subject. The reference has no such
/// screen, so it is drawn from the picture's own colours: the dark grey card for the
/// whole course and each subject's own tile colour for that subject.
fn progress_page() {
    let overall = overall_progress();
    let done = LESSONS.iter().filter(|lesson| lesson.open).count();
    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("Your progress")
                .type_role(TypeRole::Headline)
                .font_weight(600)
                .color(palette::TEXT);

            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(palette::LOCKED)
                        .shape_role(ShapeRole::Large)
                        .padding_role(SpaceRole::Md),
                )
                .space_role(SpaceRole::Sm)
                .content(|| {
                    Text(format!("{done} of {} lessons open", LESSONS.len()))
                        .type_role(TypeRole::Body)
                        .color(palette::TEXT);
                    ProgressIndicator(overall).modifier(Modifier.fill_max_width());
                });

            Column()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .content(|| {
                    for subject in &SUBJECTS {
                        key(subject.name, || {
                            Column()
                                .modifier(
                                    Modifier
                                        .fill_max_width()
                                        .background(subject.fill)
                                        .shape_role(ShapeRole::Large)
                                        .padding_role(SpaceRole::Md),
                                )
                                .space_role(SpaceRole::Xs)
                                .content(|| {
                                    Row()
                                        .modifier(Modifier.fill_max_width())
                                        .alignment(Alignment::CenterStart)
                                        .content(|| {
                                            Text(subject.name)
                                                .type_role(TypeRole::BodyStrong)
                                                .color(palette::TEXT)
                                                .modifier(Modifier.weight(1.0));
                                            Text(format!(
                                                "{}%",
                                                (subject.progress * 100.0).round() as i32
                                            ))
                                            .type_role(TypeRole::Label)
                                            .color(palette::TEXT);
                                        });
                                    Text(subject.blurb)
                                        .type_role(TypeRole::Caption)
                                        .color(palette::TEXT);
                                    ProgressIndicator(subject.progress)
                                        .modifier(Modifier.fill_max_width());
                                });
                        });
                    }
                });
        });
}

/// The profile destination, which the reference leaves as a name.
fn profile_page() {
    Box()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .alignment(Alignment::Center)
        .content(|| {
            Text("Profile")
                .type_role(TypeRole::Body)
                .color(palette::TEXT);
        });
}

/// The bar along the bottom: four white icons and nothing else.
///
/// Drawn here rather than declared as a `Navigation`, which is the widget for "the
/// destinations, in whatever shape this design system and this window call for": a
/// labelled tab bar at a phone's width and a rail or a sidebar wider than that. The
/// reference is four bare icons on the black page with no labels, no pill and no hairline,
/// and the one you are on carries a coral dot at its shoulder.
fn bottom_bar(current: Destination, destination: MutableState<Destination>) {
    Row()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Sm))
        .arrangement(Arrangement::SpaceAround)
        .alignment(Alignment::Center)
        .content(|| {
            for choice in Destination::STRIP {
                key(choice.label(), || {
                    Box()
                        .modifier(Modifier.size(ICON_TAP, ICON_TAP))
                        .alignment(Alignment::TopEnd)
                        .content(|| {
                            icon_button(choice.icon(), choice.label(), move || {
                                destination.set(choice)
                            });
                            if choice == current {
                                Box().modifier(
                                    Modifier
                                        .size(DOT, DOT)
                                        .offset(-DOT, DOT)
                                        .background(palette::ACCENT)
                                        .shape_role(ShapeRole::Full),
                                );
                            }
                        });
                });
            }
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

    let destination = remember(|| mutable_state_of(Destination::Skills));
    let chosen_stage = remember(|| mutable_state_of(2_u32));

    let on_open: OpenSubject = Rc::new(move |_: &'static str| {
        destination.set(Destination::Plan);
    });

    let current = destination.get();

    // The page and the bar are both held to a phone's width once the window is wider than
    // one, so the title, the grid and the bar stay in one column rather than the title
    // running to one edge of a desktop window and the bar to both.
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
            Box()
                .modifier(Modifier.fill_max_size().background(palette::PAGE))
                .alignment(Alignment::TopCenter)
                .content(|| {
                    ScrollColumn()
                        .modifier(
                            Modifier
                                .width_if(measure)
                                .fill_max_width_if(measure.is_none())
                                .fill_max_height(),
                        )
                        .content(|| match current {
                            Destination::Skills => skills_page(&on_open, destination),
                            Destination::Plan => plan_page(&chosen_stage, destination),
                            Destination::Progress => progress_page(),
                            Destination::Profile => profile_page(),
                        });
                });
        });
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
    // nothing was listed under whatever the renderer happened to be called, and every
    // sample here was listed under the toolkit's default name until this line existed.
    LaunchBuilder::new()
        // The same as [bundle] identifier in Dioxus.toml. It names the storage.
        .identifier("dev.darkpyonix.composerust.samples.academic")
        .with_theme(compose_rust::demo_theme_for(THEME))
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Academic")
                // Without one the window wears the toolkit's picture, which on Windows is
                // the Java coffee cup, wherever the system lists windows. The bytes travel
                // as an asset and the renderer refers to them by id: a path would be a
                // fact about the machine this was built on, and a name would ask the
                // toolkit to find something it may not have.
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
// Both are declared unconditionally. Each macro compiles into nothing that runs off its
// own platform, and gating them here instead would mean a desktop build never checks that
// this sample can still be built for the other two.
compose_rust::android_application!({ launch_builder() }, app);
compose_rust::web_application!({ launch_builder() }, app);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;

    use compose_rust::ComposeHost;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
    use compose_rust::schema::{EventPayload, Modifier as Wire, PropertyKind, WidgetKind};

    /// Named for what it defends: the reference is a dark design, and a machine set
    /// the other way drew this sample light with nothing to compare against.
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

    /// The screen, driven the way a Renderer drives it.
    ///
    /// Every batch is kept, because a batch is the change since the frame before it rather
    /// than what is on screen. Controls are looked up in what is still live, so a press
    /// never names a handler that went with its node.
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

        fn live(&self) -> Vec<Mutation<'_>> {
            sample_frames::live(self.mutations())
        }

        /// The handler a live node declared for a press.
        fn handler_of(&self, node: u32) -> Option<u64> {
            self.live()
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

        /// What a live node was last placed in.
        fn parent_of(&self, node: u32) -> Option<u32> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::Insert {
                        parent_id, node_id, ..
                    }
                    | Mutation::Move {
                        parent_id, node_id, ..
                    } if *node_id == node => Some(*parent_id),
                    _ => None,
                })
        }

        /// What a live node holds.
        fn children_of(&self, node: u32) -> Vec<u32> {
            let live = self.live();
            let mut children: Vec<u32> = live
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::Insert {
                        parent_id, node_id, ..
                    }
                    | Mutation::Move {
                        parent_id, node_id, ..
                    } if *parent_id == node => Some(*node_id),
                    _ => None,
                })
                .collect();
            children.sort_unstable();
            children.dedup();
            children
                .into_iter()
                .filter(|child| self.parent_of(*child) == Some(node))
                .collect()
        }

        /// The fill a live node was last given, if any.
        fn background_of(&self, node: u32) -> Option<Paint> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        node_id,
                        modifier: Wire::Background(paint),
                        ..
                    } if *node_id == node => Some(*paint),
                    _ => None,
                })
        }

        /// The live node that carries this text.
        fn node_saying(&self, label: &str) -> Option<u32> {
            self.live()
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

        /// The live control whose face is this icon.
        fn node_showing(&self, icon: IconRole) -> Option<u32> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Icon,
                        value: PropertyValue::Integer(value),
                    } if *value == icon as i64 => Some(*node_id),
                    _ => None,
                })
        }

        /// Presses a node, or the nearest thing around it that answers a press: a label
        /// written inside a pressable row is pressed through the row.
        fn press_node(&mut self, node: u32) -> bool {
            let mut at = Some(node);
            while let Some(candidate) = at {
                if let Some(handler_id) = self.handler_of(candidate) {
                    self.send(HostEvent {
                        node_id: candidate,
                        handler_id,
                        payload: EventPayload::Clicked,
                    });
                    return true;
                }
                at = self.parent_of(candidate);
            }
            false
        }

        fn press(&mut self, label: &str) -> bool {
            match self.node_saying(label) {
                Some(node) => self.press_node(node),
                None => false,
            }
        }

        fn press_icon(&mut self, icon: IconRole) -> bool {
            match self.node_showing(icon) {
                Some(node) => self.press_node(node),
                None => false,
            }
        }

        /// Answers every live windowing list's range request, which is what a real
        /// Renderer does before the first pixel. Without it a `LazyRow` is an empty box
        /// and nothing inside it can be pressed.
        fn fill_lists(&mut self, count: u32) {
            let lists: Vec<(u32, u64)> = {
                let live = self.live();
                live.iter()
                    .filter_map(|mutation| match mutation {
                        Mutation::Create {
                            node_id,
                            widget: WidgetKind::LazyColumn | WidgetKind::LazyRow,
                        } => Some(*node_id),
                        _ => None,
                    })
                    .filter_map(|node| {
                        live.iter().rev().find_map(|mutation| match mutation {
                            Mutation::SetProp {
                                node_id,
                                property: PropertyKind::OnRangeRequested,
                                value: PropertyValue::Integer(handler),
                            } if *node_id == node => Some((node, *handler as u64)),
                            _ => None,
                        })
                    })
                    .collect()
            };
            for (node_id, handler_id) in lists {
                self.send(HostEvent {
                    node_id,
                    handler_id,
                    payload: EventPayload::RangeRequested { start: 0, count },
                });
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

        /// The fills of everything live.
        fn live_backgrounds(&self) -> Vec<Paint> {
            self.live()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        modifier: Wire::Background(paint),
                        ..
                    } => Some(*paint),
                    _ => None,
                })
                .collect()
        }

        /// Every paint the screen has sent so far: fills, borders and the colours of
        /// text, icons and buttons.
        fn every_paint(&self) -> Vec<(u32, Paint)> {
            self.mutations()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        node_id,
                        modifier: Wire::Background(paint) | Wire::Border { paint, .. },
                        ..
                    } => Some((*node_id, *paint)),
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Color,
                        value: PropertyValue::Integer(bits),
                    } => Paint::from_bits(*bits as u64).map(|paint| (*node_id, paint)),
                    _ => None,
                })
                .collect()
        }
    }

    /// Walks every destination and, on the plan, every stage, so a test reads every
    /// screen the sample has rather than the one it opens on.
    fn every_screen() -> Screen {
        let mut screen = Screen::new();
        for choice in Destination::STRIP {
            assert!(
                screen.press_icon(choice.icon()),
                "the bar has no destination called {}",
                choice.label()
            );
            if choice == Destination::Plan {
                screen.fill_lists(STAGES.len() as u32);
                for number in STAGES {
                    assert!(
                        screen.press(&format!("{number} Stage")),
                        "the strip has no stage {number}"
                    );
                }
            }
        }
        screen
    }

    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        compose_rust::window::reset_window_size();
        assert!(ComposeHost::new(app).rebuild().is_ok());
    }

    /// Every destination has to encode, not just the one the app opens on.
    #[test]
    fn fr15_every_destination_encodes() {
        every_screen();
        compose_rust::window::reset_window_size();
    }

    /// The grid has four marks on it, one registration each, and a mark whose id names
    /// nothing is a blank square where a subject should be.
    #[test]
    fn fr16_every_subject_carries_a_mark() {
        compose_rust::window::reset_window_size();
        let batch = ComposeHost::new(app)
            .rebuild()
            .expect("the first frame failed")
            .to_vec();
        let mutations = decode_batch(&batch).expect("the batch did not decode");
        let mut marks = Vec::new();
        for subject in SUBJECTS {
            let registered: Vec<u32> = mutations
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::RegisterAsset {
                        asset_id, bytes, ..
                    } if *bytes == subject.mark => Some(*asset_id),
                    _ => None,
                })
                .collect();
            assert_eq!(
                registered.len(),
                1,
                "{} has no mark, or sent it more than once",
                subject.name
            );
            marks.push(registered[0]);
        }
        let drawn: Vec<u32> = mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetProp {
                    property: PropertyKind::Asset,
                    value: PropertyValue::Integer(id),
                    ..
                } if marks.contains(&(*id as u32)) => Some(*id as u32),
                _ => None,
            })
            .collect();
        for mark in marks {
            assert_eq!(
                drawn.iter().filter(|id| **id == mark).count(),
                1,
                "a subject's mark is not on the grid exactly once"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// Opening a subject takes you to its lessons.
    #[test]
    fn fr15_opening_a_subject_reaches_its_lessons() {
        let mut screen = Screen::new();
        assert!(screen.press("Technique"), "no subject on the grid opens");
        let recent = LESSONS
            .iter()
            .find(|lesson| lesson.recent)
            .expect("no recent lesson");
        let showing = screen.latest_texts();
        assert!(
            showing.iter().any(|text| text == recent.title),
            "opening a subject did not reach the lessons: {showing:?}"
        );
        compose_rust::window::reset_window_size();
    }

    /// "LESSON PLAN" and "YOUR PROGRESS" under the grid go where they say.
    #[test]
    fn fr15_the_labels_under_the_grid_lead_somewhere() {
        let mut screen = Screen::new();
        assert!(screen.press("LESSON PLAN"), "LESSON PLAN does nothing");
        assert!(
            screen.latest_texts().iter().any(|text| text == "MORE"),
            "LESSON PLAN did not reach the lessons"
        );
        assert!(
            screen.press_icon(IconRole::Back),
            "the plan has no way back"
        );
        assert!(screen.press("YOUR PROGRESS"), "YOUR PROGRESS does nothing");
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text == "Your progress"),
            "YOUR PROGRESS did not reach the progress page"
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
                screen.press(&format!("{number} Stage")),
                "the strip has no stage {number}"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// Nothing on any screen is painted by the design system.
    ///
    /// Named for what it defends: the lesson cards, the chosen stage and the progress
    /// cards were all roles once, so the open lesson and the chosen stage came out in
    /// the running system's blue and the progress cards in its pale accent containers.
    /// The reference has none of those colours.
    #[test]
    fn fr22_nothing_on_any_screen_is_painted_by_a_role() {
        let screen = every_screen();
        for (node, paint) in screen.every_paint() {
            assert!(
                matches!(paint, Paint::Literal(_)),
                "node {node} is painted {paint:?}, which the design system picks"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// No card or panel on any screen is one of the accent containers.
    #[test]
    fn fr22_no_card_is_an_accent_container() {
        let screen = every_screen();
        for (node, paint) in screen.every_paint() {
            assert!(
                !matches!(
                    paint,
                    Paint::Role(
                        ColorRole::PrimaryContainer
                            | ColorRole::SecondaryContainer
                            | ColorRole::TertiaryContainer
                    )
                ),
                "node {node} is painted {paint:?}"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// The bar is the sample's own: four controls whose face is an icon and nothing else,
    /// and no `Navigation` that the design system would turn into a tab bar, a rail or a
    /// sidebar.
    #[test]
    fn fr22_the_bar_is_four_icons_the_sample_draws() {
        let screen = every_screen();
        assert!(
            !screen.mutations().iter().any(|mutation| matches!(
                mutation,
                Mutation::Create {
                    widget: WidgetKind::Navigation | WidgetKind::NavigationItem,
                    ..
                }
            )),
            "the destinations are the design system's bar"
        );
        for choice in Destination::STRIP {
            let node = screen
                .node_showing(choice.icon())
                .unwrap_or_else(|| panic!("the bar has no icon for {}", choice.label()));
            let words: Vec<String> = screen
                .live()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                    } if *node_id == node && !text.is_empty() => Some((*text).to_owned()),
                    _ => None,
                })
                .collect();
            assert!(
                words.is_empty(),
                "{} is labelled {words:?}; the reference's bar is icons alone",
                choice.label()
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// The destination you are on is the one with the coral dot at its shoulder, and it
    /// moves with you.
    #[test]
    fn fr22_the_destination_you_are_on_carries_the_accent() {
        let mut screen = Screen::new();
        for choice in Destination::STRIP {
            assert!(
                screen.press_icon(choice.icon()),
                "the bar has no destination called {}",
                choice.label()
            );
            for other in Destination::STRIP {
                let icon = screen
                    .node_showing(other.icon())
                    .unwrap_or_else(|| panic!("the bar has no icon for {}", other.label()));
                let slot = screen.parent_of(icon).expect("a bar icon sits in nothing");
                let marked = screen
                    .children_of(slot)
                    .into_iter()
                    .any(|child| screen.background_of(child) == Some(palette::ACCENT));
                assert_eq!(
                    marked,
                    other == choice,
                    "on {}, {} is {}marked in the accent",
                    choice.label(),
                    other.label(),
                    if marked { "" } else { "not " }
                );
            }
        }
        compose_rust::window::reset_window_size();
    }

    /// The plan the reference draws: one white card for the lesson that is open, dark grey
    /// ones for the two still locked, and the chosen stage as the one amber pill. Nothing
    /// on it is a filled button, which is the theme's blue.
    #[test]
    fn fr22_the_lesson_cards_are_the_reference_white_and_grey() {
        let mut screen = Screen::new();
        assert!(
            screen.press_icon(Destination::Plan.icon()),
            "no lesson plan"
        );
        screen.fill_lists(STAGES.len() as u32);
        let fills = screen.live_backgrounds();
        let count = |wanted: Paint| fills.iter().filter(|paint| **paint == wanted).count();
        assert_eq!(
            count(palette::CARD),
            1,
            "the open lesson is not the one white card"
        );
        assert_eq!(
            count(palette::LOCKED),
            2,
            "the locked lessons are not dark grey"
        );
        assert_eq!(
            count(palette::AMBER),
            1,
            "the chosen stage is not the amber pill"
        );
        assert!(
            !screen.live().iter().any(|mutation| matches!(
                mutation,
                Mutation::SetProp {
                    property: PropertyKind::Variant,
                    value: PropertyValue::Integer(variant),
                    ..
                } if *variant == ButtonVariant::Filled as i64
            )),
            "a filled button on the plan is drawn in the theme's accent"
        );
        compose_rust::window::reset_window_size();
    }

    /// A phone design does not become a desktop design by being put in a wider window: the
    /// page, with the title on it, and the bar both stop at a phone's width.
    #[test]
    fn fr20_the_page_stops_widening_past_a_phone() {
        fn widths(width_dp: f32) -> Vec<f32> {
            compose_rust::window::reset_window_size();
            let mut host = ComposeHost::new(app);
            host.rebuild().expect("the first frame failed");
            let mut bytes = Vec::new();
            encode_event(
                &HostEvent {
                    node_id: 0,
                    handler_id: 0,
                    payload: EventPayload::WindowSizeChanged {
                        width_dp,
                        height_dp: 780.0,
                        class: WindowSizeClass::from_width_dp(width_dp),
                        height_class: WindowHeightClass::from_height_dp(780.0),
                        safe_area_top: 0.0,
                        safe_area_leading: 0.0,
                        safe_area_trailing: 0.0,
                    },
                },
                &mut bytes,
            )
            .expect("the resize did not encode");
            let (batch, _) = host.dispatch_event(&bytes).expect("the resize failed");
            let found = decode_batch(batch)
                .expect("the batch did not decode")
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        modifier: Wire::Width(dp),
                        ..
                    } => Some(*dp),
                    _ => None,
                })
                .collect();
            compose_rust::window::reset_window_size();
            found
        }

        let wide = widths(1180.0);
        assert!(
            wide.iter().filter(|dp| **dp == PAGE_MEASURE).count() >= 2,
            "the page and the bar should both stop at a phone's width: {wide:?}"
        );
        assert!(!widths(420.0).contains(&PAGE_MEASURE));
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
