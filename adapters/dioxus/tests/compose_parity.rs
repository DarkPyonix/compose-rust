//! The same screens written both ways build the same tree for the Renderer.
//!
//! Each fixture is written twice: once in `rsx!`, the way the samples are written, and
//! once with composables. The two are driven through the same interactions, and after
//! every one the node tree the Renderer would hold is compared, with node and handler ids
//! taken out. The calculator and todo fixtures are the samples' own screens, copied; the
//! last fixture puts every widget in the schema on one screen with its properties set.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::HostEvent;
use compose_rust::schema::{EventPayload, PropertyKind};
use dioxus_compose_adapter::Host;
use support::Tree;

mod rsx_side {
    #![allow(non_snake_case)]

    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::{DrawList, LinearProgressIndicator};

    pub const ROWS: [[&str; 4]; 5] = [
        ["C", "\u{232b}", "%", "\u{00f7}"],
        ["7", "8", "9", "\u{00d7}"],
        ["4", "5", "6", "\u{2212}"],
        ["1", "2", "3", "+"],
        ["\u{00b1}", "0", ".", "="],
    ];

    pub fn variant_for(label: &str) -> ButtonVariant {
        match label {
            "=" => ButtonVariant::Filled,
            "\u{00f7}" | "\u{00d7}" | "\u{2212}" | "+" => ButtonVariant::Operator,
            "C" | "\u{232b}" | "%" | "\u{00b1}" => ButtonVariant::Outlined,
            _ => ButtonVariant::Tonal,
        }
    }

    pub fn color_for(label: &str) -> Option<Paint> {
        matches!(
            label,
            "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" | "."
        )
        .then_some(Paint::Role(ColorRole::OnSurface))
    }

    pub const MEMORY_KEYS: [&str; 6] = ["MC", "MR", "M+", "M\u{2212}", "MS", "M\u{25be}"];

    // ----- the calculator's screen, as samples/calculator writes it --------------------

    fn keypad(press: EventHandler<&'static str>) -> Element {
        rsx! {
            Column {
                fill_max_width: true,
                fill_max_height: true,
                space_role: SpaceRole::Xs,
                for (index , row) in ROWS.iter().enumerate() {
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

    fn memory_row(memory_set: bool, press: EventHandler<&'static str>) -> Element {
        rsx! {
            Row {
                fill_max_width: true,
                space_role: SpaceRole::Xs,
                alignment: Alignment::CenterStart,
                for label in MEMORY_KEYS {
                    Button {
                        key: "{label}",
                        text: label,
                        weight: 1.0,
                        variant: ButtonVariant::Text,
                        color: Paint::Role(ColorRole::OnSurfaceVariant),
                        enabled: memory_set || !matches!(label, "MC" | "MR"),
                        on_click: move |_| press.call(label),
                    }
                }
            }
        }
    }

    fn readout(status: String, display: String) -> Element {
        rsx! {
            dioxus_compose_adapter::Box {
                fill_max_width: true,
                Column {
                    fill_max_width: true,
                    space_role: SpaceRole::Xs,
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

    #[derive(Clone, PartialEq)]
    pub struct TapeEntry {
        pub id: u64,
        pub expression: String,
        pub result: String,
        pub value: f64,
    }

    fn tape(
        entries: Vec<TapeEntry>,
        recall: EventHandler<f64>,
        clear: EventHandler<()>,
    ) -> Element {
        rsx! {
            Column {
                fill_max_width: true,
                fill_max_height: true,
                space_role: SpaceRole::Sm,
                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    alignment: Alignment::CenterStart,
                    Text { text: "History", type_role: TypeRole::Subtitle, weight: 1.0 }
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

    /// The calculator's readout, memory row, keypad and tape, wired to a display that
    /// shows the last key and a tape that records every equals.
    pub fn calculator() -> Element {
        let mut display = use_signal(|| "0".to_owned());
        let mut entries = use_signal(Vec::<TapeEntry>::new);
        let mut next = use_signal(|| 1_u64);
        let press = EventHandler::new(move |label: &'static str| {
            if label == "=" {
                let id = next();
                next.set(id + 1);
                let shown = display();
                entries.write().push(TapeEntry {
                    id,
                    expression: shown.clone(),
                    result: shown,
                    value: 0.0,
                });
            } else {
                display.set(label.to_owned());
            }
        });
        rsx! {
            Column {
                fill_max_width: true,
                {readout(String::new(), display())}
                {memory_row(false, press)}
                {keypad(press)}
                {tape(
                    entries(),
                    EventHandler::new(|_: f64| {}),
                    EventHandler::new(move |()| entries.write().clear()),
                )}
            }
        }
    }

    // ----- the todo sample's bar, about panel and composer -------------------------------

    fn list_bar(measure: Option<f32>, done: usize, total: usize, children: Element) -> Element {
        rsx! {
            Column {
                fill_max_width: true,
                TopAppBar {
                    fill_max_width: true,
                    dioxus_compose_adapter::Box {
                        weight: 1.0,
                        alignment: Alignment::Center,
                        Row {
                            width: measure,
                            fill_max_width: measure.is_none(),
                            padding_role: measure.map(|_| SpaceRole::Md),
                            space_role: SpaceRole::Sm,
                            alignment: Alignment::CenterStart,
                            {children}
                        }
                    }
                }
                if total > 0 {
                    ProgressIndicator { value: done as f32 / total as f32 }
                }
            }
        }
    }

    fn about_panel(total: usize, fill: EventHandler<()>, close: EventHandler<()>) -> Element {
        rsx! {
            Column {
                fill_max_width: true,
                space_role: SpaceRole::Md,
                Row {
                    fill_max_width: true,
                    alignment: Alignment::CenterStart,
                    Text { text: "About this sample", type_role: TypeRole::Subtitle, weight: 1.0 }
                    Button {
                        text: "Close",
                        variant: ButtonVariant::Filled,
                        on_click: move |_| close.call(()),
                    }
                }
                Separator {}
                Text {
                    text: "The list windows its rows.",
                    type_role: TypeRole::Body,
                    color: Paint::Role(ColorRole::OnSurfaceVariant),
                }
                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    alignment: Alignment::CenterStart,
                    Text {
                        text: "{total} tasks now",
                        type_role: TypeRole::Label,
                        color: Paint::Role(ColorRole::OnSurfaceVariant),
                        weight: 1.0,
                    }
                    Button {
                        text: "Add 5 tasks",
                        variant: ButtonVariant::Tonal,
                        on_click: move |_| fill.call(()),
                    }
                }
            }
        }
    }

    fn composer(add: EventHandler<String>, draft: Signal<String>) -> Element {
        let mut draft = draft;
        rsx! {
            Surface {
                fill_max_width: true,
                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    alignment: Alignment::CenterStart,
                    TextField {
                        weight: 1.0,
                        placeholder: "Add a task, then press Enter",
                        on_value_change: move |value| draft.set(value),
                        on_submit: move |value: String| add.call(value),
                    }
                    Button {
                        text: "Add",
                        variant: ButtonVariant::Filled,
                        on_click: move |_| add.call(draft()),
                    }
                }
            }
        }
    }

    /// The bar counting tasks, the composer adding them, and the about panel bulk-adding.
    pub fn todo() -> Element {
        let mut titles = use_signal(Vec::<String>::new);
        let draft = use_signal(String::new);
        let total = titles.read().len();
        let add = EventHandler::new(move |title: String| {
            if !title.trim().is_empty() {
                titles.write().push(title);
            }
        });
        rsx! {
            Column {
                fill_max_width: true,
                {list_bar(Some(840.0), total / 2, total, rsx! {
                    Text { text: "{total} tasks", type_role: TypeRole::Headline, weight: 1.0 }
                    if total > 3 {
                        Button { text: "Clear", variant: ButtonVariant::Text, on_click: move |_| titles.write().clear() }
                    }
                })}
                {composer(add, draft)}
                for (index , title) in titles().iter().enumerate() {
                    Text { key: "{index}", text: "{title}" }
                }
                {about_panel(
                    total,
                    EventHandler::new(move |()| {
                        for number in 0..5 {
                            titles.write().push(format!("Generated {number}"));
                        }
                    }),
                    EventHandler::new(|()| {}),
                )}
            }
        }
    }

    // ----- every widget ------------------------------------------------------------------

    /// Every widget in the schema, each with its properties set away from their defaults.
    pub fn sink() -> Element {
        rsx! {
            Scaffold {
                background: Paint::Role(ColorRole::Background),
                material: MaterialRole::Chrome,
                top_bar: rsx! {
                    TopAppBar { title: "Sink".to_owned(), Text { text: "bar" } }
                },
                bottom_bar: rsx! {
                    Navigation {
                        selected_index: 1,
                        head: rsx! { Text { text: "head" } },
                        foot: rsx! { Text { text: "foot" } },
                        NavigationItem {
                            text: "One".to_owned(),
                            icon: IconRole::Search,
                            section: "Group".to_owned(),
                            on_click: move |_| {},
                        }
                        NavigationItem {
                            text: "Two".to_owned(),
                            enabled: false,
                            color: Paint::Role(ColorRole::Primary),
                        }
                    }
                },
                floating_action: rsx! {
                    FloatingAction { icon: IconRole::Search, text: "New", on_click: move |_| {} }
                },
                Column {
                    arrangement: Arrangement::SpaceBetween,
                    spacing: 4.0,
                    alignment: Alignment::CenterStart,
                    padding: 8.0,
                    background: Paint::Role(ColorRole::Surface),
                    corner_radius: 6.0,
                    border_width: 1.0,
                    border_color: Paint::Role(ColorRole::Outline),
                    elevation: 2.0,
                    motion: MotionRole::Quick,
                    Row {
                        space_role: SpaceRole::Sm,
                        weight: 1.0,
                        width: 100.0,
                        height: 40.0,
                        shape_role: ShapeRole::Small,
                        Text {
                            text: "styled",
                            type_role: TypeRole::Title,
                            font_size: 18.0,
                            font_weight: 600,
                            line_height: 22.0,
                            letter_spacing: 0.5,
                            color: Paint::Literal(Color::rgb(0x123456)),
                            text_align: TextAlign::Center,
                            max_lines: 2,
                            overflow: TextOverflow::Ellipsis,
                        }
                        Button {
                            text: "Go",
                            icon: IconRole::Search,
                            enabled: false,
                            variant: ButtonVariant::Outlined,
                            color: Paint::Role(ColorRole::Error),
                        }
                    }
                    dioxus_compose_adapter::Box {
                        alignment: Alignment::Center,
                        padding_role: SpaceRole::Md,
                        Text { text: "boxed" }
                    }
                    ScrollColumn { height: 50.0, Text { text: "scrolled" } }
                    ScrollRow { Text { text: "sideways" } }
                    TextField {
                        placeholder: "Type",
                        enabled: false,
                        multiline: true,
                        type_role: TypeRole::Mono,
                    }
                    Spacer { height: 8.0 }
                    LazyColumn { item_count: 3, item: move |index: usize| rsx! { Text { text: "{index}" } } }
                    Image { asset_id: 7, width: 24.0 }
                    Icon { asset_id: 8, color: Paint::Role(ColorRole::Primary) }
                    Checkbox { checked: true }
                    RadioButton { selected: true, enabled: false }
                    Switch { checked: true }
                    Slider {
                        value: 0.5,
                        min: 0.0,
                        max: 2.0,
                        steps: 3,
                        color: Paint::Role(ColorRole::Primary),
                    }
                    ProgressIndicator { value: 0.25, circular: true }
                    Divider { vertical: true }
                    Separator {}
                    Card { padding: 4.0, Text { text: "card" } }
                    Surface { material: MaterialRole::Thin, Text { text: "surface" } }
                    Dialog { open: true, Text { text: "dialog" } }
                    Menu {
                        expanded: true,
                        anchor: rsx! { Button { text: "anchor" } },
                        Button { text: "entry" }
                    }
                    Tabs {
                        selected_index: 1,
                        color: Paint::Role(ColorRole::Primary),
                        Button { text: "A" }
                        Button { text: "B" }
                    }
                    LazyRow { item_count: 2, item: move |index: usize| rsx! { Text { text: "{index}" } } }
                    Tooltip { text: "tip", Button { text: "hover" } }
                    Canvas {
                        commands: DrawList::builder()
                            .line(Paint::Role(ColorRole::Primary), 0.0, 0.0, 10.0, 10.0, 1.0)
                            .build(),
                        width: 20.0,
                        height: 20.0,
                    }
                    DatePicker { value: 19_000, min: 18_000, max: 20_000 }
                    TimePicker { value: 600, max: 1_200 }
                    Dropdown { selected_index: 1, Text { text: "x" } Text { text: "y" } }
                    Sheet { open: false, Text { text: "sheet" } }
                    LazyGrid {
                        columns: 3,
                        item_count: 4,
                        item: move |index: usize| rsx! { Text { text: "{index}" } },
                    }
                    FileDropTarget { alignment: Alignment::Center, Text { text: "drop" } }
                    Chip { text: "chip", icon: IconRole::Check, selected: true, padding: 2.0 }
                    Badge { value: 3, color: ColorRole::Error, Icon { asset_id: 9 } }
                    Badge { text: "new".to_owned() }
                    SelectionContainer { Text { text: "copy me" } }
                    SplitPane {
                        value: 200.0,
                        min: 100.0,
                        max: 300.0,
                        collapsible: true,
                        selected_index: 1,
                        label: "Sidebar".to_owned(),
                        Text { text: "side" }
                        Text { text: "body" }
                    }
                    LinearProgressIndicator { progress: 0.75 }
                }
            }
        }
    }
}

mod compose_side {
    use compose_rust::runtime::*;
    use compose_rust::ui::*;
    use std::rc::Rc;

    use super::rsx_side::{MEMORY_KEYS, ROWS, color_for, variant_for};

    type Press = Rc<dyn Fn(&'static str)>;

    // ----- the calculator's screen, as samples/compose/calculator writes it --------------

    #[composable]
    fn keypad(press: Press) {
        Column()
            .modifier(Modifier.fill_max_width().fill_max_height())
            .space_role(SpaceRole::Xs)
            .content(|| {
                for (index, row) in ROWS.iter().enumerate() {
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

    #[composable]
    fn memory_row(memory_set: bool, press: Press) {
        Row()
            .modifier(Modifier.fill_max_width())
            .space_role(SpaceRole::Xs)
            .alignment(Alignment::CenterStart)
            .content(|| {
                for label in MEMORY_KEYS {
                    let press = Rc::clone(&press);
                    key(label, || {
                        Button(label)
                            .modifier(Modifier.weight(1.0))
                            .variant(ButtonVariant::Text)
                            .color(Paint::Role(ColorRole::OnSurfaceVariant))
                            .enabled(memory_set || !matches!(label, "MC" | "MR"))
                            .on_click(move || press(label));
                    });
                }
            });
    }

    #[composable]
    fn readout(status: String, display: String) {
        Box().modifier(Modifier.fill_max_width()).content(|| {
            Column()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Xs)
                .content(|| {
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

    #[derive(Clone, PartialEq)]
    struct TapeEntry {
        id: u64,
        expression: String,
        result: String,
        value: f64,
    }

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
                        Text("History")
                            .type_role(TypeRole::Subtitle)
                            .modifier(Modifier.weight(1.0));
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

    #[composable]
    pub fn calculator() {
        let display = remember(|| mutable_state_of("0".to_owned()));
        let entries = remember(|| {
            mutable_state_of_with_policy(Vec::<TapeEntry>::new(), never_equal_policy())
        });
        let next = remember(|| mutable_state_of(1_u64));
        let press: Press = {
            let (display, entries, next) = (display.clone(), entries.clone(), next.clone());
            Rc::new(move |label: &'static str| {
                if label == "=" {
                    let id = next.get_untracked();
                    next.set(id + 1);
                    let shown = display.get_untracked();
                    entries.update(|entries| {
                        entries.push(TapeEntry {
                            id,
                            expression: shown.clone(),
                            result: shown,
                            value: 0.0,
                        })
                    });
                } else {
                    display.set(label.to_owned());
                }
            })
        };
        let clear: Rc<dyn Fn()> = {
            let entries = entries.clone();
            Rc::new(move || entries.update(Vec::clear))
        };
        Column().modifier(Modifier.fill_max_width()).content(|| {
            readout(String::new(), display.get());
            memory_row(false, Rc::clone(&press));
            keypad(Rc::clone(&press));
            tape(entries.get(), Rc::new(|_: f64| {}), Rc::clone(&clear));
        });
    }

    // ----- the todo sample's bar, about panel and composer -------------------------------

    #[composable]
    fn list_bar(measure: Option<f32>, done: usize, total: usize, children: impl FnOnce()) {
        Column().modifier(Modifier.fill_max_width()).content(|| {
            TopAppBar().modifier(Modifier.fill_max_width()).content(|| {
                Box()
                    .modifier(Modifier.weight(1.0))
                    .alignment(Alignment::Center)
                    .content(|| {
                        Row()
                            .modifier(
                                Modifier
                                    .width_if(measure)
                                    .fill_max_width_if(measure.is_none())
                                    .padding_role_if(measure.map(|_| SpaceRole::Md)),
                            )
                            .space_role(SpaceRole::Sm)
                            .alignment(Alignment::CenterStart)
                            .content(children);
                    });
            });
            if total > 0 {
                ProgressIndicator(done as f32 / total as f32);
            }
        });
    }

    #[composable]
    fn about_panel(total: usize, fill: Rc<dyn Fn()>, close: Rc<dyn Fn()>) {
        Column()
            .modifier(Modifier.fill_max_width())
            .space_role(SpaceRole::Md)
            .content(|| {
                Row()
                    .modifier(Modifier.fill_max_width())
                    .alignment(Alignment::CenterStart)
                    .content(|| {
                        Text("About this sample")
                            .type_role(TypeRole::Subtitle)
                            .modifier(Modifier.weight(1.0));
                        let close = Rc::clone(&close);
                        Button("Close")
                            .variant(ButtonVariant::Filled)
                            .on_click(move || close());
                    });
                Separator(None);
                Text("The list windows its rows.")
                    .type_role(TypeRole::Body)
                    .color(Paint::Role(ColorRole::OnSurfaceVariant));
                Row()
                    .modifier(Modifier.fill_max_width())
                    .space_role(SpaceRole::Sm)
                    .alignment(Alignment::CenterStart)
                    .content(|| {
                        Text(format!("{total} tasks now"))
                            .type_role(TypeRole::Label)
                            .color(Paint::Role(ColorRole::OnSurfaceVariant))
                            .modifier(Modifier.weight(1.0));
                        let fill = Rc::clone(&fill);
                        Button("Add 5 tasks")
                            .variant(ButtonVariant::Tonal)
                            .on_click(move || fill());
                    });
            });
    }

    #[composable]
    fn composer(add: Rc<dyn Fn(String)>, draft: MutableState<String>) {
        Surface().modifier(Modifier.fill_max_width()).content(|| {
            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    let (typed, submit) = (draft.clone(), Rc::clone(&add));
                    TextField()
                        .modifier(Modifier.weight(1.0))
                        .placeholder("Add a task, then press Enter")
                        .on_value_change(move |value| typed.set(value))
                        .on_submit(move |value: String| submit(value));
                    let (pressed, current) = (Rc::clone(&add), draft.clone());
                    Button("Add")
                        .variant(ButtonVariant::Filled)
                        .on_click(move || pressed(current.get_untracked()));
                });
        });
    }

    #[composable]
    pub fn todo() {
        let titles =
            remember(|| mutable_state_of_with_policy(Vec::<String>::new(), never_equal_policy()));
        let draft = remember(|| mutable_state_of_with_policy(String::new(), never_equal_policy()));
        let total = titles.with(Vec::len);
        let add: Rc<dyn Fn(String)> = {
            let titles = titles.clone();
            Rc::new(move |title: String| {
                if !title.trim().is_empty() {
                    titles.update(|titles| titles.push(title));
                }
            })
        };
        Column().modifier(Modifier.fill_max_width()).content(|| {
            list_bar(Some(840.0), total / 2, total, || {
                Text(format!("{total} tasks"))
                    .type_role(TypeRole::Headline)
                    .modifier(Modifier.weight(1.0));
                if total > 3 {
                    let clearing = titles.clone();
                    Button("Clear")
                        .variant(ButtonVariant::Text)
                        .on_click(move || clearing.update(Vec::clear));
                }
            });
            composer(Rc::clone(&add), draft.clone());
            for (index, title) in titles.get().iter().enumerate() {
                key(index, || {
                    Text(title.clone());
                });
            }
            let fill: Rc<dyn Fn()> = {
                let titles = titles.clone();
                Rc::new(move || {
                    for number in 0..5 {
                        titles.update(|titles| titles.push(format!("Generated {number}")));
                    }
                })
            };
            about_panel(total, fill, Rc::new(|| {}));
        });
    }

    // ----- every widget ------------------------------------------------------------------

    #[composable]
    pub fn sink() {
        Scaffold()
            .modifier(
                Modifier
                    .background(Paint::Role(ColorRole::Background))
                    .material(MaterialRole::Chrome),
            )
            .top_bar(|| {
                TopAppBar().title("Sink").content(|| {
                    Text("bar");
                });
            })
            .bottom_bar(|| {
                Navigation(1)
                    .head(|| {
                        Text("head");
                    })
                    .foot(|| {
                        Text("foot");
                    })
                    .content(|| {
                        NavigationItem("One")
                            .icon(IconRole::Search)
                            .section("Group")
                            .on_click(|| {});
                        NavigationItem("Two")
                            .enabled(false)
                            .color(Paint::Role(ColorRole::Primary));
                    });
            })
            .floating_action(|| {
                FloatingAction("New").icon(IconRole::Search).on_click(|| {});
            })
            .content(|| {
                Column()
                    .arrangement(Arrangement::SpaceBetween)
                    .spacing(4.0)
                    .alignment(Alignment::CenterStart)
                    .modifier(
                        Modifier
                            .padding(8.0)
                            .background(Paint::Role(ColorRole::Surface))
                            .corner_radius(6.0)
                            .border(1.0, Paint::Role(ColorRole::Outline))
                            .elevation(2.0)
                            .motion(MotionRole::Quick),
                    )
                    .content(|| {
                        Row()
                            .space_role(SpaceRole::Sm)
                            .modifier(
                                Modifier
                                    .weight(1.0)
                                    .width(100.0)
                                    .height(40.0)
                                    .shape_role(ShapeRole::Small),
                            )
                            .content(|| {
                                Text("styled")
                                    .type_role(TypeRole::Title)
                                    .font_size(18.0)
                                    .font_weight(600)
                                    .line_height(22.0)
                                    .letter_spacing(0.5)
                                    .color(Paint::Literal(Color::rgb(0x123456)))
                                    .text_align(TextAlign::Center)
                                    .max_lines(2)
                                    .overflow(TextOverflow::Ellipsis);
                                Button("Go")
                                    .icon(IconRole::Search)
                                    .enabled(false)
                                    .variant(ButtonVariant::Outlined)
                                    .color(Paint::Role(ColorRole::Error));
                            });
                        Box()
                            .alignment(Alignment::Center)
                            .modifier(Modifier.padding_role(SpaceRole::Md))
                            .content(|| {
                                Text("boxed");
                            });
                        ScrollColumn().modifier(Modifier.height(50.0)).content(|| {
                            Text("scrolled");
                        });
                        ScrollRow().content(|| {
                            Text("sideways");
                        });
                        TextField()
                            .placeholder("Type")
                            .enabled(false)
                            .multiline(true)
                            .type_role(TypeRole::Mono);
                        Spacer().modifier(Modifier.height(8.0));
                        LazyColumn().content(|list| {
                            list.items(3, |index| {
                                Text(format!("{index}"));
                            })
                        });
                        Image(7).modifier(Modifier.width(24.0));
                        Icon(8).color(Paint::Role(ColorRole::Primary));
                        Checkbox(true);
                        RadioButton(true).enabled(false);
                        Switch(true);
                        Slider(0.5)
                            .range(0.0, 2.0)
                            .steps(3)
                            .color(Paint::Role(ColorRole::Primary));
                        ProgressIndicator(0.25).circular(true);
                        Divider().vertical(true);
                        Separator(None);
                        Card().modifier(Modifier.padding(4.0)).content(|| {
                            Text("card");
                        });
                        Surface()
                            .modifier(Modifier.material(MaterialRole::Thin))
                            .content(|| {
                                Text("surface");
                            });
                        Dialog(true).content(|| {
                            Text("dialog");
                        });
                        Menu(true)
                            .anchor(|| {
                                Button("anchor");
                            })
                            .content(|| {
                                Button("entry");
                            });
                        Tabs(1).color(Paint::Role(ColorRole::Primary)).content(|| {
                            Button("A");
                            Button("B");
                        });
                        LazyRow().content(|list| {
                            list.items(2, |index| {
                                Text(format!("{index}"));
                            })
                        });
                        Tooltip("tip").content(|| {
                            Button("hover");
                        });
                        Canvas(
                            DrawList::builder()
                                .line(Paint::Role(ColorRole::Primary), 0.0, 0.0, 10.0, 10.0, 1.0)
                                .build(),
                        )
                        .modifier(Modifier.width(20.0).height(20.0));
                        DatePicker(19_000).min(18_000).max(20_000);
                        TimePicker(600).max(1_200);
                        Dropdown(1).content(|| {
                            Text("x");
                            Text("y");
                        });
                        Sheet(false).content(|| {
                            Text("sheet");
                        });
                        LazyGrid().columns(3).content(|list| {
                            list.items(4, |index| {
                                Text(format!("{index}"));
                            })
                        });
                        FileDropTarget().alignment(Alignment::Center).content(|| {
                            Text("drop");
                        });
                        Chip("chip")
                            .icon(IconRole::Check)
                            .selected(true)
                            .modifier(Modifier.padding(2.0));
                        Badge().value(3).color(ColorRole::Error).content(|| {
                            Icon(9);
                        });
                        Badge().text("new");
                        SelectionContainer().content(|| {
                            Text("copy me");
                        });
                        SplitPane()
                            .value(200.0)
                            .min(100.0)
                            .max(300.0)
                            .collapsible(true)
                            .selected_index(1)
                            .label("Sidebar")
                            .content(|| {
                                Text("side");
                                Text("body");
                            });
                        LinearProgressIndicator(0.75);
                    });
            });
    }
}

/// Both paths of one fixture, side by side, with the tree each has built.
struct Pair {
    dioxus: Host,
    compose: ComposeHost,
    dioxus_tree: Tree,
    compose_tree: Tree,
}

impl Pair {
    fn start(dioxus: fn() -> dioxus_compose_adapter::Element, compose: fn()) -> Self {
        let mut pair = Self {
            dioxus: Host::new(dioxus),
            compose: ComposeHost::new(compose),
            dioxus_tree: Tree::default(),
            compose_tree: Tree::default(),
        };
        pair.dioxus_tree
            .apply(&pair.dioxus.rebuild().unwrap().to_vec());
        pair.compose_tree
            .apply(&pair.compose.rebuild().unwrap().to_vec());
        pair.assert_same("the first composition");
        pair
    }

    fn assert_same(&self, after: &str) {
        assert_eq!(
            self.compose_tree.dump(),
            self.dioxus_tree.dump(),
            "the two paths built different trees after {after}"
        );
    }

    /// Delivers the same event to the same widget on both paths.
    fn send(
        &mut self,
        widget: &str,
        label: Option<&str>,
        nth: usize,
        event: PropertyKind,
        payload: EventPayload<'_>,
    ) {
        let (node_id, handler_id) = self.dioxus_tree.find(widget, label, nth, event);
        let (batch, _) = self
            .dioxus
            .dispatch(HostEvent {
                node_id,
                handler_id,
                payload: payload.clone(),
            })
            .unwrap();
        let batch = batch.to_vec();
        self.dioxus_tree.apply(&batch);
        let (node_id, handler_id) = self.compose_tree.find(widget, label, nth, event);
        let (batch, _) = self
            .compose
            .dispatch(HostEvent {
                node_id,
                handler_id,
                payload,
            })
            .unwrap();
        let batch = batch.to_vec();
        self.compose_tree.apply(&batch);
        self.assert_same(&format!("{widget} {label:?} received an event"));
    }

    fn click(&mut self, label: &str) {
        self.send(
            "Button",
            Some(label),
            0,
            PropertyKind::OnClick,
            EventPayload::Clicked,
        );
    }
}

#[test]
fn fr39_the_calculator_screen_is_the_same_tree_on_both_paths() {
    let mut pair = Pair::start(rsx_side::calculator, compose_side::calculator);
    for key in ["7", "\u{00d7}", "2", "=", "C", "=", "Clear"] {
        pair.click(key);
    }
}

#[test]
fn fr39_the_todo_screen_is_the_same_tree_on_both_paths() {
    let mut pair = Pair::start(rsx_side::todo, compose_side::todo);
    let field = "Add a task, then press Enter";
    for title in ["Buy milk", "Call the bank"] {
        pair.send(
            "TextField",
            None,
            0,
            PropertyKind::OnValueChange,
            EventPayload::TextChanged(title),
        );
        pair.send(
            "TextField",
            None,
            0,
            PropertyKind::OnSubmit,
            EventPayload::TextSubmitted(title),
        );
    }
    let _ = field;
    pair.click("Add 5 tasks");
    pair.click("Clear");
}

#[test]
fn fr39_every_widget_writes_what_its_rsx_counterpart_writes() {
    let mut pair = Pair::start(rsx_side::sink, compose_side::sink);
    // The lazy lists compose nothing until a window is asked for.
    pair.send(
        "LazyColumn",
        None,
        0,
        PropertyKind::OnRangeRequested,
        EventPayload::RangeRequested { start: 0, count: 3 },
    );
    pair.send(
        "LazyGrid",
        None,
        0,
        PropertyKind::OnRangeRequested,
        EventPayload::RangeRequested { start: 0, count: 4 },
    );
}
