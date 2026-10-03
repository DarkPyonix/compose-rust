//! A working desktop calculator, clone coded from the Windows, macOS and Deepin ones, with
//! the tape every desk calculator that had a printer used to have.
//!
//! The three references are one declaration under three design systems. What they share is
//! the shape: the number at the foot of a reading area, a row of memory keys, and four
//! columns of keys along the bottom.
//!
//! Written the way an application using this library would be written: `rsx!` and hooks,
//! no Kotlin and no protocol types.
//!
//! There is no keyboard entry, and the reason is worth saying out loud because it used to
//! be here. Only Enter can cross the boundary as a key, so digits had to be read out of a
//! focused text field by comparing what it held before against what it holds now. That
//! works, and it needs a field on the screen to hold the focus: a line of development
//! scaffolding sitting under the keypad, doing nothing a calculator does. A keypad is
//! pressed. When the protocol can name the keys, typing comes back as typing.

use dioxus_compose_adapter::prelude::*;

mod engine;

use engine::Calculator;

/// The key grid, top to bottom and left to right.
///
/// One shape at every width. There used to be a second, wider one, where the function keys
/// left the grid and stood in a column of their own beside it: a desk calculator, which is
/// a real instrument but not one any of the three references is. Windows, macOS and Deepin
/// all keep four columns however wide the window gets, and the wide pad put equals beside
/// plus, which none of them does either. What changes with the window here is where the
/// tape is, which is the adaptation this screen actually has.
const ROWS: [[&str; 4]; 5] = [
    ["C", "\u{232b}", "%", "\u{00f7}"],
    ["7", "8", "9", "\u{00d7}"],
    ["4", "5", "6", "\u{2212}"],
    ["1", "2", "3", "+"],
    ["\u{00b1}", "0", ".", "="],
];

/// The same pad as Apple's, which is not the same pad.
///
/// Apple's calculator has no memory row and no `C`: the top row is erase, clear
/// everything, percent and divide, and the memory keys live behind a menu rather than on
/// the face. Windows' has `C` and six memory keys across the pad. Both are calculators
/// and neither is a restyling of the other, which is why this is the one place in this
/// sample that reads which design system is running rather than asking for a role.
const APPLE_ROWS: [[&str; 4]; 5] = [
    ["\u{232b}", "AC", "%", "\u{00f7}"],
    ["7", "8", "9", "\u{00d7}"],
    ["4", "5", "6", "\u{2212}"],
    ["1", "2", "3", "+"],
    ["\u{00b1}", "0", ".", "="],
];

/// A keypad is a field of keys, so every key has a surface and the three kinds of key are
/// told apart by which surface they get.
///
/// Equals is the accent action, digits are the quiet filled keys that most of the pad is
/// made of, and operators use the design system's operator role. That role is an accent
/// fill on macOS, a neutral key on Windows, and a neutral key with accent ink on Deepin.
/// The function keys are outlined, which is a key with an edge rather than a key with a
/// fill. A plain text button is not a key at all: it reads as a label lying on the case.
fn variant_for(label: &str) -> ButtonVariant {
    match label {
        "=" => ButtonVariant::Filled,
        "\u{00f7}" | "\u{00d7}" | "\u{2212}" | "+" => ButtonVariant::Operator,
        "C" | "\u{232b}" | "%" | "\u{00b1}" => ButtonVariant::Outlined,
        _ => ButtonVariant::Tonal,
    }
}

/// Whether a key carries content rather than a command.
///
/// The digits and the decimal point are the number being entered, and a number is content.
/// A tonal button is a secondary *action* in every design system, so its label is the
/// accent colour: left alone, a keypad prints its digits in the link colour. The digits
/// therefore name their own ink and the command keys keep the tint, which leaves one
/// meaning on the pad reading as accent, something that acts on the number.
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

/// The keypad, in the shape this platform's calculator has.
fn keypad(apple: bool, press: EventHandler<&'static str>) -> Element {
    // Every row an equal share of the height, every key an equal share of its row. Weight
    // is what makes this a grid rather than five lines of differently sized buttons.
    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            space_role: SpaceRole::Xs,
            for (index , row) in if apple { APPLE_ROWS } else { ROWS }.iter().enumerate() {
                Row {
                    key: "row-{index}",
                    fill_max_width: true,
                    weight: 1.0,
                    space_role: SpaceRole::Xs,
                    for label in row.iter().copied() {
                        Button {
                            key: "{label}",
                            text: label,
                            weight: 1.0,
                            fill_max_height: true,
                            variant: variant_for(label),
                            color: color_for(label),
                            on_click: move |_| press.call(label),
                        }
                    }
                }
            }
        }
    }
}

/// The memory keys, as the row the Windows and Deepin references give them.
///
/// A second register beside the running value. They are text keys rather than keys in the
/// pad, because they do not enter anything: the pad is what a number is typed on, and a
/// row of quiet labels above it is how both references say "these act on what is already
/// there".
fn memory_row(memory_set: bool, press: EventHandler<&'static str>) -> Element {
    rsx! {
        Row {
            fill_max_width: true,
            space_role: SpaceRole::Xs,
            alignment: Alignment::CenterStart,
            for label in engine::MEMORY_KEYS {
                Button {
                    key: "{label}",
                    text: label,
                    weight: 1.0,
                    variant: ButtonVariant::Text,
                    color: Paint::Role(ColorRole::OnSurfaceVariant),
                    // Recalling and clearing act on what is stored, so they are dead keys
                    // until something is. The reference greys them the same way.
                    enabled: memory_set || !matches!(label, "MC" | "MR"),
                    on_click: move |_| press.call(label),
                }
            }
        }
    }
}

/// The readout: what is being worked out, and what it comes to.
///
/// Printed on the case rather than set into it. It used to be a filled panel, on the
/// reasoning that a calculator's display is a component behind glass, and none of the
/// three references does that: the Windows, macOS and Deepin calculators all set the
/// number directly on the page with nothing drawn behind it. A panel also fought the
/// reading area, which is mostly the room above the number.
fn readout(status: String, display: String) -> Element {
    rsx! {
        dioxus_compose_adapter::Box {
            fill_max_width: true,
            Column {
                fill_max_width: true,
                space_role: SpaceRole::Xs,
                // The pending operation is a line only while there is one. An empty line
                // still has a height, and the panel is meant to be as tall as the type it
                // holds and no taller.
                if !status.is_empty() {
                    Text {
                        text: status,
                        fill_max_width: true,
                        text_align: TextAlign::End,
                        type_role: TypeRole::Caption,
                        color: Paint::Role(ColorRole::OnSurfaceVariant),
                        max_lines: 1,
                        overflow: TextOverflow::Ellipsis,
                    }
                }
                Text {
                    text: display,
                    fill_max_width: true,
                    text_align: TextAlign::End,
                    type_role: TypeRole::Display,
                    color: Paint::Role(ColorRole::OnSurface),
                    max_lines: 1,
                    overflow: TextOverflow::Ellipsis,
                }
            }
        }
    }
}

/// One finished calculation, kept so it can be read back and used again.
#[derive(Clone, PartialEq)]
struct TapeEntry {
    /// Never reused, so it is the list key and a row that scrolls past another row is not
    /// mistaken for the same row changing.
    id: u64,
    expression: String,
    result: String,
    value: f64,
}

/// What the app bar calls the tape, and what the sheet is opened by.
const TAPE_LABEL: &str = "History";

/// The tape: what has been worked out, newest at the top, each line a key back into the
/// calculation it came from.
///
/// Every desk calculator with a printer has one of these, and it is the answer to the
/// thing a calculator is worst at: you work something out, you work the next thing out,
/// and the first answer is gone.
fn tape(entries: Vec<TapeEntry>, recall: EventHandler<f64>, clear: EventHandler<()>) -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            space_role: SpaceRole::Sm,
            Row {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::CenterStart,
                Text { text: TAPE_LABEL, type_role: TypeRole::Subtitle, weight: 1.0 }
                // Throwing the tape away is the one thing here that destroys something, so
                // it is the error ink and it offers the tape back afterwards.
                Button {
                    text: "Clear",
                    variant: ButtonVariant::Text,
                    color: Paint::Role(ColorRole::Error),
                    enabled: !entries.is_empty(),
                    on_click: move |_| clear.call(()),
                }
            }
            Separator {}
            if entries.is_empty() {
                dioxus_compose_adapter::Box {
                    fill_max_width: true,
                    weight: 1.0,
                    alignment: Alignment::Center,
                    Text {
                        text: "Nothing worked out yet. Press equals and it lands here.",
                        type_role: TypeRole::Body,
                        text_align: TextAlign::Center,
                        color: Paint::Role(ColorRole::OnSurfaceVariant),
                    }
                }
            } else {
                // Newest first: the thing you are most likely to want back is the thing
                // you worked out last, and it should not be at the bottom of a scroll.
                ScrollColumn {
                    fill_max_width: true,
                    weight: 1.0,
                    for entry in entries.iter().rev() {
                        Button {
                            key: "{entry.id}",
                            text: "{entry.expression} = {entry.result}",
                            fill_max_width: true,
                            variant: ButtonVariant::Text,
                            color: Paint::Role(ColorRole::OnSurface),
                            on_click: {
                                let value = entry.value;
                                move |_| recall.call(value)
                            },
                        }
                    }
                }
            }
        }
    }
}

/// How the reading area and the keypad share the instrument's height.
///
/// All three references put the keypad along the bottom and let the number sit at the
/// foot of whatever is left above it. The ratio is theirs too: measured off the three
/// shots it runs from one to two to one to three, and the keys grow with the window in
/// every one of them rather than stopping at a size.
const READING_SHARE: f32 = 1.0;
const KEYPAD_SHARE: f32 = 3.0;

/// How the instrument and the tape share an expanded window.
///
/// The keypad is what the hand is on, so it gets the larger share, and the tape is a
/// column of short lines that gains nothing from being wider.
const INSTRUMENT_SHARE: f32 = 3.0;
const TAPE_SHARE: f32 = 2.0;

pub fn app() -> Element {
    let window = use_window_size();
    // The one thing on this screen that a role cannot answer. Apple's calculator and
    // Windows' differ in which keys exist, not in how the same keys are painted, so this
    // reads the system the Renderer resolved the theme to and nothing else about it.
    let apple = matches!(
        use_design_system(),
        DesignSystem::Cupertino | DesignSystem::LiquidGlass
    );
    let mut calculator = use_signal(Calculator::new);
    let mut entries = use_signal(Vec::<TapeEntry>::new);
    let mut next_entry = use_signal(|| 1_u64);
    let mut tape_open = use_signal(|| false);

    let display = calculator.read().display();
    let status = calculator.read().status();
    let memory_set = calculator.read().memory_set();
    // A desktop window has room for the tape to stand beside the keypad. Narrower than
    // that it is a sheet, which is the same tape arriving from an edge instead.
    let tape_beside = window.is_expanded();
    // The keypad stops widening once there is more window than a keypad needs, and centres
    // in what is left: a key the width of a hand is not easier to press, and no calculator
    // on any of the three platforms grows one. The condition used to read
    // `!tape_beside && window.is_expanded()`, which is never true, so the pad had been
    // growing without limit the whole time.
    let pad_width = (!window.is_compact()).then_some(WindowSizeClass::MEDIUM_MIN_WIDTH_DP);

    // One key press, whichever key it was. The tape is written here rather than in the
    // engine because a tape is a thing the application keeps, not a thing arithmetic has.
    let press = move |label: &'static str| {
        let finished = {
            let mut state = calculator.write();
            state.press(label);
            state.take_completed()
        };
        let Some(finished) = finished else { return };
        if finished.failed {
            // A result a calculator cannot show is worth one sentence, and the sentence
            // is not a widget this code places: it is handed over once and the Renderer
            // decides where it goes and how long it stays.
            Message::new(format!("{} has no answer", finished.expression)).show();
            return;
        }
        let id = next_entry();
        next_entry.set(id + 1);
        entries.write().push(TapeEntry {
            id,
            expression: finished.expression,
            result: finished.result,
            value: finished.value,
        });
    };

    let recall = EventHandler::new(move |value: f64| {
        calculator.write().recall(value);
        tape_open.set(false);
    });
    let clear_tape = EventHandler::new(move |()| {
        let thrown_away = std::mem::take(&mut *entries.write());
        let lines = if thrown_away.len() == 1 {
            "line"
        } else {
            "lines"
        };
        Message::new(format!("Cleared {} {lines}", thrown_away.len()))
            .with_action("Undo", move |()| entries.set(thrown_away.clone()))
            .with_duration(MessageDuration::Long)
            .show();
    });

    rsx! {
        // The frame is named rather than built. What the bar becomes here, whether it is
        // the window's caption or a bar under the system's, and where the page starts, are
        // answers this screen never has to know.
        Scaffold {
            top_bar: rsx! {
                TopAppBar {
                    fill_max_width: true,
                    Button {
                        text: "",
                        icon: IconRole::Menu,
                        variant: ButtonVariant::Text,
                        on_click: move |_| {},
                    }
                    // Apple's calculator names no mode across its bar: the two icons are
                    // the whole of it, and the mode is where the menu leads. Windows'
                    // names the mode in bold beside the menu, which is what the bar is
                    // mostly for there. The spacer keeps the trailing icon at the trailing
                    // edge either way.
                    if apple {
                        Spacer { weight: 1.0 }
                    } else {
                        Text { text: "Standard", type_role: TypeRole::Title, weight: 1.0 }
                    }
                    if !tape_beside {
                        Button {
                            text: "",
                            icon: IconRole::History,
                            variant: ButtonVariant::Text,
                            on_click: move |_| tape_open.set(true),
                        }
                    }
                }
            },

            Row {
                fill_max_width: true,
                fill_max_height: true,
                // The instrument: the number, the memory keys and the pad, all on one
                // measure so their edges agree. The pad used to be the only one of the
                // three that stopped widening, which put the number to the right of the
                // keys it belongs to.
                dioxus_compose_adapter::Box {
                    weight: INSTRUMENT_SHARE,
                    fill_max_height: true,
                    alignment: Alignment::TopCenter,
                    Column {
                        fill_max_width: pad_width.is_none(),
                        width: pad_width,
                        fill_max_height: true,
                        // The bar insets its own contents by the medium step, so the
                        // instrument under it uses the same one and the two line up.
                        padding_role: SpaceRole::Md,
                        space_role: SpaceRole::Md,

                        // The reading area takes the height the keys leave and the number
                        // sits at its foot, which is where all three references put it:
                        // the room above the number is what a long expression grows into
                        // rather than something that pushes the keys down.
                        dioxus_compose_adapter::Box {
                            fill_max_width: true,
                            weight: READING_SHARE,
                            alignment: Alignment::BottomCenter,
                            {readout(status, display)}
                        }
                        // Apple's calculator has no memory row on its face, so there is
                        // none here when Apple's language is what is running.
                        if !apple {
                            {memory_row(memory_set, EventHandler::new(press))}
                        }
                        dioxus_compose_adapter::Box {
                            fill_max_width: true,
                            weight: KEYPAD_SHARE,
                            alignment: Alignment::BottomCenter,
                            {keypad(apple, EventHandler::new(press))}
                        }
                    }
                }
                if tape_beside {
                    Divider { vertical: true }
                    Column {
                        weight: TAPE_SHARE,
                        fill_max_height: true,
                        padding_role: SpaceRole::Md,
                        {tape(entries(), recall, clear_tape)}
                    }
                }
            }

            // The same tape, arriving from an edge, for the windows with no room beside
            // the keys. Which edge is the Renderer's decision: up from the bottom of a
            // phone, in from the side of a tablet.
            Sheet {
                open: tape_open() && !tape_beside,
                on_dismiss: move |_| tape_open.set(false),
                fill_max_width: true,
                // Only where the tape is not already beside the keys. Declaring it in
                // both places would be two tapes: one list of nodes per finished
                // calculation, twice, and a scroll position each.
                if !tape_beside {
                    {tape(entries(), recall, clear_tape)}
                }
            }
        }
    }
}

// Samples are demonstrations, so they let you see any of the design systems rather than
// only the one this machine happens to select. Unset, the app adapts to the host platform,
// which is what a real application wants.
/// Runs the sample as a program of its own. The desktop binary is one line of this.
pub fn launch() {
    launch_builder().launch(app);
}

/// How this sample is configured, in one place because three entry points need it.
///
/// The theme above all: a sample that names one and then reaches a platform through an
/// entry point that makes its own builder is a sample that draws the same screens in a
/// different design system depending on where it runs.
fn launch_builder() -> dioxus_compose_adapter::LaunchBuilder {
    // The name the window carries. A desktop lists windows by it, so a window that said
    // nothing was listed under whatever the renderer happened to be called, and every
    // sample here was listed as DioxusCompose until this line existed.
    dioxus_compose_adapter::LaunchBuilder::new()
        .with_theme(dioxus_compose_adapter::demo_theme())
        .with_window(
            dioxus_compose_adapter::schema::Window::new()
                .with_title("Calculator")
                // A calculator is a tall narrow window on all three of the machines this
                // is a rebuild of, and for the same reason on each: the keys are a grid
                // four across, and a window wider than that grid needs to be does not
                // give a key back, it stretches it. Left at the renderer's own default
                // this opened at 800 by 600 and drew the keys as wide lozenges, which is
                // the one shape none of the three references has.
                .with_size(380, 620)
                .with_min_size(320, 480)
                // Without one the window wears the toolkit's picture, which on
                // Windows is the Java coffee cup, wherever the system lists
                // windows. The bytes travel as an asset and the renderer refers
                // to them by id: a path would be a fact about the machine this
                // was built on, and a name would ask the toolkit to find
                // something it may not have.
                .with_icon(dioxus_compose_adapter::asset::asset(
                    dioxus_compose_adapter::schema::AssetKind::Png,
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
dioxus_compose_adapter::android_main!({ launch_builder() }, app);
dioxus_compose_adapter::web_main!({ launch_builder() }, app);
dioxus_compose_adapter::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;
    use dioxus_compose_adapter::Host;
    use dioxus_compose_adapter::protocol::{
        HostEvent, Mutation, PropertyValue, decode_batch, encode_event,
    };
    use dioxus_compose_adapter::schema::{
        EventPayload, MessageDuration, PropertyKind, TypeRole, WidgetKind,
    };
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
        dioxus_compose_adapter::window::reset_window_size();
        let mut host = Host::new(app);
        let batch = host.rebuild().expect("the first frame failed to encode");
        let labels = collect_button_labels(batch);
        dioxus_compose_adapter::window::reset_window_size();

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
        let mut dom = VirtualDom::new(app);
        let mut renderer = dioxus_compose_adapter::renderer::ComposeRenderer::new();
        renderer.begin_frame();
        dom.rebuild(&mut renderer);
        renderer
            .finish_frame()
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
        host: Host,
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
                host: Host::new(app),
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
            dioxus_compose_adapter::window::reset_window_size();
            let mut screen = Self::new();
            screen.dispatch(
                0,
                0,
                EventPayload::WindowSizeChanged {
                    width_dp,
                    height_dp: 800.0,
                    class: dioxus_compose_adapter::WindowSizeClass::from_width_dp(width_dp),
                    height_class: dioxus_compose_adapter::WindowHeightClass::from_height_dp(800.0),
                },
            );
            dioxus_compose_adapter::window::reset_window_size();
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
