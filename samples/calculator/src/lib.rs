//! A working desktop calculator, clone coded from the Windows, macOS and Deepin ones, with
//! the tape every desk calculator that had a printer used to have.
//!
//! The three references are one declaration under three design systems. What they share is
//! the shape: the number at the foot of a reading area, a row of memory keys, and four
//! columns of keys along the bottom.
//!
//! Written with compose-rust's own API: state lives in `mutable_state_of`, screens are
//! `#[composable]` functions, and a widget is a statement. No Kotlin and no protocol types.
//!
//! There is no keyboard entry. A keypad is pressed, and a text field kept on screen only to
//! hold the focus would be development scaffolding under the keypad, doing nothing a
//! calculator does.

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

/// How this sample is configured, in one place because three entry points need it.
///
/// A calculator is a tall narrow window on all three of the machines this is a rebuild of:
/// the keys are a grid four across, and a wider window stretches a key rather than giving
/// one back.
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
    use compose_rust::schema::{EventPayload, MessageDuration, PropertyKind, TypeRole, WidgetKind};
    use std::collections::HashMap;

    /// Whether a label belongs to the keypad rather than to the rest of the screen.
    ///
    /// The screen holds buttons that are not keys, and a test about the shape of the
    /// keypad has to be able to say so: the tape's own controls appearing in a count of
    /// keys is how a keypad test starts failing for a reason that has nothing to do with
    /// the keypad.
    fn is_key(label: &str) -> bool {
        ROWS.iter().flatten().any(|key| *key == label)
    }

    /// The key labels the screen holds, in the order the keypad declares them, after the
    /// Renderer reports a window of the given width.
    fn keys_at(width_dp: f32) -> Vec<String> {
        let mut labels = Screen::at(width_dp).button_labels();
        labels.retain(|label| is_key(label));
        labels
    }

    fn collect_button_labels(batch: &[u8]) -> Vec<String> {
        let mutations = decode_batch(batch).expect("the batch did not decode");
        let buttons: Vec<u32> = mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::Create {
                    node_id,
                    widget: WidgetKind::Button,
                } => Some(*node_id),
                _ => None,
            })
            .collect();
        mutations
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Text,
                    value: PropertyValue::String(text),
                } if buttons.contains(node_id) => Some((*text).to_owned()),
                _ => None,
            })
            .collect()
    }

    /// The two pads are two calculators, not one calculator painted twice.
    ///
    /// Apple's has no `C` and no memory keys on its face; the top row erases, clears
    /// everything, takes a percentage and divides. Windows' clears with `C` and carries
    /// six memory keys. If these two ever hold the same labels, the branch that chooses
    /// between them has stopped meaning anything and the screen has quietly become one
    /// screen again.
    #[test]
    fn fr32_the_apple_pad_is_not_the_windows_pad() {
        assert_ne!(APPLE_ROWS, ROWS);
        assert_eq!(APPLE_ROWS[0], ["\u{232b}", "AC", "%", "\u{00f7}"]);
        assert_eq!(ROWS[0], ["C", "\u{232b}", "%", "\u{00f7}"]);
        assert!(
            !APPLE_ROWS.iter().flatten().any(|label| *label == "C"),
            "Apple's calculator clears with AC and has no C",
        );
        // The digits, the point and the arithmetic are the same instrument in both.
        for row in 1..5 {
            assert_eq!(APPLE_ROWS[row], ROWS[row]);
        }
    }

    /// The reference screen reads from the top down: what is being worked out, what it
    /// comes to, the memory keys, then the pad. This asserts the order the screen is
    /// declared in, because a memory row under the keypad would be a different screen.
    #[test]
    fn fr22_the_memory_row_stands_between_the_reading_area_and_the_keys() {
        compose_rust::window::reset_window_size();
        let mut host = ComposeHost::new(app);
        let batch = host.rebuild().expect("the first frame failed to encode");
        let labels = collect_button_labels(batch);
        compose_rust::window::reset_window_size();

        let memory_at = labels
            .iter()
            .position(|label| label == engine::MEMORY_KEYS[0])
            .expect("the screen has no memory row");
        assert_eq!(
            &labels[memory_at..memory_at + engine::MEMORY_KEYS.len()],
            engine::MEMORY_KEYS,
            "the memory keys are not the row the reference prints"
        );

        let first_key = labels
            .iter()
            .position(|label| label == ROWS[0][0])
            .expect("the screen has no keypad");
        assert!(
            memory_at < first_key,
            "the memory row is declared after the keypad, so it is drawn under it"
        );
    }

    /// A phone keypad and a desk keypad are different keypads, not the same one scaled.
    /// Narrow, the four function keys share the top row with an operator. Wide, they are a
    /// column of their own and the grid is four rows of digits.
    #[test]
    fn fr20_the_keypad_is_one_shape_and_the_tape_is_what_moves() {
        let narrow = keys_at(420.0);
        assert_eq!(narrow.first().map(String::as_str), Some("C"));
        assert_eq!(narrow.len(), 20);

        // The same twenty keys in the same order. All three references keep four columns
        // however wide the window is; what a wider window buys is somewhere to put the
        // tape.
        assert_eq!(keys_at(900.0), narrow);
        assert_eq!(keys_at(1200.0), narrow);
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
        let mut calculator = Calculator::new();
        for row in ROWS {
            for label in row {
                let _ = variant_for(label);
                calculator.press(label);
            }
        }
        assert!(!calculator.display().is_empty());
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
        display: u32,
        /// Every message the screen has said, in order, with its action label.
        messages: Vec<(String, String)>,
        event: Vec<u8>,
    }

    impl Screen {
        fn new() -> Self {
            let mut screen = Self {
                host: ComposeHost::new(app),
                texts: HashMap::new(),
                widgets: HashMap::new(),
                buttons: Vec::new(),
                icons: HashMap::new(),
                clicks: HashMap::new(),
                parents: HashMap::new(),
                display: 0,
                messages: Vec::new(),
                event: Vec::new(),
            };
            let first = screen
                .host
                .rebuild()
                .expect("the first frame failed to encode")
                .to_vec();
            let display_roles = screen.absorb(&first);
            assert_eq!(
                display_roles.len(),
                1,
                "the screen should have exactly one display sized text"
            );
            screen.display = display_roles[0];
            screen
        }

        /// Applies one batch and returns the nodes in it that are set at the display rung.
        fn absorb(&mut self, batch: &[u8]) -> Vec<u32> {
            let mut display_roles = Vec::new();
            for mutation in decode_batch(batch).expect("the frame did not decode") {
                match mutation {
                    Mutation::Create { node_id, widget } => {
                        self.widgets.insert(node_id, widget);
                        if widget == WidgetKind::Button {
                            self.buttons.push(node_id);
                        }
                    }
                    Mutation::Insert {
                        parent_id, node_id, ..
                    }
                    | Mutation::Move {
                        parent_id, node_id, ..
                    } => {
                        self.parents.insert(node_id, parent_id);
                    }
                    Mutation::Remove { node_id } => self.forget(node_id),
                    Mutation::ShowMessage { text, action, .. } => {
                        self.messages.push((text.to_owned(), action.to_owned()));
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
                        (PropertyKind::TypeRole, PropertyValue::Integer(role))
                            if role == i64::from(TypeRole::Display as u8) =>
                        {
                            display_roles.push(node_id);
                        }
                        _ => {}
                    },
                    _ => {}
                }
            }
            display_roles
        }

        /// Drops a node and everything under it.
        fn forget(&mut self, node_id: u32) {
            let children: Vec<u32> = self
                .parents
                .iter()
                .filter(|(_, parent)| **parent == node_id)
                .map(|(child, _)| *child)
                .collect();
            for child in children {
                self.forget(child);
            }
            self.texts.remove(&node_id);
            self.clicks.remove(&node_id);
            self.parents.remove(&node_id);
            self.widgets.remove(&node_id);
            self.buttons.retain(|found| *found != node_id);
        }

        /// The screen after the Renderer reports a window of this width.
        ///
        /// A resize is a diff rather than a fresh screen, so this keeps the tree and
        /// applies the change to it: reading the resize batch alone would show only what
        /// moved.
        fn at(width_dp: f32) -> Self {
            compose_rust::window::reset_window_size();
            let mut screen = Self::new();
            screen.dispatch(
                0,
                0,
                EventPayload::WindowSizeChanged {
                    width_dp,
                    height_dp: 800.0,
                    class: WindowSizeClass::from_width_dp(width_dp),
                    height_class: WindowHeightClass::from_height_dp(800.0),
                },
            );
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

        fn has_button(&self, label: &str) -> bool {
            self.button_labels().iter().any(|found| found == label)
        }

        /// Whether a button carrying this icon stands on the screen.
        fn has_icon_button(&self, role: IconRole) -> bool {
            self.buttons
                .iter()
                .any(|node| self.icons.get(node) == Some(&(role as u8)))
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

        fn display(&self) -> &str {
            self.texts
                .get(&self.display)
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
