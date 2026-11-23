//! The minimal sample's component sheet as it was when the benchmark scenarios were frozen:
//! three groups behind a Parts, Panels and Colour strip, in one design system.
//!
//! Measurement fixture. Taken from `samples/minimal/src/lib.rs` at fea50dc5, the last
//! commit before 3712197e redrew the minimal sample on 2026-11-13, and checked line by line
//! against the rsx! screen the Dioxus path measures (dioxus-compose dd1af76b,
//! `samples/native-widgets/minimal/src/lib.rs`, the same screen compose-rust held at
//! 22822c13). The two built the same tree; nothing here had to be changed to match it.
//! Builder chains became today's function and call macro form, and nothing else changed.
//!
//! It lives in the bench, not in `samples/`, because the comparison needs the same screen
//! on both paths. The Dioxus path is pinned with this screen, and the shipped sample has
//! since been redrawn after its design reference, so it no longer builds the tree the
//! pinned path builds. What is measured here is the authoring path and the runtime under
//! this screen, not the look of today's sample. Change nothing that changes the tree it
//! builds: the comparison only counts runs where both paths build the same tree after
//! every step.
//!
//! The launch, the theme and the platform entry points of the sample are left out. The
//! bench starts the root composable on a Host of its own and never opens a window.

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
    Column!(
        modifier = Modifier.fill_max_width(),
        space_role = SpaceRole::Sm,
        {
            Text!(
                title,
                type_role = TypeRole::Label,
                color = Paint::Role(ColorRole::OnSurfaceVariant)
            );
            children();
        }
    );
}

/// One stepper: the amount, and a minus and a plus in the panel's own inverted ink.
fn counter(fill: ColorRole, ink: ColorRole, amount: i32, step: &Rc<dyn Fn(i32)>) {
    Surface!(
        modifier = Modifier
            .weight(1.0)
            .background(Paint::Role(fill))
            .shape_role(ShapeRole::Large)
            .padding_role(SpaceRole::Md),
        {
            Column!(
                modifier = Modifier.fill_max_width(),
                space_role = SpaceRole::Sm,
                alignment = Alignment::Center,
                {
                    Text!(
                        "Amount",
                        type_role = TypeRole::Label,
                        color = Paint::Role(ink)
                    );
                    Text!(
                        format!("{amount}"),
                        type_role = TypeRole::Display,
                        color = Paint::Role(ink)
                    );
                    Row!(space_role = SpaceRole::Sm, alignment = Alignment::Center, {
                        for (label, by) in [("\u{2212}", -1), ("+", 1)] {
                            let step = Rc::clone(step);
                            Button!(
                                label,
                                move || step(by),
                                variant = ButtonVariant::Filled,
                                modifier = Modifier
                                    .shape_role(ShapeRole::Full)
                                    .background(Paint::Role(ink)),
                                color = Paint::Role(fill)
                            );
                        }
                    });
                }
            );
        }
    );
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
fn idle(label: &str, variant: ButtonVariant, enabled: bool, color: Option<Paint>) {
    Button!(
        label,
        || {},
        modifier = Modifier.weight(1.0),
        variant = variant,
        enabled = enabled,
        color = color
    );
}

/// A row with a label and a toggle at its far end.
fn toggle_row(label: &str, toggle: impl FnOnce()) {
    Row!(
        modifier = Modifier.fill_max_width(),
        alignment = Alignment::CenterStart,
        {
            Text!(
                label,
                type_role = TypeRole::Body,
                modifier = Modifier.weight(1.0)
            );
            toggle();
        }
    );
}

/// Everything a person can operate.
fn controls_group(controls: &Controls) {
    let amount = controls.amount;
    let step: Rc<dyn Fn(i32)> =
        Rc::new(move |by: i32| amount.set((amount.get_untracked() + by).clamp(0, 99)));
    let reading = controls.reading.get();
    let percent = (reading * 100.0).round() as i32;
    let shown = controls.amount.get();

    Column!(
        modifier = Modifier.fill_max_width(),
        space_role = SpaceRole::Lg,
        {
            block("Stepper", || {
                Row!(
                    modifier = Modifier.fill_max_width(),
                    space_role = SpaceRole::Sm,
                    {
                        counter(
                            ColorRole::SurfaceContainer,
                            ColorRole::OnSurface,
                            shown,
                            &step,
                        );
                        counter(ColorRole::OnSurface, ColorRole::Surface, shown, &step);
                    }
                );
            });

            block("Field", || {
                TextField!(
                    "",
                    |_| {},
                    modifier = Modifier.fill_max_width(),
                    placeholder = "Search"
                );
            });

            block("Slider", || {
                Surface!(
                    modifier = Modifier
                        .fill_max_width()
                        .shape_role(ShapeRole::Large)
                        .padding_role(SpaceRole::Md),
                    {
                        Column!(
                            modifier = Modifier.fill_max_width(),
                            space_role = SpaceRole::Sm,
                            {
                                Row!(
                                    modifier = Modifier.fill_max_width(),
                                    space_role = SpaceRole::Sm,
                                    alignment = Alignment::CenterStart,
                                    {
                                        Text!("A", type_role = TypeRole::Caption);
                                        let set = controls.reading;
                                        // Ink on paper: the filled track is ink too.
                                        Slider!(
                                            reading,
                                            move |value| set.set(value),
                                            modifier = Modifier.weight(1.0),
                                            color = Paint::Role(ColorRole::OnSurface)
                                        );
                                        Text!("A", type_role = TypeRole::Title);
                                    }
                                );
                                Text!(
                                    format!("Reading size {percent}%"),
                                    type_role = TypeRole::Label,
                                    color = Paint::Role(ColorRole::OnSurfaceVariant)
                                );
                            }
                        );
                    }
                );
            });

            block("Toggles", || {
                Surface!(
                    modifier = Modifier
                        .fill_max_width()
                        .shape_role(ShapeRole::Large)
                        .padding_role(SpaceRole::Md),
                    {
                        Column!(
                            modifier = Modifier.fill_max_width(),
                            space_role = SpaceRole::Sm,
                            {
                                toggle_row("Notifications", || {
                                    let set = controls.notify;
                                    Switch!(controls.notify.get(), move |value| set.set(value));
                                });
                                Separator!(None);
                                toggle_row("Remember me", || {
                                    let set = controls.remember;
                                    Checkbox!(controls.remember.get(), move |value| set.set(value));
                                });
                                Separator!(None);
                                toggle_row("Daily digest", || {
                                    let set = controls.digest;
                                    RadioButton!(controls.digest.get(), move |value| set
                                        .set(value));
                                });
                            }
                        );
                    }
                );
            });

            block("Progress", || {
                Row!(
                    modifier = Modifier.fill_max_width(),
                    space_role = SpaceRole::Md,
                    alignment = Alignment::CenterStart,
                    {
                        ProgressIndicator!(reading, modifier = Modifier.weight(1.0));
                        ProgressIndicator!(reading, circular = true);
                    }
                );
            });

            block("Buttons", || {
                Column!(
                    modifier = Modifier.fill_max_width(),
                    space_role = SpaceRole::Sm,
                    {
                        Row!(
                            modifier = Modifier.fill_max_width(),
                            space_role = SpaceRole::Sm,
                            {
                                idle("Filled", ButtonVariant::Filled, true, None);
                                idle("Tonal", ButtonVariant::Tonal, true, None);
                            }
                        );
                        Row!(
                            modifier = Modifier.fill_max_width(),
                            space_role = SpaceRole::Sm,
                            {
                                idle("Outlined", ButtonVariant::Outlined, true, None);
                                idle("Text", ButtonVariant::Text, true, None);
                            }
                        );
                        Row!(
                            modifier = Modifier.fill_max_width(),
                            space_role = SpaceRole::Sm,
                            {
                                idle("Disabled", ButtonVariant::Filled, false, None);
                                idle(
                                    "Delete",
                                    ButtonVariant::Text,
                                    true,
                                    Some(Paint::Role(ColorRole::Error)),
                                );
                            }
                        );
                    }
                );
            });
        }
    );
}

/// The containers those controls sit in.
fn surfaces_group() {
    Column!(
        modifier = Modifier.fill_max_width(),
        space_role = SpaceRole::Lg,
        {
            block("Inverted bar", || {
                Surface!(
                    modifier = Modifier
                        .fill_max_width()
                        .background(Paint::Role(ColorRole::OnSurface))
                        .shape_role(ShapeRole::Large)
                        .padding_role(SpaceRole::Md),
                    {
                        Column!(
                            modifier = Modifier.fill_max_width(),
                            space_role = SpaceRole::Md,
                            {
                                Row!(
                                    modifier = Modifier.fill_max_width(),
                                    alignment = Alignment::CenterStart,
                                    {
                                        Button!(
                                            "\u{2190}",
                                            || {},
                                            variant = ButtonVariant::Text,
                                            color = Paint::Role(ColorRole::Surface)
                                        );
                                        Text!(
                                            "Title",
                                            type_role = TypeRole::Title,
                                            color = Paint::Role(ColorRole::Surface),
                                            modifier = Modifier.weight(1.0),
                                            text_align = TextAlign::End
                                        );
                                    }
                                );
                                TextField!(
                                    "",
                                    |_| {},
                                    modifier = Modifier.fill_max_width(),
                                    placeholder = "Search"
                                );
                            }
                        );
                    }
                );
            });

            block("Panel and action", || {
                Row!(
                    modifier = Modifier.fill_max_width(),
                    space_role = SpaceRole::Sm,
                    alignment = Alignment::CenterStart,
                    {
                        Surface!(
                            modifier = Modifier
                                .weight(1.0)
                                .shape_role(ShapeRole::Large)
                                .padding_role(SpaceRole::Md),
                            {
                                Column!(
                                    modifier = Modifier.fill_max_width(),
                                    space_role = SpaceRole::Xs,
                                    {
                                        Text!("Title", type_role = TypeRole::Subtitle);
                                        Text!(
                                            "Subtitle",
                                            type_role = TypeRole::Body,
                                            color = Paint::Role(ColorRole::OnSurfaceVariant)
                                        );
                                        Text!(
                                            "A paragraph sitting on a panel, which is the \
                                             reason the panel has a colour of its own.",
                                            type_role = TypeRole::Caption,
                                            color = Paint::Role(ColorRole::OnSurfaceVariant)
                                        );
                                    }
                                );
                            }
                        );
                        Button!(
                            "Delete",
                            || {},
                            variant = ButtonVariant::Text,
                            color = Paint::Role(ColorRole::Error)
                        );
                    }
                );
            });

            block("Layers", || {
                Row!(
                    modifier = Modifier.fill_max_width(),
                    space_role = SpaceRole::Sm,
                    {
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
                                Box!(
                                    modifier = Modifier
                                        .weight(1.0)
                                        .height(72.0)
                                        .background(Paint::Role(role))
                                        .shape_role(ShapeRole::Medium)
                                        .border(1.0, Paint::Role(ColorRole::OutlineVariant)),
                                    alignment = Alignment::Center,
                                    {
                                        Text!(
                                            label,
                                            type_role = TypeRole::Label,
                                            color = Paint::Role(ink)
                                        );
                                    }
                                );
                            });
                        }
                    }
                );
            });

            block("Corners", || {
                Row!(
                    modifier = Modifier.fill_max_width(),
                    space_role = SpaceRole::Xs,
                    {
                        for (label, shape) in [
                            ("XS", ShapeRole::ExtraSmall),
                            ("S", ShapeRole::Small),
                            ("M", ShapeRole::Medium),
                            ("L", ShapeRole::Large),
                            ("Full", ShapeRole::Full),
                        ] {
                            key(label, || {
                                Box!(
                                    modifier = Modifier
                                        .weight(1.0)
                                        .height(56.0)
                                        .background(Paint::Role(ColorRole::SurfaceVariant))
                                        .shape_role(shape),
                                    alignment = Alignment::Center,
                                    {
                                        Text!(
                                            label,
                                            type_role = TypeRole::Caption,
                                            color = Paint::Role(ColorRole::OnSurfaceVariant)
                                        );
                                    }
                                );
                            });
                        }
                    }
                );
            });

            block("Raised", || {
                Row!(
                    modifier = Modifier.fill_max_width(),
                    space_role = SpaceRole::Md,
                    {
                        for step in [0.0_f32, 2.0, 8.0] {
                            key(format!("{step}"), || {
                                Surface!(
                                    modifier = Modifier
                                        .weight(1.0)
                                        .height(72.0)
                                        .elevation(step)
                                        .shape_role(ShapeRole::Medium),
                                    {
                                        Box!(
                                            modifier = Modifier.fill_max_width().fill_max_height(),
                                            alignment = Alignment::Center,
                                            {
                                                Text!(
                                                    format!("{step} dp"),
                                                    type_role = TypeRole::Caption,
                                                    color =
                                                        Paint::Role(ColorRole::OnSurfaceVariant)
                                                );
                                            }
                                        );
                                    }
                                );
                            });
                        }
                    }
                );
            });
        }
    );
}

/// The fills and the type ladder.
fn colour_group() {
    Column!(
        modifier = Modifier.fill_max_width(),
        space_role = SpaceRole::Lg,
        {
            block("Fills, and the ink that reads on each", || {
                Column!(
                    modifier = Modifier.fill_max_width(),
                    space_role = SpaceRole::Xs,
                    {
                        for (name, fill, ink) in SWATCHES {
                            key(name, || {
                                Column!(
                                    modifier = Modifier
                                        .fill_max_width()
                                        .background(Paint::Role(fill))
                                        .shape_role(ShapeRole::Medium)
                                        .padding_role(SpaceRole::Md),
                                    space_role = SpaceRole::Xs,
                                    {
                                        Text!(
                                            name,
                                            type_role = TypeRole::BodyStrong,
                                            color = Paint::Role(ink)
                                        );
                                        Text!(
                                            "The ink this fill promises to carry.",
                                            type_role = TypeRole::Caption,
                                            color = Paint::Role(ink)
                                        );
                                    }
                                );
                            });
                        }
                    }
                );
            });

            block("The type ladder", || {
                Surface!(
                    modifier = Modifier
                        .fill_max_width()
                        .shape_role(ShapeRole::Large)
                        .padding_role(SpaceRole::Md),
                    {
                        Column!(
                            modifier = Modifier.fill_max_width(),
                            space_role = SpaceRole::Xs,
                            {
                                for (name, rung) in LADDER {
                                    key(name, || {
                                        Text!(
                                            name,
                                            type_role = rung,
                                            max_lines = 1,
                                            overflow = TextOverflow::Ellipsis
                                        );
                                    });
                                }
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

    Scaffold!(
        modifier = Modifier.background(Paint::Role(ColorRole::Background)),
        top_bar = Some(|| {
            TopAppBar!(modifier = Modifier.fill_max_width(), {
                Text!(
                    "Minimal",
                    type_role = TypeRole::Title,
                    modifier = Modifier.weight(1.0)
                );
                Text!(
                    "one design, every platform",
                    type_role = TypeRole::Label,
                    color = Paint::Role(ColorRole::OnSurfaceVariant)
                );
            });
        }),
        {
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
                                    Column!(
                                        modifier = Modifier.fill_max_width(),
                                        alignment = Alignment::Center,
                                        space_role = SpaceRole::Sm,
                                        {
                                            Canvas!(
                                                hero_marks(HERO_SIDE),
                                                modifier = Modifier.size(HERO_SIDE, HERO_SIDE)
                                            );
                                            Text!(
                                                "Every component",
                                                type_role = TypeRole::Display,
                                                text_align = TextAlign::Center
                                            );
                                            Text!(
                                                "Including dark theme",
                                                type_role = TypeRole::Body,
                                                color = Paint::Role(ColorRole::OnSurfaceVariant),
                                                text_align = TextAlign::Center
                                            );
                                        }
                                    );

                                    Column!(
                                        modifier = Modifier.fill_max_width(),
                                        space_role = SpaceRole::Sm,
                                        {
                                            // Each child of the strip is one segment, and a
                                            // tap is that child's own click.
                                            Tabs!(
                                                selected.index(),
                                                modifier = Modifier.fill_max_width(),
                                                {
                                                    for choice in Group::STRIP {
                                                        let pick = group;
                                                        key(choice.label(), || {
                                                            Button!(
                                                                choice.label(),
                                                                move || pick.set(choice),
                                                                variant = ButtonVariant::Text,
                                                                color = Paint::Role(
                                                                    if choice == selected {
                                                                        ColorRole::OnSurface
                                                                    } else {
                                                                        ColorRole::OnSurfaceVariant
                                                                    }
                                                                )
                                                            );
                                                        });
                                                    }
                                                }
                                            );
                                            Text!(
                                                selected.caption(),
                                                type_role = TypeRole::Caption,
                                                color = Paint::Role(ColorRole::OnSurfaceVariant)
                                            );
                                        }
                                    );

                                    match selected {
                                        Group::Controls => controls_group(&controls),
                                        Group::Surfaces => surfaces_group(),
                                        Group::Colour => colour_group(),
                                    }
                                }
                            );
                        }
                    );
                }
            );
        }
    );
}
