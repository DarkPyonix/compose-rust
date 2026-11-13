//! A working desktop calculator, clone coded from the Windows, macOS and Deepin ones, with
//! the tape every desk calculator that had a printer used to have.
//!
//! The three references share a shape: what is being worked out and what it comes to,
//! right aligned at the foot of a reading area, and keys four across along the bottom of
//! the window. What they do not share is the keys. Windows clears with `C` and `CE` and has
//! a row of functions, macOS clears with `AC` and has no memory keys, and Deepin has a
//! bracket key where the others have a sign key. Those are different calculators rather
//! than one calculator restyled, so each design system gets the pad of the reference drawn
//! in it, and the systems without a reference get a plain one.
//!
//! Written with compose-rust's own API: state lives in `mutable_state_of`, screens are
//! `#[Composable]` functions, and a widget is a statement. No Kotlin and no protocol types.
//!
//! There is no keyboard entry. A keypad is pressed, and a text field kept on screen only to
//! hold the focus would be development scaffolding under the keypad, doing nothing a
//! calculator does.

use compose_rust::runtime::*;
use compose_rust::ui::*;
use compose_rust::window::WindowSize;
use std::rc::Rc;

mod engine;

use engine::{ALL_CLEAR, BRACKET, CLEAR_ENTRY, Calculator, RECIPROCAL, SQUARE, SQUARE_ROOT};

const BACKSPACE: &str = "\u{232b}";
const DIVIDE: &str = "\u{00f7}";
const MULTIPLY: &str = "\u{00d7}";
const MINUS: &str = "\u{2212}";
const SIGN: &str = "\u{00b1}";

/// Windows 11's standard pad: six rows, the top two of them functions.
const WINDOWS_PAD: &[[&str; 4]] = &[
    ["%", CLEAR_ENTRY, "C", BACKSPACE],
    [RECIPROCAL, SQUARE, SQUARE_ROOT, DIVIDE],
    ["7", "8", "9", MULTIPLY],
    ["4", "5", "6", MINUS],
    ["1", "2", "3", "+"],
    [SIGN, "0", ".", "="],
];

/// The macOS pad: erase and clear everything in the top row, and no memory keys.
const APPLE_PAD: &[[&str; 4]] = &[
    [BACKSPACE, ALL_CLEAR, "%", DIVIDE],
    ["7", "8", "9", MULTIPLY],
    ["4", "5", "6", MINUS],
    ["1", "2", "3", "+"],
    [SIGN, "0", ".", "="],
];

/// Deepin's pad: a bracket key at the foot where the others have a sign key.
const DEEPIN_PAD: &[[&str; 4]] = &[
    ["C", "%", BACKSPACE, DIVIDE],
    ["7", "8", "9", MULTIPLY],
    ["4", "5", "6", MINUS],
    ["1", "2", "3", "+"],
    ["0", ".", BRACKET, "="],
];

/// The pad of the systems no reference is drawn in: the keys all three references agree
/// on, in the order most calculators put them.
const PLAIN_PAD: &[[&str; 4]] = &[
    ["C", BACKSPACE, "%", DIVIDE],
    ["7", "8", "9", MULTIPLY],
    ["4", "5", "6", MINUS],
    ["1", "2", "3", "+"],
    [SIGN, "0", ".", "="],
];

/// What is left of a pad once its function keys stand in a column of their own: the
/// digits and the arithmetic, four rows of four, the way a desk calculator lays them out.
const DESK_GRID: [[&str; 4]; 4] = [
    ["7", "8", "9", DIVIDE],
    ["4", "5", "6", MULTIPLY],
    ["1", "2", "3", MINUS],
    ["0", ".", "=", "+"],
];

/// Which reference a design system is drawn after.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Reference {
    /// Windows 11's calculator, under Fluent 2.
    Windows,
    /// The macOS calculator, under Liquid Glass and Cupertino.
    Apple,
    /// Deepin's calculator.
    Deepin,
    /// Material 3, GNOME and Breeze, which no reference is drawn in.
    Plain,
}

impl Reference {
    /// The one thing a role cannot answer: which calculator this platform has.
    fn of(system: DesignSystem) -> Self {
        match system {
            DesignSystem::Fluent => Self::Windows,
            DesignSystem::Cupertino | DesignSystem::LiquidGlass => Self::Apple,
            DesignSystem::Deepin => Self::Deepin,
            _ => Self::Plain,
        }
    }

    fn pad(self) -> &'static [[&'static str; 4]] {
        match self {
            Self::Windows => WINDOWS_PAD,
            Self::Apple => APPLE_PAD,
            Self::Deepin => DEEPIN_PAD,
            Self::Plain => PLAIN_PAD,
        }
    }

    /// The Windows and the Deepin references print a row of memory keys under the
    /// reading area. The macOS one does not, and a system with no reference has nothing
    /// to copy one from.
    fn has_memory_row(self) -> bool {
        matches!(self, Self::Windows | Self::Deepin)
    }

    /// Windows prints the memory keys as words with no key around them, Deepin as small
    /// filled keys.
    fn memory_variant(self) -> ButtonVariant {
        match self {
            Self::Deepin => ButtonVariant::Tonal,
            _ => ButtonVariant::Text,
        }
    }

    /// The macOS reference prints what is being worked out at a little over half the
    /// size of the answer. Windows prints it small and grey.
    fn expression_role(self) -> TypeRole {
        match self {
            Self::Apple => TypeRole::Title,
            _ => TypeRole::Caption,
        }
    }
}

/// Whether a key carries content rather than a command: the digits and the point.
fn is_content_key(label: &str) -> bool {
    matches!(
        label,
        "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" | "."
    )
}

fn is_operator(label: &str) -> bool {
    matches!(label, DIVIDE | MULTIPLY | MINUS | "+")
}

/// The keys that do something to the entry rather than enter it or combine it: clearing,
/// erasing, the sign, the percentage, Windows' functions and Deepin's bracket.
fn is_function_key(label: &str) -> bool {
    !is_content_key(label) && !is_operator(label) && label != "="
}

/// A pad's function keys, in the order the pad prints them.
fn function_keys(pad: &[[&'static str; 4]]) -> Vec<&'static str> {
    pad.iter()
        .flatten()
        .copied()
        .filter(|label| is_function_key(label))
        .collect()
}

/// Equals is the accent, operators the operator role, and every other key the quiet
/// filled key most of the pad is made of. All three references fill the function keys
/// too: none of them draws a key as an outline.
fn variant_for(label: &str) -> ButtonVariant {
    if label == "=" {
        ButtonVariant::Filled
    } else if is_operator(label) {
        ButtonVariant::Operator
    } else {
        ButtonVariant::Tonal
    }
}

/// The label ink for a key, or `None` to let the variant decide. The neutral keys print
/// their label in the page's ink in every reference, where some systems' tonal key would
/// print it in the accent.
fn color_for(label: &str) -> Option<Paint> {
    (variant_for(label) == ButtonVariant::Tonal).then_some(Paint::Role(ColorRole::OnSurface))
}

/// What a key press does, whichever key it was.
type Press = Rc<dyn Fn(&'static str)>;

/// How many rows the wide keypad has: the four of `DESK_GRID`, which the function
/// columns match.
const WIDE_ROWS: usize = DESK_GRID.len();

/// The shape of the keypad, in keys: rows down and columns across.
fn keypad_shape(reference: Reference, wide: bool) -> (usize, usize) {
    if wide {
        let functions = function_keys(reference.pad()).len();
        (
            WIDE_ROWS,
            DESK_GRID[0].len() + functions.div_ceil(WIDE_ROWS),
        )
    } else {
        (reference.pad().len(), reference.pad()[0].len())
    }
}

/// One key of the pad.
fn pad_key(label: &'static str, modifier: Modifier, press: Press) {
    Button(label)
        .modifier(modifier)
        .variant(variant_for(label))
        .kind(ButtonKind::ActionKey)
        .color_if(color_for(label))
        .on_click(move || press(label));
}

/// Rows of keys, each row sharing the height and each key sharing its row.
#[Composable]
fn key_rows(rows: Vec<[&'static str; 4]>, weight: f32, press: Press) {
    Column()
        .modifier(Modifier.weight(weight).fill_max_height())
        .space_role(SpaceRole::Xs)
        .content(|| {
            for (index, row) in rows.iter().enumerate() {
                key(format!("row-{index}"), || {
                    Row()
                        .modifier(Modifier.fill_max_width().weight(1.0))
                        .space_role(SpaceRole::Xs)
                        .content(|| {
                            for label in row.iter().copied() {
                                let press = Rc::clone(&press);
                                key(label, || {
                                    pad_key(label, Modifier.weight(1.0).fill_max_height(), press);
                                });
                            }
                        });
                });
            }
        });
}

/// The keypad, in the shape this platform's calculator has.
///
/// Narrow, it is the reference's own pad: the function keys share rows with the
/// operators. Wide, the function keys stand in columns of their own beside a grid of
/// digits and arithmetic, which is how a calculator uses a wider window without
/// stretching every key across it.
#[Composable]
fn keypad(reference: Reference, wide: bool, press: Press) {
    Row()
        .modifier(Modifier.fill_max_width().fill_max_height())
        .space_role(SpaceRole::Xs)
        .content(|| {
            if wide {
                let functions = function_keys(reference.pad());
                for (index, column) in functions.chunks(WIDE_ROWS).enumerate() {
                    key(format!("functions-{index}"), || {
                        Column()
                            .modifier(Modifier.weight(1.0).fill_max_height())
                            .space_role(SpaceRole::Xs)
                            .content(|| {
                                for label in column.iter().copied() {
                                    let press = Rc::clone(&press);
                                    key(label, || {
                                        pad_key(
                                            label,
                                            Modifier.weight(1.0).fill_max_width(),
                                            press,
                                        );
                                    });
                                }
                            });
                    });
                }
                key_rows(
                    DESK_GRID.to_vec(),
                    DESK_GRID[0].len() as f32,
                    Rc::clone(&press),
                );
            } else {
                key_rows(
                    reference.pad().to_vec(),
                    reference.pad()[0].len() as f32,
                    Rc::clone(&press),
                );
            }
        });
}

/// The memory keys, as the row the Windows and Deepin references give them.
#[Composable]
fn memory_row(reference: Reference, memory_set: bool, press: Press) {
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
                        .variant(reference.memory_variant())
                        // Recalling, clearing and listing act on what is stored, so they
                        // are dead keys until something is. Both references grey them.
                        .enabled(memory_set || !matches!(label, "MC" | "MR" | "M\u{25be}"))
                        .on_click(move || press(label));
                });
            }
        });
}

/// The readout: what is being worked out, and what it comes to.
#[Composable]
fn readout(reference: Reference, status: String, display: String) {
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
                        .type_role(reference.expression_role())
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
#[Composable]
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

/// How the reading area and the keypad share the instrument's height while the keys are
/// under finger size: the keypad three times the reading area, which is the proportion
/// of the Windows and Deepin references (macOS is nearer two and a half).
const READING_SHARE: f32 = 1.0;
const KEYPAD_SHARE: f32 = 3.0;

/// How the instrument and the tape share an expanded window.
const INSTRUMENT_SHARE: f32 = 3.0;
const TAPE_SHARE: f32 = 2.0;

/// The largest a key grows, either way, in dp.
///
/// A key is pressed with one fingertip. An adult fingertip is 16 to 20 mm across, and a
/// dp is a 160th of an inch, so 112 dp is 17.8 mm: a key past this is wider than the
/// thing pressing it, and the window's extra room is better spent on the reading area.
/// No reference grows its keys past it either: the Windows, Deepin and macOS keys are all
/// smaller at their default window sizes.
const KEY_MAX_DP: f32 = 112.0;

/// How the instrument is laid out in a window.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Proportions {
    /// The keypad's share of the height against `READING_SHARE`.
    keypad_share: f32,
    /// The instrument's width, or `None` to fill the window.
    width: Option<f32>,
}

/// Holds the keys under `KEY_MAX_DP` in both directions.
///
/// The height is worked out from the window's, which is more than the instrument gets
/// once the bar is drawn, so a key comes out a little under the cap rather than over it.
/// The width outside a phone-shaped window is the narrowest the instrument can be in its
/// size class, so it never overflows a window that is still in that class.
fn proportions(window: WindowSize, rows: usize, columns: usize) -> Proportions {
    let tallest = rows as f32 * KEY_MAX_DP;
    let keypad_share = if window.height_dp > tallest {
        (tallest / (window.height_dp - tallest)).min(KEYPAD_SHARE)
    } else {
        KEYPAD_SHARE
    };

    let widest = columns as f32 * KEY_MAX_DP;
    let width = if window.is_compact() {
        (window.width_dp > widest).then_some(widest)
    } else if window.is_medium() {
        Some(widest.min(WindowSizeClass::MEDIUM_MIN_WIDTH_DP))
    } else {
        let room = WindowSizeClass::EXPANDED_MIN_WIDTH_DP * INSTRUMENT_SHARE
            / (INSTRUMENT_SHARE + TAPE_SHARE);
        Some(widest.min(room))
    };
    Proportions {
        keypad_share,
        width,
    }
}

/// The calculator.
#[Composable]
pub fn app() {
    let window = current_window_size();
    let reference = Reference::of(current_design_system());
    let calculator =
        remember(|| mutable_state_of_with_policy(Calculator::new(), never_equal_policy()));
    let entries = remember(|| mutable_state_of(Vec::<TapeEntry>::new()));
    let next_entry = remember(|| mutable_state_of(1_u64));
    let tape_open = remember(|| mutable_state_of(false));

    let (display, status, memory_set) =
        calculator.with(|state| (state.display(), state.status(), state.memory_set()));
    let tape_beside = window.is_expanded();
    // Anything wider than a phone takes the function keys out of the grid.
    let wide = !window.is_compact();
    let (rows, columns) = keypad_shape(reference, wide);
    let layout = proportions(window, rows, columns);

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

    Scaffold()
        .top_bar(|| {
            TopAppBar().modifier(Modifier.fill_max_width()).content(|| {
                // Each reference's own bar. Windows has its navigation button and the
                // mode's name; macOS has the sidebar the tape lives in; Deepin keeps its
                // menu in the window's title bar and puts nothing above the reading area.
                if reference == Reference::Windows {
                    Button("")
                        .icon(IconRole::Menu)
                        .variant(ButtonVariant::Text)
                        .on_click(|| {});
                    Text("Standard").type_role(TypeRole::Title);
                } else if reference == Reference::Apple && !tape_beside {
                    let open = tape_open;
                    Button("")
                        .icon(IconRole::Sidebar)
                        .variant(ButtonVariant::Text)
                        .on_click(move || open.set(true));
                }
                Spacer().modifier(Modifier.weight(1.0));
                if reference != Reference::Apple && !tape_beside {
                    let open = tape_open;
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
                                        .fill_max_width_if(layout.width.is_none())
                                        .width_if(layout.width)
                                        .fill_max_height()
                                        .padding_role(SpaceRole::Md),
                                )
                                .space_role(SpaceRole::Md)
                                .content(|| {
                                    // The reading area takes what the keys leave, and
                                    // the keys sit on the bottom edge of the window.
                                    Box()
                                        .modifier(Modifier.fill_max_width().weight(READING_SHARE))
                                        .alignment(Alignment::BottomCenter)
                                        .content(|| {
                                            readout(reference, status.clone(), display.clone())
                                        });
                                    if reference.has_memory_row() {
                                        memory_row(reference, memory_set, Rc::clone(&press));
                                    }
                                    Box()
                                        .modifier(
                                            Modifier.fill_max_width().weight(layout.keypad_share),
                                        )
                                        .alignment(Alignment::BottomCenter)
                                        .content(|| keypad(reference, wide, Rc::clone(&press)));
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
            let close = tape_open;
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

/// How this sample is configured, in one place because three entry points need it.
///
/// A calculator is a tall narrow window on all three of the machines this is a rebuild of:
/// the keys are a grid four across. A wider window takes the function keys out of the
/// grid and puts the tape beside the keys, and past that it goes to the reading area
/// rather than to bigger keys.
fn launch_builder() -> LaunchBuilder {
    LaunchBuilder::new()
        // The same as [bundle] identifier in Dioxus.toml. It names the storage.
        .identifier("dev.darkpyonix.composerust.samples.calculator")
        .with_theme(compose_rust::demo_theme())
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Calculator")
                .with_size(380, 620)
                .with_min_size(320, 480)
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
    use compose_rust::schema::Modifier as Wire;
    use compose_rust::schema::{EventPayload, MessageDuration, PropertyKind, TypeRole, WidgetKind};
    use sample_frames::SYSTEMS;
    use std::collections::HashMap;

    /// Whether a label belongs to the keypad rather than to the rest of the screen.
    ///
    /// The screen holds buttons that are not keys, and a test about the shape of the
    /// keypad has to be able to say so: the tape's own controls appearing in a count of
    /// keys is how a keypad test starts failing for a reason that has nothing to do with
    /// the keypad.
    fn is_key(label: &str) -> bool {
        [WINDOWS_PAD, APPLE_PAD, DEEPIN_PAD, PLAIN_PAD]
            .iter()
            .flat_map(|pad| pad.iter().flatten())
            .chain(DESK_GRID.iter().flatten())
            .any(|key| *key == label)
    }

    fn rows_of(rows: &[[&str; 4]]) -> Vec<Vec<String>> {
        rows.iter()
            .map(|row| row.iter().map(|label| (*label).to_owned()).collect())
            .collect()
    }

    /// The groups of keys the wide keypad should hold: the pad's function keys in columns
    /// of four, then the desk grid's rows.
    fn wide_groups(reference: Reference) -> Vec<Vec<String>> {
        function_keys(reference.pad())
            .chunks(WIDE_ROWS)
            .map(|column| column.iter().map(|label| (*label).to_owned()).collect())
            .chain(rows_of(&DESK_GRID))
            .collect()
    }

    /// The windows the layout tests look through: the three the recordings use, and
    /// taller and wider ones, which is where a key that keeps growing shows.
    const WINDOWS: [(f32, f32); 8] = [
        (420.0, 780.0),
        (420.0, 1600.0),
        (560.0, 780.0),
        (760.0, 780.0),
        (760.0, 1600.0),
        (1180.0, 780.0),
        (1180.0, 1600.0),
        (2400.0, 1600.0),
    ];

    /// Each reference's own pad, at a phone's width.
    ///
    /// Windows clears with `CE` and `C` and has a row of functions, macOS clears with `AC`
    /// and has no `C`, and Deepin has a bracket key where the others have a sign. If two of
    /// these ever hold the same keys, the branch that chooses between them has stopped
    /// meaning anything and the screen has quietly become one screen again.
    #[test]
    fn fr22_each_reference_has_its_own_pad() {
        assert_eq!(WINDOWS_PAD[0], ["%", "CE", "C", "\u{232b}"]);
        assert_eq!(
            WINDOWS_PAD[1],
            ["1/x", "x\u{00b2}", "\u{00b2}\u{221a}x", "\u{00f7}"]
        );
        assert_eq!(WINDOWS_PAD[5], ["\u{00b1}", "0", ".", "="]);
        assert_eq!(APPLE_PAD[0], ["\u{232b}", "AC", "%", "\u{00f7}"]);
        assert!(
            !APPLE_PAD.iter().flatten().any(|label| *label == "C"),
            "Apple's calculator clears with AC and has no C",
        );
        assert_eq!(DEEPIN_PAD[0], ["C", "%", "\u{232b}", "\u{00f7}"]);
        assert_eq!(DEEPIN_PAD[4], ["0", ".", "( )", "="]);
        assert!(
            !DEEPIN_PAD
                .iter()
                .flatten()
                .any(|label| *label == "\u{00b1}"),
            "Deepin's calculator has no sign key",
        );

        for system in SYSTEMS {
            let reference = Reference::of(system);
            let screen = Screen::with(system, 420.0, 780.0);
            assert_eq!(
                screen.key_groups(),
                rows_of(reference.pad()),
                "{system:?} does not draw the {reference:?} pad",
            );
        }
        assert_eq!(Reference::of(DesignSystem::Fluent), Reference::Windows);
        assert_eq!(Reference::of(DesignSystem::LiquidGlass), Reference::Apple);
        assert_eq!(Reference::of(DesignSystem::Cupertino), Reference::Apple);
        assert_eq!(Reference::of(DesignSystem::Deepin), Reference::Deepin);
    }

    /// Every key is pressed with something behind it. None of the three references draws
    /// a key as an outline: the function keys are filled like the digits, `AC` included,
    /// and every key is drawn in the system's own calculator-key shape.
    #[test]
    fn fr22_every_key_is_a_filled_key() {
        for system in SYSTEMS {
            for width in [420.0, 760.0] {
                let screen = Screen::with(system, width, 780.0);
                let keys = screen.keys();
                assert!(!keys.is_empty(), "{system:?} at {width} has no keys");
                for (node, label) in keys {
                    let variant = screen.variants.get(&node).copied();
                    let expected = if label == "=" {
                        ButtonVariant::Filled
                    } else if is_operator(&label) {
                        ButtonVariant::Operator
                    } else {
                        ButtonVariant::Tonal
                    };
                    assert_eq!(
                        variant,
                        Some(expected as i64),
                        "{label} under {system:?} at {width} is not a {expected:?} key",
                    );
                    assert_eq!(
                        screen.kinds.get(&node).copied(),
                        Some(ButtonKind::ActionKey as i64),
                        "{label} under {system:?} is not drawn as a calculator key",
                    );
                }
            }
        }
        let apple = Screen::with(DesignSystem::LiquidGlass, 420.0, 780.0);
        assert_eq!(
            apple.variant_of("AC"),
            apple.variant_of("%"),
            "AC is styled apart from the keys beside it",
        );
    }

    /// Fluent fills only equals with the accent, while macOS fills every operator and
    /// Deepin draws them as accent glyphs. The pad says `Operator` for the four of them
    /// and `Filled` for equals under every system, and the system draws the difference.
    #[test]
    fn fr22_equals_is_the_only_accent_key_on_fluent() {
        let fluent = Screen::with(DesignSystem::Fluent, 420.0, 780.0);
        assert_eq!(
            fluent.labels_with_variant(ButtonVariant::Filled),
            vec!["=".to_owned()],
            "Fluent fills equals and nothing else with the accent",
        );
        for system in [
            DesignSystem::Cupertino,
            DesignSystem::LiquidGlass,
            DesignSystem::Fluent,
            DesignSystem::Deepin,
        ] {
            let screen = Screen::with(system, 420.0, 780.0);
            let mut operators = screen.labels_with_variant(ButtonVariant::Operator);
            operators.sort();
            let mut expected: Vec<String> = [DIVIDE, MULTIPLY, MINUS, "+"]
                .iter()
                .map(|label| (*label).to_owned())
                .collect();
            expected.sort();
            assert_eq!(operators, expected, "{system:?} operators");
            assert_eq!(screen.variant_of("="), Some(ButtonVariant::Filled as i64));
        }
    }

    /// The reference screen reads from the top down: what is being worked out, what it
    /// comes to, the memory keys where the reference has them, then the pad. Windows and
    /// Deepin print a memory row; macOS does not, and the systems without a reference
    /// have nothing to copy one from.
    #[test]
    fn fr22_the_memory_row_stands_only_where_the_reference_has_one() {
        for system in SYSTEMS {
            let screen = Screen::with(system, 420.0, 780.0);
            let labels = screen.button_labels();
            let memory_at = labels
                .iter()
                .position(|label| label == engine::MEMORY_KEYS[0]);
            if !matches!(system, DesignSystem::Fluent | DesignSystem::Deepin) {
                assert_eq!(memory_at, None, "{system:?} has a memory row");
                continue;
            }
            let memory_at = memory_at.unwrap_or_else(|| panic!("{system:?} has no memory row"));
            assert_eq!(
                &labels[memory_at..memory_at + engine::MEMORY_KEYS.len()],
                engine::MEMORY_KEYS,
                "the memory keys are not the row the reference prints"
            );

            let instrument = screen.instrument();
            let row = screen.parents[&screen.button("MC")];
            assert_eq!(
                screen.children[&instrument.column],
                vec![instrument.reading, row, instrument.keypad],
                "under {system:?} the memory row does not stand between the reading area \
                 and the keys",
            );
        }
    }

    /// The memory keys do what they say on the screen, not only in the engine, and the
    /// ones that act on what is stored are dead until something is.
    #[test]
    fn fr22_the_memory_keys_store_and_recall_on_screen() {
        for system in [DesignSystem::Fluent, DesignSystem::Deepin] {
            let mut screen = Screen::with(system, 420.0, 780.0);
            assert!(!screen.enabled("MR"), "MR is live with nothing stored");
            for label in ["4", "2", "MS", "C"] {
                screen.press(label);
            }
            assert_eq!(screen.display(), "0");
            assert!(screen.enabled("MR"), "MR is dead with 42 stored");
            screen.press("MR");
            assert_eq!(
                screen.display(),
                "42",
                "{system:?} did not recall the memory"
            );
        }
    }

    /// Each reference's bar, and not Windows' bar under every system.
    #[test]
    fn fr22_the_top_bar_is_the_references_bar() {
        let fluent = Screen::with(DesignSystem::Fluent, 420.0, 780.0);
        assert!(fluent.has_icon_button(IconRole::Menu));
        assert!(fluent.stands_outside_a_sheet(WidgetKind::Text, "Standard"));
        assert!(fluent.has_icon_button(IconRole::History));

        for system in [DesignSystem::Cupertino, DesignSystem::LiquidGlass] {
            let apple = Screen::with(system, 420.0, 780.0);
            assert!(apple.has_icon_button(IconRole::Sidebar), "{system:?}");
            assert!(!apple.has_icon_button(IconRole::Menu), "{system:?}");
            assert!(!apple.has_icon_button(IconRole::History), "{system:?}");
            assert!(!apple.stands_outside_a_sheet(WidgetKind::Text, "Standard"));
        }

        for system in [
            DesignSystem::Deepin,
            DesignSystem::Material3,
            DesignSystem::Gnome,
            DesignSystem::Breeze,
        ] {
            let screen = Screen::with(system, 420.0, 780.0);
            assert!(!screen.has_icon_button(IconRole::Menu), "{system:?}");
            assert!(!screen.has_icon_button(IconRole::Sidebar), "{system:?}");
            assert!(
                !screen.stands_outside_a_sheet(WidgetKind::Text, "Standard"),
                "{system:?}"
            );
            // A phone still needs a way to the tape.
            assert!(screen.has_icon_button(IconRole::History), "{system:?}");
        }

        // Beside the keys, the tape needs no button to open it.
        for system in SYSTEMS {
            let wide = Screen::with(system, 1200.0, 780.0);
            assert!(!wide.has_icon_button(IconRole::History), "{system:?}");
            assert!(!wide.has_icon_button(IconRole::Sidebar), "{system:?}");
        }
    }

    /// The macOS calculator keeps its tape in the sidebar its bar button opens.
    #[test]
    fn fr22_the_apple_sidebar_button_opens_the_tape() {
        let mut screen = Screen::with(DesignSystem::LiquidGlass, 420.0, 780.0);
        assert!(
            !screen.sheet_open(),
            "the tape is open before anything asked"
        );
        assert!(screen.press_icon(IconRole::Sidebar));
        assert!(
            screen.sheet_open(),
            "the sidebar button did not open the tape"
        );
    }

    /// A phone keypad and a desk keypad are different keypads, not the same one scaled.
    /// Narrow, the function keys share rows with the operators. Wide, they are columns of
    /// their own beside a grid of digits and arithmetic, and the keys are the same keys.
    #[test]
    fn fr22_wider_windows_take_the_function_keys_out_of_the_grid() {
        for system in SYSTEMS {
            let reference = Reference::of(system);
            let narrow = Screen::with(system, 420.0, 780.0).key_groups();
            assert_eq!(narrow, rows_of(reference.pad()), "{system:?}");
            assert!(
                narrow.iter().any(|row| {
                    row.iter().any(|label| is_function_key(label))
                        && row.iter().any(|label| is_operator(label))
                }),
                "{system:?}: narrow, a function key shares a row with an operator",
            );

            for width in [760.0, 1180.0] {
                let wide = Screen::with(system, width, 780.0).key_groups();
                assert_eq!(wide, wide_groups(reference), "{system:?} at {width}");
                for group in &wide {
                    let functions = group.iter().filter(|label| is_function_key(label)).count();
                    assert!(
                        functions == 0 || functions == group.len(),
                        "{system:?} at {width}: {group:?} mixes function keys into the grid",
                    );
                }
                let mut narrow_keys: Vec<&String> = narrow.iter().flatten().collect();
                let mut wide_keys: Vec<&String> = wide.iter().flatten().collect();
                narrow_keys.sort();
                wide_keys.sort();
                assert_eq!(
                    narrow_keys, wide_keys,
                    "{system:?}: the keys changed with the shape"
                );
            }
        }
    }

    /// The keypad sits on the bottom edge and the reading area takes the rest, in the
    /// references' proportion: the keypad two to three times the reading area.
    #[test]
    fn fr22_the_keypad_takes_two_to_three_times_the_reading_area() {
        for system in SYSTEMS {
            let screen = Screen::with(system, 420.0, 780.0);
            let instrument = screen.instrument();
            let ratio = instrument.keypad_weight / instrument.reading_weight;
            assert!(
                (2.0..=3.0).contains(&ratio),
                "{system:?}: the keypad is {ratio} times the reading area",
            );
            assert_eq!(
                screen.children[&instrument.column].first(),
                Some(&instrument.reading),
                "{system:?}: the reading area is not at the top",
            );
            assert_eq!(
                screen.children[&instrument.column].last(),
                Some(&instrument.keypad),
                "{system:?}: the keypad is not on the bottom edge",
            );
        }
    }

    /// Keys stop growing at finger size however large the window gets. Past that the room
    /// goes to the reading area, and across it the instrument stops widening.
    ///
    /// The height bound is the keypad's share of the whole window, which is more than the
    /// keypad gets once the bar is drawn, so a key passing this is a key that is too big.
    #[test]
    fn fr22_keys_stop_growing_at_finger_size() {
        for system in SYSTEMS {
            let reference = Reference::of(system);
            for (width, height) in WINDOWS {
                let screen = Screen::with(system, width, height);
                let wide = width >= WindowSizeClass::MEDIUM_MIN_WIDTH_DP;
                let (rows, columns) = keypad_shape(reference, wide);
                assert_eq!(
                    screen.keys().len(),
                    rows * columns,
                    "{system:?} at {width}: the keypad is not {rows} by {columns}",
                );

                let instrument = screen.instrument();
                let share = instrument.keypad_weight
                    / (instrument.keypad_weight + instrument.reading_weight);
                let key_height = share * height / rows as f32;
                assert!(
                    key_height <= KEY_MAX_DP + 0.01,
                    "{system:?} at {width}x{height}: keys are {key_height} dp tall",
                );

                let across = match instrument.width {
                    Some(held) => held,
                    None => {
                        assert!(
                            !wide,
                            "{system:?} at {width}: a wide instrument is not held"
                        );
                        width
                    }
                };
                let key_width = across / columns as f32;
                assert!(
                    key_width <= KEY_MAX_DP + 0.01,
                    "{system:?} at {width}x{height}: keys are {key_width} dp wide",
                );
            }
        }
    }

    /// The tape stands beside the keypad where there is room and arrives from an edge
    /// where there is not. That is what this screen does with a wider window, and it was
    /// the one adaptation it had that nothing tested.
    #[test]
    fn fr20_the_tape_stands_beside_the_keypad_on_a_desktop_window() {
        let narrow = Screen::at(420.0);
        assert!(
            !narrow.stands_outside_a_sheet(WidgetKind::Text, TAPE_LABEL),
            "a phone should reach the tape through a sheet"
        );
        assert!(
            narrow.has_icon_button(IconRole::History),
            "a phone has no way to open the tape"
        );

        let wide = Screen::at(1200.0);
        assert!(
            wide.stands_outside_a_sheet(WidgetKind::Text, TAPE_LABEL),
            "a desktop window should stand the tape beside the keys"
        );
        assert!(
            !wide.has_icon_button(IconRole::History),
            "a button that opens what you are already looking at"
        );
    }

    #[test]
    fn every_key_has_a_variant_and_an_action() {
        for pad in [WINDOWS_PAD, APPLE_PAD, DEEPIN_PAD, PLAIN_PAD] {
            let mut calculator = Calculator::new();
            for label in pad.iter().flatten().copied() {
                let _ = variant_for(label);
                calculator.press(label);
            }
            assert!(!calculator.display().is_empty());
        }
    }

    #[test]
    fn fr22_operators_use_the_adaptive_operator_role_and_equals_stays_filled() {
        for label in ["\u{00f7}", "\u{00d7}", "\u{2212}", "+"] {
            assert_eq!(variant_for(label), ButtonVariant::Operator);
        }
        assert_eq!(variant_for("="), ButtonVariant::Filled);
    }

    /// The whole declared tree, checked against the encoder. A widget or an attribute the
    /// schema cannot carry fails the frame rather than the one node, so this is the test
    /// that would have caught it.
    #[test]
    fn the_declared_tree_encodes_without_a_protocol_error() {
        ComposeHost::new(app)
            .rebuild()
            .expect("the calculator tree encodes");
    }

    /// The calculator's screen, under every design system, in both schemes, at all three
    /// window widths.
    ///
    /// A batch that encodes is not the same as a screen someone can read. Material 3
    /// shipped a readout filled with a colour that matched the page behind it, drawn full
    /// size, in the right colour and invisible, and every assertion in this file passed
    /// the whole time. The only thing that settles it is looking.
    ///
    /// So `DXC_FRAME_DIR` writes the frames out, exactly as the Renderer decodes them, and
    /// the Renderer's screenshot test turns each one into a picture. Unset, which is the
    /// normal run, this still builds all thirty-six: a width or a design system nobody can
    /// encode is the failure the tables exist to avoid.
    #[test]
    fn fr14_the_screen_is_recorded_under_every_design_system_and_width() {
        sample_frames::record("Calculator", app, |_| {});
    }

    #[test]
    fn display_is_formatted_not_raw() {
        assert_eq!(engine::format_number(1.0 / 3.0), "0.333333333333");
    }

    /// Tells the Host which design system the Renderer resolved, before the screen under
    /// test is built.
    ///
    /// A Host learns its design system from the Renderer, once, after its first frame. A
    /// screen built first and told afterwards is built twice, and the second build creates
    /// what the first did not have in an order that is not the order the screen declares
    /// it. The answer is kept for the thread, so a throwaway Host takes it and the screen
    /// built next starts from it.
    fn resolve_design_system(system: DesignSystem) {
        fn nothing() {}
        compose_rust::design::reset_design_system();
        let mut primer = ComposeHost::new(nothing);
        primer.rebuild().expect("an empty screen failed to encode");
        let mut event = Vec::new();
        encode_event(
            &HostEvent {
                node_id: 0,
                handler_id: 0,
                payload: EventPayload::DesignSystemResolved(system),
            },
            &mut event,
        )
        .expect("the design system did not encode");
        primer
            .dispatch_event(&event)
            .expect("the design system did not reach the Host");
    }

    /// Where the instrument's parts sit in the tree, and what it was given.
    struct Instrument {
        /// The column holding the reading area, the memory row and the keypad.
        column: u32,
        reading: u32,
        keypad: u32,
        reading_weight: f32,
        keypad_weight: f32,
        /// The width the column is held to, or `None` when it fills the window.
        width: Option<f32>,
    }

    /// The real screen, driven the way the Renderer drives it: an encoded event naming the
    /// button's own handler, and the display read back out of the frame that came of it.
    /// Calling the engine directly would skip everything that can actually break between a
    /// finger and a number on screen.
    ///
    /// The tables are kept up to date as frames arrive rather than read once, because the
    /// screen grows: a tape line is a button that did not exist when the screen was built,
    /// and a harness that only looked at the first frame could never press one.
    struct Screen {
        host: ComposeHost,
        /// Node to the label printed on it.
        texts: HashMap<u32, String>,
        /// What each live node was created as, so a test can ask where something sits.
        widgets: HashMap<u32, WidgetKind>,
        /// The buttons in the order they were created, which is the order the screen
        /// declares them.
        buttons: Vec<u32>,
        /// The icon role of every node that carries one, for naming a button with no text.
        icons: HashMap<u32, u8>,
        /// Node to the handler its `on_click` was given.
        clicks: HashMap<u32, u64>,
        /// Node to the node it was inserted under, so a removal takes the subtree with it
        /// the way the Renderer's node table does. Without it a tape line that has been
        /// thrown away is still there as far as this harness can tell.
        parents: HashMap<u32, u32>,
        /// Node to its children, in the order the Renderer lays them out.
        children: HashMap<u32, Vec<u32>>,
        /// The type role of every text that carries one.
        type_roles: HashMap<u32, i64>,
        /// The variant and the kind of every button that names them.
        variants: HashMap<u32, i64>,
        kinds: HashMap<u32, i64>,
        enabled: HashMap<u32, bool>,
        /// Whether each sheet is open.
        opens: HashMap<u32, bool>,
        /// Each node's modifiers, by the slot they were set in.
        modifiers: HashMap<(u32, u16), Wire>,
        /// Every message the screen has said, in order, with its action label.
        messages: Vec<(String, String)>,
        event: Vec<u8>,
    }

    impl Screen {
        /// The screen under the design system a Host assumes before it is told one.
        fn new() -> Self {
            compose_rust::design::reset_design_system();
            Self::start(ComposeHost::new(app))
        }

        fn start(host: ComposeHost) -> Self {
            let mut screen = Self {
                host,
                texts: HashMap::new(),
                widgets: HashMap::new(),
                buttons: Vec::new(),
                icons: HashMap::new(),
                clicks: HashMap::new(),
                parents: HashMap::new(),
                children: HashMap::new(),
                type_roles: HashMap::new(),
                variants: HashMap::new(),
                kinds: HashMap::new(),
                enabled: HashMap::new(),
                opens: HashMap::new(),
                modifiers: HashMap::new(),
                messages: Vec::new(),
                event: Vec::new(),
            };
            let first = screen
                .host
                .rebuild()
                .expect("the first frame failed to encode")
                .to_vec();
            screen.absorb(&first);
            let _ = screen.display_node();
            screen
        }

        /// The screen under `system`, in a window of this size.
        fn with(system: DesignSystem, width_dp: f32, height_dp: f32) -> Self {
            compose_rust::window::reset_window_size();
            resolve_design_system(system);
            let mut screen = Self::start(ComposeHost::with_theme(app, Theme::unified(system)));
            screen.resize(width_dp, height_dp);
            screen
        }

        /// Applies one batch to the tables.
        fn absorb(&mut self, batch: &[u8]) {
            for mutation in decode_batch(batch).expect("the frame did not decode") {
                match mutation {
                    Mutation::Create { node_id, widget } => {
                        self.widgets.insert(node_id, widget);
                        if widget == WidgetKind::Button {
                            self.buttons.push(node_id);
                        }
                    }
                    Mutation::Insert {
                        parent_id,
                        node_id,
                        index,
                    }
                    | Mutation::Move {
                        parent_id,
                        node_id,
                        index,
                    } => self.place(parent_id, node_id, index),
                    Mutation::Remove { node_id } => self.forget(node_id),
                    Mutation::ShowMessage { text, action, .. } => {
                        self.messages.push((text.to_owned(), action.to_owned()));
                    }
                    Mutation::SetModifier {
                        node_id,
                        index,
                        modifier,
                    } => {
                        if modifier == Wire::Empty {
                            self.modifiers.remove(&(node_id, index));
                        } else {
                            self.modifiers.insert((node_id, index), modifier);
                        }
                    }
                    Mutation::SetProp {
                        node_id,
                        property,
                        value,
                    } => match (property, value) {
                        (PropertyKind::Text, PropertyValue::String(text)) => {
                            self.texts.insert(node_id, text.to_owned());
                        }
                        (PropertyKind::OnClick, PropertyValue::Integer(id)) => {
                            self.clicks.insert(node_id, id as u64);
                        }
                        // A button with no text is named by its icon, which is what the
                        // Renderer hands to a screen reader, so the tests look a button up
                        // the same way rather than by a glyph nobody types.
                        (PropertyKind::Icon, PropertyValue::Integer(role)) => {
                            self.icons.insert(node_id, role as u8);
                        }
                        (PropertyKind::TypeRole, PropertyValue::Integer(role)) => {
                            self.type_roles.insert(node_id, role);
                        }
                        (PropertyKind::Variant, PropertyValue::Integer(variant)) => {
                            self.variants.insert(node_id, variant);
                        }
                        (PropertyKind::ButtonKind, PropertyValue::Integer(kind)) => {
                            self.kinds.insert(node_id, kind);
                        }
                        (PropertyKind::Enabled, PropertyValue::Bool(enabled)) => {
                            self.enabled.insert(node_id, enabled);
                        }
                        (PropertyKind::Open, PropertyValue::Bool(open)) => {
                            self.opens.insert(node_id, open);
                        }
                        _ => {}
                    },
                    _ => {}
                }
            }
        }

        /// Puts a node under a parent at a position, taking it out of wherever it was.
        fn place(&mut self, parent: u32, node: u32, index: u32) {
            if let Some(previous) = self.parents.insert(node, parent) {
                if let Some(siblings) = self.children.get_mut(&previous) {
                    siblings.retain(|found| *found != node);
                }
            }
            let siblings = self.children.entry(parent).or_default();
            let at = (index as usize).min(siblings.len());
            siblings.insert(at, node);
        }

        /// Drops a node and everything under it.
        fn forget(&mut self, node_id: u32) {
            for child in self.children.remove(&node_id).unwrap_or_default() {
                self.forget(child);
            }
            if let Some(parent) = self.parents.remove(&node_id) {
                if let Some(siblings) = self.children.get_mut(&parent) {
                    siblings.retain(|found| *found != node_id);
                }
            }
            self.texts.remove(&node_id);
            self.clicks.remove(&node_id);
            self.widgets.remove(&node_id);
            self.icons.remove(&node_id);
            self.type_roles.remove(&node_id);
            self.variants.remove(&node_id);
            self.kinds.remove(&node_id);
            self.enabled.remove(&node_id);
            self.opens.remove(&node_id);
            self.modifiers.retain(|(node, _), _| *node != node_id);
            self.buttons.retain(|found| *found != node_id);
        }

        /// Reports a window of this size, the way the Renderer does.
        fn resize(&mut self, width_dp: f32, height_dp: f32) {
            self.dispatch(
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
                },
            );
        }

        /// The screen after the Renderer reports a window of this width.
        ///
        /// A resize is a diff rather than a fresh screen, so this keeps the tree and
        /// applies the change to it: reading the resize batch alone would show only what
        /// moved.
        fn at(width_dp: f32) -> Self {
            compose_rust::window::reset_window_size();
            let mut screen = Self::new();
            screen.resize(width_dp, 800.0);
            compose_rust::window::reset_window_size();
            screen
        }

        /// Every button label on screen, in the order the screen declares them.
        fn button_labels(&self) -> Vec<String> {
            self.buttons
                .iter()
                .filter_map(|node| self.texts.get(node).cloned())
                .collect()
        }

        /// The keys on screen, each with its node.
        fn keys(&self) -> Vec<(u32, String)> {
            self.buttons
                .iter()
                .filter_map(|node| {
                    let label = self.texts.get(node)?;
                    is_key(label).then(|| (*node, label.clone()))
                })
                .collect()
        }

        /// The button showing this label.
        fn button(&self, label: &str) -> u32 {
            self.buttons
                .iter()
                .copied()
                .find(|node| self.texts.get(node).map(String::as_str) == Some(label))
                .unwrap_or_else(|| panic!("the screen has no button labelled {label}"))
        }

        fn variant_of(&self, label: &str) -> Option<i64> {
            self.variants.get(&self.button(label)).copied()
        }

        fn enabled(&self, label: &str) -> bool {
            self.enabled
                .get(&self.button(label))
                .copied()
                .unwrap_or(true)
        }

        /// The labels of the buttons drawn in this variant, in the order they are declared.
        fn labels_with_variant(&self, variant: ButtonVariant) -> Vec<String> {
            self.buttons
                .iter()
                .filter(|node| self.variants.get(node) == Some(&(variant as i64)))
                .filter_map(|node| self.texts.get(node).cloned())
                .collect()
        }

        /// Whether any sheet is open.
        fn sheet_open(&self) -> bool {
            self.opens
                .iter()
                .any(|(node, open)| *open && self.widgets.get(node) == Some(&WidgetKind::Sheet))
        }

        /// The node and every node above it, nearest first.
        fn ancestors(&self, node: u32) -> Vec<u32> {
            let mut chain = vec![node];
            while let Some(parent) = self.parents.get(chain.last().expect("never empty")) {
                chain.push(*parent);
            }
            chain
        }

        /// The instrument: the column the display and the keys share, the reading area and
        /// the keypad it holds, and the weights and width they were given.
        fn instrument(&self) -> Instrument {
            let key = self.ancestors(self.button("7"));
            let display = self.ancestors(self.display_node());
            let at = key
                .iter()
                .position(|node| display.contains(node))
                .expect("the keys and the display share no container");
            let column = key[at];
            let keypad = key[at - 1];
            let reading = display[display.iter().position(|node| *node == column).unwrap() - 1];
            let weight = |node: u32| {
                self.modifiers
                    .iter()
                    .find_map(|((owner, _), modifier)| match modifier {
                        Wire::Weight(weight) if *owner == node => Some(*weight),
                        _ => None,
                    })
                    .unwrap_or_else(|| panic!("node {node} has no weight"))
            };
            let width = self
                .modifiers
                .iter()
                .find_map(|((owner, _), modifier)| match modifier {
                    Wire::Width(width) if *owner == column => Some(*width),
                    _ => None,
                });
            Instrument {
                column,
                reading,
                keypad,
                reading_weight: weight(reading),
                keypad_weight: weight(keypad),
                width,
            }
        }

        /// The keys as the keypad groups them, top to bottom and left to right: each row
        /// of a phone's pad, or each function column and then each grid row of a wide one.
        fn key_groups(&self) -> Vec<Vec<String>> {
            let mut groups = Vec::new();
            self.collect_groups(self.instrument().keypad, &mut groups);
            groups
        }

        fn collect_groups(&self, node: u32, groups: &mut Vec<Vec<String>>) {
            let Some(children) = self.children.get(&node) else {
                return;
            };
            let labels: Vec<String> = children
                .iter()
                .filter_map(|child| {
                    let label = self.texts.get(child)?;
                    (self.widgets.get(child) == Some(&WidgetKind::Button) && is_key(label))
                        .then(|| label.clone())
                })
                .collect();
            if !labels.is_empty() && labels.len() == children.len() {
                groups.push(labels);
                return;
            }
            for child in children {
                self.collect_groups(*child, groups);
            }
        }

        /// Whether a button carrying this icon stands on the screen.
        fn has_icon_button(&self, role: IconRole) -> bool {
            self.buttons
                .iter()
                .any(|node| self.icons.get(node) == Some(&(role as u8)))
        }

        /// Presses the button carrying this icon, and says whether there was one.
        fn press_icon(&mut self, role: IconRole) -> bool {
            let Some(node_id) = self
                .buttons
                .iter()
                .copied()
                .find(|node| self.icons.get(node) == Some(&(role as u8)))
            else {
                return false;
            };
            let handler = self.clicks[&node_id];
            self.dispatch(node_id, handler, EventPayload::Clicked);
            true
        }

        /// Whether anything of this kind carrying this text stands on the screen itself
        /// rather than inside a sheet.
        ///
        /// The kind matters: the tape's heading and the button that opens the tape say the
        /// same word, and a question about where the tape is would otherwise be answered
        /// by the button in the bar.
        fn stands_outside_a_sheet(&self, kind: WidgetKind, label: &str) -> bool {
            self.texts
                .iter()
                .filter(|(node, text)| {
                    text.as_str() == label && self.widgets.get(node) == Some(&kind)
                })
                .any(|(node, _)| !self.hangs_from_a_sheet(*node))
        }

        fn hangs_from_a_sheet(&self, node: u32) -> bool {
            let mut walk = node;
            while let Some(parent) = self.parents.get(&walk) {
                if self.widgets.get(parent) == Some(&WidgetKind::Sheet) {
                    return true;
                }
                walk = *parent;
            }
            false
        }

        fn dispatch(&mut self, node_id: u32, handler_id: u64, payload: EventPayload<'_>) -> i64 {
            encode_event(
                &HostEvent {
                    node_id,
                    handler_id,
                    payload,
                },
                &mut self.event,
            )
            .expect("the event did not encode");
            let (batch, result) = self
                .host
                .dispatch_event(&self.event)
                .expect("the event failed");
            let batch = batch.to_vec();
            self.absorb(&batch);
            result
        }

        /// The node showing this exact label, if the screen is showing one.
        fn labelled(&self, label: &str) -> Option<u32> {
            self.texts
                .iter()
                .find(|(_, text)| *text == label)
                .map(|(node_id, _)| *node_id)
        }

        /// Press what a person would read as this label.
        fn press(&mut self, label: &str) {
            let node_id = self
                .labelled(label)
                .unwrap_or_else(|| panic!("the screen has nothing labelled {label}"));
            let handler = *self
                .clicks
                .get(&node_id)
                .unwrap_or_else(|| panic!("{label} is not something that can be pressed"));
            self.dispatch(node_id, handler, EventPayload::Clicked);
        }

        /// The one text set at the display rung.
        fn display_node(&self) -> u32 {
            let found: Vec<u32> = self
                .type_roles
                .iter()
                .filter(|(node, role)| {
                    **role == i64::from(TypeRole::Display as u8) && self.widgets.contains_key(node)
                })
                .map(|(node, _)| *node)
                .collect();
            assert_eq!(
                found.len(),
                1,
                "the screen should have exactly one display sized text"
            );
            found[0]
        }

        fn display(&self) -> &str {
            self.texts
                .get(&self.display_node())
                .map(String::as_str)
                .expect("the display carries no text")
        }
    }

    /// A sequence of key presses, through the encoded event path rather than the engine, and
    /// the answer read back off the display the way a person reads it.
    #[test]
    fn fr4_a_sequence_of_key_presses_produces_the_expected_display() {
        let mut screen = Screen::new();
        assert_eq!(screen.display(), "0");

        for label in ["1", "2", "+", "3", "0", "="] {
            screen.press(label);
        }
        assert_eq!(screen.display(), "42");

        for label in ["\u{00d7}", "2", "="] {
            screen.press(label);
        }
        assert_eq!(screen.display(), "84");

        screen.press("C");
        assert_eq!(screen.display(), "0");
    }

    /// Division, the sign key and the backspace, which are the keys where an off by one in
    /// the wiring would still look like a working calculator until someone checked.
    #[test]
    fn fr4_the_correcting_keys_reach_the_display() {
        let mut screen = Screen::new();
        for label in ["1", "2", "3", "\u{232b}"] {
            screen.press(label);
        }
        assert_eq!(screen.display(), "12");

        screen.press("\u{00f7}");
        screen.press("4");
        screen.press("=");
        assert_eq!(screen.display(), "3");

        screen.press("\u{00b1}");
        assert_eq!(screen.display(), "-3");
    }

    /// Equals writes a line on the tape, and the line is a way back into the number it
    /// holds. A tape that could only be read would be a log.
    #[test]
    fn fr4_a_finished_calculation_lands_on_the_tape_and_can_be_pressed_back_in() {
        let mut screen = Screen::new();
        for label in ["1", "2", "+", "3", "0", "="] {
            screen.press(label);
        }
        assert_eq!(screen.display(), "42");
        assert!(
            screen.labelled("12 + 30 = 42").is_some(),
            "the finished calculation is not on the tape"
        );

        screen.press("C");
        assert_eq!(screen.display(), "0");
        screen.press("12 + 30 = 42");
        assert_eq!(
            screen.display(),
            "42",
            "pressing a tape line should put its answer back in the entry"
        );
    }

    /// Nothing that cannot be worked out goes on the tape, and the user is told once.
    #[test]
    fn fr21_a_calculation_with_no_answer_is_reported_as_a_message() {
        let mut screen = Screen::new();
        for label in ["5", "\u{00f7}", "0", "="] {
            screen.press(label);
        }
        assert_eq!(screen.display(), "Error");
        assert_eq!(
            screen.messages,
            vec![("5 \u{00f7} 0 has no answer".to_owned(), String::new())],
            "a result the calculator cannot show should be said once, not written down"
        );
        assert!(
            screen.labelled("Clear").is_some(),
            "the tape panel should still be on the screen"
        );
    }

    /// Throwing the tape away offers it back, the way deleting does everywhere else in
    /// these samples.
    #[test]
    fn fr21_clearing_the_tape_offers_it_back() {
        let mut screen = Screen::new();
        for label in ["6", "\u{00d7}", "7", "="] {
            screen.press(label);
        }
        assert!(screen.labelled("6 \u{00d7} 7 = 42").is_some());

        screen.press("Clear");
        assert!(
            screen.labelled("6 \u{00d7} 7 = 42").is_none(),
            "the tape should be empty after it is cleared"
        );
        assert_eq!(
            screen.messages,
            vec![("Cleared 1 line".to_owned(), "Undo".to_owned())],
            "clearing should say what it threw away and offer it back"
        );
    }

    /// A message with an action has to name a duration the wire can carry, and the long
    /// one is what an undo needs: an offer that leaves before it can be read is not one.
    #[test]
    fn fr21_the_undo_offer_stays_the_longer_of_the_two_durations() {
        let mut screen = Screen::new();
        for label in ["6", "\u{00d7}", "7", "="] {
            screen.press(label);
        }
        let node_id = screen.labelled("Clear").expect("the tape has no clear");
        let handler = screen.clicks[&node_id];
        encode_event(
            &HostEvent {
                node_id,
                handler_id: handler,
                payload: EventPayload::Clicked,
            },
            &mut screen.event,
        )
        .expect("the event did not encode");
        let (batch, _) = screen
            .host
            .dispatch_event(&screen.event)
            .expect("the event failed");
        let durations: Vec<MessageDuration> = decode_batch(batch)
            .expect("the frame did not decode")
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::ShowMessage { duration, .. } => Some(*duration),
                _ => None,
            })
            .collect();
        assert_eq!(durations, vec![MessageDuration::Long]);
    }
}
