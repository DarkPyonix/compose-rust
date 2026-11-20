//! The report that a node's size motion has settled: the handler property and the event
//! on the wire, and how the event reaches the handler the node was given.

mod support;

use compose_rust::ComposeHost;
use compose_rust::MotionFinished;
use compose_rust::protocol::{
    HostEvent, Mutation, ProtocolError, decode_batch, decode_event, encode_event,
};
use compose_rust::schema::{EVENT_SCHEMA, EventPayload, PropertyKind};
use compose_rust::ui::*;
use std::sync::{Arc, Mutex};
use support::Tree;

const EVENTS: &[u8] = include_bytes!("vectors/events.bin");

/// Where the report sits in the event vector, and how long it is.
const REPORT_AT: usize = 1443;
const REPORT_LEN: usize = 24;

fn vector_report() -> HostEvent<'static> {
    HostEvent::new(
        14,
        30,
        EventPayload::MotionFinished {
            width_dp: 240.5,
            height_dp: 0.0,
        },
    )
}

fn event_tag(name: &str) -> u16 {
    EVENT_SCHEMA
        .iter()
        .find(|event| event.name == name)
        .unwrap_or_else(|| panic!("{name} is in the event schema"))
        .tag
}

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

#[test]
fn fr24_motion_finished_round_trips() {
    assert_eq!(PropertyKind::OnMotionFinished as u16, 193);
    assert_eq!(event_tag("MotionFinished"), 63);

    // The record is the 16 byte header, then the width and the height as f32 in dp.
    let mut wire = Vec::new();
    encode_event(&vector_report(), &mut wire).expect("the report encodes");
    assert_eq!(wire.len(), REPORT_LEN);
    assert_eq!(&wire[0..2], &63_u16.to_le_bytes());
    assert_eq!(&wire[2..4], &(REPORT_LEN as u16).to_le_bytes());
    assert_eq!(&wire[4..8], &14_u32.to_le_bytes());
    assert_eq!(&wire[8..16], &30_u64.to_le_bytes());
    assert_eq!(&wire[16..20], &240.5_f32.to_bits().to_le_bytes());
    assert_eq!(&wire[20..24], &0.0_f32.to_bits().to_le_bytes());
    assert_eq!(decode_event(&wire), Ok(vector_report()));

    // The same bytes the Kotlin side writes, read from the shared vector.
    let vector = &EVENTS[REPORT_AT..REPORT_AT + REPORT_LEN];
    assert_eq!(vector, wire.as_slice());
    assert_eq!(decode_event(vector), Ok(vector_report()));

    // A record of any other length is refused rather than read short.
    let mut short = wire[..20].to_vec();
    short[2..4].copy_from_slice(&20_u16.to_le_bytes());
    assert_eq!(
        decode_event(&short),
        Err(ProtocolError::InvalidRecordLength)
    );

    // The handler property travels as a handler id, like every other handler.
    let (_host, tree) = start(|| {
        Box!(
            modifier = Modifier.motion(MotionRole::Standard),
            on_motion_finished = Some(|_: ::compose_rust::MotionFinished| {}),
            {}
        );
    });
    assert_eq!(tree.handled("Box", PropertyKind::OnMotionFinished).len(), 1);
}

/// A report reaches the closure the node was given, once for each report, with the size
/// it carries.
#[test]
fn fr24_motion_finished_reports_once_with_the_final_size() {
    let seen: Arc<Mutex<Vec<MotionFinished>>> = Arc::new(Mutex::new(Vec::new()));
    let sink = Arc::clone(&seen);
    let (mut host, tree) = start(move || {
        let sink = Arc::clone(&sink);
        Column!(
            modifier = Modifier.motion(MotionRole::Standard).height(120.0),
            on_motion_finished = Some(move |finished: ::compose_rust::MotionFinished| sink
                .lock()
                .unwrap()
                .push(finished)),
            {
                Text!("collapsing");
            }
        );
    });
    let handled = tree.handled("Column", PropertyKind::OnMotionFinished);
    assert_eq!(handled.len(), 1, "{}", tree.dump());
    let (node_id, handler_id) = handled[0];
    assert!(seen.lock().unwrap().is_empty(), "nothing before a report");

    host.dispatch(HostEvent::new(
        node_id,
        handler_id,
        EventPayload::MotionFinished {
            width_dp: 320.0,
            height_dp: 0.0,
        },
    ))
    .expect("the report is accepted");
    assert_eq!(
        *seen.lock().unwrap(),
        vec![MotionFinished {
            width_dp: 320.0,
            height_dp: 0.0,
        }]
    );

    // Through the boundary's own entry point, from the bytes the Renderer writes.
    let mut wire = Vec::new();
    encode_event(
        &HostEvent::new(
            node_id,
            handler_id,
            EventPayload::MotionFinished {
                width_dp: 320.0,
                height_dp: 120.0,
            },
        ),
        &mut wire,
    )
    .unwrap();
    host.dispatch_event(&wire)
        .expect("the encoded report is accepted");
    assert_eq!(
        *seen.lock().unwrap(),
        vec![
            MotionFinished {
                width_dp: 320.0,
                height_dp: 0.0,
            },
            MotionFinished {
                width_dp: 320.0,
                height_dp: 120.0,
            },
        ]
    );
}

/// A node given no handler writes no handler property, so its wire is what it was before
/// the property existed; a text field takes the handler the same way a container does.
#[test]
fn fr24_no_handler_means_no_listener() {
    let mut host = ComposeHost::with_content(|| {
        Column!(modifier = Modifier.motion(MotionRole::Standard), {
            Text!("plain");
            TextField!("", |_| {}, modifier = Modifier.motion(MotionRole::Quick));
        });
    });
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the batch decodes");
    assert!(
        !mutations.iter().any(|mutation| matches!(
            mutation,
            Mutation::SetProp {
                property: PropertyKind::OnMotionFinished,
                ..
            }
        )),
        "a node with no handler wrote one: {mutations:?}"
    );

    let (_host, tree) = start(|| {
        TextField!(
            "",
            |_| {},
            modifier = Modifier.motion(MotionRole::Quick),
            on_motion_finished = Some(|_: ::compose_rust::MotionFinished| {})
        );
    });
    assert_eq!(
        tree.handled("TextField", PropertyKind::OnMotionFinished)
            .len(),
        1
    );
}

/// Every report a family's handlers heard, each with the name of the builder that heard it.
type Heard = Arc<Mutex<Vec<(&'static str, MotionFinished)>>>;

/// A handler that writes what it is told under `name`.
fn hears(heard: &Heard, name: &'static str) -> impl FnMut(MotionFinished) + 'static {
    let heard = Arc::clone(heard);
    move |finished| heard.lock().unwrap().push((name, finished))
}

/// Composes `content`, whose builders each carry a motion role and a handler made by
/// `hears`, and checks every one of `expected`: its node has the handler property, and a
/// report sent to that node reaches its own closure once, with the size the report holds.
///
/// `expected` names each builder with the widget its node is on the wire, in the order
/// `content` composes them.
fn assert_each_reports(
    content: impl Fn(&Heard) + Send + Sync + 'static,
    expected: &[(&'static str, &'static str)],
) {
    let heard: Heard = Arc::new(Mutex::new(Vec::new()));
    let sink = Arc::clone(&heard);
    let (mut host, tree) = start(move || content(&sink));
    for (index, (name, widget)) in expected.iter().enumerate() {
        let earlier = expected[..index]
            .iter()
            .filter(|(_, other)| other == widget)
            .count();
        let total = expected.iter().filter(|(_, other)| other == widget).count();
        let handled = tree.handled(widget, PropertyKind::OnMotionFinished);
        assert_eq!(
            handled.len(),
            total,
            "{name}: {widget} nodes with a motion handler\n{}",
            tree.dump()
        );
        let (node_id, handler_id) = handled[earlier];
        let size = MotionFinished {
            width_dp: 100.0 + index as f32,
            height_dp: 20.0 + index as f32,
        };
        host.dispatch(HostEvent::new(
            node_id,
            handler_id,
            EventPayload::MotionFinished {
                width_dp: size.width_dp,
                height_dp: size.height_dp,
            },
        ))
        .unwrap_or_else(|error| panic!("{name}: the report was refused: {error:?}"));
        let heard = heard.lock().unwrap();
        assert_eq!(
            heard.len(),
            index + 1,
            "{name}: one report is one call: {heard:?}"
        );
        assert_eq!(heard[index], (*name, size), "{name}: {heard:?}");
    }
}

fn moving() -> Modifier {
    Modifier.motion(MotionRole::Standard)
}

#[test]
fn fr24_1_text_reports_motion_finished() {
    assert_each_reports(
        |heard| {
            Text!(
                "settles",
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Text"))
            );
        },
        &[("Text", "Text")],
    );
}

#[test]
fn fr24_1_controls_report_motion_finished() {
    assert_each_reports(
        |heard| {
            Button!(
                "go",
                || {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Button"))
            );
            MenuItem!(
                "entry",
                || {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "MenuItem"))
            );
            Checkbox!(
                true,
                |_| {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Checkbox"))
            );
            RadioButton!(
                false,
                |_| {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "RadioButton"))
            );
            Switch!(
                true,
                |_| {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Switch"))
            );
            Slider!(
                0.5,
                |_| {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Slider"))
            );
            Chip!(
                "tag",
                || {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Chip"))
            );
            FloatingAction!(
                "add",
                || {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "FloatingAction"))
            );
            NavigationItem!(
                "home",
                || {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "NavigationItem"))
            );
        },
        &[
            ("Button", "Button"),
            // A menu item is a button on the wire.
            ("MenuItem", "Button"),
            ("Checkbox", "Checkbox"),
            ("RadioButton", "RadioButton"),
            ("Switch", "Switch"),
            ("Slider", "Slider"),
            ("Chip", "Chip"),
            ("FloatingAction", "FloatingAction"),
            ("NavigationItem", "NavigationItem"),
        ],
    );
}

#[test]
fn fr24_1_pickers_report_motion_finished() {
    assert_each_reports(
        |heard| {
            DatePicker!(
                0,
                |_| {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "DatePicker"))
            );
            TimePicker!(
                90,
                |_| {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "TimePicker"))
            );
            Dropdown!(
                0,
                |_| {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Dropdown")),
                {}
            );
        },
        &[
            ("DatePicker", "DatePicker"),
            ("TimePicker", "TimePicker"),
            ("Dropdown", "Dropdown"),
        ],
    );
}

#[test]
fn fr24_1_indicators_and_media_report_motion_finished() {
    assert_each_reports(
        |heard| {
            ProgressIndicator!(
                0.25,
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "ProgressIndicator"))
            );
            LinearProgressIndicator!(
                0.5,
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "LinearProgressIndicator"))
            );
            Divider!(
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Divider"))
            );
            Image!(
                1,
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Image"))
            );
            Icon!(
                2,
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Icon"))
            );
            Canvas!(
                DrawList::default(),
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Canvas"))
            );
            Badge!(
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Badge")),
                {}
            );
        },
        &[
            ("ProgressIndicator", "ProgressIndicator"),
            ("LinearProgressIndicator", "LinearProgressIndicator"),
            ("Divider", "Divider"),
            ("Image", "Image"),
            ("Icon", "Icon"),
            ("Canvas", "Canvas"),
            ("Badge", "Badge"),
        ],
    );
}

#[test]
fn fr24_1_overlays_report_motion_finished() {
    assert_each_reports(
        |heard| {
            Dialog!(
                true,
                || {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Dialog")),
                {}
            );
            Sheet!(
                true,
                || {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Sheet")),
                {}
            );
            Menu!(
                true,
                || {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Menu")),
                {}
            );
            Popover!(
                true,
                || {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Popover")),
                {}
            );
            Tooltip!(
                "hint",
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Tooltip")),
                {}
            );
        },
        &[
            ("Dialog", "Dialog"),
            ("Sheet", "Sheet"),
            ("Menu", "Menu"),
            ("Popover", "Popover"),
            ("Tooltip", "Tooltip"),
        ],
    );
}

#[test]
fn fr24_1_structure_reports_motion_finished() {
    assert_each_reports(
        |heard| {
            FlowRow!(
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "FlowRow")),
                {}
            );
            Tabs!(
                0,
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Tabs")),
                {}
            );
            TopAppBar!(
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "TopAppBar")),
                {}
            );
            Scaffold!(
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Scaffold")),
                {}
            );
            Navigation!(
                0,
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "Navigation")),
                {}
            );
            FileDropTarget!(
                |_| {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "FileDropTarget")),
                {}
            );
            SplitPane!(
                |_| {},
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "SplitPane")),
                {}
            );
        },
        &[
            ("FlowRow", "FlowRow"),
            ("Tabs", "Tabs"),
            ("TopAppBar", "TopAppBar"),
            ("Scaffold", "Scaffold"),
            ("Navigation", "Navigation"),
            ("FileDropTarget", "FileDropTarget"),
            ("SplitPane", "SplitPane"),
        ],
    );
}

#[test]
fn fr24_1_lazy_lists_report_motion_finished() {
    assert_each_reports(
        |heard| {
            LazyColumn!(
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "LazyColumn")),
                content = |_| {}
            );
            LazyRow!(
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "LazyRow")),
                content = |_| {}
            );
            LazyGrid!(
                modifier = moving(),
                on_motion_finished = Some(hears(heard, "LazyGrid")),
                content = |_| {}
            );
        },
        &[
            ("LazyColumn", "LazyColumn"),
            ("LazyRow", "LazyRow"),
            ("LazyGrid", "LazyGrid"),
        ],
    );
}
