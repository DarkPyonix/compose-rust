//! A component sheet: the monochrome set of iOS components the reference draws, on one
//! white page.
//!
//! The other samples adapt: they take whichever design system the platform picks. This one
//! is unified. The reference is one picture of one design (black, white, a few greys, a red
//! delete and a green switch), so the sheet draws that picture everywhere rather than the
//! platform's version of it. `THEME` names the design system and the colour scheme, and
//! `palette` names the colours, because no design system's palette has a sheet with no
//! accent in it.
//!
//! Everything on the sheet is something on the reference: the two discs and the title, the
//! steppers, the segmented control, the panel with its delete action, the chips with a
//! coin, the slider card, the card of button tiles, the inverted bar, the switch pair and
//! the three tiles. Nothing on it is a picture of the design system.

mod marks;
mod palette;

use compose_rust::runtime::*;
use compose_rust::ui::*;
use compose_rust::{FontRef, FontRefs, GenericFamily};

/// How wide the page may grow past a phone, in dp. A phone sheet in a desktop window is
/// still a phone sheet.
const PAGE_MEASURE: f32 = 420.0;

/// The side of each hero disc, and how far the shaded one sits to the right of the solid
/// one. About half, which is how much of the solid disc the reference leaves showing.
///
/// Numbers, because these are the proportions of a picture and the space ladder answers
/// how far apart two things sit rather than how large a picture is. The same holds for
/// every size below.
const HERO_DISC: f32 = 116.0;
const HERO_OVERLAP: f32 = 60.0;

/// The round buttons: a stepper's minus and plus and the bar's back button.
const ROUND: f32 = 36.0;

/// The corner of every card on the sheet, and of the smaller tiles and chips.
const CARD_CORNER: f32 = 18.0;
const TILE_CORNER: f32 = 14.0;

/// How tall a button tile and a chip are. Their width is a share of the row.
const TILE_HEIGHT: f32 = 96.0;

/// The red square beside the panel.
const DELETE_WIDTH: f32 = 64.0;

/// The small glyph on a button tile, and the coin on a chip.
const GLYPH: f32 = 14.0;
const COIN: f32 = 28.0;

/// The grabber at the top of a card.
const GRABBER_WIDTH: f32 = 28.0;
const GRABBER_HEIGHT: f32 = 4.0;

/// The search field on the inverted bar: a pill.
const FIELD_HEIGHT: f32 = 40.0;

/// The two serif letters at the ends of the slider: small on the left, large on the right.
const SMALL_LETTER: f32 = 14.0;
const LARGE_LETTER: f32 = 24.0;

/// The three segments of the segmented control.
const SEGMENTS: [&str; 3] = ["First", "Second", "Third"];

/// The weight the sheet sets its headings in. The reference's titles are heavy and its
/// stepper numeral is thin, and the design system's ladder has neither.
const BOLD: u16 = 700;
const THIN: u16 = 200;

/// The state of everything a person can operate on the sheet.
#[derive(Clone, Copy)]
struct Sheet {
    amount: MutableState<i32>,
    segment: MutableState<usize>,
    reading: MutableState<f32>,
    first_switch: MutableState<bool>,
    second_switch: MutableState<bool>,
}

/// A press that changes the amount every stepper shows.
fn step(amount: MutableState<i32>, by: i32) -> impl FnMut() + 'static {
    move || amount.set((amount.get_untracked() + by).clamp(0, 99))
}

/// One of a stepper's round buttons: a disc of `fill` with `glyph` on it in `ink`.
fn round_step(glyph: &str, name: &str, fill: Paint, ink: Paint, on_click: impl FnMut() + 'static) {
    Button(glyph)
        .accessible_name(name)
        .variant(ButtonVariant::Text)
        .color(ink)
        .modifier(
            Modifier
                .size(ROUND, ROUND)
                .background(fill)
                .corner_radius(ROUND / 2.0),
        )
        .on_click(on_click);
}

/// A minus and a plus, side by side.
fn step_buttons(amount: MutableState<i32>, fill: Paint, ink: Paint) {
    Row()
        .space_role(SpaceRole::Sm)
        .alignment(Alignment::Center)
        .content(|| {
            round_step("\u{2212}", "Less", fill, ink, step(amount, -1));
            round_step("+", "More", fill, ink, step(amount, 1));
        });
}

/// The upright stepper: "Amount" in bold, the number large and thin, and the two round
/// buttons under it. `card` is the fill and `ink` what is read on it; the round buttons
/// are the other way round, which is how the reference draws both the light and the dark
/// one.
fn upright_stepper(amount: MutableState<i32>, card: Paint, ink: Paint) {
    let shown = amount.get();
    Column()
        .modifier(
            Modifier
                .weight(1.0)
                .background(card)
                .corner_radius(CARD_CORNER)
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Sm)
        .alignment(Alignment::Center)
        .content(|| {
            Text("Amount")
                .type_role(TypeRole::BodyStrong)
                .font_weight(BOLD)
                .color(ink);
            Text(format!("{shown}"))
                .type_role(TypeRole::Display)
                .font_weight(THIN)
                .color(ink);
            step_buttons(amount, ink, card);
        });
}

/// The stepper on its side: "Amount" over the number on the left, the round buttons on
/// the right.
fn wide_stepper(amount: MutableState<i32>) {
    let shown = amount.get();
    Row()
        .modifier(
            Modifier
                .fill_max_width()
                .background(palette::TILE)
                .corner_radius(CARD_CORNER)
                .padding_role(SpaceRole::Md),
        )
        .arrangement(Arrangement::SpaceBetween)
        .alignment(Alignment::CenterStart)
        .content(|| {
            Column().content(|| {
                Text("Amount")
                    .type_role(TypeRole::BodyStrong)
                    .font_weight(BOLD)
                    .color(palette::INK);
                Text(format!("{shown}"))
                    .type_role(TypeRole::Body)
                    .color(palette::INK);
            });
            step_buttons(amount, palette::INK, palette::PAGE);
        });
}

/// The segmented control: a grey track, and the segment that is chosen as a black pill
/// with its label in white bold.
///
/// Drawn rather than taken from `Tabs`, because the design system draws the chosen
/// segment of a `Tabs` its own way (a white segment, under Cupertino) and the reference's
/// is black.
fn segmented(segment: MutableState<usize>) {
    let chosen = segment.get();
    Row()
        .modifier(
            Modifier
                .fill_max_width()
                .background(palette::TRACK)
                .corner_radius(TILE_CORNER / 2.0)
                .padding(2.0),
        )
        .content(|| {
            for (index, label) in SEGMENTS.into_iter().enumerate() {
                let picked = index == chosen;
                key(label, || {
                    Box()
                        .modifier(
                            Modifier
                                .weight(1.0)
                                .background_if(picked.then_some(palette::INK))
                                .corner_radius(TILE_CORNER / 2.0)
                                .padding_role(SpaceRole::Xs),
                        )
                        .alignment(Alignment::Center)
                        .content(|| {
                            Text(label)
                                .type_role(TypeRole::Label)
                                .font_weight(if picked { BOLD } else { 400 })
                                .color(if picked { palette::PAGE } else { palette::INK });
                            Button("")
                                .accessible_name(label)
                                .variant(ButtonVariant::Text)
                                .modifier(Modifier.match_parent_size().selected(picked))
                                .on_click(move || segment.set(index));
                        });
                });
            }
        });
}

/// The light panel with a title, a subtitle and a paragraph, and the red delete action
/// beside it.
fn panel_and_delete() {
    Row()
        .modifier(Modifier.fill_max_width())
        .equal_height(true)
        .space_role(SpaceRole::Sm)
        .content(|| {
            Column()
                .modifier(
                    Modifier
                        .weight(1.0)
                        .background(palette::TILE)
                        .corner_radius(CARD_CORNER)
                        .padding_role(SpaceRole::Md),
                )
                .space_role(SpaceRole::Xs)
                .content(|| {
                    Text("Title")
                        .type_role(TypeRole::BodyStrong)
                        .font_weight(BOLD)
                        .color(palette::INK);
                    Text("Subtitle")
                        .type_role(TypeRole::Body)
                        .color(palette::INK);
                    Text("A short paragraph that says what this row is about.")
                        .type_role(TypeRole::Caption)
                        .color(palette::MUTED);
                });
            // A word rather than the reference's bin, because no icon role means
            // deleting yet (#593).
            Box()
                .modifier(
                    Modifier
                        .width(DELETE_WIDTH)
                        .fill_max_height()
                        .background(palette::DELETE)
                        .corner_radius(CARD_CORNER),
                )
                .alignment(Alignment::Center)
                .content(|| {
                    Text("Delete")
                        .type_role(TypeRole::Label)
                        .font_weight(BOLD)
                        .color(palette::PAGE);
                    Button("")
                        .accessible_name("Delete")
                        .variant(ButtonVariant::Text)
                        .modifier(Modifier.match_parent_size())
                        .on_click(|| {});
                });
        });
}

/// A small pressable card whose face is laid out by `face`, with the press over all of it.
fn pressable(name: &str, card: Paint, modifier: Modifier, face: impl FnOnce()) {
    Box()
        .modifier(
            modifier
                .weight(1.0)
                .height(TILE_HEIGHT)
                .background(card)
                .corner_radius(TILE_CORNER),
        )
        .content(|| {
            Column()
                .modifier(Modifier.fill_max_size().padding_role(SpaceRole::Sm))
                .arrangement(Arrangement::SpaceBetween)
                .content(face);
            Button("")
                .accessible_name(name)
                .variant(ButtonVariant::Text)
                .modifier(Modifier.match_parent_size())
                .on_click(|| {});
        });
}

/// The "Button / Description" chips: the coin, a bold name and a quiet line, once on the
/// light card and once on the dark one.
fn chips() {
    Row()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Sm)
        .content(|| {
            for (chip, card, ink, quiet) in [
                ("light", palette::TILE, palette::INK, palette::MUTED),
                ("dark", palette::DARK, palette::PAGE, palette::TRACK),
            ] {
                key(chip, || {
                    pressable("Button", card, Modifier, || {
                        Canvas(marks::coin(COIN)).modifier(Modifier.size(COIN, COIN));
                        Column().content(|| {
                            Text("Button")
                                .type_role(TypeRole::BodyStrong)
                                .font_weight(BOLD)
                                .color(ink);
                            Text("Description")
                                .type_role(TypeRole::Caption)
                                .color(quiet);
                        });
                    });
                });
            }
        });
}

/// The short bar at the top of a card that says it can be pulled.
fn grabber(paint: Paint) {
    Box()
        .modifier(Modifier.fill_max_width())
        .alignment(Alignment::Center)
        .content(|| {
            Box().modifier(
                Modifier
                    .size(GRABBER_WIDTH, GRABBER_HEIGHT)
                    .background(paint)
                    .corner_radius(GRABBER_HEIGHT / 2.0),
            );
        });
}

/// A letter in the platform's serif, which is how the reference marks the two ends of
/// its reading-size slider.
fn serif_letter(size: f32) {
    Text("A")
        .font(FontRefs::new([FontRef::Generic(GenericFamily::Serif)]))
        .font_size(size)
        .color(palette::PAGE);
}

/// The mid grey card: a grabber, "Card" in white bold, and a white slider between a small
/// and a large serif "A".
fn slider_card(reading: MutableState<f32>) {
    let value = reading.get();
    Column()
        .modifier(
            Modifier
                .fill_max_width()
                .background(palette::SLATE)
                .corner_radius(CARD_CORNER)
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Sm)
        .content(|| {
            grabber(palette::SLATE_LINE);
            Text("Card")
                .type_role(TypeRole::Title)
                .font_weight(BOLD)
                .color(palette::PAGE);
            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    serif_letter(SMALL_LETTER);
                    Slider(value)
                        .accessible_name("Reading size")
                        .modifier(Modifier.weight(1.0))
                        .active_track_color(palette::PAGE)
                        .thumb_color(palette::PAGE)
                        .track_color(palette::SLATE_LINE)
                        .on_change(move |next| reading.set(next));
                    serif_letter(LARGE_LETTER);
                });
        });
}

/// A button tile: the small glyph in its corner and "Button" in bold at its foot.
///
/// `glyph` and `cut` are the glyph's sheet and the marks on it, which swap on a black tile
/// so the glyph still reads.
fn button_tile(card: Paint, ink: Paint, glyph: Paint, cut: Paint, lifted: bool) {
    let modifier = if lifted {
        Modifier.shadow(Shadow::new(0.0, 6.0, 18.0, 0.0, palette::SHADOW))
    } else {
        Modifier
    };
    pressable("Button", card, modifier, || {
        Canvas(marks::tile_glyph(GLYPH, glyph, cut)).modifier(Modifier.size(GLYPH, GLYPH * 1.3));
        Text("Button")
            .type_role(TypeRole::BodyStrong)
            .font_weight(BOLD)
            .color(ink);
    });
}

/// The large light card: a grabber, "Card", two white button tiles and a caption under
/// them.
fn tile_card() {
    Column()
        .modifier(
            Modifier
                .fill_max_width()
                .shadow(Shadow::new(0.0, 10.0, 30.0, 0.0, palette::SHADOW))
                .background(palette::TILE)
                .corner_radius(CARD_CORNER)
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Md)
        .content(|| {
            grabber(palette::TRACK);
            Text("Card")
                .type_role(TypeRole::Headline)
                .font_weight(BOLD)
                .color(palette::INK);
            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Md)
                .content(|| {
                    for tile in 0..2 {
                        key(tile, || {
                            button_tile(
                                palette::PAGE,
                                palette::INK,
                                palette::INK,
                                palette::PAGE,
                                false,
                            );
                        });
                    }
                });
            Text("Brief description or small text about the action of the buttons")
                .type_role(TypeRole::Caption)
                .color(palette::MUTED)
                .text_align(TextAlign::Center)
                .modifier(Modifier.fill_max_width());
        });
}

/// The inverted bar: a white round back button, "Title" in white bold at the far end, and
/// a dark grey pill to search in.
fn inverted_bar() {
    Column()
        .modifier(
            Modifier
                .fill_max_width()
                .background(palette::INK)
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Md)
        .content(|| {
            Row()
                .modifier(Modifier.fill_max_width())
                .arrangement(Arrangement::SpaceBetween)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Button("")
                        .icon(IconRole::Back)
                        .accessible_name("Back")
                        .variant(ButtonVariant::Text)
                        .color(palette::INK)
                        .modifier(
                            Modifier
                                .size(ROUND, ROUND)
                                .background(palette::PAGE)
                                .corner_radius(ROUND / 2.0),
                        )
                        .on_click(|| {});
                    Text("Title")
                        .type_role(TypeRole::Headline)
                        .font_weight(BOLD)
                        .color(palette::PAGE);
                });
            // The placeholder and the caret are still the design system's, because a
            // field cannot name either colour yet (#592).
            TextField()
                .placeholder("Search")
                .color(palette::PAGE)
                .modifier(
                    Modifier
                        .fill_max_width()
                        .height(FIELD_HEIGHT)
                        .background(palette::DARK)
                        .corner_radius(FIELD_HEIGHT / 2.0),
                );
        });
}

/// A switch in the reference's colours: green when on, light grey when off, with a white
/// thumb either way. The Host owns the state, so it hands over the track that goes with it.
fn switch(name: &str, on: MutableState<bool>) {
    let checked = on.get();
    Switch(checked)
        .accessible_name(name)
        .track_color(if checked {
            palette::SWITCH
        } else {
            palette::TRACK
        })
        .thumb_color(palette::PAGE)
        .on_change(move |value| on.set(value));
}

/// The switch pair beside the three button tiles: white, black and dark grey.
fn switches_and_tiles(sheet: Sheet) {
    Row()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Sm)
        .alignment(Alignment::Center)
        .content(|| {
            Column().space_role(SpaceRole::Sm).content(|| {
                switch("First switch", sheet.first_switch);
                switch("Second switch", sheet.second_switch);
            });
            button_tile(
                palette::PAGE,
                palette::INK,
                palette::INK,
                palette::PAGE,
                true,
            );
            button_tile(
                palette::INK,
                palette::PAGE,
                palette::PAGE,
                palette::INK,
                false,
            );
            button_tile(
                palette::DARK,
                palette::PAGE,
                palette::PAGE,
                palette::DARK,
                false,
            );
        });
}

/// The two discs, the title and the line under it.
fn hero() {
    Column()
        .modifier(Modifier.fill_max_width())
        .alignment(Alignment::Center)
        .space_role(SpaceRole::Sm)
        .content(|| {
            Box()
                .modifier(Modifier.size(HERO_DISC + HERO_OVERLAP, HERO_DISC))
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Box().modifier(
                        Modifier
                            .size(HERO_DISC, HERO_DISC)
                            .background(palette::INK)
                            .corner_radius(HERO_DISC / 2.0),
                    );
                    // Drawn second, so it lies over the solid disc as it does on the
                    // reference, and lifted by a soft shadow.
                    Box().modifier(
                        Modifier
                            .size(HERO_DISC, HERO_DISC)
                            .offset(HERO_OVERLAP, 0.0)
                            .shadow(Shadow::new(0.0, 16.0, 32.0, 0.0, palette::SHADOW))
                            .background(marks::hero_fade())
                            .corner_radius(HERO_DISC / 2.0),
                    );
                });
            Text("Every component")
                .type_role(TypeRole::Display)
                .font_weight(BOLD)
                .color(palette::INK)
                .text_align(TextAlign::Center);
            Text("Including dark theme")
                .type_role(TypeRole::Body)
                .color(palette::MUTED)
                .text_align(TextAlign::Center);
        });
}

/// The component sheet.
#[Composable]
pub fn app() {
    let window = current_window_size();
    let measure = if window.is_compact() {
        None
    } else {
        Some(PAGE_MEASURE)
    };

    let sheet = remember(|| Sheet {
        amount: mutable_state_of(2),
        segment: mutable_state_of(0_usize),
        reading: mutable_state_of(0.55_f32),
        first_switch: mutable_state_of(true),
        second_switch: mutable_state_of(false),
    });

    Scaffold()
        .modifier(Modifier.background(palette::PAGE))
        .content(|| {
            Box()
                .modifier(Modifier.fill_max_width().weight(1.0))
                .alignment(Alignment::TopCenter)
                .content(|| {
                    ScrollColumn()
                        .modifier(
                            Modifier
                                .width_if(measure)
                                .fill_max_width_if(measure.is_none())
                                .fill_max_height(),
                        )
                        .content(|| {
                            Column()
                                .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
                                .space_role(SpaceRole::Lg)
                                .content(|| {
                                    hero();
                                    segmented(sheet.segment);
                                    Row()
                                        .modifier(Modifier.fill_max_width())
                                        .space_role(SpaceRole::Sm)
                                        .content(|| {
                                            upright_stepper(
                                                sheet.amount,
                                                palette::TILE,
                                                palette::INK,
                                            );
                                            upright_stepper(
                                                sheet.amount,
                                                palette::DARK,
                                                palette::PAGE,
                                            );
                                        });
                                    wide_stepper(sheet.amount);
                                    panel_and_delete();
                                    chips();
                                    slider_card(sheet.reading);
                                    tile_card();
                                    inverted_bar();
                                    switches_and_tiles(sheet);
                                });
                        });
                });
        });
}

/// The design this sample draws: one design system everywhere, light.
///
/// The scheme is said out loud rather than left to follow the machine: `Theme::unified`
/// settles which design system is drawn and nothing else. `demo_theme_for` still lets
/// `DXC_DESIGN` and `DXC_SCHEME` override the half they name.
const THEME: Theme = Theme::unified(DesignSystem::Cupertino).with_color_scheme(ColorScheme::Light);

/// Runs the sample as a program of its own.
pub fn launch() {
    launch_builder().application(app);
}

/// How this sample is configured, in one place because three entry points need it.
fn launch_builder() -> LaunchBuilder {
    LaunchBuilder::new()
        .with_theme(compose_rust::demo_theme_for(THEME))
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Minimal")
                .with_icon(compose_rust::asset::asset(
                    compose_rust::schema::AssetKind::Png,
                    include_bytes!("../assets/icon.png"),
                )),
        )
}

compose_rust::android_application!({ launch_builder() }, app);
compose_rust::web_application!({ launch_builder() }, app);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;

    use compose_rust::ComposeHost;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
    use compose_rust::schema::{EventPayload, Modifier as Wire, PropertyKind, WidgetKind};

    /// The screen, driven the way a Renderer drives it.
    ///
    /// Every batch is kept, because a batch is the change since the frame before it rather
    /// than what is on screen.
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

        fn live(&self) -> Vec<Mutation<'_>> {
            sample_frames::live(self.mutations())
        }

        /// The text every live node shows now: the last one each was given.
        fn texts(&self) -> Vec<String> {
            let mut shown: Vec<(u32, String)> = Vec::new();
            for mutation in self.live() {
                if let Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Text,
                    value: PropertyValue::String(text),
                } = mutation
                {
                    shown.retain(|(node, _)| *node != node_id);
                    shown.push((node_id, text.to_owned()));
                }
            }
            shown.into_iter().map(|(_, text)| text).collect()
        }

        /// The live node whose accessible name is `name`.
        fn named(&self, name: &str) -> Option<u32> {
            self.live()
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

        /// Presses the first live control named `name`.
        fn press(&mut self, name: &str) -> bool {
            let Some(node_id) = self.named(name) else {
                return false;
            };
            let Some(handler_id) = self
                .live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id: owner,
                        property: PropertyKind::OnClick,
                        value: PropertyValue::Integer(handler),
                    } if *owner == node_id => Some(*handler as u64),
                    _ => None,
                })
            else {
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

        /// Where a live node was last placed: its parent and its place among the
        /// parent's children.
        fn placement(&self, node: u32) -> Option<(u32, u32)> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::Insert {
                        parent_id,
                        node_id,
                        index,
                    }
                    | Mutation::Move {
                        parent_id,
                        node_id,
                        index,
                    } if *node_id == node => Some((*parent_id, *index)),
                    _ => None,
                })
        }

        /// What a live node was last placed in.
        fn parent_of(&self, node: u32) -> Option<u32> {
            self.placement(node).map(|(parent, _)| parent)
        }

        /// The fill a live node carries now, if it has one.
        ///
        /// A node's modifiers sit in numbered slots, and a fill that goes away is its slot
        /// set to nothing rather than a record of its own, so only the last value of each
        /// slot counts.
        fn fill_of(&self, node: u32) -> Option<Paint> {
            let mut slots: Vec<(u16, Wire)> = Vec::new();
            for mutation in self.live() {
                if let Mutation::SetModifier {
                    node_id,
                    index,
                    modifier,
                } = mutation
                {
                    if node_id == node {
                        slots.retain(|(slot, _)| *slot != index);
                        slots.push((index, modifier));
                    }
                }
            }
            slots.into_iter().find_map(|(_, modifier)| match modifier {
                Wire::Background(paint) => Some(paint),
                _ => None,
            })
        }

        /// The fill behind the control named `name`: the card it was placed on.
        fn fill_behind(&self, name: &str) -> Option<Paint> {
            let control = self.named(name)?;
            self.fill_of(self.parent_of(control)?)
        }

        /// Every paint the screen has sent: fills, borders, shadows, the colours of text
        /// and buttons, and the colours of the switches and the slider.
        fn every_paint(&self) -> Vec<(u32, Paint)> {
            self.mutations()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        node_id,
                        modifier:
                            Wire::Background(paint)
                            | Wire::Border { paint, .. }
                            | Wire::Shadow { paint, .. },
                        ..
                    } => Some((*node_id, *paint)),
                    Mutation::SetProp {
                        node_id,
                        property:
                            PropertyKind::Color
                            | PropertyKind::TrackColor
                            | PropertyKind::ThumbColor
                            | PropertyKind::ActiveTrackColor,
                        value: PropertyValue::Integer(bits),
                    } => Paint::from_bits(*bits as u64).map(|paint| (*node_id, paint)),
                    _ => None,
                })
                .collect()
        }

        /// The track colours the switches were last given, in the order they are drawn.
        fn switch_tracks(&self) -> Vec<Paint> {
            let live = self.live();
            let switches: Vec<u32> = live
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::Create {
                        node_id,
                        widget: WidgetKind::Switch,
                    } => Some(*node_id),
                    _ => None,
                })
                .collect();
            switches
                .into_iter()
                .filter_map(|switch| {
                    live.iter().rev().find_map(|mutation| match mutation {
                        Mutation::SetProp {
                            node_id,
                            property: PropertyKind::TrackColor,
                            value: PropertyValue::Integer(bits),
                        } if *node_id == switch => Paint::from_bits(*bits as u64),
                        _ => None,
                    })
                })
                .collect()
        }
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

    /// A property the schema does not have fails the whole batch rather than just itself,
    /// so a screen that builds in Rust can still be blank on screen.
    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        assert!(ComposeHost::new(app).rebuild().is_ok());
    }

    /// Nothing on the sheet is painted by the design system.
    ///
    /// Named for what it defends: the sheet was drawn with roles once, so its segmented
    /// control, buttons, toggles and progress came out in the running system's blue and
    /// its page in that system's grey. The reference is black, white and grey, with one
    /// red and one green.
    #[test]
    fn fr22_nothing_on_the_sheet_is_painted_by_a_role() {
        let screen = Screen::new();
        let paints = screen.every_paint();
        assert!(!paints.is_empty(), "the sheet sent no paint at all");
        for (node, paint) in paints {
            assert!(
                !matches!(paint, Paint::Role(_)),
                "node {node} is painted {paint:?}, which the design system picks"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// No card or panel on the sheet is one of the accent containers, and the group of
    /// design-system swatches that used to show them is gone. The reference has neither.
    #[test]
    fn fr22_no_panel_is_an_accent_container_and_there_are_no_swatches() {
        let screen = Screen::new();
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
        let texts = screen.texts();
        for swatch in [
            "Primary container",
            "Secondary container",
            "Tertiary container",
            "Colour",
        ] {
            assert!(
                !texts.iter().any(|text| text == swatch),
                "the sheet still shows {swatch}, which is the design system's rather than the \
                 reference's"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// The chosen segment is the black pill, and choosing another moves the pill there.
    #[test]
    fn fr22_the_chosen_segment_is_a_black_pill() {
        let mut screen = Screen::new();
        assert_eq!(screen.fill_behind("First"), Some(palette::INK));
        assert_eq!(screen.fill_behind("Second"), None);
        assert!(
            screen.press("Second"),
            "the segmented control has no Second"
        );
        assert_eq!(screen.fill_behind("Second"), Some(palette::INK));
        assert_eq!(screen.fill_behind("First"), None);
        compose_rust::window::reset_window_size();
    }

    /// The steppers count, and every one of them shows the same amount.
    #[test]
    fn fr22_the_steppers_count() {
        let mut screen = Screen::new();
        assert!(screen.texts().iter().any(|text| text == "2"));
        assert!(screen.press("More"), "the stepper has no plus");
        let texts = screen.texts();
        assert_eq!(
            texts.iter().filter(|text| *text == "3").count(),
            3,
            "the three steppers do not all show the new amount: {texts:?}"
        );
        assert!(screen.press("Less"), "the stepper has no minus");
        assert!(screen.texts().iter().any(|text| text == "2"));
        compose_rust::window::reset_window_size();
    }

    /// The switch that is on is the reference's green and the one that is off is its
    /// light grey. A switch left to the design system is that system's accent.
    #[test]
    fn fr22_the_switch_pair_is_green_and_grey() {
        let screen = Screen::new();
        assert_eq!(
            screen.switch_tracks(),
            vec![palette::SWITCH, palette::TRACK],
            "the switches are not drawn on and off in the reference's colours"
        );
        compose_rust::window::reset_window_size();
    }

    /// The delete action is a red square of its own, not red words on the page.
    #[test]
    fn fr22_the_delete_action_is_the_reference_red() {
        let screen = Screen::new();
        assert_eq!(screen.fill_behind("Delete"), Some(palette::DELETE));
        compose_rust::window::reset_window_size();
    }

    /// The hero's second disc is the shaded one, lies over the solid disc, and casts the
    /// shadow. It used to be a flat grey disc under a black one, with no shadow at all.
    #[test]
    fn fr22_the_shaded_disc_lies_over_the_solid_one() {
        let screen = Screen::new();
        let mutations = screen.mutations();
        let filled_with = |paint: Paint| -> Vec<u32> {
            mutations
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        node_id,
                        modifier: Wire::Background(found),
                        ..
                    } if *found == paint => Some(*node_id),
                    _ => None,
                })
                .collect()
        };
        let shaded = *filled_with(marks::hero_fade())
            .first()
            .expect("no disc is filled with the shading");
        let (hero, shaded_at) = screen
            .placement(shaded)
            .expect("the shaded disc is not placed anywhere");
        let solid_at = filled_with(palette::INK)
            .into_iter()
            .find_map(|node| match screen.placement(node) {
                Some((parent, index)) if parent == hero => Some(index),
                _ => None,
            })
            .expect("no solid black disc stands beside the shaded one");
        assert!(
            solid_at < shaded_at,
            "the solid disc comes after the shaded one, so it is drawn over it"
        );
        assert!(
            mutations.iter().any(|mutation| matches!(
                mutation,
                Mutation::SetModifier {
                    node_id,
                    modifier: Wire::Shadow { .. },
                    ..
                } if *node_id == shaded
            )),
            "the shaded disc casts no shadow"
        );
        compose_rust::window::reset_window_size();
    }

    /// The coin and the tile glyphs are drawings, and a drawing that does not reach the
    /// Renderer is a blank square nobody notices until the picture comes back.
    #[test]
    fn fr16_every_drawing_is_a_canvas_with_commands_on_it() {
        let screen = Screen::new();
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
        // Two coins, two tiles on the card and three beside the switches.
        assert_eq!(
            canvases.len(),
            7,
            "the sheet does not draw every coin and glyph"
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
                "canvas {canvas} carries no draw list, so it is an empty square"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// A phone design does not become a desktop design by being put in a wider window.
    ///
    /// Narrow, the page fills whatever it is given. Wide, it takes a width of its own and
    /// the window shows the page on either side. Without this the reference's phone
    /// layout would quietly become a 1200dp column of stretched cards on a desktop, which
    /// is a different design rather than the same one.
    #[test]
    fn fr20_the_page_stops_widening_past_a_phone() {
        fn widths_at(width_dp: f32) -> Vec<f32> {
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

        assert!(
            widths_at(1180.0).contains(&PAGE_MEASURE),
            "a desktop window does not hold the page to a phone's measure"
        );
        assert!(
            !widths_at(420.0).contains(&PAGE_MEASURE),
            "a phone window pins the page to a measure instead of filling"
        );
    }

    /// The sheet, in the one design system it ships and the other Apple one, in its own
    /// scheme, at all three widths.
    #[test]
    fn fr14_the_sheet_is_recorded_in_the_system_it_ships() {
        sample_frames::record_as(
            "Minimal",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |_| {},
        );
    }

    /// The sheet with the second segment chosen, so the picture shows the pill somewhere
    /// other than where it starts.
    #[test]
    fn fr14_the_sheet_is_recorded_after_it_is_operated() {
        sample_frames::record_as(
            "MinimalOperated",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    screen.press_beside("Second"),
                    "the segmented control has no Second"
                );
            },
        );
    }
}
