//! A playground: every control the schema has, every accent, every rung of the ladders,
//! on one screen, in one design system.
//!
//! The other samples adapt: they take whichever design system the platform picks, and a
//! screenshot of one of them is a screenshot of six possible screens. This one is unified.
//! It names Cupertino and draws the same way on every platform, which is what an
//! application does when the design is the product rather than the platform's convention.
//!
//! The rule the whole screen keeps is that appearance comes from roles. There is no hex
//! literal here and no magic dp: a fill is a `ColorRole`, a size is a `TypeRole`, a corner
//! is a `ShapeRole` and a gap is a `SpaceRole`. That is what lets the colour group at the
//! end be a picture of the design system rather than a picture of this file.

mod playground;

use dioxus_compose::prelude::*;
use playground::{Group, LADDER, SWATCHES, hero_marks};

/// The square the hero mark is drawn into.
///
/// A number rather than a role because it is the size of a drawing, and the space ladder
/// answers how far apart two things sit, not how large a picture is. It is the one
/// dimension on this screen that the design system has no opinion about.
const HERO_SIDE: f32 = 168.0;

/// How wide the page is once the window is wider than a phone.
///
/// This is a phone design, so a window twice that width does not get a page twice as wide:
/// it gets the same page with more room around it. Letting the column run to 1200dp would
/// be redrawing the design rather than showing it.
const PAGE_MEASURE: f32 = 420.0;

/// A titled block of the playground, so the groups below read as a list of things rather
/// than as one run of widgets.
fn block(title: &str, children: Element) -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            space_role: SpaceRole::Sm,
            Text {
                text: title,
                type_role: TypeRole::Label,
                color: Paint::Role(ColorRole::OnSurfaceVariant),
            }
            {children}
        }
    }
}

/// One of the two counters at the top of the controls group.
///
/// Two of them, one filled with the reading surface and one filled with its ink, because
/// that pair is the whole of what the reference's light and dark card is saying. Drawn
/// with the same declaration twice and two role arguments, rather than with two blocks of
/// code, which is the point: an inverted card is not a second component.
/// One stepper card.
///
/// The keys are drawn in the card's own two colours rather than in the design system's
/// accent: the reference is a sheet of black and white components and has no accent in
/// it, so a Tonal button left to itself puts the one colour there that the design does
/// not use. The pair is inverted on the dark card, which is the point of showing two.
fn counter(fill: ColorRole, ink: ColorRole, amount: i32, step: EventHandler<i32>) -> Element {
    rsx! {
        Surface {
            weight: 1.0,
            background: Paint::Role(fill),
            shape_role: ShapeRole::Large,
            padding_role: SpaceRole::Md,
            Column {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::Center,
                Text {
                    text: "Amount",
                    type_role: TypeRole::Label,
                    color: Paint::Role(ink),
                }
                Text {
                    text: "{amount}",
                    type_role: TypeRole::Display,
                    color: Paint::Role(ink),
                }
                Row {
                    space_role: SpaceRole::Sm,
                    alignment: Alignment::Center,
                    // The minus and plus signs, not a hyphen and a letter t. A stepper
                    // whose two keys are different widths is a stepper that wobbles.
                    Button {
                        text: "\u{2212}",
                        variant: ButtonVariant::Filled,
                        shape_role: ShapeRole::Full,
                        background: Paint::Role(ink),
                        color: Paint::Role(fill),
                        on_click: move |_| step.call(-1),
                    }
                    Button {
                        text: "+",
                        variant: ButtonVariant::Filled,
                        shape_role: ShapeRole::Full,
                        background: Paint::Role(ink),
                        color: Paint::Role(fill),
                        on_click: move |_| step.call(1),
                    }
                }
            }
        }
    }
}

/// Everything a person can operate.
fn controls_group(
    amount: Signal<i32>,
    reading: Signal<f32>,
    notify: Signal<bool>,
    remember: Signal<bool>,
    digest: Signal<bool>,
) -> Element {
    let mut amount = amount;
    let mut reading = reading;
    let mut notify = notify;
    let mut remember = remember;
    let mut digest = digest;
    let step = EventHandler::new(move |by: i32| amount.set((amount() + by).clamp(0, 99)));
    let percent = (reading() * 100.0).round() as i32;

    rsx! {
        Column {
            fill_max_width: true,
            space_role: SpaceRole::Lg,

            {block("Stepper", rsx! {
                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    {counter(ColorRole::SurfaceContainer, ColorRole::OnSurface, amount(), step)}
                    {counter(ColorRole::OnSurface, ColorRole::Surface, amount(), step)}
                }
            })}

            {block("Field", rsx! {
                // No background, no border, no padding and no corner. The frame around a
                // field is the design system's to draw, and a field that arrives here
                // wearing one drawn by the application would look the same in all six.
                TextField {
                    fill_max_width: true,
                    placeholder: "Search",
                }
            })}

            {block("Slider", rsx! {
                Surface {
                    fill_max_width: true,
                    shape_role: ShapeRole::Large,
                    padding_role: SpaceRole::Md,
                    Column {
                        fill_max_width: true,
                        space_role: SpaceRole::Sm,
                        Row {
                            fill_max_width: true,
                            space_role: SpaceRole::Sm,
                            alignment: Alignment::CenterStart,
                            // The small A and the large A are the two ends of the thing
                            // being set, said in the ladder rather than in point sizes.
                            Text { text: "A", type_role: TypeRole::Caption }
                            Slider {
                                weight: 1.0,
                                value: reading(),
                                // The sheet is ink on paper and has no accent in it, so
                                // the filled part of the track is ink too. Left unsaid,
                                // the design system answers with a blue that belongs to
                                // it rather than to this design.
                                color: Paint::Role(ColorRole::OnSurface),
                                on_change: move |value| reading.set(value),
                            }
                            Text { text: "A", type_role: TypeRole::Title }
                        }
                        Text {
                            text: "Reading size {percent}%",
                            type_role: TypeRole::Label,
                            color: Paint::Role(ColorRole::OnSurfaceVariant),
                        }
                    }
                }
            })}

            {block("Toggles", rsx! {
                Surface {
                    fill_max_width: true,
                    shape_role: ShapeRole::Large,
                    padding_role: SpaceRole::Md,
                    Column {
                        fill_max_width: true,
                        space_role: SpaceRole::Sm,
                        Row {
                            fill_max_width: true,
                            alignment: Alignment::CenterStart,
                            Text { text: "Notifications", type_role: TypeRole::Body, weight: 1.0 }
                            Switch { checked: notify(), on_change: move |value| notify.set(value) }
                        }
                        Separator {}
                        Row {
                            fill_max_width: true,
                            alignment: Alignment::CenterStart,
                            Text { text: "Remember me", type_role: TypeRole::Body, weight: 1.0 }
                            Checkbox {
                                checked: remember(),
                                on_change: move |value| remember.set(value),
                            }
                        }
                        Separator {}
                        Row {
                            fill_max_width: true,
                            alignment: Alignment::CenterStart,
                            Text { text: "Daily digest", type_role: TypeRole::Body, weight: 1.0 }
                            RadioButton {
                                selected: digest(),
                                on_change: move |value| digest.set(value),
                            }
                        }
                    }
                }
            })}

            {block("Progress", rsx! {
                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Md,
                    alignment: Alignment::CenterStart,
                    ProgressIndicator { weight: 1.0, value: reading() }
                    ProgressIndicator { value: reading(), circular: true }
                }
            })}

            {block("Buttons", rsx! {
                Column {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    Row {
                        fill_max_width: true,
                        space_role: SpaceRole::Sm,
                        Button { text: "Filled", weight: 1.0, variant: ButtonVariant::Filled, on_click: move |_| {} }
                        Button { text: "Tonal", weight: 1.0, variant: ButtonVariant::Tonal, on_click: move |_| {} }
                    }
                    Row {
                        fill_max_width: true,
                        space_role: SpaceRole::Sm,
                        Button { text: "Outlined", weight: 1.0, variant: ButtonVariant::Outlined, on_click: move |_| {} }
                        Button { text: "Text", weight: 1.0, variant: ButtonVariant::Text, on_click: move |_| {} }
                    }
                    Row {
                        fill_max_width: true,
                        space_role: SpaceRole::Sm,
                        Button { text: "Disabled", weight: 1.0, variant: ButtonVariant::Filled, enabled: false, on_click: move |_| {} }
                        // What marks a destructive action is its label's colour, in every
                        // one of the six. The variant stays whatever a plain button is.
                        Button {
                            text: "Delete",
                            weight: 1.0,
                            variant: ButtonVariant::Text,
                            color: Paint::Role(ColorRole::Error),
                            on_click: move |_| {},
                        }
                    }
                }
            })}
        }
    }
}

/// The containers those controls sit in.
fn surfaces_group() -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            space_role: SpaceRole::Lg,

            {block("Inverted bar", rsx! {
                // The reference's dark header: a bar filled with the reading ink, holding
                // the reading surface as its own ink. Said with the pair the other way
                // round rather than with a colour of its own.
                Surface {
                    fill_max_width: true,
                    background: Paint::Role(ColorRole::OnSurface),
                    shape_role: ShapeRole::Large,
                    padding_role: SpaceRole::Md,
                    Column {
                        fill_max_width: true,
                        space_role: SpaceRole::Md,
                        Row {
                            fill_max_width: true,
                            alignment: Alignment::CenterStart,
                            Button {
                                text: "\u{2190}",
                                variant: ButtonVariant::Text,
                                color: Paint::Role(ColorRole::Surface),
                                on_click: move |_| {},
                            }
                            Text {
                                text: "Title",
                                type_role: TypeRole::Title,
                                color: Paint::Role(ColorRole::Surface),
                                weight: 1.0,
                                text_align: TextAlign::End,
                            }
                        }
                        TextField { fill_max_width: true, placeholder: "Search" }
                    }
                }
            })}

            {block("Panel and action", rsx! {
                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    alignment: Alignment::CenterStart,
                    Surface {
                        weight: 1.0,
                        shape_role: ShapeRole::Large,
                        padding_role: SpaceRole::Md,
                        Column {
                            fill_max_width: true,
                            space_role: SpaceRole::Xs,
                            Text { text: "Title", type_role: TypeRole::Subtitle }
                            Text {
                                text: "Subtitle",
                                type_role: TypeRole::Body,
                                color: Paint::Role(ColorRole::OnSurfaceVariant),
                            }
                            Text {
                                text: "A paragraph sitting on a panel, which is the \
                                       reason the panel has a colour of its own.",
                                type_role: TypeRole::Caption,
                                color: Paint::Role(ColorRole::OnSurfaceVariant),
                            }
                        }
                    }
                    Button {
                        text: "Delete",
                        variant: ButtonVariant::Text,
                        color: Paint::Role(ColorRole::Error),
                        on_click: move |_| {},
                    }
                }
            })}

            {block("Layers", rsx! {
                // Three fills that are meant to be told apart from each other: the page,
                // the layer raised off it, and the recessed region inside that layer.
                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    for (label, role, ink) in [
                        ("Page", ColorRole::Background, ColorRole::OnBackground),
                        ("Panel", ColorRole::SurfaceContainer, ColorRole::OnSurface),
                        ("Recess", ColorRole::SurfaceVariant, ColorRole::OnSurfaceVariant),
                    ] {
                        dioxus_compose::Box {
                            key: "{label}",
                            weight: 1.0,
                            height: 72.0,
                            background: Paint::Role(role),
                            shape_role: ShapeRole::Medium,
                            border_width: 1.0,
                            border_color: Paint::Role(ColorRole::OutlineVariant),
                            alignment: Alignment::Center,
                            Text {
                                text: label,
                                type_role: TypeRole::Label,
                                color: Paint::Role(ink),
                            }
                        }
                    }
                }
            })}

            {block("Corners", rsx! {
                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Xs,
                    for (label, shape) in [
                        ("XS", ShapeRole::ExtraSmall),
                        ("S", ShapeRole::Small),
                        ("M", ShapeRole::Medium),
                        ("L", ShapeRole::Large),
                        ("Full", ShapeRole::Full),
                    ] {
                        dioxus_compose::Box {
                            key: "{label}",
                            weight: 1.0,
                            height: 56.0,
                            background: Paint::Role(ColorRole::SurfaceVariant),
                            shape_role: shape,
                            alignment: Alignment::Center,
                            Text {
                                text: label,
                                type_role: TypeRole::Caption,
                                color: Paint::Role(ColorRole::OnSurfaceVariant),
                            }
                        }
                    }
                }
            })}

            {block("Raised", rsx! {
                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Md,
                    for step in [0.0_f32, 2.0, 8.0] {
                        Surface {
                            key: "{step}",
                            weight: 1.0,
                            height: 72.0,
                            elevation: step,
                            shape_role: ShapeRole::Medium,
                            dioxus_compose::Box {
                                fill_max_width: true,
                                fill_max_height: true,
                                alignment: Alignment::Center,
                                Text {
                                    text: "{step} dp",
                                    type_role: TypeRole::Caption,
                                    color: Paint::Role(ColorRole::OnSurfaceVariant),
                                }
                            }
                        }
                    }
                }
            })}
        }
    }
}

/// The table underneath everything above.
fn colour_group() -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            space_role: SpaceRole::Lg,

            {block("Fills, and the ink that reads on each", rsx! {
                Column {
                    fill_max_width: true,
                    space_role: SpaceRole::Xs,
                    for (name, fill, ink) in SWATCHES {
                        Column {
                            key: "{name}",
                            fill_max_width: true,
                            background: Paint::Role(fill),
                            shape_role: ShapeRole::Medium,
                            padding_role: SpaceRole::Md,
                            space_role: SpaceRole::Xs,
                            Text {
                                text: name,
                                type_role: TypeRole::BodyStrong,
                                color: Paint::Role(ink),
                            }
                            // A sentence rather than a word, because that is the promise
                            // the container roles make and the one a swatch of colour
                            // beside a label would never test.
                            Text {
                                text: "The ink this fill promises to carry.",
                                type_role: TypeRole::Caption,
                                color: Paint::Role(ink),
                            }
                        }
                    }
                }
            })}

            {block("The type ladder", rsx! {
                Surface {
                    fill_max_width: true,
                    shape_role: ShapeRole::Large,
                    padding_role: SpaceRole::Md,
                    Column {
                        fill_max_width: true,
                        space_role: SpaceRole::Xs,
                        for (name, rung) in LADDER {
                            Text {
                                key: "{name}",
                                text: name,
                                type_role: rung,
                                max_lines: 1,
                                overflow: TextOverflow::Ellipsis,
                            }
                        }
                    }
                }
            })}
        }
    }
}

pub fn app() -> Element {
    let window = use_window_size();
    // A phone design in a desktop window is still a phone design. Past the compact class
    // the page stops widening and sits in the middle, and the window shows the page it is
    // on either side, which is what the reference sheet does with its own background.
    let measure = if window.is_compact() {
        None
    } else {
        Some(PAGE_MEASURE)
    };

    let mut group = use_signal(|| Group::Controls);
    let amount = use_signal(|| 2);
    let reading = use_signal(|| 0.55_f32);
    let notify = use_signal(|| true);
    let remember = use_signal(|| false);
    let digest = use_signal(|| true);

    let body = match group() {
        Group::Controls => controls_group(amount, reading, notify, remember, digest),
        Group::Surfaces => surfaces_group(),
        Group::Colour => colour_group(),
    };

    rsx! {
        // The frame is named rather than built: what the bar becomes, and where the page
        // starts under it, are answers this screen never has to know.
        Scaffold {
            background: Paint::Role(ColorRole::Background),
            top_bar: rsx! {
                TopAppBar {
                    fill_max_width: true,
                    Text { text: "Minimal", type_role: TypeRole::Title, weight: 1.0 }
                    Text {
                        text: "one design, every platform",
                        type_role: TypeRole::Label,
                        color: Paint::Role(ColorRole::OnSurfaceVariant),
                    }
                }
            },

                        dioxus_compose::Box {
                fill_max_width: true,
                weight: 1.0,
                alignment: Alignment::TopCenter,
                ScrollColumn {
                    width: measure,
                    fill_max_width: measure.is_none(),
                    fill_max_height: true,
                    Column {
                        fill_max_width: true,
                        padding_role: SpaceRole::Md,
                        space_role: SpaceRole::Lg,

                        // The mark and the two lines under it, centred, which is the one
                        // piece of this screen that is a picture rather than a control.
                        Column {
                            fill_max_width: true,
                            alignment: Alignment::Center,
                            space_role: SpaceRole::Sm,
                            Canvas {
                                width: HERO_SIDE,
                                height: HERO_SIDE,
                                commands: hero_marks(HERO_SIDE),
                            }
                            Text {
                                text: "Every component",
                                type_role: TypeRole::Display,
                                text_align: TextAlign::Center,
                            }
                            Text {
                                text: "Including dark theme",
                                type_role: TypeRole::Body,
                                color: Paint::Role(ColorRole::OnSurfaceVariant),
                                text_align: TextAlign::Center,
                            }
                        }

                        Column {
                            fill_max_width: true,
                            space_role: SpaceRole::Sm,
                            // Each child of the strip is one segment. Tapping one is
                            // reported as that child's own click, so the strip does not
                            // have to be rebuilt to show which is selected: the Renderer
                            // already moved the mark before the Host heard anything.
                            Tabs {
                                fill_max_width: true,
                                selected_index: group().index(),
                                for choice in Group::STRIP {
                                    Button {
                                        key: "{choice.label()}",
                                        text: choice.label(),
                                        variant: ButtonVariant::Text,
                                        // A text button is the design system's accent by
                                        // default, which is the one colour this sheet does
                                        // not have. The chosen segment is ink and the rest
                                        // is the quieter grey beside it.
                                        color: Paint::Role(if choice == group() {
                                            ColorRole::OnSurface
                                        } else {
                                            ColorRole::OnSurfaceVariant
                                        }),
                                        on_click: move |_| group.set(choice),
                                    }
                                }
                            }
                            Text {
                                text: group().caption(),
                                type_role: TypeRole::Caption,
                                color: Paint::Role(ColorRole::OnSurfaceVariant),
                            }
                        }

                        {body}
                    }
                }
            }
        }
    }
}

/// The design this sample draws, named once.
///
/// One design system everywhere, because the design is the product here rather than the
/// platform's convention, and light because the reference sheet is a white page with the
/// components laid out on it. The two dark buttons and the dark card on it are the
/// inverted pair the sheet is demonstrating, not a second sheet in a second scheme.
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
                .with_title("Minimal")
                // The mark this sample draws on its own page, so the window in a task
                // list and the window on screen are recognisably the same application.
                // Registered like any other picture: the bytes travel and the renderer
                // refers to them by id, because a path would be a fact about the machine
                // this was built on and a name would ask the toolkit to find something it
                // may not have.
                .with_icon(dioxus_compose::asset::asset(
                    AssetKind::Png,
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
    use dioxus_compose::protocol::{Mutation, PropertyValue, decode_batch};
    use dioxus_compose::schema::{PropertyKind, WidgetKind};

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

    /// Every piece of text the screen is carrying after a batch.
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

    /// The handler a button carrying this label declared, if the batch holds one.
    fn click_handler(batch: &[u8], label: &str) -> Option<(u32, u64)> {
        let mutations = decode_batch(batch).expect("the batch did not decode");
        let node = mutations.iter().find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::Text,
                value: PropertyValue::String(text),
            } if *text == label => Some(*node_id),
            _ => None,
        })?;
        mutations.iter().find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnClick,
                value: PropertyValue::Integer(handler),
            } if *node_id == node => Some((node, *handler as u64)),
            _ => None,
        })
    }

    /// A property the schema does not have fails the whole batch rather than just itself,
    /// so a screen that builds in Rust can still be blank on screen.
    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        assert!(Host::new(app).rebuild().is_ok());
    }

    /// Every group has to encode, not just the one the screen opens on. A widget only the
    /// third tab reaches would otherwise fail for the first person to press it.
    #[test]
    fn fr15_every_group_encodes() {
        let mut host = Host::new(app);
        let first = host.rebuild().expect("the first frame failed").to_vec();
        for group in Group::STRIP {
            let (node_id, handler_id) = click_handler(&first, group.label())
                .unwrap_or_else(|| panic!("the strip has no segment called {}", group.label()));
            let mut bytes = Vec::new();
            dioxus_compose::protocol::encode_event(
                &dioxus_compose::protocol::HostEvent {
                    node_id,
                    handler_id,
                    payload: dioxus_compose::schema::EventPayload::Clicked,
                },
                &mut bytes,
            )
            .expect("the click did not encode");
            let (batch, _) = host
                .dispatch_event(&bytes)
                .unwrap_or_else(|error| panic!("{} failed to encode: {error:?}", group.label()));
            assert!(
                !batch.is_empty(),
                "selecting {} changed nothing on screen",
                group.label()
            );
        }
    }

    /// The colour group is the screen this sample exists for: it is the only place the
    /// three accents and their containers are all visible at once, and a swatch that
    /// quietly went missing would leave a role nobody ever looks at.
    #[test]
    fn fr13_the_colour_group_shows_every_accent_and_container() {
        let mut host = Host::new(app);
        let first = host.rebuild().expect("the first frame failed").to_vec();
        let (node_id, handler_id) =
            click_handler(&first, Group::Colour.label()).expect("no colour segment");
        let mut bytes = Vec::new();
        dioxus_compose::protocol::encode_event(
            &dioxus_compose::protocol::HostEvent {
                node_id,
                handler_id,
                payload: dioxus_compose::schema::EventPayload::Clicked,
            },
            &mut bytes,
        )
        .expect("the click did not encode");
        let (batch, _) = host
            .dispatch_event(&bytes)
            .expect("the colour group failed");
        let showing = texts(batch);
        for (name, _, _) in SWATCHES {
            assert!(
                showing.iter().any(|text| text == name),
                "the colour group does not show {name}, so that role is never looked at"
            );
        }
    }

    /// The hero is a drawing, and a drawing that does not reach the Renderer is a blank
    /// square nobody notices until the picture comes back.
    #[test]
    fn fr16_the_hero_mark_is_a_canvas_with_commands_on_it() {
        let mut host = Host::new(app);
        let batch = host.rebuild().expect("the first frame failed");
        let mutations = decode_batch(batch).expect("the batch did not decode");
        let canvas = mutations.iter().find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Canvas,
            } => Some(*node_id),
            _ => None,
        });
        let canvas = canvas.expect("the screen has no canvas, so the hero is not drawn");
        assert!(
            mutations.iter().any(|mutation| matches!(
                mutation,
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Commands,
                    ..
                } if *node_id == canvas
            )),
            "the canvas carries no draw list, so it is an empty square"
        );
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
            dioxus_compose::window::reset_window_size();
            let mut host = Host::new(app);
            host.rebuild().expect("the first frame failed");
            let mut bytes = Vec::new();
            dioxus_compose::protocol::encode_event(
                &dioxus_compose::protocol::HostEvent {
                    node_id: 0,
                    handler_id: 0,
                    payload: dioxus_compose::schema::EventPayload::WindowSizeChanged {
                        width_dp,
                        height_dp: 780.0,
                        class: WindowSizeClass::from_width_dp(width_dp),
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
                        modifier: dioxus_compose::Modifier::Width(dp),
                        ..
                    } => Some(*dp),
                    _ => None,
                })
                .collect();
            dioxus_compose::window::reset_window_size();
            found
        }

        assert!(
            !widths_at(1180.0).contains(&HERO_SIDE),
            "the hero is sized in the list this test reads, so the check below would pass \
             on the wrong modifier"
        );
        assert!(
            widths_at(1180.0).contains(&PAGE_MEASURE),
            "a desktop window does not hold the page to a phone's measure"
        );
        assert!(
            !widths_at(420.0).contains(&PAGE_MEASURE),
            "a phone window pins the page to a measure instead of filling"
        );
    }

    /// The screen, in the one design system it ships, in both schemes, at all three
    /// widths.
    #[test]
    fn fr14_the_playground_is_recorded_in_the_system_it_ships() {
        sample_frames::record_as(
            "Minimal",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |_| {},
        );
    }

    /// The surfaces group: the inverted bar, the layer trio, the corner ladder and the
    /// elevation steps.
    ///
    /// A third picture, because this is where a container the application filled itself has
    /// to come out in that colour rather than in the design system's. It did not, for a
    /// while, and no assertion here could have said so.
    #[test]
    fn fr14_the_surfaces_group_is_recorded() {
        sample_frames::record_as(
            "MinimalPanels",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    screen.press(Group::Surfaces.label()),
                    "the screen has no way to reach the surfaces group"
                );
            },
        );
    }

    /// The colour group, which is a different picture from the one above and the reason
    /// the accent containers were added at all.
    #[test]
    fn fr13_the_colour_group_is_recorded() {
        sample_frames::record_as(
            "MinimalColour",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert!(
                    screen.press(Group::Colour.label()),
                    "the screen has no way to reach the colour group"
                );
            },
        );
    }
}
