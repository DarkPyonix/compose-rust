//! A playground: every control the schema has, every accent, every rung of the ladders,
//! on one screen, in one design system.
//!
//! The other samples adapt: they take whichever design system the platform picks. This one
//! is unified. It names Cupertino and draws the same way on every platform, which is what
//! an application does when the design is the product rather than the platform's
//! convention.
//!
//! Appearance comes from roles. A fill is a `ColorRole`, a size is a `TypeRole`, a corner is
//! a `ShapeRole` and a gap is a `SpaceRole`, which is what lets the colour group be a
//! picture of the design system rather than a picture of this file.

mod playground;

use compose_rust::runtime::*;
use compose_rust::ui::*;
use playground::{Group, LADDER, SWATCHES, hero_marks};
use std::rc::Rc;

/// The side of the hero canvas, in dp.
const HERO_SIDE: f32 = 168.0;

/// How wide the page may grow past a phone, in dp.
const PAGE_MEASURE: f32 = 420.0;

/// A titled block of the sheet: a quiet label over whatever it shows.
fn block(title: &str, children: impl FnOnce()) {
    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Sm)
        .content(|| {
            Text(title)
                .type_role(TypeRole::Label)
                .color(Paint::Role(ColorRole::OnSurfaceVariant));
            children();
        });
}

/// One stepper: the amount, and a minus and a plus in the panel's own inverted ink.
fn counter(fill: ColorRole, ink: ColorRole, amount: i32, step: &Rc<dyn Fn(i32)>) {
    Surface()
        .modifier(
            Modifier
                .weight(1.0)
                .background(Paint::Role(fill))
                .shape_role(ShapeRole::Large)
                .padding_role(SpaceRole::Md),
        )
        .content(|| {
            Column()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::Center)
                .content(|| {
                    Text("Amount")
                        .type_role(TypeRole::Label)
                        .color(Paint::Role(ink));
                    Text(format!("{amount}"))
                        .type_role(TypeRole::Display)
                        .color(Paint::Role(ink));
                    Row()
                        .space_role(SpaceRole::Sm)
                        .alignment(Alignment::Center)
                        .content(|| {
                            for (label, by) in [("\u{2212}", -1), ("+", 1)] {
                                let step = Rc::clone(step);
                                Button(label)
                                    .variant(ButtonVariant::Filled)
                                    .modifier(
                                        Modifier
                                            .shape_role(ShapeRole::Full)
                                            .background(Paint::Role(ink)),
                                    )
                                    .color(Paint::Role(fill))
                                    .on_click(move || step(by));
                            }
                        });
                });
        });
}

/// The states the controls group shows.
#[derive(Clone)]
struct Controls {
    amount: MutableState<i32>,
    reading: MutableState<f32>,
    notify: MutableState<bool>,
    remember: MutableState<bool>,
    digest: MutableState<bool>,
}

/// A button that does nothing, which is what every one on this sheet is.
fn idle(label: &str, variant: ButtonVariant) -> ButtonScope {
    Button(label)
        .modifier(Modifier.weight(1.0))
        .variant(variant)
        .on_click(|| {})
}

/// A row with a label and a toggle at its far end.
fn toggle_row(label: &str, toggle: impl FnOnce()) {
    Row()
        .modifier(Modifier.fill_max_width())
        .alignment(Alignment::CenterStart)
        .content(|| {
            Text(label)
                .type_role(TypeRole::Body)
                .modifier(Modifier.weight(1.0));
            toggle();
        });
}

/// Everything a person can operate.
fn controls_group(controls: &Controls) {
    let amount = controls.amount.clone();
    let step: Rc<dyn Fn(i32)> =
        Rc::new(move |by: i32| amount.set((amount.get_untracked() + by).clamp(0, 99)));
    let reading = controls.reading.get();
    let percent = (reading * 100.0).round() as i32;
    let shown = controls.amount.get();

    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Lg)
        .content(|| {
            block("Stepper", || {
                Row()
                    .modifier(Modifier.fill_max_width())
                    .space_role(SpaceRole::Sm)
                    .content(|| {
                        counter(
                            ColorRole::SurfaceContainer,
                            ColorRole::OnSurface,
                            shown,
                            &step,
                        );
                        counter(ColorRole::OnSurface, ColorRole::Surface, shown, &step);
                    });
            });

            block("Field", || {
                TextField()
                    .modifier(Modifier.fill_max_width())
                    .placeholder("Search");
            });

            block("Slider", || {
                Surface()
                    .modifier(
                        Modifier
                            .fill_max_width()
                            .shape_role(ShapeRole::Large)
                            .padding_role(SpaceRole::Md),
                    )
                    .content(|| {
                        Column()
                            .modifier(Modifier.fill_max_width())
                            .space_role(SpaceRole::Sm)
                            .content(|| {
                                Row()
                                    .modifier(Modifier.fill_max_width())
                                    .space_role(SpaceRole::Sm)
                                    .alignment(Alignment::CenterStart)
                                    .content(|| {
                                        Text("A").type_role(TypeRole::Caption);
                                        let set = controls.reading.clone();
                                        Slider(reading)
                                            .modifier(Modifier.weight(1.0))
                                            // Ink on paper: the filled track is ink too.
                                            .color(Paint::Role(ColorRole::OnSurface))
                                            .on_change(move |value| set.set(value));
                                        Text("A").type_role(TypeRole::Title);
                                    });
                                Text(format!("Reading size {percent}%"))
                                    .type_role(TypeRole::Label)
                                    .color(Paint::Role(ColorRole::OnSurfaceVariant));
                            });
                    });
            });

            block("Toggles", || {
                Surface()
                    .modifier(
                        Modifier
                            .fill_max_width()
                            .shape_role(ShapeRole::Large)
                            .padding_role(SpaceRole::Md),
                    )
                    .content(|| {
                        Column()
                            .modifier(Modifier.fill_max_width())
                            .space_role(SpaceRole::Sm)
                            .content(|| {
                                toggle_row("Notifications", || {
                                    let set = controls.notify.clone();
                                    Switch(controls.notify.get())
                                        .on_change(move |value| set.set(value));
                                });
                                Separator(None);
                                toggle_row("Remember me", || {
                                    let set = controls.remember.clone();
                                    Checkbox(controls.remember.get())
                                        .on_change(move |value| set.set(value));
                                });
                                Separator(None);
                                toggle_row("Daily digest", || {
                                    let set = controls.digest.clone();
                                    RadioButton(controls.digest.get())
                                        .on_change(move |value| set.set(value));
                                });
                            });
                    });
            });

            block("Progress", || {
                Row()
                    .modifier(Modifier.fill_max_width())
                    .space_role(SpaceRole::Md)
                    .alignment(Alignment::CenterStart)
                    .content(|| {
                        ProgressIndicator(reading).modifier(Modifier.weight(1.0));
                        ProgressIndicator(reading).circular(true);
                    });
            });

            block("Buttons", || {
                Column()
                    .modifier(Modifier.fill_max_width())
                    .space_role(SpaceRole::Sm)
                    .content(|| {
                        Row()
                            .modifier(Modifier.fill_max_width())
                            .space_role(SpaceRole::Sm)
                            .content(|| {
                                idle("Filled", ButtonVariant::Filled);
                                idle("Tonal", ButtonVariant::Tonal);
                            });
                        Row()
                            .modifier(Modifier.fill_max_width())
                            .space_role(SpaceRole::Sm)
                            .content(|| {
                                idle("Outlined", ButtonVariant::Outlined);
                                idle("Text", ButtonVariant::Text);
                            });
                        Row()
                            .modifier(Modifier.fill_max_width())
                            .space_role(SpaceRole::Sm)
                            .content(|| {
                                idle("Disabled", ButtonVariant::Filled).enabled(false);
                                idle("Delete", ButtonVariant::Text)
                                    .color(Paint::Role(ColorRole::Error));
                            });
                    });
            });
        });
}

/// The containers those controls sit in.
fn surfaces_group() {
    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Lg)
        .content(|| {
            block("Inverted bar", || {
                Surface()
                    .modifier(
                        Modifier
                            .fill_max_width()
                            .background(Paint::Role(ColorRole::OnSurface))
                            .shape_role(ShapeRole::Large)
                            .padding_role(SpaceRole::Md),
                    )
                    .content(|| {
                        Column()
                            .modifier(Modifier.fill_max_width())
                            .space_role(SpaceRole::Md)
                            .content(|| {
                                Row()
                                    .modifier(Modifier.fill_max_width())
                                    .alignment(Alignment::CenterStart)
                                    .content(|| {
                                        Button("\u{2190}")
                                            .variant(ButtonVariant::Text)
                                            .color(Paint::Role(ColorRole::Surface))
                                            .on_click(|| {});
                                        Text("Title")
                                            .type_role(TypeRole::Title)
                                            .color(Paint::Role(ColorRole::Surface))
                                            .modifier(Modifier.weight(1.0))
                                            .text_align(TextAlign::End);
                                    });
                                TextField()
                                    .modifier(Modifier.fill_max_width())
                                    .placeholder("Search");
                            });
                    });
            });

            block("Panel and action", || {
                Row()
                    .modifier(Modifier.fill_max_width())
                    .space_role(SpaceRole::Sm)
                    .alignment(Alignment::CenterStart)
                    .content(|| {
                        Surface()
                            .modifier(
                                Modifier
                                    .weight(1.0)
                                    .shape_role(ShapeRole::Large)
                                    .padding_role(SpaceRole::Md),
                            )
                            .content(|| {
                                Column()
                                    .modifier(Modifier.fill_max_width())
                                    .space_role(SpaceRole::Xs)
                                    .content(|| {
                                        Text("Title").type_role(TypeRole::Subtitle);
                                        Text("Subtitle")
                                            .type_role(TypeRole::Body)
                                            .color(Paint::Role(ColorRole::OnSurfaceVariant));
                                        Text(
                                            "A paragraph sitting on a panel, which is the \
                                             reason the panel has a colour of its own.",
                                        )
                                        .type_role(TypeRole::Caption)
                                        .color(Paint::Role(ColorRole::OnSurfaceVariant));
                                    });
                            });
                        Button("Delete")
                            .variant(ButtonVariant::Text)
                            .color(Paint::Role(ColorRole::Error))
                            .on_click(|| {});
                    });
            });

            block("Layers", || {
                Row()
                    .modifier(Modifier.fill_max_width())
                    .space_role(SpaceRole::Sm)
                    .content(|| {
                        for (label, role, ink) in [
                            ("Page", ColorRole::Background, ColorRole::OnBackground),
                            ("Panel", ColorRole::SurfaceContainer, ColorRole::OnSurface),
                            (
                                "Recess",
                                ColorRole::SurfaceVariant,
                                ColorRole::OnSurfaceVariant,
                            ),
                        ] {
                            key(label, || {
                                Box()
                                    .modifier(
                                        Modifier
                                            .weight(1.0)
                                            .height(72.0)
                                            .background(Paint::Role(role))
                                            .shape_role(ShapeRole::Medium)
                                            .border(1.0, Paint::Role(ColorRole::OutlineVariant)),
                                    )
                                    .alignment(Alignment::Center)
                                    .content(|| {
                                        Text(label)
                                            .type_role(TypeRole::Label)
                                            .color(Paint::Role(ink));
                                    });
                            });
                        }
                    });
            });

            block("Corners", || {
                Row()
                    .modifier(Modifier.fill_max_width())
                    .space_role(SpaceRole::Xs)
                    .content(|| {
                        for (label, shape) in [
                            ("XS", ShapeRole::ExtraSmall),
                            ("S", ShapeRole::Small),
                            ("M", ShapeRole::Medium),
                            ("L", ShapeRole::Large),
                            ("Full", ShapeRole::Full),
                        ] {
                            key(label, || {
                                Box()
                                    .modifier(
                                        Modifier
                                            .weight(1.0)
                                            .height(56.0)
                                            .background(Paint::Role(ColorRole::SurfaceVariant))
                                            .shape_role(shape),
                                    )
                                    .alignment(Alignment::Center)
                                    .content(|| {
                                        Text(label)
                                            .type_role(TypeRole::Caption)
                                            .color(Paint::Role(ColorRole::OnSurfaceVariant));
                                    });
                            });
                        }
                    });
            });

            block("Raised", || {
                Row()
                    .modifier(Modifier.fill_max_width())
                    .space_role(SpaceRole::Md)
                    .content(|| {
                        for step in [0.0_f32, 2.0, 8.0] {
                            key(format!("{step}"), || {
                                Surface()
                                    .modifier(
                                        Modifier
                                            .weight(1.0)
                                            .height(72.0)
                                            .elevation(step)
                                            .shape_role(ShapeRole::Medium),
                                    )
                                    .content(|| {
                                        Box()
                                            .modifier(Modifier.fill_max_width().fill_max_height())
                                            .alignment(Alignment::Center)
                                            .content(|| {
                                                Text(format!("{step} dp"))
                                                    .type_role(TypeRole::Caption)
                                                    .color(Paint::Role(
                                                        ColorRole::OnSurfaceVariant,
                                                    ));
                                            });
                                    });
                            });
                        }
                    });
            });
        });
}

/// The fills and the type ladder.
fn colour_group() {
    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Lg)
        .content(|| {
            block("Fills, and the ink that reads on each", || {
                Column()
                    .modifier(Modifier.fill_max_width())
                    .space_role(SpaceRole::Xs)
                    .content(|| {
                        for (name, fill, ink) in SWATCHES {
                            key(name, || {
                                Column()
                                    .modifier(
                                        Modifier
                                            .fill_max_width()
                                            .background(Paint::Role(fill))
                                            .shape_role(ShapeRole::Medium)
                                            .padding_role(SpaceRole::Md),
                                    )
                                    .space_role(SpaceRole::Xs)
                                    .content(|| {
                                        Text(name)
                                            .type_role(TypeRole::BodyStrong)
                                            .color(Paint::Role(ink));
                                        Text("The ink this fill promises to carry.")
                                            .type_role(TypeRole::Caption)
                                            .color(Paint::Role(ink));
                                    });
                            });
                        }
                    });
            });

            block("The type ladder", || {
                Surface()
                    .modifier(
                        Modifier
                            .fill_max_width()
                            .shape_role(ShapeRole::Large)
                            .padding_role(SpaceRole::Md),
                    )
                    .content(|| {
                        Column()
                            .modifier(Modifier.fill_max_width())
                            .space_role(SpaceRole::Xs)
                            .content(|| {
                                for (name, rung) in LADDER {
                                    key(name, || {
                                        Text(name)
                                            .type_role(rung)
                                            .max_lines(1)
                                            .overflow(TextOverflow::Ellipsis);
                                    });
                                }
                            });
                    });
            });
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

    let group = remember(|| mutable_state_of(Group::Controls));
    let controls = remember(|| Controls {
        amount: mutable_state_of(2),
        reading: mutable_state_of(0.55_f32),
        notify: mutable_state_of(true),
        remember: mutable_state_of(false),
        digest: mutable_state_of(true),
    });
    let selected = group.get();

    Scaffold()
        .modifier(Modifier.background(Paint::Role(ColorRole::Background)))
        .top_bar(|| {
            TopAppBar().modifier(Modifier.fill_max_width()).content(|| {
                Text("Minimal")
                    .type_role(TypeRole::Title)
                    .modifier(Modifier.weight(1.0));
                Text("one design, every platform")
                    .type_role(TypeRole::Label)
                    .color(Paint::Role(ColorRole::OnSurfaceVariant));
            });
        })
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
                                    Column()
                                        .modifier(Modifier.fill_max_width())
                                        .alignment(Alignment::Center)
                                        .space_role(SpaceRole::Sm)
                                        .content(|| {
                                            Canvas(hero_marks(HERO_SIDE))
                                                .modifier(Modifier.size(HERO_SIDE, HERO_SIDE));
                                            Text("Every component")
                                                .type_role(TypeRole::Display)
                                                .text_align(TextAlign::Center);
                                            Text("Including dark theme")
                                                .type_role(TypeRole::Body)
                                                .color(Paint::Role(ColorRole::OnSurfaceVariant))
                                                .text_align(TextAlign::Center);
                                        });

                                    Column()
                                        .modifier(Modifier.fill_max_width())
                                        .space_role(SpaceRole::Sm)
                                        .content(|| {
                                            // Each child of the strip is one segment, and a
                                            // tap is that child's own click.
                                            Tabs(selected.index())
                                                .modifier(Modifier.fill_max_width())
                                                .content(|| {
                                                    for choice in Group::STRIP {
                                                        let pick = group.clone();
                                                        key(choice.label(), || {
                                                            Button(choice.label())
                                                                .variant(ButtonVariant::Text)
                                                                .color(Paint::Role(
                                                                    if choice == selected {
                                                                        ColorRole::OnSurface
                                                                    } else {
                                                                        ColorRole::OnSurfaceVariant
                                                                    },
                                                                ))
                                                                .on_click(move || pick.set(choice));
                                                        });
                                                    }
                                                });
                                            Text(selected.caption())
                                                .type_role(TypeRole::Caption)
                                                .color(Paint::Role(ColorRole::OnSurfaceVariant));
                                        });

                                    match selected {
                                        Group::Controls => controls_group(&controls),
                                        Group::Surfaces => surfaces_group(),
                                        Group::Colour => colour_group(),
                                    }
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
    use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
    use compose_rust::schema::{PropertyKind, WidgetKind};

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
        assert!(ComposeHost::new(app).rebuild().is_ok());
    }

    /// Every group has to encode, not just the one the screen opens on. A widget only the
    /// third tab reaches would otherwise fail for the first person to press it.
    #[test]
    fn fr15_every_group_encodes() {
        let mut host = ComposeHost::new(app);
        let first = host.rebuild().expect("the first frame failed").to_vec();
        for group in Group::STRIP {
            let (node_id, handler_id) = click_handler(&first, group.label())
                .unwrap_or_else(|| panic!("the strip has no segment called {}", group.label()));
            let mut bytes = Vec::new();
            compose_rust::protocol::encode_event(
                &compose_rust::protocol::HostEvent {
                    node_id,
                    handler_id,
                    payload: compose_rust::schema::EventPayload::Clicked,
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
        let mut host = ComposeHost::new(app);
        let first = host.rebuild().expect("the first frame failed").to_vec();
        let (node_id, handler_id) =
            click_handler(&first, Group::Colour.label()).expect("no colour segment");
        let mut bytes = Vec::new();
        compose_rust::protocol::encode_event(
            &compose_rust::protocol::HostEvent {
                node_id,
                handler_id,
                payload: compose_rust::schema::EventPayload::Clicked,
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
        let mut host = ComposeHost::new(app);
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
            compose_rust::window::reset_window_size();
            let mut host = ComposeHost::new(app);
            host.rebuild().expect("the first frame failed");
            let mut bytes = Vec::new();
            compose_rust::protocol::encode_event(
                &compose_rust::protocol::HostEvent {
                    node_id: 0,
                    handler_id: 0,
                    payload: compose_rust::schema::EventPayload::WindowSizeChanged {
                        width_dp,
                        height_dp: 780.0,
                        class: WindowSizeClass::from_width_dp(width_dp),
                        height_class: WindowHeightClass::from_height_dp(780.0),
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
                        modifier: compose_rust::schema::Modifier::Width(dp),
                        ..
                    } => Some(*dp),
                    _ => None,
                })
                .collect();
            compose_rust::window::reset_window_size();
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
