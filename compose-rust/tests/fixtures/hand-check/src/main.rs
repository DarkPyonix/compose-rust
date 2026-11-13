//! A window for checking a renderer by hand. Nothing in it is a test: a person uses it and
//! reads what it says, on screen and as `event:` lines on standard output.
//!
//! What is in the window, top to bottom:
//!
//! - a label counting clicks, and the button that counts them;
//! - two text fields, to see which has the keyboard, with what each holds echoed below it, so
//!   that what ctrl or cmd with C, V, X, Z and A did is on screen: a paste shows up as the new
//!   text, an undo as the text going back, a cut as the text shrinking;
//! - the window's size class and size, as the renderer last reported them (it reports when
//!   the class changes, not on every pixel of a resize);
//! - a scrolling list of 60 rows with Korean in them.
//!
//! For an input method, type Korean in either field and watch where the candidate window
//! opens: it should be beside the caret.
//!
//! The renderer comes from `COMPOSE_RUST_RENDERER_DIR`, as it does for any application.
//!
//! With `DXC_RESIZE_MARKER=1` the window shows something else: the marker scene, which the
//! Windows resize probe reads from a screen capture. A light field with a 2 dp green column
//! down its left and right edges and a 2 dp green row along its bottom edge, and between the
//! two columns a ruler, a dark 2 dp line every 24 dp from the left edge. Nothing else is in
//! it, so every pixel the probe looks at has a known meaning. A frame whose markers do not
//! touch the window's border shows an area the application did not draw; a marker of the
//! wrong width, or ruler lines off their 24 dp grid, shows a picture stretched to a size it
//! was not drawn for.

use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, ProtocolError};
use compose_rust::schema::{
    Color, EventPayload, Modifier, Paint, PropertyKind, WidgetKind, Window,
};
use compose_rust::{Batch, LaunchBuilder, Runtime};

const COLUMN: u32 = 1;
const CLICKS: u32 = 2;
const BUTTON: u32 = 3;
const FIRST: u32 = 4;
const FIRST_ECHO: u32 = 5;
const SECOND: u32 = 6;
const SECOND_ECHO: u32 = 7;
const SIZE: u32 = 8;
const LIST: u32 = 9;
const ROW0: u32 = 100;

// The marker scene's nodes, apart from the ruler's, which count up from RULER_MARK0.
const MARKER_ROOT: u32 = 1000;
const MARKER_BODY: u32 = 1001;
const MARKER_LEFT: u32 = 1002;
const MARKER_RULER: u32 = 1003;
const MARKER_RIGHT: u32 = 1004;
const MARKER_BOTTOM: u32 = 1005;
const RULER_MARK0: u32 = 2000;

/// The marker colour. Nothing else in the scene, the window's frame or the probe's magenta
/// backdrop is green, so the probe can find it by colour alone.
const MARKER_GREEN: u32 = 0x00ff00;
const FIELD_LIGHT: u32 = 0xe0e0e0;
const RULER_DARK: u32 = 0x404040;
/// Width of a marker and of a ruler line, and the ruler's period, in dp.
const MARKER_DP: f32 = 2.0;
const RULER_PERIOD_DP: f32 = 24.0;
/// Enough ruler periods for a window maximised on a 3840 pixel wide monitor at 100%. The
/// ruler is a row of fixed-width boxes, so the ones past the window's width get no room
/// and draw nothing.
const RULER_PERIODS: u32 = 160;

const ON_CLICK: i64 = 1;
const ON_FIRST: i64 = 2;
const ON_SECOND: i64 = 3;

struct App {
    batch: Batch,
    marker_scene: bool,
    clicks: u32,
    first: String,
    second: String,
    size: String,
    dirty: bool,
}

impl App {
    fn text(&mut self, node_id: u32, text: &str) {
        self.batch.write(Mutation::SetProp {
            node_id,
            property: PropertyKind::Text,
            value: PropertyValue::String(text),
        });
    }

    fn handler(&mut self, node_id: u32, property: PropertyKind, id: i64) {
        self.batch.write(Mutation::SetProp {
            node_id,
            property,
            value: PropertyValue::Integer(id),
        });
    }

    fn modifiers(&mut self, node_id: u32, modifiers: &[Modifier]) {
        for (index, modifier) in modifiers.iter().enumerate() {
            self.batch.write(Mutation::SetModifier {
                node_id,
                index: index as u16,
                modifier: modifier.clone(),
            });
        }
    }

    fn create(&mut self, node_id: u32, widget: WidgetKind, modifiers: &[Modifier]) {
        self.batch.write(Mutation::Create { node_id, widget });
        self.modifiers(node_id, modifiers);
    }

    fn insert(&mut self, parent_id: u32, children: &[u32]) {
        for (index, node_id) in children.iter().enumerate() {
            self.batch.write(Mutation::Insert {
                parent_id,
                node_id: *node_id,
                index: index as u32,
            });
        }
    }

    /// The scene the resize probe reads: markers on the left, right and bottom edges and a
    /// ruler between them, described at the top of this file.
    fn rebuild_marker_scene(&mut self) {
        let green = Modifier::Background(Paint::Literal(Color::rgb(MARKER_GREEN)));
        self.create(
            MARKER_ROOT,
            WidgetKind::Column,
            &[
                Modifier::FillMaxWidth,
                Modifier::FillMaxHeight,
                Modifier::Background(Paint::Literal(Color::rgb(FIELD_LIGHT))),
            ],
        );
        self.create(
            MARKER_BODY,
            WidgetKind::Row,
            &[Modifier::Weight(1.0), Modifier::FillMaxWidth],
        );
        self.create(
            MARKER_LEFT,
            WidgetKind::Box,
            &[
                Modifier::Width(MARKER_DP),
                Modifier::FillMaxHeight,
                green.clone(),
            ],
        );
        self.create(
            MARKER_RULER,
            WidgetKind::Row,
            &[Modifier::Weight(1.0), Modifier::FillMaxHeight],
        );
        self.create(
            MARKER_RIGHT,
            WidgetKind::Box,
            &[
                Modifier::Width(MARKER_DP),
                Modifier::FillMaxHeight,
                green.clone(),
            ],
        );
        self.create(
            MARKER_BOTTOM,
            WidgetKind::Box,
            &[Modifier::FillMaxWidth, Modifier::Height(MARKER_DP), green],
        );
        // Each period is a light gap and a dark line, so the first line starts 24 dp from
        // the window's left edge: the 2 dp marker and a 22 dp gap.
        let mut ruler = Vec::with_capacity(RULER_PERIODS as usize * 2);
        for period in 0..RULER_PERIODS {
            let gap = RULER_MARK0 + period * 2;
            let line = gap + 1;
            self.create(
                gap,
                WidgetKind::Box,
                &[
                    Modifier::Width(RULER_PERIOD_DP - MARKER_DP),
                    Modifier::FillMaxHeight,
                ],
            );
            self.create(
                line,
                WidgetKind::Box,
                &[
                    Modifier::Width(MARKER_DP),
                    Modifier::FillMaxHeight,
                    Modifier::Background(Paint::Literal(Color::rgb(RULER_DARK))),
                ],
            );
            ruler.push(gap);
            ruler.push(line);
        }
        self.insert(MARKER_RULER, &ruler);
        self.insert(MARKER_BODY, &[MARKER_LEFT, MARKER_RULER, MARKER_RIGHT]);
        self.insert(MARKER_ROOT, &[MARKER_BODY, MARKER_BOTTOM]);
    }
}

impl Runtime for App {
    fn batch(&self) -> &Batch {
        &self.batch
    }

    fn batch_mut(&mut self) -> &mut Batch {
        &mut self.batch
    }

    fn rebuild(&mut self) {
        let mut window = Window::new();
        window.title = "compose-rust hand check";
        window.width = 480;
        window.height = 700;
        window.min_width = 320;
        window.min_height = 320;
        window.resizable = true;
        self.batch.write(Mutation::SetWindow(window));
        if self.marker_scene {
            self.rebuild_marker_scene();
            return;
        }
        for (node_id, widget) in [
            (COLUMN, WidgetKind::Column),
            (CLICKS, WidgetKind::Text),
            (BUTTON, WidgetKind::Button),
            (FIRST, WidgetKind::TextField),
            (FIRST_ECHO, WidgetKind::Text),
            (SECOND, WidgetKind::TextField),
            (SECOND_ECHO, WidgetKind::Text),
            (SIZE, WidgetKind::Text),
            (LIST, WidgetKind::ScrollColumn),
        ] {
            self.batch.write(Mutation::Create { node_id, widget });
        }
        self.text(CLICKS, "clicks: 0");
        self.text(BUTTON, "Click me");
        self.text(FIRST, "first field: select, copy, paste, undo here");
        self.text(FIRST_ECHO, "first holds: first field: select, copy, paste, undo here");
        self.text(SECOND, "second field");
        self.text(SECOND_ECHO, "second holds: second field");
        self.text(SIZE, "window: not reported yet");
        self.handler(BUTTON, PropertyKind::OnClick, ON_CLICK);
        self.handler(FIRST, PropertyKind::OnValueChange, ON_FIRST);
        self.handler(SECOND, PropertyKind::OnValueChange, ON_SECOND);
        for row in 0..60u32 {
            self.batch.write(Mutation::Create {
                node_id: ROW0 + row,
                widget: WidgetKind::Text,
            });
            self.text(ROW0 + row, &format!("List row {row} \u{d55c}\u{ae00} \u{c785}\u{b825}"));
            self.batch.write(Mutation::Insert {
                parent_id: LIST,
                node_id: ROW0 + row,
                index: row,
            });
        }
        for (index, node_id) in [CLICKS, BUTTON, FIRST, FIRST_ECHO, SECOND, SECOND_ECHO, SIZE, LIST]
            .into_iter()
            .enumerate()
        {
            self.batch.write(Mutation::Insert {
                parent_id: COLUMN,
                node_id,
                index: index as u32,
            });
        }
    }

    fn render(&mut self, _frame_time_nanos: Option<u64>) {
        if !self.dirty || self.marker_scene {
            return;
        }
        self.dirty = false;
        let clicks = format!("clicks: {}", self.clicks);
        let first = format!("first holds: {}", self.first);
        let second = format!("second holds: {}", self.second);
        let size = format!("window: {}", self.size);
        self.text(CLICKS, &clicks);
        self.text(FIRST_ECHO, &first);
        self.text(SECOND_ECHO, &second);
        self.text(SIZE, &size);
    }

    fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        match (event.handler_id as i64, &event.payload) {
            (ON_CLICK, EventPayload::Clicked) => {
                self.clicks += 1;
                println!("event: click {}", self.clicks);
            }
            (ON_FIRST, EventPayload::TextChanged(text)) => {
                self.first = (*text).to_owned();
                println!("event: first {text}");
            }
            (ON_SECOND, EventPayload::TextChanged(text)) => {
                self.second = (*text).to_owned();
                println!("event: second {text}");
            }
            (
                _,
                EventPayload::WindowSizeChanged {
                    width_dp,
                    height_dp,
                    class,
                    ..
                },
            ) => {
                self.size = format!("{width_dp:.0} x {height_dp:.0} dp, {class:?}");
                println!("event: window {width_dp:.0}x{height_dp:.0} {class:?}");
            }
            _ => return Err(ProtocolError::InvalidValueKind(0)),
        }
        self.dirty = true;
        Ok(0)
    }
}

/// Whether `DXC_RESIZE_MARKER` asks for the marker scene: set, and neither empty nor `0`.
fn marker_scene_asked() -> bool {
    std::env::var("DXC_RESIZE_MARKER").is_ok_and(|value| !value.is_empty() && value != "0")
}

fn main() {
    let marker_scene = marker_scene_asked();
    if marker_scene {
        println!("hand-check: marker scene (DXC_RESIZE_MARKER)");
    }
    LaunchBuilder::new().launch_runtime(move || {
        Box::new(App {
            batch: Batch::new(),
            marker_scene,
            clicks: 0,
            first: "first field: select, copy, paste, undo here".to_owned(),
            second: "second field".to_owned(),
            size: "not reported yet".to_owned(),
            dirty: false,
        }) as Box<dyn Runtime>
    });
}
