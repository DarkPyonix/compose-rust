//! The public `rsx!` widget surface, end to end.
//!
//! One screen uses every widget `dioxus_compose_adapter::prelude` exports, with its main
//! properties and its event handlers, and builds through the public `Host` the way an
//! application's own tests do. The assertions are on the decoded batch: each widget is
//! created as the kind the schema names, its properties arrive with the values written in
//! rsx, and each handler written in rsx is the one an event reaches.
//!
//! The coverage checks walk the schema tables rather than a list kept here, so a widget,
//! property or modifier added to the schema without a way to write it in rsx turns this
//! red. A new widget then needs two edits in this file: a use in `surface()` and a row in
//! `expectations()`.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};

use dioxus_compose_adapter::Host;
use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
use dioxus_compose_adapter::schema::{
    EventPayload, MODIFIER_SCHEMA, PROPERTY_SCHEMA, PropertyKind, WIDGET_SCHEMA, WidgetKind,
};
use dioxus_compose_adapter::spans::TextSpans;

thread_local! {
    /// What the handlers written in `surface()` were called with, in order.
    static FIRED: RefCell<Vec<String>> = const { RefCell::new(Vec::new()) };
}

fn fired(label: impl Into<String>) {
    FIRED.with(|log| log.borrow_mut().push(label.into()));
}

fn take_fired() -> Vec<String> {
    FIRED.with(|log| std::mem::take(&mut *log.borrow_mut()))
}

const SPANS_SOURCE: &str = "every **widget** in `rsx`";

/// The two lists a code editor carries, built the same way in surface() and expectations().
fn code_lists() -> (SyntaxSpans, Decorations) {
    (
        SyntaxSpans::new([SyntaxSpan::role(0, CodeRange::of(0, 0, 0, 1), ColorRole::Primary)]),
        Decorations::new([Decoration::underline(0, CodeRange::of(0, 4, 0, 5), Severity::Warning)]),
    )
}

fn spans() -> (String, TextSpans) {
    TextSpans::from_markdown(SPANS_SOURCE)
}

fn drawing() -> DrawList {
    DrawList::builder()
        .line(Paint::Role(ColorRole::Primary), 0.0, 0.0, 10.0, 10.0, 1.0)
        .rect(Paint::Role(ColorRole::Outline), 2.0, 2.0, 6.0, 6.0, 0.0)
        .build()
}

fn paint_bits(paint: Paint) -> i64 {
    paint.to_bits() as i64
}

const PRIMARY: Paint = Paint::Role(ColorRole::Primary);
const ERROR: Paint = Paint::Role(ColorRole::Error);
const SECONDARY: Paint = Paint::Role(ColorRole::Secondary);
const TERTIARY: Paint = Paint::Role(ColorRole::Tertiary);
const ON_SURFACE: Paint = Paint::Role(ColorRole::OnSurface);
const OUTLINE: Paint = Paint::Role(ColorRole::Outline);

/// Every element the prelude exports, once, with its main properties and handlers.
fn surface() -> Element {
    let (text, spans) = spans();
    rsx! {
        Scaffold {
            background: Paint::Role(ColorRole::Background),
            material: MaterialRole::Chrome,
            top_bar: rsx! {
                TopAppBar { title: "Inbox", fill_max_width: true }
            },
            bottom_bar: rsx! {
                Navigation {
                    selected_index: 1,
                    head: rsx! { Text { text: "head" } },
                    foot: rsx! { Text { text: "foot" } },
                    NavigationItem {
                        text: "Inbox",
                        icon: IconRole::Inbox,
                        color: SECONDARY,
                        enabled: true,
                        section: "Mail".to_string(),
                        on_click: move |_| fired("NavigationItem.on_click"),
                    }
                    NavigationItem { text: "Sent", icon: IconRole::Send }
                }
            },
            floating_action: rsx! {
                Button {
                    text: "Send",
                    icon: IconRole::Send,
                    enabled: true,
                    variant: ButtonVariant::Tonal,
                    color: ERROR,
                    on_click: move |_| fired("Button.on_click"),
                }
            },
            Column {
                observe_size: 42,
                motion: MotionRole::Quick,
                material: MaterialRole::Thin,
                weight: 1.0,
                fill_max_width: true,
                fill_max_height: true,
                padding: 12.0,
                background: Paint::Role(ColorRole::Surface),
                corner_radius: 8.0,
                border_width: 1.0,
                border_color: OUTLINE,
                elevation: 3.0,
                arrangement: Arrangement::SpaceBetween,
                spacing: 8.0,
                space_role: SpaceRole::Md,
                alignment: Alignment::Center,
                Row {
                    width: 320.0,
                    height: 48.0,
                    padding_role: SpaceRole::Sm,
                    shape_role: ShapeRole::Large,
                    arrangement: Arrangement::End,
                    spacing: 4.0,
                    space_role: SpaceRole::Xs,
                    alignment: Alignment::CenterStart,
                    Spacer { width: 16.0, height: 16.0 }
                    Separator {}
                    Divider { vertical: true }
                }
                dioxus_compose_adapter::Box {
                    alignment: Alignment::BottomEnd,
                    Text {
                        text: text.clone(),
                        spans: spans.clone(),
                        type_role: TypeRole::Title,
                        font_size: 18.0,
                        font_weight: 600,
                        line_height: 24.0,
                        letter_spacing: 0.5,
                        color: PRIMARY,
                        text_align: TextAlign::Center,
                        max_lines: 2,
                        overflow: TextOverflow::Ellipsis,
                    }
                }
                TextField {
                    placeholder: "Search",
                    enabled: true,
                    multiline: true,
                    type_role: TypeRole::Mono,
                    on_value_change: move |value: String| fired(format!("TextField.on_value_change({value})")),
                    on_submit: move |value: String| fired(format!("TextField.on_submit({value})")),
                    on_focus_lost: move |_| fired("TextField.on_focus_lost"),
                    on_key_down: move |event: KeyEvent| fired(format!("TextField.on_key_down({:?})", event.key())),
                }
                ScrollColumn {
                    fill_max_width: true,
                    Image { asset_id: 7, width: 64.0, height: 64.0 }
                    Icon { asset_id: 8, color: TERTIARY }
                }
                SplitPane {
                    value: 320.0,
                    min: 200.0,
                    max: 400.0,
                    collapsible: true,
                    selected_index: 1,
                    label: "Sessions",
                    on_change: move |value: f32| fired(format!("SplitPane.on_change({value})")),
                    on_dismiss: move |_| fired("SplitPane.on_dismiss"),
                    Column {}
                    Column {}
                }
                CodeEditor {
                    text: "x = 1",
                    syntax_spans: code_lists().0,
                    decorations: code_lists().1,
                    tab_width: 4_u32,
                    on_change: move |_| fired("CodeEditor.on_change"),
                    on_edit_rejected: move |_| fired("CodeEditor.on_edit_rejected"),
                    on_hover: move |_| fired("CodeEditor.on_hover"),
                    on_save: move |_| fired("CodeEditor.on_save"),
                    on_decoration_click: move |id: u64| fired(format!("CodeEditor.on_decoration_click({id})")),
                }
                SelectionContainer {
                    Badge {
                        value: 3,
                        color: ColorRole::Primary,
                        Text { text: "Inbox" }
                    }
                }
                ScrollRow {
                    fill_max_width: true,
                    Chip {
                        text: "Unread",
                        icon: IconRole::History,
                        selected: true,
                        enabled: true,
                        on_click: move |_| fired("Chip.on_click"),
                    }
                    FloatingAction {
                        text: "New",
                        icon: IconRole::Add,
                        on_click: move |_| fired("FloatingAction.on_click"),
                    }
                }
                Card {
                    elevation: 2.0,
                    Checkbox {
                        checked: true,
                        enabled: true,
                        on_change: move |value: bool| fired(format!("Checkbox.on_change({value})")),
                    }
                    RadioButton {
                        selected: true,
                        enabled: true,
                        on_change: move |value: bool| fired(format!("RadioButton.on_change({value})")),
                    }
                    Switch {
                        checked: true,
                        enabled: true,
                        on_change: move |value: bool| fired(format!("Switch.on_change({value})")),
                    }
                }
                Surface {
                    background: Paint::Role(ColorRole::SurfaceVariant),
                    Slider {
                        value: 0.25,
                        min: -1.0,
                        max: 2.0,
                        steps: 4,
                        enabled: true,
                        color: PRIMARY,
                        on_change: move |value: f32| fired(format!("Slider.on_change({value})")),
                    }
                    ProgressIndicator { value: 0.75, determinate: true, circular: true }
                    LinearProgressIndicator { progress: 0.5 }
                }
                Tabs {
                    selected_index: 1,
                    color: ON_SURFACE,
                    Text { text: "first tab" }
                    Text { text: "second tab" }
                }
                Tooltip {
                    text: "More options",
                    Text { text: "hover me" }
                }
                Canvas { width: 10.0, height: 10.0, commands: drawing() }
                DatePicker {
                    value: 19723,
                    min: 19000,
                    max: 20000,
                    enabled: true,
                    on_change: move |value: i64| fired(format!("DatePicker.on_change({value})")),
                }
                TimePicker {
                    value: 615,
                    min: 480,
                    max: 1080,
                    enabled: true,
                    on_change: move |value: u32| fired(format!("TimePicker.on_change({value})")),
                }
                Dropdown {
                    selected_index: 1,
                    enabled: true,
                    on_change: move |value: usize| fired(format!("Dropdown.on_change({value})")),
                    Text { text: "one" }
                    Text { text: "two" }
                    Text { text: "three" }
                }
                LazyColumn {
                    item_count: 3,
                    key_of: move |index: usize| format!("message-{index}"),
                    item: move |index: usize| rsx! { Text { text: "message {index}" } },
                }
                LazyRow {
                    item_count: 4,
                    item: move |index: usize| rsx! { Text { text: "chip {index}" } },
                }
                LazyGrid {
                    item_count: 6,
                    columns: 3,
                    min_column_width: 120.0,
                    item: move |index: usize| rsx! { Text { text: "tile {index}" } },
                }
                FileDropTarget {
                    alignment: Alignment::Center,
                    on_files_entered: move |_| fired("FileDropTarget.on_files_entered"),
                    on_files_dropped: move |drop: FileDrop| {
                        fired(format!("FileDropTarget.on_files_dropped({:?})", drop.paths()))
                    },
                    Text { text: "drop files here" }
                }
                Dialog {
                    open: true,
                    on_dismiss: move |_| fired("Dialog.on_dismiss"),
                    Text { text: "dialog body" }
                }
                Menu {
                    expanded: true,
                    on_dismiss: move |_| fired("Menu.on_dismiss"),
                    anchor: rsx! { Text { text: "menu anchor" } },
                    Text { text: "menu entry" }
                }
                Sheet {
                    open: true,
                    on_dismiss: move |_| fired("Sheet.on_dismiss"),
                    Text { text: "sheet body" }
                }
            }
        }
    }
}

/// A property value as it arrived, owned so it outlives the batch it came in.
#[derive(Clone, Debug, PartialEq)]
enum Value {
    None,
    String(String),
    Bool(bool),
    Integer(i64),
    Float(f32),
    Bytes(Vec<u8>),
}

impl From<&PropertyValue<'_>> for Value {
    fn from(value: &PropertyValue<'_>) -> Self {
        match value {
            PropertyValue::None => Self::None,
            PropertyValue::String(text) => Self::String((*text).to_owned()),
            PropertyValue::Bool(flag) => Self::Bool(*flag),
            PropertyValue::Integer(number) => Self::Integer(*number),
            PropertyValue::Float(number) => Self::Float(*number),
            PropertyValue::Bytes(bytes) => Self::Bytes(bytes.to_vec()),
        }
    }
}

#[derive(Debug)]
struct Node {
    kind: WidgetKind,
    /// The latest value of each property, keyed by property.
    props: BTreeMap<u16, (PropertyKind, Value)>,
}

/// Everything the Host has told the Renderer so far, by node.
#[derive(Default)]
struct Wire {
    nodes: BTreeMap<u32, Node>,
    /// The name of every modifier variant that reached the wire.
    modifiers: BTreeSet<String>,
}

impl Wire {
    fn apply(&mut self, batch: &[u8]) {
        let mutations = decode_batch(batch).expect("the Host produced a batch it cannot decode");
        for mutation in &mutations {
            match mutation {
                Mutation::Create { node_id, widget } => {
                    self.nodes.insert(
                        *node_id,
                        Node {
                            kind: *widget,
                            props: BTreeMap::new(),
                        },
                    );
                }
                Mutation::SetProp {
                    node_id,
                    property,
                    value,
                } => {
                    let node = self.nodes.get_mut(node_id).unwrap_or_else(|| {
                        panic!("a property {property:?} was set on node {node_id}, which was never created")
                    });
                    node.props
                        .insert(*property as u16, (*property, Value::from(value)));
                }
                Mutation::SetModifier { modifier, .. } => {
                    let debug = format!("{modifier:?}");
                    let name = debug
                        .split(|c: char| !c.is_alphanumeric())
                        .next()
                        .unwrap_or_default()
                        .to_owned();
                    self.modifiers.insert(name);
                }
                _ => {}
            }
        }
    }

    fn of_kind(&self, kind: WidgetKind) -> impl Iterator<Item = (u32, &Node)> {
        self.nodes
            .iter()
            .filter(move |(_, node)| node.kind == kind)
            .map(|(id, node)| (*id, node))
    }

    /// The node of `kind` that carries a handler under `property`, and the handler's id.
    fn handler(&self, kind: WidgetKind, property: PropertyKind) -> (u32, u64) {
        self.of_kind(kind)
            .find_map(|(id, node)| match node.props.get(&(property as u16)) {
                Some((_, Value::Integer(handler))) if *handler > 0 => Some((id, *handler as u64)),
                _ => None,
            })
            .unwrap_or_else(|| {
                panic!(
                    "no {kind:?} node carries a handler under {property:?}, so an event the \
                     Renderer reports for it has nowhere to go; the {kind:?} nodes carry {:?}",
                    self.of_kind(kind)
                        .map(|(_, node)| node.props.values().map(|(p, _)| *p).collect::<Vec<_>>())
                        .collect::<Vec<_>>()
                )
            })
    }
}

/// Builds the surface and asks each windowed list for its first items, so the item
/// wrappers and their keys are on the wire as well.
fn build() -> (Host, Wire) {
    let mut host = Host::new(surface);
    let mut wire = Wire::default();
    wire.apply(host.rebuild().expect("the first frame should build"));
    for kind in [
        WidgetKind::LazyColumn,
        WidgetKind::LazyRow,
        WidgetKind::LazyGrid,
    ] {
        let (node_id, handler_id) = wire.handler(kind, PropertyKind::OnRangeRequested);
        let batch = host
            .dispatch(HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::RangeRequested { start: 0, count: 2 },
            })
            .unwrap_or_else(|error| panic!("asking {kind:?} for its first items failed: {error:?}"))
            .0
            .to_vec();
        wire.apply(&batch);
    }
    (host, wire)
}

enum Expect {
    Is(Value),
    /// A handler id: what crosses for a handler is a number the Host chose, and only
    /// that it is there means anything.
    Handler,
}

use Expect::Handler;

fn is(value: Value) -> Expect {
    Expect::Is(value)
}

fn int(value: i64) -> Expect {
    is(Value::Integer(value))
}

fn float(value: f32) -> Expect {
    is(Value::Float(value))
}

fn flag(value: bool) -> Expect {
    is(Value::Bool(value))
}

fn text(value: &str) -> Expect {
    is(Value::String(value.to_owned()))
}

fn role(value: impl Into<u16>) -> Expect {
    int(i64::from(value.into()))
}

/// What each widget in `surface()` should carry on the wire. A kind can appear more than
/// once when the surface uses it in more than one way.
fn expectations() -> Vec<(WidgetKind, Vec<(PropertyKind, Expect)>)> {
    use PropertyKind as P;
    use WidgetKind as W;
    let (spans_text, spans) = spans();
    vec![
        (
            W::Column,
            vec![
                (P::Arrangement, role(Arrangement::SpaceBetween)),
                (P::Spacing, float(8.0)),
                (P::SpaceRole, role(SpaceRole::Md)),
                (P::Alignment, role(Alignment::Center)),
            ],
        ),
        (
            W::Row,
            vec![
                (P::Arrangement, role(Arrangement::End)),
                (P::Spacing, float(4.0)),
                (P::SpaceRole, role(SpaceRole::Xs)),
                (P::Alignment, role(Alignment::CenterStart)),
            ],
        ),
        (W::Box, vec![(P::Alignment, role(Alignment::BottomEnd))]),
        // The item wrappers a windowed list materialises, under the key the screen gave.
        (W::Box, vec![(P::ItemKey, text("message-0"))]),
        (W::Box, vec![(P::ItemKey, text("message-1"))]),
        (
            W::Text,
            vec![
                (P::Text, text(&spans_text)),
                (P::Spans, is(Value::Bytes(spans.as_bytes().to_vec()))),
                (P::TypeRole, role(TypeRole::Title)),
                (P::FontSize, float(18.0)),
                (P::FontWeight, int(600)),
                (P::LineHeight, float(24.0)),
                (P::LetterSpacing, float(0.5)),
                (P::Color, int(paint_bits(PRIMARY))),
                (P::TextAlign, role(TextAlign::Center)),
                (P::MaxLines, int(2)),
                (P::Overflow, role(TextOverflow::Ellipsis)),
            ],
        ),
        (W::Text, vec![(P::Text, text("message 1"))]),
        (W::Text, vec![(P::Text, text("chip 0"))]),
        (W::Text, vec![(P::Text, text("tile 1"))]),
        (
            W::TextField,
            vec![
                (P::Placeholder, text("Search")),
                (P::Enabled, flag(true)),
                (P::Multiline, flag(true)),
                (P::TypeRole, role(TypeRole::Mono)),
                (P::OnValueChange, Handler),
                (P::OnSubmit, Handler),
                (P::OnFocusLost, Handler),
                (P::OnKeyDown, Handler),
            ],
        ),
        (
            W::Button,
            vec![
                (P::Text, text("Send")),
                (P::Icon, role(IconRole::Send)),
                (P::Enabled, flag(true)),
                (P::Variant, role(ButtonVariant::Tonal)),
                (P::Color, int(paint_bits(ERROR))),
                (P::OnClick, Handler),
            ],
        ),
        (W::Spacer, vec![]),
        (
            W::LazyColumn,
            vec![(P::ItemCount, int(3)), (P::OnRangeRequested, Handler)],
        ),
        (W::ScrollColumn, vec![]),
        (W::ScrollRow, vec![]),
        (
            W::CodeEditor,
            vec![
                (P::Text, text("x = 1")),
                (P::SyntaxSpans, is(Value::Bytes(code_lists().0.as_bytes().to_vec()))),
                (P::Decorations, is(Value::Bytes(code_lists().1.as_bytes().to_vec()))),
                (P::TabWidth, int(4)),
                (P::OnValueChange, Handler),
                (P::OnEditRejected, Handler),
                (P::OnHover, Handler),
                (P::OnSave, Handler),
                (P::OnDecorationClick, Handler),
            ],
        ),
        (
            W::SplitPane,
            vec![
                (P::Value, float(320.0)),
                (P::Min, float(200.0)),
                (P::Max, float(400.0)),
                (P::Collapsible, flag(true)),
                (P::SelectedIndex, int(1)),
                (P::Text, text("Sessions")),
                (P::OnValueChange, Handler),
                (P::OnDismiss, Handler),
            ],
        ),
        (W::SelectionContainer, vec![]),
        (
            W::Badge,
            vec![
                (P::Count, int(3)),
                (P::Color, int(paint_bits(Paint::Role(ColorRole::Primary)))),
            ],
        ),
        (
            W::Chip,
            vec![
                (P::Text, text("Unread")),
                (P::Icon, role(IconRole::History)),
                (P::Checked, flag(true)),
                (P::Enabled, flag(true)),
                (P::OnClick, Handler),
            ],
        ),
        (
            W::FloatingAction,
            vec![
                (P::Text, text("New")),
                (P::Icon, role(IconRole::Add)),
                (P::OnClick, Handler),
            ],
        ),
        (W::Image, vec![(P::Asset, int(7))]),
        (
            W::Icon,
            vec![(P::Asset, int(8)), (P::Color, int(paint_bits(TERTIARY)))],
        ),
        (
            W::Checkbox,
            vec![
                (P::Checked, flag(true)),
                (P::Enabled, flag(true)),
                (P::OnValueChange, Handler),
            ],
        ),
        (
            W::RadioButton,
            vec![
                (P::Checked, flag(true)),
                (P::Enabled, flag(true)),
                (P::OnValueChange, Handler),
            ],
        ),
        (
            W::Switch,
            vec![
                (P::Checked, flag(true)),
                (P::Enabled, flag(true)),
                (P::OnValueChange, Handler),
            ],
        ),
        (
            W::Slider,
            vec![
                (P::Value, float(0.25)),
                (P::Min, float(-1.0)),
                (P::Max, float(2.0)),
                (P::Steps, int(4)),
                (P::Enabled, flag(true)),
                (P::Color, int(paint_bits(PRIMARY))),
                (P::OnValueChange, Handler),
            ],
        ),
        (
            W::ProgressIndicator,
            vec![
                (P::Value, float(0.75)),
                (P::Determinate, flag(true)),
                (P::Circular, flag(true)),
            ],
        ),
        (W::Divider, vec![(P::Vertical, flag(true))]),
        (W::Card, vec![]),
        (W::Surface, vec![]),
        (
            W::Dialog,
            vec![(P::Open, flag(true)), (P::OnDismiss, Handler)],
        ),
        (
            W::Menu,
            vec![(P::Open, flag(true)), (P::OnDismiss, Handler)],
        ),
        (
            W::Tabs,
            vec![
                (P::SelectedIndex, int(1)),
                (P::Color, int(paint_bits(ON_SURFACE))),
            ],
        ),
        (W::TopAppBar, vec![(P::Text, text("Inbox"))]),
        (
            W::LazyRow,
            vec![(P::ItemCount, int(4)), (P::OnRangeRequested, Handler)],
        ),
        (W::Tooltip, vec![(P::Text, text("More options"))]),
        (
            W::Canvas,
            vec![(P::Commands, is(Value::Bytes(drawing().as_bytes().to_vec())))],
        ),
        (
            W::DatePicker,
            vec![
                (P::Value, int(19723)),
                (P::Min, int(19000)),
                (P::Max, int(20000)),
                (P::Enabled, flag(true)),
                (P::OnValueChange, Handler),
            ],
        ),
        (
            W::TimePicker,
            vec![
                (P::Value, int(615)),
                (P::Min, int(480)),
                (P::Max, int(1080)),
                (P::Enabled, flag(true)),
                (P::OnValueChange, Handler),
            ],
        ),
        (
            W::Dropdown,
            vec![
                (P::SelectedIndex, int(1)),
                (P::Enabled, flag(true)),
                (P::OnValueChange, Handler),
            ],
        ),
        (W::Navigation, vec![(P::SelectedIndex, int(1))]),
        (
            W::NavigationItem,
            vec![
                (P::Text, text("Inbox")),
                (P::Icon, role(IconRole::Inbox)),
                (P::Color, int(paint_bits(SECONDARY))),
                (P::Enabled, flag(true)),
                (P::Section, text("Mail")),
                (P::OnClick, Handler),
            ],
        ),
        (W::NavigationItem, vec![(P::Text, text("Sent"))]),
        (
            W::Sheet,
            vec![(P::Open, flag(true)), (P::OnDismiss, Handler)],
        ),
        (W::Scaffold, vec![]),
        // One slot per part of the frame the screen filled, and the two a navigation's
        // head and foot arrive in, which use the same numbers.
        (W::ScaffoldSlot, vec![(P::Slot, int(1))]),
        (W::ScaffoldSlot, vec![(P::Slot, int(2))]),
        (W::ScaffoldSlot, vec![(P::Slot, int(3))]),
        (W::ScaffoldSlot, vec![(P::Slot, int(4))]),
        (
            W::LazyGrid,
            vec![
                (P::ItemCount, int(6)),
                (P::Columns, int(3)),
                (P::MinColumnWidth, float(120.0)),
                (P::OnRangeRequested, Handler),
            ],
        ),
        (
            W::FileDropTarget,
            vec![
                (P::Alignment, role(Alignment::Center)),
                (P::OnFilesEntered, Handler),
                (P::OnFilesDropped, Handler),
            ],
        ),
        (W::LinearProgressIndicator, vec![(P::Progress, float(0.5))]),
    ]
}

fn satisfies(node: &Node, expected: &[(PropertyKind, Expect)]) -> bool {
    expected.iter().all(
        |(property, expect)| match (node.props.get(&(*property as u16)), expect) {
            (Some((_, value)), Expect::Is(wanted)) => value == wanted,
            (Some((_, Value::Integer(handler))), Expect::Handler) => *handler > 0,
            _ => false,
        },
    )
}

#[test]
fn fr15_every_widget_in_the_schema_has_an_rsx_element() {
    let (_host, wire) = build();
    let created: BTreeSet<u16> = wire.nodes.values().map(|node| node.kind as u16).collect();
    let missing: Vec<String> = WIDGET_SCHEMA
        .iter()
        .filter(|widget| !created.contains(&widget.tag))
        .map(|widget| format!("{} (tag {})", widget.name, widget.tag))
        .collect();
    assert!(
        missing.is_empty(),
        "these widgets are in the schema, but nothing written with the rsx! elements \
         dioxus_compose_adapter::prelude exports created one: {missing:?}. Either the widget has no \
         rsx! element or component an application can write, or it has one and \
         surface() in tests/rsx_surface.rs does not use it yet; in the second case add it \
         there and add its properties to expectations()."
    );
}

#[test]
fn fr15_every_rsx_element_sends_its_properties() {
    let (_host, wire) = build();
    let mut failures = Vec::new();
    for (kind, expected) in expectations() {
        if !wire
            .of_kind(kind)
            .any(|(_, node)| satisfies(node, &expected))
        {
            let wanted: Vec<String> = expected
                .iter()
                .map(|(property, expect)| match expect {
                    Expect::Is(value) => format!("{property:?} = {value:?}"),
                    Expect::Handler => format!("{property:?} = <handler id>"),
                })
                .collect();
            let seen: Vec<Vec<String>> = wire
                .of_kind(kind)
                .map(|(_, node)| {
                    node.props
                        .values()
                        .map(|(property, value)| format!("{property:?} = {value:?}"))
                        .collect()
                })
                .collect();
            failures.push(format!(
                "no {kind:?} node carried all of {wanted:?}; the {kind:?} nodes carried {seen:?}"
            ));
        }
    }
    assert!(
        failures.is_empty(),
        "properties written in rsx did not arrive on the wire as written:\n{}",
        failures.join("\n")
    );
}

#[test]
fn fr15_every_property_in_the_schema_is_reachable_from_rsx() {
    let (_host, wire) = build();
    let sent: BTreeSet<u16> = wire
        .nodes
        .values()
        .flat_map(|node| node.props.keys().copied())
        .collect();
    let missing: Vec<String> = PROPERTY_SCHEMA
        .iter()
        .filter(|property| !sent.contains(&property.tag))
        .map(|property| format!("{} (tag {})", property.name, property.tag))
        .collect();
    assert!(
        missing.is_empty(),
        "these properties are in the schema, but nothing written with the public rsx! \
         elements sent one: {missing:?}. Either no element exposes the property, or \
         surface() in tests/rsx_surface.rs does not set it yet."
    );
}

/// Modifiers the protocol carries that no rsx attribute writes today. They are named here
/// so the check below still catches a new modifier that arrives without an attribute,
/// and so a reader can see the gap rather than find it.
///
/// - `Size`: there is no `size` attribute; `width` and `height` are written separately.
/// - `PaddingEach`: there is no attribute for four different paddings.
/// - `Clickable`: the attribute is declared as `onclickable`, and rsx reads any attribute
///   starting with `on` as an event, which has to be a function in `events`. There is
///   none by that name, so the attribute cannot be written.
const MODIFIERS_WITHOUT_AN_RSX_ATTRIBUTE: &[&str] = &["Size", "PaddingEach", "Clickable"];

#[test]
fn fr15_every_modifier_in_the_schema_is_reachable_from_rsx() {
    let (_host, wire) = build();
    for name in MODIFIERS_WITHOUT_AN_RSX_ATTRIBUTE {
        assert!(
            MODIFIER_SCHEMA
                .iter()
                .any(|modifier| modifier.name == *name),
            "{name} is listed as a modifier rsx cannot write, but the schema no longer has \
             it; take it off the list"
        );
    }
    let missing: Vec<&str> = MODIFIER_SCHEMA
        .iter()
        .map(|modifier| modifier.name)
        .filter(|name| !MODIFIERS_WITHOUT_AN_RSX_ATTRIBUTE.contains(name))
        .filter(|name| !wire.modifiers.contains(*name))
        .collect();
    assert!(
        missing.is_empty(),
        "these modifiers are in the schema, but no Modifier attribute written in rsx sent \
         one: {missing:?}. The modifiers that did arrive were {:?}.",
        wire.modifiers
    );
}

#[test]
fn fr15_every_event_handler_written_in_rsx_is_called() {
    use PropertyKind as P;
    use WidgetKind as W;
    let (mut host, wire) = build();
    take_fired();
    let events: &[(W, P, EventPayload<'static>, &str)] = &[
        (
            W::Button,
            P::OnClick,
            EventPayload::Clicked,
            "Button.on_click",
        ),
        (
            W::NavigationItem,
            P::OnClick,
            EventPayload::Clicked,
            "NavigationItem.on_click",
        ),
        (W::Chip, P::OnClick, EventPayload::Clicked, "Chip.on_click"),
        (
            W::FloatingAction,
            P::OnClick,
            EventPayload::Clicked,
            "FloatingAction.on_click",
        ),
        (
            W::TextField,
            P::OnValueChange,
            EventPayload::TextChanged("한글"),
            "TextField.on_value_change(한글)",
        ),
        (
            W::TextField,
            P::OnSubmit,
            EventPayload::TextSubmitted("done"),
            "TextField.on_submit(done)",
        ),
        (
            W::TextField,
            P::OnFocusLost,
            EventPayload::FocusLost,
            "TextField.on_focus_lost",
        ),
        (
            W::TextField,
            P::OnKeyDown,
            EventPayload::KeyDown {
                key: Key::Enter,
                shift_key: false,
                ctrl_key: false,
                alt_key: false,
                meta_key: false,
            },
            "TextField.on_key_down(Enter)",
        ),
        (
            W::Checkbox,
            P::OnValueChange,
            EventPayload::ValueChanged(0.0),
            "Checkbox.on_change(false)",
        ),
        (
            W::RadioButton,
            P::OnValueChange,
            EventPayload::ValueChanged(0.0),
            "RadioButton.on_change(false)",
        ),
        (
            W::Switch,
            P::OnValueChange,
            EventPayload::ValueChanged(1.0),
            "Switch.on_change(true)",
        ),
        (
            W::Slider,
            P::OnValueChange,
            EventPayload::ValueChanged(0.5),
            "Slider.on_change(0.5)",
        ),
        (
            W::DatePicker,
            P::OnValueChange,
            EventPayload::ValueChanged(19800.0),
            "DatePicker.on_change(19800)",
        ),
        (
            W::TimePicker,
            P::OnValueChange,
            EventPayload::ValueChanged(700.0),
            "TimePicker.on_change(700)",
        ),
        (
            W::Dropdown,
            P::OnValueChange,
            EventPayload::ValueChanged(2.0),
            "Dropdown.on_change(2)",
        ),
        (
            W::SplitPane,
            P::OnValueChange,
            EventPayload::ValueChanged(280.0),
            "SplitPane.on_change(280)",
        ),
        (
            W::SplitPane,
            P::OnDismiss,
            EventPayload::Clicked,
            "SplitPane.on_dismiss",
        ),
        (
            W::Dialog,
            P::OnDismiss,
            EventPayload::Clicked,
            "Dialog.on_dismiss",
        ),
        (
            W::Menu,
            P::OnDismiss,
            EventPayload::Clicked,
            "Menu.on_dismiss",
        ),
        (
            W::Sheet,
            P::OnDismiss,
            EventPayload::Clicked,
            "Sheet.on_dismiss",
        ),
        (
            W::FileDropTarget,
            P::OnFilesEntered,
            EventPayload::FilesEntered,
            "FileDropTarget.on_files_entered",
        ),
        (
            W::FileDropTarget,
            P::OnFilesDropped,
            EventPayload::FilesDropped("/tmp/a.txt\0/tmp/b.txt"),
            "FileDropTarget.on_files_dropped([\"/tmp/a.txt\", \"/tmp/b.txt\"])",
        ),
    ];
    let mut failures = Vec::new();
    for (kind, property, payload, label) in events.iter().cloned() {
        let (node_id, handler_id) = wire.handler(kind, property);
        if let Err(error) = host.dispatch(HostEvent {
            node_id,
            handler_id,
            payload,
        }) {
            failures.push(format!(
                "{kind:?} {property:?}: the Host refused the event: {error:?}"
            ));
            continue;
        }
        let calls = take_fired();
        if calls != [label] {
            failures.push(format!(
                "{kind:?} {property:?}: expected the handler written in rsx to be called \
                 once as {label:?}, but the calls were {calls:?}"
            ));
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}
