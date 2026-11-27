//! The calculator sample's screen as it was when the benchmark scenarios were frozen: the
//! number at the foot of a reading area, a row of memory keys, four columns of keys, and
//! the tape beside them in a wide window.
//!
//! Measurement fixture. Taken from `samples/calculator/src/lib.rs` at fea50dc5, the last
//! commit before c4da9e5f and 67c730b5 redrew the calculator on 2026-11-13, and checked line
//! by line against the rsx! screen the Dioxus path measures (dioxus-compose dd1af76b,
//! `samples/native-widgets/calculator/src/lib.rs`, the same screen compose-rust held at
//! 22822c13). The two built the same tree; nothing here had to be changed to match it.
//! Builder chains became today's function and call macro form, and nothing else changed.
//!
//! It lives in the bench, not in `samples/`, because the comparison needs the same screen
//! on both paths. The Dioxus path is pinned with this screen, and the shipped sample has
//! since been redrawn after its design references, so it no longer builds the tree the
//! pinned path builds. What is measured here is the authoring path and the runtime under
//! this screen, not the look of today's sample. Change nothing that changes the tree it
//! builds: the comparison only counts runs where both paths build the same tree after
//! every step.
//!
//! The launch, the theme, the window and the platform entry points of the sample are left
//! out. The bench starts the root composable on a Host of its own and never opens a window.

use compose_rust::runtime::*;
use compose_rust::ui::*;
use std::rc::Rc;

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
#[Composable]
fn keypad(apple: bool, press: Press) {
    Column!(
        modifier = Modifier.fill_max_width().fill_max_height(),
        space_role = SpaceRole::Xs,
        {
            for (index, row) in if apple { APPLE_ROWS } else { ROWS }.iter().enumerate() {
                key(format!("row-{index}"), || {
                    Row!(
                        modifier = Modifier.fill_max_width().weight(1.0),
                        space_role = SpaceRole::Xs,
                        {
                            for label in row.iter().copied() {
                                let press = Rc::clone(&press);
                                key(label, || {
                                    Button!(
                                        label,
                                        move || press(label),
                                        modifier = Modifier.weight(1.0).fill_max_height(),
                                        variant = variant_for(label),
                                        color = color_for(label)
                                    );
                                });
                            }
                        }
                    );
                });
            }
        }
    );
}

/// The memory keys, as the row the Windows and Deepin references give them.
#[Composable]
fn memory_row(memory_set: bool, press: Press) {
    Row!(
        modifier = Modifier.fill_max_width(),
        space_role = SpaceRole::Xs,
        alignment = Alignment::CenterStart,
        {
            for label in engine::MEMORY_KEYS {
                let press = Rc::clone(&press);
                key(label, || {
                    // Recalling and clearing act on what is stored, so they are dead keys
                    // until something is.
                    Button!(
                        label,
                        move || press(label),
                        modifier = Modifier.weight(1.0),
                        variant = ButtonVariant::Text,
                        color = Paint::Role(ColorRole::OnSurfaceVariant),
                        enabled = memory_set || !matches!(label, "MC" | "MR")
                    );
                });
            }
        }
    );
}

/// The readout: what is being worked out, and what it comes to.
#[Composable]
fn readout(status: String, display: String) {
    Box!(modifier = Modifier.fill_max_width(), {
        Column!(
            modifier = Modifier.fill_max_width(),
            space_role = SpaceRole::Xs,
            {
                // The pending operation is a line only while there is one.
                if !status.is_empty() {
                    Text!(
                        status.clone(),
                        modifier = Modifier.fill_max_width(),
                        text_align = TextAlign::End,
                        type_role = TypeRole::Caption,
                        color = Paint::Role(ColorRole::OnSurfaceVariant),
                        max_lines = 1,
                        overflow = TextOverflow::Ellipsis
                    );
                }
                Text!(
                    display.clone(),
                    modifier = Modifier.fill_max_width(),
                    text_align = TextAlign::End,
                    type_role = TypeRole::Display,
                    color = Paint::Role(ColorRole::OnSurface),
                    max_lines = 1,
                    overflow = TextOverflow::Ellipsis
                );
            }
        );
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
#[Composable]
fn tape(entries: Vec<TapeEntry>, recall: Rc<dyn Fn(f64)>, clear: Rc<dyn Fn()>) {
    Column!(
        modifier = Modifier.fill_max_width().fill_max_height(),
        space_role = SpaceRole::Sm,
        {
            Row!(
                modifier = Modifier.fill_max_width(),
                space_role = SpaceRole::Sm,
                alignment = Alignment::CenterStart,
                {
                    Text!(
                        TAPE_LABEL,
                        type_role = TypeRole::Subtitle,
                        modifier = Modifier.weight(1.0)
                    );
                    // Throwing the tape away is the one destructive thing here, so it is
                    // the error ink and it offers the tape back afterwards.
                    let clear = Rc::clone(&clear);
                    Button!(
                        "Clear",
                        move || clear(),
                        variant = ButtonVariant::Text,
                        color = Paint::Role(ColorRole::Error),
                        enabled = !entries.is_empty()
                    );
                }
            );
            Separator!(None);
            if entries.is_empty() {
                Box!(
                    modifier = Modifier.fill_max_width().weight(1.0),
                    alignment = Alignment::Center,
                    {
                        Text!(
                            "Nothing worked out yet. Press equals and it lands here.",
                            type_role = TypeRole::Body,
                            text_align = TextAlign::Center,
                            color = Paint::Role(ColorRole::OnSurfaceVariant)
                        );
                    }
                );
            } else {
                // Newest first: the thing most likely wanted back is the last one.
                ScrollColumn!(modifier = Modifier.fill_max_width().weight(1.0), {
                    for entry in entries.iter().rev() {
                        let recall = Rc::clone(&recall);
                        let value = entry.value;
                        key(entry.id, || {
                            Button!(
                                format!("{} = {}", entry.expression, entry.result),
                                move || recall(value),
                                modifier = Modifier.fill_max_width(),
                                variant = ButtonVariant::Text,
                                color = Paint::Role(ColorRole::OnSurface)
                            );
                        });
                    }
                });
            }
        }
    );
}

/// How the reading area and the keypad share the instrument's height.
const READING_SHARE: f32 = 1.0;
const KEYPAD_SHARE: f32 = 3.0;

/// How the instrument and the tape share an expanded window.
const INSTRUMENT_SHARE: f32 = 3.0;
const TAPE_SHARE: f32 = 2.0;

/// The calculator.
#[Composable]
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
        let (calculator, entries, next_entry) = (calculator, entries, next_entry);
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
        let (calculator, tape_open) = (calculator, tape_open);
        Rc::new(move |value: f64| {
            calculator.update(|state| state.recall(value));
            tape_open.set(false);
        })
    };
    let clear_tape: Rc<dyn Fn()> = {
        Rc::new(move || {
            let thrown_away = entries.get_untracked();
            entries.update(Vec::clear);
            let lines = if thrown_away.len() == 1 {
                "line"
            } else {
                "lines"
            };
            let restore = entries;
            Message::new(format!("Cleared {} {lines}", thrown_away.len()))
                .with_action("Undo", move |()| restore.set(thrown_away.clone()))
                .with_duration(MessageDuration::Long)
                .show();
        })
    };

    Scaffold!(
        top_bar = Some(|| {
            TopAppBar!(modifier = Modifier.fill_max_width(), {
                Button!(
                    "",
                    || {},
                    icon = IconRole::Menu,
                    variant = ButtonVariant::Text
                );
                if apple {
                    Spacer!(modifier = Modifier.weight(1.0));
                } else {
                    Text!(
                        "Standard",
                        type_role = TypeRole::Title,
                        modifier = Modifier.weight(1.0)
                    );
                }
                if !tape_beside {
                    let open = tape_open;
                    Button!(
                        "",
                        move || open.set(true),
                        icon = IconRole::History,
                        variant = ButtonVariant::Text
                    );
                }
            });
        }),
        {
            Row!(modifier = Modifier.fill_max_width().fill_max_height(), {
                Box!(
                    modifier = Modifier.weight(INSTRUMENT_SHARE).fill_max_height(),
                    alignment = Alignment::TopCenter,
                    {
                        Column!(
                            modifier = Modifier
                                .fill_max_width_if(pad_width.is_none())
                                .width_if(pad_width)
                                .fill_max_height()
                                .padding_role(SpaceRole::Md),
                            space_role = SpaceRole::Md,
                            {
                                Box!(
                                    modifier = Modifier.fill_max_width().weight(READING_SHARE),
                                    alignment = Alignment::BottomCenter,
                                    {
                                        readout(status.clone(), display.clone());
                                    }
                                );
                                if !apple {
                                    memory_row(memory_set, Rc::clone(&press));
                                }
                                Box!(
                                    modifier = Modifier.fill_max_width().weight(KEYPAD_SHARE),
                                    alignment = Alignment::BottomCenter,
                                    {
                                        keypad(apple, Rc::clone(&press));
                                    }
                                );
                            }
                        );
                    }
                );
                if tape_beside {
                    Divider!(vertical = true);
                    Column!(
                        modifier = Modifier
                            .weight(TAPE_SHARE)
                            .fill_max_height()
                            .padding_role(SpaceRole::Md),
                        {
                            tape(entries.get(), Rc::clone(&recall), Rc::clone(&clear_tape));
                        }
                    );
                }
            });

            // The same tape, arriving from an edge, for windows with no room beside the
            // keys.
            let close = tape_open;
            Sheet!(
                tape_open.get() && !tape_beside,
                move || close.set(false),
                modifier = Modifier.fill_max_width(),
                {
                    if !tape_beside {
                        tape(entries.get(), Rc::clone(&recall), Rc::clone(&clear_tape));
                    }
                }
            );
        }
    );
}
