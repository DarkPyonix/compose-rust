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

use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, ProtocolError};
use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind, Window};
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

const ON_CLICK: i64 = 1;
const ON_FIRST: i64 = 2;
const ON_SECOND: i64 = 3;

struct App {
    batch: Batch,
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

    fn render(&mut self) {
        if !self.dirty {
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

fn main() {
    LaunchBuilder::new().launch_runtime(|| {
        Box::new(App {
            batch: Batch::new(),
            clicks: 0,
            first: "first field: select, copy, paste, undo here".to_owned(),
            second: "second field".to_owned(),
            size: "not reported yet".to_owned(),
            dirty: false,
        }) as Box<dyn Runtime>
    });
}
