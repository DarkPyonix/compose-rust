//! The calculator sample, written with compose-rust's own API.
//!
//! The same screen as `samples/calculator`, call for call: the same frame, the same keys,
//! the same tape, the same roles. Only the authoring differs. State lives in
//! `mutable_state_of` rather than signals, screens are `#[composable]` functions rather
//! than components, and a widget is a statement rather than an element in `rsx!`. The
//! arithmetic is the other sample's own engine, compiled into this one from where it is.

use compose_rust::runtime::*;
use compose_rust::ui::*;
use std::rc::Rc;

#[path = "../../../calculator/src/engine.rs"]
mod engine;

use engine::Calculator;

/// The key grid, top to bottom and left to right.
const ROWS: [[&str; 4]; 5] = [
    ["C", "\u{232b}", "%", "\u{00f7}"],
    ["7", "8", "9", "\u{00d7}"],
    ["4", "5", "6", "\u{2212}"],
    ["1", "2", "3", "+"],
    ["\u{00b1}", "0", ".", "="],
];

/// Apple's pad: erase and clear everything in the top row, and no memory keys.
const APPLE_ROWS: [[&str; 4]; 5] = [
    ["\u{232b}", "AC", "%", "\u{00f7}"],
    ["7", "8", "9", "\u{00d7}"],
    ["4", "5", "6", "\u{2212}"],
    ["1", "2", "3", "+"],
    ["\u{00b1}", "0", ".", "="],
];

/// Equals is the accent, operators the operator role, function keys outlined, and digits
/// the quiet filled keys most of the pad is made of.
fn variant_for(label: &str) -> ButtonVariant {
    match label {
        "=" => ButtonVariant::Filled,
        "\u{00f7}" | "\u{00d7}" | "\u{2212}" | "+" => ButtonVariant::Operator,
        "C" | "\u{232b}" | "%" | "\u{00b1}" => ButtonVariant::Outlined,
        _ => ButtonVariant::Tonal,
    }
}

/// Whether a key carries content rather than a command: the digits and the point.
fn is_content_key(label: &str) -> bool {
    matches!(
        label,
        "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" | "."
    )
}

/// The label ink for a key, or `None` to let the variant decide.
fn color_for(label: &str) -> Option<Paint> {
    is_content_key(label).then_some(Paint::Role(ColorRole::OnSurface))
}

/// What a key press does, whichever key it was.
type Press = Rc<dyn Fn(&'static str)>;

/// The keypad, in the shape this platform's calculator has.
#[composable]
fn keypad(apple: bool, press: Press) {
    Column()
        .modifier(Modifier.fill_max_width().fill_max_height())
        .space_role(SpaceRole::Xs)
        .content(|| {
            for (index, row) in if apple { APPLE_ROWS } else { ROWS }.iter().enumerate() {
                key(format!("row-{index}"), || {
                    Row()
                        .modifier(Modifier.fill_max_width().weight(1.0))
                        .space_role(SpaceRole::Xs)
                        .content(|| {
                            for label in row.iter().copied() {
                                let press = Rc::clone(&press);
                                key(label, || {
                                    Button(label)
                                        .modifier(Modifier.weight(1.0).fill_max_height())
                                        .variant(variant_for(label))
                                        .color_if(color_for(label))
                                        .on_click(move || press(label));
                                });
                            }
                        });
                });
            }
        });
}

/// The memory keys, as the row the Windows and Deepin references give them.
#[composable]
fn memory_row(memory_set: bool, press: Press) {
    Row()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Xs)
        .alignment(Alignment::CenterStart)
        .content(|| {
            for label in engine::MEMORY_KEYS {
                let press = Rc::clone(&press);
                key(label, || {
                    Button(label)
                        .modifier(Modifier.weight(1.0))
                        .variant(ButtonVariant::Text)
                        .color(Paint::Role(ColorRole::OnSurfaceVariant))
                        // Recalling and clearing act on what is stored, so they are dead
                        // keys until something is.
                        .enabled(memory_set || !matches!(label, "MC" | "MR"))
                        .on_click(move || press(label));
                });
            }
        });
}

/// The readout: what is being worked out, and what it comes to.
#[composable]
fn readout(status: String, display: String) {
    Box().modifier(Modifier.fill_max_width()).content(|| {
        Column()
            .modifier(Modifier.fill_max_width())
            .space_role(SpaceRole::Xs)
            .content(|| {
                // The pending operation is a line only while there is one.
                if !status.is_empty() {
                    Text(status.clone())
                        .modifier(Modifier.fill_max_width())
                        .text_align(TextAlign::End)
                        .type_role(TypeRole::Caption)
                        .color(Paint::Role(ColorRole::OnSurfaceVariant))
                        .max_lines(1)
                        .overflow(TextOverflow::Ellipsis);
                }
                Text(display.clone())
                    .modifier(Modifier.fill_max_width())
                    .text_align(TextAlign::End)
                    .type_role(TypeRole::Display)
                    .color(Paint::Role(ColorRole::OnSurface))
                    .max_lines(1)
                    .overflow(TextOverflow::Ellipsis);
            });
    });
}

/// One finished calculation, kept so it can be read back and used again.
#[derive(Clone, PartialEq)]
struct TapeEntry {
    id: u64,
    expression: String,
    result: String,
    value: f64,
}

/// What the app bar calls the tape.
const TAPE_LABEL: &str = "History";

/// The tape: what has been worked out, newest at the top.
#[composable]
fn tape(entries: Vec<TapeEntry>, recall: Rc<dyn Fn(f64)>, clear: Rc<dyn Fn()>) {
    Column()
        .modifier(Modifier.fill_max_width().fill_max_height())
        .space_role(SpaceRole::Sm)
        .content(|| {
            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Text(TAPE_LABEL)
                        .type_role(TypeRole::Subtitle)
                        .modifier(Modifier.weight(1.0));
                    // Throwing the tape away is the one destructive thing here, so it is
                    // the error ink and it offers the tape back afterwards.
                    let clear = Rc::clone(&clear);
                    Button("Clear")
                        .variant(ButtonVariant::Text)
                        .color(Paint::Role(ColorRole::Error))
                        .enabled(!entries.is_empty())
                        .on_click(move || clear());
                });
            Separator(None);
            if entries.is_empty() {
                Box()
                    .modifier(Modifier.fill_max_width().weight(1.0))
                    .alignment(Alignment::Center)
                    .content(|| {
                        Text("Nothing worked out yet. Press equals and it lands here.")
                            .type_role(TypeRole::Body)
                            .text_align(TextAlign::Center)
                            .color(Paint::Role(ColorRole::OnSurfaceVariant));
                    });
            } else {
                // Newest first: the thing most likely wanted back is the last one.
                ScrollColumn()
                    .modifier(Modifier.fill_max_width().weight(1.0))
                    .content(|| {
                        for entry in entries.iter().rev() {
                            let recall = Rc::clone(&recall);
                            let value = entry.value;
                            key(entry.id, || {
                                Button(format!("{} = {}", entry.expression, entry.result))
                                    .modifier(Modifier.fill_max_width())
                                    .variant(ButtonVariant::Text)
                                    .color(Paint::Role(ColorRole::OnSurface))
                                    .on_click(move || recall(value));
                            });
                        }
                    });
            }
        });
}

/// How the reading area and the keypad share the instrument's height.
const READING_SHARE: f32 = 1.0;
const KEYPAD_SHARE: f32 = 3.0;

/// How the instrument and the tape share an expanded window.
const INSTRUMENT_SHARE: f32 = 3.0;
const TAPE_SHARE: f32 = 2.0;

/// The calculator.
#[composable]
pub fn app() {
    let window = current_window_size();
    // The one thing a role cannot answer: Apple's calculator has different keys.
    let apple = matches!(
        current_design_system(),
        DesignSystem::Cupertino | DesignSystem::LiquidGlass
    );
    let calculator =
        remember(|| mutable_state_of_with_policy(Calculator::new(), never_equal_policy()));
    let entries = remember(|| mutable_state_of(Vec::<TapeEntry>::new()));
    let next_entry = remember(|| mutable_state_of(1_u64));
    let tape_open = remember(|| mutable_state_of(false));

    let (display, status, memory_set) =
        calculator.with(|state| (state.display(), state.status(), state.memory_set()));
    let tape_beside = window.is_expanded();
    let pad_width = (!window.is_compact()).then_some(WindowSizeClass::MEDIUM_MIN_WIDTH_DP);

    // One key press, whichever key it was. The tape is written here rather than in the
    // engine, because a tape is a thing the application keeps.
    let press: Press = {
        let (calculator, entries, next_entry) =
            (calculator.clone(), entries.clone(), next_entry.clone());
        Rc::new(move |label: &'static str| {
            let mut finished = None;
            calculator.update(|state| {
                state.press(label);
                finished = state.take_completed();
            });
            let Some(finished) = finished else { return };
            if finished.failed {
                Message::new(format!("{} has no answer", finished.expression)).show();
                return;
            }
            let id = next_entry.get_untracked();
            next_entry.set(id + 1);
            entries.update(|entries| {
                entries.push(TapeEntry {
                    id,
                    expression: finished.expression,
                    result: finished.result,
                    value: finished.value,
                });
            });
        })
    };

    let recall: Rc<dyn Fn(f64)> = {
        let (calculator, tape_open) = (calculator.clone(), tape_open.clone());
        Rc::new(move |value: f64| {
            calculator.update(|state| state.recall(value));
            tape_open.set(false);
        })
    };
    let clear_tape: Rc<dyn Fn()> = {
        let entries = entries.clone();
        Rc::new(move || {
            let thrown_away = entries.get_untracked();
            entries.update(Vec::clear);
            let lines = if thrown_away.len() == 1 {
                "line"
            } else {
                "lines"
            };
            let restore = entries.clone();
            Message::new(format!("Cleared {} {lines}", thrown_away.len()))
                .with_action("Undo", move |()| restore.set(thrown_away.clone()))
                .with_duration(MessageDuration::Long)
                .show();
        })
    };

    Scaffold()
        .top_bar(|| {
            TopAppBar().modifier(Modifier.fill_max_width()).content(|| {
                Button("")
                    .icon(IconRole::Menu)
                    .variant(ButtonVariant::Text)
                    .on_click(|| {});
                if apple {
                    Spacer().modifier(Modifier.weight(1.0));
                } else {
                    Text("Standard")
                        .type_role(TypeRole::Title)
                        .modifier(Modifier.weight(1.0));
                }
                if !tape_beside {
                    let open = tape_open.clone();
                    Button("")
                        .icon(IconRole::History)
                        .variant(ButtonVariant::Text)
                        .on_click(move || open.set(true));
                }
            });
        })
        .content(|| {
            Row()
                .modifier(Modifier.fill_max_width().fill_max_height())
                .content(|| {
                    Box()
                        .modifier(Modifier.weight(INSTRUMENT_SHARE).fill_max_height())
                        .alignment(Alignment::TopCenter)
                        .content(|| {
                            Column()
                                .modifier(
                                    Modifier
                                        .fill_max_width_if(pad_width.is_none())
                                        .width_if(pad_width)
                                        .fill_max_height()
                                        .padding_role(SpaceRole::Md),
                                )
                                .space_role(SpaceRole::Md)
                                .content(|| {
                                    Box()
                                        .modifier(Modifier.fill_max_width().weight(READING_SHARE))
                                        .alignment(Alignment::BottomCenter)
                                        .content(|| readout(status.clone(), display.clone()));
                                    if !apple {
                                        memory_row(memory_set, Rc::clone(&press));
                                    }
                                    Box()
                                        .modifier(Modifier.fill_max_width().weight(KEYPAD_SHARE))
                                        .alignment(Alignment::BottomCenter)
                                        .content(|| keypad(apple, Rc::clone(&press)));
                                });
                        });
                    if tape_beside {
                        Divider().vertical(true);
                        Column()
                            .modifier(
                                Modifier
                                    .weight(TAPE_SHARE)
                                    .fill_max_height()
                                    .padding_role(SpaceRole::Md),
                            )
                            .content(|| {
                                tape(entries.get(), Rc::clone(&recall), Rc::clone(&clear_tape))
                            });
                    }
                });

            // The same tape, arriving from an edge, for windows with no room beside the
            // keys.
            let close = tape_open.clone();
            Sheet(tape_open.get() && !tape_beside)
                .on_dismiss(move || close.set(false))
                .modifier(Modifier.fill_max_width())
                .content(|| {
                    if !tape_beside {
                        tape(entries.get(), Rc::clone(&recall), Rc::clone(&clear_tape));
                    }
                });
        });
}

/// Runs the sample as a program of its own.
pub fn launch() {
    launch_builder().application(app);
}

/// How this sample is configured: the same window as the other calculator.
fn launch_builder() -> LaunchBuilder {
    LaunchBuilder::new()
        .with_theme(compose_rust::demo_theme())
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Calculator")
                .with_size(380, 620)
                .with_min_size(320, 480)
                .with_icon(compose_rust::asset::asset(
                    compose_rust::schema::AssetKind::Png,
                    include_bytes!("../../../calculator/assets/icon.png"),
                )),
        )
}

compose_rust::android_application!({ launch_builder() }, app);
compose_rust::web_application!({ launch_builder() }, app);
compose_rust::ios_main!(launch);
