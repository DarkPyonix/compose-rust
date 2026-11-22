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

/// How wide the vertical column may grow on medium windows, in dp. Expanded windows switch
/// to the multi-column showcase spread instead of holding a single column.
const PAGE_MEASURE: f32 = 420.0;

/// Width of the segmented control, matching the compact pill on the reference sheet.
const SEGMENTED_WIDTH: f32 = 216.0;

/// Width of each upright amount stepper card.
const UPRIGHT_STEPPER_WIDTH: f32 = 140.0;

/// Vertical drop applied to the dark upright stepper on the expanded sheet so it sits
/// lower-right beside the light one.
const DARK_STEPPER_DROP: f32 = 32.0;

/// Side of each square "Button / Description" chip tile.
const CHIP_SIZE: f32 = 98.0;

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
    Button!(
        glyph,
        on_click,
        accessible_name = name,
        variant = ButtonVariant::Text,
        color = ink,
        modifier = Modifier
            .size(ROUND, ROUND)
            .background(fill)
            .corner_radius(ROUND / 2.0)
    );
}

/// A minus and a plus, side by side.
fn step_buttons(amount: MutableState<i32>, fill: Paint, ink: Paint) {
    Row!(space_role = SpaceRole::Sm, alignment = Alignment::Center, {
        round_step("\u{2212}", "Less", fill, ink, step(amount, -1));
        round_step("+", "More", fill, ink, step(amount, 1));
    });
}

/// Soft drop shadow for cards (steppers, panel, slider, tile card).
const fn card_shadow() -> Shadow {
    Shadow::new(0.0, 10.0, 30.0, 0.0, palette::SHADOW)
}

/// Lighter drop shadow for small tiles and chips.
const fn tile_shadow() -> Shadow {
    Shadow::new(0.0, 6.0, 18.0, 0.0, palette::SHADOW)
}

/// The upright stepper: "Amount" in bold, the number large and thin, and the two round
/// buttons under it. `card` is the fill and `ink` what is read on it; the round buttons
/// are the other way round, which is how the reference draws both the light and the dark
/// one.
fn upright_stepper(amount: MutableState<i32>, card: Paint, ink: Paint) {
    let shown = amount.get();
    Column!(
        modifier = Modifier
            .width(UPRIGHT_STEPPER_WIDTH)
            .shadow(card_shadow())
            .background(card)
            .corner_radius(CARD_CORNER)
            .padding_role(SpaceRole::Md),
        space_role = SpaceRole::Sm,
        alignment = Alignment::Center,
        {
            Text!(
                "Amount",
                type_role = TypeRole::BodyStrong,
                font_weight = BOLD,
                color = ink
            );
            Text!(
                format!("{shown}"),
                type_role = TypeRole::Display,
                font_weight = THIN,
                color = ink
            );
            step_buttons(amount, ink, card);
        }
    );
}

/// The light and dark upright steppers side by side. On the expanded sheet the dark card
/// drops lower-right to match the reference composition.
fn upright_steppers(amount: MutableState<i32>, staggered: bool) {
    Row!(
        modifier = Modifier.fill_max_width(),
        arrangement = Arrangement::Center,
        alignment = Alignment::TopStart,
        space_role = SpaceRole::Md,
        {
            upright_stepper(amount, palette::TILE, palette::INK);
            if staggered {
                Box!(
                    modifier = Modifier.padding_each(0.0, DARK_STEPPER_DROP, 0.0, 0.0),
                    {
                        upright_stepper(amount, palette::DARK, palette::PAGE);
                    }
                );
            } else {
                upright_stepper(amount, palette::DARK, palette::PAGE);
            }
        }
    );
}

/// The stepper on its side: "Amount" over the number on the left, the round buttons on
/// the right.
fn wide_stepper(amount: MutableState<i32>) {
    let shown = amount.get();
    Row!(
        modifier = Modifier
            .fill_max_width()
            .shadow(card_shadow())
            .background(palette::TILE)
            .corner_radius(CARD_CORNER)
            .padding_role(SpaceRole::Md),
        arrangement = Arrangement::SpaceBetween,
        alignment = Alignment::CenterStart,
        {
            Column!({
                Text!(
                    "Amount",
                    type_role = TypeRole::BodyStrong,
                    font_weight = BOLD,
                    color = palette::INK
                );
                Text!(
                    format!("{shown}"),
                    type_role = TypeRole::Body,
                    color = palette::INK
                );
            });
            step_buttons(amount, palette::INK, palette::PAGE);
        }
    );
}

/// The segmented control: a grey track, and the segment that is chosen as a black pill
/// with its label in white bold.
///
/// Drawn rather than taken from `Tabs`, because the design system draws the chosen
/// segment of a `Tabs` its own way (a white segment, under Cupertino) and the reference's
/// is black.
fn segmented(segment: MutableState<usize>) {
    let chosen = segment.get();
    Box!(
        modifier = Modifier.fill_max_width(),
        alignment = Alignment::Center,
        {
            Row!(
                modifier = Modifier
                    .width(SEGMENTED_WIDTH)
                    .background(palette::TRACK)
                    .corner_radius(TILE_CORNER / 2.0)
                    .padding(2.0),
                {
                    for (index, label) in SEGMENTS.into_iter().enumerate() {
                        let picked = index == chosen;
                        key(label, || {
                            Box!(
                                modifier = Modifier
                                    .weight(1.0)
                                    .background_if(picked.then_some(palette::INK))
                                    .corner_radius(TILE_CORNER / 2.0)
                                    .padding_role(SpaceRole::Xs),
                                alignment = Alignment::Center,
                                {
                                    Text!(
                                        label,
                                        type_role = TypeRole::Caption,
                                        font_weight = if picked { BOLD } else { 400 },
                                        color = if picked { palette::PAGE } else { palette::INK }
                                    );
                                    Button!(
                                        "",
                                        move || segment.set(index),
                                        accessible_name = label,
                                        variant = ButtonVariant::Text,
                                        modifier = Modifier.match_parent_size().selected(picked)
                                    );
                                }
                            );
                        });
                    }
                }
            );
        }
    );
}

/// The light panel with a title, a subtitle and a paragraph, and the red delete action
/// beside it.
fn panel_and_delete() {
    Row!(
        modifier = Modifier.fill_max_width(),
        intrinsic_size = true,
        space_role = SpaceRole::Sm,
        {
            Column!(
                modifier = Modifier
                    .weight(1.0)
                    .shadow(card_shadow())
                    .background(palette::TILE)
                    .corner_radius(CARD_CORNER)
                    .padding_role(SpaceRole::Md),
                space_role = SpaceRole::Xs,
                {
                    Text!(
                        "Title",
                        type_role = TypeRole::BodyStrong,
                        font_weight = BOLD,
                        color = palette::INK
                    );
                    Text!("Subtitle", type_role = TypeRole::Body, color = palette::INK);
                    Text!(
                        "A short paragraph that says what this row is about.",
                        type_role = TypeRole::Caption,
                        color = palette::MUTED
                    );
                }
            );
            // The reference's white bin on its red square.
            Box!(
                modifier = Modifier
                    .width(DELETE_WIDTH)
                    .fill_max_height()
                    .background(palette::DELETE)
                    .corner_radius(CARD_CORNER),
                alignment = Alignment::Center,
                {
                    Button!(
                        "",
                        || {},
                        icon = IconRole::Delete,
                        accessible_name = "Delete",
                        variant = ButtonVariant::Text,
                        color = palette::PAGE,
                        modifier = Modifier.match_parent_size()
                    );
                }
            );
        }
    );
}

/// A small pressable card whose face is laid out by `face`, with the press over all of it.
fn pressable(name: &str, card: Paint, modifier: Modifier, face: impl FnOnce()) {
    Box!(
        modifier = modifier.background(card).corner_radius(TILE_CORNER),
        {
            Column!(
                modifier = Modifier.fill_max_size().padding_role(SpaceRole::Sm),
                arrangement = Arrangement::SpaceBetween,
                content = face
            );
            Button!(
                "",
                || {},
                accessible_name = name,
                variant = ButtonVariant::Text,
                modifier = Modifier.match_parent_size()
            );
        }
    );
}

/// The "Button / Description" chips: the coin, a bold name and a quiet line, once on the
/// light card and once on the dark one.
fn chips() {
    Row!(
        modifier = Modifier.fill_max_width(),
        arrangement = Arrangement::Center,
        space_role = SpaceRole::Md,
        {
            for (chip, card, ink, quiet) in [
                ("light", palette::TILE, palette::INK, palette::MUTED),
                ("dark", palette::DARK, palette::PAGE, palette::TRACK),
            ] {
                key(chip, || {
                    pressable(
                        "Button",
                        card,
                        Modifier.size(CHIP_SIZE, CHIP_SIZE).shadow(tile_shadow()),
                        || {
                            Canvas!(marks::coin(COIN), modifier = Modifier.size(COIN, COIN));
                            Column!({
                                Text!(
                                    "Button",
                                    type_role = TypeRole::BodyStrong,
                                    font_weight = BOLD,
                                    color = ink
                                );
                                Text!("Description", type_role = TypeRole::Caption, color = quiet);
                            });
                        },
                    );
                });
            }
        }
    );
}

/// The short bar at the top of a card that says it can be pulled.
fn grabber(paint: Paint) {
    Box!(
        modifier = Modifier.fill_max_width(),
        alignment = Alignment::Center,
        {
            Box!(
                modifier = Modifier
                    .size(GRABBER_WIDTH, GRABBER_HEIGHT)
                    .background(paint)
                    .corner_radius(GRABBER_HEIGHT / 2.0)
            );
        }
    );
}

/// A letter in the platform's serif, which is how the reference marks the two ends of
/// its reading-size slider.
///
/// With no type role, because text with a role is set in the theme's font for it and the
/// serif would be ignored. Where the platform has no serif face, as a browser page that
/// ships only the renderer's fonts has none, the letter is set in the face the platform
/// falls back to, and nothing is reported: a generic family is the platform's answer.
fn serif_letter(size: f32) {
    Text!(
        "A",
        type_role = TypeRole::None,
        font = FontRefs::new([FontRef::Generic(GenericFamily::Serif)]),
        font_size = size,
        color = palette::PAGE
    );
}

/// The mid grey card: a grabber, "Card" in white bold, and a white slider between a small
/// and a large serif "A".
fn slider_card(reading: MutableState<f32>) {
    let value = reading.get();
    Column!(
        modifier = Modifier
            .fill_max_width()
            .shadow(card_shadow())
            .background(palette::SLATE)
            .corner_radius(CARD_CORNER)
            .padding_role(SpaceRole::Md),
        space_role = SpaceRole::Sm,
        {
            grabber(palette::SLATE_LINE);
            Text!(
                "Card",
                type_role = TypeRole::Title,
                font_weight = BOLD,
                color = palette::PAGE
            );
            Row!(
                modifier = Modifier.fill_max_width(),
                space_role = SpaceRole::Sm,
                alignment = Alignment::CenterStart,
                {
                    serif_letter(SMALL_LETTER);
                    Slider!(
                        value,
                        move |next| reading.set(next),
                        accessible_name = "Reading size",
                        modifier = Modifier.weight(1.0),
                        active_track_color = palette::PAGE,
                        thumb_color = palette::PAGE,
                        track_color = palette::SLATE_LINE
                    );
                    serif_letter(LARGE_LETTER);
                }
            );
        }
    );
}

/// A button tile: the small glyph in its corner and "Button" in bold at its foot.
///
/// `glyph` and `cut` are the glyph's sheet and the marks on it, which swap on a black tile
/// so the glyph still reads.
fn button_tile(card: Paint, ink: Paint, glyph: Paint, cut: Paint, lifted: bool) {
    let modifier = if lifted {
        Modifier
            .weight(1.0)
            .height(TILE_HEIGHT)
            .shadow(tile_shadow())
    } else {
        Modifier.weight(1.0).height(TILE_HEIGHT)
    };
    pressable("Button", card, modifier, || {
        Canvas!(
            marks::tile_glyph(GLYPH, glyph, cut),
            modifier = Modifier.size(GLYPH, GLYPH * 1.3)
        );
        Text!(
            "Button",
            type_role = TypeRole::BodyStrong,
            font_weight = BOLD,
            color = ink
        );
    });
}

/// The large light card: a grabber, "Card", two white button tiles and a caption under
/// them.
fn tile_card() {
    Column!(
        modifier = Modifier
            .fill_max_width()
            .shadow(card_shadow())
            .background(palette::TILE)
            .corner_radius(CARD_CORNER)
            .padding_role(SpaceRole::Md),
        space_role = SpaceRole::Md,
        {
            grabber(palette::TRACK);
            Text!(
                "Card",
                type_role = TypeRole::Headline,
                font_weight = BOLD,
                color = palette::INK
            );
            Row!(
                modifier = Modifier.fill_max_width(),
                space_role = SpaceRole::Md,
                {
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
                }
            );
            Text!(
                "Brief description or small text about the action of the buttons",
                type_role = TypeRole::Caption,
                color = palette::MUTED,
                text_align = TextAlign::Center,
                modifier = Modifier.fill_max_width()
            );
        }
    );
}

/// The inverted bar: a white round back button, "Title" in white bold at the far end, and
/// a dark grey pill to search in.
fn inverted_bar() {
    Column!(
        modifier = Modifier
            .fill_max_width()
            .shadow(card_shadow())
            .background(palette::INK)
            .corner_radius(CARD_CORNER)
            .padding_role(SpaceRole::Md),
        space_role = SpaceRole::Md,
        {
            Row!(
                modifier = Modifier.fill_max_width(),
                arrangement = Arrangement::SpaceBetween,
                alignment = Alignment::CenterStart,
                {
                    Button!(
                        "",
                        || {},
                        icon = IconRole::Back,
                        accessible_name = "Back",
                        variant = ButtonVariant::Text,
                        color = palette::INK,
                        modifier = Modifier
                            .size(ROUND, ROUND)
                            .background(palette::PAGE)
                            .corner_radius(ROUND / 2.0)
                    );
                    Text!(
                        "Title",
                        type_role = TypeRole::Headline,
                        font_weight = BOLD,
                        color = palette::PAGE
                    );
                }
            );
            // The placeholder and the caret are explicit paints matching the inverted bar.
            TextField!(
                "",
                |_| {},
                placeholder = "Search",
                color = palette::PAGE,
                placeholder_color = palette::MUTED,
                caret_color = palette::PAGE,
                modifier = Modifier
                    .fill_max_width()
                    .height(FIELD_HEIGHT)
                    .background(palette::DARK)
                    .corner_radius(FIELD_HEIGHT / 2.0)
            );
        }
    );
}

/// A switch in the reference's colours: green when on, light grey when off, with a white
/// thumb either way. The Host owns the state, so it hands over the track that goes with it.
fn switch(name: &str, on: MutableState<bool>) {
    let checked = on.get();
    Switch!(
        checked,
        move |value| on.set(value),
        accessible_name = name,
        track_color = if checked {
            palette::SWITCH
        } else {
            palette::TRACK
        },
        thumb_color = palette::PAGE
    );
}

/// The switch pair beside the three button tiles: white, black and dark grey.
fn switches_and_tiles(sheet: Sheet) {
    Row!(
        modifier = Modifier.fill_max_width(),
        space_role = SpaceRole::Sm,
        alignment = Alignment::Center,
        {
            Column!(space_role = SpaceRole::Sm, {
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
        }
    );
}

/// The two discs, the title and the line under it.
fn hero() {
    Column!(
        modifier = Modifier.fill_max_width(),
        alignment = Alignment::Center,
        space_role = SpaceRole::Sm,
        {
            Box!(
                modifier = Modifier.size(HERO_DISC + HERO_OVERLAP, HERO_DISC),
                alignment = Alignment::CenterStart,
                {
                    Box!(
                        modifier = Modifier
                            .size(HERO_DISC, HERO_DISC)
                            .background(palette::INK)
                            .corner_radius(HERO_DISC / 2.0)
                    );
                    // Drawn second, so it lies over the solid disc as it does on the
                    // reference, and lifted by a soft shadow.
                    Box!(
                        modifier = Modifier
                            .size(HERO_DISC, HERO_DISC)
                            .offset(HERO_OVERLAP, 0.0)
                            .shadow(Shadow::new(0.0, 16.0, 32.0, 0.0, palette::SHADOW))
                            .background(marks::hero_fade())
                            .corner_radius(HERO_DISC / 2.0)
                    );
                }
            );
            Text!(
                "80 UI Components",
                type_role = TypeRole::Display,
                font_weight = BOLD,
                color = palette::INK,
                text_align = TextAlign::Center
            );
            Text!(
                "Including Dark Theme",
                type_role = TypeRole::Body,
                color = palette::MUTED,
                text_align = TextAlign::Center
            );
        }
    );
}

/// The compact and medium vertical column layout.
fn narrow_sheet(sheet: Sheet, compact: bool) {
    let measure = if compact { None } else { Some(PAGE_MEASURE) };
    Box!(
        modifier = Modifier.fill_max_width().weight(1.0),
        alignment = Alignment::TopCenter,
        {
            ScrollColumn!(
                modifier = Modifier
                    .width_if(measure)
                    .fill_max_width_if(measure.is_none())
                    .fill_max_height(),
                {
                    Column!(
                        modifier = Modifier.fill_max_width().padding_role(SpaceRole::Md),
                        space_role = SpaceRole::Lg,
                        {
                            hero();
                            segmented(sheet.segment);
                            upright_steppers(sheet.amount, false);
                            wide_stepper(sheet.amount);
                            panel_and_delete();
                            chips();
                            slider_card(sheet.reading);
                            tile_card();
                            inverted_bar();
                            switches_and_tiles(sheet);
                        }
                    );
                }
            );
        }
    );
}

/// The expanded multi-column showcase spread (such as 1200x900), keeping the hero centered
/// and every component group visible above the fold across three balanced columns.
fn expanded_sheet(sheet: Sheet) {
    Box!(
        modifier = Modifier.fill_max_width().weight(1.0),
        alignment = Alignment::TopCenter,
        {
            ScrollColumn!(modifier = Modifier.fill_max_size(), {
                Row!(
                    modifier = Modifier.fill_max_width().padding_role(SpaceRole::Lg),
                    space_role = SpaceRole::Lg,
                    alignment = Alignment::TopStart,
                    {
                        // Left column: staggered upright steppers, panel+delete, slider card.
                        Column!(
                            modifier = Modifier.weight(1.0),
                            space_role = SpaceRole::Lg,
                            {
                                upright_steppers(sheet.amount, true);
                                panel_and_delete();
                                slider_card(sheet.reading);
                            }
                        );
                        // Center column: segmented control, hero, wide stepper, chips.
                        Column!(
                            modifier = Modifier.weight(1.0),
                            space_role = SpaceRole::Lg,
                            alignment = Alignment::Center,
                            {
                                segmented(sheet.segment);
                                hero();
                                wide_stepper(sheet.amount);
                                chips();
                            }
                        );
                        // Right column: inverted bar, tile card, switches and three tiles.
                        Column!(
                            modifier = Modifier.weight(1.0),
                            space_role = SpaceRole::Lg,
                            {
                                inverted_bar();
                                tile_card();
                                switches_and_tiles(sheet);
                            }
                        );
                    }
                );
            });
        }
    );
}

/// The component sheet.
#[Composable]
pub fn app() {
    let window = current_window_size();

    let sheet = remember(|| Sheet {
        amount: mutable_state_of(2),
        segment: mutable_state_of(0_usize),
        reading: mutable_state_of(0.55_f32),
        first_switch: mutable_state_of(true),
        second_switch: mutable_state_of(false),
    });

    Scaffold!(modifier = Modifier.background(palette::PAGE), {
        if window.is_expanded() {
            expanded_sheet(sheet);
        } else {
            narrow_sheet(sheet, window.is_compact());
        }
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
        // The same as [bundle] identifier in Dioxus.toml. It names the storage.
        .identifier("dev.darkpyonix.composerust.samples.minimal")
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

/// Preview entry point for `compose-rust-preview` (save-to-frame benchmark and PNG export).
#[compose_rust::Preview(name = "Minimal", sizes = [Phone, Desktop], schemes = [Light, Dark])]
pub fn minimal_preview(_ctx: &compose_rust::PreviewContext) {
    app();
}

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

        fn at_size(width_dp: f32, height_dp: f32) -> Self {
            let mut screen = Self::new();
            let mut bytes = Vec::new();
            encode_event(
                &HostEvent::new(
                    0,
                    0,
                    EventPayload::WindowSizeChanged {
                        width_dp,
                        height_dp,
                        class: WindowSizeClass::from_width_dp(width_dp),
                        height_class: WindowHeightClass::from_height_dp(height_dp),
                        safe_area_top: 0.0,
                        safe_area_leading: 0.0,
                        safe_area_trailing: 0.0,
                        safe_area_bottom: 0.0,
                    },
                ),
                &mut bytes,
            )
            .expect("the resize did not encode");
            let (batch, _) = screen
                .host
                .dispatch_event(&bytes)
                .expect("the resize failed");
            if !batch.is_empty() {
                screen.frames.push(batch.to_vec());
            }
            screen
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
                &HostEvent::new(node_id, handler_id, EventPayload::Clicked),
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

        /// The modifiers a live node carries now, deduplicated by slot.
        fn modifiers_of(&self, node: u32) -> Vec<Wire> {
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
            slots.into_iter().map(|(_, modifier)| modifier).collect()
        }

        /// The fill a live node carries now, if it has one.
        ///
        /// A node's modifiers sit in numbered slots, and a fill that goes away is its slot
        /// set to nothing rather than a record of its own, so only the last value of each
        /// slot counts.
        fn fill_of(&self, node: u32) -> Option<Paint> {
            self.modifiers_of(node)
                .into_iter()
                .find_map(|modifier| match modifier {
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
                            | PropertyKind::ActiveTrackColor
                            | PropertyKind::PlaceholderColor
                            | PropertyKind::CaretColor,
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

    /// Every text that names its own fonts says it has no type role.
    ///
    /// Text with a role is set in the theme's font for it, so a font list on it is thrown
    /// away and the Renderer reports that it was. The two serif letters beside the slider
    /// sent their serif with no role at all, which is the default role, so they were drawn
    /// in the theme's sans and each page load printed the report. The web page check
    /// counts a console error as a page that did not draw cleanly, and failed on it.
    #[test]
    fn fr40_text_that_names_fonts_has_no_type_role() {
        let screen = Screen::new();
        let live = screen.live();
        let with_fonts: Vec<u32> = live
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Font,
                    value: PropertyValue::Bytes(_),
                } => Some(*node_id),
                _ => None,
            })
            .collect();
        assert_eq!(
            with_fonts.len(),
            2,
            "the sheet names its own font for the two serif letters"
        );
        for node in with_fonts {
            let role = live.iter().rev().find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::TypeRole,
                    value,
                } if *node_id == node => Some(value.clone()),
                _ => None,
            });
            assert_eq!(
                role,
                Some(PropertyValue::Integer(0)),
                "node {node} names fonts, so it has to send the type role None"
            );
        }
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

    /// The delete action is the white bin of the delete icon, not the word "Delete".
    #[test]
    fn fr16_the_delete_action_draws_the_delete_icon() {
        let screen = Screen::new();
        let control = screen
            .named("Delete")
            .expect("the sheet has no delete action");
        let icon = screen
            .live()
            .iter()
            .rev()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Icon,
                    value: PropertyValue::Integer(tag),
                } if *node_id == control => Some(*tag),
                _ => None,
            });
        assert_eq!(icon, Some(i64::from(u16::from(IconRole::Delete))));
        assert!(
            !screen.texts().iter().any(|text| text == "Delete"),
            "the delete action still writes the word"
        );
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

    /// Compact fills the phone width, medium holds the vertical column to `PAGE_MEASURE`,
    /// and expanded switches to the multi-column showcase spread rather than a single column.
    #[test]
    fn fr20_the_page_stops_widening_past_a_phone() {
        fn widths_at(width_dp: f32) -> Vec<f32> {
            let screen = Screen::at_size(width_dp, 780.0);
            let found = screen
                .live()
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
            widths_at(700.0).contains(&PAGE_MEASURE),
            "a medium window does not hold the vertical column to the phone measure"
        );
        assert!(
            !widths_at(420.0).contains(&PAGE_MEASURE),
            "a phone window pins the page to a measure instead of filling"
        );
        assert!(
            !widths_at(1200.0).contains(&PAGE_MEASURE),
            "an expanded window still pins the sheet to a single vertical measure"
        );
    }

    /// The hero displays the reference title and subtitle copy.
    #[test]
    fn fr22_hero_copy_matches_reference() {
        for screen in [Screen::new(), Screen::at_size(1200.0, 900.0)] {
            let texts = screen.texts();
            assert!(
                texts.iter().any(|text| text == "80 UI Components"),
                "hero title is missing: {texts:?}"
            );
            assert!(
                texts.iter().any(|text| text == "Including Dark Theme"),
                "hero subtitle is missing: {texts:?}"
            );
            assert!(
                !texts.iter().any(|text| text == "Every component"),
                "old hero title is still present"
            );
            assert!(
                !texts.iter().any(|text| text == "Including dark theme"),
                "old hero subtitle is still present"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// The segmented control is constrained to its compact width and uses Caption labels.
    #[test]
    fn fr22_segmented_control_is_constrained_with_caption_labels() {
        let screen = Screen::new();
        let first_button = screen
            .named("First")
            .expect("the segmented control has no First button");
        let segment_box = screen
            .parent_of(first_button)
            .expect("First button has no parent box");
        let track_row = screen
            .parent_of(segment_box)
            .expect("segment box has no track row");
        assert!(
            screen
                .modifiers_of(track_row)
                .iter()
                .any(|modifier| matches!(modifier, Wire::Width(dp) if *dp == SEGMENTED_WIDTH)),
            "segmented control track is not constrained to {SEGMENTED_WIDTH} dp"
        );

        let live = screen.live();
        let expected_role = PropertyValue::Integer(i64::from(TypeRole::Caption as u16));
        for label in SEGMENTS {
            let text_node = live
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
                .unwrap_or_else(|| panic!("segmented label {label} is missing"));
            let role = live.iter().rev().find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::TypeRole,
                    value,
                } if *node_id == text_node => Some(value.clone()),
                _ => None,
            });
            assert_eq!(
                role,
                Some(expected_role.clone()),
                "segmented label {label} does not use TypeRole::Caption"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// At 1200x900 (expanded), the showcase uses a three-column spread containing every
    /// component group (hero, steppers with dark drop, segmented, panel+delete, chips,
    /// slider card, tile card, inverted bar, switches+tiles) and drop shadows.
    #[test]
    fn fr22_expanded_showcase_lays_out_all_sections_in_multi_column_spread() {
        let screen = Screen::at_size(1200.0, 900.0);
        let live = screen.live();
        let texts = screen.texts();

        // Multi-column spread: a Row whose live children include 3 weighted Columns.
        let rows: Vec<u32> = live
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::Create {
                    node_id,
                    widget: WidgetKind::Row,
                } => Some(*node_id),
                _ => None,
            })
            .collect();
        let has_three_column_row = rows.iter().any(|&row_id| {
            let weighted_columns: Vec<u32> = live
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::Create {
                        node_id,
                        widget: WidgetKind::Column,
                    } => Some(*node_id),
                    _ => None,
                })
                .filter(|&col_id| {
                    screen.parent_of(col_id) == Some(row_id)
                        && screen
                            .modifiers_of(col_id)
                            .iter()
                            .any(|modifier| matches!(modifier, Wire::Weight(w) if *w == 1.0))
                })
                .collect();
            weighted_columns.len() == 3
        });
        assert!(
            has_three_column_row,
            "expanded showcase does not lay out three weighted columns in a row"
        );

        // Hero, segmented control, steppers, panel+delete, chips, slider card, tile card,
        // inverted bar, switches+tiles are all present.
        assert!(texts.iter().any(|text| text == "80 UI Components"));
        assert!(texts.iter().any(|text| text == "Including Dark Theme"));
        for label in SEGMENTS {
            assert!(
                texts.iter().any(|text| text == label),
                "missing segment {label}"
            );
        }
        assert_eq!(
            texts.iter().filter(|text| *text == "Amount").count(),
            3,
            "expected three Amount steppers"
        );
        assert!(texts.iter().any(|text| text == "Subtitle"));
        assert!(screen.named("Delete").is_some(), "missing Delete action");
        assert_eq!(
            texts.iter().filter(|text| *text == "Description").count(),
            2,
            "expected two coin chip descriptions"
        );
        assert!(
            screen.named("Reading size").is_some(),
            "missing slider card"
        );
        assert!(
            live.iter().any(|mutation| matches!(
                mutation,
                Mutation::Create {
                    widget: WidgetKind::Slider,
                    ..
                }
            )),
            "missing Slider widget"
        );
        assert!(
            texts.iter().any(|text| {
                text == "Brief description or small text about the action of the buttons"
            }),
            "missing tile card caption"
        );
        assert!(screen.named("Back").is_some(), "missing inverted bar Back");
        assert!(
            live.iter().any(|mutation| matches!(
                mutation,
                Mutation::Create {
                    widget: WidgetKind::TextField,
                    ..
                }
            )),
            "missing inverted bar search TextField"
        );
        assert_eq!(
            screen.switch_tracks(),
            vec![palette::SWITCH, palette::TRACK],
            "missing switch pair"
        );
        let live_canvases = live
            .iter()
            .filter(|mutation| {
                matches!(
                    mutation,
                    Mutation::Create {
                        widget: WidgetKind::Canvas,
                        ..
                    }
                )
            })
            .count();
        assert_eq!(
            live_canvases, 7,
            "expected 7 live canvases (2 coins + 5 tiles)"
        );

        // Narrow upright steppers, staggered dark stepper drop, square chip tiles, and shadows.
        assert!(
            live.iter().any(|mutation| matches!(
                mutation,
                Mutation::SetModifier {
                    modifier: Wire::Width(dp),
                    ..
                } if *dp == UPRIGHT_STEPPER_WIDTH
            )),
            "upright steppers are not constrained to {UPRIGHT_STEPPER_WIDTH} dp"
        );
        assert!(
            live.iter().any(|mutation| matches!(
                mutation,
                Mutation::SetModifier {
                    modifier: Wire::PaddingEach { top, .. },
                    ..
                } if *top == DARK_STEPPER_DROP
            )),
            "dark upright stepper is not offset lower-right by {DARK_STEPPER_DROP} dp"
        );
        let chip_width_count = live
            .iter()
            .filter(|mutation| {
                matches!(
                    mutation,
                    Mutation::SetModifier {
                        modifier: Wire::Width(dp),
                        ..
                    } if *dp == CHIP_SIZE
                )
            })
            .count();
        let chip_height_count = live
            .iter()
            .filter(|mutation| {
                matches!(
                    mutation,
                    Mutation::SetModifier {
                        modifier: Wire::Height(dp),
                        ..
                    } if *dp == CHIP_SIZE
                )
            })
            .count();
        assert_eq!(chip_width_count, 2, "expected two square chip widths");
        assert_eq!(chip_height_count, 2, "expected two square chip heights");

        let shadow_count = live
            .iter()
            .filter(|mutation| {
                matches!(
                    mutation,
                    Mutation::SetModifier {
                        modifier: Wire::Shadow { .. },
                        ..
                    }
                )
            })
            .count();
        assert!(
            shadow_count >= 9,
            "expected drop shadows on hero, steppers, panel, chips, slider, tile card, and \
             lifted tile; got {shadow_count}"
        );

        compose_rust::window::reset_window_size();
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
